package jp.jrfanmap.ui

import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
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
import jp.jrfanmap.rail.LiveMatch
import jp.jrfanmap.rail.StationInterval
import kotlin.math.ceil

internal val DisplayPanel = Color(0xFF0C1923)
internal val DisplayText = Color(0xFFF0F7F8)
internal val DisplayMuted = Color(0xFF9DB6BC)
internal val DisplayCyan = Color(0xFF61E4DC)
internal val DisplayGrid = Color(0xFF304C57)
private val DisplayAccent = Color(0xFFDEFBA5)
private val DisplayShape = RoundedCornerShape(20.dp)
private val DisplayRim = Brush.linearGradient(listOf(Color(0xB574B4C4), Color(0x4158869A), Color(0x94597999)))
private val DisplayControl = Color(0xFF64E2E1).copy(alpha = .12f)
private val DisplayDigits = FontFamily(Font(R.font.chakra_petch_semibold, FontWeight.SemiBold))

@Composable
internal fun LiveIntervalCard(
    telemetry: Telemetry, fix: Fix?, match: LiveMatch?, reason: String,
    onRecord: () -> Unit, onPermission: () -> Unit, onDetails: (() -> Unit)?, modifier: Modifier = Modifier,
    glassTarget: BlurTarget? = null
) {
    val speed = fix?.speedMps?.takeIf { it.isFinite() && it >= 0f }?.times(3.6f)
    val interval = match?.interval
    val upcomingStops = (listOfNotNull(interval?.next) + match?.next.orEmpty())
        .asSequence().distinctBy { it.station.name }.take(3).toList()
    val upcoming = upcomingStops.firstOrNull()
    val following = upcomingStops.drop(1)
    val routeName = match?.line?.name
    Surface(modifier.testTag("live-interval-card"), color = Color.Transparent, shape = DisplayShape,
        border = BorderStroke(1.dp, DisplayRim), shadowElevation = 6.dp) {
        Box {
            if (glassTarget != null) HudBackdrop(glassTarget, Modifier.matchParentSize())
            Column(Modifier.fillMaxWidth().drawBehind {
                val top = if (glassTarget != null) Color(0xA9183445) else Color(0xF9183445)
                val bottom = if (glassTarget != null) Color(0xDB0B1726) else Color(0xFB0B1726)
                drawRect(Brush.verticalGradient(listOf(top, bottom)))
                drawRect(Brush.radialGradient(listOf(Color(0x315CC2D8), Color.Transparent),
                    center = Offset(size.width * .12f, 0f), radius = size.width * .72f))
                drawLine(Brush.horizontalGradient(listOf(Color.Transparent, Color(0xC4A5F1FF), Color.Transparent)),
                    Offset(size.width * .06f, 1.dp.toPx()), Offset(size.width * .94f, 1.dp.toPx()), 1.dp.toPx())
            }.padding(start = 13.dp, end = 13.dp, bottom = 9.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(3.dp).height(16.dp).background(if (routeName != null) DisplayCyan else DisplayMuted, DisplayShape))
                    Text(routeName ?: "线路待确认", Modifier.weight(1f).padding(start = 7.dp),
                        color = DisplayText, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (onDetails != null) TextButton(onClick = onDetails, modifier = Modifier.testTag("live-station-details"),
                        shape = Cut, colors = ButtonDefaults.textButtonColors(contentColor = DisplayCyan, containerColor = DisplayControl),
                        contentPadding = PaddingValues(horizontal = 8.dp)) { Text("站序 ›", fontSize = 12.sp) }
                    TextButton(onClick = onRecord, modifier = Modifier.testTag("record-button"),
                        colors = ButtonDefaults.textButtonColors(contentColor = if (telemetry.recording) Color(0xFFFFB2A0) else DisplayCyan),
                        contentPadding = PaddingValues(horizontal = 7.dp)) {
                        Text(if (telemetry.recording) "保存" else "记录", fontSize = 12.sp)
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    VelocityReadout(speed, match?.line?.category == "1")
                    if (upcoming != null) Surface(Modifier.weight(1f), shape = RoundedCornerShape(10.dp),
                        color = Color(0xBF0C202B), border = BorderStroke(1.dp, Color(0x634B98A9))) {
                        Column(Modifier.padding(horizontal = 9.dp, vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text("下一经由站", color = DisplayCyan, fontSize = 10.sp, lineHeight = 13.sp)
                            Text(upcoming.station.name, color = DisplayText, fontWeight = FontWeight.Bold,
                                fontSize = 18.sp, lineHeight = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(distanceLabel(upcoming.meters), color = DisplayCyan, fontFamily = DisplayDigits,
                                fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 14.sp,
                                modifier = Modifier.testTag("live-next-distance"))
                        }
                    } else {
                        Text(reason, Modifier.weight(1f), color = DisplayMuted, fontSize = 11.sp,
                            lineHeight = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (interval != null) {
                    IntervalTrack(interval, Modifier.padding(top = 7.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(interval.previous.station.name, Modifier.weight(1f), color = DisplayMuted,
                            fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(interval.next.station.name, color = DisplayCyan, fontSize = 11.sp, lineHeight = 15.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 122.dp))
                    }
                }
                if (following.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(top = 6.dp).testTag("live-following-stops"),
                    horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("后续", color = DisplayMuted, fontSize = 10.sp, lineHeight = 14.sp)
                    following.forEach { stop ->
                        Text("›", color = DisplayCyan, fontSize = 13.sp, lineHeight = 14.sp)
                        Text(stop.station.name, Modifier.weight(1f, fill = false).testTag("live-following-stop")
                            .background(DisplayControl, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp), color = DisplayText,
                            fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (telemetry.recording || (upcoming != null && following.isEmpty())) {
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
private fun HudBackdrop(target: BlurTarget, modifier: Modifier) {
    key(target) {
        AndroidView(factory = { context ->
            BlurView(context).apply {
                clipToOutline = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, 20f * view.resources.displayMetrics.density)
                    }
                }
                setupWith(target).setBlurRadius(16f)
            }
        }, modifier = modifier, onRelease = { it.setBlurEnabled(false) })
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
            Text(speed.fmt(0), fontFamily = DisplayDigits, fontWeight = FontWeight.SemiBold, color = DisplayAccent,
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
                    if (fill > 0f) drawRoundRect(if (index < 8) DisplayCyan else DisplayAccent,
                        corner, Size(barWidth * fill, barHeight), radius)
                }
            }
            Text(scale.toString(), fontFamily = DisplayDigits, fontWeight = FontWeight.SemiBold,
                color = DisplayMuted, fontSize = 9.sp, lineHeight = 10.sp)
        }
    }
}

@Composable
private fun IntervalTrack(interval: StationInterval, modifier: Modifier) {
    val fraction = interval.progress.coerceIn(0f, 1f)
    val animated by animateFloatAsState(fraction, tween(300), label = "rail progress")
    Canvas(modifier.fillMaxWidth().height(17.dp).testTag("live-segment-progress").semantics {
        contentDescription = "沿轨区间进度，${interval.previous.station.name}到${interval.next.station.name}"
        progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
    }) {
        val inset = 5.dp.toPx()
        val end = size.width - inset
        val center = center.y
        val current = inset + (end - inset).coerceAtLeast(0f) * animated
        drawLine(DisplayGrid, Offset(inset, center), Offset(end, center), 2.dp.toPx(), StrokeCap.Round)
        for (tick in 1..19) {
            val x = inset + (end - inset) * tick / 20f
            val lineColor = if (x <= current) DisplayCyan.copy(alpha = .65f) else DisplayGrid
            drawLine(lineColor, Offset(x, center - 3.dp.toPx()), Offset(x, center + 3.dp.toPx()), 1.dp.toPx())
        }
        drawLine(Brush.horizontalGradient(listOf(Color(0xFF21A9C5), DisplayCyan), startX = inset, endX = end),
            Offset(inset, center), Offset(current, center), 3.dp.toPx(), StrokeCap.Round)
        drawCircle(DisplayCyan, 3.dp.toPx(), Offset(inset, center))
        drawCircle(DisplayMuted, 3.dp.toPx(), Offset(end, center))
        drawCircle(Brush.radialGradient(listOf(DisplayCyan.copy(alpha = .6f), Color.Transparent),
            center = Offset(current, center), radius = 8.dp.toPx()), 8.dp.toPx(), Offset(current, center))
        drawCircle(DisplayAccent, 3.5.dp.toPx(), Offset(current, center))
    }
}

private fun distanceLabel(meters: Double) = if (meters < 1000) "${meters.fmt(0)} m" else "${(meters / 1000).fmt(2)} km"
