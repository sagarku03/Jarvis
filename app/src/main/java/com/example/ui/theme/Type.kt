package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val NoFontPadding = PlatformTextStyle(includeFontPadding = false)

val JarvisDisplayFontFamily = FontFamily.SansSerif
val JarvisBodyFontFamily = FontFamily.SansSerif
val JarvisMonoFontFamily = FontFamily.Monospace

/**
 * Dedicated monospace style used ONLY for Activity Log timestamps, latency ms, and masked key display.
 */
val JarvisTimestampMonoStyle = TextStyle(
    fontFamily = JarvisMonoFontFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    letterSpacing = 0.2.sp,
    platformStyle = NoFontPadding
)

val JarvisMonoStyle = TextStyle(
    fontFamily = JarvisMonoFontFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 13.sp,
    lineHeight = 18.sp,
    letterSpacing = 0.1.sp,
    platformStyle = NoFontPadding
)

val Typography = Typography(
    // Display: 32sp / 40sp line-height, Bold, -0.5.sp
    displayLarge = TextStyle(
        fontFamily = JarvisDisplayFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.5).sp,
        platformStyle = NoFontPadding
    ),
    // Headline: 22sp / 28sp, SemiBold, -0.25.sp
    headlineMedium = TextStyle(
        fontFamily = JarvisDisplayFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.25).sp,
        platformStyle = NoFontPadding
    ),
    // Title: 18sp / 24sp, SemiBold
    titleLarge = TextStyle(
        fontFamily = JarvisDisplayFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
        platformStyle = NoFontPadding
    ),
    titleMedium = TextStyle(
        fontFamily = JarvisDisplayFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
        platformStyle = NoFontPadding
    ),
    titleSmall = TextStyle(
        fontFamily = JarvisDisplayFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
        platformStyle = NoFontPadding
    ),
    // Body: 15sp / 22sp, Regular
    bodyLarge = TextStyle(
        fontFamily = JarvisBodyFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
        platformStyle = NoFontPadding
    ),
    bodyMedium = TextStyle(
        fontFamily = JarvisBodyFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
        platformStyle = NoFontPadding
    ),
    // Caption: 12sp / 16sp, Regular, +0.2.sp
    bodySmall = TextStyle(
        fontFamily = JarvisBodyFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
        platformStyle = NoFontPadding
    ),
    // Label: 13sp / 18sp, Medium, +0.1.sp
    labelLarge = TextStyle(
        fontFamily = JarvisBodyFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp,
        platformStyle = NoFontPadding
    ),
    labelMedium = TextStyle(
        fontFamily = JarvisBodyFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp,
        platformStyle = NoFontPadding
    ),
    labelSmall = TextStyle(
        fontFamily = JarvisBodyFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
        platformStyle = NoFontPadding
    )
)
