package app.runmate.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 무료 고도 API.
 * 1순위 OpenTopoData SRTM 30m (해상도가 가장 좋음), 실패 시 Open-Meteo(Copernicus 90m).
 */
class ElevationClient(
    private val http: OkHttpClient,
    private val topoBase: String = "https://api.opentopodata.org/v1/srtm30m",
    private val meteoBase: String = "https://api.open-meteo.com/v1/elevation",
    private val pauseMs: Long = 1100,
) {
    /** 결과: (고도 m 목록, 출처) 또는 null */
    suspend fun lookup(pts: List<Pair<Double, Double>>): Pair<List<Double>, String>? {
        if (pts.isEmpty()) return null
        batch(pts) { topo(it) }?.let { return it to "srtm30m" }
        batch(pts) { meteo(it) }?.let { return it to "copernicus90m" }
        return null
    }

    private suspend fun batch(pts: List<Pair<Double, Double>>, call: (List<Pair<Double, Double>>) -> List<Double>?): List<Double>? =
        withContext(Dispatchers.IO) {
            val out = ArrayList<Double>(pts.size)
            val chunks = pts.chunked(100)
            for ((i, c) in chunks.withIndex()) {
                val r = try { call(c) } catch (e: Exception) { null }
                if (r == null || r.size != c.size) return@withContext null
                out.addAll(r)
                if (i < chunks.size - 1) delay(pauseMs)
            }
            out
        }

    private fun f(v: Double) = String.format(java.util.Locale.US, "%.6f", v)

    private fun get(url: String): String? =
        http.newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body?.string() else null }

    private fun topo(c: List<Pair<Double, Double>>): List<Double>? {
        val body = get("$topoBase?locations=" + c.joinToString("|") { f(it.first) + "," + f(it.second) }) ?: return null
        val o = J.parseToJsonElement(body).jsonObject
        if (o["status"]?.jsonPrimitive?.content != "OK") return null
        return o["results"]!!.jsonArray.map {
            val e = it.jsonObject["elevation"]
            if (e == null || e is JsonNull) return null
            e.jsonPrimitive.double
        }
    }

    private fun meteo(c: List<Pair<Double, Double>>): List<Double>? {
        val body = get("$meteoBase?latitude=" + c.joinToString(",") { f(it.first) } + "&longitude=" + c.joinToString(",") { f(it.second) }) ?: return null
        return J.parseToJsonElement(body).jsonObject["elevation"]!!.jsonArray.map {
            if (it is JsonNull) return null
            it.jsonPrimitive.double
        }
    }
}

object ElevationProfile {
    const val MAX_SAMPLES = 300

    /** 경로를 따라 일정 간격으로 고른 점의 인덱스 (첫 점/끝 점 포함) */
    fun sampleIndices(t: Track, spacingM: Double = 25.0): IntArray {
        if (t.n == 0) return IntArray(0)
        val cum = Series.cumDist(t)
        val total = cum[t.n - 1]
        val spacing = maxOf(spacingM, total / (MAX_SAMPLES - 2))
        val idx = ArrayList<Int>()
        idx.add(0)
        var next = spacing
        for (i in 1 until t.n - 1) if (cum[i] >= next) { idx.add(i); next = cum[i] + spacing }
        if (t.n > 1) idx.add(t.n - 1)
        return idx.toIntArray()
    }

    fun coords(t: Track, idx: IntArray): List<Pair<Double, Double>> {
        val lat0 = t.oLat ?: return emptyList()
        val lon0 = t.oLon ?: return emptyList()
        return idx.map { Geo.unproject(lat0, lon0, t.x[it] / 10.0, t.y[it] / 10.0) }
    }

    /** 샘플 고도를 누적거리 기준으로 선형 보간해 모든 점의 고도(dm)를 만든다. */
    fun interpolate(t: Track, idx: IntArray, values: List<Double>): IntArray {
        require(idx.size == values.size && idx.isNotEmpty())
        val cum = Series.cumDist(t)
        val out = IntArray(t.n)
        var k = 0
        for (i in 0 until t.n) {
            while (k < idx.size - 2 && i > idx[k + 1]) k++
            val a = idx[k]
            val b = idx[minOf(k + 1, idx.size - 1)]
            val va = values[k]
            val vb = values[minOf(k + 1, idx.size - 1)]
            val span = cum[b] - cum[a]
            val f = if (i <= a || span <= 0) 0.0 else if (i >= b) 1.0 else (cum[i] - cum[a]) / span
            out[i] = ((va + (vb - va) * f) * 10).roundToInt()
        }
        return out
    }

    suspend fun resolve(t: Track, client: ElevationClient): Track? {
        if (t.oLat == null || t.oLon == null || t.n < 2) return null
        val idx = sampleIndices(t)
        val (vals, src) = client.lookup(coords(t, idx)) ?: return null
        return t.copy(ele = interpolate(t, idx, vals), eleSrc = src)
    }
}

enum class XAxis(private val ko: String, private val enName: String, private val unitKo: String, private val unitEn: String) {
    DISTANCE("거리", "Distance", "km", "km"), TIME("시간", "Time", "분", "min");
    val label: String get() = tr(ko, enName)
    val unit: String get() = tr(unitKo, unitEn)
}

enum class Metric(private val ko: String, private val enName: String, private val unitKo: String, private val unitEn: String) {
    PACE("페이스", "Pace", "분/km", "min/km"),
    SPEED("속도", "Speed", "km/h", "km/h"),
    ELE_API("상대높이(API)", "Rel. elev (API)", "m", "m"),
    ELE_GPS("상대높이(GPS)", "Rel. elev (GPS)", "m", "m"),
    DIST("누적거리", "Cum. distance", "km", "km");
    val label: String get() = tr(ko, enName)
    val unit: String get() = tr(unitKo, unitEn)
}

class Pts(val xs: DoubleArray, val ys: DoubleArray) { val n get() = xs.size }

object Series {
    fun cumDist(t: Track): DoubleArray {
        val c = DoubleArray(t.n)
        for (i in 1 until t.n) c[i] = c[i - 1] + hypot((t.x[i] - t.x[i - 1]) / 10.0, (t.y[i] - t.y[i - 1]) / 10.0)
        return c
    }

    /** 시간 창(초) 기준 이동평균 */
    fun smooth(t: Track, v: DoubleArray, windowS: Double): DoubleArray {
        if (windowS <= 0 || t.n < 3) return v
        val half = (windowS * 10 / 2).toInt()
        val out = DoubleArray(t.n)
        var lo = 0; var hi = 0; var sum = 0.0
        for (i in 0 until t.n) {
            while (hi < t.n && t.t[hi] <= t.t[i] + half) { sum += v[hi]; hi++ }
            while (t.t[lo] < t.t[i] - half) { sum -= v[lo]; lo++ }
            out[i] = sum / (hi - lo)
        }
        return out
    }

    /** 시간 창 안에서 실제 이동한 거리/시간으로 구한 속도(m/s) */
    fun speed(t: Track, cum: DoubleArray, windowS: Double): DoubleArray {
        val out = DoubleArray(t.n)
        if (windowS <= 0) { for (i in 0 until t.n) out[i] = t.spd[i] / 100.0; return out }
        val half = (windowS * 10 / 2).toInt()
        var lo = 0; var hi = 0
        for (i in 0 until t.n) {
            while (hi < t.n - 1 && t.t[hi + 1] <= t.t[i] + half) hi++
            while (t.t[lo] < t.t[i] - half) lo++
            if (hi < i) hi = i
            val dt = (t.t[hi] - t.t[lo]) / 10.0
            out[i] = if (dt > 0) (cum[hi] - cum[lo]) / dt else t.spd[i] / 100.0
        }
        return out
    }

    fun has(t: Track, m: Metric) = m != Metric.ELE_API || t.ele != null

    fun of(t: Track, xa: XAxis, m: Metric, smoothS: Double): Pts {
        val cum = cumDist(t)
        val xs = DoubleArray(t.n) { if (xa == XAxis.DISTANCE) cum[it] / 1000.0 else t.t[it] / 600.0 }
        val ys: DoubleArray = when (m) {
            Metric.SPEED -> speed(t, cum, smoothS).also { for (i in it.indices) it[i] *= 3.6 }
            Metric.PACE -> speed(t, cum, maxOf(smoothS, 5.0)).also {
                for (i in it.indices) it[i] = if (it[i] > 0.7) 1000.0 / it[i] / 60.0 else Double.NaN
            }
            Metric.ELE_API -> {
                val e = t.ele ?: IntArray(t.n)
                smooth(t, DoubleArray(t.n) { (e[it] - e[0]) / 10.0 }, smoothS)
            }
            Metric.ELE_GPS -> smooth(t, DoubleArray(t.n) { (t.alt[it] - t.alt[0]) / 10.0 }, maxOf(smoothS, 10.0))
            Metric.DIST -> DoubleArray(t.n) { cum[it] / 1000.0 }
        }
        return Pts(xs, ys)
    }

    /** 누적 상승고도(m). 1m 미만의 잔떨림은 무시. */
    fun ascent(t: Track): Double {
        val src = t.ele ?: t.alt
        if (t.n < 2) return 0.0
        val v = smooth(t, DoubleArray(t.n) { src[it] / 10.0 }, 30.0)
        var ref = v[0]; var up = 0.0
        for (i in 1 until t.n) {
            val d = v[i] - ref
            if (d >= 1.0) { up += d; ref = v[i] } else if (d <= -1.0) ref = v[i]
        }
        return up
    }

    fun meta(t: Track, mine: Boolean, old: TrackMeta? = null): TrackMeta = TrackMeta(
        id = t.id, ownerId = t.ownerId, ownerName = t.ownerName, startMs = t.startMs, durationS = t.durationS,
        distanceM = t.distanceM, ascentM = ascent(t), mine = mine, unread = old?.unread ?: !mine,
        groupIds = old?.groupIds ?: emptyList(), pending = old?.pending ?: emptyList(),
        eleSrc = if (t.ele != null) t.eleSrc else "", n = t.n, eleTries = old?.eleTries ?: 0,
    )
}

data class RankRow(val ownerId: String, val name: String, val distanceM: Double, val durationS: Long, val runs: Int, val ascentM: Double)

enum class RankBy(private val ko: String, private val enName: String) {
    DISTANCE("거리", "Distance"), TIME("시간", "Time"), RUNS("횟수", "Runs");
    val label: String get() = tr(ko, enName)
}

enum class Bucket(private val ko: String, private val enName: String) {
    RUN("달리기별", "Per run"), DAY("일별", "Daily"), WEEK("주별", "Weekly"), MONTH("월별", "Monthly");
    val label: String get() = tr(ko, enName)
}

object Ranking {
    /** groupId가 null이면 전체. 그룹 지정 시 그 그룹으로 주고받은 기록만 센다. */
    fun filter(metas: List<TrackMeta>, fromMs: Long, toMs: Long, groupId: String?): List<TrackMeta> =
        metas.filter { it.startMs in fromMs until toMs && (groupId == null || groupId in it.groupIds || groupId in it.pending) }

    fun names(metas: List<TrackMeta>, groups: List<Group>, me: Identity): Map<String, String> {
        val m = HashMap<String, String>()
        for (t in metas.sortedBy { it.startMs }) m[t.ownerId] = t.ownerName
        for (g in groups) for (mem in g.members.sortedBy { it.lastSeenMs }) m[mem.id] = mem.name
        m[me.id] = me.name
        return m
    }

    fun rank(metas: List<TrackMeta>, names: Map<String, String>, by: RankBy, alsoList: Collection<String> = emptyList()): List<RankRow> {
        val rows = metas.groupBy { it.ownerId }.map { (id, l) ->
            RankRow(id, names[id] ?: l.last().ownerName, l.sumOf { it.distanceM }, l.sumOf { it.durationS.toLong() }, l.size, l.sumOf { it.ascentM })
        }.toMutableList()
        for (id in alsoList) if (rows.none { it.ownerId == id }) rows.add(RankRow(id, names[id] ?: "?", 0.0, 0, 0, 0.0))
        return rows.sortedWith(
            when (by) {
                RankBy.DISTANCE -> compareByDescending<RankRow> { it.distanceM }
                RankBy.TIME -> compareByDescending { it.durationS }
                RankBy.RUNS -> compareByDescending { it.runs }
            }.thenByDescending { it.distanceM }.thenBy { it.name }
        )
    }
}

class TrendPoint(val bucketStartMs: Long, val label: String, val values: Map<String, Double>)

enum class TrendMetric(private val ko: String, private val enName: String, private val unitKo: String, private val unitEn: String) {
    DISTANCE("거리", "Distance", "km", "km"), TIME("시간", "Time", "분", "min"), PACE("평균 페이스", "Avg pace", "분/km", "min/km"),
    RUNS("횟수", "Runs", "회", "runs");
    val label: String get() = tr(ko, enName)
    val unit: String get() = tr(unitKo, unitEn)
}

object Trend {
    private fun bucketStart(ms: Long, b: Bucket, zone: java.time.ZoneId): Long {
        val d = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val s = when (b) {
            Bucket.RUN -> return ms
            Bucket.DAY -> d
            Bucket.WEEK -> d.minusDays((d.dayOfWeek.value - 1).toLong())
            Bucket.MONTH -> d.withDayOfMonth(1)
        }
        return s.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    private fun label(ms: Long, b: Bucket, zone: java.time.ZoneId): String {
        val d = java.time.Instant.ofEpochMilli(ms).atZone(zone)
        return when (b) {
            Bucket.MONTH -> "${d.year % 100}.${d.monthValue}"
            // 달리기별: 같은 날 여러 번 뛴 것을 구분할 수 있게 날짜 아래에 시각을 적는다
            Bucket.RUN -> "${d.monthValue}/${d.dayOfMonth}\n" + String.format("%02d:%02d", d.hour, d.minute)
            else -> "${d.monthValue}/${d.dayOfMonth}"
        }
    }

    /** 사람별(ownerId) 기간 묶음 값. 값이 없는 사람은 그 묶음에서 빠진다. */
    fun compute(metas: List<TrackMeta>, b: Bucket, m: TrendMetric, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): List<TrendPoint> {
        val out = ArrayList<TrendPoint>()
        val grouped = metas.sortedBy { it.startMs }.groupBy { if (b == Bucket.RUN) it.startMs else bucketStart(it.startMs, b, zone) }
        for ((start, list) in grouped.toSortedMap()) {
            val vals = list.groupBy { it.ownerId }.mapValues { (_, l) ->
                val dist = l.sumOf { it.distanceM }
                val dur = l.sumOf { it.durationS.toLong() }
                when (m) {
                    TrendMetric.DISTANCE -> dist / 1000.0
                    TrendMetric.TIME -> dur / 60.0
                    TrendMetric.PACE -> if (dist > 0) dur / 60.0 / (dist / 1000.0) else Double.NaN
                    TrendMetric.RUNS -> l.size.toDouble()
                }
            }.filterValues { it.isFinite() }
            if (vals.isNotEmpty()) out.add(TrendPoint(start, label(start, b, zone), vals))
        }
        return out
    }
}
