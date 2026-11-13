package app.runmate.core

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import kotlin.math.hypot
import kotlin.math.roundToInt

/** GPS 한 점. tMs는 단조 증가 시계(부팅 후 경과 ms) 기준. */
data class Fix(
    val tMs: Long,
    val lat: Double,
    val lon: Double,
    val alt: Double?,
    val accuracy: Float,
    val speed: Float?,
)

data class RunSnapshot(
    val running: Boolean = false,
    val startElapsedMs: Long = 0,
    val startWallMs: Long = 0,
    val hasFix: Boolean = false,
    val distanceM: Double = 0.0,
    val speed: Double = 0.0,
    val relAltM: Double = 0.0,
    val accuracy: Float = -1f,
    val gpsOff: Boolean = false,
    /** 실시간 경로 표시용 (m 단위, 최대 PLOT_POINTS개로 솎음) */
    val px: FloatArray = FloatArray(0),
    val py: FloatArray = FloatArray(0),
)

/**
 * 순수 로직: GPS 점을 걸러서 트랙을 만든다. Android 의존성 없음.
 * - 정확도가 나쁜 점, 순간이동(비정상 속도) 점은 버린다.
 * - 제자리에서의 GPS 떨림이 거리로 누적되지 않도록 최소 이동거리를 둔다.
 */
class RunRecorder(
    val id: String,
    val startElapsedMs: Long,
    val startWallMs: Long,
    private val journal: File? = null,
) {
    private var lat0 = 0.0
    private var lon0 = 0.0
    var hasOrigin = false; private set

    private var n = 0
    private var t = IntArray(1024)
    private var x = IntArray(1024)
    private var y = IntArray(1024)
    private var alt = IntArray(1024)
    private var spd = IntArray(1024)

    var distanceM = 0.0; private set
    var currentSpeed = 0.0; private set
    var lastAccuracy = -1f; private set
    private var lastFixMs = Long.MIN_VALUE
    private var lastX = 0.0
    private var lastY = 0.0
    private var lastAcceptMs = 0L
    private var lastAltDm = 0
    private var rejects = 0
    private var writer: BufferedWriter? = null
    private var unflushed = 0

    val size: Int get() = n

    fun onFix(f: Fix): Boolean {
        if (f.lat.isNaN() || f.lon.isNaN() || f.lat < -90 || f.lat > 90 || f.lon < -180 || f.lon > 180) return false
        if (f.tMs <= lastFixMs) return false
        lastAccuracy = f.accuracy
        if (f.accuracy <= 0f || f.accuracy > MAX_ACCURACY_M) return false
        if (f.tMs < startElapsedMs) return false
        lastFixMs = f.tMs

        if (!hasOrigin) {
            lat0 = f.lat; lon0 = f.lon; hasOrigin = true
            lastAltDm = 0
            altBase = f.alt
            add(f.tMs, 0.0, 0.0, 0, f.speed?.toDouble()?.takeIf { it in 0.0..MAX_SPEED } ?: 0.0)
            openJournal()
            return true
        }

        val (px, py) = Geo.project(lat0, lon0, f.lat, f.lon)
        val d = hypot(px - lastX, py - lastY)
        val dt = (f.tMs - lastAcceptMs) / 1000.0
        if (dt <= 0) return false
        val v = d / dt
        if (v > MAX_SPEED && rejects < 5) { rejects++; return false }
        rejects = 0

        val gpsSpeed = f.speed?.toDouble()?.takeIf { it in 0.0..MAX_SPEED }
        val inst = gpsSpeed ?: v
        currentSpeed = if (currentSpeed == 0.0) inst else currentSpeed * 0.7 + inst * 0.3

        val altDm = altBase?.let { b -> f.alt?.let { ((it - b) * 10).roundToInt().coerceIn(-100_000, 100_000) } } ?: lastAltDm
        if (altBase == null && f.alt != null) altBase = f.alt
        val minMove = maxOf(MIN_MOVE_M, minOf(f.accuracy.toDouble(), 12.0) * 0.35)
        if (d >= minMove) {
            distanceM += d
            add(f.tMs, px, py, altDm, inst)
            return true
        }
        if (dt >= STILL_INTERVAL_S) {
            // 멈춰 있는 동안에도 시간축이 끊기지 않도록 같은 위치에 점을 남긴다.
            currentSpeed = 0.0
            add(f.tMs, lastX, lastY, altDm, 0.0)
            return true
        }
        return false
    }

    private var altBase: Double? = null

    private fun add(tMs: Long, px: Double, py: Double, altDm: Int, speed: Double) {
        if (n >= Track.MAX_POINTS) return
        if (n == t.size) {
            val c = n * 2
            t = t.copyOf(c); x = x.copyOf(c); y = y.copyOf(c); alt = alt.copyOf(c); spd = spd.copyOf(c)
        }
        t[n] = ((tMs - startElapsedMs) / 100).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        x[n] = (px * 10).roundToInt(); y[n] = (py * 10).roundToInt()
        alt[n] = altDm; spd[n] = (speed * 100).roundToInt()
        lastX = px; lastY = py; lastAcceptMs = tMs; lastAltDm = altDm
        writer?.let { w ->
            try {
                w.write("${t[n]},${x[n]},${y[n]},${alt[n]},${spd[n]}\n")
                if (++unflushed >= FLUSH_EVERY) { w.flush(); unflushed = 0 }
            } catch (e: Exception) { /* 저널 실패는 기록 자체를 막지 않는다 */ }
        }
        n++
    }

    private fun openJournal() {
        val f = journal ?: return
        try {
            f.parentFile?.mkdirs()
            val w = BufferedWriter(FileWriter(f, false))
            w.write("RM1,$id,$startWallMs,$lat0,$lon0\n")
            w.write("0,0,0,0,${spd[0]}\n")
            w.flush()
            writer = w
        } catch (e: Exception) { writer = null }
    }

    fun flush() { try { writer?.flush(); unflushed = 0 } catch (e: Exception) {} }

    fun snapshot(gpsOff: Boolean = false): RunSnapshot {
        val step = maxOf(1, (n + PLOT_POINTS - 1) / PLOT_POINTS)
        val m = if (n == 0) 0 else (n - 1) / step + 1 + (if ((n - 1) % step != 0) 1 else 0)
        val px = FloatArray(m); val py = FloatArray(m)
        var k = 0
        var i = 0
        while (i < n) { px[k] = x[i] / 10f; py[k] = y[i] / 10f; k++; i += step }
        if (n > 0 && (n - 1) % step != 0) { px[k] = x[n - 1] / 10f; py[k] = y[n - 1] / 10f }
        return RunSnapshot(
            running = true, startElapsedMs = startElapsedMs, startWallMs = startWallMs, hasFix = hasOrigin,
            distanceM = distanceM, speed = currentSpeed, relAltM = lastAltDm / 10.0,
            accuracy = lastAccuracy, gpsOff = gpsOff, px = px, py = py,
        )
    }

    /** 기록 종료. 점이 2개 미만이면 null. */
    fun finish(endElapsedMs: Long, ownerId: String, ownerName: String): Track? {
        try { writer?.close() } catch (e: Exception) {}
        writer = null
        if (n < 2 || distanceM < MIN_TRACK_M) return null
        val dur = ((endElapsedMs - startElapsedMs) / 1000).coerceIn(1, 7L * 86400).toInt()
        return Track(
            id, ownerId, ownerName, startWallMs, maxOf(dur, t[n - 1] / 10), distanceM,
            t.copyOf(n), x.copyOf(n), y.copyOf(n), alt.copyOf(n), spd.copyOf(n),
            oLat = lat0, oLon = lon0,
        )
    }

    companion object {
        const val MAX_ACCURACY_M = 30f
        const val MAX_SPEED = 12.5
        const val MIN_MOVE_M = 2.0
        const val STILL_INTERVAL_S = 10.0
        const val MIN_TRACK_M = 10.0
        const val PLOT_POINTS = 400
        const val FLUSH_EVERY = 10

        /** 앱이 강제 종료된 경우 저널 파일에서 트랙을 복구한다. */
        fun recover(journal: File, ownerId: String, ownerName: String): Track? = try {
            val lines = journal.readLines()
            val head = lines.firstOrNull()?.split(',')
            if (head == null || head.size != 5 || head[0] != "RM1") null
            else {
                val rows = lines.drop(1).mapNotNull { l ->
                    val p = l.split(',')
                    if (p.size != 5) null else try { IntArray(5) { p[it].toInt() } } catch (e: Exception) { null }
                }
                if (rows.size < 2) null
                else {
                    var dist = 0.0
                    for (i in 1 until rows.size)
                        dist += hypot((rows[i][1] - rows[i - 1][1]) / 10.0, (rows[i][2] - rows[i - 1][2]) / 10.0)
                    if (dist < MIN_TRACK_M) null
                    else Track.fromDto(
                        Track(
                            head[1], ownerId, ownerName, head[2].toLong(), maxOf(1, rows.last()[0] / 10), dist,
                            IntArray(rows.size) { rows[it][0] }, IntArray(rows.size) { rows[it][1] },
                            IntArray(rows.size) { rows[it][2] }, IntArray(rows.size) { rows[it][3] },
                            IntArray(rows.size) { rows[it][4] },
                            oLat = head[3].toDouble(), oLon = head[4].toDouble(),
                        ).toDto()
                    )
                }
            }
        } catch (e: Exception) { null }
    }
}
