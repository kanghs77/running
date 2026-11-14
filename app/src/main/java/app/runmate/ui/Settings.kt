package app.runmate.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.runmate.Donate
import app.runmate.RunController
import app.runmate.core.tr

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val settings by app.store.settings.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Text(tr("설정", "Settings"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
        SectionTitle(tr("언어", "Language"))
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 6.dp)) {
                // 언어 이름은 어느 언어에서든 알아볼 수 있게 고정 표기
                for ((code, label) in listOf("" to tr("휴대폰 설정 따름", "Follow phone setting"), "ko" to "한국어", "en" to "English")) {
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            app.store.updateSettings { it.copy(lang = code) }
                            app.applyLanguage()
                        }.testTag("lang_$code").padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = settings.lang == code, onClick = null, modifier = Modifier.padding(10.dp))
                        Text(label)
                    }
                }
            }
        }
        Text(tr("한국어가 아닌 휴대폰에서는 영어로 표시됩니다.", "Phones set to a language other than Korean show English."), color = Dim, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
        SectionTitle(tr("정보", "About"))
        val version = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (e: Exception) { null } ?: "-"
        Text(tr("런메이트 버전 $version", "RunMate version $version"), color = Dim, style = MaterialTheme.typography.bodySmall)
    }
}

fun openDonate(ctx: Context) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Donate.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        RunController.notice.value = tr("결제 페이지를 열 수 없어요.", "Can't open the payment page.")
    }
}

/** 각 탭 맨 아래의 옅은 후원 문구. 후원 링크가 설정된 경우에만 보인다. */
@Composable
fun CoffeeFooter() {
    if (Donate.url.isBlank()) return
    val ctx = LocalContext.current
    Box(Modifier.fillMaxWidth().clickable { openDonate(ctx) }.testTag("coffee").padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Text(tr("☕ 개발자에게 커피 선물하기 · $2", "☕ Buy the developer a coffee · $2"), color = Color(0x59FFFFFF), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
    }
}
