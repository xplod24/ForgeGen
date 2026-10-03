package com.example.forgegen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/* ============================================================================
 * USTAWIENIA: STRONA "APPEARANCE" (3.6.1, podział SetupScreen na pliki)
 *
 * Co tu jest: wygląd aplikacji: motyw (systemowy, jasny, ciemny), wiersz edycji tagów pod promptem i siatka
 * obrazów po zakończonej serii.
 *
 * Do poczytania: jak Compose rysuje przełącznik (SwitchPreference w SettingsParts.kt) i jak zapis ustawień
 * (viewModel.saveConfig) od razu zmienia ekran: ekran czyta ustawienia jako stan, więc rysuje się na nowo.
 * ============================================================================ */

// Pozycje strony Appearance, w kolejności wyświetlania.
@Composable
internal fun SettingsUiState.appearanceSettings(): List<SettingItem> {
    val config by viewModel.config.collectAsStateWithLifecycle()

    return settingsOf {
        // --- APPEARANCE ---
        // Motyw: stuknięcie otwiera okno wyboru (SettingsDialogs.kt).
        add(SettingsPage.APPEARANCE, null, "theme dark light system colors") {
            TextPreference(
                title = "Theme",
                subtitle = if (config.themeMode == THEME_SYSTEM) "System default" else config.themeMode,
            ) { showThemeDialog = true }
        }
        // Wiersz "Edit Tags" pod promptami (wyłączanie i przestawianie tagów).
        add(SettingsPage.APPEARANCE, null, "show active tags ui edit tags prompts row") {
            SwitchPreference(
                title = "Show Active Tags UI",
                subtitle = "Show the \"Edit Tags\" row under the prompts to switch tags off and reorder them",
                checked = config.showActiveTagsUI,
                onCheckedChange = { viewModel.saveConfig(config.copy(showActiveTagsUI = it)) },
            )
        }
        // Siatka wszystkich obrazów serii po jej zakończeniu.
        add(SettingsPage.APPEARANCE, null, "show grid after batch images finished") {
            SwitchPreference(
                title = "Show Grid After Batch",
                subtitle = "Show all images of a batch as a grid when it finishes, until you open one or the next job starts",
                checked = config.showGridAfterGeneration,
                onCheckedChange = { viewModel.saveConfig(config.copy(showGridAfterGeneration = it)) },
            )
        }
    }
}
