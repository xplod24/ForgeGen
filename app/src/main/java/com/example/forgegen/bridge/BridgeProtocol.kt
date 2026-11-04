package com.example.forgegen

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.Instant
import java.util.UUID

/** Independent wire helpers: none of these change Forge configuration or its queue. */
object BridgeProtocol {
    val gson = Gson()
    private val semver =
        Regex(
            "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?",
        )
    val knownFeatures =
        setOf(
            "status",
            "models.count",
            "models.catalog",
            "models.scan",
            "models.move",
            "models.delete",
            "downloads.create",
            "downloads.cancel",
            "downloads.retry",
            "civitai.search",
            "civitai.download",
            "extensions.list",
            "extensions.install",
            "extensions.uninstall",
            "extensions.toggle",
            "extensions.bulk",
            "server.unload",
            "server.restart",
            "devices.list",
            "devices.revoke",
            "devices.me",
            "operations.read",
            "operations.cancel",
        )

    fun base(value: String): String {
        val url = runCatching { value.trim().toHttpUrl() }.getOrElse { throw IllegalArgumentException("Invalid Forge server address.") }
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
            "Use the Forge server address without embedded credentials, query or fragment."
        }
        return url
            .newBuilder()
            .encodedPath(url.encodedPath.trimEnd('/') + "/")
            .build()
            .toString()
    }

    fun id(value: String): String {
        require(value.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid resource identifier." }
        return value
    }

    fun uuid(value: String?): Boolean =
        value != null && runCatching { UUID.fromString(value).toString().equals(value, true) }.getOrDefault(false)

    fun verify(info: BridgeInfo) {
        val version = semver.matchEntire(info.bridgeVersion.orEmpty())
        val validPrerelease =
            version?.groupValues?.get(4).orEmpty().split('.').none {
                it.length > 1 &&
                    it.first() == '0' &&
                    it.all(Char::isDigit)
            }
        require(info.bridgeId == "forgegen-bridge" && uuid(info.serverId) && version != null && validPrerelease) {
            "The server did not return a valid ForgeGen Bridge identity."
        }
        require(info.apiVersion?.major == 1L && info.apiVersion.minor == 0L) { "This Bridge API version is not supported." }
        require(info.authentication == "bearer-device-token" && info.pairing == "administrator-issued-single-use-invitation") {
            "This Bridge authentication protocol is not supported."
        }
    }

    fun objectJson(value: String): JsonObject {
        require(value.length <= 32_768) { "The invitation is too large." }
        return runCatching {
            JsonParser
                .parseString(
                    value,
                ).asJsonObject
        }.getOrElse { throw IllegalArgumentException("Invalid invitation JSON.") }
    }

    fun invitation(
        value: String,
        currentBase: String,
        now: Instant = Instant.now(),
    ): BridgeInvitation {
        val json = objectJson(value)
        require(
            json.text("format") == "forgegen-pairing" &&
                runCatching {
                    json.get("format_version")?.asJsonPrimitive?.let { it.isNumber && it.asString == "1" } == true
                }.getOrDefault(false),
        ) { "Unsupported invitation format." }
        val invite =
            BridgeInvitation(
                base(json.text("server_url")),
                json.text("server_id"),
                json.text("invitation_id"),
                json.text("code"),
                json.text("expires_at"),
            )
        require(invite.base == base(currentBase)) { "Select the server profile matching this invitation first." }
        require(uuid(invite.serverId) && uuid(invite.invitationId)) { "Invalid invitation identity." }
        require(invite.code.length in 16..256 && invite.code.none { it == '\r' || it == '\n' }) { "Invalid invitation code." }
        val expires =
            runCatching {
                Instant.parse(
                    invite.expiresAt,
                )
            }.getOrElse { throw IllegalArgumentException("Invalid invitation expiry.") }
        require(expires.isAfter(now)) { "This invitation has expired. Create a new one on the server." }
        return invite
    }

    fun allowed(capability: BridgeCapability?): Boolean =
        capability?.supported == true && capability.enabled == true && capability.authorized == true && capability.reasonCode == null

    fun scopeFor(feature: String): String? =
        when {
            feature == "status" -> "status.read"
            feature in setOf("models.count", "models.catalog") -> "models.read"
            feature.startsWith("models.") -> "models.manage"
            feature.startsWith("downloads.") || feature == "civitai.download" -> "downloads.manage"
            feature == "civitai.search" -> "civitai.search"
            feature == "extensions.list" -> "extensions.read"
            feature.startsWith("extensions.") -> "extensions.manage"
            feature.startsWith("server.") -> "server.manage"
            feature in setOf("devices.list", "devices.revoke") -> "devices.manage"
            else -> null
        }

    fun validateMutation(
        method: String,
        path: String,
        body: JsonObject?,
    ) {
        require((body?.toString()?.toByteArray()?.size ?: 0) <= 65_536) { "This request is too large." }
        require(method != "DELETE" || body == null) { "DELETE requires an empty body." }
        body ?: return
        if (body.has("name")) {
            val name = body.text("name")
            require(
                name.length in 1..255 &&
                    name !in setOf(".", "..") &&
                    name.none {
                        it == '/' || it == '\\' || it == '\u0000'
                    },
            ) { "Use a single safe file name." }
        }
        if (body.has("root_id")) require(body.text("root_id").length in 1..100) { "Select a registered destination root." }
        if (path ==
            "civitai/downloads"
        ) {
            require(
                body.longValue("version_id")?.let { it > 0 } == true && body.longValue("file_id")?.let { it > 0 } == true,
            ) {
                "Use positive Civitai version and file IDs."
            }
        }
        if (path == "downloads") {
            val url = runCatching { body.text("url").toHttpUrl() }.getOrElse { throw IllegalArgumentException("Invalid source URL.") }
            require(
                url.scheme == "https" &&
                    url.port == 443 &&
                    url.host in setOf("civitai.com", "civitai.red") &&
                    url.username.isEmpty() &&
                    url.password.isEmpty() &&
                    url.fragment == null &&
                    url.queryParameterNames.none { it.lowercase() in setOf("token", "key", "api_key", "apikey", "authorization") },
            ) {
                "Use a Civitai HTTPS URL without embedded credentials or API keys."
            }
        }
        if (path == "extensions") {
            val url =
                runCatching {
                    body
                        .text(
                            "repository_url",
                        ).toHttpUrl()
                }.getOrElse { throw IllegalArgumentException("Invalid repository URL.") }
            require(
                url.scheme == "https" &&
                    url.host == "github.com" &&
                    url.port == 443 &&
                    url.username.isEmpty() &&
                    url.password.isEmpty() &&
                    url.query == null &&
                    url.fragment == null &&
                    url.pathSegments.size == 2 &&
                    url.pathSegments.none { it.isEmpty() || it in setOf(".", "..") },
            ) {
                "Use an HTTPS GitHub owner/repository URL without credentials or query."
            }
            require(
                body.text("directory_name").matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")),
            ) { "Use a simple extension directory name." }
        }
    }

    fun explanation(code: String?): String =
        when (code) {
            "FLAG_REQUIRED" -> "The server needs --enable-insecure-extension-access for extension changes."
            "SCOPE_REQUIRED" -> "This phone has not been granted this permission by the server owner."
            "PRIVACY_POLICY" -> "The server owner limits model access to counts."
            "NOT_CONFIGURED" -> "Configure this integration in the Bridge panel on your server."
            "MODULE_DISABLED", "BRIDGE_DISABLED" -> "This feature is switched off on the server."
            "FORGE_RESTRICTED" -> "Forge restricts this operation on the server."
            "UNSUPPORTED_BACKEND" -> "This Forge installation does not support this operation."
            "RESOURCE_BUSY", "RESOURCE_IN_USE" -> "The resource is busy or still loaded. Wait for idle or unload it first."
            "DESTINATION_EXISTS" -> "A file with this name already exists. Choose another name."
            "INSUFFICIENT_SPACE" -> "The server does not have enough free space."
            "HASH_MISMATCH" -> "The downloaded file failed verification."
            "BRIDGE_PROTECTED" -> "The Bridge and built-in extensions are protected."
            "IDEMPOTENCY_CONFLICT" -> "This request key is already associated with another operation."
            "RATE_LIMITED", "QUEUE_FULL" -> "The server is limiting requests. Wait before retrying."
            "TOKEN_REVOKED" -> "This phone's Bridge access was revoked. Pair again explicitly."
            "AUTH_REQUIRED" -> "Bridge or upstream server authentication is required. Your Forge profile is kept."
            "PAIRING_INVALID" -> "The invitation is invalid, expired or already used."
            "INCOMPATIBLE_PROTOCOL" -> "This Bridge API version is not supported."
            else -> "This Bridge operation is unavailable. Refresh its status or ask the server owner."
        }
}

class BridgeInvitation(
    val base: String,
    val serverId: String,
    val invitationId: String,
    val code: String,
    val expiresAt: String,
) {
    override fun toString() = "BridgeInvitation(redacted)"
}

internal fun JsonObject.text(key: String): String =
    runCatching {
        get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    }.getOrDefault("")

internal fun JsonObject.flag(key: String): Boolean = runCatching { get(key)?.asBoolean == true }.getOrDefault(false)

internal fun JsonObject.longValue(key: String): Long? = runCatching { get(key)?.takeUnless { it.isJsonNull }?.asLong }.getOrNull()

internal fun JsonObject.number(key: String): Double? =
    runCatching {
        get(key)?.takeUnless { it.isJsonNull }?.asDouble?.takeIf { it.isFinite() }
    }.getOrNull()

internal fun JsonObject.children(key: String): List<JsonObject> =
    get(key)
        ?.takeIf { it.isJsonArray }
        ?.asJsonArray
        ?.mapNotNull { it.takeIf { item -> item.isJsonObject }?.asJsonObject }
        .orEmpty()

class BridgeFailure(
    val status: Int,
    val code: String,
    val uncertain: Boolean = false,
) : Exception(
        when {
            code == "ABSENT" -> "ForgeGen Bridge was not found. Core ForgeGen features remain available."
            code == "NETWORK" -> "Bridge did not answer. Core ForgeGen features remain available."
            code == "INVALID_RESPONSE" -> "The server did not return a valid Bridge response."
            code == "REDIRECT_BLOCKED" -> "Bridge redirected the request. Check the configured server address."
            else -> BridgeProtocol.explanation(code)
        },
    )
