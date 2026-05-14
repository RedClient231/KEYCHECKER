package com.redclient.keychecker.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val StripePurple = Color(0xFF635BFF)
private val LiveRed = Color(0xFFD32F2F)

private val LightColors = lightColorScheme(
    primary = StripePurple,
    onPrimary = Color.White,
    secondary = Color(0xFF00D4FF),
    error = LiveRed,
)

private val DarkColors = darkColorScheme(
    primary = StripePurple,
    onPrimary = Color.White,
    secondary = Color(0xFF00D4FF),
    error = LiveRed,
)

@Composable
fun KeyCheckerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
