package com.example.forgegen

import com.example.forgegen.ui.screens.*
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
 * The image preview over the PROMPT, GENERATION and LORAS cards (MainCards.kt, 3.0.0), with the generate bar fixed at
 * the bottom; it gives way to the keyboard and the tag suggestions while a prompt is typed.
 * ============================================================================ */

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: ForgeViewModel,
    navController: NavHostController,
) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val appState = viewModel.appState.collectAsStateWithLifecycle()
    // Every typed character changes the state (3.4.0): the cards below the prompt get it without the prompts, so they
    // are not drawn again while a prompt is typed.
    val generationState by remember { derivedStateOf { appState.value.withoutPrompts() } }
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val searchEndsAt by viewModel.searchEndsAt.collectAsStateWithLifecycle()
    val pingMs by viewModel.pingMs.collectAsStateWithLifecycle()
    val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val currentEta by viewModel.currentEta.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val serverMemory by viewModel.serverMemory.collectAsStateWithLifecycle()
    val unloadingModel by viewModel.unloadingModel.collectAsStateWithLifecycle()
    // Restart Forge (3.3.0): the top bar counts the time while Forge restarts.
    val restartingSince by viewModel.restartingSince.collectAsStateWithLifecycle()
    val serverInfo by viewModel.serverInfo.collectAsStateWithLifecycle()
    var confirmRestart by remember { mutableStateOf(false) }

    val isServerBusy by viewModel.isServerBusy.collectAsStateWithLifecycle()
    val generationQueue by viewModel.generationQueue.collectAsStateWithLifecycle()
    val oomAlert by viewModel.oomAlert.collectAsStateWithLifecycle()
    val isQueuePaused by viewModel.isQueuePaused.collectAsStateWithLifecycle()
    val queueStopped = oomAlert || (isQueuePaused && generationQueue.any { it.status != GenerationStatus.FAILED })

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
    val serverModules by viewModel.serverModules.collectAsStateWithLifecycle()
    val moduleSupport by viewModel.moduleSupport.collectAsStateWithLifecycle()

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
    // Only the embeddings loaded for the current model are suggested (3.1.0).
    val embeddingList by viewModel.embeddings.collectAsStateWithLifecycle()

    var fullscreenImageIndex by remember { mutableIntStateOf(-1) }

    val context = LocalContext.current
    val imageLoader = context.imageLoader

    // Focus manager to clear focus and dismiss keyboard
    val focusManager = LocalFocusManager.current

    // The pictures of the model and the LoRAs in use, loaded ahead once the app knows which file each has
    // (ResourcePreviews, 3.0.1); unknown ones are found by the rows themselves.
    LaunchedEffect(selectedModel, activeLoras, models, availableLoras, config.resourcePictures) {
        if (!config.resourcePictures) return@LaunchedEffect // Settings > Features > Model and LoRA Pictures (3.4.0)
        launch(Dispatchers.IO) {
            val paths =
                listOfNotNull(models.find { it.name == selectedModel || it.title == selectedModel }?.path?.let { it to false }) +
                    activeLoras.mapNotNull { active -> availableLoras.find { it.name == active.name }?.path?.let { it to true } }
            paths.forEach { (path, isLora) ->
                val url = ResourcePreviews.known(viewModel.previewCandidates(path, isLora)) ?: return@forEach
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

    val onGalleryClick =
        rememberDebounced {
            viewModel.openGallery(GalleryMode.NORMAL)
            navController.navigate("gallery")
        }

    var showSettingsOverlay by rememberSaveable { mutableStateOf(false) }
    val onSettingsClick = rememberDebounced { showSettingsOverlay = !showSettingsOverlay }
    val onQueueClick = rememberDebounced { navController.navigate("queue") }

    val blurModifier = if (isRestoringPrompt != IndicatorState.IDLE) Modifier.blur(10.dp) else Modifier
    val density = LocalDensity.current

    var showMemorySheet by remember { mutableStateOf(false) }

    // The rows of the cards left open (the negative prompt, sampling, size and batch), kept between launches.
    val openRows = config.mainOpenRows
    val onToggleRow: (String) -> Unit = { row ->
        val current = viewModel.config.value
        val rows = current.mainOpenRows
        viewModel.saveConfig(current.copy(mainOpenRows = if (row in rows) rows - row else rows + row))
    }
    // Reset to Defaults also forgets the tags switched off in the prompts.
    var promptResets by remember { mutableIntStateOf(0) }

    // Estimate image pixel dimensions dynamically and warn the user about potential CUDA VRAM out-of-memory errors.
    // Only crossing the limit warns; otherwise every slider step above it (and every return to this screen) showed a toast.
    var wasOverVramLimit by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // Followed here, not read by the screen: the screen is not drawn again for each change of the state.
        snapshotFlow {
            val basePixels = generationState.width * generationState.height
            if (generationState.hiresFix) basePixels * (generationState.hiresScale * generationState.hiresScale) else basePixels.toFloat()
        }.collect { finalPixels ->
            // Estimate: Above 2.5 million pixels with Hires it starts to be dangerous for standard 8GB cards.
            val overLimit = finalPixels > 2500000
            if (overLimit && !wasOverVramLimit) {
                viewModel.showToast("High VRAM usage warning. Risk of server OOM.")
            }
            wasOverVramLimit = overLimit
        }
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
        val keyboardUp = WindowInsets.isImeVisible
        val typingPrompt = promptTyping.value != null && keyboardUp
        val suggesting = typingPrompt && config.tagSuggestions
        val statusBarDp = with(density) { WindowInsets.statusBarsIgnoringVisibility.getTop(this).toDp() }
        val keyboardDp = with(density) { WindowInsets.imeAnimationTarget.getBottom(this).toDp() }
        val typingLayout =
            if (suggesting) TypingLayout.of((maxHeight - statusBarDp - keyboardDp).value, statusBarDp.value) else TypingLayout()
        // Any field (the seed too) keeps the content above the keyboard; a prompt with suggestions also above the strip.
        val keyboardNowDp = with(density) { WindowInsets.ime.getBottom(this).toDp() }
        val keyboardBottom = if (suggesting && !typingLayout.oneBar) keyboardNowDp + typingLayout.stripDp.dp else keyboardNowDp
        val statusBarNowDp = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
        HideStatusBarWhile(typingLayout.hideStatusBar)
        // The server sends the live preview only while it can be seen here (3.4.0): not under the settings, not while
        // typing folds it away, not on the other screens (this one leaves the composition then).
        val previewVisible = !showSettingsOverlay && !typingLayout.hidePreview
        DisposableEffect(previewVisible) {
            viewModel.setPreviewShown(previewVisible)
            onDispose { viewModel.setPreviewShown(false) }
        }

        CompositionLocalProvider(LocalPromptTyping provides promptTyping) {
            Scaffold(
                modifier = blurModifier,
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    AnimatedVisibility(visible = !typingLayout.hideTopBar, enter = expandVertically(), exit = shrinkVertically()) {
                        MainTopBar(
                            connection = connection,
                            pingMs = pingMs,
                            searchEndsAt = searchEndsAt,
                            memory = serverMemory,
                            onConnectionClick = { viewModel.openServerDialog() },
                            onMemoryClick = { showMemorySheet = true },
                            onGalleryClick = onGalleryClick,
                            onSettingsClick = onSettingsClick,
                            restartingSince = restartingSince,
                            showMeters = config.memoryMeters,
                        )
                    }
                },
                bottomBar = {
                    // Out of the way while typing and while the settings are open.
                    AnimatedVisibility(
                        visible = !keyboardUp && !showSettingsOverlay,
                        enter = slideInVertically { it } + fadeIn(),
                        exit = slideOutVertically { it } + fadeOut(),
                    ) {
                        Column {
                        // What stops or holds the queue (3.0.0-1: it replaced the red cards at the top).
                        QueueStatusStrip(viewModel = viewModel, onOpenQueue = onQueueClick)
                        GenerateBar(
                            viewModel = viewModel,
                            state = generationState,
                            queueSize = generationQueue.count { it.status != GenerationStatus.FAILED },
                            isActivelyGenerating = isGenerating || isServerBusy || progress > 0f,
                            progress = progress,
                            currentEta = currentEta,
                            onQueueClick = onQueueClick,
                            onPresetsClick = { navController.navigate("presets") },
                            onReset = {
                                viewModel.resetToDefaults()
                                promptResets++
                            },
                            queueStopped = queueStopped,
                        )
                        }
                    }
                },
            ) { padding ->
                // Without the top bar (typing with little room) the content starts under the status bar.
                val topPadding = if (typingLayout.hideTopBar) statusBarNowDp else padding.calculateTopPadding()
                val bottomPadding = maxOf(padding.calculateBottomPadding(), keyboardBottom)
                Box(modifier = Modifier.padding(top = topPadding, bottom = bottomPadding).fillMaxSize()) {
                    val scrollState = rememberScrollState()

                    Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 16.dp, vertical = 4.dp)) {
                        // Folded away (not removed, so it keeps its blur) while typing leaves it no room.
                        val previewHeight by animateDpAsState(if (typingLayout.hidePreview) 0.dp else PREVIEW_HEIGHT, label = "preview")
                        Box(Modifier.fillMaxWidth().height(previewHeight).clipToBounds()) {
                            Box(Modifier.wrapContentHeight(Alignment.Top, unbounded = true)) {
                                PreviewSection(
                                    isGenerating = isGenerating,
                                    livePreview = livePreview.takeIf { config.livePreview },
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

                        PromptCard(
                            viewModel = viewModel,
                            state = appState.value,
                            config = config,
                            promptHistory = promptHistory,
                            openRows = openRows,
                            onToggleRow = onToggleRow,
                            resetKey = promptResets,
                        )

                        GenerationCard(
                            viewModel = viewModel,
                            state = generationState,
                            config = config,
                            models = models,
                            selectedModel = selectedModel,
                            samplers = samplers,
                            schedulers = schedulers,
                            upscalers = upscalers,
                            modules = serverModules,
                            moduleSupport = moduleSupport,
                            openRows = openRows,
                            onToggleRow = onToggleRow,
                        )

                        LorasCard(
                            viewModel = viewModel,
                            availableLoras = availableLoras,
                            activeLoras = activeLoras,
                        )

                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    AnimatedVisibility(
                        visible = showSettingsOverlay,
                        enter = expandVertically(expandFrom = Alignment.Top, animationSpec = tween(200, easing = LinearOutSlowInEasing)),
                        exit = shrinkVertically(shrinkTowards = Alignment.Top, animationSpec = tween(200, easing = FastOutLinearInEasing)),
                        modifier = Modifier.zIndex(100f),
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
                embeddings = if (config.embeddings) embeddingList.loaded else emptyList(),
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

        // Opened by the memory meters in the top bar (3.0.0-4: it replaced the Unload icon and its dialog).
        if (showMemorySheet) {
            ServerMemorySheet(
                memory = serverMemory,
                modelName = ModelSettingsRules.key(selectedModel),
                unloading = unloadingModel,
                busy = isGenerating || isServerBusy || progress > 0f,
                onUnload = { viewModel.unloadCheckpoint() },
                onDismiss = { showMemorySheet = false },
                canRestart = serverInfo?.canRestart,
                restarting = restartingSince > 0,
                onRestart = { confirmRestart = true },
                unloadAfter = config.unloadAfterQueue,
                onUnloadAfter = viewModel::setUnloadAfterQueue,
                coldStart = viewModel.coldStartMs(selectedModel)?.let { QueueEstimate.formatAbout(it) },
            )
            // Whether Forge can be restarted from here (/sdapi/v1/cmd-flags), read once per server; the memory now, as
            // it is read only while the meters show (3.4.0).
            LaunchedEffect(Unit) {
                viewModel.loadServerInfo()
                viewModel.readServerMemory()
            }
        }
        if (confirmRestart) {
            RestartForgeDialog(viewModel, generating = isGenerating, onDismiss = {
                confirmRestart = false
                showMemorySheet = false
            })
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

/** The state without the prompts and styles: what the cards below the prompt use (3.4.0). */
private fun AppState.withoutPrompts() = copy(positivePrompt = "", negativePrompt = "", styles = emptyList())
