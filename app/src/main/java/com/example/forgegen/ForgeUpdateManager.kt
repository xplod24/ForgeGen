package com.example.forgegen

import android.app.Application
import android.content.Intent
import android.os.Environment
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
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* ============================================================================
 * UPDATE MANAGER (OTA)
 * Checks the latest GitHub release of the app, downloads its APK with live progress,
 * verifies the SHA-256 digest reported by GitHub and launches the Android Package Installer.
 * A release is an update when its tag "v<major>.<minor>.<patch>[-<micro>]" maps to a higher versionCode than the
 * installed build.
 * ============================================================================ */
class ForgeUpdateManager(
    private val application: Application,
    private val gitHubApi: GitHubApi,
    private val getConfig: () -> AppConfig,
    private val saveConfig: (AppConfig) -> Unit,
    private val showToast: (String) -> Unit,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "ForgeUpdateManager"

        /** owner/repo whose latest release is installed as the update. */
        const val UPDATE_REPOSITORY = "xplod24/ForgeGen"
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
        get() = File(application.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "ForgeGen_Update.apk")

    /**
     * Checks for available updates. When 'manual' is false, it verifies if a check has
     * already occurred today (GitHub allows 60 anonymous API calls per hour and IP).
     */
    fun checkForUpdates(manual: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            val config = getConfig()
            val todayDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

            if (!manual && config.lastUpdateCheckDate == todayDate) {
                // Rate-limit safeguard: update has already been verified automatically today.
                return@launch
            }

            try {
                val response = gitHubApi.getLatestRelease(UPDATE_REPOSITORY)

                if (response.isSuccessful) {
                    val manifest = response.body()?.toUpdateManifest()
                    val installed = application.packageManager.getPackageInfo(application.packageName, 0)
                    val currentVersionCode = installed.longVersionCode.toInt()

                    if (manifest != null && manifest.versionCode > currentVersionCode) {
                        _updateManifest.value = manifest
                        if (manual) showToast("Update available: ${manifest.versionName}")
                    } else if (manual) {
                        val installedName = installed.versionName?.removeSuffix("-DEBUG") ?: currentVersionCode.toString()
                        showToast("App is up to date (installed: $installedName, latest release: ${manifest?.versionName ?: "none"})")
                    }

                    // Persist today's date to signify a successful update check.
                    if (!manual) saveConfig(getConfig().copy(lastUpdateCheckDate = todayDate))
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
            val file = updateFile

            // Reuse an earlier download of the same release.
            if (file.exists() && manifest.sha256 != null) {
                val existingHash = runCatching { sha256Of(file) }.getOrNull()
                if (existingHash.equals(manifest.sha256, ignoreCase = true)) {
                    Log.i(TAG, "Existing APK matches the release digest. Skipping download.")
                    withContext(Dispatchers.Main) { installUpdate() }
                    return@launch
                }
            }
            file.delete()

            withContext(Dispatchers.Main) {
                _isUpdateDownloading.value = true
                _updateDownloadProgress.value = 0f
                _updateDownloadStats.value = 0L to 0L
            }

            try {
                val response = gitHubApi.downloadAsset(manifest.url)
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    val totalBytes = body.contentLength().takeIf { it > 0 } ?: manifest.size

                    var downloadedBytes = 0L
                    var lastUpdate = 0L

                    body.byteStream().use { inputStream ->
                        file.outputStream().use { outputStream ->
                            val buffer = ByteArray(8 * 1024)
                            var read: Int
                            while (inputStream.read(buffer).also { read = it } != -1) {
                                outputStream.write(buffer, 0, read)
                                downloadedBytes += read

                                val now = System.currentTimeMillis()
                                if (now - lastUpdate > 200 || downloadedBytes == totalBytes) { // Throttle UI updates
                                    lastUpdate = now
                                    if (totalBytes > 0) {
                                        _updateDownloadProgress.value = downloadedBytes.toFloat() / totalBytes.toFloat()
                                        _updateDownloadStats.value = downloadedBytes to totalBytes
                                    }
                                }
                            }
                        }
                    }

                    _updateDownloadProgress.value = 1f
                    verifyAndInstall(file, manifest.sha256)
                } else {
                    withContext(Dispatchers.Main) {
                        _isUpdateDownloading.value = false
                        showToast("Download failed: HTTP ${response.code()}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                withContext(Dispatchers.Main) {
                    _isUpdateDownloading.value = false
                    showToast("Download error: ${e.message}")
                }
            }
        }
    }

    private suspend fun verifyAndInstall(
        file: File,
        expectedSha256: String?,
    ) {
        try {
            // Without a digest from GitHub the HTTPS download is trusted as is.
            val matches = expectedSha256 == null || sha256Of(file).equals(expectedSha256, ignoreCase = true)
            withContext(Dispatchers.Main) {
                _isUpdateDownloading.value = false
                if (matches) {
                    installUpdate()
                } else {
                    file.delete()
                    showToast("Security Error: Checksum mismatch. File deleted.")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to verify update", e)
            withContext(Dispatchers.Main) {
                _isUpdateDownloading.value = false
                showToast("Update verification failed: ${e.message}")
            }
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Triggers the Android Package Installer using a system Intent.
     */
    private fun installUpdate() {
        try {
            val file = updateFile
            if (!file.exists()) {
                showToast("Installation file missing!")
                return
            }

            val installUri = FileProvider.getUriForFile(application, "${application.packageName}.fileprovider", file)
            val installIntent =
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(installUri, "application/vnd.android.package-archive")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
            application.startActivity(installIntent)

            // Reset update states after launching installation
            _updateManifest.value = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch installer", e)
            showToast("Launch failed: ${e.message}")
        }
    }
}
