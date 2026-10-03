package com.example.forgegen

import android.util.Log
import com.example.forgegen.ForgeGalleryManager.FolderListing
import com.example.forgegen.ForgeGalleryManager.GalleryException
import com.example.forgegen.ForgeGalleryManager.PendingDelete
import com.example.forgegen.ForgeGalleryManager.Transfer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/* ============================================================================
 * GALERIA: ZMIANY PLIKÓW NA SERWERZE (3.6.1, podział ForgeGalleryManager na pliki)
 *
 * Co tu jest: zmiany plików galerii na serwerze przez IIB (3.2.0): usuwanie z możliwością cofnięcia (Undo),
 * przenoszenie i kopiowanie, nowy folder, pobieranie folderu jako ZIP, okładki folderów oraz sprawdzanie, czy
 * ulubione obrazy nadal są na serwerze.
 *
 * Jak to działa: usunięcie najpierw tylko chowa obrazy (hiddenPaths) i czeka kilka sekund; dopiero potem wysyła
 * prośbę do serwera, więc Undo nic nie musi przywracać. IIB odpowiada 403, gdy może tylko czytać (tryb read-only).
 *
 * Do poczytania: synchronized i blokady (deleteLock), ZipOutputStream (pakowanie plików), Retrofit Response.
 *
 * Funkcje w tym pliku są funkcjami rozszerzającymi obiektu ForgeGalleryManager ("fun ForgeGalleryManager.x()"):
 * wywołuje się je tak samo jak jego własne (ForgeGalleryManager.x()) i mają dostęp do jego stanu oznaczonego
 * jako internal. Sam stan (listy, flagi, zadania w tle) jest w ForgeGalleryManager.kt.
 * ============================================================================ */

// "1 image" / "3 images".
// --- CHANGING THE SERVER'S FILES (3.2.0) ---

internal fun ForgeGalleryManager.count(n: Int) = if (n == 1) "1 image" else "$n images"

// IIB odpowiedział 403: serwer pozwala tylko czytać, galeria wyłącza zmiany plików.

/** The extension refused to change a file: it may only read, and the app stops offering such changes. */
internal fun ForgeGalleryManager.markReadOnly() {
    _extension.update { it.copy(writable = false) }
}

// Treść błędu z odpowiedzi serwera, do komunikatu.
internal fun ForgeGalleryManager.errorText(response: retrofit2.Response<*>): String {
    val detail =
        try {
            response.errorBody()?.string()?.let { ForgeQueueManager.serverDetail(it) }
        } catch (e: Exception) {
            null
        }
    return detail ?: "the server answered with error ${response.code()}"
}

// Usuwa obrazy z możliwością cofnięcia: od razu chowa je z list, serwer dostaje prośbę po kilku sekundach.

/**
 * Deletes [items] from the server after [DELETE_DELAY_MS], in which [undoDelete] takes it back. They leave the
 * lists at once. Deleting again within that time sends the earlier ones at once (only the last can be undone).
 */
fun ForgeGalleryManager.deleteImages(items: List<GalleryItem>) {
    val images = items.filter { !it.isDir }.distinctBy { it.fullpath }
    if (images.isEmpty()) return
    val pending = PendingDelete(images)
    val earlier: PendingDelete?
    synchronized(deleteLock) {
        earlier = _pendingDelete.value
        deleteTimer?.cancel()
        hiddenPaths.update { it + pending.paths }
        _pendingDelete.value = pending
        deleteTimer =
            managerScope.launch {
                delay(DELETE_DELAY_MS)
                sendDelete(pending)
            }
    }
    if (earlier != null) managerScope.launch { sendDelete(earlier) }
}

// "Undo": przywraca schowane obrazy, nic nie wysyłając do serwera.

/** Undo: the images deleted last come back, and the server is not asked to delete them. */
fun ForgeGalleryManager.undoDelete() {
    synchronized(deleteLock) {
        val pending = _pendingDelete.value ?: return
        if (!pending.undo()) return
        deleteTimer?.cancel()
        _pendingDelete.value = null
        hiddenPaths.update { it - pending.paths }
    }
}

// Wysyła usunięcie do serwera po upływie czasu na Undo; przy błędzie folder wczytuje się od nowa.
internal suspend fun ForgeGalleryManager.sendDelete(pending: PendingDelete) {
    if (!pending.send()) return
    _pendingDelete.compareAndSet(pending, null)
    val paths = pending.items.map { it.fullpath }
    val error =
        try {
            val response = api().deleteGalleryFiles("${prefix()}/delete_files", GalleryDeleteRequestDto(paths))
            when {
                response.isSuccessful -> null
                response.code() == HTTP_FORBIDDEN -> READ_ONLY_MESSAGE.also { markReadOnly() }
                else -> "Delete failed: ${errorText(response)}"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Delete failed: ${e.message ?: e.javaClass.simpleName}"
        }
    if (error == null) {
        forgetFiles(paths)
    } else {
        // The server stops at the first file it cannot delete, so some may be gone: the folder is listed again.
        ForgeRepository.showToast(error)
        relistFolder()
        requestSync()
    }
    hiddenPaths.update { it - pending.paths }
}

// Usuwa ścieżki z otwartego folderu, indeksu, ulubionych i okładek.

/** Files gone from the server: out of the open folder, the index and the favorites. */
internal suspend fun ForgeGalleryManager.forgetFiles(paths: List<String>) {
    val gone = paths.toHashSet()
    listing.update { it.copy(items = it.items.filterNot { item -> item.fullpath in gone }) }
    paths.chunked(500).forEach { getDb().galleryImageDao().deleteImages(it) }
    val favorites = paths.filter { it in _favoritePaths.value }
    if (favorites.isNotEmpty()) {
        getDb().favoriteImageDao().deleteFavorites(favorites)
        _favoritePaths.update { it - favorites.toSet() }
        favoriteItems.update { files -> files.filterNot { it.fullpath in gone } }
    }
    forgetCovers(paths.map { GalleryPaths.parentOf(it) })
    reloadIndex()
}

// Przenosi albo kopiuje obrazy do innego folderu (w tle, z jednym komunikatem).

/** Moves or copies [items] to [dest] (each with its .txt of generation data); one message at the end. */
fun ForgeGalleryManager.transferImages(
    items: List<GalleryItem>,
    dest: String,
    kind: Transfer,
) {
    managerScope.launch {
        val message =
            try {
                transfer(items.filter { !it.isDir }.distinctBy { it.fullpath }, dest, kind)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "${if (kind == Transfer.MOVE) "Moving" else "Copying"} failed: ${e.message ?: e.javaClass.simpleName}"
            }
        ForgeRepository.showToast(message)
    }
}

// Właściwe przeniesienie/kopiowanie: pomija obrazy o zajętej nazwie, potem aktualizuje indeks i ulubione.
internal suspend fun ForgeGalleryManager.transfer(
    images: List<GalleryItem>,
    dest: String,
    kind: Transfer,
): String {
    val folderName = folderName(dest)
    // The server would replace a file of the same name without asking, so those stay where they are.
    val plan = GalleryTransfer.plan(images, dest, listFolder(dest).map { it.name })
    val left =
        listOfNotNull(
            "${count(plan.nameTaken.size)} left out: the name is taken there".takeIf { plan.nameTaken.isNotEmpty() },
            "${count(plan.alreadyThere.size)} already there".takeIf { plan.alreadyThere.isNotEmpty() },
        )
    if (plan.send.isEmpty()) return "Nothing to ${if (kind == Transfer.MOVE) "move" else "copy"}: " + left.joinToString(", ")

    val sent = plan.send.map { it.fullpath }
    if (kind == Transfer.COPY) {
        // IIB copies into a folder that is not there as a file of that name: the folder is made first.
        val made = api().makeGalleryFolder("${prefix()}/mkdirs", GalleryMkdirsRequestDto(dest))
        if (made.code() == HTTP_FORBIDDEN) {
            markReadOnly()
            return READ_ONLY_MESSAGE
        }
    }
    val endpoint = if (kind == Transfer.MOVE) "move_files" else "copy_files"
    val response = api().transferGalleryFiles("${prefix()}/$endpoint", GalleryTransferRequestDto(sent, dest))
    if (response.code() == HTTP_FORBIDDEN) {
        markReadOnly()
        return READ_ONLY_MESSAGE
    }
    if (!response.isSuccessful) return "${if (kind == Transfer.MOVE) "Moving" else "Copying"} failed: ${errorText(response)}"
    val failed = GalleryTransfer.failed(sent, response.body()?.errors.orEmpty())
    val done = plan.send.filter { it.fullpath !in failed }

    if (kind == Transfer.MOVE) followMoved(done, dest)
    rememberFolder(dest)
    forgetCovers(listOf(dest) + done.map { GalleryPaths.parentOf(it.fullpath) })
    relistFolder()
    requestSync()
    val verb = if (kind == Transfer.MOVE) "Moved" else "Copied"
    val notes = left + listOfNotNull("${failed.size} failed".takeIf { failed.isNotEmpty() })
    return "$verb ${count(done.size)} to $folderName" + notes.joinToString("") { ", $it" }
}

// Przeniesione obrazy zachowują dane w indeksie i miejsce w ulubionych (nowe ścieżki).

/** Moved images keep their generation data in the index and stay favorites under their new paths. */
internal suspend fun ForgeGalleryManager.followMoved(
    moved: List<GalleryItem>,
    dest: String,
) {
    if (moved.isEmpty()) return
    val index = getDb().galleryImageDao()
    val favorites = getDb().favoriteImageDao()
    for (item in moved) {
        val newPath = GalleryPaths.child(dest, item.name)
        index.movePath(item.fullpath, newPath)
        if (item.fullpath in _favoritePaths.value) favorites.moveFavorite(item.fullpath, newPath)
    }
    val paths = moved.mapTo(HashSet()) { it.fullpath }
    listing.update { it.copy(items = it.items.filterNot { item -> item.fullpath in paths }) }
    loadFavoritePaths()
    reloadIndex()
}

// Zapamiętuje folder jako ostatnio używany (tam galeria otworzy się następnym razem).
internal suspend fun ForgeGalleryManager.rememberFolder(folder: String) {
    _lastFolder.value = folder
    getDb().appSettingDao().putSetting(AppSettingEntity(LAST_FOLDER_KEY, folder))
}

// Nazwa folderu do pokazania ("Gallery" dla górnego folderu).

/** A folder's name in messages ("Gallery" for the top folder). */
fun ForgeGalleryManager.folderName(path: String): String =
    galleryRoot()?.takeIf { samePath(it, path) }?.let { GALLERY_NAME } ?: GalleryPaths.nameOf(path)

// Wczytuje otwarty folder od nowa (po zmianie plików).

/** The open folder listed again without the loading placeholders (after files were changed). */
internal suspend fun ForgeGalleryManager.relistFolder() {
    val root = galleryRoot() ?: return
    val path = listing.value.path.ifEmpty { return }
    val request = folderRequest.get()
    try {
        val items = listServerFolder(path, root) ?: return
        if (request == folderRequest.get()) {
            listing.value = FolderListing(path, items)
            requestCovers(items.filter { it.isDir }.map { it.fullpath })
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Listing $path again failed: ${e.message}")
    }
}

// Podfoldery folderu (do okna wyboru celu przenoszenia).

/** The subfolders of [path] for the folder picker, the most recently changed first. */
suspend fun ForgeGalleryManager.subfolders(path: String): List<GalleryItem> =
    withContext(Dispatchers.IO) {
        listFolder(path).filter { it.isDir }.sortedByDescending { it.date.orEmpty() }.also { folders ->
            requestCovers(folders.map { it.fullpath })
        }
    }

// Tworzy nowy folder na serwerze (IIB /mkdirs).

/** Makes the folder [name] in [parent]; its path, or the reason it could not be made. */
suspend fun ForgeGalleryManager.createFolder(
    parent: String,
    name: String,
): Result<String> =
    withContext(Dispatchers.IO) {
        GalleryPaths.folderNameProblem(name)?.let { return@withContext Result.failure(GalleryException(it)) }
        val path = GalleryPaths.child(parent, name.trim())
        try {
            val response = api().makeGalleryFolder("${prefix()}/mkdirs", GalleryMkdirsRequestDto(path))
            when {
                response.isSuccessful -> {
                    if (samePath(parent, listing.value.path)) relistFolder()
                    Result.success(path)
                }
                response.code() == HTTP_FORBIDDEN -> {
                    markReadOnly()
                    Result.failure(GalleryException(READ_ONLY_MESSAGE))
                }
                else -> Result.failure(GalleryException("The folder could not be made: ${errorText(response)}"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(GalleryException("The folder could not be made: ${e.message ?: e.javaClass.simpleName}"))
        }
    }

// Pobiera folder jako plik ZIP (IIB pakuje go po swojej stronie).

/**
 * Packs [items] into one ZIP on the phone (Downloads, or the private folder with "Save to Phone Privately"). The
 * images are downloaded into it one by one, so the server writes nothing and it works where IIB may only read.
 */
fun ForgeGalleryManager.downloadZip(items: List<GalleryItem>) {
    val images = items.filter { !it.isDir }.distinctBy { it.fullpath }
    if (images.isEmpty()) return
    managerScope.launch {
        val name = "ForgeGen_${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())}.zip"
        ForgeRepository.showToast("Packing ${count(images.size)}...")
        try {
            DeviceImages.saveArchive(application, name) { out ->
                ZipOutputStream(out).use { zip ->
                    val used = HashSet<String>()
                    for (item in images) {
                        currentCoroutineContext().ensureActive()
                        val name = DeviceImages.nameFor(item.fullpath)
                        var entry = name
                        var n = 2
                        while (!used.add(entry.lowercase(Locale.ROOT))) {
                            entry = "${name.substringBeforeLast('.')}-${n++}.${name.substringAfterLast('.')}"
                        }
                        val request = Request.Builder().url(getGalleryImageUrl(item)).build()
                        networkManager.client.newCall(request).awaitResponse().use { response ->
                            if (!response.isSuccessful) throw IOException("${item.name}: the server answered ${response.code}")
                            zip.putNextEntry(ZipEntry(entry))
                            response.body.byteStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                }
            }
            ForgeRepository.showToast("Saved $name (${count(images.size)}) to ${DeviceImages.archiveLocationName()}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ForgeRepository.showToast("ZIP failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}

// Pobiera okładki folderów (najnowsze obrazy w każdym), tylko brakujące.

/** Asks the server for the covers of [folders] it has not given yet (IIB keeps them, so it answers quickly). */
internal fun ForgeGalleryManager.requestCovers(folders: List<String>) {
    // Settings > Features > Folder Covers (3.4.0): off, plain folder icons and no requests.
    if (!coversSupported || !ForgeRepository.config.value.folderCovers) return
    val missing = folders.filter { GalleryPaths.key(it) !in _folderCovers.value }
    if (missing.isEmpty()) return
    managerScope.launch {
        for (chunk in missing.chunked(PATHS_PER_REQUEST)) {
            try {
                val response = api().getGalleryFolderCovers("${prefix()}/batch_top_4_media_info", GalleryPathsRequestDto(chunk))
                if (response.code() in UNSUPPORTED_ENDPOINT) {
                    coversSupported = false // an older IIB: plain folder icons
                    return@launch
                }
                if (!response.isSuccessful) return@launch
                val body = response.body().orEmpty()
                val found =
                    chunk.associate { folder ->
                        GalleryPaths.key(folder) to body[folder].orEmpty().map { it.toDomain() }.filter { isImage(it.name) }
                    }
                _folderCovers.update { it + found }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "No folder covers: ${e.message}")
                return@launch
            }
        }
    }
}

// Zapomina okładki folderów, których dotknęła zmiana (wczytają się na nowo).
internal fun ForgeGalleryManager.forgetCovers(folders: List<String>) {
    val keys = folders.mapTo(HashSet()) { GalleryPaths.key(it) }
    _folderCovers.update { covers -> covers.filterKeys { it !in keys } }
    // The open folder's again.
    requestCovers(
        listing.value.items
            .filter { it.isDir }
            .map { it.fullpath },
    )
}

// Sprawdza, czy ulubione obrazy są jeszcze na serwerze (najwyżej co 5 minut).

/** Asks the server which favorites are still there (when the Favorites tab shows; at most every 5 minutes). */
fun ForgeGalleryManager.checkFavorites() {
    if (readyRoot() == null || !ForgeRepository.config.value.favoritesCheck) return // Settings > Features (3.4.0)
    val now = System.currentTimeMillis()
    if (now - favoritesCheckedAt < FAVORITES_CHECK_INTERVAL_MS) return
    favoritesCheckedAt = now
    managerScope.launch {
        val missing = HashSet<String>()
        try {
            for (chunk in _favoritePaths.value.toList().chunked(PATHS_PER_REQUEST)) {
                val response = api().checkGalleryPaths("${prefix()}/check_path_exists", GalleryPathsRequestDto(chunk))
                if (!response.isSuccessful) return@launch // an older IIB, or it could not tell: nothing is marked
                val body = response.body().orEmpty()
                chunk.filterTo(missing) { body[it] == false }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            favoritesCheckedAt = 0L
            return@launch
        }
        _missingFavorites.value = missing
    }
}

// Usuwa z ulubionych obrazy, których już nie ma na serwerze.

/** Removes the favorites whose files are gone from the server. */
fun ForgeGalleryManager.removeMissingFavorites() {
    managerScope.launch {
        val gone = _missingFavorites.value.filter { it in _favoritePaths.value }
        if (gone.isNotEmpty()) {
            getDb().favoriteImageDao().deleteFavorites(gone)
            _favoritePaths.update { it - gone.toSet() }
            favoriteItems.update { files -> files.filterNot { it.fullpath in gone } }
        }
        _missingFavorites.value = emptySet()
        ForgeRepository.showToast("Removed ${gone.size} ${if (gone.size == 1) "favorite" else "favorites"}")
    }
}
