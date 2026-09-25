package com.example.forgegen

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

/* ============================================================================
 * FOREGROUND GENERATION SERVICE
 * Maintains the application's lifecycle during active background generation
 * or when the user explicitly enables the persistent background mode.
 * Handles live notification updates and wake locks for critical alerts.
 * ============================================================================ */

class GenerationService : Service() {
    companion object {
        const val ACTION_START_GENERATION = "ACTION_START_GENERATION"
        const val ACTION_UPDATE_PERSISTENCE = "ACTION_UPDATE_PERSISTENCE"

        /** The queue stopped: it is empty or paused after an error, so nothing is being generated. */
        const val ACTION_QUEUE_FINISHED = "ACTION_QUEUE_FINISHED"
        const val ACTION_EXIT_APP = "ACTION_EXIT_APP"
        private const val ACTION_NOTIFICATION_DISMISSED = "ACTION_NOTIFICATION_DISMISSED"
        private const val TAG = "GenerationService"
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val notificationId = ForgeNotifications.ID_SERVICE

    private var wasGenerating = false

    @Volatile private var isNotificationDismissed = false

    private val dismissReceiver =
        object : android.content.BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                if (intent?.action == ACTION_NOTIFICATION_DISMISSED) {
                    isNotificationDismissed = true
                }
            }
        }

    private var partialWakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        // The system can restart this service without the UI (START_STICKY), so it sets up the channels itself too.
        ForgeNotifications.init(this)

        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            partialWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ForgeGen::GenerationWakeLock")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init WakeLock", e)
        }

        val filter = android.content.IntentFilter(ACTION_NOTIFICATION_DISMISSED)
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            dismissReceiver,
            filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        serviceScope.launch {
            var lastProgress = -1
            var lastText = ""
            var lastJobNo = -1

            // Reacts to changes of the generation state instead of waking up every second for as long as the
            // service lives (all day with "Run in Background"); at most one notification update per second.
            combine(
                ForgeQueueManager.isGenerating,
                ForgeRepository.isServerBusy,
                ForgeQueueManager.progress,
                ForgeQueueManager.statusText,
                ForgeRepository.currentJobNo,
            ) { generating, busy, progress, text, jobNo ->
                ServiceState(generating || busy, (progress * 100).toInt(), text, jobNo)
            }.distinctUntilChanged()
                .conflate()
                .collect { state ->
                    val config = ForgeRepository.config.value
                    val mode = config.notificationMode

                    if (state.active && !wasGenerating) {
                        wasGenerating = true
                        acquireWakeLock()
                    } else if (!state.active && wasGenerating) {
                        wasGenerating = false
                        releaseWakeLock()
                        lastProgress = -1
                        // Without this an external job (or a paused queue) left the last progress on screen forever.
                        val queueStopped = ForgeQueueManager.generationQueue.value.isEmpty() || ForgeQueueManager.isQueuePaused.value
                        if (config.enablePersistentService && queueStopped) {
                            ForgeNotifications.post(notificationId, buildCurrentNotification())
                        }
                    }

                    val shouldUpdate =
                        state.progress != lastProgress || state.text != lastText || state.jobNo != lastJobNo || isNotificationDismissed

                    // "Disabled" keeps the static notification the foreground service needs and never refreshes it.
                    if (shouldUpdate && state.active && mode != "Disabled") {
                        lastProgress = state.progress
                        lastText = state.text
                        lastJobNo = state.jobNo
                        isNotificationDismissed = false
                        ForgeNotifications.post(notificationId, buildCurrentNotification())
                    }

                    delay(1000)
                }
        }
    }

    private data class ServiceState(
        val active: Boolean,
        val progress: Int,
        val text: String,
        val jobNo: Int,
    )

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_START_GENERATION -> {
                startForegroundSafe()
            }
            ACTION_UPDATE_PERSISTENCE -> {
                serviceScope.launch {
                    val config = ForgeRepository.config.value
                    val isActivelyGenerating = ForgeQueueManager.isGenerating.value || ForgeRepository.isServerBusy.value
                    if (config.enablePersistentService || isActivelyGenerating) {
                        startForegroundSafe()
                    } else {
                        stopSelf()
                    }
                }
            }
            ACTION_QUEUE_FINISHED -> {
                serviceScope.launch {
                    if (!ForgeRepository.config.value.enablePersistentService) {
                        stopSelf()
                    } else {
                        ForgeNotifications.post(notificationId, buildCurrentNotification())
                    }
                }
            }
            ACTION_EXIT_APP -> {
                // Handled by the service because it is alive whenever its notification is shown; the old receiver
                // lived in MainActivity and did nothing once the activity was gone. The broadcast lets a visible
                // MainActivity close its task before the process ends.
                sendBroadcast(Intent(ACTION_EXIT_APP).setPackage(packageName))
                ForgeNotifications.cancel(ForgeNotifications.ID_QUEUE_PAUSED)
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 300)
                return START_NOT_STICKY
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
            Log.e(TAG, "Failed to acquire WakeLock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (partialWakeLock?.isHeld == true) {
                partialWakeLock?.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release WakeLock", e)
        }
    }

    private fun startForegroundSafe() {
        try {
            val notification = buildCurrentNotification()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    notificationId,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(notificationId, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
        }
    }

    private fun buildCurrentNotification(): Notification =
        createNotification(
            isActivelyGenerating = ForgeQueueManager.isGenerating.value || ForgeRepository.isServerBusy.value,
            progress = ForgeQueueManager.progress.value,
            etaSeconds = ForgeQueueManager.currentEta.value,
            notificationMode = ForgeRepository.config.value.notificationMode,
            jobNo = ForgeRepository.currentJobNo.value,
            jobCount = ForgeRepository.currentJobCount.value,
            batchSize =
                ForgeQueueManager.generationQueue.value
                    .firstOrNull()
                    ?.payload
                    ?.batch_size ?: 1,
        )

    private fun createNotification(
        isActivelyGenerating: Boolean,
        progress: Float,
        etaSeconds: Double,
        notificationMode: String,
        jobNo: Int,
        jobCount: Int,
        batchSize: Int,
    ): Notification {
        val openIntent = ForgeNotifications.openAppIntent(this)

        val exitIntent =
            PendingIntent.getService(
                this,
                1,
                Intent(this, GenerationService::class.java).setAction(ACTION_EXIT_APP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        // Explicit (setPackage): implicit broadcasts do not reach receivers registered as not exported.
        val deleteIntent =
            PendingIntent.getBroadcast(
                this,
                2,
                Intent(ACTION_NOTIFICATION_DISMISSED).setPackage(packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        // Progress lives on the silent channel; the old "Standard" channel made a sound at every generation start.
        // Errors and finished jobs are separate notifications posted by ForgeQueueManager.
        val builder =
            NotificationCompat
                .Builder(this, ForgeNotifications.CHANNEL_PROGRESS)
                .setSmallIcon(R.mipmap.ic_launcher_foreground)
                .setContentIntent(openIntent)
                .setOngoing(false) // Allow dismissal
                .setDeleteIntent(deleteIntent)
                .setOnlyAlertOnce(true)

        if (isActivelyGenerating) {
            builder.color = 0xFF005BFF.toInt()
            val progInt = (progress * 100).toInt()
            // jobCount is 0 until the first progress poll arrives
            val image = if (jobCount > 0) "Image ${jobNo + 1}/$jobCount" else "Generating"
            val eta = if (etaSeconds > 0) " | ETA ${etaSeconds.roundToInt()}s" else ""

            // The values must match the options offered in SetupScreen: "Simple", "Verbose", "Disabled".
            when (notificationMode) {
                "Verbose" -> {
                    builder.setProgress(100, progInt, progInt == 0)
                    builder.setContentTitle("$image | Batch size: $batchSize")
                    builder.setContentText("Progress: $progInt%$eta")
                    builder.addAction(R.drawable.ic_launcher_foreground, "Open App", openIntent)
                }
                "Disabled" -> {
                    builder.setContentTitle("Generating in background")
                }
                else -> { // "Simple"
                    builder.setProgress(100, progInt, progInt == 0)
                    builder.setContentTitle(image)
                    builder.setContentText("Progress: $progInt%$eta")
                }
            }
        } else {
            builder.setContentTitle("ForgeGen is Active")
            builder.setContentText(if (ForgeQueueManager.isQueuePaused.value) "Queue paused" else "Ready for generation")
            builder.setProgress(0, 0, false)
        }
        builder.addAction(R.drawable.ic_launcher_foreground, "Exit App", exitIntent)

        return builder.build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Android 15+ limits dataSync foreground services to 6 hours per 24 h. If the service is still in the
     * foreground when the limit is reached it must stop within a few seconds, otherwise the system crashes the app.
     */
    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        Log.w(TAG, "Foreground service time limit reached (type $fgsType), stopping")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Swiping the app away from Recents must not end a running queue or the "Run in Background" service
        // (that is what the service is for); "Exit App" in the notification quits the app.
        val queueRunning =
            ForgeQueueManager.isGenerating.value ||
                (ForgeQueueManager.generationQueue.value.isNotEmpty() && !ForgeQueueManager.isQueuePaused.value)
        if (!queueRunning && !ForgeRepository.config.value.enablePersistentService) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (partialWakeLock?.isHeld == true) partialWakeLock?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release WakeLock in onDestroy", e)
        }
        try {
            unregisterReceiver(dismissReceiver)
        } catch (e: Exception) {
        }
        serviceScope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }
}
