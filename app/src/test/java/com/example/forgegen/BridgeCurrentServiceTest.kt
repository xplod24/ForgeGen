package com.example.forgegen

import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeCurrentServiceTest {
    @Test fun selectedScanWorksWithoutEnablingBulkModelAccess() = runBlocking {
        BridgeHarness("count-only").use { h ->
            BridgeControllerFixture(h).use { f ->
                val state = f.ready()
                assertTrue(state.can("models.scan_selected"))
                assertFalse(state.can("models.scan"))
                assertFalse(state.can("models.catalog"))
                val body = JsonObject().apply {
                    addProperty("root_id", "checkpoint-default")
                    addProperty("relative_path", "synthetic/model.safetensors")
                    addProperty("wait_for_idle", true)
                    addProperty("lookup_civitai", false)
                }
                f.controller.perform("Selected file", "models.scan_selected", "POST", "models/scan-selected", body)
                f.ready { it.lastOperationId != null && it.pending == null }
                val sent = h.requests().children("items").single { it.text("method") == "POST" }
                assertEquals("/forgegen/v1/models/scan-selected", sent.text("path"))
                assertEquals(body, sent.getAsJsonObject("body"))
                assertFalse(sent.text("key").isBlank())
            }
        }
    }

    @Test fun pairingOnlyRefreshKeepsTheSameDeviceAndToken() = runBlocking {
        BridgeHarness("pairing-only").use { h ->
            BridgeControllerFixture(h).use { f ->
                val state = f.ready()
                assertTrue(state.pairingOnly)
                assertFalse(state.can("server.restart"))
                assertTrue(state.can("devices.revoke"))
                assertTrue(state.reason("server.restart").contains("pairing service"))
                val token = f.store.read(h.base).token
                h.control("scenario=full")
                f.controller.refresh()
                val full = f.ready { it.can("server.restart") && !it.pairingOnly }
                assertEquals(state.device?.id, full.device?.id)
                assertEquals(token, f.store.read(h.base).token)
            }
        }
    }

    @Test fun administratorPairingUsesServerGrantWithoutSendingScopes() = runBlocking {
        BridgeHarness("admin").use { h ->
            BridgeControllerFixture(h, paired = false).use { f ->
                f.ready { it.connection == BridgeConnection.PAIRING_REQUIRED }
                f.controller.pair(h.fixture.getAsJsonObject("invitation").toString(), "Synthetic administrator", true)
                val state = f.ready { it.administrator }
                assertTrue(state.can("extensions.install"))
                assertTrue(state.can("server.restart"))
                val body = h.requests().children("items").single { it.text("path").endsWith("/pairing/exchange") }.getAsJsonObject("body")
                assertTrue(body.get("require_admin_access").asBoolean)
                assertFalse(body.has("admin_access"))
                assertFalse(body.has("scopes"))
                assertEquals(true, f.store.read(h.base).device?.adminAccess)
            }
        }
    }

    @Test fun readOnlyInvitationCannotBecomeAdministratorByClientChoice() = runBlocking {
        BridgeHarness("read-only").use { h ->
            BridgeControllerFixture(h, paired = false).use { f ->
                f.ready { it.connection == BridgeConnection.PAIRING_REQUIRED }
                f.controller.pair(h.fixture.getAsJsonObject("invitation").toString(), "Synthetic phone", true)
                val state = f.ready { it.message != null }
                assertFalse(state.paired)
                assertFalse(state.administrator)
                assertTrue(state.message.orEmpty().contains("does not grant administrator"))
                assertTrue(f.store.read(h.base).token.isEmpty())
                assertFalse(h.requests().children("items").any { it.text("path").endsWith("/devices/me") })
            }
        }
    }

    @Test fun selectedFileValidationRejectsTraversalAndAbsolutePaths() {
        for (path in listOf("../file.safetensors", "/file.safetensors", "C:\\file.safetensors", "a/./b", "a//b")) {
            val body = JsonObject().apply { addProperty("root_id", "checkpoint-default"); addProperty("relative_path", path) }
            assertTrue(path, runCatching { BridgeProtocol.validateMutation("POST", "models/scan-selected", body) }.isFailure)
        }
        val safe = JsonObject().apply { addProperty("root_id", "checkpoint-default"); addProperty("relative_path", "synthetic/file.safetensors") }
        BridgeProtocol.validateMutation("POST", "models/scan-selected", safe)
    }
}
