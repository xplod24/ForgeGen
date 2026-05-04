package com.example.forgegen

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
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
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

/* ============================================================================
 * SHIMMER EFFECT (SKELETON LOADING & FRAMES)
 * Tworzy animowany gradient naśladujący ładowanie oraz ozdobne ramki
 * ============================================================================ */

@Composable
fun coloredShimmerBrush(baseColor: Color): Brush {
    val shimmerColors = listOf(
        baseColor.copy(alpha = 0.2f),
        baseColor.copy(alpha = 0.8f),
        baseColor.copy(alpha = 0.2f)
    )
    val transition = rememberInfiniteTransition(label = "shimmer_${baseColor.value}")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_translate_${baseColor.value}"
    )
    return Brush.linearGradient(
        colors = shimmerColors,
        start = Offset.Zero,
        end = Offset(x = translateAnim, y = translateAnim)
    )
}

@Composable
fun shimmerBrush(): Brush = coloredShimmerBrush(MaterialTheme.colorScheme.surfaceVariant)

/* ============================================================================
 * REUSABLE UI COMPONENTS FOR SETTINGS
 * ============================================================================ */

@Composable
fun PreferenceCategory(title: String) {
    Text(
        text = title.uppercase(Locale.getDefault()),
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp, end = 16.dp)
    )
}

@Composable
fun SwitchPreference(title: String, subtitle: String? = null, checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).alpha(if (enabled) 1f else 0.5f)) {
            Text(text = title, fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground)
            if (subtitle != null) {
                Text(text = subtitle, fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f), lineHeight = 18.sp)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled
        )
    }
}

@Composable
fun TextPreference(title: String, value: String, subtitle: String? = value, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground)
            if (subtitle != null) {
                Text(text = subtitle, fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    navController: NavHostController
) {
    // --- STATE OBSERVATION ---
    val config by viewModel.config.collectAsStateWithLifecycle()
    val updateManifest by viewModel.updateManifest.collectAsStateWithLifecycle()
    val isUpdateDownloading by viewModel.isUpdateDownloading.collectAsStateWithLifecycle()
    val updateDownloadProgress by viewModel.updateDownloadProgress.collectAsStateWithLifecycle()

    // --- DIALOG VISIBILITY STATES ---
    var showUrlDialog by remember { mutableStateOf(false) }
    var showBasePathDialog by remember { mutableStateOf(false) }
    var showPathDialog by remember { mutableStateOf(false) }
    var showTimeoutDialog by remember { mutableStateOf(false) }
    var showCheckpointTimeoutDialog by remember { mutableStateOf(false) }
    var showProfilesDialog by remember { mutableStateOf(false) }
    var showPreviewModeDialog by remember { mutableStateOf(false) }
    var showNotificationPriorityDialog by remember { mutableStateOf(false) }
    var showNotificationModeDialog by remember { mutableStateOf(false) }
    var showUpdateChannelDialog by remember { mutableStateOf(false) }
    var showBetaTokenDialog by remember { mutableStateOf(false) }
    var dismissedUpdateVersion by remember { mutableIntStateOf(-1) }

    var isTestingConnection by remember { mutableStateOf(false) }
    var testStatus by remember { mutableStateOf<String?>(null) }
    var testResults by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

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
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isIgnoringBattery = pm.isIgnoringBatteryOptimizations(context.packageName)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val onBackClick = remember { {
        if (navController.currentDestination?.route == "setup") {
            navController.popBackStack()
        }
        Unit
    } }

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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
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
                TextPreference(title = "Server Profiles", subtitle = "${config.serverProfiles.size} saved profiles", value = "") { showProfilesDialog = true }
            }
            item {
                TextPreference(title = "Connection Timeout", subtitle = "${config.connectionTimeout} seconds", value = "") { showTimeoutDialog = true }
            }
            item {
                TextPreference(title = "Checkpoint Load Timeout", subtitle = "${config.checkpointTimeout} seconds", value = "") { showCheckpointTimeoutDialog = true }
            }
            item {
                TextPreference(title = "Test Connection", subtitle = "Run API diagnostics", value = "") {
                    isTestingConnection = true
                    testStatus = "Running diagnostics..."
                    testResults = emptyList()

                    scope.launch(Dispatchers.IO) {
                        val testClientBuilder = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS)
                        testClientBuilder.addInterceptor { chain ->
                            val reqBuilder = chain.request().newBuilder()
                            reqBuilder.header("Cookie", "IIB_S=bf63789069ec13d6b7b95a5176468e99f8940fe6aa65931edc17e1abf5c5e172")
                            chain.proceed(reqBuilder.build())
                        }
                        val testClient = testClientBuilder.build()
                        val endpoints = listOf("sd-models", "samplers", "schedulers", "upscalers", "loras", "options")
                        val results = mutableListOf<Pair<String, String>>()
                        var allSuccess = true

                        for (ep in endpoints) {
                            val start = System.currentTimeMillis()
                            try {
                                var cleanUrl = config.apiUrl.trimEnd('/')
                                if (cleanUrl.isNotEmpty() && !cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) cleanUrl = "http://$cleanUrl"

                                val req = Request.Builder().url("$cleanUrl/sdapi/v1/$ep").build()
                                testClient.newCall(req).awaitResponse().use { res ->
                                    val time = System.currentTimeMillis() - start
                                    if (res.isSuccessful) {
                                        results.add(ep to "${time}ms \u2714")
                                    } else if (res.code == 401 || res.code == 403) {
                                        results.add(ep to "Auth Needed \u2718")
                                        allSuccess = false
                                    } else {
                                        results.add(ep to "Err ${res.code} \u2718")
                                        allSuccess = false
                                    }
                                }
                            } catch (_: Exception) {
                                results.add(ep to "Failed \u2718")
                                allSuccess = false
                            }
                            withContext(Dispatchers.Main) { testResults = results.toList() }
                        }

                        withContext(Dispatchers.Main) {
                            testStatus = if (allSuccess) "All Systems Operational!" else "Some APIs Failed."
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
                    value = ""
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
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
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
                    }
                )
            }
            item {
                SwitchPreference(
                    title = "Biometric App Lock",
                    subtitle = "Require fingerprint or face scan on launch",
                    checked = config.useBiometricLock,
                    enabled = config.useNativeSecurity && isDeviceSecure,
                    onCheckedChange = { viewModel.saveConfig(config.copy(useBiometricLock = it)) }
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
                    value = ""
                ) {
                    viewModel.showSnackbar("Requesting paths from server...")
                    viewModel.fetchAutoConfig()
                }
            }
            item {
                TextPreference(title = "Server Base Path", value = config.serverBasePath.ifEmpty { "Not set" }) { showBasePathDialog = true }
            }
            item {
                TextPreference(title = "Gallery Server Path", value = config.galleryPath.ifEmpty { "Not set" }) { showPathDialog = true }
            }
            item {
                SwitchPreference(
                    title = "Swipe to Browse Images",
                    subtitle = "Use horizontal swiping in fullscreen preview",
                    checked = config.swipeToBrowseGallery,
                    onCheckedChange = { viewModel.saveConfig(config.copy(swipeToBrowseGallery = it)) }
                )
            }
            item {
                SwitchPreference(
                    title = "Show Grid After Batch",
                    subtitle = "Temporarily show a grid of images when a batch generation finishes",
                    checked = config.showGridAfterGeneration,
                    onCheckedChange = { viewModel.saveConfig(config.copy(showGridAfterGeneration = it)) }
                )
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
                    onCheckedChange = { viewModel.saveConfig(config.copy(isDarkMode = it)) }
                )
            }
            item {
                SwitchPreference(
                    title = "Expand Bottom Drawer by Default",
                    subtitle = "Keep generation controls visible when the app starts",
                    checked = config.bottomSheetExpandedByDefault,
                    onCheckedChange = { viewModel.saveConfig(config.copy(bottomSheetExpandedByDefault = it)) }
                )
            }
            item {
                val currentModeDisplay = when(config.previewMode) {
                    "None" -> "Loading circle"
                    "Finished" -> "Only last finished batch"
                    "Normal" -> "Normal preview"
                    else -> "Normal preview"
                }
                TextPreference(
                    title = "Preview Mode",
                    value = currentModeDisplay,
                    subtitle = "Choose how images are displayed during generation"
                ) { showPreviewModeDialog = true }
            }
            item {
                SwitchPreference(
                    title = "Show Active Tags UI",
                    subtitle = "Show a quick edit button to visually reorder tags in prompt",
                    checked = config.showActiveTagsUI,
                    onCheckedChange = { viewModel.saveConfig(config.copy(showActiveTagsUI = it)) }
                )
            }
            item {
                SwitchPreference(
                    title = "Keep Screen On",
                    subtitle = "Prevents phone sleep while rendering",
                    checked = config.keepScreenOn,
                    onCheckedChange = { viewModel.saveConfig(config.copy(keepScreenOn = it)) }
                )
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            /* ==========================================================
             * CATEGORY: PUSH NOTIFICATIONS
             * ========================================================== */
            item { PreferenceCategory("Push Notifications") }
            item {
                SwitchPreference(
                    title = "Batch Completion Alert",
                    subtitle = "Get alerted when a generation batch is fully completed",
                    checked = config.receiveGenerationNotification,
                    onCheckedChange = { viewModel.saveConfig(config.copy(receiveGenerationNotification = it)) }
                )
            }
            item {
                SwitchPreference(
                    title = "Image Preview in Notification",
                    subtitle = "Show the generated image inside the notification (BigPicture)",
                    checked = config.notifImagePreview,
                    enabled = config.receiveGenerationNotification,
                    onCheckedChange = { isChecked ->
                        viewModel.saveConfig(config.copy(notifImagePreview = isChecked))
                    }
                )
            }
            item {
                SwitchPreference(
                    title = "Queue Status Alerts",
                    subtitle = "Show heads-up alerts for queue progress (may be spammy)",
                    checked = config.notifQueueStatus,
                    onCheckedChange = { isChecked ->
                        viewModel.saveConfig(config.copy(notifQueueStatus = isChecked))
                    }
                )
            }
            item {
                val modeDesc = when (config.notificationMode) {
                    "Disabled" -> "No background progress notifications"
                    "Simple" -> "Shows overall batch progress & ETA"
                    else -> "Shows overall batch progress & ETA"
                }
                TextPreference(
                    title = "Progress Notification Mode",
                    value = "",
                    subtitle = "Current: ${config.notificationMode} - $modeDesc"
                ) {
                    showNotificationModeDialog = true
                }
            }
            item {
                val priorityDesc = when (config.notificationPriority) {
                    "High" -> "Heads-up banner, sound & vibration"
                    "Low" -> "Silent, no vibration, minimized"
                    else -> "Status bar icon, sound & vibration"
                }
                TextPreference(
                    title = "Alert Priority",
                    value = "",
                    subtitle = "Current: ${config.notificationPriority} - $priorityDesc"
                ) {
                    showNotificationPriorityDialog = true
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
                    onCheckedChange = { viewModel.saveConfig(config.copy(enablePersistentService = it)) }
                )
            }
            item {
                SwitchPreference(
                    title = "Overnight Batch Mode",
                    subtitle = "Ignores minor errors to keep batch running",
                    checked = config.overnightMode,
                    onCheckedChange = { viewModel.saveConfig(config.copy(overnightMode = it)) }
                )
            }

            if (!isIgnoringBattery) {
                item {
                    TextPreference(
                        title = "Remove Battery Restrictions",
                        subtitle = "Allow background tasks to run uninhibited (Recommended for Overnight Mode)",
                        value = ""
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
                    title = "Update Channel",
                    subtitle = "Current: ${config.updateChannel}",
                    value = ""
                ) { showUpdateChannelDialog = true }
            }
            if (config.updateChannel.equals("Beta", ignoreCase = true)) {
                item {
                    TextPreference(
                        title = "Beta Token",
                        subtitle = if (config.betaToken.isNotEmpty()) "Token is set (Tap to change)" else "Token is required for Beta updates",
                        value = ""
                    ) { showBetaTokenDialog = true }
                }
            }
            item {
                TextPreference(
                    title = "Check for Updates",
                    subtitle = "Look for new versions on the server",
                    value = ""
                ) {
                    viewModel.checkForUpdates(manual = true)
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }

        /* ==========================================================
         * DIALOG BUILDERS
         * ========================================================== */

        if (showUpdateChannelDialog) {
            AlertDialog(
                onDismissRequest = { showUpdateChannelDialog = false },
                title = { Text("Update Channel") },
                text = {
                    Column {
                        listOf("Stable", "Beta").forEach { channel ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    viewModel.saveConfig(config.copy(updateChannel = channel))
                                    showUpdateChannelDialog = false
                                }.padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = config.updateChannel == channel, onClick = null)
                                Spacer(Modifier.width(16.dp))
                                Text(channel, fontSize = 16.sp)
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showUpdateChannelDialog = false }) { Text("Close") } }
            )
        }

        if (showBetaTokenDialog) {
            var tempToken by remember { mutableStateOf(config.betaToken) }
            AlertDialog(
                onDismissRequest = { showBetaTokenDialog = false },
                title = { Text("Beta Access Token") },
                text = {
                    OutlinedTextField(
                        value = tempToken,
                        onValueChange = { tempToken = it },
                        label = { Text("Secret Token") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.saveConfig(config.copy(betaToken = tempToken.trim()))
                        showBetaTokenDialog = false
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { showBetaTokenDialog = false }) { Text("Cancel") }
                }
            )
        }

        if (updateManifest != null && updateManifest!!.versionCode != dismissedUpdateVersion && !isUpdateDownloading) {
            val manifest = updateManifest!!
            AlertDialog(
                onDismissRequest = {
                    if (!manifest.isCritical) dismissedUpdateVersion = manifest.versionCode
                },
                title = { Text("Update Available", fontWeight = FontWeight.Bold) },
                text = {
                    Column(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                        Text("Version ${manifest.versionName} (${manifest.channel})", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        if (!manifest.releaseDate.isNullOrEmpty()) {
                            Text("Released: ${manifest.releaseDate}", fontSize = 12.sp, color = Color.Gray)
                        }
                        Spacer(Modifier.height(12.dp))

                        val lang = Locale.getDefault().language
                        val changelogText = manifest.changelog?.get(lang) ?: manifest.changelog?.get("en") ?: emptyList()

                        if (changelogText.isNotEmpty()) {
                            Text("What's new:", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            changelogText.forEach { item ->
                                Row(modifier = Modifier.padding(bottom = 4.dp)) {
                                    Text("• ", fontSize = 14.sp)
                                    Text(item, fontSize = 14.sp)
                                }
                            }
                        } else {
                            Text("Do you want to download and install the new version?", fontSize = 14.sp)
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = { viewModel.downloadAndInstallUpdate() }) { Text("Update") }
                },
                dismissButton = {
                    if (!manifest.isCritical) {
                        TextButton(onClick = { dismissedUpdateVersion = manifest.versionCode }) { Text("Later") }
                    }
                }
            )
        }

        if (isUpdateDownloading) {
            AlertDialog(
                onDismissRequest = { },
                title = { Text("Downloading Update") },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        LinearProgressIndicator(
                            progress = { updateDownloadProgress },
                            modifier = Modifier.fillMaxWidth().padding(16.dp)
                        )
                        Text("${(updateDownloadProgress * 100).toInt()}%")
                    }
                },
                confirmButton = { }
            )
        }

        if (showNotificationModeDialog) {
            AlertDialog(
                onDismissRequest = { showNotificationModeDialog = false },
                title = { Text("Progress Notification Mode") },
                text = {
                    Column {
                        val modes = listOf(
                            Triple("Simple", "Simple", "Shows overall batch progress & ETA"),
                            Triple("Disabled", "Disabled", "No background progress notifications")
                        )
                        modes.forEach { (internalValue, displayName, desc) ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    viewModel.saveConfig(config.copy(notificationMode = internalValue))
                                    showNotificationModeDialog = false
                                }.padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
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
                confirmButton = { TextButton(onClick = { showNotificationModeDialog = false }) { Text("Close") } }
            )
        }

        if (showNotificationPriorityDialog) {
            AlertDialog(
                onDismissRequest = { showNotificationPriorityDialog = false },
                title = { Text("Alert Priority") },
                text = {
                    Column {
                        val priorities = listOf(
                            Triple("High", "High (Heads-Up)", "Pops up on screen with sound and vibration."),
                            Triple("Normal", "Normal (Default)", "Shows icon in status bar with sound and vibration."),
                            Triple("Low", "Low (Silent)", "Silently appears in the notification shade.")
                        )
                        priorities.forEach { (internalValue, displayName, desc) ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    viewModel.saveConfig(config.copy(notificationPriority = internalValue))
                                    showNotificationPriorityDialog = false
                                }.padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = config.notificationPriority == internalValue, onClick = null)
                                Spacer(Modifier.width(16.dp))
                                Column {
                                    Text(displayName, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                    Text(desc, fontSize = 12.sp, color = Color.Gray)
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showNotificationPriorityDialog = false }) { Text("Close") } }
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
                                modifier = Modifier.fillMaxWidth().clickable {
                                    viewModel.saveConfig(config.copy(previewMode = internalModes[index]))
                                    showPreviewModeDialog = false
                                }.padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = config.previewMode == internalModes[index], onClick = null)
                                Spacer(Modifier.width(16.dp))
                                Text(display, fontSize = 16.sp)
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showPreviewModeDialog = false }) { Text("Close") } }
            )
        }

        if (showUrlDialog) {
            var tempUrl by remember { mutableStateOf(config.apiUrl) }
            AlertDialog(
                onDismissRequest = { showUrlDialog = false },
                title = { Text("API URL") },
                text = { OutlinedTextField(value = tempUrl, onValueChange = { tempUrl = it }, modifier = Modifier.fillMaxWidth()) },
                confirmButton = {
                    TextButton(onClick = { viewModel.saveConfig(config.copy(apiUrl = tempUrl)); showUrlDialog = false }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { showUrlDialog = false }) { Text("Cancel") } }
            )
        }

        if (showBasePathDialog) {
            var tempPath by remember { mutableStateOf(config.serverBasePath) }
            AlertDialog(
                onDismissRequest = { showBasePathDialog = false },
                title = { Text("Server Base Path") },
                text = { OutlinedTextField(value = tempPath, onValueChange = { tempPath = it }, modifier = Modifier.fillMaxWidth()) },
                confirmButton = {
                    TextButton(onClick = { viewModel.saveConfig(config.copy(serverBasePath = tempPath)); showBasePathDialog = false }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { showBasePathDialog = false }) { Text("Cancel") } }
            )
        }

        if (showPathDialog) {
            var tempPath by remember { mutableStateOf(config.galleryPath) }
            AlertDialog(
                onDismissRequest = { showPathDialog = false },
                title = { Text("Gallery Server Path") },
                text = { OutlinedTextField(value = tempPath, onValueChange = { tempPath = it }, modifier = Modifier.fillMaxWidth()) },
                confirmButton = {
                    TextButton(onClick = { viewModel.saveConfig(config.copy(galleryPath = tempPath)); showPathDialog = false }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { showPathDialog = false }) { Text("Cancel") } }
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
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val t = tempTimeout.toIntOrNull() ?: 10
                        viewModel.saveConfig(config.copy(connectionTimeout = t))
                        showTimeoutDialog = false
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { showTimeoutDialog = false }) { Text("Cancel") } }
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
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val t = tempTimeout.toIntOrNull() ?: 45
                        viewModel.saveConfig(config.copy(checkpointTimeout = t))
                        showCheckpointTimeoutDialog = false
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { showCheckpointTimeoutDialog = false }) { Text("Cancel") } }
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
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        viewModel.saveConfig(config.copy(apiUrl = profile.url))
                                        showProfilesDialog = false
                                    }.padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
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
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                if (newProfileName.isNotBlank() && config.apiUrl.isNotBlank()) {
                                    viewModel.addServerProfile(newProfileName, config.apiUrl)
                                    newProfileName = ""
                                }
                            },
                            modifier = Modifier.align(Alignment.End).padding(top = 8.dp)
                        ) {
                            Text("Save Profile")
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showProfilesDialog = false }) { Text("Close") } }
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
                            color = if (testStatus?.contains("Operational") == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp)
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
                }
            )
        }
    }
}

/* ============================================================================
 * QUEUE SCREEN COMPOSABLE
 * ============================================================================ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(viewModel: ForgeViewModel, navController: NavHostController) {
    val queue by viewModel.generationQueue.collectAsStateWithLifecycle()
    val totalSize by viewModel.totalQueueSize.collectAsStateWithLifecycle()
    val completed by viewModel.completedQueueItems.collectAsStateWithLifecycle()
    val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()

    val models by viewModel.models.collectAsStateWithLifecycle()
    val availableLoras by viewModel.availableLoras.collectAsStateWithLifecycle()

    var editItemId by remember { mutableStateOf<String?>(null) }
    var editPosPrompt by remember { mutableStateOf("") }
    var editNegPrompt by remember { mutableStateOf("") }

    val onBackClick = remember { {
        if (navController.currentDestination?.route == "queue") {
            navController.popBackStack()
        }
        Unit
    } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Generation Queue", fontWeight = FontWeight.Bold)
                        if (totalSize > 0) {
                            Text("Progress: $completed / $totalSize", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (isGenerating) {
                        IconButton(onClick = { viewModel.interruptGeneration() }) {
                            Icon(Icons.Default.Stop, "Interrupt Current", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (queue.isNotEmpty()) {
                        IconButton(onClick = { viewModel.clearQueue() }) {
                            Icon(Icons.Default.Delete, "Clear Queue")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (queue.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.HourglassEmpty, null, modifier = Modifier.size(64.dp), tint = Color.Gray)
                    Spacer(Modifier.height(16.dp))
                    Text("Queue is empty", color = Color.Gray, fontSize = 18.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(queue) { item ->
                        val isFirst = queue.firstOrNull()?.id == item.id
                        val isActive = isFirst && isGenerating
                        var isExpanded by remember { mutableStateOf(false) }

                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                            ),
                            elevation = CardDefaults.cardElevation(if (isActive) 8.dp else 2.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { isExpanded = !isExpanded }
                                    .padding(16.dp)
                            ) {
                                // --- HEADER: Status + Checkpoint ---
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    if (isActive) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Generating...", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    } else {
                                        Text("Queued", fontSize = 12.sp, color = Color.Gray)
                                    }
                                    Spacer(Modifier.weight(1f))
                                    Icon(
                                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = "Expand",
                                        tint = Color.Gray
                                    )
                                }

                                Spacer(Modifier.height(8.dp))

                                // CHECKPOINT INFO
                                val checkpoint = item.payload.override_settings.sdModelCheckpoint ?: "Default Model"
                                val modelResource = models.find { it.name == checkpoint || it.title == checkpoint }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (modelResource != null) {
                                        AsyncImage(
                                            model = viewModel.getPreviewUrl(modelResource.path, isLora = false),
                                            contentDescription = null,
                                            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(4.dp)),
                                            contentScale = ContentScale.Crop
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    } else {
                                        Icon(Icons.Default.Extension, null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(modelResource?.title ?: checkpoint, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }

                                // LORAS
                                val lorasInPrompt = remember(item.positivePrompt) {
                                    val regex = Regex("<lora:([^:]+):([0-9.]+)>")
                                    regex.findAll(item.positivePrompt).map { match ->
                                        ActiveLora(match.groupValues[1], match.groupValues[2].toFloatOrNull() ?: 1f)
                                    }.toList()
                                }

                                if (lorasInPrompt.isNotEmpty()) {
                                    LazyRow(
                                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        items(lorasInPrompt) { lora ->
                                            val loraResource = availableLoras.find { it.name == lora.name }
                                            Surface(
                                                shape = MaterialTheme.shapes.small,
                                                color = MaterialTheme.colorScheme.background,
                                                modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 6.dp)) {
                                                    if (loraResource != null) {
                                                        AsyncImage(
                                                            model = viewModel.getPreviewUrl(loraResource.path, isLora = true),
                                                            contentDescription = null,
                                                            modifier = Modifier.size(24.dp).clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)),
                                                            contentScale = ContentScale.Crop
                                                        )
                                                    } else {
                                                        Box(modifier = Modifier.size(24.dp).background(Color.Gray).clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)))
                                                    }
                                                    Spacer(Modifier.width(4.dp))
                                                    Text(loraResource?.title ?: lora.name, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 80.dp))
                                                    Text(" : ${lora.strength}", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                                }
                                            }
                                        }
                                    }
                                }

                                // --- EXPANDABLE BODY ---
                                AnimatedVisibility(visible = isExpanded) {
                                    Column(modifier = Modifier.padding(top = 12.dp)) {
                                        HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))
                                        Text("Positive Prompt", fontSize = 10.sp, color = Color.Gray)
                                        Text(item.positivePrompt, fontSize = 12.sp)
                                        Spacer(Modifier.height(4.dp))

                                        if (item.payload.negative_prompt.isNotBlank()) {
                                            Text("Negative Prompt", fontSize = 10.sp, color = Color.Gray)
                                            Text(item.payload.negative_prompt, fontSize = 12.sp)
                                            Spacer(Modifier.height(4.dp))
                                        }

                                        val p = item.payload
                                        Text("Steps: ${p.steps} | CFG: ${p.cfg_scale} | Sampler: ${p.sampler_name}", fontSize = 10.sp, color = Color.Gray)
                                        Text("Size: ${p.width}x${p.height} | Seed: ${p.seed}", fontSize = 10.sp, color = Color.Gray)
                                    }
                                }

                                // --- BUTTONS ---
                                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Row {
                                        IconButton(
                                            onClick = { viewModel.moveQueueItemUp(item.id) },
                                            enabled = !isFirst,
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(Icons.Default.KeyboardArrowUp, "Move Up")
                                        }
                                        IconButton(
                                            onClick = { viewModel.moveQueueItemDown(item.id) },
                                            enabled = queue.lastOrNull()?.id != item.id,
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(Icons.Default.KeyboardArrowDown, "Move Down")
                                        }
                                    }

                                    // Block Edit/Delete while generating
                                    if (!isActive) {
                                        Row {
                                            IconButton(
                                                onClick = {
                                                    editPosPrompt = item.payload.prompt
                                                    editNegPrompt = item.payload.negative_prompt
                                                    editItemId = item.id
                                                },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(Icons.Default.Edit, "Edit", tint = MaterialTheme.colorScheme.primary)
                                            }
                                            Spacer(Modifier.width(8.dp))
                                            IconButton(
                                                onClick = { viewModel.removeFromQueue(item.id) },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(Icons.Default.Delete, "Remove", tint = MaterialTheme.colorScheme.error)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (editItemId != null) {
            AlertDialog(
                onDismissRequest = { editItemId = null },
                title = { Text("Edit Queue Item") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = editPosPrompt,
                            onValueChange = { editPosPrompt = it },
                            label = { Text("Positive Prompt") },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 200.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = editNegPrompt,
                            onValueChange = { editNegPrompt = it },
                            label = { Text("Negative Prompt") },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 150.dp)
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.updateQueueItem(editItemId!!, editPosPrompt, editNegPrompt)
                        editItemId = null
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { editItemId = null }) { Text("Cancel") }
                }
            )
        }
    }
}

/* ============================================================================
 * GALLERY SCREEN COMPOSABLE
 * ============================================================================ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(viewModel: ForgeViewModel, navController: NavHostController) {
    val galleryFiles by viewModel.galleryFiles.collectAsStateWithLifecycle()
    val currentPath by viewModel.currentGalleryPath.collectAsStateWithLifecycle()
    val isLoading by viewModel.isGalleryLoading.collectAsStateWithLifecycle()
    val error by viewModel.galleryError.collectAsStateWithLifecycle()
    val galleryMode by viewModel.galleryMode.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()

    // Subskrypcja listy ulubionych ścieżek
    val favoritePaths by viewModel.favoritePaths.collectAsStateWithLifecycle()

    var fullscreenIndex by remember { mutableIntStateOf(-1) }

    val safePopBack = {
        if (navController.currentDestination?.route == "gallery") {
            navController.popBackStack()
        }
    }

    val onBack = {
        if (error != null) {
            safePopBack()
        } else if (currentPath == "virtual://favorites") {
            val rootPath = config.galleryPath.ifEmpty { "Root" }
            viewModel.fetchGalleryFolder(rootPath)
        } else if (currentPath.isNotEmpty() && currentPath != "Root" && currentPath != config.galleryPath) {
            val lastSlash = currentPath.lastIndexOf('/')
            val lastBackslash = currentPath.lastIndexOf('\\')
            val lastSeparator = maxOf(lastSlash, lastBackslash)

            val parent = if (lastSeparator > 0) {
                currentPath.substring(0, lastSeparator)
            } else {
                config.galleryPath
            }

            if (config.galleryPath.isNotEmpty() && config.galleryPath != "Root" && !parent.startsWith(config.galleryPath)) {
                safePopBack()
            } else {
                viewModel.fetchGalleryFolder(parent)
            }
        } else {
            safePopBack()
        }
    }

    BackHandler(onBack = {
        if (fullscreenIndex >= 0) {
            fullscreenIndex = -1
        } else {
            onBack()
        }
    })

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (galleryMode == GalleryMode.PROMPT_PICKER) "Select Image" else "Gallery", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = if (currentPath == "virtual://favorites") "⭐ Favorites" else currentPath.ifEmpty { "Root" },
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.fetchGalleryFolder(currentPath) }) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (isLoading) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(4.dp)
                ) {
                    items(24) {
                        Box(
                            modifier = Modifier
                                .padding(4.dp)
                                .aspectRatio(1f)
                                .clip(MaterialTheme.shapes.small)
                                .background(shimmerBrush())
                        )
                    }
                }
            } else if (error != null) {
                Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.ErrorOutline, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    Text(error!!, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { viewModel.fetchGalleryFolder(currentPath) }) { Text("Retry") }
                }
            } else if (galleryFiles.isEmpty()) {
                Text("No files found", modifier = Modifier.align(Alignment.Center), color = Color.Gray)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(4.dp)
                ) {
                    itemsIndexed(galleryFiles) { index, item ->
                        if (item.isDir) {
                            val isFavoritesFolder = item.fullpath == "virtual://favorites"
                            Card(
                                modifier = Modifier.padding(4.dp).aspectRatio(1f).clickable { viewModel.fetchGalleryFolder(item.fullpath) },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize().padding(8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = if (isFavoritesFolder) Icons.Default.Star else Icons.Default.Folder,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp),
                                        tint = if (isFavoritesFolder) Color(0xFFFFD54F) else MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(item.name, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        } else {
                            // Rozpoznajemy stan ulubionego na żywo z pobranej listy ścieżek
                            val isFavorite = favoritePaths.contains(item.fullpath) || currentPath == "virtual://favorites"
                            val isForgeGen = item.name.contains("ForgeGen", ignoreCase = true)

                            // Grubsza (6.dp) i bardzo dobrze widoczna ramka!
                            val frameModifier = when {
                                isFavorite -> Modifier.border(6.dp, coloredShimmerBrush(Color(0xFFFFD54F)), MaterialTheme.shapes.small)
                                isForgeGen -> Modifier.border(6.dp, coloredShimmerBrush(MaterialTheme.colorScheme.primary), MaterialTheme.shapes.small)
                                else -> Modifier
                            }

                            Box(
                                modifier = Modifier
                                    .padding(4.dp)
                                    .aspectRatio(1f)
                                    .then(frameModifier)
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable {
                                        if (galleryMode == GalleryMode.PROMPT_PICKER) {
                                            viewModel.recoverPromptFromImage(item)
                                            if (navController.currentDestination?.route == "gallery") {
                                                navController.popBackStack()
                                            }
                                        } else {
                                            fullscreenIndex = index
                                        }
                                    }
                            ) {
                                SubcomposeAsyncImage(
                                    model = viewModel.getGalleryImageUrl(item),
                                    contentDescription = item.name,
                                    loading = {
                                        Box(modifier = Modifier.fillMaxSize().background(shimmerBrush()))
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .background(Color.Black.copy(alpha = 0.6f))
                                        .padding(vertical = 4.dp, horizontal = 2.dp)
                                ) {
                                    Text(
                                        text = item.name,
                                        color = Color.White,
                                        fontSize = 9.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ZABEZPIECZENIE: IndexOutOfBoundsException Fail-Safe
        if (fullscreenIndex >= 0) {
            if (galleryFiles.isEmpty()) {
                fullscreenIndex = -1
            } else {
                val safeIndex = fullscreenIndex.coerceIn(0, galleryFiles.size - 1)
                val imageFiles = galleryFiles.filter { !it.isDir }
                val targetFile = galleryFiles[safeIndex]
                val initialPage = imageFiles.indexOf(targetFile).coerceAtLeast(0)

                FullscreenGalleryViewer(
                    viewModel = viewModel,
                    config = config,
                    images = imageFiles,
                    initialIndex = initialPage,
                    onDismiss = { fullscreenIndex = -1 }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FullscreenGalleryViewer(
    viewModel: ForgeViewModel,
    config: AppConfig,
    images: List<GalleryItem>,
    initialIndex: Int,
    onDismiss: () -> Unit
) {
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { images.size })
    val currentItem = images.getOrNull(pagerState.currentPage)
    val context = LocalContext.current

    val isFavorite by viewModel.isCurrentFavorite.collectAsStateWithLifecycle()

    LaunchedEffect(pagerState.currentPage) {
        if (currentItem != null) {
            viewModel.loadMetadataForImage(currentItem)
            viewModel.checkIfFavorite(currentItem.fullpath)
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            Row(
                modifier = Modifier.fillMaxWidth().background(Color(0x88000000)).padding(vertical = 4.dp, horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
                Text(currentItem?.name ?: "", color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))

                IconButton(onClick = {
                    currentItem?.let { viewModel.toggleFavorite(it) }
                }) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = "Favorite",
                        tint = if (isFavorite) Color(0xFFFFD54F) else Color.White
                    )
                }

                IconButton(onClick = {
                    currentItem?.let {
                        viewModel.shareImage(it) { intent -> context.startActivity(intent) }
                    }
                }) {
                    Icon(Icons.Default.Share, "Share", tint = Color.White)
                }

                val showMetadata by viewModel.showGalleryMetadata.collectAsStateWithLifecycle()
                IconButton(onClick = { viewModel.toggleGalleryMetadata() }) {
                    Icon(Icons.Default.Info, "Info", tint = Color.White, modifier = Modifier.then(if (showMetadata) Modifier.background(Color(0x55FFFFFF), CircleShape).padding(2.dp) else Modifier))
                }
                IconButton(onClick = { currentItem?.let { viewModel.downloadImage(it) } }) {
                    Icon(Icons.Default.Save, "Save", tint = Color.White)
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (config.swipeToBrowseGallery) {
                    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                        SubcomposeAsyncImage(
                            model = viewModel.getGalleryImageUrl(images[page]),
                            contentDescription = null,
                            loading = {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.TopCenter
                        )
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        if (currentItem != null) {
                            SubcomposeAsyncImage(
                                model = viewModel.getGalleryImageUrl(currentItem),
                                contentDescription = null,
                                loading = {
                                    Box(modifier = Modifier.fillMaxWidth().height(400.dp), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = ContentScale.Fit,
                                alignment = Alignment.TopCenter
                            )
                        }
                    }
                }

                val showMetadata by viewModel.showGalleryMetadata.collectAsStateWithLifecycle()
                val currentMetadata by viewModel.currentImageMetadata.collectAsStateWithLifecycle()

                if (showMetadata) {
                    val fileInfo = remember(currentItem) {
                        if (currentItem != null) {
                            val sizeStr = currentItem.displaySize
                            val timeStr = currentItem.createdTime?.let { timeVal ->
                                try {
                                    val timeLong = timeVal.toDouble().toLong() * 1000
                                    java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(timeLong))
                                } catch (e: Exception) {
                                    timeVal
                                }
                            }

                            val parts = listOfNotNull(
                                currentItem.name,
                                timeStr,
                                sizeStr.takeIf { it.isNotEmpty() }
                            )
                            parts.joinToString(" • ")
                        } else null
                    }

                    MetadataAlertDialog(
                        metadata = currentMetadata,
                        fileInfo = fileInfo,
                        onDismiss = { viewModel.toggleGalleryMetadata() },
                        onApplyAll = null,
                        onApplyPrompt = { pos, neg ->
                            viewModel.updateState { it.copy(positivePrompt = pos, negativePrompt = neg) }
                            viewModel.showSnackbar("Prompts Applied")
                        },
                        onApplyModel = { modelName ->
                            viewModel.changeCheckpoint(modelName)
                            viewModel.showSnackbar("Model Applied: $modelName")
                        },
                        onApplyLoras = { loraList ->
                            loraList.forEach { loraTag ->
                                val loraName = loraTag.substringAfter("<lora:").substringBefore(":")
                                if (loraName.isNotEmpty()) viewModel.appendLora(loraName)
                            }
                            viewModel.showSnackbar("LoRAs Applied")
                        }
                    )
                }
            }
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
    onApplyAll: (() -> Unit)? = null
) {
    var posPrompt = ""
    var negPrompt = ""
    var modelName = ""
    val loras = mutableListOf<String>()

    if (metadata != null && !metadata.startsWith("Loading") && !metadata.startsWith("Failed") && !metadata.startsWith("Invalid") && !metadata.startsWith("Server")) {
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
                if (currentMode == 0) posPrompt += line + "\n"
                else if (currentMode == 1) negPrompt += line + "\n"
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
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Generation Data", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(bottom = if (fileInfo != null) 4.dp else 8.dp))

                if (fileInfo != null) {
                    Text(fileInfo, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp))
                }

                Box(modifier = Modifier.weight(1f, fill = false).background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small).padding(8.dp)) {
                    Text(
                        text = metadata ?: "Loading...",
                        fontSize = 11.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    )
                }

                if (metadata != null && posPrompt.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Apply to current session:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))

                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (onApplyAll != null) {
                            Button(onClick = onApplyAll, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), modifier = Modifier.height(32.dp)) {
                                Text("Apply All", fontSize = 12.sp)
                            }
                        }

                        OutlinedButton(onClick = { onApplyPrompt(posPrompt, negPrompt) }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), modifier = Modifier.height(32.dp)) {
                            Text("Prompt", fontSize = 12.sp)
                        }

                        if (modelName.isNotEmpty()) {
                            OutlinedButton(onClick = { onApplyModel(modelName) }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), modifier = Modifier.height(32.dp)) {
                                Text("Model", fontSize = 12.sp)
                            }
                        }

                        if (loras.isNotEmpty()) {
                            OutlinedButton(onClick = { onApplyLoras(loras) }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), modifier = Modifier.height(32.dp)) {
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