package com.x10pult.app.mi

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Криптографія запитів до хмари Xiaomi (MIoT):
 * - nonce: 8 випадкових байтів + номер хвилини від 1970 року (4 байти, big-endian), у base64;
 * - signedNonce = base64(SHA-256(ssecurity + nonce));
 * - підпис запиту = base64(SHA-1("POST&шлях&k=v&...&signedNonce"));
 * - тіло й відповідь шифруються RC4 з ключем signedNonce (перші 1024 байти потоку відкидаються).
 */
internal object MiCrypto {
    private val random = SecureRandom()

    fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    fun unb64(text: String): ByteArray = Base64.decode(text, Base64.DEFAULT)

    private fun sha1(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(bytes)
    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun nonce(): String {
        val bytes = ByteArray(12)
        random.nextBytes(bytes)
        val minutes = (System.currentTimeMillis() / 60_000L).toInt()
        bytes[8] = (minutes ushr 24).toByte()
        bytes[9] = (minutes ushr 16).toByte()
        bytes[10] = (minutes ushr 8).toByte()
        bytes[11] = minutes.toByte()
        return b64(bytes)
    }

    fun signedNonce(ssecurity: String, nonce: String): String = b64(sha256(unb64(ssecurity) + unb64(nonce)))

    /** Підпис для зашифрованих запитів: шлях без префікса /app, параметри в порядку додавання. */
    fun signature(method: String, path: String, signedNonce: String, params: List<Pair<String, String>>): String {
        val parts = ArrayList<String>(params.size + 3)
        parts += method.uppercase()
        parts += path
        params.forEach { (key, value) -> parts += "$key=$value" }
        parts += signedNonce
        return b64(sha1(parts.joinToString("&").toByteArray(Charsets.UTF_8)))
    }

    /** Підпис, яким сервер токенів (sts) перевіряє, що nonce отримано разом із ssecurity. */
    fun clientSign(nonce: String, ssecurity: String): String =
        b64(sha1("nonce=$nonce&$ssecurity".toByteArray(Charsets.UTF_8)))

    fun encrypt(signedNonce: String, text: String): String =
        b64(rc4(unb64(signedNonce), text.toByteArray(Charsets.UTF_8)))

    fun decrypt(signedNonce: String, base64Text: String): String =
        String(rc4(unb64(signedNonce), unb64(base64Text.trim())), Charsets.UTF_8)

    private fun rc4(key: ByteArray, data: ByteArray): ByteArray {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            val t = s[i]; s[i] = s[j]; s[j] = t
        }
        var x = 0
        var y = 0
        fun nextByte(): Int {
            x = (x + 1) and 0xFF
            y = (y + s[x]) and 0xFF
            val t = s[x]; s[x] = s[y]; s[y] = t
            return s[(s[x] + s[y]) and 0xFF]
        }
        repeat(1024) { nextByte() }
        val out = ByteArray(data.size)
        for (k in data.indices) out[k] = (data[k].toInt() xor nextByte()).toByte()
        return out
    }
}
