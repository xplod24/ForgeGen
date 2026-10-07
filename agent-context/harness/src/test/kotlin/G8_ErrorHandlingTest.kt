package com.example.forgegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class G8_ErrorHandlingTest {
    companion object {
        @BeforeClass @JvmStatic fun init() { TestApp.start() }
    }

    @Test fun `zwykly blad 500 wstrzymuje kolejke z powodem, nie jako OOM, i da sie ja wznowic`() {
        val vm = TestApp.vm
        onMain { vm.resumeQueue() } // independent of the other test's leftovers
        TestApp.forge.generationMs = 600
        val before = TestApp.forge.calls("/sdapi/v1/txt2img").size
        TestApp.forge.txt2imgStatus = 500
        TestApp.forge.txt2imgBody = """{"error":"RuntimeError","detail":"","errors":"Sampler 'Foo' not found"}"""
        onMain { vm.updateState { it.copy(positivePrompt = "first") } }
        onMain { vm.queueGeneration() }
        onMain { vm.updateState { it.copy(positivePrompt = "second") } }
        onMain { vm.queueGeneration() }
        awaitUntil("pierwsze zadanie zakończone błędem") { vm.isQueuePaused.value }
        Thread.sleep(1200) // the ping loop rewrites statusText every second; the reason must survive
        println("[G8] status='${ForgeQueueManager.statusText.value}' reason='${vm.queuePauseReason.value}' oom=${vm.oomAlert.value}")
        assertFalse("Zwykły błąd 500 oznaczony jako brak pamięci GPU", vm.oomAlert.value)
        // With the server's own explanation since 2.4.1.
        assertEquals("The server returned HTTP 500. RuntimeError: Sampler 'Foo' not found", vm.queuePauseReason.value)
        assertEquals(listOf("second"), vm.generationQueue.value.map { it.positivePrompt })
        assertEquals("zadanie nie rusza przy pauzie", before + 1, TestApp.forge.calls("/sdapi/v1/txt2img").size)

        TestApp.forge.txt2imgStatus = 200
        TestApp.forge.txt2imgBody = null
        onMain { vm.resumeQueue() }
        awaitUntil("drugie zadanie po wznowieniu") { TestApp.forge.calls("/sdapi/v1/txt2img").size == before + 2 && vm.generationQueue.value.isEmpty() && !vm.isGenerating.value }
        assertTrue(vm.sessionImages.value.isNotEmpty())
        assertFalse(vm.isQueuePaused.value)
    }

    @Test fun `prawdziwy OOM jest rozpoznany, a pusta kolejka nie zostaje zablokowana`() {
        val vm = TestApp.vm
        onMain { vm.resumeQueue() }
        TestApp.forge.txt2imgStatus = 500
        TestApp.forge.txt2imgBody = """{"error":"OutOfMemoryError","errors":"CUDA out of memory. Tried to allocate 2.00 GiB"}"""
        val before = TestApp.forge.calls("/sdapi/v1/txt2img").size
        onMain { vm.queueGeneration() }
        awaitUntil("zadanie OOM") { TestApp.forge.calls("/sdapi/v1/txt2img").size == before + 1 && !vm.isGenerating.value && vm.generationQueue.value.isEmpty() }
        assertTrue("Prawdziwy OOM nie został rozpoznany", vm.oomAlert.value)
        assertFalse("Pusta kolejka pozostała wstrzymana", vm.isQueuePaused.value)

        TestApp.forge.txt2imgStatus = 200
        TestApp.forge.txt2imgBody = null
        onMain { vm.queueGeneration() }
        awaitUntil("kolejne zadanie startuje od razu") { TestApp.forge.calls("/sdapi/v1/txt2img").size == before + 2 }
    }
}
