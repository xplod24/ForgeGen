package com.example.forgegen

import android.app.KeyguardManager
import android.content.Context
import android.net.Uri
import android.os.PowerManager
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* ============================================================================
 * USTAWIENIA: WSPÓLNY STAN EKRANU (3.6.1, podział SetupScreen na pliki)
 *
 * Co tu jest: SettingsUiState, jeden obiekt z tym, co dzielą między sobą strony ustawień i okna dialogowe:
 * - flagi "czy pokazać okno X" (np. showUrlDialog): strona ustawia je na true, plik SettingsDialogs.kt pokazuje okno,
 * - drobny stan ekranu (np. ile zajmuje pamięć obrazów, ile razy stuknięto w wersję aplikacji),
 * - wspólne akcje: diagnostyka serwera, instalacja aktualizacji, potwierdzenie blokadą telefonu.
 *
 * Jak to działa: rememberSettingsUiState() tworzy obiekt raz i trzyma go, dopóki ekran ustawień jest otwarty.
 * Każde pole "var x by mutableStateOf(...)" to stan Compose: gdy się zmieni, części ekranu, które go czytają,
 * rysują się na nowo. Strony ustawień są funkcjami rozszerzającymi tego obiektu (fun SettingsUiState.xxx()),
 * więc mogą pisać po prostu "showUrlDialog = true".
 *
 * Do poczytania:
 * - stan w Compose: mutableStateOf, remember, delegat "by" (getValue/setValue),
 * - funkcja rozszerzająca (extension function) w Kotlinie,
 * - korutyny (coroutines): CoroutineScope, launch, async/awaitAll, withContext, Dispatchers.IO (praca w tle),
 * - rememberLauncherForActivityResult: otwieranie systemowego wyboru pliku i odbiór wyniku,
 * - cykl życia (Lifecycle, ON_RESUME): co się dzieje, gdy aplikacja wraca na ekran.
 * ============================================================================ */

/** What the settings pages and their dialogs share while the settings are open. */
internal class SettingsUiState(
    val viewModel: ForgeViewModel,
    val context: Context,
    val scope: CoroutineScope,
) {
    // --- Strona Server: okna i rozwinięcia ---
    // Okno "Restart Forge?" i rozwinięta lista wszystkich rozszerzeń serwera.
    var confirmRestart by mutableStateOf(false)
    var showAllExtensions by mutableStateOf(false)

    // Okno z listą wszystkich pakietów Pythona z ostatniego "Check Now" (3.6.0).
    var showPackages by mutableStateOf(false)

    // --- DIALOG VISIBILITY STATES ---
    // Okna: adres serwera, czas oczekiwania, profile serwerów.
    var showUrlDialog by mutableStateOf(false)
    var showTimeoutDialog by mutableStateOf(false)
    var showProfilesDialog by mutableStateOf(false)

    // Okna: tryb powiadomienia o postępie, motyw, rozmiar pamięci obrazów.
    var showNotificationModeDialog by mutableStateOf(false)
    var showThemeDialog by mutableStateOf(false)
    var showImageCacheDialog by mutableStateOf(false)

    // Ile zajmuje pamięć obrazów na telefonie; liczone, gdy otwiera się Backup & Data (3.4.0).
    // How much the image cache takes, read when Backup & Data opens (3.4.0).
    var imageCacheUsed by mutableStateOf<Long?>(null)

    // Wersja, której kartę "New Version" użytkownik zamknął (Dismiss); -1 = żadna.
    var dismissedUpdateVersion by mutableIntStateOf(-1)

    // Wszystkie informacje o wydaniu, z "Show All" na jego karcie.
    // All notes of the offered update, from "Show All" on its card.
    var showAllReleaseNotes by mutableStateOf(false)

    // "Install": instalacja zamyka aplikację, więc zawsze najpierw pytamy (od 3.6.2; wcześniej tylko w czasie pracy
    // kolejki). Okno jest w SettingsDialogs.kt.
    var confirmInstall by mutableStateOf(false)

    // Okna: licencja i klucz galerii (IIB).
    var showLicenseDialog by mutableStateOf(false)
    var showGalleryKeyDialog by mutableStateOf(false)

    // Diagnostyka serwera: czy trwa, jej komunikat i wyniki dla poszczególnych adresów API.
    var isTestingConnection by mutableStateOf(false)
    var testStatus by mutableStateOf<String?>(null)
    var testResults by mutableStateOf<List<Pair<String, String>>>(emptyList())

    // Okno "Wipe Application Data" (wybór danych do usunięcia).
    var showWipeDataDialog by mutableStateOf(false)

    // Tryb debugowania (DebugMode): 8 szybkich stuknięć w "App Version", potem hasło.
    // Debug mode (DebugMode): 8 quick taps on "App Version", then the password.
    var versionTaps by mutableIntStateOf(0)
    var lastVersionTap by mutableLongStateOf(0L)
    var showDebugPasswordDialog by mutableStateOf(false)

    // Plik kopii zapasowej wybrany do importu; okno potwierdzenia pokazuje się, gdy nie jest null.
    var importUri by mutableStateOf<Uri?>(null)

    // Wybór pliku do eksportu i importu kopii (ustawiane w rememberSettingsUiState).
    // "Backup": the settings, presets, server profiles and wildcards to a file of the user's choice, and back.
    lateinit var exportLauncher: ManagedActivityResultLauncher<String, Uri?>
    lateinit var importLauncher: ManagedActivityResultLauncher<Array<String>, Uri?>

    // Czy telefon ma ustawioną blokadę ekranu (PIN, wzór, hasło): bez niej App Lock nie ma czym sprawdzać.
    val isDeviceSecure = (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure

    // Czy system pozwala kolejce działać przy zgaszonym ekranie (bez ograniczeń baterii); odświeżane po powrocie.
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    var isIgnoringBattery by mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName))

    // Live Updates: offered on Android 16+ (3.5.0; only Samsung's One UI 8 before); the system can also turn live
    // notifications off for the app, and whether it showed the last job's progress as one is read back (LiveUpdates).
    // Live Updates (postęp na ekranie blokady): czy telefon to umie, czy to Samsung, i co system pozwala.
    val isLiveUpdateSupported = LiveUpdates.isSupported(context)
    val isSamsung = LiveUpdates.isSamsung(context)
    var isLiveUpdateAllowed by mutableStateOf(LiveUpdates.isAllowedBySystem(context))
    var areNotificationsAllowed by mutableStateOf(LiveUpdates.areNotificationsAllowed(context))
    var promotedLastTime by mutableStateOf(LiveUpdates.promotedLastTime(context))

    // Po powrocie aplikacji na ekran: użytkownik mógł zmienić coś w ustawieniach systemu, więc czytamy to od nowa.
    fun refreshFromSystem() {
        isIgnoringBattery = powerManager.isIgnoringBatteryOptimizations(context.packageName)
        isLiveUpdateAllowed = LiveUpdates.isAllowedBySystem(context)
        areNotificationsAllowed = LiveUpdates.areNotificationsAllowed(context)
        promotedLastTime = LiveUpdates.promotedLastTime(context)
    }

    // "Install": aplikacja schodzi na bok, żeby Android mógł ją podmienić nową wersją.
    // "Install": the app steps aside so Android can replace it (the owner's request, 3.0.0-3).
    fun installNow() = viewModel.installUpdate { context.findActivity()?.moveTaskToBack(true) }

    // Wykonuje [action] dopiero po sprawdzeniu PIN-u/biometrii telefonu (gdy App Lock jest włączany albo włączony);
    // bez blokady telefonu nie ma czym sprawdzać, więc akcja idzie od razu.
    // Runs [action] after the phone's PIN/biometrics check when the app lock is on or being set up; without a phone
    // lock there is nothing to check against.
    fun confirmWithPhoneLock(
        title: String,
        action: () -> Unit,
    ) {
        val activity = context.findActivity()
        if (activity != null && AppLock.isAvailable(context)) {
            val config = viewModel.config.value
            AppLock.authenticate(
                activity = activity,
                allowBiometrics = config.useNativeSecurity && config.useBiometricLock,
                title = title,
                onSuccess = action,
            )
        } else {
            action()
        }
    }

    // Diagnostyka: pyta 7 adresów API serwera naraz (każdy z 1-sekundowym limitem) i pokazuje czasy w oknie.
    // Asks the server's usual endpoints once each, with a short timeout; the result opens in a dialog.
    fun runDiagnostics() {
        isTestingConnection = true
        testStatus = "Running diagnostics..."
        testResults = emptyList()

        scope.launch(Dispatchers.IO) {
            val testClientBuilder =
                okhttp3.OkHttpClient
                    .Builder()
                    .connectTimeout(1, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(1, java.util.concurrent.TimeUnit.SECONDS)
            val testClient = testClientBuilder.build()
            val endpoints = listOf("progress", "memory", "options", "samplers", "schedulers", "sd-models", "loras")
            val resultsMap = java.util.concurrent.ConcurrentHashMap<String, String>()

            coroutineScope {
                val deferreds =
                    endpoints.map { ep ->
                        async {
                            val start = System.currentTimeMillis()
                            try {
                                var cleanUrl =
                                    viewModel.config.value.apiUrl
                                        .trimEnd('/')
                                if (cleanUrl.isNotEmpty() &&
                                    !cleanUrl.startsWith("http://") &&
                                    !cleanUrl.startsWith("https://")
                                ) {
                                    cleanUrl = "http://$cleanUrl"
                                }

                                val req =
                                    okhttp3.Request
                                        .Builder()
                                        .url("$cleanUrl/sdapi/v1/$ep")
                                        .build()
                                testClient.newCall(req).execute().use { res ->
                                    val time = System.currentTimeMillis() - start
                                    if (res.isSuccessful) {
                                        resultsMap[ep] = "${time}ms ✔"
                                    } else if (res.code == 401 || res.code == 403) {
                                        resultsMap[ep] = "Auth Needed ✘"
                                    } else {
                                        resultsMap[ep] = "Err ${res.code} ✘"
                                    }
                                }
                            } catch (_: Exception) {
                                resultsMap[ep] = "Failed ✘"
                            }
                        }
                    }
                deferreds.awaitAll()
            }

            val finalResults = endpoints.map { it to (resultsMap[it] ?: "Timeout ✘") }
            val allSuccess = finalResults.all { it.second.contains("✔") }

            withContext(Dispatchers.Main) {
                testResults = finalResults
                testStatus = if (allSuccess) "All Systems Operational!" else "Some APIs Failed."
                if (!allSuccess) {
                    val failedEps = finalResults.filter { !it.second.contains("✔") }.map { it.first }
                    viewModel.showToast("Unresponsive: ${failedEps.joinToString(", ")}")
                }
            }
        }
    }
}

// Tworzy (raz) wspólny stan ekranu ustawień, wybór plików kopii i obserwatora powrotu aplikacji na ekran.

/** The settings screen's shared state, made once while the settings are open. */
@Composable
internal fun rememberSettingsUiState(viewModel: ForgeViewModel): SettingsUiState {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val state = remember { SettingsUiState(viewModel, context, scope) }

    // "Backup": the settings, presets, server profiles and wildcards to a file of the user's choice, and back.
    state.exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) viewModel.exportBackup(uri)
        }
    state.importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> state.importUri = uri }

    // --- LIFECYCLE OBSERVER FOR BATTERY OPTIMIZATION AND LIVE NOTIFICATION REFRESH ---
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) state.refreshFromSystem()
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return state
}
