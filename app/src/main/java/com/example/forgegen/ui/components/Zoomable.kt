package com.example.forgegen.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize

/* ============================================================================
 * ZOOM
 * Pinch to zoom an image (up to 5x) and double tap to zoom in at the tapped point (2.5x) or back out. A zoomed image
 * moves with one finger; at 1x one-finger swipes are left alone, so the pager still turns to the next image. The
 * "Pinch to Zoom" setting (2.1.0) switches it off.
 * ============================================================================ */

private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f

/** The size (px) to decode a zoomable image at: twice the screen's width, at most 2048, so zooming stays sharp. */
@Composable
fun zoomableImageSizePx(): Int {
    val widthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp * density }
    return (widthPx * 2).toInt().coerceIn(512, 2048)
}

/** Zoom for a full-screen image; [enabled] false (the "Pinch to Zoom" setting off) leaves the image as it is. */
@Composable
fun Modifier.zoomable(
    key: Any?,
    enabled: Boolean = true,
): Modifier {
    if (!enabled) return this
    var scale by remember(key) { mutableFloatStateOf(1f) }
    var offset by remember(key) { mutableStateOf(Offset.Zero) }

    // Offsets are those of the layer (scaled around its middle): a point stays under the finger when
    // offset' = (g - c) - (g - c - offset) * scale' / scale, g the finger, c the middle.
    fun clamp(
        value: Offset,
        s: Float,
        size: IntSize,
    ): Offset {
        val maxX = (s - 1f) * size.width / 2f
        val maxY = (s - 1f) * size.height / 2f
        return Offset(value.x.coerceIn(-maxX, maxX), value.y.coerceIn(-maxY, maxY))
    }

    return this
        .clipToBounds()
        .pointerInput(key) {
            detectTapGestures(onDoubleTap = { tap ->
                if (scale > 1f) {
                    scale = 1f
                    offset = Offset.Zero
                } else {
                    val middle = Offset(size.width / 2f, size.height / 2f)
                    offset = clamp((tap - middle) * (1f - DOUBLE_TAP_ZOOM), DOUBLE_TAP_ZOOM, size)
                    scale = DOUBLE_TAP_ZOOM
                }
            })
        }.pointerInput(key) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.none { it.pressed }) break
                    val fingers = event.changes.count { it.pressed }
                    // One finger at 1x belongs to the pager (or the page's scroll).
                    if (fingers < 2 && scale <= 1f) continue
                    val zoom = event.calculateZoom()
                    val pan = event.calculatePan()
                    val centroid = event.calculateCentroid(useCurrent = true)
                    val middle = Offset(size.width / 2f, size.height / 2f)
                    val newScale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                    val anchored =
                        if (centroid == Offset.Unspecified) {
                            offset
                        } else {
                            (centroid - middle) - (centroid - middle - offset) * (newScale / scale)
                        }
                    offset = if (newScale <= 1f) Offset.Zero else clamp(anchored + pan, newScale, size)
                    scale = newScale
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            }
        }.graphicsLayer {
            scaleX = scale
            scaleY = scale
            translationX = offset.x
            translationY = offset.y
        }
}
