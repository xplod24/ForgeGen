package com.example.forgegen

import android.app.KeyguardManager
import coil.imageLoader
import com.example.forgegen.ui.components.GalleryKeyDialog
import com.example.forgegen.ui.components.LicenseDialog
import com.example.forgegen.ui.components.MarkdownText
import com.example.forgegen.ui.components.RESTART_NEEDS_FLAG_HINT
import com.example.forgegen.ui.components.RestartForgeDialog
import com.example.forgegen.ui.components.UpdateMoveCard
import com.example.forgegen.ui.components.WhatsNewDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.launch

/* ============================================================================
 * SETTINGS ROWS
 * A setting is a row inside a rounded card: title, a short explanation, and a switch or an arrow.
 * ============================================================================ */

@Composable
fun SwitchPreference(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { onCheckedChange(!checked) }
                .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).alpha(if (enabled) 1f else 0.5f)) {
            Text(text = title, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    lineHeight = 17.sp,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
        )
    }
}

/** A row that opens something (a dialog, a system page) or acts at once; [trailing] replaces the arrow. */
@Composable
fun TextPreference(
    title: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    titleColor: Color = Color.Unspecified,
    trailing: (@Composable () -> Unit)? = { RowArrow() },
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).alpha(if (enabled) 1f else 0.5f)) {
            Text(
                text = title,
                fontSize = 16.sp,
                color = if (titleColor == Color.Unspecified) MaterialTheme.colorScheme.onSurface else titleColor,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    lineHeight = 17.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
private fun RowArrow() {
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
    )
}

/* ============================================================================
 * SETTINGS PAGES (2.3.0, the owner's pick "B")
 * The main page shows the server and seven categories, each with a line on how it is set; a category opens its own
 * page of cards. The search on the main page shows the matching settings themselves, from every page.
 * ============================================================================ */

private enum class SettingsPage(
    val title: String,
    val icon: ImageVector,
    val tint: Color,
) {
    SERVER("Server", Icons.Default.Dns, Color(0xFF3E80FF)),
    FEATURES("Features", Icons.Default.ToggleOn, Color(0xFFEC407A)),
    APPEARANCE("Appearance", Icons.Default.Palette, Color(0xFFA87BFF)),
    NOTIFICATIONS("Notifications", Icons.Default.Notifications, Color(0xFFFFA726)),
    QUEUE("Queue & Background", Icons.Default.Bedtime, Color(0xFF26C6DA)),
    PRIVACY("Privacy & Security", Icons.Default.Shield, Color(0xFF66BB6A)),
    UPDATES("Updates", Icons.Default.SystemUpdate, Color(0xFF42A5F5)),
    DATA("Backup & Data", Icons.Default.Storage, Color(0xFF9E9E9E)),
    DEBUG("Debug", Icons.Default.BugReport, Color(0xFFEF5350)),
}

/**
 * One setting (or a block of its page, e.g. the Live Update checklist) in its [page] and [group], with the [words] the
 * search looks through (its title and explanation, plus other words people may look for).
 */
private class SettingItem(
    val page: SettingsPage,
    val group: String?,
    val words: String,
    val content: @Composable () -> Unit,
)

/** Every word of [query] appears in the item's words (ignoring case). */
private fun SettingItem.matches(query: String): Boolean {
    val text = words.lowercase()
    return query.lowercase().split(' ').filter { it.isNotBlank() }.all { it in text }
}

private val CONNECTED_GREEN = Color(0xFF4CAF50)
private val SEARCHING_AMBER = Color(0xFFFFB300)

/* ============================================================================
 * SETUP SCREEN COMPOSABLE
 * ============================================================================ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    viewModel: ForgeViewModel,
    onDismiss: () -> Unit,
    // Shown over the main screen, under its top bar: the status bar is already covered there. Counting it again
    // left a wide empty band above "Settings" (2.3.0).
    belowTopBar: Boolean = false,
) {
    // --- STATE OBSERVATION ---
    val config by viewModel.config.collectAsStateWithLifecycle()
    val updateManifest by viewModel.updateManifest.collectAsStateWithLifecycle()
    val updateDownload by viewModel.updateDownload.collectAsStateWithLifecycle()
    val readyUpdate by viewModel.readyUpdate.collectAsStateWithLifecycle()
    val installingUpdate by viewModel.installingUpdate.collectAsStateWithLifecycle()
    val updateConfirm by viewModel.updateConfirm.collectAsStateWithLifecycle()
    val queueActive by viewModel.isQueueActive.collectAsStateWithLifecycle()
    val generating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val pingMs by viewModel.pingMs.collectAsStateWithLifecycle()
    val tagListStatus by viewModel.tagListStatus.collectAsStateWithLifecycle()
    // The server page (3.3.0): what Forge tells about itself, and restarting it.
    val serverInfo by viewModel.serverInfo.collectAsStateWithLifecycle()
    val restartingSince by viewModel.restartingSince.collectAsStateWithLifecycle()
    val serverMemory by viewModel.serverMemory.collectAsStateWithLifecycle()
    var confirmRestart by remember { mutableStateOf(false) }
    var showAllExtensions by remember { mutableStateOf(false) }

    // --- DIALOG VISIBILITY STATES ---
    var showUrlDialog by remember { mutableStateOf(false) }

    var showTimeoutDialog by remember { mutableStateOf(false) }

    var showProfilesDialog by remember { mutableStateOf(false) }

    var showNotificationModeDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showImageCacheDialog by remember { mutableStateOf(false) }
    // How much the image cache takes, read when Backup & Data opens (3.4.0).
    var imageCacheUsed by remember { mutableStateOf<Long?>(null) }
    var dismissedUpdateVersion by remember { mutableIntStateOf(-1) }
    // All notes of the offered update, from "Show All" on its card.
    var showAllReleaseNotes by remember { mutableStateOf(false) }
    // "Install" while the queue works: installing ends the app, so it asks first.
    var confirmInstallDuringQueue by remember { mutableStateOf(false) }
    var showLicenseDialog by remember { mutableStateOf(false) }
    var showGalleryKeyDialog by remember { mutableStateOf(false) }
    val galleryExtension by viewModel.galleryExtension.collectAsStateWithLifecycle()

    var isTestingConnection by remember { mutableStateOf(false) }
    var testStatus by remember { mutableStateOf<String?>(null) }
    var testResults by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var showWipeDataDialog by remember { mutableStateOf(false) }

    // Debug mode (DebugMode): 8 quick taps on "App Version", then the password.
    val debugUnlocked by viewModel.debugUnlocked.collectAsStateWithLifecycle()
    var versionTaps by remember { mutableIntStateOf(0) }
    var lastVersionTap by remember { mutableLongStateOf(0L) }
    var showDebugPasswordDialog by remember { mutableStateOf(false) }

    // The open page (null: the main page) and the search on the main page.
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    // Kept outside the pages, so the main page is where it was when a category page is closed.
    val homeListState = rememberLazyListState()
    // "Turn Off Debug Mode" empties the Debug page: back to the main page.
    LaunchedEffect(debugUnlocked) { if (!debugUnlocked && page == SettingsPage.DEBUG) page = null }

    // --- SYSTEM SERVICES ---
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // "Backup": the settings, presets, server profiles and wildcards to a file of the user's choice, and back.
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) viewModel.exportBackup(uri)
        }
    var importUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> importUri = uri }

    val keyguardManager = remember { context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager }
    val isDeviceSecure = remember { keyguardManager.isDeviceSecure }

    // "Install": the app steps aside so Android can replace it (the owner's request, 3.0.0-3).
    fun installNow() = viewModel.installUpdate { context.findActivity()?.moveTaskToBack(true) }

    // Runs [action] after the phone's PIN/biometrics check when the app lock is on or being set up; without a phone
    // lock there is nothing to check against.
    val confirmWithPhoneLock: (title: String, action: () -> Unit) -> Unit = { title, action ->
        val activity = context.findActivity()
        if (activity != null && AppLock.isAvailable(context)) {
            AppLock.authenticate(
                activity = activity,
                allowBiometrics = config.useNativeSecurity && config.useBiometricLock,
                title = title,
                onSuccess = action,
            )
        } else {
            action()
        }
    }

    val pm = remember { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
    var isIgnoringBattery by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(context.packageName)) }

    // Live Updates: offered on Android 16+ (3.5.0; only Samsung's One UI 8 before); the system can also turn live
    // notifications off for the app, and whether it showed the last job's progress as one is read back (LiveUpdates).
    val isLiveUpdateSupported = remember { LiveUpdates.isSupported(context) }
    val isSamsung = remember { LiveUpdates.isSamsung(context) }
    var isLiveUpdateAllowed by remember { mutableStateOf(LiveUpdates.isAllowedBySystem(context)) }
    var areNotificationsAllowed by remember { mutableStateOf(LiveUpdates.areNotificationsAllowed(context)) }
    var promotedLastTime by remember { mutableStateOf(LiveUpdates.promotedLastTime(context)) }
    // The latest release is another app (3.5.2-1): whether it is installed yet, read again when the app comes back.
    val movesTo = readyUpdate?.takeIf { it.versionCode == updateManifest?.versionCode }?.movesTo
    var moveTargetInstalled by remember { mutableStateOf(false) }
    LaunchedEffect(movesTo) { moveTargetInstalled = movesTo != null && SelfUpdate.isInstalled(context, movesTo) }

    // --- LIFECYCLE OBSERVER FOR BATTERY OPTIMIZATION AND LIVE NOTIFICATION REFRESH ---
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    isIgnoringBattery = pm.isIgnoringBatteryOptimizations(context.packageName)
                    isLiveUpdateAllowed = LiveUpdates.isAllowedBySystem(context)
                    areNotificationsAllowed = LiveUpdates.areNotificationsAllowed(context)
                    promotedLastTime = LiveUpdates.promotedLastTime(context)
                    readyUpdate?.movesTo?.let { moveTargetInstalled = SelfUpdate.isInstalled(context, it) }
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Back: a category page returns to the main page, a search is cleared, then the settings close (both as a nav
    // destination and as the overlay on MainScreen).
    BackHandler {
        when {
            page != null -> page = null
            query.isNotEmpty() -> query = ""
            else -> onDismiss()
        }
    }

    // Asks the server's usual endpoints once each, with a short timeout; the result opens in a dialog.
    val runDiagnostics: () -> Unit = {
        isTestingConnection = true
        testStatus = "Running diagnostics..."
        testResults = emptyList()

        scope.launch(Dispatchers.IO) {
            val testClientBuilder =
                okhttp3.OkHttpClient
                    .Builder()
                    .connectTimeout(1, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(1, java.util.concurrent.TimeUnit.SECONDS)
            val testClient = testClientBuilder.build()
            val endpoints = listOf("progress", "memory", "options", "samplers", "schedulers", "sd-models", "loras")
            val resultsMap = java.util.concurrent.ConcurrentHashMap<String, String>()

            coroutineScope {
                val deferreds =
                    endpoints.map { ep ->
                        async {
                            val start = System.currentTimeMillis()
                            try {
                                var cleanUrl = config.apiUrl.trimEnd('/')
                                if (cleanUrl.isNotEmpty() &&
                                    !cleanUrl.startsWith("http://") &&
                                    !cleanUrl.startsWith("https://")
                                ) {
                                    cleanUrl = "http://$cleanUrl"
                                }

                                val req = okhttp3.Request.Builder().url("$cleanUrl/sdapi/v1/$ep").build()
                                testClient.newCall(req).execute().use { res ->
                                    val time = System.currentTimeMillis() - start
                                    if (res.isSuccessful) {
                                        resultsMap[ep] = "${time}ms ✔"
                                    } else if (res.code == 401 || res.code == 403) {
                                        resultsMap[ep] = "Auth Needed ✘"
                                    } else {
                                        resultsMap[ep] = "Err ${res.code} ✘"
                                    }
                                }
                            } catch (_: Exception) {
                                resultsMap[ep] = "Failed ✘"
                            }
                        }
                    }
                deferreds.awaitAll()
            }

            val finalResults = endpoints.map { it to (resultsMap[it] ?: "Timeout ✘") }
            val allSuccess = finalResults.all { it.second.contains("✔") }

            withContext(Dispatchers.Main) {
                testResults = finalResults
                testStatus = if (allSuccess) "All Systems Operational!" else "Some APIs Failed."
                if (!allSuccess) {
                    val failedEps = finalResults.filter { !it.second.contains("✔") }.map { it.first }
                    viewModel.showToast("Unresponsive: ${failedEps.joinToString(", ")}")
                }
            }
        }
    }

    val connectionText =
        when (connection) {
            ServerConnection.CONNECTED -> "Connected · $pingMs ms"
            ServerConnection.SEARCHING -> "Connecting..."
            ServerConnection.OFFLINE -> "Offline"
        }
    val connectionColor =
        when (connection) {
            ServerConnection.CONNECTED -> CONNECTED_GREEN
            ServerConnection.SEARCHING -> SEARCHING_AMBER
            ServerConnection.OFFLINE -> MaterialTheme.colorScheme.error
        }

    /* ==========================================================
     * EVERY SETTING, by page and group (also what the search looks through)
     * ========================================================== */
    val settings =
        buildList {
            fun add(
                page: SettingsPage,
                group: String?,
                words: String,
                content: @Composable () -> Unit,
            ) = add(SettingItem(page, group, words, content))

            // --- SERVER ---
            add(SettingsPage.SERVER, "Connection", "server connection status connected offline retry ping $connectionText") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(connectionColor))
                    Spacer(Modifier.width(10.dp))
                    Text(connectionText, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    if (connection != ServerConnection.CONNECTED) {
                        FilledTonalButton(onClick = { viewModel.reconnect() }) { Text("Retry") }
                    }
                }
            }
            add(SettingsPage.SERVER, "Connection", "server address api url ip host ${config.apiUrl}") {
                TextPreference(title = "Server Address", subtitle = config.apiUrl) { showUrlDialog = true }
            }
            add(SettingsPage.SERVER, "Connection", "server profiles switch add saved servers") {
                val count = config.serverProfiles.size
                TextPreference(
                    title = "Server Profiles",
                    subtitle = "$count saved · switch between servers or save this one",
                ) { showProfilesDialog = true }
            }
            add(SettingsPage.SERVER, "Connection", "connection timeout seconds requests slow") {
                TextPreference(
                    title = "Connection Timeout",
                    subtitle = "${config.timeout} s for ordinary requests (generating has its own 2 h limit)",
                ) { showTimeoutDialog = true }
            }
            add(SettingsPage.SERVER, "Connection", "diagnostics test endpoints check server api") {
                TextPreference(
                    title = "Diagnostics",
                    subtitle = "Asks 7 of the server's endpoints how fast they answer",
                ) { runDiagnostics() }
            }
            // The secret key of the server's Infinite Image Browsing (3.5.0), for a gallery that asks for one.
            add(SettingsPage.SERVER, "Gallery", "gallery key iib secret key infinite image browsing locked password") {
                val saved = GalleryKey.savedFor(config) != null
                TextPreference(
                    title = "Gallery Key",
                    subtitle =
                        when {
                            galleryExtension.state == ForgeGalleryManager.Extension.LOCKED ->
                                if (saved) {
                                    "The saved key no longer opens the gallery: tap to enter the new one"
                                } else {
                                    "Needed: the gallery asks for its secret key"
                                }
                            galleryExtension.state == ForgeGalleryManager.Extension.KEY_NOT_SET ->
                                "Forge has a login, so IIB_SECRET_KEY must first be set on the server"
                            saved -> "Saved for this server (only its fingerprint) · tap to change or remove"
                            else -> "Not needed by this server"
                        },
                ) { showGalleryKeyDialog = true }
            }

            // What Forge tells about itself (3.3.0, board 2C): read once per server when the page shows.
            val info = serverInfo
            add(SettingsPage.SERVER, "Server Info", "forge version server info commit ${info?.version.orEmpty()}") {
                LaunchedEffect(connection) { if (connection == ServerConnection.CONNECTED) viewModel.loadServerInfo() }
                TextPreference(
                    title = "Forge",
                    subtitle =
                        when {
                            info?.version != null -> "Version ${info.version}"
                            info?.reportProblem != null -> info.reportProblem
                            connection != ServerConnection.CONNECTED -> "Shown when the app is connected"
                            else -> "Reading the server's report..."
                        },
                    trailing = {
                        Icon(Icons.Default.Refresh, contentDescription = "Read Again", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                ) { viewModel.loadServerInfo(again = true) }
            }
            if (info?.gpu != null || info?.system != null) {
                add(SettingsPage.SERVER, "Server Info", "gpu graphics card vram cuda ${info.gpu.orEmpty()}") {
                    val vram = serverMemory?.takeIf { it.hasVram }?.let { " · ${"%.0f".format(it.vramTotal)} GB" }.orEmpty()
                    TextPreference(title = "GPU", subtitle = (info.gpu ?: "Not reported") + vram, trailing = null) {}
                }
                add(SettingsPage.SERVER, "Server Info", "system windows linux python torch ${info.system.orEmpty()}") {
                    TextPreference(title = "System", subtitle = info.system ?: "Not reported", trailing = null) {}
                }
            }
            if (info?.report != null) {
                add(SettingsPage.SERVER, "Server Info", "share server report sysinfo bug report file") {
                    TextPreference(
                        title = "Share Server Report",
                        subtitle = "The server's details as a file, for a bug report (its settings and folders included)",
                        trailing = {
                            Icon(Icons.Default.Share, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                    ) { viewModel.serverReportIntent()?.let { context.startActivity(it) } }
                }
            }
            info?.extensions?.let { extensions ->
                val group = "Extensions · ${extensions.size}"
                val used = extensions.filter { it.purpose != null }
                val others = extensions.filter { it.purpose == null }
                used.forEach { extension ->
                    add(SettingsPage.SERVER, group, "extension ${extension.name} ${extension.purpose}") {
                        ExtensionRow(extension)
                    }
                }
                if (others.isNotEmpty()) {
                    if (showAllExtensions) {
                        others.forEach { extension ->
                            add(SettingsPage.SERVER, group, "extension ${extension.name}") { ExtensionRow(extension) }
                        }
                    } else {
                        add(SettingsPage.SERVER, group, "extensions more list") {
                            TextPreference(
                                title = "${others.size} More",
                                subtitle = others.take(3).joinToString(", ") { it.name } + "...",
                            ) { showAllExtensions = true }
                        }
                    }
                }
            }
            add(SettingsPage.SERVER, "Control", "restart forge server reboot api-server-stop") {
                val restarting = restartingSince > 0
                TextPreference(
                    title = if (restarting) "Forge Is Restarting" else "Restart Forge",
                    subtitle =
                        when {
                            restarting -> "The app waits for it and goes on when it is back"
                            info?.canRestart == false -> RESTART_NEEDS_FLAG_HINT
                            else -> "The queue waits and goes on when it is back"
                        },
                    enabled = !restarting && info?.canRestart != false && connection == ServerConnection.CONNECTED,
                    titleColor = MaterialTheme.colorScheme.error,
                ) { confirmRestart = true }
            }

            // --- APPEARANCE ---
            add(SettingsPage.APPEARANCE, null, "theme dark light system colors") {
                TextPreference(
                    title = "Theme",
                    subtitle = if (config.themeMode == THEME_SYSTEM) "System default" else config.themeMode,
                ) { showThemeDialog = true }
            }
            add(SettingsPage.APPEARANCE, null, "show active tags ui edit tags prompts row") {
                SwitchPreference(
                    title = "Show Active Tags UI",
                    subtitle = "Show the \"Edit Tags\" row under the prompts to switch tags off and reorder them",
                    checked = config.showActiveTagsUI,
                    onCheckedChange = { viewModel.saveConfig(config.copy(showActiveTagsUI = it)) },
                )
            }
            add(SettingsPage.APPEARANCE, null, "show grid after batch images finished") {
                SwitchPreference(
                    title = "Show Grid After Batch",
                    subtitle = "Show all images of a batch as a grid when it finishes, until you open one or the next job starts",
                    checked = config.showGridAfterGeneration,
                    onCheckedChange = { viewModel.saveConfig(config.copy(showGridAfterGeneration = it)) },
                )
            }

            // --- FEATURES (3.4.0) ---
            // Each one can be switched off; off, it leaves the screen and the app no longer asks the server for its data.
            fun feature(
                group: String,
                words: String,
                title: String,
                subtitle: String,
                checked: Boolean,
                update: (Boolean) -> AppConfig,
            ) = add(SettingsPage.FEATURES, group, "feature switch $words") {
                SwitchPreference(
                    title = title,
                    subtitle = subtitle,
                    checked = checked,
                    onCheckedChange = { viewModel.saveConfig(update(it)) },
                )
            }
            feature(
                "Prompt",
                "tag suggestions autocomplete danbooru keyboard wildcards lora tagcomplete",
                "Tag Suggestions",
                "While you type a prompt, tags, __wildcards and <lora: names above the keyboard",
                config.tagSuggestions,
            ) { config.copy(tagSuggestions = it) }
            if (config.tagSuggestions) {
                add(SettingsPage.FEATURES, "Prompt", "tag list danbooru csv tagcomplete download reload") {
                    TextPreference(title = "Tag List", subtitle = tagListText(tagListStatus)) { viewModel.reloadTagList() }
                }
            }
            // Off unless turned on here (3.1.0, the owner's decision).
            feature(
                "Prompt",
                "server styles styles.csv prompt style preset web ui",
                "Server Styles",
                "The server's saved styles (styles.csv) in a row under the prompt; the chosen ones go with every job",
                config.serverStyles,
            ) { config.copy(serverStyles = it) }
            feature(
                "Prompt",
                "embeddings textual inversion negative suggestions",
                "Embeddings",
                "The server's embeddings in the LoRA list and in the suggestions above the keyboard",
                config.embeddings,
            ) { config.copy(embeddings = it) }
            feature(
                "LoRAs and Models",
                "lora details base model trigger words training tags fits filter metadata",
                "LoRA Details",
                "Each LoRA's model and trigger words, its details and the \"Fits\" filter. Off: their data (megabytes " +
                    "with many LoRAs) is not downloaded",
                config.loraDetails,
            ) { config.copy(loraDetails = it) }
            feature(
                "LoRAs and Models",
                "model lora pictures previews thumbnails images",
                "Model and LoRA Pictures",
                "The pictures next to the models and LoRAs, from the server's model folders",
                config.resourcePictures,
            ) { config.copy(resourcePictures = it) }
            feature(
                "Main Screen",
                "live preview generating image progress",
                "Live Preview",
                "The image as it forms while it is generated. Off: the server sends none",
                config.livePreview,
            ) { config.copy(livePreview = it) }
            feature(
                "Main Screen",
                "memory meters vram ram top bar server memory",
                "Memory Meters",
                "The server's VRAM and RAM in the top bar. Off: an icon opens Server Memory, which reads them when it opens",
                config.memoryMeters,
            ) { config.copy(memoryMeters = it) }
            feature(
                "Gallery",
                "folder covers gallery thumbnails",
                "Folder Covers",
                "A folder's newest images as its picture",
                config.folderCovers,
            ) { config.copy(folderCovers = it) }
            feature(
                "Gallery",
                "check favorites missing deleted gone server",
                "Check Favorites",
                "Tells when favorites are gone from the server",
                config.favoritesCheck,
            ) { config.copy(favoritesCheck = it) }
            feature(
                "Gallery",
                "image jobs upscale more like this variance seed",
                "Image Jobs",
                "Upscale, More Like This and Variance for gallery images",
                config.imageJobs,
            ) { config.copy(imageJobs = it) }
            feature(
                "Server",
                "other jobs server queue web ui waiting internal progress",
                "Other Jobs on the Server",
                "Says when jobs from the web UI or another app go first",
                config.serverQueue,
            ) { config.copy(serverQueue = it) }

            // --- NOTIFICATIONS ---
            add(SettingsPage.NOTIFICATIONS, "Alerts", "notify on batch finish notification completed alert") {
                SwitchPreference(
                    title = "Notify on Batch Finish",
                    subtitle = "Get alerted when a generation batch is fully completed",
                    checked = config.notifOnBatchFinish,
                    onCheckedChange = { viewModel.saveConfig(config.copy(notifOnBatchFinish = it)) },
                )
            }
            add(SettingsPage.NOTIFICATIONS, "Alerts", "vibrate on batch finish vibration") {
                SwitchPreference(
                    title = "Vibrate on Batch Finish",
                    subtitle = "A short vibration when a batch is done while the app is on screen",
                    checked = config.vibrateOnFinish,
                    onCheckedChange = { viewModel.saveConfig(config.copy(vibrateOnFinish = it)) },
                )
            }
            add(SettingsPage.NOTIFICATIONS, "Alerts", "notify on queue finish notification jobs done alert") {
                SwitchPreference(
                    title = "Notify on Queue Finish",
                    subtitle = "Get alerted when all queued jobs are finished",
                    checked = config.notifOnQueueFinish,
                    onCheckedChange = { viewModel.saveConfig(config.copy(notifOnQueueFinish = it)) },
                )
            }
            add(SettingsPage.NOTIFICATIONS, "Progress", "progress notification mode simple verbose disabled eta") {
                val modeDesc =
                    when (config.notificationMode) {
                        "Disabled" -> "Only a static notice (Android requires one)"
                        "Verbose" -> "Also batch size and an Open App button"
                        else -> "Image number, progress and ETA"
                    }
                TextPreference(
                    title = "Progress Notification Mode",
                    subtitle = "${config.notificationMode}: $modeDesc",
                ) { showNotificationModeDialog = true }
            }
            add(SettingsPage.NOTIFICATIONS, "Progress", "show progress as live update now bar samsung status chip lock screen") {
                // Off until the user turns it on (Google's rules: a Live Update the user asked for); greyed out below
                // Android 16. Saved as nowBarProgress, its name up to 3.4.2.
                SwitchPreference(
                    title = "Show Progress as Live Update",
                    subtitle =
                        when {
                            !isLiveUpdateSupported -> "Android 16 or newer only"
                            config.notificationMode == "Disabled" -> "Needs a Progress Notification Mode other than Disabled"
                            isSamsung -> "The generation progress in the Now Bar at the bottom of the lock screen and in the status bar"
                            else -> "The generation progress as a chip in the status bar and on the lock screen"
                        },
                    checked = isLiveUpdateSupported && config.nowBarProgress,
                    enabled = isLiveUpdateSupported,
                    onCheckedChange = {
                        if (it) {
                            LiveUpdates.forgetPromotion(context)
                            promotedLastTime = null
                        }
                        viewModel.saveConfig(config.copy(nowBarProgress = it))
                    },
                )
            }
            if (isLiveUpdateSupported && config.nowBarProgress) {
                add(SettingsPage.NOTIFICATIONS, "Progress", "live update checklist did it work live notifications developer options") {
                    LiveUpdateChecklist(
                        notificationsAllowed = areNotificationsAllowed,
                        liveNotificationsAllowed = isLiveUpdateAllowed,
                        promotedLastTime = promotedLastTime,
                        isSamsung = isSamsung,
                        onOpenNotificationSettings = { LiveUpdates.openNotificationSettings(context) },
                        onOpenLiveNotificationSettings = { LiveUpdates.openSystemSettings(context) },
                        onOpenDeveloperOptions = { LiveUpdates.openDeveloperOptions(context) },
                    )
                }
            }

            // --- QUEUE & BACKGROUND ---
            add(SettingsPage.QUEUE, null, "overnight batch mode failed jobs night queue connection retry") {
                SwitchPreference(
                    title = "Overnight Batch Mode",
                    subtitle = "A failed job is set aside and the queue goes on; a lost connection is retried until " +
                        "the server is back. A summary at the end tells what failed.",
                    checked = config.overnightMode,
                    onCheckedChange = { viewModel.saveConfig(config.copy(overnightMode = it)) },
                )
            }
            add(SettingsPage.QUEUE, null, "keep screen on awake display generating") {
                SwitchPreference(
                    title = "Keep Screen On",
                    subtitle = "Keep the screen on while images are being generated and the app is open",
                    checked = config.keepScreenOn,
                    onCheckedChange = { viewModel.saveConfig(config.copy(keepScreenOn = it)) },
                )
            }
            if (!isIgnoringBattery) {
                add(SettingsPage.QUEUE, null, "remove battery restrictions optimization screen off background") {
                    TextPreference(
                        title = "Remove Battery Restrictions",
                        subtitle = "Let the queue run with the screen off (recommended for Overnight Batch Mode)",
                    ) {
                        // Asks for this app directly; the list of all apps is the fallback where the dialog is missing.
                        try {
                            context.startActivity(
                                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
                            )
                        } catch (e: android.content.ActivityNotFoundException) {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    }
                }
            }

            // --- PRIVACY & SECURITY ---
            add(SettingsPage.PRIVACY, "Privacy", "hide prompts in notifications privacy") {
                SwitchPreference(
                    title = "Hide Prompts in Notifications",
                    subtitle = "Finished-batch notifications do not show the prompt (never on the lock screen anyway)",
                    checked = config.hidePromptsInNotifications,
                    onCheckedChange = { viewModel.saveConfig(config.copy(hidePromptsInNotifications = it)) },
                )
            }
            add(SettingsPage.PRIVACY, "Privacy", "hide app in recents recent apps screen preview") {
                SwitchPreference(
                    title = "Hide App in Recents",
                    subtitle =
                        if (config.useNativeSecurity) {
                            "Always on while the App Lock is on"
                        } else {
                            "The recent apps screen shows no picture of the app (Android 13 and newer)"
                        },
                    checked = config.hideInRecents || config.useNativeSecurity,
                    enabled = !config.useNativeSecurity,
                    onCheckedChange = { viewModel.saveConfig(config.copy(hideInRecents = it)) },
                )
            }
            add(SettingsPage.PRIVACY, "Privacy", "block screenshots screen recording") {
                SwitchPreference(
                    title = "Block Screenshots",
                    subtitle = "No screenshots or screen recordings of the app",
                    checked = config.blockScreenshots,
                    onCheckedChange = { viewModel.saveConfig(config.copy(blockScreenshots = it)) },
                )
            }
            add(SettingsPage.PRIVACY, "Privacy", "save to phone privately private folder gallery cloud backup") {
                SwitchPreference(
                    title = "Save to Phone Privately",
                    subtitle = "Saved images go to the app's own folder instead of the phone's gallery, so gallery apps " +
                        "and their cloud backup do not see them. They are deleted when the app is uninstalled.",
                    checked = config.savePrivately,
                    onCheckedChange = { viewModel.saveConfig(config.copy(savePrivately = it)) },
                )
            }
            add(SettingsPage.PRIVACY, "Privacy", "share without generation data metadata prompt seed model") {
                SwitchPreference(
                    title = "Share Without Generation Data",
                    subtitle = "Shared images leave without their prompt, seed and model",
                    checked = config.shareWithoutMetadata,
                    onCheckedChange = { viewModel.saveConfig(config.copy(shareWithoutMetadata = it)) },
                )
            }
            if (!isDeviceSecure) {
                add(SettingsPage.PRIVACY, "App Lock", "app lock security pin pattern password") {
                    Text(
                        text = "Your device does not have a PIN, Pattern, or Password set. Please set a lock in your device settings to enable App Security.",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
            add(SettingsPage.PRIVACY, "App Lock", "app lock security pin pattern password unlock") {
                SwitchPreference(
                    title = "App Lock",
                    subtitle = "Ask for the phone's PIN, pattern or password when opening the app",
                    checked = config.useNativeSecurity,
                    // Stays switchable while on, so it can be turned off after the phone lock was removed.
                    enabled = isDeviceSecure || config.useNativeSecurity,
                    onCheckedChange = { enable ->
                        // Both directions need the phone's lock: off, so whoever holds the unlocked phone cannot just
                        // switch it off; on, so nobody enables a lock they are unable to open.
                        confirmWithPhoneLock(if (enable) "Turn on the app lock" else "Turn off the app lock") {
                            viewModel.markUnlocked()
                            viewModel.saveConfig(
                                config.copy(useNativeSecurity = enable, useBiometricLock = enable && config.useBiometricLock),
                            )
                        }
                    },
                )
            }
            add(SettingsPage.PRIVACY, "App Lock", "allow biometrics fingerprint face unlock") {
                SwitchPreference(
                    title = "Allow Biometrics",
                    subtitle = "Also unlock with fingerprint or face (the PIN keeps working)",
                    checked = config.useBiometricLock,
                    enabled = config.useNativeSecurity && isDeviceSecure,
                    onCheckedChange = { allow ->
                        confirmWithPhoneLock(if (allow) "Allow biometric unlock" else "Turn off biometric unlock") {
                            viewModel.saveConfig(config.copy(useBiometricLock = allow))
                        }
                    },
                )
            }

            // --- UPDATES ---
            val download = updateDownload
            if (download != null) {
                // "Install Update" was tapped: it downloads in the background (UpdateDownloadService), also with the
                // app closed or the screen locked; the notification (and a Live Update) shows the same.
                add(SettingsPage.UPDATES, null, "update downloading progress ${download.versionName}") {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                                .clip(MaterialTheme.shapes.small)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                                .padding(16.dp),
                    ) {
                        Text("Downloading ${download.versionName}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(Modifier.height(10.dp))
                        if (download.total <= 0) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(progress = { download.fraction }, modifier = Modifier.fillMaxWidth())
                        }
                        Spacer(Modifier.height(8.dp))
                        val detail =
                            when {
                                download.total > 0 ->
                                    "${(download.fraction * 100).toInt()}% · " +
                                        "${"%.1f".format(java.util.Locale.US, download.done / 1048576.0)} / " +
                                        "${"%.1f".format(java.util.Locale.US, download.total / 1048576.0)} MB"
                                else -> "Starting the download..."
                            }
                        Text(detail, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                        Text(
                            "It goes on in the background: you can leave the app or lock the screen.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                }
            }
            val installing = installingUpdate
            if (installing != null) {
                // "Install" was tapped: the app went to the background and Android replaces it. No button here, so it
                // cannot be started twice (3.0.0-3).
                add(SettingsPage.UPDATES, null, "update installing $installing") {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                                .clip(MaterialTheme.shapes.small)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                                .padding(16.dp),
                    ) {
                        Text("Installing $installing", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        val confirm = updateConfirm
                        Text(
                            if (confirm != null) {
                                "Android wants you to confirm the install this time."
                            } else {
                                "Android replaces ForgeGen now; a notification says when it is done."
                            },
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                        if (confirm != null) {
                            Spacer(Modifier.height(8.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                Button(onClick = {
                                    try {
                                        context.startActivity(confirm)
                                    } catch (e: Exception) {
                                        viewModel.showToast("Cannot open the confirmation: ${e.message}")
                                    }
                                }) { Text("Confirm Install") }
                            }
                        }
                    }
                }
            }
            val manifest = updateManifest
            if (download == null && installing == null && manifest != null && movesTo != null) {
                // Another app (3.5.2-1): no "Install", no "Dismiss"; the steps move the data to it.
                add(SettingsPage.UPDATES, null, "update new app move data export import uninstall ${manifest.versionName}") {
                    UpdateMoveCard(
                        versionName = manifest.versionName,
                        newPackage = movesTo,
                        newAppInstalled = moveTargetInstalled,
                        onExport = {
                            val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                            exportLauncher.launch("forgegen-backup-$date.json")
                        },
                        onInstall = {
                            try {
                                context.startActivity(SelfUpdate.installerIntent(context, SelfUpdate.apkFile(context)))
                            } catch (e: Exception) {
                                viewModel.showToast("Cannot open the installer: ${e.message}")
                            }
                        },
                        onOpen = {
                            context.packageManager.getLaunchIntentForPackage(movesTo)?.let {
                                context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            } ?: viewModel.showToast("The new app is not installed yet")
                        },
                        onUninstall = {
                            try {
                                context.startActivity(SelfUpdate.appInfoIntent(context))
                            } catch (e: Exception) {
                                viewModel.showToast("Cannot open the app info: ${e.message}")
                            }
                        },
                    )
                }
            }
            val offerUpdate = download == null && installing == null && manifest != null && movesTo == null
            if (offerUpdate && manifest != null && manifest.versionCode != dismissedUpdateVersion) {
                val ready = readyUpdate?.takeIf { it.versionCode == manifest.versionCode }
                add(SettingsPage.UPDATES, null, "update available download install new version ${manifest.versionName}") {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                                .clip(MaterialTheme.shapes.small)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                                .padding(16.dp),
                    ) {
                        Text(
                            if (ready != null) "Update Ready: ${manifest.versionName}" else "Update Available: ${manifest.versionName}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                        )
                        if (ready != null) {
                            Text(
                                "Downloaded and checked · ${"%.1f".format(java.util.Locale.US, ready.size / 1048576.0)} MB",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        val notes = manifest.changelog ?: emptyList()
                        val noteCount = releaseNoteCount(notes)
                        if (notes.isNotEmpty()) {
                            Text("What's new:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            // Drawn from Markdown like the What's New notes (3.0.0-2: the ** of bold text showed), with
                            // the release's kind and its New / Changed / Fixed headings (3.0.0-4).
                            MarkdownText(
                                markdown = releaseNotesMarkdown(notes, maxItems = 3),
                                textStyle = MaterialTheme.typography.bodySmall,
                            )
                            if (noteCount > 3) {
                                TextButton(onClick = { showAllReleaseNotes = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                    Text("Show All ($noteCount)")
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { dismissedUpdateVersion = manifest.versionCode }) {
                                Text("Dismiss")
                            }
                            Spacer(Modifier.width(8.dp))
                            // Two steps (3.0.0-3): "Download" runs in UpdateDownloadService with its progress here, in the
                            // notifications and a Live Update, and the app stays open; once the file matches the release,
                            // "Install" sends the app to the background and Android replaces it.
                            if (ready != null) {
                                Button(onClick = {
                                    if (queueActive || generating) confirmInstallDuringQueue = true else installNow()
                                }) { Text("Install") }
                            } else {
                                Button(onClick = { viewModel.downloadUpdate() }) { Text("Download") }
                            }
                        }
                    }
                }
            }
            add(SettingsPage.UPDATES, null, "app version build number") {
                TextPreference(
                    title = "App Version",
                    subtitle = "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                    trailing = null,
                ) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    versionTaps = if (now - lastVersionTap <= DebugMode.TAP_WINDOW_MS) versionTaps + 1 else 1
                    lastVersionTap = now
                    if (versionTaps >= DebugMode.TAPS_TO_UNLOCK) {
                        versionTaps = 0
                        if (debugUnlocked) viewModel.showToast("Debug mode is already on") else showDebugPasswordDialog = true
                    }
                }
            }
            add(SettingsPage.UPDATES, null, "license gpl gnu free software source code copyright author") {
                TextPreference(
                    title = "License",
                    subtitle = "${AppLicense.NAME} · © 2026 ${AppLicense.AUTHOR}",
                ) { showLicenseDialog = true }
            }
            add(SettingsPage.UPDATES, null, "install updates automatically background wi-fi") {
                SwitchPreference(
                    title = "Install Updates Automatically",
                    subtitle =
                        "Looks for new releases every 6 hours on Wi-Fi and installs them in the background " +
                            "(never while the queue works); off: only a notification",
                    checked = config.autoInstallUpdates,
                    onCheckedChange = { viewModel.saveConfig(config.copy(autoInstallUpdates = it)) },
                )
            }
            add(SettingsPage.UPDATES, null, "check for updates github release") {
                TextPreference(
                    title = "Check for Updates",
                    subtitle = "Look for a newer release on GitHub",
                    trailing = { Icon(Icons.Default.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                ) {
                    dismissedUpdateVersion = -1
                    viewModel.checkForUpdates(manual = true)
                }
            }

            // --- BACKUP & DATA ---
            add(SettingsPage.DATA, "Backup", "export settings backup file presets profiles wildcards favorites queue") {
                TextPreference(
                    title = "Export Settings",
                    subtitle = "Settings, presets, server profiles, wildcards, favorites and the queue to a file",
                ) {
                    val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                    exportLauncher.launch("forgegen-backup-$date.json")
                }
            }
            add(SettingsPage.DATA, "Backup", "import settings backup file restore favorites queue") {
                TextPreference(
                    title = "Import Settings",
                    subtitle = "Replaces the settings, presets and server profiles; adds the wildcards, favorites and queued jobs",
                ) {
                    importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                }
            }
            add(SettingsPage.DATA, "Logs", "save logs on out of memory oom report downloads") {
                SwitchPreference(
                    title = "Save Logs on Out of Memory",
                    subtitle = "When the app or the server runs out of memory, save a report with the app's log to " +
                        "Downloads (ForgeGen-OOM-date.txt). Android needs no storage permission for it.",
                    checked = config.saveOomLogs,
                    onCheckedChange = { viewModel.saveConfig(config.copy(saveOomLogs = it)) },
                )
            }
            // The image cache on the phone (3.4.0): its size, up to 2.5 GB (the owner's decision), and emptying it.
            add(SettingsPage.DATA, "Storage", "image cache size thumbnails storage space disk") {
                LaunchedEffect(Unit) { imageCacheUsed = withContext(Dispatchers.IO) { ImageCache.usedBytes(context) } }
                val restart = ImageCache.builtWithMb != 0 && ImageCache.builtWithMb != config.imageCacheMb
                TextPreference(
                    title = "Image Cache",
                    subtitle =
                        "Up to ${ImageCache.label(config.imageCacheMb)} of thumbnails and images · " +
                            (imageCacheUsed?.let { "${ImageCache.formatBytes(it)} used" } ?: "counting...") +
                            if (restart) " · the new size applies after a restart" else "",
                ) { showImageCacheDialog = true }
            }
            add(SettingsPage.DATA, "Storage", "clear image cache thumbnails free space") {
                TextPreference(title = "Clear Image Cache", subtitle = "Frees the space; images load again from the server") {
                    scope.launch(Dispatchers.IO) {
                        clearImageCache(context)
                        imageCacheUsed = ImageCache.usedBytes(context)
                        viewModel.showToast("Image cache cleared")
                    }
                }
            }
            add(SettingsPage.DATA, "Danger Zone", "wipe application data delete clear reset") {
                TextPreference(
                    title = "Wipe Application Data",
                    subtitle = "Choose what to delete: settings, presets, profiles, history, wildcards, image index",
                    titleColor = MaterialTheme.colorScheme.error,
                ) {
                    showWipeDataDialog = true
                }
            }

            // --- DEBUG (only after unlocking the debug mode) ---
            if (debugUnlocked) {
                add(SettingsPage.DEBUG, null, "debug tools logs raw settings test notifications") { DebugPanel(viewModel) }
            }
        }

    // How each category is set, one line under its name on the main page.
    val summaries: Map<SettingsPage, String> =
        mapOf(
            SettingsPage.APPEARANCE to
                listOfNotNull(
                    when (config.themeMode) {
                        THEME_LIGHT -> "Light theme"
                        THEME_DARK -> "Dark theme"
                        else -> "System theme"
                    },
                    "grid after batch".takeIf { config.showGridAfterGeneration },
                    "tags row".takeIf { config.showActiveTagsUI },
                ).joinToString(" · "),
            SettingsPage.FEATURES to FeatureSwitches.summary(config),
            SettingsPage.NOTIFICATIONS to
                listOfNotNull(
                    "queue finish".takeIf { config.notifOnQueueFinish },
                    "batch finish".takeIf { config.notifOnBatchFinish },
                    "vibration".takeIf { config.vibrateOnFinish },
                    "${config.notificationMode} progress",
                    "Live Update".takeIf { isLiveUpdateSupported && config.nowBarProgress },
                ).joinToString(" · ").replaceFirstChar { it.uppercase() },
            SettingsPage.QUEUE to
                listOfNotNull(
                    if (config.overnightMode) "Overnight mode on" else "Overnight mode off",
                    "screen stays on".takeIf { config.keepScreenOn },
                    "battery restricted".takeIf { !isIgnoringBattery },
                ).joinToString(" · "),
            SettingsPage.PRIVACY to
                listOfNotNull(
                    if (config.useNativeSecurity) "App Lock on" else "App Lock off",
                    "prompts hidden".takeIf { config.hidePromptsInNotifications },
                    "screenshots blocked".takeIf { config.blockScreenshots },
                    "private saving".takeIf { config.savePrivately },
                ).joinToString(" · "),
            SettingsPage.UPDATES to
                (
                    installingUpdate?.let { "Installing $it" }
                        ?: updateDownload?.let { d -> "Downloading ${d.versionName} · ${(d.fraction * 100).toInt()}%" }
                        ?: readyUpdate?.takeIf { it.versionCode == updateManifest?.versionCode }?.let {
                            if (it.movesTo != null) {
                                "${it.versionName} is a new app: move your data"
                            } else {
                                "${it.versionName} ready to install"
                            }
                        }
                        ?: ("${BuildConfig.VERSION_NAME} · " + if (config.autoInstallUpdates) "installs automatically" else "notifies only")
                ),
            SettingsPage.DATA to "Export, import, logs, image cache, wipe",
            SettingsPage.DEBUG to "Tools for testing the app",
        )
    val hasUpdate = updateManifest != null && updateManifest?.versionCode != dismissedUpdateVersion

    // --- UI STRUCTURE ---
    Scaffold(contentWindowInsets = if (belowTopBar) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets) { padding ->
        AnimatedContent(
            targetState = page,
            modifier = Modifier.fillMaxSize().padding(padding),
            transitionSpec = {
                // Into a category from the right, back out to the left, a short slide with a fade.
                val forward = targetState != null
                (
                    slideInHorizontally(tween(SETTINGS_PAGE_MS)) { width -> if (forward) width / 4 else -width / 4 } +
                        fadeIn(tween(SETTINGS_PAGE_MS))
                ) togetherWith
                    (
                        slideOutHorizontally(tween(SETTINGS_PAGE_MS)) { width -> if (forward) -width / 4 else width / 4 } +
                            fadeOut(tween(SETTINGS_PAGE_MS))
                    )
            },
            label = "settings_page",
        ) { shown ->
            if (shown == null) {
                SettingsHome(
                    listState = homeListState,
                    query = query,
                    onQueryChange = { query = it },
                    serverLine = connectionText,
                    serverColor = connectionColor,
                    serverAddress = config.apiUrl.removePrefix("http://").removePrefix("https://"),
                    profileCount = config.serverProfiles.size,
                    pages = SettingsPage.entries.filter { it != SettingsPage.SERVER && (it != SettingsPage.DEBUG || debugUnlocked) },
                    summaries = summaries,
                    hasUpdate = hasUpdate,
                    settings = settings,
                    onOpen = { page = it },
                )
            } else {
                SettingsPageContent(
                    page = shown,
                    settings = settings.filter { it.page == shown },
                    onBack = { page = null },
                )
            }
        }
    }

        /* ==========================================================
         * DIALOG BUILDERS
         * ========================================================== */

        if (showDebugPasswordDialog) {
            var password by remember { mutableStateOf("") }
            var checking by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            AlertDialog(
                onDismissRequest = { if (!checking) showDebugPasswordDialog = false },
                title = { Text("Debug Mode") },
                text = {
                    OutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            error = null
                        },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = error != null,
                        supportingText = error?.let { { Text(it) } },
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = password.isNotEmpty() && !checking,
                        onClick = {
                            checking = true
                            scope.launch {
                                val result = viewModel.debugUnlock(password)
                                checking = false
                                when (result) {
                                    DebugMode.UnlockResult.UNLOCKED -> {
                                        showDebugPasswordDialog = false
                                        viewModel.showToast("Debug mode on: see Settings > Debug")
                                    }
                                    DebugMode.UnlockResult.WRONG_PASSWORD -> error = "Wrong password"
                                    DebugMode.UnlockResult.TOO_MANY_ATTEMPTS -> error = "Too many attempts, try again in a minute"
                                }
                            }
                        },
                    ) { Text("Unlock") }
                },
                dismissButton = { TextButton(onClick = { showDebugPasswordDialog = false }) { Text("Cancel") } },
            )
        }

        importUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { importUri = null },
            title = { Text("Import Settings?") },
            text = {
                Text(
                    "The settings, presets and server profiles on this phone are replaced by the ones in the file. " +
                        "Its wildcards are added (a wildcard with the same name is replaced).",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.importBackup(uri)
                    importUri = null
                }) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { importUri = null }) { Text("Cancel") } },
        )
    }

    if (showWipeDataDialog) {
            var wipeSettings by remember { mutableStateOf(false) }
            var wipePresets by remember { mutableStateOf(false) }
            var wipeProfiles by remember { mutableStateOf(false) }
            var wipeHistory by remember { mutableStateOf(false) }
            var wipeWildcards by remember { mutableStateOf(false) }
            var wipeImages by remember { mutableStateOf(false) }

            val promptHistory by viewModel.promptHistory.collectAsStateWithLifecycle()
            val wildcards by viewModel.wildcards.collectAsStateWithLifecycle()

            AlertDialog(
                onDismissRequest = { showWipeDataDialog = false },
                title = { Text("Wipe Application Data") },
                text = {
                    Column {
                        Text("Select which data to permanently delete:")
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = wipeSettings, onCheckedChange = { wipeSettings = it })
                            Text(
                                "App Settings & State (1 item" +
                                    (if (debugUnlocked) ", turns the debug mode off)" else ")"),
                                fontSize = 14.sp,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = wipePresets, onCheckedChange = { wipePresets = it })
                            Text("Presets (${config.presets.size} items)", fontSize = 14.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = wipeProfiles, onCheckedChange = { wipeProfiles = it })
                            Text("Server Profiles (${config.serverProfiles.size} items)", fontSize = 14.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = wipeHistory, onCheckedChange = { wipeHistory = it })
                            Text("Prompt History (${promptHistory.size} items)", fontSize = 14.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = wipeWildcards, onCheckedChange = { wipeWildcards = it })
                            Text("Wildcards (${wildcards.size} items)", fontSize = 14.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = wipeImages, onCheckedChange = { wipeImages = it })
                            Text("Images Index (Re-fetch later)", fontSize = 14.sp)
                        }
                    }
                },
                confirmButton = {
                    // With nothing ticked the button used to silently wipe settings and history anyway.
                    val anySelected = wipeSettings || wipePresets || wipeProfiles || wipeHistory || wipeWildcards || wipeImages
                    TextButton(
                        enabled = anySelected,
                        onClick = {
                            val wipe = {
                                if (wipeSettings) viewModel.wipeSettings()
                                if (wipePresets) viewModel.wipePresets()
                                if (wipeProfiles) viewModel.wipeServerProfiles()
                                if (wipeHistory) viewModel.wipePromptHistory()
                                if (wipeWildcards) viewModel.wipeWildcards()
                                if (wipeImages) viewModel.wipeGalleryIndex()
                                viewModel.showToast("Selected data wiped")
                                showWipeDataDialog = false
                            }
                            // Wiping the settings also switches the app lock off, so it needs the same check.
                            if (config.useNativeSecurity) confirmWithPhoneLock("Wipe application data") { wipe() } else wipe()
                        },
                    ) {
                        val labelColor =
                            if (anySelected) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            }
                        Text("Wipe Selected", color = labelColor)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showWipeDataDialog = false }) {
                        Text("Cancel")
                    }
                },
            )
        }

        // The download progress dialog is shown globally by MainActivity.

        if (confirmInstallDuringQueue) {
            AlertDialog(
                onDismissRequest = { confirmInstallDuringQueue = false },
                title = { Text("Install Now?") },
                text = { Text("Installing closes ForgeGen, so the queue stops. Its jobs are kept and wait for you.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmInstallDuringQueue = false
                        installNow()
                    }) { Text("Install") }
                },
                dismissButton = { TextButton(onClick = { confirmInstallDuringQueue = false }) { Text("Cancel") } },
            )
        }

        val notesOf = updateManifest
        if (showAllReleaseNotes && notesOf != null) {
            WhatsNewDialog(
                markdown = releaseNotesMarkdown(notesOf.changelog.orEmpty()),
                onDismiss = { showAllReleaseNotes = false },
                title = "What's New in ${notesOf.versionName}",
            )
        }

        if (showGalleryKeyDialog) {
            GalleryKeyDialog(
                saved = GalleryKey.savedFor(config) != null,
                onSave = viewModel::saveGalleryKey,
                onRemove = { viewModel.forgetGalleryKey() },
                onDismiss = { showGalleryKeyDialog = false },
            )
        }

        if (showLicenseDialog) {
            LicenseDialog(
                onDismiss = { showLicenseDialog = false },
                onOpenSource = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AppLicense.SOURCE_URL))) },
            )
        }

        if (showThemeDialog) {
            AlertDialog(
                onDismissRequest = { showThemeDialog = false },
                title = { Text("Theme") },
                text = {
                    Column {
                        listOf(
                            THEME_SYSTEM to "System default",
                            THEME_LIGHT to "Light",
                            THEME_DARK to "Dark",
                        ).forEach { (mode, name) ->
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            viewModel.saveConfig(config.copy(themeMode = mode))
                                            showThemeDialog = false
                                        }.padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = config.themeMode == mode, onClick = null)
                                Spacer(Modifier.width(16.dp))
                                Text(name, fontSize = 16.sp)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showThemeDialog = false }) { Text("Close") }
                },
            )
        }

        if (showImageCacheDialog) {
            AlertDialog(
                onDismissRequest = { showImageCacheDialog = false },
                title = { Text("Image Cache") },
                text = {
                    Column {
                        Text(
                            "Thumbnails and images kept on the phone, so they show at once. The size applies after a restart.",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        ImageCache.SIZES_MB.forEach { mb ->
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            viewModel.saveConfig(config.copy(imageCacheMb = mb))
                                            showImageCacheDialog = false
                                        }.padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = config.imageCacheMb == mb, onClick = null)
                                Spacer(Modifier.width(16.dp))
                                Text(ImageCache.label(mb), fontSize = 16.sp)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showImageCacheDialog = false }) { Text("Close") }
                },
            )
        }

        if (showNotificationModeDialog) {
            AlertDialog(
                onDismissRequest = { showNotificationModeDialog = false },
                title = { Text("Progress Notification Mode") },
                text = {
                    Column {
                        val modes =
                            listOf(
                                Triple("Simple", "Simple", "Image number, progress and ETA"),
                                Triple("Verbose", "Verbose", "Also batch size and an Open App button"),
                                Triple("Disabled", "Disabled", "Only a static notice (Android requires one)"),
                            )
                        modes.forEach { (internalValue, displayName, desc) ->
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            viewModel.saveConfig(config.copy(notificationMode = internalValue))
                                            showNotificationModeDialog = false
                                        }.padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = config.notificationMode == internalValue, onClick = null)
                                Spacer(Modifier.width(16.dp))
                                Column {
                                    Text(displayName, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                    Text(desc, fontSize = 12.sp, color = Color.Gray)
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showNotificationModeDialog = false }) { Text("Close") } },
            )
        }



        if (showUrlDialog) {
            var tempUrl by remember { mutableStateOf(config.apiUrl) }
            AlertDialog(
                onDismissRequest = { showUrlDialog = false },
                title = { Text("API URL") },
                text = { OutlinedTextField(value = tempUrl, onValueChange = { tempUrl = it }, modifier = Modifier.fillMaxWidth()) },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.saveConfig(config.copy(apiUrl = tempUrl))
                        showUrlDialog = false
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { showUrlDialog = false }) { Text("Cancel") } },
            )
        }


        if (confirmRestart) {
            RestartForgeDialog(viewModel, generating = generating, onDismiss = { confirmRestart = false })
        }

        if (showTimeoutDialog) {
            var tempTimeout by remember { mutableStateOf(config.timeout.toString()) }
            AlertDialog(
                onDismissRequest = { showTimeoutDialog = false },
                title = { Text("Connection Timeout (sec)") },
                text = {
                    OutlinedTextField(
                        value = tempTimeout,
                        onValueChange = { tempTimeout = it },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val t = tempTimeout.toIntOrNull() ?: 10
                        viewModel.saveConfig(config.copy(timeout = t))
                        showTimeoutDialog = false
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { showTimeoutDialog = false }) { Text("Cancel") } },
            )
        }



        if (showProfilesDialog) {
            var newProfileName by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showProfilesDialog = false },
                title = { Text("Server Profiles") },
                text = {
                    Column {
                        LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                            items(config.serverProfiles) { profile ->
                                Row(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                viewModel.saveConfig(config.copy(apiUrl = profile.url))
                                                showProfilesDialog = false
                                            }.padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(profile.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                        Text(profile.url, fontSize = 12.sp, color = Color.Gray)
                                    }
                                    IconButton(onClick = { viewModel.removeServerProfile(profile.name) }) {
                                        Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Text("Add New Profile", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        OutlinedTextField(
                            value = newProfileName,
                            onValueChange = { newProfileName = it },
                            label = { Text("Profile Name (Uses current API URL)") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = {
                                if (newProfileName.isNotBlank() && config.apiUrl.isNotBlank()) {
                                    viewModel.addServerProfile(newProfileName, config.apiUrl)
                                    newProfileName = ""
                                }
                            },
                            modifier = Modifier.align(Alignment.End).padding(top = 8.dp),
                        ) {
                            Text("Save Profile")
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showProfilesDialog = false }) { Text("Close") } },
            )
        }

        if (isTestingConnection || testStatus != null) {
            AlertDialog(
                onDismissRequest = {
                    isTestingConnection = false
                    testStatus = null
                },
                title = { Text("Diagnostics") },
                text = {
                    Column {
                        Text(
                            text = testStatus ?: "",
                            color =
                                if (testStatus?.contains("Operational") ==
                                    true
                                ) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        testResults.forEach { (endpoint, result) ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(endpoint, fontSize = 12.sp)
                                Text(result, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        isTestingConnection = false
                        testStatus = null
                    }) { Text("Close") }
                },
            )
        }
}

/** Empties the image cache on the phone and in memory (3.4.0); images load again from the server. */
@OptIn(coil.annotation.ExperimentalCoilApi::class)
private fun clearImageCache(context: Context) {
    val loader = context.imageLoader
    loader.memoryCache?.clear()
    loader.diskCache?.clear()
}

/** Into a category page and back: a short slide with a fade. */
private const val SETTINGS_PAGE_MS = 250

/** The main page: the search, the server, the categories; while searching, the matching settings themselves. */
@Composable
private fun SettingsHome(
    listState: LazyListState,
    query: String,
    onQueryChange: (String) -> Unit,
    serverLine: String,
    serverColor: Color,
    serverAddress: String,
    profileCount: Int,
    pages: List<SettingsPage>,
    summaries: Map<SettingsPage, String>,
    hasUpdate: Boolean,
    settings: List<SettingItem>,
    onOpen: (SettingsPage) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    // Below the list the generation sheet's handle covers the screen's bottom edge.
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        item {
            Text(
                "Settings",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
            )
        }
        item {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("Search settings") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Default.Close, "Clear Search") }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors =
                    TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
        if (query.isBlank()) {
            item {
                ServerCard(
                    address = serverAddress,
                    line = serverLine,
                    color = serverColor,
                    profileCount = profileCount,
                    onClick = { onOpen(SettingsPage.SERVER) },
                )
            }
            item {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    SettingsCard(
                        pages.map { category ->
                            {
                                CategoryRow(
                                    page = category,
                                    summary = summaries[category].orEmpty(),
                                    badge = category == SettingsPage.UPDATES && hasUpdate,
                                    onClick = { onOpen(category) },
                                )
                            }
                        },
                    )
                }
            }
            item {
                Text(
                    "ForgeGen ${BuildConfig.VERSION_NAME}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                )
            }
        } else {
            val found = settings.filter { it.matches(query) }
            if (found.isEmpty()) {
                item {
                    Text(
                        "No settings match \"${query.trim()}\"",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                    )
                }
            }
            // The real settings, under the name of the page each one is on.
            found.groupBy { it.page }.forEach { (resultPage, results) ->
                item(key = resultPage.name) {
                    Column {
                        SectionLabel(resultPage.title)
                        SettingsCard(results.map { it.content })
                    }
                }
            }
        }
    }
}

/** A category's own page: a back arrow with its name, then its groups of settings, each group a card. */
@Composable
private fun SettingsPageContent(
    page: SettingsPage,
    settings: List<SettingItem>,
    onBack: () -> Unit,
) {
    val groups = mutableListOf<Pair<String?, MutableList<SettingItem>>>()
    settings.forEach { setting ->
        val last = groups.lastOrNull()
        if (last != null && last.first == setting.group) last.second += setting else groups += setting.group to mutableListOf(setting)
    }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to Settings") }
                Spacer(Modifier.width(4.dp))
                Text(page.title, style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp))
            }
        }
        groups.forEachIndexed { index, (group, items) ->
            item(key = "group$index") {
                Column {
                    if (group != null) SectionLabel(group) else Spacer(Modifier.height(8.dp))
                    SettingsCard(items.map { it.content })
                }
            }
        }
    }
}

/** A group's name above its card. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        color = MaterialTheme.colorScheme.primary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(start = 32.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

/** Rows in one rounded card, with thin lines between them. */
@Composable
private fun SettingsCard(rows: List<@Composable () -> Unit>) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column {
            rows.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(modifier = Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                row()
            }
        }
    }
}

/** A category's coloured icon on a tile of its colour. */
@Composable
private fun CategoryIcon(
    page: SettingsPage,
    size: Dp,
) {
    Box(
        modifier = Modifier.size(size).clip(RoundedCornerShape(size * 0.35f)).background(page.tint.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(page.icon, contentDescription = null, tint = page.tint, modifier = Modifier.size(size * 0.55f))
    }
}

@Composable
private fun CategoryRow(
    page: SettingsPage,
    summary: String,
    badge: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryIcon(page, 40.dp)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(page.title, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(
                summary,
                fontSize = 13.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (badge) {
            Text(
                "New",
                color = MaterialTheme.colorScheme.onPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier =
                    Modifier
                        .padding(start = 8.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        RowArrow()
    }
}

/** The server at the top of the main page: its address, how the connection goes, and the saved profiles. */
@Composable
private fun ServerCard(
    address: String,
    line: String,
    color: Color,
    profileCount: Int,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CategoryIcon(SettingsPage.SERVER, 48.dp)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(address, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "$line · $profileCount ${if (profileCount == 1) "profile" else "profiles"}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            RowArrow()
        }
    }
}

/**
 * What Live Updates need (3.5.0): notifications allowed and live notifications allowed by the system, which the app
 * can read, and whether the system showed the last job's progress as one (LiveUpdates.notePromotion). Samsung's own
 * rule is listed with a button to the developer options, and the rest with buttons to the pages where it is changed.
 */
@Composable
private fun LiveUpdateChecklist(
    notificationsAllowed: Boolean,
    liveNotificationsAllowed: Boolean,
    promotedLastTime: Boolean?,
    isSamsung: Boolean,
    onOpenNotificationSettings: () -> Unit,
    onOpenLiveNotificationSettings: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
) {
    // Inside the Notifications card, on a tinted panel of its own.
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("For Live Updates", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            LiveUpdateCheck(notificationsAllowed, "Notifications allowed")
            LiveUpdateCheck(liveNotificationsAllowed, "Live notifications allowed by the system")
            LiveUpdateCheck(
                promotedLastTime,
                when (promotedLastTime) {
                    true -> "Shown as a Live Update: yes, during the last job"
                    false -> "Shown as a Live Update: no, the phone kept it as a normal notification"
                    null -> "Shown as a Live Update: not checked yet, start a job"
                },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (isSamsung) {
                    "Samsung also needs \"Live notifications for all apps\" turned on in the developer options (otherwise " +
                        "it shows only the apps on its own list), and ForgeGen's notifications shown on the lock screen, " +
                        "with their content."
                } else {
                    "The lock screen also needs ForgeGen's notifications shown with their content. Some phones add " +
                        "rules of their own: the line above tells whether yours showed the progress as a Live Update."
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Two buttons per row at most, so they fit on narrow phones.
            if (!liveNotificationsAllowed) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onOpenLiveNotificationSettings) { Text("Live Notifications") }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onOpenNotificationSettings) { Text("Notifications") }
                if (isSamsung) TextButton(onClick = onOpenDeveloperOptions) { Text("Developer Options") }
            }
        }
    }
}

/** One line of the checklist: done, missing, or (null) not known yet. */
@Composable
private fun LiveUpdateCheck(
    ok: Boolean?,
    text: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Icon(
            when (ok) {
                true -> Icons.Default.CheckCircle
                false -> Icons.Default.Warning
                null -> Icons.Default.HelpOutline
            },
            contentDescription =
                when (ok) {
                    true -> "Done"
                    false -> "Missing"
                    null -> "Not known yet"
                },
            tint =
                when (ok) {
                    true -> Color(0xFF2E7D32)
                    false -> MaterialTheme.colorScheme.error
                    null -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 13.sp)
    }
}

/** The "Tag List" line of the settings: how many tags, from which file and when, or why there are none. */
private fun tagListText(status: ForgeTagManager.Status): String {
    val date =
        status.savedAt.takeIf { it > 0 }?.let {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(it))
        }
    val saved = String.format(java.util.Locale.US, "%,d tags from %s", status.count, status.file) + (date?.let { ", saved $it" } ?: "")
    return when (status.source) {
        ForgeTagManager.Source.OFF -> "Off"
        ForgeTagManager.Source.NONE -> "Downloaded from the server's tagcomplete extension once connected"
        ForgeTagManager.Source.LOADING -> "Loading…"
        ForgeTagManager.Source.READY -> saved + (status.message?.let { ". $it" } ?: "") + ". Tap to download it again"
        ForgeTagManager.Source.MISSING ->
            (status.message ?: "The server has no tagcomplete extension") +
                if (status.count > 0) ". Using the saved $saved" else ". Wildcards and LoRAs are still suggested"
        ForgeTagManager.Source.FAILED -> (status.message ?: "The tag list could not be loaded") + ". Tap to try again"
    }
}

/** An extension of the server: the ones the app uses with a tick and what for, the others with their version. */
@Composable
private fun ExtensionRow(extension: ServerExtension) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (extension.purpose != null) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CONNECTED_GREEN, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f).alpha(if (extension.enabled) 1f else 0.5f)) {
            Text(extension.name, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val detail =
                listOfNotNull(
                    extension.purpose,
                    extension.version.takeIf { it.isNotBlank() && extension.purpose == null },
                    "off".takeIf { !extension.enabled },
                ).joinToString(" · ")
            if (detail.isNotEmpty()) {
                Text(detail, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f), maxLines = 1)
            }
        }
    }
}
