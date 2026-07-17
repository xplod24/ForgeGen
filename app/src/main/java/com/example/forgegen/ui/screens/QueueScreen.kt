package com.example.forgegen

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

/* ============================================================================
 * SHIMMER EFFECT (SKELETON LOADING & FRAMES)
 * Tworzy animowany gradient naśladujący ładowanie oraz ozdobne ramki
 * ============================================================================ */

/* ============================================================================
 * QUEUE SCREEN COMPOSABLE
 * ============================================================================ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(viewModel: ForgeViewModel, navController: NavHostController) {
    val queue by viewModel.generationQueue.collectAsStateWithLifecycle()
    val totalSize by viewModel.totalQueueSize.collectAsStateWithLifecycle()
    val completed by viewModel.completedQueueItems.collectAsStateWithLifecycle()
    val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()

    val models by viewModel.models.collectAsStateWithLifecycle()
    val availableLoras by viewModel.availableLoras.collectAsStateWithLifecycle()

    var editItemId by remember { mutableStateOf<String?>(null) }
    var editPosPrompt by remember { mutableStateOf("") }
    var editNegPrompt by remember { mutableStateOf("") }

    val onBackClick = remember { {
        if (navController.currentDestination?.route == "queue") {
            navController.popBackStack()
        }
        Unit
    } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Generation Queue", fontWeight = FontWeight.Bold)
                        if (totalSize > 0) {
                            Text("Progress: $completed / $totalSize", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (isGenerating) {
                        IconButton(onClick = { viewModel.interruptGeneration() }) {
                            Icon(Icons.Default.Stop, "Interrupt Current", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (queue.isNotEmpty()) {
                        IconButton(onClick = { viewModel.clearQueue() }) {
                            Icon(Icons.Default.Delete, "Clear Queue")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (queue.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.HourglassEmpty, null, modifier = Modifier.size(64.dp), tint = Color.Gray)
                    Spacer(Modifier.height(16.dp))
                    Text("Queue is empty", color = Color.Gray, fontSize = 18.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(queue) { item ->
                        val isFirst = queue.firstOrNull()?.id == item.id
                        val isActive = isFirst && isGenerating
                        var isExpanded by remember { mutableStateOf(false) }

                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                            ),
                            elevation = CardDefaults.cardElevation(if (isActive) 8.dp else 2.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { isExpanded = !isExpanded }
                                    .padding(16.dp)
                            ) {
                                // --- HEADER: Status + Checkpoint ---
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    if (isActive) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Generating...", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    } else {
                                        Text("Queued", fontSize = 12.sp, color = Color.Gray)
                                    }
                                    Spacer(Modifier.weight(1f))
                                    Icon(
                                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = "Expand",
                                        tint = Color.Gray
                                    )
                                }

                                Spacer(Modifier.height(8.dp))

                                // CHECKPOINT INFO
                                val checkpoint = item.payload.override_settings.sdModelCheckpoint ?: "Default Model"
                                val modelResource = models.find { it.name == checkpoint || it.title == checkpoint }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (modelResource != null) {
                                        AsyncImage(
                                            model = viewModel.getPreviewUrl(modelResource.path, isLora = false),
                                            contentDescription = null,
                                            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(4.dp)),
                                            contentScale = ContentScale.Crop
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    } else {
                                        Icon(Icons.Default.Extension, null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(modelResource?.title ?: checkpoint, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }

                                // LORAS
                                val lorasInPrompt = remember(item.positivePrompt) {
                                    val regex = Regex("<lora:([^:]+):([0-9.]+)>")
                                    regex.findAll(item.positivePrompt).map { match ->
                                        ActiveLora(match.groupValues[1], match.groupValues[2].toFloatOrNull() ?: 1f)
                                    }.toList()
                                }

                                if (lorasInPrompt.isNotEmpty()) {
                                    LazyRow(
                                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        items(lorasInPrompt) { lora ->
                                            val loraResource = availableLoras.find { it.name == lora.name }
                                            Surface(
                                                shape = MaterialTheme.shapes.small,
                                                color = MaterialTheme.colorScheme.background,
                                                modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 6.dp)) {
                                                    if (loraResource != null) {
                                                        AsyncImage(
                                                            model = viewModel.getPreviewUrl(loraResource.path, isLora = true),
                                                            contentDescription = null,
                                                            modifier = Modifier.size(24.dp).clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)),
                                                            contentScale = ContentScale.Crop
                                                        )
                                                    } else {
                                                        Box(modifier = Modifier.size(24.dp).background(Color.Gray).clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)))
                                                    }
                                                    Spacer(Modifier.width(4.dp))
                                                    Text(loraResource?.title ?: lora.name, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 80.dp))
                                                    Text(" : ${lora.strength}", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                                }
                                            }
                                        }
                                    }
                                }

                                // --- EXPANDABLE BODY ---
                                AnimatedVisibility(visible = isExpanded) {
                                    Column(modifier = Modifier.padding(top = 12.dp)) {
                                        HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))
                                        Text("Positive Prompt", fontSize = 10.sp, color = Color.Gray)
                                        Text(item.positivePrompt, fontSize = 12.sp)
                                        Spacer(Modifier.height(4.dp))

                                        if (item.payload.negative_prompt.isNotBlank()) {
                                            Text("Negative Prompt", fontSize = 10.sp, color = Color.Gray)
                                            Text(item.payload.negative_prompt, fontSize = 12.sp)
                                            Spacer(Modifier.height(4.dp))
                                        }

                                        val p = item.payload
                                        Text("Steps: ${p.steps} | CFG: ${p.cfg_scale} | Sampler: ${p.sampler_name}", fontSize = 10.sp, color = Color.Gray)
                                        Text("Size: ${p.width}x${p.height} | Seed: ${p.seed}", fontSize = 10.sp, color = Color.Gray)
                                    }
                                }

                                // --- BUTTONS ---
                                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Row {
                                        IconButton(
                                            onClick = { viewModel.moveQueueItemUp(item.id) },
                                            enabled = !isFirst,
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(Icons.Default.KeyboardArrowUp, "Move Up")
                                        }
                                        IconButton(
                                            onClick = { viewModel.moveQueueItemDown(item.id) },
                                            enabled = queue.lastOrNull()?.id != item.id,
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(Icons.Default.KeyboardArrowDown, "Move Down")
                                        }
                                    }

                                    // Block Edit/Delete while generating
                                    if (!isActive) {
                                        Row {
                                            IconButton(
                                                onClick = {
                                                    editPosPrompt = item.payload.prompt
                                                    editNegPrompt = item.payload.negative_prompt
                                                    editItemId = item.id
                                                },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(Icons.Default.Edit, "Edit", tint = MaterialTheme.colorScheme.primary)
                                            }
                                            Spacer(Modifier.width(8.dp))
                                            IconButton(
                                                onClick = { viewModel.removeFromQueue(item.id) },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(Icons.Default.Delete, "Remove", tint = MaterialTheme.colorScheme.error)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
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
                            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 200.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = editNegPrompt,
                            onValueChange = { editNegPrompt = it },
                            label = { Text("Negative Prompt") },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 150.dp)
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
                }
            )
        }
    }
}
