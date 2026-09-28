package com.example.forgegen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.forgegen.ui.components.ResourcePickerSheet
import com.example.forgegen.ui.components.countTokens
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

/* ============================================================================
 * MAIN SCREEN CARDS (3.0.0, the owner's pick "A")
 * The main screen in the style of the settings: blue labels over rounded cards of rows. PROMPT (the negative prompt
 * folds into a row), GENERATION (model, sampling, size and batch, hires fix) and LORAS, with the generate bar fixed at
 * the bottom. Which rows are open is kept in AppConfig.mainOpenRows.
 * ============================================================================ */

private val SAMPLING_TINT = Color(0xFF26C6DA)
private val SIZE_TINT = Color(0xFFA87BFF)
private val HIRES_TINT = Color(0xFFFFA726)

// The line between rows with a 40 dp picture starts under their text.
private val PICTURE_ROW_INDENT = 70.dp

// Forge reads a prompt in chunks of this many tokens.
private const val TOKEN_CHUNK = 75

/* ---------------------------------------------------------------------------
 * Building blocks
 * --------------------------------------------------------------------------- */

/** A section's name above its card, with an optional action on the right ("Recent", "Add"). */
@Composable
fun MainSectionLabel(
    text: String,
    actionText: String? = null,
    actionIcon: ImageVector? = null,
    onAction: () -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(start = 16.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text.uppercase(),
            color = MaterialTheme.colorScheme.primary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            modifier = Modifier.weight(1f),
        )
        if (actionText != null) {
            TextButton(
                onClick = onAction,
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.height(36.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.secondary),
            ) {
                if (actionIcon != null) {
                    Icon(actionIcon, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(actionText, fontSize = 13.sp)
            }
        }
    }
}

/** Rows in one rounded card, as on the settings pages. */
@Composable
fun MainCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(content = content)
    }
}

@Composable
fun CardDivider(indent: Dp = 16.dp) {
    HorizontalDivider(modifier = Modifier.padding(start = indent), color = MaterialTheme.colorScheme.surfaceVariant)
}

/** A row's coloured icon on a tile of its colour (the settings' category icons). */
@Composable
private fun RowIcon(
    icon: ImageVector,
    tint: Color,
) {
    Box(
        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(tint.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** A model's or LoRA's preview in a row (a plain tile while it loads or when there is none). */
@Composable
private fun ResourceThumb(url: String?) {
    val context = LocalContext.current
    Box(
        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNullOrEmpty()) {
            Icon(
                Icons.Default.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                modifier = Modifier.size(20.dp),
            )
        } else {
            AsyncImage(
                model =
                    remember(url) {
                        ImageRequest
                            .Builder(context)
                            .data(url)
                            .size(150)
                            .crossfade(true)
                            .build()
                    },
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun CardRow(
    title: String,
    subtitle: String?,
    onClick: (() -> Unit)?,
    leading: (@Composable () -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(14.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(
                    subtitle,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

@Composable
private fun ExpandArrow(expanded: Boolean) {
    val turn by animateFloatAsState(if (expanded) 180f else 0f, label = "arrow")
    Icon(
        Icons.Default.KeyboardArrowDown,
        contentDescription = if (expanded) "Collapse" else "Expand",
        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
        modifier = Modifier.rotate(turn),
    )
}

@Composable
private fun RowArrowRight() {
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
    )
}

/** A row that opens its settings under it; its summary shows while it is closed. */
@Composable
private fun ExpandableRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    CardRow(
        title = title,
        subtitle = if (expanded) null else summary,
        onClick = onToggle,
        leading = { RowIcon(icon, tint) },
        trailing = { ExpandArrow(expanded) },
    )
    AnimatedVisibility(visible = expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = PICTURE_ROW_INDENT, end = 16.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** A choice that opens a list: "Sampler … Euler a ›". */
@Composable
private fun PickerField(
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onClick)
                .padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
        RowArrowRight()
    }
}

@Composable
private fun ValuePill(text: String) {
    Text(
        text,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.secondary,
        modifier =
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(horizontal = 10.dp, vertical = 2.dp),
    )
}

/** A slider with its name and value above it; [round] snaps what the finger gives to the value's steps. */
@Composable
private fun ValueSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    text: String,
    round: (Float) -> Float,
    onChange: (Float) -> Unit,
    onChangeFinished: (() -> Unit)? = null,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
            ValuePill(text)
        }
        Slider(
            value = value.coerceIn(range),
            onValueChange = { onChange(round(it)) },
            onValueChangeFinished = onChangeFinished,
            valueRange = range,
        )
    }
}

private fun roundStep(
    value: Float,
    step: Float,
): Float = (value / step).roundToInt() * step

/** Choices side by side in one pill (clip skip, a model's type). */
@Composable
private fun <T> SegmentedChoice(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    fill: Boolean = false,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(2.dp),
    ) {
        options.forEach { option ->
            val on = option == selected
            Box(
                modifier =
                    Modifier
                        .then(if (fill) Modifier.weight(1f) else Modifier.widthIn(min = 40.dp))
                        .height(32.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .clickable { onSelect(option) }
                        .padding(horizontal = if (fill) 2.dp else 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    fontSize = if (fill) 13.sp else 14.sp,
                    maxLines = 1,
                    softWrap = false,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                )
            }
        }
    }
}

/** An icon button of a prompt's footer (Undo, Copy, Clear). */
@Composable
fun FieldIconButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(36.dp),
        colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(18.dp))
    }
}

/** "41 / 75 tokens"; past 75 the next chunk: "90 / 150 tokens". */
@Composable
fun TokenCount(prompt: String) {
    val tokens = remember(prompt) { countTokens(prompt) }
    val limit = maxOf(TOKEN_CHUNK, (tokens + TOKEN_CHUNK - 1) / TOKEN_CHUNK * TOKEN_CHUNK)
    Text("$tokens / $limit tokens", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
}

/** A plain list to pick one of (samplers, schedules, upscalers, VAEs). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptionPickerSheet(
    title: String,
    options: List<String>,
    selected: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        if (options.isEmpty()) {
            Text(
                "Not loaded from the server yet.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
        }
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(options) { option ->
                val on = option == selected
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onPick(option) }.padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        option,
                        fontSize = 15.sp,
                        color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.weight(1f),
                    )
                    if (on) Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/* ---------------------------------------------------------------------------
 * PROMPT
 * --------------------------------------------------------------------------- */

/**
 * The positive prompt with its footer (tokens, Undo, Redo, Copy, Clear) and the negative prompt folded into a row.
 * [resetKey] changes when the prompts are reset, which also forgets the tags switched off in them.
 */
@Composable
fun PromptCard(
    viewModel: ForgeViewModel,
    state: AppState,
    config: AppConfig,
    promptHistory: List<PromptHistoryItem>,
    openRows: List<String>,
    onToggleRow: (String) -> Unit,
    resetKey: Int,
) {
    var disabledPosTags by remember(resetKey) { mutableStateOf(emptySet<String>()) }
    var disabledNegTags by remember(resetKey) { mutableStateOf(emptySet<String>()) }
    var showRecent by remember { mutableStateOf(false) }
    val context = LocalContext.current

    fun copy(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Prompt", text))
        viewModel.showToast("Prompt Copied")
    }

    MainSectionLabel(
        "Prompt",
        actionText = if (promptHistory.isNotEmpty()) "Recent" else null,
        actionIcon = Icons.Default.History,
        onAction = { showRecent = true },
    )
    MainCard {
        HybridPromptEditor(
            prompt = state.positivePrompt,
            onPromptChange = { viewModel.updateState { s -> s.copy(positivePrompt = it) } },
            disabledTags = disabledPosTags,
            onDisabledTagsChange = { disabledPosTags = it },
            placeholder = "Positive prompt",
            showTagEditor = config.showActiveTagsUI,
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 4.dp),
        ) {
            FieldIconButton(Icons.Default.ContentCopy, "Copy Prompt", enabled = state.positivePrompt.isNotEmpty()) {
                copy(state.positivePrompt)
            }
            FieldIconButton(Icons.Default.Close, "Clear", enabled = state.positivePrompt.isNotEmpty()) {
                viewModel.updateState { s -> s.copy(positivePrompt = "") }
                disabledPosTags = emptySet()
            }
        }
        CardDivider()
        val negativeOpen = MainRows.NEGATIVE in openRows
        CardRow(
            title = "Negative",
            subtitle = if (negativeOpen) null else state.negativePrompt.ifBlank { "None" },
            onClick = { onToggleRow(MainRows.NEGATIVE) },
            trailing = { ExpandArrow(negativeOpen) },
        )
        AnimatedVisibility(visible = negativeOpen, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            HybridPromptEditor(
                prompt = state.negativePrompt,
                onPromptChange = { viewModel.updateState { s -> s.copy(negativePrompt = it) } },
                disabledTags = disabledNegTags,
                onDisabledTagsChange = { disabledNegTags = it },
                placeholder = "Negative prompt",
                showTagEditor = config.showActiveTagsUI,
                modifier = Modifier.padding(start = 16.dp, end = 8.dp, bottom = 4.dp),
            ) {
                FieldIconButton(Icons.Default.ContentCopy, "Copy Negative Prompt", enabled = state.negativePrompt.isNotEmpty()) {
                    copy(state.negativePrompt)
                }
                FieldIconButton(Icons.Default.Close, "Clear Negative Prompt", enabled = state.negativePrompt.isNotEmpty()) {
                    viewModel.updateState { s -> s.copy(negativePrompt = "") }
                    disabledNegTags = emptySet()
                }
            }
        }
    }

    if (showRecent) {
        RecentPromptsSheet(
            history = promptHistory,
            onPick = { item ->
                viewModel.updateState { it.copy(positivePrompt = item.positivePrompt, negativePrompt = item.negativePrompt) }
                disabledPosTags = emptySet()
                disabledNegTags = emptySet()
                showRecent = false
            },
            onDismiss = { showRecent = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecentPromptsSheet(
    history: List<PromptHistoryItem>,
    onPick: (PromptHistoryItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val timeFormat = remember(locale) { SimpleDateFormat("MMM dd, HH:mm", locale) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(
            "Recent Prompts",
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(history) { item ->
                Column(modifier = Modifier.fillMaxWidth().clickable { onPick(item) }.padding(horizontal = 24.dp, vertical = 10.dp)) {
                    Text(
                        timeFormat.format(item.timestamp),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                    Text(item.positivePrompt, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (item.negativePrompt.isNotBlank()) {
                        Text(
                            item.negativePrompt,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/* ---------------------------------------------------------------------------
 * GENERATION
 * --------------------------------------------------------------------------- */

@Composable
fun GenerationCard(
    viewModel: ForgeViewModel,
    state: AppState,
    config: AppConfig,
    models: List<ApiResource>,
    selectedModel: String,
    samplers: List<String>,
    schedulers: List<String>,
    upscalers: List<String>,
    modules: List<ServerModule>,
    moduleSupport: ModuleSupport,
    openRows: List<String>,
    onToggleRow: (String) -> Unit,
) {
    val modelResource = models.find { it.name == selectedModel || it.title == selectedModel }
    val modelTitle = modelResource?.title ?: selectedModel
    val settings = ModelSettingsRules.of(config.modelSettings, modelTitle)
    val type = settings.modelType
    var pickModel by remember { mutableStateOf(false) }
    var tuneModel by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<String?>(null) }

    MainSectionLabel("Generation")
    MainCard {
        // --- Model ---
        CardRow(
            title = if (modelTitle.isEmpty()) "No Model Loaded" else ModelSettingsRules.key(modelTitle),
            subtitle = ModelSettingsRules.summary(settings),
            onClick = { pickModel = true },
            leading = { ResourceThumb(modelResource?.let { remember(it.path) { viewModel.getPreviewUrl(it.path, isLora = false) } }) },
            trailing = {
                IconButton(onClick = { tuneModel = true }, enabled = modelTitle.isNotEmpty()) {
                    Icon(Icons.Default.Layers, contentDescription = "Model Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
        CardDivider(PICTURE_ROW_INDENT)

        // --- Sampling ---
        val samplingSummary =
            buildList {
                add(state.sampler)
                add(state.scheduler)
                add("${state.steps} steps")
                add("CFG ${ModelSettingsRules.formatCfg(state.cfgScale)}")
                if (type == ModelType.FLUX) add("distilled ${ModelSettingsRules.formatCfg(settings.distilledCfg)}")
                if (state.clipSkip > 1) add("clip skip ${state.clipSkip}")
            }.joinToString(" · ")
        ExpandableRow(
            icon = Icons.Default.Tune,
            tint = SAMPLING_TINT,
            title = "Sampling",
            summary = samplingSummary,
            expanded = MainRows.SAMPLING in openRows,
            onToggle = { onToggleRow(MainRows.SAMPLING) },
        ) {
            PickerField("Sampler", state.sampler) { picking = "Sampler" }
            PickerField("Schedule", state.scheduler) { picking = "Schedule" }
            ValueSlider("Steps", state.steps.toFloat(), 1f..100f, "${state.steps}", { it.roundToInt().toFloat() }, { v ->
                viewModel.updateState { it.copy(steps = v.toInt()) }
            })
            ValueSlider("CFG Scale", state.cfgScale, 1f..20f, ModelSettingsRules.formatCfg(state.cfgScale), { roundStep(it, 0.5f) }, { v ->
                viewModel.updateState { it.copy(cfgScale = v) }
            })
            if (type == ModelType.FLUX) {
                // Kept with the model; saved when the finger lifts, not at every step.
                var distilled by remember(settings.distilledCfg) { mutableFloatStateOf(settings.distilledCfg) }
                ValueSlider(
                    "Distilled CFG",
                    distilled,
                    1f..10f,
                    ModelSettingsRules.formatCfg(distilled),
                    { roundStep(it, 0.1f) },
                    { distilled = it },
                    onChangeFinished = { viewModel.updateModelSettings(modelTitle) { it.copy(distilledCfg = distilled) } },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Clip skip", fontSize = 14.sp, modifier = Modifier.weight(1f))
                SegmentedChoice(listOf(1, 2, 3), state.clipSkip, { "$it" }) { v -> viewModel.updateState { it.copy(clipSkip = v) } }
            }
        }
        CardDivider(PICTURE_ROW_INDENT)

        // --- Size & Batch ---
        val images = state.batchCount * state.batchSize
        val imagesText =
            when {
                state.batchSize > 1 -> "${state.batchCount}×${state.batchSize} images"
                images == 1 -> "1 image"
                else -> "$images images"
            }
        ExpandableRow(
            icon = Icons.Default.AspectRatio,
            tint = SIZE_TINT,
            title = "Size & Batch",
            summary = "${state.width}×${state.height} · $imagesText · seed ${if (state.seed == -1L) "random" else state.seed}",
            expanded = MainRows.SIZE in openRows,
            onToggle = { onToggleRow(MainRows.SIZE) },
        ) {
            val large = SizePresets.isLarge(type, state.width, state.height)
            // The ratios side by side (none lit after the sliders set a size of their own), then portrait/landscape.
            Row(verticalAlignment = Alignment.CenterVertically) {
                SegmentedChoice(SizePresets.RATIOS, state.aspectRatio, { it }, Modifier.weight(1f), fill = true) { ratio ->
                    viewModel.updateState { SizePresets.apply(it, ratio, large) }
                }
                IconButton(onClick = { viewModel.updateState { it.withSwappedSize() } }) {
                    Icon(
                        Icons.Default.SwapHoriz,
                        contentDescription = "Swap Width and Height",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            ValueSlider("Width", state.width.toFloat(), 256f..2048f, "${state.width}", { roundStep(it, 64f) }, { v ->
                viewModel.updateState { it.copy(width = v.toInt(), aspectRatio = "Custom") }
            })
            ValueSlider("Height", state.height.toFloat(), 256f..2048f, "${state.height}", { roundStep(it, 64f) }, { v ->
                viewModel.updateState { it.copy(height = v.toInt(), aspectRatio = "Custom") }
            })
            ValueSlider("Batch count", state.batchCount.toFloat(), 1f..100f, "${state.batchCount}", { it.roundToInt().toFloat() }, { v ->
                viewModel.updateState { it.copy(batchCount = v.toInt()) }
            })
            ValueSlider("Batch size", state.batchSize.toFloat(), 1f..16f, "${state.batchSize}", { it.roundToInt().toFloat() }, { v ->
                viewModel.updateState { it.copy(batchSize = v.toInt()) }
            })
            SeedField(viewModel, state.seed)
        }
        CardDivider(PICTURE_ROW_INDENT)

        // --- Hires fix ---
        CardRow(
            title = "Hires fix",
            subtitle =
                if (state.hiresFix) {
                    "${state.upscaler} · ×${ModelSettingsRules.formatCfg(state.hiresScale)} · " +
                        "denoise ${String.format(Locale.US, "%.2f", state.denoising)}"
                } else {
                    "Off"
                },
            onClick = { viewModel.updateState { it.copy(hiresFix = !it.hiresFix) } },
            leading = { RowIcon(Icons.Default.OpenInFull, HIRES_TINT) },
            trailing = { Switch(checked = state.hiresFix, onCheckedChange = null) },
        )
        AnimatedVisibility(visible = state.hiresFix, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = PICTURE_ROW_INDENT, end = 16.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PickerField("Upscaler", state.upscaler) { picking = "Upscaler" }
                ValueSlider("Upscale by", state.hiresScale, 1f..4f, "×${ModelSettingsRules.formatCfg(state.hiresScale)}", {
                    roundStep(it, 0.05f)
                }, { v -> viewModel.updateState { it.copy(hiresScale = v) } })
                ValueSlider("Denoising", state.denoising, 0f..1f, String.format(Locale.US, "%.2f", state.denoising), {
                    roundStep(it, 0.01f)
                }, { v -> viewModel.updateState { it.copy(denoising = v) } })
            }
        }
    }

    // --- Pickers ---
    if (pickModel) {
        ResourcePickerSheet(
            title = "Model",
            items = models,
            isSelected = { it.name == selectedModel || it.title == selectedModel },
            previewUrl = { viewModel.getPreviewUrl(it.path, isLora = false) },
            onPick = {
                viewModel.changeCheckpoint(it.name)
                pickModel = false
            },
            onDismiss = { pickModel = false },
            onRefresh = { viewModel.refreshCheckpoints() },
        )
    }
    if (tuneModel && modelTitle.isNotEmpty()) {
        ModelSettingsSheet(
            model = modelTitle,
            settings = settings,
            modules = modules,
            support = moduleSupport,
            onChange = { transform -> viewModel.updateModelSettings(modelTitle, transform) },
            onSaveDefaults = { turnOn -> viewModel.saveModelDefaults(modelTitle, turnOn) },
            onApplyDefaults = { defaults -> viewModel.updateState { ModelSettingsRules.applyDefaults(it, defaults) } },
            onDismiss = { tuneModel = false },
        )
    }
    when (picking) {
        "Sampler" ->
            OptionPickerSheet("Sampler", samplers, state.sampler, onPick = { v ->
                viewModel.updateState { it.copy(sampler = v) }
                picking = null
            }, onDismiss = { picking = null })
        "Schedule" ->
            OptionPickerSheet("Schedule", schedulers, state.scheduler, onPick = { v ->
                viewModel.updateState { it.copy(scheduler = v) }
                picking = null
            }, onDismiss = { picking = null })
        "Upscaler" ->
            OptionPickerSheet("Upscaler", upscalers, state.upscaler, onPick = { v ->
                viewModel.updateState { it.copy(upscaler = v) }
                picking = null
            }, onDismiss = { picking = null })
    }
}

/** The seed, typed or random, with the dice menu (random, the last image's seed). */
@Composable
private fun SeedField(
    viewModel: ForgeViewModel,
    seed: Long,
) {
    // The field keeps its own text: deriving it from the seed turned an emptied field (or a lone "-") straight back
    // into "-1", so a new seed could not be typed from scratch.
    var seedText by remember { mutableStateOf(if (seed == -1L) "" else seed.toString()) }
    LaunchedEffect(seed) {
        // Follow outside changes (dice, recovered seed, preset) without fighting the user's typing.
        if ((seedText.toLongOrNull() ?: -1L) != seed) seedText = if (seed == -1L) "" else seed.toString()
    }
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Seed", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
        Spacer(Modifier.width(12.dp))
        BasicTextField(
            value = seedText,
            onValueChange = {
                if (it.isEmpty() || it == "-" || it.toLongOrNull() != null) {
                    seedText = it
                    viewModel.updateState { s -> s.copy(seed = it.toLongOrNull() ?: -1L) }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = TextStyle(fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.End),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.weight(1f),
            decorationBox = { field ->
                Box(contentAlignment = Alignment.CenterEnd) {
                    if (seedText.isEmpty()) {
                        Text("Random", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f))
                    }
                    field()
                }
            },
        )
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Default.Casino, contentDescription = "Seed Options", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Random (-1)") },
                    onClick = {
                        viewModel.updateState { it.copy(seed = -1L) }
                        menu = false
                    },
                )
                DropdownMenuItem(
                    text = { Text("Recover Last Seed") },
                    onClick = {
                        viewModel.recoverLastSeed()
                        menu = false
                    },
                )
            }
        }
    }
}

/**
 * What the checkpoint is and what it needs (3.0.0, the owner's idea 4): its type, the VAE of SD, the VAE and text
 * encoders of FLUX; and its own defaults, used only when switched on (idea 6).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSettingsSheet(
    model: String,
    settings: ModelSettings,
    modules: List<ServerModule>,
    support: ModuleSupport,
    onChange: ((ModelSettings) -> ModelSettings) -> Unit,
    onSaveDefaults: (turnOn: Boolean) -> Unit,
    onApplyDefaults: (ModelDefaults) -> Unit,
    onDismiss: () -> Unit,
) {
    val type = settings.modelType
    var pickVae by remember { mutableStateOf(false) }
    var pickEncoders by remember { mutableStateOf(false) }
    val vaes = remember(modules) { ModelSettingsRules.vaes(modules) }
    val encoders = remember(modules) { ModelSettingsRules.textEncoders(modules) }
    val sendsModules = support == ModuleSupport.FORGE || (support == ModuleSupport.A1111 && type == ModelType.SD)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
        ) {
            Text(
                ModelSettingsRules.key(model),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Text(
                "What this checkpoint is and what it needs",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            MainSectionLabel("Type")
            MainCard {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SegmentedChoice(ModelType.entries, type, { it.label }, Modifier.fillMaxWidth(), fill = true) { picked ->
                        onChange { it.copy(type = picked.name) }
                    }
                    Text(
                        when (type) {
                            ModelType.AUTO -> "Sends no modules: the server keeps what is set in its own UI."
                            ModelType.SD -> "Sends the VAE below with every job."
                            ModelType.SDXL -> "Uses the VAE built into the checkpoint; no modules are sent."
                            ModelType.FLUX -> "Sends the VAE and text encoders below with every job, and the distilled CFG."
                        },
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    )
                }
            }

            if (type == ModelType.SD || type == ModelType.FLUX) {
                MainSectionLabel("Modules")
                MainCard {
                    when {
                        support == ModuleSupport.NONE ->
                            CardRow(title = "No Modules", subtitle = "The server lists no VAEs or text encoders", onClick = null)
                        !sendsModules ->
                            CardRow(title = "Not Supported", subtitle = "This server takes a VAE only for SD models", onClick = null)
                        else -> {
                            CardRow(
                                title = "VAE",
                                subtitle = settings.vae?.let { ModelSettingsRules.key(it) } ?: "Built in",
                                onClick = { pickVae = true },
                                trailing = { RowArrowRight() },
                            )
                            if (type == ModelType.FLUX) {
                                CardDivider()
                                CardRow(
                                    title = "Text Encoders",
                                    subtitle =
                                        settings.textEncoders
                                            .joinToString(", ") { ModelSettingsRules.key(it) }
                                            .ifEmpty { "None picked" },
                                    onClick = { pickEncoders = true },
                                    trailing = { RowArrowRight() },
                                )
                            }
                        }
                    }
                }
            }

            MainSectionLabel("Defaults")
            MainCard {
                val defaults = settings.defaults
                SwitchPreference(
                    title = "Use Model Defaults",
                    subtitle = "Set the saved size, steps, CFG, sampler, schedule and clip skip when this model is picked",
                    checked = settings.useDefaults && defaults != null,
                    onCheckedChange = { on ->
                        if (on && defaults == null) onSaveDefaults(true) else onChange { it.copy(useDefaults = on) }
                    },
                )
                CardDivider()
                TextPreference(
                    title = "Save Current Settings",
                    subtitle = defaults?.let { ModelSettingsRules.describe(it) } ?: "Nothing saved yet",
                    trailing = null,
                ) { onSaveDefaults(false) }
                if (defaults != null) {
                    CardDivider()
                    TextPreference(title = "Apply Defaults Now", trailing = null) { onApplyDefaults(defaults) }
                }
            }
        }
    }

    if (pickVae) {
        val builtIn = "Built in"
        OptionPickerSheet(
            title = "VAE",
            options = listOf(builtIn) + vaes,
            selected = settings.vae ?: builtIn,
            onPick = { picked ->
                onChange { it.copy(vae = picked.takeIf { name -> name != builtIn }) }
                pickVae = false
            },
            onDismiss = { pickVae = false },
        )
    }
    if (pickEncoders) {
        ModalBottomSheet(
            onDismissRequest = { pickEncoders = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Text(
                "Text Encoders",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            if (encoders.isEmpty()) {
                Text(
                    "The server lists no text encoders.",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(encoders) { name ->
                    val on = name in settings.textEncoders
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onChange { it.copy(textEncoders = if (on) it.textEncoders - name else it.textEncoders + name) }
                                }.padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = on, onCheckedChange = null, modifier = Modifier.padding(8.dp))
                        Text(name, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/* ---------------------------------------------------------------------------
 * LORAS
 * --------------------------------------------------------------------------- */

@Composable
fun LorasCard(
    viewModel: ForgeViewModel,
    availableLoras: List<ApiResource>,
    activeLoras: List<ActiveLora>,
) {
    var pickLora by remember { mutableStateOf(false) }
    MainSectionLabel(
        if (activeLoras.isEmpty()) "LoRAs" else "LoRAs · ${activeLoras.size}",
        actionText = "Add",
        actionIcon = Icons.Default.Add,
        onAction = { pickLora = true },
    )
    MainCard {
        if (activeLoras.isEmpty()) {
            CardRow(title = "No LoRAs", subtitle = "Added ones go into the prompt with their strength", onClick = { pickLora = true })
        }
        activeLoras.forEachIndexed { index, lora ->
            if (index > 0) CardDivider(PICTURE_ROW_INDENT)
            val resource = availableLoras.find { it.name == lora.name }
            val title = resource?.title ?: lora.name
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ResourceThumb(resource?.let { remember(it.path) { viewModel.getPreviewUrl(it.path, isLora = true) } })
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(
                            String.format(Locale.US, "%.2f", lora.strength),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    Slider(
                        value = lora.strength.coerceIn(0.1f, 2f),
                        onValueChange = { viewModel.updateLoraStrength(lora.name, roundStep(it, 0.05f)) },
                        valueRange = 0.1f..2f,
                    )
                }
                IconButton(onClick = { viewModel.removeLora(lora.name) }) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Remove $title",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }

    if (pickLora) {
        ResourcePickerSheet(
            title = "LoRA",
            items = availableLoras,
            isSelected = { lora -> activeLoras.any { it.name == lora.name } },
            previewUrl = { viewModel.getPreviewUrl(it.path, isLora = true) },
            onPick = {
                viewModel.addLora(it.name)
                pickLora = false
            },
            onDismiss = { pickLora = false },
            onRefresh = { viewModel.refreshLoras() },
        )
    }
}

/* ---------------------------------------------------------------------------
 * GENERATE BAR
 * --------------------------------------------------------------------------- */

/**
 * Fixed at the bottom of the main screen: the queue, Stop while generating, Add to Queue (filled as the image
 * progresses) and ⋯ with the presets, the last prompt, resetting, and where images are saved.
 */
@Composable
fun GenerateBar(
    viewModel: ForgeViewModel,
    state: AppState,
    queueSize: Int,
    isActivelyGenerating: Boolean,
    progress: Float,
    currentEta: Double,
    onQueueClick: () -> Unit,
    onPresetsClick: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val barShape = RoundedCornerShape(16.dp)
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarButton(onClick = onQueueClick, modifier = Modifier.widthIn(min = 76.dp), description = "Queue, $queueSize jobs") {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text("$queueSize", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            if (isActivelyGenerating) {
                BarButton(
                    onClick = { viewModel.interruptGeneration() },
                    color = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                    description = "Interrupt",
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                }
            }
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .height(52.dp)
                        .clip(barShape)
                        .background(
                            if (isActivelyGenerating) {
                                MaterialTheme.colorScheme.primary.copy(
                                    alpha = 0.35f,
                                )
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        ).clickable { viewModel.queueGeneration() },
                contentAlignment = Alignment.Center,
            ) {
                if (isActivelyGenerating) {
                    Box(
                        modifier =
                            Modifier
                                .align(Alignment.CenterStart)
                                .fillMaxHeight()
                                .fillMaxWidth(progress.coerceIn(0f, 1f))
                                .background(MaterialTheme.colorScheme.primary),
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (isActivelyGenerating) "Add to Queue · ${(progress * 100).toInt()}%" else "Add to Queue",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    if (isActivelyGenerating) {
                        Text(
                            "ETA ${String.format(Locale.US, "%.1f", currentEta)} s",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                        )
                    }
                }
            }
            Box {
                BarButton(onClick = { menu = true }, description = "More: presets, restore last, save options") {
                    Icon(Icons.Default.MoreVert, contentDescription = null)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Presets") },
                        leadingIcon = { Icon(Icons.Default.Bookmarks, contentDescription = null) },
                        onClick = {
                            menu = false
                            onPresetsClick()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Restore Last") },
                        leadingIcon = { Icon(Icons.Default.Restore, contentDescription = null) },
                        onClick = {
                            menu = false
                            viewModel.recoverLastPrompt()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Reset to Defaults") },
                        leadingIcon = { Icon(Icons.Default.RestartAlt, contentDescription = null) },
                        onClick = {
                            menu = false
                            onReset()
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Save on Server") },
                        leadingIcon = {
                            Icon(
                                if (state.saveImages) Icons.Default.CloudDone else Icons.Default.CloudOff,
                                contentDescription = null,
                            )
                        },
                        trailingIcon = { Checkbox(checked = state.saveImages, onCheckedChange = null) },
                        onClick = { viewModel.updateState { it.copy(saveImages = !it.saveImages) } },
                    )
                    DropdownMenuItem(
                        text = { Text("Save to Phone") },
                        leadingIcon = { Icon(Icons.Default.PhoneAndroid, contentDescription = null) },
                        trailingIcon = { Checkbox(checked = state.saveToDevice, onCheckedChange = null) },
                        onClick = { viewModel.updateState { it.copy(saveToDevice = !it.saveToDevice) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun BarButton(
    onClick: () -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = color,
        contentColor = contentColor,
        modifier = modifier.height(52.dp).widthIn(min = 52.dp).semantics { contentDescription = description },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}
