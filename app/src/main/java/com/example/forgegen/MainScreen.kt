package com.yourname.forgegen

import android.content.Context
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
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
import coil.compose.LocalImageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import com.yourname.forgegen.Translator.t

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
        Regex("\\[[^]]+\\]").findAll(str).forEach { match ->
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
    onClear: () -> Unit,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    actions: @Composable RowScope.() -> Unit = {}
) {
    var history by remember { mutableStateOf(listOf(value)) }
    var historyIndex by remember { mutableStateOf(0) }

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

fun getTagStrength(tag: String): String {
    val trimmed = tag.trim()
    val pattern = java.util.regex.Pattern.compile("^\\((.*):([0-9.]+)\\)$")
    val matcher = pattern.matcher(trimmed)
    if (matcher.find() && matcher.groupCount() >= 2) {
        return matcher.group(2) ?: "1.0"
    }
    return "1.0"
}

fun adjustTagStrength(tag: String, delta: Float): String {
    val trimmed = tag.trim()
    val pattern = java.util.regex.Pattern.compile("^\\((.*):([0-9.]+)\\)$")
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DragToReorderTagsRow(text: String, onPromptChanged: (String) -> Unit) {
    val tags = text.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    if (tags.isNotEmpty()) {
        var draggingIndex by remember { mutableStateOf<Int?>(null) }
        var tunedIndex by remember { mutableStateOf<Int?>(null) }

        var dragOffsetX by remember { mutableStateOf(0f) }
        var dragOffsetY by remember { mutableStateOf(0f) }

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
                                Icons.Default.Remove, "Decrease".t,
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
                                Icons.Default.Add, "Increase".t,
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
                                contentDescription = "Remove".t,
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
        Text("No active tags".t, color = Color.Gray, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(viewModel: ForgeViewModel, navController: NavHostController) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val state by viewModel.appState.collectAsStateWithLifecycle()
    val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()
    val pingMs by viewModel.pingMs.collectAsStateWithLifecycle()
    val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val currentEta by ForgeState.currentEta.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val vram by ForgeState.vramUsage.collectAsStateWithLifecycle()

    val isServerBusy by ForgeState.isServerBusy.collectAsStateWithLifecycle()
    val generationQueue by ForgeState.generationQueue.collectAsStateWithLifecycle()

    val sessionImages by viewModel.sessionImages.collectAsStateWithLifecycle()
    val currentSessionIndex by viewModel.currentSessionIndex.collectAsStateWithLifecycle()
    val livePreviewBase64 by ForgeState.livePreviewImage.collectAsStateWithLifecycle()
    val isShowingGridPreview by ForgeState.isShowingGridPreview.collectAsStateWithLifecycle()

    val batchStart by ForgeState.currentBatchStartIndex.collectAsStateWithLifecycle()
    val batchEnd by ForgeState.currentBatchEndIndex.collectAsStateWithLifecycle()

    val currentJobNo by ForgeState.currentJobNo.collectAsStateWithLifecycle()
    val currentJobCount by ForgeState.currentJobCount.collectAsStateWithLifecycle()
    val currentSamplingStep by ForgeState.currentSamplingStep.collectAsStateWithLifecycle()
    val currentSamplingSteps by ForgeState.currentSamplingSteps.collectAsStateWithLifecycle()

    val models by viewModel.models.collectAsStateWithLifecycle()
    val selectedModel by viewModel.selectedModel.collectAsStateWithLifecycle()
    val samplers by viewModel.samplers.collectAsStateWithLifecycle()
    val schedulers by viewModel.schedulers.collectAsStateWithLifecycle()
    val availableLoras by viewModel.availableLoras.collectAsStateWithLifecycle()
    val activeLoras by viewModel.activeLoras.collectAsStateWithLifecycle()
    val upscalers by viewModel.upscalers.collectAsStateWithLifecycle()

    val tagSuggestions by viewModel.tagSuggestions.collectAsStateWithLifecycle()
    val isRestoringPrompt by viewModel.isRestoringPrompt.collectAsStateWithLifecycle()
    val promptHistory by viewModel.promptHistory.collectAsStateWithLifecycle()

    var showOverflowMenu by remember { mutableStateOf(false) }
    var showHistoryDialog by remember { mutableStateOf(false) }
    var showRecoverMenu by remember { mutableStateOf(false) }
    var showPresetsDialog by remember { mutableStateOf(false) }
    var fullscreenImageIndex by remember { mutableStateOf(-1) }

    var pendingLora by remember { mutableStateOf<ApiResource?>(null) }

    var showPosTagEditor by remember { mutableStateOf(false) }
    var showNegTagEditor by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val imageLoader = LocalImageLoader.current

    LaunchedEffect(selectedModel, activeLoras, models, availableLoras) {
        launch(Dispatchers.IO) {
            val currentModelResource = models.find { it.title == selectedModel }
            if (currentModelResource != null) {
                val url = viewModel.getPreviewUrl(currentModelResource.path, isLora = false)
                if (url.isNotEmpty()) {
                    val request = ImageRequest.Builder(context)
                        .data(url)
                        .size(150)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .build()
                    imageLoader.enqueue(request)
                }
            }

            activeLoras.forEach { activeLora ->
                val loraResource = availableLoras.find { it.name == activeLora.name }
                if (loraResource != null) {
                    val url = viewModel.getPreviewUrl(loraResource.path, isLora = true)
                    if (url.isNotEmpty()) {
                        val request = ImageRequest.Builder(context)
                            .data(url)
                            .size(150)
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .build()
                        imageLoader.enqueue(request)
                    }
                }
            }
        }
    }

    val onGalleryClick = rememberDebounced {
        showOverflowMenu = false
        viewModel.setGalleryMode(GalleryMode.NORMAL)
        viewModel.fetchGalleryFolder(config.galleryPath)
        navController.navigate("gallery")
    }

    val onSettingsClick = rememberDebounced {
        showOverflowMenu = false
        navController.navigate("setup")
    }

    val onQueueClick = rememberDebounced { navController.navigate("queue") }

    Box(modifier = Modifier.fillMaxSize()) {

        Scaffold(
            modifier = Modifier.then(if (isRestoringPrompt) Modifier.blur(10.dp) else Modifier),
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Forge Generator".t, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (isConnected) Icons.Default.Wifi else Icons.Default.WifiOff,
                                    contentDescription = null,
                                    tint = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isConnected) "${pingMs}ms" else "Offline".t,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Normal
                                )
                                if (vram != null) {
                                    Text(
                                        text = " | $vram",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Normal,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha=0.7f)
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { showOverflowMenu = true }) {
                                Icon(Icons.Default.MoreVert, null)
                            }
                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Gallery".t, fontSize = 14.sp) },
                                    leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                    onClick = onGalleryClick
                                )
                                DropdownMenuItem(
                                    text = { Text("Settings".t, fontSize = 14.sp) },
                                    leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                    onClick = onSettingsClick
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                )
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                val scrollState = rememberScrollState()

                Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(8.dp)) {

                    val oomAlert by ForgeState.oomAlert.collectAsStateWithLifecycle()
                    AnimatedVisibility(visible = oomAlert) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Warning, contentDescription = "Error", tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(32.dp))
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("SERVER OUT OF MEMORY (OOM)".t, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                                Text("The current generation failed and the queue is paused. The failed prompt was skipped.".t, fontSize = 12.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onErrorContainer)
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(onClick = { viewModel.resumeQueue() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onErrorContainer, contentColor = MaterialTheme.colorScheme.errorContainer)) {
                                    Text("Resume Queue".t)
                                }
                            }
                        }
                    }

                    Box(modifier = Modifier.fillMaxWidth().height(240.dp).clip(MaterialTheme.shapes.medium).background(Color.DarkGray)) {

                        if (isGenerating && config.previewMode == "None") {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.align(Alignment.Center)) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("Generating...".t, color = Color.LightGray, fontSize = 12.sp)
                            }
                        } else if (isGenerating && !livePreviewBase64.isNullOrEmpty()) {
                            val previewBitmap by produceState<android.graphics.Bitmap?>(initialValue = null, livePreviewBase64) {
                                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                    try {
                                        val bytes = android.util.Base64.decode(livePreviewBase64, android.util.Base64.DEFAULT)
                                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                    } catch (e: Exception) { null }
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
                            val bStart = batchStart
                            val bEnd = batchEnd
                            val batchImages = sessionImages.subList(bStart, bEnd + 1)
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
                                                viewModel.dismissGridPreview(bStart + index)
                                                fullscreenImageIndex = bStart + index
                                            },
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            }
                        } else if (currentSessionIndex >= 0 && sessionImages.isNotEmpty() && currentSessionIndex < sessionImages.size) {
                            AsyncImage(
                                model = sessionImages[currentSessionIndex],
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize().clickable { fullscreenImageIndex = currentSessionIndex },
                                contentScale = ContentScale.Fit
                            )
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.align(Alignment.Center)) {
                                Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color.Gray)
                                Text("No Preview".t, color = Color.Gray)
                            }
                        }

                        Row(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            FilledTonalButton(onClick = { viewModel.sessionPrev() }, enabled = currentSessionIndex > batchStart, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(32.dp)) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null)
                            }
                            FilledTonalButton(onClick = { viewModel.sessionNext() }, enabled = currentSessionIndex < batchEnd, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(32.dp)) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    SectionHeader("Prompts".t)

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { showPresetsDialog = true }, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(32.dp)) {
                            Icon(Icons.Default.SettingsSuggest, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Manage Presets".t, fontSize = 12.sp)
                        }
                        Box {
                            TextButton(onClick = { showRecoverMenu = true }, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(32.dp)) {
                                Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Recover Prompt".t, fontSize = 12.sp)
                            }
                            DropdownMenu(expanded = showRecoverMenu, onDismissRequest = { showRecoverMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Last Generated Image".t, fontSize = 14.sp) },
                                    leadingIcon = { Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                    onClick = {
                                        showRecoverMenu = false
                                        viewModel.recoverLastPrompt()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("From History".t, fontSize = 14.sp) },
                                    leadingIcon = { Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                    onClick = {
                                        showRecoverMenu = false
                                        showHistoryDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("From Gallery Image".t, fontSize = 14.sp) },
                                    leadingIcon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(20.dp)) },
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
                        label = { Text("Positive Prompt".t, fontSize = 12.sp) },
                        minLines = 3,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PromptVisualTransformation(),
                        onClear = { viewModel.updateState { s -> s.copy(positivePrompt = "") } },
                        actions = {
                            if (config.showActiveTagsUI && state.positivePrompt.isNotBlank()) {
                                IconButton(onClick = { showPosTagEditor = true }) {
                                    Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    )

                    val positiveTokens = countTokens(state.positivePrompt)
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("$positiveTokens / 75", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha=0.5f))
                        Row {
                            TextButton(onClick = {
                                clipboardManager.setText(AnnotatedString(state.positivePrompt))
                                Toast.makeText(context, "Prompt Copied".t, Toast.LENGTH_SHORT).show()
                            }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp), modifier = Modifier.height(24.dp)) {
                                Text("Copy Prompt".t, fontSize = 10.sp)
                            }
                            TextButton(onClick = { viewModel.updateState { s -> s.copy(positivePrompt = "") } }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp), modifier = Modifier.height(24.dp)) {
                                Text("Clear".t, fontSize = 10.sp)
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
                        label = { Text("Negative Prompt".t, fontSize = 12.sp) },
                        minLines = 2,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PromptVisualTransformation(),
                        onClear = { viewModel.updateState { s -> s.copy(negativePrompt = "") } },
                        actions = {
                            if (config.showActiveTagsUI && state.negativePrompt.isNotBlank()) {
                                IconButton(onClick = { showNegTagEditor = true }) {
                                    Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    )

                    val negativeTokens = countTokens(state.negativePrompt)
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("$negativeTokens / 75", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha=0.5f))
                        TextButton(onClick = { viewModel.resetToDefaults() }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp), modifier = Modifier.height(24.dp)) {
                            Text("Reset to Defaults".t, fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    SectionHeader("Settings".t)

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
                                    Text("${"Model: ".t}${selectedModel.ifEmpty { "Loading...".t }}", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                                }
                            }
                            DropdownMenu(expanded = modelExpanded, onDismissRequest = { modelExpanded = false }, modifier = Modifier.heightIn(max = 350.dp)) {
                                models.forEach { mod ->
                                    DropdownMenuItem(
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
                                                Text(mod.title, fontSize = 12.sp)
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
                                label = { Text("Seed".t, fontSize = 12.sp) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )

                            var seedExpanded by remember { mutableStateOf(false) }
                            Box {
                                IconButton(
                                    onClick = { seedExpanded = true },
                                    modifier = Modifier.padding(start = 8.dp)
                                ) {
                                    Icon(Icons.Default.Casino, contentDescription = "Seed Options".t)
                                }
                                DropdownMenu(expanded = seedExpanded, onDismissRequest = { seedExpanded = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Randomize (-1)".t, fontSize = 14.sp) },
                                        onClick = {
                                            viewModel.updateState { s -> s.copy(seed = -1L) }
                                            seedExpanded = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Recover Last Seed".t, fontSize = 14.sp) },
                                        onClick = {
                                            viewModel.recoverLastSeed()
                                            seedExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        ForgeSlider("Batch Count".t, state.batchCount.toFloat(), 1f..100f, 0) { viewModel.updateState { s -> s.copy(batchCount = it.toInt()) } }
                        ForgeSlider("Batch Size".t, state.batchSize.toFloat(), 1f..16f, 0) { viewModel.updateState { s -> s.copy(batchSize = it.toInt()) } }
                        ForgeSlider("Steps".t, state.steps.toFloat(), 1f..100f, 0) { viewModel.updateState { s -> s.copy(steps = it.toInt()) } }
                        ForgeSlider("CFG Scale".t, state.cfgScale, 1f..20f, 1) { viewModel.updateState { s -> s.copy(cfgScale = it) } }
                        ForgeSlider("Clip Skip".t, state.clipSkip.toFloat(), 1f..3f, 0) { viewModel.updateState { s -> s.copy(clipSkip = it.toInt()) } }

                        Text("Aspect Ratio".t, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val aspectRatios = listOf("Custom", "1:1", "4:3", "3:4", "16:9", "9:16")
                            aspectRatios.forEach { ratio ->
                                val isSelected = state.aspectRatio == ratio
                                Surface(
                                    modifier = Modifier.clickable {
                                        viewModel.updateState { s ->
                                            var w = s.width
                                            var h = s.height
                                            when (ratio) {
                                                "1:1" -> { w = 512; h = 512 }
                                                "4:3" -> { w = 768; h = 512 }
                                                "3:4" -> { w = 512; h = 768 }
                                                "16:9" -> { w = 912; h = 512 }
                                                "9:16" -> { w = 512; h = 912 }
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

                        ForgeSlider("Width".t, state.width.toFloat(), 256f..2048f, 0) { viewModel.updateState { s -> s.copy(width = (it.toInt() / 64) * 64, aspectRatio = "Custom") } }
                        ForgeSlider("Height".t, state.height.toFloat(), 256f..2048f, 0) { viewModel.updateState { s -> s.copy(height = (it.toInt() / 64) * 64, aspectRatio = "Custom") } }

                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            var samplerExpanded by remember { mutableStateOf(false) }
                            Box(modifier = Modifier.weight(1f)) {
                                OutlinedButton(onClick = { samplerExpanded = true }, modifier = Modifier.fillMaxWidth().height(36.dp), contentPadding = PaddingValues(4.dp)) {
                                    Text(state.sampler, maxLines = 1, fontSize = 11.sp, overflow = TextOverflow.Ellipsis)
                                }
                                DropdownMenu(expanded = samplerExpanded, onDismissRequest = { samplerExpanded = false }, modifier = Modifier.heightIn(max = 350.dp)) {
                                    samplers.forEach { samp -> DropdownMenuItem(text = { Text(samp, fontSize = 12.sp) }, onClick = { viewModel.updateState { s -> s.copy(sampler = samp) }; samplerExpanded = false }) }
                                }
                            }

                            var schedulerExpanded by remember { mutableStateOf(false) }
                            Box(modifier = Modifier.weight(1f)) {
                                OutlinedButton(onClick = { schedulerExpanded = true }, modifier = Modifier.fillMaxWidth().height(36.dp), contentPadding = PaddingValues(4.dp)) {
                                    Text(state.scheduler, maxLines = 1, fontSize = 11.sp, overflow = TextOverflow.Ellipsis)
                                }
                                DropdownMenu(expanded = schedulerExpanded, onDismissRequest = { schedulerExpanded = false }, modifier = Modifier.heightIn(max = 350.dp)) {
                                    schedulers.forEach { sched -> DropdownMenuItem(text = { Text(sched, fontSize = 12.sp) }, onClick = { viewModel.updateState { s -> s.copy(scheduler = sched) }; schedulerExpanded = false }) }
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
                                Text("Hires.fix".t, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                            }
                            HorizontalDivider(modifier = Modifier.weight(1f))
                        }
                        AnimatedVisibility(visible = state.hiresFix) {
                            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                                var upscalerExpanded by remember { mutableStateOf(false) }
                                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    OutlinedButton(onClick = { upscalerExpanded = true }, modifier = Modifier.fillMaxWidth().height(36.dp), contentPadding = PaddingValues(4.dp)) {
                                        Text("${"Upscaler".t}: ${state.upscaler}", maxLines = 1, fontSize = 11.sp, overflow = TextOverflow.Ellipsis)
                                    }
                                    DropdownMenu(expanded = upscalerExpanded, onDismissRequest = { upscalerExpanded = false }, modifier = Modifier.heightIn(max = 350.dp)) {
                                        upscalers.forEach { upsc -> DropdownMenuItem(text = { Text(upsc, fontSize = 12.sp) }, onClick = { viewModel.updateState { s -> s.copy(upscaler = upsc) }; upscalerExpanded = false }) }
                                    }
                                }
                                ForgeSlider("Hires Scale".t, state.hiresScale, 1f..4f, 2) { viewModel.updateState { s -> s.copy(hiresScale = it) } }
                                ForgeSlider("Denoising".t, state.denoising, 0f..1f, 2) { viewModel.updateState { s -> s.copy(denoising = it) } }
                            }
                        }

                    }

                    SectionHeader("LoRAs".t)

                    Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                        var loraExpanded by remember { mutableStateOf(false) }
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            OutlinedButton(onClick = { loraExpanded = true }, modifier = Modifier.fillMaxWidth().height(36.dp), contentPadding = PaddingValues(4.dp)) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Add LoRA...".t, fontSize = 12.sp)
                            }
                            DropdownMenu(expanded = loraExpanded, onDismissRequest = { loraExpanded = false }, modifier = Modifier.heightIn(max = 350.dp)) {
                                availableLoras.forEach { loraData ->
                                    DropdownMenuItem(
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
                                                Text(loraData.title, fontSize = 12.sp)
                                            }
                                        },
                                        onClick = {
                                            pendingLora = loraData
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
                                            IconButton(onClick = { viewModel.removeLora(lora.name) }, modifier = Modifier.size(24.dp)) {
                                                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("Strength".t, fontSize = 10.sp)
                                            Text(String.format(java.util.Locale.US, "%.2f", lora.strength), fontSize = 10.sp)
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

                    Spacer(modifier = Modifier.height(100.dp))
                }

                val isActivelyGenerating = isGenerating || isServerBusy || progress > 0f

                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp, start = 16.dp, end = 16.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onQueueClick,
                        modifier = Modifier.height(54.dp).weight(0.35f).shadow(8.dp, CircleShape),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(Icons.Default.List, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("${generationQueue.size}", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }

                    if (isActivelyGenerating) {
                        val queueGen = rememberDebounced { viewModel.queueGeneration() }
                        val interruptGen = rememberDebounced { viewModel.interruptGeneration() }

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
                                        text = "ADD TO QUEUE • ".t + "$percentage%",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        style = TextStyle(shadow = androidx.compose.ui.graphics.Shadow(color = Color.Black.copy(alpha=0.8f), blurRadius = 4f))
                                    )

                                    val stepStr = if (currentSamplingSteps > 0) "Img ${currentJobNo + 1}/$currentJobCount | Step $currentSamplingStep/$currentSamplingSteps".t else "Img ${currentJobNo + 1}/$currentJobCount | Step ${(progress * state.steps).toInt()}/${state.steps}".t

                                    Text(
                                        text = "$stepStr | ETA: ${String.format(Locale.US, "%.1f", currentEta)}s",
                                        fontSize = 9.sp,
                                        color = Color.LightGray,
                                        style = TextStyle(shadow = androidx.compose.ui.graphics.Shadow(color = Color.Black.copy(alpha=0.8f), blurRadius = 4f))
                                    )
                                }
                            }
                        }
                    } else {
                        val genBtn = rememberDebounced { viewModel.queueGeneration() }
                        Button(
                            onClick = genBtn,
                            enabled = isConnected && !isRestoringPrompt,
                            modifier = Modifier.height(54.dp).weight(1.2f).shadow(8.dp, CircleShape),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text("GENERATE".t, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }
        }

        if (showPresetsDialog) {
            var newPresetName by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showPresetsDialog = false },
                title = { Text("Manage Presets".t) },
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
                                        Icon(Icons.Default.Delete, "Delete".t, tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Text("Save Current Settings as Preset".t, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        OutlinedTextField(
                            value = newPresetName,
                            onValueChange = { newPresetName = it },
                            label = { Text("Preset Name".t) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { viewModel.saveCurrentAsDefault(); showPresetsDialog = false }) {
                                Text("Set Current as Default".t, fontSize = 12.sp)
                            }
                            Button(
                                onClick = {
                                    if (newPresetName.isNotBlank()) {
                                        viewModel.savePreset(newPresetName)
                                        newPresetName = ""
                                    }
                                }
                            ) {
                                Text("Save Preset".t)
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showPresetsDialog = false }) { Text("Close".t) } }
            )
        }

        if (showPosTagEditor) {
            AlertDialog(
                onDismissRequest = { showPosTagEditor = false },
                title = { Text("Active Positive Tags".t, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                text = {
                    Box(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp).background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small).padding(8.dp)) {
                        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            DragToReorderTagsRow(state.positivePrompt) { newPrompt -> viewModel.updateState { s -> s.copy(positivePrompt = newPrompt) } }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showPosTagEditor = false }) { Text("Done".t) }
                }
            )
        }

        if (showNegTagEditor) {
            AlertDialog(
                onDismissRequest = { showNegTagEditor = false },
                title = { Text("Active Negative Tags".t, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                text = {
                    Box(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp).background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small).padding(8.dp)) {
                        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            DragToReorderTagsRow(state.negativePrompt) { newPrompt -> viewModel.updateState { s -> s.copy(negativePrompt = newPrompt) } }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showNegTagEditor = false }) { Text("Done".t) }
                }
            )
        }

        if (showHistoryDialog) {
            AlertDialog(
                onDismissRequest = { showHistoryDialog = false },
                title = {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Prompt History".t)
                        IconButton(onClick = { viewModel.clearPromptHistory(); showHistoryDialog = false }) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                        }
                    }
                },
                text = {
                    if (promptHistory.isEmpty()) {
                        Text("No history available yet.".t, color = Color.Gray)
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            items(promptHistory) { item ->
                                val timeFormat = java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault()).format(item.timestamp)
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
                                        if (item.negativePrompt.isNotBlank()) Text("Negative: ".t + item.negativePrompt, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 10.sp, color = Color.Gray)
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showHistoryDialog = false }) { Text("Close".t) }
                }
            )
        }

        if (pendingLora != null) {
            val lora = pendingLora!!
            var triggerWords by remember(lora) { mutableStateOf<List<String>?>(null) }
            var selectedWords by remember { mutableStateOf(emptySet<String>()) }
            var originalPrompt by remember(lora) { mutableStateOf(state.positivePrompt) }

            LaunchedEffect(lora) {
                viewModel.fetchLoraTriggerWords(lora) { words -> triggerWords = words }
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
                    pendingLora = null
                },
                title = { Text(lora.title, fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        if (triggerWords == null) {
                            CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                            Text("Loading trigger words...".t, fontSize = 12.sp)
                        } else {
                            if (triggerWords!!.isNotEmpty()) {
                                Text("Click tags to add/remove them from your prompt:".t, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                                LazyColumn(modifier = Modifier.heightIn(max = 250.dp).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)) {
                                    items(triggerWords!!) { word ->
                                        val isSelected = selectedWords.contains(word)
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth().clickable {
                                                selectedWords = if (isSelected) selectedWords.minus(word) else selectedWords.plus(word)
                                            }.padding(horizontal = 8.dp, vertical = 6.dp)
                                        ) {
                                            Checkbox(
                                                checked = isSelected,
                                                onCheckedChange = null
                                            )
                                            Text(word, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
                                        }
                                    }
                                }
                            } else {
                                Text("This LoRA has no trigger words associated with it. Do you still want to add it?".t, fontSize = 14.sp)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { pendingLora = null }, enabled = triggerWords != null) {
                        Text(if (triggerWords?.isEmpty() == true) "Add LoRA".t else "Done".t)
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        viewModel.updateState { it.copy(positivePrompt = originalPrompt) }
                        pendingLora = null
                    }) { Text("Cancel".t) }
                }
            )
        }

        if (fullscreenImageIndex >= 0 && sessionImages.isNotEmpty()) {
            val pagerState = rememberPagerState(initialPage = fullscreenImageIndex, pageCount = { sessionImages.size })
            val currentFile = sessionImages.getOrNull(pagerState.currentPage) ?: ""

            LaunchedEffect(pagerState.currentPage) {
                if (fullscreenImageIndex != pagerState.currentPage) {
                    fullscreenImageIndex = pagerState.currentPage
                }
                if (currentFile.isNotEmpty()) {
                    viewModel.loadMetadataForLocalFile(currentFile)
                }
            }

            Dialog(onDismissRequest = { fullscreenImageIndex = -1 }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {

                    Row(modifier = Modifier.fillMaxWidth().background(Color(0x88000000)).padding(vertical = 4.dp, horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { fullscreenImageIndex = -1 }) { Icon(Icons.Default.Close, contentDescription = "Close".t, tint = Color.White) }
                        Text(File(currentFile).name, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))

                        IconButton(onClick = {
                            viewModel.shareSessionImage(currentFile) { intent ->
                                context.startActivity(intent)
                            }
                        }) {
                            Icon(Icons.Default.Share, contentDescription = "Share".t, tint = Color.White)
                        }

                        val showMetadata by viewModel.showGalleryMetadata.collectAsStateWithLifecycle()
                        IconButton(onClick = { viewModel.toggleGalleryMetadata() }) {
                            Icon(Icons.Default.Info, contentDescription = "Info".t, tint = Color.White, modifier = Modifier.then(if (showMetadata) Modifier.background(Color(0x55FFFFFF), CircleShape).padding(2.dp) else Modifier))
                        }
                        IconButton(onClick = { viewModel.downloadSessionImage(currentFile) }) {
                            Icon(Icons.Default.Save, contentDescription = "Save".t, tint = Color.White)
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
                                    viewModel.updateState { it.copy(positivePrompt = pos, negativePrompt = neg) }
                                    Toast.makeText(context, "Prompts Applied".t, Toast.LENGTH_SHORT).show()
                                },
                                onApplyModel = { modelName ->
                                    viewModel.changeCheckpoint(modelName)
                                    Toast.makeText(context, "Model Applied: ".t + modelName, Toast.LENGTH_SHORT).show()
                                },
                                onApplyLoras = { loraList ->
                                    loraList.forEach { loraTag ->
                                        val loraName = loraTag.substringAfter("<lora:").substringBefore(":")
                                        if (loraName.isNotEmpty()) viewModel.appendLora(loraName)
                                    }
                                    Toast.makeText(context, "LoRAs Applied".t, Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}