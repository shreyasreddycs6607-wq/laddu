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

private val Dark = darkColorScheme(
    primary = LadduOrange,
    onPrimary = Color(0xFF4A2800),
    primaryContainer = Color(0xFF6B3C00),
    onPrimaryContainer = Color(0xFFFFDCBB),
    secondary = Color(0xFFE2C1A0),
    onSecondary = Color(0xFF402C14),
    secondaryContainer = Color(0xFF59422A),
    onSecondaryContainer = Color(0xFFF3DFCB),
    tertiary = Color(0xFF5BD6C6),
    onTertiary = Color(0xFF003731),
    background = Color(0xFF17120D),
    onBackground = Color(0xFFEDE0D4),
    surface = Color(0xFF1F1812),
    onSurface = Color(0xFFEDE0D4),
    surfaceVariant = Color(0xFF3A2F26),
    onSurfaceVariant = Color(0xFFD7C3B3),
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
