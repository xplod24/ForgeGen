package com.example.forgegen

import com.sun.net.httpserver.HttpExchange
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters

/**
 * 3.6.0, Unload After the Queue: when the queue is done the server's model leaves VRAM at once or after an alarm, only
 * when nothing waits in the app's queue and the server does no other job; a job added meanwhile takes the alarm back.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class G51_AutoUnloadTest {
    companion object {
        val vm get() = TestApp.vm
        lateinit var server: String

        // Jobs the server's web UI has waiting (/internal/pending-tasks).
        @Volatile var othersWaiting = 0

        private fun send(ex: HttpExchange, code: Int, text: String): Boolean {
            val bytes = text.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
            return true
        }

        @BeforeClass @JvmStatic fun init() {
            TestApp.start(custom = { ex, path, _ ->
                when (path) {
                    "/sdapi/v1/unload-checkpoint" -> send(ex, 200, "null")
                    "/internal/pending-tasks" -> send(ex, 200, """{"size":$othersWaiting,"tasks":[]}""")
                    else -> false
                }
            })
            server = GalleryKey.serverOf(TestApp.forge.url)
            onMain { vm.updateState { it.copy(positivePrompt = "a cat", batchCount = 1) } }
        }

        fun unloads() = TestApp.forge.calls("/sdapi/v1/unload-checkpoint").size

        fun choose(choice: Int) {
            onMain { vm.saveConfig(vm.config.value.copy(unloadAfterQueue = choice)) }
            awaitUntil("saved") { vm.config.value.unloadAfterQueue == choice }
        }

        fun runJob() {
            val before = TestApp.db.jobRuns.size
            onMain { vm.queueGeneration() }
            awaitUntil("job done", 20_000) { TestApp.db.jobRuns.size > before && !vm.isGenerating.value && vm.generationQueue.value.isEmpty() }
        }

        fun loadedState() = TestApp.db.settings["loaded_model:$server"].orEmpty()
    }

    @Test fun `01 off by default - the model stays`() {
        assertEquals(AutoUnload.OFF, vm.config.value.unloadAfterQueue)
        runJob()
        Thread.sleep(1_500)
        assertEquals(0, unloads())
        assertTrue(loadedState().contains("KNOWN"))
    }

    @Test fun `02 at once - the model leaves when the queue is done, and the next job starts cold`() {
        choose(AutoUnload.AT_ONCE)
        runJob()
        awaitUntil("unloaded", 5_000) { unloads() == 1 }
        awaitUntil("known to be gone") { loadedState().contains("EMPTY") }
        // The server was asked first whether it has other jobs.
        assertTrue(TestApp.forge.calls("/internal/pending-tasks").isNotEmpty())
        runJob()
        assertEquals("COLD", TestApp.db.jobRuns.values.maxBy { it.startedAt }.startKind)
        awaitUntil("unloaded again", 5_000) { unloads() == 2 }
    }

    @Test fun `03 not while the server has another job waiting`() {
        othersWaiting = 1
        val before = unloads()
        runJob()
        Thread.sleep(1_500)
        assertEquals(before, unloads())
        othersWaiting = 0
    }

    @Test fun `04 after 10 minutes by an alarm, which a new job takes back`() {
        choose(10)
        val alarms = TestApp.app.alarms
        val before = unloads()
        val t0 = System.currentTimeMillis()
        runJob()
        awaitUntil("alarm set") { AutoUnload.pendingAt != null }
        val at = AutoUnload.pendingAt!!
        assertTrue("about 10 minutes from now", at - t0 in 595_000L..610_000L)
        assertEquals("inexact@$at", alarms.calls.last())
        assertEquals("nothing yet", before, unloads())
        // A new job: the alarm goes.
        onMain { ForgeQueueManager.scheduleStart(System.currentTimeMillis() + 3_600_000) }
        onMain { vm.queueGeneration() }
        awaitUntil("alarm taken back") { AutoUnload.pendingAt == null && alarms.calls.contains("cancel") }
        // The alarm while a job waits in the app's queue: the model stays.
        assertFalse(runBlocking { AutoUnload.unloadIfIdle() })
        assertEquals(before, unloads())
        onMain { ForgeQueueManager.startScheduledQueueNow() }
        awaitUntil("job done", 20_000) { !vm.isGenerating.value && vm.generationQueue.value.isEmpty() }
        awaitUntil("alarm set again") { AutoUnload.pendingAt != null }
        // The alarm goes off with nothing to do: the model leaves.
        assertTrue(runBlocking { AutoUnload.unloadIfIdle() })
        assertEquals(before + 1, unloads())
        assertNull(AutoUnload.pendingAt)
        // Already gone: no second request.
        assertFalse(runBlocking { AutoUnload.unloadIfIdle() })
        assertEquals(before + 1, unloads())
        assertNotNull(alarms)
    }

    @Test fun `05 switched off with an alarm set, the model stays`() {
        runJob() // the model is in again, the alarm set for 10 minutes
        awaitUntil("alarm set") { AutoUnload.pendingAt != null }
        val before = unloads()
        onMain { vm.setUnloadAfterQueue(AutoUnload.OFF) }
        awaitUntil("alarm taken back") { AutoUnload.pendingAt == null && TestApp.app.alarms.calls.last() == "cancel" }
        assertFalse("an alarm that still went off does nothing", runBlocking { AutoUnload.unloadIfIdle() })
        assertEquals(before, unloads())
    }
}
