package com.example.forgegen

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/* ============================================================================
 * JOB RECORDER (3.6.0, "Generation History")
 * Records how each job the app sends goes, as the app sees it, into job_runs (JobRunEntity) for the statistics, the
 * queue's estimate and Group by Model. Forge times its own model loading exactly, but only prints it in its console,
 * so the app measures from outside, to about a second (its ping interval):
 * - Unload and load: from sending the job until the server answers again. Forge frees the old model and reads the
 *   new one from disk inside the job's request, holding Python's GIL, so even the light /sdapi/v1/progress waits
 *   (SlowServer, 3.5.3). An answer slower than [BUSY_ANSWER_MS] or one that timed out marks the server busy.
 * - Into VRAM: from then until the first sampling step (the weights move to the GPU, the prompt is encoded); the
 *   VRAM is read every ping meanwhile, which gives the job's VRAM curve from the emptied card to the full model.
 * - Sampling and hires fix from the progress's steps; a pass starts again when the step goes back or the batch moves on.
 * - Sending: from the last step to the images on the phone (decoding, saving, sending).
 * How the job found the model (JobStart) comes from what the app knows was loaded last on that server (Unload Model
 * and Restart Forge empty it), checked against what it saw: a job that should have found its model but waited for a
 * load counts as unknown and stays out of the loading times.
 * ============================================================================ */

/** How a job found the server's model. */
enum class JobStart {
    // The server had no model in memory (it started, or the model was unloaded): read from disk and into VRAM.
    COLD,

    // Another checkpoint, VAE or text encoder than the job before: Forge unloads the old one first.
    SWAP,

    // The model was already in VRAM: only the prompt is encoded.
    SAME,

    // Not known (the app had not seen the server's model, or another program changed it).
    UNKNOWN,
}

enum class JobOutcome { DONE, FAILED, INTERRUPTED }

enum class JobFailure { OUT_OF_VRAM, SERVER_ERROR, REFUSED, SERVER_GONE, PHONE_MEMORY, OTHER }

/** What the app knows about the model in a server's memory; saved per server so a new start still knows it. */
data class LoadedModel(
    // KNOWN, EMPTY or UNKNOWN.
    val state: String = UNKNOWN,
    val model: String = "",
    val modules: String = "",
) {
    companion object {
        const val KNOWN = "KNOWN"
        const val EMPTY = "EMPTY"
        const val UNKNOWN = "UNKNOWN"

        val empty = LoadedModel(EMPTY)
        val unknown = LoadedModel(UNKNOWN)

        /** How a job with [model] and [modules] ("" = the server's own) would find [loaded], and the model it replaces. */
        fun predict(
            loaded: LoadedModel,
            model: String,
            modules: String,
        ): Pair<JobStart, String?> =
            when (loaded.state) {
                EMPTY -> JobStart.COLD to null
                KNOWN ->
                    if ((model.isEmpty() || model == loaded.model) && (modules.isEmpty() || modules == loaded.modules)) {
                        JobStart.SAME to null
                    } else {
                        JobStart.SWAP to loaded.model
                    }
                else -> JobStart.UNKNOWN to null
            }

        /** What is loaded after a job with [model] and [modules] ended with [outcome]. */
        fun after(
            loaded: LoadedModel,
            model: String,
            modules: String,
            outcome: JobOutcome,
        ): LoadedModel {
            if (outcome == JobOutcome.FAILED) return unknown
            val known = loaded.state == KNOWN
            val newModel = model.ifEmpty { if (known) loaded.model else "" }
            val newModules = modules.ifEmpty { if (known) loaded.modules else "" }
            return if (newModel.isEmpty()) unknown else LoadedModel(KNOWN, newModel, newModules)
        }
    }
}

/**
 * One job's observations, turned into a [JobRunEntity] when it ends. Pure: the recorder feeds it the moments it sees,
 * and the tests feed it their own.
 */
class JobTimeline(
    val id: String,
    val server: String,
    val queueJobId: String?,
    val startedAt: Long,
    val model: String,
    val modules: String,
    val predicted: JobStart,
    val previousModel: String?,
    val firstHash: Boolean,
    val width: Int,
    val height: Int,
    val images: Int,
    // Batch count: hires fix phases are told apart only for one batch.
    val iterations: Int,
    val steps: Int,
    val sampler: String,
    val scheduler: String,
    val hiresScale: Float?,
    val hiresSteps: Int?,
    vramBefore: Float?,
) {
    private var busySeen = false
    private var busyEnd = 0L
    private var aheadSeen = false

    @Volatile var firstStepAt = 0L
        private set
    private var firstStep = 0
    private var idleSampleAt = 0L
    private var lastStepAt = 0L
    private var hiresStartAt = 0L
    private var hiresFirstStep = 0
    private var baseSampleBeforeHires = 0L
    private var pass = 0
    private var lastStep = -1
    private var lastSteps = -1
    private var lastJobNo = -1
    private var lastSampleAt = 0L
    private var baseDone = 0
    private var baseMs = 0L
    private var hiresDone = 0
    private var hiresMs = 0L

    // VRAM readings: ms from the start and GB.
    private val vram = ArrayList<Pair<Long, Float>>()
    private var vramTotal: Float? = null

    init {
        if (vramBefore != null) vram += 0L to vramBefore
    }

    @Volatile private var lastVramAt = 0L

    val waitingForFirstStep: Boolean get() = firstStepAt == 0L

    /** The VRAM is read at every ping until one reading after the first step: the model is all in by then. */
    val vramWanted: Boolean get() = firstStepAt == 0L || lastVramAt < firstStepAt

    /** A request sent at [sentAt] was answered (or gave up) only at [answeredAt]: the server was too busy. */
    @Synchronized
    fun onBusy(
        sentAt: Long,
        answeredAt: Long,
    ) {
        if (firstStepAt != 0L || answeredAt < startedAt) return
        busySeen = true
        busyEnd = max(busyEnd, answeredAt)
    }

    /** Other jobs ran before this one on the server: its times are not only its own. */
    @Synchronized
    fun onJobsAhead() {
        aheadSeen = true
    }

    @Synchronized
    fun onProgress(
        at: Long,
        step: Int,
        steps: Int,
        jobNo: Int,
    ) {
        if (step < 1) { // before the first step, or between two passes (decoding)
            if (firstStepAt == 0L) idleSampleAt = at
            return
        }
        if (firstStepAt == 0L) {
            firstStepAt = at
            firstStep = step
        }
        val newPass = lastStep >= 0 && (step < lastStep || jobNo != lastJobNo || steps != lastSteps)
        if (newPass) {
            pass++
            if (hiresPass() && hiresStartAt == 0L) {
                hiresStartAt = at
                hiresFirstStep = step
                baseSampleBeforeHires = lastSampleAt
            }
        } else if (lastStep >= 0 && step > lastStep) {
            val dt = at - lastSampleAt
            if (hiresPass()) {
                hiresDone += step - lastStep
                hiresMs += dt
            } else {
                baseDone += step - lastStep
                baseMs += dt
            }
        }
        if (step != lastStep || newPass) lastStepAt = at
        lastStep = step
        lastSteps = steps
        lastJobNo = jobNo
        lastSampleAt = at
    }

    // With hires fix every second pass is the hires one (base, hires, base, hires... for each batch).
    private fun hiresPass() = hiresScale != null && pass % 2 == 1

    @Synchronized
    fun onVram(
        at: Long,
        usedGb: Float,
        totalGb: Float,
    ) {
        if (usedGb <= 0f) return
        lastVramAt = at
        vram += (at - startedAt).coerceAtLeast(0L) to usedGb
        if (totalGb > 0f) vramTotal = totalGb
    }

    @Synchronized
    fun finish(
        at: Long,
        outcome: JobOutcome,
        failure: JobFailure? = null,
        failureText: String? = null,
    ): JobRunEntity {
        // A step is seen only at a ping (every second or two): the moments the sampling began, the hires pass began and
        // the last step was made are worked out from the speed, never before what was seen without them.
        val baseRate = if (baseDone > 0 && baseMs > 0) baseDone.toDouble() / baseMs else null
        val hiresRate = if (hiresDone > 0 && hiresMs > 0) hiresDone.toDouble() / hiresMs else null
        val firstAt =
            if (firstStepAt > 0 && baseRate != null) {
                maxOf(firstStepAt - ((firstStep - 1) / baseRate).toLong(), idleSampleAt, busyEnd, startedAt)
            } else {
                firstStepAt
            }
        val hiresAt =
            if (hiresStartAt > 0 && hiresRate != null) {
                maxOf(hiresStartAt - ((hiresFirstStep - 1) / hiresRate).toLong(), baseSampleBeforeHires)
            } else {
                hiresStartAt
            }
        val lastRate = if (hiresPass()) hiresRate else baseRate
        val lastAt =
            if (lastStepAt > 0 && lastRate != null && lastSteps > lastStep) {
                minOf(lastStepAt + ((lastSteps - lastStep) / lastRate).toLong(), at)
            } else {
                lastStepAt
            }
        val firstStepMs = if (firstAt > 0) firstAt - startedAt else null
        val observedLoad = busySeen || (firstStepMs != null && firstStepMs > LOAD_MS)
        val kind =
            when {
                aheadSeen -> JobStart.UNKNOWN
                predicted == JobStart.SAME || predicted == JobStart.UNKNOWN -> if (observedLoad) JobStart.UNKNOWN else JobStart.SAME
                else -> predicted
            }
        val loaded = kind == JobStart.COLD || kind == JobStart.SWAP || (kind == JobStart.UNKNOWN && observedLoad)
        val split = loaded && busySeen && firstAt > 0 && busyEnd in (startedAt + 1)..firstAt
        val sequential = hiresAt > 0 && iterations <= 1
        return JobRunEntity(
            id = id,
            server = server,
            queueJobId = queueJobId,
            startedAt = startedAt,
            model = model,
            modules = modules,
            previousModel = previousModel.takeIf { kind == JobStart.SWAP },
            startKind = kind.name,
            firstHash = firstHash,
            width = width,
            height = height,
            images = images,
            steps = steps,
            sampler = sampler,
            scheduler = scheduler,
            hiresScale = hiresScale,
            hiresSteps = hiresSteps,
            loadMs = if (split) busyEnd - startedAt else null,
            vramMs = if (split) firstAt - busyEnd else null,
            firstStepMs = firstStepMs,
            samplingMs =
                when {
                    firstAt == 0L -> null
                    sequential -> hiresAt - firstAt
                    else -> lastAt - firstAt
                },
            hiresMs = if (sequential) lastAt - hiresAt else null,
            sendMs = if (lastAt > 0) at - lastAt else null,
            totalMs = at - startedAt,
            itPerSec = rate(baseDone, baseMs),
            hiresItPerSec = rate(hiresDone, hiresMs),
            vramBeforeGb = vram.firstOrNull { it.first == 0L }?.second,
            vramPeakGb = vram.maxOfOrNull { it.second },
            vramTotalGb = vramTotal,
            vramCurve = vram.takeIf { it.isNotEmpty() }?.joinToString(",") { (ms, gb) -> "$ms:${(gb * 10).roundToInt() / 10f}" },
            outcome = outcome.name,
            failure = failure?.name,
            failureText = failureText?.take(MAX_FAILURE_TEXT),
        )
    }

    private fun rate(
        steps: Int,
        ms: Long,
    ): Float? = if (steps > 0 && ms >= MIN_RATE_MS) (steps * 1000f / ms * 100).roundToInt() / 100f else null

    companion object {
        // A first step later than this after sending means a model was loaded, even without a slow answer seen.
        const val LOAD_MS = 8_000L

        // An answer slower than this means the server was too busy (a ping on the LAN takes a few ms).
        const val BUSY_ANSWER_MS = 1_500L

        private const val MIN_RATE_MS = 500L
        private const val MAX_FAILURE_TEXT = 500

        /** The VRAM readings of [curve] ("ms:gb,..."), in order. */
        fun curve(curve: String?): List<Pair<Long, Float>> =
            curve
                ?.split(',')
                ?.mapNotNull { pair ->
                    val ms = pair.substringBefore(':').toLongOrNull()
                    val gb = pair.substringAfter(':', "").toFloatOrNull()
                    if (ms != null && gb != null) ms to gb else null
                }.orEmpty()
    }
}

object JobRecorder {
    private const val TAG = "JobRecorder"
    private const val LOADED_KEY = "loaded_model:"

    @Volatile private var current: JobTimeline? = null

    // The server's checkpoints (ForgeNetworkManager's list, set when the app starts): one without a hash yet is loaded
    // for the first time.
    @Volatile var models: StateFlow<List<ApiResource>>? = null

    private val loaded = ConcurrentHashMap<String, LoadedModel>()

    // Checkpoints whose first load (with Forge working out the hash) was already seen while the app ran.
    private val hashedThisRun: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // Goes up with every job recorded, so the statistics read the history again.
    private val _recorded = MutableStateFlow(0)
    val recorded: StateFlow<Int> = _recorded.asStateFlow()

    // The last known model in a server's memory, with the server: the queue's timeline and Group by Model follow it.
    private val _loadedState = MutableStateFlow<Pair<String, LoadedModel>?>(null)
    val loadedState: StateFlow<Pair<String, LoadedModel>?> = _loadedState.asStateFlow()

    val isRecording: Boolean get() = current != null

    /** Whether the VRAM should be read at every ping: from sending a job until a reading after its first step. */
    fun wantsVram(): Boolean = current?.vramWanted == true

    private fun enabled() = ForgeRepository.config.value.generationHistory

    private suspend fun loadedOn(server: String): LoadedModel =
        loaded[server] ?: run {
            val saved =
                try {
                    ForgeRepository.db.appSettingDao().getSetting(LOADED_KEY + server)?.value?.let {
                        ForgeSettingsManager.gson.fromJson(it, LoadedModel::class.java)
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    null
                }
            (saved?.takeIf { it.state in setOf(LoadedModel.KNOWN, LoadedModel.EMPTY) } ?: LoadedModel.unknown).also { loaded[server] = it }
        }

    private suspend fun setLoaded(
        server: String,
        state: LoadedModel,
    ) {
        loaded[server] = state
        _loadedState.value = server to state
        try {
            ForgeRepository.db.appSettingDao().putSetting(AppSettingEntity(LOADED_KEY + server, ForgeSettingsManager.gson.toJson(state)))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "Could not save the loaded model", e)
        }
    }

    /** What the app knows about the model in the current server's memory (Group by Model starts from it). */
    suspend fun loadedNow(): LoadedModel {
        val server = GalleryKey.serverOf(ForgeRepository.config.value.apiUrl)
        return loadedOn(server).also { _loadedState.value = server to it }
    }

    /** The server's model left its memory: Unload Model, Restart Forge or Unload After the Queue. */
    suspend fun modelUnloaded() = setLoaded(GalleryKey.serverOf(ForgeRepository.config.value.apiUrl), LoadedModel.empty)

    /** Starts recording [job], sent now. */
    suspend fun begin(job: QueuedGeneration) {
        current = null
        if (!enabled()) return
        val payload = job.payload
        val server = GalleryKey.serverOf(ForgeRepository.config.value.apiUrl)
        val (model, modules) = JobModel.of(payload)
        val (predicted, previous) = LoadedModel.predict(loadedOn(server), model, modules)
        val firstHash =
            model.isNotEmpty() &&
                model !in hashedThisRun &&
                models
                    ?.value
                    .orEmpty()
                    .firstOrNull { it.title == model }
                    ?.let { it.hash.isNullOrEmpty() } == true
        if (firstHash) hashedThisRun += model
        val memory = ForgeRepository.serverMemory.value
        current =
            JobTimeline(
                id = UUID.randomUUID().toString(),
                server = server,
                queueJobId = job.id,
                startedAt = System.currentTimeMillis(),
                model = model,
                modules = modules,
                predicted = predicted,
                previousModel = previous,
                firstHash = firstHash,
                width = payload.width,
                height = payload.height,
                images = payload.batch_size.coerceAtLeast(1) * payload.n_iter.coerceAtLeast(1),
                iterations = payload.n_iter.coerceAtLeast(1),
                steps = payload.steps,
                sampler = payload.sampler_name,
                scheduler = payload.scheduler,
                hiresScale = payload.hr_scale.takeIf { payload.enable_hr },
                hiresSteps = payload.hr_second_pass_steps.takeIf { payload.enable_hr && it > 0 },
                vramBefore = memory?.takeIf { it.hasVram }?.vramUsed?.toFloat(),
            )
    }

    fun onBusy(
        sentAt: Long,
        answeredAt: Long,
    ) = current?.onBusy(sentAt, answeredAt)

    fun onJobsAhead() = current?.onJobsAhead()

    fun onProgress(
        at: Long,
        state: ProgressStateDto,
    ) = current?.onProgress(at, state.samplingStep, state.samplingSteps, state.jobNo)

    fun onVram(memory: ServerMemory?) {
        if (memory == null || !memory.hasVram) return
        current?.onVram(System.currentTimeMillis(), memory.vramUsed.toFloat(), memory.vramTotal.toFloat())
    }

    /** The job ended: saves its record and what the server has loaded now; returns the record (null when not recording). */
    suspend fun finish(
        outcome: JobOutcome,
        failure: JobFailure? = null,
        failureText: String? = null,
    ): JobRunEntity? {
        val timeline = current ?: return null
        current = null
        val run = timeline.finish(System.currentTimeMillis(), outcome, failure, failureText)
        setLoaded(timeline.server, LoadedModel.after(loadedOn(timeline.server), timeline.model, timeline.modules, outcome))
        try {
            ForgeRepository.db.jobRunDao().insert(run)
            _recorded.update { it + 1 }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Could not save the job's record", e)
        }
        return run
    }

    /** The job was cancelled (removed while running, or the app stopped it): nothing to record. */
    fun abandon() {
        current = null
    }
}
