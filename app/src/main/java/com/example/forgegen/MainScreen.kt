package com.example.forgegen

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import coil.compose.LocalImageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/* ============================================================================
 * MAIN SCREEN (Orchestrator)
 * Czysty szkielet UI. Wszystkie duże komponenty pochodzą z MainComponents.kt
 * ============================================================================ */

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: ForgeViewModel, navController: NavHostController) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val state by viewModel.appState.collectAsStateWithLifecycle()
    val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()
    val pingMs by viewModel.pingMs.collectAsStateWithLifecycle()
    val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val currentEta by viewModel.currentEta.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val vram by viewModel.vramUsage.collectAsStateWithLifecycle()

    val isServerBusy by viewModel.isServerBusy.collectAsStateWithLifecycle()
    val generationQueue by viewModel.generationQueue.collectAsStateWithLifecycle()

    val sessionImages by viewModel.sessionImages.collectAsStateWithLifecycle()
    val currentSessionIndex by viewModel.currentSessionIndex.collectAsStateWithLifecycle()
    val livePreviewBase64 by viewModel.livePreviewImage.collectAsStateWithLifecycle()
    val isShowingGridPreview by viewModel.isShowingGridPreview.collectAsStateWithLifecycle()

    val batchStart by viewModel.currentBatchStartIndex.collectAsStateWithLifecycle()
    val batchEnd by viewModel.currentBatchEndIndex.collectAsStateWithLifecycle()

    val currentJobNo by viewModel.currentJobNo.collectAsStateWithLifecycle()
    val currentJobCount by viewModel.currentJobCount.collectAsStateWithLifecycle()
    val currentSamplingStep by viewModel.currentSamplingStep.collectAsStateWithLifecycle()
    val currentSamplingSteps by viewModel.currentSamplingSteps.collectAsStateWithLifecycle()

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

    val serverStats by viewModel.serverStats.collectAsStateWithLifecycle()

    var pendingLora by remember { mutableStateOf<ApiResource?>(null) }
    var fullscreenImageIndex by remember { mutableIntStateOf(-1) }
    var showStatsDialog by remember { mutableStateOf(false) }

    var tagsPopupHash by remember { mutableStateOf<String?>(null) }
    var tagsPopupName by remember { mutableStateOf("") }
    var availableTagsForPopup by remember { mutableStateOf<List<String>>(emptyList()) }

    val context = LocalContext.current
    val imageLoader = LocalImageLoader.current

    // Manager do kontroli focusu na ekranie
    val focusManager = LocalFocusManager.current

    LaunchedEffect(selectedModel, activeLoras, models, availableLoras) {
        launch(Dispatchers.IO) {
            val currentModelResource = models.find { it.title == selectedModel }
            if (currentModelResource != null) {
                val url = viewModel.getPreviewUrl(currentModelResource.path, isLora = false)
                if (url.isNotEmpty()) {
                    val request = ImageRequest.Builder(context)
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
                        val request = ImageRequest.Builder(context)
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

    val onGalleryClick = rememberDebounced {
        viewModel.setGalleryMode(GalleryMode.NORMAL)
        viewModel.fetchGalleryFolder(config.galleryPath)
        navController.navigate("gallery")
    }

    val onSettingsClick = rememberDebounced { navController.navigate("setup") }
    val onQueueClick = rememberDebounced { navController.navigate("queue") }

    val blurModifier = if (isRestoringPrompt) Modifier.blur(10.dp) else Modifier

    // Główny kontener ekranu przechwytujący dotknięcia
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    // Gdy użytkownik kliknie gdziekolwiek indziej, zdejmij focus i schowaj klawiaturę
                    focusManager.clearFocus()
                })
            }
    ) {
        Scaffold(
            modifier = blurModifier,
            topBar = {
                ForgeTopAppBar(
                    isConnected = isConnected,
                    pingMs = pingMs,
                    vram = vram,
                    onStatsClick = { showStatsDialog = true },
                    onGalleryClick = onGalleryClick,
                    onSettingsClick = onSettingsClick
                )
            }
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
                        onNext = { viewModel.sessionNext() }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    PromptsSection(
                        viewModel = viewModel,
                        state = state,
                        config = config,
                        tagSuggestions = tagSuggestions,
                        promptHistory = promptHistory,
                        navController = navController
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    GenerationSettingsSection(
                        viewModel = viewModel,
                        state = state,
                        models = models,
                        selectedModel = selectedModel,
                        samplers = samplers,
                        schedulers = schedulers,
                        upscalers = upscalers
                    )

                    LorasSection(
                        viewModel = viewModel,
                        availableLoras = availableLoras,
                        activeLoras = activeLoras,
                        onPendingLora = { pendingLora = it },
                        onOpenTagsPopup = { hash, name ->
                            tagsPopupHash = hash
                            tagsPopupName = name
                        }
                    )

                    Spacer(modifier = Modifier.height(100.dp))
                }

                BottomControlsSection(
                    viewModel = viewModel,
                    state = state,
                    generationQueueSize = generationQueue.size,
                    isActivelyGenerating = isGenerating || isServerBusy || progress > 0f,
                    progress = progress,
                    currentEta = currentEta,
                    currentJobNo = currentJobNo,
                    currentJobCount = currentJobCount,
                    currentSamplingStep = currentSamplingStep,
                    currentSamplingSteps = currentSamplingSteps,
                    isConnected = isConnected,
                    isRestoringPrompt = isRestoringPrompt,
                    onQueueClick = onQueueClick,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }

        // --- ROOT DIALOGS ---
        if (showStatsDialog) {
            ServerStatsDialog(
                serverStats = serverStats,
                onDismiss = { showStatsDialog = false }
            )
        }

        if (pendingLora != null) {
            LoraTriggerDialog(
                viewModel = viewModel,
                state = state,
                lora = pendingLora!!,
                onDismiss = { pendingLora = null }
            )
        }

        if (fullscreenImageIndex >= 0 && sessionImages.isNotEmpty()) {
            FullscreenImageViewer(
                viewModel = viewModel,
                config = config,
                sessionImages = sessionImages,
                initialIndex = fullscreenImageIndex,
                onDismiss = { fullscreenImageIndex = -1 }
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
                        Text("Brak nowych tagów. Odśwież API, jeśli model został zaktualizowany.", fontSize = 14.sp)
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.heightIn(max = 300.dp)
                        ) {
                            filteredTags.forEach { tag ->
                                AssistChip(
                                    onClick = {
                                        val currentPrompt = state.positivePrompt
                                        val newPrompt = if (currentPrompt.endsWith(",")) "$currentPrompt $tag" else if (currentPrompt.isBlank()) tag else "$currentPrompt, $tag"
                                        viewModel.updateState { it.copy(positivePrompt = newPrompt) }
                                    },
                                    label = { Text(tag, fontSize = 12.sp) }
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { tagsPopupHash = null }) { Text("Close") }
                }
            )
        }
    }
}