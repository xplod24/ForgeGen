package com.example.forgegen.ui.components

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.forgegen.HomelabCa
import com.example.forgegen.HomelabCaException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import java.io.IOException

private const val CERTIFICATE_FILENAME = "ForgeGen-Homelab-CA.cer"

@Composable
internal fun HomelabCaInstallButton(serverAddress: String) {
    val certificateUrl = HomelabCa.certificateUrl(serverAddress) ?: return
    var showDialog by rememberSaveable(certificateUrl.host) { mutableStateOf(false) }
    TextButton(onClick = { showDialog = true }) { Text("Install Homelab CA") }
    if (showDialog) HomelabCaInstallDialog(certificateUrl) { showDialog = false }
}

@Composable
private fun HomelabCaInstallDialog(
    certificateUrl: HttpUrl,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var savedName by rememberSaveable { mutableStateOf<String?>(null) }
    var problem by rememberSaveable { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf(false) }

    fun openSettings() {
        if (!openSecuritySettings(context)) {
            problem = "Open Android Settings and search for Install a certificate."
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Install Homelab CA") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    savedName?.let { "Certificate saved in Downloads as $it." }
                        ?: "ForgeGen will download and verify the homelab CA for ${certificateUrl.host}.",
                )
                Text(
                    "In Android Settings, choose Encryption & credentials → Install a certificate → CA certificate. " +
                        "Select the certificate in Downloads.",
                )
                if (downloading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !downloading,
                onClick = {
                    problem = null
                    if (savedName != null) {
                        openSettings()
                    } else {
                        downloading = true
                        scope.launch {
                            try {
                                savedName = downloadToDevice(context.applicationContext, certificateUrl)
                                openSettings()
                            } catch (error: HomelabCaException) {
                                problem = error.message
                            } catch (_: IOException) {
                                problem = "Could not download the CA. Connect to the homelab Wi-Fi or WireGuard and try again."
                            } finally {
                                downloading = false
                            }
                        }
                    }
                },
            ) {
                Text(if (savedName == null) "Download and Open Settings" else "Open Security Settings")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private suspend fun downloadToDevice(
    context: Context,
    certificateUrl: HttpUrl,
): String =
    withContext(Dispatchers.IO) {
        val bytes = HomelabCa.download(certificateUrl)
        ensureActive()
        try {
            saveCertificate(context, bytes)
        } catch (error: IOException) {
            throw HomelabCaException("Android could not save the CA certificate in Downloads.", error)
        } catch (error: SecurityException) {
            throw HomelabCaException("Android denied saving the CA certificate in Downloads.", error)
        }
    }

private fun saveCertificate(
    context: Context,
    bytes: ByteArray,
): String {
    val resolver = context.contentResolver
    val nameColumn = MediaStore.MediaColumns.DISPLAY_NAME
    val values =
        ContentValues().apply {
            put(nameColumn, CERTIFICATE_FILENAME)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/x-x509-ca-cert")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    val downloads = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val uri = resolver.insert(downloads, values) ?: throw IOException("Cannot create the CA certificate file")
    try {
        val stream = resolver.openOutputStream(uri) ?: throw IOException("Cannot write the CA certificate")
        stream.use { it.write(bytes) }
        val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        if (resolver.update(uri, ready, null, null) != 1) throw IOException("Cannot publish the CA certificate")
    } catch (error: Exception) {
        runCatching { resolver.delete(uri, null, null) }
        throw error
    }
    return resolver.query(uri, arrayOf(nameColumn), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: CERTIFICATE_FILENAME
}

private fun openSecuritySettings(context: Context): Boolean =
    try {
        context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
