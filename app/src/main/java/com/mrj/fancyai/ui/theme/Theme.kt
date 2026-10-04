package com.mrj.fancyai.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp

private val FancyScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Ink,
    primaryContainer = AccentDeep,
    onPrimaryContainer = AccentSoft,
    secondary = AccentSoft,
    onSecondary = Ink,
    background = Ink,
    onBackground = TextPrimary,
    surface = Slate,
    onSurface = TextPrimary,
    surfaceVariant = SlateRaised,
    onSurfaceVariant = TextMuted,
    surfaceDim = Ink,
    surfaceBright = SlateRaised,
    surfaceContainerLowest = Ink,
    surfaceContainerLow = Slate,
    surfaceContainer = Slate,
    surfaceContainerHigh = SlateRaised,
    surfaceContainerHighest = Hairline,
    outline = Hairline,
    outlineVariant = Hairline,
    error = Danger,
    onError = Ink,
)

private val FancyShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(26.dp),
)

@Composable
fun FancyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FancyScheme,
        typography = FancyTypography,
        shapes = FancyShapes,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onBackground,
            content = content,
        )
    }
}
