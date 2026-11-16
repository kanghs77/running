package app.runmate.ui

import androidx.compose.foundation.clickable
import app.runmate.core.tr
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
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import app.runmate.RunController
import app.runmate.core.Export
import app.runmate.core.Fmt
import app.runmate.core.Metric
import app.runmate.core.Pts
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
    val ctx = LocalContext.current
    val zipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) app.scope.launch(Dispatchers.IO) {
            RunController.notice.value = try {
                val n = ctx.contentResolver.openOutputStream(uri)!!.use { out -> Export.zip(out, app.store.metas.value) { app.store.loadTrack(it) } }
                tr("기록 ${n}개를 내보냈어요.", "Exported ${n} runs.")
            } catch (e: Exception) { tr("내보내기에 실패했어요.", "Export failed.") }
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("기록", "History"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (unread > 0) TextButton(onClick = { app.store.updateMetas { if (it.unread) it.copy(unread = false) else it } }) { Text(tr("모두 읽음", "Mark all read")) }
            if (metas.isNotEmpty()) TextButton(modifier = Modifier.testTag("exportAll"), onClick = {
                try { app.skipHomeOnce = true; zipLauncher.launch("runmate_" + fmtDay(System.currentTimeMillis()).replace('.', '-') + ".zip") } catch (e: Exception) { RunController.notice.value = tr("파일 저장 화면을 열 수 없어요.", "Can't open the file save screen.") }
            }) { Text(tr("전체 내보내기", "Export all")) }
        }
        Chips(listOf(0 to tr("전체", "All"), 1 to tr("내 기록", "My runs"), 2 to tr("그룹원 기록", "Members' runs")), filter) { filter = it }
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (filter == 2) tr("아직 받은 기록이 없어요", "No runs received yet") else tr("아직 기록이 없어요.\n달리기 탭에서 시작을 눌러 보세요!", "No runs yet.\nTap Start on the Run tab!"), color = Dim)
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
                    Text(if (m.mine) tr("나", "Me") else m.ownerName, fontWeight = FontWeight.Bold, color = if (m.mine) Green else Color(0xFF4FC3F7), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(8.dp))
                    Text(fmtDate(m.startMs), color = Dim, style = MaterialTheme.typography.bodySmall)
                    if (m.unread) { Spacer(Modifier.width(8.dp)); Badge { Text("NEW") } }
                }
                Text("${Fmt.km(m.distanceM)} km · ${Fmt.duration(m.durationS)} · ${Fmt.pace(m.avgSpeed)}/km", style = MaterialTheme.typography.titleMedium)
                if (m.mine) {
                    val s = when {
                        m.pending.isNotEmpty() -> tr("전송 대기 중", "Waiting to send")
                        m.groupIds.isNotEmpty() -> tr("그룹 ${m.groupIds.size}곳에 보냄", "Sent to ${m.groupIds.size} group(s)")
                        else -> tr("보내지 않음", "Not sent")
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
    val track by produceState<Track?>(null, id, meta.eleSrc, meta.n, meta.durationS) { value = withContext(Dispatchers.IO) { app.store.loadTrack(id) } }
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var shareOpen by remember { mutableStateOf(false) }
    var eleBusy by remember { mutableStateOf(false) }
    var eleMsg by remember { mutableStateOf<String?>(null) }
    var trimOpen by remember { mutableStateOf(false) }
    val detailChart = remember(id) { ChartState() }
    val ctx = LocalContext.current
    fun save(uri: android.net.Uri?, text: () -> String?) {
        if (uri != null) app.scope.launch(Dispatchers.IO) {
            RunController.notice.value = try {
                ctx.contentResolver.openOutputStream(uri)!!.use { it.write(text()!!.toByteArray(Charsets.UTF_8)) }
                tr("파일로 내보냈어요.", "Exported to file.")
            } catch (e: Exception) { tr("내보내기에 실패했어요.", "Export failed.") }
        }
    }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> save(uri) { app.store.loadTrack(id)?.let { Export.csv(it) } } }
    val gpxLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri -> save(uri) { app.store.loadTrack(id)?.let { Export.gpx(it) } } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(tr("‹ 뒤로", "‹ Back")) }
            Text(if (meta.mine) tr("내 기록", "My run") else tr("${meta.ownerName}님의 기록", "${meta.ownerName}'s run"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(fmtDate(meta.startMs), color = Dim)
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Stat(tr("거리 km", "Distance km"), Fmt.km(meta.distanceM)); Stat(tr("시간", "Time"), Fmt.duration(meta.durationS))
            Stat(tr("평균 페이스", "Avg pace"), Fmt.pace(meta.avgSpeed)); Stat(tr("상승 m", "Ascent m"), String.format("%.0f", meta.ascentM))
        }
        val t = track
        if (t == null) Text(tr("불러오는 중…", "Loading…"), color = Dim)
        else {
            val hasApi = t.ele != null
            val chart = detailChart
            val settings by app.store.settings.collectAsStateWithLifecycle()
            val canMap = t.oLat != null
            val distPts = remember(t) { Series.cumDist(t).let { c -> DoubleArray(t.n) { c[it] / 1000.0 } }.let { Pts(it, it) } }
            val pages = remember(t, app.lang.value) {
                buildList {
                    if (hasApi) add(ChartPage(tr("높이(API)", "Elev (API)"), "m", Palette[1], Series.of(t, XAxis.DISTANCE, Metric.ELE_API, 20.0)))
                    add(ChartPage(tr("높이(GPS)", "Elev (GPS)"), "m", Palette[2], Series.of(t, XAxis.DISTANCE, Metric.ELE_GPS, 20.0)))
                    add(ChartPage(tr("페이스", "Pace"), tr("분/km", "min/km"), Green, Series.of(t, XAxis.DISTANCE, Metric.PACE, 30.0), invert = true, fmt = { Fmt.paceMin(it) }))
                    add(ChartPage(tr("속도", "Speed"), "km/h", Palette[3], Series.of(t, XAxis.DISTANCE, Metric.SPEED, 30.0)))
                }
            }
            val markIdx = chart.cursorX?.let { nearest(distPts, it) }
            SectionTitle(if (canMap) tr("경로", "Route") else tr("경로 (시작점 기준 상대좌표)", "Route (relative to start point)"))
            if (canMap) Chips(listOf(true to tr("지도", "Map"), false to tr("격자", "Grid")), settings.mapMode) { v -> app.store.updateSettings { it.copy(mapMode = v) } }
            Surface(Modifier.fillMaxWidth().height(300.dp).padding(top = 4.dp), color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
                if (canMap && settings.mapMode) {
                    val geo = remember(t) { geoTrack(t, Green) }
                    MapTrackPlot(listOfNotNull(geo), markers = listOfNotNull(markIdx?.let { geoMarker(t, it, Color(0xFFFF5252)) }))
                } else {
                    val plot = remember(t) { PlotTrack(Green, FloatArray(t.n) { t.x[it] / 10f }, FloatArray(t.n) { t.y[it] / 10f }) }
                    TrackPlot(listOf(plot), markers = listOfNotNull(markIdx?.let { PlotMarker(Color(0xFFFF5252), t.x[it] / 10f, t.y[it] / 10f) }))
                }
            }
            if (!canMap) Text(tr("받은 기록에는 실제 위치가 없어(상대좌표만 공유) 지도에 올릴 수 없어요.", "Received runs have no real location (only relative coordinates are shared), so they can't be shown on the map."), color = Dim, style = MaterialTheme.typography.labelSmall)

            var page by rememberSaveable(id) { mutableIntStateOf(0) }
            val pg = pages[page.coerceIn(0, pages.size - 1)]
            SectionTitle(tr("그래프", "Charts"))
            Chips(pages.indices.map { it to pages[it].label }, page.coerceIn(0, pages.size - 1)) { page = it }
            if (hasApi) Text(tr("API 출처: ${t.eleSrc}", "API source: ${t.eleSrc}"), color = Dim, style = MaterialTheme.typography.labelSmall)
            // 그래프 위에서 좌우로 끌면 선택 지점이 연속으로 움직이고, 위 경로의 빨간 점이 따라간다
            Box(Modifier.fillMaxWidth().testTag("pager")) {
                LineChart(listOf(ChartSeries(pg.label, pg.color, pg.pts)), "km", pg.unit, height = 200.dp, state = chart,
                    invertY = pg.invert, yFormat = pg.fmt, dragCursor = true)
            }
            if (chart.zoomed) TextButton(onClick = { chart.reset() }) { Text(tr("전체 구간 보기", "Show full range")) }
            if (meta.mine && !hasApi && t.oLat != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(enabled = !eleBusy, onClick = {
                    eleBusy = true; eleMsg = null
                    scope.launch {
                        val ok = app.resolveElevation(id)
                        eleBusy = false; eleMsg = if (ok) null else tr("고도 API에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.", "Couldn't reach the elevation API. Please try again later.")
                    }
                }) { Text(if (eleBusy) tr("고도 받는 중…", "Fetching elevation…") else tr("고도 API에서 높이 받기", "Get elevation from API")) }
                eleMsg?.let { Text(it, color = Color(0xFFFFB74D), style = MaterialTheme.typography.bodySmall) }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCompare) { Text(tr("분석에 추가", "Add to Analyze")) }
            if (meta.mine && groups.isNotEmpty()) Button(onClick = { shareOpen = true }) { Text(tr("그룹에 보내기", "Send to groups")) }
            TextButton(onClick = { confirmDelete = true }) { Text(tr("삭제", "Delete"), color = MaterialTheme.colorScheme.error) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { try { app.skipHomeOnce = true; csvLauncher.launch(Export.fileBase(meta) + ".csv") } catch (e: Exception) { RunController.notice.value = tr("파일 저장 화면을 열 수 없어요.", "Can't open the file save screen.") } }) { Text(tr("CSV로 내보내기", "Export CSV")) }
            if (meta.mine && track?.oLat != null) OutlinedButton(onClick = { try { app.skipHomeOnce = true; gpxLauncher.launch(Export.fileBase(meta) + ".gpx") } catch (e: Exception) { RunController.notice.value = tr("파일 저장 화면을 열 수 없어요.", "Can't open the file save screen.") } }) { Text(tr("GPX로 내보내기", "Export GPX")) }
        }
        if (meta.mine) OutlinedButton(onClick = { trimOpen = true }, modifier = Modifier.testTag("trim")) { Text(tr("끝부분 잘라내기", "Trim the end")) }
        Spacer(Modifier.height(20.dp))
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(tr("기록 삭제", "Delete run")) },
        text = { Text(tr("이 기록을 내 휴대폰에서 삭제할까요? 이미 그룹원에게 보낸 기록은 그룹원 휴대폰에 남습니다.", "Delete this run from your phone? Runs already sent stay on your group members' phones.")) },
        confirmButton = { TextButton(onClick = { confirmDelete = false; app.store.deleteTrack(id); onBack() }, modifier = Modifier.testTag("confirmDelete")) { Text(tr("삭제", "Delete")) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("취소", "Cancel")) } },
    )
    if (trimOpen) {
        val t = track
        if (t == null) trimOpen = false
        else {
            val idle = remember(t) { Series.idleTailStart(t) }
            val picked = detailChart.cursorX?.let { km -> Series.firstIndexAtDistance(t, km * 1000) }?.takeIf { it in 1 until t.n - 1 }
            fun cut(k: Int) {
                trimOpen = false
                detailChart.reset(); detailChart.cursorX = null
                app.scope.launch(Dispatchers.IO) {
                    val nt = app.store.loadTrack(id)?.trimmed(k)
                    RunController.notice.value = if (nt == null || app.store.meta(id) == null) tr("잘라낼 수 없어요.", "Can't trim this run.") else {
                        app.store.saveTrack(nt) { old -> Series.meta(nt, mine = true, old = old) }
                        tr("끝부분을 잘라냈어요.", "The end was trimmed.")
                    }
                }
            }
            AlertDialog(
                onDismissRequest = { trimOpen = false },
                title = { Text(tr("끝부분 잘라내기", "Trim the end")) },
                text = {
                    Column {
                        if (idle != null) {
                            val len = Fmt.duration((t.t[t.n - 1] - t.t[idle]) / 10)
                            Button(onClick = { cut(idle) }, modifier = Modifier.fillMaxWidth().testTag("trimIdle")) { Text(tr("멈춰 있던 끝부분 제거 (마지막 $len)", "Remove the idle tail (last $len)")) }
                        }
                        if (picked != null) {
                            val km = String.format("%.2f", Series.cumDist(t)[picked] / 1000); val at = Fmt.duration(t.t[picked] / 10)
                            OutlinedButton(onClick = { cut(picked) }, modifier = Modifier.fillMaxWidth().testTag("trimPicked")) { Text(tr("그래프에서 고른 지점 이후 제거 ($km km, $at)", "Remove everything after the point picked on the chart ($km km, $at)")) }
                        } else Text(tr("그래프를 탭해 자를 지점을 고르면 그 뒤를 잘라낼 수 있어요.", "Tap the chart to pick a point; everything after it can be removed."), color = Dim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                        Text(tr("잘라낸 부분은 되돌릴 수 없어요. 이미 그룹에 보낸 기록은 그룹원 쪽에서는 바뀌지 않습니다.", "Trimming can't be undone. Runs already sent to groups won't change on your group members' phones."), color = Color(0xFFFFB74D), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 10.dp))
                    }
                },
                confirmButton = { TextButton(onClick = { trimOpen = false }) { Text(tr("취소", "Cancel")) } },
            )
        }
    }
    if (shareOpen) {
        val checked = remember { mutableStateMapOf<String, Boolean>() }
        val already = meta.groupIds.toSet() + meta.pending
        val candidates = groups.filter { it.id !in already }
        AlertDialog(
            onDismissRequest = { shareOpen = false },
            title = { Text(tr("그룹에 보내기", "Send to groups")) },
            text = {
                if (candidates.isEmpty()) Text(tr("이미 모든 그룹에 보낸 기록이에요.", "This run has already been sent to all groups."))
                else GroupChecklist(candidates, candidates.associate { it.id to (checked[it.id] ?: true) }) { g, v -> checked[g] = v }
            },
            confirmButton = {
                val sel = candidates.map { it.id }.filter { checked[it] ?: true }
                TextButton(enabled = sel.isNotEmpty(), onClick = { app.share(id, sel); shareOpen = false }) { Text(tr("보내기", "Send")) }
            },
            dismissButton = { TextButton(onClick = { shareOpen = false }) { Text(tr("취소", "Cancel")) } },
        )
    }
}

class ChartPage(val label: String, val unit: String, val color: Color, val pts: Pts, val invert: Boolean = false, val fmt: (Double) -> String = { Fmt.num(it) })
