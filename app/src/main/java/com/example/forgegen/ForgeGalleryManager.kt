package com.example.forgegen

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.util.Base64
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
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
 * The index is filled without downloading images: IIB reads the generation data on the PC and sends only the
 * text, 100 images per request. A quiet sync only lists folders that changed (or are recent); the Sync button
 * lists everything and also removes deleted images from the index.
 * ============================================================================ */
@SuppressLint("StaticFieldLeak")
object ForgeGalleryManager {
    private const val TAG = "ForgeGalleryManager"

    const val FAVORITES = "virtual://favorites"
    const val ALL_IMAGES = "virtual://all"

    private const val SHOW_META_KEY = "show_gallery_meta"
    private const val FOLDER_DATES_KEY = "gallery_folder_dates"
    private const val THUMBNAIL_SIZE = "512x512" // three columns on a 1440 px wide screen
    private const val INFO_BATCH_SIZE = 100
    private const val MAX_FOLDER_DEPTH = 3
    private const val RECENT_FOLDER_MS = 48 * 60 * 60 * 1000L // re-listed on every sync, as new images land there
    private const val AUTO_SYNC_INTERVAL_MS = 30_000L
    private const val NEW_IMAGE_SYNC_DELAY_MS = 2_000L
    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "avif", "gif")

    private lateinit var application: Application
    private lateinit var getDb: () -> ForgeDatabase
    private lateinit var networkManager: ForgeNetworkManager
    private val gson = Gson()
    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val folderItems = MutableStateFlow<List<GalleryItem>>(emptyList())

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

    private val _favoritePaths = MutableStateFlow<Set<String>>(emptySet())
    val favoritePaths: StateFlow<Set<String>> = _favoritePaths.asStateFlow()

    // --- Index sync state ---

    /** The sync dialog: LOADING while shown, SUCCESS/ERROR briefly at the end, IDLE when hidden. */
    private val _isGallerySyncing = MutableStateFlow(IndicatorState.IDLE)
    val isGallerySyncing: StateFlow<IndicatorState> = _isGallerySyncing.asStateFlow()

    /** True while any sync runs, also a quiet one or one sent to the background (thin progress bar). */
    private val _isIndexing = MutableStateFlow(false)
    val isIndexing: StateFlow<Boolean> = _isIndexing.asStateFlow()

    private val _gallerySyncProgress = MutableStateFlow(0 to 0)
    val gallerySyncProgress: StateFlow<Pair<Int, Int>> = _gallerySyncProgress.asStateFlow()

    private val _gallerySyncCurrentFile = MutableStateFlow("")
    val gallerySyncCurrentFile: StateFlow<String> = _gallerySyncCurrentFile.asStateFlow()

    private val indexedImages = MutableStateFlow<List<GalleryImageEntity>>(emptyList())

    private val _indexedImageCount = MutableStateFlow(0)
    val indexedImageCount: StateFlow<Int> = _indexedImageCount.asStateFlow()

    internal val _isRestoringPrompt = MutableStateFlow(IndicatorState.IDLE)
    val isRestoringPrompt: StateFlow<IndicatorState> = _isRestoringPrompt.asStateFlow()

    private var restoreJob: Job? = null
    private var folderJob: Job? = null
    private val folderRequest = AtomicInteger(0)
    private var metadataJob: Job? = null

    private var syncJob: Job? = null

    // Set from the UI and read by the sync coroutine; nothing observes them, so volatile flags are enough.
    @Volatile private var syncDialogShown = false

    @Volatile private var notifySyncInBackground = false

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

    val displayedFiles: StateFlow<List<GalleryItem>> =
        combine(folderItems, _currentGalleryPath, _galleryFilters, indexedImages) { files, path, filters, index ->
            if (filters.isSearch || path == ALL_IMAGES) {
                val root = galleryRoot()
                val found =
                    index
                        .asSequence()
                        .filter { root == null || isUnder(it.fullpath, root) }
                        .filter { !filters.isSearch || matches(it, filters) }
                        .map { it.toGalleryItem() }
                        .toList()
                sortItems(found, filters.sortOrder)
            } else {
                sortItems(files, filters.sortOrder)
            }
        }.stateIn(
            scope = managerScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList(),
        )

    private fun matches(
        image: GalleryImageEntity,
        filters: GalleryFilters,
    ): Boolean {
        if (filters.name.isNotBlank() && !image.name.contains(filters.name.trim(), ignoreCase = true)) return false
        if (filters.prompt.isNotBlank()) {
            val text = filters.prompt.trim()
            if (!image.positivePrompt.contains(text, ignoreCase = true) && !image.negativePrompt.contains(text, ignoreCase = true)) {
                return false
            }
        }
        if (filters.models.isNotEmpty() && image.model !in filters.models) return false
        if (filters.loras.isNotEmpty()) {
            val used = image.loras.split(",").map { it.trim() }
            val ok = if (filters.lorasIsAnd) filters.loras.all { it in used } else filters.loras.any { it in used }
            if (!ok) return false
        }
        return true
    }

    /** Folders first (the virtual ones on top, in their order), then images. Dates are "yyyy-MM-dd HH:mm:ss". */
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
        val (virtualDirs, rest) = items.partition { it.fullpath.startsWith("virtual://") }
        val (dirs, images) = rest.partition { it.isDir }
        return virtualDirs + dirs.sortedWith(comparator) + images.sortedWith(comparator)
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
        // "All new images" saving: look for them after connecting and whenever the app generated images.
        managerScope.launch {
            ForgeRepository.isConnected.collect { connected ->
                if (connected && isAutoSavingAll()) requestSync()
            }
        }
        managerScope.launch {
            var count = ForgeQueueManager.sessionImages.value.size
            ForgeQueueManager.sessionImages.collect { images ->
                if (images.size > count && isAutoSavingAll()) {
                    delay(NEW_IMAGE_SYNC_DELAY_MS)
                    requestSync()
                }
                count = images.size
            }
        }
    }

    private fun isAutoSavingAll() = ForgeRepository.config.value.autoSaveMode == AUTO_SAVE_ALL

    fun setGalleryMode(mode: GalleryMode) {
        _galleryMode.value = mode
    }

    fun applyFilters(filters: GalleryFilters) {
        _galleryFilters.value = filters
    }

    fun clearFilters() {
        _galleryFilters.value = GalleryFilters(sortOrder = _galleryFilters.value.sortOrder)
    }

    /** Reloads the index into memory; the filter lists only offer models and LoRAs of the current gallery. */
    private suspend fun reloadIndex() {
        try {
            val all = getDb().galleryImageDao().getAllImages()
            val root = galleryRoot()
            val inGallery = all.filter { root == null || isUnder(it.fullpath, root) }
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
        _favoritePaths.value = getDb().favoriteImageDao().getAllFavorites().map { it.fullpath }.toSet()
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
                if (_currentGalleryPath.value == FAVORITES) {
                    folderItems.update { files -> files.filterNot { it.fullpath == item.fullpath } }
                }
            } else {
                dao.insertFavorite(FavoriteImageEntity(fullpath = item.fullpath, name = item.name, date = item.date ?: ""))
                _favoritePaths.update { it + item.fullpath }
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
            ForgeRepository.showToast("Gallery Index Wiped")
        }
    }

    // --- PATHS & URLS ---

    /** The gallery's top folder (from the gallery settings), or null while it is not set. */
    private fun galleryRoot(): String? = ForgeRepository.config.value.galleryPath.takeIf { it.isNotBlank() && it != "Root" }

    // Server paths may use "\" (Windows) or "/"; compared case-insensitively.
    private fun norm(path: String) = path.replace('\\', '/').trimEnd('/').lowercase()

    private fun isUnder(
        path: String,
        folder: String,
    ): Boolean {
        val root = norm(folder)
        return root.isEmpty() || norm(path).startsWith("$root/")
    }

    private fun parentOf(path: String) = norm(path).substringBeforeLast('/', "")

    private fun fileName(path: String) = path.replace('\\', '/').trimEnd('/').substringAfterLast('/')

    private fun isImage(name: String) = name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

    private fun prefix() = networkManager.galleryApiPrefix.value

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

    private fun GalleryImageEntity.toGalleryItem() = GalleryItem(name = name, fullpath = fullpath, type = "file", date = date)

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

    fun fetchGalleryFolder(path: String) {
        // Only the latest request may show its folder: a slow answer for a folder the user already left used to
        // replace the folder opened after it.
        val request = folderRequest.incrementAndGet()
        folderJob?.cancel()
        _isGalleryLoading.value = true
        _galleryError.value = null

        folderJob =
            managerScope.launch {
                try {
                    val items =
                        when (path) {
                            FAVORITES ->
                                getDb().favoriteImageDao().getAllFavorites().map {
                                    GalleryItem(name = it.name, fullpath = it.fullpath, type = "file", date = it.date)
                                }
                            ALL_IMAGES -> emptyList() // shown straight from the index
                            else -> listServerFolder(path)
                        }
                    if (request != folderRequest.get()) return@launch
                    if (items != null) {
                        folderItems.value = items
                        _currentGalleryPath.value = path
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

    /** The folder's images and subfolders; null when the server rejected it and the gallery root is shown instead. */
    private suspend fun listServerFolder(path: String): List<GalleryItem>? {
        val target = if (path.isNotEmpty() && path != "Root") encodeFolderPath(path) else ""
        val response = api().getGalleryFilesDynamic(url = "${prefix()}/files", folderPath = target)
        return when {
            response.isSuccessful -> {
                val items =
                    parseGalleryItems(response.body()?.string().orEmpty())
                        .filter { it.isDir || isImage(it.name) }
                        .toMutableList()
                if (path == "Root" || path == ForgeRepository.config.value.galleryPath) {
                    items.add(0, GalleryItem(name = "⭐ Favorites", fullpath = FAVORITES, type = "dir"))
                    items.add(1, GalleryItem(name = "🕒 All Images", fullpath = ALL_IMAGES, type = "dir"))
                }
                items
            }
            response.code() == 400 && path.isNotEmpty() && path != "Root" -> {
                fetchGalleryFolder("Root")
                null
            }
            response.code() == 401 || response.code() == 403 -> throw GalleryException("Authentication Required.")
            else -> throw GalleryException("Server returned Error ${response.code()}")
        }
    }

    // --- INDEX SYNC ---

    /** The Sync button: lists every folder, shows the progress dialog and removes deleted images from the index. */
    fun triggerManualGallerySync() {
        val root =
            galleryRoot() ?: run {
                ForgeRepository.showToast("Set the Gallery Server Path first (gallery settings)")
                return
            }
        syncDialogShown = true
        notifySyncInBackground = false
        _isGallerySyncing.value = IndicatorState.LOADING
        val previous = syncJob
        syncJob =
            managerScope.launch {
                previous?.cancelAndJoin() // a quiet sync may be running; this one covers everything it would
                runSync(root, full = true)
            }
    }

    /** A quiet sync when the gallery opens; skipped if one ran moments ago. */
    fun autoSyncGallery() = requestSync(throttle = true)

    private fun requestSync(throttle: Boolean = false) {
        val root = galleryRoot() ?: return
        if (syncJob?.isActive == true) {
            resyncRequested = true
            return
        }
        val now = System.currentTimeMillis()
        if (throttle && now - lastAutoSyncAt < AUTO_SYNC_INTERVAL_MS) return
        lastAutoSyncAt = now
        syncDialogShown = false
        notifySyncInBackground = false
        syncJob = managerScope.launch { runSync(root, full = false) }
    }

    fun cancelManualGallerySync() {
        syncJob?.cancel()
        syncDialogShown = false
        notifySyncInBackground = false
        _isGallerySyncing.value = IndicatorState.IDLE
        ForgeNotifications.cancel(ForgeNotifications.ID_GALLERY_SYNC)
        ForgeRepository.showToast("Indexing cancelled")
    }

    fun putSyncToBackground() {
        syncDialogShown = false
        notifySyncInBackground = true
        _isGallerySyncing.value = IndicatorState.IDLE
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
                        "$failed could not be read".takeIf { failed > 0 },
                        "$savedToPhone saved to the phone".takeIf { savedToPhone > 0 },
                    )
                return if (parts.isEmpty()) "Gallery index is up to date" else "Gallery indexed: " + parts.joinToString(", ")
            }
    }

    private suspend fun runSync(
        root: String,
        full: Boolean,
    ) {
        _isIndexing.value = true
        _gallerySyncProgress.value = 0 to 0
        _gallerySyncCurrentFile.value = ""
        val result =
            try {
                doSync(root, full)
            } catch (e: CancellationException) {
                _isIndexing.value = false
                throw e
            } catch (e: Exception) {
                // A dropped connection used to escape from here and close the app.
                Log.e(TAG, "Gallery sync failed", e)
                SyncResult(error = e.message ?: e.javaClass.simpleName)
            }
        _isIndexing.value = false
        if (resyncRequested) {
            resyncRequested = false
            currentCoroutineContext().job.invokeOnCompletion { requestSync() }
        }
        report(result)
    }

    private suspend fun report(result: SyncResult) {
        if (notifySyncInBackground) {
            notifySyncInBackground = false
            ForgeNotifications.builder(ForgeNotifications.CHANNEL_PROGRESS)?.let { builder ->
                val notification =
                    builder
                        .setContentTitle(if (result.error == null) "Gallery indexed" else "Gallery indexing failed")
                        .setContentText(result.message)
                        .setAutoCancel(true)
                        .setSilent(true)
                        .build()
                ForgeNotifications.post(ForgeNotifications.ID_GALLERY_SYNC, notification)
            }
        }
        if (syncDialogShown) {
            _isGallerySyncing.value = if (result.error == null) IndicatorState.SUCCESS else IndicatorState.ERROR
            ForgeRepository.showToast(result.message)
            delay(1500)
            if (syncDialogShown) _isGallerySyncing.value = IndicatorState.IDLE
            syncDialogShown = false
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
        val indexed = dao.getAllImages()
        val indexedPaths = indexed.mapTo(HashSet()) { it.fullpath }

        // 1. Find the images.
        val listing = listTree(root, if (full) emptyMap() else loadFolderDates())

        // 2. Forget images that are gone: from every listed folder, or anywhere in the gallery on a full sync.
        val present = listing.files.mapTo(HashSet()) { norm(it.fullpath) }
        val stale =
            indexed
                .filter { image ->
                    isUnder(image.fullpath, root) &&
                        norm(image.fullpath) !in present &&
                        (
                            full ||
                                parentOf(image.fullpath) in listing.listedFolders ||
                                listing.removedFolders.any { isUnder(image.fullpath, it) }
                        )
                }.map { it.fullpath }
        stale.chunked(500).forEach { dao.deleteImages(it) } // SQLite limits the number of parameters

        // 3. Read the generation data of the new images, 100 per request.
        val newFiles = listing.files.filter { it.fullpath !in indexedPaths }
        val reader = InfoReader()
        val failedFolders = HashSet<String>()
        var failed = 0
        var done = 0
        var lastPercent = -1
        _gallerySyncProgress.value = 0 to newFiles.size
        for (chunk in newFiles.chunked(INFO_BATCH_SIZE)) {
            currentCoroutineContext().ensureActive()
            _gallerySyncCurrentFile.value = chunk.first().name
            val infos = reader.read(chunk)
            val entities = chunk.mapNotNull { file -> infos[file.fullpath]?.let { toEntity(file, it) } }
            if (entities.isNotEmpty()) dao.insertImages(entities)
            chunk.filter { it.fullpath !in infos }.forEach {
                failed++
                failedFolders += parentOf(it.fullpath)
            }
            done += chunk.size
            _gallerySyncProgress.value = done to newFiles.size
            val percent = done * 100 / newFiles.size
            if (notifySyncInBackground && percent != lastPercent) {
                lastPercent = percent
                postProgressNotification(done, newFiles.size)
            }
        }

        // Folders with unreadable images are listed again next time, so those images get another try.
        saveFolderDates(listing.folderDates.filterKeys { it !in failedFolders })
        reloadIndex()

        val saved = if (isAutoSavingAll()) autoSaveNewImages(root) else 0
        return SyncResult(added = newFiles.size - failed, removed = stale.size, failed = failed, savedToPhone = saved)
    }

    private fun postProgressNotification(
        done: Int,
        total: Int,
    ) {
        ForgeNotifications.builder(ForgeNotifications.CHANNEL_PROGRESS)?.let { builder ->
            val notification =
                builder
                    .setContentTitle("Indexing Gallery...")
                    .setContentText("$done / $total images")
                    .setProgress(total, done, false)
                    .setOngoing(true)
                    .setSilent(true)
                    .build()
            ForgeNotifications.post(ForgeNotifications.ID_GALLERY_SYNC, notification)
        }
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
            _gallerySyncCurrentFile.value = fileName(folder)
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
                if (!quietIfSaved) ForgeRepository.showToast("Already saved in Pictures/ForgeGen")
                return
            }
            saveServerImage(item, name)
            ForgeRepository.showToast("Saved to Pictures/ForgeGen")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ForgeRepository.showToast("Download Failed: ${e.message}")
        }
    }

    fun downloadImage(item: GalleryItem) {
        managerScope.launch { saveToPhone(item, quietIfSaved = false) }
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
            val newState = state.copy(positivePrompt = info.positivePrompt, negativePrompt = info.negativePrompt)
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

                    val imgReq = Request.Builder().url(imageUrl).build()
                    val bytes =
                        networkManager.client.newCall(imgReq).awaitResponse().use { res ->
                            if (!res.isSuccessful) throw Exception("No data")
                            res.body.bytes()
                        }
                    val infoStr = bytes.inputStream().use { extractPngParameters(it) }

                    ForgeQueueManager.saveRecoveredImageToCache(bytes)
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

    private suspend fun fetchLastGeneratedImageInfo(): String? {
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

        val targetFile =
            candidateImages.maxWithOrNull(
                compareBy<GalleryItem> { item ->
                    val match = "^(\\d+)-".toRegex().find(item.name)
                    match?.groupValues?.get(1)?.toLongOrNull() ?: -1L
                }.thenBy { item ->
                    item.createdTime?.toDoubleOrNull() ?: item.date?.toDoubleOrNull() ?: 0.0
                },
            )
        if (targetFile != null) {
            val imageUrl = getGalleryImageUrl(targetFile)
            if (imageUrl.isNotEmpty()) {
                val imgReq = Request.Builder().url(imageUrl).build()
                val bytes =
                    networkManager.client.newCall(imgReq).awaitResponse().use { res ->
                        if (res.isSuccessful) res.body.bytes() else null
                    }
                if (bytes != null) {
                    val infoStr = bytes.inputStream().use { extractPngParameters(it) }
                    ForgeQueueManager.saveRecoveredImageToCache(bytes)
                    return infoStr
                }
            }
        }
        return null
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
                        val sessionImage = ForgeQueueManager.sessionImages.value.firstOrNull()
                        if (sessionImage != null) {
                            val bytes = java.io.File(sessionImage).readBytes()
                            val base64Str = Base64.encodeToString(bytes, Base64.NO_WRAP)
                            ForgeQueueManager.setLivePreviewImage(base64Str)
                        }
                        withContext(Dispatchers.Main) { parseAndApplyPngInfo(galleryInfoStr) }
                        _isRestoringPrompt.value = IndicatorState.SUCCESS
                        return@launch
                    }

                    // 2. Check local cache (Fallback)
                    val localInfoStr = ForgeSettingsManager.loadLastGeneratedInfo()
                    val localImgFile = java.io.File(application.cacheDir, "last_generated_image.png")

                    if (localInfoStr != null && localImgFile.exists()) {
                        val bytes = localImgFile.readBytes()
                        ForgeQueueManager.saveRecoveredImageToCache(bytes)
                        val base64Str = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        ForgeQueueManager.setLivePreviewImage(base64Str)
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
                val infoStr = fetchLastGeneratedImageInfo()
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
}
