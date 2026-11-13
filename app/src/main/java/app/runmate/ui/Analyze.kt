package app.runmate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.runmate.core.Bucket
import app.runmate.core.Fmt
import app.runmate.core.Metric
import app.runmate.core.Ranking
import app.runmate.core.Series
import app.runmate.core.Track
import app.runmate.core.Trend
import app.runmate.core.TrendMetric
import app.runmate.core.XAxis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val MAX_COMPARE = 8

@Composable
fun AnalyzeScreen(selected: SnapshotStateList<String>) {
    var mode by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Text("분석", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
        Chips(listOf(0 to "트랙 비교", 1 to "기간 추세"), mode) { mode = it }
        if (mode == 0) CompareView(selected) else TrendView()
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun CompareView(selected: SnapshotStateList<String>) {
    val metas by app.store.metas.collectAsStateWithLifecycle()
    var picker by remember { mutableStateOf(false) }
    var xAxis by rememberSaveable { mutableStateOf(XAxis.DISTANCE) }
    var view by rememberSaveable { mutableStateOf("PACE") }      // Metric 이름 또는 "ROUTE"
    var smooth by rememberSaveable { mutableFloatStateOf(30f) }
    var from by rememberSaveable { mutableFloatStateOf(0f) }
    var to by rememberSaveable { mutableFloatStateOf(1f) }

    val ids = selected.filter { id -> metas.any { it.id == id } }
    val tracks by produceState<Map<String, Track>>(emptyMap(), ids.joinToString(","), metas.filter { it.id in ids }.joinToString(",") { it.eleSrc }) {
        value = withContext(Dispatchers.IO) { ids.mapNotNull { id -> app.store.loadTrack(id)?.let { id to it } }.toMap() }
    }

    SectionTitle("비교할 기록 (최대 ${MAX_COMPARE}개)")
    if (ids.isEmpty()) Text("내 기록이나 그룹원 기록을 골라 겹쳐서 비교해 보세요.", color = Dim)
    ids.forEachIndexed { i, id ->
        val m = metas.first { it.id == id }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.size(12.dp).background(Palette[i % Palette.size], CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(
                "${if (m.mine) "나" else m.ownerName} · ${fmtDay(m.startMs)} · ${Fmt.km(m.distanceM)}km · ${Fmt.pace(m.avgSpeed)}",
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { selected.remove(id) }) { Text("빼기") }
        }
    }
    Button(onClick = { picker = true }, modifier = Modifier.testTag("pick")) { Text("기록 선택") }

    if (ids.isNotEmpty()) {
        SectionTitle("세로축")
        Chips(Metric.values().map { it.name to it.label } + ("ROUTE" to "경로 겹치기"), view) { view = it }
        if (view == "ROUTE") {
            Surface(Modifier.fillMaxWidth().height(320.dp).padding(top = 8.dp), color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
                TrackPlot(ids.mapIndexedNotNull { i, id ->
                    tracks[id]?.let { t -> PlotTrack(Palette[i % Palette.size], FloatArray(t.n) { t.x[it] / 10f }, FloatArray(t.n) { t.y[it] / 10f }) }
                })
            }
            Text("각 기록의 시작점을 원점(0,0)에 맞춰 겹쳐 그립니다.", color = Dim, style = MaterialTheme.typography.labelSmall)
        } else {
            val metric = Metric.valueOf(view)
            SectionTitle("가로축")
            Chips(XAxis.values().map { it to it.label }, xAxis) { xAxis = it }
            val series = ids.mapIndexedNotNull { i, id ->
                val t = tracks[id] ?: return@mapIndexedNotNull null
                if (!Series.has(t, metric)) return@mapIndexedNotNull null
                val m = metas.first { it.id == id }
                ChartSeries("${if (m.mine) "나" else m.ownerName} ${fmtDay(m.startMs)}", Palette[i % Palette.size], Series.of(t, xAxis, metric, smooth.toDouble()))
            }
            Spacer(Modifier.height(8.dp))
            if (series.isEmpty()) Text(if (tracks.isEmpty()) "불러오는 중…" else "선택한 기록에 이 데이터가 없어요. (API 고도는 인터넷 연결 후 받아집니다)", color = Dim)
            else LineChart(
                series, xAxis.unit, metric.unit, height = 260.dp, xFrom = from, xTo = to,
                invertY = metric == Metric.PACE, yFormat = if (metric == Metric.PACE) { v -> Fmt.paceMin(v) } else { v -> Fmt.num(v) },
            )
            if (series.size < ids.size && series.isNotEmpty()) Text("일부 기록에는 이 데이터가 없어 제외됐어요.", color = Dim, style = MaterialTheme.typography.labelSmall)
            SectionTitle("구간 확대 (가로축 범위)")
            RangeSlider(value = from..to, onValueChange = { from = it.start; to = it.endInclusive }, valueRange = 0f..1f)
            SectionTitle("부드럽게: ${smooth.toInt()}초 평균")
            Slider(value = smooth, onValueChange = { smooth = it }, valueRange = 0f..180f)
        }
    }

    if (picker) AlertDialog(
        onDismissRequest = { picker = false },
        title = { Text("비교할 기록 선택") },
        text = {
            if (metas.isEmpty()) Text("아직 기록이 없어요.")
            else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(metas, key = { it.id }) { m ->
                    val on = m.id in selected
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = on, enabled = on || selected.size < MAX_COMPARE, onCheckedChange = { if (it) { if (m.id !in selected) selected.add(m.id) } else selected.remove(m.id) })
                        Column {
                            Text("${if (m.mine) "나" else m.ownerName} · ${fmtDate(m.startMs)}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${Fmt.km(m.distanceM)} km · ${Fmt.duration(m.durationS)} · ${Fmt.pace(m.avgSpeed)}/km", color = Dim, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { picker = false }) { Text("완료") } },
    )
}

@Composable
private fun TrendView() {
    val metas by app.store.metas.collectAsStateWithLifecycle()
    val groups by app.store.groups.collectAsStateWithLifecycle()
    val me by app.store.identity.collectAsStateWithLifecycle()
    var metric by rememberSaveable { mutableStateOf(TrendMetric.DISTANCE) }
    var bucket by rememberSaveable { mutableStateOf(Bucket.WEEK) }
    var days by rememberSaveable { mutableIntStateOf(90) }
    var hidden by rememberSaveable { mutableStateOf(listOf<String>()) }

    val now = System.currentTimeMillis()
    val inRange = metas.filter { days == 0 || it.startMs >= now - days * 86400_000L }
    val names = Ranking.names(metas, groups, me)
    val owners = inRange.map { it.ownerId }.distinct().sortedBy { if (it == me.id) "" else names[it] ?: it }
    val colored = owners.mapIndexed { i, id -> id to Palette[i % Palette.size] }
    val visible = colored.filter { it.first !in hidden }
    val pts = Trend.compute(inRange.filter { it.ownerId !in hidden }, bucket, metric)

    SectionTitle("항목")
    Chips(TrendMetric.values().map { it to it.label }, metric) { metric = it }
    SectionTitle("묶음")
    Chips(Bucket.values().map { it to it.label }, bucket) { bucket = it }
    SectionTitle("기간")
    Chips(listOf(28 to "4주", 90 to "3개월", 365 to "1년", 0 to "전체"), days) { days = it }
    Spacer(Modifier.height(10.dp))
    if (pts.isEmpty()) Text("이 기간에는 기록이 없어요.", color = Dim)
    else {
        BarChart(pts, visible, metric.unit, height = 260.dp, yFormat = if (metric == TrendMetric.PACE) { v -> Fmt.paceMin(v) } else { v -> Fmt.num(v) })
        if (pts.size > 30) Text("최근 30개 묶음만 표시합니다.", color = Dim, style = MaterialTheme.typography.labelSmall)
    }
    SectionTitle("사람 (눌러서 숨기기/보이기)")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column {
            for ((id, color) in colored) FilterChip(
                selected = id !in hidden,
                onClick = { hidden = if (id in hidden) hidden - id else hidden + id },
                label = { Text(if (id == me.id) "나 (${me.name})" else names[id] ?: "?") },
                leadingIcon = { Box(Modifier.size(10.dp).background(color, CircleShape)) },
            )
        }
    }
}
