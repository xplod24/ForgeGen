package com.example.forgegen

import com.example.forgegen.Markdown.Block
import com.example.forgegen.Markdown.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {
    @Test
    fun `blocks of a changelog`() {
        val blocks =
            Markdown.parse(
                """
                ## 1.1.0
                - First point
                  continued on the next line
                - Second with **bold**
                  - nested point

                A paragraph
                over two lines.

                1. one
                2) two
                ---
                ```
                val x = 1
                ```
                """.trimIndent(),
            )
        assertEquals(
            listOf(
                Block.Heading(2, listOf(Span("1.1.0"))),
                Block.ListItem(0, "•", listOf(Span("First point continued on the next line"))),
                Block.ListItem(0, "•", listOf(Span("Second with "), Span("bold", bold = true))),
                Block.ListItem(1, "•", listOf(Span("nested point"))),
                Block.Paragraph(listOf(Span("A paragraph over two lines."))),
                Block.ListItem(0, "1.", listOf(Span("one"))),
                Block.ListItem(0, "2.", listOf(Span("two"))),
                Block.Rule,
                Block.Code("val x = 1"),
            ),
            blocks,
        )
    }

    @Test
    fun `inline formatting`() {
        assertEquals(
            listOf(
                Span("a "),
                Span("bold", bold = true),
                Span(", "),
                Span("italic", italic = true),
                Span(", "),
                Span("code", code = true),
                Span(" and "),
                Span("a link", url = "https://example.com"),
            ),
            Markdown.inline("a **bold**, *italic*, `code` and [a link](https://example.com)"),
        )
        assertEquals(
            listOf(Span("x "), Span("very ", italic = true), Span("bold", bold = true, italic = true)),
            Markdown.inline("x *very **bold***"),
        )
    }

    @Test
    fun `text that only looks like formatting stays as it is`() {
        assertEquals(listOf(Span("snake_case_name and 5 * 3 * 2")), Markdown.inline("snake_case_name and 5 * 3 * 2"))
        assertEquals(listOf(Span("a [bracket] and *star")), Markdown.inline("a [bracket] and *star"))
        assertEquals(
            listOf(Span("\"Use Native Security\" is now \"App Lock\"")),
            Markdown.inline("\"Use Native Security\" is now \"App Lock\""),
        )
        assertEquals(listOf(Span("literal *stars*")), Markdown.inline("literal \\*stars\\*"))
    }

    @Test
    fun `the whole changelog parses`() {
        val changelog = java.io.File("../CHANGELOG.md").takeIf { it.exists() } ?: return
        val text = changelog.readText()
        val parsed = Markdown.parse(text).filterIsInstance<Block.Heading>()

        fun headings(level: Int) = parsed.filter { it.level == level }.map { heading -> heading.text.joinToString("") { it.text } }

        // "## <version>" sections, and since 2.0.0 "### " parts inside a section.
        val headings = headings(2)
        assertEquals(text.lines().filter { it.startsWith("## ") }.map { it.removePrefix("## ").trim() }, headings)
        assertEquals(text.lines().filter { it.startsWith("### ") }.map { it.removePrefix("### ").trim() }, headings(3))
        assertTrue("newest section first: ${headings.first()}", Regex("""\d+\.\d+\.\d+(-\d+)?""").matches(headings.first()))
    }
}

/** 3.0.0-2: the update card in the settings draws the release notes as Markdown too. */
class ReleaseNotesMarkdownTest {
    @Test
    fun `release notes are a Markdown list whose bold and code are read`() {
        val notes = parseReleaseNotes("## 3.0.0-1\n### Queue\n- **Queue as a timeline:** next to each job\n- the `Start at` chip\n")
        val blocks = Markdown.parse(releaseNotesMarkdown(notes))
        assertEquals(3, blocks.size)
        assertEquals(3, (blocks[0] as Block.Heading).level)
        val first = blocks[1] as Block.ListItem
        assertEquals(listOf(Span("Queue as a timeline:", bold = true), Span(" next to each job")), first.text)
        val second = blocks[2] as Block.ListItem
        assertTrue(second.text.any { it.code && it.text == "Start at" })
        val spans = blocks.filterIsInstance<Block.ListItem>().flatMap { it.text }
        assertTrue("no ** or ` left in the text", spans.none { "**" in it.text || "`" in it.text })
    }

    // 3.0.0-4: a section starts with the release's kind and groups its items under New, Changed and Fixed.
    private val body =
        """
        **Bugfix** · a lighter top bar

        ### Changed
        - **Top bar:** one line
        - Memory meters

        ### Fixed
        - Unload refreshes the meters
        - The panel says what unloading frees

        Built by CI
        """.trimIndent()

    @Test
    fun `the kind line and the New, Changed and Fixed headings are kept`() {
        val notes = parseReleaseNotes(body)
        assertEquals(
            listOf(
                "**Bugfix** · a lighter top bar",
                "### Changed",
                "- **Top bar:** one line",
                "- Memory meters",
                "### Fixed",
                "- Unload refreshes the meters",
                "- The panel says what unloading frees",
            ),
            notes,
        )
        assertEquals(4, releaseNoteCount(notes))
        val blocks = Markdown.parse(releaseNotesMarkdown(notes))
        assertEquals(Span("Bugfix", bold = true), (blocks[0] as Block.Paragraph).text.first())
        assertEquals(listOf("Changed", "Fixed"), blocks.filterIsInstance<Block.Heading>().map { h -> h.text.joinToString("") { it.text } })
        assertEquals(4, blocks.filterIsInstance<Block.ListItem>().size)
    }

    @Test
    fun `the card shows the first items under their headings and no heading without items`() {
        val notes = parseReleaseNotes(body)
        assertEquals(
            "**Bugfix** · a lighter top bar\n### Changed\n- **Top bar:** one line\n- Memory meters",
            releaseNotesMarkdown(notes, maxItems = 2),
        )
        assertEquals(
            "**Bugfix** · a lighter top bar\n### Changed\n- **Top bar:** one line\n- Memory meters\n### Fixed\n- Unload refreshes the meters",
            releaseNotesMarkdown(notes, maxItems = 3),
        )
    }

    @Test
    fun `notes of older releases stay a plain list`() {
        val notes = parseReleaseNotes("What's new:\n- Added cool new feature\n* Naprawiono błąd\n\nBuilt by CI")
        assertEquals(listOf("- Added cool new feature", "- Naprawiono błąd"), notes)
        assertEquals("- Added cool new feature", releaseNotesMarkdown(notes, maxItems = 1))
    }

    @Test
    fun `every changelog section since 3_0_0-4 names its kind and uses the three headings`() {
        val changelog = java.io.File("../CHANGELOG.md").takeIf { it.exists() } ?: return
        val kinds = setOf("Bugfix", "Polish", "Feature", "Overhaul")
        val headings = setOf("New", "Changed", "Fixed")
        val sections = changelog.readText().split(Regex("""(?m)^## """)).drop(1)
        for (section in sections) {
            val version = section.lineSequence().first().trim()
            val code = versionCodeFromTag("v$version") ?: continue
            if (code < versionCodeFromTag("v3.0.0-4")!!) continue
            val lines =
                section
                    .lines()
                    .drop(1)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            val kind = Regex("""^\*\*(\w+)\*\*""").find(lines.first())?.groupValues?.get(1)
            assertTrue("$version starts with its kind in bold: ${lines.first()}", kind in kinds)
            val used = lines.filter { it.startsWith("### ") }.map { it.removePrefix("### ").trim() }
            assertTrue("$version uses only New, Changed and Fixed: $used", used.isNotEmpty() && headings.containsAll(used))
            assertEquals("$version keeps the order New, Changed, Fixed", headings.filter { it in used }, used)
        }
    }
}

/** 3.0.0-4: the server's memory in the main screen's top bar. */
class ServerMemoryTest {
    private val gib = 1024.0 * 1024.0 * 1024.0

    @Test
    fun `the server's answer becomes GB, and nothing when it reports nothing`() {
        val memory =
            ServerMemory.of(
                MemoryResponseDto(
                    ram = MemoryStatDto(used = 12.3 * gib, total = 31.9 * gib),
                    cuda = CudaStatDto(system = MemoryStatDto(used = 5.1 * gib, total = 8.0 * gib)),
                ),
            )!!
        assertEquals("RAM: 12.3/31.9GB | VRAM: 5.1/8.0GB", memory.summary())
        assertNull(ServerMemory.of(MemoryResponseDto(ram = null, cuda = null)))
        assertNull(ServerMemory.of(null))
        val ramOnly = ServerMemory.of(MemoryResponseDto(ram = MemoryStatDto(used = 2 * gib, total = 16 * gib), cuda = null))!!
        assertTrue(ramOnly.hasRam && !ramOnly.hasVram)
        assertEquals("RAM: 2.0/16.0GB", ramOnly.summary())
    }

    @Test
    fun `the meters stay short and the panel gives the whole numbers`() {
        assertEquals("5.1/8.0", ServerMemory.compact(5.1, 8.0))
        assertEquals("12.3/32", ServerMemory.compact(12.3, 31.9))
        assertEquals("0.4/24", ServerMemory.compact(0.4, 24.0))
        assertEquals("45.2/128", ServerMemory.compact(45.2, 128.0))
        assertEquals("112/128", ServerMemory.compact(112.4, 128.0))
        assertEquals("5.1 of 8.0 GB · 64%", ServerMemory.detail(5.1, 8.0))
        assertEquals(0f, ServerMemory.share(1.0, 0.0))
        assertEquals(1f, ServerMemory.share(9.0, 8.0))
        assertTrue(ServerMemory.share(7.6, 8.0) >= ServerMemory.ALMOST_FULL)
        assertTrue(ServerMemory.share(5.1, 8.0) < ServerMemory.ALMOST_FULL)
    }
}

class WhatsNewTest {
    private val changelog =
        """
        ## 1.1.0
        - New gallery

        ## 1.0.2
        - App lock fixes

        ## 1.0.1
        - Fades

        ## build-1034
        - Old build
        """.trimIndent()

    @Test
    fun `an update shows every version after the last one seen`() {
        assertEquals(
            "## 1.1.0\n- New gallery\n\n## 1.0.2\n- App lock fixes",
            WhatsNew.notesFor(changelog, current = "1.1.0", lastSeen = "1.0.1", wasUpdated = true),
        )
    }

    @Test
    fun `the first version with the dialog shows only its own notes after an update`() {
        assertEquals("## 1.1.0\n- New gallery", WhatsNew.notesFor(changelog, current = "1.1.0", lastSeen = null, wasUpdated = true))
    }

    @Test
    fun `a micro-patch comes after its release`() {
        val withMicro = "## 1.1.4-1\n- Fix\n\n## 1.1.4\n- Feature\n\n$changelog"
        assertEquals("## 1.1.4-1\n- Fix", WhatsNew.notesFor(withMicro, current = "1.1.4-1", lastSeen = "1.1.4", wasUpdated = true))
        assertEquals(
            "## 1.1.4-1\n- Fix\n\n## 1.1.4\n- Feature",
            WhatsNew.notesFor(withMicro, current = "1.1.4-1", lastSeen = "1.1.0", wasUpdated = true),
        )
        assertNull(WhatsNew.notesFor(withMicro, current = "1.1.4-1", lastSeen = "1.1.4-1", wasUpdated = true))
    }

    @Test
    fun `nothing after a fresh install or when the version was already seen`() {
        assertNull(WhatsNew.notesFor(changelog, current = "1.1.0", lastSeen = null, wasUpdated = false))
        assertNull(WhatsNew.notesFor(changelog, current = "1.1.0", lastSeen = "1.1.0", wasUpdated = true))
        assertNull("downgrade", WhatsNew.notesFor(changelog, current = "1.0.2", lastSeen = "1.1.0", wasUpdated = true))
        assertNull("no section for this version", WhatsNew.notesFor(changelog, current = "1.2.0", lastSeen = "1.1.0", wasUpdated = true))
    }
}
