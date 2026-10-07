package com.example.forgegen

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.File
import java.util.Base64

/** 2.0.0: the session keeps at most 100 images, images are read as they arrive, the screen's state is written in bursts. */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class G32_SessionAndStateTest {
    companion object {
        val vm get() = TestApp.vm

        @BeforeClass @JvmStatic fun init() {
            TestApp.start()
        }

        fun images(n: Int, marker: Int): Pair<String, List<ByteArray>> {
            val pngs = (0 until n).map { Png.withParameters("$INFOTEXT\nmarker $marker-$it") }
            val json = pngs.joinToString(",", "[", "]") { "\"${Base64.getEncoder().encodeToString(it)}\"" }
            return json to pngs
        }

        fun runJob(prompt: String) {
            val before = vm.sessionImages.value.lastOrNull()
            onMain { vm.updateState { it.copy(positivePrompt = prompt, batchCount = 1) } }
            onMain { vm.queueGeneration() }
            awaitUntil("job $prompt done", 20_000) {
                vm.generationQueue.value.isEmpty() && !vm.isGenerating.value && vm.sessionImages.value.lastOrNull() != before
            }
        }
    }

    @Before fun reset() {
        TestApp.forge.txt2imgStatus = 200
        TestApp.forge.txt2imgBody = null
    }

    @Test fun `01 the session keeps the latest 100 images and deletes the older ones`() {
        TestApp.forge.txt2imgBody = """{"images": ${images(60, 1).first}, "parameters": {}, "info": "{}"}"""
        runJob("first sixty")
        val first = vm.sessionImages.value.toList()
        assertEquals(60, first.size)
        TestApp.forge.txt2imgBody = """{"images": ${images(60, 2).first}, "parameters": {}, "info": "{}"}"""
        runJob("second sixty")
        val session = vm.sessionImages.value
        println("[G32-01] ${session.size} images, batch ${vm.currentBatchStartIndex.value}..${vm.currentBatchEndIndex.value}")
        assertEquals(100, session.size)
        assertEquals(40, vm.currentBatchStartIndex.value)
        assertEquals(99, vm.currentBatchEndIndex.value)
        assertEquals(99, vm.currentSessionIndex.value)
        assertTrue("the 20 oldest are deleted", first.take(20).none { File(it).exists() })
        assertTrue("the rest stay", first.drop(20).all { File(it).exists() })
        assertEquals(first.drop(20), session.take(40))
    }

    @Test fun `02 a batch bigger than the limit is kept whole`() {
        val old = vm.sessionImages.value.toList()
        TestApp.forge.txt2imgBody = """{"images": ${images(120, 3).first}, "parameters": {}, "info": "{}"}"""
        runJob("hundred twenty")
        assertEquals(120, vm.sessionImages.value.size)
        assertEquals(0, vm.currentBatchStartIndex.value)
        assertTrue(old.none { File(it).exists() })
    }

    @Test fun `03 images are read wherever they are in the answer, escaped slashes included`() {
        val (_, pngs) = images(2, 4)
        val escaped = pngs.joinToString(",", "[", "]") { "\"${Base64.getEncoder().encodeToString(it).replace("/", "\\/")}\"" }
        TestApp.forge.txt2imgBody =
            """{"parameters": {"prompt": "a \"quoted\" [prompt]", "n": [1, {"x": null}]}, "info": "{\"seed\": 1}", "images": $escaped}"""
        runJob("fields first")
        val files = vm.sessionImages.value.takeLast(2)
        assertArrayEquals(pngs[0], File(files[0]).readBytes())
        assertArrayEquals(pngs[1], File(files[1]).readBytes())
    }

    @Test fun `04 typing writes the state once the typing pauses`() {
        val writes = { TestApp.db.writes["last_state"]?.get() ?: 0 }
        Thread.sleep(1_000)
        val before = writes()
        for (i in 1..60) {
            onMain { vm.updateState { it.copy(positivePrompt = "typed ${"x".repeat(i)}") } }
            Thread.sleep(5)
        }
        awaitUntil("written", 3_000) { TestApp.db.settings["last_state"]?.contains("x".repeat(60)) == true }
        Thread.sleep(800)
        val count = writes() - before
        println("[G32-04] 60 changes -> $count writes")
        assertTrue("$count writes", count in 1..3)
        val saved = TestApp.gson.fromJson(TestApp.db.settings["last_state"], AppState::class.java)
        assertEquals("typed ${"x".repeat(60)}", saved.positivePrompt)
    }

    @Test fun `05 leaving the screen writes a waiting change at once`() {
        Thread.sleep(1_000)
        onMain { vm.updateState { it.copy(positivePrompt = "left right after typing") } }
        onMain { vm.setAppForegroundState(false) }
        awaitUntil("flushed", 300) { TestApp.db.settings["last_state"]?.contains("left right after typing") == true }
        onMain { vm.setAppForegroundState(true) }
        assertFalse(TestApp.db.settings["last_state"].isNullOrEmpty())
    }
}
