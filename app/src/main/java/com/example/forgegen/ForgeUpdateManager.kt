package com.example.forgegen

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
 * Responsible for managing the Over-The-Air (OTA) update lifecycle: scheduling checks,
 * fetching the manifest, tracking download progress (Live Progress), verifying checksums,
 * and launching the Android Package Installer.
 * ============================================================================ */
class ForgeUpdateManager(
    private val application: Application,
    private val getForgeApi: () -> ForgeApi?,

    private val getConfig: () -> AppConfig,
    private val saveConfig: (AppConfig) -> Unit,
    private val showToast: (String) -> Unit,
    private val scope: CoroutineScope
) {
    private val TAG = "ForgeUpdateManager"

    private val _updateManifest = MutableStateFlow<UpdateManifest?>(null)
    val updateManifest: StateFlow<UpdateManifest?> = _updateManifest.asStateFlow()

    private val _isUpdateDownloading = MutableStateFlow(false)
    val isUpdateDownloading: StateFlow<Boolean> = _isUpdateDownloading.asStateFlow()

    private val _updateDownloadProgress = MutableStateFlow(0f)
    val updateDownloadProgress: StateFlow<Float> = _updateDownloadProgress.asStateFlow()

    private val _updateDownloadStats = MutableStateFlow(0L to 0L)
    val updateDownloadStats: StateFlow<Pair<Long, Long>> = _updateDownloadStats.asStateFlow()

    private var currentDownloadId: Long = -1L

    /**
     * Checks for available updates. When 'manual' is false, it verifies if a check has
     * already occurred today to prevent redundant background network traffic.
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
                val api = getForgeApi()
                if (api == null) {
                    if (manual) showToast("Not connected to any server.")
                    return@launch
                }

                val response = api.getAppMetadata()

                if (response.isSuccessful) {
                    val manifestDto = response.body() ?: return@launch
                    val manifest = manifestDto.toDomain()

                    val pInfo = application.packageManager.getPackageInfo(application.packageName, 0)
                    val currentVersionCode = pInfo.longVersionCode.toInt()

                    if (!manifest.channel.equals("Release", ignoreCase = true)) {
                        Log.e(TAG, "CHANNEL CONSISTENCY ERROR: Downloaded manifest for channel ${manifest.channel}, expected Release.")
                        if (manual) showToast("Server error: Found version ${manifest.channel} in folder Release.")
                        return@launch
                    }

                    if (manifest.versionCode > currentVersionCode) {
                        _updateManifest.value = manifest
                        if (manual) showToast("Update available: ${manifest.versionName}")
                    } else {
                        if (manual) showToast("App is up to date (Local: $currentVersionCode, Server: ${manifest.versionCode})")
                    }

                    // Persist today's date to signify a successful update check.
                    if (!manual) saveConfig(getConfig().copy(lastUpdateCheckDate = todayDate))
                } else {
                    Log.e(TAG, "NETWORK ERROR (OTA): HTTP status ${response.code()}")
                    if (manual) {
                        val msg = if (response.code() == 403) "Access denied (HTTP 403). Check Beta token!" else "Server error (HTTP ${response.code()})"
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
            val file = File(application.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "ForgeGen_Update.apk")
            
            // Check if existing file is already the correct update
            if (file.exists() && manifest.sha256.isNotEmpty()) {
                try {
                    val digest = MessageDigest.getInstance("SHA-256")
                    file.inputStream().use { fis ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (fis.read(buffer).also { bytesRead = it } != -1) {
                            digest.update(buffer, 0, bytesRead)
                        }
                    }
                    val calculatedSha256 = digest.digest().joinToString("") { "%02x".format(it) }
                    
                    if (calculatedSha256.equals(manifest.sha256, ignoreCase = true)) {
                        Log.i(TAG, "Existing APK matches manifest hash. Skipping download.")
                        withContext(Dispatchers.Main) {
                            installUpdate()
                        }
                        return@launch
                    } else {
                        Log.i(TAG, "Existing APK hash mismatch. Deleting and re-downloading.")
                        file.delete()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking existing APK hash", e)
                    file.delete()
                }
            } else if (file.exists()) {
                file.delete()
            }

            withContext(Dispatchers.Main) {
                _isUpdateDownloading.value = true
                _updateDownloadProgress.value = 0f
                _updateDownloadStats.value = 0L to 0L
            }

            try {
                val api = getForgeApi()
                if (api == null) {
                    withContext(Dispatchers.Main) {
                        _isUpdateDownloading.value = false
                        showToast("Not connected to server")
                    }
                    return@launch
                }

                val response = api.downloadAppUpdate()
                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    val file = File(application.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "ForgeGen_Update.apk")
                    val totalBytes = body.contentLength()
                    
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
                    verifyAndPrepareApk(manifest.sha256)
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

    private fun verifyAndPrepareApk(expectedSha256: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val file = File(application.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "ForgeGen_Update.apk")
                if (!file.exists()) {
                    showToast("Update file missing after download")
                    _isUpdateDownloading.value = false
                    return@launch
                }

                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { fis ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                        digest.update(buffer, 0, bytesRead)
                    }
                }
                val hashBytes = digest.digest()
                val calculatedSha256 = hashBytes.joinToString("") { "%02x".format(it) }

                withContext(Dispatchers.Main) {
                    _isUpdateDownloading.value = false
                    if (calculatedSha256.equals(expectedSha256, ignoreCase = true)) {
                        // Success: calculation matches server manifest. Launch installer automatically.
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
    }

    /**
     * Triggers the Android Package Installer using a system Intent.
     */
    fun installUpdate() {
        try {
            val file = File(application.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "ForgeGen_Update.apk")
            if (!file.exists()) {
                showToast("Installation file missing!")
                return
            }

            val installUri = FileProvider.getUriForFile(application, "${application.packageName}.fileprovider", file)
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
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

    fun dismissUpdate() {
        _isUpdateDownloading.value = false
        // Silently dismiss update without resetting.
        // Future improvements could persist manifest.versionCode as 'ignored'.
    }
}