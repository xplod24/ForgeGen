package com.example.forgegen

import java.util.Locale

/* ============================================================================
 * QUEUE ESTIMATE
 * How long the queue still needs. Every job that finishes teaches the app how fast the server is: seconds per unit
 * of work (megapixels × steps, the hires pass included), kept as a running average for each checkpoint and for all
 * of them together. A job is estimated with the rate of its checkpoint, or the common one for a checkpoint not
 * used yet; before the first job has finished there is no estimate.
 * ============================================================================ */
object QueueEstimate {
    // The newest job counts this much in the running average: quick to follow a change, steady against one outlier.
    private const val NEW_WEIGHT = 0.3

    // The rate of all checkpoints together.
    const val ANY_MODEL = "*"

    /**
     * The work of a job: megapixels × steps for every image, plus the hires pass, which works on the enlarged image
     * with the steps its denoising strength leaves.
     */
    fun workload(payload: Txt2ImgPayloadDto): Double {
        val images = payload.batch_size.coerceAtLeast(1).toDouble() * payload.n_iter.coerceAtLeast(1)
        val megapixels = payload.width.toDouble() * payload.height / 1_000_000
        var perImage = megapixels * payload.steps
        if (payload.enable_hr) {
            perImage += megapixels * payload.hr_scale * payload.hr_scale * payload.steps * payload.denoising_strength.coerceIn(0.1f, 1f)
        }
        return perImage * images
    }

    /** [rates] after a job with [payload] took [seconds]. */
    fun learn(
        rates: Map<String, Double>,
        payload: Txt2ImgPayloadDto,
        seconds: Double,
    ): Map<String, Double> {
        val work = workload(payload)
        if (work <= 0 || seconds <= 0) return rates
        val rate = seconds / work

        fun average(old: Double?) = if (old == null) rate else old * (1 - NEW_WEIGHT) + rate * NEW_WEIGHT
        val model = payload.override_settings.sdModelCheckpoint
        val learned = rates + (ANY_MODEL to average(rates[ANY_MODEL]))
        return if (model.isNullOrEmpty()) learned else learned + (model to average(rates[model]))
    }

    /** Seconds a job with [payload] should take, or null while nothing has been learned. */
    fun seconds(
        rates: Map<String, Double>,
        payload: Txt2ImgPayloadDto,
    ): Double? {
        val rate = payload.override_settings.sdModelCheckpoint?.let { rates[it] } ?: rates[ANY_MODEL] ?: return null
        return rate * workload(payload)
    }

    /**
     * The queue's timeline (3.0.0-1): for each job of [queue], in order, the seconds from now until it should be done,
     * and (3.6.0) the model change expected before it. Failed jobs are skipped (null); from the first job that cannot
     * be estimated on, every end is null. The running job counts by the server's own estimate [runningEta] when it
     * has one. A model change adds its cost from [costs] (Generation History): a swap when a job needs another
     * checkpoint or modules than the job before, a cold start when the server's model was unloaded ([loaded]).
     */
    data class Timeline(
        val ends: List<Double?> = emptyList(),
        val changes: List<ModelChange?> = emptyList(),
        // Seconds the queue still needs, or null while a job cannot be estimated.
        val remaining: Double? = null,
    ) {
        /** Seconds of the expected model changes whose cost is known. */
        val changeSeconds: Double get() = changes.sumOf { (it?.ms ?: 0L) / 1000.0 }
    }

    fun timeline(
        queue: List<QueuedGeneration>,
        rates: Map<String, Double>,
        runningEta: Double,
        loaded: LoadedModel = LoadedModel.unknown,
        costs: ModelChangeCosts = ModelChangeCosts.none,
    ): Timeline {
        val running = queue.firstOrNull { it.status == GenerationStatus.GENERATING }
        // The running job's own change is behind it or in the server's estimate; the others start from its model.
        val start =
            running?.let {
                val needs = JobModel.of(it.payload)
                LoadedModel.after(loaded, needs.model, needs.modules, JobOutcome.DONE)
            } ?: loaded
        val waiting = queue.filter { it.status != GenerationStatus.FAILED && it.status != GenerationStatus.GENERATING }
        val changeOf = waiting.zip(QueueGrouping.changes(start, waiting, costs)).associate { it.first.id to it.second }
        var total = 0.0
        var known = true
        val ends =
            queue.map { job ->
                if (job.status == GenerationStatus.FAILED) return@map null
                val seconds =
                    if (!known) {
                        null
                    } else if (job.status == GenerationStatus.GENERATING) {
                        runningEta.takeIf { it > 0 } ?: seconds(rates, job.payload)
                    } else {
                        seconds(rates, job.payload)?.plus((changeOf[job.id]?.ms ?: 0L) / 1000.0)
                    }
                if (seconds == null) {
                    known = false
                    null
                } else {
                    total += seconds
                    total
                }
            }
        return Timeline(ends, queue.map { changeOf[it.id] }, remaining = if (known) total else null)
    }

    /** Seconds the jobs of [queue] still need (failed ones are skipped), or null while nothing has been learned. */
    fun remaining(
        queue: List<QueuedGeneration>,
        rates: Map<String, Double>,
        runningEta: Double,
    ): Double? = timeline(queue, rates, runningEta).remaining

    /** For each job of [queue], in order, the seconds from now until it should be done (see [timeline]). */
    fun ends(
        queue: List<QueuedGeneration>,
        rates: Map<String, Double>,
        runningEta: Double,
    ): List<Double?> = timeline(queue, rates, runningEta).ends

    /** "2 h 15 min", "12 min", "under a minute". */
    fun formatDuration(seconds: Long): String {
        val minutes = (seconds + 30) / 60
        return when {
            minutes < 1 -> "under a minute"
            minutes < 60 -> "$minutes min"
            else -> String.format(Locale.US, "%d h %02d min", minutes / 60, minutes % 60)
        }
    }

    /** "45 s", "49 min 30 s", "2 h 05 min": the queue's generating time and model changes (3.6.0). */
    fun formatSpan(seconds: Long): String =
        when {
            seconds < 60 -> "$seconds s"
            seconds < 3600 -> "${seconds / 60} min ${seconds % 60} s"
            else -> formatDuration(seconds)
        }

    /** "about 26 s", "about 2 min": what a model change costs or Group by Model saves. */
    fun formatAbout(ms: Long): String {
        val seconds = (ms + 500) / 1000
        return if (seconds < 90) "about $seconds s" else "about ${(seconds + 30) / 60} min"
    }
}
