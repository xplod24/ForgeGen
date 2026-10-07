package com.example.forgegen

import org.junit.Assert.assertEquals
import org.junit.Test

class G5_PresetTest {
    @Test fun `preset zapisany bez promptow nie nadpisuje biezacych promptow`() {
        val vm = TestApp.start()
        onMain { vm.updateState { it.copy(positivePrompt = "old prompt", negativePrompt = "old neg", steps = 33) } }
        onMain { vm.savePreset("Ustawienia SDXL", includePrompts = false) }
        onMain { vm.updateState { it.copy(positivePrompt = "my new prompt", negativePrompt = "new neg", steps = 10) } }
        onMain { vm.loadPreset("Ustawienia SDXL") }
        assertEquals(33, vm.appState.value.steps)
        assertEquals("my new prompt", vm.appState.value.positivePrompt)
        assertEquals("new neg", vm.appState.value.negativePrompt)
    }
}
