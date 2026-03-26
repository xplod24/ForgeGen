package com.yourname.forgegen

import com.google.gson.annotations.SerializedName

// --- CORE CONFIGURATION ---
data class ServerProfile(
    val name: String,
    val url: String
)

data class AppConfig(
    var apiUrl: String = "http://192.168.1.90:7860",
    var galleryPath: String = "C:\\webui_forge_cu124_torch24\\webui\\outputs\\txt2img-images",
    var checkpointPath: String = "C:\\webui_forge_cu124_torch24\\webui\\models\\Stable-diffusion",
    var loraPath: String = "C:\\webui_forge_cu124_torch24\\webui\\models\\Lora",
    var language: String = "en",
    var isDarkMode: Boolean = false,
    var connectionTimeout: Int = 10,
    var receiveGenerationNotification: Boolean = true,
    var silentNotifications: Boolean = false,
    var notificationVerbosity: String = "Full",
    var keepScreenOn: Boolean = false,
    var useDynamicColor: Boolean = true,
    var swipeToBrowseGallery: Boolean = true,
    var galleryGridColumns: Int = 3,
    var serverProfiles: List<ServerProfile> = listOf(ServerProfile("Default Local", "http://192.168.1.90:7860")),
    var livePreviews: Boolean = false,
    var useNativeSecurity: Boolean = false,
    var useBiometricLock: Boolean = false,
    var overnightMode: Boolean = false,
    var autoIndexGallery: Boolean = false,
    var showGridAfterGeneration: Boolean = true,
    var showActiveTagsUI: Boolean = true
)

// --- GENERATION STATE ---
data class AppState(
    var positivePrompt: String = "",
    var negativePrompt: String = "",
    var cfgScale: Float = 7.0f,
    var steps: Int = 20,
    var width: Int = 512,
    var height: Int = 512,
    var batchCount: Int = 1,
    var batchSize: Int = 1,
    var clipSkip: Int = 1,
    var seed: Long = -1L,
    var sampler: String = "Euler a",
    var scheduler: String = "Automatic",
    var hiresFix: Boolean = false,
    var hiresScale: Float = 2.0f,
    var denoising: Float = 0.7f,
    var upscaler: String = "Latent"
)

// --- PROMPT HISTORY ---
data class PromptHistoryItem(
    val positivePrompt: String,
    val negativePrompt: String,
    val timestamp: Long
)

// --- API PAYLOADS ---
data class ApiResource(
    val title: String,
    val path: String,
    val name: String
)

data class OverrideSettings(
    @SerializedName("CLIP_stop_at_last_layers") val clipSkip: Int,
    @SerializedName("sd_model_checkpoint") val sdModelCheckpoint: String? = null
)

data class Txt2ImgPayload(
    val prompt: String,
    val negative_prompt: String,
    val steps: Int,
    val cfg_scale: Float,
    val width: Int,
    val height: Int,
    val n_iter: Int,
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
enum class GalleryMode {
    NORMAL,
    PROMPT_PICKER
}

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