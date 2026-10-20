package io.github.swiderekartmax.seconddisplay

import androidx.compose.ui.graphics.Color

/**
 * Second Display colours: graphite surfaces with an orange accent. Every colour in the UI
 * comes from here; the launcher icon (`res/drawable/ic_launcher_foreground.xml`,
 * `res/values/ic_launcher_background.xml`) and `docs/images/second_display_icon.svg`
 * use the same Background / Accent / AccentContainer values.
 */
object Palette {
    val Background = Color(0xFF121214)
    val BackgroundScrim = Color(0xDD121214)
    val Surface = Color(0xFF1C1C21)
    val SurfaceVariant = Color(0xFF26262C)
    val Chip = Color(0xFF17171B)
    val Border = Color(0xFF2C2C33)
    val BorderStrong = Color(0xFF3A3A43)

    val Accent = Color(0xFFFF9F43)
    val AccentTranslucent = Color(0x66FF9F43)
    val AccentFocused = Color(0xFFFFBE7A)
    /** Selected chips, "Ready" pill. */
    val AccentContainer = Color(0xFF4A3218)
    /** Darkest accent tint: tablet screen in the icon, deep backgrounds. */
    val AccentContainerDark = Color(0xFF3A2A18)

    val Text = Color(0xFFF4F4F5)
    val TextSecondary = Color(0xFFE4E4E7)
    val TextMuted = Color(0xFFA1A1AA)
    val TextFaint = Color(0xFF71717A)

    // Warnings are yellow so they stay distinct from the orange accent.
    val Warning = Color(0xFFEAB308)
    val WarningBright = Color(0xFFFDE047)
    val WarningContainer = Color(0xFF2A2410)
    val Error = Color(0xFFF87171)
    val White = Color(0xFFFFFFFF)
}
