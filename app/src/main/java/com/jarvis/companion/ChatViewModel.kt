package com.jarvis.companion

import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import com.jarvis.companion.audio.Recorder
import com.jarvis.companion.audio.Speaker
import com.jarvis.companion.net.ConnState
import com.jarvis.companion.net.ConnectionManager
import com.jarvis.companion.net.FileRef
import com.jarvis.companion.net.artifactFiles
import com.jarvis.companion.net.arr
import com.jarvis.companion.net.bool
import com.jarvis.companion.net.int
import com.jarvis.companion.net.msg
import com.jarvis.companion.net.obj
import com.jarvis.companion.net.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID

data class ChatItem(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val text: String,
    val files: List<FileRef> = emptyList()
)

data class ConvRow(val id: Int, val title: String)

data class ProposalUi(
    val id: String,
    val summary: String,
    val will: String,
    val estimate: String
)

data class PendingUpload(val id: String, val name: String)

val KOKORO_VOICES = listOf(
    "af_heart" to "Heart — US female",
    "af_bella" to "Bella — US female",
    "af_nicole" to "Nicole — US female",
    "af_sky" to "Sky — US female",
    "am_adam" to "Adam — US male",
    "am_michael" to "Michael — US male",
    "bf_emma" to "Emma — UK female",
    "bf_isabella" to "Isabella — UK female",
    "bm_george" to "George — UK male",
    "bm_daniel" to "Daniel — UK male"
)

// The phone's whole model of the world: one connection, one transcript, the
// sidebar list, and whatever question Jarvis is currently asking. The Mac's
// daemon stays the source of truth; this class just keeps up.
private const val PRIVATE_BANNER = "Private mode: nothing you say here will be remembered."

class ChatViewModel(private val app: Application, private val prefs: Prefs) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val conn = ConnectionManager(scope, app)
    private val speaker = Speaker(app, scope)
    private val recorder = Recorder()

    val items = mutableStateListOf<ChatItem>()
    val conversations = mutableStateListOf<ConvRow>()
    val activeConversation = mutableStateOf<Int?>(null)
    val privateChat = mutableStateOf(false)
    val proposal = mutableStateOf<ProposalUi?>(null)
    val busy = mutableStateOf(false)
    val busyLine = mutableStateOf("")
    val recording = mutableStateOf(false)
    val pendingUploads = mutableStateListOf<PendingUpload>()
    val imagePreviews = mutableStateMapOf<String, Bitmap>()
    val voiceReady = mutableStateOf(false)
    val macTheme = mutableStateOf("dark")
    val macName = mutableStateOf("")
    val currentVoice = mutableStateOf("af_heart")
    val toast = mutableStateOf<String?>(null)

    init {
        scope.launch {
            conn.state.collect { state ->
                if (state is ConnState.Live) afterConnect()
                if (state !is ConnState.Live) { busy.value = false; busyLine.value = "" }
            }
        }
        scope.launch { conn.events.collect { handle(it) } }
        scope.launch { conn.audio.collect { speaker.enqueue(it) } }
    }

    fun connect() {
        if (prefs.paired) conn.start(prefs.host, prefs.port, prefs.token, prefs.secret, prefs.turn)
    }

    // Messages written while the link was down wait here and go out the
    // moment it returns — nothing typed ever just sits and dies.
    private val outbox = ArrayDeque<JsonObject>()

    private fun afterConnect() {
        if (prefs.speakReplies) conn.send(msg("speak_replies", "on" to true))
        conn.send(msg("onboarding"))
        // Flush before selecting: each queued message names its own chat, the
        // daemon records it, and the snapshot that follows already shows it.
        while (outbox.isNotEmpty()) {
            val queued = outbox.first()
            if (!conn.send(queued)) break
            outbox.removeFirst()
            if (queued.str("type") == "intent") {
                busy.value = true
                busyLine.value = "Thinking…"
            }
        }
        conn.send(msg("conversations_list"))
        if (privateChat.value) conn.send(msg("private_chat", "on" to true))
        else activeConversation.value?.let { conn.send(msg("conversation_select", "id" to it)) }
    }

    private fun handle(event: JsonObject) {
        when (event.str("type")) {
            "onboarding_result" -> {
                voiceReady.value = event.bool("voice_ready") == true
                val profile = event.obj("profile") ?: return
                macTheme.value = profile.str("theme") ?: "dark"
                macName.value = profile.str("name") ?: ""
                currentVoice.value = profile.obj("voice")?.str("voice") ?: "af_heart"
            }
            // Another surface changed the shared profile; this one follows.
            "profile_changed" -> {
                val profile = event.obj("profile") ?: return
                profile.str("theme")?.let { macTheme.value = it }
                profile.str("name")?.let { macName.value = it }
                profile.obj("voice")?.str("voice")?.let { currentVoice.value = it }
            }
            "conversations_result" -> {
                conversations.clear()
                event.arr("conversations")?.forEach { row ->
                    val convo = row as? JsonObject ?: return@forEach
                    val id = convo.int("id") ?: return@forEach
                    conversations.add(ConvRow(id, convo.str("title") ?: "Chat"))
                }
            }
            "conversation_started" -> {
                val id = event.int("id") ?: return
                activeConversation.value = id
                conversations.removeAll { it.id == id }
                conversations.add(0, ConvRow(id, event.str("title") ?: "Chat"))
            }
            "conversation_messages" -> {
                activeConversation.value = event.int("id")
                items.clear()
                event.arr("messages")?.forEach { row ->
                    val message = row as? JsonObject ?: return@forEach
                    items.add(ChatItem(
                        role = message.str("role") ?: "system",
                        text = message.str("text") ?: "",
                        files = artifactFiles(message.obj("artifacts"))))
                }
            }
            "conversation_event" -> {
                val convo = event.obj("conversation") ?: return
                val id = convo.int("id") ?: return
                when (event.str("kind")) {
                    "started" -> {
                        conversations.removeAll { it.id == id }
                        conversations.add(0, ConvRow(id, convo.str("title") ?: "Chat"))
                        appendRemote(event, id)
                    }
                    "message" -> {
                        val known = conversations.find { it.id == id }
                        if (known != null) {
                            conversations.remove(known)
                            conversations.add(0, known)
                        }
                        appendRemote(event, id)
                    }
                    "proposal" -> event.obj("proposal")?.let { setProposal(it) }
                    "deleted" -> {
                        conversations.removeAll { it.id == id }
                        if (activeConversation.value == id) {
                            activeConversation.value = null
                            items.clear()
                        }
                    }
                    "busy" -> if (activeConversation.value == id) {
                        busy.value = event.bool("busy") == true
                        if (busy.value) busyLine.value = "Working on another surface…"
                        else busyLine.value = ""
                    }
                }
            }
            "intent_accepted" -> { busy.value = true; busyLine.value = "Thinking…" }
            "intent_result" -> {
                busy.value = false
                busyLine.value = ""
                // A reply born in another chat stays there; the store has it
                // and switching back shows it. Only this chat's replies land
                // in this transcript.
                val home = event.int("conversation")
                if (home == null || home == activeConversation.value) {
                    val text = event.str("response") ?: event.str("error") ?: "No response."
                    val role = if (event.str("status") == "error") "error" else "assistant"
                    items.add(ChatItem(role = role, text = text,
                        files = artifactFiles(event.obj("artifacts"))))
                }
                event.obj("proposal")?.let { setProposal(it) }
            }
            "stt_result" -> items.add(ChatItem(role = "user", text = event.str("text") ?: ""))
            "speak_start" -> speaker.begin()
            "proposal_taken" -> { proposal.value = null; speaker.stop() }
            "activity" -> {
                val stage = event.str("stage") ?: event.str("event") ?: return
                busyLine.value = describeActivity(event.str("source") ?: "", stage)
            }
            "pipeline_error" -> {
                busy.value = false
                items.add(ChatItem(role = "error", text = event.str("error") ?: "Voice failed."))
            }
            "speech_unavailable" -> items.add(
                ChatItem(role = "system", text = event.str("message") ?: "Text only."))
            "error" -> {
                busy.value = false
                items.add(ChatItem(role = "error", text = event.str("error") ?: "Something failed."))
            }
        }
    }

    private fun appendRemote(event: JsonObject, id: Int) {
        if (activeConversation.value != id) return
        val message = event.obj("message") ?: return
        items.add(ChatItem(
            role = message.str("role") ?: "system",
            text = message.str("text") ?: "",
            files = artifactFiles(message.obj("artifacts"))))
    }

    private fun setProposal(raw: JsonObject) {
        proposal.value = ProposalUi(
            id = raw.str("id") ?: return,
            summary = raw.str("summary") ?: "Jarvis wants to do something new.",
            will = raw.str("will") ?: "",
            estimate = raw.str("estimate") ?: "")
    }

    private fun describeActivity(source: String, stage: String): String = when {
        source == "router" -> "Deciding what this needs…"
        source == "planner" -> "Planning the steps…"
        source == "plan" -> "Running the plan…"
        source == "generator" && stage == "verifying" -> "Verifying the new skill…"
        source == "generator" -> "Writing a new skill…"
        source == "skill" -> "Running the skill…"
        else -> "Working…"
    }

    fun sendIntent(text: String) {
        if (text.isBlank()) return
        val ids = pendingUploads.map { it.id }
        items.add(ChatItem(role = "user", text = text,
            files = pendingUploads.map { FileRef(it.id, it.name, null) }))
        val payload = msg("intent", "text" to text,
            "attachments" to (ids.ifEmpty { null }),
            "conversation" to activeConversation.value)
        pendingUploads.clear()
        if (conn.send(payload)) {
            busy.value = true
            busyLine.value = "Thinking…"
        } else {
            outbox.add(payload)
            busy.value = true
            busyLine.value = "Waiting for the connection…"
        }
    }

    fun approve(id: String, yes: Boolean) {
        proposal.value = null
        // The decision outranks the question: stop reading it out.
        speaker.stop()
        conn.send(msg("approval", "id" to id, "decision" to if (yes) "yes" else "no"))
    }

    fun abort() {
        conn.send(msg("abort"))
        outbox.clear()
        busy.value = false
        busyLine.value = ""
    }

    fun newChat() {
        endPrivateChat()
        activeConversation.value = null
        items.clear()
        conn.send(msg("conversation_select", "id" to null))
    }

    fun selectConversation(id: Int) {
        endPrivateChat()
        conn.send(msg("conversation_select", "id" to id))
    }

    fun startPrivateChat() {
        activeConversation.value = null
        proposal.value = null
        items.clear()
        items.add(ChatItem(role = "system", text = PRIVATE_BANNER))
        privateChat.value = true
        conn.send(msg("private_chat", "on" to true))
    }

    fun endPrivateChat() {
        if (!privateChat.value) return
        privateChat.value = false
        items.clear()
        conn.send(msg("private_chat", "on" to false))
    }

    fun setSpeakReplies(on: Boolean) {
        prefs.speakReplies = on
        conn.send(msg("speak_replies", "on" to on))
    }

    fun setVoice(voice: String) {
        currentVoice.value = voice
        val payload = msg("profile_update", "voice" to buildJsonObject {
            put("enabled", JsonPrimitive(true))
            put("tts", JsonPrimitive(true))
            put("voice", JsonPrimitive(voice))
        })
        if (!conn.send(payload)) {
            outbox.add(payload)
            toast.value = "Not connected — the voice change waits for the link."
        }
    }

    fun startRecording(): Boolean {
        val ok = recorder.start()
        recording.value = ok
        return ok
    }

    fun stopRecording(send: Boolean) {
        val wav = recorder.stop()
        recording.value = false
        if (send && wav != null) {
            if (conn.sendBinary(wav)) {
                busy.value = true
                busyLine.value = "Listening back…"
            } else {
                toast.value = "No connection — the voice note wasn't sent."
            }
        }
    }

    fun upload(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            try {
                val resolver = app.contentResolver
                val name = resolver.query(uri, null, null, null, null)?.use { cursor ->
                    val at = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && at >= 0) cursor.getString(at) else null
                } ?: "upload"
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("could not read the file")
                val reply = conn.uploadFile(name, bytes)
                val id = reply.str("id") ?: throw IllegalStateException("no id back")
                withContext(Dispatchers.Main) {
                    pendingUploads.add(PendingUpload(id, reply.str("name") ?: name))
                }
            } catch (err: Exception) {
                withContext(Dispatchers.Main) { toast.value = "Upload failed: " + err.message }
            }
        }
    }

    private val savedFiles = mutableMapOf<String, Uri?>()

    fun download(file: FileRef, open: Boolean) {
        val id = file.id ?: run { toast.value = "That file has no handle."; return }
        // Already fetched this session: open it again, don't mint a copy.
        if (savedFiles.containsKey(id)) {
            val uri = savedFiles[id]
            if (open && uri != null) openSaved(uri, file.name)
            else toast.value = file.name + " is already in Downloads."
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val fetched = conn.downloadFile(id, file.name)
                if (fetched.status != 200) throw IllegalStateException(
                    "fetch refused (" + fetched.status + ")")
                if (fetched.mime.startsWith("image/")) {
                    val bitmap = BitmapFactory.decodeByteArray(fetched.bytes, 0, fetched.bytes.size)
                    if (bitmap != null) withContext(Dispatchers.Main) {
                        imagePreviews[id] = bitmap
                    }
                }
                val uri = saveToDownloads(file.name, fetched.mime, fetched.bytes)
                withContext(Dispatchers.Main) {
                    savedFiles[id] = uri
                    toast.value = "Saved " + file.name
                    if (open && uri != null && !fetched.mime.startsWith("image/")) {
                        openSaved(uri, file.name)
                    }
                }
            } catch (err: Exception) {
                withContext(Dispatchers.Main) { toast.value = "Download failed: " + err.message }
            }
        }
    }

    private fun openSaved(uri: Uri, name: String) {
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, app.contentResolver.getType(uri) ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(Intent.createChooser(view, name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun saveToDownloads(name: String, mime: String, bytes: ByteArray): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = app.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } else {
            val dir = app.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                ?: return null
            val out = java.io.File(dir, name)
            out.writeBytes(bytes)
            runCatching {
                androidx.core.content.FileProvider.getUriForFile(
                    app, app.packageName + ".files", out)
            }.getOrNull()
        }
    }

    fun shutdown() {
        conn.stop()
        speaker.release()
    }
}
