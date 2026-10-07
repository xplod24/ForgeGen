package com.example.forgegen

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
 * 2.3.0-2: the queue starts GenerationService as soon as it is active (waiting for the server or for "Start at"),
 * not only when a job starts; removing the last jobs to run ends a pause, and "Undo" brings it back.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class G36_QueueServiceAndPauseTest {
    companion object {
        @Volatile var down = true
        val vm get() = TestApp.vm
        fun calls() = TestApp.forge.calls("/sdapi/v1/txt2img").size
        fun services() = TestApp.app.startedServices.filter { it.getComponentClass() == GenerationService::class.java }
        fun queue(prompt: String) {
            onMain { vm.updateState { it.copy(positivePrompt = prompt, batchCount = 1) } }
            onMain { vm.queueGeneration() }
        }
        fun prompts() = vm.generationQueue.value.map { it.positivePrompt }

        @BeforeClass @JvmStatic fun init() {
            ForgeRepository.searchWindowMs = 2_000
            ForgeRepository.searchPingMs = 300
            TestApp.start(
                custom = { ex, path, _ -> if (down && path.startsWith("/sdapi/")) { ex.close(); true } else false },
                awaitServer = false,
            )
        }
    }

    @Test fun `01 a queue waiting for an unreachable server has the service before its first job`() {
        TestApp.app.startedServices.clear()
        queue("waiting for the server")
        awaitUntil("active") { vm.isQueueActive.value }
        awaitUntil("service started", 3_000) { services().isNotEmpty() }
        assertEquals(GenerationService.ACTION_START_GENERATION, services().first().action)
        assertEquals("nothing sent", 0, calls())
        assertFalse(vm.isGenerating.value)
        // It stays active (and never offline) while it waits.
        Thread.sleep(2_500)
        assertTrue(vm.isQueueActive.value)
        assertTrue(vm.connection.value != ServerConnection.OFFLINE)
        println("[G36-01] ${services().size} service start(s) before the first job, connection=${vm.connection.value}")

        // The server comes back: the job runs (and starts the service again, as before). The queue asks again every
        // minute by then; another try (back on screen, "Retry") asks at once.
        down = false
        onMain { vm.reconnect() }
        awaitUntil("done", 20_000) { vm.generationQueue.value.isEmpty() && !vm.isGenerating.value }
        assertEquals(1, calls())
        // Derived from the queue on another thread: a moment later.
        awaitUntil("no longer active") { !vm.isQueueActive.value }
    }

    @Test fun `02 jobs added while waiting for Start at start the service too`() {
        TestApp.app.startedServices.clear()
        onMain { ForgeQueueManager.scheduleStart(System.currentTimeMillis() + 60_000) }
        queue("scheduled")
        awaitUntil("service started", 3_000) { services().isNotEmpty() }
        assertTrue(vm.isWaitingForSchedule.value)
        assertEquals("nothing sent before the time", 1, calls())
        onMain { vm.startScheduledQueueNow() }
        awaitUntil("done", 20_000) { vm.generationQueue.value.isEmpty() && !vm.isGenerating.value }
        assertEquals(2, calls())
    }

    @Test fun `03 Clear ends the pause, and Undo brings it back with the jobs`() {
        onMain { ForgeQueueManager.scheduleStart(System.currentTimeMillis() + 60_000) } // hold the jobs
        queue("A")
        queue("B")
        awaitUntil("queued") { prompts() == listOf("A", "B") }
        onMain { ForgeQueueManager.pauseByUser() }
        assertTrue(vm.isQueuePaused.value)
        onMain { vm.startScheduledQueueNow() }

        val cleared = onMain { vm.clearQueue() }
        assertNotNull(cleared)
        assertFalse("nothing left to hold back", vm.isQueuePaused.value)
        assertNull(vm.queuePauseReason.value)
        assertEquals(ForgeQueueManager.USER_PAUSED_REASON, cleared!!.liftedPause)

        // Undo: the jobs and the pause are back, nothing is sent.
        val before = calls()
        onMain { vm.restoreJobs(cleared) }
        assertEquals(listOf("A", "B"), prompts())
        assertTrue(vm.isQueuePaused.value)
        assertEquals(ForgeQueueManager.USER_PAUSED_REASON, vm.queuePauseReason.value)
        Thread.sleep(1_500)
        assertEquals(before, calls())

        // Clear again, then a new job: it runs without "Resume" (it used to wait under the old reason).
        onMain { vm.clearQueue() }
        queue("C")
        // queueGeneration adds the job on its own dispatcher: an empty queue right after Clear is not "C done".
        awaitUntil("C sent", 20_000) { calls() == before + 1 }
        awaitUntil("C done", 20_000) { vm.generationQueue.value.isEmpty() && !vm.isGenerating.value }
        assertEquals(before + 1, calls())
        assertFalse(vm.isQueuePaused.value)
    }

    @Test fun `04 removing the last waiting job ends the pause, a job left keeps it`() {
        onMain { ForgeQueueManager.scheduleStart(System.currentTimeMillis() + 60_000) }
        queue("D")
        queue("E")
        awaitUntil("queued") { prompts() == listOf("D", "E") }
        onMain { ForgeQueueManager.pauseByUser() }
        onMain { vm.startScheduledQueueNow() }
        val d = vm.generationQueue.value.first { it.positivePrompt == "D" }
        val e = vm.generationQueue.value.first { it.positivePrompt == "E" }

        val first = onMain { vm.removeFromQueue(d.id) }
        assertNull("E still waits: the pause stays", first!!.liftedPause)
        assertTrue(vm.isQueuePaused.value)

        val last = onMain { vm.removeFromQueue(e.id) }
        assertEquals(ForgeQueueManager.USER_PAUSED_REASON, last!!.liftedPause)
        assertFalse(vm.isQueuePaused.value)

        onMain { vm.restoreJobs(last) }
        assertEquals(listOf("E"), prompts())
        assertTrue("Undo brings the pause back", vm.isQueuePaused.value)
        onMain { vm.resumeQueue() }
        awaitUntil("E done", 20_000) { vm.generationQueue.value.isEmpty() && !vm.isGenerating.value }
    }
}
