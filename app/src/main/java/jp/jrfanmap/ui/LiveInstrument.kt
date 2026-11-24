package jp.jrfanmap.ui

import android.content.Context
import android.graphics.Outline
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider
import androidx.annotation.RequiresApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView
import jp.jrfanmap.R
import jp.jrfanmap.data.Fix
import jp.jrfanmap.data.Telemetry
import jp.jrfanmap.map.LATER_STATION_COLOR
import jp.jrfanmap.map.NAVIGATION_INK
import jp.jrfanmap.map.NEXT_STATION_COLOR
import jp.jrfanmap.rail.AheadStation
import jp.jrfanmap.rail.LiveMatch
import jp.jrfanmap.rail.StationInterval
import kotlin.math.ceil

internal val DisplayPanel = Color(NAVIGATION_INK)
internal val DisplayText = Color(0xFFF4F6FD)
internal val DisplayMuted = Color(0xFFB6C1D7)
internal val DisplayCyan = Color(0xFF9BD9D0)
internal val DisplayGrid = Color(0xFF444C6B)
private val DisplayAccent = Color(NEXT_STATION_COLOR)
private val StationAccent = Color(LATER_STATION_COLOR)
private val HudRadius = 20.dp
private val DisplayShape = RoundedCornerShape(HudRadius)
private val LiquidRim = Brush.linearGradient(listOf(Color(0xD9D8E7FF), Color(0x516F84B0), Color(0xC39BBCDE)))
private val FrostedRim = Brush.linearGradient(listOf(Color(0x9AA7BBD9), Color(0x4B64769A)))
private val DisplayControl = Color(0x258BA5D4)
private val DisplayDigits = FontFamily(Font(R.font.chakra_petch_semibold, FontWeight.SemiBold))

internal enum class HudMaterial(val title: String) {
    LIQUID("液态玻璃"), FROSTED("毛玻璃");

    companion object {
        val supportsLiquid: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        fun fromSetting(value: String?) = if (supportsLiquid && value != FROSTED.name) LIQUID else FROSTED
    }
}

@Composable
internal fun LiveIntervalCard(
    telemetry: Telemetry, fix: Fix?, match: LiveMatch?, ahead: List<AheadStation>, reason: String, material: HudMaterial,
    onRecord: () -> Unit, onPermission: () -> Unit, onDetails: (() -> Unit)?, modifier: Modifier = Modifier,
    glassTarget: BlurTarget? = null
) {
    val speed = fix?.speedMps?.takeIf { it.isFinite() && it >= 0f }?.times(3.6f)
    val interval = match?.interval
    val upcoming = ahead.firstOrNull()
    val routeName = match?.line?.name
    val liquid = material == HudMaterial.LIQUID && glassTarget != null
    Surface(modifier.testTag("live-interval-card"), color = Color.Transparent, shape = DisplayShape,
        border = BorderStroke(if (liquid) 1.25.dp else 1.dp, if (liquid) LiquidRim else FrostedRim),
        shadowElevation = 6.dp) {
        Box {
            if (glassTarget != null) HudBackdrop(glassTarget, liquid, Modifier.matchParentSize())
            Column(Modifier.fillMaxWidth().drawBehind {
                val tint = if (liquid) {
                    listOf(Color(0x263D5585), Color(0x36121936))
                } else if (glassTarget != null) {
                    listOf(Color(0xA6313C58), Color(0xCB1E283F))
                } else {
                    listOf(Color(0xF62A3651), Color(0xFB171E33))
                }
                drawRect(Brush.verticalGradient(tint))
                if (liquid) {
                    val inset = 2.4.dp.toPx()
                    drawRoundRect(Brush.linearGradient(listOf(Color(0x557C9FCE), Color.Transparent, Color(0x477EACD6))),
                        topLeft = Offset(inset, inset), size = Size(size.width - 2 * inset, size.height - 2 * inset),
                        cornerRadius = CornerRadius(HudRadius.toPx() - inset), style = Stroke(.8.dp.toPx()))
                }
            }.padding(start = 13.dp, end = 13.dp, bottom = 9.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(3.dp).height(16.dp).background(if (routeName != null) StationAccent else DisplayMuted, DisplayShape))
                    Text(routeName ?: "线路待确认", Modifier.weight(1f).padding(start = 7.dp),
                        color = DisplayText, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (onDetails != null) TextButton(onClick = onDetails, modifier = Modifier.testTag("live-station-details"),
                        shape = Cut, colors = ButtonDefaults.textButtonColors(contentColor = DisplayText, containerColor = DisplayControl),
                        contentPadding = PaddingValues(horizontal = 8.dp)) { Text("站序 ›", fontSize = 12.sp) }
                    TextButton(onClick = onRecord, modifier = Modifier.testTag("record-button"),
                        colors = ButtonDefaults.textButtonColors(contentColor = if (telemetry.recording) Color(0xFFFFB2A0) else DisplayMuted),
                        contentPadding = PaddingValues(horizontal = 7.dp)) {
                        Text(if (telemetry.recording) "保存" else "记录", fontSize = 12.sp)
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    VelocityReadout(speed, match?.line?.category == "1")
                    if (upcoming != null) Surface(Modifier.weight(1f), shape = RoundedCornerShape(12.dp),
                        color = Color.Transparent, border = BorderStroke(1.dp, DisplayAccent.copy(alpha = .31f))) {
                        Column(Modifier.background(Brush.horizontalGradient(
                            listOf(Color(0xF3263A3D), Color(0xE61D263A))))
                            .padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text("下一经由站", color = DisplayAccent, fontSize = 10.sp, lineHeight = 13.sp)
                            Text(upcoming.station.name, color = DisplayText, fontWeight = FontWeight.Bold,
                                fontSize = 19.sp, lineHeight = 23.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(distanceLabel(upcoming.meters), color = DisplayMuted, fontFamily = DisplayDigits,
                                fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 15.sp,
                                modifier = Modifier.testTag("live-next-distance"))
                        }
                    } else {
                        Text(reason, Modifier.weight(1f), color = DisplayMuted, fontSize = 11.sp,
                            lineHeight = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (ahead.isNotEmpty()) {
                    StationStrip(ahead, interval, Modifier.padding(top = 9.dp))
                }
                if (telemetry.recording || ahead.size == 1) {
                    val note = if (telemetry.recording) "记录中 · ${(telemetry.distanceM / 1000).fmt(2)} km" else "沿轨推断"
                    Text(note, Modifier.padding(top = 5.dp), color = DisplayMuted, fontSize = 10.sp, lineHeight = 14.sp)
                } else if (!telemetry.active && fix == null) {
                    TextButton(onClick = onPermission, modifier = Modifier.align(Alignment.End),
                        colors = ButtonDefaults.textButtonColors(contentColor = DisplayCyan),
                        contentPadding = PaddingValues(horizontal = 4.dp)) { Text("定位设置", fontSize = 12.sp) }
                }
            }
        }
    }
}

@Composable
private fun HudBackdrop(target: BlurTarget, liquid: Boolean, modifier: Modifier) {
    key(target, liquid) {
        AndroidView(factory = { context ->
            val backdrop = if (liquid) LiquidHudBackdrop(context) else BlurView(context)
            backdrop.apply {
                clipToOutline = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, HudRadius.value * view.resources.displayMetrics.density)
                    }
                }
                val radiusDp = if (liquid) 2.4f else 24f
                setupWith(target, 1f, !liquid).setBlurRadius(radiusDp * resources.displayMetrics.density)
            }
        }, modifier = modifier, onRelease = { it.setBlurEnabled(false) })
    }
}

private class LiquidHudBackdrop(context: Context) : BlurView(context) {
    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && width > 0 && height > 0 && isHardwareAccelerated) {
            applyEdgeRefraction(width, height)
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun applyEdgeRefraction(width: Int, height: Int) {
        val density = resources.displayMetrics.density
        val shader = RuntimeShader("""
            uniform shader backdrop;
            uniform float2 viewport;
            uniform float radius;
            uniform float lensWidth;

            half4 main(float2 point) {
                float2 middle = viewport * 0.5;
                float2 position = point - middle;
                float2 edge = abs(position) - (middle - radius);
                float distanceToRim = length(max(edge, float2(0.0))) + min(max(edge.x, edge.y), 0.0) - radius;
                float rim = smoothstep(-lensWidth, 0.0, distanceToRim);
                float2 corner = max(edge, float2(0.0));
                float2 normal = (edge.x > edge.y) ? float2(1.0, 0.0) : float2(0.0, 1.0);
                if (dot(corner, corner) > 0.001) normal = normalize(corner);
                float2 bend = normal * sign(position) * rim * rim * lensWidth * 0.72;
                half4 refracted = backdrop.eval(clamp(point - bend, float2(0.0), viewport - 1.0));
                return mix(half4(0.068, 0.088, 0.152, 1.0), refracted, half(0.25 + rim * 0.59));
            }
        """.trimIndent())
        shader.setFloatUniform("viewport", width.toFloat(), height.toFloat())
        shader.setFloatUniform("radius", HudRadius.value * density)
        shader.setFloatUniform("lensWidth", 21f * density)
        setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "backdrop"))
    }
}

@Composable
internal fun LiveMapControls(
    following: Boolean, immersive: Boolean, material: HudMaterial, onMaterial: (HudMaterial) -> Unit,
    onFollow: () -> Unit, onFullscreen: () -> Unit, modifier: Modifier = Modifier
) {
    val followTint by animateColorAsState(if (following) DisplayCyan else DisplayText, tween(170), label = "map follow tint")
    Surface(modifier, color = DisplayPanel.copy(alpha = .88f), shape = RoundedCornerShape(17.dp),
        border = BorderStroke(1.dp, StationAccent.copy(alpha = .29f)), shadowElevation = 4.dp) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = onFollow, modifier = Modifier.semantics {
                contentDescription = "回到位置"
                stateDescription = if (following) "跟随中" else "自由浏览"
            }) {
                InstrumentIcon(1, followTint)
            }
            HorizontalDivider(Modifier.width(28.dp), color = DisplayGrid.copy(alpha = .9f))
            IconButton(onClick = onFullscreen, modifier = Modifier.semantics {
                contentDescription = if (immersive) "退出全屏" else "全屏"
            }) {
                Canvas(Modifier.size(20.dp)) {
                    val outer = size.width * .15f
                    val inner = size.width * .36f
                    val corner = if (immersive) inner else outer
                    val tip = if (immersive) outer else inner
                    val farCorner = size.width - corner
                    val farTip = size.width - tip
                    val glyph = Path().apply {
                        moveTo(corner, tip)
                        lineTo(corner, corner)
                        lineTo(tip, corner)
                        moveTo(farTip, corner)
                        lineTo(farCorner, corner)
                        lineTo(farCorner, tip)
                        moveTo(corner, farTip)
                        lineTo(corner, farCorner)
                        lineTo(tip, farCorner)
                        moveTo(farTip, farCorner)
                        lineTo(farCorner, farCorner)
                        lineTo(farCorner, farTip)
                    }
                    drawPath(glyph, DisplayText, style = Stroke(width = 1.55.dp.toPx(), cap = StrokeCap.Round))
                }
            }
            if (HudMaterial.supportsLiquid) {
                HorizontalDivider(Modifier.width(28.dp), color = DisplayGrid.copy(alpha = .9f))
                var expanded by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { expanded = true }, modifier = Modifier.size(48.dp).semantics {
                        stateDescription = material.title
                    }, contentPadding = PaddingValues(0.dp), colors = ButtonDefaults.textButtonColors(contentColor = DisplayText)) {
                        Text("材质", fontSize = 11.sp)
                    }
                    DropdownMenu(expanded, onDismissRequest = { expanded = false }, containerColor = DisplayPanel) {
                        HudMaterial.entries.forEach { item ->
                            DropdownMenuItem(text = { Text(item.title, color = if (item == material) DisplayAccent else DisplayText) },
                                onClick = {
                                    expanded = false
                                    onMaterial(item)
                                }, modifier = Modifier.semantics { stateDescription = if (item == material) "当前材质" else "可选材质" })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VelocityReadout(speed: Float?, highSpeed: Boolean) {
    val scale = when {
        speed != null && speed > 360f -> (ceil(speed / 100f) * 100f).toInt()
        highSpeed || (speed ?: 0f) > 160f -> 360
        else -> 160
    }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(speed.fmt(0), fontFamily = DisplayDigits, fontWeight = FontWeight.SemiBold, color = DisplayText,
                fontSize = 44.sp, lineHeight = 45.sp, modifier = Modifier.testTag("live-speed"))
            Text("km/h", Modifier.padding(bottom = 4.dp), color = DisplayMuted, fontSize = 10.sp, lineHeight = 13.sp)
        }
        val level by animateFloatAsState((speed ?: 0f).div(scale).coerceIn(0f, 1f), tween(260), label = "speed scale")
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.width(76.dp).height(8.dp).semantics { contentDescription = "速度刻度 0 到 $scale 公里每小时" }) {
                val step = size.width / 12f
                val gap = 1.2.dp.toPx()
                val radius = CornerRadius(1.dp.toPx())
                for (index in 0 until 12) {
                    val barHeight = size.height * (.55f + index * .04f)
                    val corner = Offset(index * step, size.height - barHeight)
                    val barWidth = (step - gap).coerceAtLeast(0f)
                    drawRoundRect(DisplayGrid, corner, Size(barWidth, barHeight), radius)
                    val fill = (level * 12f - index).coerceIn(0f, 1f)
                    if (fill > 0f) drawRoundRect(StationAccent,
                        corner, Size(barWidth * fill, barHeight), radius)
                }
            }
            Text(scale.toString(), fontFamily = DisplayDigits, fontWeight = FontWeight.SemiBold,
                color = DisplayMuted, fontSize = 9.sp, lineHeight = 10.sp)
        }
    }
}

@Composable
private fun StationStrip(ahead: List<AheadStation>, interval: StationInterval?, modifier: Modifier) {
    val hasPrevious = interval != null
    val stations = listOfNotNull(interval?.previous) + ahead
    val fraction = interval?.progress?.coerceIn(0f, 1f) ?: 0f
    val progress by animateFloatAsState(fraction, tween(300), label = "rail progress")
    val trackSemantics = if (interval == null) Modifier else Modifier.testTag("live-segment-progress").semantics {
        contentDescription = "沿轨区间进度，${interval.previous.station.name}到${interval.next.station.name}"
        progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
    }

    Box(modifier.fillMaxWidth().testTag("live-station-strip")) {
        Canvas(Modifier.fillMaxWidth().height(25.dp).then(trackSemantics)) {
            val spacing = size.width / stations.size
            val first = spacing / 2
            val last = size.width - first
            val railY = 15.dp.toPx()
            drawLine(StationAccent.copy(alpha = .44f), Offset(first, railY), Offset(last, railY), 1.6.dp.toPx(), StrokeCap.Round)
            if (hasPrevious) {
                val current = first + spacing * progress
                drawCircle(DisplayMuted, 3.dp.toPx(), Offset(first, railY))
                drawLine(DisplayCyan, Offset(first, railY), Offset(current, railY), 2.dp.toPx(), StrokeCap.Round)
                val arrow = Path().apply {
                    moveTo(current - 4.dp.toPx(), 2.dp.toPx())
                    lineTo(current + 4.dp.toPx(), 5.5.dp.toPx())
                    lineTo(current - 4.dp.toPx(), 9.dp.toPx())
                    close()
                }
                drawPath(arrow, DisplayText)
            }
        }
        Row(Modifier.fillMaxWidth()) {
            stations.forEachIndexed { index, stop ->
                val order = index + if (hasPrevious) 0 else 1
                val tint = when (order) {
                    0 -> DisplayMuted
                    1 -> DisplayAccent
                    else -> StationAccent
                }
                Column(Modifier.weight(1f).padding(horizontal = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    // The unnumbered node is the previous station. Its dot is part of the progress track.
                    Box(Modifier.padding(top = 7.dp).size(16.dp), contentAlignment = Alignment.Center) {
                        if (order > 0) Surface(shape = CircleShape,
                            color = if (order == 1) tint else DisplayPanel, border = BorderStroke(1.dp, tint)) {
                            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                                Text(order.toString(), color = if (order == 1) DisplayPanel else tint,
                                    fontFamily = DisplayDigits, fontWeight = FontWeight.SemiBold,
                                    fontSize = 10.sp, lineHeight = 12.sp)
                            }
                        }
                    }
                    val nameModifier = if (order > 1) Modifier.testTag("live-following-stop") else Modifier
                    Text(stop.station.name, nameModifier.padding(top = 3.dp).semantics {
                        stateDescription = when (order) {
                            0 -> "上一经由站"
                            1 -> "下一经由站"
                            else -> "前方第 ${order} 站"
                        }
                    }, color = if (order <= 1) tint else DisplayText,
                        fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun distanceLabel(meters: Double) = if (meters < 1000) "${meters.fmt(0)} m" else "${(meters / 1000).fmt(2)} km"
