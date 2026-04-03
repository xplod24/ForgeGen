@file:Suppress("unused", "MemberVisibilityCanBePrivate", "UNNECESSARY_SAFE_CALL")

package com.example.forgegen

import android.annotation.SuppressLint
import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.icu.text.SimpleDateFormat
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.room.Room
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/* ============================================================================
 * IDIOMATIC COROUTINES EXTENSIONS
 * Converts callback-based OkHttp calls to clean, suspendable coroutine functions.
 * ============================================================================ */

suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
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
        try { cancel() } catch (_: Throwable) { /* Ignore */ }
    }
}

data class ActiveLora(val name: String, val strength: Float)

/* ============================================================================
 * DATA REPOSITORY (SINGLETON)
 * Handles API orchestration, state management, background battery optimization,
 * queue failsafe mechanisms, and background service delegation.
 * ============================================================================ */

@SuppressLint("StaticFieldLeak")
object ForgeRepository {

    private const val TAG = "ForgeAPI"

    private lateinit var application: Application
    private lateinit var prefs: SharedPreferences
    private lateinit var db: ForgeDatabase
    private val gson = Gson()

    val repositoryScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _config = MutableStateFlow(AppConfig())
    val config: StateFlow<AppConfig> = _config.asStateFlow()

    var client: OkHttpClient = OkHttpClient()
        private set

    private val _appState = MutableStateFlow(AppState())
    val appState: StateFlow<AppState> = _appState.asStateFlow()

    private val _promptHistory = MutableStateFlow<List<PromptHistoryItem>>(emptyList())
    val promptHistory: StateFlow<List<PromptHistoryItem>> = _promptHistory.asStateFlow()

    @Suppress("RedundantCollectionOperation")
    val activeLoras: StateFlow<List<ActiveLora>> = _appState.map { state ->
        val regex = Regex("<lora:([^:]+):([0-9.]+)>")
        regex.findAll(state.positivePrompt).map { match ->
            val n = match.groupValues[1]
            val s = match.groupValues[2].toFloatOrNull() ?: 1f
            ActiveLora(n, s)
        }.toList()
    }.stateIn(repositoryScope, SharingStarted.Lazily, emptyList())

    private val _lastPromptState = MutableStateFlow(AppState())

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _isAppInForeground = MutableStateFlow(true)

    private val _pingMs = MutableStateFlow(0L)
    val pingMs: StateFlow<Long> = _pingMs.asStateFlow()

    private val _serverStats = MutableStateFlow<List<ServerStatRecord>>(emptyList())
    val serverStats: StateFlow<List<ServerStatRecord>> = _serverStats.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _currentEta = MutableStateFlow(0.0)
    val currentEta: StateFlow<Double> = _currentEta.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _statusText = MutableStateFlow("Ready")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _currentJobNo = MutableStateFlow(0)
    val currentJobNo: StateFlow<Int> = _currentJobNo.asStateFlow()

    private val _currentJobCount = MutableStateFlow(0)
    val currentJobCount: StateFlow<Int> = _currentJobCount.asStateFlow()

    private val _currentSamplingStep = MutableStateFlow(0)
    val currentSamplingStep: StateFlow<Int> = _currentSamplingStep.asStateFlow()

    private val _currentSamplingSteps = MutableStateFlow(0)
    val currentSamplingSteps: StateFlow<Int> = _currentSamplingSteps.asStateFlow()

    private val _isServerBusy = MutableStateFlow(false)
    val isServerBusy: StateFlow<Boolean> = _isServerBusy.asStateFlow()

    private val _generationQueue = MutableStateFlow<List<QueuedGeneration>>(emptyList())
    val generationQueue: StateFlow<List<QueuedGeneration>> = _generationQueue.asStateFlow()

    private val _isQueuePaused = MutableStateFlow(false)
    val isQueuePaused: StateFlow<Boolean> = _isQueuePaused.asStateFlow()

    private val _oomAlert = MutableStateFlow(false)
    val oomAlert: StateFlow<Boolean> = _oomAlert.asStateFlow()

    private val _vramUsage = MutableStateFlow<String?>(null)
    val vramUsage: StateFlow<String?> = _vramUsage.asStateFlow()

    private val _totalQueueSize = MutableStateFlow(0)
    val totalQueueSize: StateFlow<Int> = _totalQueueSize.asStateFlow()

    private val _completedQueueItems = MutableStateFlow(0)
    val completedQueueItems: StateFlow<Int> = _completedQueueItems.asStateFlow()

    private val _sessionImages = MutableStateFlow<List<String>>(emptyList())
    val sessionImages: StateFlow<List<String>> = _sessionImages.asStateFlow()

    private val _currentSessionIndex = MutableStateFlow(-1)
    val currentSessionIndex: StateFlow<Int> = _currentSessionIndex.asStateFlow()

    private val _livePreviewImage = MutableStateFlow<String?>(null)
    val livePreviewImage: StateFlow<String?> = _livePreviewImage.asStateFlow()

    private val _isShowingGridPreview = MutableStateFlow(false)
    val isShowingGridPreview: StateFlow<Boolean> = _isShowingGridPreview.asStateFlow()

    private val _currentBatchStartIndex = MutableStateFlow(0)
    val currentBatchStartIndex: StateFlow<Int> = _currentBatchStartIndex.asStateFlow()

    private val _currentBatchEndIndex = MutableStateFlow(-1)
    val currentBatchEndIndex: StateFlow<Int> = _currentBatchEndIndex.asStateFlow()

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

    private val _showGalleryMetadata = MutableStateFlow(false)
    val showGalleryMetadata: StateFlow<Boolean> = _showGalleryMetadata.asStateFlow()

    private val _currentImageMetadata = MutableStateFlow<String?>(null)
    val currentImageMetadata: StateFlow<String?> = _currentImageMetadata.asStateFlow()

    private val _galleryMode = MutableStateFlow(GalleryMode.NORMAL)
    val galleryMode: StateFlow<GalleryMode> = _galleryMode.asStateFlow()

    private var isInitialized = false

    fun init(app: Application) {
        if (isInitialized) return
        application = app
        prefs = app.getSharedPreferences("ForgeGenPrefs", Context.MODE_PRIVATE)

        db = Room.databaseBuilder(app, ForgeDatabase::class.java, "forge_db")
            .fallbackToDestructiveMigration(false)
            .build()

        _config.value = loadConfig()
        _appState.value = loadState()
        _promptHistory.value = loadPromptHistory()
        _showGalleryMetadata.value = prefs.getBoolean("show_gallery_meta", false)

        client = createClient(_config.value.connectionTimeout)

        loadQueueState()
        loadServerStats()
        manageServiceState(_config.value.enablePersistentService)
        startBackgroundPing()
        startStatsMaintenance()
        fetchApiData()
        loadTags()
        startQueueManager()
        cleanupRecoveredImages()

        isInitialized = true
    }

    /* ============================================================================
     * LOCAL METADATA TAGS DATABASE INTERACTION
     * ============================================================================ */

    suspend fun getTagsForLora(hash: String): List<String> {
        return withContext(Dispatchers.IO) {
            val entity = db.loraTagDao().getTagsByHash(hash)
            entity?.trainedWords?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        }
    }

    /* ============================================================================
     * SMART DOWNSAMPLING & STATS MAINTENANCE
     * ============================================================================ */

    private fun startStatsMaintenance() {
        repositoryScope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(60_000L)
                compactServerStats()
                saveServerStats()
            }
        }
    }

    private fun compactServerStats() {
        _serverStats.update { currentList ->
            if (currentList.isEmpty()) return@update currentList

            val now = System.currentTimeMillis()
            val limit15m = now - 15 * 60 * 1000L
            val limit24h = now - 24 * 60 * 60 * 1000L

            val validRecords = currentList.filter { it.timestamp >= limit24h }
            val recent = validRecords.filter { it.timestamp >= limit15m }
            val older = validRecords.filter { it.timestamp < limit15m }

            val compactedOlder = older.groupBy { it.timestamp / 60000L }.map { (minuteBucket, records) ->
                ServerStatRecord(
                    timestamp = minuteBucket * 60000L,
                    pingMs = records.map { it.pingMs }.average().toLong(),
                    ramUsed = records.map { it.ramUsed }.average(),
                    ramTotal = records.map { it.ramTotal }.average(),
                    vramUsed = records.map { it.vramUsed }.average(),
                    vramTotal = records.map { it.vramTotal }.average()
                )
            }.sortedBy { it.timestamp }

            compactedOlder + recent.sortedBy { it.timestamp }
        }
    }

    private fun loadServerStats() {
        val json = prefs.getString("saved_server_stats", null)
        if (!json.isNullOrEmpty()) {
            try {
                val type = object : TypeToken<List<ServerStatRecord>>() {}.type
                val loaded: List<ServerStatRecord> = gson.fromJson(json, type)
                _serverStats.value = loaded
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load saved stats", e)
            }
        }
    }

    private fun saveServerStats() {
        try {
            val json = gson.toJson(_serverStats.value)
            prefs.edit { putString("saved_server_stats", json) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save stats", e)
        }
    }

    /* ============================================================================
     * FOREGROUND SERVICE MANAGEMENT
     * ============================================================================ */

    private fun manageServiceState(enablePersistent: Boolean) {
        val serviceIntent = Intent(application, GenerationService::class.java).apply {
            action = "ACTION_UPDATE_PERSISTENCE"
        }
        try {
            application.startService(serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service", e)
        }
    }

    /* ============================================================================
     * QUEUE FAILSAFE MECHANISM (LOCAL STORAGE)
     * ============================================================================ */

    private fun loadQueueState() {
        val json = prefs.getString("saved_queue", null)
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

    private fun saveQueueState() {
        try {
            val q = _generationQueue.value
            prefs.edit { putString("saved_queue", gson.toJson(q)) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save queue", e)
        }
    }

    /* ============================================================================
     * CACHE AND FILE RECOVERY MANAGEMENT
     * ============================================================================ */

    private fun cleanupRecoveredImages() {
        repositoryScope.launch(Dispatchers.IO) {
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

                options.inSampleSize = calculateInSampleSize(options)
                options.inJustDecodeBounds = false

                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                val file = File(application.cacheDir, "recovered_${System.currentTimeMillis()}.png")

                FileOutputStream(file).use { out ->
                    bitmap?.compress(Bitmap.CompressFormat.PNG, 85, out)
                }
                bitmap?.recycle()

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

    /* ============================================================================
     * SETTINGS & STATE CONFIGURATION
     * ============================================================================ */

    fun setAppForegroundState(isForeground: Boolean) {
        _isAppInForeground.value = isForeground
        if (!isForeground) {
            saveServerStats()
        }
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
            try { gson.fromJson(json, AppConfig::class.java) } catch(_: Exception) { null }
        } else null

        val oldLivePreviewState = try {
            val jsonObj = JSONObject(json ?: "{}")
            jsonObj.optBoolean("livePreviews", false)
        } catch(_: Exception) { false }

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
            notificationPriority = parsed?.notificationPriority ?: "Normal",
            notificationVerbosity = parsed?.notificationVerbosity ?: "Full",
            keepScreenOn = parsed?.keepScreenOn ?: false,
            enablePersistentService = parsed?.enablePersistentService ?: false,
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
            presets = parsed?.presets ?: emptyList(),
        )
    }

    fun saveConfig(newConfig: AppConfig) {
        var cleanUrl = newConfig.apiUrl.trim()
        if (cleanUrl.isNotEmpty() && !cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            cleanUrl = "http://$cleanUrl"
        }

        val oldPersistent = _config.value.enablePersistentService
        val updatedConfig = newConfig.copy(apiUrl = cleanUrl)

        _config.value = updatedConfig
        prefs.edit { putString("config", gson.toJson(updatedConfig)) }

        client = client.newBuilder()
            .connectTimeout(updatedConfig.connectionTimeout.toLong(), TimeUnit.SECONDS)
            .build()

        if (updatedConfig.enablePersistentService != oldPersistent) {
            manageServiceState(updatedConfig.enablePersistentService)
        }
    }

    fun saveCurrentAsDefault() {
        val currentConfig = _config.value
        val newState = currentConfig.copy(defaultState = _appState.value.copy())
        saveConfig(newState)
        Toast.makeText(application, "Set Current as Default", Toast.LENGTH_SHORT).show()
    }

    fun resetToDefaults() {
        _appState.value = _config.value.defaultState.copy()
        prefs.edit { putString("last_state", gson.toJson(_appState.value)) }
        Toast.makeText(application, "Reset to Defaults", Toast.LENGTH_SHORT).show()
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
            prefs.edit { putString("last_state", gson.toJson(_appState.value)) }
            Toast.makeText(application, "Loaded: $name", Toast.LENGTH_SHORT).show()
        }
    }

    fun deletePreset(name: String) {
        val currentPresets = _config.value.presets.toMutableList()
        currentPresets.removeAll { it.name == name }
        saveConfig(_config.value.copy(presets = currentPresets))
    }

    /* ============================================================================
     * PREVIEW AND NETWORK UTILS
     * ============================================================================ */

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

    fun updateGalleryGridColumns(cols: Int) {
        val newConfig = _config.value.copy(galleryGridColumns = cols)
        _config.value = newConfig
        prefs.edit { putString("config", gson.toJson(newConfig)) }
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
            try { gson.fromJson(json, AppState::class.java) } catch(_: Exception) { null }
        } else null

        if (parsed != null) return parsed
        return loadConfig().defaultState.copy()
    }

    fun updateState(update: (AppState) -> AppState) {
        val newState = update(_appState.value)
        _appState.value = newState
        prefs.edit { putString("last_state", gson.toJson(newState)) }
    }

    private fun loadPromptHistory(): List<PromptHistoryItem> {
        val json = prefs.getString("prompt_history", null)
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val type = object : TypeToken<List<PromptHistoryItem>>() {}.type
            gson.fromJson(json, type)
        } catch (_: Exception) {
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
        prefs.edit { putString("prompt_history", gson.toJson(trimmedList)) }
    }

    fun clearPromptHistory() {
        _promptHistory.value = emptyList()
        prefs.edit { remove("prompt_history") }
    }

    fun resumeQueue() {
        _isQueuePaused.value = false
        _oomAlert.value = false
        _statusText.value = "Queue Resumed"
    }

    fun interruptGeneration() {
        repositoryScope.launch(Dispatchers.IO) {
            try {
                val url = _config.value.apiUrl.trimEnd('/')
                val request = Request.Builder().url("$url/sdapi/v1/interrupt").post("{}".toRequestBody("application/json".toMediaType())).build()
                client.newCall(request).awaitResponse().close()
                _statusText.value = "Interrupting..."
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
                val length = ByteBuffer.wrap(bytes, offset, 4).int
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

    /* ============================================================================
     * QUEUE ORCHESTRATION & GENERATION EXECUTION
     * ============================================================================ */

    private fun startQueueManager() {
        repositoryScope.launch(Dispatchers.IO) {
            var lastGenerationTime = 0L
            while (isActive) {
                try {
                    val queue = _generationQueue.value
                    val isBusy = _isServerBusy.value
                    val isGeneratingLocally = _isGenerating.value
                    val isPaused = _isQueuePaused.value

                    if (queue.isNotEmpty() && !isBusy && !isGeneratingLocally && !isPaused) {
                        val timeSinceLast = System.currentTimeMillis() - lastGenerationTime

                        if (lastGenerationTime != 0L && timeSinceLast < 10000) {
                            val secondsLeft = (10000 - timeSinceLast) / 1000
                            _statusText.value = "Queue Cooldown (${secondsLeft}s)..."
                            delay(1000)
                            continue
                        }

                        val nextJob = queue.first()
                        _isGenerating.value = true
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
            prompt = state.positivePrompt,
            negative_prompt = state.negativePrompt,
            steps = state.steps,
            cfg_scale = state.cfgScale,
            width = state.width,
            height = state.height,
            n_iter = state.batchCount,
            batch_size = state.batchSize,
            seed = state.seed,
            sampler_name = state.sampler,
            scheduler = state.scheduler,
            override_settings = OverrideSettings(
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
            positivePrompt = state.positivePrompt,
            payload = payload
        )

        _generationQueue.update { it + item }
        saveQueueState()

        if (_generationQueue.value.size == 1 && !_isGenerating.value) {
            _totalQueueSize.value = 1
            _completedQueueItems.value = 0
        } else {
            _totalQueueSize.update { it + 1 }
        }

        saveToPromptHistory(state.positivePrompt, state.negativePrompt)

        if (_isServerBusy.value && !_isGenerating.value) {
            Toast.makeText(application, "External generation active. Added to queue.", Toast.LENGTH_SHORT).show()
        } else if (_isGenerating.value) {
            Toast.makeText(application, "Added to queue.", Toast.LENGTH_SHORT).show()
        }
    }

    fun updateQueueItem(id: String, positivePrompt: String, negativePrompt: String) {
        _generationQueue.update { currentQueue ->
            currentQueue.map {
                if (it.id == id) {
                    it.copy(
                        positivePrompt = positivePrompt,
                        payload = it.payload.copy(prompt = positivePrompt, negative_prompt = negativePrompt)
                    )
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

    private suspend fun executeGeneration(job: QueuedGeneration) {
        _lastPromptState.value = _appState.value.copy()
        val shouldSaveToDevice = _appState.value.saveToDevice

        _progress.value = 0f
        _currentEta.value = 0.0
        _livePreviewImage.value = null
        _isShowingGridPreview.value = false

        val previewText = job.positivePrompt.take(30).replace("\n", " ")
        _statusText.value = "Generating: \"$previewText...\""

        val serviceIntent = Intent(application, GenerationService::class.java).apply {
            action = "ACTION_START_GENERATION"
        }
        try {
            application.startForegroundService(serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
        }

        try {
            val url = _config.value.apiUrl.trimEnd('/')
            val jsonPayload = gson.toJson(job.payload)
            val body = jsonPayload.toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url("$url/sdapi/v1/txt2img").post(body).build()

            client.newCall(request).awaitResponse().use { response ->
                val responseBody = response.body?.string() ?: "{}"
                if (response.isSuccessful) {
                    val txt2ImgData = gson.fromJson(responseBody, Txt2ImgResponse::class.java)

                    if (txt2ImgData.images.isNotEmpty()) {
                        val currentList = _sessionImages.value.toMutableList()
                        val startIndex = currentList.size

                        val cachePath = application.cacheDir.absolutePath

                        for ((i, b64) in txt2ImgData.images.withIndex()) {
                            val bytes = Base64.decode(b64, Base64.DEFAULT)
                            val file = File("$cachePath/gen_${System.currentTimeMillis()}_$i.png")
                            file.writeBytes(bytes)
                            currentList.add(file.absolutePath)

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
                                        resolver.openOutputStream(uri)?.use { it.write(bytes) }
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

                        if (_config.value.showGridAfterGeneration && txt2ImgData.images.size > 1) {
                            _isShowingGridPreview.value = true
                        }

                        if (_config.value.receiveGenerationNotification) {
                            try {
                                val notifManager = application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                                val openIntent = Intent(application, MainActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                }
                                val pendingIntent = PendingIntent.getActivity(application, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

                                val channelId = when (_config.value.notificationPriority) {
                                    "High" -> "forge_high"
                                    "Low" -> "forge_low"
                                    else -> "forge_default"
                                }

                                val builder = NotificationCompat.Builder(application, channelId)
                                    .setSmallIcon(R.mipmap.ic_launcher_foreground)
                                    .setContentTitle("Batch Completed")
                                    .setContentText("Finished: ${job.positivePrompt.take(35)}...")
                                    .setContentIntent(pendingIntent)
                                    .setAutoCancel(true)

                                val lastImage = currentList.lastOrNull()
                                if (lastImage != null) {
                                    try {
                                        val bitmap = BitmapFactory.decodeFile(lastImage)
                                        if (bitmap != null) {
                                            builder.setLargeIcon(bitmap)
                                            builder.setStyle(
                                                NotificationCompat.BigPictureStyle()
                                                    .bigPicture(bitmap)
                                                    .bigLargeIcon(null as Bitmap?)
                                                    .setBigContentTitle("Batch Completed")
                                                    .setSummaryText(job.positivePrompt.take(100))
                                            )
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Failed to load BigPicture for notification", e)
                                    }
                                }

                                // UNIQUE NOTIFICATION ID TO PREVENT OVERWRITING
                                val uniqueNotifId = System.currentTimeMillis().toInt()
                                notifManager.notify(uniqueNotifId, builder.build())
                            } catch (e: Exception) {
                                Log.e(TAG, "Notification Launch Failed", e)
                            }
                        }
                    }

                } else {
                    if (response.code == 500 || responseBody.contains("OutOfMemoryError", true) || responseBody.contains("CUDA out of memory", true)) {
                        _statusText.value = "SERVER OUT OF MEMORY (OOM)"
                        _isQueuePaused.value = true
                        _oomAlert.value = true
                    } else {
                        _statusText.value = "Error: ${response.code}"
                        if (!_config.value.overnightMode) _isQueuePaused.value = true
                    }
                }
            }
        } catch (e: Exception) {
            _statusText.value = "Failed: ${e.localizedMessage}"
            if (!_config.value.overnightMode) {
                _isQueuePaused.value = true
            }
        } finally {
            _generationQueue.update { q -> q.filter { it.id != job.id } }
            _completedQueueItems.update { it + 1 }

            if (_generationQueue.value.isEmpty()) {
                _totalQueueSize.value = 0
                _completedQueueItems.value = 0

                val finishIntent = Intent(application, GenerationService::class.java).apply {
                    action = "ACTION_QUEUE_FINISHED"
                }
                try {
                    application.startService(finishIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to notify service of queue finish", e)
                }
            }

            saveQueueState()

            _isGenerating.value = false
            _progress.value = 1f
            _currentEta.value = 0.0
        }
    }

    /* ============================================================================
     * BACKGROUND PING POLLING
     * ============================================================================ */

    private fun startBackgroundPing() {
        repositoryScope.launch(Dispatchers.IO) {
            var failCount = 0
            while (isActive) {
                try {
                    val url = _config.value.apiUrl.trimEnd('/')
                    if (url.isNotEmpty()) {
                        val start = System.currentTimeMillis()
                        val skipImage = _config.value.previewMode != "Normal"

                        val request = Request.Builder().url("$url/sdapi/v1/progress?skip_current_image=$skipImage").build()
                        client.newCall(request).awaitResponse().use { response ->
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
                                        _livePreviewImage.value = currentImageStr
                                    } else {
                                        if (!_isGenerating.value) _livePreviewImage.value = null
                                    }

                                    val busy = progressVal > 0.001f || jobCount > 0
                                    _isServerBusy.value = busy
                                    _progress.value = progressVal
                                    _currentEta.value = etaVal

                                    progressData.state?.let { stateObj ->
                                        _currentJobNo.value = stateObj.jobNo
                                        _currentJobCount.value = stateObj.jobCount
                                        _currentSamplingStep.value = stateObj.samplingStep
                                        _currentSamplingSteps.value = stateObj.samplingSteps
                                    }

                                    if (busy && !_isGenerating.value) {
                                        _statusText.value = "External Task: ${(progressVal * 100).toInt()}%"
                                    } else if (!busy && !_isGenerating.value) {
                                        _statusText.value = "Ready"
                                    }
                                }
                            } else if (response.code == 401 || response.code == 403) {
                                _isConnected.value = false
                                _isServerBusy.value = false
                                _currentEta.value = 0.0
                                failCount = 0
                                _statusText.value = "Authentication Required."
                            } else {
                                failCount++
                            }
                        }

                        if (failCount == 0) {
                            try {
                                var ramU = 0.0
                                var ramT = 0.0
                                var vramU = 0.0
                                var vramT = 0.0
                                var memStr = ""

                                val memReq = Request.Builder().url("$url/sdapi/v1/memory").build()
                                client.newCall(memReq).awaitResponse().use { res ->
                                    if (res.isSuccessful) {
                                        val json = JSONObject(res.body?.string() ?: "{}")

                                        val ram = json.optJSONObject("ram")
                                        if (ram != null) {
                                            ramU = ram.optDouble("used", 0.0) / (1024.0 * 1024.0 * 1024.0)
                                            ramT = ram.optDouble("total", 0.0) / (1024.0 * 1024.0 * 1024.0)
                                            if (ramT > 0) {
                                                memStr += "RAM: ${String.format(Locale.US, "%.1f", ramU)}/${String.format(Locale.US, "%.1f", ramT)}GB"
                                            }
                                        }

                                        val cuda = json.optJSONObject("cuda")
                                        val system = cuda?.optJSONObject("system")
                                        if (system != null) {
                                            vramU = system.optDouble("used", 0.0) / (1024.0 * 1024.0 * 1024.0)
                                            vramT = system.optDouble("total", 0.0) / (1024.0 * 1024.0 * 1024.0)
                                            if (vramT > 0) {
                                                val vramStr = "VRAM: ${String.format(Locale.US, "%.1f", vramU)}/${String.format(Locale.US, "%.1f", vramT)}GB"
                                                memStr += if (memStr.isNotEmpty()) " | $vramStr" else vramStr
                                            }
                                        }

                                        _vramUsage.value = memStr.ifEmpty { null }
                                    }
                                }

                                val record = ServerStatRecord(
                                    timestamp = System.currentTimeMillis(),
                                    pingMs = _pingMs.value,
                                    ramUsed = ramU,
                                    ramTotal = ramT,
                                    vramUsed = vramU,
                                    vramTotal = vramT
                                )
                                _serverStats.update { it + record }

                            } catch (_: Exception) {
                                _vramUsage.value = null
                            }
                        }
                    }
                } catch (_: Exception) {
                    _isConnected.value = false
                    _isServerBusy.value = false
                    _currentEta.value = 0.0
                    _vramUsage.value = null
                    failCount++
                    if (failCount >= _config.value.connectionTimeout && !_isGenerating.value) {
                        _statusText.value = "Connection Lost (Timeout)"
                    }
                }

                val isForeground = _isAppInForeground.value
                val isActivelyGenerating = _isGenerating.value || _isServerBusy.value

                val baseInterval = if (isForeground || isActivelyGenerating) 1000L else 10000L
                val finalDelay = if (failCount > 0) {
                    val backoff = when (failCount) {
                        1 -> 5000L
                        2 -> 10000L
                        3 -> 30000L
                        else -> 60000L
                    }
                    maxOf(baseInterval, backoff)
                } else {
                    baseInterval
                }

                delay(finalDelay)
            }
        }
    }

    /* ============================================================================
     * METADATA & PNG CHUNK UTILITIES
     * ============================================================================ */

    fun toggleGalleryMetadata() {
        val newVal = !_showGalleryMetadata.value
        _showGalleryMetadata.value = newVal
        prefs.edit { putBoolean("show_gallery_meta", newVal) }
    }

    fun loadMetadataForImage(item: GalleryItem?) {
        if (item == null) {
            _currentImageMetadata.value = null
            return
        }
        _currentImageMetadata.value = "Loading metadata..."
        repositoryScope.launch(Dispatchers.IO) {
            try {
                val imageUrl = getGalleryImageUrl(item)
                if (imageUrl.isEmpty()) {
                    _currentImageMetadata.value = "Invalid URL."
                    return@launch
                }
                val imgReq = Request.Builder().url(imageUrl).build()
                var imgBytes: ByteArray? = null
                client.newCall(imgReq).awaitResponse().use { res ->
                    if (res.isSuccessful) {
                        imgBytes = res.body?.bytes()
                    }
                }

                val bytes = imgBytes
                if (bytes != null) {
                    val infoStr = extractPngParameters(bytes)
                    _currentImageMetadata.value = infoStr.ifBlank { "No generation data found." }
                } else {
                    _currentImageMetadata.value = "Failed to load image."
                }
            } catch (e: Exception) {
                _currentImageMetadata.value = "Failed: ${e.message}"
            }
        }
    }

    fun loadMetadataForLocalFile(filePath: String) {
        _currentImageMetadata.value = "Loading metadata..."
        repositoryScope.launch(Dispatchers.IO) {
            try {
                val file = File(filePath)
                if (!file.exists()) {
                    _currentImageMetadata.value = "File not found locally."
                    return@launch
                }

                val bytes = file.readBytes()
                val infoStr = extractPngParameters(bytes)

                _currentImageMetadata.value = infoStr.ifBlank { "No generation data found." }
            } catch (e: Exception) {
                _currentImageMetadata.value = "Failed: ${e.message}"
            }
        }
    }

    fun changeCheckpoint(modelTitle: String) {
        _selectedModel.value = modelTitle
        repositoryScope.launch(Dispatchers.IO) {
            try {
                val url = _config.value.apiUrl.trimEnd('/')
                val payload = JSONObject().apply { put("sd_model_checkpoint", modelTitle) }
                val body = payload.toString().toRequestBody("application/json".toMediaType())
                val req = Request.Builder().url("$url/sdapi/v1/options").post(body).build()
                client.newCall(req).awaitResponse().close()
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
        repositoryScope.launch(Dispatchers.IO) {
            val cachePath = application.cacheDir.absolutePath
            val file = File("$cachePath/tags.csv")
            if (!file.exists()) {
                try {
                    val url = "https://raw.githubusercontent.com/DominikDoom/a1111-sd-webui-tagcomplete/main/tags/danbooru.csv"
                    val request = Request.Builder().url(url).build()
                    client.newCall(request).awaitResponse().use { res ->
                        if (res.isSuccessful) file.writeText(res.body?.string() ?: "")
                    }
                } catch (_: Exception) {}
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
        repositoryScope.launch(Dispatchers.IO) {
            _tagSuggestions.value = _allTags.filter { it.startsWith(query, ignoreCase = true) }.take(8)
        }
    }

    /* ============================================================================
     * REMOTE INFINITE IMAGE BROWSING RECOVERY & PARSING
     * ============================================================================ */

    private fun parseGalleryItems(json: String): List<GalleryItem> {
        val list = mutableListOf<GalleryItem>()
        if (json.isEmpty()) return list
        try {
            val fileList = gson.fromJson(json, GalleryFileList::class.java)
            if (fileList?.files != null) list.addAll(fileList.files)
        } catch (_: Exception) {
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

            client.newCall(Request.Builder().url(url).build()).awaitResponse().use { response ->
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
        val currentDateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

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
                client.newCall(imgReq).awaitResponse().use { res ->
                    if (res.isSuccessful) imgBytes = res.body?.bytes()
                }

                val bytes = imgBytes
                if (bytes != null) {
                    saveRecoveredImageToCache(bytes)
                    return extractPngParameters(bytes)
                }
            }
        }
        return null
    }

    fun recoverLastPrompt() {
        if (_isRestoringPrompt.value) return
        _isRestoringPrompt.value = true

        repositoryScope.launch(Dispatchers.IO) {
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
                        Toast.makeText(application, "Used local cache (No images found in gallery)", Toast.LENGTH_SHORT).show()
                        _isRestoringPrompt.value = false
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    updateState { _lastPromptState.value.copy() }
                    Toast.makeText(application, "Used local cache (Network error)", Toast.LENGTH_SHORT).show()
                    _isRestoringPrompt.value = false
                }
            }
        }
    }

    fun recoverLastSeed() {
        repositoryScope.launch(Dispatchers.IO) {
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
                            Toast.makeText(application, "Seed recovered: $foundSeed", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(application, "No seed found in last image", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(application, "Failed to find last image", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(application, "Network error recovering seed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun recoverPromptFromImage(item: GalleryItem) {
        if (_isRestoringPrompt.value) return
        _isRestoringPrompt.value = true

        repositoryScope.launch(Dispatchers.IO) {
            try {
                val imageUrl = getGalleryImageUrl(item)
                if (imageUrl.isEmpty()) {
                    withContext(Dispatchers.Main) { Toast.makeText(application, "Invalid Image URL", Toast.LENGTH_SHORT).show() }
                    _isRestoringPrompt.value = false
                    return@launch
                }

                val imgReq = Request.Builder().url(imageUrl).build()
                var imgBytes: ByteArray? = null

                client.newCall(imgReq).awaitResponse().use { res ->
                    if (res.isSuccessful) {
                        imgBytes = res.body?.bytes()
                    }
                }

                val bytes = imgBytes
                if (bytes != null) {
                    val infoStr = extractPngParameters(bytes)
                    saveRecoveredImageToCache(bytes)
                    withContext(Dispatchers.Main) {
                        parseAndApplyPngInfo(infoStr)
                        _isRestoringPrompt.value = false
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(application, "Failed to extract data", Toast.LENGTH_SHORT).show()
                    _isRestoringPrompt.value = false
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(application, "Network error", Toast.LENGTH_SHORT).show()
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
        Toast.makeText(application, "Loaded generation data", Toast.LENGTH_SHORT).show()
    }

    fun fetchApiData() {
        repositoryScope.launch(Dispatchers.IO) {
            val url = _config.value.apiUrl.trimEnd('/')
            if (url.isEmpty()) return@launch
            try {
                val listType = object : TypeToken<List<NameResponse>>() {}.type

                listOf("samplers", "schedulers", "upscalers").forEach { endpoint ->
                    val request = Request.Builder().url("$url/sdapi/v1/$endpoint").build()
                    client.newCall(request).awaitResponse().use { res ->
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
                client.newCall(modelReq).awaitResponse().use { res ->
                    if (res.isSuccessful) {
                        val type = object : TypeToken<List<SdModelItem>>() {}.type
                        val sdModels = gson.fromJson<List<SdModelItem>>(res.body?.string() ?: "[]", type)
                        models.value = sdModels.map {
                            ApiResource(title = it.title, path = it.filename ?: "", name = it.modelName, hash = null)
                        }
                    }
                }

                val loraReq = Request.Builder().url("$url/sdapi/v1/loras").build()
                client.newCall(loraReq).awaitResponse().use { res ->
                    if (res.isSuccessful) {
                        val bodyStr = res.body?.string() ?: "[]"
                        val jsonArray = JSONArray(bodyStr)
                        val parsedLoras = mutableListOf<ApiResource>()

                        val dao = db.loraTagDao()
                        val existingHashes = dao.getAllTags().map { it.loraHash }.toSet()

                        for (i in 0 until jsonArray.length()) {
                            val itemObj = jsonArray.getJSONObject(i)
                            val name = itemObj.optString("name", "Unknown")
                            val path = itemObj.optString("path", "")
                            val meta = itemObj.optJSONObject("metadata")

                            val hash = meta?.optString("sshs_model_hash")?.takeIf { it.isNotEmpty() } ?: name

                            parsedLoras.add(ApiResource(title = name, path = path, name = name, hash = hash))

                            if (!existingHashes.contains(hash)) {
                                val tagFrequencies = mutableMapOf<String, Int>()
                                val freqObj = meta?.optJSONObject("ss_tag_frequency")
                                    ?: try { JSONObject(meta?.optString("ss_tag_frequency") ?: "{}") } catch (_: Exception) { null }

                                if (freqObj != null) {
                                    val datasetKeys = freqObj.keys()
                                    while (datasetKeys.hasNext()) {
                                        val dataset = freqObj.optJSONObject(datasetKeys.next())
                                        if (dataset != null) {
                                            val tagKeys = dataset.keys()
                                            while(tagKeys.hasNext()) {
                                                val tag = tagKeys.next()
                                                val freq = dataset.optInt(tag, 0)
                                                tagFrequencies[tag] = tagFrequencies.getOrDefault(tag, 0) + freq
                                            }
                                        }
                                    }
                                }

                                val sortedTags = tagFrequencies.entries
                                    .sortedByDescending { it.value }
                                    .map { it.key }

                                if (sortedTags.isNotEmpty()) {
                                    dao.insertTags(LoraTagEntity(hash, name, sortedTags.joinToString(", "), System.currentTimeMillis()))
                                }
                            }
                        }
                        availableLoras.value = parsedLoras
                    }
                }

                val reqOpts = Request.Builder().url("$url/sdapi/v1/options").build()
                client.newCall(reqOpts).awaitResponse().use { res ->
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

        repositoryScope.launch(Dispatchers.IO) {
            try {
                val baseUrlStr = _config.value.apiUrl.trimEnd('/')
                val builder = baseUrlStr.toHttpUrlOrNull()?.newBuilder()
                    ?.addPathSegments("infinite_image_browsing/files")

                if (path.isNotEmpty() && path != "Root") {
                    builder?.addQueryParameter("folder_path", path)
                }

                val url = builder?.build() ?: throw Exception("Invalid API URL format")
                val request = Request.Builder().url(url).build()

                client.newCall(request).awaitResponse().use { response ->
                    val responseBody = response.body?.string() ?: ""
                    when {
                        response.isSuccessful -> {
                            _galleryFiles.value = parseGalleryItems(responseBody).sortedWith(compareBy({ !it.isDir }, { it.name }))
                            _currentGalleryPath.value = path
                        }
                        response.code == 400 && path.isNotEmpty() && path != "Root" -> {
                            fetchGalleryFolder("Root")
                            return@launch
                        }
                        response.code == 401 || response.code == 403 -> {
                            _galleryError.value = "Authentication Required."
                        }
                        else -> {
                            _galleryError.value = "Server returned Error ${response.code}"
                        }
                    }
                }
            } catch (e: Exception) {
                _galleryError.value = e.message ?: "Failed to reach server."
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
        repositoryScope.launch(Dispatchers.IO) {
            try {
                val file = File(localFilePath)
                if (!file.exists()) throw Exception("Local file missing")

                val bytes = file.readBytes()
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
                    resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    withContext(Dispatchers.Main) { Toast.makeText(application, "Saved to Downloads", Toast.LENGTH_SHORT).show() }
                } else throw Exception("Failed to create file in MediaStore")

            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(application, "Download Failed: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    fun downloadImage(item: GalleryItem) {
        repositoryScope.launch(Dispatchers.IO) {
            try {
                val url = getGalleryImageUrl(item)
                if (url.isEmpty()) throw Exception("Invalid Gallery URL")

                val request = Request.Builder().url(url).build()

                client.newCall(request).awaitResponse().use { response ->
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes() ?: throw Exception("Empty response body")
                        val contentValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
                            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ForgeGen")
                        }

                        val resolver = application.contentResolver
                        val insertUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                        val uri = resolver.insert(insertUri, contentValues)

                        if (uri != null) {
                            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                            withContext(Dispatchers.Main) { Toast.makeText(application, "Saved to Downloads", Toast.LENGTH_SHORT).show() }
                        } else throw Exception("Failed to create file in MediaStore")
                    } else throw Exception("Server returned ${response.code}")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(application, "Download Failed: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    fun shareSessionImage(localFilePath: String, onIntentReady: (Intent) -> Unit) {
        repositoryScope.launch(Dispatchers.IO) {
            try {
                val file = File(localFilePath)
                if (!file.exists()) throw Exception("Local file missing")

                val bytes = file.readBytes()
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
                    resolver.openOutputStream(uri)?.use { it.write(bytes) }

                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    withContext(Dispatchers.Main) {
                        onIntentReady(Intent.createChooser(shareIntent, "Share Image"))
                    }
                } else throw Exception("Failed to prepare file for sharing")
            } catch(e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(application, "Share Failed: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    fun shareImage(item: GalleryItem, onIntentReady: (Intent) -> Unit) {
        repositoryScope.launch(Dispatchers.IO) {
            try {
                val url = getGalleryImageUrl(item)
                if (url.isEmpty()) throw Exception("Invalid Gallery URL")

                val request = Request.Builder().url(url).build()

                client.newCall(request).awaitResponse().use { response ->
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes() ?: throw Exception("Empty response body")
                        val contentValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, "Shared_${item.name}")
                            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ForgeGen_Shared")
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
                                onIntentReady(Intent.createChooser(shareIntent, "Share Image"))
                            }
                        } else throw Exception("Failed to prepare file for sharing")
                    } else throw Exception("Server returned ${response.code}")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(application, "Share Failed: ${e.message}", Toast.LENGTH_SHORT).show() }
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
}