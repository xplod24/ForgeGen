package com.example.forgegen

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.0.0: what a checkpoint is (SD, SDXL, FLUX), the modules it needs, and its own defaults. */
class ModelSettingsTest {
    private val payload =
        Txt2ImgPayloadDto(
            prompt = "1girl",
            negative_prompt = "",
            steps = 20,
            cfg_scale = 7f,
            width = 1024,
            height = 1024,
            n_iter = 1,
            batch_size = 1,
            seed = -1L,
            sampler_name = "Euler",
            scheduler = "Simple",
            override_settings = OverrideSettingsDto(clipSkip = 1, sdModelCheckpoint = "flux1-dev.safetensors [abc123]"),
            enable_hr = false,
            hr_scale = 2f,
            hr_upscaler = "Latent",
            denoising_strength = 0.7f,
            save_images = true,
            send_images = true,
        )

    private val flux =
        ModelSettings(
            type = ModelType.FLUX.name,
            vae = "ae.safetensors",
            textEncoders = listOf("clip_l.safetensors", "t5xxl_fp8_e4m3fn.safetensors"),
            distilledCfg = 3f,
        )

    @Test
    fun `a model's settings are kept under its file name, whatever form the name comes in`() {
        assertEquals("flux1-dev", ModelSettingsRules.key("flux1-dev.safetensors [abc123]"))
        assertEquals("flux1-dev", ModelSettingsRules.key("FLUX/flux1-dev.safetensors"))
        assertEquals("flux1-dev-Q8", ModelSettingsRules.key("C:\\models\\flux1-dev-Q8.gguf"))
        assertEquals("animagineXL31", ModelSettingsRules.key("animagineXL31"))
        val all = mapOf("flux1-dev" to flux)
        assertEquals(flux, ModelSettingsRules.of(all, "flux1-dev.safetensors [abc123]"))
        assertEquals("a model never set up is Auto", ModelSettings(), ModelSettingsRules.of(all, "other"))
        assertEquals(ModelSettings(), ModelSettingsRules.of(all, null))
    }

    @Test
    fun `the server's modules are told apart by their folder`() {
        assertEquals(
            ServerModule("ae.safetensors", ServerModule.Kind.VAE),
            ModelSettingsRules.module("ae.safetensors", "/forge/models/VAE/ae.safetensors"),
        )
        assertEquals(
            ServerModule.Kind.TEXT_ENCODER,
            ModelSettingsRules.module("t5xxl.safetensors", "/forge/models/text_encoder/t5xxl.safetensors")!!.kind,
        )
        assertEquals(ServerModule.Kind.TEXT_ENCODER, ModelSettingsRules.module(null, "D:\\forge\\models\\clip\\clip_l.safetensors")!!.kind)
        assertEquals(ServerModule("x.bin", ServerModule.Kind.OTHER), ModelSettingsRules.module(null, "/elsewhere/x.bin"))
        assertNull(ModelSettingsRules.module(null, null))

        val modules =
            listOf(
                ServerModule("ae", ServerModule.Kind.VAE),
                ServerModule("t5", ServerModule.Kind.TEXT_ENCODER),
                ServerModule("odd", ServerModule.Kind.OTHER),
            )
        assertEquals(listOf("ae", "odd"), ModelSettingsRules.vaes(modules))
        assertEquals(listOf("t5", "odd"), ModelSettingsRules.textEncoders(modules))
    }

    @Test
    fun `Forge gets FLUX's VAE and text encoders per job, and its distilled CFG`() {
        val sent = ModelSettingsRules.applyTo(payload, flux, ModuleSupport.FORGE)
        assertEquals(
            listOf("ae.safetensors", "clip_l.safetensors", "t5xxl_fp8_e4m3fn.safetensors"),
            sent.override_settings.forgeAdditionalModules,
        )
        assertEquals(3f, sent.distilled_cfg_scale)
        val json = Gson().toJson(sent)
        assertTrue(json, json.contains("\"forge_additional_modules\":[\"ae.safetensors\""))
        assertTrue(json, json.contains("\"distilled_cfg_scale\":3.0"))

        val remade = ModelSettingsRules.applyTo(payload.copy(distilled_cfg_scale = 4.5f), flux, ModuleSupport.FORGE)
        assertEquals("an image's own distilled CFG is kept", 4.5f, remade.distilled_cfg_scale)
    }

    @Test
    fun `SD sends its VAE, SDXL the built-in one, Auto nothing`() {
        val sd = ModelSettings(type = ModelType.SD.name, vae = "vae-ft-mse.safetensors")
        assertEquals(
            listOf("vae-ft-mse.safetensors"),
            ModelSettingsRules.applyTo(payload, sd, ModuleSupport.FORGE).override_settings.forgeAdditionalModules,
        )
        assertEquals(
            emptyList<String>(),
            ModelSettingsRules.applyTo(payload, sd.copy(vae = null), ModuleSupport.FORGE).override_settings.forgeAdditionalModules,
        )
        val sdxl = ModelSettings(type = ModelType.SDXL.name, vae = "ignored")
        assertEquals(
            emptyList<String>(),
            ModelSettingsRules.applyTo(payload, sdxl, ModuleSupport.FORGE).override_settings.forgeAdditionalModules,
        )
        assertNull("only FLUX has a distilled CFG", ModelSettingsRules.applyTo(payload, sdxl, ModuleSupport.FORGE).distilled_cfg_scale)

        assertEquals("Auto sends nothing", payload, ModelSettingsRules.applyTo(payload, ModelSettings(vae = "ae"), ModuleSupport.FORGE))
        assertFalse(Gson().toJson(payload).contains("forge_additional_modules"))
    }

    @Test
    fun `A1111 takes one VAE by name, and nothing when the server lists no modules`() {
        val sd = ModelSettings(type = ModelType.SD.name, vae = "vae-ft-mse.safetensors")
        val a1111 = ModelSettingsRules.applyTo(payload, sd, ModuleSupport.A1111).override_settings
        assertEquals("vae-ft-mse.safetensors", a1111.sdVae)
        assertNull(a1111.forgeAdditionalModules)
        assertEquals(
            ModelSettingsRules.AUTOMATIC_VAE,
            ModelSettingsRules.applyTo(payload, ModelSettings(type = ModelType.SDXL.name), ModuleSupport.A1111).override_settings.sdVae,
        )
        assertNull(ModelSettingsRules.applyTo(payload, flux, ModuleSupport.A1111).override_settings.sdVae)
        assertEquals(payload.override_settings, ModelSettingsRules.applyTo(payload, flux, ModuleSupport.NONE).override_settings)
    }

    @Test
    fun `the model row sums the settings up`() {
        assertEquals("Model", ModelSettingsRules.summary(ModelSettings()))
        assertEquals("Model · SDXL", ModelSettingsRules.summary(ModelSettings(type = ModelType.SDXL.name, vae = "hidden")))
        assertEquals("Model · FLUX · ae · 2 text encoders", ModelSettingsRules.summary(flux))
        assertEquals(
            "Model · SD · defaults",
            ModelSettingsRules.summary(ModelSettings(type = ModelType.SD.name, useDefaults = true, defaults = ModelDefaults())),
        )
        assertEquals("not without saved defaults", "Model", ModelSettingsRules.summary(ModelSettings(useDefaults = true)))
    }

    @Test
    fun `a model's defaults are the main screen's settings and come back as they were`() {
        val state =
            AppState(
                width = 832,
                height = 1216,
                aspectRatio = "9:16",
                steps = 28,
                cfgScale = 5.5f,
                sampler = "DPM++ 2M",
                scheduler = "Karras",
                clipSkip = 2,
            )
        val defaults = ModelSettingsRules.defaultsOf(state)
        assertEquals(ModelDefaults(832, 1216, 28, 5.5f, "DPM++ 2M", "Karras", 2), defaults)
        val applied = ModelSettingsRules.applyDefaults(AppState(positivePrompt = "kept"), defaults)
        assertEquals("kept", applied.positivePrompt)
        assertEquals(state.copy(positivePrompt = "kept", aspectRatio = "Custom"), applied)
        assertEquals("832×1216 · 28 steps · CFG 5.5 · DPM++ 2M · Karras · clip skip 2", ModelSettingsRules.describe(defaults))
        assertEquals("7", ModelSettingsRules.formatCfg(7f))
    }

    @Test
    fun `settings saved by an older version read with their defaults`() {
        val loaded = ForgeSettingsManager.loadConfig("""{"modelSettings":{"m":{"type":"SD"}}}""")
        val settings = loaded.modelSettings.getValue("m")
        assertEquals(ModelType.SD, settings.modelType)
        assertEquals(emptyList<String>(), settings.textEncoders)
        assertEquals(ModelSettings.DEFAULT_DISTILLED_CFG, settings.distilledCfg)
        assertEquals(ModelType.AUTO, ModelType.of("UNKNOWN"))
        assertEquals(emptyList<String>(), ForgeSettingsManager.loadConfig("{}").mainOpenRows)
    }

    @Test
    fun `the aspect-ratio chips follow the model's size`() {
        assertTrue(SizePresets.isLarge(ModelType.SDXL, 512, 512))
        assertFalse(SizePresets.isLarge(ModelType.SD, 1024, 1024))
        assertTrue(SizePresets.isLarge(ModelType.AUTO, 832, 1216))
        assertFalse(SizePresets.isLarge(ModelType.AUTO, 512, 768))
        val state = AppState(width = 1000, height = 1000)
        assertEquals(state.copy(aspectRatio = "16:9", width = 1344, height = 768), SizePresets.apply(state, "16:9", large = true))
        assertEquals(state.copy(aspectRatio = "4:3", width = 768, height = 576), SizePresets.apply(state, "4:3", large = false))
        assertEquals(state, SizePresets.apply(state, "Custom", large = true))
    }
}
