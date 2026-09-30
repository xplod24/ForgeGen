package com.example.forgegen.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.forgegen.GalleryKey

/* ============================================================================
 * GALLERY KEY (3.5.0)
 * The secret key of the server's Infinite Image Browsing: asked for in the gallery when the extension is locked, and
 * kept in Settings > Server > Gallery Key. The phone keeps only its fingerprint (GalleryKey).
 * ============================================================================ */

/** The key's text field: hidden like a password, with a button to show it. */
@Composable
fun GalleryKeyField(
    value: String,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("IIB Secret Key") },
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (visible) "Hide Key" else "Show Key")
            }
        },
        modifier = modifier,
    )
}

/** "Where to find it": a link that unfolds where the key is on the server. */
@Composable
private fun WhereToFindKey() {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        TextButton(onClick = { open = !open }) { Text("Where to find it") }
        AnimatedVisibility(visible = open) {
            Text(
                GalleryKey.WHERE_TO_FIND,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The locked gallery: the extension asks for its key, or refused the saved one ([message]). */
@Composable
fun GalleryLockedPanel(
    message: String?,
    onUnlock: (key: String, onResult: (Boolean) -> Unit) -> Unit,
) {
    var key by rememberSaveable { mutableStateOf("") }
    var checking by rememberSaveable { mutableStateOf(false) }
    val unlock = {
        if (key.isNotBlank() && !checking) {
            checking = true
            onUnlock(key) { accepted ->
                checking = false
                if (accepted) key = ""
            }
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Default.Lock, null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Gallery Locked", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            "Infinite Image Browsing on this server asks for its secret key (IIB_SECRET_KEY).",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (message != null && !checking) {
            Text(message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error)
        }
        GalleryKeyField(
            value = key,
            onValueChange = { key = it },
            onDone = unlock,
            enabled = !checking,
            modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
        )
        Button(onClick = unlock, enabled = key.isNotBlank() && !checking) {
            if (checking) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text("Unlock")
            }
        }
        Text(
            "Only a fingerprint of the key is kept on this phone, never the key itself.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        WhereToFindKey()
    }
}

/** Forge has a login and the extension no key of its own, so it refuses everything until one is set on the server. */
@Composable
fun GalleryKeyNotSetPanel(onCheckAgain: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Default.Key, null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.error)
        Text("The Gallery Needs a Key on the Server", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            "Forge asks for a login, so Infinite Image Browsing refuses every request until it has a secret key of its " +
                "own. Set IIB_SECRET_KEY in the .env file in the extension's folder " +
                "(extensions/sd-webui-infinite-image-browsing), restart Forge, then check again.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Button(onClick = onCheckAgain) { Text("Check Again") }
    }
}

/** Settings > Server > Gallery Key: enter or change the key, or remove the saved one. */
@Composable
fun GalleryKeyDialog(
    saved: Boolean,
    onSave: (key: String, onResult: (Boolean) -> Unit) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var key by rememberSaveable { mutableStateOf("") }
    var checking by rememberSaveable { mutableStateOf(false) }
    var refused by rememberSaveable { mutableStateOf(false) }
    val save = {
        if (key.isNotBlank() && !checking) {
            checking = true
            refused = false
            onSave(key) { accepted ->
                checking = false
                if (accepted) onDismiss() else refused = true
            }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!checking) onDismiss() },
        title = { Text("Gallery Key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "The secret key of Infinite Image Browsing on this server, for a gallery that asks for one. " +
                        "The phone keeps only its fingerprint, and backups leave it out.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                GalleryKeyField(value = key, onValueChange = {
                    key = it
                    refused = false
                }, onDone = save, enabled = !checking, modifier = Modifier.fillMaxWidth())
                if (refused) Text("The server did not accept this key.", color = MaterialTheme.colorScheme.error)
                WhereToFindKey()
            }
        },
        confirmButton = {
            TextButton(onClick = save, enabled = key.isNotBlank() && !checking) {
                if (checking) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Save")
            }
        },
        dismissButton = {
            Row {
                if (saved) {
                    TextButton(onClick = {
                        onRemove()
                        onDismiss()
                    }, enabled = !checking) { Text("Remove") }
                }
                TextButton(onClick = onDismiss, enabled = !checking) { Text("Cancel") }
            }
        },
    )
}
