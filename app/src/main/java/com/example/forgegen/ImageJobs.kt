package com.example.forgegen

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/* ============================================================================
 * JOBS FROM GALLERY IMAGES (2.4.0)
 * "Upscale Selected" and "More Like This" remake a txt2img job from an image's generation data (its infotext): the
 * same prompt, seed, sampler, schedule, steps, CFG, size, clip skip and model, so the server makes the same image
 * again. The app is txt2img only (owner's rule): an upscale is that image made again with hires fix (not the extras
 * endpoint), and "More Like This" keeps the seed and mixes in a nearby variation seed (or uses nearby seeds).
 * Pure functions; ForgeGalleryManager reads the images' data and ForgeQueueManager.queueJobs queues the result.
 * ============================================================================ */
object ImageJobs {
    enum class Kind { UPSCALE, MORE_LIKE_THIS }

    val UPSCALE_SCALES = listOf(1.5f, 2f, 2.5f, 3f)
    const val DEFAULT_SCALE = 2f
    const val DEFAULT_DENOISING = 0.35f

    // Results with more pixels than this may run out of GPU memory on the server.
    const val LARGE_PIXELS = 2048 * 2048

    // "More Like This": seeds within +/-MAX_OFFSET of the image's, in pairs (+1, -1, +2, ...).
    const val MAX_OFFSET = 10
    const val DEFAULT_COUNT = 6
    const val DEFAULT_STRENGTH = 0.15f
    private const val MAX_SEED = 4_294_967_295L // Forge's seeds are unsigned 32-bit numbers

    /** An image remade as a job, or why it cannot be. */
    sealed interface Source {
        /**
         * The job the image was made with. [baseWidth]/[baseHeight] are its first pass; [hiresScale] how much its
         * hires fix enlarged it (null: none).
         */
        data class Ready(
            val payload: Txt2ImgPayloadDto,
            val baseWidth: Int,
            val baseHeight: Int,
            val hiresScale: Float?,
            val modelLabel: String,
        ) : Source

        data class Skipped(
            val reason: String,
        ) : Source
    }

    const val NO_DATA = "no generation data"
    const val UNREADABLE = "its generation data could not be read"

    /**
     * The job [info] describes. The model is looked up in the server's [models] by its hash, then its name; data
     * without a model uses [currentModel]. A model the server does not have skips the image.
     */
    fun remake(
        info: Infotext,
        models: List<ApiResource>,
        currentModel: String?,
    ): Source {
        val p = info.params
        if (p.isEmpty() || info.positivePrompt.isEmpty() && p["Steps"] == null) return Source.Skipped(NO_DATA)
        val steps = p["Steps"]?.toIntOrNull() ?: return Source.Skipped("its data has no steps")
        val seed = p["Seed"]?.toLongOrNull()?.takeIf { it >= 0 } ?: return Source.Skipped("its data has no seed")
        val (width, height) = size(p["Size"]) ?: return Source.Skipped("its data has no size")

        val modelName = p["Model"]?.takeIf { it.isNotBlank() }
        val modelHash = p["Model hash"]?.takeIf { it.isNotBlank() }
        val checkpoint: String?
        val modelLabel: String
        if (modelName == null && modelHash == null) {
            checkpoint = currentModel?.takeIf { it.isNotEmpty() }
            modelLabel = checkpoint ?: "current model"
        } else {
            if (models.isEmpty()) return Source.Skipped("the server's models are not loaded")
            val match =
                findModel(models, modelName, modelHash)
                    ?: return Source.Skipped("the server has no model ${modelName ?: modelHash}")
            checkpoint = match.title
            modelLabel = match.name
        }

        val hiresScale = hiresScale(p, width, height)
        val payload =
            Txt2ImgPayloadDto(
                prompt = info.positivePrompt,
                negative_prompt = info.negativePrompt,
                steps = steps,
                cfg_scale = p["CFG scale"]?.toFloatOrNull() ?: 7f,
                width = width,
                height = height,
                n_iter = 1,
                batch_size = 1,
                seed = seed,
                sampler_name = p["Sampler"]?.takeIf { it.isNotBlank() } ?: "Euler a",
                scheduler = p["Schedule type"]?.takeIf { it.isNotBlank() } ?: "Automatic",
                override_settings =
                    OverrideSettingsDto(
                        clipSkip = p["Clip skip"]?.toIntOrNull() ?: 1,
                        sdModelCheckpoint = checkpoint,
                        etaNoiseSeedDelta = p["ENSD"]?.toIntOrNull(),
                        randnSource = p["RNG"]?.takeIf { it.isNotBlank() },
                    ),
                enable_hr = hiresScale != null,
                hr_scale = hiresScale ?: 2f,
                hr_upscaler = p["Hires upscaler"]?.takeIf { it.isNotBlank() } ?: "Latent",
                denoising_strength = p["Denoising strength"]?.toFloatOrNull() ?: DEFAULT_DENOISING,
                save_images = true, // the results belong in the gallery
                send_images = true,
                subseed = p["Variation seed"]?.toLongOrNull() ?: -1L,
                subseed_strength = p["Variation seed strength"]?.toFloatOrNull() ?: 0f,
                hr_second_pass_steps = p["Hires steps"]?.toIntOrNull() ?: 0,
            )
        return Source.Ready(payload, width, height, hiresScale, modelLabel)
    }

    /** "832x1216" -> (832, 1216). */
    fun size(text: String?): Pair<Int, Int>? {
        val parts = text?.lowercase(Locale.US)?.split("x") ?: return null
        if (parts.size != 2) return null
        val w = parts[0].trim().toIntOrNull() ?: return null
        val h = parts[1].trim().toIntOrNull() ?: return null
        return if (w > 0 && h > 0) w to h else null
    }

    /** How much the image's hires fix enlarged it: "Hires upscale: 2", or "Hires resize: 1664x2432" over its size. */
    private fun hiresScale(
        p: Map<String, String>,
        width: Int,
        height: Int,
    ): Float? {
        p["Hires upscale"]?.toFloatOrNull()?.let { if (it > 1f) return it }
        size(p["Hires resize"])?.let { (w, h) -> return maxOf(w.toFloat() / width, h.toFloat() / height).takeIf { it > 1f } }
        return null
    }

    /** The server's model for an image: by its short hash ("Model hash"), else by its name ("Model"). */
    fun findModel(
        models: List<ApiResource>,
        name: String?,
        hash: String?,
    ): ApiResource? {
        if (hash != null) {
            val h = hash.lowercase(Locale.US)
            models
                .firstOrNull { m ->
                    m.hash?.lowercase(Locale.US)?.startsWith(h) == true || m.title.lowercase(Locale.US).contains("[$h]")
                }?.let { return it }
        }
        if (name == null) return null
        val n = name.lowercase(Locale.US)

        fun ApiResource.names() =
            listOf(
                this.name,
                title,
                title.substringBefore(" ["),
                title.substringBefore(" [").substringBeforeLast('.'),
                path.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.'),
            ).map { it.lowercase(Locale.US) }
        return models.firstOrNull { n in it.names() }
    }

    // --- Upscale Selected ---

    /** The image made again [scale] times larger with hires fix; null when it already is that large or larger. */
    fun upscale(
        source: Source.Ready,
        scale: Float,
        upscaler: String,
        denoising: Float,
    ): Txt2ImgPayloadDto? {
        if ((source.hiresScale ?: 1f) >= scale) return null
        return source.payload.copy(
            enable_hr = true,
            hr_scale = scale,
            hr_upscaler = upscaler,
            denoising_strength = denoising,
        )
    }

    /** The size an upscale makes: Forge rounds the first pass times the scale down. */
    fun upscaledSize(
        source: Source.Ready,
        scale: Float,
    ): Pair<Int, Int> = (source.baseWidth * scale).toInt() to (source.baseHeight * scale).toInt()

    fun upscaleLabel(scale: Float) = "Upscale ×${scaleText(scale)}"

    fun scaleText(scale: Float): String = if (scale == scale.roundToInt().toFloat()) scale.roundToInt().toString() else scale.toString()

    // --- More Like This ---

    /**
     * [count] seed offsets around [seed] in pairs (+1, -1, +2, -2, ...), at most +/-MAX_OFFSET, leaving out seeds a
     * server cannot use (below 0 or above 2^32 - 1).
     */
    fun seedOffsets(
        seed: Long,
        count: Int,
    ): List<Int> {
        val out = mutableListOf<Int>()
        for (k in 1..MAX_OFFSET) {
            for (offset in intArrayOf(k, -k)) {
                if (out.size < count && seed + offset in 0..MAX_SEED) out += offset
            }
        }
        return out
    }

    /**
     * More images like [source]: [similar] keeps its seed and mixes in the neighbouring variation seeds with
     * [strength] (same composition, small changes); otherwise the neighbouring seeds themselves (new images with
     * the same prompt). Each with its label for the queue.
     */
    fun moreLikeThis(
        source: Source.Ready,
        similar: Boolean,
        count: Int,
        strength: Float,
    ): List<Pair<Txt2ImgPayloadDto, String>> {
        val seed = source.payload.seed
        return seedOffsets(seed, count).map { offset ->
            val payload =
                if (similar) {
                    source.payload.copy(subseed = seed + offset, subseed_strength = strength)
                } else {
                    source.payload.copy(seed = seed + offset, subseed = -1L, subseed_strength = 0f)
                }
            payload to "More Like This · $seed ${if (offset > 0) "+" else "−"}${abs(offset)}"
        }
    }
}
