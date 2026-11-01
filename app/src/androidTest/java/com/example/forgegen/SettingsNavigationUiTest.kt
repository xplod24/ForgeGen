package com.example.forgegen

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Follow the real settings entry and real NavHost with one shared production ViewModel. */
class SettingsNavigationUiTest {
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()
    private lateinit var viewModel: ForgeViewModel
    private lateinit var previousConfig: AppConfig
    private lateinit var previousState: AppState
    private val previousGitHubUrl = GitHubApi.baseUrl

    @Before fun prepareLocalFixture() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val db =
            Room
                .databaseBuilder(app, ForgeDatabase::class.java, "forge_db")
                .addMigrations(ForgeRepository.MIGRATION_13_14)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
        val fixture =
            runBlocking {
                val raw = db.appSettingDao().getSetting(ForgeSettingsManager.CONFIG_KEY)?.value
                previousConfig = raw?.let { Gson().fromJson(it, AppConfig::class.java) } ?: AppConfig()
                previousConfig.copy(apiUrl = "http://127.0.0.1:9", timeout = 1, useNativeSecurity = false, resourcePictures = false).also {
                    db.appSettingDao().putSetting(AppSettingEntity(ForgeSettingsManager.CONFIG_KEY, Gson().toJson(it)))
                }
            }
        db.close()
        GitHubApi.baseUrl = "http://127.0.0.1:9/"
        viewModel = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[ForgeViewModel::class.java]
        runBlocking { withTimeout(20_000) { viewModel.initializeApp() } }
        viewModel.saveConfig(fixture)
        previousState = viewModel.appState.value
        viewModel.updateState { it.copy(positivePrompt = "Keep this prompt when returning from settings") }
    }

    @After fun restoreFixture() {
        if (::viewModel.isInitialized && ::previousState.isInitialized) {
            viewModel.saveConfig(previousConfig)
            viewModel.updateState { previousState }
        }
        GitHubApi.baseUrl = previousGitHubUrl
        store.clear()
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

    @Test fun sameEntryOpensAFullScreenAndBackPreservesGenerationState() {
        lateinit var nav: NavHostController
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme(colorScheme = forgeColorScheme(true), typography = forgeTypography(), shapes = forgeShapes()) {
                nav = rememberNavController()
                AppNavigation(viewModel, nav)
            }
        }
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed().performClick()
        compose.onNodeWithText("Search settings").assertIsDisplayed()
        compose.runOnIdle { assertEquals("setup", nav.currentDestination?.route) }
        screenshot("settings-fullscreen-dark")

        compose.onNodeWithText("127.0.0.1:9").performClick()
        compose.onNodeWithText("Server Address").assertIsDisplayed()
        compose.onNodeWithText("Server").assertIsDisplayed()
        screenshot("settings-server-fullscreen-dark")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Search settings").assertIsDisplayed().performTextInput("Gallery Key")
        compose.onNode(hasText("Gallery Key") and !hasSetTextAction()).assertExists()

        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertEquals("setup", nav.currentDestination?.route) }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Search settings").assertIsDisplayed()
        compose.runOnIdle { assertEquals("setup", nav.currentDestination?.route) }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle {
            assertEquals("main", nav.currentDestination?.route)
            assertEquals("Keep this prompt when returning from settings", viewModel.appState.value.positivePrompt)
        }
    }
}
