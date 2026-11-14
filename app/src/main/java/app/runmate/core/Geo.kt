package app.runmate.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    const val R = 6_371_000.0
    private const val RAD = PI / 180.0

    /** 시작점 기준 (동쪽 m, 북쪽 m) */
    fun project(lat0: Double, lon0: Double, lat: Double, lon: Double): Pair<Double, Double> {
        var dLon = lon - lon0
        if (dLon > 180) dLon -= 360 else if (dLon < -180) dLon += 360
        return (R * dLon * RAD * cos(lat0 * RAD)) to (R * (lat - lat0) * RAD)
    }

    fun unproject(lat0: Double, lon0: Double, x: Double, y: Double): Pair<Double, Double> {
        val c = cos(lat0 * RAD)
        val lat = lat0 + y / R / RAD
        val lon = if (abs(c) < 1e-9) lon0 else lon0 + x / (R * c) / RAD
        return lat to lon
    }

    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = (lat2 - lat1) * RAD
        val dLon = (lon2 - lon1) * RAD
        val a = sin(dLat / 2).pow(2) + cos(lat1 * RAD) * cos(lat2 * RAD) * sin(dLon / 2).pow(2)
        return 2 * R * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** max 이하의 1/2/5 x 10^n 중 가장 큰 값 (축척 막대 길이) */
    fun niceFloor(max: Double): Double {
        if (!(max > 0) || !max.isFinite()) return 1.0
        val e = 10.0.pow(floor(log10(max)))
        val f = max / e
        return when { f >= 5 -> 5 * e; f >= 2 -> 2 * e; else -> e }
    }

    /** 축 눈금 간격 */
    fun niceStep(range: Double, target: Int = 5): Double {
        if (!(range > 0) || !range.isFinite()) return 1.0
        val raw = range / target
        val e = 10.0.pow(floor(log10(raw)))
        val f = raw / e
        return when { f <= 1 -> e; f <= 2 -> 2 * e; f <= 5 -> 5 * e; else -> 10 * e }
    }

    fun ticks(min: Double, max: Double, target: Int = 5): List<Double> {
        if (!(max > min)) return listOf(min)
        val step = niceStep(max - min, target)
        val out = ArrayList<Double>()
        var v = ceil(min / step - 1e-9) * step
        var guard = 0
        while (v <= max + step * 1e-6 && guard++ < 100) { out.add(if (abs(v) < step * 1e-9) 0.0 else v); v += step }
        return out
    }
}

object Fmt {
    fun km(m: Double): String = String.format("%.2f", m / 1000.0)
    fun duration(s: Int): String {
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%02d:%02d", m, sec)
    }
    /** m/s -> "5'30\"" (분/km) */
    fun pace(speed: Double): String {
        if (!(speed > 0.3)) return "--'--\""
        val secPerKm = (1000.0 / speed).toInt()
        return String.format("%d'%02d\"", secPerKm / 60, secPerKm % 60)
    }
    fun paceMin(minPerKm: Double): String {
        if (!minPerKm.isFinite() || minPerKm <= 0) return "-"
        val s = Math.round(minPerKm * 60).toInt()
        return String.format("%d'%02d\"", s / 60, s % 60)
    }
    fun meters(m: Double): String = if (m >= 1000) String.format("%.1fkm", m / 1000) else String.format("%.0fm", m)
    fun num(v: Double): String =
        if (abs(v) >= 100 || v == Math.rint(v)) String.format("%.0f", v) else if (abs(v) >= 10) String.format("%.1f", v) else String.format("%.2f", v).trimEnd('0').trimEnd('.')
}

/** 웹 메르카토르(지도 타일) 좌표. 세계 전체가 0~1. */
object Mercator {
    fun x(lon: Double): Double = (lon + 180.0) / 360.0
    fun y(lat: Double): Double {
        val r = Math.toRadians(lat.coerceIn(-85.05, 85.05))
        return (1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / PI) / 2
    }
    /** fit: 화면 px / 세계 단위. 타일 1px이 화면에서 density*0.8px 안팎이 되도록 줌 선택 */
    fun zoomFor(fit: Double, density: Float): Int {
        if (!(fit > 0)) return 2
        return Math.round(Math.log(fit / (256.0 * density * 0.8)) / Math.log(2.0)).toInt().coerceIn(2, 19)
    }
    /** 해당 위도에서 세계 단위 1이 몇 m인지 */
    fun metersPerUnit(lat: Double): Double = 40_075_016.686 * cos(Math.toRadians(lat))
}
