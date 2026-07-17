@file:Suppress("PropertyName", "unused")

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
 * Czyste modele biznesowe, odseparowane od szczegółów implementacyjnych API.
 * ============================================================================ */

data class ServerProfile(
    val name: String,
    val url: String
)

data class GenerationPreset(
    val name: String,
    val state: AppState,
    val includePrompts: Boolean = true
)

enum class GallerySyncMode {
    MANUAL, ON_ENTRY, BACKGROUND
}

data class AppConfig(
    var apiUrl: String = "http://192.168.1.90:7860",
    var serverBasePath: String = "",
    var galleryPath: String = "",
    var isDarkMode: Boolean = false,
    var connectionTimeout: Int = 10,
    var checkpointTimeout: Int = 45,
    var receiveGenerationNotification: Boolean = true, // Legacy field (could remove, but keeping it to avoid breaking other things right now if it's used elsewhere like in Service)
    var notifOnBatchFinish: Boolean = false,
    var notifOnQueueFinish: Boolean = true,
    var notifCivitaiSync: Boolean = true,
    var autoDismissCivitaiNotif: Boolean = false,
    var notifQueueStatus: Boolean = false,
    var notificationMode: String = "Simple",
    var keepScreenOn: Boolean = false,
    var enablePersistentService: Boolean = false,
    var swipeToBrowseGallery: Boolean = true,
    var bottomSheetExpandedByDefault: Boolean = false,
    var serverProfiles: List<ServerProfile> = listOf(ServerProfile("Default Local", "http://192.168.1.90:7860")),
    var previewMode: String = "Finished",
    var useNativeSecurity: Boolean = false,
    var useBiometricLock: Boolean = false,
    var overnightMode: Boolean = false,
    var showGridAfterGeneration: Boolean = true,
    var showActiveTagsUI: Boolean = true,
    var enableLogging: Boolean = false,
    var lastUpdateCheckDate: String = "",
    var defaultState: AppState = AppState(),
    var presets: List<GenerationPreset> = emptyList(),
    var gallerySyncMode: GallerySyncMode = GallerySyncMode.MANUAL
)

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
    var saveToDevice: Boolean = false
)

data class PngInfoPayloadDto(val image: String)
data class PngInfoResponseDto(val info: String, val items: Map<String, String>? = null)
data class TokenizePayloadDto(val text: String)
data class TokenizeResponseDto(val tokens: List<Int>? = null)
data class PhystonHistoryDto(val prompt: String, val tags: List<String>? = null)

data class Txt2ImgRequestDto(
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
    val send_images: Boolean = true
)

data class PromptHistoryItem(
    val positivePrompt: String,
    val negativePrompt: String,
    val timestamp: Long
)

// DTO zagnieżdżone celowo dla kompatybilności wstecznej w SharedPreferences (kolejka).
// Zachowujemy strukturę Txt2ImgPayload jako część payloadu kolejki.
data class QueuedGeneration(
    val id: String,
    val positivePrompt: String,
    val payload: Txt2ImgPayloadDto,
    val status: GenerationStatus = GenerationStatus.QUEUED
)

enum class GenerationStatus {
    QUEUED,
    GENERATING,
    SUSPENDED
}

data class ServerStatRecord(
    val timestamp: Long,
    val pingMs: Long,
    val ramUsed: Double,
    val ramTotal: Double,
    val vramUsed: Double,
    val vramTotal: Double
)

data class ApiResource(
    val title: String,
    val path: String,
    val name: String,
    val hash: String? = null
)

data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val url: String,
    val channel: String,
    val sha256: String,
    val releaseDate: String? = null,
    val isCritical: Boolean = false,
    val changelog: Map<String, List<String>>? = null
)

enum class GalleryMode {
    NORMAL,
    PROMPT_PICKER
}

data class GalleryItem(
    val name: String,
    val fullpath: String,
    val type: String,
    val date: String? = null,
    val createdTime: String? = null,
    val size: String? = null
) {
    val isDir: Boolean get() = type == "dir"
    val displaySize: String get() {
        val sizeBytes = size?.toLongOrNull()
        return if (sizeBytes != null) "${sizeBytes / 1024} KB" else ""
    }
}

/* ============================================================================
 * 2. ROOM DATABASE COMPONENTS (Entities & DAOs)
 * Reprezentacja danych w lokalnej bazie SQLite.
 * ============================================================================ */

@Entity(tableName = "civitai_models")
data class CivitaiModelEntity(
    @PrimaryKey val sha256: String,
    val type: String,
    val name: String,
    val trainedWords: String,
    val previewImage: String?
)

@Dao
interface CivitaiModelDao {
    @Query("SELECT * FROM civitai_models ORDER BY name ASC")
    suspend fun getAllModels(): List<CivitaiModelEntity>

    @Query("SELECT * FROM civitai_models WHERE sha256 = :sha256")
    suspend fun getModelByHash(sha256: String): CivitaiModelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertModels(models: List<CivitaiModelEntity>)

    @Query("DELETE FROM civitai_models")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM civitai_models")
    suspend fun count(): Int
}

@Entity(tableName = "favorite_images")
data class FavoriteImageEntity(
    @PrimaryKey val fullpath: String,
    val name: String,
    val date: String?,
    val savedAt: Long = System.currentTimeMillis()
)

@Dao
interface FavoriteImageDao {
    @Query("SELECT * FROM favorite_images ORDER BY savedAt DESC")
    suspend fun getAllFavorites(): List<FavoriteImageEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_images WHERE fullpath = :path LIMIT 1)")
    suspend fun isFavorite(path: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorite(favorite: FavoriteImageEntity)

    @Delete
    suspend fun deleteFavoriteEntity(favorite: FavoriteImageEntity)

    @Query("DELETE FROM favorite_images WHERE fullpath = :path")
    suspend fun deleteFavorite(path: String)

    @Query("SELECT COUNT(*) FROM favorite_images")
    suspend fun count(): Int

    @Query("DELETE FROM favorite_images")
    suspend fun clearAll()
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
    val savedAt: Long
)

@Dao
interface GalleryImageDao {
    @Query("SELECT * FROM gallery_images WHERE positivePrompt LIKE '%' || :query || '%' OR name LIKE '%' || :query || '%' OR loras LIKE '%' || :query || '%'")
    suspend fun searchImages(query: String): List<GalleryImageEntity>

    @Query("SELECT * FROM gallery_images WHERE fullpath = :path LIMIT 1")
    suspend fun getImageByPath(path: String): GalleryImageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertImage(image: GalleryImageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(images: List<GalleryImageEntity>)
    
    @Query("DELETE FROM gallery_images WHERE fullpath LIKE :folderPath || '%'")
    suspend fun clearFolder(folderPath: String)
}

@Database(
    entities = [CivitaiModelEntity::class, FavoriteImageEntity::class, WildcardEntity::class, GalleryImageEntity::class],
    version = 9,
    exportSchema = false
)
abstract class ForgeDatabase : RoomDatabase() {
    abstract fun civitaiModelDao(): CivitaiModelDao
    abstract fun favoriteImageDao(): FavoriteImageDao
    abstract fun wildcardDao(): WildcardDao
    abstract fun galleryImageDao(): GalleryImageDao
}

/* ============================================================================
 * 3. DATA TRANSFER OBJECTS (DTOs)
 * Klasy mapujące odpowiedzi JSON z Retrofita (A1111, Forge, Civitai).
 * ============================================================================ */

data class OverrideSettingsDto(
    @SerializedName("CLIP_stop_at_last_layers") val clipSkip: Int,
    @SerializedName("sd_model_checkpoint") val sdModelCheckpoint: String? = null
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
    val send_images: Boolean = true
)

data class ProgressStateDto(
    @SerializedName("job_count") val jobCount: Int = 0,
    @SerializedName("job_no") val jobNo: Int = 0,
    @SerializedName("sampling_step") val samplingStep: Int = 0,
    @SerializedName("sampling_steps") val samplingSteps: Int = 0
)

data class ProgressResponseDto(
    val progress: Double = 0.0,
    @SerializedName("eta_relative") val etaRelative: Double = 0.0,
    val state: ProgressStateDto? = null,
    @SerializedName("current_image") val currentImage: String? = null
)

data class Txt2ImgResponseDto(
    val images: List<String> = emptyList(),
    val info: String = "",
    val parameters: Map<String, Any>? = null
)

data class OptionsPayloadDto(
    @SerializedName("sd_model_checkpoint") val sdModelCheckpoint: String
)

data class OptionsResponseDto(
    @SerializedName("sd_model_checkpoint") val sdModelCheckpoint: String? = null
)

data class NameResponseDto(val name: String)

data class SdModelItemDto(
    val title: String?,
    val filename: String?,
    @SerializedName("model_name") val modelName: String?
)

data class LoraItemDto(
    val name: String?,
    val path: String?,
    val metadata: LoraMetadataDto?
)

data class LoraMetadataDto(
    @SerializedName("sshs_model_hash") val sshsModelHash: String?
)

data class UpdateManifestDto(
    val versionCode: Int?,
    val versionName: String?,
    val url: String?,
    val channel: String?,
    val sha256: String?,
    val releaseDate: String? = null,
    val isCritical: Boolean = false,
    val changelog: Map<String, List<String>>? = null
)

data class GalleryFileListDto(
    val files: List<GalleryItemDto> = emptyList()
)

data class GalleryItemDto(
    val name: String?,
    val fullpath: String?,
    val type: String?,
    val date: String? = null,
    @SerializedName("created_time") val createdTime: String? = null,
    val size: String? = null
)

// Nowe DTO dla Custom API (zastępuje org.json.JSONObject)
data class CustomApiModelsResponseDto(
    val models: List<CustomApiModelDto>? = emptyList()
)

data class CustomApiModelDto(
    val type: String?,
    val name: String?,
    val filename: String?,
    val sha256: String?
)

// Nowe DTO dla Civitai (zastępuje org.json.JSONObject)
data class CivitaiVersionResponseDto(
    val model: CivitaiBaseModelDto?,
    val trainedWords: List<String>?,
    val images: List<CivitaiImageDto>?
)

data class CivitaiBaseModelDto(val name: String?)

data class CivitaiImageDto(val url: String?)

// Nowe DTO dla zapytań o pamięć i ustawienia globalne
data class MemoryResponseDto(
    val ram: MemoryStatDto?,
    val cuda: CudaStatDto?
)

data class CudaStatDto(val system: MemoryStatDto?)
data class MemoryStatDto(val used: Double?, val total: Double?)

data class GlobalSettingResponseDto(
    @SerializedName("sd_cwd") val sdCwd: String?,
    @SerializedName("global_setting") val globalSetting: GlobalSettingInnerDto?
)

data class GlobalSettingInnerDto(
    @SerializedName("outdir_txt2img_samples") val outdirTxt2ImgSamples: String?
)

/* ============================================================================
 * 4. MAPPERS (Extension Functions)
 * Czyste konwersje pomiędzy warstwą sieciową (DTO) a Domeną.
 * ============================================================================ */

fun UpdateManifestDto.toDomain() = UpdateManifest(
    versionCode = this.versionCode ?: 0,
    versionName = this.versionName ?: "Unknown",
    url = this.url ?: "",
    channel = this.channel ?: "Stable",
    sha256 = this.sha256 ?: "",
    releaseDate = this.releaseDate,
    isCritical = this.isCritical,
    changelog = this.changelog
)

fun GalleryItemDto.toDomain() = GalleryItem(
    name = this.name ?: "Unknown",
    fullpath = this.fullpath ?: "",
    type = this.type ?: "file",
    date = this.date,
    createdTime = this.createdTime,
    size = this.size
)

fun SdModelItemDto.toDomain() = ApiResource(
    title = this.title ?: "Unknown Model",
    path = this.filename ?: "",
    name = this.modelName ?: "Unknown",
    hash = null
)

fun LoraItemDto.toDomain() = ApiResource(
    title = this.name ?: "Unknown LoRA",
    path = this.path ?: "",
    name = this.name ?: "Unknown",
    hash = this.metadata?.sshsModelHash?.takeIf { it.isNotEmpty() } ?: this.name
)




@androidx.room.Entity(tableName = "wildcards")
data class WildcardEntity(
    @androidx.room.PrimaryKey val name: String,
    val content: String
)

@androidx.room.Dao
interface WildcardDao {
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insertWildcard(wildcard: WildcardEntity)

    @androidx.room.Delete
    suspend fun deleteWildcard(wildcard: WildcardEntity)

    @androidx.room.Query("SELECT * FROM wildcards ORDER BY name ASC")
    suspend fun getAllWildcards(): List<WildcardEntity>
    
    @androidx.room.Query("SELECT COUNT(*) FROM wildcards")
    suspend fun count(): Int
    
    @androidx.room.Query("DELETE FROM wildcards")
    suspend fun clearAll()
}
