package com.example.forgegen

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** Fictional loopback backend, shared by instrumentation and the local native-graphics rendering rig. */
internal class BridgeUiFixture(
    private val countOnly: Boolean = false,
) : Closeable {
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val base = "http://127.0.0.1:${socket.localPort}/"
    val serverId = "00000000-0000-4000-8000-000000000001"
    private val deviceId = "00000000-0000-4000-8000-000000000002"
    private val modelId = "00000000-0000-4000-8000-000000000003"
    private val extensionId = "00000000-0000-4000-8000-000000000004"
    private val protectedId = "00000000-0000-4000-8000-000000000005"
    private val token = UUID.randomUUID().toString()
    private val code = UUID.randomUUID().toString()
    private val invitationId = UUID.randomUUID().toString()
    private val operations = CopyOnWriteArrayList<JsonObject>()
    private val fixtureGson =
        com.google.gson
            .GsonBuilder()
            .serializeNulls()
            .create()
    private var nextEnabled = true

    @Volatile private var running = true

    data class Recorded(
        val method: String,
        val path: String,
        val key: String?,
        val fields: Set<String>,
    )

    val requests = CopyOnWriteArrayList<Recorded>()

    val scopes =
        listOf(
            "status.read",
            "models.read",
            "models.manage",
            "downloads.manage",
            "civitai.search",
            "extensions.read",
            "extensions.manage",
            "server.manage",
            "devices.manage",
        )
    val device = BridgeDevice(deviceId, "Synthetic Android phone", scopes, "2026-11-04T12:00:00Z", null)
    val store =
        object : BridgeStore {
            private var local = BridgeSession()

            @Synchronized override fun read(base: String) = local

            @Synchronized override fun write(
                base: String,
                value: BridgeSession,
            ) {
                local = value
            }

            @Synchronized override fun forget(base: String) {
                local = BridgeSession()
            }
        }

    fun configure(paired: Boolean) {
        store.write(base, if (paired) BridgeSession(true, serverId, token, device) else BridgeSession())
    }

    val invitation: String get() =
        JsonObject()
            .apply {
                addProperty("format", "forgegen-pairing")
                addProperty("format_version", 1)
                addProperty("server_url", base)
                addProperty("server_id", serverId)
                addProperty("invitation_id", invitationId)
                addProperty("code", code)
                addProperty("expires_at", Instant.now().plusSeconds(120).toString())
            }.toString()

    init {
        thread(isDaemon = true, name = "Bridge-UI-fixture") {
            while (running) {
                val client =
                    try {
                        socket.accept()
                    } catch (_: Exception) {
                        break
                    }
                thread(isDaemon = true) { client.use { runCatching { handle(it) } } }
            }
        }
    }

    private fun json(value: String) = JsonParser.parseString(value).asJsonObject

    private fun page(items: List<JsonObject>): JsonObject =
        JsonObject().apply {
            add("items", JsonArray().apply { items.forEach(::add) })
            addProperty("offset", 0)
            addProperty("limit", 50)
            addProperty("total", items.size)
        }

    private fun capabilities(): JsonObject {
        val caps = JsonObject()
        val names =
            listOf(
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
        names.forEach { key ->
            val restricted = countOnly && key in BridgeState.MODEL_ACTIONS + "downloads.cancel"
            caps.add(
                key,
                JsonObject().apply {
                    addProperty("supported", true)
                    addProperty("enabled", !restricted)
                    addProperty("authorized", true)
                    add("reason_code", if (restricted) com.google.gson.JsonPrimitive("PRIVACY_POLICY") else JsonNull.INSTANCE)
                },
            )
        }
        return JsonObject().apply {
            add("api_version", json("""{"major":1,"minor":0}"""))
            add("features", caps)
            add(
                "required_flags",
                json("""{"enable_insecure_extension_access":true,"extension_access_allowed":true,"extensions_globally_allowed":true}"""),
            )
            addProperty("privacy_policy", if (countOnly) "count_only" else "full")
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = 5000
        val input = client.getInputStream()

        fun line(): String {
            val bytes = java.io.ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0 || b == 10) break
                if (b != 13) bytes.write(b)
            }
            return bytes.toString("UTF-8")
        }
        val start = line().split(' ')
        if (start.size < 2) return
        val method = start[0]
        val path = start[1].substringBefore('?').removePrefix("/forgegen/v1/")
        val headers = mutableMapOf<String, String>()
        while (true) {
            val l = line()
            if (l.isEmpty()) break
            headers[l.substringBefore(':').lowercase()] = l.substringAfter(':').trim()
        }
        val length = headers["content-length"]?.toInt() ?: 0
        check(length in 0..65536)
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val n = input.read(bytes, offset, length - offset)
            if (n < 0) break
            offset += n
        }
        val body = if (length > 0) json(String(bytes, Charsets.UTF_8)) else JsonObject()
        requests += Recorded(method, path, headers["idempotency-key"], body.keySet())
        var status = 200
        val result =
            when {
                path == "info" ->
                    json(
                        """{"bridge_id":"forgegen-bridge","bridge_version":"1.0.0","api_version":{"major":1,"minor":0},"server_id":"$serverId","authentication":"bearer-device-token","pairing":"administrator-issued-single-use-invitation","openapi_url":"/forgegen/v1/openapi.json"}""",
                    )
                path == "pairing/exchange" -> {
                    check(body.text("code") == code && body.text("invitation_id") == invitationId)
                    JsonObject().apply {
                        add("device", fixtureGson.toJsonTree(device))
                        addProperty("token_type", "Bearer")
                        addProperty("access_token", token)
                    }
                }
                headers["authorization"] != "Bearer $token" -> {
                    status = 401
                    json(
                        """{"error":{"code":"AUTH_REQUIRED","message":"Synthetic auth required","retryable":false},"request_id":"$serverId"}""",
                    )
                }
                path == "devices/me" -> fixtureGson.toJsonTree(device).asJsonObject
                path == "capabilities" -> capabilities()
                path == "models/roots" -> page(listOf(json("""{"id":"checkpoint-default","type":"checkpoint"}""")))
                path == "models/counts" ->
                    page(
                        listOf(
                            json(
                                """{"root_id":"checkpoint-default","type":"checkpoint","count":24,"recursive":false,"reason_code":null}""",
                            ),
                        ),
                    )
                path == "models" -> page(listOf(model()))
                path == "models/$modelId" -> model()
                path == "civitai/models" -> page(listOf(civitaiModel()))
                path == "civitai/models/100" -> civitaiModel()
                path == "civitai/versions/200" -> civitaiVersion()
                path == "extensions" && method == "GET" ->
                    page(
                        listOf(extension(protectedId, "ForgeGen Bridge", true), extension(extensionId, "Example Tools", false)),
                    )
                path == "operations" || path == "downloads" -> page(operations.toList())
                path.startsWith("operations/") && method == "GET" ->
                    operations.firstOrNull { it.text("id") == path.substringAfter('/') }
                        ?: JsonObject()
                path == "server/status" ->
                    json(
                        """{"busy":false,"phase":"idle","progress":null,"elapsed_seconds":null,"memory":{"vram_used_bytes":4509715660,"vram_total_bytes":12884901888},"recent_errors":[],"backend":"synthetic"}""",
                    )
                method in setOf("POST", "PATCH", "DELETE") -> {
                    check(!headers["idempotency-key"].isNullOrBlank())
                    if (method == "PATCH") nextEnabled = body.flag("enabled")
                    status = 202
                    val id = UUID.randomUUID().toString()
                    val kind = if (path == "civitai/downloads") "download.create" else "extensions.toggle"
                    operations +=
                        json(
                            """{"id":"$id","kind":"$kind","status":"completed","phase":"finished","progress":null,"bytes_done":null,"bytes_total":null,"created_at":"2026-11-04T12:00:00Z","updated_at":"2026-11-04T12:00:01Z","result":{},"error":null,"restart_required":true}""",
                        )
                    json("""{"operation_id":"$id","status_url":"/forgegen/v1/operations/$id"}""")
                }
                else -> {
                    status = 404
                    json(
                        """{"error":{"code":"ENDPOINT_NOT_FOUND","message":"Synthetic missing route","retryable":false},"request_id":"$serverId"}""",
                    )
                }
            }
        val data = result.toString().toByteArray()
        val out = client.getOutputStream()
        out.write(
            "HTTP/1.1 $status OK\r\nContent-Type: application/json\r\nContent-Length: ${data.size}\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n"
                .toByteArray(),
        )
        out.write(data)
        out.flush()
    }

    private fun model() =
        json(
            """{"id":"$modelId","type":"checkpoint","name":"Example XL.safetensors","root_id":"checkpoint-default","size_bytes":1024,"metadata":{},"source":null}""",
        )

    private fun civitaiVersion() =
        json(
            """{"id":200,"model_id":100,"name":"v2.0","base_model":"SDXL","files":[{"id":300,"name":"Example XL.safetensors","type":"Model","size_kb":1024.0,"sha256":null}]}""",
        )

    private fun civitaiModel() =
        json(
            """{"id":100,"name":"Landscape Study XL","type":"Checkpoint","creator":"Example Creator","license":{},"source_url":"https://civitai.com/models/100","versions":[]}""",
        ).apply { add("versions", JsonArray().apply { add(civitaiVersion()) }) }

    private fun extension(
        id: String,
        name: String,
        protected: Boolean,
    ) = json(
        """{"id":"$id","name":"$name","version":"1.0.0","builtin":false,"protected":$protected,"current_enabled":true,"next_enabled":${if (protected) true else nextEnabled},"installed":true,"restart_required":${!protected && !nextEnabled}}""",
    )

    override fun close() {
        running = false
        socket.close()
    }
}
