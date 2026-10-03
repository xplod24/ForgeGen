package com.example.forgegen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* ============================================================================
 * USTAWIENIA: WSPÓLNE CEGIEŁKI (3.6.1, podział SetupScreen na pliki)
 *
 * Co tu jest: elementy, z których zbudowane są wszystkie strony ustawień:
 * - SwitchPreference i TextPreference: jeden wiersz ustawienia (tytuł, opis, przełącznik albo strzałka),
 * - SettingsPage: lista kategorii (Server, Features, Appearance, ...) z ikoną i kolorem,
 * - SettingItem: jedno ustawienie na liście, z kategorią, grupą i słowami, po których szuka wyszukiwarka,
 * - SettingsList i settingsOf: mały "pomocnik" do budowania listy ustawień jednej strony.
 *
 * Jak to działa: każda strona ustawień (pliki Settings*Page.kt) zwraca listę SettingItem. SetupScreen skleja te listy
 * w jedną; strona kategorii pokazuje tylko swoje pozycje, a wyszukiwarka przegląda wszystkie.
 *
 * Do poczytania (Kotlin i Jetpack Compose):
 * - funkcja @Composable: funkcja, która "rysuje" kawałek ekranu; Compose wywołuje ją ponownie, gdy zmienią się
 *   dane, które czyta (tzw. rekompozycja),
 * - lambda (np. `{ ... }` przekazane jako parametr) i lambda z odbiorcą (`SettingsList.() -> Unit`),
 * - enum class, data/zwykła class, modyfikatory widoczności `private` (tylko ten plik) i `internal` (cała aplikacja),
 * - Modifier w Compose (padding, fillMaxWidth, clickable, ...): łańcuch ustawień wyglądu i zachowania elementu.
 * ============================================================================ */

// SETTINGS ROWS: a setting is a row inside a rounded card: title, a short explanation, and a switch or an arrow.

// Wiersz z przełącznikiem (włącz/wyłącz). Stuknięcie w cały wiersz przełącza wartość i woła onCheckedChange.
@Composable
fun SwitchPreference(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { onCheckedChange(!checked) }
                .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).alpha(if (enabled) 1f else 0.5f)) {
            Text(text = title, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    lineHeight = 17.sp,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
        )
    }
}

// Wiersz, który coś otwiera (okno, stronę systemu) albo od razu działa; domyślnie ze strzałką po prawej.

/** A row that opens something (a dialog, a system page) or acts at once; [trailing] replaces the arrow. */
@Composable
fun TextPreference(
    title: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    titleColor: Color = Color.Unspecified,
    trailing: (@Composable () -> Unit)? = { RowArrow() },
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).alpha(if (enabled) 1f else 0.5f)) {
            Text(
                text = title,
                fontSize = 16.sp,
                color = if (titleColor == Color.Unspecified) MaterialTheme.colorScheme.onSurface else titleColor,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    lineHeight = 17.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

// Szara strzałka "dalej" na końcu wiersza.
@Composable
internal fun RowArrow() {
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
    )
}

/* ============================================================================
 * SETTINGS PAGES (2.3.0, the owner's pick "B")
 * The main page shows the server and seven categories, each with a line on how it is set; a category opens its own
 * page of cards. The search on the main page shows the matching settings themselves, from every page.
 * ============================================================================ */

// Kategorie ustawień: nazwa, ikona i kolor kafelka. Kolejność tutaj = kolejność na stronie głównej ustawień.
internal enum class SettingsPage(
    val title: String,
    val icon: ImageVector,
    val tint: Color,
) {
    SERVER("Server", Icons.Default.Dns, Color(0xFF3E80FF)),
    FEATURES("Features", Icons.Default.ToggleOn, Color(0xFFEC407A)),
    APPEARANCE("Appearance", Icons.Default.Palette, Color(0xFFA87BFF)),
    NOTIFICATIONS("Notifications", Icons.Default.Notifications, Color(0xFFFFA726)),
    QUEUE("Queue & Background", Icons.Default.Bedtime, Color(0xFF26C6DA)),
    PRIVACY("Privacy & Security", Icons.Default.Shield, Color(0xFF66BB6A)),
    UPDATES("Updates", Icons.Default.SystemUpdate, Color(0xFF42A5F5)),
    DATA("Backup & Data", Icons.Default.Storage, Color(0xFF9E9E9E)),
    DEBUG("Debug", Icons.Default.BugReport, Color(0xFFEF5350)),
}

// Jedno ustawienie na liście: do jakiej kategorii i grupy (nagłówka) należy, jakie słowa znajdzie wyszukiwarka
// i co narysować (content: funkcja @Composable wywoływana dopiero, gdy pozycja jest widoczna).

/**
 * One setting (or a block of its page, e.g. the Live Update checklist) in its [page] and [group], with the [words] the
 * search looks through (its title and explanation, plus other words people may look for).
 */
internal class SettingItem(
    val page: SettingsPage,
    val group: String?,
    val words: String,
    val content: @Composable () -> Unit,
)

// Czy ustawienie pasuje do wyszukiwania: każde wpisane słowo musi wystąpić w jego słowach (bez wielkości liter).

/** Every word of [query] appears in the item's words (ignoring case). */
internal fun SettingItem.matches(query: String): Boolean {
    val text = words.lowercase()
    return query
        .lowercase()
        .split(' ')
        .filter { it.isNotBlank() }
        .all { it in text }
}

// Lista ustawień jednej strony w budowie: add(...) dopisuje kolejną pozycję na koniec (kolejność ma znaczenie).

/** The settings of a page as they are added, in the order they show. */
internal class SettingsList {
    val collected = mutableListOf<SettingItem>()

    fun add(
        page: SettingsPage,
        group: String?,
        words: String,
        content: @Composable () -> Unit,
    ) {
        collected += SettingItem(page, group, words, content)
    }
}

// Buduje listę ustawień: wewnątrz klamer można wołać add(...) (to tzw. lambda z odbiorcą SettingsList).

/** The settings [build] adds, in their order. */
internal inline fun settingsOf(build: SettingsList.() -> Unit): List<SettingItem> = SettingsList().apply(build).collected

// Nazwa sekcji z wydaniem do pobrania na stronie Updates (pod wersją aplikacji i jej ustawieniami).
// Settings > Updates: the section of the release on offer, below the app's own version and settings.
internal const val NEW_VERSION = "New Version"

// Kolory kropki połączenia: zielony = połączono, bursztynowy = łączenie.
internal val CONNECTED_GREEN = Color(0xFF4CAF50)
internal val SEARCHING_AMBER = Color(0xFFFFB300)

// Jak długo (ms) trwa przejście do strony kategorii i z powrotem.

/** Into a category page and back: a short slide with a fade. */
internal const val SETTINGS_PAGE_MS = 250
