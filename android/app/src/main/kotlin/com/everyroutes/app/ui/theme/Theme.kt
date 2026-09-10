package com.everyroutes.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = GreenPrimary,
    onPrimary = GreenOnPrimary,
    primaryContainer = GreenPrimaryContainer,
    onPrimaryContainer = GreenOnPrimaryContainer,
    secondary = GreenSecondary,
    background = GreenBackground,
    surface = GreenSurface,
    error = GreenError,
)

private val DarkColors = darkColorScheme(
    primary = GreenPrimaryContainer,
    onPrimary = GreenOnPrimaryContainer,
    primaryContainer = GreenPrimary,
    onPrimaryContainer = GreenPrimaryContainer,
    secondary = GreenPrimaryContainer,
    error = Color(0xFFFFB4AB),
)

/** Every routes の Material 3 テーマ（ライト／ダーク対応）。 */
@Composable
fun EveryRoutesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
