@file:Suppress("DEPRECATION")

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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*

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
            partialWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ForgeGen::GenerationWakeLock")
        } catch (e: Exception) {
            Log.e("GenerationService", "Failed to init WakeLock", e)
        }

        serviceScope.launch {
            var lastProgress = -1
            var lastText = ""
            var lastJobNo = -1

            while (isActive) {
                val config = ForgeRepository.config.value
                val isActivelyGenerating = ForgeRepository.isGenerating.value || ForgeRepository.isServerBusy.value
                val oomAlert = ForgeRepository.oomAlert.value
                val progress = ForgeRepository.progress.value
                val progInt = (progress * 100).toInt()
                val text = ForgeRepository.statusText.value
                val mode = config.notificationMode
                val jobNo = ForgeRepository.currentJobNo.value
                val jobCount = ForgeRepository.currentJobCount.value

                if (isActivelyGenerating && !wasGenerating) {
                    generationStartTime = System.currentTimeMillis()
                    wasGenerating = true
                    acquireWakeLock()
                } else if (!isActivelyGenerating && wasGenerating) {
                    wasGenerating = false
                    releaseWakeLock()
                }

                val shouldUpdate = (progInt != lastProgress) || (text != lastText) || (jobNo != lastJobNo)

                if (shouldUpdate) {
                    lastProgress = progInt
                    lastText = text
                    lastJobNo = jobNo

                    val notification = createNotification(
                        isActivelyGenerating,
                        progress,
                        text,
                        oomAlert,
                        mode,
                        jobNo,
                        jobCount
                    )

                    val manager = getSystemService(NotificationManager::class.java)
                    manager.notify(notificationId, notification)
                }

                delay(1000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "ACTION_START_GENERATION" -> {
                startForegroundSafe()
            }
            "ACTION_UPDATE_PERSISTENCE" -> {
                serviceScope.launch {
                    val config = ForgeRepository.config.value
                    val isActivelyGenerating = ForgeRepository.isGenerating.value || ForgeRepository.isServerBusy.value
                    if (config.enablePersistentService || isActivelyGenerating) {
                        startForegroundSafe()
                    } else {
                        stopSelf()
                    }
                }
            }
            "ACTION_QUEUE_FINISHED" -> {
                serviceScope.launch {
                    val config = ForgeRepository.config.value
                    if (!config.enablePersistentService) {
                        stopSelf()
                    } else {
                        val manager = getSystemService(NotificationManager::class.java)
                        manager.notify(
                            notificationId,
                            createNotification(false, 0f, "Ready", false, config.notificationMode, 0, 0)
                        )
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun acquireWakeLock() {
        try {
            if (partialWakeLock?.isHeld == false) {
                partialWakeLock?.acquire(10 * 60 * 1000L /*10 minutes*/)
            }
        } catch (e: Exception) {
            Log.e("GenerationService", "Failed to acquire WakeLock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (partialWakeLock?.isHeld == true) {
                partialWakeLock?.release()
            }
        } catch (e: Exception) {
            Log.e("GenerationService", "Failed to release WakeLock", e)
        }
    }

    private fun startForegroundSafe() {
        try {
            val config = ForgeRepository.config.value
            val notification = createNotification(
                ForgeRepository.isGenerating.value || ForgeRepository.isServerBusy.value,
                ForgeRepository.progress.value,
                ForgeRepository.statusText.value,
                ForgeRepository.oomAlert.value,
                config.notificationMode,
                ForgeRepository.currentJobNo.value,
                ForgeRepository.currentJobCount.value
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    notificationId,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(notificationId, notification)
            }
        } catch (e: Exception) {
            Log.e("GenerationService", "startForeground failed", e)
        }
    }

    @SuppressLint("RestrictedApi")
    private fun createNotification(
        isActivelyGenerating: Boolean,
        progress: Float,
        statusText: String,
        oomAlert: Boolean,
        notificationMode: String,
        jobNo: Int,
        jobCount: Int
    ): Notification {
        val config = ForgeRepository.config.value
        val channelId = when {
            oomAlert -> "forge_high"
            config.notificationPriority == "High" -> "forge_high"
            config.notificationPriority == "Low" -> "forge_low"
            else -> "forge_default"
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val exitIntent = PendingIntent.getBroadcast(
            this, 1,
            Intent("ACTION_EXIT_APP"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(isActivelyGenerating || config.enablePersistentService)
            .setOnlyAlertOnce(true)

        if (oomAlert) {
            builder.setContentTitle("Server Error")
            builder.setContentText("Out of Memory (OOM)! Queue paused.")
            builder.color = 0xFFFF0000.toInt()
        } else if (isActivelyGenerating) {
            // Dodane wyświetlanie postępu paczki zadań
            val batchStr = if (jobCount > 1) " (Batch ${jobNo + 1}/$jobCount)" else ""
            builder.setContentTitle("Generating$batchStr")

            if (notificationMode == "Disabled") {
                builder.setContentText("Task in progress...")
            } else {
                builder.setContentText(statusText)
            }
            builder.color = 0xFF005BFF.toInt()
        } else {
            builder.setContentTitle("ForgeGen is Active")
            builder.setContentText("Ready for generation")
            builder.addAction(R.drawable.ic_launcher_foreground, "Exit App", exitIntent)
        }

        if (isActivelyGenerating && !oomAlert && notificationMode != "Disabled") {
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

        manager.createNotificationChannel(highChannel)
        manager.createNotificationChannel(defaultChannel)
        manager.createNotificationChannel(lowChannel)
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