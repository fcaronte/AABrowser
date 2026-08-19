package com.fcaronte.aabrowser.ui

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
import com.fcaronte.aabrowser.settings.AppSettings
import com.fcaronte.aabrowser.settings.ThemeMode

private val AmoledColorScheme = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    secondary = Color(0xFFCCC2DC),
    tertiary = Color(0xFFEFB8C8),
    background = Color.Black,
    surface = Color(0xFF121212),
    onPrimary = Color(0xFF381E72),
    onSecondary = Color(0xFF332D41),
    onTertiary = Color(0xFF492532),
    onBackground = Color.White,
    onSurface = Color.White,
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    secondary = Color(0xFFCCC2DC),
    tertiary = Color(0xFFEFB8C8),
    background = Color(0xFF1C1B1F),
    surface = Color(0xFF252429),
    onPrimary = Color(0xFF381E72),
    onSecondary = Color(0xFF332D41),
    onTertiary = Color(0xFF492532),
    onBackground = Color.White,
    onSurface = Color.White,
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF6750A4),
    secondary = Color(0xFF625B71),
    tertiary = Color(0xFF7D5260),
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
)

@Composable
fun AABrowserTheme(
    themeMode: ThemeMode = AppSettings.themeMode.value,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val supportsDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val systemIsDark = isSystemInDarkTheme()
    val dynamicColorEnabled = AppSettings.dynamicColor.value

    val colorScheme = when (themeMode) {
        ThemeMode.LIGHT -> {
            if (supportsDynamic && dynamicColorEnabled) {
                if (systemIsDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(
                    context
                )
            } else {
                if (systemIsDark) DarkColorScheme else LightColorScheme
            }
        }

        ThemeMode.DARK -> {
            if (supportsDynamic && dynamicColorEnabled) {
                dynamicDarkColorScheme(context)
            } else {
                DarkColorScheme
            }
        }

        ThemeMode.AMOLED -> {
            if (supportsDynamic && dynamicColorEnabled) {
                val dynamicDark = dynamicDarkColorScheme(context)
                dynamicDark.copy(
                    background = Color.Black,
                    surface = Color(0xFF121212)
                )
            } else {
                AmoledColorScheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}