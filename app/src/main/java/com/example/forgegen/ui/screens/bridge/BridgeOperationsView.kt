package com.example.forgegen

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.google.gson.JsonObject

private val terminalOperations = setOf("completed", "failed", "cancelled", "interrupted")

@Composable
internal fun BridgeOperationList(
    json: JsonObject,
    offset: Int,
    limit: Int,
    onOffset: (Int) -> Unit,
    open: (String) -> Unit,
) {
    BridgeCard {
        val items = json.children("items")
        items.forEach { operation ->
            TextPreference(operation.text("kind"), "${operation.text("status")} · ${operation.text("phase")}") {
                operation.text("id").takeIf(BridgeProtocol::uuid)?.let { open("operation/$it") }
            }
            BridgeOperationProgress(operation)
            if (operation.flag("restart_required")) BridgeNote("Restart required")
            HorizontalDivider()
        }
        if (items.isEmpty()) BridgeNote("No visible operations on this page.")
        BridgePages(offset, limit, items.size, json.longValue("total"), onOffset)
    }
}

@Composable
private fun BridgeOperationProgress(json: JsonObject) {
    val fraction = json.number("progress")?.takeIf { it in 0.0..1.0 }
    val active = json.text("status") in setOf("queued", "waiting_idle", "running")
    if (fraction != null) {
        LinearProgressIndicator(progress = { fraction.toFloat() }, modifier = Modifier.fillMaxWidth())
        BridgeNote("${(fraction * 100).toInt()}%")
    } else if (active) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    if (json.longValue("bytes_done") != null || json.longValue("bytes_total") != null) {
        BridgeNote("${bridgeBytes(json.longValue("bytes_done"))} / ${bridgeBytes(json.longValue("bytes_total"))}")
    }
}

@Composable
internal fun BridgeOperationsView(
    controller: BridgeController,
    state: BridgeState,
    page: String,
    open: (String) -> Unit,
    form: (BridgeForm) -> Unit,
) {
    if (!state.can("operations.read")) {
        BridgeNote(state.reason("operations.read"))
        return
    }
    if (!page.startsWith("operation/")) {
        var offset by rememberSaveable(state.base) { mutableIntStateOf(0) }
        BridgeRemote(controller, state, "operations", mapOf("offset" to "$offset", "limit" to "50"), poll = true) { json ->
            BridgeOperationList(json, offset, 50, { offset = it }, open)
        }
        return
    }
    val id = BridgeProtocol.id(page.substringAfter('/'))
    BridgeRemote(controller, state, "operations/$id", poll = true) { operation ->
        val status = operation.text("status")
        val kind = operation.text("kind")
        val fileOperation = kind.startsWith("models.") || kind.startsWith("download.")
        val scoped =
            when {
                kind.startsWith("models.") -> state.scoped("models.manage")
                kind.startsWith("download.") -> state.scoped("downloads.manage")
                kind.startsWith("extensions.") -> state.scoped("extensions.manage")
                kind.startsWith("server.") -> state.scoped("server.manage")
                else -> false
            }
        BridgeCard {
            Text(kind, style = MaterialTheme.typography.titleLarge)
            Text(status, style = MaterialTheme.typography.titleMedium)
            BridgeNote(operation.text("phase"))
            BridgeOperationProgress(operation)
            BridgeNote("Created: ${operation.text("created_at")}")
            BridgeNote("Updated: ${operation.text("updated_at")}")
            BridgeNote("Operation: $id")
            if (operation.flag("restart_required")) Text("Restart required", color = MaterialTheme.colorScheme.primary)
            operation.get("error")?.takeIf { it.isJsonObject }?.asJsonObject?.let { error ->
                Text(BridgeProtocol.explanation(error.text("code")), color = MaterialTheme.colorScheme.error)
            }
            val result = operation.get("result")?.takeIf { it.isJsonObject }?.asJsonObject
            if (result?.flag("requires_reconnect") == true || result?.flag("restart_requested") == true) {
                Text("Restart requested. Reconnect and check Server status to confirm Forge is ready.")
                TextButton(onClick = controller::refresh, enabled = !state.busy) { Text("Reconnect Bridge") }
            } else if (result != null) {
                var showResult by remember(id) { mutableStateOf(false) }
                TextButton(onClick = { showResult = !showResult }) { Text("Result details") }
                if (showResult) BridgeNote(result.toString().take(4000))
            }
            if (status !in terminalOperations &&
                status in setOf("queued", "waiting_idle", "running") &&
                scoped &&
                (!fileOperation || state.fullModels)
            ) {
                BridgeActionButton(state, "operations.cancel", "Request cancellation…") {
                    form(
                        BridgeForm(
                            "Cancel server operation",
                            "operations.cancel",
                            "DELETE",
                            "operations/$id",
                            noBody = true,
                            destructive = true,
                            explanation = "Cancellation is cooperative. An already committed action may finish first. History is retained.",
                        ),
                    )
                }
                if (kind.startsWith("download.")) {
                    BridgeActionButton(state, "downloads.cancel", "Cancel download…") {
                        form(
                            BridgeForm("Cancel download", "downloads.cancel", "DELETE", "downloads/$id", noBody = true, destructive = true),
                        )
                    }
                }
            }
            if (kind.startsWith("download.") && status in setOf("failed", "cancelled", "interrupted")) {
                BridgeActionButton(state, "downloads.retry", "Retry download…") {
                    form(
                        BridgeForm(
                            "Retry download",
                            "downloads.retry",
                            "POST",
                            "downloads/$id/retry",
                            noBody = true,
                            explanation =
                                "Explicitly retry this transfer with the same operation ID. " +
                                    "The server resumes safely or starts the download again.",
                        ),
                    )
                }
            }
            if (status == "interrupted" && !kind.startsWith("download.")) {
                BridgeNote("Review the server state before submitting another administrative operation. It is not retried automatically.")
            }
        }
    }
}
