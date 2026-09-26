package com.example.forgegen

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/* ============================================================================
 * QUEUE SCHEDULE
 * "Start at": the queue waits until a time of day the user picked (ForgeQueueManager.scheduledStart). Meanwhile the
 * generation service keeps the app alive, without the wake lock, and an alarm wakes the phone at that time; an exact
 * one where Android allows it (SCHEDULE_EXACT_ALARM, granted by default up to Android 13), otherwise one Android may
 * deliver a few minutes late to save battery.
 * ============================================================================ */
object QueueSchedule {
    private const val TAG = "QueueSchedule"
    private const val REQUEST_CODE = 3

    /** The next time the clock shows [hour]:[minute] after [now] (today, or tomorrow when that has passed). */
    fun nextOccurrence(
        hour: Int,
        minute: Int,
        now: Long = System.currentTimeMillis(),
    ): Long {
        val at =
            Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
        if (at.timeInMillis <= now) at.add(Calendar.DAY_OF_MONTH, 1)
        return at.timeInMillis
    }

    /** The time of day of [millis] in the phone's format ("01:00" or "1:00 AM"). */
    fun formatTime(millis: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))

    private fun alarmIntent(context: Context) =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, QueueScheduleReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun setAlarm(
        context: Context,
        at: Long,
    ) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(context))
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(context))
            }
        } catch (e: SecurityException) {
            // The exact-alarm permission was taken back between the check and the call.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(context))
        } catch (e: Exception) {
            Log.e(TAG, "Cannot set the alarm for the queue", e)
        }
    }

    fun cancelAlarm(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(context))
    }
}

/** The alarm of "Start at": starts the waiting queue. */
class QueueScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        // A moment awake for the queue to send its first job; from then on the service holds the wake lock.
        context
            .getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ForgeGen::ScheduledStart")
            ?.acquire(30_000)
        ForgeQueueManager.onScheduledTime()
    }
}
