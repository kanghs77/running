package app.runmate

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.runmate.core.ElevationClient
import app.runmate.core.ElevationProfile
import app.runmate.core.Fmt
import app.runmate.core.NostrRelay
import app.runmate.core.NtfyRelay
import app.runmate.core.RunRecorder
import app.runmate.core.RunSnapshot
import app.runmate.core.Series
import app.runmate.core.Store
import app.runmate.core.SyncEngine
import app.runmate.core.TrackMeta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** 달리기 서비스와 화면이 공유하는 상태 */
object RunController {
    val state = MutableStateFlow(RunSnapshot())
    /** 방금 끝난(또는 복구된) 기록: 보내기/안보내기 선택 대기 */
    val finished = MutableStateFlow<String?>(null)
    /** 사용자에게 한 번 보여줄 안내 문구 */
    val notice = MutableStateFlow<String?>(null)
    val uiVisible = MutableStateFlow(false)
}

data class SyncUi(val syncing: Boolean = false, val lastOkMs: Long = 0, val status: Map<String, Boolean> = emptyMap(), val tried: Boolean = false)

class App : Application() {
    lateinit var store: Store
    lateinit var engine: SyncEngine
    lateinit var elevation: ElevationClient
    lateinit var tiles: TileStore
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val syncUi = MutableStateFlow(SyncUi())
    private val eleMutex = Mutex()

    override fun onCreate() {
        super.onCreate()
        instance = this
        val http = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS).build()
        store = Store(filesDir.resolve("data"))
        engine = SyncEngine(store, listOf(NtfyRelay(http), NostrRelay(http)))
        elevation = ElevationClient(http)
        tiles = TileStore(cacheDir)
        createChannels()
        recoverJournal()
        try { scheduleBackground() } catch (e: Exception) { /* WorkManager 미초기화(테스트) */ }
    }

    fun initForTest(s: Store, e: SyncEngine, el: ElevationClient, t: TileStore = tiles) { store = s; engine = e; elevation = el; tiles = t }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CH_RUN, "달리기 기록 중", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        nm.createNotificationChannel(NotificationChannel(CH_SHARE, "그룹원의 새 기록", NotificationManager.IMPORTANCE_DEFAULT))
    }

    /** 기록 도중 앱이 죽었으면 남아 있는 저널에서 기록을 살린다. */
    private fun recoverJournal() {
        val f = store.journalFile
        if (!f.exists() || RunController.state.value.running) return
        try {
            val me = store.identity.value
            val t = RunRecorder.recover(f, me.id, me.name)
            if (t != null && store.meta(t.id) == null) {
                store.saveTrack(t) { Series.meta(t, mine = true) }
                RunController.finished.value = t.id
                RunController.notice.value = "비정상 종료된 달리기 기록을 복구했어요."
                scope.launch { resolveElevation(t.id) }
            }
        } catch (e: Exception) { } finally { f.delete() }
    }

    /** 알림 켜짐: 15분마다 가볍게 확인 / 꺼짐: 6시간마다(릴레이 보관 기간 안에 받아두기 위해) */
    fun scheduleBackground() {
        val wm = WorkManager.getInstance(this)
        if (store.groups.value.isEmpty()) { wm.cancelUniqueWork(WORK_SYNC); return }
        val minutes = if (store.settings.value.notifications) 15L else 360L
        val req = PeriodicWorkRequestBuilder<SyncWorker>(minutes, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        wm.enqueueUniquePeriodicWork(WORK_SYNC, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun kickOutbox() {
        try {
            val req = OneTimeWorkRequestBuilder<OutboxWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            WorkManager.getInstance(this).enqueueUniqueWork(WORK_OUTBOX, ExistingWorkPolicy.REPLACE, req)
        } catch (e: Exception) { }
    }

    /** 화면에서 호출: 보내기 + 받기 */
    fun syncNow(fast: Boolean = false) {
        if (syncUi.value.syncing) return
        if (store.groups.value.isEmpty() && store.outbox.value.isEmpty()) return
        syncUi.value = syncUi.value.copy(syncing = true)
        scope.launch {
            try {
                val flushed = try { engine.flushOutbox() } catch (e: Exception) { false }
                if (!flushed) kickOutbox()
                val r = engine.sync(fast = fast)
                r.notices.firstOrNull()?.let { RunController.notice.value = it; try { scheduleBackground() } catch (e: Exception) {} }
                syncUi.value = SyncUi(false, if (r.ok) System.currentTimeMillis() else syncUi.value.lastOkMs,
                    if (fast) syncUi.value.status + r.status else r.status, true)
            } catch (e: Exception) {
                syncUi.value = syncUi.value.copy(syncing = false, tried = true)
            }
        }
    }

    fun share(trackId: String, groupIds: List<String>) {
        engine.shareTrack(trackId, groupIds)
        kickOutbox()
    }

    /** 내 기록의 고도를 API로 채운다. 성공하면 true. */
    suspend fun resolveElevation(trackId: String): Boolean = eleMutex.withLock {
        val meta = store.meta(trackId) ?: return@withLock false
        if (!meta.mine) return@withLock false
        if (meta.eleSrc.isNotEmpty()) return@withLock true
        val t = store.loadTrack(trackId) ?: return@withLock false
        val r = try { ElevationProfile.resolve(t, elevation) } catch (e: Exception) { null }
        if (r == null) {
            store.updateMeta(trackId) { it.copy(eleTries = it.eleTries + 1) }
            return@withLock false
        }
        if (store.meta(trackId) == null) return@withLock false   // 그 사이 삭제됨
        store.saveTrack(r) { old -> Series.meta(r, mine = true, old = old) }
        true
    }

    fun notifyNewTracks(list: List<TrackMeta>) {
        if (list.isEmpty() || !store.settings.value.notifications || RunController.uiVisible.value) return
        val muted = store.groups.value.filter { it.muted }.map { it.id }.toSet()
        val shown = list.filter { m -> m.groupIds.any { it !in muted } }
        if (shown.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val title = if (shown.size == 1) "${shown[0].ownerName}님의 새 달리기 기록" else "새 달리기 기록 ${shown.size}개"
        val text = shown.take(4).joinToString(", ") { "${it.ownerName} ${Fmt.km(it.distanceM)}km" }
        val n = NotificationCompat.Builder(this, CH_SHARE)
            .setSmallIcon(R.drawable.ic_run).setContentTitle(title).setContentText(text)
            .setAutoCancel(true).setContentIntent(mainIntent(this)).build()
        try { NotificationManagerCompat.from(this).notify(NOTIF_SHARE, n) } catch (e: SecurityException) { }
    }

    companion object {
        lateinit var instance: App
        const val CH_RUN = "run"
        const val CH_SHARE = "share"
        const val NOTIF_RUN = 1
        const val NOTIF_SHARE = 2
        const val WORK_SYNC = "sync"
        const val WORK_OUTBOX = "outbox"

        fun mainIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/** 주기적 백그라운드 확인. 평소엔 가벼운 주 경로만, 6시간에 한 번은 백업 경로까지 확인한다. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as App
        return try {
            if (RunController.state.value.running) return Result.success()   // 달리는 중엔 통신하지 않는다
            app.engine.flushOutbox()
            val nowS = System.currentTimeMillis() / 1000
            val lastFull = app.store.groups.value.minOfOrNull { it.since["nostr"] ?: 0L } ?: nowS
            val r = app.engine.sync(fast = nowS - lastFull < 6 * 3600)
            app.notifyNewTracks(r.newTracks)
            r.notices.firstOrNull()?.let { RunController.notice.value = it; try { app.scheduleBackground() } catch (e: Exception) {} }
            Result.success()
        } catch (e: Exception) { Result.success() }
    }
}

/** 보내기 대기열 처리: 네트워크가 연결되면 실행, 실패하면 간격을 늘려가며 재시도 */
class OutboxWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as App
        return try {
            // 보내기 전에 고도를 먼저 채운다(최대 2번 시도, 안 되면 GPS 고도만 보냄)
            for (item in app.store.outbox.value) {
                val id = item.trackId ?: continue
                val m = app.store.meta(id) ?: continue
                if (m.eleSrc.isEmpty() && m.eleTries < 2) app.resolveElevation(id)
            }
            if (app.engine.flushOutbox()) Result.success()
            else if (runAttemptCount > 12) Result.failure() else Result.retry()
        } catch (e: Exception) { if (runAttemptCount > 12) Result.failure() else Result.retry() }
    }
}
