package com.laddu.app.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.shape.RoundedCornerShape

// Laddu brand: warm "laddu" orange + chocolate brown + a calm teal for "all good".
val LadduOrange = Color(0xFFE8892B)
val LadduOrangeDark = Color(0xFFB85F0A)
val LadduBrown = Color(0xFF5B3A1E)
val LadduCream = Color(0xFFFFF8F0)
val LadduTeal = Color(0xFF2A9D8F)
val StatusGreen = Color(0xFF2E9E5B)
val StatusRed = Color(0xFFD64545)
val StatusAmber = Color(0xFFE0A100)

private val Light = lightColorScheme(
    primary = LadduOrangeDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE0BF),
    onPrimaryContainer = Color(0xFF3B1F00),
    secondary = LadduBrown,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF3DFCB),
    onSecondaryContainer = Color(0xFF2B1700),
    tertiary = LadduTeal,
    onTertiary = Color.White,
    background = LadduCream,
    onBackground = Color(0xFF211A14),
    surface = Color(0xFFFFFBF8),
    onSurface = Color(0xFF211A14),
    surfaceVariant = Color(0xFFF4E4D5),
    onSurfaceVariant = Color(0xFF52443A),
    error = StatusRed,
)

// Dark-first palette: cool charcoal surfaces (so video and thumbnails stand out) with Laddu orange as the one accent.
private val Dark = darkColorScheme(
    primary = Color(0xFFF0922F),
    onPrimary = Color(0xFF3A2000),
    primaryContainer = Color(0xFF4A2F10),
    onPrimaryContainer = Color(0xFFFFDDB8),
    secondary = Color(0xFFB9C3CC),
    onSecondary = Color(0xFF1B242B),
    secondaryContainer = Color(0xFF2A333B),
    onSecondaryContainer = Color(0xFFDCE4EA),
    tertiary = Color(0xFF58D3C4),
    onTertiary = Color(0xFF00332D),
    background = Color(0xFF0E1114),
    onBackground = Color(0xFFE6EAED),
    surface = Color(0xFF14181C),
    onSurface = Color(0xFFE6EAED),
    surfaceVariant = Color(0xFF1C2228),
    onSurfaceVariant = Color(0xFF9FAAB4),
    outline = Color(0xFF3B454E),
    outlineVariant = Color(0xFF272F36),
    error = Color(0xFFFF8A80),
)

private val LadduTypography = Typography(
    headlineLarge = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
)

private val LadduShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
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
