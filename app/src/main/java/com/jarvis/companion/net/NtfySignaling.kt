package com.jarvis.companion.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

// The rendezvous: sealed envelopes on an unguessable topic. The relay reads
// nothing — it shuttles ciphertext between two devices that already share
// the camera-carried secret.
class NtfySignaling(
    private val scope: CoroutineScope,
    secretHex: String,
    private val onSignal: (JsonObject) -> Unit
) {
    private val base = "https://ntfy.sh"
    private val topic = DirectCrypto.topicFor(secretHex)
    private val key = DirectCrypto.keyFor(secretHex)
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var listener: Job? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        listener = scope.launch(Dispatchers.IO) {
            while (running) {
                runCatching { listenOnce() }
                if (running) delay(3000)
            }
        }
    }

    private fun listenOnce() {
        val request = Request.Builder().url("$base/$topic/json?since=45s").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return
            val source = response.body?.source() ?: return
            while (running) {
                val line = source.readUtf8Line() ?: break
                if (line.isBlank()) continue
                val event = runCatching {
                    Json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: continue
                if (event.str("event") != "message") continue
                val raw = event.str("message") ?: continue
                val payload = DirectCrypto.open(key, "phone", raw) ?: continue
                android.util.Log.d("JarvisDirect", "signal in: " + payload.str("kind"))
                onSignal(payload)
            }
        }
    }

    fun publish(payload: JsonObject) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val body = DirectCrypto.seal(key, "phone", payload)
                android.util.Log.d("JarvisDirect", "signal out: " + payload.str("kind"))
                client.newCall(Request.Builder()
                    .url("$base/$topic")
                    .post(body.toRequestBody("text/plain".toMediaType()))
                    .build()).execute().use { }
            }.onFailure { android.util.Log.w("JarvisDirect", "publish failed: " + it.message) }
        }
    }

    fun stop() {
        running = false
        listener?.cancel()
    }
}
