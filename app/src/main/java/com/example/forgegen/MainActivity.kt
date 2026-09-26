package com.example.forgegen

import android.app.Activity
import android.app.UiModeManager
import android.os.SystemClock
import android.view.animation.AccelerateInterpolator
import androidx.activity.viewModels
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.res.painterResource
import androidx.core.splashscreen.SplashScreenViewProvider
import androidx.lifecycle.lifecycleScope
import kotlin.math.hypot
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
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

// How long the screen stays on after generating stopped, so it does not dim between two jobs.
private const val KEEP_SCREEN_ON_GRACE_MS = 3_000L

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
        startDestination = "main",
        enterTransition = { fadeIn(animationSpec = tween(200)) },
        exitTransition = { fadeOut(animationSpec = tween(200)) },
        popEnterTransition = { fadeIn(animationSpec = tween(200)) },
        popExitTransition = { fadeOut(animationSpec = tween(200)) },
    ) {
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

// The splash plays its animation (the hammer's strike) at least this long, and waits for the app's own data at most
// SPLASH_MAX_MS; after that StartupScreen shows what the start is doing.
private const val SPLASH_MIN_MS = 850L
private const val SPLASH_MAX_MS = 1_500L

/** Shows the content only inside a circle growing from the middle of the screen ([progress] 0..1). */
private fun Modifier.circularReveal(progress: () -> Float): Modifier =
    drawWithContent {
        val p = progress()
        if (p >= 1f) {
            drawContent()
        } else {
            val radius = hypot(size.width, size.height) / 2f * p
            clipPath(Path().apply { addOval(Rect(center, radius)) }) { this@drawWithContent.drawContent() }
        }
    }

/** A start slower than the splash may stay: the anvil and what the app is loading. */
@Composable
private fun StartupScreen(status: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(painter = painterResource(R.drawable.splash_anvil), contentDescription = null, modifier = Modifier.size(160.dp))
        LinearProgressIndicator(modifier = Modifier.width(120.dp).padding(top = 8.dp))
        Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f), modifier = Modifier.padding(top = 12.dp))
    }
}

// --- MAIN ACTIVITY ENTRY POINT ---

class MainActivity : ComponentActivity() {
    val navEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val pendingIntents = MutableSharedFlow<Intent>(extraBufferCapacity = 1)

    private val viewModel: ForgeViewModel by viewModels()

    // The main screen has drawn its first frame: the splash leaves only then, so the reveal shows a finished screen.
    @Volatile private var mainShown = false

    // How far the screen is revealed from the middle when the splash leaves (1 = all of it).
    private val reveal = Animatable(1f)

    /**
     * The splash leaves: the anvil grows and fades while the main screen opens in a circle from the middle. The
     * screen is hidden first (under the splash, so nothing flashes) and revealed while the splash fades out.
     */
    private fun playSplashExit(provider: SplashScreenViewProvider) {
        lifecycleScope.launch {
            reveal.snapTo(0f)
            reveal.animateTo(1f, tween(durationMillis = 650, easing = FastOutSlowInEasing))
        }
        try {
            provider.iconView
                .animate()
                .scaleX(1.8f)
                .scaleY(1.8f)
                .alpha(0f)
                .setDuration(320)
                .setInterpolator(AccelerateInterpolator())
                .start()
        } catch (_: Exception) {
            // no icon on this splash (e.g. a start from a notification)
        }
        provider.view
            .animate()
            .alpha(0f)
            .setStartDelay(60)
            .setDuration(360)
            .withEndAction { provider.remove() }
            .start()
    }

    /**
     * The system draws the splash before the app runs, in the phone's light or dark mode; telling it the app's own
     * choice (Light, Dark or System in the settings) makes the next splash match the app.
     */
    private fun syncSplashNightMode(themeMode: String) {
        val mode =
            when (themeMode) {
                THEME_DARK -> UiModeManager.MODE_NIGHT_YES
                THEME_LIGHT -> UiModeManager.MODE_NIGHT_NO
                else -> UiModeManager.MODE_NIGHT_AUTO
            }
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        if (prefs.getInt("splash_night_mode", UiModeManager.MODE_NIGHT_AUTO) == mode) return
        try {
            getSystemService(UiModeManager::class.java)?.setApplicationNightMode(mode)
            prefs.edit().putInt("splash_night_mode", mode).apply()
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "Could not set the splash's night mode", e)
        }
    }

    override fun onStop() {
        super.onStop()
        syncSplashNightMode(viewModel.config.value.themeMode)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == "ACTION_OPEN_SETTINGS") {
            navEvents.tryEmit("setup")
        } else {
            pendingIntents.tryEmit(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The system splash (the animated anvil) stays until the app's own data is loaded and the main screen is drawn,
        // at most SPLASH_MAX_MS; a slower start then shows its status (StartupScreen). The server is not waited for.
        val splash = installSplashScreen()
        val splashShownAt = SystemClock.uptimeMillis()
        splash.setKeepOnScreenCondition {
            val shownFor = SystemClock.uptimeMillis() - splashShownAt
            shownFor < SPLASH_MIN_MS || (!(viewModel.isStarted.value && mainShown) && shownFor < SPLASH_MAX_MS)
        }
        splash.setOnExitAnimationListener { provider -> playSplashExit(provider) }

        super.onCreate(savedInstanceState)
        ForgeSettingsManager.applyCachedThemeMode(this)
        lifecycleScope.launch { viewModel.initializeApp() }

        if (intent?.action == "ACTION_OPEN_SETTINGS") {
            navEvents.tryEmit("setup")
        } else if (intent != null) {
            pendingIntents.tryEmit(intent)
        }

        setContent {
            val context = LocalContext.current
            val viewModel: ForgeViewModel = viewModel()
            val isStarted by viewModel.isStarted.collectAsStateWithLifecycle()
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
            // With the lock on, or "Hide App in Recents", keep the app's content out of the Recents preview (Android 13+).
            LaunchedEffect(config.useNativeSecurity, config.hideInRecents) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    activity.setRecentsScreenshotEnabled(!(config.useNativeSecurity || config.hideInRecents))
                }
            }
            // "Block Screenshots": no screenshots or screen recordings of the app (and a blank Recents preview).
            LaunchedEffect(config.blockScreenshots) {
                if (config.blockScreenshots) {
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            val isOnline by currentConnectivityStatus(this)
            val isServerBusy by viewModel.isServerBusy.collectAsStateWithLifecycle()
            val isQueueActive by viewModel.isQueueActive.collectAsStateWithLifecycle()
            val isWaitingForSchedule by viewModel.isWaitingForSchedule.collectAsStateWithLifecycle()

            // "Keep Screen On" only while images are being generated (it used to keep the screen on whenever the app
            // was open); the grace period keeps the screen from dimming in the moment between two jobs.
            // Not while the queue only waits for its scheduled start ("Start at"), maybe for hours.
            val keepScreenOn = config.keepScreenOn && ((isQueueActive && !isWaitingForSchedule) || isServerBusy)
            LaunchedEffect(keepScreenOn) {
                if (keepScreenOn) {
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    delay(KEEP_SCREEN_ON_GRACE_MS)
                    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            val systemDark = isSystemInDarkTheme()
            val darkTheme =
                when (config.themeMode) {
                    THEME_DARK -> true
                    THEME_LIGHT -> false
                    else -> systemDark
                }
            val defaultColorScheme =
                remember(darkTheme) {
                    if (darkTheme) {
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
            // The phone's network came back: the server may be reachable again (a new minute of tries).
            LaunchedEffect(isOnline) { if (isOnline) viewModel.reconnect() }

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

            MaterialTheme(colorScheme = defaultColorScheme, typography = defaultTypography, shapes = defaultShapes) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Surface(
                        modifier = Modifier.fillMaxSize().circularReveal { reveal.value },
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        if (isStarted) {
                            AppNavigation(viewModel = viewModel, navController = navController)
                            LaunchedEffect(Unit) {
                                withFrameNanos { } // drawn once: the splash may leave
                                mainShown = true
                            }
                        } else {
                            // The start took longer than the splash may stay: say what it is doing.
                            val initStatus by ForgeSettingsManager.initStatus.collectAsStateWithLifecycle()
                            StartupScreen(initStatus)
                        }
                    }

                    // The app stopped looking for the server (a minute without an answer), or the connection status was
                    // tapped: its address, the saved profiles, a test and another try. Closing it keeps the app usable.
                    val connection by viewModel.connection.collectAsStateWithLifecycle()
                    val serverDialogRequested by viewModel.serverDialogRequested.collectAsStateWithLifecycle()
                    var offlineDialogClosed by rememberSaveable { mutableStateOf(false) }
                    LaunchedEffect(connection) { if (connection != ServerConnection.OFFLINE) offlineDialogClosed = false }
                    val offline = connection == ServerConnection.OFFLINE
                    if (isStarted && !isLocked && (serverDialogRequested || (offline && !offlineDialogClosed))) {
                        ServerConnectionDialog(
                            viewModel = viewModel,
                            offline = offline,
                            phoneOnline = isOnline,
                            onDismiss = {
                                viewModel.closeServerDialog()
                                if (offline) offlineDialogClosed = true
                            },
                            onOpenSettings = {
                                viewModel.closeServerDialog()
                                if (offline) offlineDialogClosed = true
                                navController.navigate("setup")
                            },
                        )
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
                        if (!isLocked && isStarted && currentRoute != null) {
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
