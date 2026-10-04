// PL: stan galerii z podkreśleniem ("_x") jest internal, bo zmieniają go funkcje z plików gallery/ (3.6.1);
// ktlint chciałby, żeby taki stan był private, więc ta jedna reguła jest tu wyłączona.
@file:Suppress("ktlint:standard:backing-property-naming")

package com.example.forgegen

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.util.Log
import com.example.forgegen.ui.components.IndicatorState
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/* ============================================================================
 * GALLERY MANAGER
 * Browses the server's images through the Infinite Image Browsing (IIB) extension, keeps a local index of their
 * generation data (for searching the whole gallery and the "All Images" view), favorites, saving to the phone,
 * sharing, and restoring prompts from images.
 *
 * The gallery needs IIB on the server: when the app connects, detectExtension() looks for it and reads the folders
 * from it (Forge's working folder and its image folder, the gallery's top folder); nobody types them any more.
 *
 * The index is filled without downloading images: IIB reads the generation data on the PC and sends only the
 * text, 100 images per request. It is kept up to date on its own (2.1.0): after the extension is found, when the
 * gallery opens or is refreshed and after the app generated images. Such a sync only lists folders that changed
 * (or are recent); once a week (3.6.0-1) it lists every folder, which also forgets images deleted anywhere.
 *
 * 3.2.0: images are deleted (after a few seconds in which Undo takes it back), moved or copied to another folder
 * through the extension, where it may change files (IIB answers 403 when it may only read); folders show their
 * newest images as a cover; favorites gone from the server are found; statistics come from the index.
 *
 * PO POLSKU (3.6.1): ten plik to "serce" galerii: stan (co jest otwarte, indeks w pamięci, ulubione, filtry, zadania
 * w tle) i najmniejsze klocki (ścieżki, adresy URL, ulubione). Funkcje działające na tym stanie są podzielone na pliki
 * w folderze gallery/ (to funkcje rozszerzające tego obiektu, "fun ForgeGalleryManager.x()"):
 * - GalleryBrowsing.kt: otwieranie galerii, zakładki, wczytywanie folderów, pasek ścieżki,
 * - GallerySync.kt: synchronizacja indeksu (baza Room na telefonie) z obrazami na serwerze,
 * - GalleryFiltering.kt: filtry, sortowanie, statystyki,
 * - GalleryExtensionCheck.kt: wykrywanie rozszerzenia IIB i jego klucza,
 * - GalleryFileChanges.kt: usuwanie z Undo, przenoszenie, kopiowanie, foldery, ZIP, okładki, brakujące ulubione,
 * - GalleryImageActions.kt: zapis na telefon, pobieranie, udostępnianie, zadania z obrazów,
 * - GalleryPromptRecovery.kt: dane generowania z obrazów PNG i odzyskiwanie ostatniego promptu.
 * Stan oznaczony "internal" jest widoczny w całej aplikacji (potrzebują go te pliki); "private" tylko tutaj.
 *
 * Do poczytania: object w Kotlinie (jedna instancja na całą aplikację), MutableStateFlow/StateFlow (wartość, którą
 * ekran obserwuje), CoroutineScope + SupervisorJob (zadania w tle, których błąd nie przerywa pozostałych), Room.
 * ============================================================================ */
@SuppressLint("StaticFieldLeak")
object ForgeGalleryManager {
    internal const val TAG = "ForgeGalleryManager"

    private const val SHOW_META_KEY = "show_gallery_meta"
    internal const val FOLDER_DATES_KEY = "gallery_folder_dates"
    internal const val FULL_SYNC_AT_KEY = "gallery_full_sync_at"
    private const val THUMBNAIL_SIZE = "512x512" // three columns on a 1440 px wide screen
    internal const val INFO_BATCH_SIZE = 100

    // Between two requests for older images' details (3.6.0).
    internal const val DETAILS_PAUSE_MS = 500L
    internal const val MAX_FOLDER_DEPTH = 3
    internal const val RECENT_FOLDER_MS = 48 * 60 * 60 * 1000L // re-listed on every sync, as new images land there

    // Every folder listed once a week (3.6.0-1; daily before): a folder's date already shows images removed from it.
    internal const val FULL_SYNC_INTERVAL_MS = 7 * 24 * 60 * 60 * 1000L

    // How often one run of the app tries to read an image's generation data before it leaves the image as it is (3.6.0-1).
    internal const val MAX_INFO_TRIES = 3
    internal const val AUTO_SYNC_INTERVAL_MS = 30_000L
    private const val NEW_IMAGE_SYNC_DELAY_MS = 2_000L
    private const val PROMPT_CACHE_SIZE = 300
    internal const val LAST_FOLDER_KEY = "gallery_last_folder"
    internal const val DELETE_DELAY_MS = 6_000L
    internal const val FAVORITES_CHECK_INTERVAL_MS = 5 * 60 * 1000L
    internal const val PATHS_PER_REQUEST = 200
    internal const val PROMPT_PAGE = 2_000
    internal const val HTTP_FORBIDDEN = 403

    /** Said when the extension refuses to change files (IIB_ACCESS_CONTROL_PERMISSION=read-only on the server). */
    const val READ_ONLY_MESSAGE = "The server's gallery is read-only: Infinite Image Browsing may not change its files."
    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "avif", "gif")

    /** [GalleryImageEntity.savedAt] of an image whose generation data could not be read yet. */
    internal const val INFO_NOT_READ = 0L

    /** Where Forge saves txt2img images unless its settings say otherwise (relative to its working folder). */
    internal const val DEFAULT_OUTPUT_FOLDER = "outputs/txt2img-images"

    /** The extension's page, for the message shown when a server does not have it. */
    const val EXTENSION_URL = "https://github.com/zanllp/sd-webui-infinite-image-browsing"

    internal lateinit var application: Application
    internal lateinit var getDb: () -> ForgeDatabase
    internal lateinit var networkManager: ForgeNetworkManager
    internal val gson = Gson()
    internal val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** A folder of the gallery as the server listed it: set at once, so the list never shows under another path. */
    internal data class FolderListing(
        val path: String,
        val items: List<GalleryItem>,
    )

    internal val listing = MutableStateFlow(FolderListing("", emptyList()))

    internal val _currentGalleryPath = MutableStateFlow("")
    val currentGalleryPath: StateFlow<String> = _currentGalleryPath.asStateFlow()

    /** The open tab; the gallery opens on the one used last (the prompt picker on All Images). */
    internal val _tab = MutableStateFlow(GalleryTab.GALLERY)
    val tab: StateFlow<GalleryTab> = _tab.asStateFlow()

    internal val _isGalleryLoading = MutableStateFlow(false)
    val isGalleryLoading: StateFlow<Boolean> = _isGalleryLoading.asStateFlow()

    internal val _galleryError = MutableStateFlow<String?>(null)
    val galleryError: StateFlow<String?> = _galleryError.asStateFlow()

    private val _showGalleryMetadata = MutableStateFlow(false)
    val showGalleryMetadata: StateFlow<Boolean> = _showGalleryMetadata.asStateFlow()

    internal val _currentImageMetadata = MutableStateFlow<String?>(null)
    val currentImageMetadata: StateFlow<String?> = _currentImageMetadata.asStateFlow()

    internal val _galleryMode = MutableStateFlow(GalleryMode.NORMAL)
    val galleryMode: StateFlow<GalleryMode> = _galleryMode.asStateFlow()

    internal val _favoritePaths = MutableStateFlow<Set<String>>(emptySet())
    val favoritePaths: StateFlow<Set<String>> = _favoritePaths.asStateFlow()

    // The favorites as images (the Favorites tab), newest starred first like the database keeps them.
    internal val favoriteItems = MutableStateFlow<List<GalleryItem>>(emptyList())

    // --- The server's gallery extension ---
    // PL: stan rozszerzenia IIB na serwerze (czy jest, czy chce klucza) i adres, pod którym odpowiada.

    /** Whether the server has the Infinite Image Browsing extension the gallery needs. */
    enum class Extension {
        /** Not asked yet (not connected), or the question got no answer. */
        UNKNOWN,
        CHECKING,

        /** Found, and the gallery's folder was read from it. */
        READY,

        /** The server answered, but has no gallery extension. */
        MISSING,

        /** The extension answered with an error, or without its folders. */
        FAILED,

        /** The extension asks for its secret key, or refused the one saved for this server (GalleryKey, 3.5.0). */
        LOCKED,

        /** Forge has a login and the extension no secret key, so it refuses everything (GalleryKey, 3.5.0). */
        KEY_NOT_SET,
    }

    data class ExtensionStatus(
        val state: Extension,
        val message: String? = null,
        // False when the extension may only read (3.2.0): deleting, moving and copying are then not offered.
        val writable: Boolean = true,
    ) {
        val canWrite: Boolean get() = state == Extension.READY && writable
    }

    internal val _extension = MutableStateFlow(ExtensionStatus(Extension.UNKNOWN))
    val extension: StateFlow<ExtensionStatus> = _extension.asStateFlow()

    // The extension's URL prefix on the server (older builds of it used other names).
    @Volatile internal var apiPrefix = ForgeSettingsManager.GALLERY_PREFIXES.first()

    internal val detection = Mutex()

    // --- Index sync state ---
    // PL: stan synchronizacji indeksu, zadania w tle (wczytywanie folderu, metadanych, synchronizacja) i ich liczniki.

    /** True while the index is being updated (a thin progress bar in the gallery). */
    internal val _isIndexing = MutableStateFlow(false)
    val isIndexing: StateFlow<Boolean> = _isIndexing.asStateFlow()

    internal val _gallerySyncProgress = MutableStateFlow(0 to 0)
    val gallerySyncProgress: StateFlow<Pair<Int, Int>> = _gallerySyncProgress.asStateFlow()

    /** Why the last update of the index failed; null when it worked. Shown quietly in the search panel. */
    internal val _indexError = MutableStateFlow<String?>(null)
    val indexError: StateFlow<String?> = _indexError.asStateFlow()

    /** Updates of the index that finished (tests wait for them). */
    internal val syncsDone = AtomicInteger(0)

    // The index in memory without the prompts (most of its size); a prompt search asks the database.
    internal val indexedImages = MutableStateFlow<List<IndexedImage>>(emptyList())

    internal val _isRestoringPrompt = MutableStateFlow(IndicatorState.IDLE)
    val isRestoringPrompt: StateFlow<IndicatorState> = _isRestoringPrompt.asStateFlow()

    internal var restoreJob: Job? = null
    internal var folderJob: Job? = null

    // The folder asked for last (shown once it loads): Refresh and Retry ask for it again.
    @Volatile internal var requestedPath = ""
    internal val folderRequest = AtomicInteger(0)
    internal var metadataJob: Job? = null

    internal var syncJob: Job? = null

    // Set by a sync asked for while another one runs, read when that one ends.
    @Volatile internal var resyncRequested = false

    @Volatile internal var lastAutoSyncAt = 0L

    // The images whose generation data could not be read, with how many times this run of the app tried (3.6.0-1).
    internal val infoTries = ConcurrentHashMap<String, Int>()

    // --- Changing files (3.2.0) ---
    // PL: stan zmian plików: usuwanie czekające na Undo, okładki folderów, brakujące ulubione, ostatni folder.

    /** Images deleted a moment ago, which Undo still brings back; the server deletes them when the time is up. */
    class PendingDelete(
        val items: List<GalleryItem>,
    ) {
        val paths: Set<String> = items.mapTo(HashSet()) { it.fullpath }

        // 0: waiting, 1: sent to the server, 2: undone. Undo and the timer may meet; only one of them wins.
        private val state = AtomicInteger(0)

        internal fun send() = state.compareAndSet(0, 1)

        internal fun undo() = state.compareAndSet(0, 2)
    }

    internal val _pendingDelete = MutableStateFlow<PendingDelete?>(null)
    val pendingDelete: StateFlow<PendingDelete?> = _pendingDelete.asStateFlow()

    // Deleted images the lists leave out: from the tap on Delete until the server's answer (or Undo).
    internal val hiddenPaths = MutableStateFlow<Set<String>>(emptySet())
    internal var deleteTimer: Job? = null
    internal val deleteLock = Any()

    /** The newest images of each folder, for its cover (keys: [GalleryPaths.key]); an empty list: none there. */
    internal val _folderCovers = MutableStateFlow<Map<String, List<GalleryItem>>>(emptyMap())
    val folderCovers: StateFlow<Map<String, List<GalleryItem>>> = _folderCovers.asStateFlow()

    @Volatile internal var coversSupported = true

    /** Favorites whose files are no longer on the server, as the last check found them. */
    internal val _missingFavorites = MutableStateFlow<Set<String>>(emptySet())
    val missingFavorites: StateFlow<Set<String>> = _missingFavorites.asStateFlow()

    @Volatile internal var favoritesCheckedAt = 0L

    /** The folder images were moved or copied to last, offered first next time. */
    internal val _lastFolder = MutableStateFlow<String?>(null)
    val lastFolder: StateFlow<String?> = _lastFolder.asStateFlow()

    internal val _allImagesOrder = MutableStateFlow(AllImagesOrder())
    val allImagesOrder: StateFlow<AllImagesOrder> = _allImagesOrder.asStateFlow()

    // --- FILTERING ---
    // PL: filtry i listy do pokazania (folder, ulubione, All Images); liczą się na nowo, gdy zmieni się indeks lub filtry.
    enum class SortOrder { NEWEST, OLDEST, NAME_ASC, NAME_DESC }

    data class GalleryFilters(
        val models: Set<String> = emptySet(),
        val loras: Set<String> = emptySet(),
        val lorasIsAnd: Boolean = false, // false = any of the LoRAs, true = all of them
        val name: String = "",
        // Tags the positive and the negative prompt must hold, all of them (3.6.2-1: before, one text in either).
        val positiveTags: List<String> = emptyList(),
        val negativeTags: List<String> = emptyList(),
        // Whole tags only ("cat" no longer finds "catgirl"), compared as the statistics count them (TagFilter).
        val exactTags: Boolean = false,
        val sortOrder: SortOrder = SortOrder.NEWEST,
        // A setting the statistics opened the gallery with (3.6.0), shown as one chip.
        val detail: GalleryDetailFilter? = null,
    ) {
        /** A search looks through the whole indexed gallery instead of the open folder. */
        val isSearch: Boolean
            get() =
                name.isNotBlank() ||
                    positiveTags.isNotEmpty() ||
                    negativeTags.isNotEmpty() ||
                    models.isNotEmpty() ||
                    loras.isNotEmpty() ||
                    detail != null
    }

    internal val _galleryFilters = MutableStateFlow(GalleryFilters())
    val galleryFilters: StateFlow<GalleryFilters> = _galleryFilters.asStateFlow()

    // The gallery's top folder as the settings have it: the index under it is made again when it changes.
    internal val galleryPath = ForgeRepository.config.map { it.galleryPath }.distinctUntilChanged()

    /**
     * The index under the gallery's top folder (3.4.0). The counts and filter lists below are made from it only while
     * the gallery shows them; they used to be made again after every sync, also with the gallery closed.
     */
    private val inGalleryIndex: Flow<List<IndexedImage>> =
        combine(indexedImages, galleryPath) { index, _ ->
            val root = galleryRoot()?.let { norm(it) }
            if (root == null) index else index.filter { isUnderNormalized(it.fullpath, root) }
        }.shareIn(managerScope, SharingStarted.WhileSubscribed(5000), replay = 1)

    val indexedImageCount: StateFlow<Int> =
        inGalleryIndex.map { it.size }.stateIn(managerScope, SharingStarted.WhileSubscribed(5000), 0)

    /** How many indexed images each folder holds, its subfolders included (keys: [GalleryPaths.key]). */
    val folderImageCounts: StateFlow<Map<String, Int>> =
        inGalleryIndex
            .map { inGallery -> galleryRoot()?.let { GalleryFolders.imageCounts(inGallery, it) }.orEmpty() }
            .stateIn(managerScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** The search panel's lists: only the models and LoRAs of the current gallery. */
    val availableModels: StateFlow<List<String>> =
        inGalleryIndex
            .map { inGallery ->
                inGallery
                    .map { it.model }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .sorted()
            }.stateIn(managerScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val availableLoras: StateFlow<List<String>> =
        inGalleryIndex
            .map { inGallery ->
                inGallery
                    .flatMap { it.loras.split(",") }
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .sorted()
            }.stateIn(managerScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** The search as it was computed: its results (null without a search) and the index under the top folder. */
    internal class Search(
        val filters: GalleryFilters,
        val hits: List<IndexedImage>?,
        val inGallery: List<IndexedImage>,
    )

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    internal val search: StateFlow<Search> =
        combine(_galleryFilters, indexedImages, galleryPath) { filters, index, _ -> filters to index }
            .mapLatest { (filters, index) ->
                val root = galleryRoot()?.let { norm(it) }
                val inGallery = if (root == null) index else index.filter { isUnderNormalized(it.fullpath, root) }
                val hits =
                    if (filters.isSearch) {
                        val promptHits = tagMatches(filters)
                        val detailHits = filters.detail?.let { detailMatches(it) }
                        inGallery.filter { matches(it, filters, promptHits) && (detailHits == null || it.fullpath in detailHits) }
                    } else {
                        null
                    }
                Search(filters, hits, inGallery)
            }.stateIn(managerScope, SharingStarted.WhileSubscribed(5000), Search(GalleryFilters(), null, emptyList()))

    /**
     * What the Gallery tab shows: the open folder, or the search's results in the whole gallery. [filters] are the
     * ones it was made with (the screen scrolls a tab back to the top when a list made with other filters arrives).
     */
    data class FolderView(
        val path: String,
        val items: List<GalleryItem>,
        val filters: GalleryFilters,
    ) {
        val isSearch: Boolean get() = filters.isSearch
    }

    /** What the Favorites and All Images tabs show, with the filters (and the All Images order) it was made with. */
    data class ImagesView(
        val items: List<GalleryItem>,
        val filters: GalleryFilters,
        val order: AllImagesOrder = AllImagesOrder(),
    )

    // A stopped flow keeps its last value (WhileSubscribed keeps the replay), so a reopened gallery shows its lists at
    // once and its tabs return to where they were scrolled.
    val folderView: StateFlow<FolderView> =
        combine(listing, search, hiddenPaths) { folder, found, hidden ->
            val hits = found.hits
            val items = if (hits != null) hits.map { it.toGalleryItem() } else folder.items
            FolderView(folder.path, sortItems(items.withoutPaths(hidden), found.filters.sortOrder), found.filters)
        }.stateIn(managerScope, SharingStarted.WhileSubscribed(5000), FolderView("", emptyList(), GalleryFilters()))

    /** The Favorites tab: every favorite (the search narrows them), in the sort panel's order. */
    val favoriteImages: StateFlow<ImagesView> =
        combine(favoriteItems, search, hiddenPaths) { favorites, found, hidden ->
            val hits = found.hits?.mapTo(HashSet()) { it.fullpath }
            val shown = if (hits == null) favorites else favorites.filter { it.fullpath in hits }
            ImagesView(sortItems(shown.withoutPaths(hidden), found.filters.sortOrder), found.filters)
        }.stateIn(managerScope, SharingStarted.WhileSubscribed(5000), ImagesView(emptyList(), GalleryFilters()))

    /** The All Images tab: every image of the gallery (or the search's results), the newest first or shuffled. */
    val allImages: StateFlow<ImagesView> =
        combine(search, _allImagesOrder, hiddenPaths) { found, order, hidden ->
            // The index comes the newest first from the database (3.4.0), and a search keeps its order.
            val newest = (found.hits ?: found.inGallery).map { it.toGalleryItem() }
            ImagesView(order.apply(newest.withoutPaths(hidden)), found.filters, order)
        }.stateIn(managerScope, SharingStarted.WhileSubscribed(5000), ImagesView(emptyList(), GalleryFilters()))

    // PL: Lista bez podanych ścieżek (np. obrazów czekających na usunięcie).
    private fun List<GalleryItem>.withoutPaths(paths: Set<String>) = if (paths.isEmpty()) this else filterNot { it.fullpath in paths }

    // PL: Przekazuje aplikację, bazę i menedżera sieci (raz, przy starcie aplikacji).
    fun init(
        app: Application,
        database: () -> ForgeDatabase,
        network: ForgeNetworkManager,
    ) {
        application = app
        getDb = database
        networkManager = network
    }

    internal val _indexLoaded = MutableStateFlow(false)

    /** The index has been read from the database once (3.4.0: in the background, the start no longer waits for it). */
    val indexLoaded: StateFlow<Boolean> = _indexLoaded.asStateFlow()

    // PL: Start galerii: ulubione, ostatni folder, wczytanie indeksu w tle i obserwowanie nowych obrazów.
    /** Returns once the favorites are loaded; the index loads in the background, the automatic saving runs from then on. */
    suspend fun start() {
        if (!::getDb.isInitialized) {
            Log.e(TAG, "ForgeGalleryManager start called but getDb is not initialized!")
            return
        }
        withContext(Dispatchers.IO) {
            DeviceImages.clearSharedCopies(application)
            _showGalleryMetadata.value = getDb().appSettingDao().getSetting(SHOW_META_KEY)?.value?.toBoolean() ?: false
            loadFavoritePaths()
            _lastFolder.value =
                getDb()
                    .appSettingDao()
                    .getSetting(LAST_FOLDER_KEY)
                    ?.value
                    ?.takeIf { it.isNotBlank() }
        }
        // A gallery request refused for its key (3.5.0), e.g. after the key was changed on the server: the extension
        // is asked again, which locks the gallery until the new key is entered.
        managerScope.launch {
            GalleryKey.refused.collect { if (_extension.value.state == Extension.READY) checkExtension() }
        }
        // The whole index is read without holding the start screen (3.4.0); All Images shows placeholders meanwhile.
        managerScope.launch {
            reloadIndex()
            _indexLoaded.value = true
        }
        // The index follows new images: the server saved them a moment ago (the extension is found on connecting,
        // and that starts a sync too). Only while the gallery is open or all new images are saved to the phone
        // (3.4.0): a long queue used to list the gallery on the server after every batch; the gallery catches up when
        // it opens (autoSyncGallery).
        managerScope.launch {
            // The newest image, not the count: the session keeps at most 100 images, so its size stops growing.
            var newest = ForgeQueueManager.sessionImages.value.lastOrNull()
            ForgeQueueManager.sessionImages.collect { images ->
                val last = images.lastOrNull()
                if (last != null && last != newest) {
                    if (galleryVisible || isAutoSavingAll()) {
                        delay(NEW_IMAGE_SYNC_DELAY_MS)
                        requestSync()
                    } else {
                        missedNewImages = true
                    }
                }
                newest = last
            }
        }
    }

    // The gallery screen is open (3.4.0), and new images came while it was not.
    @Volatile private var galleryVisible = false

    @Volatile internal var missedNewImages = false

    // PL: Ekran galerii jest otwarty albo zamknięty (synchronizacja po nowych obrazach tylko, gdy jest otwarty).
    fun setGalleryVisible(visible: Boolean) {
        galleryVisible = visible
    }

    // PL: Czy włączony jest automatyczny zapis wszystkich nowych obrazów na telefon.
    internal fun isAutoSavingAll() = ForgeRepository.config.value.autoSaveMode == AUTO_SAVE_ALL

    // PL: Pokazuje/chowa dane generowania pod obrazem w podglądzie (zapamiętane w bazie).
    fun toggleGalleryMetadata() {
        val newVal = !_showGalleryMetadata.value
        _showGalleryMetadata.value = newVal
        managerScope.launch {
            getDb().appSettingDao().putSetting(AppSettingEntity(SHOW_META_KEY, newVal.toString()))
        }
    }

    // --- FAVORITES ---
    // PL: ulubione: wczytanie z bazy, kopia zapasowa, import, przełączanie gwiazdki, czyszczenie indeksu.

    // PL: Wczytuje ulubione z bazy do pamięci.
    internal suspend fun loadFavoritePaths() {
        val favorites =
            getDb().favoriteImageDao().getAllFavorites().map {
                GalleryItem(name = it.name, fullpath = it.fullpath, type = "file", date = it.date)
            }
        favoriteItems.value = favorites
        _favoritePaths.value = favorites.mapTo(HashSet()) { it.fullpath }
    }

    // PL: Ulubione do kopii zapasowej.
    /** The favorites as saved, for a backup (3.5.2-1). */
    suspend fun favoritesForBackup(): List<FavoriteImageEntity> = getDb().favoriteImageDao().getAllFavorites()

    // PL: Dodaje ulubione z kopii zapasowej (tylko brakujące); zwraca, ile dodano.
    /** The favorites of a backup (3.5.2-1), with their own dates; those already here stay. How many were new. */
    suspend fun importFavorites(favorites: List<FavoriteImageEntity>): Int {
        val dao = getDb().favoriteImageDao()
        val added = favorites.filter { it.fullpath !in _favoritePaths.value }.distinctBy { it.fullpath }
        if (added.isEmpty()) return 0
        for (favorite in added) dao.insertFavorite(favorite)
        loadFavoritePaths()
        return added.size
    }

    // PL: Dodaje obraz do ulubionych albo go z nich usuwa (gwiazdka).
    fun toggleFavorite(item: GalleryItem) {
        managerScope.launch {
            val dao = getDb().favoriteImageDao()
            if (dao.isFavorite(item.fullpath)) {
                dao.deleteFavorite(item.fullpath)
                _favoritePaths.update { it - item.fullpath }
                favoriteItems.update { files -> files.filterNot { it.fullpath == item.fullpath } }
            } else {
                dao.insertFavorite(FavoriteImageEntity(fullpath = item.fullpath, name = item.name, date = item.date ?: ""))
                _favoritePaths.update { it + item.fullpath }
                favoriteItems.update { files -> listOf(item.asFavorite()) + files.filterNot { it.fullpath == item.fullpath } }
                if (ForgeRepository.config.value.autoSaveMode == AUTO_SAVE_FAVORITES) saveToPhone(item, quietIfSaved = true)
            }
        }
    }

    // PL: "Wipe": czyści indeks galerii na telefonie; następna synchronizacja zbuduje go od nowa.
    fun clearDatabase() {
        managerScope.launch {
            syncJob?.cancelAndJoin()
            getDb().galleryImageDao().clearAll()
            getDb().appSettingDao().removeSetting(FOLDER_DATES_KEY) // the next sync lists every folder again
            infoTries.clear() // and tries every image anew
            reloadIndex() // empties the model/LoRA filter lists built from the index
            lastAutoSyncAt = 0L // opening the gallery builds it again at once
            ForgeRepository.showToast("Gallery Index Wiped")
        }
    }

    // --- PROMPTS FOR THE LIST VIEW ---
    // PL: prompty do widoku listy, z małą pamięcią ostatnio czytanych (indeks w pamięci nie ma promptów).

    // The positive prompts of the rows seen last (the index in memory has none), newest use last.
    internal val promptCache =
        object : LinkedHashMap<String, String>(PROMPT_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > PROMPT_CACHE_SIZE
        }

    // PL: Prompt obrazu z indeksu (do widoku listy), z pamięci podręcznej albo z bazy.
    /** The prompt an image was made with, from the index; "" when it is not indexed or has none. */
    suspend fun positivePrompt(path: String): String {
        synchronized(promptCache) { promptCache[path] }?.let { return it }
        val prompt =
            withContext(Dispatchers.IO) {
                try {
                    getDb().galleryImageDao().getPositivePrompt(path).orEmpty()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    ""
                }
            }
        synchronized(promptCache) { promptCache[path] = prompt }
        return prompt
    }

    internal val WINDOWS_DRIVE = Regex("^[A-Za-z]:[\\\\/]")

    // --- PATHS & URLS ---
    // PL: ścieżki serwera ("/" albo "\\", bez wielkości liter), adresy obrazów i miniatur IIB, lista folderu.

    // PL: Górny folder galerii (z rozszerzenia IIB) albo null, gdy jeszcze nieznany.
    /** The gallery's top folder (read from the server's extension), or null while it is not known. */
    internal fun galleryRoot(): String? =
        ForgeRepository.config.value.galleryPath
            .takeIf { it.isNotBlank() && it != "Root" }

    // PL: Górny folder galerii, ale tylko gdy rozszerzenie jest gotowe (READY).
    /** The gallery's top folder when the extension is there, null otherwise (the gallery shows why). */
    fun readyRoot(): String? = if (_extension.value.state == Extension.READY) galleryRoot() else null

    // PL: Ścieżka do porównań: tylko "/", bez "/" na końcu, małymi literami.
    // Server paths may use "\" (Windows) or "/"; compared case-insensitively.
    internal fun norm(path: String) = path.replace('\\', '/').trimEnd('/').lowercase()

    // PL: Czy ścieżka leży w folderze (albo w jego podfolderach).
    internal fun isUnder(
        path: String,
        folder: String,
    ): Boolean {
        val root = norm(folder)
        return root.isEmpty() || norm(path).startsWith("$root/")
    }

    // PL: isUnder dla folderu już znormalizowanego, bez tworzenia nowych napisów (szybkie, dla całego indeksu).
    /**
     * [isUnder] for a folder already normalized ([norm]) without building new strings: it runs for every image of
     * the index whenever the shown list changes.
     */
    internal fun isUnderNormalized(
        path: String,
        root: String,
    ): Boolean {
        if (root.isEmpty()) return true
        if (path.length <= root.length) return false
        for (i in root.indices) {
            val c = path[i].let { if (it == '\\') '/' else it.lowercaseChar() }
            if (c != root[i]) return false
        }
        val next = path[root.length]
        return next == '/' || next == '\\'
    }

    // PL: Folder, w którym leży ścieżka (znormalizowany).
    internal fun parentOf(path: String) = norm(path).substringBeforeLast('/', "")

    // PL: Czy nazwa pliku ma rozszerzenie obrazu (png, jpg, webp, ...).
    internal fun isImage(name: String) = name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

    // PL: Adres, pod którym odpowiada IIB na tym serwerze (np. /infinite_image_browsing).
    internal fun prefix() = apiPrefix

    // PL: Pełny adres zapytania do IIB z parametrami (zakodowanymi w URL).
    private fun galleryUrl(
        endpoint: String,
        vararg params: Pair<String, String>,
    ): String {
        val base =
            ForgeRepository.config.value.apiUrl
                .trimEnd('/')
                .toHttpUrlOrNull() ?: return ""
        val builder = base.newBuilder().addPathSegment(prefix()).addPathSegment(endpoint)
        params.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build().toString()
    }

    // PL: Adres pełnego obrazu na serwerze.
    // IIB requires "t" (the file's date, so a changed file is not served from a cache) even when it is empty;
    // without it the server answered 422 and favorites saved without a date never loaded.
    fun getGalleryImageUrl(item: GalleryItem): String = galleryUrl("file", "path" to item.fullpath, "t" to item.date.orEmpty())

    // PL: Adres miniatury obrazu (512x512) na serwerze.
    /** A small WebP made and cached by the server; the grid used to download every full-size PNG. */
    fun getGalleryThumbnailUrl(item: GalleryItem): String =
        galleryUrl("image-thumbnail", "path" to item.fullpath, "t" to item.date.orEmpty(), "size" to THUMBNAIL_SIZE)

    // PL: Ścieżka folderu zakodowana do adresu URL.
    internal fun encodeFolderPath(path: String?): String {
        if (path == null) return ""
        val normalizedPath = path.replace("\\", "/")
        return try {
            java.net.URLEncoder
                .encode(normalizedPath, "UTF-8")
                .replace("%2F", "/")
                .replace("+", "%20")
        } catch (e: Exception) {
            normalizedPath
        }
    }

    // PL: Lista plików i folderów z odpowiedzi JSON serwera.
    internal fun parseGalleryItems(json: String): List<GalleryItem> {
        val list = mutableListOf<GalleryItem>()
        if (json.isEmpty()) return list
        try {
            val fileList = gson.fromJson(json, GalleryFileListDto::class.java)
            if (fileList?.files != null) {
                list.addAll(fileList.files.map { it.toDomain() })
            }
        } catch (_: Exception) {
            try {
                val type = object : TypeToken<List<GalleryItemDto>>() {}.type
                val arrayItems = gson.fromJson<List<GalleryItemDto>>(json, type)
                if (arrayItems != null) list.addAll(arrayItems.map { it.toDomain() })
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to parse gallery items. JSON: $json", e2)
            }
        }
        return list
    }

    // PL: Wiersz indeksu jako element galerii.
    internal fun IndexedImage.toGalleryItem() = GalleryItem(name = name, fullpath = fullpath, type = "file", date = date)

    // PL: Element galerii jako ulubiony (sam plik, z datą).
    internal fun GalleryItem.asFavorite() = GalleryItem(name = name, fullpath = fullpath, type = "file", date = date.orEmpty())

    /** An error the server reported, shown to the user as it is. */
    internal class GalleryException(
        message: String,
    ) : IOException(message)

    // PL: Klient API serwera; wyjątek, gdy aplikacja nie jest połączona.
    internal fun api(): ForgeApi = networkManager.forgeApi ?: throw GalleryException("Not connected to the server.")

    // PL: Zawartość folderu z serwera (IIB /files), z ponowieniem przy zmianie klucza.
    internal suspend fun listFolder(folder: String): List<GalleryItem> {
        val target = if (folder.isNotEmpty() && folder != "Root") encodeFolderPath(folder) else ""
        val response = api().getGalleryFilesDynamic(url = "${prefix()}/files", folderPath = target)
        return when {
            response.isSuccessful -> parseGalleryItems(response.body()?.string().orEmpty())
            response.code() == 401 || response.code() == 403 -> throw GalleryException("Authentication Required.")
            else -> throw GalleryException("Server returned Error ${response.code()}")
        }
    }

    internal const val GALLERY_NAME = "Gallery"

    /** Only in the log: the index updates itself and never interrupts the user. */
    internal val lastSyncMessage = MutableStateFlow("")

    // Images whose details (IndexDetails, 3.6.0) are still to be read; the statistics show it while it is not 0.
    internal val _detailsBacklog = MutableStateFlow(0)
    val detailsBacklog: StateFlow<Int> = _detailsBacklog.asStateFlow()

    enum class Transfer { MOVE, COPY }

    internal val UNSUPPORTED_ENDPOINT = setOf(404, 405, 422)

    // --- JOBS FROM IMAGES (2.4.0) ---
    // PL: stan okna zadań z obrazów (funkcje w gallery/GalleryImageActions.kt).

    /**
     * The dialog of "Upscale Selected" or "More Like This": its [kind], how many images it is for, and those images
     * remade as jobs, in the same order (null while their generation data is read).
     */
    data class ImageJobsRequest(
        val kind: ImageJobs.Kind,
        val count: Int,
        val sources: List<ImageJobs.Source>? = null,
    )

    internal val _imageJobs = MutableStateFlow<ImageJobsRequest?>(null)
    val imageJobs: StateFlow<ImageJobsRequest?> = _imageJobs.asStateFlow()
    internal var imageJobsRead: Job? = null
}
