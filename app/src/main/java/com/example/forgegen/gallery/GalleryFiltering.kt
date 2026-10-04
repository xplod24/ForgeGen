package com.example.forgegen

import android.util.Log
import com.example.forgegen.ForgeGalleryManager.GalleryFilters
import com.example.forgegen.ForgeGalleryManager.SortOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDate

/* ============================================================================
 * GALERIA: FILTRY, SORTOWANIE I STATYSTYKI (3.6.1, podział ForgeGalleryManager na pliki)
 *
 * Co tu jest: funkcje, które z indeksu galerii (lista obrazów w pamięci) robią to, co widać na ekranie:
 * filtrowanie po modelu, LoRA, słowach promptu i szczegółach generowania, sortowanie, losowa kolejność "All Images"
 * oraz liczenie statystyk galerii.
 *
 * Jak to działa: same listy do pokazania (folderView, allImages, favoriteImages) są właściwościami obiektu
 * ForgeGalleryManager (plik ForgeGalleryManager.kt); liczą się na nowo, gdy zmieni się indeks albo filtry, i wołają
 * funkcje z tego pliku.
 *
 * Do poczytania: Flow i combine (łączenie strumieni danych), filter/sortedWith/groupBy na listach w Kotlinie,
 * Room i zapytania SQL z LIKE (szukanie w promptach w bazie na telefonie).
 *
 * Funkcje w tym pliku są funkcjami rozszerzającymi obiektu ForgeGalleryManager ("fun ForgeGalleryManager.x()"):
 * wywołuje się je tak samo jak jego własne (ForgeGalleryManager.x()) i mają dostęp do jego stanu oznaczonego
 * jako internal. Sam stan (listy, flagi, zadania w tle) jest w ForgeGalleryManager.kt.
 * ============================================================================ */

// Włącza/wyłącza losową kolejność zakładki All Images (nowy seed = nowe tasowanie).

/** All Images the newest first, or shuffled; Random chosen again shuffles anew. */
fun ForgeGalleryManager.setAllImagesRandom(random: Boolean) {
    _allImagesOrder.value = if (random) AllImagesOrder(true, System.nanoTime()) else AllImagesOrder()
}

// Ścieżki obrazów pasujących do filtra szczegółów (rozmiar, sampler, moduły, ...), z bazy na telefonie.

/** Paths of the images made with the setting [filter] (3.6.0), found by the database. */
internal suspend fun ForgeGalleryManager.detailMatches(filter: GalleryDetailFilter): Set<String> {
    val dao = getDb().galleryImageDao()
    return try {
        when (filter.kind) {
            GalleryDetailFilter.Kind.SIZE -> {
                val (w, h) = filter.value.split('x').map { it.trim().toIntOrNull() ?: -1 } + listOf(-1, -1)
                dao.findPathsBySize(w, h)
            }
            GalleryDetailFilter.Kind.SAMPLER -> dao.findPathsBySampler(filter.value, filter.extra)
            GalleryDetailFilter.Kind.MODULES -> dao.findPathsByModules(filter.value)
            GalleryDetailFilter.Kind.HIRES -> dao.findPathsWithHires()
            GalleryDetailFilter.Kind.EMBEDDING -> {
                val escaped =
                    filter.value
                        .replace("\\", "\\\\")
                        .replace("%", "\\%")
                        .replace("_", "\\_")
                dao.findPathsByEmbedding("%,$escaped,%")
            }
            GalleryDetailFilter.Kind.STEPS -> dao.findPathsBySteps(filter.value.toIntOrNull() ?: -1)
            GalleryDetailFilter.Kind.CFG -> dao.findPathsByCfg(filter.value.toFloatOrNull() ?: -1f)
        }.toHashSet()
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.e(TAG, "Detail search failed", e)
        emptySet()
    }
}

// Ścieżki obrazów pasujących do tagów z filtrów (pozytywne i negatywne osobno, wszystkie naraz); null bez tagów.
// Najpierw baza zawęża wyniki zapytaniem LIKE (jeden tag, najdłuższy), potem Kotlin sprawdza każdy prompt dokładnie
// (TagFilter.holdsAll: fragment tekstu albo, przy "Exact Tags", cały tag). Od 3.6.2-1.

/** Paths of the images whose prompts hold the filters' tags (3.6.2-1); null without tags. */
internal suspend fun ForgeGalleryManager.tagMatches(filters: GalleryFilters): Set<String>? {
    val positive = filters.positiveTags.filter { it.isNotBlank() }
    val negative = filters.negativeTags.filter { it.isNotBlank() }
    if (positive.isEmpty() && negative.isEmpty()) return null
    return try {
        val dao = getDb().galleryImageDao()

        // Jeden prompt (pozytywny albo negatywny): zawężenie w bazie, potem dokładne sprawdzenie w Kotlinie.
        suspend fun inPrompt(
            tags: List<String>,
            isNegative: Boolean,
        ): Set<String> {
            val pattern = TagFilter.likePattern(tags.maxBy { it.length }, filters.exactTags)
            val rows = if (isNegative) dao.findByNegativePrompt(pattern) else dao.findByPositivePrompt(pattern)
            return rows
                .filter { TagFilter.holdsAll(if (isNegative) it.negativePrompt else it.positivePrompt, tags, filters.exactTags) }
                .mapTo(HashSet()) { it.fullpath }
        }
        val positiveHits = if (positive.isEmpty()) null else inPrompt(positive, isNegative = false)
        val negativeHits = if (negative.isEmpty()) null else inPrompt(negative, isNegative = true)
        when {
            positiveHits == null -> negativeHits.orEmpty()
            negativeHits == null -> positiveHits
            else -> positiveHits.intersect(negativeHits)
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.e(TAG, "Prompt search failed", e)
        emptySet()
    }
}

// Czy obraz pasuje do filtrów (model, LoRA, wynik wyszukiwania w promptach i szczegółach).
internal fun ForgeGalleryManager.matches(
    image: IndexedImage,
    filters: GalleryFilters,
    promptHits: Set<String>?,
): Boolean {
    if (filters.name.isNotBlank() && !image.name.contains(filters.name.trim(), ignoreCase = true)) return false
    if (promptHits != null && image.fullpath !in promptHits) return false
    if (filters.models.isNotEmpty() && image.model !in filters.models) return false
    if (filters.loras.isNotEmpty()) {
        val used = image.loras.split(",").map { it.trim() }
        val ok = if (filters.lorasIsAnd) filters.loras.all { it in used } else filters.loras.any { it in used }
        if (!ok) return false
    }
    return true
}

// Sortuje obrazy wybraną kolejnością (najnowsze, najstarsze, po nazwie); foldery zawsze na górze.

/** Folders first, then images. Dates are "yyyy-MM-dd HH:mm:ss". */
internal fun ForgeGalleryManager.sortItems(
    items: List<GalleryItem>,
    order: SortOrder,
): List<GalleryItem> {
    val byDate = compareBy<GalleryItem>({ it.date.orEmpty() }, { it.name })
    val byName = compareBy<GalleryItem> { it.name.lowercase() }
    val comparator =
        when (order) {
            SortOrder.NEWEST -> byDate.reversed()
            SortOrder.OLDEST -> byDate
            SortOrder.NAME_ASC -> byName
            SortOrder.NAME_DESC -> byName.reversed()
        }
    val (dirs, images) = items.partition { it.isDir }
    return dirs.sortedWith(comparator) + images.sortedWith(comparator)
}

// Ustawia nowe filtry galerii (listy na ekranie przeliczą się same).
fun ForgeGalleryManager.applyFilters(filters: GalleryFilters) {
    _galleryFilters.value = filters
}

// Czyści filtry, zostawia tylko wybraną kolejność sortowania.
fun ForgeGalleryManager.clearFilters() {
    _galleryFilters.value = GalleryFilters(sortOrder = _galleryFilters.value.sortOrder)
}

// Liczy statystyki galerii z indeksu: obrazy dziennie i miesięcznie, modele, LoRA, tagi i szczegóły.

/** The gallery's statistics from the index on the phone; the prompts' tags are read from the database page by page. */
suspend fun ForgeGalleryManager.statistics(): GalleryStats =
    withContext(Dispatchers.IO) {
        val root = galleryRoot()?.let { norm(it) }
        val images = indexedImages.value.filter { root == null || isUnderNormalized(it.fullpath, root) }
        val inGallery = images.mapTo(HashSet()) { it.fullpath }
        // 3.6.0: the details and the favorites too, a page at a time (the prompts are most of the index's size).
        val insights = GalleryInsights(favoritePaths.value)
        val dao = getDb().galleryImageDao()
        var offset = 0
        while (true) {
            ensureActive()
            val page = dao.getStatsRows(PROMPT_PAGE, offset)
            page.forEach { if (it.fullpath in inGallery) insights.add(it) }
            if (page.size < PROMPT_PAGE) break
            offset += PROMPT_PAGE
        }
        offset = 0
        while (true) {
            ensureActive()
            val page = dao.getPromptPairs(PROMPT_PAGE, offset)
            page.forEach { if (it.fullpath in inGallery) insights.addPrompts(it.fullpath, it.positivePrompt, it.negativePrompt) }
            if (page.size < PROMPT_PAGE) break
            offset += PROMPT_PAGE
        }
        GalleryStatistics
            .compute(images, LocalDate.now(), insights.tagCounts())
            .copy(details = insights.details(), liked = insights.liked())
    }
