package com.yourname.forgegen

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.hardware.biometrics.BiometricPrompt
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.util.Base64
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
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
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.system.exitProcess

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
            val appState by viewModel.appState.collectAsState()
            val activity = LocalContext.current as Activity

            var isUnlocked by remember { mutableStateOf(!config.useBiometricLock) }

            if (!isUnlocked) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(64.dp), tint = Color.White)
                        Spacer(Modifier.height(16.dp))
                        Text("App Locked", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Spacer(Modifier.height(32.dp))
                        Button(onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                val prompt = BiometricPrompt.Builder(activity)
                                    .setTitle("ForgeGen is Locked")
                                    .setNegativeButton("Cancel", activity.mainExecutor) { _, _ -> }
                                    .build()
                                prompt.authenticate(
                                    CancellationSignal(),
                                    activity.mainExecutor,
                                    object : BiometricPrompt.AuthenticationCallback() {
                                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                                            isUnlocked = true
                                        }
                                    }
                                )
                            } else {
                                isUnlocked = true
                            }
                        }) {
                            Text("Tap to Unlock")
                        }
                    }
                }
                return@setContent
            }

            LaunchedEffect(config.keepScreenOn) {
                if (config.keepScreenOn) {
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            val isOnline by currentConnectivityStatus(this)
            val isConnected by viewModel.isConnected.collectAsState()

            val isServerBusy by ForgeState.isServerBusy.collectAsState()
            val generationQueue by ForgeState.generationQueue.collectAsState()

            val isGenerating by viewModel.isGenerating.collectAsState()
            val progress by viewModel.progress.collectAsState()
            val status by viewModel.statusText.collectAsState()
            val isRestoringPrompt by viewModel.isRestoringPrompt.collectAsState()
            val currentEta by ForgeState.currentEta.collectAsState()

            val context = LocalContext.current
            val prefs = remember { context.getSharedPreferences("ForgeGenPrefs", Context.MODE_PRIVATE) }

            val isSamsungDevice = remember { Build.MANUFACTURER.equals("samsung", ignoreCase = true) }
            var useSamsungTheme by remember { mutableStateOf(prefs.getBoolean("use_samsung_theme", isSamsungDevice)) }

            val useDynamicColor = config.useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

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

            val finalColorScheme = when {
                useDynamicColor -> if (config.isDarkMode) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                isSamsungDevice && useSamsungTheme -> samsungColorScheme
                else -> aospColorScheme
            }

            val finalTypography = if (isSamsungDevice && useSamsungTheme && !useDynamicColor) samsungTypography else appTypography
            val finalShapes = if (isSamsungDevice && useSamsungTheme && !useDynamicColor) samsungShapes else MaterialTheme.shapes

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

                                    Spacer(modifier = Modifier.height(16.dp))

                                    Button(
                                        onClick = { navController.navigate("setup") },
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                    ) {
                                        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Open Settings")
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
fun ExpandableSection(title: String, prefKey: String, initiallyExpanded: Boolean = false, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ForgeGenPrefs", Context.MODE_PRIVATE) }
    var expanded by remember { mutableStateOf(prefs.getBoolean("expandable_$prefKey", initiallyExpanded)) }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable {
                    expanded = !expanded
                    prefs.edit().putBoolean("expandable_$prefKey", expanded).apply()
                }
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
fun UndoRedoTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
    onClear: () -> Unit
) {
    var history by remember { mutableStateOf(listOf(value)) }
    var historyIndex by remember { mutableStateOf(0) }

    LaunchedEffect(value) {
        if (history.isEmpty() || history[historyIndex] != value) {
            val newHistory = history.take(historyIndex + 1) + value
            history = newHistory
            historyIndex = newHistory.size - 1
        }
    }

    Column(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(
                onClick = {
                    if (historyIndex > 0) {
                        historyIndex--
                        onValueChange(history[historyIndex])
                    }
                },
                enabled = historyIndex > 0,
                modifier = Modifier.size(32.dp)
            ) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo", Modifier.size(18.dp)) }

            IconButton(
                onClick = {
                    if (historyIndex < history.size - 1) {
                        historyIndex++
                        onValueChange(history[historyIndex])
                    }
                },
                enabled = historyIndex < history.size - 1,
                modifier = Modifier.size(32.dp)
            ) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo", Modifier.size(18.dp)) }
        }

        OutlinedTextField(
            value = value,
            onValueChange = {
                if (it != value) {
                    val newHistory = history.take(historyIndex + 1) + it
                    history = newHistory
                    historyIndex = newHistory.size - 1
                    onValueChange(it)
                }
            },
            label = label,
            minLines = minLines,
            maxLines = maxLines,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                if (value.isNotEmpty()) {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Default.Close, contentDescription = "Clear")
                    }
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MetadataAlertDialog(
    metadata: String?,
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

        val loraRegex = Regex("<lora:([^:]+):([0-9.]+)>")
        loraRegex.findAll(posPrompt).forEach { match ->
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
                Text("Generation Data", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(bottom = 8.dp))

                Box(modifier = Modifier.weight(1f, fill = false).background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small).padding(8.dp)) {
                    Text(
                        text = metadata ?: "Loading...",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
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

    NavHost(navController = navController, startDestination = "main") {
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

    var url by remember(config.apiUrl) { mutableStateOf(config.apiUrl) }
    var path by remember(config.galleryPath) { mutableStateOf(config.galleryPath) }
    var username by remember(config.serverUsername) { mutableStateOf(config.serverUsername) }
    var password by remember(config.serverPassword) { mutableStateOf(config.serverPassword) }
    var timeout by remember(config.connectionTimeout) { mutableStateOf(config.connectionTimeout.toString()) }

    var apiTestStatus by remember { mutableStateOf<String?>(null) }
    var apiTestResults by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var newProfileName by remember { mutableStateOf("") }
    var showAddProfileDialog by remember { mutableStateOf(false) }
    var profileExpanded by remember { mutableStateOf(false) }

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
                    IconButton(onClick = {
                        if (navController.previousBackStackEntry != null) {
                            navController.popBackStack()
                        } else {
                            navController.navigate("main") { popUpTo(0) }
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {

            ExpandableSection("Connection & Server Profiles", "setup_connection", true) {
                Column(modifier = Modifier.padding(horizontal = 8.dp)) {
                    Box(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        OutlinedButton(
                            onClick = { profileExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text("Saved Profiles \u25BC", color = MaterialTheme.colorScheme.onSurface)
                        }
                        DropdownMenu(
                            expanded = profileExpanded,
                            onDismissRequest = { profileExpanded = false },
                            modifier = Modifier.fillMaxWidth(0.9f)
                        ) {
                            config.serverProfiles.forEach { profile ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(profile.name, fontWeight = FontWeight.Bold)
                                            Text(profile.url, fontSize = 10.sp, color = Color.Gray)
                                        }
                                    },
                                    onClick = {
                                        url = profile.url
                                        profileExpanded = false
                                    },
                                    trailingIcon = {
                                        IconButton(onClick = { viewModel.removeServerProfile(profile.name) }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete Profile", tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("API URL") },
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(onClick = { showAddProfileDialog = true }) {
                                Icon(Icons.Default.Save, contentDescription = "Save Profile")
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Gradio Username (Optional)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Gradio Password (Optional)") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = path, onValueChange = { path = it }, label = { Text("Gallery Server Path") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = timeout, onValueChange = { timeout = it }, label = { Text("Timeout (sec)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                }
            }

            ExpandableSection("Appearance & UI", "setup_appearance", false) {
                Column(modifier = Modifier.padding(horizontal = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        Checkbox(checked = config.isDarkMode, onCheckedChange = { viewModel.saveConfig(config.copy(isDarkMode = it)) })
                        Text("Enable Dark Mode")
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                            Checkbox(checked = config.useDynamicColor, onCheckedChange = { viewModel.saveConfig(config.copy(useDynamicColor = it)) })
                            Column {
                                Text("Use Material You (Dynamic Color)")
                                Text("Extracts app theme directly from wallpaper.", fontSize = 10.sp, color = Color.Gray)
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        Checkbox(checked = config.keepScreenOn, onCheckedChange = { viewModel.saveConfig(config.copy(keepScreenOn = it)) })
                        Column {
                            Text("Keep Screen On")
                            Text("Prevents phone sleep while rendering.", fontSize = 10.sp, color = Color.Gray)
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        Checkbox(checked = useSamsungTheme, onCheckedChange = onThemeChange, enabled = isSamsung && !config.useDynamicColor)
                        Column(modifier = Modifier.alpha(if (isSamsung && !config.useDynamicColor) 1f else 0.5f)) {
                            Text("Samsung One UI Mode")
                            Text("Use native Samsung styling and shapes.", fontSize = 10.sp, color = Color.Gray)
                            if (!isSamsung) Text("Only available on Samsung devices.", fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
                            if (config.useDynamicColor) Text("Disabled when Material You is ON.", fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            ExpandableSection("Gallery & Media", "setup_gallery", false) {
                Column(modifier = Modifier.padding(horizontal = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        Checkbox(checked = config.swipeToBrowseGallery, onCheckedChange = { viewModel.saveConfig(config.copy(swipeToBrowseGallery = it)) })
                        Column {
                            Text("Swipe to Browse Images")
                            Text("Use horizontal swiping in fullscreen preview.", fontSize = 10.sp, color = Color.Gray)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        Checkbox(checked = config.livePreviews, onCheckedChange = { viewModel.saveConfig(config.copy(livePreviews = it)) })
                        Column {
                            Text("Live Step-by-Step Previews")
                            Text("Show a live blurry image stream while generating.", fontSize = 10.sp, color = Color.Gray)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        Checkbox(checked = config.showGridAfterGeneration, onCheckedChange = { viewModel.saveConfig(config.copy(showGridAfterGeneration = it)) })
                        Column {
                            Text("Show Grid After Batch")
                            Text("Temporarily show a grid of images when a batch generation finishes.", fontSize = 10.sp, color = Color.Gray)
                        }
                    }
                }
            }

            ExpandableSection("Advanced & System", "setup_advanced", false) {
                Column(modifier = Modifier.padding(horizontal = 8.dp)) {

                    val indexerStatus by ForgeState.indexerStatus.collectAsState()
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Background Prompt Indexer", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(indexerStatus, fontSize = 10.sp, color = if (indexerStatus.contains("Error")) MaterialTheme.colorScheme.error else Color.Gray)
                        }
                        Button(onClick = { viewModel.startIndexer() }, enabled = !indexerStatus.contains("Indexing")) {
                            Text("Sync")
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        Checkbox(checked = config.overnightMode, onCheckedChange = { viewModel.saveConfig(config.copy(overnightMode = it)) })
                        Column {
                            Text("Overnight Batch Mode")
                            Text("Ignores minor errors to keep batch running.", fontSize = 10.sp, color = Color.Gray)
                        }
                    }

                    Text("Notification Detail Level", fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Full", "Brief", "Simple").forEach { level ->
                            Button(
                                onClick = { viewModel.saveConfig(config.copy(notificationVerbosity = level)) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (config.notificationVerbosity == level) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = if (config.notificationVerbosity == level) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                modifier = Modifier.weight(1f).height(36.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text(level, fontSize = 12.sp)
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        Checkbox(checked = config.useBiometricLock, onCheckedChange = { viewModel.saveConfig(config.copy(useBiometricLock = it)) })
                        Column {
                            Text("Biometric App Lock")
                            Text("Require fingerprint or face scan on launch.", fontSize = 10.sp, color = Color.Gray)
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        Checkbox(checked = config.silentNotifications, onCheckedChange = { viewModel.saveConfig(config.copy(silentNotifications = it)) })
                        Column {
                            Text("Silent Notifications")
                            Text("Disable sound and vibration alerts.", fontSize = 10.sp, color = Color.Gray)
                        }
                    }

                    val useMultiThreading by viewModel.useMultiThreading.collectAsState()
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
                            val testClientBuilder = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS)
                            testClientBuilder.addInterceptor { chain ->
                                val reqBuilder = chain.request().newBuilder()
                                if (username.isNotEmpty() && password.isNotEmpty()) {
                                    val creds = "$username:$password"
                                    val basic = "Basic " + Base64.encodeToString(creds.toByteArray(), Base64.NO_WRAP)
                                    reqBuilder.header("Authorization", basic)
                                }
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
                                    var cleanUrl = url.trimEnd('/')
                                    if (cleanUrl.isNotEmpty() && !cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) cleanUrl = "http://$cleanUrl"

                                    val req = Request.Builder().url("$cleanUrl/sdapi/v1/$ep").build()
                                    testClient.newCall(req).execute().use { res ->
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
                        val updateObj = config.copy(
                            apiUrl = url,
                            galleryPath = path,
                            serverUsername = username,
                            serverPassword = password,
                            connectionTimeout = timeout.toIntOrNull() ?: 10
                        )
                        viewModel.saveConfig(updateObj)
                        Toast.makeText(context, "Settings saved successfully", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f).height(50.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (useSamsungTheme && !config.useDynamicColor) MaterialTheme.colorScheme.primary else Color(0xFF4CAF50)
                    )
                ) {
                    Text("Save Settings", textAlign = TextAlign.Center)
                }
            }
            Spacer(modifier = Modifier.height(30.dp))

            if (showAddProfileDialog) {
                AlertDialog(
                    onDismissRequest = { showAddProfileDialog = false },
                    title = { Text("Save Server Profile") },
                    text = {
                        OutlinedTextField(
                            value = newProfileName,
                            onValueChange = { newProfileName = it },
                            label = { Text("Profile Name (e.g. Local PC)") }
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            if (newProfileName.isNotBlank() && url.isNotBlank()) {
                                viewModel.addServerProfile(newProfileName, url)
                            }
                            showAddProfileDialog = false
                            newProfileName = ""
                        }) { Text("Save") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showAddProfileDialog = false }) { Text("Cancel") }
                    }
                )
            }
        }
    }
}

@Composable
fun DragToReorderTagsRow(text: String, onPromptChanged: (String) -> Unit) {
    val tags = text.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    if (tags.isNotEmpty()) {
        var draggingIndex by remember { mutableStateOf<Int?>(null) }
        var dragOffset by remember { mutableStateOf(0f) }

        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(tags) { index, tag ->
                val isDragging = index == draggingIndex
                val modifier = if (isDragging) {
                    Modifier.offset { IntOffset(dragOffset.roundToInt(), 0) }.zIndex(1f)
                } else {
                    Modifier.zIndex(0f)
                }

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
                    shape = MaterialTheme.shapes.small,
                    modifier = modifier.pointerInput(Unit) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { draggingIndex = index },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragOffset += dragAmount.x

                                val swapThreshold = 150f // pixels to trigger swap
                                if (dragOffset > swapThreshold && index < tags.size - 1) {
                                    val newTags = tags.toMutableList()
                                    newTags[index] = newTags[index + 1].also { newTags[index + 1] = newTags[index] }
                                    onPromptChanged(newTags.joinToString(", "))
                                    draggingIndex = index + 1
                                    dragOffset -= swapThreshold
                                } else if (dragOffset < -swapThreshold && index > 0) {
                                    val newTags = tags.toMutableList()
                                    newTags[index] = newTags[index - 1].also { newTags[index - 1] = newTags[index] }
                                    onPromptChanged(newTags.joinToString(", "))
                                    draggingIndex = index - 1
                                    dragOffset += swapThreshold
                                }
                            },
                            onDragEnd = { draggingIndex = null; dragOffset = 0f },
                            onDragCancel = { draggingIndex = null; dragOffset = 0f }
                        )
                    }
                )
            }
        }
    } else {
        Text("No active tags", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(viewModel: ForgeViewModel, navController: NavHostController, isSamsungMode: Boolean) {
    val config by viewModel.config.collectAsState()
    val state by viewModel.appState.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()
    val pingMs by viewModel.pingMs.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val currentEta by ForgeState.currentEta.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val status by viewModel.statusText.collectAsState()

    val isServerBusy by ForgeState.isServerBusy.collectAsState()
    val generationQueue by ForgeState.generationQueue.collectAsState()

    val sessionImages by viewModel.sessionImages.collectAsState()
    val currentSessionIndex by viewModel.currentSessionIndex.collectAsState()
    val livePreviewBase64 by ForgeState.livePreviewImage.collectAsState()
    val isShowingGridPreview by ForgeState.isShowingGridPreview.collectAsState()

    val batchStart by ForgeState.currentBatchStartIndex.collectAsState()
    val batchEnd by ForgeState.currentBatchEndIndex.collectAsState()

    val models by viewModel.models.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val samplers by viewModel.samplers.collectAsState()
    val schedulers by viewModel.schedulers.collectAsState()
    val vaes by viewModel.vaes.collectAsState()
    val selectedVae by viewModel.selectedVae.collectAsState()
    val availableLoras by viewModel.availableLoras.collectAsState()
    val activeLoras by viewModel.activeLoras.collectAsState()

    val tagSuggestions by viewModel.tagSuggestions.collectAsState()
    val isRestoringPrompt by viewModel.isRestoringPrompt.collectAsState()
    val promptHistory by viewModel.promptHistory.collectAsState()

    var showOverflowMenu by remember { mutableStateOf(false) }
    var showHistoryDialog by remember { mutableStateOf(false) }
    var showRecoverMenu by remember { mutableStateOf(false) }
    var fullscreenImageIndex by remember { mutableStateOf(-1) }

    val context = LocalContext.current

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
            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                val scrollState = rememberScrollState()

                Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(8.dp)) {

                    val oomAlert by ForgeState.oomAlert.collectAsState()
                    AnimatedVisibility(visible = oomAlert) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Warning, contentDescription = "Error", tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(32.dp))
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("SERVER OUT OF MEMORY (OOM)", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                                Text("The current generation failed and the queue is paused. The failed prompt was skipped.", fontSize = 12.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onErrorContainer)
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(onClick = { viewModel.resumeQueue() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onErrorContainer, contentColor = MaterialTheme.colorScheme.errorContainer)) {
                                    Text("Resume Queue")
                                }
                            }
                        }
                    }

                    if (generationQueue.isNotEmpty()) {
                        ExpandableSection("Queued: ${generationQueue.size} items waiting", "main_queue", initiallyExpanded = false) {
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
                        if (isGenerating && !livePreviewBase64.isNullOrEmpty()) {
                            val bytes = Base64.decode(livePreviewBase64, Base64.DEFAULT)
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "Live Preview",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                            }
                        } else if (isShowingGridPreview && sessionImages.size > batchStart.toInt() && batchEnd.toInt() >= batchStart.toInt()) {
                            val bStart = batchStart.toInt()
                            val bEnd = batchEnd.toInt()
                            val batchImages = sessionImages.subList(bStart, bEnd + 1)
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(minSize = 80.dp),
                                modifier = Modifier.fillMaxSize().padding(bottom = 36.dp),
                                contentPadding = PaddingValues(4.dp)
                            ) {
                                items(batchImages.size) { index ->
                                    val imgPath = batchImages[index]
                                    AsyncImage(
                                        model = imgPath,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .padding(2.dp)
                                            .aspectRatio(1f)
                                            .clip(MaterialTheme.shapes.small)
                                            .clickable {
                                                viewModel.dismissGridPreview(bStart + index)
                                                fullscreenImageIndex = bStart + index
                                            },
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            }
                        } else if (currentSessionIndex.toInt() >= 0 && sessionImages.isNotEmpty() && currentSessionIndex.toInt() < sessionImages.size) {
                            AsyncImage(
                                model = sessionImages[currentSessionIndex.toInt()],
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize().clickable { fullscreenImageIndex = currentSessionIndex.toInt() },
                                contentScale = ContentScale.Fit
                            )
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.align(Alignment.Center)) {
                                Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color.Gray)
                                Text("No Preview", color = Color.Gray)
                            }
                        }

                        Row(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            FilledTonalButton(onClick = { viewModel.sessionPrev() }, enabled = currentSessionIndex.toInt() > batchStart.toInt(), contentPadding = PaddingValues(0.dp), modifier = Modifier.height(32.dp)) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null)
                            }
                            FilledTonalButton(onClick = { viewModel.sessionNext() }, enabled = currentSessionIndex.toInt() < batchEnd.toInt(), contentPadding = PaddingValues(0.dp), modifier = Modifier.height(32.dp)) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                            }
                        }
                    }

                    AnimatedVisibility(visible = isGenerating || isServerBusy || progress > 0f) {
                        Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                val currentStep = (progress * state.steps).toInt()
                                val modeText = if (isGenerating) "Generating..." else "External Task..."
                                Text("$modeText (Step $currentStep/${state.steps})", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                if (currentEta > 0) {
                                    Text("ETA: ${String.format(Locale.US, "%.1f", currentEta)}s", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth().height(12.dp).clip(CircleShape),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                            Text(
                                text = "${(progress * 100).toInt()}% • $status",
                                fontSize = 10.sp,
                                color = Color.Gray,
                                modifier = Modifier.padding(top = 4.dp).fillMaxWidth(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Prompts", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { viewModel.recoverLastPrompt() }, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(32.dp)) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Recover Last", fontSize = 12.sp)
                            }
                            Box {
                                IconButton(onClick = { showRecoverMenu = true }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = "More Recover Options")
                                }
                                DropdownMenu(expanded = showRecoverMenu, onDismissRequest = { showRecoverMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("From History", fontSize = 14.sp) },
                                        leadingIcon = { Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                        onClick = {
                                            showRecoverMenu = false
                                            showHistoryDialog = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("From Gallery", fontSize = 14.sp) },
                                        leadingIcon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                        onClick = {
                                            showRecoverMenu = false
                                            viewModel.fetchGalleryFolder()
                                            navController.navigate("gallery")
                                        }
                                    )
                                }
                            }
                        }
                    }

                    UndoRedoTextField(
                        value = state.positivePrompt,
                        onValueChange = {
                            viewModel.updateState { s -> s.copy(positivePrompt = it) }
                            val currentWord = it.substringAfterLast(",").trim()
                            viewModel.searchTags(currentWord)
                        },
                        label = { Text("Positive Prompt", fontSize = 12.sp) },
                        minLines = 3,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                        onClear = { viewModel.updateState { s -> s.copy(positivePrompt = "") } }
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

                    ExpandableSection("Active Positive Tags", "main_pos_tags", false) {
                        Box(modifier = Modifier.padding(horizontal = 12.dp)) {
                            DragToReorderTagsRow(state.positivePrompt) { newPrompt -> viewModel.updateState { s -> s.copy(positivePrompt = newPrompt) } }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    UndoRedoTextField(
                        value = state.negativePrompt,
                        onValueChange = { viewModel.updateState { s -> s.copy(negativePrompt = it) } },
                        label = { Text("Negative Prompt", fontSize = 12.sp) },
                        minLines = 2,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                        onClear = { viewModel.updateState { s -> s.copy(negativePrompt = "") } }
                    )

                    ExpandableSection("Active Negative Tags", "main_neg_tags", false) {
                        Box(modifier = Modifier.padding(horizontal = 12.dp)) {
                            DragToReorderTagsRow(state.negativePrompt) { newPrompt -> viewModel.updateState { s -> s.copy(negativePrompt = newPrompt) } }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    ExpandableSection("Settings & LoRAs", "main_settings", true) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp)) {

                            val isIllustrious = selectedModel.contains("illustrious", ignoreCase = true) || selectedModel.contains("ill", ignoreCase = true)

                            var modelExpanded by remember { mutableStateOf(false) }
                            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                OutlinedButton(onClick = { modelExpanded = true }, modifier = Modifier.fillMaxWidth().height(36.dp), contentPadding = PaddingValues(4.dp)) {
                                    Text("Model: ${selectedModel.ifEmpty { "Loading..." }}", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                                }
                                DropdownMenu(expanded = modelExpanded, onDismissRequest = { modelExpanded = false }, modifier = Modifier.heightIn(max = 350.dp)) {
                                    models.forEach { mod ->
                                        DropdownMenuItem(
                                            text = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    AsyncImage(
                                                        model = viewModel.getPreviewUrl(mod.path),
                                                        contentDescription = null,
                                                        modifier = Modifier.size(48.dp).padding(end = 8.dp).clip(MaterialTheme.shapes.small),
                                                        contentScale = ContentScale.Crop
                                                    )
                                                    Text(mod.title, fontSize = 12.sp)
                                                }
                                            },
                                            onClick = { viewModel.changeCheckpoint(mod.title); modelExpanded = false }
                                        )
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

                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                var vaeExpanded by remember { mutableStateOf(false) }
                                Box(modifier = Modifier.fillMaxWidth()) {
                                    OutlinedButton(onClick = { vaeExpanded = true }, enabled = !isIllustrious, modifier = Modifier.fillMaxWidth().height(36.dp), contentPadding = PaddingValues(4.dp)) {
                                        Text(if (isIllustrious) "VAE Disabled (Illustrious Detected)" else "VAE: ${selectedVae.ifEmpty { "Loading..." }}", maxLines = 1, fontSize = 11.sp, overflow = TextOverflow.Ellipsis)
                                    }
                                    DropdownMenu(expanded = vaeExpanded, onDismissRequest = { vaeExpanded = false }, modifier = Modifier.heightIn(max = 350.dp)) {
                                        vaes.forEach { vae -> DropdownMenuItem(text = { Text(vae, fontSize = 12.sp) }, onClick = { viewModel.changeVae(vae); vaeExpanded = false }) }
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
                                            text = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    AsyncImage(
                                                        model = viewModel.getPreviewUrl(loraName.path),
                                                        contentDescription = null,
                                                        modifier = Modifier.size(48.dp).padding(end = 8.dp).clip(MaterialTheme.shapes.small),
                                                        contentScale = ContentScale.Crop
                                                    )
                                                    Text(loraName.title, fontSize = 12.sp)
                                                }
                                            },
                                            onClick = { viewModel.appendLora(loraName.name); loraExpanded = false }
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

                    Spacer(modifier = Modifier.height(100.dp)) // Extra padding to allow smooth scrolling past the FAB area
                }

                // Always Floating Generate Button
                val buttonTextOverlay = when {
                    isGenerating -> "GENERATING..."
                    isServerBusy -> "SERVER BUSY - ADD TO QUEUE"
                    generationQueue.isNotEmpty() -> "ADD TO QUEUE (${generationQueue.size})"
                    else -> "GENERATE"
                }

                val buttonColorOverlay = when {
                    isGenerating -> Color.Gray
                    isServerBusy || generationQueue.isNotEmpty() -> Color(0xFFFFA000)
                    else -> if (isSamsungMode && !config.useDynamicColor) MaterialTheme.colorScheme.primary else Color(0xFF4CAF50)
                }

                Button(
                    onClick = { viewModel.queueGeneration() },
                    enabled = isConnected && !isRestoringPrompt,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp, start = 16.dp, end = 16.dp)
                        .fillMaxWidth()
                        .height(54.dp)
                        .shadow(8.dp, CircleShape),
                    colors = ButtonDefaults.buttonColors(containerColor = buttonColorOverlay)
                ) {
                    Text(buttonTextOverlay, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }

        if (showHistoryDialog) {
            AlertDialog(
                onDismissRequest = { showHistoryDialog = false },
                title = {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Prompt History")
                        IconButton(onClick = { viewModel.clearPromptHistory(); showHistoryDialog = false }) {
                            Icon(Icons.Default.Delete, contentDescription = "Clear History")
                        }
                    }
                },
                text = {
                    if (promptHistory.isEmpty()) {
                        Text("No history available yet.", color = Color.Gray)
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            items(promptHistory) { item ->
                                val timeFormat = java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault()).format(item.timestamp)
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                                        viewModel.updateState { it.copy(positivePrompt = item.positivePrompt, negativePrompt = item.negativePrompt) }
                                        showHistoryDialog = false
                                    },
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        Text(timeFormat, fontSize = 10.sp, color = Color.Gray, modifier = Modifier.align(Alignment.End))
                                        if (item.positivePrompt.isNotBlank()) Text(item.positivePrompt, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        if (item.negativePrompt.isNotBlank()) Text("Negative: " + item.negativePrompt, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 10.sp, color = Color.Gray)
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showHistoryDialog = false }) { Text("Close") }
                }
            )
        }

        if (fullscreenImageIndex >= 0 && sessionImages.isNotEmpty()) {
            val scope = rememberCoroutineScope()
            val pagerState = rememberPagerState(initialPage = fullscreenImageIndex, pageCount = { sessionImages.size })

            LaunchedEffect(pagerState.currentPage) {
                if (fullscreenImageIndex != pagerState.currentPage) {
                    fullscreenImageIndex = pagerState.currentPage
                }
                viewModel.loadMetadataForLocalFile(sessionImages[pagerState.currentPage])
            }

            Dialog(onDismissRequest = { fullscreenImageIndex = -1 }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                    val currentFile = sessionImages[fullscreenImageIndex]

                    Row(modifier = Modifier.fillMaxWidth().background(Color(0x88000000)).padding(vertical = 4.dp, horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { fullscreenImageIndex = -1 }) { Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White) }
                        Text(File(currentFile).name, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))

                        val showMetadata by viewModel.showGalleryMetadata.collectAsState()
                        IconButton(onClick = { viewModel.toggleGalleryMetadata() }) {
                            Icon(Icons.Default.Info, contentDescription = "Info", tint = Color.White, modifier = Modifier.then(if (showMetadata) Modifier.background(Color(0x55FFFFFF), CircleShape).padding(2.dp) else Modifier))
                        }
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {

                        if (config.swipeToBrowseGallery) {
                            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                                AsyncImage(
                                    model = sessionImages[page],
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxWidth(),
                                    contentScale = ContentScale.Fit,
                                    alignment = Alignment.TopCenter
                                )
                            }
                        } else {
                            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                AsyncImage(
                                    model = currentFile,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxWidth(),
                                    contentScale = ContentScale.Fit,
                                    alignment = Alignment.TopCenter
                                )
                            }
                        }

                        val showMetadata by viewModel.showGalleryMetadata.collectAsState()
                        val currentMetadata by viewModel.currentImageMetadata.collectAsState()

                        if (showMetadata) {
                            MetadataAlertDialog(
                                metadata = currentMetadata,
                                onDismiss = { viewModel.toggleGalleryMetadata() },
                                onApplyAll = {
                                    // Since we are in the Preview screen (not Gallery), we cannot use selectedImage,
                                    // But we CAN use the local file path!
                                    viewModel.loadMetadataForLocalFile(sessionImages[fullscreenImageIndex])
                                    viewModel.toggleGalleryMetadata()
                                    fullscreenImageIndex = -1
                                },
                                onApplyPrompt = { pos, neg ->
                                    viewModel.updateState { it.copy(positivePrompt = pos, negativePrompt = neg) }
                                    Toast.makeText(context, "Prompts Applied", Toast.LENGTH_SHORT).show()
                                    viewModel.toggleGalleryMetadata()
                                },
                                onApplyModel = { model ->
                                    viewModel.changeCheckpoint(model)
                                    Toast.makeText(context, "Model Applied: $model", Toast.LENGTH_SHORT).show()
                                    viewModel.toggleGalleryMetadata()
                                },
                                onApplyLoras = { loras ->
                                    var currentPos = state.positivePrompt
                                    loras.forEach { loraTag ->
                                        if (!currentPos.contains(loraTag)) {
                                            currentPos += if (currentPos.isEmpty() || currentPos.endsWith(",")) " $loraTag" else ", $loraTag"
                                        }
                                    }
                                    viewModel.updateState { it.copy(positivePrompt = currentPos) }
                                    Toast.makeText(context, "LoRAs Applied", Toast.LENGTH_SHORT).show()
                                    viewModel.toggleGalleryMetadata()
                                }
                            )
                        }

                        if (fullscreenImageIndex > batchStart) {
                            IconButton(
                                onClick = {
                                    if (config.swipeToBrowseGallery) { scope.launch { pagerState.animateScrollToPage(fullscreenImageIndex - 1) } }
                                    else { fullscreenImageIndex-- }
                                },
                                modifier = Modifier.align(Alignment.CenterStart).padding(8.dp).background(Color(0x88000000), CircleShape)
                            ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous", tint = Color.White) }
                        }

                        if (fullscreenImageIndex < batchEnd && fullscreenImageIndex < sessionImages.size - 1) {
                            IconButton(
                                onClick = {
                                    if (config.swipeToBrowseGallery) { scope.launch { pagerState.animateScrollToPage(fullscreenImageIndex + 1) } }
                                    else { fullscreenImageIndex++ }
                                },
                                modifier = Modifier.align(Alignment.CenterEnd).padding(8.dp).background(Color(0x88000000), CircleShape)
                            ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next", tint = Color.White) }
                        }
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(viewModel: ForgeViewModel, navController: NavHostController) {
    val state by viewModel.appState.collectAsState()
    val files by viewModel.galleryFiles.collectAsState()
    val currentPath by viewModel.currentGalleryPath.collectAsState()
    val isLoading by viewModel.isGalleryLoading.collectAsState()
    val errorMsg by viewModel.galleryError.collectAsState()
    val config by viewModel.config.collectAsState()

    var selectedImage by remember { mutableStateOf<GalleryItem?>(null) }
    var isGridView by remember { mutableStateOf(true) }
    var showGridSlider by remember { mutableStateOf(false) }

    // Batch Selection State
    val selectedItems = remember { mutableStateListOf<GalleryItem>() }
    val isSelectionMode = selectedItems.isNotEmpty()

    val context = LocalContext.current

    Scaffold(
        topBar = {
            Column {
                if (isSelectionMode) {
                    TopAppBar(
                        title = { Text("${selectedItems.size} Selected", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                        navigationIcon = {
                            IconButton(onClick = { selectedItems.clear() }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear Selection")
                            }
                        },
                        actions = {
                            IconButton(onClick = {
                                val allImages = files.filter { !it.isDir }
                                if (selectedItems.size == allImages.size) selectedItems.clear()
                                else { selectedItems.clear(); selectedItems.addAll(allImages) }
                            }) {
                                Icon(Icons.Default.SelectAll, contentDescription = "Select All")
                            }
                            IconButton(onClick = {
                                selectedItems.forEach { item -> viewModel.downloadImage(item) }
                                selectedItems.clear()
                            }) {
                                Icon(Icons.Default.Download, contentDescription = "Download Selected")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    )
                } else {
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
                            if (isGridView) {
                                IconButton(onClick = { showGridSlider = !showGridSlider }) {
                                    Icon(Icons.Default.ViewColumn, contentDescription = "Adjust Columns")
                                }
                            }
                        }
                    )
                }

                // Inject Dropdown Grid Slider cleanly beneath the Appbar
                AnimatedVisibility(visible = showGridSlider && isGridView && !isSelectionMode) {
                    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Columns: ${config.galleryGridColumns}", modifier = Modifier.width(90.dp), fontWeight = FontWeight.Bold)
                            Slider(
                                value = config.galleryGridColumns.toFloat(),
                                onValueChange = { viewModel.updateGalleryGridColumns(it.roundToInt()) },
                                valueRange = 1f..5f,
                                steps = 3,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {

            Row(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (currentPath.isNotEmpty() && currentPath != config.galleryPath && currentPath != "Root") {
                    IconButton(onClick = {
                        val separator = if (currentPath.contains("\\")) "\\" else "/"
                        if (currentPath.contains(separator)) {
                            val parent = currentPath.substringBeforeLast(separator)
                            viewModel.fetchGalleryFolder(parent.ifEmpty { "Root" })
                        } else {
                            viewModel.fetchGalleryFolder("Root")
                        }
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
                    LazyVerticalGrid(columns = GridCells.Fixed(config.galleryGridColumns), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(4.dp)) {
                        items(files) { file ->
                            val isSelected = selectedItems.contains(file)

                            Card(
                                modifier = Modifier.padding(4.dp).aspectRatio(1f)
                                    .then(if (isSelected) Modifier.border(4.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium) else Modifier)
                                    .combinedClickable(
                                        onClick = {
                                            if (isSelectionMode && !file.isDir) {
                                                if (isSelected) selectedItems.remove(file) else selectedItems.add(file)
                                            } else if (file.isDir) {
                                                viewModel.fetchGalleryFolder(file.fullpath)
                                            } else {
                                                selectedImage = file
                                            }
                                        },
                                        onLongClick = {
                                            if (!file.isDir) {
                                                if (isSelected) selectedItems.remove(file) else selectedItems.add(file)
                                            }
                                        }
                                    ),
                                shape = MaterialTheme.shapes.medium
                            ) {
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
                            val isSelected = selectedItems.contains(file)
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .then(if (isSelected) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha=0.2f)) else Modifier)
                                    .combinedClickable(
                                        onClick = {
                                            if (isSelectionMode && !file.isDir) {
                                                if (isSelected) selectedItems.remove(file) else selectedItems.add(file)
                                            } else if (file.isDir) {
                                                viewModel.fetchGalleryFolder(file.fullpath)
                                            } else {
                                                selectedImage = file
                                            }
                                        },
                                        onLongClick = {
                                            if (!file.isDir) {
                                                if (isSelected) selectedItems.remove(file) else selectedItems.add(file)
                                            }
                                        }
                                    ).padding(12.dp),
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
        val scope = rememberCoroutineScope()
        val imageList = files.filter { !it.isDir }
        val initialIndex = imageList.indexOf(selectedImage)
        val pagerState = rememberPagerState(initialPage = if (initialIndex >= 0) initialIndex else 0, pageCount = { imageList.size })

        LaunchedEffect(pagerState.currentPage) {
            if (imageList.isNotEmpty()) {
                selectedImage = imageList[pagerState.currentPage]
                viewModel.loadMetadataForImage(selectedImage)
            }
        }

        Dialog(onDismissRequest = { selectedImage = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {

                Row(modifier = Modifier.fillMaxWidth().background(Color(0x88000000)).padding(vertical = 4.dp, horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { selectedImage = null }) { Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White) }

                    Text(selectedImage!!.name, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))

                    IconButton(onClick = { viewModel.shareImage(selectedImage!!, context) }) {
                        Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.White)
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

                    if (config.swipeToBrowseGallery && imageList.isNotEmpty()) {
                        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                            AsyncImage(
                                model = viewModel.getGalleryImageUrl(imageList[page]),
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = ContentScale.Fit,
                                alignment = Alignment.TopCenter
                            )
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            AsyncImage(
                                model = viewModel.getGalleryImageUrl(selectedImage!!),
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = ContentScale.Fit,
                                alignment = Alignment.TopCenter
                            )
                        }
                    }

                    val showMetadata by viewModel.showGalleryMetadata.collectAsState()
                    val currentMetadata by viewModel.currentImageMetadata.collectAsState()

                    if (showMetadata) {
                        MetadataAlertDialog(
                            metadata = currentMetadata,
                            onDismiss = { viewModel.toggleGalleryMetadata() },
                            onApplyAll = {
                                // Since we are in the Gallery screen, we can use selectedImage
                                viewModel.recoverPromptFromImage(selectedImage!!)
                                viewModel.toggleGalleryMetadata()
                                selectedImage = null
                                navController.popBackStack()
                            },
                            onApplyPrompt = { pos, neg ->
                                viewModel.updateState { it.copy(positivePrompt = pos, negativePrompt = neg) }
                                Toast.makeText(context, "Prompts Applied", Toast.LENGTH_SHORT).show()
                                viewModel.toggleGalleryMetadata()
                            },
                            onApplyModel = { model ->
                                viewModel.changeCheckpoint(model)
                                Toast.makeText(context, "Model Applied: $model", Toast.LENGTH_SHORT).show()
                                viewModel.toggleGalleryMetadata()
                            },
                            onApplyLoras = { loras ->
                                var currentPos = state.positivePrompt
                                loras.forEach { loraTag ->
                                    if (!currentPos.contains(loraTag)) {
                                        currentPos += if (currentPos.isEmpty() || currentPos.endsWith(",")) " $loraTag" else ", $loraTag"
                                    }
                                }
                                viewModel.updateState { it.copy(positivePrompt = currentPos) }
                                Toast.makeText(context, "LoRAs Applied", Toast.LENGTH_SHORT).show()
                                viewModel.toggleGalleryMetadata()
                            }
                        )
                    }

                    val currentIndex = imageList.indexOf(selectedImage)

                    if (currentIndex > 0) {
                        IconButton(
                            onClick = {
                                if (config.swipeToBrowseGallery) { scope.launch { pagerState.animateScrollToPage(currentIndex - 1) } }
                                else { selectedImage = imageList[currentIndex - 1] }
                            },
                            modifier = Modifier.align(Alignment.CenterStart).padding(8.dp).background(Color(0x88000000), CircleShape)
                        ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous", tint = Color.White) }
                    }

                    if (currentIndex < imageList.size - 1 && currentIndex != -1) {
                        IconButton(
                            onClick = {
                                if (config.swipeToBrowseGallery) { scope.launch { pagerState.animateScrollToPage(currentIndex + 1) } }
                                else { selectedImage = imageList[currentIndex + 1] }
                            },
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
    val serverLogs by ForgeState.serverLogs.collectAsState()
    val serverScrollState = rememberLazyListState()

    LaunchedEffect(serverLogs.size) {
        if (serverLogs.isNotEmpty()) serverScrollState.animateScrollToItem(serverLogs.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Server Terminal", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
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