package com.example.forgegen

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.graphics.toColorInt
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.Locale

/* ============================================================================
 * FOREGROUND GENERATION SERVICE
 * Maintains the application's lifecycle during active background generation
 * or when the user explicitly enables the persistent background mode.
 * Handles live notification updates and wake locks for critical alerts.
 * ============================================================================ */

class GenerationService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val notificationId = 1001

    private var generationStartTime: Long = 0
    private var wasGenerating = false

    private var partialWakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()

        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            partialWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ForgeGen:OvernightBatchLock")
        } catch (e: Exception) {
            Log.e("GenerationService", "Failed to initialize WakeLock", e)
        }

        serviceScope.launch {
            while (isActive) {
                updateNotificationAndServiceState(intentAction = null)
                val isBusy = ForgeRepository.isGenerating.value || ForgeRepository.isServerBusy.value || ForgeRepository.generationQueue.value.isNotEmpty()
                delay(if (isBusy) 1000L else 5000L)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == "ACTION_EXIT") {
            try {
                if (partialWakeLock?.isHeld == true) partialWakeLock?.release()
            } catch (e: Exception) {
                Log.e("GenerationService", "Failed to release WakeLock on exit", e)
            }
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(notificationId)
            Process.killProcess(Process.myPid())
            return START_NOT_STICKY
        }

        // Natychmiastowa aktualizacja stanu na podstawie przesłanej akcji (np. z ustawień)
        updateNotificationAndServiceState(action)

        return START_STICKY
    }

    private fun updateNotificationAndServiceState(intentAction: String?) {
        val isGenerating = ForgeRepository.isGenerating.value
        val progress = ForgeRepository.progress.value
        val status = ForgeRepository.statusText.value
        val isServerBusy = ForgeRepository.isServerBusy.value
        val queuedCount = ForgeRepository.generationQueue.value.size
        val currentEta = ForgeRepository.currentEta.value
        val oomAlert = ForgeRepository.oomAlert.value

        val prefs = getSharedPreferences("ForgeGenPrefs", MODE_PRIVATE)
        val configJson = prefs.getString("config", "{}") ?: "{}"
        val stateJson = prefs.getString("last_state", "{}") ?: "{}"

        val verbosity = try { JSONObject(configJson).optString("notificationVerbosity", "Full") } catch(_: Exception) { "Full" }
        val priority = try { JSONObject(configJson).optString("notificationPriority", "Normal") } catch(_: Exception) { "Normal" }
        val showQueueStatus = try { JSONObject(configJson).optBoolean("notifQueueStatus", true) } catch(_: Exception) { true }
        val enablePersistentService = try { JSONObject(configJson).optBoolean("enablePersistentService", false) } catch(_: Exception) { false }

        val steps = try { JSONObject(stateJson).optInt("steps", 20) } catch(_: Exception) { 20 }
        val batchCount = try { JSONObject(stateJson).optInt("batchCount", 1) } catch(_: Exception) { 1 }
        val batchSize = try { JSONObject(stateJson).optInt("batchSize", 1) } catch(_: Exception) { 1 }

        val isActivelyGenerating = isGenerating || isServerBusy || queuedCount > 0

        // BUG #3 FIX: Overnight Batch Mode & OOM Protection
        // WakeLock aktywowany JEDYNIE dla ciężkich zadań, chroniony przed brakiem uprawnień (SecurityException)
        val isHeavyTask = (batchCount >= 50 || batchSize >= 8)
        if (isActivelyGenerating && isHeavyTask) {
            try {
                if (partialWakeLock?.isHeld == false) {
                    partialWakeLock?.acquire(12 * 60 * 60 * 1000L) // Ograniczenie awaryjne: 12 godzin
                }
            } catch (e: SecurityException) {
                Log.e("GenerationService", "Missing WAKE_LOCK permission in Manifest", e)
            } catch (e: Exception) {
                Log.e("GenerationService", "Failed to acquire WakeLock", e)
            }
        } else {
            try {
                if (partialWakeLock?.isHeld == true) {
                    partialWakeLock?.release()
                }
            } catch (e: Exception) {
                Log.e("GenerationService", "Failed to release WakeLock", e)
            }
        }

        if (isActivelyGenerating && !wasGenerating) {
            generationStartTime = System.currentTimeMillis()
        }
        wasGenerating = isActivelyGenerating

        // Czy usługa powinna nadal zachowywać najwyższy priorytet (Foreground)?
        val shouldBeForeground = isActivelyGenerating || enablePersistentService

        val notification = buildNotification(
            isGenerating, progress, status, isServerBusy, queuedCount,
            currentEta, oomAlert, verbosity, priority, steps, showQueueStatus,
            isOngoing = shouldBeForeground // BUG #5 FIX: Dynamiczne sterowanie flagą Ongoing
        )

        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // BUG #5 FIX: Logika przełączania usługi na "usuwalną" w locie
        if (shouldBeForeground) {
            startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            // Zdejmujemy flagę Foreground (usuwa "klej"), ale aktualizujemy powiadomienie jako zwykłe (dismissible)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            manager.notify(notificationId, notification)

            // Jeżeli akcja pochodzi z odznaczenia opcji w UI lub zakończenia kolejki, ubij usługę dla oszczędności baterii
            if (intentAction == "ACTION_UPDATE_PERSISTENCE" || intentAction == "ACTION_QUEUE_FINISHED") {
                stopSelf()
            }
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("FullScreenIntentPolicy")
    private fun buildNotification(
        isGenerating: Boolean,
        progress: Float,
        status: String,
        isServerBusy: Boolean,
        queuedCount: Int,
        currentEta: Double,
        oomAlert: Boolean,
        verbosity: String,
        priority: String,
        steps: Int,
        showQueueStatus: Boolean,
        isOngoing: Boolean
    ): Notification {
        val isActivelyGenerating = isGenerating || isServerBusy

        val activeChannelId = if (oomAlert) "forge_high" else when (priority) {
            "High" -> "forge_high"
            "Low" -> "forge_low"
            else -> "forge_default"
        }

        val notifTitle = when {
            oomAlert -> "CRITICAL: Server OOM Error!"
            isActivelyGenerating -> "Forge Generator - Working"
            else -> "Forge Generator"
        }

        val etaString = if (currentEta > 0) " (ETA: ${String.format(Locale.US, "%.1f", currentEta)}s)" else ""

        val jobNo = ForgeRepository.currentJobNo.value
        val jobCount = ForgeRepository.currentJobCount.value
        val samplingStep = ForgeRepository.currentSamplingStep.value
        val samplingSteps = ForgeRepository.currentSamplingSteps.value
        val currentStep = (progress * steps).toInt()

        val queueText = if (showQueueStatus && queuedCount > 0) "\nRemaining in queue: $queuedCount" else ""

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
                    else -> "$mode: $progStr ($stepStr)$etaString\n$status$queueText"
                }
            }
            queuedCount > 0 -> if (verbosity == "Simple") "Queued: $queuedCount" else "Ready$queueText"
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
            .setOngoing(isOngoing)
            .setOnlyAlertOnce(!oomAlert && isActivelyGenerating) // Zapobiega ciągłemu dźwiękowi podczas aktualizacji progressu
            .setSilent(isActivelyGenerating && !oomAlert)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openPendingIntent)
            .setDeleteIntent(exitPendingIntent)
            .addAction(android.R.drawable.ic_menu_view, "Open App", openPendingIntent)

        val isSamsungDevice = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

        if (oomAlert) {
            try {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                val wakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "ForgeGen:OOMAlert")
                wakeLock.acquire(3000)
            } catch (e: SecurityException) {
                Log.e("GenerationService", "Missing WAKE_LOCK permission for OOM alert", e)
            }

            builder.setFullScreenIntent(openPendingIntent, true)
            builder.setPriority(NotificationCompat.PRIORITY_MAX)
            builder.setCategory(NotificationCompat.CATEGORY_ERROR)
            builder.setSmallIcon(R.mipmap.ic_launcher_foreground)
        } else {
            builder.addAction(android.R.drawable.ic_menu_preferences, "Settings", settingsPendingIntent)
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Exit App", exitPendingIntent)

            if (isSamsungDevice && Build.VERSION.SDK_INT >= 34 && isActivelyGenerating) {
                builder.setCategory(NotificationCompat.CATEGORY_PROGRESS)
                builder.setUsesChronometer(true)
                builder.setWhen(generationStartTime)
                builder.setSmallIcon(R.mipmap.ic_launcher_foreground)
                builder.setColor("#005BFF".toColorInt())
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
        val manager = getSystemService(NotificationManager::class.java)

        val highChannel = NotificationChannel("forge_high", "High Priority Alerts", NotificationManager.IMPORTANCE_HIGH)
        val defaultChannel = NotificationChannel("forge_default", "Standard Alerts", NotificationManager.IMPORTANCE_DEFAULT)
        val lowChannel = NotificationChannel("forge_low", "Silent Alerts", NotificationManager.IMPORTANCE_LOW)

        manager?.createNotificationChannel(highChannel)
        manager?.createNotificationChannel(defaultChannel)
        manager?.createNotificationChannel(lowChannel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (partialWakeLock?.isHeld == true) partialWakeLock?.release()
        } catch (e: Exception) {
            Log.e("GenerationService", "Failed to release WakeLock in onDestroy", e)
        }
        serviceScope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }
}