package com.example.forgegen

import android.app.ActivityManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest

/* ============================================================================
 * SELF UPDATE (2.0.2)
 * GitHub releases reach the phone without the app being opened:
 * - UpdateCheckJob asks GitHub once a day on Wi-Fi (JobScheduler, kept across restarts of the phone; every 6 hours up
 *   to 3.6.0-2). The app's start and this job share one automatic check a day (3.6.1, the owner's request,
 *   claimDailyCheck): whichever comes first that day asks, and a check without a connection leaves the next one to
 *   the next day. The release it finds is kept (saveOffer), so the app offers it on a later start that day.
 * - A newer release is downloaded (SHA-256 checked) and installed with PackageInstaller. On Android 12+ an app may
 *   update itself without asking (USER_ACTION_NOT_REQUIRED + UPDATE_PACKAGES_WITHOUT_USER_ACTION); where the system
 *   still wants a confirmation (the first time, some phones), a notification or the open app asks for it.
 * - Never while the queue works (installing ends the app's process) or while the app is on screen (Settings > Updates
 *   offers the update there; "Install Update" downloads it in UpdateDownloadService). "Install Updates
 *   Automatically" off (the default since 3.6.1): one notification for each new version.
 * - After a silent update UpdatedReceiver says so in a notification (the app itself is not restarted).
 * - From the app (3.0.0-3, the owner's request) it is two steps: "Download" (UpdateDownloadService) keeps the file once
 *   its SHA-256 matches the release (readyUpdate), and only then "Install" sends the app to the background and hands
 *   the file to PackageInstaller (installing until the app is replaced or the system says why not). One tap used to
 *   do both, and the card offered it again while the install waited, so each tap started another install.
 * ============================================================================ */
object SelfUpdate {
    private const val TAG = "SelfUpdate"

    const val ACTION_INSTALL_STATUS = "com.example.forgegen.UPDATE_INSTALL_STATUS"
    const val CHECK_EVERY_MS = 24 * 60 * 60 * 1000L

    // The window inside each day in which the system may run the check, when it suits the battery.
    private const val CHECK_FLEX_MS = 6 * 60 * 60 * 1000L
    private const val JOB_ID = 4_201
    const val ID_UPDATE_NOTIFICATION = 1_003

    // Kept in SharedPreferences, readable without the app's database (the background check starts with nothing).
    private const val PREFS = "updates"
    private const val KEY_AUTO_INSTALL = "auto_install"
    private const val KEY_INSTALLING = "installing_version"
    private const val KEY_NOTIFIED = "notified_version"

    // The day ("2026-10-03") of the last automatic check, and the newest release it found (3.6.1).
    private const val KEY_AUTO_CHECK_DAY = "auto_check_day"
    private const val KEY_OFFER = "offered_update"
    private val gson = com.google.gson.Gson()

    // "<versionCode>:<file length>" of the downloaded update whose SHA-256 matched the release.
    private const val KEY_READY = "ready_update"

    /** What the background check does with the latest release. */
    enum class Action { NONE, NOTIFY, WAIT, INSTALL }

    /**
     * An update downloaded from the app ("Download", UpdateDownloadService): its version and the bytes so far and in
     * all (0: not known yet). Settings > Updates shows it.
     */
    data class DownloadProgress(
        val versionName: String,
        val done: Long,
        val total: Long,
    ) {
        val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    }

    private val _downloadProgress = MutableStateFlow<DownloadProgress?>(null)
    val downloadProgress: StateFlow<DownloadProgress?> = _downloadProgress.asStateFlow()

    fun setDownloadProgress(progress: DownloadProgress?) {
        _downloadProgress.value = progress
    }

    /** A downloaded update whose file matched the release: "Install" can take it (3.0.0-3). */
    data class ReadyUpdate(
        val versionCode: Int,
        val versionName: String,
        val size: Long,
    )

    private val _readyUpdate = MutableStateFlow<ReadyUpdate?>(null)
    val readyUpdate: StateFlow<ReadyUpdate?> = _readyUpdate.asStateFlow()

    // The version being installed from the app, from "Install" until the app is replaced or the install fails.
    private val _installing = MutableStateFlow<String?>(null)
    val installing: StateFlow<String?> = _installing.asStateFlow()

    // The system's confirmation screen, while it waits for the user (the card offers it again).
    private val _pendingConfirm = MutableStateFlow<Intent?>(null)
    val pendingConfirm: StateFlow<Intent?> = _pendingConfirm.asStateFlow()

    fun setInstalling(versionName: String?) {
        _installing.value = versionName
        if (versionName == null) _pendingConfirm.value = null
    }

    fun setPendingConfirm(confirm: Intent?) {
        _pendingConfirm.value = confirm
    }

    /** The file of [manifest] matched the release: it stays ready for "Install", also after a restart. */
    fun markReady(
        context: Context,
        manifest: UpdateManifest,
        file: File,
    ) {
        prefs(context).edit().putString(KEY_READY, "${manifest.versionCode}:${file.length()}").apply()
        _readyUpdate.value = ReadyUpdate(manifest.versionCode, manifest.versionName, file.length())
    }

    fun clearReady(context: Context) {
        prefs(context).edit().remove(KEY_READY).apply()
        _readyUpdate.value = null
    }

    /** Whether [manifest]'s update is already downloaded and checked (its file still there, of the same size). */
    fun refreshReady(
        context: Context,
        manifest: UpdateManifest?,
    ) {
        val file = apkFile(context)
        val saved = prefs(context).getString(KEY_READY, null)
        _readyUpdate.value =
            if (manifest != null && file.exists() && saved == "${manifest.versionCode}:${file.length()}") {
                ReadyUpdate(manifest.versionCode, manifest.versionName, file.length())
            } else {
                null
            }
    }

    fun decide(
        installedCode: Int,
        latestCode: Int?,
        autoInstall: Boolean,
        appOnScreen: Boolean,
        queueWorking: Boolean,
    ): Action =
        when {
            latestCode == null || latestCode <= installedCode -> Action.NONE
            appOnScreen -> Action.NONE // the app's own update dialog offers it
            !autoInstall -> Action.NOTIFY
            queueWorking -> Action.WAIT
            else -> Action.INSTALL
        }

    fun apkFile(context: Context) = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "ForgeGen_Update.apk")

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun setAutoInstall(
        context: Context,
        on: Boolean,
    ) = prefs(context).edit().putBoolean(KEY_AUTO_INSTALL, on).apply()

    // Off unless the user turns it on (3.6.1, the owner's request; on before).
    private fun isAutoInstall(context: Context) = prefs(context).getBoolean(KEY_AUTO_INSTALL, false)

    /** One of the app's screens is visible (a foreground service alone does not count). */
    fun isAppOnScreen(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
            info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }

    /** Whether the daily job must be (re)planned: none is, or one with another period (3.6.0-2 and older: 6 hours). */
    fun needsScheduling(pendingIntervalMs: Long?): Boolean = pendingIntervalMs != CHECK_EVERY_MS

    /** The periodic check; scheduling it again keeps the one already planned, and replaces one of an older period. */
    fun scheduleChecks(context: Context) {
        try {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val pending = scheduler.getPendingJob(JOB_ID)
            if (!needsScheduling(pending?.intervalMillis)) return
            if (pending != null) scheduler.cancel(JOB_ID)
            scheduler.schedule(
                JobInfo
                    .Builder(JOB_ID, ComponentName(context, UpdateCheckJob::class.java))
                    .setPeriodic(CHECK_EVERY_MS, CHECK_FLEX_MS)
                    // Only on Wi-Fi: with "Install Updates Automatically" on it downloads the APK (about 7 MB).
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                    .setPersisted(true)
                    .build(),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not schedule the update check", e)
        }
    }

    // ---------------------------------------------------------------------------------------------- once a day

    /** The day of [now] in the phone's time zone, e.g. "2026-10-03". */
    fun dayOf(
        now: Long,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): String =
        java.time.Instant
            .ofEpochMilli(now)
            .atZone(zone)
            .toLocalDate()
            .toString()

    /** Whether an automatic check is due on [today]: none ran that day yet ([lastDay]: the day of the last one). */
    fun autoCheckDue(
        lastDay: String?,
        today: String,
    ): Boolean = lastDay != today

    /**
     * Takes today's automatic check (3.6.1): true for the first caller of the day, the app's start or the background
     * job, false after. It is taken before GitHub is asked, so a check without a connection leaves the next to tomorrow.
     */
    @Synchronized
    fun claimDailyCheck(
        context: Context,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val today = dayOf(now)
        if (!autoCheckDue(prefs(context).getString(KEY_AUTO_CHECK_DAY, null), today)) return false
        prefs(context).edit().putString(KEY_AUTO_CHECK_DAY, today).apply()
        return true
    }

    /** Keeps [manifest], a newer release a check found, for the app's later starts on a day without a check. */
    fun saveOffer(
        context: Context,
        manifest: UpdateManifest,
    ) = prefs(context).edit().putString(KEY_OFFER, gson.toJson(manifest)).apply()

    /** The release a check found last, when it is newer than [installedCode]; null otherwise. */
    fun savedOffer(
        context: Context,
        installedCode: Int,
    ): UpdateManifest? {
        val json = prefs(context).getString(KEY_OFFER, null) ?: return null
        return runCatching { gson.fromJson(json, UpdateManifest::class.java) }
            .getOrNull()
            ?.takeIf { it.versionCode > installedCode }
    }

    // ---------------------------------------------------------------------------------------------- download

    fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * The release's APK in [file], downloaded unless an earlier download of it is already there; false when it could
     * not be downloaded or its SHA-256 digest does not match GitHub's (the file is deleted then).
     */
    suspend fun download(
        api: GitHubApi,
        manifest: UpdateManifest,
        file: File,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): Boolean {
        if (file.exists() && manifest.sha256 != null && runCatching { sha256Of(file) }.getOrNull().equals(manifest.sha256, true)) {
            return true
        }
        file.delete()
        val response = api.downloadAsset(manifest.url)
        val body = response.body()
        if (!response.isSuccessful || body == null) return false
        val total = body.contentLength().takeIf { it > 0 } ?: manifest.size
        var downloaded = 0L
        var lastReport = 0L
        body.byteStream().use { input ->
            file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    downloaded += read
                    val now = System.currentTimeMillis()
                    if (now - lastReport > 200) {
                        lastReport = now
                        onProgress(downloaded, total)
                    }
                }
            }
        }
        onProgress(downloaded, total)
        // Without a digest from GitHub the HTTPS download is trusted as it is.
        if (manifest.sha256 != null && !sha256Of(file).equals(manifest.sha256, ignoreCase = true)) {
            file.delete()
            return false
        }
        return true
    }

    // ---------------------------------------------------------------------------------------------- install

    /**
     * Installs [apk] (a newer version of this app) with PackageInstaller: without asking on Android 12+ where the
     * system allows it; otherwise UpdateStatusReceiver gets the system's confirmation screen to show. Installing ends
     * the app's process.
     */
    fun install(
        context: Context,
        apk: File,
        versionName: String,
    ) {
        val installer = context.packageManager.packageInstaller
        val params =
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(apk.length())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                // Android 14+: later updates stay this app's own (no other installer takes them over).
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) setRequestUpdateOwnership(true)
            }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                prefs(context).edit().putString(KEY_INSTALLING, versionName).apply()
                val status =
                    PendingIntent.getBroadcast(
                        context,
                        sessionId,
                        Intent(context, UpdateStatusReceiver::class.java).setAction(ACTION_INSTALL_STATUS).putExtra("version", versionName),
                        // Mutable: the system adds the result to it.
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    )
                session.commit(status.intentSender)
            }
        } catch (e: Exception) {
            installer.abandonSession(sessionId)
            throw e
        }
    }

    // ---------------------------------------------------------------------------------------------- background check

    /** The periodic check: what [decide] says, done; at most once a day together with the app's start (3.6.1). */
    suspend fun checkInBackground(context: Context) {
        if (!claimDailyCheck(context)) return
        val api = GitHubApi.create()
        val response = api.getLatestRelease(ForgeUpdateManager.UPDATE_REPOSITORY)
        if (!response.isSuccessful) return
        val manifest = response.body()?.toUpdateManifest() ?: return
        val installedCode =
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .longVersionCode
                .toInt()
        if (manifest.versionCode > installedCode) saveOffer(context, manifest)
        val queueWorking = ForgeQueueManager.isQueueActive.value || ForgeQueueManager.isGenerating.value
        val action = decide(installedCode, manifest.versionCode, isAutoInstall(context), isAppOnScreen(), queueWorking)
        Log.i(TAG, "Latest ${manifest.versionName} (${manifest.versionCode}), installed $installedCode: $action")
        when (action) {
            Action.NONE -> Unit
            Action.NOTIFY -> notifyAvailable(context, manifest.versionName, "Tap to open ForgeGen and install it.")
            Action.WAIT -> notifyAvailable(context, manifest.versionName, "It installs itself once the queue is done.")
            Action.INSTALL -> {
                val file = apkFile(context)
                if (!download(api, manifest, file)) {
                    Log.w(TAG, "The update could not be downloaded; the next check tries again")
                    return
                }
                markReady(context, manifest, file)
                // The user may have opened the app or started the queue during the download.
                if (isAppOnScreen() || ForgeQueueManager.isQueueActive.value || ForgeQueueManager.isGenerating.value) return
                install(context, file, manifest.versionName)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------- notifications

    /** "A new version is available": once per version, from whichever check finds it first (3.6.1). */
    fun notifyAvailable(
        context: Context,
        versionName: String,
        text: String,
    ) {
        // Once per version: one notification for each new version, whichever check finds it.
        if (prefs(context).getString(KEY_NOTIFIED, null) == versionName) return
        prefs(context).edit().putString(KEY_NOTIFIED, versionName).apply()
        post(context, "ForgeGen $versionName is available", text, ForgeNotifications.openAppIntent(context))
    }

    fun notifyConfirm(
        context: Context,
        confirm: Intent,
        versionName: String?,
    ) {
        val tap = PendingIntent.getActivity(context, 7, confirm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        post(context, "Install ForgeGen ${versionName.orEmpty()}".trim(), "Android wants a confirmation this time. Tap to install.", tap)
    }

    fun notifyFailed(
        context: Context,
        message: String?,
    ) {
        prefs(context).edit().remove(KEY_INSTALLING).apply()
        // The card offers "Install" again (the downloaded file stays ready).
        setInstalling(null)
        val text = message ?: "The system did not install the update."
        post(context, "ForgeGen update failed", text, ForgeNotifications.openAppIntent(context))
    }

    /** A downloaded update is ready and the app is not on screen: one tap opens it, where "Install" waits. */
    fun notifyReady(
        context: Context,
        versionName: String,
    ) = post(context, "ForgeGen $versionName is downloaded", "Tap to open ForgeGen and install it.", ForgeNotifications.openAppIntent(context))

    /** The system's installer screen for [file], for an update PackageInstaller refused. */
    fun installerIntent(
        context: Context,
        file: File,
    ): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** PackageInstaller could not be used: the system's installer screen, one tap away in a notification. */
    fun offerInstallerScreen(
        context: Context,
        file: File,
        versionName: String,
    ) {
        val view = installerIntent(context, file)
        val tap = PendingIntent.getActivity(context, 8, view, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        post(context, "Install ForgeGen $versionName", "Downloaded. Tap to install it.", tap)
    }

    /** After the app was replaced: says so if this app installed the update (the new version runs this). */
    fun onUpdated(context: Context) {
        clearReady(context)
        val installing = prefs(context).getString(KEY_INSTALLING, null) ?: return
        prefs(context).edit().remove(KEY_INSTALLING).apply()
        post(context, "ForgeGen updated to $installing", "Tap to open it and see what's new.", ForgeNotifications.openAppIntent(context))
    }

    private fun post(
        context: Context,
        title: String,
        text: String,
        tap: PendingIntent,
    ) {
        ForgeNotifications.init(context)
        val notification =
            ForgeNotifications
                .builder(ForgeNotifications.CHANNEL_RESULTS)
                ?.setContentTitle(title)
                ?.setContentText(text)
                ?.setContentIntent(tap)
                ?.setAutoCancel(true)
                ?.build() ?: return
        ForgeNotifications.post(ID_UPDATE_NOTIFICATION, notification)
    }
}

/** The periodic update check (SelfUpdate.scheduleChecks). */
class UpdateCheckJob : JobService() {
    private var scope: CoroutineScope? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val jobScope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scope = it }
        jobScope.launch {
            try {
                SelfUpdate.checkInBackground(applicationContext)
            } catch (e: Exception) {
                Log.w("UpdateCheckJob", "Update check failed", e)
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        scope?.cancel()
        return true
    }
}

/** The result of an install session: the system's confirmation to show, or why it failed. */
class UpdateStatusReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val version = intent.getStringExtra("version")
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm =
                    (
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(Intent.EXTRA_INTENT)
                        }
                    ) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Kept for the card in Settings > Updates, which offers it again ("Confirm Install").
                SelfUpdate.setPendingConfirm(confirm)
                // On screen the confirmation opens at once; otherwise a notification waits for the user.
                if (SelfUpdate.isAppOnScreen()) {
                    try {
                        context.startActivity(confirm)
                        return
                    } catch (e: Exception) {
                        Log.w("UpdateStatusReceiver", "Could not open the confirmation", e)
                    }
                }
                SelfUpdate.notifyConfirm(context, confirm, version)
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // UpdatedReceiver tells the user
            else -> SelfUpdate.notifyFailed(context, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
    }
}

/** The app was replaced by a newer version (ACTION_MY_PACKAGE_REPLACED). */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        SelfUpdate.onUpdated(context)
        SelfUpdate.scheduleChecks(context)
    }
}
