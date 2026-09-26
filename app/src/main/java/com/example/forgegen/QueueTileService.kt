package com.example.forgegen

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/* ============================================================================
 * QUICK SETTINGS TILE
 * The queue in the phone's Quick Settings: how far it is ("45% · 3 left"), and a tap pauses it (the running job
 * finishes) or resumes it. With nothing queued a tap opens the app. It follows the queue only while the Quick
 * Settings are open.
 * ============================================================================ */
class QueueTileService : TileService() {
    private var scope: CoroutineScope? = null

    private data class Shown(
        val waiting: Int,
        val generating: Boolean,
        val percent: Int,
        val pausedByUser: Boolean,
    )

    override fun onStartListening() {
        super.onStartListening()
        scope?.cancel()
        scope =
            CoroutineScope(Dispatchers.Main + SupervisorJob()).also { tileScope ->
                tileScope.launch {
                    combine(
                        ForgeQueueManager.generationQueue,
                        ForgeQueueManager.isGenerating,
                        ForgeQueueManager.progress,
                        ForgeQueueManager.queuePauseReason,
                    ) { queue, generating, progress, reason ->
                        Shown(
                            waiting = queue.count { it.status != GenerationStatus.FAILED },
                            generating = generating,
                            percent = (progress * 100).toInt(),
                            pausedByUser = reason == ForgeQueueManager.USER_PAUSED_REASON,
                        )
                    }.distinctUntilChanged()
                        .collect { show(it) }
                }
            }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val waiting = ForgeQueueManager.generationQueue.value.count { it.status != GenerationStatus.FAILED }
        when {
            ForgeQueueManager.queuePauseReason.value == ForgeQueueManager.USER_PAUSED_REASON -> ForgeQueueManager.resumeQueue()
            waiting > 0 -> ForgeQueueManager.pauseByUser()
            else -> openApp()
        }
    }

    private fun show(shown: Shown) {
        val tile = qsTile ?: return
        tile.label = "ForgeGen"
        tile.state = if (shown.waiting > 0 && !shown.pausedByUser) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle =
            when {
                shown.waiting == 0 -> "No jobs"
                shown.pausedByUser -> "Paused · ${shown.waiting} left"
                shown.generating -> "${shown.percent}% · ${shown.waiting} left"
                else -> "${shown.waiting} waiting"
            }
        tile.updateTile()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
