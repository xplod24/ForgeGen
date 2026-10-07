package com.example.forgegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.File

/** 3.6.2: the session (its images, the one shown, a pause, the run's progress) comes back at the first start after an update. */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class G56_SessionMemoryTest {
    companion object {
        lateinit var kept: List<String>
        lateinit var stale: File

        private fun job(id: String) =
            QueuedGeneration(
                id, "saved $id",
                Txt2ImgPayloadDto("saved $id", "", 20, 7f, 512, 512, 1, 1, -1, "Euler a", "automatic", OverrideSettingsDto(2), false, 2f, "Latent", 0.5f),
            )

        fun saved(): SavedSession = TestApp.gson.fromJson(TestApp.db.settings[SessionMemory.KEY], SavedSession::class.java)

        @BeforeClass @JvmStatic fun init() {
            TestApp.start(
                seed = { settings["saved_queue"] = TestApp.gson.toJson(listOf(job("j1"), job("j2"))) },
                prepare = {
                    val files = listOf("gen_1_0.png", "gen_2_0.png", "gen_2_1.png").map { File(cacheDir, it).apply { writeBytes(byteArrayOf(1, 2, 3)) } }
                    kept = files.map { it.absolutePath }
                    val gone = File(cacheDir, "gen_0_0.png").absolutePath // listed, but no longer in the cache
                    stale = File(cacheDir, "gen_9_0.png").apply { writeBytes(byteArrayOf(4)) } // not part of the session
                    // Written by the build before the update: the second batch shown at its first image, the queue
                    // paused by the user after three jobs of its run.
                    TestApp.db.settings[SessionMemory.KEY] =
                        TestApp.gson.toJson(
                            SavedSession(
                                versionCode = BuildConfig.VERSION_CODE - 1,
                                images = listOf(gone) + kept,
                                index = 2,
                                batchStart = 2,
                                batchEnd = 3,
                                paused = true,
                                pauseReason = ForgeQueueManager.USER_PAUSED_REASON,
                                completed = 3,
                            ),
                        )
                },
            )
        }
    }

    @Test fun `01 the session's images and the one shown come back, the rest of the cache is cleaned`() {
        assertEquals(kept, ForgeQueueManager.sessionImages.value)
        assertEquals("the shown image", 1, ForgeQueueManager.currentSessionIndex.value)
        assertEquals("its batch", 1 to 2, ForgeQueueManager.currentBatchStartIndex.value to ForgeQueueManager.currentBatchEndIndex.value)
        awaitUntil("the stale image deleted") { !stale.exists() }
        assertTrue("the session's files stay", kept.all { File(it).isFile })
    }

    @Test fun `02 the queue still waits, paused with its reason and its run's progress`() {
        assertTrue(ForgeQueueManager.isQueuePaused.value)
        assertEquals(ForgeQueueManager.USER_PAUSED_REASON, ForgeQueueManager.queuePauseReason.value)
        assertEquals("done in this run", 3, ForgeQueueManager.completedQueueItems.value)
        assertEquals("3 done + 2 waiting", 5, ForgeQueueManager.totalQueueSize.value)
        Thread.sleep(1_500)
        assertTrue("nothing sent while paused", TestApp.forge.calls("/sdapi/v1/txt2img").isEmpty())
    }

    @Test fun `03 this build saves the session again as its own`() {
        awaitUntil("saved by this build") { saved().versionCode == BuildConfig.VERSION_CODE }
        assertEquals(kept, saved().images)
        assertTrue(saved().paused)
        // A start of the same build (an ordinary restart) would not bring it back.
        assertEquals(null, SessionMemory.afterUpdate(saved(), BuildConfig.VERSION_CODE) { true })
    }

    @Test fun `04 resumed, the queue runs and the new images join the saved session`() {
        ForgeQueueManager.resumeQueue()
        awaitUntil("queue done", 30_000) { ForgeQueueManager.generationQueue.value.isEmpty() && !ForgeQueueManager.isGenerating.value }
        val images = ForgeQueueManager.sessionImages.value
        assertTrue("two new images after the kept ones: $images", images.size == kept.size + 2 && images.take(kept.size) == kept)
        awaitUntil("saved with the new images") { saved().images == images }
        assertFalse("the run is over", saved().paused)
        assertEquals(0, saved().completed)
    }
}
