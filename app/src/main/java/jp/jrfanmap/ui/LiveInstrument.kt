package jp.jrfanmap.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.jrfanmap.data.Fix
import jp.jrfanmap.data.Telemetry
import jp.jrfanmap.rail.LiveMatch
import jp.jrfanmap.rail.StationInterval

internal val DisplayPanel = Color(0xFF0C1923)
internal val DisplayText = Color(0xFFF0F7F8)
internal val DisplayMuted = Color(0xFF9DB6BC)
internal val DisplayCyan = Color(0xFF61E4DC)
internal val DisplayGrid = Color(0xFF304C57)
private val DisplayAccent = Color(0xFFC8F888)
private val DisplayShape = RoundedCornerShape(18.dp)

@Composable
internal fun LiveIntervalCard(
    telemetry: Telemetry, fix: Fix?, match: LiveMatch?, reason: String,
    onRecord: () -> Unit, onPermission: () -> Unit, onDetails: (() -> Unit)?, modifier: Modifier = Modifier
) {
    val speed = fix?.speedMps?.takeIf { it.isFinite() && it >= 0f }?.times(3.6f)
    val interval = match?.interval
    val upcoming = interval?.next ?: match?.next?.firstOrNull()
    val routeName = match?.line?.name
    Surface(modifier.testTag("live-interval-card"), color = Color.Transparent, shape = DisplayShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, DisplayGrid), shadowElevation = 4.dp) {
        Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(
            Color(0xF5203541), DisplayPanel.copy(alpha = .97f)
        ))).padding(start = 13.dp, end = 13.dp, bottom = 10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(if (routeName != null) DisplayCyan else DisplayMuted, DisplayShape))
                Text(routeName ?: "线路待确认", Modifier.weight(1f).padding(start = 7.dp),
                    color = DisplayText, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = onRecord, modifier = Modifier.testTag("record-button"),
                    colors = ButtonDefaults.textButtonColors(contentColor = if (telemetry.recording) Color(0xFFFFB2A0) else DisplayCyan),
                    contentPadding = PaddingValues(horizontal = 7.dp)) {
                    Text(if (telemetry.recording) "保存" else "记录", fontSize = 12.sp)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(speed.fmt(0), fontFamily = Mono, fontWeight = FontWeight.Bold, color = DisplayAccent,
                        fontSize = 38.sp, lineHeight = 42.sp, modifier = Modifier.testTag("live-speed"))
                    Text("km/h", Modifier.padding(bottom = 4.dp), color = DisplayMuted, fontSize = 10.sp, lineHeight = 13.sp)
                }
                if (upcoming != null) {
                    Box(Modifier.width(1.dp).height(33.dp).background(DisplayGrid))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text("下一经由站", color = DisplayMuted, fontSize = 10.sp, lineHeight = 13.sp)
                        Text(upcoming.station.name, color = DisplayText, fontWeight = FontWeight.Bold,
                            fontSize = 16.sp, lineHeight = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(distanceLabel(upcoming.meters), color = DisplayCyan, fontFamily = Mono,
                            fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.testTag("live-next-distance"))
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
            if (interval != null || onDetails != null || (!telemetry.active && fix == null) || telemetry.recording) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(when {
                        telemetry.recording -> "记录中 · ${(telemetry.distanceM / 1000).fmt(2)} km"
                        interval != null -> "沿轨推断"
                        else -> ""
                    }, Modifier.weight(1f), color = DisplayMuted, fontSize = 10.sp, lineHeight = 14.sp)
                    if (onDetails != null) TextButton(onClick = onDetails, modifier = Modifier.testTag("live-station-details"),
                        colors = ButtonDefaults.textButtonColors(contentColor = DisplayCyan),
                        contentPadding = PaddingValues(horizontal = 4.dp)) { Text("站序 ›", fontSize = 12.sp) }
                    else if (!telemetry.active && fix == null) TextButton(onClick = onPermission,
                        colors = ButtonDefaults.textButtonColors(contentColor = DisplayCyan),
                        contentPadding = PaddingValues(horizontal = 4.dp)) { Text("定位设置", fontSize = 12.sp) }
                }
            }
        }
    }
}

@Composable
private fun IntervalTrack(interval: StationInterval, modifier: Modifier) {
    val fraction = interval.progress.coerceIn(0f, 1f)
    Canvas(modifier.fillMaxWidth().height(16.dp).testTag("live-segment-progress").semantics {
        contentDescription = "沿轨区间进度，${interval.previous.station.name}到${interval.next.station.name}"
        progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
    }) {
        val inset = 5.dp.toPx()
        val end = size.width - inset
        val center = center.y
        val current = inset + (end - inset).coerceAtLeast(0f) * fraction
        drawLine(DisplayGrid, Offset(inset, center), Offset(end, center), 3.dp.toPx(), StrokeCap.Round)
        drawLine(Brush.horizontalGradient(listOf(Color(0xFF21A9C5), DisplayCyan), startX = inset, endX = end),
            Offset(inset, center), Offset(current, center), 3.dp.toPx(), StrokeCap.Round)
        drawCircle(DisplayCyan, 3.dp.toPx(), Offset(inset, center))
        drawCircle(DisplayMuted, 3.dp.toPx(), Offset(end, center))
        drawCircle(DisplayCyan.copy(alpha = .26f), 7.dp.toPx(), Offset(current, center))
        drawCircle(DisplayAccent, 4.dp.toPx(), Offset(current, center))
    }
}

private fun distanceLabel(meters: Double) = if (meters < 1000) "${meters.fmt(0)} m" else "${(meters / 1000).fmt(2)} km"
