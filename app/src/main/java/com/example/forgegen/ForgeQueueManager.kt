package com.example.forgegen

import android.annotation.SuppressLint
import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.FileOutputStream
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
    private lateinit var application: Application
    private val gson = Gson()
    private val QUEUE_KEY = stringPreferencesKey("saved_queue")

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
        loadQueueState()
        startQueueLoop()
        cleanupRecoveredImages()
    }

    fun updateExternalProgress(progress: Float, eta: Double, image: String?) {
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
            val prefs = application.dataStore.data.first()
            val json = prefs[QUEUE_KEY]
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
                application.dataStore.edit { it[QUEUE_KEY] = gson.toJson(_generationQueue.value) }
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

                    if (firstItem != null && !_isGenerating.value && !ForgeRepository.isServerBusy.value && !_isQueuePaused.value) {

                        // Resume suspended task if the queue is unpaused
                        if (firstItem.status == GenerationStatus.SUSPENDED) {
                            _generationQueue.update { q ->
                                val list = q.toMutableList()
                                if (list.isNotEmpty()) list[0] = list[0].copy(status = GenerationStatus.GENERATING)
                                list
                            }
                        }

                        _isGenerating.value = true
                        currentGenerationJob = launch {
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

    private fun applyWildcards(prompt: String, allWildcards: List<WildcardEntity>): String {
        var result = prompt
        val regex = Regex("__([a-zA-Z0-9_\\-]+)__")
        var match = regex.find(result)

        while (match != null) {
            val wildcardName = match.groupValues[1]
            val entity = allWildcards.find { it.name == wildcardName }
            var replacement = match.value
            if (entity != null) {
                val options = entity.content.split("\n", ",").map { it.trim() }.filter { it.isNotEmpty() }
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

            val payload = Txt2ImgPayloadDto(
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
                override_settings = OverrideSettingsDto(
                    clipSkip = state.clipSkip,
                    sdModelCheckpoint = currentModel
                ),
                enable_hr = state.hiresFix,
                hr_scale = state.hiresScale,
                hr_upscaler = state.upscaler,
                denoising_strength = state.denoising,
                save_images = state.saveImages,
                send_images = true
            )

            val item = QueuedGeneration(
                id = UUID.randomUUID().toString(),
                positivePrompt = finalPositive,
                payload = payload,
                status = GenerationStatus.QUEUED
            )

            _generationQueue.update { it + item }
            saveQueueState()

            val qSize = _generationQueue.value.size
            if (qSize == 1) {
                _totalQueueSize.value = 1
                _completedQueueItems.value = 0

                if (!_isGenerating.value && !ForgeRepository.isServerBusy.value && !_isQueuePaused.value) {
                    _isGenerating.value = true
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
        val shouldSaveToDevice = ForgeRepository.appState.value.saveToDevice

        _progress.value = 0f
        _currentEta.value = 0.0
        _livePreviewImage.value = null
        _isShowingGridPreview.value = false

        val previewText = job.positivePrompt.take(30).replace("\n", " ")
        val initialBatchInfo = if (job.payload.n_iter > 1) "(Batch 1 of ${job.payload.n_iter}) " else ""
        _statusText.value = "Preparing $initialBatchInfo\"$previewText...\""

        val serviceIntent = Intent(application, GenerationService::class.java).apply {
            action = "ACTION_START_GENERATION"
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
                                ForgeSettingsManager.saveLastGeneratedInfo(txt2ImgData.info)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to cache local last generated image", e)
                            }
                        }

                        if (shouldSaveToDevice) {
                            try {
                                val fileName = "Gen_${System.currentTimeMillis()}_$i.png"
                                val contentValues = ContentValues().apply {
                                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ForgeGen")
                                }
                                val resolver = application.contentResolver
                                val insertUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                                val uri = resolver.insert(insertUri, contentValues)
                                if (uri != null) {
                                    resolver.openOutputStream(uri)?.use { out ->
                                        out.write(bytes)
                                    }
                                }
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

                    if (config.notifOnBatchFinish) {
                        launchNotification(job.positivePrompt, currentList.lastOrNull(), config, isQueueFinished = false)
                    }
                }
            } else {
                val errorBody = response?.errorBody()?.string() ?: ""
                if (response?.code() == 500 || errorBody.contains("OutOfMemoryError", true) || errorBody.contains("CUDA out of memory", true)) {
                    _statusText.value = "SERVER OUT OF MEMORY (OOM)"
                    _isQueuePaused.value = true
                    _oomAlert.value = true
                } else {
                    _statusText.value = "Error: ${response?.code()}"
                    if (!config.overnightMode) _isQueuePaused.value = true
                }
            }
        } catch (e: CancellationException) {
            Log.d(TAG, "Generation cancelled or suspended")
            throw e
        } catch (e: Exception) {
            _statusText.value = "Failed: ${e.localizedMessage}"
            if (!ForgeRepository.config.value.overnightMode) {
                _isQueuePaused.value = true
            }
        } finally {
            val isSuspended = _generationQueue.value.firstOrNull()?.id == job.id && _generationQueue.value.firstOrNull()?.status == GenerationStatus.SUSPENDED

            if (!isSuspended) {
                _generationQueue.update { q -> q.filter { it.id != job.id } }
                _completedQueueItems.update { it + 1 }

                if (_generationQueue.value.isEmpty()) {
                    _totalQueueSize.value = 0
                    _completedQueueItems.value = 0
                    
                    if (ForgeRepository.config.value.notifOnQueueFinish) {
                        launchNotification(job.positivePrompt, _sessionImages.value.lastOrNull(), ForgeRepository.config.value, isQueueFinished = true)
                    }

                    try {
                        val finishIntent = Intent(application, GenerationService::class.java).apply { action = "ACTION_QUEUE_FINISHED" }
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

    private fun launchNotification(prompt: String, lastImagePath: String?, config: AppConfig, isQueueFinished: Boolean = false) {
        try {
            val notifManager = application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val openIntent = Intent(application, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(application, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val channelId = "forge_default"
            val title = if (isQueueFinished) "Queue Completed" else "Batch Completed"
            val text = if (isQueueFinished) "All generation jobs have finished." else "Finished: ${prompt.take(35)}..."

            val builder = NotificationCompat.Builder(application, channelId)
                .setSmallIcon(R.mipmap.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)



            val uniqueNotifId = System.currentTimeMillis().toInt()
            notifManager.notify(uniqueNotifId, builder.build())
        } catch (e: Exception) {
            Log.e(TAG, "Notification Launch Failed", e)
        }
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
        _isQueuePaused.value = true
        _statusText.value = "Queue Suspended (Connection Lost)"

        currentGenerationJob?.cancel()

        try {
            val finishIntent = Intent(application, GenerationService::class.java).apply { action = "ACTION_QUEUE_FINISHED" }
            application.startService(finishIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to notify service of queue finish", e)
        }

        saveQueueState()
    }

    fun updateQueueItem(id: String, positivePrompt: String, negativePrompt: String) {
        _generationQueue.update { currentQueue ->
            currentQueue.map {
                if (it.id == id) {
                    it.copy(positivePrompt = positivePrompt, payload = it.payload.copy(prompt = positivePrompt, negative_prompt = negativePrompt))
                } else it
            }
        }
        saveQueueState()
    }

    fun clearQueue() {
        _generationQueue.value = emptyList()
        _totalQueueSize.value = 0
        _completedQueueItems.value = 0
        saveQueueState()
    }

    fun removeFromQueue(id: String) {
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
            if (idx > 0) {
                val list = q.toMutableList()
                Collections.swap(list, idx, idx - 1)
                list
            } else q
        }
        saveQueueState()
    }

    fun moveQueueItemDown(id: String) {
        _generationQueue.update { q ->
            val idx = q.indexOfFirst { it.id == id }
            if (idx in 0 until q.size - 1) {
                val list = q.toMutableList()
                Collections.swap(list, idx, idx + 1)
                list
            } else q
        }
        saveQueueState()
    }

    fun resumeQueue() {
        _isQueuePaused.value = false
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

    private fun calculateInSampleSize(options: BitmapFactory.Options): Int {
        val reqHeight = 512
        val reqWidth = 512
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun cleanupRecoveredImages() {
        ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
            try {
                application.cacheDir.listFiles()?.forEach { file ->
                    if (file.name.startsWith("recovered_") && file.name.endsWith(".png")) {
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

                val fileName = "Gen_${System.currentTimeMillis()}.png"
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ForgeGen")
                }

                val resolver = application.contentResolver
                val insertUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val uri = resolver.insert(insertUri, contentValues)

                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { outStream ->
                        file.inputStream().use { inStream ->
                            inStream.copyTo(outStream)
                        }
                    }
                    ForgeRepository.showToast("Saved to Downloads")
                } else throw Exception("Failed to create file in MediaStore")
            } catch (e: Exception) {
                ForgeRepository.showToast("Download Failed: ${e.message}")
            }
        }
    }

    fun shareSessionImage(localFilePath: String, onIntentReady: (Intent) -> Unit) {
        ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
            try {
                val file = File(localFilePath)
                if (!file.exists()) throw Exception("Local file missing")

                val fileName = "Shared_${System.currentTimeMillis()}.png"
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ForgeGen_Shared")
                }

                val resolver = application.contentResolver
                val insertUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val uri = resolver.insert(insertUri, contentValues)

                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { outStream ->
                        file.inputStream().use { inStream ->
                            inStream.copyTo(outStream)
                        }
                    }

                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    withContext(Dispatchers.Main) { onIntentReady(Intent.createChooser(shareIntent, "Share Image")) }
                } else throw Exception("Failed to prepare file for sharing")
            } catch(e: Exception) {
                ForgeRepository.showToast("Share Failed: ${e.message}")
            }
        }
    }
}