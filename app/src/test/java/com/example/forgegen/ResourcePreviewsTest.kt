package com.example.forgegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** 3.0.1: a model's or LoRA's picture is found under any of the names Forge's extra networks look for. */
class ResourcePreviewsTest {
    private val server = "http://192.168.1.20:7860"

    @Before
    fun clean() = ResourcePreviews.forget()

    @Test
    fun `the model's extension comes off only at the end`() {
        assertEquals("D:\\sd\\models\\Lora\\detail", ResourcePreviews.stem("D:\\sd\\models\\Lora\\detail.safetensors"))
        assertEquals("/m/my.pt_model", ResourcePreviews.stem("/m/my.pt_model.safetensors"))
        assertEquals("/m/old", ResourcePreviews.stem("/m/old.PT"))
        assertEquals("/m/flux1-dev-Q8", ResourcePreviews.stem("/m/flux1-dev-Q8.gguf"))
        assertEquals("/m/no_extension", ResourcePreviews.stem("/m/no_extension"))
    }

    @Test
    fun `every usual name is asked of the thumb route, the path encoded`() {
        val candidates = ResourcePreviews.candidates(server, "D:\\sd\\models\\Lora\\my lora.safetensors")
        assertEquals(ResourcePreviews.SUFFIXES.size, candidates.size)
        assertEquals("$server/sd_extra_networks/thumb?filename=D%3A%5Csd%5Cmodels%5CLora%5Cmy+lora.preview.png", candidates.first())
        assertEquals("$server/sd_extra_networks/thumb?filename=D%3A%5Csd%5Cmodels%5CLora%5Cmy+lora.png", candidates[1])
        assertEquals(emptyList<String>(), ResourcePreviews.candidates("", "/m/a.safetensors"))
        assertEquals(emptyList<String>(), ResourcePreviews.candidates(server, ""))
    }

    @Test
    fun `the name that loaded is remembered, and a model without a picture is not asked again`() {
        val a = ResourcePreviews.candidates(server, "/m/a.safetensors")
        assertEquals(0, ResourcePreviews.startIndex(a))
        assertNull(ResourcePreviews.known(a))
        // ".preview.png" is missing, ".png" loads.
        assertEquals(1, ResourcePreviews.missing(a, 0))
        ResourcePreviews.loaded(a, 1)
        assertEquals(1, ResourcePreviews.startIndex(a))
        assertEquals(a[1], ResourcePreviews.known(a))

        val b = ResourcePreviews.candidates(server, "/m/b.safetensors")
        var index = 0
        repeat(ResourcePreviews.SUFFIXES.size) { index = ResourcePreviews.missing(b, index) }
        assertEquals(ResourcePreviews.NONE, index)
        assertEquals(ResourcePreviews.NONE, ResourcePreviews.startIndex(b))
        assertNull(ResourcePreviews.known(b))

        // A refreshed model list asks again.
        ResourcePreviews.forget()
        assertEquals(0, ResourcePreviews.startIndex(b))
        assertEquals(ResourcePreviews.NONE, ResourcePreviews.startIndex(emptyList()))
    }
}

/** 3.0.1: hires fix's latent modes, which /upscalers leaves out. */
class HiresUpscalersTest {
    @Test
    fun `the server's latent modes, else the usual six`() {
        assertEquals(HiresUpscalers.LATENT_MODES, HiresUpscalers.latentModes(null))
        assertEquals(HiresUpscalers.LATENT_MODES, HiresUpscalers.latentModes(listOf(" ", "")))
        assertEquals(listOf("Latent", "Latent (custom)"), HiresUpscalers.latentModes(listOf("Latent", "Latent (custom)")))
        assertEquals("Latent", HiresUpscalers.LATENT_MODES.first())
        assertEquals(6, HiresUpscalers.LATENT_MODES.size)
    }

    @Test
    fun `an upscaler list that already has latent modes does not show them twice`() {
        val latent = HiresUpscalers.LATENT_MODES
        assertEquals(
            listOf("None", "Lanczos", "4x-UltraSharp"),
            HiresUpscalers.upscalers(latent, listOf("Latent", "None", "Lanczos", "Latent (bicubic)", "4x-UltraSharp")),
        )
    }
}
