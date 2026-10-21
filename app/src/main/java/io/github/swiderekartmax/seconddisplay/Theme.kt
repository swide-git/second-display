package io.github.swiderekartmax.seconddisplay

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** Material You: wallpaper colours on Android 12+, the orange brand scheme before that; light or dark with the system. */
@Composable
fun SecondDisplayTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Palette.DarkScheme
        else -> Palette.LightScheme
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
