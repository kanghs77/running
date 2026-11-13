package app.runmate

import app.runmate.core.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Collections
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

fun tmpDir(): File = Files.createTempDirectory("rm").toFile()

/** 위도 37.5 부근에서 동쪽으로 speed m/s로 달리는 GPS 점을 만든다 */
fun fixAt(tMs: Long, eastM: Double, northM: Double = 0.0, acc: Float = 5f, alt: Double? = 20.0, speed: Float? = null): Fix {
    val (lat, lon) = Geo.unproject(37.5, 127.0, eastM, northM)
    return Fix(tMs, lat, lon, alt, acc, speed)
}

fun makeTrack(id: String, owner: String, name: String, startMs: Long, n: Int = 300, speed: Double = 3.0, withOrigin: Boolean = true): Track {
    val r = RunRecorder(id, 0, startMs)
    for (i in 0 until n) r.onFix(fixAt(i * 1000L, i * speed, 30 * sin(i / 40.0), alt = 20 + 3 * sin(i / 50.0), speed = speed.toFloat()))
    val t = r.finish(n * 1000L, owner, name)!!
    return if (withOrigin) t else t.copy(oLat = null, oLon = null)
}

class CryptoTest {
    @Test fun bip340Vector0() {
        val sk = Crypto.unhex("0000000000000000000000000000000000000000000000000000000000000003")
        val pk = Secp256k1.pubkey(sk)
        assertEquals("f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9", Crypto.hex(pk))
        val sig = Secp256k1.sign(ByteArray(32), sk, ByteArray(32))
        assertEquals(
            "e907831f80848d1069a5371b402410364bdf1c5f8307b0084c55f1ce2dca821525f66a4a85ea8b71e482a74f382d2ce5ebeee8fdb2172f477df4900d310536c0",
            Crypto.hex(sig))
        assertTrue(Secp256k1.verify(ByteArray(32), pk, sig))
    }

    @Test fun bip340Vector1() {
        val sk = Crypto.unhex("b7e151628aed2a6abf7158809cf4f3c762e7160f38b4da56a784d9045190cfef")
        val msg = Crypto.unhex("243f6a8885a308d313198a2e03707344a4093822299f31d0082efa98ec4e6c89")
        val aux = Crypto.unhex("0000000000000000000000000000000000000000000000000000000000000001")
        val pk = Secp256k1.pubkey(sk)
        assertEquals("dff1d77f2a671c5f36183726db2341be58feae1da2deced843240f7b502ba659", Crypto.hex(pk))
        val sig = Secp256k1.sign(msg, sk, aux)
        assertEquals(
            "6896bd60eeae296db48a229ff71dfe071bde413e6d43f917dc8dcf8c78de33418906d11ac976abccb20b091292bff4ea897efcb639ea871cfa95f6de339e4b0a",
            Crypto.hex(sig))
        assertTrue(Secp256k1.verify(msg, pk, sig))
    }

    @Test fun signVerifyRandomAndTamper() {
        repeat(5) {
            val sk = Secp256k1.newSecret()
            val msg = Crypto.randomBytes(32)
            val sig = Secp256k1.sign(msg, sk)
            val pk = Secp256k1.pubkey(sk)
            assertTrue(Secp256k1.verify(msg, pk, sig))
            sig[40] = (sig[40] + 1).toByte()
            assertFalse(Secp256k1.verify(msg, pk, sig))
        }
    }

    @Test fun sealOpen() {
        val k = Crypto.randomBytes(32)
        val data = "안녕하세요 runmate ".repeat(500).toByteArray()
        val s = Crypto.seal(k, data)
        assertTrue(s.size < data.size / 5)
        assertArrayEquals(data, Crypto.open(k, s))
        assertNull(Crypto.open(Crypto.randomBytes(32), s))
        s[s.size - 1] = (s[s.size - 1] + 1).toByte()
        assertNull(Crypto.open(k, s))
        assertNull(Crypto.open(k, ByteArray(5)))
        assertNull(Crypto.open(k, ByteArray(0)))
        assertArrayEquals(ByteArray(0), Crypto.open(k, Crypto.seal(k, ByteArray(0))))
    }

    @Test fun invite() {
        val k = Crypto.randomBytes(32)
        val s = Invite.encode("한강 러닝 & 크루=1", k)
        val (n, k2) = Invite.decode("  $s \n")!!
        assertEquals("한강 러닝 & 크루=1", n)
        assertArrayEquals(k, k2)
        assertNull(Invite.decode("https://example.com"))
        assertNull(Invite.decode("runmate://join?v=1&k=abc&n=eA"))
        assertNull(Invite.decode("runmate://join?v=2&k=${Crypto.b64(k)}&n=eA"))
        assertNull(Invite.decode(""))
        assertNull(Invite.decode("runmate://join?"))
        assertEquals(40, Crypto.topicOf(k).length)
        assertNotEquals(Crypto.topicOf(k), Crypto.topicOf(Crypto.randomBytes(32)))
    }

    @Test fun nostrEventIdAndSig() {
        val ev = NostrRelay.buildEvent("rmtopic", "abcd", byteArrayOf(1, 2, 3), 1700000000)
        val ser = "[0,\"${ev["pubkey"]!!.jsonPrimitive.content}\",1700000000,30078,[[\"d\",\"abcd\"],[\"t\",\"rmtopic\"]],\"AQID\"]"
        val id = Crypto.sha256(ser.toByteArray())
        assertEquals(Crypto.hex(id), ev["id"]!!.jsonPrimitive.content)
        assertTrue(Secp256k1.verify(id, Crypto.unhex(ev["pubkey"]!!.jsonPrimitive.content), Crypto.unhex(ev["sig"]!!.jsonPrimitive.content)))
    }
}

class GeoTest {
    @Test fun projectRoundTrip() {
        val (x, y) = Geo.project(37.5, 127.0, 37.51, 127.02)
        assertEquals(Geo.haversine(37.5, 127.0, 37.51, 127.02), Math.hypot(x, y), 3.0)
        val (lat, lon) = Geo.unproject(37.5, 127.0, x, y)
        assertEquals(37.51, lat, 1e-9); assertEquals(127.02, lon, 1e-9)
        assertEquals(1112.0, Geo.project(0.0, 179.995, 0.0, -179.995).first, 1.0)
    }

    @Test fun nice() {
        assertEquals(200.0, Geo.niceFloor(499.0), 0.0)
        assertEquals(500.0, Geo.niceFloor(500.0), 0.0)
        assertEquals(0.05, Geo.niceFloor(0.07), 1e-12)
        assertEquals(1.0, Geo.niceFloor(0.0), 0.0)
        assertEquals(1.0, Geo.niceFloor(Double.NaN), 0.0)
        assertEquals(listOf(0.0, 2.0, 4.0, 6.0, 8.0, 10.0), Geo.ticks(0.0, 10.0))
        assertEquals(1, Geo.ticks(5.0, 5.0).size)
        assertTrue(Geo.ticks(-3.3, 7.9).contains(0.0))
    }

    @Test fun fmt() {
        assertEquals("5'00\"", Fmt.pace(1000.0 / 300))
        assertEquals("--'--\"", Fmt.pace(0.0))
        assertEquals("01:05", Fmt.duration(65))
        assertEquals("1:00:05", Fmt.duration(3605))
        assertEquals("5.20", Fmt.km(5200.0))
    }
}

class RecorderTest {
    @Test fun straightRunDistance() {
        val r = RunRecorder("a", 0, 1000)
        for (i in 0..600) r.onFix(fixAt(i * 1000L, i * 3.0))
        assertEquals(1800.0, r.distanceM, 5.0)
        val t = r.finish(601_000, "me", "나")!!
        assertEquals(601, t.durationS)
        assertEquals(0, t.x[0]); assertEquals(0, t.y[0])
        assertEquals(18000.0, t.x.last().toDouble(), 30.0)
        assertEquals(37.5, t.oLat!!, 1e-9)
        assertEquals(3.0, r.currentSpeed, 0.2)
    }

    @Test fun rejectsBadAccuracyAndTeleport() {
        val r = RunRecorder("a", 0, 0)
        assertFalse(r.onFix(fixAt(0, 0.0, acc = 80f)))
        assertFalse(r.hasOrigin)
        assertTrue(r.onFix(fixAt(1000, 0.0)))
        assertTrue(r.onFix(fixAt(2000, 3.0)))
        assertFalse(r.onFix(fixAt(3000, 500.0)))      // 순간이동
        assertFalse(r.onFix(fixAt(3500, 6.0, acc = 50f)))
        assertTrue(r.onFix(fixAt(4000, 9.0)))
        assertEquals(9.0, r.distanceM, 0.5)
        assertFalse(r.onFix(fixAt(4000, 12.0)))       // 같은 시각
        assertFalse(r.onFix(fixAt(3000, 12.0)))       // 과거 시각
        assertFalse(r.onFix(Fix(5000, Double.NaN, 127.0, null, 5f, null)))
    }

    @Test fun standingStillDoesNotAccumulate() {
        val r = RunRecorder("a", 0, 0)
        val rnd = java.util.Random(1)
        for (i in 0..300) r.onFix(fixAt(i * 1000L, rnd.nextGaussian() * 0.8, rnd.nextGaussian() * 0.8, acc = 8f))
        assertTrue("dist=${r.distanceM}", r.distanceM < 40)
        assertTrue(r.size in 25..80)   // 10초마다 시간축 점
    }

    @Test fun tooShortIsNull() {
        val r = RunRecorder("a", 0, 0)
        assertNull(r.finish(1000, "me", "나"))
        r.onFix(fixAt(0, 0.0)); r.onFix(fixAt(1000, 3.0))
        assertNull(r.finish(2000, "me", "나"))
    }

    @Test fun persistentTeleportResyncs() {
        val r = RunRecorder("a", 0, 0)
        r.onFix(fixAt(0, 0.0))
        var accepted = 0
        for (i in 1..10) if (r.onFix(fixAt(i * 1000L, 5000.0 + i * 3))) accepted++
        assertTrue(accepted >= 4)
    }

    @Test fun fixBeforeStartIgnoredAndNoAltitude() {
        val r = RunRecorder("a", 10_000, 0)
        assertFalse(r.onFix(fixAt(5000, 0.0)))
        assertTrue(r.onFix(fixAt(11_000, 0.0, alt = null)))
        assertTrue(r.onFix(fixAt(12_000, 3.0, alt = 55.0)))
        assertTrue(r.onFix(fixAt(13_000, 6.0, alt = 56.0)))
        val t = r.finish(14_000, "me", "나")
        assertNull(t)  // 6m < 10m
        val s = r.snapshot()
        assertEquals(1.0, s.relAltM, 0.01)
    }

    @Test fun journalRecovery() {
        val f = File(tmpDir(), "run/current.csv")
        val r = RunRecorder("rid", 0, 123456, f)
        for (i in 0..100) r.onFix(fixAt(i * 1000L, i * 3.0))
        r.flush()
        val t = RunRecorder.recover(f, "me", "나")!!
        assertEquals("rid", t.id); assertEquals(123456, t.startMs)
        assertEquals(101, t.n)
        assertEquals(300.0, t.distanceM, 2.0)
        assertEquals(37.5, t.oLat!!, 1e-9)
        f.writeText("garbage"); assertNull(RunRecorder.recover(f, "me", "나"))
        f.writeText("RM1,x,1,37.5,127.0\n0,0,0,0,0\nbad,line\n"); assertNull(RunRecorder.recover(f, "me", "나"))
        assertNull(RunRecorder.recover(File(f.parentFile, "none.csv"), "me", "나"))
    }

    @Test fun snapshotDownsamples() {
        val r = RunRecorder("a", 0, 0)
        for (i in 0..3000) r.onFix(fixAt(i * 1000L, i * 3.0))
        val s = r.snapshot()
        assertTrue(s.px.size <= RunRecorder.PLOT_POINTS + 2)
        assertEquals(9000f, s.px.last(), 2f)
        assertEquals(0, RunRecorder("b", 0, 0).snapshot().px.size)
    }
}

class TrackTest {
    @Test fun dtoRoundTripAndValidation() {
        val t = makeTrack("t1", "o", "철수", 1000)
        val d = t.toDto()
        val json = J.encodeToString(TrackDto.serializer(), d)
        val back = Track.fromDto(J.decodeFromString(TrackDto.serializer(), json))!!
        assertArrayEquals(t.x, back.x); assertArrayEquals(t.t, back.t); assertArrayEquals(t.spd, back.spd)
        assertNull(Track.fromDto(d.copy(x = d.x.drop(1))))
        assertNull(Track.fromDto(d.copy(t = emptyList(), x = emptyList(), y = emptyList(), alt = emptyList(), spd = emptyList())))
        assertNull(Track.fromDto(d.copy(distanceM = Double.NaN)))
        assertNull(Track.fromDto(d.copy(distanceM = -1.0)))
        assertNull(Track.fromDto(d.copy(id = "")))
        assertNull(Track.fromDto(d.copy(t = d.t.map { -5 })))
        assertNull(Track.fromDto(d.copy(x = d.x.map { Int.MAX_VALUE })))
        assertNull(Track.fromDto(d.copy(ele = listOf(1))))
    }

    @Test fun sharingStripsOriginAndCaps() {
        val t = makeTrack("t1", "o", "철수", 1000, n = 4000)
        val s = t.forSharing()
        assertNull(s.oLat); assertNull(s.oLon)
        assertEquals(Track.MAX_SHARE_POINTS, s.n)
        assertEquals(t.x.last(), s.x.last()); assertEquals(t.x[0], s.x[0])
        val sealed = Crypto.seal(Crypto.randomBytes(32), J.encodeToString(TrackDto.serializer(), s.toDto()).toByteArray())
        assertTrue("size=${sealed.size}", sealed.size < 60_000)
        assertFalse(J.encodeToString(TrackDto.serializer(), s.toDto()).contains("oLat"))
    }

    @Test fun seriesAndAscent() {
        val t = makeTrack("t1", "o", "철수", 1000, n = 600)
        val cum = Series.cumDist(t)
        assertEquals(t.distanceM, cum.last(), t.distanceM * 0.01)
        for (m in Metric.values()) for (xa in XAxis.values()) for (sm in listOf(0.0, 20.0, 120.0)) {
            val p = Series.of(t, xa, m, sm)
            assertEquals(t.n, p.n)
            for (i in 1 until p.n) assertTrue(p.xs[i] >= p.xs[i - 1])
            assertTrue(p.ys.any { it.isFinite() })
        }
        val pace = Series.of(t, XAxis.DISTANCE, Metric.PACE, 20.0).ys.filter { it.isFinite() }
        assertEquals(1000.0 / 3.0 / 60, pace[pace.size / 2], 0.6)
        // 고도 진폭 3m 사인 -> 상승 약 3~12m
        val a = Series.ascent(t)
        assertTrue("ascent=$a", a in 2.0..14.0)
        // 점 1개짜리도 죽지 않아야 한다
        val one = Track("x", "o", "n", 0, 1, 0.0, IntArray(1), IntArray(1), IntArray(1), IntArray(1), IntArray(1))
        for (m in Metric.values()) assertEquals(1, Series.of(one, XAxis.TIME, m, 20.0).n)
        assertEquals(0.0, Series.ascent(one), 0.0)
    }

    @Test fun elevationInterpolation() {
        val t = makeTrack("t1", "o", "철수", 1000, n = 600)
        val idx = ElevationProfile.sampleIndices(t)
        assertEquals(0, idx.first()); assertEquals(t.n - 1, idx.last())
        assertTrue(idx.size in 20..ElevationProfile.MAX_SAMPLES)
        val coords = ElevationProfile.coords(t, idx)
        assertEquals(37.5, coords[0].first, 1e-6); assertEquals(127.0, coords[0].second, 1e-6)
        val cum = Series.cumDist(t)
        val vals = idx.map { 10 + cum[it] / 100.0 }   // 100m당 1m 상승
        val e = ElevationProfile.interpolate(t, idx, vals)
        for (i in 0 until t.n) assertEquals((10 + cum[i] / 100.0) * 10, e[i].toDouble(), 1.01)
        val long = makeTrack("t2", "o", "철수", 1000, n = 20000)
        assertTrue(ElevationProfile.sampleIndices(long).size <= ElevationProfile.MAX_SAMPLES)
        assertTrue(ElevationProfile.coords(t.copy(oLat = null), idx).isEmpty())
    }

    @Test fun rankingAndTrend() {
        val day = 86400_000L
        val base = 1_750_000_000_000L
        fun m(id: String, owner: String, name: String, d: Int, dist: Double, groups: List<String>, mine: Boolean = false) =
            TrackMeta(id, owner, name, base + d * day, 1800, dist, 5.0, mine, groupIds = groups)
        val metas = listOf(
            m("1", "a", "철수", 0, 5000.0, listOf("g1")), m("2", "a", "철수2", 2, 7000.0, listOf("g1")),
            m("3", "b", "영희", 1, 10000.0, listOf("g2")), m("4", "me", "나", 1, 3000.0, listOf("g1", "g2"), true),
            m("5", "me", "나", 40, 9000.0, emptyList(), true),
        )
        val me = Identity("me", "나")
        val names = Ranking.names(metas, emptyList(), me)
        assertEquals("철수2", names["a"])
        val all = Ranking.rank(Ranking.filter(metas, base, base + 10 * day, null), names, RankBy.DISTANCE)
        assertEquals(listOf("a", "b", "me"), all.map { it.ownerId })
        assertEquals(12000.0, all[0].distanceM, 0.0); assertEquals(2, all[0].runs)
        val g2 = Ranking.rank(Ranking.filter(metas, base, base + 10 * day, "g2"), names, RankBy.DISTANCE, listOf("zz"))
        assertEquals(listOf("b", "me", "zz"), g2.map { it.ownerId })
        assertEquals("a", Ranking.rank(Ranking.filter(metas, base, base + 10 * day, null), names, RankBy.RUNS)[0].ownerId)
        assertTrue(Ranking.rank(emptyList(), names, RankBy.TIME).isEmpty())
        // 경계: to는 미포함
        assertEquals(0, Ranking.filter(metas, base + day, base + day, null).size)

        val z = java.time.ZoneId.of("Asia/Seoul")
        val weekly = Trend.compute(metas, Bucket.WEEK, TrendMetric.DISTANCE, z)
        assertTrue(weekly.size in 2..3)
        assertEquals(12.0 + 3.0 + 10.0 + 9.0, weekly.sumOf { it.values.values.sum() }, 1e-9)
        assertEquals(4, Trend.compute(metas, Bucket.RUN, TrendMetric.PACE, z).size)
        assertEquals(6.0, Trend.compute(metas, Bucket.RUN, TrendMetric.PACE, z)[0].values["a"]!!, 1e-9)
        assertTrue(Trend.compute(emptyList(), Bucket.MONTH, TrendMetric.RUNS, z).isEmpty())
    }
}

class StoreTest {
    @Test fun persistsAcrossInstances() {
        val d = tmpDir()
        val s = Store(d)
        val id = s.identity.value
        s.setName("  달리는 호랑이  ")
        val t = makeTrack("t1", id.id, "x", 5000)
        s.saveTrack(t) { Series.meta(t, true) }
        s.updateGroups { it + Group("g", "그룹", Crypto.b64(Crypto.randomBytes(32)), 1) }
        s.updateSettings { it.copy(notifications = false) }
        assertTrue(s.markSeen("a")); assertFalse(s.markSeen("a")); s.persistSeen()

        val s2 = Store(d)
        assertEquals(id.id, s2.identity.value.id)
        assertEquals("달리는 호랑이", s2.identity.value.name)
        assertTrue(s2.settings.value.nameSet); assertFalse(s2.settings.value.notifications)
        assertEquals(1, s2.metas.value.size); assertEquals(1, s2.groups.value.size)
        assertArrayEquals(t.x, s2.loadTrack("t1")!!.x)
        assertFalse(s2.markSeen("a"))
        s2.deleteTrack("t1")
        assertNull(s2.loadTrack("t1")); assertTrue(Store(d).metas.value.isEmpty())
    }

    @Test fun survivesCorruptFiles() {
        val d = tmpDir()
        val s = Store(d)
        val t = makeTrack("t1", s.identity.value.id, "x", 5000)
        s.saveTrack(t) { Series.meta(t, true) }
        val id = s.identity.value.id
        for (n in listOf("settings.json", "groups.json", "outbox.json", "seen.json", "tracks/index.json")) File(d, n).writeText("{not json")
        val s2 = Store(d)
        assertEquals(id, s2.identity.value.id)
        assertEquals(1, s2.metas.value.size)       // 트랙 파일에서 인덱스 복구
        assertTrue(s2.metas.value[0].mine)
        assertTrue(s2.groups.value.isEmpty())
        File(d, "tracks/t1.json").writeText("zzz")
        assertNull(s2.loadTrack("t1"))
        File(d, "identity.json").writeText("")
        assertNotEquals("", Store(d).identity.value.id)
    }

    @Test fun weirdIdsAreSafe() {
        val s = Store(tmpDir())
        val t0 = makeTrack("../../evil", "o", "x", 1)
        s.saveTrack(t0) { Series.meta(t0, false) }
        assertNotNull(s.loadTrack("../../evil"))
        assertNull(s.loadTrack("nope"))
        s.setName("   "); assertFalse(s.settings.value.nameSet)
    }
}

/** 메모리 릴레이: 여러 기기가 공유 */
class FakeRelay(override val name: String) : Relay {
    val msgs = Collections.synchronizedList(ArrayList<Triple<String, Long, ByteArray>>())
    @Volatile var down = false
    var clock = { System.currentTimeMillis() / 1000 }
    override suspend fun publish(topic: String, msgId: String, sealed: ByteArray): Boolean {
        if (down) return false
        msgs.add(Triple(topic, clock(), sealed)); return true
    }
    override suspend fun fetch(topics: List<String>, sinceSec: Long): List<Incoming>? {
        if (down) return null
        return synchronized(msgs) { msgs.filter { it.first in topics && it.second >= sinceSec }.map { Incoming(it.first, it.third) } }
    }
}

class SyncTest {
    class Dev(val relays: List<Relay>, name: String) {
        val store = Store(tmpDir())
        var time = 1_760_000_000_000L
        val engine = SyncEngine(store, relays) { time }
        init { store.setName(name) }
        fun run(id: String, startMs: Long = time): Track {
            val t = makeTrack(id, store.identity.value.id, store.identity.value.name, startMs)
            store.saveTrack(t) { Series.meta(t, true) }
            return t
        }
    }

    @Test fun twoGroupsEndToEnd() = runBlocking {
        val a = FakeRelay("ntfy"); val b = FakeRelay("nostr"); val relays = listOf(a, b)
        val me = Dev(relays, "나"); val f1 = Dev(relays, "철수"); val f2 = Dev(relays, "영희"); val out = Dev(relays, "외부인")
        val g1 = me.engine.createGroup("그룹1"); val g2 = me.engine.createGroup("그룹2")
        assertNotNull(f1.engine.joinGroup(Invite.encode(g1.name, g1.keyBytes())))
        assertNotNull(f2.engine.joinGroup(Invite.encode(g2.name, g2.keyBytes())))
        out.engine.createGroup("다른 그룹")
        assertNull(f1.engine.joinGroup("garbage"))
        // 같은 초대 두 번 -> 중복 그룹 없음
        f1.engine.joinGroup(Invite.encode(g1.name, g1.keyBytes())); assertEquals(1, f1.store.groups.value.size)
        for (d in listOf(me, f1, f2, out)) assertTrue(d.engine.flushOutbox())
        for (d in listOf(me, f1, f2, out)) d.engine.sync()
        assertEquals(listOf("철수"), me.store.groups.value.first { it.id == g1.id }.members.map { it.name })
        assertEquals(listOf("영희"), me.store.groups.value.first { it.id == g2.id }.members.map { it.name })
        assertEquals(listOf("나"), f1.store.groups.value[0].members.map { it.name })

        me.run("run1")
        me.engine.shareTrack("run1", listOf(g1.id, g2.id))
        assertEquals(2, me.store.meta("run1")!!.pending.size)
        assertTrue(me.engine.flushOutbox())
        assertEquals(setOf(g1.id, g2.id), me.store.meta("run1")!!.groupIds.toSet())
        assertTrue(me.store.meta("run1")!!.pending.isEmpty())

        val r1 = f1.engine.sync(); val r2 = f2.engine.sync(); val r3 = out.engine.sync()
        assertEquals(1, r1.newTracks.size); assertEquals(1, r2.newTracks.size); assertEquals(0, r3.newTracks.size)
        val got = f1.store.loadTrack("run1")!!
        assertNull(got.oLat)                                   // 절대 위치는 공유되지 않음
        assertEquals("나", f1.store.meta("run1")!!.ownerName)
        assertTrue(f1.store.meta("run1")!!.unread); assertFalse(f1.store.meta("run1")!!.mine)
        // 다시 동기화해도 중복 없음
        assertEquals(0, f1.engine.sync().newTracks.size); assertEquals(1, f1.store.metas.value.size)
        // 그룹1에만 보낸 기록은 그룹2 사람이 못 받는다
        me.run("run2"); me.engine.shareTrack("run2", listOf(g1.id)); me.engine.flushOutbox()
        assertEquals(1, f1.engine.sync().newTracks.size); assertEquals(0, f2.engine.sync().newTracks.size)
        // 보내지 않은 기록은 아무도 못 받는다
        me.run("run3")
        assertEquals(0, f1.engine.sync().newTracks.size)
        assertEquals(0, a.msgs.count { false })
    }

    @Test fun offlineQueuesAndRetries() = runBlocking {
        val a = FakeRelay("ntfy"); val b = FakeRelay("nostr")
        val me = Dev(listOf(a, b), "나"); val f = Dev(listOf(a, b), "철수")
        val g = me.engine.createGroup("g"); f.engine.joinGroup(Invite.encode(g.name, g.keyBytes()))
        a.down = true; b.down = true
        me.run("r"); me.engine.shareTrack("r", listOf(g.id))
        assertFalse(me.engine.flushOutbox())
        assertEquals(listOf(g.id), me.store.meta("r")!!.pending)
        assertFalse(f.engine.sync().ok)
        // 앱 재시작 후에도 대기열 유지
        assertEquals(2, Store(File(me.store.journalFile.parentFile.parentFile.path)).outbox.value.size)
        // 주 경로만 복구: 전송은 됐지만 백업 경로는 대기
        a.down = false
        assertFalse(me.engine.flushOutbox())
        assertTrue(me.store.meta("r")!!.pending.isEmpty()); assertEquals(listOf(g.id), me.store.meta("r")!!.groupIds)
        assertEquals(1, f.engine.sync(fast = true).newTracks.size)
        b.down = false
        assertTrue(me.engine.flushOutbox())
        assertEquals(0, f.engine.sync().newTracks.size)       // 백업 경로로 같은 메시지가 와도 중복 없음
        // 오래된 실패 항목은 정리
        a.down = true; b.down = true
        me.run("r2"); me.engine.shareTrack("r2", listOf(g.id))
        me.time += SyncEngine.MAX_AGE_MS + 1
        assertTrue(me.engine.flushOutbox()); assertTrue(me.store.meta("r2")!!.pending.isEmpty())
    }

    @Test fun backupRelayDeliversWhenPrimaryExpired() = runBlocking {
        val a = FakeRelay("ntfy"); val b = FakeRelay("nostr")
        val me = Dev(listOf(a, b), "나"); val f = Dev(listOf(a, b), "철수")
        val g = me.engine.createGroup("g"); f.engine.joinGroup(Invite.encode(g.name, g.keyBytes()))
        me.run("r"); me.engine.shareTrack("r", listOf(g.id)); me.engine.flushOutbox()
        a.msgs.clear()                                         // ntfy 12시간 보관 만료 상황
        assertEquals(0, f.engine.sync(fast = true).newTracks.size)
        assertEquals(1, f.engine.sync().newTracks.size)
    }

    @Test fun rejectsForgedAndMalformed() = runBlocking {
        val a = FakeRelay("ntfy")
        val me = Dev(listOf(a), "나"); val f = Dev(listOf(a), "철수")
        val g = me.engine.createGroup("g"); f.engine.joinGroup(Invite.encode(g.name, g.keyBytes()))
        val key = g.keyBytes()
        fun put(env: Envelope, k: ByteArray = key) = a.msgs.add(Triple(g.id, System.currentTimeMillis() / 1000, Crypto.seal(k, J.encodeToString(Envelope.serializer(), env).toByteArray())))
        val t = makeTrack("x1", "attacker", "공격자", 1)
        a.msgs.add(Triple(g.id, 0L, ByteArray(100)))                                           // 쓰레기
        a.msgs.add(Triple(g.id, 0L, Crypto.seal(key, "not json".toByteArray())))
        put(Envelope(id = "m1", from = "attacker", name = "x", ts = 1, type = "track", track = t.toDto()), Crypto.randomBytes(32)) // 다른 키
        put(Envelope(id = "m2", from = "someone", name = "x", ts = 1, type = "track", track = t.toDto()))   // 소유자 불일치
        put(Envelope(id = "m3", from = "attacker", name = "x", ts = 1, type = "track", track = t.toDto().copy(x = listOf(1))))
        put(Envelope(id = "m4", from = "attacker", name = "x", ts = 1, type = "track", track = null))
        put(Envelope(id = "m5", from = "attacker", name = "x", ts = 1, type = "unknown"))
        put(Envelope(id = "m6", from = me.store.identity.value.id, name = "x", ts = 1, type = "track", track = t.toDto().copy(ownerId = me.store.identity.value.id)))
        assertEquals(0, me.engine.sync().newTracks.size)
        assertTrue(me.store.metas.value.isEmpty())
        // 내 기록 id를 덮어쓰려는 시도
        me.run("mine1")
        put(Envelope(id = "m7", from = "attacker", name = "x", ts = 1, type = "track", track = makeTrack("mine1", "attacker", "x", 1).toDto()))
        assertEquals(0, me.engine.sync().newTracks.size)
        assertTrue(me.store.meta("mine1")!!.mine)
        // 정상 메시지는 통과
        put(Envelope(id = "m8", from = "attacker", name = "", ts = 1, type = "track", track = t.toDto()))
        assertEquals(1, me.engine.sync().newTracks.size)
        assertEquals("이름없음", me.store.meta("x1")!!.ownerName)
    }

    @Test fun leaveAndRename() = runBlocking {
        val a = FakeRelay("ntfy")
        val me = Dev(listOf(a), "나"); val f = Dev(listOf(a), "철수")
        val g = me.engine.createGroup("g"); f.engine.joinGroup(Invite.encode(g.name, g.keyBytes()))
        f.engine.flushOutbox(); me.engine.sync()
        f.time += 1000; f.store.setName("철수짱"); f.engine.announceName(); f.engine.flushOutbox(); me.engine.sync()
        assertEquals(listOf("철수짱"), me.store.groups.value[0].members.map { it.name })
        me.run("r"); me.engine.shareTrack("r", listOf(g.id)); me.engine.flushOutbox(); f.engine.sync()
        f.time += 1000
        val fg = f.store.groups.value[0]
        f.engine.leaveGroup(fg.id); f.engine.announceLeave(fg)
        assertTrue(f.store.groups.value.isEmpty())
        assertTrue(f.store.meta("r")!!.groupIds.isEmpty())    // 기록은 남고 그룹 연결만 해제
        me.engine.sync()
        assertTrue(me.store.groups.value[0].members.isEmpty())
        // 그룹이 사라진 뒤 공유 시도는 무시
        f.run("fr"); f.engine.shareTrack("fr", listOf(fg.id)); assertTrue(f.store.meta("fr")!!.pending.isEmpty())
        // 받은 기록은 재공유 불가
        f.engine.shareTrack("r", listOf(fg.id)); assertTrue(f.store.outbox.value.isEmpty())
    }
}

class NetTest {
    @Test fun ntfyChunkingAgainstMockServer() = runBlocking {
        val stored = Collections.synchronizedList(ArrayList<Pair<String, String>>())
        val srv = MockWebServer()
        srv.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPathSegments
                return if (request.method == "POST") {
                    val body = request.body.readUtf8()
                    if (body.length > 4096) MockResponse().setResponseCode(429)
                    else { stored.add(path[0] to body); MockResponse().setBody("{}") }
                } else {
                    val topics = path[0].split(',')
                    val sb = StringBuilder()
                    sb.append("{\"event\":\"open\",\"topic\":\"x\"}\n")
                    synchronized(stored) {
                        for ((t, b) in stored.shuffled()) if (t in topics)
                            sb.append(buildJsonObject { put("event", "message"); put("topic", t); put("message", b) }.toString()).append('\n')
                    }
                    sb.append("{\"event\":\"message\",\"topic\":\"${topics[0]}\",\"message\":\"someone else's text\"}\n")
                    sb.append("{\"event\":\"message\",\"topic\":\"${topics[0]}\",\"message\":\"RM1:zz:0:2:AAAA\"}\n")   // 미완성 조각
                    sb.append("not json\n")
                    MockResponse().setBody(sb.toString())
                }
            }
        }
        srv.start()
        val relay = NtfyRelay(OkHttpClient(), srv.url("/").toString().trimEnd('/'), 0)
        val big = Crypto.randomBytes(20_000); val small = byteArrayOf(9, 8, 7)
        assertTrue(relay.publish("t1", "aa", big)); assertTrue(relay.publish("t2", "bb", small))
        assertTrue(stored.size >= 8)
        val got = relay.fetch(listOf("t1", "t2"), 0)!!
        assertEquals(2, got.size)
        assertArrayEquals(big, got.first { it.topic == "t1" }.sealed)
        assertArrayEquals(small, got.first { it.topic == "t2" }.sealed)
        assertFalse(relay.publish("t1", "cc", Crypto.randomBytes(400_000)))   // 너무 큼
        srv.shutdown()
        assertNull(relay.fetch(listOf("t1"), 0)); assertFalse(relay.publish("t1", "dd", small))
    }

    @Test fun nostrAgainstMockRelay() = runBlocking {
        val events = Collections.synchronizedList(ArrayList<JsonObject>())
        fun relayServer(accept: Boolean): MockWebServer {
            val srv = MockWebServer()
            srv.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onClosing(ws: WebSocket, code: Int, reason: String) { ws.close(1000, null) }
                    override fun onMessage(ws: WebSocket, text: String) {
                        val a = J.parseToJsonElement(text).jsonArray
                        when (a[0].jsonPrimitive.content) {
                            "EVENT" -> {
                                val ev = a[1].jsonObject
                                val ser = JsonArray(listOf(JsonPrimitive(0), ev["pubkey"]!!, ev["created_at"]!!, ev["kind"]!!, ev["tags"]!!, ev["content"]!!)).toString()
                                val id = Crypto.sha256(ser.toByteArray())
                                val valid = Crypto.hex(id) == ev["id"]!!.jsonPrimitive.content &&
                                    Secp256k1.verify(id, Crypto.unhex(ev["pubkey"]!!.jsonPrimitive.content), Crypto.unhex(ev["sig"]!!.jsonPrimitive.content))
                                if (valid && accept) events.add(ev)
                                ws.send(buildJsonArray { add("OK"); add(ev["id"]!!); add(valid && accept); add(if (accept) "" else "blocked: no") }.toString())
                            }
                            "REQ" -> {
                                val f = a[2].jsonObject
                                val topics = f["#t"]!!.jsonArray.map { it.jsonPrimitive.content }
                                val since = f["since"]?.jsonPrimitive?.long ?: 0
                                synchronized(events) {
                                    for (ev in events) {
                                        val t = ev["tags"]!!.jsonArray.first { it.jsonArray[0].jsonPrimitive.content == "t" }.jsonArray[1].jsonPrimitive.content
                                        if (t in topics && ev["created_at"]!!.jsonPrimitive.long >= since)
                                            ws.send(buildJsonArray { add("EVENT"); add(a[1]); add(ev) }.toString())
                                    }
                                }
                                ws.send("[\"NOTICE\",\"hi\"]"); ws.send("garbage")
                                ws.send(buildJsonArray { add("EOSE"); add(a[1]) }.toString())
                            }
                        }
                    }
                })
            }
            srv.start(); return srv
        }
        val good = relayServer(true); val rejecting = relayServer(false)
        val http = OkHttpClient()
        val deadUrl = "ws://127.0.0.1:1/"
        val relay = NostrRelay(http, listOf(good.url("/").toString().replace("http", "ws"), rejecting.url("/").toString().replace("http", "ws"), deadUrl), 4000)
        val data = Crypto.randomBytes(30_000)
        assertTrue(relay.publish("topicA", "m1", data)); assertEquals(1, relay.lastOk)
        assertTrue(relay.publish("topicB", "m2", byteArrayOf(1)))
        val got = relay.fetch(listOf("topicA"), 0)!!
        assertEquals(1, got.size); assertArrayEquals(data, got[0].sealed)
        assertEquals(2, relay.fetch(listOf("topicA", "topicB"), System.currentTimeMillis() / 1000 - 60)!!.size)
        assertEquals(0, relay.fetch(listOf("topicA"), System.currentTimeMillis() / 1000 + 600)!!.size)
        val onlyBad = NostrRelay(http, listOf(rejecting.url("/").toString().replace("http", "ws"), deadUrl), 3000)
        assertFalse(onlyBad.publish("topicA", "m3", data))
        val dead = NostrRelay(http, listOf(deadUrl), 2000)
        assertNull(dead.fetch(listOf("topicA"), 0)); assertFalse(dead.publish("t", "m", data))
        try { good.shutdown(); rejecting.shutdown() } catch (e: Exception) {}
        http.dispatcher.executorService.shutdown()
    }

    @Test fun elevationClientFallback() = runBlocking {
        val srv = MockWebServer()
        var topoOk = true
        srv.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val u = request.requestUrl!!
                return if (u.encodedPath.startsWith("/topo")) {
                    if (!topoOk) return MockResponse().setResponseCode(429)
                    val n = u.queryParameter("locations")!!.split('|').size
                    MockResponse().setBody(buildJsonObject {
                        put("status", "OK"); putJsonArray("results") { repeat(n) { add(buildJsonObject { put("elevation", 12.5) }) } }
                    }.toString())
                } else {
                    val n = u.queryParameter("latitude")!!.split(',').size
                    MockResponse().setBody(buildJsonObject { putJsonArray("elevation") { repeat(n) { add(7.0) } } }.toString())
                }
            }
        }
        srv.start()
        val c = ElevationClient(OkHttpClient(), srv.url("/topo").toString(), srv.url("/meteo").toString(), 0)
        val pts = List(250) { 37.5 + it * 1e-4 to 127.0 }
        val (v, src) = c.lookup(pts)!!
        assertEquals(250, v.size); assertEquals("srtm30m", src); assertEquals(12.5, v[100], 0.0)
        topoOk = false
        val (v2, src2) = c.lookup(pts)!!
        assertEquals(250, v2.size); assertEquals("copernicus90m", src2); assertEquals(7.0, v2[0], 0.0)
        val t = makeTrack("e", "o", "n", 1, n = 400)
        val r = ElevationProfile.resolve(t, c)!!
        assertEquals(t.n, r.ele!!.size); assertEquals(70, r.ele!![5])
        assertNull(ElevationProfile.resolve(t.copy(oLat = null), c))
        srv.shutdown()
        assertNull(c.lookup(pts)); assertNull(c.lookup(emptyList()))
    }
}
