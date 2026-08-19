package com.jarvis.companion

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.jarvis.companion.ui.ChatScreen
import com.jarvis.companion.ui.JarvisTheme
import com.jarvis.companion.ui.OnboardingFlow
import com.jarvis.companion.ui.SettingsScreen
import com.jarvis.companion.ui.resolveDark
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : FragmentActivity() {

    companion object {
        // Survives activity re-creation so the socket and transcript don't
        // reset when the user leaves and comes back.
        private var sharedModel: ChatViewModel? = null
    }

    private lateinit var prefs: Prefs
    private lateinit var vm: ChatViewModel
    private lateinit var micLauncher: ActivityResultLauncher<String>
    private lateinit var scanLauncher: ActivityResultLauncher<ScanOptions>
    private lateinit var pickLauncher: ActivityResultLauncher<Array<String>>

    private val locked = mutableStateOf(false)
    private val micGranted = mutableStateOf(false)
    private val scannedPayload = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(applicationContext)
        vm = sharedModel ?: ChatViewModel(application, prefs).also { sharedModel = it }

        micGranted.value = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        micLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()) { micGranted.value = it }
        scanLauncher = registerForActivityResult(ScanContract()) { result ->
            scannedPayload.value = result.contents
        }
        pickLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) vm.upload(uri)
        }

        locked.value = prefs.lockEnabled
        if (prefs.paired && prefs.onboarded) vm.connect()

        setContent {
            var screen by androidx.compose.runtime.remember {
                mutableStateOf("chat")
            }
            val themePref = androidx.compose.runtime.remember {
                mutableStateOf(prefs.theme)
            }
            JarvisTheme(dark = resolveDark(themePref.value, vm.macTheme.value)) {
                when {
                    !prefs.onboarded || !prefs.paired -> OnboardingFlow(
                        vm = vm, prefs = prefs,
                        themePref = themePref,
                        canLock = canUseLock(),
                        onScanRequest = { launchScan() },
                        scannedPayload = scannedPayload,
                        onTryLock = { promptUnlock(enrollProbe = true) },
                        onDone = {
                            prefs.onboarded = true
                            vm.connect()
                            screen = "chat"
                        })
                    locked.value -> LockGate()
                    screen == "settings" -> SettingsScreen(
                        vm = vm, prefs = prefs, themePref = themePref,
                        canLock = canUseLock(),
                        onBack = { screen = "chat" },
                        onRePair = {
                            vm.shutdown()
                            sharedModel = null
                            prefs.forgetPairing()
                            recreate()
                        })
                    else -> ChatScreen(
                        vm = vm,
                        micGranted = micGranted.value,
                        onNeedMic = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                        onAttach = {
                            pickLauncher.launch(arrayOf(
                                "application/pdf", "image/*", "text/*",
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                        },
                        onSettings = { screen = "settings" })
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (prefs.lockEnabled) locked.value = true
    }

    private fun canUseLock(): Boolean =
        BiometricManager.from(this).canAuthenticate(
            BIOMETRIC_WEAK or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS

    private fun launchScan() {
        scanLauncher.launch(ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("Scan the pairing code on your Mac")
            .setBeepEnabled(false)
            .setOrientationLocked(true))
    }

    private fun promptUnlock(enrollProbe: Boolean = false) {
        val prompt = BiometricPrompt(this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult) {
                    if (!enrollProbe) locked.value = false
                }
            })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
            .setTitle(if (enrollProbe) "Confirm it works" else "Unlock Jarvis")
            .setSubtitle("Fingerprint, face, or your screen lock")
            .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
            .build())
    }

    @Composable
    private fun LockGate() {
        LaunchedEffect(Unit) { promptUnlock() }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Jarvis is locked", style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground)
            Button(onClick = { promptUnlock() },
                modifier = Modifier.padding(top = 24.dp)) {
                Text("Unlock")
            }
        }
    }
}
