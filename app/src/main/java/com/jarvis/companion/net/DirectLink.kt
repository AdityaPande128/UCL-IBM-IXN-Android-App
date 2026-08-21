package com.jarvis.companion.net

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer

// The hole punch: offer through the sealed rendezvous, ICE via public STUN,
// then a direct DTLS channel phone-to-Mac that no relay ever carries. The
// channel presents the same face as a websocket to everything above it.
class DirectLink(
    context: Context,
    scope: CoroutineScope,
    secretHex: String,
    private val turnUris: String = "",
    private val onOpen: () -> Unit,
    private val onText: (String) -> Unit,
    private val onBinary: (ByteArray) -> Unit,
    private val onClosed: () -> Unit
) {
    companion object {
        private var factory: PeerConnectionFactory? = null
        fun sharedFactory(context: Context): PeerConnectionFactory {
            factory?.let { return it }
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .createInitializationOptions())
            return PeerConnectionFactory.builder().createPeerConnectionFactory()
                .also { factory = it }
        }
    }

    private val assembler = Frames.Assembler()
    private var nextSid = 1
    private var pc: PeerConnection? = null
    private var channel: DataChannel? = null
    @Volatile private var alive = true

    private val signaling = NtfySignaling(scope, secretHex) { signal ->
        when (signal.str("kind")) {
            "answer" -> signal.str("sdp")?.let { sdp ->
                pc?.setRemoteDescription(sdpObserver,
                    SessionDescription(SessionDescription.Type.ANSWER, sdp))
            }
            "candidate" -> signal.str("candidate")?.let { candidate ->
                pc?.addIceCandidate(IceCandidate(signal.str("mid") ?: "0", 0, candidate))
            }
            // The Mac announcing its router's mapped door: a fully direct
            // TCP path for when the punch cannot land.
            "endpoint" -> {
                val host = signal.str("host")
                val port = signal.int("port")
                if (host != null && port != null) onEndpoint?.invoke(host, port)
            }
        }
    }

    var onEndpoint: ((String, Int) -> Unit)? = null

    private val sdpObserver = object : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(reason: String?) { close() }
        override fun onSetFailure(reason: String?) {}
    }

    fun connect(context: Context) {
        // Listen before speaking: the Mac answers within a second, and an
        // offer published before our stream is up loses that answer forever.
        signaling.start()
        val servers = mutableListOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer())
        // Relay entries come through the pairing QR as turn:user:pass@host:port
        // URIs — the rung that still connects when the punch cannot land.
        for (uri in turnUris.split(',')) {
            val trimmed = uri.trim()
            if (trimmed.isEmpty()) continue
            val m = Regex("^(turns?):([^:@]+):([^@]+)@(.+)$").find(trimmed)
            servers += if (m != null) {
                val (scheme, user, pass, rest) = m.destructured
                PeerConnection.IceServer.builder("$scheme:$rest")
                    .setUsername(user).setPassword(pass).createIceServer()
            } else PeerConnection.IceServer.builder(trimmed).createIceServer()
        }
        val config = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val peer = sharedFactory(context).createPeerConnection(config,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    signaling.publish(buildJsonObject {
                        put("kind", JsonPrimitive("candidate"))
                        put("candidate", JsonPrimitive(candidate.sdp))
                        put("mid", JsonPrimitive(candidate.sdpMid ?: "0"))
                    })
                }
                override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                    android.util.Log.d("JarvisDirect", "pc state: " + state)
                    if (state == PeerConnection.PeerConnectionState.FAILED
                        || state == PeerConnection.PeerConnectionState.CLOSED) close()
                }
                override fun onDataChannel(dc: DataChannel) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onAddStream(stream: MediaStream?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onRenegotiationNeeded() {}
            }) ?: return close()
        pc = peer

        val dc = peer.createDataChannel("jarvis", DataChannel.Init())
        channel = dc
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previous: Long) {}
            override fun onStateChange() {
                android.util.Log.d("JarvisDirect", "dc state: " + dc.state())
                when (dc.state()) {
                    DataChannel.State.OPEN -> onOpen()
                    DataChannel.State.CLOSED -> close()
                    else -> {}
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                if (!buffer.binary) return onText(String(bytes, Charsets.UTF_8))
                val whole = assembler.accept(bytes) ?: return
                when (whole.tag) {
                    Frames.TAG_WS_BINARY -> onBinary(whole.body)
                    Frames.TAG_WS_TEXT -> onText(String(whole.body, Charsets.UTF_8))
                    Frames.TAG_FILE_RES -> onFileResponse?.invoke(whole)
                    else -> {}
                }
            }
        })

        peer.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                peer.setLocalDescription(sdpObserver, desc)
                signaling.publish(buildJsonObject {
                    put("kind", JsonPrimitive("offer"))
                    put("sdp", JsonPrimitive(desc.description))
                })
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(reason: String?) { close() }
            override fun onSetFailure(reason: String?) {}
        }, MediaConstraints())
    }

    var onFileResponse: ((Frames.Whole) -> Unit)? = null

    fun sendText(text: String): Boolean {
        val dc = channel ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false
        return dc.send(DataChannel.Buffer(
            ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8)), false))
    }

    fun sendWsBinary(bytes: ByteArray): Boolean = sendFrames(Frames.TAG_WS_BINARY,
        buildJsonObject { put("sid", JsonPrimitive(nextSid++)) }, bytes)

    fun sendFileRequest(meta: JsonObject, body: ByteArray): Boolean {
        val stamped = buildJsonObject {
            meta.forEach { (k, v) -> put(k, v) }
            put("sid", JsonPrimitive(nextSid++))
        }
        return sendFrames(Frames.TAG_FILE_REQ, stamped, body)
    }

    private fun sendFrames(tag: Int, meta: JsonObject, body: ByteArray): Boolean {
        val dc = channel ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false
        for (frame in Frames.chunks(tag, meta, body)) {
            if (!dc.send(DataChannel.Buffer(ByteBuffer.wrap(frame), true))) return false
        }
        return true
    }

    fun close() {
        if (!alive) return
        alive = false
        signaling.stop()
        runCatching { channel?.close() }
        runCatching { pc?.close() }
        channel = null
        pc = null
        onClosed()
    }
}
