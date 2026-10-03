package com.example.forgegen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* ============================================================================
 * USTAWIENIA: UKŁAD EKRANU (3.6.1, podział SetupScreen na pliki)
 *
 * Co tu jest: jak wyglądają strony ustawień, niezależnie od tego, jakie ustawienia na nich są:
 * - SettingsHome: strona główna (tytuł, wyszukiwarka, karta serwera, lista kategorii albo wyniki wyszukiwania),
 * - SettingsPageContent: strona jednej kategorii (strzałka wstecz, nazwa, grupy ustawień w kartach),
 * - SectionLabel, SettingsCard, CategoryIcon, CategoryRow, ServerCard: mniejsze kawałki tych stron.
 *
 * Do poczytania:
 * - LazyColumn: przewijana lista, która rysuje tylko widoczne pozycje (item { ... } = jedna pozycja),
 * - Surface, Row, Column, Box, Spacer: podstawowe "klocki" układu w Compose,
 * - MaterialTheme.colorScheme: kolory motywu (jasny/ciemny) z Material 3,
 * - groupBy / filter / map: funkcje kolekcji w Kotlinie (dzielenie, wybieranie i przekształcanie list).
 * ============================================================================ */

// Strona główna ustawień. Bez wyszukiwania: karta serwera + kategorie z jednolinijkowym podsumowaniem.
// Z wyszukiwaniem: same pasujące ustawienia, pogrupowane pod nazwami stron, na których są.

/** The main page: the search, the server, the categories; while searching, the matching settings themselves. */
@Composable
internal fun SettingsHome(
    listState: LazyListState,
    query: String,
    onQueryChange: (String) -> Unit,
    serverLine: String,
    serverColor: Color,
    serverAddress: String,
    profileCount: Int,
    pages: List<SettingsPage>,
    summaries: Map<SettingsPage, String>,
    hasUpdate: Boolean,
    settings: List<SettingItem>,
    onOpen: (SettingsPage) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    // Below the list the generation sheet's handle covers the screen's bottom edge.
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        item {
            Text(
                "Settings",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
            )
        }
        // Pole wyszukiwania; klawisz "szukaj" na klawiaturze tylko ją chowa (wyniki pokazują się od razu).
        item {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("Search settings") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Default.Close, "Clear Search") }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors =
                    TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
        if (query.isBlank()) {
            item {
                ServerCard(
                    address = serverAddress,
                    line = serverLine,
                    color = serverColor,
                    profileCount = profileCount,
                    onClick = { onOpen(SettingsPage.SERVER) },
                )
            }
            item {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    SettingsCard(
                        pages.map { category ->
                            {
                                CategoryRow(
                                    page = category,
                                    summary = summaries[category].orEmpty(),
                                    badge = category == SettingsPage.UPDATES && hasUpdate,
                                    onClick = { onOpen(category) },
                                )
                            }
                        },
                    )
                }
            }
            item {
                Text(
                    "ForgeGen ${BuildConfig.VERSION_NAME}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                )
            }
        } else {
            val found = settings.filter { it.matches(query) }
            if (found.isEmpty()) {
                item {
                    Text(
                        "No settings match \"${query.trim()}\"",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                    )
                }
            }
            // The real settings, under the name of the page each one is on.
            found.groupBy { it.page }.forEach { (resultPage, results) ->
                item(key = resultPage.name) {
                    Column {
                        SectionLabel(resultPage.title)
                        SettingsCard(results.map { it.content })
                    }
                }
            }
        }
    }
}

// Strona jednej kategorii. Kolejne ustawienia z tą samą grupą trafiają do jednej karty pod wspólnym nagłówkiem.

/** A category's own page: a back arrow with its name, then its groups of settings, each group a card. */
@Composable
internal fun SettingsPageContent(
    page: SettingsPage,
    settings: List<SettingItem>,
    onBack: () -> Unit,
) {
    val groups = mutableListOf<Pair<String?, MutableList<SettingItem>>>()
    settings.forEach { setting ->
        val last = groups.lastOrNull()
        if (last != null && last.first == setting.group) last.second += setting else groups += setting.group to mutableListOf(setting)
    }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to Settings") }
                Spacer(Modifier.width(4.dp))
                Text(page.title, style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp))
            }
        }
        groups.forEachIndexed { index, (group, items) ->
            item(key = "group$index") {
                Column {
                    if (group != null) SectionLabel(group) else Spacer(Modifier.height(8.dp))
                    SettingsCard(items.map { it.content })
                }
            }
        }
    }
}

// Nagłówek grupy (niebieskie wersaliki nad kartą).

/** A group's name above its card. */
@Composable
internal fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        color = MaterialTheme.colorScheme.primary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(start = 32.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

// Karta z zaokrąglonymi rogami; wiersze w środku oddzielone cienkimi liniami.

/** Rows in one rounded card, with thin lines between them. */
@Composable
internal fun SettingsCard(rows: List<@Composable () -> Unit>) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column {
            rows.forEachIndexed { index, row ->
                if (index >
                    0
                ) {
                    HorizontalDivider(modifier = Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                }
                row()
            }
        }
    }
}

// Kolorowa ikona kategorii na kafelku w jej kolorze.

/** A category's coloured icon on a tile of its colour. */
@Composable
internal fun CategoryIcon(
    page: SettingsPage,
    size: Dp,
) {
    Box(
        modifier = Modifier.size(size).clip(RoundedCornerShape(size * 0.35f)).background(page.tint.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(page.icon, contentDescription = null, tint = page.tint, modifier = Modifier.size(size * 0.55f))
    }
}

// Wiersz kategorii na stronie głównej: ikona, nazwa, podsumowanie i (dla Updates) plakietka "New".
@Composable
internal fun CategoryRow(
    page: SettingsPage,
    summary: String,
    badge: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryIcon(page, 40.dp)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(page.title, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(
                summary,
                fontSize = 13.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (badge) {
            Text(
                "New",
                color = MaterialTheme.colorScheme.onPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier =
                    Modifier
                        .padding(start = 8.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        RowArrow()
    }
}

// Karta serwera u góry strony głównej: adres, stan połączenia (kropka) i liczba zapisanych profili.

/** The server at the top of the main page: its address, how the connection goes, and the saved profiles. */
@Composable
internal fun ServerCard(
    address: String,
    line: String,
    color: Color,
    profileCount: Int,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CategoryIcon(SettingsPage.SERVER, 48.dp)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(address, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "$line · $profileCount ${if (profileCount == 1) "profile" else "profiles"}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            RowArrow()
        }
    }
}
