package com.example.forgegen

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.awaitResponse
import java.util.Locale
import java.util.concurrent.TimeUnit

/* ============================================================================
 * SHIMMER EFFECT (SKELETON LOADING & FRAMES)
 * Tworzy animowany gradient naśladujący ładowanie oraz ozdobne ramki
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
                .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).alpha(if (enabled) 1f else 0.5f)) {
            Text(text = title, fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    lineHeight = 18.sp,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
        )
    }
}

@Composable
fun TextPreference(
    title: String,
    value: String,
    subtitle: String? = value,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/* ============================================================================
 * SETUP SCREEN COMPOSABLE
 * ============================================================================ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    viewModel: ForgeViewModel,
    navController: NavHostController,
) {
    // --- STATE OBSERVATION ---
    val config by viewModel.config.collectAsStateWithLifecycle()
    val updateManifest by viewModel.updateManifest.collectAsStateWithLifecycle()
    val isUpdateDownloading by viewModel.isUpdateDownloading.collectAsStateWithLifecycle()
    val updateDownloadProgress by viewModel.updateDownloadProgress.collectAsStateWithLifecycle()
    val updateDownloadStats by viewModel.updateDownloadStats.collectAsStateWithLifecycle()

    // --- DIALOG VISIBILITY STATES ---
    var showUrlDialog by remember { mutableStateOf(false) }
    var showBasePathDialog by remember { mutableStateOf(false) }
    var showPathDialog by remember { mutableStateOf(false) }
    var showTimeoutDialog by remember { mutableStateOf(false) }
    var showCheckpointTimeoutDialog by remember { mutableStateOf(false) }
    var showProfilesDialog by remember { mutableStateOf(false) }
    var showPreviewModeDialog by remember { mutableStateOf(false) }

    var showNotificationModeDialog by remember { mutableStateOf(false) }
    var showUpdateChannelDialog by remember { mutableStateOf(false) }
    var showBetaTokenDialog by remember { mutableStateOf(false) }
    var dismissedUpdateVersion by remember { mutableIntStateOf(-1) }

    var isTestingConnection by remember { mutableStateOf(false) }
    var testStatus by remember { mutableStateOf<String?>(null) }
    var testResults by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var showWipeDataDialog by remember { mutableStateOf(false) }

    // --- SYSTEM SERVICES ---
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val keyguardManager = remember { context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager }
    val isDeviceSecure = remember { keyguardManager.isDeviceSecure }

    val pm = remember { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
    var isIgnoringBattery by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(context.packageName)) }

    // --- LIFECYCLE OBSERVER FOR BATTERY OPTIMIZATION REFRESH ---
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    isIgnoringBattery = pm.isIgnoringBatteryOptimizations(context.packageName)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val onBackClick =
        remember {
            {
                if (navController.currentDestination?.route == "setup") {
                    navController.popBackStack()
                }
                Unit
            }
        }

    // --- UI STRUCTURE ---
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontSize = 20.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            /* ==========================================================
             * CATEGORY: SERVER CONNECTION
             * ========================================================== */
            item { PreferenceCategory("Server Connection") }
            item {
                TextPreference(title = "API URL", value = config.apiUrl) { showUrlDialog = true }
            }
            item {
                TextPreference(title = "Server Profiles", subtitle = "${config.serverProfiles.size} saved profiles", value = "") {
                    showProfilesDialog =
                        true
                }
            }
            item {
                TextPreference(title = "Connection Timeout", subtitle = "${config.connectionTimeout} seconds", value = "") {
                    showTimeoutDialog =
                        true
                }
            }
            item {
                TextPreference(title = "Checkpoint Load Timeout", subtitle = "${config.checkpointTimeout} seconds", value = "") {
                    showCheckpointTimeoutDialog =
                        true
                }
            }
            item {
                TextPreference(title = "Test Connection", subtitle = "Run API diagnostics", value = "") {
                    isTestingConnection = true
                    testStatus = "Running diagnostics..."
                    testResults = emptyList()

                    scope.launch(Dispatchers.IO) {
                        val testClientBuilder =
                            OkHttpClient
                                .Builder()
                                .connectTimeout(1, TimeUnit.SECONDS)
                                .readTimeout(1, TimeUnit.SECONDS)
                        testClientBuilder.addInterceptor { chain ->
                            val reqBuilder = chain.request().newBuilder()
                            reqBuilder.header("Cookie", "IIB_S=bf63789069ec13d6b7b95a5176468e99f8940fe6aa65931edc17e1abf5c5e172")
                            chain.proceed(reqBuilder.build())
                        }
                        val testClient = testClientBuilder.build()
                        val endpoints = listOf("progress", "memory", "options", "samplers", "schedulers", "sd-models", "loras")
                        val resultsMap = java.util.concurrent.ConcurrentHashMap<String, String>()

                        kotlinx.coroutines.coroutineScope {
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

                                            val req = Request.Builder().url("$cleanUrl/sdapi/v1/$ep").build()
                                            testClient.newCall(req).awaitResponse().use { res ->
                                                val time = System.currentTimeMillis() - start
                                                if (res.isSuccessful) {
                                                    resultsMap[ep] = "${time}ms \u2714"
                                                } else if (res.code == 401 || res.code == 403) {
                                                    resultsMap[ep] = "Auth Needed \u2718"
                                                } else {
                                                    resultsMap[ep] = "Err ${res.code} \u2718"
                                                }
                                            }
                                        } catch (_: Exception) {
                                            resultsMap[ep] = "Failed \u2718"
                                        }
                                    }
                                }
                            deferreds.awaitAll()
                        }

                        val finalResults = endpoints.map { it to (resultsMap[it] ?: "Timeout \u2718") }
                        val allSuccess = finalResults.all { it.second.contains("\u2714") }

                        withContext(Dispatchers.Main) {
                            testResults = finalResults
                            testStatus = if (allSuccess) "All Systems Operational!" else "Some APIs Failed."
                            if (!allSuccess) {
                                val failedEps = finalResults.filter { !it.second.contains("\u2714") }.map { it.first }
                                viewModel.showToast("Unresponsive: ${failedEps.joinToString(", ")}")
                            }
                        }
                    }
                }
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            /* ==========================================================
             * CATEGORY: METADATA & CIVITAI
             * ========================================================== */
            item { PreferenceCategory("Metadata & Civitai") }
            item {
                TextPreference(
                    title = "Sync Models Now",
                    subtitle = "Fetch missing thumbnails and trigger words from Civitai API",
                    value = "",
                ) {
                    viewModel.syncCivitaiModelsManual()
                }
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            /* ==========================================================
             * CATEGORY: SECURITY
             * ========================================================== */
            item { PreferenceCategory("Security") }
            if (!isDeviceSecure) {
                item {
                    Text(
                        text = "Your device does not have a PIN, Pattern, or Password set. Please set a lock in your device settings to enable App Security.",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            item {
                SwitchPreference(
                    title = "Use Native Security",
                    subtitle = "Require device PIN/Password/Pattern to open app",
                    checked = config.useNativeSecurity,
                    enabled = isDeviceSecure,
                    onCheckedChange = {
                        val newConfig = config.copy(useNativeSecurity = it)
                        if (!it) {
                            newConfig.useBiometricLock = false
                        }
                        viewModel.saveConfig(newConfig)
                    },
                )
            }
            item {
                SwitchPreference(
                    title = "Biometric App Lock",
                    subtitle = "Require fingerprint or face scan on launch",
                    checked = config.useBiometricLock,
                    enabled = config.useNativeSecurity && isDeviceSecure,
                    onCheckedChange = { viewModel.saveConfig(config.copy(useBiometricLock = it)) },
                )
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            /* ==========================================================
             * CATEGORY: PATHS & MEDIA
             * ========================================================== */
            item { PreferenceCategory("Paths & Media") }
            item {
                TextPreference(
                    title = "Auto-Configure Server Paths",
                    subtitle = "Tap to auto-detect base and gallery folders from server",
                    value = "",
                ) {
                    viewModel.showToast("Requesting paths from server...")
                    viewModel.fetchAutoConfig()
                }
            }
            item {
                TextPreference(
                    title = "Server Base Path",
                    value = config.serverBasePath.ifEmpty { "Not set" },
                ) { showBasePathDialog = true }
            }
            item {
                TextPreference(title = "Gallery Server Path", value = config.galleryPath.ifEmpty { "Not set" }) { showPathDialog = true }
            }
            item {
                SwitchPreference(
                    title = "Swipe to Browse Images",
                    subtitle = "Use horizontal swiping in fullscreen preview",
                    checked = config.swipeToBrowseGallery,
                    onCheckedChange = { viewModel.saveConfig(config.copy(swipeToBrowseGallery = it)) },
                )
            }
            item {
                SwitchPreference(
                    title = "Show Grid After Batch",
                    subtitle = "Temporarily show a grid of images when a batch generation finishes",
                    checked = config.showGridAfterGeneration,
                    onCheckedChange = { viewModel.saveConfig(config.copy(showGridAfterGeneration = it)) },
                )
            }

            item {
                TextPreference(
                    title = "Index Gallery Now",
                    subtitle = "Recursively fetch metadata and index all images in the database",
                    value = "",
                ) {
                    viewModel.showToast("Indexing Gallery...")
                    viewModel.triggerManualGallerySync()
                }
            }
            item {
                var showGallerySyncModeDialog by remember { mutableStateOf(false) }

                TextPreference(
                    title = "Gallery Sync Mode",
                    subtitle = "When to index gallery metadata: ${if (config.gallerySyncMode == GallerySyncMode.ON_ENTRY) "On Entry" else "Manual"}",
                    value = "",
                ) {
                    showGallerySyncModeDialog = true
                }

                if (showGallerySyncModeDialog) {
                    AlertDialog(
                        onDismissRequest = { showGallerySyncModeDialog = false },
                        title = { Text("Gallery Sync Mode") },
                        text = {
                            Column {
                                listOf(GallerySyncMode.MANUAL, GallerySyncMode.ON_ENTRY).forEach { mode ->
                                    val label = if (mode == GallerySyncMode.ON_ENTRY) "On Entry" else "Manual"
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    viewModel.saveConfig(config.copy(gallerySyncMode = mode))
                                                    showGallerySyncModeDialog = false
                                                }.padding(vertical = 8.dp),
                                    ) {
                                        RadioButton(selected = config.gallerySyncMode == mode, onClick = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(label)
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showGallerySyncModeDialog = false }) { Text("Close") }
                        },
                    )
                }
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            /* ==========================================================
             * CATEGORY: APPEARANCE & UI
             * ========================================================== */
            item { PreferenceCategory("Appearance & UI") }
            item {
                SwitchPreference(
                    title = "Enable Dark Mode",
                    checked = config.isDarkMode,
                    onCheckedChange = { viewModel.saveConfig(config.copy(isDarkMode = it)) },
                )
            }
            item {
                SwitchPreference(
                    title = "Expand Bottom Drawer by Default",
                    subtitle = "Keep generation controls visible when the app starts",
                    checked = config.bottomSheetExpandedByDefault,
                    onCheckedChange = { viewModel.saveConfig(config.copy(bottomSheetExpandedByDefault = it)) },
                )
            }
            item {
                val currentModeDisplay =
                    when (config.previewMode) {
                        "None" -> "Loading circle"
                        "Finished" -> "Only last finished batch"
                        "Normal" -> "Normal preview"
                        else -> "Normal preview"
                    }
                TextPreference(
                    title = "Preview Mode",
                    value = currentModeDisplay,
                    subtitle = "Choose how images are displayed during generation",
                ) { showPreviewModeDialog = true }
            }
            item {
                SwitchPreference(
                    title = "Show Active Tags UI",
                    subtitle = "Show a quick edit button to visually reorder tags in prompt",
                    checked = config.showActiveTagsUI,
                    onCheckedChange = { viewModel.saveConfig(config.copy(showActiveTagsUI = it)) },
                )
            }
            item {
                SwitchPreference(
                    title = "Keep Screen On",
                    subtitle = "Prevents phone sleep while rendering",
                    checked = config.keepScreenOn,
                    onCheckedChange = { viewModel.saveConfig(config.copy(keepScreenOn = it)) },
                )
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            /* ==========================================================
             * CATEGORY: PUSH NOTIFICATIONS
             * ========================================================== */
            item { PreferenceCategory("Push Notifications") }
            item {
                SwitchPreference(
                    title = "Show Foreground Service Notification",
                    subtitle = "Required for keeping generation alive in the background",
                    checked = config.receiveGenerationNotification,
                    onCheckedChange = { viewModel.saveConfig(config.copy(receiveGenerationNotification = it)) },
                )
            }
            item {
                SwitchPreference(
                    title = "Notify on Batch Finish",
                    subtitle = "Get alerted when a generation batch is fully completed",
                    checked = config.notifOnBatchFinish,
                    onCheckedChange = { viewModel.saveConfig(config.copy(notifOnBatchFinish = it)) },
                )
            }
            item {
                SwitchPreference(
                    title = "Notify on Queue Finish",
                    subtitle = "Get alerted when all queued jobs are finished",
                    checked = config.notifOnQueueFinish,
                    onCheckedChange = { viewModel.saveConfig(config.copy(notifOnQueueFinish = it)) },
                )
            }
            item {
                SwitchPreference(
                    title = "Notify during Civitai Sync",
                    subtitle = "Show progress notification while synchronizing models",
                    checked = config.notifCivitaiSync,
                    onCheckedChange = { viewModel.saveConfig(config.copy(notifCivitaiSync = it)) },
                )
            }
            item {
                SwitchPreference(
                    title = "Auto-Dismiss Sync Notification",
                    subtitle = "Automatically dismiss the notification after a successful sync",
                    checked = config.autoDismissCivitaiNotif,
                    enabled = config.notifCivitaiSync,
                    onCheckedChange = { viewModel.saveConfig(config.copy(autoDismissCivitaiNotif = it)) },
                )
            }
            item {
                val modeDesc =
                    when (config.notificationMode) {
                        "Disabled" -> "No background progress notifications"
                        "Simple" -> "Shows overall batch progress & ETA"
                        "Verbose" -> "Detailed progress for each step"
                        else -> "Shows overall batch progress & ETA"
                    }
                TextPreference(
                    title = "Progress Notification Mode",
                    value = "",
                    subtitle = "Current: ${config.notificationMode} - $modeDesc",
                ) {
                    showNotificationModeDialog = true
                }
            }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            /* ==========================================================
             * CATEGORY: BACKGROUND SERVICE & ADVANCED
             * ========================================================== */
            item { PreferenceCategory("Background Service & Advanced") }
            item {
                SwitchPreference(
                    title = "Run in Background",
                    subtitle = "Keep the app alive with a persistent notification",
                    checked = config.enablePersistentService,
                    onCheckedChange = { viewModel.saveConfig(config.copy(enablePersistentService = it)) },
                )
            }
            item {
                SwitchPreference(
                    title = "Overnight Batch Mode",
                    subtitle = "Ignores minor errors to keep batch running",
                    checked = config.overnightMode,
                    onCheckedChange = { viewModel.saveConfig(config.copy(overnightMode = it)) },
                )
            }

            if (!isIgnoringBattery) {
                item {
                    TextPreference(
                        title = "Remove Battery Restrictions",
                        subtitle = "Allow background tasks to run uninhibited (Recommended for Overnight Mode)",
                        value = "",
                    ) {
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        context.startActivity(intent)
                    }
                }
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            /* ==========================================================
             * CATEGORY: APP UPDATES
             * ========================================================== */
            item { PreferenceCategory("App Updates") }
            item {
                TextPreference(
                    title = "Check for Updates",
                    subtitle = "Look for new versions on the server",
                    value = "",
                ) {
                    viewModel.checkForUpdates(manual = true)
                }
            }

            if (updateManifest != null && updateManifest!!.versionCode != dismissedUpdateVersion && !isUpdateDownloading) {
                val manifest = updateManifest!!
                item {
                    Card(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Update Available: ${manifest.versionName}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Spacer(Modifier.height(4.dp))
                            val lang = androidx.compose.ui.text.intl.Locale.current.language
                            val changelogText = manifest.changelog?.get(lang) ?: manifest.changelog?.get("en") ?: emptyList()
                            if (changelogText.isNotEmpty()) {
                                Text("What's new:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                changelogText.take(3).forEach { Text("• $it", fontSize = 12.sp) }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                if (!manifest.isCritical) {
                                    TextButton(onClick = { dismissedUpdateVersion = manifest.versionCode }) {
                                        Text("Dismiss")
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                Button(onClick = { viewModel.updateManager.downloadUpdate() }) {
                                    Text("Install Update")
                                }
                            }
                        }
                    }
                }
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            /* ==========================================================
             * CATEGORY: DANGER ZONE
             * ========================================================== */
            item { PreferenceCategory("Danger Zone") }
            item {
                TextPreference(
                    title = "Wipe Application Data",
                    subtitle = "Selectively clear application data",
                    value = "",
                ) {
                    showWipeDataDialog = true
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }

        /* ==========================================================
         * DIALOG BUILDERS
         * ========================================================== */

        if (showWipeDataDialog) {
            var wipeSettings by remember { mutableStateOf(false) }
            var wipePresets by remember { mutableStateOf(false) }
            var wipeProfiles by remember { mutableStateOf(false) }
            var wipeHistory by remember { mutableStateOf(false) }
            var wipeWildcards by remember { mutableStateOf(false) }

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
                            Text("App Settings & State (1 item)", fontSize = 14.sp)
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
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (wipeSettings) viewModel.wipeSettings()
                        if (wipePresets) viewModel.wipePresets()
                        if (wipeProfiles) viewModel.wipeServerProfiles()
                        if (wipeHistory) viewModel.wipePromptHistory()
                        if (wipeWildcards) viewModel.wipeWildcards()
                        if (!wipeSettings && !wipePresets && !wipeProfiles && !wipeHistory && !wipeWildcards) {
                            viewModel.wipeAllData()
                        }
                        viewModel.showToast("Selected data wiped")
                        showWipeDataDialog = false
                    }) {
                        Text("Wipe Selected", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showWipeDataDialog = false }) {
                        Text("Cancel")
                    }
                },
            )
        }

        if (isUpdateDownloading) {
            AlertDialog(
                onDismissRequest = { },
                properties =
                    androidx.compose.ui.window
                        .DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
                title = { Text("Downloading Update") },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        LinearProgressIndicator(
                            progress = { updateDownloadProgress },
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        )
                        val mbDownloaded = String.format(Locale.US, "%.2f", updateDownloadStats.first / (1024f * 1024f))
                        val mbTotal = String.format(Locale.US, "%.2f", updateDownloadStats.second / (1024f * 1024f))
                        Text("${(updateDownloadProgress * 100).toInt()}% ($mbDownloaded MB / $mbTotal MB)")
                    }
                },
                confirmButton = { },
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
                                Triple("Simple", "Simple", "Shows overall batch progress & ETA"),
                                Triple("Verbose", "Verbose", "Detailed progress for each step"),
                                Triple("Disabled", "Disabled", "No background progress notifications"),
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

        if (showPreviewModeDialog) {
            AlertDialog(
                onDismissRequest = { showPreviewModeDialog = false },
                title = { Text("Preview Mode") },
                text = {
                    Column {
                        val modes = listOf("Loading circle", "Only last finished batch", "Normal preview")
                        val internalModes = listOf("None", "Finished", "Normal")
                        modes.forEachIndexed { index, display ->
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            viewModel.saveConfig(config.copy(previewMode = internalModes[index]))
                                            showPreviewModeDialog = false
                                        }.padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = config.previewMode == internalModes[index], onClick = null)
                                Spacer(Modifier.width(16.dp))
                                Text(display, fontSize = 16.sp)
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showPreviewModeDialog = false }) { Text("Close") } },
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

        if (showBasePathDialog) {
            var tempPath by remember { mutableStateOf(config.serverBasePath) }
            AlertDialog(
                onDismissRequest = { showBasePathDialog = false },
                title = { Text("Server Base Path") },
                text = { OutlinedTextField(value = tempPath, onValueChange = { tempPath = it }, modifier = Modifier.fillMaxWidth()) },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.saveConfig(config.copy(serverBasePath = tempPath))
                        showBasePathDialog = false
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { showBasePathDialog = false }) { Text("Cancel") } },
            )
        }

        if (showPathDialog) {
            var tempPath by remember { mutableStateOf(config.galleryPath) }
            AlertDialog(
                onDismissRequest = { showPathDialog = false },
                title = { Text("Gallery Server Path") },
                text = { OutlinedTextField(value = tempPath, onValueChange = { tempPath = it }, modifier = Modifier.fillMaxWidth()) },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.saveConfig(config.copy(galleryPath = tempPath))
                        showPathDialog = false
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { showPathDialog = false }) { Text("Cancel") } },
            )
        }

        if (showTimeoutDialog) {
            var tempTimeout by remember { mutableStateOf(config.connectionTimeout.toString()) }
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
                        viewModel.saveConfig(config.copy(connectionTimeout = t))
                        showTimeoutDialog = false
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { showTimeoutDialog = false }) { Text("Cancel") } },
            )
        }

        if (showCheckpointTimeoutDialog) {
            var tempTimeout by remember { mutableStateOf(config.checkpointTimeout.toString()) }
            AlertDialog(
                onDismissRequest = { showCheckpointTimeoutDialog = false },
                title = { Text("Timeout for switching large models") },
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
                        val t = tempTimeout.toIntOrNull() ?: 45
                        viewModel.saveConfig(config.copy(checkpointTimeout = t))
                        showCheckpointTimeoutDialog = false
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { showCheckpointTimeoutDialog = false }) { Text("Cancel") } },
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
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MetadataAlertDialog(
    metadata: String?,
    fileInfo: String? = null,
    onDismiss: () -> Unit,
    onApplyPrompt: (String, String) -> Unit,
    onApplyModel: (String) -> Unit,
    onApplyLoras: (List<String>) -> Unit,
    onApplyAll: (() -> Unit)? = null,
) {
    var posPrompt = ""
    var negPrompt = ""
    var modelName = ""
    val loras = mutableListOf<String>()

    if (metadata != null &&
        !metadata.startsWith("Loading") &&
        !metadata.startsWith("Failed") &&
        !metadata.startsWith("Invalid") &&
        !metadata.startsWith("Server")
    ) {
        val lines = metadata.split("\n")
        var currentMode = 0
        for (line in lines) {
            if (line.startsWith("Negative prompt:")) {
                currentMode = 1
                negPrompt += line.substringAfter("Negative prompt:").trim() + "\n"
            } else if (line.startsWith("Steps:")) {
                currentMode = 2
                val params = line.split(",")
                params.forEach { p ->
                    val kv = p.split(":")
                    if (kv.size >= 2 && kv[0].trim() == "Model") {
                        modelName = kv[1].trim()
                    }
                }
            } else {
                if (currentMode == 0) {
                    posPrompt += line + "\n"
                } else if (currentMode == 1) {
                    negPrompt += line + "\n"
                }
            }
        }
        posPrompt = posPrompt.trim()
        negPrompt = negPrompt.trim()

        PromptParser.LORA.findAll(posPrompt).forEach { match ->
            loras.add(match.value)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Generation Data",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier =
                        Modifier.padding(
                            bottom =
                                if (fileInfo !=
                                    null
                                ) {
                                    4.dp
                                } else {
                                    8.dp
                                },
                        ),
                )

                if (fileInfo != null) {
                    Text(fileInfo, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp))
                }

                Box(
                    modifier =
                        Modifier
                            .weight(
                                1f,
                                fill = false,
                            ).background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                            .padding(8.dp),
                ) {
                    Text(
                        text = metadata ?: "Loading...",
                        fontSize = 11.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                }

                if (metadata != null && posPrompt.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Apply to current session:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))

                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (onApplyAll != null) {
                            Button(
                                onClick = onApplyAll,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp),
                            ) {
                                Text("Apply All", fontSize = 12.sp)
                            }
                        }

                        OutlinedButton(onClick = {
                            onApplyPrompt(posPrompt, negPrompt)
                        }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), modifier = Modifier.height(32.dp)) {
                            Text("Prompt", fontSize = 12.sp)
                        }

                        if (modelName.isNotEmpty()) {
                            OutlinedButton(onClick = {
                                onApplyModel(modelName)
                            }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), modifier = Modifier.height(32.dp)) {
                                Text("Model", fontSize = 12.sp)
                            }
                        }

                        if (loras.isNotEmpty()) {
                            OutlinedButton(onClick = {
                                onApplyLoras(loras)
                            }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), modifier = Modifier.height(32.dp)) {
                                Text("LoRAs (${loras.size})", fontSize = 12.sp)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Close")
                }
            }
        }
    }
}
