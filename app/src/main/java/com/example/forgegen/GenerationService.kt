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
import com.example.forgegen.R
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.Locale

class GenerationService : Service() {
    private val channelId = "GenerationChannelAlert"
    private val oomChannelId = "OOMAlertChannel"
    private val silentChannelId = "GenerationChannelSilent"
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val notificationId = 1001

    private var generationStartTime: Long = 0
    private var wasGenerating = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()

        // Background loop updating notification constantly (1Hz)
        // This ensures lock screen and Samsung "Now Bar" updates perfectly while app is suspended
        serviceScope.launch {
            while (isActive) {
                updateNotification()
                delay(1000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ACTION_EXIT") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(notificationId)
            android.os.Process.killProcess(android.os.Process.myPid())
            return START_NOT_STICKY
        }

        // Initial basic notification to satisfy Foreground Service requirements instantly
        val notif = buildNotification(
            isGenerating = false,
            progress = 0f,
            status = "Ready",
            isServerBusy = false,
            queuedCount = 0,
            currentEta = 0.0,
            oomAlert = false,
            verbosity = "Full",
            silent = false,
            steps = 20
        )

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(notificationId, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(notificationId, notif)
        }

        return START_STICKY
    }

    private fun updateNotification() {
        val isGenerating = ForgeState.isGenerating.value
        val progress = ForgeState.progress.value
        val status = ForgeState.statusText.value
        val isServerBusy = ForgeState.isServerBusy.value
        val queuedCount = ForgeState.generationQueue.value.size
        val currentEta = ForgeState.currentEta.value
        val oomAlert = ForgeState.oomAlert.value

        val prefs = getSharedPreferences("ForgeGenPrefs", Context.MODE_PRIVATE)
        val configJson = prefs.getString("config", "{}")
        val stateJson = prefs.getString("last_state", "{}")

        val verbosity = try { JSONObject(configJson).optString("notificationVerbosity", "Full") } catch(e: Exception) { "Full" }
        val silent = try { JSONObject(configJson).optBoolean("silentNotifications", false) } catch(e: Exception) { false }
        val steps = try { JSONObject(stateJson).optInt("steps", 20) } catch(e: Exception) { 20 }

        val isActivelyGenerating = isGenerating || isServerBusy

        // Lock the chronometer start time so it doesn't flicker/reset on every update ping
        if (isActivelyGenerating && !wasGenerating) {
            generationStartTime = System.currentTimeMillis()
        }
        wasGenerating = isActivelyGenerating

        val notification = buildNotification(
            isGenerating, progress, status, isServerBusy, queuedCount,
            currentEta, oomAlert, verbosity, silent, steps
        )

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, notification)
    }

    private fun buildNotification(
        isGenerating: Boolean,
        progress: Float,
        status: String,
        isServerBusy: Boolean,
        queuedCount: Int,
        currentEta: Double,
        oomAlert: Boolean,
        verbosity: String,
        silent: Boolean,
        steps: Int
    ): Notification {
        val isActivelyGenerating = isGenerating || isServerBusy
        val activeChannelId = if (oomAlert) oomChannelId else if (silent) silentChannelId else channelId

        val notifTitle = when {
            oomAlert -> "CRITICAL: Server OOM Error!"
            isActivelyGenerating -> "Forge Generator - Working"
            else -> "Forge Generator"
        }

        val etaString = if (currentEta > 0) " (ETA: ${String.format(Locale.US, "%.1f", currentEta)}s)" else ""

        val jobNo = ForgeState.currentJobNo.value
        val jobCount = ForgeState.currentJobCount.value
        val samplingStep = ForgeState.currentSamplingStep.value
        val samplingSteps = ForgeState.currentSamplingSteps.value
        val currentStep = (progress * steps).toInt()

        val notifText = when {
            oomAlert -> "Out of Memory on Server. Queue has been paused. Tap to manage."
            status.contains("Connection Lost", true) -> if (verbosity == "Simple") "Disconnected" else "Server Offline or Unreachable."
            status.contains("Restoring", true) -> "Restoring prompt..."
            isActivelyGenerating -> {
                val mode = if (isGenerating) "Generating" else "External Task"
                val progStr = "${(progress * 100).toInt()}%"
                val stepStr = if (samplingSteps > 0) "Img ${jobNo + 1}/$jobCount | Step $samplingStep/$samplingSteps" else "Step $currentStep/$steps"

                when (verbosity) {
                    "Simple" -> "$mode..."
                    "Brief" -> "$mode: $progStr"
                    else -> "$mode: $progStr ($stepStr)$etaString\n$status" + if (queuedCount > 0) "\nRemaining in queue: $queuedCount" else ""
                }
            }
            queuedCount > 0 -> if (verbosity == "Simple") "Queued: $queuedCount" else "Ready - Remaining in queue: $queuedCount"
            else -> if (verbosity == "Simple") "Ready" else "Ready - Connected to server."
        }

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val exitIntent = Intent(this, GenerationService::class.java).apply {
            action = "ACTION_EXIT"
        }
        val exitPendingIntent = PendingIntent.getService(this, 1, exitIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val settingsIntent = Intent(this, MainActivity::class.java).apply {
            action = "ACTION_OPEN_SETTINGS"
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val settingsPendingIntent = PendingIntent.getActivity(this, 2, settingsIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, activeChannelId)
            .setContentTitle(notifTitle)
            .setContentText(notifText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notifText))
            .setOngoing(isActivelyGenerating || queuedCount > 0 || oomAlert)
            .setOnlyAlertOnce(!oomAlert)
            .setSilent(isActivelyGenerating && !oomAlert)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openPendingIntent)
            .setDeleteIntent(exitPendingIntent)
            .addAction(android.R.drawable.ic_menu_view, "Open App", openPendingIntent)

        val isSamsungDevice = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

        if (oomAlert) {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val wakeLock = pm.newWakeLock(android.os.PowerManager.FULL_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP, "ForgeGen:OOMAlert")
            wakeLock.acquire(3000)

            builder.setFullScreenIntent(openPendingIntent, true)
            builder.setPriority(NotificationCompat.PRIORITY_MAX)
            builder.setCategory(NotificationCompat.CATEGORY_ERROR)
            builder.setSmallIcon(R.mipmap.ic_launcher_foreground)
        } else {
            builder.addAction(android.R.drawable.ic_menu_preferences, "Settings", settingsPendingIntent)
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Exit App", exitPendingIntent)

            // Samsung OneUI 6/7/8 & Android 14+ "Now Bar" / "Live Pill" Implementation
            if (isSamsungDevice && Build.VERSION.SDK_INT >= 34 && isActivelyGenerating) {
                builder.setCategory(NotificationCompat.CATEGORY_PROGRESS)
                builder.setUsesChronometer(true)
                builder.setWhen(generationStartTime)
                // A transparent vector icon is strictly required for the pill to extract the shape correctly
                builder.setSmallIcon(R.mipmap.ic_launcher_foreground)
                // Primary blue for the pill tint backdrop
                builder.setColor(android.graphics.Color.parseColor("#005BFF"))
            } else {
                builder.setSmallIcon(R.mipmap.ic_launcher_foreground)
            }
        }

        if (isActivelyGenerating && !oomAlert) {
            val max = 100
            val progInt = (progress * 100).toInt()
            builder.setProgress(max, progInt, progInt == 0)
        } else {
            builder.setProgress(0, 0, false)
        }

        return builder.build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            val alertChannel = NotificationChannel(channelId, "App Status (Alerts)", NotificationManager.IMPORTANCE_DEFAULT)
            val silentChannel = NotificationChannel(silentChannelId, "App Status (Silent)", NotificationManager.IMPORTANCE_LOW)
            val oomChannel = NotificationChannel(oomChannelId, "Critical Alerts (OOM)", NotificationManager.IMPORTANCE_HIGH)

            manager?.createNotificationChannel(alertChannel)
            manager?.createNotificationChannel(silentChannel)
            manager?.createNotificationChannel(oomChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}