package app.runmate.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SyncResult(val ok: Boolean, val newTracks: List<TrackMeta>, val status: Map<String, Boolean>)

/**
 * 그룹 공유 엔진. 서버/로그인 없이 공개 릴레이에 암호화된 메시지를 올리고 받아온다.
 * relays[0]은 가벼운 주 경로(백그라운드에서도 사용), 나머지는 보관 기간이 긴 백업 경로.
 */
class SyncEngine(
    private val store: Store,
    private val relays: List<Relay>,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val mutex = Mutex()

    fun createGroup(name: String): Group = addGroup(name.trim().take(30).ifEmpty { "그룹" }, Crypto.randomBytes(32))!!

    /** 초대 코드로 참여. 형식이 틀리면 null, 이미 참여 중이면 기존 그룹. */
    fun joinGroup(inviteText: String): Group? {
        val (name, key) = Invite.decode(inviteText) ?: return null
        return addGroup(name, key)
    }

    private fun addGroup(name: String, key: ByteArray): Group? {
        val id = Crypto.topicOf(key)
        store.groups.value.firstOrNull { it.id == id }?.let { return it }
        val g = Group(id, name, Crypto.b64(key), now())
        store.updateGroups { it + g }
        enqueue(g.id, Envelope.HELLO, null)
        return g
    }

    fun leaveGroup(groupId: String) {
        // 나가기 알림은 최선 노력: 그룹 키가 지워지므로 미리 만들어 둘 수 없어 즉시 전송을 시도한다.
        store.updateOutbox { l -> l.filter { it.groupId != groupId } }
        store.updateGroups { l -> l.filter { it.id != groupId } }
        store.updateMetas { m ->
            if (groupId in m.groupIds || groupId in m.pending) m.copy(groupIds = m.groupIds - groupId, pending = m.pending - groupId) else m
        }
    }

    suspend fun announceLeave(g: Group) {
        val me = store.identity.value
        val env = Envelope(id = Crypto.randomHex(8), from = me.id, name = me.name, ts = now(), type = Envelope.LEAVE)
        val sealed = Crypto.seal(g.keyBytes(), J.encodeToString(Envelope.serializer(), env).toByteArray())
        for (r in relays) try { r.publish(g.topic(), env.id, sealed) } catch (e: Exception) {}
    }

    fun announceName() { for (g in store.groups.value) enqueue(g.id, Envelope.HELLO, null) }

    /** 내 트랙을 그룹들에 보내도록 대기열에 넣는다. */
    fun shareTrack(trackId: String, groupIds: List<String>) {
        val meta = store.meta(trackId) ?: return
        if (!meta.mine) return
        val valid = groupIds.filter { gid -> store.groups.value.any { it.id == gid } }.distinct()
        val fresh = valid.filter { it !in meta.pending }
        if (fresh.isEmpty()) return
        for (gid in fresh) enqueue(gid, Envelope.TRACK, trackId)
        store.updateMeta(trackId) { it.copy(pending = (it.pending + fresh).distinct()) }
    }

    private fun enqueue(groupId: String, type: String, trackId: String?) {
        store.updateOutbox { l ->
            // 같은 그룹의 이전 hello는 새 것으로 대체
            val base = if (type == Envelope.HELLO) l.filter { !(it.groupId == groupId && it.type == Envelope.HELLO) } else l
            base + OutboxItem(Crypto.randomHex(8), groupId, type, trackId, now())
        }
    }

    /** 대기 중인 메시지를 전송한다. 모두 끝났으면 true, 다시 시도할 것이 남았으면 false. */
    suspend fun flushOutbox(): Boolean = mutex.withLock {
        val me = store.identity.value
        for (item in store.outbox.value) {
            val g = store.groups.value.firstOrNull { it.id == item.groupId }
            if (g == null || now() - item.createdMs > MAX_AGE_MS) { drop(item, failed = true); continue }
            val track = if (item.type == Envelope.TRACK) {
                val t = item.trackId?.let { store.loadTrack(it) }
                if (t == null) { drop(item, failed = true); continue }
                t.forSharing().copy(ownerName = me.name).toDto()
            } else null
            val env = Envelope(id = item.msgId, from = me.id, name = me.name, ts = item.createdMs, type = item.type, track = track)
            val sealed = Crypto.seal(g.keyBytes(), J.encodeToString(Envelope.serializer(), env).toByteArray())
            var done = item.done
            for (r in relays) {
                if (r.name in done) continue
                val ok = try { r.publish(g.topic(), item.msgId, sealed) } catch (e: Exception) { false }
                if (ok) {
                    done = done + r.name
                    store.updateOutbox { l -> l.map { if (it.msgId == item.msgId) it.copy(done = done) else it } }
                    if (item.trackId != null) store.updateMeta(item.trackId) {
                        it.copy(groupIds = (it.groupIds + g.id).distinct(), pending = it.pending - g.id)
                    }
                }
            }
            val allDone = relays.all { it.name in done }
            // 한 경로라도 성공했다면 나머지 경로는 일정 시간만 재시도한다.
            if (allDone || (done.isNotEmpty() && now() - item.createdMs > SECONDARY_RETRY_MS)) drop(item, failed = false)
        }
        store.outbox.value.isEmpty()
    }

    private fun drop(item: OutboxItem, failed: Boolean) {
        store.updateOutbox { l -> l.filter { it.msgId != item.msgId } }
        if (failed && item.trackId != null) store.updateMeta(item.trackId) { it.copy(pending = it.pending - item.groupId) }
    }

    /** 새 메시지를 받아온다. fast=true면 주 경로만 사용(백그라운드용). */
    suspend fun sync(fast: Boolean = false): SyncResult = mutex.withLock {
        val groups = store.groups.value
        if (groups.isEmpty()) return@withLock SyncResult(true, emptyList(), emptyMap())
        val newTracks = ArrayList<TrackMeta>()
        val status = LinkedHashMap<String, Boolean>()
        val byTopic = groups.associateBy { it.topic() }
        for (r in if (fast) relays.take(1) else relays) {
            val started = now() / 1000
            val sinces = groups.map { it.since[r.name] ?: 0L }
            val since = if (sinces.any { it <= 0L }) 0L else (sinces.min() - OVERLAP_S).coerceAtLeast(1)
            val got = try { r.fetch(byTopic.keys.toList(), since) } catch (e: Exception) { null }
            status[r.name] = got != null
            if (got == null) continue
            for (inc in got) {
                val g = byTopic[inc.topic] ?: continue
                try { process(g, inc.sealed)?.let { newTracks.add(it) } } catch (e: Exception) { /* 잘못된 메시지는 무시 */ }
            }
            val ids = groups.map { it.id }.toSet()
            store.updateGroups { l -> l.map { if (it.id in ids) it.copy(since = it.since + (r.name to started)) else it } }
        }
        store.persistSeen()
        SyncResult(status.values.any { it }, newTracks, status)
    }

    private fun process(g: Group, sealed: ByteArray): TrackMeta? {
        val plain = Crypto.open(g.keyBytes(), sealed) ?: return null
        val env = J.decodeFromString(Envelope.serializer(), String(plain, Charsets.UTF_8))
        val me = store.identity.value
        if (env.v != 1 || env.id.isBlank() || env.from.isBlank() || env.from.length > 64) return null
        if (!store.markSeen(g.id + ":" + env.id)) return null
        if (env.from == me.id) return null
        val name = env.name.trim().take(20).ifEmpty { "이름없음" }
        val ts = env.ts.coerceAtMost(now() + 3600_000)

        if (env.type == Envelope.LEAVE) {
            store.updateGroup(g.id) { gg ->
                val cur = gg.members.firstOrNull { it.id == env.from }
                if (cur != null && cur.lastSeenMs <= ts) gg.copy(members = gg.members.filter { it.id != env.from }) else gg
            }
            return null
        }
        store.updateGroup(g.id) { gg ->
            val cur = gg.members.firstOrNull { it.id == env.from }
            when {
                cur == null -> if (gg.members.size >= MAX_MEMBERS) gg else gg.copy(members = gg.members + Member(env.from, name, ts))
                cur.lastSeenMs <= ts -> gg.copy(members = gg.members.map { if (it.id == env.from) Member(env.from, name, ts) else it })
                else -> gg
            }
        }
        if (env.type != Envelope.TRACK) return null
        val t = Track.fromDto(env.track ?: return null) ?: return null
        if (t.ownerId != env.from) return null
        val existing = store.meta(t.id)
        if (existing != null) {
            // 같은 기록이 다른 그룹으로도 온 경우: 그룹만 추가
            if (existing.ownerId == t.ownerId && !existing.mine && g.id !in existing.groupIds)
                store.updateMeta(t.id) { it.copy(groupIds = it.groupIds + g.id) }
            return null
        }
        val clean = t.copy(ownerName = name, oLat = null, oLon = null)
        var meta: TrackMeta? = null
        store.saveTrack(clean) { Series.meta(clean, mine = false).copy(unread = true, groupIds = listOf(g.id)).also { meta = it } }
        return meta
    }

    companion object {
        const val OVERLAP_S = 1800L
        const val MAX_AGE_MS = 7L * 86400_000
        const val SECONDARY_RETRY_MS = 6L * 3600_000
        const val MAX_MEMBERS = 200
    }
}
