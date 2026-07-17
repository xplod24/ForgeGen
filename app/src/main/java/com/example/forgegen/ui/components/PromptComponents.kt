package com.example.forgegen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
import com.example.forgegen.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Locale
import kotlin.math.roundToInt

/* ============================================================================
 * STATIC REGEX PARSER & TOKENIZER (Performance Optimization & Couple Tags)
 * ============================================================================ */

object PromptParser {
    val LORA = Regex("<lora:[^>]+>")
    val WEIGHT_PAREN = Regex("\\([^)]+\\)")
    val WEIGHT_BRACKET = Regex("\\[[^]]+]")
    val TAG_STRENGTH = Regex("^\\((.*):([0-9.]+)\\)$")
    val SEPARATOR = Regex("[,\\s]+")
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
    actions: @Composable RowScope.() -> Unit = {},
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
                modifier = Modifier.size(32.dp),
            ) { Icon(Icons.AutoMirrored.Filled.Undo, null, Modifier.size(18.dp)) }

            IconButton(
                onClick = {
                    if (historyIndex < history.size - 1) {
                        historyIndex++
                        onValueChange(history[historyIndex])
                    }
                },
                enabled = historyIndex < history.size - 1,
                modifier = Modifier.size(32.dp),
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
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                        )
                    }
                    actions()
                }
            },
        )
    }
}

/* ============================================================================
 * PROMPT-IN-ONE HYBRID EDITOR (Custom Tokenizer & Ghost Drag 'n Drop)
 * ============================================================================ */

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HybridPromptEditor(
    prompt: String,
    onPromptChange: (String) -> Unit,
    disabledTags: Set<String>,
    onDisabledTagsChange: (Set<String>) -> Unit,
    label: String,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        UndoRedoTextField(
            value = prompt,
            onValueChange = onPromptChange,
            label = { Text(label, fontSize = 12.sp) },
            minLines = 3,
            maxLines = 8,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PromptVisualTransformation(),
        )

        // Rozbijamy tagi szanując zagnieżdżenia za pomocą customowego Tokenizera
        val activeTags = remember(prompt) { parseTags(prompt) }

        if (activeTags.isNotEmpty() || disabledTags.isNotEmpty()) {
            var isExpanded by remember { mutableStateOf(false) }

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { isExpanded = !isExpanded }
                        .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HorizontalDivider(modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (isExpanded) "Hide Tags" else "Edit Tags (${activeTags.size})",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                )
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                HorizontalDivider(modifier = Modifier.weight(1f))
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    var draggingIndex by remember { mutableStateOf<Int?>(null) }
                    var dragOffsetX by remember { mutableFloatStateOf(0f) }
                    var dragOffsetY by remember { mutableFloatStateOf(0f) }

                    // Tymczasowa lista mutowalna podczas przeciągania
                    var displayTags by remember(activeTags) { mutableStateOf(activeTags.toList()) }
                    var editingTagWeight by remember { mutableStateOf<String?>(null) }

                    val dashColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    // POPRAWKA BŁĘDU (Zdefiniowany jawny typ oraz prawidłowa metoda dashPathEffect)
                    val dashEffect: PathEffect = remember { PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f) }

                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        displayTags.forEachIndexed { index, tag ->
                            val isGhost = index == draggingIndex

                            // Modifikator offsetu działa tylko dla warstwy unoszącej się
                            val flyingModifier =
                                if (isGhost) {
                                    Modifier
                                        .offset { IntOffset(dragOffsetX.roundToInt(), dragOffsetY.roundToInt()) }
                                        .zIndex(2f)
                                        .shadow(8.dp, RoundedCornerShape(8.dp))
                                } else {
                                    Modifier.zIndex(1f)
                                }

                            val match = PromptParser.TAG_STRENGTH.find(tag)
                            val (baseName, weightStr) =
                                if (match != null && match.groupValues.size >= 3) {
                                    match.groupValues[1] to match.groupValues[2]
                                } else {
                                    if (tag.startsWith("(") && tag.endsWith(")")) {
                                        tag.drop(1).dropLast(1) to "1.1"
                                    } else {
                                        tag to "1.0"
                                    }
                                }

                            Box {
                                // GHOST - Puste pole w miejscu w którym wyląduje tag (renderowane tylko w pierwotnym slocie)
                                if (isGhost) {
                                    Box(
                                        modifier =
                                            Modifier
                                                .matchParentSize()
                                                .drawBehind {
                                                    drawRoundRect(
                                                        color = dashColor,
                                                        style = Stroke(width = 2.dp.toPx(), pathEffect = dashEffect),
                                                        cornerRadius = CornerRadius(8.dp.toPx()),
                                                    )
                                                },
                                    )
                                }

                                // CHIP - Normalny lub "latający" chip
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier =
                                        flyingModifier.pointerInput(tag) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = {
                                                    draggingIndex = index
                                                    dragOffsetX = 0f
                                                    dragOffsetY = 0f
                                                },
                                                onDrag = { change, dragAmount ->
                                                    change.consume()
                                                    dragOffsetX += dragAmount.x
                                                    dragOffsetY += dragAmount.y

                                                    var moved = false
                                                    val currentDragIdx = draggingIndex ?: index

                                                    // Swap logic approximating FlowRow grid thresholds
                                                    if (dragOffsetX > 80f && currentDragIdx < displayTags.size - 1) {
                                                        val mutList = displayTags.toMutableList()
                                                        Collections.swap(mutList, currentDragIdx, currentDragIdx + 1)
                                                        displayTags = mutList
                                                        draggingIndex = currentDragIdx + 1
                                                        dragOffsetX -= 80f
                                                        moved = true
                                                    } else if (dragOffsetX < -80f && currentDragIdx > 0) {
                                                        val mutList = displayTags.toMutableList()
                                                        Collections.swap(mutList, currentDragIdx, currentDragIdx - 1)
                                                        displayTags = mutList
                                                        draggingIndex = currentDragIdx - 1
                                                        dragOffsetX += 80f
                                                        moved = true
                                                    }

                                                    if (dragOffsetY > 45f && currentDragIdx < displayTags.size - 3) {
                                                        val mutList = displayTags.toMutableList()
                                                        Collections.swap(mutList, currentDragIdx, currentDragIdx + 3)
                                                        displayTags = mutList
                                                        draggingIndex = currentDragIdx + 3
                                                        dragOffsetY -= 45f
                                                        moved = true
                                                    } else if (dragOffsetY < -45f && currentDragIdx > 2) {
                                                        val mutList = displayTags.toMutableList()
                                                        Collections.swap(mutList, currentDragIdx, currentDragIdx - 3)
                                                        displayTags = mutList
                                                        draggingIndex = currentDragIdx - 3
                                                        dragOffsetY += 45f
                                                        moved = true
                                                    }
                                                },
                                                onDragEnd = {
                                                    draggingIndex = null
                                                    dragOffsetX = 0f
                                                    dragOffsetY = 0f
                                                    onPromptChange(displayTags.joinToString(", "))
                                                },
                                                onDragCancel = {
                                                    draggingIndex = null
                                                    dragOffsetX = 0f
                                                    dragOffsetY = 0f
                                                    displayTags = activeTags.toList() // Revert
                                                },
                                            )
                                        },
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                                    ) {
                                        Icon(
                                            Icons.Default.Visibility,
                                            contentDescription = "Disable",
                                            modifier =
                                                Modifier.size(16.dp).clickable {
                                                    val newTags = displayTags.toMutableList()
                                                    newTags.remove(tag)
                                                    onPromptChange(newTags.joinToString(", "))
                                                    onDisabledTagsChange(disabledTags + tag)
                                                },
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            baseName,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            fontWeight = FontWeight.Medium,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Icon(
                                            Icons.Default.Remove,
                                            "Decrease",
                                            modifier =
                                                Modifier.size(14.dp).clickable {
                                                    val newTags = displayTags.toMutableList()
                                                    val pos = newTags.indexOf(tag)
                                                    if (pos != -1) {
                                                        newTags[pos] = adjustTagStrength(tag, -0.1f)
                                                        onPromptChange(newTags.joinToString(", "))
                                                    }
                                                },
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                        Text(
                                            weightStr,
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier =
                                                Modifier.padding(horizontal = 4.dp).clickable {
                                                    editingTagWeight =
                                                        tag
                                                },
                                        )
                                        Icon(
                                            Icons.Default.Add,
                                            "Increase",
                                            modifier =
                                                Modifier.size(14.dp).clickable {
                                                    val newTags = displayTags.toMutableList()
                                                    val pos = newTags.indexOf(tag)
                                                    if (pos != -1) {
                                                        newTags[pos] = adjustTagStrength(tag, 0.1f)
                                                        onPromptChange(newTags.joinToString(", "))
                                                    }
                                                },
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }

                        disabledTags.forEach { tag ->
                            val match = PromptParser.TAG_STRENGTH.find(tag)
                            val baseName = if (match != null && match.groupValues.size >= 3) match.groupValues[1] else tag

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                                ) {
                                    Icon(
                                        Icons.Default.VisibilityOff,
                                        contentDescription = "Enable",
                                        modifier =
                                            Modifier.size(16.dp).clickable {
                                                val newTags = displayTags.toMutableList()
                                                newTags.add(tag)
                                                onPromptChange(newTags.joinToString(", "))
                                                onDisabledTagsChange(disabledTags - tag)
                                            },
                                        tint = Color.Gray,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(baseName, fontSize = 11.sp, color = Color.Gray)
                                }
                            }
                        }
                    } // Ends FlowRow

                    if (editingTagWeight != null) {
                        val currentEditingTag = editingTagWeight!!
                        val match = PromptParser.TAG_STRENGTH.find(currentEditingTag)
                        val (baseName, weightStr) =
                            if (match != null && match.groupValues.size >= 3) {
                                match.groupValues[1] to match.groupValues[2]
                            } else {
                                if (currentEditingTag.startsWith("(") && currentEditingTag.endsWith(")")) {
                                    currentEditingTag.drop(1).dropLast(1) to "1.1"
                                } else {
                                    currentEditingTag to "1.0"
                                }
                            }

                        var sliderValue by remember(currentEditingTag) { mutableFloatStateOf(weightStr.toFloatOrNull() ?: 1.0f) }

                        AlertDialog(
                            onDismissRequest = { editingTagWeight = null },
                            title = { Text("Adjust Weight: $baseName", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                            text = {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        "Weight: ${String.format(java.util.Locale.US, "%.1f", sliderValue)}",
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Slider(
                                        value = sliderValue,
                                        onValueChange = { sliderValue = it },
                                        onValueChangeFinished = {
                                            val newTag =
                                                if (sliderValue ==
                                                    1.0f
                                                ) {
                                                    baseName
                                                } else {
                                                    "($baseName:${String.format(java.util.Locale.US, "%.1f", sliderValue)})"
                                                }
                                            val newTags = activeTags.toMutableList()
                                            val pos = newTags.indexOf(currentEditingTag)
                                            if (pos != -1) {
                                                newTags[pos] = newTag
                                                onPromptChange(newTags.joinToString(", "))
                                                editingTagWeight = newTag
                                            }
                                        },
                                        valueRange = 0.1f..2.0f,
                                        steps = 18,
                                    )
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { editingTagWeight = null }) {
                                    Text("Done")
                                }
                            },
                        )
                    }
                } // Ends Column
            } // Ends AnimatedVisibility
        } // Ends if (activeTags.isNotEmpty() || disabledTags.isNotEmpty())
    }
}

@Composable
fun PromptHistoryCarousel(
    history: List<PromptHistoryItem>,
    onSelect: (PromptHistoryItem) -> Unit,
) {
    if (history.isEmpty()) return

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
            Icon(Icons.Default.History, null, modifier = Modifier.size(14.dp), tint = Color.Gray)
            Spacer(Modifier.width(4.dp))
            Text("Recent Prompts", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(history) { item: PromptHistoryItem ->
                Card(
                    modifier = Modifier.width(220.dp).clickable { onSelect(item) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        val timeFormat =
                            SimpleDateFormat(
                                "MMM dd, HH:mm",
                                androidx.compose.ui.platform.LocalConfiguration.current.locales[0],
                            ).format(item.timestamp)
                        Text(timeFormat, fontSize = 9.sp, color = Color.Gray, modifier = Modifier.align(Alignment.End))
                        Spacer(Modifier.height(4.dp))
                        Text(
                            item.positivePrompt,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
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
    ram: String?,
    vram: String?,
    isActivelyGenerating: Boolean,
    onUnloadClick: () -> Unit,
    onGalleryClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    TopAppBar(
        title = {
            Column {
                Text("Forge Generator", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .padding(horizontal = 4.dp, vertical = 0.dp),
                ) {
                    Icon(
                        imageVector = if (isConnected) Icons.Default.Wifi else Icons.Default.WifiOff,
                        contentDescription = null,
                        tint = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isConnected) "${pingMs}ms" else "Offline",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal,
                    )
                }
                if (ram != null || vram != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 0.dp),
                    ) {
                        if (ram != null) {
                            Icon(
                                Icons.Default.Memory,
                                contentDescription = "RAM",
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                modifier = Modifier.size(10.dp),
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = ram,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            )
                        }
                        if (ram != null && vram != null) {
                            Spacer(modifier = Modifier.width(6.dp))
                            VerticalDivider(
                                modifier = Modifier.height(8.dp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        if (vram != null) {
                            Icon(
                                Icons.Default.DeveloperBoard,
                                contentDescription = "VRAM",
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                modifier = Modifier.size(10.dp),
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = vram,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
            }
        },
        actions = {
            if (!isActivelyGenerating) {
                IconButton(onClick = onUnloadClick) {
                    Icon(Icons.Default.Memory, contentDescription = "Unload Models")
                }
            }

            IconButton(onClick = onGalleryClick) {
                Icon(Icons.Default.PhotoLibrary, contentDescription = "Gallery")
            }

            IconButton(onClick = onSettingsClick) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    )
}

@Composable
fun OomAlertSection(viewModel: ForgeViewModel) {
    val oomAlert by viewModel.oomAlert.collectAsStateWithLifecycle()
    AnimatedVisibility(visible = oomAlert) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = "Error",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("SERVER OUT OF MEMORY (OOM)", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(
                    "The current generation failed and the queue is paused. The failed prompt was skipped.",
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { viewModel.resumeQueue() },
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.onErrorContainer,
                            contentColor = MaterialTheme.colorScheme.errorContainer,
                        ),
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
    onNext: () -> Unit,
    onRecoverLast: () -> Unit,
    onRecoverFromGallery: () -> Unit,
) {
    var isBlurred by remember { mutableStateOf(true) }
    var showRecoverMenu by remember { mutableStateOf(false) }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(240.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(Color.DarkGray),
    ) {
        val blurModifier = if (isBlurred) Modifier.blur(25.dp) else Modifier

        Box(modifier = Modifier.fillMaxSize().then(blurModifier)) {
            if (isGenerating && previewMode == "None") {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.align(Alignment.Center)) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Generating...", color = Color.LightGray, fontSize = 12.sp)
                }
            } else if (isGenerating && !livePreviewBase64.isNullOrEmpty()) {
                val previewBitmap by produceState<Bitmap?>(initialValue = null, livePreviewBase64) {
                    value =
                        withContext(Dispatchers.Default) {
                            try {
                                val bytes = Base64.decode(livePreviewBase64, Base64.DEFAULT)
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            } catch (_: Exception) {
                                null
                            }
                        }
                }
                previewBitmap?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Live Preview",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }
            } else if (isShowingGridPreview && sessionImages.size > batchStart && batchEnd >= batchStart) {
                val batchImages = sessionImages.subList(batchStart, batchEnd + 1)
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 80.dp),
                    modifier = Modifier.fillMaxSize().padding(bottom = 36.dp),
                    contentPadding = PaddingValues(4.dp),
                ) {
                    items(batchImages.size) { index: Int ->
                        val imgPath = batchImages[index]
                        AsyncImage(
                            model = imgPath,
                            contentDescription = null,
                            modifier =
                                Modifier
                                    .padding(2.dp)
                                    .aspectRatio(1f)
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable {
                                        onDismissGrid(batchStart + index)
                                        onFullscreen(batchStart + index)
                                    },
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
            } else if (currentSessionIndex >= 0 && sessionImages.isNotEmpty() && currentSessionIndex < sessionImages.size) {
                AsyncImage(
                    model = sessionImages[currentSessionIndex],
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().clickable { onFullscreen(currentSessionIndex) },
                    contentScale = ContentScale.Fit,
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.align(Alignment.Center)) {
                    Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color.Gray)
                    Text("No Preview", color = Color.Gray)
                }
            }
        }

        Box(modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
            IconButton(
                onClick = { showRecoverMenu = true },
                modifier =
                    Modifier
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        .size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.AutoFixHigh,
                    contentDescription = "Recover",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
            DropdownMenu(expanded = showRecoverMenu, onDismissRequest = { showRecoverMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Last Generated Image", fontSize = 14.sp) },
                    leadingIcon = { Icon(Icons.Default.Image, null, modifier = Modifier.size(20.dp)) },
                    onClick = {
                        showRecoverMenu = false
                        onRecoverLast()
                    },
                )
                DropdownMenuItem(
                    text = { Text("From Gallery Image", fontSize = 14.sp) },
                    leadingIcon = { Icon(Icons.Default.PhotoLibrary, null, modifier = Modifier.size(20.dp)) },
                    onClick = {
                        showRecoverMenu = false
                        onRecoverFromGallery()
                    },
                )
            }
        }

        IconButton(
            onClick = { isBlurred = !isBlurred },
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    .size(32.dp),
        ) {
            Icon(
                imageVector = if (isBlurred) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                contentDescription = "Toggle Blur",
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
        }

        Row(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            FilledTonalButton(
                onClick = onPrev,
                enabled = currentSessionIndex > batchStart,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.height(32.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null)
            }
            FilledTonalButton(
                onClick = onNext,
                enabled = currentSessionIndex < batchEnd,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.height(32.dp),
            ) {
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
    promptHistory: List<PromptHistoryItem>,
    navController: NavHostController,
) {
    var disabledPosTags by remember { mutableStateOf(emptySet<String>()) }
    var disabledNegTags by remember { mutableStateOf(emptySet<String>()) }

    val context = LocalContext.current

    SectionHeader("Prompts")

    PromptHistoryCarousel(
        history = promptHistory,
        onSelect = { item ->
            viewModel.updateState { it.copy(positivePrompt = item.positivePrompt, negativePrompt = item.negativePrompt) }
            disabledPosTags = emptySet()
            disabledNegTags = emptySet()
        },
    )

    HybridPromptEditor(
        prompt = state.positivePrompt,
        onPromptChange = {
            viewModel.updateState { s -> s.copy(positivePrompt = it) }
        },
        disabledTags = disabledPosTags,
        onDisabledTagsChange = { disabledPosTags = it },
        label = "Positive Prompt",
    )

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${countTokens(state.positivePrompt)} / 75", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        Row {
            TextButton(onClick = {
                val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboardManager.setPrimaryClip(ClipData.newPlainText("Prompt", state.positivePrompt))
                viewModel.showToast("Prompt Copied")
            }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp), modifier = Modifier.height(24.dp)) {
                Text("Copy Prompt", fontSize = 10.sp)
            }
            TextButton(onClick = {
                viewModel.updateState { s -> s.copy(positivePrompt = "") }
                disabledPosTags = emptySet()
            }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp), modifier = Modifier.height(24.dp)) {
                Text("Clear", fontSize = 10.sp)
            }
        }
    }

    Spacer(modifier = Modifier.height(4.dp))

    HybridPromptEditor(
        prompt = state.negativePrompt,
        onPromptChange = { viewModel.updateState { s -> s.copy(negativePrompt = it) } },
        disabledTags = disabledNegTags,
        onDisabledTagsChange = { disabledNegTags = it },
        label = "Negative Prompt",
    )

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${countTokens(state.negativePrompt)} / 75", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        TextButton(onClick = {
            viewModel.resetToDefaults()
            disabledPosTags = emptySet()
            disabledNegTags = emptySet()
        }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp), modifier = Modifier.height(24.dp)) {
            Text("Reset to Defaults", fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
fun AppForgeSlider(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    decimals: Int,
    onValueChange: (Float) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f), fontSize = 12.sp)
        Text(String.format(java.util.Locale.US, "%.${decimals}f", value), fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.weight(2f),
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
    upscalers: List<String>,
) {
    SectionHeader("Settings")

    Column(modifier = Modifier.padding(horizontal = 12.dp)) {
        var modelExpanded by remember { mutableStateOf(false) }
        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            OutlinedButton(
                onClick = { modelExpanded = true },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                contentPadding = PaddingValues(8.dp),
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val currentModelResource = models.find { it.name == selectedModel || it.title == selectedModel }
                    if (currentModelResource != null) {
                        AsyncImage(
                            model =
                                ImageRequest
                                    .Builder(LocalContext.current)
                                    .data(viewModel.getPreviewUrl(currentModelResource.path, isLora = false))
                                    .crossfade(true)
                                    .build(),
                            contentDescription = null,
                            modifier =
                                Modifier
                                    .size(32.dp)
                                    .padding(end = 8.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.DarkGray),
                            contentScale = ContentScale.Crop,
                        )
                    }
                    Text(
                        "Model: ${currentModelResource?.title ?: selectedModel.ifEmpty { "Loading..." }}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 12.sp,
                    )
                }
            }
            DropdownMenu(
                expanded = modelExpanded,
                onDismissRequest = { modelExpanded = false },
                modifier = Modifier.heightIn(max = 350.dp),
            ) {
                models.forEach { mod ->
                    val isSelected = mod.name == selectedModel || mod.title == selectedModel
                    DropdownMenuItem(
                        modifier =
                            if (isSelected) {
                                Modifier.background(
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                                )
                            } else {
                                Modifier
                            },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AsyncImage(
                                    model =
                                        ImageRequest
                                            .Builder(LocalContext.current)
                                            .data(viewModel.getPreviewUrl(mod.path, isLora = false))
                                            .crossfade(true)
                                            .build(),
                                    contentDescription = null,
                                    modifier =
                                        Modifier
                                            .size(
                                                40.dp,
                                            ).padding(end = 8.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color.DarkGray),
                                    contentScale = ContentScale.Crop,
                                )
                                Text(
                                    text = mod.title,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        },
                        onClick = {
                            viewModel.changeCheckpoint(mod.name)
                            modelExpanded = false
                        },
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
                modifier = Modifier.weight(1f),
            )

            var seedExpanded by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { seedExpanded = true }, modifier = Modifier.padding(start = 8.dp)) {
                    Icon(Icons.Default.Casino, contentDescription = "Seed Options")
                }
                DropdownMenu(expanded = seedExpanded, onDismissRequest = { seedExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Randomize (-1)", fontSize = 14.sp) },
                        onClick = {
                            viewModel.updateState { s -> s.copy(seed = -1L) }
                            seedExpanded = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Recover Last Seed", fontSize = 14.sp) },
                        onClick = {
                            viewModel.recoverLastSeed()
                            seedExpanded = false
                        },
                    )
                }
            }
        }

        AppForgeSlider(
            "Batch Count",
            state.batchCount.toFloat(),
            1f..100f,
            0,
        ) { value: Float -> viewModel.updateState { s -> s.copy(batchCount = value.toInt()) } }
        AppForgeSlider("Batch Size", state.batchSize.toFloat(), 1f..16f, 0) { value: Float ->
            viewModel.updateState { s ->
                s.copy(batchSize = value.toInt())
            }
        }
        AppForgeSlider("Steps", state.steps.toFloat(), 1f..100f, 0) { value: Float ->
            viewModel.updateState { s ->
                s.copy(steps = value.toInt())
            }
        }
        AppForgeSlider("CFG Scale", state.cfgScale, 1f..20f, 1) { value: Float -> viewModel.updateState { s -> s.copy(cfgScale = value) } }
        AppForgeSlider("Clip Skip", state.clipSkip.toFloat(), 1f..3f, 0) { value: Float ->
            viewModel.updateState { s ->
                s.copy(clipSkip = value.toInt())
            }
        }

        Text("Aspect Ratio", fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val aspectRatios = listOf("Custom", "1:1", "4:3", "3:4", "16:9", "9:16")
            aspectRatios.forEach { ratio ->
                val isSelected = state.aspectRatio == ratio
                Surface(
                    modifier =
                        Modifier.clickable {
                            viewModel.updateState { s ->
                                val (w, h) =
                                    when (ratio) {
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
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        text = ratio,
                        fontSize = 12.sp,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }

        AppForgeSlider("Width", state.width.toFloat(), 256f..2048f, 0) { value: Float ->
            viewModel.updateState { s ->
                s.copy(
                    width =
                        (value.toInt() / 64) * 64,
                    aspectRatio = "Custom",
                )
            }
        }
        AppForgeSlider("Height", state.height.toFloat(), 256f..2048f, 0) { value: Float ->
            viewModel.updateState { s ->
                s.copy(
                    height =
                        (value.toInt() / 64) * 64,
                    aspectRatio = "Custom",
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            var samplerExpanded by remember { mutableStateOf(false) }
            Box(modifier = Modifier.weight(1f)) {
                OutlinedButton(onClick = {
                    samplerExpanded = true
                }, modifier = Modifier.fillMaxWidth().height(36.dp), contentPadding = PaddingValues(4.dp)) {
                    Text(state.sampler, maxLines = 1, fontSize = 11.sp, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(
                    expanded = samplerExpanded,
                    onDismissRequest = { samplerExpanded = false },
                    modifier = Modifier.heightIn(max = 350.dp),
                ) {
                    samplers.forEach { samp ->
                        val isSelected = samp == state.sampler
                        DropdownMenuItem(
                            modifier =
                                if (isSelected) {
                                    Modifier.background(
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                                    )
                                } else {
                                    Modifier
                                },
                            text = {
                                Text(
                                    samp,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            onClick = {
                                viewModel.updateState { s -> s.copy(sampler = samp) }
                                samplerExpanded = false
                            },
                        )
                    }
                }
            }

            var schedulerExpanded by remember { mutableStateOf(false) }
            Box(modifier = Modifier.weight(1f)) {
                OutlinedButton(onClick = {
                    schedulerExpanded = true
                }, modifier = Modifier.fillMaxWidth().height(36.dp), contentPadding = PaddingValues(4.dp)) {
                    Text(state.scheduler, maxLines = 1, fontSize = 11.sp, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(
                    expanded = schedulerExpanded,
                    onDismissRequest = { schedulerExpanded = false },
                    modifier = Modifier.heightIn(max = 350.dp),
                ) {
                    schedulers.forEach { sched ->
                        val isSelected = sched == state.scheduler
                        DropdownMenuItem(
                            modifier =
                                if (isSelected) {
                                    Modifier.background(
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                                    )
                                } else {
                                    Modifier
                                },
                            text = {
                                Text(
                                    sched,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            onClick = {
                                viewModel.updateState { s -> s.copy(scheduler = sched) }
                                schedulerExpanded = false
                            },
                        )
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            HorizontalDivider(modifier = Modifier.weight(1f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp).clickable { viewModel.updateState { it.copy(hiresFix = !it.hiresFix) } },
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
                    OutlinedButton(onClick = {
                        upscalerExpanded = true
                    }, modifier = Modifier.fillMaxWidth().height(36.dp), contentPadding = PaddingValues(4.dp)) {
                        Text("Upscaler: ${state.upscaler}", maxLines = 1, fontSize = 11.sp, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(
                        expanded = upscalerExpanded,
                        onDismissRequest = { upscalerExpanded = false },
                        modifier = Modifier.heightIn(max = 350.dp),
                    ) {
                        upscalers.forEach { upsc ->
                            val isSelected = upsc == state.upscaler
                            DropdownMenuItem(
                                modifier =
                                    if (isSelected) {
                                        Modifier.background(
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                                        )
                                    } else {
                                        Modifier
                                    },
                                text = {
                                    Text(
                                        upsc,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    )
                                },
                                onClick = {
                                    viewModel.updateState { s -> s.copy(upscaler = upsc) }
                                    upscalerExpanded = false
                                },
                            )
                        }
                    }
                }
                AppForgeSlider("Hires Scale", state.hiresScale, 1f..4f, 2) { value: Float ->
                    viewModel.updateState { s ->
                        s.copy(hiresScale = value)
                    }
                }
                AppForgeSlider("Denoising", state.denoising, 0f..1f, 2) { value: Float ->
                    viewModel.updateState { s ->
                        s.copy(denoising = value)
                    }
                }
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
    onOpenTagsPopup: (String, String) -> Unit,
) {
    SectionHeader("LoRAs")

    Column(modifier = Modifier.padding(horizontal = 12.dp)) {
        var loraExpanded by remember { mutableStateOf(false) }
        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            OutlinedButton(
                onClick = { loraExpanded = true },
                modifier = Modifier.fillMaxWidth().height(36.dp),
                contentPadding = PaddingValues(4.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Add LoRA...", fontSize = 12.sp)
            }
            DropdownMenu(expanded = loraExpanded, onDismissRequest = { loraExpanded = false }, modifier = Modifier.heightIn(max = 350.dp)) {
                availableLoras.forEach { loraData ->
                    val isSelected = activeLoras.any { it.name == loraData.name }
                    DropdownMenuItem(
                        modifier =
                            if (isSelected) {
                                Modifier.background(
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                                )
                            } else {
                                Modifier
                            },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AsyncImage(
                                    model =
                                        ImageRequest
                                            .Builder(LocalContext.current)
                                            .data(viewModel.getPreviewUrl(loraData.path, isLora = true))
                                            .crossfade(true)
                                            .build(),
                                    contentDescription = null,
                                    modifier =
                                        Modifier
                                            .size(
                                                40.dp,
                                            ).padding(end = 8.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color.DarkGray),
                                    contentScale = ContentScale.Crop,
                                )
                                Text(
                                    text = loraData.title,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        },
                        onClick = {
                            onPendingLora(loraData)
                            loraExpanded = false
                        },
                    )
                }
            }
        }

        activeLoras.forEach { lora ->
            val loraResource = availableLoras.find { it.name == lora.name }
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (loraResource != null) {
                        AsyncImage(
                            model =
                                ImageRequest
                                    .Builder(LocalContext.current)
                                    .data(viewModel.getPreviewUrl(loraResource.path, isLora = true))
                                    .crossfade(true)
                                    .build(),
                            contentDescription = null,
                            modifier =
                                Modifier
                                    .size(50.dp)
                                    .padding(end = 8.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.DarkGray),
                            contentScale = ContentScale.Crop,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = loraResource?.title ?: lora.name,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = {
                                        val hash = loraResource?.hash
                                        if (hash != null) {
                                            onOpenTagsPopup(hash, lora.name)
                                        } else {
                                            viewModel.showToast("Brak metadanych modelu. Odśwież API.")
                                        }
                                    },
                                    modifier = Modifier.size(24.dp),
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
                            modifier = Modifier.height(24.dp),
                        )
                    }
                }
            }
        }
    }
}

// Kompaktowy panel dedykowany dla Bottom Sheet
@Composable
fun BottomControlsSection(
    viewModel: ForgeViewModel,
    state: AppState,
    generationQueueSize: Int,
    isActivelyGenerating: Boolean,
    progress: Float,
    currentEta: Double,
    onQueueClick: () -> Unit,
    onNavigateToPresets: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .navigationBarsPadding() // Zabezpieczenie przed nachodzeniem na systemowe przyciski (np. wstecz, home)
                .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        // Górny rząd: Checkboxy do zarządzania miejscem zapisu
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier =
                    Modifier
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), MaterialTheme.shapes.small)
                        .clip(MaterialTheme.shapes.small)
                        .clickable { viewModel.updateState { it.copy(saveImages = !state.saveImages) } }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (state.saveImages) Icons.Default.CloudDone else Icons.Default.CloudOff,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint =
                            if (state.saveImages) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(
                                    alpha = 0.6f,
                                )
                            },
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Save Server", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                Checkbox(
                    checked = state.saveImages,
                    onCheckedChange = { isChecked -> viewModel.updateState { it.copy(saveImages = isChecked) } },
                    modifier = Modifier.size(20.dp),
                )
            }

            Row(
                modifier =
                    Modifier
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), MaterialTheme.shapes.small)
                        .clip(MaterialTheme.shapes.small)
                        .clickable { viewModel.updateState { it.copy(saveToDevice = !state.saveToDevice) } }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Save,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint =
                            if (state.saveToDevice) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(
                                    alpha = 0.6f,
                                )
                            },
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Save Device", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                Checkbox(
                    checked = state.saveToDevice,
                    onCheckedChange = { isChecked -> viewModel.updateState { it.copy(saveToDevice = isChecked) } },
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        // Dolny rząd: Akcje główne (Queue, Interrupt, Add)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onQueueClick,
                modifier = Modifier.height(54.dp).weight(0.35f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(0.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.List, null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("$generationQueueSize", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }

            if (isActivelyGenerating) {
                Button(
                    onClick = { viewModel.interruptGeneration() },
                    modifier = Modifier.height(54.dp).weight(0.25f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Icon(Icons.Default.Stop, contentDescription = "Interrupt", tint = MaterialTheme.colorScheme.onError)
                }
            }

            Box(
                modifier =
                    Modifier
                        .weight(if (isActivelyGenerating) 0.75f else 1.2f)
                        .height(54.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isActivelyGenerating) Color.DarkGray else MaterialTheme.colorScheme.primary)
                        .clickable(onClick = { viewModel.queueGeneration() }),
            ) {
                if (isActivelyGenerating) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progress.coerceIn(0f, 1f))
                                .background(MaterialTheme.colorScheme.primary),
                    )
                }
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (isActivelyGenerating) {
                        Text(
                            text = "ADD TO QUEUE • ${(progress * 100).toInt()}%",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            style = TextStyle(shadow = Shadow(color = Color.Black.copy(alpha = 0.8f), blurRadius = 4f)),
                        )
                        Text(
                            text = "ETA: ${String.format(Locale.US, "%.1f", currentEta)}s",
                            fontSize = 9.sp,
                            color = Color.LightGray,
                            style = TextStyle(shadow = Shadow(color = Color.Black.copy(alpha = 0.8f), blurRadius = 4f)),
                        )
                    } else {
                        Text("ADD TO QUEUE", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Dolny rząd dodatkowych akcji
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { onNavigateToPresets() },
                modifier = Modifier.height(40.dp).weight(1f),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(0.dp),
            ) {
                Text("Presets", fontSize = 12.sp)
            }
            Button(
                onClick = { viewModel.recoverLastPrompt() },
                modifier = Modifier.height(40.dp).weight(1f),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(0.dp),
            ) {
                Text("Restore Last", fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { viewModel.refreshCheckpoints() },
                modifier = Modifier.height(40.dp).weight(1f),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
            ) {
                Text("Check Checkpoints", fontSize = 12.sp)
            }
            Button(
                onClick = { viewModel.refreshLoras() },
                modifier = Modifier.height(40.dp).weight(1f),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
            ) {
                Text("Check Loras", fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun LoraTriggerDialog(
    viewModel: ForgeViewModel,
    state: AppState,
    lora: ApiResource,
    onDismiss: () -> Unit,
) {
    var triggerWords by remember(lora) { mutableStateOf<List<String>?>(null) }
    var selectedWords by remember { mutableStateOf(emptySet<String>()) }
    var originalPrompt by remember(lora) { mutableStateOf(state.positivePrompt) }

    LaunchedEffect(lora) {
        triggerWords =
            if (lora.hash != null) {
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
                        Text(
                            "Click tags to add/remove them from your prompt:",
                            fontSize = 12.sp,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        LazyColumn(
                            modifier =
                                Modifier
                                    .heightIn(
                                        max = 250.dp,
                                    ).fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small),
                        ) {
                            items(triggerWords!!) { word: String ->
                                val isSelected = selectedWords.contains(word)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                selectedWords = if (isSelected) selectedWords.minus(word) else selectedWords.plus(word)
                                            }.padding(horizontal = 8.dp, vertical = 6.dp),
                                ) {
                                    Checkbox(checked = isSelected, onCheckedChange = null)
                                    Text(word, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
                                }
                            }
                        }
                    } else {
                        Text("This LoRA has no trigger words on Civitai. Do you still want to add it?", fontSize = 14.sp)
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
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FullscreenImageViewer(
    viewModel: ForgeViewModel,
    config: AppConfig,
    sessionImages: List<String>,
    initialIndex: Int,
    onDismiss: () -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { sessionImages.size })
    val currentFile = sessionImages.getOrNull(pagerState.currentPage) ?: ""
    val context = LocalContext.current

    LaunchedEffect(pagerState.currentPage) {
        if (currentFile.isNotEmpty()) {
            // Czyszczenie starych metadanych, ponieważ funkcja dla plików lokalnych została usunięta
            viewModel.loadMetadataForImage(null)
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            Row(
                modifier = Modifier.fillMaxWidth().background(Color(0x88000000)).padding(vertical = 4.dp, horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White) }
                Text(
                    File(currentFile).name,
                    color = Color.White,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )

                IconButton(onClick = {
                    viewModel.shareSessionImage(currentFile) { intent ->
                        context.startActivity(intent)
                    }
                }) {
                    Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.White)
                }

                val showMetadata by viewModel.showGalleryMetadata.collectAsStateWithLifecycle()
                IconButton(onClick = { viewModel.toggleGalleryMetadata() }) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = "Info",
                        tint = Color.White,
                        modifier =
                            Modifier.then(
                                if (showMetadata) Modifier.background(Color(0x55FFFFFF), CircleShape).padding(2.dp) else Modifier,
                            ),
                    )
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
                            alignment = Alignment.TopCenter,
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
                                alignment = Alignment.TopCenter,
                            )
                        }
                    }
                }

                val showMetadata by viewModel.showGalleryMetadata.collectAsStateWithLifecycle()
                val currentMetadata by viewModel.currentImageMetadata.collectAsStateWithLifecycle()

                if (showMetadata) {
                    val fileInfo =
                        remember(currentFile) {
                            val file = File(currentFile)
                            if (file.exists()) {
                                val sizeKb = file.length() / 1024
                                "${file.name} • $sizeKb KB"
                            } else {
                                file.name
                            }
                        }

                    AppMetadataAlertDialog(
                        metadata = currentMetadata,
                        fileInfo = fileInfo,
                        onDismiss = { viewModel.toggleGalleryMetadata() },
                        onApplyAll = null,
                        onApplyPrompt = { pos: String, neg: String ->
                            viewModel.updateState {
                                it.copy(
                                    positivePrompt = pos,
                                    negativePrompt = neg,
                                )
                            }
                            viewModel.showToast("Prompts Applied")
                        },
                        onApplyModel = { modelName: String ->
                            viewModel.changeCheckpoint(modelName)
                            viewModel.showToast("Model Applied: $modelName")
                        },
                        onApplyLoras = { loraList: List<String> ->
                            loraList.forEach { loraTag: String ->
                                val loraName = loraTag.substringAfter("<lora:").substringBefore(":")
                                if (loraName.isNotEmpty()) viewModel.appendLora(loraName)
                            }
                            viewModel.showToast("LoRAs Applied")
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppMetadataAlertDialog(
    metadata: String?,
    fileInfo: String? = null,
    onDismiss: () -> Unit,
    onApplyPrompt: (String, String) -> Unit,
    onApplyModel: (String) -> Unit,
    onApplyLoras: (List<String>) -> Unit,
    onApplyAll: (() -> Unit)? = null,
) {
    var posPrompt = ""
    var negPrompt = ""
    var modelName = ""
    val loras = mutableListOf<String>()

    if (metadata != null &&
        !metadata.startsWith("Loading") &&
        !metadata.startsWith("Failed") &&
        !metadata.startsWith("Invalid") &&
        !metadata.startsWith("Server")
    ) {
        val lines = metadata.split("\n")
        var currentMode = 0
        for (line in lines) {
            if (line.startsWith("Negative prompt:")) {
                currentMode = 1
                negPrompt += line.substringAfter("Negative prompt:").trim() + "\n"
            } else if (line.startsWith("Steps:")) {
                currentMode = 2
                val params = line.split(",")
                params.forEach { p ->
                    val kv = p.split(":")
                    if (kv.size >= 2 && kv[0].trim() == "Model") {
                        modelName = kv[1].trim()
                    }
                }
            } else {
                if (currentMode == 0) {
                    posPrompt += line + "\n"
                } else if (currentMode == 1) {
                    negPrompt += line + "\n"
                }
            }
        }
        posPrompt = posPrompt.trim()
        negPrompt = negPrompt.trim()

        PromptParser.LORA.findAll(posPrompt).forEach { match ->
            loras.add(match.value)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Generation Data",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier =
                        Modifier.padding(
                            bottom =
                                if (fileInfo !=
                                    null
                                ) {
                                    4.dp
                                } else {
                                    8.dp
                                },
                        ),
                )

                if (fileInfo != null) {
                    Text(fileInfo, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp))
                }

                Box(
                    modifier =
                        Modifier
                            .weight(
                                1f,
                                fill = false,
                            ).background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                            .padding(8.dp),
                ) {
                    Text(
                        text = metadata ?: "Loading...",
                        fontSize = 11.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                }

                if (metadata != null && posPrompt.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Apply to current session:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))

                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (onApplyAll != null) {
                            Button(
                                onClick = onApplyAll,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp),
                            ) {
                                Text("Apply All", fontSize = 12.sp)
                            }
                        }

                        OutlinedButton(onClick = {
                            onApplyPrompt(posPrompt, negPrompt)
                        }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), modifier = Modifier.height(32.dp)) {
                            Text("Prompt", fontSize = 12.sp)
                        }

                        if (modelName.isNotEmpty()) {
                            OutlinedButton(onClick = {
                                onApplyModel(modelName)
                            }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), modifier = Modifier.height(32.dp)) {
                                Text("Model", fontSize = 12.sp)
                            }
                        }

                        if (loras.isNotEmpty()) {
                            OutlinedButton(onClick = {
                                onApplyLoras(loras)
                            }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), modifier = Modifier.height(32.dp)) {
                                Text("LoRAs (${loras.size})", fontSize = 12.sp)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Close")
                }
            }
        }
    }
}
