package com.jarvis.companion.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.security.SecureRandom
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// Mirror of the daemon's directCrypto: HKDF-SHA256 from the camera-carried
// secret, AES-256-GCM envelopes over deflated JSON, sender name as AAD.
// The formats are pinned against a Node-generated vector in unit tests —
// a byte of drift here is silent total failure in the field.
object DirectCrypto {

    const val WINDOW_MS = 120_000L
    private val random = SecureRandom()

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    // RFC 5869 with an absent salt (zero-filled key — HMAC pads identically).
    fun derive(secretHex: String, info: String, bytes: Int): ByteArray {
        val secret = secretHex.hexToBytes()
        require(secret.size == 32) { "pairing secret must be 32 bytes" }
        val prk = hmac(ByteArray(32), secret)
        var block = ByteArray(0)
        val out = ByteArray(bytes)
        var filled = 0
        var counter = 1
        while (filled < bytes) {
            block = hmac(prk, block + info.toByteArray(Charsets.UTF_8) + byteArrayOf(counter.toByte()))
            val take = minOf(block.size, bytes - filled)
            block.copyInto(out, filled, 0, take)
            filled += take
            counter++
        }
        return out
    }

    fun topicFor(secretHex: String): String =
        "jarvis-" + derive(secretHex, "jarvis-direct/topic", 16).toHex()

    fun keyFor(secretHex: String): ByteArray = derive(secretHex, "jarvis-direct/signal", 32)

    private fun deflateRaw(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        deflater.setInput(data); deflater.finish()
        val out = ByteArray(data.size + 64)
        val chunks = ArrayList<ByteArray>()
        while (!deflater.finished()) {
            val n = deflater.deflate(out)
            chunks.add(out.copyOf(n))
        }
        deflater.end()
        return chunks.fold(ByteArray(0)) { acc, c -> acc + c }
    }

    private fun inflateRaw(data: ByteArray): ByteArray {
        val inflater = Inflater(true)
        inflater.setInput(data)
        val out = ByteArray(64 * 1024)
        val chunks = ArrayList<ByteArray>()
        while (!inflater.finished()) {
            val n = inflater.inflate(out)
            if (n == 0 && inflater.needsInput()) break
            chunks.add(out.copyOf(n))
        }
        inflater.end()
        return chunks.fold(ByteArray(0)) { acc, c -> acc + c }
    }

    fun seal(key: ByteArray, from: String, payload: JsonObject): String {
        val nonce = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(from.toByteArray(Charsets.UTF_8))
        val sealed = cipher.doFinal(deflateRaw(payload.toString().toByteArray(Charsets.UTF_8)))
        return buildJsonObject {
            put("v", JsonPrimitive(1))
            put("from", JsonPrimitive(from))
            put("ts", JsonPrimitive(System.currentTimeMillis()))
            put("n", JsonPrimitive(nonce.toBase64()))
            put("c", JsonPrimitive(sealed.toBase64()))
        }.toString()
    }

    private val seen = LinkedHashMap<String, Long>()

    fun open(key: ByteArray, self: String, raw: String): JsonObject? {
        val envelope = runCatching {
            Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        if (envelope.int("v") != 1) return null
        if (envelope.str("from") == self) return null
        val ts = (envelope["ts"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return null
        if (kotlin.math.abs(System.currentTimeMillis() - ts) > WINDOW_MS) return null
        val nonceB64 = envelope.str("n") ?: return null
        synchronized(seen) {
            if (seen.containsKey(nonceB64)) return null
        }
        return runCatching {
            val nonce = nonceB64.fromBase64()
            val sealed = (envelope.str("c") ?: return null).fromBase64()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD((envelope.str("from") ?: "").toByteArray(Charsets.UTF_8))
            val packed = cipher.doFinal(sealed)
            synchronized(seen) {
                seen[nonceB64] = ts
                val cutoff = System.currentTimeMillis() - WINDOW_MS
                seen.entries.removeAll { it.value < cutoff }
            }
            Json.parseToJsonElement(inflateRaw(packed).decodeToString()) as? JsonObject
        }.getOrNull()
    }
}

fun String.hexToBytes(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun ByteArray.toBase64(): String = java.util.Base64.getEncoder().encodeToString(this)

fun String.fromBase64(): ByteArray = java.util.Base64.getDecoder().decode(this)
