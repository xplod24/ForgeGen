package com.yourname.forgegen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class GenerationService : Service() {
    private val channelId = "GenerationChannelAlert"
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private val notificationId = 1001

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ACTION_EXIT") {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            android.os.Process.killProcess(android.os.Process.myPid())
            return START_NOT_STICKY
        }

        // Start the foreground service immediately so Android doesn't kill the app in the background
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(notificationId, buildNotification(0, 100, "ForgeGen Active", false), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(notificationId, buildNotification(0, 100, "ForgeGen Active", false))
        }

        // We leave this running continuously. MainActivity handles the rich UI updates
        // to this exact same notification ID dynamically!

        return START_STICKY // Ensures the service is recreated if the system is extremely low on memory
    }

    private fun buildNotification(progress: Int, max: Int, text: String, showProgress: Boolean): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            this.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val settingsIntent = Intent(this, MainActivity::class.java).apply {
            action = "ACTION_OPEN_SETTINGS"
            this.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val settingsPendingIntent = PendingIntent.getActivity(
            this, 2, settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val exitIntent = Intent("ACTION_EXIT_APP").setPackage(this.packageName)
        val exitPendingIntent = PendingIntent.getBroadcast(
            this, 1, exitIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Forge Generator")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_preferences)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPendingIntent)
            .setDeleteIntent(exitPendingIntent) // Triggers Kill Process when user swipes away
            .addAction(android.R.drawable.ic_menu_view, "Open App", openPendingIntent)
            .addAction(android.R.drawable.ic_menu_preferences, "Settings", settingsPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Exit App", exitPendingIntent)

        if (showProgress) {
            builder.setProgress(max, progress, progress == 0)
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            val alertChannel = NotificationChannel("GenerationChannelAlert", "App Status (Alerts)", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Shows progress of background image generation and server connection status"
            }
            manager?.createNotificationChannel(alertChannel)

            val silentChannel = NotificationChannel("GenerationChannelSilent", "App Status (Silent)", NotificationManager.IMPORTANCE_LOW)
            manager?.createNotificationChannel(silentChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}