package com.example.forgegen.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.forgegen.ApiResource

/* ============================================================================
 * MODEL LABELS
 * Civitai's labels of a model (NSFW, a real person), shown next to it. The real-person label also feeds rule 2 of
 * BlockingApi (no nudity with a real person's LoRA).
 * ============================================================================ */

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
