package com.example.forgegen

import android.app.Activity
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Kept outside the existing gallery layout. No content is fetched until the user unlocks the vault. */
@Composable
fun RemoteVaultPanel(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val enabled by RemoteVault.enabled.collectAsState()
    val automatic by RemoteVault.automatic.collectAsState()
    val status by RemoteVault.status.collectAsState()
    var page by remember { mutableStateOf("Secure Remote Vault") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var unlocked by remember { mutableStateOf(false) }
    var recoveryCode by remember { mutableStateOf<String?>(null) }
    var restoreCode by remember { mutableStateOf("") }
    var showRestore by remember { mutableStateOf(false) }
    var showTrash by remember { mutableStateOf(false) }
    var entries by remember { mutableStateOf<List<VaultEntry>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    val headers = remember { mutableStateMapOf<String, VaultHeader>() }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) {
                    unlocked = false
                    entries = emptyList()
                    headers.clear()
                    if (page == "Images") page = "Secure Remote Vault"
                }
            }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            headers.clear()
        }
    }

    fun action(task: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                task()
                message = ""
            } catch (error: Exception) {
                message = error.message ?: "Operation failed"
            } finally {
                busy = false
            }
        }
    }

    suspend fun refresh() {
        entries = RemoteVault.list().objects
        headers.clear()
    }
    val connection =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                action {
                    val text =
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openInputStream(uri)!!.use { input ->
                                val bytes = input.readNBytes(32769)
                                require(bytes.size <= 32768) { "Connection file is too large" }
                                String(bytes)
                            }
                        }
                    RemoteVault.connect(text)
                }
            }
        }
    val exportCode =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            val code = recoveryCode
            if (uri != null && code != null) {
                scope.launch(Dispatchers.IO) {
                    try {
                        context.contentResolver.openOutputStream(uri)!!.use { it.write(code.toByteArray()) }
                    } catch (
                        _: Exception,
                    ) {
                        withContext(Dispatchers.Main) { message = "Recovery code could not be saved" }
                    }
                }
            }
        }
    val canLeave = recoveryCode == null && !busy

    fun back() {
        if (page == "Secure Remote Vault") onClose() else page = "Secure Remote Vault"
    }
    AlertDialog(
        onDismissRequest = { if (canLeave) back() },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(page) },
        text = {
            LazyColumn(Modifier.heightIn(max = 540.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (page == "Secure Remote Vault") {
                    item {
                        VaultSwitches(
                            enabled,
                            automatic,
                            RemoteVault.ready,
                            status,
                            onEnabled = {
                                RemoteVault.setEnabled(it)
                                if (!it) {
                                    unlocked = false
                                    entries = emptyList()
                                    headers.clear()
                                }
                            },
                            onAutomatic = { RemoteVault.setAutomatic(it) },
                            showAutomatic = false,
                        )
                    }
                    if (enabled) {
                        item {
                            VaultSettingsGroup {
                                TextPreference("Server Connection", RemoteVault.address.ifBlank { "Connect your own HTTPS server" }) {
                                    page = "Server Connection"
                                }
                                VaultSettingsDivider()
                                TextPreference("Saving & Transfers", "Automatic saving and gallery import") { page = "Saving & Transfers" }
                                VaultSettingsDivider()
                                TextPreference(
                                    "Browse Vault",
                                    "Unlock to view images and the 24-hour trash",
                                    enabled =
                                        RemoteVault.ready && !busy,
                                ) {
                                    if (!AppLock.isAvailable(context)) {
                                        message = "Set a screen lock on this phone to browse the vault."
                                    } else {
                                        AppLock.authenticate(context as Activity, true, "Unlock Remote Vault") {
                                            unlocked = true
                                            page = "Images"
                                            action { refresh() }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (page == "Server Connection") {
                    item {
                        Text("Images are encrypted on this phone. Your server stores only encrypted files.")
                        VaultSettingsGroup {
                            TextPreference(
                                "Import Connection File",
                                RemoteVault.address.ifBlank { "Select the private file from your server" },
                                enabled = !busy,
                            ) {
                                connection.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                            }
                            if (!RemoteVault.ready) {
                                VaultSettingsDivider()
                                TextPreference(
                                    "Create Vault",
                                    "Create a vault and show its recovery code once",
                                    enabled =
                                        !busy && RemoteVault.address.isNotBlank(),
                                ) {
                                    action { recoveryCode = RemoteVault.create() }
                                }
                                VaultSettingsDivider()
                                TextPreference(
                                    "Restore Vault",
                                    "Use the recovery code from your previous phone",
                                    enabled =
                                        !busy && RemoteVault.address.isNotBlank(),
                                ) {
                                    showRestore = true
                                }
                            }
                        }
                    }
                }
                if (page == "Saving & Transfers") {
                    item {
                        VaultSettingsGroup {
                            SwitchPreference(
                                "Save New Results Automatically",
                                "Save new images in the background, even while the phone is locked",
                                automatic,
                                { RemoteVault.setAutomatic(it) },
                                enabled = RemoteVault.ready && !busy,
                            )
                            VaultSettingsDivider()
                            TextPreference(
                                "Import Entire Forge Gallery",
                                "Queue existing images for encrypted upload",
                                enabled =
                                    RemoteVault.ready && !busy,
                            ) {
                                action { RemoteVault.queueWholeGallery() }
                            }
                            VaultSettingsDivider()
                            TextPreference(
                                "Retry Pending Transfers",
                                status,
                                enabled = RemoteVault.ready && !busy,
                            ) { RemoteVault.schedule() }
                            VaultSettingsDivider()
                            TextPreference(
                                "Cancel Gallery Import",
                                "Stop importing; keep images already saved",
                                enabled =
                                    RemoteVault.ready && !busy,
                            ) { RemoteVault.cancelImport() }
                        }
                    }
                }
                if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (message.isNotEmpty()) item { Text(message, color = MaterialTheme.colorScheme.error) }
                if (unlocked && enabled && page == "Images") {
                    item {
                        OutlinedTextField(
                            query,
                            { query = it },
                            label = { Text("Search names or generation data") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(!showTrash, { showTrash = false }, label = { Text("Images") })
                            FilterChip(showTrash, { showTrash = true }, label = { Text("Trash · 24 hours") })
                        }
                        Text(
                            "Deleting here only affects the remote vault. Saved copies elsewhere remain.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    items(entries.filter { (it.deleted != null) == showTrash }, key = { it.id }) { entry ->
                        var error by remember(entry.id) { mutableStateOf(false) }
                        LaunchedEffect(entry.id, unlocked) {
                            if (unlocked) {
                                try {
                                    val header = RemoteVault.header(entry.id)
                                    if (unlocked &&
                                        enabled
                                    ) {
                                        headers[entry.id] = header
                                    }
                                } catch (_: Exception) {
                                    error = true
                                }
                            }
                        }
                        val header = headers[entry.id]
                        if (query.isBlank() || header?.let { (it.name + " " + it.metadata).contains(query, true) } == true) {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    if (header != null) {
                                        val bitmap =
                                            remember(header.thumbnail) {
                                                runCatching {
                                                    val data = VaultCrypto.decode(header.thumbnail)
                                                    BitmapFactory.decodeByteArray(data, 0, data.size)
                                                }.getOrNull()
                                            }
                                        if (bitmap !=
                                            null
                                        ) {
                                            Image(bitmap.asImageBitmap(), header.name, Modifier.fillMaxWidth().height(160.dp))
                                        }
                                        Text(header.name, style = MaterialTheme.typography.titleSmall)
                                        if (entry.expires !=
                                            null
                                        ) {
                                            Text(
                                                "Expires: " + java.util.Date((entry.expires * 1000).toLong()),
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                    } else {
                                        Text(if (error) "Encrypted image could not be opened" else "Loading encrypted thumbnail…")
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (!showTrash) {
                                            TextButton(
                                                onClick = { action { RemoteVault.download(entry.id) } },
                                                enabled =
                                                    !busy && header != null,
                                            ) { Text("Save to Phone") }
                                        }
                                        TextButton(onClick = {
                                            action {
                                                RemoteVault.trash(entry.id, showTrash)
                                                refresh()
                                            }
                                        }, enabled = !busy) { Text(if (showTrash) "Restore" else "Move to Trash") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { back() }, enabled = canLeave) {
                Text(if (page == "Secure Remote Vault") "Close" else "Back")
            }
        },
    )
    if (recoveryCode != null) {
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
            title = { Text("Save Your Recovery Code") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Shown only now. Without this code, a lost phone means you cannot recover your vault. Anyone with the code and your encrypted vault can decrypt it.",
                    )
                    Text(recoveryCode!!, style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { exportCode.launch("forgegen-vault-recovery.txt") }) { Text("Save as Text File") }
                }
            },
            confirmButton = { TextButton(onClick = { recoveryCode = null }) { Text("I saved it, or accept losing recovery") } },
        )
    }
    if (showRestore) {
        AlertDialog(
            onDismissRequest = {
                showRestore = false
                restoreCode = ""
            },
            properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
            title = { Text("Restore Remote Vault") },
            text = { OutlinedTextField(restoreCode, { restoreCode = it }, label = { Text("Recovery code") }) },
            confirmButton = {
                TextButton(onClick = {
                    val code = restoreCode
                    restoreCode = ""
                    showRestore = false
                    action { RemoteVault.recover(code) }
                }) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRestore = false
                    restoreCode = ""
                }) { Text("Cancel") }
            },
        )
    }
}

/** Stateless component used both in the app and in visual regression tests. */
@Composable
fun VaultSwitches(
    enabled: Boolean,
    automatic: Boolean,
    ready: Boolean,
    status: String,
    onEnabled: (Boolean) -> Unit,
    onAutomatic: (Boolean) -> Unit,
    showAutomatic: Boolean = true,
) {
    VaultSettingsGroup {
        SwitchPreference("Secure Remote Vault", "Optional · your server, encrypted on your phone", enabled, onEnabled)
        if (enabled && showAutomatic) {
            VaultSettingsDivider()
            SwitchPreference("Save New Results Automatically", status, automatic, onAutomatic, enabled = ready)
        }
    }
}

/** Uses the same rounded surfaces and preference rows as the existing settings. */
@Composable
private fun VaultSettingsGroup(content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth()) { content() }
    }
}

@Composable
private fun VaultSettingsDivider() {
    androidx.compose.material3.HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    )
}
