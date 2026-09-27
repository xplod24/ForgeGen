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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
 * "UPSCALE SELECTED" AND "MORE LIKE THIS" (2.4.0)
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
        ImageJobs.Kind.UPSCALE -> UpscaleDialog(viewModel, current, onQueued)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UpscaleDialog(
    viewModel: ForgeViewModel,
    request: ForgeGalleryManager.ImageJobsRequest,
    onQueued: () -> Unit,
) {
    val state by viewModel.appState.collectAsStateWithLifecycle()
    val upscalers by viewModel.upscalers.collectAsStateWithLifecycle()
    var scale by remember { mutableFloatStateOf(ImageJobs.DEFAULT_SCALE) }
    // The upscaler of the hires fix settings, as the owner asked.
    var upscaler by remember { mutableStateOf(state.upscaler) }
    var denoising by remember { mutableFloatStateOf(ImageJobs.DEFAULT_DENOISING) }
    val sources = request.sources
    val ready = sources.orEmpty().filterIsInstance<ImageJobs.Source.Ready>()
    val queued = ready.filter { (it.hiresScale ?: 1f) < scale }

    AlertDialog(
        onDismissRequest = { viewModel.dismissImageJobs() },
        title = { Text(if (request.count == 1) "Upscale Image" else "Upscale ${request.count} Images") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (sources == null) {
                    Loading()
                    return@Column
                }
                Note("Each image is made again with its own seed and settings plus hires fix, one job after another in the queue.")

                Label("Scale")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ImageJobs.UPSCALE_SCALES.forEach { s ->
                        FilterChip(selected = scale == s, onClick = { scale = s }, label = { Text("×${ImageJobs.scaleText(s)}") })
                    }
                }

                Label("Upscaler")
                UpscalerPicker(upscalers = upscalers, selected = upscaler, onSelect = { upscaler = it })

                Label("Denoising: ${"%.2f".format(Locale.US, denoising)}")
                Slider(
                    value = denoising,
                    onValueChange = { denoising = (it * 20).roundToInt() / 20f },
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

                // What is left out, and why.
                val reasons =
                    sources.mapNotNull { source ->
                        when (source) {
                            is ImageJobs.Source.Skipped -> source.reason
                            is ImageJobs.Source.Ready ->
                                source.hiresScale?.takeIf { it >= scale }?.let { "already upscaled ×${ImageJobs.scaleText(it)}" }
                        }
                    }
                reasons.groupingBy { it }.eachCount().forEach { (reason, n) ->
                    Note("${if (n == 1) "1 image" else "$n images"} left out: $reason")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    viewModel.queueUpscales(scale, upscaler, denoising)
                    onQueued()
                },
                enabled = queued.isNotEmpty(),
            ) { Text(if (sources == null) "Add to Queue" else "Add ${queued.size} to Queue") }
        },
        dismissButton = { TextButton(onClick = { viewModel.dismissImageJobs() }) { Text("Cancel") } },
    )
}

@Composable
private fun UpscalerPicker(
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
