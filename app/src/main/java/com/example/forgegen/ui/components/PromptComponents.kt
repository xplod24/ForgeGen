package com.example.forgegen

import com.example.forgegen.ui.components.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
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
import coil.compose.AsyncImage
import coil.request.ImageRequest
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

// Undo keeps this many steps; typing without a pause this long is one step.
private const val UNDO_STEPS = 100
private const val UNDO_GROUP_MS = 800L

/**
 * A prompt field without a frame, for the cards of the main screen (3.0.0): the text, and under it a footer with [info]
 * on the left (the token count), then Undo, Redo and [actions] (Copy, Clear).
 */
@Composable
fun UndoRedoTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    // A prompt field: no autocorrect (Gboard split "1girl"), and it tells the tag suggestions what is typed (2.4.2).
    typing: PromptTyping? = null,
    info: @Composable RowScope.() -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    var history by remember { mutableStateOf(listOf(value)) }
    var historyIndex by remember { mutableIntStateOf(0) }
    // When the last step was typed (0 when it came from elsewhere or from Undo/Redo): typing within a moment of it
    // replaces that step instead of adding one per character, and the history keeps a limited number of steps.
    var lastTypedAt by remember { mutableLongStateOf(0L) }

    fun record(
        newValue: String,
        typed: Boolean,
    ) {
        val base = history.take(historyIndex + 1)
        val now = System.currentTimeMillis()
        val merge = typed && base.size > 1 && now - lastTypedAt < UNDO_GROUP_MS
        history = ((if (merge) base.dropLast(1) else base) + newValue).takeLast(UNDO_STEPS)
        historyIndex = history.lastIndex
        lastTypedAt = if (typed) now else 0L
    }

    LaunchedEffect(value) {
        if (history.isEmpty() || history[historyIndex] != value) record(value, typed = false)
    }

    // The field keeps its caret (the tag suggestions need it); the text is always [value], so a change from elsewhere
    // (Undo, Clear, the tags row) shows at once and the caret stays where it can, as in the plain text field before.
    var fieldState by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    val fieldValue = if (fieldState.text == value) fieldState else fieldState.copy(text = value)
    val currentValue by rememberUpdatedState(value)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val typingKey = remember { Any() }
    var isFocused by remember { mutableStateOf(false) }
    // A suggestion tapped in the strip: its own Undo step.
    val applyEdit: (TextFieldValue) -> Unit =
        remember {
            { edited ->
                fieldState = edited
                if (edited.text != currentValue) {
                    record(edited.text, typed = false)
                    currentOnValueChange(edited.text)
                }
            }
        }
    if (typing != null) {
        SideEffect { if (isFocused) typing.changed(typingKey, fieldValue) }
        DisposableEffect(typing) { onDispose { typing.left(typingKey) } }
    }

    val textStyle = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onSurface)
    Column(modifier = modifier) {
        BasicTextField(
            value = fieldValue,
            onValueChange = {
                fieldState = it
                if (it.text != value) {
                    record(it.text, typed = true)
                    onValueChange(it.text)
                }
            },
            textStyle = textStyle,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            minLines = minLines,
            maxLines = maxLines,
            visualTransformation = visualTransformation,
            keyboardOptions =
                if (typing != null) {
                    KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false)
                } else {
                    KeyboardOptions.Default
                },
            modifier =
                Modifier.fillMaxWidth().padding(end = 8.dp).onFocusChanged { focus ->
                    isFocused = focus.isFocused
                    if (typing != null) {
                        if (focus.isFocused) typing.focused(typingKey, fieldValue, applyEdit) else typing.left(typingKey)
                    }
                },
            decorationBox = { field ->
                Box {
                    if (value.isEmpty()) Text(placeholder, style = textStyle.copy(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)))
                    field()
                }
            },
        )

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, content = info)
            FieldIconButton(Icons.AutoMirrored.Filled.Undo, "Undo", enabled = historyIndex > 0) {
                if (historyIndex > 0) {
                    historyIndex--
                    lastTypedAt = 0L
                    onValueChange(history[historyIndex])
                }
            }
            FieldIconButton(Icons.AutoMirrored.Filled.Redo, "Redo", enabled = historyIndex < history.size - 1) {
                if (historyIndex < history.size - 1) {
                    historyIndex++
                    lastTypedAt = 0L
                    onValueChange(history[historyIndex])
                }
            }
            actions()
        }
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
    placeholder: String,
    showTagEditor: Boolean = true,
    modifier: Modifier = Modifier,
    // The footer's buttons after Undo and Redo (Copy, Clear).
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier = modifier.fillMaxWidth()) {
        UndoRedoTextField(
            value = prompt,
            onValueChange = onPromptChange,
            placeholder = placeholder,
            minLines = 3,
            maxLines = 8,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PromptHighlighting,
            typing = LocalPromptTyping.current,
            info = { TokenCount(prompt) },
            actions = actions,
        )

        // Split tags respecting nesting using a custom Tokenizer
        val activeTags = remember(prompt) { parseTags(prompt) }

        // "Show Active Tags UI" in the settings hides the row (the setting used to change nothing).
        if (showTagEditor && (activeTags.isNotEmpty() || disabledTags.isNotEmpty())) {
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

                    // Temporary mutable list during dragging
                    var displayTags by remember(activeTags) { mutableStateOf(activeTags.toList()) }
                    var editingTagWeight by remember { mutableStateOf<String?>(null) }

                    val dashColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    // BUG FIX (Explicit type defined and correct dashPathEffect method)
                    val dashEffect: PathEffect = remember { PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f) }

                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        displayTags.forEachIndexed { index, tag ->
                            val isGhost = index == draggingIndex

                            // Offset modifier only works for the floating layer
                            val flyingModifier =
                                if (isGhost) {
                                    Modifier
                                        .offset { IntOffset(dragOffsetX.roundToInt(), dragOffsetY.roundToInt()) }
                                        .zIndex(2f)
                                        .shadow(8.dp, RoundedCornerShape(8.dp))
                                } else {
                                    Modifier.zIndex(1f)
                                }

                            val (baseName, weightStr) = splitTagWeight(tag)

                            Box {
                                // GHOST - Empty space where the tag will land (rendered only in the original slot)
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

                                // CHIP - Normal or "flying" chip
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer,
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
                                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            baseName,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
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
                            val baseName = splitTagWeight(tag).base

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
                        val (baseName, weightStr) = splitTagWeight(currentEditingTag)

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
                                            // The slider yields values like 0.99999994, so an exact == 1.0f check almost never matched.
                                            val newTag = withTagWeight(baseName, sliderValue)
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

/* ============================================================================
 * EXPORTED UI SECTIONS
 * ============================================================================ */

// The image preview's height on the main screen (folded away while typing leaves no room, TypingLayout).
val PREVIEW_HEIGHT = 220.dp

@Composable
fun PreviewSection(
    isGenerating: Boolean,
    livePreview: LivePreview?,
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
    val quiet = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(PREVIEW_HEIGHT)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surface),
    ) {
        val blurModifier = if (isBlurred) Modifier.blur(25.dp) else Modifier

        Box(modifier = Modifier.fillMaxSize().then(blurModifier)) {
            if (isGenerating && livePreview == null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.align(Alignment.Center)) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Generating...", color = quiet, fontSize = 12.sp)
                }
            } else if (isGenerating && livePreview != null) {
                // Decoded no bigger than this box (the server may send the preview in full size); the previous
                // image stays until the next one is ready, so the preview does not flicker.
                val boxPx = with(LocalDensity.current) { PREVIEW_HEIGHT.roundToPx() }
                var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
                LaunchedEffect(livePreview) {
                    val preview = livePreview
                    previewBitmap =
                        withContext(Dispatchers.Default) {
                            try {
                                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeByteArray(preview.bytes, 0, preview.bytes.size, bounds)
                                var sample = 1
                                while (bounds.outHeight / (sample * 2) >= boxPx && bounds.outWidth / (sample * 2) >= boxPx) sample *= 2
                                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                                BitmapFactory.decodeByteArray(preview.bytes, 0, preview.bytes.size, options)
                            } catch (_: Exception) {
                                null
                            }
                        } ?: previewBitmap
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
                    modifier = Modifier.fillMaxSize().padding(bottom = 44.dp),
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
                    Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(44.dp), tint = quiet)
                    Spacer(Modifier.height(6.dp))
                    Text("No Preview", color = quiet, fontSize = 13.sp)
                }
            }
        }

        Box(modifier = Modifier.align(Alignment.TopStart).padding(10.dp)) {
            PreviewButton(Icons.Default.AutoFixHigh, "Recover") { showRecoverMenu = true }
            DropdownMenu(expanded = showRecoverMenu, onDismissRequest = { showRecoverMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Last Generated Image") },
                    leadingIcon = { Icon(Icons.Default.Image, null, modifier = Modifier.size(20.dp)) },
                    onClick = {
                        showRecoverMenu = false
                        onRecoverLast()
                    },
                )
                DropdownMenuItem(
                    text = { Text("From Gallery Image") },
                    leadingIcon = { Icon(Icons.Default.PhotoLibrary, null, modifier = Modifier.size(20.dp)) },
                    onClick = {
                        showRecoverMenu = false
                        onRecoverFromGallery()
                    },
                )
            }
        }

        Box(modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)) {
            PreviewButton(if (isBlurred) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Toggle Blur") { isBlurred = !isBlurred }
        }

        // The images of the last batch: back, where we are, forward.
        if (batchEnd > batchStart && currentSessionIndex >= batchStart) {
            Row(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 10.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onPrev, enabled = currentSessionIndex > batchStart, modifier = Modifier.size(width = 36.dp, height = 28.dp)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous", tint = Color.White)
                }
                Text(
                    "${(currentSessionIndex - batchStart + 1).coerceAtMost(batchEnd - batchStart + 1)} / ${batchEnd - batchStart + 1}",
                    fontSize = 12.sp,
                    color = Color(0xFFD0D0D0),
                )
                IconButton(onClick = onNext, enabled = currentSessionIndex < batchEnd, modifier = Modifier.size(width = 36.dp, height = 28.dp)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next", tint = Color.White)
                }
            }
        }
    }
}

/** A round button over the preview (it stays readable on any image). */
@Composable
private fun PreviewButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(36.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape),
    ) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(18.dp))
    }
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
            viewModel.loadMetadataForLocalFile(currentFile)
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
                    val zoomSize = zoomableImageSizePx()
                    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                        // Pinch or double tap to zoom; decoded big enough to stay sharp when zoomed.
                        AsyncImage(
                            model =
                                ImageRequest
                                    .Builder(LocalContext.current)
                                    .data(sessionImages[page])
                                    .size(zoomSize)
                                    .build(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize().zoomable(sessionImages[page], enabled = config.pinchToZoom),
                            contentScale = ContentScale.Fit,
                        )
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        val item = sessionImages.getOrNull(pagerState.currentPage)
                        if (item != null) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current).data(item).size(zoomableImageSizePx()).build(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth().zoomable(item, enabled = config.pinchToZoom),
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
        val info = remember(metadata) { Infotext.parse(metadata) }
        posPrompt = info.positivePrompt
        negPrompt = info.negativePrompt
        modelName = info.model

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
