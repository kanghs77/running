package app.runmate.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/** 파일 기반 저장소. 모든 쓰기는 임시 파일에 쓴 뒤 교체(원자적)한다. */
class Store(private val dir: File) {
    private val lock = Any()
    private val trackDir = File(dir, "tracks")

    private val _identity: MutableStateFlow<Identity>
    private val _settings: MutableStateFlow<Settings>
    private val _groups: MutableStateFlow<List<Group>>
    private val _metas: MutableStateFlow<List<TrackMeta>>
    private val _outbox: MutableStateFlow<List<OutboxItem>>
    private val seen: LinkedHashSet<String>

    val identity: StateFlow<Identity> get() = _identity
    val settings: StateFlow<Settings> get() = _settings
    val groups: StateFlow<List<Group>> get() = _groups
    val metas: StateFlow<List<TrackMeta>> get() = _metas
    val outbox: StateFlow<List<OutboxItem>> get() = _outbox

    val journalFile: File get() = File(dir, "run/current.csv")

    /** 내 공개키(hex). 그룹원에게 알려져 그룹장 확인/키 전달에 쓰인다. */
    val myPub: String by lazy { Crypto.hex(Secp256k1.pubkey(Crypto.unhex(_identity.value.sk))) }
    fun mySecret(): ByteArray = Crypto.unhex(_identity.value.sk)

    init {
        dir.mkdirs(); trackDir.mkdirs()
        var id = read("identity.json", Identity.serializer())
        if (id == null || id.id.isBlank()) id = Identity(Crypto.randomHex(16), tr("러너", "Runner") + (1000 + (Math.random() * 9000).toInt()))
        if (id.sk.length != 64 || !runCatching { Secp256k1.validSecret(Crypto.unhex(id.sk)) }.getOrDefault(false)) {
            id = id.copy(sk = Crypto.hex(Secp256k1.newSecret()))
            write("identity.json", Identity.serializer(), id)
        }
        _identity = MutableStateFlow(id)
        _settings = MutableStateFlow(read("settings.json", Settings.serializer()) ?: Settings())
        _groups = MutableStateFlow(read("groups.json", ListSerializer(Group.serializer())) ?: emptyList())
        _metas = MutableStateFlow(loadIndex())
        _outbox = MutableStateFlow(read("outbox.json", ListSerializer(OutboxItem.serializer())) ?: emptyList())
        seen = LinkedHashSet(read("seen.json", ListSerializer(String.serializer())) ?: emptyList())
    }

    private fun loadIndex(): List<TrackMeta> {
        val idx = read("tracks/index.json", ListSerializer(TrackMeta.serializer()))
        if (idx != null) return idx.filter { trackFile(it.id).exists() }.sortedByDescending { it.startMs }
        // 인덱스가 없거나 깨졌으면 트랙 파일에서 다시 만든다.
        val myId = _identity.value.id
        val rebuilt = (trackDir.listFiles() ?: emptyArray()).filter { it.name.endsWith(".json") && it.name != "index.json" }.mapNotNull { f ->
            try { Track.fromDto(J.decodeFromString(TrackDto.serializer(), f.readText()))?.takeIf { safe(it.id) + ".json" == f.name } } catch (e: Exception) { null }
        }.map { Series.meta(it, it.ownerId == myId).copy(unread = false) }.sortedByDescending { it.startMs }
        if (rebuilt.isNotEmpty()) write("tracks/index.json", ListSerializer(TrackMeta.serializer()), rebuilt)
        return rebuilt
    }

    private fun <T> read(name: String, ser: KSerializer<T>): T? {
        val f = File(dir, name)
        if (!f.exists()) return null
        return try { J.decodeFromString(ser, f.readText()) } catch (e: Exception) {
            try { f.copyTo(File(dir, "$name.bad"), overwrite = true) } catch (e2: Exception) {}
            null
        }
    }

    private fun <T> write(name: String, ser: KSerializer<T>, value: T) {
        val f = File(dir, name)
        f.parentFile?.mkdirs()
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(J.encodeToString(ser, value))
        if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() } }
    }

    private fun safe(id: String) = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64).ifEmpty { "x" }
    private fun trackFile(id: String) = File(trackDir, safe(id) + ".json")

    fun setName(name: String) = synchronized(lock) {
        val n = name.trim().take(20)
        if (n.isEmpty()) return@synchronized
        _identity.value = _identity.value.copy(name = n)
        write("identity.json", Identity.serializer(), _identity.value)
        updateSettings { it.copy(nameSet = true) }
    }

    fun updateSettings(f: (Settings) -> Settings) = synchronized(lock) {
        _settings.value = f(_settings.value)
        write("settings.json", Settings.serializer(), _settings.value)
    }

    fun updateGroups(f: (List<Group>) -> List<Group>) = synchronized(lock) {
        val g = f(_groups.value)
        if (g != _groups.value) {
            _groups.value = g
            write("groups.json", ListSerializer(Group.serializer()), g)
        }
    }

    fun updateGroup(id: String, f: (Group) -> Group) = updateGroups { l -> l.map { if (it.id == id) f(it) else it } }

    fun updateOutbox(f: (List<OutboxItem>) -> List<OutboxItem>) = synchronized(lock) {
        val o = f(_outbox.value)
        if (o != _outbox.value) {
            _outbox.value = o
            write("outbox.json", ListSerializer(OutboxItem.serializer()), o)
        }
    }

    private fun setMetas(m: List<TrackMeta>) {
        _metas.value = m.sortedByDescending { it.startMs }
        write("tracks/index.json", ListSerializer(TrackMeta.serializer()), _metas.value)
    }

    fun updateMeta(id: String, f: (TrackMeta) -> TrackMeta) = synchronized(lock) {
        val cur = _metas.value
        if (cur.none { it.id == id }) return@synchronized
        val next = cur.map { if (it.id == id) f(it) else it }
        if (next != cur) setMetas(next)
    }

    fun updateMetas(f: (TrackMeta) -> TrackMeta) = synchronized(lock) {
        val next = _metas.value.map(f)
        if (next != _metas.value) setMetas(next)
    }

    fun meta(id: String): TrackMeta? = _metas.value.firstOrNull { it.id == id }

    /** 트랙 저장. metaFn은 기존 메타(없으면 null)를 받아 새 메타를 만든다. */
    fun saveTrack(t: Track, metaFn: (TrackMeta?) -> TrackMeta) = synchronized(lock) {
        val f = trackFile(t.id)
        val tmp = File(trackDir, f.name + ".tmp")
        tmp.writeText(J.encodeToString(TrackDto.serializer(), t.toDto()))
        if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() } }
        val old = _metas.value.firstOrNull { it.id == t.id }
        setMetas(_metas.value.filter { it.id != t.id } + metaFn(old))
    }

    fun loadTrack(id: String): Track? {
        val f = trackFile(id)
        return try {
            if (!f.exists()) null else Track.fromDto(J.decodeFromString(TrackDto.serializer(), f.readText()))
        } catch (e: Exception) { null }
    }

    fun deleteTrack(id: String) = synchronized(lock) {
        trackFile(id).delete()
        setMetas(_metas.value.filter { it.id != id })
        updateOutbox { l -> l.filter { it.trackId != id } }
    }

    /** 처음 보는 메시지면 true */
    fun markSeen(msgKey: String): Boolean = synchronized(lock) {
        if (!seen.add(msgKey)) return@synchronized false
        while (seen.size > 4000) { val it = seen.iterator(); it.next(); it.remove() }
        true
    }

    fun isSeen(msgKey: String): Boolean = synchronized(lock) { msgKey in seen }

    fun persistSeen() = synchronized(lock) { write("seen.json", ListSerializer(String.serializer()), seen.toList()) }
}
