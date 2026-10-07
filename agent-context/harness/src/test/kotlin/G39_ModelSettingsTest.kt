package com.example.forgegen

import com.sun.net.httpserver.HttpExchange
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 3.0.0: a checkpoint's type and modules (Forge's /sdapi/v1/sd-modules, sent per job in forge_additional_modules),
 * FLUX's distilled CFG, a model's own defaults, and "Variance on Seed" from gallery images.
 */
@OptIn(DelicateCoroutinesApi::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class G39_ModelSettingsTest {
    companion object {
        val vm get() = TestApp.vm
        val toasts = CopyOnWriteArrayList<String>()

        private fun json(ex: HttpExchange, body: String): Boolean {
            val bytes = body.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
            return true
        }

        // Forge Neo's list: VAEs and text encoders from their folders (modules_list in Forge's main_entry.py).
        const val MODULES =
            """[{"model_name":"ae.safetensors","filename":"/forge/models/VAE/ae.safetensors"},""" +
                """{"model_name":"clip_l.safetensors","filename":"/forge/models/text_encoder/clip_l.safetensors"},""" +
                """{"model_name":"t5xxl_fp8.safetensors","filename":"/forge/models/text_encoder/t5xxl_fp8.safetensors"}]"""

        @BeforeClass @JvmStatic fun init() {
            TestApp.start(custom = { ex, path, body ->
                if (path == "/sdapi/v1/sd-modules") json(ex, MODULES) else MockIib.route(ex, path, body)
            })
            GlobalScope.launch(Dispatchers.IO) { ForgeSettingsManager.snackbarMessage.collect { toasts += it } }
            GlobalScope.launch(Dispatchers.IO) { vm.toastMessage.collect { toasts += it } }
        }

        fun payloads() = TestApp.forge.txt2imgPayloads()

        fun idle() = awaitUntil("queue done", 30_000) {
            (vm.generationQueue.value.isEmpty() || vm.isQueuePaused.value) && !vm.isGenerating.value
        }

        fun send(): org.json.JSONObject {
            val sent = payloads().size
            onMain { vm.queueGeneration() }
            awaitUntil("sent", 20_000) { payloads().size == sent + 1 }
            idle()
            return payloads().drop(sent).single()
        }
    }

    @Test fun `01 the server's VAEs and text encoders are read when it connects`() {
        awaitUntil("modules") { vm.serverModules.value.isNotEmpty() }
        assertEquals(ModuleSupport.FORGE, vm.moduleSupport.value)
        assertEquals(
            listOf(
                ServerModule("ae.safetensors", ServerModule.Kind.VAE),
                ServerModule("clip_l.safetensors", ServerModule.Kind.TEXT_ENCODER),
                ServerModule("t5xxl_fp8.safetensors", ServerModule.Kind.TEXT_ENCODER),
            ),
            vm.serverModules.value,
        )
    }

    @Test fun `02 a model left on Auto sends no modules, as before`() {
        onMain { vm.updateState { it.copy(positivePrompt = "a cat", batchCount = 1, hiresFix = false) } }
        val p = send()
        assertFalse(p.toString(), p.getJSONObject("override_settings").has("forge_additional_modules"))
        assertFalse(p.has("distilled_cfg_scale"))
    }

    @Test fun `03 a FLUX model sends its VAE, text encoders and distilled CFG with every job`() {
        val model = vm.selectedModel.value
        onMain {
            vm.updateModelSettings(model) {
                it.copy(type = ModelType.FLUX.name, vae = "ae.safetensors", textEncoders = listOf("clip_l.safetensors", "t5xxl_fp8.safetensors"), distilledCfg = 3f)
            }
        }
        awaitUntil("saved") { ModelSettingsRules.of(vm.config.value.modelSettings, model).modelType == ModelType.FLUX }
        val p = send()
        println("[G39-03] $p")
        assertEquals(
            listOf("ae.safetensors", "clip_l.safetensors", "t5xxl_fp8.safetensors"),
            p.getJSONObject("override_settings").getJSONArray("forge_additional_modules").toList(),
        )
        assertEquals(3.0, p.getDouble("distilled_cfg_scale"), 0.001)
        assertTrue("kept after a restart", TestApp.db.settings["config"]!!.contains("t5xxl_fp8.safetensors"))
    }

    @Test fun `04 an SD model sends only its VAE, an SDXL one the built-in VAE`() {
        val model = vm.selectedModel.value
        onMain { vm.updateModelSettings(model) { it.copy(type = ModelType.SD.name) } }
        awaitUntil("saved") { ModelSettingsRules.of(vm.config.value.modelSettings, model).modelType == ModelType.SD }
        onMain { vm.updateState { it.copy(positivePrompt = "a dog") } }
        val sd = send()
        assertEquals(listOf("ae.safetensors"), sd.getJSONObject("override_settings").getJSONArray("forge_additional_modules").toList())
        assertFalse("only FLUX has a distilled CFG", sd.has("distilled_cfg_scale"))

        onMain { vm.updateModelSettings(model) { it.copy(type = ModelType.SDXL.name) } }
        awaitUntil("saved") { ModelSettingsRules.of(vm.config.value.modelSettings, model).modelType == ModelType.SDXL }
        val sdxl = send()
        assertEquals(0, sdxl.getJSONObject("override_settings").getJSONArray("forge_additional_modules").length())
        assertFalse(sdxl.has("distilled_cfg_scale"))
    }

    @Test fun `05 a model's defaults are set when it is picked, only when switched on`() {
        onMain { vm.updateState { it.copy(width = 832, height = 1216, steps = 28, cfgScale = 5f, sampler = "DPM++ 2M", clipSkip = 2) } }
        onMain { vm.saveModelDefaults("other") }
        awaitUntil("saved") { ModelSettingsRules.of(vm.config.value.modelSettings, "other").defaults != null }
        assertFalse("saving does not switch them on", ModelSettingsRules.of(vm.config.value.modelSettings, "other").useDefaults)

        onMain { vm.updateState { it.copy(width = 512, height = 512, steps = 20, cfgScale = 7f, sampler = "Euler a", clipSkip = 1) } }
        onMain { vm.changeCheckpoint("other") }
        Thread.sleep(300)
        assertEquals("not switched on: nothing changes", 512, vm.appState.value.width)

        onMain { vm.updateModelSettings("other") { it.copy(useDefaults = true) } }
        toasts.clear()
        onMain { vm.changeCheckpoint("model") }
        onMain { vm.changeCheckpoint("other") }
        awaitUntil("applied") { vm.appState.value.width == 832 }
        val s = vm.appState.value
        assertEquals(listOf(832, 1216, 28, 2), listOf(s.width, s.height, s.steps, s.clipSkip))
        assertEquals(5f, s.cfgScale)
        assertEquals("DPM++ 2M", s.sampler)
        awaitUntil("said") { toasts.any { it.contains("defaults applied") } }
    }

    @Test fun `06 Variance on Seed queues every combination with the image's seed`() {
        MockIib.infos["/out/v.png"] = "a cat, <lora:detail:0.6>\nNegative prompt: ugly\n" +
            "Steps: 20, Sampler: Euler a, Schedule type: Karras, CFG scale: 7, Seed: 4242, Size: 512x512, Model hash: abc123, Model: model"
        onMain { vm.requestImageJobs(ImageJobs.Kind.UPSCALE, listOf(GalleryItem(name = "v.png", fullpath = "/out/v.png", type = "file"))) }
        awaitUntil("read", 10_000) { vm.imageJobs.value?.sources != null }
        onMain { vm.setImageJobsKind(ImageJobs.Kind.VARIANCE) }
        assertEquals(ImageJobs.Kind.VARIANCE, vm.imageJobs.value!!.kind)
        val ready = vm.imageJobs.value!!.sources!!.filterIsInstance<ImageJobs.Source.Ready>()
        assertEquals(mapOf("detail" to listOf(0.6f)), ImageJobs.lorasOf(ready))

        val tooMany = ImageJobs.VarianceSpec(
            loras = mapOf("detail" to ImageJobs.Spread(0.5f, 0.05f)),
            cfg = ImageJobs.Spread(2f, 0.5f),
            steps = ImageJobs.Spread(10f, 5f),
        )
        assertTrue(ImageJobs.varianceCount(ready, tooMany) > ImageJobs.MAX_VARIANCE_JOBS)
        onMain { vm.queueVariance(tooMany) }
        assertTrue("more than 100 is not queued", vm.imageJobs.value != null)

        TestApp.forge.generationMs = 100
        val sent = payloads().size
        val spec = ImageJobs.VarianceSpec(loras = mapOf("detail" to ImageJobs.Spread(0.2f, 0.1f)), cfg = ImageJobs.Spread(0.5f, 0.5f))
        onMain { vm.queueVariance(spec) }
        assertNull("the dialog closes", vm.imageJobs.value)
        awaitUntil("sent", 30_000) { payloads().size == sent + 14 }
        idle()
        val jobs = payloads().drop(sent)
        assertEquals(listOf(4242L), jobs.map { it.getLong("seed") }.distinct())
        val combos = jobs.map { Regex("<lora:detail:([0-9.]+)>").find(it.getString("prompt"))!!.groupValues[1] to it.getDouble("cfg_scale") }.toSet()
        assertEquals(14, combos.size)
        assertFalse("the image itself is not made again", ("0.6" to 7.0) in combos)
        awaitUntil("toast") { toasts.any { it.startsWith("Added 14 jobs") } }
    }
}
