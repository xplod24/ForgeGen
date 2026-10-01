package com.example.forgegen

/* ============================================================================
 * MODEL CHANGES IN THE QUEUE (3.6.0)
 * Where the queue makes Forge change its model, and what that costs.
 * - ModelChangeCosts: from the job history (JobRecorder), how much longer than a start on the same model a job takes
 *   when Forge swaps the model (the median for that pair of models, else for the model swapped in, else for every
 *   swap) or loads it into an empty server (a cold start). A checkpoint's first load, when Forge also works out its
 *   hash, is left out.
 * - QueueGrouping.changes: walks the jobs from the model in the server's memory (LoadedModel) and finds each change.
 * - QueueGrouping.plan: each model's jobs together, the models in the order they first come, the loaded one first.
 *   The job being sent (or about to be sent again) stays first and jobs set aside stay where they are. The app never
 *   regroups by itself: the queue suggests it when at least [QueueGrouping.MIN_SPARED] changes can be spared.
 * ============================================================================ */

/** The model a job needs: its checkpoint and modules ("" = whatever the server has). */
data class JobModel(
    val model: String,
    val modules: String,
) {
    companion object {
        fun of(payload: Txt2ImgPayloadDto) =
            JobModel(
                payload.override_settings.sdModelCheckpoint.orEmpty(),
                payload.override_settings.forgeAdditionalModules
                    .orEmpty()
                    .joinToString(", "),
            )
    }
}

/** A model change before a job: a swap from [from], or a cold start; [ms] is null while its cost is not known. */
data class ModelChange(
    val from: String?,
    val to: JobModel,
    val cold: Boolean,
    val ms: Long?,
)

class ModelChangeCosts(
    // The usual first step on the model already in memory (the prompt alone).
    val sameMs: Long,
    private val pairs: Map<Pair<String, String>, Long>,
    private val into: Map<String, Long>,
    private val anySwap: Long?,
    private val coldInto: Map<String, Long>,
    private val anyCold: Long?,
) {
    /** How much longer a job on [to] takes after a job on [from], or null with no swap recorded. */
    fun swapMs(
        from: String?,
        to: String,
    ): Long? = from?.let { pairs[it to to] } ?: into[to] ?: anySwap

    /** How much longer a job on [to] takes on a server with no model in memory, or null with no cold start recorded. */
    fun coldMs(to: String): Long? = coldInto[to] ?: anyCold

    /** The part of [run]'s time that went to loading its model: the queue's speed is learned without it. */
    fun loadingMs(run: JobRunEntity): Long {
        val first = run.firstStepMs ?: return 0L
        val loaded =
            run.startKind == JobStart.COLD.name ||
                run.startKind == JobStart.SWAP.name ||
                run.loadMs != null ||
                first > JobTimeline.LOAD_MS
        return if (loaded) (first - sameMs).coerceAtLeast(0L) else 0L
    }

    companion object {
        // The newest jobs only: a new disk or card changes the times.
        const val HISTORY = 1000

        val none = ModelChangeCosts(0L, emptyMap(), emptyMap(), null, emptyMap(), null)

        fun of(runs: List<JobStartTime>): ModelChangeCosts {
            val usable = runs.filter { !it.firstHash }
            val sameMs = GenerationStatistics.median(usable.filter { it.startKind == JobStart.SAME.name }.map { it.firstStepMs }) ?: 0L

            fun extra(of: List<JobStartTime>) =
                GenerationStatistics.median(of.map { it.firstStepMs })?.let { (it - sameMs).coerceAtLeast(0L) }
            val swaps = usable.filter { it.startKind == JobStart.SWAP.name }
            val colds = usable.filter { it.startKind == JobStart.COLD.name }
            return ModelChangeCosts(
                sameMs = sameMs,
                pairs =
                    swaps
                        .filter { it.previousModel != null }
                        .groupBy { it.previousModel.orEmpty() to it.model }
                        .mapValues { extra(it.value)!! },
                into = swaps.groupBy { it.model }.mapValues { extra(it.value)!! },
                anySwap = extra(swaps),
                coldInto = colds.groupBy { it.model }.mapValues { extra(it.value)!! },
                anyCold = extra(colds),
            )
        }
    }
}

object QueueGrouping {
    const val MIN_SPARED = 2

    /**
     * The order Group by Model gives: the ids of the whole queue ([order]), with the model changes before ([before]) and
     * after ([after]), and how much sooner the queue ends ([savedMs], null while a change's cost is not known).
     */
    data class Plan(
        val order: List<String>,
        val before: Int,
        val after: Int,
        val savedMs: Long?,
    ) {
        val spared: Int get() = before - after
    }

    /** For each of [jobs], in order, the model change before it (null for none), starting from [loaded]. */
    fun changes(
        loaded: LoadedModel,
        jobs: List<QueuedGeneration>,
        costs: ModelChangeCosts,
    ): List<ModelChange?> {
        var state = loaded
        return jobs.map { job ->
            val needs = JobModel.of(job.payload)
            val (kind, previous) = LoadedModel.predict(state, needs.model, needs.modules)
            state = LoadedModel.after(state, needs.model, needs.modules, JobOutcome.DONE)
            when (kind) {
                JobStart.SWAP -> ModelChange(previous, needs, cold = false, ms = costs.swapMs(previous, needs.model))
                JobStart.COLD -> ModelChange(null, needs, cold = true, ms = costs.coldMs(needs.model))
                else -> null
            }
        }
    }

    // The job being sent, or the one whose connection broke and that is sent again first: it stays first.
    private fun pinned(job: QueuedGeneration) = job.status == GenerationStatus.GENERATING || job.status == GenerationStatus.SUSPENDED

    private fun movable(job: QueuedGeneration) = !pinned(job) && job.status != GenerationStatus.FAILED

    /** What the server has once the pinned job (if any) is done: the waiting jobs start from it. */
    private fun startOf(
        queue: List<QueuedGeneration>,
        loaded: LoadedModel,
    ): LoadedModel {
        val first = queue.firstOrNull { pinned(it) } ?: return loaded
        val needs = JobModel.of(first.payload)
        return LoadedModel.after(loaded, needs.model, needs.modules, JobOutcome.DONE)
    }

    /**
     * Each model's waiting jobs of [queue] together, or null when that spares no model change. A job without a checkpoint
     * runs on whatever the server has, so moving it could change its images: then nothing is suggested.
     */
    fun plan(
        queue: List<QueuedGeneration>,
        loaded: LoadedModel,
        costs: ModelChangeCosts,
    ): Plan? {
        val waiting = queue.filter { movable(it) }
        if (waiting.size < 2 || waiting.any { JobModel.of(it.payload).model.isEmpty() }) return null
        val start = startOf(queue, loaded)
        val groups = LinkedHashMap<JobModel, MutableList<QueuedGeneration>>()
        // The model already in memory goes first.
        waiting
            .firstOrNull {
                val needs = JobModel.of(it.payload)
                LoadedModel.predict(start, needs.model, needs.modules).first == JobStart.SAME
            }?.let { groups[JobModel.of(it.payload)] = ArrayList() }
        waiting.forEach { groups.getOrPut(JobModel.of(it.payload)) { ArrayList() } += it }
        val grouped = groups.values.flatten()
        val before = changes(start, waiting, costs).filterNotNull()
        val after = changes(start, grouped, costs).filterNotNull()
        if (after.size >= before.size) return null
        val savedMs =
            if ((before + after).all { it.ms != null }) {
                (before.sumOf { it.ms!! } - after.sumOf { it.ms!! }).coerceAtLeast(0L)
            } else {
                null
            }
        val next = grouped.iterator()
        return Plan(
            order = queue.map { if (movable(it)) next.next().id else it.id },
            before = before.size,
            after = after.size,
            savedMs = savedMs,
        )
    }

    /** [queue] in [order] (ids); the pinned and set-aside jobs keep their places, jobs not in [order] go last. */
    fun reorder(
        queue: List<QueuedGeneration>,
        order: List<String>,
    ): List<QueuedGeneration> {
        val rank = order.withIndex().associate { it.value to it.index }
        val next = queue.filter { movable(it) }.sortedBy { rank[it.id] ?: Int.MAX_VALUE }.iterator()
        return queue.map { if (movable(it)) next.next() else it }
    }

    /** The waiting jobs' ids: "Not Now" hides the suggestion until a job not among them is added. */
    fun waitingIds(queue: List<QueuedGeneration>): Set<String> = queue.filter { movable(it) }.map { it.id }.toSet()
}
