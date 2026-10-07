package com.example.forgegen

import android.app.Notification
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** 1.6.x: no content modes, no Civitai, no prompt check in the app; the server's HTTP 403; privacy options. */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class G28_PrivacyAndForbiddenTest {
    companion object {
        val vm get() = TestApp.vm
        val toasts = CopyOnWriteArrayList<String>()
        private fun json(ex: com.sun.net.httpserver.HttpExchange, body: String): Boolean {
            val b = body.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
            return true
        }

        @BeforeClass @JvmStatic fun init() {
            TestApp.start(
                custom = { ex, path, body ->
                    if (path == "/sdapi/v1/txt2img" && body.contains("forbidden subject")) {
                        // The server's prompt-checking extension refuses this prompt.
                        val b = """{"detail":"Refused by the server's extension"}""".toByteArray()
                        ex.responseHeaders.add("Content-Type", "application/json")
                        ex.sendResponseHeaders(403, b.size.toLong()); ex.responseBody.use { it.write(b) }
                        true
                    } else if (path == "/customapi/v1/all-models-hashes") {
                        json(ex, """{"models":[{"type":"checkpoint","name":"model","filename":"model.safetensors","sha256":"m1"},
                            {"type":"lora","name":"celeb","filename":"celeb.safetensors","sha256":"l1"},
                            {"type":"lora","name":"old","filename":"old.safetensors","sha256":"l2"},
                            {"type":"lora","name":"kid","filename":"kid.safetensors","sha256":"l3"}]}""")
                    } else {
                        false
                    }
                },
                seed = {
                    wildcardRows["outfit"] = WildcardEntity("outfit", "nude")
                },
            )
            CoroutineScope(Dispatchers.IO).launch { vm.toastMessage.collect { toasts += it } }
        }

        fun calls() = TestApp.forge.calls("/sdapi/v1/txt2img").size
        fun idle() = awaitUntil("idle", 20_000) { vm.generationQueue.value.none { it.status != GenerationStatus.FAILED } && !vm.isGenerating.value }

        /** Queues [prompt]; true when it reached the server. */
        fun send(prompt: String): Boolean {
            val before = calls()
            onMain { vm.updateState { it.copy(positivePrompt = prompt, batchCount = 1) } }
            onMain { vm.queueGeneration() }
            // A refused job stays in the queue as FAILED, a sent one reaches the server.
            awaitUntil("queued") { vm.generationQueue.value.any { it.positivePrompt == prompt } || calls() > before }
            idle()
            return calls() > before
        }

        fun failed() = vm.generationQueue.value.filter { it.status == GenerationStatus.FAILED }
    }

    @Before fun reset() {
        TestApp.forge.txt2imgStatus = 200
        TestApp.forge.txt2imgBody = null
        TestApp.forge.generationMs = 300
        onMain { vm.removeFailedJobs() }
        onMain { vm.saveConfig(vm.config.value.copy(notifOnBatchFinish = false)) }
        toasts.clear()
    }

    @Test fun `01 adult prompts and wildcards reach the server, the app no longer judges them`() {
        assertTrue(send("1girl, nude, beach"))
        assertTrue("a wildcard filled with an adult word", send("1girl, __outfit__"))
        assertTrue(send("knight, battle, gore"))
        assertTrue(failed().isEmpty())
    }

    @Test fun `02 every prompt is sent as written, only the server's 403 sets a job aside`() {
        val before = calls()
        onMain { vm.updateState { it.copy(positivePrompt = "forbidden subject, nude", batchCount = 1) } }
        onMain { vm.queueGeneration() }
        onMain { vm.updateState { it.copy(positivePrompt = "castle at dusk", batchCount = 1) } }
        onMain { vm.queueGeneration() }
        awaitUntil("both sent") { calls() >= before + 2 }
        idle()
        println("[G28-02] failed=${failed().map { it.error }} toasts=${toasts.toList()}")
        assertEquals("both reached the server", before + 2, calls())
        val prompts = TestApp.forge.txt2imgPayloads().takeLast(2).map { it.getString("prompt") }
        assertEquals(listOf("forbidden subject, nude", "castle at dusk"), prompts)
        val job = failed().single()
        assertEquals("forbidden subject, nude", job.positivePrompt)
        assertEquals("${ForgeQueueManager.PROMPT_REFUSED} Refused by the server's extension", job.error)
        assertTrue(toasts.any { it.startsWith(ForgeQueueManager.PROMPT_REFUSED) })
        assertFalse("not a pause", vm.isQueuePaused.value)
    }

    @Test fun `03 a server's 403 fails only that job, with the server's reason`() {
        TestApp.forge.txt2imgStatus = 403
        TestApp.forge.txt2imgBody = """{"detail":"Banned word: dragon"}"""
        val before = calls()
        assertTrue("sent, the server refused it", send("red dragon") || calls() > before)
        val job = failed().single()
        println("[G28-03] ${job.error} | ${toasts.toList()}")
        assertEquals("${ForgeQueueManager.PROMPT_REFUSED} Banned word: dragon", job.error)
        assertTrue(toasts.contains("${ForgeQueueManager.PROMPT_REFUSED} Banned word: dragon"))
        assertFalse(vm.isQueuePaused.value)
        TestApp.forge.txt2imgStatus = 200
        TestApp.forge.txt2imgBody = null
        assertTrue("the next job runs", send("castle at dusk"))
        assertEquals("a 403 without a reason", null, ForgeQueueManager.serverDetail("Forbidden"))
    }

    @Test fun `04 a real person's LoRA with adult content is the server's business too`() {
        assertTrue(send("<lora:celeb:1>, nude"))
        assertTrue(failed().isEmpty())
    }

    @Test fun `05 the lists come from the server alone, and picking a LoRA adds it to the prompt once`() {
        awaitUntil("lists", 20_000) { vm.availableLoras.value.size == 3 }
        println("[G28-05] ${vm.models.value} ${vm.availableLoras.value}")
        assertEquals("model.safetensors", vm.models.value.single().path)
        assertEquals("the server's own preview", "celeb.safetensors", vm.availableLoras.value.first { it.name == "celeb" }.path)
        onMain { vm.updateState { it.copy(positivePrompt = "castle") } }
        onMain { vm.addLora("celeb") }
        onMain { vm.addLora("celeb") }
        assertEquals("castle, <lora:celeb:1.0>", vm.appState.value.positivePrompt)
    }

    @Test fun `06 finished-batch notifications hide the prompt, and never show it on the lock screen`() {
        onMain { vm.saveConfig(vm.config.value.copy(notifOnBatchFinish = true, notifOnQueueFinish = true, hidePromptsInNotifications = true)) }
        fun batchNotes() = synchronized(NotificationManagerCompat.posted) {
            NotificationManagerCompat.posted.map { it[1] as Notification }.filter { it.title == "Batch Completed" }
        }
        NotificationManagerCompat.posted.clear()
        TestApp.forge.generationMs = 800
        onMain { vm.updateState { it.copy(positivePrompt = "secret castle", batchCount = 1) } }
        onMain { vm.queueGeneration() }
        onMain { vm.queueGeneration() }
        awaitUntil("batch note", 20_000) { batchNotes().isNotEmpty() }
        val hidden = batchNotes().first()
        assertEquals("A batch has finished.", hidden.text.toString())
        awaitUntil("idle", 20_000) { vm.generationQueue.value.isEmpty() && !vm.isGenerating.value }

        onMain { vm.saveConfig(vm.config.value.copy(hidePromptsInNotifications = false)) }
        NotificationManagerCompat.posted.clear()
        onMain { vm.queueGeneration() }
        onMain { vm.queueGeneration() }
        awaitUntil("batch note", 20_000) { batchNotes().isNotEmpty() }
        val shown = batchNotes().first()
        println("[G28-06] ${hidden.text} | ${shown.text} | public=${shown.publicVersion?.text}")
        assertTrue(shown.text.toString().startsWith("Finished: secret castle"))
        assertEquals("the lock screen version has no prompt", "A batch has finished.", shown.publicVersion?.text.toString())
        awaitUntil("idle", 20_000) { vm.generationQueue.value.isEmpty() && !vm.isGenerating.value }
    }

    @Test fun `07 saving privately keeps images out of the phone's gallery`() {
        onMain { vm.saveConfig(vm.config.value.copy(savePrivately = true)) }
        val rows = TestApp.app.resolver.rows.size
        val file = File(TestApp.app.cacheDir, "gen_test.png").apply { writeBytes(Png.withParameters(INFOTEXT)) }
        toasts.clear()
        vm.downloadSessionImage(file.absolutePath)
        awaitUntil("saved") { toasts.any { it.startsWith("Saved to") } }
        val dir = File(TestApp.app.getExternalFilesDir("Pictures"), "ForgeGen")
        println("[G28-07] ${toasts.toList()} ${dir.list()?.toList()}")
        assertTrue(toasts.contains("Saved to the app's private folder"))
        assertEquals("nothing in MediaStore", rows, TestApp.app.resolver.rows.size)
        assertEquals(1, dir.list()!!.count { it.startsWith("Gen_") && it.endsWith(".png") })
        assertEquals(dir.list()!!.toSet(), DeviceImages.savedNames(TestApp.app))
        onMain { vm.saveConfig(vm.config.value.copy(savePrivately = false)) }
    }

    @Test fun `08 shared copies can leave without the generation data`() {
        val png = Png.withParameters(INFOTEXT)
        onMain { vm.saveConfig(vm.config.value.copy(shareWithoutMetadata = true)) }
        DeviceImages.shareIntent(TestApp.app, "stripped.png") { it.write(png) }
        val stripped = File(File(TestApp.app.cacheDir, "shared"), "stripped.png").readBytes()
        onMain { vm.saveConfig(vm.config.value.copy(shareWithoutMetadata = false)) }
        DeviceImages.shareIntent(TestApp.app, "full.png") { it.write(png) }
        val full = File(File(TestApp.app.cacheDir, "shared"), "full.png").readBytes()
        assertEquals(INFOTEXT, PngMetadata.readParameters(full.inputStream()))
        assertEquals("", PngMetadata.readParameters(stripped.inputStream()))
        assertTrue(stripped.size < full.size)
    }
}
