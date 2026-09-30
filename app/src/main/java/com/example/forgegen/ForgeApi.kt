package com.example.forgegen

import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming
import retrofit2.http.Url
import java.util.concurrent.TimeUnit

/* ============================================================================
 * 1. FORGE / AUTOMATIC1111 API
 * Main communication interface with the Stable Diffusion WebUI (Forge/Automatic1111) image generation server.
 * Retrofit's Response<T> wrapper is used to safely inspect raw HTTP status codes (such as 401 Unauthorized
 * or 403 Forbidden) and retrieve custom error bodies without throwing exceptions during network calls.
 * ============================================================================ */
interface ForgeApi {
    // Streamed: the answer holds every image as one large base64 string, so it is read one image at a time
    // instead of the whole batch being parsed into memory at once.
    @Streaming
    @POST("sdapi/v1/txt2img")
    suspend fun generateImage(
        @Body payload: Txt2ImgPayloadDto,
    ): Response<ResponseBody>

    @POST("sdapi/v1/interrupt")
    suspend fun interruptGeneration(): Response<Unit>

    @POST("sdapi/v1/refresh-checkpoints")
    suspend fun refreshCheckpoints(): Response<Unit>

    @POST("sdapi/v1/refresh-loras")
    suspend fun refreshLoras(): Response<Unit>

    @POST("sdapi/v1/unload-checkpoint")
    suspend fun unloadCheckpoint(): Response<Unit>

    /** Skips the image being made; the job goes on with its next one (3.3.0). */
    @POST("sdapi/v1/skip")
    suspend fun skipImage(): Response<ResponseBody>

    /**
     * One task's state by the id the app gave it (force_task_id, 3.3.0). Part of the web UI: a server started with
     * --nowebui answers 404, one with a web UI login 401.
     */
    @POST("internal/progress")
    suspend fun getTaskProgress(
        @Body body: TaskProgressRequestDto,
    ): Response<TaskProgressDto>

    @GET("internal/pending-tasks")
    suspend fun getPendingTasks(): Response<PendingTasksDto>

    /** Forge's system report (versions, GPU, extensions, settings; slow: the server lists its Python packages). */
    @GET("internal/sysinfo")
    suspend fun getSysinfo(): Response<ResponseBody>

    @GET("sdapi/v1/extensions")
    suspend fun getExtensions(): Response<List<ServerExtensionDto>>

    /** How Forge was started; "api_server_stop" says whether it can be restarted from here. */
    @GET("sdapi/v1/cmd-flags")
    suspend fun getCmdFlags(): Response<Map<String, Any?>>

    /** Only with --api-server-stop (else 404); 501 when Forge was not started by webui.bat/webui.sh. */
    @POST("sdapi/v1/server-restart")
    suspend fun restartServer(): Response<ResponseBody>

    @GET("sdapi/v1/progress")
    suspend fun getProgress(
        @Query("skip_current_image") skipImage: Boolean,
    ): Response<ProgressResponseDto>

    @GET("sdapi/v1/memory")
    suspend fun getMemoryStats(): Response<MemoryResponseDto>

    // --- Physton Prompt History ---

    @GET("physton_prompt/get_latest_history")
    suspend fun getLatestHistory(
        @Query("type") type: String, // "txt2img" or "txt2img_neg"
    ): Response<PhystonHistoryDto>

    @GET("sdapi/v1/options")
    suspend fun getOptions(): Response<OptionsResponseDto>

    @POST("sdapi/v1/options")
    suspend fun setOptions(
        @Body payload: OptionsPayloadDto,
    ): Response<Unit>

    @GET("sdapi/v1/samplers")
    suspend fun getSamplers(): Response<List<NameResponseDto>>

    @GET("sdapi/v1/schedulers")
    suspend fun getSchedulers(): Response<List<NameResponseDto>>

    @GET("sdapi/v1/upscalers")
    suspend fun getUpscalers(): Response<List<NameResponseDto>>

    /** Hires fix's latent modes ("Latent", "Latent (antialiased)", ...), which /upscalers leaves out (3.0.1). */
    @GET("sdapi/v1/latent-upscale-modes")
    suspend fun getLatentUpscaleModes(): Response<List<NameResponseDto>>

    /** Rescans the VAE folder. Forge Neo's own module list (sd-modules) is not rescanned by it (3.0.1). */
    @POST("sdapi/v1/refresh-vae")
    suspend fun refreshVae(): Response<Unit>

    @GET("sdapi/v1/sd-models")
    suspend fun getSdModels(): Response<List<SdModelItemDto>>

    @GET("sdapi/v1/loras")
    suspend fun getLoras(): Response<List<LoraItemDto>>

    /** The LoRAs with their whole metadata, read as it streams by LoraMetadata (3.1.0). */
    @Streaming
    @GET("sdapi/v1/loras")
    suspend fun getLorasWithMetadata(): Response<ResponseBody>

    /** Embeddings loaded for the current model and those skipped (3.1.0). */
    @GET("sdapi/v1/embeddings")
    suspend fun getEmbeddings(): Response<EmbeddingsResponseDto>

    @POST("sdapi/v1/refresh-embeddings")
    suspend fun refreshEmbeddings(): Response<Unit>

    /** The styles saved on the server (styles.csv, 3.1.0). */
    @GET("sdapi/v1/prompt-styles")
    suspend fun getPromptStyles(): Response<List<PromptStyleDto>>

    /** Forge's VAEs and text encoders (3.0.0, model settings). */
    @GET("sdapi/v1/sd-modules")
    suspend fun getSdModules(): Response<List<SdModuleItemDto>>

    /** A1111's VAEs, where the server has no sd-modules. */
    @GET("sdapi/v1/sd-vae")
    suspend fun getSdVaes(): Response<List<SdModuleItemDto>>

    // --- Custom API & Infinite Image Browsing ---

    @GET("customapi/v1/all-models-hashes")
    suspend fun getCustomModelsHashes(): Response<CustomApiModelsResponseDto>

    @GET
    suspend fun getGlobalSettingsDynamic(
        @Url url: String,
    ): Response<GlobalSettingResponseDto>

    // Using ResponseBody directly prevents serialization/parsing failures across vastly different versions
    // of the Infinite Image Browsing (IIB) extension, allowing us to parse the raw JSON dynamically.
    @GET
    suspend fun getGalleryFilesDynamic(
        @Url url: String,
        @Query(value = "folder_path", encoded = true) folderPath: String = "",
    ): Response<ResponseBody>

    /** Generation parameters of one image, read by the gallery extension on the server (a JSON string). */
    @GET
    suspend fun getGalleryGenInfo(
        @Url url: String,
        @Query("path") path: String,
    ): Response<ResponseBody>

    /** Generation parameters of many images at once (newer versions of the gallery extension): path -> text. */
    @POST
    suspend fun getGalleryGenInfoBatch(
        @Url url: String,
        @Body body: GalleryPathsRequestDto,
    ): Response<Map<String, String?>>

    // --- Changing the gallery's files (3.2.0; the extension answers 403 when it may only read) ---

    @POST
    suspend fun deleteGalleryFiles(
        @Url url: String,
        @Body body: GalleryDeleteRequestDto,
    ): Response<ResponseBody>

    /** move_files or copy_files, by [url]. */
    @POST
    suspend fun transferGalleryFiles(
        @Url url: String,
        @Body body: GalleryTransferRequestDto,
    ): Response<GalleryTransferResultDto>

    @POST
    suspend fun makeGalleryFolder(
        @Url url: String,
        @Body body: GalleryMkdirsRequestDto,
    ): Response<ResponseBody>

    /** The newest (up to) four images of each folder, for its cover: folder -> images. */
    @POST
    suspend fun getGalleryFolderCovers(
        @Url url: String,
        @Body body: GalleryPathsRequestDto,
    ): Response<Map<String, List<GalleryItemDto>>>

    /** Whether each path is still on the server: path -> exists. */
    @POST
    suspend fun checkGalleryPaths(
        @Url url: String,
        @Body body: GalleryPathsRequestDto,
    ): Response<Map<String, Boolean>>

    /** A file the web UI serves (`file=<path>`, e.g. the tagcomplete extension's tag list), streamed. */
    @Streaming
    @GET
    suspend fun getServerFile(
        @Url url: String,
    ): Response<ResponseBody>
}

/* ============================================================================
 * 2. GITHUB RELEASES API
 * App updates come from the latest release of the (public) GitHub repository, so no token is needed.
 * ============================================================================ */
interface GitHubApi {
    @Headers("Accept: application/vnd.github+json")
    @GET("repos/{repository}/releases/latest")
    suspend fun getLatestRelease(
        @Path(value = "repository", encoded = true) repository: String,
    ): Response<GitHubReleaseDto>

    // Absolute browser_download_url of a release asset; OkHttp follows GitHub's redirect to the file host.
    @Streaming
    @GET
    suspend fun downloadAsset(
        @Url url: String,
    ): Response<ResponseBody>

    companion object {
        // GitHub's API; tests point it at a local server.
        @Volatile internal var baseUrl = "https://api.github.com/"

        fun create(): GitHubApi {
            val client =
                OkHttpClient
                    .Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build()
            return Retrofit
                .Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(GitHubApi::class.java)
        }
    }
}
