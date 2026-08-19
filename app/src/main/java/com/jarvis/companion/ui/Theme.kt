package com.jarvis.companion.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// The Mac app's palette, carried over: near-black chrome, teal-to-blue
// gradient accents, the same greys for secondary text.
object JarvisColors {
    val darkBg = Color(0xFF0A0C11)
    val darkSurface = Color(0xFF12151D)
    val darkText = Color(0xFFEDEFF3)
    val darkText2 = Color(0xFF9AA3B2)
    val teal = Color(0xFF4FD1C5)
    val blue = Color(0xFF60A5FA)
    val lightBg = Color(0xFFF3F4F7)
    val lightSurface = Color(0xFFFFFFFF)
    val lightText = Color(0xFF1B2333)
    val lightText2 = Color(0xFF4D576B)
    val tealDeep = Color(0xFF0F766E)
    val blueDeep = Color(0xFF1D4ED8)
    val error = Color(0xFFF87171)
}

fun gradient(dark: Boolean): Brush = Brush.linearGradient(
    if (dark) listOf(JarvisColors.teal, JarvisColors.blue)
    else listOf(JarvisColors.tealDeep, JarvisColors.blueDeep)
)

// "mac" resolves to whatever the Mac profile chose, which may itself be
// "system"; every path bottoms out in a plain dark-or-not boolean.
@Composable
fun resolveDark(pref: String, macTheme: String): Boolean {
    val system = isSystemInDarkTheme()
    return when (pref) {
        "dark" -> true
        "light" -> false
        "mac" -> when (macTheme) {
            "dark" -> true
            "light" -> false
            else -> system
        }
        else -> system
    }
}

@Composable
fun JarvisTheme(dark: Boolean, content: @Composable () -> Unit) {
    val scheme = if (dark) darkColorScheme(
        primary = JarvisColors.teal,
        secondary = JarvisColors.blue,
        background = JarvisColors.darkBg,
        surface = JarvisColors.darkSurface,
        onBackground = JarvisColors.darkText,
        onSurface = JarvisColors.darkText,
        onSurfaceVariant = JarvisColors.darkText2,
        error = JarvisColors.error
    ) else lightColorScheme(
        primary = JarvisColors.tealDeep,
        secondary = JarvisColors.blueDeep,
        background = JarvisColors.lightBg,
        surface = JarvisColors.lightSurface,
        onBackground = JarvisColors.lightText,
        onSurface = JarvisColors.lightText,
        onSurfaceVariant = JarvisColors.lightText2,
        error = JarvisColors.error
    )
    MaterialTheme(colorScheme = scheme, content = content)
}
