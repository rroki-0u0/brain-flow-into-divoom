package io.rroki.brainflowintodivoom.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val BrainFlowDarkColorScheme = darkColorScheme(
    primary = BrainAccent,
    onPrimary = BrainBackground,
    secondary = BrainAccentSecondary,
    onSecondary = BrainBackground,
    tertiary = BrainAccent,
    onTertiary = BrainBackground,
    background = BrainBackground,
    onBackground = BrainOnSurface,
    surface = BrainSurface,
    onSurface = BrainOnSurface,
    surfaceVariant = BrainSurfaceVariant,
    onSurfaceVariant = BrainOnSurfaceMuted,
    error = BrainError,
    onError = BrainBackground
)

@Composable
fun BrainFlowIntoDivoomTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BrainFlowDarkColorScheme,
        content = content
    )
}
