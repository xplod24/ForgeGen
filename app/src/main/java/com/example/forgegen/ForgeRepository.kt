package com.example.forgegen

import com.example.forgegen.ui.components.*
import android.annotation.SuppressLint
import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.icu.text.SimpleDateFormat
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/* ============================================================================
 * ASYNC PREFERENCES (VIA ROOM DAO)
 * ============================================================================ */
/* ============================================================================
 * IDIOMATIC COROUTINES EXTENSIONS
 * Extension left exclusively for downloading raw image bytes
 * bypassing Retrofit.
 * ============================================================================ */

suspend fun Call.awaitResponse(): Response =
    suspendCancellableCoroutine { continuation ->
        enqueue(
            object : Callback {
                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    continuation.resume(response)
                }

                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    if (continuation.isCancelled) return
                    continuation.resumeWithException(e)
                }
            },
        )

        continuation.invokeOnCancellation {
            try {
                cancel()
            } catch (_: Throwable) {
                // Ignore
            }
        }
    }

data class ActiveLora(
    val name: String,
    val strength: Float,
)

/* ============================================================================
 * DATA REPOSITORY (SINGLETON)
 * Handles API orchestration, state management, background battery optimization,
 * queue failsafe mechanisms, and background service delegation.
 * ============================================================================ */

@SuppressLint("StaticFieldLeak")
object ForgeRepository {
    private const val TAG = "ForgeAPI"

    private lateinit var application: Application
    lateinit var db: ForgeDatabase

    // RETROFIT APIS
    var forgeApi: ForgeApi? = null

    private val QUEUE_KEY = "saved_queue"
    private val STATS_KEY = "saved_server_stats"

    val repositoryScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    fun showSnackbar(message: String) = ForgeSettingsManager.showSnackbar(message)

    fun showToast(message: String) = ForgeSettingsManager.showToast(message)

    // --- DELEGATED SETTINGS / STATE FROM ForgeSettingsManager ---
    val config: StateFlow<AppConfig> get() = ForgeSettingsManager.config
    val appState: StateFlow<AppState> get() = ForgeSettingsManager.appState
    val promptHistory: StateFlow<List<PromptHistoryItem>> get() = ForgeSettingsManager.promptHistory
    val showGalleryMetadata: StateFlow<Boolean> get() = ForgeSettingsManager.showGalleryMetadata
    val pinnedImages: StateFlow<Set<String>> get() = ForgeSettingsManager.pinnedImages

    val isInitialized: StateFlow<Boolean> get() = ForgeSettingsManager.isInitialized
    val initStatus: StateFlow<String> get() = ForgeSettingsManager.initStatus

    val client: OkHttpClient get() = ForgeSettingsManager.client
    val snackbarMessage: SharedFlow<String> get() = ForgeSettingsManager.snackbarMessage

    val activeLoras: StateFlow<List<ActiveLora>> =
        ForgeSettingsManager.appState
            .map { state ->
                val regex = Regex("<lora:([^:>]+):(-?[0-9.]+)>")
                regex
                    .findAll(state.positivePrompt)
                    .map { match ->
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

    // Generation state is owned by ForgeQueueManager; these getters only keep the old API working.
    val progress: StateFlow<Float> get() = ForgeQueueManager.progress
    val currentEta: StateFlow<Double> get() = ForgeQueueManager.currentEta
    val isGenerating: StateFlow<Boolean> get() = ForgeQueueManager.isGenerating
    val statusText: StateFlow<String> get() = ForgeQueueManager.statusText

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

    val livePreviewImage: StateFlow<String?> get() = ForgeQueueManager.livePreviewImage

    private val _isShowingGridPreview = MutableStateFlow(false)
    val isShowingGridPreview: StateFlow<Boolean> = _isShowingGridPreview.asStateFlow()

    private val _currentBatchStartIndex = MutableStateFlow(0)
    val currentBatchStartIndex: StateFlow<Int> = _currentBatchStartIndex.asStateFlow()

    private val _currentBatchEndIndex = MutableStateFlow(-1)
    val currentBatchEndIndex: StateFlow<Int> = _currentBatchEndIndex.asStateFlow()

    private val _tagSuggestions = MutableStateFlow<List<String>>(emptyList())
    val tagSuggestions: StateFlow<List<String>> = _tagSuggestions.asStateFlow()

    // We use a new graphical enum for animations from MainComponents.kt
    private val _isRestoringPrompt = MutableStateFlow(IndicatorState.IDLE)
    val isRestoringPrompt: StateFlow<IndicatorState> = _isRestoringPrompt.asStateFlow()

    val selectedModel: StateFlow<String> get() = ForgeModelManager.selectedModel
    val samplers: StateFlow<List<String>> get() = ForgeModelManager.samplers
    val schedulers: StateFlow<List<String>> get() = ForgeModelManager.schedulers
    val models: StateFlow<List<ApiResource>> get() = ForgeModelManager.models
    val upscalers: StateFlow<List<String>> get() = ForgeModelManager.upscalers
    val availableLoras: StateFlow<List<ApiResource>> get() = ForgeModelManager.availableLoras

    private val _galleryFiles = MutableStateFlow<List<GalleryItem>>(emptyList())
    val galleryFiles: StateFlow<List<GalleryItem>> = _galleryFiles.asStateFlow()

    private val _currentGalleryPath = MutableStateFlow("")
    val currentGalleryPath: StateFlow<String> = _currentGalleryPath.asStateFlow()

    private val _isGalleryLoading = MutableStateFlow(false)
    val isGalleryLoading: StateFlow<Boolean> = _isGalleryLoading.asStateFlow()

    private val _galleryError = MutableStateFlow<String?>(null)
    val galleryError: StateFlow<String?> = _galleryError.asStateFlow()


    private val _currentImageMetadata = MutableStateFlow<String?>(null)
    val currentImageMetadata: StateFlow<String?> = _currentImageMetadata.asStateFlow()

    private val _galleryMode = MutableStateFlow(GalleryMode.NORMAL)
    val galleryMode: StateFlow<GalleryMode> = _galleryMode.asStateFlow()

    private val _isCurrentFavorite = MutableStateFlow(false)
    val isCurrentFavorite: StateFlow<Boolean> = _isCurrentFavorite.asStateFlow()

    private val _favoritePaths = MutableStateFlow<Set<String>>(emptySet())
    val favoritePaths: StateFlow<Set<String>> = _favoritePaths.asStateFlow()

    private val _updateManifest = MutableStateFlow<UpdateManifest?>(null)
    val updateManifest: StateFlow<UpdateManifest?> = _updateManifest.asStateFlow()

    private val _isUpdateDownloading = MutableStateFlow(false)
    val isUpdateDownloading: StateFlow<Boolean> = _isUpdateDownloading.asStateFlow()

    private val _updateDownloadProgress = MutableStateFlow(0f)
    val updateDownloadProgress: StateFlow<Float> = _updateDownloadProgress.asStateFlow()

    // --- CIVITAI SYNC STATES ---
    val isCivitaiSyncing: StateFlow<IndicatorState> get() = ForgeModelManager.isCivitaiSyncing
    val civitaiSyncCurrentModel: StateFlow<String> get() = ForgeModelManager.civitaiSyncCurrentModel
    val civitaiSyncProgress: StateFlow<Pair<Int, Int>> get() = ForgeModelManager.civitaiSyncProgress
    val civitaiSyncLastResult: StateFlow<String?> get() = ForgeModelManager.civitaiSyncLastResult

    private fun rebuildForgeApi(url: String) {
        var cleanUrl = url.trimEnd('/')
        if (cleanUrl.isNotEmpty() && !cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            cleanUrl = "http://$cleanUrl"
        }
        if (cleanUrl.isEmpty()) return

        try {
            val retrofitForge =
                Retrofit
                    .Builder()
                    .baseUrl("$cleanUrl/")
                    .client(client)
                    .addConverterFactory(GsonConverterFactory.create(ForgeSettingsManager.gson))
                    .build()
            forgeApi = retrofitForge.create(ForgeApi::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ForgeApi with URL: $cleanUrl", e)
        }
    }

    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `app_settings` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))")
        }
    }

    suspend fun initializeDatabaseAndSettings(app: Application) {
        if (isInitialized.value) return
        application = app

        ForgeSettingsManager.updateInitStatus("Initializing Database...")
        db =
            Room
                .databaseBuilder(app, ForgeDatabase::class.java, "forge_db")
                .addMigrations(MIGRATION_9_10)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()

        ForgeSettingsManager.updateInitStatus("Loading Settings...")
        ForgeSettingsManager.init(app, db)
    }

    suspend fun initializeApiClientAndData() {
        ForgeSettingsManager.updateInitStatus("Connecting to Server...")
        rebuildForgeApi(config.value.apiUrl)

        // Models, samplers and LoRAs are fetched by ForgeNetworkManager when the connection comes up
        // or the URL changes; here only the Retrofit instance (URL / timeout) has to be rebuilt.
        ForgeSettingsManager.onApiUrlChanged = { newUrl ->
            rebuildForgeApi(newUrl)
        }

        ForgeSettingsManager.updateInitStatus("Loading App Data...")
        loadFavoritePaths()
        loadServerStats()
        manageServiceState(config.value.enablePersistentService)

        startBackgroundPing()
        startStatsMaintenance()
    }

    private fun loadFavoritePaths() {
        repositoryScope.launch(Dispatchers.IO) {
            val favs = db.favoriteImageDao().getAllFavorites()
            _favoritePaths.value = favs.map { it.fullpath }.toSet()
        }
    }

    fun checkIfFavorite(path: String) {
        if (path.isEmpty()) {
            _isCurrentFavorite.value = false
            return
        }
        repositoryScope.launch(Dispatchers.IO) {
            _isCurrentFavorite.value = db.favoriteImageDao().isFavorite(path)
        }
    }

    fun toggleFavorite(item: GalleryItem) {
        repositoryScope.launch(Dispatchers.IO) {
            val dao = db.favoriteImageDao()
            val isFav = dao.isFavorite(item.fullpath)

            if (isFav) {
                dao.deleteFavorite(item.fullpath)
                _isCurrentFavorite.value = false
                _favoritePaths.update { it - item.fullpath }

                if (_currentGalleryPath.value == "virtual://favorites") {
                    val currentList = _galleryFiles.value.toMutableList()
                    currentList.removeAll { it.fullpath == item.fullpath }
                    _galleryFiles.value = currentList
                }
            } else {
                dao.insertFavorite(
                    FavoriteImageEntity(
                        fullpath = item.fullpath,
                        name = item.name,
                        date = item.date,
                        savedAt = System.currentTimeMillis(),
                    ),
                )
                _isCurrentFavorite.value = true
                _favoritePaths.update { it + item.fullpath }
            }
        }
    }

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

            val compactedOlder =
                older
                    .groupBy { it.timestamp / 60000L }
                    .map { (minuteBucket, records) ->
                        ServerStatRecord(
                            timestamp = minuteBucket * 60000L,
                            pingMs = records.map { it.pingMs }.average().toLong(),
                            ramUsed = records.map { it.ramUsed }.average(),
                            ramTotal = records.map { it.ramTotal }.average(),
                            vramUsed = records.map { it.vramUsed }.average(),
                            vramTotal = records.map { it.vramTotal }.average(),
                        )
                    }.sortedBy { it.timestamp }

            compactedOlder + recent.sortedBy { it.timestamp }
        }
    }

    private fun loadServerStats() {
        repositoryScope.launch(Dispatchers.IO) {
            val json = db.appSettingDao().getSetting("saved_stats")?.value
            if (!json.isNullOrEmpty()) {
                try {
                    val type = object : TypeToken<List<ServerStatRecord>>() {}.type
                    val loaded: List<ServerStatRecord> = ForgeSettingsManager.gson.fromJson(json, type)
                    _serverStats.value = loaded
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load saved stats", e)
                }
            }
        }
    }

    private fun saveServerStats() {
        try {
            repositoryScope.launch(Dispatchers.IO) {
                db.appSettingDao().putSetting(AppSettingEntity("saved_stats", ForgeSettingsManager.gson.toJson(_serverStats.value)))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save stats", e)
        }
    }

    private fun manageServiceState(enablePersistent: Boolean) {
        val serviceIntent =
            Intent(application, GenerationService::class.java).apply {
                action = "ACTION_UPDATE_PERSISTENCE"
            }
        try {
            application.startService(serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service", e)
        }
    }

    private fun loadQueueState() {
        repositoryScope.launch(Dispatchers.IO) {
            val json = db.appSettingDao().getSetting("saved_queue")?.value
            if (!json.isNullOrEmpty()) {
                try {
                    val type = object : TypeToken<List<QueuedGeneration>>() {}.type
                    val q: List<QueuedGeneration> = ForgeSettingsManager.gson.fromJson(json, type)
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

    fun setAppForegroundState(isForeground: Boolean) {
        _isAppInForeground.value = isForeground
        if (!isForeground) {
            saveServerStats()
        }
    }

    fun setGalleryMode(mode: GalleryMode) {
        _galleryMode.value = mode
    }

    private fun createClient(timeoutSeconds: Int): OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
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
            }.build()

    private fun saveCurrentAsDefault() = ForgeSettingsManager.saveCurrentAsDefault()

    fun savePreset(name: String) {
        val currentPresets = config.value.presets.toMutableList()
        currentPresets.removeAll { it.name == name }
        currentPresets.add(GenerationPreset(name, appState.value.copy()))
        ForgeSettingsManager.saveConfig(config.value.copy(presets = currentPresets))
    }

    fun loadPreset(name: String) = ForgeSettingsManager.loadPreset(name)

    fun deletePreset(name: String) = ForgeSettingsManager.deletePreset(name)

    fun fetchAutoConfig() {
        repositoryScope.launch(Dispatchers.IO) {
            try {
                val response = forgeApi?.getGlobalSettings()
                if (response?.isSuccessful == true) {
                    val body = response.body()
                    val sdCwd = body?.sdCwd ?: ""
                    val outdirTxt2Img = body?.globalSetting?.outdirTxt2ImgSamples ?: ""

                    if (sdCwd.isNotEmpty()) {
                        val separator = if (sdCwd.contains("\\")) "\\" else "/"
                        val cleanOutdir = outdirTxt2Img.trimStart('/', '\\')
                        val galleryPath = if (cleanOutdir.isNotEmpty()) "$sdCwd$separator$cleanOutdir" else sdCwd

                        val newConfig =
                            config.value.copy(
                                serverBasePath = sdCwd,
                                galleryPath = galleryPath,
                            )
                        ForgeSettingsManager.saveConfig(newConfig)
                        showSnackbar("Auto-config applied successfully")
                    } else {
                        showSnackbar("Failed to read path from server")
                    }
                } else {
                    showSnackbar("Server returned HTTP ${response?.code()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to auto configure", e)
                showSnackbar("Network error during auto-config")
            }
        }
    }

    fun getPreviewUrl(
        originalPath: String,
        isLora: Boolean = false,
    ): String {
        if (originalPath.isEmpty()) return ""
        if (originalPath.startsWith("http://") || originalPath.startsWith("https://")) {
            return originalPath
        }

        val urlStr = config.value.apiUrl.trimEnd('/')

        val sdCwd = config.value.serverBasePath
        val fullPath =
            if (sdCwd.isNotEmpty() && !originalPath.contains("\\") && !originalPath.contains("/")) {
                val separator = if (sdCwd.contains("\\")) "\\" else "/"
                val subDir = if (isLora) "models${separator}Lora" else "models${separator}Stable-diffusion"
                "$sdCwd$separator$subDir$separator$originalPath"
            } else {
                originalPath
            }

        val basePath = fullPath.substringBeforeLast(".safetensors").substringBeforeLast(".ckpt").substringBeforeLast(".pt")
        return "$urlStr/file=$basePath.preview.png"
    }

    fun addServerProfile(
        name: String,
        url: String,
    ) = ForgeSettingsManager.addServerProfile(name, url)

    private fun extractPngParameters(bytes: ByteArray): String = PngMetadata.readParameters(bytes)

    suspend fun extractMetadataFromUri(uri: Uri): String? =
        withContext(Dispatchers.IO) {
            try {
                val bytes = application.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null) {
                    val infoStr = extractPngParameters(bytes)
                    infoStr.takeIf { it.isNotBlank() }
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read URI for metadata", e)
                null
            }
        }


    private var pingJob: kotlinx.coroutines.Job? = null

    fun resetPingJob() {
        startBackgroundPing()
    }

    private fun startBackgroundPing() {
        pingJob?.cancel()
        pingJob = repositoryScope.launch(Dispatchers.IO) {
            var failCount = 0
            while (isActive) {
                try {
                    if (forgeApi != null) {
                        val start = System.currentTimeMillis()
                        val response = forgeApi?.getProgress(false)

                        if (response?.isSuccessful == true) {
                            _pingMs.value = System.currentTimeMillis() - start
                            _isConnected.value = true
                            failCount = 0

                            val progressData = response.body() ?: ProgressResponseDto()
                            val progressVal = progressData.progress.toFloat()
                            val etaVal = progressData.etaRelative
                            val jobCount = progressData.state?.jobCount ?: 0

                            val currentImageStr = progressData.currentImage ?: ""
                            val busy = progressVal > 0.001f || jobCount > 0
                            _isServerBusy.value = busy
                            ForgeQueueManager.updateExternalProgress(progressVal, etaVal, currentImageStr.ifEmpty { null })
                            if (currentImageStr.isEmpty() && !isGenerating.value) ForgeQueueManager.setLivePreviewImage(null)

                            progressData.state?.let { stateObj ->
                                _currentJobNo.value = stateObj.jobNo
                                _currentJobCount.value = stateObj.jobCount
                                _currentSamplingStep.value = stateObj.samplingStep
                                _currentSamplingSteps.value = stateObj.samplingSteps
                            }

                            if (busy) {
                                val jCount = progressData.state?.jobCount ?: 0
                                val jNo = (progressData.state?.jobNo ?: 0) + 1
                                val batchInfo = if (jCount > 1) "(Batch $jNo of $jCount) " else ""
                                val prefix = if (isGenerating.value) "Generating" else "External Task"
                                ForgeQueueManager.updateStatusText("$prefix $batchInfo... ${(progressVal * 100).toInt()}%")
                            } else if (!busy && !isGenerating.value) {
                                ForgeQueueManager.updateStatusText("Ready")
                            }
                        } else if (response?.code() == 401 || response?.code() == 403) {
                            _isConnected.value = false
                            _isServerBusy.value = false
                            failCount = 0
                            ForgeQueueManager.updateStatusText("Authentication Required.")
                        } else {
                            failCount++
                        }

                        if (failCount == 0) {
                            try {
                                var ramU = 0.0
                                var ramT = 0.0
                                var vramU = 0.0
                                var vramT = 0.0
                                var memStr = ""

                                val memRes = forgeApi?.getMemoryStats()
                                if (memRes?.isSuccessful == true) {
                                    val body = memRes.body()

                                    ramU = (body?.ram?.used ?: 0.0) / (1024.0 * 1024.0 * 1024.0)
                                    ramT = (body?.ram?.total ?: 0.0) / (1024.0 * 1024.0 * 1024.0)
                                    if (ramT > 0) {
                                        memStr +=
                                            "RAM: ${String.format(Locale.US, "%.1f", ramU)}/${String.format(Locale.US, "%.1f", ramT)}GB"
                                    }

                                    vramU = (body?.cuda?.system?.used ?: 0.0) / (1024.0 * 1024.0 * 1024.0)
                                    vramT = (body?.cuda?.system?.total ?: 0.0) / (1024.0 * 1024.0 * 1024.0)
                                    if (vramT > 0) {
                                        val vramStr = "VRAM: ${String.format(
                                            Locale.US,
                                            "%.1f",
                                            vramU,
                                        )}/${String.format(Locale.US, "%.1f", vramT)}GB"
                                        memStr += if (memStr.isNotEmpty()) " | $vramStr" else vramStr
                                    }

                                    _vramUsage.value = memStr.ifEmpty { null }
                                }

                                val record =
                                    ServerStatRecord(
                                        timestamp = System.currentTimeMillis(),
                                        pingMs = _pingMs.value,
                                        ramUsed = ramU,
                                        ramTotal = ramT,
                                        vramUsed = vramU,
                                        vramTotal = vramT,
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
                    _vramUsage.value = null
                    failCount++
                    if (failCount >= config.value.timeout && !ForgeQueueManager.isGenerating.value) {
                        ForgeQueueManager.updateStatusText("Connection Lost (Timeout)")
                    }
                }

                val isForeground = _isAppInForeground.value
                val isActivelyGenerating = isGenerating.value || _isServerBusy.value

                val baseInterval = if (isForeground || isActivelyGenerating) 1000L else 10000L
                val finalDelay =
                    if (failCount > 0) {
                        val backoff =
                            when (failCount) {
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

    fun toggleGalleryMetadata() {
        val newVal = !showGalleryMetadata.value

        repositoryScope.launch(Dispatchers.IO) {
            ForgeSettingsManager.toggleGalleryMetadata()
        }
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

                // Kept raw OkHttp byte downloading for seamless PNG chunk extraction
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
                    _currentImageMetadata.value = if (infoStr.isNotBlank()) infoStr else "No generation data found."
                } else {
                    _currentImageMetadata.value = "Failed to load image."
                }
            } catch (e: Exception) {
                _currentImageMetadata.value = "Failed: ${e.message}"
            }
        }
    }

    fun changeCheckpoint(modelTitle: String) {
        ForgeModelManager.updateState(selectedModel = modelTitle)
        repositoryScope.launch(Dispatchers.IO) {
            try {
                forgeApi?.setOptions(OptionsPayloadDto(modelTitle))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to switch model", e)
            }
        }
    }

    fun appendLora(name: String) {
        val current = appState.value.positivePrompt
        if (!current.contains("<lora:$name:")) {
            val base = current.trimEnd()
            val separator =
                when {
                    base.isEmpty() -> ""
                    base.endsWith(",") -> " "
                    else -> ", "
                }
            val newPrompt = base + separator + "<lora:$name:1.0>"
            ForgeSettingsManager.updateState { it.copy(positivePrompt = newPrompt) }
        }
    }

    fun updateLoraStrength(
        name: String,
        strength: Float,
    ) {
        val current = appState.value.positivePrompt
        val regex = Regex("<lora:${Regex.escape(name)}:-?[0-9.]+>")
        val formattedStrength = String.format(Locale.US, "%.2f", strength)
        // Lambda replacement: a '$' or '\' in the LoRA name must not be treated as a group reference.
        val newPrompt = current.replace(regex) { "<lora:$name:$formattedStrength>" }
        ForgeSettingsManager.updateState { it.copy(positivePrompt = newPrompt) }
    }

    fun removeLora(name: String) {
        val current = appState.value.positivePrompt
        val escapedName = Regex.escape(name)
        val newPrompt =
            current
                .replace(Regex("<lora:$escapedName:-?[0-9.]+>"), "")
                .replace(Regex(",\\s*,"), ",") // separator left behind by the removed tag
                .replace(Regex(" {2,}"), " ")
                .trim()
                .trim(',')
                .trim()
        ForgeSettingsManager.updateState { it.copy(positivePrompt = newPrompt) }
    }

    private fun parseGalleryItems(json: String): List<GalleryItem> {
        val list = mutableListOf<GalleryItem>()
        if (json.isEmpty()) return list
        try {
            val fileList = ForgeSettingsManager.gson.fromJson(json, GalleryFileListDto::class.java)
            if (fileList?.files != null) {
                for (f in fileList.files) {
                    list.add(f.toDomain())
                }
            }
        } catch (_: Exception) {
            try {
                val type = object : TypeToken<List<GalleryItemDto>>() {}.type
                val arrayItems = ForgeSettingsManager.gson.fromJson<List<GalleryItemDto>>(json, type)
                if (arrayItems != null) {
                    for (item in arrayItems) {
                        list.add(item.toDomain())
                    }
                }
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to parse gallery items. JSON: $json", e2)
            }
        }
        return list
    }

    private suspend fun fetchLastGeneratedImageInfo(): String? {
        val rootPath = config.value.galleryPath

        suspend fun fetchFiles(folder: String): List<GalleryItem> {
            try {
                val response = forgeApi?.getGalleryFiles(folderPath = if (folder.isNotEmpty() && folder != "Root") folder else "")
                if (response?.isSuccessful == true) {
                    val responseBody = response.body()?.string() ?: ""
                    return parseGalleryItems(responseBody)
                } else if (response?.code() == 400 && folder.isNotEmpty() && folder != "Root") {
                    return fetchFiles("Root")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Fetch Last Generated files error", e)
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
            targetFile =
                candidateImages.maxByOrNull { item ->
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
                    ForgeQueueManager.saveRecoveredImageToCache(bytes)
                    return extractPngParameters(bytes)
                }
            }
        }
        return null
    }

    fun recoverLastPrompt() {
        if (_isRestoringPrompt.value != IndicatorState.IDLE) return
        _isRestoringPrompt.value = IndicatorState.LOADING

        repositoryScope.launch(Dispatchers.IO) {
            try {
                val infoStr = fetchLastGeneratedImageInfo()
                if (infoStr != null) {
                    withContext(Dispatchers.Main) {
                        parseAndApplyPngInfo(infoStr)
                    }
                    _isRestoringPrompt.value = IndicatorState.SUCCESS
                } else {
                    ForgeSettingsManager.updateState { _lastPromptState.value.copy() }
                    showSnackbar("Used local cache (No images found in gallery)")
                    _isRestoringPrompt.value = IndicatorState.SUCCESS
                }
            } catch (_: Exception) {
                ForgeSettingsManager.updateState { _lastPromptState.value.copy() }
                showSnackbar("Used local cache (Network error)")
                _isRestoringPrompt.value = IndicatorState.ERROR
            }
            delay(1500)
            _isRestoringPrompt.value = IndicatorState.IDLE
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
                    if (foundSeed != null) {
                        ForgeSettingsManager.updateState { it.copy(seed = foundSeed) }
                        showSnackbar("Seed recovered: $foundSeed")
                    } else {
                        showSnackbar("No seed found in last image")
                    }
                } else {
                    showSnackbar("Failed to find last image")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Network error recovering seed", e)
                showSnackbar("Network error recovering seed")
            }
        }
    }

    fun recoverPromptFromImage(item: GalleryItem) {
        if (_isRestoringPrompt.value != IndicatorState.IDLE) return
        _isRestoringPrompt.value = IndicatorState.LOADING

        repositoryScope.launch(Dispatchers.IO) {
            try {
                val imageUrl = getGalleryImageUrl(item)
                if (imageUrl.isEmpty()) {
                    showSnackbar("Invalid Image URL")
                    _isRestoringPrompt.value = IndicatorState.ERROR
                    delay(1500)
                    _isRestoringPrompt.value = IndicatorState.IDLE
                    return@launch
                }

                // Kept raw OkHttp byte downloading for performance with binary files
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
                    ForgeQueueManager.saveRecoveredImageToCache(bytes)
                    withContext(Dispatchers.Main) {
                        parseAndApplyPngInfo(infoStr)
                    }
                    _isRestoringPrompt.value = IndicatorState.SUCCESS
                } else {
                    showSnackbar("Failed to extract data")
                    _isRestoringPrompt.value = IndicatorState.ERROR
                }
            } catch (e: Exception) {
                Log.e(TAG, "Network error", e)
                showSnackbar("Network error")
                _isRestoringPrompt.value = IndicatorState.ERROR
            }
            delay(1500)
            _isRestoringPrompt.value = IndicatorState.IDLE
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
                if (currentMode == 0) {
                    pos += line + "\n"
                } else if (currentMode == 1) {
                    neg += line + "\n"
                }
            }
        }

        ForgeSettingsManager.updateState { state ->
            val newState =
                state.copy(
                    positivePrompt = pos.trim(),
                    negativePrompt = neg.trim(),
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
        showSnackbar("Loaded generation data")
    }

    fun fetchGalleryFolder(path: String = config.value.galleryPath) {
        _isGalleryLoading.value = true
        _galleryError.value = null

        repositoryScope.launch(Dispatchers.IO) {
            try {
                if (path == "virtual://favorites") {
                    val favorites = db.favoriteImageDao().getAllFavorites()
                    val items =
                        favorites.map {
                            GalleryItem(
                                name = it.name,
                                fullpath = it.fullpath,
                                type = "file",
                                date = it.date,
                                createdTime = null,
                                size = null,
                            )
                        }
                    _galleryFiles.value = items
                    _currentGalleryPath.value = path
                    return@launch
                }

                val targetFolder = if (path.isNotEmpty() && path != "Root") path else ""
                val response = forgeApi?.getGalleryFiles(folderPath = targetFolder)

                if (response?.isSuccessful == true) {
                    val responseBody = response.body()?.string() ?: ""
                    val allItems = parseGalleryItems(responseBody).sortedWith(compareBy({ !it.isDir }, { it.name })).toMutableList()

                    if (path == "Root" || path == config.value.galleryPath) {
                        allItems.add(
                            0,
                            GalleryItem(
                                name = "⭐ Favorites",
                                fullpath = "virtual://favorites",
                                type = "dir",
                            ),
                        )
                    }

                    _galleryFiles.value = allItems
                    _currentGalleryPath.value = path
                } else if (response?.code() == 400 && path.isNotEmpty() && path != "Root") {
                    fetchGalleryFolder("Root")
                } else if (response?.code() == 401 || response?.code() == 403) {
                    _galleryError.value = "Authentication Required."
                } else {
                    _galleryError.value = "Server returned Error ${response?.code()}"
                }
            } catch (e: Exception) {
                _galleryError.value = e.message ?: "Failed to reach server."
            } finally {
                _isGalleryLoading.value = false
            }
        }
    }

    fun getGalleryImageUrl(item: GalleryItem): String {
        val urlStr = config.value.apiUrl.trimEnd('/')
        val builder =
            urlStr
                .toHttpUrlOrNull()
                ?.newBuilder()
                ?.addPathSegments("infinite_image_browsing/file")
                ?.addQueryParameter("path", item.fullpath)

        if (!item.date.isNullOrEmpty()) {
            builder?.addQueryParameter("t", item.date)
        }

        return builder?.build()?.toString() ?: ""
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
                        val contentValues =
                            ContentValues().apply {
                                put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
                                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ForgeGen")
                            }

                        val resolver = application.contentResolver
                        val insertUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                        val uri = resolver.insert(insertUri, contentValues)

                        if (uri != null) {
                            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                            showSnackbar("Saved to Downloads")
                        } else {
                            throw Exception("Failed to create file in MediaStore")
                        }
                    } else {
                        throw Exception("Server returned ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download Failed", e)
                showSnackbar("Download Failed: ${e.message}")
            }
        }
    }

    fun shareImage(
        item: GalleryItem,
        onIntentReady: (Intent) -> Unit,
    ) {
        repositoryScope.launch(Dispatchers.IO) {
            try {
                val url = getGalleryImageUrl(item)
                if (url.isEmpty()) throw Exception("Invalid Gallery URL")

                val request = Request.Builder().url(url).build()

                client.newCall(request).awaitResponse().use { response ->
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes() ?: throw Exception("Empty response body")
                        val contentValues =
                            ContentValues().apply {
                                put(MediaStore.MediaColumns.DISPLAY_NAME, "Shared_${item.name}")
                                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ForgeGen_Shared")
                            }

                        val resolver = application.contentResolver
                        val insertUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        val uri = resolver.insert(insertUri, contentValues)

                        if (uri != null) {
                            resolver.openOutputStream(uri)?.use { it.write(bytes) }

                            val shareIntent =
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "image/png"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                            withContext(Dispatchers.Main) {
                                onIntentReady(Intent.createChooser(shareIntent, "Share Image"))
                            }
                        } else {
                            throw Exception("Failed to prepare file for sharing")
                        }
                    } else {
                        throw Exception("Server returned ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Share Failed", e)
                showSnackbar("Share Failed: ${e.message}")
            }
        }
    }

    fun queueGeneration() = ForgeQueueManager.queueGeneration()

    fun resumeQueue() = ForgeQueueManager.resumeQueue()

    fun interruptGeneration() = ForgeQueueManager.interruptGeneration()

    fun updateQueueItem(
        id: String,
        positivePrompt: String,
        negativePrompt: String,
    ) = ForgeQueueManager.updateQueueItem(id, positivePrompt, negativePrompt)

    fun clearQueue() = ForgeQueueManager.clearQueue()

    fun removeFromQueue(id: String) = ForgeQueueManager.removeFromQueue(id)

    fun moveQueueItemUp(id: String) = ForgeQueueManager.moveQueueItemUp(id)

    fun moveQueueItemDown(id: String) = ForgeQueueManager.moveQueueItemDown(id)

    fun dismissGridPreview(index: Int? = null) = ForgeQueueManager.dismissGridPreview(index)

    fun sessionPrev() = ForgeQueueManager.sessionPrev()

    fun sessionNext() = ForgeQueueManager.sessionNext()

    fun downloadSessionImage(localFilePath: String) = ForgeQueueManager.downloadSessionImage(localFilePath)

    fun shareSessionImage(
        localFilePath: String,
        onIntentReady: (Intent) -> Unit,
    ) = ForgeQueueManager.shareSessionImage(localFilePath, onIntentReady)
}
