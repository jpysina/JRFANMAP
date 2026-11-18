package jp.jrfanmap.ui

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import jp.jrfanmap.data.*
import jp.jrfanmap.map.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

private val Ink = Color(0xFF0C1419)
private val Panel = Color(0xFF152128)
private val Mint = Color(0xFF84F4B0)
private val Muted = Color(0xFF9BAEB9)
private val Amber = Color(0xFFFFCF83)
private val colors = darkColorScheme(primary = Mint, onPrimary = Ink, background = Ink, surface = Panel, onSurface = Color(0xFFE8F1F4), onSurfaceVariant = Muted, secondary = Color(0xFF8BCBFF))
private fun Float?.fmt(decimals: Int = 1) = this?.let { String.format(Locale.US, "%.${decimals}f", it) } ?: "—"
private fun Double?.fmt(decimals: Int = 1) = this?.let { String.format(Locale.US, "%.${decimals}f", it) } ?: "—"
private fun time(value: Long?) = value?.let { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(it)) } ?: "—"

@Composable
fun RailApp(model: RailViewModel, onRecord: () -> Unit, onExport: (Long) -> Unit, onKeepAwake: (Boolean) -> Unit) {
    val t by model.store.telemetry.collectAsStateWithLifecycle()
    val railState by model.rail.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val hasKey by model.hasKey.collectAsStateWithLifecycle()
    val prefs = remember { context.getSharedPreferences("display", 0) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var keep by rememberSaveable { mutableStateOf(prefs.getBoolean("keep", true)) }
    var line by rememberSaveable { mutableStateOf(prefs.getString("line", "") ?: "") }
    val snackbar = remember { SnackbarHostState() }
    DisposableEffect(keep) { onKeepAwake(keep); onDispose { onKeepAwake(false) } }
    LaunchedEffect(message) { if (message.isNotBlank()) { snackbar.showSnackbar(message); model.message.value = "" } }
    LaunchedEffect(t.recording) { if (!t.recording) { delay(500); model.refreshTrips() } }
    LaunchedEffect(hasKey, owner) {
        if (hasKey) owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { model.refreshRail(automatic = true); delay(60_000) }
        }
    }
    MaterialTheme(colorScheme = colors, typography = Typography()) {
        Scaffold(containerColor = Ink, snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
            NavigationBar(containerColor = Ink, tonalElevation = 0.dp) {
                listOf("监视", "卫星", "铁路", "行程", "设置").forEachIndexed { i, title ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i; if (i == 3) model.refreshTrips() },
                        icon = { Text(listOf("◉", "⌖", "≋", "↗", "☷")[i], fontSize = 22.sp) }, label = { Text(title) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Mint, indicatorColor = Panel))
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Mint), contentAlignment = Alignment.Center) { Text("JR", color = Ink, fontWeight = FontWeight.Black) }
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text("JRFANMAP", fontWeight = FontWeight.Bold, letterSpacing = 2.sp, fontSize = 16.sp)
                        Text("RAIL OBSERVATORY / 日本", fontSize = 9.sp, color = Muted, letterSpacing = 1.sp)
                    }
                    Text(if (t.recording) "● REC" else "● 待机", color = if (t.recording) Mint else Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
                when (tab) {
                    0 -> Monitor(t, line, onRecord, model.offline, railState) { tab = 2 }
                    1 -> Satellites(t)
                    2 -> Railway(model)
                    3 -> History(model, onExport)
                    else -> Settings(model, keep, { keep = it; prefs.edit().putBoolean("keep", it).apply() }, line, { line = it; prefs.edit().putString("line", it).apply() })
                }
            }
        }
    }
}

@Composable private fun Heading(kicker: String, title: String, trailing: String = "") {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(kicker, color = Mint, fontSize = 10.sp, letterSpacing = 2.sp); Text(title, fontSize = 24.sp, fontWeight = FontWeight.SemiBold) }
        if (trailing.isNotBlank()) Text(trailing, color = Muted, fontSize = 11.sp)
    }
}
@Composable private fun CardPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(Panel).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}
@Composable private fun LabelValue(label: String, value: String, accent: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, color = if (accent) Mint else MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.weight(1.3f))
    }
}
@Composable private fun Monitor(t: Telemetry, line: String, onRecord: () -> Unit, offline: OfflineMaps, rail: RailState, onRail: () -> Unit) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtimeNanos()) }
    LaunchedEffect(Unit) { while (true) { now = SystemClock.elapsedRealtimeNanos(); delay(1000) } }
    val fresh = t.recording && t.fix?.let { FixPolicy.fresh(it, now) } == true
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        val wide = maxWidth > 700.dp
        if (wide) Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            MapPanel(t, offline, Modifier.weight(1.25f).fillMaxHeight())
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Instruments(t, line, now, fresh, onRecord, rail, onRail); Spacer(Modifier.height(16.dp))
            }
        } else LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 18.dp)) {
            item { Heading("LIVE TELEMETRY", line.ifBlank { "沿着铁道，感知此刻" }, if (!t.recording) "未开始采样" else if (t.network) "网络可用" else "网络未连接") }
            item { SpeedPanel(t, fresh) }
            item { RecordButton(t, onRecord) }
            item { RailSummary(rail, onRail) }
            item { MapPanel(t, offline, Modifier.fillMaxWidth().height(330.dp)) }
            item { PositionPanel(t, now, fresh) }
            item { SensorPanel(t) }
        }
    }
}
@Composable private fun Instruments(t: Telemetry, line: String, now: Long, fresh: Boolean, onRecord: () -> Unit, rail: RailState, onRail: () -> Unit) {
    Heading("LIVE TELEMETRY", line.ifBlank { "实时监视" })
    SpeedPanel(t, fresh); RecordButton(t, onRecord); RailSummary(rail, onRail); PositionPanel(t, now, fresh); SensorPanel(t)
}
@Composable private fun RailSummary(state: RailState, onOpen: () -> Unit) {
    CardPanel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("JR EAST / ODPT", color = Mint, fontSize = 10.sp, letterSpacing = 1.sp)
                Text(when {
                    state.loading -> "正在获取运营方信息…"
                    state.error != null -> "查询失败 · 运行状态未知"
                    state.notices.isNotEmpty() -> "${state.notices.size} 条运营信息 · 请核对发布时间"
                    state.fetched != null -> "没有可用记录 · 不代表运行正常"
                    else -> "尚无运营方数据"
                }, color = Muted, fontSize = 12.sp)
                if (state.fetched != null) Text("上次获取 ${time(state.fetched)}", color = Muted, fontSize = 10.sp)
            }
            TextButton(onClick = onOpen) { Text("详情 →", fontSize = 12.sp) }
        }
    }
}
@Composable private fun SpeedPanel(t: Telemetry, fresh: Boolean) {
    CardPanel(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("对地速度", color = Muted, fontSize = 12.sp)
            Text(if (fresh) "${t.fix?.provider?.uppercase()}${if (t.fix?.mock == true) " · 模拟" else ""}" else "等待有效定位", color = if (fresh) Mint else Amber, fontSize = 11.sp)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(if (fresh) t.fix?.speedMps?.times(3.6f).fmt() else "—", color = Mint, fontSize = 64.sp, fontWeight = FontWeight.Light, fontFamily = FontFamily.Monospace, letterSpacing = (-3).sp)
            Text("km/h", color = Muted, fontSize = 16.sp, modifier = Modifier.padding(start = 12.dp, bottom = 18.dp))
        }
        SpeedChart(t.path)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column { Text("本次有效轨迹", color = Muted, fontSize = 10.sp); Text("${(t.distanceM / 1000).fmt(2)} km", fontFamily = FontFamily.Monospace) }
            Column { Text("最高速度", color = Muted, fontSize = 10.sp); Text("${t.maxSpeedKmh.fmt()} km/h", fontFamily = FontFamily.Monospace) }
            Column { Text("速度精度 ±", color = Muted, fontSize = 10.sp); Text("${t.fix?.speedAccuracy?.times(3.6f).fmt()} km/h", fontFamily = FontFamily.Monospace) }
        }
        Text("${t.message} · 速度取自定位服务，不以加速度积分替代", color = Muted, fontSize = 10.sp)
    }
}
@Composable private fun SpeedChart(path: List<Fix>) {
    val points = path.takeLast(120)
    Canvas(Modifier.fillMaxWidth().height(44.dp)) {
        for (i in 0..2) drawLine(Color(0xFF2B3B42), Offset(0f, size.height * i / 2), Offset(size.width, size.height * i / 2), 1f)
        if (points.size < 2) return@Canvas
        val max = maxOf(30f, points.maxOf { (it.speedMps ?: 0f) * 3.6f })
        val p = Path(); var prev: Fix? = null
        points.forEachIndexed { i, f ->
            val x = size.width * i / (points.size - 1); val y = size.height * (1 - (f.speedMps ?: 0f) * 3.6f / max)
            if (prev == null || f.speedMps == null || prev?.speedMps == null || f.elapsedNanos - prev!!.elapsedNanos > 15_000_000_000) p.moveTo(x, y) else p.lineTo(x, y)
            prev = f
        }
        drawPath(p, Mint, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
    }
}
@Composable private fun RecordButton(t: Telemetry, onRecord: () -> Unit) {
    Button(onClick = onRecord, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = if (t.recording) Color(0xFF33454E) else Mint)) {
        Text(if (t.recording) "■  结束并保存行程" else "●  开始定位与行程记录", modifier = Modifier.padding(6.dp), color = if (t.recording) Color.White else Ink)
    }
}
@Composable private fun PositionPanel(t: Telemetry, now: Long, fresh: Boolean) {
    val f = t.fix
    CardPanel(Modifier.fillMaxWidth()) {
        Text("POSITION / 定位详情", fontSize = 12.sp, color = Mint, letterSpacing = 1.sp)
        if (f != null && !fresh) Text("位置已过期或记录已停止；以下为最后一次定位", color = Amber, fontSize = 12.sp)
        LabelValue("纬度 · WGS84", f?.latitude.fmt(6))
        LabelValue("经度 · WGS84", f?.longitude.fmt(6))
        LabelValue("水平精度", "± ${f?.accuracy.fmt()} m")
        LabelValue("椭球高 / 垂直精度", "${f?.altitude.fmt()} / ±${f?.verticalAccuracy.fmt()} m")
        LabelValue("行驶方向 · 真北", "${f?.bearing.fmt(0)}°")
        LabelValue("位置更新时间", time(f?.time))
        LabelValue("定位数据年龄", f?.let { "${FixPolicy.ageSeconds(it, now)} s" } ?: "—")
        if (f?.mock == true) Text("检测到模拟定位，不计入有效里程。", color = Amber, fontSize = 12.sp)
        Text("椭球高不等于海拔；隧道内不推算位置。里程会排除低精度点、跳点及长时间断点。", color = Muted, fontSize = 11.sp)
    }
}
@Composable private fun SensorPanel(t: Telemetry) {
    CardPanel(Modifier.fillMaxWidth()) {
        Text("SENSORS / 设备观测", color = Mint, fontSize = 12.sp, letterSpacing = 1.sp)
        if (!t.recording) Text("记录已停止 · 以下为最后一次观测", color = Muted, fontSize = 11.sp)
        LabelValue("卫星参与 / 可见", "${t.satellites.count { it.used }} / ${t.satellites.size}")
        LabelValue("气压", "${t.pressureHpa.fmt()} hPa")
        LabelValue("手机朝向 · 磁北", "${t.magneticHeading.fmt(0)}°")
        LabelValue("线性加速度模长", "${t.acceleration.fmt(2)} m/s²")
        Text("— 表示未获得读数或设备不支持。手机朝向与列车行驶方向不同；磁场可能受车体干扰。", color = Muted, fontSize = 11.sp)
    }
}

@Composable private fun MapPanel(t: Telemetry, offline: OfflineMaps, modifier: Modifier) {
    val handle = remember { MapHandle() }
    var follow by rememberSaveable { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var area by remember { mutableStateOf<DownloadArea?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtimeNanos()) }
    LaunchedEffect(Unit) { while (true) { now = SystemClock.elapsedRealtimeNanos(); delay(1000) } }
    val stale = t.fix?.let { !FixPolicy.fresh(it, now) } == true
    val download by offline.state.collectAsStateWithLifecycle()
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(Panel)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("地理院 VECTOR", fontSize = 11.sp, color = Mint, modifier = Modifier.weight(1f))
            TextButton(onClick = { follow = !follow }) { Text(if (follow) "跟随位置 ✓" else "自由浏览", fontSize = 11.sp) }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            RailMap(t.fix, t.path, follow, handle, Modifier.fillMaxSize(), onError = { error = it })
            if (t.fix == null || !t.recording || stale) Text(when { t.fix == null -> "尚无设备位置 · 默认浏览东京"; !t.recording -> "记录已停止 · 最后位置"; else -> "定位已过期 · 显示最后位置" }, color = Ink, fontSize = 10.sp, modifier = Modifier.align(Alignment.TopCenter).padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xEFFFFFFF)).padding(6.dp))
        }
        if (error.isNotBlank()) Text(error, color = Amber, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 12.dp))
        Text(download.message, color = Muted, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (download.busy) offline.pause() else area = handle.area() }, modifier = Modifier.weight(1f)) { Text(if (download.busy) "停止下载" else "下载当前范围", fontSize = 11.sp) }
            TextButton(onClick = { confirmDelete = true }, enabled = download.count > 0) { Text("清除离线区", fontSize = 11.sp) }
        }
    }
    area?.let { a -> AlertDialog(onDismissRequest = { area = null }, title = { Text("缓存当前地图范围") }, text = {
        Text("缩放 ${a.minZoom}–${a.maxZoom}，约 ${a.tiles} 个瓦片（不含字体资源，体积取决于区域）。${if (a.tiles > 1500) "范围过大，请放大地图后重试。" else "保留国土地理院出处，最多保存 5 个区域。请在应用前台完成下载，下载完成后可断网查看该范围。"}")
    }, confirmButton = { TextButton(enabled = a.tiles <= 1500, onClick = { offline.download(a); area = null }) { Text("开始下载") } }, dismissButton = { TextButton(onClick = { area = null }) { Text("取消") } }) }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("删除全部离线区域？") }, text = { Text("将删除下载区域；普通浏览缓存仍由地图引擎管理。") }, confirmButton = { TextButton(onClick = { offline.deleteAll(); confirmDelete = false }) { Text("删除") } }, dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } })
}

@Composable private fun Satellites(t: Telemetry) {
    LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        item { Heading("GNSS DIAGNOSTICS", "卫星与信号", "${t.satellites.count { it.used }} / ${t.satellites.size}") }
        item { CardPanel { Text("GPS · QZSS · Galileo · GLONASS · 北斗", color = Mint, fontSize = 13.sp); Text("各星座是否可用取决于手机芯片与系统。请开始记录并靠近车窗；C/N₀ 越高通常表示信号越强。", color = Muted, fontSize = 12.sp) } }
        if (t.satellites.isEmpty()) item { CardPanel { Text("等待卫星状态"); Text("精确定位权限、系统 GPS 和 GNSS 芯片均需可用。没有卫星读数不等于没有网络定位。", color = Muted, fontSize = 12.sp) } }
        items(t.satellites.sortedWith(compareByDescending<Satellite> { it.used }.thenByDescending { it.cn0 })) { s ->
            CardPanel(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("${s.constellation} / ${s.id}", fontFamily = FontFamily.Monospace); Text(if (s.used) "参与定位" else "可见", color = if (s.used) Mint else Muted, fontSize = 11.sp) }
                LinearProgressIndicator(progress = { (s.cn0 / 60).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = if (s.used) Mint else Muted)
                Text("${s.cn0.fmt()} dB-Hz", color = Muted, fontSize = 11.sp)
            }
        }
    }
}
@Composable private fun Railway(model: RailViewModel) {
    val state by model.rail.collectAsStateWithLifecycle()
    val hasKey by model.hasKey.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        item { Heading("OPERATOR INTELLIGENCE", "JR 东日本运行信息") }
        item { CardPanel(Modifier.fillMaxWidth()) {
            Text("公开数据，与设备观测分开显示", color = Mint)
            Text("配置 Key 后，应用可见时每分钟查询一次。ODPT 数据种类、线路覆盖和延迟由运营方决定；无返回记录不代表全线正常。", color = Muted, fontSize = 12.sp)
            Button(onClick = { model.refreshRail() }, enabled = hasKey && !state.loading) { Text(if (state.loading) "正在查询…" else "刷新 ODPT 运行信息") }
            if (!hasKey) Text("请在设置中配置 ODPT API Key。", color = Amber, fontSize = 12.sp)
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.jreast.co.jp/t_i/unko.html"))) }) { Text("打开 JR 东日本官方运行信息 ↗") }
            Text("最近成功获取：${time(state.fetched)}（设备本地时间）", color = Muted, fontSize = 11.sp)
            state.error?.let { Text(it, color = Amber, fontSize = 12.sp) }
            if (state.notices.isEmpty()) Text(if (state.fetched == null) "尚未获取运营方数据" else "本次未返回可用记录，运行状态未知", color = Muted, fontSize = 12.sp)
        } }
        items(state.notices) { n -> CardPanel(Modifier.fillMaxWidth()) {
            Text(n.railway, fontWeight = FontWeight.Bold); Text(n.status, color = Amber); Text(n.text, fontSize = 14.sp)
            Text("运营方发布时间：${n.published.ifBlank { "未提供" }}\n来源：ODPT / JR-East · 请核对时间", color = Muted, fontSize = 11.sp)
        } }
    }
}
@Composable private fun History(model: RailViewModel, onExport: (Long) -> Unit) {
    val trips by model.history.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<Trip?>(null) }
    LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        item { Heading("JOURNEY LOG", "行程记录", "${trips.size} 次") }
        item { Text("轨迹仅存本机，不上传到交通服务。CSV 含精确位置，请自行选择分享对象。", color = Muted, fontSize = 12.sp) }
        if (trips.isEmpty()) item { CardPanel(Modifier.fillMaxWidth()) { Text("还没有行程"); Text("从监视页开始记录，锁屏后仍继续采样。", color = Muted, fontSize = 12.sp) } }
        items(trips, key = { it.id }) { trip -> CardPanel(Modifier.fillMaxWidth()) {
            Text(time(trip.start), fontWeight = FontWeight.Bold)
            Text("${(trip.distance / 1000).fmt(2)} km   ·   ${trip.points} 个定位点", fontFamily = FontFamily.Monospace, color = Mint)
            Text(if (trip.end == null) "记录中" else "结束：${time(trip.end)}${if (trip.interrupted) " · 上次意外中断" else ""}", color = Muted, fontSize = 12.sp)
            Row { TextButton(onClick = { onExport(trip.id) }, enabled = trip.end != null) { Text("导出 CSV") }; TextButton(onClick = { deleting = trip }, enabled = trip.end != null) { Text("删除", color = Amber) } }
        } }
    }
    deleting?.let { trip -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除此行程？") }, text = { Text("本机的定位记录将被永久删除。") }, confirmButton = { TextButton(onClick = { model.delete(trip.id); deleting = null }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}
@Composable private fun Settings(model: RailViewModel, keep: Boolean, onKeep: (Boolean) -> Unit, line: String, onLine: (String) -> Unit) {
    var key by remember { mutableStateOf("") }
    val hasKey by model.hasKey.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Heading("PREFERENCES", "你的随车观测站")
        CardPanel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("前台屏幕常亮"); Text("持续定位和屏幕常亮会增加耗电", color = Muted, fontSize = 11.sp) }; Switch(checked = keep, onCheckedChange = onKeep) }
            OutlinedTextField(value = line, onValueChange = onLine, label = { Text("本次线路 / 车次备注") }, placeholder = { Text("例：東海道線 · 普通 熱海行") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("备注由你填写，不表示应用已识别列车。", fontSize = 11.sp, color = Muted)
        }
        CardPanel(Modifier.fillMaxWidth()) {
            Text("ODPT · 公共交通开放数据", color = Mint)
            Text(if (hasKey) "已保存 Key（不会回显）" else "尚未配置 Key", color = Muted, fontSize = 12.sp)
            OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("新的 API Key") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true, modifier = Modifier.fillMaxWidth())
            Row { Button(onClick = { model.saveKey(key); key = "" }, enabled = key.isNotBlank()) { Text("保存") }; TextButton(onClick = { model.saveKey(""); key = "" }, enabled = hasKey) { Text("移除 Key") } }
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://developer.odpt.org/"))) }) { Text("申请 Key 与查看数据使用条款 ↗") }
            Text("Key 使用 Android Keystore 加密保存在本机，只发送到 api.odpt.org。应用不会上传轨迹。请使用你有权访问的数据。", color = Muted, fontSize = 11.sp)
        }
        CardPanel(Modifier.fillMaxWidth()) {
            Text("地图来源与离线说明", color = Mint)
            Text("国土地理院ベクトルタイル提供実験。地图数据与样式来源：国土地理院。全国覆盖；实验接口可能变动。下载仅覆盖所选范围和缩放等级；完成前请保持应用前台。", color = Muted, fontSize = 12.sp)
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.gsi.go.jp/help/use.html"))) }) { Text("地理院地图使用说明 ↗") }
            Text("地图会请求瓦片和字体，服务商可观察请求对应的地图范围。GNSS 与传感器存在误差；本工具供铁道爱好者观察使用。", color = Muted, fontSize = 11.sp)
        }
        Text("JRFANMAP 0.1.0 / Kotlin · Compose · MapLibre", color = Muted, fontSize = 11.sp)
        Spacer(Modifier.height(16.dp))
    }
}
