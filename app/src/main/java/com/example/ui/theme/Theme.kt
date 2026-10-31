package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AuroraDarkColorScheme = darkColorScheme(
    primary = Violet500,
    onPrimary = Color.White,
    primaryContainer = Violet600,
    onPrimaryContainer = TextPrimary,
    secondary = ElectricBlue,
    onSecondary = Color.White,
    secondaryContainer = SurfaceVariantDark,
    onSecondaryContainer = TextPrimary,
    tertiary = Aqua,
    onTertiary = Bg0,
    background = Bg0,
    onBackground = TextPrimary,
    surface = Bg1,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = TextSecondary,
    outline = GlassStroke,
    error = Danger,
    onError = Color.White
)

@Composable
fun JarvisTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) AuroraDarkColorScheme else AuroraDarkColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
