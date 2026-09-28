package com.example.forgegen

import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/* ============================================================================
 * MODEL AND LORA PREVIEWS (3.0.1)
 * A checkpoint's or LoRA's picture is a file next to it that Forge's extra networks find by name
 * (ui_extra_networks.find_preview: "<name>.<ext>" or "<name>.preview.<ext>"), served for the model folders by
 * /sd_extra_networks/thumb. The app used to ask only for "<name>.preview.png" through "file=", so pictures saved as
 * .png, .jpg or .webp never showed. Now it tries the usual names in turn and remembers, for as long as the app runs,
 * which one the server had or that it had none, so a list does not ask again.
 * ============================================================================ */
object ResourcePreviews {
    // The usual names first: Civitai Helper saves ".preview.png", the web UI's "Replace preview" saves ".png".
    val SUFFIXES = listOf(".preview.png", ".png", ".jpg", ".jpeg", ".webp", ".preview.jpg", ".preview.jpeg", ".preview.webp")

    private val MODEL_EXTENSIONS = listOf(".safetensors", ".ckpt", ".gguf", ".sft", ".pth", ".pt", ".bin")

    const val NONE = -1

    // The first candidate (it names the server and the model) -> the index that loaded, or NONE.
    private val found = ConcurrentHashMap<String, Int>()

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
    ) {
        candidates.firstOrNull()?.let { found[it] = index }
    }

    /** The server has no file at [index]: the next one to try, or NONE (remembered) after the last. */
    fun missing(
        candidates: List<String>,
        index: Int,
    ): Int {
        val next = index + 1
        if (next in candidates.indices) return next
        candidates.firstOrNull()?.let { found[it] = NONE }
        return NONE
    }

    /** The picture that loaded before (to load it ahead); null while unknown or when there is none. */
    fun known(candidates: List<String>): String? {
        val key = candidates.firstOrNull() ?: return null
        return found[key]?.takeIf { it >= 0 }?.let { candidates.getOrNull(it) }
    }

    /** The model lists were refreshed: pictures may have been added. */
    fun forget() = found.clear()
}
