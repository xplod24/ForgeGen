package com.example.forgegen

import android.annotation.SuppressLint
import android.app.Application
import android.util.Log
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
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
            taskProgressSupported = true // another server may have its web UI
            _serverInfo.value = null
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

    // This app (io.github.xplod24.forgegen) started at 3.5.2-2 with database 13, so only the steps from 13 on are here;
    // a database older than that (never this app's) is built anew by the fallback below.

    // 3.6.0: the rest of each image's generation settings (IndexDetails; old rows are read once more, details = 0) and
    // the jobs the app sent with their phases (JobRunEntity).
    val MIGRATION_13_14 =
        object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val columns =
                    "width INTEGER, height INTEGER, steps INTEGER, cfg REAL, distilledCfg REAL, scheduler TEXT, hiresScale REAL, " +
                        "hiresUpscaler TEXT, hiresSteps INTEGER, denoising REAL, modules TEXT, embeddings TEXT, clipSkip INTEGER, " +
                        "forgeVersion TEXT, details INTEGER NOT NULL DEFAULT 0"
                columns.split(", ").forEach { column -> db.execSQL("ALTER TABLE `gallery_images` ADD COLUMN $column") }
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `job_runs` (`id` TEXT NOT NULL, `server` TEXT NOT NULL, `queueJobId` TEXT, " +
                        "`startedAt` INTEGER NOT NULL, `model` TEXT NOT NULL, `modules` TEXT NOT NULL, `previousModel` TEXT, " +
                        "`startKind` TEXT NOT NULL, `firstHash` INTEGER NOT NULL, `width` INTEGER NOT NULL, " +
                        "`height` INTEGER NOT NULL, `images` INTEGER NOT NULL, `steps` INTEGER NOT NULL, `sampler` TEXT NOT NULL, " +
                        "`scheduler` TEXT NOT NULL, `hiresScale` REAL, `hiresSteps` INTEGER, `loadMs` INTEGER, `vramMs` INTEGER, " +
                        "`firstStepMs` INTEGER, `samplingMs` INTEGER, `hiresMs` INTEGER, `sendMs` INTEGER, " +
                        "`totalMs` INTEGER NOT NULL, `itPerSec` REAL, `hiresItPerSec` REAL, `vramBeforeGb` REAL, " +
                        "`vramPeakGb` REAL, `vramTotalGb` REAL, `vramCurve` TEXT, `outcome` TEXT NOT NULL, `failure` TEXT, " +
                        "`failureText` TEXT, PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_job_runs_startedAt` ON `job_runs` (`startedAt`)")
            }
        }

    suspend fun initializeDatabaseAndSettings(app: Application) {
        if (ForgeSettingsManager.isInitialized.value) return

        ForgeSettingsManager.updateInitStatus("Initializing Database...")
        db =
            Room
                .databaseBuilder(app, ForgeDatabase::class.java, "forge_db")
                .addMigrations(MIGRATION_13_14)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()

        ForgeSettingsManager.updateInitStatus("Loading Settings...")
        ForgeSettingsManager.init(app, db)
    }

    /**
     * For an alarm that wakes a closed app (Unload After the Queue, 3.6.0): the settings and the API client, without
     * the ping or the service; nothing changes when the app runs.
     */
    suspend fun prepareApi(app: Application) {
        initializeDatabaseAndSettings(app)
        if (forgeApi == null) rebuildForgeApi(config.value.apiUrl)
    }

    suspend fun initializeApiClientAndData() {
        ForgeSettingsManager.updateInitStatus("Preparing API Clients...")
        rebuildForgeApi(config.value.apiUrl)

        // Models, samplers and LoRAs are fetched by ForgeNetworkManager when the connection comes up
        // or the URL changes; here only the Retrofit instance (URL / timeout) has to be rebuilt.
        ForgeSettingsManager.onApiUrlChanged = { newUrl ->
            rebuildForgeApi(newUrl)
        }

        rememberResourcePreviews()
        startBackgroundPing()
    }

    private const val RESOURCE_PREVIEWS_KEY = "resource_previews"
    private const val RESOURCE_PREVIEWS_SAVE_DELAY_MS = 2_000L

    /**
     * Which pictures the models and LoRAs have (ResourcePreviews), kept across starts (3.4.0): the LoRA list used to ask
     * the server up to 8 times again for every LoRA without a picture after each start.
     */
    private fun rememberResourcePreviews() {
        repositoryScope.launch(Dispatchers.IO) {
            try {
                db.appSettingDao().getSetting(RESOURCE_PREVIEWS_KEY)?.value?.let { json ->
                    val type = object : com.google.gson.reflect.TypeToken<Map<String, Int>>() {}.type
                    ResourcePreviews.restore(ForgeSettingsManager.gson.fromJson(json, type))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Saved model pictures unreadable: $e")
            }
            ResourcePreviews.changes.drop(1).collectLatest {
                delay(RESOURCE_PREVIEWS_SAVE_DELAY_MS) // a list that scrolls learns many at once
                try {
                    val json = ForgeSettingsManager.gson.toJson(ResourcePreviews.saved())
                    db.appSettingDao().putSetting(AppSettingEntity(RESOURCE_PREVIEWS_KEY, json))
                } catch (e: Exception) {
                    Log.w(TAG, "Model pictures not saved: $e")
                }
            }
        }
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

    /**
     * The pictures a model or LoRA may have on the server, in the order to try them (ResourcePreviews, 3.0.1). A bare
     * file name is looked for in the server's default model folders.
     */
    fun previewCandidates(
        originalPath: String,
        isLora: Boolean = false,
    ): List<String> {
        if (originalPath.isEmpty()) return emptyList()
        if (originalPath.startsWith("http://") || originalPath.startsWith("https://")) return listOf(originalPath)

        var serverUrl =
            config.value.apiUrl
                .trim()
                .trimEnd('/')
        if (serverUrl.isNotEmpty() && !serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
            serverUrl = "http://$serverUrl"
        }

        val sdCwd = config.value.serverBasePath
        val fullPath =
            if (sdCwd.isNotEmpty() && !originalPath.contains("\\") && !originalPath.contains("/")) {
                val separator = if (sdCwd.contains("\\")) "\\" else "/"
                val subDir = if (isLora) "models${separator}Lora" else "models${separator}Stable-diffusion"
                "$sdCwd$separator$subDir$separator$originalPath"
            } else {
                originalPath
            }
        return ResourcePreviews.candidates(serverUrl, fullPath)
    }

    private var pingJob: kotlinx.coroutines.Job? = null

    // RAM/VRAM change slowly: read every few seconds, and only while the memory meters can be seen (3.4.0).
    private const val MEMORY_EVERY_GENERATING_MS = 5_000L
    private const val MEMORY_EVERY_IDLE_MS = 10_000L

    /** The live preview is asked for only while the main screen shows it (3.4.0; before, whenever the app was open). */
    private fun previewWanted() = _isAppInForeground.value && ForgeQueueManager.previewShown && config.value.livePreview

    private fun memoryWanted() = _isAppInForeground.value && config.value.memoryMeters

    // How long the app looks for a server that does not answer, and how often it asks meanwhile (shorter in tests).
    @Volatile internal var searchWindowMs = 60_000L

    @Volatile internal var searchPingMs = 2_000L

    // Since when the server, still connected, has been too busy to answer a ping (SlowServer, 3.5.3); 0 while it answers.
    @Volatile private var slowSince = 0L

    /**
     * A ping that timed out on a server too busy to answer (SlowServer, 3.5.3: Forge loading a checkpoint): the app stays
     * connected, the server counts as busy and the queue says what it waits for. False when it is a lost connection.
     */
    private fun serverTooBusy(e: Exception): Boolean {
        if (_connection.value != ServerConnection.CONNECTED || !SlowServer.isSlowAnswer(e)) return false
        val now = System.currentTimeMillis()
        if (slowSince == 0L) slowSince = now
        if (now - slowSince > SlowServer.maxSlowMs || !SlowServer.stillListening(config.value.apiUrl)) return false
        _isServerBusy.value = true
        ForgeQueueManager.updateStatusText(ForgeQueueManager.slowServerText())
        return true
    }

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
        // While Forge restarts (3.3.0) the app waits for it longer than the minute given to a lost connection.
        val restartEnds = _restartingSince.value.takeIf { it > 0 }?.plus(restartWaitMs) ?: 0L
        _searchEndsAt.value = maxOf(System.currentTimeMillis() + searchWindowMs, restartEnds)
        _connection.value = ServerConnection.SEARCHING
    }

    // --- The server's queue and restarting Forge (3.3.0) ---

    // Whether the server answers /internal/progress: not without its web UI (--nowebui, 404) or behind its login (401).
    @Volatile private var taskProgressSupported = true

    // The task the server was seen doing (or done with): it is not asked about again.
    @Volatile private var taskSeenStarted: String? = null

    private val taskProgressUnsupported = setOf(401, 403, 404, 405, 422)

    /**
     * How many jobs the server does before the running job (from its web UI or another app); 0 when it is doing ours,
     * when ours has not arrived yet, or when the server cannot tell.
     */
    private suspend fun jobsAheadOfOurs(api: ForgeApi): Int {
        val taskId = ForgeQueueManager.runningTaskId ?: return 0
        // Settings > Features > Other Jobs on the Server (3.4.0): off, the server is not asked.
        if (!config.value.serverQueue || !taskProgressSupported || taskSeenStarted == taskId) return 0
        val state =
            try {
                api.getTaskProgress(TaskProgressRequestDto(taskId))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                return 0
            }
        if (!state.isSuccessful) {
            if (state.code() in taskProgressUnsupported) taskProgressSupported = false
            return 0
        }
        val task = state.body() ?: return 0
        if (task.active || task.completed) {
            taskSeenStarted = taskId
            return 0
        }
        if (!task.queued) return 0
        val pending =
            try {
                api
                    .getPendingTasks()
                    .takeIf { it.isSuccessful }
                    ?.body()
                    ?.tasks
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        return pending?.let { ServerTasks.jobsAhead(taskId, it) } ?: ServerTasks.jobsAhead(task.textinfo) ?: 1
    }

    private val _restartingSince = MutableStateFlow(0L)

    /** When Restart Forge was asked for (ms since 1970); 0 while Forge is not restarting. */
    val restartingSince: StateFlow<Long> = _restartingSince.asStateFlow()

    // Forge was seen gone after the restart was asked for: the next answer is the restarted server.
    @Volatile private var restartOutageSeen = false

    /** How long the app waits for a restarting Forge before it is given up like any lost server. */
    @Volatile internal var restartWaitMs = 180_000L

    const val RESTART_NEEDS_FLAG = "Forge has to be started with --api-server-stop to be restarted from the phone."
    const val RESTART_NOT_POSSIBLE =
        "Forge was not started with webui.bat or webui.sh, so it cannot start itself again. Restart it on the PC."

    /** Restarts Forge: null when it is restarting (the app waits for it), else why it cannot. */
    suspend fun restartServer(): String? {
        val api = forgeApi ?: return "Not connected to the server."
        val response =
            try {
                api.restartServer()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                null // Forge quits at once, mostly before it answers
            }
        val code = response?.code()
        when {
            code == 404 || code == 405 -> return RESTART_NEEDS_FLAG
            code == 501 -> return RESTART_NOT_POSSIBLE
            code != null && code >= 400 -> return "The server refused to restart (HTTP $code)."
        }
        restartOutageSeen = false
        _restartingSince.value = System.currentTimeMillis()
        _serverInfo.value = null // read again once it is back
        JobRecorder.modelUnloaded() // Forge starts without a model: the next job starts cold (3.6.0)
        pingNow()
        return null
    }

    /** After each ping: the restart is over when Forge answers again after it was gone (or it never went away). */
    private fun followRestart(connected: Boolean) {
        val since = _restartingSince.value
        if (since == 0L) return
        val waited = System.currentTimeMillis() - since
        when {
            !connected -> {
                restartOutageSeen = true
                if (waited > restartWaitMs) {
                    _restartingSince.value = 0L
                    showToast("Forge did not come back within ${restartWaitMs / 60_000} minutes")
                }
            }
            restartOutageSeen -> {
                _restartingSince.value = 0L
                showToast("Forge is back")
            }
            waited > RESTART_NOT_GONE_MS -> _restartingSince.value = 0L // it never went away
        }
    }

    private const val RESTART_NOT_GONE_MS = 20_000L

    private val _serverInfo = MutableStateFlow<ServerInfo?>(null)

    /** What the server page shows; null until it is read (3.3.0). */
    val serverInfo: StateFlow<ServerInfo?> = _serverInfo.asStateFlow()

    private var serverInfoJob: Job? = null

    /**
     * Reads the light part of the server page, once per server ([again]: anew): how Forge was started and its
     * extensions. Its report waits for "Check Now" (3.6.0, [checkServer]).
     */
    fun loadServerInfo(again: Boolean = false) {
        if (!again && (_serverInfo.value != null || serverInfoJob?.isActive == true)) return
        val api = forgeApi ?: return
        serverInfoJob?.cancel()
        serverInfoJob =
            repositoryScope.launch(Dispatchers.IO) {
                _serverInfo.value = ServerInfo()
                val flags = answer { api.getCmdFlags() }
                val extensions = answer { api.getExtensions() }
                _serverInfo.value =
                    ServerInfo(
                        canRestart = flags?.body()?.let { ServerInfoParser.canRestart(it) },
                        extensions = extensions?.takeIf { it.isSuccessful }?.body()?.let { ServerInfoParser.extensions(it) },
                    )
            }
    }

    private const val SERVER_CHECK_KEY = "server_check:"

    private val _serverCheck = MutableStateFlow<ServerCheck?>(null)

    /** The last "Check Now" of the current server (3.6.0), kept until the next one; null before the first. */
    val serverCheck: StateFlow<ServerCheck?> = _serverCheck.asStateFlow()

    private val _checkingSince = MutableStateFlow(0L)

    /** When the running "Check Now" began (0: none runs); Forge takes a few seconds for its report. */
    val checkingSince: StateFlow<Long> = _checkingSince.asStateFlow()

    private val _checkProblem = MutableStateFlow<String?>(null)

    /** Why the last "Check Now" found nothing (e.g. a server without its web UI); null when it worked. */
    val checkProblem: StateFlow<String?> = _checkProblem.asStateFlow()

    // The server whose check [serverCheck] holds.
    @Volatile private var checkedServer: String? = null

    /** Reads the current server's last check from the settings, when the server page shows. */
    fun loadServerCheck() {
        val server = GalleryKey.serverOf(config.value.apiUrl)
        if (server == checkedServer) return
        checkedServer = server
        _serverCheck.value = null
        _checkProblem.value = null
        repositoryScope.launch(Dispatchers.IO) {
            val saved =
                try {
                    db.appSettingDao().getSetting(SERVER_CHECK_KEY + server)?.value?.let {
                        ForgeSettingsManager.gson.fromJson(it, ServerCheck::class.java)
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w(TAG, "Could not read the last server check", e)
                    null
                }
            if (checkedServer == server) _serverCheck.value = saved
        }
    }

    /**
     * "Check Now" (3.6.0): Forge's report and its VRAM counters, kept for this server. The last check stays on the
     * page until the new one is in.
     */
    fun checkServer() {
        val api = forgeApi ?: return
        if (_checkingSince.value != 0L) return
        val server = GalleryKey.serverOf(config.value.apiUrl)
        _checkingSince.value = System.currentTimeMillis()
        repositoryScope.launch(Dispatchers.IO) {
            try {
                val report = answer { api.getSysinfo() }
                val memory = answer { api.getMemoryStats() }?.takeIf { it.isSuccessful }?.body()
                ServerMemory.of(memory)?.let { _serverMemory.value = it }
                val problem =
                    when {
                        report == null -> "The server did not answer."
                        report.isSuccessful -> null
                        report.code() == 404 -> "Forge runs without its web UI (--nowebui), which gives the report."
                        report.code() == 401 || report.code() == 403 -> "Forge's web UI asks for a login, so its report cannot be read."
                        else -> "The report could not be read (HTTP ${report.code()})."
                    }
                _checkProblem.value = problem
                if (problem != null || report == null) return@launch
                val check = ServerInfoParser.check(report.body()?.string().orEmpty(), memory, System.currentTimeMillis())
                checkedServer = server
                _serverCheck.value = check
                db.appSettingDao().putSetting(AppSettingEntity(SERVER_CHECK_KEY + server, ForgeSettingsManager.gson.toJson(check)))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Server check failed", e)
                _checkProblem.value = "The report could not be read."
            } finally {
                _checkingSince.value = 0L
            }
        }
    }

    /** [call]'s answer, or null when the server could not be reached. */
    private suspend fun <T> answer(call: suspend () -> retrofit2.Response<T>): retrofit2.Response<T>? =
        try {
            call()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    /**
     * Jobs are waiting or running: the queue needs the server, so it is never given up. Not while it only waits for
     * its scheduled start ("Start at"), maybe for hours; it asks again when that time comes.
     */
    private fun queueNeedsServer() = ForgeQueueManager.isQueueActive.value && !ForgeQueueManager.isWaitingForSchedule.value

    private fun connectionFailed(failCount: Int) {
        slowSince = 0L
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
        val start = System.currentTimeMillis()
        try {
            val response = forgeApi?.getMemoryStats()
            val answeredAt = System.currentTimeMillis()
            if (answeredAt - start >= JobTimeline.BUSY_ANSWER_MS) JobRecorder.onBusy(start, answeredAt)
            if (response?.isSuccessful == true) {
                _serverMemory.value = ServerMemory.of(response.body())
                JobRecorder.onVram(_serverMemory.value)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // A server too busy to answer keeps its last reading; a gone one shows none.
            if (SlowServer.isSlowAnswer(e)) JobRecorder.onBusy(start, System.currentTimeMillis()) else _serverMemory.value = null
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
            var memoryReadAt = 0L
            while (isActive) {
                awaitPingNeeded()
                var start = 0L
                try {
                    if (forgeApi != null) {
                        start = System.currentTimeMillis()
                        // The live preview (a base64 image, sent with every answer while generating) is asked for only
                        // while it can be seen: not in the background, the gallery, the queue or the settings.
                        val response = forgeApi?.getProgress(skipImage = !previewWanted())

                        if (response?.isSuccessful == true) {
                            slowSince = 0L
                            val answeredAt = System.currentTimeMillis()
                            _pingMs.value = answeredAt - start
                            // A running job's record (3.6.0): an answer this slow means the server is busy loading.
                            if (answeredAt - start >= JobTimeline.BUSY_ANSWER_MS) JobRecorder.onBusy(start, answeredAt)
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
                            // The running job waits while the server does others first (3.3.0): their progress and
                            // preview are not the job's.
                            val ahead = if (ForgeQueueManager.isGenerating.value) forgeApi?.let { jobsAheadOfOurs(it) } ?: 0 else 0
                            ForgeQueueManager.setServerJobsAhead(ahead)
                            if (ahead > 0) {
                                ForgeQueueManager.updateExternalProgress(0f, 0.0, null)
                                ForgeQueueManager.setLivePreviewImage(null)
                                JobRecorder.onJobsAhead()
                            } else {
                                ForgeQueueManager.updateExternalProgress(progressVal, etaVal, currentImageStr.ifEmpty { null })
                                progressData.state?.let { JobRecorder.onProgress(answeredAt, it) }
                            }
                            if (currentImageStr.isEmpty() && !ForgeQueueManager.isGenerating.value) {
                                ForgeQueueManager.setLivePreviewImage(null)
                            }

                            progressData.state?.let { stateObj ->
                                _currentJobNo.value = stateObj.jobNo
                                _currentJobCount.value = stateObj.jobCount
                            }

                            if (ahead > 0) {
                                ForgeQueueManager.updateStatusText("Waiting for the server: ${ServerTasks.aheadText(ahead)}")
                            } else if (busy) {
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

                        // A job's record reads the VRAM at every ping until a reading after its first step (3.6.0), then
                        // only as the meters do (3.4.0: on screen with the meters shown).
                        val memoryEvery =
                            when {
                                JobRecorder.wantsVram() -> 0L
                                ForgeQueueManager.isGenerating.value -> MEMORY_EVERY_GENERATING_MS
                                else -> MEMORY_EVERY_IDLE_MS
                            }
                        val now = System.currentTimeMillis()
                        val wanted = memoryWanted() || JobRecorder.wantsVram()
                        if (failCount == 0 && _isConnected.value && wanted && now - memoryReadAt >= memoryEvery) {
                            memoryReadAt = now
                            refreshServerMemory()
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    if (SlowServer.isSlowAnswer(e) && start > 0) JobRecorder.onBusy(start, System.currentTimeMillis())
                    if (!serverTooBusy(e)) connectionFailed(++failCount)
                }
                pingRounds.update { it + 1 }
                followRestart(_isConnected.value)

                val delayMs =
                    pingDelay(
                        connected = _isConnected.value,
                        foreground = _isAppInForeground.value,
                        generating = ForgeQueueManager.isGenerating.value || _isServerBusy.value,
                        searching = System.currentTimeMillis() < _searchEndsAt.value,
                        failCount = failCount,
                        jobsWaiting = ForgeQueueManager.generationQueue.value.any { it.status != GenerationStatus.FAILED },
                    )
                withTimeoutOrNull(delayMs) { wakePing.receive() }
            }
        }
    }

    /**
     * The wait before the next ping. Connected: every second while images are generated on screen (progress and
     * preview), every 2 s while they are generated in the background (3.4.0: the notification shows no more than that)
     * and on screen while [jobsWaiting], every 4 s on screen with nothing to do (3.6.0-1; 2 s before: a job started
     * asks at once, ForgeQueueManager), every 10 s in the background (only while the queue works). Not connected: every
     * [searchPingMs] during the minute of tries, then (only an active queue keeps trying) 5 s, 10 s, 30 s, 1 min.
     */
    internal fun pingDelay(
        connected: Boolean,
        foreground: Boolean,
        generating: Boolean,
        searching: Boolean,
        failCount: Int,
        jobsWaiting: Boolean,
    ): Long =
        when {
            connected && generating -> if (foreground) 1_000L else 2_000L
            connected && foreground -> if (jobsWaiting) 2_000L else 4_000L
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
