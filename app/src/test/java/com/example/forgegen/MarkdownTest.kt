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
        val headings =
            Markdown
                .parse(text)
                .filterIsInstance<Block.Heading>()
                .map { heading -> heading.text.joinToString("") { it.text } }
        assertEquals(text.lines().filter { it.startsWith("## ") }.map { it.removePrefix("## ").trim() }, headings)
        assertTrue("newest section first: ${headings.first()}", Regex("""\d+\.\d+\.\d+""").matches(headings.first()))
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
    fun `nothing after a fresh install or when the version was already seen`() {
        assertNull(WhatsNew.notesFor(changelog, current = "1.1.0", lastSeen = null, wasUpdated = false))
        assertNull(WhatsNew.notesFor(changelog, current = "1.1.0", lastSeen = "1.1.0", wasUpdated = true))
        assertNull("downgrade", WhatsNew.notesFor(changelog, current = "1.0.2", lastSeen = "1.1.0", wasUpdated = true))
        assertNull("no section for this version", WhatsNew.notesFor(changelog, current = "1.2.0", lastSeen = "1.1.0", wasUpdated = true))
    }
}
