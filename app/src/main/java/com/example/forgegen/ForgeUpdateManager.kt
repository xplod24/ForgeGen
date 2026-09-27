package com.example.forgegen

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/* ============================================================================
 * UPDATE MANAGER (OTA)
 * Checks the latest GitHub release of the app when it starts (at most every 15 minutes, or on "Check for Updates"),
 * downloads its APK with live progress and installs it through SelfUpdate (PackageInstaller: on Android 12+ without
 * the system's confirmation where it allows that). The background check without the app open is SelfUpdate's.
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

    private val _isUpdateDownloading = MutableStateFlow(false)
    val isUpdateDownloading: StateFlow<Boolean> = _isUpdateDownloading.asStateFlow()

    private val _updateDownloadProgress = MutableStateFlow(0f)
    val updateDownloadProgress: StateFlow<Float> = _updateDownloadProgress.asStateFlow()

    private val _updateDownloadStats = MutableStateFlow(0L to 0L)
    val updateDownloadStats: StateFlow<Pair<Long, Long>> = _updateDownloadStats.asStateFlow()

    private val updateFile: File
        get() = SelfUpdate.apkFile(application)

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

    fun downloadUpdate() {
        val manifest = _updateManifest.value ?: return
        if (_isUpdateDownloading.value) return

        scope.launch(Dispatchers.IO) {
            _isUpdateDownloading.value = true
            _updateDownloadProgress.value = 0f
            _updateDownloadStats.value = 0L to 0L
            try {
                val downloaded =
                    SelfUpdate.download(gitHubApi, manifest, updateFile) { done, total ->
                        if (total > 0) {
                            _updateDownloadProgress.value = done.toFloat() / total.toFloat()
                            _updateDownloadStats.value = done to total
                        }
                    }
                _isUpdateDownloading.value = false
                if (downloaded) {
                    _updateDownloadProgress.value = 1f
                    installUpdate(manifest)
                } else {
                    showToast("Download failed or the file did not match the release. Try again.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                _isUpdateDownloading.value = false
                showToast("Download error: ${e.message}")
            }
        }
    }

    /**
     * Installs the downloaded update through SelfUpdate (the app closes when it is replaced, and a notification
     * says so); if a session cannot be opened, the system's installer screen as before.
     */
    private suspend fun installUpdate(manifest: UpdateManifest) {
        val file = updateFile
        if (!file.exists()) {
            showToast("Installation file missing!")
            return
        }
        try {
            showToast("Installing ${manifest.versionName}... The app closes when it is done.")
            SelfUpdate.install(application, file, manifest.versionName)
            _updateManifest.value = null
        } catch (e: Exception) {
            Log.e(TAG, "PackageInstaller session failed, opening the installer screen", e)
            withContext(Dispatchers.Main) { openInstallerScreen(file) }
        }
    }

    private fun openInstallerScreen(file: File) {
        try {
            val installUri = FileProvider.getUriForFile(application, "${application.packageName}.fileprovider", file)
            val installIntent =
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(installUri, "application/vnd.android.package-archive")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
            application.startActivity(installIntent)
            _updateManifest.value = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch installer", e)
            showToast("Launch failed: ${e.message}")
        }
    }
}
