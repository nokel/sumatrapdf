package com.sumatrapdf.library.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import android.app.Activity

// SumatraPDF has a fixed light identity, a fixed dark identity, and follows
// the system theme by default. The user can override in Settings.
enum class SumThemeMode { System, Light, Dark }

fun lightSumColors() = lightColorScheme(
    primary = SumAccentLight,
    onPrimary = Color.White,
    primaryContainer = SumHighlightLight,
    onPrimaryContainer = SumTextLight,
    secondary = SumTextDimLight,
    onSecondary = SumTextLight,
    background = SumBackgroundLight,
    onBackground = SumTextLight,
    surface = SumSurfaceLight,
    onSurface = SumTextLight,
    surfaceVariant = SumBarLight,
    onSurfaceVariant = SumTextDimLight,
    outline = SumBarLightLine,
    outlineVariant = SumBarLightLine,
)

fun darkSumColors() = darkColorScheme(
    primary = SumAccentDark,
    onPrimary = Color(0xFF101018),
    primaryContainer = SumHighlightDark,
    onPrimaryContainer = SumTextDark,
    secondary = SumTextDimDark,
    onSecondary = SumTextDark,
    background = SumBackgroundDark,
    onBackground = SumTextDark,
    surface = SumSurfaceDark,
    onSurface = SumTextDark,
    surfaceVariant = SumBarDark,
    onSurfaceVariant = SumTextDimDark,
    outline = SumBarDarkLine,
    outlineVariant = SumBarDarkLine,
)

@Composable
fun SumatraPDFTheme(
    mode: SumThemeMode = SumThemeMode.System,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (mode) {
        SumThemeMode.System -> systemDark
        SumThemeMode.Light -> false
        SumThemeMode.Dark -> true
    }
    val colors = if (dark) darkSumColors() else lightSumColors()

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = colors.surfaceVariant.toArgb()
                window.navigationBarColor = colors.surfaceVariant.toArgb()
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            }
        }
    }

    MaterialTheme(
        colorScheme = colors,
        typography = SumTypography,
        content = content,
    )
}
