package com.example.forgegen.ui.components

import java.util.Locale
import kotlin.math.abs

/* ============================================================================
 * PROMPT TAG HELPERS
 * Pure string functions behind the tag chips, kept free of Compose so they can be unit-tested.
 * ============================================================================ */

object PromptParser {
    val LORA = Regex("<lora:[^>]+>")
    val WEIGHT_PAREN = Regex("\\([^)]+\\)")
    val WEIGHT_BRACKET = Regex("\\[[^]]+]")
    val TAG_STRENGTH = Regex("^\\((.*):([0-9.]+)\\)$")
}

/** Splits a prompt on top-level commas; commas inside (), [] or {} stay part of the tag. */
fun parseTags(prompt: String): List<String> {
    val result = mutableListOf<String>()
    val currentTag = java.lang.StringBuilder()
    var depth = 0

    for (char in prompt) {
        when (char) {
            '(', '[', '{' -> {
                depth++
                currentTag.append(char)
            }
            ')', ']', '}' -> {
                depth = maxOf(0, depth - 1)
                currentTag.append(char)
            }
            ',' -> {
                if (depth == 0) {
                    if (currentTag.isNotBlank()) {
                        result.add(currentTag.toString().trim())
                    }
                    currentTag.clear()
                } else {
                    currentTag.append(char)
                }
            }
            else -> currentTag.append(char)
        }
    }
    if (currentTag.isNotBlank()) {
        result.add(currentTag.toString().trim())
    }
    return result
}

fun countTokens(text: String): Int {
    if (text.isBlank()) return 0
    val words = parseTags(text)
    return words.size
}

/** A tag without its attention syntax, e.g. "(cat:1.2)" -> ("cat", "1.2") and "(cat)" -> ("cat", "1.1"). */
data class TagWeight(
    val base: String,
    val weight: String,
)

fun splitTagWeight(tag: String): TagWeight {
    val trimmed = tag.trim()
    val match = PromptParser.TAG_STRENGTH.find(trimmed)
    return when {
        match != null -> TagWeight(match.groupValues[1], match.groupValues[2])
        // In A1111/Forge plain parentheses mean a weight of 1.1.
        isWrappedInParens(trimmed) -> TagWeight(trimmed.substring(1, trimmed.length - 1), "1.1")
        else -> TagWeight(trimmed, "1.0")
    }
}

/** Builds "(base:weight)"; a weight of about 1.0 gives the bare tag (floats are never compared with ==). */
fun withTagWeight(
    base: String,
    weight: Float,
): String = if (abs(weight - 1.0f) < 0.05f) base else "($base:${String.format(Locale.US, "%.1f", weight)})"

fun adjustTagStrength(
    tag: String,
    delta: Float,
): String {
    val (base, weight) = splitTagWeight(tag)
    val newStrength = ((weight.toFloatOrNull() ?: 1.0f) + delta).coerceIn(0.1f, 3.0f)
    return withTagWeight(base, newStrength)
}

/** True for "(a, b)" but false for "(a) (b)", where the first parenthesis closes before the end. */
private fun isWrappedInParens(text: String): Boolean {
    if (text.length < 2 || text.first() != '(' || text.last() != ')') return false
    var depth = 0
    text.forEachIndexed { i, c ->
        if (c == '(') depth++
        if (c == ')') depth--
        if (depth == 0 && i < text.lastIndex) return false
    }
    return depth == 0
}
