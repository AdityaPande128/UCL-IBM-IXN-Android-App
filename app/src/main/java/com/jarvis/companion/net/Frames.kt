package com.jarvis.companion.net

import java.nio.ByteBuffer

// Mirror of the daemon's channelFrames: [tag][4B BE header len][header JSON]
// [payload chunk]. Ordered+reliable channel, so sequence gaps mean bugs and
// kill the stream whole — nothing is ever delivered short.
object Frames {
    const val TAG_WS_BINARY = 0x01
    const val TAG_FILE_REQ = 0x02
    const val TAG_FILE_RES = 0x03
    const val CHUNK_BYTES = 64 * 1024
    private val MAX_BYTES = mapOf(
        TAG_WS_BINARY to 8 * 1024 * 1024,
        TAG_FILE_REQ to 24 * 1024 * 1024,
        TAG_FILE_RES to 24 * 1024 * 1024)

    fun encode(tag: Int, header: String, payload: ByteArray, from: Int, len: Int): ByteArray {
        val head = header.toByteArray(Charsets.UTF_8)
        val frame = ByteBuffer.allocate(5 + head.size + len)
        frame.put(tag.toByte())
        frame.putInt(head.size)
        frame.put(head)
        frame.put(payload, from, len)
        return frame.array()
    }

    data class Decoded(val tag: Int, val header: kotlinx.serialization.json.JsonObject, val payload: ByteArray)

    fun decode(frame: ByteArray): Decoded? {
        if (frame.size < 5) return null
        val buffer = ByteBuffer.wrap(frame)
        val tag = buffer.get().toInt() and 0xff
        if (tag < 0x01 || tag > 0x03) return null
        val headLength = buffer.int
        if (headLength < 0 || headLength > 64 * 1024 || 5 + headLength > frame.size) return null
        val header = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(
                String(frame, 5, headLength, Charsets.UTF_8))
                as? kotlinx.serialization.json.JsonObject
        }.getOrNull() ?: return null
        return Decoded(tag, header, frame.copyOfRange(5 + headLength, frame.size))
    }

    fun chunks(tag: Int, meta: kotlinx.serialization.json.JsonObject, body: ByteArray): List<ByteArray> {
        val total = maxOf(1, (body.size + CHUNK_BYTES - 1) / CHUNK_BYTES)
        val sid = meta.int("sid") ?: 0
        return (0 until total).map { seq ->
            val from = seq * CHUNK_BYTES
            val len = minOf(CHUNK_BYTES, body.size - from).coerceAtLeast(0)
            val header = kotlinx.serialization.json.buildJsonObject {
                if (seq == 0) meta.forEach { (k, v) -> put(k, v) }
                put("sid", kotlinx.serialization.json.JsonPrimitive(sid))
                put("seq", kotlinx.serialization.json.JsonPrimitive(seq))
                put("last", kotlinx.serialization.json.JsonPrimitive(seq == total - 1))
            }
            encode(tag, header.toString(), body, from, len)
        }
    }

    data class Whole(val tag: Int, val meta: kotlinx.serialization.json.JsonObject, val body: ByteArray)

    class Assembler {
        private data class Stream(val meta: kotlinx.serialization.json.JsonObject,
            val parts: ArrayList<ByteArray>, var bytes: Int, var next: Int)
        // LinkedHashMap keeps insertion (age) order so the eldest half-done
        // stream can be evicted; a sender never legitimately needs many open.
        private val streams = LinkedHashMap<String, Stream>()
        private val maxOpenStreams = 16

        fun accept(frame: ByteArray): Whole? {
            val decoded = decode(frame) ?: return null
            val seq = decoded.header.int("seq") ?: return null
            val key = decoded.tag.toString() + ":" + (decoded.header.int("sid") ?: return null)
            var stream = streams[key]
            if (seq == 0) {
                while (streams.size >= maxOpenStreams && !streams.containsKey(key)) {
                    streams.remove(streams.keys.first())
                }
                stream = Stream(decoded.header, ArrayList(), 0, 0)
                streams[key] = stream
            }
            if (stream == null || seq != stream.next) { streams.remove(key); return null }
            stream.next += 1
            stream.bytes += decoded.payload.size
            if (stream.bytes > (MAX_BYTES[decoded.tag] ?: 0)) { streams.remove(key); return null }
            stream.parts.add(decoded.payload)
            if (decoded.header.bool("last") != true) return null
            streams.remove(key)
            val body = ByteArray(stream.bytes)
            var at = 0
            for (part in stream.parts) { part.copyInto(body, at); at += part.size }
            return Whole(decoded.tag, stream.meta, body)
        }
    }
}
