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
import android.net.Uri
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
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coil.ImageLoader
import coil.compose.LocalImageLoader
import coil.disk.DiskCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.system.exitProcess

// --- GLOBAL UTILITIES & SHARED COMPONENTS ---

tailrec fun Context.findActivity(): Activity? =
    when (this) {
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
    } catch (_: SecurityException) {
        true
    }
}

@Composable
fun currentConnectivityStatus(context: Context): State<Boolean> {
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val isConnected = remember { mutableStateOf(checkConnectivity(connectivityManager)) }
    val wasOffline = remember { mutableStateOf(!isConnected.value) }

    val backOnlineMessage = "Back online!"

    DisposableEffect(connectivityManager) {
        val callback =
            object : ConnectivityManager.NetworkCallback() {
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

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
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

        try {
            connectivityManager.registerDefaultNetworkCallback(callback)
        } catch (_: SecurityException) {
        }

        onDispose {
            try {
                connectivityManager.unregisterNetworkCallback(callback)
            } catch (_: Exception) {
            }
        }
    }
    return isConnected
}

@Composable
fun AppNavigation(
    viewModel: ForgeViewModel,
    navController: NavHostController,
) {
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
        popExitTransition = { fadeOut(animationSpec = tween(0)) },
    ) {
        composable("welcome") { WelcomeScreen(navController) }
        composable("setup") { SetupScreen(viewModel, navController) }
        composable("main") { MainScreen(viewModel, navController) }
        composable("gallery") { GalleryScreen(viewModel, navController) }
        composable("queue") { QueueScreen(viewModel, navController) }
        composable("wildcards") { WildcardsScreen(viewModel, navController) }
        composable("presets") { PresetsScreen(viewModel, navController) }
    }
}

// --- MAIN ACTIVITY ENTRY POINT ---

class MainActivity : ComponentActivity() {
    val navEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val pendingIntents = MutableSharedFlow<Intent>(extraBufferCapacity = 1)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == "ACTION_OPEN_SETTINGS") {
            navEvents.tryEmit("setup")
        } else {
            pendingIntents.tryEmit(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Immediately dismiss the system splash screen to pass composition control to the custom WelcomeScreen.
        installSplashScreen().apply {
            setKeepOnScreenCondition { false }
        }

        super.onCreate(savedInstanceState)

        if (intent?.action == "ACTION_OPEN_SETTINGS") {
            navEvents.tryEmit("setup")
        } else if (intent != null) {
            pendingIntents.tryEmit(intent)
        }

        setContent {
            val context = LocalContext.current
            val viewModel: ForgeViewModel = viewModel()
            val config by viewModel.config.collectAsStateWithLifecycle()
            val activity = context.findActivity() ?: return@setContent

            // Capture incoming Android Share Intents containing images and extract generation parameters.
            LaunchedEffect(Unit) {
                pendingIntents.collect { receivedIntent ->
                    if (receivedIntent.action == Intent.ACTION_SEND && receivedIntent.type?.startsWith("image/") == true) {
                        val uri = receivedIntent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                        if (uri != null) {
                            launch(Dispatchers.IO) {
                                val metadata = viewModel.extractMetadataFromUri(uri)
                                withContext(Dispatchers.Main) {
                                    if (metadata != null) {
                                        viewModel.setImportedImageMetadata(metadata)
                                    } else {
                                        viewModel.showToast("No generation data found in this image.")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            DisposableEffect(context) {
                val receiver =
                    object : BroadcastReceiver() {
                        override fun onReceive(
                            context: Context?,
                            intent: Intent?,
                        ) {
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

            var isUnlocked by remember { mutableStateOf(!config.useNativeSecurity) }

            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer =
                    LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_START) {
                            viewModel.setAppForegroundState(true)
                        } else if (event == Lifecycle.Event.ON_STOP) {
                            viewModel.setAppForegroundState(false)
                            if (config.useNativeSecurity) {
                                isUnlocked = false
                            }
                        }
                    }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            // Initialize the Coil image loader configuration using a custom HTTP Client to force a 30-day cache (2.5GB maximum size) for loaded network thumbnails.
            val imageLoader =
                remember(context) {
                    val customClient =
                        viewModel.client
                            .newBuilder()
                            .addNetworkInterceptor { chain ->
                                val originalResponse = chain.proceed(chain.request())
                                originalResponse
                                    .newBuilder()
                                    .header("Cache-Control", "public, max-age=2592000")
                                    .build()
                            }.build()

                    ImageLoader
                        .Builder(context)
                        .okHttpClient { customClient }
                        .diskCache {
                            DiskCache
                                .Builder()
                                .directory(context.cacheDir.resolve("image_cache"))
                                .maxSizeBytes((2.5 * 1024 * 1024 * 1024).toLong())
                                .build()
                        }.build()
                }

            if (!isUnlocked) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(64.dp), tint = Color.White)
                        Spacer(Modifier.height(16.dp))
                        Text("App Locked", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Spacer(Modifier.height(32.dp))
                        Button(onClick = {
                            val authenticators =
                                if (config.useBiometricLock) {
                                    BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
                                } else {
                                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
                                }
                            val prompt =
                                BiometricPrompt
                                    .Builder(activity)
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
                                },
                            )
                        }) {
                            Text("Tap to unlock")
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
            val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()
            val isServerBusy by viewModel.isServerBusy.collectAsStateWithLifecycle()

            val defaultColorScheme =
                remember(config.isDarkMode) {
                    if (config.isDarkMode) {
                        darkColorScheme(
                            primary = Color(0xFF3E80FF),
                            background = Color.Black,
                            surface = Color(0xFF151515),
                            surfaceVariant = Color(0xFF252525),
                            primaryContainer = Color.Black,
                            onPrimaryContainer = Color.White,
                        )
                    } else {
                        lightColorScheme(
                            primary = Color(0xFF005BFF),
                            background = Color(0xFFF2F2F2),
                            surface = Color.White,
                            surfaceVariant = Color(0xFFE5E5E5),
                            primaryContainer = Color(0xFFF2F2F2),
                            onPrimaryContainer = Color.Black,
                        )
                    }
                }

            val defaultTypography =
                remember {
                    Typography(
                        bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp),
                        bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp),
                        bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp),
                        labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                        labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                        labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 10.sp, fontWeight = FontWeight.Medium),
                        titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 24.sp, fontWeight = FontWeight.Bold),
                        titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 18.sp, fontWeight = FontWeight.Bold),
                        titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                    )
                }

            val defaultShapes =
                remember {
                    Shapes(
                        small = RoundedCornerShape(12.dp),
                        medium = RoundedCornerShape(20.dp),
                        large = RoundedCornerShape(26.dp),
                        extraLarge = RoundedCornerShape(32.dp),
                    )
                }

            if (Build.VERSION.SDK_INT >= 33) {
                val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
                LaunchedEffect(Unit) {
                    if (ContextCompat.checkSelfPermission(this@MainActivity, "android.permission.POST_NOTIFICATIONS") !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        permissionLauncher.launch("android.permission.POST_NOTIFICATIONS")
                    }
                }
            }

            val navController = rememberNavController()
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = navBackStackEntry?.destination?.route
            val isSessionActive =
                currentRoute == "main" || currentRoute == "gallery" || currentRoute == "queue" || currentRoute == "wildcards"

            // CIVITAI SYNC STATES
            val isCivitaiSyncing by viewModel.isCivitaiSyncing.collectAsStateWithLifecycle()
            val civitaiSyncCurrentModel by viewModel.civitaiSyncCurrentModel.collectAsStateWithLifecycle()
            val civitaiSyncProgress by viewModel.civitaiSyncProgress.collectAsStateWithLifecycle()
            val civitaiSyncLastResult by viewModel.civitaiSyncLastResult.collectAsStateWithLifecycle()

            val shouldBlur = (!isOnline || (!isConnected && !isServerBusy)) && isSessionActive
            val onSetupClick = rememberDebounced { navController.navigate("setup") }

            // GLOBAL UPDATER STATES
            val updateManifest by viewModel.updateManifest.collectAsStateWithLifecycle()
            val isUpdateDownloading by viewModel.isUpdateDownloading.collectAsStateWithLifecycle()
            val updateDownloadProgress by viewModel.updateDownloadProgress.collectAsStateWithLifecycle()
            val updateDownloadStats by viewModel.updateDownloadStats.collectAsStateWithLifecycle()
            val isAppBlurred by viewModel.isAppBlurred.collectAsStateWithLifecycle()

            // Global Toast event bus
            LaunchedEffect(Unit) {
                viewModel.toastMessage.collect { message ->
                    android.widget.Toast
                        .makeText(context, message, android.widget.Toast.LENGTH_LONG)
                        .show()
                }
            }

            CompositionLocalProvider(LocalImageLoader provides imageLoader) {
                MaterialTheme(colorScheme = defaultColorScheme, typography = defaultTypography, shapes = defaultShapes) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Surface(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .then(if (shouldBlur || isCivitaiSyncing != IndicatorState.IDLE) Modifier.blur(15.dp) else Modifier),
                            color = MaterialTheme.colorScheme.background,
                        ) {
                            AppNavigation(viewModel = viewModel, navController = navController)
                        }

                        if (shouldBlur) {
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.5f))
                                        .clickable(enabled = false) {},
                                contentAlignment = Alignment.Center,
                            ) {
                                Card(
                                    shape = MaterialTheme.shapes.large,
                                    elevation = CardDefaults.cardElevation(8.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                ) {
                                    Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        if (!isOnline) {
                                            Icon(
                                                Icons.Default.SignalWifiOff,
                                                contentDescription = null,
                                                modifier = Modifier.size(48.dp),
                                                tint = MaterialTheme.colorScheme.error,
                                            )
                                            Spacer(Modifier.height(8.dp))
                                            Text("No Internet Connection", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                            Spacer(Modifier.height(8.dp))
                                            Text("Turn on the internet to use the app", textAlign = TextAlign.Center)
                                        } else {
                                            Icon(
                                                Icons.Default.CloudOff,
                                                contentDescription = null,
                                                modifier = Modifier.size(48.dp),
                                                tint = MaterialTheme.colorScheme.error,
                                            )
                                            Spacer(Modifier.height(8.dp))
                                            Text("Server Not Found", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                            Spacer(Modifier.height(8.dp))
                                            Text("Make sure the API server is running.", textAlign = TextAlign.Center)
                                        }

                                        Spacer(modifier = Modifier.height(16.dp))

                                        Button(
                                            onClick = onSetupClick,
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
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
                        var showPromptCancel by remember { mutableStateOf(false) }

                        LaunchedEffect(isRestoringPrompt) {
                            if (isRestoringPrompt == IndicatorState.LOADING) {
                                showPromptCancel = false
                                kotlinx.coroutines.delay(3000)
                                showPromptCancel = true
                            } else {
                                showPromptCancel = false
                            }
                        }

                        if (isRestoringPrompt != IndicatorState.IDLE) {
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.5f))
                                        .zIndex(100f)
                                        .clickable(enabled = false) {},
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    AnimatedStatusIndicator(state = isRestoringPrompt)
                                    Spacer(modifier = Modifier.height(16.dp))

                                    val statusText =
                                        when (isRestoringPrompt) {
                                            IndicatorState.SUCCESS -> "Successfully recovered!"
                                            IndicatorState.ERROR -> "Failed to recover."
                                            else -> "Recovering prompt..."
                                        }

                                    Text(statusText, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)

                                    if (showPromptCancel) {
                                        Spacer(modifier = Modifier.height(16.dp))
                                        OutlinedButton(onClick = { viewModel.cancelPromptRestore() }) {
                                            Text("Cancel", color = Color.White)
                                        }
                                    }
                                }
                            }
                        }

                        // GLOBAL CIVITAI SYNCHRONIZATION OVERLAY DIALOG
                        var showCivitaiCancel by remember { mutableStateOf(false) }

                        LaunchedEffect(isCivitaiSyncing) {
                            if (isCivitaiSyncing == IndicatorState.LOADING) {
                                showCivitaiCancel = false
                                kotlinx.coroutines.delay(3000)
                                showCivitaiCancel = true
                            } else {
                                showCivitaiCancel = false
                            }
                        }

                        if (isCivitaiSyncing != IndicatorState.IDLE) {
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.7f))
                                        .zIndex(150f)
                                        .clickable(enabled = false) {},
                                contentAlignment = Alignment.Center,
                            ) {
                                Card(
                                    modifier =
                                        Modifier
                                            .padding(
                                                32.dp,
                                            ).fillMaxWidth(0.85f)
                                            .animateContentSize(animationSpec = tween(200, easing = FastOutSlowInEasing)),
                                    shape = MaterialTheme.shapes.large,
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        AnimatedStatusIndicator(state = isCivitaiSyncing)
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Text("Civitai Sync", fontWeight = FontWeight.Bold, fontSize = 18.sp, textAlign = TextAlign.Center)
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text("Fetching metadata for:", fontSize = 12.sp, color = Color.Gray)
                                        Text(
                                            text = civitaiSyncCurrentModel.ifEmpty { "..." },
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            textAlign = TextAlign.Center,
                                            maxLines = 2,
                                        )
                                        Spacer(modifier = Modifier.height(16.dp))

                                        val (current, total) = civitaiSyncProgress
                                        LinearProgressIndicator(
                                            progress = { if (total > 0) current.toFloat() / total.toFloat() else 0f },
                                            modifier = Modifier.fillMaxWidth().height(6.dp),
                                            color = MaterialTheme.colorScheme.primary,
                                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text("Model $current of $total", fontSize = 12.sp)

                                        if (civitaiSyncLastResult != null) {
                                            Spacer(modifier = Modifier.height(12.dp))
                                            val isError =
                                                civitaiSyncLastResult!!.contains("Błąd", ignoreCase = true) ||
                                                    civitaiSyncLastResult!!.contains("Error", ignoreCase = true)
                                            Text(
                                                text = "Last result: $civitaiSyncLastResult",
                                                fontSize = 11.sp,
                                                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text("API limits applied to prevent IP ban.", fontSize = 10.sp, color = Color.Gray)

                                        if (showCivitaiCancel) {
                                            Spacer(modifier = Modifier.height(16.dp))
                                            OutlinedButton(onClick = { viewModel.cancelCivitaiSync() }) {
                                                Text("Cancel")
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // GLOBAL DOWNLOAD PROGRESS DIALOG
                        if (isUpdateDownloading) {
                            AlertDialog(
                                onDismissRequest = { },
                                properties =
                                    androidx.compose.ui.window.DialogProperties(
                                        dismissOnBackPress = false,
                                        dismissOnClickOutside = false,
                                    ),
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

                        // GLOBAL ALERTIMPORT DIALOG FOR INCOMING SHARED IMAGES
                        val importedImageMetadata by viewModel.importedImageMetadata.collectAsStateWithLifecycle()
                        if (importedImageMetadata != null) {
                            AppMetadataAlertDialog(
                                metadata = importedImageMetadata,
                                onDismiss = { viewModel.setImportedImageMetadata(null) },
                                onApplyPrompt = { pos, neg ->
                                    viewModel.updateState { it.copy(positivePrompt = pos, negativePrompt = neg) }
                                    viewModel.setImportedImageMetadata(null)
                                    viewModel.showToast("Applied Prompts")
                                },
                                onApplyModel = { modelName ->
                                    viewModel.changeCheckpoint(modelName)
                                    viewModel.setImportedImageMetadata(null)
                                    viewModel.showToast("Applied Model: $modelName")
                                },
                                onApplyLoras = { loras ->
                                    loras.forEach { loraTag ->
                                        val loraName = loraTag.substringAfter("<lora:").substringBefore(":")
                                        if (loraName.isNotEmpty()) viewModel.appendLora(loraName)
                                    }
                                    viewModel.setImportedImageMetadata(null)
                                    viewModel.showToast("Applied LoRAs")
                                },
                            )
                        }

                        // GLOBAL APP BLUR PRIVACY OVERLAY
                        if (isAppBlurred) {
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.9f))
                                        .blur(25.dp)
                                        .clickable(enabled = true) {
                                            if (config.useBiometricLock || config.useNativeSecurity) {
                                                val authenticators =
                                                    if (config.useBiometricLock) {
                                                        android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                                                            android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
                                                    } else {
                                                        android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
                                                    }
                                                val prompt =
                                                    android.hardware.biometrics.BiometricPrompt
                                                        .Builder(activity)
                                                        .setTitle("ForgeGen Authentication")
                                                        .setAllowedAuthenticators(authenticators)
                                                        .build()
                                                prompt.authenticate(
                                                    android.os.CancellationSignal(),
                                                    activity.mainExecutor,
                                                    object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                                                        override fun onAuthenticationSucceeded(
                                                            result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult?,
                                                        ) {
                                                            viewModel.setAppBlurred(false)
                                                        }

                                                        override fun onAuthenticationError(
                                                            errorCode: Int,
                                                            errString: CharSequence?,
                                                        ) {
                                                            activity.finishAffinity()
                                                            java.lang.System.exit(0)
                                                        }

                                                        override fun onAuthenticationFailed() {
                                                            activity.finishAffinity()
                                                            java.lang.System.exit(0)
                                                        }
                                                    },
                                                )
                                            } else {
                                                viewModel.setAppBlurred(false)
                                            }
                                        },
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.VisibilityOff,
                                        contentDescription = null,
                                        modifier = Modifier.size(64.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Text("App Blurred / Hidden", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                    Text(
                                        "Tap anywhere to unlock/reveal",
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
