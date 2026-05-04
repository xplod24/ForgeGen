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
 * Główny interfejs komunikacji z serwerem generowania obrazów.
 * Używamy klasy Response<T> w celu bezpiecznej obsługi kodów błędów (np. 401/403).
 * ============================================================================ */
interface ForgeApi {
    @POST("sdapi/v1/txt2img")
    suspend fun generateImage(@Body payload: Txt2ImgPayloadDto): Response<Txt2ImgResponseDto>

    @POST("sdapi/v1/interrupt")
    suspend fun interruptGeneration(): Response<Unit>

    @GET("sdapi/v1/progress")
    suspend fun getProgress(@Query("skip_current_image") skipImage: Boolean): Response<ProgressResponseDto>

    @GET("sdapi/v1/memory")
    suspend fun getMemoryStats(): Response<MemoryResponseDto>

    @GET("sdapi/v1/options")
    suspend fun getOptions(): Response<OptionsResponseDto>

    @POST("sdapi/v1/options")
    suspend fun setOptions(@Body payload: OptionsPayloadDto): Response<Unit>

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

    /* --- Custom API & Infinite Image Browsing --- */

    @GET("customapi/v1/all-models-hashes")
    suspend fun getCustomModelsHashes(): Response<CustomApiModelsResponseDto>

    @GET("infinite_image_browsing/global_setting")
    suspend fun getGlobalSettings(): Response<GlobalSettingResponseDto>

    // ResponseBody zapobiega błędom parsowania na skrajnie różniących się wersjach rozszerzenia IIB.
    @GET("infinite_image_browsing/files")
    suspend fun getGalleryFiles(
        @Header("Cookie") cookie: String = "IIB_S=bf63789069ec13d6b7b95a5176468e99f8940fe6aa65931edc17e1abf5c5e172",
        @Query("folder_path") folderPath: String? = null
    ): Response<ResponseBody>
}

/* ============================================================================
 * 2. CIVITAI API
 * Pobieranie metadanych modeli na podstawie ich hash'a SHA256.
 * ============================================================================ */
interface CivitaiApi {
    @GET("api/v1/model-versions/by-hash/{hash}")
    suspend fun getModelByHash(@Path("hash") hash: String): Response<CivitaiVersionResponseDto>
}

/* ============================================================================
 * 3. UPDATE API
 * Weryfikacja i pobieranie manifestów aktualizacji z własnego serwera.
 * Używa dynamicznego adresu URL dla wersji Stable/Beta.
 * ============================================================================ */
interface UpdateApi {
    @GET
    suspend fun getUpdateManifest(
        @Url url: String,
        @Header("Beta-Tester") betaToken: String? = null
    ): Response<UpdateManifestDto>
}