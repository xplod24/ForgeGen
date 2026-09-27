package com.example.forgegen

import android.app.KeyguardManager
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
    APPEARANCE("Appearance", Icons.Default.Palette, Color(0xFFA87BFF)),
    NOTIFICATIONS("Notifications", Icons.Default.Notifications, Color(0xFFFFA726)),
    QUEUE("Queue & Background", Icons.Default.Bedtime, Color(0xFF26C6DA)),
    PRIVACY("Privacy & Security", Icons.Default.Shield, Color(0xFF66BB6A)),
    UPDATES("Updates", Icons.Default.SystemUpdate, Color(0xFF42A5F5)),
    DATA("Backup & Data", Icons.Default.Storage, Color(0xFF9E9E9E)),
    DEBUG("Debug", Icons.Default.BugReport, Color(0xFFEF5350)),
}

/**
 * One setting (or a block of its page, e.g. the Now Bar checklist) in its [page] and [group], with the [words] the
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
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val pingMs by viewModel.pingMs.collectAsStateWithLifecycle()

    // --- DIALOG VISIBILITY STATES ---
    var showUrlDialog by remember { mutableStateOf(false) }

    var showTimeoutDialog by remember { mutableStateOf(false) }

    var showProfilesDialog by remember { mutableStateOf(false) }

    var showNotificationModeDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
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
    var areNotificationsAllowed by remember { mutableStateOf(NowBar.areNotificationsAllowed(context)) }

    // --- LIFECYCLE OBSERVER FOR BATTERY OPTIMIZATION AND LIVE NOTIFICATION REFRESH ---
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    isIgnoringBattery = pm.isIgnoringBatteryOptimizations(context.packageName)
                    isNowBarAllowed = NowBar.isAllowedBySystem(context)
                    areNotificationsAllowed = NowBar.areNotificationsAllowed(context)
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

            // --- APPEARANCE ---
            add(SettingsPage.APPEARANCE, null, "theme dark light system colors") {
                TextPreference(
                    title = "Theme",
                    subtitle = if (config.themeMode == THEME_SYSTEM) "System default" else config.themeMode,
                ) { showThemeDialog = true }
            }
            add(SettingsPage.APPEARANCE, null, "expand bottom drawer by default generation controls sheet start") {
                SwitchPreference(
                    title = "Expand Bottom Drawer by Default",
                    subtitle = "Keep generation controls visible when the app starts",
                    checked = config.bottomSheetExpandedByDefault,
                    onCheckedChange = { viewModel.saveConfig(config.copy(bottomSheetExpandedByDefault = it)) },
                )
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
            add(SettingsPage.NOTIFICATIONS, "Progress", "show progress in now bar samsung lock screen live notification") {
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
            if (isNowBarSupported && config.nowBarProgress) {
                add(SettingsPage.NOTIFICATIONS, "Progress", "now bar checklist live notifications developer options lock screen") {
                    NowBarChecklist(
                        notificationsAllowed = areNotificationsAllowed,
                        liveNotificationsAllowed = isNowBarAllowed,
                        onOpenNotificationSettings = { NowBar.openNotificationSettings(context) },
                        onOpenLiveNotificationSettings = { NowBar.openSystemSettings(context) },
                        onOpenDeveloperOptions = { NowBar.openDeveloperOptions(context) },
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
                // app closed or the screen locked; the notification (and the Now Bar) shows the same.
                add(SettingsPage.UPDATES, null, "update downloading installing progress ${download.versionName}") {
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
                            if (download.installing) "Installing ${download.versionName}" else "Downloading ${download.versionName}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        if (download.installing || download.total <= 0) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(progress = { download.fraction }, modifier = Modifier.fillMaxWidth())
                        }
                        Spacer(Modifier.height(8.dp))
                        val detail =
                            when {
                                download.installing -> "ForgeGen closes to update; a notification says when it is done."
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
            val manifest = updateManifest
            if (download == null && manifest != null && manifest.versionCode != dismissedUpdateVersion) {
                add(SettingsPage.UPDATES, null, "update available install new version ${manifest.versionName}") {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                                .clip(MaterialTheme.shapes.small)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                                .padding(16.dp),
                    ) {
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
                            // The download runs in UpdateDownloadService with its progress here, in the notifications
                            // and the Now Bar; the app stays open (2.3.0-3: 2.3.0-1 sent it to the background, which the
                            // owner did not like).
                            Button(onClick = { viewModel.downloadUpdate() }) {
                                Text("Install Update")
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
            add(SettingsPage.DATA, "Backup", "export settings backup file presets profiles wildcards") {
                TextPreference(
                    title = "Export Settings",
                    subtitle = "Settings, presets, server profiles and wildcards to a file",
                ) {
                    val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                    exportLauncher.launch("forgegen-backup-$date.json")
                }
            }
            add(SettingsPage.DATA, "Backup", "import settings backup file restore") {
                TextPreference(
                    title = "Import Settings",
                    subtitle = "Replaces the settings, presets and server profiles; adds the wildcards",
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
            SettingsPage.NOTIFICATIONS to
                listOfNotNull(
                    "queue finish".takeIf { config.notifOnQueueFinish },
                    "batch finish".takeIf { config.notifOnBatchFinish },
                    "vibration".takeIf { config.vibrateOnFinish },
                    "${config.notificationMode} progress",
                    "Now Bar".takeIf { isNowBarSupported && config.nowBarProgress },
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
                    updateDownload?.let { d ->
                        if (d.installing) "Installing ${d.versionName}" else "Downloading ${d.versionName} · ${(d.fraction * 100).toInt()}%"
                    } ?: ("${BuildConfig.VERSION_NAME} · " + if (config.autoInstallUpdates) "installs automatically" else "notifies only")
                ),
            SettingsPage.DATA to "Export, import, logs, wipe",
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
 * What the Now Bar needs besides the app: what the app can check is marked, the rest (Samsung's) is listed, with
 * buttons to the pages where it is changed.
 */
@Composable
private fun NowBarChecklist(
    notificationsAllowed: Boolean,
    liveNotificationsAllowed: Boolean,
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
            Text("For the Now Bar", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            NowBarCheck(notificationsAllowed, "Notifications allowed")
            NowBarCheck(liveNotificationsAllowed, "Live notifications allowed by the system")
            Spacer(Modifier.height(6.dp))
            Text(
                "Samsung also needs these, which the app cannot check: \"Live notifications for all apps\" turned on in " +
                    "the developer options (otherwise Samsung shows only the apps on its own list), and ForgeGen's " +
                    "notifications shown on the lock screen, with their content.",
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
                TextButton(onClick = onOpenDeveloperOptions) { Text("Developer Options") }
            }
        }
    }
}

@Composable
private fun NowBarCheck(
    ok: Boolean,
    text: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Icon(
            if (ok) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = if (ok) "Done" else "Missing",
            tint = if (ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 13.sp)
    }
}
