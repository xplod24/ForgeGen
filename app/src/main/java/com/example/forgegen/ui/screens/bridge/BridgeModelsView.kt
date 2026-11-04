package com.example.forgegen

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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

@Composable
internal fun BridgeModelsView(
    controller: BridgeController,
    state: BridgeState,
    page: String,
    roots: List<BridgeRootInfo>,
    open: (String) -> Unit,
    form: (BridgeForm) -> Unit,
) {
    var offset by rememberSaveable(page, state.base) { mutableIntStateOf(0) }
    var query by rememberSaveable(state.base) { mutableStateOf("") }
    var search by rememberSaveable(state.base) { mutableStateOf("") }
    val kind = page.substringBefore('/')
    val limit = if (kind == "civitai") 20 else 50
    val paging = mapOf("offset" to "$offset", "limit" to "$limit")
    when (kind) {
        "models" -> {
            if (!state.fullModels) {
                BridgeCard {
                    Text("Counts only", style = MaterialTheme.typography.titleMedium)
                    BridgeNote("The server owner limits model access. Counts cover top-level files, not subfolders.")
                }
            }
            if (state.can("models.count")) {
                BridgeRemote(controller, state, "models/counts", paging) { json ->
                    BridgeCard {
                        json.children("items").forEach { item ->
                            Text("${item.text("type").replace('_', ' ')} · ${item.longValue("count")?.toString() ?: "Unknown"}")
                            BridgeNote(item.text("root_id"))
                            if (item.text("reason_code").isNotEmpty()) BridgeNote(BridgeProtocol.explanation(item.text("reason_code")))
                        }
                        BridgePages(offset, limit, json.children("items").size, json.longValue("total")) { offset = it }
                    }
                }
            } else {
                BridgeNote(state.reason("models.count"))
            }
            TextPreference(
                "Browse model catalog",
                if (state.fullModels) "Names, metadata and file actions" else "Names and file actions are unavailable",
                enabled = state.can("models.catalog"),
            ) { open("catalog") }
            BridgeActionButton(state, "models.scan", "Scan and refresh models") {
                form(BridgeForm("Scan models", "models.scan", "POST", "models/scan", wait = true))
            }
            TextButton(
                onClick = { open("downloads") },
                enabled = state.scoped("downloads.manage") && state.fullModels,
            ) { Text("Downloads") }
            TextButton(onClick = { open("civitai") }, enabled = state.can("civitai.search")) { Text("Search Civitai") }
        }
        "catalog" ->
            if (state.can("models.catalog")) {
                BridgeRemote(controller, state, "models", paging) { json ->
                    BridgeCard {
                        json.children("items").forEach { item ->
                            TextPreference(
                                item.text("name"),
                                "${item.text("type")} · ${bridgeBytes(item.longValue("size_bytes"))}",
                            ) {
                                item.text("id").takeIf { it.isNotEmpty() }?.let { open("model/${BridgeProtocol.id(it)}") }
                            }
                        }
                        if (json.children("items").isEmpty()) BridgeNote("No registered models on this page.")
                        BridgePages(offset, limit, json.children("items").size, json.longValue("total")) { offset = it }
                    }
                }
            } else {
                BridgeNote(state.reason("models.catalog"))
            }
        "model" ->
            if (state.can("models.catalog")) {
                BridgeRemote(controller, state, "models/${BridgeProtocol.id(page.substringAfter('/'))}") { model ->
                    BridgeCard {
                        Text(model.text("name"), style = MaterialTheme.typography.titleLarge)
                        Text(model.text("type"))
                        BridgeNote(bridgeBytes(model.longValue("size_bytes")))
                        BridgeNote("Root: ${model.text("root_id")}")
                        var details by remember { mutableStateOf(false) }
                        TextButton(onClick = { details = !details }) { Text("Metadata & source") }
                        if (details) {
                            BridgeNote(model.get("metadata")?.toString()?.take(4000) ?: "No metadata")
                            BridgeNote(
                                model
                                    .get("source")
                                    ?.takeUnless { it.isJsonNull }
                                    ?.toString()
                                    ?.take(4000) ?: "No source information",
                            )
                        }
                        BridgeActionButton(state, "models.move", "Move model…") {
                            form(
                                BridgeForm(
                                    "Move model",
                                    "models.move",
                                    "POST",
                                    "models/${BridgeProtocol.id(model.text("id"))}/move",
                                    fields =
                                        listOf(
                                            rootField(roots, model.text("type")),
                                            BridgeField("name", "File name", model.text("name")),
                                        ),
                                    wait = true,
                                    explanation =
                                        "Move this model to another registered root of the same type. " +
                                            "Existing files are not overwritten.",
                                ),
                            )
                        }
                        BridgeActionButton(state, "models.delete", "Delete model…") {
                            form(
                                BridgeForm(
                                    "Delete model",
                                    "models.delete",
                                    "DELETE",
                                    "models/${BridgeProtocol.id(model.text("id"))}",
                                    wait = true,
                                    waitInQuery = true,
                                    noBody = true,
                                    destructive = true,
                                    explanation = "Delete ${model.text("name")} from your server. Unload it first if it is still resident.",
                                ),
                            )
                        }
                    }
                }
            } else {
                BridgeNote(state.reason("models.catalog"))
            }
        "downloads" -> {
            BridgeActionButton(state, "downloads.create", "New server download…") {
                form(
                    BridgeForm(
                        "Download to server",
                        "downloads.create",
                        "POST",
                        "downloads",
                        fields =
                            listOf(
                                BridgeField("url", "Civitai HTTPS download URL"),
                                rootField(roots),
                                BridgeField("name", "File name"),
                                BridgeField("expected_sha256", "SHA-256 (optional)", optional = true),
                            ),
                        wait = true,
                        explanation =
                            "The server downloads this file. For files requiring a provider key, " +
                                "use the Civitai search or exact version/file IDs.",
                    ),
                )
            }
            BridgeActionButton(state, "civitai.download", "Download by Civitai IDs…") {
                form(
                    BridgeForm(
                        "Download selected Civitai file",
                        "civitai.download",
                        "POST",
                        "civitai/downloads",
                        fields = listOf(BridgeField("version_id", "Version ID"), BridgeField("file_id", "File ID"), rootField(roots)),
                        wait = true,
                    ),
                )
            }
            if (state.scoped("downloads.manage") &&
                state.fullModels
            ) {
                BridgeRemote(controller, state, "downloads", paging, poll = true) { json ->
                    BridgeOperationList(json, offset, limit, { offset = it }, open)
                }
            } else {
                BridgeNote("Downloads are unavailable under this server policy or this phone's permissions.")
            }
            BridgeNote("Transfers continue on the server after this phone disconnects. Publication waits for server idle.")
        }
        "civitai" -> {
            if (!state.can("civitai.search")) {
                BridgeNote(state.reason("civitai.search"))
                return
            }
            OutlinedTextField(query, {
                query = it.take(200)
            }, label = { Text("Search Civitai models") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            FilledTonalButton(onClick = {
                search = query.trim()
                offset = 0
            }) { Text("Search") }
            BridgeRemote(controller, state, "civitai/models", paging + ("query" to search)) { json ->
                BridgeCard {
                    json.children("items").forEach { model ->
                        TextPreference(
                            model.text("name"),
                            "${model.text("type")} · ${model.text("creator").ifEmpty { "Unknown creator" }}",
                        ) {
                            model.longValue("id")?.takeIf { it > 0 }?.let { open("civitai-model/$it") }
                        }
                    }
                    if (json.children("items").isEmpty()) BridgeNote("No results on this page.")
                    BridgePages(offset, limit, json.children("items").size, json.longValue("total")) { offset = it }
                }
            }
            BridgeNote("Civitai is configured on your server. Its API key is not stored on this phone.")
        }
        "civitai-model" ->
            if (state.can("civitai.search")) {
                BridgeRemote(controller, state, "civitai/models/${page.substringAfter('/').toLong()}") { model ->
                    BridgeCard {
                        Text(model.text("name"), style = MaterialTheme.typography.titleLarge)
                        Text("${model.text("type")} · ${model.text("creator")}")
                        BridgeNote("Source: ${model.text("source_url")}")
                        BridgeNote("License: ${model.get("license")?.toString()?.take(4000) ?: "Not supplied"}")
                        model.children("versions").forEach { version ->
                            TextPreference(version.text("name"), version.text("base_model")) {
                                version.longValue("id")?.takeIf { it > 0 }?.let { open("civitai-version/$it") }
                            }
                        }
                    }
                }
            } else {
                BridgeNote(state.reason("civitai.search"))
            }
        "civitai-version" ->
            if (state.can("civitai.search")) {
                BridgeRemote(controller, state, "civitai/versions/${page.substringAfter('/').toLong()}") { version ->
                    BridgeCard {
                        Text(version.text("name"), style = MaterialTheme.typography.titleLarge)
                        BridgeNote(version.text("base_model"))
                        version.children("files").forEach { file ->
                            HorizontalDivider()
                            Text(file.text("name"))
                            BridgeNote("${file.text("type")} · ${bridgeBytes(file.number("size_kb")?.times(1024)?.toLong())}")
                            file.text("sha256").takeIf { it.isNotBlank() }?.let { BridgeNote("SHA-256: $it") }
                            BridgeActionButton(state, "civitai.download", "Download this file…") {
                                val body =
                                    JsonObject().apply {
                                        addProperty("version_id", version.longValue("id"))
                                        addProperty("file_id", file.longValue("id"))
                                    }
                                form(
                                    BridgeForm(
                                        "Download selected file",
                                        "civitai.download",
                                        "POST",
                                        "civitai/downloads",
                                        fixed = body,
                                        fields = listOf(rootField(roots)),
                                        wait = true,
                                        explanation = "Download ${file.text(
                                            "name",
                                        )} to your server. The server resolves its source, hash and license.",
                                    ),
                                )
                            }
                        }
                        if (version.children("files").isEmpty()) BridgeNote("No files were supplied for this version.")
                    }
                }
            } else {
                BridgeNote(state.reason("civitai.search"))
            }
    }
}
