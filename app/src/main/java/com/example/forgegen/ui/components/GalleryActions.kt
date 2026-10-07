package com.example.forgegen.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ImageNotSupported
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.forgegen.AllImagesOrder
import com.example.forgegen.ForgeGalleryManager
import com.example.forgegen.ForgeViewModel
import com.example.forgegen.GalleryItem
import com.example.forgegen.GalleryPaths
import kotlinx.coroutines.launch

/* ============================================================================
 * GALLERY ACTIONS (3.2.0)
 * The selection's "More" menu, the Undo bar after a delete, folder covers, the folder picker for moving and copying,
 * the note about favorites gone from the server, and All Images' Newest First / Random.
 * ============================================================================ */

private fun count(n: Int) = if (n == 1) "1 image" else "$n images"

/**
 * The selection's "More" button (board 3A). Moving, copying and deleting need a server whose gallery extension may
 * change files; on a read-only one they are greyed out and the menu says why.
 */
@Composable
fun SelectionMoreMenu(
    canWrite: Boolean,
    onSave: () -> Unit,
    onZip: () -> Unit,
    // Null without Settings > Features > Image Jobs (3.4.0).
    onUpscale: (() -> Unit)?,
    onMove: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onVault: (() -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "More Actions") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            @Composable
            fun item(
                label: String,
                icon: androidx.compose.ui.graphics.vector.ImageVector,
                enabled: Boolean = true,
                color: Color = Color.Unspecified,
                onClick: () -> Unit,
            ) = DropdownMenuItem(
                text = { Text(label, color = if (enabled) color else Color.Unspecified) },
                leadingIcon = {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint =
                            if (enabled &&
                                color != Color.Unspecified
                            ) {
                                color
                            } else {
                                androidx.compose.material3.LocalContentColor.current
                            },
                    )
                },
                enabled = enabled,
                onClick = {
                    open = false
                    onClick()
                },
            )
            item("Save to Phone", Icons.Default.Save, onClick = onSave)
            if (onVault != null) item("Save to Remote Vault", Icons.Default.Save, onClick = onVault)
            item("Download as ZIP", Icons.Default.FolderZip, onClick = onZip)
            if (onUpscale != null) item("Upscale or Vary", Icons.Default.OpenInFull, onClick = onUpscale)
            HorizontalDivider()
            item("Move to Folder", Icons.AutoMirrored.Filled.DriveFileMove, enabled = canWrite, onClick = onMove)
            item("Copy to Folder", Icons.Default.ContentCopy, enabled = canWrite, onClick = onCopy)
            HorizontalDivider()
            item(
                "Delete from Server",
                Icons.Default.Delete,
                enabled = canWrite,
                color = MaterialTheme.colorScheme.error,
                onClick = onDelete,
            )
            if (!canWrite) {
                Text(
                    "The server's gallery is read-only",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/**
 * "3 images deleted from the server · Undo" (board 3B). It shows while Undo can still take the delete back; the
 * server deletes them when it goes (about 6 s).
 */
@Composable
fun UndoDeleteBar(
    pending: ForgeGalleryManager.PendingDelete?,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shown = rememberLastActive(pending, null)
    AnimatedVisibility(
        visible = pending != null,
        enter = slideInVertically(tween(UNDO_ANIMATION_MS)) { it / 2 } + fadeIn(tween(UNDO_ANIMATION_MS)),
        exit = slideOutVertically(tween(UNDO_ANIMATION_MS)) { it / 2 } + fadeOut(tween(UNDO_ANIMATION_MS)),
        modifier = modifier,
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shadowElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.heightIn(min = 52.dp).padding(start = 16.dp, end = 8.dp),
            ) {
                Text("${count(shown?.items?.size ?: 0)} deleted from the server", fontSize = 14.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onUndo) {
                    Text("Undo", color = MaterialTheme.colorScheme.inversePrimary, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private const val UNDO_ANIMATION_MS = 200

/** A folder's newest images as a 2×2 cover (board 3C); empty places stay plain. */
@Composable
fun FolderCover(
    covers: List<GalleryItem>,
    thumbnailUrl: (GalleryItem) -> String,
    modifier: Modifier = Modifier,
    gap: androidx.compose.ui.unit.Dp = 2.dp,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        repeat(2) { row ->
            Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                repeat(2) { column ->
                    val item = covers.getOrNull(row * 2 + column)
                    Box(modifier = Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant)) {
                        if (item != null) {
                            AsyncImage(
                                model = thumbnailUrl(item),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A small folder picture: its cover once the server sent it, else a folder icon. */
@Composable
fun FolderThumb(
    covers: List<GalleryItem>?,
    thumbnailUrl: (GalleryItem) -> String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (covers.isNullOrEmpty()) {
            Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        } else {
            FolderCover(covers, thumbnailUrl, Modifier.fillMaxSize())
        }
    }
}

/** "2 favorites are no longer on the server · Remove" at the top of the Favorites tab (board 3C). */
@Composable
fun MissingFavoritesNote(
    missing: Int,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Icon(
                Icons.Default.ImageNotSupported,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                if (missing == 1) "1 favorite is no longer on the server" else "$missing favorites are no longer on the server",
                fontSize = 13.sp,
                lineHeight = 17.sp,
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(onClick = onRemove) { Text("Remove", fontSize = 13.sp) }
        }
    }
}

/** All Images: the newest first, or Random (again: shuffled anew); and the statistics (board 3C). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllImagesOrderRow(
    order: AllImagesOrder,
    onRandom: (Boolean) -> Unit,
    onStatistics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
    ) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
            SegmentedButton(
                selected = !order.random,
                onClick = { onRandom(false) },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
                label = { Text("Newest First", maxLines = 1) },
            )
            SegmentedButton(
                selected = order.random,
                onClick = { onRandom(true) },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                icon = { Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp)) },
                label = { Text(if (order.random) "Shuffle Again" else "Random", maxLines = 1) },
            )
        }
        IconButton(onClick = onStatistics) { Icon(Icons.Default.BarChart, "Statistics") }
    }
}

/**
 * Where the selected images go (board 3B): the gallery's folders, opened one inside another, each with its cover
 * and number of images; New Folder makes one here. "Move Here" and "Copy Here" act on the folder shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPickerSheet(
    viewModel: ForgeViewModel,
    items: List<GalleryItem>,
    kind: ForgeGalleryManager.Transfer,
    onPick: (String, ForgeGalleryManager.Transfer) -> Unit,
    onDismiss: () -> Unit,
) {
    val root = viewModel.readyGalleryRoot() ?: return
    var path by rememberSaveable { mutableStateOf(root) }
    var reload by remember { mutableIntStateOf(0) }
    var folders by remember { mutableStateOf<List<GalleryItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(path, reload) {
        folders = null
        error = null
        try {
            folders = viewModel.gallerySubfolders(path)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "The folders could not be listed"
        }
    }
    val covers by viewModel.galleryFolderCovers.collectAsStateWithLifecycle()
    val counts by viewModel.galleryFolderImageCounts.collectAsStateWithLifecycle()
    val lastFolder by viewModel.galleryLastFolder.collectAsStateWithLifecycle()
    var naming by remember { mutableStateOf(false) }
    val thumb: (GalleryItem) -> String = { viewModel.getGalleryThumbnailUrl(it) }
    // Nothing to move when every image is in this folder already.
    val allHere = items.all { GalleryPaths.same(GalleryPaths.parentOf(it.fullpath), path) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
            Text(
                "${if (kind == ForgeGalleryManager.Transfer.MOVE) "Move" else "Copy"} ${items.size} ${if (items.size == 1) "Image" else "Images"}",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
            // Where the picker is, each folder above it a tap away.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
            ) {
                val crumbs = remember(path) { viewModel.galleryBreadcrumb(path) }
                crumbs.forEachIndexed { index, (name, crumbPath) ->
                    val last = index == crumbs.lastIndex
                    Text(
                        name,
                        fontSize = 13.sp,
                        color = if (last) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                        fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.clickable(enabled = !last) { path = crumbPath }.padding(vertical = 4.dp),
                    )
                    if (!last) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                item {
                    PickerRow(onClick = { naming = true }) {
                        Box(
                            modifier =
                                Modifier
                                    .size(
                                        48.dp,
                                    ).clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.CreateNewFolder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                        Text("New Folder", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    }
                }
                val last = lastFolder
                if (last != null && GalleryPaths.same(path, root) && !GalleryPaths.same(last, root)) {
                    item {
                        FolderRow(
                            name = viewModel.galleryFolderName(last),
                            detail = counts[GalleryPaths.key(last)]?.let { "${count(it)} · last used" } ?: "Last used",
                            covers = covers[GalleryPaths.key(last)],
                            thumb = thumb,
                            icon = Icons.Default.History,
                            onClick = { path = last },
                        )
                    }
                }
                val list = folders
                when {
                    error != null ->
                        item {
                            Text(
                                error.orEmpty(),
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        }
                    list == null ->
                        item {
                            Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                        }
                    list.isEmpty() ->
                        item {
                            Text(
                                "No folders inside",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        }
                    else ->
                        items(list, key = { it.fullpath }) { folder ->
                            FolderRow(
                                name = folder.name,
                                detail = counts[GalleryPaths.key(folder.fullpath)]?.let { count(it) },
                                covers = covers[GalleryPaths.key(folder.fullpath)],
                                thumb = thumb,
                                onClick = { path = folder.fullpath },
                            )
                        }
                }
            }
            Text(
                "Their .txt files with the generation data go with them.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val move = { onPick(path, ForgeGalleryManager.Transfer.MOVE) }
                val copy = { onPick(path, ForgeGalleryManager.Transfer.COPY) }
                if (kind == ForgeGalleryManager.Transfer.MOVE) {
                    OutlinedButton(onClick = { copy() }, enabled = !allHere, modifier = Modifier.weight(1f)) { Text("Copy Here") }
                    Button(onClick = { move() }, enabled = !allHere, modifier = Modifier.weight(1f)) { Text("Move Here") }
                } else {
                    OutlinedButton(onClick = { move() }, enabled = !allHere, modifier = Modifier.weight(1f)) { Text("Move Here") }
                    Button(onClick = { copy() }, enabled = !allHere, modifier = Modifier.weight(1f)) { Text("Copy Here") }
                }
            }
        }
    }

    if (naming) {
        NewFolderDialog(
            viewModel = viewModel,
            parent = path,
            onMade = { made ->
                naming = false
                path = made
                reload++
            },
            onDismiss = { naming = false },
        )
    }
}

@Composable
private fun PickerRow(
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(
                    RoundedCornerShape(12.dp),
                ).clickable(onClick = onClick)
                .padding(vertical = 6.dp, horizontal = 4.dp),
    ) { content() }
}

@Composable
private fun FolderRow(
    name: String,
    detail: String?,
    covers: List<GalleryItem>?,
    thumb: (GalleryItem) -> String,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    PickerRow(onClick = onClick) {
        FolderThumb(covers, thumb, Modifier.size(48.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(name, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (detail != null) Text(detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NewFolderDialog(
    viewModel: ForgeViewModel,
    parent: String,
    onMade: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val problem = GalleryPaths.folderNameProblem(name).takeIf { name.isNotEmpty() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Folder") },
        text = {
            Column {
                Text("In ${viewModel.galleryFolderName(parent)}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        failure = null
                    },
                    singleLine = true,
                    label = { Text("Name") },
                    isError = problem != null || failure != null,
                    supportingText = (problem ?: failure)?.let { { Text(it) } },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && name.isNotBlank() && problem == null,
                onClick = {
                    busy = true
                    scope.launch {
                        viewModel
                            .createGalleryFolder(parent, name)
                            .onSuccess(onMade)
                            .onFailure { failure = it.message }
                        busy = false
                    }
                },
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
