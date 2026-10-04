package com.example.forgegen

import java.util.Locale
import kotlin.math.roundToInt

/* ============================================================================
 * GALLERY INSIGHTS (3.6.0)
 * The statistics' new parts, worked out from the index's details (IndexDetails) and the favorites: the samplers and
 * schedulers, sizes, steps, CFG, hires fix, VAE and text encoders, embeddings and negative tags used, and "What You
 * Like": how often what an image was made with ends up in the favorites. Only what has at least [MIN_IMAGES] images
 * counts there, so a few lucky images do not tip it. Every row can open the gallery with its images (StatTarget).
 * ============================================================================ */

/** A setting the statistics open the gallery with (3.6.0): the gallery's search keeps it as one chip. */
data class GalleryDetailFilter(
    val kind: Kind,
    val value: String,
    // The scheduler of a SAMPLER filter ("" = any).
    val extra: String = "",
) {
    enum class Kind { SIZE, SAMPLER, MODULES, HIRES, EMBEDDING, STEPS, CFG }

    /** "Size 832×1216", as the gallery's chip says it. */
    val label: String
        get() =
            when (kind) {
                Kind.SIZE -> "Size ${value.replace('x', '×')}"
                Kind.SAMPLER -> listOf(value, extra).filter { it.isNotEmpty() }.joinToString(" · ")
                Kind.MODULES -> value.ifEmpty { "The model's own VAE" }
                Kind.HIRES -> "Hires fix"
                Kind.EMBEDDING -> value
                Kind.STEPS -> "$value steps"
                Kind.CFG -> "CFG $value"
            }
}

/** What a statistics row opens in the gallery. */
sealed interface StatTarget {
    data class Model(
        val name: String,
    ) : StatTarget

    data class Lora(
        val name: String,
    ) : StatTarget

    // A tag of the positive prompt, or of the negative one (3.6.2-1).
    data class Tag(
        val tag: String,
        val negative: Boolean = false,
    ) : StatTarget

    data class Detail(
        val filter: GalleryDetailFilter,
    ) : StatTarget

    companion object {
        /** The gallery's search for [target]: everything else cleared, the sort order kept. */
        fun filters(
            target: StatTarget,
            current: ForgeGalleryManager.GalleryFilters,
        ): ForgeGalleryManager.GalleryFilters {
            val clean = ForgeGalleryManager.GalleryFilters(sortOrder = current.sortOrder)
            return when (target) {
                is Model -> clean.copy(models = setOf(target.name))
                is Lora -> clean.copy(loras = setOf(target.name))
                // Whole tags, as the statistics count them, so the gallery finds as many images as the count says.
                is Tag ->
                    if (target.negative) {
                        clean.copy(negativeTags = listOf(target.tag), exactTags = true)
                    } else {
                        clean.copy(positiveTags = listOf(target.tag), exactTags = true)
                    }
                is Detail -> clean.copy(detail = target.filter)
            }
        }
    }
}

/** A counted setting, with what it opens. */
data class SettingCount(
    val label: String,
    val count: Int,
    val target: StatTarget? = null,
)

data class GalleryDetailStats(
    // Images whose details were read (the others wait for fillDetails).
    val known: Int = 0,
    val samplers: List<SettingCount> = emptyList(),
    val sizes: List<SettingCount> = emptyList(),
    // Value to count for the most used values, by value.
    val steps: List<Pair<Int, Int>> = emptyList(),
    val typicalSteps: Int? = null,
    val cfg: List<Pair<Float, Int>> = emptyList(),
    val typicalCfg: Float? = null,
    // The most used distilled CFG (FLUX), and on how many images.
    val distilledCfg: Float? = null,
    val distilledCount: Int = 0,
    val hiresCount: Int = 0,
    val hiresScale: Float? = null,
    val hiresUpscaler: String? = null,
    val hiresDenoising: Float? = null,
    val hiresSteps: Int? = null,
    val modules: List<SettingCount> = emptyList(),
    val embeddings: List<SettingCount> = emptyList(),
    val negativeTags: List<SettingCount> = emptyList(),
)

/** How often [label]'s images are favorites. */
data class LikedRate(
    val label: String,
    val favorites: Int,
    val total: Int,
    val target: StatTarget? = null,
) {
    val rate: Float get() = if (total > 0) favorites.toFloat() / total else 0f
}

/** How often images with [label] are favorites, and images without it. */
data class LikedComparison(
    val label: String,
    val withRate: Float,
    val withoutRate: Float,
    val target: StatTarget? = null,
)

/** A tag [lift] times as common in the favorites as in the whole gallery. */
data class LikedTag(
    val tag: String,
    val lift: Float,
)

data class LikedStats(
    val favorites: Int,
    val total: Int,
    val byModel: List<LikedRate>,
    val bySetting: List<LikedComparison>,
    val tagsMore: List<LikedTag>,
    val tagsLess: List<LikedTag>,
)

/** Collects the index's rows and prompts (a page at a time) for [GalleryDetailStats] and [LikedStats]. */
class GalleryInsights(
    private val favorites: Set<String>,
) {
    private var total = 0
    private var liked = 0
    private var known = 0
    private val samplers = Counter<Pair<String, String>>()
    private val sizes = Counter<Pair<Int, Int>>()
    private val steps = Counter<Int>()
    private val cfg = Counter<Float>()
    private val distilled = Counter<Float>()
    private var hires = 0
    private val hiresScales = Counter<Float>()
    private val upscalers = Counter<String>()
    private val denoising = Counter<Float>()
    private val hiresSteps = Counter<Int>()
    private val modules = Counter<String>()
    private val embeddings = Counter<String>()
    private val negativeTags = HashMap<String, Int>()
    private val tags = HashMap<String, Int>()
    private val favoriteTags = HashMap<String, Int>()

    // For What You Like: per model, and per setting (with it / its favorites).
    private val models = Counter<String>()
    private val likedModels = Counter<String>()
    private val withHires = IntArray(2)
    private val loras = Counter<String>()
    private val likedLoras = Counter<String>()
    private val likedSizes = Counter<Pair<Int, Int>>()
    private val likedSamplers = Counter<Pair<String, String>>()
    private var hiresKnown = 0
    private var hiresLikedKnown = 0

    fun add(row: GalleryStatsRow) {
        total++
        val isFavorite = row.fullpath in favorites
        if (isFavorite) liked++
        row.model.trim().takeIf { it.isNotEmpty() }?.let {
            models.add(it)
            if (isFavorite) likedModels.add(it)
        }
        val usedLoras =
            row.loras
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        usedLoras.forEach {
            loras.add(it)
            if (isFavorite) likedLoras.add(it)
        }
        if (row.details <= 0) return
        known++
        val sampler = row.sampler.trim()
        if (sampler.isNotEmpty()) {
            val key = sampler to row.scheduler?.trim().orEmpty()
            samplers.add(key)
            if (isFavorite) likedSamplers.add(key)
        }
        if (row.width != null && row.height != null) {
            val key = row.width to row.height
            sizes.add(key)
            if (isFavorite) likedSizes.add(key)
        }
        row.steps?.let { steps.add(it) }
        row.cfg?.let { cfg.add(it) }
        row.distilledCfg?.let { distilled.add(it) }
        hiresKnown++
        if (isFavorite) hiresLikedKnown++
        if (row.hiresScale != null) {
            hires++
            withHires[0]++
            if (isFavorite) withHires[1]++
            hiresScales.add(row.hiresScale)
            row.hiresUpscaler?.let { upscalers.add(it) }
            row.denoising?.let { denoising.add(it) }
            row.hiresSteps?.let { hiresSteps.add(it) }
        }
        modules.add(row.modules.orEmpty())
        row.embeddings
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.forEach { embeddings.add(it) }
    }

    /** The tags of an image's prompts (only for images [add] was given). */
    fun addPrompts(
        fullpath: String,
        positive: String,
        negative: String,
    ) {
        GalleryStatistics.countTags(negative, negativeTags)
        if (fullpath in favorites) {
            val counted = HashMap<String, Int>()
            GalleryStatistics.countTags(positive, counted)
            counted.keys.forEach { favoriteTags.merge(it, 1, Int::plus) }
            counted.keys.forEach { tags.merge(it, 1, Int::plus) }
        } else {
            GalleryStatistics.countTags(positive, tags)
        }
    }

    /** Every positive prompt's tags, each once per image (the statistics' top tags). */
    fun tagCounts(): Map<String, Int> = tags

    fun details(): GalleryDetailStats {
        val stepsTop = steps.top(STEP_BARS).map { it.first }.sorted()
        val cfgTop = cfg.top(STEP_BARS).map { it.first }.sorted()
        return GalleryDetailStats(
            known = known,
            samplers =
                samplers.top(TOP).map { (key, count) ->
                    val (sampler, scheduler) = key
                    SettingCount(
                        listOf(sampler, scheduler).filter { it.isNotEmpty() }.joinToString(" · "),
                        count,
                        StatTarget.Detail(GalleryDetailFilter(GalleryDetailFilter.Kind.SAMPLER, sampler, scheduler)),
                    )
                },
            sizes =
                sizes.top(TOP_SIZES).map { (key, count) ->
                    SettingCount("${key.first}×${key.second}", count, sizeTarget(key))
                },
            steps = stepsTop.map { it to steps.count(it) },
            typicalSteps = steps.top(1).firstOrNull()?.first,
            cfg = cfgTop.map { it to cfg.count(it) },
            typicalCfg = cfg.top(1).firstOrNull()?.first,
            distilledCfg = distilled.top(1).firstOrNull()?.first,
            distilledCount = distilled.total,
            hiresCount = hires,
            hiresScale = hiresScales.top(1).firstOrNull()?.first,
            hiresUpscaler = upscalers.top(1).firstOrNull()?.first,
            hiresDenoising = denoising.top(1).firstOrNull()?.first,
            hiresSteps = hiresSteps.top(1).firstOrNull()?.first,
            modules =
                modules.top(TOP).map { (key, count) ->
                    SettingCount(
                        key.ifEmpty { "The model's own" },
                        count,
                        StatTarget.Detail(GalleryDetailFilter(GalleryDetailFilter.Kind.MODULES, key)),
                    )
                },
            embeddings =
                embeddings.top(TOP).map { (name, count) ->
                    SettingCount(name, count, StatTarget.Detail(GalleryDetailFilter(GalleryDetailFilter.Kind.EMBEDDING, name)))
                },
            negativeTags =
                negativeTags.entries
                    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                    .take(TOP_TAGS)
                    .map { SettingCount(it.key, it.value, StatTarget.Tag(it.key, negative = true)) },
        )
    }

    /** Null until there are enough favorites to say something. */
    fun liked(): LikedStats? {
        if (liked < MIN_FAVORITES || total < MIN_IMAGES) return null
        val byModel =
            models
                .entries()
                .filter { it.second >= MIN_IMAGES }
                .map { (name, count) -> LikedRate(name, likedModels.count(name), count, StatTarget.Model(name)) }
                .sortedByDescending { it.rate }
                .take(TOP)
        val settings = ArrayList<LikedComparison>()
        comparison(
            "Hires fix",
            withHires[0],
            withHires[1],
            hiresKnown,
            hiresLikedKnown,
            StatTarget.Detail(GalleryDetailFilter(GalleryDetailFilter.Kind.HIRES, "on")),
        )?.let { settings += it }
        sizes.top(1).firstOrNull()?.let { (key, count) ->
            comparison("${key.first}×${key.second}", count, likedSizes.count(key), known, likedKnown(), sizeTarget(key))?.let {
                settings +=
                    it
            }
        }
        samplers.top(1).firstOrNull()?.let { (key, count) ->
            val label = listOf(key.first, key.second).filter { it.isNotEmpty() }.joinToString(" · ")
            val target = StatTarget.Detail(GalleryDetailFilter(GalleryDetailFilter.Kind.SAMPLER, key.first, key.second))
            comparison(label, count, likedSamplers.count(key), known, likedKnown(), target)?.let { settings += it }
        }
        loras.top(1).firstOrNull()?.let { (name, count) ->
            comparison(name, count, likedLoras.count(name), total, liked, StatTarget.Lora(name))?.let { settings += it }
        }
        val favoriteShare = liked.toFloat() / total
        val lifts =
            tags.entries
                .filter { it.value >= MIN_IMAGES }
                .map { (tag, count) -> LikedTag(tag, round1((favoriteTags[tag] ?: 0).toFloat() / count / favoriteShare)) }
        return LikedStats(
            favorites = liked,
            total = total,
            byModel = byModel,
            bySetting = settings,
            tagsMore = lifts.filter { it.lift >= MORE }.sortedByDescending { it.lift }.take(TOP_LIFTS),
            tagsLess = lifts.filter { it.lift <= LESS }.sortedBy { it.lift }.take(TOP_LIFTS - 1),
        )
    }

    // Images with details known among the favorites (the base of the size and sampler comparisons).
    private fun likedKnown() = hiresLikedKnown

    private fun comparison(
        label: String,
        withCount: Int,
        withLiked: Int,
        baseCount: Int,
        baseLiked: Int,
        target: StatTarget?,
    ): LikedComparison? {
        val withoutCount = baseCount - withCount
        if (withCount < MIN_IMAGES || withoutCount < MIN_IMAGES) return null
        return LikedComparison(label, withLiked.toFloat() / withCount, (baseLiked - withLiked).toFloat() / withoutCount, target)
    }

    private fun sizeTarget(key: Pair<Int, Int>) =
        StatTarget.Detail(GalleryDetailFilter(GalleryDetailFilter.Kind.SIZE, "${key.first}x${key.second}"))

    private class Counter<K> {
        private val map = HashMap<K, Int>()
        var total = 0
            private set

        fun add(key: K) {
            map.merge(key, 1, Int::plus)
            total++
        }

        fun count(key: K) = map[key] ?: 0

        fun entries(): List<Pair<K, Int>> = map.entries.map { it.key to it.value }

        fun top(n: Int): List<Pair<K, Int>> =
            map.entries
                .sortedWith(compareByDescending<Map.Entry<K, Int>> { it.value }.thenBy { it.key.toString() })
                .take(n)
                .map { it.key to it.value }
    }

    companion object {
        const val MIN_IMAGES = 20
        const val MIN_FAVORITES = 5
        private const val TOP = 5
        private const val TOP_SIZES = 6
        private const val TOP_TAGS = 6
        private const val STEP_BARS = 6
        private const val TOP_LIFTS = 3
        private const val MORE = 1.25f
        private const val LESS = 0.75f

        private fun round1(value: Float) = (value * 10).roundToInt() / 10f

        /** "5", "6.5": a CFG or scale without a needless ".0". */
        fun number(value: Float): String =
            if (value == value.toInt().toFloat()) value.toInt().toString() else String.format(Locale.US, "%.2f", value).trimEnd('0')
    }
}
