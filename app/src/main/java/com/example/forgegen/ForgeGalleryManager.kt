package com.example.forgegen

import com.example.forgegen.ui.components.*
import android.annotation.SuppressLint
import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* ============================================================================
 * GALLERY MANAGER
 * Handles the online gallery via the Infinite Image Browsing API, bookmarks/favorites,
 * reads Embedded PNG metadata, and manages file operations such as downloading and sharing.
 * ============================================================================ */
@SuppressLint("StaticFieldLeak")
object ForgeGalleryManager {
    private const val TAG = "ForgeGalleryManager"

    private lateinit var application: Application
    private lateinit var getDb: () -> ForgeDatabase
    private lateinit var networkManager: ForgeNetworkManager
    private val gson = Gson()
    private const val SHOW_META_KEY = "show_gallery_meta"
    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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

    private val _isCurrentFavorite = MutableStateFlow(false)
    val isCurrentFavorite: StateFlow<Boolean> = _isCurrentFavorite.asStateFlow()

    private val _gallerySyncProgress = MutableStateFlow(0 to 0)
    val gallerySyncProgress: StateFlow<Pair<Int, Int>> = _gallerySyncProgress.asStateFlow()

    private val _isGallerySyncing = MutableStateFlow(IndicatorState.IDLE)
    val isGallerySyncing: StateFlow<IndicatorState> = _isGallerySyncing.asStateFlow()

    // Set from the UI and read by the sync coroutine; nothing observes it, so a volatile flag is enough.
    @Volatile private var isGallerySyncBackgrounded = false

    private val _gallerySyncCurrentFile = MutableStateFlow("")
    val gallerySyncCurrentFile: StateFlow<String> = _gallerySyncCurrentFile.asStateFlow()

    private val _favoritePaths = MutableStateFlow<Set<String>>(emptySet())
    val favoritePaths: StateFlow<Set<String>> = _favoritePaths.asStateFlow()

    internal val _isRestoringPrompt = MutableStateFlow(IndicatorState.IDLE)
    val isRestoringPrompt: StateFlow<IndicatorState> = _isRestoringPrompt.asStateFlow()

    private var restoreJob: Job? = null

    fun cancelPromptRestore() {
        if (_isRestoringPrompt.value == IndicatorState.LOADING) {
            restoreJob?.cancel()
            _isRestoringPrompt.value = IndicatorState.IDLE
        }
    }

    // --- FILTERING STATE AND LOGIC (Offloaded from UI) ---
    enum class SortOrder { NEWEST, OLDEST, NAME_ASC, NAME_DESC }

    data class GalleryFilters(
        val models: Set<String> = emptySet(),
        val modelsIsAnd: Boolean = false, // false = OR, true = AND
        val loras: Set<String> = emptySet(),
        val lorasIsAnd: Boolean = false,
        val name: String = "",
        val prompt: String = "",
        val sortOrder: SortOrder = SortOrder.NEWEST
    )

    // --- FILTERING STATE AND LOGIC (Offloaded from UI) ---
    private val _galleryFilters = MutableStateFlow(GalleryFilters())
    val galleryFilters: StateFlow<GalleryFilters> = _galleryFilters.asStateFlow()
    
    private val _availableModels = MutableStateFlow<List<String>>(emptyList())
    val availableModels: StateFlow<List<String>> = _availableModels.asStateFlow()

    private val _availableLoras = MutableStateFlow<List<String>>(emptyList())
    val availableLoras: StateFlow<List<String>> = _availableLoras.asStateFlow()

    private val _allImageMetadata = MutableStateFlow<List<GalleryImageEntity>>(emptyList())

    val displayedFiles: StateFlow<List<GalleryItem>> =
        combine(
            _galleryFiles,
            _currentGalleryPath,
            _galleryFilters,
            _allImageMetadata
        ) { files, path, filters, metadata ->
            var result = files.toList()
            val metadataMap = metadata.associateBy { it.fullpath }

            val isSearchActive = filters.name.isNotBlank() || filters.prompt.isNotBlank() || filters.models.isNotEmpty() || filters.loras.isNotEmpty()

            if (isSearchActive) {
                // Remove directories from search results
                result = result.filter { !it.isDir }

                result = result.filter { item ->
                    val meta = metadataMap[item.fullpath]
                    if (meta == null) {
                        // If no metadata is found but filters are active, and we are filtering by name, check name
                        // Otherwise it fails advanced metadata filters
                        if (filters.models.isNotEmpty() || filters.loras.isNotEmpty() || filters.prompt.isNotBlank()) {
                            return@filter false
                        }
                        return@filter item.name.contains(filters.name, ignoreCase = true)
                    }

                    // Name check
                    if (filters.name.isNotBlank() && !meta.name.contains(filters.name, ignoreCase = true)) {
                        return@filter false
                    }

                    // Prompt check
                    if (filters.prompt.isNotBlank()) {
                        val promptTag = filters.prompt.lowercase()
                        if (!meta.positivePrompt.lowercase().contains(promptTag) && !meta.negativePrompt.lowercase().contains(promptTag)) {
                            return@filter false
                        }
                    }

                    // Models check
                    if (filters.models.isNotEmpty()) {
                        if (filters.modelsIsAnd) {
                            // AND logic for models: impossible since an image only has one model, but if enforced:
                            if (!filters.models.contains(meta.model)) return@filter false
                        } else {
                            // OR logic
                            if (!filters.models.contains(meta.model)) return@filter false
                        }
                    }

                    // Loras check
                    if (filters.loras.isNotEmpty()) {
                        val imgLoras = meta.loras.split(",").map { it.trim() }
                        if (filters.lorasIsAnd) {
                            if (!filters.loras.all { it in imgLoras }) return@filter false
                        } else {
                            if (!filters.loras.any { it in imgLoras }) return@filter false
                        }
                    }

                    true
                }
            }

            // Apply SortOrder
            when (filters.sortOrder) {
                SortOrder.NEWEST -> {
                    val dirs = result.filter { it.isDir }.sortedByDescending { it.name }
                    val items = result.filter { !it.isDir }.sortedByDescending { it.name }
                    result = dirs + items
                }
                SortOrder.OLDEST -> {
                    val dirs = result.filter { it.isDir }.sortedBy { it.name }
                    val items = result.filter { !it.isDir }.sortedBy { it.name }
                    result = dirs + items
                }
                SortOrder.NAME_ASC -> {
                    val dirs = result.filter { it.isDir }.sortedBy { it.name.lowercase() }
                    val items = result.filter { !it.isDir }.sortedBy { it.name.lowercase() }
                    result = dirs + items
                }
                SortOrder.NAME_DESC -> {
                    val dirs = result.filter { it.isDir }.sortedByDescending { it.name.lowercase() }
                    val items = result.filter { !it.isDir }.sortedByDescending { it.name.lowercase() }
                    result = dirs + items
                }
            }

            result
        }.stateIn(
                scope = managerScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList(),
            )

    fun init(
        app: Application,
        database: () -> ForgeDatabase,
        network: ForgeNetworkManager,
    ) {
        application = app
        getDb = database
        networkManager = network
    }

    fun start() {
        ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
            if (!::getDb.isInitialized) {
                android.util.Log.e(TAG, "ForgeGalleryManager start called but getDb is not initialized!")
                return@launch
            }
            _showGalleryMetadata.value = getDb().appSettingDao().getSetting(SHOW_META_KEY)?.value?.toBoolean() ?: false
            // Without this the stars in the gallery stay empty after a restart until something is toggled.
            loadFavoritePaths()
            fetchAvailableModels()
        }
    }

    fun setGalleryMode(mode: GalleryMode) {
        _galleryMode.value = mode
    }

    // === WYSZUKIWANIE I FILTROWANIE ===

    fun applyFilters(filters: GalleryFilters) {
        _galleryFilters.value = filters
    }

    fun clearFilters() {
        _galleryFilters.value = GalleryFilters()
    }

    private suspend fun fetchAvailableModels() {
        if (::getDb.isInitialized) {
            try {
                val allImgs = getDb().galleryImageDao().getAllImages()
                _allImageMetadata.value = allImgs
                
                val models = allImgs.map { it.model }.filter { it.isNotBlank() }.distinct().sorted()
                _availableModels.value = models
                
                val loras = allImgs.flatMap { it.loras.split(",") }.map { it.trim() }.filter { it.isNotBlank() }.distinct().sorted()
                _availableLoras.value = loras
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch available metadata", e)
            }
        }
    }



    fun toggleGalleryMetadata() {
        val newVal = !_showGalleryMetadata.value
        _showGalleryMetadata.value = newVal
        ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
            getDb().appSettingDao().putSetting(AppSettingEntity("show_gallery_meta", newVal.toString()))
        }
    }

    private suspend fun loadFavoritePaths() {
        val favs = getDb().favoriteImageDao().getAllFavorites()
        _favoritePaths.value = favs.map { it.fullpath }.toSet()
    }

    fun checkIfFavorite(path: String) {
        if (path.isEmpty()) {
            _isCurrentFavorite.value = false
            return
        }
        ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
            _isCurrentFavorite.value = getDb().favoriteImageDao().isFavorite(path)
        }
    }

    fun toggleFavorite(item: GalleryItem) {
        ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
            val dao = getDb().favoriteImageDao()
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
                        date = item.date ?: "",
                    ),
                )
                _isCurrentFavorite.value = true
                _favoritePaths.update { it + item.fullpath }
            }
        }
    }

    fun clearDatabase() {
        managerScope.launch(Dispatchers.IO) {
            getDb().galleryImageDao().clearAll()
            fetchAvailableModels() // empties the model/LoRA filter lists built from the index
            withContext(Dispatchers.Main) {
                ForgeRepository.showToast("Gallery Index Wiped")
            }
        }
    }

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

    fun fetchGalleryFolder(path: String) {
        _isGalleryLoading.value = true
        _galleryError.value = null

        ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
            try {
                if (path == "virtual://favorites") {
                    val favorites = getDb().favoriteImageDao().getAllFavorites()
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

                if (path == "virtual://pinned") {
                    val pinnedPaths = ForgeSettingsManager.pinnedImages.value
                    val items = pinnedPaths.map { p ->
                        val existing = getDb().galleryImageDao().getImageByPath(p)
                        GalleryItem(
                            name = existing?.name ?: p.substringAfterLast("/").substringAfterLast("\\"),
                            fullpath = p,
                            type = "file",
                            date = existing?.date,
                            createdTime = null,
                            size = null,
                        )
                    }
                    _galleryFiles.value = items
                    _currentGalleryPath.value = path
                    return@launch
                }

                val targetFolder = if (path.isNotEmpty() && path != "Root") encodeFolderPath(path) else ""
                val prefix = networkManager.galleryApiPrefix.value
                val response = networkManager.forgeApi?.getGalleryFilesDynamic(url = "$prefix/files", folderPath = targetFolder)
                val configGalleryPath = ForgeRepository.config.value.galleryPath

                if (response?.isSuccessful == true) {
                    val responseBody = response.body()?.string() ?: ""
                    val allItems = parseGalleryItems(responseBody).sortedWith(compareBy({ !it.isDir }, { it.name })).toMutableList()

                    if (path == "Root" || path == configGalleryPath) {
                        allItems.add(0, GalleryItem(name = "⭐ Favorites", fullpath = "virtual://favorites", type = "dir"))
                        if (ForgeSettingsManager.pinnedImages.value.isNotEmpty()) {
                            allItems.add(1, GalleryItem(name = "📌 Pinned", fullpath = "virtual://pinned", type = "dir"))
                        }
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
                _galleryError.value = "Failed to load gallery: ${e.message}"
            } finally {
                _isGalleryLoading.value = false
            }
        }
    }

    private var syncJob: Job? = null

    fun triggerManualGallerySync() {
        if (_currentGalleryPath.value.isEmpty()) return
        isGallerySyncBackgrounded = false
        ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
            syncGalleryDatabase(_currentGalleryPath.value)
            syncJob?.join()
            if (!isGallerySyncBackgrounded) {
                withContext(Dispatchers.Main) {
                    ForgeRepository.showToast("Gallery Indexed Successfully")
                }
            }
            _isGallerySyncing.value = IndicatorState.IDLE
            isGallerySyncBackgrounded = false
        }
    }

    fun cancelManualGallerySync() {
        syncJob?.cancel()
        _isGallerySyncing.value = IndicatorState.IDLE
        isGallerySyncBackgrounded = false
        ForgeNotifications.cancel(ForgeNotifications.ID_GALLERY_SYNC)
    }

    fun putSyncToBackground() {
        isGallerySyncBackgrounded = true
        _isGallerySyncing.value = IndicatorState.IDLE
    }

    private suspend fun scanDirectoryDeep(folderPath: String, currentDepth: Int, maxDepth: Int = 2): List<GalleryItem> {
        val prefix = networkManager.galleryApiPrefix.value
        val response = networkManager.forgeApi?.getGalleryFilesDynamic(url = "$prefix/files", folderPath = encodeFolderPath(folderPath))
        if (response?.isSuccessful == true) {
            val responseBody = response.body()?.string() ?: ""
            val items = parseGalleryItems(responseBody)
            val files = items.filter { !it.isDir }.toMutableList()
            if (currentDepth < maxDepth) {
                val dirs = items.filter { it.isDir && it.name != "Root" && !it.name.contains("favorites") }
                for (dir in dirs) {
                    files.addAll(scanDirectoryDeep(dir.fullpath, currentDepth + 1, maxDepth))
                }
            }
            return files
        }
        return emptyList()
    }

    fun syncGalleryDatabase(folderPath: String) {
        if (folderPath == "virtual://favorites" || folderPath == "virtual://pinned" || folderPath == "Root") return
        syncJob?.cancel()
        syncJob =
            managerScope.launch(Dispatchers.IO) {
                _isGallerySyncing.value = IndicatorState.LOADING
                val dao = getDb().galleryImageDao()
                
                Log.d("GallerySync", "Scanning directory tree for files: '$folderPath'...")
                val files = scanDirectoryDeep(folderPath, 0)
                val total = files.size
                _gallerySyncProgress.value = 0 to total
                
                Log.d("GallerySync", "Starting sync for $total files in '$folderPath'...")

                if (total == 0) {
                    Log.w("GallerySync", "No images found to sync. Is the folder empty?")
                }

                val processedCount = java.util.concurrent.atomic.AtomicInteger(0)
                val lastNotifiedPercent = java.util.concurrent.atomic.AtomicInteger(-1)
                val channel = kotlinx.coroutines.channels.Channel<GalleryItem>(kotlinx.coroutines.channels.Channel.UNLIMITED)
                files.forEach { channel.trySend(it) }
                channel.close()

                val workers = List(2) {
                    launch(Dispatchers.IO) {
                        for (file in channel) {
                            if (!isActive) break
                            _gallerySyncCurrentFile.value = file.name

                            val existing = dao.getImageByPath(file.fullpath)
                            if (existing == null) {
                                Log.d("GallerySync", "Fetching: ${file.name}...")
                                val imageUrl = getGalleryImageUrl(file)
                                val request = Request.Builder().url(imageUrl).build()
                                try {
                                    // Closing the response releases the connection; only the PNG header is read.
                                    val infoStr =
                                        networkManager.client.newCall(request).execute().use { response ->
                                            if (!response.isSuccessful) {
                                                Log.e("GallerySync", "Error ${response.code} fetching ${file.name}")
                                                null
                                            } else {
                                                extractPngParameters(response.body.byteStream())
                                            }
                                        }
                                    // Not indexed on failure, so the next sync retries the file.
                                    if (infoStr != null) {
                                        var posPrompt = ""
                                        var negPrompt = ""
                                        var model = ""
                                        var sampler = ""
                                        var seed = ""
                                        var loras = ""

                                        if (infoStr.isNotEmpty()) {
                                            val lines = infoStr.split("\n")
                                            // The positive prompt may span several lines, up to "Negative prompt:" / "Steps:".
                                            posPrompt =
                                                lines
                                                    .takeWhile { !it.startsWith("Negative prompt:") && !it.startsWith("Steps:") }
                                                    .joinToString("\n")
                                                    .trim()
                                            val negIndex = lines.indexOfFirst { it.startsWith("Negative prompt:") }
                                            if (negIndex != -1) negPrompt = lines[negIndex].substringAfter("Negative prompt:").trim()

                                            val paramLine = lines.lastOrNull { it.contains("Steps:") } ?: ""
                                            val params =
                                                paramLine.split(",").associate {
                                                    val parts = it.split(":")
                                                    if (parts.size == 2) parts[0].trim() to parts[1].trim() else "" to ""
                                                }

                                            model = params["Model"] ?: ""
                                            sampler = params["Sampler"] ?: ""
                                            seed = params["Seed"] ?: ""

                                            val loraRegex = Regex("<lora:([^:]+):[^>]+>")
                                            loras = loraRegex.findAll(posPrompt).map { it.groupValues[1] }.joinToString(",")
                                            Log.d("GallerySync", "Parsed metadata for ${file.name}")
                                        } else {
                                            Log.w("GallerySync", "No metadata found in ${file.name}")
                                        }

                                        val entity =
                                            GalleryImageEntity(
                                                fullpath = file.fullpath,
                                                name = file.name,
                                                date = file.date ?: "",
                                                positivePrompt = posPrompt,
                                                negativePrompt = negPrompt,
                                                model = model,
                                                sampler = sampler,
                                                seed = seed,
                                                loras = loras,
                                                savedAt = System.currentTimeMillis(),
                                            )
                                        dao.insertImage(entity)
                                        Log.d("GallerySync", "Indexed: ${file.name}")
                                    }
                                } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    Log.e(TAG, "Sync failed for ${file.fullpath}: ${e.message}")
                                }
                            } else {
                                // Silent skip as requested
                            }
                            
                            val current = processedCount.incrementAndGet()
                            _gallerySyncProgress.value = current to total

                            // Only on a new percentage: one post per file exceeded Android's rate limit.
                            val percent = if (total > 0) current * 100 / total else 100
                            if (isGallerySyncBackgrounded && lastNotifiedPercent.getAndSet(percent) != percent) {
                                ForgeNotifications.builder(ForgeNotifications.CHANNEL_PROGRESS)?.let { builder ->
                                    val notification =
                                        builder
                                            .setContentTitle("Indexing Gallery...")
                                            .setContentText("$current / $total images")
                                            .setProgress(total, current, false)
                                            .setOngoing(true)
                                            .setSilent(true)
                                            .build()
                                    ForgeNotifications.post(ForgeNotifications.ID_GALLERY_SYNC, notification)
                                }
                            }
                        }
                    }
                }
                
                workers.joinAll()
                _isGallerySyncing.value = IndicatorState.SUCCESS
                
                // In the background the "Indexed" toast is skipped, so the progress notification turns into the result.
                if (isGallerySyncBackgrounded) {
                    ForgeNotifications.builder(ForgeNotifications.CHANNEL_PROGRESS)?.let { builder ->
                        val notification =
                            builder
                                .setContentTitle("Gallery indexed")
                                .setContentText("$total images checked")
                                .setAutoCancel(true)
                                .setSilent(true)
                                .build()
                        ForgeNotifications.post(ForgeNotifications.ID_GALLERY_SYNC, notification)
                    }
                }

                fetchAvailableModels()
                delay(1500)
                _isGallerySyncing.value = IndicatorState.IDLE
                _gallerySyncProgress.value = 0 to 0
            }
    }

    fun getGalleryImageUrl(item: GalleryItem): String {
        val urlStr =
            ForgeRepository.config.value.apiUrl
                .trimEnd('/')
        val prefix = networkManager.galleryApiPrefix.value
        val builder =
            urlStr
                .toHttpUrlOrNull()
                ?.newBuilder()
                ?.addPathSegment(prefix)
                ?.addPathSegment("file")
                ?.addQueryParameter("path", item.fullpath)

        if (!item.date.isNullOrEmpty()) builder?.addQueryParameter("t", item.date)
        return builder?.build()?.toString() ?: ""
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

    // --- PNG METADATA EXTRACTION LOGIC (STREAMING SAFEGUARD) ---

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
        ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
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

    fun loadMetadataForImage(item: GalleryItem?) {
        if (item == null) {
            _currentImageMetadata.value = null
            return
        }
        _currentImageMetadata.value = "Loading metadata..."
        ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
            try {
                val imageUrl = getGalleryImageUrl(item)
                if (imageUrl.isEmpty()) {
                    _currentImageMetadata.value = "Invalid URL."
                    return@launch
                }

                val imgReq = Request.Builder().url(imageUrl).build()
                networkManager.client.newCall(imgReq).awaitResponse().use { res ->
                    if (res.isSuccessful) {
                        res.body.byteStream().use { stream ->
                            val infoStr = extractPngParameters(stream)
                            _currentImageMetadata.value = if (infoStr.isNotBlank()) infoStr else "No generation data found."
                        }
                    } else {
                        _currentImageMetadata.value = "Failed to load image."
                    }
                }
            } catch (e: Exception) {
                _currentImageMetadata.value = "Failed: ${e.message}"
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
                if (currentMode == 0) {
                    pos += line + "\n"
                } else if (currentMode == 1) {
                    neg += line + "\n"
                }
            }
        }

        ForgeSettingsManager.updateState { state: AppState ->
            val newState = state.copy(positivePrompt = pos.trim(), negativePrompt = neg.trim())
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
        ForgeRepository.showToast("Loaded generation data")
    }

    fun recoverPromptFromImage(item: GalleryItem) {
        if (_isRestoringPrompt.value != IndicatorState.IDLE) return
        _isRestoringPrompt.value = IndicatorState.LOADING

        restoreJob =
            ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
                try {
                    val imageUrl = getGalleryImageUrl(item)
                    if (imageUrl.isEmpty()) throw Exception("Invalid URL")

                    val imgReq = Request.Builder().url(imageUrl).build()

                    networkManager.client.newCall(imgReq).awaitResponse().use { res ->
                        if (res.isSuccessful) {
                            res.body.byteStream().use { stream ->
                                // Buffer to a local file to cache the image (required for UI preview/sharing).
                                val cacheFile = java.io.File(application.cacheDir, "recovered_${System.currentTimeMillis()}.png")
                                stream.use { input -> java.io.FileOutputStream(cacheFile).use { out -> input.copyTo(out) } }

                                val bytes = cacheFile.readBytes()
                                val infoStr = cacheFile.inputStream().use { extractPngParameters(it) }

                                ForgeQueueManager.saveRecoveredImageToCache(bytes)
                                withContext(Dispatchers.Main) { parseAndApplyPngInfo(infoStr) }
                                _isRestoringPrompt.value = IndicatorState.SUCCESS
                            }
                        } else {
                            throw Exception("No data")
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
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
                val prefix = networkManager.galleryApiPrefix.value
                val response =
                    networkManager.forgeApi?.getGalleryFilesDynamic(
                        url = "$prefix/files",
                        folderPath =
                            if (folder.isNotEmpty() &&
                                folder != "Root"
                            ) {
                                encodeFolderPath(folder)
                            } else {
                                ""
                            },
                    )
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

        val targetFile = candidateImages.maxWithOrNull(
            compareBy<GalleryItem> { item ->
                val match = "^(\\d+)-".toRegex().find(item.name)
                match?.groupValues?.get(1)?.toLongOrNull() ?: -1L
            }.thenBy { item ->
                item.createdTime?.toDoubleOrNull() ?: item.date?.toDoubleOrNull() ?: 0.0
            }
        )
        if (targetFile != null) {
            val imageUrl = getGalleryImageUrl(targetFile)
            if (imageUrl.isNotEmpty()) {
                val imgReq = Request.Builder().url(imageUrl).build()
                val tempFile = java.io.File(application.cacheDir, "temp_rec_${System.currentTimeMillis()}.png")
                var success = false

                networkManager.client.newCall(imgReq).awaitResponse().use { res ->
                    if (res.isSuccessful) {
                        res.body.byteStream().use { input ->
                            java.io.FileOutputStream(tempFile).use { out -> input.copyTo(out) }
                            success = true
                        }
                    }
                }

                if (success) {
                    try {
                        val infoStr = tempFile.inputStream().use { extractPngParameters(it) }
                        ForgeQueueManager.saveRecoveredImageToCache(tempFile.readBytes())
                        return infoStr
                    } finally {
                        tempFile.delete()
                    }
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
                    ForgeSettingsManager.updateState { state: AppState -> backupState }
                    ForgeRepository.showToast("Used local cache (No images found)")
                    _isRestoringPrompt.value = IndicatorState.SUCCESS
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    ForgeSettingsManager.updateState { state: AppState -> backupState }
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
                        ForgeSettingsManager.updateState { state: AppState -> state.copy(seed = foundSeed) }
                        ForgeRepository.showToast("Seed recovered: $foundSeed")
                    } else {
                        ForgeRepository.showToast("No seed found in last image")
                    }
                } else {
                    ForgeRepository.showToast("Failed to find last image")
                }
            } catch (e: Exception) {
                ForgeRepository.showToast("Network error recovering seed")
            }
        }
    }

    // --- LOCAL FILE OPERATIONS ---

    fun downloadImage(item: GalleryItem) {
        ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
            try {
                val url = getGalleryImageUrl(item)
                if (url.isEmpty()) throw Exception("Invalid Gallery URL")

                val request = Request.Builder().url(url).build()
                networkManager.client.newCall(request).awaitResponse().use { response ->
                    if (response.isSuccessful) {
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
                            resolver.openOutputStream(uri)?.use { outStream ->
                                response.body.byteStream().use { inStream ->
                                    inStream.copyTo(outStream)
                                }
                            }
                            ForgeRepository.showToast("Saved to Downloads")
                        } else {
                            throw Exception("Failed to create file in MediaStore")
                        }
                    } else {
                        throw Exception("Server returned ${response.code}")
                    }
                }
            } catch (e: Exception) {
                ForgeRepository.showToast("Download Failed: ${e.message}")
            }
        }
    }

    fun shareImage(
        item: GalleryItem,
        onIntentReady: (Intent) -> Unit,
    ) {
        ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
            try {
                val url = getGalleryImageUrl(item)
                if (url.isEmpty()) throw Exception("Invalid Gallery URL")

                val request = Request.Builder().url(url).build()
                networkManager.client.newCall(request).awaitResponse().use { response ->
                    if (response.isSuccessful) {
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
                            resolver.openOutputStream(uri)?.use { outStream ->
                                response.body.byteStream().use { inStream ->
                                    inStream.copyTo(outStream)
                                }
                            }
                            val shareIntent =
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "image/png"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                            withContext(Dispatchers.Main) { onIntentReady(Intent.createChooser(shareIntent, "Share Image")) }
                        } else {
                            throw Exception("Failed to prepare file for sharing")
                        }
                    } else {
                        throw Exception("Server returned ${response.code}")
                    }
                }
            } catch (e: Exception) {
                ForgeRepository.showToast("Share Failed: ${e.message}")
            }
        }
    }
}
