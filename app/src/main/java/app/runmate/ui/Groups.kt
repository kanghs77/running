package app.runmate.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.runmate.RunController
import app.runmate.core.Group
import app.runmate.core.Invite
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch

fun qrBitmap(text: String, size: Int = 640): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
    val px = IntArray(size * size) { if (m.get(it % size, it / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}

@Composable
fun GroupsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val groups by app.store.groups.collectAsStateWithLifecycle()
    val me by app.store.identity.collectAsStateWithLifecycle()
    val settings by app.store.settings.collectAsStateWithLifecycle()
    val sync by app.syncUi.collectAsStateWithLifecycle()
    val outbox by app.store.outbox.collectAsStateWithLifecycle()

    var dialog by remember { mutableStateOf<String?>(null) }       // name | create | code
    var afterName by remember { mutableStateOf<String?>(null) }    // 이름 입력 뒤 이어서 할 동작
    var text by remember { mutableStateOf("") }
    var qrFor by remember { mutableStateOf<Group?>(null) }
    var leaving by remember { mutableStateOf<Group?>(null) }

    fun afterChange() { app.kickOutbox(); try { app.scheduleBackground() } catch (e: Exception) {}; app.syncNow() }

    fun join(code: String) {
        val before = app.store.groups.value.size
        val g = app.engine.joinGroup(code)
        RunController.notice.value = when {
            g == null -> "런메이트 초대 QR/코드가 아니에요."
            app.store.groups.value.size == before -> "이미 참여 중인 그룹이에요: ${g.name}"
            else -> "'${g.name}' 그룹에 참여했어요."
        }
        if (g != null) afterChange()
    }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let { join(it) } }
    fun scan() {
        try {
            scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("친구의 초대 QR 코드를 비춰 주세요").setBeepEnabled(false).setOrientationLocked(false))
        } catch (e: Exception) { RunController.notice.value = "카메라를 열 수 없어요. '코드로 참여'를 이용해 주세요." }
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) RunController.notice.value = "휴대폰 설정에서 런메이트 알림을 허용해야 알림이 울려요."
    }
    fun needName(next: String) { if (settings.nameSet) { if (next == "scan") scan() else { text = ""; dialog = next } } else { afterName = next; text = ""; dialog = "name" } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Text("그룹", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))

        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("내 이름", color = Dim, style = MaterialTheme.typography.labelMedium)
                        Text(me.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = { afterName = null; text = me.name; dialog = "name" }) { Text("변경") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("새 기록 알림")
                        Text(if (settings.notifications) "약 15분 간격으로 확인해 알려 드려요" else "알림 없이, 앱을 열면 새 기록을 표시해요", color = Dim, style = MaterialTheme.typography.labelSmall)
                    }
                    Switch(checked = settings.notifications, modifier = Modifier.testTag("notifSwitch"), onCheckedChange = { on ->
                        app.store.updateSettings { it.copy(notifications = on) }
                        try { app.scheduleBackground() } catch (e: Exception) {}
                        if (on && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                            try { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) } catch (e: Exception) {}
                    })
                }
            }
        }

        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { needName("create") }, modifier = Modifier.testTag("create")) { Text("그룹 만들기") }
            OutlinedButton(onClick = { needName("scan") }) { Text("QR 스캔") }
            OutlinedButton(onClick = { needName("code") }) { Text("코드로 참여") }
        }

        if (groups.isEmpty()) Text("그룹을 만들고 QR 코드를 친구에게 보여 주세요.\n그룹은 여러 개 만들 수 있고, 그룹마다 다른 사람들과 기록을 주고받을 수 있어요.", color = Dim, modifier = Modifier.padding(vertical = 16.dp))
        for (g in groups) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text(g.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("${g.members.size + 1}명: 나" + g.members.joinToString("") { ", ${it.name}" }, color = Dim, style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { qrFor = g }) { Text("초대 QR") }
                        TextButton(onClick = { leaving = g }) { Text("나가기", color = MaterialTheme.colorScheme.error) }
                        Spacer(Modifier.weight(1f))
                        Text("알림", color = Dim, style = MaterialTheme.typography.labelMedium)
                        Switch(checked = !g.muted, enabled = settings.notifications, onCheckedChange = { on -> app.store.updateGroup(g.id) { it.copy(muted = !on) } })
                    }
                }
            }
        }

        if (groups.isNotEmpty()) {
            SectionTitle("동기화")
            val st = sync.status.entries.joinToString(" · ") { (k, v) -> (if (k == "ntfy") "빠른 경로" else "보관 경로") + (if (v) " ✓" else " ✕") }
            Text(
                when {
                    sync.syncing -> "동기화 중…"
                    sync.lastOkMs > 0 -> "마지막 동기화 ${fmtClock(sync.lastOkMs)} · $st"
                    sync.tried -> "연결하지 못했어요. 인터넷 연결을 확인해 주세요. $st"
                    else -> "아직 동기화하지 않았어요"
                }, color = Dim, style = MaterialTheme.typography.bodySmall,
            )
            if (outbox.isNotEmpty()) Text("전송 대기 ${outbox.size}건 (연결되면 자동 전송)", color = Color(0xFFFFB74D), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled = !sync.syncing, onClick = { app.syncNow() }) { Text("지금 동기화") }
            Text(
                "기록은 그룹 키로 암호화되어 공개 중계 서버(ntfy.sh, Nostr 릴레이)를 거쳐 전달됩니다. 서버는 내용을 볼 수 없고, 위치는 시작점 기준 상대좌표만 공유됩니다.",
                color = Dim, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
    }

    when (dialog) {
        "name", "create", "code" -> {
            val kind = dialog!!
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(when (kind) { "name" -> "내 이름"; "create" -> "새 그룹 이름"; else -> "초대 코드 붙여넣기" }) },
                text = {
                    Column {
                        if (kind == "name") Text("그룹원에게 표시될 이름이에요.", color = Dim, style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(value = text, onValueChange = { text = if (kind == "code") it else it.take(if (kind == "name") 20 else 30) }, singleLine = kind != "code", modifier = Modifier.testTag("input"))
                    }
                },
                confirmButton = {
                    TextButton(enabled = text.isNotBlank(), modifier = Modifier.testTag("ok"), onClick = {
                        val v = text.trim(); dialog = null
                        when (kind) {
                            "name" -> {
                                app.store.setName(v); app.engine.announceName(); app.kickOutbox()
                                val next = afterName; afterName = null
                                if (next == "scan") scan() else if (next != null) { text = ""; dialog = next }
                            }
                            "create" -> { val g = app.engine.createGroup(v); afterChange(); qrFor = g }
                            else -> join(v)
                        }
                    }) { Text("확인") }
                },
                dismissButton = { TextButton(onClick = { dialog = null; afterName = null }) { Text("취소") } },
            )
        }
    }

    qrFor?.let { g ->
        val code = remember(g.id) { Invite.encode(g.name, g.keyBytes()) }
        val bmp = remember(g.id) { try { qrBitmap(code) } catch (e: Exception) { null } }
        AlertDialog(
            onDismissRequest = { qrFor = null },
            title = { Text("'${g.name}' 초대") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (bmp != null) Image(bmp.asImageBitmap(), "초대 QR 코드", Modifier.size(250.dp))
                    Text("친구가 런메이트의 그룹 탭 → 'QR 스캔'으로 이 코드를 찍으면 그룹에 들어옵니다. 이 QR에는 그룹의 암호 키가 들어 있으니 함께할 사람에게만 보여 주세요.", style = MaterialTheme.typography.bodySmall, color = Dim, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { qrFor = null }) { Text("닫기") } },
            dismissButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(code)); RunController.notice.value = "초대 코드를 복사했어요." }) { Text("코드 복사") } },
        )
    }

    leaving?.let { g ->
        AlertDialog(
            onDismissRequest = { leaving = null },
            title = { Text("그룹 나가기") },
            text = { Text("'${g.name}' 그룹에서 나갈까요? 이미 받은 기록은 남고, 더 이상 이 그룹의 기록을 주고받지 않습니다.") },
            confirmButton = {
                TextButton(onClick = {
                    leaving = null
                    app.engine.leaveGroup(g.id)
                    try { app.scheduleBackground() } catch (e: Exception) {}
                    app.scope.launch { try { app.engine.announceLeave(g) } catch (e: Exception) {} }
                }, modifier = Modifier.testTag("confirmLeave")) { Text("나가기") }
            },
            dismissButton = { TextButton(onClick = { leaving = null }) { Text("취소") } },
        )
    }
}
