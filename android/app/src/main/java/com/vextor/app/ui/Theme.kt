package com.vextor.app.ui

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

private val Dark = darkColorScheme(
    primary = Color(0xFF8B9CFF),
    secondary = Color(0xFF6EE7B7),
    background = Color(0xFF0E1015),
    surface = Color(0xFF14171F),
    surfaceVariant = Color(0xFF1E222C),
)
private val Light = lightColorScheme(
    primary = Color(0xFF4F5BD5),
    secondary = Color(0xFF0F9F6E),
)

@Composable
fun VextorTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
