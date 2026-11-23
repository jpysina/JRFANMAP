package jp.jrfanmap.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import jp.jrfanmap.RailApplication
import jp.jrfanmap.data.Trip
import jp.jrfanmap.rail.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class CatalogState(val info: CatalogInfo? = null, val busy: Boolean = true, val status: String = "正在准备内置铁路库…")

class RailViewModel(app: Application) : AndroidViewModel(app) {
    val store = (app as RailApplication).store
    val offline = store.offline
    private val matcher = LineMatcher(store.catalog)
    val message = MutableStateFlow("")
    val history = MutableStateFlow<List<Trip>>(emptyList())
    val catalog = MutableStateFlow(CatalogState())
    val live = MutableStateFlow(LiveMatch())
    var exportId: Long? = null

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
    }
    private suspend fun initializeCatalog() {
        catalog.value = try {
            CatalogState(withContext(Dispatchers.IO) { store.catalog.prepare() }, false, "内置铁路库可离线使用")
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            CatalogState(busy = false, status = "无法初始化铁路库，请检查存储空间后重试")
        }
    }
    fun updateCatalog(reinstall: Boolean = false) {
        if (catalog.value.busy) return
        val previous = catalog.value.info
        catalog.value = catalog.value.copy(busy = true, status = "检查更新…")
        viewModelScope.launch {
            try {
                val info = store.catalog.update(reinstall) { status -> catalog.update { it.copy(status = status) } }
                val result = when {
                    info.year != previous?.year -> "铁路库已更新"
                    reinstall -> "本年度铁路数据已重新下载并校验"
                    else -> "本机已使用最新年度数据"
                }
                catalog.value = CatalogState(info, false, result)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                val retained = withContext(Dispatchers.IO) { runCatching { store.catalog.info() }.getOrNull() }
                val stage = catalog.value.status.trimEnd('…')
                val cause = when (failure) {
                    is PublicCatalogHttpException -> "官方服务返回 HTTP ${failure.status}"
                    is java.net.SocketTimeoutException -> "连接超时"
                    is java.net.UnknownHostException, is java.net.ConnectException -> "无法连接国土交通省"
                    is javax.net.ssl.SSLException -> "无法验证 HTTPS 连接"
                    is java.util.zip.ZipException -> "数据包损坏或不完整"
                    else -> "数据格式或本机存储出错"
                }
                catalog.value = CatalogState(retained?.takeIf { it.year > 0 } ?: previous, false, "$stage 失败 · $cause。已保留原铁路库。")
            }
        }
    }
    suspend fun visibleRail(bounds: RailBounds, includeStations: Boolean): Pair<List<RailSection>, List<RailStation>> =
        withContext(Dispatchers.IO) {
            if (catalog.value.info == null) emptyList<RailSection>() to emptyList()
            else store.catalog.sections(bounds) to if (includeStations) store.catalog.stations(bounds) else emptyList()
        }
    suspend fun line(id: Long): RailNetwork? = withContext(Dispatchers.IO) { store.catalog.network(id) }
    suspend fun lines(ids: List<Long>): List<RailLine> = withContext(Dispatchers.IO) { store.catalog.lines(ids) }
    suspend fun tripSpeeds(id: Long) = withContext(Dispatchers.IO) { store.database.speedHistory(id) }

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
