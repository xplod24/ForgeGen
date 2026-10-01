package com.example.forgegen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/* ============================================================================
 * HOME SCREEN WIDGETS (3.6.0, board 10)
 * "Queue" (2×1): how far the running job is, what is left and when the queue ends. "ForgeGen" (4×2): the connection,
 * the queue, today's images and GPU time (from the job history), the last VRAM the app read, Pause/Resume and
 * Generate Again. No prompts and no images, as on the lock screen.
 * A widget never asks the server anything: the app pushes its state to them, and only while one is on the home screen,
 * at once for a new job, a finished one, a pause or a lost connection, and for the running job's progress at most every
 * [WidgetThrottle.STEP] % or every [WidgetThrottle.EVERY_MS] ms. The views themselves (RemoteViews) are drawn by
 * [ForgeWidgets.renderer] (WidgetViews), so this part runs without Android's widget classes.
 * ============================================================================ */

/** What the widgets show. */
data class WidgetState(
    val connected: Boolean = false,
    // Jobs still to run (the running one included), and of this run how many are done out of how many.
    val waiting: Int = 0,
    val done: Int = 0,
    val total: Int = 0,
    // The running job's progress; null while no job runs.
    val percent: Int? = null,
    // When the queue should be done (ms since 1970); null while not known.
    val endsAt: Long? = null,
    val paused: Boolean = false,
    val pausedByUser: Boolean = false,
    // Today's images and GPU time from the job history (null while it is off).
    val imagesToday: Int? = null,
    val gpuTodayMs: Long? = null,
    // The last VRAM the app read (the widgets do not read it themselves).
    val vramUsedGb: Double? = null,
    val vramTotalGb: Double? = null,
    // When the last queue was done.
    val lastDoneAt: Long? = null,
)

/** The widgets' words, from a [WidgetState]; [time] writes a time of day ("15:42"). */
object WidgetText {
    /** The small widget's big text: "42%", "paused", "3 waiting" or "idle". */
    fun queueTitle(s: WidgetState): String =
        when {
            s.waiting == 0 -> "idle"
            s.pausedByUser || s.paused -> "paused"
            s.percent != null -> "${s.percent}%"
            else -> "${s.waiting} waiting"
        }

    /** "4 left · about 15:42", "Paused · 3 left", "All done at 14:31" or "No jobs". */
    fun queueLine(
        s: WidgetState,
        time: (Long) -> String,
    ): String =
        when {
            s.waiting == 0 -> s.lastDoneAt?.let { "All done at ${time(it)}" } ?: "No jobs"
            s.pausedByUser || s.paused -> "Paused · ${s.waiting} left"
            s.endsAt != null -> "${s.waiting} left · about ${time(s.endsAt)}"
            else -> "${s.waiting} left"
        }

    /** The large widget's job line: "Job 2 of 5 · 42%", "Paused · 3 left", "3 waiting" or "No jobs waiting". */
    fun jobLine(s: WidgetState): String =
        when {
            s.waiting == 0 -> "No jobs waiting"
            s.pausedByUser || s.paused -> "Paused · ${s.waiting} left"
            s.percent != null && s.total > 0 -> "Job ${(s.done + 1).coerceAtMost(s.total)} of ${s.total} · ${s.percent}%"
            s.percent != null -> "${s.percent}%"
            else -> "${s.waiting} waiting"
        }

    /** "done about 15:42", "All done at 14:31", or nothing. */
    fun endLine(
        s: WidgetState,
        time: (Long) -> String,
    ): String =
        when {
            s.waiting > 0 -> s.endsAt?.let { "done about ${time(it)}" }.orEmpty()
            else -> s.lastDoneAt?.let { "All done at ${time(it)}" }.orEmpty()
        }

    fun images(s: WidgetState): String = s.imagesToday?.toString() ?: "–"

    /** "14 min", "1 h 05 min", "40 s", or "–" without the history. */
    fun gpu(s: WidgetState): String {
        val ms = s.gpuTodayMs ?: return "–"
        val seconds = ms / 1000
        return when {
            seconds < 60 -> "$seconds s"
            seconds < 3600 -> "${(seconds + 30) / 60} min"
            else -> String.format(Locale.US, "%d h %02d min", seconds / 3600, (seconds % 3600) / 60)
        }
    }

    /** "7.6 GB" and "VRAM of 12", or "–" and "VRAM" before the app read it. */
    fun vram(s: WidgetState): Pair<String, String> {
        val used = s.vramUsedGb ?: return "–" to "VRAM"
        val total = s.vramTotalGb?.let { " of ${String.format(Locale.US, "%.0f", it)}" }.orEmpty()
        return "${ServerMemory.gb(used)} GB" to "VRAM$total"
    }

    /** "Pause" while jobs wait, "Resume" when the user paused them, null without jobs. */
    fun pauseLabel(s: WidgetState): String? =
        when {
            s.waiting == 0 -> null
            s.pausedByUser -> "Resume"
            else -> "Pause"
        }
}

/** When the app pushes a new state to the widgets: at once for a change, the progress and end time only now and then. */
object WidgetThrottle {
    const val STEP = 10
    const val EVERY_MS = 10_000L

    fun shouldPush(
        last: WidgetState?,
        lastAt: Long,
        next: WidgetState,
        now: Long,
    ): Boolean {
        if (last == null) return true
        if (next == last) return false

        fun calm(s: WidgetState) = s.copy(percent = s.percent?.let { it / STEP }, endsAt = null)
        return calm(next) != calm(last) || now - lastAt >= EVERY_MS
    }
}

/** Draws the widgets (WidgetViews in the app; a stand-in in the tests). */
interface WidgetRenderer {
    /** Whether any widget of the app is on the home screen. */
    fun present(context: Context): Boolean

    fun render(
        context: Context,
        state: WidgetState,
    )
}

/** Pause/Resume from the large widget: as the Quick Settings tile does it. */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_PAUSE_RESUME) return
        val waiting = ForgeQueueManager.generationQueue.value.count { it.status != GenerationStatus.FAILED }
        when {
            ForgeQueueManager.queuePauseReason.value == ForgeQueueManager.USER_PAUSED_REASON -> ForgeQueueManager.resumeQueue()
            waiting > 0 -> ForgeQueueManager.pauseByUser()
            // The app was closed (no queue in memory): the widget shows that again.
            else -> ForgeWidgets.widgetsChanged(context)
        }
    }

    companion object {
        const val ACTION_PAUSE_RESUME = "com.example.forgegen.WIDGET_PAUSE_RESUME"
    }
}

object ForgeWidgets {
    private const val TAG = "ForgeWidgets"

    @Volatile var renderer: WidgetRenderer? = null

    // Whether a widget is on the home screen; asked again when one is added or removed.
    @Volatile private var present: Boolean? = null

    @Volatile private var last: WidgetState? = null
    private var lastAt = 0L

    // How many times the widgets were drawn (the tests count them).
    val pushes = AtomicInteger()

    private val today = MutableStateFlow<JobTotals?>(null)
    private val lastDoneAt = MutableStateFlow<Long?>(null)

    @Volatile private var started = false

    /** Follows the queue, the connection and the history from the app's start on, and pushes what the widgets show. */
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
            JobRecorder.recorded.collect { readToday() }
        }
        ForgeRepository.repositoryScope.launch(Dispatchers.Default) {
            val queue =
                combine(
                    ForgeQueueManager.generationQueue,
                    ForgeQueueManager.isGenerating,
                    ForgeQueueManager.progress,
                    ForgeQueueManager.queueSecondsLeft,
                    ForgeQueueManager.queuePauseReason,
                ) { jobs, generating, progress, secondsLeft, reason ->
                    WidgetState(
                        waiting = jobs.count { it.status != GenerationStatus.FAILED },
                        percent = if (generating) (progress * 100).toInt().coerceIn(0, 100) else null,
                        endsAt = secondsLeft?.let { System.currentTimeMillis() + it * 1000 },
                        paused = reason != null,
                        pausedByUser = reason == ForgeQueueManager.USER_PAUSED_REASON,
                    )
                }
            val run = combine(ForgeQueueManager.completedQueueItems, ForgeQueueManager.totalQueueSize) { done, total -> done to total }
            val server = combine(ForgeRepository.connection, ForgeRepository.serverMemory) { connection, memory -> connection to memory }
            combine(queue, run, server, today, lastDoneAt) { q, (done, total), (connection, memory), totals, doneAt ->
                q.copy(
                    connected = connection == ServerConnection.CONNECTED,
                    done = done,
                    total = total,
                    imagesToday = totals?.images,
                    gpuTodayMs = totals?.gpuMs,
                    vramUsedGb = memory?.takeIf { it.hasVram }?.vramUsed,
                    vramTotalGb = memory?.takeIf { it.hasVram }?.vramTotal,
                    lastDoneAt = doneAt,
                )
            }.collect { consider(app, it) }
        }
    }

    /** A widget was added or removed (or the system asks for them again): drawn at once with the latest state. */
    fun widgetsChanged(context: Context) {
        present = null
        val state = last ?: WidgetState()
        draw(context.applicationContext, state)
    }

    private fun consider(
        context: Context,
        state: WidgetState,
    ) {
        val previous = last
        val now = System.currentTimeMillis()
        var next = state
        // The queue ran out: "All done at ...".
        if (previous != null && previous.waiting > 0 && next.waiting == 0) {
            lastDoneAt.value = now
            next = next.copy(lastDoneAt = now)
        }
        if (!WidgetThrottle.shouldPush(previous, lastAt, next, now)) return
        last = next
        lastAt = now
        draw(context, next)
    }

    private fun draw(
        context: Context,
        state: WidgetState,
    ) {
        val renderer = renderer ?: return
        try {
            val shown = present ?: renderer.present(context).also { present = it }
            if (!shown) return
            renderer.render(context, state)
            pushes.incrementAndGet()
        } catch (e: Exception) {
            Log.w(TAG, "Could not update the widgets", e)
        }
    }

    /** Today's images and GPU time from the job history, while it is on. */
    private suspend fun readToday() {
        today.value =
            if (!ForgeRepository.config.value.generationHistory) {
                null
            } else {
                try {
                    ForgeRepository.db.jobRunDao().totalsSince(startOfToday())
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    null
                }
            }
    }

    fun startOfToday(now: Long = System.currentTimeMillis()): Long =
        Calendar
            .getInstance()
            .apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
}
