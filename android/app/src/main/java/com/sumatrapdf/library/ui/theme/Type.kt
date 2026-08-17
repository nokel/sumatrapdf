package com.sumatrapdf.library.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// SumatraPDF on Windows uses a compact UI font. On Android we lean on
// System (Roboto) with a slight size shrink, since the default 16sp body
// is too airy for a dense document viewer.
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
