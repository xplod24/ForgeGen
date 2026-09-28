package com.example.forgegen.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.example.forgegen.ForgeViewModel
import com.example.forgegen.GalleryStatistics
import com.example.forgegen.GalleryStats
import com.example.forgegen.ui.components.FloatingTopBar
import java.time.format.TextStyle
import java.util.Locale

/* ============================================================================
 * GALLERY STATISTICS (3.2.0, board 3D)
 * Worked out from the gallery index on the phone, so the server is not asked anything: how many images there are,
 * how many this month and how much room they take, a day-by-day map of the last 17 weeks, and the models, LoRAs and
 * tags used most.
 * ============================================================================ */

@Composable
fun GalleryStatsScreen(
    viewModel: ForgeViewModel,
    navController: NavHostController,
) {
    val stats by produceState<GalleryStats?>(initialValue = null) { value = viewModel.galleryStatistics() }
    Scaffold(
        topBar = {
            FloatingTopBar(
                title = "Statistics",
                subtitle = "From the gallery index on this phone",
                onNavigate = { navController.popBackStack() },
            )
        },
    ) { padding ->
        val shown = stats
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                shown == null -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                shown.images == 0 ->
                    Text(
                        "No images in the gallery index yet. It fills itself while the gallery is open.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                else -> StatsContent(shown)
            }
        }
    }
}

private fun number(n: Int) = String.format(Locale.getDefault(), "%,d", n)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StatsContent(stats: GalleryStats) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(
                    rememberScrollState(),
                ).padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile(number(stats.images), "images", Modifier.weight(1f))
            Tile(number(stats.thisMonth), "this month", Modifier.weight(1f))
            Tile(
                if (stats.bytes > 0) GalleryStatistics.formatBytes(stats.bytes) else "—",
                if (stats.sizesKnown) "in the gallery" else "counted so far",
                Modifier.weight(1f),
            )
        }

        Label("IMAGES PER DAY · LAST ${GalleryStatistics.WEEKS} WEEKS")
        Card {
            HeatMap(stats)
        }

        if (stats.topModels.isNotEmpty()) {
            Label("TOP MODELS")
            Card {
                val max = stats.topModels.first().second
                stats.topModels.forEach { (name, count) -> Bar(name, count, count.toFloat() / max) }
            }
        }

        if (stats.topLoras.isNotEmpty() || stats.topTags.isNotEmpty()) {
            Label("TOP LORAS AND TAGS")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                stats.topLoras.forEach { (name, count) -> Chip(name, count, lora = true) }
                stats.topTags.forEach { (name, count) -> Chip(name, count, lora = false) }
            }
        }
    }
}

@Composable
private fun Tile(
    value: String,
    label: String,
    modifier: Modifier,
) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) { content() }
    }
}

/** The colours of the heat map's levels 0 (none) to 4 (the busiest days). */
@Composable
private fun levelColors(): List<Color> {
    val primary = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceVariant
    return listOf(empty, lerp(empty, primary, 0.3f), lerp(empty, primary, 0.6f), primary, lerp(primary, Color.White, 0.45f))
}

/** One column per week (Monday on top), today in the last one. */
@Composable
private fun HeatMap(stats: GalleryStats) {
    val colors = levelColors()
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (week in 0 until GalleryStatistics.WEEKS) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                for (day in 0 until 7) {
                    val index = week * 7 + day
                    val count = stats.perDay.getOrNull(index)
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(3.dp))
                                .background(if (count == null) Color.Transparent else colors[stats.level(count)]),
                    )
                }
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        val first = stats.firstDay.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
        val last =
            stats.firstDay
                .plusDays((stats.perDay.size - 1).toLong())
                .month
                .getDisplayName(TextStyle.FULL, Locale.getDefault())
        Text(first, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text("Less", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        colors.forEach { color ->
            Box(
                modifier =
                    Modifier
                        .padding(horizontal = 2.dp)
                        .size(10.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(color),
            )
        }
        Text("More", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            last,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Bar(
    name: String,
    count: Int,
    share: Float,
) {
    Column(modifier = Modifier.padding(vertical = 5.dp)) {
        Row {
            Text(name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(number(count), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(
            modifier =
                Modifier
                    .padding(top = 5.dp)
                    .fillMaxWidth(share.coerceIn(0.02f, 1f))
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun Chip(
    name: String,
    count: Int,
    lora: Boolean,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (lora) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (lora) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(28.dp).padding(horizontal = 10.dp)) {
            Text(name, fontSize = 12.sp, maxLines = 1)
            Text(
                "  ${number(count)}",
                fontSize = 12.sp,
                color = if (lora) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
