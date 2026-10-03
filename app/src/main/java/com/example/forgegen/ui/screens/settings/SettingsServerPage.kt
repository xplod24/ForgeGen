package com.example.forgegen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.forgegen.ui.components.RESTART_NEEDS_FLAG_HINT
import com.example.forgegen.ui.components.UnloadAfterQueueChoice
import kotlinx.coroutines.delay
import java.util.Locale

/* ============================================================================
 * USTAWIENIA: STRONA "SERVER" (3.6.1, podział SetupScreen na pliki)
 *
 * Co tu jest: wszystko o serwerze Forge, w grupach:
 * - Connection: stan połączenia, adres, profile serwerów, limit czasu, diagnostyka,
 * - Gallery: klucz galerii (rozszerzenie Infinite Image Browsing na serwerze),
 * - Server Info / Server Health / Forge Errors / Start and Packages: raport Forge po "Check Now" (3.6.0),
 * - Extensions: rozszerzenia serwera,
 * - Control: zwalnianie modelu po kolejce i restart Forge.
 *
 * Jak to działa: serverSettings() zbiera aktualne dane (collectAsStateWithLifecycle) i zwraca listę pozycji.
 * Część pozycji pojawia się tylko, gdy są dane (np. raport po "Check Now"). Okna otwierane stąd są w
 * SettingsDialogs.kt (np. showUrlDialog = true otwiera okno adresu serwera).
 *
 * Do poczytania:
 * - StateFlow i collectAsStateWithLifecycle: strumień wartości z ViewModelu, czytany przez ekran tylko wtedy,
 *   gdy ekran jest widoczny,
 * - LaunchedEffect: kod uruchamiany w tle, gdy element pojawia się na ekranie (lub zmieni się jego klucz),
 * - produceState: wartość, która sama się odświeża (tu: zegar "2 hours ago"),
 * - operatory ?. ?: i let (bezpieczna praca z wartościami, które mogą być null).
 * ============================================================================ */

// Pozycje strony Server, w kolejności wyświetlania. [connectionText] i [connectionColor] liczy SetupScreen
// (to samo pokazuje karta serwera na stronie głównej).
@Composable
internal fun SettingsUiState.serverSettings(
    connectionText: String,
    connectionColor: Color,
): List<SettingItem> {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    // The server page (3.3.0): what Forge tells about itself, and restarting it.
    val serverInfo by viewModel.serverInfo.collectAsStateWithLifecycle()
    val restartingSince by viewModel.restartingSince.collectAsStateWithLifecycle()
    // Check Now (3.6.0): Forge's report on demand, kept for each server.
    val serverCheck by viewModel.lastServerCheck.collectAsStateWithLifecycle()
    val checkingSince by viewModel.checkingSince.collectAsStateWithLifecycle()
    val checkProblem by viewModel.checkProblem.collectAsStateWithLifecycle()
    val galleryExtension by viewModel.galleryExtension.collectAsStateWithLifecycle()

    return settingsOf {
        // --- SERVER ---
        // Stan połączenia (kropka i opis); gdy nie połączono, przycisk "Retry" próbuje od nowa.
        add(SettingsPage.SERVER, "Connection", "server connection status connected offline retry ping $connectionText") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(connectionColor))
                Spacer(Modifier.width(10.dp))
                Text(connectionText, fontSize = 16.sp, modifier = Modifier.weight(1f))
                if (connection != ServerConnection.CONNECTED) {
                    FilledTonalButton(onClick = { viewModel.reconnect() }) { Text("Retry") }
                }
            }
        }
        // Adres serwera Forge; stuknięcie otwiera okno do wpisania nowego.
        add(SettingsPage.SERVER, "Connection", "server address api url ip host ${config.apiUrl}") {
            TextPreference(title = "Server Address", subtitle = config.apiUrl) { showUrlDialog = true }
        }
        // Zapisane serwery (profile): przełączanie między nimi i zapis obecnego.
        add(SettingsPage.SERVER, "Connection", "server profiles switch add saved servers") {
            val count = config.serverProfiles.size
            TextPreference(
                title = "Server Profiles",
                subtitle = "$count saved · switch between servers or save this one",
            ) { showProfilesDialog = true }
        }
        // Ile sekund czekać na zwykłe odpowiedzi serwera (generowanie ma osobny, 2-godzinny limit).
        add(SettingsPage.SERVER, "Connection", "connection timeout seconds requests slow") {
            TextPreference(
                title = "Connection Timeout",
                subtitle = "${config.timeout} s for ordinary requests (generating has its own 2 h limit)",
            ) { showTimeoutDialog = true }
        }
        // Diagnostyka: sprawdza, jak szybko odpowiada 7 adresów API (wynik w oknie).
        add(SettingsPage.SERVER, "Connection", "diagnostics test endpoints check server api") {
            TextPreference(
                title = "Diagnostics",
                subtitle = "Asks 7 of the server's endpoints how fast they answer",
            ) { runDiagnostics() }
        }
        // Klucz galerii: tajny klucz rozszerzenia IIB na serwerze, gdy galeria o niego prosi (3.5.0).
        // The secret key of the server's Infinite Image Browsing (3.5.0), for a gallery that asks for one.
        add(SettingsPage.SERVER, "Gallery", "gallery key iib secret key infinite image browsing locked password") {
            val saved = GalleryKey.savedFor(config) != null
            TextPreference(
                title = "Gallery Key",
                subtitle =
                    when {
                        galleryExtension.state == ForgeGalleryManager.Extension.LOCKED ->
                            if (saved) {
                                "The saved key no longer opens the gallery: tap to enter the new one"
                            } else {
                                "Needed: the gallery asks for its secret key"
                            }
                        galleryExtension.state == ForgeGalleryManager.Extension.KEY_NOT_SET ->
                            "Forge has a login, so IIB_SECRET_KEY must first be set on the server"
                        saved -> "Saved for this server (only its fingerprint) · tap to change or remove"
                        else -> "Not needed by this server"
                    },
            ) { showGalleryKeyDialog = true }
        }

        // Informacje o serwerze: flagi i rozszerzenia czytane, gdy strona się pokazuje; pełny raport Forge tylko
        // po "Check Now", a ostatni raport jest pamiętany dla każdego serwera.
        // What Forge tells about itself (3.3.0, board 2C). Its flags and extensions are read when the page shows;
        // its report only on "Check Now" (3.6.0, boards 4 and 5), and the last check is kept for each server.
        val info = serverInfo
        val check = serverCheck
        // Wiersz "Check Now": kiedy było ostatnie sprawdzenie, albo ile trwa obecne.
        add(SettingsPage.SERVER, "Server Info", "check now server info forge report last check sysinfo ${check?.version.orEmpty()}") {
            LaunchedEffect(connection) { if (connection == ServerConnection.CONNECTED) viewModel.loadServerInfo() }
            LaunchedEffect(config.apiUrl) { viewModel.loadServerCheck() }
            ServerCheckRow(
                check = check,
                checkingSince = checkingSince,
                problem = checkProblem,
                connected = connection == ServerConnection.CONNECTED,
                onCheck = viewModel::checkServer,
            )
        }
        // Poniższe pozycje są tylko wtedy, gdy jest raport z "Check Now".
        if (check != null) {
            // Wersja Forge i czas jego startu.
            add(SettingsPage.SERVER, "Server Info", "forge version startup ${check.version.orEmpty()}") {
                val started = check.startupSeconds?.let { " · started in ${"%.1f".format(Locale.US, it)} s" }.orEmpty()
                TextPreference(
                    title = "Forge",
                    subtitle = (check.version?.let { "Version $it" } ?: "Not reported") + started,
                    trailing = null,
                ) {}
            }
            // Karta graficzna i jej pamięć (VRAM).
            add(SettingsPage.SERVER, "Server Info", "gpu graphics card vram cuda ${check.gpu.orEmpty()}") {
                val vram = check.vramTotalGb?.let { " · ${"%.0f".format(Locale.US, it)} GB" }.orEmpty()
                TextPreference(title = "GPU", subtitle = (check.gpu ?: "Not reported") + vram, trailing = null) {}
            }
            // Procesor komputera z serwerem (jeśli raport go podał).
            check.cpu?.let { cpu ->
                add(SettingsPage.SERVER, "Server Info", "processor cpu cores threads $cpu") {
                    TextPreference(title = "Processor", subtitle = cpu, trailing = null) {}
                }
            }
            // Pamięć RAM całego komputera (miernik na ekranie głównym pokazuje część Forge).
            if (check.ramTotalGb != null) {
                add(SettingsPage.SERVER, "Server Info", "computer memory ram whole") {
                    val used = check.ramUsedGb?.let { "${ServerMemory.gb(it)} of " }.orEmpty()
                    TextPreference(
                        title = "Computer Memory",
                        subtitle =
                            "$used${ServerMemory.gb(check.ramTotalGb)} GB\n" +
                                "The whole computer; the meter on the main screen shows Forge's share",
                        trailing = null,
                    ) {}
                }
            }
            // System, Python, Torch.
            add(SettingsPage.SERVER, "Server Info", "system windows linux python torch ${check.system.orEmpty()}") {
                TextPreference(title = "System", subtitle = check.system ?: "Not reported", trailing = null) {}
            }
            // Udostępnij raport jako plik (np. do zgłoszenia błędu).
            if (check.report != null) {
                add(SettingsPage.SERVER, "Server Info", "share server report sysinfo bug report file") {
                    TextPreference(
                        title = "Share Server Report",
                        subtitle = "The report of the last check as a file, for a bug report (its settings and folders included)",
                        trailing = {
                            Icon(Icons.Default.Share, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                    ) { viewModel.serverReportIntent()?.let { context.startActivity(it) } }
                }
            }
            // Zdrowie VRAM od startu Forge (liczniki z /sdapi/v1/memory): braki pamięci, ponowienia, szczyt.
            // How the VRAM has held up since Forge started (/sdapi/v1/memory's counters).
            check.outOfVram?.let { ooms ->
                add(SettingsPage.SERVER, "Server Health", "out of vram oom memory health") {
                    TextPreference(
                        title = "Out of VRAM",
                        subtitle = if (ooms == 0) "Never since Forge started" else "${times(ooms)} since Forge started",
                        titleColor = if (ooms > 0) MaterialTheme.colorScheme.error else Color.Unspecified,
                        trailing = null,
                    ) {}
                }
            }
            check.vramShort?.let { retries ->
                add(SettingsPage.SERVER, "Server Health", "vram short retries alloc slow health") {
                    TextPreference(
                        title = if (retries == 0) "VRAM Was Never Short" else "VRAM Was Short ${times(retries)}",
                        subtitle =
                            if (retries == 0) {
                                "Since Forge started the card never had to free memory before it could go on"
                            } else {
                                "Since Forge started the card had to free memory before it could go on, which slows images down"
                            },
                        trailing = null,
                    ) {}
                }
            }
            check.vramPeakGb?.let { peak ->
                add(SettingsPage.SERVER, "Server Health", "vram peak most held health") {
                    val total = check.vramTotalGb?.let { " of ${ServerMemory.gb(it)}" }.orEmpty()
                    TextPreference(
                        title = "VRAM Peak",
                        subtitle = "${ServerMemory.gb(peak)}$total GB\nThe most Forge has held since it started",
                        trailing = null,
                    ) {}
                }
            }
            // Ostatnie błędy Forge (do 5, od najnowszego), albo "No Errors".
            val errorsGroup = if (check.errors.isEmpty()) "Forge Errors" else "Forge Errors · ${check.errors.size}"
            if (check.errors.isEmpty()) {
                add(SettingsPage.SERVER, errorsGroup, "forge errors exceptions none") {
                    TextPreference(title = "No Errors", subtitle = "Forge has kept none since it started", trailing = null) {}
                }
            } else {
                check.errors.forEach { error ->
                    add(SettingsPage.SERVER, errorsGroup, "forge errors exceptions ${error.message}") {
                        TextPreference(title = error.message, subtitle = error.place, trailing = null) {}
                    }
                }
                add(SettingsPage.SERVER, errorsGroup, "forge errors last five restart") {
                    Text(
                        "Forge keeps its last 5 errors, newest first, until it restarts",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            // Flagi startowe Forge, kluczowe pakiety, wszystkie pakiety i wyłączone rozszerzenia.
            check.launchFlags?.let { flags ->
                add(SettingsPage.SERVER, "Start and Packages", "launch flags command line arguments $flags") {
                    TextPreference(title = "Launch Flags", subtitle = flags, trailing = null) {}
                }
            }
            check.keyPackages?.let { packages ->
                add(SettingsPage.SERVER, "Start and Packages", "key packages torch xformers gradio $packages") {
                    TextPreference(title = "Key Packages", subtitle = packages, trailing = null) {}
                }
            }
            if (check.packages.isNotEmpty()) {
                add(SettingsPage.SERVER, "Start and Packages", "all packages pip list") {
                    TextPreference(
                        title = "All Packages · ${check.packages.size}",
                        subtitle = "Every Python package Forge runs with",
                    ) { showPackages = true }
                }
            }
            if (check.turnedOffExtensions.isNotEmpty()) {
                add(SettingsPage.SERVER, "Start and Packages", "turned off extensions disabled inactive") {
                    TextPreference(
                        title = "Turned Off Extensions · ${check.turnedOffExtensions.size}",
                        subtitle = check.turnedOffExtensions.joinToString(", "),
                        trailing = null,
                    ) {}
                }
            }
        }
        // Rozszerzenia serwera: najpierw te, których używa aplikacja (z opisem po co), reszta po "N More".
        info?.extensions?.let { extensions ->
            val group = "Extensions · ${extensions.size}"
            val used = extensions.filter { it.purpose != null }
            val others = extensions.filter { it.purpose == null }
            used.forEach { extension ->
                add(SettingsPage.SERVER, group, "extension ${extension.name} ${extension.purpose}") {
                    ExtensionRow(extension)
                }
            }
            if (others.isNotEmpty()) {
                if (showAllExtensions) {
                    others.forEach { extension ->
                        add(SettingsPage.SERVER, group, "extension ${extension.name}") { ExtensionRow(extension) }
                    }
                } else {
                    add(SettingsPage.SERVER, group, "extensions more list") {
                        TextPreference(
                            title = "${others.size} More",
                            subtitle = others.take(3).joinToString(", ") { it.name } + "...",
                        ) { showAllExtensions = true }
                    }
                }
            }
        }
        // Zwalnianie modelu z VRAM po kolejce: wyłączone, od razu, po 10 albo 30 minutach (3.6.0).
        add(SettingsPage.SERVER, "Control", "unload after the queue vram free model at once minutes") {
            UnloadAfterQueueChoice(
                choice = config.unloadAfterQueue,
                onChoice = viewModel::setUnloadAfterQueue,
                coldStart = null,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        // Restart Forge (wymaga flagi na serwerze); kolejka czeka i rusza, gdy serwer wróci.
        add(SettingsPage.SERVER, "Control", "restart forge server reboot api-server-stop") {
            val restarting = restartingSince > 0
            TextPreference(
                title = if (restarting) "Forge Is Restarting" else "Restart Forge",
                subtitle =
                    when {
                        restarting -> "The app waits for it and goes on when it is back"
                        info?.canRestart == false -> RESTART_NEEDS_FLAG_HINT
                        else -> "The queue waits and goes on when it is back"
                    },
                enabled = !restarting && info?.canRestart != false && connection == ServerConnection.CONNECTED,
                titleColor = MaterialTheme.colorScheme.error,
            ) { confirmRestart = true }
        }
    }
}

// "raz", "dwa razy", "3 razy" po angielsku: "once", "twice", "3 times".

/** "once", "twice", "3 times". */
private fun times(count: Int): String =
    when (count) {
        1 -> "once"
        2 -> "twice"
        else -> "$count times"
    }

// Kiedy było sprawdzenie, czytelnie: "Today 14:32 · 2 hours ago" itp. (używane też poza tą stroną).

/** "Today 14:32 · 2 hours ago", "Yesterday 9:05 · 1 day ago", "12/09/2026 18:00 · 19 days ago". */
fun checkedText(
    at: Long,
    now: Long,
): String {
    val today =
        java.util.Calendar
            .getInstance()
            .apply { timeInMillis = now }
    val then =
        java.util.Calendar
            .getInstance()
            .apply { timeInMillis = at }
    val days =
        (
            (today.get(java.util.Calendar.YEAR) - then.get(java.util.Calendar.YEAR)) * 366 +
                today.get(java.util.Calendar.DAY_OF_YEAR) - then.get(java.util.Calendar.DAY_OF_YEAR)
        ).coerceAtLeast(0)
    val day =
        when (days) {
            0 -> "Today"
            1 -> "Yesterday"
            else ->
                java.text.DateFormat
                    .getDateInstance(java.text.DateFormat.SHORT)
                    .format(java.util.Date(at))
        }
    val minutes = ((now - at) / 60_000).coerceAtLeast(0)
    val ago =
        when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 24 * 60 -> (minutes / 60).let { if (it == 1L) "1 hour ago" else "$it hours ago" }
            else -> (minutes / (24 * 60)).let { if (it == 1L) "1 day ago" else "$it days ago" }
        }
    return "$day ${QueueSchedule.formatTime(at)} · $ago"
}

// Wiersz "Check Now": opis ostatniego sprawdzenia (albo dlaczego go nie ma) i przycisk; w trakcie pokazuje sekundy.

/**
 * "Check Now" (3.6.0, boards 4 and 5): when the last check was, or why there is none yet; while Forge writes its
 * report, how long it has taken. The last check stays on the page until the new one is in.
 */
@Composable
private fun ServerCheckRow(
    check: ServerCheck?,
    checkingSince: Long,
    problem: String?,
    connected: Boolean,
    onCheck: () -> Unit,
) {
    // The seconds of a running check, and "2 hours ago", move with the clock.
    val now by produceState(System.currentTimeMillis(), checkingSince) {
        while (true) {
            value = System.currentTimeMillis()
            delay(if (checkingSince != 0L) 1_000L else 30_000L)
        }
    }
    val checking = checkingSince != 0L
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                when {
                    checking -> "Reading Forge's report..."
                    check != null -> "Last Check"
                    else -> "Not Checked Yet"
                },
                fontSize = 16.sp,
            )
            Text(
                when {
                    checking ->
                        "${(now - checkingSince).coerceAtLeast(0) / 1000} s" +
                            if (check != null) " · the last check stays until the new one is in" else ""
                    problem != null -> problem
                    check != null -> checkedText(check.checkedAt, now)
                    !connected -> "Shown when the app is connected"
                    else ->
                        "Forge's report tells its version, the GPU, the computer's memory, the last errors and how the VRAM " +
                            "has held up. Reading it takes a few seconds, so the app does it only when you ask, and keeps what " +
                            "it found until the next check."
                },
                fontSize = 13.sp,
                lineHeight = 17.sp,
                color =
                    if (problem != null && !checking) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    },
            )
        }
        Spacer(Modifier.width(12.dp))
        if (checking) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
        } else {
            FilledTonalButton(onClick = onCheck, enabled = connected) { Text("Check Now") }
        }
    }
}

// Okno z listą wszystkich pakietów Pythona z ostatniego sprawdzenia ("All Packages").

/** Every Python package of the last check ("All Packages"). */
@Composable
internal fun PackagesDialog(
    packages: List<String>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("All Packages · ${packages.size}") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
                items(packages.size) { i ->
                    Text(packages[i], fontSize = 13.sp, modifier = Modifier.padding(vertical = 3.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

// Jedno rozszerzenie serwera: używane przez aplikację mają zielony znaczek i opis "po co", pozostałe wersję.

/** An extension of the server: the ones the app uses with a tick and what for, the others with their version. */
@Composable
private fun ExtensionRow(extension: ServerExtension) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (extension.purpose != null) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CONNECTED_GREEN, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f).alpha(if (extension.enabled) 1f else 0.5f)) {
            Text(extension.name, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val detail =
                listOfNotNull(
                    extension.purpose,
                    extension.version.takeIf { it.isNotBlank() && extension.purpose == null },
                    "off".takeIf { !extension.enabled },
                ).joinToString(" · ")
            if (detail.isNotEmpty()) {
                Text(detail, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f), maxLines = 1)
            }
        }
    }
}
