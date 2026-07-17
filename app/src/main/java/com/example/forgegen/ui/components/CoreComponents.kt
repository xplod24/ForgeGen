package com.example.forgegen

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.unit.sp
import com.example.forgegen.ui.theme.*
import java.util.Locale
import kotlin.math.abs

/* ============================================================================
 * STATIC REGEX PARSER & TOKENIZER (Performance Optimization & Couple Tags)
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
                tween(durationMillis = 400, easing = FastOutSlowInEasing)
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
                tween(durationMillis = 300, easing = FastOutSlowInEasing, delayMillis = 400)
            } else {
                snap()
            }
        },
        label = "tick_progress",
    ) { if (it == IndicatorState.SUCCESS) 1f else 0f }

    val crossProgress by transition.animateFloat(
        transitionSpec = {
            if (targetState == IndicatorState.ERROR) {
                tween(durationMillis = 300, easing = FastOutSlowInEasing, delayMillis = 400)
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
                    durationMillis = 500
                    delayMillis = 700
                    1f at 0
                    1.25f at 200 using FastOutSlowInEasing
                    1f at 500 using LinearOutSlowInEasing
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
        transitionSpec = { tween(durationMillis = 400) },
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

@Composable
fun SectionHeader(title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            title,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

fun parseTags(prompt: String): List<String> {
    val result = mutableListOf<String>()
    val currentTag = java.lang.StringBuilder()
    var depth = 0

    for (char in prompt) {
        when (char) {
            '(', '[', '{' -> {
                depth++
                currentTag.append(char)
            }
            ')', ']', '}' -> {
                depth = maxOf(0, depth - 1)
                currentTag.append(char)
            }
            ',' -> {
                if (depth == 0) {
                    if (currentTag.isNotBlank()) {
                        result.add(currentTag.toString().trim())
                    }
                    currentTag.clear()
                } else {
                    currentTag.append(char)
                }
            }
            else -> currentTag.append(char)
        }
    }
    if (currentTag.isNotBlank()) {
        result.add(currentTag.toString().trim())
    }
    return result
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

fun countTokens(text: String): Int {
    if (text.isBlank()) return 0
    val words = parseTags(text)
    return words.size
}

fun getTagStrength(tag: String): String {
    val trimmed = tag.trim()
    val match = PromptParser.TAG_STRENGTH.find(trimmed)
    return match?.groupValues?.getOrNull(2) ?: "1.0"
}

fun adjustTagStrength(
    tag: String,
    delta: Float,
): String {
    val trimmed = tag.trim()
    val match = PromptParser.TAG_STRENGTH.find(trimmed)

    if (match != null && match.groupValues.size >= 3) {
        val base = match.groupValues[1]
        val currentStrength = match.groupValues[2].toFloatOrNull() ?: 1.0f
        val newStrength = (currentStrength + delta).coerceIn(0.1f, 3.0f)
        if (abs(newStrength - 1.0f) < 0.05f) return base
        return "($base:${String.format(Locale.US, "%.1f", newStrength)})"
    } else {
        val newStrength = (1.0f + delta).coerceIn(0.1f, 3.0f)
        if (abs(newStrength - 1.0f) < 0.05f) return trimmed
        return "($trimmed:${String.format(Locale.US, "%.1f", newStrength)})"
    }
}

/* ============================================================================
 * SHARED UI COMPONENTS & ANIMATIONS
 * ============================================================================ */

enum class IndicatorState { IDLE, LOADING, SUCCESS, ERROR }
