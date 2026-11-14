package app.runmate.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.testTag
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
import app.runmate.core.Mercator
import app.runmate.core.Track
import app.runmate.core.Pts
import app.runmate.core.TrendPoint
import kotlinx.coroutines.launch
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

/** 그래프의 확대 구간과 선택 위치. 여러 그래프가 같은 상태를 공유할 수 있다. */
class ChartState {
    var from by mutableFloatStateOf(0f)
    var to by mutableFloatStateOf(1f)
    /** 선택한 가로축 값(예: km). 지도 위 위치 표시에 쓰인다. */
    var cursorX by mutableStateOf<Double?>(null)
    val zoomed: Boolean get() = to - from < 0.999f
    fun reset() { from = 0f; to = 1f }

    /** 두 손가락 확대/이동. centerFrac: 보이는 구간 안에서의 중심 위치(0~1), panFrac: 보이는 구간 대비 이동량 */
    fun transform(zoom: Float, centerFrac: Float, panFrac: Float) {
        val span = to - from
        val z = if (zoom.isFinite() && zoom > 0f) zoom else 1f
        val newSpan = (span / z).coerceIn(0.02f, 1f)
        val anchor = from + centerFrac.coerceIn(0f, 1f) * span
        val f = (anchor - centerFrac.coerceIn(0f, 1f) * newSpan - panFrac * newSpan).coerceIn(0f, 1f - newSpan)
        from = f; to = f + newSpan
    }
}

/**
 * 여러 계열을 겹쳐 그리는 선 그래프.
 * - 탭: 그 위치의 값을 선택(아래에 값 표시, state.cursorX 갱신)
 * - 두 손가락: 가로 방향 확대/축소/이동
 * - dragCursor=true면 한 손가락 가로 드래그로도 선택 위치를 옮긴다(스와이프로 넘기는 화면에서는 false)
 */
@Composable
fun LineChart(
    series: List<ChartSeries>,
    xUnit: String,
    yUnit: String,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp,
    state: ChartState = remember { ChartState() },
    invertY: Boolean = false,
    yFormat: (Double) -> String = { Fmt.num(it) },
    showCursorValues: Boolean = true,
    dragCursor: Boolean = true,
) {
    val tm = rememberTextMeasurer()
    val valid = series.filter { it.pts.n > 0 }
    var gx0 = Double.MAX_VALUE; var gx1 = -Double.MAX_VALUE
    for (s in valid) { gx0 = min(gx0, s.pts.xs.first()); gx1 = max(gx1, s.pts.xs.last()) }
    if (valid.isEmpty()) { gx0 = 0.0; gx1 = 1.0 }
    if (gx1 <= gx0) gx1 = gx0 + 1e-6
    val f0 = state.from.coerceIn(0f, 1f); val f1 = state.to.coerceIn(0f, 1f).let { if (it - f0 < 0.01f) min(1f, f0 + 0.01f) else it }
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
    val range = rememberUpdatedState(x0 to x1)

    Column(modifier) {
        Canvas(
            Modifier.fillMaxWidth().height(height).testTag("chart")
                .pointerInput(dragCursor) {
                    val l = 44.dp.toPx(); val rpad = 8.dp.toPx()
                    fun frac(x: Float) = ((x - l) / (size.width - l - rpad).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    fun pick(x: Float) { val (a, b) = range.value; state.cursorX = a + frac(x) * (b - a) }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var multi = false; var moved = false; var dragging = false
                        while (true) {
                            val ev = awaitPointerEvent()
                            val pressed = ev.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                multi = true
                                val w = (size.width - l - rpad).coerceAtLeast(1f)
                                state.transform(ev.calculateZoom(), frac(ev.calculateCentroid(useCurrent = false).x), ev.calculatePan().x / w)
                                ev.changes.forEach { it.consume() }
                            } else if (!multi) {
                                val ch = pressed[0]
                                val d = ch.position - down.position
                                if (!moved && d.getDistance() > viewConfiguration.touchSlop) {
                                    moved = true
                                    dragging = dragCursor && abs(d.x) > abs(d.y)
                                }
                                if (dragging) { pick(ch.position.x); ch.consume() }
                            } else ev.changes.forEach { it.consume() }
                        }
                        if (!multi && !moved) pick(down.position.x)
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
                // 보이는 구간의 점 개수에 맞춰 솎는다(확대하면 더 촘촘하게)
                val step = max(1, ((s.pts.n * (f1 - f0)) / 1500).toInt())
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
            state.cursorX?.let { xv ->
                if (xv in x0..x1) {
                    val cx = sx(xv)
                    drawLine(Color.White, Offset(cx, t), Offset(cx, b), 1.5f)
                    for (s in valid) nearest(s.pts, xv)?.let { i ->
                        if (s.pts.ys[i].isFinite() && s.pts.xs[i] in x0..x1) drawCircle(s.color, 4.dp.toPx(), Offset(sx(s.pts.xs[i]), sy(s.pts.ys[i])))
                    }
                }
            }
        }
        val xv = state.cursorX
        if (showCursorValues && xv != null && valid.isNotEmpty()) {
            Text("$xUnit ${Fmt.num(xv)}", style = MaterialTheme.typography.labelSmall, color = Color.White)
            for (s in valid) {
                val i = nearest(s.pts, xv)
                val ok = i != null && s.pts.ys[i].isFinite() && abs(s.pts.xs[i] - xv) <= (gx1 - gx0) * 0.05 + 1e-9
                Text("● ${s.label}: ${if (ok) yFormat(s.pts.ys[i!!]) + " " + yUnit else "-"}", color = s.color, style = MaterialTheme.typography.labelMedium)
            }
        } else if (showCursorValues && valid.isNotEmpty()) {
            Text("탭: 값·위치 보기 · 두 손가락: 확대/축소", style = MaterialTheme.typography.labelSmall, color = Color(0x99FFFFFF))
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
class PlotMarker(val color: Color, val x: Float, val y: Float)

/**
 * 지도 없이 시작점 기준 상대좌표(m)로 경로를 그린다. 가로/세로 축척이 같고 축척 막대를 표시한다.
 * ● 시작, ■ 끝. 위쪽이 북쪽.
 */
@Composable
fun TrackPlot(tracks: List<PlotTrack>, modifier: Modifier = Modifier, markers: List<PlotMarker> = emptyList()) {
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
        for (m in markers) {
            drawCircle(Color.White, 9.dp.toPx(), Offset(px(m.x), py(m.y)))
            drawCircle(m.color, 6.dp.toPx(), Offset(px(m.x), py(m.y)))
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

class GeoTrack(val color: Color, val lats: DoubleArray, val lons: DoubleArray)
class GeoMarker(val color: Color, val lat: Double, val lon: Double)

/** 내 기록(위경도가 있는 기록)을 지도용 좌표로 바꾼다. 받은 기록은 위치 정보가 없어 null. */
fun geoTrack(t: Track, color: Color, maxPts: Int = 800): GeoTrack? {
    val lat0 = t.oLat ?: return null
    val lon0 = t.oLon ?: return null
    val step = max(1, t.n / maxPts)
    val idx = (0 until t.n step step).toMutableList().also { if (it.last() != t.n - 1) it.add(t.n - 1) }
    val lats = DoubleArray(idx.size); val lons = DoubleArray(idx.size)
    for ((k, i) in idx.withIndex()) { val (la, lo) = Geo.unproject(lat0, lon0, t.x[i] / 10.0, t.y[i] / 10.0); lats[k] = la; lons[k] = lo }
    return GeoTrack(color, lats, lons)
}

fun geoMarker(t: Track, i: Int, color: Color): GeoMarker? {
    val lat0 = t.oLat ?: return null
    val lon0 = t.oLon ?: return null
    val (la, lo) = Geo.unproject(lat0, lon0, t.x[i] / 10.0, t.y[i] / 10.0)
    return GeoMarker(color, la, lo)
}

/** 실제 지도(OpenStreetMap) 위에 경로를 그린다. 기록 전체가 보이도록 자동으로 맞춘다. */
@Composable
fun MapTrackPlot(tracks: List<GeoTrack>, modifier: Modifier = Modifier, markers: List<GeoMarker> = emptyList()) {
    val tm = rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val tiles = remember { androidx.compose.runtime.mutableStateMapOf<String, androidx.compose.ui.graphics.ImageBitmap>() }
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
        var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE; var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE; var latSum = 0.0; var cnt = 0
        for (t in tracks) for (i in t.lats.indices) {
            val x = Mercator.x(t.lons[i]); val y = Mercator.y(t.lats[i])
            minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y); latSum += t.lats[i]; cnt++
        }
        if (cnt == 0 || w <= 0f || h <= 0f) return@BoxWithConstraints
        val lat = latSum / cnt
        val minSpan = 80.0 / Mercator.metersPerUnit(lat)      // 최소 80m 범위
        val fit = min(w * 0.8 / max(maxX - minX, minSpan), h * 0.8 / max(maxY - minY, minSpan))   // 화면 px / 세계 단위
        val z = Mercator.zoomFor(fit, density)
        val n = 1 shl z
        val cx = (minX + maxX) / 2; val cy = (minY + maxY) / 2
        val tx0 = Math.floor((cx - w / 2 / fit) * n).toInt().coerceIn(0, n - 1); val tx1 = Math.floor((cx + w / 2 / fit) * n).toInt().coerceIn(0, n - 1)
        val ty0 = Math.floor((cy - h / 2 / fit) * n).toInt().coerceIn(0, n - 1); val ty1 = Math.floor((cy + h / 2 / fit) * n).toInt().coerceIn(0, n - 1)
        val few = (tx1 - tx0 + 1) * (ty1 - ty0 + 1) <= 48
        androidx.compose.runtime.LaunchedEffect(z, tx0, tx1, ty0, ty1) {
            if (few) for (x in tx0..tx1) for (y in ty0..ty1) {
                val key = "$z/$x/$y"
                if (key !in tiles) launch { app.tiles.get(z, x, y)?.let { tiles[key] = it } }
            }
        }
        Canvas(Modifier.fillMaxSize().testTag("map")) {
            fun sx(wx: Double) = (w / 2 + (wx - cx) * fit).toFloat()
            fun sy(wy: Double) = (h / 2 + (wy - cy) * fit).toFloat()
            drawRect(Color(0xFFDDE3E8))
            if (few) for (x in tx0..tx1) for (y in ty0..ty1) {
                val img = tiles["$z/$x/$y"] ?: continue
                val l = sx(x.toDouble() / n); val t = sy(y.toDouble() / n); val r = sx((x + 1.0) / n); val b = sy((y + 1.0) / n)
                drawImage(img, dstOffset = androidx.compose.ui.unit.IntOffset(Math.floor(l.toDouble()).toInt(), Math.floor(t.toDouble()).toInt()),
                    dstSize = androidx.compose.ui.unit.IntSize(Math.ceil((r - l).toDouble()).toInt() + 1, Math.ceil((b - t).toDouble()).toInt() + 1))
            }
            for (t in tracks) {
                if (t.lats.isEmpty()) continue
                val p = Path()
                p.moveTo(sx(Mercator.x(t.lons[0])), sy(Mercator.y(t.lats[0])))
                for (i in 1 until t.lats.size) p.lineTo(sx(Mercator.x(t.lons[i])), sy(Mercator.y(t.lats[i])))
                drawPath(p, Color(0xCC000000), style = Stroke(6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(p, t.color, style = Stroke(3.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                val s0 = Offset(sx(Mercator.x(t.lons[0])), sy(Mercator.y(t.lats[0])))
                drawCircle(Color.Black, 6.dp.toPx(), s0); drawCircle(Color.White, 4.5.dp.toPx(), s0)
                val e = Offset(sx(Mercator.x(t.lons.last())), sy(Mercator.y(t.lats.last()))); val q = 5.dp.toPx()
                drawRect(Color.Black, Offset(e.x - q, e.y - q), Size(2 * q, 2 * q)); drawRect(Color.White, Offset(e.x - q * 0.7f, e.y - q * 0.7f), Size(q * 1.4f, q * 1.4f))
            }
            for (m in markers) {
                val o = Offset(sx(Mercator.x(m.lon)), sy(Mercator.y(m.lat)))
                drawCircle(Color.Black, 11.dp.toPx(), o); drawCircle(Color.White, 9.dp.toPx(), o); drawCircle(m.color, 6.dp.toPx(), o)
            }
            val mpp = Mercator.metersPerUnit(lat) / fit
            val bar = Geo.niceFloor(w * 0.3 * mpp); val bw = (bar / mpp).toFloat()
            val by = h - 10.dp.toPx(); val bx = 10.dp.toPx()
            drawRect(Color(0xAAFFFFFF), Offset(bx - 4.dp.toPx(), by - 18.dp.toPx()), Size(bw + 70.dp.toPx(), 24.dp.toPx()))
            drawLine(Color.Black, Offset(bx, by), Offset(bx + bw, by), 2.dp.toPx())
            drawLine(Color.Black, Offset(bx, by - 4.dp.toPx()), Offset(bx, by + 1.dp.toPx()), 2.dp.toPx())
            drawLine(Color.Black, Offset(bx + bw, by - 4.dp.toPx()), Offset(bx + bw, by + 1.dp.toPx()), 2.dp.toPx())
            label(tm, Fmt.meters(bar), bx + bw + 6.dp.toPx(), by - 13.dp.toPx(), style = LabelStyle.copy(fontSize = 12.sp, color = Color.Black))
            label(tm, "© OpenStreetMap", w - 4.dp.toPx(), h - 14.dp.toPx(), alignRight = true, style = LabelStyle.copy(fontSize = 9.sp, color = Color(0xFF333333)))
        }
    }
}
