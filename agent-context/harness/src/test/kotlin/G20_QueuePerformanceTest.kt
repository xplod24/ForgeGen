package com.example.forgegen

import com.sun.net.httpserver.HttpExchange
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.File

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class G20_QueuePerformanceTest {
    companion object {
        val vm get() = TestApp.vm

        /** "batch": three images plus other fields around them; "broken": not JSON at all. */
        @Volatile var answer = "batch"

        fun route(ex: HttpExchange, path: String): Boolean {
            if (path != "/sdapi/v1/txt2img") return false
            Thread.sleep(500) // long enough for the test to queue a second job meanwhile
            val body =
                when (answer) {
                    "broken" -> "<html>Bad gateway</html>"
                    else -> {
                        val images = (1..3).joinToString(",") { "\"" + java.util.Base64.getEncoder().encodeToString(Png.withParameters("image $it\nSteps: 1, Sampler: Euler, Seed: $it, Model: m")) + "\"" }
                        """{"parameters":{"prompt":"x","nested":{"a":[1,2,{"b":null}]}},"images":[$images],"info":"{\"seed\": 1}"}"""
                    }
                }
            val bytes = body.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
            return true
        }

        @BeforeClass @JvmStatic fun init() {
            TestApp.start()
            TestApp.forge.custom = { ex, path, _ -> route(ex, path) }
        }

        fun runJob(jobs: Int = 1) {
            repeat(jobs) { i ->
                onMain { vm.updateState { it.copy(positivePrompt = "perf $i", batchCount = 1) } }
                onMain { vm.queueGeneration() }
                Thread.sleep(200)
            }
            awaitUntil("job finished", 20_000) { vm.generationQueue.value.isEmpty() && !vm.isGenerating.value || vm.isQueuePaused.value }
        }
    }

    @Before fun reset() {
        answer = "batch"
        TestApp.app.brokenCache = false
        onMain { vm.clearQueue() }
        onMain { vm.resumeQueue() }
        onMain { vm.setAppForegroundState(true) }
    }

    @Test fun `01 a batch is read image by image into the cache`() {
        val before = vm.sessionImages.value.size
        runJob()
        val images = vm.sessionImages.value.drop(before)
        assertEquals(3, images.size)
        val texts = images.map { File(it).inputStream().use { s -> PngMetadata.readParameters(s) } }
        assertEquals(listOf("image 1", "image 2", "image 3"), texts.map { it.lines().first() })
        assertArrayEquals(File(images.first()).readBytes(), File(TestApp.app.cacheDir, "last_generated_image.png").readBytes())
        assertFalse(vm.isQueuePaused.value)
    }

    @Test fun `02 the live preview is only requested while the app is on screen`() {
        fun lastProgressQuery() = TestApp.forge.calls("/sdapi/v1/progress").last().query.orEmpty()
        vm.setPreviewShown(true) // the main screen shows it (3.4.0: only then is it asked for, G45)
        Thread.sleep(1500)
        assertTrue(lastProgressQuery(), lastProgressQuery().contains("skip_current_image=false"))
        onMain { vm.setAppForegroundState(false) }
        // In the background with nothing to do the app no longer asks the server at all (2.0.0).
        Thread.sleep(2_500)
        val idle = TestApp.forge.calls("/sdapi/v1/progress").size
        Thread.sleep(4_000)
        assertEquals("no pings in the background", idle, TestApp.forge.calls("/sdapi/v1/progress").size)
        // While a job runs it does, without the preview.
        TestApp.forge.generationMs = 4_000
        onMain { vm.updateState { it.copy(positivePrompt = "background job", batchCount = 1) } }
        onMain { vm.queueGeneration() }
        awaitUntil("pinged while generating", 15_000) { TestApp.forge.calls("/sdapi/v1/progress").size > idle }
        assertTrue(lastProgressQuery(), lastProgressQuery().contains("skip_current_image=true"))
        awaitUntil("job done", 20_000) { vm.generationQueue.value.isEmpty() && !vm.isGenerating.value }
        TestApp.forge.generationMs = 300
        onMain { vm.setAppForegroundState(true) }
        // Back on screen: at once, not after the next interval.
        val back = TestApp.forge.calls("/sdapi/v1/progress").size
        awaitUntil("ping on return", 1_500) { TestApp.forge.calls("/sdapi/v1/progress").size > back }
    }

    @Test fun `03 memory statistics are asked for about every 10 s, not at every ping`() {
        val first = TestApp.forge.calls("/sdapi/v1/progress").size
        awaitUntil("pinging on screen again", 15_000) { TestApp.forge.calls("/sdapi/v1/progress").size > first + 1 }
        val progress = TestApp.forge.calls("/sdapi/v1/progress").size
        val memory = TestApp.forge.calls("/sdapi/v1/memory").size
        // On screen and idle: a ping every 4 s (3.6.0-1; 2 s from 2.0.0), the memory at the first one 10 s after the last.
        Thread.sleep(12_500)
        val p = TestApp.forge.calls("/sdapi/v1/progress").size - progress
        val m = TestApp.forge.calls("/sdapi/v1/memory").size - memory
        println("[G20-03] progress=$p memory=$m")
        assertTrue("$m memory requests for $p pings", m in 1..2 && p in 3..4)
    }

    @Test fun `04 a failure to save the images is an error, not a lost connection`() {
        TestApp.app.brokenCache = true
        runJob(jobs = 2) // the second job keeps the queue paused, so the reason stays
        TestApp.app.brokenCache = false
        println("[G20-04] reason=${vm.queuePauseReason.value} paused=${vm.isQueuePaused.value} status=${ForgeQueueManager.statusText.value} queue=${vm.generationQueue.value.map { it.positivePrompt + "/" + it.status }} images=${vm.sessionImages.value.size} txt2img=${TestApp.forge.calls("/sdapi/v1/txt2img").size}")
        assertTrue(vm.queuePauseReason.value.orEmpty().startsWith("Generation failed"))
        assertFalse(vm.queuePauseReason.value == ForgeQueueManager.CONNECTION_LOST_REASON)
    }

    @Test fun `05 a broken answer is an error, not a lost connection`() {
        answer = "broken"
        runJob(jobs = 2)
        println("[G20-05] reason=${vm.queuePauseReason.value}")
        assertTrue(vm.queuePauseReason.value.orEmpty().startsWith("Generation failed"))
    }
}
