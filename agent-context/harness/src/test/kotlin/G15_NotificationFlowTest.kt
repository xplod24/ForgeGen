package com.example.forgegen

import android.app.Notification
import androidx.core.app.NotificationManagerCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import kotlinx.coroutines.runBlocking

class G15_NotificationFlowTest {
    companion object {
        @BeforeClass @JvmStatic fun init() {
            TestApp.start()
        }
    }

    private val vm get() = TestApp.vm

    private fun posted(): List<Pair<Int, Notification>> =
        synchronized(NotificationManagerCompat.posted) { NotificationManagerCompat.posted.map { (it[0] as Int) to (it[1] as Notification) } }

    private fun titles() = posted().map { it.second.title.toString() }

    // The service follows isQueueActive by itself (1.1.5; it used to be told with ACTION_QUEUE_FINISHED).
    private fun queueStopped() = !ForgeQueueManager.isQueueActive.value

    private fun runJobs(vararg prompts: String) {
        for (p in prompts) {
            onMain { vm.updateState { it.copy(positivePrompt = p) } }
            onMain { vm.queueGeneration() }
        }
    }

    @Before fun reset() {
        onMain { vm.resumeQueue() }
        onMain { vm.clearQueue() }
        awaitUntil("brak aktywnego zadania") { !vm.isGenerating.value }
        TestApp.forge.txt2imgStatus = 200
        TestApp.forge.txt2imgBody = null
        TestApp.forge.generationMs = 300
        onMain { vm.saveConfig(vm.config.value.copy(notifOnBatchFinish = true, notifOnQueueFinish = true, overnightMode = false)) }
        NotificationManagerCompat.posted.clear()
        NotificationManagerCompat.cancelled.clear()
    }

    @Test fun `jedno udane zadanie daje jedno powiadomienie o koncu kolejki`() {
        runJobs("single")
        awaitUntil("zadanie skończone") { vm.generationQueue.value.isEmpty() && !vm.isGenerating.value && titles().isNotEmpty() }
        Thread.sleep(300)
        println("[G15] single: ${titles()}")
        assertEquals(listOf("Queue Completed"), titles())
        assertEquals(ForgeNotifications.CHANNEL_RESULTS, posted().single().second.channelId)
        awaitUntil("kolejka zatrzymana") { queueStopped() }
    }

    @Test fun `dwa zadania daja batch i koniec kolejki`() {
        runJobs("first", "second")
        awaitUntil("oba zadania") { vm.generationQueue.value.isEmpty() && !vm.isGenerating.value && titles().size >= 2 }
        Thread.sleep(300)
        println("[G15] two: ${titles()}")
        assertEquals(listOf("Batch Completed", "Queue Completed"), titles())
    }

    @Test fun `blad ostatniego zadania to alert o bledzie, a nie ukonczona kolejka`() {
        TestApp.forge.txt2imgStatus = 500
        TestApp.forge.txt2imgBody = """{"error":"RuntimeError","errors":"Sampler 'Foo' not found"}"""
        runJobs("broken")
        awaitUntil("zadanie z błędem") { vm.generationQueue.value.isEmpty() && !vm.isGenerating.value && titles().isNotEmpty() }
        Thread.sleep(300)
        println("[G15] failed last: ${posted().map { it.first to it.second.title }}")
        assertEquals(listOf("Generation failed"), titles())
        val (id, n) = posted().single()
        assertEquals(ForgeNotifications.ID_QUEUE_PAUSED, id)
        assertEquals(ForgeNotifications.CHANNEL_ALERTS, n.channelId)
        awaitUntil("kolejka zatrzymana") { queueStopped() }
    }

    @Test fun `blad z kolejnymi zadaniami wstrzymuje kolejke, zatrzymuje postep serwisu, wznowienie kasuje alert`() {
        TestApp.forge.txt2imgStatus = 500
        TestApp.forge.txt2imgBody = """{"error":"RuntimeError","errors":"boom"}"""
        TestApp.forge.generationMs = 600
        runJobs("a", "b")
        awaitUntil("kolejka wstrzymana") { vm.isQueuePaused.value && !vm.isGenerating.value && titles().isNotEmpty() }
        Thread.sleep(300)
        println("[G15] paused: ${titles()} text=${posted().first().second.text}")
        assertEquals(listOf("Queue paused"), titles())
        assertTrue(posted().first().second.text.toString().contains("resume"))
        awaitUntil("serwis ma przestać pokazywać postęp") { queueStopped() }

        TestApp.forge.txt2imgStatus = 200
        TestApp.forge.txt2imgBody = null
        onMain { vm.resumeQueue() }
        assertTrue(NotificationManagerCompat.cancelled.contains(ForgeNotifications.ID_QUEUE_PAUSED))
        awaitUntil("drugie zadanie po wznowieniu") { vm.generationQueue.value.isEmpty() && !vm.isGenerating.value }
    }

    @Test fun `brak pamieci na serwerze ma wlasny alert na kanale bledow`() {
        TestApp.forge.txt2imgStatus = 500
        TestApp.forge.txt2imgBody = """{"error":"OutOfMemoryError","errors":"CUDA out of memory"}"""
        TestApp.forge.generationMs = 600
        runJobs("big", "bigger")
        awaitUntil("OOM") { vm.oomAlert.value && !vm.isGenerating.value && titles().isNotEmpty() }
        Thread.sleep(300)
        println("[G15] oom: ${posted().map { it.second.title to it.second.text }}")
        assertEquals(listOf("Server out of memory"), titles())
        assertEquals(ForgeNotifications.CHANNEL_ALERTS, posted().single().second.channelId)
    }

    @Test fun `wylaczone powiadomienia o koncu nic nie wysylaja`() {
        onMain { vm.saveConfig(vm.config.value.copy(notifOnBatchFinish = false, notifOnQueueFinish = false)) }
        NotificationManagerCompat.posted.clear()
        runJobs("quiet")
        awaitUntil("zadanie") { vm.generationQueue.value.isEmpty() && !vm.isGenerating.value && vm.sessionImages.value.isNotEmpty() }
        Thread.sleep(500)
        assertFalse(titles().any { it.contains("Completed") })
    }

    @Test fun `nowy ViewModel po zamknieciu aktywnosci laduje listy modeli`() {
        val second = onMain { ForgeViewModel(TestApp.app) }
        runBlocking { second.initializeApp() }
        awaitUntil("modele w nowym ViewModelu") { second.models.value.isNotEmpty() && second.samplers.value.isNotEmpty() }
    }
}
