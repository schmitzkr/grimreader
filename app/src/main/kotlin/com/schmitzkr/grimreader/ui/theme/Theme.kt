package com.schmitzkr.grimreader.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.schmitzkr.grimreader.data.ThemeMode

/**
 * The accent colours a user can pick. One accent is used for the primary
 * button, the active pill, progress bars and little else; everything
 * around it stays neutral.
 */
enum class Accent(val label: String, val color: Color) {
    VIOLET("Violet", Color(0xFF8B7CF6)),
    EMBER("Ember", Color(0xFFF08A24)),
    TEAL("Teal", Color(0xFF3FA9A2)),
    MOSS("Moss", Color(0xFF6DAA5B)),
    ROSE("Rose", Color(0xFFE0607E)),
    SLATE("Slate", Color(0xFF8A96A8));

    companion object {
        fun byName(name: String?): Accent = entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: VIOLET
    }
}

/** WCAG relative-luminance contrast ratio between two colours (1..21). */
private fun contrastRatio(a: Color, b: Color): Float {
    val l1 = a.luminance() + 0.05f
    val l2 = b.luminance() + 0.05f
    return maxOf(l1, l2) / minOf(l1, l2)
}

/**
 * Whichever of white or near-black text actually reads better against
 * [background], by contrast ratio rather than a fixed luminance cutoff --
 * every one of the six accents below 0.4 luminance still contrasts about
 * twice as well with near-black text as with white, so a flat threshold
 * picked white for all of them regardless.
 */
fun onColorFor(background: Color): Color {
    val nearBlack = Color(0xFF111111)
    return if (contrastRatio(background, Color.White) >= contrastRatio(background, nearBlack)) Color.White else nearBlack
}

/**
 * Neutral grey surfaces (or pure black on OLED), one accent, no tonal tint
 * anywhere. Selection colours are a wash of the accent over the surface.
 */
fun grimScheme(dark: Boolean, accent: Accent, oledBlack: Boolean): ColorScheme {
    val a = accent.color
    val onA = onColorFor(a)
    return if (dark) {
        val surface = if (oledBlack) Color.Black else Color(0xFF0E0E0E)
        val onSurface = Color(0xFFECECEC)
        darkColorScheme(
            primary = a,
            onPrimary = onA,
            secondary = a,
            onSecondary = onA,
            tertiary = a,
            onTertiary = onA,
            primaryContainer = a.copy(alpha = 0.32f).compositeOver(surface),
            onPrimaryContainer = onSurface,
            secondaryContainer = a.copy(alpha = 0.24f).compositeOver(surface),
            onSecondaryContainer = onSurface,
            surface = surface,
            onSurface = onSurface,
            background = surface,
            onBackground = onSurface,
            surfaceVariant = if (oledBlack) Color(0xFF0F0F0F) else Color(0xFF242424),
            onSurfaceVariant = Color(0xFFA3A3A3),
            outline = Color(0xFF3A3A3A),
            outlineVariant = if (oledBlack) Color(0xFF1C1C1C) else Color(0xFF262626),
            surfaceTint = Color.Transparent,
            surfaceDim = surface,
            surfaceBright = Color(0xFF2A2A2A),
            surfaceContainerLowest = if (oledBlack) Color.Black else Color(0xFF0A0A0A),
            surfaceContainerLow = if (oledBlack) Color.Black else Color(0xFF141414),
            surfaceContainer = if (oledBlack) Color(0xFF050505) else Color(0xFF181818),
            surfaceContainerHigh = if (oledBlack) Color(0xFF0A0A0A) else Color(0xFF1E1E1E),
            surfaceContainerHighest = if (oledBlack) Color(0xFF0F0F0F) else Color(0xFF242424),
        )
    } else {
        val surface = Color(0xFFF6F6F6)
        val onSurface = Color(0xFF1A1A1A)
        lightColorScheme(
            primary = a,
            onPrimary = onA,
            secondary = a,
            onSecondary = onA,
            tertiary = a,
            onTertiary = onA,
            primaryContainer = a.copy(alpha = 0.22f).compositeOver(surface),
            onPrimaryContainer = onSurface,
            secondaryContainer = a.copy(alpha = 0.18f).compositeOver(surface),
            onSecondaryContainer = onSurface,
            surface = surface,
            onSurface = onSurface,
            background = surface,
            onBackground = onSurface,
            surfaceVariant = Color(0xFFE0E0E0),
            onSurfaceVariant = Color(0xFF5C5C5C),
            outline = Color(0xFFBDBDBD),
            outlineVariant = Color(0xFFDADADA),
            surfaceTint = Color.Transparent,
            surfaceDim = Color(0xFFE4E4E4),
            surfaceBright = Color.White,
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color(0xFFF1F1F1),
            surfaceContainer = Color(0xFFECECEC),
            surfaceContainerHigh = Color(0xFFE6E6E6),
            surfaceContainerHighest = Color(0xFFE0E0E0),
        )
    }
}

@Composable
fun GrimReaderTheme(
    mode: ThemeMode,
    accent: Accent,
    oledBlack: Boolean,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val scheme = grimScheme(dark, accent, oledBlack)
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = GrimTypography,
        shapes = GrimShapes,
        content = content,
    )
}
