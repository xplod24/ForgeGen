@file:Suppress("DEPRECATION")

package com.example.forgegen

import android.app.Activity
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coil.ImageLoader
import coil.compose.LocalImageLoader
import coil.disk.DiskCache
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

// --- GLOBAL UTILITIES & SHARED COMPONENTS ---

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun rememberDebounced(onClick: () -> Unit): () -> Unit {
    var lastClickTime by remember { mutableLongStateOf(0L) }
    return remember(onClick) {
        {
            val now = System.currentTimeMillis()
            if (now - lastClickTime >= 500L) {
                lastClickTime = now
                onClick()
            }
        }
    }
}

fun checkConnectivity(connectivityManager: ConnectivityManager): Boolean {
    return try {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (_: SecurityException) { true }
}

@Composable
fun currentConnectivityStatus(context: Context): State<Boolean> {
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val isConnected = remember { mutableStateOf(checkConnectivity(connectivityManager)) }
    val wasOffline = remember { mutableStateOf(!isConnected.value) }

    val backOnlineMessage = "Back online!"

    DisposableEffect(connectivityManager) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = connectivityManager.getNetworkCapabilities(network)
                val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                if (hasInternet) {
                    if (wasOffline.value) {
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(context, backOnlineMessage, Toast.LENGTH_SHORT).show()
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
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(context, backOnlineMessage, Toast.LENGTH_SHORT).show()
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

        try { connectivityManager.registerDefaultNetworkCallback(callback) } catch (_: SecurityException) {}

        onDispose { try { connectivityManager.unregisterNetworkCallback(callback) } catch (_: Exception) {} }
    }
    return isConnected
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

        // Idiomatyczne wyciąganie tagów LoRA za pomocą wbudowanego Kotlin Regex
        Regex("<lora:([^:]+):([0-9.]+)>").findAll(posPrompt).forEach { match ->
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
fun ForgeSlider(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    decimals: Int,
    onValueChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, modifier = Modifier.weight(1f), fontSize = 12.sp)
        Text(String.format(java.util.Locale.US, "%.${decimals}f", value), fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.weight(2f)
        )
    }
}

@Composable
fun AppNavigation(viewModel: ForgeViewModel, navController: NavHostController) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        if (context is MainActivity) {
            context.navEvents.collect { route ->
                if (navController.currentDestination?.route != route) {
                    navController.navigate(route) { popUpTo(navController.graph.startDestinationId) }
                }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = "welcome",
        enterTransition = { fadeIn(animationSpec = tween(0)) },
        exitTransition = { fadeOut(animationSpec = tween(0)) },
        popEnterTransition = { fadeIn(animationSpec = tween(0)) },
        popExitTransition = { fadeOut(animationSpec = tween(0)) }
    ) {
        composable("welcome") { WelcomeScreen(navController) }
        composable("setup") { SetupScreen(viewModel, navController) }
        composable("main") { MainScreen(viewModel, navController) }
        composable("gallery") { GalleryScreen(viewModel, navController) }
        composable("queue") { QueueScreen(viewModel, navController) }
    }
}

// --- MAIN ACTIVITY ENTRY POINT ---

class MainActivity : ComponentActivity() {
    val navEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == "ACTION_OPEN_SETTINGS") {
            navEvents.tryEmit("setup")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Natychmiastowe ukrycie systemowego splash screena by oddać renderowanie WelcomeScreen
        installSplashScreen().apply {
            setKeepOnScreenCondition { false }
        }

        super.onCreate(savedInstanceState)

        if (intent?.action == "ACTION_OPEN_SETTINGS") {
            navEvents.tryEmit("setup")
        }

        setContent {
            val context = LocalContext.current
            val viewModel: ForgeViewModel = viewModel()
            val config by viewModel.config.collectAsStateWithLifecycle()
            val activity = context.findActivity() ?: return@setContent

            DisposableEffect(context) {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        if (intent?.action == "ACTION_EXIT_APP") {
                            val manager = context?.getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
                            manager?.cancel(1001)
                            manager?.cancel(1002)

                            val svcIntent = Intent(context, GenerationService::class.java)
                            context?.stopService(svcIntent)

                            if (context is Activity) {
                                context.finishAndRemoveTask()
                            }
                            Process.killProcess(Process.myPid())
                            exitProcess(0)
                        }
                    }
                }
                val filter = IntentFilter("ACTION_EXIT_APP")
                ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
                onDispose { context.unregisterReceiver(receiver) }
            }

            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_START) viewModel.setAppForegroundState(true)
                    else if (event == Lifecycle.Event.ON_STOP) viewModel.setAppForegroundState(false)
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            // Inicjalizacja biblioteki Coil ze zdefiniowanym kluczem Cache (2.5GB + Interceptor na 30 dni)
            val imageLoader = remember(config.connectionTimeout, config.apiUrl) {
                val customClient = viewModel.client.newBuilder()
                    .addNetworkInterceptor { chain ->
                        val originalResponse = chain.proceed(chain.request())
                        // Wymuszenie na bibliotece zaufania, że plik będzie ważny przez 30 dni (2592000 sekund)
                        originalResponse.newBuilder()
                            .header("Cache-Control", "public, max-age=2592000")
                            .build()
                    }
                    .build()

                ImageLoader.Builder(context)
                    .okHttpClient { customClient }
                    .diskCache {
                        DiskCache.Builder()
                            .directory(context.cacheDir.resolve("image_cache"))
                            // Twardy limit wielkości cache dyskowego: 2.5 GB
                            .maxSizeBytes((2.5 * 1024 * 1024 * 1024).toLong())
                            .build()
                    }
                    .build()
            }

            var isUnlocked by remember { mutableStateOf(!config.useNativeSecurity) }

            if (!isUnlocked) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(64.dp), tint = Color.White)
                        Spacer(Modifier.height(16.dp))
                        Text("App Locked", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Spacer(Modifier.height(32.dp))
                        Button(onClick = {
                            val authenticators = if (config.useBiometricLock) {
                                BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
                            } else {
                                BiometricManager.Authenticators.DEVICE_CREDENTIAL
                            }
                            val prompt = BiometricPrompt.Builder(activity)
                                .setTitle("ForgeGen Security")
                                .setAllowedAuthenticators(authenticators)
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
                        }) {
                            Text("Tap to Unlock")
                        }
                    }
                }
                return@setContent
            }

            LaunchedEffect(config.keepScreenOn) {
                if (config.keepScreenOn) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }

            val isOnline by currentConnectivityStatus(this)
            val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()
            val isServerBusy by viewModel.isServerBusy.collectAsStateWithLifecycle()

            val defaultColorScheme = remember(config.isDarkMode) {
                if (config.isDarkMode) {
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
            }

            val defaultTypography = remember {
                Typography(
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
            }

            val defaultShapes = remember {
                Shapes(
                    small = RoundedCornerShape(12.dp),
                    medium = RoundedCornerShape(20.dp),
                    large = RoundedCornerShape(26.dp),
                    extraLarge = RoundedCornerShape(32.dp)
                )
            }

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
            val isSessionActive = currentRoute == "main" || currentRoute == "gallery" || currentRoute == "queue"

            val shouldBlur = (!isOnline || (!isConnected && !isServerBusy)) && isSessionActive
            val onSetupClick = rememberDebounced { navController.navigate("setup") }

            CompositionLocalProvider(LocalImageLoader provides imageLoader) {
                MaterialTheme(colorScheme = defaultColorScheme, typography = defaultTypography, shapes = defaultShapes) {
                    // Całkowicie wyczyszczony Box ze śmieci po pointerInput
                    Box(modifier = Modifier.fillMaxSize()) {
                        Surface(
                            modifier = Modifier
                                .fillMaxSize()
                                .then(if (shouldBlur) Modifier.blur(15.dp) else Modifier),
                            color = MaterialTheme.colorScheme.background
                        ) {
                            AppNavigation(viewModel = viewModel, navController = navController)
                        }

                        if (shouldBlur) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.5f))
                                    .clickable(enabled = false) {},
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
                                            onClick = onSetupClick,
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

                        val isRestoringPrompt by viewModel.isRestoringPrompt.collectAsStateWithLifecycle()
                        if (isRestoringPrompt) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.5f))
                                    .zIndex(100f)
                                    .clickable(enabled = false) {},
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text("Recovering prompt...", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}