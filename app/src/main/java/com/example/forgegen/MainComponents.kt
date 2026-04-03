@file:Suppress("UNUSED_VALUE", "ASSIGNED_BUT_NEVER_ACCESSED_VARIABLE")

package com.example.forgegen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.abs
import kotlin.math.roundToInt

/* ============================================================================
 * HELPER CLASSES & FUNCTIONS
 * ============================================================================ */

class PromptVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val spanStyles = mutableListOf<AnnotatedString.Range<SpanStyle>>()
        val str = text.text

        Regex("<lora:[^>]+>").findAll(str).forEach { match ->
            spanStyles.add(AnnotatedString.Range(SpanStyle(color = Color(0xFFB388FF), fontWeight = FontWeight.Bold), match.range.first, match.range.last + 1))
        }
        Regex("\\([^)]+\\)").findAll(str).forEach { match ->
            spanStyles.add(AnnotatedString.Range(SpanStyle(color = Color(0xFFFFD54F)), match.range.first, match.range.last + 1))
        }
        Regex("\\[[^]]+]").findAll(str).forEach { match ->
            spanStyles.add(AnnotatedString.Range(SpanStyle(color = Color(0xFF81C784)), match.range.first, match.range.last + 1))
        }

        return TransformedText(AnnotatedString(str, spanStyles), OffsetMapping.Identity)
    }
}

fun countTokens(text: String): Int {
    if (text.isBlank()) return 0
    val words = text.split(Regex("[,\\s]+")).filter { it.isNotBlank() }
    return words.size
}

fun getTagStrength(tag: String): String {
    val trimmed = tag.trim()
    val pattern = Pattern.compile("^\\((.*):([0-9.]+)\\)$")
    val matcher = pattern.matcher(trimmed)
    if (matcher.find() && matcher.groupCount() >= 2) {
        return matcher.group(2) ?: "1.0"
    }
    return "1.0"
}

fun adjustTagStrength(tag: String, delta: Float): String {
    val trimmed = tag.trim()
    val pattern = Pattern.compile("^\\((.*):([0-9.]+)\\)$")
    val matcher = pattern.matcher(trimmed)

    if (matcher.find() && matcher.groupCount() >= 2) {
        val base = matcher.group(1)
        val currentStrength = matcher.group(2)?.toFloatOrNull() ?: 1.0f
        val newStrength = (currentStrength + delta).coerceIn(0.1f, 3.0f)
        if (abs(newStrength - 1.0f) < 0.05f) return base ?: ""
        return "($base:${String.format(Locale.US, "%.1f", newStrength)})"
    } else {
        val newStrength = (1.0f + delta).coerceIn(0.1f, 3.0f)
        if (abs(newStrength - 1.0f) < 0.05f) return trimmed
        return "($trimmed:${String.format(Locale.US, "%.1f", newStrength)})"
    }
}

/* ============================================================================
 * SHARED UI COMPONENTS
 * ============================================================================ */

@Composable
fun SectionHeader(title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 8.dp))
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

@Composable
fun UndoRedoTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    actions: @Composable RowScope.() -> Unit = {}
) {
    var history by remember { mutableStateOf(listOf(value)) }
    var historyIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(value) {
        if (history.isEmpty() || history[historyIndex] != value) {
            val newHistory = history.take(historyIndex + 1) + value
            history = newHistory
            historyIndex = newHistory.size - 1
        }
    }

    Column(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(
                onClick = {
                    if (historyIndex > 0) {
                        historyIndex--
                        onValueChange(history[historyIndex])
                    }
                },
                enabled = historyIndex > 0,
                modifier = Modifier.size(32.dp)
            ) { Icon(Icons.AutoMirrored.Filled.Undo, null, Modifier.size(18.dp)) }

            IconButton(
                onClick = {
                    if (historyIndex < history.size - 1) {
                        historyIndex++
                        onValueChange(history[historyIndex])
                    }
                },
                enabled = historyIndex < history.size - 1,
                modifier = Modifier.size(32.dp)
            ) { Icon(Icons.AutoMirrored.Filled.Redo, null, Modifier.size(18.dp)) }
        }

        OutlinedTextField(
            value = value,
            onValueChange = {
                if (it != value) {
                    val newHistory = history.take(historyIndex + 1) + it
                    history = newHistory
                    historyIndex = newHistory.size - 1
                    onValueChange(it)
                }
            },
            label = label,
            minLines = minLines,
            maxLines = maxLines,
            visualTransformation = visualTransformation,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp)) {
                    if (value.isNotEmpty()) {
                        VerticalDivider(
                            modifier = Modifier.height(24.dp).padding(horizontal = 4.dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                        )
                    }
                    actions()
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DragToReorderTagsRow(text: String, onPromptChanged: (String) -> Unit) {
    val tags = text.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    if (tags.isNotEmpty()) {
        var draggingIndex by remember { mutableStateOf<Int?>(null) }
        var tunedIndex by remember { mutableStateOf<Int?>(null) }

        var dragOffsetX by remember { mutableFloatStateOf(0f) }
        var dragOffsetY by remember { mutableFloatStateOf(0f) }

        fun swap(i: Int, j: Int) {
            val newTags = tags.toMutableList()
            newTags[i] = newTags[j].also { newTags[j] = newTags[i] }
            onPromptChanged(newTags.joinToString(", "))
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            tags.forEachIndexed { index, tag ->
                val isDragging = index == draggingIndex
                val isTuned = index == tunedIndex

                val modifier = if (isDragging) {
                    Modifier.offset { IntOffset(dragOffsetX.roundToInt(), dragOffsetY.roundToInt()) }.zIndex(1f)
                } else {
                    Modifier.zIndex(0f)
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
                    AnimatedVisibility(visible = isTuned && !isDragging) {
                        Row(
                            modifier = Modifier
                                .padding(bottom = 2.dp)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Remove, "Decrease",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(14.dp).clickable {
                                    val newTags = tags.toMutableList()
                                    newTags[index] = adjustTagStrength(tag, -0.1f)
                                    onPromptChanged(newTags.joinToString(", "))
                                }
                            )
                            Text(
                                getTagStrength(tag),
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 6.dp)
                            )
                            Icon(
                                Icons.Default.Add, "Increase",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(14.dp).clickable {
                                    val newTags = tags.toMutableList()
                                    newTags[index] = adjustTagStrength(tag, 0.1f)
                                    onPromptChanged(newTags.joinToString(", "))
                                }
                            )
                        }
                    }

                    AssistChip(
                        onClick = { tunedIndex = if (tunedIndex == index) null else index },
                        label = { Text(tag, fontSize = 12.sp) },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Remove",
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable {
                                        val newTags = tags.toMutableList()
                                        newTags.removeAt(index)
                                        onPromptChanged(newTags.joinToString(", "))
                                        if (tunedIndex == index) tunedIndex = null
                                    }
                            )
                        },
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.pointerInput(Unit) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggingIndex = index
                                    tunedIndex = null
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragOffsetX += dragAmount.x
                                    dragOffsetY += dragAmount.y

                                    val swapXThreshold = 150f
                                    val swapYThreshold = 80f

                                    if (dragOffsetX > swapXThreshold && index < tags.size - 1) {
                                        swap(index, index + 1)
                                        draggingIndex = index + 1
                                        dragOffsetX -= swapXThreshold
                                    } else if (dragOffsetX < -swapXThreshold && index > 0) {
                                        swap(index, index - 1)
                                        draggingIndex = index - 1
                                        dragOffsetX += swapXThreshold
                                    }

                                    if (dragOffsetY > swapYThreshold && index < tags.size - 3) {
                                        val target = (index + 3).coerceAtMost(tags.size - 1)
                                        swap(index, target)
                                        draggingIndex = target
                                        dragOffsetY -= swapYThreshold
                                    } else if (dragOffsetY < -swapYThreshold && index > 2) {
                                        val target = (index - 3).coerceAtLeast(0)
                                        swap(index, target)
                                        draggingIndex = target
                                        dragOffsetY += swapYThreshold
                                    }
                                },
                                onDragEnd = { draggingIndex = null; dragOffsetX = 0f; dragOffsetY = 0f },
                                onDragCancel = { draggingIndex = null; dragOffsetX = 0f; dragOffsetY = 0f }
                            )
                        }
                    )
                }
            }
        }
    } else {
        Text("No active tags", color = Color.Gray, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
    }
}

/* ============================================================================
 * EXPORTED UI SECTIONS
 * ============================================================================ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForgeTopAppBar(
    isConnected: Boolean,
    pingMs: Long,
    vram: String?,
    onStatsClick: () -> Unit,
    onGalleryClick: () -> Unit,
    onSettingsClick: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onRefreshServer: () -> Unit // Zostawione dla zachowania kompatybilności wstecznej
) {
    TopAppBar(
        title = {
            Column {
                Text("Forge Generator", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onStatsClick() }
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Icon(
                        imageVector = if (isConnected) Icons.Default.Wifi else Icons.Default.WifiOff,
                        contentDescription = null,
                        tint = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isConnected) "${pingMs}ms" else "Offline",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal
                    )
                    if (vram != null) {
                        Text(
                            text = " | $vram",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        },
        actions = {
            IconButton(onClick = onGalleryClick) {
                Icon(Icons.Default.PhotoLibrary, contentDescription = "Gallery")
            }

            IconButton(onClick = onSettingsClick) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    )
}

@Composable
fun ServerStatsDialog(
    serverStats: List<ServerStatRecord>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ForgeGenPrefs", Context.MODE_PRIVATE) }
    var timeRangeMinutes by remember { mutableIntStateOf(prefs.getInt("stats_time_range", 15)) }
    val ranges = listOf(15 to "15m", 60 to "1h", 720 to "12h", 1440 to "24h")

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(
            modifier = Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.85f),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp).fillMaxSize()) {
                Text("Server Statistics", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth().height(36.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(18.dp))) {
                    ranges.forEach { (mins, label) ->
                        val selected = timeRangeMinutes == mins
                        Box(
                            modifier = Modifier.weight(1f).fillMaxHeight()
                                .clip(RoundedCornerShape(18.dp))
                                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                                .clickable {
                                    timeRangeMinutes = mins
                                    prefs.edit().putInt("stats_time_range", mins).apply()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(label, fontSize = 12.sp, color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                Column(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    val cutoff = System.currentTimeMillis() - timeRangeMinutes * 60 * 1000L
                    val data = serverStats.filter { it.timestamp >= cutoff }

                    if (data.isEmpty()) {
                        Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                            Text("Not enough data yet.", color = Color.Gray)
                        }
                    } else {
                        val pings = data.map { it.pingMs.toFloat() }
                        StatChart(
                            title = "Ping (Latency)",
                            currentValue = "${pings.last().toInt()} ms",
                            avgValue = "Avg: ${pings.average().toInt()} ms",
                            points = pings,
                            minY = 0f,
                            maxY = (pings.maxOrNull() ?: 100f).coerceAtLeast(10f) * 1.2f,
                            color = Color(0xFF4CAF50)
                        )
                        Spacer(Modifier.height(24.dp))

                        val rams = data.map { it.ramUsed.toFloat() }
                        val ramTotal = data.last().ramTotal.toFloat()
                        StatChart(
                            title = "RAM Usage",
                            currentValue = String.format(Locale.US, "%.1f GB", rams.last()),
                            avgValue = "Avg: ${String.format(Locale.US, "%.1f GB", rams.average())}",
                            points = rams,
                            minY = 0f,
                            maxY = ramTotal.coerceAtLeast(1f),
                            color = Color(0xFF2196F3)
                        )
                        Spacer(Modifier.height(24.dp))

                        val vrams = data.map { it.vramUsed.toFloat() }
                        val vramTotal = data.last().vramTotal.toFloat()
                        StatChart(
                            title = "VRAM Usage",
                            currentValue = String.format(Locale.US, "%.1f GB", vrams.last()),
                            avgValue = "Avg: ${String.format(Locale.US, "%.1f GB", vrams.average())}",
                            points = vrams,
                            minY = 0f,
                            maxY = vramTotal.coerceAtLeast(1f),
                            color = Color(0xFF9C27B0)
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Close")
                }
            }
        }
    }
}

@Composable
fun StatChart(
    title: String,
    currentValue: String,
    avgValue: String,
    points: List<Float>,
    minY: Float,
    maxY: Float,
    color: Color
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Column(horizontalAlignment = Alignment.End) {
                Text(currentValue, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = color)
                Text(avgValue, fontSize = 10.sp, color = Color.Gray)
            }
        }
        Spacer(Modifier.height(8.dp))

        Canvas(modifier = Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.3f))) {
            val w = size.width
            val h = size.height

            val gridLines = 4
            for (i in 0..gridLines) {
                val y = h * (i.toFloat() / gridLines)
                drawLine(
                    color = Color.Gray.copy(alpha = 0.2f),
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 1.dp.toPx()
                )
            }

            if (points.isEmpty()) return@Canvas

            val range = maxY - minY
            val validRange = if (range <= 0f) 1f else range

            if (points.size == 1) {
                val y = h - ((points[0] - minY) / validRange) * h
                drawLine(color, start = Offset(0f, y), end = Offset(w, y), strokeWidth = 2.dp.toPx())
                return@Canvas
            }

            val xStep = w / (points.size - 1)
            val path = Path()
            val fillPath = Path()

            var prevX = 0f
            var prevY = h - ((points[0] - minY) / validRange) * h

            path.moveTo(prevX, prevY)
            fillPath.moveTo(prevX, h)
            fillPath.lineTo(prevX, prevY)

            for (i in 1 until points.size) {
                val x = i * xStep
                val y = h - ((points[i] - minY) / validRange) * h

                val cpX = (prevX + x) / 2f
                path.cubicTo(cpX, prevY, cpX, y, x, y)
                fillPath.cubicTo(cpX, prevY, cpX, y, x, y)

                prevX = x
                if (i < points.size - 1) prevY = y
            }

            fillPath.lineTo(prevX, h)
            fillPath.close()

            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(color.copy(alpha = 0.5f), Color.Transparent),
                    startY = 0f,
                    endY = h
                )
            )

            drawPath(
                path = path,
                color = color,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }
}

@Composable
fun OomAlertSection(viewModel: ForgeViewModel) {
    val oomAlert by viewModel.oomAlert.collectAsStateWithLifecycle()
    AnimatedVisibility(visible = oomAlert) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Warning, contentDescription = "Error", tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(32.dp))
                Spacer(modifier = Modifier.height(8.dp))
                Text("SERVER OUT OF MEMORY (OOM)", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                Text("The current generation failed and the queue is paused. The failed prompt was skipped.", fontSize = 12.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { viewModel.resumeQueue() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onErrorContainer, contentColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text("Resume Queue")
                }
            }
        }
    }
}

@Composable
fun PreviewSection(
    isGenerating: Boolean,
    previewMode: String,
    livePreviewBase64: String?,
    isShowingGridPreview: Boolean,
    sessionImages: List<String>,
    batchStart: Int,
    batchEnd: Int,
    currentSessionIndex: Int,
    onDismissGrid: (Int) -> Unit,
    onFullscreen: (Int) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    // Stan rozmycia dla obrazków NSFW (domyślnie włączony z możliwością tymczasowego wyłączenia)
    var isBlurred by remember { mutableStateOf(true) }

    Box(modifier = Modifier.fillMaxWidth().height(240.dp).clip(MaterialTheme.shapes.medium).background(Color.DarkGray)) {

        // Modyfikator nakładający blur o sile 25.dp (ukrywa detale, zachowuje paletę barw)
        val blurModifier = if (isBlurred) Modifier.blur(25.dp) else Modifier

        // Zamknięcie samej zawartości w sub-Boxie, aby blur nie wpływał na przyciski
        Box(modifier = Modifier.fillMaxSize().then(blurModifier)) {
            if (isGenerating && previewMode == "None") {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.align(Alignment.Center)) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Generating...", color = Color.LightGray, fontSize = 12.sp)
                }
            } else if (isGenerating && !livePreviewBase64.isNullOrEmpty()) {
                val previewBitmap by produceState<Bitmap?>(initialValue = null, livePreviewBase64) {
                    value = withContext(Dispatchers.Default) {
                        try {
                            val bytes = Base64.decode(livePreviewBase64, Base64.DEFAULT)
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        } catch (_: Exception) { null }
                    }
                }
                previewBitmap?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Live Preview",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }
            } else if (isShowingGridPreview && sessionImages.size > batchStart && batchEnd >= batchStart) {
                val batchImages = sessionImages.subList(batchStart, batchEnd + 1)
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 80.dp),
                    modifier = Modifier.fillMaxSize().padding(bottom = 36.dp),
                    contentPadding = PaddingValues(4.dp)
                ) {
                    items(batchImages.size) { index ->
                        val imgPath = batchImages[index]
                        AsyncImage(
                            model = imgPath,
                            contentDescription = null,
                            modifier = Modifier
                                .padding(2.dp)
                                .aspectRatio(1f)
                                .clip(MaterialTheme.shapes.small)
                                .clickable {
                                    onDismissGrid(batchStart + index)
                                    onFullscreen(batchStart + index)
                                },
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            } else if (currentSessionIndex >= 0 && sessionImages.isNotEmpty() && currentSessionIndex < sessionImages.size) {
                AsyncImage(
                    model = sessionImages[currentSessionIndex],
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().clickable { onFullscreen(currentSessionIndex) },
                    contentScale = ContentScale.Fit
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.align(Alignment.Center)) {
                    Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color.Gray)
                    Text("No Preview", color = Color.Gray)
                }
            }
        } // Koniec sub-Boxa dla obrazków

        // --- NAKŁADKI UI (Przyciski sterujące nie podlegające blurowi) ---

        // Przycisk widoczności
        IconButton(
            onClick = { isBlurred = !isBlurred },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                .size(32.dp)
        ) {
            Icon(
                imageVector = if (isBlurred) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                contentDescription = "Toggle Blur",
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }

        Row(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            FilledTonalButton(onClick = onPrev, enabled = currentSessionIndex > batchStart, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(32.dp)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null)
            }
            FilledTonalButton(onClick = onNext, enabled = currentSessionIndex < batchEnd, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(32.dp)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
            }
        }
    }
}

@Composable
fun PromptsSection(
    viewModel: ForgeViewModel,
    state: AppState,
    config: AppConfig,
    tagSuggestions: List<String>,
    promptHistory: List<PromptHistoryItem>,
    navController: NavHostController
) {
    var showPresetsDialog by remember { mutableStateOf(false) }
    var showRecoverMenu by remember { mutableStateOf(false) }
    var showHistoryDialog by remember { mutableStateOf(false) }
    var showPosTagEditor by remember { mutableStateOf(false) }
    var showNegTagEditor by remember { mutableStateOf(false) }

    val context = LocalContext.current

    SectionHeader("Prompts")

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { showPresetsDialog = true }, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(32.dp)) {
            Icon(Icons.Default.SettingsSuggest, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Manage Presets", fontSize = 12.sp)
        }
        Box {
            TextButton(onClick = { showRecoverMenu = true }, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(32.dp)) {
                Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Recover Prompt", fontSize = 12.sp)
            }
            DropdownMenu(expanded = showRecoverMenu, onDismissRequest = { showRecoverMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Last Generated Image", fontSize = 14.sp) },
                    leadingIcon = { Icon(Icons.Default.Image, null, modifier = Modifier.size(20.dp)) },
                    onClick = { showRecoverMenu = false; viewModel.recoverLastPrompt() }
                )
                DropdownMenuItem(
                    text = { Text("From History", fontSize = 14.sp) },
                    leadingIcon = { Icon(Icons.Default.History, null, modifier = Modifier.size(20.dp)) },
                    onClick = { showRecoverMenu = false; showHistoryDialog = true }
                )
                DropdownMenuItem(
                    text = { Text("From Gallery Image", fontSize = 14.sp) },
                    leadingIcon = { Icon(Icons.Default.PhotoLibrary, null, modifier = Modifier.size(20.dp)) },
                    onClick = {
                        showRecoverMenu = false
                        viewModel.setGalleryMode(GalleryMode.PROMPT_PICKER)
                        viewModel.fetchGalleryFolder(config.galleryPath)
                        navController.navigate("gallery")
                    }
                )
            }
        }
    }

    UndoRedoTextField(
        value = state.positivePrompt,
        onValueChange = {
            viewModel.updateState { s -> s.copy(positivePrompt = it) }
            val currentWord = it.substringAfterLast(",").trim()
            viewModel.searchTags(currentWord)
        },
        label = { Text("Positive Prompt", fontSize = 12.sp) },
        minLines = 3,
        maxLines = 8,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = PromptVisualTransformation(),
        actions = {
            if (config.showActiveTagsUI && state.positivePrompt.isNotBlank()) {
                IconButton(onClick = { showPosTagEditor = true }) {
                    Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    )

    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("${countTokens(state.positivePrompt)} / 75", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        Row {
            TextButton(onClick = {
                val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboardManager.setPrimaryClip(ClipData.newPlainText("Prompt", state.positivePrompt))
                Toast.makeText(context, "Prompt Copied", Toast.LENGTH_SHORT).show()
            }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp), modifier = Modifier.height(24.dp)) {
                Text("Copy Prompt", fontSize = 10.sp)
            }
            TextButton(onClick = { viewModel.updateState { s -> s.copy(positivePrompt = "") } }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp), modifier = Modifier.height(24.dp)) {
                Text("Clear", fontSize = 10.sp)
            }
        }
    }

    AnimatedVisibility(visible = tagSuggestions.isNotEmpty()) {
        Card(
            modifier = Modifier.fillMaxWidth().heightIn(max = 150.dp).padding(top = 4.dp, bottom = 4.dp),
            elevation = CardDefaults.cardElevation(4.dp),
            shape = MaterialTheme.shapes.medium
        ) {
            LazyColumn {
                items(tagSuggestions) { tag ->
                    Text(
                        text = tag,
                        fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().clickable {
                            val before = state.positivePrompt.substringBeforeLast(",", "")
                            val newText = if (before.isEmpty()) "$tag, " else "$before, $tag, "
                            viewModel.updateState { s -> s.copy(positivePrompt = newText) }
                            viewModel.searchTags("")
                        }.padding(12.dp)
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(4.dp))

    UndoRedoTextField(
        value = state.negativePrompt,
        onValueChange = { viewModel.updateState { s -> s.copy(negativePrompt = it) } },
        label = { Text("Negative Prompt", fontSize = 12.sp) },
        minLines = 2,
        maxLines = 6,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = PromptVisualTransformation(),
        actions = {
            if (config.showActiveTagsUI && state.negativePrompt.isNotBlank()) {
                IconButton(onClick = { showNegTagEditor = true }) {
                    Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    )

    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("${countTokens(state.negativePrompt)} / 75", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        TextButton(onClick = { viewModel.resetToDefaults() }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp), modifier = Modifier.height(24.dp)) {
            Text("Reset to Defaults", fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
        }
    }

    // --- PROMPT SECTION DIALOGS ---

    if (showPresetsDialog) {
        var newPresetName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPresetsDialog = false },
            title = { Text("Manage Presets") },
            text = {
                Column {
                    LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                        items(config.presets) { preset ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    viewModel.loadPreset(preset.name)
                                    showPresetsDialog = false
                                }.padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(preset.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    Text("${preset.state.steps} Steps | CFG: ${preset.state.cfgScale} | ${preset.state.sampler}", fontSize = 10.sp, color = Color.Gray)
                                }
                                IconButton(onClick = { viewModel.deletePreset(preset.name) }) {
                                    Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Save Current Settings as Preset", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    OutlinedTextField(
                        value = newPresetName,
                        onValueChange = { newPresetName = it },
                        label = { Text("Preset Name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { viewModel.saveCurrentAsDefault(); showPresetsDialog = false }) {
                            Text("Set Current as Default", fontSize = 12.sp)
                        }
                        Button(
                            onClick = {
                                if (newPresetName.isNotBlank()) {
                                    viewModel.savePreset(newPresetName)
                                    newPresetName = ""
                                }
                            }
                        ) {
                            Text("Save Preset")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPresetsDialog = false }) { Text("Close") } }
        )
    }

    if (showPosTagEditor) {
        AlertDialog(
            onDismissRequest = { showPosTagEditor = false },
            title = { Text("Active Positive Tags", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            text = {
                Box(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp).background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small).padding(8.dp)) {
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        DragToReorderTagsRow(state.positivePrompt) { newPrompt -> viewModel.updateState { s -> s.copy(positivePrompt = newPrompt) } }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPosTagEditor = false }) { Text("Done") } }
        )
    }

    if (showNegTagEditor) {
        AlertDialog(
            onDismissRequest = { showNegTagEditor = false },
            title = { Text("Active Negative Tags", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            text = {
                Box(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp).background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small).padding(8.dp)) {
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        DragToReorderTagsRow(state.negativePrompt) { newPrompt -> viewModel.updateState { s -> s.copy(negativePrompt = newPrompt) } }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showNegTagEditor = false }) { Text("Done") } }
        )
    }

    if (showHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showHistoryDialog = false },
            title = {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Prompt History")
                    IconButton(onClick = { viewModel.clearPromptHistory(); showHistoryDialog = false }) {
                        Icon(Icons.Default.Delete, null)
                    }
                }
            },
            text = {
                if (promptHistory.isEmpty()) {
                    Text("No history available yet.", color = Color.Gray)
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(promptHistory) { item ->
                            val timeFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(item.timestamp)
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                                    viewModel.updateState { it.copy(positivePrompt = item.positivePrompt, negativePrompt = item.negativePrompt) }
                                    showHistoryDialog = false
                                },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text(timeFormat, fontSize = 10.sp, color = Color.Gray, modifier = Modifier.align(Alignment.End))
                                    if (item.positivePrompt.isNotBlank()) Text(item.positivePrompt, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    if (item.negativePrompt.isNotBlank()) Text("Negative: " + item.negativePrompt, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 10.sp, color = Color.Gray)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showHistoryDialog = false }) { Text("Close") } }
        )
    }
}

@Composable
fun GenerationSettingsSection(
    viewModel: ForgeViewModel,
    state: AppState,
    models: List<ApiResource>,
    selectedModel: String,
    samplers: List<String>,
    schedulers: List<String>,
    upscalers: List<String>
) {
    SectionHeader("Settings")

    Column(modifier = Modifier.padding(horizontal = 12.dp)) {
        var modelExpanded by remember { mutableStateOf(false) }
        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            OutlinedButton(onClick = { modelExpanded = true }, modifier = Modifier.fillMaxWidth().height(54.dp), contentPadding = PaddingValues(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val currentModelResource = models.find { it.title == selectedModel }
                    if (currentModelResource != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(viewModel.getPreviewUrl(currentModelResource.path, isLora = false))
                                .crossfade(true)
                                .build(),
                            contentDescription = null,
                            modifier = Modifier.size(32.dp).padding(end = 8.dp).clip(RoundedCornerShape(6.dp)).background(Color.DarkGray),
                            contentScale = ContentScale.Crop
                        )
                    }
                    Text("Model: ${selectedModel.ifEmpty { "Loading..." }}", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                }
            }
            DropdownMenu(expanded = modelExpanded, onDismissRequest = { modelExpanded = false }, modifier = Modifier.heightIn(max = 350.dp)) {
                models.forEach { mod ->
                    val isSelected = mod.title == selectedModel
                    DropdownMenuItem(
                        modifier = if (isSelected) Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)) else Modifier,
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(viewModel.getPreviewUrl(mod.path, isLora = false))
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp).padding(end = 8.dp).clip(RoundedCornerShape(6.dp)).background(Color.DarkGray),
                                    contentScale = ContentScale.Crop
                                )
                                Text(
                                    text = mod.title,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        },
                        onClick = { viewModel.changeCheckpoint(mod.title); modelExpanded = false }
                    )
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = if (state.seed == -1L) "-1" else state.seed.toString(),
                onValueChange = {
                    val parsed = it.toLongOrNull()
                    if (parsed != null || it == "-" || it.isEmpty()) {
                        viewModel.updateState { s -> s.copy(seed = parsed ?: -1L) }
                    }
                },
                label = { Text("Seed", fontSize = 12.sp) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f)
            )

            var seedExpanded by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { seedExpanded = true }, modifier = Modifier.padding(start = 8.dp)) {
                    Icon(Icons.Default.Casino, contentDescription = "Seed Options")
                }
                DropdownMenu(expanded = seedExpanded, onDismissRequest = { seedExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Randomize (-1)", fontSize = 14.sp) },
                        onClick = { viewModel.updateState { s -> s.copy(seed = -1L) }; seedExpanded = false }
                    )
                    DropdownMenuItem(
                        text = { Text("Recover Last Seed", fontSize = 14.sp) },
                        onClick = { viewModel.recoverLastSeed(); seedExpanded = false }
                    )
                }
            }
        }

        ForgeSlider(
            "Batch Count",
            state.batchCount.toFloat(),
            1f..100f,
            0
        ) { viewModel.updateState { s -> s.copy(batchCount = it.toInt()) } }
        ForgeSlider(
            "Batch Size",
            state.batchSize.toFloat(),
            1f..16f,
            0
        ) { viewModel.updateState { s -> s.copy(batchSize = it.toInt()) } }
        ForgeSlider(
            "Steps",
            state.steps.toFloat(),
            1f..100f,
            0
        ) { viewModel.updateState { s -> s.copy(steps = it.toInt()) } }
        ForgeSlider(
            "CFG Scale",
            state.cfgScale,
            1f..20f,
            1
        ) { viewModel.updateState { s -> s.copy(cfgScale = it) } }
        ForgeSlider(
            "Clip Skip",
            state.clipSkip.toFloat(),
            1f..3f,
            0
        ) { viewModel.updateState { s -> s.copy(clipSkip = it.toInt()) } }

        Text("Aspect Ratio", fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val aspectRatios = listOf("Custom", "1:1", "4:3", "3:4", "16:9", "9:16")
            aspectRatios.forEach { ratio ->
                val isSelected = state.aspectRatio == ratio
                Surface(
                    modifier = Modifier.clickable {
                        viewModel.updateState { s ->
                            val (w, h) = when (ratio) {
                                "1:1" -> 512 to 512
                                "4:3" -> 768 to 512
                                "3:4" -> 512 to 768
                                "16:9" -> 912 to 512
                                "9:16" -> 512 to 912
                                else -> s.width to s.height
                            }
                            s.copy(aspectRatio = ratio, width = w, height = h)
                        }
                    },
                    shape = MaterialTheme.shapes.small,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = ratio,
                        fontSize = 12.sp,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        ForgeSlider(
            "Width",
            state.width.toFloat(),
            256f..2048f,
            0
        ) {
            viewModel.updateState { s ->
                s.copy(
                    width = (it.toInt() / 64) * 64,
                    aspectRatio = "Custom"
                )
            }
        }
        ForgeSlider(
            "Height",
            state.height.toFloat(),
            256f..2048f,
            0
        ) {
            viewModel.updateState { s ->
                s.copy(
                    height = (it.toInt() / 64) * 64,
                    aspectRatio = "Custom"
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            var samplerExpanded by remember { mutableStateOf(false) }
            Box(modifier = Modifier.weight(1f)) {
                OutlinedButton(onClick = { samplerExpanded = true }, modifier = Modifier.fillMaxWidth().height(36.dp), contentPadding = PaddingValues(4.dp)) {
                    Text(state.sampler, maxLines = 1, fontSize = 11.sp, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(expanded = samplerExpanded, onDismissRequest = { samplerExpanded = false }, modifier = Modifier.heightIn(max = 350.dp)) {
                    samplers.forEach { samp ->
                        val isSelected = samp == state.sampler
                        DropdownMenuItem(
                            modifier = if (isSelected) Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)) else Modifier,
                            text = {
                                Text(
                                    text = samp,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            },
                            onClick = { viewModel.updateState { s -> s.copy(sampler = samp) }; samplerExpanded = false }
                        )
                    }
                }
            }

            var schedulerExpanded by remember { mutableStateOf(false) }
            Box(modifier = Modifier.weight(1f)) {
                OutlinedButton(onClick = { schedulerExpanded = true }, modifier = Modifier.fillMaxWidth().height(36.dp), contentPadding = PaddingValues(4.dp)) {
                    Text(state.scheduler, maxLines = 1, fontSize = 11.sp, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(expanded = schedulerExpanded, onDismissRequest = { schedulerExpanded = false }, modifier = Modifier.heightIn(max = 350.dp)) {
                    schedulers.forEach { sched ->
                        val isSelected = sched == state.scheduler
                        DropdownMenuItem(
                            modifier = if (isSelected) Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)) else Modifier,
                            text = {
                                Text(
                                    text = sched,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            },
                            onClick = { viewModel.updateState { s -> s.copy(scheduler = sched) }; schedulerExpanded = false }
                        )
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            HorizontalDivider(modifier = Modifier.weight(1f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp).clickable { viewModel.updateState { it.copy(hiresFix = !it.hiresFix) } }
            ) {
                Checkbox(checked = state.hiresFix, onCheckedChange = { v -> viewModel.updateState { it.copy(hiresFix = v) } })
                Text("Hires.fix", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
            }
            HorizontalDivider(modifier = Modifier.weight(1f))
        }

        AnimatedVisibility(visible = state.hiresFix) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                var upscalerExpanded by remember { mutableStateOf(false) }
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    OutlinedButton(onClick = { upscalerExpanded = true }, modifier = Modifier.fillMaxWidth().height(36.dp), contentPadding = PaddingValues(4.dp)) {
                        Text("Upscaler: ${state.upscaler}", maxLines = 1, fontSize = 11.sp, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded = upscalerExpanded, onDismissRequest = { upscalerExpanded = false }, modifier = Modifier.heightIn(max = 350.dp)) {
                        upscalers.forEach { upsc ->
                            val isSelected = upsc == state.upscaler
                            DropdownMenuItem(
                                modifier = if (isSelected) Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)) else Modifier,
                                text = {
                                    Text(
                                        text = upsc,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                onClick = { viewModel.updateState { s -> s.copy(upscaler = upsc) }; upscalerExpanded = false }
                            )
                        }
                    }
                }
                ForgeSlider(
                    "Hires Scale",
                    state.hiresScale,
                    1f..4f,
                    2
                ) { viewModel.updateState { s -> s.copy(hiresScale = it) } }
                ForgeSlider(
                    "Denoising",
                    state.denoising,
                    0f..1f,
                    2
                ) { viewModel.updateState { s -> s.copy(denoising = it) } }
            }
        }
    }
}

@Composable
fun LorasSection(
    viewModel: ForgeViewModel,
    availableLoras: List<ApiResource>,
    activeLoras: List<ActiveLora>,
    onPendingLora: (ApiResource) -> Unit,
    onOpenTagsPopup: (String, String) -> Unit
) {
    SectionHeader("LoRAs")

    val context = LocalContext.current

    Column(modifier = Modifier.padding(horizontal = 12.dp)) {
        var loraExpanded by remember { mutableStateOf(false) }
        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            OutlinedButton(onClick = { loraExpanded = true }, modifier = Modifier.fillMaxWidth().height(36.dp), contentPadding = PaddingValues(4.dp)) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Add LoRA...", fontSize = 12.sp)
            }
            DropdownMenu(expanded = loraExpanded, onDismissRequest = { loraExpanded = false }, modifier = Modifier.heightIn(max = 350.dp)) {
                availableLoras.forEach { loraData ->
                    val isSelected = activeLoras.any { it.name == loraData.name }
                    DropdownMenuItem(
                        modifier = if (isSelected) Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)) else Modifier,
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(viewModel.getPreviewUrl(loraData.path, isLora = true))
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp).padding(end = 8.dp).clip(RoundedCornerShape(6.dp)).background(Color.DarkGray),
                                    contentScale = ContentScale.Crop
                                )
                                Text(
                                    text = loraData.title,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        },
                        onClick = {
                            onPendingLora(loraData)
                            loraExpanded = false
                        }
                    )
                }
            }
        }

        activeLoras.forEach { lora ->
            val loraResource = availableLoras.find { it.name == lora.name }
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = MaterialTheme.shapes.medium) {
                Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (loraResource != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(viewModel.getPreviewUrl(loraResource.path, isLora = true))
                                .crossfade(true)
                                .build(),
                            contentDescription = null,
                            modifier = Modifier.size(50.dp).padding(end = 8.dp).clip(RoundedCornerShape(6.dp)).background(Color.DarkGray),
                            contentScale = ContentScale.Crop
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(lora.name, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = {
                                        val hash = loraResource?.hash
                                        if (hash != null) {
                                            onOpenTagsPopup(hash, lora.name)
                                        } else {
                                            Toast.makeText(context, "Brak metadanych modelu. Odśwież API.", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Default.LocalOffer, "View Tags", modifier = Modifier.size(16.dp))
                                }

                                IconButton(onClick = { viewModel.removeLora(lora.name) }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Strength", fontSize = 10.sp)
                            Text(String.format(Locale.US, "%.2f", lora.strength), fontSize = 10.sp)
                        }
                        Slider(
                            value = lora.strength,
                            onValueChange = { viewModel.updateLoraStrength(lora.name, it) },
                            valueRange = 0.1f..2.0f,
                            modifier = Modifier.height(24.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun BottomControlsSection(
    viewModel: ForgeViewModel,
    state: AppState,
    generationQueueSize: Int,
    isActivelyGenerating: Boolean,
    progress: Float,
    currentEta: Double,
    currentJobNo: Int,
    currentJobCount: Int,
    currentSamplingStep: Int,
    currentSamplingSteps: Int,
    isConnected: Boolean,
    isRestoringPrompt: Boolean,
    onQueueClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp, start = 16.dp, end = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), MaterialTheme.shapes.small)
                    .clip(MaterialTheme.shapes.small)
                    .clickable { viewModel.updateState { it.copy(saveImages = !state.saveImages) } }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (state.saveImages) Icons.Default.CloudDone else Icons.Default.CloudOff,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (state.saveImages) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Server",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
                Switch(
                    checked = state.saveImages,
                    onCheckedChange = { isChecked -> viewModel.updateState { it.copy(saveImages = isChecked) } },
                    modifier = Modifier.height(24.dp)
                )
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), MaterialTheme.shapes.small)
                    .clip(MaterialTheme.shapes.small)
                    .clickable { viewModel.updateState { it.copy(saveToDevice = !state.saveToDevice) } }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Save,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (state.saveToDevice) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Device",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
                Switch(
                    checked = state.saveToDevice,
                    onCheckedChange = { isChecked -> viewModel.updateState { it.copy(saveToDevice = isChecked) } },
                    modifier = Modifier.height(24.dp)
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onQueueClick,
                modifier = Modifier.height(54.dp).weight(0.35f).shadow(8.dp, CircleShape),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.List, null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("$generationQueueSize", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }

            if (isActivelyGenerating) {
                val queueGen = remember { { viewModel.queueGeneration() } }
                val interruptGen = remember { { viewModel.interruptGeneration() } }

                Row(modifier = Modifier.weight(1.2f).height(54.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = interruptGen,
                        modifier = Modifier.weight(0.25f).fillMaxHeight().shadow(8.dp, CircleShape),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = "Interrupt", tint = MaterialTheme.colorScheme.onError)
                    }

                    Box(
                        modifier = Modifier
                            .weight(0.75f)
                            .fillMaxHeight()
                            .shadow(8.dp, CircleShape)
                            .clip(CircleShape)
                            .background(Color.DarkGray)
                            .clickable(enabled = isConnected && !isRestoringPrompt, onClick = queueGen)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progress.coerceIn(0f, 1f))
                                .background(MaterialTheme.colorScheme.primary)
                        )
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            val percentage = (progress * 100).toInt()

                            Text(
                                text = "ADD TO QUEUE • $percentage%",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                style = TextStyle(shadow = Shadow(color = Color.Black.copy(alpha = 0.8f), blurRadius = 4f))
                            )

                            val stepStr = if (currentSamplingSteps > 0) "Img ${currentJobNo + 1}/$currentJobCount | Step $currentSamplingStep/$currentSamplingSteps" else "Img ${currentJobNo + 1}/$currentJobCount | Step ${(progress * state.steps).toInt()}/${state.steps}"

                            Text(
                                text = "$stepStr | ETA: ${String.format(Locale.US, "%.1f", currentEta)}s",
                                fontSize = 9.sp,
                                color = Color.LightGray,
                                style = TextStyle(shadow = Shadow(color = Color.Black.copy(alpha = 0.8f), blurRadius = 4f))
                            )
                        }
                    }
                }
            } else {
                val genBtn = remember { { viewModel.queueGeneration() } }
                Button(
                    onClick = genBtn,
                    enabled = isConnected && !isRestoringPrompt,
                    modifier = Modifier.height(54.dp).weight(1.2f).shadow(8.dp, CircleShape),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("GENERATE", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
}

@Composable
fun LoraTriggerDialog(
    viewModel: ForgeViewModel,
    state: AppState,
    lora: ApiResource,
    onDismiss: () -> Unit
) {
    var triggerWords by remember(lora) { mutableStateOf<List<String>?>(null) }
    var selectedWords by remember { mutableStateOf(emptySet<String>()) }
    var originalPrompt by remember(lora) { mutableStateOf(state.positivePrompt) }

    LaunchedEffect(lora) {
        triggerWords = if (lora.hash != null) {
            viewModel.getTagsForLora(lora.hash)
        } else {
            emptyList()
        }
    }

    LaunchedEffect(selectedWords, lora) {
        val hasLora = originalPrompt.contains("<lora:${lora.name}:")
        var newPrompt = originalPrompt

        if (!hasLora) {
            val prefix = if (newPrompt.isNotEmpty() && !newPrompt.trimEnd().endsWith(",")) ", " else ""
            newPrompt = newPrompt.trimEnd() + prefix + "<lora:${lora.name}:1.0>"
        }

        if (selectedWords.isNotEmpty()) {
            val tags = ", " + selectedWords.joinToString(", ")
            newPrompt += tags
        }

        viewModel.updateState { it.copy(positivePrompt = newPrompt) }
    }

    AlertDialog(
        onDismissRequest = {
            viewModel.updateState { it.copy(positivePrompt = originalPrompt) }
            onDismiss()
        },
        title = { Text(lora.title, fontSize = 18.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                if (triggerWords == null) {
                    CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                    Text("Loading trigger words...", fontSize = 12.sp)
                } else {
                    if (triggerWords!!.isNotEmpty()) {
                        Text("Click tags to add/remove them from your prompt:", fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                        LazyColumn(modifier = Modifier.heightIn(max = 250.dp).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)) {
                            items(triggerWords!!) { word ->
                                val isSelected = selectedWords.contains(word)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        selectedWords = if (isSelected) selectedWords.minus(word) else selectedWords.plus(word)
                                    }.padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Checkbox(checked = isSelected, onCheckedChange = null)
                                    Text(word, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
                                }
                            }
                        }
                    } else {
                        Text("This LoRA has no trigger words associated with it. Do you still want to add it?", fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = triggerWords != null) {
                Text(if (triggerWords?.isEmpty() == true) "Add LoRA" else "Done")
            }
        },
        dismissButton = {
            TextButton(onClick = {
                viewModel.updateState { it.copy(positivePrompt = originalPrompt) }
                onDismiss()
            }) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FullscreenImageViewer(
    viewModel: ForgeViewModel,
    config: AppConfig,
    sessionImages: List<String>,
    initialIndex: Int,
    onDismiss: () -> Unit
) {
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { sessionImages.size })
    val currentFile = sessionImages.getOrNull(pagerState.currentPage) ?: ""
    val context = LocalContext.current

    LaunchedEffect(pagerState.currentPage) {
        if (currentFile.isNotEmpty()) {
            viewModel.loadMetadataForLocalFile(currentFile)
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {

            Row(modifier = Modifier.fillMaxWidth().background(Color(0x88000000)).padding(vertical = 4.dp, horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White) }
                Text(File(currentFile).name, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))

                IconButton(onClick = {
                    viewModel.shareSessionImage(currentFile) { intent ->
                        context.startActivity(intent)
                    }
                }) {
                    Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.White)
                }

                val showMetadata by viewModel.showGalleryMetadata.collectAsStateWithLifecycle()
                IconButton(onClick = { viewModel.toggleGalleryMetadata() }) {
                    Icon(Icons.Default.Info, contentDescription = "Info", tint = Color.White, modifier = Modifier.then(if (showMetadata) Modifier.background(Color(0x55FFFFFF), CircleShape).padding(2.dp) else Modifier))
                }
                IconButton(onClick = { viewModel.downloadSessionImage(currentFile) }) {
                    Icon(Icons.Default.Save, contentDescription = "Save", tint = Color.White)
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (config.swipeToBrowseGallery) {
                    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                        AsyncImage(
                            model = sessionImages[page],
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.TopCenter
                        )
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        val item = sessionImages.getOrNull(pagerState.currentPage)
                        if (item != null) {
                            AsyncImage(
                                model = item,
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = ContentScale.Fit,
                                alignment = Alignment.TopCenter
                            )
                        }
                    }
                }

                val showMetadata by viewModel.showGalleryMetadata.collectAsStateWithLifecycle()
                val currentMetadata by viewModel.currentImageMetadata.collectAsStateWithLifecycle()

                if (showMetadata) {
                    MetadataAlertDialog(
                        metadata = currentMetadata,
                        onDismiss = { viewModel.toggleGalleryMetadata() },
                        onApplyAll = null,
                        onApplyPrompt = { pos, neg ->
                            viewModel.updateState {
                                it.copy(
                                    positivePrompt = pos,
                                    negativePrompt = neg
                                )
                            }
                            Toast.makeText(context, "Prompts Applied", Toast.LENGTH_SHORT).show()
                        },
                        onApplyModel = { modelName ->
                            viewModel.changeCheckpoint(modelName)
                            Toast.makeText(
                                context,
                                "Model Applied: $modelName",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        onApplyLoras = { loraList ->
                            loraList.forEach { loraTag ->
                                val loraName = loraTag.substringAfter("<lora:").substringBefore(":")
                                if (loraName.isNotEmpty()) viewModel.appendLora(loraName)
                            }
                            Toast.makeText(context, "LoRAs Applied", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }
}