package com.sumatrapdf.library.ui.theme

import androidx.compose.ui.graphics.Color

// SumatraPDF on Windows is plain, neutral, and functional. Light mode is the
// identity; the app does not pop or shout. The accent is a desaturated blue,
// used sparingly for the active tab, the focused control, the TOC row.

// Light theme (Win32 identity)
val SumBarLight = Color(0xFFF2F2F2)        // toolbar / menu bar
val SumBarLightLine = Color(0xFFD9D9D9)     // 1px line under bars
val SumSurfaceLight = Color(0xFFFFFFFF)     // reader page area
val SumBackgroundLight = Color(0xFFEDEDED)  // window background
val SumTextLight = Color(0xFF1A1A1A)        // primary text
val SumTextDimLight = Color(0xFF595959)     // secondary text
val SumAccentLight = Color(0xFF2C5AA0)      // selection / link / accent
val SumHighlightLight = Color(0xFFCFE2F3)   // TOC row hover / selected

// Dark theme
val SumBarDark = Color(0xFF22222A)
val SumBarDarkLine = Color(0xFF14141A)
val SumSurfaceDark = Color(0xFF1B1B1F)
val SumBackgroundDark = Color(0xFF14141A)
val SumTextDark = Color(0xFFE6E1E5)
val SumTextDimDark = Color(0xFF9A96A0)
val SumAccentDark = Color(0xFF8AB4F8)
val SumHighlightDark = Color(0xFF33333D)
