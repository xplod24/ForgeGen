package com.example.forgegen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import coil.compose.SubcomposeAsyncImage
import java.util.Locale

/* ============================================================================
 * SHIMMER EFFECT (SKELETON LOADING & FRAMES)
 * Tworzy animowany gradient naśladujący ładowanie oraz ozdobne ramki
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

@Composable
fun shimmerBrush(): Brush = coloredShimmerBrush(MaterialTheme.colorScheme.surfaceVariant)

/* ============================================================================
 * REUSABLE UI COMPONENTS FOR SETTINGS
 * ============================================================================ */

@Composable
fun PreferenceCategory(title: String) {
    Text(
        text = title.uppercase(androidx.compose.ui.platform.LocalConfiguration.current.locales[0]),
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp, end = 16.dp),
    )
}

/* ============================================================================
 * GALLERY SCREEN COMPOSABLE
 * ============================================================================ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    viewModel: ForgeViewModel,
    navController: NavHostController,
) {
    val galleryFiles by viewModel.galleryFiles.collectAsStateWithLifecycle()
    val currentPath by viewModel.currentGalleryPath.collectAsStateWithLifecycle()
    val isLoading by viewModel.isGalleryLoading.collectAsStateWithLifecycle()
    val error by viewModel.galleryError.collectAsStateWithLifecycle()
    val galleryMode by viewModel.galleryMode.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()

    // Subskrypcja listy ulubionych ścieżek
    val favoritePaths by viewModel.favoritePaths.collectAsStateWithLifecycle()

    var fullscreenIndex by remember { mutableIntStateOf(-1) }

    val safePopBack = {
        if (navController.currentDestination?.route == "gallery") {
            navController.popBackStack()
        }
    }

    val onBack = {
        if (error != null) {
            safePopBack()
        } else if (currentPath == "virtual://favorites") {
            val rootPath = config.galleryPath.ifEmpty { "Root" }
            viewModel.fetchGalleryFolder(rootPath)
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
                title = {
                    Column {
                        Text(
                            if (galleryMode ==
                                GalleryMode.PROMPT_PICKER
                            ) {
                                "Select Image"
                            } else {
                                "Gallery"
                            },
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = if (currentPath == "virtual://favorites") "⭐ Favorites" else currentPath.ifEmpty { "Root" },
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.fetchGalleryFolder(currentPath) }) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
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
            } else if (galleryFiles.isEmpty()) {
                Text("No files found", modifier = Modifier.align(Alignment.Center), color = Color.Gray)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(4.dp),
                ) {
                    itemsIndexed(galleryFiles) { index, item ->
                        if (item.isDir) {
                            val isFavoritesFolder = item.fullpath == "virtual://favorites"
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
                                        imageVector = if (isFavoritesFolder) Icons.Default.Star else Icons.Default.Folder,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp),
                                        tint = if (isFavoritesFolder) Color(0xFFFFD54F) else MaterialTheme.colorScheme.primary,
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
                            // Rozpoznajemy stan ulubionego na żywo z pobranej listy ścieżek
                            val isFavorite = favoritePaths.contains(item.fullpath) || currentPath == "virtual://favorites"
                            val isForgeGen = item.name.contains("ForgeGen", ignoreCase = true)

                            // Grubsza (6.dp) i bardzo dobrze widoczna ramka!
                            val frameModifier =
                                when {
                                    isFavorite -> Modifier.border(6.dp, coloredShimmerBrush(Color(0xFFFFD54F)), MaterialTheme.shapes.small)
                                    isForgeGen ->
                                        Modifier.border(
                                            6.dp,
                                            coloredShimmerBrush(MaterialTheme.colorScheme.primary),
                                            MaterialTheme.shapes.small,
                                        )
                                    else -> Modifier
                                }

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
                                    model = viewModel.getGalleryImageUrl(item),
                                    contentDescription = item.name,
                                    loading = {
                                        Box(modifier = Modifier.fillMaxSize().background(shimmerBrush()))
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

        // ZABEZPIECZENIE: IndexOutOfBoundsException Fail-Safe
        if (fullscreenIndex >= 0) {
            if (galleryFiles.isEmpty()) {
                fullscreenIndex = -1
            } else {
                val safeIndex = fullscreenIndex.coerceIn(0, galleryFiles.size - 1)
                val imageFiles = galleryFiles.filter { !it.isDir }
                val targetFile = galleryFiles[safeIndex]
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
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FullscreenGalleryViewer(
    viewModel: com.example.forgegen.ForgeViewModel,
    config: com.example.forgegen.AppConfig,
    images: List<com.example.forgegen.GalleryItem>,
    initialIndex: Int,
    onDismiss: () -> Unit,
) {
    val pagerState =
        androidx.compose.foundation.pager
            .rememberPagerState(initialPage = initialIndex, pageCount = { images.size })
    val currentItem = images.getOrNull(pagerState.currentPage)
    val context = androidx.compose.ui.platform.LocalContext.current

    val isFavorite by viewModel.isCurrentFavorite.collectAsStateWithLifecycle()

    LaunchedEffect(pagerState.currentPage) {
        if (currentItem != null) {
            viewModel.loadMetadataForImage(currentItem)
            viewModel.checkIfFavorite(currentItem.fullpath)
        }
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties =
            androidx.compose.ui.window
                .DialogProperties(usePlatformDefaultWidth = false),
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
                        tint = if (isFavorite) Color(0xFFFFD54F) else Color.White,
                    )
                }

                IconButton(onClick = {
                    currentItem?.let {
                        viewModel.shareImage(it) { intent -> context.startActivity(intent) }
                    }
                }) {
                    Icon(Icons.Default.Share, "Share", tint = Color.White)
                }

                val showMetadata by viewModel.showGalleryMetadata.collectAsStateWithLifecycle()
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
                    androidx.compose.foundation.pager.HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                        coil.compose.SubcomposeAsyncImage(
                            model = viewModel.getGalleryImageUrl(images[page]),
                            contentDescription = null,
                            loading = {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                            alignment = Alignment.TopCenter,
                        )
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        if (currentItem != null) {
                            coil.compose.SubcomposeAsyncImage(
                                model = viewModel.getGalleryImageUrl(currentItem),
                                contentDescription = null,
                                loading = {
                                    Box(modifier = Modifier.fillMaxWidth().height(400.dp), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                alignment = Alignment.TopCenter,
                            )
                        }
                    }
                }

                val showMetadata by viewModel.showGalleryMetadata.collectAsStateWithLifecycle()
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
                                    }

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

                    MetadataAlertDialog(
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
