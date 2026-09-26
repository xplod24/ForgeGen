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
     * Seconds the jobs of [queue] still need (failed ones are skipped): the running one by the server's own
     * estimate [runningEta] when it has one. Null while nothing has been learned.
     */
    fun remaining(
        queue: List<QueuedGeneration>,
        rates: Map<String, Double>,
        runningEta: Double,
    ): Double? {
        var total = 0.0
        for (job in queue) {
            total +=
                when (job.status) {
                    GenerationStatus.FAILED -> 0.0
                    GenerationStatus.GENERATING -> runningEta.takeIf { it > 0 } ?: seconds(rates, job.payload) ?: return null
                    else -> seconds(rates, job.payload) ?: return null
                }
        }
        return total
    }

    /** "2 h 15 min", "12 min", "under a minute". */
    fun formatDuration(seconds: Long): String {
        val minutes = (seconds + 30) / 60
        return when {
            minutes < 1 -> "under a minute"
            minutes < 60 -> "$minutes min"
            else -> String.format(Locale.US, "%d h %02d min", minutes / 60, minutes % 60)
        }
    }
}
