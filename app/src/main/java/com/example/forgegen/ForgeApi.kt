package com.example.forgegen

import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
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

    @GET("sdapi/v1/sd-models")
    suspend fun getSdModels(): Response<List<SdModelItemDto>>

    @GET("sdapi/v1/loras")
    suspend fun getLoras(): Response<List<LoraItemDto>>

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
        @Header("Cookie") cookie: String = "IIB_S=bf63789069ec13d6b7b95a5176468e99f8940fe6aa65931edc17e1abf5c5e172",
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
}

/* ============================================================================
 * 2. CIVITAI API
 * Civitai integration API.
 * Used for fetching model metadata, image previews, and model versions from Civitai based on their SHA256 hashes.
 * ============================================================================ */
interface CivitaiApi {
    @GET("api/v1/model-versions/by-hash/{hash}")
    suspend fun getModelByHash(
        @Path("hash") hash: String,
    ): Response<CivitaiVersionResponseDto>
}

/* ============================================================================
 * 3. GITHUB RELEASES API
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
        fun create(): GitHubApi {
            val client =
                OkHttpClient
                    .Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build()
            return Retrofit
                .Builder()
                .baseUrl("https://api.github.com/")
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(GitHubApi::class.java)
        }
    }
}
