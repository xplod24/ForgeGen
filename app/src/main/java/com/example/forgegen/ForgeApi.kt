package com.example.forgegen

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

/* ============================================================================
 * 1. FORGE / AUTOMATIC1111 API
 * Main communication interface with the Stable Diffusion WebUI (Forge/Automatic1111) image generation server.
 * Retrofit's Response<T> wrapper is used to safely inspect raw HTTP status codes (such as 401 Unauthorized
 * or 403 Forbidden) and retrieve custom error bodies without throwing exceptions during network calls.
 * ============================================================================ */
interface ForgeApi {
    @POST("sdapi/v1/txt2img")
    suspend fun generateImage(
        @Body payload: Txt2ImgPayloadDto,
    ): Response<Txt2ImgResponseDto>

    @POST("sdapi/v1/interrupt")
    suspend fun interruptGeneration(): Response<Unit>

    @POST("sdapi/v1/skip")
    suspend fun skipGeneration(): Response<Unit>

    @POST("sdapi/v1/refresh-checkpoints")
    suspend fun refreshCheckpoints(): Response<Unit>

    @POST("sdapi/v1/refresh-loras")
    suspend fun refreshLoras(): Response<Unit>

    @POST("sdapi/v1/unload-checkpoint")
    suspend fun unloadCheckpoint(): Response<Unit>

    @POST("sdapi/v1/png-info")
    suspend fun getPngInfo(
        @Body payload: PngInfoPayloadDto,
    ): Response<PngInfoResponseDto>

    @POST("sdapi/v1/tokenize")
    suspend fun tokenize(
        @Body payload: TokenizePayloadDto,
    ): Response<TokenizeResponseDto>

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

    @GET("infinite_image_browsing/global_setting")
    suspend fun getGlobalSettings(): Response<GlobalSettingResponseDto>

    // Using ResponseBody directly prevents serialization/parsing failures across vastly different versions
    // of the Infinite Image Browsing (IIB) extension, allowing us to parse the raw JSON dynamically.
    @GET("infinite_image_browsing/files")
    suspend fun getGalleryFiles(
        @Header("Cookie") cookie: String = "IIB_S=bf63789069ec13d6b7b95a5176468e99f8940fe6aa65931edc17e1abf5c5e172",
        @Query(value = "folder_path", encoded = true) folderPath: String? = null,
    ): Response<ResponseBody>

    @GET
    suspend fun getGlobalSettingsDynamic(
        @Url url: String,
    ): Response<GlobalSettingResponseDto>

    @GET
    suspend fun getGalleryFilesDynamic(
        @Url url: String,
        @Header("Cookie") cookie: String = "IIB_S=bf63789069ec13d6b7b95a5176468e99f8940fe6aa65931edc17e1abf5c5e172",
        @Query(value = "folder_path", encoded = true) folderPath: String? = null,
    ): Response<ResponseBody>

    @GET("app/metadata")
    suspend fun getAppMetadata(): Response<UpdateManifestDto>

    @retrofit2.http.Streaming
    @GET("app/download?apk")
    suspend fun downloadAppUpdate(): Response<ResponseBody>
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
