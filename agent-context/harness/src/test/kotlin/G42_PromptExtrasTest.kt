package com.example.forgegen

import com.sun.net.httpserver.HttpExchange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 3.1.0: the LoRAs' training metadata from /sdapi/v1/loras, the embeddings (/sdapi/v1/embeddings, refreshed on the
 * server), and the server's styles (/sdapi/v1/prompt-styles), which a job sends only while they are turned on.
 */
class G42_PromptExtrasTest {
    companion object {
        val embeddingRefreshes = AtomicInteger()

        private fun json(
            ex: HttpExchange,
            body: String,
        ): Boolean {
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
            return true
        }

        @BeforeClass @JvmStatic fun init() {
            TestApp.start(custom = { ex: HttpExchange, path, _ ->
                when (path) {
                    "/sdapi/v1/loras" ->
                        json(
                            ex,
                            """[{"name":"detail","path":"/l/detail.safetensors","metadata":{"ss_base_model_version":"sdxl_base_v1-0",""" +
                                """"ss_tag_frequency":{"10_x":{"detailed":40,"intricate":28,"sharp focus":20,"extra":1}}}}]""",
                        )
                    "/sdapi/v1/embeddings" ->
                        json(ex, """{"loaded":{"EasyNegative":{"step":null,"shape":768,"vectors":8}},"skipped":{"bad-hands-5":{"shape":1024}}}""")
                    "/sdapi/v1/refresh-embeddings" -> {
                        embeddingRefreshes.incrementAndGet()
                        json(ex, "null")
                    }
                    "/sdapi/v1/prompt-styles" ->
                        json(ex, """[{"name":"Cinematic","prompt":"cinematic still, {prompt}","negative_prompt":"cartoon"},{"name":"","prompt":"x"}]""")
                    else -> false
                }
            })
        }
    }

    @Test fun `LoRA metadata, embeddings and styles come with the lists`() {
        val vm = TestApp.vm
        awaitUntil("lora info") { vm.loraInfo.value.size == 1 }
        val info = vm.loraInfo.value.of("detail", "/l/detail.safetensors")!!
        assertEquals(LoraBase.SDXL, info.base)
        assertEquals(listOf("detailed", "intricate", "sharp focus", "extra"), info.tags.map { it.tag })
        awaitUntil("embeddings") { vm.embeddings.value.loaded == listOf("EasyNegative") }
        assertEquals(listOf("bad-hands-5"), vm.embeddings.value.skipped)
        // 3.4.0: the styles are read only while they are turned on, and at once when they are.
        assertEquals(0, TestApp.forge.calls("/sdapi/v1/prompt-styles").size)
        onMain { vm.saveConfig(vm.config.value.copy(serverStyles = true)) }
        awaitUntil("styles") { vm.promptStyles.value.map { it.name } == listOf("Cinematic") }
        onMain { vm.saveConfig(vm.config.value.copy(serverStyles = false)) }
        awaitUntil("styles dropped") { vm.promptStyles.value.isEmpty() }

        onMain { vm.refreshEmbeddings() }
        awaitUntil("refreshed") { embeddingRefreshes.get() == 1 }
    }

    @Test fun `trigger words and embeddings are added once`() {
        val vm = TestApp.vm
        onMain { vm.updateState { it.copy(positivePrompt = "a cat, detailed", negativePrompt = "lowres") } }
        onMain { vm.addPromptTags(listOf("detailed", "intricate")) }
        onMain { vm.addPromptTags(listOf("EasyNegative"), negative = true) }
        assertEquals("a cat, detailed, intricate", vm.appState.value.positivePrompt)
        assertEquals("lowres, EasyNegative", vm.appState.value.negativePrompt)
    }

    @Test fun `styles go with a job only while they are turned on`() {
        val vm = TestApp.vm
        onMain { vm.updateState { it.copy(positivePrompt = "a cat", batchCount = 1, styles = listOf("Cinematic")) } }

        var sent = TestApp.forge.txt2imgPayloads().size
        onMain { vm.queueGeneration() }
        awaitUntil("sent off", 20_000) { TestApp.forge.txt2imgPayloads().size == sent + 1 }
        assertFalse("off by default: no styles", TestApp.forge.txt2imgPayloads().last().has("styles"))

        onMain { vm.saveConfig(vm.config.value.copy(serverStyles = true)) }
        awaitUntil("setting") { vm.config.value.serverStyles }
        awaitUntil("styles") { vm.promptStyles.value.isNotEmpty() }
        sent = TestApp.forge.txt2imgPayloads().size
        onMain { vm.queueGeneration() }
        awaitUntil("sent on", 20_000) { TestApp.forge.txt2imgPayloads().size == sent + 1 }
        val payload = TestApp.forge.txt2imgPayloads().last()
        assertEquals("Cinematic", payload.getJSONArray("styles").getString(0))
        assertEquals("a cat", payload.getString("prompt"))

        onMain { vm.pasteStyles() }
        assertEquals("cinematic still, a cat", vm.appState.value.positivePrompt)
        assertTrue(vm.appState.value.styles.isEmpty())
        onMain { vm.saveConfig(vm.config.value.copy(serverStyles = false)) }
    }
}
