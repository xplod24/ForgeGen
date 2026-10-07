package com.example.forgegen

import com.sun.net.httpserver.HttpExchange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** txt2img and progress under test control: answers, dropped connections, hanging requests, outages. */
object QueueServer {
    enum class Mode { OK, DROP, HANG }

    @Volatile var mode = Mode.OK
    @Volatile var generationMs = 400L
    @Volatile var progressDown = false
    val prompts = CopyOnWriteArrayList<String>()
    private val active = AtomicInteger(0)
    @Volatile var maxActive = 0
    private val hangRound = AtomicInteger(0)

    fun reset() {
        hangRound.incrementAndGet() // lets hanging requests of the previous test end
        mode = Mode.OK; generationMs = 400; progressDown = false; prompts.clear(); maxActive = 0
    }

    fun route(ex: HttpExchange, path: String, body: String): Boolean {
        when (path) {
            "/sdapi/v1/progress", "/sdapi/v1/memory" -> {
                if (!progressDown) return false
                ex.close()
                return true
            }
            "/sdapi/v1/txt2img" -> {
                prompts += org.json.JSONObject(body).getString("prompt")
                when (mode) {
                    Mode.DROP -> ex.close()
                    Mode.HANG -> {
                        // Like a socket that died with the phone's Wi-Fi: the server is idle and no answer or error ever comes.
                        val round = hangRound.get()
                        while (hangRound.get() == round) Thread.sleep(100)
                        ex.close()
                    }
                    Mode.OK -> {
                        val now = active.incrementAndGet()
                        maxActive = maxOf(maxActive, now)
                        TestApp.forge.generating = true
                        try { Thread.sleep(generationMs) } finally {
                            TestApp.forge.generating = false
                            active.decrementAndGet()
                        }
                        val b64 = java.util.Base64.getEncoder().encodeToString(Png.withParameters(INFOTEXT))
                        val bytes = """{"images":["$b64"],"parameters":{},"info":"{}"}""".toByteArray()
                        ex.responseHeaders.add("Content-Type", "application/json")
                        ex.sendResponseHeaders(200, bytes.size.toLong())
                        ex.responseBody.use { it.write(bytes) }
                    }
                }
                return true
            }
        }
        return false
    }
}

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class G19_QueueTest {
    companion object {
        val vm get() = TestApp.vm

        @BeforeClass @JvmStatic fun init() {
            TestApp.start()
            TestApp.forge.custom = { ex, path, body -> QueueServer.route(ex, path, body) }
        }

        fun queue(prompt: String) {
            onMain { vm.updateState { it.copy(positivePrompt = prompt, batchCount = 1) } }
            onMain { vm.queueGeneration() }
            awaitUntil("queued $prompt") { vm.generationQueue.value.any { it.positivePrompt == prompt } || prompt in QueueServer.prompts }
        }

        fun done() = vm.generationQueue.value.isEmpty() && !vm.isGenerating.value
    }

    @Before fun reset() {
        QueueServer.reset()
        onMain { vm.clearQueue() }
        onMain { vm.saveConfig(vm.config.value.copy(overnightMode = false)) }
        awaitUntil("idle", 70_000) { !vm.isGenerating.value }
        onMain { vm.clearQueue() }
        onMain { vm.resumeQueue() }
        awaitUntil("connected", 70_000) { vm.isConnected.value }
    }

    @Test fun `01 jobs are sent one at a time, in order`() {
        listOf("p1", "p2", "p3", "p4").forEach { queue(it) }
        awaitUntil("all done", 20_000) { done() }
        println("[G19-01] order=${QueueServer.prompts} maxActive=${QueueServer.maxActive}")
        assertEquals(listOf("p1", "p2", "p3", "p4"), QueueServer.prompts.toList())
        assertEquals(1, QueueServer.maxActive)
    }

    @Test fun `02 overnight mode does not throw the queue away when the connection drops`() {
        onMain { vm.saveConfig(vm.config.value.copy(overnightMode = true)) }
        QueueServer.mode = QueueServer.Mode.DROP
        listOf("a", "b", "c").forEach { queue(it) }
        Thread.sleep(3000)
        println("[G19-02] queue=${vm.generationQueue.value.map { it.positivePrompt }} sent=${QueueServer.prompts} paused=${vm.isQueuePaused.value} reason=${vm.queuePauseReason.value}")
        assertEquals("jobs kept", listOf("a", "b", "c"), vm.generationQueue.value.map { it.positivePrompt })
        assertEquals("only the first job was sent, once", listOf("a"), QueueServer.prompts.toList())
        assertTrue(vm.isQueuePaused.value)
    }

    @Test fun `03 a dropped connection keeps the job and the queue continues by itself`() {
        QueueServer.mode = QueueServer.Mode.DROP
        val images = vm.sessionImages.value.size
        queue("d")
        awaitUntil("paused", 10_000) { vm.isQueuePaused.value }
        println("[G19-03] after drop: queue=${vm.generationQueue.value.map { "${it.positivePrompt}/${it.status}" }} reason=${vm.queuePauseReason.value}")
        assertEquals("job kept", listOf("d"), vm.generationQueue.value.map { it.positivePrompt })
        QueueServer.mode = QueueServer.Mode.OK
        awaitUntil("resumed and done", 30_000) { done() && vm.sessionImages.value.size > images }
        assertFalse(vm.isQueuePaused.value)
    }

    @Test fun `04 automatic retries stop after three`() {
        QueueServer.mode = QueueServer.Mode.DROP
        queue("e")
        Thread.sleep(35_000)
        println("[G19-04] attempts=${QueueServer.prompts.size} queue=${vm.generationQueue.value.map { it.positivePrompt }} reason=${vm.queuePauseReason.value}")
        assertEquals("first try and three retries", 4, QueueServer.prompts.count { it == "e" })
        assertTrue(vm.isQueuePaused.value)
        assertEquals(listOf("e"), vm.generationQueue.value.map { it.positivePrompt })
    }

    @Test fun `05 nothing is sent while the server is unreachable`() {
        QueueServer.progressDown = true
        awaitUntil("disconnected", 10_000) { !vm.isConnected.value }
        queue("f")
        Thread.sleep(2000)
        assertTrue("sent while offline: ${QueueServer.prompts}", QueueServer.prompts.isEmpty())
        QueueServer.progressDown = false
        awaitUntil("sent after reconnecting", 40_000) { done() && "f" in QueueServer.prompts }
    }

    @Test fun `06 the status says the connection is lost while a job waits for the server`() {
        QueueServer.generationMs = 7000
        queue("g")
        awaitUntil("generating") { "g" in QueueServer.prompts }
        Thread.sleep(500)
        QueueServer.progressDown = true
        awaitUntil("status shows the outage", 6_000) { ForgeQueueManager.statusText.value.contains("Connection lost", ignoreCase = true) }
        QueueServer.progressDown = false
        awaitUntil("job still completes", 30_000) { done() }
        assertFalse(vm.isQueuePaused.value)
    }

    @Test fun `07 a request orphaned by an outage is sent again`() {
        QueueServer.mode = QueueServer.Mode.HANG
        val images = vm.sessionImages.value.size
        queue("h")
        awaitUntil("sent") { "h" in QueueServer.prompts }
        QueueServer.mode = QueueServer.Mode.OK
        QueueServer.progressDown = true
        awaitUntil("outage seen", 6_000) { !vm.isConnected.value }
        QueueServer.progressDown = false
        awaitUntil("sent again and done", 60_000) { done() && vm.sessionImages.value.size > images }
        println("[G19-07] attempts=${QueueServer.prompts}")
        assertEquals(2, QueueServer.prompts.count { it == "h" })
    }

    @Test fun `08 the running job can be neither removed nor overtaken`() {
        QueueServer.generationMs = 3000
        queue("i1")
        queue("i2")
        awaitUntil("running") { vm.isGenerating.value && "i1" in QueueServer.prompts }
        val running = vm.generationQueue.value.first()
        onMain { vm.removeFromQueue(running.id) }
        onMain { vm.moveQueueItemUp(vm.generationQueue.value[1].id) }
        assertEquals(listOf("i1", "i2"), vm.generationQueue.value.map { it.positivePrompt })
        assertEquals(GenerationStatus.GENERATING, vm.generationQueue.value.first().status)
        awaitUntil("done", 20_000) { done() }
    }
}
