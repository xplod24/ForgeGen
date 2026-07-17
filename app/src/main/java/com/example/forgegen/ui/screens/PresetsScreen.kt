
package com.example.forgegen

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetsScreen(viewModel: ForgeViewModel, navController: NavHostController) {
    val context = LocalContext.current
    val config by viewModel.config.collectAsStateWithLifecycle()
    val appState by viewModel.appState.collectAsStateWithLifecycle()

    var showAddDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf<GenerationPreset?>(null) }
    var showDefaultConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Generation Presets", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showDefaultConfirm = true }) {
                        Icon(Icons.Default.SettingsBackupRestore, contentDescription = "Set current settings as startup default")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, contentDescription = "Save current configuration as Preset")
            }
        }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            if (config.presets.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(
                            Icons.Default.SettingsSuggest,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                        Text(
                            "No presets saved",
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "Tap + to save your current generation parameters as a Preset",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                            modifier = Modifier.padding(horizontal = 32.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(config.presets, key = { it.name }) { preset ->
                        var presetName by remember(preset.name) { mutableStateOf(preset.name) }
                        var positivePrompt by remember(preset.state.positivePrompt) { mutableStateOf(preset.state.positivePrompt) }
                        var negativePrompt by remember(preset.state.negativePrompt) { mutableStateOf(preset.state.negativePrompt) }
                        var includePrompts by remember(preset.includePrompts) { mutableStateOf(preset.includePrompts) }

                        // Function to save preset inline
                        val saveChangesInline = {
                            val updatedPreset = preset.copy(
                                name = presetName,
                                includePrompts = includePrompts,
                                state = preset.state.copy(
                                    positivePrompt = positivePrompt,
                                    negativePrompt = negativePrompt
                                )
                            )
                            viewModel.updatePreset(preset.name, updatedPreset)
                        }

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                // Inline editable Preset Name & Top Actions
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    OutlinedTextField(
                                        value = presetName,
                                        onValueChange = {
                                            presetName = it
                                            saveChangesInline()
                                        },
                                        textStyle = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp),
                                        placeholder = { Text("Preset Name", fontSize = 16.sp) },
                                        modifier = Modifier.weight(1f).height(48.dp),
                                        singleLine = true,
                                        colors = TextFieldDefaults.colors(
                                            focusedContainerColor = Color.Transparent,
                                            unfocusedContainerColor = Color.Transparent,
                                            disabledContainerColor = Color.Transparent,
                                            focusedIndicatorColor = Color.Transparent,
                                            unfocusedIndicatorColor = Color.Transparent
                                        )
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Button(
                                            onClick = {
                                                viewModel.loadPreset(preset.name)
                                                Toast.makeText(context, "Loaded Preset: ${preset.name}", Toast.LENGTH_LONG).show()
                                                navController.popBackStack()
                                            },
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                            modifier = Modifier.height(32.dp)
                                        ) {
                                            Icon(Icons.Default.Check, null, modifier = Modifier.size(14.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Apply", fontSize = 12.sp)
                                        }
                                        IconButton(
                                            onClick = { showDeleteConfirm = preset },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = "Delete Preset",
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }

                                // Specs Row
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    val state = preset.state
                                    val sizeText = if (state.aspectRatio == "Custom") "${state.width}x${state.height}" else state.aspectRatio
                                    Badge(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                                        Text("Steps: ${state.steps}", modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp), fontSize = 10.sp)
                                    }
                                    Badge(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                                        Text("CFG: ${state.cfgScale}", modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp), fontSize = 10.sp)
                                    }
                                    Badge(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                                        Text(state.sampler, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp), fontSize = 10.sp)
                                    }
                                    Badge(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                                        Text(sizeText, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp), fontSize = 10.sp)
                                    }
                                    if (state.hiresFix) {
                                        Badge(containerColor = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer) {
                                            Text("Hires.Fix", modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp), fontSize = 10.sp)
                                        }
                                    }
                                }

                                // Toggle Prompts Inclusion Inline
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Include prompts in preset", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    Checkbox(
                                        checked = includePrompts,
                                        onCheckedChange = {
                                            includePrompts = it
                                            saveChangesInline()
                                        }
                                    )
                                }

                                // Editable Prompts (Only if prompts inclusion is checked)
                                if (includePrompts) {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("Positive Prompt (Inline Edit):", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                                        OutlinedTextField(
                                            value = positivePrompt,
                                            onValueChange = {
                                                positivePrompt = it
                                                saveChangesInline()
                                            },
                                            textStyle = TextStyle(fontSize = 12.sp),
                                            modifier = Modifier.fillMaxWidth(),
                                            maxLines = 4
                                        )

                                        Spacer(modifier = Modifier.height(2.dp))

                                        Text("Negative Prompt (Inline Edit):", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                                        OutlinedTextField(
                                            value = negativePrompt,
                                            onValueChange = {
                                                negativePrompt = it
                                                saveChangesInline()
                                            },
                                            textStyle = TextStyle(fontSize = 12.sp),
                                            modifier = Modifier.fillMaxWidth(),
                                            maxLines = 4
                                        )
                                    }
                                }
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
        }
    }

    // Save Preset Dialog (Still used for initial save of current state via FAB)
    if (showAddDialog) {
        var presetName by remember { mutableStateOf("") }
        var includePromptsByDef by remember { mutableStateOf(true) }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Save current configuration as Preset") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = presetName,
                        onValueChange = { presetName = it },
                        label = { Text("Preset Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Include current prompts", fontSize = 14.sp)
                        Checkbox(
                            checked = includePromptsByDef,
                            onCheckedChange = { includePromptsByDef = it }
                        )
                    }
                    Text("Settings to save:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val sizeText = if (appState.aspectRatio == "Custom") "${appState.width}x${appState.height}" else appState.aspectRatio
                        Text("• Sampler: ${appState.sampler} (${appState.scheduler})", fontSize = 11.sp)
                        Text("• Steps: ${appState.steps} | CFG: ${appState.cfgScale}", fontSize = 11.sp)
                        Text("• Resolution: $sizeText", fontSize = 11.sp)
                        if (appState.hiresFix) {
                            Text("• Hires.Fix active (Denoise: ${appState.denoising})", fontSize = 11.sp)
                        }
                        if (includePromptsByDef && appState.positivePrompt.isNotBlank()) {
                            Text("• Prompts will be saved and loaded", fontSize = 11.sp)
                        } else if (!includePromptsByDef) {
                            Text("• Prompts will be saved, but NOT loaded by default", fontSize = 11.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (presetName.isNotBlank()) {
                            viewModel.savePreset(presetName.trim(), includePromptsByDef)
                            showAddDialog = false
                            Toast.makeText(context, "Preset saved: $presetName", Toast.LENGTH_LONG).show()
                        }
                    },
                    enabled = presetName.isNotBlank()
                ) {
                    Text("Save", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Set startup default confirmation
    if (showDefaultConfirm) {
        AlertDialog(
            onDismissRequest = { showDefaultConfirm = false },
            title = { Text("Set Default Startup settings") },
            text = { Text("Make your current layout (prompts, steps, sampler, resolution) the default settings loaded every time ForgeGen starts up?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.saveCurrentAsDefault()
                    showDefaultConfirm = false
                    Toast.makeText(context, "Startup defaults updated", Toast.LENGTH_LONG).show()
                }) {
                    Text("Set Startup Default", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDefaultConfirm = false }) { Text("Cancel") }
            }
        )
    }

    // Delete confirmation
    showDeleteConfirm?.let { preset ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("Delete Preset") },
            text = { Text("Are you sure you want to delete the preset \"${preset.name}\"?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deletePreset(preset.name)
                    showDeleteConfirm = null
                    Toast.makeText(context, "Deleted Preset: ${preset.name}", Toast.LENGTH_LONG).show()
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun FlowRow(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable FlowRowScope.() -> Unit
) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = modifier,
        horizontalArrangement = horizontalArrangement,
        verticalArrangement = verticalArrangement,
        content = content
    )
}
