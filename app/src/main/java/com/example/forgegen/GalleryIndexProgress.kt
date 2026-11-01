package com.example.forgegen

/** One coherent snapshot: gallery coverage and this update's work have different denominators. */
data class GalleryIndexProgress(
    val source: String = "",
    val stage: GalleryIndexStage = GalleryIndexStage.IDLE,
    val indexed: Int = 0,
    val total: Int? = null,
    val processed: Int = 0,
    val workTotal: Int = 0,
    val foldersChecked: Int = 0,
    val foldersTotal: Int? = null,
    val unreadable: Int? = null,
    val detailsRemaining: Int = 0,
    val error: String? = null,
) {
    val isActive: Boolean
        get() = stage in ACTIVE_STAGES

    val remaining: Int?
        get() = total?.let { (it - indexed).coerceAtLeast(0) }

    // Folder discovery and finalization have no honest fixed denominator.
    val fraction: Float?
        get() =
            if (stage in READING_STAGES && workTotal > 0) {
                (processed.toFloat() / workTotal).coerceIn(0f, 1f)
            } else {
                null
            }

    /** Local deletions or moves update known coverage without making a partial index appear complete. */
    fun afterReload(
        source: String,
        count: Int,
    ): GalleryIndexProgress {
        if (this.source != source) return GalleryIndexProgress(source = source, indexed = count)
        if (isActive) return this
        return copy(indexed = count, total = total?.let { (it + count - indexed).coerceAtLeast(count) })
    }

    private companion object {
        val READING_STAGES = setOf(GalleryIndexStage.READING_IMAGES, GalleryIndexStage.READING_DETAILS)
        val ACTIVE_STAGES = READING_STAGES + setOf(GalleryIndexStage.CHECKING_FOLDERS, GalleryIndexStage.FINISHING)
    }
}

enum class GalleryIndexStage {
    IDLE,
    CHECKING_FOLDERS,
    READING_IMAGES,
    READING_DETAILS,
    FINISHING,
    UP_TO_DATE,
    WAITING_FOR_GENERATION,
    FAILED,
    INTERRUPTED,
}
