package com.example.forgegen

import java.util.Locale

/* ============================================================================
 * SERVER STYLES AND EMBEDDINGS (3.1.0)
 * Styles are the ones saved on the server (styles.csv, /sdapi/v1/prompt-styles). The chosen ones go with a job as
 * its "styles", and the server adds them to the prompts, so the prompt stays as written; "Paste into Prompt" writes
 * them in instead, the way the web UI does. Off unless AppConfig.serverStyles is on (the owner's decision).
 * Embeddings (Textual Inversion, /sdapi/v1/embeddings) are used by writing their name in a prompt; the server lists
 * the ones it loaded for the current model and those it skipped as made for another one.
 * ============================================================================ */

data class PromptStyle(
    val name: String,
    val prompt: String,
    val negativePrompt: String,
)

object PromptStyles {
    /** A style merged into a prompt as the web UI does: in place of "{prompt}", else after it with ", ". */
    fun merge(
        stylePrompt: String,
        prompt: String,
    ): String =
        if ("{prompt}" in stylePrompt) {
            stylePrompt.replace("{prompt}", prompt)
        } else {
            listOf(prompt.trim(), stylePrompt.trim()).filter { it.isNotEmpty() }.joinToString(", ")
        }

    /** [state] with its chosen styles written into both prompts, in their order, and none chosen any more. */
    fun pasteInto(
        state: AppState,
        styles: List<PromptStyle>,
    ): AppState {
        val chosen = state.styles.mapNotNull { name -> styles.firstOrNull { it.name == name } }
        var positive = state.positivePrompt
        var negative = state.negativePrompt
        chosen.forEach {
            positive = merge(it.prompt, positive)
            negative = merge(it.negativePrompt, negative)
        }
        return state.copy(positivePrompt = positive, negativePrompt = negative, styles = emptyList())
    }

    /** The styles a job sends: the chosen ones the setting allows, null (not sent) when there are none. */
    fun forJob(
        enabled: Boolean,
        chosen: List<String>,
    ): List<String>? = chosen.takeIf { enabled && it.isNotEmpty() }

    /** A style's prompts for the list: "+ ..." and "- ...", without "{prompt}". */
    fun preview(text: String): String =
        text
            .replace("{prompt}", "")
            .trim()
            .trim(',')
            .trim()
}

data class EmbeddingList(
    val loaded: List<String> = emptyList(),
    val skipped: List<String> = emptyList(),
) {
    /** The loaded ones first, then the skipped ones, each alphabetically. */
    val all: List<String> get() = loaded.sortedBy { it.lowercase(Locale.ROOT) } + skipped.sortedBy { it.lowercase(Locale.ROOT) }

    fun isSkipped(name: String) = name in skipped && name !in loaded
}
