package jp.jrfanmap.map

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import jp.jrfanmap.rail.readNBytesBounded
import org.maplibre.android.geometry.LatLngBounds
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import kotlin.math.*

data class DownloadArea(val bounds: LatLngBounds, val minZoom: Int, val maxZoom: Int, val tiles: Int)
data class GsiRegion(val id: String, val south: Double, val west: Double, val north: Double,
                     val east: Double, val minZoom: Int, val maxZoom: Int, val tileUrl: String)
data class OfflineState(val message: String = "正在读取离线地图…", val regions: List<GsiRegion> = emptyList(), val busy: Boolean = true) {
    val count get() = regions.size
}
internal data class Tile(val zoom: Int, val x: Int, val y: Int) {
    val path get() = "${zoom}/${x}/${y}.png"
}

object TileBudget {
    const val LIMIT = 600
    private fun grid(north: Double, south: Double, east: Double, west: Double, min: Int, max: Int):
        List<Triple<Int, IntRange, IntRange>>? {
        if (!listOf(north, south, east, west).all(Double::isFinite) ||
            west < -180 || east > 180 || east <= west || north <= south || min !in 0..16 || max !in min..16) return null
        return (min..max).map { z ->
            val n = 1 shl z
            fun x(lon: Double) = floor((lon + 180) / 360 * n).toInt().coerceIn(0, n - 1)
            fun y(lat: Double): Int {
                val r = Math.toRadians(lat.coerceIn(-85.05112878, 85.05112878))
                return floor((1 - ln(tan(r) + 1 / cos(r)) / Math.PI) / 2 * n).toInt().coerceIn(0, n - 1)
            }
            Triple(z, x(west)..x(east), y(north)..y(south))
        }
    }

    fun count(north: Double, south: Double, east: Double, west: Double, min: Int, max: Int): Int =
        grid(north, south, east, west, min, max)?.sumOf { (_, xs, ys) ->
            (xs.last - xs.first + 1L) * (ys.last - ys.first + 1L)
        }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: Int.MAX_VALUE

    internal fun tiles(north: Double, south: Double, east: Double, west: Double, min: Int, max: Int): List<Tile> {
        require(count(north, south, east, west, min, max) in 1..LIMIT) { "地图范围过大，请放大后重试" }
        return grid(north, south, east, west, min, max)!!.flatMap { (z, xs, ys) ->
            xs.flatMap { x -> ys.map { y -> Tile(z, x, y) } }
        }
    }
}

internal const val GSI_STANDARD = "https://maps.gsi.go.jp/xyz/std/"
internal const val GSI_ATTRIBUTION = """<a href="https://maps.gsi.go.jp/development/ichiran.html">国土地理院・地理院タイル（標準地図）</a>"""
private const val REGIONS_LIMIT = 5
private const val REGION_SIZE_LIMIT = 128L * 1024 * 1024

/** Saves complete regions as local tile pyramids. A failed or canceled transfer never becomes visible. */
class OfflineMaps(context: Context) {
    private val root = File(context.filesDir, "gsi-standard")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val stateValue = MutableStateFlow(OfflineState())
    val state = stateValue.asStateFlow()
    private var operation: Job? = null

    init {
        scope.launch {
            val regions = withContext(Dispatchers.IO) {
                root.listFiles()?.filter { it.name.startsWith(".downloading-") }?.forEach(File::deleteRecursively)
                readRegions()
            }
            stateValue.value = OfflineState(summary(regions), regions, false)
        }
    }

    fun download(area: DownloadArea) {
        val previous = stateValue.value
        if (previous.busy) return
        if (area.tiles !in 1..TileBudget.LIMIT || previous.count >= REGIONS_LIMIT) {
            stateValue.value = previous.copy(message = "请缩小下载范围或删除已有区域（最多 ${REGIONS_LIMIT} 区）")
            return
        }
        stateValue.value = previous.copy(busy = true, message = "正在准备地理院标准地图…")
        operation = scope.launch {
            val id = UUID.randomUUID().toString()
            val staging = File(root, ".downloading-$id")
            val region = File(root, id)
            val bounds = area.bounds
            val result = try {
                withContext(Dispatchers.IO) {
                    val tiles = TileBudget.tiles(bounds.latitudeNorth, bounds.latitudeSouth, bounds.longitudeEast,
                        bounds.longitudeWest, area.minZoom, area.maxZoom)
                    check(staging.mkdirs()) { "无法创建地图缓存，请检查存储空间" }
                    var bytes = 0L
                    var downloaded = 0
                    for ((index, tile) in tiles.withIndex()) {
                        ensureActive()
                        val image = readGsiTile(tile)
                        if (image != null) {
                            bytes += image.size
                            check(bytes <= REGION_SIZE_LIMIT) { "下载超过 128 MiB，请缩小范围" }
                            val file = File(staging, tile.path)
                            file.parentFile?.mkdirs()
                            file.writeBytes(image)
                            downloaded++
                        }
                        stateValue.value = previous.copy(busy = true, message = "下载地图 ${index + 1}/${tiles.size} · ${bytes / 1048576} MiB")
                    }
                    check(downloaded > 0) { "所选范围没有地理院标准地图，请移到日本陆地附近" }
                    File(staging, "region.json").writeText(JSONObject()
                        .put("north", bounds.latitudeNorth).put("south", bounds.latitudeSouth)
                        .put("west", bounds.longitudeWest).put("east", bounds.longitudeEast)
                        .put("min", area.minZoom).put("max", area.maxZoom).toString())
                    ensureActive()
                    check(staging.renameTo(region)) { "无法保存离线区域，请检查存储空间" }
                    summary(readRegions())
                }
            } catch (_: CancellationException) {
                "下载已取消"
            } catch (error: Exception) {
                "地图下载失败 · ${downloadError(error)}"
            }
            withContext(NonCancellable) {
                val regions = withContext(Dispatchers.IO) { staging.deleteRecursively(); readRegions() }
                stateValue.value = OfflineState(result, regions, false)
                operation = null
            }
        }
    }

    fun pause() { operation?.cancel() }

    fun deleteAll() {
        val state = stateValue.value
        if (state.busy || state.count == 0) return
        stateValue.value = state.copy(busy = true, message = "正在删除离线地图…")
        scope.launch {
            val (deleted, regions) = withContext(Dispatchers.IO) {
                val results = state.regions.map { File(root, it.id).deleteRecursively() }
                results.all { it } to readRegions()
            }
            stateValue.value = OfflineState(if (deleted) summary(regions) else "有区域未能删除，请重试", regions, false)
        }
    }

    private fun readRegions(): List<GsiRegion> = root.listFiles().orEmpty().mapNotNull { folder ->
        if (!folder.isDirectory || folder.name.startsWith('.')) return@mapNotNull null
        runCatching {
            val data = JSONObject(File(folder, "region.json").readText())
            GsiRegion(folder.name, data.getDouble("south"), data.getDouble("west"), data.getDouble("north"),
                data.getDouble("east"), data.getInt("min"), data.getInt("max"),
                folder.toURI().toASCIIString().replaceFirst("file:/", "file:///") + "{z}/{x}/{y}.png")
        }.getOrNull()
    }.sortedBy { it.id }

    private fun summary(regions: List<GsiRegion>) =
        if (regions.isEmpty()) "尚未下载区域" else "已保存 ${regions.size} 个离线区域"
}

internal class PublicTileHttpException(val code: Int) : IOException("HTTP $code")
private fun downloadError(error: Exception) = when (error) {
    is PublicTileHttpException -> "地理院返回 HTTP ${error.code}，请稍后重试"
    is java.net.SocketTimeoutException -> "连接地理院超时，请检查网络"
    is java.net.UnknownHostException, is java.net.ConnectException -> "无法连接地理院，请检查网络"
    is javax.net.ssl.SSLException -> "无法验证地理院 HTTPS 连接"
    else -> error.message ?: "写入或网络连接出错"
}

private fun readGsiTile(tile: Tile): ByteArray? {
    val connection = URI(GSI_STANDARD + tile.path).toURL().openConnection() as HttpURLConnection
    connection.connectTimeout = 12_000
    connection.readTimeout = 15_000
    connection.instanceFollowRedirects = false
    connection.setRequestProperty("User-Agent", "JRFANMAP/0.2.1 Android")
    connection.setRequestProperty("Accept", "image/png")
    return try {
        when (val status = connection.responseCode) {
            200 -> connection.inputStream.use { it.readNBytesBounded(2_000_000) }.also {
                check(isGsiPng(it)) { "地理院未返回有效的 256×256 PNG 瓦片" }
            }
            404 -> null
            else -> throw PublicTileHttpException(status)
        }
    } finally { connection.disconnect() }
}

internal fun isGsiPng(bytes: ByteArray): Boolean {
    if (bytes.size < 45) return false
    val header = java.nio.ByteBuffer.wrap(bytes)
    if (header.long != -8552249625308161526L || header.int != 13 || header.int != 0x49484452 ||
        header.int != 256 || header.int != 256) return false
    // Require the final IEND chunk so a truncated HTTP response cannot become a saved tile.
    return java.nio.ByteBuffer.wrap(bytes, bytes.size - 12, 12).let { it.int == 0 && it.int == 0x49454e44 && it.int == -1371381630 }
}
