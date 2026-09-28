package com.example.forgegen

import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

/** 3.1.0: what a LoRA's training left in its file, read from /sdapi/v1/loras as it streams. */
class LoraMetadataTest {
    @Test
    fun `the model a LoRA was trained for`() {
        assertEquals(LoraBase.SDXL, LoraMetadata.base("sdxl_base_v1-0", null, null))
        assertEquals(LoraBase.SDXL, LoraMetadata.base(null, "stable-diffusion-xl-v1-base/lora", null))
        assertEquals(LoraBase.SD1, LoraMetadata.base("sd_v1", null, "False"))
        assertEquals(LoraBase.SD2, LoraMetadata.base(null, null, "True"))
        assertEquals(LoraBase.SD1, LoraMetadata.base(null, "stable-diffusion-v1/lora", null))
        assertEquals(LoraBase.FLUX, LoraMetadata.base("flux1", null, null))
        assertEquals(LoraBase.FLUX, LoraMetadata.base(null, "flux-1-dev/lora", null))
        assertEquals(LoraBase.OTHER, LoraMetadata.base(null, "stable-diffusion-v3-medium/lora", null))
        assertNull(LoraMetadata.base(null, null, null))
        assertEquals("stable-diffusion-v3-medium", LoraMetadata.architectureLabel("stable-diffusion-v3-medium/lora", null))
    }

    @Test
    fun `whether it fits the checkpoint, when both are known`() {
        val xl = LoraInfo("a", "/a", base = LoraBase.SDXL)
        assertEquals(true, xl.fits(ModelType.SDXL))
        assertEquals(false, xl.fits(ModelType.SD))
        assertNull(xl.fits(ModelType.AUTO))
        assertNull(LoraInfo("b", "/b").fits(ModelType.SDXL))
        assertEquals(false, LoraInfo("c", "/c", base = LoraBase.OTHER, architecture = "sd3").fits(ModelType.FLUX))
        assertEquals("sd3", LoraInfo("c", "/c", base = LoraBase.OTHER, architecture = "sd3").baseLabel)
        assertEquals(true, LoraInfo("d", "/d", base = LoraBase.SD2).fits(ModelType.SD))
    }

    @Test
    fun `the most used tags over every training folder`() {
        val tags =
            LoraMetadata.topTags(
                mapOf(
                    "10_a" to mapOf("1girl" to 30, " detailed " to 12),
                    "5_b" to mapOf("detailed" to 20, "solo" to 5, "" to 99),
                ),
                2,
            )
        assertEquals(listOf(LoraTag("detailed", 32), LoraTag("1girl", 30)), tags)
    }

    @Test
    fun `the training resolution in its usual spellings`() {
        assertEquals("1024×1024", LoraMetadata.resolution("(1024, 1024)"))
        assertEquals("768×512", LoraMetadata.resolution("[768,512]"))
        assertEquals("512×512", LoraMetadata.resolution("512"))
        assertNull(LoraMetadata.resolution(""))
        assertNull(LoraMetadata.resolution(null))
    }

    @Test
    fun `the list is read as it streams, keeping only what is shown`() {
        val json =
            """
            [
              {"name": "detail", "alias": "detail", "path": "D:\\sd\\models\\Lora\\detail.safetensors",
               "metadata": {"ss_base_model_version": "sdxl_base_v1-0", "ss_resolution": "(1024, 1024)", "ss_num_epochs": "10",
                            "ss_dataset_dirs": {"10_x": {"n_repeats": 10, "img_count": 40}},
                            "ss_tag_frequency": {"10_x": {"detailed": 40, "intricate": 28}}, "ss_network_dim": 32}},
              {"name": "flat", "path": "/l/flat.safetensors",
               "metadata": {"modelspec.architecture": "stable-diffusion-v1/lora", "ss_num_epochs": 4,
                            "ss_tag_frequency": "{\"5_y\": {\"flat color\": 9, \"no lineart\": 3}}"}},
              {"name": "plain", "path": "/l/plain.safetensors", "metadata": {}},
              {"name": "odd", "path": "/l/odd.safetensors", "metadata": null}
            ]
            """.trimIndent()
        val list = LoraMetadata.readList(JsonReader(StringReader(json)))
        assertEquals(listOf("detail", "flat", "plain", "odd"), list.map { it.name })
        val detail = list[0]
        assertEquals(LoraBase.SDXL, detail.base)
        assertEquals("1024×1024", detail.resolution)
        assertEquals(10, detail.epochs)
        assertEquals(listOf("detailed", "intricate"), detail.tags.map { it.tag })
        val flat = list[1]
        assertEquals(LoraBase.SD1, flat.base)
        assertEquals(4, flat.epochs)
        assertEquals(listOf(LoraTag("flat color", 9), LoraTag("no lineart", 3)), flat.tags)
        assertNull(list[2].base)
        assertTrue(list[2].tags.isEmpty())
        assertNull(list[3].base)
    }

    @Test
    fun `a LoRA is found by its file, whatever the slashes and case, or else by name`() {
        val index = LoraInfoIndex(listOf(LoraInfo("detail", "D:\\SD\\models\\Lora\\detail.safetensors", base = LoraBase.SDXL)))
        assertEquals(LoraBase.SDXL, index.of("x", "d:/sd/models/lora/detail.safetensors")?.base)
        assertEquals(LoraBase.SDXL, index.of("Detail", "")?.base)
        assertNull(index.of("other", "/l/other.safetensors"))
    }
}

/** 3.1.0: trigger words and embeddings added to a prompt by a tap. */
class PromptEditsTest {
    @Test
    fun `a prompt has a tag whatever its weight, case or underscores`() {
        val prompt = "masterpiece, (Long_Hair:1.2), <lora:detail:0.6>, sharp focus"
        assertTrue(PromptEdits.hasTag(prompt, "long hair"))
        assertTrue(PromptEdits.hasTag(prompt, "SHARP_FOCUS"))
        assertFalse(PromptEdits.hasTag(prompt, "long"))
        assertFalse(PromptEdits.hasTag(prompt, " "))
    }

    @Test
    fun `only the missing tags are added, each after a comma`() {
        assertEquals("a, b, c", PromptEdits.addTags("a, b", listOf("b", "c")))
        assertEquals("c", PromptEdits.addTags("", listOf("c")))
        assertEquals("a, c", PromptEdits.addTags("a,", listOf("c")))
        assertEquals("a, EasyNegative", PromptEdits.addTags("a  ", listOf("EasyNegative", "easynegative")))
    }
}

/** 3.1.0: the server's styles; off unless turned on in the settings. */
class PromptStylesTest {
    @Test
    fun `a style is merged as the web UI merges it`() {
        assertEquals("cinematic still of a cat, film", PromptStyles.merge("cinematic still of {prompt}, film", "a cat"))
        assertEquals("a cat, film grain", PromptStyles.merge("film grain", "a cat "))
        assertEquals("film grain", PromptStyles.merge("film grain", ""))
        assertEquals("a cat", PromptStyles.merge("", "a cat"))
    }

    @Test
    fun `pasting writes the chosen styles into both prompts, in their order, and chooses none`() {
        val styles =
            listOf(
                PromptStyle("Cine", "cinematic, {prompt}", "cartoon"),
                PromptStyle("Grain", "film grain", ""),
                PromptStyle("Unused", "x", "y"),
            )
        val state = AppState(positivePrompt = "a cat", negativePrompt = "lowres", styles = listOf("Grain", "Cine", "Gone"))
        val pasted = PromptStyles.pasteInto(state, styles)
        assertEquals("cinematic, a cat, film grain", pasted.positivePrompt)
        assertEquals("lowres, cartoon", pasted.negativePrompt)
        assertTrue(pasted.styles.isEmpty())
    }

    @Test
    fun `a job sends styles only while they are turned on`() {
        assertNull(PromptStyles.forJob(false, listOf("Cine")))
        assertNull(PromptStyles.forJob(true, emptyList()))
        assertEquals(listOf("Cine"), PromptStyles.forJob(true, listOf("Cine")))
        // Not sent at all (null is left out), so a server without styles never sees the field.
        val gson = Gson()
        val payload =
            Txt2ImgPayloadDto(
                "p",
                "n",
                20,
                7f,
                512,
                512,
                1,
                1,
                -1,
                "Euler a",
                "Automatic",
                OverrideSettingsDto(1, null),
                false,
                2f,
                "Latent",
                0.7f,
            )
        assertFalse(gson.toJson(payload).contains("styles"))
        assertTrue(gson.toJson(payload.copy(styles = listOf("Cine"))).contains("\"styles\":[\"Cine\"]"))
        assertEquals("cinematic still", PromptStyles.preview("cinematic still, {prompt}"))
    }

    @Test
    fun `the loaded embeddings come first, a skipped one is marked`() {
        val list = EmbeddingList(loaded = listOf("zeta", "Alpha"), skipped = listOf("bad-hands"))
        assertEquals(listOf("Alpha", "zeta", "bad-hands"), list.all)
        assertTrue(list.isSkipped("bad-hands"))
        assertFalse(list.isSkipped("zeta"))
    }
}

/** 3.1.0: the server's embeddings are suggested before the tags while one is typed. */
class EmbeddingSuggestionsTest {
    @Test
    fun `embeddings starting with the typed text come first, as they are named`() {
        val fragment = PromptTypingRules.fragmentAt("lowres, easyn", 13)
        val chips =
            Suggestions.forFragment(
                fragment,
                null,
                TagInsertRules(),
                emptyList(),
                emptyList(),
                listOf("EasyNegative", "easynegativeV2", "badhand", "NotEasy"),
            )
        assertEquals(listOf("EasyNegative", "easynegativeV2"), chips.map { it.insertion })
        assertTrue(chips.all { it.category == Suggestions.EMBEDDING && it.kind == TypedFragment.Kind.TAG })
        val many =
            Suggestions.forFragment(
                PromptTypingRules.fragmentAt(
                    "em",
                    2,
                ),
                null,
                TagInsertRules(),
                emptyList(),
                emptyList(),
                List(6) {
                    "emb$it"
                },
            )
        assertEquals(3, many.size)
    }
}
