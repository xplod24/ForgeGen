package com.example.forgegen

import android.util.Log
import com.example.forgegen.ForgeGalleryManager.GalleryException
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.IOException
import java.util.Date

/* ============================================================================
 * GALERIA: SYNCHRONIZACJA INDEKSU (3.6.1, podział ForgeGalleryManager na pliki)
 *
 * Co tu jest: utrzymywanie indeksu galerii na telefonie (baza Room) w zgodzie z obrazami na serwerze, bez
 * pobierania samych obrazów: serwer (IIB) czyta dane generowania i wysyła tylko tekst, po 100 obrazów naraz.
 *
 * Jak to działa:
 * - requestSync() uruchamia jedną synchronizację naraz (nie częściej niż co 30 s, chyba że przyszły nowe obrazy),
 * - doSync() listuje foldery (tylko zmienione i świeże; raz w tygodniu wszystkie), usuwa z indeksu obrazy, których
 *   już nie ma, czyta dane nowych obrazów i dopisuje zmiany do listy w pamięci (mergeIndex, 3.6.0-1),
 * - obraz, którego danych nie da się odczytać, ma 3 próby na jedno uruchomienie aplikacji,
 * - fillDetails() doczytuje szczegóły (rozmiar, kroki, CFG, ...) obrazów zindeksowanych przed 3.6.0.
 *
 * Do poczytania: Room (baza SQLite na Androidzie, @Dao, @Query), korutyny (suspend, ensureActive, delay),
 * StateFlow.update (bezpieczna zmiana wartości z wielu wątków), @Synchronized, ConcurrentHashMap.
 *
 * Funkcje w tym pliku są funkcjami rozszerzającymi obiektu ForgeGalleryManager ("fun ForgeGalleryManager.x()"):
 * wywołuje się je tak samo jak jego własne (ForgeGalleryManager.x()) i mają dostęp do jego stanu oznaczonego
 * jako internal. Sam stan (listy, flagi, zadania w tle) jest w ForgeGalleryManager.kt.
 * ============================================================================ */

// Wczytuje cały indeks z bazy do pamięci (przy starcie, po przeniesieniu i usunięciu plików).

/** Reloads the index into memory, the newest first (the database sorts it, 3.4.0). */
internal suspend fun ForgeGalleryManager.reloadIndex() {
    synchronized(promptCache) { promptCache.clear() } // an image read again may have its prompt now
    try {
        indexedImages.value = getDb().galleryImageDao().getIndexedImages()
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.e(TAG, "Failed to load the gallery index", e)
    }
}

// Wpisuje zmiany jednej synchronizacji do indeksu w pamięci, bez czytania całej bazy (3.6.0-1).

/** A sync's changes put into the index in memory (3.6.0-1), as [reloadIndex] would read them from the database. */
internal fun ForgeGalleryManager.mergeIndex(
    removed: List<String>,
    added: List<GalleryImageEntity>,
    sizes: List<GalleryImageSize>,
) {
    synchronized(promptCache) {
        removed.forEach { promptCache.remove(it) }
        added.forEach { promptCache.remove(it.fullpath) } // an image read again may have its prompt now
    }
    val bytes = sizes.associate { it.fullpath to it.size }
    indexedImages.update { GalleryIndex.merge(it, removed, added.map(GalleryIndex::of), bytes) }
}

// Przy otwarciu galerii: aktualizacja indeksu (pominięta, gdy była przed chwilą i nie ma nowych obrazów).
// --- INDEX SYNC (automatic) ---

/** When the gallery opens: an update of the index, skipped if one ran moments ago and no new images came since. */
fun ForgeGalleryManager.autoSyncGallery() = requestSync(throttle = !missedNewImages)

// Uruchamia synchronizację indeksu, jedną naraz; prośba w trakcie innej uruchomi następną po niej.
// Synchronized: asked for from the screen, the server check and the new-image watcher at once, it must still
// start only one sync.
@Synchronized
internal fun ForgeGalleryManager.requestSync(throttle: Boolean = false) {
    val root = readyRoot() ?: return
    if (syncJob?.isActive == true) {
        resyncRequested = true
        return
    }
    val now = System.currentTimeMillis()
    if (throttle && now - lastAutoSyncAt < AUTO_SYNC_INTERVAL_MS) return
    lastAutoSyncAt = now
    missedNewImages = false
    syncJob = managerScope.launch { runSync(root) }
}

// Wynik jednej synchronizacji (ile nowych, usuniętych, nieodczytanych, zapisanych); tylko do logu.
internal data class SyncResult(
    val added: Int = 0,
    val removed: Int = 0,
    val failed: Int = 0,
    val savedToPhone: Int = 0,
    val error: String? = null,
) {
    val message: String
        get() {
            if (error != null) return "Indexing failed: $error"
            val parts =
                listOfNotNull(
                    "$added new".takeIf { added > 0 },
                    "$removed removed".takeIf { removed > 0 },
                    "$failed without readable generation data".takeIf { failed > 0 },
                    "$savedToPhone saved to the phone".takeIf { savedToPhone > 0 },
                )
            return if (parts.isEmpty()) "Gallery index is up to date" else "Gallery indexed: " + parts.joinToString(", ")
        }
}

// Jedna synchronizacja: wybiera pełną (raz w tygodniu) albo zwykłą, zapisuje wynik do logu.
internal suspend fun ForgeGalleryManager.runSync(root: String) {
    _isIndexing.value = true
    _gallerySyncProgress.value = 0 to 0
    val result =
        try {
            // Once a week every folder is listed, which also forgets images deleted in folders that look unchanged.
            val lastFull =
                getDb()
                    .appSettingDao()
                    .getSetting(FULL_SYNC_AT_KEY)
                    ?.value
                    ?.toLongOrNull() ?: 0L
            val full = System.currentTimeMillis() - lastFull >= FULL_SYNC_INTERVAL_MS
            doSync(root, full).also {
                if (full) getDb().appSettingDao().putSetting(AppSettingEntity(FULL_SYNC_AT_KEY, System.currentTimeMillis().toString()))
            }
        } catch (e: CancellationException) {
            _isIndexing.value = false
            throw e
        } catch (e: Exception) {
            // A dropped connection used to escape from here and close the app.
            Log.e(TAG, "Gallery sync failed", e)
            SyncResult(error = e.message ?: e.javaClass.simpleName)
        }
    _indexError.value = result.error
    lastSyncMessage.value = result.message
    Log.d(TAG, result.message)
    _isIndexing.value = false
    syncsDone.incrementAndGet()
    if (resyncRequested) {
        resyncRequested = false
        currentCoroutineContext().job.invokeOnCompletion { requestSync() }
    }
}

// Wynik listowania folderów: znalezione obrazy, wylistowane i usunięte foldery, daty folderów.
internal class Listing(
    val files: List<GalleryItem>,
    val listedFolders: Set<String>,
    val removedFolders: Set<String>,
    val folderDates: MutableMap<String, String>,
)

// Właściwa synchronizacja: listowanie folderów, usuwanie starych, czytanie nowych, zmiany w pamięci.
internal suspend fun ForgeGalleryManager.doSync(
    root: String,
    full: Boolean,
): SyncResult {
    val dao = getDb().galleryImageDao()
    // The paths from the index in memory once it is loaded (3.6.0-1), else only the paths from the database (the
    // whole index used to be read here, prompts included).
    val indexed = if (_indexLoaded.value) indexedImages.value.map { it.fullpath } else dao.getAllPaths()
    val indexedPaths = indexed.toHashSet()
    val unread = dao.getUnreadPaths().toHashSet()

    // 1. Find the images.
    val listing = listTree(root, if (full) emptyMap() else loadFolderDates())

    // 2. Forget images that are gone: from every listed folder, or anywhere in the gallery on a full sync.
    val present = listing.files.mapTo(HashSet()) { norm(it.fullpath) }
    val stale =
        indexed.filter { path ->
            isUnder(path, root) &&
                norm(path) !in present &&
                (
                    full ||
                        parentOf(path) in listing.listedFolders ||
                        listing.removedFolders.any { isUnder(path, it) }
                )
        }
    stale.chunked(500).forEach { dao.deleteImages(it) } // SQLite limits the number of parameters

    // 3. Read the generation data of the new images (and of those it could not be read for before, at most
    // MAX_INFO_TRIES times in a run of the app), 100 per request. An image whose data cannot be read is indexed
    // anyway, so "All Images" shows every image.
    val newFiles =
        listing.files.filter {
            it.fullpath !in indexedPaths || (it.fullpath in unread && (infoTries[it.fullpath] ?: 0) < MAX_INFO_TRIES)
        }
    val reader = InfoReader()
    val failedFolders = HashSet<String>()
    val inserted = ArrayList<GalleryImageEntity>()
    var failed = 0
    var done = 0
    _gallerySyncProgress.value = 0 to newFiles.size
    for (chunk in newFiles.chunked(INFO_BATCH_SIZE)) {
        currentCoroutineContext().ensureActive()
        val infos = reader.read(chunk)
        val entities =
            chunk.map { file ->
                infos[file.fullpath]?.let { toEntity(file, it) } ?: run {
                    failed++
                    // Its folder is listed again next time, for another try, until the tries run out.
                    val tries = (infoTries[file.fullpath] ?: 0) + 1
                    infoTries[file.fullpath] = tries
                    if (tries < MAX_INFO_TRIES) failedFolders += parentOf(file.fullpath)
                    unreadEntity(file)
                }
            }
        dao.insertImages(entities)
        inserted += entities
        done += chunk.size
        _gallerySyncProgress.value = done to newFiles.size
    }

    // The details of images indexed before 3.6.0, read once more (never IIB's own index under /db/).
    fillDetails(dao, reader)

    // Sizes of images indexed before the index kept them (3.2.0): the listing tells them.
    val knownSizes = indexedImages.value.associate { it.fullpath to it.size }
    val sizes =
        listing.files.mapNotNull { file ->
            val bytes = file.bytes ?: 0L
            if (bytes > 0 && knownSizes[file.fullpath] == 0L) GalleryImageSize(file.fullpath, bytes) else null
        }
    sizes.chunked(500).forEach { dao.updateSizes(it) }

    // Folders with unreadable images are listed again next time, so those images get another try.
    saveFolderDates(listing.folderDates.filterKeys { it !in failedFolders })
    // Only when something changed (3.4.0): each quiet sync used to read the whole index again. 3.6.0-1: the changes
    // go into the index in memory; it is read from the database only while it has not been loaded yet.
    if (stale.isNotEmpty() || inserted.isNotEmpty() || sizes.isNotEmpty()) {
        if (_indexLoaded.value) mergeIndex(stale, inserted, sizes) else reloadIndex()
    }

    val saved = if (isAutoSavingAll()) autoSaveNewImages(root) else 0
    val added = newFiles.count { it.fullpath !in indexedPaths }
    return SyncResult(added = added, removed = stale.size, failed = failed, savedToPhone = saved)
}

// Przechodzi foldery galerii; pomija te, których data się nie zmieniła (chyba że są świeże).

/**
 * Walks the gallery's folders. Without [known] dates every folder is listed; otherwise folders whose date
 * (changed when files are added or removed) is the same as last time are skipped unless they are recent,
 * so a quiet sync only asks for the folders new images can be in.
 */
internal suspend fun ForgeGalleryManager.listTree(
    root: String,
    known: Map<String, String>,
): Listing {
    val files = ArrayList<GalleryItem>()
    val listed = HashSet<String>()
    val removed = HashSet<String>()
    val dates = HashMap<String, String>()
    val recent = serverDateFormat().format(Date(System.currentTimeMillis() - RECENT_FOLDER_MS))
    val queue = ArrayDeque<Pair<String, Int>>().apply { add(root to 0) }

    while (queue.isNotEmpty()) {
        val (folder, depth) = queue.removeFirst()
        currentCoroutineContext().ensureActive()
        val items = listFolder(folder)
        val folderKey = norm(folder)
        listed += folderKey
        files += items.filter { !it.isDir && isImage(it.name) }

        val subfolders = items.filter { it.isDir }
        val seen = subfolders.mapTo(HashSet()) { norm(it.fullpath) }
        known.keys.filter { parentOf(it) == folderKey && it !in seen }.forEach { removed += it }
        if (depth >= MAX_FOLDER_DEPTH) continue

        for (dir in subfolders) {
            val key = norm(dir.fullpath)
            val date = dir.date.orEmpty()
            dates[key] = date
            val unchanged = date.isNotEmpty() && known[key] == date && date < recent
            if (unchanged) {
                dates.putAll(known.filterKeys { isUnder(it, key) }) // keep what is known about its subfolders
            } else {
                queue.add(dir.fullpath to depth + 1)
            }
        }
    }
    return Listing(files, listed, removed, dates)
}

// Daty folderów zapamiętane po ostatniej synchronizacji.
internal suspend fun ForgeGalleryManager.loadFolderDates(): Map<String, String> =
    try {
        val json = getDb().appSettingDao().getSetting(FOLDER_DATES_KEY)?.value
        if (json.isNullOrEmpty()) {
            emptyMap()
        } else {
            gson.fromJson<Map<String, String>>(json, object : TypeToken<Map<String, String>>() {}.type) ?: emptyMap()
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        emptyMap()
    }

// Zapisuje daty folderów do następnej synchronizacji.
internal suspend fun ForgeGalleryManager.saveFolderDates(dates: Map<String, String>) {
    getDb().appSettingDao().putSetting(AppSettingEntity(FOLDER_DATES_KEY, gson.toJson(dates)))
}

// Wiersz indeksu z obrazu i jego danych generowania (prompt, model, LoRA, szczegóły).
internal fun ForgeGalleryManager.toEntity(
    file: GalleryItem,
    text: String,
): GalleryImageEntity {
    val info = Infotext.parse(text)
    return GalleryImageEntity(
        fullpath = file.fullpath,
        name = file.name,
        date = file.date.orEmpty(),
        positivePrompt = info.positivePrompt,
        negativePrompt = info.negativePrompt,
        model = info.model,
        sampler = info.sampler,
        seed = info.seed,
        loras = info.loras.joinToString(","),
        savedAt = System.currentTimeMillis(),
        size = file.bytes ?: 0L,
    ).withDetails(IndexDetails.of(info))
}

// Doczytuje szczegóły obrazów zindeksowanych przed 3.6.0, po 100, z przerwami, nigdy podczas generowania.

/**
 * Reads the details of images indexed before 3.6.0 once more, 100 per request with a pause between requests, so the
 * PC (which reads the files) is not kept busy. It stops while a job runs and goes on with the next sync. An image
 * the extension cannot read any more keeps empty details: it is not asked for again.
 */
internal suspend fun ForgeGalleryManager.fillDetails(
    dao: GalleryImageDao,
    reader: InfoReader,
) {
    var remaining = dao.countDetailsBacklog(IndexDetails.VERSION)
    _detailsBacklog.value = remaining
    while (remaining > 0 && !ForgeQueueManager.isGenerating.value) {
        currentCoroutineContext().ensureActive()
        val rows = dao.getDetailsBacklog(IndexDetails.VERSION, INFO_BATCH_SIZE)
        if (rows.isEmpty()) break
        val files = rows.map { GalleryItem(name = it.name, fullpath = it.fullpath, type = "file", date = it.date, bytes = it.size) }
        val infos = reader.read(files)
        dao.updateDetails(
            rows.map { row ->
                val details = infos[row.fullpath]?.let { IndexDetails.of(Infotext.parse(it)) } ?: IndexDetails()
                GalleryImageDetails.of(row.fullpath, details)
            },
        )
        remaining = dao.countDetailsBacklog(IndexDetails.VERSION)
        _detailsBacklog.value = remaining
        if (remaining > 0) delay(DETAILS_PAUSE_MS)
    }
}

// Wiersz indeksu dla obrazu bez odczytanych danych (pokazany z nazwą i datą, odczyt ponowiony później).

/** An image whose generation data could not be read: shown with its name and date, read again by later syncs. */
internal fun ForgeGalleryManager.unreadEntity(file: GalleryItem) =
    GalleryImageEntity(
        fullpath = file.fullpath,
        name = file.name,
        date = file.date.orEmpty(),
        positivePrompt = "",
        negativePrompt = "",
        model = "",
        sampler = "",
        seed = "",
        loras = "",
        savedAt = INFO_NOT_READ,
        size = file.bytes ?: 0L,
    )

// Czyta dane generowania najtańszym sposobem, na jaki pozwala wersja IIB: 100 naraz, po jednym albo z pliku.

/**
 * Reads generation data the cheapest way the server's IIB version allows: 100 images per request, else one
 * request per image, else (very old versions) the start of each image file. Images missing from the result
 * could not be read; an empty text means the image has no generation data.
 */
internal class InfoReader {
    private enum class Source { BATCH, SINGLE, FILE }

    private var source = Source.BATCH

    suspend fun read(files: List<GalleryItem>): Map<String, String> {
        val result = HashMap<String, String>()
        var remaining = files

        if (source == Source.BATCH) {
            val response =
                ForgeGalleryManager.api().getGalleryGenInfoBatch(
                    "${ForgeGalleryManager.prefix()}/image_geninfo_batch",
                    GalleryPathsRequestDto(files.map { it.fullpath }),
                )
            when {
                response.isSuccessful -> {
                    val body = response.body().orEmpty()
                    files.filter { body.containsKey(it.fullpath) }.forEach { result[it.fullpath] = body[it.fullpath].orEmpty() }
                    return result
                }
                response.code() in UNSUPPORTED -> source = Source.SINGLE
                else -> throw GalleryException("Server returned Error ${response.code()}")
            }
        }

        if (source == Source.SINGLE) {
            for (file in files) {
                val text = ForgeGalleryManager.serverGenInfo(file.fullpath)
                if (text == null) {
                    source = Source.FILE
                    break
                }
                result[file.fullpath] = text
            }
            remaining = files.filter { it.fullpath !in result }
        }

        if (source == Source.FILE) {
            coroutineScope {
                remaining.chunked(4).forEach { group ->
                    group
                        .map { file ->
                            async {
                                try {
                                    file.fullpath to ForgeGalleryManager.infoFromImageFile(file)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Log.w(ForgeGalleryManager.TAG, "Cannot read ${file.fullpath}: ${e.message}")
                                    null
                                }
                            }
                        }.awaitAll()
                        .filterNotNull()
                        .forEach { (path, text) -> result[path] = text }
                }
            }
        }
        return result
    }

    companion object {
        // Older IIB versions do not know the endpoint (404 / 405) or its body (422).
        private val UNSUPPORTED = setOf(404, 405, 422)
    }
}

// Dane generowania jednego obrazu od serwera (starsze IIB bez odczytu wsadowego).

/** Generation data read by IIB on the server; null when this IIB version has no such endpoint. */
internal suspend fun ForgeGalleryManager.serverGenInfo(path: String): String? {
    val response = api().getGalleryGenInfo("${prefix()}/image_geninfo", path)
    if (response.code() == 404 || response.code() == 405) return null
    if (!response.isSuccessful) throw GalleryException("Server returned Error ${response.code()}")
    val body = response.body()?.string().orEmpty()
    // FastAPI sends the text as a JSON string.
    return try {
        gson.fromJson(body, String::class.java) ?: ""
    } catch (_: Exception) {
        body
    }
}

// Dane generowania z samego pliku obrazu (najstarsze IIB): pobiera początek pliku PNG.

/** Generation data from the start of the image file; the connection is closed after the PNG text chunks. */
internal suspend fun ForgeGalleryManager.infoFromImageFile(item: GalleryItem): String {
    val request = Request.Builder().url(getGalleryImageUrl(item)).build()
    networkManager.client.newCall(request).awaitResponse().use { response ->
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        return extractPngParameters(response.body.byteStream())
    }
}
