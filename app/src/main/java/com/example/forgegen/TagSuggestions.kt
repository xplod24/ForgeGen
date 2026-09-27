package com.example.forgegen

import java.io.BufferedReader
import java.io.Reader
import java.util.Locale

/*
 * Tag suggestions above the keyboard (2.4.2, the owner's idea 1). The tags are the tag file of the server's
 * tagcomplete extension (DominikDoom's a1111-sd-webui-tagcomplete; its danbooru.csv is MIT licensed), searched while
 * a tag is typed. Everything here is plain Kotlin, so the search, the typed fragment, the insertion and the layout
 * decisions are tested without Android.
 */

/** A tag found for what is typed: its name as the list writes it, Danbooru category, post count, the alias it matched. */
data class TagMatch(
    val name: String,
    val category: Int,
    val count: Int,
    val alias: String? = null,
)

/**
 * The tag list, most used first. A tagcomplete CSV line is `name,category,count,"alias1,alias2"`; the count and the
 * aliases are optional, and the extra file (quality tags) has text instead of a count.
 */
class TagList private constructor(
    private val names: Array<String>,
    // The names and aliases in lower case, for the search.
    private val keys: Array<String>,
    private val categories: ByteArray,
    private val counts: IntArray,
    private val aliases: Array<String?>,
) {
    val size: Int get() = names.size

    /**
     * Tags for [typed]: first those starting with it, then those in which a later word starts with it (`hair` gives
     * `long_hair`), then those with an alias starting with it; each group most used first.
     */
    fun search(
        typed: String,
        limit: Int = MAX_RESULTS,
    ): List<TagMatch> {
        val query = normalizeQuery(typed)
        if (query.isEmpty()) return emptyList()
        val prefix = ArrayList<Int>()
        val word = ArrayList<Int>()
        val alias = ArrayList<Pair<Int, String>>()
        for (i in keys.indices) {
            val key = keys[i]
            if (key.startsWith(query)) {
                prefix += i
                if (prefix.size >= limit) break // the list is most used first: nothing later can rank higher
            } else if (word.size < limit && startsAWord(key, query)) {
                word += i
            } else if (alias.size < limit) {
                aliases[i]?.let { all -> aliasStartingWith(all, query)?.let { alias += i to it } }
            }
        }
        val seen = HashSet<String>()
        val found = ArrayList<TagMatch>(limit)

        fun add(
            i: Int,
            matched: String?,
        ) {
            if (found.size < limit && seen.add(keys[i])) found += TagMatch(names[i], categories[i].toInt(), counts[i], matched)
        }
        prefix.forEach { add(it, null) }
        word.forEach { add(it, null) }
        alias.forEach { (i, matched) -> add(i, matched) }
        return found
    }

    companion object {
        const val MAX_RESULTS = 24

        /**
         * Reads the tag file [main] and the optional [extra] file (tagcomplete's "Extra filename"), which goes before
         * the main list when [extraFirst] (its "Insert before" mode) and after it otherwise.
         */
        fun parse(
            main: Reader,
            extra: Reader? = null,
            extraFirst: Boolean = true,
        ): TagList {
            val mainRows = readRows(main)
            // The files are sorted most used first; one that is not is sorted here (stable: equal counts keep order).
            val sorted =
                if ((1 until mainRows.size).all { mainRows[it - 1].count >= mainRows[it].count }) {
                    mainRows
                } else {
                    mainRows.sortedByDescending { it.count }
                }
            val extraRows = extra?.let { readRows(it) }.orEmpty()
            val rows = if (extraFirst) extraRows + sorted else sorted + extraRows
            return TagList(
                names = Array(rows.size) { rows[it].name },
                keys = Array(rows.size) { rows[it].name.lowercase(Locale.ROOT) },
                categories = ByteArray(rows.size) { rows[it].category.toByte() },
                counts = IntArray(rows.size) { rows[it].count },
                aliases = Array(rows.size) { rows[it].aliases?.lowercase(Locale.ROOT) },
            )
        }

        private class Row(
            val name: String,
            val category: Int,
            val count: Int,
            val aliases: String?,
        )

        private fun readRows(reader: Reader): List<Row> {
            val rows = ArrayList<Row>()
            BufferedReader(reader).useLines { lines ->
                lines.forEach { line ->
                    val fields = csvFields(line)
                    val name = fields[0].trim()
                    if (name.isNotEmpty()) {
                        rows +=
                            Row(
                                name = name,
                                category =
                                    fields
                                        .getOrNull(1)
                                        ?.trim()
                                        ?.toIntOrNull()
                                        ?.coerceIn(0, Byte.MAX_VALUE.toInt()) ?: 0,
                                count = fields.getOrNull(2)?.trim()?.toIntOrNull() ?: 0,
                                aliases = fields.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() },
                            )
                    }
                }
            }
            return rows
        }

        /** The fields of one CSV line: commas inside quotes stay, a doubled quote is a quote. */
        internal fun csvFields(line: String): List<String> {
            val fields = ArrayList<String>(4)
            val field = StringBuilder()
            var quoted = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                        field.append('"')
                        i++
                    }
                    c == '"' -> quoted = !quoted
                    c == ',' && !quoted -> {
                        fields += field.toString()
                        field.setLength(0)
                    }
                    else -> field.append(c)
                }
                i++
            }
            fields += field.toString()
            return fields
        }

        /** What is typed, as the list writes it: lower case, underscores for spaces, no escaping backslashes. */
        fun normalizeQuery(typed: String): String =
            typed
                .trim()
                .lowercase(Locale.ROOT)
                .replace("\\(", "(")
                .replace("\\)", ")")
                .replace("\\[", "[")
                .replace("\\]", "]")
                .replace(Regex("\\s+"), "_")

        /** Whether [query] starts a later word of [key], after something that is not a letter (as tagcomplete searches). */
        private fun startsAWord(
            key: String,
            query: String,
        ): Boolean {
            var at = key.indexOf(query, 1)
            while (at > 0) {
                if (!key[at - 1].isLetter()) return true
                at = key.indexOf(query, at + 1)
            }
            return false
        }

        private fun aliasStartingWith(
            all: String,
            query: String,
        ): String? {
            var start = 0
            while (start < all.length) {
                val end = all.indexOf(',', start).let { if (it < 0) all.length else it }
                if (end - start >= query.length && all.startsWith(query, start)) return all.substring(start, end)
                start = end + 1
            }
            return null
        }
    }
}

/**
 * How a tag is written into the prompt, as tagcomplete does it (its settings on the server are used when it has
 * them): underscores become spaces except in emoticons like `^_^`, and brackets get a backslash (`chen \(touhou\)`),
 * because plain ones change the weight in Forge.
 */
data class TagInsertRules(
    val replaceUnderscores: Boolean = true,
    val keepUnderscores: Set<String> = DEFAULT_KEEP_UNDERSCORES,
    val escapeBrackets: Boolean = true,
) {
    fun format(tag: String): String {
        var text = tag
        val emoticon = text.none { it.isLetterOrDigit() } || text in keepUnderscores
        if (replaceUnderscores && !emoticon) text = text.replace('_', ' ')
        if (escapeBrackets) {
            text =
                text
                    .replace("(", "\\(")
                    .replace(")", "\\)")
                    .replace("[", "\\[")
                    .replace("]", "\\]")
        }
        return text
    }

    /** The tag as the strip shows it: spaces like in the prompt, without the backslashes. */
    fun label(tag: String): String = copy(escapeBrackets = false).format(tag)

    companion object {
        // tagcomplete's default "Underscore replacement exclusion list".
        val DEFAULT_KEEP_UNDERSCORES =
            "0_0,(o)_(o),+_+,+_-,._.,<o>_<o>,<|>_<|>,=_=,>_<,3_3,6_9,>_o,@_@,^_^,o_o,u_u,x_x,|_|,||_||"
                .split(',')
                .toSet()

        /** The rules set on the server's tagcomplete; what it does not say stays as tagcomplete's default. */
        fun of(
            replaceUnderscores: Boolean?,
            keepUnderscores: String?,
            escapeBrackets: Boolean?,
        ) = TagInsertRules(
            replaceUnderscores = replaceUnderscores ?: true,
            keepUnderscores =
                keepUnderscores
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.toSet() ?: DEFAULT_KEEP_UNDERSCORES,
            escapeBrackets = escapeBrackets ?: true,
        )
    }
}

/** What is being typed at the caret: a tag, a wildcard after `__`, or a LoRA after `<lora:`; [start]..[end] is replaced. */
data class TypedFragment(
    val kind: Kind,
    val start: Int,
    val end: Int,
    val query: String,
) {
    enum class Kind { TAG, WILDCARD, LORA }
}

/** The new text and caret after a suggestion was tapped. */
data class TypedEdit(
    val text: String,
    val caret: Int,
)

object PromptTypingRules {
    // A tag is suggested from its second character; wildcards and LoRAs right after `__` and `<lora:`.
    const val MIN_TAG_CHARS = 2
    private const val LORA_START = "<lora:"

    /** What is being typed at [caret] in [text], or null when nothing is (a new tag has fewer than 2 characters). */
    fun fragmentAt(
        text: String,
        caret: Int,
    ): TypedFragment? {
        if (caret !in 0..text.length) return null
        // The rest of the word under the caret is replaced too, so tapping in the middle of a word leaves no tail.
        var end = caret
        while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_')) end++
        val segmentStart = maxOf(text.lastIndexOf(',', caret - 1), text.lastIndexOf('\n', caret - 1)) + 1
        val segment = text.substring(segmentStart, caret)

        val lora = segment.lastIndexOf(LORA_START, ignoreCase = true)
        if (lora >= 0 && segment.indexOf('>', lora) < 0) {
            val name = segment.substring(lora + LORA_START.length)
            if (':' in name) return null // its strength is being typed
            return TypedFragment(TypedFragment.Kind.LORA, segmentStart + lora, end, name.trim())
        }

        // Inside a wildcard: an odd number of "__" before the caret.
        if (Regex("__").findAll(segment).count() % 2 == 1) {
            val open = segment.lastIndexOf("__")
            val name = segment.substring(open + 2)
            if (name.any { it.isWhitespace() }) return null
            return TypedFragment(TypedFragment.Kind.WILDCARD, segmentStart + open, end, name)
        }

        // A tag: after the separator, the spaces and the brackets that open a weight.
        var start = segmentStart
        while (start < caret && (text[start].isWhitespace() || text[start] in "([{")) start++
        val typed = text.substring(start, caret)
        if (typed.trim().length < MIN_TAG_CHARS) return null
        return TypedFragment(TypedFragment.Kind.TAG, start, end, typed)
    }

    /**
     * [insertion] in place of [fragment], followed by ", " (an existing comma after it is reused), the caret after that.
     * Inside a weight (`(lo|:1.2)`) nothing is added, so the weight stays whole; a LoRA name typed before its strength
     * (`<lora:de|:0.8>`) keeps that strength.
     */
    fun insert(
        text: String,
        fragment: TypedFragment,
        insertion: String,
    ): TypedEdit {
        val after = text.substring(fragment.end)
        if (after.firstOrNull()?.let { it in ":)]}>" } == true) {
            val name = if (fragment.kind == TypedFragment.Kind.LORA) insertion.substringBeforeLast(':') else insertion
            val head = text.substring(0, fragment.start) + name
            return TypedEdit(head + after, head.length)
        }
        val head = text.substring(0, fragment.start) + insertion
        var rest = after.trimStart(' ')
        if (rest.startsWith(",")) rest = rest.substring(1).trimStart(' ')
        return TypedEdit("$head, $rest", head.length + 2)
    }

    /** Names (wildcards or LoRAs) for [query]: those starting with it, then those containing it, alphabetically. */
    fun matchNames(
        names: List<String>,
        query: String,
        limit: Int = TagList.MAX_RESULTS,
    ): List<String> {
        val q = query.trim().lowercase(Locale.ROOT)
        val sorted = names.distinct().sortedBy { it.lowercase(Locale.ROOT) }
        if (q.isEmpty()) return sorted.take(limit)
        val starting = sorted.filter { it.lowercase(Locale.ROOT).startsWith(q) }
        val containing = sorted.filter { q in it.lowercase(Locale.ROOT) && it !in starting }
        return (starting + containing).take(limit)
    }
}

/** One chip of the strip: what it shows and what a tap writes into the prompt. */
data class Suggestion(
    val label: String,
    val insertion: String,
    val kind: TypedFragment.Kind,
    val category: Int = 0,
    val count: Int = 0,
    val alias: String? = null,
)

object Suggestions {
    /** The chips for [fragment]: tags from [tags], or the app's wildcards, or the server's LoRAs. */
    fun forFragment(
        fragment: TypedFragment?,
        tags: TagList?,
        rules: TagInsertRules,
        wildcards: List<String>,
        loras: List<String>,
    ): List<Suggestion> =
        when (fragment?.kind) {
            null -> emptyList()
            TypedFragment.Kind.TAG ->
                tags?.search(fragment.query).orEmpty().map {
                    Suggestion(
                        rules.label(it.name),
                        rules.format(it.name),
                        fragment.kind,
                        it.category,
                        it.count,
                        it.alias?.let(rules::label),
                    )
                }
            TypedFragment.Kind.WILDCARD ->
                PromptTypingRules.matchNames(wildcards, fragment.query).map { Suggestion("__${it}__", "__${it}__", fragment.kind) }
            // The strength ForgeRepository.addLora gives a LoRA picked from the list.
            TypedFragment.Kind.LORA ->
                PromptTypingRules.matchNames(loras, fragment.query).map { Suggestion(it, "<lora:$it:1.0>", fragment.kind) }
        }

    /** A post count as the chip shows it: 950, 12.3k, 4.4M. */
    fun compactCount(count: Int): String {
        fun oneDecimal(value: Double) = String.format(Locale.US, "%.1f", value).removeSuffix(".0")
        return when {
            count >= 999_950 -> oneDecimal(count / 1_000_000.0) + "M"
            count >= 1_000 -> oneDecimal(count / 1_000.0) + "k"
            else -> count.toString()
        }
    }
}

/**
 * What the main screen gives up while a prompt is typed with the keyboard up, from the space left above the keyboard
 * ([freeDp]: the window without the status bar and the keyboard). Decided with the strip counted, so nothing jumps
 * when a tag is started: the image preview goes first, then the top bar; below that the strip alone stays, with the
 * end of the text on its left, and when even that has no room the status bar is hidden while typing.
 */
data class TypingLayout(
    val hidePreview: Boolean = false,
    val hideTopBar: Boolean = false,
    val oneBar: Boolean = false,
    val hideStatusBar: Boolean = false,
    val stripDp: Float = STRIP_DP,
) {
    companion object {
        const val STRIP_DP = 44f
        const val STRIP_MIN_DP = 36f
        const val FIELD_MIN_DP = 72f
        const val FIELD_MAX_DP = 152f
        const val TOP_BAR_DP = 64f
        const val PREVIEW_MIN_DP = 80f

        fun of(
            freeDp: Float,
            statusBarDp: Float,
        ): TypingLayout {
            val hideStatusBar = freeDp < STRIP_DP
            val available = if (hideStatusBar) freeDp + statusBarDp else freeDp
            if (available - STRIP_DP < FIELD_MIN_DP) {
                return TypingLayout(
                    hidePreview = true,
                    hideTopBar = true,
                    oneBar = true,
                    hideStatusBar = hideStatusBar,
                    stripDp = available.coerceIn(STRIP_MIN_DP, STRIP_DP),
                )
            }
            var rest = available - STRIP_DP
            val hideTopBar = rest - FIELD_MIN_DP < TOP_BAR_DP
            if (!hideTopBar) rest -= TOP_BAR_DP
            val hidePreview = rest - minOf(FIELD_MAX_DP, rest) < PREVIEW_MIN_DP
            return TypingLayout(hidePreview = hidePreview, hideTopBar = hideTopBar)
        }
    }
}
