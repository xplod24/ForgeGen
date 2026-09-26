package com.example.forgegen.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.forgegen.ForgeViewModel
import com.example.forgegen.GenerationStatus
import com.example.forgegen.QueueEstimate
import com.example.forgegen.QueueSchedule
import java.util.Calendar

/* ============================================================================
 * QUEUE SCHEDULE AND ESTIMATE IN THE UI
 * "Start at" (QueueSchedule) and the expected end of the queue (QueueEstimate).
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

/** How the queue's time looks now: when it starts and ends, or how much work is left; null for an empty queue. */
@Composable
fun queueTimingText(viewModel: ForgeViewModel): String? {
    val queue by viewModel.generationQueue.collectAsStateWithLifecycle()
    val secondsLeft by viewModel.queueSecondsLeft.collectAsStateWithLifecycle()
    val scheduledStart by viewModel.scheduledStart.collectAsStateWithLifecycle()
    val waiting by viewModel.isWaitingForSchedule.collectAsStateWithLifecycle()
    val active by viewModel.isQueueActive.collectAsStateWithLifecycle()
    if (queue.none { it.status != GenerationStatus.FAILED }) return null
    val at = scheduledStart
    val left = secondsLeft
    return when {
        left == null && at != null && waiting -> "Starts at ${QueueSchedule.formatTime(at)}"
        left == null -> "The end time is estimated after the first finished job"
        at != null && waiting -> "Starts at ${QueueSchedule.formatTime(at)}, ends around ${QueueSchedule.formatTime(at + left * 1000)}"
        active ->
            "Ends around ${QueueSchedule.formatTime(System.currentTimeMillis() + left * 1000)} " +
                "(${QueueEstimate.formatDuration(left)} left)"
        else -> "About ${QueueEstimate.formatDuration(left)} of work left"
    }
}

/** While the queue waits for its scheduled start: when, and buttons to start now or change the time. */
@Composable
fun QueueScheduleCard(
    viewModel: ForgeViewModel,
    modifier: Modifier = Modifier,
) {
    val scheduledStart by viewModel.scheduledStart.collectAsStateWithLifecycle()
    val waiting by viewModel.isWaitingForSchedule.collectAsStateWithLifecycle()
    val queue by viewModel.generationQueue.collectAsStateWithLifecycle()
    val jobs = queue.count { it.status != GenerationStatus.FAILED }
    var showPicker by remember { mutableStateOf(false) }
    val timing = queueTimingText(viewModel)

    AnimatedVisibility(visible = scheduledStart != null && waiting) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            modifier = modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "QUEUE STARTS AT ${scheduledStart?.let { QueueSchedule.formatTime(it) } ?: ""}",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    (if (jobs == 1) "1 job waits." else "$jobs jobs wait.") + (timing?.let { " $it." } ?: ""),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showPicker = true }) { Text("Change") }
                    Button(onClick = { viewModel.startScheduledQueueNow() }) { Text("Start Now") }
                }
            }
        }
    }

    if (showPicker) {
        QueueStartTimeDialog(
            initial = scheduledStart,
            onDismiss = { showPicker = false },
            onPick = { hour, minute -> viewModel.scheduleQueueStart(hour, minute) },
        )
    }
}
