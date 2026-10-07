package com.example.forgegen

import org.junit.Assert.assertEquals
import org.junit.Test

class G2_ModelSelectionTest {
    @Test fun `model wybrany w UI trafia do override_settings zadania`() {
        val vm = TestApp.start()
        assertEquals("model.safetensors [abc123]", vm.selectedModel.value)
        onMain { vm.changeCheckpoint("other") } // PromptComponents calls changeCheckpoint(mod.name)
        awaitUntil("POST options") { TestApp.forge.calls("/sdapi/v1/options").any { it.method == "POST" } }
        onMain { vm.queueGeneration() }
        awaitUntil("txt2img") { TestApp.forge.calls("/sdapi/v1/txt2img").isNotEmpty() }
        val override = TestApp.forge.txt2imgPayloads().single().getJSONObject("override_settings")
        println("[G2] UI selectedModel='${vm.selectedModel.value}', payload sd_model_checkpoint='${override.optString("sd_model_checkpoint")}'")
        assertEquals("Zadanie wymusza inny model niż wybrany w UI", "other", override.optString("sd_model_checkpoint"))
    }
}
