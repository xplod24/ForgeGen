package com.example.forgegen

/* ============================================================================
 * INDEX DETAILS (3.6.0)
 * The generation settings the gallery index keeps besides the prompts, model, sampler, seed and LoRAs, for the
 * statistics and for opening the gallery from them: the size, steps, CFG, schedule type, hires fix, the modules (VAE
 * and text encoders), the embeddings (Forge's "TI"), clip skip and Forge's version. All of them come from the
 * infotext, which the index reads anyway (image_geninfo_batch); an image without one of them keeps null there.
 * ============================================================================ */
data class IndexDetails(
    val width: Int? = null,
    val height: Int? = null,
    val steps: Int? = null,
    val cfg: Float? = null,
    val distilledCfg: Float? = null,
    val scheduler: String? = null,
    val hiresScale: Float? = null,
    val hiresUpscaler: String? = null,
    val hiresSteps: Int? = null,
    val denoising: Float? = null,
    // "Module 1", "Module 2", ... in their order, joined by ", " (the VAE and text encoders of Forge's model types).
    val modules: String? = null,
    // The embeddings Forge used, joined by ","; Forge writes them as "TI: name, name".
    val embeddings: String? = null,
    val clipSkip: Int? = null,
    val forgeVersion: String? = null,
) {
    /** "832×1216", or null without a size. */
    val size: String? get() = if (width != null && height != null) "$width×$height" else null

    companion object {
        // Rows indexed before 3.6.0 have 0: their details are read once more (ForgeGalleryManager.fillDetails).
        const val VERSION = 1

        private val SIZE = Regex("""^\s*(\d+)\s*x\s*(\d+)\s*$""")
        private val MODULE = Regex("""^Module (\d+)$""")

        fun of(info: Infotext): IndexDetails {
            val p = info.params
            val size = p["Size"]?.let { SIZE.find(it) }
            val modules =
                p.keys
                    .mapNotNull { key ->
                        MODULE
                            .find(key)
                            ?.groupValues
                            ?.get(1)
                            ?.toIntOrNull()
                            ?.let { it to p.getValue(key) }
                    }.sortedBy { it.first }
                    .map { it.second.trim() }
                    .filter { it.isNotEmpty() }
            val embeddings =
                p["TI"]
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.distinct()
                    .orEmpty()
            return IndexDetails(
                width = size?.groupValues?.get(1)?.toIntOrNull(),
                height = size?.groupValues?.get(2)?.toIntOrNull(),
                steps = p["Steps"].int(),
                cfg = p["CFG scale"].float(),
                distilledCfg = p["Distilled CFG Scale"].float(),
                scheduler = p["Schedule type"].text(),
                hiresScale = p["Hires upscale"].float(),
                hiresUpscaler = p["Hires upscaler"].text(),
                hiresSteps = p["Hires steps"].int(),
                denoising = p["Denoising strength"].float(),
                modules = modules.joinToString(", ").ifEmpty { null },
                embeddings = embeddings.joinToString(",").ifEmpty { null },
                clipSkip = p["Clip skip"].int(),
                forgeVersion = p["Version"].text(),
            )
        }

        private fun String?.text(): String? = this?.trim()?.ifEmpty { null }

        private fun String?.int(): Int? = this?.trim()?.toIntOrNull()

        private fun String?.float(): Float? = this?.trim()?.toFloatOrNull()?.takeIf { it.isFinite() }
    }
}
