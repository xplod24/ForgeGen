package com.yourname.forgegen

import com.google.gson.annotations.SerializedName

// --- CORE CONFIGURATION ---
data class AppConfig(
    var apiUrl: String = "http://192.168.1.90:7860",
    var galleryPath: String = "C:\\webui_forge_cu124_torch24\\webui\\outputs\\txt2img-images",
    var isDarkMode: Boolean = false,
    var connectionTimeout: Int = 10,
    var updateServerUrl: String = "http://localhost/update",
    var silentNotifications: Boolean = false
)

// --- GENERATION STATE ---
data class AppState(
    var positivePrompt: String = "",
    var negativePrompt: String = "",
    var cfgScale: Float = 7.0f,
    var steps: Int = 20,
    var width: Int = 512,
    var height: Int = 512,
    var batchSize: Int = 1,
    var clipSkip: Int = 1,
    var seed: Long = -1,
    var sampler: String = "Euler a",
    var scheduler: String = "Automatic",
    var hiresFix: Boolean = false,
    var hiresScale: Float = 2.0f,
    var denoising: Float = 0.7f,
    var upscaler: String = "Latent"
)

// --- API PAYLOADS ---
data class OverrideSettings(
    @SerializedName("CLIP_stop_at_last_layers") val clipSkip: Int
)

data class Txt2ImgPayload(
    val prompt: String,
    val negative_prompt: String,
    val steps: Int,
    val cfg_scale: Float,
    val width: Int,
    val height: Int,
    val batch_size: Int,
    val seed: Long,
    val sampler_name: String,
    val scheduler: String,
    val override_settings: OverrideSettings,
    val enable_hr: Boolean,
    val hr_scale: Float,
    val hr_upscaler: String,
    val denoising_strength: Float
)

data class QueuedGeneration(
    val id: String,
    val positivePrompt: String,
    val payload: Txt2ImgPayload
)

// --- GALLERY MODELS ---
data class GalleryFileList(
    val files: List<GalleryItem> = emptyList()
)

data class GalleryItem(
    val name: String,
    val fullpath: String,
    val type: String,
    val date: String? = null,
    @SerializedName("created_time") val createdTime: String? = null,
    val size: String? = null
) {
    val isDir: Boolean get() = type == "dir"

    val displaySize: String get() {
        val sizeBytes = size?.toLongOrNull()
        return if (sizeBytes != null) "${sizeBytes / 1024} KB" else ""
    }
}