package com.example.forgegen

import com.example.forgegen.ui.components.parseTags
import com.example.forgegen.ui.components.splitTagWeight
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.random.Random

/* ============================================================================
 * GALLERY FILES AND STATISTICS (3.2.0)
 * The rules behind changing the server's gallery through Infinite Image Browsing (moving, copying, new folders) and
 * behind what the app works out from its own index (folder counts, statistics, a random order). Paths are the
 * server's: "\" on Windows, "/" elsewhere, compared without letter case.
 * ============================================================================ */

object GalleryPaths {
    /** The separator [path] uses. */
    fun separatorOf(path: String): Char = if ('\\' in path) '\\' else '/'

    /** [name] inside [folder], written with the folder's separator. */
    fun child(
        folder: String,
        name: String,
    ): String = folder.trimEnd('/', '\\') + separatorOf(folder) + name

    fun nameOf(path: String): String = path.trimEnd('/', '\\').let { it.substring(maxOf(it.lastIndexOf('/'), it.lastIndexOf('\\')) + 1) }

    /** The folder [path] is in. */
    fun parentOf(path: String): String {
        val trimmed = path.trimEnd('/', '\\')
        val cut = maxOf(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'))
        return if (cut > 0) trimmed.substring(0, cut) else trimmed
    }

    /** A path to compare: "/" only, no trailing "/", lower case. */
    fun key(path: String): String = path.replace('\\', '/').trimEnd('/').lowercase(Locale.ROOT)

    fun same(
        a: String,
        b: String,
    ) = key(a) == key(b)

    /** Why [name] cannot name a new folder (on Windows or elsewhere); null when it can. */
    fun folderNameProblem(name: String): String? {
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> "Type a name"
            trimmed == "." || trimmed == ".." -> "Not a folder name"
            trimmed.any { it in FORBIDDEN || it < ' ' } -> "A name cannot have / \\ : * ? \" < > |"
            trimmed.endsWith(".") -> "A name cannot end with a dot"
            trimmed.length > MAX_NAME -> "At most $MAX_NAME characters"
            else -> null
        }
    }

    private const val FORBIDDEN = "/\\:*?\"<>|"
    private const val MAX_NAME = 100
}

object GalleryTransfer {
    /**
     * What a move or copy to [dest] sends: images already in [dest] stay out, and so do images whose name is taken
     * there ([namesInDest]) or by another image sent (the server would overwrite it without asking).
     */
    data class Plan(
        val send: List<GalleryItem>,
        val alreadyThere: List<GalleryItem>,
        val nameTaken: List<GalleryItem>,
    )

    fun plan(
        items: List<GalleryItem>,
        dest: String,
        namesInDest: Collection<String>,
    ): Plan {
        val taken = namesInDest.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
        val already = ArrayList<GalleryItem>()
        val send = ArrayList<GalleryItem>()
        val skipped = ArrayList<GalleryItem>()
        for (item in items) {
            val name = item.name.lowercase(Locale.ROOT)
            when {
                GalleryPaths.same(GalleryPaths.parentOf(item.fullpath), dest) -> already += item
                !taken.add(name) -> skipped += item
                else -> send += item
            }
        }
        return Plan(send, already, skipped)
    }

    /** The paths of [sent] the server's error messages name ("Error moving file <path> to <dest>: ..."). */
    fun failed(
        sent: List<String>,
        errors: List<String>,
    ): Set<String> = sent.filterTo(HashSet()) { path -> errors.any { path in it } }
}

object GalleryFolders {
    /** How many images of the index are in each folder under [root], its subfolders included (keys: [GalleryPaths.key]). */
    fun imageCounts(
        images: List<IndexedImage>,
        root: String,
    ): Map<String, Int> {
        val rootKey = GalleryPaths.key(root)
        val counts = HashMap<String, Int>()
        for (image in images) {
            var folder = GalleryPaths.key(image.fullpath).substringBeforeLast('/', "")
            while (folder.length > rootKey.length && folder.startsWith("$rootKey/")) {
                counts[folder] = (counts[folder] ?: 0) + 1
                folder = folder.substringBeforeLast('/', "")
            }
        }
        return counts
    }
}

/** What the statistics show, worked out from the index on the phone. */
data class GalleryStats(
    val images: Int,
    val thisMonth: Int,
    // The files' sizes added up; [sizesKnown] is false while some are not known yet (the index fills them in).
    val bytes: Long,
    val sizesKnown: Boolean,
    // Images per day from [firstDay] (a Monday) to today.
    val firstDay: LocalDate,
    val perDay: List<Int>,
    val topModels: List<Pair<String, Int>>,
    val topLoras: List<Pair<String, Int>>,
    val topTags: List<Pair<String, Int>>,
    // 3.6.0: images per month ("yyyy-MM") from the first image's month to this one, the details and What You Like.
    val perMonth: List<Pair<String, Int>> = emptyList(),
    val details: GalleryDetailStats = GalleryDetailStats(),
    val liked: LikedStats? = null,
) {
    val busiestDay: Int get() = perDay.maxOrNull() ?: 0

    /** 0 (none) to 4 (the busiest days), for the heat map. */
    fun level(count: Int): Int {
        val max = busiestDay
        return if (count <= 0 || max <= 0) 0 else ((count * LEVELS + max - 1) / max).coerceIn(1, LEVELS)
    }

    companion object {
        const val LEVELS = 4
    }
}

object GalleryStatistics {
    const val WEEKS = 17
    const val TOP = 5
    const val TOP_TAGS = 8

    /** [images] of the gallery counted up on [today]; [tagCounts] come from [countTags] over their prompts. */
    fun compute(
        images: List<IndexedImage>,
        today: LocalDate,
        tagCounts: Map<String, Int> = emptyMap(),
    ): GalleryStats {
        val month = today.toString().take(7) // yyyy-MM, as the server's dates start
        val first = today.minusWeeks(WEEKS - 1L).with(DayOfWeek.MONDAY)
        val span = ChronoUnit.DAYS.between(first, today).toInt() + 1
        val perDay = IntArray(span)
        var thisMonth = 0
        var bytes = 0L
        var unknownSizes = 0
        val models = HashMap<String, Int>()
        val loras = HashMap<String, Int>()
        val months = HashMap<String, Int>()
        for (image in images) {
            if (image.date.startsWith(month)) thisMonth++
            dayOf(image.date)?.let { months.merge(image.date.take(7), 1, Int::plus) }
            dayOf(image.date)?.let { day ->
                val index = ChronoUnit.DAYS.between(first, day)
                if (index in 0 until span) perDay[index.toInt()]++
            }
            if (image.size > 0) bytes += image.size else unknownSizes++
            image.model
                .trim()
                .takeIf { it.isNotEmpty() }
                ?.let { models.merge(it, 1, Int::plus) }
            image.loras
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .forEach { loras.merge(it, 1, Int::plus) }
        }
        return GalleryStats(
            images = images.size,
            thisMonth = thisMonth,
            bytes = bytes,
            sizesKnown = unknownSizes == 0,
            firstDay = first,
            perDay = perDay.toList(),
            topModels = top(models, TOP),
            topLoras = top(loras, TOP),
            topTags = top(tagCounts, TOP_TAGS),
            perMonth = monthsUpTo(months, today),
        )
    }

    /** [counts] for every month from the first one with images to [today]'s (none for an empty gallery). */
    private fun monthsUpTo(
        counts: Map<String, Int>,
        today: LocalDate,
    ): List<Pair<String, Int>> {
        val first = counts.keys.minOrNull() ?: return emptyList()
        val start = runCatching { java.time.YearMonth.parse(first) }.getOrNull() ?: return emptyList()
        val end = java.time.YearMonth.from(today)
        if (start.isAfter(end)) return emptyList()
        val result = ArrayList<Pair<String, Int>>()
        var m = start
        while (!m.isAfter(end)) {
            val key = m.toString()
            result += key to (counts[key] ?: 0)
            m = m.plusMonths(1)
        }
        return result
    }

    /**
     * Adds the tags of [prompt] to [counts], each once: without weights, LoRAs and BREAK, "_" read as a space. A LoRA
     * ("<lora:x:0.8>") is taken out first, as it is often written without a comma before it.
     */
    fun countTags(
        prompt: String,
        counts: MutableMap<String, Int>,
    ) {
        parseTags(prompt.replace(ANGLE_BLOCK, ","))
            .mapNotNull { tag ->
                val base =
                    splitTagWeight(tag)
                        .base
                        .replace('_', ' ')
                        .trim()
                        .lowercase(Locale.ROOT)
                base.takeIf { it.isNotEmpty() && !it.startsWith("<") && it != "break" }
            }.toSet()
            .forEach { counts.merge(it, 1, Int::plus) }
    }

    private val ANGLE_BLOCK = Regex("<[^<>]*>")

    /** The day of a server date ("yyyy-MM-dd HH:mm:ss"); null when it is not one. */
    fun dayOf(date: String): LocalDate? = runCatching { LocalDate.parse(date.take(10)) }.getOrNull()

    private fun top(
        counts: Map<String, Int>,
        n: Int,
    ): List<Pair<String, Int>> =
        counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(n)
            .map { it.key to it.value }

    /** "812 MB", "3.4 GB", "41 GB" (1024-based, as file managers count). */
    fun formatBytes(bytes: Long): String {
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return if (value < 10 && unit > 0) String.format(Locale.US, "%.1f %s", value, units[unit]) else "${value.toLong()} ${units[unit]}"
    }
}

/** The order of the All Images tab (3.2.0): the newest first, or shuffled by [seed] (a tap on Random shuffles again). */
data class AllImagesOrder(
    val random: Boolean = false,
    val seed: Long = 0,
) {
    fun <T> apply(items: List<T>): List<T> = if (random) items.shuffled(Random(seed)) else items
}
