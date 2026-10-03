package com.example.forgegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.lang.reflect.Modifier

class ForgeSettingsManagerConfigTest {
    @Test
    fun `every AppConfig field survives a save and load`() {
        val saved =
            AppConfig(
                apiUrl = "http://10.0.0.5:7860",
                serverBasePath = "/srv/forge",
                galleryPath = "/srv/out",
                themeMode = THEME_DARK,
                timeout = 33,
                notifOnBatchFinish = true,
                notifOnQueueFinish = false,
                notificationMode = "Verbose",
                keepScreenOn = true,
                swipeToBrowseGallery = false,
                serverProfiles = listOf(ServerProfile("A", "http://a")),
                useNativeSecurity = true,
                useBiometricLock = true,
                overnightMode = true,
                showGridAfterGeneration = false,
                showActiveTagsUI = false,
                enableLogging = true,
                lastUpdateCheckDate = "2026-09-24",
                defaultState = AppState(steps = 40),
                presets = listOf(GenerationPreset("P", AppState(), includePrompts = false)),
                mainOpenRows = listOf(MainRows.NEGATIVE, MainRows.SIZE),
                autoSaveMode = AUTO_SAVE_FAVORITES,
                autoSaveSince = "2026-09-24 10:00:00",
                saveOomLogs = true,
                nowBarProgress = true,
                hidePromptsInNotifications = false,
                hideInRecents = true,
                blockScreenshots = true,
                savePrivately = true,
                shareWithoutMetadata = true,
                vibrateOnFinish = false,
                autoInstallUpdates = true, // off by default since 3.6.1
                pinchToZoom = false,
                galleryView = GalleryView.LIST_LARGE.name,
                galleryTab = GalleryTab.FAVORITES.name,
                tagSuggestions = false,
                serverStyles = true,
                embeddings = false,
                loraDetails = false,
                resourcePictures = false,
                livePreview = false,
                memoryMeters = false,
                folderCovers = false,
                favoritesCheck = false,
                imageJobs = false,
                serverQueue = false,
                generationHistory = false,
                unloadAfterQueue = 30,
                imageCacheMb = 1024,
                galleryKeys = mapOf("http://192.168.1.90:7860" to GalleryKey.fingerprint("key")),
                modelSettings =
                    mapOf(
                        "flux1-dev" to
                            ModelSettings(
                                type = ModelType.FLUX.name,
                                vae = "ae.safetensors",
                                textEncoders = listOf("clip_l.safetensors", "t5xxl_fp8.safetensors"),
                                distilledCfg = 3f,
                                useDefaults = true,
                                defaults = ModelDefaults(width = 896, height = 1152, steps = 28),
                            ),
                    ),
            )

        // Every field differs from its default here, so a field left out of loadConfig (or out of this test) fails it:
        // 3.1.0's serverStyles was saved but never loaded, and the switch was off again after each start.
        val defaults = AppConfig()
        AppConfig::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.forEach { field ->
            field.isAccessible = true
            assertNotEquals("set AppConfig.${field.name} to a value other than its default", field.get(defaults), field.get(saved))
        }

        val loaded = ForgeSettingsManager.loadConfig(ForgeSettingsManager.gson.toJson(saved))

        assertEquals(saved, loaded)
    }

    @Test
    fun `the feature switches of 3_4_0 are on in an older config, and the image cache keeps a known size`() {
        val old = ForgeSettingsManager.loadConfig("""{"timeout":10,"tagSuggestions":false}""")
        assertEquals(AppConfig(timeout = 10, tagSuggestions = false), old)
        assertEquals(ImageCache.DEFAULT_MB, ForgeSettingsManager.loadConfig("""{"imageCacheMb":123}""").imageCacheMb)
        assertEquals(512, ForgeSettingsManager.loadConfig("""{"imageCacheMb":512}""").imageCacheMb)
        // 3.6.0: the history is on and the model stays after the queue in a config from before.
        assertEquals(true, old.generationHistory)
        assertEquals(AutoUnload.OFF, old.unloadAfterQueue)
        assertEquals(AutoUnload.OFF, ForgeSettingsManager.loadConfig("""{"unloadAfterQueue":7}""").unloadAfterQueue)
        assertEquals(AutoUnload.AT_ONCE, ForgeSettingsManager.loadConfig("""{"unloadAfterQueue":-1}""").unloadAfterQueue)
    }

    @Test
    fun `the dark mode switch of older versions becomes the theme`() {
        assertEquals(THEME_DARK, ForgeSettingsManager.loadConfig("""{"isDarkMode":true}""").themeMode)
        assertEquals("the old default look stays light", THEME_LIGHT, ForgeSettingsManager.loadConfig("""{"isDarkMode":false}""").themeMode)
        assertEquals(THEME_SYSTEM, ForgeSettingsManager.loadConfig("""{"timeout":10}""").themeMode)
        assertEquals(THEME_SYSTEM, ForgeSettingsManager.loadConfig("""{"themeMode":"Purple"}""").themeMode)
        assertEquals(THEME_LIGHT, ForgeSettingsManager.loadConfig("""{"themeMode":"Light","isDarkMode":true}""").themeMode)
        assertEquals("fresh install", THEME_SYSTEM, ForgeSettingsManager.loadConfig(null).themeMode)
    }

    @Test
    fun `a content mode stored by 1_3 to 1_5 is ignored`() {
        val old = ForgeSettingsManager.loadConfig("""{"timeout":10,"contentMode":"Unrestricted","blockScreenshots":true}""")
        assertEquals(AppConfig(timeout = 10, blockScreenshots = true), old)
    }

    @Test
    fun `an unknown gallery layout or tab becomes the default`() {
        assertEquals(GalleryView.GRID_3.name, ForgeSettingsManager.loadConfig("""{"galleryView":"HEXAGONS"}""").galleryView)
        assertEquals(GalleryView.GRID_5, GalleryView.of(ForgeSettingsManager.loadConfig("""{"galleryView":"GRID_5"}""").galleryView))
        assertEquals(GalleryTab.GALLERY.name, ForgeSettingsManager.loadConfig("""{"galleryTab":"TRASH"}""").galleryTab)
    }

    @Test
    fun `missing or broken config falls back to defaults`() {
        assertEquals(AppConfig(), ForgeSettingsManager.loadConfig(null))
        assertEquals(AppConfig(), ForgeSettingsManager.loadConfig("not json"))
    }

    @Test
    fun `stored timeout outside the allowed range is clamped`() {
        // OkHttp throws for a negative timeout, so such a value must never reach the client.
        val negative = ForgeSettingsManager.gson.toJson(AppConfig(timeout = -5))
        val huge = ForgeSettingsManager.gson.toJson(AppConfig(timeout = 100_000))

        assertEquals(1, ForgeSettingsManager.loadConfig(negative).timeout)
        assertEquals(600, ForgeSettingsManager.loadConfig(huge).timeout)
    }
}
