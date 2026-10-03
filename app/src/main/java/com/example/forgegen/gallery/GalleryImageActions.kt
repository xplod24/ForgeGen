package com.example.forgegen

import android.content.Intent
import android.net.ConnectivityManager
import android.util.Log
import com.example.forgegen.ForgeGalleryManager.ImageJobsRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* ============================================================================
 * GALERIA: DZIAŁANIA NA OBRAZACH (3.6.1, podział ForgeGalleryManager na pliki)
 *
 * Co tu jest: to, co użytkownik robi z obrazami: zapis na telefon (także automatyczny), pobieranie i udostępnianie
 * kilku obrazów naraz, dodawanie do ulubionych oraz zadania z obrazów (Upscale, More Like This, Variance).
 *
 * Jak to działa: zapis idzie przez DeviceImages (MediaStore Androida albo prywatny folder aplikacji); udostępnianie
 * przez systemowe okno "Udostępnij" (Intent.ACTION_SEND). Zadania z obrazów czytają dane generowania z indeksu
 * i dopisują nowe zadania do kolejki.
 *
 * Do poczytania: MediaStore (zapis zdjęć w galerii telefonu), FileProvider i Intent (udostępnianie plików),
 * sieć taryfowa (metered network) w ConnectivityManager.
 *
 * Funkcje w tym pliku są funkcjami rozszerzającymi obiektu ForgeGalleryManager ("fun ForgeGalleryManager.x()"):
 * wywołuje się je tak samo jak jego własne (ForgeGalleryManager.x()) i mają dostęp do jego stanu oznaczonego
 * jako internal. Sam stan (listy, flagi, zadania w tle) jest w ForgeGalleryManager.kt.
 * ============================================================================ */

// Format dat serwera ("yyyy-MM-dd HH:mm:ss").
// --- SAVING TO THE PHONE ---

/** Server date format ("yyyy-MM-dd HH:mm:ss"), used for folder dates and the auto-save start. */
internal fun ForgeGalleryManager.serverDateFormat() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

// Tryb automatycznego zapisu na telefon: wyłączony, ulubione albo wszystkie nowe obrazy.
fun ForgeGalleryManager.setAutoSaveMode(mode: String) {
    val config = ForgeRepository.config.value
    // From now on: switching "All new images" on must not download the gallery that already exists.
    val since =
        if (mode == AUTO_SAVE_ALL && config.autoSaveMode != AUTO_SAVE_ALL) serverDateFormat().format(Date()) else config.autoSaveSince
    ForgeSettingsManager.saveConfig(config.copy(autoSaveMode = mode, autoSaveSince = since))
}

// Czy telefon jest na sieci taryfowej (np. dane komórkowe): wtedy bez automatycznego zapisu wszystkich.
internal fun ForgeGalleryManager.isOnMeteredNetwork(): Boolean =
    application.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: false

// Zapisuje na telefon nowe obrazy z serwera (tryb "wszystkie"); zwraca, ile zapisano.

/** Saves new images of the gallery (newer than the moment "All new images" was switched on); only on Wi-Fi. */
internal suspend fun ForgeGalleryManager.autoSaveNewImages(root: String): Int {
    val since = ForgeRepository.config.value.autoSaveSince
    if (since.isBlank() || isOnMeteredNetwork()) return 0
    val candidates =
        indexedImages.value
            .filter { isUnder(it.fullpath, root) && it.date.isNotEmpty() && it.date >= since }
            .sortedBy { it.date }
    if (candidates.isEmpty()) return 0

    val saved = DeviceImages.savedNames(application).toMutableSet()
    var count = 0
    for (image in candidates) {
        currentCoroutineContext().ensureActive()
        val name = DeviceImages.nameFor(image.fullpath)
        if (name in saved) continue
        try {
            saveServerImage(image.toGalleryItem(), name)
            saved += name
            count++
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Auto-save of ${image.fullpath} failed, retried on the next sync: ${e.message}")
        }
    }
    return count
}

// Pobiera obraz z serwera i zapisuje go na telefonie.
internal suspend fun ForgeGalleryManager.saveServerImage(
    item: GalleryItem,
    name: String,
) {
    val request = Request.Builder().url(getGalleryImageUrl(item)).build()
    networkManager.client.newCall(request).awaitResponse().use { response ->
        if (!response.isSuccessful) throw IOException("Server returned ${response.code}")
        DeviceImages.save(application, name) { out -> response.body.byteStream().use { it.copyTo(out) } }
    }
}

// Zapis jednego obrazu na telefon z komunikatem (pomija już zapisane, gdy quietIfSaved).
internal suspend fun ForgeGalleryManager.saveToPhone(
    item: GalleryItem,
    quietIfSaved: Boolean,
) {
    try {
        val name = DeviceImages.nameFor(item.fullpath)
        if (name in DeviceImages.savedNames(application)) {
            if (!quietIfSaved) ForgeRepository.showToast("Already saved in ${DeviceImages.locationName()}")
            return
        }
        saveServerImage(item, name)
        ForgeRepository.showToast("Saved to ${DeviceImages.locationName()}")
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        ForgeRepository.showToast("Download Failed: ${e.message}")
    }
}

// Pobiera jeden obraz na telefon (przycisk w podglądzie).
fun ForgeGalleryManager.downloadImage(item: GalleryItem) {
    managerScope.launch { saveToPhone(item, quietIfSaved = false) }
}

// Pobiera kilka zaznaczonych obrazów na telefon, z jednym komunikatem na końcu.
// --- SEVERAL IMAGES AT ONCE (selected in the gallery) ---

/** Saves [items] to the phone, skipping those saved before; one message at the end. */
fun ForgeGalleryManager.downloadImages(items: List<GalleryItem>) {
    managerScope.launch {
        val saved = DeviceImages.savedNames(application).toMutableSet()
        var count = 0
        var failed = 0
        for (item in items) {
            val name = DeviceImages.nameFor(item.fullpath)
            if (name in saved) continue
            try {
                saveServerImage(item, name)
                saved += name
                count++
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failed++
            }
        }
        val failedText = if (failed > 0) ", $failed failed" else ""
        ForgeRepository.showToast("Saved $count of ${items.size} images to ${DeviceImages.locationName()}$failedText")
    }
}

// Dodaje zaznaczone obrazy do ulubionych.

/** Adds [items] to the favorites (those already there stay). */
fun ForgeGalleryManager.addFavorites(items: List<GalleryItem>) {
    managerScope.launch {
        val dao = getDb().favoriteImageDao()
        val added = items.filter { it.fullpath !in _favoritePaths.value }
        for (item in added) {
            dao.insertFavorite(FavoriteImageEntity(fullpath = item.fullpath, name = item.name, date = item.date ?: ""))
        }
        _favoritePaths.update { it + added.map { item -> item.fullpath } }
        favoriteItems.update { files -> added.map { it.asFavorite() } + files }
        ForgeRepository.showToast("Added ${added.size} images to the favorites")
        if (ForgeRepository.config.value.autoSaveMode == AUTO_SAVE_FAVORITES) added.forEach { saveToPhone(it, quietIfSaved = true) }
    }
}

// Udostępnia kilka obrazów naraz (opcjonalnie bez danych generowania).

/** One share sheet for [items], downloaded from the server one after another. */
fun ForgeGalleryManager.shareImages(
    items: List<GalleryItem>,
    onIntentReady: (Intent) -> Unit,
) {
    managerScope.launch {
        try {
            val files =
                items.map { item ->
                    val request = Request.Builder().url(getGalleryImageUrl(item)).build()
                    networkManager.client.newCall(request).awaitResponse().use { response ->
                        if (!response.isSuccessful) throw IOException("Server returned ${response.code}")
                        DeviceImages.sharedCopy(application, item.name) { out -> response.body.byteStream().use { it.copyTo(out) } }
                    }
                }
            val intent = DeviceImages.shareManyIntent(application, files)
            withContext(Dispatchers.Main) { onIntentReady(intent) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ForgeRepository.showToast("Share Failed: ${e.message}")
        }
    }
}

// Udostępnia jeden obraz (opcjonalnie bez danych generowania).
fun ForgeGalleryManager.shareImage(
    item: GalleryItem,
    onIntentReady: (Intent) -> Unit,
) {
    managerScope.launch {
        try {
            val request = Request.Builder().url(getGalleryImageUrl(item)).build()
            val intent =
                networkManager.client.newCall(request).awaitResponse().use { response ->
                    if (!response.isSuccessful) throw IOException("Server returned ${response.code}")
                    DeviceImages.shareIntent(application, item.name) { out -> response.body.byteStream().use { it.copyTo(out) } }
                }
            withContext(Dispatchers.Main) { onIntentReady(intent) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ForgeRepository.showToast("Share Failed: ${e.message}")
        }
    }
}

// Otwiera okno zadań z obrazów (Upscale, More Like This, Variance) dla zaznaczonych obrazów.

/** Opens the dialog of [kind] for [items] and reads their generation data (100 images per request where IIB can). */
fun ForgeGalleryManager.requestImageJobs(
    kind: ImageJobs.Kind,
    items: List<GalleryItem>,
) {
    val files = items.filter { !it.isDir }
    if (files.isEmpty()) return
    imageJobsRead?.cancel()
    val request = ImageJobsRequest(kind, files.size)
    _imageJobs.value = request
    imageJobsRead =
        ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
            val sources =
                try {
                    remakeImages(files)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot read the images' generation data", e)
                    files.map { ImageJobs.Source.Skipped(ImageJobs.UNREADABLE) }
                }
            // Only into the dialog it was read for (it may have been closed or replaced meanwhile).
            if (_imageJobs.value === request) _imageJobs.value = request.copy(sources = sources)
        }
}

// Odczytuje dane generowania zaznaczonych obrazów, żeby zrobić z nich zadania.

/** [files] remade as jobs from their generation data, in the same order. */
internal suspend fun ForgeGalleryManager.remakeImages(files: List<GalleryItem>): List<ImageJobs.Source> {
    val reader = InfoReader()
    val texts = HashMap<String, String>()
    files.chunked(100).forEach { texts += reader.read(it) }
    val models = networkManager.models.value
    val current = ForgeModelManager.selectedModel.value
    return files.map { file ->
        val text = texts[file.fullpath]
        when {
            text == null -> ImageJobs.Source.Skipped(ImageJobs.UNREADABLE)
            text.isBlank() -> ImageJobs.Source.Skipped(ImageJobs.NO_DATA)
            else -> ImageJobs.remake(Infotext.parse(text), models, current)
        }
    }
}

// Zamyka okno zadań z obrazów.
fun ForgeGalleryManager.dismissImageJobs() {
    imageJobsRead?.cancel()
    _imageJobs.value = null
}

// Zmienia rodzaj zadań w oknie (Upscale, More Like This, Variance).

/** Another tab of the selection's dialog (Upscale, Variance on Seed); the images' data stays. */
fun ForgeGalleryManager.setImageJobsKind(kind: ImageJobs.Kind) {
    _imageJobs.update { it?.copy(kind = kind) }
}

// Dodaje do kolejki powiększenia (hires fix) zaznaczonych obrazów.

/** Queues the upscales of the dialog's images, one job per image; images already that large are left out. */
fun ForgeGalleryManager.queueUpscales(
    scale: Float,
    upscaler: String,
    denoising: Float,
) {
    val sources = _imageJobs.value?.sources ?: return
    val jobs =
        sources.filterIsInstance<ImageJobs.Source.Ready>().mapNotNull { source ->
            ImageJobs.upscale(source, scale, upscaler, denoising)?.let { it to ImageJobs.upscaleLabel(scale) }
        }
    ForgeQueueManager.queueJobs(jobs)
    _imageJobs.value = null
    ForgeRepository.showToast(queuedMessage(jobs.size, sources.size - jobs.size))
}

// Dodaje do kolejki "More Like This": te same ustawienia, nowe seedy.

/** Queues the images like the dialog's one (see ImageJobs.moreLikeThis). */
fun ForgeGalleryManager.queueMoreLikeThis(
    similar: Boolean,
    count: Int,
    strength: Float,
) {
    val source = _imageJobs.value?.sources?.firstOrNull() as? ImageJobs.Source.Ready ?: return
    val jobs = ImageJobs.moreLikeThis(source, similar, count, strength)
    ForgeQueueManager.queueJobs(jobs)
    _imageJobs.value = null
    ForgeRepository.showToast(queuedMessage(jobs.size, 0))
}

// Dodaje do kolejki warianty: ten sam seed z wariacją (variance seed).

/**
 * Queues "Variance on Seed" of the dialog's images (3.0.0): each keeps its seed, the jobs vary what [spec] says;
 * nothing when that is more than MAX_VARIANCE_JOBS (the dialog does not offer it then).
 */
fun ForgeGalleryManager.queueVariance(spec: ImageJobs.VarianceSpec) {
    val sources = _imageJobs.value?.sources ?: return
    val ready = sources.filterIsInstance<ImageJobs.Source.Ready>()
    if (ImageJobs.varianceCount(ready, spec) > ImageJobs.MAX_VARIANCE_JOBS) return
    val jobs = ready.flatMap { ImageJobs.variance(it, spec) }
    ForgeQueueManager.queueJobs(jobs)
    _imageJobs.value = null
    ForgeRepository.showToast(queuedMessage(jobs.size, sources.size - ready.size))
}

// Komunikat po dodaniu zadań ("3 jobs queued", z pominiętymi).
internal fun ForgeGalleryManager.queuedMessage(
    queued: Int,
    leftOut: Int,
) = buildString {
    append("Added $queued ${if (queued == 1) "job" else "jobs"} to the queue")
    if (leftOut > 0) append(" ($leftOut left out)")
}
