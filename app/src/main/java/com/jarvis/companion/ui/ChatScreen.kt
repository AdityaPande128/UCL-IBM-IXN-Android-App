package com.jarvis.companion.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import com.jarvis.companion.ChatItem
import com.jarvis.companion.ChatViewModel
import com.jarvis.companion.net.ConnState
import com.jarvis.companion.net.FileRef
import kotlinx.coroutines.launch

@Composable
fun ConnectionPill(state: ConnState) {
    val (label, color) = when (state) {
        is ConnState.Live -> (if (state.via == "direct") "Connected · Direct" else "Connected") to
            MaterialTheme.colorScheme.primary
        is ConnState.Connecting -> "Connecting…" to MaterialTheme.colorScheme.onSurfaceVariant
        is ConnState.PairRequired -> "Re-pair needed" to MaterialTheme.colorScheme.error
        is ConnState.Unreachable -> "Unreachable — Telegram still works" to
            MaterialTheme.colorScheme.error
        else -> "Mac unreachable" to MaterialTheme.colorScheme.error
    }
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(end = 10.dp)) {
        Box(modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color))
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    micGranted: Boolean,
    onNeedMic: () -> Unit,
    onAttach: () -> Unit,
    onSettings: () -> Unit
) {
    val drawer = androidx.compose.material3.rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val connState by vm.conn.state.collectAsState()
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    var draft by remember { mutableStateOf("") }

    LaunchedEffect(vm.items.size) {
        if (vm.items.isNotEmpty()) listState.animateScrollToItem(vm.items.size - 1)
    }
    LaunchedEffect(vm.toast.value) {
        vm.toast.value?.let { snackbar.showSnackbar(it); vm.toast.value = null }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            vm.newChat(); scope.launch { drawer.close() }
                        }
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary)
                    Text("New chat", modifier = Modifier.padding(start = 10.dp))
                }
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(vm.conversations, key = { it.id }) { convo ->
                        val active = vm.activeConversation.value == convo.id
                        Text(convo.title,
                            maxLines = 1,
                            color = if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.selectConversation(convo.id)
                                    scope.launch { drawer.close() }
                                }
                                .padding(horizontal = 18.dp, vertical = 12.dp))
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { scope.launch { drawer.close() }; onSettings() }
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Settings, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Settings", modifier = Modifier.padding(start = 10.dp))
                }
            }
        }
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = {
                        val title = vm.conversations
                            .find { it.id == vm.activeConversation.value }?.title ?: "Jarvis"
                        Text(title, maxLines = 1)
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawer.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Conversations")
                        }
                    },
                    actions = { ConnectionPill(connState) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background)
                )
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            Column(modifier = Modifier
                .fillMaxSize()
                .padding(padding)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                ) {
                    items(vm.items, key = { it.id }) { item -> Bubble(item, vm) }
                    if (vm.busy.value) {
                        item {
                            Text(
                                text = if (vm.busyLine.value.isBlank()) "Thinking…"
                                else vm.busyLine.value,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 10.dp))
                        }
                    }
                }
                vm.proposal.value?.let { asking ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 3.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(asking.summary,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface)
                            if (asking.will.isNotBlank()) Text("Jarvis will " + asking.will,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp))
                            if (asking.estimate.isNotBlank()) Text("Takes " + asking.estimate,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(modifier = Modifier.padding(top = 12.dp)) {
                                Button(onClick = { vm.approve(asking.id, true) }) { Text("Yes") }
                                OutlinedButton(onClick = { vm.approve(asking.id, false) },
                                    modifier = Modifier.padding(start = 10.dp)) { Text("No") }
                            }
                            Text("…or hold the mic and say yes or no.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
                if (vm.pendingUploads.isNotEmpty()) {
                    Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)) {
                        vm.pendingUploads.forEach { pending ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.padding(end = 6.dp)
                            ) {
                                Text(pending.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onAttach) {
                        Icon(Icons.Filled.AttachFile, contentDescription = "Attach a file",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = { Text("Ask Jarvis…") },
                        modifier = Modifier.weight(1f),
                        maxLines = 4,
                        shape = RoundedCornerShape(22.dp))
                    Box(
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(
                                if (vm.recording.value) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.surface)
                            .pointerInput(micGranted) {
                                detectTapGestures(onPress = {
                                    if (!micGranted) { onNeedMic(); return@detectTapGestures }
                                    if (!vm.startRecording()) return@detectTapGestures
                                    val released = tryAwaitRelease()
                                    vm.stopRecording(send = released)
                                })
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Mic, contentDescription = "Hold to talk",
                            tint = if (vm.recording.value) MaterialTheme.colorScheme.onError
                            else MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = {
                        if (vm.busy.value) vm.abort()
                        else { vm.sendIntent(draft.trim()); draft = "" }
                    }) {
                        Icon(
                            if (vm.busy.value) Icons.Filled.Stop
                            else Icons.AutoMirrored.Filled.Send,
                            contentDescription = if (vm.busy.value) "Stop" else "Send",
                            tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun Bubble(item: ChatItem, vm: ChatViewModel) {
    val fromUser = item.role == "user"
    val align = if (fromUser) Alignment.CenterEnd else Alignment.CenterStart
    val color = when (item.role) {
        "user" -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        "error" -> MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
        else -> MaterialTheme.colorScheme.surface
    }
    if (item.role == "system") {
        Text(item.text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp))
        return
    }
    Box(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 4.dp), contentAlignment = align) {
        Surface(shape = RoundedCornerShape(16.dp), color = color) {
            Column(modifier = Modifier
                .widthIn(max = 300.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (item.text.isNotBlank()) Text(item.text,
                    color = if (item.role == "error") MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface)
                item.files.forEach { file -> FileChip(file, vm) }
            }
        }
    }
}

@Composable
private fun FileChip(file: FileRef, vm: ChatViewModel) {
    val preview = file.id?.let { vm.imagePreviews[it] }
    Column(modifier = Modifier.padding(top = 6.dp)) {
        if (preview != null) {
            Image(bitmap = preview.asImageBitmap(), contentDescription = file.name,
                modifier = Modifier
                    .widthIn(max = 260.dp)
                    .clip(RoundedCornerShape(10.dp)))
            Spacer(modifier = Modifier.height(4.dp))
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.5f))
                .clickable { vm.download(file, open = true) }
                .padding(horizontal = 10.dp, vertical = 7.dp)
        ) {
            Icon(Icons.Filled.Description, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp))
            Text(file.name, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 6.dp))
        }
    }
}
