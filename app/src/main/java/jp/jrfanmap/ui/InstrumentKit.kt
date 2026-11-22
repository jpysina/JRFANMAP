package jp.jrfanmap.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

internal val Ink = Color(0xFF132B30)
internal val Paper = Color(0xFFF2F3ED)
internal val Panel = Color(0xFFFFFEF9)
internal val Muted = Color(0xFF4E6568)
internal val Mint = Color(0xFF006D65)
internal val Amber = Color(0xFF8A470A)
internal val Signal = Color(0xFFE2F477)
internal val Grid = Color(0xFFCFD7D1)
internal val Metal = Color(0xFFB5CDCA)
internal val Mono = FontFamily.Monospace
internal val Cut = RoundedCornerShape(12.dp)

internal val instrumentColors = lightColorScheme(
    primary = Mint, onPrimary = Color.White, secondary = Muted, onSecondary = Color.White,
    background = Paper, onBackground = Ink, surface = Panel, onSurface = Ink,
    surfaceVariant = Color(0xFFE3EBE5), onSurfaceVariant = Muted, outline = Muted,
    outlineVariant = Grid, surfaceContainerHigh = Panel
)

@Composable
internal fun CardPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(Cut).background(Panel).border(1.dp, Grid, Cut).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
internal fun LabelValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, color = Muted, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.weight(.85f))
        Text(value, fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 19.sp,
            color = Ink, modifier = Modifier.weight(1.15f))
    }
}

@Composable
internal fun PageHead(title: String, page: Int, action: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(34.dp).background(Ink, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            InstrumentIcon(page, Signal)
        }
        Text(title, Modifier.weight(1f), color = Ink, fontWeight = FontWeight.Bold, fontSize = 25.sp, lineHeight = 30.sp)
        action()
    }
}

@Composable
internal fun SectionTitle(title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(4.dp, 16.dp).background(Mint))
        Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Ink)
    }
}

@Composable
internal fun InstrumentNav(selected: Int, side: Boolean, select: (Int) -> Unit) {
    val labels = listOf("LIVE", "GPS", "地图", "行程", "数据")
    val entry: @Composable (Int, Modifier) -> Unit = { index, modifier ->
        val active = selected == index
        Column(modifier.clip(RoundedCornerShape(8.dp))
            .background(if (active) Ink else Panel)
            .selectable(active, role = Role.Tab, onClick = { select(index) }).testTag("tab-$index")
            .padding(vertical = 3.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            InstrumentIcon(index, if (active) Signal else Muted)
            Text(labels[index], color = if (active) Color.White else Muted, fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
    if (side) Column(Modifier.fillMaxHeight().width(70.dp).background(Panel).padding(5.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)) {
        labels.indices.forEach { entry(it, Modifier.weight(1f, fill = false).fillMaxWidth().height(58.dp)) }
    } else Row(Modifier.fillMaxWidth().background(Panel).navigationBarsPadding().padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        labels.indices.forEach { entry(it, Modifier.weight(1f).height(52.dp)) }
    }
}

@Composable
internal fun InstrumentIcon(index: Int, tint: Color) {
    Canvas(Modifier.size(22.dp)) {
        val u = size.minDimension / 24f
        fun p(x: Int, y: Int) = Offset(x * u, y * u)
        fun line(x: Int, y: Int, xx: Int, yy: Int) = drawLine(tint, p(x,y), p(xx,yy), 1.7f*u)
        val stroke = Stroke(1.7f*u)
        when (index) {
            0 -> {
                drawArc(tint, 145f, 250f, false, p(2,4), Size(20*u,20*u), style = stroke)
                line(12,14,17,8)
                drawCircle(tint,2*u,p(12,14))
                line(7,20,17,20)
            }
            1 -> {
                drawCircle(tint,7*u,p(12,12),style=stroke)
                drawCircle(tint,2*u,p(12,12))
                line(12,1,12,7); line(12,17,12,23); line(1,12,7,12); line(17,12,23,12)
            }
            2 -> {
                line(3,6,9,3); line(9,3,15,6); line(15,6,21,3); line(21,3,21,18)
                line(21,18,15,21); line(15,21,9,18); line(9,18,3,21); line(3,21,3,6)
                line(9,3,9,18); line(15,6,15,21)
            }
            3 -> {
                line(6,6,6,14); line(6,14,18,10); line(18,10,18,18)
                drawCircle(tint,2.6f*u,p(6,4),style=stroke)
                drawCircle(tint,2.6f*u,p(18,20),style=stroke)
            }
            else -> {
                repeat(3) { n ->
                    val y = 4 + n*7
                    drawRect(tint,p(3,y), Size(18*u,4*u), style=stroke)
                    line(5,y+2,7,y+2)
                }
            }
        }
    }
}

@Composable
internal fun Compass(bearing: Float?) {
    Box(Modifier.size(68.dp), contentAlignment = Alignment.TopCenter) {
        Canvas(Modifier.fillMaxSize().padding(6.dp)) {
            val r = size.minDimension * .46f
            drawCircle(Metal.copy(alpha=.55f), r, style = Stroke(1.dp.toPx()))
            for (i in 0 until 16) {
                val a = i * Math.PI / 8
                val v = Offset(sin(a).toFloat(), -cos(a).toFloat())
                drawLine(Metal, center + v * (r - if (i%4==0) 5.dp.toPx() else 2.dp.toPx()), center + v*r)
            }
            if (bearing != null) rotate(bearing, center) {
                val needle = Path().apply {
                    moveTo(center.x,center.y-r*.7f)
                    lineTo(center.x+r*.23f,center.y+r*.35f)
                    lineTo(center.x,center.y+r*.19f)
                    lineTo(center.x-r*.23f,center.y+r*.35f)
                    close()
                }
                drawPath(needle,Signal)
            } else drawLine(Metal,center-Offset(4.dp.toPx(),0f),center+Offset(4.dp.toPx(),0f),2.dp.toPx())
        }
        Text("N", Modifier.background(Ink).padding(horizontal = 2.dp), color = Metal,
            fontFamily = Mono, fontSize = 9.sp, lineHeight=10.sp)
    }
}
