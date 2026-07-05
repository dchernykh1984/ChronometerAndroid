package com.dchernykh.chronometer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import com.dchernykh.chronometer.data.ThemeMode

/**
 * Applies the Material 3 light/dark scheme chosen in settings. Only the built-in
 * [lightColorScheme]/[darkColorScheme] are used and every screen reads its colors
 * from `MaterialTheme.colorScheme`, so text/controls stay legible in both themes.
 */
@Composable
fun ChronometerTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val darkTheme = themeMode.resolvesToDark(isSystemInDarkTheme())
    val colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colorScheme, content = content)
}
