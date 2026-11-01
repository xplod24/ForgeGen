package com.example.forgegen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

/** Gallery-wide coverage stays visible; only the current update's details expand into the grid's space. */
@Composable
internal fun GalleryIndexPanel(
    progress: GalleryIndexProgress,
    loaded: Boolean,
    onRetry: () -> Unit,
) {
    var expanded by rememberSaveable(progress.source) { mutableStateOf(false) }
    val numbers = remember { NumberFormat.getIntegerInstance(Locale.US) }
    val count = numbers.format(progress.indexed)
    val title =
        when {
            !loaded && !progress.isActive -> "Loading gallery index…"
            progress.total != null && progress.isActive -> "$count / ${numbers.format(progress.total)} indexed"
            progress.total != null && progress.stage == GalleryIndexStage.FAILED -> "$count / ${numbers.format(progress.total)} indexed"
            else -> "$count images indexed"
        }
    val problem = progress.stage == GalleryIndexStage.FAILED || progress.stage == GalleryIndexStage.INTERRUPTED
    val icon =
        when {
            problem -> Icons.Default.ErrorOutline
            progress.stage == GalleryIndexStage.UP_TO_DATE -> Icons.Default.CheckCircle
            else -> Icons.Default.Sync
        }
    val iconColor =
        when {
            problem -> SEARCHING_AMBER
            progress.stage == GalleryIndexStage.UP_TO_DATE -> CONNECTED_GREEN
            else -> MaterialTheme.colorScheme.primary
        }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                        .clickable(role = Role.Button, onClickLabel = "Gallery index details") { expanded = !expanded }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Icon(icon, contentDescription = "Gallery index", tint = iconColor, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(title, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
            }
            if (progress.isActive && !expanded) IndexProgressLine(progress, Modifier.fillMaxWidth().height(2.dp))
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text("Gallery index", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(progress.stage.label, style = MaterialTheme.typography.bodyMedium)
                    if (progress.isActive) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IndexProgressLine(progress, Modifier.weight(1f).height(4.dp))
                            progress.fraction?.let { fraction ->
                                Text("${(fraction * 100).roundToInt()}%", fontSize = 12.sp, modifier = Modifier.padding(start = 12.dp))
                            }
                        }
                    }
                    if (progress.stage == GalleryIndexStage.READING_IMAGES || progress.stage == GalleryIndexStage.READING_DETAILS) {
                        IndexDetailRow("This update", "${numbers.format(progress.processed)} / ${numbers.format(progress.workTotal)}")
                    }
                    progress.remaining?.let { IndexDetailRow("Images remaining", numbers.format(it)) }
                    if (progress.foldersChecked > 0) {
                        val folders =
                            numbers.format(progress.foldersChecked) +
                                (progress.foldersTotal?.let { " / ${numbers.format(it)}" } ?: "")
                        IndexDetailRow("Folders checked", folders)
                    }
                    progress.unreadable?.let { unreadable ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        IndexDetailRow("Generation data not read", numbers.format(unreadable))
                        Text("Images are still included in the gallery.", style = MaterialTheme.typography.bodySmall)
                    }
                    if (progress.detailsRemaining > 0) {
                        IndexDetailRow("Details remaining", numbers.format(progress.detailsRemaining))
                    }
                    if (progress.stage == GalleryIndexStage.WAITING_FOR_GENERATION) {
                        Text("Image details will continue on the next update.", style = MaterialTheme.typography.bodySmall)
                    }
                    if (problem) {
                        progress.error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        TextButton(onClick = onRetry) { Text("Retry") }
                    }
                    Text(
                        "You can keep browsing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun IndexProgressLine(
    progress: GalleryIndexProgress,
    modifier: Modifier,
) {
    val fraction = progress.fraction
    if (fraction != null) {
        LinearProgressIndicator(progress = { fraction }, modifier = modifier)
    } else {
        LinearProgressIndicator(modifier = modifier)
    }
}

@Composable
private fun IndexDetailRow(
    label: String,
    value: String,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

private val GalleryIndexStage.label: String
    get() =
        when (this) {
            GalleryIndexStage.IDLE -> "Waiting for an update"
            GalleryIndexStage.CHECKING_FOLDERS -> "Checking folders…"
            GalleryIndexStage.READING_IMAGES -> "Reading image data"
            GalleryIndexStage.READING_DETAILS -> "Updating image details"
            GalleryIndexStage.FINISHING -> "Finishing update…"
            GalleryIndexStage.UP_TO_DATE -> "Index is up to date"
            GalleryIndexStage.WAITING_FOR_GENERATION -> "Image details pending"
            GalleryIndexStage.FAILED -> "Index update failed"
            GalleryIndexStage.INTERRUPTED -> "Index update interrupted"
        }
