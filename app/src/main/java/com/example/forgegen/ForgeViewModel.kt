
package com.example.forgegen

import com.example.forgegen.ui.components.*
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    // --- APP LOCK ---
    // Kept here rather than in the UI: it survives rotation (a remember{} was reset by it) but not a restart of the
    // process, so the app is always locked again after being killed.
    private val unlocked = MutableStateFlow(false)

    val isLocked: StateFlow<Boolean> =
        combine(ForgeRepository.config, unlocked) { config, isUnlocked -> config.useNativeSecurity && !isUnlocked }
            .stateIn(viewModelScope, SharingStarted.Eagerly, ForgeRepository.config.value.useNativeSecurity)

    fun lockApp() {
        unlocked.value = false
    }

    fun markUnlocked() {
        unlocked.value = true
    }

    // --- WHAT'S NEW ---
    // The changelog of the versions installed since the app was last opened (shown once, after an update).
    private val _whatsNew = MutableStateFlow<String?>(null)
    val whatsNew: StateFlow<String?> = _whatsNew.asStateFlow()

    private val installedVersion get() = BuildConfig.VERSION_NAME.removeSuffix("-DEBUG")

    private suspend fun checkWhatsNew() {
        try {
            val app = getApplication<Application>()
            val settings = ForgeRepository.db.appSettingDao()
            val lastSeen = settings.getSetting(WhatsNew.LAST_SEEN_KEY)?.value
            if (lastSeen == installedVersion) return
            val info = app.packageManager.getPackageInfo(app.packageName, 0)
            val changelog = app.assets.open(WhatsNew.CHANGELOG_ASSET).bufferedReader().use { it.readText() }
            val notes = WhatsNew.notesFor(changelog, installedVersion, lastSeen, wasUpdated = info.lastUpdateTime > info.firstInstallTime)
            if (notes != null) {
                _whatsNew.value = notes
            } else {
                // A fresh install: nothing to show now, but the next update shows what is new since this version.
                settings.putSetting(AppSettingEntity(WhatsNew.LAST_SEEN_KEY, installedVersion))
            }
        } catch (e: Exception) {
            Log.w("ForgeViewModel", "Cannot read the changelog", e)
        }
    }

    fun dismissWhatsNew() {
        _whatsNew.value = null
        viewModelScope.launch(Dispatchers.IO) {
            ForgeRepository.db.appSettingDao().putSetting(AppSettingEntity(WhatsNew.LAST_SEEN_KEY, installedVersion))
        }
    }

    // --- DEBUG MODE (DebugMode) ---
    val debugUnlocked: StateFlow<Boolean> = DebugMode.unlocked
    val debugForceNowBar: StateFlow<Boolean> = DebugMode.forceNowBar

    /** Checks the password off the main thread (PBKDF2 is slow on purpose). */
    suspend fun debugUnlock(password: String): DebugMode.UnlockResult = withContext(Dispatchers.Default) { DebugMode.unlock(password) }

    fun debugLock() = DebugMode.lock()

    fun debugSetForceNowBar(on: Boolean) = DebugMode.setForceNowBar(on)


    /** The settings as JSON, for the raw editor. */
    fun debugConfigJson(): String =
        com.google.gson
            .GsonBuilder()
            .setPrettyPrinting()
            .create()
            .toJson(ForgeSettingsManager.config.value)

    /** Stores settings edited as JSON (checked like stored settings are); returns an error, or null when saved. */
    fun debugApplyConfigJson(json: String): String? {
        if (!DebugMode.unlocked.value) return "The debug mode is locked"
        val parsed =
            try {
                com.google.gson.JsonParser
                    .parseString(json)
                    .asJsonObject
            } catch (e: Exception) {
                return "Not valid JSON: ${e.message}"
            }
        ForgeSettingsManager.saveConfig(ForgeSettingsManager.loadConfig(parsed.toString()))
        return null
    }

    /** Shows the "What's New" dialog of the installed version again. */
    fun debugShowWhatsNew() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val assets = getApplication<Application>().assets
                val changelog = assets.open(WhatsNew.CHANGELOG_ASSET).bufferedReader().use { it.readText() }
                _whatsNew.value = WhatsNew.notesFor(changelog, installedVersion, null, wasUpdated = true)
                    ?: "No notes for $installedVersion in the changelog."
            } catch (e: Exception) {
                ForgeSettingsManager.showToast("Cannot read the changelog: ${e.message}")
            }
        }
    }

    /** Offers the latest release even when it is not newer, to reinstall it. */
    fun debugOfferLatestRelease() = updateManager.checkForUpdates(manual = true, offerAnyRelease = true)

    fun debugTestNotification(kind: String) = ForgeQueueManager.debugNotify(kind)

    fun debugSaveFullLog() = OomLogs.saveDebugLog()

    fun debugRebuildModelLists() = networkManager.fetchApiData()

    // --- INITIALIZATION OF MANAGERS ---

    val networkManager: ForgeNetworkManager

    val updateManager: ForgeUpdateManager

    init {
        ForgeNotifications.init(getApplication())
        OomLogs.install(getApplication())
        DebugMode.init(getApplication())

        // Create managers but do NOT start them yet.
        networkManager =
            ForgeNetworkManager(
                getDb = { ForgeRepository.db },
                getConfig = { ForgeRepository.config.value },
                updateConfig = { ForgeSettingsManager.saveConfig(it) },
                managerScope = viewModelScope,
            )

        ForgeGalleryManager.init(getApplication(), { ForgeRepository.db }, networkManager)
        ForgeQueueManager.init(getApplication())

        updateManager =
            ForgeUpdateManager(
                application = getApplication(),
                gitHubApi = GitHubApi.create(),
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

    // A screen recreated meanwhile (e.g. rotation) waits for the same start instead of beginning a second one.
    private var initialization: Deferred<Unit>? = null

    // The server's part of the start (its first answer and its lists), which the screen does not wait for.
    private var serverCheck: Deferred<Unit>? = null

    /** True once the app's own data is loaded (settings, queue, gallery index); the splash stays until then. */
    val isStarted: StateFlow<Boolean> = ForgeSettingsManager.isInitialized

    /**
     * Starts every part of the app and returns when the phone's part is ready to work: database, settings,
     * wildcards, queue and gallery index. The server is checked after that without holding the start (since 2.0.0
     * the main screen shows at once and the top bar tells how the connection goes); its status ends with "Ready"
     * once its lists (models, LoRAs, samplers...) are loaded, or says why not.
     */
    suspend fun initializeApp() {
        val running = initialization ?: viewModelScope.async { startApp() }.also { initialization = it }
        running.await()
    }

    /** Returns once the server's part of the start has finished too (its final status is set). */
    suspend fun awaitServerCheck() {
        initializeApp()
        serverCheck?.await()
    }

    private suspend fun startApp() {
        // The app-wide part runs once per process. When the process outlived the previous activity (e.g. Back was
        // pressed while the background service kept it alive) it is done already, and only this ViewModel's network
        // manager is new and must start, otherwise model lists stay empty and the gallery has no API.
        val app = getApplication<Application>()
        val appWide =
            synchronized(Companion) {
                appStart ?: ForgeRepository.repositoryScope.async { startAppWide(app) }.also { appStart = it }
            }
        appWide.await()
        networkManager.start()

        // In the background: not needed to work, and GitHub may answer slowly.
        updateManager.checkForUpdates(manual = false)
        withContext(Dispatchers.IO) { checkWhatsNew() }

        ForgeSettingsManager.setInitialized()
        serverCheck = viewModelScope.async { awaitServer() }
    }

    /** Database, settings, wildcards, API clients and the app-wide managers, each with its saved data loaded. */
    private suspend fun startAppWide(app: Application) {
        ForgeRepository.initializeDatabaseAndSettings(app)
        ForgeSettingsManager.updateInitStatus("Loading Wildcards...")
        ForgePromptManager.init()

        ForgeRepository.initializeApiClientAndData() // API clients, the background service and the server ping

        ForgeSettingsManager.updateInitStatus("Loading Queue...")
        ForgeQueueManager.start()
        ForgeSettingsManager.updateInitStatus("Loading Gallery...")
        ForgeGalleryManager.start()
    }

    /** The last step of the start: the server's first answer and its lists. Sets the final status. */
    private suspend fun awaitServer() {
        ForgeSettingsManager.updateInitStatus("Connecting to Server...")
        // The ping gives up after the connection timeout; the start does not wait longer than SERVER_CHECK_MAX_MS.
        val checkTimeout = (ForgeRepository.config.value.timeout * 1000L).coerceAtMost(SERVER_CHECK_MAX_MS) + 1000L
        if (!ForgeRepository.awaitServerCheck(checkTimeout)) {
            ForgeSettingsManager.updateInitStatus("Server not reachable")
            return
        }
        ForgeSettingsManager.updateInitStatus("Loading Models...")
        val status =
            when (networkManager.awaitServerData(SERVER_DATA_MAX_MS)) {
                ForgeNetworkManager.ServerData.LOADED -> "Ready"
                ForgeNetworkManager.ServerData.INCOMPLETE -> "Connected, but the model list failed to load"
                ForgeNetworkManager.ServerData.PENDING -> "Connected, model lists still loading"
            }
        ForgeSettingsManager.updateInitStatus(status)
    }

    private companion object {
        const val SERVER_CHECK_MAX_MS = 10_000L
        const val SERVER_DATA_MAX_MS = 15_000L

        // Shared by every ViewModel of the process and run in a process-wide scope: a ViewModel cleared half-way
        // (the start screen left with Back) must not leave the managers half-started, or let the next one start a
        // second queue worker.
        var appStart: Deferred<Unit>? = null
    }

    // --- DELEGATION OF STATE FROM FORGE REPOSITORY ---
    val config: StateFlow<AppConfig> = ForgeRepository.config
    val client: OkHttpClient get() = ForgeRepository.client
    val appState: StateFlow<AppState> = ForgeRepository.appState
    val promptHistory: StateFlow<List<PromptHistoryItem>> = ForgeRepository.promptHistory
    val wildcards: StateFlow<List<WildcardEntity>> = ForgePromptManager.wildcards

    val activeLoras: StateFlow<List<ActiveLora>> = ForgeRepository.activeLoras

    val isConnected: StateFlow<Boolean> = ForgeRepository.isConnected
    val pingMs: StateFlow<Long> = ForgeRepository.pingMs
    val connection: StateFlow<ServerConnection> = ForgeRepository.connection
    val searchEndsAt: StateFlow<Long> = ForgeRepository.searchEndsAt

    /** Another minute of tries to reach the server ("Retry", the network is back). */
    fun reconnect() = ForgeRepository.reconnect()

    val isServerBusy: StateFlow<Boolean> = ForgeRepository.isServerBusy
    val vramUsage: StateFlow<String?> = ForgeRepository.vramUsage

    // --- DELEGATION OF STATE FROM FORGE NETWORK MANAGER ---
    val selectedModel: StateFlow<String> = networkManager.selectedModel
    val samplers: StateFlow<List<String>> = networkManager.samplers
    val schedulers: StateFlow<List<String>> = networkManager.schedulers
    val models: StateFlow<List<ApiResource>> = networkManager.models
    val upscalers: StateFlow<List<String>> = networkManager.upscalers
    val availableLoras: StateFlow<List<ApiResource>> = networkManager.availableLoras


    // --- DELEGATION OF STATE FROM FORGE GALLERY MANAGER ---
    val displayedFiles: StateFlow<List<GalleryItem>> = ForgeGalleryManager.displayedFiles // NEW (optimized filtering)
    val currentGalleryPath: StateFlow<String> = ForgeGalleryManager.currentGalleryPath
    val isGalleryLoading: StateFlow<Boolean> = ForgeGalleryManager.isGalleryLoading
    val isGallerySyncing: StateFlow<IndicatorState> = ForgeGalleryManager.isGallerySyncing
    val gallerySyncCurrentFile: StateFlow<String> = ForgeGalleryManager.gallerySyncCurrentFile
    val gallerySyncProgress: StateFlow<Pair<Int, Int>> = ForgeGalleryManager.gallerySyncProgress
    val galleryError: StateFlow<String?> = ForgeGalleryManager.galleryError
    val showGalleryMetadata: StateFlow<Boolean> = ForgeGalleryManager.showGalleryMetadata
    val currentImageMetadata: StateFlow<String?> = ForgeGalleryManager.currentImageMetadata
    val galleryMode: StateFlow<GalleryMode> = ForgeGalleryManager.galleryMode

    val isGalleryIndexing: StateFlow<Boolean> = ForgeGalleryManager.isIndexing
    val galleryIndexedImageCount: StateFlow<Int> = ForgeGalleryManager.indexedImageCount
    val favoritePaths: StateFlow<Set<String>> = ForgeGalleryManager.favoritePaths
    val isRestoringPrompt: StateFlow<IndicatorState> = ForgeGalleryManager.isRestoringPrompt

    // --- DELEGATION OF STATE FROM FORGE QUEUE MANAGER ---
    val progress: StateFlow<Float> = ForgeQueueManager.progress
    val currentEta: StateFlow<Double> = ForgeQueueManager.currentEta
    val isGenerating: StateFlow<Boolean> = ForgeQueueManager.isGenerating
    val generationQueue: StateFlow<List<QueuedGeneration>> = ForgeQueueManager.generationQueue
    val isQueuePaused: StateFlow<Boolean> = ForgeQueueManager.isQueuePaused
    val isQueueActive: StateFlow<Boolean> = ForgeQueueManager.isQueueActive
    val oomAlert: StateFlow<Boolean> = ForgeQueueManager.oomAlert
    val queuePauseReason: StateFlow<String?> = ForgeQueueManager.queuePauseReason
    val scheduledStart: StateFlow<Long?> = ForgeQueueManager.scheduledStart
    val isWaitingForSchedule: StateFlow<Boolean> = ForgeQueueManager.isWaitingForSchedule
    val queueSecondsLeft: StateFlow<Long?> = ForgeQueueManager.queueSecondsLeft

    /** "Start at" [hour]:[minute]: today, or tomorrow when that time has passed. */
    fun scheduleQueueStart(
        hour: Int,
        minute: Int,
    ) = ForgeQueueManager.scheduleStart(QueueSchedule.nextOccurrence(hour, minute))

    fun startScheduledQueueNow() = ForgeQueueManager.startScheduledQueueNow()
    val totalQueueSize: StateFlow<Int> = ForgeQueueManager.totalQueueSize
    val completedQueueItems: StateFlow<Int> = ForgeQueueManager.completedQueueItems
    val sessionImages: StateFlow<List<String>> = ForgeQueueManager.sessionImages
    val currentSessionIndex: StateFlow<Int> = ForgeQueueManager.currentSessionIndex
    val livePreviewImage: StateFlow<LivePreview?> = ForgeQueueManager.livePreviewImage
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

    // Gallery Filters Delegation
    val galleryFilters: StateFlow<ForgeGalleryManager.GalleryFilters> = ForgeGalleryManager.galleryFilters
    val availableModels: StateFlow<List<String>> = ForgeGalleryManager.availableModels
    val galleryAvailableLoras: StateFlow<List<String>> = ForgeGalleryManager.availableLoras

    fun applyGalleryFilters(filters: ForgeGalleryManager.GalleryFilters) = ForgeGalleryManager.applyFilters(filters)
    fun clearGalleryFilters() = ForgeGalleryManager.clearFilters()
    fun cancelPromptRestore() = ForgeGalleryManager.cancelPromptRestore()

    // --- DELEGATION OF ACTIONS TO REPOSITORY ---

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

    fun fetchAutoConfig() = ForgeRepository.fetchAutoConfig(networkManager.galleryApiPrefix.value)

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

    fun wipeSettings() {
        ForgeSettingsManager.resetSettings()
        DebugMode.lock()
    }

    fun wipePresets() = ForgeSettingsManager.saveConfig(ForgeSettingsManager.config.value.copy(presets = emptyList()))

    fun wipeServerProfiles() =
        ForgeSettingsManager.saveConfig(
            ForgeSettingsManager.config.value.copy(serverProfiles = AppConfig().serverProfiles),
        )

    fun wipePromptHistory() = ForgeSettingsManager.clearPromptHistory()

    fun wipeWildcards() = ForgePromptManager.deleteAllWildcards()

    // --- DELEGATION OF ACTIONS TO QUEUE MANAGER ---
    fun resumeQueue() = ForgeQueueManager.resumeQueue()

    fun retryFailed(id: String? = null) = ForgeQueueManager.retryFailed(id)

    fun removeFailedJobs() = ForgeQueueManager.removeFailedJobs()

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

    fun toggleFavorite(item: GalleryItem) = ForgeGalleryManager.toggleFavorite(item)

    fun shareImage(
        item: GalleryItem,
        startActivity: (Intent) -> Unit,
    ) = ForgeGalleryManager.shareImage(item, startActivity)

    fun toggleGalleryMetadata() = ForgeGalleryManager.toggleGalleryMetadata()

    fun downloadImage(item: GalleryItem) = ForgeGalleryManager.downloadImage(item)

    fun getGalleryImageUrl(item: GalleryItem): String = ForgeGalleryManager.getGalleryImageUrl(item)

    fun getGalleryThumbnailUrl(item: GalleryItem): String = ForgeGalleryManager.getGalleryThumbnailUrl(item)

    fun autoSyncGallery() = ForgeGalleryManager.autoSyncGallery()

    fun setAutoSaveMode(mode: String) = ForgeGalleryManager.setAutoSaveMode(mode)

    fun recoverPromptFromImage(item: GalleryItem) = ForgeGalleryManager.recoverPromptFromImage(item)

    fun fetchGalleryFolder(path: String) = ForgeGalleryManager.fetchGalleryFolder(path)

    fun triggerManualGallerySync() = ForgeGalleryManager.triggerManualGallerySync()

    suspend fun extractMetadataFromUri(uri: android.net.Uri): String? = ForgeGalleryManager.extractMetadataFromUri(uri)

    fun setImportedImageMetadata(data: String?) {
        _importedImageMetadata.value = data
    }

    fun setGalleryMode(mode: GalleryMode) = ForgeGalleryManager.setGalleryMode(mode)

    fun addLora(name: String) = ForgeRepository.addLora(name)

    fun removeLora(name: String) = ForgeRepository.removeLora(name)

    fun updateLoraStrength(
        name: String,
        strength: Float,
    ) = ForgeRepository.updateLoraStrength(name, strength)

    fun recoverLastSeed() = ForgeGalleryManager.recoverLastSeed()

    fun recoverLastPrompt() = ForgeGalleryManager.recoverLastPrompt()

    fun changeCheckpoint(modelTitle: String) = networkManager.changeCheckpoint(modelTitle)


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
}
