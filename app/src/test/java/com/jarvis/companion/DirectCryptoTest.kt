package com.jarvis.companion

import com.jarvis.companion.net.DirectCrypto
import com.jarvis.companion.net.Frames
import com.jarvis.companion.net.str
import com.jarvis.companion.net.toBase64
import com.jarvis.companion.net.toHex
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// The daemon generated these bytes (backend directCrypto with a fixed
// nonce); if this test fails, the two implementations have drifted and
// Direct will fail silently in the field.
class DirectCryptoTest {

    private val secret = "a".repeat(64)

    @Test
    fun `derivation matches the daemon exactly`() {
        assertEquals("4f126bdcacba5bc73c37643374ae519fcdd076a3192e00b89b166758b859e644",
            DirectCrypto.keyFor(secret).toHex())
        assertEquals("jarvis-7137e81c6989a55a22b22c2243052d55",
            DirectCrypto.topicFor(secret))
    }

    @Test
    fun `a daemon-sealed envelope opens on the phone`() {
        val envelope = buildJsonObject {
            put("v", JsonPrimitive(1))
            put("from", JsonPrimitive("mac"))
            put("ts", JsonPrimitive(System.currentTimeMillis()))
            put("n", JsonPrimitive("AAECAwQFBgcICQoL"))
            put("c", JsonPrimitive(
                "qMDVbXsCwAYCFZEDrweq9gy5ngEhui2UhTXEoSr59mPUcQJeQfneKQb0NGNNCSmBp7JDeOw7"))
        }.toString()
        val opened = DirectCrypto.open(DirectCrypto.keyFor(secret), "phone", envelope)
        assertEquals("answer", opened?.str("kind"))
        assertEquals("v=0 pinned", opened?.str("sdp"))
        assertNull(DirectCrypto.open(DirectCrypto.keyFor(secret), "phone", envelope),
            "a nonce must never open twice")
    }

    @Test
    fun `phone-sealed envelopes round trip and refuse self, stale, tampering`() {
        val key = DirectCrypto.keyFor(secret)
        val sealed = DirectCrypto.seal(key, "phone", buildJsonObject {
            put("kind", JsonPrimitive("offer"))
            put("sdp", JsonPrimitive("v=0 round trip"))
        })
        assertNull(DirectCrypto.open(key, "phone", sealed), "own echo must be ignored")
        val opened = DirectCrypto.open(key, "mac", sealed)
        assertEquals("v=0 round trip", opened?.str("sdp"))
        val tampered = sealed.replace("\"from\":\"phone\"", "\"from\":\"imposter\"")
        assertNull(DirectCrypto.open(key, "mac", tampered), "AAD binds the sender")
    }

    @Test
    fun `the remote key and binary envelope match the daemon byte for byte`() {
        assertEquals("ea0caf873be8104371102f25ab03bca882b65273db18dfe99919602926382103",
            DirectCrypto.remoteKeyFor(secret).toHex())
        // Encrypt with the daemon's fixed nonce and timestamp and demand the
        // daemon's exact bytes — a byte of drift is silent field failure.
        val key = DirectCrypto.remoteKeyFor(secret)
        val nonce = ByteArray(12) { it.toByte() }
        val ts = java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.BIG_ENDIAN).putLong(1755700000000L).array()
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.GCMParameterSpec(128, nonce))
        cipher.updateAAD("mac".toByteArray(Charsets.UTF_8) + ts)
        val envelope = byteArrayOf(1) + nonce + ts +
            cipher.doFinal("sealed door".toByteArray(Charsets.UTF_8))
        assertEquals("AQABAgMEBQYHCAkKCwAAAZjH3/UAzFQwSFH6esZo6kDlDhKYtQqZeQW9/zF03opd",
            envelope.toBase64())
    }

    @Test
    fun `binary envelopes round trip and refuse tampering and wrong senders`() {
        val key = DirectCrypto.remoteKeyFor(secret)
        val sealed = DirectCrypto.sealBinary(key, "phone", "round trip".toByteArray())
        assertEquals("round trip",
            DirectCrypto.openBinary(key, "phone", sealed)!!.decodeToString())
        assertNull(DirectCrypto.openBinary(key, "mac", sealed), "AAD binds the sender")
        val tampered = sealed.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0xff).toByte()
        assertNull(DirectCrypto.openBinary(key, "phone", tampered))
    }

    @Test
    fun `oversized text rides framed and reassembles whole`() {
        val text = "x".repeat(200_000)
        val frames = Frames.chunks(Frames.TAG_WS_TEXT,
            kotlinx.serialization.json.buildJsonObject {
                put("sid", JsonPrimitive(9)) }, text.toByteArray())
        assertTrue(frames.size > 1)
        val assembler = Frames.Assembler()
        var whole: Frames.Whole? = null
        for (frame in frames) whole = assembler.accept(frame) ?: whole
        assertEquals(text, whole!!.body.decodeToString())
    }

    @Test
    fun `frames chunk and reassemble exactly, gaps kill the stream`() {
        val body = ByteArray(200_000) { (it % 251).toByte() }
        val frames = Frames.chunks(Frames.TAG_WS_BINARY,
            buildJsonObject { put("sid", JsonPrimitive(7)) }, body)
        assertTrue(frames.size > 1)
        val assembler = Frames.Assembler()
        var whole: Frames.Whole? = null
        for (frame in frames) whole = assembler.accept(frame) ?: whole
        assertEquals(body.toList(), whole!!.body.toList())

        val gappy = Frames.Assembler()
        assertNull(gappy.accept(frames[0]))
        assertNull(gappy.accept(frames[2]), "a gap kills the stream")
        assertNull(gappy.accept(frames[1]), "nothing resurrects it")
    }
}
