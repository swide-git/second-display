package io.github.swiderekartmax.seconddisplay

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Second Display colours. The UI follows the wallpaper (Material You) on Android 12+; these
 * orange schemes are the fallback on Android 11 and the brand colours of the launcher icon
 * (`res/drawable/ic_launcher_foreground.xml`, `res/values/ic_launcher_background.xml`,
 * `docs/images/second_display_icon.svg`).
 */
object Palette {
    /** Launcher icon background and accent. */
    val IconBackground = Color(0xFF121214)
    val Accent = Color(0xFFFF9F43)

    /** Overlays drawn on top of video stay dark whatever the theme. */
    val OverlayScrim = Color(0xCC000000)
    val OverlayText = Color(0xFFF4F4F5)
    val OverlayMuted = Color(0xFFA1A1AA)

    val LightScheme: ColorScheme = lightColorScheme(
        primary = Color(0xFF904D00),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFDCC2),
        onPrimaryContainer = Color(0xFF2F1500),
        secondary = Color(0xFF745846),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFFFDCC2),
        onSecondaryContainer = Color(0xFF2B1709),
        tertiary = Color(0xFF5E6135),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFE3E6AF),
        onTertiaryContainer = Color(0xFF1B1D00),
        background = Color(0xFFFFF8F5),
        onBackground = Color(0xFF221A15),
        surface = Color(0xFFFFF8F5),
        onSurface = Color(0xFF221A15),
        surfaceVariant = Color(0xFFF4DED3),
        onSurfaceVariant = Color(0xFF52443B),
        outline = Color(0xFF85746A),
        outlineVariant = Color(0xFFD7C3B7),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFFFF1EA),
        surfaceContainer = Color(0xFFFCEBE2),
        surfaceContainerHigh = Color(0xFFF6E5DC),
        surfaceContainerHighest = Color(0xFFF0DFD7)
    )

    val DarkScheme: ColorScheme = darkColorScheme(
        primary = Color(0xFFFFB77C),
        onPrimary = Color(0xFF4E2600),
        primaryContainer = Color(0xFF6F3900),
        onPrimaryContainer = Color(0xFFFFDCC2),
        secondary = Color(0xFFE3BFA8),
        onSecondary = Color(0xFF422B1B),
        secondaryContainer = Color(0xFF5B4130),
        onSecondaryContainer = Color(0xFFFFDCC2),
        tertiary = Color(0xFFC7CA95),
        onTertiary = Color(0xFF30330B),
        tertiaryContainer = Color(0xFF464A20),
        onTertiaryContainer = Color(0xFFE3E6AF),
        background = Color(0xFF19120D),
        onBackground = Color(0xFFF0DFD6),
        surface = Color(0xFF19120D),
        onSurface = Color(0xFFF0DFD6),
        surfaceVariant = Color(0xFF52443B),
        onSurfaceVariant = Color(0xFFD7C3B7),
        outline = Color(0xFF9F8D83),
        outlineVariant = Color(0xFF52443B),
        surfaceContainerLowest = Color(0xFF130D08),
        surfaceContainerLow = Color(0xFF221A15),
        surfaceContainer = Color(0xFF261E19),
        surfaceContainerHigh = Color(0xFF312823),
        surfaceContainerHighest = Color(0xFF3C332D)
    )
}
