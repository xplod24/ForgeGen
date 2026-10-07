package com.example.forgegen

import com.sun.net.httpserver.HttpExchange
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters

/** A Forge with LoRA metadata, embeddings, styles and its web UI's queue, which can go away for a moment (3.4.0). */
object MockFeatures {
    @Volatile var down = false

    // A LoRA added on the server: the list (and so its metadata) changes.
    @Volatile var extraLora = false

    fun route(
        ex: HttpExchange,
        path: String,
        body: String,
    ): Boolean {
        if (down) {
            ex.close()
            return true
        }
        val extraModel = if (extraLora) EXTRA_MODEL else ""
        val extraInfo = if (extraLora) EXTRA_INFO else ""
        return when (path) {
            "/customapi/v1/all-models-hashes" ->
                json(
                    ex,
                    """{"models":[{"type":"checkpoint","name":"model","filename":"/m/model.safetensors","sha256":"m1"},""" +
                        """{"type":"lora","name":"detail","filename":"/l/detail.safetensors","sha256":"l1"}""" + extraModel + "]}",
                )
            "/sdapi/v1/loras" ->
                json(
                    ex,
                    """[{"name":"detail","path":"/l/detail.safetensors","metadata":{"ss_base_model_version":"sdxl_base_v1-0"}}""" + extraInfo + "]",
                )
            "/sdapi/v1/embeddings" -> json(ex, """{"loaded":{"EasyNegative":{}},"skipped":{}}""")
            "/sdapi/v1/prompt-styles" -> json(ex, """[{"name":"Cinematic","prompt":"cinematic, {prompt}","negative_prompt":""}]""")
            "/internal/progress" -> json(ex, """{"active":true,"queued":false,"completed":false,"textinfo":"Waiting..."}""")
            else -> MockIibFiles.route(ex, path, body)
        }
    }

    private const val EXTRA_MODEL = ",{\"type\":\"lora\",\"name\":\"extra\",\"filename\":\"/l/extra.safetensors\",\"sha256\":\"l2\"}"
    private const val EXTRA_INFO = ",{\"name\":\"extra\",\"path\":\"/l/extra.safetensors\",\"metadata\":{}}"

    private fun json(
        ex: HttpExchange,
        text: String,
    ): Boolean {
        val bytes = text.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
        return true
    }
}

@OptIn(DelicateCoroutinesApi::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class G45_FeatureSwitchesTest {
    companion object {
        val vm get() = TestApp.vm

        @BeforeClass @JvmStatic fun init() {
            ForgeRepository.searchPingMs = 300
            MockIib.reset()
            MockIibFiles.reset()
            val day = MockIib.dir("/out", "2026-09-28")
            (1..3).forEach { MockIib.file(day, "d$it.png", "alpha", date = "2026-09-28 10:0$it:00") }
            MockIib.dir("/out", "empty")
            TestApp.start(custom = { ex, path, body -> MockFeatures.route(ex, path, body) })
            GlobalScope.launch(Dispatchers.IO) { ForgeGalleryManager.folderView.collect { } }
            GlobalScope.launch(Dispatchers.IO) { ForgeGalleryManager.favoriteImages.collect { } }
            awaitUntil("gallery extension", 20_000) { vm.galleryExtension.value.state == ForgeGalleryManager.Extension.READY }
        }

        fun calls(path: String) = TestApp.forge.calls(path).size

        fun set(change: AppConfig.() -> AppConfig) = onMain { vm.saveConfig(vm.config.value.change()) }

        /** The server goes away for a moment and comes back; the app fetches its lists again. */
        fun blip() {
            val listsBefore = calls("/customapi/v1/all-models-hashes")
            MockFeatures.down = true
            awaitUntil("gone", 15_000) { !vm.isConnected.value }
            MockFeatures.down = false
            awaitUntil("back", 15_000) { vm.isConnected.value && calls("/customapi/v1/all-models-hashes") > listsBefore }
            Thread.sleep(1_500) // what the lists start in the background
        }

        fun progressSkips(since: Int) =
            TestApp.forge.requests
                .filter { it.path == "/sdapi/v1/progress" }
                .drop(since)
                .map { it.query.orEmpty().contains("skip_current_image=true") }
    }

    @Test fun `01 a LoRA's metadata is read again only when the LoRAs changed`() {
        awaitUntil("lora info", 15_000) { vm.loraInfo.value.size == 1 }
        awaitUntil("embeddings") { vm.embeddings.value.loaded == listOf("EasyNegative") }
        assertEquals(1, calls("/sdapi/v1/loras"))
        assertEquals("styles are off unless turned on", 0, calls("/sdapi/v1/prompt-styles"))
        blip()
        assertEquals("the same LoRAs: their metadata is not downloaded again", 1, calls("/sdapi/v1/loras"))
        MockFeatures.extraLora = true
        blip()
        awaitUntil("new metadata") { vm.loraInfo.value.size == 2 }
        assertEquals(2, calls("/sdapi/v1/loras"))
    }

    @Test fun `02 switched off, a feature drops its list and asks the server nothing`() {
        val embeddings = calls("/sdapi/v1/embeddings")
        set { copy(loraDetails = false, embeddings = false) }
        awaitUntil("dropped") { vm.loraInfo.value.size == 0 && vm.embeddings.value.loaded.isEmpty() }
        MockFeatures.extraLora = false
        blip()
        onMain { vm.changeCheckpoint("model") }
        Thread.sleep(1_000)
        assertEquals(2, calls("/sdapi/v1/loras"))
        assertEquals(embeddings, calls("/sdapi/v1/embeddings"))
        assertEquals(0, vm.loraInfo.value.size)
    }

    @Test fun `03 switched on again, its list comes at once`() {
        set { copy(loraDetails = true, embeddings = true, serverStyles = true) }
        awaitUntil("back on", 10_000) {
            vm.loraInfo.value.size == 1 && vm.embeddings.value.loaded.isNotEmpty() && vm.promptStyles.value.map { it.name } == listOf("Cinematic")
        }
        set { copy(serverStyles = false) }
        awaitUntil("styles dropped") { vm.promptStyles.value.isEmpty() }
    }

    @Test fun `04 the live preview is asked for only while it can be seen`() {
        TestApp.forge.generationMs = 7_000
        vm.setPreviewShown(true)
        onMain { vm.queueGeneration() }
        awaitUntil("generating", 10_000) { TestApp.forge.generating }
        var mark = calls("/sdapi/v1/progress")
        awaitUntil("preview asked for", 5_000) { progressSkips(mark).contains(false) }
        // The gallery, the queue or the settings in front: no preview.
        vm.setPreviewShown(false)
        Thread.sleep(300)
        mark = calls("/sdapi/v1/progress")
        Thread.sleep(2_000)
        assertTrue("some pings: ${progressSkips(mark)}", progressSkips(mark).size >= 2)
        assertTrue(progressSkips(mark).all { it })
        // Shown again, but Live Preview is off.
        set { copy(livePreview = false) }
        vm.setPreviewShown(true)
        Thread.sleep(300)
        mark = calls("/sdapi/v1/progress")
        Thread.sleep(1_500)
        assertTrue(progressSkips(mark).isNotEmpty() && progressSkips(mark).all { it })
        set { copy(livePreview = true) }
        awaitUntil("done", 15_000) { !vm.isGenerating.value }
    }

    @Test fun `05 memory is read on screen, not in the background, not with the meters off`() {
        TestApp.forge.generationMs = 14_000
        onMain { vm.queueGeneration() }
        awaitUntil("generating", 10_000) { TestApp.forge.generating }
        var memory = calls("/sdapi/v1/memory")
        awaitUntil("read on screen", 7_000) { calls("/sdapi/v1/memory") > memory }
        // 3.6.0: the job's start is recorded first (its VRAM is read until a reading after the first step).
        awaitUntil("the start recorded", 7_000) { !JobRecorder.wantsVram() }
        // In the background: the progress every 2 s, and no memory.
        onMain { vm.setAppForegroundState(false) }
        Thread.sleep(300)
        memory = calls("/sdapi/v1/memory")
        val progress = calls("/sdapi/v1/progress")
        Thread.sleep(5_000)
        assertEquals("no memory in the background", memory, calls("/sdapi/v1/memory"))
        val pings = calls("/sdapi/v1/progress") - progress
        assertTrue("about one ping in 2 s, not every second: $pings", pings in 2..3)
        // On screen with the meters off: none either, but the panel reads it when it opens.
        set { copy(memoryMeters = false) }
        onMain { vm.setAppForegroundState(true) }
        Thread.sleep(300)
        memory = calls("/sdapi/v1/memory")
        Thread.sleep(5_500)
        assertEquals("no memory with the meters off", memory, calls("/sdapi/v1/memory"))
        onMain { vm.readServerMemory() }
        awaitUntil("read for the panel") { calls("/sdapi/v1/memory") == memory + 1 }
        set { copy(memoryMeters = true) }
        awaitUntil("done", 20_000) { !vm.isGenerating.value }
    }

    @Test fun `06 other jobs on the server are not asked about with the switch off`() {
        set { copy(serverQueue = false) }
        TestApp.forge.generationMs = 2_500
        val asked = calls("/internal/progress")
        onMain { vm.queueGeneration() }
        awaitUntil("generating", 10_000) { TestApp.forge.generating }
        awaitUntil("done", 15_000) { !vm.isGenerating.value }
        assertEquals(asked, calls("/internal/progress"))
        set { copy(serverQueue = true) }
        onMain { vm.queueGeneration() }
        awaitUntil("asked", 10_000) { calls("/internal/progress") > asked }
        awaitUntil("done", 15_000) { !vm.isGenerating.value }
    }

    @Test fun `07 folder covers and the favorites check ask nothing when off`() {
        fun iib(endpoint: String) = calls("/infinite_image_browsing/$endpoint")
        set { copy(folderCovers = false, favoritesCheck = false) }
        val covers = iib("batch_top_4_media_info")
        onMain { vm.fetchGalleryFolder("/out") }
        awaitUntil("listed") { vm.currentGalleryPath.value == "/out" && !vm.isGalleryLoading.value }
        onMain { vm.toggleFavorite(GalleryItem("d1.png", "/out/2026-09-28/d1.png", "file", "2026-09-28 10:01:00")) }
        awaitUntil("favorite") { vm.favoritePaths.value.isNotEmpty() }
        onMain { vm.checkFavorites() }
        Thread.sleep(1_000)
        assertEquals(covers, iib("batch_top_4_media_info"))
        assertEquals(0, iib("check_path_exists"))
        set { copy(folderCovers = true, favoritesCheck = true) }
        onMain { vm.refreshGallery() }
        awaitUntil("covers") { iib("batch_top_4_media_info") > covers }
        onMain { vm.checkFavorites() }
        awaitUntil("checked") { iib("check_path_exists") == 1 }
    }

    @Test fun `08 new images are indexed at once only while the gallery shows`() {
        TestApp.forge.generationMs = 300
        awaitUntil("no sync running", 20_000) { !vm.isGalleryIndexing.value }
        var syncs = ForgeGalleryManager.syncsDone.get()
        onMain { vm.queueGeneration() }
        awaitUntil("done", 15_000) { vm.sessionImages.value.isNotEmpty() && !vm.isGenerating.value }
        Thread.sleep(3_500)
        assertEquals("the gallery is closed: no sync", syncs, ForgeGalleryManager.syncsDone.get())
        // Opened moments after a sync: it still catches up with the image it missed.
        onMain { vm.autoSyncGallery() }
        awaitUntil("caught up", 15_000) { ForgeGalleryManager.syncsDone.get() > syncs }
        vm.setGalleryVisible(true)
        awaitUntil("no sync running", 20_000) { !vm.isGalleryIndexing.value }
        syncs = ForgeGalleryManager.syncsDone.get()
        val images = vm.sessionImages.value.size
        onMain { vm.queueGeneration() }
        awaitUntil("new image", 15_000) { vm.sessionImages.value.size > images }
        awaitUntil("indexed while open", 10_000) { ForgeGalleryManager.syncsDone.get() > syncs }
        vm.setGalleryVisible(false)
    }

    @Test fun `09 what the app learned about pictures is kept for the next start`() {
        val candidates = ResourcePreviews.candidates(TestApp.forge.url, "/l/detail.safetensors")
        ResourcePreviews.loaded(candidates, 3)
        awaitUntil("saved", 6_000) {
            val saved = TestApp.db.settings["resource_previews"]?.let { TestApp.gson.fromJson(it, Map::class.java) }
            saved?.get(candidates.first()) == 3.0
        }
    }
}
