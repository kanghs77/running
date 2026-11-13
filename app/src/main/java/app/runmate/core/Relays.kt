package app.runmate.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.Base64
import java.util.Collections

class Incoming(val topic: String, val sealed: ByteArray)

/**
 * 계정/로그인 없이 쓸 수 있는 공개 중계 서버 추상화.
 * 서버는 암호문만 보관/전달하며 내용을 볼 수 없다.
 */
interface Relay {
    val name: String
    suspend fun publish(topic: String, msgId: String, sealed: ByteArray): Boolean
    /** 실패하면 null */
    suspend fun fetch(topics: List<String>, sinceSec: Long): List<Incoming>?
}

/** ntfy.sh: HTTP 기반 공개 pub/sub. 메시지 12시간 보관, 본문 4KB 제한 -> 조각으로 나눠 보낸다. */
class NtfyRelay(
    private val http: OkHttpClient,
    private val base: String = "https://ntfy.sh",
    private val chunkPause: Long = 150,
) : Relay {
    override val name = "ntfy"

    override suspend fun publish(topic: String, msgId: String, sealed: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val b64 = Base64.getEncoder().encodeToString(sealed)
        val parts = b64.chunked(CHUNK)
        if (parts.size > MAX_CHUNKS) return@withContext false
        for ((i, p) in parts.withIndex()) {
            val body = "RM1:$msgId:$i:${parts.size}:$p"
            val req = Request.Builder().url("$base/$topic")
                .header("Cache", "yes")
                .post(body.toRequestBody("text/plain".toMediaType())).build()
            val ok = try { http.newCall(req).execute().use { it.isSuccessful } } catch (e: Exception) { false }
            if (!ok) return@withContext false
            if (i < parts.size - 1) delay(chunkPause)
        }
        true
    }

    override suspend fun fetch(topics: List<String>, sinceSec: Long): List<Incoming>? = withContext(Dispatchers.IO) {
        if (topics.isEmpty()) return@withContext emptyList()
        val since = if (sinceSec <= 0) "all" else sinceSec.toString()
        val req = Request.Builder().url("$base/${topics.joinToString(",")}/json?poll=1&since=$since").build()
        val text = try {
            http.newCall(req).execute().use { r: Response -> if (!r.isSuccessful) null else r.body?.string() }
        } catch (e: Exception) { null } ?: return@withContext null

        // (topic, msgId) -> 조각들
        val parts = LinkedHashMap<Pair<String, String>, Array<String?>>()
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            try {
                val o = J.parseToJsonElement(line).jsonObject
                if (o["event"]?.jsonPrimitive?.content != "message") continue
                val topic = o["topic"]!!.jsonPrimitive.content
                val m = o["message"]!!.jsonPrimitive.content.split(':', limit = 5)
                if (m.size != 5 || m[0] != "RM1") continue
                val idx = m[2].toInt(); val total = m[3].toInt()
                if (total < 1 || total > MAX_CHUNKS || idx < 0 || idx >= total) continue
                val arr = parts.getOrPut(topic to m[1]) { arrayOfNulls(total) }
                if (arr.size == total) arr[idx] = m[4]
            } catch (e: Exception) { /* 형식이 다른 메시지는 무시 */ }
        }
        val out = ArrayList<Incoming>()
        for ((k, arr) in parts) {
            if (arr.any { it == null }) continue
            try { out.add(Incoming(k.first, Base64.getDecoder().decode(arr.joinToString("")))) } catch (e: Exception) {}
        }
        out
    }

    companion object {
        const val CHUNK = 3400
        const val MAX_CHUNKS = 60
    }
}

/** Nostr 공개 릴레이 여러 곳: 오래 보관되는 백업 경로. 이벤트마다 일회용 키로 서명한다. */
class NostrRelay(
    private val http: OkHttpClient,
    private val urls: List<String> = DEFAULT_URLS,
    private val timeoutMs: Long = 10_000,
) : Relay {
    override val name = "nostr"

    /** 마지막 호출에서 응답한 릴레이 수 (진단용) */
    @Volatile var lastOk = 0; private set

    override suspend fun publish(topic: String, msgId: String, sealed: ByteArray): Boolean = coroutineScope {
        val ev = buildEvent(topic, msgId, sealed, System.currentTimeMillis() / 1000)
        val id = ev["id"]!!.jsonPrimitive.content
        val msg = buildJsonArray { add("EVENT"); add(ev) }.toString()
        val res = urls.map { u -> async { publishOne(u, msg, id) } }.awaitAll()
        lastOk = res.count { it }
        lastOk > 0
    }

    override suspend fun fetch(topics: List<String>, sinceSec: Long): List<Incoming>? = coroutineScope {
        if (topics.isEmpty()) return@coroutineScope emptyList()
        val req = buildJsonArray {
            add("REQ"); add("rm")
            add(buildJsonObject {
                putJsonArray("kinds") { add(KIND) }
                putJsonArray("#t") { topics.forEach { add(it) } }
                if (sinceSec > 0) put("since", sinceSec)
                put("limit", 500)
            })
        }.toString()
        val res = urls.map { u -> async { fetchOne(u, req) } }.awaitAll()
        lastOk = res.count { it != null }
        if (lastOk == 0) return@coroutineScope null
        val seen = HashSet<String>()
        val out = ArrayList<Incoming>()
        for (list in res) for (ev in list ?: continue) {
            try {
                val id = ev["id"]!!.jsonPrimitive.content
                if (!seen.add(id)) continue
                if (ev["kind"]!!.jsonPrimitive.content != KIND.toString()) continue
                val topic = ev["tags"]!!.jsonArray.map { it.jsonArray }
                    .firstOrNull { it.size >= 2 && it[0].jsonPrimitive.content == "t" }?.get(1)?.jsonPrimitive?.content ?: continue
                if (topic !in topics) continue
                out.add(Incoming(topic, Base64.getDecoder().decode(ev["content"]!!.jsonPrimitive.content)))
            } catch (e: Exception) {}
        }
        out
    }

    private suspend fun publishOne(url: String, msg: String, id: String): Boolean {
        val done = CompletableDeferred<Boolean>()
        val ws = try {
            http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(msg) }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val a = J.parseToJsonElement(text).jsonArray
                        if (a[0].jsonPrimitive.content == "OK" && a[1].jsonPrimitive.content == id) {
                            val ok = a[2].jsonPrimitive.content == "true" ||
                                (a.size > 3 && a[3].jsonPrimitive.content.startsWith("duplicate"))
                            done.complete(ok)
                        }
                    } catch (e: Exception) {}
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { done.complete(false) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { done.complete(false) }
            })
        } catch (e: Exception) { return false }
        return try { withTimeoutOrNull(timeoutMs) { done.await() } ?: false } finally { closeQuietly(ws) }
    }

    private suspend fun fetchOne(url: String, req: String): List<JsonObject>? {
        val done = CompletableDeferred<Boolean>()
        val events = Collections.synchronizedList(ArrayList<JsonObject>())
        val ws = try {
            http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(req) }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val a = J.parseToJsonElement(text).jsonArray
                        when (a[0].jsonPrimitive.content) {
                            "EVENT" -> if (events.size < 2000) events.add(a[2].jsonObject)
                            "EOSE" -> done.complete(true)
                            "CLOSED" -> done.complete(false)
                        }
                    } catch (e: Exception) {}
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { done.complete(false) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { done.complete(false) }
            })
        } catch (e: Exception) { return null }
        val ok = try { withTimeoutOrNull(timeoutMs) { done.await() } ?: false } finally { closeQuietly(ws) }
        return if (ok) synchronized(events) { ArrayList(events) } else null
    }

    private fun closeQuietly(ws: WebSocket) {
        try { if (!ws.close(1000, null)) ws.cancel() } catch (e: Exception) { try { ws.cancel() } catch (e2: Exception) {} }
    }

    companion object {
        /** NIP-78: 앱 전용 임의 데이터 */
        const val KIND = 30078
        val DEFAULT_URLS = listOf("wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net", "wss://relay.snort.social")

        fun buildEvent(topic: String, msgId: String, sealed: ByteArray, createdAt: Long, sk: ByteArray = Secp256k1.newSecret()): JsonObject {
            val pub = Crypto.hex(Secp256k1.pubkey(sk))
            val content = Base64.getEncoder().encodeToString(sealed)
            val tags = buildJsonArray {
                add(buildJsonArray { add("d"); add(msgId) })
                add(buildJsonArray { add("t"); add(topic) })
            }
            val ser = JsonArray(listOf(JsonPrimitive(0), JsonPrimitive(pub), JsonPrimitive(createdAt), JsonPrimitive(KIND), tags, JsonPrimitive(content))).toString()
            val id = Crypto.sha256(ser.toByteArray(Charsets.UTF_8))
            return buildJsonObject {
                put("id", Crypto.hex(id)); put("pubkey", pub); put("created_at", createdAt); put("kind", KIND)
                put("tags", tags); put("content", content); put("sig", Crypto.hex(Secp256k1.sign(id, sk)))
            }
        }
    }
}
