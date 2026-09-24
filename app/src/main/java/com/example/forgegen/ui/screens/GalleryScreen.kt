package com.example.forgegen.ui.screens
import com.example.forgegen.*
import com.example.forgegen.ui.components.*

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage

/* ============================================================================
 * SHIMMER EFFECT (SKELETON LOADING)
 * An animated gradient on placeholders while something loads. Each placeholder runs it only while it is shown.
 * ============================================================================ */

@Composable
fun coloredShimmerBrush(baseColor: Color): Brush {
    val shimmerColors =
        listOf(
            baseColor.copy(alpha = 0.2f),
            baseColor.copy(alpha = 0.8f),
            baseColor.copy(alpha = 0.2f),
        )
    val transition = rememberInfiniteTransition(label = "shimmer_${baseColor.value}")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 1000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
        label = "shimmer_translate_${baseColor.value}",
    )
    return Brush.linearGradient(
        colors = shimmerColors,
        start = Offset.Zero,
        end = Offset(x = translateAnim, y = translateAnim),
    )
}

enum class ActiveMenu { NONE, FILTER, SORT, SETTINGS }

@Composable
fun shimmerBrush(): Brush = coloredShimmerBrush(MaterialTheme.colorScheme.surfaceVariant)

private val FavoriteGold = Color(0xFFFFD54F)

/* ============================================================================
 * GALLERY SCREEN COMPOSABLE
 * ============================================================================ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    viewModel: ForgeViewModel,
    navController: NavHostController,
) {
    var activeMenu by remember { mutableStateOf(ActiveMenu.NONE) }
    val galleryFilters by viewModel.galleryFilters.collectAsStateWithLifecycle()
    val availableModels by viewModel.availableModels.collectAsStateWithLifecycle()
    val availableLoras by viewModel.galleryAvailableLoras.collectAsStateWithLifecycle()

    var tempFilters by remember { mutableStateOf(galleryFilters) }
    var showBasePathDialog by remember { mutableStateOf(false) }
    var showPathDialog by remember { mutableStateOf(false) }

    LaunchedEffect(activeMenu, galleryFilters) {
        if (activeMenu != ActiveMenu.NONE) {
            tempFilters = galleryFilters
        }
    }

    val displayedFiles by viewModel.displayedFiles.collectAsStateWithLifecycle()
    val currentPath by viewModel.currentGalleryPath.collectAsStateWithLifecycle()
    val isLoading by viewModel.isGalleryLoading.collectAsStateWithLifecycle()
    val error by viewModel.galleryError.collectAsStateWithLifecycle()
    val galleryMode by viewModel.galleryMode.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()

    val isGallerySyncing by viewModel.isGallerySyncing.collectAsStateWithLifecycle()
    val isIndexing by viewModel.isGalleryIndexing.collectAsStateWithLifecycle()
    val indexedImageCount by viewModel.galleryIndexedImageCount.collectAsStateWithLifecycle()
    val gallerySyncCurrentFile by viewModel.gallerySyncCurrentFile.collectAsStateWithLifecycle()
    val gallerySyncProgress by viewModel.gallerySyncProgress.collectAsStateWithLifecycle()

    // Subscription to the list of favorite paths
    val favoritePaths by viewModel.favoritePaths.collectAsStateWithLifecycle()

    var fullscreenIndex by remember { mutableIntStateOf(-1) }

    // Keep the index (search, "All Images") up to date: a quick sync that only asks for changed folders.
    LaunchedEffect(Unit) { viewModel.autoSyncGallery() }

    val galleryRoot = config.galleryPath.ifEmpty { "Root" }
    val isSearch = galleryFilters.isSearch

    val safePopBack = {
        if (navController.currentDestination?.route == "gallery") {
            navController.popBackStack()
        }
    }

    val onBack = {
        if (error != null) {
            safePopBack()
        } else if (currentPath.startsWith("virtual://")) {
            // Favorites and All Images are opened from the gallery's top folder, so Back returns there.
            viewModel.fetchGalleryFolder(galleryRoot)
        } else if (currentPath.isNotEmpty() && currentPath != "Root" && currentPath != config.galleryPath) {
            val lastSlash = currentPath.lastIndexOf('/')
            val lastBackslash = currentPath.lastIndexOf('\\')
            val lastSeparator = maxOf(lastSlash, lastBackslash)

            val parent =
                if (lastSeparator > 0) {
                    currentPath.substring(0, lastSeparator)
                } else {
                    config.galleryPath
                }

            if (config.galleryPath.isNotEmpty() && config.galleryPath != "Root" && !parent.startsWith(config.galleryPath)) {
                safePopBack()
            } else {
                viewModel.fetchGalleryFolder(parent)
            }
        } else {
            safePopBack()
        }
    }

    BackHandler(onBack = {
        if (fullscreenIndex >= 0) {
            fullscreenIndex = -1
        } else {
            onBack()
        }
    })

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (galleryMode == GalleryMode.PROMPT_PICKER) "Select Image" else "Gallery") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { activeMenu = if (activeMenu == ActiveMenu.SETTINGS) ActiveMenu.NONE else ActiveMenu.SETTINGS }) {
                        Icon(Icons.Default.Settings, "Settings")
                    }
                    IconButton(onClick = { activeMenu = if (activeMenu == ActiveMenu.SORT) ActiveMenu.NONE else ActiveMenu.SORT }) {
                        Icon(Icons.Default.Sort, "Sort")
                    }
                    IconButton(onClick = { activeMenu = if (activeMenu == ActiveMenu.FILTER) ActiveMenu.NONE else ActiveMenu.FILTER }) {
                        Icon(
                            Icons.Default.FilterList,
                            "Filter",
                            tint = if (isSearch) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                        )
                    }
                    IconButton(onClick = { viewModel.triggerManualGallerySync() }) {
                        Icon(Icons.Default.Sync, "Sync Database")
                    }
                    IconButton(onClick = { viewModel.fetchGalleryFolder(currentPath) }) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                }
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // BREADCRUMB NAVIGATION
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isSearch) {
                    Text(
                        "Search in the whole gallery: ${displayedFiles.size} found",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else if (currentPath == ForgeGalleryManager.FAVORITES) {
                    Text("⭐ Favorites", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                } else if (currentPath == ForgeGalleryManager.ALL_IMAGES) {
                    Text("🕒 All Images", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                } else {
                    val pathSegments = if (currentPath.isEmpty()) listOf("Root") else listOf("Root") + currentPath.split(Regex("[/\\\\]")).filter { it.isNotEmpty() }
                    pathSegments.forEachIndexed { index, segment ->
                        val isLast = index == pathSegments.size - 1
                        Text(
                            text = segment,
                            fontSize = 14.sp,
                            fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                            color = if (isLast) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable(enabled = !isLast) {
                                    if (index == 0) {
                                        viewModel.fetchGalleryFolder(galleryRoot)
                                    } else {
                                        // Reconstruct path up to this segment; split() dropped the leading "/" of a Linux path.
                                        val root = if (currentPath.startsWith("/")) "/" else ""
                                        val subPath = root + pathSegments.drop(1).take(index).joinToString("/")
                                        viewModel.fetchGalleryFolder(subPath)
                                    }
                                }
                                .padding(vertical = 4.dp)
                        )
                        if (!isLast) {
                            Icon(
                                Icons.Default.KeyboardArrowRight,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp).padding(horizontal = 4.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // A quiet index sync (or one sent to the background) shows only this thin bar.
            AnimatedVisibility(
                visible = isIndexing && isGallerySyncing == IndicatorState.IDLE,
                enter = fadeIn(tween(OVERLAY_FADE_MS)),
                exit = fadeOut(tween(OVERLAY_FADE_MS)),
            ) {
                val (done, total) = gallerySyncProgress
                if (total > 0) {
                    LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth().height(2.dp))
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // SLIDE-DOWN MENUS
            AnimatedVisibility(
                visible = activeMenu != ActiveMenu.NONE,
                enter = expandVertically(animationSpec = tween(200, easing = LinearOutSlowInEasing)),
                exit = shrinkVertically(animationSpec = tween(200, easing = FastOutLinearInEasing)),
                modifier = Modifier.align(Alignment.TopCenter).zIndex(100f)
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth().zIndex(100f),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
                    tonalElevation = 8.dp,
                    shadowElevation = 8.dp
                ) {
                    if (activeMenu == ActiveMenu.SORT) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Sort By", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Button(onClick = { viewModel.applyGalleryFilters(tempFilters); activeMenu = ActiveMenu.NONE }) { Text("Confirm") }
                            }
                            Spacer(Modifier.height(8.dp))

                            val sortOptions = listOf(
                                ForgeGalleryManager.SortOrder.NEWEST to "Newest First",
                                ForgeGalleryManager.SortOrder.OLDEST to "Oldest First",
                                ForgeGalleryManager.SortOrder.NAME_ASC to "A-Z (Alphabetical)",
                                ForgeGalleryManager.SortOrder.NAME_DESC to "Z-A (Reverse Alphabetical)"
                            )

                            sortOptions.forEach { (order, label) ->
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { tempFilters = tempFilters.copy(sortOrder = order) }.padding(vertical = 4.dp)) {
                                    RadioButton(selected = tempFilters.sortOrder == order, onClick = { tempFilters = tempFilters.copy(sortOrder = order) })
                                    Spacer(Modifier.width(8.dp))
                                    Text(label)
                                }
                            }
                        }
                    } else if (activeMenu == ActiveMenu.FILTER) {
                        Column(modifier = Modifier.padding(16.dp).heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Search Gallery", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Row {
                                    TextButton(onClick = {
                                        viewModel.clearGalleryFilters()
                                        activeMenu = ActiveMenu.NONE
                                    }) { Text("Clear All") }
                                    Spacer(Modifier.width(8.dp))
                                    Button(onClick = {
                                        viewModel.applyGalleryFilters(tempFilters)
                                        activeMenu = ActiveMenu.NONE
                                    }) { Text("Confirm") }
                                }
                            }
                            Text(
                                "Searches all indexed images of the gallery, not only this folder ($indexedImageCount indexed).",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))

                            OutlinedTextField(
                                value = tempFilters.name,
                                onValueChange = { tempFilters = tempFilters.copy(name = it) },
                                label = { Text("File Name") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))

                            OutlinedTextField(
                                value = tempFilters.prompt,
                                onValueChange = { tempFilters = tempFilters.copy(prompt = it) },
                                label = { Text("Prompt Tag (Pos/Neg)") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(16.dp))

                            // Models section: an image has one model, so any of the selected ones matches.
                            var modelsExpanded by remember { mutableStateOf(false) }
                            Row(modifier = Modifier.fillMaxWidth().clickable { modelsExpanded = !modelsExpanded }.padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Models (${tempFilters.models.size} selected)", fontWeight = FontWeight.Bold)
                                Text(if (modelsExpanded) "▲" else "▼")
                            }
                            if (modelsExpanded) {
                                if (availableModels.isEmpty()) {
                                    Text("No models indexed.", color = Color.Gray, modifier = Modifier.padding(start = 8.dp))
                                } else {
                                    availableModels.forEach { model ->
                                        val isChecked = tempFilters.models.contains(model)
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable {
                                            val newModels = tempFilters.models.toMutableSet()
                                            if (isChecked) newModels.remove(model) else newModels.add(model)
                                            tempFilters = tempFilters.copy(models = newModels)
                                        }.padding(start = 8.dp, top = 2.dp, bottom = 2.dp)) {
                                            Checkbox(checked = isChecked, onCheckedChange = null)
                                            Spacer(Modifier.width(8.dp))
                                            Text(model, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))

                            // Loras section
                            var lorasExpanded by remember { mutableStateOf(false) }
                            Row(modifier = Modifier.fillMaxWidth().clickable { lorasExpanded = !lorasExpanded }.padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("LoRAs (${tempFilters.loras.size} selected)", fontWeight = FontWeight.Bold)
                                Text(if (lorasExpanded) "▲" else "▼")
                            }
                            if (lorasExpanded) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                                    Text("Match:", style = MaterialTheme.typography.bodySmall)
                                    Spacer(Modifier.width(8.dp))
                                    FilterChip(selected = !tempFilters.lorasIsAnd, onClick = { tempFilters = tempFilters.copy(lorasIsAnd = false) }, label = { Text("Any") })
                                    Spacer(Modifier.width(8.dp))
                                    FilterChip(selected = tempFilters.lorasIsAnd, onClick = { tempFilters = tempFilters.copy(lorasIsAnd = true) }, label = { Text("All") })
                                }
                                if (availableLoras.isEmpty()) {
                                    Text("No LoRAs indexed.", color = Color.Gray, modifier = Modifier.padding(start = 8.dp))
                                } else {
                                    availableLoras.forEach { lora ->
                                        val isChecked = tempFilters.loras.contains(lora)
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable {
                                            val newLoras = tempFilters.loras.toMutableSet()
                                            if (isChecked) newLoras.remove(lora) else newLoras.add(lora)
                                            tempFilters = tempFilters.copy(loras = newLoras)
                                        }.padding(start = 8.dp, top = 2.dp, bottom = 2.dp)) {
                                            Checkbox(checked = isChecked, onCheckedChange = null)
                                            Spacer(Modifier.width(8.dp))
                                            Text(lora, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                    } else if (activeMenu == ActiveMenu.SETTINGS) {
                        Column(modifier = Modifier.padding(16.dp).heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Gallery Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                IconButton(onClick = { activeMenu = ActiveMenu.NONE }) {
                                    Icon(Icons.Default.Close, "Close")
                                }
                            }
                            Spacer(Modifier.height(8.dp))

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.showToast("Requesting paths from server...")
                                        viewModel.fetchAutoConfig()
                                    }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = "Auto-Config Gallery Path", fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground)
                                    Text(
                                        text = "Tap to auto-detect base and gallery folders from server",
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                                        lineHeight = 18.sp,
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showBasePathDialog = true }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = "Server Base Path", fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground)
                                    Text(
                                        text = config.serverBasePath.ifEmpty { "Not set" },
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showPathDialog = true }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = "Gallery Server Path", fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground)
                                    Text(
                                        text = config.galleryPath.ifEmpty { "Not set" },
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.saveConfig(config.copy(swipeToBrowseGallery = !config.swipeToBrowseGallery)) }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = "Swipe to Browse Images", fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground)
                                    Text(
                                        text = "Use horizontal swiping in fullscreen preview",
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                                        lineHeight = 18.sp,
                                    )
                                }
                                Switch(
                                    checked = config.swipeToBrowseGallery,
                                    onCheckedChange = null,
                                )
                            }

                            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                                Text(text = "Save to Phone Automatically", fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground)
                                val autoSaveDescription =
                                    when (config.autoSaveMode) {
                                        AUTO_SAVE_FAVORITES -> "Images you star are saved to Pictures/ForgeGen"
                                        AUTO_SAVE_ALL ->
                                            "New images from the server are saved to Pictures/ForgeGen on Wi-Fi, " +
                                                "while the app runs (also those made on the PC)"
                                        else -> "Images are only saved when you tap Save"
                                    }
                                Text(
                                    text = autoSaveDescription,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                                    lineHeight = 18.sp,
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(AUTO_SAVE_OFF, AUTO_SAVE_FAVORITES, AUTO_SAVE_ALL).forEach { mode ->
                                        FilterChip(
                                            selected = config.autoSaveMode == mode,
                                            onClick = { viewModel.setAutoSaveMode(mode) },
                                            label = { Text(mode) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }


                if (isLoading) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(4.dp),
                ) {
                    items(24) {
                        Box(
                            modifier =
                                Modifier
                                    .padding(4.dp)
                                    .aspectRatio(1f)
                                    .clip(MaterialTheme.shapes.small)
                                    .background(shimmerBrush()),
                        )
                    }
                }
            } else if (error != null) {
                Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.ErrorOutline, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    Text(error!!, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { viewModel.fetchGalleryFolder(currentPath) }) { Text("Retry") }
                }
            } else if (displayedFiles.isEmpty()) {
                val emptyText =
                    when {
                        (isSearch || currentPath == ForgeGalleryManager.ALL_IMAGES) && indexedImageCount == 0 ->
                            if (isIndexing) "Indexing the gallery..." else "The gallery is not indexed yet. Tap Sync to index it."
                        isSearch -> "No images match the search"
                        else -> "No files found"
                    }
                Text(emptyText, modifier = Modifier.align(Alignment.Center).padding(16.dp), color = Color.Gray, textAlign = TextAlign.Center)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(4.dp),
                ) {
                    itemsIndexed(displayedFiles, key = { _, item -> item.fullpath }) { index, item ->
                        if (item.isDir) {
                            val folderIcon =
                                when (item.fullpath) {
                                    ForgeGalleryManager.FAVORITES -> Icons.Default.Star
                                    ForgeGalleryManager.ALL_IMAGES -> Icons.Default.Schedule
                                    else -> Icons.Default.Folder
                                }
                            val folderColor = if (item.fullpath == ForgeGalleryManager.FAVORITES) FavoriteGold else MaterialTheme.colorScheme.primary
                            Card(
                                modifier = Modifier.padding(4.dp).aspectRatio(1f).clickable { viewModel.fetchGalleryFolder(item.fullpath) },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize().padding(8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Icon(
                                        imageVector = folderIcon,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp),
                                        tint = folderColor,
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        item.name,
                                        fontSize = 12.sp,
                                        textAlign = TextAlign.Center,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        } else {
                            val isFavorite = favoritePaths.contains(item.fullpath) || currentPath == ForgeGalleryManager.FAVORITES

                            // A still gold frame: the animated one kept redrawing every favorite as long as the gallery was open.
                            val frameModifier =
                                if (isFavorite) Modifier.border(4.dp, FavoriteGold, MaterialTheme.shapes.small) else Modifier

                            Box(
                                modifier =
                                    Modifier
                                        .padding(4.dp)
                                        .aspectRatio(1f)
                                        .then(frameModifier)
                                        .clip(MaterialTheme.shapes.small)
                                        .clickable {
                                            if (galleryMode == GalleryMode.PROMPT_PICKER) {
                                                viewModel.recoverPromptFromImage(item)
                                                if (navController.currentDestination?.route == "gallery") {
                                                    navController.popBackStack()
                                                }
                                            } else {
                                                fullscreenIndex = index
                                            }
                                        },
                            ) {
                                SubcomposeAsyncImage(
                                    model = viewModel.getGalleryThumbnailUrl(item),
                                    contentDescription = item.name,
                                    loading = {
                                        Box(modifier = Modifier.fillMaxSize().background(shimmerBrush()))
                                    },
                                    // Very old gallery extensions have no thumbnails; show the image itself then.
                                    error = {
                                        AsyncImage(
                                            model = viewModel.getGalleryImageUrl(item),
                                            contentDescription = item.name,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop,
                                        )
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                )
                                Box(
                                    modifier =
                                        Modifier
                                            .align(Alignment.BottomCenter)
                                            .fillMaxWidth()
                                            .background(Color.Black.copy(alpha = 0.6f))
                                            .padding(vertical = 4.dp, horizontal = 2.dp),
                                ) {
                                    Text(
                                        text = item.name,
                                        color = Color.White,
                                        fontSize = 9.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // FAIL-SAFE: IndexOutOfBoundsException Fail-Safe
        if (fullscreenIndex >= 0) {
            if (displayedFiles.isEmpty()) {
                fullscreenIndex = -1
            } else {
                val safeIndex = fullscreenIndex.coerceIn(0, displayedFiles.size - 1)
                val imageFiles = displayedFiles.filter { !it.isDir }
                val targetFile = displayedFiles[safeIndex]
                val initialPage = imageFiles.indexOf(targetFile).coerceAtLeast(0)

                FullscreenGalleryViewer(
                    viewModel = viewModel,
                    config = config,
                    images = imageFiles,
                    initialIndex = initialPage,
                    onDismiss = { fullscreenIndex = -1 },
                )
            }
        }

        if (showBasePathDialog) {
            var tempPath by remember { mutableStateOf(config.serverBasePath) }
            AlertDialog(
                onDismissRequest = { showBasePathDialog = false },
                title = { Text("Server Base Path") },
                text = { OutlinedTextField(value = tempPath, onValueChange = { tempPath = it }, modifier = Modifier.fillMaxWidth()) },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.saveConfig(config.copy(serverBasePath = tempPath))
                        showBasePathDialog = false
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { showBasePathDialog = false }) { Text("Cancel") } },
            )
        }

        if (showPathDialog) {
            var inputPath by remember { mutableStateOf(config.galleryPath) }
            AlertDialog(
                onDismissRequest = { showPathDialog = false },
                title = { Text("Gallery Server Path") },
                text = {
                    Column {
                        Text("Enter the remote path for IIB gallery.", fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = inputPath,
                            onValueChange = { inputPath = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.saveConfig(config.copy(galleryPath = inputPath))
                        showPathDialog = false
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { showPathDialog = false }) { Text("Cancel") }
                }
            )
        }

        // GALLERY SYNC PROGRESS OVERLAY (fades in and out like the other overlays)
        val shownSyncState = rememberLastActive(isGallerySyncing, IndicatorState.IDLE)
        AnimatedVisibility(
            visible = isGallerySyncing != IndicatorState.IDLE,
            modifier = Modifier.zIndex(150f),
            enter = fadeIn(tween(OVERLAY_FADE_MS)),
            exit = fadeOut(tween(OVERLAY_FADE_MS)),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.7f))
                        .clickable(enabled = false) {},
                contentAlignment = Alignment.Center,
            ) {
                Card(
                    modifier =
                        Modifier
                            .padding(32.dp)
                            .fillMaxWidth(0.85f)
                            .animateContentSize(animationSpec = tween(200, easing = FastOutSlowInEasing)),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        AnimatedStatusIndicator(state = shownSyncState)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Gallery Sync", fontWeight = FontWeight.Bold, fontSize = 18.sp, textAlign = TextAlign.Center)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Reading generation data:", fontSize = 12.sp, color = Color.Gray)
                        Text(
                            text = gallerySyncCurrentFile.ifEmpty { "..." },
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        val (current, total) = gallerySyncProgress
                        LinearProgressIndicator(
                            progress = { if (total > 0) current.toFloat() / total.toFloat() else 0f },
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (total > 0) "$current / $total new images" else "Looking for new images...",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )

                        var showButtons by remember { mutableStateOf(false) }
                        LaunchedEffect(Unit) {
                            kotlinx.coroutines.delay(1000)
                            showButtons = true
                        }

                        if (showButtons && shownSyncState == IndicatorState.LOADING) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                OutlinedButton(onClick = { viewModel.putSyncToBackground() }) {
                                    Text("Background")
                                }
                                Button(
                                    onClick = { viewModel.cancelManualGallerySync() },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Text("Cancel")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FullscreenGalleryViewer(
    viewModel: ForgeViewModel,
    config: AppConfig,
    images: List<GalleryItem>,
    initialIndex: Int,
    onDismiss: () -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { images.size })
    val currentItem = images.getOrNull(pagerState.currentPage)
    val context = LocalContext.current

    val favoritePaths by viewModel.favoritePaths.collectAsStateWithLifecycle()
    val isFavorite = currentItem != null && currentItem.fullpath in favoritePaths
    val showMetadata by viewModel.showGalleryMetadata.collectAsStateWithLifecycle()

    // Generation data is only fetched while the info panel is open.
    LaunchedEffect(currentItem?.fullpath, showMetadata) {
        if (showMetadata && currentItem != null) viewModel.loadMetadataForImage(currentItem)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            Row(
                modifier = Modifier.fillMaxWidth().background(Color(0x88000000)).padding(vertical = 4.dp, horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
                Text(
                    currentItem?.name ?: "",
                    color = Color.White,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )

                IconButton(onClick = {
                    currentItem?.let { viewModel.toggleFavorite(it) }
                }) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = "Favorite",
                        tint = if (isFavorite) FavoriteGold else Color.White,
                    )
                }

                IconButton(onClick = {
                    currentItem?.let {
                        viewModel.shareImage(it) { intent -> context.startActivity(intent) }
                    }
                }) {
                    Icon(Icons.Default.Share, "Share", tint = Color.White)
                }

                IconButton(onClick = { viewModel.toggleGalleryMetadata() }) {
                    Icon(
                        Icons.Default.Info,
                        "Info",
                        tint = Color.White,
                        modifier =
                            Modifier.then(
                                if (showMetadata) Modifier.background(Color(0x55FFFFFF), CircleShape).padding(2.dp) else Modifier,
                            ),
                    )
                }
                IconButton(onClick = { currentItem?.let { viewModel.downloadImage(it) } }) {
                    Icon(Icons.Default.Save, "Save", tint = Color.White)
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (config.swipeToBrowseGallery) {
                    // The neighbouring images load in advance, so swiping does not wait for the network.
                    HorizontalPager(state = pagerState, beyondViewportPageCount = 1, modifier = Modifier.fillMaxSize()) { page ->
                        FullImage(viewModel, images[page])
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        if (currentItem != null) FullImage(viewModel, currentItem)
                    }
                }

                val currentMetadata by viewModel.currentImageMetadata.collectAsStateWithLifecycle()

                if (showMetadata) {
                    val fileInfo =
                        remember(currentItem) {
                            if (currentItem != null) {
                                val sizeStr = currentItem.displaySize
                                val timeStr =
                                    currentItem.createdTime?.let { timeVal ->
                                        try {
                                            val timeLong = timeVal.toDouble().toLong() * 1000
                                            java.text
                                                .SimpleDateFormat(
                                                    "dd MMM yyyy, HH:mm",
                                                    java.util.Locale.getDefault(),
                                                ).format(java.util.Date(timeLong))
                                        } catch (e: Exception) {
                                            timeVal
                                        }
                                    } ?: currentItem.date

                                val parts =
                                    listOfNotNull(
                                        currentItem.name,
                                        timeStr,
                                        sizeStr.takeIf { it.isNotEmpty() },
                                    )
                                parts.joinToString(" • ")
                            } else {
                                null
                            }
                        }

                    AppMetadataAlertDialog(
                        metadata = currentMetadata,
                        fileInfo = fileInfo,
                        onDismiss = { viewModel.toggleGalleryMetadata() },
                        onApplyAll = null,
                        onApplyPrompt = { pos, neg ->
                            viewModel.updateState { it.copy(positivePrompt = pos, negativePrompt = neg) }
                            viewModel.showToast("Prompts Applied")
                        },
                        onApplyModel = { modelName ->
                            viewModel.changeCheckpoint(modelName)
                            viewModel.showToast("Model Applied: $modelName")
                        },
                        onApplyLoras = { loraList ->
                            loraList.forEach { loraTag ->
                                val loraName = loraTag.substringAfter("<lora:").substringBefore(":")
                                if (loraName.isNotEmpty()) viewModel.appendLora(loraName)
                            }
                            viewModel.showToast("LoRAs Applied")
                        },
                    )
                }
            }
        }
    }
}

/** The full image; its thumbnail (already cached by the grid) is shown at once while the full one loads. */
@Composable
private fun FullImage(
    viewModel: ForgeViewModel,
    item: GalleryItem,
) {
    SubcomposeAsyncImage(
        model = viewModel.getGalleryImageUrl(item),
        contentDescription = null,
        loading = {
            Box(modifier = Modifier.fillMaxWidth().heightIn(min = 400.dp), contentAlignment = Alignment.Center) {
                AsyncImage(
                    model = viewModel.getGalleryThumbnailUrl(item),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.TopCenter,
                )
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        },
        modifier = Modifier.fillMaxWidth(),
        contentScale = ContentScale.Fit,
        alignment = Alignment.TopCenter,
    )
}
