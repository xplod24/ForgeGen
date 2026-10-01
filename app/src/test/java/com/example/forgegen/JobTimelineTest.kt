package com.example.forgegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.6.0: a job's phases as the app sees them, and how it found the server's model. */
class JobTimelineTest {
    private fun timeline(
        predicted: JobStart,
        previous: String? = null,
        hires: Boolean = false,
        iterations: Int = 1,
        vramBefore: Float? = 7.8f,
    ) = JobTimeline(
        id = "r1",
        server = "http://pc:7860",
        queueJobId = "q1",
        startedAt = 0L,
        model = "animagineXL31",
        modules = "sdxl_vae",
        predicted = predicted,
        previousModel = previous,
        firstHash = false,
        width = 832,
        height = 1216,
        images = 4,
        iterations = iterations,
        steps = 28,
        sampler = "Euler a",
        scheduler = "Karras",
        hiresScale = if (hires) 1.5f else null,
        hiresSteps = if (hires) 15 else null,
        vramBefore = vramBefore,
    )

    /** Steps [from]..[to] of a pass of [steps], one every [msPerStep] from [at]; returns the time after the last one. */
    private fun JobTimeline.pass(
        at: Long,
        from: Int,
        to: Int,
        steps: Int,
        msPerStep: Long,
        jobNo: Int = 0,
    ): Long {
        var t = at
        for (step in from..to) {
            onProgress(t, step, steps, jobNo)
            t += msPerStep
        }
        return t - msPerStep
    }

    @Test
    fun `a swap is split into the load the server was busy with and the move into VRAM`() {
        val t = timeline(JobStart.SWAP, previous = "ponyDiffusionV6XL", hires = true)
        t.onVram(0, 7.8f, 12f)
        t.onBusy(1_000, 7_900) // the pings sent meanwhile were answered only when the load was over
        t.onVram(8_900, 3.1f, 12f)
        t.onVram(9_900, 7.6f, 12f)
        assertTrue(t.waitingForFirstStep)
        var end = t.pass(10_000, 1, 28, 28, 135) // 7.4 steps per second
        end = t.pass(end + 1_500, 1, 15, 15, 345) // the hires pass after decoding
        val run = t.finish(end + 1_100, JobOutcome.DONE)
        assertEquals("SWAP", run.startKind)
        assertEquals("ponyDiffusionV6XL", run.previousModel)
        assertEquals(7_900L, run.loadMs)
        assertEquals(2_100L, run.vramMs)
        assertEquals(10_000L, run.firstStepMs)
        assertEquals(27 * 135L + 1_500, run.samplingMs)
        assertEquals(14 * 345L, run.hiresMs)
        assertEquals(1_100L, run.sendMs)
        assertEquals(end + 1_100, run.totalMs)
        assertEquals(7.41f, run.itPerSec)
        assertEquals(2.9f, run.hiresItPerSec)
        assertEquals(7.8f, run.vramBeforeGb)
        assertEquals(7.8f, run.vramPeakGb)
        assertEquals(12f, run.vramTotalGb)
        assertEquals(listOf(0L to 7.8f, 8_900L to 3.1f, 9_900L to 7.6f), JobTimeline.curve(run.vramCurve).drop(1))
        assertEquals("DONE", run.outcome)
        assertNull(run.failure)
    }

    @Test
    fun `steps seen only at the pings are placed by the speed`() {
        val t = timeline(JobStart.SAME)
        t.onProgress(9_000, 0, 0, 0) // the last ping before the sampling began
        t.onProgress(10_000, 5, 28, 0)
        t.onProgress(11_000, 12, 28, 0)
        t.onProgress(12_000, 20, 28, 0)
        t.onProgress(13_000, 27, 28, 0)
        val run = t.finish(14_000, JobOutcome.DONE)
        // 22 steps in 3 s: the first step about 545 ms before step 5 was seen, the last one about 136 ms after step 27.
        assertEquals(9_455L, run.firstStepMs)
        assertEquals(13_136L - 9_455L, run.samplingMs)
        assertEquals(14_000L - 13_136L, run.sendMs)
        assertEquals(7.33f, run.itPerSec)
        // Never before a ping that still saw no step.
        val early = timeline(JobStart.SAME)
        early.onProgress(9_900, 0, 0, 0)
        early.onProgress(10_000, 5, 28, 0)
        early.onProgress(11_000, 12, 28, 0)
        assertEquals(9_900L, early.finish(12_000, JobOutcome.DONE).firstStepMs)
    }

    @Test
    fun `the same model goes straight to sampling`() {
        val t = timeline(JobStart.SAME)
        val end = t.pass(700, 1, 28, 28, 135)
        val run = t.finish(end + 800, JobOutcome.DONE)
        assertEquals("SAME", run.startKind)
        assertEquals(700L, run.firstStepMs)
        assertNull(run.loadMs)
        assertNull(run.vramMs)
        assertNull("no hires fix", run.hiresMs)
        assertNull(run.previousModel)
    }

    @Test
    fun `a model that should have been there but had to be loaded is not counted`() {
        val busy = timeline(JobStart.SAME).apply { onBusy(500, 9_000) }
        busy.pass(10_000, 1, 2, 28, 135)
        assertEquals("UNKNOWN", busy.finish(20_000, JobOutcome.DONE).startKind)

        // A late first step tells a load too, even when no slow answer was seen (the pings came between stalls).
        val late = timeline(JobStart.SAME)
        late.pass(12_000, 1, 2, 28, 135)
        assertEquals("UNKNOWN", late.finish(20_000, JobOutcome.DONE).startKind)
    }

    @Test
    fun `an unknown model that turns out to be loaded counts as the same model`() {
        val t = timeline(JobStart.UNKNOWN)
        t.pass(900, 1, 28, 28, 135)
        assertEquals("SAME", t.finish(6_000, JobOutcome.DONE).startKind)
    }

    @Test
    fun `other jobs before ours make its times unknown`() {
        val t = timeline(JobStart.COLD)
        t.onJobsAhead()
        t.pass(30_000, 1, 28, 28, 135)
        assertEquals("UNKNOWN", t.finish(40_000, JobOutcome.DONE).startKind)
    }

    @Test
    fun `a cold start without a slow answer keeps its time to the first step but no split`() {
        val t = timeline(JobStart.COLD, vramBefore = null)
        t.pass(15_000, 1, 28, 28, 135)
        val run = t.finish(20_000, JobOutcome.DONE)
        assertEquals("COLD", run.startKind)
        assertEquals(15_000L, run.firstStepMs)
        assertNull(run.loadMs)
        assertNull(run.vramBeforeGb)
        assertNull(run.vramCurve)
    }

    @Test
    fun `two batches with hires fix give one sampling time and both speeds`() {
        val t = timeline(JobStart.SAME, hires = true, iterations = 2)
        var end = t.pass(600, 1, 28, 28, 135, jobNo = 0)
        end = t.pass(end + 1_000, 1, 15, 15, 345, jobNo = 0)
        end = t.pass(end + 1_000, 1, 28, 28, 135, jobNo = 1)
        end = t.pass(end + 1_000, 1, 15, 15, 345, jobNo = 1)
        val run = t.finish(end + 900, JobOutcome.DONE)
        assertEquals(end - 600, run.samplingMs)
        assertNull("the passes interleave", run.hiresMs)
        assertEquals(7.41f, run.itPerSec)
        assertEquals(2.9f, run.hiresItPerSec)
    }

    @Test
    fun `a failed job keeps its reason`() {
        val t = timeline(JobStart.SAME)
        val run = t.finish(3_000, JobOutcome.FAILED, JobFailure.OUT_OF_VRAM, "CUDA out of memory. Tried to allocate 2.50 GiB")
        assertEquals("FAILED", run.outcome)
        assertEquals("OUT_OF_VRAM", run.failure)
        assertNull(run.firstStepMs)
        assertNull(run.samplingMs)
        assertNull(run.sendMs)
        assertEquals(3_000L, run.totalMs)
    }

    @Test
    fun `what the server has loaded`() {
        val known = LoadedModel(LoadedModel.KNOWN, "animagineXL31", "sdxl_vae")
        assertEquals(JobStart.SAME to null, LoadedModel.predict(known, "animagineXL31", "sdxl_vae"))
        assertEquals("the server's own model", JobStart.SAME to null, LoadedModel.predict(known, "", ""))
        assertEquals(JobStart.SWAP to "animagineXL31", LoadedModel.predict(known, "flux1-dev", "ae, clip_l, t5xxl_fp8"))
        assertEquals(
            "another VAE reloads the model",
            JobStart.SWAP to "animagineXL31",
            LoadedModel.predict(known, "animagineXL31", "other_vae"),
        )
        assertEquals(JobStart.COLD to null, LoadedModel.predict(LoadedModel.empty, "animagineXL31", ""))
        assertEquals(JobStart.UNKNOWN to null, LoadedModel.predict(LoadedModel.unknown, "animagineXL31", ""))

        assertEquals(
            LoadedModel(LoadedModel.KNOWN, "flux1-dev", "ae"),
            LoadedModel.after(known, "flux1-dev", "ae", JobOutcome.DONE),
        )
        assertEquals("the server's own model stays", known, LoadedModel.after(known, "", "", JobOutcome.INTERRUPTED))
        assertEquals(LoadedModel.unknown, LoadedModel.after(known, "flux1-dev", "ae", JobOutcome.FAILED))
        assertEquals(LoadedModel.unknown, LoadedModel.after(LoadedModel.empty, "", "", JobOutcome.DONE))
    }

    @Test
    fun `the readings come back from their text`() {
        assertEquals(listOf(0L to 7.8f, 1_000L to 9.1f), JobTimeline.curve("0:7.8,1000:9.1"))
        assertEquals(emptyList<Pair<Long, Float>>(), JobTimeline.curve(null))
        assertEquals(listOf(5L to 1f), JobTimeline.curve("x,5:1,7:"))
    }
}
