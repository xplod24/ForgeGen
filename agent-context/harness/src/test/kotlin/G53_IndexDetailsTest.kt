package com.example.forgegen

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 3.6.0: the index keeps each image's size, steps, CFG, hires fix and modules (IndexDetails). Images indexed before
 * 3.6.0 get them once more through image_geninfo_batch, 100 per request, never while a job runs and never through
 * IIB's own index (the db endpoints), whose build blocks Forge.
 */
@OptIn(DelicateCoroutinesApi::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class G53_IndexDetailsTest {
    companion object {
        val toasts = CopyOnWriteArrayList<String>()
        val vm get() = TestApp.vm

        @BeforeClass @JvmStatic fun init() {
            TestApp.start(custom = { ex, path, body -> MockIib.route(ex, path, body) })
            GlobalScope.launch(Dispatchers.IO) { ForgeSettingsManager.snackbarMessage.collect { toasts += it } }
            GlobalScope.launch(Dispatchers.IO) { ForgeGalleryManager.allImages.collect { } }
        }

        fun calls(path: String) = TestApp.forge.calls("/infinite_image_browsing/$path")

        fun sync(): String {
            awaitUntil("no sync running", 20_000) { !vm.isGalleryIndexing.value }
            TestApp.db.settings["gallery_full_sync_at"] = "0"
            val field = ForgeGalleryManager::class.java.getDeclaredField("lastAutoSyncAt").apply { isAccessible = true }
            field.setLong(if (java.lang.reflect.Modifier.isStatic(field.modifiers)) null else ForgeGalleryManager, 0L)
            val before = ForgeGalleryManager.syncsDone.get()
            onMain { vm.autoSyncGallery() }
            awaitUntil("sync", 60_000) { ForgeGalleryManager.syncsDone.get() > before && !vm.isGalleryIndexing.value }
            return ForgeGalleryManager.lastSyncMessage.value
        }

        const val INFO =
            "a cat\nNegative prompt: ugly\nSteps: 28, Sampler: Euler a, Schedule type: Karras, CFG scale: 5, Seed: 7, " +
                "Size: 832x1216, Model: alpha, Module 1: sdxl_vae, Denoising strength: 0.35, Hires upscale: 1.5, " +
                "Hires upscaler: 4x-UltraSharp, Version: neo-2.1"

        /** [count] images on the server, already in the index as 3.5.x left them (no details). */
        fun oldGallery(count: Int): List<String> {
            val folder = MockIib.dir("/out", "2026-01-01")
            val paths = (1..count).map { i -> MockIib.file(folder, "%05d-%d.png".format(i, i), "alpha").also { MockIib.infos[it] = INFO } }
            paths.forEach { p ->
                TestApp.db.gallery[p] =
                    GalleryImageEntity(p, p.substringAfterLast('/'), MockIib.OLD, "a cat", "ugly", "alpha", "Euler a", "7", "", 5L, 1000L)
            }
            reloadGalleryIndex() // as the app found them at its start
            return paths
        }

        @Suppress("UNCHECKED_CAST")
        fun generating(on: Boolean) {
            val field = ForgeQueueManager::class.java.getDeclaredField("_isGenerating").apply { isAccessible = true }
            (field.get(ForgeQueueManager) as MutableStateFlow<Boolean>).value = on
        }
    }

    @Before fun reset() {
        MockIib.reset()
        generating(false)
        toasts.clear()
        onMain { vm.wipeGalleryIndex() }
        awaitUntil("index wiped") { toasts.contains("Gallery Index Wiped") }
    }

    @Test fun `01 new images are indexed with their details at once`() {
        val folder = MockIib.dir("/out", "2026-09-24", MockIib.now())
        val path = MockIib.file(folder, "00001-1.png", "alpha", date = MockIib.now(-60_000))
        MockIib.infos[path] = INFO
        sync()
        val row = TestApp.db.gallery.getValue(path)
        assertEquals(832, row.width)
        assertEquals(1216, row.height)
        assertEquals(28, row.steps)
        assertEquals(5f, row.cfg)
        assertEquals("Karras", row.scheduler)
        assertEquals(1.5f, row.hiresScale)
        assertEquals("sdxl_vae", row.modules)
        assertEquals(IndexDetails.VERSION, row.details)
        assertEquals("one request for the new image, none for details", 1, calls("image_geninfo_batch").size)
        assertEquals(0, ForgeGalleryManager.detailsBacklog.value)
    }

    @Test fun `02 images indexed before 3_6_0 get their details once, 100 per request, never through db`() {
        val paths = oldGallery(250)
        val batchesBefore = calls("image_geninfo_batch").size
        sync()
        assertEquals("250 old images: three requests", 3, calls("image_geninfo_batch").size - batchesBefore)
        paths.forEach { p ->
            val row = TestApp.db.gallery.getValue(p)
            assertEquals(IndexDetails.VERSION, row.details)
            assertEquals(832, row.width)
            assertEquals("a cat", row.positivePrompt)
        }
        assertEquals(0, ForgeGalleryManager.detailsBacklog.value)
        assertTrue("never IIB's own index", TestApp.forge.requests.none { it.path.contains("/db/") })
        // Done once: the next sync asks for nothing more.
        val again = calls("image_geninfo_batch").size
        sync()
        assertEquals(again, calls("image_geninfo_batch").size)
    }

    @Test fun `03 it waits while a job runs and goes on with the next sync`() {
        oldGallery(30)
        generating(true)
        val before = calls("image_geninfo_batch").size
        sync()
        assertEquals("nothing asked while generating", before, calls("image_geninfo_batch").size)
        assertEquals(30, ForgeGalleryManager.detailsBacklog.value)
        generating(false)
        sync()
        assertEquals(0, ForgeGalleryManager.detailsBacklog.value)
        assertEquals(1, calls("image_geninfo_batch").size - before)
    }

    @Test fun `04 an image the extension cannot read any more is not asked for again`() {
        val paths = oldGallery(3)
        MockIib.unreadable += paths[1]
        sync()
        val gone = TestApp.db.gallery.getValue(paths[1])
        assertEquals(IndexDetails.VERSION, gone.details)
        assertNull(gone.width)
        assertEquals("its prompt stays", "a cat", gone.positivePrompt)
        assertEquals(832, TestApp.db.gallery.getValue(paths[0]).width)
        val before = calls("image_geninfo_batch").size
        sync()
        assertEquals(before, calls("image_geninfo_batch").size)
    }

    @Test fun `05 a statistics row opens All Images with just its images`() {
        val folder = MockIib.dir("/out", "2026-09-25", MockIib.now())
        val tall = (1..3).map { i -> MockIib.file(folder, "0000$i-$i.png", "alpha", date = MockIib.now(-60_000L * i)) }
        val square = (4..5).map { i -> MockIib.file(folder, "0000$i-$i.png", "alpha", date = MockIib.now(-60_000L * i)) }
        tall.forEach { MockIib.infos[it] = INFO }
        square.forEachIndexed { i, p ->
            val schedule = if (i == 1) " Schedule type: Karras," else ""
            MockIib.infos[p] = "a dog\nSteps: 20, Sampler: DPM++ 2M,$schedule CFG scale: 7, Seed: 1, Size: 1024x1024, Model: alpha, Version: neo-2.1"
        }
        sync()
        val stats = kotlinx.coroutines.runBlocking { vm.galleryStatistics() }
        val sizes = stats.details!!.sizes
        assertEquals(listOf(3, 2), sizes.map { it.count })
        onMain { vm.openGalleryFrom(sizes.first().target!!) }
        awaitUntil("only the 832×1216 images") { vm.allImages.value.items.map { it.fullpath }.toSet() == tall.toSet() }
        assertEquals(GalleryTab.ALL_IMAGES, vm.galleryTab.value)
        assertEquals("Size 832×1216", vm.galleryFilters.value.detail?.label)
        // A sampler row opens just the images of its sampler and schedule type.
        assertEquals(setOf("Euler a · Karras", "DPM++ 2M", "DPM++ 2M · Karras"), stats.details!!.samplers.map { it.label }.toSet())
        onMain { vm.openGalleryFrom(stats.details!!.samplers.first { it.label == "DPM++ 2M" }.target!!) }
        awaitUntil("only the DPM++ 2M image without a schedule type") {
            vm.allImages.value.items.map { it.fullpath } == listOf(square[0])
        }
        onMain { vm.clearGalleryFilters() }
        awaitUntil("all five again") { vm.allImages.value.items.size == 5 }
    }
}
