package com.example.forgegen

import com.example.forgegen.ui.components.parseTags
import com.example.forgegen.ui.components.splitTagWeight
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.util.Locale

/* ============================================================================
 * LORA METADATA (3.1.0)
 * What a LoRA's training left in its file, as /sdapi/v1/loras lists it: the model it was trained for
 * (ss_base_model_version, modelspec.architecture) and the tags of its training captions (ss_tag_frequency), whose
 * most used ones are usually its trigger words. Forge itself does not tell a LoRA's model (its web UI filters only by
 * a version typed in by hand), so the app reads it here. The answer holds every LoRA's whole metadata (megabytes with
 * many LoRAs), so it is read as it streams and only these few things are kept.
 * ============================================================================ */

/** The model a LoRA was trained for; [type] is the checkpoint type it fits (null: none the app knows). */
enum class LoraBase(
    val label: String,
    val type: ModelType?,
) {
    SD1("SD 1.5", ModelType.SD),
    SD2("SD 2", ModelType.SD),
    SDXL("SDXL", ModelType.SDXL),
    FLUX("FLUX", ModelType.FLUX),
    OTHER("Other", null),
}

data class LoraTag(
    val tag: String,
    val count: Int,
)

data class LoraInfo(
    val name: String,
    val path: String,
    // Null when the file does not say.
    val base: LoraBase? = null,
    // The architecture as the file names it, shown for LoraBase.OTHER ("sd3", ...).
    val architecture: String? = null,
    // The most used tags of its training captions, most used first.
    val tags: List<LoraTag> = emptyList(),
    val resolution: String? = null,
    val epochs: Int? = null,
) {
    val baseLabel: String? get() = base?.let { if (it == LoraBase.OTHER) architecture ?: it.label else it.label }

    /** Whether it fits a checkpoint of [type]; null when that is not known (Auto, or a LoRA that does not say). */
    fun fits(type: ModelType): Boolean? =
        when {
            type == ModelType.AUTO || base == null -> null
            else -> base.type == type
        }
}

object LoraMetadata {
    const val TOP_TAGS = 12

    /** The model from the training metadata: "sdxl_base_v1-0", "sd_v1", "flux1", "stable-diffusion-xl-v1-base/lora"... */
    fun base(
        baseModelVersion: String?,
        architecture: String?,
        v2: String?,
    ): LoraBase? {
        val arch = architecture.orEmpty().lowercase(Locale.ROOT)
        val version = baseModelVersion.orEmpty().lowercase(Locale.ROOT)
        return when {
            "flux" in arch || version.startsWith("flux") -> LoraBase.FLUX
            "stable-diffusion-xl" in arch || version.startsWith("sdxl") -> LoraBase.SDXL
            "stable-diffusion-v2" in arch || version.startsWith("sd_v2") || v2.equals("true", ignoreCase = true) -> LoraBase.SD2
            "stable-diffusion-v1" in arch || version.startsWith("sd_v1") -> LoraBase.SD1
            arch.isNotEmpty() || version.isNotEmpty() -> LoraBase.OTHER
            else -> null
        }
    }

    /** The name an unknown architecture is shown with: "stable-diffusion-v3-medium/lora" -> "stable-diffusion-v3-medium". */
    fun architectureLabel(
        architecture: String?,
        baseModelVersion: String?,
    ): String? = architecture?.substringBefore('/')?.takeIf { it.isNotBlank() } ?: baseModelVersion?.takeIf { it.isNotBlank() }

    /** The most used tags over all the training folders ([frequency]: folder -> tag -> count). */
    fun topTags(
        frequency: Map<String, Map<String, Int>>,
        limit: Int = TOP_TAGS,
    ): List<LoraTag> {
        val counts = HashMap<String, Int>()
        frequency.values.forEach { folder ->
            folder.forEach { (tag, count) ->
                val clean = tag.trim()
                if (clean.isNotEmpty() && count > 0) counts[clean] = (counts[clean] ?: 0) + count
            }
        }
        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { LoraTag(it.key, it.value) }
    }

    /** "(1024, 1024)", "[1024,1024]", "1024,1024" or "1024" as "1024×1024"; null when there is no number. */
    fun resolution(value: String?): String? {
        val numbers = Regex("\\d+").findAll(value.orEmpty()).map { it.value }.toList()
        return when (numbers.size) {
            0 -> null
            1 -> "${numbers[0]}×${numbers[0]}"
            else -> "${numbers[0]}×${numbers[1]}"
        }
    }

    /** The server's /sdapi/v1/loras answer, read as it streams: every LoRA with what the app shows of it. */
    fun readList(reader: JsonReader): List<LoraInfo> {
        val list = mutableListOf<LoraInfo>()
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() == JsonToken.BEGIN_OBJECT) list += readLora(reader) else reader.skipValue()
        }
        reader.endArray()
        return list
    }

    private fun readLora(reader: JsonReader): LoraInfo {
        var name = ""
        var path = ""
        var baseVersion: String? = null
        var architecture: String? = null
        var v2: String? = null
        var resolution: String? = null
        var epochs: Int? = null
        var tags = emptyList<LoraTag>()
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "name" -> name = readText(reader).orEmpty()
                "path" -> path = readText(reader).orEmpty()
                "metadata" ->
                    if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                        reader.skipValue()
                    } else {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "ss_base_model_version" -> baseVersion = readText(reader)
                                "modelspec.architecture" -> architecture = readText(reader)
                                "ss_v2" -> v2 = readText(reader)
                                "ss_resolution" -> resolution = resolution(readText(reader))
                                "ss_num_epochs" -> epochs = readText(reader)?.toDoubleOrNull()?.toInt()
                                "ss_tag_frequency" -> tags = topTags(readFrequency(reader))
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                    }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        val base = base(baseVersion, architecture, v2)
        return LoraInfo(
            name = name,
            path = path,
            base = base,
            architecture = if (base == LoraBase.OTHER) architectureLabel(architecture, baseVersion) else null,
            tags = tags,
            resolution = resolution,
            epochs = epochs,
        )
    }

    /** A string, number or boolean as text; anything else is skipped (null). */
    private fun readText(reader: JsonReader): String? =
        when (reader.peek()) {
            JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
            JsonToken.BOOLEAN -> reader.nextBoolean().toString()
            else -> {
                reader.skipValue()
                null
            }
        }

    /** ss_tag_frequency: an object (Forge parses metadata values that look like JSON) or the JSON text itself. */
    private fun readFrequency(reader: JsonReader): Map<String, Map<String, Int>> =
        when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> frequencyOf(JsonParser.parseReader(reader))
            JsonToken.STRING -> runCatching { frequencyOf(JsonParser.parseString(reader.nextString())) }.getOrDefault(emptyMap())
            else -> {
                reader.skipValue()
                emptyMap()
            }
        }

    private fun frequencyOf(element: JsonElement): Map<String, Map<String, Int>> {
        if (!element.isJsonObject) return emptyMap()
        return element.asJsonObject.entrySet().associate { (folder, tags) ->
            folder to
                if (tags.isJsonObject) {
                    tags.asJsonObject
                        .entrySet()
                        .mapNotNull { (tag, count) -> runCatching { tag to count.asInt }.getOrNull() }
                        .toMap()
                } else {
                    emptyMap()
                }
        }
    }

    /** The key a LoRA is found under by its file: the path without case and with "/" only. */
    fun pathKey(path: String): String = path.replace('\\', '/').lowercase(Locale.ROOT)
}

/** The LoRAs' metadata found by file (or by name when the paths differ). */
class LoraInfoIndex(
    list: List<LoraInfo> = emptyList(),
) {
    private val byPath = list.filter { it.path.isNotEmpty() }.associateBy { LoraMetadata.pathKey(it.path) }
    private val byName = list.associateBy { it.name.lowercase(Locale.ROOT) }

    val size = list.size

    fun of(
        name: String,
        path: String,
    ): LoraInfo? = path.takeIf { it.isNotEmpty() }?.let { byPath[LoraMetadata.pathKey(it)] } ?: byName[name.lowercase(Locale.ROOT)]
}

/* ============================================================================
 * PROMPT EDITS (3.1.0)
 * Tags added to a prompt by a tap (a LoRA's trigger words, an embedding), and whether a prompt already has one.
 * ============================================================================ */
object PromptEdits {
    private fun normal(tag: String): String =
        splitTagWeight(tag)
            .base
            .replace('_', ' ')
            .trim()
            .lowercase(Locale.ROOT)

    /** Whether [tag] is one of [prompt]'s tags, whatever its weight, case or underscores. */
    fun hasTag(
        prompt: String,
        tag: String,
    ): Boolean {
        val wanted = normal(tag)
        if (wanted.isEmpty()) return false
        return parseTags(prompt).any { normal(it) == wanted }
    }

    /** [prompt] with those of [tags] it does not have yet added at its end, each after ", ". */
    fun addTags(
        prompt: String,
        tags: List<String>,
    ): String {
        var result = prompt.trimEnd()
        tags.map { it.trim() }.filter { it.isNotEmpty() }.forEach { tag ->
            if (hasTag(result, tag)) return@forEach
            result =
                when {
                    result.isEmpty() -> tag
                    result.endsWith(",") -> "$result $tag"
                    else -> "$result, $tag"
                }
        }
        return result
    }
}
