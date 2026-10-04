package com.example.forgegen

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.forgegen.ui.components.GalleryKeyDialog
import com.example.forgegen.ui.components.LicenseDialog
import com.example.forgegen.ui.components.RestartForgeDialog
import com.example.forgegen.ui.components.WhatsNewDialog
import kotlinx.coroutines.launch

/* ============================================================================
 * USTAWIENIA: OKNA DIALOGOWE (3.6.1, podział SetupScreen na pliki)
 *
 * Co tu jest: wszystkie okna otwierane ze stron ustawień (adres serwera, profile, motyw, licencja, usuwanie
 * danych, hasło trybu debugowania, ...). Każde okno pokazuje się, gdy jego flaga w SettingsUiState jest true
 * (np. showThemeDialog), a zamknięcie okna ustawia ją z powrotem na false.
 *
 * Jak to działa: SetupScreen wywołuje SettingsDialogs() zawsze; funkcja rysuje tylko te okna, których flagi są
 * włączone. Stan wpisywany w oknie (np. nowy adres) żyje w remember { } wewnątrz okna i znika po jego zamknięciu.
 *
 * Do poczytania: AlertDialog (Material 3), OutlinedTextField, Checkbox, RadioButton, produceState,
 * scope.launch (uruchomienie korutyny z kliknięcia).
 * ============================================================================ */

// Wszystkie okna dialogowe ustawień; rysowane są tylko te, których flagi są włączone.
@Composable
internal fun SettingsUiState.SettingsDialogs() {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val serverCheck by viewModel.lastServerCheck.collectAsStateWithLifecycle()
    val generating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val queueActive by viewModel.isQueueActive.collectAsStateWithLifecycle()
    val updateManifest by viewModel.updateManifest.collectAsStateWithLifecycle()
    val debugUnlocked by viewModel.debugUnlocked.collectAsStateWithLifecycle()

    /* ==========================================================
     * DIALOG BUILDERS
     * ========================================================== */

    // Okno hasła trybu debugowania (po 8 stuknięciach w wersję aplikacji). Hasło sprawdza DebugMode (tylko jego skrót).
    if (showDebugPasswordDialog) {
        var password by remember { mutableStateOf("") }
        var checking by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { if (!checking) showDebugPasswordDialog = false },
            title = { Text("Debug Mode") },
            text = {
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        error = null
                    },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = password.isNotEmpty() && !checking,
                    onClick = {
                        checking = true
                        scope.launch {
                            val result = viewModel.debugUnlock(password)
                            checking = false
                            when (result) {
                                DebugMode.UnlockResult.UNLOCKED -> {
                                    showDebugPasswordDialog = false
                                    viewModel.showToast("Debug mode on: see Settings > Debug")
                                }
                                DebugMode.UnlockResult.WRONG_PASSWORD -> error = "Wrong password"
                                DebugMode.UnlockResult.TOO_MANY_ATTEMPTS -> error = "Too many attempts, try again in a minute"
                            }
                        }
                    },
                ) { Text("Unlock") }
            },
            dismissButton = { TextButton(onClick = { showDebugPasswordDialog = false }) { Text("Cancel") } },
        )
    }

    // Potwierdzenie importu kopii zapasowej z wybranego pliku.
    importUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { importUri = null },
            title = { Text("Import Settings?") },
            text = {
                Text(
                    "The settings, presets and server profiles on this phone are replaced by the ones in the file. " +
                        "Its wildcards are added (a wildcard with the same name is replaced).",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.importBackup(uri)
                    importUri = null
                }) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { importUri = null }) { Text("Cancel") } },
        )
    }

    // Usuwanie wybranych danych aplikacji; przy włączonym App Lock wymaga potwierdzenia blokadą telefonu.
    if (showWipeDataDialog) {
        var wipeSettings by remember { mutableStateOf(false) }
        var wipePresets by remember { mutableStateOf(false) }
        var wipeProfiles by remember { mutableStateOf(false) }
        var wipeHistory by remember { mutableStateOf(false) }
        var wipeWildcards by remember { mutableStateOf(false) }
        var wipeImages by remember { mutableStateOf(false) }
        var wipeJobs by remember { mutableStateOf(false) }
        val jobCount by produceState(0) { value = viewModel.jobHistoryCount() }

        val promptHistory by viewModel.promptHistory.collectAsStateWithLifecycle()
        val wildcards by viewModel.wildcards.collectAsStateWithLifecycle()

        AlertDialog(
            onDismissRequest = { showWipeDataDialog = false },
            title = { Text("Wipe Application Data") },
            text = {
                Column {
                    Text("Select which data to permanently delete:")
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = wipeSettings, onCheckedChange = { wipeSettings = it })
                        Text(
                            "App Settings & State (1 item" +
                                (if (debugUnlocked) ", turns the debug mode off)" else ")"),
                            fontSize = 14.sp,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = wipePresets, onCheckedChange = { wipePresets = it })
                        Text("Presets (${config.presets.size} items)", fontSize = 14.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = wipeProfiles, onCheckedChange = { wipeProfiles = it })
                        Text("Server Profiles (${config.serverProfiles.size} items)", fontSize = 14.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = wipeHistory, onCheckedChange = { wipeHistory = it })
                        Text("Prompt History (${promptHistory.size} items)", fontSize = 14.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = wipeWildcards, onCheckedChange = { wipeWildcards = it })
                        Text("Wildcards (${wildcards.size} items)", fontSize = 14.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = wipeImages, onCheckedChange = { wipeImages = it })
                        Text("Images Index (Re-fetch later)", fontSize = 14.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = wipeJobs, onCheckedChange = { wipeJobs = it })
                        Text("Generation History ($jobCount jobs)", fontSize = 14.sp)
                    }
                }
            },
            confirmButton = {
                // With nothing ticked the button used to silently wipe settings and history anyway.
                val anySelected = wipeSettings || wipePresets || wipeProfiles || wipeHistory || wipeWildcards || wipeImages || wipeJobs
                TextButton(
                    enabled = anySelected,
                    onClick = {
                        val wipe = {
                            if (wipeSettings) viewModel.wipeSettings()
                            if (wipePresets) viewModel.wipePresets()
                            if (wipeProfiles) viewModel.wipeServerProfiles()
                            if (wipeHistory) viewModel.wipePromptHistory()
                            if (wipeWildcards) viewModel.wipeWildcards()
                            if (wipeImages) viewModel.wipeGalleryIndex()
                            if (wipeJobs) viewModel.wipeGenerationHistory()
                            viewModel.showToast("Selected data wiped")
                            showWipeDataDialog = false
                        }
                        // Wiping the settings also switches the app lock off, so it needs the same check.
                        if (config.useNativeSecurity) confirmWithPhoneLock("Wipe application data") { wipe() } else wipe()
                    },
                ) {
                    val labelColor =
                        if (anySelected) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        }
                    Text("Wipe Selected", color = labelColor)
                }
            },
            dismissButton = {
                TextButton(onClick = { showWipeDataDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    // The download progress dialog is shown globally by MainActivity.

    // Potwierdzenie "Install" (3.6.2, prośba właściciela): instalacja zamyka aplikację, a Android ją podmienia.
    // Tekst mówi, co zostaje: kolejka, prompty i obrazy tej sesji wracają po aktualizacji (SessionMemory.kt).
    // Gdy kolejka pracuje, dochodzi zdanie o przerwanym zadaniu: zacznie się od nowa po ponownym otwarciu.
    if (confirmInstall) {
        val queueWorks = queueActive || generating
        AlertDialog(
            onDismissRequest = { confirmInstall = false },
            title = { Text(updateManifest?.let { "Install ForgeGen ${it.versionName}?" } ?: "Install the Update?") },
            text = {
                Text(
                    if (queueWorks) {
                        "ForgeGen closes while Android replaces it, so the queue stops: the running job starts again " +
                            "when you open ForgeGen, and the jobs after it wait. The prompts and this session's images " +
                            "are kept."
                    } else {
                        "ForgeGen closes while Android replaces it, and a notification says when it is done. Open it " +
                            "again and it goes on where you left it: the queue, the prompts and this session's images " +
                            "are kept."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmInstall = false
                    installNow()
                }) { Text("Install") }
            },
            dismissButton = { TextButton(onClick = { confirmInstall = false }) { Text("Cancel") } },
        )
    }

    // Wszystkie informacje o oferowanym wydaniu ("Show All" na karcie aktualizacji).
    val notesOf = updateManifest
    if (showAllReleaseNotes && notesOf != null) {
        WhatsNewDialog(
            markdown = releaseNotesMarkdown(notesOf.changelog.orEmpty()),
            onDismiss = { showAllReleaseNotes = false },
            title = "What's New in ${notesOf.versionName}",
        )
    }

    // Klucz galerii IIB: wpisanie, zmiana albo usunięcie (zapisywany jest tylko jego odcisk).
    if (showGalleryKeyDialog) {
        GalleryKeyDialog(
            saved = GalleryKey.savedFor(config) != null,
            onSave = viewModel::saveGalleryKey,
            onRemove = { viewModel.forgetGalleryKey() },
            onDismiss = { showGalleryKeyDialog = false },
        )
    }

    // Pełny tekst licencji i link do kodu źródłowego.
    if (showLicenseDialog) {
        LicenseDialog(
            onDismiss = { showLicenseDialog = false },
            onOpenSource = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AppLicense.SOURCE_URL))) },
        )
    }

    // Wybór motywu: systemowy, jasny, ciemny.
    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("Theme") },
            text = {
                Column {
                    listOf(
                        THEME_SYSTEM to "System default",
                        THEME_LIGHT to "Light",
                        THEME_DARK to "Dark",
                    ).forEach { (mode, name) ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.saveConfig(config.copy(themeMode = mode))
                                        showThemeDialog = false
                                    }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = config.themeMode == mode, onClick = null)
                            Spacer(Modifier.width(16.dp))
                            Text(name, fontSize = 16.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) { Text("Close") }
            },
        )
    }

    // Wybór rozmiaru pamięci obrazów na telefonie (działa po ponownym uruchomieniu).
    if (showImageCacheDialog) {
        AlertDialog(
            onDismissRequest = { showImageCacheDialog = false },
            title = { Text("Image Cache") },
            text = {
                Column {
                    Text(
                        "Thumbnails and images kept on the phone, so they show at once. The size applies after a restart.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    ImageCache.SIZES_MB.forEach { mb ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.saveConfig(config.copy(imageCacheMb = mb))
                                        showImageCacheDialog = false
                                    }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = config.imageCacheMb == mb, onClick = null)
                            Spacer(Modifier.width(16.dp))
                            Text(ImageCache.label(mb), fontSize = 16.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showImageCacheDialog = false }) { Text("Close") }
            },
        )
    }

    // Wybór trybu powiadomienia o postępie: Simple, Verbose, Disabled.
    if (showNotificationModeDialog) {
        AlertDialog(
            onDismissRequest = { showNotificationModeDialog = false },
            title = { Text("Progress Notification Mode") },
            text = {
                Column {
                    val modes =
                        listOf(
                            Triple("Simple", "Simple", "Image number, progress and ETA"),
                            Triple("Verbose", "Verbose", "Also batch size and an Open App button"),
                            Triple("Disabled", "Disabled", "Only a static notice (Android requires one)"),
                        )
                    modes.forEach { (internalValue, displayName, desc) ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.saveConfig(config.copy(notificationMode = internalValue))
                                        showNotificationModeDialog = false
                                    }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = config.notificationMode == internalValue, onClick = null)
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(displayName, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Text(desc, fontSize = 12.sp, color = Color.Gray)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showNotificationModeDialog = false }) { Text("Close") } },
        )
    }

    // Adres serwera (API URL) do wpisania.
    if (showUrlDialog) {
        var tempUrl by remember { mutableStateOf(config.apiUrl) }
        AlertDialog(
            onDismissRequest = { showUrlDialog = false },
            title = { Text("API URL") },
            text = { OutlinedTextField(value = tempUrl, onValueChange = { tempUrl = it }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.saveConfig(config.copy(apiUrl = tempUrl))
                    showUrlDialog = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showUrlDialog = false }) { Text("Cancel") } },
        )
    }

    // Lista wszystkich pakietów Pythona z raportu "Check Now"; okno restartu Forge.
    if (showPackages) {
        PackagesDialog(serverCheck?.packages.orEmpty()) { showPackages = false }
    }
    if (confirmRestart) {
        RestartForgeDialog(viewModel, generating = generating, onDismiss = { confirmRestart = false })
    }

    // Limit czasu zwykłych zapytań (w sekundach); niepoprawna wartość = 10 s.
    if (showTimeoutDialog) {
        var tempTimeout by remember { mutableStateOf(config.timeout.toString()) }
        AlertDialog(
            onDismissRequest = { showTimeoutDialog = false },
            title = { Text("Connection Timeout (sec)") },
            text = {
                OutlinedTextField(
                    value = tempTimeout,
                    onValueChange = { tempTimeout = it },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = tempTimeout.toIntOrNull() ?: 10
                    viewModel.saveConfig(config.copy(timeout = t))
                    showTimeoutDialog = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showTimeoutDialog = false }) { Text("Cancel") } },
        )
    }

    // Profile serwerów: przełączenie (stuknięcie), usunięcie (kosz) i zapis obecnego adresu pod nazwą.
    if (showProfilesDialog) {
        var newProfileName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showProfilesDialog = false },
            title = { Text("Server Profiles") },
            text = {
                Column {
                    LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                        items(config.serverProfiles) { profile ->
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            viewModel.saveConfig(config.copy(apiUrl = profile.url))
                                            showProfilesDialog = false
                                        }.padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(profile.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    Text(profile.url, fontSize = 12.sp, color = Color.Gray)
                                }
                                IconButton(onClick = { viewModel.removeServerProfile(profile.name) }) {
                                    Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Add New Profile", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    OutlinedTextField(
                        value = newProfileName,
                        onValueChange = { newProfileName = it },
                        label = { Text("Profile Name (Uses current API URL)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            if (newProfileName.isNotBlank() && config.apiUrl.isNotBlank()) {
                                viewModel.addServerProfile(newProfileName, config.apiUrl)
                                newProfileName = ""
                            }
                        },
                        modifier = Modifier.align(Alignment.End).padding(top = 8.dp),
                    ) {
                        Text("Save Profile")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showProfilesDialog = false }) { Text("Close") } },
        )
    }

    // Wyniki diagnostyki: czas odpowiedzi każdego adresu API albo błąd.
    if (isTestingConnection || testStatus != null) {
        AlertDialog(
            onDismissRequest = {
                isTestingConnection = false
                testStatus = null
            },
            title = { Text("Diagnostics") },
            text = {
                Column {
                    Text(
                        text = testStatus ?: "",
                        color =
                            if (testStatus?.contains("Operational") ==
                                true
                            ) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    testResults.forEach { (endpoint, result) ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(endpoint, fontSize = 12.sp)
                            Text(result, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    isTestingConnection = false
                    testStatus = null
                }) { Text("Close") }
            },
        )
    }
}
