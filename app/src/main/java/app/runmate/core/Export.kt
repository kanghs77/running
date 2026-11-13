package app.runmate.core

import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 기록 내보내기: 사람이 읽을 수 있는 CSV, 다른 운동 앱에서 쓰는 GPX, 전체 백업 ZIP */
object Export {
    private fun f(v: Double, d: Int = 1) = String.format(Locale.US, "%.${d}f", v)
    private fun stamp(ms: Long, zone: ZoneId) = Instant.ofEpochMilli(ms).atZone(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    private fun clean(s: String) = s.replace(Regex("[^0-9A-Za-z가-힣_-]"), "_").take(20).ifEmpty { "x" }
    private fun csvCell(s: String) = "\"" + s.replace("\"", "\"\"") + "\""

    fun fileBase(m: TrackMeta, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(m.startMs).atZone(zone).format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm")) + "_" + clean(if (m.mine) "me" else m.ownerName)

    /** 한 기록의 모든 점. x/y는 시작점 기준 동/북 방향 m. */
    fun csv(t: Track): String {
        val cum = Series.cumDist(t)
        val sb = StringBuilder("time_s,x_east_m,y_north_m,distance_m,speed_mps,rel_alt_gps_m,rel_ele_api_m\n")
        for (i in 0 until t.n) {
            sb.append(f(t.t[i] / 10.0)).append(',').append(f(t.x[i] / 10.0)).append(',').append(f(t.y[i] / 10.0)).append(',')
                .append(f(cum[i])).append(',').append(f(t.spd[i] / 100.0, 2)).append(',').append(f((t.alt[i] - t.alt[0]) / 10.0)).append(',')
                .append(t.ele?.let { f((it[i] - it[0]) / 10.0) } ?: "").append('\n')
        }
        return sb.toString()
    }

    /** 위경도가 있는(내가 달린) 기록만 가능. 없으면 null. */
    fun gpx(t: Track): String? {
        val lat0 = t.oLat ?: return null
        val lon0 = t.oLon ?: return null
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"RunMate\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n<trk><name>RunMate</name><trkseg>\n")
        for (i in 0 until t.n) {
            val (lat, lon) = Geo.unproject(lat0, lon0, t.x[i] / 10.0, t.y[i] / 10.0)
            sb.append("<trkpt lat=\"").append(f(lat, 7)).append("\" lon=\"").append(f(lon, 7)).append("\">")
            t.ele?.let { sb.append("<ele>").append(f(it[i] / 10.0)).append("</ele>") }
            sb.append("<time>").append(Instant.ofEpochMilli(t.startMs + t.t[i] * 100L).toString()).append("</time></trkpt>\n")
        }
        return sb.append("</trkseg></trk>\n</gpx>\n").toString()
    }

    fun summaryCsv(metas: List<TrackMeta>, zone: ZoneId = ZoneId.systemDefault()): String {
        val sb = StringBuilder("﻿날짜,이름,내 기록,거리(km),시간,평균 페이스(분/km),상승(m),파일\n")
        for (m in metas.sortedBy { it.startMs }) {
            sb.append(stamp(m.startMs, zone)).append(',').append(csvCell(m.ownerName)).append(',').append(if (m.mine) "Y" else "N").append(',')
                .append(f(m.distanceM / 1000, 2)).append(',').append(Fmt.duration(m.durationS)).append(',')
                .append(csvCell(Fmt.pace(m.avgSpeed))).append(',').append(f(m.ascentM, 0)).append(',').append(fileBase(m, zone)).append('_').append(clean(m.id).take(8)).append(".csv\n")
        }
        return sb.toString()
    }

    /** 전체 기록 ZIP: 요약.csv + 기록별 CSV + (내 기록은) GPX + 원본 JSON. 내보낸 기록 수를 돌려준다. */
    fun zip(out: OutputStream, metas: List<TrackMeta>, zone: ZoneId = ZoneId.systemDefault(), load: (String) -> Track?): Int {
        var n = 0
        ZipOutputStream(out).use { z ->
            fun put(name: String, text: String) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray(Charsets.UTF_8)); z.closeEntry() }
            put("summary.csv", summaryCsv(metas, zone))
            for (m in metas.sortedBy { it.startMs }) {
                val t = load(m.id) ?: continue
                val base = fileBase(m, zone) + "_" + clean(m.id).take(8)
                put("tracks/$base.csv", csv(t))
                gpx(t)?.let { put("gpx/$base.gpx", it) }
                put("json/$base.json", J.encodeToString(TrackDto.serializer(), t.toDto()))
                n++
            }
        }
        return n
    }
}
