package com.example.forgegen

import com.sun.net.httpserver.HttpExchange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.File
import java.net.URLDecoder

/**
 * 2.4.2: the tag list of tag suggestions comes from the server's tagcomplete extension, as its own script reads it:
 * `file=tmp/tagAutocompletePath.txt` gives its folder, `file=<folder>/<tac_tagFile>` the list. The mock serves the
 * real danbooru.csv (140k tags) from a Windows folder with a space, like the owner's server may have.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class G38_TagSuggestionsTest {
    object TacServer {
        // The tags folder of the a1111-sd-webui-tagcomplete extension (danbooru.csv, extra-quality-tags.csv; about 25 MB,
        // not in the repository): set TAGCOMPLETE_TAGS to it, otherwise this class is skipped.
        val tags = File(System.getenv("TAGCOMPLETE_TAGS").orEmpty())
        const val FOLDER = "C:/Stable Diffusion/extensions/a1111-sd-webui-tagcomplete/tags"
        @Volatile var installed = true
        @Volatile var tagFile = "danbooru.csv"
        @Volatile var extraFile = "extra-quality-tags.csv"
        val served = java.util.concurrent.CopyOnWriteArrayList<String>()

        private fun send(ex: HttpExchange, code: Int, bytes: ByteArray) {
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }

        fun options() =
            org.json.JSONObject()
                .put("sd_model_checkpoint", "model.safetensors [abc123]")
                .put("tac_tagFile", tagFile)
                .put("tac_extra.extraFile", extraFile)
                .put("tac_extra.addMode", "Insert before")
                .put("tac_replaceUnderscores", true)
                .put("tac_escapeParentheses", true)
                .put("tac_undersocreReplacementExclusionList", "0_0,^_^,o_o")
                .toString()

        fun route(ex: HttpExchange, path: String): Boolean {
            when {
                path == "/sdapi/v1/options" && ex.requestMethod == "GET" -> send(ex, 200, options().toByteArray())
                path == "/file=tmp/tagAutocompletePath.txt" ->
                    if (installed) send(ex, 200, "$FOLDER\n".toByteArray()) else send(ex, 404, """{"detail":"Not Found"}""".toByteArray())
                path.startsWith("/file=") -> {
                    val file = URLDecoder.decode(path.removePrefix("/file=").replace("+", "%2B"), "UTF-8")
                    served += path
                    val name = file.removePrefix("$FOLDER/")
                    val source = File(tags, name)
                    if (installed && file.startsWith("$FOLDER/") && source.exists()) send(ex, 200, source.readBytes())
                    else send(ex, 404, """{"detail":"File not allowed"}""".toByteArray())
                }
                else -> return false
            }
            return true
        }

        fun count(name: String) = File(tags, name).readLines().count { it.substringBefore(',').isNotBlank() }
    }

    companion object {
        val vm get() = TestApp.vm

        @BeforeClass @JvmStatic fun init() {
            org.junit.Assume.assumeTrue("TAGCOMPLETE_TAGS is not set", File(TacServer.tags, "danbooru.csv").isFile)
            TestApp.start(custom = { ex, path, _ -> TacServer.route(ex, path) })
        }

        fun status() = ForgeTagManager.status.value

        fun sameServerAgain() {
            val options = ForgeSettingsManager.gson.fromJson(TacServer.options(), OptionsResponseDto::class.java)
            ForgeTagManager.onServerOptions(ForgeRepository.forgeApi!!, options, vm.config.value.apiUrl)
        }
    }

    @Test fun `01 on connect the server's tag list is downloaded, from a folder with a space, extra tags first`() {
        awaitUntil("tag list", 30_000) { status().source == ForgeTagManager.Source.READY }
        val expected = TacServer.count("danbooru.csv") + TacServer.count("extra-quality-tags.csv")
        println("[G38-01] ${status()} served=${TacServer.served}")
        assertEquals(expected, status().count)
        assertEquals("danbooru.csv", status().file)
        assertTrue(TacServer.served.any { it.contains("Stable%20Diffusion") && it.endsWith("/danbooru.csv") })
        val tags = ForgeTagManager.tags.value!!
        assertEquals("masterpiece", tags.search("mast").first().name)
        assertEquals(listOf("long_hair", "long_sleeves"), tags.search("long").take(2).map { it.name })
        assertEquals("blonde_hair", tags.search("blonde").first().name)
        assertEquals("chen \\(touhou\\)", ForgeTagManager.rules.value.format("chen_(touhou)"))
        // Searching the whole list is quick enough for every key press.
        val t0 = System.nanoTime()
        repeat(100) { tags.search("ha") ; tags.search("xyzzy") }
        val msEach = (System.nanoTime() - t0) / 200 / 1e6
        println("[G38-01] search ${"%.2f".format(msEach)} ms")
        assertTrue("search took $msEach ms", msEach < 50)
    }

    @Test fun `02 the next connect to the same server downloads nothing`() {
        val before = TacServer.served.size
        sameServerAgain()
        Thread.sleep(1_500)
        assertEquals(before, TacServer.served.size)
        assertEquals(ForgeTagManager.Source.READY, status().source)
    }

    @Test fun `03 another tag file chosen on the server is downloaded`() {
        TacServer.tagFile = "e621.csv"
        sameServerAgain()
        awaitUntil("e621", 30_000) { status().file == "e621.csv" && status().source == ForgeTagManager.Source.READY }
        assertEquals(TacServer.count("e621.csv") + TacServer.count("extra-quality-tags.csv"), status().count)
    }

    @Test fun `04 switched off the list is freed, switched on it comes back from the phone`() {
        val before = TacServer.served.size
        onMain { vm.saveConfig(vm.config.value.copy(tagSuggestions = false)) }
        awaitUntil("off") { status().source == ForgeTagManager.Source.OFF }
        assertNull(ForgeTagManager.tags.value)
        sameServerAgain()
        Thread.sleep(500)
        assertEquals("nothing is downloaded while off", before, TacServer.served.size)
        onMain { vm.saveConfig(vm.config.value.copy(tagSuggestions = true)) }
        awaitUntil("on", 30_000) { status().source == ForgeTagManager.Source.READY && ForgeTagManager.tags.value != null }
        Thread.sleep(1_000)
        println("[G38-04] ${status()}")
        assertEquals("e621.csv", status().file)
        assertEquals("read from the phone, not downloaded", before, TacServer.served.size)
    }

    @Test fun `05 Tag List downloads again, and a server without tagcomplete keeps the saved list`() {
        TacServer.tagFile = "danbooru.csv"
        sameServerAgain()
        awaitUntil("danbooru again", 30_000) { status().file == "danbooru.csv" && status().source == ForgeTagManager.Source.READY }
        val before = TacServer.served.size
        onMain { vm.reloadTagList() }
        awaitUntil("reloaded", 30_000) { TacServer.served.size > before && status().source == ForgeTagManager.Source.READY }
        TacServer.installed = false
        onMain { vm.reloadTagList() }
        awaitUntil("missing", 10_000) { status().source == ForgeTagManager.Source.MISSING }
        println("[G38-05] ${status()}")
        assertEquals("The server has no tagcomplete extension", status().message)
        assertTrue("the saved list is still used", status().count > 100_000 && ForgeTagManager.tags.value != null)
        TacServer.installed = true
    }

    @Test fun `06 an extra file the server does not send is not asked for at every connect`() {
        TacServer.extraFile = "my-tags.csv"
        sameServerAgain()
        awaitUntil("without extra", 30_000) {
            status().source == ForgeTagManager.Source.READY && TacServer.served.any { it.endsWith("/my-tags.csv") }
        }
        awaitUntil("loaded", 30_000) { status().source == ForgeTagManager.Source.READY && status().count == TacServer.count("danbooru.csv") }
        val before = TacServer.served.size
        sameServerAgain()
        Thread.sleep(1_500)
        assertEquals(before, TacServer.served.size)
        assertEquals(TacServer.count("danbooru.csv"), status().count)
        TacServer.extraFile = "extra-quality-tags.csv"
    }
}
