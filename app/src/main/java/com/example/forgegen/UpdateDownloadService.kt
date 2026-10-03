package com.example.forgegen

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/* ============================================================================
 * UPDATE DOWNLOAD (2.3.0-1)
 * "Download" in Settings > Updates downloads the release here, in a foreground service, so a locked screen or the app
 * in the background does not stop it (it ran in the screen's own coroutine before and the phone froze it). The
 * progress is a notification (a Live Update on Android 16+ when "Show Progress as Live Update" is on, see
 * LiveUpdates) and a card in Settings > Updates (SelfUpdate.downloadProgress). Since 3.0.0-3 it only downloads: a file
 * whose SHA-256 matches the release becomes SelfUpdate.readyUpdate, and "Install" (ForgeUpdateManager.installUpdate)
 * is a separate tap.
 * ============================================================================ */
class UpdateDownloadService : Service() {
    companion object {
        private const val TAG = "UpdateDownloadService"
        const val ID_DOWNLOAD_NOTIFICATION = 1_004

        private const val EXTRA_VERSION_CODE = "version_code"
        private const val EXTRA_VERSION_NAME = "version_name"
        private const val EXTRA_URL = "url"
        private const val EXTRA_SHA256 = "sha256"
        private const val EXTRA_SIZE = "size"

        // A long download must not keep the phone awake forever if something hangs.
        private const val WAKE_LOCK_TIMEOUT_MS = 20 * 60 * 1000L

        /** Downloads [manifest] in the background and keeps it ready to install. */
        fun start(
            context: Context,
            manifest: UpdateManifest,
        ) {
            val intent =
                Intent(context, UpdateDownloadService::class.java)
                    .putExtra(EXTRA_VERSION_CODE, manifest.versionCode)
                    .putExtra(EXTRA_VERSION_NAME, manifest.versionName)
                    .putExtra(EXTRA_URL, manifest.url)
                    .putExtra(EXTRA_SHA256, manifest.sha256)
                    .putExtra(EXTRA_SIZE, manifest.size)
            context.startForegroundService(intent)
        }

        private fun Intent.toManifest(): UpdateManifest? {
            val url = getStringExtra(EXTRA_URL) ?: return null
            val name = getStringExtra(EXTRA_VERSION_NAME) ?: return null
            return UpdateManifest(
                versionCode = getIntExtra(EXTRA_VERSION_CODE, 0),
                versionName = name,
                url = url,
                sha256 = getStringExtra(EXTRA_SHA256),
                size = getLongExtra(EXTRA_SIZE, 0L),
            )
        }

        private fun megabytes(bytes: Long) = String.format(Locale.US, "%.1f", bytes / (1024.0 * 1024.0))
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        ForgeNotifications.init(this)
        val manifest = intent?.toManifest()
        // startForegroundService() must be answered with startForeground() at once, even when there is nothing to do.
        startInForeground(manifest?.versionName.orEmpty())
        if (manifest == null) {
            stop()
            return START_NOT_STICKY
        }
        if (job?.isActive == true) return START_NOT_STICKY // a second tap while it downloads
        job = scope.launch { download(manifest) }
        return START_NOT_STICKY
    }

    private fun startInForeground(versionName: String) {
        val notification = progressNotification(versionName, 0L, 0L)
        try {
            ServiceCompat.startForeground(this, ID_DOWNLOAD_NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
        }
    }

    private suspend fun download(manifest: UpdateManifest) {
        holdWakeLock()
        val name = manifest.versionName
        try {
            SelfUpdate.setDownloadProgress(SelfUpdate.DownloadProgress(name, 0L, manifest.size))
            var shownPercent = -1
            val file = SelfUpdate.apkFile(this)
            val downloaded =
                SelfUpdate.download(GitHubApi.create(), manifest, file) { done, total ->
                    SelfUpdate.setDownloadProgress(SelfUpdate.DownloadProgress(name, done, total))
                    val percent = if (total > 0) (done * 100 / total).toInt() else 0
                    // The notification only when the percentage changes: posting it on every chunk would be throttled.
                    if (percent != shownPercent) {
                        shownPercent = percent
                        ForgeNotifications.post(ID_DOWNLOAD_NOTIFICATION, progressNotification(name, done, total))
                    }
                }
            if (!downloaded) {
                fail("The download failed or did not match the release. Try again from Settings > Updates.")
                return
            }
            // Checked: "Install" in Settings > Updates takes it from here. Off screen, a notification says so.
            SelfUpdate.markReady(this, manifest, file)
            if (!SelfUpdate.isAppOnScreen()) SelfUpdate.notifyReady(this, name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Update download failed", e)
            fail("Download error: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            SelfUpdate.setDownloadProgress(null)
            releaseWakeLock()
            stop()
        }
    }

    /** The download's notification: a Live Update where the phone offers it, else a plain progress bar. */
    private fun progressNotification(
        versionName: String,
        done: Long,
        total: Long,
    ): android.app.Notification {
        val percent = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
        val unknown = total <= 0
        val base =
            ForgeNotifications.builder(ForgeNotifications.CHANNEL_PROGRESS)
                ?: NotificationCompat.Builder(this, ForgeNotifications.CHANNEL_PROGRESS)
        val builder =
            base
                .setSmallIcon(R.mipmap.ic_launcher_foreground)
                .setContentTitle("Downloading ForgeGen $versionName".trim())
                .setContentText(
                    when {
                        total > 0 -> "$percent% · ${megabytes(done)} / ${megabytes(total)} MB"
                        else -> "Starting the download"
                    },
                ).setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        if (LiveUpdates.isSupported(this) && ForgeRepository.config.value.nowBarProgress) {
            // The same Live Update as the generation progress (see GenerationService): no custom views, not colorized.
            builder
                .setRequestPromotedOngoing(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setStyle(NotificationCompat.ProgressStyle().setProgress(percent).setProgressIndeterminate(unknown))
            if (percent > 0) builder.setShortCriticalText("$percent%")
        } else {
            builder.setProgress(100, percent, unknown)
        }
        return builder.build()
    }

    /** The download failed: a notification that says why (tap: the app, where "Download" tries again). */
    private fun fail(message: String) {
        val notification =
            ForgeNotifications
                .builder(ForgeNotifications.CHANNEL_RESULTS)
                ?.setContentTitle("ForgeGen update failed")
                ?.setContentText(message)
                ?.setAutoCancel(true)
                ?.build() ?: return
        ForgeNotifications.post(SelfUpdate.ID_UPDATE_NOTIFICATION, notification)
    }

    private fun holdWakeLock() {
        try {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock =
                power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ForgeGen::UpdateDownload").apply {
                    setReferenceCounted(false)
                    acquire(WAKE_LOCK_TIMEOUT_MS)
                }
        } catch (e: Exception) {
            Log.w(TAG, "No wake lock for the download", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release the wake lock", e)
        }
        wakeLock = null
    }

    private fun stop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        SelfUpdate.setDownloadProgress(null)
        super.onDestroy()
    }
}
