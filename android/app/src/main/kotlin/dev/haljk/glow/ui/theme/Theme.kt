package dev.haljk.glow.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * A warm, low-contrast palette so photographs stay the loudest thing on screen.
 * Colours are declared once here; nothing in the app hard-codes a hex value.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF6B4EFF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE7E1FF),
    onPrimaryContainer = Color(0xFF1B0F52),
    secondary = Color(0xFF5C5A6B),
    secondaryContainer = Color(0xFFEDEAF4),
    onSecondaryContainer = Color(0xFF201F2A),
    background = Color(0xFFFBF9FE),
    onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFF0EDF6),
    onSurfaceVariant = Color(0xFF5B5968),
    outline = Color(0xFFC9C5D4),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB9A8FF),
    onPrimary = Color(0xFF241B54),
    primaryContainer = Color(0xFF3A2F73),
    onPrimaryContainer = Color(0xFFE7E1FF),
    secondary = Color(0xFFC9C5D6),
    secondaryContainer = Color(0xFF2C2A36),
    onSecondaryContainer = Color(0xFFE7E3F0),
    background = Color(0xFF121116),
    onBackground = Color(0xFFE6E3EC),
    surface = Color(0xFF1A1920),
    onSurface = Color(0xFFE6E3EC),
    surfaceVariant = Color(0xFF27252F),
    onSurfaceVariant = Color(0xFFB6B2C2),
    outline = Color(0xFF4A4756),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
)

private val GlowTypography = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
)

@Composable
fun GlowTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        LaunchedEffect(darkTheme) {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }
    }
    MaterialTheme(colorScheme = colors, typography = GlowTypography, content = content)
}

val Spacing = object {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
}
