package dev.haljk.glow.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * A warm, low-contrast palette so photographs stay the loudest thing on screen.
 *
 * Layering is carried by a tinted page plus a hairline on cards, not by elevation:
 * tonal elevation at 1–3 dp mixes in so little colour that two surfaces become
 * indistinguishable (measured 1.02:1 before this change), which is what made the
 * workbench read as one long form.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF6B4EFF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE7E1FF),
    onPrimaryContainer = Color(0xFF1B0F52),
    secondary = Color(0xFF5C5A6B),
    secondaryContainer = Color(0xFFE9E5F3),
    onSecondaryContainer = Color(0xFF201F2A),
    // Page is tinted, cards are pure white: 1.15:1 apart.
    background = Color(0xFFEDE9F7),
    onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFF2EFF9),
    onSurfaceVariant = Color(0xFF5B5968),
    // Input borders reach 3:1; card hairlines sit just under 1.8:1.
    outline = Color(0xFF8B8599),
    outlineVariant = Color(0xFFBFB8D2),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB9A8FF),
    onPrimary = Color(0xFF241B54),
    primaryContainer = Color(0xFF3D3178),
    onPrimaryContainer = Color(0xFFE7E1FF),
    secondary = Color(0xFFC9C5D6),
    secondaryContainer = Color(0xFF2E2B3A),
    onSecondaryContainer = Color(0xFFE7E3F0),
    // Near-black page, visibly lighter cards: 1.18:1 apart.
    background = Color(0xFF0E0D12),
    onBackground = Color(0xFFE6E3EC),
    surface = Color(0xFF1E1C25),
    onSurface = Color(0xFFE6E3EC),
    surfaceVariant = Color(0xFF292633),
    onSurfaceVariant = Color(0xFFB6B2C2),
    outline = Color(0xFF6E6980),
    outlineVariant = Color(0xFF4A4557),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
)

/**
 * Roles Material 3 does not expose but this product needs: a positive status
 * colour (Material has no success role) and the fixed dark chrome of the
 * full-screen viewer, which must not flip with the system theme.
 */
@Immutable
data class GlowExtras(
    val success: Color,
    val viewerSurface: Color,
    val viewerContent: Color,
    val viewerDanger: Color,
    /** Overlays drawn on top of photographs; identical in both modes. */
    val scrim: Color,
    val onScrim: Color,
)

private val LightExtras = GlowExtras(
    success = Color(0xFF2E7D32),
    viewerSurface = Color(0xFF141317),
    viewerContent = Color(0xFFFFFFFF),
    viewerDanger = Color(0xFFF2B8B5),
    scrim = Color(0xFF000000),
    onScrim = Color(0xFFFFFFFF),
)

private val DarkExtras = GlowExtras(
    success = Color(0xFF7BD88F),
    viewerSurface = Color(0xFF141317),
    viewerContent = Color(0xFFFFFFFF),
    viewerDanger = Color(0xFFF2B8B5),
    scrim = Color(0xFF000000),
    onScrim = Color(0xFFFFFFFF),
)

private val LocalGlowExtras = staticCompositionLocalOf { LightExtras }

val MaterialTheme.glow: GlowExtras
    @Composable @ReadOnlyComposable get() = LocalGlowExtras.current

/**
 * Four body sizes only: 24 / 17 / 14 / 13. Block titles are heavier than card
 * titles so the reading order is title → section → card without relying on size
 * alone, which was the defect before: a 15 sp SemiBold card title outweighed the
 * 17 sp Medium section title above it.
 */
private val GlowTypography = Typography(
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
)

@Composable
fun GlowTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val extras = if (darkTheme) DarkExtras else LightExtras
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
    CompositionLocalProvider(LocalGlowExtras provides extras) {
        MaterialTheme(colorScheme = colors, typography = GlowTypography, content = content)
    }
}

val Spacing = object {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
}

/** Widest the single-column layout is allowed to get, so landscape stays sane. */
val ContentMaxWidth = 560.dp
