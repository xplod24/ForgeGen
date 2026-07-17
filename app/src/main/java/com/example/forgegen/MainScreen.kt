package com.example.forgegen

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
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
    val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()
    val pingMs by viewModel.pingMs.collectAsStateWithLifecycle()
    val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val currentEta by viewModel.currentEta.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val vram by viewModel.vramUsage.collectAsStateWithLifecycle()
    val ram by viewModel.ramUsage.collectAsStateWithLifecycle()

    val isServerBusy by viewModel.isServerBusy.collectAsStateWithLifecycle()
    val generationQueue by viewModel.generationQueue.collectAsStateWithLifecycle()

    val sessionImages by viewModel.sessionImages.collectAsStateWithLifecycle()
    val currentSessionIndex by viewModel.currentSessionIndex.collectAsStateWithLifecycle()
    val livePreviewBase64 by viewModel.livePreviewImage.collectAsStateWithLifecycle()
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

    var pendingLora by remember { mutableStateOf<ApiResource?>(null) }
    var fullscreenImageIndex by remember { mutableIntStateOf(-1) }

    var tagsPopupHash by remember { mutableStateOf<String?>(null) }
    var tagsPopupName by remember { mutableStateOf("") }
    var availableTagsForPopup by remember { mutableStateOf<List<String>>(emptyList()) }

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
            viewModel.setGalleryMode(GalleryMode.NORMAL)
            viewModel.fetchGalleryFolder(config.galleryPath)
            navController.navigate("gallery")
        }

    val onSettingsClick = rememberDebounced { navController.navigate("setup") }
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

    // Estimate image pixel dimensions dynamically and warn the user about potential CUDA VRAM out-of-memory errors
    LaunchedEffect(state.width, state.height, state.hiresFix, state.hiresScale) {
        val basePixels = state.width * state.height
        val finalPixels = if (state.hiresFix) basePixels * (state.hiresScale * state.hiresScale) else basePixels.toFloat()

        // Estymata: Powyżej 2.5 miliona pikseli przy Hires zaczyna być niebezpiecznie dla standardowych kart 8GB.
        if (finalPixels > 2500000) {
            viewModel.showToast("High VRAM usage warning. Risk of server OOM.")
        }
    }

    // Root screen layout container configured with tap gestures to dismiss the virtual keyboard
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        // Gdy użytkownik kliknie gdziekolwiek indziej, zdejmij focus i schowaj klawiaturę
                        focusManager.clearFocus()
                    })
                },
    ) {
        BottomSheetScaffold(
            modifier = blurModifier,
            scaffoldState = scaffoldState,
            sheetPeekHeight = peekHeight,
            sheetContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            topBar = {
                val isActivelyGenerating = isGenerating || isServerBusy || progress > 0f
                ForgeTopAppBar(
                    isConnected = isConnected,
                    pingMs = pingMs,
                    ram = ram,
                    vram = vram,
                    isActivelyGenerating = isActivelyGenerating,
                    onUnloadClick = { showUnloadDialog = true },
                    onGalleryClick = onGalleryClick,
                    onSettingsClick = onSettingsClick,
                )
            },
            sheetContent = {
                BottomControlsSection(
                    viewModel = viewModel,
                    state = state,
                    generationQueueSize = generationQueue.size,
                    isActivelyGenerating = isGenerating || isServerBusy || progress > 0f,
                    progress = progress,
                    currentEta = currentEta,
                    onQueueClick = onQueueClick,
                    onNavigateToPresets = { navController.navigate("presets") },
                )
            },
        ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                val scrollState = rememberScrollState()

                Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(8.dp)) {
                    OomAlertSection(viewModel)

                    PreviewSection(
                        isGenerating = isGenerating,
                        previewMode = config.previewMode,
                        livePreviewBase64 = livePreviewBase64,
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
                            viewModel.setGalleryMode(com.example.forgegen.GalleryMode.PROMPT_PICKER)
                            viewModel.fetchGalleryFolder(config.galleryPath)
                            navController.navigate("gallery")
                        },
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    PromptsSection(
                        viewModel = viewModel,
                        state = state,
                        config = config,
                        promptHistory = promptHistory,
                        navController = navController,
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
                    )

                    LorasSection(
                        viewModel = viewModel,
                        availableLoras = availableLoras,
                        activeLoras = activeLoras,
                        onPendingLora = { pendingLora = it },
                        onOpenTagsPopup = { hash, name ->
                            tagsPopupHash = hash
                            tagsPopupName = name
                        },
                    )

                    // Spacer at the bottom to ensure contents can clear the bottom sheet peek height when scrolled
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }

        // --- ROOT DIALOGS ---

        if (pendingLora != null) {
            LoraTriggerDialog(
                viewModel = viewModel,
                state = state,
                lora = pendingLora!!,
                onDismiss = { pendingLora = null },
            )
        }

        if (fullscreenImageIndex >= 0 && sessionImages.isNotEmpty()) {
            FullscreenImageViewer(
                viewModel = viewModel,
                config = config,
                sessionImages = sessionImages,
                initialIndex = fullscreenImageIndex,
                onDismiss = { fullscreenImageIndex = -1 },
            )
        }

        if (tagsPopupHash != null) {
            LaunchedEffect(tagsPopupHash) {
                availableTagsForPopup = viewModel.getTagsForLora(tagsPopupHash!!)
            }

            AlertDialog(
                onDismissRequest = { tagsPopupHash = null },
                title = { Text("Tags for: $tagsPopupName", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                text = {
                    val promptTags = state.positivePrompt.split(",").map { it.trim() }
                    val filteredTags = availableTagsForPopup.filter { !promptTags.contains(it) }

                    if (filteredTags.isEmpty()) {
                        Text("No new tags found. Refresh API if the model was updated on Civitai.", fontSize = 14.sp)
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.heightIn(max = 300.dp),
                        ) {
                            filteredTags.forEach { tag ->
                                AssistChip(
                                    onClick = {
                                        val currentPrompt = state.positivePrompt
                                        val newPrompt =
                                            if (currentPrompt.endsWith(",")) {
                                                "$currentPrompt $tag"
                                            } else if (currentPrompt.isBlank()) {
                                                tag
                                            } else {
                                                "$currentPrompt, $tag"
                                            }
                                        viewModel.updateState { it.copy(positivePrompt = newPrompt) }
                                    },
                                    label = { Text(tag, fontSize = 12.sp) },
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { tagsPopupHash = null }) { Text("Close") }
                },
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
