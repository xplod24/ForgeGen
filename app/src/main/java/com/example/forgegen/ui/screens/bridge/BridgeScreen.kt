package com.example.forgegen

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.forgegen.ui.components.FloatingTopBar
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.CancellationException

@Composable
fun BridgeScreen(
    controller: BridgeController,
    onBack: () -> Unit,
    serverName: String = "Selected server",
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(controller, state.base) { if (state.enabled) controller.refresh() }
    DisposableEffect(controller, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) controller.refresh() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var stack by rememberSaveable(state.base, state.info?.serverId) { mutableStateOf(listOf("home")) }
    val savedPages = rememberSaveableStateHolder()
    val page = stack.last()
    val open: (String) -> Unit = { if (it != page) stack = stack + it }
    val back: () -> Unit = { if (stack.size > 1) stack = stack.dropLast(1) else onBack() }
    BackHandler(onBack = back)
    var form by remember(state.base, state.info?.serverId) { mutableStateOf<BridgeForm?>(null) }
    var confirmForget by remember { mutableStateOf(false) }
    var confirmDismiss by remember { mutableStateOf(false) }
    val roots by produceState<List<BridgeRootInfo>>(emptyList(), state.base, state.device?.id, state.capabilities) {
        value = emptyList()
        if (state.paired && state.scoped("models.read") && (state.can("models.count") || state.can("models.catalog"))) {
            try {
                val all = mutableListOf<BridgeRootInfo>()
                var offset = 0
                do {
                    val response =
                        BridgeApi.decode<BridgeRootInfoPage>(
                            controller.read(
                                "models/roots",
                                mapOf(
                                    "offset" to "$offset",
                                    "limit" to "100",
                                ),
                            ),
                        )
                    val next = response.items.orEmpty()
                    all += next
                    offset += next.size
                } while (next.size == 100 && (response.total == null || offset < response.total) && offset < 1000)
                value = all
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                value = emptyList()
            }
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            FloatingTopBar(title = bridgeTitle(page), onNavigate = back) {
                IconButton(onClick = { controller.refresh() }, enabled = state.enabled && !state.busy) {
                    Icon(Icons.Default.Refresh, "Refresh Bridge")
                }
            }
        },
    ) { insets ->
        savedPages.SaveableStateProvider("${state.base}|${state.info?.serverId}|$page") {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(insets)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (page == "home") {
                    SwitchPreference(
                        "Use ForgeGen Bridge",
                        "Optional tools for this server",
                        state.enabled,
                        onCheckedChange = controller::setEnabled,
                        enabled = state.base.isNotBlank(),
                    )
                    SelectionContainer { BridgeNote(state.base.ifEmpty { "Configure a valid Forge server address first." }) }
                }
                if (state.pairingOnly) {
                    BridgeCard {
                        Text("Pairing service only", style = MaterialTheme.typography.titleMedium)
                        BridgeNote("Pairing and device access are available. Start full Forge at this address, then tap Refresh for server tools.")
                    }
                }
                state.message?.let { BridgeCard { Text(it) } }
                if (state.pending != null && !state.busy) {
                    BridgeCard {
                        Text("Request outcome needs review", style = MaterialTheme.typography.titleSmall)
                        Text(state.pending!!.title)
                        BridgeNote("The server may already have accepted it. Retrying uses the same request key.")
                        TextButton(
                            onClick = controller::retryPending,
                            enabled = state.paired && state.can(state.pending!!.feature),
                        ) { Text("Retry same request") }
                        TextButton(onClick = { open("operations") }, enabled = state.paired) { Text("Review operations") }
                        TextButton(onClick = { confirmDismiss = true }) { Text("Forget this request…") }
                    }
                }
                if (state.lastOperationId != null && state.paired) {
                    TextButton(onClick = { open("operation/${state.lastOperationId}") }) { Text("View last accepted operation") }
                }
                if (state.paired) {
                    if (page == "home") {
                        BridgeOverview(state, serverName, open)
                    } else {
                        BridgeModule(controller, state, page, roots, open, { form = it })
                    }
                } else {
                    BridgeConnectionCard(state, controller)
                }
                if (page == "home") {
                    BridgeNote("Generation, your existing queue and gallery work without Bridge.")
                    TextButton(onClick = { confirmForget = true }, enabled = !state.busy) { Text("Forget pairing…") }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
    form?.let { action ->
        BridgeActionDialog(action, onDismiss = { form = null }) { body, query ->
            controller.perform(action.title, action.feature, action.method, action.path, body, query)
            form = null
        }
    }
    if (confirmForget || confirmDismiss) {
        AlertDialog(
            onDismissRequest = {
                confirmForget = false
                confirmDismiss = false
            },
            title = { Text(if (confirmForget) "Forget Bridge pairing?" else "Forget uncertain request?") },
            text = {
                Text(
                    "Server operations may continue. Review the server history before submitting the same work as a new request. Your Forge profile is kept.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (confirmForget) controller.forget() else controller.dismissPending()
                    confirmForget = false
                    confirmDismiss = false
                }) { Text("Forget") }
            },
            dismissButton = {
                TextButton(onClick = {
                    confirmForget = false
                    confirmDismiss = false
                }) { Text("Cancel") }
            },
        )
    }
}

private fun bridgeTitle(page: String): String =
    when (page.substringBefore('/')) {
        "home" -> "ForgeGen Bridge"
        "models" -> "Model counts"
        "catalog" -> "Models"
        "model" -> "Model details"
        "downloads" -> "Downloads"
        "civitai" -> "Civitai"
        "civitai-model" -> "Civitai model"
        "civitai-version" -> "Version & files"
        "extensions" -> "Extensions"
        "server" -> "Server"
        "devices" -> "Devices & access"
        "capabilities" -> "Granted features"
        "operation" -> "Operation details"
        else -> "Operations"
    }

@Composable
private fun BridgeConnectionCard(
    state: BridgeState,
    controller: BridgeController,
) {
    BridgeCard {
        Text(bridgeConnectionLabel(state.connection, state.pairingOnly), style = MaterialTheme.typography.titleLarge)
        state.info?.let { BridgeNote("Bridge ${it.bridgeVersion}") }
        when (state.connection) {
            BridgeConnection.OFF -> BridgeNote("Enable this optional integration to discover Bridge on the selected server.")
            BridgeConnection.ABSENT -> BridgeNote("Install ForgeGen Bridge on your server to use these optional tools.")
            BridgeConnection.SERVER_CHANGED ->
                BridgeNote(
                    "Verify the server identity, then forget the old pairing before using a new invitation.",
                )
            BridgeConnection.REVOKED -> BridgeNote("The owner revoked this phone's access. Create a new invitation to pair again.")
            BridgeConnection.AUTH_REQUIRED ->
                BridgeNote(
                    "An upstream proxy can require separate authentication. Bridge pairing does not change Forge credentials.",
                )
            else -> Unit
        }
        if (state.enabled && !state.busy) TextButton(onClick = controller::refresh) { Text("Check again") }
    }
    if (state.enabled && state.info != null && state.connection != BridgeConnection.SERVER_CHANGED) BridgePairingCard(state, controller)
}

@Composable
private fun BridgePairingCard(
    state: BridgeState,
    controller: BridgeController,
) {
    // Invitation secrets intentionally use remember, never rememberSaveable or persistent form storage.
    var invitation by remember(state.base) { mutableStateOf("") }
    var requireAdmin by remember(state.base) { mutableStateOf(false) }
    var name by remember { mutableStateOf("${Build.MANUFACTURER} ${Build.MODEL}".take(100)) }
    val parsed = remember(invitation, state.base) { runCatching { BridgeProtocol.invitation(invitation, state.base) } }
    val scanner =
        rememberLauncherForActivityResult(ScanContract()) { result ->
            result.contents?.let { invitation = it.take(32_768) }
        }
    BridgeCard {
        Text("Pair this phone", style = MaterialTheme.typography.titleMedium)
        BridgeNote("Create an invitation in your server's Bridge panel. Access is assigned by the server owner.")
        FilledTonalButton(onClick = {
            scanner.launch(
                ScanOptions()
                    .setCaptureActivity(BridgeCaptureActivity::class.java)
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("Scan your server's Bridge invitation")
                    .setOrientationLocked(false)
                    .setBeepEnabled(false),
            )
        }, enabled = !state.busy) { Text("Scan invitation QR") }
        OutlinedTextField(
            value = invitation,
            onValueChange = { invitation = it.take(32_768) },
            label = { Text("Or paste invitation JSON") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 3,
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(100) },
            label = { Text("Device name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        SwitchPreference(
            "Pair as administrator",
            "Requires an owner-issued admin_access invitation; the phone cannot grant itself permissions.",
            requireAdmin,
            onCheckedChange = { requireAdmin = it },
            enabled = state.info?.adminPairingSupported == true && !state.busy,
        )
        if (state.info?.adminPairingSupported != true) BridgeNote("Administrator pairing requires Bridge 1.2.0 or newer.")
        parsed.getOrNull()?.let { invite ->
            BridgeNote("Server: ${invite.base}")
            BridgeNote("Identity: ${invite.serverId}")
            BridgeNote("Expires: ${invite.expiresAt}")
        }
        if (invitation.isNotBlank() && parsed.isFailure) BridgeNote(parsed.exceptionOrNull()?.message ?: "Invalid invitation.")
        Button(onClick = {
            controller.pair(invitation, name, requireAdmin)
        }, enabled = parsed.isSuccess && name.isNotBlank() && !state.busy) { Text("Pair this phone") }
    }
}

@Composable
private fun BridgeOverview(
    state: BridgeState,
    serverName: String,
    open: (String) -> Unit,
) {
    BridgeCard {
        Text(serverName, style = MaterialTheme.typography.titleLarge)
        Text("Paired · Bridge ${state.info?.bridgeVersion}", color = MaterialTheme.colorScheme.primary)
        BridgeNote("This phone: ${state.device?.name ?: "Unknown device"}")
        BridgeNote(if (state.administrator) "Administrator access · all Bridge permissions" else "Access is assigned by the server owner.")
    }
    BridgeCard {
        TextPreference(
            "Models",
            if (state.fullModels) "Catalog, counts and server files" else if (state.can("models.scan_selected")) "Counts and selected-file scans" else "Counts only · set by server owner",
            enabled = state.can("models.count") || state.can("models.catalog") || state.can("models.scan_selected"),
        ) { open("models") }
        TextPreference("Downloads", "Server transfers and history", enabled = state.scoped("downloads.manage") && state.fullModels) {
            open("downloads")
        }
        TextPreference(
            "Civitai",
            if (state.can("civitai.search")) "Search versions and files" else state.reason("civitai.search"),
            enabled = state.can("civitai.search"),
        ) {
            open("civitai")
        }
        TextPreference(
            "Extensions",
            if (state.can("extensions.list")) "Server extensions and restart status" else state.reason("extensions.list"),
            enabled = state.can("extensions.list"),
        ) {
            open("extensions")
        }
        TextPreference("Server", "Status and maintenance", enabled = state.can("status")) { open("server") }
        TextPreference("Operations", "Accepted work and server activity", enabled = state.can("operations.read")) { open("operations") }
        TextPreference("Devices & access", "This phone and granted permissions") { open("devices") }
        TextPreference("Granted features", "Server policy and feature availability") { open("capabilities") }
    }
}

@Composable
private fun BridgeModule(
    controller: BridgeController,
    state: BridgeState,
    page: String,
    roots: List<BridgeRootInfo>,
    open: (String) -> Unit,
    form: (BridgeForm) -> Unit,
) {
    when (page.substringBefore('/')) {
        "models", "catalog", "model", "civitai", "civitai-model", "civitai-version", "downloads" ->
            BridgeModelsView(controller, state, page, roots, open, form)
        "extensions" -> BridgeExtensionsView(controller, state, form)
        "server" -> BridgeServerView(controller, state, form)
        "devices" -> BridgeDevicesView(controller, state, form)
        "capabilities" -> BridgeCapabilitiesView(state)
        else -> BridgeOperationsView(controller, state, page, open, form)
    }
}

@Composable
internal fun BridgeActionButton(
    state: BridgeState,
    feature: String,
    title: String,
    onClick: () -> Unit,
) {
    FilledTonalButton(onClick = onClick, enabled = state.can(feature) && !state.busy && state.pending == null) { Text(title) }
    if (!state.can(feature)) BridgeNote(state.reason(feature))
}

internal fun rootField(
    roots: List<BridgeRootInfo>,
    type: String? = null,
): BridgeField {
    val choices = roots.filter { type == null || it.type == type }.mapNotNull { r -> r.id?.let { it to "${r.type} · $it" } }
    return BridgeField("root_id", "Destination root ID", choices = choices)
}
