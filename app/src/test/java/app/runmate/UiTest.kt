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
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.assertIsSelected
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
    private lateinit var tileServer: okhttp3.mockwebserver.MockWebServer
    private lateinit var coffee: FakeCoffee

    /** Play 결제 대신 쓰는 가짜 상점 */
    class FakeCoffee : CoffeeStore {
        override val price = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
        var refreshed = 0; var bought = 0; var lastActivity: android.app.Activity? = null
        override fun refresh() { refreshed++ }
        override fun buy(activity: android.app.Activity) { bought++; lastActivity = activity }
    }
    private val tilePaths = java.util.Collections.synchronizedList(ArrayList<String>())
    private val tileAgents = java.util.Collections.synchronizedList(ArrayList<String>())

    private val setup = object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<App>()
            WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().build())
            val store = Store(tmpDir())
            store.updateSettings { it.copy(lang = "ko") }       // 테스트 문구는 한국어 기준
            // 죽은 주소: 테스트 중 실제 고도 API를 호출하지 않는다
            val dead = ElevationClient(OkHttpClient.Builder().connectTimeout(300, TimeUnit.MILLISECONDS).build(), "http://127.0.0.1:1/a", "http://127.0.0.1:1/b", 0)
            tileServer = okhttp3.mockwebserver.MockWebServer()
            val bmp = android.graphics.Bitmap.createBitmap(256, 256, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFB5D6A7.toInt()) }
            val png = java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            tileServer.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                    tilePaths.add(request.path ?: ""); tileAgents.add(request.getHeader("User-Agent") ?: "")
                    return okhttp3.mockwebserver.MockResponse().setHeader("Cache-Control", "max-age=86400").setBody(okio.Buffer().write(png))
                }
            }
            tileServer.start()
            app.initForTest(store, SyncEngine(store, listOf(relayA, relayB)), dead, TileStore(tmpDir(), tileServer.url("/").toString().trimEnd('/')))
            coffee = FakeCoffee(); app.coffee = coffee
            RunController.state.value = RunSnapshot(); RunController.finished.value = null; RunController.notice.value = null
            friend = SyncTest.Dev(listOf(relayA, relayB), "철수")
        }
    }

    @get:Rule(order = 0) val setupRule = setup
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()

    private val app: App get() = ApplicationProvider.getApplicationContext()
    private fun shot(name: String) = rule.onAllNodes(isRoot()).onFirst().captureRoboImage("build/shots/$name.png")
    private fun dialogShot(name: String) = rule.onNode(isDialog()).captureRoboImage("build/shots/$name.png")

    private fun startedIntents(): List<Intent> {
        val out = ArrayList<Intent>()
        while (true) out.add(shadowOf(app as Application).nextStartedActivity ?: break)
        return out
    }

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
        for (m in listOf("속도", "상대높이(API)", "상대높이(GPS)", "누적거리", "페이스")) {
            rule.onNodeWithText(m).performScrollTo().performClick(); rule.waitForIdle()
        }
        // 그래프 탭 -> 선택 위치 표시, 두 손가락 확대 -> '전체 구간 보기' 버튼
        rule.onNodeWithTag("chart").performScrollTo().performTouchInput { click(androidx.compose.ui.geometry.Offset(width * 0.6f, height * 0.5f)) }; rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("km ", substring = true).fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithTag("chart").performTouchInput {
            pinch(androidx.compose.ui.geometry.Offset(width * 0.45f, height * 0.5f), androidx.compose.ui.geometry.Offset(width * 0.2f, height * 0.5f),
                androidx.compose.ui.geometry.Offset(width * 0.55f, height * 0.5f), androidx.compose.ui.geometry.Offset(width * 0.9f, height * 0.5f))
        }; rule.waitForIdle()
        rule.onNodeWithText("전체 구간 보기").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("overlay").performScrollTo(); rule.waitForIdle()
        rule.waitUntil(5000) { tilePaths.isNotEmpty() }
        Thread.sleep(300); rule.waitForIdle()
        shot("11_analyze_map")
        rule.onNodeWithText("격자").performScrollTo().performClick(); rule.waitForIdle()
        assertFalse(app.store.settings.value.mapMode)
        shot("11_analyze_grid")
        // 기록 하나를 골라 회전·이동: 버튼 회전, 손가락 끌기, 두 손가락 회전, 초기화
        rule.onNodeWithText("부드럽게", substring = true).performScrollTo(); rule.waitForIdle()      // 세로로 먼저 내려 칩이 보이게
        rule.onNodeWithTag("adj1").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("rotL").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("rotL").performClick(); rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("(회전 10°)", substring = true).fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithTag("overlay").performScrollTo().performTouchInput { swipe(center, center + androidx.compose.ui.geometry.Offset(120f, -90f), 300) }; rule.waitForIdle()
        rule.onNodeWithTag("overlay").performTouchInput {
            // 두 손가락을 중심 둘레로 90도 돌린다 (가로 -> 세로)
            down(0, center + androidx.compose.ui.geometry.Offset(-200f, 0f)); down(1, center + androidx.compose.ui.geometry.Offset(200f, 0f))
            for (k in 1..10) { val a = Math.toRadians(9.0 * k); val dx = (200 * Math.cos(a)).toFloat(); val dy = (200 * Math.sin(a)).toFloat()
                moveTo(0, center + androidx.compose.ui.geometry.Offset(-dx, -dy)); moveTo(1, center + androidx.compose.ui.geometry.Offset(dx, dy)); advanceEventTime(20) }
            up(0); up(1)
        }; rule.waitForIdle()
        val rotText = rule.onAllNodesWithText("(회전 ", substring = true).fetchSemanticsNodes().first().config.toString()
        val deg = Regex("회전 (-?\\d+)°").find(rotText)!!.groupValues[1].toInt()
        assertTrue("deg=$deg", Math.floorMod(deg, 360) in 270..290)        // 10° - 90° = -80°
        shot("11_analyze_aligned")
        rule.onNodeWithTag("xfReset").performScrollTo().performClick(); rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("(회전 0°)", substring = true).fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithText("부드럽게", substring = true).performScrollTo(); rule.waitForIdle()
        rule.onNodeWithTag("adj0").performClick(); rule.waitForIdle()
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasTestTag("rotL")).fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText("지도").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithText("전체 구간 보기").performScrollTo().performClick(); rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("전체 구간 보기").fetchSemanticsNodes().isEmpty())
        rule.onAllNodesWithText("분석").onFirst().performScrollTo(); rule.waitForIdle()     // 맨 위로
        rule.onNodeWithText("기간 추세").performClick(); rule.waitForIdle()
        // 막대를 탭하면 정확한 값이 나온다
        rule.onNodeWithTag("bars").performScrollTo().performTouchInput { click(androidx.compose.ui.geometry.Offset(width * 0.85f, height * 0.5f)) }; rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText(" km", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithText("● ", substring = true).fetchSemanticsNodes().isNotEmpty())
        shot("12_analyze_trend")
        for (m in listOf("평균 페이스", "횟수", "일별", "월별", "달리기별", "전체")) {
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
        rule.onNodeWithTag("tab5").performClick(); rule.waitForIdle()
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
        rule.waitUntil(5000) { tilePaths.size >= 2 }
        assertTrue(tileAgents.all { it.startsWith("RunMate/") })
        assertTrue(tilePaths.all { Regex("/\\d+/\\d+/\\d+\\.png").matches(it) })
        // 그래프를 탭하면 지도에 위치가 표시되고, 스와이프하면 다음 그래프로 넘어간다
        rule.onNodeWithTag("pager").performScrollTo().performTouchInput { click(androidx.compose.ui.geometry.Offset(width * 0.5f, height * 0.4f)) }; rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("km ", substring = true).fetchSemanticsNodes().isNotEmpty())
        Thread.sleep(300); rule.waitForIdle()
        shot("17_detail_mine")
        rule.onNodeWithText("높이(API)").assertIsSelected()
        // 그래프 위에서 좌우로 끌면 그래프가 넘어가지 않고 선택 지점이 연속으로 움직인다
        fun cursorKm() = rule.onAllNodesWithText("km ", substring = true).fetchSemanticsNodes().first().config.toString().let { Regex("km ([0-9.]+)").find(it)!!.groupValues[1].toDouble() }
        val kmBefore = cursorKm()
        rule.onNodeWithTag("pager").performTouchInput { swipe(androidx.compose.ui.geometry.Offset(width * 0.5f, height * 0.4f), androidx.compose.ui.geometry.Offset(width * 0.85f, height * 0.4f), 300) }; rule.waitForIdle()
        assertTrue("before=$kmBefore after=${cursorKm()}", cursorKm() > kmBefore + 0.3)
        rule.onNodeWithTag("pager").performTouchInput { swipeLeft() }; rule.waitForIdle()
        assertTrue(cursorKm() < kmBefore)
        rule.onNodeWithText("높이(API)").assertIsSelected()
        rule.onNodeWithText("높이(GPS)").performClick(); rule.waitForIdle()
        rule.onNodeWithText("높이(GPS)").assertIsSelected()
        rule.onNodeWithText("속도").performClick(); rule.waitForIdle()
        rule.onNodeWithText("속도").assertIsSelected()
        rule.onNodeWithTag("pager").performTouchInput {
            pinch(androidx.compose.ui.geometry.Offset(width * 0.45f, height * 0.4f), androidx.compose.ui.geometry.Offset(width * 0.2f, height * 0.4f),
                androidx.compose.ui.geometry.Offset(width * 0.55f, height * 0.4f), androidx.compose.ui.geometry.Offset(width * 0.9f, height * 0.4f))
        }; rule.waitForIdle()
        rule.onNodeWithText("속도").assertIsSelected()               // 확대 중에는 페이지가 넘어가지 않는다
        // 두 손가락을 세로로 벌리면 세로축만 조절된다
        rule.onNodeWithText("전체 구간 보기").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("pager").performScrollTo().performTouchInput {
            pinch(androidx.compose.ui.geometry.Offset(width * 0.5f, height * 0.25f), androidx.compose.ui.geometry.Offset(width * 0.5f, height * 0.35f),
                androidx.compose.ui.geometry.Offset(width * 0.5f, height * 0.6f), androidx.compose.ui.geometry.Offset(width * 0.5f, height * 0.5f))
        }; rule.waitForIdle()
        rule.onNodeWithText("전체 구간 보기").performScrollTo().assertIsDisplayed()
        shot("17_detail_yzoom")
        rule.onNodeWithText("전체 구간 보기").performScrollTo().assertIsDisplayed()
        shot("17_detail_zoomed")
        rule.onNodeWithText("격자").performScrollTo().performClick(); rule.waitForIdle()
        shot("17_detail_grid")
        rule.onNodeWithText("지도").performScrollTo().performClick(); rule.waitForIdle()
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
        rule.onNodeWithTag("tab5").performClick(); rule.waitForIdle()
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
        val bmp = qrBitmap(code)                    // 앱이 실제로 화면에 띄우는 크기
        val sz = bmp.width
        val px = IntArray(sz * sz); bmp.getPixels(px, 0, sz, 0, 0, sz, sz)
        val decoded = com.google.zxing.qrcode.QRCodeReader().decode(
            com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(com.google.zxing.RGBLuminanceSource(sz, sz, px))),
            mapOf(com.google.zxing.DecodeHintType.PURE_BARCODE to true)).text
        assertEquals(code, decoded)
        assertNotNull(friend.engine.joinGroup(decoded))
        runBlocking { friend.engine.flushOutbox(); app.engine.flushOutbox(); app.engine.sync() }
        assertEquals(listOf("철수"), app.store.groups.value[0].members.map { it.name })
    }

    @Test fun adminKicksMemberFromUi() {
        seed()
        rule.onNodeWithTag("tab5").performClick(); rule.waitForIdle()
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

    /** 실제 지도 타일 위에 경로가 제 위치에 그려지는지 눈으로 확인하기 위한 화면 (-Dlive=1) */
    @Test fun realMapScreenshot() {
        org.junit.Assume.assumeTrue(System.getProperty("live") == "1")
        app.tiles = TileStore(tmpDir())
        val me = app.store.identity.value
        // 반포한강공원 부근(잠수교 남단)에서 강변을 따라 동쪽으로
        val r = RunRecorder("han", 0, System.currentTimeMillis() - 3600_000)
        for (i in 0..700) { val (la, lo) = Geo.unproject(37.5106, 126.9960, i * 3.0, i * 0.9); r.onFix(Fix(i * 1000L, la, lo, 12.0, 5f, 3f)) }
        val t = r.finish(701_000, me.id, me.name)!!
        app.store.saveTrack(t) { Series.meta(t, true) }
        rule.onNodeWithTag("tab1").performClick(); rule.waitForIdle()
        rule.onAllNodesWithText("나").onFirst().performClick(); rule.waitForIdle()
        rule.waitUntil(5000) { rule.onAllNodesWithText("페이스").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("pager").performScrollTo().performTouchInput { click(androidx.compose.ui.geometry.Offset(width * 0.5f, height * 0.4f)) }
        Thread.sleep(6000); rule.waitForIdle()
        shot("30_real_map")
    }

    @Test fun languageSwitchAndSettingsTab() {
        seed()
        rule.onNodeWithTag("tab6").performClick(); rule.waitForIdle()
        assertEquals(2, rule.onAllNodesWithText("설정").fetchSemanticsNodes().size)     // 제목 + 탭
        shot("40_settings_ko")
        rule.onNodeWithTag("lang_en").performClick(); rule.waitForIdle()
        assertEquals("en", app.store.settings.value.lang); assertEquals("en", app.lang.value)
        rule.onNodeWithText("Language").assertIsDisplayed()
        for (t in listOf("Run", "History", "Analyze", "Rank", "Badges", "Groups")) rule.onNodeWithText(t).assertIsDisplayed()
        shot("41_settings_en")
        rule.onNodeWithTag("tab0").performClick(); rule.waitForIdle()
        rule.onNodeWithText("START").assertIsDisplayed()
        shot("42_home_en")
        rule.onNodeWithTag("tab1").performClick(); rule.waitForIdle()
        rule.onNodeWithText("Export all").assertIsDisplayed()
        shot("43_history_en")
        rule.onAllNodesWithText("Me").onFirst().performClick(); rule.waitForIdle()
        rule.waitUntil(5000) { rule.onAllNodesWithText("Pace").fetchSemanticsNodes().isNotEmpty() }
        shot("44_detail_en")
        rule.onNodeWithTag("tab2").performClick(); rule.waitForIdle(); shot("45_analyze_en")
        rule.onNodeWithText("Trends").performClick(); rule.waitForIdle()
        rule.onNodeWithText("Weekly").assertIsDisplayed()
        rule.onNodeWithTag("tab3").performClick(); rule.waitForIdle()
        rule.onNodeWithText("This month").assertIsDisplayed(); shot("46_rank_en")
        rule.onNodeWithTag("tab5").performClick(); rule.waitForIdle()
        rule.onNodeWithText("Create group").assertIsDisplayed(); shot("47_groups_en")
        // 알림 문구도 영어
        RunController.uiVisible.value = false
        shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        app.notifyNewTracks(listOf(app.store.metas.value.first { !it.mine }))
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        assertTrue(shadowOf(shadowOf(nm).allNotifications[0]).contentTitle.toString().startsWith("New run from"))
        assertEquals("Recording run", nm.getNotificationChannel(App.CH_RUN).name.toString())
        RunController.uiVisible.value = true
        // '휴대폰 설정 따름': 휴대폰 언어에 맞춘다
        rule.onNodeWithTag("tab6").performClick(); rule.waitForIdle()
        val old = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.KOREA)
            rule.onNodeWithTag("lang_").performClick(); rule.waitForIdle()
            assertEquals("ko", app.lang.value); rule.onNodeWithText("언어").assertIsDisplayed()
            java.util.Locale.setDefault(java.util.Locale.JAPAN); app.applyLanguage(); rule.waitForIdle()
            assertEquals("ja", app.lang.value); rule.onNodeWithText("言語").assertIsDisplayed()
            java.util.Locale.setDefault(java.util.Locale("th", "TH")); app.applyLanguage(); rule.waitForIdle()      // 지원하지 않는 언어 -> 영어
            assertEquals("en", app.lang.value); rule.onNodeWithText("Language").assertIsDisplayed()
            java.util.Locale.setDefault(java.util.Locale("in", "ID")); app.applyLanguage(); rule.waitForIdle()      // 인도네시아어 옛 코드
            assertEquals("id", app.lang.value)
        } finally { java.util.Locale.setDefault(old) }
        // 재시작해도 선택한 언어 유지
        rule.onNodeWithTag("lang_ko").performClick(); rule.waitForIdle()
        assertEquals("ko", app.store.settings.value.lang)
        rule.onNodeWithText("달리기").assertIsDisplayed()
    }

    @Test fun allTenLanguagesRender() {
        seed()
        val expect = mapOf("zh" to listOf("跑步", "设置", "语言"), "ja" to listOf("ラン", "設定", "言語"), "es" to listOf("Correr", "Ajustes", "Idioma"),
            "pt" to listOf("Correr", "Ajustes", "Idioma"), "de" to listOf("Laufen", "Optionen", "Sprache"), "fr" to listOf("Courir", "Réglages", "Langue"),
            "ru" to listOf("Бег", "Настройки", "Язык"), "id" to listOf("Lari", "Setelan", "Bahasa"))
        assertEquals(10, I18n.SUPPORTED.size)
        for ((code, words) in expect) {
            rule.onNodeWithTag("tab6").performClick(); rule.waitForIdle()
            rule.onNodeWithTag("lang_$code").performScrollTo().performClick(); rule.waitForIdle()
            assertEquals(code, app.lang.value)
            for (w in words) assertTrue("$code: $w", rule.onAllNodesWithText(w).fetchSemanticsNodes().isNotEmpty())
            shot("50_${code}_settings")
            // 모든 탭이 그 언어로 문제없이 그려지는지
            for (i in 0..5) { rule.onNodeWithTag("tab$i").performClick(); rule.waitForIdle(); if (code in listOf("de", "ru", "ja", "zh")) shot("50_${code}_tab$i") }
            rule.onNodeWithTag("tab1").performClick(); rule.waitForIdle()
            rule.onAllNodesWithText(tr("나", "Me")).onFirst().performClick(); rule.waitForIdle()
            rule.waitUntil(5000) { rule.onAllNodesWithText(tr("페이스", "Pace")).fetchSemanticsNodes().isNotEmpty() }
            if (code in listOf("de", "ru")) shot("50_${code}_detail")
            rule.onNodeWithText(tr("‹ 뒤로", "‹ Back")).performClick(); rule.waitForIdle()
        }
        // 달리는 화면도 번역된다
        RunController.state.value = RunSnapshot(running = true, startElapsedMs = SystemClock.elapsedRealtime(), hasFix = true, accuracy = 5f)
        rule.onNodeWithTag("tab0").performClick(); rule.waitForIdle()
        rule.onNodeWithText("BERHENTI").assertIsDisplayed()
        shot("50_id_running")
        RunController.state.value = RunSnapshot()
    }

    @Test fun coffeeFooterUsesPlayBillingOnlyWhenAvailable() {
        // Play에서 가격을 받아오지 못하면(스토어 밖 설치, 상품 미등록) 버튼이 없다
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasTestTag("coffee")).fetchSemanticsNodes().isEmpty())
        coffee.price.value = "₩2,900"
        rule.waitForIdle()
        for (i in 0..6) {
            rule.onNodeWithTag("tab$i").performClick(); rule.waitForIdle()
            rule.onNodeWithTag("coffee").assertIsDisplayed()
        }
        assertTrue(rule.onAllNodesWithText("₩2,900", substring = true).fetchSemanticsNodes().isNotEmpty())      // Play가 준 가격을 그대로 표시
        shot("48_coffee_footer")
        rule.onNodeWithTag("coffee").performClick(); rule.waitForIdle()
        assertEquals(1, coffee.bought); assertTrue(coffee.lastActivity is MainActivity)
        assertTrue(startedIntents().none { it.action == Intent.ACTION_VIEW })        // 외부 결제 페이지를 열지 않는다
        // 달리는 중에는 숨김
        RunController.state.value = RunSnapshot(running = true, startElapsedMs = SystemClock.elapsedRealtime())
        rule.onNodeWithTag("tab0").performClick(); rule.waitForIdle()
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasTestTag("coffee")).fetchSemanticsNodes().isEmpty())
        RunController.state.value = RunSnapshot()
        // 다른 언어에서도 가격이 들어간 문구가 번역된다
        assertEquals("☕ Spendiere dem Entwickler einen Kaffee · ₩2,900", I18n.translate("de", "☕ Buy the developer a coffee · ₩2,900"))
    }

    @Test fun realPlayBillingIsSafeWithoutPlayStore() {
        // 테스트 환경에는 Play 스토어가 없다: 죽지 않고, 가격이 없으니 버튼도 없어야 한다
        val real = PlayCoffee(app)
        real.refresh(); real.refresh()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNull(real.price.value)
        real.buy(rule.activity)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(startedIntents().none { it.action == Intent.ACTION_VIEW })
        assertNotNull(RunController.notice.value)
        RunController.notice.value = null
    }

    @Test fun returningToAppShowsStartTab() {
        seed()
        rule.onNodeWithTag("tab4").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("achievements").assertIsDisplayed()
        shot("60_achievements")
        // 화면 회전 같은 재구성은 탭을 유지
        rule.onNodeWithTag("tab3").performClick(); rule.waitForIdle()
        // 앱 밖으로 나갔다 돌아오면 달리기 탭(시작 버튼)
        rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        rule.waitForIdle()
        rule.onNodeWithTag("start").assertIsDisplayed()
        // 기록 상세를 보다가 나갔다 와도 마찬가지
        rule.onNodeWithTag("tab1").performClick(); rule.waitForIdle()
        rule.onAllNodesWithText("NEW").onFirst().performClick(); rule.waitForIdle()
        rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        rule.waitForIdle()
        rule.onNodeWithTag("start").assertIsDisplayed()
        // 앱이 직접 띄운 화면(QR 스캔, 파일 저장 등)에서 돌아올 때는 보던 탭을 유지
        rule.onNodeWithTag("tab5").performClick(); rule.waitForIdle()
        app.skipHomeOnce = true
        rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        rule.waitForIdle()
        rule.onNodeWithTag("create").assertIsDisplayed()
        assertFalse(app.skipHomeOnce)
    }

    @Test fun trimIdleTailAndPickedPoint() {
        val me = app.store.identity.value
        // 10분 달리고 종료를 안 눌러 20분 동안 멈춰 있던 기록
        val r = RunRecorder("forgot", 0, System.currentTimeMillis() - 3600_000)
        for (i in 0..600) r.onFix(fixAt(i * 1000L, i * 3.0, alt = 20.0, speed = 3f))
        val rnd = java.util.Random(3)
        for (i in 601..1800) r.onFix(fixAt(i * 1000L, 1800.0 + rnd.nextGaussian() * 0.6, rnd.nextGaussian() * 0.6, alt = 20.0, speed = 0f))
        val t = r.finish(1_801_000, me.id, me.name)!!
        app.store.saveTrack(t) { Series.meta(t, true) }
        assertEquals(1801, app.store.meta("forgot")!!.durationS)
        rule.onNodeWithTag("tab1").performClick(); rule.waitForIdle()
        rule.onAllNodesWithText("나").onFirst().performClick(); rule.waitForIdle()
        rule.waitUntil(5000) { rule.onAllNodesWithText("페이스").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("trim").performScrollTo().performClick(); rule.waitForIdle()
        dialogShot("61_trim_dialog")
        rule.onNodeWithTag("trimIdle").performClick(); rule.waitForIdle()
        rule.waitUntil(5000) { app.store.meta("forgot")!!.durationS < 700 }
        val m = app.store.meta("forgot")!!
        assertTrue("dur=${m.durationS}", m.durationS in 595..640)
        assertEquals(1800.0, m.distanceM, 30.0)
        assertEquals(m.n, app.store.loadTrack("forgot")!!.n)
        // 그래프에서 고른 지점 이후 자르기
        Thread.sleep(400); rule.waitForIdle()        // 잘라낸 기록을 다시 불러올 때까지
        RunController.notice.value = null
        rule.mainClock.advanceTimeBy(12_000); rule.waitForIdle()      // 하단 안내 메시지가 사라질 때까지
        rule.onNodeWithTag("pager").performScrollTo().performTouchInput { click(androidx.compose.ui.geometry.Offset(width * 0.5f, height * 0.4f)) }; rule.waitForIdle()
        rule.onNodeWithTag("trim").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("trimPicked").performClick(); rule.waitForIdle()
        rule.waitUntil(5000) { app.store.meta("forgot")!!.distanceM < 1500 }
        val m2 = app.store.meta("forgot")!!
        assertTrue("dist=${m2.distanceM}", m2.distanceM in 600.0..1200.0)
        assertTrue(m2.durationS in 200..400)
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
