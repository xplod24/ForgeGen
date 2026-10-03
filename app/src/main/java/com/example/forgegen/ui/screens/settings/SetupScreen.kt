package com.example.forgegen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/* ============================================================================
 * USTAWIENIA: EKRAN (3.6.1, podział SetupScreen na pliki)
 *
 * Co tu jest: SetupScreen, czyli cały ekran ustawień. Sam niewiele rysuje: skleja pozycje ze wszystkich stron
 * (pliki Settings*Page.kt), liczy jednolinijkowe podsumowania kategorii i przełącza między stroną główną
 * a stroną wybranej kategorii (z krótką animacją przesunięcia). Na końcu dokłada okna dialogowe.
 *
 * Mapa plików ustawień (folder ui/screens/settings):
 * - SetupScreen.kt (ten plik): ekran, podsumowania, nawigacja wstecz,
 * - SettingsUiState.kt: wspólny stan (flagi okien, diagnostyka, blokada telefonu),
 * - SettingsParts.kt: wiersze ustawień, lista kategorii, budowanie listy,
 * - SettingsLayout.kt: wygląd strony głównej i strony kategorii,
 * - SettingsServerPage.kt, SettingsFeaturesPage.kt, SettingsAppearancePage.kt, SettingsNotificationsPage.kt,
 *   SettingsQueuePage.kt, SettingsPrivacyPage.kt, SettingsUpdatesPage.kt, SettingsDataPage.kt: strony,
 * - SettingsDialogs.kt: okna dialogowe.
 *
 * Jak to działa: każda strona to funkcja zwracająca listę pozycji (SettingItem). Kolejność sklejania niżej to
 * kolejność wyników wyszukiwania. Wybrana strona (page) i tekst wyszukiwania (query) są w rememberSaveable,
 * więc przetrwają obrót ekranu.
 *
 * Do poczytania: rememberSaveable, BackHandler (przycisk wstecz), AnimatedContent (animowana zmiana zawartości),
 * Scaffold (szkielet ekranu w Material 3), operator + na listach w Kotlinie (sklejanie list).
 * ============================================================================ */

// Ekran ustawień. [belowTopBar]: pokazany pod górnym paskiem ekranu głównego (bez dodatkowego odstępu na pasek stanu).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    viewModel: ForgeViewModel,
    onDismiss: () -> Unit,
    // Shown over the main screen, under its top bar: the status bar is already covered there. Counting it again
    // left a wide empty band above "Settings" (2.3.0).
    belowTopBar: Boolean = false,
) {
    // --- STATE OBSERVATION ---
    // Dane potrzebne stronie głównej i podsumowaniom (strony zbierają swoje same).
    val config by viewModel.config.collectAsStateWithLifecycle()
    val updateManifest by viewModel.updateManifest.collectAsStateWithLifecycle()
    val updateDownload by viewModel.updateDownload.collectAsStateWithLifecycle()
    val readyUpdate by viewModel.readyUpdate.collectAsStateWithLifecycle()
    val installingUpdate by viewModel.installingUpdate.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val pingMs by viewModel.pingMs.collectAsStateWithLifecycle()
    val debugUnlocked by viewModel.debugUnlocked.collectAsStateWithLifecycle()

    // Wspólny stan stron i okien (SettingsUiState.kt).
    val ui = rememberSettingsUiState(viewModel)

    // Otwarta strona (null = strona główna) i wyszukiwanie na stronie głównej.
    // The open page (null: the main page) and the search on the main page.
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    // Kept outside the pages, so the main page is where it was when a category page is closed.
    val homeListState = rememberLazyListState()
    // "Turn Off Debug Mode" empties the Debug page: back to the main page.
    LaunchedEffect(debugUnlocked) { if (!debugUnlocked && page == SettingsPage.DEBUG) page = null }

    // Wstecz: ze strony kategorii na główną, potem czyszczenie wyszukiwania, na końcu zamknięcie ustawień.
    // Back: a category page returns to the main page, a search is cleared, then the settings close (both as a nav
    // destination and as the overlay on MainScreen).
    BackHandler {
        when {
            page != null -> page = null
            query.isNotEmpty() -> query = ""
            else -> onDismiss()
        }
    }

    // Opis i kolor stanu połączenia (karta serwera na stronie głównej i pierwszy wiersz strony Server).
    val connectionText =
        when (connection) {
            ServerConnection.CONNECTED -> "Connected · $pingMs ms"
            ServerConnection.SEARCHING -> "Connecting..."
            ServerConnection.OFFLINE -> "Offline"
        }
    val connectionColor =
        when (connection) {
            ServerConnection.CONNECTED -> CONNECTED_GREEN
            ServerConnection.SEARCHING -> SEARCHING_AMBER
            ServerConnection.OFFLINE -> MaterialTheme.colorScheme.error
        }

    /* ==========================================================
     * EVERY SETTING, by page and group (also what the search looks through)
     * ========================================================== */

    // Wszystkie ustawienia, strona po stronie; ta kolejność to też kolejność wyników wyszukiwania.
    val settings =
        ui.serverSettings(connectionText, connectionColor) +
            ui.appearanceSettings() +
            ui.featuresSettings() +
            ui.notificationsSettings() +
            ui.queueSettings() +
            ui.privacySettings() +
            ui.updatesSettings() +
            ui.dataSettings()

    // Jednolinijkowe podsumowanie każdej kategorii na stronie głównej.
    // How each category is set, one line under its name on the main page.
    val summaries: Map<SettingsPage, String> =
        mapOf(
            SettingsPage.APPEARANCE to
                listOfNotNull(
                    when (config.themeMode) {
                        THEME_LIGHT -> "Light theme"
                        THEME_DARK -> "Dark theme"
                        else -> "System theme"
                    },
                    "grid after batch".takeIf { config.showGridAfterGeneration },
                    "tags row".takeIf { config.showActiveTagsUI },
                ).joinToString(" · "),
            SettingsPage.FEATURES to FeatureSwitches.summary(config),
            SettingsPage.NOTIFICATIONS to
                listOfNotNull(
                    "queue finish".takeIf { config.notifOnQueueFinish },
                    "batch finish".takeIf { config.notifOnBatchFinish },
                    "vibration".takeIf { config.vibrateOnFinish },
                    "${config.notificationMode} progress",
                    "Live Update".takeIf { ui.isLiveUpdateSupported && config.nowBarProgress },
                ).joinToString(" · ").replaceFirstChar { it.uppercase() },
            SettingsPage.QUEUE to
                listOfNotNull(
                    if (config.overnightMode) "Overnight mode on" else "Overnight mode off",
                    "screen stays on".takeIf { config.keepScreenOn },
                    "battery restricted".takeIf { !ui.isIgnoringBattery },
                ).joinToString(" · "),
            SettingsPage.PRIVACY to
                listOfNotNull(
                    if (config.useNativeSecurity) "App Lock on" else "App Lock off",
                    "prompts hidden".takeIf { config.hidePromptsInNotifications },
                    "screenshots blocked".takeIf { config.blockScreenshots },
                    "private saving".takeIf { config.savePrivately },
                ).joinToString(" · "),
            SettingsPage.UPDATES to
                (
                    installingUpdate?.let { "Installing $it" }
                        ?: updateDownload?.let { d -> "Downloading ${d.versionName} · ${(d.fraction * 100).toInt()}%" }
                        ?: readyUpdate
                            ?.takeIf { it.versionCode == updateManifest?.versionCode }
                            ?.let { "${it.versionName} ready to install" }
                        ?: ("${BuildConfig.VERSION_NAME} · " + if (config.autoInstallUpdates) "installs automatically" else "notifies only")
                ),
            SettingsPage.DATA to "Export, import, logs, image cache, wipe",
            SettingsPage.DEBUG to "Tools for testing the app",
        )
    // Plakietka "New" przy Updates: jest wydanie, którego karty użytkownik nie zamknął.
    val hasUpdate = updateManifest != null && updateManifest?.versionCode != ui.dismissedUpdateVersion

    // --- UI STRUCTURE ---
    Scaffold(contentWindowInsets = if (belowTopBar) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets) { padding ->
        // Animowane przejście między stroną główną a stroną kategorii.
        AnimatedContent(
            targetState = page,
            modifier = Modifier.fillMaxSize().padding(padding),
            transitionSpec = {
                // Into a category from the right, back out to the left, a short slide with a fade.
                val forward = targetState != null
                (
                    slideInHorizontally(tween(SETTINGS_PAGE_MS)) { width -> if (forward) width / 4 else -width / 4 } +
                        fadeIn(tween(SETTINGS_PAGE_MS))
                ) togetherWith
                    (
                        slideOutHorizontally(tween(SETTINGS_PAGE_MS)) { width -> if (forward) -width / 4 else width / 4 } +
                            fadeOut(tween(SETTINGS_PAGE_MS))
                    )
            },
            label = "settings_page",
        ) { shown ->
            if (shown == null) {
                SettingsHome(
                    listState = homeListState,
                    query = query,
                    onQueryChange = { query = it },
                    serverLine = connectionText,
                    serverColor = connectionColor,
                    serverAddress = config.apiUrl.removePrefix("http://").removePrefix("https://"),
                    profileCount = config.serverProfiles.size,
                    pages = SettingsPage.entries.filter { it != SettingsPage.SERVER && (it != SettingsPage.DEBUG || debugUnlocked) },
                    summaries = summaries,
                    hasUpdate = hasUpdate,
                    settings = settings,
                    onOpen = { page = it },
                )
            } else {
                SettingsPageContent(
                    page = shown,
                    settings = settings.filter { it.page == shown },
                    onBack = { page = null },
                )
            }
        }
    }

    // Okna dialogowe (SettingsDialogs.kt): rysowane tylko te, których flagi są włączone.
    ui.SettingsDialogs()
}
