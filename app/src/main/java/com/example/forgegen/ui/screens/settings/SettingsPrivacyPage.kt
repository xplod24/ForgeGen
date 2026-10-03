package com.example.forgegen

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/* ============================================================================
 * USTAWIENIA: STRONA "PRIVACY & SECURITY" (3.6.1, podział SetupScreen na pliki)
 *
 * Co tu jest:
 * - Privacy: prompty ukryte w powiadomieniach, aplikacja ukryta w ostatnich aplikacjach, blokada zrzutów ekranu,
 *   prywatny zapis obrazów i udostępnianie bez danych generowania,
 * - App Lock: blokada aplikacji PIN-em/wzorem/hasłem telefonu i biometria.
 *
 * Jak to działa: włączenie i wyłączenie App Lock wymaga potwierdzenia blokadą telefonu (confirmWithPhoneLock
 * w SettingsUiState.kt), żeby nikt z odblokowanym telefonem nie wyłączył jej jednym stuknięciem.
 *
 * Do poczytania: BiometricPrompt i KeyguardManager w Androidzie (uwierzytelnianie użytkownika),
 * FLAG_SECURE (blokada zrzutów ekranu).
 * ============================================================================ */

// Pozycje strony Privacy & Security, w kolejności wyświetlania.
@Composable
internal fun SettingsUiState.privacySettings(): List<SettingItem> {
    val config by viewModel.config.collectAsStateWithLifecycle()

    return settingsOf {
        // --- PRIVACY & SECURITY ---
        // Powiadomienia o serii bez promptu (na ekranie blokady i tak go nie ma).
        add(SettingsPage.PRIVACY, "Privacy", "hide prompts in notifications privacy") {
            SwitchPreference(
                title = "Hide Prompts in Notifications",
                subtitle = "Finished-batch notifications do not show the prompt (never on the lock screen anyway)",
                checked = config.hidePromptsInNotifications,
                onCheckedChange = { viewModel.saveConfig(config.copy(hidePromptsInNotifications = it)) },
            )
        }
        // Bez podglądu aplikacji na ekranie ostatnich aplikacji (zawsze włączone przy App Lock).
        add(SettingsPage.PRIVACY, "Privacy", "hide app in recents recent apps screen preview") {
            SwitchPreference(
                title = "Hide App in Recents",
                subtitle =
                    if (config.useNativeSecurity) {
                        "Always on while the App Lock is on"
                    } else {
                        "The recent apps screen shows no picture of the app (Android 13 and newer)"
                    },
                checked = config.hideInRecents || config.useNativeSecurity,
                enabled = !config.useNativeSecurity,
                onCheckedChange = { viewModel.saveConfig(config.copy(hideInRecents = it)) },
            )
        }
        // Blokada zrzutów i nagrywania ekranu.
        add(SettingsPage.PRIVACY, "Privacy", "block screenshots screen recording") {
            SwitchPreference(
                title = "Block Screenshots",
                subtitle = "No screenshots or screen recordings of the app",
                checked = config.blockScreenshots,
                onCheckedChange = { viewModel.saveConfig(config.copy(blockScreenshots = it)) },
            )
        }
        // Zapis obrazów do prywatnego folderu aplikacji zamiast do galerii telefonu.
        add(SettingsPage.PRIVACY, "Privacy", "save to phone privately private folder gallery cloud backup") {
            SwitchPreference(
                title = "Save to Phone Privately",
                subtitle =
                    "Saved images go to the app's own folder instead of the phone's gallery, so gallery apps " +
                        "and their cloud backup do not see them. They are deleted when the app is uninstalled.",
                checked = config.savePrivately,
                onCheckedChange = { viewModel.saveConfig(config.copy(savePrivately = it)) },
            )
        }
        // Udostępnianie obrazów bez promptu, seeda i modelu.
        add(SettingsPage.PRIVACY, "Privacy", "share without generation data metadata prompt seed model") {
            SwitchPreference(
                title = "Share Without Generation Data",
                subtitle = "Shared images leave without their prompt, seed and model",
                checked = config.shareWithoutMetadata,
                onCheckedChange = { viewModel.saveConfig(config.copy(shareWithoutMetadata = it)) },
            )
        }
        // Ostrzeżenie: telefon nie ma blokady ekranu, więc App Lock nie da się włączyć.
        if (!isDeviceSecure) {
            add(SettingsPage.PRIVACY, "App Lock", "app lock security pin pattern password") {
                Text(
                    text =
                        "Your device does not have a PIN, Pattern, or Password set. Please set a lock in your device " +
                            "settings to enable App Security.",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
        // App Lock: przy otwieraniu aplikacji pyta o PIN, wzór lub hasło telefonu.
        add(SettingsPage.PRIVACY, "App Lock", "app lock security pin pattern password unlock") {
            SwitchPreference(
                title = "App Lock",
                subtitle = "Ask for the phone's PIN, pattern or password when opening the app",
                checked = config.useNativeSecurity,
                // Stays switchable while on, so it can be turned off after the phone lock was removed.
                enabled = isDeviceSecure || config.useNativeSecurity,
                onCheckedChange = { enable ->
                    // Both directions need the phone's lock: off, so whoever holds the unlocked phone cannot just
                    // switch it off; on, so nobody enables a lock they are unable to open.
                    confirmWithPhoneLock(if (enable) "Turn on the app lock" else "Turn off the app lock") {
                        viewModel.markUnlocked()
                        viewModel.saveConfig(
                            config.copy(useNativeSecurity = enable, useBiometricLock = enable && config.useBiometricLock),
                        )
                    }
                },
            )
        }
        // Odblokowanie odciskiem palca lub twarzą (PIN dalej działa).
        add(SettingsPage.PRIVACY, "App Lock", "allow biometrics fingerprint face unlock") {
            SwitchPreference(
                title = "Allow Biometrics",
                subtitle = "Also unlock with fingerprint or face (the PIN keeps working)",
                checked = config.useBiometricLock,
                enabled = config.useNativeSecurity && isDeviceSecure,
                onCheckedChange = { allow ->
                    confirmWithPhoneLock(if (allow) "Allow biometric unlock" else "Turn off biometric unlock") {
                        viewModel.saveConfig(config.copy(useBiometricLock = allow))
                    }
                },
            )
        }
    }
}
