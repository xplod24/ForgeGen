@file:Suppress("PropertyName", "unused")

package com.example.forgegen

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import com.google.gson.annotations.SerializedName

/* ============================================================================
 * CORE CONFIGURATION MODELS
 * Defines the application's main configuration, user preferences, and saved presets.
 * ============================================================================ */

data class ServerProfile(
    val name: String,
    val url: String
)

data class GenerationPreset(
    val name: String,
    val state: AppState
)

data class AppConfig(
    var apiUrl: String = "http://192.168.1.90:7860",
    var serverBasePath: String = "", // Pobierane z API (sd_cwd)
    var galleryPath: String = "", // Ustawiane przez AUTO config
    var language: String = "en",
    var isDarkMode: Boolean = false,
    var connectionTimeout: Int = 10,
    var checkpointTimeout: Int = 45,
    var receiveGenerationNotification: Boolean = true,
    var notifImagePreview: Boolean = true, // Nowe: przeniesione z DataStore (luzem)
    var notifQueueStatus: Boolean = false, // Nowe: przeniesione z DataStore (luzem)
    var notificationPriority: String = "Normal", // "High", "Normal", "Low"
    var notificationVerbosity: String = "Full",
    var keepScreenOn: Boolean = false,
    var enablePersistentService: Boolean = false,
    var swipeToBrowseGallery: Boolean = true,
    var galleryGridColumns: Int = 3,
    var serverProfiles: List<ServerProfile> = listOf(ServerProfile("Default Local", "http://192.168.1.90:7860")),
    var previewMode: String = "Finished", // "None" (Loading circle), "Finished" (Last batch), "Normal" (Live)
    var useNativeSecurity: Boolean = false,
    var useBiometricLock: Boolean = false,
    var overnightMode: Boolean = false,
    var showGridAfterGeneration: Boolean = true,
    var showActiveTagsUI: Boolean = true,
    var updateChannel: String = "Stable", // "Stable" lub "Beta"
    var betaToken: String = "", // Tajny token dostępu do aktualizacji Beta
    var defaultState: AppState = AppState(),
    var presets: List<GenerationPreset> = emptyList()
)

/* ============================================================================
 * GENERATION STATE MODELS
 * Holds all properties related to the current prompt generation parameters.
 * ============================================================================ */

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

/* ============================================================================
 * HISTORY & QUEUE MODELS
 * Used for tracking previous prompts and queueing new generations.
 * ============================================================================ */

data class PromptHistoryItem(
    val positivePrompt: String,
    val negativePrompt: String,
    val timestamp: Long
)

data class QueuedGeneration(
    val id: String,
    val positivePrompt: String,
    val payload: Txt2ImgPayload
)

/* ============================================================================
 * SERVER STATISTICS MODELS
 * Used for tracking historical telemetry data (Ping, RAM, VRAM) for charts.
 * ============================================================================ */

data class ServerStatRecord(
    val timestamp: Long,
    val pingMs: Long,
    val ramUsed: Double,
    val ramTotal: Double,
    val vramUsed: Double,
    val vramTotal: Double
)

/* ============================================================================
 * API PAYLOADS & RESPONSES
 * Structures mapped directly to the Forge/Automatic1111 API JSON endpoints.
 * ============================================================================ */

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

data class ApiResource(
    val title: String,
    val path: String,
    val name: String,
    val hash: String? = null
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
    val denoising_strength: Float,
    val save_images: Boolean = true,
    val send_images: Boolean = true
)

data class ProgressState(
    @SerializedName("job_count") val jobCount: Int = 0,
    @SerializedName("job_no") val jobNo: Int = 0,
    @SerializedName("sampling_step") val samplingStep: Int = 0,
    @SerializedName("sampling_steps") val samplingSteps: Int = 0
)

data class ProgressResponse(
    val progress: Double = 0.0,
    @SerializedName("eta_relative") val etaRelative: Double = 0.0,
    val state: ProgressState? = null,
    @SerializedName("current_image") val currentImage: String? = null
)

data class Txt2ImgResponse(
    val images: List<String> = emptyList()
)

data class OptionsResponse(
    @SerializedName("sd_model_checkpoint") val sdModelCheckpoint: String? = null
)

data class NameResponse(val name: String)

data class SdModelItem(
    val title: String,
    val filename: String?,
    @SerializedName("model_name") val modelName: String
)

/* ============================================================================
 * ROOM DATABASE COMPONENTS (LoRA Tag Cache & Favorites)
 * Local cache to prevent API rate limits and drastically improve UI speed.
 * ============================================================================ */

@Entity(tableName = "lora_tags")
data class LoraTagEntity(
    @PrimaryKey val loraHash: String,
    val loraName: String,
    val trainedWords: String, // comma-separated string mapping List<String> natively
    val lastUpdated: Long
)

@Dao
interface LoraTagDao {
    @Query("SELECT * FROM lora_tags WHERE loraHash = :hash")
    suspend fun getTagsByHash(hash: String): LoraTagEntity?

    @Query("SELECT * FROM lora_tags")
    suspend fun getAllTags(): List<LoraTagEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTags(entity: LoraTagEntity)

    @Query("DELETE FROM lora_tags")
    suspend fun clearAll()
}

@Entity(tableName = "favorite_images")
data class FavoriteImageEntity(
    @PrimaryKey val fullpath: String,
    val name: String,
    val date: String?,
    val savedAt: Long // Znacznik czasu dodania do ulubionych (do sortowania)
)

@Dao
interface FavoriteImageDao {
    @Query("SELECT * FROM favorite_images ORDER BY savedAt DESC")
    suspend fun getAllFavorites(): List<FavoriteImageEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_images WHERE fullpath = :path)")
    suspend fun isFavorite(path: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorite(entity: FavoriteImageEntity)

    @Query("DELETE FROM favorite_images WHERE fullpath = :path")
    suspend fun deleteFavorite(path: String)
}

// BUMP WERSJI DO 5, by wygenerować tabele na nowo (używamy destructive migration)
@Database(entities = [LoraTagEntity::class, FavoriteImageEntity::class], version = 5, exportSchema = false)
abstract class ForgeDatabase : RoomDatabase() {
    abstract fun loraTagDao(): LoraTagDao
    abstract fun favoriteImageDao(): FavoriteImageDao
}

/* ============================================================================
 * GALLERY MODELS
 * Used for browsing locally or remotely generated images via infinite image browsing.
 * ============================================================================ */

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