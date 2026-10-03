package com.example.forgegen

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/* ============================================================================
 * USTAWIENIA: STRONA "QUEUE & BACKGROUND" (3.6.1, podział SetupScreen na pliki)
 *
 * Co tu jest: jak kolejka pracuje w tle: tryb nocny (błędne zadanie odkładane na bok, utracone połączenie
 * ponawiane), ekran włączony podczas generowania i zdjęcie ograniczeń baterii.
 *
 * Do poczytania:
 * - Intent w Androidzie: "prośba" o otwarcie innego ekranu (tu: systemowego okna optymalizacji baterii),
 * - optymalizacja baterii (Doze) i ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
 * - try/catch: obsługa błędu, gdy telefon nie ma danego ekranu systemowego.
 * ============================================================================ */

// Pozycje strony Queue & Background, w kolejności wyświetlania.
@Composable
internal fun SettingsUiState.queueSettings(): List<SettingItem> {
    val config by viewModel.config.collectAsStateWithLifecycle()

    return settingsOf {
        // --- QUEUE & BACKGROUND ---
        // Tryb nocny: błędne zadanie idzie na bok, kolejka jedzie dalej, połączenie jest ponawiane do skutku.
        add(SettingsPage.QUEUE, null, "overnight batch mode failed jobs night queue connection retry") {
            SwitchPreference(
                title = "Overnight Batch Mode",
                subtitle =
                    "A failed job is set aside and the queue goes on; a lost connection is retried until " +
                        "the server is back. A summary at the end tells what failed.",
                checked = config.overnightMode,
                onCheckedChange = { viewModel.saveConfig(config.copy(overnightMode = it)) },
            )
        }
        // Ekran nie gaśnie, gdy aplikacja jest otwarta i generuje.
        add(SettingsPage.QUEUE, null, "keep screen on awake display generating") {
            SwitchPreference(
                title = "Keep Screen On",
                subtitle = "Keep the screen on while images are being generated and the app is open",
                checked = config.keepScreenOn,
                onCheckedChange = { viewModel.saveConfig(config.copy(keepScreenOn = it)) },
            )
        }
        // Zdjęcie ograniczeń baterii (widoczne tylko, gdy system je nakłada).
        if (!isIgnoringBattery) {
            add(SettingsPage.QUEUE, null, "remove battery restrictions optimization screen off background") {
                TextPreference(
                    title = "Remove Battery Restrictions",
                    subtitle = "Let the queue run with the screen off (recommended for Overnight Batch Mode)",
                ) {
                    // Asks for this app directly; the list of all apps is the fallback where the dialog is missing.
                    try {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
                        )
                    } catch (e: android.content.ActivityNotFoundException) {
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }
            }
        }
    }
}
