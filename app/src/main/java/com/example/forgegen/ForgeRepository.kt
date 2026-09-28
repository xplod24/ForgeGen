package com.example.forgegen

import android.annotation.SuppressLint
import android.app.Application
import android.util.Log
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
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

/**
 * The app and the server: CONNECTED, SEARCHING (the server stopped answering or never answered; the app tries every
 * 2 s for a minute) or OFFLINE (the minute passed; nothing is sent until the user asks for another try, the app
 * returns to the screen or the phone's network comes back). An active queue never goes OFFLINE: it keeps trying.
 */
enum class ServerConnection { CONNECTED, SEARCHING, OFFLINE }

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

    lateinit var db: ForgeDatabase

    // RETROFIT APIS
    var forgeApi: ForgeApi? = null

    // For txt2img only: its client never repeats a request by itself. OkHttp retries a request whose connection
    // dropped, which for txt2img sent the same job to the server again (up to four times) without the queue knowing.
    var generationApi: ForgeApi? = null

    val repositoryScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

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

    private val _connection = MutableStateFlow(ServerConnection.SEARCHING)
    val connection: StateFlow<ServerConnection> = _connection.asStateFlow()

    // When the current search gives up (ms since 1970), for the countdown on screen.
    private val _searchEndsAt = MutableStateFlow(0L)
    val searchEndsAt: StateFlow<Long> = _searchEndsAt.asStateFlow()

    private val _pingMs = MutableStateFlow(0L)
    val pingMs: StateFlow<Long> = _pingMs.asStateFlow()

    private val _currentJobNo = MutableStateFlow(0)
    val currentJobNo: StateFlow<Int> = _currentJobNo.asStateFlow()

    private val _currentJobCount = MutableStateFlow(0)
    val currentJobCount: StateFlow<Int> = _currentJobCount.asStateFlow()

    private val _isServerBusy = MutableStateFlow(false)
    val isServerBusy: StateFlow<Boolean> = _isServerBusy.asStateFlow()

    // The server's RAM and VRAM (/sdapi/v1/memory): read every few pings, and at once after Unload Model.
    private val _serverMemory = MutableStateFlow<ServerMemory?>(null)
    val serverMemory: StateFlow<ServerMemory?> = _serverMemory.asStateFlow()

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

    // 1.3.0: Civitai's image ratings and the model's NSFW and real-person flags.
    val MIGRATION_10_11 =
        object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `civitai_models` ADD COLUMN `previewImages` TEXT")
                db.execSQL("ALTER TABLE `civitai_models` ADD COLUMN `nsfw` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `civitai_models` ADD COLUMN `realPerson` INTEGER NOT NULL DEFAULT 0")
            }
        }

    // 1.6.1: Civitai sync removed (model data comes from the server).
    val MIGRATION_11_12 =
        object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `civitai_models`")
            }
        }

    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `app_settings` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))")
        }
    }

    suspend fun initializeDatabaseAndSettings(app: Application) {
        if (ForgeSettingsManager.isInitialized.value) return

        ForgeSettingsManager.updateInitStatus("Initializing Database...")
        db =
            Room
                .databaseBuilder(app, ForgeDatabase::class.java, "forge_db")
                .addMigrations(MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()

        ForgeSettingsManager.updateInitStatus("Loading Settings...")
        ForgeSettingsManager.init(app, db)
    }

    suspend fun initializeApiClientAndData() {
        ForgeSettingsManager.updateInitStatus("Preparing API Clients...")
        rebuildForgeApi(config.value.apiUrl)

        // Models, samplers and LoRAs are fetched by ForgeNetworkManager when the connection comes up
        // or the URL changes; here only the Retrofit instance (URL / timeout) has to be rebuilt.
        ForgeSettingsManager.onApiUrlChanged = { newUrl ->
            rebuildForgeApi(newUrl)
        }

        startBackgroundPing()
    }

    fun setAppForegroundState(isForeground: Boolean) {
        _isAppInForeground.value = isForeground
        if (isForeground) {
            reconnect() // back on screen: the server may be back too (a new minute of tries when it is not connected)
            wakePing.trySend(Unit)
        } else {
            ForgeSettingsManager.flushState()
        }
    }

    fun loadPreset(name: String) = ForgeSettingsManager.loadPreset(name)

    fun deletePreset(name: String) = ForgeSettingsManager.deletePreset(name)

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

    // How long the app looks for a server that does not answer, and how often it asks meanwhile (shorter in tests).
    @Volatile internal var searchWindowMs = 60_000L

    @Volatile internal var searchPingMs = 2_000L

    // Ends a wait between pings early (back on screen, another try asked for).
    private val wakePing = Channel<Unit>(Channel.CONFLATED)

    // Finished pings, answered or not, so the start can wait for the server's first answer.
    private val pingRounds = MutableStateFlow(0)

    /** Waits (at most [timeoutMs]) until the server has been pinged once; true when it answered. */
    suspend fun awaitServerCheck(timeoutMs: Long): Boolean {
        withTimeoutOrNull(timeoutMs) { pingRounds.first { it > 0 } }
        return _isConnected.value
    }

    /** Another minute of tries: after "Retry", a new address, a return to the screen or the network coming back. */
    fun reconnect() {
        if (_isConnected.value) return
        startSearch()
        wakePing.trySend(Unit)
    }

    /** Asks the server now instead of after the current wait (a job just started: its progress should show at once). */
    fun pingNow() {
        wakePing.trySend(Unit)
    }

    private fun startSearch() {
        _searchEndsAt.value = System.currentTimeMillis() + searchWindowMs
        _connection.value = ServerConnection.SEARCHING
    }

    /**
     * Jobs are waiting or running: the queue needs the server, so it is never given up. Not while it only waits for
     * its scheduled start ("Start at"), maybe for hours; it asks again when that time comes.
     */
    private fun queueNeedsServer() = ForgeQueueManager.isQueueActive.value && !ForgeQueueManager.isWaitingForSchedule.value

    private fun connectionFailed(failCount: Int) {
        _isConnected.value = false
        _isServerBusy.value = false
        _serverMemory.value = null
        when {
            _connection.value == ServerConnection.CONNECTED -> startSearch() // lost: a minute of tries starts now
            System.currentTimeMillis() >= _searchEndsAt.value && !queueNeedsServer() ->
                _connection.value = ServerConnection.OFFLINE
        }
        // A running job shows its own status (it waits for the server); otherwise say it now. It used to appear
        // only after as many failed pings as the timeout had seconds, which with the backoff took about 8 minutes.
        if (failCount >= 1 && !ForgeQueueManager.isGenerating.value) {
            ForgeQueueManager.updateStatusText("Connection lost")
        }
    }

    /** Reads the server's RAM and VRAM now (3.0.0-4: right after Unload Model, not up to 10 s later). */
    suspend fun refreshServerMemory() {
        try {
            val response = forgeApi?.getMemoryStats()
            if (response?.isSuccessful == true) _serverMemory.value = ServerMemory.of(response.body())
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _serverMemory.value = null
        }
    }

    fun resetPingJob() {
        startBackgroundPing()
    }

    /**
     * Waits until a ping is useful: not OFFLINE (unless the queue needs the server), and on screen or with work
     * going on. In the background with nothing to do the app asked the server every 10 s for as long as it lived.
     */
    private suspend fun awaitPingNeeded() {
        combine(
            combine(ForgeQueueManager.isQueueActive, ForgeQueueManager.isWaitingForSchedule) { active, waiting -> active && !waiting },
            _connection,
            _isAppInForeground,
            ForgeQueueManager.isGenerating,
            _isServerBusy,
        ) { queueWorking, connection, foreground, generating, busy ->
            val working = queueWorking || generating || busy
            (connection != ServerConnection.OFFLINE || working) && (foreground || working)
        }.first { it }
    }

    private fun startBackgroundPing() {
        pingJob?.cancel()
        if (!_isConnected.value) startSearch()
        pingJob = repositoryScope.launch(Dispatchers.IO) {
            var failCount = 0
            var pingCount = 0
            while (isActive) {
                awaitPingNeeded()
                try {
                    if (forgeApi != null) {
                        val start = System.currentTimeMillis()
                        // The live preview (a base64 image, sent with every answer while generating) is only shown
                        // on screen, so in the background it is not requested at all.
                        val response = forgeApi?.getProgress(skipImage = !_isAppInForeground.value)

                        if (response?.isSuccessful == true) {
                            _pingMs.value = System.currentTimeMillis() - start
                            _isConnected.value = true
                            _connection.value = ServerConnection.CONNECTED
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
                            connectionFailed(failCount) // reachable, but of no use without access; not backed off
                            ForgeQueueManager.updateStatusText("Authentication Required.")
                        } else {
                            // E.g. a proxy answering 502 while Forge is down: as unreachable as no answer at all
                            // (the app used to stay "connected" and the queue kept sending jobs).
                            connectionFailed(++failCount)
                        }

                        // RAM/VRAM change slowly: every 5th ping instead of a second request each second.
                        if (failCount == 0 && _isConnected.value && pingCount++ % MEMORY_STATS_EVERY == 0) {
                            refreshServerMemory()
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    connectionFailed(++failCount)
                }
                pingRounds.update { it + 1 }

                val delayMs =
                    pingDelay(
                        connected = _isConnected.value,
                        foreground = _isAppInForeground.value,
                        generating = ForgeQueueManager.isGenerating.value || _isServerBusy.value,
                        searching = System.currentTimeMillis() < _searchEndsAt.value,
                        failCount = failCount,
                    )
                withTimeoutOrNull(delayMs) { wakePing.receive() }
            }
        }
    }

    /**
     * The wait before the next ping. Connected: every second while images are generated (progress and preview),
     * every 2 s on screen, every 10 s in the background (only while the queue works). Not connected: every
     * [searchPingMs] during the minute of tries, then (only an active queue keeps trying) 5 s, 10 s, 30 s, 1 min.
     */
    internal fun pingDelay(
        connected: Boolean,
        foreground: Boolean,
        generating: Boolean,
        searching: Boolean,
        failCount: Int,
    ): Long =
        when {
            connected && generating -> 1_000L
            connected && foreground -> 2_000L
            connected -> 10_000L
            searching -> searchPingMs
            else ->
                when (failCount) {
                    0, 1 -> 5_000L
                    2 -> 10_000L
                    3 -> 30_000L
                    else -> 60_000L
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

    /** Adds `<lora:name:1.0>` to the positive prompt, unless the LoRA is already in it. */
    fun addLora(name: String) {
        val current = appState.value.positivePrompt
        if (current.contains("<lora:$name:")) return
        val separator =
            when {
                current.isBlank() -> ""
                current.trimEnd().endsWith(",") -> " "
                else -> ", "
            }
        ForgeSettingsManager.updateState { it.copy(positivePrompt = current.trimEnd() + separator + "<lora:$name:1.0>") }
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
