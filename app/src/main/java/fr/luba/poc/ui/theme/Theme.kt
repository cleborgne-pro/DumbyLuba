package fr.luba.poc.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import fr.luba.poc.i18n.tr

/** Theme choice; [SYSTEM] is the default and follows the device. */
enum class ThemeMode(private val labelEn: String, private val labelFr: String) {
    SYSTEM("System", "Système"),
    LIGHT("Light", "Clair"),
    DARK("Dark", "Sombre");

    val label: String get() = tr(labelEn, labelFr)

    /** Effective theme, the system setting being the default reference. */
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }
}

// Like the Mammotion app: primary actions as an "ink" pill, settings and
// selections in green (sliders, switches, checkboxes), values in blue.
private fun lightScheme(colors: LubaColors) = lightColorScheme(
    primary = colors.Ink,
    onPrimary = colors.OnInk,
    primaryContainer = colors.GreenSoft,
    onPrimaryContainer = colors.GreenDeep,
    secondary = colors.GreenDeep,
    onSecondary = Color.White,
    secondaryContainer = colors.GreenSoft,
    onSecondaryContainer = colors.GreenDeep,
    tertiary = colors.Info,
    onTertiary = Color.White,
    error = colors.Danger,
    onError = Color.White,
    errorContainer = colors.DangerSoft,
    onErrorContainer = colors.DangerDeep,
    background = colors.Background,
    onBackground = colors.TextPrimary,
    surface = colors.Surface,
    onSurface = colors.TextPrimary,
    surfaceVariant = colors.SurfaceMuted,
    onSurfaceVariant = colors.TextSecondary,
    outline = colors.Outline,
    outlineVariant = colors.Outline,
)

private fun darkScheme(colors: LubaColors) = darkColorScheme(
    primary = colors.Ink,
    onPrimary = colors.OnInk,
    primaryContainer = colors.GreenSoft,
    onPrimaryContainer = colors.GreenDeep,
    secondary = colors.GreenDeep,
    onSecondary = colors.Background,
    secondaryContainer = colors.GreenSoft,
    onSecondaryContainer = colors.GreenDeep,
    tertiary = colors.Info,
    onTertiary = Color.White,
    error = colors.Danger,
    onError = Color.White,
    errorContainer = colors.DangerSoft,
    onErrorContainer = colors.DangerDeep,
    background = colors.Background,
    onBackground = colors.TextPrimary,
    surface = colors.Surface,
    onSurface = colors.TextPrimary,
    surfaceVariant = colors.SurfaceMuted,
    onSurfaceVariant = colors.TextSecondary,
    outline = colors.Outline,
    outlineVariant = colors.Outline,
)

/**
 * Single app theme, modelled on the visual identity of the Mammotion app. By default it follows the
 * device theme (light or dark); screens read the colours through [MammotionColors] and therefore
 * adapt automatically.
 */
@Composable
fun LubaTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkLubaColors else LightLubaColors
    CompositionLocalProvider(LocalLubaColors provides colors) {
        MaterialTheme(
            colorScheme = if (darkTheme) darkScheme(colors) else lightScheme(colors),
            shapes = LubaShapes,
            typography = LubaTypography,
            content = content,
        )
    }
}
