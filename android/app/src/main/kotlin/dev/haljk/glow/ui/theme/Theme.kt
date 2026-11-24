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
    // Neutral field, purple only for selection and the primary action. The previous
    // palette tinted every surface violet, which left purple meaning nothing: it was
    // the page, the card, the border and the button all at once.
    primary = Color(0xFF5B4CF0),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE7E3FF),
    onPrimaryContainer = Color(0xFF21166B),
    secondary = Color(0xFF5D5D69),
    secondaryContainer = Color(0xFFEEEEF3),
    onSecondaryContainer = Color(0xFF1B1B21),
    background = Color(0xFFF6F6F9),
    onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFEEEEF3),
    onSurfaceVariant = Color(0xFF5D5D69),
    // Inputs sit at 3:1; card hairlines are a quiet 1.2:1 on the neutral page.
    outline = Color(0xFF9696A2),
    outlineVariant = Color(0xFFE6E6EC),
    error = Color(0xFFC4311F),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFDE6E2),
    onErrorContainer = Color(0xFF5E1408),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB8ACFF),
    onPrimary = Color(0xFF24156A),
    primaryContainer = Color(0xFF3E3592),
    onPrimaryContainer = Color(0xFFEEEAFF),
    secondary = Color(0xFFABAAB8),
    secondaryContainer = Color(0xFF2B2B34),
    onSecondaryContainer = Color(0xFFE8E7F0),
    background = Color(0xFF111115),
    onBackground = Color(0xFFE8E7F0),
    surface = Color(0xFF1E1E25),
    onSurface = Color(0xFFE8E7F0),
    surfaceVariant = Color(0xFF2B2B34),
    onSurfaceVariant = Color(0xFFABAAB8),
    outline = Color(0xFF7C7B89),
    outlineVariant = Color(0xFF2D2D36),
    error = Color(0xFFFF9C8F),
    onError = Color(0xFF4D1F1A),
    errorContainer = Color(0xFF4D1F1A),
    onErrorContainer = Color(0xFFFFDAD4),
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
    success = Color(0xFF1F8A4C),
    viewerSurface = Color(0xFF141317),
    viewerContent = Color(0xFFFFFFFF),
    viewerDanger = Color(0xFFF2B8B5),
    scrim = Color(0xFF000000),
    onScrim = Color(0xFFFFFFFF),
)

private val DarkExtras = GlowExtras(
    success = Color(0xFF6FD394),
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
