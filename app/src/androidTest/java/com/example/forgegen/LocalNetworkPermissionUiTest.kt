package com.example.forgegen

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Exercise the production settings row on both sides of a permission decision. */
class LocalNetworkPermissionUiTest {
    @get:Rule val compose = createComposeRule()

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

    @Test fun allowingAccessUpdatesTheExistingConnectionSettings() {
        var requests = 0
        compose.setContent {
            var allowed by remember { mutableStateOf(false) }
            MaterialTheme(colorScheme = forgeColorScheme(false), shapes = forgeShapes()) {
                Surface(Modifier.width(360.dp)) {
                    Column {
                        TextPreference("Server Address", "http://127.0.0.1:7860") {}
                        LocalNetworkPermissionRow(allowed, canRequest = true) {
                            requests++
                            allowed = true
                        }
                        TextPreference("Server Profiles", "1 saved") {}
                    }
                }
            }
        }
        compose.onNodeWithText("Server Address").assertIsDisplayed()
        compose.onNodeWithText("Allow").assertIsDisplayed().performClick()
        compose.onNodeWithText("Allowed · connect to servers on Wi-Fi or Ethernet").assertIsDisplayed()
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithText("Server Profiles").assertIsDisplayed()
        assertEquals(1, requests)
        screenshot("local-network-allowed-light")
    }

    @Test fun permanentDenialKeepsTheSettingsButtonVisibleWithLargeText() {
        var settingsOpened = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MaterialTheme(colorScheme = forgeColorScheme(true), shapes = forgeShapes()) {
                    Surface(Modifier.width(360.dp)) {
                        LocalNetworkPermissionRow(allowed = false, canRequest = false) { settingsOpened++ }
                    }
                }
            }
        }
        compose.onNodeWithText("Local Network Access").assertIsDisplayed()
        compose.onNodeWithText("Settings").assertIsDisplayed().performClick()
        assertEquals(1, settingsOpened)
        screenshot("local-network-denied-dark-large-text")
    }
}
