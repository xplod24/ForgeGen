package com.example.forgegen

import org.junit.Assert.assertEquals
import org.junit.Test

class G12_LastPromptFallbackTest {
    @Test fun `odzyskanie ostatniego promptu z lokalnej kopii gdy galeria niedostepna`() {
        val vm = TestApp.start()
        onMain { vm.queueGeneration() } // server returns an image whose metadata is INFOTEXT
        awaitUntil("obraz") { vm.sessionImages.value.isNotEmpty() && !vm.isGenerating.value }
        onMain { vm.updateState { it.copy(positivePrompt = "changed", steps = 5) } }
        onMain { vm.recoverLastPrompt() } // no gallery extension (404) and no Physton history on the server
        awaitUntil("koniec odzyskiwania", 10_000) { vm.isRestoringPrompt.value == com.example.forgegen.ui.components.IndicatorState.IDLE }
        Thread.sleep(300)
        println("[G12] prompt po odzyskaniu='${vm.appState.value.positivePrompt}' steps=${vm.appState.value.steps}")
        assertEquals("masterpiece, best quality, <lora:detail:0.8>", vm.appState.value.positivePrompt)
        assertEquals(28, vm.appState.value.steps)
    }
}
