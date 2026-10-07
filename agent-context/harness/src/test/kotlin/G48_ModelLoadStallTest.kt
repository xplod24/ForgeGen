package com.example.forgegen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Owner's report 2026-10-01, fixed in 3.5.3 (SlowServer): "Add To Queue" after a checkpoint change showed a lost
 * connection for a while, then everything went on. Forge loads the new checkpoint inside the job's txt2img request
 * (process_images -> forge_model_reload) and the loading thread holds Python's GIL for long stretches, so the light
 * /sdapi/v1/progress answers only after it. The stand-in does the same: a "load" before the job of another checkpoint,
 * with the progress endpoint stalled meanwhile (01) or answering (02, the control).
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class G48_ModelLoadStallTest {
    companion object {
        @Volatile var loadMs = 7_000L
        @Volatile var loaded = "model"
        @Volatile var loadingUntil = 0L
        @Volatile var stallProgress = true

        @BeforeClass @JvmStatic fun init() {
            TestApp.start(config = { copy(timeout = 3) }, custom = { _, path, body ->
                when (path) {
                    "/sdapi/v1/txt2img" -> {
                        val wanted = if (body.contains("other.safetensors")) "other" else "model"
                        if (wanted != loaded) {
                            loadingUntil = System.currentTimeMillis() + loadMs
                            Thread.sleep(loadMs) // forge_model_reload()
                            loaded = wanted
                        }
                        false // then the usual generation
                    }
                    "/sdapi/v1/progress" -> {
                        val wait = loadingUntil - System.currentTimeMillis()
                        if (stallProgress && wait > 0) Thread.sleep(wait) // no answer while the GIL is held
                        false
                    }
                    else -> false
                }
            })
        }

        /** Runs one job with [model] and records what the app showed meanwhile, every 100 ms. */
        fun runJob(model: String): List<String> {
            val seen = CopyOnWriteArrayList<String>()
            onMain { TestApp.vm.changeCheckpoint(model) }
            onMain { TestApp.vm.updateState { it.copy(positivePrompt = "a cat", batchCount = 1) } }
            val before = TestApp.vm.sessionImages.value.size
            val t0 = System.currentTimeMillis()
            onMain { TestApp.vm.queueGeneration() }
            val watcher = Thread {
                while (!Thread.currentThread().isInterrupted) {
                    val line =
                        "${TestApp.vm.connection.value} | ${ForgeQueueManager.statusText.value} | paused=${TestApp.vm.isQueuePaused.value}"
                    if (seen.lastOrNull()?.substringAfter("] ") != line) seen += "[${(System.currentTimeMillis() - t0) / 100 / 10.0}s] $line"
                    try { Thread.sleep(100) } catch (e: InterruptedException) { break }
                }
            }.apply { start() }
            awaitUntil("job done", 40_000) { TestApp.vm.sessionImages.value.size > before && TestApp.vm.generationQueue.value.isEmpty() }
            Thread.sleep(1_500)
            watcher.interrupt()
            watcher.join()
            return seen
        }
    }

    @Test fun `01 a checkpoint load that stalls the progress endpoint is a busy server, not a lost one`() {
        stallProgress = true
        val other = TestApp.vm.models.value.first { it.title.startsWith("other") }.title
        val seen = runJob(other)
        seen.forEach { println("[G48-01] $it") }
        assertFalse("never shown as gone", seen.any { it.contains("SEARCHING") || it.contains("Connection lost") })
        assertTrue("says what it waits for", seen.any { it.contains("Loading model other") })
        assertTrue(seen.last().contains("CONNECTED"))
        assertFalse("the queue is never paused for it", seen.any { it.contains("paused=true") })
    }

    @Test fun `02 control - the same load with the progress endpoint answering shows nothing`() {
        stallProgress = false
        val back = TestApp.vm.models.value.first { it.title.startsWith("model") }.title
        val seen = runJob(back)
        seen.forEach { println("[G48-02] $it") }
        assertFalse(seen.any { it.contains("SEARCHING") || it.contains("Connection lost") || it.contains("busy") })
    }

    @Test fun `03 a server busy for longer than the limit counts as lost after all, and comes back`() {
        stallProgress = true
        loadMs = 12_000L
        SlowServer.maxSlowMs = 4_000L
        try {
            val other = TestApp.vm.models.value.first { it.title.startsWith("other") }.title
            val seen = runJob(other)
            seen.forEach { println("[G48-03] $it") }
            assertTrue("first busy", seen.any { it.contains("Loading model other") })
            assertTrue("then gone", seen.any { it.contains("SEARCHING") })
            assertTrue("and back at the end", seen.last().contains("CONNECTED"))
        } finally {
            SlowServer.maxSlowMs = 5 * 60_000L
            loadMs = 7_000L
        }
    }
}
