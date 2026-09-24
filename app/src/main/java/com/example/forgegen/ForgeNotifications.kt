package com.example.forgegen

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/* ============================================================================
 * NOTIFICATIONS
 * One place for channels, notification ids and posting, used by the service, the queue,
 * the gallery sync and the Civitai sync. Channels are created at app start, so a notification
 * posted before the generation service ever ran is not silently dropped.
 * ============================================================================ */
@SuppressLint("StaticFieldLeak") // holds the application context only
object ForgeNotifications {
    // Ids kept from the old channels, so settings the user already changed for them survive.
    const val CHANNEL_ALERTS = "forge_high" // queue paused, server out of memory
    const val CHANNEL_RESULTS = "forge_default" // batch / queue finished, sync results
    const val CHANNEL_PROGRESS = "forge_low" // silent: generation service, gallery and Civitai sync progress

    const val ID_SERVICE = 1001
    const val ID_QUEUE_PAUSED = 1002
    const val ID_GALLERY_SYNC = 555
    const val ID_CIVITAI_SYNC = 556

    private const val TAG = "ForgeNotifications"
    private var context: Context? = null

    fun init(appContext: Context) {
        if (context != null) return
        context = appContext.applicationContext
        createChannels(appContext.applicationContext)
    }

    private fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, "Errors", NotificationManager.IMPORTANCE_HIGH))
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULTS, "Finished jobs", NotificationManager.IMPORTANCE_DEFAULT),
        )
        manager.createNotificationChannel(NotificationChannel(CHANNEL_PROGRESS, "Progress", NotificationManager.IMPORTANCE_LOW))
    }

    /** Builder with the app icon and a tap action that opens the app. */
    fun builder(channelId: String): NotificationCompat.Builder? {
        val ctx = context ?: return null
        return NotificationCompat
            .Builder(ctx, channelId)
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setContentIntent(openAppIntent(ctx))
    }

    fun openAppIntent(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx,
            0,
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Posts unless notifications are turned off for the app (e.g. POST_NOTIFICATIONS denied). */
    @SuppressLint("MissingPermission") // areNotificationsEnabled() is false without POST_NOTIFICATIONS
    fun post(
        id: Int,
        notification: Notification,
    ) {
        val ctx = context ?: return
        val manager = NotificationManagerCompat.from(ctx)
        if (!manager.areNotificationsEnabled()) return
        try {
            manager.notify(id, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission revoked", e)
        }
    }

    fun cancel(id: Int) {
        val ctx = context ?: return
        NotificationManagerCompat.from(ctx).cancel(id)
    }
}
