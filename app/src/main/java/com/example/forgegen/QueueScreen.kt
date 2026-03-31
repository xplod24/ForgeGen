package com.yourname.forgegen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.yourname.forgegen.Translator.t

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(viewModel: ForgeViewModel, navController: NavHostController) {
    val queue by ForgeState.generationQueue.collectAsStateWithLifecycle()
    val totalSize by ForgeState.totalQueueSize.collectAsStateWithLifecycle()
    val completed by ForgeState.completedQueueItems.collectAsStateWithLifecycle()
    val currentProgress by viewModel.progress.collectAsStateWithLifecycle()
    val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()

    var editItemId by remember { mutableStateOf<String?>(null) }
    var editPosPrompt by remember { mutableStateOf("") }
    var editNegPrompt by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Generation Queue".t, fontSize = 20.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
                actions = {
                    if (isGenerating) {
                        IconButton(onClick = { viewModel.interruptGeneration() }) {
                            Icon(Icons.Default.Stop, contentDescription = "Interrupt", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    TextButton(onClick = { viewModel.clearQueue() }) {
                        Text("Clear All".t)
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (isGenerating || queue.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Current Item Progress".t, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        LinearProgressIndicator(
                            progress = { currentProgress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        val totalQueueProgress = if (totalSize > 0) completed.toFloat() / totalSize.toFloat() else 0f
                        Text("${"Entire Queue Progress".t} ($completed/$totalSize)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        LinearProgressIndicator(
                            progress = { totalQueueProgress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            color = Color(0xFF4CAF50)
                        )
                    }
                }
            }

            if (queue.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Queue is empty.".t, color = Color.Gray)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(items = queue, key = { it.id }) { item ->
                        val isCurrentlyActive = isGenerating && queue.indexOf(item) == 0

                        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(item.positivePrompt.ifEmpty { "[No Prompt]".t }, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                                    Text("${"Steps: ".t}${item.payload.steps} | ${"CFG: ".t}${item.payload.cfg_scale}", fontSize = 12.sp, color = Color.Gray)
                                }

                                if (isCurrentlyActive) {
                                    Icon(Icons.Default.HourglassEmpty, null, modifier = Modifier.size(24.dp).padding(horizontal = 4.dp), tint = MaterialTheme.colorScheme.primary)
                                } else {
                                    IconButton(
                                        onClick = {
                                            editItemId = item.id
                                            editPosPrompt = item.positivePrompt
                                            editNegPrompt = item.payload.negative_prompt
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) { Icon(Icons.Default.Edit, "Edit".t, modifier = Modifier.size(20.dp)) }
                                }

                                Column {
                                    IconButton(onClick = { viewModel.moveQueueItemUp(item.id) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.KeyboardArrowUp, "Move Up".t) }
                                    IconButton(onClick = { viewModel.moveQueueItemDown(item.id) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.KeyboardArrowDown, "Move Down".t) }
                                }
                                IconButton(onClick = { viewModel.removeFromQueue(item.id) }) {
                                    Icon(Icons.Default.Delete, "Remove".t, tint = MaterialTheme.colorScheme.error)
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
                title = { Text("Edit Queue Item".t) },
                text = {
                    Column {
                        OutlinedTextField(
                            value = editPosPrompt,
                            onValueChange = { editPosPrompt = it },
                            label = { Text("Positive Prompt".t) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 200.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = editNegPrompt,
                            onValueChange = { editNegPrompt = it },
                            label = { Text("Negative Prompt".t) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 150.dp)
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.updateQueueItem(editItemId!!, editPosPrompt, editNegPrompt)
                        editItemId = null
                    }) { Text("Save".t) }
                },
                dismissButton = {
                    TextButton(onClick = { editItemId = null }) { Text("Cancel".t) }
                }
            )
        }
    }
}