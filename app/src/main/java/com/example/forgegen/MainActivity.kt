package com.example.forgegen

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.withResumed
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.forgegen.ui.components.*
import com.example.forgegen.ui.screens.*
import coil.ImageLoader
import coil.compose.LocalImageLoader
import coil.disk.DiskCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

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
        enterTransition = { fadeIn(animationSpec = tween(200)) },
        exitTransition = { fadeOut(animationSpec = tween(200)) },
        popEnterTransition = { fadeIn(animationSpec = tween(200)) },
        popExitTransition = { fadeOut(animationSpec = tween(200)) },
    ) {
        composable("welcome") { WelcomeScreen(viewModel, navController) }
        composable("setup") { SetupScreen(viewModel, onDismiss = { navController.popBackStack() }) }
        composable("main") { MainScreen(viewModel, navController) }
        composable(
            "gallery",
            enterTransition = { androidx.compose.animation.slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(200)) },
            exitTransition = { androidx.compose.animation.slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(200)) },
            popEnterTransition = { androidx.compose.animation.slideInHorizontally(initialOffsetX = { -it }, animationSpec = tween(200)) },
            popExitTransition = { androidx.compose.animation.slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(200)) }
        ) { GalleryScreen(viewModel, navController) }
        composable("queue") { QueueScreen(viewModel, navController) }
        composable("wildcards") { WildcardsScreen(viewModel, navController) }
        composable("presets") { PresetsScreen(viewModel, navController) }
    }
}

@Composable
private fun AppLockScreen(onUnlock: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(64.dp), tint = Color.White)
            Spacer(Modifier.height(16.dp))
            Text("App Locked", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(32.dp))
            Button(onClick = onUnlock) {
                Text("Tap to unlock")
            }
        }
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
                            // GenerationService handles "Exit App" (notifications, service, process); the activity
                            // only has to leave Recents before the process ends.
                            if (intent?.action == GenerationService.ACTION_EXIT_APP) {
                                activity.finishAndRemoveTask()
                            }
                        }
                    }
                val filter = IntentFilter(GenerationService.ACTION_EXIT_APP)
                ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
                onDispose { context.unregisterReceiver(receiver) }
            }

            // Locked whenever "App Lock" is on and the app has not been unlocked since it was last left.
            val isLocked by viewModel.isLocked.collectAsStateWithLifecycle()

            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer =
                    LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_START) {
                            viewModel.setAppForegroundState(true)
                        } else if (event == Lifecycle.Event.ON_STOP) {
                            viewModel.setAppForegroundState(false)
                            // Rotating also stops the activity; that must not lock the app.
                            if (!activity.isChangingConfigurations) viewModel.lockApp()
                        }
                    }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            val requestUnlock: () -> Unit = {
                if (AppLock.isAvailable(activity)) {
                    AppLock.authenticate(activity, allowBiometrics = config.useBiometricLock, title = "Unlock ForgeGen") {
                        viewModel.markUnlocked()
                    }
                } else {
                    // The phone lock was removed, so there is nothing to check against; don't lock the user out for good.
                    viewModel.markUnlocked()
                }
            }
            // Ask right away instead of waiting for a tap: once per lock, and only while the app is on screen.
            LaunchedEffect(isLocked) {
                if (isLocked) lifecycleOwner.lifecycle.withResumed { requestUnlock() }
            }
            // With the lock on, keep the app's content out of the Recents preview (Android 13+).
            LaunchedEffect(config.useNativeSecurity) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    activity.setRecentsScreenshotEnabled(!config.useNativeSecurity)
                }
            }

            // Initialize the Coil image loader configuration using a custom HTTP Client to force a 30-day cache (2.5GB maximum size) for loaded network thumbnails.
            val imageLoader =
                remember(context) {
                    ImageLoader
                        .Builder(context)
                        // Built lazily on the first image request: before initialization viewModel.client is a
                        // bare OkHttpClient without the gallery cookie and timeouts from the settings.
                        .okHttpClient {
                            viewModel.client
                                .newBuilder()
                                .addNetworkInterceptor { chain ->
                                    val originalResponse = chain.proceed(chain.request())
                                    originalResponse
                                        .newBuilder()
                                        .header("Cache-Control", "public, max-age=2592000")
                                        .build()
                                }.build()
                        }.diskCache {
                            DiskCache
                                .Builder()
                                .directory(context.cacheDir.resolve("image_cache"))
                                .maxSizeBytes((2.5 * 1024 * 1024 * 1024).toLong())
                                .build()
                        }.build()
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
                    // The overlays below fade in and out, so the blur behind them follows instead of snapping.
                    val backgroundBlur by animateDpAsState(
                        targetValue = if (shouldBlur || isCivitaiSyncing != IndicatorState.IDLE) 15.dp else 0.dp,
                        animationSpec = tween(OVERLAY_FADE_MS),
                        label = "background_blur",
                    )
                    Box(modifier = Modifier.fillMaxSize()) {
                        Surface(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .then(if (backgroundBlur > 0.dp) Modifier.blur(backgroundBlur) else Modifier),
                            color = MaterialTheme.colorScheme.background,
                        ) {
                            AppNavigation(viewModel = viewModel, navController = navController)
                        }

                        AnimatedVisibility(
                            visible = shouldBlur,
                            enter = fadeIn(tween(OVERLAY_FADE_MS)),
                            exit = fadeOut(tween(OVERLAY_FADE_MS)),
                        ) {
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

                        val shownRestoreState = rememberLastActive(isRestoringPrompt, IndicatorState.IDLE)
                        AnimatedVisibility(
                            visible = isRestoringPrompt != IndicatorState.IDLE,
                            modifier = Modifier.zIndex(100f),
                            enter = fadeIn(tween(OVERLAY_FADE_MS)),
                            exit = fadeOut(tween(OVERLAY_FADE_MS)),
                        ) {
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.5f))
                                        .clickable(enabled = false) {},
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    AnimatedStatusIndicator(state = shownRestoreState)
                                    Spacer(modifier = Modifier.height(16.dp))

                                    val statusText =
                                        when (shownRestoreState) {
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

                        val shownCivitaiState = rememberLastActive(isCivitaiSyncing, IndicatorState.IDLE)
                        AnimatedVisibility(
                            visible = isCivitaiSyncing != IndicatorState.IDLE,
                            modifier = Modifier.zIndex(150f),
                            enter = fadeIn(tween(OVERLAY_FADE_MS)),
                            exit = fadeOut(tween(OVERLAY_FADE_MS)),
                        ) {
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.7f))
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
                                        AnimatedStatusIndicator(state = shownCivitaiState)
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
                                                    civitaiSyncLastResult!!.contains("Error", ignoreCase = true) ||
                                                    civitaiSyncLastResult!!.startsWith("Stopped")
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
                        if (isUpdateDownloading && !isLocked) {
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
                        if (importedImageMetadata != null && !isLocked) {
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

                        // WHAT'S NEW after an update: once the app is unlocked and past the start animation.
                        val whatsNew by viewModel.whatsNew.collectAsStateWithLifecycle()
                        whatsNew?.let { notes ->
                            if (!isLocked && currentRoute != null && currentRoute != "welcome") {
                                WhatsNewDialog(markdown = notes, onDismiss = { viewModel.dismissWhatsNew() })
                            }
                        }

                        // APP LOCK: laid over the app instead of replacing it. The old lock removed the whole UI, so
                        // every unlock rebuilt the app from the start screen and lost the current screen and state.
                        // Its own window also keeps it above dialogs that were open when the app was left.
                        if (isLocked) {
                            Box(modifier = Modifier.fillMaxSize().background(Color.Black))
                            Dialog(
                                onDismissRequest = { activity.moveTaskToBack(true) }, // Back leaves the app, still locked
                                properties =
                                    DialogProperties(
                                        dismissOnClickOutside = false,
                                        usePlatformDefaultWidth = false,
                                    ),
                            ) {
                                AppLockScreen(onUnlock = requestUnlock)
                            }
                        }
                    }
                }
            }
        }
    }
}
