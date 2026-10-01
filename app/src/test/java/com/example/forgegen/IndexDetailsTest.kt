package com.example.forgegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 3.6.0: the generation settings the gallery index keeps from each image's infotext. */
class IndexDetailsTest {
    private fun details(params: String) = IndexDetails.of(Infotext.parse("a cat\nNegative prompt: lowres\n$params"))

    @Test
    fun `an SDXL image with hires fix and embeddings`() {
        val d =
            details(
                "Steps: 28, Sampler: Euler a, Schedule type: Karras, CFG scale: 5, Seed: 1, Size: 832x1216, " +
                    "Model: animagineXL31, Module 1: sdxl_vae, Denoising strength: 0.35, Hires upscale: 1.5, Hires steps: 15, " +
                    "Hires upscaler: 4x-UltraSharp, TI: \"easynegative, badhandv4\", Version: neo-2.1",
            )
        assertEquals(832, d.width)
        assertEquals(1216, d.height)
        assertEquals("832×1216", d.size)
        assertEquals(28, d.steps)
        assertEquals(5f, d.cfg)
        assertEquals("Karras", d.scheduler)
        assertEquals(1.5f, d.hiresScale)
        assertEquals("4x-UltraSharp", d.hiresUpscaler)
        assertEquals(15, d.hiresSteps)
        assertEquals(0.35f, d.denoising)
        assertEquals("sdxl_vae", d.modules)
        assertEquals("easynegative,badhandv4", d.embeddings)
        assertEquals("neo-2.1", d.forgeVersion)
        assertNull("no Distilled CFG for SDXL", d.distilledCfg)
        assertNull(d.clipSkip)
    }

    @Test
    fun `a FLUX image keeps its modules in their order and the distilled CFG`() {
        val d =
            details(
                "Steps: 20, Sampler: Euler, Schedule type: Simple, CFG scale: 1, Distilled CFG Scale: 3.5, Seed: 7, " +
                    "Size: 1024x1024, Model: flux1-dev, Module 2: clip_l, Module 1: ae, Module 3: t5xxl_fp8",
            )
        assertEquals("ae, clip_l, t5xxl_fp8", d.modules)
        assertEquals(3.5f, d.distilledCfg)
        assertEquals(1f, d.cfg)
        assertNull("no hires fix", d.hiresScale)
        assertNull(d.denoising)
    }

    @Test
    fun `an image without generation data has no details`() {
        assertEquals(IndexDetails(), IndexDetails.of(Infotext.parse("")))
        assertEquals(IndexDetails(), IndexDetails.of(Infotext.parse("just a prompt")))
    }

    @Test
    fun `odd values are left out instead of guessed`() {
        val d = details("Steps: many, Sampler: Euler, CFG scale: NaN, Size: big, Clip skip: 2, Seed: 1")
        assertNull(d.steps)
        assertNull(d.cfg)
        assertNull(d.width)
        assertNull(d.size)
        assertEquals(2, d.clipSkip)
    }

    @Test
    fun `the entity takes the details and their version`() {
        val entity =
            GalleryImageEntity("p", "n", "2026-10-01 12:00:00", "a cat", "", "m", "Euler", "1", "", savedAt = 1L)
                .withDetails(details("Steps: 30, Sampler: Euler, Seed: 1, Size: 512x768"))
        assertEquals(30, entity.steps)
        assertEquals(512, entity.width)
        assertEquals(IndexDetails.VERSION, entity.details)
    }
}
