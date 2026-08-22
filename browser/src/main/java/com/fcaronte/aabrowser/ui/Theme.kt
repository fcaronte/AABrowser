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

// Palette AMOLED True Black (Nero assoluto e grigi profondissimi)
private val AmoledColorScheme = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    secondary = Color(0xFFCCC2DC),
    tertiary = Color(0xFFEFB8C8),
    background = Color(0xFF060606),                 // Sfondo dashboard: un nero morbidissimo e scurissimo
    surface = Color(0xFF0E0E0E),                  // Superficie delle card
    surfaceContainer = Color(0xFF161616),         // Container interno per staccare
    surfaceVariant = Color(0xFF1F1F1F),
    onPrimary = Color(0xFF381E72),
    onSecondary = Color(0xFF332D41),
    onTertiary = Color(0xFF492532),
    onBackground = Color.White,
    onSurface = Color.White,
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF333333),                  // Bordo visibile ma elegante sul nero
    outlineVariant = Color(0xFF262626)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    secondary = Color(0xFFCCC2DC),
    tertiary = Color(0xFFEFB8C8),
    background = Color(0xFF1C1B1F),
    surface = Color(0xFF252429),
    surfaceContainer = Color(0xFF2B2A30),
    surfaceVariant = Color(0xFF36343B),
    onPrimary = Color(0xFF381E72),
    onSecondary = Color(0xFF332D41),
    onTertiary = Color(0xFF492532),
    onBackground = Color.White,
    onSurface = Color.White,
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF49454F),
    outlineVariant = Color(0xFF38353D)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF005AC1),          // Blu profondo e leggibile
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E2FF),
    onPrimaryContainer = Color(0xFF001A41),
    background = Color(0xFFF2F4F7),       // Grigio ghiaccio neutro per far staccare le card
    onBackground = Color(0xFF191C20),     // Quasi nero per massima leggibilità
    surface = Color(0xFFFFFFFF),          // Bianco pieno per le card
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurface = Color(0xFF191C20),
    onSurfaceVariant = Color(0xFF44474F),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C7C5)
)

@Composable
fun AABrowserTheme(
    forceTheme: Boolean = AppSettings.forceTheme.value,
    themeMode: ThemeMode = AppSettings.themeMode.value,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val supportsDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val systemIsDark = isSystemInDarkTheme()
    val dynamicColorEnabled = AppSettings.dynamicColor.value

    val colorScheme = if (!forceTheme) {
        // Modalità Automatica: segue il sistema
        if (supportsDynamic && dynamicColorEnabled) {
            if (systemIsDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            if (systemIsDark) DarkColorScheme else LightColorScheme
        }
    } else {
        // Modalità Manuale Forzata
        when (themeMode) {
            ThemeMode.LIGHT -> {
                if (supportsDynamic && dynamicColorEnabled) dynamicLightColorScheme(context)
                else LightColorScheme
            }
            ThemeMode.DARK -> {
                if (supportsDynamic && dynamicColorEnabled) dynamicDarkColorScheme(context)
                else DarkColorScheme
            }
            ThemeMode.AMOLED -> {
                // Forziamo in modo pulito e rigoroso la palette AMOLED True Black,
                // bypassando il dynamic color che altrimenti schiarirebbe il nero.
                AmoledColorScheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}