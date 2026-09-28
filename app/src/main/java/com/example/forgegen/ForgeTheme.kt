package com.example.forgegen

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* ============================================================================
 * THEME
 * The app's colours, type and shapes (MainActivity's MaterialTheme).
 * ============================================================================ */

fun forgeColorScheme(dark: Boolean): ColorScheme =
    if (dark) {
        // Every role is set (3.0.0): the ones left out used to fall back to Material's purple.
        darkColorScheme(
            primary = Color(0xFF3E80FF),
            onPrimary = Color.White,
            primaryContainer = Color.Black,
            onPrimaryContainer = Color.White,
            secondary = Color(0xFF8FB4FF),
            onSecondary = Color(0xFF0B1E3A),
            secondaryContainer = Color(0xFF1D2B47),
            onSecondaryContainer = Color(0xFFD6E3FF),
            tertiary = Color(0xFF6FD3F2),
            onTertiary = Color(0xFF00363F),
            tertiaryContainer = Color(0xFF142F3A),
            onTertiaryContainer = Color(0xFFB8EAFF),
            background = Color.Black,
            onBackground = Color(0xFFECECEC),
            surface = Color(0xFF151515),
            onSurface = Color(0xFFECECEC),
            surfaceVariant = Color(0xFF252525),
            onSurfaceVariant = Color(0xFFB3B3B3),
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color(0xFF111111),
            surfaceContainer = Color(0xFF151515),
            surfaceContainerHigh = Color(0xFF1C1C1C),
            surfaceContainerHighest = Color(0xFF252525),
            surfaceBright = Color(0xFF2C2C2C),
            surfaceDim = Color.Black,
            outline = Color(0xFF8A8A8A),
            outlineVariant = Color(0xFF2E2E2E),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF005BFF),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFF2F2F2),
            onPrimaryContainer = Color.Black,
            secondary = Color(0xFF3A5BA0),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFD6E3FF),
            onSecondaryContainer = Color(0xFF0B1E3A),
            tertiary = Color(0xFF00687A),
            onTertiary = Color.White,
            tertiaryContainer = Color(0xFFBDEBFA),
            onTertiaryContainer = Color(0xFF001F26),
            background = Color(0xFFF2F2F2),
            onBackground = Color(0xFF1A1A1A),
            surface = Color.White,
            onSurface = Color(0xFF1A1A1A),
            surfaceVariant = Color(0xFFE5E5E5),
            onSurfaceVariant = Color(0xFF555555),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color(0xFFFAFAFA),
            surfaceContainer = Color(0xFFF5F5F5),
            surfaceContainerHigh = Color(0xFFEDEDED),
            surfaceContainerHighest = Color(0xFFE5E5E5),
            surfaceBright = Color.White,
            surfaceDim = Color(0xFFDADADA),
            outline = Color(0xFF7A7A7A),
            outlineVariant = Color(0xFFDADADA),
        )
    }

fun forgeTypography(): Typography =
    Typography(
        bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp),
        bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp),
        bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp),
        labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, fontWeight = FontWeight.Medium),
        labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, fontWeight = FontWeight.Medium),
        labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 10.sp, fontWeight = FontWeight.Medium),
        titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 24.sp, fontWeight = FontWeight.Bold),
        titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 18.sp, fontWeight = FontWeight.Bold),
        titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, fontWeight = FontWeight.Bold),
    )

fun forgeShapes(): Shapes =
    Shapes(
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(20.dp),
        large = RoundedCornerShape(26.dp),
        extraLarge = RoundedCornerShape(32.dp),
    )
