package app.runmate

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import app.runmate.core.Fix
import app.runmate.core.Fmt
import app.runmate.core.RunRecorder
import app.runmate.core.RunSnapshot
import app.runmate.core.Series
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 달리기 기록용 포그라운드 서비스.
 * 전력 절약: 화면이 꺼져 있으면 GPS 점을 15초 단위로 묶어 받아(배칭) CPU가 잠들 수 있게 하고,
 * 웨이크락은 쓰지 않으며, 달리는 동안에는 네트워크를 전혀 사용하지 않는다.
 */
class RunService : Service() {
    private var recorder: RunRecorder? = null
    private var lm: LocationManager? = null
    private var gpsOff = false
    private var lastNotifMs = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var visJob: Job? = null

    private val listener = object : LocationListenerCompat {
        override fun onLocationChanged(location: Location) = handle(listOf(location))
        override fun onLocationChanged(locations: List<Location>) = handle(locations)
        override fun onProviderDisabled(provider: String) { gpsOff = true; publish() }
        override fun onProviderEnabled(provider: String) { gpsOff = false; publish() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_STOP -> stop()
            else -> if (recorder == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start() {
        if (recorder != null) return
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        try {
            ServiceCompat.startForeground(
                this, App.NOTIF_RUN, notification(0.0),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
        } catch (e: Exception) {
            RunController.notice.value = "기록을 시작할 수 없어요. 위치 권한을 확인해 주세요."
            stopSelf(); return
        }
        if (!fine) {
            RunController.notice.value = "정확한 위치 권한이 필요해요."
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf(); return
        }
        val app = application as App
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        gpsOff = try { lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) != true } catch (e: Exception) { false }
        recorder = RunRecorder(UUID.randomUUID().toString(), SystemClock.elapsedRealtime(), System.currentTimeMillis(), app.store.journalFile)
        publish()
        visJob = scope.launch { RunController.uiVisible.collectLatest { request(it) } }
    }

    @SuppressLint("MissingPermission")
    private fun request(uiVisible: Boolean) {
        val m = lm ?: return
        try {
            LocationManagerCompat.removeUpdates(m, listener)
            val req = LocationRequestCompat.Builder(1000L)
                .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
                .setMinUpdateIntervalMillis(1000L)
                .setMaxUpdateDelayMillis(if (uiVisible) 0L else BATCH_MS)
                .build()
            LocationManagerCompat.requestLocationUpdates(m, LocationManager.GPS_PROVIDER, req, ContextCompat.getMainExecutor(this), listener)
        } catch (e: Exception) {
            RunController.notice.value = "GPS를 사용할 수 없어요."
        }
    }

    private fun handle(locations: List<Location>) {
        val r = recorder ?: return
        for (l in locations) {
            r.onFix(
                Fix(
                    tMs = l.elapsedRealtimeNanos / 1_000_000,
                    lat = l.latitude, lon = l.longitude,
                    alt = if (l.hasAltitude()) l.altitude else null,
                    accuracy = if (l.hasAccuracy()) l.accuracy else 999f,
                    speed = if (l.hasSpeed()) l.speed else null,
                )
            )
        }
        gpsOff = false
        publish()
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotifMs > 20_000) {
            lastNotifMs = now
            try { (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager).notify(App.NOTIF_RUN, notification(r.distanceM)) } catch (e: Exception) {}
        }
    }

    private fun publish() {
        val r = recorder ?: return
        RunController.state.value = r.snapshot(gpsOff)
    }

    private fun stop() {
        val r = recorder
        recorder = null
        visJob?.cancel()
        try { lm?.let { LocationManagerCompat.removeUpdates(it, listener) } } catch (e: Exception) {}
        if (r != null) {
            val app = application as App
            val me = app.store.identity.value
            val track = try { r.finish(SystemClock.elapsedRealtime(), me.id, me.name) } catch (e: Exception) { null }
            if (track != null) {
                try {
                    app.store.saveTrack(track) { Series.meta(track, mine = true) }
                    RunController.finished.value = track.id
                    app.scope.launch { app.resolveElevation(track.id) }
                } catch (e: Exception) {
                    RunController.notice.value = "기록 저장에 실패했어요. 저장 공간을 확인해 주세요."
                }
            } else {
                RunController.notice.value = "이동 거리가 너무 짧아 저장하지 않았어요."
            }
            try { app.store.journalFile.delete() } catch (e: Exception) {}
        }
        RunController.state.value = RunSnapshot()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // 시스템이 서비스를 죽이는 경우: 저널을 디스크에 남겨 다음 실행 때 복구한다.
        recorder?.flush()
        if (recorder != null) RunController.state.value = RunSnapshot()
        try { lm?.let { LocationManagerCompat.removeUpdates(it, listener) } } catch (e: Exception) {}
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(distanceM: Double): Notification {
        val start = recorder?.startWallMs ?: System.currentTimeMillis()
        return NotificationCompat.Builder(this, App.CH_RUN)
            .setSmallIcon(R.drawable.ic_run)
            .setContentTitle("달리기 기록 중")
            .setContentText("${Fmt.km(distanceM)} km · 종료하려면 앱을 여세요")
            .setOngoing(true).setOnlyAlertOnce(true)
            .setWhen(start).setUsesChronometer(true)
            .setContentIntent(App.mainIntent(this))
            // 실수로 눌리지 않도록 알림에는 종료 버튼을 두지 않는다. 종료는 앱 화면에서만 가능.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val ACTION_START = "app.runmate.START"
        const val ACTION_STOP = "app.runmate.STOP"
        const val BATCH_MS = 15_000L

        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, RunService::class.java).setAction(ACTION_START))
        fun stop(ctx: Context) { ctx.startService(Intent(ctx, RunService::class.java).setAction(ACTION_STOP)) }
    }
}
