package com.example.forgegen

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A separate client: no Forge interceptors, cookies, retries or redirects carry a Bridge credential. */
class BridgeApi(
    base: String,
    private val client: OkHttpClient = defaultClient(),
) {
    val serverBase = BridgeProtocol.base(base)

    suspend fun request(
        method: String,
        path: String,
        token: String = "",
        body: JsonObject? = null,
        query: Map<String, String> = emptyMap(),
        key: String? = null,
    ): JsonObject {
        require(path.matches(Regex("[A-Za-z0-9_./-]*")) && path.split('/').none { it == ".." || it == "." })
        require(method in setOf("GET", "POST", "PATCH", "DELETE", "OPTIONS"))
        require(method !in setOf("GET", "DELETE", "OPTIONS") || body == null) { "This method requires an empty body." }
        val public = path in setOf("info", "openapi.json", "pairing/exchange") || method == "OPTIONS"
        require(public || token.isNotBlank()) { "Pair this phone before using Bridge tools." }
        require(token.length <= 4096 && token.none { it == '\n' || it == '\r' })
        if (method in setOf("POST", "PATCH", "DELETE")) require(!key.isNullOrBlank() && key.length <= 200)
        val encoded = body?.toString()
        require((encoded?.toByteArray()?.size ?: 0) <= 65_536) { "This request is too large." }
        val url =
            (serverBase + "forgegen/v1/" + path)
                .toHttpUrl()
                .newBuilder()
                .apply {
                    query.forEach { (name, value) -> addQueryParameter(name, value) }
                }.build()
        val request = Request.Builder().url(url).header("Accept", "application/json")
        if (path != "info") request.header("X-ForgeGen-API-Version", "1.0")
        if (!public) request.header("Authorization", "Bearer $token")
        if (key != null) request.header("Idempotency-Key", key)
        val bytes = encoded?.toRequestBody("application/json".toMediaType())
        request.method(method, if (method in setOf("POST", "PATCH")) bytes ?: ByteArray(0).toRequestBody() else null)
        val response =
            try {
                client.newCall(request.build()).await()
            } catch (e: IOException) {
                throw BridgeFailure(0, "NETWORK", method !in setOf("GET", "OPTIONS"))
            }
        return response.use { res ->
            if (res.isRedirect) throw BridgeFailure(res.code, "REDIRECT_BLOCKED", method !in setOf("GET", "OPTIONS"))
            if (method == "OPTIONS" && res.code == 204) return@use JsonObject().apply { addProperty("allow", res.header("Allow")) }
            val text =
                res.body
                    ?.let { responseBody ->
                        if (responseBody.contentLength() > MAX_RESPONSE) throw BridgeFailure(res.code, "INVALID_RESPONSE", method != "GET")
                        val buffer = Buffer()
                        val source = responseBody.source()
                        var read = 0L
                        while (true) {
                            val n = source.read(buffer, 8192)
                            if (n == -1L) break
                            read += n
                            if (read > MAX_RESPONSE) throw BridgeFailure(res.code, "INVALID_RESPONSE", method != "GET")
                        }
                        buffer.readUtf8()
                    }.orEmpty()
            val json = runCatching { JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject }.getOrNull()
            if (!res.isSuccessful) {
                val code =
                    json
                        ?.get("error")
                        ?.takeIf { it.isJsonObject }
                        ?.asJsonObject
                        ?.text("code")
                        .orEmpty()
                throw BridgeFailure(
                    res.code,
                    when {
                        path == "info" && res.code == 404 -> "ABSENT"
                        res.code == 426 -> "INCOMPATIBLE_PROTOCOL"
                        code.matches(Regex("[A-Z_]{1,64}")) -> code
                        res.code == 401 -> "AUTH_REQUIRED"
                        else -> "HTTP_ERROR"
                    },
                    res.code >= 500 && method !in setOf("GET", "OPTIONS"),
                )
            }
            if (res
                    .header("Content-Type")
                    .orEmpty()
                    .substringBefore(';')
                    .trim() != "application/json" ||
                json == null
            ) {
                throw BridgeFailure(res.code, "INVALID_RESPONSE", method !in setOf("GET", "OPTIONS"))
            }
            BridgeContract.response(method, path, res.code, json)
            json
        }
    }

    suspend fun info(): BridgeInfo = decode<BridgeInfo>(request("GET", "info")).also(BridgeProtocol::verify)

    companion object {
        private const val MAX_RESPONSE = 4L * 1024 * 1024

        fun defaultClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .callTimeout(12, TimeUnit.SECONDS)
                .build()

        inline fun <reified T> decode(json: JsonObject): T = BridgeProtocol.gson.fromJson(json, T::class.java)
    }
}

private suspend fun Call.await(): Response =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    continuation.resume(response) { _, value, _ -> value.close() }
                }
            },
        )
    }
