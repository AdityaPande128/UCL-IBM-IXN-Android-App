package com.jarvis.companion.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

// The talk-back half: TTS arrives as one WAV per sentence, each chunk at
// whatever rate the model spoke it (Kokoro is 24 kHz, the header is the
// truth). Chunks queue and play in order; music ducks while Jarvis talks.
class Speaker(context: Context, scope: CoroutineScope) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val queue = Channel<ByteArray>(capacity = 64)
    private var track: AudioTrack? = null
    private var trackRate = 0
    private var focus: AudioFocusRequest? = null
    // Barge-in: answering a question mid-sentence silences the rest of it.
    @Volatile private var interrupted = false

    init {
        scope.launch(Dispatchers.IO) {
            for (wav in queue) {
                if (interrupted) continue
                val parsed = parseWav(wav) ?: continue
                play(parsed)
                if (queue.isEmpty) dropFocus()
            }
        }
    }

    // A fresh utterance began on the Mac; chunks may play again. Arriving
    // chunks alone never clear the interrupt — a silenced stream's stragglers
    // stay silenced.
    fun begin() {
        interrupted = false
    }

    fun enqueue(wav: ByteArray) {
        queue.trySend(wav)
    }

    fun stop() {
        interrupted = true
        while (queue.tryReceive().isSuccess) { /* drain what was queued */ }
        runCatching { track?.pause(); track?.flush() }
        dropFocus()
    }

    private data class Pcm(val rate: Int, val channels: Int, val data: ByteArray, val offset: Int, val length: Int)

    private fun parseWav(bytes: ByteArray): Pcm? {
        if (bytes.size < 44) return null
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes[0] != 'R'.code.toByte() || bytes[1] != 'I'.code.toByte()) return null
        val channels = header.getShort(22).toInt()
        val rate = header.getInt(24)
        // Chunks after fmt can vary; walk to the data chunk instead of
        // assuming offset 44.
        var at = 12
        while (at + 8 <= bytes.size) {
            val id = String(bytes, at, 4, Charsets.US_ASCII)
            val size = header.getInt(at + 4)
            if (size < 0 || size > bytes.size) return null
            if (id == "data") {
                val start = at + 8
                val length = minOf(size, bytes.size - start)
                if (length <= 0) return null
                return Pcm(rate, channels.coerceIn(1, 2), bytes, start, length)
            }
            at += 8 + size + (size % 2)
        }
        return null
    }

    private fun grabFocus() {
        if (focus != null) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            ).build()
        audioManager.requestAudioFocus(request)
        focus = request
    }

    private fun dropFocus() {
        focus?.let { audioManager.abandonAudioFocusRequest(it) }
        focus = null
    }

    private fun play(pcm: Pcm) {
        grabFocus()
        if (track == null || trackRate != pcm.rate) {
            track?.release()
            val channelMask = if (pcm.channels == 2)
                AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val minBuf = AudioTrack.getMinBufferSize(
                pcm.rate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(pcm.rate)
                        .setChannelMask(channelMask)
                        .build())
                .setBufferSizeInBytes(maxOf(minBuf, 16384))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            trackRate = pcm.rate
        }
        if (track?.playState != AudioTrack.PLAYSTATE_PLAYING) runCatching { track?.play() }
        var written = 0
        while (written < pcm.length && !interrupted) {
            val n = track?.write(pcm.data, pcm.offset + written, pcm.length - written) ?: break
            if (n <= 0) break
            written += n
        }
    }

    fun release() {
        track?.release()
        track = null
        dropFocus()
    }
}
