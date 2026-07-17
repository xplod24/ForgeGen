package com.example.forgegen

import android.app.Application
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
import kotlinx.coroutines.launch
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
    private val application: Application,
    private val db: ForgeDatabase,
    private val getConfig: () -> AppConfig,
    private val updateConfig: (AppConfig) -> Unit,
    private val showToast: (String) -> Unit,
    val managerScope: CoroutineScope,
) {
    private val TAG = "ForgeNetworkManager"
    private val gson = Gson()

    // --- HTTP CLIENTS AND RETROFIT APIS ---
    var client: OkHttpClient = OkHttpClient()
        private set

    var forgeApi: ForgeApi? = null
        private set

    init {
        managerScope.launch(Dispatchers.IO) {
            var currentUrl = ""
            var currentTimeout = -1
            ForgeRepository.config.collect { config ->
                if (currentTimeout != config.connectionTimeout) {
                    currentTimeout = config.connectionTimeout
                    initClient(currentTimeout)
                }
                if (currentUrl != config.apiUrl) {
                    currentUrl = config.apiUrl
                    rebuildForgeApi(currentUrl)
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
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
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

    // --- CIVITAI SYNCHRONIZATION STATES ---
    private val _isCivitaiSyncing = MutableStateFlow(IndicatorState.IDLE)
    val isCivitaiSyncing: StateFlow<IndicatorState> = _isCivitaiSyncing.asStateFlow()

    fun cancelCivitaiSync() {
        if (_isCivitaiSyncing.value == IndicatorState.LOADING) {
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
        val conditionalLogger =
            okhttp3.Interceptor { chain ->
                val request = chain.request()
                val path = request.url.encodedPath
                val skipLogging = path.contains("progress") || path.contains("memory") || !getConfig().enableLogging
                if (skipLogging) {
                    chain.proceed(request)
                } else {
                    logging.intercept(chain)
                }
            }

        client =
            OkHttpClient
                .Builder()
                .connectTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
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
                    chain.proceed(requestBuilder.build())
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
            ForgeRepository.forgeApi = forgeApi
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Failed to initialize ForgeApi with URL: $cleanUrl. Exception: $e")
        }
    }

    fun changeCheckpoint(modelTitle: String) {
        _selectedModel.value = modelTitle
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

    /**
     * Main data fetch operation to pull server properties needed for UI setup (Checkpoints, Samplers, Schedulers, LoRAs).
     */
    fun fetchApiData() {
        fetchJob?.cancel()
        fetchJob =
            managerScope.launch(Dispatchers.IO) {
                if (forgeApi == null) return@launch

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
                                } catch (
                                    e: Exception,
                                ) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    Log.e(TAG, "Failed samplers: $e")
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

                                            if (!localDbModels.containsKey(sha256)) {
                                                newModelsToInsert.add(CivitaiModelEntity(sha256, type, name, "", null))
                                            }
                                        }

                                        if (newModelsToInsert.isNotEmpty()) {
                                            db.civitaiModelDao().insertModels(newModelsToInsert)
                                        }

                                        val updatedDbModels = db.civitaiModelDao().getAllModels().associateBy { it.sha256 }

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

                                if (!customApiSuccess) {
                                    try {
                                        val modelRes = forgeApi?.getSdModels()
                                        if (modelRes?.isSuccessful == true) {
                                            _models.value =
                                                modelRes.body()?.map { it.toDomain() }?.sortedBy { it.title.lowercase(Locale.getDefault()) }
                                                    ?: emptyList()
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
                            }

                        val defOpts =
                            async {
                                try {
                                    val res = forgeApi?.getOptions()
                                    if (res?.isSuccessful == true) {
                                        _selectedModel.value = res.body()?.sdModelCheckpoint ?: ""
                                    }
                                } catch (
                                    e: Exception,
                                ) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    Log.e(TAG, "Failed options: $e")
                                }
                            }

                        awaitAll(defSamplers, defSchedulers, defUpscalers, defModelsAndLoras, defOpts)
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.e(TAG, "Failed to synchronize API definitions: $e")
                }
            }
    }

    /**
     * Manually triggers Civitai synchronization. Implements a rate-limiting delay to prevent IP bans.
     */
    fun syncCivitaiModelsManual() {
        if (_isCivitaiSyncing.value != IndicatorState.IDLE) return

        managerScope.launch(Dispatchers.IO) {
            try {
                _isCivitaiSyncing.value = IndicatorState.LOADING
                _civitaiSyncLastResult.value = null

                val customRes = forgeApi?.getCustomModelsHashes()
                if (customRes?.isSuccessful != true) {
                    _civitaiSyncLastResult.value = "Error: No Custom API on Forge server."
                    _isCivitaiSyncing.value = IndicatorState.ERROR
                    delay(3000)
                    _isCivitaiSyncing.value = IndicatorState.IDLE
                    return@launch
                }

                val modelsList = customRes.body()?.models ?: emptyList()
                val localDbModels = db.civitaiModelDao().getAllModels().associateBy { it.sha256 }

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

                for ((index, cam) in missingOrIncomplete.withIndex()) {
                    var civName = cam.name ?: "Unknown"
                    val civType = cam.type ?: "checkpoint"
                    val sha256 = cam.sha256 ?: continue
                    var trainedWords = ""
                    var previewImage: String? = null

                    _civitaiSyncCurrentModel.value = civName
                    _civitaiSyncProgress.value = index to missingOrIncomplete.size

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
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        _civitaiSyncLastResult.value = "Network error"
                        hasError = true
                    }

                    val updatedEntity = CivitaiModelEntity(sha256, civType, civName, trainedWords, previewImage)
                    db.civitaiModelDao().insertModels(listOf(updatedEntity))
                    _civitaiSyncProgress.value = (index + 1) to missingOrIncomplete.size
                }

                _civitaiSyncLastResult.value = "Synchronization completed successfully"
                fetchApiData() // Refresh model resources from the local SQLite database to reflect synced metadata.

                _isCivitaiSyncing.value = if (hasError) IndicatorState.ERROR else IndicatorState.SUCCESS
                delay(2000)
                _isCivitaiSyncing.value = IndicatorState.IDLE
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Critical error during Civitai synchronization: $e")
                _civitaiSyncLastResult.value = "A critical error occurred"
                _isCivitaiSyncing.value = IndicatorState.ERROR
                delay(3000)
                _isCivitaiSyncing.value = IndicatorState.IDLE
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
