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
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.Locale

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

    // --- HTTP CLIENT AND RETROFIT API ---
    // The app's one client (ForgeSettingsManager.createClient), shared with the queue and the image loader.
    val client: OkHttpClient get() = ForgeSettingsManager.client

    var forgeApi: ForgeApi? = null
        private set

    @Volatile private var hasFetchedInitialData = false

    fun start() {
        managerScope.launch(Dispatchers.IO) {
            var currentUrl = ""
            var currentTimeout = -1
            ForgeRepository.config.collect { config ->
                // ForgeSettingsManager replaces the client before it publishes a new timeout.
                val timeoutChanged = currentTimeout != config.timeout
                if (timeoutChanged) currentTimeout = config.timeout
                if (currentUrl != config.apiUrl) {
                    currentUrl = config.apiUrl
                    rebuildForgeApi(currentUrl)
                    ForgeGalleryManager.onServerChanged()
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

    // Hires fix's latent modes (3.0.1), the usual ones until the server lists its own.
    private val _latentModes = MutableStateFlow(HiresUpscalers.LATENT_MODES)
    val latentModes: StateFlow<List<String>> = _latentModes.asStateFlow()

    private val _availableLoras = MutableStateFlow<List<ApiResource>>(emptyList())
    val availableLoras: StateFlow<List<ApiResource>> = _availableLoras.asStateFlow()

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
                // Counted when it completes, its children included, so the gallery extension is also known by then.
                coroutineContext.job.invokeOnCompletion { cause -> if (cause == null) fetchesDone.update { it + 1 } }

                // The gallery extension, its folders, and then the gallery index.
                launch { ForgeGalleryManager.detectExtension() }

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
                                    // A server without the list keeps the usual latent modes.
                                    try {
                                        val latent = forgeApi?.getLatentUpscaleModes()
                                        if (latent?.isSuccessful == true) {
                                            _latentModes.value = HiresUpscalers.latentModes(latent.body()?.map { it.name })
                                        }
                                    } catch (e: Exception) {
                                        if (e is kotlinx.coroutines.CancellationException) throw e
                                        Log.w(TAG, "No latent upscale modes: $e")
                                    }
                                    val res = forgeApi?.getUpscalers()
                                    if (res?.isSuccessful == true) {
                                        val names = res.body()?.map { it.name }.orEmpty()
                                        _upscalers.value = HiresUpscalers.upscalers(_latentModes.value, names)
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
                                    val api = forgeApi
                                    val res = api?.getOptions()
                                    if (res?.isSuccessful == true) {
                                        ForgeModelManager.updateState(selectedModel = res.body()?.sdModelCheckpoint ?: "")
                                        // Tag suggestions: the tagcomplete extension's list, when it is not saved yet.
                                        ForgeTagManager.onServerOptions(api, res.body(), getConfig().apiUrl)
                                    }
                                } catch (
                                    e: Exception,
                                ) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    Log.e(TAG, "Failed options: $e")
                                }
                            }

                        // The VAEs and text encoders model settings pick from (3.0.0): Forge's list, else A1111's VAEs.
                        val defModules = async { fetchModules() }

                        awaitAll(defSamplers, defSchedulers, defUpscalers, defModelsAndLoras, defOpts, defModules)
                        lastFetchHadLists = defSamplers.await() && defModelsAndLoras.await()
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.e(TAG, "Failed to synchronize API definitions: $e")
                    lastFetchHadLists = false
                }
            }
    }

    /** The VAEs and text encoders: Forge's module list, else A1111's VAEs, else none. */
    private suspend fun fetchModules() {
        try {
            val forge = forgeApi?.getSdModules()
            if (forge?.isSuccessful == true) {
                val modules = forge.body().orEmpty().mapNotNull { ModelSettingsRules.module(it.modelName, it.filename) }
                ForgeModelManager.updateModules(modules, ModuleSupport.FORGE)
            } else {
                val a1111 = forgeApi?.getSdVaes()
                if (a1111?.isSuccessful == true) {
                    val vaes =
                        a1111.body().orEmpty().mapNotNull {
                            ModelSettingsRules.module(it.modelName, it.filename)?.copy(kind = ServerModule.Kind.VAE)
                        }
                    ForgeModelManager.updateModules(vaes, ModuleSupport.A1111)
                } else {
                    ForgeModelManager.updateModules(emptyList(), ModuleSupport.NONE)
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Failed modules: $e")
        }
    }

    /**
     * Refresh in the VAE and text encoder lists (3.0.1): the server rescans its VAE folder, then both lists are read
     * again. Forge Neo's refresh-vae only rescans A1111's VAE list; its own list (sd-modules) is made at start and by
     * Refresh in its web UI, which the API cannot do, so there the lists only catch up with the server.
     */
    fun refreshModules(onResult: (String) -> Unit) {
        managerScope.launch(Dispatchers.IO) {
            val rescanned =
                try {
                    forgeApi?.refreshVae()?.isSuccessful == true
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    false
                }
            fetchModules()
            onResult(
                when {
                    ForgeModelManager.moduleSupport.value == ModuleSupport.FORGE ->
                        "Lists read again. Forge adds new files after Refresh in its web UI or a restart."
                    rescanned -> "VAE list refreshed"
                    else -> "Could not refresh the VAE list"
                },
            )
        }
    }

    fun refreshCheckpoints(onResult: (Boolean, String) -> Unit) {
        managerScope.launch(Dispatchers.IO) {
            try {
                val res = forgeApi?.refreshCheckpoints()
                if (res?.isSuccessful == true) {
                    ResourcePreviews.forget() // new pictures may have come with the new files
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
                    ResourcePreviews.forget() // new pictures may have come with the new files
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
