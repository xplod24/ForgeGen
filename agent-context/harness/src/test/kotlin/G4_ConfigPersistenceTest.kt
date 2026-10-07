package com.example.forgegen

import org.junit.Assert.assertEquals
import org.junit.Test

class G4_ConfigPersistenceTest {
    @Test fun `wszystkie ustawienia przetrwaja zapis i odczyt`() {
        TestMain.ensure()
        val saved = AppConfig(
            apiUrl = "http://10.0.0.5:7860", serverBasePath = "/srv/forge", galleryPath = "/srv/out", themeMode = THEME_DARK, timeout = 33,
            notifOnBatchFinish = true, notifOnQueueFinish = false,
            notificationMode = "Verbose", keepScreenOn = true,
            swipeToBrowseGallery = false,
            serverProfiles = listOf(ServerProfile("A", "http://a")), useNativeSecurity = true, useBiometricLock = true, overnightMode = true,
            showGridAfterGeneration = false, showActiveTagsUI = false, enableLogging = true, lastUpdateCheckDate = "2026-09-24",
            defaultState = AppState(steps = 40), presets = listOf(GenerationPreset("P", AppState(), includePrompts = false)),
            mainOpenRows = listOf("sampling"),
            modelSettings = mapOf("sdxl" to ModelSettings(type = ModelType.SDXL.name, useDefaults = true, defaults = ModelDefaults(steps = 30))),
        )
        val loaded = ForgeSettingsManager.loadConfig(ForgeSettingsManager.gson.toJson(saved))
        assertEquals(saved, loaded)
    }
}
