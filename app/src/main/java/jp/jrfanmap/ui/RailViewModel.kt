package jp.jrfanmap.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import jp.jrfanmap.RailApplication
import jp.jrfanmap.data.Trip
import jp.jrfanmap.map.OfflineMaps
import jp.jrfanmap.rail.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class CatalogState(val info: CatalogInfo? = null, val busy: Boolean = true, val status: String = "正在准备内置铁路库…")
data class AlertState(val url: String = "", val snapshot: AlertSnapshot? = null, val busy: Boolean = false, val error: String? = null)

class RailViewModel(app: Application) : AndroidViewModel(app) {
    val store = (app as RailApplication).store
    val offline = OfflineMaps(app)
    private val alerts = OperatorUpdates(app)
    private val preferences = app.getSharedPreferences("rail_sources", 0)
    private val matcher = LineMatcher(store.catalog)
    val message = MutableStateFlow("")
    val history = MutableStateFlow<List<Trip>>(emptyList())
    val catalog = MutableStateFlow(CatalogState())
    val live = MutableStateFlow(LiveMatch())
    val operator = MutableStateFlow(AlertState(url = preferences.getString("gtfs_alerts", "").orEmpty()))
    var exportId: Long? = null
    private var alertJob: Job? = null
    private var foregroundRefresh: Job? = null
    private var lastAlertRequest = 0L

    init {
        refreshTrips()
        viewModelScope.launch { initializeCatalog() }
        viewModelScope.launch {
            var activeYear = 0
            combine(store.telemetry.map { it.samples }.distinctUntilChanged(), catalog.map { it.info?.year ?: 0 }.distinctUntilChanged()) {
                samples, year -> samples to year
            }.conflate().flowOn(Dispatchers.Default).collect { (samples, year) ->
                live.value = withContext(Dispatchers.IO) {
                    if (year != activeYear || samples.isEmpty()) {
                        matcher.reset()
                        activeYear = year
                    }
                    if (year == 0) LiveMatch(reason = "铁路库正在准备")
                    else runCatching { matcher.match(samples) }.getOrElse { LiveMatch(reason = "暂时无法读取离线线路") }
                }
            }
        }
        loadCachedAlert()
    }
    private suspend fun initializeCatalog() {
        catalog.value = try {
            CatalogState(withContext(Dispatchers.IO) { store.catalog.prepare() }, false, "内置铁路库可离线使用")
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            CatalogState(busy = false, status = "无法初始化铁路库，请检查存储空间后重试")
        }
    }
    fun updateCatalog() {
        if (catalog.value.busy) return
        val previous = catalog.value.info
        catalog.value = catalog.value.copy(busy = true, status = "检查更新…")
        viewModelScope.launch {
            try {
                val info = store.catalog.update { status -> catalog.update { it.copy(status = status) } }
                catalog.value = CatalogState(info, false, if (info.year == previous?.year) "本机已使用最新可识别版本" else "铁路库已更新")
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                val retained = withContext(Dispatchers.IO) { runCatching { store.catalog.info() }.getOrNull() }
                catalog.value = CatalogState(retained?.takeIf { it.year > 0 } ?: previous, false,
                    "更新失败，保留已安装数据。请检查网络后重试。")
            }
        }
    }
    suspend fun visibleRail(bounds: RailBounds, includeStations: Boolean): Pair<List<RailSection>, List<RailStation>> =
        withContext(Dispatchers.IO) {
            if (catalog.value.info == null) emptyList<RailSection>() to emptyList()
            else store.catalog.sections(bounds) to if (includeStations) store.catalog.stations(bounds) else emptyList()
        }
    suspend fun line(id: Long): RailNetwork? = withContext(Dispatchers.IO) { store.catalog.network(id) }

    private fun loadCachedAlert() {
        val url = operator.value.url
        if (url.isBlank()) return
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { runCatching { alerts.cached(url) }.getOrNull() }
            operator.update { if (it.url == url && it.snapshot == null) it.copy(snapshot = cached) else it }
        }
    }
    fun setForeground(visible: Boolean) {
        if (!visible) {
            foregroundRefresh?.cancel()
            foregroundRefresh = null
        } else if (foregroundRefresh == null) {
            foregroundRefresh = viewModelScope.launch {
                operator.map { it.url }.distinctUntilChanged().collectLatest { url ->
                    if (url.isBlank()) return@collectLatest
                    while (true) {
                        if (store.telemetry.value.network) refreshAlerts(automatic = true)
                        delay(60_000)
                    }
                }
            }
        }
    }
    fun saveAlertUrl(value: String) {
        val url = value.trim()
        if (url.isNotBlank() && runCatching { OperatorUpdates.validateFeedUrl(url) }.isFailure) {
            message.value = "请输入没有查询参数的公开 HTTPS 公告源地址"
            return
        }
        if (url == operator.value.url) return
        alertJob?.cancel()
        lastAlertRequest = 0
        preferences.edit().putString("gtfs_alerts", url).apply()
        operator.value = AlertState(url)
        loadCachedAlert()
    }
    fun refreshAlerts(automatic: Boolean = false) {
        val state = operator.value
        if (state.url.isBlank() || state.busy) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastAlertRequest != 0L && now - lastAlertRequest < 60_000) {
            if (!automatic) message.value = "请间隔至少一分钟刷新"
            return
        }
        lastAlertRequest = now
        operator.value = state.copy(busy = true, error = null)
        alertJob = viewModelScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) { alerts.refresh(state.url) }
                operator.value = state.copy(snapshot = snapshot)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                operator.value = state.copy(error = "公告刷新失败。请核对公告源和网络。")
            }
        }
    }
    fun clearAlerts() {
        if (operator.value.busy) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { alerts.clear() }
            operator.update { it.copy(snapshot = null, error = null) }
        }
    }

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
}
