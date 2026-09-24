package com.example.forgegen.ui.components
import com.example.forgegen.*

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/* ============================================================================
 * ANIMATED STATUS INDICATOR (tag parsing lives in PromptTags.kt)
 * ============================================================================ */

@Composable
fun AnimatedStatusIndicator(
    state: IndicatorState,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    strokeWidth: Dp = 6.dp,
) {
    val transition = updateTransition(targetState = state, label = "indicator_transition")

    val infiniteTransition = rememberInfiniteTransition(label = "infinite_rotation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(1200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
        label = "rotation",
    )

    val circleSweep by transition.animateFloat(
        transitionSpec = {
            if (targetState == IndicatorState.SUCCESS || targetState == IndicatorState.ERROR) {
                tween(durationMillis = 200, easing = FastOutSlowInEasing)
            } else {
                snap()
            }
        },
        label = "circle_sweep",
    ) { target ->
        when (target) {
            IndicatorState.LOADING -> 120f
            IndicatorState.SUCCESS, IndicatorState.ERROR -> 360f
            else -> 0f
        }
    }

    val tickProgress by transition.animateFloat(
        transitionSpec = {
            if (targetState == IndicatorState.SUCCESS) {
                tween(durationMillis = 200, easing = FastOutSlowInEasing, delayMillis = 200)
            } else {
                snap()
            }
        },
        label = "tick_progress",
    ) { if (it == IndicatorState.SUCCESS) 1f else 0f }

    val crossProgress by transition.animateFloat(
        transitionSpec = {
            if (targetState == IndicatorState.ERROR) {
                tween(durationMillis = 200, easing = FastOutSlowInEasing, delayMillis = 200)
            } else {
                snap()
            }
        },
        label = "cross_progress",
    ) { if (it == IndicatorState.ERROR) 1f else 0f }

    val scale by transition.animateFloat(
        transitionSpec = {
            if (targetState == IndicatorState.SUCCESS || targetState == IndicatorState.ERROR) {
                keyframes {
                    durationMillis = 200
                    delayMillis = 200
                    1f at 0
                    1.25f at 100 using FastOutSlowInEasing
                    1f at 200 using LinearOutSlowInEasing
                }
            } else {
                snap()
            }
        },
        label = "scale_pulse",
    ) { target ->
        when (target) {
            else -> 1f
        }
    }

    val color by transition.animateColor(
        transitionSpec = { tween(durationMillis = 200) },
        label = "indicator_color",
    ) { target ->
        when (target) {
            IndicatorState.LOADING -> MaterialTheme.colorScheme.primary
            IndicatorState.SUCCESS -> Color(0xFF4CAF50)
            IndicatorState.ERROR -> MaterialTheme.colorScheme.error
            else -> Color.Transparent
        }
    }

    Canvas(
        modifier =
            modifier
                .size(size)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    rotationZ = if (state == IndicatorState.LOADING) rotation else 0f
                },
    ) {
        val strokePx = strokeWidth.toPx()

        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = circleSweep,
            useCenter = false,
            style = Stroke(width = strokePx, cap = StrokeCap.Round),
        )

        if (tickProgress > 0f) {
            val tickPath =
                Path().apply {
                    moveTo(size.toPx() * 0.25f, size.toPx() * 0.5f)
                    lineTo(size.toPx() * 0.45f, size.toPx() * 0.7f)
                    lineTo(size.toPx() * 0.75f, size.toPx() * 0.35f)
                }
            val measure = PathMeasure().apply { setPath(tickPath, false) }
            val length = measure.length
            val dash =
                PathEffect.dashPathEffect(
                    floatArrayOf(length, length),
                    length - (length * tickProgress),
                )

            drawPath(
                path = tickPath,
                color = color,
                style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = dash),
            )
        }

        if (crossProgress > 0f) {
            val path1 =
                Path().apply {
                    moveTo(size.toPx() * 0.3f, size.toPx() * 0.3f)
                    lineTo(size.toPx() * 0.7f, size.toPx() * 0.7f)
                }
            val path2 =
                Path().apply {
                    moveTo(size.toPx() * 0.7f, size.toPx() * 0.3f)
                    lineTo(size.toPx() * 0.3f, size.toPx() * 0.7f)
                }

            val pm1 = PathMeasure().apply { setPath(path1, false) }
            val pm2 = PathMeasure().apply { setPath(path2, false) }

            val l1 = pm1.length
            val l2 = pm2.length

            val dash1 =
                PathEffect.dashPathEffect(
                    floatArrayOf(l1, l1),
                    l1 - (l1 * (crossProgress * 2f).coerceIn(0f, 1f)),
                )
            val dash2 =
                PathEffect.dashPathEffect(
                    floatArrayOf(l2, l2),
                    l2 - (l2 * ((crossProgress - 0.5f) * 2f).coerceIn(0f, 1f)),
                )

            drawPath(path1, color, style = Stroke(width = strokePx, cap = StrokeCap.Round, pathEffect = dash1))
            drawPath(path2, color, style = Stroke(width = strokePx, cap = StrokeCap.Round, pathEffect = dash2))
        }
    }
}

/* ============================================================================
 * HELPER CLASSES & FUNCTIONS
 * ============================================================================ */

class PromptVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val spanStyles = mutableListOf<AnnotatedString.Range<SpanStyle>>()
        val str = text.text

        PromptParser.LORA.findAll(str).forEach { match ->
            spanStyles.add(
                AnnotatedString.Range(
                    SpanStyle(color = Color(0xFFB388FF), fontWeight = FontWeight.Bold),
                    match.range.first,
                    match.range.last + 1,
                ),
            )
        }
        PromptParser.WEIGHT_PAREN.findAll(str).forEach { match ->
            spanStyles.add(AnnotatedString.Range(SpanStyle(color = Color(0xFFFFD54F)), match.range.first, match.range.last + 1))
        }
        PromptParser.WEIGHT_BRACKET.findAll(str).forEach { match ->
            spanStyles.add(AnnotatedString.Range(SpanStyle(color = Color(0xFF81C784)), match.range.first, match.range.last + 1))
        }

        return TransformedText(AnnotatedString(str, spanStyles), OffsetMapping.Identity)
    }
}

/* ============================================================================
 * SHARED UI COMPONENTS & ANIMATIONS
 * ============================================================================ */

enum class IndicatorState { IDLE, LOADING, SUCCESS, ERROR }
