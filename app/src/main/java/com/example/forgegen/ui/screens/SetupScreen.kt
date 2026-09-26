package com.example.forgegen

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
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
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick)
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
    onDismiss: () -> Unit,
) {
    // --- STATE OBSERVATION ---
    val config by viewModel.config.collectAsStateWithLifecycle()
    val appState by viewModel.appState.collectAsStateWithLifecycle()
    val updateManifest by viewModel.updateManifest.collectAsStateWithLifecycle()
    val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()
    val isUpdateDownloading by viewModel.isUpdateDownloading.collectAsStateWithLifecycle()

    // --- DIALOG VISIBILITY STATES ---
    var showUrlDialog by remember { mutableStateOf(false) }

    var showTimeoutDialog by remember { mutableStateOf(false) }

    var showProfilesDialog by remember { mutableStateOf(false) }

    var showNotificationModeDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showContentModeDialog by remember { mutableStateOf(false) }
    // A more permissive content mode waits here for the adult confirmation.
    var pendingContentMode by remember { mutableStateOf<String?>(null) }
    var dismissedUpdateVersion by remember { mutableIntStateOf(-1) }

    var isTestingConnection by remember { mutableStateOf(false) }
    var testStatus by remember { mutableStateOf<String?>(null) }
    var testResults by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var showWipeDataDialog by remember { mutableStateOf(false) }

    // Debug mode (DebugMode): 8 quick taps on "App Version", then the password.
    val debugUnlocked by viewModel.debugUnlocked.collectAsStateWithLifecycle()
    var versionTaps by remember { mutableIntStateOf(0) }
    var lastVersionTap by remember { mutableLongStateOf(0L) }
    var showDebugPasswordDialog by remember { mutableStateOf(false) }

    // --- SYSTEM SERVICES ---
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val keyguardManager = remember { context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager }
    val isDeviceSecure = remember { keyguardManager.isDeviceSecure }

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

    // Samsung Now Bar: offered on One UI 8+ only; the system can also turn live notifications off for the app.
    val isNowBarSupported = remember { NowBar.isSupported(context) }
    var isNowBarAllowed by remember { mutableStateOf(NowBar.isAllowedBySystem(context)) }

    // --- LIFECYCLE OBSERVER FOR BATTERY OPTIMIZATION AND LIVE NOTIFICATION REFRESH ---
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    isIgnoringBattery = pm.isIgnoringBatteryOptimizations(context.packageName)
                    isNowBarAllowed = NowBar.isAllowedBySystem(context)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // System back closes the screen both as a nav destination and as the overlay on MainScreen.
    BackHandler { onDismiss() }

    // --- UI STRUCTURE ---
    Scaffold() { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            /* ==========================================================
             * CATEGORY: SERVER CONNECTION
             * ========================================================== */            /* ==========================================================
             * TOP ACTION CHIPS
             * ========================================================== */
            item {
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 0.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.foundation.layout.FlowRow(
                        modifier = Modifier.weight(1f).padding(top = 8.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        ElevatedFilterChip(
                            selected = false,
                            onClick = { showUrlDialog = true },
                            label = { Text("API: ${config.apiUrl}") }
                        )
                        ElevatedFilterChip(
                            selected = false,
                            onClick = { showProfilesDialog = true },
                            label = { Text("Profiles: ${config.serverProfiles.size}") }
                        )
                        ElevatedFilterChip(
                            selected = false,
                            onClick = { showTimeoutDialog = true },
                            label = { Text("Timeout: ${config.timeout}s") }
                        )

                        ElevatedFilterChip(
                            selected = false,
                            onClick = {
                                isTestingConnection = true
                                testStatus = "Running diagnostics..."
                                testResults = emptyList()

                                scope.launch(Dispatchers.IO) {
                                    val testClientBuilder =
                                        okhttp3.OkHttpClient
                                            .Builder()
                                            .connectTimeout(1, java.util.concurrent.TimeUnit.SECONDS)
                                            .readTimeout(1, java.util.concurrent.TimeUnit.SECONDS)
                                    testClientBuilder.addInterceptor { chain ->
                                        val reqBuilder = chain.request().newBuilder()
                                        reqBuilder.header("Cookie", "IIB_S=bf63789069ec13d6b7b95a5176468e99f8940fe6aa65931edc17e1abf5c5e172")
                                        chain.proceed(reqBuilder.build())
                                    }
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
                            },
                            label = { Text("Diagnostics") }
                        )
                    }
                }
            }



            item { ExpandableCategoryHeader("Metadata & Civitai", appState, viewModel) }
            if ("Metadata & Civitai" in appState.setupExpandedSections) {

            item {
                // Only while connected to the Forge server: the models to look up come from it.
                TextPreference(
                    title = "Sync Models Now",
                    subtitle =
                        if (isConnected) {
                            "Fetch missing thumbnails and trigger words from Civitai API"
                        } else {
                            "Needs a connection to the Forge server"
                        },
                    value = "",
                    enabled = isConnected,
                ) {
                    viewModel.syncCivitaiModelsManual()
                }
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            /* ==========================================================
             * CATEGORY: CONTENT & PRIVACY
             * ========================================================== */            }

            item { ExpandableCategoryHeader("Content & Privacy", appState, viewModel) }
            if ("Content & Privacy" in appState.setupExpandedSections) {

            item {
                TextPreference(
                    title = "Content Mode",
                    value = "",
                    subtitle = "Current: ${config.contentMode} - ${contentModeDescription(config.contentMode)}",
                ) {
                    showContentModeDialog = true
                }
            }
            item {
                SwitchPreference(
                    title = "Hide Prompts in Notifications",
                    subtitle = "Finished-batch notifications do not show the prompt (never on the lock screen anyway)",
                    checked = config.hidePromptsInNotifications,
                    onCheckedChange = { viewModel.saveConfig(config.copy(hidePromptsInNotifications = it)) },
                )
            }
            item {
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
            item {
                SwitchPreference(
                    title = "Block Screenshots",
                    subtitle = "No screenshots or screen recordings of the app",
                    checked = config.blockScreenshots,
                    onCheckedChange = { viewModel.saveConfig(config.copy(blockScreenshots = it)) },
                )
            }
            item {
                SwitchPreference(
                    title = "Save to Phone Privately",
                    subtitle = "Saved images go to the app's own folder instead of the phone's gallery, so gallery apps " +
                        "and their cloud backup do not see them. They are deleted when the app is uninstalled.",
                    checked = config.savePrivately,
                    onCheckedChange = { viewModel.saveConfig(config.copy(savePrivately = it)) },
                )
            }
            item {
                SwitchPreference(
                    title = "Share Without Generation Data",
                    subtitle =
                        if (DeviceImages.sharingAllowed(config.contentMode)) {
                            "Shared images leave without their prompt, seed and model"
                        } else {
                            DeviceImages.SHARING_OFF
                        },
                    checked = config.shareWithoutMetadata,
                    enabled = DeviceImages.sharingAllowed(config.contentMode),
                    onCheckedChange = { viewModel.saveConfig(config.copy(shareWithoutMetadata = it)) },
                )
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            /* ==========================================================
             * CATEGORY: SECURITY
             * ========================================================== */            }

            item { ExpandableCategoryHeader("Security", appState, viewModel) }
            if ("Security" in appState.setupExpandedSections) {

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
            item {
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

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }


            /* ==========================================================
             * CATEGORY: APPEARANCE & UI
             * ========================================================== */            }

            item { ExpandableCategoryHeader("Appearance & UI", appState, viewModel) }
            if ("Appearance & UI" in appState.setupExpandedSections) {

            item {
                TextPreference(
                    title = "Theme",
                    value = "",
                    subtitle = if (config.themeMode == THEME_SYSTEM) "System default" else config.themeMode,
                ) {
                    showThemeDialog = true
                }
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
                SwitchPreference(
                    title = "Show Active Tags UI",
                    subtitle = "Show the \"Edit Tags\" row under the prompts to switch tags off and reorder them",
                    checked = config.showActiveTagsUI,
                    onCheckedChange = { viewModel.saveConfig(config.copy(showActiveTagsUI = it)) },
                )
            }
            item {
                SwitchPreference(
                    title = "Show Grid After Batch",
                    subtitle = "Show all images of a batch as a grid when it finishes, until you open one or the next job starts",
                    checked = config.showGridAfterGeneration,
                    onCheckedChange = { viewModel.saveConfig(config.copy(showGridAfterGeneration = it)) },
                )
            }
            item {
                SwitchPreference(
                    title = "Keep Screen On",
                    subtitle = "Keep the screen on while images are being generated and the app is open",
                    checked = config.keepScreenOn,
                    onCheckedChange = { viewModel.saveConfig(config.copy(keepScreenOn = it)) },
                )
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            /* ==========================================================
             * CATEGORY: PUSH NOTIFICATIONS
             * ========================================================== */            }

            item { ExpandableCategoryHeader("Push Notifications", appState, viewModel) }
            if ("Push Notifications" in appState.setupExpandedSections) {

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
                        "Disabled" -> "Only a static notice (Android requires one)"
                        "Simple" -> "Image number, progress and ETA"
                        "Verbose" -> "Also batch size and an Open App button"
                        else -> "Image number, progress and ETA"
                    }
                TextPreference(
                    title = "Progress Notification Mode",
                    value = "",
                    subtitle = "Current: ${config.notificationMode} - $modeDesc",
                ) {
                    showNotificationModeDialog = true
                }
            }
            item {
                // Off until the user turns it on; greyed out on phones without Samsung's One UI 8 or newer.
                SwitchPreference(
                    title = "Show Progress in Now Bar",
                    subtitle =
                        when {
                            !isNowBarSupported -> "Samsung phones with One UI 8 or newer only"
                            config.notificationMode == "Disabled" -> "Needs a Progress Notification Mode other than Disabled"
                            else -> "Show the generation progress in the pill at the bottom of the lock screen (Samsung Now Bar)"
                        },
                    checked = isNowBarSupported && config.nowBarProgress,
                    enabled = isNowBarSupported,
                    onCheckedChange = { viewModel.saveConfig(config.copy(nowBarProgress = it)) },
                )
            }
            if (isNowBarSupported && config.nowBarProgress && !isNowBarAllowed) {
                item {
                    TextPreference(
                        title = "Live Notifications Are Off",
                        subtitle = "The system settings do not let ForgeGen show live notifications. Tap to open them.",
                        value = "",
                    ) {
                        NowBar.openSystemSettings(context)
                    }
                }
            }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            /* ==========================================================
             * CATEGORY: BACKGROUND & OVERNIGHT
             * ========================================================== */            }

            item { ExpandableCategoryHeader("Background & Overnight", appState, viewModel) }
            if ("Background & Overnight" in appState.setupExpandedSections) {

            item {
                SwitchPreference(
                    title = "Overnight Batch Mode",
                    subtitle = "A failed job is set aside and the queue goes on; a lost connection is retried until " +
                        "the server is back. A summary at the end tells what failed.",
                    checked = config.overnightMode,
                    onCheckedChange = { viewModel.saveConfig(config.copy(overnightMode = it)) },
                )
            }

            if (!isIgnoringBattery) {
                item {
                    TextPreference(
                        title = "Remove Battery Restrictions",
                        subtitle = "Let the queue run with the screen off (recommended for Overnight Batch Mode)",
                        value = "",
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

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            /* ==========================================================
             * CATEGORY: PERMISSIONS
             * ========================================================== */            }

            item { ExpandableCategoryHeader("Permissions", appState, viewModel) }
            if ("Permissions" in appState.setupExpandedSections) {

            item {
                SwitchPreference(
                    title = "Save Logs on Out of Memory",
                    subtitle = "When the app or the server runs out of memory, save a report with the app's log to " +
                        "Downloads (ForgeGen-OOM-date.txt). Android needs no storage permission for it.",
                    checked = config.saveOomLogs,
                    onCheckedChange = { viewModel.saveConfig(config.copy(saveOomLogs = it)) },
                )
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            /* ==========================================================
             * CATEGORY: APP UPDATES
             * ========================================================== */            }

            item { ExpandableCategoryHeader("App Updates", appState, viewModel) }
            if ("App Updates" in appState.setupExpandedSections) {

            item {
                TextPreference(
                    title = "App Version",
                    subtitle = "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                    value = "",
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
            item {
                TextPreference(
                    title = "Check for Updates",
                    subtitle = "Look for a newer release on GitHub",
                    value = "",
                ) {
                    dismissedUpdateVersion = -1
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
                            val changelogText = manifest.changelog ?: emptyList()
                            if (changelogText.isNotEmpty()) {
                                Text("What's new:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                changelogText.take(3).forEach { Text("• $it", fontSize = 12.sp) }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { dismissedUpdateVersion = manifest.versionCode }) {
                                    Text("Dismiss")
                                }
                                Spacer(Modifier.width(8.dp))
                                Button(onClick = { viewModel.downloadUpdate() }) {
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
             * ========================================================== */            }

            // The Debug section, only after unlocking the debug mode; placed above the Danger Zone.
            if (debugUnlocked) {
                item { ExpandableCategoryHeader("Debug", appState, viewModel) }
                if ("Debug" in appState.setupExpandedSections) {
                    item { DebugPanel(viewModel) }
                }
                item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            }

            item { ExpandableCategoryHeader("Danger Zone", appState, viewModel) }
            if ("Danger Zone" in appState.setupExpandedSections) {

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
                                        viewModel.showToast("Debug mode on: see the Debug section")
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
                                    (if (config.contentMode == CONTENT_UNRESTRICTED) ", turns Unrestricted off" else "") +
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

        if (showContentModeDialog) {
            AlertDialog(
                onDismissRequest = { showContentModeDialog = false },
                title = { Text("Content Mode") },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        // Unrestricted is one-way (ForgeSettingsManager.saveConfig): the other modes are shown, not offered.
                        val locked = config.contentMode == CONTENT_UNRESTRICTED
                        if (locked) {
                            Text(
                                "Unrestricted is on and cannot be turned off here. Only wiping \"App Settings & State\" " +
                                    "in the Danger Zone brings back SFW.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        listOf(CONTENT_SFW, CONTENT_NSFW, CONTENT_UNRESTRICTED).forEach { mode ->
                            val selectable = !locked || mode == CONTENT_UNRESTRICTED
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = selectable) {
                                            showContentModeDialog = false
                                            if (contentModeRank(mode) > contentModeRank(config.contentMode)) {
                                                pendingContentMode = mode
                                            } else {
                                                viewModel.saveConfig(config.copy(contentMode = mode))
                                            }
                                        }.padding(vertical = 10.dp)
                                        .alpha(if (selectable) 1f else 0.38f),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = config.contentMode == mode, onClick = null, enabled = selectable)
                                Spacer(Modifier.width(16.dp))
                                Column {
                                    Text(mode, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                    Text(contentModeDescription(mode), fontSize = 12.sp, color = Color.Gray)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "In every mode, sexual content with a minor, and nudity or sex with the LoRA of a real person, " +
                                "are never sent. The mode is a safeguard in this app: the server itself accepts anything.",
                            fontSize = 11.sp,
                            color = Color.Gray,
                        )
                    }
                },
                confirmButton = { TextButton(onClick = { showContentModeDialog = false }) { Text("Close") } },
            )
        }

        pendingContentMode?.let { mode ->
            if (mode == CONTENT_UNRESTRICTED) {
                // One-way and the user's sole responsibility: the confirmation needs the box ticked.
                var accepted by remember { mutableStateOf(false) }
                AlertDialog(
                    onDismissRequest = { pendingContentMode = null },
                    title = { Text("Turn On Unrestricted Mode?") },
                    text = {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            Text(
                                "Unrestricted sends and shows everything: nothing is blurred and no tag is hidden. Only the two " +
                                    "blocks kept in every mode stay: sexual content with a minor, and nudity or sex with the LoRA " +
                                    "of a real person.",
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "You alone are responsible for what you generate, keep and pass on, and for following the law " +
                                    "where you live. Creating or possessing some content, AI-generated content too, can be a " +
                                    "criminal offence, punished with fines or prison.",
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "This cannot be undone in the settings: only wiping \"App Settings & State\" in the Danger Zone " +
                                    "turns it off. Sharing images from the app is turned off in this mode.",
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { accepted = !accepted },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = accepted, onCheckedChange = { accepted = it })
                                Text("I am 18 or older and I accept sole responsibility.", fontSize = 14.sp)
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            enabled = accepted,
                            onClick = {
                                viewModel.saveConfig(config.copy(contentMode = mode))
                                pendingContentMode = null
                            },
                        ) {
                            Text(
                                "Turn On Unrestricted",
                                color =
                                    if (accepted) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                    },
                            )
                        }
                    },
                    dismissButton = { TextButton(onClick = { pendingContentMode = null }) { Text("Cancel") } },
                )
            } else {
                AlertDialog(
                    onDismissRequest = { pendingContentMode = null },
                    title = { Text("Allow Adult Content?") },
                    text = {
                        Text(
                            "The $mode mode lets the app send and show sexual content. Turn it on only if you are 18 or older " +
                                "and such content is legal where you are.",
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            viewModel.saveConfig(config.copy(contentMode = mode))
                            pendingContentMode = null
                        }) { Text("I Am 18 or Older") }
                    },
                    dismissButton = { TextButton(onClick = { pendingContentMode = null }) { Text("Cancel") } },
                )
            }
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
}



@Composable
fun ExpandableCategoryHeader(title: String, appState: AppState, viewModel: ForgeViewModel) {
    val isExpanded = title in appState.setupExpandedSections
    Row(
        modifier = Modifier.fillMaxWidth().clickable {
            viewModel.updateState { state ->
                state.copy(
                    setupExpandedSections = if (isExpanded) state.setupExpandedSections - title else state.setupExpandedSections + title
                )
            }
        }.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            title,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Icon(
            if (isExpanded) androidx.compose.material.icons.Icons.Default.KeyboardArrowUp else androidx.compose.material.icons.Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

/** What a content mode does, for the settings. */
private fun contentModeDescription(mode: String): String =
    when (mode) {
        CONTENT_NSFW ->
            "Adult content is allowed. Extreme tags (non-consent, gore, bestiality and the like) are not sent, and images " +
                "made with them are blurred. Civitai previews up to X."
        CONTENT_UNRESTRICTED ->
            "Nothing is blurred or hidden; only the two blocks kept in every mode apply. Civitai previews: all except " +
                "those Civitai itself blocks. It cannot be turned off, and images cannot be shared."
        else ->
            "Tags with nudity, sex or other adult content are not sent and are hidden, and every image is blurred until " +
                "you tap it. Civitai previews: safe ones only."
    }

/** How permissive a mode is; moving to a higher one asks for the adult confirmation. */
private fun contentModeRank(mode: String) =
    when (mode) {
        CONTENT_UNRESTRICTED -> 2
        CONTENT_NSFW -> 1
        else -> 0
    }
