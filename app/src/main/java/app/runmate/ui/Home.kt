package app.runmate.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.runmate.RunController
import app.runmate.RunService
import app.runmate.core.Fmt
import app.runmate.core.RunSnapshot
import kotlinx.coroutines.delay

private fun fineGranted(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

private fun gpsEnabled(ctx: Context): Boolean = try {
    (ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager).isProviderEnabled(LocationManager.GPS_PROVIDER)
} catch (e: Exception) { false }

@Composable
fun HomeScreen(unread: Int, onOpenHistory: () -> Unit) {
    val run by RunController.state.collectAsStateWithLifecycle()
    if (run.running) RunningView(run) else IdleView(unread, onOpenHistory)
}

@Composable
private fun IdleView(unread: Int, onOpenHistory: () -> Unit) {
    val ctx = LocalContext.current
    var dialog by remember { mutableStateOf<String?>(null) }   // "perm" | "gps"
    var accuracy by remember { mutableStateOf<Float?>(null) }
    var granted by remember { mutableStateOf(fineGranted(ctx)) }
    val settings by app.store.settings.collectAsStateWithLifecycle()

    fun startRun() {
        granted = fineGranted(ctx)
        when {
            !granted -> dialog = "perm"
            !gpsEnabled(ctx) -> dialog = "gps"
            else -> try { RunService.start(ctx) } catch (e: Exception) { RunController.notice.value = "기록을 시작할 수 없어요." }
        }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        granted = fineGranted(ctx)
        if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true || granted) {
            if (!gpsEnabled(ctx)) dialog = "gps" else try { RunService.start(ctx) } catch (e: Exception) { RunController.notice.value = "기록을 시작할 수 없어요." }
        } else dialog = "perm"
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && !settings.askedNotif &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            app.store.updateSettings { it.copy(askedNotif = true) }
            try { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) } catch (e: Exception) {}
        }
    }

    // 시작 화면이 보이는 동안 최대 45초만 GPS를 미리 깨워 둔다(첫 위치를 빨리 잡기 위해).
    GpsWarmup(granted) { accuracy = it }

    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("런메이트", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        val a = accuracy
        Text(
            when {
                !granted -> "시작을 누르면 위치 권한을 요청해요"
                a == null -> "GPS 준비 중…"
                a <= 12f -> "GPS 준비됨 (±${a.toInt()}m)"
                else -> "GPS 신호 약함 (±${a.toInt()}m)"
            },
            color = if (a != null && a <= 12f) Green else Dim, style = MaterialTheme.typography.bodyMedium,
        )
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(220.dp).clip(CircleShape).background(Green).clickable { startRun() }.testTag("start"),
                contentAlignment = Alignment.Center,
            ) { Text("시작", color = Color(0xFF08130B), fontSize = 54.sp, fontWeight = FontWeight.Black) }
        }
        if (unread > 0) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().clickable { onOpenHistory() },
            ) { Text("그룹원의 새 기록 ${unread}개가 도착했어요  ›", Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onPrimaryContainer) }
        } else {
            Text("시작을 누른 곳이 원점(0, 0)이 됩니다", color = Dim, style = MaterialTheme.typography.bodySmall)
        }
    }

    when (dialog) {
        "perm" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("위치 권한이 필요해요") },
            text = { Text("달리는 경로와 속도를 기록하려면 '정확한 위치' 권한을 허용해 주세요. 위치는 휴대폰 안에만 저장되고, 그룹에는 시작점 기준 상대좌표만 공유됩니다.") },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }) { Text("권한 허용") }
            },
            dismissButton = {
                TextButton(onClick = {
                    dialog = null
                    try { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {}
                }) { Text("앱 설정 열기") }
            },
        )
        "gps" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("위치(GPS)가 꺼져 있어요") },
            text = { Text("휴대폰의 위치 기능을 켠 뒤 다시 시작을 눌러 주세요.") },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    try { ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {}
                }) { Text("위치 설정 열기") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("닫기") } },
        )
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun GpsWarmup(enabled: Boolean, onAccuracy: (Float?) -> Unit) {
    val ctx = LocalContext.current
    LifecycleStartEffect(enabled) {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = LocationListenerCompat { loc -> onAccuracy(if (loc.hasAccuracy()) loc.accuracy else null) }
        val handler = Handler(Looper.getMainLooper())
        val stop = Runnable { try { LocationManagerCompat.removeUpdates(lm, listener) } catch (e: Exception) {} }
        if (enabled) {
            try {
                LocationManagerCompat.requestLocationUpdates(
                    lm, LocationManager.GPS_PROVIDER,
                    LocationRequestCompat.Builder(1000L).setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY).build(),
                    ContextCompat.getMainExecutor(ctx), listener,
                )
                handler.postDelayed(stop, 45_000)
            } catch (e: Exception) { }
        }
        onStopOrDispose { handler.removeCallbacks(stop); stop.run() }
    }
}

@Composable
private fun RunningView(run: RunSnapshot) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    // 화면이 보일 때만 1초마다 시계를 갱신한다.
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { now = SystemClock.elapsedRealtime(); delay(1000) }
        }
    }
    val elapsed = ((now - run.startElapsedMs) / 1000).coerceAtLeast(0).toInt()
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            when {
                run.gpsOff -> "GPS가 꺼져 있어요. 위치를 켜 주세요."
                !run.hasFix -> "GPS 신호를 찾는 중… 하늘이 보이는 곳으로 이동해 주세요"
                else -> "기록 중 · GPS ±${run.accuracy.toInt().coerceAtLeast(0)}m"
            },
            color = if (run.hasFix && !run.gpsOff) Green else Color(0xFFFFB74D), style = MaterialTheme.typography.bodyMedium,
        )
        Stat("시간", Fmt.duration(elapsed), big = true)
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Stat("거리 km", Fmt.km(run.distanceM))
            Stat("평균 페이스", Fmt.pace(if (elapsed > 0) run.distanceM / elapsed else 0.0))
            Stat("현재 페이스", Fmt.pace(run.speed))
            Stat("상대높이 m", String.format("%+.0f", run.relAltM))
        }
        Surface(Modifier.weight(1f).fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
            if (run.px.isEmpty()) Box(contentAlignment = Alignment.Center) { Text("첫 위치를 기다리는 중", color = Dim) }
            else TrackPlot(listOf(PlotTrack(Green, run.px, run.py)))
        }
        Text("화면을 끄거나 다른 앱을 써도 기록은 계속됩니다", color = Dim, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { try { RunService.stop(ctx) } catch (e: Exception) { RunController.notice.value = "종료에 실패했어요. 다시 눌러 주세요." } },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE57373), contentColor = Color.Black),
            modifier = Modifier.fillMaxWidth().height(72.dp).testTag("stop"),
        ) { Text("종료", fontSize = 28.sp, fontWeight = FontWeight.Black) }
    }
}

/** 달리기를 마친 직후: 그룹원에게 보낼지 선택 */
@Composable
fun SendDialog(trackId: String, onDone: () -> Unit) {
    val metas by app.store.metas.collectAsStateWithLifecycle()
    val groups by app.store.groups.collectAsStateWithLifecycle()
    val meta = metas.firstOrNull { it.id == trackId }
    if (meta == null) { LaunchedEffect(trackId) { onDone() }; return }
    val checked = remember(trackId) { mutableStateMapOf<String, Boolean>() }
    val summary = "${Fmt.km(meta.distanceM)} km · ${Fmt.duration(meta.durationS)} · ${Fmt.pace(meta.avgSpeed)}/km"
    AlertDialog(
        onDismissRequest = { },
        title = { Text("달리기 완료!") },
        text = {
            Column {
                Text(summary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                if (groups.isEmpty()) Text("기록이 저장됐어요. 그룹 탭에서 친구를 추가하면 기록을 보낼 수 있어요.")
                else {
                    Text("이 기록을 그룹원에게 보낼까요?")
                    GroupChecklist(groups, groups.associate { it.id to (checked[it.id] ?: true) }) { id, v -> checked[id] = v }
                }
            }
        },
        confirmButton = {
            if (groups.isEmpty()) TextButton(onClick = onDone) { Text("확인") }
            else {
                val sel = groups.map { it.id }.filter { checked[it] ?: true }
                Button(enabled = sel.isNotEmpty(), onClick = { app.share(trackId, sel); onDone() }, modifier = Modifier.testTag("send")) { Text("보내기") }
            }
        },
        dismissButton = { if (groups.isNotEmpty()) TextButton(onClick = onDone, modifier = Modifier.testTag("nosend")) { Text("안 보내고 종료") } },
    )
}
