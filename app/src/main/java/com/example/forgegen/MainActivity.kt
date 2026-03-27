package com.yourname.forgegen

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
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
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
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
import coil.compose.AsyncImage
import coil.compose.LocalImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.system.exitProcess
import com.yourname.forgegen.Translator.t

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

class PromptVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val spanStyles = mutableListOf<AnnotatedString.Range<SpanStyle>>()
        val str = text.text

        // Syntax Highlighting: LoRAs <lora:name:1.0> (Purple)
        Regex("<lora:[^>]+>").findAll(str).forEach { match ->
            spanStyles.add(AnnotatedString.Range(SpanStyle(color = Color(0xFFB388FF), fontWeight = FontWeight.Bold), match.range.first, match.range.last + 1))
        }
        // Syntax Highlighting: Emphasis (tag:1.5) (Yellow/Orange)
        Regex("\\([^)]+\\)").findAll(str).forEach { match ->
            spanStyles.add(AnnotatedString.Range(SpanStyle(color = Color(0xFFFFD54F)), match.range.first, match.range.last + 1))
        }
        // Syntax Highlighting: De-emphasis [tag] (Green)
        Regex("\\[[^]]+\\]").findAll(str).forEach { match ->
            spanStyles.add(AnnotatedString.Range(SpanStyle(color = Color(0xFF81C784)), match.range.first, match.range.last + 1))
        }

        return TransformedText(AnnotatedString(str, spanStyles), OffsetMapping.Identity)
    }
}

fun countTokens(text: String): Int {
    if (text.isBlank()) return 0
    val words = text.split(Regex("[,\\s]+")).filter { it.isNotBlank() }
    return words.size
}

class MainActivity : ComponentActivity() {

    val navEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)

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

        setContent {
            val context = LocalContext.current
            val viewModel: ForgeViewModel = viewModel()

            val config by viewModel.config.collectAsStateWithLifecycle()
            val appState by viewModel.appState.collectAsStateWithLifecycle()

            val activity = context.findActivity() ?: return@setContent

            DisposableEffect(context) {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        if (intent?.action == "ACTION_EXIT_APP") {
                            val manager = context?.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
                            manager?.cancel(1001)
                            manager?.cancel(1002)

                            val svcIntent = Intent(context, GenerationService::class.java)
                            context?.stopService(svcIntent)

                            if (context is Activity) {
                                context.finishAndRemoveTask()
                            }
                            android.os.Process.killProcess(android.os.Process.myPid())
                            exitProcess(0)
                        }
                    }
                }
                val filter = IntentFilter("ACTION_EXIT_APP")
                ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

                onDispose {
                    context.unregisterReceiver(receiver)
                }
            }

            LaunchedEffect(config.language) {
                Translator.currentLang = config.language
            }

            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_START) {
                        viewModel.setAppForegroundState(true)
                    } else if (event == Lifecycle.Event.ON_STOP) {
                        viewModel.setAppForegroundState(false)
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }

            val imageLoader = remember(config.connectionTimeout, config.apiUrl) {
                ImageLoader.Builder(context)
                    .okHttpClient { viewModel.client }
                    .build()
            }

            var isUnlocked by remember { mutableStateOf(!config.useNativeSecurity) }

            if (!isUnlocked) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(64.dp), tint = Color.White)
                        Spacer(Modifier.height(16.dp))
                        Text("App Locked".t, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Spacer(Modifier.height(32.dp))
                        Button(onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                val authenticators = if (config.useBiometricLock) {
                                    android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG or android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
                                } else {
                                    android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
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
                            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                val prompt = BiometricPrompt.Builder(activity)
                                    .setTitle("ForgeGen Security")
                                    .setNegativeButton("Cancel".t, activity.mainExecutor) { _, _ -> }
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
                            Text("Tap to Unlock".t)
                        }
                    }
                }
                return@setContent
            }

            // Screen Dimming & Activity tracking
            var lastTouchTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
            var isScreenDimmed by remember { mutableStateOf(false) }

            LaunchedEffect(config.keepScreenOn) {
                if (config.keepScreenOn) {
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            LaunchedEffect(lastTouchTime, config.screenDimming, config.screenDimmingTimeout) {
                if (!config.screenDimming) {
                    isScreenDimmed = false
                    return@LaunchedEffect
                }
                val timeoutMs = config.screenDimmingTimeout * 60 * 1000L
                while (isActive) {
                    if (System.currentTimeMillis() - lastTouchTime >= timeoutMs) {
                        isScreenDimmed = true
                    } else {
                        isScreenDimmed = false
                    }
                    delay(1000)
                }
            }

            LaunchedEffect(isScreenDimmed) {
                val lp = activity.window.attributes
                lp.screenBrightness = if (isScreenDimmed) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                activity.window.attributes = lp
            }

            val isOnline by currentConnectivityStatus(this)
            val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()

            val isServerBusy by ForgeState.isServerBusy.collectAsStateWithLifecycle()
            val generationQueue by ForgeState.generationQueue.collectAsStateWithLifecycle()

            val prefs = remember { context.getSharedPreferences("ForgeGenPrefs", Context.MODE_PRIVATE) }

            val isSamsungDevice = remember { Build.MANUFACTURER.equals("samsung", ignoreCase = true) }
            var useSamsungTheme by remember { mutableStateOf(prefs.getBoolean("use_samsung_theme", isSamsungDevice)) }

            val useDynamicColor = config.useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

            val aospColorScheme = remember(config.isDarkMode) { if (config.isDarkMode) darkColorScheme() else lightColorScheme() }

            val samsungColorScheme = remember(config.isDarkMode) {
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

            val appTypography = remember {
                Typography(
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
            }

            val samsungTypography = remember {
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

            val samsungShapes = remember {
                Shapes(
                    small = RoundedCornerShape(12.dp),
                    medium = RoundedCornerShape(20.dp),
                    large = RoundedCornerShape(26.dp),
                    extraLarge = RoundedCornerShape(32.dp)
                )
            }

            val finalColorScheme = remember(useDynamicColor, config.isDarkMode, isSamsungDevice, useSamsungTheme) {
                when {
                    useDynamicColor -> if (config.isDarkMode) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                    isSamsungDevice && useSamsungTheme -> samsungColorScheme
                    else -> aospColorScheme
                }
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
            val isSessionActive = currentRoute == "main" || currentRoute == "gallery" || currentRoute == "queue"

            val shouldBlur = (!isOnline || (!isConnected && !isServerBusy)) && isSessionActive
            val onSetupClick = rememberDebounced { navController.navigate("setup") }

            CompositionLocalProvider(LocalImageLoader provides imageLoader) {
                MaterialTheme(colorScheme = finalColorScheme, typography = finalTypography, shapes = finalShapes) {
                    Box(modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent(PointerEventPass.Initial)
                                    lastTouchTime = System.currentTimeMillis()
                                }
                            }
                        }
                    ) {
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
                                            Text("No Internet Connection".t, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                            Spacer(Modifier.height(8.dp))
                                            Text("Turn on internet connection to use app".t, textAlign = TextAlign.Center)
                                        } else {
                                            Icon(Icons.Default.CloudOff, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
                                            Spacer(Modifier.height(8.dp))
                                            Text("Server Not Found".t, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                            Spacer(Modifier.height(8.dp))
                                            Text("Make sure your API server is running.".t, textAlign = TextAlign.Center)
                                        }

                                        Spacer(modifier = Modifier.height(16.dp))

                                        Button(
                                            onClick = onSetupClick,
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                        ) {
                                            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Open Settings".t)
                                        }
                                    }
                                }
                            }
                        }

                        if (isScreenDimmed) {
                            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha=0.9f)).zIndex(999f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun rememberDebounced(onClick: () -> Unit): () -> Unit {
    var lastClickTime by remember { mutableStateOf(0L) }
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

@Composable
fun currentConnectivityStatus(context: Context): State<Boolean> {
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val isConnected = remember { mutableStateOf(checkConnectivity(connectivityManager)) }
    val wasOffline = remember { mutableStateOf(!isConnected.value) }

    val backOnlineMessage = "Back online!".t

    DisposableEffect(connectivityManager) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = connectivityManager.getNetworkCapabilities(network)
                val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                if (hasInternet) {
                    if (wasOffline.value) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            android.widget.Toast.makeText(context, backOnlineMessage, android.widget.Toast.LENGTH_SHORT).show()
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
                            android.widget.Toast.makeText(context, backOnlineMessage, android.widget.Toast.LENGTH_SHORT).show()
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
fun UndoRedoTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
    onClear: () -> Unit,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    actions: @Composable RowScope.() -> Unit = {}
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
            ) { Icon(Icons.AutoMirrored.Filled.Undo, null, Modifier.size(18.dp)) }

            IconButton(
                onClick = {
                    if (historyIndex < history.size - 1) {
                        historyIndex++
                        onValueChange(history[historyIndex])
                    }
                },
                enabled = historyIndex < history.size - 1,
                modifier = Modifier.size(32.dp)
            ) { Icon(Icons.AutoMirrored.Filled.Redo, null, Modifier.size(18.dp)) }
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
            visualTransformation = visualTransformation,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp)) {
                    if (value.isNotEmpty()) {
                        VerticalDivider(
                            modifier = Modifier.height(24.dp).padding(horizontal = 4.dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                        )
                    }
                    actions()
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

        val loraPattern = java.util.regex.Pattern.compile("<lora:([^:]+):([0-9.]+)>")
        val matcher = loraPattern.matcher(posPrompt)
        while (matcher.find()) {
            loras.add(matcher.group(0) ?: "")
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Generation Data".t, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(bottom = 8.dp))

                Box(modifier = Modifier.weight(1f, fill = false).background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small).padding(8.dp)) {
                    Text(
                        text = metadata ?: "Loading...".t,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    )
                }

                if (metadata != null && posPrompt.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Apply to current session:".t, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))

                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (onApplyAll != null) {
                            Button(onClick = onApplyAll, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), modifier = Modifier.height(32.dp)) {
                                Text("Apply All".t, fontSize = 12.sp)
                            }
                        }

                        OutlinedButton(onClick = { onApplyPrompt(posPrompt, negPrompt) }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), modifier = Modifier.height(32.dp)) {
                            Text("Prompt".t, fontSize = 12.sp)
                        }

                        if (modelName.isNotEmpty()) {
                            OutlinedButton(onClick = { onApplyModel(modelName) }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), modifier = Modifier.height(32.dp)) {
                                Text("Model".t, fontSize = 12.sp)
                            }
                        }

                        if (loras.isNotEmpty()) {
                            OutlinedButton(onClick = { onApplyLoras(loras) }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), modifier = Modifier.height(32.dp)) {
                                Text("${"LoRAs".t} (${loras.size})", fontSize = 12.sp)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Close".t)
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
                    navController.navigate(route) { popUpTo(navController.graph.startDestinationId) }
                }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = "main",
        enterTransition = { fadeIn(animationSpec = tween(0)) },
        exitTransition = { fadeOut(animationSpec = tween(0)) },
        popEnterTransition = { fadeIn(animationSpec = tween(0)) },
        popExitTransition = { fadeOut(animationSpec = tween(0)) }
    ) {
        composable("setup") { SetupScreen(viewModel, navController, isSamsung, useSamsungTheme, onThemeChange) }
        composable("main") { MainScreen(viewModel, navController, isSamsung && useSamsungTheme) }
        composable("gallery") { GalleryScreen(viewModel, navController) }
        composable("queue") { QueueScreen(viewModel, navController) }
    }
}

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
    navController: NavHostController,
    isSamsung: Boolean,
    useSamsungTheme: Boolean,
    onThemeChange: (Boolean) -> Unit
) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val useMultiThreading by viewModel.useMultiThreading.collectAsStateWithLifecycle()

    var showUrlDialog by remember { mutableStateOf(false) }
    var showPathDialog by remember { mutableStateOf(false) }
    var showCheckpointPathDialog by remember { mutableStateOf(false) }
    var showLoraPathDialog by remember { mutableStateOf(false) }
    var showTimeoutDialog by remember { mutableStateOf(false) }
    var showProfilesDialog by remember { mutableStateOf(false) }
    var showVerbosityDialog by remember { mutableStateOf(false) }

    var isTestingConnection by remember { mutableStateOf(false) }
    var testStatus by remember { mutableStateOf<String?>(null) }
    var testResults by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val keyguardManager = remember { context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager }
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

    val onBackClick = rememberDebounced {
        if (navController.previousBackStackEntry != null) {
            navController.popBackStack()
        } else {
            navController.navigate("main") { popUpTo(navController.graph.startDestinationId) }
        }
    }

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
                TextPreference(title = "Test Connection".t, subtitle = "Run API diagnostics".t, value = "") {
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
                                testClient.newCall(req).await().use { res ->
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
                            withContext(Dispatchers.Main) { testResults = results.toList() }
                        }

                        withContext(Dispatchers.Main) {
                            testStatus = if (allSuccess) "All Systems Operational!" else "Some APIs Failed."
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
                    title = "Live Step-by-Step Previews".t,
                    subtitle = "Show a live blurry image stream while generating".t,
                    checked = config.livePreviews,
                    onCheckedChange = { viewModel.saveConfig(config.copy(livePreviews = it)) }
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                item {
                    SwitchPreference(
                        title = "Use Material You".t,
                        subtitle = "Extracts app dynamic color theme directly from your wallpaper".t,
                        checked = config.useDynamicColor,
                        onCheckedChange = { viewModel.saveConfig(config.copy(useDynamicColor = it)) }
                    )
                }
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
            item {
                SwitchPreference(
                    title = "Samsung One UI Mode".t,
                    subtitle = "Use native Samsung styling and shapes".t,
                    checked = useSamsungTheme,
                    enabled = isSamsung && !config.useDynamicColor,
                    onCheckedChange = onThemeChange
                )
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

fun getTagStrength(tag: String): String {
    val trimmed = tag.trim()
    val pattern = java.util.regex.Pattern.compile("^\\((.*):([0-9.]+)\\)$")
    val matcher = pattern.matcher(trimmed)
    if (matcher.find() && matcher.groupCount() >= 2) {
        return matcher.group(2) ?: "1.0"
    }
    return "1.0"
}

fun adjustTagStrength(tag: String, delta: Float): String {
    val trimmed = tag.trim()
    val pattern = java.util.regex.Pattern.compile("^\\((.*):([0-9.]+)\\)$")
    val matcher = pattern.matcher(trimmed)

    if (matcher.find() && matcher.groupCount() >= 2) {
        val base = matcher.group(1)
        val currentStrength = matcher.group(2)?.toFloatOrNull() ?: 1.0f
        val newStrength = (currentStrength + delta).coerceIn(0.1f, 3.0f)
        if (abs(newStrength - 1.0f) < 0.05f) return base ?: ""
        return "($base:${String.format(Locale.US, "%.1f", newStrength)})"
    } else {
        val newStrength = (1.0f + delta).coerceIn(0.1f, 3.0f)
        if (abs(newStrength - 1.0f) < 0.05f) return trimmed
        return "($trimmed:${String.format(Locale.US, "%.1f", newStrength)})"
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DragToReorderTagsRow(text: String, onPromptChanged: (String) -> Unit) {
    val tags = text.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    if (tags.isNotEmpty()) {
        var draggingIndex by remember { mutableStateOf<Int?>(null) }
        var tunedIndex by remember { mutableStateOf<Int?>(null) }

        var dragOffsetX by remember { mutableStateOf(0f) }
        var dragOffsetY by remember { mutableStateOf(0f) }

        fun swap(i: Int, j: Int) {
            val newTags = tags.toMutableList()
            newTags[i] = newTags[j].also { newTags[j] = newTags[i] }
            onPromptChanged(newTags.joinToString(", "))
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            tags.forEachIndexed { index, tag ->
                val isDragging = index == draggingIndex
                val isTuned = index == tunedIndex

                val modifier = if (isDragging) {
                    Modifier.offset { IntOffset(dragOffsetX.roundToInt(), dragOffsetY.roundToInt()) }.zIndex(1f)
                } else {
                    Modifier.zIndex(0f)
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
                    AnimatedVisibility(visible = isTuned && !isDragging) {
                        Row(
                            modifier = Modifier
                                .padding(bottom = 2.dp)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Remove, "Decrease".t,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(14.dp).clickable {
                                    val newTags = tags.toMutableList()
                                    newTags[index] = adjustTagStrength(tag, -0.1f)
                                    onPromptChanged(newTags.joinToString(", "))
                                }
                            )
                            Text(
                                getTagStrength(tag),
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 6.dp)
                            )
                            Icon(
                                Icons.Default.Add, "Increase".t,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(14.dp).clickable {
                                    val newTags = tags.toMutableList()
                                    newTags[index] = adjustTagStrength(tag, 0.1f)
                                    onPromptChanged(newTags.joinToString(", "))
                                }
                            )
                        }
                    }

                    AssistChip(
                        onClick = { tunedIndex = if (tunedIndex == index) null else index },
                        label = { Text(tag, fontSize = 12.sp) },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Remove".t,
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable {
                                        val newTags = tags.toMutableList()
                                        newTags.removeAt(index)
                                        onPromptChanged(newTags.joinToString(", "))
                                        if (tunedIndex == index) tunedIndex = null
                                    }
                            )
                        },
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.pointerInput(Unit) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggingIndex = index
                                    tunedIndex = null
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragOffsetX += dragAmount.x
                                    dragOffsetY += dragAmount.y

                                    val swapXThreshold = 150f
                                    val swapYThreshold = 80f

                                    if (dragOffsetX > swapXThreshold && index < tags.size - 1) {
                                        swap(index, index + 1)
                                        draggingIndex = index + 1
                                        dragOffsetX -= swapXThreshold
                                    } else if (dragOffsetX < -swapXThreshold && index > 0) {
                                        swap(index, index - 1)
                                        draggingIndex = index - 1
                                        dragOffsetX += swapXThreshold
                                    }

                                    if (dragOffsetY > swapYThreshold && index < tags.size - 3) {
                                        val target = (index + 3).coerceAtMost(tags.size - 1)
                                        swap(index, target)
                                        draggingIndex = target
                                        dragOffsetY -= swapYThreshold
                                    } else if (dragOffsetY < -swapYThreshold && index > 2) {
                                        val target = (index - 3).coerceAtLeast(0)
                                        swap(index, target)
                                        draggingIndex = target
                                        dragOffsetY += swapYThreshold
                                    }
                                },
                                onDragEnd = { draggingIndex = null; dragOffsetX = 0f; dragOffsetY = 0f },
                                onDragCancel = { draggingIndex = null; dragOffsetX = 0f; dragOffsetY = 0f }
                            )
                        }
                    )
                }
            }
        }
    } else {
        Text("No active tags".t, color = Color.Gray, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(viewModel: ForgeViewModel, navController: NavHostController, isSamsungMode: Boolean) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val state by viewModel.appState.collectAsStateWithLifecycle()
    val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()
    val pingMs by viewModel.pingMs.collectAsStateWithLifecycle()
    val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val currentEta by ForgeState.currentEta.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val status by viewModel.statusText.collectAsStateWithLifecycle()
    val vram by ForgeState.vramUsage.collectAsStateWithLifecycle()

    val isServerBusy by ForgeState.isServerBusy.collectAsStateWithLifecycle()
    val generationQueue by ForgeState.generationQueue.collectAsStateWithLifecycle()

    val sessionImages by viewModel.sessionImages.collectAsStateWithLifecycle()
    val currentSessionIndex by viewModel.currentSessionIndex.collectAsStateWithLifecycle()
    val livePreviewBase64 by ForgeState.livePreviewImage.collectAsStateWithLifecycle()
    val isShowingGridPreview by ForgeState.isShowingGridPreview.collectAsStateWithLifecycle()

    val batchStart by ForgeState.currentBatchStartIndex.collectAsStateWithLifecycle()
    val batchEnd by ForgeState.currentBatchEndIndex.collectAsStateWithLifecycle()

    val models by viewModel.models.collectAsStateWithLifecycle()
    val selectedModel by viewModel.selectedModel.collectAsStateWithLifecycle()
    val samplers by viewModel.samplers.collectAsStateWithLifecycle()
    val schedulers by viewModel.schedulers.collectAsStateWithLifecycle()
    val availableLoras by viewModel.availableLoras.collectAsStateWithLifecycle()
    val activeLoras by viewModel.activeLoras.collectAsStateWithLifecycle()
    val upscalers by viewModel.upscalers.collectAsStateWithLifecycle()

    val tagSuggestions by viewModel.tagSuggestions.collectAsStateWithLifecycle()
    val isRestoringPrompt by viewModel.isRestoringPrompt.collectAsStateWithLifecycle()
    val promptHistory by viewModel.promptHistory.collectAsStateWithLifecycle()

    var showOverflowMenu by remember { mutableStateOf(false) }
    var showHistoryDialog by remember { mutableStateOf(false) }
    var showRecoverMenu by remember { mutableStateOf(false) }
    var showPresetsDialog by remember { mutableStateOf(false) }
    var fullscreenImageIndex by remember { mutableStateOf(-1) }

    var pendingLora by remember { mutableStateOf<ApiResource?>(null) }

    var showPosTagEditor by remember { mutableStateOf(false) }
    var showNegTagEditor by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val onGalleryClick = rememberDebounced {
        showOverflowMenu = false
        viewModel.setGalleryMode(GalleryMode.NORMAL)
        viewModel.fetchGalleryFolder()
        navController.navigate("gallery")
    }

    val onSettingsClick = rememberDebounced {
        showOverflowMenu = false
        navController.navigate("setup")
    }

    val onQueueClick = rememberDebounced { navController.navigate("queue") }

    Box(modifier = Modifier.fillMaxSize()) {

        Scaffold(
            modifier = Modifier.then(if (isRestoringPrompt) Modifier.blur(10.dp) else Modifier),
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(24.dp))
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
                                        text = if (isConnected) "${pingMs}ms" else "Offline".t,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Normal
                                    )
                                    if (vram != null) {
                                        Text(
                                            text = " | VRAM: $vram",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Normal,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha=0.7f)
                                        )
                                    }
                                }
                            }
                        }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { showOverflowMenu = true }) {
                                Icon(Icons.Default.MoreVert, null)
                            }
                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Gallery".t, fontSize = 14.sp) },
                                    leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                    onClick = onGalleryClick
                                )
                                DropdownMenuItem(
                                    text = { Text("Settings".t, fontSize = 14.sp) },
                                    leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                    onClick = onSettingsClick
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

                    val oomAlert by ForgeState.oomAlert.collectAsStateWithLifecycle()
                    AnimatedVisibility(visible = oomAlert) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Warning, contentDescription = "Error", tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(32.dp))
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("SERVER OUT OF MEMORY (OOM)".t, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                                Text("The current generation failed and the queue is paused. The failed prompt was skipped.".t, fontSize = 12.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onErrorContainer)
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(onClick = { viewModel.resumeQueue() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onErrorContainer, contentColor = MaterialTheme.colorScheme.errorContainer)) {
                                    Text("Resume Queue".t)
                                }
                            }
                        }
                    }

                    Box(modifier = Modifier.fillMaxWidth().height(240.dp).clip(MaterialTheme.shapes.medium).background(Color.DarkGray)) {
                        if (isGenerating && !livePreviewBase64.isNullOrEmpty()) {

                            val previewBitmap by produceState<android.graphics.Bitmap?>(initialValue = null, livePreviewBase64) {
                                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                    try {
                                        val bytes = android.util.Base64.decode(livePreviewBase64, android.util.Base64.DEFAULT)
                                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                    } catch (e: Exception) { null }
                                }
                            }

                            previewBitmap?.let { bitmap ->
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "Live Preview",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                            }

                        } else if (isShowingGridPreview && sessionImages.size > batchStart && batchEnd >= batchStart) {
                            val bStart = batchStart
                            val bEnd = batchEnd
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
                        } else if (currentSessionIndex >= 0 && sessionImages.isNotEmpty() && currentSessionIndex < sessionImages.size) {
                            AsyncImage(
                                model = sessionImages[currentSessionIndex],
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize().clickable { fullscreenImageIndex = currentSessionIndex },
                                contentScale = ContentScale.Fit
                            )
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.align(Alignment.Center)) {
                                Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color.Gray)
                                Text("No Preview".t, color = Color.Gray)
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
                        Text("Prompts".t, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Row {
                            IconButton(onClick = { showPresetsDialog = true }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.SettingsSuggest, contentDescription = "Manage Presets".t, tint = MaterialTheme.colorScheme.primary)
                            }
                            Box {
                                TextButton(onClick = { showRecoverMenu = true }, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(32.dp)) {
                                    Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Recover Prompt".t, fontSize = 12.sp)
                                }
                                DropdownMenu(expanded = showRecoverMenu, onDismissRequest = { showRecoverMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Last Generated Image".t, fontSize = 14.sp) },
                                        leadingIcon = { Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                        onClick = {
                                            showRecoverMenu = false
                                            viewModel.recoverLastPrompt()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("From History".t, fontSize = 14.sp) },
                                        leadingIcon = { Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                        onClick = {
                                            showRecoverMenu = false
                                            showHistoryDialog = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("From Gallery Image".t, fontSize = 14.sp) },
                                        leadingIcon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                        onClick = {
                                            showRecoverMenu = false
                                            viewModel.setGalleryMode(GalleryMode.PROMPT_PICKER)
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
                        label = { Text("Positive Prompt".t, fontSize = 12.sp) },
                        minLines = 3,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PromptVisualTransformation(),
                        onClear = { viewModel.updateState { s -> s.copy(positivePrompt = "") } },
                        actions = {
                            if (config.showActiveTagsUI && state.positivePrompt.isNotBlank()) {
                                IconButton(onClick = { showPosTagEditor = true }) {
                                    Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    )

                    // Pozytywne tokeny, Kopiowanie i Reset
                    val positiveTokens = countTokens(state.positivePrompt)
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("$positiveTokens / 75", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha=0.5f))
                        Row {
                            TextButton(onClick = {
                                clipboardManager.setText(AnnotatedString(state.positivePrompt))
                                Toast.makeText(context, "Prompt Copied".t, Toast.LENGTH_SHORT).show()
                            }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp), modifier = Modifier.height(24.dp)) {
                                Text("Copy Prompt".t, fontSize = 10.sp)
                            }
                            TextButton(onClick = { viewModel.updateState { s -> s.copy(positivePrompt = "") } }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp), modifier = Modifier.height(24.dp)) {
                                Text("Clear".t, fontSize = 10.sp)
                            }
                        }
                    }

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

                    Spacer(modifier = Modifier.height(4.dp))
                    UndoRedoTextField(
                        value = state.negativePrompt,
                        onValueChange = { viewModel.updateState { s -> s.copy(negativePrompt = it) } },
                        label = { Text("Negative Prompt".t, fontSize = 12.sp) },
                        minLines = 2,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PromptVisualTransformation(),
                        onClear = { viewModel.updateState { s -> s.copy(negativePrompt = "") } },
                        actions = {
                            if (config.showActiveTagsUI && state.negativePrompt.isNotBlank()) {
                                IconButton(onClick = { showNegTagEditor = true }) {
                                    Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    )

                    // Negatywne tokeny i Reset Globalny
                    val negativeTokens = countTokens(state.negativePrompt)
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("$negativeTokens / 75", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha=0.5f))
                        TextButton(onClick = { viewModel.resetToDefaults() }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp), modifier = Modifier.height(24.dp)) {
                            Text("Reset to Defaults".t, fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Settings & LoRAs".t, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    Column(modifier = Modifier.padding(horizontal = 0.dp)) {
                        var modelExpanded by remember { mutableStateOf(false) }
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            OutlinedButton(onClick = { modelExpanded = true }, modifier = Modifier.fillMaxWidth().height(42.dp), contentPadding = PaddingValues(4.dp)) {
                                Text("${"Model: ".t}${selectedModel.ifEmpty { "Loading...".t }}", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                            }
                            DropdownMenu(expanded = modelExpanded, onDismissRequest = { modelExpanded = false }, modifier = Modifier.heightIn(max = 350.dp)) {
                                models.forEach { mod ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                AsyncImage(
                                                    model = viewModel.getPreviewUrl(mod.path, isLora = false),
                                                    contentDescription = null,
                                                    modifier = Modifier.size(40.dp).padding(end = 8.dp).clip(RoundedCornerShape(6.dp)).background(Color.DarkGray),
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
                                label = { Text("Seed".t, fontSize = 12.sp) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )

                            var seedExpanded by remember { mutableStateOf(false) }
                            Box {
                                IconButton(
                                    onClick = { seedExpanded = true },
                                    modifier = Modifier.padding(start = 8.dp)
                                ) {
                                    Icon(Icons.Default.Casino, contentDescription = "Seed Options".t)
                                }
                                DropdownMenu(expanded = seedExpanded, onDismissRequest = { seedExpanded = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Randomize (-1)".t, fontSize = 14.sp) },
                                        onClick = {
                                            viewModel.updateState { s -> s.copy(seed = -1L) }
                                            seedExpanded = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Recover Last Seed".t, fontSize = 14.sp) },
                                        onClick = {
                                            viewModel.recoverLastSeed()
                                            seedExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        ForgeSlider("Batch Count".t, state.batchCount.toFloat(), 1f..100f, 0) { viewModel.updateState { s -> s.copy(batchCount = it.toInt()) } }
                        ForgeSlider("Batch Size".t, state.batchSize.toFloat(), 1f..16f, 0) { viewModel.updateState { s -> s.copy(batchSize = it.toInt()) } }
                        ForgeSlider("Steps".t, state.steps.toFloat(), 1f..100f, 0) { viewModel.updateState { s -> s.copy(steps = it.toInt()) } }
                        ForgeSlider("CFG Scale".t, state.cfgScale, 1f..20f, 1) { viewModel.updateState { s -> s.copy(cfgScale = it) } }
                        ForgeSlider("Clip Skip".t, state.clipSkip.toFloat(), 1f..3f, 0) { viewModel.updateState { s -> s.copy(clipSkip = it.toInt()) } }
                        ForgeSlider("Width".t, state.width.toFloat(), 256f..2048f, 0) { viewModel.updateState { s -> s.copy(width = (it.toInt() / 64) * 64) } }
                        ForgeSlider("Height".t, state.height.toFloat(), 256f..2048f, 0) { viewModel.updateState { s -> s.copy(height = (it.toInt() / 64) * 64) } }

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

                        // --- Hires.fix UI ---
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                            HorizontalDivider(modifier = Modifier.weight(1f))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp).clickable { viewModel.updateState { it.copy(hiresFix = !it.hiresFix) } }
                            ) {
                                Checkbox(checked = state.hiresFix, onCheckedChange = { v -> viewModel.updateState { it.copy(hiresFix = v) } })
                                Text("Hires.fix".t, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                            HorizontalDivider(modifier = Modifier.weight(1f))
                        }
                        AnimatedVisibility(visible = state.hiresFix) {
                            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                                var upscalerExpanded by remember { mutableStateOf(false) }
                                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    OutlinedButton(onClick = { upscalerExpanded = true }, modifier = Modifier.fillMaxWidth().height(36.dp), contentPadding = PaddingValues(4.dp)) {
                                        Text("${"Upscaler".t}: ${state.upscaler}", maxLines = 1, fontSize = 11.sp, overflow = TextOverflow.Ellipsis)
                                    }
                                    DropdownMenu(expanded = upscalerExpanded, onDismissRequest = { upscalerExpanded = false }, modifier = Modifier.heightIn(max = 350.dp)) {
                                        upscalers.forEach { upsc -> DropdownMenuItem(text = { Text(upsc, fontSize = 12.sp) }, onClick = { viewModel.updateState { s -> s.copy(upscaler = upsc) }; upscalerExpanded = false }) }
                                    }
                                }
                                ForgeSlider("Hires Scale".t, state.hiresScale, 1f..4f, 2) { viewModel.updateState { s -> s.copy(hiresScale = it) } }
                                ForgeSlider("Denoising".t, state.denoising, 0f..1f, 2) { viewModel.updateState { s -> s.copy(denoising = it) } }
                            }
                        }
                        // --- End Hires.fix UI ---

                        Spacer(modifier = Modifier.height(8.dp))
                        Text("LoRAs".t, fontWeight = FontWeight.Bold, fontSize = 16.sp)

                        var loraExpanded by remember { mutableStateOf(false) }
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            OutlinedButton(onClick = { loraExpanded = true }, modifier = Modifier.fillMaxWidth().height(36.dp), contentPadding = PaddingValues(4.dp)) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Add LoRA...".t, fontSize = 12.sp)
                            }
                            DropdownMenu(expanded = loraExpanded, onDismissRequest = { loraExpanded = false }, modifier = Modifier.heightIn(max = 350.dp)) {
                                availableLoras.forEach { loraData ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                AsyncImage(
                                                    model = viewModel.getPreviewUrl(loraData.path, isLora = true),
                                                    contentDescription = null,
                                                    modifier = Modifier.size(40.dp).padding(end = 8.dp).clip(RoundedCornerShape(6.dp)).background(Color.DarkGray),
                                                    contentScale = ContentScale.Crop
                                                )
                                                Text(loraData.title, fontSize = 12.sp)
                                            }
                                        },
                                        onClick = {
                                            pendingLora = loraData
                                            loraExpanded = false
                                        }
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
                                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("Strength".t, fontSize = 10.sp)
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

                    Spacer(modifier = Modifier.height(100.dp))
                }

                val isActivelyGenerating = isGenerating || isServerBusy || progress > 0f
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp, start = 16.dp, end = 16.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (generationQueue.isNotEmpty()) {
                        Button(
                            onClick = onQueueClick,
                            modifier = Modifier.height(54.dp).weight(0.35f).shadow(8.dp, CircleShape),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Icon(Icons.Default.List, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("${generationQueue.size}", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (isActivelyGenerating || generationQueue.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .height(54.dp)
                                .weight(1.2f)
                                .shadow(8.dp, CircleShape)
                                .clip(CircleShape)
                                .background(Color.DarkGray)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                val currentStep = (progress * state.steps).toInt()
                                val totalImgs = state.batchCount * state.batchSize
                                val percentage = (progress * 100).toInt()

                                Text(
                                    text = if (isGenerating) "GENERATING • ".t + "$percentage%" else "SERVER BUSY • ".t + "$percentage%",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    style = TextStyle(shadow = androidx.compose.ui.graphics.Shadow(color = Color.Black.copy(alpha=0.8f), blurRadius = 4f))
                                )
                                Text(
                                    text = "$totalImgs Imgs | Step $currentStep/${state.steps} | ETA: ${String.format(Locale.US, "%.1f", currentEta)}s",
                                    fontSize = 9.sp,
                                    color = Color.LightGray,
                                    style = TextStyle(shadow = androidx.compose.ui.graphics.Shadow(color = Color.Black.copy(alpha=0.8f), blurRadius = 4f))
                                )
                            }
                        }

                        val queueGen = rememberDebounced { viewModel.queueGeneration() }
                        Button(
                            onClick = queueGen,
                            enabled = isConnected && !isRestoringPrompt,
                            modifier = Modifier.height(54.dp).weight(0.8f).shadow(8.dp, CircleShape),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFA000)),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("QUEUE".t, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    } else {
                        val genBtn = rememberDebounced { viewModel.queueGeneration() }
                        Button(
                            onClick = genBtn,
                            enabled = isConnected && !isRestoringPrompt,
                            modifier = Modifier.height(54.dp).weight(1f).shadow(8.dp, CircleShape),
                            colors = ButtonDefaults.buttonColors(containerColor = if (isSamsungMode && !config.useDynamicColor) MaterialTheme.colorScheme.primary else Color(0xFF4CAF50))
                        ) {
                            Text("GENERATE".t, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }
        }

        // --- Presets Dialog ---
        if (showPresetsDialog) {
            var newPresetName by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showPresetsDialog = false },
                title = { Text("Manage Presets".t) },
                text = {
                    Column {
                        LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                            items(config.presets) { preset ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        viewModel.loadPreset(preset.name)
                                        showPresetsDialog = false
                                    }.padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(preset.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                        Text("${preset.state.steps} Steps | CFG: ${preset.state.cfgScale} | ${preset.state.sampler}", fontSize = 10.sp, color = Color.Gray)
                                    }
                                    IconButton(onClick = { viewModel.deletePreset(preset.name) }) {
                                        Icon(Icons.Default.Delete, "Delete".t, tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Text("Save Current Settings as Preset".t, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        OutlinedTextField(
                            value = newPresetName,
                            onValueChange = { newPresetName = it },
                            label = { Text("Preset Name".t) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { viewModel.saveCurrentAsDefault(); showPresetsDialog = false }) {
                                Text("Set Current as Default".t, fontSize = 12.sp)
                            }
                            Button(
                                onClick = {
                                    if (newPresetName.isNotBlank()) {
                                        viewModel.savePreset(newPresetName)
                                        newPresetName = ""
                                    }
                                }
                            ) {
                                Text("Save Preset".t)
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showPresetsDialog = false }) { Text("Close".t) } }
            )
        }

        if (showPosTagEditor) {
            AlertDialog(
                onDismissRequest = { showPosTagEditor = false },
                title = { Text("Active Positive Tags".t, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                text = {
                    Box(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp).background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small).padding(8.dp)) {
                        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            DragToReorderTagsRow(state.positivePrompt) { newPrompt -> viewModel.updateState { s -> s.copy(positivePrompt = newPrompt) } }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showPosTagEditor = false }) { Text("Done".t) }
                }
            )
        }

        if (showNegTagEditor) {
            AlertDialog(
                onDismissRequest = { showNegTagEditor = false },
                title = { Text("Active Negative Tags".t, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                text = {
                    Box(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp).background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small).padding(8.dp)) {
                        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            DragToReorderTagsRow(state.negativePrompt) { newPrompt -> viewModel.updateState { s -> s.copy(negativePrompt = newPrompt) } }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showNegTagEditor = false }) { Text("Done".t) }
                }
            )
        }

        if (showHistoryDialog) {
            AlertDialog(
                onDismissRequest = { showHistoryDialog = false },
                title = {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Prompt History".t)
                        IconButton(onClick = { viewModel.clearPromptHistory(); showHistoryDialog = false }) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                        }
                    }
                },
                text = {
                    if (promptHistory.isEmpty()) {
                        Text("No history available yet.".t, color = Color.Gray)
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
                                        if (item.negativePrompt.isNotBlank()) Text("Negative: ".t + item.negativePrompt, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 10.sp, color = Color.Gray)
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showHistoryDialog = false }) { Text("Close".t) }
                }
            )
        }

        // Live-updating LoRA Tag Application Dialog
        if (pendingLora != null) {
            val lora = pendingLora!!
            var triggerWords by remember(lora) { mutableStateOf<List<String>?>(null) }
            var selectedWords by remember { mutableStateOf(emptySet<String>()) }
            var originalPrompt by remember(lora) { mutableStateOf(state.positivePrompt) }

            // Fetch json trigger words dynamically when dialog opens
            LaunchedEffect(lora) {
                viewModel.fetchLoraTriggerWords(lora) { words ->
                    triggerWords = words
                }
            }

            // Instantly apply tags visually to the main prompt when clicked
            LaunchedEffect(selectedWords, lora) {
                val hasLora = originalPrompt.contains("<lora:${lora.name}:")
                var newPrompt = originalPrompt

                if (!hasLora) {
                    val prefix = if (newPrompt.isNotEmpty() && !newPrompt.trimEnd().endsWith(",")) ", " else ""
                    newPrompt = newPrompt.trimEnd() + prefix + "<lora:${lora.name}:1.0>"
                }

                if (selectedWords.isNotEmpty()) {
                    val tags = ", " + selectedWords.joinToString(", ")
                    newPrompt += tags
                }

                viewModel.updateState { it.copy(positivePrompt = newPrompt) }
            }

            AlertDialog(
                onDismissRequest = {
                    viewModel.updateState { it.copy(positivePrompt = originalPrompt) }
                    pendingLora = null
                },
                title = { Text(lora.title, fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        if (triggerWords == null) {
                            CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                            Text("Loading trigger words...".t, fontSize = 12.sp)
                        } else {
                            if (triggerWords!!.isNotEmpty()) {
                                Text("Click tags to add/remove them from your prompt:".t, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                                LazyColumn(modifier = Modifier.heightIn(max = 250.dp).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)) {
                                    items(triggerWords!!) { word ->
                                        val isSelected = selectedWords.contains(word)
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth().clickable {
                                                selectedWords = if (isSelected) selectedWords.minus(word) else selectedWords.plus(word)
                                            }.padding(horizontal = 8.dp, vertical = 6.dp)
                                        ) {
                                            Checkbox(
                                                checked = isSelected,
                                                onCheckedChange = null
                                            )
                                            Text(word, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
                                        }
                                    }
                                }
                            } else {
                                Text("This LoRA has no trigger words associated with it. Do you still want to add it?".t, fontSize = 14.sp)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { pendingLora = null }, enabled = triggerWords != null) {
                        Text(if (triggerWords?.isEmpty() == true) "Add LoRA".t else "Done".t)
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        viewModel.updateState { it.copy(positivePrompt = originalPrompt) } // Cancel reverts the Live state
                        pendingLora = null
                    }) { Text("Cancel".t) }
                }
            )
        }

        if (fullscreenImageIndex >= 0 && sessionImages.isNotEmpty()) {
            val scope = rememberCoroutineScope()
            val pagerState = rememberPagerState(initialPage = fullscreenImageIndex, pageCount = { sessionImages.size })
            val currentFile = sessionImages.getOrNull(pagerState.currentPage) ?: ""

            LaunchedEffect(pagerState.currentPage) {
                if (fullscreenImageIndex != pagerState.currentPage) {
                    fullscreenImageIndex = pagerState.currentPage
                }
                if (currentFile.isNotEmpty()) {
                    viewModel.loadMetadataForLocalFile(currentFile)
                }
            }

            Dialog(onDismissRequest = { fullscreenImageIndex = -1 }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {

                    Row(modifier = Modifier.fillMaxWidth().background(Color(0x88000000)).padding(vertical = 4.dp, horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { fullscreenImageIndex = -1 }) { Icon(Icons.Default.Close, contentDescription = "Close".t, tint = Color.White) }
                        Text(File(currentFile).name, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))

                        IconButton(onClick = { viewModel.shareSessionImage(currentFile, context) }) {
                            Icon(Icons.Default.Share, contentDescription = "Share".t, tint = Color.White)
                        }

                        val showMetadata by viewModel.showGalleryMetadata.collectAsStateWithLifecycle()
                        IconButton(onClick = { viewModel.toggleGalleryMetadata() }) {
                            Icon(Icons.Default.Info, contentDescription = "Info".t, tint = Color.White, modifier = Modifier.then(if (showMetadata) Modifier.background(Color(0x55FFFFFF), CircleShape).padding(2.dp) else Modifier))
                        }
                        IconButton(onClick = { viewModel.downloadSessionImage(currentFile) }) {
                            Icon(Icons.Default.Save, contentDescription = "Save".t, tint = Color.White)
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

                        val showMetadata by viewModel.showGalleryMetadata.collectAsStateWithLifecycle()
                        val currentMetadata by viewModel.currentImageMetadata.collectAsStateWithLifecycle()

                        if (showMetadata) {
                            // Extract translations early in the Composable to safely pass to the onClick lambdas
                            val promptsAppliedMsg = "Prompts Applied".t
                            val modelAppliedMsg = "Model Applied: ".t
                            val lorasAppliedMsg = "LoRAs Applied".t

                            MetadataAlertDialog(
                                metadata = currentMetadata,
                                onDismiss = { viewModel.toggleGalleryMetadata() },
                                onApplyAll = null, // Set to null as "Apply All" is complex for local images without prompt picker
                                onApplyPrompt = { pos, neg ->
                                    viewModel.updateState { it.copy(positivePrompt = pos, negativePrompt = neg) }
                                    Toast.makeText(context, promptsAppliedMsg, Toast.LENGTH_SHORT).show()
                                    viewModel.toggleGalleryMetadata()
                                },
                                onApplyModel = { model ->
                                    viewModel.changeCheckpoint(model)
                                    Toast.makeText(context, modelAppliedMsg + model, Toast.LENGTH_SHORT).show()
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
                                    Toast.makeText(context, lorasAppliedMsg, Toast.LENGTH_SHORT).show()
                                    viewModel.toggleGalleryMetadata()
                                }
                            )
                        }

                        val currentIndex = pagerState.currentPage

                        if (currentIndex > 0) {
                            IconButton(
                                onClick = {
                                    if (config.swipeToBrowseGallery) { scope.launch { pagerState.animateScrollToPage(currentIndex - 1) } }
                                    else {
                                        fullscreenImageIndex = currentIndex - 1
                                        scope.launch { pagerState.scrollToPage(currentIndex - 1) }
                                    }
                                },
                                modifier = Modifier.align(Alignment.CenterStart).padding(8.dp).background(Color(0x88000000), CircleShape)
                            ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, tint = Color.White) }
                        }

                        if (currentIndex < sessionImages.size - 1) {
                            IconButton(
                                onClick = {
                                    if (config.swipeToBrowseGallery) { scope.launch { pagerState.animateScrollToPage(currentIndex + 1) } }
                                    else {
                                        fullscreenImageIndex = currentIndex + 1
                                        scope.launch { pagerState.scrollToPage(currentIndex + 1) }
                                    }
                                },
                                modifier = Modifier.align(Alignment.CenterEnd).padding(8.dp).background(Color(0x88000000), CircleShape)
                            ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color.White) }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(viewModel: ForgeViewModel, navController: NavHostController) {
    val queue by ForgeState.generationQueue.collectAsStateWithLifecycle()
    val isGenerating by ForgeState.isGenerating.collectAsStateWithLifecycle()
    val progress by ForgeState.progress.collectAsStateWithLifecycle()
    val status by ForgeState.statusText.collectAsStateWithLifecycle()
    val currentEta by ForgeState.currentEta.collectAsStateWithLifecycle()

    val onBackClick = rememberDebounced { navController.popBackStack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Generation Queue".t + " (${queue.size})", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(8.dp)) {

            if (isGenerating || progress > 0f || status.contains("Cooldown")) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Currently Generating".t, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha=0.2f)
                        )
                        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${(progress * 100).toInt()}% • $status", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            if (currentEta > 0) Text("ETA: ${String.format(Locale.US, "%.1f", currentEta)}s", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
            }

            if (queue.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Queue is empty.".t, color = Color.Gray)
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(queue) { item ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // Up and Down controls for Queue Drag-and-Drop / Reordering
                                Column(modifier = Modifier.padding(start = 4.dp)) {
                                    IconButton(onClick = { viewModel.moveQueueItemUp(item.id) }, modifier = Modifier.size(24.dp)) {
                                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move Up".t)
                                    }
                                    IconButton(onClick = { viewModel.moveQueueItemDown(item.id) }, modifier = Modifier.size(24.dp)) {
                                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move Down".t)
                                    }
                                }
                                Column(modifier = Modifier.padding(12.dp).weight(1f)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                                        Text("Prompt: ".t + item.positivePrompt, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                        IconButton(onClick = { viewModel.removeFromQueue(item.id) }, modifier = Modifier.size(32.dp)) {
                                            Icon(Icons.Default.Delete, contentDescription = "Discard".t, tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    val modelStr = item.payload.override_settings.sdModelCheckpoint ?: "Current Default"
                                    Text("${"Model".t}: $modelStr", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                    Text("${"Steps".t}: ${item.payload.steps} | ${"Batch Count".t}: ${item.payload.n_iter} | ${"CFG Scale".t}: ${item.payload.cfg_scale} | ${"Size".t}: ${item.payload.width}x${item.payload.height}", fontSize = 10.sp, color = Color.Gray)
                                }
                            }
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
    val state by viewModel.appState.collectAsStateWithLifecycle()
    val files by viewModel.galleryFiles.collectAsStateWithLifecycle()
    val currentPath by viewModel.currentGalleryPath.collectAsStateWithLifecycle()
    val isLoading by viewModel.isGalleryLoading.collectAsStateWithLifecycle()
    val errorMsg by viewModel.galleryError.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()
    val galleryMode by viewModel.galleryMode.collectAsStateWithLifecycle()

    val isPromptPicker = galleryMode == GalleryMode.PROMPT_PICKER

    var selectedImage by remember { mutableStateOf<GalleryItem?>(null) }
    var isGridView by remember { mutableStateOf(true) }
    var showGridSlider by remember { mutableStateOf(false) }

    val selectedItems = remember { mutableStateListOf<GalleryItem>() }
    val isSelectionMode = selectedItems.isNotEmpty() && !isPromptPicker

    val context = LocalContext.current

    val onBackClick = rememberDebounced {
        if (isPromptPicker) viewModel.setGalleryMode(GalleryMode.NORMAL)
        navController.popBackStack()
    }

    BackHandler(enabled = isPromptPicker) {
        viewModel.setGalleryMode(GalleryMode.NORMAL)
        navController.popBackStack()
    }

    Scaffold(
        topBar = {
            Column {
                if (isSelectionMode) {
                    TopAppBar(
                        title = { Text("${selectedItems.size} " + "Selected".t, fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                        navigationIcon = {
                            IconButton(onClick = { selectedItems.clear() }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear Selection".t)
                            }
                        },
                        actions = {
                            IconButton(onClick = {
                                val allImages = files.filter { !it.isDir }
                                if (selectedItems.size == allImages.size) selectedItems.clear()
                                else { selectedItems.clear(); selectedItems.addAll(allImages) }
                            }) {
                                Icon(Icons.Default.SelectAll, contentDescription = "Select All".t)
                            }
                            IconButton(onClick = {
                                selectedItems.forEach { item -> viewModel.downloadImage(item) }
                                selectedItems.clear()
                            }) {
                                Icon(Icons.Default.Download, contentDescription = "Download Selected".t)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    )
                } else {
                    TopAppBar(
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (!isPromptPicker) {
                                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(24.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Text(if (isPromptPicker) "Select Image for Prompt".t else "Gallery".t, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = onBackClick) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                            }
                        },
                        actions = {
                            IconButton(onClick = { isGridView = !isGridView }) {
                                Icon(if (isGridView) Icons.Default.List else Icons.Default.GridView, contentDescription = "Toggle View".t)
                            }
                            if (isGridView) {
                                IconButton(onClick = { showGridSlider = !showGridSlider }) {
                                    Icon(Icons.Default.ViewColumn, contentDescription = "Adjust Columns".t)
                                }
                            }
                        }
                    )
                }

                AnimatedVisibility(visible = showGridSlider && isGridView && !isSelectionMode) {
                    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Columns: ".t + "${config.galleryGridColumns}", modifier = Modifier.width(100.dp), fontWeight = FontWeight.Bold, fontSize = 12.sp)
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
                        Icon(Icons.Default.ArrowUpward, null)
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
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Folder is empty.".t, color = Color.Gray) }
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
                                            if (isPromptPicker && !file.isDir) {
                                                viewModel.recoverPromptFromImage(file)
                                                viewModel.setGalleryMode(GalleryMode.NORMAL)
                                                navController.popBackStack()
                                            } else if (isSelectionMode && !file.isDir) {
                                                if (isSelected) selectedItems.remove(file) else selectedItems.add(file)
                                            } else if (file.isDir) {
                                                viewModel.fetchGalleryFolder(file.fullpath)
                                            } else {
                                                selectedImage = file
                                            }
                                        },
                                        onLongClick = {
                                            if (!file.isDir && !isPromptPicker) {
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
                                    Text(
                                        text = file.name,
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x88000000)).padding(4.dp)
                                    )
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
                                            if (isPromptPicker && !file.isDir) {
                                                viewModel.recoverPromptFromImage(file)
                                                viewModel.setGalleryMode(GalleryMode.NORMAL)
                                                navController.popBackStack()
                                            } else if (isSelectionMode && !file.isDir) {
                                                if (isSelected) selectedItems.remove(file) else selectedItems.add(file)
                                            } else if (file.isDir) {
                                                viewModel.fetchGalleryFolder(file.fullpath)
                                            } else {
                                                selectedImage = file
                                            }
                                        },
                                        onLongClick = {
                                            if (!file.isDir && !isPromptPicker) {
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

    if (selectedImage != null && !isPromptPicker) {
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
                    IconButton(onClick = { selectedImage = null }) { Icon(Icons.Default.Close, contentDescription = "Close".t, tint = Color.White) }

                    Text(selectedImage!!.name, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))

                    IconButton(onClick = { viewModel.shareImage(selectedImage!!, context) }) {
                        Icon(Icons.Default.Share, contentDescription = "Share".t, tint = Color.White)
                    }

                    val showMetadata by viewModel.showGalleryMetadata.collectAsStateWithLifecycle()
                    IconButton(onClick = { viewModel.toggleGalleryMetadata() }) {
                        Icon(Icons.Default.Info, contentDescription = "Info".t, tint = Color.White, modifier = Modifier.then(if (showMetadata) Modifier.background(Color(0x55FFFFFF), CircleShape).padding(2.dp) else Modifier))
                    }
                    IconButton(onClick = { viewModel.downloadImage(selectedImage!!) }) {
                        Icon(Icons.Default.Save, contentDescription = "Save".t, tint = Color.White)
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

                    val showMetadata by viewModel.showGalleryMetadata.collectAsStateWithLifecycle()
                    val currentMetadata by viewModel.currentImageMetadata.collectAsStateWithLifecycle()

                    if (showMetadata) {
                        // Extract translations early in the Composable to safely pass to the onClick lambdas
                        val promptsAppliedMsg = "Prompts Applied".t
                        val modelAppliedMsg = "Model Applied: ".t
                        val lorasAppliedMsg = "LoRAs Applied".t

                        MetadataAlertDialog(
                            metadata = currentMetadata,
                            onDismiss = { viewModel.toggleGalleryMetadata() },
                            onApplyAll = {
                                viewModel.recoverPromptFromImage(selectedImage!!)
                                viewModel.toggleGalleryMetadata()
                                selectedImage = null
                                navController.popBackStack()
                            },
                            onApplyPrompt = { pos, neg ->
                                viewModel.updateState { it.copy(positivePrompt = pos, negativePrompt = neg) }
                                Toast.makeText(context, promptsAppliedMsg, Toast.LENGTH_SHORT).show()
                                viewModel.toggleGalleryMetadata()
                            },
                            onApplyModel = { model ->
                                viewModel.changeCheckpoint(model)
                                Toast.makeText(context, modelAppliedMsg + model, Toast.LENGTH_SHORT).show()
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
                                Toast.makeText(context, lorasAppliedMsg, Toast.LENGTH_SHORT).show()
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
                        ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, tint = Color.White) }
                    }

                    if (currentIndex < imageList.size - 1 && currentIndex != -1) {
                        IconButton(
                            onClick = {
                                if (config.swipeToBrowseGallery) { scope.launch { pagerState.animateScrollToPage(currentIndex + 1) } }
                                else { selectedImage = imageList[currentIndex + 1] }
                            },
                            modifier = Modifier.align(Alignment.CenterEnd).padding(8.dp).background(Color(0x88000000), CircleShape)
                        ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color.White) }
                    }
                }
            }
        }
    }
}