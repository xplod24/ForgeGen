package com.yourname.forgegen

import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.forgegen.R
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.yourname.forgegen.Translator.t

// --- Idiomatic Extension for Non-Blocking OkHttp Calls ---
suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response)
        }

        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isCancelled) return
            continuation.resumeWithException(e)
        }
    })

    continuation.invokeOnCancellation {
        try { cancel() } catch (ex: Throwable) { /* Ignore */ }
    }
}

data class ActiveLora(val name: String, val strength: Float)

class ForgeViewModel(private val application: Application) : AndroidViewModel(application) {

    private val TAG = "ForgeAPI"

    private val prefs = application.getSharedPreferences("ForgeGenPrefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<AppConfig> = _config.asStateFlow()

    var client: OkHttpClient = createClient(_config.value.connectionTimeout)
        private set

    private val _appState = MutableStateFlow(loadState())
    val appState: StateFlow<AppState> = _appState.asStateFlow()

    private val _promptHistory = MutableStateFlow(loadPromptHistory())
    val promptHistory: StateFlow<List<PromptHistoryItem>> = _promptHistory.asStateFlow()

    val activeLoras: StateFlow<List<ActiveLora>> = _appState.map { state ->
        val regex = Regex("<lora:([^:]+):([0-9.]+)>")
        regex.findAll(state.positivePrompt).mapNotNull { match ->
            val n = match.groupValues[1]
            val s = match.groupValues[2].toFloatOrNull() ?: 1f
            ActiveLora(n, s)
        }.toList()
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _lastPromptState = MutableStateFlow(AppState())

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _isAppInForeground = MutableStateFlow(true)

    private val _pingMs = MutableStateFlow(0L)
    val pingMs: StateFlow<Long> = _pingMs.asStateFlow()

    val progress: StateFlow<Float> = ForgeState.progress.asStateFlow()
    val statusText: StateFlow<String> = ForgeState.statusText.asStateFlow()
    val isGenerating: StateFlow<Boolean> = ForgeState.isGenerating.asStateFlow()
    val sessionImages: StateFlow<List<String>> = ForgeState.sessionImages.asStateFlow()
    val currentSessionIndex: StateFlow<Int> = ForgeState.currentSessionIndex.asStateFlow()

    private val _useMultiThreading = MutableStateFlow(prefs.getBoolean("multi_threading", true))
    val useMultiThreading: StateFlow<Boolean> = _useMultiThreading.asStateFlow()
    private var workerDispatcher: CoroutineDispatcher = Dispatchers.Default

    private var _allTags = emptyList<String>()

    private val _tagSuggestions = MutableStateFlow<List<String>>(emptyList())
    val tagSuggestions: StateFlow<List<String>> = _tagSuggestions.asStateFlow()

    private val _isRestoringPrompt = MutableStateFlow(false)
    val isRestoringPrompt: StateFlow<Boolean> = _isRestoringPrompt.asStateFlow()

    private val _selectedModel = MutableStateFlow("")
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()

    val samplers = MutableStateFlow<List<String>>(emptyList())
    val schedulers = MutableStateFlow<List<String>>(emptyList())
    val models = MutableStateFlow<List<ApiResource>>(emptyList())
    val upscalers = MutableStateFlow<List<String>>(emptyList())
    val availableLoras = MutableStateFlow<List<ApiResource>>(emptyList())

    private val _galleryFiles = MutableStateFlow<List<GalleryItem>>(emptyList())
    val galleryFiles: StateFlow<List<GalleryItem>> = _galleryFiles.asStateFlow()

    private val _currentGalleryPath = MutableStateFlow("")
    val currentGalleryPath: StateFlow<String> = _currentGalleryPath.asStateFlow()

    private val _isGalleryLoading = MutableStateFlow(false)
    val isGalleryLoading: StateFlow<Boolean> = _isGalleryLoading.asStateFlow()

    private val _galleryError = MutableStateFlow<String?>(null)
    val galleryError: StateFlow<String?> = _galleryError.asStateFlow()

    private val _showGalleryMetadata = MutableStateFlow(prefs.getBoolean("show_gallery_meta", false))
    val showGalleryMetadata: StateFlow<Boolean> = _showGalleryMetadata.asStateFlow()

    private val _currentImageMetadata = MutableStateFlow<String?>(null)
    val currentImageMetadata: StateFlow<String?> = _currentImageMetadata.asStateFlow()

    private val _galleryMode = MutableStateFlow(GalleryMode.NORMAL)
    val galleryMode: StateFlow<GalleryMode> = _galleryMode.asStateFlow()

    init {
        refreshDispatcher()
        startBackgroundPing()
        fetchApiData()
        loadTags()
        startQueueManager()
        cleanupRecoveredImages()
    }

    private fun cleanupRecoveredImages() {
        viewModelScope.launch(Dispatchers.IO) {
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

    private suspend fun saveRecoveredImageToCache(bytes: ByteArray) {
        withContext(Dispatchers.IO) {
            try {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

                options.inSampleSize = calculateInSampleSize(options, 512, 512)
                options.inJustDecodeBounds = false

                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                val file = File(application.cacheDir, "recovered_${System.currentTimeMillis()}.png")

                FileOutputStream(file).use { out ->
                    bitmap?.compress(Bitmap.CompressFormat.PNG, 85, out)
                }
                bitmap?.recycle()

                // StateFlow update does not need withContext(Dispatchers.Main)
                ForgeState.sessionImages.value = listOf(file.absolutePath)
                ForgeState.currentSessionIndex.value = 0
                ForgeState.currentBatchStartIndex.value = 0
                ForgeState.currentBatchEndIndex.value = 0
                ForgeState.isShowingGridPreview.value = false
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cache recovered image", e)
            }
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
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

    fun setAppForegroundState(isForeground: Boolean) {
        _isAppInForeground.value = isForeground
    }

    fun setGalleryMode(mode: GalleryMode) {
        _galleryMode.value = mode
    }

    private fun createClient(timeoutSeconds: Int): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val requestBuilder = originalRequest.newBuilder()

                val isGalleryCall = originalRequest.url.encodedPath.contains("infinite_image_browsing")

                if (isGalleryCall) {
                    requestBuilder.header("Cookie", "IIB_S=bf63789069ec13d6b7b95a5176468e99f8940fe6aa65931edc17e1abf5c5e172")
                }

                try {
                    chain.proceed(requestBuilder.build())
                } catch (e: Exception) {
                    Log.e(TAG, "API CALL FAILED: ${e.message}", e)
                    throw e
                }
            }
            .build()
    }

    private fun loadConfig(): AppConfig {
        val json = prefs.getString("config", null)
        val parsed = if (json != null) {
            try { gson.fromJson(json, AppConfig::class.java) } catch(e: Exception) { null }
        } else null

        val oldLivePreviewState = try {
            val jsonObj = JSONObject(json ?: "{}")
            jsonObj.optBoolean("livePreviews", false)
        } catch(e: Exception) { false }

        val finalPreviewMode = parsed?.previewMode ?: if (oldLivePreviewState) "Normal" else "Finished"

        return AppConfig(
            apiUrl = parsed?.apiUrl ?: "http://192.168.1.90:7860",
            galleryPath = parsed?.galleryPath ?: "C:\\webui_forge_cu124_torch24\\webui\\outputs\\txt2img-images",
            checkpointPath = parsed?.checkpointPath ?: "C:\\webui_forge_cu124_torch24\\webui\\models\\Stable-diffusion",
            loraPath = parsed?.loraPath ?: "C:\\webui_forge_cu124_torch24\\webui\\models\\Lora",
            language = parsed?.language ?: "en",
            isDarkMode = parsed?.isDarkMode ?: false,
            connectionTimeout = parsed?.connectionTimeout ?: 10,
            checkpointTimeout = parsed?.checkpointTimeout ?: 45,
            receiveGenerationNotification = parsed?.receiveGenerationNotification ?: true,
            silentNotifications = parsed?.silentNotifications ?: false,
            notificationVerbosity = parsed?.notificationVerbosity ?: "Full",
            keepScreenOn = parsed?.keepScreenOn ?: false,
            screenDimming = parsed?.screenDimming ?: false,
            screenDimmingTimeout = parsed?.screenDimmingTimeout ?: 5,
            swipeToBrowseGallery = parsed?.swipeToBrowseGallery ?: true,
            galleryGridColumns = parsed?.galleryGridColumns ?: 3,
            serverProfiles = parsed?.serverProfiles ?: listOf(ServerProfile("Default Local", "http://192.168.1.90:7860")),
            previewMode = finalPreviewMode,
            useNativeSecurity = parsed?.useNativeSecurity ?: false,
            useBiometricLock = parsed?.useBiometricLock ?: false,
            overnightMode = parsed?.overnightMode ?: false,
            showGridAfterGeneration = parsed?.showGridAfterGeneration ?: true,
            showActiveTagsUI = parsed?.showActiveTagsUI ?: true,
            defaultState = parsed?.defaultState ?: AppState(),
            presets = parsed?.presets ?: emptyList()
        )
    }

    fun saveConfig(newConfig: AppConfig) {
        var cleanUrl = newConfig.apiUrl.trim()
        if (cleanUrl.isNotEmpty() && !cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            cleanUrl = "http://$cleanUrl"
        }
        val updatedConfig = newConfig.copy(apiUrl = cleanUrl)
        _config.value = updatedConfig
        prefs.edit().putString("config", gson.toJson(updatedConfig)).apply()

        client = client.newBuilder()
            .connectTimeout(updatedConfig.connectionTimeout.toLong(), TimeUnit.SECONDS)
            .build()
    }

    fun saveCurrentAsDefault() {
        val currentConfig = _config.value
        val newState = currentConfig.copy(defaultState = _appState.value.copy())
        saveConfig(newState)
        Toast.makeText(application, "Set Current as Default".t, Toast.LENGTH_SHORT).show()
    }

    fun resetToDefaults() {
        _appState.value = _config.value.defaultState.copy()
        prefs.edit().putString("last_state", gson.toJson(_appState.value)).apply()
        Toast.makeText(application, "Reset to Defaults".t, Toast.LENGTH_SHORT).show()
    }

    fun savePreset(name: String) {
        val currentPresets = _config.value.presets.toMutableList()
        currentPresets.removeAll { it.name == name }
        currentPresets.add(GenerationPreset(name, _appState.value.copy()))
        saveConfig(_config.value.copy(presets = currentPresets))
    }

    fun loadPreset(name: String) {
        val preset = _config.value.presets.find { it.name == name }
        if (preset != null) {
            _appState.value = preset.state.copy()
            prefs.edit().putString("last_state", gson.toJson(_appState.value)).apply()
            Toast.makeText(application, "Loaded: ".t + name, Toast.LENGTH_SHORT).show()
        }
    }

    fun deletePreset(name: String) {
        val currentPresets = _config.value.presets.toMutableList()
        currentPresets.removeAll { it.name == name }
        saveConfig(_config.value.copy(presets = currentPresets))
    }

    fun getPreviewUrl(originalPath: String, isLora: Boolean = false): String {
        if (originalPath.isEmpty()) return ""
        val urlStr = _config.value.apiUrl.trimEnd('/')

        val fullPath = if (!originalPath.contains("\\") && !originalPath.contains("/")) {
            val dir = if (isLora) _config.value.loraPath else _config.value.checkpointPath
            val separator = if (dir.contains("\\")) "\\" else "/"
            if (dir.isNotEmpty()) "$dir$separator$originalPath" else originalPath
        } else originalPath

        val basePath = fullPath.substringBeforeLast(".safetensors").substringBeforeLast(".ckpt").substringBeforeLast(".pt")
        return "$urlStr/file=$basePath.preview.png"
    }

    fun fetchLoraTriggerWords(lora: ApiResource, onResult: (List<String>) -> Unit) {
        viewModelScope.launch(workerDispatcher) {
            try {
                val urlStr = _config.value.apiUrl.trimEnd('/')
                val path = if (!lora.path.contains("\\") && !lora.path.contains("/")) {
                    val dir = _config.value.loraPath
                    val separator = if (dir.contains("\\")) "\\" else "/"
                    if (dir.isNotEmpty()) "$dir$separator${lora.path}" else lora.path
                } else lora.path

                val basePath = path.substringBeforeLast(".safetensors").substringBeforeLast(".ckpt").substringBeforeLast(".pt")
                val jsonUrl = "$urlStr/file=$basePath.json"

                val request = Request.Builder().url(jsonUrl).build()
                client.newCall(request).await().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: "{}"
                        val json = JSONObject(body)
                        val activationText = json.optString("activation text", "")
                        val tags = activationText.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        onResult(tags)
                    } else {
                        onResult(emptyList())
                    }
                }
            } catch(e: Exception) {
                onResult(emptyList())
            }
        }
    }

    fun updateGalleryGridColumns(cols: Int) {
        val newConfig = _config.value.copy(galleryGridColumns = cols)
        _config.value = newConfig
        prefs.edit().putString("config", gson.toJson(newConfig)).apply()
    }

    fun addServerProfile(name: String, url: String) {
        val currentProfiles = _config.value.serverProfiles.toMutableList()
        currentProfiles.removeAll { it.name == name }
        currentProfiles.add(ServerProfile(name, url))
        saveConfig(_config.value.copy(serverProfiles = currentProfiles))
    }

    fun removeServerProfile(name: String) {
        val currentProfiles = _config.value.serverProfiles.toMutableList()
        currentProfiles.removeAll { it.name == name }
        saveConfig(_config.value.copy(serverProfiles = currentProfiles))
    }

    private fun loadState(): AppState {
        val json = prefs.getString("last_state", null)
        val parsed = if (json != null) {
            try { gson.fromJson(json, AppState::class.java) } catch(e: Exception) { null }
        } else null

        if (parsed != null) return parsed
        return loadConfig().defaultState.copy()
    }

    fun updateState(update: (AppState) -> AppState) {
        val newState = update(_appState.value)
        _appState.value = newState
        prefs.edit().putString("last_state", gson.toJson(newState)).apply()
    }

    private fun loadPromptHistory(): List<PromptHistoryItem> {
        val json = prefs.getString("prompt_history", null)
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val type = object : TypeToken<List<PromptHistoryItem>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveToPromptHistory(positive: String, negative: String) {
        if (positive.isBlank() && negative.isBlank()) return

        val currentList = _promptHistory.value.toMutableList()
        if (currentList.isNotEmpty() && currentList.first().positivePrompt == positive && currentList.first().negativePrompt == negative) {
            return
        }

        val newItem = PromptHistoryItem(positive, negative, System.currentTimeMillis())
        currentList.add(0, newItem)

        val trimmedList = currentList.take(20)
        _promptHistory.value = trimmedList
        prefs.edit().putString("prompt_history", gson.toJson(trimmedList)).apply()
    }

    fun clearPromptHistory() {
        _promptHistory.value = emptyList()
        prefs.edit().remove("prompt_history").apply()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun refreshDispatcher() {
        val pm = application.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val isIgnoringOpt = pm.isIgnoringBatteryOptimizations(application.packageName)
        workerDispatcher = if (_useMultiThreading.value && isIgnoringOpt) {
            Dispatchers.Default
        } else {
            Dispatchers.IO.limitedParallelism(1)
        }
    }

    fun setMultiThreading(use: Boolean) {
        _useMultiThreading.value = use
        prefs.edit().putBoolean("multi_threading", use).apply()
        refreshDispatcher()
    }

    fun resumeQueue() {
        ForgeState.isQueuePaused.value = false
        ForgeState.oomAlert.value = false
        ForgeState.statusText.value = "Queue Resumed".t
    }

    fun interruptGeneration() {
        viewModelScope.launch(workerDispatcher) {
            try {
                val url = _config.value.apiUrl.trimEnd('/')
                val request = Request.Builder().url("$url/sdapi/v1/interrupt").post("{}".toRequestBody("application/json".toMediaType())).build()
                client.newCall(request).await().close()
                ForgeState.statusText.value = "Interrupting...".t
            } catch (e: Exception) {
                Log.e(TAG, "Failed to interrupt", e)
            }
        }
    }

    private fun extractPngParameters(bytes: ByteArray): String {
        try {
            if (bytes.size < 8) return ""
            var offset = 8
            while (offset < bytes.size - 8) {
                val length = java.nio.ByteBuffer.wrap(bytes, offset, 4).int
                offset += 4
                val chunkType = String(bytes, offset, 4)
                offset += 4
                if (chunkType == "tEXt" || chunkType == "iTXt") {
                    val chunkData = bytes.copyOfRange(offset, offset + length)
                    val nullIndex = chunkData.indexOf(0.toByte())
                    if (nullIndex != -1) {
                        val keyword = String(chunkData.copyOfRange(0, nullIndex), Charsets.ISO_8859_1)
                        if (keyword == "parameters") {
                            return String(chunkData.copyOfRange(nullIndex + 1, chunkData.size), Charsets.UTF_8)
                        }
                    }
                }
                offset += length + 4
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing PNG chunks", e)
        }
        return ""
    }

    private fun startQueueManager() {
        viewModelScope.launch(workerDispatcher) {
            var lastGenerationTime = 0L
            while (true) {
                try {
                    val queue = ForgeState.generationQueue.value
                    val isBusy = ForgeState.isServerBusy.value
                    val isGeneratingLocally = ForgeState.isGenerating.value
                    val isPaused = ForgeState.isQueuePaused.value

                    if (queue.isNotEmpty() && !isBusy && !isGeneratingLocally && !isPaused) {
                        val timeSinceLast = System.currentTimeMillis() - lastGenerationTime

                        if (lastGenerationTime != 0L && timeSinceLast < 10000) {
                            val secondsLeft = (10000 - timeSinceLast) / 1000
                            ForgeState.statusText.value = "Queue Cooldown".t + " (${secondsLeft}s)..."
                            delay(1000)
                            continue
                        }

                        val nextJob = queue.first()
                        ForgeState.isGenerating.value = true
                        executeGeneration(nextJob)
                        lastGenerationTime = System.currentTimeMillis()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Queue Manager Exception", e)
                }
                delay(1000)
            }
        }
    }

    fun queueGeneration() {
        val state = _appState.value
        val currentModel = _selectedModel.value.ifEmpty { null }

        val payload = Txt2ImgPayload(
            prompt = state.positivePrompt, negative_prompt = state.negativePrompt,
            steps = state.steps, cfg_scale = state.cfgScale, width = state.width, height = state.height,
            n_iter = state.batchCount, batch_size = state.batchSize,
            seed = state.seed, sampler_name = state.sampler, scheduler = state.scheduler,
            override_settings = OverrideSettings(
                clipSkip = state.clipSkip,
                sdModelCheckpoint = currentModel
            ),
            enable_hr = state.hiresFix, hr_scale = state.hiresScale,
            hr_upscaler = state.upscaler, denoising_strength = state.denoising
        )

        val item = QueuedGeneration(
            id = java.util.UUID.randomUUID().toString(),
            positivePrompt = state.positivePrompt,
            payload = payload
        )

        ForgeState.generationQueue.update { it + item }

        if (ForgeState.generationQueue.value.size == 1 && !ForgeState.isGenerating.value) {
            ForgeState.totalQueueSize.value = 1
            ForgeState.completedQueueItems.value = 0
        } else {
            ForgeState.totalQueueSize.update { it + 1 }
        }

        saveToPromptHistory(state.positivePrompt, state.negativePrompt)

        if (ForgeState.isServerBusy.value && !ForgeState.isGenerating.value) {
            Toast.makeText(application, "External generation active. Added to queue.".t, Toast.LENGTH_SHORT).show()
        } else if (ForgeState.isGenerating.value) {
            Toast.makeText(application, "Added to queue.".t, Toast.LENGTH_SHORT).show()
        }
    }

    fun updateQueueItem(id: String, positivePrompt: String, negativePrompt: String) {
        ForgeState.generationQueue.update { currentQueue ->
            currentQueue.map {
                if (it.id == id) {
                    it.copy(
                        positivePrompt = positivePrompt,
                        payload = it.payload.copy(prompt = positivePrompt, negative_prompt = negativePrompt)
                    )
                } else it
            }
        }
    }

    fun clearQueue() {
        ForgeState.generationQueue.value = emptyList()
        ForgeState.totalQueueSize.value = 0
        ForgeState.completedQueueItems.value = 0
    }

    fun removeFromQueue(id: String) {
        ForgeState.generationQueue.update { currentQueue ->
            val prevSize = currentQueue.size
            val newQueue = currentQueue.filter { it.id != id }
            if (newQueue.size < prevSize) {
                ForgeState.totalQueueSize.update { maxOf(ForgeState.completedQueueItems.value, it - 1) }
            }
            newQueue
        }
    }

    fun moveQueueItemUp(id: String) {
        ForgeState.generationQueue.update { q ->
            val idx = q.indexOfFirst { it.id == id }
            if (idx > 0) {
                val list = q.toMutableList()
                java.util.Collections.swap(list, idx, idx - 1)
                list
            } else q
        }
    }

    fun moveQueueItemDown(id: String) {
        ForgeState.generationQueue.update { q ->
            val idx = q.indexOfFirst { it.id == id }
            if (idx in 0 until q.size - 1) {
                val list = q.toMutableList()
                java.util.Collections.swap(list, idx, idx + 1)
                list
            } else q
        }
    }

    private suspend fun executeGeneration(job: QueuedGeneration) {
        _lastPromptState.value = _appState.value.copy()
        ForgeState.progress.value = 0f
        ForgeState.currentEta.value = 0.0
        ForgeState.livePreviewImage.value = null
        ForgeState.isShowingGridPreview.value = false

        val previewText = job.positivePrompt.take(30).replace("\n", " ")
        ForgeState.statusText.value = "Generating:".t + " \"$previewText...\""

        val serviceIntent = Intent(application, GenerationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            application.startForegroundService(serviceIntent)
        } else {
            application.startService(serviceIntent)
        }

        try {
            val url = _config.value.apiUrl.trimEnd('/')
            val jsonPayload = gson.toJson(job.payload)
            val body = jsonPayload.toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url("$url/sdapi/v1/txt2img").post(body).build()

            client.newCall(request).await().use { response ->
                val responseBody = response.body?.string() ?: "{}"
                if (response.isSuccessful) {
                    val txt2ImgData = gson.fromJson(responseBody, Txt2ImgResponse::class.java)

                    if (txt2ImgData.images.isNotEmpty()) {
                        val currentList = ForgeState.sessionImages.value.toMutableList()
                        val startIndex = currentList.size

                        val cachePath = application.cacheDir.absolutePath

                        for ((i, b64) in txt2ImgData.images.withIndex()) {
                            val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                            val file = java.io.File("$cachePath/gen_${System.currentTimeMillis()}_$i.png")
                            file.writeBytes(bytes)
                            currentList.add(file.absolutePath)
                        }

                        val endIndex = currentList.size - 1

                        ForgeState.sessionImages.value = currentList
                        ForgeState.currentBatchStartIndex.value = startIndex
                        ForgeState.currentBatchEndIndex.value = endIndex
                        ForgeState.currentSessionIndex.value = endIndex
                        ForgeState.statusText.value = "Generation Complete".t
                        ForgeState.livePreviewImage.value = null

                        if (_config.value.showGridAfterGeneration && txt2ImgData.images.size > 1) {
                            ForgeState.isShowingGridPreview.value = true
                        }

                        if (_config.value.receiveGenerationNotification) {
                            try {
                                val notifManager = application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                                val openIntent = Intent(application, MainActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                }
                                val pendingIntent = PendingIntent.getActivity(application, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                                val notif = NotificationCompat.Builder(application, "GenerationChannelAlert")
                                    .setSmallIcon(R.mipmap.ic_launcher_foreground)
                                    .setContentTitle("Receive Generation Completion Notification".t)
                                    .setContentText("Batch completed: ${job.positivePrompt.take(35)}...")
                                    .setContentIntent(pendingIntent)
                                    .setAutoCancel(true)
                                    .build()
                                notifManager.notify(1002 + job.id.hashCode() % 1000, notif)
                            } catch (e: Exception) {
                                Log.e(TAG, "Notification Launch Failed", e)
                            }
                        }
                    }

                } else {
                    if (response.code == 500 || responseBody.contains("OutOfMemoryError", true) || responseBody.contains("CUDA out of memory", true)) {
                        ForgeState.statusText.value = "SERVER OUT OF MEMORY (OOM)".t
                        ForgeState.isQueuePaused.value = true
                        ForgeState.oomAlert.value = true
                    } else {
                        ForgeState.statusText.value = "Error:".t + " ${response.code}"
                        if (!_config.value.overnightMode) ForgeState.isQueuePaused.value = true
                    }
                }
            }
        } catch (e: Exception) {
            ForgeState.statusText.value = "Failed:".t + " ${e.localizedMessage}"
            if (!_config.value.overnightMode) {
                ForgeState.isQueuePaused.value = true
            }
        } finally {
            ForgeState.generationQueue.update { q -> q.filter { it.id != job.id } }
            ForgeState.completedQueueItems.update { it + 1 }

            if (ForgeState.generationQueue.value.isEmpty()) {
                ForgeState.totalQueueSize.value = 0
                ForgeState.completedQueueItems.value = 0
            }

            ForgeState.isGenerating.value = false
            ForgeState.progress.value = 1f
            ForgeState.currentEta.value = 0.0
        }
    }

    private fun startBackgroundPing() {
        viewModelScope.launch(Dispatchers.IO) {
            var failCount = 0
            while (true) {
                try {
                    val url = _config.value.apiUrl.trimEnd('/')
                    if (url.isNotEmpty()) {
                        val start = System.currentTimeMillis()
                        val skipImage = _config.value.previewMode != "Normal"

                        val request = Request.Builder().url("$url/sdapi/v1/progress?skip_current_image=$skipImage").build()
                        client.newCall(request).await().use { response ->
                            if (response.isSuccessful) {
                                _pingMs.value = System.currentTimeMillis() - start
                                _isConnected.value = true
                                failCount = 0

                                val body = response.body?.string()
                                if (body != null) {
                                    val progressData = gson.fromJson(body, ProgressResponse::class.java)
                                    val progressVal = progressData.progress.toFloat()
                                    val etaVal = progressData.etaRelative
                                    val jobCount = progressData.state?.jobCount ?: 0

                                    val currentImageStr = progressData.currentImage ?: ""
                                    if (currentImageStr.isNotEmpty() && !skipImage) {
                                        ForgeState.livePreviewImage.value = currentImageStr
                                    } else {
                                        if (!ForgeState.isGenerating.value) ForgeState.livePreviewImage.value = null
                                    }

                                    val busy = progressVal > 0.001f || jobCount > 0
                                    ForgeState.isServerBusy.value = busy
                                    ForgeState.progress.value = progressVal
                                    ForgeState.currentEta.value = etaVal

                                    progressData.state?.let { stateObj ->
                                        ForgeState.currentJobNo.value = stateObj.jobNo
                                        ForgeState.currentJobCount.value = stateObj.jobCount
                                        ForgeState.currentSamplingStep.value = stateObj.samplingStep
                                        ForgeState.currentSamplingSteps.value = stateObj.samplingSteps
                                    }

                                    if (busy && !ForgeState.isGenerating.value) {
                                        ForgeState.statusText.value = "External Task:".t + " ${(progressVal * 100).toInt()}%"
                                    } else if (!busy && !ForgeState.isGenerating.value) {
                                        ForgeState.statusText.value = "Ready".t
                                    }
                                }
                            } else if (response.code == 401 || response.code == 403) {
                                _isConnected.value = false
                                ForgeState.isServerBusy.value = false
                                ForgeState.currentEta.value = 0.0
                                failCount = 0
                                ForgeState.statusText.value = "Authentication Required.".t
                            } else throw Exception("Bad Status")
                        }

                        try {
                            val memReq = Request.Builder().url("$url/sdapi/v1/memory").build()
                            client.newCall(memReq).await().use { res ->
                                if (res.isSuccessful) {
                                    val json = JSONObject(res.body?.string() ?: "{}")
                                    var memStr = ""

                                    val ram = json.optJSONObject("ram")
                                    if (ram != null) {
                                        val ramUsed = ram.optDouble("used", 0.0) / (1024.0 * 1024.0 * 1024.0)
                                        val ramTotal = ram.optDouble("total", 0.0) / (1024.0 * 1024.0 * 1024.0)
                                        if (ramTotal > 0) {
                                            memStr += "RAM: ${String.format(Locale.US, "%.1f", ramUsed)}/${String.format(Locale.US, "%.1f", ramTotal)}GB"
                                        }
                                    }

                                    val cuda = json.optJSONObject("cuda")
                                    val system = cuda?.optJSONObject("system")
                                    if (system != null) {
                                        val vramUsed = system.optDouble("used", 0.0) / (1024.0 * 1024.0 * 1024.0)
                                        val vramTotal = system.optDouble("total", 0.0) / (1024.0 * 1024.0 * 1024.0)
                                        if (vramTotal > 0) {
                                            val vramStr = "VRAM: ${String.format(Locale.US, "%.1f", vramUsed)}/${String.format(Locale.US, "%.1f", vramTotal)}GB"
                                            memStr += if (memStr.isNotEmpty()) " | $vramStr" else vramStr
                                        }
                                    }

                                    ForgeState.vramUsage.value = memStr.ifEmpty { null }
                                }
                            }
                        } catch (e: Exception) {
                            ForgeState.vramUsage.value = null
                        }

                    }
                } catch (e: Exception) {
                    _isConnected.value = false
                    ForgeState.isServerBusy.value = false
                    ForgeState.currentEta.value = 0.0
                    ForgeState.vramUsage.value = null
                    failCount++
                    if (failCount >= _config.value.connectionTimeout && !ForgeState.isGenerating.value) {
                        ForgeState.statusText.value = "Connection Lost (Timeout)".t
                    }
                }

                val isForeground = _isAppInForeground.value
                val isActivelyGenerating = ForgeState.isGenerating.value || ForgeState.isServerBusy.value

                val pingInterval = if (isForeground || isActivelyGenerating) 1000L else 10000L
                delay(pingInterval)
            }
        }
    }

    fun toggleGalleryMetadata() {
        val newVal = !_showGalleryMetadata.value
        _showGalleryMetadata.value = newVal
        prefs.edit().putBoolean("show_gallery_meta", newVal).apply()
    }

    fun loadMetadataForImage(item: GalleryItem?) {
        if (item == null) {
            _currentImageMetadata.value = null
            return
        }
        _currentImageMetadata.value = "Loading metadata...".t
        viewModelScope.launch(workerDispatcher) {
            try {
                val imageUrl = getGalleryImageUrl(item)
                if (imageUrl.isEmpty()) {
                    _currentImageMetadata.value = "Invalid URL.".t
                    return@launch
                }
                val imgReq = Request.Builder().url(imageUrl).build()
                var imgBytes: ByteArray? = null
                client.newCall(imgReq).await().use { res ->
                    if (res.isSuccessful) {
                        imgBytes = res.body?.bytes()
                    }
                }

                if (imgBytes != null) {
                    val infoStr = extractPngParameters(imgBytes!!)
                    _currentImageMetadata.value = if (infoStr.isNotBlank()) infoStr else "No generation data found.".t
                } else {
                    _currentImageMetadata.value = "Failed to load image.".t
                }
            } catch (e: Exception) {
                _currentImageMetadata.value = "Failed:".t + " ${e.message}"
            }
        }
    }

    fun loadMetadataForLocalFile(filePath: String) {
        _currentImageMetadata.value = "Loading metadata...".t
        viewModelScope.launch(workerDispatcher) {
            try {
                val file = java.io.File(filePath)
                if (!file.exists()) {
                    _currentImageMetadata.value = "File not found locally.".t
                    return@launch
                }

                val bytes = file.readBytes()
                val infoStr = extractPngParameters(bytes)

                _currentImageMetadata.value = if (infoStr.isNotBlank()) infoStr else "No generation data found.".t
            } catch (e: Exception) {
                _currentImageMetadata.value = "Failed:".t + " ${e.message}"
            }
        }
    }

    fun changeCheckpoint(modelTitle: String) {
        _selectedModel.value = modelTitle
        viewModelScope.launch(workerDispatcher) {
            try {
                val url = _config.value.apiUrl.trimEnd('/')
                val payload = JSONObject().apply { put("sd_model_checkpoint", modelTitle) }
                val body = payload.toString().toRequestBody("application/json".toMediaType())
                val req = Request.Builder().url("$url/sdapi/v1/options").post(body).build()
                client.newCall(req).await().close()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to switch model", e)
            }
        }
    }

    fun appendLora(name: String) {
        val current = _appState.value.positivePrompt
        if (!current.contains("<lora:$name:")) {
            val prefix = if (current.isNotEmpty() && !current.endsWith(",")) ", " else ""
            val newPrompt = current.trimEnd() + prefix + "<lora:$name:1.0>"
            updateState { it.copy(positivePrompt = newPrompt) }
        }
    }

    fun updateLoraStrength(name: String, strength: Float) {
        val current = _appState.value.positivePrompt
        val regex = Regex("<lora:${Regex.escape(name)}:[0-9.]+>")
        val formattedStrength = String.format(Locale.US, "%.2f", strength)
        val newPrompt = current.replace(regex, "<lora:$name:$formattedStrength>")
        updateState { it.copy(positivePrompt = newPrompt) }
    }

    fun removeLora(name: String) {
        val current = _appState.value.positivePrompt
        val escapedName = Regex.escape(name)
        val regex = Regex(",?\\s*<lora:$escapedName:[0-9.]+>\\s*,?")
        var newPrompt = current.replace(regex, ", ").trim()
        if (newPrompt.startsWith(",")) newPrompt = newPrompt.substring(1).trim()
        if (newPrompt.endsWith(",")) newPrompt = newPrompt.substring(0, newPrompt.length - 1).trim()
        updateState { it.copy(positivePrompt = newPrompt) }
    }

    private fun loadTags() {
        viewModelScope.launch(workerDispatcher) {
            val cachePath = application.cacheDir.absolutePath
            val file = java.io.File("$cachePath/tags.csv")
            if (!file.exists()) {
                try {
                    val url = "https://raw.githubusercontent.com/DominikDoom/a1111-sd-webui-tagcomplete/main/tags/danbooru.csv"
                    val request = Request.Builder().url(url).build()
                    client.newCall(request).await().use { res ->
                        if (res.isSuccessful) file.writeText(res.body?.string() ?: "")
                    }
                } catch (e: Exception) {}
            }
            if (file.exists()) {
                val tempTags = mutableListOf<String>()
                file.useLines { lines ->
                    lines.forEach { line ->
                        val parts = line.split(",")
                        if (parts.isNotEmpty() && parts[0].isNotBlank()) tempTags.add(parts[0])
                    }
                }
                _allTags = tempTags.toList()
            }
        }
    }

    fun searchTags(query: String) {
        if (query.length < 2) {
            _tagSuggestions.value = emptyList()
            return
        }
        viewModelScope.launch(workerDispatcher) {
            _tagSuggestions.value = _allTags.filter { it.startsWith(query, ignoreCase = true) }.take(8)
        }
    }

    private fun parseGalleryItems(json: String): List<GalleryItem> {
        val list = mutableListOf<GalleryItem>()
        if (json.isEmpty()) return list
        try {
            val fileList = gson.fromJson(json, GalleryFileList::class.java)
            if (fileList?.files != null) list.addAll(fileList.files)
        } catch (e: Exception) {
            try {
                val type = object : TypeToken<List<GalleryItem>>() {}.type
                val arrayItems = gson.fromJson<List<GalleryItem>>(json, type)
                if (arrayItems != null) list.addAll(arrayItems)
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to parse gallery items. JSON: $json", e2)
            }
        }
        return list
    }

    private suspend fun fetchLastGeneratedImageInfo(): String? {
        val urlStr = _config.value.apiUrl.trimEnd('/')
        val rootPath = _config.value.galleryPath

        suspend fun fetchFiles(folder: String): List<GalleryItem> {
            val builder = urlStr.toHttpUrlOrNull()?.newBuilder()
                ?.addPathSegments("infinite_image_browsing/files")
            if (folder.isNotEmpty() && folder != "Root") {
                builder?.addQueryParameter("folder_path", folder)
            }
            val url = builder?.build() ?: return emptyList()

            client.newCall(Request.Builder().url(url).build()).await().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    return parseGalleryItems(responseBody)
                } else if (response.code == 400 && folder.isNotEmpty() && folder != "Root") {
                    return fetchFiles("Root")
                }
            }
            return emptyList()
        }

        val rootItems = fetchFiles(rootPath)
        val currentDateStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())

        var targetFile: GalleryItem? = null
        val candidateImages = mutableListOf<GalleryItem>()

        val todayFolder = rootItems.find { it.isDir && it.name == currentDateStr }
        if (todayFolder != null) {
            candidateImages.addAll(fetchFiles(todayFolder.fullpath).filter { !it.isDir })
        }

        if (candidateImages.isEmpty()) {
            val dateFolders = rootItems.filter { it.isDir }.sortedByDescending { it.name }
            for (folder in dateFolders) {
                val folderImages = fetchFiles(folder.fullpath).filter { !it.isDir }
                if (folderImages.isNotEmpty()) {
                    candidateImages.addAll(folderImages)
                    break
                }
            }
        }

        if (candidateImages.isEmpty()) {
            candidateImages.addAll(rootItems.filter { !it.isDir })
        }

        if (candidateImages.isNotEmpty()) {
            targetFile = candidateImages.maxByOrNull { item ->
                item.name.take(5).toIntOrNull() ?: -1
            }
        }

        if (targetFile != null) {
            val imageUrl = getGalleryImageUrl(targetFile)
            if (imageUrl.isNotEmpty()) {
                val imgReq = Request.Builder().url(imageUrl).build()
                var imgBytes: ByteArray? = null
                client.newCall(imgReq).await().use { res ->
                    if (res.isSuccessful) imgBytes = res.body?.bytes()
                }

                if (imgBytes != null) {
                    saveRecoveredImageToCache(imgBytes!!)
                    return extractPngParameters(imgBytes!!)
                }
            }
        }
        return null
    }

    fun recoverLastPrompt() {
        if (_isRestoringPrompt.value) return
        _isRestoringPrompt.value = true

        viewModelScope.launch(workerDispatcher) {
            try {
                val infoStr = fetchLastGeneratedImageInfo()
                if (infoStr != null) {
                    withContext(Dispatchers.Main) {
                        parseAndApplyPngInfo(infoStr)
                        _isRestoringPrompt.value = false
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        updateState { _lastPromptState.value.copy() }
                        Toast.makeText(application, "Used local cache (No images found in gallery)".t, Toast.LENGTH_SHORT).show()
                        _isRestoringPrompt.value = false
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    updateState { _lastPromptState.value.copy() }
                    Toast.makeText(application, "Used local cache (Network error)".t, Toast.LENGTH_SHORT).show()
                    _isRestoringPrompt.value = false
                }
            }
        }
    }

    fun recoverLastSeed() {
        viewModelScope.launch(workerDispatcher) {
            try {
                val infoStr = fetchLastGeneratedImageInfo()
                if (infoStr != null) {
                    var foundSeed: Long? = null
                    infoStr.split("\n").forEach { line ->
                        if (line.startsWith("Steps:")) {
                            val params = line.split(", ")
                            params.forEach { pair ->
                                val kv = pair.split(": ")
                                if (kv.size == 2 && kv[0].trim() == "Seed") {
                                    foundSeed = kv[1].trim().toLongOrNull()
                                }
                            }
                        }
                    }
                    withContext(Dispatchers.Main) {
                        if (foundSeed != null) {
                            updateState { it.copy(seed = foundSeed!!) }
                            Toast.makeText(application, "${"Seed recovered:".t} $foundSeed", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(application, "No seed found in last image".t, Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(application, "Failed to find last image".t, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(application, "Network error recovering seed".t, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun recoverPromptFromImage(item: GalleryItem) {
        if (_isRestoringPrompt.value) return
        _isRestoringPrompt.value = true

        viewModelScope.launch(workerDispatcher) {
            try {
                val imageUrl = getGalleryImageUrl(item)
                if (imageUrl.isEmpty()) {
                    withContext(Dispatchers.Main) { Toast.makeText(application, "Invalid Image URL".t, Toast.LENGTH_SHORT).show() }
                    _isRestoringPrompt.value = false
                    return@launch
                }

                val imgReq = Request.Builder().url(imageUrl).build()
                var imgBytes: ByteArray? = null

                client.newCall(imgReq).await().use { res ->
                    if (res.isSuccessful) {
                        imgBytes = res.body?.bytes()
                    }
                }

                if (imgBytes != null) {
                    val infoStr = extractPngParameters(imgBytes!!)
                    saveRecoveredImageToCache(imgBytes!!)
                    withContext(Dispatchers.Main) {
                        parseAndApplyPngInfo(infoStr)
                        _isRestoringPrompt.value = false
                    }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(application, "Failed to extract data".t, Toast.LENGTH_SHORT).show()
                    _isRestoringPrompt.value = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(application, "Network error".t, Toast.LENGTH_SHORT).show()
                    _isRestoringPrompt.value = false
                }
            }
        }
    }

    private fun parseAndApplyPngInfo(info: String) {
        if (info.isEmpty()) return
        var pos = ""
        var neg = ""
        var params = ""

        val lines = info.split("\n")
        var currentMode = 0

        for (line in lines) {
            if (line.startsWith("Negative prompt:")) {
                currentMode = 1
                neg += line.substringAfter("Negative prompt:").trim() + "\n"
            } else if (line.startsWith("Steps:")) {
                currentMode = 2
                params = line
            } else {
                if (currentMode == 0) pos += line + "\n"
                else if (currentMode == 1) neg += line + "\n"
            }
        }

        updateState { state ->
            val newState = state.copy(
                positivePrompt = pos.trim(),
                negativePrompt = neg.trim()
            )

            val paramPairs = params.split(", ")
            paramPairs.forEach { pair ->
                val kv = pair.split(": ")
                if (kv.size == 2) {
                    val k = kv[0].trim()
                    val v = kv[1].trim()
                    when (k) {
                        "Steps" -> newState.steps = v.toIntOrNull() ?: newState.steps
                        "CFG scale" -> newState.cfgScale = v.toFloatOrNull() ?: newState.cfgScale
                        "Seed" -> newState.seed = v.toLongOrNull() ?: newState.seed
                        "Sampler" -> newState.sampler = v
                        "Size" -> {
                            val dims = v.split("x")
                            if (dims.size == 2) {
                                newState.width = dims[0].toIntOrNull() ?: newState.width
                                newState.height = dims[1].toIntOrNull() ?: newState.height
                            }
                        }
                        "Clip skip" -> newState.clipSkip = v.toIntOrNull() ?: newState.clipSkip
                    }
                }
            }
            newState
        }
        Toast.makeText(application, "Loaded generation data".t, Toast.LENGTH_SHORT).show()
    }

    private fun fetchApiData() {
        viewModelScope.launch(Dispatchers.IO) {
            val url = _config.value.apiUrl.trimEnd('/')
            if (url.isEmpty()) return@launch
            try {
                val listType = object : TypeToken<List<NameResponse>>() {}.type

                listOf("samplers", "schedulers", "upscalers").forEach { endpoint ->
                    val request = Request.Builder().url("$url/sdapi/v1/$endpoint").build()
                    client.newCall(request).await().use { res ->
                        if (res.isSuccessful) {
                            val list = gson.fromJson<List<NameResponse>>(res.body?.string() ?: "[]", listType).map { it.name }
                            when(endpoint) {
                                "samplers" -> samplers.value = list
                                "schedulers" -> schedulers.value = list
                                "upscalers" -> upscalers.value = list
                            }
                        }
                    }
                }

                val modelReq = Request.Builder().url("$url/sdapi/v1/sd-models").build()
                client.newCall(modelReq).await().use { res ->
                    if (res.isSuccessful) {
                        val type = object : TypeToken<List<SdModelItem>>() {}.type
                        val sdModels = gson.fromJson<List<SdModelItem>>(res.body?.string() ?: "[]", type)
                        models.value = sdModels.map {
                            ApiResource(it.title, it.filename ?: "", it.modelName)
                        }
                    }
                }

                val loraReq = Request.Builder().url("$url/sdapi/v1/loras").build()
                client.newCall(loraReq).await().use { res ->
                    if (res.isSuccessful) {
                        val type = object : TypeToken<List<LoraItem>>() {}.type
                        val lorasList = gson.fromJson<List<LoraItem>>(res.body?.string() ?: "[]", type)
                        availableLoras.value = lorasList.map {
                            ApiResource(it.name, it.path ?: "", it.name)
                        }
                    }
                }

                val reqOpts = Request.Builder().url("$url/sdapi/v1/options").build()
                client.newCall(reqOpts).await().use { res ->
                    if (res.isSuccessful) {
                        val optionsData = gson.fromJson(res.body?.string() ?: "{}", OptionsResponse::class.java)
                        _selectedModel.value = optionsData.sdModelCheckpoint ?: ""
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to synchronize API definitions: ${e.message}")
            }
        }
    }

    fun fetchGalleryFolder(path: String = _config.value.galleryPath) {
        _isGalleryLoading.value = true
        _galleryError.value = null

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val baseUrlStr = _config.value.apiUrl.trimEnd('/')
                val builder = baseUrlStr.toHttpUrlOrNull()?.newBuilder()
                    ?.addPathSegments("infinite_image_browsing/files")

                if (path.isNotEmpty() && path != "Root") {
                    builder?.addQueryParameter("folder_path", path)
                }

                val url = builder?.build() ?: throw Exception("Invalid API URL format")
                val request = Request.Builder().url(url).build()

                client.newCall(request).await().use { response ->
                    val responseBody = response.body?.string() ?: ""
                    if (response.isSuccessful) {
                        _galleryFiles.value = parseGalleryItems(responseBody).sortedWith(compareBy({ !it.isDir }, { it.name }))
                        _currentGalleryPath.value = path
                    } else {
                        if (response.code == 400 && path.isNotEmpty() && path != "Root") {
                            fetchGalleryFolder("Root")
                            return@launch
                        } else if (response.code == 401 || response.code == 403) {
                            _galleryError.value = "Authentication Required.".t
                        } else {
                            _galleryError.value = "Server returned Error ".t + "${response.code}"
                        }
                    }
                }
            } catch (e: Exception) {
                _galleryError.value = e.message ?: "Failed to reach server.".t
            } finally {
                _isGalleryLoading.value = false
            }
        }
    }

    fun getGalleryImageUrl(item: GalleryItem): String {
        val urlStr = _config.value.apiUrl.trimEnd('/')
        val builder = urlStr.toHttpUrlOrNull()?.newBuilder()
            ?.addPathSegments("infinite_image_browsing/file")
            ?.addQueryParameter("path", item.fullpath)

        if (!item.date.isNullOrEmpty()) {
            builder?.addQueryParameter("t", item.date)
        }

        return builder?.build()?.toString() ?: ""
    }

    fun downloadSessionImage(localFilePath: String) {
        viewModelScope.launch(workerDispatcher) {
            try {
                val file = File(localFilePath)
                if (!file.exists()) throw Exception("Local file missing".t)

                val bytes = file.readBytes()
                val fileName = "Gen_${System.currentTimeMillis()}.png"

                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ForgeGen")
                    }
                }

                val resolver = application.contentResolver
                val insertUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }

                val uri = resolver.insert(insertUri, contentValues)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    withContext(Dispatchers.Main) { Toast.makeText(application, "Saved to Downloads".t, Toast.LENGTH_SHORT).show() }
                } else throw Exception("Failed to create file in MediaStore".t)

            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(application, "Download Failed:".t + " ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    fun downloadImage(item: GalleryItem) {
        viewModelScope.launch(workerDispatcher) {
            try {
                val url = getGalleryImageUrl(item)
                if (url.isEmpty()) throw Exception("Invalid Gallery URL".t)

                val request = Request.Builder().url(url).build()

                client.newCall(request).await().use { response ->
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes() ?: throw Exception("Empty response body".t)
                        val contentValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
                            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ForgeGen")
                            }
                        }

                        val resolver = application.contentResolver
                        val insertUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI
                        } else {
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        }

                        val uri = resolver.insert(insertUri, contentValues)

                        if (uri != null) {
                            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                            withContext(Dispatchers.Main) { Toast.makeText(application, "Saved to Downloads".t, Toast.LENGTH_SHORT).show() }
                        } else throw Exception("Failed to create file in MediaStore".t)
                    } else throw Exception("Server returned ".t + "${response.code}")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(application, "Download Failed:".t + " ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    fun shareSessionImage(localFilePath: String, onIntentReady: (Intent) -> Unit) {
        viewModelScope.launch(workerDispatcher) {
            try {
                val file = File(localFilePath)
                if (!file.exists()) throw Exception("Local file missing".t)

                val bytes = file.readBytes()
                val fileName = "Shared_${System.currentTimeMillis()}.png"

                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ForgeGen_Shared")
                    }
                }

                val resolver = application.contentResolver
                val insertUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val uri = resolver.insert(insertUri, contentValues)

                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { it.write(bytes) }

                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    withContext(Dispatchers.Main) {
                        onIntentReady(Intent.createChooser(shareIntent, "Share Image".t))
                    }
                } else throw Exception("Failed to prepare file for sharing".t)
            } catch(e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(application, "Share Failed:".t + " ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    fun shareImage(item: GalleryItem, onIntentReady: (Intent) -> Unit) {
        viewModelScope.launch(workerDispatcher) {
            try {
                val url = getGalleryImageUrl(item)
                if (url.isEmpty()) throw Exception("Invalid Gallery URL".t)

                val request = Request.Builder().url(url).build()

                client.newCall(request).await().use { response ->
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes() ?: throw Exception("Empty response body".t)
                        val contentValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, "Shared_${item.name}")
                            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ForgeGen_Shared")
                            }
                        }

                        val resolver = application.contentResolver
                        val insertUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        val uri = resolver.insert(insertUri, contentValues)

                        if (uri != null) {
                            resolver.openOutputStream(uri)?.use { it.write(bytes) }

                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "image/png"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            withContext(Dispatchers.Main) {
                                onIntentReady(Intent.createChooser(shareIntent, "Share Image".t))
                            }
                        } else throw Exception("Failed to prepare file for sharing".t)
                    } else throw Exception("Server returned ".t + "${response.code}")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(application, "Share Failed:".t + " ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    fun dismissGridPreview(index: Int? = null) {
        ForgeState.isShowingGridPreview.value = false
        if (index != null && index in 0 until ForgeState.sessionImages.value.size) {
            ForgeState.currentSessionIndex.value = index
        }
    }

    fun sessionPrev() {
        ForgeState.isShowingGridPreview.value = false
        val idx = ForgeState.currentSessionIndex.value
        if (idx > ForgeState.currentBatchStartIndex.value) ForgeState.currentSessionIndex.value = idx - 1
    }

    fun sessionNext() {
        ForgeState.isShowingGridPreview.value = false
        val idx = ForgeState.currentSessionIndex.value
        if (idx < ForgeState.currentBatchEndIndex.value) ForgeState.currentSessionIndex.value = idx + 1
    }
}