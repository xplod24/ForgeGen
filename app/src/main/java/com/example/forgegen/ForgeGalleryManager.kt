package com.example.forgegen

import android.annotation.SuppressLint
import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.InputStream
import java.nio.ByteBuffer
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
    private const val MAX_CHUNK_SIZE = 5 * 1024 * 1024 // 5MB Limit

    private lateinit var application: Application
    private lateinit var db: ForgeDatabase
    private lateinit var networkManager: ForgeNetworkManager
    private val gson = Gson()
    private val SHOW_META_KEY = booleanPreferencesKey("show_gallery_meta")
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

    private val _favoritePaths = MutableStateFlow<Set<String>>(emptySet())
    val favoritePaths: StateFlow<Set<String>> = _favoritePaths.asStateFlow()

    internal val _isRestoringPrompt = MutableStateFlow(IndicatorState.IDLE)
    val isRestoringPrompt: StateFlow<IndicatorState> = _isRestoringPrompt.asStateFlow()

    fun cancelPromptRestore() {
        if (_isRestoringPrompt.value == IndicatorState.LOADING) {
            _isRestoringPrompt.value = IndicatorState.IDLE
        }
    }

    // --- FILTERING STATE AND LOGIC (Offloaded from UI) ---
    private val _favoritesSearchQuery = MutableStateFlow("")
    val favoritesSearchQuery: StateFlow<String> = _favoritesSearchQuery.asStateFlow()

    private val _favoritesSortOrder = MutableStateFlow("DESC") // DESC = Najnowsze, ASC = Najstarsze
    val favoritesSortOrder: StateFlow<String> = _favoritesSortOrder.asStateFlow()

    private val _favoritesFilterModels = MutableStateFlow<Set<String>>(emptySet())
    val favoritesFilterModels: StateFlow<Set<String>> = _favoritesFilterModels.asStateFlow()

    val displayedFiles: StateFlow<List<GalleryItem>> =
        combine(
            _galleryFiles,
            _currentGalleryPath,
            _favoritesSearchQuery,
            _favoritesSortOrder,
            _favoritesFilterModels,
        ) { files, path, query, sortOrder, filters ->
            var result = files.toList()

            val isSearchActive = query.isNotBlank() || filters.isNotEmpty()

            if (isSearchActive) {
                // Remove directories from search results
                result = result.filter { !it.isDir }

                if (::db.isInitialized) {
                    val dbMatches =
                        if (query.isNotBlank()) {
                            db
                                .galleryImageDao()
                                .searchImages(query)
                                .map { it.fullpath }
                                .toSet()
                        } else {
                            null
                        }

                    result =
                        result.filter { item ->
                            val matchesQuery = dbMatches?.contains(item.fullpath) ?: true
                            val matchesFilters =
                                if (filters.isNotEmpty()) {
                                    filters.any { model -> item.name.contains(model, ignoreCase = true) }
                                } else {
                                    true
                                }

                            matchesQuery && matchesFilters
                        }
                } else {
                    if (query.isNotBlank()) {
                        result = result.filter { it.name.contains(query, ignoreCase = true) }
                    }
                    if (filters.isNotEmpty()) {
                        result = result.filter { item -> filters.any { model -> item.name.contains(model, ignoreCase = true) } }
                    }
                }
            }

            if (sortOrder == "ASC") {
                val dirs = result.filter { it.isDir }
                val items = result.filter { !it.isDir }.reversed()
                result = dirs + items
            }

            result
        }.flowOn(Dispatchers.IO)
            .stateIn(
                scope = managerScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList(),
            )

    fun init(
        app: Application,
        database: ForgeDatabase,
        network: ForgeNetworkManager,
    ) {
        application = app
        db = database
        networkManager = network

        ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
            val prefs = application.dataStore.data.first()
            _showGalleryMetadata.value = prefs[SHOW_META_KEY] ?: false
            loadFavoritePaths()
        }
    }

    fun setGalleryMode(mode: GalleryMode) {
        _galleryMode.value = mode
    }

    fun setFavoritesSearchQuery(query: String) {
        _favoritesSearchQuery.value = query
    }

    fun setFavoritesSortOrder(order: String) {
        _favoritesSortOrder.value = order
    }

    fun toggleFavoriteFilterModel(model: String) {
        val current = _favoritesFilterModels.value.toMutableSet()
        if (current.contains(model)) current.remove(model) else current.add(model)
        _favoritesFilterModels.value = current
    }

    fun clearFavoriteFilters() {
        _favoritesFilterModels.value = emptySet()
        _favoritesSortOrder.value = "DESC"
        _favoritesSearchQuery.value = ""
    }

    fun toggleGalleryMetadata() {
        val newVal = !_showGalleryMetadata.value
        _showGalleryMetadata.value = newVal
        ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
            application.dataStore.edit { it[SHOW_META_KEY] = newVal }
        }
    }

    fun setShowGalleryMetadata(value: Boolean) {
        _showGalleryMetadata.value = value
    }

    fun setCurrentImageMetadata(value: String?) {
        _currentImageMetadata.value = value
    }

    private suspend fun loadFavoritePaths() {
        val favs = db.favoriteImageDao().getAllFavorites()
        _favoritePaths.value = favs.map { it.fullpath }.toSet()
    }

    fun checkIfFavorite(path: String) {
        if (path.isEmpty()) {
            _isCurrentFavorite.value = false
            return
        }
        ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
            _isCurrentFavorite.value = db.favoriteImageDao().isFavorite(path)
        }
    }

    fun toggleFavorite(item: GalleryItem) {
        ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
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

    private fun encodeFolderPath(path: String?): String? {
        if (path == null) return null
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

                val targetFolder = if (path.isNotEmpty() && path != "Root") encodeFolderPath(path) else null
                val prefix = networkManager.galleryApiPrefix.value
                val response = networkManager.forgeApi?.getGalleryFilesDynamic(url = "$prefix/files", folderPath = targetFolder)
                val configGalleryPath = ForgeRepository.config.value.galleryPath

                if (response?.isSuccessful == true) {
                    val responseBody = response.body()?.string() ?: ""
                    val allItems = parseGalleryItems(responseBody).sortedWith(compareBy({ !it.isDir }, { it.name })).toMutableList()

                    if (path == "Root" || path == configGalleryPath) {
                        allItems.add(0, GalleryItem(name = "⭐ Favorites", fullpath = "virtual://favorites", type = "dir"))
                    }

                    _galleryFiles.value = allItems
                    _currentGalleryPath.value = path

                    if (ForgeRepository.config.value.gallerySyncMode == GallerySyncMode.ON_ENTRY) {
                        syncGalleryDatabase(path, allItems)
                    }
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
        ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
            val items = _galleryFiles.value.filter { !it.isDir }
            syncGalleryDatabase(_currentGalleryPath.value, items)
            withContext(Dispatchers.Main) {
                ForgeRepository.showToast("Gallery Indexed Successfully")
            }
        }
    }

    fun syncGalleryDatabase(
        folderPath: String,
        items: List<GalleryItem>,
    ) {
        if (folderPath == "virtual://favorites" || folderPath == "Root") return
        syncJob?.cancel()
        syncJob =
            managerScope.launch(Dispatchers.IO) {
                val dao = db.galleryImageDao()
                val files = items.filter { !it.isDir }
                val total = files.size
                _gallerySyncProgress.value = 0 to total

                for ((index, file) in files.withIndex()) {
                    if (!isActive) break
                    _gallerySyncProgress.value = index to total

                    val existing = dao.getImageByPath(file.fullpath)
                    if (existing == null) {
                        val prefix = networkManager.galleryApiPrefix.value
                        val request = Request.Builder().url("$prefix/file?path=${encodeFolderPath(file.fullpath)}").build()
                        try {
                            val response = networkManager.client.newCall(request).execute()
                            val stream = response.body.byteStream()
                            val infoStr = extractPngParameters(stream)

                            var posPrompt = ""
                            var negPrompt = ""
                            var model = ""
                            var sampler = ""
                            var seed = ""
                            var loras = ""

                            if (infoStr.isNotEmpty()) {
                                val lines = infoStr.split("\n")
                                if (lines.isNotEmpty()) {
                                    posPrompt =
                                        lines[0].takeIf { !it.startsWith("Negative prompt:") && !it.startsWith("Steps:") } ?: ""
                                }
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
                        } catch (e: Exception) {
                            Log.e(TAG, "Sync failed for ${file.fullpath}: ${e.message}")
                        }
                    }
                }
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

    private fun extractPngParameters(inputStream: InputStream): String {
        try {
            val signature = ByteArray(8)
            if (inputStream.read(signature) != 8) return ""
            val headerBuffer = ByteArray(8)

            while (true) {
                var readH = 0
                while (readH < 8) {
                    val c = inputStream.read(headerBuffer, readH, 8 - readH)
                    if (c == -1) break
                    readH += c
                }
                if (readH != 8) break

                val length = ByteBuffer.wrap(headerBuffer, 0, 4).int
                val chunkType = String(headerBuffer, 4, 4)

                // SAFEGUARD: Protect against corrupted or malicious chunk sizes that could cause an OutOfMemory (OOM) error.
                if (length < 0 || length > MAX_CHUNK_SIZE) {
                    Log.w(TAG, "Skipping suspicious chunk: $chunkType with length $length")
                    break
                }

                if (chunkType == "tEXt" || chunkType == "iTXt") {
                    val chunkData = ByteArray(length)
                    var read = 0
                    while (read < length) {
                        val count = inputStream.read(chunkData, read, length - read)
                        if (count == -1) break
                        read += count
                    }
                    val nullIndex = chunkData.indexOf(0.toByte())
                    if (nullIndex != -1) {
                        val keyword = String(chunkData.copyOfRange(0, nullIndex), Charsets.ISO_8859_1)
                        if (keyword == "parameters") {
                            return String(chunkData.copyOfRange(nullIndex + 1, chunkData.size), Charsets.UTF_8)
                        }
                    }
                    // skip CRC
                    val crc = ByteArray(4)
                    inputStream.read(crc)
                } else if (chunkType == "IDAT" || chunkType == "IEND") {
                    break
                } else {
                    inputStream.skip(length.toLong() + 4)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing PNG chunks", e)
        }
        return ""
    }

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
                                null
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

        val targetFile =
            candidateImages.maxByOrNull { item ->
                item.createdTime?.toDoubleOrNull() ?: item.date?.toDoubleOrNull() ?: 0.0
            }
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
                    val infoStr = tempFile.inputStream().use { extractPngParameters(it) }
                    ForgeQueueManager.saveRecoveredImageToCache(tempFile.readBytes())
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

        ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
            try {
                // 1. Check local cache
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

                // 2. Fetch from server gallery
                val galleryInfoStr = fetchLastGeneratedImageInfo()
                if (galleryInfoStr != null) {
                    val tempFile = java.io.File(application.cacheDir, "last_generated_image.png") // fetchLastGeneratedImageInfo might not save here but we can use the latest session image
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
            } catch (_: Exception) {
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
