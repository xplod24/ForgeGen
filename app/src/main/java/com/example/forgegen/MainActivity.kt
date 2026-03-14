package com.yourname.forgegen

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

const val APP_VERSION = "1"

class MainActivity : ComponentActivity() {

    val navEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)

    private val exitReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "ACTION_EXIT_APP") {
                val manager = context?.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
                manager?.cancel(1001)

                val serviceIntent = Intent(context, GenerationService::class.java)
                context?.stopService(serviceIntent)

                finishAndRemoveTask()
                android.os.Process.killProcess(android.os.Process.myPid())
                exitProcess(0)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == "ACTION_OPEN_SETTINGS") {
            navEvents.tryEmit("setup")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Trigger persistent background service immediately on app launch
        val serviceIntent = Intent(this, GenerationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        if (intent?.action == "ACTION_OPEN_SETTINGS") {
            navEvents.tryEmit("setup")
        }

        val filter = IntentFilter("ACTION_EXIT_APP")
        ContextCompat.registerReceiver(this, exitReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        setContent {
            val viewModel: ForgeViewModel = viewModel()
            val config by viewModel.config.collectAsState()

            val isOnline by currentConnectivityStatus(this)
            val isConnected by viewModel.isConnected.collectAsState()

            val isServerBusy by ForgeState.isServerBusy.collectAsState()
            val generationQueue by ForgeState.generationQueue.collectAsState()

            val isGenerating by viewModel.isGenerating.collectAsState()
            val progress by viewModel.progress.collectAsState()
            val status by viewModel.statusText.collectAsState()
            val isRestoringPrompt by viewModel.isRestoringPrompt.collectAsState()

            val context = LocalContext.current
            val prefs = remember { context.getSharedPreferences("ForgeGenPrefs", Context.MODE_PRIVATE) }

            val isSamsungDevice = remember { Build.MANUFACTURER.equals("samsung", ignoreCase = true) }
            var useSamsungTheme by remember { mutableStateOf(prefs.getBoolean("use_samsung_theme", isSamsungDevice)) }

            val aospColorScheme = if (config.isDarkMode) darkColorScheme() else lightColorScheme()

            val samsungColorScheme = if (config.isDarkMode) {
                darkColorScheme(
                    primary = Color(0xFF3E80FF),
                    background = Color.Black,
                    surface = Color(0xFF151515),
                    surfaceVariant = Color(0xFF252525),
                    primaryContainer = Color.Black,
                    onPrimaryContainer = Color.White
                )
            } else {
                lightColorScheme(
                    primary = Color(0xFF005BFF),
                    background = Color(0xFFF2F2F2),
                    surface = Color.White,
                    surfaceVariant = Color(0xFFE5E5E5),
                    primaryContainer = Color(0xFFF2F2F2),
                    onPrimaryContainer = Color.Black
                )
            }

            val appTypography = Typography(
                bodyLarge = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 16.sp),
                bodyMedium = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                bodySmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                labelLarge = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                labelMedium = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                labelSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
                titleLarge = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 22.sp),
                titleMedium = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 16.sp),
                titleSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            )

            val samsungTypography = Typography(
                bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp),
                bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp),
                bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp),
                labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 10.sp, fontWeight = FontWeight.Medium),
                titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 24.sp, fontWeight = FontWeight.Bold),
                titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 18.sp, fontWeight = FontWeight.Bold),
                titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            )

            val samsungShapes = Shapes(
                small = RoundedCornerShape(12.dp),
                medium = RoundedCornerShape(20.dp),
                large = RoundedCornerShape(26.dp),
                extraLarge = RoundedCornerShape(32.dp)
            )

            val finalColorScheme = if (isSamsungDevice && useSamsungTheme) samsungColorScheme else aospColorScheme
            val finalTypography = if (isSamsungDevice && useSamsungTheme) samsungTypography else appTypography
            val finalShapes = if (isSamsungDevice && useSamsungTheme) samsungShapes else MaterialTheme.shapes

            if (Build.VERSION.SDK_INT >= 33) {
                val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
                LaunchedEffect(Unit) {
                    if (ContextCompat.checkSelfPermission(this@MainActivity, "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                        permissionLauncher.launch("android.permission.POST_NOTIFICATIONS")
                    }
                }
            }

            val navController = rememberNavController()

            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = navBackStackEntry?.destination?.route
            val isSessionActive = currentRoute == "main" || currentRoute == "gallery" || currentRoute == "terminal"

            LaunchedEffect(isOnline, isConnected, isGenerating, progress, status, isRestoringPrompt, isSessionActive, isServerBusy, generationQueue, config.silentNotifications) {
                if (!isGenerating) delay(2100)

                if (ContextCompat.checkSelfPermission(context, "android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED) {
                    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

                    val channelId = if (config.silentNotifications) "GenerationChannelSilent" else "GenerationChannelAlert"

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val silentChannel = android.app.NotificationChannel("GenerationChannelSilent", "App Status (Silent)", android.app.NotificationManager.IMPORTANCE_LOW)
                        val alertChannel = android.app.NotificationChannel("GenerationChannelAlert", "App Status (Alerts)", android.app.NotificationManager.IMPORTANCE_DEFAULT)
                        manager.createNotificationChannel(silentChannel)
                        manager.createNotificationChannel(alertChannel)
                    }

                    val notifTitle = if (isGenerating || isServerBusy) "Forge Generator - Working" else "Forge Generator"

                    val notifText = when {
                        !isOnline -> "No Internet Connection! Check your network settings."
                        !isConnected && !isServerBusy -> "Server Offline or Unreachable. Please check the API URL or server status."
                        isRestoringPrompt -> "Restoring generation details from local image..."
                        isServerBusy && !isGenerating -> "External generation in progress on the server... (${(progress * 100).toInt()}%)"
                        isGenerating -> "$status" + if (generationQueue.isNotEmpty()) "\nQueued: ${generationQueue.size} items waiting." else ""
                        generationQueue.isNotEmpty() -> "Ready - Queued: ${generationQueue.size} items waiting to be processed."
                        !isSessionActive -> "Configuring Settings... App is active in background."
                        else -> "Ready - Connected to server and waiting for prompts."
                    }

                    val max = 100
                    val progInt = if (isGenerating) (progress * 100).toInt() else if (isServerBusy && !isGenerating) (progress * 100).toInt() else 0
                    val isActivelyGenerating = isGenerating || isServerBusy

                    val openIntent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    val openPendingIntent = android.app.PendingIntent.getActivity(context, 0, openIntent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)

                    val exitIntent = Intent("ACTION_EXIT_APP").setPackage(context.packageName)
                    val exitPendingIntent = android.app.PendingIntent.getBroadcast(context, 1, exitIntent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)

                    val settingsIntent = Intent(context, MainActivity::class.java).apply {
                        action = "ACTION_OPEN_SETTINGS"
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    val settingsPendingIntent = android.app.PendingIntent.getActivity(context, 2, settingsIntent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)

                    val builder = androidx.core.app.NotificationCompat.Builder(context, channelId)
                        .setContentTitle(notifTitle)
                        .setContentText(notifText)
                        .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(notifText))
                        .setSmallIcon(android.R.drawable.ic_menu_preferences)
                        .setOngoing(isActivelyGenerating || generationQueue.isNotEmpty())
                        .setOnlyAlertOnce(true)
                        .setContentIntent(openPendingIntent)
                        .setDeleteIntent(exitPendingIntent)
                        .addAction(android.R.drawable.ic_menu_view, "Open App", openPendingIntent)
                        .addAction(android.R.drawable.ic_menu_preferences, "Settings", settingsPendingIntent)
                        .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Exit App", exitPendingIntent)

                    if (isActivelyGenerating) {
                        builder.setProgress(max, progInt, progInt == 0)
                    } else {
                        builder.setProgress(0, 0, false)
                    }

                    manager.notify(1001, builder.build())
                }
            }

            val shouldBlur = (!isOnline || (!isConnected && !isServerBusy)) && isSessionActive

            MaterialTheme(colorScheme = finalColorScheme, typography = finalTypography, shapes = finalShapes) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (shouldBlur) Modifier.blur(15.dp) else Modifier),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        AppNavigation(
                            viewModel = viewModel,
                            navController = navController,
                            isSamsung = isSamsungDevice,
                            useSamsungTheme = useSamsungTheme,
                            onThemeChange = {
                                useSamsungTheme = it
                                prefs.edit().putBoolean("use_samsung_theme", it).apply()
                            }
                        )
                    }

                    if (shouldBlur) {
                        Box(
                            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable(enabled = false) {},
                            contentAlignment = Alignment.Center
                        ) {
                            Card(shape = MaterialTheme.shapes.large, elevation = CardDefaults.cardElevation(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    if (!isOnline) {
                                        Icon(Icons.Default.SignalWifiOff, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
                                        Spacer(Modifier.height(8.dp))
                                        Text("No Internet Connection", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                        Spacer(Modifier.height(8.dp))
                                        Text("Turn on internet connection to use app", textAlign = TextAlign.Center)
                                    } else {
                                        Icon(Icons.Default.CloudOff, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
                                        Spacer(Modifier.height(8.dp))
                                        Text("Server Not Found", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                        Spacer(Modifier.height(8.dp))
                                        Text("Make sure your API server is running.", textAlign = TextAlign.Center)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(exitReceiver)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.cancel(1001)
    }
}

@Composable
fun currentConnectivityStatus(context: Context): State<Boolean> {
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val isConnected = remember { mutableStateOf(checkConnectivity(connectivityManager)) }
    val wasOffline = remember { mutableStateOf(!isConnected.value) }

    DisposableEffect(connectivityManager) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = connectivityManager.getNetworkCapabilities(network)
                val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                if (hasInternet) {
                    if (wasOffline.value) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            android.widget.Toast.makeText(context, "Back online!", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    isConnected.value = true
                    wasOffline.value = false
                }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                if (hasInternet) {
                    if (wasOffline.value) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            android.widget.Toast.makeText(context, "Back online!", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    isConnected.value = true
                    wasOffline.value = false
                }
            }

            override fun onLost(network: Network) {
                isConnected.value = false
                wasOffline.value = true
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try { connectivityManager.registerDefaultNetworkCallback(callback) } catch (e: SecurityException) {}
        } else {
            val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
            try { connectivityManager.registerNetworkCallback(request, callback) } catch (e: SecurityException) {}
        }

        onDispose { try { connectivityManager.unregisterNetworkCallback(callback) } catch (e: Exception) {} }
    }
    return isConnected
}

fun checkConnectivity(connectivityManager: ConnectivityManager): Boolean {
    return try {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (e: SecurityException) { true }
}

@Composable
fun ExpandableSection(title: String, initiallyExpanded: Boolean = false, content: @Composable () -> Unit) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = "Toggle",
                modifier = Modifier.size(20.dp)
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                content()
            }
        }
    }
}

@Composable
fun AppNavigation(
    viewModel: ForgeViewModel,
    navController: NavHostController,
    isSamsung: Boolean,
    useSamsungTheme: Boolean,
    onThemeChange: (Boolean) -> Unit
) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        if (context is MainActivity) {
            context.navEvents.collect { route ->
                if (navController.currentDestination?.route != route) {
                    navController.navigate(route) { popUpTo(0) }
                }
            }
        }
    }

    NavHost(navController = navController, startDestination = "setup") {
        composable("setup") { SetupScreen(viewModel, navController, isSamsung, useSamsungTheme, onThemeChange) }
        composable("main") { MainScreen(viewModel, navController, isSamsung && useSamsungTheme) }
        composable("gallery") { GalleryScreen(viewModel, navController) }
        composable("terminal") { TerminalScreen(viewModel, navController) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    viewModel: ForgeViewModel,
    navController: NavHostController,
    isSamsung: Boolean,
    useSamsungTheme: Boolean,
    onThemeChange: (Boolean) -> Unit
) {
    val config by viewModel.config.collectAsState()
    val useMultiThreading by viewModel.useMultiThreading.collectAsState()

    var url by remember { mutableStateOf(config.apiUrl) }
    var path by remember { mutableStateOf(config.galleryPath) }
    var timeout by remember { mutableStateOf(config.connectionTimeout.toString()) }
    var updateServerUrl by remember { mutableStateOf(config.updateServerUrl) }

    var isDark by remember { mutableStateOf(config.isDarkMode) }
    var silentNotifications by remember { mutableStateOf(config.silentNotifications) }

    var apiTestStatus by remember { mutableStateOf<String?>(null) }
    var apiTestResults by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    var updateResult by remember { mutableStateOf<String?>(null) }
    var apkUrlToDownload by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Settings, contentDescription = "App Icon", modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Settings", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                },
                navigationIcon = {
                    if (navController.previousBackStackEntry != null) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {

            Text("Connection Parameters", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))

            OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("API URL") }, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = path, onValueChange = { path = it }, label = { Text("Gallery Server Path") }, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = timeout, onValueChange = { timeout = it }, label = { Text("Timeout (sec)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Text("App Preferences", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Checkbox(checked = isDark, onCheckedChange = { isDark = it })
                Text("Enable Dark Mode")
            }

            // Always render but disable if not Samsung
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Checkbox(checked = useSamsungTheme, onCheckedChange = onThemeChange, enabled = isSamsung)
                Column(modifier = Modifier.alpha(if (isSamsung) 1f else 0.5f)) {
                    Text("Samsung One UI Mode")
                    Text("Use native Samsung styling and shapes.", fontSize = 10.sp, color = Color.Gray)
                    if (!isSamsung) {
                        Text("Only available on Samsung devices.", fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            ExpandableSection("Advanced Settings & Updates", initiallyExpanded = false) {
                Column(modifier = Modifier.padding(horizontal = 12.dp)) {

                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        Checkbox(checked = silentNotifications, onCheckedChange = { silentNotifications = it })
                        Column {
                            Text("Silent Notifications")
                            Text("Disable sound and vibration alerts.", fontSize = 10.sp, color = Color.Gray)
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        Checkbox(checked = useMultiThreading, onCheckedChange = { viewModel.setMultiThreading(it) })
                        Column {
                            Text("Multi-threaded Processing")
                            Text("Utilize all cores. Disable to save battery.", fontSize = 10.sp, color = Color.Gray)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text("Remove Battery Restrictions", textAlign = TextAlign.Center)
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(value = updateServerUrl, onValueChange = { updateServerUrl = it }, label = { Text("Update Server URL") }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
                    Text("Current Version: $APP_VERSION", fontSize = 12.sp, color = Color.Gray)

                    if (updateResult != null) {
                        Text(
                            text = updateResult!!,
                            color = if (updateResult!!.contains("available")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                    if (apkUrlToDownload != null) {
                        Button(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(apkUrlToDownload))
                                context.startActivity(intent)
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (useSamsungTheme) MaterialTheme.colorScheme.primary else Color(0xFF4CAF50)
                            )
                        ) {
                            Text("Download New Version", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            updateResult = "Checking for updates..."
                            apkUrlToDownload = null
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).build()
                                    val cleanUrl = updateServerUrl.trimEnd('/')
                                    val req = Request.Builder().url("$cleanUrl/metadata.json").build()
                                    client.newCall(req).execute().use { res ->
                                        if (res.isSuccessful) {
                                            val responseBody = res.body?.string() ?: ""
                                            try {
                                                val json = JSONObject(responseBody)
                                                val meta = json.getJSONObject("meta")
                                                val version = meta.getString("version")
                                                val file = meta.getString("file")

                                                withContext(Dispatchers.Main) {
                                                    val serverVersionNum = version.substringBefore("-")
                                                    if (serverVersionNum == APP_VERSION) {
                                                        updateResult = "You are up-to-date"
                                                    } else {
                                                        updateResult = "New version available: $version"
                                                        apkUrlToDownload = "$cleanUrl/$file"
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                withContext(Dispatchers.Main) { updateResult = "Update server failed to fetch data" }
                                            }
                                        } else {
                                            withContext(Dispatchers.Main) { updateResult = "Update server is not found" }
                                        }
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) { updateResult = "Update server is not found" }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(48.dp)
                    ) {
                        Text("Check for Updates", textAlign = TextAlign.Center)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (apiTestStatus != null) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = apiTestStatus!!,
                            color = if (apiTestStatus!!.contains("Operational")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        apiTestResults.forEach { (endpoint, result) ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(endpoint, fontSize = 12.sp)
                                Text(result, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        apiTestStatus = "Running diagnostics..."
                        apiTestResults = emptyList()

                        scope.launch(Dispatchers.IO) {
                            val client = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).build()
                            val endpoints = listOf("sd-models", "samplers", "schedulers", "upscalers", "loras", "options")
                            val results = mutableListOf<Pair<String, String>>()
                            var allSuccess = true

                            for (ep in endpoints) {
                                val start = System.currentTimeMillis()
                                try {
                                    var cleanUrl = url.trimEnd('/')
                                    if (cleanUrl.isNotEmpty() && !cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) cleanUrl = "http://$cleanUrl"

                                    val req = Request.Builder().url("$cleanUrl/sdapi/v1/$ep").build()
                                    client.newCall(req).execute().use { res ->
                                        val time = System.currentTimeMillis() - start
                                        if (res.isSuccessful) {
                                            results.add(ep to "${time}ms \u2714")
                                        } else {
                                            results.add(ep to "Err ${res.code} \u2718")
                                            allSuccess = false
                                        }
                                    }
                                } catch (e: Exception) {
                                    results.add(ep to "Failed \u2718")
                                    allSuccess = false
                                }
                                withContext(Dispatchers.Main) { apiTestResults = results.toList() }
                            }

                            withContext(Dispatchers.Main) {
                                apiTestStatus = if (allSuccess) "All Systems Operational!" else "Some APIs Failed."
                            }
                        }
                    },
                    modifier = Modifier.weight(1f).height(50.dp)
                ) {
                    Text("Test Connection", textAlign = TextAlign.Center)
                }

                Button(
                    onClick = {
                        viewModel.saveConfig(AppConfig(url, path, isDark, timeout.toIntOrNull() ?: 10, updateServerUrl, silentNotifications))
                        navController.navigate("main") { popUpTo(0) }
                    },
                    modifier = Modifier.weight(1f).height(50.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (useSamsungTheme) MaterialTheme.colorScheme.primary else Color(0xFF4CAF50)
                    )
                ) {
                    Text("Save & Connect", textAlign = TextAlign.Center)
                }
            }
            Spacer(modifier = Modifier.height(30.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagFlowRow(text: String, onPromptChanged: (String) -> Unit) {
    val tags = text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    if (tags.isNotEmpty()) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            tags.forEachIndexed { index, tag ->
                AssistChip(
                    onClick = {},
                    label = { Text(tag, fontSize = 10.sp) },
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Remove",
                            modifier = Modifier
                                .size(16.dp)
                                .clickable {
                                    val newTags = tags.toMutableList()
                                    newTags.removeAt(index)
                                    onPromptChanged(newTags.joinToString(", "))
                                }
                        )
                    },
                    shape = MaterialTheme.shapes.small
                )
            }
        }
    } else {
        Text("No active tags", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: ForgeViewModel, navController: NavHostController, isSamsungMode: Boolean) {
    val state by viewModel.appState.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()
    val pingMs by viewModel.pingMs.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()

    val isServerBusy by ForgeState.isServerBusy.collectAsState()
    val generationQueue by ForgeState.generationQueue.collectAsState()

    val sessionImages by viewModel.sessionImages.collectAsState()
    val currentSessionIndex by viewModel.currentSessionIndex.collectAsState()

    val batchStart by ForgeState.currentBatchStartIndex.collectAsState()
    val batchEnd by ForgeState.currentBatchEndIndex.collectAsState()

    val models by viewModel.models.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val samplers by viewModel.samplers.collectAsState()
    val schedulers by viewModel.schedulers.collectAsState()
    val availableLoras by viewModel.availableLoras.collectAsState()
    val activeLoras by viewModel.activeLoras.collectAsState()

    val tagSuggestions by viewModel.tagSuggestions.collectAsState()
    val isRestoringPrompt by viewModel.isRestoringPrompt.collectAsState()

    val plugins by viewModel.plugins.collectAsState()
    val loadedPlugin by viewModel.loadedPluginName.collectAsState()

    var showPluginMenu by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var fullscreenImageIndex by remember { mutableStateOf(-1) }

    val context = LocalContext.current
    val pluginPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            viewModel.importPlugin(uri, context)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        Scaffold(
            modifier = Modifier.then(if (isRestoringPrompt) Modifier.blur(10.dp) else Modifier),
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Settings, contentDescription = "App Icon", modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("Forge Generator", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = if (isConnected) Icons.Default.Wifi else Icons.Default.WifiOff,
                                        contentDescription = null,
                                        tint = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (isConnected) "${pingMs}ms" else "Offline",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Normal
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { showPluginMenu = true }) {
                                Icon(Icons.Default.Vaccines, contentDescription = "Plugins")
                            }
                            DropdownMenu(
                                expanded = showPluginMenu,
                                onDismissRequest = { showPluginMenu = false }
                            ) {
                                if (loadedPlugin != null) {
                                    Text("Loaded: $loadedPlugin", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    DropdownMenuItem(
                                        text = { Text("Execute loaded script", fontSize = 14.sp) },
                                        onClick = {
                                            viewModel.executeLoadedPlugin()
                                            showPluginMenu = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Remove currently loaded .lua script", fontSize = 14.sp, color = MaterialTheme.colorScheme.error) },
                                        onClick = {
                                            viewModel.unloadPlugin()
                                            showPluginMenu = false
                                        }
                                    )
                                } else {
                                    Text("No script loaded", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                                }

                                HorizontalDivider()

                                DropdownMenuItem(
                                    text = { Text("Import new .lua script", fontSize = 14.sp) },
                                    onClick = {
                                        pluginPickerLauncher.launch("*/*")
                                        showPluginMenu = false
                                    }
                                )

                                if (plugins.isNotEmpty()) {
                                    HorizontalDivider()
                                    Text("Available Scripts", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontSize = 12.sp, color = Color.Gray)
                                    plugins.forEach { pluginName ->
                                        DropdownMenuItem(
                                            text = { Text("Load: $pluginName", fontSize = 14.sp) },
                                            onClick = {
                                                viewModel.loadPluginIntoMemory(pluginName)
                                                showPluginMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        Box {
                            IconButton(onClick = { showOverflowMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More Options")
                            }
                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Terminal", fontSize = 14.sp) },
                                    leadingIcon = { Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                    onClick = {
                                        showOverflowMenu = false
                                        navController.navigate("terminal")
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Gallery", fontSize = 14.sp) },
                                    leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                    onClick = {
                                        showOverflowMenu = false
                                        viewModel.fetchGalleryFolder()
                                        navController.navigate("gallery")
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Settings", fontSize = 14.sp) },
                                    leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                    onClick = {
                                        showOverflowMenu = false
                                        navController.navigate("setup")
                                    }
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                )
            }
        ) { padding ->
            Column(modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {

                if (generationQueue.isNotEmpty()) {
                    ExpandableSection("Queued: ${generationQueue.size} items waiting", initiallyExpanded = false) {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            generationQueue.forEach { item ->
                                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = MaterialTheme.shapes.medium) {
                                    Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("Prompt: ${item.positivePrompt}", fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                        IconButton(onClick = { viewModel.removeFromQueue(item.id) }, modifier = Modifier.size(24.dp)) {
                                            Icon(Icons.Default.Delete, contentDescription = "Discard", tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Box(modifier = Modifier.fillMaxWidth().height(240.dp).clip(MaterialTheme.shapes.medium).background(Color.DarkGray)) {
                    if (currentSessionIndex >= 0 && sessionImages.isNotEmpty() && currentSessionIndex < sessionImages.size) {
                        AsyncImage(
                            model = sessionImages[currentSessionIndex],
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize().clickable { fullscreenImageIndex = currentSessionIndex },
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.align(Alignment.Center)) {
                            Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color.Gray)
                            Text("No Preview", color = Color.Gray)
                        }
                    }

                    Row(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        FilledTonalButton(onClick = { viewModel.sessionPrev() }, enabled = currentSessionIndex > batchStart, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(32.dp)) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null)
                        }
                        FilledTonalButton(onClick = { viewModel.sessionNext() }, enabled = currentSessionIndex < batchEnd, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(32.dp)) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Prompts", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    TextButton(onClick = { viewModel.recoverLastPrompt() }, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(24.dp)) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Recover Last", fontSize = 12.sp)
                    }
                }

                OutlinedTextField(
                    value = state.positivePrompt,
                    onValueChange = {
                        viewModel.updateState { s -> s.copy(positivePrompt = it) }
                        val currentWord = it.substringAfterLast(",").trim()
                        viewModel.searchTags(currentWord)
                    },
                    label = { Text("Positive Prompt", fontSize = 12.sp) },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth()
                )

                AnimatedVisibility(visible = tagSuggestions.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 150.dp).padding(top = 4.dp, bottom = 4.dp),
                        elevation = CardDefaults.cardElevation(4.dp),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        LazyColumn {
                            items(tagSuggestions) { tag ->
                                Text(
                                    text = tag,
                                    fontSize = 12.sp,
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        val before = state.positivePrompt.substringBeforeLast(",", "")
                                        val newText = if (before.isEmpty()) "$tag, " else "$before, $tag, "
                                        viewModel.updateState { s -> s.copy(positivePrompt = newText) }
                                        viewModel.searchTags("")
                                    }.padding(12.dp)
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }

                ExpandableSection("Active Positive Tags", false) {
                    Box(modifier = Modifier.padding(horizontal = 12.dp)) {
                        TagFlowRow(state.positivePrompt) { newPrompt -> viewModel.updateState { s -> s.copy(positivePrompt = newPrompt) } }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = state.negativePrompt,
                    onValueChange = { viewModel.updateState { s -> s.copy(negativePrompt = it) } },
                    label = { Text("Negative Prompt", fontSize = 12.sp) },
                    minLines = 2,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )

                ExpandableSection("Active Negative Tags", false) {
                    Box(modifier = Modifier.padding(horizontal = 12.dp)) {
                        TagFlowRow(state.negativePrompt) { newPrompt -> viewModel.updateState { s -> s.copy(negativePrompt = newPrompt) } }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                ExpandableSection("Settings & LoRAs", true) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                        var modelExpanded by remember { mutableStateOf(false) }
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            OutlinedButton(onClick = { modelExpanded = true }, modifier = Modifier.fillMaxWidth().height(36.dp), contentPadding = PaddingValues(4.dp)) {
                                Text("Model: ${selectedModel.ifEmpty { "Loading..." }}", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                            }
                            DropdownMenu(expanded = modelExpanded, onDismissRequest = { modelExpanded = false }, modifier = Modifier.heightIn(max = 350.dp)) {
                                models.forEach { mod ->
                                    DropdownMenuItem(text = { Text(mod, fontSize = 12.sp) }, onClick = { viewModel.changeCheckpoint(mod); modelExpanded = false })
                                }
                            }
                        }

                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = if (state.seed == -1L) "-1" else state.seed.toString(),
                                onValueChange = {
                                    val parsed = it.toLongOrNull()
                                    if (parsed != null || it == "-" || it.isEmpty()) {
                                        viewModel.updateState { s -> s.copy(seed = parsed ?: -1L) }
                                    }
                                },
                                label = { Text("Seed (-1 for random)", fontSize = 12.sp) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { viewModel.updateState { s -> s.copy(seed = -1L) } },
                                modifier = Modifier.padding(start = 8.dp)
                            ) {
                                Icon(Icons.Default.Casino, contentDescription = "Random Seed")
                            }
                        }

                        ForgeSlider("Steps", state.steps.toFloat(), 1f..100f, 0) { viewModel.updateState { s -> s.copy(steps = it.toInt()) } }
                        ForgeSlider("CFG Scale", state.cfgScale, 1f..20f, 1) { viewModel.updateState { s -> s.copy(cfgScale = it) } }
                        ForgeSlider("Clip Skip", state.clipSkip.toFloat(), 1f..3f, 0) { viewModel.updateState { s -> s.copy(clipSkip = it.toInt()) } }
                        ForgeSlider("Width", state.width.toFloat(), 256f..2048f, 0) { viewModel.updateState { s -> s.copy(width = (it.toInt() / 64) * 64) } }
                        ForgeSlider("Height", state.height.toFloat(), 256f..2048f, 0) { viewModel.updateState { s -> s.copy(height = (it.toInt() / 64) * 64) } }
                        ForgeSlider("Batch Size", state.batchSize.toFloat(), 1f..16f, 0) { viewModel.updateState { s -> s.copy(batchSize = it.toInt()) } }

                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            var samplerExpanded by remember { mutableStateOf(false) }
                            Box(modifier = Modifier.weight(1f)) {
                                OutlinedButton(onClick = { samplerExpanded = true }, modifier = Modifier.fillMaxWidth().height(36.dp), contentPadding = PaddingValues(4.dp)) {
                                    Text(state.sampler, maxLines = 1, fontSize = 11.sp, overflow = TextOverflow.Ellipsis)
                                }
                                DropdownMenu(expanded = samplerExpanded, onDismissRequest = { samplerExpanded = false }, modifier = Modifier.heightIn(max = 350.dp)) {
                                    samplers.forEach { samp -> DropdownMenuItem(text = { Text(samp, fontSize = 12.sp) }, onClick = { viewModel.updateState { s -> s.copy(sampler = samp) }; samplerExpanded = false }) }
                                }
                            }

                            var schedulerExpanded by remember { mutableStateOf(false) }
                            Box(modifier = Modifier.weight(1f)) {
                                OutlinedButton(onClick = { schedulerExpanded = true }, modifier = Modifier.fillMaxWidth().height(36.dp), contentPadding = PaddingValues(4.dp)) {
                                    Text(state.scheduler, maxLines = 1, fontSize = 11.sp, overflow = TextOverflow.Ellipsis)
                                }
                                DropdownMenu(expanded = schedulerExpanded, onDismissRequest = { schedulerExpanded = false }, modifier = Modifier.heightIn(max = 350.dp)) {
                                    schedulers.forEach { sched -> DropdownMenuItem(text = { Text(sched, fontSize = 12.sp) }, onClick = { viewModel.updateState { s -> s.copy(scheduler = sched) }; schedulerExpanded = false }) }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text("LoRAs", fontWeight = FontWeight.Bold, fontSize = 16.sp)

                        var loraExpanded by remember { mutableStateOf(false) }
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            OutlinedButton(onClick = { loraExpanded = true }, modifier = Modifier.fillMaxWidth().height(36.dp), contentPadding = PaddingValues(4.dp)) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Add LoRA...", fontSize = 12.sp)
                            }
                            DropdownMenu(expanded = loraExpanded, onDismissRequest = { loraExpanded = false }, modifier = Modifier.heightIn(max = 350.dp)) {
                                availableLoras.forEach { loraName ->
                                    DropdownMenuItem(
                                        text = { Text(loraName, fontSize = 12.sp) },
                                        onClick = { viewModel.appendLora(loraName); loraExpanded = false }
                                    )
                                }
                            }
                        }

                        activeLoras.forEach { lora ->
                            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = MaterialTheme.shapes.medium) {
                                Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Text(lora.name, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                        IconButton(onClick = { viewModel.removeLora(lora.name) }, modifier = Modifier.size(24.dp)) {
                                            Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp))
                                        }
                                    }
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("Strength", fontSize = 10.sp)
                                        Text(String.format(java.util.Locale.US, "%.2f", lora.strength), fontSize = 10.sp)
                                    }
                                    Slider(
                                        value = lora.strength,
                                        onValueChange = { viewModel.updateLoraStrength(lora.name, it) },
                                        valueRange = 0.1f..2.0f,
                                        modifier = Modifier.height(24.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                val buttonText = when {
                    isGenerating -> "GENERATING..."
                    isServerBusy -> "SERVER BUSY - ADD TO QUEUE"
                    generationQueue.isNotEmpty() -> "ADD TO QUEUE (${generationQueue.size})"
                    else -> "GENERATE"
                }

                val buttonColor = when {
                    isGenerating -> Color.Gray
                    isServerBusy || generationQueue.isNotEmpty() -> Color(0xFFFFA000)
                    else -> if (isSamsungMode) MaterialTheme.colorScheme.primary else Color(0xFF4CAF50)
                }

                Button(
                    onClick = { viewModel.queueGeneration() },
                    enabled = isConnected && !isRestoringPrompt,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = buttonColor)
                ) {
                    Text(buttonText, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }

        if (fullscreenImageIndex >= 0 && fullscreenImageIndex < sessionImages.size) {
            val currentFile = sessionImages[fullscreenImageIndex]

            LaunchedEffect(currentFile) {
                viewModel.loadMetadataForLocalFile(currentFile)
            }

            Dialog(onDismissRequest = { fullscreenImageIndex = -1 }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {

                    Row(modifier = Modifier.fillMaxWidth().background(Color(0x88000000)).padding(vertical = 4.dp, horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { fullscreenImageIndex = -1 }) { Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White) }
                        Text(File(currentFile).name, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))

                        val showMetadata by viewModel.showGalleryMetadata.collectAsState()
                        IconButton(onClick = { viewModel.toggleGalleryMetadata() }) {
                            Icon(Icons.Default.Info, contentDescription = "Info", tint = Color.White, modifier = Modifier.then(if (showMetadata) Modifier.background(Color(0x55FFFFFF), CircleShape).padding(2.dp) else Modifier))
                        }
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {

                        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            AsyncImage(
                                model = currentFile,
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = ContentScale.Fit,
                                alignment = Alignment.TopCenter
                            )

                            val showMetadata by viewModel.showGalleryMetadata.collectAsState()
                            val currentMetadata by viewModel.currentImageMetadata.collectAsState()

                            if (showMetadata) {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    shape = MaterialTheme.shapes.large,
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E).copy(alpha = 0.9f))
                                ) {
                                    Text(
                                        text = currentMetadata ?: "Loading metadata...",
                                        color = Color.LightGray,
                                        fontSize = 11.sp,
                                        lineHeight = 14.sp,
                                        modifier = Modifier.padding(16.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(80.dp))
                        }

                        if (fullscreenImageIndex > batchStart) {
                            IconButton(
                                onClick = { fullscreenImageIndex-- },
                                modifier = Modifier.align(Alignment.CenterStart).padding(8.dp).background(Color(0x88000000), CircleShape)
                            ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous", tint = Color.White) }
                        }

                        if (fullscreenImageIndex < batchEnd && fullscreenImageIndex < sessionImages.size - 1) {
                            IconButton(
                                onClick = { fullscreenImageIndex++ },
                                modifier = Modifier.align(Alignment.CenterEnd).padding(8.dp).background(Color(0x88000000), CircleShape)
                            ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next", tint = Color.White) }
                        }
                    }
                }
            }
        }

        if (isRestoringPrompt) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable(enabled = false) {},
                contentAlignment = Alignment.Center
            ) {
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator()
                        Text("Restoring prompt...", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun ForgeSlider(name: String, value: Float, range: ClosedFloatingPointRange<Float>, decimals: Int, onValueChange: (Float) -> Unit) {
    var textValue by remember(value) { mutableStateOf(String.format(java.util.Locale.US, "%.${decimals}f", value)) }

    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(name, fontSize = 12.sp)
            BasicTextField(
                value = textValue,
                onValueChange = { input ->
                    textValue = input
                    val parsed = input.toFloatOrNull()
                    if (parsed != null && parsed in range) {
                        onValueChange(parsed)
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End
                ),
                modifier = Modifier
                    .widthIn(min = 40.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                singleLine = true
            )
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = range, modifier = Modifier.height(32.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(viewModel: ForgeViewModel, navController: NavHostController) {
    val files by viewModel.galleryFiles.collectAsState()
    val currentPath by viewModel.currentGalleryPath.collectAsState()
    val isLoading by viewModel.isGalleryLoading.collectAsState()
    val errorMsg by viewModel.galleryError.collectAsState()
    val config by viewModel.config.collectAsState()

    var selectedImage by remember { mutableStateOf<GalleryItem?>(null) }
    var isGridView by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Settings, contentDescription = "App Icon", modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Gallery", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { isGridView = !isGridView }) {
                        Icon(if (isGridView) Icons.Default.List else Icons.Default.GridView, contentDescription = "Toggle View")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {

            Row(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (currentPath.isNotEmpty() && currentPath != config.galleryPath) {
                    IconButton(onClick = {
                        val separator = if (currentPath.contains("\\")) "\\" else "/"
                        val parent = currentPath.substringBeforeLast(separator, config.galleryPath)
                        viewModel.fetchGalleryFolder(parent)
                    }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = "Up")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(currentPath.removePrefix(config.galleryPath).ifEmpty { "Root" }, maxLines = 1, modifier = Modifier.weight(1f), fontSize = 12.sp)
            }

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (errorMsg != null) {
                Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    Text("Error: $errorMsg", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, maxLines = 3)
                }
            } else if (files.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Folder is empty.", color = Color.Gray) }
            } else {
                if (isGridView) {
                    LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 100.dp), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(4.dp)) {
                        items(files) { file ->
                            Card(modifier = Modifier.padding(4.dp).aspectRatio(1f).clickable {
                                if (file.isDir) viewModel.fetchGalleryFolder(file.fullpath) else selectedImage = file
                            }, shape = MaterialTheme.shapes.medium) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    if (file.isDir) {
                                        Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(48.dp).align(Alignment.Center), tint = Color(0xFFFFC107))
                                    } else {
                                        AsyncImage(model = viewModel.getGalleryImageUrl(file), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                    }
                                    Text(file.name, color = Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0x88000000)).padding(4.dp))
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(files) { file ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    if (file.isDir) viewModel.fetchGalleryFolder(file.fullpath) else selectedImage = file
                                }.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (file.isDir) {
                                    Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(50.dp).padding(4.dp), tint = Color(0xFFFFC107))
                                } else {
                                    AsyncImage(model = viewModel.getGalleryImageUrl(file), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(50.dp).clip(MaterialTheme.shapes.small))
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(file.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (!file.isDir) Text(file.displaySize, fontSize = 12.sp, color = Color.Gray)
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    if (selectedImage != null) {
        LaunchedEffect(selectedImage) {
            viewModel.loadMetadataForImage(selectedImage)
        }

        Dialog(onDismissRequest = { selectedImage = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {

                Row(modifier = Modifier.fillMaxWidth().background(Color(0x88000000)).padding(vertical = 4.dp, horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { selectedImage = null }) { Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White) }

                    Text(selectedImage!!.name, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))

                    IconButton(onClick = {
                        viewModel.recoverPromptFromImage(selectedImage!!)
                        selectedImage = null
                        navController.popBackStack()
                    }) {
                        Icon(Icons.Default.CheckCircle, contentDescription = "Use in txt2img", tint = Color.White)
                    }

                    val showMetadata by viewModel.showGalleryMetadata.collectAsState()
                    IconButton(onClick = { viewModel.toggleGalleryMetadata() }) {
                        Icon(Icons.Default.Info, contentDescription = "Info", tint = Color.White, modifier = Modifier.then(if (showMetadata) Modifier.background(Color(0x55FFFFFF), CircleShape).padding(2.dp) else Modifier))
                    }
                    IconButton(onClick = { viewModel.downloadImage(selectedImage!!) }) {
                        Icon(Icons.Default.Save, contentDescription = "Save", tint = Color.White)
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {

                    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        AsyncImage(
                            model = viewModel.getGalleryImageUrl(selectedImage!!),
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.TopCenter
                        )

                        val showMetadata by viewModel.showGalleryMetadata.collectAsState()
                        val currentMetadata by viewModel.currentImageMetadata.collectAsState()

                        if (showMetadata) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                shape = MaterialTheme.shapes.large,
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E).copy(alpha = 0.9f))
                            ) {
                                Text(
                                    text = currentMetadata ?: "Loading metadata...",
                                    color = Color.LightGray,
                                    fontSize = 11.sp,
                                    lineHeight = 14.sp,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(80.dp))
                    }

                    val imageList = files.filter { !it.isDir }
                    val currentIndex = imageList.indexOf(selectedImage)

                    if (currentIndex > 0) {
                        IconButton(
                            onClick = { selectedImage = imageList[currentIndex - 1] },
                            modifier = Modifier.align(Alignment.CenterStart).padding(8.dp).background(Color(0x88000000), CircleShape)
                        ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous", tint = Color.White) }
                    }

                    if (currentIndex < imageList.size - 1 && currentIndex != -1) {
                        IconButton(
                            onClick = { selectedImage = imageList[currentIndex + 1] },
                            modifier = Modifier.align(Alignment.CenterEnd).padding(8.dp).background(Color(0x88000000), CircleShape)
                        ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next", tint = Color.White) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(viewModel: ForgeViewModel, navController: NavHostController) {
    val appLogs by viewModel.appLogs.collectAsState()
    val serverLogs by ForgeState.serverLogs.collectAsState()

    val appScrollState = rememberLazyListState()
    val serverScrollState = rememberLazyListState()

    // Auto-scroll to bottom when new logs arrive
    LaunchedEffect(appLogs.size) {
        if (appLogs.isNotEmpty()) appScrollState.animateScrollToItem(appLogs.size - 1)
    }

    LaunchedEffect(serverLogs.size) {
        if (serverLogs.isNotEmpty()) serverScrollState.animateScrollToItem(serverLogs.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Terminal / Console", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Upper part: Local App Logcat
            Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Text(
                    "App Logcat",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                LazyColumn(
                    state = appScrollState,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .padding(8.dp)
                ) {
                    items(appLogs) { log ->
                        Text(
                            text = log,
                            color = Color(0xFF00FF00), // Hacker Green
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 12.sp
                        )
                    }
                }
            }

            HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.primary)

            // Lower part: Server Logs
            Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Text(
                    "Server Logs",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                LazyColumn(
                    state = serverScrollState,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .padding(8.dp)
                ) {
                    items(serverLogs) { log ->
                        Text(
                            text = log,
                            color = Color(0xFF00FFFF), // Cyan for network
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 12.sp
                        )
                    }
                }
            }
        }
    }
}