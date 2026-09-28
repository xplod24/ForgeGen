package com.example.forgegen.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
