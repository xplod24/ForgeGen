package com.example.forgegen

import com.example.forgegen.ui.components.*
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.Locale
import java.util.concurrent.TimeUnit

/* ============================================================================
 * NETWORK MANAGER
 * Manages OkHttpClient instances, Retrofit client initialization, and coordinates API fetch operations
 * for server configurations (Models, Samplers, LoRAs, and Civitai updates).
 * ============================================================================ */
class ForgeNetworkManager(
    private val getDb: () -> ForgeDatabase,
    private val getConfig: () -> AppConfig,
    private val updateConfig: (AppConfig) -> Unit,
    val managerScope: CoroutineScope,
) {
    private val TAG = "ForgeNetworkManager"
    private val gson = Gson()

    // --- HTTP CLIENTS AND RETROFIT APIS ---
    var client: OkHttpClient = OkHttpClient()
        private set

    var forgeApi: ForgeApi? = null
        private set

    @Volatile private var hasFetchedInitialData = false

    fun start() {
        managerScope.launch(Dispatchers.IO) {
            var currentUrl = ""
            var currentTimeout = -1
            ForgeRepository.config.collect { config ->
                val timeoutChanged = currentTimeout != config.timeout
                if (timeoutChanged) {
                    currentTimeout = config.timeout
                    initClient(currentTimeout)
                }
                if (currentUrl != config.apiUrl) {
                    currentUrl = config.apiUrl
                    rebuildForgeApi(currentUrl)
                    ForgeRepository.resetPingJob()
                    hasFetchedInitialData = false
                    // isConnected only emits on a change, so when it is already true (app reopened while the process
                    // lived on, or a switch between two reachable servers) the lists must be fetched from here.
                    if (ForgeRepository.isConnected.value) {
                        hasFetchedInitialData = true
                        fetchApiData()
                    }
                } else if (timeoutChanged && currentUrl.isNotEmpty()) {
                    rebuildForgeApi(currentUrl) // Retrofit keeps the client it was built with
                }
            }
        }

        managerScope.launch(Dispatchers.IO) {
            ForgeRepository.isConnected.collect { isConnected ->
                if (!isConnected) {
                    hasFetchedInitialData = false // refresh the lists once the server is back (it may have restarted)
                } else if (!hasFetchedInitialData && forgeApi != null) {
                    // forgeApi is null until the config collector above has built it; that collector fetches then.
                    hasFetchedInitialData = true
                    fetchApiData()
                }
            }
        }
    }

    val civitaiApi: CivitaiApi by lazy {
        val loggingInterceptor =
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }
        val conditionalCivitaiLogger =
            okhttp3.Interceptor { chain ->
                if (!getConfig().enableLogging) {
                    chain.proceed(chain.request())
                } else {
                    loggingInterceptor.intercept(chain)
                }
            }
        val civitaiClient =
            OkHttpClient
                .Builder()
                .addInterceptor(conditionalCivitaiLogger)
                .connectTimeout(getConfig().timeout.toLong(), TimeUnit.SECONDS)
                .readTimeout(getConfig().timeout.toLong(), TimeUnit.SECONDS)
                .build()

        Retrofit
            .Builder()
            .baseUrl("https://civitai.com/")
            .client(civitaiClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(CivitaiApi::class.java)
    }

    // --- STATIC CACHE STATES (Model list, Samplers, Schedulers) ---
    // The selected checkpoint is shared with ForgeQueueManager (override_settings of every job), so it lives in
    // ForgeModelManager: a second copy here let the queue keep sending the model that was active at app start.
    val selectedModel: StateFlow<String> get() = ForgeModelManager.selectedModel

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

    // --- CIVITAI SYNCHRONIZATION STATES ---
    private val _isCivitaiSyncing = MutableStateFlow(IndicatorState.IDLE)
    val isCivitaiSyncing: StateFlow<IndicatorState> = _isCivitaiSyncing.asStateFlow()

    private var civitaiSyncJob: kotlinx.coroutines.Job? = null

    fun cancelCivitaiSync() {
        if (_isCivitaiSyncing.value == IndicatorState.LOADING) {
            civitaiSyncJob?.cancel()
            ForgeNotifications.cancel(ForgeNotifications.ID_CIVITAI_SYNC)
            _isCivitaiSyncing.value = IndicatorState.IDLE
        }
    }

    private val _civitaiSyncCurrentModel = MutableStateFlow("")
    val civitaiSyncCurrentModel: StateFlow<String> = _civitaiSyncCurrentModel.asStateFlow()

    private val _civitaiSyncProgress = MutableStateFlow(0 to 0)
    val civitaiSyncProgress: StateFlow<Pair<Int, Int>> = _civitaiSyncProgress.asStateFlow()

    private val _civitaiSyncLastResult = MutableStateFlow<String?>(null)
    val civitaiSyncLastResult: StateFlow<String?> = _civitaiSyncLastResult.asStateFlow()

    private val _galleryApiPrefix = MutableStateFlow("infinite_image_browsing")
    val galleryApiPrefix: StateFlow<String> = _galleryApiPrefix.asStateFlow()

    fun initClient(timeoutSeconds: Int) {
        val logging =
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }
        // Image downloads are logged without their body: logging a body buffers all of it, so reading only the
        // start of a PNG (gallery metadata) downloaded the whole file while logging was on.
        val headerLogging =
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.HEADERS
            }
        val conditionalLogger =
            okhttp3.Interceptor { chain ->
                val request = chain.request()
                val path = request.url.encodedPath
                val skipLogging = path.contains("progress") || path.contains("memory") || !getConfig().enableLogging
                val isImage = path.endsWith("/file") || path.endsWith("/image-thumbnail")
                when {
                    skipLogging -> chain.proceed(request)
                    isImage -> headerLogging.intercept(chain)
                    else -> logging.intercept(chain)
                }
            }

        client =
            OkHttpClient
                .Builder()
                .connectTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .addInterceptor(conditionalLogger)
                .addInterceptor { chain ->
                    val originalRequest = chain.request()
                    val requestBuilder = originalRequest.newBuilder()

                    val path = originalRequest.url.encodedPath
                    val isGalleryCall =
                        path.contains("infinite_image_browsing") ||
                            path.contains("inifinite-image-gallery") ||
                            path.contains("infinite-image-gallery")

                    if (isGalleryCall) {
                        requestBuilder.header("Cookie", "IIB_S=bf63789069ec13d6b7b95a5176468e99f8940fe6aa65931edc17e1abf5c5e172")
                    }
                    try {
                        val response = chain.proceed(requestBuilder.build())
                        if (!response.isSuccessful) {
                            try {
                                val bodyStr = response.peekBody(Long.MAX_VALUE).string()
                                Log.e(TAG, "API ERROR [${response.code}]: ${response.request.url}\nBody: $bodyStr")
                            } catch (e: Exception) {
                                Log.e(TAG, "API ERROR [${response.code}]: ${response.request.url} (Could not read body)")
                            }
                        }
                        response
                    } catch (e: Exception) {
                        Log.e(TAG, "API CALL FAILED: ${e.message}", e)
                        throw e
                    }
                }.build()
    }

    fun rebuildForgeApi(url: String) {
        var cleanUrl = url.trimEnd('/')
        if (cleanUrl.isNotEmpty() && !cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            cleanUrl = "http://$cleanUrl"
        }
        if (cleanUrl.isEmpty()) return

        try {
            val retrofitForge =
                Retrofit
                    .Builder()
                    .baseUrl("$cleanUrl/")
                    .client(client)
                    .addConverterFactory(GsonConverterFactory.create(gson))
                    .build()
            forgeApi = retrofitForge.create(ForgeApi::class.java)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Failed to initialize ForgeApi with URL: $cleanUrl. Exception: $e")
        }
    }

    fun changeCheckpoint(modelTitle: String) {
        ForgeModelManager.updateState(selectedModel = modelTitle)
        managerScope.launch(Dispatchers.IO) {
            try {
                forgeApi?.setOptions(OptionsPayloadDto(modelTitle))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Failed to switch model. Exception: $e")
            }
        }
    }

    private var fetchJob: kotlinx.coroutines.Job? = null

    // Completed fetches of the server lists (a fetch replaced by a newer one does not count), and whether the last
    // one got the lists a generation needs: the models and the samplers.
    private val fetchesDone = MutableStateFlow(0)

    @Volatile private var lastFetchHadLists = false

    /** How the first fetch of the server lists went. */
    enum class ServerData { LOADED, INCOMPLETE, PENDING }

    /** Waits (at most [timeoutMs]) for the first fetch of the server lists; PENDING when it has not finished yet. */
    suspend fun awaitServerData(timeoutMs: Long): ServerData =
        when {
            withTimeoutOrNull(timeoutMs) { fetchesDone.first { it > 0 } } == null -> ServerData.PENDING
            lastFetchHadLists -> ServerData.LOADED
            else -> ServerData.INCOMPLETE
        }

    /**
     * Main data fetch operation to pull server properties needed for UI setup (Checkpoints, Samplers, Schedulers, LoRAs).
     */
    fun fetchApiData() {
        fetchJob?.cancel()
        fetchJob =
            managerScope.launch(Dispatchers.IO) {
                if (forgeApi == null) return@launch
                // Counted when it completes, its children included, so the gallery prefix is also known by then.
                coroutineContext.job.invokeOnCompletion { cause -> if (cause == null) fetchesDone.update { it + 1 } }

                // Auto-detect the working directory and base prefix used by the gallery extension.
                launch {
                    val prefixes = listOf("infinite_image_browsing", "inifinite-image-gallery", "infinite-image-gallery")
                    for (prefix in prefixes) {
                        try {
                            val response = forgeApi?.getGalleryFilesDynamic("$prefix/files")
                            if (response?.isSuccessful == true) {
                                _galleryApiPrefix.value = prefix
                                Log.d(TAG, "Detected gallery API prefix: $prefix")

                                // Extract the server's working directory (sdCwd) if the global settings endpoint is available under this prefix.
                                try {
                                    val settingsRes = forgeApi?.getGlobalSettingsDynamic("$prefix/global_setting")
                                    if (settingsRes?.isSuccessful == true) {
                                        val sdCwd = settingsRes.body()?.sdCwd ?: ""
                                        val config = getConfig()
                                        if (sdCwd.isNotEmpty() && config.serverBasePath != sdCwd) {
                                            updateConfig(config.copy(serverBasePath = sdCwd))
                                        }
                                    }
                                } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    Log.w(TAG, "Failed to fetch global settings for prefix $prefix. Exception: $e")
                                }
                                break
                            }
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            Log.w(TAG, "Probe failed for gallery prefix: $prefix. Exception: $e")
                        }
                    }
                }

                try {
                    coroutineScope {
                        val defSamplers =
                            async {
                                try {
                                    val res = forgeApi?.getSamplers()
                                    if (res?.isSuccessful == true) {
                                        _samplers.value = res.body()?.map { it.name } ?: emptyList()
                                    }
                                    res?.isSuccessful == true
                                } catch (
                                    e: Exception,
                                ) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    Log.e(TAG, "Failed samplers: $e")
                                    false
                                }
                            }

                        val defSchedulers =
                            async {
                                try {
                                    val res = forgeApi?.getSchedulers()
                                    if (res?.isSuccessful == true) {
                                        _schedulers.value = res.body()?.map { it.name } ?: emptyList()
                                    }
                                } catch (
                                    e: Exception,
                                ) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    Log.e(TAG, "Failed schedulers: $e")
                                }
                            }

                        val defUpscalers =
                            async {
                                try {
                                    val res = forgeApi?.getUpscalers()
                                    if (res?.isSuccessful == true) {
                                        _upscalers.value = res.body()?.map { it.name } ?: emptyList()
                                    }
                                } catch (
                                    e: Exception,
                                ) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    Log.e(TAG, "Failed upscalers: $e")
                                }
                            }

                        val defModelsAndLoras =
                            async {
                                var customApiSuccess = false
                                try {
                                    val customRes = forgeApi?.getCustomModelsHashes()
                                    if (customRes?.isSuccessful == true) {
                                        val modelsList = customRes.body()?.models ?: emptyList()

                                        val localDbModels = getDb().civitaiModelDao().getAllModels().associateBy { it.sha256 }
                                        val newModelsToInsert = mutableListOf<CivitaiModelEntity>()
                                        val parsedApiModels = mutableListOf<CustomApiModelDto>()

                                        for (item in modelsList) {
                                            val type = item.type ?: ""
                                            val name = item.name ?: ""
                                            val filename = item.filename ?: ""
                                            val sha256 = item.sha256 ?: ""

                                            if (sha256.isEmpty()) continue
                                            parsedApiModels.add(CustomApiModelDto(type, name, filename, sha256))

                                            if (!localDbModels.containsKey(sha256)) {
                                                newModelsToInsert.add(CivitaiModelEntity(sha256, type, name, "", null))
                                            }
                                        }

                                        if (newModelsToInsert.isNotEmpty()) {
                                            getDb().civitaiModelDao().insertModels(newModelsToInsert)
                                        }

                                        val updatedDbModels = getDb().civitaiModelDao().getAllModels().associateBy { it.sha256 }

                                        val checkpoints =
                                            parsedApiModels
                                                .filter { it.type == "checkpoint" }
                                                .map { cam ->
                                                    val dbEntity = updatedDbModels[cam.sha256]
                                                    ApiResource(
                                                        title = dbEntity?.name ?: cam.name ?: "Unknown",
                                                        name = cam.name ?: "Unknown",
                                                        path = dbEntity?.previewImage ?: cam.filename ?: "",
                                                        hash = cam.sha256,
                                                    )
                                                }.sortedBy { it.title.lowercase(Locale.getDefault()) }

                                        val loras =
                                            parsedApiModels
                                                .filter { it.type == "lora" }
                                                .map { cam ->
                                                    val dbEntity = updatedDbModels[cam.sha256]
                                                    ApiResource(
                                                        title = dbEntity?.name ?: cam.name ?: "Unknown",
                                                        name = cam.name ?: "Unknown",
                                                        path = dbEntity?.previewImage ?: cam.filename ?: "",
                                                        hash = cam.sha256,
                                                    )
                                                }.sortedBy { it.title.lowercase(Locale.getDefault()) }

                                        _models.value = checkpoints
                                        _availableLoras.value = loras
                                        customApiSuccess = true
                                    }
                                } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    Log.e(TAG, "Custom API fetch failed: $e")
                                }

                                var fallbackSuccess = false
                                if (!customApiSuccess) {
                                    try {
                                        val modelRes = forgeApi?.getSdModels()
                                        if (modelRes?.isSuccessful == true) {
                                            _models.value =
                                                modelRes.body()?.map { it.toDomain() }?.sortedBy { it.title.lowercase(Locale.getDefault()) }
                                                    ?: emptyList()
                                            fallbackSuccess = true
                                        }

                                        val loraRes = forgeApi?.getLoras()
                                        if (loraRes?.isSuccessful == true) {
                                            _availableLoras.value =
                                                loraRes.body()?.map { it.toDomain() }?.sortedBy { it.title.lowercase(Locale.getDefault()) }
                                                    ?: emptyList()
                                        }
                                    } catch (e: Exception) {
                                        if (e is kotlinx.coroutines.CancellationException) throw e
                                        Log.e(TAG, "Fallback API fetch failed: $e")
                                    }
                                }
                                customApiSuccess || fallbackSuccess
                            }

                        val defOpts =
                            async {
                                try {
                                    val res = forgeApi?.getOptions()
                                    if (res?.isSuccessful == true) {
                                        ForgeModelManager.updateState(selectedModel = res.body()?.sdModelCheckpoint ?: "")
                                    }
                                } catch (
                                    e: Exception,
                                ) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    Log.e(TAG, "Failed options: $e")
                                }
                            }

                        awaitAll(defSamplers, defSchedulers, defUpscalers, defModelsAndLoras, defOpts)
                        lastFetchHadLists = defSamplers.await() && defModelsAndLoras.await()
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.e(TAG, "Failed to synchronize API definitions: $e")
                    lastFetchHadLists = false
                }
            }
    }

    /**
     * Why the Forge server cannot be used for a Civitai sync right now, or null when it answers with HTTP 200.
     * Asked directly rather than taken from the ping loop, which may not have noticed an outage yet.
     */
    private suspend fun serverNotReadyReason(): String? {
        val api = forgeApi ?: return "No Forge server is set."
        return try {
            val code = api.getProgress(skipImage = true).code()
            if (code == 200) null else "The Forge server answered HTTP $code instead of 200."
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            "The Forge server is not reachable."
        }
    }

    /**
     * Manually triggers Civitai synchronization. Implements a rate-limiting delay to prevent IP bans.
     * It only starts while the Forge server answers with HTTP 200: the models to look up come from the server.
     */
    fun syncCivitaiModelsManual() {
        if (_isCivitaiSyncing.value != IndicatorState.IDLE) return

        civitaiSyncJob =
            managerScope.launch(Dispatchers.IO) {
                try {
                    _isCivitaiSyncing.value = IndicatorState.LOADING
                    _civitaiSyncLastResult.value = null

                    val notReady = serverNotReadyReason()
                    val customRes = if (notReady == null) forgeApi?.getCustomModelsHashes() else null
                    if (notReady != null || customRes?.code() != 200) {
                        _civitaiSyncLastResult.value =
                            "Error: " + (notReady ?: "No Custom API on Forge server (HTTP ${customRes?.code() ?: -1}).")
                        _isCivitaiSyncing.value = IndicatorState.ERROR
                        delay(3000)
                        _isCivitaiSyncing.value = IndicatorState.IDLE
                        return@launch
                    }

                    val modelsList = customRes.body()?.models ?: emptyList()
                    val localDbModels = getDb().civitaiModelDao().getAllModels().associateBy { it.sha256 }

                    val missingOrIncomplete =
                        modelsList.filter { item ->
                            val sha = item.sha256 ?: return@filter false
                            val entity = localDbModels[sha]
                            entity == null || (entity.previewImage == null && entity.trainedWords.isEmpty())
                        }

                    if (missingOrIncomplete.isEmpty()) {
                        _civitaiSyncLastResult.value = "All models are already synchronized!"
                        _isCivitaiSyncing.value = IndicatorState.SUCCESS
                        delay(2000)
                        _isCivitaiSyncing.value = IndicatorState.IDLE
                        return@launch
                    }

                    _civitaiSyncProgress.value = 0 to missingOrIncomplete.size
                    var hasError = false
                    var failedCount = 0

                    for ((index, cam) in missingOrIncomplete.withIndex()) {
                        var civName = cam.name ?: "Unknown"
                        val civType = cam.type ?: "checkpoint"
                        val sha256 = cam.sha256 ?: continue
                        var trainedWords = ""
                        var previewImage: String? = null

                        _civitaiSyncCurrentModel.value = civName
                        _civitaiSyncProgress.value = index to missingOrIncomplete.size
                        notifyCivitaiSync(
                            title = "Syncing Civitai models (${index + 1}/${missingOrIncomplete.size})",
                            text = civName,
                            progress = index to missingOrIncomplete.size,
                        )

                        // Rate-limiting delay (5 seconds) to prevent Civitai from issuing an IP ban/rate-limit.
                        if (index > 0) delay(5000) else delay(500)

                        try {
                            val civRes = civitaiApi.getModelByHash(sha256)
                            if (civRes.isSuccessful) {
                                val civBody = civRes.body()
                                if (civBody?.model != null) civName = civBody.model.name ?: civName
                                trainedWords = civBody?.trainedWords?.joinToString(", ") ?: ""
                                if (!civBody?.images.isNullOrEmpty()) {
                                    previewImage =
                                        civBody.images
                                            .firstOrNull()
                                            ?.url
                                            ?.replace("original=true", "original=false")
                                }
                                _civitaiSyncLastResult.value = "Downloaded successfully"
                            } else {
                                _civitaiSyncLastResult.value = "Error: HTTP ${civRes.code()}"
                                hasError = true
                                failedCount++
                            }
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            _civitaiSyncLastResult.value = "Network error"
                            hasError = true
                            failedCount++
                        }

                        val updatedEntity = CivitaiModelEntity(sha256, civType, civName, trainedWords, previewImage)
                        getDb().civitaiModelDao().insertModels(listOf(updatedEntity))
                        _civitaiSyncProgress.value = (index + 1) to missingOrIncomplete.size
                    }

                    val total = missingOrIncomplete.size
                    _civitaiSyncLastResult.value =
                        if (hasError) {
                            "Finished with errors: $failedCount of $total models failed"
                        } else {
                            "Synchronization completed successfully"
                        }
                    when {
                        hasError ->
                            notifyCivitaiSync(
                                title = "Civitai sync finished with errors",
                                text = "$failedCount of $total models failed",
                                isError = true,
                            )
                        getConfig().autoDismissCivitaiNotif -> ForgeNotifications.cancel(ForgeNotifications.ID_CIVITAI_SYNC)
                        else -> notifyCivitaiSync("Civitai sync finished", "$total models updated")
                    }
                    fetchApiData() // Refresh model resources from the local SQLite database to reflect synced metadata.

                    _isCivitaiSyncing.value = if (hasError) IndicatorState.ERROR else IndicatorState.SUCCESS
                    delay(2000)
                    _isCivitaiSyncing.value = IndicatorState.IDLE
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) {
                        ForgeNotifications.cancel(ForgeNotifications.ID_CIVITAI_SYNC) // an ongoing notification cannot be swiped away
                        throw e
                    }
                    Log.e(TAG, "Critical error during Civitai synchronization: $e")
                    _civitaiSyncLastResult.value = "A critical error occurred"
                    notifyCivitaiSync("Civitai sync failed", "A critical error occurred", isError = true)
                    _isCivitaiSyncing.value = IndicatorState.ERROR
                    delay(3000)
                    _isCivitaiSyncing.value = IndicatorState.IDLE
                }
            }
    }

    /** Honours "Notify during Civitai Sync"; the sync pauses 5 s per model, so it can run for minutes. */
    private fun notifyCivitaiSync(
        title: String,
        text: String,
        progress: Pair<Int, Int>? = null,
        isError: Boolean = false,
    ) {
        if (!getConfig().notifCivitaiSync) return
        val channel = if (isError) ForgeNotifications.CHANNEL_RESULTS else ForgeNotifications.CHANNEL_PROGRESS
        val builder = ForgeNotifications.builder(channel) ?: return
        builder.setContentTitle(title).setContentText(text)
        if (progress != null) {
            builder.setProgress(progress.second, progress.first, false).setOngoing(true).setSilent(true)
        } else {
            builder.setAutoCancel(true)
        }
        ForgeNotifications.post(ForgeNotifications.ID_CIVITAI_SYNC, builder.build())
    }

    fun refreshCheckpoints(onResult: (Boolean, String) -> Unit) {
        managerScope.launch(Dispatchers.IO) {
            try {
                val res = forgeApi?.refreshCheckpoints()
                if (res?.isSuccessful == true) {
                    fetchApiData()
                    onResult(true, "Models list refreshed successfully")
                } else {
                    onResult(false, "Server error: ${res?.code() ?: -1}")
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                onResult(false, "Network error: ${e.localizedMessage}")
            }
        }
    }

    fun refreshLoras(onResult: (Boolean, String) -> Unit) {
        managerScope.launch(Dispatchers.IO) {
            try {
                val res = forgeApi?.refreshLoras()
                if (res?.isSuccessful == true) {
                    fetchApiData()
                    onResult(true, "LoRAs list refreshed successfully")
                } else {
                    onResult(false, "Server error: ${res?.code() ?: -1}")
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                onResult(false, "Network error: ${e.localizedMessage}")
            }
        }
    }
}
