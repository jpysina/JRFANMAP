package jp.jrfanmap.location

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.*
import android.location.*
import android.net.*
import android.os.*
import jp.jrfanmap.MainActivity
import jp.jrfanmap.R
import jp.jrfanmap.RailApplication
import jp.jrfanmap.data.*
import kotlinx.coroutines.*
import kotlin.math.sqrt

class TrackingService : Service(), LocationListener, SensorEventListener {
    private val store get() = (application as RailApplication).store
    private lateinit var locations: LocationManager
    private lateinit var sensors: SensorManager
    private lateinit var connectivity: ConnectivityManager
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private var trip: Long? = null
    private var previous: Fix? = null
    private var gnssRegistered = false
    private var networkRegistered = false
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { updateNetwork() }
        override fun onLost(network: Network) { updateNetwork() }
    }
    private fun updateNetwork() {
        val online = connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        store.update { it.copy(network = online) }
    }
    private val gnss = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(s: GnssStatus) {
            val list = (0 until s.satelliteCount).map { i ->
                Satellite(when (s.getConstellationType(i)) {
                    GnssStatus.CONSTELLATION_GPS -> "GPS"; GnssStatus.CONSTELLATION_GALILEO -> "GALILEO"
                    GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"; GnssStatus.CONSTELLATION_BEIDOU -> "北斗"
                    GnssStatus.CONSTELLATION_QZSS -> "QZSS"; else -> "其他"
                }, s.getSvid(i), s.getCn0DbHz(i), s.usedInFix(i))
            }
            store.update { it.copy(satellites = list) }
        }
        override fun onStopped() { store.update { it.copy(satellites = emptyList()) } }
    }
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        locations = getSystemService(LocationManager::class.java)
        sensors = getSystemService(SensorManager::class.java)
        connectivity = getSystemService(ConnectivityManager::class.java)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (trip != null) return START_NOT_STICKY
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            store.update { it.copy(message = "需要精确定位权限才能记录") }; stopSelf(); return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("tracking", "行程记录", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, TrackingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "tracking").setSmallIcon(R.drawable.ic_train)
            .setContentTitle("JRFANMAP · 正在记录行程").setContentText("锁屏后继续定位 · 点击返回仪表")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "停止记录", stop).build()).build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(1, notification)
            trip = store.database.begin()
            store.update { Telemetry(recording = true, tripId = trip, message = "等待卫星 / 网络定位", startedAt = System.currentTimeMillis()) }
            var count = 0
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (locations.allProviders.contains(provider) && locations.isProviderEnabled(provider)) {
                    locations.requestLocationUpdates(provider, 1000L, 0f, this, Looper.getMainLooper()); count++
                }
            }
            if (count == 0) { store.update { it.copy(message = "系统定位已关闭，请打开定位后重新开始") }; stopSelf(); return START_NOT_STICKY }
            gnssRegistered = locations.registerGnssStatusCallback(gnss, Handler(Looper.getMainLooper()))
            listOf(Sensor.TYPE_PRESSURE, Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_LINEAR_ACCELERATION).forEach { type ->
                sensors.getDefaultSensor(type)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
            }
            connectivity.registerDefaultNetworkCallback(networkCallback); networkRegistered = true; updateNetwork()
        } catch (_: SecurityException) {
            store.update { it.copy(message = "定位权限已撤销，请重新授权") }; stopSelf()
        } catch (_: Exception) {
            store.update { it.copy(message = "无法启动记录，请检查系统定位与存储空间") }; stopSelf()
        }
        return START_NOT_STICKY
    }
    override fun onLocationChanged(location: Location) {
        val id = trip ?: return
        val f = Fix(location.latitude, location.longitude, if (location.hasAltitude()) location.altitude else null,
            if (location.hasSpeed()) location.speed else null, if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond else null,
            if (location.hasBearing()) location.bearing else null, location.accuracy,
            if (location.hasVerticalAccuracy()) location.verticalAccuracyMeters else null,
            location.provider ?: "unknown", location.time, location.elapsedRealtimeNanos,
            if (Build.VERSION.SDK_INT >= 31) location.isMock else @Suppress("DEPRECATION") location.isFromMockProvider)
        if (!FixPolicy.fresh(f, SystemClock.elapsedRealtimeNanos()) || f.elapsedNanos <= (previous?.elapsedNanos ?: 0)) return
        // Network fixes must not replace a fresh and more precise GNSS fix.
        previous?.let { if (it.provider == "gps" && f.provider != "gps" && FixPolicy.fresh(it, f.elapsedNanos) && f.accuracy > it.accuracy) return }
        val segment = FixPolicy.segment(previous, f)
        previous = f
        store.update { it.copy(fix = f, message = if (f.mock) "模拟定位数据" else "正在接收 ${f.provider.uppercase()} 定位",
            distanceM = it.distanceM + segment,
            maxSpeedKmh = if (!f.mock && f.accuracy <= 50) maxOf(it.maxSpeedKmh, (f.speedMps ?: 0f) * 3.6f) else it.maxSpeedKmh,
            path = (it.path + f).takeLast(3600)) }
        writer.launch { runCatching { store.database.add(id, f, segment) }.onFailure {
            store.update { it.copy(message = "轨迹写入失败，记录已停止，请检查存储空间") }
            withContext(Dispatchers.Main) { stopSelf() }
        } }
    }
    override fun onProviderDisabled(provider: String) { store.update { it.copy(message = "$provider 定位已关闭；等待可用位置") } }
    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_PRESSURE -> store.update { it.copy(pressureHpa = event.values[0]) }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                val a = sqrt(event.values.take(3).sumOf { (it * it).toDouble() }).toFloat()
                store.update { it.copy(acceleration = a) }
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                val r = FloatArray(9); val angles = FloatArray(3)
                SensorManager.getRotationMatrixFromVector(r, event.values); SensorManager.getOrientation(r, angles)
                val heading = ((Math.toDegrees(angles[0].toDouble()) + 360) % 360).toFloat()
                store.update { it.copy(magneticHeading = heading) }
            }
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    override fun onDestroy() {
        runCatching { locations.removeUpdates(this) }
        if (gnssRegistered) runCatching { locations.unregisterGnssStatusCallback(gnss) }
        sensors.unregisterListener(this)
        if (networkRegistered) runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        val id = trip; trip = null
        store.update { it.copy(recording = false, satellites = emptyList()) }
        writer.launch { try { if (id != null) store.database.finish(id) } finally { writer.cancel() } }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    companion object { const val ACTION_STOP = "jp.jrfanmap.STOP" }
}
