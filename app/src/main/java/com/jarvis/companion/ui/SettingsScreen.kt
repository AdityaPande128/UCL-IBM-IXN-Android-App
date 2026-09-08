package com.jarvis.companion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jarvis.companion.ChatViewModel
import com.jarvis.companion.KOKORO_VOICES
import com.jarvis.companion.Prefs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: ChatViewModel,
    prefs: Prefs,
    themePref: MutableState<String>,
    canLock: Boolean,
    onLockChanged: () -> Unit = {},
    onBack: () -> Unit,
    onSignOut: () -> Unit
) {
    var lockOn by remember { mutableStateOf(prefs.lockEnabled) }
    var speakOn by remember { mutableStateOf(prefs.speakReplies) }
    var confirmSignOut by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background))
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Section("Appearance", "Only this phone; the Mac keeps its own look.")
            listOf(
                "light" to "Light", "dark" to "Dark",
                "system" to "System default", "mac" to "Match my Mac"
            ).forEach { (key, label) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { themePref.value = key; prefs.theme = key }
                        .padding(vertical = 2.dp)
                ) {
                    RadioButton(selected = themePref.value == key,
                        onClick = { themePref.value = key; prefs.theme = key })
                    Text(label, color = MaterialTheme.colorScheme.onBackground)
                }
            }

            Section("App lock",
                if (canLock) "Ask for fingerprint, face, or screen lock when the app opens."
                else "Set up a screen lock on this phone to enable this.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = lockOn, enabled = canLock, onCheckedChange = {
                    lockOn = it; prefs.lockEnabled = it; onLockChanged()
                })
                Text(if (lockOn) "On" else "Off",
                    modifier = Modifier.padding(start = 12.dp),
                    color = MaterialTheme.colorScheme.onBackground)
            }

            Section("Spoken replies", "Jarvis speaks its answers out of this phone.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = speakOn, onCheckedChange = {
                    speakOn = it; vm.setSpeakReplies(it)
                })
                Text(if (speakOn) "On" else "Off",
                    modifier = Modifier.padding(start = 12.dp),
                    color = MaterialTheme.colorScheme.onBackground)
            }

            Section("Voice", "Kokoro voice for every surface — the Mac follows this change.")
            KOKORO_VOICES.forEach { (id, label) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.setVoice(id) }
                        .padding(vertical = 2.dp)
                ) {
                    RadioButton(selected = vm.currentVoice.value == id,
                        onClick = { vm.setVoice(id) })
                    Text(label, color = MaterialTheme.colorScheme.onBackground)
                }
            }

            Section("On your Mac",
                "Models, hardware tiers, “Hey Jarvis” and permissions "
                    + "live in the Mac app — they are the laptop’s business.")

            Section("Profile",
                "Signed in to " + vm.macName.value.trim().ifBlank { prefs.host } + "'s Mac.")
            OutlinedButton(onClick = { confirmSignOut = true },
                modifier = Modifier.padding(top = 4.dp, bottom = 40.dp)) {
                Text("Sign out", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out of this Mac?") },
            text = { Text("This wipes every chat, setting and the pairing from this phone, "
                + "and takes you back to the start. Your Mac keeps everything.") },
            confirmButton = {
                TextButton(onClick = { confirmSignOut = false; onSignOut() }) {
                    Text("Sign out", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") }
            })
    }
}

@Composable
private fun Section(title: String, sub: String) {
    Text(title, style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(top = 26.dp))
    Text(sub, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
}
