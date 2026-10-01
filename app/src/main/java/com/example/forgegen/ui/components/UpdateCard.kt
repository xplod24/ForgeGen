package com.example.forgegen.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.forgegen.SelfUpdate
import com.example.forgegen.UpdateManifest
import com.example.forgegen.releaseNoteCount
import com.example.forgegen.releaseNotesMarkdown

/**
 * Settings > Updates, section "New Version": the release on offer with its notes. "Download" no longer replaces the
 * card (the whole changelog used to vanish in one frame): the notes stay, the buttons fold away and the download's
 * progress slides out below them; once the file is checked the progress folds back and "Install" comes out.
 */
@Composable
fun UpdateCard(
    manifest: UpdateManifest,
    ready: SelfUpdate.ReadyUpdate?,
    download: SelfUpdate.DownloadProgress?,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onShowAll: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                .padding(16.dp),
    ) {
        Text(
            if (ready != null) "Update Ready: ${manifest.versionName}" else "Update Available: ${manifest.versionName}",
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
        )
        AnimatedVisibility(visible = ready != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Text(
                "Downloaded and checked · ${"%.1f".format(java.util.Locale.US, (ready?.size ?: manifest.size) / 1048576.0)} MB",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
        Spacer(Modifier.height(4.dp))
        val notes = manifest.changelog ?: emptyList()
        val noteCount = releaseNoteCount(notes)
        if (notes.isNotEmpty()) {
            Text("What's new:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            MarkdownText(markdown = releaseNotesMarkdown(notes, maxItems = 3), textStyle = MaterialTheme.typography.bodySmall)
            if (noteCount > 3) {
                TextButton(onClick = onShowAll, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Show All ($noteCount)") }
            }
        }
        // The progress slides out below the notes; it keeps the last numbers while it folds back.
        val shown = remember { mutableStateOf(download) }
        if (download != null) shown.value = download
        AnimatedVisibility(
            visible = download != null,
            enter = expandVertically(expandFrom = Alignment.Top, animationSpec = tween(320)) + fadeIn(tween(320)),
            exit = shrinkVertically(shrinkTowards = Alignment.Top, animationSpec = tween(260)) + fadeOut(tween(200)),
        ) {
            shown.value?.let { DownloadProgress(it) }
        }
        AnimatedVisibility(
            visible = download == null,
            enter = expandVertically(animationSpec = tween(260)) + fadeIn(tween(260)),
            exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(tween(160)),
        ) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Dismiss") }
                Spacer(Modifier.width(8.dp))
                if (ready != null) Button(onClick = onInstall) { Text("Install") } else Button(onClick = onDownload) { Text("Download") }
            }
        }
    }
}

@Composable
private fun DownloadProgress(download: SelfUpdate.DownloadProgress) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        val fraction by animateFloatAsState(download.fraction, animationSpec = tween(300), label = "download")
        if (download.total <= 0) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (download.total > 0) {
                "Downloading · ${(download.fraction * 100).toInt()}% · " +
                    "${"%.1f".format(java.util.Locale.US, download.done / 1048576.0)} / " +
                    "${"%.1f".format(java.util.Locale.US, download.total / 1048576.0)} MB"
            } else {
                "Starting the download..."
            },
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Text(
            "It goes on in the background: you can leave the app or lock the screen.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}
