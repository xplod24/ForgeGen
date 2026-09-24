package com.example.forgegen

import org.junit.Assert.assertEquals
import org.junit.Test

class ForgeSettingsManagerConfigTest {
    @Test
    fun `every AppConfig field survives a save and load`() {
        val saved =
            AppConfig(
                apiUrl = "http://10.0.0.5:7860",
                serverBasePath = "/srv/forge",
                galleryPath = "/srv/out",
                isDarkMode = true,
                timeout = 33,
                notifOnBatchFinish = true,
                notifOnQueueFinish = false,
                notifCivitaiSync = false,
                autoDismissCivitaiNotif = true,
                notificationMode = "Verbose",
                keepScreenOn = true,
                enablePersistentService = true,
                swipeToBrowseGallery = false,
                bottomSheetExpandedByDefault = true,
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
                autoSyncModels = true,
                mainPromptsExpanded = false,
                mainSettingsExpanded = true,
                mainLorasExpanded = true,
            )

        val loaded = ForgeSettingsManager.loadConfig(ForgeSettingsManager.gson.toJson(saved))

        assertEquals(saved, loaded)
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
