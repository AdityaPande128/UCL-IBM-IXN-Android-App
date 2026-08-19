package com.jarvis.companion.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Hold-to-talk capture: the same shape the Mac's push-to-talk sends — one
// 16 kHz mono 16-bit WAV in a single frame. The daemon's pipeline and
// silence gate stay none the wiser about which device spoke.
class Recorder {

    private var record: AudioRecord? = null
    private var worker: Thread? = null
    private val pcm = ByteArrayOutputStream()
    @Volatile private var running = false

    val isRecording: Boolean get() = running

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val minBuf = AudioRecord.getMinBufferSize(
            16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC, 16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, 8192))
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return false
        }
        pcm.reset()
        record = recorder
        running = true
        recorder.startRecording()
        worker = Thread {
            val buffer = ByteArray(4096)
            while (running) {
                val n = recorder.read(buffer, 0, buffer.size)
                if (n > 0) synchronized(pcm) { pcm.write(buffer, 0, n) }
            }
        }.also { it.start() }
        return true
    }

    fun stop(): ByteArray? {
        if (!running) return null
        running = false
        worker?.join(500)
        worker = null
        record?.let { runCatching { it.stop() }; it.release() }
        record = null
        val data = synchronized(pcm) { pcm.toByteArray() }
        if (data.size < 3200) return null
        return wav(data)
    }

    private fun wav(data: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + data.size)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(16000)
        header.putInt(16000 * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(data.size)
        return header.array() + data
    }
}
