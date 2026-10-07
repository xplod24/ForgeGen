package com.example.forgegen

import com.sun.net.httpserver.HttpExchange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.BeforeClass
import org.junit.Test

/** 3.0.0: a server without Forge's module list (A1111): its VAEs from /sdapi/v1/sd-vae, one sent in sd_vae. */
class G40_ModulesA1111Test {
    companion object {
        @BeforeClass @JvmStatic fun init() {
            TestApp.start(custom = { ex: HttpExchange, path, _ ->
                if (path == "/sdapi/v1/sd-vae") {
                    val bytes = """[{"model_name":"vae-ft-mse","filename":"/sd/models/VAE/vae-ft-mse.safetensors"}]""".toByteArray()
                    ex.sendResponseHeaders(200, bytes.size.toLong())
                    ex.responseBody.use { it.write(bytes) }
                    true
                } else {
                    false
                }
            })
        }
    }

    @Test fun `an SD model on A1111 sends its VAE by name`() {
        val vm = TestApp.vm
        awaitUntil("modules") { vm.moduleSupport.value == ModuleSupport.A1111 }
        assertEquals(listOf(ServerModule("vae-ft-mse", ServerModule.Kind.VAE)), vm.serverModules.value)

        onMain { vm.updateModelSettings(vm.selectedModel.value) { it.copy(type = ModelType.SD.name, vae = "vae-ft-mse") } }
        awaitUntil("saved") { ModelSettingsRules.of(vm.config.value.modelSettings, vm.selectedModel.value).vae != null }
        val sent = TestApp.forge.txt2imgPayloads().size
        onMain { vm.updateState { it.copy(positivePrompt = "a cat", batchCount = 1) } }
        onMain { vm.queueGeneration() }
        awaitUntil("sent", 20_000) { TestApp.forge.txt2imgPayloads().size == sent + 1 }
        val o = TestApp.forge.txt2imgPayloads().last().getJSONObject("override_settings")
        assertEquals("vae-ft-mse", o.getString("sd_vae"))
        assertFalse(o.has("forge_additional_modules"))
    }
}
