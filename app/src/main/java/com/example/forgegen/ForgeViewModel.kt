
package com.example.forgegen

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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

 * Drastycznie odchudzony komponent. Służy wyłącznie jako lekkie proxy

 * przekazujące stan i akcje z Menedżerów oraz Repozytorium do warstwy UI.

 * Poprawiono architekturę, wiążąc osierocone Menedżery.

 * ============================================================================ */

class ForgeViewModel(
    application: Application,
) : AndroidViewModel(application) {
    // --- GLOBALNY SYSTEM TOASTÓW ---

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

    // --- INICJALIZACJA MENEDŻERÓW ---

    val networkManager: ForgeNetworkManager

    val updateManager: ForgeUpdateManager

    init {

        // 1. Core Repository (SSOT)

        ForgeRepository.init(application)

        // 2. Network Manager

        networkManager =
            ForgeNetworkManager(
                application = application,
                db = ForgeRepository.db,
                getConfig = { ForgeRepository.config.value },
                updateConfig = { ForgeSettingsManager.saveConfig(it) },
                showToast = { showToast(it) },
                managerScope = viewModelScope,
            )

        // 3. Gallery & Queue Managers

        ForgeGalleryManager.init(application, ForgeRepository.db, networkManager)
        ForgeQueueManager.init(application)

        // 4. Update Manager
        updateManager =
            ForgeUpdateManager(
                application = application,
                getForgeApi = { ForgeRepository.forgeApi },
                getConfig = { ForgeRepository.config.value },
                saveConfig = { ForgeSettingsManager.saveConfig(it) },
                showToast = { showToast(it) },
                scope = viewModelScope,
            )

        // Podłącz logikę subskrypcji zdarzeń toastów z repozytorium do viewModelu
        viewModelScope.launch {
            ForgeRepository.snackbarMessage.collect {
                showToast(it)
            }
        }
    }

    // --- DELEGACJA STANU Z FORGE REPOSITORY ---
    val config: StateFlow<AppConfig> = ForgeRepository.config
    val client: OkHttpClient get() = ForgeRepository.client
    val appState: StateFlow<AppState> = ForgeRepository.appState
    val promptHistory: StateFlow<List<PromptHistoryItem>> = ForgeRepository.promptHistory
    val wildcards: StateFlow<List<WildcardEntity>> = ForgePromptManager.wildcards

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

    // --- DELEGACJA STANU Z FORGE NETWORK MANAGER ---
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

    // --- DELEGACJA STANU Z FORGE GALLERY MANAGER ---
    val galleryFiles: StateFlow<List<GalleryItem>> = ForgeGalleryManager.galleryFiles
    val displayedFiles: StateFlow<List<GalleryItem>> = ForgeGalleryManager.displayedFiles // NOWE (zoptymalizowane filtrowanie)
    val currentGalleryPath: StateFlow<String> = ForgeGalleryManager.currentGalleryPath
    val isGalleryLoading: StateFlow<Boolean> = ForgeGalleryManager.isGalleryLoading
    val galleryError: StateFlow<String?> = ForgeGalleryManager.galleryError
    val showGalleryMetadata: StateFlow<Boolean> = ForgeGalleryManager.showGalleryMetadata
    val currentImageMetadata: StateFlow<String?> = ForgeGalleryManager.currentImageMetadata
    val galleryMode: StateFlow<GalleryMode> = ForgeGalleryManager.galleryMode

    val isCurrentFavorite: StateFlow<Boolean> = ForgeGalleryManager.isCurrentFavorite
    val favoritePaths: StateFlow<Set<String>> = ForgeGalleryManager.favoritePaths
    val isRestoringPrompt: StateFlow<IndicatorState> = ForgeGalleryManager.isRestoringPrompt

    // --- DELEGACJA STANU Z FORGE QUEUE MANAGER ---
    val progress: StateFlow<Float> = ForgeQueueManager.progress
    val currentEta: StateFlow<Double> = ForgeQueueManager.currentEta
    val isGenerating: StateFlow<Boolean> = ForgeQueueManager.isGenerating
    val statusText: StateFlow<String> = ForgeQueueManager.statusText
    val generationQueue: StateFlow<List<QueuedGeneration>> = ForgeQueueManager.generationQueue
    val isQueuePaused: StateFlow<Boolean> = ForgeQueueManager.isQueuePaused
    val oomAlert: StateFlow<Boolean> = ForgeQueueManager.oomAlert
    val totalQueueSize: StateFlow<Int> = ForgeQueueManager.totalQueueSize
    val completedQueueItems: StateFlow<Int> = ForgeQueueManager.completedQueueItems
    val sessionImages: StateFlow<List<String>> = ForgeQueueManager.sessionImages
    val currentSessionIndex: StateFlow<Int> = ForgeQueueManager.currentSessionIndex
    val livePreviewImage: StateFlow<String?> = ForgeQueueManager.livePreviewImage
    val isShowingGridPreview: StateFlow<Boolean> = ForgeQueueManager.isShowingGridPreview
    val currentBatchStartIndex: StateFlow<Int> = ForgeQueueManager.currentBatchStartIndex
    val currentBatchEndIndex: StateFlow<Int> = ForgeQueueManager.currentBatchEndIndex

    // --- DELEGACJA STANU Z FORGE UPDATE MANAGER ---
    val updateManifest: StateFlow<UpdateManifest?> = updateManager.updateManifest
    val isUpdateDownloading: StateFlow<Boolean> = updateManager.isUpdateDownloading
    val updateDownloadProgress: StateFlow<Float> = updateManager.updateDownloadProgress
    val updateDownloadStats: StateFlow<Pair<Long, Long>> = updateManager.updateDownloadStats

    // --- STAN DLA IMPORTOWANEGO OBRAZU (Share Intent) ---
    private val _importedImageMetadata = MutableStateFlow<String?>(null)
    val importedImageMetadata: StateFlow<String?> = _importedImageMetadata.asStateFlow()

    // --- STAN DLA NOWYCH FUNKCJI (Kiosk Mode, Server Stats Range) ---
    private val _isKioskMode = MutableStateFlow(false)
    val isKioskMode: StateFlow<Boolean> = _isKioskMode.asStateFlow()

    private val STATS_TIME_RANGE_KEY = intPreferencesKey("stats_time_range")
    val statsTimeRangeMinutes: StateFlow<Int> =
        getApplication<Application>()
            .dataStore.data
            .map { it[STATS_TIME_RANGE_KEY] ?: 15 }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 15)

    fun setStatsTimeRange(minutes: Int) {
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit { it[STATS_TIME_RANGE_KEY] = minutes }
        }
    }

    // Gallery Filters Delegation
    val favoritesSearchQuery: StateFlow<String> = ForgeGalleryManager.favoritesSearchQuery
    val favoritesSortOrder: StateFlow<String> = ForgeGalleryManager.favoritesSortOrder
    val favoritesFilterModels: StateFlow<Set<String>> = ForgeGalleryManager.favoritesFilterModels

    // --- DELEGACJA AKCJI DO REPOZYTORIUM ---
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
    ) = ForgeRepository.addServerProfile(name, url)

    fun removeServerProfile(name: String) = ForgeSettingsManager.removeServerProfile(name)

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

    // --- DELEGACJA AKCJI DO MENEDŻERA KOLEJKI ---
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

    // --- DELEGACJA AKCJI DO MENEDŻERA AKTUALIZACJI ---
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

    // --- DELEGACJA AKCJI DO MENEDŻERÓW (Missing ones) ---
    fun loadMetadataForImage(item: GalleryItem?) = ForgeGalleryManager.loadMetadataForImage(item)

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

    fun cancelPromptRestore() = ForgeGalleryManager.cancelPromptRestore()

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
