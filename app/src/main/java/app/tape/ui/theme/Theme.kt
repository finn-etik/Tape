package app.tape.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/*
 * Paper and ink, one calm moss accent. Colour is used sparingly: the accent only
 * marks "this is where you are" (current chapter, progress), never decoration.
 */
private val Light = lightColorScheme(
    background = Color(0xFFF3F0E8),
    onBackground = Color(0xFF1B1A17),
    surface = Color(0xFFFBF9F4),
    onSurface = Color(0xFF1B1A17),
    surfaceVariant = Color(0xFFE6E1D6),
    onSurfaceVariant = Color(0xFF8C877D),
    outline = Color(0xFFCFC9BC),
    outlineVariant = Color(0xFFE1DCD1),
    primary = Color(0xFF4F6B58),
    onPrimary = Color(0xFFF3F0E8),
    surfaceContainer = Color(0xFFF8F5EE),
    surfaceContainerHigh = Color(0xFFFBF9F4),
    error = Color(0xFFA4473A),
)

private val Dark = darkColorScheme(
    background = Color(0xFF121110),
    onBackground = Color(0xFFEEEAE1),
    surface = Color(0xFF1B1A18),
    onSurface = Color(0xFFEEEAE1),
    surfaceVariant = Color(0xFF262421),
    onSurfaceVariant = Color(0xFF8F897E),
    outline = Color(0xFF3A3733),
    outlineVariant = Color(0xFF2A2825),
    primary = Color(0xFF9DB8A4),
    onPrimary = Color(0xFF121110),
    surfaceContainer = Color(0xFF1B1A18),
    surfaceContainerHigh = Color(0xFF211F1D),
    error = Color(0xFFE0907F),
)

private val Sans = FontFamily.SansSerif

private val TapeType = Typography(
    displaySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.6).sp),
    headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, letterSpacing = 0.2.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 1.2.sp),
)

@Composable
fun TapeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = TapeType,
        content = content,
    )
}
