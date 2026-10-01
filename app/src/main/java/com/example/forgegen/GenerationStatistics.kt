package com.example.forgegen

import java.util.Locale

/* ============================================================================
 * GENERATION STATISTICS (3.6.0)
 * The Statistics screen's Generation tab, worked out from the jobs the app recorded (JobRecorder, job_runs): the GPU
 * time, how long a cold start, a model swap and the same model take to the first step (medians; a checkpoint's first
 * load, when Forge also works out its hash, and jobs of unknown start are left out), each model's times and speed,
 * the swaps made most, the speed by size, the recent jobs and why jobs failed. It also says how many swaps running each
 * model's jobs together would have spared: in each sitting (jobs less than [SITTING_GAP_MS] apart) a model change
 * more than one per further model used could have been left out.
 * ============================================================================ */

data class LoadingStat(
    val kind: JobStart,
    val count: Int,
    val firstStepMs: Long?,
    val loadMs: Long?,
    val vramMs: Long?,
)

data class ModelTimes(
    val name: String,
    val title: String,
    val coldMs: Long?,
    val swapMs: Long?,
    val itPerSec: Float?,
    val images: Int,
    // How much longer the checkpoint's first load took (Forge working out its hash), once.
    val firstHashExtraMs: Long?,
)

data class SwapStat(
    val from: String,
    val to: String,
    val count: Int,
    val medianMs: Long,
    // The same checkpoint with another VAE or text encoder: Forge reloads it all the same.
    val modulesOnly: Boolean,
)

data class SizeSpeed(
    val size: String,
    val itPerSec: Float,
    val hires: Boolean,
)

data class GenerationStats(
    val jobs: Int,
    val failed: Int,
    val images: Int,
    val gpuMs: Long,
    val perImageMs: Long?,
    val since: Long?,
    val loading: List<LoadingStat>,
    // The time model loading added (cold starts and swaps, beyond the same model's start).
    val loadingMs: Long,
    val avoidableSwaps: Int,
    val savableMs: Long?,
    val byModel: List<ModelTimes>,
    val swaps: List<SwapStat>,
    val speedModel: String?,
    val speeds: List<SizeSpeed>,
    val recent: List<JobRunEntity>,
    val failures: List<Pair<JobFailure, Int>>,
)

/** One part of a recorded job, for its details. */
data class JobPhase(
    val kind: Kind,
    val ms: Long,
    val note: String,
) {
    enum class Kind { LOAD, VRAM, LOADING, PROMPT, SAMPLING, HIRES, SEND }

    val name: String
        get() =
            when (kind) {
                Kind.LOAD -> "Unload and load"
                Kind.VRAM -> "Into VRAM"
                Kind.LOADING -> "Loading"
                Kind.PROMPT -> "Prompt"
                Kind.SAMPLING -> "Sampling"
                Kind.HIRES -> "Hires fix"
                Kind.SEND -> "Sending images"
            }
}

object GenerationStatistics {
    const val SITTING_GAP_MS = 30 * 60_000L
    private const val TOP = 5
    private const val TOP_SWAPS = 4
    private const val TOP_SPEEDS = 3
    private const val RECENT = 10

    fun compute(runs: List<JobRunEntity>): GenerationStats {
        val done = runs.filter { it.outcome != JobOutcome.FAILED.name }
        val images = done.sumOf { it.images }
        val start = { run: JobRunEntity -> JobStart.entries.firstOrNull { it.name == run.startKind } ?: JobStart.UNKNOWN }
        val timed = runs.filter { it.firstStepMs != null }
        val sameMedian = median(timed.filter { start(it) == JobStart.SAME }.mapNotNull { it.firstStepMs }) ?: 0L
        val loading =
            listOf(JobStart.COLD, JobStart.SWAP, JobStart.SAME).mapNotNull { kind ->
                val of = timed.filter { start(it) == kind && !it.firstHash }
                if (of.isEmpty()) {
                    null
                } else {
                    LoadingStat(
                        kind = kind,
                        count = of.size,
                        firstStepMs = median(of.mapNotNull { it.firstStepMs }),
                        loadMs = median(of.mapNotNull { it.loadMs }),
                        vramMs = median(of.mapNotNull { it.vramMs }),
                    )
                }
            }
        val loads = timed.filter { start(it) == JobStart.COLD || start(it) == JobStart.SWAP }
        val loadingMs = loads.sumOf { ((it.firstStepMs ?: 0L) - sameMedian).coerceAtLeast(0L) }
        val swapCost = median(timed.filter { start(it) == JobStart.SWAP && !it.firstHash }.mapNotNull { it.firstStepMs })
        val avoidable = avoidableSwaps(runs)
        return GenerationStats(
            jobs = runs.size,
            failed = runs.size - done.size,
            images = images,
            gpuMs = runs.sumOf { it.totalMs },
            perImageMs = if (images > 0) done.sumOf { it.totalMs } / images else null,
            since = runs.minOfOrNull { it.startedAt },
            loading = loading,
            loadingMs = loadingMs,
            avoidableSwaps = avoidable,
            savableMs = swapCost?.let { ((it - sameMedian).coerceAtLeast(0L)) * avoidable }?.takeIf { avoidable > 0 },
            byModel = byModel(runs, start),
            swaps = swaps(timed, start),
            speedModel = speedModel(done),
            speeds = speeds(done),
            recent = runs.sortedByDescending { it.startedAt }.take(RECENT),
            failures =
                runs
                    .filter { it.outcome == JobOutcome.FAILED.name }
                    .groupingBy { JobFailure.entries.firstOrNull { f -> f.name == it.failure } ?: JobFailure.OTHER }
                    .eachCount()
                    .entries
                    .sortedByDescending { it.value }
                    .map { it.key to it.value },
        )
    }

    /** The model's short name ("animagineXL31"), or the server's own model for a job that named none. */
    fun nameOf(title: String): String = if (title.isEmpty()) "The server's model" else ModelSettingsRules.key(title)

    private fun byModel(
        runs: List<JobRunEntity>,
        start: (JobRunEntity) -> JobStart,
    ): List<ModelTimes> =
        runs
            .groupBy { it.model }
            .map { (title, of) ->
                val cold = median(of.filter { start(it) == JobStart.COLD && !it.firstHash }.mapNotNull { it.firstStepMs })
                val swap = median(of.filter { start(it) == JobStart.SWAP && !it.firstHash }.mapNotNull { it.firstStepMs })
                val firstLoad = of.filter { it.firstHash }.mapNotNull { it.firstStepMs }.maxOrNull()
                val usual = cold ?: swap
                ModelTimes(
                    name = nameOf(title),
                    title = title,
                    coldMs = cold,
                    swapMs = swap,
                    itPerSec = medianFloat(of.mapNotNull { it.itPerSec }),
                    images = of.filter { it.outcome != JobOutcome.FAILED.name }.sumOf { it.images },
                    firstHashExtraMs = if (firstLoad != null && usual != null) (firstLoad - usual).takeIf { it > 0 } else null,
                )
            }.sortedByDescending { it.images }
            .take(TOP)

    private fun swaps(
        timed: List<JobRunEntity>,
        start: (JobRunEntity) -> JobStart,
    ): List<SwapStat> =
        timed
            .filter { start(it) == JobStart.SWAP && !it.firstHash && it.previousModel != null }
            .groupBy { it.previousModel.orEmpty() to it.model }
            .map { (key, of) ->
                SwapStat(
                    from = nameOf(key.first),
                    to = nameOf(key.second),
                    count = of.size,
                    medianMs = median(of.mapNotNull { it.firstStepMs }) ?: 0L,
                    modulesOnly = key.first == key.second,
                )
            }.sortedWith(compareByDescending<SwapStat> { it.count }.thenByDescending { it.medianMs })
            .take(TOP_SWAPS)

    /** The model the speeds by size are shown for: the one with the most images. */
    private fun speedModel(done: List<JobRunEntity>): String? =
        done
            .groupBy { it.model }
            .maxByOrNull { (_, of) -> of.sumOf { it.images } }
            ?.key
            ?.let { nameOf(it) }

    private fun speeds(done: List<JobRunEntity>): List<SizeSpeed> {
        val model = done.groupBy { it.model }.maxByOrNull { (_, of) -> of.sumOf { it.images } }?.value ?: return emptyList()
        val base =
            model
                .filter { it.itPerSec != null }
                .groupBy { "${it.width}×${it.height}" }
                .map { (size, of) -> Triple(SizeSpeed(size, medianFloat(of.mapNotNull { it.itPerSec })!!, false), of.size, false) }
        val hires =
            model
                .filter { it.hiresItPerSec != null && it.hiresScale != null }
                .groupBy { "${(it.width * it.hiresScale!!).toInt()}×${(it.height * it.hiresScale).toInt()}" }
                .map { (size, of) -> Triple(SizeSpeed(size, medianFloat(of.mapNotNull { it.hiresItPerSec })!!, true), of.size, true) }
        return (base + hires)
            .sortedByDescending { it.second }
            .take(TOP_SPEEDS)
            .map { it.first }
            .sortedByDescending { it.itPerSec }
    }

    /**
     * Swaps that running each model's jobs together would have spared: in each sitting, the swaps made beyond one for
     * every further model (checkpoint with its modules) it used.
     */
    fun avoidableSwaps(runs: List<JobRunEntity>): Int {
        var avoidable = 0
        var sitting = ArrayList<JobRunEntity>()
        var lastEnd = Long.MIN_VALUE

        fun close() {
            if (sitting.isEmpty()) return
            val models = sitting.map { it.model to it.modules }.distinct().size
            val swaps = sitting.count { it.startKind == JobStart.SWAP.name }
            // A sitting that began by swapping out a model it never used needed that swap.
            val first = sitting.first()
            val opening = first.startKind == JobStart.SWAP.name && sitting.none { it.model == first.previousModel }
            avoidable += (swaps - (models - 1) - (if (opening) 1 else 0)).coerceAtLeast(0)
            sitting = ArrayList()
        }
        for (run in runs.sortedBy { it.startedAt }) {
            if (run.startedAt - lastEnd > SITTING_GAP_MS) close()
            sitting += run
            lastEnd = run.startedAt + run.totalMs
        }
        close()
        return avoidable
    }

    fun median(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    private fun medianFloat(values: List<Float>): Float? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    /** The parts of [run] as the app saw them, in order; a part it did not see is left out. */
    fun phases(run: JobRunEntity): List<JobPhase> {
        val list = ArrayList<JobPhase>()
        val load = run.loadMs
        val vram = run.vramMs
        val first = run.firstStepMs
        if (load != null && vram != null) {
            val from = run.previousModel?.let { "Forge freed ${nameOf(it)} and read ${nameOf(run.model)} from disk" }
            list += JobPhase(JobPhase.Kind.LOAD, load, from ?: "Read from disk while the server could not answer")
            val curve = JobTimeline.curve(run.vramCurve)
            val before = curve.lastOrNull { it.first <= load }?.second
            val after = curve.firstOrNull { it.first >= load + vram }?.second ?: curve.lastOrNull()?.second
            val climb = if (before != null && after != null && after > before) "VRAM ${gb(before)} → ${gb(after)} GB, " else ""
            list += JobPhase(JobPhase.Kind.VRAM, vram, "${climb}until the first step")
        } else if (first != null) {
            list +=
                if (run.startKind == JobStart.SAME.name) {
                    JobPhase(JobPhase.Kind.PROMPT, first, "The model was in VRAM: only the prompt was read")
                } else {
                    JobPhase(JobPhase.Kind.LOADING, first, "Loading the model until the first step")
                }
        }
        run.samplingMs?.let { ms ->
            val passes = if (run.images > 1) "${run.steps} steps, ${run.images} images" else "${run.steps} steps"
            list += JobPhase(JobPhase.Kind.SAMPLING, ms, listOfNotNull(passes, run.itPerSec?.let { "$it it/s" }).joinToString(" · "))
        }
        run.hiresMs?.let { ms ->
            val scale = run.hiresScale?.let { "×${GalleryInsights.number(it)} to ${(run.width * it).toInt()}×${(run.height * it).toInt()}" }
            list += JobPhase(JobPhase.Kind.HIRES, ms, listOfNotNull(scale, run.hiresItPerSec?.let { "$it it/s" }).joinToString(" · "))
        }
        run.sendMs?.let { list += JobPhase(JobPhase.Kind.SEND, it, "Decoded, saved and sent to the phone") }
        return list
    }

    private fun gb(value: Float) = String.format(Locale.US, "%.1f", value)

    /** "0.7 s", "24.6 s", "1 min 15 s", "6 h 12 m". */
    fun duration(ms: Long): String {
        val seconds = ms / 1000.0
        return when {
            seconds < 60 -> String.format(Locale.US, "%.1f s", seconds)
            seconds < 3600 -> "${(seconds / 60).toInt()} min ${(seconds % 60).toInt()} s"
            else -> "${(seconds / 3600).toInt()} h ${((seconds % 3600) / 60).toInt()} m"
        }
    }
}
