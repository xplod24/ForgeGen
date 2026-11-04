package com.example.forgegen

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal class MemoryBridgeStore : BridgeStore {
    private val sessions = ConcurrentHashMap<String, BridgeSession>()

    override fun read(base: String) = sessions[base] ?: BridgeSession()

    override fun write(
        base: String,
        value: BridgeSession,
    ) {
        sessions[base] = value
    }

    override fun forget(base: String) {
        sessions.remove(base)
    }
}

internal class BridgeHarness(
    scenario: String = "full",
) : Closeable {
    private val directory =
        java.nio.file.Files
            .createTempDirectory("forgegen-bridge-test-")
            .toFile()
    private val credential = File(directory, "fixture.json")
    private val source = listOf(File("server/bridge-harness"), File("../server/bridge-harness")).first { File(it, "server.py").isFile }
    private val process =
        ProcessBuilder(
            "python3",
            "-u",
            "server.py",
            "--port",
            "0",
            "--scenario",
            scenario,
            "--credential-file",
            credential.absolutePath,
        ).directory(source).redirectError(File(directory, "errors.txt")).start()
    val base: String
    val fixture: JsonObject
    val token: String
    val client =
        BridgeApi
            .defaultClient()
            .newBuilder()
            .callTimeout(
                1200,
                TimeUnit.MILLISECONDS,
            ).readTimeout(1200, TimeUnit.MILLISECONDS)
            .build()
    val api: BridgeApi

    init {
        val line = process.inputStream.bufferedReader().readLine() ?: error("Synthetic Bridge did not start")
        val port =
            JsonParser
                .parseString(line)
                .asJsonObject
                .get("port")
                .asInt
        base = "http://127.0.0.1:$port/"
        fixture = JsonParser.parseString(credential.readText()).asJsonObject
        token = fixture.text("token")
        api = BridgeApi(base, client)
    }

    fun session(enabled: Boolean = true): BridgeSession =
        BridgeSession(
            enabled = enabled,
            serverId = fixture.text("server_id"),
            token = token,
            device = BridgeApi.decode<BridgeDevice>(fixture.getAsJsonObject("device")),
        )

    fun control(query: String) {
        plain("/__harness/control?$query")
    }

    fun requests() = plain("/__harness/requests")

    private fun plain(path: String): JsonObject =
        OkHttpClient().newCall(Request.Builder().url(base.trimEnd('/') + path).build()).execute().use {
            JsonParser.parseString(it.body.string()).asJsonObject
        }

    override fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        process.destroy()
        if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
        directory.deleteRecursively()
    }
}

internal class BridgeControllerFixture(
    val harness: BridgeHarness,
    paired: Boolean = true,
    enabled: Boolean = true,
) : Closeable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val urls = MutableStateFlow(harness.base)
    val store =
        MemoryBridgeStore().apply {
            write(
                harness.base,
                if (paired) harness.session(enabled) else BridgeSession(enabled = enabled),
            )
        }
    val controller = BridgeController(scope, urls, store) { BridgeApi(it, harness.client) }

    suspend fun ready(predicate: (BridgeState) -> Boolean = { it.paired }): BridgeState =
        withTimeout(10_000) {
            controller.state.first { !it.busy && predicate(it) }
        }

    override fun close() {
        scope.cancel()
    }
}
