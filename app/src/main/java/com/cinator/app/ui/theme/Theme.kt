package com.cinator.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val CinatorBackground = Color(0xFF09090B)
val CinatorCard = Color(0xFF18181B)
val CinatorElevatedCard = Color(0xFF222227)
val CinatorAccent = Color(0xFFE50914)
val CinatorMutedText = Color(0xFFA1A1AA)
val CinatorBorder = Color(0xFF27272A)
val CinatorPlayableGreen = Color(0xFF4ADE80)
val CinatorMetadataBlue = Color(0xFF60A5FA)
val CinatorWarningYellow = Color(0xFFFBBF24)
val CinatorErrorRed = Color(0xFFF87171)

private val DarkColorScheme = darkColorScheme(
    primary = CinatorAccent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF450A0A),
    onPrimaryContainer = Color.White,
    secondary = CinatorAccent,
    onSecondary = Color.White,
    background = CinatorBackground,
    onBackground = Color.White,
    surface = CinatorCard,
    onSurface = Color.White,
    surfaceVariant = CinatorElevatedCard,
    onSurfaceVariant = CinatorMutedText,
    outline = CinatorBorder
)

@Composable
fun CinatorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
