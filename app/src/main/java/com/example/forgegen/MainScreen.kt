package com.example.forgegen

import com.example.forgegen.ui.screens.*
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.zIndex
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.NavHostController
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.example.forgegen.ui.components.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/* ============================================================================
 * MAIN SCREEN (Orchestrator)
 * UI shell for the main workspace. Core layout sections are imported from MainComponents.kt.
 * Integrates BottomSheetScaffold to overlay parameters and generation controls.
 * ============================================================================ */

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: ForgeViewModel,
    navController: NavHostController,
) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val state by viewModel.appState.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val searchEndsAt by viewModel.searchEndsAt.collectAsStateWithLifecycle()
    val pingMs by viewModel.pingMs.collectAsStateWithLifecycle()
    val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val currentEta by viewModel.currentEta.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val vram by viewModel.vramUsage.collectAsStateWithLifecycle()

    val isServerBusy by viewModel.isServerBusy.collectAsStateWithLifecycle()
    val generationQueue by viewModel.generationQueue.collectAsStateWithLifecycle()

    val sessionImages by viewModel.sessionImages.collectAsStateWithLifecycle()
    val currentSessionIndex by viewModel.currentSessionIndex.collectAsStateWithLifecycle()
    val livePreview by viewModel.livePreviewImage.collectAsStateWithLifecycle()
    val isShowingGridPreview by viewModel.isShowingGridPreview.collectAsStateWithLifecycle()

    val batchStart by viewModel.currentBatchStartIndex.collectAsStateWithLifecycle()
    val batchEnd by viewModel.currentBatchEndIndex.collectAsStateWithLifecycle()

    val models by viewModel.models.collectAsStateWithLifecycle()
    val selectedModel by viewModel.selectedModel.collectAsStateWithLifecycle()
    val samplers by viewModel.samplers.collectAsStateWithLifecycle()
    val schedulers by viewModel.schedulers.collectAsStateWithLifecycle()
    val availableLoras by viewModel.availableLoras.collectAsStateWithLifecycle()
    val activeLoras by viewModel.activeLoras.collectAsStateWithLifecycle()
    val upscalers by viewModel.upscalers.collectAsStateWithLifecycle()

    val isRestoringPrompt by viewModel.isRestoringPrompt.collectAsStateWithLifecycle()
    val promptHistory by viewModel.promptHistory.collectAsStateWithLifecycle()

    // Tag suggestions (2.4.2): the prompt field being typed in, and what the strip above the keyboard offers.
    val promptTyping = remember { PromptTyping() }
    val tagList by viewModel.tagList.collectAsStateWithLifecycle()
    val tagListStatus by viewModel.tagListStatus.collectAsStateWithLifecycle()
    val tagInsertRules by viewModel.tagInsertRules.collectAsStateWithLifecycle()
    val wildcards by viewModel.wildcards.collectAsStateWithLifecycle()
    val wildcardNames = remember(wildcards) { wildcards.map { it.name } }
    val loraNames = remember(availableLoras) { availableLoras.map { it.name } }

    var fullscreenImageIndex by remember { mutableIntStateOf(-1) }

    val context = LocalContext.current
    val imageLoader = context.imageLoader

    // Focus manager to clear focus and dismiss keyboard
    val focusManager = LocalFocusManager.current

    LaunchedEffect(selectedModel, activeLoras, models, availableLoras) {
        launch(Dispatchers.IO) {
            val currentModelResource = models.find { it.name == selectedModel || it.title == selectedModel }
            if (currentModelResource != null) {
                val url = viewModel.getPreviewUrl(currentModelResource.path, isLora = false)
                if (url.isNotEmpty()) {
                    val request =
                        ImageRequest
                            .Builder(context)
                            .data(url)
                            .size(150)
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .build()
                    imageLoader.enqueue(request)
                }
            }

            activeLoras.forEach { activeLora ->
                val loraResource = availableLoras.find { it.name == activeLora.name }
                if (loraResource != null) {
                    val url = viewModel.getPreviewUrl(loraResource.path, isLora = true)
                    if (url.isNotEmpty()) {
                        val request =
                            ImageRequest
                                .Builder(context)
                                .data(url)
                                .size(150)
                                .memoryCachePolicy(CachePolicy.ENABLED)
                                .diskCachePolicy(CachePolicy.ENABLED)
                                .build()
                        imageLoader.enqueue(request)
                    }
                }
            }
        }
    }

    val onGalleryClick =
        rememberDebounced {
            viewModel.openGallery(GalleryMode.NORMAL)
            navController.navigate("gallery")
        }

    var showSettingsOverlay by rememberSaveable { mutableStateOf(false) }
    val onSettingsClick = rememberDebounced { showSettingsOverlay = !showSettingsOverlay }
    val onQueueClick = rememberDebounced { navController.navigate("queue") }

    val blurModifier = if (isRestoringPrompt != IndicatorState.IDLE) Modifier.blur(10.dp) else Modifier

    // Calculate device navigation bar height in pixels to correctly align bottom elements
    val density = LocalDensity.current
    val navBarHeightDp =
        with(density) {
            WindowInsets.navigationBars.getBottom(this).toDp()
        }

    // Elevate the sheet handle (40.dp peek offset) above the system navigation bar to prevent overlaps
    val peekHeight = 40.dp + navBarHeightDp

    // Initialize bottom sheet state with expand status based on AppConfig preferences
    val sheetState =
        rememberStandardBottomSheetState(
            initialValue = if (config.bottomSheetExpandedByDefault) SheetValue.Expanded else SheetValue.PartiallyExpanded,
            skipHiddenState = true,
        )
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)

    var showUnloadDialog by remember { mutableStateOf(false) }

    // Estimate image pixel dimensions dynamically and warn the user about potential CUDA VRAM out-of-memory errors.
    // Only crossing the limit warns; otherwise every slider step above it (and every return to this screen) showed a toast.
    var wasOverVramLimit by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.width, state.height, state.hiresFix, state.hiresScale) {
        val basePixels = state.width * state.height
        val finalPixels = if (state.hiresFix) basePixels * (state.hiresScale * state.hiresScale) else basePixels.toFloat()

        // Estimate: Above 2.5 million pixels with Hires it starts to be dangerous for standard 8GB cards.
        val overLimit = finalPixels > 2500000
        if (overLimit && !wasOverVramLimit) {
            viewModel.showToast("High VRAM usage warning. Risk of server OOM.")
        }
        wasOverVramLimit = overLimit
    }

    // Root screen layout container configured with tap gestures to dismiss the virtual keyboard
    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        // When the user clicks anywhere else, remove focus and hide the keyboard
                        focusManager.clearFocus()
                    })
                },
    ) {
        // While a prompt is typed with the keyboard up, the screen ends on the keyboard (and the suggestion strip), so
        // the field stays in view above them; with little room the preview, then the top bar give way (TypingLayout).
        val typingPrompt = promptTyping.value != null && WindowInsets.isImeVisible
        val suggesting = typingPrompt && config.tagSuggestions
        val statusBarDp = with(density) { WindowInsets.statusBarsIgnoringVisibility.getTop(this).toDp() }
        val keyboardDp = with(density) { WindowInsets.imeAnimationTarget.getBottom(this).toDp() }
        val typingLayout =
            if (suggesting) TypingLayout.of((maxHeight - statusBarDp - keyboardDp).value, statusBarDp.value) else TypingLayout()
        val keyboardNowDp = with(density) { WindowInsets.ime.getBottom(this).toDp() }
        val typingBottom =
            when {
                !typingPrompt -> 0.dp
                suggesting && !typingLayout.oneBar -> keyboardNowDp + typingLayout.stripDp.dp
                else -> keyboardNowDp
            }
        val statusBarNowDp = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
        HideStatusBarWhile(typingLayout.hideStatusBar)

        CompositionLocalProvider(LocalPromptTyping provides promptTyping) {
            BottomSheetScaffold(
                modifier = blurModifier,
                scaffoldState = scaffoldState,
                sheetPeekHeight = peekHeight,
                sheetContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                topBar = {
                    val isActivelyGenerating = isGenerating || isServerBusy || progress > 0f
                    AnimatedVisibility(visible = !typingLayout.hideTopBar, enter = expandVertically(), exit = shrinkVertically()) {
                        ForgeTopAppBar(
                            connection = connection,
                            pingMs = pingMs,
                            searchEndsAt = searchEndsAt,
                            onConnectionClick = { viewModel.openServerDialog() },
                            vram = vram,
                            isActivelyGenerating = isActivelyGenerating,
                            onUnloadClick = { showUnloadDialog = true },
                            onGalleryClick = onGalleryClick,
                            onSettingsClick = onSettingsClick,
                        )
                    }
                },
                sheetContent = {
                    BottomControlsSection(
                        viewModel = viewModel,
                        state = state,
                        generationQueueSize = generationQueue.count { it.status != GenerationStatus.FAILED },
                        isActivelyGenerating = isGenerating || isServerBusy || progress > 0f,
                        progress = progress,
                        currentEta = currentEta,
                        onQueueClick = onQueueClick,
                        onNavigateToPresets = { navController.navigate("presets") },
                    )
                },
            ) { padding ->
                // The scaffold places the content under the top bar; without it (typing with little room) the content
                // starts under the status bar.
                val topPadding = if (typingLayout.hideTopBar) statusBarNowDp else padding.calculateTopPadding()
                val bottomPadding = maxOf(padding.calculateBottomPadding(), typingBottom)
                Box(modifier = Modifier.padding(top = topPadding, bottom = bottomPadding).fillMaxSize()) {
                    val scrollState = rememberScrollState()

                    Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(8.dp)) {
                        OomAlertSection(viewModel)

                        // Folded away (not removed, so it keeps its blur) while typing leaves it no room.
                        val previewHeight by animateDpAsState(if (typingLayout.hidePreview) 0.dp else 240.dp, label = "preview")
                        Box(Modifier.fillMaxWidth().height(previewHeight).clipToBounds()) {
                            Box(Modifier.wrapContentHeight(Alignment.Top, unbounded = true)) {
                                PreviewSection(
                                    isGenerating = isGenerating,

                                    livePreview = livePreview,
                                    isShowingGridPreview = isShowingGridPreview,
                                    sessionImages = sessionImages,
                                    batchStart = batchStart,
                                    batchEnd = batchEnd,
                                    currentSessionIndex = currentSessionIndex,
                                    onDismissGrid = { viewModel.dismissGridPreview(it) },
                                    onFullscreen = { fullscreenImageIndex = it },
                                    onPrev = { viewModel.sessionPrev() },
                                    onNext = { viewModel.sessionNext() },
                                    onRecoverLast = { viewModel.recoverLastPrompt() },
                                    onRecoverFromGallery = {
                                        viewModel.openGallery(GalleryMode.PROMPT_PICKER)
                                        navController.navigate("gallery")
                                    },
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        PromptsSection(
                            viewModel = viewModel,
                            state = state,
                            config = config,
                            promptHistory = promptHistory,
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        GenerationSettingsSection(
                            viewModel = viewModel,
                            state = state,
                            models = models,
                            selectedModel = selectedModel,
                            samplers = samplers,
                            schedulers = schedulers,
                            upscalers = upscalers,
                            config = config,
                        )

                        LorasSection(
                            viewModel = viewModel,
                            availableLoras = availableLoras,
                            activeLoras = activeLoras,
                            config = config,
                        )

                        // Spacer at the bottom to ensure contents can clear the bottom sheet peek height when scrolled
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showSettingsOverlay,
                        enter = androidx.compose.animation.expandVertically(expandFrom = androidx.compose.ui.Alignment.Top, animationSpec = tween(200, easing = androidx.compose.animation.core.LinearOutSlowInEasing)),
                        exit = androidx.compose.animation.shrinkVertically(shrinkTowards = androidx.compose.ui.Alignment.Top, animationSpec = tween(200, easing = androidx.compose.animation.core.FastOutLinearInEasing)),
                        modifier = Modifier.zIndex(100f)
                    ) {
                        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            SetupScreen(
                                viewModel = viewModel,
                                onDismiss = { showSettingsOverlay = false },
                                belowTopBar = true,
                            )
                        }
                    }
                }
            }
        }

        // Tag suggestions, docked on the keyboard.
        if (suggesting) {
            TagSuggestionStrip(
                typing = promptTyping,
                tags = tagList,
                rules = tagInsertRules,
                status = tagListStatus,
                wildcards = wildcardNames,
                loras = loraNames,
                oneBar = typingLayout.oneBar,
                height = typingLayout.stripDp.dp,
                modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.ime),
            )
        }

        // --- ROOT DIALOGS ---

        if (fullscreenImageIndex >= 0 && sessionImages.isNotEmpty()) {
            FullscreenImageViewer(
                viewModel = viewModel,
                config = config,
                sessionImages = sessionImages,
                initialIndex = fullscreenImageIndex,
                onDismiss = { fullscreenImageIndex = -1 },
            )
        }

        if (showUnloadDialog) {
            AlertDialog(
                onDismissRequest = { showUnloadDialog = false },
                title = { Text("Unload Model from VRAM", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        "Are you sure you want to unload the active model from GPU VRAM to free up server memory?",
                        fontSize = 14.sp,
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showUnloadDialog = false
                            viewModel.unloadCheckpoint()
                        },
                    ) {
                        Text("Unload", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showUnloadDialog = false }) {
                        Text("Cancel")
                    }
                },
            )
        }
    }
}

/**
 * Hides the status bar while [hidden] (typing a prompt with the keyboard leaving no room for the suggestion strip,
 * TypingLayout); a swipe from the top shows it for a moment.
 */
@Composable
private fun HideStatusBarWhile(hidden: Boolean) {
    val view = LocalView.current
    DisposableEffect(hidden) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val behavior = controller?.systemBarsBehavior
        if (hidden && controller != null) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.statusBars())
        }
        onDispose {
            if (hidden && controller != null) {
                controller.show(WindowInsetsCompat.Type.statusBars())
                if (behavior != null) controller.systemBarsBehavior = behavior
            }
        }
    }
}
