
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
    // The changelog of the versions installed since the app was last opened. After an update a floating bar offers it
    // once (3.0.0): the version counts as seen when the bar shows, and "Show" opens the notes.
    private val _whatsNew = MutableStateFlow<String?>(null)
    val whatsNew: StateFlow<String?> = _whatsNew.asStateFlow()
    private val _whatsNewBar = MutableStateFlow(false)
    val whatsNewBar: StateFlow<Boolean> = _whatsNewBar.asStateFlow()
    private val _whatsNewOpen = MutableStateFlow(false)
    val whatsNewOpen: StateFlow<Boolean> = _whatsNewOpen.asStateFlow()

    private val installedVersion get() = BuildConfig.VERSION_NAME.removeSuffix("-DEBUG")

    /** The version the bar names ("What's new in 3.0.0"). */
    val whatsNewVersion: String get() = installedVersion

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
                _whatsNewBar.value = true
            } else {
                // A fresh install: nothing to show now, but the next update shows what is new since this version.
                settings.putSetting(AppSettingEntity(WhatsNew.LAST_SEEN_KEY, installedVersion))
            }
        } catch (e: Exception) {
            Log.w("ForgeViewModel", "Cannot read the changelog", e)
        }
    }

    private fun rememberWhatsNewSeen() {
        viewModelScope.launch(Dispatchers.IO) {
            ForgeRepository.db.appSettingDao().putSetting(AppSettingEntity(WhatsNew.LAST_SEEN_KEY, installedVersion))
        }
    }

    /** The bar is on screen: it shows once, so the version is seen now. */
    fun markWhatsNewSeen() = rememberWhatsNewSeen()

    /** "Show" on the bar: the notes open and the bar leaves. */
    fun openWhatsNew() {
        _whatsNewOpen.value = true
        _whatsNewBar.value = false
    }

    /** The bar's 30 seconds are up (or it was closed) without the notes being opened. */
    fun hideWhatsNewBar() {
        _whatsNewBar.value = false
        if (!_whatsNewOpen.value) _whatsNew.value = null
    }

    fun dismissWhatsNew() {
        _whatsNewOpen.value = false
        _whatsNewBar.value = false
        _whatsNew.value = null
        rememberWhatsNewSeen()
    }

    // --- DEBUG MODE (DebugMode) ---
    val debugUnlocked: StateFlow<Boolean> = DebugMode.unlocked
    val debugForceLiveUpdates: StateFlow<Boolean> = DebugMode.forceLiveUpdates

    /** Checks the password off the main thread (PBKDF2 is slow on purpose). */
    suspend fun debugUnlock(password: String): DebugMode.UnlockResult = withContext(Dispatchers.Default) { DebugMode.unlock(password) }

    fun debugLock() = DebugMode.lock()

    fun debugSetForceLiveUpdates(on: Boolean) = DebugMode.setForceLiveUpdates(on)


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

    /** Shows the "What's New" bar of the installed version again. */
    fun debugShowWhatsNew() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val assets = getApplication<Application>().assets
                val changelog = assets.open(WhatsNew.CHANGELOG_ASSET).bufferedReader().use { it.readText() }
                _whatsNew.value = WhatsNew.notesFor(changelog, installedVersion, null, wasUpdated = true)
                    ?: "No notes for $installedVersion in the changelog."
                _whatsNewOpen.value = false
                _whatsNewBar.value = true
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
        JobRecorder.models = networkManager.models

        updateManager =
            ForgeUpdateManager(
                application = getApplication(),
                gitHubApi = GitHubApi.create(),
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
        ForgeTagManager.start(app) // the saved tag list is read in the background
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

    // Tag suggestions (2.4.2): the server's tag list, its state and how the server writes tags.
    val tagList: StateFlow<TagList?> = ForgeTagManager.tags
    val tagListStatus: StateFlow<ForgeTagManager.Status> = ForgeTagManager.status
    val tagInsertRules: StateFlow<TagInsertRules> = ForgeTagManager.rules

    fun reloadTagList() = ForgeTagManager.reload()

    val activeLoras: StateFlow<List<ActiveLora>> = ForgeRepository.activeLoras

    val isConnected: StateFlow<Boolean> = ForgeRepository.isConnected
    val pingMs: StateFlow<Long> = ForgeRepository.pingMs
    val connection: StateFlow<ServerConnection> = ForgeRepository.connection
    val searchEndsAt: StateFlow<Long> = ForgeRepository.searchEndsAt

    // The server dialog asked for from the connection status (the app opens it by itself when it goes offline).
    private val _serverDialogRequested = MutableStateFlow(false)
    val serverDialogRequested: StateFlow<Boolean> = _serverDialogRequested.asStateFlow()

    fun openServerDialog() {
        _serverDialogRequested.value = true
    }

    fun closeServerDialog() {
        _serverDialogRequested.value = false
    }

    /** Another minute of tries to reach the server ("Retry", the network is back). */
    fun reconnect() = ForgeRepository.reconnect()

    /** Uses [address] (a changed one is saved, which restarts the pings) and tries again at once. */
    fun connectTo(address: String) {
        val current = ForgeSettingsManager.config.value
        val clean = address.trim()
        if (clean.isNotEmpty() && clean != current.apiUrl) ForgeSettingsManager.saveConfig(current.copy(apiUrl = clean))
        ForgeRepository.reconnect()
    }

    /** One question to the server at [address] (the connection dialog's Test): how long the answer took, or why none came. */
    suspend fun testServer(address: String): String =
        withContext(Dispatchers.IO) {
            val clean = address.trim().trimEnd('/').let { if (it.startsWith("http://") || it.startsWith("https://")) it else "http://$it" }
            val request =
                try {
                    okhttp3.Request.Builder().url("$clean/sdapi/v1/progress?skip_current_image=true").build()
                } catch (e: IllegalArgumentException) {
                    return@withContext "Not a valid address"
                }
            val start = System.currentTimeMillis()
            try {
                client.newCall(request).awaitResponse().use { response ->
                    if (response.isSuccessful) {
                        "Answered in ${System.currentTimeMillis() - start} ms"
                    } else {
                        "The server answered HTTP ${response.code}"
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                "No answer (${e.message ?: e.javaClass.simpleName})"
            }
        }

    val isServerBusy: StateFlow<Boolean> = ForgeRepository.isServerBusy
    val serverMemory: StateFlow<ServerMemory?> = ForgeRepository.serverMemory

    /** Reads the server's RAM and VRAM now (the Server Memory panel; the ping reads it only while the meters show). */
    fun readServerMemory() {
        viewModelScope.launch(Dispatchers.IO) { ForgeRepository.refreshServerMemory() }
    }

    /** Whether the main screen's live preview can be seen now: the server sends it only then (3.4.0). */
    fun setPreviewShown(shown: Boolean) = ForgeQueueManager.setPreviewShown(shown)

    // --- DELEGATION OF STATE FROM FORGE NETWORK MANAGER ---
    val selectedModel: StateFlow<String> = networkManager.selectedModel
    val samplers: StateFlow<List<String>> = networkManager.samplers
    val schedulers: StateFlow<List<String>> = networkManager.schedulers
    val models: StateFlow<List<ApiResource>> = networkManager.models
    val upscalers: StateFlow<List<String>> = networkManager.upscalers
    val latentModes: StateFlow<List<String>> = networkManager.latentModes
    val availableLoras: StateFlow<List<ApiResource>> = networkManager.availableLoras

    // --- LORA METADATA, EMBEDDINGS AND THE SERVER'S STYLES (3.1.0) ---
    val loraInfo: StateFlow<LoraInfoIndex> = networkManager.loraInfo
    val embeddings: StateFlow<EmbeddingList> = networkManager.embeddings
    val promptStyles: StateFlow<List<PromptStyle>> = networkManager.promptStyles

    /** [tags] added to the positive (or the [negative]) prompt, those it does not have yet (a trigger word, an embedding). */
    fun addPromptTags(
        tags: List<String>,
        negative: Boolean = false,
    ) = updateState {
        if (negative) {
            it.copy(negativePrompt = PromptEdits.addTags(it.negativePrompt, tags))
        } else {
            it.copy(positivePrompt = PromptEdits.addTags(it.positivePrompt, tags))
        }
    }

    fun refreshEmbeddings() = networkManager.refreshEmbeddings { showToast(it) }

    fun refreshPromptStyles() = networkManager.refreshPromptStyles { showToast(it) }

    fun setStyles(names: List<String>) = updateState { it.copy(styles = names) }

    /** The chosen styles written into the prompts, none chosen any more ("Paste into Prompt"). */
    fun pasteStyles() = updateState { PromptStyles.pasteInto(it, promptStyles.value) }


    // --- DELEGATION OF STATE FROM FORGE GALLERY MANAGER ---
    // The three gallery tabs (2.2.0): the open folder (or the search's results), the favorites, every image.
    val galleryFolder: StateFlow<ForgeGalleryManager.FolderView> = ForgeGalleryManager.folderView
    val favoriteImages: StateFlow<ForgeGalleryManager.ImagesView> = ForgeGalleryManager.favoriteImages
    val allImages: StateFlow<ForgeGalleryManager.ImagesView> = ForgeGalleryManager.allImages
    val galleryTab: StateFlow<GalleryTab> = ForgeGalleryManager.tab

    fun selectGalleryTab(tab: GalleryTab) = ForgeGalleryManager.selectTab(tab)

    // "Upscale Selected" and "More Like This": gallery images remade as queue jobs (2.4.0).
    val imageJobs: StateFlow<ForgeGalleryManager.ImageJobsRequest?> = ForgeGalleryManager.imageJobs

    fun requestImageJobs(
        kind: ImageJobs.Kind,
        items: List<GalleryItem>,
    ) = ForgeGalleryManager.requestImageJobs(kind, items)

    fun dismissImageJobs() = ForgeGalleryManager.dismissImageJobs()

    fun queueUpscales(
        scale: Float,
        upscaler: String,
        denoising: Float,
    ) = ForgeGalleryManager.queueUpscales(scale, upscaler, denoising)

    fun queueMoreLikeThis(
        similar: Boolean,
        count: Int,
        strength: Float,
    ) = ForgeGalleryManager.queueMoreLikeThis(similar, count, strength)

    fun setImageJobsKind(kind: ImageJobs.Kind) = ForgeGalleryManager.setImageJobsKind(kind)

    fun queueVariance(spec: ImageJobs.VarianceSpec) = ForgeGalleryManager.queueVariance(spec)

    // --- The gallery's files, folder covers, favorites gone, Random and statistics (3.2.0) ---
    val galleryPendingDelete: StateFlow<ForgeGalleryManager.PendingDelete?> = ForgeGalleryManager.pendingDelete
    val galleryFolderCovers: StateFlow<Map<String, List<GalleryItem>>> = ForgeGalleryManager.folderCovers
    val galleryFolderImageCounts: StateFlow<Map<String, Int>> = ForgeGalleryManager.folderImageCounts
    val missingFavorites: StateFlow<Set<String>> = ForgeGalleryManager.missingFavorites
    val galleryLastFolder: StateFlow<String?> = ForgeGalleryManager.lastFolder
    val allImagesOrder: StateFlow<AllImagesOrder> = ForgeGalleryManager.allImagesOrder

    fun deleteGalleryImages(items: List<GalleryItem>) = ForgeGalleryManager.deleteImages(items)

    fun undoGalleryDelete() = ForgeGalleryManager.undoDelete()

    fun transferGalleryImages(
        items: List<GalleryItem>,
        dest: String,
        kind: ForgeGalleryManager.Transfer,
    ) = ForgeGalleryManager.transferImages(items, dest, kind)

    suspend fun gallerySubfolders(path: String): List<GalleryItem> = ForgeGalleryManager.subfolders(path)

    suspend fun createGalleryFolder(
        parent: String,
        name: String,
    ): Result<String> = ForgeGalleryManager.createFolder(parent, name)

    fun galleryFolderName(path: String): String = ForgeGalleryManager.folderName(path)

    fun downloadGalleryZip(items: List<GalleryItem>) = ForgeGalleryManager.downloadZip(items)

    fun checkFavorites() = ForgeGalleryManager.checkFavorites()

    fun removeMissingFavorites() = ForgeGalleryManager.removeMissingFavorites()

    fun setAllImagesRandom(random: Boolean) = ForgeGalleryManager.setAllImagesRandom(random)

    suspend fun galleryStatistics(): GalleryStats = ForgeGalleryManager.statistics()

    // --- STATISTICS (3.6.0) ---

    /** Images whose details are still being read (the Gallery tab of the statistics shows it while not 0). */
    val galleryDetailsBacklog: StateFlow<Int> = ForgeGalleryManager.detailsBacklog

    /** Goes up with every job recorded, so the Generation tab reads the history again. */
    val jobsRecorded: StateFlow<Int> = JobRecorder.recorded

    suspend fun generationStatistics(): GenerationStats =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            GenerationStatistics.compute(ForgeRepository.db.jobRunDao().getAllWithoutCurves())
        }

    /** One recorded job with its VRAM readings (the job's details). */
    suspend fun jobRun(id: String): JobRunEntity? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ForgeRepository.db.jobRunDao().get(id) }

    /** The gallery's All Images with the images of a statistics row (3.6.0). */
    fun openGalleryFrom(target: StatTarget) {
        ForgeGalleryManager.applyFilters(StatTarget.filters(target, ForgeGalleryManager.galleryFilters.value))
        ForgeGalleryManager.selectTab(GalleryTab.ALL_IMAGES)
    }

    fun galleryBreadcrumb(path: String): List<Pair<String, String>> = ForgeGalleryManager.breadcrumb(path)

    fun galleryParentFolder(path: String): String? = ForgeGalleryManager.parentFolder(path)

    /** Where each gallery tab, and each folder of the Gallery tab, was scrolled to; kept while the app runs. */
    val galleryScroll = HashMap<String, GalleryScrollPosition>()
    val currentGalleryPath: StateFlow<String> = ForgeGalleryManager.currentGalleryPath
    val isGalleryLoading: StateFlow<Boolean> = ForgeGalleryManager.isGalleryLoading
    val gallerySyncProgress: StateFlow<Pair<Int, Int>> = ForgeGalleryManager.gallerySyncProgress
    val galleryError: StateFlow<String?> = ForgeGalleryManager.galleryError
    val showGalleryMetadata: StateFlow<Boolean> = ForgeGalleryManager.showGalleryMetadata
    val currentImageMetadata: StateFlow<String?> = ForgeGalleryManager.currentImageMetadata
    val galleryMode: StateFlow<GalleryMode> = ForgeGalleryManager.galleryMode

    val isGalleryIndexing: StateFlow<Boolean> = ForgeGalleryManager.isIndexing
    val galleryIndexError: StateFlow<String?> = ForgeGalleryManager.indexError
    val galleryExtension: StateFlow<ForgeGalleryManager.ExtensionStatus> = ForgeGalleryManager.extension
    val galleryIndexedImageCount: StateFlow<Int> = ForgeGalleryManager.indexedImageCount
    val galleryIndexLoaded: StateFlow<Boolean> = ForgeGalleryManager.indexLoaded
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
    val queueJobEnds: StateFlow<List<Double?>> = ForgeQueueManager.queueJobEnds

    /** The queue's timeline with the model changes it expects (3.6.0). */
    val queueTimeline: StateFlow<QueueEstimate.Timeline> = ForgeQueueManager.queueTimeline

    /** Group by Model, while it spares model changes and was not put off with "Not Now" (3.6.0). */
    val groupingSuggestion: StateFlow<QueueGrouping.Plan?> = ForgeQueueManager.groupingSuggestion

    fun groupQueueByModel(): List<String>? = ForgeQueueManager.groupByModel()

    fun restoreQueueOrder(order: List<String>) = ForgeQueueManager.restoreQueueOrder(order)

    fun dismissGrouping() = ForgeQueueManager.dismissGrouping()

    /** Unload After the Queue (3.6.0); switched off, an alarm already set is taken back. */
    fun setUnloadAfterQueue(choice: Int) {
        saveConfig(config.value.copy(unloadAfterQueue = choice))
        if (choice == AutoUnload.OFF) AutoUnload.cancel(getApplication<Application>())
    }

    /** What a cold start of [model] adds, from the job history (Unload After the Queue says it); null while not known. */
    fun coldStartMs(model: String): Long? = ForgeQueueManager.coldStartMs(model)

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
    val batchFinished: SharedFlow<Unit> = ForgeQueueManager.batchFinished
    val isShowingGridPreview: StateFlow<Boolean> = ForgeQueueManager.isShowingGridPreview
    val currentBatchStartIndex: StateFlow<Int> = ForgeQueueManager.currentBatchStartIndex
    val currentBatchEndIndex: StateFlow<Int> = ForgeQueueManager.currentBatchEndIndex

    // --- DELEGATION OF STATE FROM FORGE UPDATE MANAGER ---
    val updateManifest: StateFlow<UpdateManifest?> = updateManager.updateManifest
    val updateDownload: StateFlow<SelfUpdate.DownloadProgress?> = updateManager.updateDownload

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

    fun previewCandidates(
        originalPath: String,
        isLora: Boolean = false,
    ) = ForgeRepository.previewCandidates(originalPath, isLora)

    fun addServerProfile(
        name: String,
        url: String,
    ) = ForgeSettingsManager.addServerProfile(name, url)

    fun removeServerProfile(name: String) = ForgeSettingsManager.removeServerProfile(name)

    fun wipeGalleryIndex() {
        ForgeGalleryManager.clearDatabase()
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

    /** How many jobs the generation history holds (3.6.0, for the wipe choice). */
    suspend fun jobHistoryCount(): Int = withContext(Dispatchers.IO) { ForgeRepository.db.jobRunDao().count() }

    /** Wipes the generation history (3.6.0): the statistics, the queue's model changes and the widgets read it again. */
    fun wipeGenerationHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            ForgeRepository.db.jobRunDao().clearAll()
            JobRecorder.historyChanged()
        }
    }

    // --- DELEGATION OF ACTIONS TO QUEUE MANAGER ---
    fun resumeQueue() = ForgeQueueManager.resumeQueue()

    fun retryFailed(id: String? = null) = ForgeQueueManager.retryFailed(id)

    fun removeFailedJobs() = ForgeQueueManager.removeFailedJobs()

    fun interruptGeneration() = ForgeQueueManager.interruptGeneration()

    // --- The server's queue, the server page and restarting Forge (3.3.0) ---

    /** Jobs the server does before the running one (from its web UI or another app); 0 when it is doing ours. */
    val serverJobsAhead: StateFlow<Int> = ForgeQueueManager.serverJobsAhead
    val restartingSince: StateFlow<Long> = ForgeRepository.restartingSince
    val serverInfo: StateFlow<ServerInfo?> = ForgeRepository.serverInfo

    fun skipImage() = ForgeQueueManager.skipImage()

    fun loadServerInfo(again: Boolean = false) = ForgeRepository.loadServerInfo(again)

    /** Null when Forge is restarting; else why it cannot (shown to the user). */
    suspend fun restartServer(): String? = ForgeRepository.restartServer()

    /** A share sheet for the last checked report (for a bug report), named by the check's time; null without one. */
    fun serverReportIntent(): android.content.Intent? {
        val check = lastServerCheck.value ?: return null
        val text = check.report ?: return null
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd-HH-mm", java.util.Locale.US).format(java.util.Date(check.checkedAt))
        return DeviceImages.shareTextIntent(getApplication(), "sysinfo-$stamp.json", text)
    }

    // --- CHECK NOW (3.6.0) ---

    val lastServerCheck: StateFlow<ServerCheck?> = ForgeRepository.serverCheck
    val checkingSince: StateFlow<Long> = ForgeRepository.checkingSince
    val checkProblem: StateFlow<String?> = ForgeRepository.checkProblem

    fun loadServerCheck() = ForgeRepository.loadServerCheck()

    fun checkServer() = ForgeRepository.checkServer()

    fun queueGeneration() = ForgeQueueManager.queueGeneration()

    fun updateQueueItem(
        id: String,
        positivePrompt: String,
        negativePrompt: String,
    ) = ForgeQueueManager.updateQueueItem(id, positivePrompt, negativePrompt)

    fun clearQueue() = ForgeQueueManager.clearQueue()

    fun removeFromQueue(id: String) = ForgeQueueManager.removeFromQueue(id)

    fun restoreJobs(removed: ForgeQueueManager.RemovedJobs) = ForgeQueueManager.restoreJobs(removed)

    fun duplicateJob(
        id: String,
        newSeed: Boolean,
    ) = ForgeQueueManager.duplicateJob(id, newSeed)

    fun moveQueueItem(
        id: String,
        toIndex: Int,
    ) = ForgeQueueManager.moveQueueItem(id, toIndex)

    fun moveQueueItemUp(id: String) = ForgeQueueManager.moveQueueItemUp(id)

    // --- BACKUP (settings, presets, server profiles, wildcards, and since 3.5.2-1 favorites and the queue, in one file) ---

    fun exportBackup(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val json =
                    Backup.write(
                        ForgeSettingsManager.config.value,
                        ForgePromptManager.wildcards.value,
                        AppVersion.currentVersion,
                        favorites = ForgeGalleryManager.favoritesForBackup(),
                        queue = ForgeQueueManager.jobsForBackup(),
                        jobs = ForgeRepository.db.jobRunDao().getAll(),
                    )
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray()) }
                    ?: throw java.io.IOException("The file cannot be written")
                showToast("Settings exported")
            } catch (e: Exception) {
                showToast("Export failed: ${e.message}")
            }
        }
    }

    fun importBackup(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val json =
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                        ?: throw java.io.IOException("The file cannot be read")
                val backup = Backup.read(json)
                if (backup == null) {
                    showToast("This is not a ForgeGen backup")
                    return@launch
                }
                val current = ForgeSettingsManager.config.value
                withContext(Dispatchers.Main) {
                    ForgeSettingsManager.saveConfig(
                        backup.config.copy(lastUpdateCheckDate = current.lastUpdateCheckDate, galleryKeys = current.galleryKeys),
                    )
                }
                ForgePromptManager.saveWildcards(backup.wildcards)
                val favorites = ForgeGalleryManager.importFavorites(backup.favorites)
                val jobs = ForgeQueueManager.importJobs(backup.queue)
                // The generation history (3.6.0): the jobs this phone does not have yet.
                val history =
                    ForgeRepository.db
                        .jobRunDao()
                        .insertMissing(backup.jobs)
                        .count { it != -1L }
                if (history > 0) JobRecorder.historyChanged()
                showToast(
                    "Settings imported: ${backup.config.presets.size} presets, ${backup.config.serverProfiles.size} server profiles, " +
                        "${backup.wildcards.size} wildcards" +
                        (if (favorites > 0) ", $favorites favorites" else "") +
                        (if (jobs > 0) ", $jobs queued jobs (the queue is paused)" else "") +
                        (if (history > 0) ", $history jobs of the generation history" else ""),
                )
            } catch (e: Exception) {
                showToast("Import failed: ${e.message}")
            }
        }
    }

    fun downloadImages(items: List<GalleryItem>) = ForgeGalleryManager.downloadImages(items)

    fun addFavorites(items: List<GalleryItem>) = ForgeGalleryManager.addFavorites(items)

    fun shareImages(
        items: List<GalleryItem>,
        onIntentReady: (Intent) -> Unit,
    ) = ForgeGalleryManager.shareImages(items, onIntentReady)

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

    // The downloaded, checked update; the one being installed; the system's confirmation waiting (3.0.0-3).
    val readyUpdate: StateFlow<SelfUpdate.ReadyUpdate?> = SelfUpdate.readyUpdate
    val installingUpdate: StateFlow<String?> = SelfUpdate.installing
    val updateConfirm: StateFlow<android.content.Intent?> = SelfUpdate.pendingConfirm

    fun installUpdate(sendToBackground: () -> Unit) = updateManager.installUpdate(sendToBackground)

    // --- REFRESH, VRAM & PNG INFO ACTIONS ---

    /** Refresh in the VAE and text encoder lists (3.0.1). */
    fun refreshModules() = networkManager.refreshModules { showToast(it) }

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

    /** The gallery screen shows (3.4.0): new images are indexed at once only then. */
    fun setGalleryVisible(visible: Boolean) = ForgeGalleryManager.setGalleryVisible(visible)

    fun setAutoSaveMode(mode: String) = ForgeGalleryManager.setAutoSaveMode(mode)

    fun recoverPromptFromImage(item: GalleryItem) = ForgeGalleryManager.recoverPromptFromImage(item)

    fun fetchGalleryFolder(path: String) = ForgeGalleryManager.fetchGalleryFolder(path)

    /** Opens the gallery at its top folder (or shows why it cannot: no gallery extension on the server). */
    fun openGallery(mode: GalleryMode) = ForgeGalleryManager.openGallery(mode)

    fun refreshGallery() = ForgeGalleryManager.refreshGallery()

    /** "Check Again" in the gallery: asks the server for its gallery extension, or first tries to reach the server. */
    fun checkGalleryExtension() {
        if (ForgeRepository.isConnected.value) ForgeGalleryManager.checkExtension() else ForgeRepository.reconnect()
    }

    /** "Unlock" in the gallery or "Save" in Settings > Server > Gallery Key (3.5.0); [onResult]: the server took it. */
    fun saveGalleryKey(
        key: String,
        onResult: (Boolean) -> Unit,
    ) {
        viewModelScope.launch { onResult(ForgeGalleryManager.tryKey(key)) }
    }

    fun forgetGalleryKey() = ForgeGalleryManager.forgetKey()

    fun readyGalleryRoot(): String? = ForgeGalleryManager.readyRoot()

    suspend fun galleryPositivePrompt(path: String): String = ForgeGalleryManager.positivePrompt(path)

    suspend fun extractMetadataFromUri(uri: android.net.Uri): String? = ForgeGalleryManager.extractMetadataFromUri(uri)

    fun setImportedImageMetadata(data: String?) {
        _importedImageMetadata.value = data
    }


    fun addLora(name: String) = ForgeRepository.addLora(name)

    fun removeLora(name: String) = ForgeRepository.removeLora(name)

    fun updateLoraStrength(
        name: String,
        strength: Float,
    ) = ForgeRepository.updateLoraStrength(name, strength)

    fun recoverLastSeed() = ForgeGalleryManager.recoverLastSeed()

    fun recoverLastPrompt() = ForgeGalleryManager.recoverLastPrompt()

    fun changeCheckpoint(modelTitle: String) {
        networkManager.changeCheckpoint(modelTitle)
        // The model's own defaults, only when the user ticked them for it (3.0.0, owner's idea 6).
        val settings = ModelSettingsRules.of(config.value.modelSettings, modelTitle)
        val defaults = settings.defaults
        if (settings.useDefaults && defaults != null) {
            updateState { ModelSettingsRules.applyDefaults(it, defaults) }
            showToast("${ModelSettingsRules.key(modelTitle)}: defaults applied")
        }
    }

    // Model settings (3.0.0): what each checkpoint is, its modules and its own defaults.
    val serverModules: StateFlow<List<ServerModule>> = ForgeModelManager.modules
    val moduleSupport: StateFlow<ModuleSupport> = ForgeModelManager.moduleSupport

    fun updateModelSettings(
        model: String,
        transform: (ModelSettings) -> ModelSettings,
    ) {
        val current = config.value
        val key = ModelSettingsRules.key(model)
        val updated = transform(current.modelSettings[key] ?: ModelSettings())
        saveConfig(current.copy(modelSettings = current.modelSettings + (key to updated)))
    }

    /**
     * The main screen's size, steps, CFG, sampler, schedule and clip skip become [model]'s defaults; they are used only
     * when the user switches them on ([turnOn]: the switch itself asked for them).
     */
    fun saveModelDefaults(
        model: String,
        turnOn: Boolean = false,
    ) = updateModelSettings(model) {
        it.copy(defaults = ModelSettingsRules.defaultsOf(appState.value), useDefaults = it.useDefaults || turnOn)
    }


    fun appendLora(loraName: String) = ForgeRepository.appendLora(loraName)

    fun updateState(transform: (AppState) -> AppState) = ForgeSettingsManager.updateState(transform)

    fun deleteWildcard(wildcard: WildcardEntity) = ForgePromptManager.deleteWildcard(wildcard.name)

    // Unload Model in the Server Memory panel is running (its button waits).
    private val _unloadingModel = MutableStateFlow(false)
    val unloadingModel: StateFlow<Boolean> = _unloadingModel.asStateFlow()

    /**
     * POST /sdapi/v1/unload-checkpoint, as the web UI's button: Forge Neo drops the model from VRAM and RAM, Forge
     * only from VRAM; the next image loads it again. The meters then read the memory at once (3.0.0-4).
     */
    fun unloadCheckpoint() {
        if (_unloadingModel.value) return
        _unloadingModel.value = true
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val response = ForgeRepository.forgeApi?.unloadCheckpoint()
                if (response?.isSuccessful == true) {
                    JobRecorder.modelUnloaded() // the next job starts cold (3.6.0)
                    ForgeRepository.refreshServerMemory()
                    showToast("Model unloaded")
                } else {
                    showToast("Failed to unload model")
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                showToast("Error unloading model")
            } finally {
                _unloadingModel.value = false
            }
        }
    }
}
