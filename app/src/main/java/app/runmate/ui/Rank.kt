package app.runmate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.runmate.core.Fmt
import app.runmate.core.RankBy
import app.runmate.core.Ranking
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** 기간 프리셋 -> [from, to) ms */
fun periodRange(key: String, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> {
    fun ms(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
    val end = ms(today.plusDays(1))
    return when (key) {
        "week" -> ms(today.minusDays((today.dayOfWeek.value - 1).toLong())) to end
        "month" -> ms(today.withDayOfMonth(1)) to end
        "30d" -> ms(today.minusDays(29)) to end
        "year" -> ms(today.withDayOfYear(1)) to end
        else -> 0L to Long.MAX_VALUE
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RankScreen() {
    val metas by app.store.metas.collectAsStateWithLifecycle()
    val groups by app.store.groups.collectAsStateWithLifecycle()
    val me by app.store.identity.collectAsStateWithLifecycle()
    var period by rememberSaveable { mutableStateOf("month") }
    var customFrom by rememberSaveable { mutableLongStateOf(0L) }
    var customTo by rememberSaveable { mutableLongStateOf(0L) }
    var groupId by rememberSaveable { mutableStateOf<String?>(null) }
    var by by rememberSaveable { mutableStateOf(RankBy.DISTANCE) }
    var pickerOpen by remember { mutableStateOf(false) }

    val gid = groupId?.takeIf { g -> groups.any { it.id == g } }
    val (from, to) = if (period == "custom") customFrom to customTo else periodRange(period)
    val names = Ranking.names(metas, groups, me)
    val members = if (gid == null) groups.flatMap { g -> g.members.map { it.id } } + me.id
    else groups.first { it.id == gid }.members.map { it.id } + me.id
    val rows = Ranking.rank(Ranking.filter(metas, from, to, gid), names, by, members.distinct())
    val top = rows.maxOfOrNull { when (by) { RankBy.DISTANCE -> it.distanceM; RankBy.TIME -> it.durationS.toDouble(); RankBy.RUNS -> it.runs.toDouble() } } ?: 0.0

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Text("순위", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
        SectionTitle("기간")
        Chips(listOf("week" to "이번 주", "month" to "이번 달", "30d" to "최근 30일", "year" to "올해", "all" to "전체", "custom" to "직접 선택"), period) {
            if (it == "custom") pickerOpen = true else period = it
        }
        if (period == "custom") Text("${fmtDay(customFrom)} ~ ${fmtDay(customTo - 1)}", color = Dim, style = MaterialTheme.typography.bodySmall)
        if (groups.isNotEmpty()) {
            SectionTitle("그룹")
            Chips(listOf<Pair<String?, String>>(null to "전체") + groups.map { it.id to it.name }, gid) { groupId = it }
        }
        SectionTitle("기준")
        Chips(RankBy.values().map { it to it.label }, by) { by = it }
        Spacer(Modifier.height(12.dp))
        rows.forEachIndexed { i, r ->
            val v = when (by) { RankBy.DISTANCE -> r.distanceM; RankBy.TIME -> r.durationS.toDouble(); RankBy.RUNS -> r.runs.toDouble() }
            val isMe = r.ownerId == me.id
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (v > 0) when (i) { 0 -> "🥇"; 1 -> "🥈"; 2 -> "🥉"; else -> "${i + 1}" } else "-", modifier = Modifier.width(34.dp), style = MaterialTheme.typography.titleMedium)
                        Text(if (isMe) "${r.name} (나)" else r.name, fontWeight = FontWeight.Bold, color = if (isMe) Green else Color.White, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            when (by) { RankBy.DISTANCE -> "${Fmt.km(r.distanceM)} km"; RankBy.TIME -> Fmt.duration(r.durationS.toInt()); RankBy.RUNS -> "${r.runs}회" },
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        )
                    }
                    Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(6.dp).background(Color(0x22FFFFFF), RoundedCornerShape(3.dp))) {
                        if (top > 0 && v > 0) Box(Modifier.fillMaxWidth((v / top).toFloat().coerceIn(0.01f, 1f)).height(6.dp).background(if (isMe) Green else Color(0xFF4FC3F7), RoundedCornerShape(3.dp)))
                    }
                    Text("${r.runs}회 · ${Fmt.km(r.distanceM)} km · ${Fmt.duration(r.durationS.toInt())} · 상승 ${String.format("%.0f", r.ascentM)}m", color = Dim, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        Text(
            if (gid == null) "내 모든 기록과, 그룹원이 나에게 보낸 기록을 합산합니다."
            else "이 그룹에 보낸 내 기록과, 이 그룹으로 받은 기록만 합산합니다.",
            color = Dim, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 10.dp),
        )
    }

    if (pickerOpen) {
        val st = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { pickerOpen = false },
            confirmButton = {
                TextButton(enabled = st.selectedStartDateMillis != null, onClick = {
                    val zone = ZoneId.systemDefault()
                    fun local(utcMs: Long) = Instant.ofEpochMilli(utcMs).atZone(ZoneOffset.UTC).toLocalDate()
                    val s = local(st.selectedStartDateMillis!!)
                    val e = local(st.selectedEndDateMillis ?: st.selectedStartDateMillis!!)
                    customFrom = s.atStartOfDay(zone).toInstant().toEpochMilli()
                    customTo = e.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                    period = "custom"; pickerOpen = false
                }) { Text("적용") }
            },
            dismissButton = { TextButton(onClick = { pickerOpen = false }) { Text("취소") } },
        ) { DateRangePicker(state = st, modifier = Modifier.height(480.dp), title = { Text("기간 선택", Modifier.padding(16.dp)) }) }
    }
}
