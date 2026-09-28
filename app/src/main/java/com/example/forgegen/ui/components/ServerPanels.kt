package com.example.forgegen.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.forgegen.ForgeViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/* ============================================================================
 * RESTARTING FORGE (3.3.0, board 2D)
 * "Restart Forge?" and the button of the Server Memory panel; the settings' Server page has the same row. Forge
 * restarts only when it was started with --api-server-stop (the server answers 404 otherwise) and by webui.bat or
 * webui.sh (501 otherwise); the app then waits for it longer than for a lost connection.
 * ============================================================================ */

/** "Restart Forge?", then the restart; what happened is said in a message. */
@Composable
fun RestartForgeDialog(
    viewModel: ForgeViewModel,
    generating: Boolean,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(Icons.Default.RestartAlt, contentDescription = null) },
        title = { Text("Restart Forge?") },
        text = {
            Text(
                "The server stops, starts again and loads the model on the next image. The queue waits and goes on " +
                    "when it is back." + if (generating) " The image being made now is lost; its job is sent again." else "",
            )
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        val problem = viewModel.restartServer()
                        viewModel.showToast(problem ?: "Restarting Forge...")
                        onDismiss()
                    }
                },
            ) { Text("Restart", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

/**
 * The Server Memory panel's "Restart Forge". Greyed out when Forge said it cannot be restarted from the phone
 * ([canRestart] false; null: not known yet, so it is offered) or while it restarts.
 */
@Composable
fun RestartForgeButton(
    canRestart: Boolean?,
    restarting: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = canRestart != false && !restarting,
        modifier = Modifier.fillMaxWidth().height(48.dp),
    ) {
        Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (restarting) "Forge is restarting..." else "Restart Forge", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Why Restart Forge is greyed out, for the text under it. */
const val RESTART_NEEDS_FLAG_HINT = "Restarting from the phone needs Forge started with --api-server-stop."

/** Seconds since [since] (ms since 1970), counting up once a second. */
@Composable
fun rememberSecondsSince(since: Long): Long {
    val seconds by produceState(initialValue = ((System.currentTimeMillis() - since) / 1000).coerceAtLeast(0), since) {
        while (true) {
            value = ((System.currentTimeMillis() - since) / 1000).coerceAtLeast(0)
            delay(1000)
        }
    }
    return seconds
}
