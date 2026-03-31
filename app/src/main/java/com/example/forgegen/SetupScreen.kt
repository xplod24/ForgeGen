package com.yourname.forgegen

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
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
import androidx.navigation.NavHostController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import com.yourname.forgegen.Translator.t

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    viewModel: ForgeViewModel,
    navController: NavHostController
) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val useMultiThreading by viewModel.useMultiThreading.collectAsStateWithLifecycle()

    var showUrlDialog by remember { mutableStateOf(false) }
    var showPathDialog by remember { mutableStateOf(false) }
    var showCheckpointPathDialog by remember { mutableStateOf(false) }
    var showLoraPathDialog by remember { mutableStateOf(false) }
    var showTimeoutDialog by remember { mutableStateOf(false) }
    var showCheckpointTimeoutDialog by remember { mutableStateOf(false) }
    var showProfilesDialog by remember { mutableStateOf(false) }
    var showVerbosityDialog by remember { mutableStateOf(false) }
    var showPreviewModeDialog by remember { mutableStateOf(false) }

    var isTestingConnection by remember { mutableStateOf(false) }
    var testStatus by remember { mutableStateOf<String?>(null) }
    var testResults by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val keyguardManager = remember { context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager }
    val isDeviceSecure = remember { keyguardManager.isDeviceSecure }

    val pm = remember { context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager }
    var isIgnoringBattery by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(context.packageName)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isIgnoringBattery = pm.isIgnoringBatteryOptimizations(context.packageName)
                viewModel.refreshDispatcher()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val onBackClick = remember { {
        if (navController.previousBackStackEntry != null) {
            navController.popBackStack()
        } else {
            navController.navigate("main") { popUpTo(navController.graph.startDestinationId) }
        }
        Unit
    } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings".t, fontSize = 20.sp, fontWeight = FontWeight.Bold) },
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

            item { PreferenceCategory("Language".t) }
            item {
                var langExpanded by remember { mutableStateOf(false) }
                Box(modifier = Modifier.fillMaxWidth()) {
                    TextPreference(
                        title = "Language (Język)".t,
                        value = if (config.language == "pl") "Polski" else "English",
                        onClick = { langExpanded = true }
                    )
                    DropdownMenu(expanded = langExpanded, onDismissRequest = { langExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("English") },
                            onClick = {
                                viewModel.saveConfig(config.copy(language = "en"))
                                Translator.currentLang = "en"
                                langExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Polski") },
                            onClick = {
                                viewModel.saveConfig(config.copy(language = "pl"))
                                Translator.currentLang = "pl"
                                langExpanded = false
                            }
                        )
                    }
                }
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item { PreferenceCategory("Server Connection".t) }
            item {
                TextPreference(title = "API URL".t, value = config.apiUrl) { showUrlDialog = true }
            }
            item {
                TextPreference(title = "Server Profiles".t, subtitle = "${config.serverProfiles.size} " + "saved profiles".t, value = "") { showProfilesDialog = true }
            }
            item {
                TextPreference(title = "Connection Timeout".t, subtitle = "${config.connectionTimeout} " + "seconds".t, value = "") { showTimeoutDialog = true }
            }
            item {
                TextPreference(title = "Checkpoint Load Timeout".t, subtitle = "${config.checkpointTimeout} " + "seconds".t, value = "") { showCheckpointTimeoutDialog = true }
            }
            item {
                TextPreference(title = "Test Connection".t, subtitle = "Run API diagnostics".t, value = "") {
                    isTestingConnection = true
                    testStatus = "Running diagnostics...".t
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
                                testClient.newCall(req).await().use { res ->
                                    val time = System.currentTimeMillis() - start
                                    if (res.isSuccessful) {
                                        results.add(ep to "${time}ms \u2714")
                                    } else if (res.code == 401 || res.code == 403) {
                                        results.add(ep to "${"Auth Needed".t} \u2718")
                                        allSuccess = false
                                    } else {
                                        results.add(ep to "Err ${res.code} \u2718")
                                        allSuccess = false
                                    }
                                }
                            } catch (e: Exception) {
                                results.add(ep to "${"Failed".t} \u2718")
                                allSuccess = false
                            }
                            withContext(Dispatchers.Main) { testResults = results.toList() }
                        }

                        withContext(Dispatchers.Main) {
                            testStatus = if (allSuccess) "All Systems Operational!".t else "Some APIs Failed.".t
                        }
                    }
                }
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item { PreferenceCategory("Security".t) }
            if (!isDeviceSecure) {
                item {
                    Text(
                        text = "Your device does not have a PIN, Pattern, or Password set. Please set a lock in your device settings to enable App Security.".t,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
            item {
                SwitchPreference(
                    title = "Use Native Security".t,
                    subtitle = "Require device PIN/Password/Pattern to open app".t,
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
                    title = "Biometric App Lock".t,
                    subtitle = "Require fingerprint or face scan on launch".t,
                    checked = config.useBiometricLock,
                    enabled = config.useNativeSecurity && isDeviceSecure,
                    onCheckedChange = { viewModel.saveConfig(config.copy(useBiometricLock = it)) }
                )
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item { PreferenceCategory("Paths & Media".t) }
            item {
                TextPreference(title = "Gallery Server Path".t, value = config.galleryPath) { showPathDialog = true }
            }
            item {
                TextPreference(title = "Checkpoint Directory Path".t, value = config.checkpointPath) { showCheckpointPathDialog = true }
            }
            item {
                TextPreference(title = "LoRA Directory Path".t, value = config.loraPath) { showLoraPathDialog = true }
            }
            item {
                SwitchPreference(
                    title = "Swipe to Browse Images".t,
                    subtitle = "Use horizontal swiping in fullscreen preview".t,
                    checked = config.swipeToBrowseGallery,
                    onCheckedChange = { viewModel.saveConfig(config.copy(swipeToBrowseGallery = it)) }
                )
            }
            item {
                SwitchPreference(
                    title = "Show Grid After Batch".t,
                    subtitle = "Temporarily show a grid of images when a batch generation finishes".t,
                    checked = config.showGridAfterGeneration,
                    onCheckedChange = { viewModel.saveConfig(config.copy(showGridAfterGeneration = it)) }
                )
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item { PreferenceCategory("Appearance & UI".t) }
            item {
                SwitchPreference(
                    title = "Enable Dark Mode".t,
                    checked = config.isDarkMode,
                    onCheckedChange = { viewModel.saveConfig(config.copy(isDarkMode = it)) }
                )
            }
            item {
                val currentModeDisplay = when(config.previewMode) {
                    "None" -> "Loading circle".t
                    "Finished" -> "Only last finished batch".t
                    "Normal" -> "Normal preview".t
                    else -> "Normal preview".t
                }
                TextPreference(
                    title = "Preview Mode".t,
                    value = currentModeDisplay,
                    subtitle = "Choose how images are displayed during generation".t
                ) { showPreviewModeDialog = true }
            }
            item {
                SwitchPreference(
                    title = "Show Active Tags UI".t,
                    subtitle = "Show a quick edit button to visually reorder tags in prompt".t,
                    checked = config.showActiveTagsUI,
                    onCheckedChange = { viewModel.saveConfig(config.copy(showActiveTagsUI = it)) }
                )
            }
            item {
                SwitchPreference(
                    title = "Keep Screen On".t,
                    subtitle = "Prevents phone sleep while rendering".t,
                    checked = config.keepScreenOn,
                    onCheckedChange = { viewModel.saveConfig(config.copy(keepScreenOn = it)) }
                )
            }
            item {
                SwitchPreference(
                    title = "Screen Dimming Mode".t,
                    subtitle = "Dims the screen instead of full wake lock when idle".t,
                    checked = config.screenDimming,
                    onCheckedChange = { viewModel.saveConfig(config.copy(screenDimming = it)) }
                )
            }
            if (config.screenDimming) {
                item {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Dimming Timeout (Minutes)".t + ": ${config.screenDimmingTimeout}", modifier = Modifier.weight(1f), fontSize = 14.sp)
                        Slider(
                            value = config.screenDimmingTimeout.toFloat(),
                            onValueChange = { viewModel.saveConfig(config.copy(screenDimmingTimeout = it.roundToInt())) },
                            valueRange = 1f..15f,
                            steps = 14,
                            modifier = Modifier.width(150.dp)
                        )
                    }
                }
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item { PreferenceCategory("Notifications & Advanced".t) }
            item {
                SwitchPreference(
                    title = "Receive Generation Completion Notification".t,
                    subtitle = "Get alerted when batch is fully completed".t,
                    checked = config.receiveGenerationNotification,
                    onCheckedChange = { viewModel.saveConfig(config.copy(receiveGenerationNotification = it)) }
                )
            }
            item {
                SwitchPreference(
                    title = "Silent Notifications".t,
                    subtitle = "Disable sound and vibration alerts for app notifications".t,
                    checked = config.silentNotifications,
                    onCheckedChange = { viewModel.saveConfig(config.copy(silentNotifications = it)) }
                )
            }
            item {
                TextPreference(
                    title = "Notification Detail Level".t,
                    value = config.notificationVerbosity,
                    onClick = { showVerbosityDialog = true }
                )
            }
            item {
                SwitchPreference(
                    title = "Overnight Batch Mode".t,
                    subtitle = "Ignores minor errors to keep batch running".t,
                    checked = config.overnightMode,
                    onCheckedChange = { viewModel.saveConfig(config.copy(overnightMode = it)) }
                )
            }
            item {
                SwitchPreference(
                    title = "Multi-threaded Processing".t,
                    subtitle = if (isIgnoringBattery) "Utilize all cores. Disable to save battery".t else "Requires removing battery restrictions first!".t,
                    checked = useMultiThreading && isIgnoringBattery,
                    enabled = isIgnoringBattery,
                    onCheckedChange = { viewModel.setMultiThreading(it) }
                )
            }
            if (!isIgnoringBattery) {
                item {
                    TextPreference(
                        title = "Remove Battery Restrictions".t,
                        subtitle = "Allow app to run uninhibited in the background".t,
                        value = ""
                    ) {
                        val intent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        context.startActivity(intent)
                    }
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }

        if (showPreviewModeDialog) {
            AlertDialog(
                onDismissRequest = { showPreviewModeDialog = false },
                title = { Text("Preview Mode".t) },
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
                                Text(display.t, fontSize = 16.sp)
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showPreviewModeDialog = false }) { Text("Close".t) } }
            )
        }

        if (showUrlDialog) {
            var tempUrl by remember { mutableStateOf(config.apiUrl) }
            AlertDialog(
                onDismissRequest = { showUrlDialog = false },
                title = { Text("API URL".t) },
                text = { OutlinedTextField(value = tempUrl, onValueChange = { tempUrl = it }, modifier = Modifier.fillMaxWidth()) },
                confirmButton = {
                    TextButton(onClick = { viewModel.saveConfig(config.copy(apiUrl = tempUrl)); showUrlDialog = false }) { Text("Save".t) }
                },
                dismissButton = { TextButton(onClick = { showUrlDialog = false }) { Text("Cancel".t) } }
            )
        }

        if (showPathDialog) {
            var tempPath by remember { mutableStateOf(config.galleryPath) }
            AlertDialog(
                onDismissRequest = { showPathDialog = false },
                title = { Text("Gallery Server Path".t) },
                text = { OutlinedTextField(value = tempPath, onValueChange = { tempPath = it }, modifier = Modifier.fillMaxWidth()) },
                confirmButton = {
                    TextButton(onClick = { viewModel.saveConfig(config.copy(galleryPath = tempPath)); showPathDialog = false }) { Text("Save".t) }
                },
                dismissButton = { TextButton(onClick = { showPathDialog = false }) { Text("Cancel".t) } }
            )
        }

        if (showCheckpointPathDialog) {
            var tempPath by remember { mutableStateOf(config.checkpointPath) }
            AlertDialog(
                onDismissRequest = { showCheckpointPathDialog = false },
                title = { Text("Checkpoint Directory Path".t) },
                text = { OutlinedTextField(value = tempPath, onValueChange = { tempPath = it }, modifier = Modifier.fillMaxWidth()) },
                confirmButton = {
                    TextButton(onClick = { viewModel.saveConfig(config.copy(checkpointPath = tempPath)); showCheckpointPathDialog = false }) { Text("Save".t) }
                },
                dismissButton = { TextButton(onClick = { showCheckpointPathDialog = false }) { Text("Cancel".t) } }
            )
        }

        if (showLoraPathDialog) {
            var tempPath by remember { mutableStateOf(config.loraPath) }
            AlertDialog(
                onDismissRequest = { showLoraPathDialog = false },
                title = { Text("LoRA Directory Path".t) },
                text = { OutlinedTextField(value = tempPath, onValueChange = { tempPath = it }, modifier = Modifier.fillMaxWidth()) },
                confirmButton = {
                    TextButton(onClick = { viewModel.saveConfig(config.copy(loraPath = tempPath)); showLoraPathDialog = false }) { Text("Save".t) }
                },
                dismissButton = { TextButton(onClick = { showLoraPathDialog = false }) { Text("Cancel".t) } }
            )
        }

        if (showTimeoutDialog) {
            var tempTimeout by remember { mutableStateOf(config.connectionTimeout.toString()) }
            AlertDialog(
                onDismissRequest = { showTimeoutDialog = false },
                title = { Text("Connection Timeout (sec)".t) },
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
                    }) { Text("Save".t) }
                },
                dismissButton = { TextButton(onClick = { showTimeoutDialog = false }) { Text("Cancel".t) } }
            )
        }

        if (showCheckpointTimeoutDialog) {
            var tempTimeout by remember { mutableStateOf(config.checkpointTimeout.toString()) }
            AlertDialog(
                onDismissRequest = { showCheckpointTimeoutDialog = false },
                title = { Text("Timeout for switching large models".t) },
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
                    }) { Text("Save".t) }
                },
                dismissButton = { TextButton(onClick = { showCheckpointTimeoutDialog = false }) { Text("Cancel".t) } }
            )
        }

        if (showVerbosityDialog) {
            AlertDialog(
                onDismissRequest = { showVerbosityDialog = false },
                title = { Text("Notification Verbosity".t) },
                text = {
                    Column {
                        listOf("Full", "Brief", "Simple").forEach { level ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    viewModel.saveConfig(config.copy(notificationVerbosity = level))
                                    showVerbosityDialog = false
                                }.padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = config.notificationVerbosity == level, onClick = null)
                                Spacer(Modifier.width(16.dp))
                                Text(level, fontSize = 16.sp)
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showVerbosityDialog = false }) { Text("Close".t) } }
            )
        }

        if (showProfilesDialog) {
            var newProfileName by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showProfilesDialog = false },
                title = { Text("Server Profiles".t) },
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
                                        Icon(Icons.Default.Delete, "Delete".t, tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Text("Add New Profile".t, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        OutlinedTextField(
                            value = newProfileName,
                            onValueChange = { newProfileName = it },
                            label = { Text("Profile Name (Uses current API URL)".t) },
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
                            Text("Save Profile".t)
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showProfilesDialog = false }) { Text("Close".t) } }
            )
        }

        if (isTestingConnection || testStatus != null) {
            AlertDialog(
                onDismissRequest = {
                    isTestingConnection = false
                    testStatus = null
                },
                title = { Text("Diagnostics".t) },
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
                    }) { Text("Close".t) }
                }
            )
        }
    }
}