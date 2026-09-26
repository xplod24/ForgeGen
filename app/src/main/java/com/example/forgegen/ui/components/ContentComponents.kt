package com.example.forgegen.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.forgegen.ApiResource
import com.example.forgegen.ContentFilter

/* ============================================================================
 * CONTENT MODE IN THE UI
 * Blurring and labels that follow the content mode (ContentFilter).
 * ============================================================================ */

/** The blur of a model or LoRA preview: in SFW every preview that Civitai did not rate as safe. */
fun Modifier.previewBlur(
    resource: ApiResource,
    mode: String,
): Modifier = if (ContentFilter.blursPreview(resource.previewLevel, mode)) blur(8.dp) else this

/** An image blurred by the content mode (blurs are only drawn on Android 12+, which the app requires anyway). */
fun Modifier.contentBlur(blurred: Boolean): Modifier = if (blurred) blur(24.dp) else this

/** A prompt tag the content mode hides (ContentFilter.isHidden) is blurred in the tag editor. */
fun Modifier.tagBlur(hidden: Boolean): Modifier = if (hidden) blur(5.dp) else this

/**
 * An image as the content mode wants it: blurred, with a button that shows it, or (for the images refused in every
 * mode) with a note instead. [content] draws the image with the modifier it gets.
 */
@Composable
fun ContentGate(
    rating: ContentFilter.Rating,
    mode: String,
    revealed: Boolean,
    onReveal: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val canReveal = ContentFilter.canReveal(rating)
    val blurred = ContentFilter.blurs(rating, mode) && !(revealed && canReveal)
    Box(contentAlignment = Alignment.Center) {
        content(Modifier.contentBlur(blurred))
        if (blurred) {
            if (canReveal) {
                FilledTonalButton(onClick = onReveal) {
                    Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Show image")
                }
            } else {
                Text(
                    "Hidden in every content mode: its prompt has sexual content with a minor.",
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp)).padding(8.dp),
                )
            }
        }
    }
}

/** Labels for a model Civitai marks as NSFW or as a real person. */
@Composable
fun ModelBadges(resource: ApiResource) {
    if (!resource.nsfw && !resource.realPerson) return
    Row {
        if (resource.nsfw) Badge("NSFW")
        if (resource.realPerson) {
            if (resource.nsfw) Spacer(Modifier.width(4.dp))
            Badge("Real person")
        }
    }
}

@Composable
private fun Badge(text: String) {
    Text(
        text,
        fontSize = 9.sp,
        color = MaterialTheme.colorScheme.onErrorContainer,
        modifier =
            Modifier
                .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}
