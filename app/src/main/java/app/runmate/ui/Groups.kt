package app.runmate.ui

import android.Manifest
import app.runmate.core.tr
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
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
    var managing by remember { mutableStateOf<String?>(null) }     // 멤버 관리 중인 그룹 id
    var kicking by remember { mutableStateOf<Pair<String, String>?>(null) }   // (그룹 id, 멤버 id)

    fun afterChange() { app.kickOutbox(); try { app.scheduleBackground() } catch (e: Exception) {}; app.syncNow() }

    fun join(code: String) {
        val before = app.store.groups.value.size
        val g = app.engine.joinGroup(code)
        RunController.notice.value = when {
            g == null -> tr("런메이트 초대 QR/코드가 아니에요.", "This is not a RunMate invite QR/code.")
            app.store.groups.value.size == before -> tr("이미 참여 중인 그룹이에요: ${g.name}", "You're already in this group: ${g.name}")
            else -> tr("'${g.name}' 그룹에 참여했어요.", "Joined the group '${g.name}'.")
        }
        if (g != null) afterChange()
    }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let { join(it) } }
    fun scan() {
        try {
            scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt(tr("친구의 초대 QR 코드를 비춰 주세요", "Point at your friend's invite QR code")).setBeepEnabled(false).setOrientationLocked(false))
        } catch (e: Exception) { RunController.notice.value = tr("카메라를 열 수 없어요. '코드로 참여'를 이용해 주세요.", "Can't open the camera. Please use 'Join by code'.") }
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) RunController.notice.value = tr("휴대폰 설정에서 런메이트 알림을 허용해야 알림이 울려요.", "Allow RunMate notifications in your phone settings to get alerts.")
    }
    fun needName(next: String) { if (settings.nameSet) { if (next == "scan") scan() else { text = ""; dialog = next } } else { afterName = next; text = ""; dialog = "name" } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Text(tr("그룹", "Groups"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))

        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("내 이름", "My name"), color = Dim, style = MaterialTheme.typography.labelMedium)
                        Text(me.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = { afterName = null; text = me.name; dialog = "name" }) { Text(tr("변경", "Change")) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("새 기록 알림", "New run alerts"))
                        Text(if (settings.notifications) tr("약 15분 간격으로 확인해 알려 드려요", "Checks about every 15 minutes and notifies you") else tr("알림 없이, 앱을 열면 새 기록을 표시해요", "No alerts; new runs show up when you open the app"), color = Dim, style = MaterialTheme.typography.labelSmall)
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

        // 언어에 따라 글자가 길어질 수 있어 옆으로 밀 수 있게 한다
        Row(Modifier.padding(top = 12.dp).fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { needName("create") }, modifier = Modifier.testTag("create")) { Text(tr("그룹 만들기", "Create group")) }
            OutlinedButton(onClick = { needName("scan") }) { Text(tr("QR 스캔", "Scan QR")) }
            OutlinedButton(onClick = { needName("code") }) { Text(tr("코드로 참여", "Join by code")) }
        }

        if (groups.isEmpty()) Text(tr("그룹을 만들고 QR 코드를 친구에게 보여 주세요.\n그룹은 여러 개 만들 수 있고, 그룹마다 다른 사람들과 기록을 주고받을 수 있어요.", "Create a group and show the QR code to your friends.\nYou can have several groups and share runs with different people in each."), color = Dim, modifier = Modifier.padding(vertical = 16.dp))
        for (g in groups) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Column(Modifier.padding(14.dp)) {
                    val admin = app.engine.isAdmin(g)
                    Text(g.name + if (admin) tr("  · 내가 그룹장", "  · You're the owner") else "", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(tr("${g.members.size + 1}명: 나", "${g.members.size + 1} members: Me") + g.members.joinToString("") { ", ${it.name}" }, color = Dim, style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { qrFor = g }) { Text(tr("초대 QR", "Invite QR"), maxLines = 1) }
                        if (admin && g.members.isNotEmpty()) TextButton(onClick = { managing = g.id }, modifier = Modifier.testTag("manage")) { Text(tr("멤버 관리", "Members"), maxLines = 1) }
                        TextButton(onClick = { leaving = g }) { Text(tr("나가기", "Leave"), color = MaterialTheme.colorScheme.error, maxLines = 1) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("알림", "Alerts"), color = Dim, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f).padding(start = 12.dp))
                        Switch(checked = !g.muted, enabled = settings.notifications, onCheckedChange = { on -> app.store.updateGroup(g.id) { it.copy(muted = !on) } })
                    }
                }
            }
        }

        if (groups.isNotEmpty()) {
            SectionTitle(tr("동기화", "Sync"))
            val st = sync.status.entries.joinToString(" · ") { (k, v) -> (if (k == "ntfy") tr("빠른 경로", "Fast route") else tr("보관 경로", "Archive route")) + (if (v) " ✓" else " ✕") }
            Text(
                when {
                    sync.syncing -> tr("동기화 중…", "Syncing…")
                    sync.lastOkMs > 0 -> tr("마지막 동기화 ${fmtClock(sync.lastOkMs)} · $st", "Last sync ${fmtClock(sync.lastOkMs)} · $st")
                    sync.tried -> tr("연결하지 못했어요. 인터넷 연결을 확인해 주세요. $st", "Couldn't connect. Please check your internet connection. $st")
                    else -> tr("아직 동기화하지 않았어요", "Not synced yet")
                }, color = Dim, style = MaterialTheme.typography.bodySmall,
            )
            if (outbox.isNotEmpty()) Text(tr("전송 대기 ${outbox.size}건 (연결되면 자동 전송)", "${outbox.size} waiting to send (sent automatically when online)"), color = Color(0xFFFFB74D), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled = !sync.syncing, onClick = { app.syncNow() }) { Text(tr("지금 동기화", "Sync now")) }
            Text(
                tr("기록은 그룹 키로 암호화되어 공개 중계 서버(ntfy.sh, Nostr 릴레이)를 거쳐 전달됩니다. 서버는 내용을 볼 수 없고, 위치는 시작점 기준 상대좌표만 공유됩니다.", "Runs are encrypted with the group key and delivered through public relay servers (ntfy.sh, Nostr relays). The servers can't read them, and only coordinates relative to your start point are shared."),
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
                title = { Text(when (kind) { "name" -> tr("내 이름", "My name"); "create" -> tr("새 그룹 이름", "New group name"); else -> tr("초대 코드 붙여넣기", "Paste invite code") }) },
                text = {
                    Column {
                        if (kind == "name") Text(tr("그룹원에게 표시될 이름이에요.", "This name is shown to your group members."), color = Dim, style = MaterialTheme.typography.bodySmall)
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
                    }) { Text(tr("확인", "OK")) }
                },
                dismissButton = { TextButton(onClick = { dialog = null; afterName = null }) { Text(tr("취소", "Cancel")) } },
            )
        }
    }

    qrFor?.let { g ->
        val code = remember(g.id) { app.engine.inviteFor(g) }
        val bmp = remember(g.id) { try { qrBitmap(code) } catch (e: Exception) { null } }
        AlertDialog(
            onDismissRequest = { qrFor = null },
            title = { Text(tr("'${g.name}' 초대", "Invite to '${g.name}'")) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (bmp != null) Image(bmp.asImageBitmap(), tr("초대 QR 코드", "Invite QR code"), Modifier.size(250.dp))
                    Text(tr("친구가 런메이트의 그룹 탭 → 'QR 스캔'으로 이 코드를 찍으면 그룹에 들어옵니다. 이 QR에는 그룹의 암호 키가 들어 있으니 함께할 사람에게만 보여 주세요.", "Your friend joins by scanning this in RunMate: Groups tab → 'Scan QR'. This QR contains the group's encryption key, so show it only to people you want in the group."), style = MaterialTheme.typography.bodySmall, color = Dim, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { qrFor = null }) { Text(tr("닫기", "Close")) } },
            dismissButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(code)); RunController.notice.value = tr("초대 코드를 복사했어요.", "Invite code copied.") }) { Text(tr("코드 복사", "Copy code")) } },
        )
    }

    managing?.let { gid ->
        val g = groups.firstOrNull { it.id == gid }
        if (g == null || g.members.isEmpty()) managing = null
        else AlertDialog(
            onDismissRequest = { managing = null },
            title = { Text(tr("'${g.name}' 멤버 관리", "Members of '${g.name}'")) },
            text = {
                Column {
                    for (m in g.members) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(m.name, Modifier.weight(1f))
                        TextButton(onClick = { kicking = g.id to m.id }, modifier = Modifier.testTag("kick")) { Text(tr("내보내기", "Remove"), color = MaterialTheme.colorScheme.error) }
                    }
                    Text(tr("내보내면 그룹의 암호 키가 새로 바뀌고, 남은 그룹원에게만 새 키가 전달됩니다. 내보낸 사람은 이후 기록을 볼 수 없어요(이미 받은 기록은 그 사람 휴대폰에 남습니다).", "Removing someone changes the group's encryption key, and only the remaining members get the new key. The removed person can't see later runs (runs they already received stay on their phone)."), color = Dim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { managing = null }) { Text(tr("닫기", "Close")) } },
        )
    }

    kicking?.let { (gid, mid) ->
        val g = groups.firstOrNull { it.id == gid }
        val m = g?.members?.firstOrNull { it.id == mid }
        if (g == null || m == null) kicking = null
        else {
            val noKey = g.members.filter { it.id != mid && it.pub.isEmpty() }
            AlertDialog(
                onDismissRequest = { kicking = null },
                title = { Text(tr("그룹원 내보내기", "Remove member")) },
                text = {
                    Text(tr("'${m.name}'님을 '${g.name}' 그룹에서 내보낼까요?", "Remove '${m.name}' from the group '${g.name}'?") +
                        if (noKey.isNotEmpty()) tr("\n\n주의: ${noKey.joinToString(", ") { it.name }}님은 구버전 앱이라 새 키를 받을 수 없어요. 내보낸 뒤 초대 QR을 다시 보여 주세요.", "\n\nNote: ${noKey.joinToString(", ") { it.name }} use(s) an old app version and can't receive the new key. Show them the invite QR again afterwards.") else "")
                },
                confirmButton = {
                    TextButton(onClick = {
                        kicking = null; managing = null
                        RunController.notice.value = if (app.engine.kick(gid, mid)) tr("'${m.name}'님을 내보냈어요.", "Removed '${m.name}'.") else tr("내보내지 못했어요.", "Couldn't remove the member.")
                        afterChange()
                    }, modifier = Modifier.testTag("confirmKick")) { Text(tr("내보내기", "Remove")) }
                },
                dismissButton = { TextButton(onClick = { kicking = null }) { Text(tr("취소", "Cancel")) } },
            )
        }
    }

    leaving?.let { g ->
        AlertDialog(
            onDismissRequest = { leaving = null },
            title = { Text(tr("그룹 나가기", "Leave group")) },
            text = { Text(tr("'${g.name}' 그룹에서 나갈까요? 이미 받은 기록은 남고, 더 이상 이 그룹의 기록을 주고받지 않습니다.", "Leave the group '${g.name}'? Runs you already received stay, but you'll no longer exchange runs with this group.")) },
            confirmButton = {
                TextButton(onClick = {
                    leaving = null
                    app.engine.leaveGroup(g.id)
                    try { app.scheduleBackground() } catch (e: Exception) {}
                    app.scope.launch { try { app.engine.announceLeave(g) } catch (e: Exception) {} }
                }, modifier = Modifier.testTag("confirmLeave")) { Text(tr("나가기", "Leave")) }
            },
            dismissButton = { TextButton(onClick = { leaving = null }) { Text(tr("취소", "Cancel")) } },
        )
    }
}
