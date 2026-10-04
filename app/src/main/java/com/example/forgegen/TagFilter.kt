package com.example.forgegen

import com.example.forgegen.ui.components.parseTags
import com.example.forgegen.ui.components.splitTagWeight
import java.util.Locale

/* ============================================================================
 * TAG FILTER (3.6.2-1, the owner's request)
 * The gallery's search by prompt tags: the positive and the negative prompt apart, several tags at once (an image must
 * hold all of them), each tag as a part of the prompt's text or, with "Exact Tags", as a whole tag. Whole tags are
 * compared as the statistics count them (GalleryStatistics.countTags uses tagsOf): without weights and escapes, "_"
 * read as a space, any case, LoRAs and BREAK left out.
 * ============================================================================ */
object TagFilter {
    private val ANGLE_BLOCK = Regex("<[^<>]*>")
    private val WORD = Regex("[\\p{L}\\p{N}]+")

    /** [tag] as a whole tag: "(Long_Hair:1.2)" -> "long hair", "artist \(style\)" -> "artist (style)"; "" for BREAK. */
    fun normalize(tag: String): String {
        val base =
            splitTagWeight(tag)
                .base
                .replace("\\(", "(")
                .replace("\\)", ")")
                .replace('_', ' ')
                .trim()
                .lowercase(Locale.ROOT)
        return if (base.startsWith("<") || base == "break") "" else base
    }

    /**
     * The whole tags of [prompt], each once. A LoRA ("<lora:x:0.8>") is taken out first, as it is often written without
     * a comma, and a new line parts tags as a comma does.
     */
    fun tagsOf(prompt: String): Set<String> =
        parseTags(prompt.replace(ANGLE_BLOCK, ",").replace('\n', ','))
            .map { normalize(it) }
            .filter { it.isNotEmpty() }
            .toSet()

    /** What was typed into a tag field ("cat, dog,"), as the tags to add. */
    fun split(input: String): List<String> =
        input
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    /** Whether [prompt] holds every one of [tags]: as whole tags ([exact]) or as parts of its text, any case. */
    fun holdsAll(
        prompt: String,
        tags: List<String>,
        exact: Boolean,
    ): Boolean {
        if (!exact) return tags.all { prompt.contains(it.trim(), ignoreCase = true) }
        val present = tagsOf(prompt)
        return tags.all { normalize(it) in present }
    }

    /**
     * A LIKE pattern that finds at least every prompt [holdsAll] accepts with [tag], for the database to narrow the
     * search down first. A whole tag looks for its longest word only: the prompt may write it with "_", escapes or a
     * weight.
     */
    fun likePattern(
        tag: String,
        exact: Boolean,
    ): String {
        val text =
            if (exact) {
                WORD
                    .findAll(normalize(tag))
                    .map { it.value }
                    .maxByOrNull { it.length }
                    .orEmpty()
            } else {
                tag.trim()
            }
        val escaped =
            text
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_")
        return "%$escaped%"
    }
}
