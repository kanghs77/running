package app.runmate.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val J = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

@Serializable
data class Identity(val id: String, val name: String, /** 그룹장 서명·키 교환용 비밀키(hex). 휴대폰 밖으로 나가지 않는다. */ val sk: String = "")

@Serializable
data class Settings(val notifications: Boolean = true, val nameSet: Boolean = false, val askedNotif: Boolean = false, val mapMode: Boolean = true, /** ""=휴대폰 설정 따름, "ko", "en" */ val lang: String = "")

@Serializable
data class Member(val id: String, val name: String, val lastSeenMs: Long, val pub: String = "")

@Serializable
data class Group(
    val id: String,
    val name: String,
    val key: String,
    val createdMs: Long,
    val muted: Boolean = false,
    val members: List<Member> = emptyList(),
    /** 릴레이 이름 -> 마지막으로 성공한 동기화 시각(초) */
    val since: Map<String, Long> = emptyMap(),
    /** 그룹을 만든 사람(그룹장)의 공개키. 그룹원 내보내기는 그룹장만 할 수 있다. */
    val adminPub: String = "",
) {
    fun keyBytes(): ByteArray = Crypto.unb64(key)
    fun topic(): String = id
}

@Serializable
data class TrackMeta(
    val id: String,
    val ownerId: String,
    val ownerName: String,
    val startMs: Long,
    val durationS: Int,
    val distanceM: Double,
    val ascentM: Double = 0.0,
    val mine: Boolean,
    val unread: Boolean = false,
    /** 받은 기록: 전달받은 그룹 / 내 기록: 전송 완료된 그룹 */
    val groupIds: List<String> = emptyList(),
    /** 내 기록: 전송 대기 중인 그룹 */
    val pending: List<String> = emptyList(),
    val eleSrc: String = "",
    val n: Int = 0,
    val eleTries: Int = 0,
) {
    val avgSpeed: Double get() = if (durationS > 0) distanceM / durationS else 0.0
}

/** 저장/전송용 표현. 배열은 모두 델타 인코딩. */
@Serializable
data class TrackDto(
    val id: String,
    val ownerId: String,
    val ownerName: String,
    val startMs: Long,
    val durationS: Int,
    val distanceM: Double,
    val t: List<Int>,
    val x: List<Int>,
    val y: List<Int>,
    val alt: List<Int>,
    val spd: List<Int>,
    val ele: List<Int>? = null,
    val eleSrc: String = "",
    val oLat: Double? = null,
    val oLon: Double? = null,
)

/**
 * 메모리 상의 트랙.
 * t: 시작 후 1/10초, x/y: 시작점 기준 동/북 방향 dm, alt: GPS 고도 dm, ele: API 고도 dm, spd: cm/s
 */
class Track(
    val id: String,
    val ownerId: String,
    val ownerName: String,
    val startMs: Long,
    val durationS: Int,
    val distanceM: Double,
    val t: IntArray,
    val x: IntArray,
    val y: IntArray,
    val alt: IntArray,
    val spd: IntArray,
    val ele: IntArray? = null,
    val eleSrc: String = "",
    val oLat: Double? = null,
    val oLon: Double? = null,
) {
    val n: Int get() = t.size

    fun copy(
        ownerName: String = this.ownerName,
        ele: IntArray? = this.ele,
        eleSrc: String = this.eleSrc,
        oLat: Double? = this.oLat,
        oLon: Double? = this.oLon,
    ) = Track(id, ownerId, ownerName, startMs, durationS, distanceM, t, x, y, alt, spd, ele, eleSrc, oLat, oLon)

    fun toDto(): TrackDto = TrackDto(
        id, ownerId, ownerName, startMs, durationS, distanceM,
        delta(t), delta(x), delta(y), delta(alt), delta(spd), ele?.let { delta(it) }, eleSrc, oLat, oLon,
    )

    /** endIdx번째 점까지만 남긴 기록(끝부분 잘라내기). 남는 점이 2개 미만이면 null. */
    fun trimmed(endIdx: Int): Track? {
        val m = endIdx + 1
        if (endIdx < 1 || m > n) return null
        if (m == n) return this
        var dist = 0.0
        for (i in 1 until m) dist += Math.hypot((x[i] - x[i - 1]) / 10.0, (y[i] - y[i - 1]) / 10.0)
        return Track(
            id, ownerId, ownerName, startMs, maxOf(1, t[endIdx] / 10), dist,
            t.copyOf(m), x.copyOf(m), y.copyOf(m), alt.copyOf(m), spd.copyOf(m), ele?.copyOf(m), eleSrc, oLat, oLon,
        )
    }

    /** 공유용: 절대 위치(위경도)를 제거하고 점 개수를 제한한다. */
    fun forSharing(maxPoints: Int = MAX_SHARE_POINTS): Track {
        if (n <= maxPoints) return copy(oLat = null, oLon = null)
        val idx = IntArray(maxPoints) { i -> ((i.toLong() * (n - 1)) / (maxPoints - 1)).toInt() }
        fun pick(a: IntArray) = IntArray(maxPoints) { a[idx[it]] }
        return Track(
            id, ownerId, ownerName, startMs, durationS, distanceM,
            pick(t), pick(x), pick(y), pick(alt), pick(spd), ele?.let { pick(it) }, eleSrc, null, null,
        )
    }

    companion object {
        const val MAX_SHARE_POINTS = 2500
        const val MAX_POINTS = 100_000

        fun delta(a: IntArray): List<Int> {
            val out = ArrayList<Int>(a.size)
            var prev = 0
            for (v in a) { out.add(v - prev); prev = v }
            return out
        }

        fun undelta(l: List<Int>): IntArray {
            val out = IntArray(l.size)
            var acc = 0L
            for (i in l.indices) {
                acc += l[i]
                if (acc > Int.MAX_VALUE || acc < Int.MIN_VALUE) throw IllegalArgumentException("overflow")
                out[i] = acc.toInt()
            }
            return out
        }

        /** 형식이 잘못되었으면 null. 외부에서 받은 데이터도 여기서 검증한다. */
        fun fromDto(d: TrackDto): Track? = try {
            val n = d.t.size
            if (n < 1 || n > MAX_POINTS) null
            else if (d.x.size != n || d.y.size != n || d.alt.size != n || d.spd.size != n) null
            else if (d.ele != null && d.ele.size != n) null
            else if (d.id.isBlank() || d.id.length > 64 || d.ownerId.isBlank() || d.ownerId.length > 64) null
            else if (d.durationS < 0 || d.durationS > 7 * 86400) null
            else if (!d.distanceM.isFinite() || d.distanceM < 0 || d.distanceM > 1_000_000) null
            else {
                val t = undelta(d.t)
                var ok = true
                for (i in 1 until n) if (t[i] < t[i - 1]) { ok = false; break }
                if (!ok || t[0] < 0) null
                else Track(
                    d.id, d.ownerId, d.ownerName.take(40), d.startMs, d.durationS, d.distanceM,
                    t, undelta(d.x), undelta(d.y), undelta(d.alt), undelta(d.spd), d.ele?.let { undelta(it) },
                    d.eleSrc.take(20), d.oLat, d.oLon,
                )
            }
        } catch (e: Exception) { null }
    }
}

@Serializable
data class OutboxItem(
    val msgId: String,
    val groupId: String,
    val type: String,
    val trackId: String? = null,
    val createdMs: Long,
    val done: List<String> = emptyList(),
    /** 그룹이 이미 바뀌었거나 없어진 뒤에도 보낼 수 있도록 내용/키를 직접 담은 항목 */
    val topic: String? = null,
    val key: String? = null,
    val body: String? = null,
)

@Serializable
data class Envelope(
    val v: Int = 1,
    val id: String,
    val from: String,
    val name: String,
    val ts: Long,
    val type: String,
    val track: TrackDto? = null,
    val pub: String? = null,
    val rekey: Rekey? = null,
) {
    companion object {
        const val REKEY = "rekey"
        const val HELLO = "hello"
        const val LEAVE = "leave"
        const val TRACK = "track"
    }
}

@Serializable
data class RekeyEntry(val to: String, val box: String)

/** 그룹원 내보내기: 그룹장이 새 그룹 키를 남는 사람 각각에게만 암호화해 전달하고 서명한다. */
@Serializable
data class Rekey(val removed: String, val entries: List<RekeyEntry>, val sig: String) {
    companion object {
        fun digest(oldGroupId: String, removed: String, entries: List<RekeyEntry>): ByteArray =
            Crypto.sha256(("runmate-rekey-v1|$oldGroupId|$removed|" + entries.joinToString(",") { it.to + ":" + it.box }).toByteArray())
    }
}
