package com.openmesh.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object OpenMeshPalette {
    val Ink = Color(0xFF0B0F0E)
    val Card = Color(0xFF121A17)
    val CardElevated = Color(0xFF17211D)
    val Line = Color(0xFF25332D)
    val Mint = Color(0xFF57E3A6)
    val MintDeep = Color(0xFF0F2C22)
    val Amber = Color(0xFFF5C451)
    val AmberDeep = Color(0xFF2C2310)
    val Coral = Color(0xFFFF7A6B)
    val CoralDeep = Color(0xFF2E1512)
    val Text = Color(0xFFE8F2ED)
    val Muted = Color(0xFF91A79E)
    val Faint = Color(0xFF5F736B)
}

private val OpenMeshColorScheme = darkColorScheme(
    primary = OpenMeshPalette.Mint,
    onPrimary = Color(0xFF04140E),
    primaryContainer = OpenMeshPalette.MintDeep,
    onPrimaryContainer = OpenMeshPalette.Mint,
    secondary = OpenMeshPalette.Amber,
    onSecondary = Color(0xFF1D1606),
    background = OpenMeshPalette.Ink,
    onBackground = OpenMeshPalette.Text,
    surface = OpenMeshPalette.Card,
    onSurface = OpenMeshPalette.Text,
    surfaceVariant = OpenMeshPalette.CardElevated,
    onSurfaceVariant = OpenMeshPalette.Muted,
    outline = OpenMeshPalette.Line,
    error = OpenMeshPalette.Coral,
    onError = Color(0xFF210705),
)

@Composable
fun OpenMeshTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OpenMeshColorScheme,
        content = content,
    )
}
