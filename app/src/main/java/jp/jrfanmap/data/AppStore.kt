package jp.jrfanmap.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class AppStore(context: Context) {
    val database = TripDatabase(context).apply { recover() }
    val keys = KeyStore(context)
    private val mutable = MutableStateFlow(Telemetry())
    val telemetry = mutable.asStateFlow()
    fun update(block: (Telemetry) -> Telemetry) = mutable.update(block)
}
