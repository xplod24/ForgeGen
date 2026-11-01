@file:Suppress("ktlint:standard:class-naming")

package com.example.forgegen

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real production synchronization against MockIib, observed between committed batches. */
@OptIn(DelicateCoroutinesApi::class)
class G57_IndexProgressTest {
    companion object {
        private val toasts = CopyOnWriteArrayList<String>()
        private val batches = AtomicInteger()

        @Volatile private var blockSecond = false

        @Volatile private var failSecond = false
        private var entered = CountDownLatch(1)
        private var release = CountDownLatch(1)

        @BeforeClass @JvmStatic
        fun init() {
            TestApp.start(custom = { ex, path, body ->
                if (path == "/infinite_image_browsing/image_geninfo_batch" && batches.incrementAndGet() == 2) {
                    if (blockSecond) {
                        entered.countDown()
                        check(release.await(20, TimeUnit.SECONDS)) { "The test did not release the metadata response" }
                    }
                    if (failSecond) {
                        ex.sendResponseHeaders(500, -1)
                        ex.close()
                        true
                    } else {
                        MockIib.route(ex, path, body)
                    }
                } else {
                    MockIib.route(ex, path, body)
                }
            })
            GlobalScope.launch(Dispatchers.IO) { ForgeSettingsManager.snackbarMessage.collect { toasts += it } }
            GlobalScope.launch(Dispatchers.IO) { ForgeGalleryManager.allImages.collect { } }
            awaitUntil("index loaded") { ForgeGalleryManager.indexLoaded.value }
        }
    }

    @Before fun reset() {
        release.countDown()
        awaitUntil("previous sync finished", 20_000) { !ForgeGalleryManager.isIndexing.value }
        MockIib.reset()
        toasts.clear()
        blockSecond = false
        failSecond = false
        entered = CountDownLatch(1)
        release = CountDownLatch(1)
        batches.set(0)
        onMain { TestApp.vm.wipeGalleryIndex() }
        awaitUntil("index wiped") { "Gallery Index Wiped" in toasts }
    }

    private fun add(
        folder: String,
        start: Int,
        count: Int,
    ) {
        repeat(count) { i -> MockIib.file(folder, "${(start + i).toString().padStart(5, '0')}.png", "model", date = MockIib.now()) }
    }

    private fun start(): Int {
        val before = ForgeGalleryManager.syncsDone.get()
        batches.set(0)
        ForgeGalleryManager.lastAutoSyncAt = 0
        onMain { TestApp.vm.autoSyncGallery() }
        return before
    }

    private fun finish(before: Int) {
        awaitUntil("sync finished", 20_000) {
            ForgeGalleryManager.syncsDone.get() > before && !ForgeGalleryManager.isIndexing.value
        }
    }

    @Test fun initialIndexPublishesOnlyCommittedImagesAndKeepsUnreadableOnes() {
        val folder = MockIib.dir("/out", "today", MockIib.now())
        add(folder, 0, 205)
        MockIib.unreadable += "$folder/00000.png"
        blockSecond = true
        val before = start()
        try {
            assertTrue(entered.await(15, TimeUnit.SECONDS))
            val progress = ForgeGalleryManager.indexProgress.value
            assertEquals(GalleryIndexStage.READING_IMAGES, progress.stage)
            assertEquals(100, progress.indexed)
            assertEquals(205, progress.total)
            assertEquals(100, progress.processed)
            assertEquals(205, progress.workTotal)
            assertEquals(1, progress.unreadable)
            assertEquals(100, TestApp.db.gallery.size)
        } finally {
            release.countDown()
        }
        finish(before)
        assertEquals(205, ForgeGalleryManager.indexProgress.value.indexed)
        assertEquals(GalleryIndexStage.UP_TO_DATE, ForgeGalleryManager.indexProgress.value.stage)
        assertTrue(TestApp.db.gallery.containsKey("$folder/00000.png"))
    }

    @Test fun incrementalUpdateKeepsGalleryWideCounts() {
        val folder = MockIib.dir("/out", "today", MockIib.now())
        add(folder, 0, 205)
        finish(start())
        add(folder, 205, 120)
        blockSecond = true
        val before = start()
        try {
            assertTrue(entered.await(15, TimeUnit.SECONDS))
            val progress = ForgeGalleryManager.indexProgress.value
            assertEquals(305, progress.indexed)
            assertEquals(325, progress.total)
            assertEquals(100, progress.processed)
            assertEquals(120, progress.workTotal)
        } finally {
            release.countDown()
        }
        finish(before)
        assertEquals(325, ForgeGalleryManager.indexProgress.value.indexed)
    }

    @Test fun failureKeepsCommittedImagesAvailableAndRetryFinishesTheIndex() {
        val folder = MockIib.dir("/out", "today", MockIib.now())
        add(folder, 0, 205)
        failSecond = true
        finish(start())
        val failed = ForgeGalleryManager.indexProgress.value
        assertEquals(GalleryIndexStage.FAILED, failed.stage)
        assertEquals(100, failed.indexed)
        assertEquals(205, failed.total)
        assertNotNull(failed.error)
        assertEquals(100, ForgeGalleryManager.indexedImages.value.size)
        failSecond = false
        finish(start())
        assertEquals(GalleryIndexStage.UP_TO_DATE, ForgeGalleryManager.indexProgress.value.stage)
        assertEquals(205, ForgeGalleryManager.indexProgress.value.indexed)
    }
}
