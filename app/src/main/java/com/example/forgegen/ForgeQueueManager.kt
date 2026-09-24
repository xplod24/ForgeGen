package com.example.forgegen

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.util.Collections
import java.util.UUID

/* ============================================================================
 * QUEUE MANAGER
 * Oversees the generation queue, handles Text2Img requests, manages OOM errors,
 * caches session images, and communicates with the Background Service.
 * Implements Resumable Queue features for connection loss handling.
 * ============================================================================ */
@SuppressLint("StaticFieldLeak")
object ForgeQueueManager {
    private const val TAG = "ForgeQueueManager"

    // Cache files of a session: generated images, images restored from the gallery, temporary downloads.
    private val SESSION_CACHE_PREFIXES = listOf("gen_", "recovered_", "temp_rec_")

    private lateinit var application: Application
    private val gson = Gson()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _currentEta = MutableStateFlow(0.0)
    val currentEta: StateFlow<Double> = _currentEta.asStateFlow()

    private val _statusText = MutableStateFlow("Ready")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _livePreviewImage = MutableStateFlow<String?>(null)
    val livePreviewImage: StateFlow<String?> = _livePreviewImage.asStateFlow()

    private val _generationQueue = MutableStateFlow<List<QueuedGeneration>>(emptyList())
    val generationQueue: StateFlow<List<QueuedGeneration>> = _generationQueue.asStateFlow()

    private val _isQueuePaused = MutableStateFlow(false)
    val isQueuePaused: StateFlow<Boolean> = _isQueuePaused.asStateFlow()

    private val _oomAlert = MutableStateFlow(false)
    val oomAlert: StateFlow<Boolean> = _oomAlert.asStateFlow()

    // Why the queue is paused; statusText can't carry it because the ping loop overwrites it every second.
    private val _queuePauseReason = MutableStateFlow<String?>(null)
    val queuePauseReason: StateFlow<String?> = _queuePauseReason.asStateFlow()

    private fun pauseQueue(reason: String) {
        _queuePauseReason.value = reason
        _isQueuePaused.value = true
    }

    private val _totalQueueSize = MutableStateFlow(0)
    val totalQueueSize: StateFlow<Int> = _totalQueueSize.asStateFlow()

    private val _completedQueueItems = MutableStateFlow(0)
    val completedQueueItems: StateFlow<Int> = _completedQueueItems.asStateFlow()

    private val _sessionImages = MutableStateFlow<List<String>>(emptyList())
    val sessionImages: StateFlow<List<String>> = _sessionImages.asStateFlow()

    private val _currentSessionIndex = MutableStateFlow(-1)
    val currentSessionIndex: StateFlow<Int> = _currentSessionIndex.asStateFlow()

    private val _isShowingGridPreview = MutableStateFlow(false)
    val isShowingGridPreview: StateFlow<Boolean> = _isShowingGridPreview.asStateFlow()

    private val _currentBatchStartIndex = MutableStateFlow(0)
    val currentBatchStartIndex: StateFlow<Int> = _currentBatchStartIndex.asStateFlow()

    private val _currentBatchEndIndex = MutableStateFlow(-1)
    val currentBatchEndIndex: StateFlow<Int> = _currentBatchEndIndex.asStateFlow()

    private var currentGenerationJob: Job? = null

    fun init(app: Application) {
        application = app
    }

    fun start() {
        loadQueueState()
        startQueueLoop()
        cleanupSessionCache()
    }

    fun updateExternalProgress(
        progress: Float,
        eta: Double,
        image: String?,
    ) {
        _progress.value = progress
        _currentEta.value = eta
        if (image != null) _livePreviewImage.value = image
    }

    fun updateStatusText(text: String) {
        _statusText.value = text
    }

    fun setLivePreviewImage(image: String?) {
        _livePreviewImage.value = image
    }

    private fun loadQueueState() {
        ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
            val json = ForgeRepository.db.appSettingDao().getSetting("saved_queue")?.value
            if (!json.isNullOrEmpty()) {
                try {
                    val type = object : TypeToken<List<QueuedGeneration>>() {}.type
                    val q: List<QueuedGeneration> = gson.fromJson(json, type)
                    if (q.isNotEmpty()) {
                        _generationQueue.value = q
                        _totalQueueSize.value = q.size
                        _completedQueueItems.value = 0
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load saved queue", e)
                }
            }
        }
    }

    private fun saveQueueState() {
        ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
            try {
                ForgeRepository.db.appSettingDao().putSetting(AppSettingEntity("saved_queue", gson.toJson(_generationQueue.value)))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save queue", e)
            }
        }
    }

    private fun startQueueLoop() {
        ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val queue = _generationQueue.value
                    val firstItem = queue.firstOrNull()

                    if (firstItem != null &&
                        !ForgeRepository.isServerBusy.value &&
                        !_isQueuePaused.value &&
                        _isGenerating.compareAndSet(false, true) // atomic claim: queueGeneration() may start the same item
                    ) {
                        // Resume suspended task if the queue is unpaused
                        if (firstItem.status == GenerationStatus.SUSPENDED) {
                            _generationQueue.update { q ->
                                val list = q.toMutableList()
                                if (list.isNotEmpty()) list[0] = list[0].copy(status = GenerationStatus.GENERATING)
                                list
                            }
                        }

                        currentGenerationJob =
                            launch {
                                executeGeneration(firstItem)
                            }
                        currentGenerationJob?.join()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Queue Manager Exception", e)
                    _isGenerating.value = false
                }
                delay(500)
            }
        }
    }

    private fun applyWildcards(
        prompt: String,
        allWildcards: List<WildcardEntity>,
    ): String {
        var result = prompt
        val regex = Regex("__([a-zA-Z0-9_\\-]+)__")
        var match = regex.find(result)

        while (match != null) {
            val wildcardName = match.groupValues[1]
            val entity = allWildcards.find { it.name == wildcardName }
            var replacement = match.value
            if (entity != null) {
                val options =
                    entity.content
                        .split("\n", ",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                if (options.isNotEmpty()) {
                    replacement = options.random()
                }
            }
            result = result.replaceRange(match.range, replacement)
            match = regex.find(result, match.range.first + replacement.length)
        }
        return result
    }

    fun queueGeneration() {
        ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
            val state = ForgeRepository.appState.value
            val currentModel = ForgeRepository.selectedModel.value.ifEmpty { null }
            val wildcards = ForgePromptManager.wildcards.value

            val finalPositive = applyWildcards(state.positivePrompt, wildcards)
            val finalNegative = applyWildcards(state.negativePrompt, wildcards)

            val payload =
                Txt2ImgPayloadDto(
                    prompt = finalPositive,
                    negative_prompt = finalNegative,
                    steps = state.steps,
                    cfg_scale = state.cfgScale,
                    width = state.width,
                    height = state.height,
                    n_iter = state.batchCount,
                    batch_size = state.batchSize,
                    seed = state.seed,
                    sampler_name = state.sampler,
                    scheduler = state.scheduler,
                    override_settings =
                        OverrideSettingsDto(
                            clipSkip = state.clipSkip,
                            sdModelCheckpoint = currentModel,
                        ),
                    enable_hr = state.hiresFix,
                    hr_scale = state.hiresScale,
                    hr_upscaler = state.upscaler,
                    denoising_strength = state.denoising,
                    save_images = state.saveImages,
                    send_images = true,
                )

            val item =
                QueuedGeneration(
                    id = UUID.randomUUID().toString(),
                    positivePrompt = finalPositive,
                    payload = payload,
                    status = GenerationStatus.QUEUED,
                )

            _generationQueue.update { it + item }
            saveQueueState()

            val qSize = _generationQueue.value.size
            if (qSize == 1) {
                _totalQueueSize.value = 1
                _completedQueueItems.value = 0

                if (!ForgeRepository.isServerBusy.value && !_isQueuePaused.value && _isGenerating.compareAndSet(false, true)) {
                    currentGenerationJob = launch { executeGeneration(item) }
                }
            } else {
                _totalQueueSize.update { it + 1 }
            }

            ForgeSettingsManager.saveToPromptHistory(state.positivePrompt, state.negativePrompt)

            if (ForgeRepository.isServerBusy.value && qSize > 1) {
                ForgeRepository.showToast("External generation active. Added to queue.")
            } else if (qSize > 1) {
                ForgeRepository.showToast("Added to queue.")
            }
        }
    }

    private suspend fun executeGeneration(job: QueuedGeneration) {
        val config = ForgeRepository.config.value
        var succeeded = false
        var errorReason: String? = null // set when a failure paused the queue
        var isOom = false
        // With "Save to phone: All new images" the gallery sync saves the server's copy (its own name and
        // folder), so saving here as well would put every image on the phone twice.
        val shouldSaveToDevice =
            ForgeRepository.appState.value.saveToDevice &&
                !(config.autoSaveMode == AUTO_SAVE_ALL && job.payload.save_images)

        _progress.value = 0f
        _currentEta.value = 0.0
        _livePreviewImage.value = null
        _isShowingGridPreview.value = false

        val previewText = job.positivePrompt.take(30).replace("\n", " ")
        val initialBatchInfo = if (job.payload.n_iter > 1) "(Batch 1 of ${job.payload.n_iter}) " else ""
        _statusText.value = "Preparing $initialBatchInfo\"$previewText...\""

        val serviceIntent =
            Intent(application, GenerationService::class.java).apply {
                action = GenerationService.ACTION_START_GENERATION
            }
        try {
            application.startForegroundService(serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
        }

        try {
            val response = ForgeRepository.forgeApi?.generateImage(job.payload)
            if (response?.isSuccessful == true) {
                val txt2ImgData = response.body() ?: Txt2ImgResponseDto()

                if (txt2ImgData.images.isNotEmpty()) {
                    val currentList = _sessionImages.value.toMutableList()
                    val startIndex = currentList.size
                    val cachePath = application.cacheDir.absolutePath

                    for ((i, b64) in txt2ImgData.images.withIndex()) {
                        val bytes = Base64.decode(b64, Base64.DEFAULT)
                        val file = File("$cachePath/gen_${System.currentTimeMillis()}_$i.png")
                        file.writeBytes(bytes)
                        currentList.add(file.absolutePath)

                        // Save the very first image of the batch as the local fallback "last generated image"
                        if (i == 0) {
                            try {
                                val lastGenFile = File(cachePath, "last_generated_image.png")
                                lastGenFile.writeBytes(bytes)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to cache local last generated image", e)
                            }
                        }

                        if (shouldSaveToDevice) {
                            try {
                                DeviceImages.save(application, "Gen_${System.currentTimeMillis()}_$i.png") { it.write(bytes) }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to save generated image directly to device", e)
                            }
                        }
                    }

                    val endIndex = currentList.size - 1
                    _sessionImages.value = currentList
                    _currentBatchStartIndex.value = startIndex
                    _currentBatchEndIndex.value = endIndex
                    _currentSessionIndex.value = endIndex
                    _statusText.value = "Generation Complete"
                    _livePreviewImage.value = null

                    if (config.showGridAfterGeneration && txt2ImgData.images.size > 1) {
                        _isShowingGridPreview.value = true
                    }

                    succeeded = true
                }
            } else {
                val errorBody = response?.errorBody()?.string() ?: ""
                // Forge answers most failures (bad sampler, missing model, ...) with HTTP 500, so the status code
                // alone must not raise the out-of-memory alarm.
                if (errorBody.contains("OutOfMemoryError", true) ||
                    errorBody.contains("out of memory", true)
                ) {
                    _statusText.value = "SERVER OUT OF MEMORY (OOM)"
                    pauseQueue("Server out of memory (OOM).")
                    _oomAlert.value = true
                    errorReason = "Server out of memory (OOM)."
                    isOom = true
                } else {
                    _statusText.value = "Error: HTTP ${response?.code()}"
                    if (!config.overnightMode) {
                        pauseQueue("The server returned HTTP ${response?.code()}.")
                        errorReason = "The server returned HTTP ${response?.code()}."
                    }
                }
            }
        } catch (e: CancellationException) {
            Log.d(TAG, "Generation cancelled or suspended")
            throw e
        } catch (e: Exception) {
            _statusText.value = "Failed: ${e.localizedMessage}"
            if (!ForgeRepository.config.value.overnightMode) {
                pauseQueue("Generation failed: ${e.localizedMessage}")
                errorReason = "Generation failed: ${e.localizedMessage}"
            }
        } finally {
            val isSuspended =
                _generationQueue.value.firstOrNull()?.id == job.id &&
                    _generationQueue.value.firstOrNull()?.status == GenerationStatus.SUSPENDED

            if (!isSuspended) {
                _generationQueue.update { q -> q.filter { it.id != job.id } }
                _completedQueueItems.update { it + 1 }

                val queueEmpty = _generationQueue.value.isEmpty()
                if (queueEmpty) {
                    _totalQueueSize.value = 0
                    _completedQueueItems.value = 0
                    // Nothing left to hold back: a paused empty queue would silently swallow the next job.
                    _isQueuePaused.value = false
                    _queuePauseReason.value = null
                }

                // One notification per job: "queue completed" replaces "batch completed" for the last job, and a
                // failed job only gets the error alert (it used to be reported as a completed queue).
                val notifConfig = ForgeRepository.config.value
                val failure = errorReason
                when {
                    failure != null -> notifyGenerationError(failure, isOom, queuePaused = !queueEmpty)
                    queueEmpty && notifConfig.notifOnQueueFinish ->
                        launchNotification(job.positivePrompt, isQueueFinished = true)
                    succeeded && notifConfig.notifOnBatchFinish ->
                        launchNotification(job.positivePrompt, isQueueFinished = false)
                }

                // Also when the queue paused: otherwise the service kept showing the last progress indefinitely.
                if (queueEmpty || _isQueuePaused.value) {
                    try {
                        val finishIntent =
                            Intent(application, GenerationService::class.java).setAction(GenerationService.ACTION_QUEUE_FINISHED)
                        application.startService(finishIntent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to notify service of queue finish", e)
                    }
                }
            }

            saveQueueState()
            _isGenerating.value = false
            _progress.value = if (isSuspended) 0f else 1f
            _currentEta.value = 0.0
        }
    }

    private fun launchNotification(
        prompt: String,
        isQueueFinished: Boolean,
    ) {
        val title = if (isQueueFinished) "Queue Completed" else "Batch Completed"
        val text = if (isQueueFinished) "All generation jobs have finished." else "Finished: ${prompt.take(35)}..."
        val builder = ForgeNotifications.builder(ForgeNotifications.CHANNEL_RESULTS) ?: return
        val notification =
            builder
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .build()
        ForgeNotifications.post(System.currentTimeMillis().toInt(), notification)
    }

    /** A failed job needs the user, who may not be looking at the app; one alert, replaced by the next. */
    private fun notifyGenerationError(
        reason: String,
        isOom: Boolean,
        queuePaused: Boolean,
    ) {
        val builder = ForgeNotifications.builder(ForgeNotifications.CHANNEL_ALERTS) ?: return
        val title =
            when {
                isOom -> "Server out of memory"
                queuePaused -> "Queue paused"
                else -> "Generation failed"
            }
        val text =
            when {
                isOom && queuePaused -> "Lower the resolution or batch size, then resume the queue."
                isOom -> "Lower the resolution or batch size and try again."
                queuePaused -> "$reason Open the app to resume the queue."
                else -> reason
            }
        val notification =
            builder
                .setContentTitle(title)
                .setContentText(text)
                .setColor(0xFFFF0000.toInt())
                .setAutoCancel(true)
                .build()
        ForgeNotifications.post(ForgeNotifications.ID_QUEUE_PAUSED, notification)
    }

    fun suspendCurrentGeneration() {
        if (!_isGenerating.value) return

        _generationQueue.update { q ->
            val list = q.toMutableList()
            if (list.isNotEmpty()) {
                list[0] = list[0].copy(status = GenerationStatus.SUSPENDED)
            }
            list
        }
        pauseQueue("Connection to the server was lost.")
        _statusText.value = "Queue Suspended (Connection Lost)"

        currentGenerationJob?.cancel()

        try {
            val finishIntent = Intent(application, GenerationService::class.java).apply { action = GenerationService.ACTION_QUEUE_FINISHED }
            application.startService(finishIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to notify service of queue finish", e)
        }

        saveQueueState()
    }

    fun updateQueueItem(
        id: String,
        positivePrompt: String,
        negativePrompt: String,
    ) {
        _generationQueue.update { currentQueue ->
            currentQueue.map {
                if (it.id == id) {
                    it.copy(
                        positivePrompt = positivePrompt,
                        payload = it.payload.copy(prompt = positivePrompt, negative_prompt = negativePrompt),
                    )
                } else {
                    it
                }
            }
        }
        saveQueueState()
    }

    fun clearQueue() {
        ForgeNotifications.cancel(ForgeNotifications.ID_QUEUE_PAUSED)
        _generationQueue.value = emptyList()
        _totalQueueSize.value = 0
        _completedQueueItems.value = 0
        saveQueueState()
    }

    /** The first queue item is the one being sent to the server while a generation is running. */
    private fun isActiveItem(
        queue: List<QueuedGeneration>,
        index: Int,
    ) = index == 0 && _isGenerating.value && queue.isNotEmpty()

    fun removeFromQueue(id: String) {
        if (_isGenerating.value && _generationQueue.value.firstOrNull()?.id == id) return
        _generationQueue.update { currentQueue ->
            val prevSize = currentQueue.size
            val newQueue = currentQueue.filter { it.id != id }
            if (newQueue.size < prevSize) {
                _totalQueueSize.update { maxOf(_completedQueueItems.value, it - 1) }
            }
            newQueue
        }
        saveQueueState()
    }

    fun moveQueueItemUp(id: String) {
        _generationQueue.update { q ->
            val idx = q.indexOfFirst { it.id == id }
            if (idx > 0 && !isActiveItem(q, idx - 1)) {
                val list = q.toMutableList()
                Collections.swap(list, idx, idx - 1)
                list
            } else {
                q
            }
        }
        saveQueueState()
    }

    fun moveQueueItemDown(id: String) {
        _generationQueue.update { q ->
            val idx = q.indexOfFirst { it.id == id }
            if (idx in 0 until q.size - 1 && !isActiveItem(q, idx)) {
                val list = q.toMutableList()
                Collections.swap(list, idx, idx + 1)
                list
            } else {
                q
            }
        }
        saveQueueState()
    }

    fun resumeQueue() {
        ForgeNotifications.cancel(ForgeNotifications.ID_QUEUE_PAUSED)
        _isQueuePaused.value = false
        _queuePauseReason.value = null
        _oomAlert.value = false
        _statusText.value = "Queue Resumed"
    }

    fun interruptGeneration() {
        ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
            try {
                ForgeRepository.forgeApi?.interruptGeneration()
                _statusText.value = "Interrupting..."
            } catch (e: Exception) {
                Log.e(TAG, "Failed to interrupt", e)
            }
        }
    }

    suspend fun saveRecoveredImageToCache(bytes: ByteArray) {
        withContext(Dispatchers.IO) {
            try {
                if (bytes.isEmpty()) {
                    Log.e(TAG, "Cannot cache empty recovered image bytes.")
                    return@withContext
                }

                val file = File(application.cacheDir, "recovered_${System.currentTimeMillis()}.png")
                file.writeBytes(bytes)

                _sessionImages.value = listOf(file.absolutePath)
                _currentSessionIndex.value = 0
                _currentBatchStartIndex.value = 0
                _currentBatchEndIndex.value = 0
                _isShowingGridPreview.value = false
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cache recovered image", e)
            }
        }
    }

    /**
     * Deletes the images of the previous run from the cache. The session only lives in memory, so after a restart
     * nothing refers to them any more; generated images ("gen_") used to pile up there forever.
     */
    private fun cleanupSessionCache() {
        ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
            try {
                application.cacheDir.listFiles()?.forEach { file ->
                    val isSessionFile = SESSION_CACHE_PREFIXES.any { file.name.startsWith(it) }
                    if (isSessionFile && file.name.endsWith(".png")) {
                        file.delete()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning up recovered cache", e)
            }
        }
    }

    fun dismissGridPreview(index: Int? = null) {
        _isShowingGridPreview.value = false
        if (index != null && index in 0 until _sessionImages.value.size) {
            _currentSessionIndex.value = index
        }
    }

    fun sessionPrev() {
        _isShowingGridPreview.value = false
        val idx = _currentSessionIndex.value
        if (idx > _currentBatchStartIndex.value) _currentSessionIndex.value = idx - 1
    }

    fun sessionNext() {
        _isShowingGridPreview.value = false
        val idx = _currentSessionIndex.value
        if (idx < _currentBatchEndIndex.value) _currentSessionIndex.value = idx + 1
    }

    fun downloadSessionImage(localFilePath: String) {
        ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
            try {
                val file = File(localFilePath)
                if (!file.exists()) throw Exception("Local file missing")
                DeviceImages.save(application, "Gen_${System.currentTimeMillis()}.png") { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                ForgeRepository.showToast("Saved to Pictures/ForgeGen")
            } catch (e: Exception) {
                ForgeRepository.showToast("Download Failed: ${e.message}")
            }
        }
    }

    fun shareSessionImage(
        localFilePath: String,
        onIntentReady: (Intent) -> Unit,
    ) {
        ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
            try {
                val file = File(localFilePath)
                if (!file.exists()) throw Exception("Local file missing")
                // A temporary copy for the share sheet; it used to be saved to Pictures/ForgeGen_Shared for good.
                val intent =
                    DeviceImages.shareIntent(application, "ForgeGen_${System.currentTimeMillis()}.png") { out ->
                        file.inputStream().use { it.copyTo(out) }
                    }
                withContext(Dispatchers.Main) { onIntentReady(intent) }
            } catch (e: Exception) {
                ForgeRepository.showToast("Share Failed: ${e.message}")
            }
        }
    }
}
