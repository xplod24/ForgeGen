package com.example.forgegen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/* ============================================================================
 * USTAWIENIA: STRONA "NOTIFICATIONS" (3.6.1, podział SetupScreen na pliki)
 *
 * Co tu jest: powiadomienia aplikacji:
 * - Alerts: powiadomienie i wibracja po serii, powiadomienie po całej kolejce,
 * - Progress: tryb powiadomienia o postępie i Live Update (postęp na ekranie blokady, Android 16+),
 *   z listą kontrolną "czy to działa" (LiveUpdateChecklist).
 *
 * Do poczytania:
 * - powiadomienia w Androidzie (NotificationManager, kanały powiadomień),
 * - Live Updates / "promoted ongoing notifications" w Androidzie 16 i Now Bar Samsunga,
 * - wyrażenie when w Kotlinie (wybór jednej z wielu wartości).
 * ============================================================================ */

// Pozycje strony Notifications, w kolejności wyświetlania.
@Composable
internal fun SettingsUiState.notificationsSettings(): List<SettingItem> {
    val config by viewModel.config.collectAsStateWithLifecycle()

    return settingsOf {
        // --- NOTIFICATIONS ---
        // Powiadomienie po zakończeniu serii obrazów.
        add(SettingsPage.NOTIFICATIONS, "Alerts", "notify on batch finish notification completed alert") {
            SwitchPreference(
                title = "Notify on Batch Finish",
                subtitle = "Get alerted when a generation batch is fully completed",
                checked = config.notifOnBatchFinish,
                onCheckedChange = { viewModel.saveConfig(config.copy(notifOnBatchFinish = it)) },
            )
        }
        // Krótka wibracja po serii, gdy aplikacja jest na ekranie.
        add(SettingsPage.NOTIFICATIONS, "Alerts", "vibrate on batch finish vibration") {
            SwitchPreference(
                title = "Vibrate on Batch Finish",
                subtitle = "A short vibration when a batch is done while the app is on screen",
                checked = config.vibrateOnFinish,
                onCheckedChange = { viewModel.saveConfig(config.copy(vibrateOnFinish = it)) },
            )
        }
        // Powiadomienie po zakończeniu wszystkich zadań w kolejce.
        add(SettingsPage.NOTIFICATIONS, "Alerts", "notify on queue finish notification jobs done alert") {
            SwitchPreference(
                title = "Notify on Queue Finish",
                subtitle = "Get alerted when all queued jobs are finished",
                checked = config.notifOnQueueFinish,
                onCheckedChange = { viewModel.saveConfig(config.copy(notifOnQueueFinish = it)) },
            )
        }
        // Tryb powiadomienia o postępie: Simple, Verbose albo Disabled (okno wyboru w SettingsDialogs.kt).
        add(SettingsPage.NOTIFICATIONS, "Progress", "progress notification mode simple verbose disabled eta") {
            val modeDesc =
                when (config.notificationMode) {
                    "Disabled" -> "Only a static notice (Android requires one)"
                    "Verbose" -> "Also batch size and an Open App button"
                    else -> "Image number, progress and ETA"
                }
            TextPreference(
                title = "Progress Notification Mode",
                subtitle = "${config.notificationMode}: $modeDesc",
            ) { showNotificationModeDialog = true }
        }
        // Postęp jako Live Update (Android 16+); domyślnie wyłączone, bo Google wymaga, by użytkownik sam o to poprosił.
        add(SettingsPage.NOTIFICATIONS, "Progress", "show progress as live update now bar samsung status chip lock screen") {
            // Off until the user turns it on (Google's rules: a Live Update the user asked for); greyed out below
            // Android 16. Saved as nowBarProgress, its name up to 3.4.2.
            SwitchPreference(
                title = "Show Progress as Live Update",
                subtitle =
                    when {
                        !isLiveUpdateSupported -> "Android 16 or newer only"
                        config.notificationMode == "Disabled" -> "Needs a Progress Notification Mode other than Disabled"
                        isSamsung -> "The generation progress in the Now Bar at the bottom of the lock screen and in the status bar"
                        else -> "The generation progress as a chip in the status bar and on the lock screen"
                    },
                checked = isLiveUpdateSupported && config.nowBarProgress,
                enabled = isLiveUpdateSupported,
                onCheckedChange = {
                    if (it) {
                        LiveUpdates.forgetPromotion(context)
                        promotedLastTime = null
                    }
                    viewModel.saveConfig(config.copy(nowBarProgress = it))
                },
            )
        }
        // Lista kontrolna Live Update (tylko gdy jest włączony): co system pozwala i czy ostatnio zadziałało.
        if (isLiveUpdateSupported && config.nowBarProgress) {
            add(SettingsPage.NOTIFICATIONS, "Progress", "live update checklist did it work live notifications developer options") {
                LiveUpdateChecklist(
                    notificationsAllowed = areNotificationsAllowed,
                    liveNotificationsAllowed = isLiveUpdateAllowed,
                    promotedLastTime = promotedLastTime,
                    isSamsung = isSamsung,
                    onOpenNotificationSettings = { LiveUpdates.openNotificationSettings(context) },
                    onOpenLiveNotificationSettings = { LiveUpdates.openSystemSettings(context) },
                    onOpenDeveloperOptions = { LiveUpdates.openDeveloperOptions(context) },
                )
            }
        }
    }
}

// Panel "For Live Updates": co jest potrzebne do Live Update i przyciski do odpowiednich stron systemu.

/**
 * What Live Updates need (3.5.0): notifications allowed and live notifications allowed by the system, which the app
 * can read, and whether the system showed the last job's progress as one (LiveUpdates.notePromotion). Samsung's own
 * rule is listed with a button to the developer options, and the rest with buttons to the pages where it is changed.
 */
@Composable
private fun LiveUpdateChecklist(
    notificationsAllowed: Boolean,
    liveNotificationsAllowed: Boolean,
    promotedLastTime: Boolean?,
    isSamsung: Boolean,
    onOpenNotificationSettings: () -> Unit,
    onOpenLiveNotificationSettings: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
) {
    // Inside the Notifications card, on a tinted panel of its own.
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("For Live Updates", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            LiveUpdateCheck(notificationsAllowed, "Notifications allowed")
            LiveUpdateCheck(liveNotificationsAllowed, "Live notifications allowed by the system")
            LiveUpdateCheck(
                promotedLastTime,
                when (promotedLastTime) {
                    true -> "Shown as a Live Update: yes, during the last job"
                    false -> "Shown as a Live Update: no, the phone kept it as a normal notification"
                    null -> "Shown as a Live Update: not checked yet, start a job"
                },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (isSamsung) {
                    "Samsung also needs \"Live notifications for all apps\" turned on in the developer options (otherwise " +
                        "it shows only the apps on its own list), and ForgeGen's notifications shown on the lock screen, " +
                        "with their content."
                } else {
                    "The lock screen also needs ForgeGen's notifications shown with their content. Some phones add " +
                        "rules of their own: the line above tells whether yours showed the progress as a Live Update."
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Two buttons per row at most, so they fit on narrow phones.
            if (!liveNotificationsAllowed) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onOpenLiveNotificationSettings) { Text("Live Notifications") }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onOpenNotificationSettings) { Text("Notifications") }
                if (isSamsung) TextButton(onClick = onOpenDeveloperOptions) { Text("Developer Options") }
            }
        }
    }
}

// Jedna linia listy kontrolnej: zrobione (zielony znaczek), brak (ostrzeżenie) albo jeszcze nieznane (pytajnik).

/** One line of the checklist: done, missing, or (null) not known yet. */
@Composable
private fun LiveUpdateCheck(
    ok: Boolean?,
    text: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Icon(
            when (ok) {
                true -> Icons.Default.CheckCircle
                false -> Icons.Default.Warning
                null -> Icons.Default.HelpOutline
            },
            contentDescription =
                when (ok) {
                    true -> "Done"
                    false -> "Missing"
                    null -> "Not known yet"
                },
            tint =
                when (ok) {
                    true -> Color(0xFF2E7D32)
                    false -> MaterialTheme.colorScheme.error
                    null -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 13.sp)
    }
}
