package com.example.forgegen.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.forgegen.ForgeGalleryManager
import com.example.forgegen.ForgeViewModel
import com.example.forgegen.ImageJobs
import java.util.Locale
import kotlin.math.roundToInt

/* ============================================================================
 * JOBS FROM GALLERY IMAGES: "UPSCALE" AND "VARIANCE ON SEED" (tabs for the selected images; the second since 3.0.0),
 * "MORE LIKE THIS" (the viewer's)
 * The dialogs that turn gallery images into queue jobs (ImageJobs): the options, what the jobs will make, and
 * which images are left out and why. [onQueued] runs once the jobs are in the queue (the gallery then clears its
 * selection).
 * ============================================================================ */
@Composable
fun ImageJobsDialog(
    viewModel: ForgeViewModel,
    onQueued: () -> Unit = {},
) {
    val request by viewModel.imageJobs.collectAsStateWithLifecycle()
    val current = request ?: return
    when (current.kind) {
        ImageJobs.Kind.UPSCALE, ImageJobs.Kind.VARIANCE -> SelectionJobsDialog(viewModel, current, onQueued)
        ImageJobs.Kind.MORE_LIKE_THIS -> MoreLikeThisDialog(viewModel, current, onQueued)
    }
}

@Composable
private fun Loading() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Reading the generation data…", fontSize = 13.sp)
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun Label(text: String) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Note(text: String) {
    Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
}

/**
 * Images selected in the gallery: one dialog with two tabs, "Upscale" and "Variance on Seed" (3.0.0), sharing the
 * images' data; each tab keeps its options while the other is open.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun SelectionJobsDialog(
    viewModel: ForgeViewModel,
    request: ForgeGalleryManager.ImageJobsRequest,
    onQueued: () -> Unit,
) {
    val state by viewModel.appState.collectAsStateWithLifecycle()
    val upscalers by viewModel.upscalers.collectAsStateWithLifecycle()
    val latentModes by viewModel.latentModes.collectAsStateWithLifecycle()
    // Upscale.
    var scale by remember { mutableFloatStateOf(ImageJobs.DEFAULT_SCALE) }
    // The upscaler of the hires fix settings, as the owner asked.
    var upscaler by remember { mutableStateOf(state.upscaler) }
    var denoising by remember { mutableFloatStateOf(ImageJobs.DEFAULT_DENOISING) }
    // Variance on Seed: the +/- of each LoRA (by name), of the CFG and of the steps.
    var loraSpreads by remember { mutableStateOf(mapOf<String, Float>()) }
    var loraStep by remember { mutableFloatStateOf(ImageJobs.DEFAULT_LORA_STEP) }
    var cfgPlus by remember { mutableFloatStateOf(0f) }
    var stepsPlus by remember { mutableFloatStateOf(0f) }

    val sources = request.sources
    val ready = sources.orEmpty().filterIsInstance<ImageJobs.Source.Ready>()
    val upscaled = ready.filter { (it.hiresScale ?: 1f) < scale }
    val spec =
        ImageJobs.VarianceSpec(
            loras = loraSpreads.mapValues { (_, plus) -> ImageJobs.Spread(plus, loraStep) },
            cfg = ImageJobs.Spread(cfgPlus, ImageJobs.CFG_STEP),
            steps = ImageJobs.Spread(stepsPlus, ImageJobs.STEPS_STEP),
        )
    val varianceJobs = remember(ready, spec) { ImageJobs.varianceCount(ready, spec) }
    val variance = request.kind == ImageJobs.Kind.VARIANCE

    AlertDialog(
        onDismissRequest = { viewModel.dismissImageJobs() },
        title = { Text(if (request.count == 1) "1 Image" else "${request.count} Images") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PrimaryTabRow(selectedTabIndex = if (variance) 1 else 0, containerColor = Color.Transparent) {
                    Tab(
                        selected = !variance,
                        onClick = { viewModel.setImageJobsKind(ImageJobs.Kind.UPSCALE) },
                        text = { Text("Upscale") },
                    )
                    Tab(
                        selected = variance,
                        onClick = { viewModel.setImageJobsKind(ImageJobs.Kind.VARIANCE) },
                        text = { Text("Variance on Seed") },
                    )
                }
                if (sources == null) {
                    Loading()
                    return@Column
                }
                if (variance) {
                    VarianceOptions(
                        ready = ready,
                        loraSpreads = loraSpreads,
                        onLoraSpread = { name, plus -> loraSpreads = loraSpreads + (name to plus) },
                        loraStep = loraStep,
                        onLoraStep = { loraStep = it },
                        cfgPlus = cfgPlus,
                        onCfgPlus = { cfgPlus = it },
                        stepsPlus = stepsPlus,
                        onStepsPlus = { stepsPlus = it },
                        jobs = varianceJobs,
                    )
                    SkippedNotes(sources.filterIsInstance<ImageJobs.Source.Skipped>().map { it.reason })
                } else {
                    UpscaleOptions(
                        scale = scale,
                        onScale = { scale = it },
                        upscalers = upscalers,
                        latentModes = latentModes,
                        upscaler = upscaler,
                        onUpscaler = { upscaler = it },
                        denoising = denoising,
                        onDenoising = { denoising = it },
                        queued = upscaled,
                    )
                    // What is left out, and why.
                    SkippedNotes(
                        sources.mapNotNull { source ->
                            when (source) {
                                is ImageJobs.Source.Skipped -> source.reason
                                is ImageJobs.Source.Ready ->
                                    source.hiresScale?.takeIf { it >= scale }?.let { "already upscaled ×${ImageJobs.scaleText(it)}" }
                            }
                        },
                    )
                }
            }
        },
        confirmButton = {
            if (variance) {
                Button(
                    onClick = {
                        viewModel.queueVariance(spec)
                        onQueued()
                    },
                    enabled = varianceJobs in 1..ImageJobs.MAX_VARIANCE_JOBS,
                ) { Text(if (varianceJobs in 1..ImageJobs.MAX_VARIANCE_JOBS) "Add $varianceJobs to Queue" else "Add to Queue") }
            } else {
                Button(
                    onClick = {
                        viewModel.queueUpscales(scale, upscaler, denoising)
                        onQueued()
                    },
                    enabled = upscaled.isNotEmpty(),
                ) { Text(if (sources == null) "Add to Queue" else "Add ${upscaled.size} to Queue") }
            }
        },
        dismissButton = { TextButton(onClick = { viewModel.dismissImageJobs() }) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UpscaleOptions(
    scale: Float,
    onScale: (Float) -> Unit,
    upscalers: List<String>,
    latentModes: List<String>,
    upscaler: String,
    onUpscaler: (String) -> Unit,
    denoising: Float,
    onDenoising: (Float) -> Unit,
    queued: List<ImageJobs.Source.Ready>,
) {
    Note("Each image is made again with its own seed and settings plus hires fix, one job after another in the queue.")

    Label("Scale")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ImageJobs.UPSCALE_SCALES.forEach { s ->
            FilterChip(selected = scale == s, onClick = { onScale(s) }, label = { Text("×${ImageJobs.scaleText(s)}") })
        }
    }

    Label("Upscaler")
    UpscalerPicker(latentModes = latentModes, upscalers = upscalers, selected = upscaler, onSelect = onUpscaler)

    Label("Denoising: ${"%.2f".format(Locale.US, denoising)}")
    Slider(
        value = denoising,
        onValueChange = { onDenoising((it * 20).roundToInt() / 20f) },
        valueRange = 0.1f..0.7f,
        steps = 11,
    )

    val sizes = queued.map { it.baseWidth to it.baseHeight }.distinct()
    if (sizes.isNotEmpty()) {
        Label("Result")
        sizes.take(3).forEach { (w, h) ->
            Text("$w×$h → ${(w * scale).toInt()}×${(h * scale).toInt()}", fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        }
        if (sizes.size > 3) Note("and ${sizes.size - 3} more sizes")
    }
    val large = queued.any { ImageJobs.upscaledSize(it, scale).let { (w, h) -> w.toLong() * h > ImageJobs.LARGE_PIXELS } }
    if (large) {
        Text(
            "Very large results need a lot of GPU memory; the server may run out of it.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** "Variance on Seed": the +/- of each LoRA the images use, of the CFG and of the steps, and how many jobs that is. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VarianceOptions(
    ready: List<ImageJobs.Source.Ready>,
    loraSpreads: Map<String, Float>,
    onLoraSpread: (String, Float) -> Unit,
    loraStep: Float,
    onLoraStep: (Float) -> Unit,
    cfgPlus: Float,
    onCfgPlus: (Float) -> Unit,
    stepsPlus: Float,
    onStepsPlus: (Float) -> Unit,
    jobs: Int,
) {
    Note(
        "Each image keeps its seed and settings. The jobs vary the chosen LoRA weights and settings around each " +
            "image's own values, every combination once.",
    )
    val loras = remember(ready) { ImageJobs.lorasOf(ready) }
    if (loras.isEmpty()) {
        Note("The images use no LoRAs.")
    } else {
        loras.forEach { (name, weights) ->
            val used =
                if (weights.size == 1) ImageJobs.weightText(weights[0]) else "${ImageJobs.weightText(weights.first())}–${ImageJobs.weightText(weights.last())}"
            Label("$name · $used")
            SpreadChips(ImageJobs.LORA_SPREADS, loraSpreads[name] ?: 0f) { onLoraSpread(name, it) }
        }
        if (loraSpreads.values.any { it > 0f }) {
            Label("LoRA step")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ImageJobs.LORA_STEPS.forEach { step ->
                    FilterChip(selected = loraStep == step, onClick = { onLoraStep(step) }, label = { Text(ImageJobs.weightText(step)) })
                }
            }
        }
    }
    Label("CFG scale · step ${ImageJobs.weightText(ImageJobs.CFG_STEP)}")
    SpreadChips(ImageJobs.CFG_SPREADS, cfgPlus, onCfgPlus)
    Label("Steps · step ${ImageJobs.STEPS_STEP.roundToInt()}")
    SpreadChips(ImageJobs.STEPS_SPREADS, stepsPlus, onStepsPlus)

    val tooMany = jobs > ImageJobs.MAX_VARIANCE_JOBS
    Text(
        when {
            jobs == 0 -> "Choose what to vary."
            tooMany -> "$jobs jobs: at most ${ImageJobs.MAX_VARIANCE_JOBS}. Narrow the ranges or take a larger step."
            else -> "$jobs ${if (jobs == 1) "job" else "jobs"} (at most ${ImageJobs.MAX_VARIANCE_JOBS})"
        },
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        color = if (tooMany) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
}

/** Off, or +/- one of [spreads]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpreadChips(
    spreads: List<Float>,
    selected: Float,
    onSelect: (Float) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = selected <= 0f, onClick = { onSelect(0f) }, label = { Text("Off") })
        spreads.forEach { plus ->
            FilterChip(selected = selected == plus, onClick = { onSelect(plus) }, label = { Text("±${ImageJobs.weightText(plus)}") })
        }
    }
}

@Composable
private fun SkippedNotes(reasons: List<String>) {
    reasons.groupingBy { it }.eachCount().forEach { (reason, n) ->
        Note("${if (n == 1) "1 image" else "$n images"} left out: $reason")
    }
}

@Composable
private fun UpscalerPicker(
    latentModes: List<String>,
    upscalers: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = upscalers.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text(selected, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            // The latent modes first, as in hires fix's own picker (3.0.1).
            latentModes.forEach { name ->
                DropdownMenuItem(text = { Text(name) }, onClick = {
                    onSelect(name)
                    expanded = false
                })
            }
            if (latentModes.isNotEmpty() && upscalers.isNotEmpty()) HorizontalDivider()
            upscalers.forEach { name ->
                DropdownMenuItem(text = { Text(name) }, onClick = {
                    onSelect(name)
                    expanded = false
                })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MoreLikeThisDialog(
    viewModel: ForgeViewModel,
    request: ForgeGalleryManager.ImageJobsRequest,
    onQueued: () -> Unit,
) {
    var similar by remember { mutableStateOf(true) }
    var count by remember { mutableIntStateOf(ImageJobs.DEFAULT_COUNT) }
    var strength by remember { mutableFloatStateOf(ImageJobs.DEFAULT_STRENGTH) }
    val source = request.sources?.firstOrNull()
    val jobs = (source as? ImageJobs.Source.Ready)?.let { ImageJobs.seedOffsets(it.payload.seed, count).size } ?: 0

    AlertDialog(
        onDismissRequest = { viewModel.dismissImageJobs() },
        title = { Text("More Like This") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (source) {
                    null -> Loading()
                    is ImageJobs.Source.Skipped -> Note("This image cannot be made again: ${source.reason}.")
                    is ImageJobs.Source.Ready -> {
                        val p = source.payload
                        Text(
                            "Seed ${p.seed} · ${p.width}×${p.height} · ${source.modelLabel}",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = similar, onClick = { similar = true }, label = { Text("Similar") })
                            FilterChip(selected = !similar, onClick = { similar = false }, label = { Text("Neighbouring Seeds") })
                        }
                        Note(
                            if (similar) {
                                "Keeps the image's seed and mixes in a variation seed next to it: the same composition with small changes."
                            } else {
                                "Uses the seeds next to the image's: new images with the same prompt and settings, not similar ones."
                            },
                        )

                        Label("Images: $count (seeds ±${(count + 1) / 2})")
                        Slider(
                            value = count.toFloat(),
                            onValueChange = { count = (it / 2).roundToInt() * 2 },
                            valueRange = 2f..(ImageJobs.MAX_OFFSET * 2).toFloat(),
                            steps = ImageJobs.MAX_OFFSET - 2,
                        )
                        if (similar) {
                            Label("Variation strength: ${"%.2f".format(Locale.US, strength)}")
                            Slider(
                                value = strength,
                                onValueChange = { strength = (it * 20).roundToInt() / 20f },
                                valueRange = 0.05f..0.3f,
                                steps = 4,
                            )
                        }
                        if (p.enable_hr) Note("Hires fix stays on, as in the image.")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    viewModel.queueMoreLikeThis(similar, count, strength)
                    onQueued()
                },
                enabled = jobs > 0,
            ) { Text(if (jobs > 0) "Add $jobs to Queue" else "Add to Queue") }
        },
        dismissButton = { TextButton(onClick = { viewModel.dismissImageJobs() }) { Text("Cancel") } },
    )
}
