package com.example.forgegen.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import java.util.Calendar

/* ============================================================================
 * QUEUE SCHEDULE IN THE UI
 * The time picker of "Start at" (QueueSchedule). The queue's timeline shows the times (QueueScreen); the main screen's
 * status strip shows a scheduled start (QueueStatusStrip).
 * ============================================================================ */

/** Picks the time of day the queue starts at; the next time the clock shows it (today or tomorrow). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueStartTimeDialog(
    initial: Long?,
    onDismiss: () -> Unit,
    onPick: (hour: Int, minute: Int) -> Unit,
) {
    val context = LocalContext.current
    val start = Calendar.getInstance().apply { initial?.let { timeInMillis = it } ?: set(Calendar.HOUR_OF_DAY, 1) }
    val state =
        rememberTimePickerState(
            initialHour = start.get(Calendar.HOUR_OF_DAY),
            initialMinute = if (initial != null) start.get(Calendar.MINUTE) else 0,
            is24Hour =
                android.text.format.DateFormat
                    .is24HourFormat(context),
        )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start the Queue At") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TimePicker(state = state)
                Text(
                    "Nothing is sent before then, also jobs added later. Android may start it a few minutes late to " +
                        "save battery.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onPick(state.hour, state.minute)
                onDismiss()
            }) { Text("Schedule") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
