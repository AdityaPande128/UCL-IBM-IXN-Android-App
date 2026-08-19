package com.jarvis.companion.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

sealed class ConnState {
    data object Disconnected : ConnState()
    data object Connecting : ConnState()
    data object Live : ConnState()
    data class PairRequired(val reason: String) : ConnState()
}

// One websocket to the Mac, kept honest: a ping every 20 s so cellular can't
// hold a dead socket open, a flat 3 s retry like the Mac client, and a 4401
// treated as "re-pair", never retried into a loop.
class ConnectionManager(private val scope: CoroutineScope) {

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    val state = MutableStateFlow<ConnState>(ConnState.Disconnected)
    val events = MutableSharedFlow<JsonObject>(extraBufferCapacity = 256)
    val audio = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    private var socket: WebSocket? = null
    private var wanted = false
    private var retry: Job? = null
    private var host = ""
    private var port = 8080
    private var token = ""

    val httpBase: String get() = "http://" + host + ":" + port
    val bearer: String get() = token

    fun start(host: String, port: Int, token: String) {
        this.host = host; this.port = port; this.token = token
        wanted = true
        open()
    }

    fun stop() {
        wanted = false
        retry?.cancel()
        socket?.close(1000, "bye")
        socket = null
        state.value = ConnState.Disconnected
    }

    private fun open() {
        if (!wanted) return
        state.value = ConnState.Connecting
        val request = Request.Builder().url("ws://" + host + ":" + port).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(buildJsonObject {
                    put("type", kotlinx.serialization.json.JsonPrimitive("auth"))
                    put("token", kotlinx.serialization.json.JsonPrimitive(token))
                }.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val parsed = runCatching { json.parseToJsonElement(text) as? JsonObject }
                    .getOrNull() ?: return
                val type = parsed["type"]?.jsonPrimitive?.contentOrNull
                if (type == "connected") state.value = ConnState.Live
                events.tryEmit(parsed)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                audio.tryEmit(bytes.toByteArray())
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handleDown(code)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                handleDown(null)
            }
        })
    }

    private fun handleDown(code: Int?) {
        socket = null
        if (code == 4401) {
            wanted = false
            state.value = ConnState.PairRequired("The Mac refused this pairing.")
            return
        }
        if (!wanted) { state.value = ConnState.Disconnected; return }
        state.value = ConnState.Disconnected
        retry?.cancel()
        retry = scope.launch {
            delay(3000)
            open()
        }
    }

    fun send(payload: JsonObject): Boolean =
        socket?.send(payload.toString()) ?: false

    fun sendBinary(bytes: ByteArray): Boolean =
        socket?.send(bytes.toByteString()) ?: false
}
