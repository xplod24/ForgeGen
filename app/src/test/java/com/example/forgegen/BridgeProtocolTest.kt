package com.example.forgegen

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

class BridgeProtocolTest {
    private val sid = UUID.randomUUID().toString()

    private fun invite() =
        JsonObject().apply {
            addProperty("format", "forgegen-pairing")
            addProperty("format_version", 1)
            addProperty("server_url", "http://forge.test:7860/subpath")
            addProperty("server_id", sid)
            addProperty("invitation_id", UUID.randomUUID().toString())
            addProperty("code", "fictional-single-use-code-only")
            addProperty("expires_at", "2026-11-04T12:02:00Z")
        }

    private val now = Instant.parse("2026-11-04T12:00:00Z")

    @Test fun basePreservesProxySubpathsAndCanonicalizesHost() {
        assertEquals("https://forge.test/proxy/forge/", BridgeProtocol.base("https://FORGE.test/proxy/forge"))
        assertEquals(BridgeProtocol.base("http://forge.test:80/"), BridgeProtocol.base("http://forge.test"))
    }

    @Test fun rejectsEmbeddedCredentialsAndNonBaseAddresses() {
        listOf(
            "https://name:secret@forge.test/",
            "https://forge.test/?token=secret",
            "https://forge.test/#secret",
            "file:///tmp/forge",
        ).forEach {
            assertTrue(runCatching { BridgeProtocol.base(it) }.isFailure)
        }
    }

    @Test fun invitationUsesExactSelectedServerAndExpiry() {
        val parsed = BridgeProtocol.invitation(invite().toString(), "http://forge.test:7860/subpath/", now)
        assertEquals(sid, parsed.serverId)
        assertFalse(parsed.toString().contains(parsed.code))
        assertTrue(runCatching { BridgeProtocol.invitation(invite().toString(), "http://another.test/", now) }.isFailure)
        assertTrue(
            runCatching {
                BridgeProtocol.invitation(
                    invite().toString(),
                    "http://forge.test:7860/subpath",
                    now.plusSeconds(121),
                )
            }.isFailure,
        )
    }

    @Test fun rejectsInvalidOrFutureInvitationFormat() {
        val json = invite().apply { addProperty("format_version", 2) }
        assertTrue(runCatching { BridgeProtocol.invitation(json.toString(), "http://forge.test:7860/subpath", now) }.isFailure)
        assertTrue(runCatching { BridgeProtocol.objectJson("not JSON") }.isFailure)
    }

    @Test fun capabilitiesFailClosedAndRespectCountOnlyAndScopes() {
        val yes = BridgeCapability(true, true, true, null)
        val state =
            BridgeState(
                connection = BridgeConnection.CONNECTED,
                device = BridgeDevice(scopes = listOf("models.read", "models.manage")),
                capabilities =
                    BridgeCapabilities(
                        features = mapOf("models.catalog" to yes, "models.count" to yes),
                        privacyPolicy = "count_only",
                    ),
            )
        assertTrue(state.can("models.count"))
        assertFalse(state.can("models.catalog"))
        assertFalse(state.can("future.action"))
        assertFalse(BridgeProtocol.allowed(yes.copy(reasonCode = "FUTURE_BLOCK")))
        assertFalse(
            state
                .copy(
                    capabilities = state.capabilities!!.copy(privacyPolicy = "full"),
                    device = BridgeDevice(scopes = emptyList()),
                ).can("models.catalog"),
        )
        val extensionState =
            state.copy(
                device = BridgeDevice(scopes = listOf("extensions.manage")),
                capabilities =
                    BridgeCapabilities(
                        features = mapOf("extensions.toggle" to yes),
                        requiredFlags =
                            mapOf(
                                "enable_insecure_extension_access" to false,
                                "extension_access_allowed" to true,
                                "extensions_globally_allowed" to true,
                            ),
                        privacyPolicy = "full",
                    ),
            )
        assertFalse(extensionState.can("extensions.toggle"))
        assertTrue(extensionState.reason("extensions.toggle").contains("--enable-insecure-extension-access"))
    }

    @Test fun identityAndProtocolAreValidatedBeforeAuthorization() {
        val info =
            BridgeInfo(
                "forgegen-bridge",
                "1.0.0",
                BridgeApiVersion(1, 0),
                sid,
                "bearer-device-token",
                "administrator-issued-single-use-invitation",
                "/forgegen/v1/openapi.json",
            )
        BridgeProtocol.verify(info)
        listOf(
            info.copy(bridgeId = "wrong"),
            info.copy(serverId = "wrong"),
            info.copy(bridgeVersion = "latest"),
            info.copy(apiVersion = BridgeApiVersion(2, 0)),
        ).forEach {
            assertTrue(runCatching { BridgeProtocol.verify(it) }.isFailure)
        }
    }

    @Test fun sourceCredentialsAndUnsafeNamesNeverEnterPendingStorage() {
        listOf(
            "https://civitai.com/file?token=secret",
            "https://user:secret@civitai.com/file",
            "http://civitai.com/file",
            "https://other.test/file",
        ).forEach { url ->
            assertTrue(
                runCatching {
                    BridgeProtocol.validateMutation(
                        "POST",
                        "downloads",
                        JsonObject().apply { addProperty("url", url) },
                    )
                }.isFailure,
            )
        }
        assertTrue(
            runCatching {
                BridgeProtocol.validateMutation("POST", "models/id/move", JsonObject().apply { addProperty("name", "../model") })
            }.isFailure,
        )
        BridgeProtocol.validateMutation(
            "POST",
            "downloads",
            JsonObject().apply {
                addProperty("url", "https://civitai.com/api/download/models/200?fileId=300")
            },
        )
    }

    @Test fun secretDtosRedactTheirStringRepresentation() {
        assertFalse(BridgePairRequest("id", "synthetic-secret", "phone").toString().contains("synthetic-secret"))
        assertFalse(BridgePairResponse(accessToken = "synthetic-secret").toString().contains("synthetic-secret"))
        assertFalse(BridgeSession(token = "synthetic-secret").toString().contains("synthetic-secret"))
    }

    @Test fun localQrDecoderReadsTheActualInvitationFormatWithoutNetworkServices() {
        val json = invite().toString()
        val matrix =
            com.google.zxing.qrcode
                .QRCodeWriter()
                .encode(json, com.google.zxing.BarcodeFormat.QR_CODE, 512, 512)
        val pixels = IntArray(512 * 512) { i -> if (matrix[i % 512, i / 512]) 0xff000000.toInt() else 0xffffffff.toInt() }
        val bitmap =
            com.google.zxing.BinaryBitmap(
                com.google.zxing.common
                    .HybridBinarizer(com.google.zxing.RGBLuminanceSource(512, 512, pixels)),
            )
        val decoded =
            com.google.zxing
                .MultiFormatReader()
                .decode(bitmap)
        assertEquals(sid, BridgeProtocol.invitation(decoded.text, "http://forge.test:7860/subpath/", now).serverId)
    }
}
