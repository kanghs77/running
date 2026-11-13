package app.runmate.core

import java.math.BigInteger

/** BIP-340 Schnorr 서명 (Nostr 이벤트 서명용). 순수 BigInteger 구현. */
object Secp256k1 {
    private val P = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    private val N = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val GX = BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16)
    private val GY = BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16)
    private val TWO = BigInteger.valueOf(2)
    private val THREE = BigInteger.valueOf(3)
    private val SEVEN = BigInteger.valueOf(7)

    private class Pt(val x: BigInteger, val y: BigInteger)
    private val G = Pt(GX, GY)

    private fun add(a: Pt?, b: Pt?): Pt? {
        if (a == null) return b
        if (b == null) return a
        if (a.x == b.x && a.y != b.y) return null
        val lam = if (a.x == b.x) {
            THREE.multiply(a.x).multiply(a.x).multiply(TWO.multiply(a.y).modInverse(P)).mod(P)
        } else {
            b.y.subtract(a.y).multiply(b.x.subtract(a.x).mod(P).modInverse(P)).mod(P)
        }
        val x3 = lam.multiply(lam).subtract(a.x).subtract(b.x).mod(P)
        return Pt(x3, lam.multiply(a.x.subtract(x3)).subtract(a.y).mod(P))
    }

    private fun mul(p: Pt?, k: BigInteger): Pt? {
        var r: Pt? = null
        var q = p
        for (i in 0 until k.bitLength()) {
            if (k.testBit(i)) r = add(r, q)
            q = add(q, q)
        }
        return r
    }

    private fun bytes32(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        val out = ByteArray(32)
        if (b.size >= 32) System.arraycopy(b, b.size - 32, out, 0, 32) else System.arraycopy(b, 0, out, 32 - b.size, b.size)
        return out
    }

    private fun int(b: ByteArray) = BigInteger(1, b)

    private fun tagged(tag: String, vararg parts: ByteArray): ByteArray {
        val th = Crypto.sha256(tag.toByteArray())
        return Crypto.sha256(th, th, *parts)
    }

    private fun liftX(x: BigInteger): Pt? {
        if (x >= P) return null
        val c = x.modPow(THREE, P).add(SEVEN).mod(P)
        val y = c.modPow(P.add(BigInteger.ONE).shiftRight(2), P)
        if (y.modPow(TWO, P) != c) return null
        return Pt(x, if (y.testBit(0)) P.subtract(y) else y)
    }

    fun validSecret(sk: ByteArray): Boolean = sk.size == 32 && int(sk).let { it.signum() > 0 && it < N }

    fun newSecret(): ByteArray { while (true) { val s = Crypto.randomBytes(32); if (validSecret(s)) return s } }

    /** x-only 공개키 (32바이트) */
    fun pubkey(sk: ByteArray): ByteArray {
        require(validSecret(sk))
        return bytes32(mul(G, int(sk))!!.x)
    }

    /** x-only 공개키와의 ECDH 공유 비밀(32바이트). 양쪽이 같은 값을 얻는다. */
    fun ecdh(sk: ByteArray, pubX: ByteArray): ByteArray? {
        if (!validSecret(sk) || pubX.size != 32) return null
        val p = liftX(int(pubX)) ?: return null
        return bytes32((mul(p, int(sk)) ?: return null).x)
    }

    fun sign(msg: ByteArray, sk: ByteArray, aux: ByteArray = Crypto.randomBytes(32)): ByteArray {
        require(msg.size == 32 && aux.size == 32 && validSecret(sk))
        val d0 = int(sk)
        val pt = mul(G, d0)!!
        val d = if (pt.y.testBit(0)) N.subtract(d0) else d0
        val px = bytes32(pt.x)
        val tmask = tagged("BIP0340/aux", aux)
        val db = bytes32(d)
        val t = ByteArray(32) { (db[it].toInt() xor tmask[it].toInt()).toByte() }
        val k0 = int(tagged("BIP0340/nonce", t, px, msg)).mod(N)
        require(k0.signum() != 0)
        val r = mul(G, k0)!!
        val k = if (r.y.testBit(0)) N.subtract(k0) else k0
        val rx = bytes32(r.x)
        val e = int(tagged("BIP0340/challenge", rx, px, msg)).mod(N)
        return rx + bytes32(k.add(e.multiply(d)).mod(N))
    }

    fun verify(msg: ByteArray, pk: ByteArray, sig: ByteArray): Boolean {
        if (msg.size != 32 || pk.size != 32 || sig.size != 64) return false
        val p = liftX(int(pk)) ?: return false
        val r = int(sig.copyOfRange(0, 32))
        val s = int(sig.copyOfRange(32, 64))
        if (r >= P || s >= N) return false
        val e = int(tagged("BIP0340/challenge", sig.copyOfRange(0, 32), pk, msg)).mod(N)
        val rr = add(mul(G, s), mul(p, N.subtract(e))) ?: return false
        return !rr.y.testBit(0) && rr.x == r
    }
}
