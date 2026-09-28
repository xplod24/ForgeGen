package com.example.forgegen

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.4.0: gallery images remade as txt2img jobs ("Upscale Selected", "More Like This"). */
class ImageJobsTest {
    private val models =
        listOf(
            // The owner's server lists full SHA-256 hashes; the fallback lists "name.safetensors [short hash]".
            ApiResource(
                title = "animagineXL31",
                path = "/m/animagineXL31.safetensors",
                name = "animagineXL31",
                hash = "1449e5b0b9aabbccdd",
            ),
            ApiResource(title = "ponyDiffusion.safetensors [ab12cd34ef]", path = "/m/ponyDiffusion.safetensors", name = "ponyDiffusion"),
        )

    private fun info(params: String) = Infotext.parse("1girl, <lora:detail:0.6>, smile\nNegative prompt: lowres\n$params")

    private fun ready(params: String) = ImageJobs.remake(info(params), models, "current") as ImageJobs.Source.Ready

    private val base =
        "Steps: 28, Sampler: DPM++ 2M, Schedule type: Karras, CFG scale: 6.5, Seed: 1234567, Size: 832x1216, " +
            "Model hash: 1449e5b0b9, Model: animagineXL31, Clip skip: 2, ENSD: 31337, RNG: CPU, Version: f2.0.1"

    @Test
    fun `an image's job has its prompt, seed, settings and model`() {
        val s = ready(base)
        val p = s.payload
        assertEquals("1girl, <lora:detail:0.6>, smile", p.prompt)
        assertEquals("lowres", p.negative_prompt)
        assertEquals(28, p.steps)
        assertEquals("DPM++ 2M", p.sampler_name)
        assertEquals("Karras", p.scheduler)
        assertEquals(6.5f, p.cfg_scale)
        assertEquals(1234567L, p.seed)
        assertEquals(832 to 1216, p.width to p.height)
        assertEquals(1 to 1, p.n_iter to p.batch_size)
        assertEquals("animagineXL31", p.override_settings.sdModelCheckpoint)
        assertEquals(2, p.override_settings.clipSkip)
        assertEquals(31337, p.override_settings.etaNoiseSeedDelta)
        assertEquals("CPU", p.override_settings.randnSource)
        assertFalse(p.enable_hr)
        assertTrue("the results belong in the gallery", p.save_images)
        assertNull(s.hiresScale)
    }

    @Test
    fun `the model is found by its hash, else by its name, and a missing one leaves the image out`() {
        assertEquals("ponyDiffusion.safetensors [ab12cd34ef]", ImageJobs.findModel(models, "renamed", "AB12CD34EF")?.title)
        assertEquals("ponyDiffusion.safetensors [ab12cd34ef]", ImageJobs.findModel(models, "ponyDiffusion", null)?.title)
        assertEquals("animagineXL31", ImageJobs.findModel(models, "ANIMAGINEXL31", null)?.title)
        val missing = ImageJobs.remake(info("Steps: 20, Seed: 1, Size: 512x512, Model: sd15"), models, "current")
        assertEquals(ImageJobs.Source.Skipped("the server has no model sd15"), missing)
        // Data without a model: the current one.
        val noModel = ImageJobs.remake(info("Steps: 20, Seed: 1, Size: 512x512, Sampler: Euler a"), models, "current")
        assertEquals("current", (noModel as ImageJobs.Source.Ready).payload.override_settings.sdModelCheckpoint)
        assertEquals(1, noModel.payload.override_settings.clipSkip)
        assertNull("not sent when the image does not name it", noModel.payload.override_settings.etaNoiseSeedDelta)
    }

    @Test
    fun `images that cannot be made again say why`() {
        assertEquals(ImageJobs.Source.Skipped(ImageJobs.NO_DATA), ImageJobs.remake(Infotext.parse("just a caption"), models, null))
        assertEquals(
            ImageJobs.Source.Skipped("its data has no seed"),
            ImageJobs.remake(info("Steps: 20, Sampler: Euler a, Size: 512x512"), models, null),
        )
        assertEquals(
            ImageJobs.Source.Skipped("its data has no size"),
            ImageJobs.remake(info("Steps: 20, Sampler: Euler a, Seed: 5"), models, null),
        )
        assertEquals(
            ImageJobs.Source.Skipped("the server's models are not loaded"),
            ImageJobs.remake(info(base), emptyList(), null),
        )
    }

    @Test
    fun `an upscale is the same image with hires fix, and not smaller than the image already is`() {
        val plain = ready(base)
        val up = ImageJobs.upscale(plain, 2f, "R-ESRGAN 4x+ Anime6B", 0.35f)!!
        assertTrue(up.enable_hr)
        assertEquals(2f, up.hr_scale)
        assertEquals("R-ESRGAN 4x+ Anime6B", up.hr_upscaler)
        assertEquals(0.35f, up.denoising_strength)
        assertEquals(plain.payload.seed to plain.payload.width, up.seed to up.width)
        assertEquals(1664 to 2432, ImageJobs.upscaledSize(plain, 2f))

        val hires = ready("$base, Denoising strength: 0.4, Hires upscale: 2, Hires steps: 12, Hires upscaler: Latent")
        assertEquals(2f, hires.hiresScale)
        assertTrue("its hires fix stays for More Like This", hires.payload.enable_hr)
        assertEquals(12, hires.payload.hr_second_pass_steps)
        assertNull("already ×2", ImageJobs.upscale(hires, 2f, "Latent", 0.35f))
        assertNull(ImageJobs.upscale(hires, 1.5f, "Latent", 0.35f))
        assertEquals(2.5f, ImageJobs.upscale(hires, 2.5f, "Latent", 0.35f)!!.hr_scale)
        assertEquals(832 to 1216, hires.baseWidth to hires.baseHeight)

        val resized = ready("$base, Hires resize: 1248x1824, Hires upscaler: Latent")
        assertEquals(1.5f, resized.hiresScale)
        assertEquals("Upscale ×2", ImageJobs.upscaleLabel(2f))
        assertEquals("Upscale ×1.5", ImageJobs.upscaleLabel(1.5f))
    }

    @Test
    fun `More Like This takes the seeds next to the image's, in pairs, within 10 and never below 0`() {
        assertEquals(listOf(1, -1, 2, -2, 3, -3), ImageJobs.seedOffsets(100, 6))
        assertEquals((1..10).flatMap { listOf(it, -it) }, ImageJobs.seedOffsets(1000, 20))
        assertEquals(listOf(1, -1, 2, 3), ImageJobs.seedOffsets(1, 4))
        assertEquals(13, ImageJobs.seedOffsets(3, 20).size)
        assertEquals(listOf(-1, -2, -3), ImageJobs.seedOffsets(4_294_967_295L, 3))
    }

    @Test
    fun `Similar keeps the seed and varies the variation seed, Neighbouring Seeds varies the seed`() {
        val s = ready(base)
        val similar = ImageJobs.moreLikeThis(s, similar = true, count = 4, strength = 0.2f)
        assertEquals(listOf(1234567L), similar.map { it.first.seed }.distinct())
        assertEquals(listOf(1234568L, 1234566L, 1234569L, 1234565L), similar.map { it.first.subseed })
        assertTrue(similar.all { it.first.subseed_strength == 0.2f })
        assertEquals("More Like This · 1234567 +1", similar[0].second)
        assertEquals("More Like This · 1234567 −1", similar[1].second)

        val neighbours = ImageJobs.moreLikeThis(s, similar = false, count = 2, strength = 0.2f)
        assertEquals(listOf(1234568L, 1234566L), neighbours.map { it.first.seed })
        assertTrue(neighbours.all { it.first.subseed == -1L && it.first.subseed_strength == 0f })
    }

    @Test
    fun `the job is sent with the new fields and without the settings the image did not name`() {
        val json = Gson().toJson(ImageJobs.moreLikeThis(ready(base), true, 2, 0.15f).first().first)
        assertTrue(json, json.contains("\"subseed\":1234568"))
        assertTrue(json, json.contains("\"subseed_strength\":0.15"))
        assertTrue(json, json.contains("\"eta_noise_seed_delta\":31337"))
        val plain =
            Gson().toJson(
                (ImageJobs.remake(info("Steps: 20, Seed: 1, Size: 512x512"), models, null) as ImageJobs.Source.Ready).payload,
            )
        assertFalse(plain, plain.contains("eta_noise_seed_delta"))
        assertFalse(plain, plain.contains("randn_source"))
        assertFalse(plain, plain.contains("sd_model_checkpoint"))
    }

    @Test
    fun `a job with hires fix goes to the server with Forge's hires modules, one without it unchanged`() {
        // 2.4.1: Forge's hires pass fails with HTTP 500 without "hr_additional_modules".
        val upscale = ImageJobs.upscale(ready(base), 2f, "Latent", 0.35f)!!
        assertNull("not stored with the job", upscale.hr_additional_modules)
        val json = Gson().toJson(upscale.forServer())
        assertTrue(json, json.contains("\"hr_additional_modules\":[\"Use same choices\"]"))

        val plain = ready(base).payload
        assertEquals(plain, plain.forServer())
        assertFalse(Gson().toJson(plain.forServer()).contains("hr_additional_modules"))

        val chosen = upscale.copy(hr_additional_modules = listOf("ae.safetensors"))
        assertEquals(chosen, chosen.forServer())
    }

    // --- Variance on Seed (3.0.0) ---

    private val loraSpec = ImageJobs.VarianceSpec(loras = mapOf("detail" to ImageJobs.Spread(0.2f, 0.1f)))

    @Test
    fun `Variance on Seed varies a LoRA's weight around the image's own and leaves the image itself out`() {
        val source = ready(base)
        val jobs = ImageJobs.variance(source, loraSpec)
        assertEquals(
            listOf("0.4", "0.5", "0.7", "0.8"),
            jobs.map { (payload, _) ->
                Regex("<lora:detail:([0-9.]+)>").find(payload.prompt)!!.groupValues[1]
            },
        )
        assertTrue("the seed stays", jobs.all { (payload, _) -> payload.seed == 1234567L })
        assertEquals("Variance · 1234567 · detail 0.4", jobs.first().second)
        assertEquals(4, ImageJobs.varianceCount(listOf(source), loraSpec))
    }

    @Test
    fun `every combination of the varied settings is one job, labelled with what changed`() {
        val spec = loraSpec.copy(cfg = ImageJobs.Spread(0.5f, 0.5f), steps = ImageJobs.Spread(5f, 5f))
        val source = ready(base)
        val jobs = ImageJobs.variance(source, spec)
        assertEquals(5 * 3 * 3 - 1, jobs.size)
        assertEquals(jobs.size, ImageJobs.varianceCount(listOf(source), spec))
        assertEquals("every job differs", jobs.size, jobs.map { it.first }.distinct().size)
        val job =
            jobs.single { (payload, _) ->
                payload.cfg_scale == 7f &&
                    payload.steps == 23 &&
                    payload.prompt.contains("<lora:detail:0.6>")
            }
        assertEquals("Variance · 1234567 · CFG 7 · 23 steps", job.second)
    }

    @Test
    fun `limits keep the values sensible and a LoRA the image does not use is not varied`() {
        val low =
            ImageJobs.remake(Infotext.parse("<lora:detail:-3.9>\n" + base.replace("CFG scale: 6.5", "CFG scale: 1")), models, null)
                as ImageJobs.Source.Ready
        val spec =
            ImageJobs.VarianceSpec(
                loras = mapOf("detail" to ImageJobs.Spread(0.2f, 0.1f), "other" to ImageJobs.Spread(0.5f, 0.1f)),
                cfg = ImageJobs.Spread(1f, 0.5f),
            )
        val jobs = ImageJobs.variance(low, spec)
        val weights = jobs.map { Regex("<lora:detail:(-?[0-9.]+)>").find(it.first.prompt)!!.groupValues[1].toFloat() }.distinct().sorted()
        assertEquals(listOf(-4f, -3.9f, -3.8f, -3.7f), weights)
        assertTrue(jobs.all { it.first.cfg_scale in 1f..2f })
        assertEquals(4 * 3 - 1, jobs.size)
        assertEquals(0, ImageJobs.varianceCount(listOf(low), ImageJobs.VarianceSpec()))
    }

    @Test
    fun `the LoRAs of the selected images are listed with the weights they use`() {
        val a = ready(base)
        val b = ImageJobs.remake(Infotext.parse("<lora:detail:0.8>, <lora:flat:1>\n$base"), models, null) as ImageJobs.Source.Ready
        assertEquals(mapOf("detail" to listOf(0.6f, 0.8f), "flat" to listOf(1f)), ImageJobs.lorasOf(listOf(a, b)))
        assertEquals(
            "1girl, <lora:detail:0.75>, x <lora:detail:0.75>",
            ImageJobs.withLoraWeight("1girl, <lora:detail:0.6>, x <lora:detail:1>", "detail", 0.75f),
        )
        assertEquals("7", ImageJobs.weightText(7f))
        assertEquals("1.25", ImageJobs.weightText(1.25f))
    }
}
