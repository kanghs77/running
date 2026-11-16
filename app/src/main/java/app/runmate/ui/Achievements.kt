package app.runmate.ui

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.runmate.core.tr

/** 뱃지 종류(틀). 획득 기준은 아직 정해지지 않아 모두 '준비 중'으로 보여준다. */
private class BadgeKind(val icon: String, val name: String, val desc: String)

@Composable
fun AchievementsScreen() {
    val kinds = listOf(
        BadgeKind("⚡", tr("페이스", "Pace"), tr("빠른 평균 페이스로 달리면 받는 뱃지", "Earned by running at a fast average pace")),
        BadgeKind("📏", tr("한 번에 달린 거리", "Distance in one run"), tr("한 번에 긴 거리를 달리면 받는 뱃지", "Earned by covering a long distance in a single run")),
        BadgeKind("⏱️", tr("한 번에 달린 시간", "Time in one run"), tr("한 번에 오래 달리면 받는 뱃지", "Earned by running for a long time in a single run")),
        BadgeKind("📅", tr("꾸준함", "Consistency"), tr("꾸준히 자주 달리면 받는 뱃지", "Earned by running regularly")),
        BadgeKind("👑", tr("챔피언", "Champion"), tr("위 뱃지 가운데 둘 이상에서 기록이 매우 좋으면 받는 뱃지", "Earned when two or more of the above are outstanding")),
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).testTag("achievements")) {
        Text(tr("업적", "Achievements"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
        SectionTitle(tr("획득한 뱃지", "Earned badges"))
        Text(tr("아직 획득한 뱃지가 없어요.", "No badges earned yet."), color = Dim)
        SectionTitle(tr("도전할 수 있는 뱃지", "Badges you can earn"))
        for (k in kinds) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(k.icon, fontSize = 28.sp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(k.name, fontWeight = FontWeight.Bold)
                        Text(k.desc, color = Dim, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(tr("준비 중", "Coming soon"), color = Dim, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Text(tr("뱃지 기준은 준비 중이에요. 곧 추가됩니다.", "Badge criteria are being prepared and will be added soon."), color = Dim, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 10.dp))
        Spacer(Modifier.height(12.dp))
    }
}
