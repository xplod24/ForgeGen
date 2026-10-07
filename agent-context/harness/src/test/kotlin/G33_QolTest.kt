package com.example.forgegen

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters

/** 2.0.0 quality-of-life: undo, duplicate, drag, pause from the tile, backup, size swap. */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class G33_QolTest {
    companion object {
        val vm get() = TestApp.vm

        @BeforeClass @JvmStatic fun init() {
            TestApp.start(seed = { wildcardRows["color"] = WildcardEntity("color", "red\nblue") })
        }

        fun prompts() = vm.generationQueue.value.map { it.positivePrompt }

        fun queue(vararg prompts: String) {
            for (p in prompts) {
                onMain { vm.updateState { it.copy(positivePrompt = p, batchCount = 1, seed = 42) } }
                onMain { vm.queueGeneration() }
            }
            awaitUntil("queued") { prompts.all { p -> vm.generationQueue.value.any { it.positivePrompt == p } } }
        }

        fun hold() = onMain { ForgeQueueManager.scheduleStart(System.currentTimeMillis() + 3_600_000) } // nothing is sent

        fun release() = onMain { vm.startScheduledQueueNow() }
    }

    @Test fun `01 undo puts a removed job and a cleared queue back in place`() {
        hold()
        queue("A", "B", "C")
        val removed = onMain { vm.removeFromQueue(vm.generationQueue.value.first { it.positivePrompt == "B" }.id) }
        assertEquals(listOf("A", "C"), prompts())
        onMain { vm.restoreJobs(removed!!) }
        assertEquals(listOf("A", "B", "C"), prompts())
        assertEquals(3, vm.totalQueueSize.value)
        val cleared = onMain { vm.clearQueue() }
        assertTrue(prompts().isEmpty())
        assertEquals(3, cleared!!.jobs.size)
        onMain { vm.restoreJobs(cleared) }
        onMain { vm.restoreJobs(cleared) } // twice: nothing doubles
        assertEquals(listOf("A", "B", "C"), prompts())
        assertEquals(3, vm.totalQueueSize.value)
        assertNull("nothing to remove", onMain { vm.removeFromQueue("no such job") })
    }

    @Test fun `02 a duplicate goes after the jobs to run, with the same or a new seed`() {
        val a = vm.generationQueue.value.first { it.positivePrompt == "A" }
        // A failed job set aside at the end stays last.
        onMain {
            ForgeQueueManager.restoreJobs(
                ForgeQueueManager.RemovedJobs(listOf(IndexedValue(3, a.copy(id = "failed", status = GenerationStatus.FAILED, error = "x")))),
            )
        }
        onMain { vm.duplicateJob(a.id, newSeed = false) }
        onMain { vm.duplicateJob(a.id, newSeed = true) }
        val q = vm.generationQueue.value
        println("[G33-02] ${q.map { it.positivePrompt + "/" + it.status + "/" + it.payload.seed }}")
        assertEquals(listOf("A", "B", "C", "A", "A", "A"), q.map { it.positivePrompt })
        assertEquals(GenerationStatus.FAILED, q.last().status)
        assertEquals(42L, q[3].payload.seed)
        assertEquals(-1L, q[4].payload.seed)
        assertTrue(q.map { it.id }.toSet().size == q.size)
        assertEquals(5, vm.totalQueueSize.value)
        onMain { vm.removeFailedJobs() }
    }

    @Test fun `03 dragging moves a job, never above the running one`() {
        val c = vm.generationQueue.value.first { it.positivePrompt == "C" }
        onMain { vm.moveQueueItem(c.id, 0) }
        assertEquals("C", prompts().first())
        onMain { vm.moveQueueItem(c.id, 99) }
        assertEquals("C", prompts().last())
        onMain { vm.clearQueue() }
        // A running job stays first.
        TestApp.forge.generationMs = 3_000
        release()
        queue("running", "second", "third")
        awaitUntil("running", 10_000) { vm.generationQueue.value.firstOrNull()?.status == GenerationStatus.GENERATING }
        val third = vm.generationQueue.value.first { it.positivePrompt == "third" }
        onMain { vm.moveQueueItem(third.id, 0) }
        assertEquals(listOf("running", "third", "second"), prompts())
        val running = vm.generationQueue.value.first()
        onMain { vm.moveQueueItem(running.id, 2) }
        assertEquals("the running job does not move", "running", prompts().first())
    }

    @Test fun `04 the tile pauses the queue after the running job and resumes it`() {
        onMain { ForgeQueueManager.pauseByUser() }
        assertEquals(ForgeQueueManager.USER_PAUSED_REASON, vm.queuePauseReason.value)
        awaitUntil("running job done", 15_000) { vm.generationQueue.value.none { it.status == GenerationStatus.GENERATING } }
        Thread.sleep(1_500)
        assertEquals("the rest waits", listOf("third", "second"), prompts())
        assertFalse(vm.isQueueActive.value)
        TestApp.forge.generationMs = 300
        onMain { vm.resumeQueue() }
        awaitUntil("all done", 20_000) { vm.generationQueue.value.isEmpty() }
    }

    @Test fun `05 a backup brings back the settings, presets, profiles and wildcards`() {
        val config =
            vm.config.value.copy(
                timeout = 33,
                presets = listOf(GenerationPreset("portrait", AppState(width = 512, height = 768))),
                serverProfiles = listOf(ServerProfile("Home", TestApp.forge.url), ServerProfile("Work", "http://10.0.0.2:7860")),
            )
        val json = Backup.write(config, listOf(WildcardEntity("animal", "cat\ndog")), "v2.0.0")
        val back = Backup.read(json)!!
        assertEquals(config, back.config)
        assertEquals(listOf(WildcardEntity("animal", "cat\ndog")), back.wildcards)
        assertNull(Backup.read("not json"))
        assertNull(Backup.read("""{"app": "Other", "config": {}}"""))
        // An older backup without newer fields gets their defaults.
        val old = Backup.read("""{"app": "ForgeGen", "format": 1, "config": {"timeout": 20, "apiUrl": "http://x:1"}}""")!!
        assertEquals(20, old.config.timeout)
        assertTrue(old.config.vibrateOnFinish)

        // Through the app: exported to a picked file, imported from one.
        val out = Uri("content://docs/backup.json")
        onMain { vm.exportBackup(out) }
        awaitUntil("exported") { TestApp.app.resolver.documents[out.toString()]?.length()?.let { it > 0 } == true }
        val exported = Backup.read(TestApp.app.resolver.documents[out.toString()]!!.readText())
        assertNotNull(exported)
        assertEquals(vm.config.value.apiUrl, exported!!.config.apiUrl)
        assertTrue(exported.wildcards.any { it.name == "color" })

        val input = Uri("content://docs/import.json")
        TestApp.app.resolver.inputs[input] = json.toByteArray()
        val keptDate = vm.config.value.lastUpdateCheckDate
        onMain { vm.importBackup(input) }
        awaitUntil("imported") { vm.config.value.timeout == 33 && vm.wildcards.value.any { it.name == "animal" } }
        assertEquals(listOf("portrait"), vm.config.value.presets.map { it.name })
        assertEquals(listOf("Home", "Work"), vm.config.value.serverProfiles.map { it.name })
        assertTrue("wildcards are added", vm.wildcards.value.any { it.name == "color" })
        assertEquals(keptDate, vm.config.value.lastUpdateCheckDate)
    }

    @Test fun `06 swapping width and height swaps the aspect ratio too`() {
        assertEquals(AppState(width = 512, height = 768, aspectRatio = "3:4"), AppState(width = 768, height = 512, aspectRatio = "4:3").withSwappedSize())
        assertEquals("Custom", AppState(width = 640, height = 512, aspectRatio = "Custom").withSwappedSize().aspectRatio)
        assertEquals("1:1", AppState(aspectRatio = "1:1").withSwappedSize().aspectRatio)
    }
}
