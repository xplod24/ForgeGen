package com.example.forgegen

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class GalleryIndexPanelUiTest {
    @get:Rule val compose = createComposeRule()

    private val reading =
        GalleryIndexProgress(
            source = "example",
            stage = GalleryIndexStage.READING_IMAGES,
            indexed = 8_240,
            total = 12_000,
            processed = 8_240,
            workTotal = 12_000,
            foldersChecked = 26,
            foldersTotal = 26,
            unreadable = 12,
        )

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

    @Test fun detailsExpandInlineAndSurviveStateRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme(colorScheme = forgeColorScheme(true), shapes = forgeShapes()) {
                Surface(Modifier.width(360.dp).height(720.dp)) {
                    Column(Modifier.fillMaxSize()) {
                        GalleryIndexPanel(reading, loaded = true, onRetry = {})
                        Text("Gallery images")
                    }
                }
            }
        }
        compose.onNodeWithText("8,240 / 12,000 indexed").assertIsDisplayed()
        compose.onNodeWithText("Reading image data").assertDoesNotExist()
        screenshot("gallery-index-collapsed-dark")
        compose.onNodeWithText("8,240 / 12,000 indexed").performClick()
        compose.onNodeWithText("Reading image data").assertIsDisplayed()
        compose.onNodeWithText("3,760").assertIsDisplayed()
        compose.onNodeWithText("26 / 26").assertIsDisplayed()
        compose.onNodeWithText("Gallery images").assertIsDisplayed()
        screenshot("gallery-index-expanded-dark")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Reading image data").assertIsDisplayed()
        compose.onNodeWithText("8,240 / 12,000 indexed").performClick()
        compose.onNodeWithText("Reading image data").assertDoesNotExist()
    }

    @Test fun interruptedIndexRetainsCountsAndRetryWorksAtLargeText() {
        var retries = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MaterialTheme(colorScheme = forgeColorScheme(false), shapes = forgeShapes()) {
                    Surface(Modifier.width(360.dp).height(720.dp)) {
                        Column {
                            GalleryIndexPanel(
                                reading.copy(stage = GalleryIndexStage.FAILED, error = "Server is not reachable"),
                                true,
                            ) { retries++ }
                            Text("Gallery images")
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("8,240 / 12,000 indexed").performClick()
        compose.onNodeWithText("Index update failed").assertIsDisplayed()
        compose.onNodeWithText("Gallery images").assertIsDisplayed()
        compose
            .onNodeWithText("Retry")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        assertEquals(1, retries)
        screenshot("gallery-index-error-light-large-text")
    }

    @Test fun checkingFoldersAndCompletedIndexHaveHonestLabels() {
        compose.setContent {
            MaterialTheme(colorScheme = forgeColorScheme(true), shapes = forgeShapes()) {
                Column(Modifier.width(360.dp)) {
                    GalleryIndexPanel(reading.copy(stage = GalleryIndexStage.CHECKING_FOLDERS, total = null), true, {})
                    GalleryIndexPanel(reading.copy(source = "complete", stage = GalleryIndexStage.UP_TO_DATE, indexed = 12_000), true, {})
                }
            }
        }
        compose.onNodeWithText("8,240 images indexed").assertIsDisplayed().performClick()
        compose.onNodeWithText("Checking folders…").assertIsDisplayed()
        compose.onNodeWithText("69%").assertDoesNotExist()
        compose.onNodeWithText("12,000 images indexed").assertIsDisplayed()
    }
}
