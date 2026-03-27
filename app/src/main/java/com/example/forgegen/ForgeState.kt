package com.yourname.forgegen

import kotlinx.coroutines.flow.MutableStateFlow

object ForgeState {
    val progress = MutableStateFlow(0f)
    val currentEta = MutableStateFlow(0.0)
    val isGenerating = MutableStateFlow(false)
    val statusText = MutableStateFlow("Ready")

    // Server Status and Queue
    val isServerBusy = MutableStateFlow(false)
    val generationQueue = MutableStateFlow<List<QueuedGeneration>>(emptyList())
    val isQueuePaused = MutableStateFlow(false)
    val oomAlert = MutableStateFlow(false)
    val vramUsage = MutableStateFlow<String?>(null)

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