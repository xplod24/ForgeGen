@file:Suppress("unused")
package com.example.forgegen

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import com.google.gson.annotations.SerializedName

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
    var serverBasePath: String = "",
    var galleryPath: String = "",
    // THEME_SYSTEM, THEME_LIGHT or THEME_DARK (was the switch "isDarkMode" up to 1.1.4-1).
    var themeMode: String = THEME_SYSTEM,
    var timeout: Int = 10,
    var notifOnBatchFinish: Boolean = false,
    var notifOnQueueFinish: Boolean = true,
    var notifCivitaiSync: Boolean = true,
    var autoDismissCivitaiNotif: Boolean = false,
    var notificationMode: String = "Simple",
    var keepScreenOn: Boolean = false,
    var swipeToBrowseGallery: Boolean = true,
    var bottomSheetExpandedByDefault: Boolean = false,
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
    var autoSyncModels: Boolean = false,
    var mainPromptsExpanded: Boolean = true,
    var mainSettingsExpanded: Boolean = false,
    var mainLorasExpanded: Boolean = false,
    // Gallery images saved to the phone on their own: AUTO_SAVE_OFF, AUTO_SAVE_FAVORITES or AUTO_SAVE_ALL.
    var autoSaveMode: String = AUTO_SAVE_OFF,
    // With AUTO_SAVE_ALL only images newer than this (the server's "yyyy-MM-dd HH:mm:ss" format) are saved,
    // so switching it on does not download the whole existing gallery.
    var autoSaveSince: String = "",
    // The user's consent to write a report with the app's log to Downloads when the app or the server runs out of memory.
    var saveOomLogs: Boolean = false,
    // Samsung One UI 8+: the generation progress as a Live Update in the Now Bar of the lock screen (opt-in).
    var nowBarProgress: Boolean = false,
)

const val THEME_SYSTEM = "System"
const val THEME_LIGHT = "Light"
const val THEME_DARK = "Dark"

const val AUTO_SAVE_OFF = "Off"
const val AUTO_SAVE_FAVORITES = "Favorites"
const val AUTO_SAVE_ALL = "All new images"

data class AppState(
    var setupExpandedSections: Set<String> = emptySet(),
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
)

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

@Entity(tableName = "civitai_models")
data class CivitaiModelEntity(
    @PrimaryKey val sha256: String,
    val type: String,
    val name: String,
    val trainedWords: String,
    val previewImage: String?,
)

@Dao
interface CivitaiModelDao {
    @Query("SELECT * FROM civitai_models ORDER BY name ASC")
    suspend fun getAllModels(): List<CivitaiModelEntity>

    @Query("SELECT * FROM civitai_models WHERE sha256 = :sha256")
    suspend fun getModelByHash(sha256: String): CivitaiModelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertModels(models: List<CivitaiModelEntity>)
}

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
    val savedAt: Long,
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

    @Query("SELECT * FROM gallery_images")
    suspend fun getAllImages(): List<GalleryImageEntity>
}

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
        CivitaiModelEntity::class, 
        FavoriteImageEntity::class, 
        WildcardEntity::class, 
        GalleryImageEntity::class,
        AppSettingEntity::class
    ],
    version = 10,
    exportSchema = false,
)
abstract class ForgeDatabase : RoomDatabase() {
    abstract fun civitaiModelDao(): CivitaiModelDao

    abstract fun favoriteImageDao(): FavoriteImageDao

    abstract fun wildcardDao(): WildcardDao

    abstract fun galleryImageDao(): GalleryImageDao

    abstract fun appSettingDao(): AppSettingDao
}

/* ============================================================================
 * 3. DATA TRANSFER OBJECTS (DTOs)
 * Classes mapping JSON responses from Retrofit (A1111, Forge, Civitai).
 * ============================================================================ */

data class OverrideSettingsDto(
    @SerializedName("CLIP_stop_at_last_layers") val clipSkip: Int,
    @SerializedName("sd_model_checkpoint") val sdModelCheckpoint: String? = null,
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
)

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
)

data class NameResponseDto(
    val name: String,
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

// New DTO for Civitai (replaces org.json.JSONObject)
data class CivitaiVersionResponseDto(
    val model: CivitaiBaseModelDto?,
    val trainedWords: List<String>?,
    val images: List<CivitaiImageDto>?,
)

data class CivitaiBaseModelDto(
    val name: String?,
)

data class CivitaiImageDto(
    val url: String?,
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

data class GlobalSettingResponseDto(
    @SerializedName("sd_cwd") val sdCwd: String?,
    @SerializedName("global_setting") val globalSetting: GlobalSettingInnerDto?,
)

data class GlobalSettingInnerDto(
    @SerializedName("outdir_txt2img_samples") val outdirTxt2ImgSamples: String?,
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

/** The "- item" lines of a release description (the workflow copies them from CHANGELOG.md). */
fun parseReleaseNotes(body: String?): List<String> =
    body
        .orEmpty()
        .lines()
        .map { it.trim() }
        .filter { it.startsWith("- ") || it.startsWith("* ") }
        .map { it.drop(2).trim() }

fun GalleryItemDto.toDomain() =
    GalleryItem(
        name = this.name ?: "Unknown",
        fullpath = this.fullpath ?: "",
        type = this.type ?: "file",
        date = this.date,
        createdTime = this.createdTime,
        size = this.size,
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
