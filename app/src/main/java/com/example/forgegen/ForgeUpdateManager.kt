package com.example.forgegen

import android.app.Application
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/* ============================================================================
 * UPDATE MANAGER (OTA)
 * Checks the latest GitHub release of the app when it starts (at most every 15 minutes, or on "Check for Updates").
 * "Install Update" hands the release to UpdateDownloadService, which downloads it in the background and installs it
 * through SelfUpdate (PackageInstaller: on Android 12+ without the system's confirmation where it allows that).
 * The background check without the app open is SelfUpdate's.
 * A release is an update when its tag "v<major>.<minor>.<patch>[-<micro>]" maps to a higher versionCode than the
 * installed build.
 * ============================================================================ */
class ForgeUpdateManager(
    private val application: Application,
    private val gitHubApi: GitHubApi,
    private val showToast: (String) -> Unit,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "ForgeUpdateManager"

        /** owner/repo whose latest release is installed as the update. */
        const val UPDATE_REPOSITORY = "xplod24/ForgeGen"

        // Automatic checks at the start: GitHub allows 60 anonymous API calls per hour and IP.
        private const val AUTO_CHECK_EVERY_MS = 15 * 60 * 1000L
        private const val LAST_CHECK_KEY = "last_check_ms"
    }

    private val _updateManifest = MutableStateFlow<UpdateManifest?>(null)
    val updateManifest: StateFlow<UpdateManifest?> = _updateManifest.asStateFlow()

    /** An update being downloaded from the app (UpdateDownloadService); null when none is. */
    val updateDownload: StateFlow<SelfUpdate.DownloadProgress?> = SelfUpdate.downloadProgress

    /**
     * Checks for a newer release. An automatic check (not [manual]) is skipped when one ran in the last 15 minutes
     * (it used to run once a day, so a release made after it waited until the next day).
     */
    fun checkForUpdates(
        manual: Boolean = false,
        offerAnyRelease: Boolean = false,
    ) {
        scope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            if (!manual) {
                val prefs = application.getSharedPreferences("updates", Context.MODE_PRIVATE)
                if (now - prefs.getLong(LAST_CHECK_KEY, 0L) < AUTO_CHECK_EVERY_MS) return@launch
            }

            try {
                val response = gitHubApi.getLatestRelease(UPDATE_REPOSITORY)

                if (response.isSuccessful) {
                    val manifest = response.body()?.toUpdateManifest()
                    val installed = application.packageManager.getPackageInfo(application.packageName, 0)
                    val currentVersionCode = installed.longVersionCode.toInt()

                    // The debug mode can offer the latest release even when it is not newer (to reinstall it).
                    if (manifest != null && (manifest.versionCode > currentVersionCode || offerAnyRelease)) {
                        _updateManifest.value = manifest
                        if (manual) showToast("Update available: ${manifest.versionName}")
                    } else if (manual) {
                        val installedName = installed.versionName?.removeSuffix("-DEBUG") ?: currentVersionCode.toString()
                        showToast("App is up to date (installed: $installedName, latest release: ${manifest?.versionName ?: "none"})")
                    }

                    if (!manual) {
                        application.getSharedPreferences("updates", Context.MODE_PRIVATE).edit().putLong(LAST_CHECK_KEY, now).apply()
                    }
                } else {
                    Log.e(TAG, "NETWORK ERROR (OTA): HTTP status ${response.code()}")
                    if (manual) {
                        val msg =
                            when (response.code()) {
                                404 -> "No release has been published yet"
                                403, 429 -> "GitHub rate limit reached, try again later"
                                else -> "GitHub error (HTTP ${response.code()})"
                            }
                        showToast(msg)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "OTA Exception: ", e)
                if (manual) showToast("Connection error: ${e.message}")
            }
        }
    }

    /**
     * "Install Update": the download and the install run in UpdateDownloadService, a foreground service, so the
     * app can go to the background and a locked screen does not stop it (2.3.0-1).
     */
    fun downloadUpdate() {
        val manifest = _updateManifest.value ?: return
        if (SelfUpdate.downloadProgress.value != null) return
        try {
            UpdateDownloadService.start(application, manifest)
        } catch (e: Exception) {
            Log.e(TAG, "Could not start the update download", e)
            showToast("Download error: ${e.message}")
        }
    }
}
