package com.example.forgegen

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit

internal object HomelabCa {
    private const val CERTIFICATE_PATH = "/.well-known/forgegen/ca.cer"
    private const val CERTIFICATE_SHA256 = "0b701ad091879ff118c871675702cbe3b686cfe8baa71d21171b2822fd6952a4"
    private const val MAX_CERTIFICATE_BYTES = 16_384L
    private val wireGuardUrl = "http://10.8.0.1$CERTIFICATE_PATH".toHttpUrl()

    private val client by lazy {
        OkHttpClient
            .Builder()
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    fun certificateUrl(serverAddress: String): HttpUrl? {
        val address = serverAddress.trim()
        val server = (if ("://" in address) address else "https://$address").toHttpUrlOrNull() ?: return null
        if (!server.isHttps || server.username.isNotEmpty() || server.password.isNotEmpty()) return null
        return when (server.host) {
            "diffusion.local", "diffusion.home.arpa" -> "http://${server.host}$CERTIFICATE_PATH".toHttpUrl()
            else -> null
        }
    }

    fun download(
        primary: HttpUrl,
        fallback: HttpUrl = wireGuardUrl,
        expectedSha256: String = CERTIFICATE_SHA256,
    ): ByteArray =
        try {
            fetch(primary, expectedSha256)
        } catch (_: IOException) {
            fetch(fallback, expectedSha256)
        }

    private fun fetch(
        url: HttpUrl,
        expectedSha256: String,
    ): ByteArray =
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.code != 200) throw IOException("CA download returned HTTP ${response.code}")
            val source = response.body.source()
            if (source.request(MAX_CERTIFICATE_BYTES + 1)) {
                throw HomelabCaException("The downloaded certificate file is too large.")
            }
            val bytes = source.readByteArray()
            // The public bootstrap endpoint uses HTTP. Only the already verified DER artifact can be exported.
            if (bytes.toByteString().sha256().hex() != expectedSha256) {
                throw HomelabCaException("The downloaded certificate does not match the homelab CA fingerprint.")
            }
            bytes
        }
}

internal class HomelabCaException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
