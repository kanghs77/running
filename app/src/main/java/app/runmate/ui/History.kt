package app.runmate.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.runmate.core.Fmt
import app.runmate.core.Metric
import app.runmate.core.Series
import app.runmate.core.Track
import app.runmate.core.TrackMeta
import app.runmate.core.XAxis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HistoryScreen(onOpen: (String) -> Unit) {
    val metas by app.store.metas.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val shown = when (filter) { 1 -> metas.filter { it.mine }; 2 -> metas.filter { !it.mine }; else -> metas }
    val unread = metas.count { it.unread }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("기록", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (unread > 0) TextButton(onClick = { app.store.updateMetas { if (it.unread) it.copy(unread = false) else it } }) { Text("모두 읽음") }
        }
        Chips(listOf(0 to "전체", 1 to "내 기록", 2 to "그룹원 기록"), filter) { filter = it }
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (filter == 2) "아직 받은 기록이 없어요" else "아직 기록이 없어요.\n달리기 탭에서 시작을 눌러 보세요!", color = Dim)
            }
        } else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
            items(shown, key = { it.id }) { m -> TrackRow(m) { onOpen(m.id) } }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
fun TrackRow(m: TrackMeta, trailing: @Composable () -> Unit = {}, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (m.mine) "나" else m.ownerName, fontWeight = FontWeight.Bold, color = if (m.mine) Green else Color(0xFF4FC3F7), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(8.dp))
                    Text(fmtDate(m.startMs), color = Dim, style = MaterialTheme.typography.bodySmall)
                    if (m.unread) { Spacer(Modifier.width(8.dp)); Badge { Text("NEW") } }
                }
                Text("${Fmt.km(m.distanceM)} km · ${Fmt.duration(m.durationS)} · ${Fmt.pace(m.avgSpeed)}/km", style = MaterialTheme.typography.titleMedium)
                if (m.mine) {
                    val s = when {
                        m.pending.isNotEmpty() -> "전송 대기 중"
                        m.groupIds.isNotEmpty() -> "그룹 ${m.groupIds.size}곳에 보냄"
                        else -> "보내지 않음"
                    }
                    Text(s, color = if (m.pending.isNotEmpty()) Color(0xFFFFB74D) else Dim, style = MaterialTheme.typography.labelSmall)
                }
            }
            trailing()
        }
    }
}

@Composable
fun TrackDetailScreen(id: String, onBack: () -> Unit, onCompare: () -> Unit) {
    val metas by app.store.metas.collectAsStateWithLifecycle()
    val groups by app.store.groups.collectAsStateWithLifecycle()
    val meta = metas.firstOrNull { it.id == id }
    if (meta == null) { LaunchedEffect(id) { onBack() }; return }
    LaunchedEffect(id) { app.store.updateMeta(id) { if (it.unread) it.copy(unread = false) else it } }
    val track by produceState<Track?>(null, id, meta.eleSrc) { value = withContext(Dispatchers.IO) { app.store.loadTrack(id) } }
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var shareOpen by remember { mutableStateOf(false) }
    var eleBusy by remember { mutableStateOf(false) }
    var eleMsg by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ 뒤로") }
            Text(if (meta.mine) "내 기록" else "${meta.ownerName}님의 기록", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(fmtDate(meta.startMs), color = Dim)
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Stat("거리 km", Fmt.km(meta.distanceM)); Stat("시간", Fmt.duration(meta.durationS))
            Stat("평균 페이스", Fmt.pace(meta.avgSpeed)); Stat("상승 m", String.format("%.0f", meta.ascentM))
        }
        val t = track
        if (t == null) Text("불러오는 중…", color = Dim)
        else {
            SectionTitle("경로 (시작점 기준 상대좌표)")
            Surface(Modifier.fillMaxWidth().height(280.dp), color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
                TrackPlot(listOf(PlotTrack(Green, FloatArray(t.n) { t.x[it] / 10f }, FloatArray(t.n) { t.y[it] / 10f })))
            }
            val hasApi = t.ele != null
            SectionTitle(if (hasApi) "상대높이 · 고도 API (${t.eleSrc})" else "상대높이 · GPS 고도")
            LineChart(listOf(ChartSeries("높이", Palette[1], Series.of(t, XAxis.DISTANCE, if (hasApi) Metric.ELE_API else Metric.ELE_GPS, 20.0))), "km", "m", height = 170.dp)
            if (hasApi) {
                SectionTitle("상대높이 · GPS 고도")
                LineChart(listOf(ChartSeries("높이", Palette[2], Series.of(t, XAxis.DISTANCE, Metric.ELE_GPS, 20.0))), "km", "m", height = 150.dp)
            }
            SectionTitle("페이스")
            LineChart(listOf(ChartSeries("페이스", Green, Series.of(t, XAxis.DISTANCE, Metric.PACE, 30.0))), "km", "분/km", height = 170.dp, invertY = true, yFormat = { Fmt.paceMin(it) })
            SectionTitle("속도")
            LineChart(listOf(ChartSeries("속도", Palette[3], Series.of(t, XAxis.DISTANCE, Metric.SPEED, 30.0))), "km", "km/h", height = 170.dp)
            if (meta.mine && !hasApi && t.oLat != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(enabled = !eleBusy, onClick = {
                    eleBusy = true; eleMsg = null
                    scope.launch {
                        val ok = app.resolveElevation(id)
                        eleBusy = false; eleMsg = if (ok) null else "고도 API에 연결하지 못했어요. 잠시 후 다시 시도해 주세요."
                    }
                }) { Text(if (eleBusy) "고도 받는 중…" else "고도 API에서 높이 받기") }
                eleMsg?.let { Text(it, color = Color(0xFFFFB74D), style = MaterialTheme.typography.bodySmall) }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCompare) { Text("분석에 추가") }
            if (meta.mine && groups.isNotEmpty()) Button(onClick = { shareOpen = true }) { Text("그룹에 보내기") }
            TextButton(onClick = { confirmDelete = true }) { Text("삭제", color = MaterialTheme.colorScheme.error) }
        }
        Spacer(Modifier.height(20.dp))
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("기록 삭제") },
        text = { Text("이 기록을 내 휴대폰에서 삭제할까요? 이미 그룹원에게 보낸 기록은 그룹원 휴대폰에 남습니다.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; app.store.deleteTrack(id); onBack() }, modifier = Modifier.testTag("confirmDelete")) { Text("삭제") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } },
    )
    if (shareOpen) {
        val checked = remember { mutableStateMapOf<String, Boolean>() }
        val already = meta.groupIds.toSet() + meta.pending
        val candidates = groups.filter { it.id !in already }
        AlertDialog(
            onDismissRequest = { shareOpen = false },
            title = { Text("그룹에 보내기") },
            text = {
                if (candidates.isEmpty()) Text("이미 모든 그룹에 보낸 기록이에요.")
                else GroupChecklist(candidates, candidates.associate { it.id to (checked[it.id] ?: true) }) { g, v -> checked[g] = v }
            },
            confirmButton = {
                val sel = candidates.map { it.id }.filter { checked[it] ?: true }
                TextButton(enabled = sel.isNotEmpty(), onClick = { app.share(id, sel); shareOpen = false }) { Text("보내기") }
            },
            dismissButton = { TextButton(onClick = { shareOpen = false }) { Text("취소") } },
        )
    }
}
