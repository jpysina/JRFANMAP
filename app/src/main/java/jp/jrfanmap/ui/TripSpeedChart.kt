package jp.jrfanmap.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.jrfanmap.data.Trip
import jp.jrfanmap.data.TripSpeedHistory
import kotlinx.coroutines.CancellationException
import kotlin.math.ceil

@Composable
internal fun TripSpeedChart(model: RailViewModel, trip: Trip) {
    var loading by remember(trip.id) { mutableStateOf(true) }
    var failed by remember(trip.id) { mutableStateOf(false) }
    val history by produceState<TripSpeedHistory?>(null, trip.id, trip.points) {
        loading = true
        failed = false
        try {
            value = model.tripSpeeds(trip.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        } finally {
            loading = false
        }
    }
    val series = history
    when {
        loading -> Text("读取速度记录…", color = Muted, fontSize = 12.sp)
        failed -> Text("读取速度记录失败", color = Amber, fontSize = 12.sp)
        series == null || series.buckets.isEmpty() -> Text("这段行程没有可用的速度观测。", color = Muted, fontSize = 12.sp)
        else -> SpeedPlot(series)
    }
}

@Composable
private fun SpeedPlot(series: TripSpeedHistory) {
    val peak = series.peakKmh
    val ceiling = (ceil(peak / 20f) * 20f).coerceAtLeast(20f)
    val duration = (series.end - series.start).coerceAtLeast(1)
    Column(Modifier.fillMaxWidth().clip(Cut).background(DisplayPanel).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("速度曲线", color = DisplayText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("最高 ${peak.fmt(0)} km/h", color = DisplayCyan, fontFamily = Mono, fontSize = 12.sp)
        }
        Text("${ceiling.fmt(0)} km/h", fontFamily = Mono, color = DisplayMuted, fontSize = 10.sp)
        Canvas(Modifier.fillMaxWidth().height(86.dp).testTag("trip-speed-plot").semantics {
            contentDescription = "按时间排列的速度曲线，最高 ${peak.fmt(0)} 公里每小时"
        }) {
            val gutter = 3.dp.toPx()
            val width = (size.width - 2 * gutter).coerceAtLeast(0f)
            val height = (size.height - 2 * gutter).coerceAtLeast(0f)
            fun x(time: Long) = gutter + width * ((time - series.start).toDouble() / duration).toFloat()
            fun y(kmh: Float) = gutter + height * (1f - kmh / ceiling)
            for (index in 0..3) {
                val at = gutter + height * index / 3f
                drawLine(DisplayGrid, Offset(gutter, at), Offset(gutter + width, at), 1.dp.toPx())
            }
            val brush = Brush.horizontalGradient(listOf(DisplayCyan, Signal), endX = size.width)
            var previous: Offset? = null
            var lastTime = 0L
            for (bucket in series.buckets) {
                val centerTime = bucket.firstTime + (bucket.lastTime - bucket.firstTime) / 2
                val point = Offset(x(centerTime), y(bucket.meanKmh))
                if (bucket.maxKmh > bucket.meanKmh) {
                    drawLine(DisplayCyan.copy(alpha = .46f), point, Offset(point.x, y(bucket.maxKmh)), 1.dp.toPx())
                }
                if (previous != null && bucket.firstTime - lastTime <= 15_000) {
                    drawLine(brush, previous, point, 2.dp.toPx(), StrokeCap.Round)
                } else {
                    drawCircle(DisplayCyan, 2.dp.toPx(), point)
                }
                lastTime = bucket.lastTime
                previous = point
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0", color = DisplayMuted, fontFamily = Mono, fontSize = 10.sp)
            val seconds = duration / 1000
            Text(if (seconds >= 60) "${seconds / 60} 分 ${seconds % 60} 秒" else "$seconds 秒",
                color = DisplayMuted, fontFamily = Mono, fontSize = 10.sp)
        }
    }
}
