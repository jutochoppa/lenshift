package dev.lenshift.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Lenshift's own palette. Deliberately not a clone of any Google product: a cool
 * near-black base with a mint accent, which also reads clearly over arbitrary screenshot
 * content when used with scrims.
 */
object LenshiftColors {
    val Mint = Color(0xFF5EE7C8)
    val MintDim = Color(0xFF2E8C79)
    val Ink = Color(0xFF0B1020)
    val InkElevated = Color(0xFF141A2E)
    val InkSurface = Color(0xFF1B2238)
    val Paper = Color(0xFFF7F9FC)
    val PaperElevated = Color(0xFFFFFFFF)
    val Muted = Color(0xFF8C97B2)
    val Danger = Color(0xFFFF6B6B)
    val Warning = Color(0xFFFFC46B)
}

/**
 * Palette used by the activated screen-search overlay. Neutral graphite surfaces sampled
 * to sit naturally over arbitrary screen content; Lenshift's mint stays the only accent.
 */
object AssistantCaptureColors {
    /** Backdrop around the shrunken screenshot once a selection is active. */
    val Backdrop = Color(0xFF0E0F13)
    /** Bottom sheet and the inner controls of the floating search pill. */
    val Sheet = Color(0xFF22242A)
    /** Margins beside the centred content column on wide screens. */
    val SheetGutter = Color(0xFF16171A)
    /** Search field inside the sheet. */
    val Field = Color(0xFF2C2E35)
    /** Outer shell of the floating search pill. */
    val Pill = Color(0xFF000000)
    /** Neutral veil over the live screenshot while nothing is selected. */
    val IdleDim = Color(0x5C1F2128)
    /** Soft colour wash at the top and bottom of the screen in search mode. */
    val IdleTintTop = Color(0x334C8DFF)
    val IdleTintBottom = Color(0x2E5EEAD4)
    /** Translucent round buttons floating over the live screenshot. */
    val FloatingButton = Color(0x66000000)
    val Divider = Color(0xFF3A3C42)
    val Handle = Color(0xFF8E9099)
    val Skeleton = Color(0xFF2C2E35)
    val SkeletonHighlight = Color(0xFF3A3D45)
    val Text = Color(0xFFE8EAED)
    val Muted = Color(0xFF9AA0A6)
    val Accent = Color(0xFF5EEAD4)
    val Bracket = Color(0xFFFFFFFF)
    val Error = Color(0xFFF28B82)
    /** Text-selection highlight and handles on the captured screen. */
    val TextSelection = Color(0x6B4C8DFF)
    val TextHandle = Color(0xFF4C8DFF)
    /** Floating text toolbar and the "Select text" pill. */
    val Toolbar = Color(0xFF2A2C31)

    /** Edge-glow hues, used for the activation sweep and around the selection. */
    val Glow = listOf(
        Color(0xFF5EEAD4),
        Color(0xFF4C8DFF),
        Color(0xFFB57BFF),
        Color(0xFFFF6E8A),
        Color(0xFFFFB648),
        Color(0xFF8BE36B),
        Color(0xFF5EEAD4)
    )
}

private val DarkScheme = darkColorScheme(
    primary = LenshiftColors.Mint,
    onPrimary = LenshiftColors.Ink,
    primaryContainer = LenshiftColors.MintDim,
    onPrimaryContainer = LenshiftColors.Paper,
    secondary = LenshiftColors.Mint,
    onSecondary = LenshiftColors.Ink,
    background = LenshiftColors.Ink,
    onBackground = LenshiftColors.Paper,
    surface = LenshiftColors.InkElevated,
    onSurface = LenshiftColors.Paper,
    surfaceVariant = LenshiftColors.InkSurface,
    onSurfaceVariant = LenshiftColors.Muted,
    error = LenshiftColors.Danger,
    onError = LenshiftColors.Ink
)

private val LightScheme = lightColorScheme(
    primary = LenshiftColors.MintDim,
    onPrimary = Color.White,
    primaryContainer = LenshiftColors.Mint,
    onPrimaryContainer = LenshiftColors.Ink,
    secondary = LenshiftColors.MintDim,
    onSecondary = Color.White,
    background = LenshiftColors.Paper,
    onBackground = LenshiftColors.Ink,
    surface = LenshiftColors.PaperElevated,
    onSurface = LenshiftColors.Ink,
    surfaceVariant = Color(0xFFE6EAF2),
    onSurfaceVariant = Color(0xFF4A5568),
    error = Color(0xFFB3261E),
    onError = Color.White
)

@Composable
fun LenshiftTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val scheme = if (darkTheme) DarkScheme else LightScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = scheme,
        content = content
    )
}
