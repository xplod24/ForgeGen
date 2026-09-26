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
 * Keeps the app alive while the queue works (ForgeQueueManager.isQueueActive): a job runs, or jobs wait to be sent,
 * also while the queue waits for a lost connection. It holds a wake lock for that time and stops by itself when the
 * queue stops. Its type is specialUse, which has no daily time limit (dataSync stops after 6 hours a day on
 * Android 15+).
 * ============================================================================ */

class GenerationService : Service() {
    companion object {
        const val ACTION_START_GENERATION = "ACTION_START_GENERATION"
        const val ACTION_EXIT_APP = "ACTION_EXIT_APP"
        private const val ACTION_NOTIFICATION_DISMISSED = "ACTION_NOTIFICATION_DISMISSED"
        private const val TAG = "GenerationService"

        // Taken with a timeout (in case the service dies without releasing it) and renewed while the queue works;
        // it used to expire after 10 minutes of generation and was not taken again.
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L
        private const val WAKE_LOCK_RENEW_MS = 5 * 60 * 1000L
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val notificationId = ForgeNotifications.ID_SERVICE

    private var wasActive = false
    private var wakeLockKeeper: Job? = null

    // Stopping before startForeground() (which must follow startForegroundService()) would crash the app.
    @Volatile private var isInForeground = false

    // The latest start: stopping with it does nothing if a newer job started the service meanwhile.
    @Volatile private var lastStartId = 0

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
        ForgeNotifications.init(this) // the channels must exist before the first notification

        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            // Not reference-counted: each acquire(timeout) renews the one lock instead of stacking another.
            partialWakeLock =
                pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ForgeGen::GenerationWakeLock").apply {
                    setReferenceCounted(false)
                }
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

            // Reacts to changes of the generation state instead of waking up every second; at most one
            // notification update per second.
            combine(
                combine(
                    ForgeQueueManager.isQueueActive,
                    ForgeQueueManager.isGenerating,
                    ForgeRepository.isServerBusy,
                ) { queueActive, generating, busy ->
                    (queueActive || busy) to (generating || busy)
                },
                ForgeQueueManager.progress,
                ForgeQueueManager.statusText,
                ForgeRepository.currentJobNo,
            ) { (active, generating), progress, text, jobNo ->
                ServiceState(active, generating, (progress * 100).toInt(), text, jobNo)
            }.distinctUntilChanged()
                .conflate()
                .collect { state ->
                    if (state.active && !wasActive) {
                        wasActive = true
                        holdWakeLock()
                    } else if (!state.active && wasActive) {
                        wasActive = false
                        releaseWakeLock()
                        lastProgress = -1
                    }
                    if (!state.active) {
                        stopWhenIdle()
                        return@collect
                    }

                    val shouldUpdate =
                        state.progress != lastProgress || state.text != lastText || state.jobNo != lastJobNo || isNotificationDismissed

                    // "Disabled" keeps the static notification the foreground service needs and never refreshes it.
                    if (shouldUpdate && ForgeRepository.config.value.notificationMode != "Disabled") {
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
        val generating: Boolean,
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
                lastStartId = startId
                startForegroundSafe()
                stopWhenIdle() // the job may already be over
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
            }
            else -> stopSelf(startId) // only the queue starts this service
        }
        // Not restarted after the process was killed: without the app's start nothing would run in it.
        return START_NOT_STICKY
    }

    /**
     * Stops the service once the queue no longer works (not before startForeground, see [isInForeground]).
     * isGenerating is set before a job starts the service, so it covers the moment isQueueActive is still catching up.
     */
    private fun stopWhenIdle() {
        val working = ForgeQueueManager.isGenerating.value || ForgeQueueManager.isQueueActive.value || ForgeRepository.isServerBusy.value
        if (!isInForeground || working) return
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf(lastStartId)
    }

    private fun holdWakeLock() {
        wakeLockKeeper?.cancel()
        wakeLockKeeper =
            serviceScope.launch {
                while (isActive) {
                    try {
                        partialWakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to acquire WakeLock", e)
                    }
                    delay(WAKE_LOCK_RENEW_MS)
                }
            }
    }

    private fun releaseWakeLock() {
        wakeLockKeeper?.cancel()
        wakeLockKeeper = null
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
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(notificationId, notification)
            }
            isInForeground = true
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
            // Between jobs, or waiting for the server (the service only runs while the queue works).
            builder.setContentTitle("ForgeGen queue")
            builder.setContentText(
                when {
                    ForgeQueueManager.queuePauseReason.value == ForgeQueueManager.CONNECTION_LOST_REASON ->
                        "Connection lost, waiting for the server"
                    !ForgeRepository.isConnected.value -> "Waiting for the server"
                    else -> "Waiting to send the next job"
                },
            )
            builder.setProgress(0, 0, false)
        }
        builder.addAction(R.drawable.ic_launcher_foreground, "Exit App", exitIntent)

        return builder.build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Swiping the app away from Recents must not end a running queue; "Exit App" in the notification quits it.
        stopWhenIdle()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        try {
            unregisterReceiver(dismissReceiver)
        } catch (e: Exception) {
        }
        serviceScope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }
}
