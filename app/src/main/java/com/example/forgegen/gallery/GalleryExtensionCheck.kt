package com.example.forgegen

import android.util.Log
import com.example.forgegen.ForgeGalleryManager.Extension
import com.example.forgegen.ForgeGalleryManager.ExtensionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/* ============================================================================
 * GALERIA: ROZSZERZENIE IIB NA SERWERZE (3.6.1, podział ForgeGalleryManager na pliki)
 *
 * Co tu jest: wykrywanie rozszerzenia Infinite Image Browsing (IIB) na serwerze Forge, bez którego galeria nie
 * działa, oraz obsługa jego tajnego klucza.
 *
 * Jak to działa: po połączeniu z serwerem detectExtension() pyta kolejne możliwe adresy IIB; gdy któryś odpowie,
 * odczytuje z ustawień serwera folder roboczy Forge i folder z obrazami (górny folder galerii). Odpowiedź 401/403
 * znaczy, że galeria chce klucza (stan LOCKED).
 *
 * Do poczytania: kody odpowiedzi HTTP (200, 401, 403, 404), Retrofit (wywołania API jako funkcje Kotlina),
 * Mutex z kotlinx.coroutines (jedno sprawdzanie naraz).
 *
 * Funkcje w tym pliku są funkcjami rozszerzającymi obiektu ForgeGalleryManager ("fun ForgeGalleryManager.x()"):
 * wywołuje się je tak samo jak jego własne (ForgeGalleryManager.x()) i mają dostęp do jego stanu oznaczonego
 * jako internal. Sam stan (listy, flagi, zadania w tle) jest w ForgeGalleryManager.kt.
 * ============================================================================ */

// "Check Again": sprawdza rozszerzenie IIB jeszcze raz.
// --- THE SERVER'S GALLERY EXTENSION ---

/** Looks for the extension again ("Check Again" in the gallery). */
fun ForgeGalleryManager.checkExtension() {
    managerScope.launch { detectExtension() }
}

// Sprawdza podany klucz galerii u serwera; true, gdy otwiera galerię.

/**
 * "Unlock" in the gallery or "Gallery Key" in the settings (3.5.0): saves [key]'s fingerprint for this server and
 * asks the extension again. False when the server refused it; the key saved before, if any, is then kept.
 */
suspend fun ForgeGalleryManager.tryKey(key: String): Boolean {
    val before = ForgeRepository.config.value
    ForgeSettingsManager.saveConfig(GalleryKey.withFingerprint(before, GalleryKey.fingerprint(key)))
    detectExtension()
    if (_extension.value.state != Extension.LOCKED) return true
    ForgeSettingsManager.saveConfig(GalleryKey.withFingerprint(ForgeRepository.config.value, GalleryKey.savedFor(before)))
    _extension.value = ExtensionStatus(Extension.LOCKED, "The server did not accept this key.")
    return false
}

// Zapomina klucz galerii dla tego serwera i sprawdza rozszerzenie od nowa.

/** Removes the key saved for this server and asks the extension again. */
fun ForgeGalleryManager.forgetKey() {
    ForgeSettingsManager.saveConfig(GalleryKey.withFingerprint(ForgeRepository.config.value, null))
    checkExtension()
}

// Inny serwer: stan rozszerzenia nieznany, otwarty folder zamknięty.

/** Another server: what was found on the previous one no longer holds, the open folder included. */
fun ForgeGalleryManager.onServerChanged() {
    _extension.value = ExtensionStatus(Extension.UNKNOWN)
    clearFolder()
}

// Szuka IIB na serwerze (po połączeniu) i ustawia z niego foldery galerii; potem synchronizuje indeks.

/**
 * Asks the server for the Infinite Image Browsing extension (run with the server's lists when the app connects)
 * and takes the gallery's folders from its settings: Forge's working folder and the folder its images are saved
 * to. Once it is found the index is brought up to date.
 */
suspend fun ForgeGalleryManager.detectExtension() {
    val api = networkManager.forgeApi ?: return
    val status =
        detection.withLock {
            val before = _extension.value
            _extension.value = ExtensionStatus(Extension.CHECKING)
            val found =
                try {
                    askForExtension(api)
                } catch (e: CancellationException) {
                    _extension.value = before // replaced by a newer question, or the app closed
                    throw e
                }
            _extension.value = found
            found
        }
    if (status.state == Extension.READY) requestSync()
}

// Pyta kolejne możliwe adresy IIB i zamienia odpowiedź na stan rozszerzenia (READY, MISSING, LOCKED, ...).
internal suspend fun ForgeGalleryManager.askForExtension(api: ForgeApi): ExtensionStatus {
    for (prefix in ForgeSettingsManager.GALLERY_PREFIXES) {
        val response =
            try {
                api.getGlobalSettingsDynamic("$prefix/global_setting")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "No answer from the server about the gallery extension: ${e.message}")
                return ExtensionStatus(Extension.UNKNOWN, "The server did not answer: ${e.message ?: e.javaClass.simpleName}")
            }
        val errorType =
            if (response.isSuccessful) {
                null
            } else {
                try {
                    GalleryKey.errorType(response.errorBody()?.string())
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    null
                }
            }
        when {
            response.code() == 404 || response.code() == 405 -> continue // not under this name
            response.code() == 401 && errorType == GalleryKey.LOCKED_TYPE -> {
                apiPrefix = prefix
                val saved = GalleryKey.savedFor(ForgeRepository.config.value) != null
                return ExtensionStatus(Extension.LOCKED, "The key saved for this server no longer opens the gallery.".takeIf { saved })
            }
            response.code() == 400 && errorType == GalleryKey.KEY_REQUIRED_TYPE -> {
                apiPrefix = prefix
                return ExtensionStatus(Extension.KEY_NOT_SET)
            }
            response.code() == 401 || response.code() == 403 -> {
                apiPrefix = prefix
                return ExtensionStatus(Extension.FAILED, "The gallery extension refused the app (HTTP ${response.code()}).")
            }
            !response.isSuccessful -> {
                apiPrefix = prefix
                return ExtensionStatus(Extension.FAILED, "The gallery extension answered with error ${response.code()}.")
            }
        }
        apiPrefix = prefix
        val settings =
            try {
                response.body()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }
        val sdCwd = settings?.sdCwd.orEmpty()
        val folder =
            outputFolder(sdCwd, settings?.globalSetting)
                ?: return ExtensionStatus(Extension.FAILED, "The gallery extension did not tell where Forge saves images.")
        val config = ForgeRepository.config.value
        if (config.galleryPath != folder || (sdCwd.isNotEmpty() && config.serverBasePath != sdCwd)) {
            ForgeSettingsManager.saveConfig(config.copy(galleryPath = folder, serverBasePath = sdCwd.ifEmpty { config.serverBasePath }))
        }
        Log.d(TAG, "Gallery extension found at $prefix, images in $folder")
        return ExtensionStatus(Extension.READY, writable = settings?.isReadonly != true)
    }
    return ExtensionStatus(Extension.MISSING)
}

// Folder, do którego Forge zapisuje obrazy txt2img (z jego ustawień), jako pełna ścieżka.

/**
 * The folder Forge saves txt2img images to, from its settings as the extension reports them: "outdir_samples"
 * when set (every image goes there), else "outdir_txt2img_samples", else Forge's default. A relative folder is
 * under Forge's working folder [sdCwd] (and uses its separator); null when that is needed but unknown.
 */
internal fun ForgeGalleryManager.outputFolder(
    sdCwd: String,
    settings: GlobalSettingInnerDto?,
): String? {
    val configured =
        (settings?.outdirSamples?.takeIf { it.isNotBlank() } ?: settings?.outdirTxt2ImgSamples?.takeIf { it.isNotBlank() })
            ?.trim() ?: DEFAULT_OUTPUT_FOLDER
    val isAbsolute = configured.startsWith("/") || configured.startsWith("\\\\") || WINDOWS_DRIVE.containsMatchIn(configured)
    if (isAbsolute) return configured.trimEnd('/', '\\').ifEmpty { configured }
    if (sdCwd.isBlank()) return null
    val separator = if (sdCwd.contains('\\')) '\\' else '/'
    val relative =
        configured
            .removePrefix("./")
            .removePrefix(".\\")
            .replace('/', separator)
            .replace('\\', separator)
            .trim(separator)
    val base = sdCwd.trimEnd('/', '\\')
    return if (relative.isEmpty()) base else "$base$separator$relative"
}
