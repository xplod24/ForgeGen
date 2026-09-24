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
                receiveGenerationNotification = false,
                notifOnBatchFinish = true,
                notifOnQueueFinish = false,
                notifCivitaiSync = false,
                autoDismissCivitaiNotif = true,
                notifQueueStatus = true,
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
}
