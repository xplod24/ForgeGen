package com.example.forgegen

/* ============================================================================
 * SESSION MEMORY (3.6.2, the owner's request)
 * An update ends the app's process, and with it what the app held only in memory: the images of this session on the
 * main screen (and which one was shown), a pause of the queue with its reason, and how far the queue's run got. The
 * queue's jobs, the prompts and every setting are saved anyway.
 *
 * ForgeQueueManager keeps that memory saved as a SavedSession (settings key "saved_session", with the build that
 * wrote it) and brings it back at the first start of a newer build, so after an update the app goes on where it
 * was. Any other start (the same build) still begins with an empty session, as before.
 * ============================================================================ */

/** What the app was doing, as saved for the first start after an update. Gson data: keep it in this package (R8). */
data class SavedSession(
    // The build that wrote it (BuildConfig.VERSION_CODE); 0: none.
    val versionCode: Int = 0,
    // The session's images in the cache, oldest first, and the one shown with its batch.
    val images: List<String> = emptyList(),
    val index: Int = -1,
    val batchStart: Int = 0,
    val batchEnd: Int = -1,
    // The queue's pause and its reason, and the jobs done in its current run.
    val paused: Boolean = false,
    val pauseReason: String? = null,
    val completed: Int = 0,
)

object SessionMemory {
    const val KEY = "saved_session"

    /**
     * What to bring back at a start of build [runningVersion]: [saved] when a different build wrote it (the first
     * start after an update), with only the images that are still in the cache ([exists]) and the positions moved
     * to match; null after an ordinary restart or when nothing was saved.
     */
    fun afterUpdate(
        saved: SavedSession?,
        runningVersion: Int,
        exists: (String) -> Boolean,
    ): SavedSession? {
        if (saved == null || saved.versionCode == 0 || saved.versionCode == runningVersion) return null
        val present = saved.images.map(exists)
        val images = saved.images.filterIndexed { i, _ -> present[i] }

        // How many of the images before position [i] are still there.
        fun keptBefore(i: Int) = present.take(i.coerceIn(0, present.size)).count { it }
        var batchStart = keptBefore(saved.batchStart)
        var batchEnd = keptBefore(saved.batchEnd + 1) - 1
        if (images.isEmpty()) {
            batchStart = 0
            batchEnd = -1
        } else if (batchEnd < batchStart) {
            // The shown batch is gone: the newest image left is shown on its own.
            batchStart = images.lastIndex
            batchEnd = images.lastIndex
        }
        val index =
            if (images.isEmpty()) -1 else (keptBefore(saved.index + 1) - 1).coerceIn(batchStart, batchEnd)
        return saved.copy(images = images, index = index, batchStart = batchStart, batchEnd = batchEnd)
    }

    /**
     * Whether a saved pause comes back: it was the user's or the server's reason to stop, and jobs are left to run.
     * A lost connection is not kept: the queue waits for the server anyway and goes on once it is back.
     */
    fun keepsPause(
        saved: SavedSession,
        runnableJobs: Int,
        connectionLostReason: String,
    ): Boolean = saved.paused && runnableJobs > 0 && saved.pauseReason != connectionLostReason
}
