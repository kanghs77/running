package app.runmate.ui

import androidx.compose.foundation.horizontalScroll
import app.runmate.core.tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.runmate.App
import app.runmate.core.Group
import app.runmate.core.Lang
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

val Green = Color(0xFF7BE495)
val Dim = Color(0xAAFFFFFF)

private val Colors = darkColorScheme(
    primary = Green, onPrimary = Color(0xFF08130B), primaryContainer = Color(0xFF1F3A27), onPrimaryContainer = Color(0xFFCFF5D8),
    secondary = Color(0xFF4FC3F7), onSecondary = Color(0xFF04161F), secondaryContainer = Color(0xFF1F3A27), onSecondaryContainer = Color(0xFFCFF5D8),
    background = Color(0xFF101214), onBackground = Color(0xFFECEFF1),
    surface = Color(0xFF101214), onSurface = Color(0xFFECEFF1),
    surfaceVariant = Color(0xFF1C2024), onSurfaceVariant = Color(0xFFB8C0C7),
    surfaceContainer = Color(0xFF171A1D), surfaceContainerHigh = Color(0xFF1C2024), surfaceContainerHighest = Color(0xFF23282D),
    surfaceContainerLow = Color(0xFF14171A), error = Color(0xFFFF8A80), onError = Color(0xFF2B0B08),
)

/** OLED 화면 전력을 줄이기 위해 어두운 테마만 사용 */
@Composable
fun RunMateTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Colors, content = content)

val app: App get() = App.instance

private val dateFmts = java.util.concurrent.ConcurrentHashMap<String, DateTimeFormatter>()
private fun dateFmt(lang: String): DateTimeFormatter = dateFmts.getOrPut(lang) {
    when (lang) {
        "ko" -> DateTimeFormatter.ofPattern("M월 d일 (E) HH:mm", Locale.KOREAN)
        "zh", "ja" -> DateTimeFormatter.ofPattern("M月d日 (E) HH:mm", Locale(lang))
        else -> DateTimeFormatter.ofPattern("d MMM (E) HH:mm", Locale(lang))
    }
}
private val dayFmt = DateTimeFormatter.ofPattern("yyyy.M.d", Locale.KOREAN)
fun fmtDate(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(dateFmt(Lang.code))
fun fmtDay(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(dayFmt)
fun fmtClock(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))

@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier, big: Boolean = false) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = if (big) MaterialTheme.typography.displayMedium else MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelMedium, color = Dim)
    }
}

@Composable
fun <T> Chips(options: List<Pair<T, String>>, selected: T, modifier: Modifier = Modifier, tagPrefix: String? = null, onSelect: (T) -> Unit) {
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEachIndexed { i, (v, label) ->
            FilterChip(selected = v == selected, onClick = { onSelect(v) }, label = { Text(label) },
                modifier = if (tagPrefix != null) Modifier.testTag("$tagPrefix$i") else Modifier)
        }
    }
}

@Composable
fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleSmall, color = Green, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))

@Composable
fun GroupChecklist(groups: List<Group>, checked: Map<String, Boolean>, onToggle: (String, Boolean) -> Unit) {
    Column {
        for (g in groups) Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked[g.id] ?: false, onCheckedChange = { onToggle(g.id, it) })
            Text(tr("${g.name} (${g.members.size + 1}명)", "${g.name} (${g.members.size + 1})"), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
