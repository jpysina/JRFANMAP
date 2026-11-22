package jp.jrfanmap.ui

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.jrfanmap.data.*
import jp.jrfanmap.map.*
import jp.jrfanmap.rail.*
import kotlinx.coroutines.delay
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
                            0 -> LivePage(telemetry, live, now, fresh, immersive, onRecord, onLocationPermission) { immersive = !immersive }
                            1 -> GpsPage(telemetry, now, fresh, onLocationPermission)
                            2 -> RailMapPage(model, telemetry, fresh, catalog)
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

@Composable private fun LivePage(t: Telemetry, match: LiveMatch, now: Long, fresh: Boolean, immersive: Boolean,
                                 onRecord: () -> Unit, onPermission: () -> Unit, toggleImmersive: () -> Unit) {
    val context = LocalContext.current
    val line = match.line.takeIf { fresh }
    val fix = t.fix.takeIf { fresh }
    val speed = fix?.speedMps?.times(3.6f)
    val status = when {
        fresh -> if (fix?.mock == true) "模拟定位" else "定位有效"
        t.active && t.fix != null -> "定位已过期"
        else -> t.message
    }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("live-page")) {
        val wide = maxWidth >= 570.dp
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PageHead("随车观测", 0) {
                OutlinedButton(onClick = toggleImmersive, contentPadding = PaddingValues(horizontal = 14.dp)) {
                    Text(if (immersive) "退出全屏" else "全屏")
                }
            }
            val instrument: @Composable () -> Unit = {
                Column(Modifier.fillMaxWidth().clip(Cut).background(Ink).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("对地速度", Modifier.weight(1f), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(status, color = if (fresh) Signal else Color(0xFFFFCF94), fontSize = 12.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(speed.fmt(), color = Signal, fontFamily = Mono, fontWeight = FontWeight.Bold,
                                fontSize = if (wide) 58.sp else 66.sp, lineHeight = 70.sp, maxLines = 1)
                            Text("km/h", color = Metal, fontFamily = Mono, fontSize = 14.sp)
                        }
                        Compass(fix?.bearing)
                    }
                    SpeedScale(speed)
                    HorizontalDivider(color = Metal.copy(alpha=.3f))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MeterCell("真北方向", fix?.bearing?.let { "${it.fmt(0)}°" } ?: "—", Modifier.weight(1f))
                        MeterCell("水平精度", fix?.accuracy?.let { "±${it.fmt()} m" } ?: "—", Modifier.weight(1.15f))
                        MeterCell("定位时效", t.fix?.let { "${FixPolicy.ageSeconds(it, now)} s" } ?: "—", Modifier.weight(1f))
                    }
                    fix?.let { Text("${it.provider.uppercase(Locale.ROOT)}  ·  ${time(it.time)}", color = Metal, fontFamily = Mono, fontSize = 12.sp) }
                    if (!fresh) TextButton(onClick = onPermission, colors = ButtonDefaults.textButtonColors(contentColor = Signal)) { Text("定位设置") }
                }
            }
            val route: @Composable () -> Unit = {
                CardPanel {
                    SectionTitle("本机线路推断")
                    Text(line?.name ?: "线路待确认", fontSize = 23.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
                    if (line != null) Text("${line.operator} · 距线形约 ${match.offsetMeters.fmt(0)} m", color = Muted)
                    Text(if (fresh) match.reason else "等待新的定位观测", color = if (line == null) Amber else Muted, fontSize = 13.sp)
                    operatorPages.firstOrNull { it.operator == line?.operator }?.let { page ->
                        TextButton(onClick = { open(context, page.url) }) { Text("${page.title} 官方运行信息 ↗") }
                    }
                    if (line != null && match.next.isNotEmpty()) {
                        HorizontalDivider(color = Grid)
                        Text("前方站序", color = Mint, fontWeight = FontWeight.Bold)
                        match.next.forEachIndexed { index, ahead ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Canvas(Modifier.size(14.dp, 30.dp)) {
                                    if (index > 0) drawLine(Mint, Offset(center.x,0f), center, 2.dp.toPx())
                                    if (index < match.next.lastIndex) drawLine(Mint,center,Offset(center.x,size.height),2.dp.toPx())
                                    drawCircle(Mint,4.dp.toPx()); drawCircle(Panel,2.dp.toPx())
                                }
                                Text(ahead.station.name, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                                Text(if (ahead.meters < 1000) "约 ${ahead.meters.fmt(0)} m" else "约 ${(ahead.meters / 1000).fmt()} km", color = Muted)
                            }
                        }
                        Text("按轨道位置推断，列车可能通过不停站。", color = Muted, fontSize = 12.sp)
                    }
                }
            }
            val record: @Composable () -> Unit = {
                Button(onClick = onRecord, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("record-button"),
                    shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = Mint)) {
                    Canvas(Modifier.size(15.dp)) {
                        if (t.recording) drawRect(Color.White) else drawCircle(Color.White)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(if (t.recording) "结束并保存行程" else "开始行程记录", fontWeight = FontWeight.Bold)
                }
                if (t.recording) LabelValue("记录距离 / 最高速度", "${(t.distanceM / 1000).fmt(2)} km / ${t.maxSpeedKmh.fmt()} km/h")
            }
            if (wide) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1.12f), verticalArrangement = Arrangement.spacedBy(10.dp)) { instrument() }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) { record(); route() }
            } else { instrument(); record(); route() }
        }
    }
}

@Composable private fun MeterCell(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, color = Metal, fontSize = 12.sp)
        Text(value, color = Color.White, fontFamily = Mono, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable private fun SpeedScale(speed: Float?) {
    Column(Modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(20.dp)) {
            for (tick in 0..40) {
                val x = size.width * tick / 40
                drawLine(if (speed != null && tick * 10 <= speed) Signal else Metal.copy(alpha=.65f),
                    Offset(x,size.height),Offset(x,size.height - (if (tick % 10 == 0) 12.dp else 5.dp).toPx()),1.dp.toPx())
            }
            speed?.let {
                val x = size.width * it.coerceIn(0f,400f) / 400
                drawLine(Signal,Offset(x,0f),Offset(x,size.height),3.dp.toPx())
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("0","100","200","300","400").forEach { Text(it, color = Metal, fontFamily = Mono,fontSize = 10.sp) }
        }
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
        items(t.satellites.sortedWith(compareByDescending<Satellite> { it.used }.thenByDescending { it.cn0 })) { s ->
            CardPanel(Modifier.fillMaxWidth()) {
                LabelValue("${s.constellation} / ${s.id}", if (s.used) "参与定位" else "可见")
                LabelValue("方位角 / 仰角", "${s.azimuth.fmt(0)}° / ${s.elevation.fmt(0)}°")
                LabelValue("载噪比 / 基带", "${s.cn0.fmt()} / ${s.basebandCn0.fmt()} dB-Hz")
                LabelValue("载波频率", "${s.frequencyMhz.fmt()} MHz")
                LabelValue("星历 / 历书", "${if (s.ephemeris) "有" else "—"} / ${if (s.almanac) "有" else "—"}")
            }
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

@Composable private fun RailMapPage(model: RailViewModel, telemetry: Telemetry, fresh: Boolean, catalog: CatalogState) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("display", 0) }
    var follow by rememberSaveable { mutableStateOf(false) }
    var gsi by rememberSaveable { mutableStateOf(prefs.getBoolean("gsi", false)) }
    var error by remember { mutableStateOf("") }
    var selectedIds by remember { mutableStateOf<List<Long>>(emptyList()) }
    var line by remember { mutableStateOf<RailNetwork?>(null) }
    var alternatives by remember { mutableStateOf<List<RailNetwork>>(emptyList()) }
    var stationName by remember { mutableStateOf<String?>(null) }
    var area by remember { mutableStateOf<DownloadArea?>(null) }
    val handle = remember { MapHandle() }
    val offline by model.offline.state.collectAsStateWithLifecycle()
    LaunchedEffect(selectedIds, catalog.info?.year) {
        val results = selectedIds.mapNotNull { model.line(it) }
        if (results.size == 1) line = results.first() else alternatives = results
    }
    Column(Modifier.fillMaxSize().testTag("map-page")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            InstrumentIcon(2, Mint)
            Spacer(Modifier.width(6.dp))
            Text(if (gsi) "地理院地図" else "离线铁路图", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { follow = !follow }) { Text(if (follow) "跟随中" else "跟随位置") }
            TextButton(onClick = { error = ""; gsi = !gsi; prefs.edit().putBoolean("gsi", gsi).apply() }) { Text(if (gsi) "离线图" else "地理院") }
        }
        RailMap(telemetry.fix.takeIf { fresh }, telemetry.path, follow, handle, gsi, catalog.info?.year ?: 0, model::visibleRail,
            onLines = { selectedIds = it }, onStation = { stationName = it }, modifier = Modifier.weight(1f).fillMaxWidth(),
            onError = { error = it })
        if (error.isNotBlank()) Text(error, color = Amber, modifier = Modifier.padding(horizontal = 12.dp))
        Text(if (fresh) "点击线路查看资料，放大查看车站" else "点击线路查看资料 · 定位未更新",
            fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        Text(CATALOG_CREDIT, color = Muted, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        if (gsi) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(offline.message, Modifier.weight(1f).padding(start = 12.dp), fontSize = 11.sp)
            TextButton(onClick = { if (offline.busy) model.offline.pause() else area = handle.area() }) {
                Text(if (offline.busy) "停止下载" else "下载范围")
            }
        }
    }
    if (alternatives.isNotEmpty()) AlertDialog(onDismissRequest = { alternatives = emptyList(); selectedIds = emptyList() },
        title = { Text("选择线路") }, text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                alternatives.forEach { candidate ->
                    TextButton(onClick = { line = candidate; alternatives = emptyList() }) {
                        Text("${candidate.line.name} · ${candidate.line.operator}")
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { alternatives = emptyList(); selectedIds = emptyList() }) { Text("关闭") } })
    line?.let { info -> LineDetails(info) { line = null; selectedIds = emptyList() } }
    stationName?.let { name -> AlertDialog(onDismissRequest = { stationName = null },
        title = { Text(name) }, text = { Text("日文原站名 · 国土数値情報") },
        confirmButton = { TextButton(onClick = { stationName = null }) { Text("关闭") } }) }
    area?.let { a -> AlertDialog(onDismissRequest = { area = null }, title = { Text("下载地理院地图当前范围") },
        text = { Text("缩放 ${a.minZoom}–${a.maxZoom}，约 ${a.tiles} 个瓦片。每区最多 1500 瓦片。") },
        confirmButton = { TextButton(enabled = a.tiles <= 1500, onClick = { model.offline.download(a); area = null }) { Text("下载") } },
        dismissButton = { TextButton(onClick = { area = null }) { Text("取消") } }) }
}

@Composable private fun LineDetails(network: RailNetwork, close: () -> Unit) {
    val context = LocalContext.current
    val fact = network.line.fact()
    val official = operatorPages.firstOrNull { it.operator == network.line.operator }
    val stations = remember(network) { network.stations.map { it.name }.distinct().sorted() }
    val kilometers = remember(network) {
        network.sections.sumOf { section -> section.shape.zipWithNext().sumOf { (a, b) ->
            hypot((a.lat - b.lat) * 111.195, (a.lon - b.lon) * 111.195 * cos(Math.toRadians((a.lat + b.lat) / 2)))
        } }
    }
    AlertDialog(onDismissRequest = close, title = { Text(network.line.name) }, text = {
        Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${network.line.operator} · ${network.line.categoryLabel()}")
            Text("${stations.size} 个不同站名 · 线形总长约 ${kilometers.fmt()} km")
            Text("线形总长包含数据中的支线及重复轨道时可能与营业里程不同。")
            fact?.let {
                Text(it.text)
                TextButton(onClick = { open(context, it.source) }) { Text("查看科普来源 ↗") }
            }
            official?.let { TextButton(onClick = { open(context, it.url) }) { Text("${it.title} 运行信息 ↗") } }
            Text("站点名录", style = MaterialTheme.typography.titleSmall)
            Text(stations.joinToString(" · ").ifBlank { "本版资料未收录车站" })
            Text(CATALOG_CREDIT, color = Muted, fontSize = 10.sp)
        }
    }, confirmButton = { TextButton(onClick = close) { Text("关闭") } })
}

@Composable private fun DataPage(model: RailViewModel, catalog: CatalogState, keep: Boolean, onKeep: (Boolean) -> Unit) {
    val offline by model.offline.state.collectAsStateWithLifecycle()
    val alerts by model.operator.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var url by rememberSaveable(alerts.url) { mutableStateOf(alerts.url) }
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
            Button(onClick = model::updateCatalog, enabled = !catalog.busy) { Text("联网检查并下载更新") }
            TextButton(onClick = { open(context, CATALOG_SOURCE) }) { Text("国土交通省数据来源 ↗") }
            Text(CATALOG_CREDIT, color = Muted, fontSize = 11.sp)
        } }
        item { CardPanel(Modifier.fillMaxWidth()) {
            SectionTitle("地理院地图缓存")
            Text("内置铁路图可直接离线使用。地理院详细底图可在地图页选择范围下载。")
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
        item { CardPanel(Modifier.fillMaxWidth()) {
            SectionTitle("GTFS Realtime 公告")
            Text("可添加提供方授权公开的 HTTPS Service Alerts 数据源。应用在前台联网时每分钟自动刷新，也可手动刷新。最近一次快照可离线查看。", color = Muted)
            OutlinedTextField(url, { url = it }, label = { Text("公告源 URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { model.saveAlertUrl(url) }, enabled = url.trim() != alerts.url) { Text("保存") }
                TextButton(onClick = { model.refreshAlerts() }, enabled = alerts.url.isNotBlank() && !alerts.busy) { Text(if (alerts.busy) "刷新中…" else "刷新") }
                TextButton(onClick = model::clearAlerts, enabled = alerts.snapshot != null && !alerts.busy) { Text("清除缓存") }
            }
            alerts.error?.let { Text(it, color = Amber) }
            alerts.snapshot?.let { snap ->
                Text("缓存获取时间 ${time(snap.fetchedAt)} · 源时间 ${time(snap.issuedAt)}", fontSize = 12.sp)
                if (snap.alerts.isEmpty()) Text("快照无公告，运行状态未知", color = Muted)
            } ?: Text("暂无已保存公告", color = Muted)
        } }
        items(alerts.snapshot?.alerts.orEmpty()) { notice ->
            CardPanel(Modifier.fillMaxWidth()) {
                Text(notice.title.ifBlank { "运行公告" }, style = MaterialTheme.typography.titleMedium)
                if (notice.routeIds.isNotEmpty()) Text(notice.routeIds.joinToString(" · "), color = Muted)
                Text(notice.description)
            }
        }
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
