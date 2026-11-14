package app.runmate.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SyncResult(val ok: Boolean, val newTracks: List<TrackMeta>, val status: Map<String, Boolean>, val notices: List<String> = emptyList())

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

    fun createGroup(name: String): Group = addGroup(name.trim().take(30).ifEmpty { tr("그룹", "Group") }, Crypto.randomBytes(32), store.myPub)!!

    fun inviteFor(g: Group): String = Invite.encode(g.name, g.keyBytes(), g.adminPub)

    fun isAdmin(g: Group): Boolean = g.adminPub.isNotEmpty() && g.adminPub == store.myPub

    /** 초대 코드로 참여. 형식이 틀리면 null, 이미 참여 중이면 기존 그룹. */
    fun joinGroup(inviteText: String): Group? {
        val d = Invite.decode(inviteText) ?: return null
        return addGroup(d.name, d.key, d.adminPub)
    }

    private fun addGroup(name: String, key: ByteArray, adminPub: String): Group? {
        val id = Crypto.topicOf(key)
        store.groups.value.firstOrNull { it.id == id }?.let { return it }
        val g = Group(id, name, Crypto.b64(key), now(), adminPub = adminPub)
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
        val env = Envelope(id = Crypto.randomHex(8), from = me.id, name = me.name, ts = now(), type = Envelope.LEAVE, pub = store.myPub)
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
            if (now() - item.createdMs > MAX_AGE_MS) { drop(item, failed = true); continue }
            val topic: String
            val sealed: ByteArray
            if (item.topic != null && item.key != null && item.body != null) {
                topic = item.topic
                sealed = Crypto.seal(Crypto.unb64(item.key), item.body.toByteArray())
            } else {
                val g = store.groups.value.firstOrNull { it.id == item.groupId }
                if (g == null) { drop(item, failed = true); continue }
                val track = if (item.type == Envelope.TRACK) {
                    val t = item.trackId?.let { store.loadTrack(it) }
                    if (t == null) { drop(item, failed = true); continue }
                    t.forSharing().copy(ownerName = me.name).toDto()
                } else null
                val env = Envelope(id = item.msgId, from = me.id, name = me.name, ts = item.createdMs, type = item.type, track = track, pub = store.myPub)
                topic = g.topic()
                sealed = Crypto.seal(g.keyBytes(), J.encodeToString(Envelope.serializer(), env).toByteArray())
            }
            var done = item.done
            for (r in relays) {
                if (r.name in done) continue
                val ok = try { r.publish(topic, item.msgId, sealed) } catch (e: Exception) { false }
                if (ok) {
                    done = done + r.name
                    store.updateOutbox { l -> l.map { if (it.msgId == item.msgId) it.copy(done = done) else it } }
                    if (item.trackId != null) store.updateMeta(item.trackId) {
                        it.copy(groupIds = (it.groupIds + item.groupId).distinct(), pending = it.pending - item.groupId)
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
        if (store.groups.value.isEmpty()) return@withLock SyncResult(true, emptyList(), emptyMap())
        val newTracks = ArrayList<TrackMeta>()
        notices.clear()
        val status = LinkedHashMap<String, Boolean>()
        for (r in if (fast) relays.take(1) else relays) {
            val groups = store.groups.value          // 앞 경로 처리 중 그룹이 바뀌었을 수 있다
            val byTopic = groups.associateBy { it.topic() }
            val started = now() / 1000
            val sinces = groups.map { it.since[r.name] ?: 0L }
            val since = if (sinces.any { it <= 0L }) 0L else (sinces.min() - OVERLAP_S).coerceAtLeast(1)
            val got = try { r.fetch(byTopic.keys.toList(), since) } catch (e: Exception) { null }
            status[r.name] = got != null
            if (got == null) continue
            for (inc in got) {
                // 처리 도중 그룹이 바뀌었을 수 있으므로(내보내기) 현재 목록에서 다시 찾는다
                val g = store.groups.value.firstOrNull { it.id == inc.topic } ?: continue
                try { process(g, inc.sealed)?.let { newTracks.add(it) } } catch (e: Exception) { /* 잘못된 메시지는 무시 */ }
            }
            val ids = groups.map { it.id }.toSet()
            store.updateGroups { l -> l.map { if (it.id in ids) it.copy(since = it.since + (r.name to started)) else it } }
        }
        store.persistSeen()
        SyncResult(status.values.any { it }, newTracks, status, notices.toList())
    }

    private fun process(g: Group, sealed: ByteArray): TrackMeta? {
        val plain = Crypto.open(g.keyBytes(), sealed) ?: return null
        val env = J.decodeFromString(Envelope.serializer(), String(plain, Charsets.UTF_8))
        val me = store.identity.value
        if (env.v != 1 || env.id.isBlank() || env.from.isBlank() || env.from.length > 64) return null
        if (!store.markSeen(g.id + ":" + env.id)) return null
        if (env.from == me.id) return null
        val name = env.name.trim().take(20).ifEmpty { tr("이름없음", "No name") }
        val ts = env.ts.coerceAtMost(now() + 3600_000)

        if (env.type == Envelope.REKEY) { applyRekey(g, env.rekey ?: return null); return null }
        val pub = env.pub?.takeIf { Invite.validPub(it) }

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
                cur == null -> if (gg.members.size >= MAX_MEMBERS) gg else gg.copy(members = gg.members + Member(env.from, name, ts, pub ?: ""))
                cur.lastSeenMs <= ts -> gg.copy(members = gg.members.map { if (it.id == env.from) Member(env.from, name, ts, pub ?: it.pub) else it })
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

    private val notices = ArrayList<String>()

    /**
     * 그룹원 내보내기(그룹장 전용). 새 그룹 키를 만들어 남는 사람 각각에게만 암호화해 보낸다.
     * 내보낸 사람은 새 키를 받지 못하므로 이후 기록을 볼 수 없다.
     */
    fun kick(groupId: String, memberId: String): Boolean {
        val g = store.groups.value.firstOrNull { it.id == groupId } ?: return false
        if (!isAdmin(g) || g.members.none { it.id == memberId }) return false
        val me = store.identity.value
        val newKey = Crypto.randomBytes(32)
        val entries = g.members.filter { it.id != memberId && Invite.validPub(it.pub) }.mapNotNull { m ->
            val shared = Secp256k1.ecdh(store.mySecret(), Crypto.unhex(m.pub)) ?: return@mapNotNull null
            RekeyEntry(m.id, Crypto.b64(Crypto.seal(boxKey(shared, g.id), newKey)))
        }
        val sig = Crypto.hex(Secp256k1.sign(Rekey.digest(g.id, memberId, entries), store.mySecret()))
        val env = Envelope(id = Crypto.randomHex(8), from = me.id, name = me.name, ts = now(), type = Envelope.REKEY,
            pub = store.myPub, rekey = Rekey(memberId, entries, sig))
        store.updateOutbox { it + OutboxItem(env.id, g.id, Envelope.REKEY, null, now(), topic = g.topic(), key = g.key,
            body = J.encodeToString(Envelope.serializer(), env)) }
        migrate(g, newKey, memberId)
        return true
    }

    private fun boxKey(shared: ByteArray, oldGroupId: String) = Crypto.sha256("runmate-rekey-box".toByteArray(), shared, oldGroupId.toByteArray())

    /** 그룹을 새 키로 옮긴다: 멤버·기록 연결·대기열을 새 그룹으로 이어 붙인다. */
    private fun migrate(old: Group, newKey: ByteArray, removed: String) {
        val newId = Crypto.topicOf(newKey)
        val ng = old.copy(id = newId, key = Crypto.b64(newKey), members = old.members.filter { it.id != removed }, since = emptyMap())
        store.updateGroups { l -> l.filter { it.id != newId }.map { if (it.id == old.id) ng else it } }
        store.updateMetas { m ->
            if (old.id in m.groupIds || old.id in m.pending)
                m.copy(groupIds = m.groupIds.map { if (it == old.id) newId else it }.distinct(), pending = m.pending.map { if (it == old.id) newId else it }.distinct())
            else m
        }
        store.updateOutbox { l -> l.map { if (it.groupId == old.id && it.key == null) it.copy(groupId = newId, done = emptyList()) else it } }
        enqueue(newId, Envelope.HELLO, null)
    }

    private fun applyRekey(g: Group, rk: Rekey) {
        if (!Invite.validPub(g.adminPub) || isAdmin(g)) return
        val sig = try { Crypto.unhex(rk.sig) } catch (e: Exception) { return }
        if (!Secp256k1.verify(Rekey.digest(g.id, rk.removed, rk.entries), Crypto.unhex(g.adminPub), sig)) return
        val me = store.identity.value
        val mine = rk.entries.firstOrNull { it.to == me.id }
        val newKey = if (rk.removed == me.id || mine == null) null else try {
            val shared = Secp256k1.ecdh(store.mySecret(), Crypto.unhex(g.adminPub))
            shared?.let { Crypto.open(boxKey(it, g.id), Crypto.unb64(mine.box)) }?.takeIf { it.size == 32 }
        } catch (e: Exception) { null }
        if (newKey != null) { migrate(g, newKey, rk.removed); return }
        leaveGroup(g.id)
        notices.add(
            if (rk.removed == me.id) tr("'${g.name}' 그룹에서 내보내졌어요.", "You were removed from the group '${g.name}'.")
            else tr("'${g.name}' 그룹의 키가 바뀌었어요. 그룹장에게 초대 QR을 다시 받아 주세요.", "The key of group '${g.name}' has changed. Ask the group owner for a new invite QR.")
        )
    }

    companion object {
        const val OVERLAP_S = 1800L
        const val MAX_AGE_MS = 7L * 86400_000
        const val SECONDARY_RETRY_MS = 6L * 3600_000
        const val MAX_MEMBERS = 200
    }
}
