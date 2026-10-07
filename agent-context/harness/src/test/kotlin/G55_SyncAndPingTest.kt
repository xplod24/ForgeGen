package com.example.forgegen

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 3.6.0-1: a sync puts its changes into the index in memory instead of reading the whole index (and all its paths) from
 * the database again; an image whose data cannot be read is tried three times in a run of the app; every folder is
 * listed once a week; on screen with nothing to do the server is asked every 4 s instead of every 2 s.
 */
@OptIn(DelicateCoroutinesApi::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class G55_SyncAndPingTest {
    companion object {
        val toasts = CopyOnWriteArrayList<String>()
        val vm get() = TestApp.vm
        const val DAY = 24 * 60 * 60 * 1000L

        @BeforeClass @JvmStatic fun init() {
            TestApp.start(custom = { ex, path, body -> MockIib.route(ex, path, body) })
            GlobalScope.launch(Dispatchers.IO) { ForgeSettingsManager.snackbarMessage.collect { toasts += it } }
            GlobalScope.launch(Dispatchers.IO) { ForgeGalleryManager.allImages.collect { } }
            awaitUntil("index loaded") { ForgeGalleryManager.indexLoaded.value }
        }

        fun calls(path: String) = TestApp.forge.calls("/infinite_image_browsing/$path")

        /** A sync now; every folder is listed when the last full sync was at [fullSyncAt] a week ago or more. */
        fun sync(fullSyncAt: Long): String {
            awaitUntil("no sync running", 20_000) { !vm.isGalleryIndexing.value }
            TestApp.db.settings["gallery_full_sync_at"] = fullSyncAt.toString()
            val field = ForgeGalleryManager::class.java.getDeclaredField("lastAutoSyncAt").apply { isAccessible = true }
            field.setLong(if (java.lang.reflect.Modifier.isStatic(field.modifiers)) null else ForgeGalleryManager, 0L)
            val before = ForgeGalleryManager.syncsDone.get()
            onMain { vm.autoSyncGallery() }
            awaitUntil("sync", 20_000) { ForgeGalleryManager.syncsDone.get() > before && !vm.isGalleryIndexing.value }
            return ForgeGalleryManager.lastSyncMessage.value
        }

        fun fullSync() = sync(0L)

        fun quietSync() = sync(System.currentTimeMillis())

        fun all() = vm.allImages.value.items.map { it.fullpath }

        /** The index as the database sorts it (the real query: date, then name, both descending). */
        fun databaseOrder() =
            TestApp.db.gallery.values
                .sortedWith(compareByDescending<GalleryImageEntity> { it.date }.thenByDescending { it.name })
                .map { it.fullpath }

        fun listings(folder: String) = calls("files").count { it.query.orEmpty().contains(folder.substringAfterLast('/')) }

        fun pings() = TestApp.forge.calls("/sdapi/v1/progress").size
    }

    @Before fun reset() {
        MockIib.reset()
        toasts.clear()
        onMain { vm.wipeGalleryIndex() }
        awaitUntil("index wiped") { toasts.contains("Gallery Index Wiped") }
    }

    @Test fun `01 a sync puts new and removed images into the index in memory without reading the database`() {
        val today = MockIib.dir("/out", "2026-10-02", MockIib.now())
        val old = MockIib.dir("/out", "2026-01-01")
        MockIib.file(today, "00001-1.png", "alpha", date = MockIib.now(-120_000))
        MockIib.file(today, "00002-2.png", "alpha", date = MockIib.now(-60_000))
        val gone = MockIib.file(old, "00001-3.png", "beta")
        val indexReads = TestApp.db.indexReads.get()
        val pathReads = TestApp.db.pathReads.get()

        fullSync()
        awaitUntil("three images") { all().size == 3 }
        assertEquals(databaseOrder(), all())

        // A new image: in at the top.
        val fresh = MockIib.file(today, "00003-4.png", "gamma", date = MockIib.now())
        quietSync()
        awaitUntil("the new image first") { all().firstOrNull() == fresh }
        assertEquals(databaseOrder(), all())

        // An image deleted on the server (its folder's date changes): out.
        MockIib.remove(gone)
        MockIib.tree.getValue("/out").first { it.path == old }.date = "2026-01-02 10:00:00"
        quietSync()
        awaitUntil("the deleted image gone") { gone !in all() }
        assertEquals(databaseOrder(), all())
        assertEquals(3, all().size)

        assertEquals("the whole index was not read again", indexReads, TestApp.db.indexReads.get())
        assertEquals("nor all its paths", pathReads, TestApp.db.pathReads.get())
    }

    @Test fun `02 an image whose data cannot be read is tried three times, then left until the app starts again`() {
        val old = MockIib.dir("/out", "2026-01-01")
        MockIib.file(old, "00001-1.png", "alpha")
        val bad = MockIib.file(old, "00002-2.png", "alpha")
        MockIib.unreadable += bad
        fun tries() = calls("image_geninfo_batch").count { it.body.contains(bad) }

        val message = fullSync()
        assertTrue(message, message.contains("1 without readable generation data"))
        assertEquals(1, tries())
        awaitUntil("both shown") { all().size == 2 }

        quietSync()
        quietSync()
        assertEquals("three tries", 3, tries())
        val listed = listings(old)
        quietSync()
        assertEquals("no fourth try", 3, tries())
        assertEquals("its folder is no longer listed for it", listed, listings(old))
        fullSync()
        assertEquals("not even by a full sync", 3, tries())
        assertEquals("still shown", 2, all().size)

        // Wiping the index tries every image anew; one that can be read now is read.
        MockIib.unreadable.clear()
        toasts.clear()
        onMain { vm.wipeGalleryIndex() }
        awaitUntil("index wiped") { toasts.contains("Gallery Index Wiped") }
        fullSync()
        assertEquals(4, tries())
        assertTrue(TestApp.db.gallery.getValue(bad).savedAt > 0)
    }

    @Test fun `03 every folder is listed once a week, not once a day`() {
        val today = MockIib.dir("/out", "2026-10-02", MockIib.now())
        val old = MockIib.dir("/out", "2026-01-01")
        MockIib.file(today, "00001-1.png", "alpha", date = MockIib.now(-60_000))
        MockIib.file(old, "00001-2.png", "beta")
        fullSync()
        val listed = listings(old)

        // Two days after the last full sync: only the recent folder is listed.
        val twoDaysAgo = System.currentTimeMillis() - 2 * DAY
        sync(twoDaysAgo)
        assertEquals("the old folder is not listed", listed, listings(old))
        assertEquals(twoDaysAgo.toString(), TestApp.db.settings["gallery_full_sync_at"])

        // Eight days after it: every folder.
        sync(System.currentTimeMillis() - 8 * DAY)
        assertEquals(listed + 1, listings(old))
        assertTrue(TestApp.db.settings.getValue("gallery_full_sync_at").toLong() > System.currentTimeMillis() - 60_000)
    }

    @Test fun `04 the wait between pings`() {
        val delay = { foreground: Boolean, generating: Boolean, jobsWaiting: Boolean ->
            ForgeRepository.pingDelay(
                connected = true,
                foreground = foreground,
                generating = generating,
                searching = false,
                failCount = 0,
                jobsWaiting = jobsWaiting,
            )
        }
        assertEquals("on screen, nothing to do", 4_000L, delay(true, false, false))
        assertEquals("on screen, jobs waiting", 2_000L, delay(true, false, true))
        assertEquals("generating on screen", 1_000L, delay(true, true, true))
        assertEquals("generating in the background", 2_000L, delay(false, true, true))
        assertEquals("in the background", 10_000L, delay(false, false, true))
    }

    @Test fun `05 on screen with nothing to do the server is asked every 4 s, with a job waiting every 2 s`() {
        onMain { vm.setAppForegroundState(true) }
        awaitUntil("connected") { vm.isConnected.value }
        Thread.sleep(1_000)
        val idleStart = pings()
        Thread.sleep(8_500)
        val idle = pings() - idleStart
        assertTrue("about two pings in 8.5 s, not four: $idle", idle in 1..3)

        // A job waits for its start time: every 2 s again.
        onMain { vm.updateState { it.copy(positivePrompt = "a cat", batchCount = 1) } }
        onMain { ForgeQueueManager.scheduleStart(System.currentTimeMillis() + 3_600_000) }
        onMain { vm.queueGeneration() }
        awaitUntil("job waiting") { vm.generationQueue.value.isNotEmpty() }
        Thread.sleep(1_000)
        val waitingStart = pings()
        Thread.sleep(8_500)
        val waiting = pings() - waitingStart
        assertTrue("about four pings in 8.5 s: $waiting", waiting >= 4)

        onMain { ForgeQueueManager.startScheduledQueueNow() }
        awaitUntil("job done", 20_000) { !vm.isGenerating.value && vm.generationQueue.value.isEmpty() }
        assertFalse(vm.isGenerating.value)
    }
}
