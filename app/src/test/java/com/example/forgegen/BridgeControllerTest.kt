package com.example.forgegen

import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeControllerTest {
    @Test fun disabledBridgeDoesNotProbeAnyEndpoint() =
        runBlocking {
            BridgeHarness().use { h ->
                BridgeControllerFixture(h, enabled = false).use { f ->
                    f.ready { it.base == h.base }
                    delay(100)
                    assertTrue(h.requests().children("items").isEmpty())
                }
            }
        }

    @Test fun pairingUsesQrIdentityThenVerifiesTheDeviceAndScopes() =
        runBlocking {
            BridgeHarness().use { h ->
                BridgeControllerFixture(h, paired = false).use { f ->
                    f.ready { it.connection == BridgeConnection.PAIRING_REQUIRED }
                    f.controller.pair(h.fixture.getAsJsonObject("invitation").toString(), "My synthetic phone")
                    val state = f.ready()
                    assertEquals("My synthetic phone", state.device?.name)
                    assertTrue(state.can("extensions.toggle"))
                    val req = h.requests().children("items").first { it.text("path").endsWith("pairing/exchange") }
                    assertFalse(req.flag("bearer"))
                    assertEquals(setOf("invitation_id", "code", "device_name"), req.getAsJsonObject("body").keySet())
                    assertFalse(
                        f.store
                            .read(h.base)
                            .toString()
                            .contains(h.token),
                    )
                }
            }
        }

    @Test fun changedServerIdentityNeverReceivesTheSavedDeviceToken() =
        runBlocking {
            BridgeHarness("server-changed").use { h ->
                BridgeControllerFixture(h).use { f ->
                    f.ready { it.connection == BridgeConnection.SERVER_CHANGED }
                    assertTrue(h.requests().children("items").all { !it.flag("bearer") })
                }
            }
        }

    @Test fun unknownResponseAndAbsentBridgeDoNotAffectBasicForgeAccess() =
        runBlocking {
            for (scenario in listOf("absent", "html", "incompatible", "wrong-id", "timeout")) {
                BridgeHarness(scenario).use { h ->
                    BridgeControllerFixture(h).use { f ->
                        f.ready {
                            it.connection in
                                setOf(BridgeConnection.ABSENT, BridgeConnection.UNAVAILABLE, BridgeConnection.INCOMPATIBLE)
                        }
                        val client = okhttp3.OkHttpClient()
                        client
                            .newCall(
                                okhttp3.Request
                                    .Builder()
                                    .url(h.base + "sdapi/v1/progress")
                                    .build(),
                            ).execute()
                            .use { assertEquals(200, it.code) }
                        assertFalse(
                            h
                                .requests()
                                .children("items")
                                .last()
                                .flag("bearer"),
                        )
                    }
                }
            }
        }

    @Test fun aLostAcknowledgmentIsRecoveredWithTheSameKeyAndOperation() =
        runBlocking {
            BridgeHarness().use { h ->
                BridgeControllerFixture(h).use { f ->
                    f.ready()
                    h.control("drop=1")
                    f.controller.perform("Scan models", "models.scan", "POST", "models/scan", JsonObject())
                    val uncertain = f.ready { it.pending != null }
                    val key = uncertain.pending!!.key
                    // A recreated Android controller must preserve the original intention and key.
                    f.close()
                    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                    try {
                        val recreated = BridgeController(scope, kotlinx.coroutines.flow.flowOf(h.base), f.store) { BridgeApi(it, h.client) }
                        withTimeout(6000) { recreated.state.first { it.paired && !it.busy } }
                        assertEquals(
                            key,
                            recreated.state.value.pending
                                ?.key,
                        )
                        recreated.retryPending()
                        val accepted =
                            withTimeout(6000) {
                                recreated.state.first {
                                    !it.busy &&
                                        it.pending == null &&
                                        it.lastOperationId != null
                                }
                            }
                        assertTrue(BridgeProtocol.uuid(accepted.lastOperationId))
                        val sent = h.requests().children("items").filter { it.text("path").endsWith("models/scan") }
                        assertEquals(2, sent.size)
                        assertTrue(sent.all { it.text("key") == key })
                        assertEquals(1, h.requests().get("operations").asInt)
                    } finally {
                        scope.cancel()
                    }
                }
            }
        }

    @Test fun revocationClearsOnlyBridgeCredentialsAndRequiresExplicitPairing() =
        runBlocking {
            BridgeHarness().use { h ->
                BridgeControllerFixture(h).use { f ->
                    f.ready()
                    h.control("revoke=1")
                    runCatching { f.controller.read("server/status") }
                    f.ready { it.connection == BridgeConnection.REVOKED }
                    assertEquals("", f.store.read(h.base).token)
                    assertEquals(h.base, f.controller.state.value.base)
                    assertTrue(f.controller.state.value.enabled)
                }
            }
        }

    @Test fun revocationRemainsIsolatedWhenPrivateStorageCannotBeUpdated() =
        runBlocking {
            BridgeHarness().use { h ->
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    val backing = MemoryBridgeStore().apply { write(h.base, h.session()) }
                    val unavailable =
                        object : BridgeStore by backing {
                            override fun write(
                                base: String,
                                value: BridgeSession,
                            ): Unit = throw java.io.IOException("Synthetic storage failure")
                        }
                    val controller =
                        BridgeController(scope, kotlinx.coroutines.flow.flowOf(h.base), unavailable) { BridgeApi(it, h.client) }
                    withTimeout(6000) { controller.state.first { it.paired && !it.busy } }
                    h.control("revoke=1")
                    val failure = runCatching { controller.read("server/status") }.exceptionOrNull()
                    assertEquals("TOKEN_REVOKED", (failure as BridgeFailure).code)
                    assertEquals(BridgeConnection.REVOKED, controller.state.value.connection)
                    assertNull(controller.state.value.device)
                    assertEquals(h.base, controller.state.value.base)
                } finally {
                    scope.cancel()
                }
            }
        }

    @Test fun livePolicyBlocksOnlyTheRelevantFeatureAndNeverSendsTheMutation() =
        runBlocking {
            for ((scenario, blocked, permitted) in listOf(
                Triple("count-only", "models.scan", "models.count"),
                Triple("missing-flag", "extensions.install", "extensions.list"),
                Triple("civitai-disabled", "civitai.search", "status"),
            )) {
                BridgeHarness(scenario).use { h ->
                    BridgeControllerFixture(h).use { f ->
                        val state = f.ready()
                        assertFalse(state.can(blocked))
                        assertTrue(state.can(permitted))
                        f.controller.perform("Blocked action", blocked, "POST", "models/scan", JsonObject())
                        delay(100)
                        assertFalse(h.requests().children("items").any { it.text("method") == "POST" })
                    }
                }
            }
        }

    @Test fun profilesAreIsolatedAndDisablingPreservesUncertainWork() =
        runBlocking {
            BridgeHarness().use { h ->
                BridgeControllerFixture(h).use { f ->
                    f.ready()
                    h.control("drop=1")
                    f.controller.perform("Unload model", "server.unload", "POST", "server/unload", JsonObject())
                    f.ready { it.pending != null }
                    f.controller.setEnabled(false)
                    f.ready { !it.enabled }
                    assertNotNull(f.store.read(h.base).pending)
                    f.urls.value = "http://127.0.0.1:1/different/"
                    val state = f.ready { it.base.endsWith("different/") }
                    assertFalse(state.enabled)
                    assertNull(state.device)
                    assertNull(state.pending)
                }
            }
        }
}
