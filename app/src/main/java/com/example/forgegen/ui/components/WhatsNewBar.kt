package com.example.forgegen.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/* ============================================================================
 * WHAT'S NEW BAR (3.0.0)
 * After an update a half-transparent bar floats at the top of the app instead of opening the notes at once: "Show"
 * opens them. It bobs gently, gives a light shake every 5 seconds, and after 30 seconds flies up off the screen.
 * ============================================================================ */

const val WHATS_NEW_BAR_MS = 30_000L
private const val SHAKE_EVERY_MS = 5_000L
private const val LEAVE_MS = 600

@Composable
fun WhatsNewBar(
    visible: Boolean,
    version: String,
    onShown: () -> Unit,
    onShow: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnShown by rememberUpdatedState(onShown)
    val currentOnClose by rememberUpdatedState(onClose)
    // Shown once: seen as soon as it is on screen, gone after its time.
    LaunchedEffect(visible) {
        if (visible) {
            currentOnShown()
            delay(WHATS_NEW_BAR_MS)
            currentOnClose()
        }
    }

    // It leaves past the status bar, not only by its own height.
    val density = LocalDensity.current
    val abovePx = with(density) { WindowInsets.statusBars.getTop(this) + 24.dp.roundToPx() }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
        enter = slideInVertically(tween(450, easing = LinearOutSlowInEasing)) { -(it + abovePx) } + fadeIn(tween(300)),
        exit = slideOutVertically(tween(LEAVE_MS, easing = FastOutSlowInEasing)) { -(it + abovePx) } + fadeOut(tween(LEAVE_MS)),
    ) {
        val float = rememberInfiniteTransition(label = "float")
        val bob by float.animateFloat(
            initialValue = -3f,
            targetValue = 3f,
            animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "bob",
        )
        val shake = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(SHAKE_EVERY_MS)
                for (x in listOf(-6f, 6f, -4f, 4f, -2f, 0f)) shake.animateTo(x, tween(70))
            }
        }

        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.8f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)),
            shadowElevation = 6.dp,
            modifier =
                Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .graphicsLayer {
                        translationY = bob.dp.toPx()
                        translationX = shake.value.dp.toPx()
                    },
        ) {
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "What's new in $version",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onShow) { Text("Show", fontWeight = FontWeight.SemiBold) }
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
