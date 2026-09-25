package com.example.forgegen

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/* ============================================================================
 * QUEUE MANAGER
 * Oversees the generation queue, handles Text2Img requests, manages OOM errors,
 * caches session images, and communicates with the Background Service.
 *
 * One worker sends the jobs, strictly one after another in queue order: it waits (without polling) until the
 * first job may start, marks it GENERATING, and removes it when it is done. The GENERATING job cannot be removed
 * or overtaken. The queue is saved by a single writer, so an older state can never overwrite a newer one.
 *
 * Losing the connection never costs a job: nothing is sent while the server is unreachable, and a job whose
 * connection drops stays first in the queue (SUSPENDED). The queue pauses and continues by itself once the server
 * is back and idle, at most MAX_AUTO_RETRIES times per job; after that the user resumes it.
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

    const val CONNECTION_LOST_REASON = "Connection to the server was lost. The queue continues when the server is back."
    private const val MAX_AUTO_RETRIES = 3
    private const val AUTO_RETRY_DELAY_MS = 5_000L

    // After an outage during a generation, a server that stays idle this long no longer works on our request:
    // its answer is lost with the dropped connection (a phone's socket can hang for the whole read timeout).
    private const val ORPHANED_REQUEST_IDLE_MS = 10_000L

    // How often each job lost its connection, for the automatic retries.
    private val connectionLosses = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Thrown into the txt2img call when the watchdog finds it orphaned. */
    private class OrphanedRequest : CancellationException("The request was lost in an outage")

    /** The connection broke while a job was being sent; unlike other errors the job itself did not fail. */
    private class ConnectionLost(
        cause: Throwable,
    ) : Exception(cause.message, cause)

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

    // Save requests; conflated, so a burst of changes is written once, always with the latest queue.
    private val saveRequests = Channel<Unit>(Channel.CONFLATED)

    fun init(app: Application) {
        application = app
    }

    /** Returns once the saved queue is loaded; the writer, the worker and the reconnect watcher run from then on. */
    suspend fun start() {
        loadQueueState()
        startQueueWriter()
        startQueueWorker()
        startReconnectWatcher()
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

    private suspend fun loadQueueState() {
        withContext(Dispatchers.IO) {
            val json = ForgeRepository.db.appSettingDao().getSetting("saved_queue")?.value
            if (!json.isNullOrEmpty()) {
                try {
                    val type = object : TypeToken<List<QueuedGeneration>>() {}.type
                    // A job that was running when the app was closed starts again from the beginning.
                    val saved = gson.fromJson<List<QueuedGeneration>>(json, type).map { it.copy(status = GenerationStatus.QUEUED) }
                    if (saved.isNotEmpty()) {
                        // Jobs added while the saved queue was being read are kept after it (they used to be lost).
                        _generationQueue.update { current -> saved + current.filter { job -> saved.none { it.id == job.id } } }
                        _totalQueueSize.value = _generationQueue.value.size
                        _completedQueueItems.value = 0
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load saved queue", e)
                }
            }
        }
    }

    private fun saveQueueState() {
        saveRequests.trySend(Unit)
    }

    /**
     * The only writer of the saved queue. Each save used to be its own coroutine reading the queue at a random
     * moment, so an older queue could be written after a newer one and come back after a restart.
     */
    private fun startQueueWriter() {
        ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
            for (request in saveRequests) {
                try {
                    ForgeRepository.db.appSettingDao().putSetting(AppSettingEntity("saved_queue", gson.toJson(_generationQueue.value)))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save queue", e)
                }
            }
        }
    }

    /**
     * The first job, as soon as one may start: queued, the queue not paused, the server reachable and not busy.
     * Jobs used to be sent while the server was unreachable, each failing at once.
     */
    private suspend fun nextJob(): QueuedGeneration =
        combine(
            _generationQueue,
            _isQueuePaused,
            ForgeRepository.isServerBusy,
            ForgeRepository.isConnected,
        ) { queue, paused, busy, connected ->
            queue.firstOrNull()?.takeIf { !paused && !busy && connected }
        }.filterNotNull().first()

    /** Continues a queue paused by a lost connection once the server is back and idle (with a short delay). */
    private fun startReconnectWatcher() {
        ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
            combine(_queuePauseReason, ForgeRepository.isConnected, ForgeRepository.isServerBusy) { reason, connected, busy ->
                reason == CONNECTION_LOST_REASON && connected && !busy
            }.distinctUntilChanged().collectLatest { serverBack ->
                if (!serverBack) return@collectLatest
                delay(AUTO_RETRY_DELAY_MS)
                if (_queuePauseReason.value == CONNECTION_LOST_REASON) {
                    ForgeNotifications.cancel(ForgeNotifications.ID_QUEUE_PAUSED)
                    _queuePauseReason.value = null
                    _isQueuePaused.value = false
                    _statusText.value = "Connection restored, continuing the queue"
                }
            }
        }
    }

    /** Marks [job] GENERATING if it is still first in the queue (the user may have removed or moved it). */
    private fun claim(job: QueuedGeneration): QueuedGeneration? {
        var claimed: QueuedGeneration? = null
        _generationQueue.update { queue ->
            val first = queue.firstOrNull()
            claimed = first?.takeIf { it.id == job.id }?.copy(status = GenerationStatus.GENERATING)
            claimed?.let { listOf(it) + queue.drop(1) } ?: queue
        }
        return claimed
    }

    /**
     * Sends the jobs one after another. It used to poll every 500 ms for as long as the app lived, and
     * queueGeneration() could also start a job itself, guarded only by a flag.
     */
    private fun startQueueWorker() {
        ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
            while (isActive) {
                val job = claim(nextJob()) ?: continue
                _isGenerating.value = true
                saveQueueState()
                try {
                    executeGeneration(job)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // executeGeneration handles its own errors; anything else must not end the queue for good.
                    Log.e(TAG, "Unexpected error while running a job", e)
                    _isGenerating.value = false
                    pauseQueue("Unexpected error: ${e.localizedMessage}")
                }
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

            // The worker picks the job up by itself as soon as it may start.
            val qSize = _generationQueue.value.size
            if (qSize == 1) {
                _totalQueueSize.value = 1
                _completedQueueItems.value = 0
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

        var connectionLost = false
        try {
            val answer = requestWithWatchdog(job, shouldSaveToDevice)
            if (answer is Answer.Images) {
                if (answer.files.isNotEmpty()) {
                    val currentList = _sessionImages.value.toMutableList()
                    val startIndex = currentList.size
                    currentList += answer.files.map { it.absolutePath }

                    val endIndex = currentList.size - 1
                    _sessionImages.value = currentList
                    _currentBatchStartIndex.value = startIndex
                    _currentBatchEndIndex.value = endIndex
                    _currentSessionIndex.value = endIndex
                    _statusText.value = "Generation Complete"
                    _livePreviewImage.value = null

                    if (config.showGridAfterGeneration && answer.files.size > 1) {
                        _isShowingGridPreview.value = true
                    }

                    succeeded = true
                }
            } else if (answer is Answer.Failed) {
                val errorBody = answer.body
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
                    OomLogs.report(
                        reason = "The server ran out of memory.",
                        details = "${describeForReport(job)}\n\nServer answer (HTTP ${answer.code}):\n$errorBody",
                    )
                } else {
                    _statusText.value = "Error: HTTP ${answer.code}"
                    if (!config.overnightMode) {
                        pauseQueue("The server returned HTTP ${answer.code}.")
                        errorReason = "The server returned HTTP ${answer.code}."
                    }
                }
            }
        } catch (e: CancellationException) {
            Log.d(TAG, "Generation cancelled")
            throw e
        } catch (e: ConnectionLost) {
            // The connection broke (refused, reset, timed out): the job stays, also in overnight mode, where every
            // following job used to fail at once and the whole queue was thrown away.
            connectionLost = true
            val losses = connectionLosses.merge(job.id, 1, Int::plus) ?: 1
            if (losses <= MAX_AUTO_RETRIES) {
                pauseQueue(CONNECTION_LOST_REASON)
                _statusText.value = "Connection lost. The job is sent again when the server is back."
            } else {
                pauseQueue("The connection was lost $losses times while sending this job. Resume the queue to try again.")
                _statusText.value = "Connection lost"
            }
            errorReason = _queuePauseReason.value
        } catch (e: OutOfMemoryError) {
            // The images are on the server anyway; the app (and the queue) must survive a batch too big to decode.
            _statusText.value = "The images are too large for the phone's memory"
            val reason = "The images were too large for the phone's memory. They are saved on the server."
            if (!ForgeRepository.config.value.overnightMode) pauseQueue(reason)
            errorReason = reason
            OomLogs.report(
                reason = "The app ran out of memory while reading the images.",
                details = "${describeForReport(job)}\n\n${e.stackTraceToString()}",
            )
        } catch (e: Exception) {
            _statusText.value = "Failed: ${e.localizedMessage}"
            if (!ForgeRepository.config.value.overnightMode) {
                pauseQueue("Generation failed: ${e.localizedMessage}")
                errorReason = "Generation failed: ${e.localizedMessage}"
            }
        } finally {
            if (connectionLost) {
                keepForRetry(job, errorReason ?: CONNECTION_LOST_REASON)
            } else {
                finishJob(job, succeeded, errorReason, isOom)
            }
            saveQueueState()
            _isGenerating.value = false
            _progress.value = if (connectionLost) 0f else 1f
            _currentEta.value = 0.0
        }
    }

    /** The settings of [job] for an out-of-memory report; the prompts stay out of it. */
    private fun describeForReport(job: QueuedGeneration): String =
        with(job.payload) {
            val hires = if (enable_hr) "x$hr_scale with $hr_upscaler, denoising $denoising_strength" else "off"
            val model = override_settings.sdModelCheckpoint ?: "(current)"
            "Job: ${width}x$height, batch size $batch_size, batch count $n_iter, steps $steps, " +
                "sampler $sampler_name ($scheduler), hires fix $hires, model $model"
        }

    /** What the server answered to a job. */
    private sealed interface Answer {
        data class Images(
            val files: List<File>,
        ) : Answer

        data class Failed(
            val code: Int,
            val body: String,
        ) : Answer
    }

    /** Writing an image to the phone failed; not a network error, so the job is not sent again. */
    private class SaveFailed(
        cause: Throwable,
    ) : Exception(cause.message, cause)

    /**
     * Sends [job], while a watchdog looks for a request orphaned by an outage: the connection was lost, and since
     * it is back the server stays idle, so no answer will come on the old connection. The watchdog stops once the
     * server has answered, so a slow download of the images is never taken for an orphaned request.
     */
    private suspend fun requestWithWatchdog(
        job: QueuedGeneration,
        saveToDevice: Boolean,
    ): Answer =
        coroutineScope {
            val answered = AtomicBoolean(false)
            val call =
                async {
                    try {
                        val api = ForgeRepository.generationApi ?: throw java.io.IOException("Not connected to the server")
                        val response = api.generateImage(job.payload)
                        answered.set(true)
                        if (response.isSuccessful) {
                            Answer.Images(response.body()?.use { readImages(it, saveToDevice) }.orEmpty())
                        } else {
                            Answer.Failed(response.code(), response.errorBody()?.string().orEmpty())
                        }
                    } catch (e: com.google.gson.stream.MalformedJsonException) {
                        throw e // a broken answer, not a broken connection
                    } catch (e: java.io.IOException) {
                        throw ConnectionLost(e)
                    }
                }
            val watchdog =
                launch {
                    var outageSeen = false
                    var idleSince = 0L
                    while (!answered.get()) {
                        delay(1000)
                        val connected = ForgeRepository.isConnected.value
                        val busy = ForgeRepository.isServerBusy.value
                        when {
                            answered.get() -> return@launch
                            !connected -> {
                                outageSeen = true
                                idleSince = 0L
                                _statusText.value = "Connection lost, waiting for the server..."
                            }
                            outageSeen && !busy -> {
                                if (idleSince == 0L) idleSince = System.currentTimeMillis()
                                if (System.currentTimeMillis() - idleSince >= ORPHANED_REQUEST_IDLE_MS) {
                                    call.cancel(OrphanedRequest())
                                    return@launch
                                }
                            }
                            else -> idleSince = 0L
                        }
                    }
                }
            try {
                call.await()
            } catch (e: OrphanedRequest) {
                ensureActive() // only the watchdog cancels with OrphanedRequest; a stopped worker stays stopped
                throw ConnectionLost(e)
            } finally {
                watchdog.cancel()
            }
        }

    /**
     * Reads the images from the answer one at a time and writes each to the cache as soon as it is read: the
     * whole batch used to be held in memory as base64 text (several times its size), enough to crash the app with
     * big images. Other fields of the answer are skipped without being read into memory.
     */
    private fun readImages(
        body: okhttp3.ResponseBody,
        saveToDevice: Boolean,
    ): List<File> {
        val files = mutableListOf<File>()
        com.google.gson.stream.JsonReader(body.charStream()).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() != "images") {
                    reader.skipValue()
                    continue
                }
                reader.beginArray()
                while (reader.hasNext()) {
                    val base64 = reader.nextString()
                    files +=
                        try {
                            saveGeneratedImage(base64, files.size, saveToDevice)
                        } catch (e: java.io.IOException) {
                            throw SaveFailed(e)
                        }
                }
                reader.endArray()
            }
            reader.endObject()
        }
        return files
    }

    private fun saveGeneratedImage(
        base64: String,
        index: Int,
        saveToDevice: Boolean,
    ): File {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        val file = File(application.cacheDir, "gen_${System.currentTimeMillis()}_$index.png")
        file.writeBytes(bytes)

        // The first image of the batch is the local fallback "last generated image".
        if (index == 0) {
            try {
                file.copyTo(File(application.cacheDir, "last_generated_image.png"), overwrite = true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cache local last generated image", e)
            }
        }
        if (saveToDevice) {
            try {
                DeviceImages.save(application, "Gen_${System.currentTimeMillis()}_$index.png") { it.write(bytes) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save generated image directly to device", e)
            }
        }
        return file
    }

    /** A job whose connection broke stays first in the queue, waiting to be sent again. */
    private fun keepForRetry(
        job: QueuedGeneration,
        reason: String,
    ) {
        _generationQueue.update { q -> q.map { if (it.id == job.id) it.copy(status = GenerationStatus.SUSPENDED) else it } }
        notifyGenerationError(reason, isOom = false, queuePaused = true)
        try {
            application.startService(Intent(application, GenerationService::class.java).setAction(GenerationService.ACTION_QUEUE_FINISHED))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to notify service of the paused queue", e)
        }
    }

    /** Removes a finished (or failed) job and posts at most one notification for it. */
    private fun finishJob(
        job: QueuedGeneration,
        succeeded: Boolean,
        errorReason: String?,
        isOom: Boolean,
    ) {
        connectionLosses.remove(job.id)
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
        when {
            errorReason != null -> notifyGenerationError(errorReason, isOom, queuePaused = !queueEmpty)
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
                .setOnlyAlertOnce(true) // repeated connection losses update the alert without sounding again
                .build()
        ForgeNotifications.post(ForgeNotifications.ID_QUEUE_PAUSED, notification)
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
        // The running job stays: the server is already working on it and the worker removes it when it is done.
        _generationQueue.update { q -> q.filter { it.status == GenerationStatus.GENERATING } }
        _totalQueueSize.value = _generationQueue.value.size
        _completedQueueItems.value = 0
        saveQueueState()
    }

    /** The job being sent to the server; it stays first until it is done. */
    private fun isActiveItem(
        queue: List<QueuedGeneration>,
        index: Int,
    ) = queue.getOrNull(index)?.status == GenerationStatus.GENERATING

    fun removeFromQueue(id: String) {
        var removed = false
        _generationQueue.update { currentQueue ->
            // Decided inside the update, so the worker cannot claim the job between the check and the removal.
            val newQueue = currentQueue.filter { it.id != id || it.status == GenerationStatus.GENERATING }
            removed = newQueue.size < currentQueue.size
            newQueue
        }
        if (removed) _totalQueueSize.update { maxOf(_completedQueueItems.value, it - 1) }
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
        // A manual resume also gives a job whose connection kept failing its automatic retries back.
        _generationQueue.value.firstOrNull()?.let { connectionLosses.remove(it.id) }
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
