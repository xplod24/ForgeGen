package com.yourname.forgegen

import kotlinx.coroutines.flow.MutableStateFlow

object ForgeState {
    val progress = MutableStateFlow(0f)
    val currentEta = MutableStateFlow(0.0)
    val isGenerating = MutableStateFlow(false)
    val statusText = MutableStateFlow("Ready")

    // Zmienne postępu poszczególnych kroków obrazu
    val currentJobNo = MutableStateFlow(0)
    val currentJobCount = MutableStateFlow(0)
    val currentSamplingStep = MutableStateFlow(0)
    val currentSamplingSteps = MutableStateFlow(0)

    // Server Status and Queue
    val isServerBusy = MutableStateFlow(false)
    val generationQueue = MutableStateFlow<List<QueuedGeneration>>(emptyList())
    val isQueuePaused = MutableStateFlow(false)
    val oomAlert = MutableStateFlow(false)
    val vramUsage = MutableStateFlow<String?>(null)

    // Liczniki dla całkowitego postępu kolejki
    val totalQueueSize = MutableStateFlow(0)
    val completedQueueItems = MutableStateFlow(0)

    val sessionImages = MutableStateFlow<List<String>>(emptyList())
    val currentSessionIndex = MutableStateFlow(-1)

    // Live Base64 Image Preview
    val livePreviewImage = MutableStateFlow<String?>(null)

    // Grid Preview
    val isShowingGridPreview = MutableStateFlow(false)

    // Tracks the index boundaries of the most recently generated batch
    val currentBatchStartIndex = MutableStateFlow(0)
    val currentBatchEndIndex = MutableStateFlow(-1)
}