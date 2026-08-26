package org.libremediaconverter.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80,
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40,
)

/**
 * Material 3 theme.
 *
 * Dynamic color (Material You) needs API 31+; minSdk is 33, so it is available
 * unconditionally and no version guard is required.
 *
 * [dynamicColor] has no caller. `MainActivity` is the single call site and takes the
 * default, so the parameter is always `true`, the two dynamic branches always win, and
 * [DarkColorScheme] and [LightColorScheme] are dead: nothing in the app can opt back to the
 * brand palette. `ThemeColorSchemeTest` reaches those two branches only by passing
 * [dynamicColor] explicitly -- a test doing it, not a feature.
 *
 * That is known rather than an oversight. #68 holds the choice between adding a switch,
 * deleting the dead branches together with the template palette, and replacing that palette
 * first; it is undecided, so nothing here should be read as a promise that any of them
 * happens.
 */
@Composable
fun LibreMediaConverterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && darkTheme -> dynamicDarkColorScheme(context)
        dynamicColor -> dynamicLightColorScheme(context)
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
