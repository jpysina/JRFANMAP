package jp.jrfanmap.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import jp.jrfanmap.data.*
import kotlin.math.sqrt

/** One device subscription, shared by the visible Activity and the recording service. */
class DeviceMonitor(private val context: Context, private val store: AppStore) : LocationListener, SensorEventListener {
    private val locations = context.getSystemService(LocationManager::class.java)
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var visible = false
    private var running = false
    private var subscribed = false
    private var fixCallback: ((Fix) -> Unit)? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = updateNetwork()
        override fun onLost(network: Network) = updateNetwork()
    }
    private fun updateNetwork() {
        val online = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        store.update { it.copy(network = online) }
    }

    private val nmea = OnNmeaMessageListener { sentence, _ ->
        val fields = sentence.substringBefore('*').trim().split(',')
        if (fields.firstOrNull()?.endsWith("GSA") == true && fields.size >= 18) {
            store.update { it.copy(dop = Dilution(fields[15].toFloatOrNull(), fields[16].toFloatOrNull(), fields[17].toFloatOrNull())) }
        }
    }
    private val raw = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
            store.update { it.copy(rawMeasurements = event.measurements.size) }
        }
    }
    private val gnss = object : GnssStatus.Callback() {
        override fun onFirstFix(ttffMillis: Int) { store.update { it.copy(firstFixMillis = ttffMillis) } }
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val list = (0 until status.satelliteCount).map { i ->
                val system = when (status.getConstellationType(i)) {
                    GnssStatus.CONSTELLATION_GPS -> "GPS"
                    GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
                    GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
                    GnssStatus.CONSTELLATION_BEIDOU -> "北斗"
                    GnssStatus.CONSTELLATION_QZSS -> "QZSS"
                    GnssStatus.CONSTELLATION_SBAS -> "SBAS"
                    GnssStatus.CONSTELLATION_IRNSS -> "NavIC"
                    else -> "其他"
                }
                Satellite(system, status.getSvid(i), status.getCn0DbHz(i), status.usedInFix(i),
                    status.getElevationDegrees(i), status.getAzimuthDegrees(i),
                    if (status.hasCarrierFrequencyHz(i)) status.getCarrierFrequencyHz(i) / 1e6f else null,
                    status.hasEphemerisData(i), status.hasAlmanacData(i),
                    if (Build.VERSION.SDK_INT >= 30 && status.hasBasebandCn0DbHz(i)) status.getBasebandCn0DbHz(i) else null)
            }
            store.update { it.copy(satellites = list) }
        }
        override fun onStopped() { clearGnss() }
    }
    private fun clearGnss() { store.update { it.copy(satellites = emptyList(), dop = null, rawMeasurements = null, firstFixMillis = null) } }

    fun visible(value: Boolean) { visible = value; reconcile() }
    fun record(callback: ((Fix) -> Unit)?) { fixCallback = callback; reconcile() }

    private fun reconcile() {
        val wanted = visible || fixCallback != null
        if (wanted && !running) {
            running = true
            listOf(Sensor.TYPE_PRESSURE, Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_LINEAR_ACCELERATION,
                Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_GYROSCOPE).forEach { type ->
                sensors.getDefaultSensor(type)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI, main) }
            }
            runCatching { connectivity.registerDefaultNetworkCallback(networkCallback, main) }
            updateNetwork()
        } else if (!wanted && running) {
            running = false
            sensors.unregisterListener(this)
            runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
            store.update { it.copy(samples = emptyList(), active = false) }
        }
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if ((!wanted || !fine && !coarse) && subscribed) unsubscribe()
        if (!wanted) return
        if (!fine && !coarse) {
            store.update { it.copy(active = false, message = "请授予定位权限") }
            return
        }
        // Refresh on resume or permission changes so a newly granted precise permission enables GNSS.
        if (subscribed && precise != fine) unsubscribe()
        if (!subscribed) {
            precise = fine
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (!fine && provider == LocationManager.GPS_PROVIDER) continue
                if (provider in locations.allProviders) {
                    runCatching { locations.requestLocationUpdates(provider, 1000L, 0f, this, Looper.getMainLooper()) }
                }
            }
            if (fine) {
                runCatching { locations.registerGnssStatusCallback(gnss, main) }
                runCatching { locations.registerGnssMeasurementsCallback(raw, main) }
                runCatching { locations.addNmeaListener(nmea, main) }
            }
            subscribed = true
        }
        updateProviderStatus()
    }

    private var precise = false
    private fun unsubscribe() {
        subscribed = false
        runCatching { locations.removeUpdates(this) }
        if (precise) {
            runCatching { locations.unregisterGnssStatusCallback(gnss) }
            runCatching { locations.unregisterGnssMeasurementsCallback(raw) }
            runCatching { locations.removeNmeaListener(nmea) }
        }
        clearGnss()
    }

    private fun updateProviderStatus() {
        val gps = precise && locations.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val network = locations.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        val message = when {
            !gps && !network -> "系统定位不可用，请检查设置"
            !precise -> "仅获准使用大致位置"
            else -> "等待卫星 / 网络定位"
        }
        store.update { it.copy(active = subscribed, message = message) }
    }

    override fun onProviderDisabled(provider: String) = updateProviderStatus()
    override fun onProviderEnabled(provider: String) = updateProviderStatus()
    @Deprecated("Legacy callbacks on Android 8–10")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit

    override fun onLocationChanged(location: Location) {
        if (!subscribed) return
        val fix = Fix(location.latitude, location.longitude, location.altitude.takeIf { location.hasAltitude() },
            location.speed.takeIf { location.hasSpeed() }, location.speedAccuracyMetersPerSecond.takeIf { location.hasSpeedAccuracy() },
            location.bearing.takeIf { location.hasBearing() }, location.accuracy,
            location.verticalAccuracyMeters.takeIf { location.hasVerticalAccuracy() }, location.provider ?: "unknown",
            location.time, location.elapsedRealtimeNanos,
            if (Build.VERSION.SDK_INT >= 31) location.isMock else @Suppress("DEPRECATION") location.isFromMockProvider,
            location.bearingAccuracyDegrees.takeIf { location.hasBearingAccuracy() },
            if (Build.VERSION.SDK_INT >= 34 && location.hasMslAltitude()) location.mslAltitudeMeters else null,
            if (Build.VERSION.SDK_INT >= 34 && location.hasMslAltitudeAccuracy()) location.mslAltitudeAccuracyMeters else null)
        val current = store.telemetry.value
        if (!FixPolicy.fresh(fix, SystemClock.elapsedRealtimeNanos()) || fix.elapsedNanos <= (current.fix?.elapsedNanos ?: 0)) return
        current.fix?.let {
            if (it.provider == "gps" && fix.provider != "gps" && FixPolicy.fresh(it, fix.elapsedNanos) && fix.accuracy >= it.accuracy) return
        }
        store.update { it.copy(fix = fix, samples = (it.samples + fix).takeLast(120),
            message = if (fix.mock) "模拟定位数据" else "正在接收 ${fix.provider.uppercase()} 定位") }
        fixCallback?.invoke(fix)
    }

    private val lastSensorTime = mutableMapOf<Int, Long>()
    override fun onSensorChanged(event: SensorEvent) {
        val type = event.sensor.type
        if (event.timestamp - (lastSensorTime[type] ?: 0) < 500_000_000) return
        lastSensorTime[type] = event.timestamp
        fun magnitude() = sqrt(event.values.take(3).sumOf { (it * it).toDouble() }).toFloat()
        when (type) {
            Sensor.TYPE_PRESSURE -> store.update { it.copy(pressureHpa = event.values[0]) }
            Sensor.TYPE_LINEAR_ACCELERATION -> store.update { it.copy(acceleration = magnitude()) }
            Sensor.TYPE_MAGNETIC_FIELD -> store.update { it.copy(magneticField = magnitude()) }
            Sensor.TYPE_GYROSCOPE -> store.update { it.copy(angularSpeed = magnitude()) }
            Sensor.TYPE_ROTATION_VECTOR -> {
                val matrix = FloatArray(9)
                val angles = FloatArray(3)
                SensorManager.getRotationMatrixFromVector(matrix, event.values)
                SensorManager.getOrientation(matrix, angles)
                val heading = ((Math.toDegrees(angles[0].toDouble()) + 360) % 360).toFloat()
                store.update { it.copy(magneticHeading = heading) }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
