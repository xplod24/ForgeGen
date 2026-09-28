package com.example.forgegen.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.forgegen.ForgeViewModel
import com.example.forgegen.ServerConnection
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/* ============================================================================
 * SERVER CONNECTION
 * How the app reaches the server (ForgeRepository.connection): the status in the top bar (a countdown while it
 * looks for the server), and the dialog with the server's address, the saved profiles and a test. The dialog opens
 * by itself when the app stops looking (after a minute without an answer) and can be closed to work without the
 * server; the status then says "Offline" and a tap opens it again.
 * ============================================================================ */

/** Seconds left of the current search, ticking once a second (0 once it is over). */
@Composable
fun rememberSearchSecondsLeft(searchEndsAt: Long): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(searchEndsAt) {
        while (true) {
            now = System.currentTimeMillis()
            if (now >= searchEndsAt) break
            delay(1_000L - now % 1_000L)
        }
    }
    return ((searchEndsAt - now + 999) / 1_000).coerceAtLeast(0)
}

/** "Connecting... 0:42" / "35 ms" / "Offline"; the main screen's top bar opens the server dialog on a tap around it. */
@Composable
fun ConnectionStatus(
    connection: ServerConnection,
    pingMs: Long,
    searchEndsAt: Long,
    // When Restart Forge was asked for (3.3.0); 0 when Forge is not restarting.
    restartingSince: Long = 0L,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (restartingSince > 0 && connection != ServerConnection.CONNECTED) {
            val seconds = rememberSecondsSince(restartingSince)
            CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.5.dp)
            Spacer(Modifier.width(6.dp))
            Text("Restarting Forge... ${seconds / 60}:${"%02d".format(seconds % 60)}", fontSize = 12.sp)
            return@Row
        }
        when (connection) {
            ServerConnection.CONNECTED -> {
                Icon(Icons.Default.Wifi, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
                Text("${pingMs}ms", fontSize = 12.sp)
            }
            ServerConnection.SEARCHING -> {
                val left = rememberSearchSecondsLeft(searchEndsAt)
                CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.5.dp)
                Spacer(Modifier.width(6.dp))
                // Past the minute only a waiting queue keeps looking, less often.
                Text(
                    if (left > 0) "Connecting... ${left / 60}:${"%02d".format(left % 60)}" else "Waiting for the server",
                    fontSize = 12.sp,
                )
            }
            ServerConnection.OFFLINE -> {
                Icon(Icons.Default.WifiOff, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
                Text("Offline", fontSize = 12.sp, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * The server's address, the saved profiles, a test and another try. [offline]: opened because the app stopped
 * looking for the server, so it says so.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ServerConnectionDialog(
    viewModel: ForgeViewModel,
    offline: Boolean,
    phoneOnline: Boolean,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val pingMs by viewModel.pingMs.collectAsStateWithLifecycle()
    val searchEndsAt by viewModel.searchEndsAt.collectAsStateWithLifecycle()
    var address by rememberSaveable { mutableStateOf(config.apiUrl) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(if (offline) Icons.Default.CloudOff else Icons.Default.Sync, contentDescription = null) },
        title = { Text(if (offline) "No connection to the server" else "Server") },
        text = {
            Column {
                when {
                    !phoneOnline -> Text("The phone has no network connection. Turn on Wi-Fi or mobile data.")
                    offline ->
                        Text(
                            "The server did not answer for a minute, so the app stopped asking. " +
                                "Check its address and that Forge is running.",
                        )
                    else -> ConnectionStatus(connection, pingMs, searchEndsAt)
                }
                OutlinedTextField(
                    value = address,
                    onValueChange = {
                        address = it
                        testResult = null
                    },
                    label = { Text("Server Address") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                if (config.serverProfiles.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                        config.serverProfiles.forEach { profile ->
                            SuggestionChip(
                                onClick = {
                                    address = profile.url
                                    testResult = null
                                },
                                label = { Text(profile.name, fontSize = 12.sp) },
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedButton(
                        onClick = {
                            testing = true
                            testResult = null
                            scope.launch {
                                testResult = viewModel.testServer(address)
                                testing = false
                            }
                        },
                        enabled = !testing && address.isNotBlank(),
                    ) { Text("Test") }
                    Spacer(Modifier.width(12.dp))
                    if (testing) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    testResult?.let { Text(it, fontSize = 12.sp) }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    viewModel.connectTo(address)
                    onDismiss()
                },
                enabled = address.isNotBlank(),
            ) { Text(if (address.trim() == config.apiUrl) "Retry" else "Connect") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onOpenSettings) { Text("Settings") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}
