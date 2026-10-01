package com.example.forgegen.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.example.forgegen.ForgeViewModel
import com.example.forgegen.GalleryInsights
import com.example.forgegen.GalleryStatistics
import com.example.forgegen.GalleryStats
import com.example.forgegen.GenerationStatistics
import com.example.forgegen.GenerationStats
import com.example.forgegen.JobFailure
import com.example.forgegen.JobPhase
import com.example.forgegen.JobRunEntity
import com.example.forgegen.JobStart
import com.example.forgegen.JobTimeline
import com.example.forgegen.LikedStats
import com.example.forgegen.SettingCount
import com.example.forgegen.StatTarget
import com.example.forgegen.ui.components.FloatingTopBar
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale

/* ============================================================================
 * STATISTICS (3.2.0, board 3D; tabs since 3.6.0)
 * Gallery: worked out from the gallery index on the phone, so the server is not asked anything: how many images there
 * are, a day-by-day map of the last 17 weeks and the months, the models, samplers, sizes, steps and CFG, hires fix,
 * VAE and text encoders, LoRAs, tags and embeddings used most, and What You Like (how often each ends up in the
 * favorites). A row opens the gallery's All Images with its images.
 * Generation (with "Generation History" on): the jobs the app recorded (JobRecorder): GPU time, model loading, each
 * model's times, swaps, speed, recent jobs (a tap shows the job's phases and VRAM) and failures.
 * ============================================================================ */

@Composable
fun GalleryStatsScreen(
    viewModel: ForgeViewModel,
    navController: NavHostController,
) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val history = config.generationHistory
    val pager = rememberPagerState { if (history) 2 else 1 }
    val scope = rememberCoroutineScope()
    val onOpen: (StatTarget) -> Unit = { target ->
        viewModel.openGalleryFrom(target)
        if (!navController.popBackStack("gallery", inclusive = false)) navController.navigate("gallery")
    }
    Scaffold(
        topBar = {
            FloatingTopBar(
                title = "Statistics",
                subtitle = if (pager.currentPage == 0) "From the gallery index on this phone" else "Measured by this app on every job",
                onNavigate = { navController.popBackStack() },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (history) {
                PrimaryTabRow(selectedTabIndex = pager.currentPage) {
                    listOf("Gallery" to Icons.Default.Image, "Generation" to Icons.Default.Timer).forEachIndexed { index, (name, icon) ->
                        Tab(
                            selected = pager.currentPage == index,
                            onClick = { scope.launch { pager.animateScrollToPage(index) } },
                            modifier = Modifier.heightIn(min = 48.dp),
                            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(name, style = MaterialTheme.typography.titleSmall)
                            }
                        }
                    }
                }
            }
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                if (page == 0) GalleryPage(viewModel, onOpen) else GenerationPage(viewModel)
            }
        }
    }
}

@Composable
private fun GalleryPage(
    viewModel: ForgeViewModel,
    onOpen: (StatTarget) -> Unit,
) {
    val backlog by viewModel.galleryDetailsBacklog.collectAsStateWithLifecycle()
    // Read again when the details of older images are all in.
    val stats by produceState<GalleryStats?>(initialValue = null, backlog == 0) { value = viewModel.galleryStatistics() }
    val shown = stats
    Box(modifier = Modifier.fillMaxSize()) {
        when {
            shown == null -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            shown.images == 0 ->
                Text(
                    "No images in the gallery index yet. It fills itself while the gallery is open.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            else -> StatsContent(shown, backlog, onOpen)
        }
    }
}

private fun number(n: Int) = String.format(Locale.getDefault(), "%,d", n)

private fun percent(share: Float) = "${(share * 100).toInt()}%"

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StatsContent(
    stats: GalleryStats,
    backlog: Int = 0,
    onOpen: (StatTarget) -> Unit = {},
) {
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
        if (backlog > 0) DetailsBacklog(stats.images, backlog)

        Label("IMAGES PER DAY · LAST ${GalleryStatistics.WEEKS} WEEKS")
        Card {
            HeatMap(stats)
        }

        if (stats.perMonth.size >= 2) {
            Label("IMAGES PER MONTH · ALL TIME")
            Card { MonthBars(stats.perMonth) }
        }

        if (stats.topModels.isNotEmpty()) {
            Label("TOP MODELS")
            Card {
                val max = stats.topModels.first().second
                stats.topModels.forEach { (name, count) -> Bar(name, count, count.toFloat() / max) { onOpen(StatTarget.Model(name)) } }
            }
        }

        val details = stats.details
        if (details.samplers.isNotEmpty()) {
            Label("SAMPLERS AND SCHEDULERS")
            Card { CountBars(details.samplers, onOpen) }
        }
        if (details.sizes.isNotEmpty()) {
            Label("IMAGE SIZES")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                details.sizes.forEach { Chip(it.label, it.count, ChipKind.PLAIN) { it.target?.let(onOpen) } }
            }
        }
        if (details.steps.isNotEmpty() || details.cfg.isNotEmpty()) {
            Label("STEPS AND CFG")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Histogram(
                    value = details.typicalSteps?.toString() ?: "—",
                    caption = "steps, mostly",
                    bars = details.steps.map { it.second },
                    highlight = details.steps.indexOfFirst { it.first == details.typicalSteps },
                    from =
                        details.steps
                            .firstOrNull()
                            ?.first
                            ?.toString()
                            .orEmpty(),
                    to =
                        details.steps
                            .lastOrNull()
                            ?.first
                            ?.toString()
                            .orEmpty(),
                    modifier = Modifier.weight(1f),
                )
                Histogram(
                    value = details.typicalCfg?.let { GalleryInsights.number(it) } ?: "—",
                    caption = "CFG, mostly",
                    bars = details.cfg.map { it.second },
                    highlight = details.cfg.indexOfFirst { it.first == details.typicalCfg },
                    from =
                        details.cfg
                            .firstOrNull()
                            ?.first
                            ?.let { GalleryInsights.number(it) }
                            .orEmpty(),
                    to =
                        details.cfg
                            .lastOrNull()
                            ?.first
                            ?.let { GalleryInsights.number(it) }
                            .orEmpty(),
                    modifier = Modifier.weight(1f),
                )
            }
            details.distilledCfg?.let {
                Note("${number(details.distilledCount)} images also have a Distilled CFG, mostly ${GalleryInsights.number(it)}")
            }
        }
        if (details.hiresCount > 0 && details.known > 0) {
            Label("HIRES FIX")
            Card {
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.clickable { onOpen(hiresTarget()) }) {
                    Text(percent(details.hiresCount.toFloat() / details.known), fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "  of images, ${number(details.hiresCount)} in all",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    details.hiresScale?.let { SmallChip("×${GalleryInsights.number(it)} mostly") }
                    details.hiresUpscaler?.let { SmallChip(it) }
                    details.hiresDenoising?.let { SmallChip("Denoising ${GalleryInsights.number(it)}") }
                    details.hiresSteps?.let { SmallChip("$it steps") }
                }
            }
        }
        if (details.modules.any { it.label != "The model's own" }) {
            Label("VAE AND TEXT ENCODERS")
            Card { CountBars(details.modules, onOpen) }
        }

        if (stats.topLoras.isNotEmpty() || stats.topTags.isNotEmpty()) {
            Label("TOP LORAS AND TAGS")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                stats.topLoras.forEach { (name, count) -> Chip(name, count, ChipKind.LORA) { onOpen(StatTarget.Lora(name)) } }
                stats.topTags.forEach { (name, count) -> Chip(name, count, ChipKind.PLAIN) { onOpen(StatTarget.Tag(name)) } }
            }
        }
        if (details.embeddings.isNotEmpty() || details.negativeTags.isNotEmpty()) {
            Label("EMBEDDINGS AND NEGATIVE TAGS")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                details.embeddings.forEach { Chip(it.label, it.count, ChipKind.EMBEDDING) { it.target?.let(onOpen) } }
                details.negativeTags.forEach { Chip(it.label, it.count, ChipKind.PLAIN) { it.target?.let(onOpen) } }
            }
        }

        val liked = stats.liked
        if (liked != null) {
            LikedSection(liked, onOpen)
        } else {
            Label("WHAT YOU LIKE")
            Note("Star at least ${GalleryInsights.MIN_FAVORITES} images to see what your favorites have in common.")
        }
    }
}

private fun hiresTarget() =
    StatTarget.Detail(com.example.forgegen.GalleryDetailFilter(com.example.forgegen.GalleryDetailFilter.Kind.HIRES, "on"))

@Composable
private fun DetailsBacklog(
    images: Int,
    backlog: Int,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row {
                Text(
                    "Adding sizes, steps and hires fix to older images",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text("${number(backlog)} left", fontSize = 12.sp)
            }
            LinearProgressIndicator(
                progress = { if (images > 0) ((images - backlog).toFloat() / images).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun LikedSection(
    liked: LikedStats,
    onOpen: (StatTarget) -> Unit,
) {
    Label("WHAT YOU LIKE")
    Note("How often images end up in your favorites · ${number(liked.favorites)} of ${number(liked.total)}")
    if (liked.byModel.isNotEmpty()) {
        Card {
            Caption("By model")
            val max = liked.byModel.maxOf { it.rate }.coerceAtLeast(0.01f)
            liked.byModel.forEach { rate ->
                Column(modifier = Modifier.fillMaxWidth().clickable { rate.target?.let(onOpen) }.padding(vertical = 5.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(rate.label, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(percent(rate.rate), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(
                            " · ${rate.favorites} of ${rate.total}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Track(rate.rate / max, MaterialTheme.colorScheme.secondary)
                }
            }
        }
    }
    if (liked.bySetting.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Card {
            Caption("By setting · with it, and without")
            val max = liked.bySetting.maxOf { maxOf(it.withRate, it.withoutRate) }.coerceAtLeast(0.01f)
            liked.bySetting.forEach { c ->
                Column(modifier = Modifier.fillMaxWidth().clickable { c.target?.let(onOpen) }.padding(vertical = 6.dp)) {
                    Text(c.label, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    PairBar(c.withRate / max, "with ${percent(c.withRate)}", MaterialTheme.colorScheme.secondary)
                    PairBar(c.withoutRate / max, "without ${percent(c.withoutRate)}", MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
    if (liked.tagsMore.isNotEmpty() || liked.tagsLess.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Card {
            if (liked.tagsMore.isNotEmpty()) {
                Caption("Tags your favorites have more often")
                LiftChips(liked.tagsMore, more = true, onOpen = onOpen)
            }
            if (liked.tagsLess.isNotEmpty()) {
                Caption("and less often")
                LiftChips(liked.tagsLess, more = false, onOpen = onOpen)
            }
        }
    }
    Note("Only what has at least ${GalleryInsights.MIN_IMAGES} images counts, so a few lucky ones do not tip it.")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LiftChips(
    tags: List<com.example.forgegen.LikedTag>,
    more: Boolean,
    onOpen: (StatTarget) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(bottom = 6.dp),
    ) {
        tags.forEach { tag ->
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (more) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (more) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable { onOpen(StatTarget.Tag(tag.tag)) },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(28.dp).padding(horizontal = 10.dp)) {
                    Text(tag.tag, fontSize = 12.sp, maxLines = 1)
                    Text(
                        if (tag.lift == 0f) "  never" else "  ×${GalleryInsights.number(tag.lift)}",
                        fontSize = 12.sp,
                        color = if (more) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
private fun Note(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp, end = 4.dp),
    )
}

@Composable
private fun Caption(text: String) {
    Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(bottom = 6.dp, top = 2.dp))
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
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

/** A bar per month, the current one lighter with its count above it. */
@Composable
private fun MonthBars(months: List<Pair<String, Int>>) {
    val shown = months.takeLast(MONTHS_SHOWN)
    val max = shown.maxOf { it.second }.coerceAtLeast(1)
    val primary = MaterialTheme.colorScheme.primary
    val current = lerp(primary, Color.White, 0.45f)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth().height(130.dp)) {
        shown.forEachIndexed { index, (month, count) ->
            val last = index == shown.lastIndex
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                if (last) Text(number(count), fontSize = 10.sp, maxLines = 1)
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.82f * count / max)
                            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                            .background(if (last) current else primary),
                )
                val name =
                    runCatching {
                        YearMonth
                            .parse(
                                month,
                            ).month
                            .getDisplayName(TextStyle.NARROW, Locale.getDefault())
                    }.getOrDefault("")
                Text(name, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
    val since =
        runCatching {
            val first = YearMonth.parse(months.first().first)
            "${first.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${first.year}"
        }.getOrDefault("")
    Text("Since $since", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
}

private const val MONTHS_SHOWN = 12

@Composable
private fun CountBars(
    rows: List<SettingCount>,
    onOpen: (StatTarget) -> Unit,
) {
    val max = rows.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
    rows.forEach { row -> Bar(row.label, row.count, row.count.toFloat() / max, row.target?.let { { onOpen(it) } }) }
}

@Composable
private fun Bar(
    name: String,
    count: Int,
    share: Float,
    onClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Column(modifier = Modifier.weight(1f).padding(vertical = 5.dp)) {
            Row {
                Text(name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(number(count), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Track(share, MaterialTheme.colorScheme.primary)
        }
        if (onClick != null) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Show the images",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 6.dp).size(18.dp),
            )
        }
    }
}

@Composable
private fun Track(
    share: Float,
    color: Color,
) {
    Box(
        modifier =
            Modifier
                .padding(top = 5.dp)
                .fillMaxWidth(share.coerceIn(0.02f, 1f))
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(color),
    )
}

@Composable
private fun PairBar(
    share: Float,
    label: String,
    color: Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth(share.coerceIn(0.02f, 1f) * 0.7f)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(color),
        )
        Text("  $label", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun Histogram(
    value: String,
    caption: String,
    bars: List<Int>,
    highlight: Int,
    from: String,
    to: String,
    modifier: Modifier,
) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text("  $caption", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            val max = bars.maxOrNull()?.coerceAtLeast(1) ?: 1
            val faint = lerp(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.primary, 0.45f)
            Row(
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.fillMaxWidth().height(44.dp).padding(top = 8.dp),
            ) {
                bars.forEachIndexed { index, count ->
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxHeight((count.toFloat() / max).coerceAtLeast(0.06f))
                                .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                                .background(if (index == highlight) MaterialTheme.colorScheme.primary else faint),
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(from, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(to, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private enum class ChipKind { PLAIN, LORA, EMBEDDING }

@Composable
private fun Chip(
    name: String,
    count: Int,
    kind: ChipKind,
    onClick: (() -> Unit)? = null,
) {
    val container =
        when (kind) {
            ChipKind.LORA -> MaterialTheme.colorScheme.secondaryContainer
            ChipKind.EMBEDDING -> MaterialTheme.colorScheme.tertiaryContainer
            ChipKind.PLAIN -> MaterialTheme.colorScheme.surfaceContainerHigh
        }
    val content =
        when (kind) {
            ChipKind.LORA -> MaterialTheme.colorScheme.onSecondaryContainer
            ChipKind.EMBEDDING -> MaterialTheme.colorScheme.onTertiaryContainer
            ChipKind.PLAIN -> MaterialTheme.colorScheme.onSurface
        }
    val countColor =
        when (kind) {
            ChipKind.LORA -> MaterialTheme.colorScheme.primary
            ChipKind.EMBEDDING -> MaterialTheme.colorScheme.tertiary
            ChipKind.PLAIN -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = container,
        contentColor = content,
        modifier =
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(28.dp).padding(horizontal = 10.dp)) {
            Text(name, fontSize = 12.sp, maxLines = 1)
            Text("  ${number(count)}", fontSize = 12.sp, color = countColor)
        }
    }
}

@Composable
private fun SmallChip(text: String) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Text(text, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
    }
}

// --- GENERATION (3.6.0) ---

/** The colour of each phase of a job; neighbours differ in lightness, not only in hue. */
@Composable
private fun phaseColor(kind: JobPhase.Kind): Color =
    when (kind) {
        JobPhase.Kind.LOAD, JobPhase.Kind.LOADING -> MaterialTheme.colorScheme.primary
        JobPhase.Kind.VRAM -> MaterialTheme.colorScheme.tertiary
        JobPhase.Kind.PROMPT -> MaterialTheme.colorScheme.outline
        JobPhase.Kind.SAMPLING -> lerp(MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.onSurface, 0.55f)
        JobPhase.Kind.HIRES -> MaterialTheme.colorScheme.secondary
        JobPhase.Kind.SEND -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    }

@Composable
private fun GenerationPage(viewModel: ForgeViewModel) {
    val recorded by viewModel.jobsRecorded.collectAsStateWithLifecycle()
    val stats by produceState<GenerationStats?>(initialValue = null, recorded) { value = viewModel.generationStatistics() }
    var openRun by remember { mutableStateOf<String?>(null) }
    val shown = stats
    Box(modifier = Modifier.fillMaxSize()) {
        when {
            shown == null -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            shown.jobs == 0 ->
                Text(
                    "No jobs recorded yet. From now on every job's model loading, sampling and VRAM is measured here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            else -> GenerationContent(shown, onOpenRun = { openRun = it })
        }
    }
    openRun?.let { id -> JobDetailsSheet(viewModel, id, onDismiss = { openRun = null }) }
}

@Composable
internal fun GenerationContent(
    stats: GenerationStats,
    onOpenRun: (String) -> Unit = {},
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile(GenerationStatistics.duration(stats.gpuMs), "GPU time", Modifier.weight(1f))
            Tile(number(stats.images), "images", Modifier.weight(1f))
            Tile(stats.perImageMs?.let { GenerationStatistics.duration(it) } ?: "—", "per image", Modifier.weight(1f))
        }
        val since = stats.since?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) }
        Note(listOfNotNull(since?.let { "Since $it" }, "${number(stats.jobs)} jobs", "${number(stats.failed)} failed").joinToString(" · "))

        if (stats.loading.isNotEmpty()) {
            Label("MODEL LOADING")
            Card { LoadingRows(stats) }
        }
        if (stats.loadingMs > 0) {
            Spacer(Modifier.height(8.dp))
            Insight(stats)
        }

        if (stats.byModel.isNotEmpty()) {
            Label("BY MODEL")
            Card { ModelTable(stats) }
        }

        if (stats.swaps.isNotEmpty()) {
            Label("MOST COMMON SWAPS")
            Card {
                stats.swaps.forEach { swap ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (swap.modulesOnly) "${swap.to} · another VAE or encoder" else "${swap.from} → ${swap.to}",
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                if (swap.count == 1) "once" else "${swap.count} times",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(GenerationStatistics.duration(swap.medianMs), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (stats.speeds.isNotEmpty()) {
            Label("SPEED BY SIZE · ${stats.speedModel.orEmpty().uppercase(Locale.getDefault())}")
            Card {
                val max = stats.speeds.maxOf { it.itPerSec }.coerceAtLeast(0.01f)
                stats.speeds.forEach { speed ->
                    Column(modifier = Modifier.padding(vertical = 5.dp)) {
                        Row {
                            Text(
                                if (speed.hires) "${speed.size} · hires fix" else speed.size,
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f),
                            )
                            Text("${speed.itPerSec} it/s", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Track(speed.itPerSec / max, MaterialTheme.colorScheme.primary)
                    }
                }
                Caption("Steps per second while sampling")
            }
        }

        if (stats.recent.isNotEmpty()) {
            Label("RECENT JOBS")
            Card {
                stats.recent.forEach { run -> RecentJob(run) { onOpenRun(run.id) } }
            }
        }

        if (stats.failures.isNotEmpty()) {
            Label("FAILED JOBS · ${number(stats.failed)}")
            Card {
                stats.failures.forEach { (failure, count) ->
                    Row(modifier = Modifier.padding(vertical = 6.dp)) {
                        Text(failureName(failure), fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text(number(count), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun failureName(failure: JobFailure) =
    when (failure) {
        JobFailure.OUT_OF_VRAM -> "Out of VRAM"
        JobFailure.SERVER_ERROR -> "Server error"
        JobFailure.REFUSED -> "Refused by the server"
        JobFailure.SERVER_GONE -> "Server gone during the job"
        JobFailure.PHONE_MEMORY -> "Too large for the phone's memory"
        JobFailure.OTHER -> "Other"
    }

@Composable
private fun LoadingRows(stats: GenerationStats) {
    val longest = stats.loading.maxOf { it.firstStepMs ?: 0L }.coerceAtLeast(1L)
    stats.loading.forEach { row ->
        val (title, about) =
            when (row.kind) {
                JobStart.COLD -> "Cold start" to "From an empty server to the first step"
                JobStart.SWAP -> "Model swap" to "Another checkpoint, VAE or text encoder than the job before"
                else -> "Same model" to "Already in VRAM, only the prompt is read"
            }
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
            Row {
                Text(title, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(row.firstStepMs?.let { GenerationStatistics.duration(it) } ?: "—", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                "$about · ${if (row.count == 1) "once" else "${number(row.count)} times"}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val total = row.firstStepMs ?: 0L
            val parts =
                when {
                    row.kind == JobStart.SAME -> listOf(JobPhase.Kind.PROMPT to total)
                    row.loadMs != null && row.vramMs != null -> listOf(JobPhase.Kind.LOAD to row.loadMs, JobPhase.Kind.VRAM to row.vramMs)
                    else -> listOf(JobPhase.Kind.LOADING to total)
                }
            PhaseBar(parts, share = total.toFloat() / longest, height = 10)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 4.dp)) {
        Legend(JobPhase.Kind.LOAD, "Unload and load")
        Legend(JobPhase.Kind.VRAM, "Into VRAM")
        Legend(JobPhase.Kind.PROMPT, "Prompt")
    }
}

@Composable
private fun Legend(
    kind: JobPhase.Kind,
    text: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(phaseColor(kind)))
        Text("  $text", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A bar of [parts] in proportion, [share] of the card's width. */
@Composable
private fun PhaseBar(
    parts: List<Pair<JobPhase.Kind, Long>>,
    share: Float = 1f,
    height: Int = 8,
) {
    val total = parts.sumOf { it.second }.coerceAtLeast(1L)
    Box(
        modifier =
            Modifier
                .padding(top = 6.dp)
                .fillMaxWidth()
                .height(height.dp)
                .clip(RoundedCornerShape((height / 2).dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(modifier = Modifier.fillMaxWidth(share.coerceIn(0.03f, 1f)).fillMaxHeight()) {
            parts.forEach { (kind, ms) ->
                if (ms > 0) {
                    Box(modifier = Modifier.weight(ms.toFloat() / total).fillMaxHeight().background(phaseColor(kind)))
                }
            }
        }
    }
}

@Composable
private fun Insight(stats: GenerationStats) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(14.dp)) {
            Icon(
                Icons.Default.Lightbulb,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(20.dp),
            )
            val saved = stats.savableMs
            val text =
                "Loading models took ${GenerationStatistics.duration(
                    stats.loadingMs,
                )} of the ${GenerationStatistics.duration(stats.gpuMs)}." +
                    if (saved != null && saved > 0) {
                        " Running each model's jobs one after another would have spared ${stats.avoidableSwaps} model " +
                            "changes, about ${GenerationStatistics.duration(saved)}."
                    } else {
                        ""
                    }
            Text(text, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(start = 10.dp))
        }
    }
}

@Composable
private fun ModelTable(stats: GenerationStats) {
    Row(modifier = Modifier.padding(bottom = 4.dp)) {
        Text("Model", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.weight(1f))
        listOf("Cold", "Swap", "Speed").forEach {
            Text(
                it,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.End,
                modifier = Modifier.width(62.dp),
            )
        }
    }
    stats.byModel.forEach { model ->
        Column(modifier = Modifier.padding(vertical = 5.dp)) {
            Row {
                Text(model.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                listOf(
                    model.coldMs?.let { GenerationStatistics.duration(it) } ?: "—",
                    model.swapMs?.let { GenerationStatistics.duration(it) } ?: "—",
                    model.itPerSec?.let { "$it it/s" } ?: "—",
                ).forEach { Text(it, fontSize = 13.sp, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(62.dp)) }
            }
            model.firstHashExtraMs?.let {
                Text(
                    "Its first load also worked out its hash: +${GenerationStatistics.duration(it)}, once",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RecentJob(
    run: JobRunEntity,
    onClick: () -> Unit,
) {
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(run.startedAt))
    val failed = run.outcome == "FAILED"
    val start =
        when (run.startKind) {
            JobStart.COLD.name -> "cold start"
            JobStart.SWAP.name -> "model swap"
            JobStart.SAME.name -> "same model"
            else -> null
        }
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(time, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(52.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(GenerationStatistics.nameOf(run.model), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (failed) {
                        "Failed: ${failureName(JobFailure.entries.firstOrNull { it.name == run.failure } ?: JobFailure.OTHER).lowercase()}"
                    } else {
                        listOfNotNull("${run.images} ${if (run.images == 1) "image" else "images"}", start).joinToString(" · ")
                    },
                    fontSize = 12.sp,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(GenerationStatistics.duration(run.totalMs), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Details",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp),
            )
        }
        val phases = GenerationStatistics.phases(run)
        if (phases.isNotEmpty()) {
            Box(modifier = Modifier.padding(start = 52.dp)) { PhaseBar(phases.map { it.kind to it.ms }, height = 6) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JobDetailsSheet(
    viewModel: ForgeViewModel,
    id: String,
    onDismiss: () -> Unit,
) {
    val run by produceState<JobRunEntity?>(initialValue = null, id) { value = viewModel.jobRun(id) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        val shown = run
        if (shown == null) {
            Box(
                modifier = Modifier.fillMaxWidth().height(200.dp),
            ) { CircularProgressIndicator(modifier = Modifier.align(Alignment.Center)) }
        } else {
            JobDetails(shown)
        }
    }
}

@Composable
internal fun JobDetails(run: JobRunEntity) {
    val phases = GenerationStatistics.phases(run)
    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
        Text("Job Details", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        val time = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(run.startedAt))
        Text(
            "$time · ${GenerationStatistics.nameOf(run.model)} · ${run.images} " +
                "${if (run.images == 1) "image" else "images"} · ${run.width}×${run.height}",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val start =
            when (run.startKind) {
                JobStart.COLD.name -> "Cold start"
                JobStart.SWAP.name -> "Model swap"
                JobStart.SAME.name -> "Same model"
                else -> "Start not known"
            }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(
                    start,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
            run.previousModel?.let {
                Text("  from ${GenerationStatistics.nameOf(it)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 12.dp)) {
            Text(GenerationStatistics.duration(run.totalMs), fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(
                "  from sending the job to the last image",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 5.dp),
            )
        }
        if (run.outcome == "FAILED") {
            Text(
                run.failureText ?: failureName(JobFailure.entries.firstOrNull { it.name == run.failure } ?: JobFailure.OTHER),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (phases.isNotEmpty()) {
            PhaseBar(phases.map { it.kind to it.ms }, height = 12)
            phases.forEach { phase ->
                Row(modifier = Modifier.padding(top = 10.dp)) {
                    Box(
                        modifier =
                            Modifier
                                .padding(top = 4.dp)
                                .size(12.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(phaseColor(phase.kind)),
                    )
                    Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(phase.name, fontSize = 14.sp)
                        Text(phase.note, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(GenerationStatistics.duration(phase.ms), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        val curve = JobTimeline.curve(run.vramCurve)
        if (curve.size >= 2) {
            Label("VRAM DURING THE JOB")
            Card { VramChart(run, curve, phases) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
            run.itPerSec?.let {
                Tile("$it it/s", run.hiresItPerSec?.let { h -> "sampling · hires fix $h" } ?: "sampling", Modifier.weight(1f))
            }
            run.vramPeakGb?.let { peak ->
                Tile(
                    String.format(Locale.US, "%.1f GB", peak),
                    run.vramTotalGb?.let { String.format(Locale.US, "VRAM at most, of %.1f", it) } ?: "VRAM at most",
                    Modifier.weight(1f),
                )
            }
        }
        Note("Measured by the app from what the server answers, to about a second.")
    }
}

/**
 * The VRAM readings over the job, on its phases. While Forge loads a model it answers nothing, so there are no
 * readings: that stretch is drawn dashed.
 */
@Composable
private fun VramChart(
    run: JobRunEntity,
    curve: List<Pair<Long, Float>>,
    phases: List<JobPhase>,
) {
    val total = run.vramTotalGb ?: curve.maxOf { it.second }
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val bands = phases.map { phaseColor(it.kind).copy(alpha = 0.16f) to it.ms }
    Row {
        Text(
            String.format(Locale.US, "%.0f GB", total),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        run.vramPeakGb?.let { Text(String.format(Locale.US, "peak %.1f GB", it), fontSize = 11.sp) }
    }
    Canvas(modifier = Modifier.fillMaxWidth().height(120.dp).padding(vertical = 6.dp)) {
        val span = run.totalMs.coerceAtLeast(1L).toFloat()
        var x0 = 0f
        bands.forEach { (color, ms) ->
            val w = size.width * ms / span
            drawRect(
                color,
                topLeft = Offset(x0, 0f),
                size =
                    androidx.compose.ui.geometry
                        .Size(w, size.height),
            )
            x0 += w
        }
        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())

        fun point(p: Pair<Long, Float>) = Offset(size.width * p.first / span, size.height * (1f - (p.second / total).coerceIn(0f, 1f)))
        curve.zipWithNext().forEach { (a, b) ->
            val gap = b.first - a.first > GAP_MS
            drawLine(
                line,
                point(a),
                point(b),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
                pathEffect = if (gap) PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())) else null,
            )
        }
        curve.forEach { drawCircle(line, radius = 2.5.dp.toPx(), center = point(it)) }
    }
    Row {
        Text("0 s", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(GenerationStatistics.duration(run.totalMs), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (curve.zipWithNext().any { (a, b) -> b.first - a.first > GAP_MS }) {
        Text(
            "The dashed part has no readings: the server was too busy loading to answer.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

// Readings further apart than this are a stretch without answers (the pings come every 1 or 2 s).
private const val GAP_MS = 2_500L
