package com.example.forgegen

import com.example.forgegen.ForgeGalleryManager.Extension
import com.example.forgegen.ForgeGalleryManager.FolderListing
import com.example.forgegen.ForgeGalleryManager.GalleryException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/* ============================================================================
 * GALERIA: PRZEGLĄDANIE FOLDERÓW (3.6.1, podział ForgeGalleryManager na pliki)
 *
 * Co tu jest: otwieranie galerii, przełączanie zakładek (Gallery, Favorites, All Images), wczytywanie folderu
 * z serwera i nawigacja po folderach (pasek ścieżki, przycisk wstecz), która nigdy nie wychodzi poza górny folder.
 *
 * Jak to działa: fetchGalleryFolder() pyta serwer o zawartość folderu; jeśli w międzyczasie otwarto inny folder,
 * spóźniona odpowiedź jest pomijana (licznik folderRequest), żeby lista nie pokazała złego folderu.
 *
 * Do poczytania: Job i cancel() w korutynach (przerywanie poprzedniego wczytywania), AtomicInteger.
 *
 * Funkcje w tym pliku są funkcjami rozszerzającymi obiektu ForgeGalleryManager ("fun ForgeGalleryManager.x()"):
 * wywołuje się je tak samo jak jego własne (ForgeGalleryManager.x()) i mają dostęp do jego stanu oznaczonego
 * jako internal. Sam stan (listy, flagi, zadania w tle) jest w ForgeGalleryManager.kt.
 * ============================================================================ */

// Otwiera galerię w trybie zwykłym albo wyboru promptu, na ostatnio używanej zakładce i folderze.
// --- BROWSING ---

/**
 * Opens the gallery on the tab used last (the prompt picker on All Images), in the folder that was open last if
 * it is still in the gallery, else its top folder. While the server's extension is not found (yet) nothing is
 * listed: the screen shows why, and opens the top folder once the extension is there.
 */
fun ForgeGalleryManager.openGallery(mode: GalleryMode) {
    _galleryMode.value = mode
    _tab.value = if (mode == GalleryMode.PROMPT_PICKER) GalleryTab.ALL_IMAGES else GalleryTab.of(ForgeRepository.config.value.galleryTab)
    val root = readyRoot()
    if (root != null) {
        val last = _currentGalleryPath.value
        fetchGalleryFolder(if (last.isNotEmpty() && isInGallery(last, root)) last else root)
        return
    }
    clearFolder()
    // Asked when the app connected; an unanswered question is asked again.
    if (_extension.value.state == Extension.UNKNOWN && ForgeRepository.isConnected.value) checkExtension()
}

// Przełącza zakładkę galerii (Gallery, Favorites, All Images).

/** Another tab chosen; the gallery opens on it next time (not when it was chosen while picking a prompt). */
fun ForgeGalleryManager.selectTab(tab: GalleryTab) {
    _tab.value = tab
    val config = ForgeRepository.config.value
    if (_galleryMode.value == GalleryMode.NORMAL && config.galleryTab != tab.name) {
        ForgeSettingsManager.saveConfig(config.copy(galleryTab = tab.name))
    }
}

// Zamyka otwarty folder (pusta lista, bez błędu).
internal fun ForgeGalleryManager.clearFolder() {
    folderRequest.incrementAndGet()
    folderJob?.cancel()
    requestedPath = ""
    listing.value = FolderListing("", emptyList())
    _currentGalleryPath.value = ""
    _galleryError.value = null
    _isGalleryLoading.value = false
}

// "Refresh": wczytuje folder od nowa, czyści okładki i synchronizuje indeks.

/** Refresh (and Retry after an error): the folder asked for last again, and the index brought up to date at once. */
fun ForgeGalleryManager.refreshGallery() {
    _folderCovers.value = emptyMap() // new images may have come
    val path = requestedPath
    if (path.isEmpty()) {
        checkExtension()
        return
    }
    fetchGalleryFolder(path)
    requestSync()
}

// Wczytuje folder z serwera; spóźniona odpowiedź dla wcześniej otwartego folderu jest pomijana.

/**
 * Opens [path] in the Gallery tab. Only the gallery's own folders can be opened (2.2.0): anything outside its top
 * folder (e.g. the parent "outputs" folder, reached through the path bar before) opens the top folder instead.
 */
fun ForgeGalleryManager.fetchGalleryFolder(path: String) {
    val root = galleryRoot() ?: return
    val target = if (isInGallery(path, root)) path else root
    // Only the latest request may show its folder: a slow answer for a folder the user already left used to
    // replace the folder opened after it.
    val request = folderRequest.incrementAndGet()
    folderJob?.cancel()
    requestedPath = target
    _isGalleryLoading.value = true
    _galleryError.value = null

    folderJob =
        managerScope.launch {
            try {
                val items = listServerFolder(target, root)
                if (request != folderRequest.get()) return@launch
                if (items != null) {
                    listing.value = FolderListing(target, items)
                    _currentGalleryPath.value = target
                    requestCovers(items.filter { it.isDir }.map { it.fullpath })
                }
                _isGalleryLoading.value = false
            } catch (e: CancellationException) {
                throw e // a newer request owns the loading state
            } catch (e: Exception) {
                if (request != folderRequest.get()) return@launch
                _galleryError.value = if (e is GalleryException) e.message else "Failed to load gallery: ${e.message}"
                _isGalleryLoading.value = false
            }
        }
}

// Lista folderu z serwera (IIB /files), tylko obrazy i podfoldery.

/** The folder's images and subfolders; null when the server rejected it and the top folder is shown instead. */
internal suspend fun ForgeGalleryManager.listServerFolder(
    path: String,
    root: String,
): List<GalleryItem>? {
    val response = api().getGalleryFilesDynamic(url = "${prefix()}/files", folderPath = encodeFolderPath(path))
    return when {
        response.isSuccessful -> parseGalleryItems(response.body()?.string().orEmpty()).filter { it.isDir || isImage(it.name) }
        response.code() == 400 && !samePath(path, root) -> {
            fetchGalleryFolder(root)
            null
        }
        response.code() == 401 || response.code() == 403 -> throw GalleryException("Authentication Required.")
        else -> throw GalleryException("Server returned Error ${response.code()}")
    }
}

// Czy dwie ścieżki serwera to ten sam folder (bez względu na "/" lub "\\" i wielkość liter).
// --- THE GALLERY'S FOLDERS (the Gallery tab never leaves its top folder) ---

internal fun ForgeGalleryManager.samePath(
    a: String,
    b: String,
) = norm(a) == norm(b)

// Czy ścieżka jest górnym folderem galerii albo w nim.
internal fun ForgeGalleryManager.isInGallery(
    path: String,
    root: String,
) = samePath(path, root) || isUnder(path, root)

// Pasek ścieżki: kolejne foldery od górnego folderu galerii do otwartego.

/** The path bar of the Gallery tab: from the top folder ("Gallery") down to [path]. */
fun ForgeGalleryManager.breadcrumb(path: String): List<Pair<String, String>> = galleryRoot()?.let { breadcrumb(path, it) }.orEmpty()

// Folder wyżej (przycisk wstecz); null na górnym folderze galerii.

/** The folder above [path] inside the gallery; null in the top folder (Back then closes the gallery). */
fun ForgeGalleryManager.parentFolder(path: String): String? = galleryRoot()?.let { parentFolder(path, it) }

// Pasek ścieżki: kolejne foldery od górnego folderu galerii do otwartego.

/**
 * (name, path) of each folder from [root] (named "Gallery") to [path]. The paths are cut from [path] itself, so
 * they are written as the server writes them (its separator and letter case).
 */
internal fun ForgeGalleryManager.breadcrumb(
    path: String,
    root: String,
): List<Pair<String, String>> {
    val top = listOf(GALLERY_NAME to root)
    if (!isUnder(path, root)) return top
    val base = path.substring(0, root.trimEnd('/', '\\').length)
    val separator = path[base.length]
    val names = path.substring(base.length).split('/', '\\').filter { it.isNotEmpty() }
    return top + names.indices.map { i -> names[i] to base + separator + names.take(i + 1).joinToString(separator.toString()) }
}

// Folder wyżej (przycisk wstecz); null na górnym folderze galerii.
internal fun ForgeGalleryManager.parentFolder(
    path: String,
    root: String,
): String? {
    if (!isUnder(path, root)) return null
    val cut = maxOf(path.trimEnd('/', '\\').lastIndexOf('/'), path.trimEnd('/', '\\').lastIndexOf('\\'))
    val parent = if (cut > 0) path.substring(0, cut) else root
    return if (isUnder(parent, root)) parent else root
}
