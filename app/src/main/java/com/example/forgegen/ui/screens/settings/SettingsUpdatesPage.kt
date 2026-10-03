package com.example.forgegen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.forgegen.ui.components.UpdateCard

/* ============================================================================
 * USTAWIENIA: STRONA "UPDATES" (3.6.1, podział SetupScreen na pliki)
 *
 * Co tu jest: wersja aplikacji (8 szybkich stuknięć + hasło włącza tryb debugowania), licencja, automatyczna
 * instalacja aktualizacji, "Check for Updates" oraz sekcja "New Version" z wydaniem do pobrania i zainstalowania.
 *
 * Jak to działa: aplikacja sama pyta GitHuba raz dziennie (przy starcie albo w tle, co pierwsze; bez połączenia
 * następna próba jest dopiero nazajutrz) i o każdej nowej wersji daje jedno powiadomienie. Aktualizacja ma dwa
 * kroki: "Download" (pobiera plik w tle i sprawdza jego sumę SHA-256), potem "Install" (aplikacja schodzi na bok,
 * a Android ją podmienia). W trakcie instalacji karta pokazuje "Installing" bez przycisku, żeby nie dało się
 * uruchomić instalacji dwa razy.
 *
 * Do poczytania: PackageInstaller w Androidzie (instalacja aplikacji przez samą aplikację), suma kontrolna SHA-256,
 * GitHub Releases (skąd przychodzą wydania), android.os.SystemClock.elapsedRealtime (zegar do mierzenia odstępów),
 * JobScheduler (zadania w tle według harmonogramu), SharedPreferences (małe dane zapisane w telefonie).
 * ============================================================================ */

// Pozycje strony Updates, w kolejności wyświetlania.
@Composable
internal fun SettingsUiState.updatesSettings(): List<SettingItem> {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val updateManifest by viewModel.updateManifest.collectAsStateWithLifecycle()
    val updateDownload by viewModel.updateDownload.collectAsStateWithLifecycle()
    val readyUpdate by viewModel.readyUpdate.collectAsStateWithLifecycle()
    val installingUpdate by viewModel.installingUpdate.collectAsStateWithLifecycle()
    val updateConfirm by viewModel.updateConfirm.collectAsStateWithLifecycle()
    val queueActive by viewModel.isQueueActive.collectAsStateWithLifecycle()
    val generating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val debugUnlocked by viewModel.debugUnlocked.collectAsStateWithLifecycle()

    return settingsOf {
        // --- UPDATES ---
        // Wersja aplikacji. Ukryte: 8 stuknięć w krótkich odstępach otwiera okno hasła trybu debugowania.
        add(SettingsPage.UPDATES, null, "app version build number") {
            TextPreference(
                title = "App Version",
                subtitle = "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                trailing = null,
            ) {
                val now = android.os.SystemClock.elapsedRealtime()
                versionTaps = if (now - lastVersionTap <= DebugMode.TAP_WINDOW_MS) versionTaps + 1 else 1
                lastVersionTap = now
                if (versionTaps >= DebugMode.TAPS_TO_UNLOCK) {
                    versionTaps = 0
                    if (debugUnlocked) viewModel.showToast("Debug mode is already on") else showDebugPasswordDialog = true
                }
            }
        }
        // Licencja (GNU GPL v3 lub nowsza) i autor; stuknięcie otwiera pełny tekst.
        add(SettingsPage.UPDATES, null, "license gpl gnu free software source code copyright author") {
            TextPreference(
                title = "License",
                subtitle = "${AppLicense.NAME} · © 2026 ${AppLicense.AUTHOR}",
            ) { showLicenseDialog = true }
        }
        // Automatyczna instalacja aktualizacji w tle. Od 3.6.1 domyślnie wyłączona: wtedy tylko jedno powiadomienie
        // o każdej nowej wersji.
        add(SettingsPage.UPDATES, null, "install updates automatically background wi-fi notification") {
            SwitchPreference(
                title = "Install Updates Automatically",
                subtitle =
                    "Installs a new release in the background on Wi-Fi (never while the queue works); " +
                        "off: one notification for each new version",
                checked = config.autoInstallUpdates,
                onCheckedChange = { viewModel.saveConfig(config.copy(autoInstallUpdates = it)) },
            )
        }
        // Ręczne sprawdzenie, czy na GitHubie jest nowsze wydanie (pokazuje też zamkniętą wcześniej kartę).
        // Samo z siebie aplikacja sprawdza raz dziennie (SelfUpdate.claimDailyCheck); to sprawdzenie jest dodatkowe.
        add(SettingsPage.UPDATES, null, "check for updates github release daily") {
            TextPreference(
                title = "Check for Updates",
                subtitle = "Look for a newer release on GitHub now; the app also looks once a day by itself",
                trailing = { Icon(Icons.Default.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            ) {
                dismissedUpdateVersion = -1
                viewModel.checkForUpdates(manual = true)
            }
        }
        // The release on offer, its download and its install: a section of their own below (2026-10-01).
        // Sekcja "New Version": najpierw karta "Installing" (gdy instalacja trwa), potem karta wydania.
        val download = updateDownload
        val installing = installingUpdate
        if (installing != null) {
            // "Install" was tapped: the app went to the background and Android replaces it. No button here, so it
            // cannot be started twice (3.0.0-3).
            add(SettingsPage.UPDATES, NEW_VERSION, "update installing $installing") {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                            .padding(16.dp),
                ) {
                    Text("Installing $installing", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    val confirm = updateConfirm
                    Text(
                        if (confirm != null) {
                            "Android wants you to confirm the install this time."
                        } else {
                            "Android replaces ForgeGen now; a notification says when it is done."
                        },
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                    // Android chce tym razem potwierdzenia: przycisk otwiera jego okno.
                    if (confirm != null) {
                        Spacer(Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Button(onClick = {
                                try {
                                    context.startActivity(confirm)
                                } catch (e: Exception) {
                                    viewModel.showToast("Cannot open the confirmation: ${e.message}")
                                }
                            }) { Text("Confirm Install") }
                        }
                    }
                }
            }
        }
        val manifest = updateManifest
        // Also while it downloads: the notes stay and the progress slides out below them (UpdateCard).
        // Karta wydania: informacje o nowościach, "Download", a po sprawdzeniu pliku "Install".
        val offerUpdate = installing == null && manifest != null
        if (offerUpdate && manifest != null && (manifest.versionCode != dismissedUpdateVersion || download != null)) {
            val ready = readyUpdate?.takeIf { it.versionCode == manifest.versionCode }
            add(SettingsPage.UPDATES, NEW_VERSION, "update available download install new version ${manifest.versionName}") {
                // Two steps (3.0.0-3): "Download" runs in UpdateDownloadService with its progress here, in the
                // notifications and a Live Update, and the app stays open; once the file matches the release,
                // "Install" sends the app to the background and Android replaces it.
                UpdateCard(
                    manifest = manifest,
                    ready = ready,
                    download = download,
                    onDismiss = { dismissedUpdateVersion = manifest.versionCode },
                    onDownload = { viewModel.downloadUpdate() },
                    onInstall = { if (queueActive || generating) confirmInstallDuringQueue = true else installNow() },
                    onShowAll = { showAllReleaseNotes = true },
                )
            }
        }
    }
}
