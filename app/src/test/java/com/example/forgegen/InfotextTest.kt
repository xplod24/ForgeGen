package com.example.forgegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InfotextTest {
    @Test
    fun `multi-line prompts and parameters are split like Forge does`() {
        val info =
            Infotext.parse(
                """
                a cat, <lora:detail:0.8>
                sitting on a mat <lora:style>
                Negative prompt: ugly,
                blurry
                Steps: 20, Sampler: DPM++ 2M, Schedule type: Karras, CFG scale: 7, Seed: 123, Size: 832x1216, Model: juggernautXL_v9, Lora hashes: "detail: abc123, style: def456", Version: f2.0
                """.trimIndent(),
            )
        assertEquals("a cat, <lora:detail:0.8>\nsitting on a mat <lora:style>", info.positivePrompt)
        assertEquals("ugly,\nblurry", info.negativePrompt)
        assertEquals("juggernautXL_v9", info.model)
        assertEquals("DPM++ 2M", info.sampler)
        assertEquals("123", info.seed)
        assertEquals("832x1216", info.params["Size"])
        assertEquals("a quoted value keeps its commas", "detail: abc123, style: def456", info.params["Lora hashes"])
        assertEquals(listOf("detail", "style"), info.loras)
    }

    @Test
    fun `text without a parameter line is all prompt`() {
        val info = Infotext.parse("just a prompt\nNegative prompt: bad")
        assertEquals("just a prompt", info.positivePrompt)
        assertEquals("bad", info.negativePrompt)
        assertTrue(info.params.isEmpty())
    }

    @Test
    fun `empty text gives an empty result`() {
        val info = Infotext.parse("")
        assertEquals("", info.positivePrompt)
        assertEquals("", info.model)
        assertTrue(info.loras.isEmpty())
    }

    @Test
    fun `Windows line endings are handled`() {
        val info = Infotext.parse("prompt\r\nNegative prompt: neg\r\nSteps: 4, Sampler: Euler, Seed: 9, Model: m")
        assertEquals("prompt", info.positivePrompt)
        assertEquals("neg", info.negativePrompt)
        assertEquals("m", info.model)
    }
}
