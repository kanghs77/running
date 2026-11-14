package app.runmate

import app.runmate.core.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** 실제 서버를 상대로 하는 테스트. -Dlive=1 일 때만 실행. */
class LiveTest {
    private val http = OkHttpClient.Builder().callTimeout(40, TimeUnit.SECONDS).build()
    private fun live() = assumeTrue(System.getProperty("live") == "1")

    @Test fun realNtfyEndToEnd() = runBlocking {
        live()
        val relay = NtfyRelay(http)
        val me = SyncTest.Dev(listOf(relay), "나"); val f = SyncTest.Dev(listOf(relay), "철수"); val stranger = SyncTest.Dev(listOf(relay), "남")
        me.time = System.currentTimeMillis(); f.time = me.time; stranger.time = me.time
        val g = me.engine.createGroup("라이브 테스트")
        assertNotNull(f.engine.joinGroup(Invite.encode(g.name, g.keyBytes())))
        stranger.engine.createGroup("다른 그룹")
        assertTrue(me.engine.flushOutbox()); assertTrue(f.engine.flushOutbox())
        val t = makeTrack("live1", me.store.identity.value.id, "나", me.time, n = 1800)   // 30분 달리기
        me.store.saveTrack(t) { Series.meta(t, true) }
        me.engine.shareTrack("live1", listOf(g.id))
        assertTrue("publish failed", me.engine.flushOutbox())
        delay(1500)
        val r = f.engine.sync()
        assertTrue(r.ok)
        assertEquals(1, r.newTracks.size)
        val got = f.store.loadTrack("live1")!!
        assertEquals(t.n, got.n); assertArrayEquals(t.x, got.x); assertArrayEquals(t.spd, got.spd)
        assertNull(got.oLat)
        assertEquals(listOf("나"), f.store.groups.value[0].members.map { it.name })
        assertEquals(0, stranger.engine.sync().newTracks.size)
        // 두 번째 동기화(since 사용)에서 중복 없이, 새 기록만
        me.time += 5000
        val t2 = makeTrack("live2", me.store.identity.value.id, "나", me.time, n = 200)
        me.store.saveTrack(t2) { Series.meta(t2, true) }
        me.engine.shareTrack("live2", listOf(g.id)); assertTrue(me.engine.flushOutbox())
        delay(1500)
        // 서버 반영이 조금 늦을 수 있으므로 몇 번 다시 확인한다(앱에서는 다음 동기화 때 받게 됨)
        var got2 = emptyList<String>()
        for (i in 0 until 6) { got2 = f.engine.sync().newTracks.map { it.id }; if (got2.isNotEmpty()) break; delay(2000) }
        assertEquals(listOf("live2"), got2)
        assertEquals(listOf("철수"), me.apply { engine.sync() }.store.groups.value[0].members.map { it.name })
    }

    @Test fun realElevationApis() = runBlocking {
        live()
        // 한강 잠원 부근에서 동쪽으로 3km
        val r = RunRecorder("e", 0, 0)
        for (i in 0..1000) {
            val (lat, lon) = Geo.unproject(37.5205, 127.0000, i * 3.0, 0.0)
            r.onFix(Fix(i * 1000L, lat, lon, 15.0, 5f, 3f))
        }
        val t = r.finish(1_001_000, "me", "나")!!
        val c = ElevationClient(http)
        val res = ElevationProfile.resolve(t, c)!!
        println("LIVE src=${res.eleSrc} first=${res.ele!!.first() / 10.0} min=${res.ele!!.min() / 10.0} max=${res.ele!!.max() / 10.0} samples=${ElevationProfile.sampleIndices(t).size}")
        assertEquals("srtm30m", res.eleSrc)
        assertEquals(t.n, res.ele!!.size)
        assertTrue(res.ele!!.all { it in -500..3000 })     // 한강변: -50m ~ 300m
        // 예비 API도 단독으로 동작하는지
        val meteo = ElevationClient(http, topoBase = "http://127.0.0.1:1/x")
        val res2n = ElevationProfile.resolve(t, meteo)
        // 예비 API는 IP당 일일 무료 한도가 있다. 한도를 넘긴 날에는 이 부분만 건너뛴다(앱은 GPS 고도로 대체 후 나중에 재시도).
        assumeTrue("Open-Meteo daily limit", res2n != null)
        val res2 = res2n!!
        println("LIVE src=${res2.eleSrc} min=${res2.ele!!.min() / 10.0} max=${res2.ele!!.max() / 10.0}")
        assertEquals("copernicus90m", res2.eleSrc)
    }

    @Test fun realMapTile() {
        live()
        val n = 1 shl 16
        val url = "https://tile.openstreetmap.org/16/${Math.floor(Mercator.x(126.978) * n).toInt()}/${Math.floor(Mercator.y(37.5665) * n).toInt()}.png"
        http.newCall(okhttp3.Request.Builder().url(url).header("User-Agent", "RunMate/1.2 (Android running tracker)").build()).execute().use { r ->
            assertEquals(200, r.code)
            val b = r.body!!.bytes()
            println("LIVE tile bytes=${b.size} cache=${r.header("Cache-Control")}")
            assertEquals(0x89.toByte(), b[0]); assertEquals('P'.code.toByte(), b[1]); assertTrue(b.size > 500)
        }
    }
}
