package dev.mooner.kakaodb.core.crypto

import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * Decryption of KakaoTalk database values.
 * reference: https://github.com/hui1601/libdream
 * Special thanks to HiBot, the original author of libdream.
 *
 * Values are AES-256-CBC with a fixed IV. The key comes from PKCS#12 key derivation (SHA-1, 2 iterations) of a fixed
 * password and a salt made of a prefix chosen by `enc` and the user id. KakaoDBRouter asked BouncyCastle for it
 * ("PBEWITHSHAAND256BITAES-CBC-BC"); Android's own BouncyCastle can't be relied on to provide that algorithm, and a
 * bundled copy clashes with it, so the derivation is done here. The unit tests check it against BouncyCastle.
 */
object KakaoCrypto {

    private val IV = byteArrayOf(15, 8, 1, 0, 25, 71, 37, -36, 21, -11, 23, -32, -31, 21, 12, 53)

    internal val PASSWORD: CharArray =
        intArrayOf(22, 8, 9, 111, 2, 23, 43, 8, 33, 33, 10, 16, 3, 3, 7, 6).map(Int::toChar).toCharArray()

    private val PREFIXES = arrayOf(
        "", "", "12", "24", "18", "30", "36", "12", "48", "7", "35", "40", "17", "23", "29", "isabel", "kale", "sulli",
        "van", "merry", "kyle", "james", "maddux", "tony", "hayden", "paul", "elijah", "dorothy", "sally", "bran",
        "extr.ursra", "veil",
    )

    private const val ITERATIONS = 2
    private const val KEY_BYTES = 32

    private val passwordBytes: ByteArray = pkcs12PasswordBytes(PASSWORD)

    // The key depends only on (userId, enc), so each one is derived once.
    private val keyCache: MutableMap<String, SecretKeySpec> = ConcurrentHashMap()

    private val cipher: ThreadLocal<Cipher> =
        ThreadLocal.withInitial { Cipher.getInstance("AES/CBC/PKCS5Padding") }

    private val ivSpec = IvParameterSpec(IV)

    /** Decrypts [text], or returns null if it isn't a value encrypted for ([userId], [enc]). */
    fun decryptOrNull(userId: Long, enc: Int, text: String?): String? {
        if (text == null)
            return null
        return try {
            decryptOrThrow(userId.toString(), enc, text)
        } catch (e: Exception) {
            null
        }
    }

    /** For values that may or may not be encrypted: returns [text] itself if it can't be decrypted. */
    fun decryptOrRaw(userId: Long, enc: Int, text: String?): String? {
        if (text.isNullOrEmpty() || text == "{}" || text == "[]")
            return text
        return decryptOrNull(userId, enc, text) ?: text
    }

    /** The inverse of [decryptOrNull], for building test databases. */
    fun encrypt(userId: Long, enc: Int, text: String): String {
        val cipher = cipher.get()!!
        cipher.init(Cipher.ENCRYPT_MODE, key(userId.toString(), enc), ivSpec)
        return Base64.getEncoder().encodeToString(cipher.doFinal(text.toByteArray(Charsets.UTF_8)))
    }

    fun isSupportedEnc(enc: Int): Boolean =
        enc in PREFIXES.indices

    private fun decryptOrThrow(userId: String, enc: Int, text: String): String {
        val cipher = cipher.get()!!
        cipher.init(Cipher.DECRYPT_MODE, key(userId, enc), ivSpec)
        return String(cipher.doFinal(Base64.getDecoder().decode(text)), Charsets.UTF_8)
    }

    private fun key(userId: String, enc: Int): SecretKeySpec =
        keyCache.getOrPut("$enc:$userId") { SecretKeySpec(deriveKey(userId, enc), "AES") }

    internal fun deriveKey(userId: String, enc: Int): ByteArray =
        pkcs12Kdf(passwordBytes, salt(userId, enc), ITERATIONS, KEY_BYTES)

    internal fun salt(userId: String, enc: Int): ByteArray {
        val prefix = PREFIXES.getOrNull(enc)
            ?: throw IllegalArgumentException("Unsupported enc type: $enc")
        if ((userId.toLongOrNull() ?: 0L) <= 0L)
            return ByteArray(16)
        return (prefix + userId).take(16).padEnd(16, Char.MIN_VALUE).toByteArray(Charsets.UTF_8)
    }

    /** PKCS#12 password encoding: UTF-16BE with a two-byte terminator (as BouncyCastle's PKCS12PasswordToBytes). */
    internal fun pkcs12PasswordBytes(password: CharArray): ByteArray {
        if (password.isEmpty())
            return ByteArray(0)
        val bytes = ByteArray((password.size + 1) * 2)
        for ((i, c) in password.withIndex()) {
            bytes[i * 2] = (c.code ushr 8).toByte()
            bytes[i * 2 + 1] = c.code.toByte()
        }
        return bytes
    }

    /** PKCS#12 key derivation (RFC 7292, appendix B.2) with SHA-1. [id] 1 derives key material. */
    internal fun pkcs12Kdf(password: ByteArray, salt: ByteArray, iterations: Int, keyLength: Int, id: Int = 1): ByteArray {
        val u = 20 // SHA-1 output size
        val v = 64 // SHA-1 block size
        val d = ByteArray(v) { id.toByte() }
        val i = repeatToBlocks(salt, v) + repeatToBlocks(password, v)
        val blocks = (keyLength + u - 1) / u
        val out = ByteArray(keyLength)
        val sha1 = MessageDigest.getInstance("SHA-1")
        for (n in 1..blocks) {
            sha1.update(d)
            sha1.update(i)
            var a = sha1.digest()
            repeat(iterations - 1) { a = sha1.digest(a) }
            val b = ByteArray(v) { a[it % u] }
            for (j in 0 until i.size / v)
                addOneWithCarry(i, j * v, b)
            val start = (n - 1) * u
            System.arraycopy(a, 0, out, start, minOf(u, keyLength - start))
        }
        return out
    }

    private fun repeatToBlocks(src: ByteArray, v: Int): ByteArray {
        if (src.isEmpty())
            return ByteArray(0)
        val length = v * ((src.size + v - 1) / v)
        return ByteArray(length) { src[it % src.size] }
    }

    /** a[off until off + b.size] = (a[off...] + b + 1) mod 2^(8 * b.size) */
    private fun addOneWithCarry(a: ByteArray, off: Int, b: ByteArray) {
        var x = 1
        for (k in b.size - 1 downTo 0) {
            x += (b[k].toInt() and 0xff) + (a[off + k].toInt() and 0xff)
            a[off + k] = x.toByte()
            x = x ushr 8
        }
    }
}
