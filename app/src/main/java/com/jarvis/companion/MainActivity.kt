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
    // Flips when the onboarding enrolment prompt succeeds, so the wizard
    // can move on by itself.
    private val lockEnrolled = mutableStateOf(false)
    // Leaving for our own picker or scanner is not leaving the app: the lock
    // must not slam shut on the way back from choosing a file.
    private var expectingReturn = false
    private val scannedPayload = mutableStateOf<String?>(null)
    // Mirrors prefs.onboarded as observable state: a plain property read
    // inside setContent never invalidates the composition that read it.
    private val ready = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(applicationContext)
        vm = sharedModel ?: ChatViewModel(application, prefs).also { sharedModel = it }

        micGranted.value = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        micLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()) { granted ->
            // Some OEMs stop the activity for the permission dialog; that
            // is not leaving the app, and the lock must not slam.
            expectingReturn = false
            micGranted.value = granted
            // The tap that asked for permission meant "start listening" —
            // honour it the moment the system says yes.
            if (granted) vm.startRecording()
        }
        scanLauncher = registerForActivityResult(ScanContract()) { result ->
            expectingReturn = false
            scannedPayload.value = result.contents
        }
        pickLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()) { uri ->
            expectingReturn = false
            if (uri != null) vm.upload(uri)
        }

        locked.value = prefs.lockEnabled
        applySecureFlag()
        ready.value = prefs.paired && prefs.onboarded
        if (ready.value) vm.connect()

        setContent {
            var screen by androidx.compose.runtime.remember {
                mutableStateOf("chat")
            }
            val themePref = androidx.compose.runtime.remember {
                mutableStateOf(prefs.theme)
            }
            JarvisTheme(dark = resolveDark(themePref.value, vm.macTheme.value)) {
                when {
                    !ready.value -> OnboardingFlow(
                        vm = vm, prefs = prefs,
                        themePref = themePref,
                        canLock = canUseLock(),
                        lockEnrolled = lockEnrolled,
                        onScanRequest = { launchScan() },
                        scannedPayload = scannedPayload,
                        onTryLock = { promptUnlock(enrollProbe = true) },
                        onDone = {
                            prefs.onboarded = true
                            ready.value = true
                            vm.connect()
                            screen = "chat"
                        })
                    locked.value -> LockGate()
                    screen == "settings" -> SettingsScreen(
                        vm = vm, prefs = prefs, themePref = themePref,
                        canLock = canUseLock(),
                        onLockChanged = { applySecureFlag() },
                        onBack = { screen = "chat" },
                        onSignOut = {
                            // Total wipe: pairing, settings, transcript — back
                            // to the very first onboarding screen.
                            vm.shutdown()
                            sharedModel = null
                            prefs.wipe()
                            recreate()
                        })
                    else -> ChatScreen(
                        vm = vm,
                        micGranted = micGranted.value,
                        onNeedMic = {
                            expectingReturn = true
                            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        onAttach = {
                            expectingReturn = true
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
        if (prefs.lockEnabled && !expectingReturn) locked.value = true
    }

    // A locked app must not leak its transcript through the recents
    // thumbnail or a screenshot.
    private fun applySecureFlag() {
        if (prefs.lockEnabled) {
            window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun canUseLock(): Boolean =
        BiometricManager.from(this).canAuthenticate(
            BIOMETRIC_WEAK or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS

    private fun launchScan() {
        expectingReturn = true
        scanLauncher.launch(ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("Point at the pairing code on your Mac")
            .setBeepEnabled(false)
            .setCaptureActivity(PortraitCaptureActivity::class.java)
            .setOrientationLocked(true))
    }

    private fun promptUnlock(enrollProbe: Boolean = false) {
        val prompt = BiometricPrompt(this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult) {
                    if (enrollProbe) {
                        prefs.lockEnabled = true
                        lockEnrolled.value = true
                        applySecureFlag()
                    } else {
                        locked.value = false
                    }
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
