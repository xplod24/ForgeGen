package com.example.forgegen

import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters

/**
 * 3.5.2-1: the backup carries the favorites and the queue, and imported jobs wait in a paused queue. (The updater's
 * move to the new package, tested here up to 3.6.0-1, was removed in 3.6.0-2.)
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class G47_MoveTest {
    companion object {
        val payload =
            Txt2ImgPayloadDto("a cat", "blurry", 20, 7f, 512, 512, 1, 1, 7L, "Euler a", "Automatic", OverrideSettingsDto(1, null), false, 2f, "Latent", 0.7f)

        @BeforeClass @JvmStatic fun init() {
            TestApp.start()
            android.content.ContextWrapper.base = TestApp.app
        }
    }

    @Test fun `04 the backup carries favorites and the queue, imported jobs wait paused`() {
        val favorite = FavoriteImageEntity(fullpath = "/out/txt2img/a.png", name = "a.png", date = "2026-09-30", savedAt = 5L)
        assertEquals(1, runBlocking { ForgeGalleryManager.importFavorites(listOf(favorite, favorite)) })
        assertEquals("already there", 0, runBlocking { ForgeGalleryManager.importFavorites(listOf(favorite)) })
        assertTrue(favorite.fullpath in ForgeGalleryManager.favoritePaths.value)

        val jobs =
            listOf(
                QueuedGeneration(id = "move-1", positivePrompt = "a cat", payload = payload),
                QueuedGeneration(id = "move-2", positivePrompt = "a dog", payload = payload.copy(prompt = "a dog"), status = GenerationStatus.GENERATING),
            )
        val sentBefore = TestApp.forge.calls("/sdapi/v1/txt2img").size
        assertEquals(2, ForgeQueueManager.importJobs(jobs))
        assertEquals("already there", 0, ForgeQueueManager.importJobs(jobs))
        assertTrue(TestApp.vm.isQueuePaused.value)
        assertEquals(ForgeQueueManager.IMPORTED_REASON, ForgeQueueManager.queuePauseReason.value)
        assertEquals("a running job comes back waiting", GenerationStatus.QUEUED, TestApp.vm.generationQueue.value.first { it.id == "move-2" }.status)
        Thread.sleep(1_500)
        assertEquals("nothing starts by itself", sentBefore, TestApp.forge.calls("/sdapi/v1/txt2img").size)

        // Exported here...
        val out = Uri("content://documents/forgegen-backup-move.json")
        onMain { TestApp.vm.exportBackup(out) }
        awaitUntil("exported") { TestApp.app.resolver.documents[out.toString()]?.length() ?: 0L > 0L }
        val json = TestApp.app.resolver.documents[out.toString()]!!.readText()
        val backup = Backup.read(json)!!
        assertTrue(backup.favorites.any { it.fullpath == favorite.fullpath })
        assertEquals(listOf("move-1", "move-2"), backup.queue.map { it.id }.filter { it.startsWith("move-") })

        // ...and imported where they are missing (as in the new app).
        onMain { ForgeQueueManager.clearQueue() }
        onMain { TestApp.vm.toggleFavorite(GalleryItem(name = "a.png", fullpath = favorite.fullpath, type = "file", date = favorite.date)) }
        awaitUntil("favorite gone") { favorite.fullpath !in ForgeGalleryManager.favoritePaths.value }
        awaitUntil("queue empty") { TestApp.vm.generationQueue.value.isEmpty() }
        val input = Uri("content://documents/picked-backup.json")
        TestApp.app.resolver.inputs[input] = json.toByteArray()
        onMain { TestApp.vm.importBackup(input) }
        awaitUntil("favorite back") { favorite.fullpath in ForgeGalleryManager.favoritePaths.value }
        awaitUntil("jobs back") { TestApp.vm.generationQueue.value.map { it.id }.containsAll(listOf("move-1", "move-2")) }
        assertTrue("imported jobs wait", TestApp.vm.isQueuePaused.value)
        Thread.sleep(1_000)
        assertEquals(sentBefore, TestApp.forge.calls("/sdapi/v1/txt2img").size)
        onMain { ForgeQueueManager.clearQueue() }
    }
}
