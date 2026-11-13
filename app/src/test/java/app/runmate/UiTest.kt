package app.runmate

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import app.runmate.core.*
import app.runmate.ui.qrBitmap
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class UiTest {
    private val relayA = FakeRelay("ntfy")
    private val relayB = FakeRelay("nostr")
    private lateinit var friend: SyncTest.Dev

    private val setup = object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<App>()
            WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().build())
            val store = Store(tmpDir())
            // 죽은 주소: 테스트 중 실제 고도 API를 호출하지 않는다
            val dead = ElevationClient(OkHttpClient.Builder().connectTimeout(300, TimeUnit.MILLISECONDS).build(), "http://127.0.0.1:1/a", "http://127.0.0.1:1/b", 0)
            app.initForTest(store, SyncEngine(store, listOf(relayA, relayB)), dead)
            RunController.state.value = RunSnapshot(); RunController.finished.value = null; RunController.notice.value = null
            friend = SyncTest.Dev(listOf(relayA, relayB), "철수")
        }
    }

    @get:Rule(order = 0) val setupRule = setup
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()

    private val app: App get() = ApplicationProvider.getApplicationContext()
    private fun shot(name: String) = rule.onAllNodes(isRoot()).onFirst().captureRoboImage("build/shots/$name.png")
    private fun dialogShot(name: String) = rule.onNode(isDialog()).captureRoboImage("build/shots/$name.png")

    private fun seed() = runBlocking {
        val g1 = app.engine.createGroup("한강 크루"); val g2 = app.engine.createGroup("회사 러닝")
        friend.engine.joinGroup(Invite.encode(g1.name, g1.keyBytes()))
        val f2 = SyncTest.Dev(listOf(relayA, relayB), "영희"); f2.engine.joinGroup(Invite.encode(g2.name, g2.keyBytes()))
        val now = System.currentTimeMillis(); val day = 86400_000L
        val me = app.store.identity.value
        for (i in 0 until 5) {
            val t0 = makeTrack("m$i", me.id, me.name, now - i * 3 * day - 3600_000, n = 900 + i * 200, speed = 2.6 + i * 0.15)
            val t = t0.copy(ele = IntArray(t0.n) { 300 + (40 * Math.sin(it / 90.0)).toInt() }, eleSrc = "srtm30m")
            app.store.saveTrack(t) { Series.meta(t, true) }
            if (i < 3) app.engine.shareTrack(t.id, listOf(g1.id, g2.id))
        }
        for (i in 0 until 4) { friend.time = now - i * 2 * day - 7200_000; friend.run("f$i", friend.time); friend.engine.shareTrack("f$i", listOf(g1.id)) }
        for (i in 0 until 2) { f2.time = now - i * 5 * day - 9000_000; f2.run("y$i", f2.time); f2.engine.shareTrack("y$i", listOf(g2.id)) }
        friend.engine.flushOutbox(); f2.engine.flushOutbox(); app.engine.flushOutbox()
        val r = app.engine.sync()
        assertEquals(6, r.newTracks.size)
        assertEquals(3, friend.engine.sync().newTracks.size)
    }

    @Test fun startRequiresPermissionThenStartsService() {
        rule.onNodeWithTag("start").assertIsDisplayed()
        shot("01_home_idle")
        rule.onNodeWithTag("start").performClick()
        rule.onNodeWithText("위치 권한이 필요해요").assertIsDisplayed()
        dialogShot("02_perm_dialog")
        rule.onNodeWithText("앱 설정 열기").performClick()
        shadowOf(app as Application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        rule.onNodeWithTag("start").performClick()
        rule.onNodeWithText("위치(GPS)가 꺼져 있어요").assertIsDisplayed()
        rule.onNodeWithText("닫기").performClick()
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        while (shadowOf(app as Application).nextStartedService != null) { }
        rule.onNodeWithTag("start").performClick()
        val started = shadowOf(app as Application).nextStartedService
        assertEquals(RunService.ACTION_START, started.action)
    }

    private fun loc(base: Long, i: Int, east: Double, acc: Float = 6f): Location {
        val (lat, lon) = Geo.unproject(37.5, 127.0, east, 20 * Math.sin(i / 30.0))
        return Location(LocationManager.GPS_PROVIDER).apply {
            latitude = lat; longitude = lon; altitude = 30.0 + i * 0.01; accuracy = acc; speed = 3f
            time = System.currentTimeMillis(); elapsedRealtimeNanos = (base + i * 1000L) * 1_000_000
        }
    }

    @Test fun fullRunThroughServiceAndSend() {
        seed()
        shadowOf(app as Application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val ctl = Robolectric.buildService(RunService::class.java, Intent(app, RunService::class.java).setAction(RunService.ACTION_START)).create()
        ctl.startCommand(0, 1)
        rule.waitForIdle()
        assertTrue(RunController.state.value.running)
        assertFalse(RunController.state.value.hasFix)
        rule.onNodeWithTag("stop").assertIsDisplayed()
        shot("03_running_waiting")
        val base = SystemClock.elapsedRealtime() + 1000
        shadowOf(lm).simulateLocation(loc(base, 0, 0.0, acc = 90f))     // 부정확한 점은 무시
        assertFalse(RunController.state.value.hasFix)
        for (i in 1..400) shadowOf(lm).simulateLocation(loc(base, i, i * 3.0))
        rule.waitForIdle()
        val s = RunController.state.value
        assertTrue(s.hasFix); assertEquals(1197.0, s.distanceM, 25.0)
        assertTrue(app.store.journalFile.exists())
        shot("04_running")
        // 화면이 꺼졌다 켜져도(배칭 전환) 계속 기록
        RunController.uiVisible.value = false; rule.waitForIdle()
        for (i in 401..500) shadowOf(lm).simulateLocation(loc(base, i, i * 3.0))
        RunController.uiVisible.value = true; rule.waitForIdle()
        assertEquals(1497.0, RunController.state.value.distanceM, 30.0)
        // 중복 START는 무시
        ctl.get().onStartCommand(Intent(app, RunService::class.java).setAction(RunService.ACTION_START), 0, 2)
        assertEquals(1497.0, RunController.state.value.distanceM, 30.0)

        ctl.get().onStartCommand(Intent(app, RunService::class.java).setAction(RunService.ACTION_STOP), 0, 3)
        rule.waitForIdle()
        assertFalse(RunController.state.value.running)
        val id = RunController.finished.value!!
        assertFalse(app.store.journalFile.exists())
        val t = app.store.loadTrack(id)!!
        assertEquals(1497.0, t.distanceM, 30.0); assertEquals(37.5, t.oLat!!, 1e-4)
        // 종료 후에는 위치 리스너가 남아 있지 않아야 한다(전력)
        assertTrue(shadowOf(lm).getLocationRequests(LocationManager.GPS_PROVIDER).size <= 1)   // 시작 화면의 GPS 예열만 허용
        shadowOf(android.os.Looper.getMainLooper()).idleFor(46, TimeUnit.SECONDS)
        assertTrue(shadowOf(lm).getLocationRequests(LocationManager.GPS_PROVIDER).isEmpty())
        rule.onNodeWithText("달리기 완료!").assertIsDisplayed()
        dialogShot("05_send_dialog")
        rule.onNodeWithTag("send").performClick()
        rule.waitForIdle()
        assertNull(RunController.finished.value)
        assertEquals(2, app.store.meta(id)!!.pending.size)
        runBlocking { app.engine.flushOutbox(); assertEquals(1, friend.engine.sync().newTracks.size) }
        assertNull(friend.store.loadTrack(id)!!.oLat)
        assertEquals(2, app.store.meta(id)!!.groupIds.size)
    }

    @Test fun declineSendKeepsTrackPrivate() {
        seed()
        val me = app.store.identity.value
        val t = makeTrack("solo", me.id, me.name, System.currentTimeMillis())
        app.store.saveTrack(t) { Series.meta(t, true) }
        RunController.finished.value = "solo"
        rule.onNodeWithTag("nosend").performClick()
        rule.waitForIdle()
        assertNull(RunController.finished.value)
        assertTrue(app.store.meta("solo")!!.pending.isEmpty())
        runBlocking { app.engine.flushOutbox(); assertEquals(0, friend.engine.sync().newTracks.size) }
    }

    @Test fun shortRunIsDiscardedWithNotice() {
        shadowOf(app as Application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val ctl = Robolectric.buildService(RunService::class.java, Intent(app, RunService::class.java).setAction(RunService.ACTION_START)).create()
        ctl.startCommand(0, 1)
        ctl.get().onStartCommand(Intent(app, RunService::class.java).setAction(RunService.ACTION_STOP), 0, 2)
        assertFalse(RunController.state.value.running)
        assertNull(RunController.finished.value)
        assertTrue(app.store.metas.value.isEmpty())
        rule.waitForIdle()
        rule.onNodeWithTag("start").assertIsDisplayed()
    }

    @Test fun serviceWithoutPermissionDoesNotCrash() {
        val ctl = Robolectric.buildService(RunService::class.java, Intent(app, RunService::class.java).setAction(RunService.ACTION_START)).create()
        ctl.startCommand(0, 1)
        assertFalse(RunController.state.value.running)
        ctl.get().onStartCommand(null, 0, 2)
        ctl.destroy()
    }

    @Test fun allTabsRenderWithData() {
        seed()
        rule.waitForIdle()
        shot("06_home_unread")
        rule.onNodeWithTag("tab1").performClick(); rule.waitForIdle()
        assertEquals(6, app.store.metas.value.count { it.unread })
        shot("07_history")
        rule.onNodeWithText("그룹원 기록").performClick(); rule.waitForIdle()
        rule.onAllNodesWithText("NEW").onFirst().performClick(); rule.waitForIdle()
        rule.waitUntil(5000) { rule.onAllNodesWithText("페이스").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(5, app.store.metas.value.count { it.unread })
        shot("08_detail_friend")
        rule.onNodeWithText("분석에 추가").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("pick").performClick(); rule.waitForIdle()
        dialogShot("09_picker")
        rule.onAllNodesWithText("나 ·", substring = true).onFirst().performClick()
        rule.onNodeWithText("완료").performClick(); rule.waitForIdle()
        rule.waitUntil(5000) { rule.onAllNodesWithText("가로축").fetchSemanticsNodes().isNotEmpty() }
        shot("10_analyze_cmp")
        for (m in listOf("속도", "상대높이(API)", "상대높이(GPS)", "누적거리", "경로 겹치기")) {
            rule.onNodeWithText(m).performScrollTo().performClick(); rule.waitForIdle()
        }
        shot("11_analyze_route")
        rule.onNodeWithText("기간 추세").performScrollTo().performClick(); rule.waitForIdle()
        shot("12_analyze_trend")
        for (m in listOf("평균 페이스", "횟수", "상승고도", "일별", "월별", "달리기별", "전체")) {
            rule.onNodeWithText(m).performScrollTo().performClick(); rule.waitForIdle()
        }
        rule.onNodeWithTag("tab3").performClick(); rule.waitForIdle()
        shot("13_rank")
        for (m in listOf("이번 주", "최근 30일", "올해", "한강 크루", "회사 러닝", "시간", "횟수")) {
            rule.onNodeWithText(m).performScrollTo().performClick(); rule.waitForIdle()
        }
        shot("14_rank_group")
        rule.onNodeWithText("직접 선택").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithText("취소").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("tab4").performClick(); rule.waitForIdle()
        shot("15_groups")
        rule.onAllNodesWithText("초대 QR").onFirst().performScrollTo().performClick(); rule.waitForIdle()
        dialogShot("16_qr")
        rule.onNodeWithText("코드 복사").performClick()
        rule.onNodeWithText("닫기").performClick(); rule.waitForIdle()
        // 알림 끄기
        rule.onNodeWithTag("notifSwitch").performClick(); rule.waitForIdle()
        assertFalse(app.store.settings.value.notifications)
        // 그룹 나가기
        rule.onAllNodesWithText("나가기").onFirst().performScrollTo().performClick()
        rule.onNodeWithTag("confirmLeave").performClick(); rule.waitForIdle()
        assertEquals(1, app.store.groups.value.size)
        // 내 기록 상세 + 삭제
        rule.onNodeWithTag("tab1").performClick(); rule.waitForIdle()
        rule.onNodeWithText("내 기록").performClick(); rule.waitForIdle()
        rule.onAllNodesWithText("나").onFirst().performClick(); rule.waitForIdle()
        rule.waitUntil(5000) { rule.onAllNodesWithText("페이스").fetchSemanticsNodes().isNotEmpty() }
        shot("17_detail_mine")
        val before = app.store.metas.value.size
        RunController.notice.value = null
        rule.mainClock.advanceTimeBy(12_000); rule.waitForIdle()      // 하단 안내 메시지가 사라질 때까지
        rule.onNodeWithText("삭제").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("confirmDelete").performClick(); rule.waitForIdle()
        assertEquals(before - 1, app.store.metas.value.size)
    }

    // Robolectric은 w411dp 화면 설정에서 '대화상자 안 입력창'을 처리하지 못해(앱과 무관) 기본 화면 크기로 실행한다.
    @Config(qualifiers = "w320dp-h470dp-mdpi")
    @Test fun createGroupFlowAsksNameThenShowsQr() {
        rule.onNodeWithTag("tab4").performClick(); rule.waitForIdle()
        shot("18_groups_empty")
        rule.onNodeWithTag("create").performClick()
        rule.onNodeWithTag("input").performTextInput("달리는곰")
        rule.onNodeWithTag("ok").performClick()
        rule.onNodeWithText("새 그룹 이름").assertIsDisplayed()
        rule.onNodeWithTag("input").performTextInput("새벽 러닝")
        rule.onNodeWithTag("ok").performClick()
        rule.waitForIdle()
        assertEquals("달리는곰", app.store.identity.value.name)
        assertEquals(1, app.store.groups.value.size)
        rule.onNodeWithText("'새벽 러닝' 초대").assertIsDisplayed()
        // QR 내용을 다시 읽어 친구가 참여
        val g = app.store.groups.value[0]
        val code = app.engine.inviteFor(g)
        assertTrue(code.contains("&o="))
        val bmp = qrBitmap(code, 300)
        val px = IntArray(300 * 300); bmp.getPixels(px, 0, 300, 0, 0, 300, 300)
        val decoded = com.google.zxing.qrcode.QRCodeReader().decode(
            com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(com.google.zxing.RGBLuminanceSource(300, 300, px)))).text
        assertEquals(code, decoded)
        assertNotNull(friend.engine.joinGroup(decoded))
        runBlocking { friend.engine.flushOutbox(); app.engine.flushOutbox(); app.engine.sync() }
        assertEquals(listOf("철수"), app.store.groups.value[0].members.map { it.name })
    }

    @Test fun adminKicksMemberFromUi() {
        seed()
        rule.onNodeWithTag("tab4").performClick(); rule.waitForIdle()
        val before = app.store.groups.value.first { it.name == "한강 크루" }
        rule.onAllNodes(androidx.compose.ui.test.hasTestTag("manage")).onFirst().performScrollTo().performClick(); rule.waitForIdle()
        dialogShot("20_manage_members")
        rule.onNodeWithTag("kick").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("confirmKick").performClick(); rule.waitForIdle()
        val after = app.store.groups.value.first { it.name == "한강 크루" }
        assertNotEquals(before.id, after.id); assertNotEquals(before.key, after.key)
        assertTrue(after.members.isEmpty())
        // 내 기록의 그룹 연결은 새 그룹으로 이어진다
        assertTrue(app.store.metas.value.filter { it.mine }.none { before.id in it.groupIds })
        assertTrue(app.store.metas.value.any { it.mine && after.id in it.groupIds })
        // 내보낸 사람은 이후 기록을 받지 못한다
        val me = app.store.identity.value
        val t = makeTrack("afterKick", me.id, me.name, System.currentTimeMillis())
        app.store.saveTrack(t) { Series.meta(t, true) }
        app.engine.shareTrack("afterKick", listOf(after.id))
        runBlocking { app.engine.flushOutbox(); assertEquals(0, friend.engine.sync().newTracks.size) }
    }

    @Test fun mutedNotificationsStillMarkUnread() {
        seed()
        app.store.updateSettings { it.copy(notifications = false) }
        RunController.uiVisible.value = false
        runBlocking {
            friend.time += 1000; friend.run("late", friend.time); friend.engine.shareTrack("late", friend.store.groups.value.map { it.id }); friend.engine.flushOutbox()
            val r = app.engine.sync(fast = true)
            assertEquals(1, r.newTracks.size)
            app.notifyNewTracks(r.newTracks)
        }
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        assertEquals(0, shadowOf(nm).allNotifications.size)
        assertTrue(app.store.meta("late")!!.unread)
        // 알림을 켜면 울린다
        app.store.updateSettings { it.copy(notifications = true) }
        shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        app.notifyNewTracks(listOf(app.store.meta("late")!!))
        assertEquals(1, shadowOf(nm).allNotifications.size)
        // 그룹별 음소거
        nm.cancelAll()
        app.store.updateGroups { l -> l.map { it.copy(muted = true) } }
        app.notifyNewTracks(listOf(app.store.meta("late")!!))
        assertEquals(0, shadowOf(nm).allNotifications.size)
        RunController.uiVisible.value = true
    }

    @Test fun journalRecoveryOnAppStart() {
        val f = app.store.journalFile
        val r = RunRecorder("crashed", 0, System.currentTimeMillis() - 600_000, f)
        for (i in 0..200) r.onFix(fixAt(i * 1000L, i * 3.0))
        r.flush()
        val m = App::class.java.getDeclaredMethod("recoverJournal"); m.isAccessible = true; m.invoke(app)
        assertFalse(f.exists())
        assertEquals("crashed", RunController.finished.value)
        assertEquals(600.0, app.store.meta("crashed")!!.distanceM, 5.0)
        RunController.finished.value = null
    }
}
