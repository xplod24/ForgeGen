package com.example.forgegen

import android.app.Notification
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.util.concurrent.CopyOnWriteArrayList

/** 1.1.5: overnight mode sets failed jobs aside and reports them; the service and its wake lock follow the queue. */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class G26_OvernightTest {
    companion object {
        val vm get() = TestApp.vm
        val sent = CopyOnWriteArrayList<String>()
        @Volatile var drop = false
        @Volatile var slowMs = 0L
        var cachedBeforeLoad: String? = null

        @BeforeClass @JvmStatic fun init() {
            // The theme chosen last time is applied before the settings are loaded.
            android.content.Context.PREFS.getOrPut("ui") { java.util.concurrent.ConcurrentHashMap() }["theme_mode"] = THEME_DARK
            ForgeSettingsManager.applyCachedThemeMode(object : android.content.ContextWrapper() {})
            cachedBeforeLoad = ForgeSettingsManager.config.value.themeMode
            TestApp.start(
                custom = { ex, path, body ->
                    if (path != "/sdapi/v1/txt2img") {
                        false
                    } else {
                        val prompt = org.json.JSONObject(body).getString("prompt")
                        sent += prompt
                        when {
                            drop -> { ex.close(); true }
                            "bad" in prompt -> {
                                val b = """{"error":"RuntimeError","errors":"Sampler not found"}""".toByteArray()
                                ex.sendResponseHeaders(500, b.size.toLong()); ex.responseBody.use { it.write(b) }; true
                            }
                            else -> { if (slowMs > 0) Thread.sleep(slowMs); false }
                        }
                    }
                },
            )
            android.content.ContextWrapper.base = TestApp.app
        }

        fun queue(vararg prompts: String) = prompts.forEach { p ->
            onMain { vm.updateState { it.copy(positivePrompt = p, batchCount = 1) } }
            onMain { vm.queueGeneration() }
            awaitUntil("queued $p") { vm.generationQueue.value.any { it.positivePrompt == p } || p in sent }
        }

        fun posted(): List<Notification> = synchronized(NotificationManagerCompat.posted) { NotificationManagerCompat.posted.map { it[1] as Notification } }
        fun statuses() = vm.generationQueue.value.map { "${it.positivePrompt}/${it.status}" }
    }

    @Before fun reset() {
        drop = false; slowMs = 0
        onMain { vm.clearQueue() }
        awaitUntil("idle", 30_000) { !vm.isGenerating.value }
        onMain { vm.clearQueue(); vm.resumeQueue() }
        onMain { vm.saveConfig(vm.config.value.copy(overnightMode = true, notifOnQueueFinish = true, notifOnBatchFinish = false)) }
        awaitUntil("connected", 40_000) { vm.isConnected.value }
        sent.clear()
        NotificationManagerCompat.posted.clear()
    }

    @Test fun `01 a failed job is set aside, the queue goes on and ends with a summary`() {
        queue("good1", "bad1", "good2")
        awaitUntil("done", 20_000) { vm.generationQueue.value.none { it.status != GenerationStatus.FAILED } && !vm.isGenerating.value }
        Thread.sleep(500)
        val titles = posted().map { it.title.toString() to it.text.toString() }
        println("[G26-01] queue=${statuses()} sent=$sent notifications=$titles")
        assertEquals(listOf("good1", "bad1", "good2"), sent.toList())
        assertEquals(listOf("bad1/FAILED"), statuses())
        assertEquals("The server returned HTTP 500. RuntimeError: Sampler not found", vm.generationQueue.value.single().error)
        assertEquals(listOf("Queue finished with errors" to "2 done, 1 failed. The failed jobs are kept in the queue."), titles)
        assertFalse(vm.isQueuePaused.value)
        assertFalse(ForgeQueueManager.isQueueActive.value)
        awaitUntil("saved") { TestApp.db.settings["saved_queue"].orEmpty().contains("\"FAILED\"") }
    }

    @Test fun `02 a retried job runs again`() {
        queue("bad2")
        awaitUntil("set aside") { statuses() == listOf("bad2/FAILED") }
        // The server works again: the same prompt with the "bad" marker removed is not possible, so edit it.
        val id = vm.generationQueue.value.single().id
        onMain { vm.updateQueueItem(id, "fixed2", "") }
        onMain { vm.retryFailed(id) }
        awaitUntil("done", 20_000) { vm.generationQueue.value.isEmpty() && !vm.isGenerating.value }
        println("[G26-02] sent=$sent")
        assertEquals(listOf("bad2", "fixed2"), sent.toList())
    }

    @Test fun `03 a failed job moved above waiting ones does not block the queue`() {
        queue("bad3")
        awaitUntil("set aside") { statuses() == listOf("bad3/FAILED") }
        slowMs = 1500
        queue("slow1", "next1")
        awaitUntil("slow1 running") { "slow1" in sent }
        val failedId = vm.generationQueue.value.first { it.status == GenerationStatus.FAILED }.id
        onMain { vm.moveQueueItemUp(failedId) }
        println("[G26-03] after move: ${statuses()}")
        awaitUntil("done", 20_000) { vm.generationQueue.value.none { it.status != GenerationStatus.FAILED } && !vm.isGenerating.value }
        println("[G26-03] end: ${statuses()} sent=$sent")
        assertEquals(listOf("bad3", "slow1", "next1"), sent.toList())
        assertEquals(listOf("bad3/FAILED"), statuses())
    }

    @Test fun `04 overnight mode keeps retrying a lost connection past three times`() {
        drop = true
        queue("net")
        awaitUntil("four attempts", 60_000) { sent.count { it == "net" } >= 4 }
        Thread.sleep(500)
        println("[G26-04] attempts=${sent.count { it == "net" }} reason=${vm.queuePauseReason.value}")
        assertEquals(ForgeQueueManager.CONNECTION_LOST_REASON, vm.queuePauseReason.value)
        assertTrue("still active while waiting for the server", ForgeQueueManager.isQueueActive.value)
        drop = false
        awaitUntil("done after the server is back", 60_000) { vm.generationQueue.value.isEmpty() && !vm.isGenerating.value }
    }

    @Test fun `05 the service holds the wake lock while the queue works and stops after it`() {
        slowMs = 1500
        val service = GenerationService()
        service.onCreate()
        queue("svc")
        awaitUntil("generating") { vm.isGenerating.value }
        service.onStartCommand(Intent(GenerationService.ACTION_START_GENERATION), 0, 1)
        val power = TestApp.app.power
        awaitUntil("wake lock held") { power.lastLock?.isHeld == true }
        assertFalse("not reference-counted, so renewing extends one lock", power.lastLock!!.referenceCounted)
        assertEquals(0, service.stops)
        awaitUntil("queue over", 20_000) { !ForgeQueueManager.isQueueActive.value }
        awaitUntil("service stopped itself", 5_000) { service.stops > 0 }
        assertFalse("wake lock released", power.lastLock!!.isHeld)
        println("[G26-05] acquires=${power.lastLock!!.acquires} stops=${service.stops}")
        service.onDestroy()

        val shell = GenerationService()
        shell.onCreate()
        assertEquals("a restart without the queue is not kept", android.app.Service.START_NOT_STICKY, shell.onStartCommand(null, 0, 7))
        assertEquals(1, shell.stops)
        shell.onDestroy()
    }

    @Test fun `06 the theme is kept where it can be read before the settings load`() {
        println("[G26-06] cachedBeforeLoad=$cachedBeforeLoad loaded=${vm.config.value.themeMode}")
        assertEquals(THEME_DARK, cachedBeforeLoad)
        assertEquals("the stored config says System", THEME_SYSTEM, vm.config.value.themeMode)
        assertEquals(THEME_SYSTEM, android.content.Context.PREFS["ui"]!!["theme_mode"])
        onMain { vm.saveConfig(vm.config.value.copy(themeMode = THEME_LIGHT)) }
        assertEquals(THEME_LIGHT, android.content.Context.PREFS["ui"]!!["theme_mode"])
        ForgeSettingsManager.applyCachedThemeMode(TestApp.app) // settings loaded: no effect
        assertEquals(THEME_LIGHT, vm.config.value.themeMode)
    }
}
