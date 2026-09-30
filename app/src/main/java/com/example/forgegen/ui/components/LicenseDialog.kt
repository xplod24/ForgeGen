package com.example.forgegen.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.example.forgegen.AppLicense
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings > Updates > License (3.4.2): the GPL notice, the whole license (from the assets, so also offline) and
 * "Source Code", which opens the repository.
 */
@Composable
fun LicenseDialog(
    onDismiss: () -> Unit,
    onOpenSource: () -> Unit,
) {
    val context = LocalContext.current
    var showFull by remember { mutableStateOf(false) }
    var fullText by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(showFull) {
        if (showFull && fullText == null) {
            fullText =
                withContext(Dispatchers.IO) {
                    runCatching {
                        context.assets
                            .open(AppLicense.ASSET)
                            .bufferedReader()
                            .use { it.readText() }
                    }.getOrNull()
                        ?.let(AppLicense::paragraphs)
                } ?: listOf("The license is not in this build. Read it at ${AppLicense.SOURCE_URL}.")
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        // Not closed by a tap outside, which happens easily while scrolling; Back and OK close it.
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(if (showFull) "GNU General Public License" else "License") },
        text = {
            // A scroll position of its own for the notice and for the whole license.
            key(showFull) {
                Column(
                    modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(if (showFull) 8.dp else 12.dp),
                ) {
                    if (showFull) {
                        fullText?.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    } else {
                        Column {
                            Text("ForgeGen", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(AppLicense.COPYRIGHT, style = MaterialTheme.typography.bodyMedium)
                        }
                        AppLicense.NOTICE.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        dismissButton = {
            Row {
                TextButton(onClick = onOpenSource) { Text("Source Code") }
                TextButton(onClick = { showFull = !showFull }) { Text(if (showFull) "Notice" else "Full License") }
            }
        },
    )
}
