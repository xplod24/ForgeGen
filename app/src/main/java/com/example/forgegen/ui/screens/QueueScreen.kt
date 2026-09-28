package com.example.forgegen.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.example.forgegen.ForgeQueueManager
import com.example.forgegen.ForgeViewModel
import com.example.forgegen.GenerationStatus
import com.example.forgegen.MainSectionLabel
import com.example.forgegen.ModelSettingsRules
import com.example.forgegen.QueueSchedule
import com.example.forgegen.QueuedGeneration
import com.example.forgegen.RowIcon
import com.example.forgegen.ServerTasks
import com.example.forgegen.Txt2ImgPayloadDto
import com.example.forgegen.ui.components.FloatingTopBar
import com.example.forgegen.ui.components.QueueStartTimeDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToLong

/* ============================================================================
 * QUEUE SCREEN (3.0.0-1, the owner's pick "C" of three mockups)
 * The queue as a timeline: next to each job the time it should start (QueueEstimate.ends), the running job with its
 * progress, and "All done" with the end time; jobs set aside below. The chip on top sets "Start at". A tap opens a
 * job (its prompt and settings, Duplicate, Edit, Remove); its handle drags it to another place.
 * ============================================================================ */

// The time column fits "12:15 PM"; the rail's centre is after it, a gap (10 dp) and half the rail (10 dp).
private val TIME_WIDTH = 62.dp
private val RAIL_CENTER = TIME_WIDTH + 20.dp
private val DOT_CENTER = 23.dp

private const val TICK_MS = 30_000L

@Composable
fun QueueScreen(
    viewModel: ForgeViewModel,
    navController: NavHostController,
) {
    val queue by viewModel.generationQueue.collectAsStateWithLifecycle()
    val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val currentEta by viewModel.currentEta.collectAsStateWithLifecycle()
    // The server does other jobs before the running one (3.3.0, from its web UI or another app).
    val serverJobsAhead by viewModel.serverJobsAhead.collectAsStateWithLifecycle()
    val ends by viewModel.queueJobEnds.collectAsStateWithLifecycle()
    val scheduledStart by viewModel.scheduledStart.collectAsStateWithLifecycle()
    val waitingForSchedule by viewModel.isWaitingForSchedule.collectAsStateWithLifecycle()
    val paused by viewModel.isQueuePaused.collectAsStateWithLifecycle()
    // The times move with the clock.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(TICK_MS)
            value = System.currentTimeMillis()
        }
    }

    var showStartTimePicker by remember { mutableStateOf(false) }
    var editItemId by remember { mutableStateOf<String?>(null) }
    var editPosPrompt by remember { mutableStateOf("") }
    var editNegPrompt by remember { mutableStateOf("") }

    // "Undo" after a job was removed or the queue cleared.
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun offerUndo(
        message: String,
        removed: ForgeQueueManager.RemovedJobs?,
    ) {
        if (removed == null) return
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(message, actionLabel = "Undo", duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) viewModel.restoreJobs(removed)
        }
    }

    fun edit(item: QueuedGeneration) {
        editPosPrompt = item.payload.prompt
        editNegPrompt = item.payload.negative_prompt
        editItemId = item.id
    }

    // Reordering by dragging a job's handle: the dragged job follows the finger and trades places with the one
    // under its middle.
    val listState = rememberLazyListState()
    var draggedId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var awaitedIndex by remember { mutableIntStateOf(-1) } // the dragged job's place after a move, until laid out
    val currentQueue by rememberUpdatedState(queue)

    val onBack =
        remember {
            {
                if (navController.currentDestination?.route == "queue") navController.popBackStack()
                Unit
            }
        }

    // The timeline starts now, or at the scheduled start while the queue waits for it.
    val waiting = queue.withIndex().filter { it.value.status != GenerationStatus.FAILED }
    val failed = queue.filter { it.status == GenerationStatus.FAILED }
    val base = if (scheduledStart != null && waitingForSchedule) scheduledStart!! else now

    fun at(seconds: Double?): String? = seconds?.let { QueueSchedule.formatTime(base + (it * 1000).roundToLong()) }
    val totalEnd = waiting.lastOrNull()?.let { ends.getOrNull(it.index) }

    val subtitle =
        when {
            waiting.isEmpty() -> null
            paused -> "${waiting.size} waiting · paused"
            scheduledStart != null && waitingForSchedule -> "${waiting.size} waiting · starts at ${QueueSchedule.formatTime(
                scheduledStart!!,
            )}"
            totalEnd != null -> "${waiting.size} waiting · ends about ${at(totalEnd)}"
            else -> "${waiting.size} waiting"
        }

    Scaffold(
        topBar = {
            FloatingTopBar(title = "Queue", subtitle = subtitle, onNavigate = onBack) {
                if (isGenerating) {
                    IconButton(onClick = { viewModel.interruptGeneration() }) {
                        Icon(Icons.Default.Stop, "Interrupt Current", tint = MaterialTheme.colorScheme.error)
                    }
                }
                if (queue.isNotEmpty()) {
                    IconButton(onClick = {
                        val removed = viewModel.clearQueue()
                        offerUndo("Queue cleared (${removed?.jobs?.size ?: 0} jobs)", removed)
                    }) {
                        Icon(Icons.Default.Delete, "Clear Queue")
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (queue.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Default.HourglassEmpty,
                        null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Queue is empty", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Jobs you add show here with the time they should start.",
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    )
                }
            } else {
                val waitingIds = remember(waiting) { waiting.map { it.value.id }.toSet() }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                ) {
                    item(key = "start") {
                        StartRow(
                            scheduledAt = scheduledStart?.takeIf { waitingForSchedule },
                            summary = summaryOf(waiting.map { it.value }),
                            onPick = { showStartTimePicker = true },
                            onStartNow = { viewModel.startScheduledQueueNow() },
                        )
                    }
                    if (serverJobsAhead > 0) {
                        item(key = "server-first") { ServerJobsFirstNote(serverJobsAhead) }
                    }
                    // The key keeps per-job state (e.g. "open") with its job when jobs are moved or removed.
                    items(waiting, key = { it.value.id }) { (index, item) ->
                        val position = waiting.indexOfFirst { it.value.id == item.id }
                        val running = item.status == GenerationStatus.GENERATING
                        val startSeconds = if (position == 0) 0.0 else ends.getOrNull(waiting[position - 1].index)
                        val time =
                            when {
                                position == 0 && running -> "now"
                                position == 0 && !(scheduledStart != null && waitingForSchedule) -> "next"
                                else -> at(startSeconds) ?: ""
                            }
                        val isDragged = item.id == draggedId
                        TimelineRow(
                            time = time,
                            highlighted = running,
                            first = position == 0,
                            modifier =
                                if (isDragged) {
                                    Modifier.zIndex(1f).graphicsLayer {
                                        translationY = dragOffset
                                        shadowElevation = 24f
                                    }
                                } else {
                                    Modifier.animateItem()
                                },
                        ) {
                            JobCard(
                                item = item,
                                running = running,
                                progressLine =
                                    when {
                                        running && serverJobsAhead > 0 -> "Waiting: ${ServerTasks.aheadText(serverJobsAhead)}"
                                        running -> runningLine(progress, currentEta, at(ends.getOrNull(index)))
                                        else -> null
                                    },
                                progress = if (running) progress else null,
                                // A job of more than one image can go on with its next one (3.3.0).
                                onSkip = if (running && serverJobsAhead == 0 && item.payload.n_iter > 1) viewModel::skipImage else null,
                                onDuplicate = { newSeed -> viewModel.duplicateJob(item.id, newSeed) },
                                onEdit = { edit(item) },
                                onRemove = { offerUndo("Job removed", viewModel.removeFromQueue(item.id)) },
                                dragHandle =
                                    if (running) {
                                        null
                                    } else {
                                        Modifier.pointerInput(item.id) {
                                            detectDragGestures(
                                                onDragStart = {
                                                    draggedId = item.id
                                                    dragOffset = 0f
                                                    awaitedIndex = -1
                                                },
                                                onDragEnd = {
                                                    draggedId = null
                                                    dragOffset = 0f
                                                },
                                                onDragCancel = {
                                                    draggedId = null
                                                    dragOffset = 0f
                                                },
                                                onDrag = { change, amount ->
                                                    change.consume()
                                                    dragOffset += amount.y
                                                    val visible = listState.layoutInfo.visibleItemsInfo
                                                    val current = visible.firstOrNull { it.key == item.id } ?: return@detectDragGestures
                                                    if (awaitedIndex >= 0 && current.index != awaitedIndex) return@detectDragGestures
                                                    awaitedIndex = -1
                                                    val middle = current.offset + dragOffset + current.size / 2f
                                                    val target =
                                                        visible.firstOrNull {
                                                            it.key != item.id &&
                                                                it.key in waitingIds &&
                                                                middle > it.offset &&
                                                                middle < it.offset + it.size
                                                        } ?: return@detectDragGestures
                                                    val targetIndex = currentQueue.indexOfFirst { it.id == target.key }
                                                    val runningFirst = currentQueue.firstOrNull()?.status == GenerationStatus.GENERATING
                                                    if (targetIndex < 0 || (targetIndex == 0 && runningFirst)) return@detectDragGestures
                                                    viewModel.moveQueueItem(item.id, targetIndex)
                                                    dragOffset += current.offset - target.offset
                                                    awaitedIndex = target.index
                                                },
                                            )
                                        }
                                    },
                            )
                        }
                    }
                    if (waiting.isNotEmpty()) {
                        item(key = "end") { EndRow(time = at(totalEnd), estimated = totalEnd != null) }
                    }
                    if (failed.isNotEmpty()) {
                        item(key = "aside") {
                            MainSectionLabel(
                                "Set aside · ${failed.size}",
                                actionText = "Remove All",
                                onAction = { viewModel.removeFailedJobs() },
                            )
                        }
                        items(failed, key = { "aside-" + it.id }) { item ->
                            FailedJobCard(
                                item = item,
                                onRetry = { viewModel.retryFailed(item.id) },
                                onDuplicate = { newSeed -> viewModel.duplicateJob(item.id, newSeed) },
                                onEdit = { edit(item) },
                                onRemove = { offerUndo("Job removed", viewModel.removeFromQueue(item.id)) },
                                modifier = Modifier.animateItem().padding(bottom = 8.dp),
                            )
                        }
                    }
                }
            }
        }

        if (showStartTimePicker) {
            QueueStartTimeDialog(
                initial = scheduledStart,
                onDismiss = { showStartTimePicker = false },
                onPick = { hour, minute -> viewModel.scheduleQueueStart(hour, minute) },
            )
        }

        if (editItemId != null) {
            AlertDialog(
                onDismissRequest = { editItemId = null },
                title = { Text("Edit Queue Item") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = editPosPrompt,
                            onValueChange = { editPosPrompt = it },
                            label = { Text("Positive Prompt") },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 200.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = editNegPrompt,
                            onValueChange = { editNegPrompt = it },
                            label = { Text("Negative Prompt") },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 150.dp),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.updateQueueItem(editItemId!!, editPosPrompt, editNegPrompt)
                        editItemId = null
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { editItemId = null }) { Text("Cancel") }
                },
            )
        }
    }
}

/** "3 jobs · 7 images". */
private fun summaryOf(jobs: List<QueuedGeneration>): String {
    val images = jobs.sumOf { it.payload.n_iter.coerceAtLeast(1) * it.payload.batch_size.coerceAtLeast(1) }
    return "${jobs.size} ${if (jobs.size == 1) "job" else "jobs"} · $images ${if (images == 1) "image" else "images"}"
}

/** "832×1216 · 28 steps · 2 images · hires ×2". */
private fun settingsLine(p: Txt2ImgPayloadDto): String {
    val images = p.n_iter.coerceAtLeast(1) * p.batch_size.coerceAtLeast(1)
    return buildList {
        add("${p.width}×${p.height}")
        add("${p.steps} steps")
        if (images > 1) add("$images images")
        if (p.enable_hr) add("hires ×${ModelSettingsRules.formatCfg(p.hr_scale)}")
    }.joinToString(" · ")
}

/** The server does [ahead] other jobs first (board 2B): its web UI's or another app's; the running job waits. */
@Composable
private fun ServerJobsFirstNote(ahead: Int) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFFFFA726).copy(alpha = 0.14f),
        contentColor = Color(0xFFFFD9A8),
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Icon(Icons.Default.HourglassTop, contentDescription = null, tint = Color(0xFFFFA726), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                "The server does ${if (ahead == 1) "1 other job" else "$ahead other jobs"} first, from its web UI or another " +
                    "app. Yours starts after them.",
                fontSize = 13.sp,
                lineHeight = 17.sp,
            )
        }
    }
}

/** "45% · 12 s left · done 14:04". */
private fun runningLine(
    progress: Float,
    eta: Double,
    doneAt: String?,
): String =
    buildList {
        add("${(progress * 100).toInt()}%")
        if (eta > 0) add("${String.format(Locale.US, "%.0f", eta)} s left")
        if (doneAt != null) add("done $doneAt")
    }.joinToString(" · ")

/** The chip that sets "Start at" (or, while the queue waits for it, starts it now), and what the queue holds. */
@Composable
private fun StartRow(
    scheduledAt: Long?,
    summary: String,
    onPick: () -> Unit,
    onStartNow: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            Surface(
                onClick = { if (scheduledAt != null) menu = true else onPick() },
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Row(
                    modifier = Modifier.height(36.dp).padding(start = 10.dp, end = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (scheduledAt != null) "Starts at ${QueueSchedule.formatTime(scheduledAt)}" else "Starts now",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Start Now") }, onClick = {
                    menu = false
                    onStartNow()
                })
                DropdownMenuItem(text = { Text("Change Time") }, onClick = {
                    menu = false
                    onPick()
                })
            }
        }
        Text(
            summary,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

/** A place on the timeline: the time, the rail with its dot, and [content]. */
@Composable
private fun TimelineRow(
    time: String,
    highlighted: Boolean,
    first: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val rail = MaterialTheme.colorScheme.surfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    Row(
        modifier =
            modifier.fillMaxWidth().drawBehind {
                val x = RAIL_CENTER.toPx()
                drawLine(rail, Offset(x, if (first) DOT_CENTER.toPx() else 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
            },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            time,
            fontSize = 12.sp,
            fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal,
            color = if (highlighted) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(TIME_WIDTH).padding(top = 14.dp),
        )
        // The running job's dot has a halo; its centre is where the rail of the first job starts (DOT_CENTER).
        Box(
            modifier =
                Modifier
                    .padding(top = 13.dp)
                    .size(20.dp)
                    .background(if (highlighted) primary.copy(alpha = 0.25f) else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(12.dp)
                        .background(if (highlighted) primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f), CircleShape),
            )
        }
        Box(modifier = Modifier.weight(1f).padding(bottom = 8.dp)) { content() }
    }
}

/** The end of the timeline: when everything should be done. */
@Composable
private fun EndRow(
    time: String?,
    estimated: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            time ?: "",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(TIME_WIDTH),
        )
        Box(modifier = Modifier.width(20.dp), contentAlignment = Alignment.Center) {
            Box(
                modifier =
                    Modifier
                        .size(14.dp)
                        .background(MaterialTheme.colorScheme.secondary, CircleShape)
                        .padding(2.dp)
                        .background(MaterialTheme.colorScheme.background, CircleShape),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text("All done", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (!estimated) {
                Text(
                    "The times show after the first finished job",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                )
            }
        }
    }
}

/** A job on the timeline: its prompt and settings; open, its whole prompt and what can be done with it. */
@Composable
private fun JobCard(
    item: QueuedGeneration,
    running: Boolean,
    progressLine: String?,
    progress: Float?,
    onDuplicate: (newSeed: Boolean) -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    dragHandle: Modifier?,
    onSkip: (() -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.clickable { open = !open }.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f).padding(end = if (dragHandle == null) 10.dp else 0.dp)) {
                    Text(item.positivePrompt.ifBlank { "(empty prompt)" }, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    SettingsText(item)
                }
                if (dragHandle != null) {
                    Icon(
                        Icons.Default.DragHandle,
                        contentDescription = "Drag to Reorder",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                        modifier = Modifier.size(44.dp).then(dragHandle).padding(10.dp),
                    )
                }
            }
            if (progress != null && progressLine != null) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 10.dp).height(6.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    drawStopIndicator = {},
                    gapSize = 0.dp,
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp, end = 6.dp)) {
                    Text(
                        progressLine,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        modifier = Modifier.weight(1f),
                    )
                    if (onSkip != null) {
                        FilledTonalButton(
                            onClick = onSkip,
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            modifier = Modifier.height(34.dp),
                        ) {
                            Icon(Icons.Default.SkipNext, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Skip Image", fontSize = 13.sp)
                        }
                    }
                }
            }
            AnimatedVisibility(visible = open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                JobDetails(item, canChange = !running, onDuplicate = onDuplicate, onEdit = onEdit, onRemove = onRemove)
            }
        }
    }
}

/** "Upscale ×2 · 1024×1024 · 20 steps": what made the job (in blue), then its settings. */
@Composable
private fun SettingsText(item: QueuedGeneration) {
    val labelColor = MaterialTheme.colorScheme.secondary
    Text(
        buildAnnotatedString {
            item.label?.let {
                withStyle(SpanStyle(color = labelColor)) { append(it) }
                append(" · ")
            }
            append(settingsLine(item.payload))
        },
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** An opened job: its whole prompt, the rest of its settings, and Duplicate / Edit / Remove. */
@Composable
private fun JobDetails(
    item: QueuedGeneration,
    canChange: Boolean,
    onDuplicate: (newSeed: Boolean) -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    val p = item.payload
    val quiet = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
    Column(modifier = Modifier.padding(top = 10.dp, end = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(item.positivePrompt, fontSize = 13.sp, lineHeight = 18.sp)
        if (p.negative_prompt.isNotBlank()) {
            Text("Negative: ${p.negative_prompt}", fontSize = 13.sp, lineHeight = 18.sp, color = quiet)
        }
        Text(
            buildList {
                add("CFG ${ModelSettingsRules.formatCfg(p.cfg_scale)}")
                add(p.sampler_name)
                add(p.scheduler)
                add(if (p.seed == -1L) "random seed" else "seed ${p.seed}")
                p.override_settings.sdModelCheckpoint?.let { add(ModelSettingsRules.key(it)) }
            }.joinToString(" · "),
            fontSize = 12.sp,
            color = quiet,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 2.dp)) {
            var duplicateMenu by remember { mutableStateOf(false) }
            Box {
                JobAction(Icons.Default.ContentCopy, "Duplicate") { duplicateMenu = true }
                DropdownMenu(expanded = duplicateMenu, onDismissRequest = { duplicateMenu = false }) {
                    DropdownMenuItem(text = { Text("Duplicate") }, onClick = {
                        duplicateMenu = false
                        onDuplicate(false)
                    })
                    if (p.seed != -1L) {
                        DropdownMenuItem(text = { Text("Duplicate with a New Seed") }, onClick = {
                            duplicateMenu = false
                            onDuplicate(true)
                        })
                    }
                }
            }
            if (canChange) {
                JobAction(Icons.Default.Edit, "Edit", onClick = onEdit)
                JobAction(Icons.Default.Delete, "Remove", color = MaterialTheme.colorScheme.error, onClick = onRemove)
            }
        }
    }
}

@Composable
private fun JobAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    color: Color = MaterialTheme.colorScheme.secondary,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 10.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = color),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 14.sp)
    }
}

/** A job set aside after an error: why, Retry, and (open) what else can be done with it. */
@Composable
private fun FailedJobCard(
    item: QueuedGeneration,
    onRetry: () -> Unit,
    onDuplicate: (newSeed: Boolean) -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.clickable { open = !open }.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RowIcon(Icons.Default.Warning, MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.positivePrompt.ifBlank { "(empty prompt)" }, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        item.error ?: "The job failed",
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = if (open) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(
                    onClick = onRetry,
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    modifier = Modifier.height(36.dp),
                ) {
                    Text("Retry", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            AnimatedVisibility(visible = open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(modifier = Modifier.padding(start = 54.dp)) {
                    SettingsText(item)
                    JobDetails(item, canChange = true, onDuplicate = onDuplicate, onEdit = onEdit, onRemove = onRemove)
                }
            }
        }
    }
}
