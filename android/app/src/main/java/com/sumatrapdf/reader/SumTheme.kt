package com.sumatrapdf.reader

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import android.app.Activity

// SumatraPDF on Windows uses a small flat UI font, light off-white chrome,
// dark text. We don't try to fake the precise grey — Material 3's light
// scheme gets close enough when we override the container colours. The
// important part is that the UI is *neutral*, not Material-blue.

val SumBar = Color(0xFFF2F2F2)
val SumBarLine = Color(0xFFC8C8C8)
val SumSurface = Color(0xFFFFFFFF)
val SumBackground = Color(0xFFEDEDED)
val SumText = Color(0xFF1A1A1A)
val SumTextDim = Color(0xFF595959)
val SumAccent = Color(0xFF2C5AA0)
val SumStatus = Color(0xFFE0E0E0)

private val system = FontFamily.Default

val SumTypography = Typography(
    titleLarge = TextStyle(fontFamily = system, fontWeight = FontWeight.Normal, fontSize = 18.sp, lineHeight = 22.sp),
    titleMedium = TextStyle(fontFamily = system, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
    titleSmall = TextStyle(fontFamily = system, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
    bodyLarge = TextStyle(fontFamily = system, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodyMedium = TextStyle(fontFamily = system, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    bodySmall = TextStyle(fontFamily = system, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = system, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontFamily = system, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = system, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp),
)

val SumBarDark = Color(0xFF2B2B2B)
val SumBarLineDark = Color(0xFF3F3F3F)
val SumSurfaceDark = Color(0xFF1E1E1E)
val SumBackgroundDark = Color(0xFF121212)
val SumTextDark = Color(0xFFE6E6E6)
val SumTextDimDark = Color(0xFFA0A0A0)
val SumAccentDark = Color(0xFF7FA8DC)

private val SumLight = lightColorScheme(
    primary = SumAccent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFE2F3),
    onPrimaryContainer = SumText,
    secondary = SumTextDim,
    onSecondary = SumText,
    background = SumBackground,
    onBackground = SumText,
    surface = SumSurface,
    onSurface = SumText,
    surfaceVariant = SumBar,
    onSurfaceVariant = SumTextDim,
    outline = SumBarLine,
    outlineVariant = SumBarLine,
)

private val SumDark = darkColorScheme(
    primary = SumAccentDark,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF20364F),
    onPrimaryContainer = SumTextDark,
    secondary = SumTextDimDark,
    onSecondary = SumTextDark,
    background = SumBackgroundDark,
    onBackground = SumTextDark,
    surface = SumSurfaceDark,
    onSurface = SumTextDark,
    surfaceVariant = SumBarDark,
    onSurfaceVariant = SumTextDimDark,
    outline = SumBarLineDark,
    outlineVariant = SumBarLineDark,
)

@Composable
fun SumatraTheme(nightMode: Boolean = false, content: @Composable () -> Unit) {
    val colors = if (nightMode) SumDark else SumLight
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = colors.surfaceVariant.toArgb()
                window.navigationBarColor = colors.surfaceVariant.toArgb()
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !nightMode
                controller.isAppearanceLightNavigationBars = !nightMode
            }
        }
    }
    MaterialTheme(
        colorScheme = colors,
        typography = SumTypography,
        content = content,
    )
}
