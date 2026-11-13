package app.runmate.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.runmate.core.Fmt
import app.runmate.core.Geo
import app.runmate.core.Pts
import app.runmate.core.TrendPoint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

val Palette = listOf(
    Color(0xFF7BE495), Color(0xFF4FC3F7), Color(0xFFFFB74D), Color(0xFFE57373),
    Color(0xFFBA68C8), Color(0xFFFFF176), Color(0xFF4DB6AC), Color(0xFFF06292),
)
private val Grid = Color(0x33FFFFFF)
private val Axis = Color(0xAAFFFFFF)
private val LabelStyle = TextStyle(color = Color(0xCCFFFFFF), fontSize = 10.sp)

class ChartSeries(val label: String, val color: Color, val pts: Pts)

private fun DrawScope.label(tm: TextMeasurer, text: String, x: Float, y: Float, alignRight: Boolean = false, center: Boolean = false, style: TextStyle = LabelStyle) {
    val l = tm.measure(text, style)
    var px = if (alignRight) x - l.size.width else if (center) x - l.size.width / 2f else x
    px = px.coerceIn(0f, max(0f, size.width - l.size.width))
    val py = y.coerceIn(0f, max(0f, size.height - l.size.height))
    drawText(l, topLeft = Offset(px, py))
}

/**
 * 여러 계열을 겹쳐 그리는 선 그래프.
 * xFrom/xTo: 표시할 x 구간(전체 범위에 대한 0~1 비율). 터치/드래그하면 그 위치의 값을 아래에 보여준다.
 */
@Composable
fun LineChart(
    series: List<ChartSeries>,
    xUnit: String,
    yUnit: String,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp,
    xFrom: Float = 0f,
    xTo: Float = 1f,
    invertY: Boolean = false,
    yFormat: (Double) -> String = { Fmt.num(it) },
    showCursorValues: Boolean = true,
) {
    val tm = rememberTextMeasurer()
    var cursor by remember { mutableStateOf<Float?>(null) }   // 그래프 영역 내 0~1
    val valid = series.filter { it.pts.n > 0 }
    var gx0 = Double.MAX_VALUE; var gx1 = -Double.MAX_VALUE
    for (s in valid) { gx0 = min(gx0, s.pts.xs.first()); gx1 = max(gx1, s.pts.xs.last()) }
    if (valid.isEmpty()) { gx0 = 0.0; gx1 = 1.0 }
    if (gx1 <= gx0) gx1 = gx0 + 1e-6
    val f0 = xFrom.coerceIn(0f, 1f); val f1 = xTo.coerceIn(0f, 1f).let { if (it - f0 < 0.01f) min(1f, f0 + 0.01f) else it }
    val x0 = gx0 + (gx1 - gx0) * f0; val x1 = gx0 + (gx1 - gx0) * f1
    var y0 = Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
    for (s in valid) for (i in 0 until s.pts.n) {
        val x = s.pts.xs[i]; val y = s.pts.ys[i]
        if (x < x0 || x > x1 || !y.isFinite()) continue
        y0 = min(y0, y); y1 = max(y1, y)
    }
    if (y0 > y1) { y0 = 0.0; y1 = 1.0 }
    if (y1 - y0 < 1e-9) { y0 -= 0.5; y1 += 0.5 }
    val pad = (y1 - y0) * 0.06; y0 -= pad; y1 += pad

    Column(modifier) {
        Canvas(
            Modifier.fillMaxWidth().height(height)
                .pointerInput(Unit) {
                    detectTapGestures { o -> cursor = ((o.x - 44.dp.toPx()) / (size.width - 52.dp.toPx())).coerceIn(0f, 1f) }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        cursor = ((change.position.x - 44.dp.toPx()) / (size.width - 52.dp.toPx())).coerceIn(0f, 1f)
                    }
                }
        ) {
            val l = 44.dp.toPx(); val r = size.width - 8.dp.toPx(); val t = 8.dp.toPx(); val b = size.height - 20.dp.toPx()
            if (r <= l || b <= t) return@Canvas
            fun sx(x: Double) = (l + (x - x0) / (x1 - x0) * (r - l)).toFloat()
            fun sy(y: Double): Float { val f = (y - y0) / (y1 - y0); return (if (invertY) t + f * (b - t) else b - f * (b - t)).toFloat() }
            for (v in Geo.ticks(y0, y1, 5)) {
                val y = sy(v)
                drawLine(Grid, Offset(l, y), Offset(r, y), 1f)
                label(tm, yFormat(v), l - 4.dp.toPx(), y - 7.dp.toPx(), alignRight = true)
            }
            for (v in Geo.ticks(x0, x1, 5)) {
                val x = sx(v)
                drawLine(Grid, Offset(x, t), Offset(x, b), 1f)
                label(tm, Fmt.num(v), x, b + 3.dp.toPx(), center = true)
            }
            drawLine(Axis, Offset(l, b), Offset(r, b), 1.5f)
            drawLine(Axis, Offset(l, t), Offset(l, b), 1.5f)
            label(tm, yUnit, l + 4.dp.toPx(), t)
            label(tm, xUnit, r, b - 16.dp.toPx(), alignRight = true)
            for (s in valid) {
                val p = Path()
                var pen = false
                val step = max(1, s.pts.n / 1500)
                var i = 0
                while (i < s.pts.n) {
                    val x = s.pts.xs[i]; val y = s.pts.ys[i]
                    if (x < x0 || x > x1 || !y.isFinite()) { pen = false } else {
                        if (pen) p.lineTo(sx(x), sy(y)) else { p.moveTo(sx(x), sy(y)); pen = true }
                    }
                    i += step
                }
                drawPath(p, s.color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            cursor?.let { c ->
                val cx = l + c * (r - l)
                drawLine(Color.White, Offset(cx, t), Offset(cx, b), 1.5f)
                val xv = x0 + c * (x1 - x0)
                for (s in valid) nearest(s.pts, xv)?.let { i ->
                    if (s.pts.ys[i].isFinite() && s.pts.xs[i] in x0..x1) drawCircle(s.color, 4.dp.toPx(), Offset(sx(s.pts.xs[i]), sy(s.pts.ys[i])))
                }
            }
        }
        val c = cursor
        if (showCursorValues && c != null && valid.isNotEmpty()) {
            val xv = x0 + c * (x1 - x0)
            Text("$xUnit ${Fmt.num(xv)}", style = MaterialTheme.typography.labelSmall, color = Color.White)
            for (s in valid) {
                val i = nearest(s.pts, xv)
                val ok = i != null && s.pts.ys[i].isFinite() && abs(s.pts.xs[i] - xv) <= (gx1 - gx0) * 0.05 + 1e-9
                Text("● ${s.label}: ${if (ok) yFormat(s.pts.ys[i!!]) + " " + yUnit else "-"}", color = s.color, style = MaterialTheme.typography.labelMedium)
            }
        } else if (showCursorValues && valid.isNotEmpty()) {
            Text("그래프를 터치하면 값을 볼 수 있어요", style = MaterialTheme.typography.labelSmall, color = Color(0x99FFFFFF))
        }
    }
}

/** x에 가장 가까운 점의 인덱스 (xs는 오름차순) */
fun nearest(p: Pts, x: Double): Int? {
    if (p.n == 0) return null
    var lo = 0; var hi = p.n - 1
    while (lo < hi) { val m = (lo + hi) / 2; if (p.xs[m] < x) lo = m + 1 else hi = m }
    return if (lo > 0 && abs(p.xs[lo - 1] - x) <= abs(p.xs[lo] - x)) lo - 1 else lo
}

class PlotTrack(val color: Color, val xs: FloatArray, val ys: FloatArray)

/**
 * 지도 없이 시작점 기준 상대좌표(m)로 경로를 그린다. 가로/세로 축척이 같고 축척 막대를 표시한다.
 * ● 시작, ■ 끝. 위쪽이 북쪽.
 */
@Composable
fun TrackPlot(tracks: List<PlotTrack>, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer()
    Canvas(modifier.fillMaxSize()) {
        var minX = 0f; var maxX = 0f; var minY = 0f; var maxY = 0f
        for (t in tracks) for (i in t.xs.indices) {
            minX = min(minX, t.xs[i]); maxX = max(maxX, t.xs[i]); minY = min(minY, t.ys[i]); maxY = max(maxY, t.ys[i])
        }
        val padPx = 18.dp.toPx()
        val w = size.width - 2 * padPx; val h = size.height - 2 * padPx - 14.dp.toPx()
        if (w <= 0 || h <= 0) return@Canvas
        val rx = max(maxX - minX, 20f); val ry = max(maxY - minY, 20f)
        val scale = min(w / rx, h / ry)              // px per m
        val cx = (minX + maxX) / 2; val cy = (minY + maxY) / 2
        fun px(x: Float) = size.width / 2 + (x - cx) * scale
        fun py(y: Float) = padPx + h / 2 - (y - cy) * scale
        // 격자: 축척 막대 길이 간격
        val bar = Geo.niceFloor((size.width * 0.3f / scale).toDouble()).toFloat()
        var gx = Math.floor(((cx - size.width / 2 / scale) / bar).toDouble()).toFloat() * bar
        var guard = 0
        while (px(gx) < size.width && guard++ < 60) { if (px(gx) >= 0) drawLine(Grid, Offset(px(gx), 0f), Offset(px(gx), size.height), 1f); gx += bar }
        var gy = Math.floor(((cy - size.height / 2 / scale) / bar).toDouble()).toFloat() * bar
        guard = 0
        while (py(gy) > 0 && guard++ < 60) { if (py(gy) <= size.height) drawLine(Grid, Offset(0f, py(gy)), Offset(size.width, py(gy)), 1f); gy += bar }
        for (t in tracks) {
            if (t.xs.isEmpty()) continue
            val p = Path()
            p.moveTo(px(t.xs[0]), py(t.ys[0]))
            for (i in 1 until t.xs.size) p.lineTo(px(t.xs[i]), py(t.ys[i]))
            drawPath(p, t.color, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawCircle(Color.White, 5.dp.toPx(), Offset(px(t.xs[0]), py(t.ys[0])))
            drawCircle(t.color, 3.dp.toPx(), Offset(px(t.xs[0]), py(t.ys[0])))
            val ex = px(t.xs.last()); val ey = py(t.ys.last()); val q = 4.dp.toPx()
            drawRect(Color.White, Offset(ex - q, ey - q), Size(2 * q, 2 * q))
            drawRect(t.color, Offset(ex - q / 2, ey - q / 2), Size(q, q))
        }
        // 축척 막대
        val by = size.height - 8.dp.toPx(); val bx = 10.dp.toPx(); val bw = bar * scale
        drawLine(Color.White, Offset(bx, by), Offset(bx + bw, by), 2.dp.toPx())
        drawLine(Color.White, Offset(bx, by - 4.dp.toPx()), Offset(bx, by + 1.dp.toPx()), 2.dp.toPx())
        drawLine(Color.White, Offset(bx + bw, by - 4.dp.toPx()), Offset(bx + bw, by + 1.dp.toPx()), 2.dp.toPx())
        label(tm, Fmt.meters(bar.toDouble()), bx + bw + 6.dp.toPx(), by - 12.dp.toPx(), style = LabelStyle.copy(fontSize = 12.sp, color = Color.White))
        label(tm, "N↑", size.width - 6.dp.toPx(), 4.dp.toPx(), alignRight = true)
    }
}

/** 기간별 묶음 막대 그래프 (사람별 색) */
@Composable
fun BarChart(points: List<TrendPoint>, owners: List<Pair<String, Color>>, unit: String, modifier: Modifier = Modifier, height: Dp = 220.dp, yFormat: (Double) -> String = { Fmt.num(it) }) {
    val tm = rememberTextMeasurer()
    val pts = points.takeLast(30)
    Canvas(modifier.fillMaxWidth().height(height)) {
        val l = 44.dp.toPx(); val r = size.width - 8.dp.toPx(); val t = 8.dp.toPx(); val b = size.height - 20.dp.toPx()
        if (r <= l || b <= t) return@Canvas
        var ymax = 0.0
        for (p in pts) for ((id, _) in owners) p.values[id]?.let { ymax = max(ymax, it) }
        if (ymax <= 0) ymax = 1.0
        ymax *= 1.08
        fun sy(v: Double) = (b - v / ymax * (b - t)).toFloat()
        for (v in Geo.ticks(0.0, ymax, 4)) {
            drawLine(Grid, Offset(l, sy(v)), Offset(r, sy(v)), 1f)
            label(tm, yFormat(v), l - 4.dp.toPx(), sy(v) - 7.dp.toPx(), alignRight = true)
        }
        drawLine(Axis, Offset(l, b), Offset(r, b), 1.5f)
        label(tm, unit, l + 4.dp.toPx(), t)
        if (pts.isEmpty() || owners.isEmpty()) return@Canvas
        val slot = (r - l) / pts.size
        val bw = min(slot * 0.8f / owners.size, 22.dp.toPx())
        val every = max(1, (pts.size * 34.dp.toPx() / (r - l)).toInt() + 1)
        for ((i, p) in pts.withIndex()) {
            val cx = l + slot * (i + 0.5f)
            val start = cx - bw * owners.size / 2
            for ((k, o) in owners.withIndex()) {
                val v = p.values[o.first] ?: continue
                drawRect(o.second, Offset(start + k * bw, sy(v)), Size(max(1f, bw - 1f), b - sy(v)))
            }
            if (i % every == 0 || i == pts.size - 1) label(tm, p.label, cx, b + 3.dp.toPx(), center = true)
        }
    }
}
