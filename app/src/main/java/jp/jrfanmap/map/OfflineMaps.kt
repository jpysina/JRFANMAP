package jp.jrfanmap.map

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.*
import kotlin.math.*

data class DownloadArea(val bounds: LatLngBounds, val minZoom: Int, val maxZoom: Int, val tiles: Int)
data class OfflineState(val message: String = "尚未下载区域", val count: Int = 0, val busy: Boolean = false)

object TileBudget {
    fun count(north: Double, south: Double, east: Double, west: Double, min: Int, max: Int): Int {
        if (east <= west || north <= south || min < 0 || max > 16 || min > max) return Int.MAX_VALUE
        var total = 0L
        for (z in min..max) {
            val n = 2.0.pow(z)
            fun x(lon: Double) = floor((lon + 180) / 360 * n).toLong().coerceIn(0, n.toLong() - 1)
            fun y(lat: Double): Long {
                val r = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
                return floor((1 - ln(tan(r) + 1 / cos(r)) / Math.PI) / 2 * n).toLong().coerceIn(0, n.toLong() - 1)
            }
            total += (x(east) - x(west) + 1) * (y(south) - y(north) + 1)
        }
        return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}

class OfflineMaps(context: Context) {
    private val manager = OfflineManager.getInstance(context.applicationContext)
    private val stateValue = MutableStateFlow(OfflineState())
    val state = stateValue.asStateFlow()
    private var regions = listOf<OfflineRegion>()
    init {
        manager.setMaximumAmbientCacheSize(128L * 1024 * 1024, object : OfflineManager.FileSourceCallback {
            override fun onSuccess() = Unit
            override fun onError(message: String) { stateValue.value = stateValue.value.copy(message = "缓存配置失败：$message") }
        })
        reload()
    }
    fun reload() {
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(list: Array<OfflineRegion>?) {
                regions = list?.toList().orEmpty()
                stateValue.value = OfflineState("已保存 ${regions.size} 个区域（含未完成区域）", regions.size)
            }
            override fun onError(error: String) { stateValue.value = OfflineState("无法读取离线区域：$error") }
        })
    }
    fun download(area: DownloadArea) {
        if (stateValue.value.busy) return
        if (area.tiles > 1500 || regions.size >= 5) {
            stateValue.value = stateValue.value.copy(message = "请缩小地图范围（每区最多 1500 瓦片，最多 5 区）"); return
        }
        stateValue.value = stateValue.value.copy(busy = true, message = "创建离线区域…")
        val definition = OfflineTilePyramidRegionDefinition(STYLE, area.bounds, area.minZoom.toDouble(), area.maxZoom.toDouble(), 1f)
        manager.createOfflineRegion(definition, "GSI vector · local region".toByteArray(), object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(region: OfflineRegion) {
                regions = regions + region
                region.setObserver(object : OfflineRegion.OfflineRegionObserver {
                    override fun onStatusChanged(status: OfflineRegionStatus) {
                        val mb = status.completedResourceSize / 1024.0 / 1024.0
                        stateValue.value = OfflineState("${if (status.isComplete) "下载完成" else "下载中"} · ${status.completedResourceCount}/${status.requiredResourceCount} 资源 · %.1f MB".format(mb), regions.size, !status.isComplete)
                        if (status.isComplete) region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                    }
                    override fun onError(error: OfflineRegionError) {
                        region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                        stateValue.value = OfflineState("下载中断：${error.message}。可删除后重试。", regions.size)
                    }
                    override fun mapboxTileCountLimitExceeded(limit: Long) {
                        region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                        stateValue.value = OfflineState("离线瓦片配额已满，请删除区域", regions.size)
                    }
                })
                region.setDownloadState(OfflineRegion.STATE_ACTIVE)
            }
            override fun onError(error: String) { stateValue.value = stateValue.value.copy(busy = false, message = "创建失败：$error") }
        })
    }
    fun pause() {
        regions.forEach { it.setDownloadState(OfflineRegion.STATE_INACTIVE) }
        stateValue.value = stateValue.value.copy(busy = false, message = "下载已停止；未完成区域可删除后重新下载")
    }
    fun deleteAll() {
        pause()
        if (regions.isEmpty()) return
        val pending = regions.toMutableSet()
        regions.toList().forEach { region ->
            region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                override fun onDelete() { pending.remove(region); if (pending.isEmpty()) reload() }
                override fun onError(error: String) { stateValue.value = stateValue.value.copy(message = "删除失败：$error") }
            })
        }
    }
    companion object { const val STYLE = "asset://gsi-style.json" }
}
