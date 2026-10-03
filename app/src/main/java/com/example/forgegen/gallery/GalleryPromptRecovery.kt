package com.example.forgegen

import android.net.Uri
import android.util.Log
import com.example.forgegen.ui.components.IndicatorState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* ============================================================================
 * GALERIA: METADANE I ODZYSKIWANIE PROMPTU (3.6.1, podział ForgeGalleryManager na pliki)
 *
 * Co tu jest: odczyt danych generowania (prompt, seed, model, ...) zapisanych w obrazie PNG i ich wczytanie do
 * ekranu głównego; odzyskiwanie ostatniego promptu i seeda, gdy aplikacja ich nie pamięta.
 *
 * Jak to działa: dane generowania Forge zapisuje w PNG w bloku tekstowym "parameters" (PngMetadata je czyta);
 * parseAndApplyPngInfo() zamienia ten tekst na ustawienia ekranu głównego.
 *
 * Do poczytania: format PNG i bloki tEXt/iTXt, ContentResolver i Uri (czytanie plików wybranych przez użytkownika),
 * InputStream.
 *
 * Funkcje w tym pliku są funkcjami rozszerzającymi obiektu ForgeGalleryManager ("fun ForgeGalleryManager.x()"):
 * wywołuje się je tak samo jak jego własne (ForgeGalleryManager.x()) i mają dostęp do jego stanu oznaczonego
 * jako internal. Sam stan (listy, flagi, zadania w tle) jest w ForgeGalleryManager.kt.
 * ============================================================================ */

// Przerywa trwające odzyskiwanie promptu.
fun ForgeGalleryManager.cancelPromptRestore() {
    if (_isRestoringPrompt.value == IndicatorState.LOADING) {
        restoreJob?.cancel()
        _isRestoringPrompt.value = IndicatorState.IDLE
    }
}

// Tekst danych generowania ("parameters") z pliku PNG.
// --- METADATA ---

internal fun ForgeGalleryManager.extractPngParameters(inputStream: InputStream): String = PngMetadata.readParameters(inputStream)

// Dane generowania z obrazu wybranego z telefonu (przez jego Uri).
suspend fun ForgeGalleryManager.extractMetadataFromUri(uri: Uri): String? =
    withContext(Dispatchers.IO) {
        try {
            application.contentResolver.openInputStream(uri)?.use { stream ->
                extractPngParameters(stream).takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read URI for metadata", e)
            null
        }
    }

// Pokazuje dane generowania obrazu z telefonu w podglądzie.
fun ForgeGalleryManager.loadMetadataForLocalFile(path: String) {
    _currentImageMetadata.value = "Loading metadata..."
    managerScope.launch {
        _currentImageMetadata.value =
            try {
                val file = java.io.File(path)
                if (!file.exists()) {
                    "Failed to load image."
                } else {
                    file.inputStream().use { extractPngParameters(it) }.ifBlank { "No generation data found." }
                }
            } catch (e: Exception) {
                "Failed: ${e.message}"
            }
    }
}

// Pokazuje dane generowania obrazu z serwera w podglądzie (z indeksu albo od serwera).

/** Generation data for the viewer: read by IIB on the server, so the image is not downloaded a second time. */
fun ForgeGalleryManager.loadMetadataForImage(item: GalleryItem?) {
    metadataJob?.cancel() // a slow answer for the previous image must not replace this one
    if (item == null) {
        _currentImageMetadata.value = null
        return
    }
    _currentImageMetadata.value = "Loading metadata..."
    metadataJob =
        managerScope.launch {
            _currentImageMetadata.value =
                try {
                    (serverGenInfo(item.fullpath) ?: infoFromImageFile(item)).ifBlank { "No generation data found." }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    "Failed: ${e.message}"
                }
        }
}

// Wczytuje dane generowania (prompt, seed, sampler, rozmiar, ...) do ekranu głównego.
internal fun ForgeGalleryManager.parseAndApplyPngInfo(text: String) {
    if (text.isEmpty()) return
    val info = Infotext.parse(text)
    ForgeSettingsManager.updateState { state: AppState ->
        // An image's prompts already hold the styles it was made with (3.1.0): chosen ones would come twice.
        val newState = state.copy(positivePrompt = info.positivePrompt, negativePrompt = info.negativePrompt, styles = emptyList())
        info.params["Steps"]?.toIntOrNull()?.let { newState.steps = it }
        info.params["CFG scale"]?.toFloatOrNull()?.let { newState.cfgScale = it }
        info.params["Seed"]?.toLongOrNull()?.let { newState.seed = it }
        info.params["Sampler"]?.let { newState.sampler = it }
        info.params["Size"]?.split("x")?.takeIf { it.size == 2 }?.let { (width, height) ->
            width.trim().toIntOrNull()?.let { newState.width = it }
            height.trim().toIntOrNull()?.let { newState.height = it }
        }
        info.params["Clip skip"]?.toIntOrNull()?.let { newState.clipSkip = it }
        newState
    }
    ForgeRepository.showToast("Loaded generation data")
}

// Wczytuje prompt i ustawienia z wybranego obrazu galerii do ekranu głównego.
// --- PROMPT RECOVERY ---

fun ForgeGalleryManager.recoverPromptFromImage(item: GalleryItem) {
    if (_isRestoringPrompt.value != IndicatorState.IDLE) return
    _isRestoringPrompt.value = IndicatorState.LOADING

    restoreJob =
        ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
            try {
                val imageUrl = getGalleryImageUrl(item)
                if (imageUrl.isEmpty()) throw Exception("Invalid URL")

                val file = downloadToCache(item) ?: throw Exception("No data")
                val infoStr = file.inputStream().use { extractPngParameters(it) }

                ForgeQueueManager.showRecoveredImage(file)
                withContext(Dispatchers.Main) { parseAndApplyPngInfo(infoStr) }
                _isRestoringPrompt.value = IndicatorState.SUCCESS
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                ForgeRepository.showToast("Network error")
                _isRestoringPrompt.value = IndicatorState.ERROR
            }
            delay(1500)
            _isRestoringPrompt.value = IndicatorState.IDLE
        }
}

// Pobiera obraz z serwera do pamięci podręcznej aplikacji (do odczytu metadanych).

/**
 * Streams [item] from the server into a new cache file (the whole image used to be held in memory, and
 * encoded again as text to be shown); null when the server did not send it.
 */
internal suspend fun ForgeGalleryManager.downloadToCache(item: GalleryItem): java.io.File? {
    val imageUrl = getGalleryImageUrl(item)
    if (imageUrl.isEmpty()) return null
    val file = ForgeQueueManager.newRecoveredImageFile()
    networkManager.client.newCall(Request.Builder().url(imageUrl).build()).awaitResponse().use { res ->
        if (!res.isSuccessful) return null
        try {
            file.outputStream().use { out -> res.body.byteStream().use { it.copyTo(out) } }
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }
    return file
}

// Szuka najnowszego obrazu wygenerowanego na serwerze (najpierw w indeksie, potem w folderach).

/** The newest image in the gallery (today's folder, else the newest folder with images, else the top folder). */
internal suspend fun ForgeGalleryManager.findLastGeneratedImage(): GalleryItem? {
    val rootPath = ForgeRepository.config.value.galleryPath

    suspend fun fetchFiles(folder: String): List<GalleryItem> {
        try {
            val response =
                networkManager.forgeApi?.getGalleryFilesDynamic(
                    url = "${prefix()}/files",
                    folderPath = if (folder.isNotEmpty() && folder != "Root") encodeFolderPath(folder) else "",
                )
            if (response?.isSuccessful == true) {
                val responseBody = response.body()?.string() ?: ""
                return parseGalleryItems(responseBody)
            } else if (response?.code() == 400 && folder.isNotEmpty() && folder != "Root") {
                return fetchFiles("Root")
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Fetch Last Generated files error", e)
        }
        return emptyList()
    }

    val rootItems = fetchFiles(rootPath)
    val currentDateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    val candidateImages = mutableListOf<GalleryItem>()

    val todayFolder = rootItems.find { it.isDir && it.name == currentDateStr }
    if (todayFolder != null) candidateImages.addAll(fetchFiles(todayFolder.fullpath).filter { !it.isDir })

    if (candidateImages.isEmpty()) {
        val dateFolders = rootItems.filter { it.isDir }.sortedByDescending { it.name }
        for (folder in dateFolders) {
            val folderImages = fetchFiles(folder.fullpath).filter { !it.isDir }
            if (folderImages.isNotEmpty()) {
                candidateImages.addAll(folderImages)
                break
            }
        }
    }
    if (candidateImages.isEmpty()) candidateImages.addAll(rootItems.filter { !it.isDir })

    return candidateImages.maxWithOrNull(
        compareBy<GalleryItem> { item ->
            val match = "^(\\d+)-".toRegex().find(item.name)
            match?.groupValues?.get(1)?.toLongOrNull() ?: -1L
        }.thenBy { item ->
            item.createdTime?.toDoubleOrNull() ?: item.date?.toDoubleOrNull() ?: 0.0
        },
    )
}

// Dane generowania najnowszego obrazu na serwerze.

/** The generation data of the newest gallery image, which is also shown as the session. */
internal suspend fun ForgeGalleryManager.fetchLastGeneratedImageInfo(): String? {
    val target = findLastGeneratedImage() ?: return null
    val file = downloadToCache(target) ?: return null
    val infoStr = file.inputStream().use { extractPngParameters(it) }
    ForgeQueueManager.showRecoveredImage(file)
    return infoStr
}

// Odzyskuje ostatni prompt: z historii aplikacji, a gdy jej brak, z najnowszego obrazu na serwerze.
fun ForgeGalleryManager.recoverLastPrompt() {
    if (_isRestoringPrompt.value != IndicatorState.IDLE) return
    _isRestoringPrompt.value = IndicatorState.LOADING

    // Backup the current AppState to restore it in case the prompt recovery fails.
    val backupState = ForgeRepository.appState.value.copy()

    restoreJob =
        ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
            try {
                // 1. Fetch from server gallery
                val galleryInfoStr = fetchLastGeneratedImageInfo()
                if (!galleryInfoStr.isNullOrBlank()) {
                    withContext(Dispatchers.Main) { parseAndApplyPngInfo(galleryInfoStr) }
                    _isRestoringPrompt.value = IndicatorState.SUCCESS
                    return@launch
                }

                // 2. Check local cache (Fallback)
                val localInfoStr = ForgeSettingsManager.loadLastGeneratedInfo()
                val localImgFile = java.io.File(application.cacheDir, "last_generated_image.png")

                if (localInfoStr != null && localImgFile.exists()) {
                    val copy = ForgeQueueManager.newRecoveredImageFile()
                    localImgFile.copyTo(copy, overwrite = true)
                    ForgeQueueManager.showRecoveredImage(copy)
                    withContext(Dispatchers.Main) { parseAndApplyPngInfo(localInfoStr) }
                    _isRestoringPrompt.value = IndicatorState.SUCCESS
                    return@launch
                }

                // 3. Fallback to Physton endpoints (for prompt only)
                var fallbackPos = ""
                var fallbackNeg = ""
                try {
                    val posRes = networkManager.forgeApi?.getLatestHistory("txt2img")
                    if (posRes?.isSuccessful == true) {
                        fallbackPos = posRes.body()?.prompt ?: ""
                    }
                    val negRes = networkManager.forgeApi?.getLatestHistory("txt2img_neg")
                    if (negRes?.isSuccessful == true) {
                        fallbackNeg = negRes.body()?.prompt ?: ""
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "Failed to fetch physton history", e)
                }

                if (fallbackPos.isNotEmpty() || fallbackNeg.isNotEmpty()) {
                    ForgeSettingsManager.updateState { state: AppState ->
                        state.copy(
                            positivePrompt = if (fallbackPos.isNotEmpty()) fallbackPos else state.positivePrompt,
                            negativePrompt = if (fallbackNeg.isNotEmpty()) fallbackNeg else state.negativePrompt,
                        )
                    }
                    ForgeRepository.showToast("Restored from server history (No image found)")
                    _isRestoringPrompt.value = IndicatorState.SUCCESS
                    return@launch
                }

                // 4. Fallback to backup state
                ForgeSettingsManager.updateState { _: AppState -> backupState }
                ForgeRepository.showToast("Used local cache (No images found)")
                _isRestoringPrompt.value = IndicatorState.SUCCESS
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                ForgeSettingsManager.updateState { _: AppState -> backupState }
                ForgeRepository.showToast("Recovery failed")
                _isRestoringPrompt.value = IndicatorState.ERROR
            } finally {
                delay(1500)
                _isRestoringPrompt.value = IndicatorState.IDLE
            }
        }
}

// Odzyskuje seed ostatniego obrazu (do ponownego użycia).
fun ForgeGalleryManager.recoverLastSeed() {
    ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
        try {
            // The server reads the seed from the image; only an old gallery extension needs the image itself.
            val target = findLastGeneratedImage()
            val infoStr =
                target?.let { item ->
                    serverGenInfo(item.fullpath) ?: infoFromImageFile(item)
                }
            if (infoStr != null) {
                val foundSeed = Infotext.parse(infoStr).seed.toLongOrNull()
                if (foundSeed != null) {
                    ForgeSettingsManager.updateState { state: AppState -> state.copy(seed = foundSeed) }
                    ForgeRepository.showToast("Seed recovered: $foundSeed")
                } else {
                    ForgeRepository.showToast("No seed found in last image")
                }
            } else {
                ForgeRepository.showToast("Failed to find last image")
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ForgeRepository.showToast("Network error recovering seed")
        }
    }
}
