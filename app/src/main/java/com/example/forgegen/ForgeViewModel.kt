
package com.example.forgegen

import com.example.forgegen.ui.components.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/* ============================================================================

 * VIEW MODEL (UI STATE HOLDER)

 * Drastically slimmed down component. Serves exclusively as a lightweight proxy

 * passing state and actions from Managers and Repository to the UI layer.

 * Improved architecture by linking orphaned Managers.

 * ============================================================================ */

class ForgeViewModel(
    application: Application,
) : AndroidViewModel(application) {
    // --- GLOBAL TOAST SYSTEM ---

    private val _toastMessage = MutableSharedFlow<String>(extraBufferCapacity = 10)

    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()

    fun showToast(message: String) {
        _toastMessage.tryEmit(message)
    }

    private val _isAppBlurred = MutableStateFlow(false)

    val isAppBlurred: StateFlow<Boolean> = _isAppBlurred.asStateFlow()

    fun setAppBlurred(blurred: Boolean) {
        _isAppBlurred.value = blurred
    }

    // --- INITIALIZATION OF MANAGERS ---

    val networkManager: ForgeNetworkManager

    val updateManager: ForgeUpdateManager

    init {
        // Create managers but do NOT start them yet.
        networkManager =
            ForgeNetworkManager(
                application = getApplication(),
                getDb = { ForgeRepository.db },
                getConfig = { ForgeRepository.config.value },
                updateConfig = { ForgeSettingsManager.saveConfig(it) },
                showToast = { showToast(it) },
                managerScope = viewModelScope,
            )

        ForgeGalleryManager.init(getApplication(), { ForgeRepository.db }, networkManager)
        ForgeQueueManager.init(getApplication())

        updateManager =
            ForgeUpdateManager(
                application = getApplication(),
                getForgeApi = { ForgeRepository.forgeApi },
                getConfig = { ForgeRepository.config.value },
                saveConfig = { ForgeSettingsManager.saveConfig(it) },
                showToast = { showToast(it) },
                scope = viewModelScope,
            )

        // Connect toast event subscription logic from repository to viewmodel
        viewModelScope.launch {
            ForgeSettingsManager.snackbarMessage.collect {
                showToast(it)
            }
        }
    }

    suspend fun initializeApp() {
        if (ForgeSettingsManager.isInitialized.value) return

        // 1. Init Database & Settings
        ForgeRepository.initializeDatabaseAndSettings(getApplication())
        ForgePromptManager.init() // wildcards must be loaded before the first job expands __name__ tokens

        // Load stats time range from database
        val json = ForgeRepository.db.appSettingDao().getSetting(STATS_TIME_RANGE_KEY)?.value
        if (json != null) {
            try {
                _statsTimeRangeMinutes.value = json.toInt()
            } catch (e: Exception) {}
        }

        // 2. Init API Clients
        ForgeRepository.initializeApiClientAndData()

        // 3. Start Managers
        ForgeSettingsManager.updateInitStatus("Starting Managers...")
        networkManager.start()
        ForgeGalleryManager.start()
        ForgeQueueManager.start()

        // Check for updates (runs in background)
        updateManager.checkForUpdates(manual = false)

        // 4. Mark as Initialized
        ForgeSettingsManager.setInitialized()
        ForgeSettingsManager.updateInitStatus("Ready")
    }

    // --- DELEGATION OF STATE FROM FORGE REPOSITORY ---
    val config: StateFlow<AppConfig> = ForgeRepository.config
    val client: OkHttpClient get() = ForgeRepository.client
    val appState: StateFlow<AppState> = ForgeRepository.appState
    val promptHistory: StateFlow<List<PromptHistoryItem>> = ForgeRepository.promptHistory
    val wildcards: StateFlow<List<WildcardEntity>> = ForgePromptManager.wildcards
    val pinnedImages: StateFlow<Set<String>> = ForgeSettingsManager.pinnedImages

    val activeLoras: StateFlow<List<ActiveLora>> = ForgeRepository.activeLoras

    val isConnected: StateFlow<Boolean> = ForgeRepository.isConnected
    val pingMs: StateFlow<Long> = ForgeRepository.pingMs
    val serverStats: StateFlow<List<ServerStatRecord>> = ForgeRepository.serverStats

    val currentJobNo: StateFlow<Int> = ForgeRepository.currentJobNo
    val currentJobCount: StateFlow<Int> = ForgeRepository.currentJobCount
    val currentSamplingStep: StateFlow<Int> = ForgeRepository.currentSamplingStep
    val currentSamplingSteps: StateFlow<Int> = ForgeRepository.currentSamplingSteps

    val isServerBusy: StateFlow<Boolean> = ForgeRepository.isServerBusy
    val vramUsage: StateFlow<String?> = ForgeRepository.vramUsage
    val ramUsage: StateFlow<String?> = kotlinx.coroutines.flow.MutableStateFlow(null)
    val positivePromptTokens: StateFlow<Int?> = kotlinx.coroutines.flow.MutableStateFlow(null)

    // --- DELEGATION OF STATE FROM FORGE NETWORK MANAGER ---
    val selectedModel: StateFlow<String> = networkManager.selectedModel
    val samplers: StateFlow<List<String>> = networkManager.samplers
    val schedulers: StateFlow<List<String>> = networkManager.schedulers
    val models: StateFlow<List<ApiResource>> = networkManager.models
    val upscalers: StateFlow<List<String>> = networkManager.upscalers
    val availableLoras: StateFlow<List<ApiResource>> = networkManager.availableLoras

    val isCivitaiSyncing: StateFlow<IndicatorState> = networkManager.isCivitaiSyncing
    val civitaiSyncCurrentModel: StateFlow<String> = networkManager.civitaiSyncCurrentModel
    val civitaiSyncProgress: StateFlow<Pair<Int, Int>> = networkManager.civitaiSyncProgress
    val civitaiSyncLastResult: StateFlow<String?> = networkManager.civitaiSyncLastResult

    // --- DELEGATION OF STATE FROM FORGE GALLERY MANAGER ---
    val galleryFiles: StateFlow<List<GalleryItem>> = ForgeGalleryManager.galleryFiles
    val displayedFiles: StateFlow<List<GalleryItem>> = ForgeGalleryManager.displayedFiles // NEW (optimized filtering)
    val currentGalleryPath: StateFlow<String> = ForgeGalleryManager.currentGalleryPath
    val isGalleryLoading: StateFlow<Boolean> = ForgeGalleryManager.isGalleryLoading
    val isGallerySyncing: StateFlow<IndicatorState> = ForgeGalleryManager.isGallerySyncing
    val gallerySyncCurrentFile: StateFlow<String> = ForgeGalleryManager.gallerySyncCurrentFile
    val gallerySyncProgress: StateFlow<Pair<Int, Int>> = ForgeGalleryManager.gallerySyncProgress
    val galleryIndexCount: StateFlow<Int> = ForgeGalleryManager.galleryIndexCount
    val galleryError: StateFlow<String?> = ForgeGalleryManager.galleryError
    val showGalleryMetadata: StateFlow<Boolean> = ForgeGalleryManager.showGalleryMetadata
    val currentImageMetadata: StateFlow<String?> = ForgeGalleryManager.currentImageMetadata
    val galleryMode: StateFlow<GalleryMode> = ForgeGalleryManager.galleryMode

    val isCurrentFavorite: StateFlow<Boolean> = ForgeGalleryManager.isCurrentFavorite
    val favoritePaths: StateFlow<Set<String>> = ForgeGalleryManager.favoritePaths
    val isRestoringPrompt: StateFlow<IndicatorState> = ForgeGalleryManager.isRestoringPrompt

    // --- DELEGATION OF STATE FROM FORGE QUEUE MANAGER ---
    val progress: StateFlow<Float> = ForgeQueueManager.progress
    val currentEta: StateFlow<Double> = ForgeQueueManager.currentEta
    val isGenerating: StateFlow<Boolean> = ForgeQueueManager.isGenerating
    val statusText: StateFlow<String> = ForgeQueueManager.statusText
    val generationQueue: StateFlow<List<QueuedGeneration>> = ForgeQueueManager.generationQueue
    val isQueuePaused: StateFlow<Boolean> = ForgeQueueManager.isQueuePaused
    val oomAlert: StateFlow<Boolean> = ForgeQueueManager.oomAlert
    val queuePauseReason: StateFlow<String?> = ForgeQueueManager.queuePauseReason
    val totalQueueSize: StateFlow<Int> = ForgeQueueManager.totalQueueSize
    val completedQueueItems: StateFlow<Int> = ForgeQueueManager.completedQueueItems
    val sessionImages: StateFlow<List<String>> = ForgeQueueManager.sessionImages
    val currentSessionIndex: StateFlow<Int> = ForgeQueueManager.currentSessionIndex
    val livePreviewImage: StateFlow<String?> = ForgeQueueManager.livePreviewImage
    val isShowingGridPreview: StateFlow<Boolean> = ForgeQueueManager.isShowingGridPreview
    val currentBatchStartIndex: StateFlow<Int> = ForgeQueueManager.currentBatchStartIndex
    val currentBatchEndIndex: StateFlow<Int> = ForgeQueueManager.currentBatchEndIndex

    // --- DELEGATION OF STATE FROM FORGE UPDATE MANAGER ---
    val updateManifest: StateFlow<UpdateManifest?> = updateManager.updateManifest
    val isUpdateDownloading: StateFlow<Boolean> = updateManager.isUpdateDownloading
    val updateDownloadProgress: StateFlow<Float> = updateManager.updateDownloadProgress
    val updateDownloadStats: StateFlow<Pair<Long, Long>> = updateManager.updateDownloadStats

    // --- STATE FOR IMPORTED IMAGE (Share Intent) ---
    private val _importedImageMetadata = MutableStateFlow<String?>(null)
    val importedImageMetadata: StateFlow<String?> = _importedImageMetadata.asStateFlow()

    // --- STATE FOR NEW FEATURES (Kiosk Mode, Server Stats Range) ---
    private val _isKioskMode = MutableStateFlow(false)
    val isKioskMode: StateFlow<Boolean> = _isKioskMode.asStateFlow()

    private val STATS_TIME_RANGE_KEY = "stats_time_range"
    private val _statsTimeRangeMinutes = MutableStateFlow(15)
    val statsTimeRangeMinutes: StateFlow<Int> = _statsTimeRangeMinutes.asStateFlow()

    fun setStatsTimeRange(minutes: Int) {
        _statsTimeRangeMinutes.value = minutes
        viewModelScope.launch(Dispatchers.IO) {
            ForgeRepository.db.appSettingDao().putSetting(AppSettingEntity(STATS_TIME_RANGE_KEY, minutes.toString()))
        }
    }

    // Gallery Filters Delegation
    val galleryFilters: StateFlow<ForgeGalleryManager.GalleryFilters> = ForgeGalleryManager.galleryFilters
    val availableModels: StateFlow<List<String>> = ForgeGalleryManager.availableModels
    val galleryAvailableLoras: StateFlow<List<String>> = ForgeGalleryManager.availableLoras

    fun applyGalleryFilters(filters: ForgeGalleryManager.GalleryFilters) = ForgeGalleryManager.applyFilters(filters)
    fun clearGalleryFilters() = ForgeGalleryManager.clearFilters()
    fun cancelPromptRestore() = ForgeGalleryManager.cancelPromptRestore()

    fun togglePinnedImage(path: String) = ForgeSettingsManager.togglePinnedImage(path)

    // --- DELEGATION OF ACTIONS TO REPOSITORY ---
    suspend fun getTagsForLora(hash: String) = ForgeModelManager.getTagsForLora(hash)

    fun saveWildcard(
        name: String,
        content: String,
    ) = ForgePromptManager.saveWildcard(name, content)

    fun setAppForegroundState(isForeground: Boolean) = ForgeRepository.setAppForegroundState(isForeground)

    fun saveConfig(newConfig: AppConfig) = ForgeSettingsManager.saveConfig(newConfig)

    fun saveCurrentAsDefault() = ForgeSettingsManager.saveCurrentAsDefault()

    fun resetToDefaults() = ForgeSettingsManager.resetToDefaults()

    fun savePreset(
        name: String,
        includePrompts: Boolean = true,
    ) = ForgeSettingsManager.savePreset(name, includePrompts)

    fun loadPreset(name: String) = ForgeRepository.loadPreset(name)

    fun deletePreset(name: String) = ForgeRepository.deletePreset(name)

    fun updatePreset(
        oldName: String,
        updated: GenerationPreset,
    ) = ForgeSettingsManager.updatePreset(oldName, updated)

    fun fetchAutoConfig() = ForgeRepository.fetchAutoConfig()

    fun getPreviewUrl(
        originalPath: String,
        isLora: Boolean = false,
    ) = ForgeRepository.getPreviewUrl(originalPath, isLora)

    fun addServerProfile(
        name: String,
        url: String,
    ) = ForgeSettingsManager.addServerProfile(name, url)

    fun removeServerProfile(name: String) = ForgeSettingsManager.removeServerProfile(name)

    fun wipeGalleryIndex() {
        ForgeGalleryManager.clearDatabase()
    }
    
    fun cancelManualGallerySync() {
        ForgeGalleryManager.cancelManualGallerySync()
    }
    
    fun putSyncToBackground() {
        ForgeGalleryManager.putSyncToBackground()
    }
    
    fun wipeAllData() {
        viewModelScope.launch(Dispatchers.IO) {
            ForgeSettingsManager.resetToDefaults()
            ForgeSettingsManager.clearPromptHistory()
            showToast("Data wiped")
        }
    }

    fun wipeSettings() = ForgeSettingsManager.resetToDefaults()

    fun wipePresets() = ForgeSettingsManager.saveConfig(ForgeSettingsManager.config.value.copy(presets = emptyList()))

    fun wipeServerProfiles() =
        ForgeSettingsManager.saveConfig(
            ForgeSettingsManager.config.value.copy(serverProfiles = listOf(ServerProfile("Default Local", "http://192.168.1.90:7860"))),
        )

    fun wipePromptHistory() = ForgeSettingsManager.clearPromptHistory()

    fun wipeWildcards() = ForgePromptManager.deleteAllWildcards()

    // --- DELEGATION OF ACTIONS TO QUEUE MANAGER ---
    fun resumeQueue() = ForgeQueueManager.resumeQueue()

    fun interruptGeneration() = ForgeQueueManager.interruptGeneration()

    fun queueGeneration() = ForgeQueueManager.queueGeneration()

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

    // --- DELEGATION OF ACTIONS TO UPDATE MANAGER ---
    fun checkForUpdates(manual: Boolean = false) = updateManager.checkForUpdates(manual)

    fun downloadUpdate() = updateManager.downloadUpdate()

    fun installUpdate() = updateManager.installUpdate()

    fun dismissUpdate() = updateManager.dismissUpdate()

    // --- REFRESH, VRAM & PNG INFO ACTIONS ---
    fun refreshCheckpoints() {
        networkManager.refreshCheckpoints { success, msg ->
            showToast(msg)
        }
    }

    fun refreshLoras() {
        networkManager.refreshLoras { success, msg ->
            if (success) {
                showToast("Loras refreshed!")
            } else {
                showToast("Error: $msg")
            }
        }
    }

    // --- DELEGATION OF ACTIONS TO MANAGERS (Missing ones) ---
    fun loadMetadataForImage(item: GalleryItem?) = ForgeGalleryManager.loadMetadataForImage(item)

    fun loadMetadataForLocalFile(path: String) = ForgeGalleryManager.loadMetadataForLocalFile(path)

    fun checkIfFavorite(path: String) = ForgeGalleryManager.checkIfFavorite(path)

    fun toggleFavorite(item: GalleryItem) = ForgeGalleryManager.toggleFavorite(item)

    fun shareImage(
        item: GalleryItem,
        startActivity: (Intent) -> Unit,
    ) = ForgeGalleryManager.shareImage(item, startActivity)

    fun toggleGalleryMetadata() = ForgeGalleryManager.toggleGalleryMetadata()

    fun downloadImage(item: GalleryItem) = ForgeGalleryManager.downloadImage(item)

    fun getGalleryImageUrl(item: GalleryItem): String = ForgeGalleryManager.getGalleryImageUrl(item)

    fun recoverPromptFromImage(item: GalleryItem) = ForgeGalleryManager.recoverPromptFromImage(item)

    fun fetchGalleryFolder(path: String) = ForgeGalleryManager.fetchGalleryFolder(path)

    fun triggerManualGallerySync() = ForgeGalleryManager.triggerManualGallerySync()

    suspend fun extractMetadataFromUri(uri: android.net.Uri): String? = ForgeGalleryManager.extractMetadataFromUri(uri)

    fun setImportedImageMetadata(data: String?) {
        _importedImageMetadata.value = data
    }

    fun cancelCivitaiSync() = networkManager.cancelCivitaiSync()

    fun setGalleryMode(mode: GalleryMode) = ForgeGalleryManager.setGalleryMode(mode)

    fun removeLora(name: String) = ForgeRepository.removeLora(name)

    fun updateLoraStrength(
        name: String,
        strength: Float,
    ) = ForgeRepository.updateLoraStrength(name, strength)

    fun recoverLastSeed() = ForgeGalleryManager.recoverLastSeed()

    fun recoverLastPrompt() = ForgeGalleryManager.recoverLastPrompt()

    fun changeCheckpoint(modelTitle: String) = networkManager.changeCheckpoint(modelTitle)

    fun syncCivitaiModelsManual() = networkManager.syncCivitaiModelsManual()

    fun appendLora(loraName: String) = ForgeRepository.appendLora(loraName)

    fun updateState(transform: (AppState) -> AppState) = ForgeSettingsManager.updateState(transform)

    fun deleteWildcard(wildcard: WildcardEntity) = ForgePromptManager.deleteWildcard(wildcard.name)

    fun unloadCheckpoint() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val response = ForgeRepository.forgeApi?.unloadCheckpoint()
                if (response?.isSuccessful == true) {
                    showToast("Model unloaded")
                } else {
                    showToast("Failed to unload model")
                }
            } catch (e: Exception) {
                showToast("Error unloading model")
            }
        }
    }

    fun getPngInfoForGalleryItem(
        item: GalleryItem,
        onResult: (String?) -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val imageUrl = getGalleryImageUrl(item)
                val request =
                    okhttp3.Request
                        .Builder()
                        .url(imageUrl)
                        .build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bytes = response.body.bytes()
                        val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                        val info = ForgeSettingsManager.getPngInfoFromServer(base64)
                        onResult(info)
                        return@launch
                    }
                }

                onResult(null)
            } catch (e: Exception) {
                android.util.Log.e("ForgeViewModel", "Failed to fetch PNG info from server: $e")

                onResult(null)
            }
        }
    }
}
