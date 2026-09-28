package com.example.forgegen

import java.util.Locale

/* ============================================================================
 * MODEL SETTINGS (3.0.0, the owner's ideas 4 and 6)
 * The user tells the app what a checkpoint is (SD, SDXL or FLUX) and which of the server's modules it needs: SD a
 * VAE, FLUX a VAE and its text encoders (plus the distilled CFG of FLUX dev). "Auto", the default, sends nothing, as
 * before. Optionally a model has its own default settings, applied when it is picked. Pure rules; kept in
 * AppConfig.modelSettings under the model's key.
 * ============================================================================ */

enum class ModelType(
    val label: String,
) {
    AUTO("Auto"),
    SD("SD"),
    SDXL("SDXL"),
    FLUX("FLUX"),
    ;

    companion object {
        fun of(name: String?): ModelType = entries.firstOrNull { it.name == name } ?: AUTO
    }
}

/** A module the server offers (`/sdapi/v1/sd-modules`): a VAE, a text encoder, or unknown (from its folder). */
data class ServerModule(
    val name: String,
    val kind: Kind,
) {
    enum class Kind { VAE, TEXT_ENCODER, OTHER }
}

/**
 * How the server takes modules: FORGE lists VAEs and text encoders (`sd-modules`) and takes them per job in
 * `forge_additional_modules`; A1111 lists only VAEs (`sd-vae`) and takes one in `sd_vae`; NONE lists neither.
 */
enum class ModuleSupport { FORGE, A1111, NONE }

/** The settings a model starts with when "Use Model Defaults" is on. All have values, so old saved data reads. */
data class ModelDefaults(
    val width: Int = 1024,
    val height: Int = 1024,
    val steps: Int = 20,
    val cfgScale: Float = 7f,
    val sampler: String = "Euler a",
    val scheduler: String = "Automatic",
    val clipSkip: Int = 1,
)

data class ModelSettings(
    // A ModelType name.
    val type: String = ModelType.AUTO.name,
    // A module name from the server's list; null: the one built into the checkpoint.
    val vae: String? = null,
    val textEncoders: List<String> = emptyList(),
    val distilledCfg: Float = DEFAULT_DISTILLED_CFG,
    // Owner's idea 6: only when the user ticks it.
    val useDefaults: Boolean = false,
    val defaults: ModelDefaults? = null,
) {
    val modelType: ModelType get() = ModelType.of(type)

    companion object {
        const val DEFAULT_DISTILLED_CFG = 3.5f
    }
}

object ModelSettingsRules {
    private val MODEL_EXTENSIONS = listOf(".safetensors", ".ckpt", ".gguf", ".pt", ".pth", ".bin", ".sft")

    /** The key a model's settings are kept under: its file name without folder, extension and "[hash]". */
    fun key(model: String): String {
        val name =
            model
                .substringBefore(" [")
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .trim()
        val extension = MODEL_EXTENSIONS.firstOrNull { name.endsWith(it, ignoreCase = true) }
        return if (extension == null) name else name.dropLast(extension.length)
    }

    /** The settings of [model] (a name or a title); a model never set up has the defaults ("Auto"). */
    fun of(
        all: Map<String, ModelSettings>,
        model: String?,
    ): ModelSettings = model?.takeIf { it.isNotBlank() }?.let { all[key(it)] } ?: ModelSettings()

    /** A module of the server's list; its kind from the folder it is in (models/VAE, models/text_encoder). */
    fun module(
        modelName: String?,
        filename: String?,
    ): ServerModule? {
        val path = filename.orEmpty().replace('\\', '/')
        val name = modelName?.takeIf { it.isNotBlank() } ?: path.substringAfterLast('/').takeIf { it.isNotBlank() } ?: return null
        val folder = path.substringBeforeLast('/', "").lowercase(Locale.ROOT)
        val kind =
            when {
                Regex("(^|/)(vae|vae-approx)(/|$)").containsMatchIn(folder) -> ServerModule.Kind.VAE
                Regex("text[_-]?encoders?|(^|/)clip(/|$)|(^|/)t5").containsMatchIn(folder) -> ServerModule.Kind.TEXT_ENCODER
                else -> ServerModule.Kind.OTHER
            }
        return ServerModule(name, kind)
    }

    /** The modules a VAE may be picked from (the unknown ones too: the user knows their files). */
    fun vaes(modules: List<ServerModule>) = modules.filter { it.kind != ServerModule.Kind.TEXT_ENCODER }.map { it.name }

    fun textEncoders(modules: List<ServerModule>) = modules.filter { it.kind != ServerModule.Kind.VAE }.map { it.name }

    /**
     * [payload] with what [settings] say the model needs. Auto changes nothing. SD sends its VAE (none: the built-in
     * one), SDXL the built-in VAE, FLUX its VAE and text encoders and its distilled CFG (an image's own distilled
     * CFG, remade from its data, is kept).
     */
    fun applyTo(
        payload: Txt2ImgPayloadDto,
        settings: ModelSettings,
        support: ModuleSupport,
    ): Txt2ImgPayloadDto {
        val type = settings.modelType
        if (type == ModelType.AUTO) return payload
        val modules =
            when (type) {
                ModelType.SD -> listOfNotNull(settings.vae)
                ModelType.FLUX -> listOfNotNull(settings.vae) + settings.textEncoders
                else -> emptyList()
            }
        val override =
            when (support) {
                ModuleSupport.FORGE -> payload.override_settings.copy(forgeAdditionalModules = modules)
                ModuleSupport.A1111 ->
                    if (type == ModelType.FLUX) {
                        payload.override_settings
                    } else {
                        payload.override_settings.copy(sdVae = settings.vae.takeIf { type == ModelType.SD } ?: AUTOMATIC_VAE)
                    }
                ModuleSupport.NONE -> payload.override_settings
            }
        val distilled = if (type == ModelType.FLUX) payload.distilled_cfg_scale ?: settings.distilledCfg else payload.distilled_cfg_scale
        return payload.copy(override_settings = override, distilled_cfg_scale = distilled)
    }

    // A1111's value for "the VAE of the checkpoint".
    const val AUTOMATIC_VAE = "Automatic"

    /** What the model row says about its settings: "SDXL", "FLUX · ae · 2 text encoders", "SD · defaults". */
    fun summary(settings: ModelSettings): String {
        val type = settings.modelType
        val parts = mutableListOf(if (type == ModelType.AUTO) "Model" else type.label)
        if (type == ModelType.SD || type == ModelType.FLUX) settings.vae?.let { parts += key(it) }
        if (type == ModelType.FLUX && settings.textEncoders.isNotEmpty()) {
            parts += "${settings.textEncoders.size} text ${if (settings.textEncoders.size == 1) "encoder" else "encoders"}"
        }
        if (settings.useDefaults && settings.defaults != null) parts += "defaults"
        return parts.joinToString(" · ")
    }

    fun defaultsOf(state: AppState) =
        ModelDefaults(
            width = state.width,
            height = state.height,
            steps = state.steps,
            cfgScale = state.cfgScale,
            sampler = state.sampler,
            scheduler = state.scheduler,
            clipSkip = state.clipSkip,
        )

    fun applyDefaults(
        state: AppState,
        defaults: ModelDefaults,
    ) = state.copy(
        width = defaults.width,
        height = defaults.height,
        aspectRatio = "Custom",
        steps = defaults.steps,
        cfgScale = defaults.cfgScale,
        sampler = defaults.sampler,
        scheduler = defaults.scheduler,
        clipSkip = defaults.clipSkip,
    )

    fun describe(defaults: ModelDefaults): String =
        "${defaults.width}×${defaults.height} · ${defaults.steps} steps · CFG ${formatCfg(defaults.cfgScale)} · " +
            "${defaults.sampler} · ${defaults.scheduler} · clip skip ${defaults.clipSkip}"

    fun formatCfg(value: Float): String = String.format(Locale.US, "%.1f", value).removeSuffix(".0")
}
