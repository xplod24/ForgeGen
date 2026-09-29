package com.example.forgegen.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.forgegen.ServerConnection
import com.example.forgegen.ServerMemory

/* ============================================================================
 * FLOATING TOP BAR (3.0.0-1, the owner's pick "C" of three mockups)
 * The top bar of the queue, presets, wildcards and gallery: a rounded pill floating under the status bar, the
 * mirror of the main screen's generate bar.
 * ============================================================================ */

@Composable
fun FloatingTopBar(
    title: String,
    onNavigate: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigationIcon: ImageVector = Icons.AutoMirrored.Filled.ArrowBack,
    navigationDescription: String = "Back",
    // The gallery's selection mode shows in another colour.
    containerColor: Color = MaterialTheme.colorScheme.surface,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Box(modifier = modifier.fillMaxWidth().statusBarsPadding().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = containerColor,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Row(modifier = Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onNavigate) { Icon(navigationIcon, contentDescription = navigationDescription) }
                Column(modifier = Modifier.weight(1f).padding(start = 2.dp)) {
                    Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                actions()
            }
        }
    }
}

/* ============================================================================
 * MAIN SCREEN TOP BAR (3.0.0-4, the owner's pick "B" of three mockups)
 * The same floating pill: the app's name over the connection (a tap opens the server dialog), then two small meters
 * of the server's VRAM and RAM, which open the Server Memory panel with Unload Model. It replaced a bar of three lines
 * (name, ping, a tiny "RAM | VRAM" line) with an Unload icon. 64 dp high like the old bar (TypingLayout.TOP_BAR_DP).
 * ============================================================================ */

@Composable
fun MainTopBar(
    connection: ServerConnection,
    pingMs: Long,
    searchEndsAt: Long,
    memory: ServerMemory?,
    onConnectionClick: () -> Unit,
    onMemoryClick: () -> Unit,
    onGalleryClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    // When Restart Forge was asked for (3.3.0); 0 while Forge is not restarting.
    restartingSince: Long = 0L,
    // Settings > Features > Memory Meters (3.4.0): off, an icon opens the Server Memory panel instead.
    showMeters: Boolean = true,
) {
    Box(modifier = modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Row(modifier = Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(
                    verticalArrangement = Arrangement.Center,
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .clickable(onClickLabel = "Server connection", onClick = onConnectionClick)
                            .padding(start = 12.dp, end = 4.dp),
                ) {
                    Text("ForgeGen", fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    ConnectionStatus(connection, pingMs, searchEndsAt, restartingSince)
                }
                // Only what the server reported, and only while connected (connectionFailed forgets it).
                if (!showMeters && connection == ServerConnection.CONNECTED) {
                    IconButton(onClick = onMemoryClick) { Icon(Icons.Default.Memory, contentDescription = "Server Memory") }
                } else if (memory != null && connection == ServerConnection.CONNECTED) {
                    MemoryMeters(memory, onClick = onMemoryClick)
                }
                IconButton(onClick = onGalleryClick) { Icon(Icons.Default.PhotoLibrary, contentDescription = "Gallery") }
                IconButton(onClick = onSettingsClick) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
            }
        }
    }
}

/** VRAM and RAM as two short bars with "used/total" GB; orange from ServerMemory.ALMOST_FULL on. */
@Composable
private fun MemoryMeters(
    memory: ServerMemory,
    onClick: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        modifier =
            Modifier
                .padding(end = 2.dp)
                .height(44.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClickLabel = "Server memory", onClick = onClick)
                .padding(horizontal = 10.dp),
    ) {
        if (memory.hasVram) MeterRow("VRAM", memory.vramUsed, memory.vramTotal, MaterialTheme.colorScheme.primary)
        if (memory.hasRam) MeterRow("RAM", memory.ramUsed, memory.ramTotal, MaterialTheme.colorScheme.secondary)
    }
}

@Composable
private fun MeterRow(
    label: String,
    used: Double,
    total: Double,
    color: Color,
) {
    val share = ServerMemory.share(used, total)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.4.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(32.dp),
        )
        MeterTrack(share, color, Modifier.width(36.dp).height(4.dp))
        Text(
            ServerMemory.compact(used, total),
            fontSize = 11.sp,
            lineHeight = 12.sp,
            style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.padding(start = 6.dp).widthIn(min = 44.dp),
        )
    }
}

/** A rounded bar filled to [share]; orange when the memory is almost full. */
@Composable
private fun MeterTrack(
    share: Float,
    color: Color,
    modifier: Modifier,
) {
    val fill = if (share >= ServerMemory.ALMOST_FULL) ALMOST_FULL_COLOR else color
    Box(modifier = modifier.clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f))) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(share)
                .clip(CircleShape)
                .background(fill),
        )
    }
}

private val ALMOST_FULL_COLOR = Color(0xFFFFA726)

/**
 * The server's VRAM and RAM in full, and Unload Model (POST /sdapi/v1/unload-checkpoint). Unloading waits while an
 * image is being made. The meters and these bars read the memory again right after it (ForgeViewModel).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerMemorySheet(
    memory: ServerMemory?,
    modelName: String,
    unloading: Boolean,
    busy: Boolean,
    onUnload: () -> Unit,
    onDismiss: () -> Unit,
    // Restart Forge (3.3.0): whether Forge can be restarted from here (null: not known yet) and whether it is.
    canRestart: Boolean? = null,
    restarting: Boolean = false,
    onRestart: (() -> Unit)? = null,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            Column {
                Text("Server Memory", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                if (modelName.isNotBlank()) {
                    Text(
                        "Selected model: $modelName",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (memory == null) {
                Text("The server has not reported its memory.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                if (memory.hasVram) MemoryDetail("VRAM", memory.vramUsed, memory.vramTotal, MaterialTheme.colorScheme.primary)
                if (memory.hasRam) MemoryDetail("RAM", memory.ramUsed, memory.ramTotal, MaterialTheme.colorScheme.secondary)
            }
            FilledTonalButton(
                onClick = onUnload,
                enabled = !unloading && !busy,
                colors =
                    ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.16f),
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                if (unloading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.error)
                } else {
                    Icon(Icons.Default.Memory, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(if (unloading) "Unloading..." else "Unload Model", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(
                if (busy) {
                    "Available when no image is being made."
                } else {
                    "Frees the model from the server's memory (on Forge Neo from RAM too). The next image loads it again."
                },
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onRestart != null) {
                RestartForgeButton(canRestart = canRestart, restarting = restarting, onClick = onRestart)
                if (canRestart == false) {
                    Text(RESTART_NEEDS_FLAG_HINT, fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun MemoryDetail(
    label: String,
    used: Double,
    total: Double,
    color: Color,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(
                ServerMemory.detail(used, total),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
            )
        }
        MeterTrack(ServerMemory.share(used, total), color, Modifier.fillMaxWidth().height(8.dp))
    }
}
