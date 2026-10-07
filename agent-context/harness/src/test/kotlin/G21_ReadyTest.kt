package com.example.forgegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters

/** "Ready" on the welcome screen only once every part of the app is loaded. */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class G21_ReadyTest {
    companion object {
        val active = java.util.concurrent.atomic.AtomicInteger()
        @Volatile var maxActive = 0

        private fun job(id: String) =
            QueuedGeneration(
                id, "saved $id",
                Txt2ImgPayloadDto("saved $id", "", 20, 7f, 512, 512, 1, 1, -1, "Euler a", "automatic", OverrideSettingsDto(2), false, 2f, "Latent", 0.5f),
            )

        @BeforeClass @JvmStatic fun init() {
            TestApp.start(
                seed = {
                    wildcardRows["color"] = WildcardEntity("color", "red\nblue")
                    favorites["/out/a.png"] = FavoriteImageEntity("/out/a.png", "a.png", "2026-01-01 00:00:00")
                    settings["saved_queue"] = TestApp.gson.toJson(listOf(job("j1")))
                },
                custom = { _, path, _ ->
                    when (path) {
                        "/sdapi/v1/sd-models" -> Thread.sleep(1500) // a slow model list: the start must wait for it
                        "/sdapi/v1/txt2img" -> { // the saved job stays in the queue meanwhile
                            maxActive = maxOf(maxActive, active.incrementAndGet())
                            try { Thread.sleep(3000) } finally { active.decrementAndGet() }
                        }
                    }
                    false
                },
            )
        }
    }

    @Test fun `01 everything is loaded when the start returns`() {
        val a = TestApp.afterInit
        assertEquals("Ready", a.status)
        assertTrue("initialized", a.initialized)
        assertTrue("connected", a.connected)
        assertEquals("models", 2, a.models)
        assertEquals("samplers", 2, a.samplers)
        assertEquals("loras", 1, a.loras)
        assertEquals("saved queue", 1, a.queue)
        assertEquals("wildcards", 1, a.wildcards)
        assertEquals("favorites", 1, a.favorites)
        assertTrue("gallery extension probed", a.galleryPrefixProbed)
        assertTrue("waited for the slow model list: ${a.ms} ms", a.ms >= 1500)
    }

    @Test fun `02 the statuses go in order and Ready comes last`() {
        val s = TestApp.statuses.toList()
        val order = listOf("Loading Wildcards...", "Loading Queue...", "Loading Gallery...", "Connecting to Server...", "Loading Models...", "Ready")
        assertEquals(order, s.filter { it in order })
        assertEquals("Ready only at the end: $s", listOf(s.lastIndex), s.indices.filter { s[it] == "Ready" })
    }

    @Test fun `03 a second start (recreated screen) does not run it again`() {
        val before = TestApp.statuses.size
        val t0 = System.currentTimeMillis()
        kotlinx.coroutines.runBlocking { TestApp.vm.initializeApp() }
        assertTrue(System.currentTimeMillis() - t0 < 500)
        assertEquals("Ready", ForgeSettingsManager.initStatus.value)
        assertEquals(before, TestApp.statuses.size)
    }

    @Test fun `04 a new screen in the same process starts only its own part`() {
        val before = TestApp.statuses.toList()
        val vm2 = ForgeViewModel(TestApp.app)
        kotlinx.coroutines.runBlocking { vm2.awaitServerCheck() }
        assertEquals("Ready", ForgeSettingsManager.initStatus.value)
        assertEquals("its own lists", 2, vm2.models.value.size)
        // One queue worker only: jobs from both screens still go one at a time.
        onMain { vm2.updateState { it.copy(positivePrompt = "x1", batchCount = 1) } }
        onMain { vm2.queueGeneration() }
        onMain { TestApp.vm.updateState { it.copy(positivePrompt = "x2", batchCount = 1) } }
        onMain { TestApp.vm.queueGeneration() }
        awaitUntil("all sent", 30_000) { TestApp.forge.calls("/sdapi/v1/txt2img").size >= 3 && vm2.generationQueue.value.isEmpty() }
        println("[G21-04] maxActive=$maxActive txt2img=${TestApp.forge.calls("/sdapi/v1/txt2img").size} statuses=$before")
        assertEquals(1, maxActive)
        assertEquals("saved job + two new ones, each once", 3, TestApp.forge.calls("/sdapi/v1/txt2img").size)
    }

    @Test fun `05 the screen does not wait for the server (2_0_0)`() {
        val a = TestApp.afterInit
        println("[G21-05] local=${a.localMs} ms, with the server=${a.ms} ms")
        assertTrue("the phone's part did not wait for the slow model list: ${a.localMs} ms", a.localMs < 1500)
        assertTrue(a.ms >= 1500)
    }
}
