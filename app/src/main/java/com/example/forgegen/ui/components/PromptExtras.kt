package com.example.forgegen.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.forgegen.ApiResource
import com.example.forgegen.EmbeddingList
import com.example.forgegen.LoraInfo
import com.example.forgegen.LoraInfoIndex
import com.example.forgegen.ModelType
import com.example.forgegen.PromptEdits
import com.example.forgegen.PromptStyle
import com.example.forgegen.PromptStyles
import java.util.Locale

/* ============================================================================
 * LORAS, EMBEDDINGS AND THE SERVER'S STYLES (3.1.0, the owner's pick of the mockups in "ForgeGen API Features
 * Proposals", boards 1A-1D)
 * The LoRA picker has a second tab with the embeddings; a LoRA shows the model it was trained for (a badge) and its
 * most used training tags, those that fit the checkpoint's type first. In the LoRA card its trigger words are chips
 * that add themselves to the prompt, and its name opens its details. The server's styles are a row of the prompt
 * card, only while "Server Styles" is on in the settings.
 * ============================================================================ */

private val FITS_COLOR = Color(0xFF3E80FF)
private val MISFIT_COLOR = Color(0xFFFFA726)

/** A LoRA's model as a small badge: blue when it fits the checkpoint, orange when it does not, grey when unknown. */
@Composable
fun LoraBadge(
    label: String,
    fits: Boolean?,
) {
    val color =
        when (fits) {
            true -> FITS_COLOR
            false -> MISFIT_COLOR
            null -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    Text(
        label,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.4.sp,
        color = color,
        maxLines = 1,
        modifier =
            Modifier
                .clip(
                    RoundedCornerShape(6.dp),
                ).background(color.copy(alpha = 0.16f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private enum class LoraFilter { FITS, ALL, IN_USE }

/**
 * LoRAs and embeddings to add (boards 1A and 1D). [modelType] is the checkpoint's type from its settings: with
 * Auto nothing is known to fit or not, so the LoRAs are one list with their badges.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoraPickerSheet(
    loras: List<ApiResource>,
    info: LoraInfoIndex,
    modelType: ModelType,
    isActive: (ApiResource) -> Boolean,
    previewCandidates: (ApiResource) -> List<String>,
    onPickLora: (ApiResource) -> Unit,
    onRefreshLoras: () -> Unit,
    embeddings: EmbeddingList,
    positivePrompt: String,
    negativePrompt: String,
    onAddEmbedding: (name: String, negative: Boolean) -> Unit,
    onRefreshEmbeddings: () -> Unit,
    onDismiss: () -> Unit,
    startOnEmbeddings: Boolean = false,
) {
    var embeddingsTab by rememberSaveable { mutableStateOf(startOnEmbeddings) }
    var query by rememberSaveable { mutableStateOf("") }
    val known = modelType != ModelType.AUTO
    var filter by rememberSaveable { mutableStateOf(if (known) LoraFilter.FITS else LoraFilter.ALL) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(if (embeddingsTab) "Embeddings" else "LoRA", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        when {
                            embeddingsTab -> "Loaded for the current model first"
                            known -> "Model type: ${modelType.label}"
                            else -> "Set the model's type to see which ones fit"
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = if (embeddingsTab) onRefreshEmbeddings else onRefreshLoras) {
                    Icon(Icons.Default.Refresh, contentDescription = if (embeddingsTab) "Refresh Embeddings" else "Refresh LoRA List")
                }
            }
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                SegmentedButton(
                    selected = !embeddingsTab,
                    onClick = { embeddingsTab = false },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("LoRA · ${loras.size}") }
                SegmentedButton(
                    selected = embeddingsTab,
                    onClick = { embeddingsTab = true },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("Embeddings · ${embeddings.all.size}") }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
            if (embeddingsTab) {
                EmbeddingRows(embeddings, query, positivePrompt, negativePrompt, onAddEmbedding)
            } else {
                LoraRows(loras, info, modelType, filter, { filter = it }, query, isActive, previewCandidates, onPickLora)
            }
        }
    }
}

@Composable
private fun LoraRows(
    loras: List<ApiResource>,
    info: LoraInfoIndex,
    modelType: ModelType,
    filter: LoraFilter,
    onFilter: (LoraFilter) -> Unit,
    query: String,
    isActive: (ApiResource) -> Boolean,
    previewCandidates: (ApiResource) -> List<String>,
    onPick: (ApiResource) -> Unit,
) {
    val text = query.trim()
    val searched = remember(loras, text) { if (text.isEmpty()) loras else loras.filter { it.title.contains(text, ignoreCase = true) } }
    val fitsOf: (ApiResource) -> Boolean? = { info.of(it.name, it.path)?.fits(modelType) }
    val fitting = searched.filter { fitsOf(it) != false }
    val misfits = searched.filter { fitsOf(it) == false }
    val inUse = searched.filter(isActive)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 4.dp)) {
        if (modelType != ModelType.AUTO) {
            FilterChip(
                selected = filter == LoraFilter.FITS,
                onClick = { onFilter(LoraFilter.FITS) },
                label = { Text("Fits ${modelType.label} · ${fitting.size}") },
            )
        }
        FilterChip(selected = filter == LoraFilter.ALL, onClick = { onFilter(LoraFilter.ALL) }, label = { Text("All") })
        FilterChip(
            selected = filter == LoraFilter.IN_USE,
            onClick = { onFilter(LoraFilter.IN_USE) },
            label = { Text("In use · ${inUse.size}") },
        )
    }
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        val main =
            when (filter) {
                LoraFilter.FITS -> fitting
                LoraFilter.ALL -> searched
                LoraFilter.IN_USE -> inUse
            }
        items(main, key = { "l:" + it.path.ifEmpty { it.name } }) { lora ->
            LoraRow(lora, info.of(lora.name, lora.path), fitsOf(lora), isActive(lora), previewCandidates, onPick)
        }
        // Those made for another model stay pickable, below and dimmed.
        if (filter == LoraFilter.FITS && misfits.isNotEmpty()) {
            item(key = "misfits") {
                Text(
                    "NOT FOR ${modelType.label.uppercase(Locale.getDefault())} · ${misfits.size}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.96.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 4.dp),
                )
            }
            items(misfits, key = { "m:" + it.path.ifEmpty { it.name } }) { lora ->
                LoraRow(lora, info.of(lora.name, lora.path), false, isActive(lora), previewCandidates, onPick, dimmed = true)
            }
        }
    }
}

@Composable
private fun LoraRow(
    lora: ApiResource,
    info: LoraInfo?,
    fits: Boolean?,
    active: Boolean,
    previewCandidates: (ApiResource) -> List<String>,
    onPick: (ApiResource) -> Unit,
    dimmed: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else Color.Transparent)
                .clickable { onPick(lora) }
                .padding(vertical = 6.dp, horizontal = 4.dp)
                .alpha(if (dimmed) 0.55f else 1f),
    ) {
        val candidates = remember(lora.path) { previewCandidates(lora) }
        ResourcePreview(
            candidates = candidates,
            modifier =
                Modifier
                    .padding(end = 10.dp)
                    .size(44.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    lora.title,
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                info?.baseLabel?.let { LoraBadge(it, fits) }
            }
            val tags =
                info
                    ?.tags
                    .orEmpty()
                    .take(3)
                    .joinToString(", ") { it.tag }
            if (tags.isNotEmpty()) {
                Text(
                    tags,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (active) Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun EmbeddingRows(
    embeddings: EmbeddingList,
    query: String,
    positivePrompt: String,
    negativePrompt: String,
    onAdd: (name: String, negative: Boolean) -> Unit,
) {
    val text = query.trim()
    val shown = remember(embeddings, text) { embeddings.all.filter { text.isEmpty() || it.contains(text, ignoreCase = true) } }
    if (shown.isEmpty()) {
        Text(
            if (embeddings.all.isEmpty()) "The server lists no embeddings." else "No matching embeddings.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp),
        )
    }
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        items(shown, key = { it }) { name ->
            val skipped = embeddings.isSkipped(name)
            val inPositive = PromptEdits.hasTag(positivePrompt, name)
            val inNegative = PromptEdits.hasTag(negativePrompt, name)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 4.dp).alpha(if (skipped) 0.55f else 1f),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            skipped -> "Not loaded: made for another model"
                            inNegative -> "In the negative prompt"
                            inPositive -> "In the prompt"
                            else -> "Not used yet"
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!skipped) {
                    PromptButton("Prompt", inPositive) { onAdd(name, false) }
                    Spacer(Modifier.width(6.dp))
                    PromptButton("Negative", inNegative) { onAdd(name, true) }
                }
            }
        }
    }
}

/** "Prompt" / "Negative": adds the name there; filled with a tick while it is there. */
@Composable
private fun PromptButton(
    text: String,
    present: Boolean,
    onClick: () -> Unit,
) {
    val padding = PaddingValues(horizontal = 10.dp)
    if (present) {
        FilledTonalButton(onClick = {}, contentPadding = padding, modifier = Modifier.heightIn(min = 32.dp)) {
            Text("$text ✓", fontSize = 13.sp)
        }
    } else {
        OutlinedButton(onClick = onClick, contentPadding = padding, modifier = Modifier.heightIn(min = 32.dp)) {
            Text(text, fontSize = 13.sp)
        }
    }
}

/**
 * A LoRA's trigger words under it in the card (board 1B): its most used training tags; a tap adds one to the prompt,
 * a tick marks the ones already there. Above them, a warning when it was made for another model.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LoraTriggers(
    info: LoraInfo?,
    modelType: ModelType,
    prompt: String,
    onAdd: (String) -> Unit,
) {
    if (info == null) return
    if (info.fits(modelType) == false) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = MISFIT_COLOR, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text("Made for ${info.baseLabel} · this model is ${modelType.label}", fontSize = 12.sp, color = MISFIT_COLOR)
        }
    }
    val tags = info.tags.take(TRIGGER_CHIPS)
    if (tags.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(bottom = 6.dp),
    ) {
        tags.forEach { tag -> TriggerChip(tag.tag, PromptEdits.hasTag(prompt, tag.tag)) { onAdd(tag.tag) } }
    }
}

private const val TRIGGER_CHIPS = 3
private const val DETAIL_TAGS = 8

/** A small chip: "+ tag" adds it, "✓ tag" is already in the prompt (compact, so three fit next to a LoRA). */
@Composable
private fun TriggerChip(
    tag: String,
    present: Boolean,
    label: String = tag,
    onClick: () -> Unit,
) = CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
    // Without the 48dp touch frame, which left wide gaps between two rows of chips.
    Surface(
        onClick = { if (!present) onClick() },
        shape = RoundedCornerShape(15.dp),
        color = if (present) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        contentColor = if (present) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        border = if (present) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        modifier = Modifier.heightIn(min = 30.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)) {
            Icon(if (present) Icons.Default.Check else Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, fontSize = 12.sp, maxLines = 1)
        }
    }
}

/** A LoRA's details (board 1B): its model, how it was trained and its most used training tags. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LoraDetailsSheet(
    title: String,
    info: LoraInfo?,
    previewCandidates: List<String>,
    modelType: ModelType,
    prompt: String,
    onAddTags: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ResourcePreview(
                    candidates = previewCandidates,
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                    placeholder = {
                        Icon(
                            Icons.Default.Image,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    info?.baseLabel?.let { Box(Modifier.padding(top = 4.dp)) { LoraBadge(it, info.fits(modelType)) } }
                }
            }
            if (info == null) {
                Text("Its file says nothing about its training.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            DetailRow("Made for", info.baseLabel ?: "Not stated")
            val trained = listOfNotNull(info.resolution, info.epochs?.let { if (it == 1) "1 epoch" else "$it epochs" }).joinToString(" · ")
            if (trained.isNotEmpty()) DetailRow("Trained at", trained)
            val tags = info.tags.take(DETAIL_TAGS)
            if (tags.isNotEmpty()) {
                Text(
                    "MOST USED IN TRAINING",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.96.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    tags.forEach { tag ->
                        TriggerChip(
                            tag.tag,
                            PromptEdits.hasTag(prompt, tag.tag),
                            label = "${tag.tag} · ${tag.count}",
                        ) { onAddTags(listOf(tag.tag)) }
                    }
                }
                val missing = tags.map { it.tag }.filterNot { PromptEdits.hasTag(prompt, it) }
                Button(onClick = { onAddTags(missing) }, enabled = missing.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Text(if (missing.isEmpty()) "All in the Prompt" else "Add All to Prompt")
                }
            }
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The styles chosen for the next jobs, in the prompt card (board 1C); only while "Server Styles" is on. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StylesRow(
    chosen: List<String>,
    onRemove: (String) -> Unit,
    onAdd: () -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Text(
            "Styles",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterVertically).padding(end = 2.dp),
        )
        chosen.forEach { name ->
            InputChip(
                selected = true,
                onClick = { onRemove(name) },
                label = { Text(name, fontSize = 13.sp) },
                trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove Style $name", modifier = Modifier.size(16.dp)) },
            )
        }
        TextButton(onClick = onAdd, modifier = Modifier.align(Alignment.CenterVertically)) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(if (chosen.isEmpty()) "Add Style" else "Add")
        }
    }
}

/** The server's styles to choose (board 1C): checked ones go with the jobs; "Paste into Prompt" writes them in. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StylesSheet(
    styles: List<PromptStyle>,
    chosen: List<String>,
    onChosen: (List<String>) -> Unit,
    onPaste: () -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(modifier = Modifier.padding(start = 20.dp, end = 12.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Styles", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        "${styles.size} saved on the server (styles.csv)",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = "Refresh Styles") }
            }
            if (styles.isEmpty()) {
                Text(
                    "The server has no saved styles. Save some in the web UI under the prompt.",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(styles, key = { it.name }) { style ->
                    val on = style.name in chosen
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onChosen(if (on) chosen - style.name else chosen + style.name) }
                                .padding(vertical = 4.dp),
                    ) {
                        Checkbox(checked = on, onCheckedChange = null, modifier = Modifier.padding(8.dp))
                        Column(modifier = Modifier.weight(1f).padding(top = 10.dp, bottom = 6.dp, end = 8.dp)) {
                            Text(style.name, fontSize = 15.sp)
                            PromptStyles.preview(style.prompt).takeIf { it.isNotEmpty() }?.let {
                                Text(
                                    "+ $it",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            PromptStyles.preview(style.negativePrompt).takeIf { it.isNotEmpty() }?.let {
                                Text(
                                    "− $it",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
            Text(
                "The server adds the chosen styles to every job; the prompt stays as you wrote it.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, end = 8.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 10.dp, end = 8.dp)) {
                OutlinedButton(
                    onClick = onPaste,
                    enabled = chosen.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) { Text("Paste into Prompt") }
                Button(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(if (chosen.isEmpty()) "Done" else "Done · ${chosen.size}")
                }
            }
        }
    }
}
