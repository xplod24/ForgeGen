package com.example.forgegen

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/* ============================================================================
 * IDIOMATIC COROUTINES EXTENSIONS
 * Extension left exclusively for downloading raw image bytes
 * bypassing Retrofit.
 * ============================================================================ */

suspend fun Call.awaitResponse(): Response =
    suspendCancellableCoroutine { continuation ->
        enqueue(
            object : Callback {
                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    continuation.resume(response)
                }

                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    if (continuation.isCancelled) return
                    continuation.resumeWithException(e)
                }
            },
        )

        continuation.invokeOnCancellation {
            try {
                cancel()
            } catch (_: Throwable) {
                // Ignore
            }
        }
    }

data class ActiveLora(
    val name: String,
    val strength: Float,
)

private val LORA_TAG = Regex("<lora:([^:>]+):(-?[0-9.]+)>")

/** LoRA tags of a prompt; shared by the main screen and the queue so both accept negative weights. */
fun parseActiveLoras(prompt: String): List<ActiveLora> =
    LORA_TAG
        .findAll(prompt)
        .map { match -> ActiveLora(match.groupValues[1], match.groupValues[2].toFloatOrNull() ?: 1f) }
        .toList()

/* ============================================================================
 * DATA REPOSITORY (SINGLETON)
 * Owns the database and the Retrofit client, pings the server (connection, VRAM,
 * external jobs) and keeps the foreground service in sync with the settings.
 * Queue, gallery, models and prompts live in their own Forge*Manager objects.
 * ============================================================================ */

@SuppressLint("StaticFieldLeak")
object ForgeRepository {
    private const val TAG = "ForgeAPI"

    private lateinit var application: Application
    lateinit var db: ForgeDatabase

    // RETROFIT APIS
    var forgeApi: ForgeApi? = null

    // For txt2img only: its client never repeats a request by itself. OkHttp retries a request whose connection
    // dropped, which for txt2img sent the same job to the server again (up to four times) without the queue knowing.
    var generationApi: ForgeApi? = null

    val repositoryScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private fun showSnackbar(message: String) = ForgeSettingsManager.showSnackbar(message)

    fun showToast(message: String) = ForgeSettingsManager.showToast(message)

    // --- DELEGATED SETTINGS / STATE FROM ForgeSettingsManager ---
    val config: StateFlow<AppConfig> get() = ForgeSettingsManager.config
    val appState: StateFlow<AppState> get() = ForgeSettingsManager.appState
    val promptHistory: StateFlow<List<PromptHistoryItem>> get() = ForgeSettingsManager.promptHistory

    val client: OkHttpClient get() = ForgeSettingsManager.client

    val activeLoras: StateFlow<List<ActiveLora>> =
        ForgeSettingsManager.appState
            .map { state -> parseActiveLoras(state.positivePrompt) }
            .stateIn(repositoryScope, SharingStarted.Lazily, emptyList())

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _isAppInForeground = MutableStateFlow(true)

    private val _pingMs = MutableStateFlow(0L)
    val pingMs: StateFlow<Long> = _pingMs.asStateFlow()

    private val _currentJobNo = MutableStateFlow(0)
    val currentJobNo: StateFlow<Int> = _currentJobNo.asStateFlow()

    private val _currentJobCount = MutableStateFlow(0)
    val currentJobCount: StateFlow<Int> = _currentJobCount.asStateFlow()

    private val _isServerBusy = MutableStateFlow(false)
    val isServerBusy: StateFlow<Boolean> = _isServerBusy.asStateFlow()

    private val _vramUsage = MutableStateFlow<String?>(null)
    val vramUsage: StateFlow<String?> = _vramUsage.asStateFlow()

    val selectedModel: StateFlow<String> get() = ForgeModelManager.selectedModel

    private fun rebuildForgeApi(url: String) {
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
                    .addConverterFactory(GsonConverterFactory.create(ForgeSettingsManager.gson))
                    .build()
            forgeApi = retrofitForge.create(ForgeApi::class.java)
            generationApi =
                retrofitForge
                    .newBuilder()
                    .client(client.newBuilder().retryOnConnectionFailure(false).build())
                    .build()
                    .create(ForgeApi::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ForgeApi with URL: $cleanUrl", e)
        }
    }

    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `app_settings` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))")
        }
    }

    suspend fun initializeDatabaseAndSettings(app: Application) {
        if (ForgeSettingsManager.isInitialized.value) return
        application = app

        ForgeSettingsManager.updateInitStatus("Initializing Database...")
        db =
            Room
                .databaseBuilder(app, ForgeDatabase::class.java, "forge_db")
                .addMigrations(MIGRATION_9_10)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()

        ForgeSettingsManager.updateInitStatus("Loading Settings...")
        ForgeSettingsManager.init(app, db)
    }

    suspend fun initializeApiClientAndData() {
        ForgeSettingsManager.updateInitStatus("Connecting to Server...")
        rebuildForgeApi(config.value.apiUrl)

        // Models, samplers and LoRAs are fetched by ForgeNetworkManager when the connection comes up
        // or the URL changes; here only the Retrofit instance (URL / timeout) has to be rebuilt.
        ForgeSettingsManager.onApiUrlChanged = { newUrl ->
            rebuildForgeApi(newUrl)
        }
        // The service reads the new value from the config itself, it only has to be poked.
        ForgeSettingsManager.onPersistentServiceChanged = { refreshServiceState() }

        ForgeSettingsManager.updateInitStatus("Loading App Data...")
        refreshServiceState()

        startBackgroundPing()
    }

    private fun refreshServiceState() {
        val serviceIntent =
            Intent(application, GenerationService::class.java).apply {
                action = GenerationService.ACTION_UPDATE_PERSISTENCE
            }
        try {
            application.startService(serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service", e)
        }
    }

    fun setAppForegroundState(isForeground: Boolean) {
        _isAppInForeground.value = isForeground
    }

    fun loadPreset(name: String) = ForgeSettingsManager.loadPreset(name)

    fun deletePreset(name: String) = ForgeSettingsManager.deletePreset(name)

    /** Reads the server's working folder and txt2img output folder from the gallery extension ([galleryPrefix]). */
    fun fetchAutoConfig(galleryPrefix: String) {
        repositoryScope.launch(Dispatchers.IO) {
            try {
                val response = forgeApi?.getGlobalSettingsDynamic("$galleryPrefix/global_setting")
                if (response?.isSuccessful == true) {
                    val body = response.body()
                    val sdCwd = body?.sdCwd ?: ""
                    val outdirTxt2Img = body?.globalSetting?.outdirTxt2ImgSamples ?: ""

                    if (sdCwd.isNotEmpty()) {
                        val separator = if (sdCwd.contains("\\")) "\\" else "/"
                        val cleanOutdir = outdirTxt2Img.trimStart('/', '\\')
                        // An output folder set as an absolute path in Forge must not be put under the working folder.
                        val isAbsolute = outdirTxt2Img.startsWith("/") || Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(outdirTxt2Img)
                        val galleryPath =
                            when {
                                isAbsolute -> outdirTxt2Img
                                cleanOutdir.isNotEmpty() -> "$sdCwd$separator$cleanOutdir"
                                else -> sdCwd
                            }

                        val newConfig =
                            config.value.copy(
                                serverBasePath = sdCwd,
                                galleryPath = galleryPath,
                            )
                        ForgeSettingsManager.saveConfig(newConfig)
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

    fun getPreviewUrl(
        originalPath: String,
        isLora: Boolean = false,
    ): String {
        if (originalPath.isEmpty()) return ""
        if (originalPath.startsWith("http://") || originalPath.startsWith("https://")) {
            return originalPath
        }

        val urlStr = config.value.apiUrl.trimEnd('/')

        val sdCwd = config.value.serverBasePath
        val fullPath =
            if (sdCwd.isNotEmpty() && !originalPath.contains("\\") && !originalPath.contains("/")) {
                val separator = if (sdCwd.contains("\\")) "\\" else "/"
                val subDir = if (isLora) "models${separator}Lora" else "models${separator}Stable-diffusion"
                "$sdCwd$separator$subDir$separator$originalPath"
            } else {
                originalPath
            }

        val basePath = fullPath.substringBeforeLast(".safetensors").substringBeforeLast(".ckpt").substringBeforeLast(".pt")
        return "$urlStr/file=$basePath.preview.png"
    }

    private var pingJob: kotlinx.coroutines.Job? = null
    private const val MEMORY_STATS_EVERY = 5

    private fun connectionFailed(failCount: Int) {
        _isConnected.value = false
        _isServerBusy.value = false
        _vramUsage.value = null
        // A running job shows its own status (it waits for the server); otherwise say it now. It used to appear
        // only after as many failed pings as the timeout had seconds, which with the backoff took about 8 minutes.
        if (failCount >= 1 && !ForgeQueueManager.isGenerating.value) {
            ForgeQueueManager.updateStatusText("Connection lost")
        }
    }

    fun resetPingJob() {
        startBackgroundPing()
    }

    private fun startBackgroundPing() {
        pingJob?.cancel()
        pingJob = repositoryScope.launch(Dispatchers.IO) {
            var failCount = 0
            var pingCount = 0
            while (isActive) {
                try {
                    if (forgeApi != null) {
                        val start = System.currentTimeMillis()
                        // The live preview (a base64 image, sent with every answer while generating) is only shown
                        // on screen, so in the background it is not requested at all.
                        val response = forgeApi?.getProgress(skipImage = !_isAppInForeground.value)

                        if (response?.isSuccessful == true) {
                            _pingMs.value = System.currentTimeMillis() - start
                            _isConnected.value = true
                            failCount = 0

                            val progressData = response.body() ?: ProgressResponseDto()
                            val progressVal = progressData.progress.toFloat()
                            val etaVal = progressData.etaRelative
                            val jobCount = progressData.state?.jobCount ?: 0

                            val currentImageStr = progressData.currentImage ?: ""
                            val busy = progressVal > 0.001f || jobCount > 0
                            _isServerBusy.value = busy
                            ForgeQueueManager.updateExternalProgress(progressVal, etaVal, currentImageStr.ifEmpty { null })
                            if (currentImageStr.isEmpty() && !ForgeQueueManager.isGenerating.value) {
                                ForgeQueueManager.setLivePreviewImage(null)
                            }

                            progressData.state?.let { stateObj ->
                                _currentJobNo.value = stateObj.jobNo
                                _currentJobCount.value = stateObj.jobCount
                            }

                            if (busy) {
                                val jCount = progressData.state?.jobCount ?: 0
                                val jNo = (progressData.state?.jobNo ?: 0) + 1
                                val batchInfo = if (jCount > 1) "(Batch $jNo of $jCount) " else ""
                                val prefix = if (ForgeQueueManager.isGenerating.value) "Generating" else "External Task"
                                ForgeQueueManager.updateStatusText("$prefix $batchInfo... ${(progressVal * 100).toInt()}%")
                            } else if (!busy && !ForgeQueueManager.isGenerating.value) {
                                ForgeQueueManager.updateStatusText("Ready")
                            }
                        } else if (response?.code() == 401 || response?.code() == 403) {
                            _isConnected.value = false
                            _isServerBusy.value = false
                            failCount = 0
                            ForgeQueueManager.updateStatusText("Authentication Required.")
                        } else {
                            // E.g. a proxy answering 502 while Forge is down: as unreachable as no answer at all
                            // (the app used to stay "connected" and the queue kept sending jobs).
                            connectionFailed(++failCount)
                        }

                        // RAM/VRAM change slowly: every 5th ping instead of a second request each second.
                        if (failCount == 0 && pingCount++ % MEMORY_STATS_EVERY == 0) {
                            try {
                                val memRes = forgeApi?.getMemoryStats()
                                if (memRes?.isSuccessful == true) {
                                    val body = memRes.body()
                                    val gib = 1024.0 * 1024.0 * 1024.0
                                    val ramU = (body?.ram?.used ?: 0.0) / gib
                                    val ramT = (body?.ram?.total ?: 0.0) / gib
                                    val vramU = (body?.cuda?.system?.used ?: 0.0) / gib
                                    val vramT = (body?.cuda?.system?.total ?: 0.0) / gib
                                    val parts = mutableListOf<String>()
                                    if (ramT > 0) parts += "RAM: ${String.format(Locale.US, "%.1f/%.1f", ramU, ramT)}GB"
                                    if (vramT > 0) parts += "VRAM: ${String.format(Locale.US, "%.1f/%.1f", vramU, vramT)}GB"
                                    _vramUsage.value = parts.joinToString(" | ").ifEmpty { null }
                                }
                            } catch (_: Exception) {
                                _vramUsage.value = null
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    connectionFailed(++failCount)
                }

                val isForeground = _isAppInForeground.value
                val isActivelyGenerating = ForgeQueueManager.isGenerating.value || _isServerBusy.value

                val baseInterval = if (isForeground || isActivelyGenerating) 1000L else 10000L
                val finalDelay =
                    if (failCount > 0) {
                        val backoff =
                            when (failCount) {
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

    fun appendLora(name: String) {
        val current = appState.value.positivePrompt
        if (!current.contains("<lora:$name:")) {
            val base = current.trimEnd()
            val separator =
                when {
                    base.isEmpty() -> ""
                    base.endsWith(",") -> " "
                    else -> ", "
                }
            val newPrompt = base + separator + "<lora:$name:1.0>"
            ForgeSettingsManager.updateState { it.copy(positivePrompt = newPrompt) }
        }
    }

    fun updateLoraStrength(
        name: String,
        strength: Float,
    ) {
        val current = appState.value.positivePrompt
        val regex = Regex("<lora:${Regex.escape(name)}:-?[0-9.]+>")
        val formattedStrength = String.format(Locale.US, "%.2f", strength)
        // Lambda replacement: a '$' or '\' in the LoRA name must not be treated as a group reference.
        val newPrompt = current.replace(regex) { "<lora:$name:$formattedStrength>" }
        ForgeSettingsManager.updateState { it.copy(positivePrompt = newPrompt) }
    }

    fun removeLora(name: String) {
        val current = appState.value.positivePrompt
        val escapedName = Regex.escape(name)
        val newPrompt =
            current
                .replace(Regex("<lora:$escapedName:-?[0-9.]+>"), "")
                .replace(Regex(",\\s*,"), ",") // separator left behind by the removed tag
                .replace(Regex(" {2,}"), " ")
                .trim()
                .trim(',')
                .trim()
        ForgeSettingsManager.updateState { it.copy(positivePrompt = newPrompt) }
    }
}
