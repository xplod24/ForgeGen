package com.example.forgegen

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Render production controls, including the existing settings rows, at small widths and enlarged text. */
class VaultUiTest {
    @get:Rule val compose = createComposeRule()

    private fun screenshot(
        name: String,
        dialog: Boolean = false,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), "ui-screenshots/$name.png").apply { parentFile!!.mkdirs() }
        val node = if (dialog) compose.onNode(isDialog()) else compose.onRoot()
        file.outputStream().use {
            node
                .captureToImage()
                .asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun disabledPreservesExistingSettingsAndIsOptional() {
        compose.setContent {
            MaterialTheme(colorScheme = forgeColorScheme(false)) {
                Surface(Modifier.width(360.dp)) {
                    Column {
                        TextPreference(
                            "Export Settings",
                            "Settings, presets, server profiles, wildcards, favorites and the queue to a file",
                        ) {}
                        TextPreference("Import Settings", "Replaces settings; adds favorites and queued jobs") {}
                        TextPreference("Secure Remote Vault", "Optional encrypted storage on your own server") {}
                        VaultSwitches(false, false, false, "Not configured", {}, {})
                        TextPreference("Clear Image Cache", "Images load again from the server") {}
                    }
                }
            }
        }
        compose.onNodeWithText("Export Settings").assertIsDisplayed()
        compose.onNodeWithText("Clear Image Cache").assertIsDisplayed()
        compose.onNodeWithText("Save New Results Automatically").assertDoesNotExist()
        compose.onNode(isToggleable()).assertIsOff().assertIsDisplayed()
        screenshot("vault-disabled-light")
    }

    @Test fun darkLargeTextDoesNotHideControls() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MaterialTheme(colorScheme = forgeColorScheme(true)) {
                    Surface(Modifier.width(360.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            VaultSwitches(true, true, true, "All pending images saved", {}, {})
                            OutlinedButton({}) { Text("Import Entire Forge Gallery") }
                            Text("Trash · 24 hours")
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Save New Results Automatically").assertIsDisplayed()
        compose.onNodeWithText("Import Entire Forge Gallery").assertIsDisplayed()
        compose.onAllNodes(isToggleable())[0].assertIsOn().assertIsDisplayed()
        compose.onAllNodes(isToggleable())[1].assertIsOn().assertIsDisplayed()
        screenshot("vault-enabled-dark-large-text")
    }

    @Test fun unopenedVaultDialogKeepsItsCloseControlVisible() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MaterialTheme(colorScheme = forgeColorScheme(true)) {
                    RemoteVaultPanel(onClose = {})
                }
            }
        }
        compose.onNodeWithText("Close").assertIsDisplayed()
        compose.onNodeWithText("Optional · your server, encrypted on your phone").assertIsDisplayed()
        compose.onNodeWithText("Unlock Vault").assertDoesNotExist()
        screenshot("vault-dialog-disabled-dark-large-text", dialog = true)
    }
}
