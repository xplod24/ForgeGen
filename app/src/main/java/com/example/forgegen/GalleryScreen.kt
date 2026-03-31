package com.yourname.forgegen

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
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
import androidx.compose.ui.draw.clip
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
import coil.compose.AsyncImage
import com.yourname.forgegen.Translator.t

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(viewModel: ForgeViewModel, navController: NavHostController) {
    val files by viewModel.galleryFiles.collectAsStateWithLifecycle()
    val currentPath by viewModel.currentGalleryPath.collectAsStateWithLifecycle()
    val isLoading by viewModel.isGalleryLoading.collectAsStateWithLifecycle()
    val error by viewModel.galleryError.collectAsStateWithLifecycle()
    val mode by viewModel.galleryMode.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()

    var fullscreenIndex by remember { mutableStateOf(-1) }
    var isGridView by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (mode == GalleryMode.PROMPT_PICKER) "Select Image for Prompt".t else "Gallery".t, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (fullscreenIndex >= 0) {
                            fullscreenIndex = -1
                        } else if (currentPath.isNotEmpty() && currentPath != "Root" && currentPath != config.galleryPath) {
                            val normalizedPath = currentPath.trimEnd('/', '\\')
                            val lastSlash = maxOf(normalizedPath.lastIndexOf('/'), normalizedPath.lastIndexOf('\\'))
                            val parentPath = if (lastSlash > 0) normalizedPath.substring(0, lastSlash) else "Root"

                            if (config.galleryPath.startsWith(parentPath) && config.galleryPath != parentPath) {
                                navController.popBackStack()
                            } else {
                                viewModel.fetchGalleryFolder(parentPath)
                            }
                        } else {
                            navController.popBackStack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
                actions = {
                    if (fullscreenIndex < 0) {
                        IconButton(onClick = { isGridView = !isGridView }) {
                            Icon(if (isGridView) Icons.Default.ViewList else Icons.Default.GridView, "Toggle View".t)
                        }
                        IconButton(onClick = { viewModel.fetchGalleryFolder(currentPath) }) {
                            Icon(Icons.Default.Refresh, "Refresh".t)
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (error != null) {
                Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${"Error: ".t}$error", color = MaterialTheme.colorScheme.error)
                    Button(onClick = { viewModel.fetchGalleryFolder(currentPath) }) { Text("Retry".t) }
                }
            } else if (files.isEmpty()) {
                Text("Folder is empty.".t, modifier = Modifier.align(Alignment.Center), color = Color.Gray)
            } else {
                if (isGridView) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(config.galleryGridColumns),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(4.dp)
                    ) {
                        items(count = files.size, key = { files[it].fullpath }) { index ->
                            val file = files[index]
                            if (file.isDir) {
                                Card(
                                    modifier = Modifier.padding(4.dp).aspectRatio(1f).clickable { viewModel.fetchGalleryFolder(file.fullpath) },
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(Icons.Default.Folder, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                                        Text(file.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(4.dp))
                                    }
                                }
                            } else {
                                AsyncImage(
                                    model = viewModel.getGalleryImageUrl(file),
                                    contentDescription = file.name,
                                    modifier = Modifier
                                        .padding(4.dp)
                                        .aspectRatio(1f)
                                        .clip(MaterialTheme.shapes.small)
                                        .clickable {
                                            if (mode == GalleryMode.PROMPT_PICKER) {
                                                viewModel.recoverPromptFromImage(file)
                                                navController.popBackStack()
                                            } else {
                                                fullscreenIndex = index
                                            }
                                        },
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(4.dp)) {
                        items(count = files.size, key = { files[it].fullpath }) { index ->
                            val file = files[index]
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).clickable {
                                    if (file.isDir) {
                                        viewModel.fetchGalleryFolder(file.fullpath)
                                    } else {
                                        if (mode == GalleryMode.PROMPT_PICKER) {
                                            viewModel.recoverPromptFromImage(file)
                                            navController.popBackStack()
                                        } else {
                                            fullscreenIndex = index
                                        }
                                    }
                                },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                                    if (file.isDir) {
                                        Icon(Icons.Default.Folder, null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                                    } else {
                                        AsyncImage(
                                            model = viewModel.getGalleryImageUrl(file),
                                            contentDescription = file.name,
                                            modifier = Modifier.size(40.dp).clip(MaterialTheme.shapes.small),
                                            contentScale = ContentScale.Crop
                                        )
                                    }
                                    Spacer(Modifier.width(16.dp))
                                    Column {
                                        Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                                        if (!file.isDir && file.date != null) {
                                            Text(file.date, fontSize = 10.sp, color = Color.Gray)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (fullscreenIndex >= 0) {
                val imagesOnly = files.filter { !it.isDir }
                val clickedItem = files[fullscreenIndex]
                val imageIndex = imagesOnly.indexOf(clickedItem).coerceAtLeast(0)

                val pagerState = rememberPagerState(initialPage = imageIndex, pageCount = { imagesOnly.size })
                val context = LocalContext.current

                LaunchedEffect(pagerState.currentPage) {
                    val currentItem = imagesOnly.getOrNull(pagerState.currentPage)
                    if (currentItem != null) {
                        viewModel.loadMetadataForImage(currentItem)
                    }
                }

                Dialog(onDismissRequest = { fullscreenIndex = -1 }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                        Row(modifier = Modifier.fillMaxWidth().background(Color(0x88000000)).padding(vertical = 4.dp, horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { fullscreenIndex = -1 }) { Icon(Icons.Default.Close, null, tint = Color.White) }

                            val currentItem = imagesOnly.getOrNull(pagerState.currentPage)
                            Text(currentItem?.name ?: "", color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))

                            IconButton(onClick = {
                                currentItem?.let { item ->
                                    viewModel.shareImage(item) { intent ->
                                        context.startActivity(intent)
                                    }
                                }
                            }) { Icon(Icons.Default.Share, null, tint = Color.White) }

                            val showMetadata by viewModel.showGalleryMetadata.collectAsStateWithLifecycle()
                            IconButton(onClick = { viewModel.toggleGalleryMetadata() }) {
                                Icon(Icons.Default.Info, "Info".t, tint = Color.White, modifier = Modifier.then(if (showMetadata) Modifier.background(Color(0x55FFFFFF), CircleShape).padding(2.dp) else Modifier))
                            }
                            IconButton(onClick = { currentItem?.let { viewModel.downloadImage(it) } }) { Icon(Icons.Default.Save, null, tint = Color.White) }
                        }

                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            if (config.swipeToBrowseGallery) {
                                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                                    val item = imagesOnly[page]
                                    AsyncImage(
                                        model = viewModel.getGalleryImageUrl(item),
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Fit,
                                        alignment = Alignment.TopCenter
                                    )
                                }
                            } else {
                                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                    val item = imagesOnly[pagerState.currentPage]
                                    AsyncImage(
                                        model = viewModel.getGalleryImageUrl(item),
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxWidth(),
                                        contentScale = ContentScale.Fit,
                                        alignment = Alignment.TopCenter
                                    )
                                }
                            }

                            val showMetadata by viewModel.showGalleryMetadata.collectAsStateWithLifecycle()
                            val currentMetadata by viewModel.currentImageMetadata.collectAsStateWithLifecycle()

                            if (showMetadata) {
                                MetadataAlertDialog(
                                    metadata = currentMetadata,
                                    onDismiss = { viewModel.toggleGalleryMetadata() },
                                    onApplyAll = null,
                                    onApplyPrompt = { pos, neg ->
                                        viewModel.updateState { it.copy(positivePrompt = pos, negativePrompt = neg) }
                                        Toast.makeText(context, "Prompts Applied".t, Toast.LENGTH_SHORT).show()
                                    },
                                    onApplyModel = { modelName ->
                                        viewModel.changeCheckpoint(modelName)
                                        Toast.makeText(context, "Model Applied: ".t + modelName, Toast.LENGTH_SHORT).show()
                                    },
                                    onApplyLoras = { loraList ->
                                        loraList.forEach { loraTag ->
                                            val loraName = loraTag.substringAfter("<lora:").substringBefore(":")
                                            if (loraName.isNotEmpty()) viewModel.appendLora(loraName)
                                        }
                                        Toast.makeText(context, "LoRAs Applied".t, Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}