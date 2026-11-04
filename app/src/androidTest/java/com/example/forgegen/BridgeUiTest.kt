package com.example.forgegen

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class BridgeUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: BridgeUiFixture
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var controller: BridgeController

    @After fun cleanup() {
        scope.cancel()
        if (::server.isInitialized) server.close()
    }

    private fun start(
        countOnly: Boolean = false,
        paired: Boolean = true,
        dark: Boolean = true,
        pairingOnly: Boolean = false,
        adminAccess: Boolean = false,
    ) {
        server = BridgeUiFixture(countOnly, pairingOnly, adminAccess).apply { configure(paired) }
        controller = BridgeController(scope, flowOf(server.base), server.store)
        runBlocking { withTimeout(10_000) { controller.state.first { !it.busy && it.base == server.base && (!paired || it.paired) } } }
        compose.setContent {
            MaterialTheme(colorScheme = forgeColorScheme(dark), typography = forgeTypography(), shapes = forgeShapes()) {
                BridgeScreen(controller, onBack = {}, serverName = "Home Lab")
            }
        }
        if (paired) awaitText("Paired · Bridge 1.2.0")
    }

    private fun awaitText(value: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), "ui-screenshots/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use {
            compose
                .onRoot()
                .captureToImage()
                .asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun fullAccessScreensAndConfirmedExtensionChangeUseTheRealClient() {
        start()
        compose.onNodeWithText("Paired · Bridge 1.2.0").assertIsDisplayed()
        screenshot("bridge-home-dark")
        compose.onNodeWithText("Models").performScrollTo().performClick()
        compose.onNodeWithText("Browse model catalog").performScrollTo().performClick()
        awaitText("Example XL.safetensors")
        compose.onNodeWithText("Example XL.safetensors").performScrollTo().performClick()
        awaitText("Delete model…")
        compose.onNodeWithText("Delete model…").performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertFalse(server.requests.any { it.method == "DELETE" })
        repeat(3) { compose.onNodeWithContentDescription("Back").performClick() }
        compose.onNodeWithText("Extensions").performScrollTo().performClick()
        awaitText("Protected")
        compose.onNodeWithText("Protected").assertExists()
        compose.onNodeWithContentDescription("Enable Example Tools after restart").performScrollTo().performClick()
        compose.onNodeWithText("Submit").performClick()
        compose.waitUntil(10_000) { server.requests.any { it.method == "PATCH" } }
        compose.waitUntil(10_000) { !controller.state.value.busy }
        awaitText("Restart required")
        compose.onNodeWithText("Restart required").assertExists()
        screenshot("bridge-extensions-dark")
        assertTrue(
            server.requests.filter { it.method == "PATCH" }.all {
                !it.key.isNullOrBlank() &&
                    it.fields == setOf("enabled", "wait_for_idle")
            },
        )
    }

    @Test fun countOnlyPolicyShowsCountsAndBlocksCatalogAndFileActions() {
        start(countOnly = true, dark = false)
        compose.onNodeWithText("Models").performScrollTo().performClick()
        compose.onNodeWithText("Counts only").assertIsDisplayed()
        awaitText("checkpoint · 24")
        compose.onNodeWithText("checkpoint · 24").assertExists()
        compose.onNodeWithText("Browse model catalog").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Scan and refresh models").performScrollTo().assertIsNotEnabled()
        screenshot("bridge-model-counts-light")
        assertFalse(server.requests.any { it.path == "models" || it.method in setOf("POST", "DELETE", "PATCH") })
    }

    @Test fun optionalDisabledStatePairsFromAnInvitationWithoutClientScopes() {
        start(paired = false)
        compose.onNodeWithText("Not enabled").assertExists()
        assertTrue(server.requests.isEmpty())
        compose.onNodeWithText("Use ForgeGen Bridge").performClick()
        compose.waitUntil(10_000) { controller.state.value.connection == BridgeConnection.PAIRING_REQUIRED && !controller.state.value.busy }
        compose.onNodeWithText("Or paste invitation JSON").performScrollTo().performTextInput(server.invitation)
        compose.onNode(hasText("Pair this phone") and hasClickAction()).performScrollTo().performClick()
        compose.waitUntil(10_000) { controller.state.value.paired && !controller.state.value.busy }
        compose.onNodeWithText("Paired · Bridge 1.2.0").assertExists()
        val exchange = server.requests.single { it.path == "pairing/exchange" }
        assertEquals(setOf("invitation_id", "code", "device_name"), exchange.fields)
        assertFalse(exchange.key.isNullOrBlank())
    }

    @Test fun civitaiDownloadsTheChosenVersionAndFileToARegisteredRoot() {
        start()
        compose.onNodeWithText("Civitai").performScrollTo().performClick()
        awaitText("Landscape Study XL")
        compose.onNodeWithText("Landscape Study XL").performClick()
        awaitText("v2.0")
        compose.onNodeWithText("v2.0").performScrollTo().performClick()
        awaitText("Example XL.safetensors")
        screenshot("bridge-civitai-file-dark")
        compose.onNodeWithText("Download this file…").performScrollTo().performClick()
        compose.onNodeWithText("Select…").performClick()
        compose.onNodeWithText("checkpoint · checkpoint-default").performClick()
        compose.onNodeWithText("Submit").performClick()
        compose.waitUntil(10_000) { server.requests.any { it.path == "civitai/downloads" } && !controller.state.value.busy }
        val sent = server.requests.single { it.path == "civitai/downloads" }
        assertEquals(setOf("version_id", "file_id", "root_id", "wait_for_idle"), sent.fields)
        assertFalse(sent.key.isNullOrBlank())
        assertNotNull(controller.state.value.lastOperationId)
    }
    @Test fun pairingOnlyModeIsVisibleAndAdminPairingUsesAnOwnerGrant() {
        start(paired = false, pairingOnly = true, adminAccess = true)
        compose.onNodeWithText("Use ForgeGen Bridge").performClick()
        awaitText("Pairing service only")
        compose.waitUntil(10_000) { !controller.state.value.busy }
        compose.onNodeWithText("Pair as administrator").performScrollTo().performClick()
        compose.onNodeWithText("Or paste invitation JSON").performScrollTo().performTextInput(server.invitation)
        compose.onNode(hasText("Pair this phone") and hasClickAction()).performScrollTo().performClick()
        compose.waitUntil(10_000) { controller.state.value.administrator && !controller.state.value.busy }
        compose.onNodeWithText("Administrator access · all Bridge permissions").assertExists()
        assertTrue(controller.state.value.pairingOnly)
        assertFalse(controller.state.value.can("server.restart"))
        assertTrue(server.requests.single { it.path == "pairing/exchange" }.fields.contains("require_admin_access"))
        screenshot("bridge-admin-pairing-only")
    }

    @Test fun countOnlyServerOffersAnExplicitSingleFileScan() {
        start(countOnly = true)
        compose.onNodeWithText("Models").performScrollTo().performClick()
        compose.onNodeWithText("Scan one selected model").performScrollTo().performClick()
        compose.onNodeWithText("Select…").performClick()
        compose.onNodeWithText("checkpoint · checkpoint-default").performClick()
        compose.onNodeWithText("One file path relative to this root").performTextInput("synthetic/model.safetensors")
        screenshot("bridge-selected-file-scan")
        compose.onNodeWithText("Submit").performClick()
        compose.waitUntil(10_000) { !controller.state.value.busy && server.requests.any { it.path == "models/scan-selected" } }
        val request = server.requests.single { it.path == "models/scan-selected" }
        assertEquals(setOf("root_id", "relative_path", "wait_for_idle", "lookup_civitai"), request.fields)
        assertFalse(request.key.isNullOrBlank())
        assertFalse(server.requests.any { it.path == "models/scan" || it.path == "models" })
        assertNotNull(controller.state.value.lastOperationId)
    }

}
