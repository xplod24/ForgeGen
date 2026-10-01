package com.example.forgegen

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** One live preview image of the running job, decoded from the server's base64; a new image is a new object. */
class LivePreview(
    val bytes: ByteArray,
)

/* ============================================================================
 * QUEUE MANAGER
 * Oversees the generation queue, handles Text2Img requests, manages OOM errors,
 * caches session images, and communicates with the Background Service.
 *
 * One worker sends the jobs, strictly one after another in queue order: it waits (without polling) until the
 * first job may start, marks it GENERATING, and removes it when it is done. The GENERATING job cannot be removed
 * or overtaken. The queue is saved by a single writer, so an older state can never overwrite a newer one.
 *
 * Losing the connection never costs a job: nothing is sent while the server is unreachable, and a job whose
 * connection drops stays first in the queue (SUSPENDED). The queue pauses and continues by itself once the server
 * is back and idle, at most MAX_AUTO_RETRIES times per job (without limit in overnight mode); after that the user
 * resumes it.
 *
 * Overnight mode does not stop the queue for a failed job: the job is set aside (FAILED, with its reason) at the end
 * of the queue and skipped, and one summary at the end says how many jobs failed.
 * ============================================================================ */
@SuppressLint("StaticFieldLeak")
object ForgeQueueManager {
    private const val TAG = "ForgeQueueManager"

    // Cache files of a session: generated images, images restored from the gallery, temporary downloads.
    private val SESSION_CACHE_PREFIXES = listOf("gen_", "recovered_", "temp_rec_")

    private lateinit var application: Application
    private val gson = Gson()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    // The id the running job was sent with (3.3.0), for asking the server about it; null while none runs.
    @Volatile var runningTaskId: String? = null
        private set

    /** Jobs the server does before the running one (from its web UI or another app); 0 when it is doing ours. */
    private val _serverJobsAhead = MutableStateFlow(0)
    val serverJobsAhead: StateFlow<Int> = _serverJobsAhead.asStateFlow()

    fun setServerJobsAhead(ahead: Int) {
        _serverJobsAhead.value = ahead
    }

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _currentEta = MutableStateFlow(0.0)
    val currentEta: StateFlow<Double> = _currentEta.asStateFlow()

    private val _statusText = MutableStateFlow("Ready")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    // The server's live preview of the running job, decoded once here (the screen used to decode the base64 text of
    // every answer itself); the same image sent again is not decoded again.
    private val _livePreviewImage = MutableStateFlow<LivePreview?>(null)
    val livePreviewImage: StateFlow<LivePreview?> = _livePreviewImage.asStateFlow()

    @Volatile private var lastPreviewText: String? = null

    /** The main screen shows the live preview now (3.4.0): the ping asks the server for it only then. */
    @Volatile var previewShown = false
        private set

    fun setPreviewShown(shown: Boolean) {
        val shownBefore = previewShown
        previewShown = shown
        if (shown && !shownBefore && _isGenerating.value) ForgeRepository.pingNow() // the preview at once, not a ping later
    }

    private val _generationQueue = MutableStateFlow<List<QueuedGeneration>>(emptyList())
    val generationQueue: StateFlow<List<QueuedGeneration>> = _generationQueue.asStateFlow()

    private val _isQueuePaused = MutableStateFlow(false)
    val isQueuePaused: StateFlow<Boolean> = _isQueuePaused.asStateFlow()

    private val _oomAlert = MutableStateFlow(false)
    val oomAlert: StateFlow<Boolean> = _oomAlert.asStateFlow()

    // Why the queue is paused; statusText can't carry it because the ping loop overwrites it every second.
    private val _queuePauseReason = MutableStateFlow<String?>(null)
    val queuePauseReason: StateFlow<String?> = _queuePauseReason.asStateFlow()

    private fun pauseQueue(reason: String) {
        _queuePauseReason.value = reason
        _isQueuePaused.value = true
    }

    const val CONNECTION_LOST_REASON = "Connection to the server was lost. The queue continues when the server is back."
    const val USER_PAUSED_REASON = "Paused by you. The running job finishes; resume the queue to go on."
    const val IMPORTED_REASON = "Jobs imported from a backup wait here. Resume the queue to run them."

    /** The user pauses the queue (the Quick Settings tile): the running job finishes, the next ones wait. */
    fun pauseByUser() {
        if (_generationQueue.value.none { it.isRunnable() }) return
        pauseQueue(USER_PAUSED_REASON)
        _statusText.value = "Queue paused"
    }

    // The server refuses a prompt against its rules with HTTP 403 (its prompt-checking extension); the app checks no
    // prompt itself.
    private const val HTTP_FORBIDDEN = 403
    const val PROMPT_REFUSED = "The prompt does not comply with the server's rules."

    /** The server's own explanation of a refusal ("detail" of FastAPI's error answer), when it is short text. */
    fun serverDetail(body: String): String? =
        try {
            com.google.gson.JsonParser
                .parseString(body)
                .asJsonObject
                .get("detail")
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it.length <= 300 }
        } catch (e: Exception) {
            null
        }

    /**
     * The server's own error message from Forge's API error answer ({"error": "TypeError", "errors": "..."}), or a
     * FastAPI "detail" text; null when the answer has neither.
     */
    fun serverError(body: String): String? =
        try {
            val answer =
                com.google.gson.JsonParser
                    .parseString(body)
                    .asJsonObject

            fun text(key: String) =
                answer
                    .get(key)
                    ?.takeIf { it.isJsonPrimitive }
                    ?.asString
                    ?.trim()
                    .orEmpty()
            val message = text("errors").ifEmpty { text("detail") }
            listOf(text("error"), message)
                .filter { it.isNotEmpty() }
                .joinToString(": ")
                .take(300)
                .ifEmpty { null }
        } catch (e: Exception) {
            null
        }

    private const val MAX_AUTO_RETRIES = 3
    private const val AUTO_RETRY_DELAY_MS = 5_000L

    // Overnight mode retries without limit, waiting longer after each loss (5 s, 10 s, ... up to a minute).
    private const val MAX_AUTO_RETRY_DELAY_MS = 60_000L

    private fun QueuedGeneration.isRunnable() = status != GenerationStatus.FAILED

    /**
     * The queue is working: a job runs, or jobs wait to be sent and the queue is not stopped (a pause for a lost
     * connection counts, it continues by itself). The service, its wake lock and "Keep Screen On" follow it.
     */
    val isQueueActive: StateFlow<Boolean> =
        combine(_isGenerating, _generationQueue, _isQueuePaused, _queuePauseReason) { generating, queue, paused, reason ->
            generating || (queue.any { it.isRunnable() } && (!paused || reason == CONNECTION_LOST_REASON))
        }.stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Default), SharingStarted.Eagerly, false) // not tied to the main thread

    // Jobs finished and set aside since the queue last ran empty, for the summary at its end.
    private val runSucceeded = AtomicInteger()
    private val runFailed = AtomicInteger()

    // After an outage during a generation, a server that stays idle this long no longer works on our request:
    // its answer is lost with the dropped connection (a phone's socket can hang for the whole read timeout).
    private const val ORPHANED_REQUEST_IDLE_MS = 10_000L

    // How often each job lost its connection, for the automatic retries.
    private val connectionLosses = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Thrown into the txt2img call when the watchdog finds it orphaned. */
    private class OrphanedRequest : CancellationException("The request was lost in an outage")

    /** The connection broke while a job was being sent; unlike other errors the job itself did not fail. */
    private class ConnectionLost(
        cause: Throwable,
    ) : Exception(cause.message, cause)

    private val _totalQueueSize = MutableStateFlow(0)
    val totalQueueSize: StateFlow<Int> = _totalQueueSize.asStateFlow()

    // "Start at" (QueueSchedule): the queue sends nothing before this time (ms since 1970); saved with the queue.
    private val _scheduledStart = MutableStateFlow<Long?>(null)
    val scheduledStart: StateFlow<Long?> = _scheduledStart.asStateFlow()

    /** Jobs wait for the scheduled start: the service keeps the app alive, but without the wake lock. */
    val isWaitingForSchedule: StateFlow<Boolean> =
        combine(_scheduledStart, _isGenerating) { at, generating -> at != null && !generating }
            .stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Default), SharingStarted.Eagerly, false)

    // The server's speed learned from finished jobs (QueueEstimate), saved across restarts.
    private val speedRates = MutableStateFlow<Map<String, Double>>(emptyMap())

    // The job the user interrupted: its short run must not teach the estimate a wrong speed.
    @Volatile private var interruptedJobId: String? = null

    // What a model change costs, from the job history (3.6.0; none while Generation History is off).
    private val changeCosts = MutableStateFlow(ModelChangeCosts.none)

    // The model in the current server's memory as far as the app knows (JobRecorder; unknown while the history is off).
    private val loadedModel: Flow<LoadedModel> =
        combine(JobRecorder.loadedState, ForgeSettingsManager.config, ::loadedOn).distinctUntilChanged()

    private fun loadedOn(
        state: Pair<String, LoadedModel>?,
        config: AppConfig,
    ): LoadedModel =
        if (!config.generationHistory || state == null || state.first != GalleryKey.serverOf(config.apiUrl)) {
            LoadedModel.unknown
        } else {
            state.second
        }

    /** The queue's timeline (QueueEstimate.timeline): when each job should be done and the model changes before them. */
    val queueTimeline: StateFlow<QueueEstimate.Timeline> =
        combine(_generationQueue, _currentEta, speedRates, loadedModel, changeCosts) { queue, eta, rates, loaded, costs ->
            QueueEstimate.timeline(queue, rates, eta, loaded, costs)
        }.stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Default), SharingStarted.Eagerly, QueueEstimate.Timeline())

    /** Seconds the queue still needs (QueueEstimate), or null before the first job has finished. */
    val queueSecondsLeft: StateFlow<Long?> =
        queueTimeline
            .map { it.remaining?.toLong() }
            .stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Default), SharingStarted.Eagerly, null)

    /** When each job of the queue should be done, in seconds from now (QueueEstimate.ends; the queue's timeline). */
    val queueJobEnds: StateFlow<List<Double?>> =
        queueTimeline
            .map { it.ends }
            .stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Default), SharingStarted.Eagerly, emptyList())

    // The waiting jobs when the user answered "Not Now" to Group by Model; saved.
    private val groupingDismissed = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Group by Model (3.6.0), offered while it spares at least [QueueGrouping.MIN_SPARED] model changes and no job was
     * added since "Not Now".
     */
    val groupingSuggestion: StateFlow<QueueGrouping.Plan?> =
        combine(_generationQueue, loadedModel, changeCosts, groupingDismissed) { queue, loaded, costs, dismissed ->
            if (QueueGrouping.waitingIds(queue).all { it in dismissed }) {
                null
            } else {
                QueueGrouping.plan(queue, loaded, costs)?.takeIf { it.spared >= QueueGrouping.MIN_SPARED }
            }
        }.stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Default), SharingStarted.Eagerly, null)

    private val _completedQueueItems = MutableStateFlow(0)
    val completedQueueItems: StateFlow<Int> = _completedQueueItems.asStateFlow()

    // A batch finished with images (the screen vibrates if the user wants it).
    private val _batchFinished = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val batchFinished: SharedFlow<Unit> = _batchFinished.asSharedFlow()

    private val _sessionImages = MutableStateFlow<List<String>>(emptyList())
    val sessionImages: StateFlow<List<String>> = _sessionImages.asStateFlow()

    private val _currentSessionIndex = MutableStateFlow(-1)
    val currentSessionIndex: StateFlow<Int> = _currentSessionIndex.asStateFlow()

    private val _isShowingGridPreview = MutableStateFlow(false)
    val isShowingGridPreview: StateFlow<Boolean> = _isShowingGridPreview.asStateFlow()

    private val _currentBatchStartIndex = MutableStateFlow(0)
    val currentBatchStartIndex: StateFlow<Int> = _currentBatchStartIndex.asStateFlow()

    private val _currentBatchEndIndex = MutableStateFlow(-1)
    val currentBatchEndIndex: StateFlow<Int> = _currentBatchEndIndex.asStateFlow()

    // Save requests; conflated, so a burst of changes is written once, always with the latest queue.
    private val saveRequests = Channel<Unit>(Channel.CONFLATED)

    fun init(app: Application) {
        application = app
    }

    /** Returns once the saved queue is loaded; the writer, the worker and the reconnect watcher run from then on. */
    suspend fun start() {
        loadQueueState()
        loadScheduleAndSpeed()
        startChangeCostsWatcher()
        startQueueWriter()
        startQueueWorker()
        startServiceWatcher()
        startReconnectWatcher()
        startScheduleWatcher()
        cleanupSessionCache()
    }

    private const val SCHEDULE_KEY = "queue_scheduled_start"
    private const val SPEED_KEY = "queue_speed"
    private const val GROUPING_DISMISSED_KEY = "queue_grouping_dismissed"

    private suspend fun loadScheduleAndSpeed() {
        withContext(Dispatchers.IO) {
            val settings = ForgeRepository.db.appSettingDao()
            try {
                settings.getSetting(SPEED_KEY)?.value?.let { json ->
                    speedRates.value = gson.fromJson(json, object : TypeToken<Map<String, Double>>() {}.type)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load the queue speed", e)
            }
            settings.getSetting(GROUPING_DISMISSED_KEY)?.value?.let { ids ->
                groupingDismissed.value = ids.split(',').filter { it.isNotEmpty() }.toSet()
            }
            // A start time that passed while the app was closed starts the queue now.
            val at = settings.getSetting(SCHEDULE_KEY)?.value?.toLongOrNull()
            if (at != null && at > System.currentTimeMillis()) {
                _scheduledStart.value = at
                QueueSchedule.setAlarm(application, at)
            } else if (at != null) {
                settings.putSetting(AppSettingEntity(SCHEDULE_KEY, ""))
            }
        }
    }

    private fun saveSchedule() {
        val at = _scheduledStart.value
        ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
            try {
                ForgeRepository.db.appSettingDao().putSetting(AppSettingEntity(SCHEDULE_KEY, at?.toString() ?: ""))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save the queue schedule", e)
            }
        }
    }

    /** "Start at": nothing is sent before [at]; jobs added meanwhile wait too. */
    fun scheduleStart(at: Long) {
        _scheduledStart.value = at
        saveSchedule()
        QueueSchedule.setAlarm(application, at)
    }

    /** Ends the wait for the scheduled start: the queue continues now. */
    fun startScheduledQueueNow() {
        if (_scheduledStart.value == null) return
        _scheduledStart.value = null
        saveSchedule()
        if (::application.isInitialized) QueueSchedule.cancelAlarm(application)
        ForgeRepository.reconnect() // the server was not asked while the queue waited
    }

    /** The scheduled time has come (the alarm, or the watcher while the phone is awake). */
    fun onScheduledTime() {
        val at = _scheduledStart.value ?: return
        if (System.currentTimeMillis() >= at - 1_000) startScheduledQueueNow()
    }

    /** Starts the queue at the scheduled time while the app is awake; the alarm covers a sleeping phone. */
    private fun startScheduleWatcher() {
        ForgeRepository.repositoryScope.launch(Dispatchers.Default) {
            _scheduledStart.collectLatest { at ->
                if (at == null) return@collectLatest
                delay((at - System.currentTimeMillis()).coerceAtLeast(0))
                onScheduledTime()
            }
        }
    }

    /**
     * What a model change costs (3.6.0), read from the job history when the app starts, after every recorded job and
     * when Generation History is switched on; with it off there are none. The model the server has is read too.
     */
    private fun startChangeCostsWatcher() {
        ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
            combine(JobRecorder.recorded, ForgeRepository.config.map { it.generationHistory }.distinctUntilChanged()) { _, on -> on }
                .collectLatest { on ->
                    changeCosts.value =
                        if (!on) {
                            ModelChangeCosts.none
                        } else {
                            try {
                                JobRecorder.loadedNow()
                                ModelChangeCosts.of(ForgeRepository.db.jobRunDao().getStartTimes(ModelChangeCosts.HISTORY))
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                Log.e(TAG, "Failed to read the model change times", e)
                                ModelChangeCosts.none
                            }
                        }
                }
        }
    }

    /**
     * Group by Model (3.6.0): each model's waiting jobs together. Returns the order before, for "Undo"; null when it
     * spares no model change.
     */
    fun groupByModel(): List<String>? {
        var before: List<String>? = null
        val loaded = loadedOn(JobRecorder.loadedState.value, ForgeSettingsManager.config.value)
        _generationQueue.update { q ->
            val plan = QueueGrouping.plan(q, loaded, changeCosts.value) ?: return@update q
            before = q.map { it.id }
            QueueGrouping.reorder(q, plan.order)
        }
        if (before != null) saveQueueState()
        return before
    }

    /** "Undo" for Group by Model: the jobs back in [order]; the suggestion stays hidden until a job is added. */
    fun restoreQueueOrder(order: List<String>) {
        _generationQueue.update { q -> QueueGrouping.reorder(q, order) }
        saveQueueState()
        dismissGrouping()
    }

    /** "Not Now": no Group by Model until a job is added. */
    fun dismissGrouping() {
        val ids = QueueGrouping.waitingIds(_generationQueue.value)
        groupingDismissed.value = ids
        ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
            try {
                ForgeRepository.db.appSettingDao().putSetting(AppSettingEntity(GROUPING_DISMISSED_KEY, ids.joinToString(",")))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save the dismissed grouping", e)
            }
        }
    }

    /** Teaches the estimate how long [job] took, unless it was interrupted. */
    private fun learnSpeed(
        job: QueuedGeneration,
        seconds: Double,
    ) {
        if (interruptedJobId == job.id) return
        val rates = QueueEstimate.learn(speedRates.value, job.payload, seconds)
        speedRates.value = rates
        ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
            try {
                ForgeRepository.db.appSettingDao().putSetting(AppSettingEntity(SPEED_KEY, gson.toJson(rates)))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save the queue speed", e)
            }
        }
    }

    fun updateExternalProgress(
        progress: Float,
        eta: Double,
        image: String?,
    ) {
        _progress.value = progress
        _currentEta.value = eta
        if (image != null) setLivePreviewImage(image)
    }

    // The checkpoint of the last job the server finished: a job with another one makes Forge load it first (3.5.3).
    @Volatile private var lastJobModel: String? = null

    /** What the queue says while the server is too busy to answer a ping (SlowServer, 3.5.3). */
    fun slowServerText(): String {
        val job = _generationQueue.value.firstOrNull { it.status == GenerationStatus.GENERATING }
        return SlowServer.statusText(_isGenerating.value, job?.payload?.override_settings?.sdModelCheckpoint, lastJobModel)
    }

    fun updateStatusText(text: String) {
        _statusText.value = text
    }

    /** The server's live preview as base64 text (null clears it). */
    fun setLivePreviewImage(image: String?) {
        if (image == null) {
            lastPreviewText = null
            _livePreviewImage.value = null
            return
        }
        if (image == lastPreviewText) return
        val bytes =
            try {
                java.util.Base64.getMimeDecoder().decode(image.substringAfter("base64,"))
            } catch (e: IllegalArgumentException) {
                return
            }
        lastPreviewText = image
        _livePreviewImage.value = LivePreview(bytes)
    }

    private suspend fun loadQueueState() {
        withContext(Dispatchers.IO) {
            val json = ForgeRepository.db.appSettingDao().getSetting("saved_queue")?.value
            if (!json.isNullOrEmpty()) {
                try {
                    val type = object : TypeToken<List<QueuedGeneration>>() {}.type
                    // A job that was running when the app was closed starts again from the beginning; failed ones stay failed.
                    val saved =
                        gson.fromJson<List<QueuedGeneration>>(json, type).map {
                            if (it.isRunnable()) it.copy(status = GenerationStatus.QUEUED) else it
                        }
                    if (saved.isNotEmpty()) {
                        // Jobs added while the saved queue was being read are kept after it (they used to be lost).
                        _generationQueue.update { current -> saved + current.filter { job -> saved.none { it.id == job.id } } }
                        _totalQueueSize.value = _generationQueue.value.count { it.isRunnable() }
                        _completedQueueItems.value = 0
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load saved queue", e)
                }
            }
        }
    }

    private fun saveQueueState() {
        saveRequests.trySend(Unit)
    }

    /**
     * The only writer of the saved queue. Each save used to be its own coroutine reading the queue at a random
     * moment, so an older queue could be written after a newer one and come back after a restart.
     */
    private fun startQueueWriter() {
        ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
            for (request in saveRequests) {
                try {
                    ForgeRepository.db.appSettingDao().putSetting(AppSettingEntity("saved_queue", gson.toJson(_generationQueue.value)))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save queue", e)
                }
            }
        }
    }

    /**
     * The first job that is not set aside, as soon as it may start: the queue not paused, the server reachable and
     * not busy. Jobs used to be sent while the server was unreachable, each failing at once.
     */
    private suspend fun nextJob(): QueuedGeneration =
        combine(
            _generationQueue,
            _isQueuePaused,
            ForgeRepository.isServerBusy,
            ForgeRepository.isConnected,
            _scheduledStart,
        ) { queue, paused, busy, connected, scheduled ->
            queue.firstOrNull { it.isRunnable() }?.takeIf { !paused && !busy && connected && scheduled == null }
        }.filterNotNull().first()

    /**
     * GenerationService runs whenever the queue is active, not only once a job starts: a queue waiting for the server
     * (unreachable since the app started) or for its "Start at" had no service before its first job, so in the
     * background the phone could freeze or end the app and the queue did not start (2.3.0-2). Each job still starts
     * the service too (executeGeneration), in case it was not allowed to start here.
     */
    private fun startServiceWatcher() {
        ForgeRepository.repositoryScope.launch {
            isQueueActive.collect { active -> if (active) startGenerationService() }
        }
    }

    private fun startGenerationService() {
        val intent = Intent(application, GenerationService::class.java).setAction(GenerationService.ACTION_START_GENERATION)
        try {
            application.startForegroundService(intent)
        } catch (e: Exception) {
            // E.g. not allowed from the background (Android 12+); the next job tries again.
            Log.e(TAG, "Failed to start foreground service", e)
        }
    }

    /** Continues a queue paused by a lost connection once the server is back and idle (with a short delay). */
    private fun startReconnectWatcher() {
        ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
            combine(_queuePauseReason, ForgeRepository.isConnected, ForgeRepository.isServerBusy) { reason, connected, busy ->
                reason == CONNECTION_LOST_REASON && connected && !busy
            }.distinctUntilChanged().collectLatest { serverBack ->
                if (!serverBack) return@collectLatest
                // Overnight mode retries without limit, so it waits longer after each loss of the same job.
                val losses =
                    if (ForgeRepository.config.value.overnightMode) {
                        _generationQueue.value.firstOrNull { it.isRunnable() }?.let { connectionLosses[it.id] } ?: 1
                    } else {
                        1
                    }
                delay((AUTO_RETRY_DELAY_MS * losses).coerceAtMost(MAX_AUTO_RETRY_DELAY_MS))
                if (_queuePauseReason.value == CONNECTION_LOST_REASON) {
                    ForgeNotifications.cancel(ForgeNotifications.ID_QUEUE_PAUSED)
                    _queuePauseReason.value = null
                    _isQueuePaused.value = false
                    _statusText.value = "Connection restored, continuing the queue"
                }
            }
        }
    }

    /**
     * Marks [job] GENERATING and puts it first, if it is still the first job not set aside (the user may have removed
     * or moved it). The running job is always first, also when failed jobs were moved above it.
     */
    private fun claim(job: QueuedGeneration): QueuedGeneration? {
        // The conditions again, from the current values (3.4.0): nextJob's combine may see a new queue before the pause
        // set just before it (Undo restores the pause, then the jobs) and let a paused queue send one job.
        val mayStart =
            !_isQueuePaused.value &&
                _scheduledStart.value == null &&
                ForgeRepository.isConnected.value &&
                !ForgeRepository.isServerBusy.value
        if (!mayStart) return null
        var claimed: QueuedGeneration? = null
        _generationQueue.update { queue ->
            val first = queue.firstOrNull { it.isRunnable() }
            claimed = first?.takeIf { it.id == job.id }?.copy(status = GenerationStatus.GENERATING)
            claimed?.let { running -> listOf(running) + queue.filter { it.id != running.id } } ?: queue
        }
        return claimed
    }

    /**
     * Sends the jobs one after another. It used to poll every 500 ms for as long as the app lived, and
     * queueGeneration() could also start a job itself, guarded only by a flag.
     */
    private fun startQueueWorker() {
        ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
            while (isActive) {
                val job = claim(nextJob()) ?: continue
                _isGenerating.value = true
                ForgeRepository.pingNow() // progress from the first second, not after the idle interval
                saveQueueState()
                try {
                    executeGeneration(job)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // executeGeneration handles its own errors; anything else must not end the queue for good.
                    Log.e(TAG, "Unexpected error while running a job", e)
                    _isGenerating.value = false
                    pauseQueue("Unexpected error: ${e.localizedMessage}")
                }
            }
        }
    }

    private fun applyWildcards(
        prompt: String,
        allWildcards: List<WildcardEntity>,
    ): String {
        var result = prompt
        val regex = Regex("__([a-zA-Z0-9_\\-]+)__")
        var match = regex.find(result)

        while (match != null) {
            val wildcardName = match.groupValues[1]
            val entity = allWildcards.find { it.name == wildcardName }
            var replacement = match.value
            if (entity != null) {
                val options =
                    entity.content
                        .split("\n", ",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                if (options.isNotEmpty()) {
                    replacement = options.random()
                }
            }
            result = result.replaceRange(match.range, replacement)
            match = regex.find(result, match.range.first + replacement.length)
        }
        return result
    }

    // Jobs are added one at a time, in the order they were asked for.
    private val queueingDispatcher = Dispatchers.IO.limitedParallelism(1)

    fun queueGeneration() {
        // Read when the button is pressed: the prompt may already be edited again when the job is built.
        val state = ForgeRepository.appState.value
        val currentModel = ForgeRepository.selectedModel.value.ifEmpty { null }
        val wildcards = ForgePromptManager.wildcards.value
        ForgeRepository.repositoryScope.launch(queueingDispatcher) {
            val finalPositive = applyWildcards(state.positivePrompt, wildcards)
            val finalNegative = applyWildcards(state.negativePrompt, wildcards)

            val built =
                Txt2ImgPayloadDto(
                    prompt = finalPositive,
                    negative_prompt = finalNegative,
                    steps = state.steps,
                    cfg_scale = state.cfgScale,
                    width = state.width,
                    height = state.height,
                    n_iter = state.batchCount,
                    batch_size = state.batchSize,
                    seed = state.seed,
                    sampler_name = state.sampler,
                    scheduler = state.scheduler,
                    override_settings =
                        OverrideSettingsDto(
                            clipSkip = state.clipSkip,
                            sdModelCheckpoint = currentModel,
                        ),
                    enable_hr = state.hiresFix,
                    hr_scale = state.hiresScale,
                    hr_upscaler = state.upscaler,
                    denoising_strength = state.denoising,
                    save_images = state.saveImages,
                    send_images = true,
                    // The server's styles, only while they are turned on in the settings (3.1.0).
                    styles = PromptStyles.forJob(ForgeRepository.config.value.serverStyles, state.styles),
                )
            // The model's type and modules as the user set them (3.0.0); "Auto" changes nothing.
            val payload = ForgeModelManager.withModelSettings(built, currentModel)

            val item =
                QueuedGeneration(
                    id = UUID.randomUUID().toString(),
                    positivePrompt = finalPositive,
                    payload = payload,
                    status = GenerationStatus.QUEUED,
                )

            _generationQueue.update { it + item }
            saveQueueState()

            // The worker picks the job up by itself as soon as it may start.
            val qSize = _generationQueue.value.count { it.isRunnable() }
            if (qSize == 1) {
                _totalQueueSize.value = 1
                _completedQueueItems.value = 0
            } else {
                _totalQueueSize.update { it + 1 }
            }

            ForgeSettingsManager.saveToPromptHistory(state.positivePrompt, state.negativePrompt)
            ForgeRepository.reconnect() // a job waits for the server: look for it again if it was given up

            if (ForgeRepository.isServerBusy.value && qSize > 1) {
                ForgeRepository.showToast("External generation active. Added to queue.")
            } else if (qSize > 1) {
                ForgeRepository.showToast("Added to queue.")
            }
        }
    }

    /**
     * Adds ready-made jobs (remade from gallery images, 2.4.0) after the ones waiting, in the given order, each with
     * its label for the queue.
     */
    fun queueJobs(jobs: List<Pair<Txt2ImgPayloadDto, String>>) {
        if (jobs.isEmpty()) return
        val currentModel = ForgeRepository.selectedModel.value.ifEmpty { null }
        ForgeRepository.repositoryScope.launch(queueingDispatcher) {
            val items =
                jobs.map { (payload, label) ->
                    QueuedGeneration(
                        id = UUID.randomUUID().toString(),
                        positivePrompt = payload.prompt,
                        // The settings of the model each image was made with (3.0.0).
                        payload = ForgeModelManager.withModelSettings(payload, currentModel),
                        status = GenerationStatus.QUEUED,
                        label = label,
                    )
                }
            _generationQueue.update { it + items }
            saveQueueState()
            val qSize = _generationQueue.value.count { it.isRunnable() }
            if (qSize == items.size) {
                _totalQueueSize.value = qSize
                _completedQueueItems.value = 0
            } else {
                _totalQueueSize.update { it + items.size }
            }
            ForgeRepository.reconnect() // the jobs wait for the server: look for it again if it was given up
        }
    }

    private suspend fun executeGeneration(job: QueuedGeneration) {
        val config = ForgeRepository.config.value
        var succeeded = false
        var errorReason: String? = null // set when a failure paused the queue
        var setAsideReason: String? = null // set when overnight mode sets the failed job aside instead
        var isOom = false
        // With "Save to phone: All new images" the gallery sync saves the server's copy (its own name and
        // folder), so saving here as well would put every image on the phone twice.
        val shouldSaveToDevice =
            ForgeRepository.appState.value.saveToDevice &&
                !(config.autoSaveMode == AUTO_SAVE_ALL && job.payload.save_images)

        _progress.value = 0f
        _currentEta.value = 0.0
        setLivePreviewImage(null)
        _isShowingGridPreview.value = false

        val previewText = job.positivePrompt.take(30).replace("\n", " ")
        val initialBatchInfo = if (job.payload.n_iter > 1) "(Batch 1 of ${job.payload.n_iter}) " else ""
        _statusText.value = "Preparing $initialBatchInfo\"$previewText...\""

        startGenerationService()
        runningTaskId = ServerTasks.idFor(job.id)

        var connectionLost = false
        val startedAt = System.currentTimeMillis()
        // How the job went, for its record in the generation history (3.6.0; JobRecorder, only when switched on).
        var record: Triple<JobOutcome, JobFailure?, String?>? = null
        // Seconds the job took, learned once its record says how much of it went to loading the model.
        var tookSeconds: Double? = null
        JobRecorder.begin(job)
        try {
            val answer = requestWithWatchdog(job, shouldSaveToDevice)
            if (answer is Answer.Images) {
                if (answer.files.isEmpty()) record = Triple(JobOutcome.FAILED, JobFailure.OTHER, "The server sent no images.")
                if (answer.files.isNotEmpty()) {
                    addSessionBatch(answer.files.map { it.absolutePath })
                    _statusText.value = "Generation Complete"
                    setLivePreviewImage(null)

                    if (config.showGridAfterGeneration && answer.files.size > 1) {
                        _isShowingGridPreview.value = true
                    }

                    succeeded = true
                    record = Triple(if (interruptedJobId == job.id) JobOutcome.INTERRUPTED else JobOutcome.DONE, null, null)
                    lastJobModel = job.payload.override_settings.sdModelCheckpoint
                    _batchFinished.tryEmit(Unit)
                    tookSeconds = (System.currentTimeMillis() - startedAt) / 1000.0
                }
            } else if (answer is Answer.Failed) {
                val errorBody = answer.body
                // Forge answers most failures (bad sampler, missing model, ...) with HTTP 500, so the status code
                // alone must not raise the out-of-memory alarm.
                if (answer.code == HTTP_FORBIDDEN) {
                    // The server's rules refused the prompt (its prompt-checking extension): only this job fails, set
                    // aside with the reason; the queue goes on.
                    val reason = PROMPT_REFUSED + (serverDetail(errorBody)?.let { " $it" } ?: "")
                    _statusText.value = PROMPT_REFUSED
                    ForgeRepository.showToast(reason)
                    setAsideReason = reason
                    record = Triple(JobOutcome.FAILED, JobFailure.REFUSED, reason)
                } else if (errorBody.contains("OutOfMemoryError", true) ||
                    errorBody.contains("out of memory", true)
                ) {
                    _statusText.value = "SERVER OUT OF MEMORY (OOM)"
                    pauseQueue("Server out of memory (OOM).")
                    _oomAlert.value = true
                    errorReason = "Server out of memory (OOM)."
                    isOom = true
                    record = Triple(JobOutcome.FAILED, JobFailure.OUT_OF_VRAM, serverError(errorBody) ?: errorReason)
                    OomLogs.report(
                        reason = "The server ran out of memory.",
                        details = "${describeForReport(job)}\n\nServer answer (HTTP ${answer.code}):\n$errorBody",
                        readServerMemory = true,
                    )
                } else {
                    _statusText.value = "Error: HTTP ${answer.code}"
                    // With the server's own explanation, when it gives one (2.4.1).
                    val reason = "The server returned HTTP ${answer.code}." + (serverError(errorBody)?.let { " $it" } ?: "")
                    record = Triple(JobOutcome.FAILED, JobFailure.SERVER_ERROR, reason)
                    if (config.overnightMode) {
                        setAsideReason = reason
                    } else {
                        pauseQueue(reason)
                        errorReason = reason
                    }
                }
            }
        } catch (e: CancellationException) {
            Log.d(TAG, "Generation cancelled")
            JobRecorder.abandon()
            throw e
        } catch (e: ConnectionLost) {
            // The connection broke (refused, reset, timed out): the job stays, also in overnight mode, where every
            // following job used to fail at once and the whole queue was thrown away.
            connectionLost = true
            record = Triple(JobOutcome.FAILED, JobFailure.SERVER_GONE, e.cause?.message)
            val losses = connectionLosses.merge(job.id, 1, Int::plus) ?: 1
            if (losses <= MAX_AUTO_RETRIES || ForgeRepository.config.value.overnightMode) {
                pauseQueue(CONNECTION_LOST_REASON)
                _statusText.value = "Connection lost. The job is sent again when the server is back."
            } else {
                pauseQueue("The connection was lost $losses times while sending this job. Resume the queue to try again.")
                _statusText.value = "Connection lost"
            }
            errorReason = _queuePauseReason.value
        } catch (e: OutOfMemoryError) {
            // The images are on the server anyway; the app (and the queue) must survive a batch too big to decode.
            _statusText.value = "The images are too large for the phone's memory"
            val reason = "The images were too large for the phone's memory. They are saved on the server."
            record = Triple(JobOutcome.FAILED, JobFailure.PHONE_MEMORY, reason)
            if (ForgeRepository.config.value.overnightMode) {
                setAsideReason = reason
            } else {
                pauseQueue(reason)
                errorReason = reason
            }
            OomLogs.report(
                reason = "The app ran out of memory while reading the images.",
                details = "${describeForReport(job)}\n\n${e.stackTraceToString()}",
            )
        } catch (e: Exception) {
            _statusText.value = "Failed: ${e.localizedMessage}"
            val reason = "Generation failed: ${e.localizedMessage}"
            record = Triple(JobOutcome.FAILED, JobFailure.OTHER, reason)
            if (ForgeRepository.config.value.overnightMode) {
                setAsideReason = reason
            } else {
                pauseQueue(reason)
                errorReason = reason
            }
        } finally {
            val run = record?.let { (outcome, failure, text) -> withContext(NonCancellable) { JobRecorder.finish(outcome, failure, text) } }
            // The speed without the model change, which the timeline adds on its own (3.6.0).
            tookSeconds?.let { learnSpeed(job, it - (run?.let { r -> changeCosts.value.loadingMs(r) } ?: 0L) / 1000.0) }
            if (connectionLost) {
                keepForRetry(job, errorReason ?: CONNECTION_LOST_REASON)
            } else {
                finishJob(job, succeeded, errorReason, isOom, setAsideReason)
            }
            saveQueueState()
            runningTaskId = null
            _serverJobsAhead.value = 0
            _isGenerating.value = false
            _progress.value = if (connectionLost) 0f else 1f
            _currentEta.value = 0.0
        }
    }

    /** The settings of [job] for an out-of-memory report; the prompts stay out of it. */
    private fun describeForReport(job: QueuedGeneration): String =
        with(job.payload) {
            val hires = if (enable_hr) "x$hr_scale with $hr_upscaler, denoising $denoising_strength" else "off"
            val model = override_settings.sdModelCheckpoint ?: "(current)"
            "Job: ${width}x$height, batch size $batch_size, batch count $n_iter, steps $steps, " +
                "sampler $sampler_name ($scheduler), hires fix $hires, model $model"
        }

    /** What the server answered to a job. */
    private sealed interface Answer {
        data class Images(
            val files: List<File>,
        ) : Answer

        data class Failed(
            val code: Int,
            val body: String,
        ) : Answer
    }

    /** Writing an image to the phone failed; not a network error, so the job is not sent again. */
    private class SaveFailed(
        cause: Throwable,
    ) : Exception(cause.message, cause)

    /**
     * Sends [job], while a watchdog looks for a request orphaned by an outage: the connection was lost, and since
     * it is back the server stays idle, so no answer will come on the old connection. The watchdog stops once the
     * server has answered, so a slow download of the images is never taken for an orphaned request.
     */
    private suspend fun requestWithWatchdog(
        job: QueuedGeneration,
        saveToDevice: Boolean,
    ): Answer =
        coroutineScope {
            val answered = AtomicBoolean(false)
            val call =
                async {
                    try {
                        val api = ForgeRepository.generationApi ?: throw java.io.IOException("Not connected to the server")
                        val response = api.generateImage(job.payload.forServer().copy(force_task_id = runningTaskId))
                        answered.set(true)
                        if (response.isSuccessful) {
                            Answer.Images(response.body()?.use { readImages(it, saveToDevice) }.orEmpty())
                        } else {
                            Answer.Failed(response.code(), response.errorBody()?.string().orEmpty())
                        }
                    } catch (e: com.google.gson.stream.MalformedJsonException) {
                        throw e // a broken answer, not a broken connection
                    } catch (e: java.io.IOException) {
                        throw ConnectionLost(e)
                    }
                }
            val watchdog =
                launch {
                    var outageSeen = false
                    var idleSince = 0L
                    while (!answered.get()) {
                        delay(1000)
                        val connected = ForgeRepository.isConnected.value
                        val busy = ForgeRepository.isServerBusy.value
                        when {
                            answered.get() -> return@launch
                            !connected -> {
                                outageSeen = true
                                idleSince = 0L
                                _statusText.value = "Connection lost, waiting for the server..."
                            }
                            outageSeen && !busy -> {
                                if (idleSince == 0L) idleSince = System.currentTimeMillis()
                                if (System.currentTimeMillis() - idleSince >= ORPHANED_REQUEST_IDLE_MS) {
                                    call.cancel(OrphanedRequest())
                                    return@launch
                                }
                            }
                            else -> idleSince = 0L
                        }
                    }
                }
            try {
                call.await()
            } catch (e: OrphanedRequest) {
                ensureActive() // only the watchdog cancels with OrphanedRequest; a stopped worker stays stopped
                throw ConnectionLost(e)
            } finally {
                watchdog.cancel()
            }
        }

    /**
     * Reads the images from the answer one at a time and writes each to the cache while it arrives (Txt2ImgImages):
     * the whole batch used to be held in memory as base64 text, enough to crash the app with big images, and later
     * each image still was. Other fields of the answer are skipped without being read into memory.
     */
    private fun readImages(
        body: okhttp3.ResponseBody,
        saveToDevice: Boolean,
    ): List<File> {
        val files = mutableListOf<File>()
        body.charStream().use { reader ->
            Txt2ImgImages.read(reader) { index, image -> files += saveGeneratedImage(image, index, saveToDevice) }
        }
        return files
    }

    /** Writes one image to the cache; only failures to write (not to read the answer) are [SaveFailed]. */
    private fun saveGeneratedImage(
        image: java.io.InputStream,
        index: Int,
        saveToDevice: Boolean,
    ): File {
        val file = File(application.cacheDir, "gen_${System.currentTimeMillis()}_$index.png")
        val out =
            try {
                file.outputStream()
            } catch (e: java.io.IOException) {
                throw SaveFailed(e)
            }
        out.use {
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = image.read(buffer)
                if (n < 0) break
                try {
                    it.write(buffer, 0, n)
                } catch (e: java.io.IOException) {
                    throw SaveFailed(e)
                }
            }
        }

        // The first image of the batch is the local fallback "last generated image".
        if (index == 0) {
            try {
                file.copyTo(File(application.cacheDir, "last_generated_image.png"), overwrite = true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cache local last generated image", e)
            }
        }
        if (saveToDevice) {
            try {
                DeviceImages.save(application, "Gen_${System.currentTimeMillis()}_$index.png") { device ->
                    file.inputStream().use { it.copyTo(device) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save generated image directly to device", e)
            }
        }
        return file
    }

    // The session keeps this many images in the cache (they stay on the server); a long queue used to fill the
    // cache with every image it made until the next start.
    private const val MAX_SESSION_IMAGES = 100

    /** Adds a finished batch to the session and shows it; the oldest images beyond the limit are deleted. */
    private fun addSessionBatch(paths: List<String>) {
        val all = _sessionImages.value + paths
        val drop = (all.size - MAX_SESSION_IMAGES).coerceIn(0, all.size - paths.size) // never the new batch
        all.take(drop).forEach { File(it).delete() }
        val kept = all.drop(drop)
        _sessionImages.value = kept
        _currentBatchStartIndex.value = kept.size - paths.size
        _currentBatchEndIndex.value = kept.lastIndex
        _currentSessionIndex.value = kept.lastIndex
    }

    /** A job whose connection broke stays first in the queue, waiting to be sent again. */
    private fun keepForRetry(
        job: QueuedGeneration,
        reason: String,
    ) {
        _generationQueue.update { q -> q.map { if (it.id == job.id) it.copy(status = GenerationStatus.SUSPENDED) else it } }
        notifyGenerationError(reason, isOom = false, queuePaused = true)
    }

    /**
     * Removes a finished job, or sets a failed one aside in overnight mode ([setAsideReason]), and posts at most one
     * notification for it. When no job is left to run, a queue in which jobs were set aside ends with a summary.
     * The service follows [isQueueActive] by itself.
     */
    private fun finishJob(
        job: QueuedGeneration,
        succeeded: Boolean,
        errorReason: String?,
        isOom: Boolean,
        setAsideReason: String?,
    ) {
        connectionLosses.remove(job.id)
        if (setAsideReason != null) {
            // At the end of the queue, so the jobs still to run stay first.
            _generationQueue.update { q ->
                q.filter { it.id != job.id } + job.copy(status = GenerationStatus.FAILED, error = setAsideReason)
            }
            runFailed.incrementAndGet()
        } else {
            _generationQueue.update { q -> q.filter { it.id != job.id } }
            if (succeeded) runSucceeded.incrementAndGet()
        }
        _completedQueueItems.update { it + 1 }

        val queueDone = _generationQueue.value.none { it.isRunnable() }
        val succeededInRun = runSucceeded.get()
        val failedInRun = runFailed.get()
        if (queueDone) {
            _totalQueueSize.value = 0
            _completedQueueItems.value = 0
            runSucceeded.set(0)
            runFailed.set(0)
            // Nothing left to hold back: a paused empty queue would silently swallow the next job.
            _isQueuePaused.value = false
            _queuePauseReason.value = null
        }

        // One notification per job: "queue completed" replaces "batch completed" for the last job, and a
        // failed job only gets the error alert (it used to be reported as a completed queue). A job set aside by
        // overnight mode gets none; the summary at the end counts it (overnight mode used to report failed jobs
        // as a completed queue).
        val notifConfig = ForgeRepository.config.value
        when {
            errorReason != null -> notifyGenerationError(errorReason, isOom, queuePaused = !queueDone)
            queueDone && failedInRun > 0 -> notifyFailedJobs(succeededInRun, failedInRun)
            queueDone && notifConfig.notifOnQueueFinish ->
                launchNotification(job.positivePrompt, isQueueFinished = true)
            succeeded && notifConfig.notifOnBatchFinish ->
                launchNotification(job.positivePrompt, isQueueFinished = false)
        }
    }

    /** The end of a queue in which overnight mode set failed jobs aside; shown like the other errors. */
    private fun notifyFailedJobs(
        succeeded: Int,
        failed: Int,
    ) {
        val builder = ForgeNotifications.builder(ForgeNotifications.CHANNEL_ALERTS) ?: return
        val notification =
            builder
                .setContentTitle("Queue finished with errors")
                .setContentText("$succeeded done, $failed failed. The failed jobs are kept in the queue.")
                .setColor(0xFFFF0000.toInt())
                .setAutoCancel(true)
                .build()
        ForgeNotifications.post(ForgeNotifications.ID_QUEUE_PAUSED, notification)
    }

    /** The debug mode's test notifications: "batch", "queue" or "failed", each as a real one would look. */
    fun debugNotify(kind: String) {
        when (kind) {
            "batch" -> launchNotification("debug test prompt", isQueueFinished = false)
            "queue" -> launchNotification("", isQueueFinished = true)
            "failed" -> notifyFailedJobs(succeeded = 3, failed = 1)
        }
    }

    /** Puts failed jobs ([id], or all of them) back in the queue, after the jobs already waiting. */
    fun retryFailed(id: String? = null) {
        var retried = 0
        _generationQueue.update { q ->
            val (failed, rest) = q.partition { !it.isRunnable() && (id == null || it.id == id) }
            retried = failed.size
            rest + failed.map { it.copy(status = GenerationStatus.QUEUED, error = null) }
        }
        if (retried > 0) _totalQueueSize.update { it + retried }
        saveQueueState()
    }

    /** Removes every failed job from the queue. */
    fun removeFailedJobs() {
        _generationQueue.update { q -> q.filter { it.isRunnable() } }
        saveQueueState()
    }

    private fun launchNotification(
        prompt: String,
        isQueueFinished: Boolean,
    ) {
        val config = ForgeRepository.config.value
        val title = if (isQueueFinished) "Queue Completed" else "Batch Completed"
        // The prompt only when the user wants it ("Hide Prompts in Notifications" off), and never on the lock screen
        // (the public version there has no prompt).
        val showPrompt = !isQueueFinished && !config.hidePromptsInNotifications
        val generic = if (isQueueFinished) "All generation jobs have finished." else "A batch has finished."
        val text = if (showPrompt) "Finished: ${prompt.take(35)}..." else generic
        val builder = ForgeNotifications.builder(ForgeNotifications.CHANNEL_RESULTS) ?: return
        val lockScreen =
            ForgeNotifications
                .builder(ForgeNotifications.CHANNEL_RESULTS)
                ?.setContentTitle(title)
                ?.setContentText(generic)
                ?.build()
        builder
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        if (lockScreen != null) builder.setPublicVersion(lockScreen)
        ForgeNotifications.post(System.currentTimeMillis().toInt(), builder.build())
    }

    /** A failed job needs the user, who may not be looking at the app; one alert, replaced by the next. */
    private fun notifyGenerationError(
        reason: String,
        isOom: Boolean,
        queuePaused: Boolean,
    ) {
        val builder = ForgeNotifications.builder(ForgeNotifications.CHANNEL_ALERTS) ?: return
        val title =
            when {
                isOom -> "Server out of memory"
                queuePaused -> "Queue paused"
                else -> "Generation failed"
            }
        val text =
            when {
                isOom && queuePaused -> "Lower the resolution or batch size, then resume the queue."
                isOom -> "Lower the resolution or batch size and try again."
                reason == CONNECTION_LOST_REASON -> reason // it continues by itself
                queuePaused -> "$reason Open the app to resume the queue."
                else -> reason
            }
        val notification =
            builder
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text)) // the whole server error, when expanded
                .setColor(0xFFFF0000.toInt())
                .setAutoCancel(true)
                .setOnlyAlertOnce(true) // repeated connection losses update the alert without sounding again
                .build()
        ForgeNotifications.post(ForgeNotifications.ID_QUEUE_PAUSED, notification)
    }

    fun updateQueueItem(
        id: String,
        positivePrompt: String,
        negativePrompt: String,
    ) {
        _generationQueue.update { currentQueue ->
            currentQueue.map {
                if (it.id == id) {
                    it.copy(
                        positivePrompt = positivePrompt,
                        payload = it.payload.copy(prompt = positivePrompt, negative_prompt = negativePrompt),
                    )
                } else {
                    it
                }
            }
        }
        saveQueueState()
    }

    /**
     * Jobs taken out of the queue with their places, so "Undo" can put them back; [liftedPause] is the reason of the
     * pause that ended because nothing was left to run, which "Undo" brings back with the jobs.
     */
    class RemovedJobs(
        val jobs: List<IndexedValue<QueuedGeneration>>,
        val liftedPause: String? = null,
    )

    /**
     * The user took out the last jobs that were to run: nothing is left to hold back, so the pause ends, as when the
     * queue runs empty (finishJob). It used to stay, and the next new job waited for "Resume" under the old reason
     * (2.3.0-2). Returns the reason of the pause that ended, or null.
     */
    private fun liftPauseIfNothingToRun(): String? {
        if (!_isQueuePaused.value || _generationQueue.value.any { it.isRunnable() }) return null
        val reason = _queuePauseReason.value
        ForgeNotifications.cancel(ForgeNotifications.ID_QUEUE_PAUSED)
        _isQueuePaused.value = false
        _queuePauseReason.value = null
        return reason
    }

    /** Removes every job but the running one; returns what was removed (for "Undo"), null when nothing was. */
    fun clearQueue(): RemovedJobs? {
        ForgeNotifications.cancel(ForgeNotifications.ID_QUEUE_PAUSED)
        // The running job stays: the server is already working on it and the worker removes it when it is done.
        var removed = emptyList<IndexedValue<QueuedGeneration>>()
        _generationQueue.update { q ->
            removed = q.withIndex().filter { it.value.status != GenerationStatus.GENERATING }
            q.filter { it.status == GenerationStatus.GENERATING }
        }
        _totalQueueSize.value = _generationQueue.value.size
        _completedQueueItems.value = 0
        runSucceeded.set(0)
        runFailed.set(0)
        val liftedPause = liftPauseIfNothingToRun()
        saveQueueState()
        return RemovedJobs(removed, liftedPause).takeIf { removed.isNotEmpty() }
    }

    /** The queue for a backup (3.5.2-1); a running job is saved as waiting, as when the app closes. */
    fun jobsForBackup(): List<QueuedGeneration> =
        _generationQueue.value.map { if (it.status == GenerationStatus.GENERATING) it.copy(status = GenerationStatus.QUEUED) else it }

    /**
     * The jobs of a backup (3.5.2-1) after the ones here; jobs already here are skipped. The queue pauses first, so they
     * wait until the user resumes it instead of starting on the spot. How many were added.
     */
    fun importJobs(jobs: List<QueuedGeneration>): Int {
        val current = _generationQueue.value.map { it.id }.toSet()
        val added =
            jobs
                .filter { it.id !in current }
                .distinctBy { it.id }
                .map { if (it.isRunnable()) it.copy(status = GenerationStatus.QUEUED) else it }
        if (added.isEmpty()) return 0
        val runnable = added.count { it.isRunnable() }
        if (runnable > 0 && !_isQueuePaused.value) pauseQueue(IMPORTED_REASON)
        _generationQueue.update { it + added }
        if (runnable > 0) {
            if (_totalQueueSize.value == 0) _completedQueueItems.value = 0
            _totalQueueSize.update { it + runnable }
        }
        saveQueueState()
        return added.size
    }

    /**
     * Puts removed jobs back at their places (never before the running job); jobs already back are skipped. A pause
     * their removal ended comes back with them.
     */
    fun restoreJobs(removed: RemovedJobs) {
        // The pause first: the worker would send a restored job the moment it is back in a queue that is not paused.
        val current = _generationQueue.value.map { it.id }.toSet()
        val bringsJobsBack = removed.jobs.any { it.value.isRunnable() && it.value.id !in current }
        if (bringsJobsBack && !_isQueuePaused.value) removed.liftedPause?.let { pauseQueue(it) }
        var restored = 0
        _generationQueue.update { q ->
            val list = q.toMutableList()
            for ((index, job) in removed.jobs.sortedBy { it.index }) {
                if (list.any { it.id == job.id }) continue
                val first = if (list.firstOrNull()?.status == GenerationStatus.GENERATING) 1 else 0
                list.add(index.coerceIn(first, list.size), job)
                if (job.isRunnable()) restored++
            }
            list
        }
        if (restored > 0) {
            if (_totalQueueSize.value == 0) _completedQueueItems.value = 0
            _totalQueueSize.update { it + restored }
            ForgeRepository.reconnect()
        }
        saveQueueState()
    }

    /**
     * A copy of the job [id] after the jobs still to run (before the failed ones set aside), with the same settings;
     * with [newSeed] a random seed instead of the job's own.
     */
    fun duplicateJob(
        id: String,
        newSeed: Boolean,
    ) {
        var added = false
        _generationQueue.update { q ->
            val original = q.firstOrNull { it.id == id } ?: return@update q
            val copy =
                original.copy(
                    id = UUID.randomUUID().toString(),
                    status = GenerationStatus.QUEUED,
                    error = null,
                    payload = if (newSeed) original.payload.copy(seed = -1L) else original.payload,
                )
            added = true
            val firstFailed = q.indexOfFirst { !it.isRunnable() }
            if (firstFailed < 0) q + copy else q.take(firstFailed) + copy + q.drop(firstFailed)
        }
        if (!added) return
        if (_generationQueue.value.count { it.isRunnable() } == 1) {
            _totalQueueSize.value = 1
            _completedQueueItems.value = 0
        } else {
            _totalQueueSize.update { it + 1 }
        }
        saveQueueState()
        ForgeRepository.reconnect()
    }

    /** The job being sent to the server; it stays first until it is done. */
    private fun isActiveItem(
        queue: List<QueuedGeneration>,
        index: Int,
    ) = queue.getOrNull(index)?.status == GenerationStatus.GENERATING

    /** Removes the job [id] unless it is running; returns it with its place (for "Undo"), null when nothing was removed. */
    fun removeFromQueue(id: String): RemovedJobs? {
        var removed: IndexedValue<QueuedGeneration>? = null
        _generationQueue.update { currentQueue ->
            // Decided inside the update, so the worker cannot claim the job between the check and the removal.
            val index = currentQueue.indexOfFirst { it.id == id && it.status != GenerationStatus.GENERATING }
            removed = if (index >= 0) IndexedValue(index, currentQueue[index]) else null
            if (index >= 0) currentQueue.filterIndexed { i, _ -> i != index } else currentQueue
        }
        val job = removed
        if (job != null && job.value.isRunnable()) _totalQueueSize.update { maxOf(_completedQueueItems.value, it - 1) }
        val liftedPause = if (job != null) liftPauseIfNothingToRun() else null
        saveQueueState()
        return job?.let { RemovedJobs(listOf(it), liftedPause) }
    }

    /** Moves the job [id] to [toIndex] (dragged in the queue); the running job stays first and cannot be moved. */
    fun moveQueueItem(
        id: String,
        toIndex: Int,
    ) {
        _generationQueue.update { q ->
            val from = q.indexOfFirst { it.id == id }
            if (from < 0 || isActiveItem(q, from)) return@update q
            val first = if (isActiveItem(q, 0)) 1 else 0
            val to = toIndex.coerceIn(first, q.size - 1)
            if (to == from) return@update q
            q.toMutableList().apply { add(to, removeAt(from)) }
        }
        saveQueueState()
    }

    fun moveQueueItemUp(id: String) = moveQueueItem(id, _generationQueue.value.indexOfFirst { it.id == id } - 1)

    fun moveQueueItemDown(id: String) = moveQueueItem(id, _generationQueue.value.indexOfFirst { it.id == id } + 1)

    fun resumeQueue() {
        // A manual resume also gives a job whose connection kept failing its automatic retries back.
        _generationQueue.value.firstOrNull()?.let { connectionLosses.remove(it.id) }
        ForgeNotifications.cancel(ForgeNotifications.ID_QUEUE_PAUSED)
        _isQueuePaused.value = false
        _queuePauseReason.value = null
        _oomAlert.value = false
        _statusText.value = "Queue Resumed"
    }

    fun interruptGeneration() {
        interruptedJobId = _generationQueue.value.firstOrNull { it.status == GenerationStatus.GENERATING }?.id
        ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
            try {
                ForgeRepository.forgeApi?.interruptGeneration()
                _statusText.value = "Interrupting..."
            } catch (e: Exception) {
                Log.e(TAG, "Failed to interrupt", e)
            }
        }
    }

    /** Skips the image being made (3.3.0): the job goes on with its next image and ends with the others. */
    fun skipImage() {
        ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
            val message =
                try {
                    val response = ForgeRepository.forgeApi?.skipImage()
                    when {
                        response == null -> "Not connected to the server"
                        response.isSuccessful -> "Skipping this image"
                        else -> "The server did not skip the image (HTTP ${response.code()})"
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    "The server did not skip the image: ${e.message ?: e.javaClass.simpleName}"
                }
            ForgeRepository.showToast(message)
        }
    }

    /** A new file in the cache for an image restored from the gallery (see [showRecoveredImage]). */
    fun newRecoveredImageFile() = File(application.cacheDir, "recovered_${System.currentTimeMillis()}.png")

    /** Shows [file] (an image restored from the gallery) as the session; the previous session's files are deleted. */
    fun showRecoveredImage(file: File) {
        if (!file.exists() || file.length() == 0L) {
            Log.e(TAG, "Cannot show an empty recovered image.")
            file.delete()
            return
        }
        val previous = _sessionImages.value
        _sessionImages.value = listOf(file.absolutePath)
        _currentSessionIndex.value = 0
        _currentBatchStartIndex.value = 0
        _currentBatchEndIndex.value = 0
        _isShowingGridPreview.value = false
        previous.filter { it != file.absolutePath }.forEach { File(it).delete() }
    }

    /**
     * Deletes the images of the previous run from the cache. The session only lives in memory, so after a restart
     * nothing refers to them any more; generated images ("gen_") used to pile up there forever.
     */
    private fun cleanupSessionCache() {
        ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
            try {
                application.cacheDir.listFiles()?.forEach { file ->
                    val isSessionFile = SESSION_CACHE_PREFIXES.any { file.name.startsWith(it) }
                    if (isSessionFile && file.name.endsWith(".png")) {
                        file.delete()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning up recovered cache", e)
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

    fun downloadSessionImage(localFilePath: String) {
        ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
            try {
                val file = File(localFilePath)
                if (!file.exists()) throw Exception("Local file missing")
                DeviceImages.save(application, "Gen_${System.currentTimeMillis()}.png") { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                ForgeRepository.showToast("Saved to ${DeviceImages.locationName()}")
            } catch (e: Exception) {
                ForgeRepository.showToast("Download Failed: ${e.message}")
            }
        }
    }

    fun shareSessionImage(
        localFilePath: String,
        onIntentReady: (Intent) -> Unit,
    ) {
        ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
            try {
                val file = File(localFilePath)
                if (!file.exists()) throw Exception("Local file missing")
                // A temporary copy for the share sheet; it used to be saved to Pictures/ForgeGen_Shared for good.
                val intent =
                    DeviceImages.shareIntent(application, "ForgeGen_${System.currentTimeMillis()}.png") { out ->
                        file.inputStream().use { it.copyTo(out) }
                    }
                withContext(Dispatchers.Main) { onIntentReady(intent) }
            } catch (e: Exception) {
                ForgeRepository.showToast("Share Failed: ${e.message}")
            }
        }
    }
}
