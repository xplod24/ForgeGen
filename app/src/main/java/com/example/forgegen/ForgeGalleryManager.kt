package com.example.forgegen

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.util.Log
import com.example.forgegen.ui.components.IndicatorState
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/* ============================================================================
 * GALLERY MANAGER
 * Browses the server's images through the Infinite Image Browsing (IIB) extension, keeps a local index of their
 * generation data (for searching the whole gallery and the "All Images" view), favorites, saving to the phone,
 * sharing, and restoring prompts from images.
 *
 * The gallery needs IIB on the server: when the app connects, detectExtension() looks for it and reads the folders
 * from it (Forge's working folder and its image folder, the gallery's top folder); nobody types them any more.
 *
 * The index is filled without downloading images: IIB reads the generation data on the PC and sends only the
 * text, 100 images per request. It is kept up to date on its own (2.1.0): after the extension is found, when the
 * gallery opens or is refreshed and after the app generated images. Such a sync only lists folders that changed
 * (or are recent); once a day it lists every folder, which also forgets images deleted anywhere.
 * ============================================================================ */
@SuppressLint("StaticFieldLeak")
object ForgeGalleryManager {
    private const val TAG = "ForgeGalleryManager"

    private const val SHOW_META_KEY = "show_gallery_meta"
    private const val FOLDER_DATES_KEY = "gallery_folder_dates"
    private const val FULL_SYNC_AT_KEY = "gallery_full_sync_at"
    private const val THUMBNAIL_SIZE = "512x512" // three columns on a 1440 px wide screen
    private const val INFO_BATCH_SIZE = 100
    private const val MAX_FOLDER_DEPTH = 3
    private const val RECENT_FOLDER_MS = 48 * 60 * 60 * 1000L // re-listed on every sync, as new images land there
    private const val FULL_SYNC_INTERVAL_MS = 24 * 60 * 60 * 1000L
    private const val AUTO_SYNC_INTERVAL_MS = 30_000L
    private const val NEW_IMAGE_SYNC_DELAY_MS = 2_000L
    private const val PROMPT_CACHE_SIZE = 300
    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "avif", "gif")

    /** [GalleryImageEntity.savedAt] of an image whose generation data could not be read yet. */
    private const val INFO_NOT_READ = 0L

    /** Where Forge saves txt2img images unless its settings say otherwise (relative to its working folder). */
    private const val DEFAULT_OUTPUT_FOLDER = "outputs/txt2img-images"

    /** The extension's page, for the message shown when a server does not have it. */
    const val EXTENSION_URL = "https://github.com/zanllp/sd-webui-infinite-image-browsing"

    private lateinit var application: Application
    private lateinit var getDb: () -> ForgeDatabase
    private lateinit var networkManager: ForgeNetworkManager
    private val gson = Gson()
    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** A folder of the gallery as the server listed it: set at once, so the list never shows under another path. */
    private data class FolderListing(
        val path: String,
        val items: List<GalleryItem>,
    )

    private val listing = MutableStateFlow(FolderListing("", emptyList()))

    private val _currentGalleryPath = MutableStateFlow("")
    val currentGalleryPath: StateFlow<String> = _currentGalleryPath.asStateFlow()

    /** The open tab; the gallery opens on the one used last (the prompt picker on All Images). */
    private val _tab = MutableStateFlow(GalleryTab.GALLERY)
    val tab: StateFlow<GalleryTab> = _tab.asStateFlow()

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

    private val _favoritePaths = MutableStateFlow<Set<String>>(emptySet())
    val favoritePaths: StateFlow<Set<String>> = _favoritePaths.asStateFlow()

    // The favorites as images (the Favorites tab), newest starred first like the database keeps them.
    private val favoriteItems = MutableStateFlow<List<GalleryItem>>(emptyList())

    // --- The server's gallery extension ---

    /** Whether the server has the Infinite Image Browsing extension the gallery needs. */
    enum class Extension {
        /** Not asked yet (not connected), or the question got no answer. */
        UNKNOWN,
        CHECKING,

        /** Found, and the gallery's folder was read from it. */
        READY,

        /** The server answered, but has no gallery extension. */
        MISSING,

        /** The extension answered with an error, or without its folders. */
        FAILED,
    }

    data class ExtensionStatus(
        val state: Extension,
        val message: String? = null,
    )

    private val _extension = MutableStateFlow(ExtensionStatus(Extension.UNKNOWN))
    val extension: StateFlow<ExtensionStatus> = _extension.asStateFlow()

    // The extension's URL prefix on the server (older builds of it used other names).
    @Volatile private var apiPrefix = ForgeSettingsManager.GALLERY_PREFIXES.first()

    private val detection = Mutex()

    // --- Index sync state ---

    /** True while the index is being updated (a thin progress bar in the gallery). */
    private val _isIndexing = MutableStateFlow(false)
    val isIndexing: StateFlow<Boolean> = _isIndexing.asStateFlow()

    private val _gallerySyncProgress = MutableStateFlow(0 to 0)
    val gallerySyncProgress: StateFlow<Pair<Int, Int>> = _gallerySyncProgress.asStateFlow()

    /** Why the last update of the index failed; null when it worked. Shown quietly in the search panel. */
    private val _indexError = MutableStateFlow<String?>(null)
    val indexError: StateFlow<String?> = _indexError.asStateFlow()

    /** Updates of the index that finished (tests wait for them). */
    internal val syncsDone = AtomicInteger(0)

    // The index in memory without the prompts (most of its size); a prompt search asks the database.
    private val indexedImages = MutableStateFlow<List<IndexedImage>>(emptyList())

    private val _indexedImageCount = MutableStateFlow(0)
    val indexedImageCount: StateFlow<Int> = _indexedImageCount.asStateFlow()

    internal val _isRestoringPrompt = MutableStateFlow(IndicatorState.IDLE)
    val isRestoringPrompt: StateFlow<IndicatorState> = _isRestoringPrompt.asStateFlow()

    private var restoreJob: Job? = null
    private var folderJob: Job? = null

    // The folder asked for last (shown once it loads): Refresh and Retry ask for it again.
    @Volatile private var requestedPath = ""
    private val folderRequest = AtomicInteger(0)
    private var metadataJob: Job? = null

    private var syncJob: Job? = null

    // Set by a sync asked for while another one runs, read when that one ends.
    @Volatile private var resyncRequested = false

    @Volatile private var lastAutoSyncAt = 0L

    fun cancelPromptRestore() {
        if (_isRestoringPrompt.value == IndicatorState.LOADING) {
            restoreJob?.cancel()
            _isRestoringPrompt.value = IndicatorState.IDLE
        }
    }

    // --- FILTERING ---
    enum class SortOrder { NEWEST, OLDEST, NAME_ASC, NAME_DESC }

    data class GalleryFilters(
        val models: Set<String> = emptySet(),
        val loras: Set<String> = emptySet(),
        val lorasIsAnd: Boolean = false, // false = any of the LoRAs, true = all of them
        val name: String = "",
        val prompt: String = "",
        val sortOrder: SortOrder = SortOrder.NEWEST,
    ) {
        /** A search looks through the whole indexed gallery instead of the open folder. */
        val isSearch: Boolean get() = name.isNotBlank() || prompt.isNotBlank() || models.isNotEmpty() || loras.isNotEmpty()
    }

    private val _galleryFilters = MutableStateFlow(GalleryFilters())
    val galleryFilters: StateFlow<GalleryFilters> = _galleryFilters.asStateFlow()

    private val _availableModels = MutableStateFlow<List<String>>(emptyList())
    val availableModels: StateFlow<List<String>> = _availableModels.asStateFlow()

    private val _availableLoras = MutableStateFlow<List<String>>(emptyList())
    val availableLoras: StateFlow<List<String>> = _availableLoras.asStateFlow()

    /** The search as it was computed: its results (null without a search) and the index under the top folder. */
    private class Search(
        val filters: GalleryFilters,
        val hits: List<IndexedImage>?,
        val inGallery: List<IndexedImage>,
    )

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val search: StateFlow<Search> =
        combine(_galleryFilters, indexedImages) { filters, index -> filters to index }
            .mapLatest { (filters, index) ->
                val root = galleryRoot()?.let { norm(it) }
                val inGallery = if (root == null) index else index.filter { isUnderNormalized(it.fullpath, root) }
                val hits =
                    if (filters.isSearch) {
                        val promptHits = filters.prompt.trim().takeIf { it.isNotEmpty() }?.let { promptMatches(it) }
                        inGallery.filter { matches(it, filters, promptHits) }
                    } else {
                        null
                    }
                Search(filters, hits, inGallery)
            }.stateIn(managerScope, SharingStarted.WhileSubscribed(5000), Search(GalleryFilters(), null, emptyList()))

    /**
     * What the Gallery tab shows: the open folder, or the search's results in the whole gallery. [filters] are the
     * ones it was made with (the screen scrolls a tab back to the top when a list made with other filters arrives).
     */
    data class FolderView(
        val path: String,
        val items: List<GalleryItem>,
        val filters: GalleryFilters,
    ) {
        val isSearch: Boolean get() = filters.isSearch
    }

    /** What the Favorites and All Images tabs show, with the filters it was made with. */
    data class ImagesView(
        val items: List<GalleryItem>,
        val filters: GalleryFilters,
    )

    // A stopped flow keeps its last value (WhileSubscribed keeps the replay), so a reopened gallery shows its lists at
    // once and its tabs return to where they were scrolled.
    val folderView: StateFlow<FolderView> =
        combine(listing, search) { folder, found ->
            val hits = found.hits
            val items = if (hits != null) hits.map { it.toGalleryItem() } else folder.items
            FolderView(folder.path, sortItems(items, found.filters.sortOrder), found.filters)
        }.stateIn(managerScope, SharingStarted.WhileSubscribed(5000), FolderView("", emptyList(), GalleryFilters()))

    /** The Favorites tab: every favorite (the search narrows them), in the sort panel's order. */
    val favoriteImages: StateFlow<ImagesView> =
        combine(favoriteItems, search) { favorites, found ->
            val hits = found.hits?.mapTo(HashSet()) { it.fullpath }
            val shown = if (hits == null) favorites else favorites.filter { it.fullpath in hits }
            ImagesView(sortItems(shown, found.filters.sortOrder), found.filters)
        }.stateIn(managerScope, SharingStarted.WhileSubscribed(5000), ImagesView(emptyList(), GalleryFilters()))

    /** The All Images tab: every image of the gallery (or the search's results), always the newest first. */
    val allImages: StateFlow<ImagesView> =
        search
            .map { found -> ImagesView(sortItems((found.hits ?: found.inGallery).map { it.toGalleryItem() }, SortOrder.NEWEST), found.filters) }
            .stateIn(managerScope, SharingStarted.WhileSubscribed(5000), ImagesView(emptyList(), GalleryFilters()))

    /** Paths of the images whose prompts contain [text] (ignoring case), found by the database. */
    private suspend fun promptMatches(text: String): Set<String> {
        val escaped = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return try {
            getDb().galleryImageDao().findPathsByPrompt("%$escaped%").toHashSet()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Prompt search failed", e)
            emptySet()
        }
    }

    private fun matches(
        image: IndexedImage,
        filters: GalleryFilters,
        promptHits: Set<String>?,
    ): Boolean {
        if (filters.name.isNotBlank() && !image.name.contains(filters.name.trim(), ignoreCase = true)) return false
        if (promptHits != null && image.fullpath !in promptHits) return false
        if (filters.models.isNotEmpty() && image.model !in filters.models) return false
        if (filters.loras.isNotEmpty()) {
            val used = image.loras.split(",").map { it.trim() }
            val ok = if (filters.lorasIsAnd) filters.loras.all { it in used } else filters.loras.any { it in used }
            if (!ok) return false
        }
        return true
    }

    /** Folders first, then images. Dates are "yyyy-MM-dd HH:mm:ss". */
    private fun sortItems(
        items: List<GalleryItem>,
        order: SortOrder,
    ): List<GalleryItem> {
        val byDate = compareBy<GalleryItem>({ it.date.orEmpty() }, { it.name })
        val byName = compareBy<GalleryItem> { it.name.lowercase() }
        val comparator =
            when (order) {
                SortOrder.NEWEST -> byDate.reversed()
                SortOrder.OLDEST -> byDate
                SortOrder.NAME_ASC -> byName
                SortOrder.NAME_DESC -> byName.reversed()
            }
        val (dirs, images) = items.partition { it.isDir }
        return dirs.sortedWith(comparator) + images.sortedWith(comparator)
    }

    fun init(
        app: Application,
        database: () -> ForgeDatabase,
        network: ForgeNetworkManager,
    ) {
        application = app
        getDb = database
        networkManager = network
    }

    /** Returns once the favorites and the index are loaded; the automatic saving to the phone runs from then on. */
    suspend fun start() {
        if (!::getDb.isInitialized) {
            Log.e(TAG, "ForgeGalleryManager start called but getDb is not initialized!")
            return
        }
        withContext(Dispatchers.IO) {
            DeviceImages.clearSharedCopies(application)
            _showGalleryMetadata.value = getDb().appSettingDao().getSetting(SHOW_META_KEY)?.value?.toBoolean() ?: false
            migratePinnedToFavorites()
            loadFavoritePaths()
            reloadIndex()
        }
        // The index follows new images: the server saved them a moment ago (the extension is found on connecting,
        // and that starts a sync too).
        managerScope.launch {
            // The newest image, not the count: the session keeps at most 100 images, so its size stops growing.
            var newest = ForgeQueueManager.sessionImages.value.lastOrNull()
            ForgeQueueManager.sessionImages.collect { images ->
                val last = images.lastOrNull()
                if (last != null && last != newest) {
                    delay(NEW_IMAGE_SYNC_DELAY_MS)
                    requestSync()
                }
                newest = last
            }
        }
    }

    private fun isAutoSavingAll() = ForgeRepository.config.value.autoSaveMode == AUTO_SAVE_ALL

    fun applyFilters(filters: GalleryFilters) {
        _galleryFilters.value = filters
    }

    fun clearFilters() {
        _galleryFilters.value = GalleryFilters(sortOrder = _galleryFilters.value.sortOrder)
    }

    /** Reloads the index into memory; the filter lists only offer models and LoRAs of the current gallery. */
    private suspend fun reloadIndex() {
        synchronized(promptCache) { promptCache.clear() } // an image read again may have its prompt now
        try {
            val all = getDb().galleryImageDao().getIndexedImages()
            val root = galleryRoot()?.let { norm(it) }
            val inGallery = all.filter { root == null || isUnderNormalized(it.fullpath, root) }
            indexedImages.value = all
            _indexedImageCount.value = inGallery.size
            _availableModels.value = inGallery.map { it.model }.filter { it.isNotBlank() }.distinct().sorted()
            _availableLoras.value =
                inGallery
                    .flatMap { it.loras.split(",") }
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .sorted()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed to load the gallery index", e)
        }
    }

    fun toggleGalleryMetadata() {
        val newVal = !_showGalleryMetadata.value
        _showGalleryMetadata.value = newVal
        managerScope.launch {
            getDb().appSettingDao().putSetting(AppSettingEntity(SHOW_META_KEY, newVal.toString()))
        }
    }

    // --- FAVORITES ---

    private suspend fun loadFavoritePaths() {
        val favorites =
            getDb().favoriteImageDao().getAllFavorites().map {
                GalleryItem(name = it.name, fullpath = it.fullpath, type = "file", date = it.date)
            }
        favoriteItems.value = favorites
        _favoritePaths.value = favorites.mapTo(HashSet()) { it.fullpath }
    }

    /** Up to 1.0.2 images could also be "pinned", a second list of bookmarks; they become favorites. */
    private suspend fun migratePinnedToFavorites() {
        val pinned = ForgeSettingsManager.pinnedImages.value
        if (pinned.isEmpty()) return
        val favorites = getDb().favoriteImageDao()
        val index = getDb().galleryImageDao()
        for (path in pinned) {
            if (!favorites.isFavorite(path)) {
                favorites.insertFavorite(
                    FavoriteImageEntity(fullpath = path, name = fileName(path), date = index.getImageByPath(path)?.date ?: ""),
                )
            }
        }
        ForgeSettingsManager.clearPinnedImages()
    }

    fun toggleFavorite(item: GalleryItem) {
        managerScope.launch {
            val dao = getDb().favoriteImageDao()
            if (dao.isFavorite(item.fullpath)) {
                dao.deleteFavorite(item.fullpath)
                _favoritePaths.update { it - item.fullpath }
                favoriteItems.update { files -> files.filterNot { it.fullpath == item.fullpath } }
            } else {
                dao.insertFavorite(FavoriteImageEntity(fullpath = item.fullpath, name = item.name, date = item.date ?: ""))
                _favoritePaths.update { it + item.fullpath }
                favoriteItems.update { files -> listOf(item.asFavorite()) + files.filterNot { it.fullpath == item.fullpath } }
                if (ForgeRepository.config.value.autoSaveMode == AUTO_SAVE_FAVORITES) saveToPhone(item, quietIfSaved = true)
            }
        }
    }

    fun clearDatabase() {
        managerScope.launch {
            syncJob?.cancelAndJoin()
            getDb().galleryImageDao().clearAll()
            getDb().appSettingDao().removeSetting(FOLDER_DATES_KEY) // the next sync lists every folder again
            reloadIndex() // empties the model/LoRA filter lists built from the index
            lastAutoSyncAt = 0L // opening the gallery builds it again at once
            ForgeRepository.showToast("Gallery Index Wiped")
        }
    }

    // --- PROMPTS FOR THE LIST VIEW ---

    // The positive prompts of the rows seen last (the index in memory has none), newest use last.
    private val promptCache =
        object : LinkedHashMap<String, String>(PROMPT_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > PROMPT_CACHE_SIZE
        }

    /** The prompt an image was made with, from the index; "" when it is not indexed or has none. */
    suspend fun positivePrompt(path: String): String {
        synchronized(promptCache) { promptCache[path] }?.let { return it }
        val prompt =
            withContext(Dispatchers.IO) {
                try {
                    getDb().galleryImageDao().getPositivePrompt(path).orEmpty()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    ""
                }
            }
        synchronized(promptCache) { promptCache[path] = prompt }
        return prompt
    }

    // --- THE SERVER'S GALLERY EXTENSION ---

    /** Looks for the extension again ("Check Again" in the gallery). */
    fun checkExtension() {
        managerScope.launch { detectExtension() }
    }

    /** Another server: what was found on the previous one no longer holds, the open folder included. */
    fun onServerChanged() {
        _extension.value = ExtensionStatus(Extension.UNKNOWN)
        clearFolder()
    }

    /**
     * Asks the server for the Infinite Image Browsing extension (run with the server's lists when the app connects)
     * and takes the gallery's folders from its settings: Forge's working folder and the folder its images are saved
     * to. Once it is found the index is brought up to date.
     */
    suspend fun detectExtension() {
        val api = networkManager.forgeApi ?: return
        val status =
            detection.withLock {
                val before = _extension.value
                _extension.value = ExtensionStatus(Extension.CHECKING)
                val found =
                    try {
                        askForExtension(api)
                    } catch (e: CancellationException) {
                        _extension.value = before // replaced by a newer question, or the app closed
                        throw e
                    }
                _extension.value = found
                found
            }
        if (status.state == Extension.READY) requestSync()
    }

    private suspend fun askForExtension(api: ForgeApi): ExtensionStatus {
        for (prefix in ForgeSettingsManager.GALLERY_PREFIXES) {
            val response =
                try {
                    api.getGlobalSettingsDynamic("$prefix/global_setting")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "No answer from the server about the gallery extension: ${e.message}")
                    return ExtensionStatus(Extension.UNKNOWN, "The server did not answer: ${e.message ?: e.javaClass.simpleName}")
                }
            when {
                response.code() == 404 || response.code() == 405 -> continue // not under this name
                response.code() == 401 || response.code() == 403 -> {
                    apiPrefix = prefix
                    return ExtensionStatus(Extension.FAILED, "The gallery extension refused the app (HTTP ${response.code()}).")
                }
                !response.isSuccessful -> {
                    apiPrefix = prefix
                    return ExtensionStatus(Extension.FAILED, "The gallery extension answered with error ${response.code()}.")
                }
            }
            apiPrefix = prefix
            val settings =
                try {
                    response.body()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    null
                }
            val sdCwd = settings?.sdCwd.orEmpty()
            val folder =
                outputFolder(sdCwd, settings?.globalSetting)
                    ?: return ExtensionStatus(Extension.FAILED, "The gallery extension did not tell where Forge saves images.")
            val config = ForgeRepository.config.value
            if (config.galleryPath != folder || (sdCwd.isNotEmpty() && config.serverBasePath != sdCwd)) {
                ForgeSettingsManager.saveConfig(config.copy(galleryPath = folder, serverBasePath = sdCwd.ifEmpty { config.serverBasePath }))
            }
            Log.d(TAG, "Gallery extension found at $prefix, images in $folder")
            return ExtensionStatus(Extension.READY)
        }
        return ExtensionStatus(Extension.MISSING)
    }

    /**
     * The folder Forge saves txt2img images to, from its settings as the extension reports them: "outdir_samples"
     * when set (every image goes there), else "outdir_txt2img_samples", else Forge's default. A relative folder is
     * under Forge's working folder [sdCwd] (and uses its separator); null when that is needed but unknown.
     */
    internal fun outputFolder(
        sdCwd: String,
        settings: GlobalSettingInnerDto?,
    ): String? {
        val configured =
            (settings?.outdirSamples?.takeIf { it.isNotBlank() } ?: settings?.outdirTxt2ImgSamples?.takeIf { it.isNotBlank() })
                ?.trim() ?: DEFAULT_OUTPUT_FOLDER
        val isAbsolute = configured.startsWith("/") || configured.startsWith("\\\\") || WINDOWS_DRIVE.containsMatchIn(configured)
        if (isAbsolute) return configured.trimEnd('/', '\\').ifEmpty { configured }
        if (sdCwd.isBlank()) return null
        val separator = if (sdCwd.contains('\\')) '\\' else '/'
        val relative =
            configured
                .removePrefix("./")
                .removePrefix(".\\")
                .replace('/', separator)
                .replace('\\', separator)
                .trim(separator)
        val base = sdCwd.trimEnd('/', '\\')
        return if (relative.isEmpty()) base else "$base$separator$relative"
    }

    private val WINDOWS_DRIVE = Regex("^[A-Za-z]:[\\\\/]")

    // --- PATHS & URLS ---

    /** The gallery's top folder (read from the server's extension), or null while it is not known. */
    private fun galleryRoot(): String? = ForgeRepository.config.value.galleryPath.takeIf { it.isNotBlank() && it != "Root" }

    /** The gallery's top folder when the extension is there, null otherwise (the gallery shows why). */
    fun readyRoot(): String? = if (_extension.value.state == Extension.READY) galleryRoot() else null

    // Server paths may use "\" (Windows) or "/"; compared case-insensitively.
    private fun norm(path: String) = path.replace('\\', '/').trimEnd('/').lowercase()

    private fun isUnder(
        path: String,
        folder: String,
    ): Boolean {
        val root = norm(folder)
        return root.isEmpty() || norm(path).startsWith("$root/")
    }

    /**
     * [isUnder] for a folder already normalized ([norm]) without building new strings: it runs for every image of
     * the index whenever the shown list changes.
     */
    private fun isUnderNormalized(
        path: String,
        root: String,
    ): Boolean {
        if (root.isEmpty()) return true
        if (path.length <= root.length) return false
        for (i in root.indices) {
            val c = path[i].let { if (it == '\\') '/' else it.lowercaseChar() }
            if (c != root[i]) return false
        }
        val next = path[root.length]
        return next == '/' || next == '\\'
    }

    private fun parentOf(path: String) = norm(path).substringBeforeLast('/', "")

    private fun fileName(path: String) = path.replace('\\', '/').trimEnd('/').substringAfterLast('/')

    private fun isImage(name: String) = name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

    private fun prefix() = apiPrefix

    private fun galleryUrl(
        endpoint: String,
        vararg params: Pair<String, String>,
    ): String {
        val base =
            ForgeRepository.config.value.apiUrl
                .trimEnd('/')
                .toHttpUrlOrNull() ?: return ""
        val builder = base.newBuilder().addPathSegment(prefix()).addPathSegment(endpoint)
        params.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build().toString()
    }

    // IIB requires "t" (the file's date, so a changed file is not served from a cache) even when it is empty;
    // without it the server answered 422 and favorites saved without a date never loaded.
    fun getGalleryImageUrl(item: GalleryItem): String = galleryUrl("file", "path" to item.fullpath, "t" to item.date.orEmpty())

    /** A small WebP made and cached by the server; the grid used to download every full-size PNG. */
    fun getGalleryThumbnailUrl(item: GalleryItem): String =
        galleryUrl("image-thumbnail", "path" to item.fullpath, "t" to item.date.orEmpty(), "size" to THUMBNAIL_SIZE)

    private fun encodeFolderPath(path: String?): String {
        if (path == null) return ""
        val normalizedPath = path.replace("\\", "/")
        return try {
            java.net.URLEncoder
                .encode(normalizedPath, "UTF-8")
                .replace("%2F", "/")
                .replace("+", "%20")
        } catch (e: Exception) {
            normalizedPath
        }
    }

    private fun parseGalleryItems(json: String): List<GalleryItem> {
        val list = mutableListOf<GalleryItem>()
        if (json.isEmpty()) return list
        try {
            val fileList = gson.fromJson(json, GalleryFileListDto::class.java)
            if (fileList?.files != null) {
                list.addAll(fileList.files.map { it.toDomain() })
            }
        } catch (_: Exception) {
            try {
                val type = object : TypeToken<List<GalleryItemDto>>() {}.type
                val arrayItems = gson.fromJson<List<GalleryItemDto>>(json, type)
                if (arrayItems != null) list.addAll(arrayItems.map { it.toDomain() })
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to parse gallery items. JSON: $json", e2)
            }
        }
        return list
    }

    private fun IndexedImage.toGalleryItem() = GalleryItem(name = name, fullpath = fullpath, type = "file", date = date)

    private fun GalleryItem.asFavorite() = GalleryItem(name = name, fullpath = fullpath, type = "file", date = date.orEmpty())

    /** An error the server reported, shown to the user as it is. */
    private class GalleryException(
        message: String,
    ) : IOException(message)

    private fun api(): ForgeApi = networkManager.forgeApi ?: throw GalleryException("Not connected to the server.")

    private suspend fun listFolder(folder: String): List<GalleryItem> {
        val target = if (folder.isNotEmpty() && folder != "Root") encodeFolderPath(folder) else ""
        val response = api().getGalleryFilesDynamic(url = "${prefix()}/files", folderPath = target)
        return when {
            response.isSuccessful -> parseGalleryItems(response.body()?.string().orEmpty())
            response.code() == 401 || response.code() == 403 -> throw GalleryException("Authentication Required.")
            else -> throw GalleryException("Server returned Error ${response.code()}")
        }
    }

    // --- BROWSING ---

    /**
     * Opens the gallery on the tab used last (the prompt picker on All Images), in the folder that was open last if
     * it is still in the gallery, else its top folder. While the server's extension is not found (yet) nothing is
     * listed: the screen shows why, and opens the top folder once the extension is there.
     */
    fun openGallery(mode: GalleryMode) {
        _galleryMode.value = mode
        _tab.value = if (mode == GalleryMode.PROMPT_PICKER) GalleryTab.ALL_IMAGES else GalleryTab.of(ForgeRepository.config.value.galleryTab)
        val root = readyRoot()
        if (root != null) {
            val last = _currentGalleryPath.value
            fetchGalleryFolder(if (last.isNotEmpty() && isInGallery(last, root)) last else root)
            return
        }
        clearFolder()
        // Asked when the app connected; an unanswered question is asked again.
        if (_extension.value.state == Extension.UNKNOWN && ForgeRepository.isConnected.value) checkExtension()
    }

    /** Another tab chosen; the gallery opens on it next time (not when it was chosen while picking a prompt). */
    fun selectTab(tab: GalleryTab) {
        _tab.value = tab
        val config = ForgeRepository.config.value
        if (_galleryMode.value == GalleryMode.NORMAL && config.galleryTab != tab.name) {
            ForgeSettingsManager.saveConfig(config.copy(galleryTab = tab.name))
        }
    }

    private fun clearFolder() {
        folderRequest.incrementAndGet()
        folderJob?.cancel()
        requestedPath = ""
        listing.value = FolderListing("", emptyList())
        _currentGalleryPath.value = ""
        _galleryError.value = null
        _isGalleryLoading.value = false
    }

    /** Refresh (and Retry after an error): the folder asked for last again, and the index brought up to date at once. */
    fun refreshGallery() {
        val path = requestedPath
        if (path.isEmpty()) {
            checkExtension()
            return
        }
        fetchGalleryFolder(path)
        requestSync()
    }

    /**
     * Opens [path] in the Gallery tab. Only the gallery's own folders can be opened (2.2.0): anything outside its top
     * folder (e.g. the parent "outputs" folder, reached through the path bar before) opens the top folder instead.
     */
    fun fetchGalleryFolder(path: String) {
        val root = galleryRoot() ?: return
        val target = if (isInGallery(path, root)) path else root
        // Only the latest request may show its folder: a slow answer for a folder the user already left used to
        // replace the folder opened after it.
        val request = folderRequest.incrementAndGet()
        folderJob?.cancel()
        requestedPath = target
        _isGalleryLoading.value = true
        _galleryError.value = null

        folderJob =
            managerScope.launch {
                try {
                    val items = listServerFolder(target, root)
                    if (request != folderRequest.get()) return@launch
                    if (items != null) {
                        listing.value = FolderListing(target, items)
                        _currentGalleryPath.value = target
                    }
                    _isGalleryLoading.value = false
                } catch (e: CancellationException) {
                    throw e // a newer request owns the loading state
                } catch (e: Exception) {
                    if (request != folderRequest.get()) return@launch
                    _galleryError.value = if (e is GalleryException) e.message else "Failed to load gallery: ${e.message}"
                    _isGalleryLoading.value = false
                }
            }
    }

    /** The folder's images and subfolders; null when the server rejected it and the top folder is shown instead. */
    private suspend fun listServerFolder(
        path: String,
        root: String,
    ): List<GalleryItem>? {
        val response = api().getGalleryFilesDynamic(url = "${prefix()}/files", folderPath = encodeFolderPath(path))
        return when {
            response.isSuccessful -> parseGalleryItems(response.body()?.string().orEmpty()).filter { it.isDir || isImage(it.name) }
            response.code() == 400 && !samePath(path, root) -> {
                fetchGalleryFolder(root)
                null
            }
            response.code() == 401 || response.code() == 403 -> throw GalleryException("Authentication Required.")
            else -> throw GalleryException("Server returned Error ${response.code()}")
        }
    }

    // --- THE GALLERY'S FOLDERS (the Gallery tab never leaves its top folder) ---

    private fun samePath(
        a: String,
        b: String,
    ) = norm(a) == norm(b)

    private fun isInGallery(
        path: String,
        root: String,
    ) = samePath(path, root) || isUnder(path, root)

    /** The path bar of the Gallery tab: from the top folder ("Gallery") down to [path]. */
    fun breadcrumb(path: String): List<Pair<String, String>> = galleryRoot()?.let { breadcrumb(path, it) }.orEmpty()

    /** The folder above [path] inside the gallery; null in the top folder (Back then closes the gallery). */
    fun parentFolder(path: String): String? = galleryRoot()?.let { parentFolder(path, it) }

    /**
     * (name, path) of each folder from [root] (named "Gallery") to [path]. The paths are cut from [path] itself, so
     * they are written as the server writes them (its separator and letter case).
     */
    internal fun breadcrumb(
        path: String,
        root: String,
    ): List<Pair<String, String>> {
        val top = listOf(GALLERY_NAME to root)
        if (!isUnder(path, root)) return top
        val base = path.substring(0, root.trimEnd('/', '\\').length)
        val separator = path[base.length]
        val names = path.substring(base.length).split('/', '\\').filter { it.isNotEmpty() }
        return top + names.indices.map { i -> names[i] to base + separator + names.take(i + 1).joinToString(separator.toString()) }
    }

    internal fun parentFolder(
        path: String,
        root: String,
    ): String? {
        if (!isUnder(path, root)) return null
        val cut = maxOf(path.trimEnd('/', '\\').lastIndexOf('/'), path.trimEnd('/', '\\').lastIndexOf('\\'))
        val parent = if (cut > 0) path.substring(0, cut) else root
        return if (isUnder(parent, root)) parent else root
    }

    private const val GALLERY_NAME = "Gallery"

    // --- INDEX SYNC (automatic) ---

    /** When the gallery opens: an update of the index, skipped if one ran moments ago. */
    fun autoSyncGallery() = requestSync(throttle = true)

    // Synchronized: asked for from the screen, the server check and the new-image watcher at once, it must still
    // start only one sync.
    @Synchronized
    private fun requestSync(throttle: Boolean = false) {
        val root = readyRoot() ?: return
        if (syncJob?.isActive == true) {
            resyncRequested = true
            return
        }
        val now = System.currentTimeMillis()
        if (throttle && now - lastAutoSyncAt < AUTO_SYNC_INTERVAL_MS) return
        lastAutoSyncAt = now
        syncJob = managerScope.launch { runSync(root) }
    }

    private data class SyncResult(
        val added: Int = 0,
        val removed: Int = 0,
        val failed: Int = 0,
        val savedToPhone: Int = 0,
        val error: String? = null,
    ) {
        val message: String
            get() {
                if (error != null) return "Indexing failed: $error"
                val parts =
                    listOfNotNull(
                        "$added new".takeIf { added > 0 },
                        "$removed removed".takeIf { removed > 0 },
                        "$failed without readable generation data".takeIf { failed > 0 },
                        "$savedToPhone saved to the phone".takeIf { savedToPhone > 0 },
                    )
                return if (parts.isEmpty()) "Gallery index is up to date" else "Gallery indexed: " + parts.joinToString(", ")
            }
    }

    /** Only in the log: the index updates itself and never interrupts the user. */
    internal val lastSyncMessage = MutableStateFlow("")

    private suspend fun runSync(root: String) {
        _isIndexing.value = true
        _gallerySyncProgress.value = 0 to 0
        val result =
            try {
                // Once a day every folder is listed, which also forgets images deleted in folders that look unchanged.
                val lastFull = getDb().appSettingDao().getSetting(FULL_SYNC_AT_KEY)?.value?.toLongOrNull() ?: 0L
                val full = System.currentTimeMillis() - lastFull >= FULL_SYNC_INTERVAL_MS
                doSync(root, full).also {
                    if (full) getDb().appSettingDao().putSetting(AppSettingEntity(FULL_SYNC_AT_KEY, System.currentTimeMillis().toString()))
                }
            } catch (e: CancellationException) {
                _isIndexing.value = false
                throw e
            } catch (e: Exception) {
                // A dropped connection used to escape from here and close the app.
                Log.e(TAG, "Gallery sync failed", e)
                SyncResult(error = e.message ?: e.javaClass.simpleName)
            }
        _indexError.value = result.error
        lastSyncMessage.value = result.message
        Log.d(TAG, result.message)
        _isIndexing.value = false
        syncsDone.incrementAndGet()
        if (resyncRequested) {
            resyncRequested = false
            currentCoroutineContext().job.invokeOnCompletion { requestSync() }
        }
    }

    private class Listing(
        val files: List<GalleryItem>,
        val listedFolders: Set<String>,
        val removedFolders: Set<String>,
        val folderDates: MutableMap<String, String>,
    )

    private suspend fun doSync(
        root: String,
        full: Boolean,
    ): SyncResult {
        val dao = getDb().galleryImageDao()
        val indexed = dao.getAllPaths() // only the paths: the whole index used to be read here, prompts included
        val indexedPaths = indexed.toHashSet()
        val unread = dao.getUnreadPaths().toHashSet()

        // 1. Find the images.
        val listing = listTree(root, if (full) emptyMap() else loadFolderDates())

        // 2. Forget images that are gone: from every listed folder, or anywhere in the gallery on a full sync.
        val present = listing.files.mapTo(HashSet()) { norm(it.fullpath) }
        val stale =
            indexed.filter { path ->
                isUnder(path, root) &&
                    norm(path) !in present &&
                    (
                        full ||
                            parentOf(path) in listing.listedFolders ||
                            listing.removedFolders.any { isUnder(path, it) }
                    )
            }
        stale.chunked(500).forEach { dao.deleteImages(it) } // SQLite limits the number of parameters

        // 3. Read the generation data of the new images (and of those it could not be read for before), 100 per
        // request. An image whose data cannot be read is indexed anyway, so "All Images" shows every image.
        val newFiles = listing.files.filter { it.fullpath !in indexedPaths || it.fullpath in unread }
        val reader = InfoReader()
        val failedFolders = HashSet<String>()
        var failed = 0
        var done = 0
        _gallerySyncProgress.value = 0 to newFiles.size
        for (chunk in newFiles.chunked(INFO_BATCH_SIZE)) {
            currentCoroutineContext().ensureActive()
            val infos = reader.read(chunk)
            val entities =
                chunk.map { file ->
                    infos[file.fullpath]?.let { toEntity(file, it) } ?: run {
                        failed++
                        failedFolders += parentOf(file.fullpath)
                        unreadEntity(file)
                    }
                }
            dao.insertImages(entities)
            done += chunk.size
            _gallerySyncProgress.value = done to newFiles.size
        }

        // Folders with unreadable images are listed again next time, so those images get another try.
        saveFolderDates(listing.folderDates.filterKeys { it !in failedFolders })
        reloadIndex()

        val saved = if (isAutoSavingAll()) autoSaveNewImages(root) else 0
        val added = newFiles.count { it.fullpath !in indexedPaths }
        return SyncResult(added = added, removed = stale.size, failed = failed, savedToPhone = saved)
    }

    /**
     * Walks the gallery's folders. Without [known] dates every folder is listed; otherwise folders whose date
     * (changed when files are added or removed) is the same as last time are skipped unless they are recent,
     * so a quiet sync only asks for the folders new images can be in.
     */
    private suspend fun listTree(
        root: String,
        known: Map<String, String>,
    ): Listing {
        val files = ArrayList<GalleryItem>()
        val listed = HashSet<String>()
        val removed = HashSet<String>()
        val dates = HashMap<String, String>()
        val recent = serverDateFormat().format(Date(System.currentTimeMillis() - RECENT_FOLDER_MS))
        val queue = ArrayDeque<Pair<String, Int>>().apply { add(root to 0) }

        while (queue.isNotEmpty()) {
            val (folder, depth) = queue.removeFirst()
            currentCoroutineContext().ensureActive()
            val items = listFolder(folder)
            val folderKey = norm(folder)
            listed += folderKey
            files += items.filter { !it.isDir && isImage(it.name) }

            val subfolders = items.filter { it.isDir }
            val seen = subfolders.mapTo(HashSet()) { norm(it.fullpath) }
            known.keys.filter { parentOf(it) == folderKey && it !in seen }.forEach { removed += it }
            if (depth >= MAX_FOLDER_DEPTH) continue

            for (dir in subfolders) {
                val key = norm(dir.fullpath)
                val date = dir.date.orEmpty()
                dates[key] = date
                val unchanged = date.isNotEmpty() && known[key] == date && date < recent
                if (unchanged) {
                    dates.putAll(known.filterKeys { isUnder(it, key) }) // keep what is known about its subfolders
                } else {
                    queue.add(dir.fullpath to depth + 1)
                }
            }
        }
        return Listing(files, listed, removed, dates)
    }

    private suspend fun loadFolderDates(): Map<String, String> =
        try {
            val json = getDb().appSettingDao().getSetting(FOLDER_DATES_KEY)?.value
            if (json.isNullOrEmpty()) {
                emptyMap()
            } else {
                gson.fromJson<Map<String, String>>(json, object : TypeToken<Map<String, String>>() {}.type) ?: emptyMap()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptyMap()
        }

    private suspend fun saveFolderDates(dates: Map<String, String>) {
        getDb().appSettingDao().putSetting(AppSettingEntity(FOLDER_DATES_KEY, gson.toJson(dates)))
    }

    private fun toEntity(
        file: GalleryItem,
        text: String,
    ): GalleryImageEntity {
        val info = Infotext.parse(text)
        return GalleryImageEntity(
            fullpath = file.fullpath,
            name = file.name,
            date = file.date.orEmpty(),
            positivePrompt = info.positivePrompt,
            negativePrompt = info.negativePrompt,
            model = info.model,
            sampler = info.sampler,
            seed = info.seed,
            loras = info.loras.joinToString(","),
            savedAt = System.currentTimeMillis(),
        )
    }

    /** An image whose generation data could not be read: shown with its name and date, read again by later syncs. */
    private fun unreadEntity(file: GalleryItem) =
        GalleryImageEntity(
            fullpath = file.fullpath,
            name = file.name,
            date = file.date.orEmpty(),
            positivePrompt = "",
            negativePrompt = "",
            model = "",
            sampler = "",
            seed = "",
            loras = "",
            savedAt = INFO_NOT_READ,
        )

    /**
     * Reads generation data the cheapest way the server's IIB version allows: 100 images per request, else one
     * request per image, else (very old versions) the start of each image file. Images missing from the result
     * could not be read; an empty text means the image has no generation data.
     */
    private class InfoReader {
        private enum class Source { BATCH, SINGLE, FILE }

        private var source = Source.BATCH

        suspend fun read(files: List<GalleryItem>): Map<String, String> {
            val result = HashMap<String, String>()
            var remaining = files

            if (source == Source.BATCH) {
                val response =
                    ForgeGalleryManager.api().getGalleryGenInfoBatch(
                        "${ForgeGalleryManager.prefix()}/image_geninfo_batch",
                        GalleryPathsRequestDto(files.map { it.fullpath }),
                    )
                when {
                    response.isSuccessful -> {
                        val body = response.body().orEmpty()
                        files.filter { body.containsKey(it.fullpath) }.forEach { result[it.fullpath] = body[it.fullpath].orEmpty() }
                        return result
                    }
                    response.code() in UNSUPPORTED -> source = Source.SINGLE
                    else -> throw GalleryException("Server returned Error ${response.code()}")
                }
            }

            if (source == Source.SINGLE) {
                for (file in files) {
                    val text = ForgeGalleryManager.serverGenInfo(file.fullpath)
                    if (text == null) {
                        source = Source.FILE
                        break
                    }
                    result[file.fullpath] = text
                }
                remaining = files.filter { it.fullpath !in result }
            }

            if (source == Source.FILE) {
                coroutineScope {
                    remaining.chunked(4).forEach { group ->
                        group
                            .map { file ->
                                async {
                                    try {
                                        file.fullpath to ForgeGalleryManager.infoFromImageFile(file)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Cannot read ${file.fullpath}: ${e.message}")
                                        null
                                    }
                                }
                            }.awaitAll()
                            .filterNotNull()
                            .forEach { (path, text) -> result[path] = text }
                    }
                }
            }
            return result
        }

        companion object {
            // Older IIB versions do not know the endpoint (404 / 405) or its body (422).
            private val UNSUPPORTED = setOf(404, 405, 422)
        }
    }

    /** Generation data read by IIB on the server; null when this IIB version has no such endpoint. */
    private suspend fun serverGenInfo(path: String): String? {
        val response = api().getGalleryGenInfo("${prefix()}/image_geninfo", path)
        if (response.code() == 404 || response.code() == 405) return null
        if (!response.isSuccessful) throw GalleryException("Server returned Error ${response.code()}")
        val body = response.body()?.string().orEmpty()
        // FastAPI sends the text as a JSON string.
        return try {
            gson.fromJson(body, String::class.java) ?: ""
        } catch (_: Exception) {
            body
        }
    }

    /** Generation data from the start of the image file; the connection is closed after the PNG text chunks. */
    private suspend fun infoFromImageFile(item: GalleryItem): String {
        val request = Request.Builder().url(getGalleryImageUrl(item)).build()
        networkManager.client.newCall(request).awaitResponse().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return extractPngParameters(response.body.byteStream())
        }
    }

    // --- SAVING TO THE PHONE ---

    /** Server date format ("yyyy-MM-dd HH:mm:ss"), used for folder dates and the auto-save start. */
    private fun serverDateFormat() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun setAutoSaveMode(mode: String) {
        val config = ForgeRepository.config.value
        // From now on: switching "All new images" on must not download the gallery that already exists.
        val since =
            if (mode == AUTO_SAVE_ALL && config.autoSaveMode != AUTO_SAVE_ALL) serverDateFormat().format(Date()) else config.autoSaveSince
        ForgeSettingsManager.saveConfig(config.copy(autoSaveMode = mode, autoSaveSince = since))
    }

    private fun isOnMeteredNetwork(): Boolean =
        application.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: false

    /** Saves new images of the gallery (newer than the moment "All new images" was switched on); only on Wi-Fi. */
    private suspend fun autoSaveNewImages(root: String): Int {
        val since = ForgeRepository.config.value.autoSaveSince
        if (since.isBlank() || isOnMeteredNetwork()) return 0
        val candidates =
            indexedImages.value
                .filter { isUnder(it.fullpath, root) && it.date.isNotEmpty() && it.date >= since }
                .sortedBy { it.date }
        if (candidates.isEmpty()) return 0

        val saved = DeviceImages.savedNames(application).toMutableSet()
        var count = 0
        for (image in candidates) {
            currentCoroutineContext().ensureActive()
            val name = DeviceImages.nameFor(image.fullpath)
            if (name in saved) continue
            try {
                saveServerImage(image.toGalleryItem(), name)
                saved += name
                count++
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Auto-save of ${image.fullpath} failed, retried on the next sync: ${e.message}")
            }
        }
        return count
    }

    private suspend fun saveServerImage(
        item: GalleryItem,
        name: String,
    ) {
        val request = Request.Builder().url(getGalleryImageUrl(item)).build()
        networkManager.client.newCall(request).awaitResponse().use { response ->
            if (!response.isSuccessful) throw IOException("Server returned ${response.code}")
            DeviceImages.save(application, name) { out -> response.body.byteStream().use { it.copyTo(out) } }
        }
    }

    private suspend fun saveToPhone(
        item: GalleryItem,
        quietIfSaved: Boolean,
    ) {
        try {
            val name = DeviceImages.nameFor(item.fullpath)
            if (name in DeviceImages.savedNames(application)) {
                if (!quietIfSaved) ForgeRepository.showToast("Already saved in ${DeviceImages.locationName()}")
                return
            }
            saveServerImage(item, name)
            ForgeRepository.showToast("Saved to ${DeviceImages.locationName()}")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ForgeRepository.showToast("Download Failed: ${e.message}")
        }
    }

    fun downloadImage(item: GalleryItem) {
        managerScope.launch { saveToPhone(item, quietIfSaved = false) }
    }

    // --- SEVERAL IMAGES AT ONCE (selected in the gallery) ---

    /** Saves [items] to the phone, skipping those saved before; one message at the end. */
    fun downloadImages(items: List<GalleryItem>) {
        managerScope.launch {
            val saved = DeviceImages.savedNames(application).toMutableSet()
            var count = 0
            var failed = 0
            for (item in items) {
                val name = DeviceImages.nameFor(item.fullpath)
                if (name in saved) continue
                try {
                    saveServerImage(item, name)
                    saved += name
                    count++
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    failed++
                }
            }
            val failedText = if (failed > 0) ", $failed failed" else ""
            ForgeRepository.showToast("Saved $count of ${items.size} images to ${DeviceImages.locationName()}$failedText")
        }
    }

    /** Adds [items] to the favorites (those already there stay). */
    fun addFavorites(items: List<GalleryItem>) {
        managerScope.launch {
            val dao = getDb().favoriteImageDao()
            val added = items.filter { it.fullpath !in _favoritePaths.value }
            for (item in added) {
                dao.insertFavorite(FavoriteImageEntity(fullpath = item.fullpath, name = item.name, date = item.date ?: ""))
            }
            _favoritePaths.update { it + added.map { item -> item.fullpath } }
            favoriteItems.update { files -> added.map { it.asFavorite() } + files }
            ForgeRepository.showToast("Added ${added.size} images to the favorites")
            if (ForgeRepository.config.value.autoSaveMode == AUTO_SAVE_FAVORITES) added.forEach { saveToPhone(it, quietIfSaved = true) }
        }
    }

    /** One share sheet for [items], downloaded from the server one after another. */
    fun shareImages(
        items: List<GalleryItem>,
        onIntentReady: (Intent) -> Unit,
    ) {
        managerScope.launch {
            try {
                val files =
                    items.map { item ->
                        val request = Request.Builder().url(getGalleryImageUrl(item)).build()
                        networkManager.client.newCall(request).awaitResponse().use { response ->
                            if (!response.isSuccessful) throw IOException("Server returned ${response.code}")
                            DeviceImages.sharedCopy(application, item.name) { out -> response.body.byteStream().use { it.copyTo(out) } }
                        }
                    }
                val intent = DeviceImages.shareManyIntent(application, files)
                withContext(Dispatchers.Main) { onIntentReady(intent) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                ForgeRepository.showToast("Share Failed: ${e.message}")
            }
        }
    }

    fun shareImage(
        item: GalleryItem,
        onIntentReady: (Intent) -> Unit,
    ) {
        managerScope.launch {
            try {
                val request = Request.Builder().url(getGalleryImageUrl(item)).build()
                val intent =
                    networkManager.client.newCall(request).awaitResponse().use { response ->
                        if (!response.isSuccessful) throw IOException("Server returned ${response.code}")
                        DeviceImages.shareIntent(application, item.name) { out -> response.body.byteStream().use { it.copyTo(out) } }
                    }
                withContext(Dispatchers.Main) { onIntentReady(intent) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                ForgeRepository.showToast("Share Failed: ${e.message}")
            }
        }
    }

    // --- METADATA ---

    private fun extractPngParameters(inputStream: InputStream): String = PngMetadata.readParameters(inputStream)

    suspend fun extractMetadataFromUri(uri: Uri): String? =
        withContext(Dispatchers.IO) {
            try {
                application.contentResolver.openInputStream(uri)?.use { stream ->
                    extractPngParameters(stream).takeIf { it.isNotBlank() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read URI for metadata", e)
                null
            }
        }

    fun loadMetadataForLocalFile(path: String) {
        _currentImageMetadata.value = "Loading metadata..."
        managerScope.launch {
            _currentImageMetadata.value =
                try {
                    val file = java.io.File(path)
                    if (!file.exists()) {
                        "Failed to load image."
                    } else {
                        file.inputStream().use { extractPngParameters(it) }.ifBlank { "No generation data found." }
                    }
                } catch (e: Exception) {
                    "Failed: ${e.message}"
                }
        }
    }

    /** Generation data for the viewer: read by IIB on the server, so the image is not downloaded a second time. */
    fun loadMetadataForImage(item: GalleryItem?) {
        metadataJob?.cancel() // a slow answer for the previous image must not replace this one
        if (item == null) {
            _currentImageMetadata.value = null
            return
        }
        _currentImageMetadata.value = "Loading metadata..."
        metadataJob =
            managerScope.launch {
                _currentImageMetadata.value =
                    try {
                        (serverGenInfo(item.fullpath) ?: infoFromImageFile(item)).ifBlank { "No generation data found." }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        "Failed: ${e.message}"
                    }
            }
    }

    private fun parseAndApplyPngInfo(text: String) {
        if (text.isEmpty()) return
        val info = Infotext.parse(text)
        ForgeSettingsManager.updateState { state: AppState ->
            // An image's prompts already hold the styles it was made with (3.1.0): chosen ones would come twice.
            val newState = state.copy(positivePrompt = info.positivePrompt, negativePrompt = info.negativePrompt, styles = emptyList())
            info.params["Steps"]?.toIntOrNull()?.let { newState.steps = it }
            info.params["CFG scale"]?.toFloatOrNull()?.let { newState.cfgScale = it }
            info.params["Seed"]?.toLongOrNull()?.let { newState.seed = it }
            info.params["Sampler"]?.let { newState.sampler = it }
            info.params["Size"]?.split("x")?.takeIf { it.size == 2 }?.let { (width, height) ->
                width.trim().toIntOrNull()?.let { newState.width = it }
                height.trim().toIntOrNull()?.let { newState.height = it }
            }
            info.params["Clip skip"]?.toIntOrNull()?.let { newState.clipSkip = it }
            newState
        }
        ForgeRepository.showToast("Loaded generation data")
    }

    // --- PROMPT RECOVERY ---

    fun recoverPromptFromImage(item: GalleryItem) {
        if (_isRestoringPrompt.value != IndicatorState.IDLE) return
        _isRestoringPrompt.value = IndicatorState.LOADING

        restoreJob =
            ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
                try {
                    val imageUrl = getGalleryImageUrl(item)
                    if (imageUrl.isEmpty()) throw Exception("Invalid URL")

                    val file = downloadToCache(item) ?: throw Exception("No data")
                    val infoStr = file.inputStream().use { extractPngParameters(it) }

                    ForgeQueueManager.showRecoveredImage(file)
                    withContext(Dispatchers.Main) { parseAndApplyPngInfo(infoStr) }
                    _isRestoringPrompt.value = IndicatorState.SUCCESS
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    ForgeRepository.showToast("Network error")
                    _isRestoringPrompt.value = IndicatorState.ERROR
                }
                delay(1500)
                _isRestoringPrompt.value = IndicatorState.IDLE
            }
    }

    /**
     * Streams [item] from the server into a new cache file (the whole image used to be held in memory, and
     * encoded again as text to be shown); null when the server did not send it.
     */
    private suspend fun downloadToCache(item: GalleryItem): java.io.File? {
        val imageUrl = getGalleryImageUrl(item)
        if (imageUrl.isEmpty()) return null
        val file = ForgeQueueManager.newRecoveredImageFile()
        networkManager.client.newCall(Request.Builder().url(imageUrl).build()).awaitResponse().use { res ->
            if (!res.isSuccessful) return null
            try {
                file.outputStream().use { out -> res.body.byteStream().use { it.copyTo(out) } }
            } catch (e: Exception) {
                file.delete()
                throw e
            }
        }
        return file
    }

    /** The newest image in the gallery (today's folder, else the newest folder with images, else the top folder). */
    private suspend fun findLastGeneratedImage(): GalleryItem? {
        val rootPath = ForgeRepository.config.value.galleryPath

        suspend fun fetchFiles(folder: String): List<GalleryItem> {
            try {
                val response =
                    networkManager.forgeApi?.getGalleryFilesDynamic(
                        url = "${prefix()}/files",
                        folderPath = if (folder.isNotEmpty() && folder != "Root") encodeFolderPath(folder) else "",
                    )
                if (response?.isSuccessful == true) {
                    val responseBody = response.body()?.string() ?: ""
                    return parseGalleryItems(responseBody)
                } else if (response?.code() == 400 && folder.isNotEmpty() && folder != "Root") {
                    return fetchFiles("Root")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Fetch Last Generated files error", e)
            }
            return emptyList()
        }

        val rootItems = fetchFiles(rootPath)
        val currentDateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val candidateImages = mutableListOf<GalleryItem>()

        val todayFolder = rootItems.find { it.isDir && it.name == currentDateStr }
        if (todayFolder != null) candidateImages.addAll(fetchFiles(todayFolder.fullpath).filter { !it.isDir })

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
        if (candidateImages.isEmpty()) candidateImages.addAll(rootItems.filter { !it.isDir })

        return candidateImages.maxWithOrNull(
            compareBy<GalleryItem> { item ->
                val match = "^(\\d+)-".toRegex().find(item.name)
                match?.groupValues?.get(1)?.toLongOrNull() ?: -1L
            }.thenBy { item ->
                item.createdTime?.toDoubleOrNull() ?: item.date?.toDoubleOrNull() ?: 0.0
            },
        )
    }

    /** The generation data of the newest gallery image, which is also shown as the session. */
    private suspend fun fetchLastGeneratedImageInfo(): String? {
        val target = findLastGeneratedImage() ?: return null
        val file = downloadToCache(target) ?: return null
        val infoStr = file.inputStream().use { extractPngParameters(it) }
        ForgeQueueManager.showRecoveredImage(file)
        return infoStr
    }

    fun recoverLastPrompt() {
        if (_isRestoringPrompt.value != IndicatorState.IDLE) return
        _isRestoringPrompt.value = IndicatorState.LOADING

        // Backup the current AppState to restore it in case the prompt recovery fails.
        val backupState = ForgeRepository.appState.value.copy()

        restoreJob =
            ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
                try {
                    // 1. Fetch from server gallery
                    val galleryInfoStr = fetchLastGeneratedImageInfo()
                    if (!galleryInfoStr.isNullOrBlank()) {
                        withContext(Dispatchers.Main) { parseAndApplyPngInfo(galleryInfoStr) }
                        _isRestoringPrompt.value = IndicatorState.SUCCESS
                        return@launch
                    }

                    // 2. Check local cache (Fallback)
                    val localInfoStr = ForgeSettingsManager.loadLastGeneratedInfo()
                    val localImgFile = java.io.File(application.cacheDir, "last_generated_image.png")

                    if (localInfoStr != null && localImgFile.exists()) {
                        val copy = ForgeQueueManager.newRecoveredImageFile()
                        localImgFile.copyTo(copy, overwrite = true)
                        ForgeQueueManager.showRecoveredImage(copy)
                        withContext(Dispatchers.Main) { parseAndApplyPngInfo(localInfoStr) }
                        _isRestoringPrompt.value = IndicatorState.SUCCESS
                        return@launch
                    }

                    // 3. Fallback to Physton endpoints (for prompt only)
                    var fallbackPos = ""
                    var fallbackNeg = ""
                    try {
                        val posRes = networkManager.forgeApi?.getLatestHistory("txt2img")
                        if (posRes?.isSuccessful == true) {
                            fallbackPos = posRes.body()?.prompt ?: ""
                        }
                        val negRes = networkManager.forgeApi?.getLatestHistory("txt2img_neg")
                        if (negRes?.isSuccessful == true) {
                            fallbackNeg = negRes.body()?.prompt ?: ""
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.e(TAG, "Failed to fetch physton history", e)
                    }

                    if (fallbackPos.isNotEmpty() || fallbackNeg.isNotEmpty()) {
                        ForgeSettingsManager.updateState { state: AppState ->
                            state.copy(
                                positivePrompt = if (fallbackPos.isNotEmpty()) fallbackPos else state.positivePrompt,
                                negativePrompt = if (fallbackNeg.isNotEmpty()) fallbackNeg else state.negativePrompt,
                            )
                        }
                        ForgeRepository.showToast("Restored from server history (No image found)")
                        _isRestoringPrompt.value = IndicatorState.SUCCESS
                        return@launch
                    }

                    // 4. Fallback to backup state
                    ForgeSettingsManager.updateState { _: AppState -> backupState }
                    ForgeRepository.showToast("Used local cache (No images found)")
                    _isRestoringPrompt.value = IndicatorState.SUCCESS
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    ForgeSettingsManager.updateState { _: AppState -> backupState }
                    ForgeRepository.showToast("Recovery failed")
                    _isRestoringPrompt.value = IndicatorState.ERROR
                } finally {
                    delay(1500)
                    _isRestoringPrompt.value = IndicatorState.IDLE
                }
            }
    }

    fun recoverLastSeed() {
        ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
            try {
                // The server reads the seed from the image; only an old gallery extension needs the image itself.
                val target = findLastGeneratedImage()
                val infoStr =
                    target?.let { item ->
                        serverGenInfo(item.fullpath) ?: infoFromImageFile(item)
                    }
                if (infoStr != null) {
                    val foundSeed = Infotext.parse(infoStr).seed.toLongOrNull()
                    if (foundSeed != null) {
                        ForgeSettingsManager.updateState { state: AppState -> state.copy(seed = foundSeed) }
                        ForgeRepository.showToast("Seed recovered: $foundSeed")
                    } else {
                        ForgeRepository.showToast("No seed found in last image")
                    }
                } else {
                    ForgeRepository.showToast("Failed to find last image")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                ForgeRepository.showToast("Network error recovering seed")
            }
        }
    }

    // --- JOBS FROM IMAGES (2.4.0) ---

    /**
     * The dialog of "Upscale Selected" or "More Like This": its [kind], how many images it is for, and those images
     * remade as jobs, in the same order (null while their generation data is read).
     */
    data class ImageJobsRequest(
        val kind: ImageJobs.Kind,
        val count: Int,
        val sources: List<ImageJobs.Source>? = null,
    )

    private val _imageJobs = MutableStateFlow<ImageJobsRequest?>(null)
    val imageJobs: StateFlow<ImageJobsRequest?> = _imageJobs.asStateFlow()
    private var imageJobsRead: Job? = null

    /** Opens the dialog of [kind] for [items] and reads their generation data (100 images per request where IIB can). */
    fun requestImageJobs(
        kind: ImageJobs.Kind,
        items: List<GalleryItem>,
    ) {
        val files = items.filter { !it.isDir }
        if (files.isEmpty()) return
        imageJobsRead?.cancel()
        val request = ImageJobsRequest(kind, files.size)
        _imageJobs.value = request
        imageJobsRead =
            ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
                val sources =
                    try {
                        remakeImages(files)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Cannot read the images' generation data", e)
                        files.map { ImageJobs.Source.Skipped(ImageJobs.UNREADABLE) }
                    }
                // Only into the dialog it was read for (it may have been closed or replaced meanwhile).
                if (_imageJobs.value === request) _imageJobs.value = request.copy(sources = sources)
            }
    }

    /** [files] remade as jobs from their generation data, in the same order. */
    private suspend fun remakeImages(files: List<GalleryItem>): List<ImageJobs.Source> {
        val reader = InfoReader()
        val texts = HashMap<String, String>()
        files.chunked(100).forEach { texts += reader.read(it) }
        val models = networkManager.models.value
        val current = ForgeModelManager.selectedModel.value
        return files.map { file ->
            val text = texts[file.fullpath]
            when {
                text == null -> ImageJobs.Source.Skipped(ImageJobs.UNREADABLE)
                text.isBlank() -> ImageJobs.Source.Skipped(ImageJobs.NO_DATA)
                else -> ImageJobs.remake(Infotext.parse(text), models, current)
            }
        }
    }

    fun dismissImageJobs() {
        imageJobsRead?.cancel()
        _imageJobs.value = null
    }

    /** Another tab of the selection's dialog (Upscale, Variance on Seed); the images' data stays. */
    fun setImageJobsKind(kind: ImageJobs.Kind) {
        _imageJobs.update { it?.copy(kind = kind) }
    }

    /** Queues the upscales of the dialog's images, one job per image; images already that large are left out. */
    fun queueUpscales(
        scale: Float,
        upscaler: String,
        denoising: Float,
    ) {
        val sources = _imageJobs.value?.sources ?: return
        val jobs =
            sources.filterIsInstance<ImageJobs.Source.Ready>().mapNotNull { source ->
                ImageJobs.upscale(source, scale, upscaler, denoising)?.let { it to ImageJobs.upscaleLabel(scale) }
            }
        ForgeQueueManager.queueJobs(jobs)
        _imageJobs.value = null
        ForgeRepository.showToast(queuedMessage(jobs.size, sources.size - jobs.size))
    }

    /** Queues the images like the dialog's one (see ImageJobs.moreLikeThis). */
    fun queueMoreLikeThis(
        similar: Boolean,
        count: Int,
        strength: Float,
    ) {
        val source = _imageJobs.value?.sources?.firstOrNull() as? ImageJobs.Source.Ready ?: return
        val jobs = ImageJobs.moreLikeThis(source, similar, count, strength)
        ForgeQueueManager.queueJobs(jobs)
        _imageJobs.value = null
        ForgeRepository.showToast(queuedMessage(jobs.size, 0))
    }

    /**
     * Queues "Variance on Seed" of the dialog's images (3.0.0): each keeps its seed, the jobs vary what [spec] says;
     * nothing when that is more than MAX_VARIANCE_JOBS (the dialog does not offer it then).
     */
    fun queueVariance(spec: ImageJobs.VarianceSpec) {
        val sources = _imageJobs.value?.sources ?: return
        val ready = sources.filterIsInstance<ImageJobs.Source.Ready>()
        if (ImageJobs.varianceCount(ready, spec) > ImageJobs.MAX_VARIANCE_JOBS) return
        val jobs = ready.flatMap { ImageJobs.variance(it, spec) }
        ForgeQueueManager.queueJobs(jobs)
        _imageJobs.value = null
        ForgeRepository.showToast(queuedMessage(jobs.size, sources.size - ready.size))
    }

    private fun queuedMessage(
        queued: Int,
        leftOut: Int,
    ) = buildString {
        append("Added $queued ${if (queued == 1) "job" else "jobs"} to the queue")
        if (leftOut > 0) append(" ($leftOut left out)")
    }
}
