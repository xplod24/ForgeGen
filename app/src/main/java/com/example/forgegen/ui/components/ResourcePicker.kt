package com.example.forgegen.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.forgegen.ApiResource

/* ============================================================================
 * RESOURCE PICKER
 * Models and LoRAs to choose from, in a sheet with a search field. The rows (and their previews) are built only as
 * they scroll into view; the dropdown menus used to build every row with its preview at once.
 * ============================================================================ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResourcePickerSheet(
    title: String,
    items: List<ApiResource>,
    isSelected: (ApiResource) -> Boolean,
    previewUrl: (ApiResource) -> String,
    onPick: (ApiResource) -> Unit,
    onDismiss: () -> Unit,
    // Asks the server for its list again (the old "Check Checkpoints" / "Check Loras" buttons, 3.0.0).
    onRefresh: (() -> Unit)? = null,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val shown =
        remember(items, query) {
            val text = query.trim()
            if (text.isEmpty()) items else items.filter { it.title.contains(text, ignoreCase = true) }
        }
    val context = LocalContext.current

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                if (onRefresh != null) {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh $title List")
                    }
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search (${items.size})") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                items(shown, key = { it.path.ifEmpty { it.name } }) { resource ->
                    val selected = isSelected(resource)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else Color.Transparent,
                                ).clickable { onPick(resource) }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                    ) {
                        val url = remember(resource.path) { previewUrl(resource) }
                        AsyncImage(
                            model =
                                remember(url) {
                                    ImageRequest
                                        .Builder(context)
                                        .data(url)
                                        .size(160) // a small preview, not the full picture
                                        .crossfade(true)
                                        .build()
                                },
                            contentDescription = null,
                            modifier =
                                Modifier
                                    .padding(end = 10.dp)
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.DarkGray),
                            contentScale = ContentScale.Crop,
                        )
                        Text(
                            text = resource.title,
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (selected) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}
