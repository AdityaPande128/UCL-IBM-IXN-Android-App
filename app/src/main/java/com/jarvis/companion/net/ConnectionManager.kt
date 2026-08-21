package com.jarvis.companion.net

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

sealed class ConnState {
    data object Disconnected : ConnState()
    data object Connecting : ConnState()
    data class Live(val via: String) : ConnState()
    data class Unreachable(val hint: String) : ConnState()
    data class PairRequired(val reason: String) : ConnState()
}

data class FetchedFile(val status: Int, val name: String, val mime: String, val bytes: ByteArray)

// The transport ladder: same Wi-Fi first, then a hole punched straight home
// from anywhere, and when a hostile network defeats both, an honest state —
// Telegram still works. Everything above sees one connection either way.
class ConnectionManager(private val scope: CoroutineScope, private val appContext: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    val state = MutableStateFlow<ConnState>(ConnState.Disconnected)
    val events = MutableSharedFlow<JsonObject>(extraBufferCapacity = 256)
    val audio = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    private var host = ""
    private var port = 8080
    private var token = ""
    private var secret = ""
    private var turn = ""
    private val prefs = com.jarvis.companion.Prefs(appContext)
    // The home router's mapped door, learned over sealed signaling; written
    // from the signaling thread, read in the ladder coroutine.
    @Volatile private var endpointHost = ""
    @Volatile private var endpointPort = 0
    @Volatile private var wanted = false
    private var supervisor: Job? = null

    private var lanSocket: WebSocket? = null
    private var direct: DirectLink? = null
    @Volatile private var via = ""
    // The remote rung's sealing key, derived per attempt; null on plain rungs.
    @Volatile private var sealKey: ByteArray? = null
    private var dropped: CompletableDeferred<String>? = null
    // Written from an OkHttp callback thread, read in the ladder coroutine.
    @Volatile private var pairFailure = false

    private val reqCounter = AtomicLong(1)
    private val fileWaiters = HashMap<String, CompletableDeferred<Frames.Whole>>()

    fun start(host: String, port: Int, token: String, secret: String, turn: String = "") {
        val unchanged = this.host == host && this.port == port
            && this.token == token && this.secret == secret && this.turn == turn
        if (wanted && unchanged && supervisor?.isActive == true) return
        stop()
        this.host = host; this.port = port; this.token = token; this.secret = secret
        this.turn = turn
        endpointHost = prefs.endpointHost
        endpointPort = prefs.endpointPort
        wanted = true
        pairFailure = false
        supervisor = scope.launch { ladder() }
    }

    fun stop() {
        wanted = false
        supervisor?.cancel()
        closeLinks()
        state.value = ConnState.Disconnected
    }

    private fun closeLinks() {
        runCatching { lanSocket?.close(1000, "bye") }
        lanSocket = null
        direct?.close()
        direct = null
        via = ""
        sealKey = null
    }

    private val hexSecret = Regex("[0-9a-fA-F]{64}")

    private suspend fun ladder() {
        while (wanted) {
            state.value = ConnState.Connecting
            if (host.isNotBlank() && attemptWs(host, port, "lan")) { awaitDrop(); continue }
            if (pairFailure) return
            // The remembered home-router door: fully direct from anywhere,
            // nothing in the path but the user's own hardware.
            val knownHost = endpointHost
            val knownPort = endpointPort
            if (hexSecret.matches(secret) && knownHost.isNotBlank() && knownPort > 0
                && attemptWs(knownHost, knownPort, "remote")) { awaitDrop(); continue }
            if (pairFailure) return
            // A hand-typed secret that isn't 64 hex chars would throw inside
            // HKDF; gate on shape, not just length, so a typo can't crash the
            // rung — it just skips Direct and lands on the honest state.
            if (hexSecret.matches(secret) && attemptDirect()) { awaitDrop(); continue }
            if (pairFailure) return
            // The failed punch still carried signaling — the Mac may have
            // just taught us a door we didn't know a moment ago.
            if (hexSecret.matches(secret)
                && (endpointHost != knownHost || endpointPort != knownPort)
                && endpointHost.isNotBlank() && endpointPort > 0
                && attemptWs(endpointHost, endpointPort, "remote")) { awaitDrop(); continue }
            if (pairFailure) return
            state.value = ConnState.Unreachable("Mac unreachable — Telegram still works")
            delay(5000)
        }
    }

    private suspend fun awaitDrop() {
        val reason = dropped?.await() ?: return
        closeLinks()
        if (!wanted) return
        state.value = ConnState.Disconnected
        delay(3000)
    }

    private fun markLive(mode: String) {
        via = mode
        state.value = ConnState.Live(mode)
    }

    private fun handleText(text: String) {
        val parsed = runCatching { json.parseToJsonElement(text) as? JsonObject }
            .getOrNull() ?: return
        handleParsed(parsed)
    }

    private fun handleParsed(parsed: JsonObject) {
        if (parsed["type"]?.jsonPrimitive?.contentOrNull == "connected" && via.isNotEmpty()) {
            state.value = ConnState.Live(via)
        }
        events.tryEmit(parsed)
    }

    // The websocket rung, aimed wherever the Mac can be reached — a room
    // away on the LAN, or across the world through its own router. The far
    // door crosses the open internet, so on the "remote" rung every frame
    // in both directions is sealed under the pairing secret — the token,
    // the chats and the files travel only as ciphertext.
    private suspend fun attemptWs(toHost: String, toPort: Int, label: String): Boolean {
        val ready = CompletableDeferred<Boolean>()
        val drop = CompletableDeferred<String>()
        val sealed = label == "remote"
        val key = if (sealed) DirectCrypto.remoteKeyFor(secret) else null
        val assembler = if (sealed) Frames.Assembler() else null
        val seenBin = if (sealed) HashMap<String, Long>() else null
        val request = Request.Builder().url("ws://$toHost:$toPort").build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            // On the sealed rung a 4401 proves nothing until this peer has
            // opened at least one envelope: a stale endpoint may now point
            // at a stranger's daemon, and a stranger must not be able to
            // convince this phone its own pairing died.
            private var sawSealedReply = false
            override fun onOpen(webSocket: WebSocket, response: Response) {
                via = label
                sealKey = key
                val auth = buildJsonObject {
                    put("type", JsonPrimitive("auth"))
                    put("token", JsonPrimitive(token))
                }
                if (key != null) {
                    webSocket.send("{\"type\":\"seal\",\"v\":1}")
                    webSocket.send(DirectCrypto.seal(key, "phone", auth))
                } else {
                    webSocket.send(auth.toString())
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (key != null) {
                    DirectCrypto.open(key, "phone", text)?.let {
                        sawSealedReply = true
                        handleParsed(it)
                    }
                } else {
                    handleText(text)
                }
                if (state.value is ConnState.Live) ready.complete(true)
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (key == null) { audio.tryEmit(bytes.toByteArray()); return }
                val clear = DirectCrypto.openBinary(key, "mac", bytes.toByteArray(), seenBin)
                    ?: return
                sawSealedReply = true
                val whole = assembler?.accept(clear) ?: return
                when (whole.tag) {
                    Frames.TAG_WS_BINARY -> audio.tryEmit(whole.body)
                    Frames.TAG_WS_TEXT -> runCatching {
                        json.parseToJsonElement(String(whole.body, Charsets.UTF_8)) as? JsonObject
                    }.getOrNull()?.let { handleParsed(it) }
                    Frames.TAG_FILE_RES -> {
                        val reqId = whole.meta.str("reqId")
                        val waiter = synchronized(fileWaiters) {
                            reqId?.let { fileWaiters.remove(it) }
                        }
                        waiter?.complete(whole)
                    }
                }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (code == 4401 && (!sealed || sawSealedReply)) {
                    pairFailure = true
                    wanted = false
                    state.value = ConnState.PairRequired("The Mac refused this pairing.")
                }
                ready.complete(false)
                drop.complete("closed $code")
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ready.complete(false)
                drop.complete(t.message ?: "failed")
            }
        })
        lanSocket = socket
        dropped = drop
        val live = withTimeoutOrNull(5000) { ready.await() } == true
        if (!live) { runCatching { socket.cancel() }; lanSocket = null }
        return live
    }

    // Rung two: the hole punch. Signaling is sealed with the pairing secret;
    // the channel is direct; the daemon still demands the token afterwards.
    private suspend fun attemptDirect(): Boolean {
        val ready = CompletableDeferred<Boolean>()
        val drop = CompletableDeferred<String>()
        val link = DirectLink(
            appContext, scope, secret, turn,
            onOpen = {
                via = "direct"
                sendRaw(buildJsonObject {
                    put("type", JsonPrimitive("auth"))
                    put("token", JsonPrimitive(token))
                }.toString())
            },
            onText = { text ->
                handleText(text)
                if (state.value is ConnState.Live) ready.complete(true)
            },
            onBinary = { bytes -> audio.tryEmit(bytes) },
            onClosed = {
                ready.complete(false)
                drop.complete("channel closed")
            })
        direct = link
        dropped = drop
        link.onEndpoint = { learnedHost, learnedPort ->
            endpointHost = learnedHost
            endpointPort = learnedPort
            prefs.endpointHost = learnedHost
            prefs.endpointPort = learnedPort
        }
        link.onFileResponse = { whole ->
            val reqId = whole.meta.str("reqId")
            val waiter = synchronized(fileWaiters) { reqId?.let { fileWaiters.remove(it) } }
            waiter?.complete(whole)
        }
        link.connect(appContext)
        val live = withTimeoutOrNull(25000) { ready.await() } == true
        if (!live) { link.close(); direct = null }
        return live
    }

    private fun sendRaw(text: String): Boolean = when (via) {
        "lan" -> lanSocket?.send(text) ?: false
        "remote" -> {
            val key = sealKey
            val payload = runCatching {
                json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            if (key == null || payload == null) false
            else lanSocket?.send(DirectCrypto.seal(key, "phone", payload)) ?: false
        }
        "direct" -> direct?.sendText(text) ?: false
        else -> false
    }

    fun send(payload: JsonObject): Boolean = sendRaw(payload.toString())

    fun sendBinary(bytes: ByteArray): Boolean = when (via) {
        "lan" -> lanSocket?.send(bytes.toByteString()) ?: false
        "remote" -> sendSealedFrames(Frames.TAG_WS_BINARY, buildJsonObject { }, bytes)
        "direct" -> direct?.sendWsBinary(bytes) ?: false
        else -> false
    }

    private fun sendSealedFrames(tag: Int, meta: JsonObject, body: ByteArray): Boolean {
        val key = sealKey ?: return false
        val socket = lanSocket ?: return false
        val stamped = buildJsonObject {
            meta.forEach { (k, v) -> put(k, v) }
            put("sid", JsonPrimitive(reqCounter.getAndIncrement().toInt()))
        }
        for (frame in Frames.chunks(tag, stamped, body)) {
            if (!socket.send(DirectCrypto.sealBinary(key, "phone", frame).toByteString())) {
                return false
            }
        }
        return true
    }

    // The file lane rides HTTP on the LAN and framed channel messages on
    // every sealed or punched rung — same daemon routes, same caps.
    suspend fun uploadFile(name: String, bytes: ByteArray): JsonObject =
        withContext(Dispatchers.IO) {
            if (via == "direct" || via == "remote") {
                val whole = fileRoundTrip(buildJsonObject {
                    put("op", JsonPrimitive("put"))
                    put("name", JsonPrimitive(name))
                }, bytes) ?: throw IllegalStateException("no reply from the Mac")
                val status = whole.meta.int("status") ?: 0
                val body = whole.meta.str("json") ?: "{}"
                if (status != 200) throw IllegalStateException("upload refused ($status)")
                (Json.parseToJsonElement(body) as? JsonObject)
                    ?: throw IllegalStateException("malformed reply")
            } else {
                val request = Request.Builder()
                    .url("http://$host:$port/files")
                    .header("Authorization", "Bearer $token")
                    .header("x-filename", android.net.Uri.encode(name))
                    .put(bytes.toRequestBody("application/octet-stream".toMediaType()))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful)
                        throw IllegalStateException("upload refused (${response.code})")
                    (Json.parseToJsonElement(response.body?.string() ?: "{}") as? JsonObject)
                        ?: throw IllegalStateException("malformed reply")
                }
            }
        }

    suspend fun downloadFile(id: String, name: String): FetchedFile =
        withContext(Dispatchers.IO) {
            if (via == "direct" || via == "remote") {
                val whole = fileRoundTrip(buildJsonObject {
                    put("op", JsonPrimitive("get"))
                    put("id", JsonPrimitive(id))
                }, ByteArray(0)) ?: throw IllegalStateException("no reply from the Mac")
                FetchedFile(
                    status = whole.meta.int("status") ?: 0,
                    name = name,
                    mime = whole.meta.str("mime") ?: "application/octet-stream",
                    bytes = whole.body)
            } else {
                val request = Request.Builder()
                    .url("http://$host:$port/files/$id")
                    .header("Authorization", "Bearer $token")
                    .build()
                client.newCall(request).execute().use { response ->
                    FetchedFile(
                        status = response.code,
                        name = name,
                        mime = response.header("Content-Type") ?: "application/octet-stream",
                        bytes = response.body?.bytes() ?: ByteArray(0))
                }
            }
        }

    private suspend fun fileRoundTrip(meta: JsonObject, body: ByteArray): Frames.Whole? {
        val reqId = "r" + reqCounter.getAndIncrement()
        val waiter = CompletableDeferred<Frames.Whole>()
        synchronized(fileWaiters) { fileWaiters[reqId] = waiter }
        val stamped = buildJsonObject {
            meta.forEach { (k, v) -> put(k, v) }
            put("reqId", JsonPrimitive(reqId))
        }
        val dispatched = when (via) {
            "direct" -> direct?.sendFileRequest(stamped, body) ?: false
            "remote" -> sendSealedFrames(Frames.TAG_FILE_REQ, stamped, body)
            else -> false
        }
        if (!dispatched) {
            synchronized(fileWaiters) { fileWaiters.remove(reqId) }
            return null
        }
        return withTimeoutOrNull(60000) { waiter.await() }
            .also { if (it == null) synchronized(fileWaiters) { fileWaiters.remove(reqId) } }
    }
}
