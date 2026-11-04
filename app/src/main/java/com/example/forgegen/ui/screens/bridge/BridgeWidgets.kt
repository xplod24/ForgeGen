package com.example.forgegen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@Composable
fun BridgeHomeEntry(
    state: BridgeState,
    onClick: () -> Unit,
) {
    if (!state.enabled) return
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 6.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 54.dp)
                .clickable(role = Role.Button, onClickLabel = "Open ForgeGen Bridge", onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Hub, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("ForgeGen Bridge", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(5.dp).clip(CircleShape).background(if (state.paired) Color(0xFF66BB6A) else Color(0xFFFFA726)))
                    Spacer(Modifier.width(5.dp))
                    Text(bridgeConnectionLabel(state.connection), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun bridgeConnectionLabel(value: BridgeConnection): String =
    when (value) {
        BridgeConnection.OFF -> "Not enabled"
        BridgeConnection.CHECKING -> "Checking Bridge…"
        BridgeConnection.ABSENT -> "Bridge not found"
        BridgeConnection.PAIRING_REQUIRED -> "Pair this phone"
        BridgeConnection.CONNECTED -> "Connected · server tools"
        BridgeConnection.AUTH_REQUIRED -> "Authentication required"
        BridgeConnection.REVOKED -> "Device access revoked"
        BridgeConnection.INCOMPATIBLE -> "Unsupported Bridge version"
        BridgeConnection.SERVER_CHANGED -> "Server identity changed"
        BridgeConnection.UNAVAILABLE -> "Bridge unavailable"
    }

@Composable
internal fun BridgeCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
internal fun BridgeNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

internal data class BridgeField(
    val key: String,
    val label: String,
    val initial: String = "",
    val optional: Boolean = false,
    val choices: List<Pair<String, String>> = emptyList(),
    val multiline: Boolean = false,
)

internal data class BridgeForm(
    val title: String,
    val feature: String,
    val method: String,
    val path: String,
    val fields: List<BridgeField> = emptyList(),
    val fixed: JsonObject = JsonObject(),
    val wait: Boolean = false,
    val waitInQuery: Boolean = false,
    val noBody: Boolean = false,
    val destructive: Boolean = false,
    val explanation: String = "This operation runs on your server.",
)

/** Forms send only explicitly declared contract fields, never arbitrary server commands or scopes. */
@Composable
internal fun BridgeActionDialog(
    form: BridgeForm,
    onDismiss: () -> Unit,
    onConfirm: (JsonObject?, Map<String, String>) -> Unit,
) {
    val values = remember(form) { mutableStateMapOf<String, String>().apply { form.fields.forEach { put(it.key, it.initial) } } }
    var wait by remember(form) { mutableStateOf(true) }
    var error by remember(form) { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(form.title) }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(form.explanation)
            form.fields.forEach { field ->
                if (field.choices.isNotEmpty()) {
                    var expanded by remember { mutableStateOf(false) }
                    Text(field.label, style = MaterialTheme.typography.labelLarge)
                    Box {
                        OutlinedButton(onClick = { expanded = true }) {
                            Text(field.choices.firstOrNull { it.first == values[field.key] }?.second ?: "Select…")
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            field.choices.forEach { (id, label) ->
                                DropdownMenuItem(text = { Text(label) }, onClick = {
                                    values[field.key] =
                                        id
                                    ; expanded = false
                                })
                            }
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = values[field.key].orEmpty(),
                        onValueChange = {
                            values[field.key] =
                                it.take(if (field.key == "url") 8192 else 2048)
                        },
                        label = { Text(field.label) },
                        singleLine = !field.multiline,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType =
                                    if (field.key == "url" ||
                                        field.key == "repository_url"
                                    ) {
                                        KeyboardType.Uri
                                    } else {
                                        KeyboardType.Text
                                    },
                            ),
                    )
                }
            }
            if (form.wait) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = wait, onCheckedChange = { wait = it })
                    Text("Wait for server idle", modifier = Modifier.weight(1f))
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(onClick = {
            try {
                val body = form.fixed.deepCopy()
                form.fields.forEach { field ->
                    val value = values[field.key].orEmpty().trim()
                    require(field.optional || value.isNotBlank()) { "Complete all required fields." }
                    when {
                        field.key in setOf("version_id", "file_id") -> {
                            require(value.toLongOrNull()?.let { it > 0 } == true) { "Use a positive file/version ID." }
                            body.addProperty(field.key, value.toLong())
                        }
                        field.key == "expected_sha256" && value.isNotEmpty() -> {
                            require(value.matches(Regex("[A-Fa-f0-9]{64}"))) { "SHA-256 must contain 64 hexadecimal digits." }
                            body.addProperty(field.key, value)
                        }
                        value.isNotEmpty() -> body.addProperty(field.key, value)
                    }
                }
                if (form.wait && !form.waitInQuery) body.addProperty("wait_for_idle", wait)
                val payload = if (form.noBody) null else body
                BridgeProtocol.validateMutation(form.method, form.path, payload)
                onConfirm(payload, if (form.waitInQuery) mapOf("wait_for_idle" to wait.toString()) else emptyMap())
            } catch (e: IllegalArgumentException) {
                error = e.message
            }
        }) { Text(if (form.destructive) "Confirm" else "Submit") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

private data class BridgeRemoteState(
    val json: JsonObject? = null,
    val error: String? = null,
)

/** Lifecycle-bound polling stops when the view leaves the foreground. */
@Composable
internal fun BridgeRemote(
    controller: BridgeController,
    state: BridgeState,
    path: String,
    query: Map<String, String> = emptyMap(),
    poll: Boolean = false,
    content: @Composable (JsonObject) -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var retry by remember(path, state.base) { mutableIntStateOf(0) }
    val result by produceState(BridgeRemoteState(), path, query, state.base, state.revision, retry) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            do {
                try {
                    value = BridgeRemoteState(json = controller.read(path, query))
                } catch (
                    e: CancellationException,
                ) {
                    throw e
                } catch (e: Exception) {
                    value = BridgeRemoteState(error = if (e is BridgeFailure) e.message else "Bridge could not read this view.")
                    break
                }
                if (poll) delay(3_000)
            } while (poll)
        }
    }
    when {
        result.json != null -> content(result.json!!)
        result.error != null ->
            BridgeCard {
                Text(result.error!!)
                TextButton(onClick = { retry++ }) { Text("Retry read") }
            }
        else -> CircularProgressIndicator(Modifier.size(24.dp))
    }
}

@Composable
internal fun BridgePages(
    offset: Int,
    limit: Int,
    count: Int,
    total: Long?,
    onOffset: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        TextButton(enabled = offset > 0, onClick = { onOffset((offset - limit).coerceAtLeast(0)) }) { Text("Previous") }
        BridgeNote(if (total != null) "${if (count == 0) 0 else offset + 1}–${offset + count} of $total" else "Page ${offset / limit + 1}")
        TextButton(
            enabled = count == limit && (total == null || offset + count < total),
            onClick = { onOffset(offset + limit) },
        ) { Text("Next") }
    }
}

internal fun bridgeBytes(bytes: Long?): String =
    if (bytes == null) {
        "Unknown size"
    } else {
        when {
            bytes >= 1_073_741_824 -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_048_576.0)
            else -> "$bytes bytes"
        }
    }
