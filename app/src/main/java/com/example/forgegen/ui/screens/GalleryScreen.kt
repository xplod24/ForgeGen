package com.example.forgegen.ui.screens
import com.example.forgegen.*
import com.example.forgegen.ui.components.*

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutLinearInEasing
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
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/* ============================================================================
 * SHIMMER EFFECT (SKELETON LOADING)
 * An animated gradient on placeholders while something loads. It is only drawn (the animation never recomposes the
 * placeholder), and each placeholder runs it only while it is shown.
 * ============================================================================ */

@Composable
fun Modifier.shimmer(baseColor: Color = MaterialTheme.colorScheme.surfaceVariant): Modifier {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translate =
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1000f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 1000, easing = LinearEasing), RepeatMode.Restart),
            label = "shimmer_translate",
        )
    val colors = remember(baseColor) { listOf(baseColor.copy(alpha = 0.2f), baseColor.copy(alpha = 0.8f), baseColor.copy(alpha = 0.2f)) }
    return drawBehind {
        val t = translate.value
        drawRect(Brush.linearGradient(colors, start = Offset.Zero, end = Offset(t, t)))
    }
}

enum class ActiveMenu { NONE, FILTER, SORT, SETTINGS }

private val FavoriteGold = Color(0xFFFFD54F)

/** Slide-down panels (sort, search, settings): the same speed in and out. */
private const val MENU_ANIMATION_MS = 200

/* ============================================================================
 * GALLERY SCREEN COMPOSABLE
 * The server's images through its Infinite Image Browsing extension (without it the gallery only explains what is
 * missing). The index behind "All Images" and the search keeps itself up to date; the layout is a grid of 2 to 5
 * columns or a list with the prompt, date and quick buttons (2.1.0).
 * ============================================================================ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    viewModel: ForgeViewModel,
    navController: NavHostController,
) {
    var activeMenu by remember { mutableStateOf(ActiveMenu.NONE) }
    val galleryFilters by viewModel.galleryFilters.collectAsStateWithLifecycle()

    var tempFilters by remember { mutableStateOf(galleryFilters) }

    LaunchedEffect(activeMenu, galleryFilters) {
        if (activeMenu != ActiveMenu.NONE) {
            tempFilters = galleryFilters
        }
    }

    val folder by viewModel.galleryFolder.collectAsStateWithLifecycle()
    val favorites by viewModel.favoriteImages.collectAsStateWithLifecycle()
    val allImages by viewModel.allImages.collectAsStateWithLifecycle()
    val tab by viewModel.galleryTab.collectAsStateWithLifecycle()
    val currentPath by viewModel.currentGalleryPath.collectAsStateWithLifecycle()
    val isLoading by viewModel.isGalleryLoading.collectAsStateWithLifecycle()
    val error by viewModel.galleryError.collectAsStateWithLifecycle()
    val galleryMode by viewModel.galleryMode.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()
    val extension by viewModel.galleryExtension.collectAsStateWithLifecycle()
    val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()

    val isIndexing by viewModel.isGalleryIndexing.collectAsStateWithLifecycle()
    val indexError by viewModel.galleryIndexError.collectAsStateWithLifecycle()
    val indexedImageCount by viewModel.galleryIndexedImageCount.collectAsStateWithLifecycle()
    val indexLoaded by viewModel.galleryIndexLoaded.collectAsStateWithLifecycle()
    val gallerySyncProgress by viewModel.gallerySyncProgress.collectAsStateWithLifecycle()

    // Subscription to the list of favorite paths
    val favoritePaths by viewModel.favoritePaths.collectAsStateWithLifecycle()

    // 3.2.0: deleting with Undo, moving and copying (where the server allows it), folder covers, favorites gone
    // from the server, and All Images shuffled.
    val pendingDelete by viewModel.galleryPendingDelete.collectAsStateWithLifecycle()
    val folderCovers by viewModel.galleryFolderCovers.collectAsStateWithLifecycle()
    val folderCounts by viewModel.galleryFolderImageCounts.collectAsStateWithLifecycle()
    val missingFavorites by viewModel.missingFavorites.collectAsStateWithLifecycle()
    val canWrite = extension.canWrite
    // The images the folder picker moves or copies, and which of the two it offers first.
    var transfer by remember { mutableStateOf<Pair<List<GalleryItem>, ForgeGalleryManager.Transfer>?>(null) }

    val view = GalleryView.of(config.galleryView)
    // The image shown full screen: the tab it was opened from and its place in that tab's list.
    var fullscreen by remember { mutableStateOf<Pair<GalleryTab, Int>?>(null) }

    // The index behind search and "All Images" keeps itself up to date: an update when the gallery opens.
    LaunchedEffect(Unit) { viewModel.autoSyncGallery() }
    // New images are indexed at once only while the gallery shows (3.4.0).
    DisposableEffect(Unit) {
        viewModel.setGalleryVisible(true)
        onDispose { viewModel.setGalleryVisible(false) }
    }

    // The extension was found while the gallery was open (e.g. the server came back): show its top folder.
    LaunchedEffect(extension.state) {
        if (extension.state == ForgeGalleryManager.Extension.READY && viewModel.currentGalleryPath.value.isEmpty()) {
            viewModel.openGallery(galleryMode)
        }
    }

    val isSearch = galleryFilters.isSearch
    // Without the extension there is nothing to browse: the screen says why instead.
    val showExtensionStatus =
        extension.state == ForgeGalleryManager.Extension.MISSING ||
            extension.state == ForgeGalleryManager.Extension.FAILED ||
            extension.state == ForgeGalleryManager.Extension.LOCKED ||
            extension.state == ForgeGalleryManager.Extension.KEY_NOT_SET ||
            (currentPath.isEmpty() && extension.state != ForgeGalleryManager.Extension.READY)

    // TABS (2.2.0): Gallery | Favorites | All Images, switched with a tap or a swipe; the gallery opens on the one
    // used last.
    val pagerState = rememberPagerState(initialPage = tab.ordinal) { GalleryTab.entries.size }
    val shownTab = GalleryTab.entries[pagerState.currentPage]
    val scope = rememberCoroutineScope()
    LaunchedEffect(tab) {
        if (pagerState.currentPage != tab.ordinal) pagerState.animateScrollToPage(tab.ordinal)
    }
    LaunchedEffect(pagerState.settledPage) { viewModel.selectGalleryTab(GalleryTab.entries[pagerState.settledPage]) }

    // SCROLL MEMORY: each tab keeps its place, and the Gallery tab one per folder (while the app runs, also when the
    // gallery is closed and opened again). A list made with other filters (a search, another order) starts at the top.
    val positions = viewModel.galleryScroll
    val favoritesState = rememberGalleryState(positions, FAVORITES_SCROLL)
    val allState = rememberGalleryState(positions, ALL_IMAGES_SCROLL)
    val folderState = rememberGalleryState(positions, folderScrollKey(folder.path))
    RecordScroll(favoritesState) { if (favorites.items.isNotEmpty()) positions[FAVORITES_SCROLL] = it }
    RecordScroll(allState) { if (allImages.items.isNotEmpty()) positions[ALL_IMAGES_SCROLL] = it }
    var restoredFolder by remember { mutableStateOf(folder.path) }
    RecordScroll(folderState) {
        // Only the folder it belongs to: while another one comes in, the old list's place is not the new folder's.
        if (restoredFolder == folder.path && !folder.isSearch && folder.items.isNotEmpty()) positions[folderScrollKey(folder.path)] = it
    }
    LaunchedEffect(folder.path) {
        if (folder.path != restoredFolder) {
            val start = positions[folderScrollKey(folder.path)] ?: GalleryScrollPosition(0, 0)
            folderState.scrollToItem(start.index, start.offset)
            restoredFolder = folder.path
        }
    }
    ScrollToTopOnNewFilters(folder.filters, folderState)
    ScrollToTopOnNewFilters(favorites.filters, favoritesState)
    ScrollToTopOnNewFilters(allImages.filters to allImages.order, allState) // shuffled again: from the top too

    fun itemsOf(tab: GalleryTab): List<GalleryItem> =
        when (tab) {
            GalleryTab.GALLERY -> folder.items
            GalleryTab.FAVORITES -> favorites.items
            GalleryTab.ALL_IMAGES -> allImages.items
        }

    val safePopBack = {
        if (navController.currentDestination?.route == "gallery") {
            navController.popBackStack()
        }
    }

    // Back: a search is cleared first; the Gallery tab goes up a folder (never above the gallery's top folder).
    val onBack = {
        val parent = viewModel.galleryParentFolder(currentPath)
        when {
            showExtensionStatus || error != null -> safePopBack()
            isSearch -> viewModel.clearGalleryFilters()
            shownTab == GalleryTab.GALLERY && parent != null -> viewModel.fetchGalleryFolder(parent)
            else -> safePopBack()
        }
    }

    // Several images selected with a long press: saved, shared or made favorites at once.
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val selectionMode = selected.isNotEmpty()
    val selectedItems = { itemsOf(shownTab).filter { !it.isDir && it.fullpath in selected } }
    val context = LocalContext.current
    LaunchedEffect(currentPath, galleryFilters, shownTab) { selected = emptySet() }
    // Favorites whose files are gone from the server are looked for when the tab shows (at most every 5 minutes).
    LaunchedEffect(shownTab, favoritePaths.size) { if (shownTab == GalleryTab.FAVORITES) viewModel.checkFavorites() }

    BackHandler(onBack = {
        when {
            fullscreen != null -> fullscreen = null
            activeMenu != ActiveMenu.NONE -> activeMenu = ActiveMenu.NONE
            selectionMode -> selected = emptySet()
            else -> onBack()
        }
    })

    // A tap on a folder opens it; on an image it selects (in selection mode), picks its prompt or shows it.
    val onItemClick: (GalleryTab, Int, GalleryItem) -> Unit = { itemTab, index, item ->
        val isSelected = item.fullpath in selected
        when {
            item.isDir -> viewModel.fetchGalleryFolder(item.fullpath)
            selectionMode -> selected = if (isSelected) selected - item.fullpath else selected + item.fullpath
            galleryMode == GalleryMode.PROMPT_PICKER -> {
                viewModel.recoverPromptFromImage(item)
                safePopBack()
            }
            else -> fullscreen = itemTab to index
        }
    }
    val onItemLongClick: (GalleryItem) -> Unit = { item ->
        if (!item.isDir && galleryMode != GalleryMode.PROMPT_PICKER) selected = selected + item.fullpath
    }
    val toggleMenu: (ActiveMenu) -> Unit = { menu -> activeMenu = if (activeMenu == menu) ActiveMenu.NONE else menu }

    Scaffold(
        topBar = {
            if (selectionMode) {
                FloatingTopBar(
                    // One line: five actions leave little room on a narrow phone.
                    title = "${selected.size} selected",
                    onNavigate = { selected = emptySet() },
                    navigationIcon = Icons.Default.Close,
                    navigationDescription = "Clear Selection",
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                        IconButton(onClick = { selected = itemsOf(shownTab).filter { !it.isDir }.map { it.fullpath }.toSet() }) {
                            Icon(Icons.Default.SelectAll, "Select All")
                        }
                        IconButton(onClick = {
                            viewModel.addFavorites(selectedItems())
                            selected = emptySet()
                        }) { Icon(Icons.Default.Star, "Add to Favorites") }
                        IconButton(onClick = {
                            viewModel.shareImages(selectedItems()) { intent -> context.startActivity(intent) }
                            selected = emptySet()
                        }) { Icon(Icons.Default.Share, "Share") }
                        // The rest in one menu (3.2.0, board 3A).
                        SelectionMoreMenu(
                            canWrite = canWrite,
                            onSave = {
                                viewModel.downloadImages(selectedItems())
                                selected = emptySet()
                            },
                            onZip = {
                                viewModel.downloadGalleryZip(selectedItems())
                                selected = emptySet()
                            },
                            // Each image made again with hires fix, one queue job each (2.4.0); the dialog's second
                            // tab makes them again with their seeds, varied ("Variance on Seed", 3.0.0). Not with
                            // Settings > Features > Image Jobs off (3.4.0).
                            onUpscale = { viewModel.requestImageJobs(ImageJobs.Kind.UPSCALE, selectedItems()) }.takeIf { config.imageJobs },
                            onMove = { transfer = selectedItems() to ForgeGalleryManager.Transfer.MOVE },
                            onCopy = { transfer = selectedItems() to ForgeGalleryManager.Transfer.COPY },
                            onDelete = {
                                viewModel.deleteGalleryImages(selectedItems())
                                selected = emptySet()
                            },
                        )
                }
            } else {
                FloatingTopBar(
                    title = if (galleryMode == GalleryMode.PROMPT_PICKER) "Select Image" else "Gallery",
                    onNavigate = onBack,
                ) {
                        IconButton(onClick = { toggleMenu(ActiveMenu.SETTINGS) }) {
                            Icon(Icons.Default.Settings, "Settings")
                        }
                        // "All Images" is always the newest first, so it has nothing to sort.
                        IconButton(
                            onClick = { toggleMenu(ActiveMenu.SORT) },
                            enabled = !showExtensionStatus && shownTab != GalleryTab.ALL_IMAGES,
                        ) {
                            Icon(Icons.Default.Sort, "Sort")
                        }
                        IconButton(onClick = { toggleMenu(ActiveMenu.FILTER) }, enabled = !showExtensionStatus) {
                            Icon(
                                Icons.Default.FilterList,
                                "Filter",
                                tint = if (isSearch) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                            )
                        }
                        GalleryViewButton(
                            view = view,
                            enabled = !showExtensionStatus,
                            onSelect = { viewModel.saveConfig(config.copy(galleryView = it.name)) },
                        )
                        IconButton(onClick = { viewModel.refreshGallery() }) {
                            Icon(Icons.Default.Refresh, "Refresh")
                        }
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!showExtensionStatus) {
                GalleryTabs(
                    selected = pagerState.currentPage,
                    random = allImages.order.random,
                    onSelect = { page -> scope.launch { pagerState.animateScrollToPage(page) } },
                )
            }

            // The index updates itself; only this thin bar shows it.
            AnimatedVisibility(
                visible = isIndexing && !showExtensionStatus,
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
                if (showExtensionStatus) {
                    ExtensionStatusPanel(
                        status = extension,
                        isConnected = isConnected,
                        onCheckAgain = { viewModel.checkGalleryExtension() },
                        onOpenPage = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ForgeGalleryManager.EXTENSION_URL)))
                        },
                        onUnlock = viewModel::saveGalleryKey,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    // Swiping between tabs is off while images are selected (the selection belongs to one tab).
                    HorizontalPager(
                        state = pagerState,
                        userScrollEnabled = !selectionMode,
                        key = { page -> GalleryTab.entries[page].name },
                        modifier = Modifier.fillMaxSize(),
                    ) { page ->
                        val pageTab = GalleryTab.entries[page]
                        Column(modifier = Modifier.fillMaxSize()) {
                            // A setting opened from the statistics (3.6.0): one chip, its cross clears it.
                            galleryFilters.detail?.let { detail ->
                                DetailFilterChip(detail.label) { viewModel.applyGalleryFilters(galleryFilters.copy(detail = null)) }
                            }
                            // The searched tags (3.6.2-1), each with a cross that takes it out of the search.
                            if (galleryFilters.positiveTags.isNotEmpty() || galleryFilters.negativeTags.isNotEmpty()) {
                                SearchedTags(galleryFilters) { viewModel.applyGalleryFilters(it) }
                            }
                            if (pageTab == GalleryTab.GALLERY) {
                                val crumbs = remember(folder.path, config.galleryPath) { viewModel.galleryBreadcrumb(folder.path) }
                                PathBar(
                                    crumbs = crumbs,
                                    searchFound = if (folder.isSearch) folder.items.size else null,
                                    onOpen = { path -> viewModel.fetchGalleryFolder(path) },
                                )
                            } else if (isSearch) {
                                PathBar(crumbs = emptyList(), searchFound = itemsOf(pageTab).size, onOpen = {})
                            }
                            if (pageTab == GalleryTab.FAVORITES && !isSearch && config.favoritesCheck) {
                                val missing = missingFavorites.count { it in favoritePaths }
                                if (missing > 0) {
                                    MissingFavoritesNote(
                                        missing = missing,
                                        onRemove = { viewModel.removeMissingFavorites() },
                                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 2.dp),
                                    )
                                }
                            }
                            if (pageTab == GalleryTab.ALL_IMAGES) {
                                AllImagesOrderRow(
                                    order = allImages.order,
                                    onRandom = { viewModel.setAllImagesRandom(it) },
                                    onStatistics = { navController.navigate("gallery_stats") },
                                )
                            }
                            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                val items = itemsOf(pageTab)
                                val emptyText =
                                    when (pageTab) {
                                        GalleryTab.GALLERY -> if (folder.isSearch) "No images match the search" else "No files found"
                                        GalleryTab.FAVORITES ->
                                            if (isSearch) "No favorites match the search" else "No favorites yet. Star an image to keep it here."
                                        GalleryTab.ALL_IMAGES ->
                                            when {
                                                isSearch && indexedImageCount > 0 -> "No images match the search"
                                                isIndexing -> "Indexing the gallery..."
                                                indexError != null -> "The gallery could not be indexed: $indexError"
                                                else -> "No images in the gallery yet"
                                            }
                                    }
                                val folderPending = pageTab == GalleryTab.GALLERY && !folder.isSearch
                                when {
                                    // The top folder is on its way (the extension was just found): placeholders.
                                    folderPending && (isLoading || (currentPath.isEmpty() && error == null)) -> LoadingPlaceholders(view)
                                    // The index is still being read after the start (3.4.0).
                                    pageTab == GalleryTab.ALL_IMAGES && !indexLoaded && items.isEmpty() -> LoadingPlaceholders(view)
                                    folderPending && error != null ->
                                        Column(modifier = Modifier.align(Alignment.Center).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(Icons.Default.ErrorOutline, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
                                            Spacer(Modifier.height(8.dp))
                                            Text(error!!, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                                            Spacer(Modifier.height(16.dp))
                                            Button(onClick = { viewModel.refreshGallery() }) { Text("Retry") }
                                        }
                                    items.isEmpty() ->
                                        Text(emptyText, modifier = Modifier.align(Alignment.Center).padding(16.dp), color = Color.Gray, textAlign = TextAlign.Center)
                                    else ->
                                        GalleryItems(
                                            viewModel = viewModel,
                                            view = view,
                                            items = items,
                                            state =
                                                when (pageTab) {
                                                    GalleryTab.GALLERY -> folderState
                                                    GalleryTab.FAVORITES -> favoritesState
                                                    GalleryTab.ALL_IMAGES -> allState
                                                },
                                            favoritePaths = favoritePaths,
                                            folderCovers = if (config.folderCovers) folderCovers else emptyMap(),
                                            folderCounts = folderCounts,
                                            inFavorites = pageTab == GalleryTab.FAVORITES,
                                            selected = selected,
                                            onClick = { index, item -> onItemClick(pageTab, index, item) },
                                            onLongClick = onItemLongClick,
                                        )
                                }
                            }
                        }
                    }
                }

                // SLIDE-DOWN MENUS over a dimmed gallery; a tap on it closes them. The panel keeps showing its menu
                // while it closes (it used to empty itself at once, so it vanished instead of sliding up).
                val shownMenu = rememberLastActive(activeMenu, ActiveMenu.NONE)
                androidx.compose.animation.AnimatedVisibility(
                    visible = activeMenu != ActiveMenu.NONE,
                    enter = fadeIn(tween(MENU_ANIMATION_MS)),
                    exit = fadeOut(tween(MENU_ANIMATION_MS)),
                    modifier = Modifier.matchParentSize().zIndex(99f),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.32f))
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                    activeMenu = ActiveMenu.NONE
                                },
                    )
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = activeMenu != ActiveMenu.NONE,
                    enter =
                        expandVertically(tween(MENU_ANIMATION_MS, easing = LinearOutSlowInEasing), expandFrom = Alignment.Top) +
                            fadeIn(tween(MENU_ANIMATION_MS)),
                    exit =
                        shrinkVertically(tween(MENU_ANIMATION_MS, easing = FastOutLinearInEasing), shrinkTowards = Alignment.Top) +
                            fadeOut(tween(MENU_ANIMATION_MS)),
                    modifier = Modifier.align(Alignment.TopCenter).zIndex(100f),
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.97f),
                        tonalElevation = 8.dp,
                        shadowElevation = 8.dp,
                    ) {
                        // Switching from one panel to another cross-fades and resizes instead of jumping.
                        AnimatedContent(
                            targetState = shownMenu,
                            transitionSpec = {
                                fadeIn(tween(MENU_ANIMATION_MS)) togetherWith fadeOut(tween(MENU_ANIMATION_MS)) using
                                    SizeTransform(clip = true)
                            },
                            label = "gallery_menu",
                        ) { menu ->
                            when (menu) {
                                ActiveMenu.SORT ->
                                    SortPanel(
                                        filters = tempFilters,
                                        onChange = { tempFilters = it },
                                        onConfirm = {
                                            viewModel.applyGalleryFilters(tempFilters)
                                            activeMenu = ActiveMenu.NONE
                                        },
                                    )
                                ActiveMenu.FILTER ->
                                    FilterPanel(
                                        viewModel = viewModel,
                                        filters = tempFilters,
                                        onChange = { tempFilters = it },
                                        indexedImageCount = indexedImageCount,
                                        isIndexing = isIndexing,
                                        indexError = indexError,
                                        onClear = {
                                            viewModel.clearGalleryFilters()
                                            activeMenu = ActiveMenu.NONE
                                        },
                                        onConfirm = { confirmed ->
                                            viewModel.applyGalleryFilters(confirmed)
                                            activeMenu = ActiveMenu.NONE
                                        },
                                    )
                                ActiveMenu.SETTINGS ->
                                    GallerySettingsPanel(
                                        viewModel = viewModel,
                                        config = config,
                                        extensionReady = extension.state == ForgeGalleryManager.Extension.READY,
                                        indexedImageCount = indexedImageCount,
                                        onStatistics = {
                                            activeMenu = ActiveMenu.NONE
                                            navController.navigate("gallery_stats")
                                        },
                                        onClose = { activeMenu = ActiveMenu.NONE },
                                    )
                                ActiveMenu.NONE -> Spacer(Modifier.fillMaxWidth())
                            }
                        }
                    }
                }

                // A delete can be taken back for a few seconds (3.2.0).
                UndoDeleteBar(
                    pending = pendingDelete,
                    onUndo = { viewModel.undoGalleryDelete() },
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(12.dp)
                            .zIndex(50f),
                )
            }
        }

        // FAIL-SAFE: the list may have changed since the image was opened (e.g. unstarred in Favorites).
        fullscreen?.let { (openTab, index) ->
            val items = itemsOf(openTab)
            if (items.isEmpty()) {
                fullscreen = null
            } else {
                val safeIndex = index.coerceIn(0, items.size - 1)
                val imageFiles = items.filter { !it.isDir }
                val initialPage = imageFiles.indexOf(items[safeIndex]).coerceAtLeast(0)

                FullscreenGalleryViewer(
                    viewModel = viewModel,
                    config = config,
                    images = imageFiles,
                    initialIndex = initialPage,
                    onDismiss = { fullscreen = null },
                )
            }
        }

        // "Upscale Selected" and "More Like This" (2.4.0); after the viewer, so it opens above it. Leaving the
        // gallery closes it (it would open again with the next gallery).
        ImageJobsDialog(viewModel, onQueued = { selected = emptySet() })

        // Move or copy to a folder (3.2.0); closing it keeps the selection.
        transfer?.let { (items, kind) ->
            FolderPickerSheet(
                viewModel = viewModel,
                items = items,
                kind = kind,
                onPick = { dest, chosen ->
                    viewModel.transferGalleryImages(items, dest, chosen)
                    transfer = null
                    selected = emptySet()
                },
                onDismiss = { transfer = null },
            )
        }
        DisposableEffect(Unit) { onDispose { viewModel.dismissImageJobs() } }
    }
}

private const val FAVORITES_SCROLL = "favorites"
private const val ALL_IMAGES_SCROLL = "all"

private fun folderScrollKey(path: String) = "folder:$path"

private fun LazyGridState.position() = GalleryScrollPosition(firstVisibleItemIndex, firstVisibleItemScrollOffset)

/** A tab's list state, starting where it was left ([key] in [positions]); it outlives the tab's page. */
@Composable
private fun rememberGalleryState(
    positions: Map<String, GalleryScrollPosition>,
    key: String,
): LazyGridState {
    val start = positions[key]
    return rememberLazyGridState(start?.index ?: 0, start?.offset ?: 0)
}

/** Hands every new position of [state] to [record]. */
@Composable
private fun RecordScroll(
    state: LazyGridState,
    record: (GalleryScrollPosition) -> Unit,
) {
    val latest by rememberUpdatedState(record)
    LaunchedEffect(state) { snapshotFlow { state.position() }.collect { latest(it) } }
}

/** Back to the top when a list made with other filters (a search, another order) arrives; not when it is the same. */
@Composable
private fun ScrollToTopOnNewFilters(
    filters: Any,
    state: LazyGridState,
) {
    var shown by remember { mutableStateOf(filters) }
    LaunchedEffect(filters) {
        if (filters != shown) {
            shown = filters
            state.scrollToItem(0)
        }
    }
}

/** The setting the statistics opened the gallery with (3.6.0), with a cross that clears it. */
@Composable
private fun DetailFilterChip(
    label: String,
    onClear: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
        InputChip(
            selected = true,
            onClick = onClear,
            label = { Text(label, maxLines = 1) },
            trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Clear", modifier = Modifier.size(16.dp)) },
        )
        Text("  from Statistics", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The tags the search looks for (3.6.2-1), in one row that scrolls sideways; a chip's cross takes its tag out. */
@Composable
private fun SearchedTags(
    filters: ForgeGalleryManager.GalleryFilters,
    onChange: (ForgeGalleryManager.GalleryFilters) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp),
    ) {
        filters.positiveTags.forEach { tag ->
            TagChip(tag, negative = false) { onChange(filters.copy(positiveTags = filters.positiveTags - tag)) }
        }
        filters.negativeTags.forEach { tag ->
            TagChip(tag, negative = true) { onChange(filters.copy(negativeTags = filters.negativeTags - tag)) }
        }
        if (filters.exactTags) Text("exact", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One searched tag: a negative prompt's tag is marked "−" in the error colors; a tap (or its cross) removes it. */
@Composable
private fun TagChip(
    tag: String,
    negative: Boolean,
    onRemove: () -> Unit,
) {
    InputChip(
        selected = true,
        onClick = onRemove,
        label = { Text(if (negative) "− $tag" else tag, maxLines = 1) },
        trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove $tag", modifier = Modifier.size(16.dp)) },
        colors =
            if (negative) {
                InputChipDefaults.inputChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onErrorContainer,
                    selectedTrailingIconColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            } else {
                InputChipDefaults.inputChipColors()
            },
    )
}

/**
 * A field for the tags of one prompt (3.6.2-1): a comma, the keyboard's Done or "+" turns what was typed into chips
 * below it, and a chip's cross takes its tag out. [draft] is the text not yet a chip; Confirm adds it too.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagField(
    label: String,
    tags: List<String>,
    negative: Boolean,
    draft: String,
    onDraft: (String) -> Unit,
    onTags: (List<String>) -> Unit,
) {
    fun commit(text: String) {
        val added = TagFilter.split(text)
        if (added.isNotEmpty()) onTags((tags + added).distinct())
    }
    OutlinedTextField(
        value = draft,
        onValueChange = { text ->
            if (text.contains(',')) {
                commit(text.substringBeforeLast(','))
                onDraft(text.substringAfterLast(',').trimStart())
            } else {
                onDraft(text)
            }
        },
        label = { Text(label) },
        placeholder = { Text("e.g. 1girl, long hair") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions =
            KeyboardActions(onDone = {
                commit(draft)
                onDraft("")
            }),
        trailingIcon =
            if (draft.isNotBlank()) {
                {
                    IconButton(onClick = {
                        commit(draft)
                        onDraft("")
                    }) { Icon(Icons.Default.Add, contentDescription = "Add Tag") }
                }
            } else {
                null
            },
        modifier = Modifier.fillMaxWidth(),
    )
    if (tags.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
            tags.forEach { tag -> TagChip(tag, negative) { onTags(tags - tag) } }
        }
    }
}

/** The three tabs; "All Images" says in which order it shows (the newest first, or random). */
@Composable
private fun GalleryTabs(
    selected: Int,
    random: Boolean,
    onSelect: (Int) -> Unit,
) {
    PrimaryTabRow(selectedTabIndex = selected) {
        GalleryTab.entries.forEachIndexed { index, tab ->
            Tab(
                selected = selected == index,
                onClick = { onSelect(index) },
                modifier = Modifier.heightIn(min = 56.dp),
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                when (tab) {
                    GalleryTab.GALLERY -> Text("Gallery", style = MaterialTheme.typography.titleSmall)
                    GalleryTab.FAVORITES ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Star, contentDescription = null, tint = FavoriteGold, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Favorites", style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        }
                    GalleryTab.ALL_IMAGES ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("All Images", style = MaterialTheme.typography.titleSmall, maxLines = 1)
                            }
                            Text(if (random) "(random)" else "(newest first)", fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1)
                        }
                }
            }
        }
    }
}

/**
 * Where the Gallery tab is: "Gallery" (its top folder) and the folders below it, each opening that folder; nothing
 * above the top folder can be opened. With a search: how many images it found.
 */
@Composable
private fun PathBar(
    crumbs: List<Pair<String, String>>,
    searchFound: Int?,
    onOpen: (String) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (searchFound != null) {
            Text(
                "Search: $searchFound found",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            return@Row
        }
        crumbs.forEachIndexed { index, (name, path) ->
            val isLast = index == crumbs.lastIndex
            Text(
                text = name,
                fontSize = 14.sp,
                fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                color = if (isLast) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(enabled = !isLast) { onOpen(path) }.padding(vertical = 4.dp),
            )
            if (!isLast) {
                Icon(
                    Icons.Default.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp).padding(horizontal = 4.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The layout chooser (where the Sync button was): a grid of 2 to 5 columns, or a small, medium or large list. */
@Composable
private fun GalleryViewButton(
    view: GalleryView,
    enabled: Boolean,
    onSelect: (GalleryView) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled) {
            Icon(if (view.isList) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView, "Layout")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuHeader("Grid")
            GalleryView.entries.filter { !it.isList }.forEach { option ->
                ViewOption(option, current = view, icon = Icons.Default.GridView) {
                    open = false
                    onSelect(option)
                }
            }
            HorizontalDivider()
            MenuHeader("List")
            GalleryView.entries.filter { it.isList }.forEach { option ->
                ViewOption(option, current = view, icon = Icons.AutoMirrored.Filled.ViewList) {
                    open = false
                    onSelect(option)
                }
            }
        }
    }
}

@Composable
private fun MenuHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun ViewOption(
    option: GalleryView,
    current: GalleryView,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(option.label, fontWeight = if (option == current) FontWeight.Bold else FontWeight.Normal) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        trailingIcon = { if (option == current) Icon(Icons.Default.Check, "Selected", tint = MaterialTheme.colorScheme.primary) },
        onClick = onClick,
    )
}

/**
 * Why there is nothing to browse: no connection, no gallery extension on the server, an error from it, or it asks for
 * its secret key (3.5.0).
 */
@Composable
private fun ExtensionStatusPanel(
    status: ForgeGalleryManager.ExtensionStatus,
    isConnected: Boolean,
    onCheckAgain: () -> Unit,
    onOpenPage: () -> Unit,
    onUnlock: (key: String, onResult: (Boolean) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (status.state) {
            ForgeGalleryManager.Extension.CHECKING, ForgeGalleryManager.Extension.READY -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("Looking for the gallery extension on the server...", textAlign = TextAlign.Center)
            }
            ForgeGalleryManager.Extension.MISSING -> {
                Icon(Icons.Default.Extension, null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                Text("Infinite Image Browsing Required", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text(
                    "The gallery shows the images saved on your Forge server through the Infinite Image Browsing " +
                        "extension, and this server does not have it. Install it in Forge (Extensions > Available, " +
                        "or Install from URL), restart Forge, then check again.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = onCheckAgain) { Text("Check Again") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onOpenPage) { Text("Open Extension Page") }
            }
            ForgeGalleryManager.Extension.FAILED -> {
                Icon(Icons.Default.ErrorOutline, null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
                Text("Gallery Extension Error", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text(status.message.orEmpty(), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(20.dp))
                Button(onClick = onCheckAgain) { Text("Check Again") }
            }
            ForgeGalleryManager.Extension.LOCKED -> GalleryLockedPanel(status.message, onUnlock)
            ForgeGalleryManager.Extension.KEY_NOT_SET -> GalleryKeyNotSetPanel(onCheckAgain)
            ForgeGalleryManager.Extension.UNKNOWN -> {
                Icon(Icons.Default.CloudOff, null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Text(
                    if (isConnected) "The Server Did Not Answer" else "Not Connected",
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    if (isConnected) status.message ?: "The gallery extension could not be checked." else "The gallery opens once the app reaches the server.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = onCheckAgain) { Text(if (isConnected) "Check Again" else "Retry") }
            }
        }
    }
}

@Composable
private fun LoadingPlaceholders(view: GalleryView) {
    if (view.isList) {
        val size = listThumbnailSize(view)
        Column(modifier = Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(8) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(size).clip(MaterialTheme.shapes.small).shimmer())
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(modifier = Modifier.fillMaxWidth(0.6f).height(14.dp).clip(MaterialTheme.shapes.extraSmall).shimmer())
                        Box(modifier = Modifier.fillMaxWidth().height(12.dp).clip(MaterialTheme.shapes.extraSmall).shimmer())
                    }
                }
            }
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(view.columns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(4.dp),
            userScrollEnabled = false,
        ) {
            items(view.columns * 8) {
                Box(
                    modifier =
                        Modifier
                            .padding(cellPadding(view.columns))
                            .aspectRatio(1f)
                            .clip(MaterialTheme.shapes.small)
                            .shimmer(),
                )
            }
        }
    }
}

private fun cellPadding(columns: Int): Dp = if (columns >= 4) 2.dp else 4.dp

private fun listThumbnailSize(view: GalleryView): Dp =
    when (view) {
        GalleryView.LIST_SMALL -> 64.dp
        GalleryView.LIST_LARGE -> 152.dp
        else -> 104.dp
    }

/** The server's "yyyy-MM-dd HH:mm:ss" (the time the image was saved) in the phone's own date format. */
private val SERVER_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
private val SHOWN_DATE: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

private fun displayDate(date: String?): String {
    if (date.isNullOrBlank()) return ""
    return try {
        LocalDateTime.parse(date, SERVER_DATE).format(SHOWN_DATE)
    } catch (e: Exception) {
        date
    }
}

/**
 * A tab's images (and, in the Gallery tab, folders) in the chosen layout. Both layouts are grids (the list has one
 * column), so a tab's [state] keeps its place when the layout changes.
 */
@Composable
private fun GalleryItems(
    viewModel: ForgeViewModel,
    view: GalleryView,
    items: List<GalleryItem>,
    state: LazyGridState,
    favoritePaths: Set<String>,
    folderCovers: Map<String, List<GalleryItem>>,
    folderCounts: Map<String, Int>,
    inFavorites: Boolean,
    selected: Set<String>,
    onClick: (Int, GalleryItem) -> Unit,
    onLongClick: (GalleryItem) -> Unit,
) {
    if (view.isList) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(1),
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(
                items,
                key = { _, item -> item.fullpath },
                contentType = { _, item -> if (item.isDir) "dir" else "image" },
            ) { index, item ->
                ListRow(
                    viewModel = viewModel,
                    view = view,
                    item = item,
                    covers = if (item.isDir) folderCovers[GalleryPaths.key(item.fullpath)] else null,
                    folderCount = if (item.isDir) folderCounts[GalleryPaths.key(item.fullpath)] else null,
                    isFavorite = inFavorites || item.fullpath in favoritePaths,
                    isSelected = item.fullpath in selected,
                    onClick = { onClick(index, item) },
                    onLongClick = { onLongClick(item) },
                )
            }
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(view.columns),
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(4.dp),
        ) {
            itemsIndexed(
                items,
                key = { _, item -> item.fullpath },
                contentType = { _, item -> if (item.isDir) "dir" else "image" },
            ) { index, item ->
                GridCell(
                    viewModel = viewModel,
                    columns = view.columns,
                    item = item,
                    covers = if (item.isDir) folderCovers[GalleryPaths.key(item.fullpath)] else null,
                    folderCount = if (item.isDir) folderCounts[GalleryPaths.key(item.fullpath)] else null,
                    isFavorite = inFavorites || item.fullpath in favoritePaths,
                    isSelected = item.fullpath in selected,
                    onClick = { onClick(index, item) },
                    onLongClick = { onLongClick(item) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridCell(
    viewModel: ForgeViewModel,
    columns: Int,
    item: GalleryItem,
    covers: List<GalleryItem>?,
    folderCount: Int?,
    isFavorite: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    // Small cells: smaller folder icons, and no names over the images (unreadable at that size).
    val small = columns >= 4
    if (item.isDir && !covers.isNullOrEmpty()) {
        // Its newest images as a cover, with its name and number of images over them (3.2.0).
        Box(
            modifier =
                Modifier
                    .padding(cellPadding(columns))
                    .aspectRatio(1f)
                    .clip(MaterialTheme.shapes.small)
                    .clickable(onClick = onClick),
        ) {
            FolderCover(covers, { viewModel.getGalleryThumbnailUrl(it) }, Modifier.fillMaxSize(), gap = if (small) 1.dp else 2.dp)
            Column(
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                        .padding(start = 6.dp, end = 6.dp, top = if (small) 8.dp else 16.dp, bottom = if (small) 3.dp else 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Folder,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(if (small) 10.dp else 14.dp),
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        item.name,
                        color = Color.White,
                        fontSize = if (small) 9.sp else 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!small && folderCount != null) {
                    Text(
                        if (folderCount == 1) "1 image" else "$folderCount images",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 10.sp,
                    )
                }
            }
        }
        return
    }
    if (item.isDir) {
        Card(
            modifier = Modifier.padding(cellPadding(columns)).aspectRatio(1f).clickable(onClick = onClick),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(if (small) 4.dp else 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                val iconSize =
                    when (columns) {
                        2 -> 56.dp
                        3 -> 48.dp
                        4 -> 36.dp
                        else -> 28.dp
                    }
                Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(iconSize), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(if (small) 4.dp else 8.dp))
                Text(
                    item.name,
                    fontSize = if (small) 10.sp else 12.sp,
                    lineHeight = if (small) 12.sp else 14.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        return
    }

    val frameWidth = if (small) 2.dp else 4.dp
    // A still gold frame: the animated one kept redrawing every favorite as long as the gallery was open.
    val frameModifier =
        when {
            isSelected -> Modifier.border(frameWidth, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small)
            isFavorite -> Modifier.border(frameWidth, FavoriteGold, MaterialTheme.shapes.small)
            else -> Modifier
        }

    Box(
        modifier =
            Modifier
                .padding(cellPadding(columns))
                .aspectRatio(1f)
                .then(frameModifier)
                .clip(MaterialTheme.shapes.small)
                .combinedClickable(onLongClick = onLongClick, onClick = onClick),
    ) {
        GalleryThumbnail(viewModel, item)
        if (isSelected) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).background(Color.White, CircleShape),
            )
        }
        if (!small) {
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
                    fontSize = if (columns == 2) 11.sp else 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** A row of the list layout: a thumbnail with the image's name, prompt and date, and buttons to star and share it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListRow(
    viewModel: ForgeViewModel,
    view: GalleryView,
    item: GalleryItem,
    covers: List<GalleryItem>?,
    folderCount: Int?,
    isFavorite: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val context = LocalContext.current
    val promptLines =
        when (view) {
            GalleryView.LIST_SMALL -> 1
            GalleryView.LIST_LARGE -> 5
            else -> 3
        }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .combinedClickable(onLongClick = onLongClick, onClick = onClick),
    ) {
        Row(modifier = Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(listThumbnailSize(view)).clip(MaterialTheme.shapes.small)) {
                if (item.isDir && !covers.isNullOrEmpty()) {
                    FolderCover(covers, { viewModel.getGalleryThumbnailUrl(it) }, Modifier.fillMaxSize())
                } else if (item.isDir) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.fillMaxSize(0.5f), tint = MaterialTheme.colorScheme.primary)
                    }
                } else {
                    GalleryThumbnail(viewModel, item)
                    if (isSelected) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).background(Color.White, CircleShape),
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = if (view == GalleryView.LIST_LARGE) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.isDir && folderCount != null) {
                    Text(
                        if (folderCount == 1) "1 image" else "$folderCount images",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (!item.isDir) {
                    // The prompt comes from the index, row by row (the gallery keeps no prompts in memory).
                    val prompt by produceState<String?>(initialValue = null, item.fullpath) {
                        value = viewModel.galleryPositivePrompt(item.fullpath)
                    }
                    Text(
                        text = prompt?.ifBlank { "No generation data" } ?: "",
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        fontStyle = if (prompt.isNullOrBlank()) FontStyle.Italic else FontStyle.Normal,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = promptLines,
                        minLines = if (view == GalleryView.LIST_SMALL) 1 else 2.coerceAtMost(promptLines),
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                val date = displayDate(item.date)
                if (date.isNotEmpty()) {
                    Text(
                        date,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (!item.isDir) {
                IconButton(onClick = { viewModel.toggleFavorite(item) }) {
                    Icon(
                        if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = if (isFavorite) "Remove from Favorites" else "Add to Favorites",
                        tint = if (isFavorite) FavoriteGold else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { viewModel.shareImage(item) { intent -> context.startActivity(intent) } }) {
                    Icon(Icons.Default.Share, "Share", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SortPanel(
    filters: ForgeGalleryManager.GalleryFilters,
    onChange: (ForgeGalleryManager.GalleryFilters) -> Unit,
    onConfirm: () -> Unit,
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Sort By", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Button(onClick = onConfirm) { Text("Confirm") }
        }
        Spacer(Modifier.height(8.dp))

        val sortOptions =
            listOf(
                ForgeGalleryManager.SortOrder.NEWEST to "Newest First",
                ForgeGalleryManager.SortOrder.OLDEST to "Oldest First",
                ForgeGalleryManager.SortOrder.NAME_ASC to "A-Z (Alphabetical)",
                ForgeGalleryManager.SortOrder.NAME_DESC to "Z-A (Reverse Alphabetical)",
            )

        sortOptions.forEach { (order, label) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { onChange(filters.copy(sortOrder = order)) }.padding(vertical = 4.dp),
            ) {
                RadioButton(selected = filters.sortOrder == order, onClick = { onChange(filters.copy(sortOrder = order)) })
                Spacer(Modifier.width(8.dp))
                Text(label)
            }
        }
        Text(
            "\"All Images\" is always shown newest first.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun FilterPanel(
    viewModel: ForgeViewModel,
    filters: ForgeGalleryManager.GalleryFilters,
    onChange: (ForgeGalleryManager.GalleryFilters) -> Unit,
    indexedImageCount: Int,
    isIndexing: Boolean,
    indexError: String?,
    onClear: () -> Unit,
    onConfirm: (ForgeGalleryManager.GalleryFilters) -> Unit,
) {
    val availableModels by viewModel.availableModels.collectAsStateWithLifecycle()
    val availableLoras by viewModel.galleryAvailableLoras.collectAsStateWithLifecycle()
    // Text typed into the tag fields that is not a chip yet; Confirm adds it to the tags.
    var positiveDraft by remember { mutableStateOf("") }
    var negativeDraft by remember { mutableStateOf("") }

    // Taller since 3.6.2-1 (two tag fields with their chips and Exact Tags); it still scrolls on a short phone.
    Column(modifier = Modifier.padding(16.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Search Gallery", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row {
                TextButton(onClick = onClear) { Text("Clear All") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    onConfirm(
                        filters.copy(
                            positiveTags = (filters.positiveTags + TagFilter.split(positiveDraft)).distinct(),
                            negativeTags = (filters.negativeTags + TagFilter.split(negativeDraft)).distinct(),
                        ),
                    )
                }) { Text("Confirm") }
            }
        }
        val indexState =
            when {
                isIndexing -> " Indexing new images..."
                indexError != null -> " The last update of the index failed: $indexError"
                else -> ""
            }
        Text(
            "Searches the whole gallery, not only the open folder; in Favorites only the favorites " +
                "($indexedImageCount images indexed).$indexState",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = filters.name,
            onValueChange = { onChange(filters.copy(name = it)) },
            label = { Text("File Name") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        // The positive and the negative prompt apart (3.6.2-1); an image must hold every tag of both.
        TagField(
            label = "Positive Prompt Tags",
            tags = filters.positiveTags,
            negative = false,
            draft = positiveDraft,
            onDraft = { positiveDraft = it },
            onTags = { onChange(filters.copy(positiveTags = it)) },
        )
        Spacer(Modifier.height(8.dp))
        TagField(
            label = "Negative Prompt Tags",
            tags = filters.negativeTags,
            negative = true,
            draft = negativeDraft,
            onDraft = { negativeDraft = it },
            onTags = { onChange(filters.copy(negativeTags = it)) },
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { onChange(filters.copy(exactTags = !filters.exactTags)) }
                    .padding(vertical = 4.dp),
        ) {
            Checkbox(checked = filters.exactTags, onCheckedChange = null)
            Spacer(Modifier.width(8.dp))
            Column {
                Text("Exact Tags")
                Text(
                    "Whole tags only: \"cat\" no longer finds \"catgirl\". Weights and \"_\" do not matter.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        // Models section: an image has one model, so any of the selected ones matches.
        var modelsExpanded by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier.fillMaxWidth().clickable { modelsExpanded = !modelsExpanded }.padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Models (${filters.models.size} selected)", fontWeight = FontWeight.Bold)
            Text(if (modelsExpanded) "▲" else "▼")
        }
        if (modelsExpanded) {
            if (availableModels.isEmpty()) {
                Text("No models indexed.", color = Color.Gray, modifier = Modifier.padding(start = 8.dp))
            } else {
                availableModels.forEach { model ->
                    val isChecked = filters.models.contains(model)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onChange(filters.copy(models = if (isChecked) filters.models - model else filters.models + model)) }
                                .padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
                    ) {
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
        Row(
            modifier = Modifier.fillMaxWidth().clickable { lorasExpanded = !lorasExpanded }.padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("LoRAs (${filters.loras.size} selected)", fontWeight = FontWeight.Bold)
            Text(if (lorasExpanded) "▲" else "▼")
        }
        if (lorasExpanded) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                Text("Match:", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(8.dp))
                FilterChip(selected = !filters.lorasIsAnd, onClick = { onChange(filters.copy(lorasIsAnd = false)) }, label = { Text("Any") })
                Spacer(Modifier.width(8.dp))
                FilterChip(selected = filters.lorasIsAnd, onClick = { onChange(filters.copy(lorasIsAnd = true)) }, label = { Text("All") })
            }
            if (availableLoras.isEmpty()) {
                Text("No LoRAs indexed.", color = Color.Gray, modifier = Modifier.padding(start = 8.dp))
            } else {
                availableLoras.forEach { lora ->
                    val isChecked = filters.loras.contains(lora)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onChange(filters.copy(loras = if (isChecked) filters.loras - lora else filters.loras + lora)) }
                                .padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
                    ) {
                        Checkbox(checked = isChecked, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text(lora, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun GallerySettingsPanel(
    viewModel: ForgeViewModel,
    config: AppConfig,
    extensionReady: Boolean,
    indexedImageCount: Int,
    onStatistics: () -> Unit,
    onClose: () -> Unit,
) {
    Column(modifier = Modifier.padding(16.dp).heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Gallery Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, "Close")
            }
        }
        Spacer(Modifier.height(8.dp))

        // Worked out from the index on the phone (3.2.0); also under All Images.
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onStatistics).padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.BarChart, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Statistics", fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground)
                Text(
                    text = "Images per day, top models, LoRAs and tags of the $indexedImageCount indexed images",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    lineHeight = 18.sp,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // The folders are read from the server's gallery extension; nothing to set by hand (2.1.0).
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Text(text = "Gallery Folder", fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground)
            Text(
                text = if (extensionReady && config.galleryPath.isNotEmpty()) config.galleryPath else "Found on its own when the app connects",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Read from the server's Infinite Image Browsing extension",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            )
        }

        SettingSwitchRow(
            title = "Swipe to Browse Images",
            subtitle = "Use horizontal swiping in fullscreen preview",
            checked = config.swipeToBrowseGallery,
            onToggle = { viewModel.saveConfig(config.copy(swipeToBrowseGallery = !config.swipeToBrowseGallery)) },
        )

        SettingSwitchRow(
            title = "Pinch to Zoom",
            subtitle = "Pinch or double tap to zoom full-screen images (also on the main screen)",
            checked = config.pinchToZoom,
            onToggle = { viewModel.saveConfig(config.copy(pinchToZoom = !config.pinchToZoom)) },
        )

        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Text(text = "Save to Phone Automatically", fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground)
            val saveLocation = DeviceImages.locationName(config.savePrivately)
            val autoSaveDescription =
                when (config.autoSaveMode) {
                    AUTO_SAVE_FAVORITES -> "Images you star are saved to $saveLocation"
                    AUTO_SAVE_ALL ->
                        "New images from the server are saved to $saveLocation on Wi-Fi, " +
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

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground)
            Text(
                text = subtitle,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                lineHeight = 18.sp,
            )
        }
        Switch(checked = checked, onCheckedChange = null)
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
    val extension by viewModel.galleryExtension.collectAsStateWithLifecycle()
    val pendingDelete by viewModel.galleryPendingDelete.collectAsStateWithLifecycle()

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
                // The next image shows in its place; Undo below brings it back (3.2.0).
                if (extension.canWrite) {
                    IconButton(onClick = { currentItem?.let { viewModel.deleteGalleryImages(listOf(it)) } }) {
                        Icon(Icons.Default.Delete, "Delete from Server", tint = Color.White)
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (config.swipeToBrowseGallery) {
                    // The neighbouring images load in advance, so swiping does not wait for the network.
                    HorizontalPager(state = pagerState, beyondViewportPageCount = 1, modifier = Modifier.fillMaxSize()) { page ->
                        FullImage(viewModel, images[page], Modifier.fillMaxSize(), zoom = config.pinchToZoom)
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        if (currentItem != null) FullImage(viewModel, currentItem, Modifier.fillMaxWidth(), zoom = config.pinchToZoom)
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

                UndoDeleteBar(
                    pending = pendingDelete,
                    onUndo = { viewModel.undoGalleryDelete() },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                )
            }

            // The image made again: more like it, or larger (2.4.0); not with Settings > Features > Image Jobs off.
            if (config.imageJobs) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(Color(0x88000000)).padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        Triple(ImageJobs.Kind.MORE_LIKE_THIS, Icons.Default.AutoAwesome, "More Like This"),
                        Triple(ImageJobs.Kind.UPSCALE, Icons.Default.OpenInFull, "Upscale"),
                    ).forEach { (kind, icon, label) ->
                        TextButton(
                            onClick = { currentItem?.let { viewModel.requestImageJobs(kind, listOf(it)) } },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(label, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

/**
 * A grid cell's image. A plain AsyncImage: SubcomposeAsyncImage (and a shimmer brush rebuilt on every animation frame)
 * made scrolling heavy. Very old gallery extensions have no thumbnails; the image itself is shown then.
 */
@Composable
private fun GalleryThumbnail(
    viewModel: ForgeViewModel,
    item: GalleryItem,
) {
    var thumbnailFailed by remember(item.fullpath) { mutableStateOf(false) }
    var loading by remember(item.fullpath) { mutableStateOf(true) }
    val url =
        remember(item.fullpath, item.date, thumbnailFailed) {
            if (thumbnailFailed) viewModel.getGalleryImageUrl(item) else viewModel.getGalleryThumbnailUrl(item)
        }
    AsyncImage(
        model = url,
        contentDescription = item.name,
        onState = { state ->
            loading = state is AsyncImagePainter.State.Loading
            if (state is AsyncImagePainter.State.Error && !thumbnailFailed) thumbnailFailed = true
        },
        modifier = Modifier.fillMaxSize().then(if (loading) Modifier.shimmer() else Modifier),
        contentScale = ContentScale.Crop,
    )
}

/** The full image; its thumbnail (already cached by the gallery) is shown at once while the full one loads. */
@Composable
private fun FullImage(
    viewModel: ForgeViewModel,
    item: GalleryItem,
    modifier: Modifier,
    zoom: Boolean,
) {
    val context = LocalContext.current
    val size = zoomableImageSizePx()
    val request = remember(item.fullpath, item.date, size) { ImageRequest.Builder(context).data(viewModel.getGalleryImageUrl(item)).size(size).build() }
    SubcomposeAsyncImage(
        model = request,
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
        // Pinch or double tap to zoom (unless "Pinch to Zoom" is off).
        modifier = modifier.zoomable(item.fullpath, enabled = zoom),
        contentScale = ContentScale.Fit,
    )
}
