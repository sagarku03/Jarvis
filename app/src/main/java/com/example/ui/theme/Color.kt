package com.example.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Aurora Violet Glass Palette (Restored from 91b38e8):
 * - Background: #07050F / #0E0A1F
 * - Glass fill: white 8% (#14FFFFFF) / 12% (#1FFFFFFF)
 * - Glass stroke: white 14% (#24FFFFFF) / 30% (#4DFFFFFF)
 * - Violet gradient: #8B5CF6 -> #6D3AF0 -> #5B3DF5
 * - Lavender: #B388FF
 * - Electric blue: #4C8DFF
 * - Aqua: #2DE2E6
 * - Lime: #C8F53C
 * - Semantic: Success #3DDC97, Warning #FFB547, Danger #FF6B87
 * - Text: #F5F3FF / #C4BEE6 / #9A94C0
 */
object AuroraColors {
    val BackgroundDeep = Color(0xFF07050F)
    val SurfacePrimary = Color(0xFF0E0A1F)
    val SurfaceElevated = Color(0xFF16112E)

    val GlassFill = Color(0x14FFFFFF)         // White 8%
    val GlassFillStrong = Color(0x1FFFFFFF)   // White 12%
    val GlassBorder = Color(0x24FFFFFF)       // White 14%
    val GlassBorderStrong = Color(0x4DFFFFFF) // White 30%
    val GlassHighlight = Color(0x1F8B5CF6)

    val Violet500 = Color(0xFF8B5CF6)
    val Violet600 = Color(0xFF6D3AF0)
    val Violet700 = Color(0xFF5B3DF5)
    val ElectricViolet = Violet500

    val Lavender = Color(0xFFB388FF)
    val ElectricBlue = Color(0xFF4C8DFF)
    val Aqua = Color(0xFF2DE2E6)
    val Lime = Color(0xFFC8F53C)

    // Alias kept for compatibility, mapped to Aurora Aqua (#2DE2E6)
    val CyanGlow = Aqua

    val EmeraldSuccess = Color(0xFF3DDC97)
    val AmberWarning = Color(0xFFFFB547)
    val CoralDanger = Color(0xFFFF6B87)

    // Semantic aliases
    val Violet = Violet500
    val Success = EmeraldSuccess
    val Warning = AmberWarning
    val Danger = CoralDanger
    val GlassFillLight = GlassFill
    val GlassFillDark = GlassFillStrong
    val BackgroundStart = BackgroundDeep

    val TextPrimary = Color(0xFFF5F3FF)
    val TextSecondary = Color(0xFFC4BEE6)
    val TextMuted = Color(0xFF9A94C0)

    val VioletGradient = Brush.linearGradient(
        colors = listOf(Violet500, Violet600, Violet700)
    )
}

val BackgroundDeep = AuroraColors.BackgroundDeep
val SurfacePrimary = AuroraColors.SurfacePrimary
val SurfaceElevated = AuroraColors.SurfaceElevated
val ElectricViolet = AuroraColors.ElectricViolet
val Lavender = AuroraColors.Lavender
val ElectricBlue = AuroraColors.ElectricBlue
val Aqua = AuroraColors.Aqua
val Lime = AuroraColors.Lime
val EmeraldSuccess = AuroraColors.EmeraldSuccess
val AmberWarning = AuroraColors.AmberWarning
val CoralDanger = AuroraColors.CoralDanger
val TextPrimary = AuroraColors.TextPrimary
val TextSecondary = AuroraColors.TextSecondary
val TextMuted = AuroraColors.TextMuted

val Bg0 = AuroraColors.BackgroundDeep
val Bg1 = AuroraColors.SurfacePrimary
val SurfaceVariantDark = AuroraColors.SurfaceElevated
val Violet500 = AuroraColors.Violet500
val Violet600 = AuroraColors.Violet600
val Violet700 = AuroraColors.Violet700
val GlassFill = AuroraColors.GlassFill
val GlassFillStrong = AuroraColors.GlassFillStrong
val GlassStroke = AuroraColors.GlassBorder
val GlassStrokeStrong = AuroraColors.GlassBorderStrong
val Success = AuroraColors.EmeraldSuccess
val Warning = AuroraColors.AmberWarning
val Danger = AuroraColors.CoralDanger
