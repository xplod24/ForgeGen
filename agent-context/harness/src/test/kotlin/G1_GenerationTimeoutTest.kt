package com.example.forgegen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class G1_GenerationTimeoutTest {
    @Test fun `generowanie dluzsze niz timeout polaczenia konczy sie obrazem`() {
        val vm = TestApp.start(config = { copy(timeout = 2) })
        TestApp.forge.generationMs = 3500 // e.g. SDXL + hires fix; "Connection Timeout" setting is 2 s
        onMain { vm.queueGeneration() }
        awaitUntil("żądanie txt2img") { TestApp.forge.calls("/sdapi/v1/txt2img").isNotEmpty() }
        awaitUntil("koniec zadania", 15_000) { !vm.isGenerating.value && vm.generationQueue.value.isEmpty() }
        println("[G1] status='${ForgeQueueManager.statusText.value}' paused=${vm.isQueuePaused.value} obrazy=${vm.sessionImages.value.size}")
        assertFalse("Kolejka wstrzymana po przekroczeniu timeoutu odczytu: ${ForgeQueueManager.statusText.value}", vm.isQueuePaused.value)
        assertTrue("Brak obrazu po generowaniu dłuższym niż timeout", vm.sessionImages.value.isNotEmpty())
    }
}
