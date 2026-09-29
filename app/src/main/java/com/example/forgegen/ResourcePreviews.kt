package com.example.forgegen

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/* ============================================================================
 * MODEL AND LORA PREVIEWS (3.0.1)
 * A checkpoint's or LoRA's picture is a file next to it that Forge's extra networks find by name
 * (ui_extra_networks.find_preview: "<name>.<ext>" or "<name>.preview.<ext>"), served for the model folders by
 * /sd_extra_networks/thumb. The app used to ask only for "<name>.preview.png" through "file=", so pictures saved as
 * .png, .jpg or .webp never showed. Now it tries the usual names in turn and remembers which one the server had or
 * that it had none, so a list does not ask again; since 3.4.0 also across starts (ForgeRepository saves [saved]).
 * ============================================================================ */
object ResourcePreviews {
    // The usual names first: Civitai Helper saves ".preview.png", the web UI's "Replace preview" saves ".png".
    val SUFFIXES = listOf(".preview.png", ".png", ".jpg", ".jpeg", ".webp", ".preview.jpg", ".preview.jpeg", ".preview.webp")

    private val MODEL_EXTENSIONS = listOf(".safetensors", ".ckpt", ".gguf", ".sft", ".pth", ".pt", ".bin")

    const val NONE = -1

    // The first candidate (it names the server and the model) -> the index that loaded, or NONE.
    private val found = ConcurrentHashMap<String, Int>()

    // At most this many are saved (the ones learned first); more are still remembered while the app runs.
    const val MAX_SAVED = 2000

    private val _changes = MutableStateFlow(0)

    /** Counts what was learned, so it can be saved. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    private fun remember(
        candidates: List<String>,
        index: Int,
    ) {
        val key = candidates.firstOrNull() ?: return
        if (found.put(key, index) != index) _changes.update { it + 1 }
    }

    /** What was learned, to save. */
    fun saved(): Map<String, Int> = found.entries.take(MAX_SAVED).associate { it.key to it.value }

    /** What an earlier start learned; what this one learned meanwhile wins. */
    fun restore(saved: Map<String, Int>) {
        saved.forEach { (key, index) -> if (index >= NONE && index < SUFFIXES.size) found.putIfAbsent(key, index) }
    }

    /** The model file's path without its extension; ".pt" only at the end, never inside a name. */
    fun stem(path: String): String {
        val extension = MODEL_EXTENSIONS.firstOrNull { path.endsWith(it, ignoreCase = true) } ?: return path
        return path.dropLast(extension.length)
    }

    /** The pictures [modelPath] (the file's path on the server) may have, in the order to try them. */
    fun candidates(
        serverUrl: String,
        modelPath: String,
    ): List<String> {
        if (serverUrl.isEmpty() || modelPath.isEmpty()) return emptyList()
        val stem = stem(modelPath)
        return SUFFIXES.map { "$serverUrl/sd_extra_networks/thumb?filename=" + URLEncoder.encode(stem + it, "UTF-8") }
    }

    /** Where to start: the one that loaded before, NONE when none did, else the first. */
    fun startIndex(candidates: List<String>): Int {
        val key = candidates.firstOrNull() ?: return NONE
        return found[key] ?: 0
    }

    fun loaded(
        candidates: List<String>,
        index: Int,
    ) = remember(candidates, index)

    /** The server has no file at [index]: the next one to try, or NONE (remembered) after the last. */
    fun missing(
        candidates: List<String>,
        index: Int,
    ): Int {
        val next = index + 1
        if (next in candidates.indices) return next
        remember(candidates, NONE)
        return NONE
    }

    /** The picture that loaded before (to load it ahead); null while unknown or when there is none. */
    fun known(candidates: List<String>): String? {
        val key = candidates.firstOrNull() ?: return null
        return found[key]?.takeIf { it >= 0 }?.let { candidates.getOrNull(it) }
    }

    /** The model lists were refreshed: pictures may have been added. */
    fun forget() {
        found.clear()
        _changes.update { it + 1 }
    }
}
