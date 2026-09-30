package com.example.forgegen.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Settings > Updates when the latest release is another app (3.5.2-1): 3.5.2-2 drops ".debug" from the package name,
 * so Android installs it next to this app instead of updating it. The data moves in four steps, each with its button:
 * export (the backup carries the favorites and the queue since 3.5.2-1), install the new app with the system's
 * installer, import there, uninstall this app from its app info page.
 */
@Composable
fun UpdateMoveCard(
    versionName: String,
    newPackage: String,
    newAppInstalled: Boolean,
    onExport: () -> Unit,
    onInstall: () -> Unit,
    onOpen: () -> Unit,
    onUninstall: () -> Unit,
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
        Text("ForgeGen $versionName Is a New App", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            "It drops \"debug\" from its package name ($newPackage), so Android installs it next to this app instead " +
                "of updating it. Your data moves in four steps:",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(10.dp))
        MoveStep(1, "Export your settings, favorites and queue to a file", done = false) {
            OutlinedButton(onClick = onExport) { Text("Export") }
        }
        if (newAppInstalled) {
            MoveStep(2, "The new app is installed", done = true) {
                OutlinedButton(onClick = onOpen) { Text("Open") }
            }
        } else {
            MoveStep(2, "Install the new app", done = false) {
                OutlinedButton(onClick = onInstall) { Text("Install") }
            }
        }
        MoveStep(3, "In the new app: Settings > Backup & Data > Import Settings, and pick the file", done = false)
        MoveStep(4, "Uninstall this app, from its app info page", done = false) {
            OutlinedButton(onClick = onUninstall) { Text("Uninstall") }
        }
    }
}

@Composable
private fun MoveStep(
    number: Int,
    text: String,
    done: Boolean,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = if (done) 1f else 0.25f)),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (done) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Done",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp),
                )
            } else {
                Text("$number", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (action != null) {
            Spacer(Modifier.width(2.dp))
            action()
        }
    }
}
