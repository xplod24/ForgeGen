package com.example.forgegen

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
    onDismiss: () -> Unit,
) {
    // --- STATE OBSERVATION ---
    val config by viewModel.config.collectAsStateWithLifecycle()
    val appState by viewModel.appState.collectAsStateWithLifecycle()
    val updateManifest by viewModel.updateManifest.collectAsStateWithLifecycle()
    val isUpdateDownloading by viewModel.isUpdateDownloading.collectAsStateWithLifecycle()

    // --- DIALOG VISIBILITY STATES ---
    var showUrlDialog by remember { mutableStateOf(false) }

    var showTimeoutDialog by remember { mutableStateOf(false) }

    var showProfilesDialog by remember { mutableStateOf(false) }

    var showNotificationModeDialog by remember { mutableStateOf(false) }
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
             * CATEGORY: APPEARANCE & UI
             * ========================================================== */            }

            item { ExpandableCategoryHeader("Appearance & UI", appState, viewModel) }
            if ("Appearance & UI" in appState.setupExpandedSections) {

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
             * ========================================================== */            }

            item { ExpandableCategoryHeader("Background Service & Advanced", appState, viewModel) }
            if ("Background Service & Advanced" in appState.setupExpandedSections) {

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
             * ========================================================== */            }

            item { ExpandableCategoryHeader("App Updates", appState, viewModel) }
            if ("App Updates" in appState.setupExpandedSections) {

            item {
                TextPreference(
                    title = "Check for Updates",
                    subtitle = "Look for new versions on the server",
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
                                if (!manifest.isCritical) {
                                    TextButton(onClick = { dismissedUpdateVersion = manifest.versionCode }) {
                                        Text("Dismiss")
                                    }
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
                            if (wipeSettings) viewModel.wipeSettings()
                            if (wipePresets) viewModel.wipePresets()
                            if (wipeProfiles) viewModel.wipeServerProfiles()
                            if (wipeHistory) viewModel.wipePromptHistory()
                            if (wipeWildcards) viewModel.wipeWildcards()
                            if (wipeImages) viewModel.wipeGalleryIndex()
                            viewModel.showToast("Selected data wiped")
                            showWipeDataDialog = false
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
