package com.example.forgegen

import com.google.gson.JsonParser
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.security.MessageDigest

/* ============================================================================
 * GALLERY KEY (3.5.0)
 * Infinite Image Browsing can be locked with a secret key (IIB_SECRET_KEY in the .env file of its folder, or in the
 * server's environment). It then compares the cookie IIB_S of every request with sha256(key + "_ciallo") and answers
 * 401 {"detail": {"type": "secret_verification_failed"}} otherwise; IIB's own web page asks for the key and makes the
 * same cookie. The server never hands the cookie out (it would give the key away), so the app asks for the key once,
 * keeps only this fingerprint, per server, in AppConfig.galleryKeys, and ForgeSettingsManager.createClient sends it
 * with the gallery's requests. A server without a key ignores the cookie. With Forge's login on (--gradio-auth) and no
 * key set, IIB refuses everything: 400 "secret_key_required". Up to 3.4.2 one fingerprint was written in the code.
 * ============================================================================ */
object GalleryKey {
    const val COOKIE = "IIB_S"
    private const val SALT = "_ciallo"

    /** The "type" of IIB's 401 for a missing or wrong key. */
    const val LOCKED_TYPE = "secret_verification_failed"

    /** The "type" of IIB's 400 when Forge has a login and IIB has no key. */
    const val KEY_REQUIRED_TYPE = "secret_key_required"

    const val WHERE_TO_FIND =
        "It is IIB_SECRET_KEY in the .env file in the extension's folder on the server " +
            "(extensions/sd-webui-infinite-image-browsing), or in the environment Forge starts in."

    /** The cookie IIB expects for [key]: the SHA-256 of the key and "_ciallo", in lowercase hex. */
    fun fingerprint(key: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest((key.trim() + SALT).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** The server an address belongs to: keys are kept per server, so every server profile has its own. */
    fun serverOf(apiUrl: String): String = apiUrl.trim().trimEnd('/').lowercase()

    /** The fingerprint saved for the server [config] connects to, if any. */
    fun savedFor(config: AppConfig): String? = config.galleryKeys[serverOf(config.apiUrl)]

    /** [config] with [fingerprint] saved for its server, or with that server's key removed when it is null. */
    fun withFingerprint(
        config: AppConfig,
        fingerprint: String?,
    ): AppConfig {
        val server = serverOf(config.apiUrl)
        return config.copy(galleryKeys = if (fingerprint == null) config.galleryKeys - server else config.galleryKeys + (server to fingerprint))
    }

    /** The "type" IIB puts in an error's "detail" (e.g. [LOCKED_TYPE]), or null for any other answer. */
    fun errorType(body: String?): String? =
        try {
            val detail = body?.let { JsonParser.parseString(it) }?.takeIf { it.isJsonObject }?.asJsonObject?.get("detail")
            detail
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.get("type")
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
        } catch (e: Exception) {
            null
        }

    // A gallery request refused for its key (the key was changed on the server, say): the gallery locks itself.
    private val _refused = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val refused: SharedFlow<Unit> = _refused.asSharedFlow()

    fun reportRefused() {
        _refused.tryEmit(Unit)
    }
}
