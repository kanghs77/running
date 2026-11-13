package app.runmate.core

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object Crypto {
    private val rnd = SecureRandom()
    private const val MAX_INFLATED = 8 * 1024 * 1024

    fun randomBytes(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }
    fun randomHex(nBytes: Int) = hex(randomBytes(nBytes))

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        for (p in parts) md.update(p)
        return md.digest()
    }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) { sb.append(HEX[(x.toInt() shr 4) and 15]); sb.append(HEX[x.toInt() and 15]) }
        return sb.toString()
    }

    fun unhex(s: String): ByteArray {
        require(s.length % 2 == 0)
        return ByteArray(s.length / 2) { ((Character.digit(s[it * 2], 16) shl 4) or Character.digit(s[it * 2 + 1], 16)).toByte() }
    }

    private const val HEX = "0123456789abcdef"

    fun b64(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    fun unb64(s: String): ByteArray = Base64.getUrlDecoder().decode(s.trim().trimEnd('='))

    /** 그룹 키에서 공개 토픽 이름을 만든다(키를 역산할 수 없음). */
    fun topicOf(key: ByteArray): String = "rm" + hex(hmac(key, "runmate-topic-v1".toByteArray())).substring(0, 38)

    private fun encKey(key: ByteArray) = hmac(key, "runmate-enc-v1".toByteArray())

    fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION)
        d.setInput(data); d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    fun inflate(data: ByteArray): ByteArray? {
        val inf = Inflater()
        return try {
            inf.setInput(data)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && !inf.finished() && (inf.needsInput() || inf.needsDictionary())) return null
                out.write(buf, 0, n)
                if (out.size() > MAX_INFLATED) return null
            }
            out.toByteArray()
        } catch (e: Exception) { null } finally { inf.end() }
    }

    /** 압축 후 AES-256-GCM 암호화. 결과 = 버전(1) | nonce(12) | 암호문+태그 */
    fun seal(key: ByteArray, plain: ByteArray): ByteArray {
        val nonce = randomBytes(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encKey(key), "AES"), GCMParameterSpec(128, nonce))
        val ct = c.doFinal(deflate(plain))
        return byteArrayOf(1) + nonce + ct
    }

    /** 키가 다르거나 변조되었으면 null. */
    fun open(key: ByteArray, sealed: ByteArray): ByteArray? = try {
        if (sealed.size < 1 + 12 + 16 || sealed[0] != 1.toByte()) null
        else {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(encKey(key), "AES"), GCMParameterSpec(128, sealed, 1, 12))
            inflate(c.doFinal(sealed, 13, sealed.size - 13))
        }
    } catch (e: Exception) { null }
}

/** QR 코드에 담기는 초대 정보. 그룹 키는 QR(또는 직접 전달한 코드)로만 이동한다. */
object Invite {
    private const val PREFIX = "runmate://join?"

    fun encode(name: String, key: ByteArray): String =
        PREFIX + "v=1&k=" + Crypto.b64(key) + "&n=" + Crypto.b64(name.toByteArray(Charsets.UTF_8))

    fun decode(text: String): Pair<String, ByteArray>? = try {
        val s = text.trim()
        if (!s.startsWith(PREFIX)) null
        else {
            val params = s.substring(PREFIX.length).split('&').mapNotNull {
                val i = it.indexOf('='); if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
            }.toMap()
            val key = Crypto.unb64(params["k"] ?: "")
            val name = String(Crypto.unb64(params["n"] ?: ""), Charsets.UTF_8).trim().take(30)
            if (params["v"] != "1" || key.size != 32 || name.isEmpty()) null else name to key
        }
    } catch (e: Exception) { null }
}
