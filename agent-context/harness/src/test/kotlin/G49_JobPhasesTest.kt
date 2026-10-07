package com.example.forgegen

import com.sun.net.httpserver.HttpExchange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters

/**
 * 3.6.0: every job's record (JobRecorder) as Forge Neo really behaves. The stand-in loads a checkpoint inside the job's
 * request like forge_model_reload: the old model leaves VRAM, the new one is read from disk while Python's GIL is held
 * (the progress and memory endpoints answer only after it), then the weights move into VRAM (the endpoints answer, the
 * VRAM climbs), then the steps run; hires fix is a second pass with fewer steps.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class G49_JobPhasesTest {
    object StandIn {
        @Volatile var loaded = "model"
        @Volatile var vram = 7.8f
        @Volatile var stallUntil = 0L
        @Volatile var step = 0
        @Volatile var steps = 0
        @Volatile var jobNo = 0
        @Volatile var jobCount = 0
        @Volatile var loadMs = 4_000L
        @Volatile var vramMs = 2_000L
        @Volatile var stepMs = 100L
        @Volatile var oom = false

        private fun now() = System.currentTimeMillis()

        private fun waitForGil() {
            val wait = stallUntil - now()
            if (wait > 0) Thread.sleep(wait)
        }

        private fun pass(count: Int) {
            steps = count
            for (s in 1..count) {
                step = s
                Thread.sleep(stepMs)
            }
            step = 0
            Thread.sleep(300) // decoding between passes
        }

        private fun send(ex: HttpExchange, code: Int, body: String) {
            val bytes = body.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }

        fun route(ex: HttpExchange, path: String, body: String): Boolean {
            when (path) {
                "/sdapi/v1/progress" -> {
                    waitForGil()
                    val progress = if (steps > 0) step.toDouble() / steps else 0.0
                    send(ex, 200, """{"progress":$progress,"eta_relative":1.0,"state":{"job_count":$jobCount,"job_no":$jobNo,"sampling_step":$step,"sampling_steps":$steps},"current_image":null}""")
                }
                "/sdapi/v1/memory" -> {
                    waitForGil()
                    val used = (vram * 1024 * 1024 * 1024).toLong()
                    send(ex, 200, """{"ram":{"used":8589934592,"total":34359738368},"cuda":{"system":{"used":$used,"total":12884901888}}}""")
                }
                "/sdapi/v1/unload-checkpoint" -> {
                    loaded = ""
                    vram = 0.5f
                    send(ex, 200, "null")
                }
                "/sdapi/v1/txt2img" -> {
                    val job = org.json.JSONObject(body)
                    val wanted = if (body.contains("other.safetensors")) "other" else "model"
                    jobCount = job.optInt("n_iter", 1)
                    if (wanted != loaded) {
                        vram = 0.9f // the old model is unloaded at once
                        stallUntil = now() + loadMs
                        Thread.sleep(loadMs) // forge_loader: read from disk, GIL held
                        val start = now()
                        while (now() - start < vramMs) { // into VRAM: the endpoints answer meanwhile
                            vram = 0.9f + 6.7f * (now() - start) / vramMs
                            Thread.sleep(100)
                        }
                        loaded = wanted
                        vram = 7.6f
                    }
                    if (oom) {
                        jobCount = 0
                        send(ex, 500, """{"error":"OutOfMemoryError","detail":"CUDA out of memory. Tried to allocate 2.50 GiB"}""")
                        return true
                    }
                    for (i in 0 until jobCount) {
                        jobNo = i
                        vram = 9.0f
                        pass(job.getInt("steps"))
                        if (job.optBoolean("enable_hr")) {
                            vram = 10.6f
                            pass(20)
                        }
                    }
                    vram = 7.9f
                    jobNo = 0
                    jobCount = 0
                    Thread.sleep(200) // saving and encoding
                    val png = java.util.Base64.getEncoder().encodeToString(Png.withParameters(INFOTEXT))
                    send(ex, 200, """{"images":["$png"],"parameters":{},"info":"{}"}""")
                }
                else -> return false
            }
            return true
        }
    }

    companion object {
        val vm get() = TestApp.vm
        lateinit var server: String

        @BeforeClass @JvmStatic fun init() {
            TestApp.start(config = { copy(timeout = 6) }, custom = { ex, path, body -> StandIn.route(ex, path, body) })
            server = GalleryKey.serverOf(TestApp.forge.url)
        }

        fun title(name: String) = TestApp.vm.models.value.first { it.title.startsWith(name) }.title

        /** Runs one job and returns its record. */
        fun runJob(model: String, steps: Int = 20, hires: Boolean = false): JobRunEntity {
            onMain { vm.changeCheckpoint(title(model)) }
            onMain { vm.updateState { it.copy(positivePrompt = "a cat", batchCount = 1, steps = steps, hiresFix = hires) } }
            val before = TestApp.db.jobRuns.size
            val t0 = System.currentTimeMillis()
            onMain { vm.queueGeneration() }
            awaitUntil("job recorded", 60_000) { TestApp.db.jobRuns.size > before && !vm.isGenerating.value }
            val run = TestApp.db.jobRuns.values.maxBy { it.startedAt }
            println("[G49] ${System.currentTimeMillis() - t0} ms: $run")
            return run
        }

        fun near(what: String, expected: Long, actual: Long?, tolerance: Long = 1_500) {
            assertNotNull(what, actual)
            assertTrue("$what: $actual instead of about $expected", kotlin.math.abs(actual!! - expected) <= tolerance)
        }
    }

    @Test fun `01 a cold start after Unload Model splits the load from the move into VRAM`() {
        onMain { vm.unloadCheckpoint() }
        awaitUntil("model unloaded") { StandIn.loaded.isEmpty() && TestApp.db.settings["loaded_model:$server"]?.contains("EMPTY") == true }
        val run = runJob("model")
        assertEquals("COLD", run.startKind)
        near("unload and load", 4_000, run.loadMs)
        near("into VRAM", 2_000, run.vramMs)
        near("to the first step", 6_000, run.firstStepMs, 700)
        val curve = JobTimeline.curve(run.vramCurve)
        assertTrue("readings while the VRAM climbs: $curve", curve.any { it.first in 3_500..6_500 && it.second in 1f..7.5f })
        assertTrue("the peak while sampling", (run.vramPeakGb ?: 0f) >= 8.9f)
        assertEquals(12f, run.vramTotalGb)
        assertEquals("DONE", run.outcome)
        assertEquals(server, run.server)
    }

    @Test fun `02 the same model again goes straight to sampling`() {
        val run = runJob("model", steps = 40)
        assertEquals("SAME", run.startKind)
        assertNull(run.loadMs)
        assertTrue("first step soon: ${run.firstStepMs}", (run.firstStepMs ?: 99_999) < 1_000)
        assertTrue("about 10 steps a second: ${run.itPerSec}", (run.itPerSec ?: 0f) in 7f..13f)
        near("sampling", 3_900, run.samplingMs, 600)
        near("sending", 500, run.sendMs, 600)
        assertNotNull(run.sendMs)
    }

    @Test fun `03 another checkpoint is a swap from the one before`() {
        StandIn.loadMs = 3_000L
        val run = runJob("other")
        assertEquals("SWAP", run.startKind)
        assertEquals(title("model"), run.previousModel)
        assertEquals(title("other"), run.model)
        near("unload and load", 3_000, run.loadMs)
        near("into VRAM", 2_000, run.vramMs)
    }

    @Test fun `04 hires fix has its own phase and speed`() {
        val run = runJob("other", steps = 30, hires = true)
        assertEquals("SAME", run.startKind)
        assertNotNull("the hires pass: $run", run.hiresMs)
        near("base pass and decoding", 3_200, run.samplingMs, 700)
        near("hires pass", 1_900, run.hiresMs, 700)
        assertTrue("hires speed: ${run.hiresItPerSec}", (run.hiresItPerSec ?: 0f) in 7f..13f)
        assertEquals("the default scale", 2.0f, run.hiresScale)
    }

    @Test fun `05 a job that ran out of VRAM is recorded with its reason, and the next one checks the model again`() {
        StandIn.oom = true
        val before = TestApp.db.jobRuns.size
        onMain { vm.updateState { it.copy(hiresFix = false) } }
        onMain { vm.queueGeneration() }
        awaitUntil("failed job recorded", 30_000) { TestApp.db.jobRuns.size > before && !vm.isGenerating.value }
        val failed = TestApp.db.jobRuns.values.maxBy { it.startedAt }
        assertEquals("FAILED", failed.outcome)
        assertEquals("OUT_OF_VRAM", failed.failure)
        assertTrue(failed.failureText.orEmpty().contains("out of memory", ignoreCase = true))
        assertTrue(TestApp.db.settings["loaded_model:$server"].orEmpty().contains("UNKNOWN"))
        StandIn.oom = false
        onMain { vm.resumeQueue() }
        awaitUntil("queue empty", 60_000) { vm.generationQueue.value.none { it.status != GenerationStatus.FAILED } && !vm.isGenerating.value }
        // The model was still there: the unknown start turns out to be the same model.
        val next = runJob("other")
        assertEquals("SAME", next.startKind)
    }

    @Test fun `06 with Generation History off nothing is recorded`() {
        onMain { vm.saveConfig(ForgeRepository.config.value.copy(generationHistory = false)) }
        val before = TestApp.db.jobRuns.size
        val memoryBefore = TestApp.forge.calls("/sdapi/v1/memory").size
        onMain { vm.queueGeneration() }
        awaitUntil("job done", 30_000) { vm.isGenerating.value }
        awaitUntil("job done", 30_000) { !vm.isGenerating.value }
        Thread.sleep(500)
        assertEquals(before, TestApp.db.jobRuns.size)
        assertTrue("no reading at every ping", TestApp.forge.calls("/sdapi/v1/memory").size - memoryBefore <= 2)
    }
}
