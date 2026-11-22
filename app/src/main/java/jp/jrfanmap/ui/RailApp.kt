package jp.jrfanmap.ui

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.jrfanmap.data.*
import jp.jrfanmap.map.*
import jp.jrfanmap.rail.*
import kotlinx.coroutines.delay
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

private fun Number?.fmt(decimals: Int = 1) = this?.let { String.format(Locale.US, "%.${decimals}f", it.toDouble()) } ?: "—"
private fun time(value: Long?) = value?.let { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(it)) } ?: "—"

@Composable
fun RailApp(model: RailViewModel, onRecord: () -> Unit, onLocationPermission: () -> Unit,
            onExport: (Long) -> Unit, onKeepAwake: (Boolean) -> Unit, onImmersive: (Boolean) -> Unit = {}) {
    val telemetry by model.store.telemetry.collectAsStateWithLifecycle()
    val catalog by model.catalog.collectAsStateWithLifecycle()
    val live by model.live.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("display", 0) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var immersive by rememberSaveable { mutableStateOf(false) }
    var keep by rememberSaveable { mutableStateOf(prefs.getBoolean("keep", true)) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtimeNanos()) }
    val fresh = telemetry.active && telemetry.fix?.let { FixPolicy.fresh(it, now) } == true
    val snackbar = remember { SnackbarHostState() }
    DisposableEffect(keep, immersive) {
        onKeepAwake(keep || immersive)
        onImmersive(immersive)
        onDispose { onKeepAwake(false); onImmersive(false) }
    }
    BackHandler(immersive) { immersive = false }
    LaunchedEffect(Unit) { while (true) { now = SystemClock.elapsedRealtimeNanos(); delay(1000) } }
    LaunchedEffect(message) { if (message.isNotBlank()) { snackbar.showSnackbar(message); model.message.value = "" } }
    LaunchedEffect(telemetry.recording) { if (!telemetry.recording) { delay(500); model.refreshTrips() } }

    MaterialTheme(colorScheme = instrumentColors) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Paper)) {
            val side = maxWidth >= 700.dp || (maxWidth > maxHeight && maxHeight < 500.dp)
            val select: (Int) -> Unit = { index ->
                tab = index
                if (index == 3) model.refreshTrips()
            }
            Scaffold(containerColor = Paper, snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
                if (!immersive && !side) InstrumentNav(tab, false, select)
            }) { padding ->
                Row(Modifier.fillMaxSize().padding(padding)) {
                    if (!immersive && side) InstrumentNav(tab, true, select)
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        when (tab) {
                            0 -> LivePage(model, catalog, telemetry, live, now, fresh, immersive, onRecord, onLocationPermission) { immersive = !immersive }
                            1 -> GpsPage(telemetry, now, fresh, onLocationPermission)
                            2 -> RailMapPage(model, telemetry, fresh, catalog, live)
                            3 -> History(model, onExport) { tab = 0 }
                            else -> DataPage(model, catalog, keep) { value ->
                                keep = value
                                prefs.edit().putBoolean("keep", value).apply()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun LivePage(model: RailViewModel, catalog: CatalogState, t: Telemetry,
                                 match: LiveMatch, now: Long, fresh: Boolean, immersive: Boolean,
                                 onRecord: () -> Unit, onPermission: () -> Unit, toggleImmersive: () -> Unit) {
    val context = LocalContext.current
    val fix = t.fix.takeIf { fresh }
    val line = match.line.takeIf { fresh && fix?.mock != true }
    val ahead = if (line == null) emptyList() else match.next.take(4)
    val speed = fix?.speedMps?.times(3.6f)
    val status = when {
        fresh && fix?.mock == true -> "模拟定位"
        fresh -> fix?.provider?.uppercase(Locale.ROOT) ?: "定位有效"
        t.active && t.fix != null -> "定位已过期"
        else -> t.message
    }
    var follow by rememberSaveable { mutableStateOf(true) }
    var showStops by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var selection by remember { mutableStateOf(RailSelectionState()) }
    var preview by remember { mutableStateOf(false) }
    val handle = remember { MapHandle() }
    val offline by model.offline.state.collectAsStateWithLifecycle()

    BoxWithConstraints(Modifier.fillMaxSize().testTag("live-page")) {
        val wide = maxWidth > maxHeight && maxWidth >= 490.dp
        val overlayHeight = maxHeight * (if (wide) .54f else .29f)
        RailMap(fix, t.path, follow, handle, MapBase.PALE, t.network, offline.regions, catalog.info?.year ?: 0,
            model::visibleRail, onLines = { selection = RailSelectionState(lines = it) },
            onStation = { selection = RailSelectionState(station = it) },
            onShinjukuPreview = { preview = it }, onEmptyTap = { selection = RailSelectionState() },
            modifier = Modifier.fillMaxSize(), highlightedLine = selection.lineId ?: line?.id,
            ahead = ahead.takeIf { selection.lineId == null || selection.lineId == line?.id }.orEmpty(),
            onGesture = { follow = false }, onError = { error = it })

        Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(9.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.Top) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Surface(shape = Cut, color = Ink.copy(alpha = .93f)) {
                    Text("LIVE · " + status, Modifier.widthIn(max = 145.dp).padding(horizontal = 8.dp, vertical = 5.dp),
                        color = if (fresh) Signal else Color(0xFFFFCF94), fontSize = 11.sp, lineHeight = 14.sp, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                }
                if (preview) PreviewCaption(Modifier)
            }
            Spacer(Modifier.weight(1f))
            Surface(shape = Cut, color = Panel, shadowElevation = 2.dp) {
                TextButton(onClick = { follow = true }, contentPadding = PaddingValues(horizontal = 10.dp)) {
                    Text(if (follow) "跟随中" else "回到位置")
                }
            }
            Surface(shape = Cut, color = Panel, shadowElevation = 2.dp) {
                TextButton(onClick = toggleImmersive, contentPadding = PaddingValues(horizontal = 10.dp)) {
                    Text(if (immersive) "退出全屏" else "全屏")
                }
            }
        }

        if (selection.isEmpty) Column(Modifier.align(Alignment.BottomStart).padding(start = 10.dp, end = 10.dp, bottom = 30.dp)
            .then(if (wide) Modifier.width((maxWidth * .38f).coerceAtMost(278.dp)) else Modifier.widthIn(max = 310.dp).fillMaxWidth())
            .heightIn(max = overlayHeight.coerceAtLeast(100.dp)).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Surface(shape = Cut, color = Ink.copy(alpha = .93f), shadowElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(speed.fmt(0), color = Signal, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 29.sp)
                        Text("km/h", color = Metal, fontSize = 12.sp)
                        Spacer(Modifier.weight(1f))
                        Column {
                            Text(fix?.bearing?.let { "真北 ${it.fmt(0)}°" } ?: "真北 —", color = Color.White, fontSize = 12.sp)
                            Text(fix?.accuracy?.let { "±${it.fmt(0)} m" } ?: "精度 —", color = Metal, fontSize = 11.sp)
                        }
                        TextButton(onClick = onRecord, modifier = Modifier.testTag("record-button"),
                            colors = ButtonDefaults.textButtonColors(contentColor = Signal),
                            contentPadding = PaddingValues(horizontal = 6.dp)) { Text(if (t.recording) "保存" else "记录") }
                    }
                    if (fix == null && !t.active) {
                        TextButton(onClick = onPermission, colors = ButtonDefaults.textButtonColors(contentColor = Signal)) { Text("定位设置") }
                    } else if (t.fix != null) {
                        Text("定位 ${FixPolicy.ageSeconds(t.fix, now)} 秒前" +
                            if (t.recording) " · 记录中 ${(t.distanceM / 1000).fmt(2)} km" else "",
                            color = Metal, fontSize = 11.sp)
                    }
                }
            }
            Surface(shape = Cut, color = Panel.copy(alpha = .92f), shadowElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(line?.name ?: "线路待确认", fontWeight = FontWeight.Bold, maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            if (line != null) {
                                Text(line.operator + " · 设备推断", color = Muted, fontSize = 11.sp)
                            } else Text(if (fresh) match.reason else "等待新的定位观测", color = Muted, fontSize = 11.sp)
                        }
                        if (line != null) {
                            TextButton(onClick = { selection = RailSelectionState(lines = listOf(line.id)) },
                                contentPadding = PaddingValues(horizontal = 8.dp)) { Text("线路资料") }
                        }
                        if (ahead.isNotEmpty()) {
                            TextButton(onClick = { showStops = !showStops },
                                contentPadding = PaddingValues(horizontal = 8.dp)) { Text(if (showStops) "收起" else "站序") }
                        }
                    }
                    if (showStops && ahead.isNotEmpty()) {
                        HorizontalDivider(color = Grid)
                        Text("前方站序 · 沿轨推断", color = Mint, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                        ahead.forEachIndexed { index, station ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (index == 0) "●" else "○", color = Mint, fontSize = 11.sp)
                                Text(station.station.name, Modifier.weight(1f), fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Normal)
                                Text(if (station.meters < 1000) "${station.meters.fmt(0)} m" else "${(station.meters / 1000).fmt()} km",
                                    color = Muted, fontFamily = Mono, fontSize = 12.sp)
                            }
                        }
                    } else if (ahead.isNotEmpty()) {
                        val next = ahead.first()
                        val distance = if (next.meters < 1000) "${next.meters.fmt(0)} m" else "${(next.meters / 1000).fmt()} km"
                        Text("前方 ${next.station.name} · $distance", color = Muted, fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 2.dp))
                    } else if (line != null) {
                        Text(match.reason, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
                    }
                }
            }
            if (error.isNotBlank()) Surface(shape = Cut, color = Panel) {
                Text(error, Modifier.padding(8.dp), color = Amber, fontSize = 11.sp)
            }
        }
        Surface(Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 4.dp), color = Ink.copy(alpha = .86f), shape = Cut) {
            Text("国土交通省・国土地理院", Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                color = Color.White, fontSize = 9.sp, lineHeight = 12.sp)
        }
        RailSelection(model, selection, match.takeIf { line != null }) { selection = it }
    }
}

@Composable private fun GpsPage(t: Telemetry, now: Long, fresh: Boolean, onPermission: () -> Unit) {
    val f = t.fix
    LazyColumn(Modifier.fillMaxSize().testTag("gps-page"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            PageHead("GPS 诊断", 1)
            Text(t.message, color = if (fresh) Mint else Amber)
            if (!t.active || !fresh) TextButton(onClick = onPermission) { Text("打开定位设置") }
        }
        item { CardPanel {
            val satellites = t.satellites
            SectionTitle("卫星天空图")
            SkyPlot(satellites)
            LabelValue("参与定位 / 可见", "${satellites.count { it.used }} / ${satellites.size}")
            LabelValue("PDOP / HDOP / VDOP", "${t.dop?.pdop.fmt()} / ${t.dop?.hdop.fmt()} / ${t.dop?.vdop.fmt()}")
            LabelValue("首次定位耗时", t.firstFixMillis?.let { "${(it / 1000.0).fmt()} s" } ?: "—")
            LabelValue("原始观测数", t.rawMeasurements?.toString() ?: "—")
            Text("圆心为天顶。实心点参与定位，空心点可见。", color = Muted, fontSize = 12.sp)
        } }
        item { CardPanel {
            SectionTitle(if (fresh) "定位详情" else "最后一次定位")
            LabelValue("纬度 / 经度", "${f?.latitude.fmt(6)} / ${f?.longitude.fmt(6)}")
            LabelValue("来源 / 模拟", "${f?.provider ?: "—"} / ${f?.let { if (it.mock) "是" else "否" } ?: "—"}")
            LabelValue("水平精度", "±${f?.accuracy.fmt()} m")
            LabelValue("WGS84 椭球高", "${f?.altitude.fmt()} ±${f?.verticalAccuracy.fmt()} m")
            LabelValue("平均海平面高度", "${f?.mslAltitude.fmt()} ±${f?.mslAccuracy.fmt()} m")
            LabelValue("速度 / 精度", "${f?.speedMps?.times(3.6f).fmt()} ±${f?.speedAccuracy?.times(3.6f).fmt()} km/h")
            LabelValue("真北方向 / 精度", "${f?.bearing.fmt()}° ±${f?.bearingAccuracy.fmt()}°")
            LabelValue("定位时间 / 年龄", "${time(f?.time)} / ${f?.let { FixPolicy.ageSeconds(it, now) } ?: "—"} s")
        } }
        item { CardPanel {
            SectionTitle("设备传感器")
            LabelValue("磁北朝向", "${t.magneticHeading.fmt(0)}°")
            LabelValue("磁场强度", "${t.magneticField.fmt()} µT")
            LabelValue("气压", "${t.pressureHpa.fmt()} hPa")
            LabelValue("线性加速度模长", "${t.acceleration.fmt(2)} m/s²")
            LabelValue("陀螺仪角速度模长", "${t.angularSpeed.fmt(3)} rad/s")
            Text("缺失读数取决于设备硬件和系统。", color = Muted)
        } }
        val groups = t.satellites.groupBy { it.constellation }.toSortedMap()
        items(groups.entries.toList(), key = { it.key }) { (name, satellites) ->
            CardPanel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    Text("${satellites.count { it.used }} / ${satellites.size} 参与定位", color = Muted, fontSize = 11.sp)
                }
                satellites.sortedWith(compareByDescending<Satellite> { it.used }.thenBy { it.id }).forEachIndexed { index, satellite ->
                    key(satellite.id, satellite.frequencyMhz, index) { SatelliteRow(satellite) }
                }
            }
        }
    }
}

@Composable private fun SatelliteRow(satellite: Satellite) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 3.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (satellite.used) "●" else "○", color = if (satellite.used) Mint else Muted, fontSize = 11.sp)
            Text("SVID ${satellite.id}", Modifier.weight(1f), fontFamily = Mono, fontSize = 12.sp)
            Text("${satellite.cn0.fmt()} dB-Hz", fontFamily = Mono, fontSize = 12.sp)
            Text("方${satellite.azimuth.fmt(0)}° 仰${satellite.elevation.fmt(0)}°", color = Muted, fontSize = 11.sp)
            Text(if (expanded) "▴" else "▾", color = Muted, fontSize = 11.sp)
        }
        if (expanded) {
            Text("载波 ${satellite.frequencyMhz.fmt()} MHz · 基带 ${satellite.basebandCn0.fmt()} dB-Hz",
                color = Muted, fontSize = 11.sp)
            Text("星历 ${if (satellite.ephemeris) "有" else "—"} · 历书 ${if (satellite.almanac) "有" else "—"}",
                color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable private fun SkyPlot(satellites: List<Satellite>) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Ink).padding(8.dp),
        contentAlignment = Alignment.Center) {
        Box(Modifier.size(190.dp)) {
            Canvas(Modifier.fillMaxSize().padding(15.dp)) {
                val radius = size.minDimension / 2
                val grid = Color(0xFF557476)
                for (level in 1..3) drawCircle(grid, radius * level / 3, center,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                for (i in 0 until 8) {
                    val angle = i * Math.PI / 4
                    drawLine(grid, center, center + Offset(radius * sin(angle).toFloat(), radius * cos(angle).toFloat()))
                }
                satellites.forEach { satellite ->
                    val angle = Math.toRadians(satellite.azimuth.toDouble())
                    val r = radius * (90 - satellite.elevation.coerceIn(0f, 90f)) / 90
                    val at = center + Offset(r * sin(angle).toFloat(), -r * cos(angle).toFloat())
                    drawCircle(Ink, 5.dp.toPx(), at)
                    if (satellite.used) drawCircle(Signal, 4.dp.toPx(), at)
                    else drawCircle(Metal, 4.dp.toPx(), at, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                }
            }
            listOf("N" to Alignment.TopCenter, "E" to Alignment.CenterEnd, "S" to Alignment.BottomCenter,
                "W" to Alignment.CenterStart).forEach { (direction, alignment) ->
                Text(direction, Modifier.align(alignment), color = Metal, fontSize = 11.sp, lineHeight = 12.sp, fontFamily = Mono)
            }
        }
    }
}

@Composable private fun RailMapPage(model: RailViewModel, telemetry: Telemetry, fresh: Boolean, catalog: CatalogState, match: LiveMatch) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("display", 0) }
    var follow by rememberSaveable { mutableStateOf(false) }
    var base by rememberSaveable {
        mutableStateOf(prefs.getString("map_base", null)?.let { runCatching { MapBase.valueOf(it) }.getOrNull() }
            ?: if (prefs.getBoolean("gsi", false)) MapBase.VECTOR else MapBase.PALE)
    }
    var showSources by remember { mutableStateOf(false) }
    var railSelection by remember { mutableStateOf(RailSelectionState()) }
    var preview by remember { mutableStateOf(false) }
    var area by remember { mutableStateOf<DownloadArea?>(null) }
    val handle = remember { MapHandle() }
    val offline by model.offline.state.collectAsStateWithLifecycle()
    val activeMatch = match.takeIf { fresh && telemetry.fix?.mock == false }

    var lastOfflineMessage by remember { mutableStateOf(offline.message) }
    LaunchedEffect(offline.message, offline.busy) {
        if (!offline.busy && lastOfflineMessage != offline.message) {
            model.message.value = offline.message
        }
        lastOfflineMessage = offline.message
    }
    Box(Modifier.fillMaxSize().testTag("map-page")) {
        RailMap(telemetry.fix.takeIf { fresh }, telemetry.path, follow, handle, base, telemetry.network,
            offline.regions, catalog.info?.year ?: 0, model::visibleRail,
            onLines = { railSelection = RailSelectionState(lines = it) },
            onStation = { railSelection = RailSelectionState(station = it) }, onShinjukuPreview = { preview = it },
            modifier = Modifier.fillMaxSize(), highlightedLine = railSelection.lineId,
            ahead = activeMatch?.takeIf { it.line?.id == railSelection.lineId }?.next?.take(4).orEmpty(),
            onGesture = { follow = false }, onEmptyTap = {
                if (railSelection.isEmpty) {
                    model.message.value = if (catalog.busy) "铁路库正在准备" else "附近未命中铁路，请放大后再点线路"
                }
                railSelection = RailSelectionState()
            }, onError = { model.message.value = it })
        if (preview) PreviewCaption(Modifier.align(Alignment.TopStart).padding(8.dp))
        Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            Surface(shape = Cut, color = Panel.copy(alpha = .90f), shadowElevation = 2.dp) {
                TextButton(onClick = { showSources = true }, modifier = Modifier.testTag("map-controls"),
                    contentPadding = PaddingValues(horizontal = 10.dp)) {
                    Text(if (offline.busy) "下载中 ▾" else "地图设置 ▾", fontSize = 13.sp)
                }
            }
            DropdownMenu(expanded = showSources, onDismissRequest = { showSources = false }) {
                DropdownMenuItem(text = { Text(if (follow) "停止跟随" else "跟随位置") }, onClick = {
                    follow = !follow
                    showSources = false
                })
                HorizontalDivider()
                listOf(MapBase.PALE to "地理院淡色地图", MapBase.RAIL to "铁路概略图", MapBase.STANDARD to "地理院标准地图",
                    MapBase.VECTOR to "地理院实验矢量图").forEach { (mode, title) ->
                    DropdownMenuItem(text = { Text(if (mode == base) "$title ✓" else title) }, onClick = {
                        base = mode
                        prefs.edit().putString("map_base", mode.name).apply()
                        showSources = false
                    })
                }
                HorizontalDivider()
                DropdownMenuItem(text = {
                    Column {
                        Text(if (offline.busy) "取消下载" else "下载标准地图")
                        Text(offline.message, color = Muted, fontSize = 11.sp)
                    }
                }, onClick = {
                        showSources = false
                        if (offline.busy) model.offline.pause()
                        else {
                            area = handle.area()
                            if (area == null) model.message.value = "请等待地图加载"
                        }
                    })
            }
        }
        Surface(Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 6.dp).widthIn(max = 195.dp),
            shape = Cut, color = Ink.copy(alpha = .86f)) {
            Text(CATALOG_CREDIT, Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                color = Color.White, fontSize = 9.sp, lineHeight = 12.sp)
        }
        RailSelection(model, railSelection, activeMatch) { railSelection = it }
    }
    area?.let { selection ->
        val fits = selection.tiles in 1..TileBudget.LIMIT
        val zooms = "缩放 ${selection.minZoom}–${selection.maxZoom}"
        AlertDialog(onDismissRequest = { area = null }, title = { Text("下载地理院标准地图") }, text = {
            Text(if (fits) "$zooms，约 ${selection.tiles} 个瓦片。下载后可离线浏览。"
                else "当前范围超过 ${TileBudget.LIMIT} 个瓦片，请先放大地图。")
        }, confirmButton = {
            TextButton(enabled = fits, onClick = { model.offline.download(selection); area = null }) { Text("下载") }
        }, dismissButton = { TextButton(onClick = { area = null }) { Text("取消") } })
    }
}

@Composable private fun PreviewCaption(modifier: Modifier) {
    Surface(modifier.testTag("map-preview-shinjuku"), color = Ink.copy(alpha = .88f), shape = Cut) {
        Text("新宿駅 · 默认浏览", Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            color = Color.White, fontSize = 12.sp)
    }
}

private data class RailSelectionState(val lines: List<Long> = emptyList(), val station: String? = null,
                                      val lineId: Long? = lines.singleOrNull()) {
    val isEmpty: Boolean get() = lines.isEmpty() && station == null
}

@Composable private fun RailSelection(model: RailViewModel, selection: RailSelectionState, match: LiveMatch?,
                                      onSelection: (RailSelectionState) -> Unit) {
    if (selection.isEmpty) return
    val selectedIds = selection.lines
    val stationName = selection.station
    val picked = selection.lineId
    val close = { onSelection(RailSelectionState()) }
    var choices by remember(selectedIds) { mutableStateOf<List<RailLine>?>(null) }
    var line by remember(picked, selectedIds) { mutableStateOf<RailNetwork?>(null) }
    var unavailable by remember(selectedIds, picked) { mutableStateOf(false) }
    var expanded by remember(selectedIds, picked, stationName) { mutableStateOf(false) }
    LaunchedEffect(selectedIds) {
        if (selectedIds.isEmpty()) return@LaunchedEffect
        try {
            val results = model.lines(selectedIds)
            choices = results
            if (picked == null && results.size == 1) onSelection(selection.copy(lineId = results.single().id))
            unavailable = results.isEmpty()
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { unavailable = true }
    }
    LaunchedEffect(picked, selectedIds) {
        val id = picked ?: return@LaunchedEffect
        try {
            line = model.line(id)
            unavailable = line == null
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { unavailable = true }
    }
    BackHandler { if (expanded) expanded = false else close() }
    val network = line
    val candidates = choices.orEmpty()
    val isChoice = picked == null && candidates.size > 1
    val title = stationName ?: network?.line?.name ?: when {
        unavailable -> "线路资料不可用"
        isChoice -> "选择线路"
        else -> "读取线路资料"
    }
    val kind = when {
        stationName != null -> "车站"
        isChoice -> "${candidates.size} 条候选线路"
        network != null -> network.line.operator
        else -> "线路资料"
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > maxHeight && maxWidth >= 450.dp
        val width = if (wide) (maxWidth * .36f).coerceIn(208.dp, 266.dp) else (maxWidth - 16.dp).coerceAtMost(288.dp)
        val height = when {
            expanded -> (maxHeight * (if (wide) .55f else .30f)).coerceIn(104.dp, 224.dp)
            isChoice -> 104.dp
            else -> 68.dp
        }.coerceAtMost((maxHeight - 30.dp).coerceAtLeast(0.dp))
        Surface(Modifier.align(Alignment.BottomEnd)
            .padding(end = 12.dp, bottom = 44.dp)
            .width(width).heightIn(max = height)
            .testTag("rail-info-panel")
            .semantics { paneTitle = "铁路资料" },
            color = Panel.copy(alpha = .93f), shape = Cut, shadowElevation = 2.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, Grid.copy(alpha = .8f))) {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(start = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(3.dp, 24.dp).background(Mint))
                    Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                        Text(title, Modifier.semantics { heading() }, fontSize = 15.sp, lineHeight = 18.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                        Text(kind, fontSize = 10.sp, lineHeight = 13.sp, color = Mint,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (network != null || stationName != null || isChoice) {
                        TextButton(onClick = { expanded = !expanded },
                            modifier = Modifier.testTag("rail-info-expand").semantics { stateDescription = if (expanded) "已展开" else "已收起" },
                            contentPadding = PaddingValues(horizontal = 4.dp)) { Text(if (expanded) "收起" else "展开", fontSize = 12.sp) }
                    }
                    IconButton(onClick = close, modifier = Modifier.testTag("rail-info-close")
                        .semantics { contentDescription = "关闭铁路资料" }) {
                        Text("×", color = Muted, fontSize = 22.sp)
                    }
                }
                if (expanded || isChoice) {
                    HorizontalDivider(color = Grid)
                    key(title, expanded) {
                        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())
                            .padding(horizontal = 10.dp, vertical = if (isChoice) 1.dp else 6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            when {
                                stationName != null -> RailSource()
                                network != null -> {
                                    LineDetails(network, match?.takeIf { it.line?.id == network.line.id })
                                    if (candidates.size > 1) TextButton(onClick = { onSelection(selection.copy(lineId = null)) }) {
                                        Text("查看其他候选线路")
                                    }
                                }
                                isChoice -> {
                                    candidates.groupBy { it.operator }.forEach { (operator, lines) ->
                                        if (expanded) Text(operator, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        lines.forEach { candidate ->
                                            Row(Modifier.fillMaxWidth().testTag("rail-choice")
                                                .clickable(role = androidx.compose.ui.semantics.Role.Button) {
                                                    onSelection(selection.copy(lineId = candidate.id))
                                                }
                                                .heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Text(candidate.name, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                                                Text(candidate.categoryLabel(), color = Mint, fontSize = 12.sp)
                                            }
                                        }
                                    }
                                }
                                else -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun LineDetails(network: RailNetwork, match: LiveMatch?) {
    val context = LocalContext.current
    var reverse by remember(network.line.id) { mutableStateOf(false) }
    val groups by produceState<List<List<RailStation>>?>(null, network) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            TrackGraph(network).browsingStationGroups()
        }
    }
    val kilometers by produceState<Double?>(null, network) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            network.sections.sumOf { section -> section.shape.zipWithNext().sumOf { (a, b) ->
                hypot((a.lat - b.lat) * 111.195, (a.lon - b.lon) * 111.195 * cos(Math.toRadians((a.lat + b.lat) / 2)))
            } }
        }
    }
    val routes = groups
    val browsing = match?.next.isNullOrEmpty() && !routes.isNullOrEmpty()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("站点名录", Modifier.weight(1f).semantics { heading() }, fontWeight = FontWeight.Bold,
            fontSize = 13.sp, lineHeight = 17.sp)
        if (browsing) TextButton(onClick = { reverse = !reverse }, modifier = Modifier.testTag("reverse-stations"),
            contentPadding = PaddingValues(horizontal = 4.dp)) { Text("反向 ↕", fontSize = 12.sp, lineHeight = 15.sp) }
    }
    if (match != null && match.next.isNotEmpty()) {
        Text(match.reason, color = Muted, fontSize = 11.sp, lineHeight = 14.sp)
        match.next.forEachIndexed { index, stop ->
            StationTimelineRow(stop.station.name, index, match.next.lastIndex, stop.meters)
        }
    } else {
        if (match != null) Text(match.reason, color = Muted, fontSize = 11.sp, lineHeight = 14.sp)
        when {
            routes == null -> Text("正在整理站序…", color = Muted, fontSize = 11.sp)
            routes.isEmpty() -> Text("离线资料暂无法建立站序", color = Muted, fontSize = 12.sp)
            else -> {
                routes.forEach { route ->
                    val stations = if (reverse) route.asReversed() else route
                    if (stations.size > 1) Text("${stations.first().name} → ${stations.last().name}",
                        Modifier.semantics { contentDescription = "按轨道走向浏览，${stations.first().name}到${stations.last().name}" },
                        color = Mint, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    stations.forEachIndexed { index, stop -> StationTimelineRow(stop.name, index, stations.lastIndex) }
                }
            }
        }
    }
    operatorPages.firstOrNull { it.operator == network.line.operator }?.let { page ->
        TextButton(onClick = { open(context, page.url) }) { Text("${page.title} 官方运行信息 ↗") }
    }
    HorizontalDivider(color = Grid)
    Text("${network.line.categoryLabel()} · 线形约 ${kilometers.fmt()} km", color = Muted, fontSize = 12.sp)
    Text("线形总长含支线及数据中的重复轨道。", color = Muted, fontSize = 11.sp)
    network.line.fact()?.let { fact ->
        HorizontalDivider(color = Grid)
        Text("线路科普", Modifier.semantics { heading() }, fontWeight = FontWeight.Bold)
        Text(fact.text, fontSize = 14.sp)
        TextButton(onClick = { open(context, fact.source) }) { Text("查看科普来源 ↗") }
    }
    HorizontalDivider(color = Grid)
    RailSource()
}

@Composable private fun StationTimelineRow(name: String, index: Int, last: Int, meters: Double? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 27.dp).testTag("rail-station-row"),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.width(16.dp).height(27.dp)) {
            val start = if (index == 0) center.y else -2.dp.toPx()
            val end = if (index == last) center.y else size.height + 2.dp.toPx()
            drawLine(Mint, Offset(center.x, start), Offset(center.x, end), strokeWidth = 1.5.dp.toPx())
            drawCircle(Mint, if (index == 0) 4.dp.toPx() else 3.dp.toPx())
            if (index != 0) drawCircle(Panel, 1.5.dp.toPx())
        }
        Text(name, Modifier.weight(1f), fontSize = 13.sp, lineHeight = 18.sp,
            fontWeight = if (index == 0) FontWeight.SemiBold else FontWeight.Medium)
        if (meters != null) Text(if (meters < 1000) "${meters.fmt(0)} m" else "${(meters / 1000).fmt()} km",
            color = Muted, fontFamily = Mono, fontSize = 11.sp)
    }
}

@Composable private fun RailSource() {
    val context = LocalContext.current
    Text(CATALOG_CREDIT, color = Muted, fontSize = 12.sp)
    TextButton(onClick = { open(context, CATALOG_SOURCE) }) { Text("国土交通省数据来源 ↗") }
}

@Composable private fun DataPage(model: RailViewModel, catalog: CatalogState, keep: Boolean, onKeep: (Boolean) -> Unit) {
    val offline by model.offline.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var clear by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().testTag("data-page"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHead("数据管理", 4) }
        item { CardPanel(Modifier.fillMaxWidth()) {
            SectionTitle("全国铁路库")
            catalog.info?.let { info ->
                LabelValue("基准年度", info.year.toString())
                LabelValue("线路 / 站点记录", "${info.lines} / ${info.stations}")
                LabelValue("数据库大小", "${(info.bytes / 1048576.0).fmt()} MiB")
            }
            Text(catalog.status, color = if (catalog.info == null) Amber else Muted)
            if (catalog.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Button(onClick = { model.updateCatalog() }, enabled = !catalog.busy) { Text("联网检查并下载更新") }
            TextButton(onClick = { model.updateCatalog(reinstall = true) }, enabled = !catalog.busy) { Text("重新下载官方铁路数据") }
            TextButton(onClick = { open(context, CATALOG_SOURCE) }) { Text("国土交通省数据来源 ↗") }
            Text(CATALOG_CREDIT, color = Muted, fontSize = 11.sp)
        } }
        item { CardPanel(Modifier.fillMaxWidth()) {
            SectionTitle("地理院地图缓存")
            Text("内置铁路图可直接离线使用。可在地图页下载地理院标准地图，断网时自动读取已保存区域。")
            Text(offline.message, color = Muted)
            TextButton(enabled = offline.count > 0 && !offline.busy, onClick = { clear = true }) { Text("删除已下载的地图区域") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("应用打开时保持屏幕常亮", modifier = Modifier.weight(1f))
                Switch(checked = keep, onCheckedChange = onKeep)
            }
        } }
        item { CardPanel(Modifier.fillMaxWidth()) {
            SectionTitle("官方运行信息")
            Text("打开对应运营方查看公告及其覆盖范围。", color = Muted)
            operatorPages.forEach { page -> TextButton(onClick = { open(context, page.url) }) { Text("${page.title} ↗") } }
        } }

    }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("删除已下载的地理院地图区域？") },
        text = { Text("可重新下载地图区域。") },
        confirmButton = { TextButton(onClick = { model.offline.deleteAll(); clear = false }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { clear = false }) { Text("取消") } })
}

private fun open(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        .onFailure { android.widget.Toast.makeText(context, "没有可打开链接的浏览器", android.widget.Toast.LENGTH_SHORT).show() }
}

@Composable private fun History(model: RailViewModel, onExport: (Long) -> Unit, onLive: () -> Unit) {
    val trips by model.history.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<Trip?>(null) }
    LazyColumn(Modifier.fillMaxSize().testTag("history-page").padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(top = 14.dp, bottom = 20.dp)) {
        item { PageHead("行程记录", 3) { Text("${trips.size} 次", color = Muted, fontFamily = Mono) } }
        item { Text("轨迹仅存本机，不上传到交通服务。CSV 含精确位置，请自行选择分享对象。", color = Muted, fontSize = 12.sp) }
        if (trips.isEmpty()) item {
            Column(Modifier.fillMaxWidth().clip(Cut).background(Ink).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                InstrumentIcon(3, Signal)
                Text("还没有行程", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 23.sp)
                Text("从 LIVE 页面开始记录，锁屏后仍继续采样。", color = Metal, fontSize = 14.sp)
                Button(onClick = onLive, shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Signal, contentColor = Ink)) { Text("前往 LIVE") }
            }
        }
        items(trips, key = { it.id }) { trip -> CardPanel(Modifier.fillMaxWidth()) {
            Text(time(trip.start), style = MaterialTheme.typography.titleMedium)
            Text("${(trip.distance / 1000).fmt(2)} km   ·   ${trip.points} 个定位点", fontFamily = FontFamily.Monospace, color = Mint)
            Text(if (trip.end == null) "记录中" else "结束：${time(trip.end)}${if (trip.interrupted) " · 上次意外中断" else ""}", color = Muted, fontSize = 12.sp)
            Row { TextButton(onClick = { onExport(trip.id) }, enabled = trip.end != null) { Text("导出 CSV") }; TextButton(onClick = { deleting = trip }, enabled = trip.end != null) { Text("删除", color = Amber) } }
        } }
    }
    deleting?.let { trip -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除此行程？") }, text = { Text("本机的定位记录将被永久删除。") }, confirmButton = { TextButton(onClick = { model.delete(trip.id); deleting = null }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}
