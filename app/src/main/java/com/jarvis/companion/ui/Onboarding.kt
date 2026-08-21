package com.jarvis.companion.ui

import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.companion.ChatViewModel
import com.jarvis.companion.Prefs
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

data class PairTarget(val host: String, val port: Int, val token: String, val secret: String,
    val turn: String = "")

// The QR carries {"host","port","token"}; a jarvis://pair URL works too, and
// so do thumbs — everything can be typed by hand.
fun parsePairPayload(raw: String): PairTarget? {
    val text = raw.trim()
    if (text.startsWith("{")) {
        return runCatching {
            val parsed = Json.parseToJsonElement(text) as JsonObject
            PairTarget(
                host = parsed["host"]!!.jsonPrimitive.contentOrNull!!,
                port = parsed["port"]?.jsonPrimitive?.intOrNull ?: 8080,
                token = parsed["token"]!!.jsonPrimitive.contentOrNull!!,
                secret = parsed["secret"]?.jsonPrimitive?.contentOrNull ?: "",
                turn = parsed["turn"]?.jsonPrimitive?.contentOrNull ?: "")
        }.getOrNull()
    }
    if (text.startsWith("jarvis://")) {
        val uri = Uri.parse(text)
        val host = uri.getQueryParameter("host") ?: return null
        val token = uri.getQueryParameter("token") ?: return null
        return PairTarget(host, uri.getQueryParameter("port")?.toIntOrNull() ?: 8080,
            token, uri.getQueryParameter("secret") ?: "", uri.getQueryParameter("turn") ?: "")
    }
    return null
}

// The trial climbs the same ladder the app lives on: LAN first, then a
// punched channel. Passing proves the pairing end to end before saving.
suspend fun trialConnect(context: android.content.Context, target: PairTarget): String? {
    val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    val probe = com.jarvis.companion.net.ConnectionManager(scope, context.applicationContext)
    return try {
        probe.start(target.host, target.port, target.token, target.secret, target.turn)
        val outcome = withTimeoutOrNull(30000) {
            probe.state.first { state ->
                state is com.jarvis.companion.net.ConnState.Live
                    || state is com.jarvis.companion.net.ConnState.PairRequired
                    || state is com.jarvis.companion.net.ConnState.Unreachable
            }
        }
        when (outcome) {
            is com.jarvis.companion.net.ConnState.Live -> null
            is com.jarvis.companion.net.ConnState.PairRequired -> "The Mac refused this token."
            is com.jarvis.companion.net.ConnState.Unreachable ->
                "Couldn't reach your Mac. Check it's awake with Jarvis open — " +
                    "the same network is the surest path."
            else -> "Timed out. Is your Mac awake with Jarvis open?"
        }
    } finally {
        probe.stop()
        scope.cancel()
    }
}

@Composable
fun OnboardingFlow(
    vm: ChatViewModel,
    prefs: Prefs,
    themePref: MutableState<String>,
    canLock: Boolean,
    lockEnrolled: MutableState<Boolean>,
    onScanRequest: () -> Unit,
    scannedPayload: MutableState<String?>,
    onTryLock: () -> Unit,
    onDone: () -> Unit
) {
    var step by remember { mutableStateOf(if (prefs.paired) "theme" else "pair") }
    var pendingTarget by remember { mutableStateOf<PairTarget?>(null) }
    var pairError by remember { mutableStateOf<String?>(null) }
    // Once paired, the wizard can already talk to the Mac: the theme step's
    // "Match my Mac" and the hello's name both come from the live profile.
    LaunchedEffect(step) { if (step != "pair" && step != "connecting") vm.connect() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        when (step) {
            "pair" -> PairStep(prefs, pairError, onScanRequest, scannedPayload,
                onInvalid = { pairError = it },
                onTarget = { target ->
                    pairError = null
                    pendingTarget = target
                    step = "connecting"
                })
            "connecting" -> ConnectingStep(pendingTarget ?: return@Box) { failure ->
                val target = pendingTarget ?: return@ConnectingStep
                if (failure == null) {
                    prefs.host = target.host
                    prefs.port = target.port
                    prefs.token = target.token
                    prefs.secret = target.secret
                    prefs.turn = target.turn
                    step = "theme"
                } else {
                    pairError = failure
                    step = "pair"
                }
            }
            "theme" -> ThemeStep(prefs, themePref) { step = "lock" }
            "lock" -> LockStep(prefs, canLock, lockEnrolled, onTryLock) { step = "hello" }
            "hello" -> HelloStep(vm) { onDone() }
        }
    }
}

@Composable
private fun StepFrame(title: String, sub: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(28.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground)
        Text(sub, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, bottom = 22.dp))
        content()
    }
}

@Composable
private fun PairStep(
    prefs: Prefs,
    error: String?,
    onScanRequest: () -> Unit,
    scannedPayload: MutableState<String?>,
    onInvalid: (String) -> Unit,
    onTarget: (PairTarget) -> Unit
) {
    var manual by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf(prefs.host) }
    var port by remember { mutableStateOf(prefs.port.toString()) }
    var token by remember { mutableStateOf(prefs.token) }
    var secret by remember { mutableStateOf(prefs.secret) }

    LaunchedEffect(scannedPayload.value) {
        val raw = scannedPayload.value ?: return@LaunchedEffect
        scannedPayload.value = null
        val target = parsePairPayload(raw)
        if (target == null) onInvalid("That code isn't a Jarvis pairing code — try again.")
        else onTarget(target)
    }

    StepFrame("Pair with Your Mac", "Don't have Jarvis? Get the Mac App!") {
        InstructionRow("1", "Open Jarvis on your Mac.")
        InstructionRow("2", "Bring up the pairing code (Terminal: pair-phone).")
        InstructionRow("3", "Scan it with this phone.")
        Text("Please make sure both devices are on the same network.",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 16.dp))
        Button(onClick = onScanRequest,
            modifier = Modifier.fillMaxWidth().padding(top = 22.dp)) {
            Text("Scan pairing code")
        }
        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp))
        }
        TextButton(onClick = { manual = !manual },
            modifier = Modifier.padding(top = 6.dp)) {
            Text(if (manual) "Hide manual entry" else "Enter details manually")
        }
        if (manual) {
            OutlinedTextField(value = host, onValueChange = { host = it },
                label = { Text("Host (IP address)") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            OutlinedTextField(value = port, onValueChange = { port = it.filter(Char::isDigit) },
                label = { Text("Port") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
            OutlinedTextField(value = token, onValueChange = { token = it.trim() },
                label = { Text("Pairing token") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
            OutlinedTextField(value = secret, onValueChange = { secret = it.trim() },
                label = { Text("Direct secret (for away-from-home)") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
            Button(
                enabled = host.isNotBlank() && token.isNotBlank(),
                onClick = {
                    onTarget(PairTarget(host.trim(), port.toIntOrNull() ?: 8080,
                        token, secret, prefs.turn))
                },
                modifier = Modifier.padding(top = 16.dp)) { Text("Connect") }
        }
    }
}

@Composable
private fun InstructionRow(step: String, text: String) {
    Row(modifier = Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Text(step, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 12.dp))
    }
}

// Scan done, details hidden: just a breathing ring while the ladder climbs —
// Wi-Fi first, then the punch straight home.
@Composable
private fun ConnectingStep(target: PairTarget, onResult: (String?) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val pulse = rememberInfiniteTransition(label = "pulse")
    val scale by pulse.animateFloat(0.82f, 1.06f,
        infiniteRepeatable(tween(950), RepeatMode.Reverse), label = "scale")
    val glow by pulse.animateFloat(0.35f, 1f,
        infiniteRepeatable(tween(950), RepeatMode.Reverse), label = "glow")
    var line by remember { mutableStateOf("Looking for your Mac…") }
    LaunchedEffect(target) {
        launch {
            kotlinx.coroutines.delay(8000)
            line = "Reaching across the internet…"
            kotlinx.coroutines.delay(12000)
            line = "Still trying — hold on…"
        }
        onResult(trialConnect(context, target))
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .scale(scale)
                .alpha(glow)
                .border(3.dp, MaterialTheme.colorScheme.primary, CircleShape))
        Text("Connecting…",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 30.dp))
        Text(line,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun ThemeStep(prefs: Prefs, themePref: MutableState<String>, onNext: () -> Unit) {
    val options = listOf(
        "light" to "Light",
        "dark" to "Dark",
        "system" to "System default",
        "mac" to "Match my Mac")
    StepFrame("Appearance",
        "Only this phone — changing it never touches the Mac.") {
        options.forEach { (key, label) ->
            val chosen = themePref.value == key
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp)
                    .border(
                        width = if (chosen) 2.dp else 1.dp,
                        color = if (chosen) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(12.dp))
                    .clickable { themePref.value = key; prefs.theme = key }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, color = MaterialTheme.colorScheme.onBackground)
            }
        }
        Button(onClick = onNext, modifier = Modifier.padding(top = 20.dp)) { Text("Continue") }
    }
}

// One button, one decision: setting up the lock IS proving it works — the
// prompt fires immediately, and success walks the wizard forward by itself.
@Composable
private fun LockStep(
    prefs: Prefs,
    canLock: Boolean,
    enrolled: MutableState<Boolean>,
    onTryLock: () -> Unit,
    onNext: () -> Unit
) {
    LaunchedEffect(enrolled.value) { if (enrolled.value) onNext() }
    StepFrame("Protect the app",
        if (canLock) "Jarvis can ask for your fingerprint, face, or screen lock every time it opens."
        else "This phone has no screen lock set up; you can enable this later in Settings.") {
        Button(onClick = onTryLock, enabled = canLock,
            modifier = Modifier.fillMaxWidth()) {
            Text("Set up fingerprint or password lock")
        }
        TextButton(onClick = { prefs.lockEnabled = false; onNext() },
            modifier = Modifier.padding(top = 8.dp)) {
            Text("Skip")
        }
    }
}

// The Mac's hello, re-staged: the cursive gradient greeting rises out of a
// blur, the sub-line follows, and the whole thing hands over by itself.
@Composable
private fun HelloStep(vm: ChatViewModel, onDone: () -> Unit) {
    val rise = remember { Animatable(18f) }
    val fade = remember { Animatable(0f) }
    val blur = remember { Animatable(6f) }
    val subFade = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { rise.animateTo(0f, tween(1400)) }
        launch { fade.animateTo(1f, tween(1400)) }
        launch { blur.animateTo(0f, tween(1200)) }
        launch {
            kotlinx.coroutines.delay(900)
            subFade.animateTo(1f, tween(1000))
        }
        kotlinx.coroutines.delay(3400)
        onDone()
    }
    val dark = MaterialTheme.colorScheme.background == JarvisColors.darkBg
    val name = vm.macName.value.trim().substringBefore(' ')
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (name.isBlank()) "Hi! I'm Jarvis" else "Hi, " + name + "! I'm Jarvis",
            style = TextStyle(
                brush = gradient(dark),
                fontSize = 44.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Cursive),
            modifier = Modifier
                .offset(y = rise.value.dp)
                .alpha(fade.value)
                .blur(blur.value.dp))
        Spacer(modifier = Modifier.height(14.dp))
        Text("Everything stays on your Mac.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.alpha(subFade.value))
    }
}
