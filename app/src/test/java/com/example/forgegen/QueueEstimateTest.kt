package com.example.forgegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class QueueEstimateTest {
    private fun payload(
        width: Int = 1000,
        height: Int = 1000,
        steps: Int = 20,
        batchSize: Int = 1,
        batchCount: Int = 1,
        model: String? = "sdxl",
        hires: Boolean = false,
    ) = Txt2ImgPayloadDto(
        prompt = "p",
        negative_prompt = "",
        steps = steps,
        cfg_scale = 7f,
        width = width,
        height = height,
        n_iter = batchCount,
        batch_size = batchSize,
        seed = -1,
        sampler_name = "Euler",
        scheduler = "Automatic",
        override_settings = OverrideSettingsDto(clipSkip = 1, sdModelCheckpoint = model),
        enable_hr = hires,
        hr_scale = 2f,
        hr_upscaler = "Latent",
        denoising_strength = 0.5f,
    )

    private fun job(
        p: Txt2ImgPayloadDto,
        status: GenerationStatus = GenerationStatus.QUEUED,
    ) = QueuedGeneration(id = p.hashCode().toString() + status, positivePrompt = "p", payload = p, status = status)

    @Test
    fun `the work grows with pixels, steps, images and the hires pass`() {
        assertEquals(20.0, QueueEstimate.workload(payload()), 1e-9)
        assertEquals(80.0, QueueEstimate.workload(payload(batchSize = 2, batchCount = 2)), 1e-9)
        // Hires x2 at denoising 0.5: 4 times the pixels, half the steps.
        assertEquals(20.0 + 4 * 20 * 0.5, QueueEstimate.workload(payload(hires = true)), 1e-9)
    }

    @Test
    fun `the speed is learned per checkpoint, with a common one for new checkpoints`() {
        var rates = emptyMap<String, Double>()
        assertNull("nothing learned yet", QueueEstimate.seconds(rates, payload()))
        rates = QueueEstimate.learn(rates, payload(), seconds = 40.0) // 2 s per unit
        assertEquals(40.0, QueueEstimate.seconds(rates, payload())!!, 1e-9)
        assertEquals("another checkpoint: the common speed", 40.0, QueueEstimate.seconds(rates, payload(model = "sd15"))!!, 1e-9)
        rates = QueueEstimate.learn(rates, payload(), seconds = 80.0) // 4 s per unit: 70 % old, 30 % new
        assertEquals(20 * (2 * 0.7 + 4 * 0.3), QueueEstimate.seconds(rates, payload())!!, 1e-9)
        assertEquals("nothing learned from nothing", rates, QueueEstimate.learn(rates, payload(), seconds = 0.0))
    }

    @Test
    fun `the queue left counts waiting jobs, the running one by the server's estimate, not failed ones`() {
        val rates = QueueEstimate.learn(emptyMap(), payload(), seconds = 40.0)
        val queue =
            listOf(
                job(payload(), GenerationStatus.GENERATING),
                job(payload(steps = 10)),
                job(payload(steps = 40), GenerationStatus.FAILED),
            )
        assertEquals(15.0 + 20.0, QueueEstimate.remaining(queue, rates, runningEta = 15.0)!!, 1e-9)
        assertEquals("no server estimate yet", 40.0 + 20.0, QueueEstimate.remaining(queue, rates, runningEta = 0.0)!!, 1e-9)
        assertNull(QueueEstimate.remaining(queue, emptyMap(), runningEta = 15.0))
        assertEquals(0.0, QueueEstimate.remaining(emptyList(), emptyMap(), 0.0)!!, 1e-9)
    }

    @Test
    fun `the timeline gives each job the time it should be done, failed jobs none`() {
        val rates = mapOf("sdxl" to 0.5, QueueEstimate.ANY_MODEL to 1.0)
        val queue =
            listOf(
                job(payload(), GenerationStatus.GENERATING),
                job(payload(steps = 40)),
                job(payload(steps = 10), GenerationStatus.FAILED),
                job(payload(model = "flux")),
            )
        // The running job by the server's 4 s, then 20 s (40 × 0.5), then 20 s (20 × the common rate).
        assertEquals(listOf(4.0, 24.0, null, 44.0), QueueEstimate.ends(queue, rates, runningEta = 4.0))
        assertEquals("without the server's estimate the running job is estimated too", 10.0, QueueEstimate.ends(queue, rates, 0.0)[0])
        assertEquals(
            "nothing learned: only the running job has a time, the server's own",
            listOf(4.0, null, null, null),
            QueueEstimate.ends(queue, emptyMap(), 4.0),
        )
        val unknownModel = listOf(job(payload()), job(payload(model = "new")), job(payload()))
        assertEquals(
            "from a job that cannot be estimated on, none can",
            listOf(10.0, null, null),
            QueueEstimate.ends(unknownModel, mapOf("sdxl" to 0.5), 0.0),
        )
    }

    @Test
    fun `durations read like a clock`() {
        assertEquals("under a minute", QueueEstimate.formatDuration(20))
        assertEquals("12 min", QueueEstimate.formatDuration(12 * 60))
        assertEquals("2 h 15 min", QueueEstimate.formatDuration(2 * 3600 + 15 * 60))
        assertEquals("1 h 00 min", QueueEstimate.formatDuration(3599))
    }

    @Test
    fun `the start time is the next time the clock shows it`() {
        val now =
            Calendar.getInstance().apply {
                set(2026, Calendar.SEPTEMBER, 26, 22, 30, 15)
                set(Calendar.MILLISECOND, 0)
            }

        fun at(
            day: Int,
            hour: Int,
            minute: Int,
        ) = Calendar
            .getInstance()
            .apply {
                set(2026, Calendar.SEPTEMBER, day, hour, minute, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        assertEquals("later today", at(26, 23, 45), QueueSchedule.nextOccurrence(23, 45, now.timeInMillis))
        assertEquals("passed today: tomorrow", at(27, 1, 0), QueueSchedule.nextOccurrence(1, 0, now.timeInMillis))
        assertEquals("this very minute: tomorrow", at(27, 22, 30), QueueSchedule.nextOccurrence(22, 30, now.timeInMillis))
    }
}
