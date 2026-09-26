package com.example.forgegen

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/* ============================================================================
 * DEBUG PANEL
 * The "Debug" section of the settings, shown only after unlocking the debug mode (DebugMode). Its actions work at
 * once, without the usual confirmations; the rules of BlockingApi stay on.
 * ============================================================================ */
@Composable
fun DebugPanel(viewModel: ForgeViewModel) {
    val context = LocalContext.current
    val config by viewModel.config.collectAsStateWithLifecycle()
    val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()
    val isServerBusy by viewModel.isServerBusy.collectAsStateWithLifecycle()
    val queue by viewModel.generationQueue.collectAsStateWithLifecycle()
    val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val isQueueActive by viewModel.isQueueActive.collectAsStateWithLifecycle()
    val isQueuePaused by viewModel.isQueuePaused.collectAsStateWithLifecycle()
    val pauseReason by viewModel.queuePauseReason.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    val loras by viewModel.availableLoras.collectAsStateWithLifecycle()
    val samplers by viewModel.samplers.collectAsStateWithLifecycle()
    val promptHistory by viewModel.promptHistory.collectAsStateWithLifecycle()
    val wildcards by viewModel.wildcards.collectAsStateWithLifecycle()
    val favorites by viewModel.favoritePaths.collectAsStateWithLifecycle()
    val realPersonLoras by ForgeModelManager.realPersonLoras.collectAsStateWithLifecycle()
    val forceNowBar by viewModel.debugForceNowBar.collectAsStateWithLifecycle()

    var refresh by remember { mutableIntStateOf(0) }
    var civitai by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(refresh) { civitai = viewModel.debugCivitaiCounts() }
    var showRawEditor by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            "Everything here acts at once, without the usual confirmations. The rules kept in every content mode " +
                "(BlockingApi) stay on.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.error,
        )

        DebugTitle("Status")
        val runtime = Runtime.getRuntime()
        val status =
            buildString {
                appendLine("App: ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE}), ${context.packageName}")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine(
                    "Now Bar: ${if (NowBar.isSupported(context)) "offered" else "not offered"}${if (forceNowBar) " (forced)" else ""}",
                )
                appendLine("Memory: ${(runtime.totalMemory() - runtime.freeMemory()) shr 20} MB used of ${runtime.maxMemory() shr 20} MB")
                appendLine("Server: ${config.apiUrl}")
                appendLine("  connected=$isConnected busy=$isServerBusy")
                val byStatus =
                    queue
                        .groupingBy { it.status }
                        .eachCount()
                        .entries
                        .joinToString { "${it.key}=${it.value}" }
                appendLine("Queue: ${queue.size} jobs ($byStatus)")
                appendLine("  generating=$isGenerating active=$isQueueActive paused=$isQueuePaused")
                pauseReason?.let { appendLine("  pause reason: $it") }
                appendLine("Lists: ${models.size} models, ${loras.size} LoRAs, ${samplers.size} samplers")
                appendLine("Content mode: ${config.contentMode}, real-person LoRAs: ${realPersonLoras.size}")
                appendLine(
                    "Civitai: " +
                        (civitai?.let { (all, unrated) -> "$all entries, $unrated without image ratings" } ?: "..."),
                )
                append("Stored: ${promptHistory.size} prompts, ${wildcards.size} wildcards, ${favorites.size} favorites")
            }
        SelectionContainer { Text(status, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
        TextButton(onClick = { refresh++ }) { Text("Refresh") }

        DebugTitle("Settings")
        SwitchPreference(
            title = "HTTP Logging",
            subtitle = "Every request to the server and to Civitai, with its content, goes to the app's log",
            checked = config.enableLogging,
            onCheckedChange = { viewModel.saveConfig(config.copy(enableLogging = it)) },
        )
        SwitchPreference(
            title = "Force Now Bar Support",
            subtitle = "Offers \"Show Progress in Now Bar\" on any phone",
            checked = forceNowBar,
            onCheckedChange = { viewModel.debugSetForceNowBar(it) },
        )
        Text("Content mode (no confirmation, no one-way lock)", fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(CONTENT_SFW, CONTENT_NSFW, CONTENT_UNRESTRICTED).forEach { mode ->
                FilterChip(
                    selected = config.contentMode == mode,
                    onClick = { viewModel.debugSetContentMode(mode) },
                    label = { Text(mode) },
                )
            }
        }
        DebugButton("Edit Raw Settings") { showRawEditor = true }

        DebugTitle("Tests")
        DebugButton("Notification: Batch Completed") { viewModel.debugTestNotification("batch") }
        DebugButton("Notification: Queue Completed") { viewModel.debugTestNotification("queue") }
        DebugButton("Notification: Queue Finished with Errors") { viewModel.debugTestNotification("failed") }
        DebugButton("Show What's New") { viewModel.debugShowWhatsNew() }
        DebugButton("Offer the Latest Release (Reinstall)") { viewModel.debugOfferLatestRelease() }
        DebugButton("Save Full Log to Downloads") { viewModel.debugSaveFullLog() }

        DebugTitle("Data")
        DebugButton("Rebuild Model Lists") { viewModel.debugRebuildModelLists() }
        DebugButton("Forget Civitai Data") {
            viewModel.debugForgetCivitaiData()
            refresh++
        }

        Button(
            onClick = { viewModel.debugLock() },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        ) { Text("Turn Off Debug Mode") }
    }

    if (showRawEditor) RawSettingsDialog(viewModel) { showRawEditor = false }
}

/** All settings as JSON, applied as they are (a wrong value falls back to its default, as for stored settings). */
@Composable
private fun RawSettingsDialog(
    viewModel: ForgeViewModel,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(viewModel.debugConfigJson()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Raw Settings") },
        text = {
            Column {
                Text("A missing field or a wrong value falls back to its default.", fontSize = 12.sp)
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        error = null
                    },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 420.dp).padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                error = viewModel.debugApplyConfigJson(text)
                if (error == null) {
                    viewModel.showToast("Settings applied")
                    onDismiss()
                }
            }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DebugTitle(text: String) {
    Text(text, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
}

@Composable
private fun DebugButton(
    text: String,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) { Text(text) }
}
