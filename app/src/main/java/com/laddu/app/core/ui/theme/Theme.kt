package com.laddu.app.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Laddu palette "Teal & Coral": teal means calm / all good, coral is the warm accent for attention.
val LadduTeal = Color(0xFF2DD4BF)
val LadduTealDark = Color(0xFF0F9F8E)
val LadduCoral = Color(0xFFFF7A59)
val LadduNavy = Color(0xFF0B1220)

val StatusGreen = Color(0xFF34D399)
val StatusRed = Color(0xFFF87171)
val StatusAmber = Color(0xFFFBBF24)

/** Hazard events and other "look at this" moments use the warm accent. */
val HazardCoral = LadduCoral
val SystemGrey = Color(0xFF8EA0BD)

private val Dark = darkColorScheme(
    primary = LadduTeal,
    onPrimary = Color(0xFF042F2A),
    primaryContainer = Color(0xFF0F3F3B),
    onPrimaryContainer = Color(0xFFBFF5EE),
    secondary = LadduCoral,
    onSecondary = Color(0xFF3A0E00),
    secondaryContainer = Color(0xFF4A2418),
    onSecondaryContainer = Color(0xFFFFD9CE),
    tertiary = StatusAmber,
    onTertiary = Color(0xFF3A2A00),
    background = LadduNavy,
    onBackground = Color(0xFFE8EEF9),
    surface = Color(0xFF111A2B),
    onSurface = Color(0xFFE8EEF9),
    surfaceVariant = Color(0xFF151E30),
    onSurfaceVariant = Color(0xFF9AA8C2),
    outline = Color(0xFF3A4864),
    outlineVariant = Color(0xFF22304A),
    error = StatusRed,
    onError = Color(0xFF3B0A0A),
)

private val Light = lightColorScheme(
    primary = LadduTealDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFF5EF),
    onPrimaryContainer = Color(0xFF053B35),
    secondary = Color(0xFFE8603C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE1D8),
    onSecondaryContainer = Color(0xFF4A1A0C),
    tertiary = Color(0xFFB7791F),
    onTertiary = Color.White,
    background = Color(0xFFF4F8FB),
    onBackground = Color(0xFF0E1A2B),
    surface = Color.White,
    onSurface = Color(0xFF0E1A2B),
    surfaceVariant = Color(0xFFE6F0F4),
    onSurfaceVariant = Color(0xFF4B5B72),
    outline = Color(0xFFB0BFCF),
    outlineVariant = Color(0xFFD4DFEA),
    error = Color(0xFFD64545),
)

private val LadduTypography = Typography(
    headlineLarge = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, lineHeight = 40.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp, letterSpacing = (-0.25).sp),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
)

// Softer, rounder corners everywhere: friendlier than the default.
private val LadduShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

@Composable
fun LadduTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) Dark else Light,
        typography = LadduTypography,
        shapes = LadduShapes,
        content = content,
    )
}
