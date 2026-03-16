package com.yourname.forgegen

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

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

    // Background Tasks
    val indexerStatus = MutableStateFlow("Idle")

    val sessionImages = MutableStateFlow<List<String>>(emptyList())
    val currentSessionIndex = MutableStateFlow(-1)

    // Live Base64 Image Preview
    val livePreviewImage = MutableStateFlow<String?>(null)

    // Grid Preview
    val isShowingGridPreview = MutableStateFlow(false)

    // Tracks the index boundaries of the most recently generated batch
    val currentBatchStartIndex = MutableStateFlow(0)
    val currentBatchEndIndex = MutableStateFlow(-1)

    // Terminal Server Logs
    val serverLogs = MutableStateFlow<List<String>>(emptyList())

    fun logServer(msg: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        serverLogs.update { (it + "[$time] $msg").takeLast(100) }
    }
}