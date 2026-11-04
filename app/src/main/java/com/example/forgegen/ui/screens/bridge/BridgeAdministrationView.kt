package com.example.forgegen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.google.gson.JsonArray
import com.google.gson.JsonObject

@Composable
internal fun BridgeExtensionsView(
    controller: BridgeController,
    state: BridgeState,
    form: (BridgeForm) -> Unit,
) {
    var offset by rememberSaveable(state.base) { mutableIntStateOf(0) }
    var selected by remember(state.base) { mutableStateOf(emptyList<String>()) }
    BridgeActionButton(state, "extensions.install", "Install extension…") {
        form(
            BridgeForm(
                "Install extension",
                "extensions.install",
                "POST",
                "extensions",
                fields =
                    listOf(
                        BridgeField("repository_url", "GitHub repository HTTPS URL"),
                        BridgeField("directory_name", "Directory name"),
                    ),
                wait = true,
                explanation = "Install server code from this repository. A restart is needed to load its code and dependencies.",
            ),
        )
    }
    if (!state.can("extensions.toggle")) BridgeNote(state.reason("extensions.toggle"))
    if (state.can("extensions.list")) {
        BridgeRemote(controller, state, "extensions", mapOf("offset" to "$offset", "limit" to "50")) { json ->
            BridgeCard {
                json.children("items").forEach { extension ->
                    val id = extension.text("id")
                    val protected =
                        extension.flag("protected") || extension.flag("builtin") || extension.text("name").equals("forgegen-bridge", true)
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!protected && state.can("extensions.bulk")) {
                                Checkbox(
                                    checked = id in selected,
                                    modifier = Modifier.semantics { contentDescription = "Select ${extension.text("name")}" },
                                    onCheckedChange = { selected = if (it) (selected + id).distinct().take(100) else selected - id },
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(extension.text("name"), style = MaterialTheme.typography.titleSmall)
                                BridgeNote(extension.text("version").ifEmpty { "Unknown version" })
                                BridgeNote(
                                    if (protected) {
                                        "Protected"
                                    } else if (extension.flag("installed")) {
                                        "Installed"
                                    } else {
                                        "Uninstalled · restart pending"
                                    },
                                )
                            }
                            if (!protected && extension.flag("installed")) {
                                Switch(
                                    checked = extension.flag("next_enabled"),
                                    modifier = Modifier.semantics { contentDescription = "Enable ${extension.text("name")} after restart" },
                                    enabled = state.can("extensions.toggle") && !state.busy && state.pending == null,
                                    onCheckedChange = { enabled ->
                                        form(
                                            BridgeForm(
                                                if (enabled) "Enable extension" else "Disable extension",
                                                "extensions.toggle",
                                                "PATCH",
                                                "extensions/${BridgeProtocol.id(id)}",
                                                fixed = JsonObject().apply { addProperty("enabled", enabled) },
                                                wait = true,
                                                explanation = "Change ${extension.text(
                                                    "name",
                                                )} for the next server start. Loaded Python code remains until restart.",
                                            ),
                                        )
                                    },
                                )
                            }
                        }
                        BridgeNote(
                            "Now: ${if (extension.flag(
                                    "current_enabled",
                                )
                            ) {
                                "active"
                            } else {
                                "inactive"
                            }} · Next start: ${if (extension.flag("next_enabled")) "enabled" else "disabled"}",
                        )
                        if (extension.flag("restart_required")) Text("Restart required", color = MaterialTheme.colorScheme.primary)
                        if (!protected &&
                            extension.flag("installed")
                        ) {
                            TextButton(
                                enabled =
                                    state.can("extensions.uninstall") && !state.busy && state.pending == null,
                                onClick = {
                                    form(
                                        BridgeForm(
                                            "Uninstall extension",
                                            "extensions.uninstall",
                                            "DELETE",
                                            "extensions/${BridgeProtocol.id(id)}",
                                            noBody = true,
                                            wait = true,
                                            waitInQuery = true,
                                            destructive = true,
                                            explanation = "Move ${extension.text("name")} to server quarantine. Restart is required.",
                                        ),
                                    )
                                },
                            ) { Text("Uninstall…") }
                        }
                        HorizontalDivider()
                    }
                }
                BridgePages(offset, 50, json.children("items").size, json.longValue("total")) { offset = it }
            }
        }
    } else {
        BridgeNote(state.reason("extensions.list"))
    }
    if (selected.isNotEmpty()) {
        BridgeNote("${selected.size} selected · up to 100")
        listOf(
            "enable" to "Enable selected…",
            "disable" to "Disable selected…",
            "uninstall" to "Uninstall selected…",
        ).forEach { (action, title) ->
            BridgeActionButton(state, "extensions.bulk", title) {
                val body =
                    JsonObject().apply {
                        addProperty("action", action)
                        add("extension_ids", JsonArray().apply { selected.forEach(::add) })
                    }
                form(
                    BridgeForm(
                        title,
                        "extensions.bulk",
                        "POST",
                        "extensions/bulk",
                        fixed = body,
                        wait = true,
                        destructive = true,
                        explanation =
                            "Apply this change to the selected extensions. " +
                                "Protected resources are rejected by the server. Restart is required.",
                    ),
                )
            }
        }
    }
    BridgeActionButton(state, "extensions.bulk", "Disable other extensions…") {
        form(
            BridgeForm(
                "Disable other extensions",
                "extensions.bulk",
                "POST",
                "extensions/bulk",
                fixed =
                    JsonObject().apply {
                        addProperty("action", "disable_others")
                        add("extension_ids", JsonArray())
                    },
                wait = true,
                destructive = true,
                explanation = "Disable eligible extensions for the next start, keeping Bridge and built-in extensions protected.",
            ),
        )
    }
    BridgeActionButton(state, "server.restart", "Restart when idle…") {
        form(
            BridgeForm(
                "Restart Forge",
                "server.restart",
                "POST",
                "server/restart",
                wait = true,
                explanation = "Request a supervised server restart. Reconnect afterwards to check that Forge is ready.",
            ),
        )
    }
}

@Composable
internal fun BridgeServerView(
    controller: BridgeController,
    state: BridgeState,
    form: (BridgeForm) -> Unit,
) {
    if (state.can("status")) {
        BridgeRemote(controller, state, "server/status", poll = true) { json ->
            BridgeCard {
                Text(
                    when (json.get("busy")?.takeUnless { it.isJsonNull }?.asBoolean) {
                        true -> "Busy"
                        false -> "Idle"
                        else -> "Status unknown"
                    },
                    style = MaterialTheme.typography.titleLarge,
                )
                BridgeNote("Phase: ${json.text("phase").ifEmpty { "Unknown" }}")
                json
                    .number("progress")
                    ?.takeIf {
                        it in 0.0..1.0
                    }?.let { LinearProgressIndicator(progress = { it.toFloat() }, modifier = Modifier.fillMaxWidth()) }
                json.number("elapsed_seconds")?.let { BridgeNote("Elapsed: ${it.toLong()} seconds") }
                BridgeNote("Backend: ${json.text("backend")}")
                json.get("memory")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.forEach { (key, value) ->
                    BridgeNote(
                        "${key.replace('_', ' ')}: ${bridgeBytes(runCatching { value.takeUnless { it.isJsonNull }?.asLong }.getOrNull())}",
                    )
                }
                json.children("recent_errors").forEach { error ->
                    Text(error.text("message").take(500), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    } else {
        BridgeNote(state.reason("status"))
    }
    BridgeActionButton(state, "server.unload", "Unload model when idle…") {
        form(BridgeForm("Unload model", "server.unload", "POST", "server/unload", wait = true))
    }
    BridgeActionButton(state, "server.restart", "Restart when idle…") {
        form(
            BridgeForm(
                "Restart Forge",
                "server.restart",
                "POST",
                "server/restart",
                wait = true,
                explanation =
                    "The server may disconnect after accepting this request. " +
                        "Reconnect and check its status before assuming it is ready.",
            ),
        )
    }
}

@Composable
internal fun BridgeDevicesView(
    controller: BridgeController,
    state: BridgeState,
    form: (BridgeForm) -> Unit,
) {
    BridgeCard {
        Text(state.device?.name ?: "This phone", style = MaterialTheme.typography.titleMedium)
        BridgeNote("Granted permissions")
        state.device
            ?.scopes
            .orEmpty()
            .forEach { Text(it) }
        BridgeNote("Permissions can be changed by the server owner. Pairing does not grant Android permissions.")
    }
    var offset by rememberSaveable(state.base) { mutableIntStateOf(0) }
    if (state.can("devices.list")) {
        BridgeRemote(controller, state, "devices", mapOf("offset" to "$offset", "limit" to "50")) { json ->
            BridgeCard {
                json.children("items").forEach { device ->
                    Text(device.text("name"), style = MaterialTheme.typography.titleSmall)
                    BridgeNote("Created: ${device.text("created_at")}")
                    val revoked = device.text("revoked_at").isNotEmpty()
                    if (revoked) BridgeNote("Revoked: ${device.text("revoked_at")}")
                    if (!revoked) {
                        BridgeActionButton(state, "devices.revoke", "Revoke access…") {
                            form(
                                BridgeForm(
                                    "Revoke device access",
                                    "devices.revoke",
                                    "DELETE",
                                    "devices/${BridgeProtocol.id(device.text("id"))}",
                                    noBody = true,
                                    destructive = true,
                                    explanation = "Revoke ${device.text(
                                        "name",
                                    )}. Its pending server work may be cancelled. Revoking this phone will require pairing again.",
                                ),
                            )
                        }
                    }
                    HorizontalDivider()
                }
                BridgePages(offset, 50, json.children("items").size, json.longValue("total")) { offset = it }
            }
        }
    } else {
        BridgeNote(state.reason("devices.list"))
    }
}

@Composable
internal fun BridgeCapabilitiesView(state: BridgeState) {
    BridgeCard {
        Text("Server policy: ${state.capabilities?.privacyPolicy ?: "Unknown"}")
        state.capabilities
            ?.requiredFlags
            .orEmpty()
            .forEach { (name, enabled) -> BridgeNote("$name: $enabled") }
    }
    state.capabilities?.features.orEmpty().toSortedMap().forEach { (name, feature) ->
        BridgeCard {
            Text(name, style = MaterialTheme.typography.titleSmall)
            BridgeNote(
                "Supported: ${feature.supported == true} · Enabled: ${feature.enabled == true} · Authorized: ${feature.authorized == true}",
            )
            if (!BridgeProtocol.allowed(feature)) BridgeNote(BridgeProtocol.explanation(feature.reasonCode))
        }
    }
}
