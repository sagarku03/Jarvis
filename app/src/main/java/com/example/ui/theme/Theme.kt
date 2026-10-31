package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val AuroraDarkColorScheme = darkColorScheme(
    primary = Violet500,
    onPrimary = TextPrimary,
    primaryContainer = Violet600,
    onPrimaryContainer = TextPrimary,
    secondary = Lavender,
    onSecondary = Bg0,
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
    outlineVariant = GlassStrokeStrong,
    error = Danger,
    onError = Bg0
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
