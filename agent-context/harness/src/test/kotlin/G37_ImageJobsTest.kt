package com.example.forgegen

import com.sun.net.httpserver.HttpExchange
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 2.4.0: "Upscale Selected" and "More Like This": gallery images read through IIB, remade as txt2img jobs, queued
 * with labels and sent to the server with the image's seed, settings and model.
 *
 * 2.4.1: the mock answers txt2img like Forge does: a hires fix without "hr_additional_modules" fails with HTTP 500
 * (TypeError in Forge's hires pass), and a failing job's pause reason carries the server's own error text.
 */
@OptIn(DelicateCoroutinesApi::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class G37_ImageJobsTest {
    companion object {
        val vm get() = TestApp.vm
        val toasts = CopyOnWriteArrayList<String>()
        private const val PARAMS = "Steps: 20, Sampler: Euler a, Schedule type: Karras, CFG scale: 7"

        fun item(name: String, info: String?): GalleryItem {
            val path = "/out/$name"
            if (info == null) MockIib.unreadable += path else MockIib.infos[path] = info
            return GalleryItem(name = name, fullpath = path, type = "file")
        }

        val plain = item("a.png", "a cat\nNegative prompt: ugly\n$PARAMS, Seed: 100, Size: 512x768, Model hash: abc123, Model: model")
        val hires = item("b.png", "a dog\n$PARAMS, Seed: 200, Size: 512x512, Model: other, Denoising strength: 0.5, Hires upscale: 2, Hires upscaler: Latent")
        val noData = item("c.png", "")
        val unreadable = item("d.png", null)
        val otherModel = item("e.png", "a fox\n$PARAMS, Seed: 300, Size: 512x512, Model hash: 9999999999, Model: sdxl_base")

        /** Forge's API error answer (modules/api/api.py, api_middleware's handle_exception). */
        fun forgeError(ex: HttpExchange, type: String, message: String): Boolean {
            val bytes = org.json.JSONObject()
                .put("error", type).put("detail", "").put("body", "").put("errors", message)
                .toString().toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(500, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
            return true
        }

        /** txt2img checked the way Forge's processing.py does; everything else as before. */
        fun forgeLike(ex: HttpExchange, path: String, body: String): Boolean {
            if (path == "/sdapi/v1/txt2img") {
                val p = org.json.JSONObject(body)
                if (p.optBoolean("enable_hr") && !p.has("hr_additional_modules")) {
                    return forgeError(ex, "TypeError", "argument of type 'NoneType' is not iterable")
                }
                if (p.optString("prompt") == "boom") return forgeError(ex, "RuntimeError", "Sizes of tensors must match")
                return false
            }
            return MockIib.route(ex, path, body)
        }

        @BeforeClass @JvmStatic fun init() {
            TestApp.start(custom = { ex, path, body -> forgeLike(ex, path, body) })
            GlobalScope.launch(Dispatchers.IO) { ForgeSettingsManager.snackbarMessage.collect { toasts += it } }
        }

        fun payloads() = TestApp.forge.txt2imgPayloads()

        fun read(kind: ImageJobs.Kind, items: List<GalleryItem>): List<ImageJobs.Source> {
            onMain { vm.requestImageJobs(kind, items) }
            awaitUntil("read", 10_000) { vm.imageJobs.value?.sources != null }
            return vm.imageJobs.value!!.sources!!
        }

        fun idle() = awaitUntil("queue done", 30_000) {
            (vm.generationQueue.value.isEmpty() || vm.isQueuePaused.value) && !vm.isGenerating.value
        }

        fun assertNotPaused(where: String) =
            assertFalse("$where: paused with '${vm.queuePauseReason.value}'", vm.isQueuePaused.value)
    }

    @Test fun `01 the selection is read in one request, and what cannot be made again says why`() {
        val before = TestApp.forge.calls("/infinite_image_browsing/image_geninfo_batch").size
        val sources = read(ImageJobs.Kind.UPSCALE, listOf(plain, hires, noData, unreadable, otherModel))
        assertEquals(before + 1, TestApp.forge.calls("/infinite_image_browsing/image_geninfo_batch").size)
        println("[G37-01] ${sources.map { it.javaClass.simpleName + (if (it is ImageJobs.Source.Skipped) ": " + it.reason else "") }}")
        assertTrue(sources[0] is ImageJobs.Source.Ready)
        assertEquals(2f, (sources[1] as ImageJobs.Source.Ready).hiresScale)
        assertEquals(ImageJobs.Source.Skipped(ImageJobs.NO_DATA), sources[2])
        assertEquals(ImageJobs.Source.Skipped(ImageJobs.UNREADABLE), sources[3])
        assertEquals(ImageJobs.Source.Skipped("the server has no model sdxl_base"), sources[4])
    }

    @Test fun `02 Upscale x2 queues one labelled job per image and sends the image again with hires fix`() {
        TestApp.forge.generationMs = 800
        val sent = payloads().size
        toasts.clear()
        onMain { vm.queueUpscales(2f, "Latent", 0.35f) }
        assertNull("the dialog closes", vm.imageJobs.value)
        awaitUntil("queued") { vm.generationQueue.value.isNotEmpty() }
        assertEquals(listOf("Upscale ×2"), vm.generationQueue.value.map { it.label })
        awaitUntil("toast") { toasts.isNotEmpty() }
        assertEquals("Added 1 job to the queue (4 left out)", toasts.last())
        awaitUntil("sent", 20_000) { payloads().size == sent + 1 }
        idle()
        val p = payloads().drop(sent).single()
        println("[G37-02] $p paused=${vm.isQueuePaused.value} reason=${vm.queuePauseReason.value}")
        assertNotPaused("Upscale")
        assertEquals(listOf("Use same choices"), p.getJSONArray("hr_additional_modules").toList())
        assertEquals(true, p.getBoolean("enable_hr"))
        assertEquals(2.0, p.getDouble("hr_scale"), 0.0)
        assertEquals(0.35, p.getDouble("denoising_strength"), 0.001)
        assertEquals(100L, p.getLong("seed"))
        assertEquals(512 to 768, p.getInt("width") to p.getInt("height"))
        assertEquals("Euler a", p.getString("sampler_name"))
        assertEquals("Karras", p.getString("scheduler"))
        assertEquals("a cat", p.getString("prompt"))
        assertEquals("ugly", p.getString("negative_prompt"))
        assertEquals("model.safetensors [abc123]", p.getJSONObject("override_settings").getString("sd_model_checkpoint"))
        assertEquals(true, p.getBoolean("save_images"))
    }

    @Test fun `03 an image made with hires fix x2 is upscaled only to a larger scale`() {
        read(ImageJobs.Kind.UPSCALE, listOf(hires))
        val sent = payloads().size
        onMain { vm.queueUpscales(2.5f, "Latent", 0.35f) }
        // Queued asynchronously: wait for what was sent, not for an empty queue.
        awaitUntil("sent", 20_000) { payloads().size == sent + 1 }
        idle()
        assertNotPaused("Upscale x2.5")
        val p = payloads().drop(sent).single()
        assertEquals(2.5, p.getDouble("hr_scale"), 0.0)
        assertEquals(200L, p.getLong("seed"))
        assertEquals("other.safetensors [def456]", p.getJSONObject("override_settings").getString("sd_model_checkpoint"))
    }

    @Test fun `04 More Like This keeps the seed and sends the neighbouring variation seeds`() {
        TestApp.forge.generationMs = 200
        read(ImageJobs.Kind.MORE_LIKE_THIS, listOf(plain))
        val sent = payloads().size
        onMain { vm.queueMoreLikeThis(similar = true, count = 4, strength = 0.2f) }
        awaitUntil("queued") { vm.generationQueue.value.size >= 3 }
        awaitUntil("sent", 20_000) { payloads().size == sent + 4 }
        println("[G37-04] ${vm.generationQueue.value.map { it.label }}")
        idle()
        val sentNow = payloads().drop(sent)
        assertEquals(listOf(100L), sentNow.map { it.getLong("seed") }.distinct())
        assertEquals(listOf(101L, 99L, 102L, 98L), sentNow.map { it.getLong("subseed") })
        assertTrue(sentNow.all { Math.abs(it.getDouble("subseed_strength") - 0.2) < 0.001 })
        assertTrue(sentNow.none { it.getBoolean("enable_hr") })
        assertTrue("no hires fix, no hires modules", sentNow.none { it.has("hr_additional_modules") })
    }

    @Test fun `05 Neighbouring Seeds sends the seeds next to the image's`() {
        read(ImageJobs.Kind.MORE_LIKE_THIS, listOf(plain))
        val sent = payloads().size
        onMain { vm.queueMoreLikeThis(similar = false, count = 2, strength = 0.2f) }
        awaitUntil("sent", 20_000) { payloads().size == sent + 2 }
        idle()
        assertEquals(listOf(101L, 99L), payloads().drop(sent).map { it.getLong("seed") })
    }

    @Test fun `06 a dialog closed while reading gets no late result`() {
        MockIib.infoDelayMs = 600
        onMain { vm.requestImageJobs(ImageJobs.Kind.UPSCALE, listOf(plain)) }
        onMain { vm.dismissImageJobs() }
        Thread.sleep(1_200)
        assertNull(vm.imageJobs.value)
        MockIib.infoDelayMs = 0
    }

    @Test fun `07 the main screen's hires fix goes through too`() {
        val sent = payloads().size
        onMain { vm.updateState { it.copy(positivePrompt = "a hires cat", batchCount = 1, hiresFix = true) } }
        onMain { vm.queueGeneration() }
        awaitUntil("sent", 20_000) { payloads().size == sent + 1 }
        idle()
        val p = payloads().drop(sent).single()
        println("[G37-07] paused=${vm.isQueuePaused.value} reason=${vm.queuePauseReason.value}")
        onMain { vm.updateState { it.copy(hiresFix = false) } }
        assertNotPaused("Hires fix")
        assertEquals(true, p.getBoolean("enable_hr"))
        assertEquals(listOf("Use same choices"), p.getJSONArray("hr_additional_modules").toList())
    }

    @Test fun `08 a failing job pauses the queue with the server's own error, also in its notification`() {
        fun job(prompt: String) = Txt2ImgPayloadDto(
            prompt = prompt, negative_prompt = "", steps = 20, cfg_scale = 7f, width = 512, height = 512, n_iter = 1,
            batch_size = 1, seed = 1L, sampler_name = "Euler a", scheduler = "Karras",
            override_settings = OverrideSettingsDto(clipSkip = 1), enable_hr = false, hr_scale = 2f,
            hr_upscaler = "Latent", denoising_strength = 0.35f,
        )
        androidx.core.app.NotificationManagerCompat.posted.clear()
        // Both at once: a failed job is dropped, and only a queue with jobs left stays paused.
        onMain { ForgeQueueManager.queueJobs(listOf(job("boom") to "first", job("after") to "second")) }
        awaitUntil("paused", 20_000) { vm.isQueuePaused.value && !vm.isGenerating.value }
        val alerts = synchronized(androidx.core.app.NotificationManagerCompat.posted) {
            androidx.core.app.NotificationManagerCompat.posted.map { it[1] as android.app.Notification }
        }.filter { it.title == "Queue paused" }
        println("[G37-08] reason=${vm.queuePauseReason.value} alert=${alerts.lastOrNull()?.bigText}")
        val reason = "The server returned HTTP 500. RuntimeError: Sizes of tensors must match"
        assertEquals(reason, vm.queuePauseReason.value)
        assertEquals(listOf("after"), vm.generationQueue.value.map { it.positivePrompt })
        assertEquals("$reason Open the app to resume the queue.", alerts.last().bigText.toString())
        onMain { vm.clearQueue() }
    }
}
