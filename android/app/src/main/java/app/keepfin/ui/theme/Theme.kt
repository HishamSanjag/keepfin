package app.keepfin.ui.theme

import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * COBALT ATELIER (dev-docs/plans/MAIN-REDESIGN.md): ink graphite or porcelain canvas, slate/white surfaces that separate by
 * tone (hairlines only where rows need them), one cobalt accent for the primary action, selection and progress.
 * Artwork supplies the rest of the colour. Manrope headings, Inter text, tabular figures for sizes/speeds/times,
 * 12dp corners. Elevation only for the player bar, menus and dialogs.
 */

private val InkDark = darkColorScheme(
    // a lighter cobalt than the spec's #456FEA: text and links need 4.5:1 on the ink canvas (this is about 6:1), and dark labels on it are about 6:1 too
    primary = Color(0xFF6F8FF0),
    onPrimary = Color(0xFF0B1222),
    primaryContainer = Color(0xFF22355F),
    onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = Color(0xFFA5ADBD),
    onSecondary = Color(0xFF111318),
    secondaryContainer = Color(0xFF262C3A),
    onSecondaryContainer = Color(0xFFEEF0F5),
    tertiary = Color(0xFF8FD8B4),
    onTertiary = Color(0xFF00382A),
    tertiaryContainer = Color(0xFF16473A),
    onTertiaryContainer = Color(0xFFB6F2D5),
    background = Color(0xFF111318),
    onBackground = Color(0xFFEEF0F5),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFEEF0F5),
    surfaceVariant = Color(0xFF1F2430),
    onSurfaceVariant = Color(0xFFA5ADBD),
    surfaceTint = Color(0xFF6F8FF0),
    surfaceBright = Color(0xFF2A3040),
    surfaceDim = Color(0xFF0D0F13),
    surfaceContainerLowest = Color(0xFF0D0F13),
    surfaceContainerLow = Color(0xFF151920),
    surfaceContainer = Color(0xFF191D25),
    surfaceContainerHigh = Color(0xFF1F2430),
    surfaceContainerHighest = Color(0xFF262C3A),
    inverseSurface = Color(0xFFEEF0F5),
    inverseOnSurface = Color(0xFF151821),
    inversePrimary = Color(0xFF2855CE),
    outline = Color(0xFF5E687C),
    outlineVariant = Color(0xFF2A3040),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF5C1A1A),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color(0xFF000000),
)

private val PaperLight = lightColorScheme(
    primary = Color(0xFF2855CE),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDDE6FF),
    onPrimaryContainer = Color(0xFF0E2A73),
    secondary = Color(0xFF4A5263),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE6E9F0),
    onSecondaryContainer = Color(0xFF151821),
    tertiary = Color(0xFF1E7A4C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFCDEEDB),
    onTertiaryContainer = Color(0xFF00321B),
    background = Color(0xFFF5F6F8),
    onBackground = Color(0xFF151821),
    surface = Color(0xFFF5F6F8),
    onSurface = Color(0xFF151821),
    surfaceVariant = Color(0xFFE9ECF1),
    onSurfaceVariant = Color(0xFF4A5263),
    surfaceTint = Color(0xFF2855CE),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFDFE2E8),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFBFC),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEEF0F4),
    surfaceContainerHighest = Color(0xFFE6E9EF),
    inverseSurface = Color(0xFF151821),
    inverseOnSurface = Color(0xFFF5F6F8),
    inversePrimary = Color(0xFF8CA6FF),
    outline = Color(0xFF7C8596),
    outlineVariant = Color(0xFFE1E4EA),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    scrim = Color(0xFF000000),
)
/** Product roles Material does not name: the one strong action fill, raised panes and outcomes. */
@Immutable
data class StudioColors(
    val action: Color,
    val onAction: Color,
    val raised: Color,
    val success: Color,
    val warning: Color,
    val separator: Color,
    val dark: Boolean,
    /** Cobalt readable as text/icon on the canvas (the dark-theme fill is too dark for 4.5:1 text). */
    val accentText: Color = if (dark) Color(0xFF8CA6FF) else Color(0xFF2855CE),
)

val LocalStudioColors = staticCompositionLocalOf {
    StudioColors(Color(0xFF6F8FF0), Color(0xFF0B1222), Color(0xFF1F2430), Color(0xFF8FD8B4), Color(0xFFF2C46D), Color(0xFF2A3040), true)
}

/** True when the user disabled animations (Developer options or accessibility "Remove animations"). */
val LocalReducedMotion = staticCompositionLocalOf { false }

object Studio {
    val colors: StudioColors @Composable get() = LocalStudioColors.current
    val reducedMotion: Boolean @Composable get() = LocalReducedMotion.current
}

private val StudioShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/** Tabular figures keep sizes, speeds and ETAs from jittering while they change. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

enum class ThemeMode(val wire: String, val label: String) {
    SYSTEM("system", "System"), LIGHT("light", "Light"), DARK("dark", "Dark");
    companion object { fun of(value: String?) = entries.firstOrNull { it.wire == value } ?: SYSTEM }
}

fun supportsDynamicColor() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
fun KeepfinTheme(mode: ThemeMode = ThemeMode.SYSTEM, dynamicColor: Boolean = false, pureBlack: Boolean = false, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val dynamic = dynamicColor && supportsDynamicColor()
    val base: ColorScheme = when {
        dynamic && dark -> dynamicDarkColorScheme(context)
        dynamic -> dynamicLightColorScheme(context)
        dark -> InkDark
        else -> PaperLight
    }
    // Pure black: true black canvas for OLED screens, raised surfaces stepping up from it. Dark theme only.
    val scheme = if (pureBlack && dark) base.copy(
        background = Color.Black, surface = Color.Black, surfaceDim = Color.Black, surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF0A0A0C), surfaceContainer = Color(0xFF111114), surfaceContainerHigh = Color(0xFF18181C), surfaceContainerHighest = Color(0xFF202026),
    ) else base
    val studio = when {
        dynamic -> StudioColors(
            action = scheme.primary, onAction = scheme.onPrimary,
            raised = if (dark) scheme.surfaceContainer else scheme.surfaceContainerLowest,
            success = if (dark) Color(0xFF8FD8B4) else Color(0xFF1E7A4C),
            warning = if (dark) Color(0xFFF2C46D) else Color(0xFF8A5A00),
            separator = scheme.outlineVariant, dark = dark, accentText = scheme.primary,
        )
        dark -> StudioColors(Color(0xFF6F8FF0), Color(0xFF0B1222), if (pureBlack) Color(0xFF111114) else Color(0xFF1F2430), Color(0xFF8FD8B4), Color(0xFFF2C46D), Color(0xFF2A3040), true)
        else -> StudioColors(Color(0xFF2855CE), Color(0xFFFFFFFF), Color(0xFFFFFFFF), Color(0xFF1E7A4C), Color(0xFF8A5A00), Color(0xFFE1E4EA), false)
    }
    val reduced = remember(context) {
        try { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
        catch (_: Exception) { false }
    }
    CompositionLocalProvider(LocalStudioColors provides studio, LocalReducedMotion provides reduced) {
        MaterialTheme(colorScheme = scheme, typography = remember { studioTypography() }, shapes = StudioShapes, content = content)
    }
}
