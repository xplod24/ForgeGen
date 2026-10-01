package com.example.forgegen

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/* ============================================================================
 * UNLOAD AFTER THE QUEUE (3.6.0)
 * When the user's queue is done, the server's model can leave VRAM so the computer has it back: at once, or 10 or 30
 * minutes later, by an alarm as "Start at" uses (QueueSchedule). Off by default (the owner's decision): it changes how
 * the server behaves. Before unloading, the app checks that nothing waits in its queue, that the server does no job
 * (from its web UI or another app) and that its model is not already gone; a job added meanwhile takes the alarm back.
 * The next job then starts cold (JobRecorder). The alarm can wake an app that was closed: it reads the settings and
 * builds its API client for the one request, without the ping or the service.
 * ============================================================================ */
object AutoUnload {
    private const val TAG = "AutoUnload"
    private const val REQUEST_CODE = 4
    private const val CHECK_TIMEOUT_MS = 25_000L

    const val OFF = 0
    const val AT_ONCE = -1

    /** Off, at once, 10 or 30 minutes after the queue (the order the choice shows them in). */
    val CHOICES = listOf(OFF, AT_ONCE, 10, 30)

    fun of(saved: Int?): Int = saved?.takeIf { it in CHOICES } ?: OFF

    fun label(choice: Int): String =
        when (choice) {
            OFF -> "Off"
            AT_ONCE -> "At once"
            else -> "$choice min"
        }

    // When the alarm set for the last queue goes off; null without one.
    @Volatile var pendingAt: Long? = null
        private set

    /**
     * Whether the server's model may leave now: nothing waits in the app's queue, the server does no job of its own or
     * others' ([serverBusy], null when it could not be asked), and the model is not known to be gone already.
     */
    fun mayUnload(
        ourJobsWaiting: Boolean,
        serverBusy: Boolean?,
        loaded: LoadedModel,
    ): Boolean = !ourJobsWaiting && serverBusy == false && loaded.state != LoadedModel.EMPTY

    /** The queue ran out of jobs: unload as [choice] says (now, later by an alarm, or not at all). */
    fun onQueueDone(
        context: Context,
        choice: Int,
    ) {
        when (choice) {
            OFF -> return
            AT_ONCE -> ForgeRepository.repositoryScope.launch(Dispatchers.IO) { unloadIfIdle() }
            else -> setAlarm(context, System.currentTimeMillis() + choice * 60_000L)
        }
    }

    /** A job came: the model is needed, so no unloading. */
    fun cancel(context: Context) {
        if (pendingAt == null) return
        pendingAt = null
        context.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(context))
    }

    private fun alarmIntent(context: Context) =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, AutoUnloadReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun setAlarm(
        context: Context,
        at: Long,
    ) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        pendingAt = at
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(context))
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(context))
            }
        } catch (e: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(context))
        } catch (e: Exception) {
            Log.e(TAG, "Cannot set the alarm to unload the model", e)
        }
    }

    /** Unloads the server's model when nothing needs it; true when it did. */
    suspend fun unloadIfIdle(): Boolean {
        pendingAt = null
        val api = ForgeRepository.forgeApi ?: return false
        return withTimeoutOrNull(CHECK_TIMEOUT_MS) {
            try {
                val ourJobsWaiting = ForgeQueueManager.generationQueue.value.any { it.status != GenerationStatus.FAILED }
                val progress = api.getProgress(skipImage = true).takeIf { it.isSuccessful }?.body()
                // Jobs of the web UI or another app wait in the server's own queue (none without the web UI: 404).
                val pending =
                    try {
                        api
                            .getPendingTasks()
                            .takeIf { it.isSuccessful }
                            ?.body()
                            ?.size ?: 0
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        0
                    }
                val busy = progress?.let { (it.state?.jobCount ?: 0) > 0 || it.progress > 0.0 || pending > 0 }
                if (!mayUnload(ourJobsWaiting, busy, JobRecorder.loadedNow())) return@withTimeoutOrNull false
                if (!api.unloadCheckpoint().isSuccessful) return@withTimeoutOrNull false
                JobRecorder.modelUnloaded()
                Log.i(TAG, "The model left the server's memory after the queue")
                true
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Could not unload the model after the queue", e)
                false
            }
        } ?: false
    }
}

/** The alarm of "Unload After the Queue": unloads the model if still nothing needs it. */
class AutoUnloadReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                ForgeRepository.prepareApi(context.applicationContext as Application)
                AutoUnload.unloadIfIdle()
            } catch (e: Exception) {
                Log.w("AutoUnload", "The alarm could not unload the model", e)
            } finally {
                pending.finish()
            }
        }
    }
}
