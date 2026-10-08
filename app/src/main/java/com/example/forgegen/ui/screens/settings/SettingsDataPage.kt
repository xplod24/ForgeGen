package com.example.forgegen

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.imageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/* ============================================================================
 * USTAWIENIA: STRONY "BACKUP & DATA" I "DEBUG" (3.6.1, podział SetupScreen na pliki)
 *
 * Co tu jest:
 * - Backup: eksport i import ustawień (z presetami, profilami, wildcardami, ulubionymi i kolejką) do pliku JSON,
 * - Logs: zapis raportu z logiem do Pobranych, gdy aplikacji lub serwerowi zabraknie pamięci,
 * - Storage: pamięć obrazów na telefonie (rozmiar, czyszczenie) i opcjonalny zdalny schowek,
 * - Danger Zone: usuwanie wybranych danych aplikacji,
 * - Debug: narzędzia testowe (tylko po odblokowaniu trybu debugowania).
 *
 * Do poczytania:
 * - Storage Access Framework: systemowe okno wyboru pliku (CreateDocument / OpenDocument),
 * - Coil: biblioteka ładowania obrazów, jej pamięć RAM (memoryCache) i na dysku (diskCache),
 * - Dispatchers.IO: wątki do pracy z plikami i siecią, żeby nie blokować ekranu.
 * ============================================================================ */

// Pozycje stron Backup & Data oraz Debug, w kolejności wyświetlania.
@Composable
internal fun SettingsUiState.dataSettings(): List<SettingItem> {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val debugUnlocked by viewModel.debugUnlocked.collectAsStateWithLifecycle()

    var showVault by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    if (showVault) RemoteVaultPanel { showVault = false }
    return settingsOf {
        // --- BACKUP & DATA ---
        // Eksport: systemowe okno zapisu pliku, domyślna nazwa z dzisiejszą datą.
        add(SettingsPage.DATA, "Backup", "export settings backup file presets profiles wildcards favorites queue") {
            TextPreference(
                title = "Export Settings",
                subtitle = "Settings, presets, server profiles, wildcards, favorites and the queue to a file",
            ) {
                val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                exportLauncher.launch("forgegen-backup-$date.json")
            }
        }
        // Import: systemowe okno wyboru pliku; potem okno potwierdzenia (SettingsDialogs.kt).
        add(SettingsPage.DATA, "Backup", "import settings backup file restore favorites queue") {
            TextPreference(
                title = "Import Settings",
                subtitle = "Replaces the settings, presets and server profiles; adds the wildcards, favorites and queued jobs",
            ) {
                importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            }
        }
        // Raport z logiem do Pobranych przy braku pamięci (nie wymaga uprawnień do plików).
        add(SettingsPage.DATA, "Logs", "save logs on out of memory oom report downloads") {
            SwitchPreference(
                title = "Save Logs on Out of Memory",
                subtitle =
                    "When the app or the server runs out of memory, save a report with the app's log to " +
                        "Downloads (ForgeGen-OOM-date.txt). Android needs no storage permission for it.",
                checked = config.saveOomLogs,
                onCheckedChange = { viewModel.saveConfig(config.copy(saveOomLogs = it)) },
            )
        }
        // Rozmiar pamięci obrazów (do 2,5 GB) i ile jest zajęte; zajętość liczona przy pokazaniu wiersza.
        // The image cache on the phone (3.4.0): its size, up to 2.5 GB (the owner's decision), and emptying it.
        add(SettingsPage.DATA, "Storage", "image cache size thumbnails storage space disk") {
            LaunchedEffect(Unit) { imageCacheUsed = withContext(Dispatchers.IO) { ImageCache.usedBytes(context) } }
            val restart = ImageCache.builtWithMb != 0 && ImageCache.builtWithMb != config.imageCacheMb
            TextPreference(
                title = "Image Cache",
                subtitle =
                    "Up to ${ImageCache.label(config.imageCacheMb)} of thumbnails and images · " +
                        (imageCacheUsed?.let { "${ImageCache.formatBytes(it)} used" } ?: "counting...") +
                        if (restart) " · the new size applies after a restart" else "",
            ) { showImageCacheDialog = true }
        }
        // Czyści pamięć obrazów (obrazy załadują się znowu z serwera).
        add(SettingsPage.DATA, "Storage", "clear image cache thumbnails free space") {
            TextPreference(title = "Clear Image Cache", subtitle = "Frees the space; images load again from the server") {
                scope.launch(Dispatchers.IO) {
                    clearImageCache(context)
                    imageCacheUsed = ImageCache.usedBytes(context)
                    viewModel.showToast("Image cache cleared")
                }
            }
        }
        // Opcjonalny schowek otwiera osobny panel; ustawienia i galeria pozostają niezależne.
        add(SettingsPage.DATA, "Storage", "secure remote vault encrypted archive") {
            TextPreference("Secure Remote Vault", "Optional encrypted storage on your own server") { showVault = true }
        }
        // Usuwanie wybranych danych (okno z listą w SettingsDialogs.kt).
        add(SettingsPage.DATA, "Danger Zone", "wipe application data delete clear reset") {
            TextPreference(
                title = "Wipe Application Data",
                subtitle = "Choose what to delete: settings, presets, profiles, history, wildcards, image index",
                titleColor = MaterialTheme.colorScheme.error,
            ) {
                showWipeDataDialog = true
            }
        }

        // --- DEBUG (only after unlocking the debug mode) ---
        // Panel narzędzi testowych (DebugPanel.kt), tylko po odblokowaniu trybu debugowania.
        if (debugUnlocked) {
            add(SettingsPage.DEBUG, null, "debug tools logs raw settings test notifications") { DebugPanel(viewModel) }
        }
    }
}

// Opróżnia pamięć obrazów: w RAM-ie i na dysku telefonu.

/** Empties the image cache on the phone and in memory (3.4.0); images load again from the server. */
@OptIn(coil.annotation.ExperimentalCoilApi::class)
private fun clearImageCache(context: Context) {
    val loader = context.imageLoader
    loader.memoryCache?.clear()
    loader.diskCache?.clear()
}
