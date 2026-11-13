package com.homerelay.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF41D9E1),
    onPrimary = Color(0xFF00363B),
    primaryContainer = Color(0xFF004F57),
    onPrimaryContainer = Color(0xFF6FF5FB),
    secondary = Color(0xFFA8CDD0),
    onSecondary = Color(0xFF0E3538),
    secondaryContainer = Color(0xFF2A4B4E),
    onSecondaryContainer = Color(0xFFC4EBEE),
    tertiary = Color(0xFFC3C7EA),
    onTertiary = Color(0xFF2C2F4C),
    background = Color(0xFF0E1414),
    onBackground = Color(0xFFDEE4E3),
    surface = Color(0xFF141B1B),
    onSurface = Color(0xFFDEE4E3),
    surfaceVariant = Color(0xFF3F4949),
    onSurfaceVariant = Color(0xFFBEC8C7),
    outline = Color(0xFF889392),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00696F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9CF2F8),
    onPrimaryContainer = Color(0xFF002023),
    secondary = Color(0xFF4B6264),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCDE7EA),
    onSecondaryContainer = Color(0xFF001F22),
    tertiary = Color(0xFF545479),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF6FAFA),
    onBackground = Color(0xFF171D1D),
    surface = Color(0xFFF6FAFA),
    onSurface = Color(0xFF171D1D),
    surfaceVariant = Color(0xFFDBE5E5),
    onSurfaceVariant = Color(0xFF3F4949),
    outline = Color(0xFF6F7979),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

/** Brand theme. HomeRelay ships one deliberate identity; no dynamic-color lottery. */
@Composable
fun HomeRelayTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
