package jp.jrfanmap.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import jp.jrfanmap.RailApplication
import jp.jrfanmap.data.*
import jp.jrfanmap.map.OfflineMaps
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

data class RailState(val loading: Boolean = false, val notices: List<RailNotice> = emptyList(), val error: String? = null, val fetched: Long? = null)
class RailViewModel(app: Application) : AndroidViewModel(app) {
    val store = (app as RailApplication).store
    val offline = OfflineMaps(app)
    val message = MutableStateFlow("")
    val history = MutableStateFlow<List<Trip>>(emptyList())
    val rail = MutableStateFlow(RailState())
    val hasKey = MutableStateFlow(store.keys.read().isNotBlank())
    var exportId: Long? = null
    private var lastRequest = 0L
    init { refreshTrips() }
    fun refreshTrips() { viewModelScope.launch(Dispatchers.IO) { history.value = store.database.list() } }
    fun delete(id: Long) { viewModelScope.launch(Dispatchers.IO) {
        if (store.telemetry.value.tripId == id && store.telemetry.value.recording) { message.value = "请先停止记录"; return@launch }
        runCatching { store.database.delete(id); history.value = store.database.list() }.onFailure { message.value = "删除失败" }
    } }
    fun export(uri: Uri) { val id = exportId ?: return; viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            val output = getApplication<Application>().contentResolver.openOutputStream(uri) ?: error("无法打开文件")
            store.database.exportCsv(id, output)
        }.onSuccess { message.value = "CSV 已导出" }.onFailure { message.value = "导出失败，请检查目标文件权限和空间" }
    } }
    fun saveKey(key: String) {
        runCatching { store.keys.save(key); hasKey.value = key.isNotBlank() }
            .onSuccess { message.value = if (key.isBlank()) "API Key 已移除" else "API Key 已加密保存在本机" }
            .onFailure { message.value = "API Key 保存失败，请重试" }
    }
    fun refreshRail(automatic: Boolean = false) {
        if (rail.value.loading) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastRequest != 0L && now - lastRequest < 60_000) { if (!automatic) message.value = "请间隔至少 60 秒刷新"; return }
        lastRequest = now
        viewModelScope.launch {
            rail.value = rail.value.copy(loading = true, error = null)
            runCatching { RailRepository().fetch(store.keys.read()) }
                .onSuccess { rail.value = RailState(notices = it, fetched = System.currentTimeMillis()) }
                .onFailure { rail.value = rail.value.copy(loading = false, error = if (it is IllegalStateException || it is IllegalArgumentException) it.message else "无法连接 ODPT，请检查网络；旧信息不代表当前状态") }
        }
    }
}
