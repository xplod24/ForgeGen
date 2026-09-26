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
 * for server configurations (Models, Samplers, LoRAs).
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

                                        // The server's own names and previews; models without a hash are left out.
                                        val parsedApiModels = modelsList.filter { !it.sha256.isNullOrEmpty() }

                                        fun resource(cam: CustomApiModelDto) =
                                            ApiResource(
                                                title = cam.name ?: "Unknown",
                                                name = cam.name ?: "Unknown",
                                                path = cam.filename ?: "",
                                                hash = cam.sha256,
                                            )

                                        val checkpoints =
                                            parsedApiModels
                                                .filter { it.type == "checkpoint" }
                                                .map(::resource)
                                                .sortedBy { it.title.lowercase(Locale.getDefault()) }

                                        val loras =
                                            parsedApiModels
                                                .filter { it.type == "lora" }
                                                .map(::resource)
                                                .sortedBy { it.title.lowercase(Locale.getDefault()) }

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
