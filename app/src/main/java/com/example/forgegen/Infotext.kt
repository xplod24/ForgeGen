package com.example.forgegen

/* ============================================================================
 * INFOTEXT
 * The generation parameters Forge writes into every image: the prompt (any number of lines), then a line
 * starting with "Negative prompt:" (which may also span lines), then one line of "Key: value" pairs such as
 * "Steps: 20, Sampler: Euler a, Seed: 1, Model: name". Parsed the way Forge itself reads it back
 * (parse_generation_parameters), so every screen agrees on what an image contains.
 * ============================================================================ */
data class Infotext(
    val positivePrompt: String,
    val negativePrompt: String,
    val params: Map<String, String>,
) {
    val model: String get() = params["Model"].orEmpty()
    val sampler: String get() = params["Sampler"].orEmpty()
    val seed: String get() = params["Seed"].orEmpty()

    /** LoRA names used in the prompt: "detail" for <lora:detail:0.8> as well as for <lora:detail>. */
    val loras: List<String>
        get() =
            LORA_NAME
                .findAll(positivePrompt)
                .map { it.groupValues[1].trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .toList()

    companion object {
        // Forge's re_param: a key, then a plain value up to the next comma or a quoted value that may contain commas.
        private val PARAM = Regex("""\s*(\w[\w \-/]+):\s*("(?:\\.|[^\\"])+"|[^,]*)(?:,|$)""")
        private val LORA_NAME = Regex("""<lora:([^:>]+)(?::[^>]*)?>""")
        private const val NEGATIVE = "Negative prompt:"

        fun parse(text: String): Infotext {
            val lines = text.trim().split("\n").map { it.trim() }
            // The last line holds the parameters only if it looks like them (at least three "Key: value" pairs).
            val hasParams = PARAM.findAll(lines.last()).count() >= 3
            val promptLines = if (hasParams) lines.dropLast(1) else lines

            val prompt = StringBuilder()
            val negative = StringBuilder()
            var inNegative = false
            for (raw in promptLines) {
                var line = raw
                if (line.startsWith(NEGATIVE)) {
                    inNegative = true
                    line = line.removePrefix(NEGATIVE).trim()
                }
                val target = if (inNegative) negative else prompt
                if (target.isNotEmpty()) target.append('\n')
                target.append(line)
            }

            val params =
                if (hasParams) {
                    PARAM.findAll(lines.last()).associate { it.groupValues[1] to unquote(it.groupValues[2].trim()) }
                } else {
                    emptyMap()
                }
            return Infotext(prompt.toString().trim(), negative.toString().trim(), params)
        }

        private fun unquote(value: String): String =
            if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
                value.substring(1, value.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
            } else {
                value
            }
    }
}
