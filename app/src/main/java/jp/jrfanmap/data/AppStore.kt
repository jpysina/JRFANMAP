package jp.jrfanmap.data

import android.content.Context
import jp.jrfanmap.location.DeviceMonitor
import jp.jrfanmap.rail.RailCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class AppStore(context: Context) {
    val database = TripDatabase(context).apply { recover() }
    val catalog = RailCatalog(context).apply { setWriteAheadLoggingEnabled(true) }
    private val mutable = MutableStateFlow(Telemetry())
    val telemetry = mutable.asStateFlow()
    val monitor = DeviceMonitor(context, this)
    fun update(block: (Telemetry) -> Telemetry) = mutable.update(block)
}
