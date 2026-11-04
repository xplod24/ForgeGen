package com.example.forgegen

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class BridgeClientContractTest {
    private fun objectBody(vararg pairs: Pair<String, String>) = JsonObject().apply { pairs.forEach { (k, v) -> addProperty(k, v) } }

    @Test fun all34OpenApiOperationsUseTheDocumentedMethodBodyAndAuthorization() =
        runBlocking {
            BridgeHarness().use { h ->
                val initial =
                    h.api
                        .request(
                            "POST",
                            "server/unload",
                            h.token,
                            JsonObject(),
                            key = UUID.randomUUID().toString(),
                        ).text("operation_id")
                val routes =
                    JsonParser
                        .parseString(javaClass.getResource("/bridge/routes.json")!!.readText())
                        .asJsonArray
                        .map { it.asJsonObject }
                        .sortedBy { if (it.text("method") == "DELETE" && it.text("path").contains("devices/")) 1 else 0 }
                assertEquals(34, routes.size)
                for (route in routes) {
                    val method = route.text("method")
                    var path = route.text("path").removePrefix("/forgegen/v1/")
                    path =
                        path
                            .replace("{operation_id}", initial)
                            .replace("{device_id}", "00000000-0000-4000-8000-000000000002")
                            .replace("{extension_id}", "00000000-0000-4000-8000-000000000004")
                            .replace(
                                "{version_id}",
                                "200",
                            ).replace("{model_id}", if (path.startsWith("civitai/")) "100" else "00000000-0000-4000-8000-000000000003")
                    val body =
                        when (route.text("request")) {
                            "PairRequest" ->
                                objectBody(
                                    "invitation_id" to h.fixture.getAsJsonObject("invitation").text("invitation_id"),
                                    "code" to h.fixture.getAsJsonObject("invitation").text("code"),
                                    "device_name" to "Contract test phone",
                                )
                            "WaitRequest" -> JsonObject()
                            "MoveRequest" -> objectBody("root_id" to "checkpoint-default", "name" to "Example XL.safetensors")
                            "DownloadRequest" ->
                                objectBody(
                                    "root_id" to "checkpoint-default",
                                    "name" to "Download.safetensors",
                                    "url" to "https://civitai.com/api/download/models/200?fileId=300",
                                )
                            "InstallRequest" ->
                                objectBody(
                                    "repository_url" to "https://github.com/example/extension.git",
                                    "directory_name" to "example-extension",
                                )
                            "CivitaiDownloadRequest" ->
                                objectBody("root_id" to "checkpoint-default").apply {
                                    addProperty("version_id", 200)
                                    addProperty("file_id", 300)
                                }
                            "ToggleRequest" -> JsonObject().apply { addProperty("enabled", false) }
                            "BulkRequest" ->
                                objectBody("action" to "disable").apply {
                                    add("extension_ids", JsonArray().apply { add("00000000-0000-4000-8000-000000000004") })
                                }
                            else -> null
                        }
                    val query =
                        if (method == "DELETE" &&
                            (path.startsWith("models/") || path.startsWith("extensions/"))
                        ) {
                            mapOf("wait_for_idle" to "true")
                        } else {
                            emptyMap()
                        }
                    val response =
                        try {
                            h.api.request(
                                method,
                                path,
                                h.token,
                                body,
                                query,
                                if (method in
                                    listOf("POST", "PATCH", "DELETE")
                                ) {
                                    UUID.randomUUID().toString()
                                } else {
                                    null
                                },
                            )
                        } catch (e: BridgeFailure) {
                            throw AssertionError("Contract route failed: $method $path (${e.code})")
                        }
                    if (route.text("response").isNotEmpty()) {
                        val schema = route.text("response")
                        val type =
                            if (schema.startsWith(
                                    "Page_",
                                )
                            ) {
                                "Bridge" + schema.removePrefix("Page_").trimEnd('_') + "Page"
                            } else {
                                "Bridge$schema"
                            }
                        // Test-only reflection checks every generated DTO, not production method reflection.
                        val parsed = BridgeProtocol.gson.fromJson(response, Class.forName("com.example.forgegen.$type"))
                        assertNotNull("Response DTO: $type", parsed)
                    }
                    h.api.request("OPTIONS", path)
                }
                val records = h.requests().children("items")
                for (record in records) {
                    val method = record.text("method")
                    if (method in listOf("POST", "PATCH", "DELETE")) assertTrue(record.text("key").isNotEmpty())
                    if (method in listOf("GET", "DELETE", "OPTIONS")) assertTrue(record.get("body").isJsonNull)
                    val public =
                        record.text("path").endsWith("/info") ||
                            record.text("path").endsWith("/openapi.json") ||
                            record.text("path").endsWith("/pairing/exchange") ||
                            method == "OPTIONS"
                    assertEquals("Authorization boundary", !public, record.flag("bearer"))
                }
            }
        }

    @Test fun idempotencyConflictsAndServerSideBridgeProtectionAreVisibleToTheClient() =
        runBlocking {
            BridgeHarness().use { h ->
                val key = UUID.randomUUID().toString()
                h.api.request("POST", "server/unload", h.token, JsonObject(), key = key)
                val conflict =
                    runCatching {
                        h.api.request(
                            "POST",
                            "server/restart",
                            h.token,
                            JsonObject(),
                            key = key,
                        )
                    }.exceptionOrNull() as BridgeFailure
                assertEquals("IDEMPOTENCY_CONFLICT", conflict.code)
                val protected =
                    runCatching {
                        h.api.request(
                            "PATCH",
                            "extensions/00000000-0000-4000-8000-000000000005",
                            h.token,
                            JsonObject().apply { addProperty("enabled", false) },
                            key = UUID.randomUUID().toString(),
                        )
                    }.exceptionOrNull() as BridgeFailure
                assertEquals("BRIDGE_PROTECTED", protected.code)
            }
        }

    @Test fun clientRejectsBodiesForDeleteAndKeepsRetryBodyAbsent() =
        runBlocking {
            BridgeHarness().use { h ->
                val failure =
                    runCatching {
                        h.api.request("DELETE", "models/id", h.token, JsonObject(), key = UUID.randomUUID().toString())
                    }.exceptionOrNull()
                assertTrue(failure is IllegalArgumentException)
                assertTrue(h.requests().children("items").isEmpty())
            }
        }

    @Test fun reverseProxyBasePathIsPreservedWithoutReusingForgeAuthorization() =
        runBlocking {
            BridgeHarness().use { h ->
                val api = BridgeApi(h.base + "proxy/forge/", h.client)
                runCatching { api.info() }
                val request = h.requests().children("items").last()
                assertEquals("/proxy/forge/forgegen/v1/info", request.text("path"))
                assertFalse(request.flag("bearer"))
            }
        }
}
