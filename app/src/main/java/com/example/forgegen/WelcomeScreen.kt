package com.example.forgegen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

@Composable
fun WelcomeScreen(navController: NavHostController) {
    val context = LocalContext.current
    val buildTextValue = remember { AppVersion.currentVersion }

    val animationProgress = remember { Animatable(0f) }
    val textMeasurer = rememberTextMeasurer()
    val primaryColor = MaterialTheme.colorScheme.primary
    val onBackgroundColor = MaterialTheme.colorScheme.onBackground

    LaunchedEffect(Unit) {
        // Smooth animation from 0.0 to 1.0 extended to 3 seconds to fit the heartbeat effect
        animationProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 3000, easing = FastOutSlowInEasing),
        )
        // Navigate clearing the stack so the user cannot navigate back to the Welcome Screen
        navController.navigate("main") {
            popUpTo("welcome") { inclusive = true }
        }
    }

    Canvas(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
    ) {
        val p = animationProgress.value
        val center = Offset(size.width / 2f, size.height / 2f)

        // Animation phases:
        // 0.0 - 0.3: Rays converging towards the center
        // 0.3 - 0.45: Anvil fading in
        // 0.45 - 0.85: Anvil beating (heartbeat effect) while text fades in

        val rayProgress = (p / 0.3f).coerceIn(0f, 1f)
        val rayAlpha = 1f - ((p - 0.3f) / 0.15f).coerceIn(0f, 1f)

        val anvilAlpha = ((p - 0.3f) / 0.15f).coerceIn(0f, 1f) // Pure fade-in
        val heartbeatProgress = ((p - 0.45f) / 0.4f).coerceIn(0f, 1f)

        // Heartbeat effect (two pulse waves) expanding scale by up to 20%
        val pulse =
            if (heartbeatProgress > 0f && heartbeatProgress < 1f) {
                max(0f, sin(heartbeatProgress * Math.PI * 4).toFloat()) * 0.2f
            } else {
                0f
            }

        // Constant base scale with pulse applied - ensures no translation jitter
        val anvilScale = 1f + pulse

        // Text "ForgeGen" and version build info fade-in
        val textProgress = ((p - 0.45f) / 0.3f).coerceIn(0f, 1f)

        // Phase 1: Five rays converging to the center
        if (rayAlpha > 0f) {
            val maxRadius = size.width.coerceAtLeast(size.height)
            val rayLength = 150f
            val currentRadius = maxRadius - (maxRadius * rayProgress)

            for (i in 0 until 5) {
                val angle = (i * 72.0) * Math.PI / 180.0
                val startX = center.x + cos(angle).toFloat() * currentRadius
                val startY = center.y + sin(angle).toFloat() * currentRadius
                val endX = center.x + cos(angle).toFloat() * (currentRadius + rayLength)
                val endY = center.y + sin(angle).toFloat() * (currentRadius + rayLength)

                drawLine(
                    color = primaryColor.copy(alpha = rayAlpha),
                    start = Offset(startX, startY),
                    end = Offset(endX, endY),
                    strokeWidth = 12f,
                    cap = StrokeCap.Round,
                )
            }
        }

        // Phase 2 & 3: Drawing the anvil (fade-in + heartbeat scaling in place)
        if (anvilAlpha > 0f) {
            val anvilPath =
                Path().apply {
                    moveTo(-60f, -40f) // Top-left corner
                    lineTo(40f, -40f) // Top-right corner (base of horn)
                    quadraticTo(70f, -40f, 70f, -10f) // Tip of horn
                    quadraticTo(40f, -10f, 30f, -10f) // Bottom of horn
                    quadraticTo(15f, -10f, 15f, 20f) // Right-side indent
                    lineTo(30f, 50f) // Right base
                    lineTo(-30f, 50f) // Left base
                    lineTo(-15f, 20f) // Left-side indent
                    quadraticTo(-15f, -10f, -60f, -10f) // Bottom-left tail
                    close()
                }

            withTransform({
                translate(left = center.x, top = center.y - 20f)
                // Set the pivot exactly on the geometric center of the path (5f, 5f)
                // This ensures heartbeat scaling is performed perfectly from the center
                scale(scaleX = anvilScale, scaleY = anvilScale, pivot = Offset(5f, 5f))
            }) {
                drawPath(
                    path = anvilPath,
                    color = onBackgroundColor.copy(alpha = anvilAlpha),
                )
            }
        }

        // Phase 4: Text fade-in of application name and version details during heartbeat
        if (textProgress > 0f) {
            val titleText = "ForgeGen"
            val titleStyle =
                TextStyle(
                    color = primaryColor.copy(alpha = textProgress),
                    fontSize = 36.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            val titleLayoutResult = textMeasurer.measure(text = titleText, style = titleStyle)

            val buildText = buildTextValue
            val buildStyle =
                TextStyle(
                    color = primaryColor.copy(alpha = textProgress),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            val buildLayoutResult = textMeasurer.measure(text = buildText, style = buildStyle)

            // Subtle vertical entry transition (offset decays to zero as alpha reaches 1.0)
            val textYOffset = (1f - textProgress) * 50f
            val startY = center.y + 70f + textYOffset

            drawText(
                textLayoutResult = titleLayoutResult,
                topLeft =
                    Offset(
                        x = center.x - (titleLayoutResult.size.width / 2f),
                        y = startY,
                    ),
            )

            drawText(
                textLayoutResult = buildLayoutResult,
                topLeft =
                    Offset(
                        x = center.x - (buildLayoutResult.size.width / 2f),
                        y = startY + titleLayoutResult.size.height + 4f,
                    ),
            )
        }
    }
}
