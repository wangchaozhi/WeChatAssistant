package com.wangchaozhi.wechatassistant.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF006C59),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF8EF4D4),
    onPrimaryContainer = Color(0xFF002019),
    secondary = Color(0xFF596400),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDEA6E),
    onSecondaryContainer = Color(0xFF1A1D00),
    tertiary = Color(0xFF8C4A18),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDBC8),
    onTertiaryContainer = Color(0xFF301400),
    error = Color(0xFFB3261E),
    background = Color(0xFFF7FAF7),
    onBackground = Color(0xFF181D1A),
    surface = Color(0xFFF7FAF7),
    onSurface = Color(0xFF181D1A),
    surfaceVariant = Color(0xFFDCE5DE),
    onSurfaceVariant = Color(0xFF414943),
    outline = Color(0xFF707971),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF72D7B9),
    onPrimary = Color(0xFF00382C),
    primaryContainer = Color(0xFF005142),
    onPrimaryContainer = Color(0xFF8EF4D4),
    secondary = Color(0xFFC1CE55),
    onSecondary = Color(0xFF2E3400),
    secondaryContainer = Color(0xFF444B00),
    onSecondaryContainer = Color(0xFFDDEA6E),
    tertiary = Color(0xFFFFB784),
    onTertiary = Color(0xFF502400),
    tertiaryContainer = Color(0xFF6F3500),
    onTertiaryContainer = Color(0xFFFFDBC8),
    background = Color(0xFF101512),
    onBackground = Color(0xFFE0E4DF),
    surface = Color(0xFF101512),
    onSurface = Color(0xFFE0E4DF),
    surfaceVariant = Color(0xFF414943),
    onSurfaceVariant = Color(0xFFC0C9C1),
    outline = Color(0xFF8A938B),
)

@Composable
fun WcaTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors: ColorScheme = if (darkTheme) Dark else Light
    MaterialTheme(
        colorScheme = colors,
        content = content,
    )
}
