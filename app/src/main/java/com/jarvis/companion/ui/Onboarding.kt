package com.jarvis.companion.ui

import android.net.Uri
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.rememberScrollState
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

data class PairTarget(val host: String, val port: Int, val token: String, val secret: String)

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
                secret = parsed["secret"]?.jsonPrimitive?.contentOrNull ?: "")
        }.getOrNull()
    }
    if (text.startsWith("jarvis://")) {
        val uri = Uri.parse(text)
        val host = uri.getQueryParameter("host") ?: return null
        val token = uri.getQueryParameter("token") ?: return null
        return PairTarget(host, uri.getQueryParameter("port")?.toIntOrNull() ?: 8080,
            token, uri.getQueryParameter("secret") ?: "")
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
        probe.start(target.host, target.port, target.token, target.secret)
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
                "Could not reach the Mac on Wi-Fi or by hole punch. Is it awake?"
            else -> "Timed out. Is the Mac awake?"
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
    onScanRequest: () -> Unit,
    scannedPayload: MutableState<String?>,
    onTryLock: () -> Unit,
    onDone: () -> Unit
) {
    var step by remember { mutableStateOf(if (prefs.paired) "theme" else "pair") }
    // Once paired, the wizard can already talk to the Mac: the theme step's
    // "Match my Mac" and the hello's name both come from the live profile.
    LaunchedEffect(step) { if (step != "pair") vm.connect() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        when (step) {
            "pair" -> PairStep(prefs, onScanRequest, scannedPayload) { step = "theme" }
            "theme" -> ThemeStep(prefs, themePref) { step = "lock" }
            "lock" -> LockStep(prefs, canLock, onTryLock) { step = "hello" }
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
    onScanRequest: () -> Unit,
    scannedPayload: MutableState<String?>,
    onNext: () -> Unit
) {
    var host by remember { mutableStateOf(prefs.host) }
    var port by remember { mutableStateOf(prefs.port.toString()) }
    var token by remember { mutableStateOf(prefs.token) }
    var secret by remember { mutableStateOf(prefs.secret) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(scannedPayload.value) {
        val raw = scannedPayload.value ?: return@LaunchedEffect
        scannedPayload.value = null
        val target = parsePairPayload(raw)
        if (target == null) { error = "That code isn't a Jarvis pairing code."; return@LaunchedEffect }
        host = target.host; port = target.port.toString(); token = target.token
        secret = target.secret
    }

    StepFrame("Pair with your Mac",
        "Run the pairing command on the Mac and scan the code, or type the details.") {
        Button(onClick = onScanRequest, modifier = Modifier.fillMaxWidth()) {
            Text("Scan pairing code")
        }
        OutlinedTextField(value = host, onValueChange = { host = it },
            label = { Text("Host (tailnet name or IP)") },
            singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 18.dp))
        OutlinedTextField(value = port, onValueChange = { port = it.filter(Char::isDigit) },
            label = { Text("Port") },
            singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
        OutlinedTextField(value = token, onValueChange = { token = it.trim() },
            label = { Text("Pairing token") },
            singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
        OutlinedTextField(value = secret, onValueChange = { secret = it.trim() },
            label = { Text("Direct secret (for away-from-home)") },
            singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
        if (error != null) {
            Text(error!!, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 10.dp))
        }
        Row(modifier = Modifier.padding(top = 20.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = !checking && host.isNotBlank() && token.isNotBlank(),
                onClick = {
                    checking = true; error = null
                    scope.launch {
                        val target = PairTarget(host.trim(), port.toIntOrNull() ?: 8080,
                            token, secret)
                        val failure = trialConnect(context, target)
                        checking = false
                        if (failure != null) { error = failure; return@launch }
                        prefs.host = target.host
                        prefs.port = target.port
                        prefs.token = target.token
                        prefs.secret = target.secret
                        onNext()
                    }
                }) { Text(if (checking) "Checking…" else "Connect") }
            if (checking) CircularProgressIndicator(
                modifier = Modifier.padding(start = 14.dp).width(22.dp).height(22.dp),
                strokeWidth = 2.dp)
        }
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

@Composable
private fun LockStep(prefs: Prefs, canLock: Boolean, onTryLock: () -> Unit, onNext: () -> Unit) {
    var enabled by remember { mutableStateOf(prefs.lockEnabled) }
    StepFrame("Protect the app",
        if (canLock) "Ask for your fingerprint, face, or screen lock whenever Jarvis opens."
        else "This phone has no screen lock set up; you can enable this later in Settings.") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = enabled, enabled = canLock,
                onCheckedChange = { enabled = it; prefs.lockEnabled = it })
            Text("Require unlock", modifier = Modifier.padding(start = 12.dp),
                color = MaterialTheme.colorScheme.onBackground)
        }
        if (enabled) {
            OutlinedButton(onClick = onTryLock, modifier = Modifier.padding(top = 14.dp)) {
                Text("Try it now")
            }
        }
        Row(modifier = Modifier.padding(top = 20.dp)) {
            Button(onClick = onNext) { Text("Continue") }
            TextButton(onClick = { enabled = false; prefs.lockEnabled = false; onNext() },
                modifier = Modifier.padding(start = 10.dp)) { Text("Skip") }
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
