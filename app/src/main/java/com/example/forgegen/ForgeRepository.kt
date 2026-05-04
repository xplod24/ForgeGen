@file:Suppress("unused", "MemberVisibilityCanBePrivate", "UNNECESSARY_SAFE_CALL")

package com.example.forgegen

import android.annotation.SuppressLint
import android.app.Application
import android.app.DownloadManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.icu.text.SimpleDateFormat
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/* ============================================================================
 * JETPACK DATASTORE (ASYNC PREFERENCES)
 * ============================================================================ */
val Context.dataStore by preferencesDataStore(name = "forge_settings")

/* ============================================================================
 * IDIOMATIC COROUTINES EXTENSIONS
 * Rozszerzenie pozostawione wyłącznie dla pobierania surowych bajtów obrazów
 * z pominięciem Retrofita.
 * ============================================================================ */

suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response)
        }

        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isCancelled) return
            continuation.resumeWithException(e)
        }
    })

    continuation.invokeOnCancellation {
        try { cancel() } catch (_: Throwable) { /* Ignore */ }
    }
}

data class ActiveLora(val name: String, val strength: Float)

/* ============================================================================
 * DATA REPOSITORY (SINGLETON)
 * Handles API orchestration, state management, background battery optimization,
 * queue failsafe mechanisms, and background service delegation.
 * ============================================================================ */

@SuppressLint("StaticFieldLeak")
object ForgeRepository {

    private const val TAG = "ForgeAPI"

    private lateinit var application: Application
    private lateinit var db: ForgeDatabase
    private val gson = Gson()

    // RETROFIT APIS
    private var forgeApi: ForgeApi? = null

    // Klient z Interceptorem specjalnie pod Civitai (zabezpiecza Logcat przed śmieciami z A1111)
    private val civitaiApi: CivitaiApi by lazy {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        val civitaiClient = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl("https://civitai.com/")
            .client(civitaiClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(CivitaiApi::class.java)
    }

    private val updateApi: UpdateApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://xplod24.ddns.net/") // Wymagane przez Retrofit, nadpisywane przez @Url
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(UpdateApi::class.java)
    }

    private val CONFIG_KEY = stringPreferencesKey("config")
    private val STATE_KEY = stringPreferencesKey("last_state")
    private val HISTORY_KEY = stringPreferencesKey("prompt_history")
    private val QUEUE_KEY = stringPreferencesKey("saved_queue")
    private val STATS_KEY = stringPreferencesKey("saved_server_stats")
    private val SHOW_META_KEY = booleanPreferencesKey("show_gallery_meta")

    val repositoryScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // --- EVENT BUS DLA SNACKBARÓW ---
    private val _snackbarMessage = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val snackbarMessage: SharedFlow<String> = _snackbarMessage.asSharedFlow()

    fun showSnackbar(message: String) {
        _snackbarMessage.tryEmit(message)
    }

    private val _config = MutableStateFlow(AppConfig())
    val config: StateFlow<AppConfig> = _config.asStateFlow()

    var client: OkHttpClient = OkHttpClient()
        private set

    private val _appState = MutableStateFlow(AppState())
    val appState: StateFlow<AppState> = _appState.asStateFlow()

    private val _promptHistory = MutableStateFlow<List<PromptHistoryItem>>(emptyList())
    val promptHistory: StateFlow<List<PromptHistoryItem>> = _promptHistory.asStateFlow()

    private val _promptStyles = MutableStateFlow<List<PromptStyleEntity>>(emptyList())
    val promptStyles: StateFlow<List<PromptStyleEntity>> = _promptStyles.asStateFlow()

    @Suppress("RedundantCollectionOperation")
    val activeLoras: StateFlow<List<ActiveLora>> = _appState.map { state ->
        val regex = Regex("<lora:([^:]+):([0-9.]+)>")
        regex.findAll(state.positivePrompt).map { match ->
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

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _currentEta = MutableStateFlow(0.0)
    val currentEta: StateFlow<Double> = _currentEta.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _statusText = MutableStateFlow("Ready")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

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

    private val _livePreviewImage = MutableStateFlow<String?>(null)
    val livePreviewImage: StateFlow<String?> = _livePreviewImage.asStateFlow()

    private val _isShowingGridPreview = MutableStateFlow(false)
    val isShowingGridPreview: StateFlow<Boolean> = _isShowingGridPreview.asStateFlow()

    private val _currentBatchStartIndex = MutableStateFlow(0)
    val currentBatchStartIndex: StateFlow<Int> = _currentBatchStartIndex.asStateFlow()

    private val _currentBatchEndIndex = MutableStateFlow(-1)
    val currentBatchEndIndex: StateFlow<Int> = _currentBatchEndIndex.asStateFlow()

    private val _tagSuggestions = MutableStateFlow<List<String>>(emptyList())
    val tagSuggestions: StateFlow<List<String>> = _tagSuggestions.asStateFlow()

    // Używamy nowego, graficznego enuma dla animacji z MainComponents.kt
    private val _isRestoringPrompt = MutableStateFlow(IndicatorState.IDLE)
    val isRestoringPrompt: StateFlow<IndicatorState> = _isRestoringPrompt.asStateFlow()

    private val _selectedModel = MutableStateFlow("")
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()

    private val _samplers = MutableStateFlow<List<String>>(emptyList())
    val samplers: StateFlow<List<String>> = _samplers.asStateFlow()

    private val _schedulers = MutableStateFlow<List<String>>(emptyList())
    val schedulers: StateFlow<List<String>> = _schedulers.asStateFlow()

    private val _models = MutableStateFlow<List<ApiResource>>(emptyList())
    val models: StateFlow<List<ApiResource>> = _models.asStateFlow()

    private val _upscalers = MutableStateFlow<List<String>>(emptyList())
    val upscalers: StateFlow<List<String>> = _upscalers.asStateFlow()

    private val _availableLoras = MutableStateFlow<List<ApiResource>>(emptyList())
    val availableLoras: StateFlow<List<ApiResource>> = _availableLoras.asStateFlow()

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

    private val _favoritePaths = MutableStateFlow<Set<String>>(emptySet())
    val favoritePaths: StateFlow<Set<String>> = _favoritePaths.asStateFlow()

    private val _updateManifest = MutableStateFlow<UpdateManifest?>(null)
    val updateManifest: StateFlow<UpdateManifest?> = _updateManifest.asStateFlow()

    private val _isUpdateDownloading = MutableStateFlow(false)
    val isUpdateDownloading: StateFlow<Boolean> = _isUpdateDownloading.asStateFlow()

    private val _updateDownloadProgress = MutableStateFlow(0f)
    val updateDownloadProgress: StateFlow<Float> = _updateDownloadProgress.asStateFlow()

    // --- CIVITAI SYNC STATES ---
    private val _isCivitaiSyncing = MutableStateFlow(IndicatorState.IDLE)
    val isCivitaiSyncing: StateFlow<IndicatorState> = _isCivitaiSyncing.asStateFlow()

    private val _civitaiSyncCurrentModel = MutableStateFlow("")
    val civitaiSyncCurrentModel: StateFlow<String> = _civitaiSyncCurrentModel.asStateFlow()

    private val _civitaiSyncProgress = MutableStateFlow(0 to 0) // (aktualny, wszystkie)
    val civitaiSyncProgress: StateFlow<Pair<Int, Int>> = _civitaiSyncProgress.asStateFlow()

    private val _civitaiSyncLastResult = MutableStateFlow<String?>(null)
    val civitaiSyncLastResult: StateFlow<String?> = _civitaiSyncLastResult.asStateFlow()

    private var isInitialized = false

    private fun rebuildForgeApi(url: String) {
        var cleanUrl = url.trimEnd('/')
        if (cleanUrl.isNotEmpty() && !cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            cleanUrl = "http://$cleanUrl"
        }
        if (cleanUrl.isEmpty()) return

        try {
            val retrofitForge = Retrofit.Builder()
                .baseUrl("$cleanUrl/")
                .client(client)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build()
            forgeApi = retrofitForge.create(ForgeApi::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ForgeApi with URL: $cleanUrl", e)
        }
    }

    fun init(app: Application) {
        if (isInitialized) return
        application = app

        db = Room.databaseBuilder(app, ForgeDatabase::class.java, "forge_db")
            .fallbackToDestructiveMigration()
            .build()

        repositoryScope.launch(Dispatchers.IO) {
            val prefs = application.dataStore.data.first()
            _config.value = loadConfig(prefs)
            _appState.value = loadState(prefs)
            _promptHistory.value = loadPromptHistory(prefs)
            _showGalleryMetadata.value = prefs[SHOW_META_KEY] ?: false

            client = createClient(_config.value.connectionTimeout)
            rebuildForgeApi(_config.value.apiUrl)

            loadFavoritePaths()
            loadPromptStyles()
            loadQueueState(prefs)
            loadServerStats(prefs)
            manageServiceState(_config.value.enablePersistentService)

            startBackgroundPing()
            startStatsMaintenance()
            fetchApiData()
            startQueueManager()
            cleanupRecoveredImages()

            checkForUpdates(manual = false)
            isInitialized = true
        }
    }

    fun checkForUpdates(manual: Boolean = false) {
        repositoryScope.launch(Dispatchers.IO) {
            try {
                val updateBaseUrl = "https://xplod24.ddns.net"
                val channel = _config.value.updateChannel
                val updatePath = if (channel.equals("Beta", ignoreCase = true)) "/beta/update.json" else "/release/update.json"
                val fullUrl = "$updateBaseUrl$updatePath"

                val token = if (channel.equals("Beta", ignoreCase = true)) {
                    val currentToken = _config.value.betaToken
                    if (currentToken.isEmpty()) {
                        Log.w(TAG, "BRAK TOKENU BETA! Zapytanie zostanie odrzucone przez Nginx (403).")
                        if (manual) showSnackbar("Brak tokenu Beta w ustawieniach!")
                    }
                    currentToken
                } else null

                val response = updateApi.getUpdateManifest(fullUrl, token)

                if (response.isSuccessful) {
                    val manifestDto = response.body() ?: return@launch
                    val manifest = manifestDto.toDomain()

                    val pInfo = application.packageManager.getPackageInfo(application.packageName, 0)
                    val currentVersionCode = if (Build.VERSION.SDK_INT >= 28) pInfo.longVersionCode.toInt() else pInfo.versionCode

                    if (!manifest.channel.equals(channel, ignoreCase = true)) {
                        Log.e(TAG, "BŁĄD SPÓJNOŚCI KANAŁU: Użytkownik jest na kanale '$channel', a JSON pobrany z serwera należy do kanału '${manifest.channel}'!")
                        if (manual) showSnackbar("Błąd serwera: Znaleziono wersję ${manifest.channel} w folderze kanału $channel. Zgłoś to administratorowi.")
                        return@launch
                    }

                    if (manifest.versionCode > currentVersionCode) {
                        _updateManifest.value = manifest
                        if (manual) showSnackbar("Dostępna aktualizacja: ${manifest.versionName}")
                    } else {
                        if (manual) showSnackbar("Aplikacja jest aktualna (Lokalna: $currentVersionCode, Serwer: ${manifest.versionCode})")
                    }
                } else {
                    Log.e(TAG, "BŁĄD SIECI: Serwer odrzucił połączenie. Kod HTTP: ${response.code()}")
                    if (manual) {
                        val msg = if (response.code() == 403) "Odmowa dostępu (HTTP 403). Sprawdź token Beta!" else "Błąd serwera (HTTP ${response.code()})"
                        showSnackbar(msg)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Wyjątek podczas sprawdzania aktualizacji: ", e)
                if (manual) showSnackbar("Błąd połączenia: ${e.message}")
            }
        }
    }

    fun downloadAndInstallUpdate() {
        val manifest = _updateManifest.value ?: return
        if (_isUpdateDownloading.value) return

        try {
            val oldFile = File(application.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "ForgeGen_Update.apk")
            if (oldFile.exists()) {
                oldFile.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Błąd podczas usuwania starego pliku APK przed nowym pobraniem", e)
        }

        _isUpdateDownloading.value = true
        _updateDownloadProgress.value = 0f

        val downloadManager = application.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = Uri.parse(manifest.url)
        val request = DownloadManager.Request(uri).apply {
            setTitle("ForgeGen Update")
            setDescription("Downloading version ${manifest.versionName}")
            setDestinationInExternalFilesDir(application, Environment.DIRECTORY_DOWNLOADS, "ForgeGen_Update.apk")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)

            if (manifest.channel.equals("Beta", ignoreCase = true) && _config.value.betaToken.isNotEmpty()) {
                addRequestHeader("Beta-Tester", _config.value.betaToken)
            }
        }

        val downloadId = downloadManager.enqueue(request)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    application.unregisterReceiver(this)
                    _isUpdateDownloading.value = false

                    val query = DownloadManager.Query().setFilterById(downloadId)
                    downloadManager.query(query).use { cursor ->
                        if (cursor.moveToFirst()) {
                            val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                            if (statusIndex != -1 && cursor.getInt(statusIndex) == DownloadManager.STATUS_SUCCESSFUL) {
                                _updateDownloadProgress.value = 1f
                                verifyAndInstallApk(manifest.sha256)
                            } else {
                                showSnackbar("Pobieranie nie powiodło się")
                            }
                        }
                    }
                }
            }
        }

        ContextCompat.registerReceiver(
            application,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    private fun verifyAndInstallApk(expectedSha256: String) {
        repositoryScope.launch(Dispatchers.IO) {
            try {
                val file = File(application.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "ForgeGen_Update.apk")
                if (!file.exists()) {
                    showSnackbar("Update file missing")
                    return@launch
                }

                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { fis ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                        digest.update(buffer, 0, bytesRead)
                    }
                }
                val hashBytes = digest.digest()
                val calculatedSha256 = hashBytes.joinToString("") { "%02x".format(it) }

                if (calculatedSha256.equals(expectedSha256, ignoreCase = true)) {
                    val installUri = FileProvider.getUriForFile(application, "${application.packageName}.fileprovider", file)
                    val installIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(installUri, "application/vnd.android.package-archive")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                    }
                    withContext(Dispatchers.Main) {
                        application.startActivity(installIntent)
                    }
                } else {
                    file.delete()
                    showSnackbar("Security Error: Checksum mismatch. File deleted.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to verify/install update", e)
                showSnackbar("Update failed: ${e.message}")
            }
        }
    }

    suspend fun getTagsForLora(hash: String): List<String> {
        return withContext(Dispatchers.IO) {
            val entity = db.civitaiModelDao().getModelByHash(hash)
            entity?.trainedWords?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        }
    }

    private fun loadPromptStyles() {
        repositoryScope.launch(Dispatchers.IO) {
            _promptStyles.value = db.promptStyleDao().getAllStyles()
        }
    }

    fun savePromptStyle(name: String, positivePrompt: String, negativePrompt: String) {
        repositoryScope.launch(Dispatchers.IO) {
            db.promptStyleDao().insertStyle(
                PromptStyleEntity(name = name, positivePrompt = positivePrompt, negativePrompt = negativePrompt)
            )
            loadPromptStyles()
        }
    }

    fun deletePromptStyle(style: PromptStyleEntity) {
        repositoryScope.launch(Dispatchers.IO) {
            db.promptStyleDao().deleteStyle(style)
            loadPromptStyles()
        }
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
                        savedAt = System.currentTimeMillis()
                    )
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

            val compactedOlder = older.groupBy { it.timestamp / 60000L }.map { (minuteBucket, records) ->
                ServerStatRecord(
                    timestamp = minuteBucket * 60000L,
                    pingMs = records.map { it.pingMs }.average().toLong(),
                    ramUsed = records.map { it.ramUsed }.average(),
                    ramTotal = records.map { it.ramTotal }.average(),
                    vramUsed = records.map { it.vramUsed }.average(),
                    vramTotal = records.map { it.vramTotal }.average()
                )
            }.sortedBy { it.timestamp }

            compactedOlder + recent.sortedBy { it.timestamp }
        }
    }

    private fun loadServerStats(prefs: Preferences? = null) {
        repositoryScope.launch(Dispatchers.IO) {
            val p = prefs ?: application.dataStore.data.first()
            val json = p[STATS_KEY]
            if (!json.isNullOrEmpty()) {
                try {
                    val type = object : TypeToken<List<ServerStatRecord>>() {}.type
                    val loaded: List<ServerStatRecord> = gson.fromJson(json, type)
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
                application.dataStore.edit { it[STATS_KEY] = gson.toJson(_serverStats.value) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save stats", e)
        }
    }

    private fun manageServiceState(enablePersistent: Boolean) {
        val serviceIntent = Intent(application, GenerationService::class.java).apply {
            action = "ACTION_UPDATE_PERSISTENCE"
        }
        try {
            application.startService(serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service", e)
        }
    }

    private fun loadQueueState(prefs: Preferences? = null) {
        repositoryScope.launch(Dispatchers.IO) {
            val p = prefs ?: application.dataStore.data.first()
            val json = p[QUEUE_KEY]
            if (!json.isNullOrEmpty()) {
                try {
                    val type = object : TypeToken<List<QueuedGeneration>>() {}.type
                    val q: List<QueuedGeneration> = gson.fromJson(json, type)
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

    private fun saveQueueState() {
        try {
            repositoryScope.launch(Dispatchers.IO) {
                application.dataStore.edit { it[QUEUE_KEY] = gson.toJson(_generationQueue.value) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save queue", e)
        }
    }

    private fun cleanupRecoveredImages() {
        repositoryScope.launch(Dispatchers.IO) {
            try {
                application.cacheDir.listFiles()?.forEach { file ->
                    if (file.name.startsWith("recovered_") && file.name.endsWith(".png")) {
                        file.delete()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning up recovered cache", e)
            }
        }
    }

    private suspend fun saveRecoveredImageToCache(bytes: ByteArray) {
        withContext(Dispatchers.IO) {
            try {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

                options.inSampleSize = calculateInSampleSize(options)
                options.inJustDecodeBounds = false

                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                val file = File(application.cacheDir, "recovered_${System.currentTimeMillis()}.png")

                FileOutputStream(file).use { out ->
                    bitmap?.compress(Bitmap.CompressFormat.PNG, 85, out)
                }
                bitmap?.recycle()

                _sessionImages.value = listOf(file.absolutePath)
                _currentSessionIndex.value = 0
                _currentBatchStartIndex.value = 0
                _currentBatchEndIndex.value = 0
                _isShowingGridPreview.value = false
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cache recovered image", e)
            }
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options): Int {
        val reqHeight = 512
        val reqWidth = 512
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
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

    private fun createClient(timeoutSeconds: Int): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
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
            }
            .build()
    }

    private fun loadConfig(prefs: Preferences): AppConfig {
        val json = prefs[CONFIG_KEY]
        val parsed = if (json != null) {
            try { gson.fromJson(json, AppConfig::class.java) } catch(_: Exception) { null }
        } else null

        val oldLivePreviewState = try {
            val jsonObj = org.json.JSONObject(json ?: "{}")
            jsonObj.optBoolean("livePreviews", false)
        } catch(_: Exception) { false }

        val finalPreviewMode = parsed?.previewMode ?: if (oldLivePreviewState) "Normal" else "Finished"

        return AppConfig(
            apiUrl = parsed?.apiUrl ?: "http://192.168.1.90:7860",
            serverBasePath = parsed?.serverBasePath ?: "",
            galleryPath = parsed?.galleryPath ?: "",
            isDarkMode = parsed?.isDarkMode ?: false,
            connectionTimeout = parsed?.connectionTimeout ?: 10,
            checkpointTimeout = parsed?.checkpointTimeout ?: 45,
            receiveGenerationNotification = parsed?.receiveGenerationNotification ?: true,
            notifImagePreview = parsed?.notifImagePreview ?: true,
            notifQueueStatus = parsed?.notifQueueStatus ?: false,
            notificationPriority = parsed?.notificationPriority ?: "Normal",
            notificationMode = parsed?.notificationMode ?: "Simple",
            keepScreenOn = parsed?.keepScreenOn ?: false,
            enablePersistentService = parsed?.enablePersistentService ?: false,
            swipeToBrowseGallery = parsed?.swipeToBrowseGallery ?: true,
            bottomSheetExpandedByDefault = parsed?.bottomSheetExpandedByDefault ?: false,
            serverProfiles = parsed?.serverProfiles ?: listOf(ServerProfile("Default Local", "http://192.168.1.90:7860")),
            previewMode = finalPreviewMode,
            useNativeSecurity = parsed?.useNativeSecurity ?: false,
            useBiometricLock = parsed?.useBiometricLock ?: false,
            overnightMode = parsed?.overnightMode ?: false,
            showGridAfterGeneration = parsed?.showGridAfterGeneration ?: true,
            showActiveTagsUI = parsed?.showActiveTagsUI ?: true,
            updateChannel = parsed?.updateChannel ?: "Stable",
            betaToken = parsed?.betaToken ?: "",
            defaultState = parsed?.defaultState ?: AppState(),
            presets = parsed?.presets ?: emptyList(),
        )
    }

    fun saveConfig(newConfig: AppConfig) {
        var cleanUrl = newConfig.apiUrl.trim()
        if (cleanUrl.isNotEmpty() && !cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            cleanUrl = "http://$cleanUrl"
        }

        val oldPersistent = _config.value.enablePersistentService
        val oldUrl = _config.value.apiUrl
        val updatedConfig = newConfig.copy(apiUrl = cleanUrl)

        _config.value = updatedConfig

        repositoryScope.launch(Dispatchers.IO) {
            application.dataStore.edit { it[CONFIG_KEY] = gson.toJson(updatedConfig) }
        }

        client = client.newBuilder()
            .connectTimeout(updatedConfig.connectionTimeout.toLong(), TimeUnit.SECONDS)
            .build()

        if (updatedConfig.enablePersistentService != oldPersistent) {
            manageServiceState(updatedConfig.enablePersistentService)
        }

        if (cleanUrl != oldUrl) {
            rebuildForgeApi(cleanUrl)
            fetchApiData()
        }
    }

    fun saveCurrentAsDefault() {
        val currentConfig = _config.value
        val newState = currentConfig.copy(defaultState = _appState.value.copy())
        saveConfig(newState)
        showSnackbar("Set Current as Default")
    }

    fun resetToDefaults() {
        _appState.value = _config.value.defaultState.copy()
        repositoryScope.launch(Dispatchers.IO) {
            application.dataStore.edit { it[STATE_KEY] = gson.toJson(_appState.value) }
        }
        showSnackbar("Reset to Defaults")
    }

    fun savePreset(name: String) {
        val currentPresets = _config.value.presets.toMutableList()
        currentPresets.removeAll { it.name == name }
        currentPresets.add(GenerationPreset(name, _appState.value.copy()))
        saveConfig(_config.value.copy(presets = currentPresets))
    }

    fun loadPreset(name: String) {
        val preset = _config.value.presets.find { it.name == name }
        if (preset != null) {
            _appState.value = preset.state.copy()
            repositoryScope.launch(Dispatchers.IO) {
                application.dataStore.edit { it[STATE_KEY] = gson.toJson(_appState.value) }
            }
            showSnackbar("Loaded: $name")
        }
    }

    fun deletePreset(name: String) {
        val currentPresets = _config.value.presets.toMutableList()
        currentPresets.removeAll { it.name == name }
        saveConfig(_config.value.copy(presets = currentPresets))
    }

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

                        val newConfig = _config.value.copy(
                            serverBasePath = sdCwd,
                            galleryPath = galleryPath
                        )
                        saveConfig(newConfig)
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

    fun getPreviewUrl(originalPath: String, isLora: Boolean = false): String {
        if (originalPath.isEmpty()) return ""
        if (originalPath.startsWith("http://") || originalPath.startsWith("https://")) {
            return originalPath
        }

        val urlStr = _config.value.apiUrl.trimEnd('/')

        val sdCwd = _config.value.serverBasePath
        val fullPath = if (sdCwd.isNotEmpty() && !originalPath.contains("\\") && !originalPath.contains("/")) {
            val separator = if (sdCwd.contains("\\")) "\\" else "/"
            val subDir = if (isLora) "models${separator}Lora" else "models${separator}Stable-diffusion"
            "$sdCwd$separator$subDir$separator$originalPath"
        } else {
            originalPath
        }

        val basePath = fullPath.substringBeforeLast(".safetensors").substringBeforeLast(".ckpt").substringBeforeLast(".pt")
        return "$urlStr/file=$basePath.preview.png"
    }

    fun addServerProfile(name: String, url: String) {
        val currentProfiles = _config.value.serverProfiles.toMutableList()
        currentProfiles.removeAll { it.name == name }
        currentProfiles.add(ServerProfile(name, url))
        saveConfig(_config.value.copy(serverProfiles = currentProfiles))
    }

    fun removeServerProfile(name: String) {
        val currentProfiles = _config.value.serverProfiles.toMutableList()
        currentProfiles.removeAll { it.name == name }
        saveConfig(_config.value.copy(serverProfiles = currentProfiles))
    }

    private fun loadState(prefs: Preferences): AppState {
        val json = prefs[STATE_KEY]
        val parsed = if (json != null) {
            try { gson.fromJson(json, AppState::class.java) } catch(_: Exception) { null }
        } else null

        if (parsed != null) return parsed
        return _config.value.defaultState.copy()
    }

    fun updateState(update: (AppState) -> AppState) {
        val newState = update(_appState.value)
        _appState.value = newState
        repositoryScope.launch(Dispatchers.IO) {
            application.dataStore.edit { it[STATE_KEY] = gson.toJson(newState) }
        }
    }

    private fun loadPromptHistory(prefs: Preferences): List<PromptHistoryItem> {
        val json = prefs[HISTORY_KEY]
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val type = object : TypeToken<List<PromptHistoryItem>>() {}.type
            gson.fromJson(json, type)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveToPromptHistory(positive: String, negative: String) {
        if (positive.isBlank() && negative.isBlank()) return

        val currentList = _promptHistory.value.toMutableList()
        if (currentList.isNotEmpty() && currentList.first().positivePrompt == positive && currentList.first().negativePrompt == negative) {
            return
        }

        val newItem = PromptHistoryItem(positive, negative, System.currentTimeMillis())
        currentList.add(0, newItem)

        val trimmedList = currentList.take(20)
        _promptHistory.value = trimmedList

        repositoryScope.launch(Dispatchers.IO) {
            application.dataStore.edit { it[HISTORY_KEY] = gson.toJson(trimmedList) }
        }
    }

    fun clearPromptHistory() {
        _promptHistory.value = emptyList()
        repositoryScope.launch(Dispatchers.IO) {
            application.dataStore.edit { it.remove(HISTORY_KEY) }
        }
    }

    fun resumeQueue() {
        _isQueuePaused.value = false
        _oomAlert.value = false
        _statusText.value = "Queue Resumed"
    }

    fun interruptGeneration() {
        repositoryScope.launch(Dispatchers.IO) {
            try {
                forgeApi?.interruptGeneration()
                _statusText.value = "Interrupting..."
            } catch (e: Exception) {
                Log.e(TAG, "Failed to interrupt", e)
            }
        }
    }

    private fun extractPngParameters(bytes: ByteArray): String {
        try {
            if (bytes.size < 8) return ""
            var offset = 8
            while (offset < bytes.size - 8) {
                val length = ByteBuffer.wrap(bytes, offset, 4).int
                offset += 4
                val chunkType = String(bytes, offset, 4)
                offset += 4
                if (chunkType == "tEXt" || chunkType == "iTXt") {
                    val chunkData = bytes.copyOfRange(offset, offset + length)
                    val nullIndex = chunkData.indexOf(0.toByte())
                    if (nullIndex != -1) {
                        val keyword = String(chunkData.copyOfRange(0, nullIndex), Charsets.ISO_8859_1)
                        if (keyword == "parameters") {
                            return String(chunkData.copyOfRange(nullIndex + 1, chunkData.size), Charsets.UTF_8)
                        }
                    }
                }
                offset += length + 4
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing PNG chunks", e)
        }
        return ""
    }

    suspend fun extractMetadataFromUri(uri: Uri): String? {
        return withContext(Dispatchers.IO) {
            try {
                val bytes = application.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null) {
                    val infoStr = extractPngParameters(bytes)
                    infoStr.takeIf { it.isNotBlank() }
                } else null
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read URI for metadata", e)
                null
            }
        }
    }

    private fun startQueueManager() {
        repositoryScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val queue = _generationQueue.value
                    if (queue.isNotEmpty() && !_isGenerating.value && !_isServerBusy.value && !_isQueuePaused.value) {
                        _isGenerating.value = true
                        executeGeneration(queue.first())
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Queue Manager Exception", e)
                    _isGenerating.value = false
                }
                delay(500)
            }
        }
    }

    fun queueGeneration() {
        val state = _appState.value
        val currentModel = _selectedModel.value.ifEmpty { null }

        val payload = Txt2ImgPayloadDto(
            prompt = state.positivePrompt,
            negative_prompt = state.negativePrompt,
            steps = state.steps,
            cfg_scale = state.cfgScale,
            width = state.width,
            height = state.height,
            n_iter = state.batchCount,
            batch_size = state.batchSize,
            seed = state.seed,
            sampler_name = state.sampler,
            scheduler = state.scheduler,
            override_settings = OverrideSettingsDto(
                clipSkip = state.clipSkip,
                sdModelCheckpoint = currentModel
            ),
            enable_hr = state.hiresFix,
            hr_scale = state.hiresScale,
            hr_upscaler = state.upscaler,
            denoising_strength = state.denoising,
            save_images = state.saveImages,
            send_images = true
        )

        val item = QueuedGeneration(
            id = UUID.randomUUID().toString(),
            positivePrompt = state.positivePrompt,
            payload = payload
        )

        _generationQueue.update { it + item }
        saveQueueState()

        val qSize = _generationQueue.value.size
        if (qSize == 1) {
            _totalQueueSize.value = 1
            _completedQueueItems.value = 0

            if (!_isGenerating.value && !_isServerBusy.value && !_isQueuePaused.value) {
                _isGenerating.value = true
                repositoryScope.launch(Dispatchers.IO) {
                    executeGeneration(item)
                }
            }
        } else {
            _totalQueueSize.update { it + 1 }
        }

        saveToPromptHistory(state.positivePrompt, state.negativePrompt)

        if (_isServerBusy.value && qSize > 1) {
            showSnackbar("External generation active. Added to queue.")
        } else if (qSize > 1) {
            showSnackbar("Added to queue.")
        }
    }

    fun updateQueueItem(id: String, positivePrompt: String, negativePrompt: String) {
        _generationQueue.update { currentQueue ->
            currentQueue.map {
                if (it.id == id) {
                    it.copy(
                        positivePrompt = positivePrompt,
                        payload = it.payload.copy(prompt = positivePrompt, negative_prompt = negativePrompt)
                    )
                } else it
            }
        }
        saveQueueState()
    }

    fun clearQueue() {
        _generationQueue.value = emptyList()
        _totalQueueSize.value = 0
        _completedQueueItems.value = 0
        saveQueueState()
    }

    fun removeFromQueue(id: String) {
        _generationQueue.update { currentQueue ->
            val prevSize = currentQueue.size
            val newQueue = currentQueue.filter { it.id != id }
            if (newQueue.size < prevSize) {
                _totalQueueSize.update { maxOf(_completedQueueItems.value, it - 1) }
            }
            newQueue
        }
        saveQueueState()
    }

    fun moveQueueItemUp(id: String) {
        _generationQueue.update { q ->
            val idx = q.indexOfFirst { it.id == id }
            if (idx > 0) {
                val list = q.toMutableList()
                Collections.swap(list, idx, idx - 1)
                list
            } else q
        }
        saveQueueState()
    }

    fun moveQueueItemDown(id: String) {
        _generationQueue.update { q ->
            val idx = q.indexOfFirst { it.id == id }
            if (idx in 0 until q.size - 1) {
                val list = q.toMutableList()
                Collections.swap(list, idx, idx + 1)
                list
            } else q
        }
        saveQueueState()
    }

    private suspend fun executeGeneration(job: QueuedGeneration) {
        _lastPromptState.value = _appState.value.copy()
        val shouldSaveToDevice = _appState.value.saveToDevice

        _progress.value = 0f
        _currentEta.value = 0.0
        _livePreviewImage.value = null
        _isShowingGridPreview.value = false

        val previewText = job.positivePrompt.take(30).replace("\n", " ")
        val initialBatchInfo = if (job.payload.n_iter > 1) "(Batch 1 of ${job.payload.n_iter}) " else ""
        _statusText.value = "Preparing $initialBatchInfo\"$previewText...\""

        val serviceIntent = Intent(application, GenerationService::class.java).apply {
            action = "ACTION_START_GENERATION"
        }
        try {
            application.startForegroundService(serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
        }

        try {
            val response = forgeApi?.generateImage(job.payload)
            if (response?.isSuccessful == true) {
                val txt2ImgData = response.body() ?: Txt2ImgResponseDto()

                if (txt2ImgData.images.isNotEmpty()) {
                    val currentList = _sessionImages.value.toMutableList()
                    val startIndex = currentList.size

                    val cachePath = application.cacheDir.absolutePath

                    for ((i, b64) in txt2ImgData.images.withIndex()) {
                        val bytes = Base64.decode(b64, Base64.DEFAULT)
                        val file = File("$cachePath/gen_${System.currentTimeMillis()}_$i.png")
                        file.writeBytes(bytes)
                        currentList.add(file.absolutePath)

                        if (shouldSaveToDevice) {
                            try {
                                val fileName = "Gen_${System.currentTimeMillis()}_$i.png"
                                val contentValues = ContentValues().apply {
                                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ForgeGen")
                                }

                                val resolver = application.contentResolver
                                val insertUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                                val uri = resolver.insert(insertUri, contentValues)

                                if (uri != null) {
                                    resolver.openOutputStream(uri)?.use { it.write(bytes) }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to save generated image directly to device", e)
                            }
                        }
                    }

                    val endIndex = currentList.size - 1

                    _sessionImages.value = currentList
                    _currentBatchStartIndex.value = startIndex
                    _currentBatchEndIndex.value = endIndex
                    _currentSessionIndex.value = endIndex
                    _statusText.value = "Generation Complete"
                    _livePreviewImage.value = null

                    if (_config.value.showGridAfterGeneration && txt2ImgData.images.size > 1) {
                        _isShowingGridPreview.value = true
                    }

                    if (_config.value.receiveGenerationNotification) {
                        try {
                            val notifManager = application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                            val openIntent = Intent(application, MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            }
                            val pendingIntent = PendingIntent.getActivity(application, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

                            val channelId = when (_config.value.notificationPriority) {
                                "High" -> "forge_high"
                                "Low" -> "forge_low"
                                else -> "forge_default"
                            }

                            val builder = NotificationCompat.Builder(application, channelId)
                                .setSmallIcon(R.mipmap.ic_launcher_foreground)
                                .setContentTitle("Batch Completed")
                                .setContentText("Finished: ${job.positivePrompt.take(35)}...")
                                .setContentIntent(pendingIntent)
                                .setAutoCancel(true)

                            val lastImage = currentList.lastOrNull()
                            if (lastImage != null && _config.value.notifImagePreview) {
                                try {
                                    val bitmap = BitmapFactory.decodeFile(lastImage)
                                    if (bitmap != null) {
                                        builder.setLargeIcon(bitmap)
                                        builder.setStyle(
                                            NotificationCompat.BigPictureStyle()
                                                .bigPicture(bitmap)
                                                .bigLargeIcon(null as Bitmap?)
                                                .setBigContentTitle("Batch Completed")
                                                .setSummaryText(job.positivePrompt.take(100))
                                        )
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to load BigPicture for notification", e)
                                }
                            }

                            val uniqueNotifId = System.currentTimeMillis().toInt()
                            notifManager.notify(uniqueNotifId, builder.build())
                        } catch (e: Exception) {
                            Log.e(TAG, "Notification Launch Failed", e)
                        }
                    }
                }

            } else {
                val errorBody = response?.errorBody()?.string() ?: ""
                if (response?.code() == 500 || errorBody.contains("OutOfMemoryError", true) || errorBody.contains("CUDA out of memory", true)) {
                    _statusText.value = "SERVER OUT OF MEMORY (OOM)"
                    _isQueuePaused.value = true
                    _oomAlert.value = true
                } else {
                    _statusText.value = "Error: ${response?.code()}"
                    if (!_config.value.overnightMode) _isQueuePaused.value = true
                }
            }
        } catch (e: Exception) {
            _statusText.value = "Failed: ${e.localizedMessage}"
            if (!_config.value.overnightMode) {
                _isQueuePaused.value = true
            }
        } finally {
            _generationQueue.update { q -> q.filter { it.id != job.id } }
            _completedQueueItems.update { it + 1 }

            if (_generationQueue.value.isEmpty()) {
                _totalQueueSize.value = 0
                _completedQueueItems.value = 0

                val finishIntent = Intent(application, GenerationService::class.java).apply {
                    action = "ACTION_QUEUE_FINISHED"
                }
                try {
                    application.startService(finishIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to notify service of queue finish", e)
                }
            }

            saveQueueState()

            _isGenerating.value = false
            _progress.value = 1f
            _currentEta.value = 0.0
        }
    }

    private fun startBackgroundPing() {
        repositoryScope.launch(Dispatchers.IO) {
            var failCount = 0
            while (isActive) {
                try {
                    if (forgeApi != null) {
                        val start = System.currentTimeMillis()
                        val skipImage = _config.value.previewMode != "Normal"

                        val response = forgeApi?.getProgress(skipImage)

                        if (response?.isSuccessful == true) {
                            _pingMs.value = System.currentTimeMillis() - start
                            _isConnected.value = true
                            failCount = 0

                            val progressData = response.body() ?: ProgressResponseDto()
                            val progressVal = progressData.progress.toFloat()
                            val etaVal = progressData.etaRelative
                            val jobCount = progressData.state?.jobCount ?: 0

                            val currentImageStr = progressData.currentImage ?: ""
                            if (currentImageStr.isNotEmpty() && !skipImage) {
                                _livePreviewImage.value = currentImageStr
                            } else {
                                if (!_isGenerating.value) _livePreviewImage.value = null
                            }

                            val busy = progressVal > 0.001f || jobCount > 0
                            _isServerBusy.value = busy
                            _progress.value = progressVal
                            _currentEta.value = etaVal

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
                                val prefix = if (_isGenerating.value) "Generating" else "External Task"
                                _statusText.value = "$prefix $batchInfo... ${(progressVal * 100).toInt()}%"
                            } else if (!busy && !_isGenerating.value) {
                                _statusText.value = "Ready"
                            }
                        } else if (response?.code() == 401 || response?.code() == 403) {
                            _isConnected.value = false
                            _isServerBusy.value = false
                            _currentEta.value = 0.0
                            failCount = 0
                            _statusText.value = "Authentication Required."
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
                                        memStr += "RAM: ${String.format(Locale.US, "%.1f", ramU)}/${String.format(Locale.US, "%.1f", ramT)}GB"
                                    }

                                    vramU = (body?.cuda?.system?.used ?: 0.0) / (1024.0 * 1024.0 * 1024.0)
                                    vramT = (body?.cuda?.system?.total ?: 0.0) / (1024.0 * 1024.0 * 1024.0)
                                    if (vramT > 0) {
                                        val vramStr = "VRAM: ${String.format(Locale.US, "%.1f", vramU)}/${String.format(Locale.US, "%.1f", vramT)}GB"
                                        memStr += if (memStr.isNotEmpty()) " | $vramStr" else vramStr
                                    }

                                    _vramUsage.value = memStr.ifEmpty { null }
                                }

                                val record = ServerStatRecord(
                                    timestamp = System.currentTimeMillis(),
                                    pingMs = _pingMs.value,
                                    ramUsed = ramU,
                                    ramTotal = ramT,
                                    vramUsed = vramU,
                                    vramTotal = vramT
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
                    _currentEta.value = 0.0
                    _vramUsage.value = null
                    failCount++
                    if (failCount >= _config.value.connectionTimeout && !_isGenerating.value) {
                        _statusText.value = "Connection Lost (Timeout)"
                    }
                }

                val isForeground = _isAppInForeground.value
                val isActivelyGenerating = _isGenerating.value || _isServerBusy.value

                val baseInterval = if (isForeground || isActivelyGenerating) 1000L else 10000L
                val finalDelay = if (failCount > 0) {
                    val backoff = when (failCount) {
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
        val newVal = !_showGalleryMetadata.value
        _showGalleryMetadata.value = newVal
        repositoryScope.launch(Dispatchers.IO) {
            application.dataStore.edit { it[SHOW_META_KEY] = newVal }
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

                // Zachowane pobieranie czystych bajtów OkHttp dla bezproblemowej ekstrakcji chunków PNG
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
        _selectedModel.value = modelTitle
        repositoryScope.launch(Dispatchers.IO) {
            try {
                forgeApi?.setOptions(OptionsPayloadDto(modelTitle))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to switch model", e)
            }
        }
    }

    fun appendLora(name: String) {
        val current = _appState.value.positivePrompt
        if (!current.contains("<lora:$name:")) {
            val prefix = if (current.isNotEmpty() && !current.endsWith(",")) ", " else ""
            val newPrompt = current.trimEnd() + prefix + "<lora:$name:1.0>"
            updateState { it.copy(positivePrompt = newPrompt) }
        }
    }

    fun updateLoraStrength(name: String, strength: Float) {
        val current = _appState.value.positivePrompt
        val regex = Regex("<lora:${Regex.escape(name)}:[0-9.]+>")
        val formattedStrength = String.format(Locale.US, "%.2f", strength)
        val newPrompt = current.replace(regex, "<lora:$name:$formattedStrength>")
        updateState { it.copy(positivePrompt = newPrompt) }
    }

    fun removeLora(name: String) {
        val current = _appState.value.positivePrompt
        val escapedName = Regex.escape(name)
        val regex = Regex(",?\\s*<lora:$escapedName:[0-9.]+>\\s*,?")
        var newPrompt = current.replace(regex, ", ").trim()
        if (newPrompt.startsWith(",")) newPrompt = newPrompt.substring(1).trim()
        if (newPrompt.endsWith(",")) newPrompt = newPrompt.substring(0, newPrompt.length - 1).trim()
        updateState { it.copy(positivePrompt = newPrompt) }
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
                if (arrayItems != null) {
                    list.addAll(arrayItems.map { it.toDomain() })
                }
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to parse gallery items. JSON: $json", e2)
            }
        }
        return list
    }

    private suspend fun fetchLastGeneratedImageInfo(): String? {
        val rootPath = _config.value.galleryPath

        suspend fun fetchFiles(folder: String): List<GalleryItem> {
            try {
                val response = forgeApi?.getGalleryFiles(folderPath = if (folder.isNotEmpty() && folder != "Root") folder else null)
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
            targetFile = candidateImages.maxByOrNull { item ->
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
                    saveRecoveredImageToCache(bytes)
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
                    updateState { _lastPromptState.value.copy() }
                    showSnackbar("Used local cache (No images found in gallery)")
                    _isRestoringPrompt.value = IndicatorState.SUCCESS
                }
            } catch (_: Exception) {
                updateState { _lastPromptState.value.copy() }
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
                        updateState { it.copy(seed = foundSeed!!) }
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

                // Zachowane pobieranie surowych bajtów OkHttp dla wydajności przy plikach binarnych
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
                    saveRecoveredImageToCache(bytes)
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
                if (currentMode == 0) pos += line + "\n"
                else if (currentMode == 1) neg += line + "\n"
            }
        }

        updateState { state ->
            val newState = state.copy(
                positivePrompt = pos.trim(),
                negativePrompt = neg.trim()
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

    /* ============================================================================
     * DATA SYNCHRONIZATION (CUSTOM API / A1111)
     * Pobiera tylko hasze/nazwy lokalnie, nie wykonuje długich operacji sieciowych na zewnątrz.
     * ============================================================================ */

    fun fetchApiData() {
        repositoryScope.launch(Dispatchers.IO) {
            if (forgeApi == null) return@launch

            // Pobranie sd_cwd w tle
            launch {
                try {
                    val response = forgeApi?.getGlobalSettings()
                    if (response?.isSuccessful == true) {
                        val sdCwd = response.body()?.sdCwd ?: ""
                        if (sdCwd.isNotEmpty() && _config.value.serverBasePath != sdCwd) {
                            _config.update { it.copy(serverBasePath = sdCwd) }
                            saveConfig(_config.value)
                        }
                    }
                } catch(e: Exception) { /* ignore silently */ }
            }

            try {
                coroutineScope {
                    val defSamplers = async {
                        try {
                            val res = forgeApi?.getSamplers()
                            if (res?.isSuccessful == true) {
                                _samplers.value = res.body()?.map { it.name } ?: emptyList()
                            }
                        } catch (e: Exception) { Log.e(TAG, "Failed samplers", e) }
                    }

                    val defSchedulers = async {
                        try {
                            val res = forgeApi?.getSchedulers()
                            if (res?.isSuccessful == true) {
                                _schedulers.value = res.body()?.map { it.name } ?: emptyList()
                            }
                        } catch (e: Exception) { Log.e(TAG, "Failed schedulers", e) }
                    }

                    val defUpscalers = async {
                        try {
                            val res = forgeApi?.getUpscalers()
                            if (res?.isSuccessful == true) {
                                _upscalers.value = res.body()?.map { it.name } ?: emptyList()
                            }
                        } catch (e: Exception) { Log.e(TAG, "Failed upscalers", e) }
                    }

                    val defModelsAndLoras = async {
                        var customApiSuccess = false
                        try {
                            val customRes = forgeApi?.getCustomModelsHashes()
                            if (customRes?.isSuccessful == true) {
                                val modelsList = customRes.body()?.models ?: emptyList()

                                val localDbModels = db.civitaiModelDao().getAllModels().associateBy { it.sha256 }
                                val newModelsToInsert = mutableListOf<CivitaiModelEntity>()
                                val parsedApiModels = mutableListOf<CustomApiModelDto>()

                                for (item in modelsList) {
                                    val type = item.type ?: ""
                                    val name = item.name ?: ""
                                    val filename = item.filename ?: ""
                                    val sha256 = item.sha256 ?: ""

                                    if (sha256.isEmpty()) continue
                                    parsedApiModels.add(CustomApiModelDto(type, name, filename, sha256))

                                    // Dodajemy tylko szczątkowe informacje by model był widoczny.
                                    // Prawdziwe dane z Civitai zostaną dociągnięte ręcznie w syncCivitaiModelsManual()
                                    if (!localDbModels.containsKey(sha256)) {
                                        newModelsToInsert.add(CivitaiModelEntity(sha256, type, name, "", null))
                                    }
                                }

                                if (newModelsToInsert.isNotEmpty()) {
                                    db.civitaiModelDao().insertModels(newModelsToInsert)
                                }

                                val updatedDbModels = db.civitaiModelDao().getAllModels().associateBy { it.sha256 }

                                val checkpoints = parsedApiModels.filter { it.type == "checkpoint" }.map { cam ->
                                    val dbEntity = updatedDbModels[cam.sha256]
                                    ApiResource(
                                        title = dbEntity?.name ?: cam.name ?: "Unknown",
                                        name = cam.name ?: "Unknown",
                                        path = dbEntity?.previewImage ?: cam.filename ?: "",
                                        hash = cam.sha256
                                    )
                                }.sortedBy { it.title.lowercase(Locale.getDefault()) }

                                val loras = parsedApiModels.filter { it.type == "lora" }.map { cam ->
                                    val dbEntity = updatedDbModels[cam.sha256]
                                    ApiResource(
                                        title = dbEntity?.name ?: cam.name ?: "Unknown",
                                        name = cam.name ?: "Unknown",
                                        path = dbEntity?.previewImage ?: cam.filename ?: "",
                                        hash = cam.sha256
                                    )
                                }.sortedBy { it.title.lowercase(Locale.getDefault()) }

                                _models.value = checkpoints
                                _availableLoras.value = loras
                                customApiSuccess = true
                            } else {
                                Log.w(TAG, "Custom API not found (HTTP ${customRes?.code()}). Fallback initiated.")
                                showSnackbar("Custom API missing. Falling back to standard models.")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Custom API fetch failed", e)
                            showSnackbar("Failed to reach Custom API. Falling back.")
                        }

                        if (!customApiSuccess) {
                            try {
                                val modelRes = forgeApi?.getSdModels()
                                if (modelRes?.isSuccessful == true) {
                                    _models.value = modelRes.body()?.map { it.toDomain() }?.sortedBy { it.title.lowercase(Locale.getDefault()) } ?: emptyList()
                                }

                                val loraRes = forgeApi?.getLoras()
                                if (loraRes?.isSuccessful == true) {
                                    _availableLoras.value = loraRes.body()?.map { it.toDomain() }?.sortedBy { it.title.lowercase(Locale.getDefault()) } ?: emptyList()
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Fallback API fetch failed", e)
                                showSnackbar("Failed to fetch models completely.")
                            }
                        }
                    }

                    val defOpts = async {
                        try {
                            val res = forgeApi?.getOptions()
                            if (res?.isSuccessful == true) {
                                _selectedModel.value = res.body()?.sdModelCheckpoint ?: ""
                            }
                        } catch (e: Exception) { Log.e(TAG, "Failed options", e) }
                    }

                    awaitAll(defSamplers, defSchedulers, defUpscalers, defModelsAndLoras, defOpts)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to synchronize API definitions: ${e.message}")
            }
        }
    }

    /* ============================================================================
     * MANUAL CIVITAI SYNCHRONIZATION
     * Ręczne, dedykowane pobieranie metadanych z Civitai z wbudowanym pop-upem
     * oraz weryfikacją postępu. Zabezpieczone 5-sekundowym interwałem.
     * ============================================================================ */

    fun syncCivitaiModelsManual() {
        if (_isCivitaiSyncing.value != IndicatorState.IDLE) return

        repositoryScope.launch(Dispatchers.IO) {
            try {
                println("[CivitaiSync] --- ROZPOCZĘCIE SYNCHRONIZACJI Z CIVITAI ---")
                _isCivitaiSyncing.value = IndicatorState.LOADING
                _civitaiSyncLastResult.value = null

                val customRes = forgeApi?.getCustomModelsHashes()
                if (customRes?.isSuccessful != true) {
                    _civitaiSyncLastResult.value = "Błąd: Brak Custom API na serwerze Forge."
                    println("[CivitaiSync] Błąd: Serwer nie posiada odpowiedniego Custom API (HTTP ${customRes?.code()})")
                    _isCivitaiSyncing.value = IndicatorState.ERROR
                    delay(3000)
                    _isCivitaiSyncing.value = IndicatorState.IDLE
                    return@launch
                }

                val modelsList = customRes.body()?.models ?: emptyList()
                val localDbModels = db.civitaiModelDao().getAllModels().associateBy { it.sha256 }

                println("[CivitaiSync] Odczytano ${modelsList.size} modeli z serwera Forge.")

                // Szukamy modeli, które zostały dodane lokalnie, ale nie mają obrazka i słów kluczowych (czyli wymagają ściągnięcia z Civitai)
                val missingOrIncomplete = modelsList.filter { item ->
                    val sha = item.sha256 ?: return@filter false
                    val entity = localDbModels[sha]
                    entity == null || (entity.previewImage == null && entity.trainedWords.isEmpty())
                }

                if (missingOrIncomplete.isEmpty()) {
                    _civitaiSyncLastResult.value = "Wszystkie modele są już zsynchronizowane!"
                    println("[CivitaiSync] Zakończono: Wszystkie modele posiadają już zapisane metadane.")
                    _isCivitaiSyncing.value = IndicatorState.SUCCESS
                    delay(2000)
                    _isCivitaiSyncing.value = IndicatorState.IDLE
                    return@launch
                }

                println("[CivitaiSync] Znaleziono ${missingOrIncomplete.size} modeli oczekujących na pobranie metadanych.")
                _civitaiSyncProgress.value = 0 to missingOrIncomplete.size

                var hasError = false

                for ((index, cam) in missingOrIncomplete.withIndex()) {
                    var civName = cam.name ?: "Unknown"
                    val civType = cam.type ?: "checkpoint"
                    val sha256 = cam.sha256 ?: continue
                    var trainedWords = ""
                    var previewImage: String? = null

                    _civitaiSyncCurrentModel.value = civName
                    _civitaiSyncProgress.value = index to missingOrIncomplete.size

                    // Ochrona przed banem IP od Civitai. Zawsze 5 sekund odstępu między żądaniami.
                    if (index > 0) delay(5000) else delay(500)

                    println("[CivitaiSync] [$index/${missingOrIncomplete.size}] Pobieranie dla haszu: $sha256 ($civName)")

                    try {
                        val civRes = civitaiApi.getModelByHash(sha256)
                        if (civRes.isSuccessful) {
                            val civBody = civRes.body()
                            if (civBody?.model != null) {
                                civName = civBody.model.name ?: civName
                            }
                            trainedWords = civBody?.trainedWords?.joinToString(", ") ?: ""
                            if (!civBody?.images.isNullOrEmpty()) {
                                previewImage = civBody?.images?.firstOrNull()?.url?.replace("original=true", "original=false")
                            }
                            _civitaiSyncLastResult.value = "Pobrano pomyślnie"

                            println("[CivitaiSync] SUKCES dla $sha256:")
                            println("[CivitaiSync]  - Parsowana Nazwa: $civName")
                            println("[CivitaiSync]  - Parsowane Tagi: $trainedWords")
                            println("[CivitaiSync]  - Parsowane URL Zdjęcia: $previewImage")

                            Log.d(TAG, "Civitai Success for $sha256")
                        } else {
                            _civitaiSyncLastResult.value = "Błąd: HTTP ${civRes.code()}"
                            println("[CivitaiSync] BŁĄD HTTP: ${civRes.code()} dla haszu $sha256")
                            Log.w(TAG, "Civitai API zwróciło błąd ${civRes.code()} dla haszu $sha256")
                            hasError = true
                        }
                    } catch (e: Exception) {
                        _civitaiSyncLastResult.value = "Błąd sieci"
                        println("[CivitaiSync] WYJĄTEK podczas pobierania dla $sha256: ${e.message}")
                        Log.e(TAG, "Civitai API fetch error for $sha256", e)
                        hasError = true
                    }

                    // Nadpisanie encji w bazie nowymi danymi z Civitai (albo pozostawienie samej nazwy, jeśli pobieranie się nie powiodło)
                    val updatedEntity = CivitaiModelEntity(sha256, civType, civName, trainedWords, previewImage)
                    db.civitaiModelDao().insertModels(listOf(updatedEntity))

                    _civitaiSyncProgress.value = (index + 1) to missingOrIncomplete.size
                }

                _civitaiSyncLastResult.value = "Synchronizacja pomyślnie zakończona"
                println("[CivitaiSync] --- ZAKOŃCZONO SYNCHRONIZACJĘ ---")

                fetchApiData()

                _isCivitaiSyncing.value = if (hasError) IndicatorState.ERROR else IndicatorState.SUCCESS
                delay(2000)
                _isCivitaiSyncing.value = IndicatorState.IDLE

            } catch (e: Exception) {
                Log.e(TAG, "Sync error", e)
                println("[CivitaiSync] KRYTYCZNY BŁĄD PĘTLI SYNCHRONIZACJI: ${e.message}")
                _civitaiSyncLastResult.value = "Wystąpił krytyczny błąd"
                _isCivitaiSyncing.value = IndicatorState.ERROR
                delay(3000)
                _isCivitaiSyncing.value = IndicatorState.IDLE
            }
        }
    }

    fun fetchGalleryFolder(path: String = _config.value.galleryPath) {
        _isGalleryLoading.value = true
        _galleryError.value = null

        repositoryScope.launch(Dispatchers.IO) {
            try {
                if (path == "virtual://favorites") {
                    val favorites = db.favoriteImageDao().getAllFavorites()
                    val items = favorites.map {
                        GalleryItem(
                            name = it.name,
                            fullpath = it.fullpath,
                            type = "file",
                            date = it.date,
                            createdTime = null,
                            size = null
                        )
                    }
                    _galleryFiles.value = items
                    _currentGalleryPath.value = path
                    return@launch
                }

                val targetFolder = if (path.isNotEmpty() && path != "Root") path else null
                val response = forgeApi?.getGalleryFiles(folderPath = targetFolder)

                if (response?.isSuccessful == true) {
                    val responseBody = response.body()?.string() ?: ""
                    val allItems = parseGalleryItems(responseBody).sortedWith(compareBy({ !it.isDir }, { it.name })).toMutableList()

                    if (path == "Root" || path == _config.value.galleryPath) {
                        allItems.add(0, GalleryItem(
                            name = "⭐ Favorites",
                            fullpath = "virtual://favorites",
                            type = "dir"
                        ))
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
        val urlStr = _config.value.apiUrl.trimEnd('/')
        val builder = urlStr.toHttpUrlOrNull()?.newBuilder()
            ?.addPathSegments("infinite_image_browsing/file")
            ?.addQueryParameter("path", item.fullpath)

        if (!item.date.isNullOrEmpty()) {
            builder?.addQueryParameter("t", item.date)
        }

        return builder?.build()?.toString() ?: ""
    }

    fun downloadSessionImage(localFilePath: String) {
        repositoryScope.launch(Dispatchers.IO) {
            try {
                val file = File(localFilePath)
                if (!file.exists()) throw Exception("Local file missing")

                val bytes = file.readBytes()
                val fileName = "Gen_${System.currentTimeMillis()}.png"

                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ForgeGen")
                }

                val resolver = application.contentResolver
                val insertUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val uri = resolver.insert(insertUri, contentValues)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    showSnackbar("Saved to Downloads")
                } else throw Exception("Failed to create file in MediaStore")

            } catch (e: Exception) {
                Log.e(TAG, "Download Failed", e)
                showSnackbar("Download Failed: ${e.message}")
            }
        }
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
                        val contentValues = ContentValues().apply {
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
                        } else throw Exception("Failed to create file in MediaStore")
                    } else throw Exception("Server returned ${response.code}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download Failed", e)
                showSnackbar("Download Failed: ${e.message}")
            }
        }
    }

    fun shareSessionImage(localFilePath: String, onIntentReady: (Intent) -> Unit) {
        repositoryScope.launch(Dispatchers.IO) {
            try {
                val file = File(localFilePath)
                if (!file.exists()) throw Exception("Local file missing")

                val bytes = file.readBytes()
                val fileName = "Shared_${System.currentTimeMillis()}.png"

                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ForgeGen_Shared")
                }

                val resolver = application.contentResolver
                val insertUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val uri = resolver.insert(insertUri, contentValues)

                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { it.write(bytes) }

                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    withContext(Dispatchers.Main) {
                        onIntentReady(Intent.createChooser(shareIntent, "Share Image"))
                    }
                } else throw Exception("Failed to prepare file for sharing")
            } catch(e: Exception) {
                Log.e(TAG, "Share Failed", e)
                showSnackbar("Share Failed: ${e.message}")
            }
        }
    }

    fun shareImage(item: GalleryItem, onIntentReady: (Intent) -> Unit) {
        repositoryScope.launch(Dispatchers.IO) {
            try {
                val url = getGalleryImageUrl(item)
                if (url.isEmpty()) throw Exception("Invalid Gallery URL")

                val request = Request.Builder().url(url).build()

                client.newCall(request).awaitResponse().use { response ->
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes() ?: throw Exception("Empty response body")
                        val contentValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, "Shared_${item.name}")
                            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ForgeGen_Shared")
                        }

                        val resolver = application.contentResolver
                        val insertUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        val uri = resolver.insert(insertUri, contentValues)

                        if (uri != null) {
                            resolver.openOutputStream(uri)?.use { it.write(bytes) }

                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "image/png"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            withContext(Dispatchers.Main) {
                                onIntentReady(Intent.createChooser(shareIntent, "Share Image"))
                            }
                        } else throw Exception("Failed to prepare file for sharing")
                    } else throw Exception("Server returned ${response.code}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Share Failed", e)
                showSnackbar("Share Failed: ${e.message}")
            }
        }
    }

    fun dismissGridPreview(index: Int? = null) {
        _isShowingGridPreview.value = false
        if (index != null && index in 0 until _sessionImages.value.size) {
            _currentSessionIndex.value = index
        }
    }

    fun sessionPrev() {
        _isShowingGridPreview.value = false
        val idx = _currentSessionIndex.value
        if (idx > _currentBatchStartIndex.value) _currentSessionIndex.value = idx - 1
    }

    fun sessionNext() {
        _isShowingGridPreview.value = false
        val idx = _currentSessionIndex.value
        if (idx < _currentBatchEndIndex.value) _currentSessionIndex.value = idx + 1
    }
}