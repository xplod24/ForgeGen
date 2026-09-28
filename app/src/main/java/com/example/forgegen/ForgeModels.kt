@file:Suppress("unused")
package com.example.forgegen

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import com.google.gson.annotations.SerializedName
import java.util.Locale
import kotlin.math.roundToInt

/* ============================================================================
 * 1. DOMAIN MODELS (UI & BUSINESS LOGIC)
 * Pure business models, separated from API implementation details.
 * ============================================================================ */

data class ServerProfile(
    val name: String,
    val url: String,
)

data class GenerationPreset(
    val name: String,
    val state: AppState,
    val includePrompts: Boolean = true,
)

data class AppConfig(
    var apiUrl: String = "http://192.168.1.90:7860",
    // Both read from the server's gallery extension when it is found (ForgeGalleryManager.detectExtension, 2.1.0):
    // Forge's working folder, and the folder its txt2img images are saved to (the gallery's top folder).
    var serverBasePath: String = "",
    var galleryPath: String = "",
    // THEME_SYSTEM, THEME_LIGHT or THEME_DARK (was the switch "isDarkMode" up to 1.1.4-1).
    var themeMode: String = THEME_SYSTEM,
    var timeout: Int = 10,
    var notifOnBatchFinish: Boolean = false,
    var notifOnQueueFinish: Boolean = true,
    var notificationMode: String = "Simple",
    var keepScreenOn: Boolean = false,
    var swipeToBrowseGallery: Boolean = true,
    var serverProfiles: List<ServerProfile> = listOf(ServerProfile("Default Local", "http://192.168.1.90:7860")),
    var useNativeSecurity: Boolean = false,
    var useBiometricLock: Boolean = false,
    var overnightMode: Boolean = false,
    var showGridAfterGeneration: Boolean = true,
    var showActiveTagsUI: Boolean = true,
    var enableLogging: Boolean = false,
    var lastUpdateCheckDate: String = "",
    var defaultState: AppState = AppState(),
    var presets: List<GenerationPreset> = emptyList(),
    // The rows of the main screen's cards left open (3.0.0): MainRows.NEGATIVE, SAMPLING, SIZE.
    var mainOpenRows: List<String> = emptyList(),
    // Gallery images saved to the phone on their own: AUTO_SAVE_OFF, AUTO_SAVE_FAVORITES or AUTO_SAVE_ALL.
    var autoSaveMode: String = AUTO_SAVE_OFF,
    // With AUTO_SAVE_ALL only images newer than this (the server's "yyyy-MM-dd HH:mm:ss" format) are saved,
    // so switching it on does not download the whole existing gallery.
    var autoSaveSince: String = "",
    // The user's consent to write a report with the app's log to Downloads when the app or the server runs out of memory.
    var saveOomLogs: Boolean = false,
    // Samsung One UI 8+: the generation progress as a Live Update in the Now Bar of the lock screen (opt-in).
    var nowBarProgress: Boolean = false,
    // Privacy (1.3.0).
    var hidePromptsInNotifications: Boolean = true,
    var hideInRecents: Boolean = false,
    var blockScreenshots: Boolean = false,
    // Images saved to the phone go to the app's own folder instead of the phone's gallery (and its cloud backup).
    var savePrivately: Boolean = false,
    // Shared images leave without their generation data (prompt, seed, model).
    var shareWithoutMetadata: Boolean = false,
    // A short vibration when a batch finishes while the app is on screen (2.0.0).
    var vibrateOnFinish: Boolean = true,
    // New releases are downloaded and installed in the background (SelfUpdate, 2.0.2); off: only a notification.
    var autoInstallUpdates: Boolean = true,
    // Full-screen images zoom with a pinch or a double tap (2.1.0).
    var pinchToZoom: Boolean = true,
    // The gallery's layout, a GalleryView name (2.1.0).
    var galleryView: String = GalleryView.GRID_3.name,
    // The gallery tab used last, a GalleryTab name; the gallery opens on it (2.2.0).
    var galleryTab: String = GalleryTab.GALLERY.name,
    // Tags, wildcards and LoRAs suggested above the keyboard while a prompt is typed (2.4.2).
    var tagSuggestions: Boolean = true,
    // The styles saved on the server under the prompt (3.1.0); off unless turned on (the owner's decision).
    var serverStyles: Boolean = false,
    // What each checkpoint is and needs, and its own defaults (3.0.0), under ModelSettingsRules.key of the model.
    var modelSettings: Map<String, ModelSettings> = emptyMap(),
)

/** The rows of the main screen's cards that open (AppConfig.mainOpenRows, 3.0.0). */
object MainRows {
    const val NEGATIVE = "negative"
    const val SAMPLING = "sampling"
    const val SIZE = "size"
}

/** The gallery's tabs: the folders of the server's gallery, the favorites, and every image newest first. */
enum class GalleryTab {
    GALLERY,
    FAVORITES,
    ALL_IMAGES,
    ;

    companion object {
        /** The saved tab; an unknown name is the folders. */
        fun of(name: String?): GalleryTab = entries.firstOrNull { it.name == name } ?: GALLERY
    }
}

/** Where a gallery tab (or a folder of the Gallery tab) was scrolled to: its first visible item and the offset. */
data class GalleryScrollPosition(
    val index: Int,
    val offset: Int,
)

/** The gallery's layouts: a grid of 2 to 5 columns, or a list with small, medium or large thumbnails. */
enum class GalleryView(
    val label: String,
    val columns: Int,
) {
    GRID_2("2 Columns", 2),
    GRID_3("3 Columns", 3),
    GRID_4("4 Columns", 4),
    GRID_5("5 Columns", 5),
    LIST_SMALL("Small", 0),
    LIST_MEDIUM("Medium", 0),
    LIST_LARGE("Large", 0),
    ;

    val isList: Boolean get() = columns == 0

    companion object {
        /** The saved layout; an unknown name (e.g. from a newer version) is the default grid. */
        fun of(name: String?): GalleryView = entries.firstOrNull { it.name == name } ?: GRID_3
    }
}

const val THEME_SYSTEM = "System"
const val THEME_LIGHT = "Light"
const val THEME_DARK = "Dark"

const val AUTO_SAVE_OFF = "Off"
const val AUTO_SAVE_FAVORITES = "Favorites"
const val AUTO_SAVE_ALL = "All new images"

data class AppState(
    var positivePrompt: String = "",
    var negativePrompt: String = "",
    var cfgScale: Float = 7.0f,
    var steps: Int = 20,
    var aspectRatio: String = "Custom",
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
    var upscaler: String = "Latent",
    var saveImages: Boolean = true,
    var saveToDevice: Boolean = false,
    // The server's styles chosen for the next jobs (3.1.0), sent only while AppConfig.serverStyles is on.
    var styles: List<String> = emptyList(),
)

/** Width and height swapped (portrait and landscape), the aspect ratio with them ("Custom" stays). */
fun AppState.withSwappedSize(): AppState {
    val ratio = aspectRatio.split(":").takeIf { it.size == 2 }?.let { (w, h) -> "$h:$w" } ?: aspectRatio
    return copy(width = height, height = width, aspectRatio = ratio)
}

data class PhystonHistoryDto(
    val prompt: String,
    val tags: List<String>? = null,
)

data class PromptHistoryItem(
    val positivePrompt: String,
    val negativePrompt: String,
    val timestamp: Long,
)

// DTO purposely nested for backward compatibility in SharedPreferences (queue).
// We keep the Txt2ImgPayload structure as part of the queue payload.
data class QueuedGeneration(
    val id: String,
    val positivePrompt: String,
    val payload: Txt2ImgPayloadDto,
    val status: GenerationStatus = GenerationStatus.QUEUED,
    // Why the job failed (FAILED only).
    val error: String? = null,
    // What made the job, when it was not the main screen ("Upscale ×2", "More Like This · 1234 +1"; 2.4.0).
    val label: String? = null,
)

enum class GenerationStatus {
    QUEUED,
    GENERATING,
    SUSPENDED,

    // Set aside by overnight mode after an error: kept at the end of the queue, skipped until the user retries it.
    FAILED,
}

data class ApiResource(
    val title: String,
    val path: String,
    val name: String,
    val hash: String? = null,
)

/** An app update offered by the latest GitHub release. */
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val url: String,
    val sha256: String?, // null when GitHub did not report a digest for the asset
    val size: Long = 0,
    val releaseDate: String? = null,
    val changelog: List<String>? = null,
)

enum class GalleryMode {
    NORMAL,
    PROMPT_PICKER,
}

data class GalleryItem(
    val name: String,
    val fullpath: String,
    val type: String,
    val date: String? = null,
    val createdTime: String? = null,
    val size: String? = null,
    // The file's size in bytes, as the gallery extension lists it (3.2.0); null when it does not say.
    val bytes: Long? = null,
) {
    val isDir: Boolean get() = type == "dir"
    val displaySize: String get() {
        val sizeBytes = size?.toLongOrNull()
        return if (sizeBytes != null) "${sizeBytes / 1024} KB" else ""
    }
}

/* ============================================================================
 * 2. ROOM DATABASE COMPONENTS (Entities & DAOs)
 * Representation of data in the local SQLite database.
 * ============================================================================ */

@Entity(tableName = "favorite_images")
data class FavoriteImageEntity(
    @PrimaryKey val fullpath: String,
    val name: String,
    val date: String?,
    val savedAt: Long = System.currentTimeMillis(),
)

@Dao
interface FavoriteImageDao {
    @Query("SELECT * FROM favorite_images ORDER BY savedAt DESC")
    suspend fun getAllFavorites(): List<FavoriteImageEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_images WHERE fullpath = :path LIMIT 1)")
    suspend fun isFavorite(path: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorite(favorite: FavoriteImageEntity)

    @Query("DELETE FROM favorite_images WHERE fullpath = :path")
    suspend fun deleteFavorite(path: String)

    /** Favorites whose files were deleted or are gone from the server (3.2.0). */
    @Query("DELETE FROM favorite_images WHERE fullpath IN (:paths)")
    suspend fun deleteFavorites(paths: List<String>)

    /** A favorite follows its file when the app moves it to another folder (3.2.0). */
    @Query("UPDATE OR REPLACE favorite_images SET fullpath = :newPath WHERE fullpath = :oldPath")
    suspend fun moveFavorite(
        oldPath: String,
        newPath: String,
    )
}

@Entity(tableName = "gallery_images")
data class GalleryImageEntity(
    @PrimaryKey val fullpath: String,
    val name: String,
    val date: String,
    val positivePrompt: String,
    val negativePrompt: String,
    val model: String,
    val sampler: String,
    val seed: String,
    val loras: String,
    // When the generation data was read; 0 while it could not be read (the image is shown, the data is tried again).
    val savedAt: Long,
    // The file's size in bytes (3.2.0, statistics); 0 until a sync lists it.
    @ColumnInfo(defaultValue = "0") val size: Long = 0,
)

/** A file's size for [GalleryImageDao.updateSizes]. */
data class GalleryImageSize(
    val fullpath: String,
    val size: Long,
)

/** An image's positive prompt, read page by page for the statistics' tags. */
data class GalleryImagePrompt(
    val fullpath: String,
    val positivePrompt: String,
)

@Dao
interface GalleryImageDao {
    @Query("SELECT * FROM gallery_images WHERE fullpath = :path LIMIT 1")
    suspend fun getImageByPath(path: String): GalleryImageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertImages(images: List<GalleryImageEntity>)

    @Query("DELETE FROM gallery_images WHERE fullpath IN (:paths)")
    suspend fun deleteImages(paths: List<String>)

    @Query("DELETE FROM gallery_images")
    suspend fun clearAll()

    /** The index without the prompts, which are most of its size; they are searched with [findPathsByPrompt]. */
    @Query("SELECT fullpath, name, date, model, loras, size FROM gallery_images")
    suspend fun getIndexedImages(): List<IndexedImage>

    /** Sizes of images indexed before the index kept them (3.2.0), filled in by the next sync. */
    @Update(entity = GalleryImageEntity::class)
    suspend fun updateSizes(sizes: List<GalleryImageSize>)

    /** An image moved by the app to another folder keeps its generation data (3.2.0). */
    @Query("UPDATE OR REPLACE gallery_images SET fullpath = :newPath WHERE fullpath = :oldPath")
    suspend fun movePath(
        oldPath: String,
        newPath: String,
    )

    /** The positive prompts, [limit] at a time (the statistics count the tags without loading them all at once). */
    @Query("SELECT fullpath, positivePrompt FROM gallery_images ORDER BY fullpath LIMIT :limit OFFSET :offset")
    suspend fun getPrompts(
        limit: Int,
        offset: Int,
    ): List<GalleryImagePrompt>

    @Query("SELECT fullpath FROM gallery_images")
    suspend fun getAllPaths(): List<String>

    /** Images whose generation data could not be read yet ([GalleryImageEntity.savedAt] 0); tried again by each sync. */
    @Query("SELECT fullpath FROM gallery_images WHERE savedAt = 0")
    suspend fun getUnreadPaths(): List<String>

    /** The positive prompt of one image (the gallery's list shows it); null when the image is not indexed. */
    @Query("SELECT positivePrompt FROM gallery_images WHERE fullpath = :path LIMIT 1")
    suspend fun getPositivePrompt(path: String): String?

    /** Images whose positive or negative prompt matches the LIKE [pattern] (with '\' as the escape character). */
    @Query("SELECT fullpath FROM gallery_images WHERE positivePrompt LIKE :pattern ESCAPE '\\' OR negativePrompt LIKE :pattern ESCAPE '\\'")
    suspend fun findPathsByPrompt(pattern: String): List<String>
}

/** A gallery image of the index as the app keeps it in memory: without its prompts. */
data class IndexedImage(
    val fullpath: String,
    val name: String,
    val date: String,
    val model: String,
    val loras: String,
    // Bytes; 0 while not known (3.2.0).
    val size: Long = 0,
)

@Entity(tableName = "app_settings")
data class AppSettingEntity(
    @PrimaryKey val key: String,
    val value: String
)

@Dao
interface AppSettingDao {
    @Query("SELECT * FROM app_settings WHERE `key` = :key")
    suspend fun getSetting(key: String): AppSettingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSetting(setting: AppSettingEntity)

    @Query("DELETE FROM app_settings WHERE `key` = :key")
    suspend fun removeSetting(key: String)
}

@Database(
    entities = [
        FavoriteImageEntity::class, 
        WildcardEntity::class, 
        GalleryImageEntity::class,
        AppSettingEntity::class
    ],
    // 12 (1.6.1): the civitai_models table is gone (MIGRATION_11_12).
    // 13 (3.2.0): gallery_images.size (MIGRATION_12_13).
    version = 13,
    exportSchema = false,
)
abstract class ForgeDatabase : RoomDatabase() {
    abstract fun favoriteImageDao(): FavoriteImageDao

    abstract fun wildcardDao(): WildcardDao

    abstract fun galleryImageDao(): GalleryImageDao

    abstract fun appSettingDao(): AppSettingDao
}

/* ============================================================================
 * 3. DATA TRANSFER OBJECTS (DTOs)
 * Classes mapping JSON responses from Retrofit (A1111, Forge).
 * ============================================================================ */

data class OverrideSettingsDto(
    @SerializedName("CLIP_stop_at_last_layers") val clipSkip: Int,
    @SerializedName("sd_model_checkpoint") val sdModelCheckpoint: String? = null,
    // Only for a job remade from an image (2.4.0), when its data names them: they change the noise, so the same seed
    // gives the same image only with the same values. Null is not sent.
    @SerializedName("eta_noise_seed_delta") val etaNoiseSeedDelta: Int? = null,
    @SerializedName("randn_source") val randnSource: String? = null,
    // The model's modules (3.0.0, ModelSettingsRules.applyTo): Forge takes VAE and text encoder file names here for
    // the job, A1111 a VAE in sd_vae. Null is not sent (the server's own choice stays).
    @SerializedName("forge_additional_modules") val forgeAdditionalModules: List<String>? = null,
    @SerializedName("sd_vae") val sdVae: String? = null,
)

data class Txt2ImgPayloadDto(
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
    val override_settings: OverrideSettingsDto,
    val enable_hr: Boolean,
    val hr_scale: Float,
    val hr_upscaler: String,
    val denoising_strength: Float,
    val save_images: Boolean = true,
    val send_images: Boolean = true,
    // A variation seed mixed into the seed's noise ("More Like This", 2.4.0); -1 and 0 change nothing.
    val subseed: Long = -1L,
    val subseed_strength: Float = 0f,
    // Steps of the hires pass; 0 = as many as the first pass.
    val hr_second_pass_steps: Int = 0,
    // Forge's text encoder/VAE choice for the hires pass; set by forServer() (null is not sent).
    val hr_additional_modules: List<String>? = null,
    // FLUX's distilled CFG (3.0.0, the model's settings or the image's data); null is not sent.
    val distilled_cfg_scale: Float? = null,
    // The server's styles the server adds to the prompts (3.1.0); null is not sent.
    val styles: List<String>? = null,
)

/**
 * The job as the server gets it. Forge's API fails a hires fix without "hr_additional_modules": its hires pass tests
 * `'Use same choices' not in self.hr_additional_modules`, which is None when not sent (TypeError, HTTP 500; the
 * owner's "Upscale" hit it in 2.4.0). Forge's UI sends "Use same choices"; so does the app, for every job with hires
 * fix, also the ones saved before 2.4.1. Servers without the field ignore it.
 */
fun Txt2ImgPayloadDto.forServer(): Txt2ImgPayloadDto =
    if (enable_hr && hr_additional_modules == null) copy(hr_additional_modules = listOf("Use same choices")) else this

data class ProgressStateDto(
    @SerializedName("job_count") val jobCount: Int = 0,
    @SerializedName("job_no") val jobNo: Int = 0,
    @SerializedName("sampling_step") val samplingStep: Int = 0,
    @SerializedName("sampling_steps") val samplingSteps: Int = 0,
)

data class ProgressResponseDto(
    val progress: Double = 0.0,
    @SerializedName("eta_relative") val etaRelative: Double = 0.0,
    val state: ProgressStateDto? = null,
    @SerializedName("current_image") val currentImage: String? = null,
)

data class OptionsPayloadDto(
    @SerializedName("sd_model_checkpoint") val sdModelCheckpoint: String,
)

data class OptionsResponseDto(
    @SerializedName("sd_model_checkpoint") val sdModelCheckpoint: String? = null,
    // The settings of the server's tagcomplete extension (tag suggestions, 2.4.2): its tag files and how it writes
    // tags. Read as text, so an odd value cannot break the reading of the options.
    @SerializedName("tac_tagFile") val tacTagFile: String? = null,
    @SerializedName("tac_extra.extraFile") val tacExtraFile: String? = null,
    @SerializedName("tac_extra.addMode") val tacExtraAddMode: String? = null,
    @SerializedName("tac_replaceUnderscores") val tacReplaceUnderscores: String? = null,
    // (sic) tagcomplete's own spelling.
    @SerializedName("tac_undersocreReplacementExclusionList") val tacKeepUnderscores: String? = null,
    @SerializedName("tac_escapeParentheses") val tacEscapeParentheses: String? = null,
)

data class NameResponseDto(
    val name: String,
)

/** A style saved on the server (/sdapi/v1/prompt-styles, 3.1.0). */
data class PromptStyleDto(
    val name: String?,
    val prompt: String?,
    @SerializedName("negative_prompt") val negativePrompt: String?,
)

/** The server's embeddings (/sdapi/v1/embeddings, 3.1.0): name -> details, loaded for the model or skipped. */
data class EmbeddingsResponseDto(
    val loaded: Map<String, com.google.gson.JsonElement>?,
    val skipped: Map<String, com.google.gson.JsonElement>?,
)

/** Hires fix's upscalers (3.0.1): the latent modes first, then the upscalers, as in the web UI. */
object HiresUpscalers {
    // Forge's and A1111's shared.latent_upscale_modes, for a server that does not list them.
    val LATENT_MODES =
        listOf(
            "Latent",
            "Latent (antialiased)",
            "Latent (bicubic)",
            "Latent (bicubic antialiased)",
            "Latent (nearest)",
            "Latent (nearest-exact)",
        )

    /** The server's latent modes, or the usual ones when it listed none. */
    fun latentModes(fromServer: List<String>?): List<String> = fromServer?.filter { it.isNotBlank() }?.ifEmpty { null } ?: LATENT_MODES

    /** The upscalers without the latent modes (a server listing both would show them twice). */
    fun upscalers(
        latent: List<String>,
        upscalers: List<String>,
    ): List<String> = upscalers.filter { it !in latent }
}

/** One module of Forge's `sd-modules` (VAEs and text encoders) or of A1111's `sd-vae`. */
data class SdModuleItemDto(
    @SerializedName("model_name") val modelName: String? = null,
    val filename: String? = null,
)

data class SdModelItemDto(
    val title: String?,
    val filename: String?,
    @SerializedName("model_name") val modelName: String?,
)

data class LoraItemDto(
    val name: String?,
    val path: String?,
    val metadata: LoraMetadataDto?,
)

data class LoraMetadataDto(
    @SerializedName("sshs_model_hash") val sshsModelHash: String?,
)

data class GitHubReleaseDto(
    @SerializedName("tag_name") val tagName: String?,
    val name: String?,
    val body: String?,
    @SerializedName("published_at") val publishedAt: String?,
    val assets: List<GitHubAssetDto>? = null,
)

data class GitHubAssetDto(
    val name: String?,
    @SerializedName("browser_download_url") val downloadUrl: String?,
    val size: Long = 0,
    val digest: String? = null, // "sha256:<hex>"
)

data class GalleryFileListDto(
    val files: List<GalleryItemDto> = emptyList(),
)

data class GalleryPathsRequestDto(
    val paths: List<String>,
)

data class GalleryItemDto(
    val name: String?,
    val fullpath: String?,
    val type: String?,
    val date: String? = null,
    @SerializedName("created_time") val createdTime: String? = null,
    val size: String? = null,
    val bytes: Long? = null,
)

// New DTO for Custom API (replaces org.json.JSONObject)
data class CustomApiModelsResponseDto(
    val models: List<CustomApiModelDto>? = emptyList(),
)

data class CustomApiModelDto(
    val type: String?,
    val name: String?,
    val filename: String?,
    val sha256: String?,
)

// New DTO for memory and global settings queries
data class MemoryResponseDto(
    val ram: MemoryStatDto?,
    val cuda: CudaStatDto?,
)

data class CudaStatDto(
    val system: MemoryStatDto?,
)

data class MemoryStatDto(
    val used: Double?,
    val total: Double?,
)

/**
 * The server's RAM and VRAM in GB (3.0.0-4: the meters in the main screen's top bar and the Server Memory panel). A
 * total of 0 is a part the server did not report.
 */
data class ServerMemory(
    val ramUsed: Double,
    val ramTotal: Double,
    val vramUsed: Double,
    val vramTotal: Double,
) {
    val hasRam get() = ramTotal > 0
    val hasVram get() = vramTotal > 0

    /** "RAM: 12.3/31.9GB | VRAM: 5.1/8.0GB", as the OOM report writes it. */
    fun summary(): String =
        listOfNotNull(
            "RAM: ${gb(ramUsed)}/${gb(ramTotal)}GB".takeIf { hasRam },
            "VRAM: ${gb(vramUsed)}/${gb(vramTotal)}GB".takeIf { hasVram },
        ).joinToString(" | ")

    companion object {
        private const val GIB = 1024.0 * 1024.0 * 1024.0

        // From this share on a meter turns orange: the next job may run out of memory.
        const val ALMOST_FULL = 0.9f

        /** Null when the server reported neither RAM nor VRAM. */
        fun of(dto: MemoryResponseDto?): ServerMemory? =
            ServerMemory(
                ramUsed = (dto?.ram?.used ?: 0.0) / GIB,
                ramTotal = (dto?.ram?.total ?: 0.0) / GIB,
                vramUsed = (dto?.cuda?.system?.used ?: 0.0) / GIB,
                vramTotal = (dto?.cuda?.system?.total ?: 0.0) / GIB,
            ).takeIf { it.hasRam || it.hasVram }

        fun gb(value: Double): String = String.format(Locale.US, "%.1f", value)

        /** How full, 0..1 (0 without a total). */
        fun share(
            used: Double,
            total: Double,
        ): Float = if (total > 0) (used / total).toFloat().coerceIn(0f, 1f) else 0f

        /** The meter's short "used/total": "5.1/8.0", or "12.3/32" once a number reaches 10 GB, so it stays narrow. */
        fun compact(
            used: Double,
            total: Double,
        ): String {
            fun short(value: Double) = if (value >= 10) String.format(Locale.US, "%.0f", value) else gb(value)
            val usedText = if (used >= 100) String.format(Locale.US, "%.0f", used) else gb(used)
            return "$usedText/${short(total)}"
        }

        /** The panel's "5.1 of 8.0 GB · 64%". */
        fun detail(
            used: Double,
            total: Double,
        ): String = "${gb(used)} of ${gb(total)} GB · ${(share(used, total) * 100).roundToInt()}%"
    }
}

data class GlobalSettingResponseDto(
    @SerializedName("sd_cwd") val sdCwd: String?,
    @SerializedName("global_setting") val globalSetting: GlobalSettingInnerDto?,
    // True when the extension may not change files (IIB_ACCESS_CONTROL_PERMISSION=read-only, 3.2.0).
    @SerializedName("is_readonly") val isReadonly: Boolean? = null,
)

/** The gallery extension's delete_files (3.2.0). */
data class GalleryDeleteRequestDto(
    @SerializedName("file_paths") val filePaths: List<String>,
)

/** The gallery extension's move_files and copy_files (3.2.0); each file's .txt goes with it. */
data class GalleryTransferRequestDto(
    @SerializedName("file_paths") val filePaths: List<String>,
    val dest: String,
    @SerializedName("create_dest_folder") val createDestFolder: Boolean = false,
    @SerializedName("continue_on_error") val continueOnError: Boolean = true,
)

/** What move_files and copy_files could not do, one message per file (naming its path). */
data class GalleryTransferResultDto(
    val errors: List<String>? = null,
)

/** The gallery extension's mkdirs (3.2.0). */
data class GalleryMkdirsRequestDto(
    @SerializedName("dest_folder") val destFolder: String,
)

data class GlobalSettingInnerDto(
    // Forge saves every image here when it is set, otherwise txt2img images go to outdirTxt2ImgSamples.
    @SerializedName("outdir_samples") val outdirSamples: String? = null,
    @SerializedName("outdir_txt2img_samples") val outdirTxt2ImgSamples: String? = null,
)

/* ============================================================================
 * 4. MAPPERS (Extension Functions)
 * Pure conversions between the network layer (DTO) and the Domain.
 * ============================================================================ */

private val RELEASE_TAG = Regex("^v(\\d+)\\.(\\d+)\\.(\\d+)(?:-(\\d+))?$")

/**
 * versionCode of a release tag "v<major>.<minor>.<patch>" or, for a micro-patch, "v<major>.<minor>.<patch>-<micro>":
 * major * 100_000_000 + minor * 100_000 + patch * 100 + micro, the formula app/build.gradle.kts uses. Null for any
 * other tag (e.g. the old "build-1034" ones). Up to 1.1.4 the formula was major * 1_000_000 + minor * 1_000 + patch;
 * every code of the new one is higher.
 */
fun versionCodeFromTag(tag: String): Int? {
    val match = RELEASE_TAG.find(tag.trim()) ?: return null
    val (major, minor, patch, micro) = match.groupValues.drop(1).map { it.ifEmpty { "0" }.toIntOrNull() ?: return null }
    if (major > 20 || minor > 999 || patch > 999 || micro > 99) return null
    return major * 100_000_000 + minor * 100_000 + patch * 100 + micro
}

/** Releases tagged "v<major>.<minor>.<patch>" (or "...-<micro>") that carry an APK are updates; anything else gives null. */
fun GitHubReleaseDto.toUpdateManifest(): UpdateManifest? {
    val tag = tagName?.trim() ?: return null
    val versionCode = versionCodeFromTag(tag) ?: return null
    val apk = assets.orEmpty().firstOrNull { it.name?.endsWith(".apk") == true && !it.downloadUrl.isNullOrEmpty() } ?: return null
    return UpdateManifest(
        versionCode = versionCode,
        versionName = tag.removePrefix("v"),
        url = apk.downloadUrl!!,
        sha256 = apk.digest?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"),
        size = apk.size,
        releaseDate = publishedAt?.take(10),
        changelog = parseReleaseNotes(body),
    )
}

/**
 * The notes of a release (the workflow copies them from CHANGELOG.md) as Markdown lines for the update card. Since
 * 3.0.0-4 a section starts with the release's kind in bold ("**Bugfix** · ...") and groups its items under
 * "### New", "### Changed" and "### Fixed": that first line, the headings and the "- item" lines are kept, anything
 * else (a "## <version>" line, other text) is left out.
 */
fun parseReleaseNotes(body: String?): List<String> {
    val notes = mutableListOf<String>()
    for (raw in body.orEmpty().lines()) {
        val line = raw.trim()
        when {
            line.startsWith("- ") || line.startsWith("* ") -> notes += "- " + line.drop(2).trim()
            line.startsWith("### ") -> notes += "### " + line.drop(4).trim()
            line.startsWith("**") && notes.isEmpty() -> notes += line
        }
    }
    return notes
}

/** How many items the [notes] of parseReleaseNotes hold. */
fun releaseNoteCount(notes: List<String>): Int = notes.count { it.startsWith("- ") }

/**
 * The [notes] of parseReleaseNotes as Markdown for MarkdownText (3.0.0-2: their **bold** and `code` are read), with
 * at most [maxItems] items (the update card shows the first few) and no heading left without an item under it.
 */
fun releaseNotesMarkdown(
    notes: List<String>,
    maxItems: Int = Int.MAX_VALUE,
): String {
    val lines = mutableListOf<String>()
    var items = 0
    for (line in notes) {
        if (items == maxItems) break
        if (line.startsWith("- ")) items++
        lines += line
    }
    while (lines.lastOrNull()?.startsWith("### ") == true) lines.removeAt(lines.lastIndex)
    return lines.joinToString("\n")
}

fun GalleryItemDto.toDomain() =
    GalleryItem(
        name = this.name ?: "Unknown",
        fullpath = this.fullpath ?: "",
        type = this.type ?: "file",
        date = this.date,
        createdTime = this.createdTime,
        size = this.size,
        bytes = this.bytes,
    )

fun SdModelItemDto.toDomain() =
    ApiResource(
        title = this.title ?: "Unknown Model",
        path = this.filename ?: "",
        name = this.modelName ?: "Unknown",
        hash = null,
    )

fun LoraItemDto.toDomain() =
    ApiResource(
        title = this.name ?: "Unknown LoRA",
        path = this.path ?: "",
        name = this.name ?: "Unknown",
        hash = this.metadata?.sshsModelHash?.takeIf { it.isNotEmpty() } ?: this.name,
    )

@Entity(tableName = "wildcards")
data class WildcardEntity(
    @PrimaryKey val name: String,
    val content: String,
)

@Dao
interface WildcardDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWildcard(wildcard: WildcardEntity)

    @Delete
    suspend fun deleteWildcard(wildcard: WildcardEntity)

    @Query("SELECT * FROM wildcards ORDER BY name ASC")
    suspend fun getAllWildcards(): List<WildcardEntity>

    @Query("DELETE FROM wildcards")
    suspend fun clearAll()
}
