package com.example.forgegen

/* ============================================================================
 * MARKDOWN
 * A small Markdown reader for texts the app shows, such as the changelog: headings, bullet and numbered lists
 * (nested by indentation), paragraphs, fenced code blocks, horizontal rules, and inline **bold**, *italic*,
 * `code` and [links](url). Kept free of Compose so it can be unit-tested; MarkdownText draws the result.
 * ============================================================================ */
object Markdown {
    sealed interface Block {
        data class Heading(
            val level: Int,
            val text: List<Span>,
        ) : Block

        /** A list entry; [marker] is "•" or the item's number ("1."), [depth] 0 for the outermost list. */
        data class ListItem(
            val depth: Int,
            val marker: String,
            val text: List<Span>,
        ) : Block

        data class Paragraph(
            val text: List<Span>,
        ) : Block

        data class Code(
            val text: String,
        ) : Block

        data object Rule : Block
    }

    data class Span(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val code: Boolean = false,
        val url: String? = null,
    )

    private val HEADING = Regex("""^(#{1,6})\s+(.*?)\s*#*\s*$""")
    private val BULLET = Regex("""^(\s*)[-*+]\s+(.*)$""")
    private val NUMBERED = Regex("""^(\s*)(\d+)[.)]\s+(.*)$""")
    private val RULE = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")

    fun parse(markdown: String): List<Block> {
        val blocks = mutableListOf<Block>()
        val paragraph = StringBuilder()
        var item: Triple<Int, String, StringBuilder>? = null // depth, marker, text of the list item being read
        var code: StringBuilder? = null

        fun flush() {
            if (paragraph.isNotBlank()) blocks += Block.Paragraph(inline(paragraph.toString().trim()))
            paragraph.clear()
            item?.let { (depth, marker, text) -> blocks += Block.ListItem(depth, marker, inline(text.toString().trim())) }
            item = null
        }

        for (rawLine in markdown.replace("\r\n", "\n").split("\n")) {
            val line = rawLine.trimEnd()
            val fence = line.trimStart().startsWith("```")
            if (code != null) {
                if (fence) {
                    blocks += Block.Code(code.toString().trimEnd('\n'))
                    code = null
                } else {
                    code.append(rawLine).append('\n')
                }
                continue
            }
            if (fence) {
                flush()
                code = StringBuilder()
                continue
            }
            if (line.isBlank()) {
                flush()
                continue
            }

            val heading = HEADING.find(line)
            val bullet = BULLET.find(line)
            val numbered = NUMBERED.find(line)
            when {
                heading != null -> {
                    flush()
                    blocks += Block.Heading(heading.groupValues[1].length, inline(heading.groupValues[2]))
                }
                RULE.matches(line) -> {
                    flush()
                    blocks += Block.Rule
                }
                bullet != null -> {
                    flush()
                    item = Triple(depthOf(bullet.groupValues[1]), "•", StringBuilder(bullet.groupValues[2]))
                }
                numbered != null -> {
                    flush()
                    item = Triple(depthOf(numbered.groupValues[1]), "${numbered.groupValues[2]}.", StringBuilder(numbered.groupValues[3]))
                }
                // A line after a list item continues that item; any other line continues the paragraph.
                item != null -> item!!.third.append(' ').append(line.trim())
                else -> paragraph.append(' ').append(line.trim())
            }
        }
        code?.let { blocks += Block.Code(it.toString().trimEnd('\n')) } // an unclosed fence still shows its text
        flush()
        return blocks
    }

    private fun depthOf(indent: String) = indent.replace("\t", "    ").length / 2

    /** Inline formatting of one block's text. */
    fun inline(text: String): List<Span> {
        val spans = mutableListOf<Span>()
        inline(text, bold = false, italic = false, url = null, out = spans)
        return merge(spans)
    }

    private fun inline(
        text: String,
        bold: Boolean,
        italic: Boolean,
        url: String?,
        out: MutableList<Span>,
    ) {
        val plain = StringBuilder()

        fun flush() {
            if (plain.isNotEmpty()) out += Span(plain.toString(), bold, italic, url = url)
            plain.clear()
        }

        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length && !text[i + 1].isLetterOrDigit()) {
                plain.append(text[i + 1])
                i += 2
                continue
            }
            if (c == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    flush()
                    out += Span(text.substring(i + 1, end), bold, italic, code = true, url = url)
                    i = end + 1
                    continue
                }
            }
            if (c == '[') {
                val close = text.indexOf("](", i + 1)
                val end = if (close > i) text.indexOf(')', close + 2) else -1
                if (close > i && end > close) {
                    flush()
                    inline(text.substring(i + 1, close), bold, italic, text.substring(close + 2, end).trim(), out)
                    i = end + 1
                    continue
                }
            }
            if (c == '*' || c == '_') {
                val double = text.startsWith("$c$c", i)
                val delimiter = if (double) "$c$c" else "$c"
                val end = closingDelimiter(text, i, delimiter)
                if (end != -1) {
                    flush()
                    val inner = text.substring(i + delimiter.length, end)
                    if (double) inline(inner, true, italic, url, out) else inline(inner, bold, true, url, out)
                    i = end + delimiter.length
                    continue
                }
            }
            plain.append(c)
            i++
        }
        flush()
    }

    /**
     * Where [delimiter] opened at [start] is closed, or -1. As in Markdown, the opening one must be followed and
     * the closing one preceded by a non-space, and "_" only counts at word edges (so snake_case stays as it is).
     */
    private fun closingDelimiter(
        text: String,
        start: Int,
        delimiter: String,
    ): Int {
        val contentStart = start + delimiter.length
        if (contentStart >= text.length || text[contentStart].isWhitespace()) return -1
        val underscore = delimiter[0] == '_'
        if (underscore && start > 0 && text[start - 1].isLetterOrDigit()) return -1
        var from = contentStart + 1
        while (true) {
            val end = text.indexOf(delimiter, from)
            if (end == -1) return -1
            if (delimiter.length == 1 && text.startsWith("${delimiter}$delimiter", end)) {
                from = end + 2 // a nested "**" inside an italic part, not its end
                continue
            }
            val after = end + delimiter.length
            val closesHere =
                !text[end - 1].isWhitespace() &&
                    (!underscore || after >= text.length || !text[after].isLetterOrDigit())
            if (closesHere) return end
            from = end + 1
        }
    }

    /** Joins neighbouring spans with the same formatting. */
    private fun merge(spans: List<Span>): List<Span> {
        val merged = mutableListOf<Span>()
        for (span in spans) {
            val last = merged.lastOrNull()
            if (last != null && last.copy(text = "") == span.copy(text = "")) {
                merged[merged.size - 1] = last.copy(text = last.text + span.text)
            } else {
                merged += span
            }
        }
        return merged
    }
}
