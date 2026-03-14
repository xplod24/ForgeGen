package com.yourname.forgegen

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

object ForgeState {
    val progress = MutableStateFlow(0f)
    val isGenerating = MutableStateFlow(false)
    val statusText = MutableStateFlow("Ready")

    // Server Status and Queue
    val isServerBusy = MutableStateFlow(false)
    val generationQueue = MutableStateFlow<List<QueuedGeneration>>(emptyList())

    val sessionImages = MutableStateFlow<List<String>>(emptyList())
    val currentSessionIndex = MutableStateFlow(-1)

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