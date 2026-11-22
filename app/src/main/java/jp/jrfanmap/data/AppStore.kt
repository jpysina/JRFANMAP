package jp.jrfanmap.data

import android.content.Context
import jp.jrfanmap.location.DeviceMonitor
import jp.jrfanmap.map.OfflineMaps
import jp.jrfanmap.map.JapanBoundary
import jp.jrfanmap.rail.RailCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class AppStore(context: Context) {
    val database = TripDatabase(context).apply { recover() }
    val catalog = RailCatalog(context).apply { setWriteAheadLoggingEnabled(true) }
    val offline by lazy { OfflineMaps(context) }
    internal val japanBoundary by lazy {
        context.assets.open("japan-boundary.geojson").bufferedReader().use { JapanBoundary.fromGeoJson(it.readText()) }
    }
    private val mutable = MutableStateFlow(Telemetry())
    val telemetry = mutable.asStateFlow()
    val monitor = DeviceMonitor(context, this)
    fun update(block: (Telemetry) -> Telemetry) = mutable.update(block)
}
