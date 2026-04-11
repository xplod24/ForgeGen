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
    val buildNumber = remember {
        try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.longVersionCode.toString()
        } catch (e: Exception) {
            "Unknown"
        }
    }

    val animationProgress = remember { Animatable(0f) }
    val textMeasurer = rememberTextMeasurer()
    val primaryColor = MaterialTheme.colorScheme.primary
    val onBackgroundColor = MaterialTheme.colorScheme.onBackground

    LaunchedEffect(Unit) {
        // Płynna animacja od 0.0 do 1.0 wydłużona do 3 sekund, by zmieścić efekt bicia serca
        animationProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 3000, easing = FastOutSlowInEasing)
        )
        // Nawigacja z wyczyszczeniem stosu, by nie można było wrócić do Splash Screena
        navController.navigate("main") {
            popUpTo("welcome") { inclusive = true }
        }
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val p = animationProgress.value
        val center = Offset(size.width / 2f, size.height / 2f)

        // Fazy animacji:
        // 0.0 - 0.3: Promienie zbliżają się do środka
        // 0.3 - 0.45: Kowadło pojawia się znikąd (fade-in)
        // 0.45 - 0.85: Kowadło pulsuje (heartbeat), napis pojawia się w tym samym czasie

        val rayProgress = (p / 0.3f).coerceIn(0f, 1f)
        val rayAlpha = 1f - ((p - 0.3f) / 0.15f).coerceIn(0f, 1f)

        val anvilAlpha = ((p - 0.3f) / 0.15f).coerceIn(0f, 1f) // Czysty fade-in
        val heartbeatProgress = ((p - 0.45f) / 0.4f).coerceIn(0f, 1f)

        // Efekt bicia serca (dwie pulsujące fale) powiększające o maksymalnie 20%
        val pulse = if (heartbeatProgress > 0f && heartbeatProgress < 1f) {
            max(0f, sin(heartbeatProgress * Math.PI * 4).toFloat()) * 0.2f
        } else 0f

        // Stały rozmiar z nałożonym pulsem - gwarantuje brak efektu wyjazdu/ruchu
        val anvilScale = 1f + pulse

        // Pojawienie się tekstu "ForgeApp" i "BUILD"
        val textProgress = ((p - 0.45f) / 0.3f).coerceIn(0f, 1f)

        // 1. Faza: Pięć promieni schodzących się do środka
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
                    cap = StrokeCap.Round
                )
            }
        }

        // 2 & 3. Faza: Rysowanie kowadła (fade-in + bicie serca w miejscu)
        if (anvilAlpha > 0f) {
            val anvilPath = Path().apply {
                moveTo(-60f, -40f) // Lewy górny róg
                lineTo(40f, -40f) // Prawy górny róg (baza rogu)
                quadraticBezierTo(70f, -40f, 70f, -10f) // Czubek rogu
                quadraticBezierTo(40f, -10f, 30f, -10f) // Dół rogu
                quadraticBezierTo(15f, -10f, 15f, 20f) // Wcięcie z prawej
                lineTo(30f, 50f) // Prawa podstawa
                lineTo(-30f, 50f) // Lewa podstawa
                lineTo(-15f, 20f) // Wcięcie z lewej
                quadraticBezierTo(-15f, -10f, -60f, -10f) // Spód tylnej części
                close()
            }

            withTransform({
                translate(left = center.x, top = center.y - 20f)
                // Ustawiamy pivot dokładnie na matematyczny środek ścieżki (5f, 5f)
                // Dzięki temu skalowanie podczas bicia serca odbędzie się idealnie ze środka
                scale(scaleX = anvilScale, scaleY = anvilScale, pivot = Offset(5f, 5f))
            }) {
                drawPath(
                    path = anvilPath,
                    color = onBackgroundColor.copy(alpha = anvilAlpha)
                )
            }
        }

        // 4. Faza: Pojawienie się tekstu "ForgeApp" oraz numeru "BUILD" podczas bicia serca
        if (textProgress > 0f) {
            val titleText = "ForgeApp"
            val titleStyle = TextStyle(
                color = primaryColor.copy(alpha = textProgress),
                fontSize = 36.sp,
                fontWeight = FontWeight.ExtraBold
            )
            val titleLayoutResult = textMeasurer.measure(text = titleText, style = titleStyle)

            val buildText = "BUILD $buildNumber"
            val buildStyle = TextStyle(
                color = primaryColor.copy(alpha = textProgress),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            val buildLayoutResult = textMeasurer.measure(text = buildText, style = buildStyle)

            // Lekkie wsunięcie tekstu od dołu (zanikanie wyjazdu wraz z zanikaniem alpha)
            val textYOffset = (1f - textProgress) * 50f
            val startY = center.y + 70f + textYOffset

            drawText(
                textLayoutResult = titleLayoutResult,
                topLeft = Offset(
                    x = center.x - (titleLayoutResult.size.width / 2f),
                    y = startY
                )
            )

            drawText(
                textLayoutResult = buildLayoutResult,
                topLeft = Offset(
                    x = center.x - (buildLayoutResult.size.width / 2f),
                    y = startY + titleLayoutResult.size.height + 4f
                )
            )
        }
    }
}