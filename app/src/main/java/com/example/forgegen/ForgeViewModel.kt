@file:Suppress("unused")

package com.example.forgegen

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient

/* ============================================================================
 * VIEW MODEL (UI STATE HOLDER)
 * Drastycznie odchudzony komponent. Służy wyłącznie jako lekkie proxy
 * przekazujące stan i akcje z ForgeRepository do warstwy UI Jetpack Compose.
 * ============================================================================ */

class ForgeViewModel(application: Application) : AndroidViewModel(application) {

    init {
        // Inicjalizacja repozytorium przy starcie aplikacji
        ForgeRepository.init(application)
    }

    // --- DELEGACJA STANU (STATE FLOWS) ---
    val config: StateFlow<AppConfig> = ForgeRepository.config
    val client: OkHttpClient get() = ForgeRepository.client
    val appState: StateFlow<AppState> = ForgeRepository.appState
    val promptHistory: StateFlow<List<PromptHistoryItem>> = ForgeRepository.promptHistory
    val activeLoras: StateFlow<List<ActiveLora>> = ForgeRepository.activeLoras

    val isConnected: StateFlow<Boolean> = ForgeRepository.isConnected
    val pingMs: StateFlow<Long> = ForgeRepository.pingMs
    val serverStats: StateFlow<List<ServerStatRecord>> = ForgeRepository.serverStats

    val progress: StateFlow<Float> = ForgeRepository.progress
    val currentEta: StateFlow<Double> = ForgeRepository.currentEta
    val isGenerating: StateFlow<Boolean> = ForgeRepository.isGenerating
    val statusText: StateFlow<String> = ForgeRepository.statusText

    val currentJobNo: StateFlow<Int> = ForgeRepository.currentJobNo
    val currentJobCount: StateFlow<Int> = ForgeRepository.currentJobCount
    val currentSamplingStep: StateFlow<Int> = ForgeRepository.currentSamplingStep
    val currentSamplingSteps: StateFlow<Int> = ForgeRepository.currentSamplingSteps

    val isServerBusy: StateFlow<Boolean> = ForgeRepository.isServerBusy
    val generationQueue: StateFlow<List<QueuedGeneration>> = ForgeRepository.generationQueue
    val isQueuePaused: StateFlow<Boolean> = ForgeRepository.isQueuePaused
    val oomAlert: StateFlow<Boolean> = ForgeRepository.oomAlert
    val vramUsage: StateFlow<String?> = ForgeRepository.vramUsage

    val totalQueueSize: StateFlow<Int> = ForgeRepository.totalQueueSize
    val completedQueueItems: StateFlow<Int> = ForgeRepository.completedQueueItems

    val sessionImages: StateFlow<List<String>> = ForgeRepository.sessionImages
    val currentSessionIndex: StateFlow<Int> = ForgeRepository.currentSessionIndex
    val livePreviewImage: StateFlow<String?> = ForgeRepository.livePreviewImage
    val isShowingGridPreview: StateFlow<Boolean> = ForgeRepository.isShowingGridPreview
    val currentBatchStartIndex: StateFlow<Int> = ForgeRepository.currentBatchStartIndex
    val currentBatchEndIndex: StateFlow<Int> = ForgeRepository.currentBatchEndIndex

    val tagSuggestions: StateFlow<List<String>> = ForgeRepository.tagSuggestions
    val isRestoringPrompt: StateFlow<Boolean> = ForgeRepository.isRestoringPrompt

    val selectedModel: StateFlow<String> = ForgeRepository.selectedModel
    val samplers: StateFlow<List<String>> = ForgeRepository.samplers
    val schedulers: StateFlow<List<String>> = ForgeRepository.schedulers
    val models: StateFlow<List<ApiResource>> = ForgeRepository.models
    val upscalers: StateFlow<List<String>> = ForgeRepository.upscalers
    val availableLoras: StateFlow<List<ApiResource>> = ForgeRepository.availableLoras

    val galleryFiles: StateFlow<List<GalleryItem>> = ForgeRepository.galleryFiles
    val currentGalleryPath: StateFlow<String> = ForgeRepository.currentGalleryPath
    val isGalleryLoading: StateFlow<Boolean> = ForgeRepository.isGalleryLoading
    val galleryError: StateFlow<String?> = ForgeRepository.galleryError
    val showGalleryMetadata: StateFlow<Boolean> = ForgeRepository.showGalleryMetadata
    val currentImageMetadata: StateFlow<String?> = ForgeRepository.currentImageMetadata
    val galleryMode: StateFlow<GalleryMode> = ForgeRepository.galleryMode

    // DELEGACJA STANU ULUBIONYCH
    val isCurrentFavorite: StateFlow<Boolean> = ForgeRepository.isCurrentFavorite
    val favoritePaths: StateFlow<Set<String>> = ForgeRepository.favoritePaths

    // DELEGACJA STANU UPDATERA
    val updateManifest: StateFlow<UpdateManifest?> = ForgeRepository.updateManifest
    val isUpdateDownloading: StateFlow<Boolean> = ForgeRepository.isUpdateDownloading
    val updateDownloadProgress: StateFlow<Float> = ForgeRepository.updateDownloadProgress

    // --- DELEGACJA AKCJI (FUNCTIONS) ---
    suspend fun getTagsForLora(hash: String) = ForgeRepository.getTagsForLora(hash)

    fun setAppForegroundState(isForeground: Boolean) = ForgeRepository.setAppForegroundState(isForeground)
    fun setGalleryMode(mode: GalleryMode) = ForgeRepository.setGalleryMode(mode)

    fun saveConfig(newConfig: AppConfig) = ForgeRepository.saveConfig(newConfig)
    fun saveCurrentAsDefault() = ForgeRepository.saveCurrentAsDefault()
    fun resetToDefaults() = ForgeRepository.resetToDefaults()
    fun savePreset(name: String) = ForgeRepository.savePreset(name)
    fun loadPreset(name: String) = ForgeRepository.loadPreset(name)
    fun deletePreset(name: String) = ForgeRepository.deletePreset(name)

    fun fetchAutoConfig() = ForgeRepository.fetchAutoConfig()

    fun getPreviewUrl(originalPath: String, isLora: Boolean = false) = ForgeRepository.getPreviewUrl(originalPath, isLora)
    fun addServerProfile(name: String, url: String) = ForgeRepository.addServerProfile(name, url)
    fun removeServerProfile(name: String) = ForgeRepository.removeServerProfile(name)

    fun updateState(update: (AppState) -> AppState) = ForgeRepository.updateState(update)
    fun clearPromptHistory() = ForgeRepository.clearPromptHistory()

    fun resumeQueue() = ForgeRepository.resumeQueue()
    fun interruptGeneration() = ForgeRepository.interruptGeneration()
    fun queueGeneration() = ForgeRepository.queueGeneration()
    fun updateQueueItem(id: String, positivePrompt: String, negativePrompt: String) = ForgeRepository.updateQueueItem(id, positivePrompt, negativePrompt)
    fun clearQueue() = ForgeRepository.clearQueue()
    fun removeFromQueue(id: String) = ForgeRepository.removeFromQueue(id)
    fun moveQueueItemUp(id: String) = ForgeRepository.moveQueueItemUp(id)
    fun moveQueueItemDown(id: String) = ForgeRepository.moveQueueItemDown(id)

    fun toggleGalleryMetadata() = ForgeRepository.toggleGalleryMetadata()
    fun loadMetadataForImage(item: GalleryItem?) = ForgeRepository.loadMetadataForImage(item)
    fun loadMetadataForLocalFile(filePath: String) = ForgeRepository.loadMetadataForLocalFile(filePath)

    fun changeCheckpoint(modelTitle: String) = ForgeRepository.changeCheckpoint(modelTitle)
    fun appendLora(name: String) = ForgeRepository.appendLora(name)
    fun updateLoraStrength(name: String, strength: Float) = ForgeRepository.updateLoraStrength(name, strength)
    fun removeLora(name: String) = ForgeRepository.removeLora(name)
    fun searchTags(query: String) = ForgeRepository.searchTags(query)

    fun recoverLastPrompt() = ForgeRepository.recoverLastPrompt()
    fun recoverLastSeed() = ForgeRepository.recoverLastSeed()
    fun recoverPromptFromImage(item: GalleryItem) = ForgeRepository.recoverPromptFromImage(item)

    fun fetchApiData() = ForgeRepository.fetchApiData()
    fun fetchGalleryFolder(path: String = config.value.galleryPath) = ForgeRepository.fetchGalleryFolder(path)
    fun getGalleryImageUrl(item: GalleryItem) = ForgeRepository.getGalleryImageUrl(item)

    fun downloadSessionImage(localFilePath: String) = ForgeRepository.downloadSessionImage(localFilePath)
    fun downloadImage(item: GalleryItem) = ForgeRepository.downloadImage(item)
    fun shareSessionImage(localFilePath: String, onIntentReady: (Intent) -> Unit) = ForgeRepository.shareSessionImage(localFilePath, onIntentReady)
    fun shareImage(item: GalleryItem, onIntentReady: (Intent) -> Unit) = ForgeRepository.shareImage(item, onIntentReady)

    fun dismissGridPreview(index: Int? = null) = ForgeRepository.dismissGridPreview(index)
    fun sessionPrev() = ForgeRepository.sessionPrev()
    fun sessionNext() = ForgeRepository.sessionNext()

    // DELEGACJA AKCJI ULUBIONYCH
    fun checkIfFavorite(path: String) = ForgeRepository.checkIfFavorite(path)
    fun toggleFavorite(item: GalleryItem) = ForgeRepository.toggleFavorite(item)

    // DELEGACJA AKCJI UPDATERA
    fun checkForUpdates(manual: Boolean = false) = ForgeRepository.checkForUpdates(manual)
    fun downloadAndInstallUpdate() = ForgeRepository.downloadAndInstallUpdate()
}