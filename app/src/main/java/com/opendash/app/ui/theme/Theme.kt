package com.opendash.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * KTM-inspired always-dark theme: signature orange (#FF6600) on near-black.
 * The whole app is dark by design (glanceable in sunlight, motorsport look),
 * so there is no light variant. The Notifications screen switches to pure
 * black via [highContrast] for maximum outdoor legibility.
 */
private val KtmColorScheme = darkColorScheme(
    primary = Ktm.Orange,
    onPrimary = Ktm.Screen,
    secondary = Ktm.Orange,
    tertiary = Ktm.Green,
    background = Ktm.Screen,
    onBackground = Ktm.White,
    surface = Ktm.Surface,
    onSurface = Ktm.TextPrimary,
    surfaceVariant = Ktm.Surface,
    onSurfaceVariant = Ktm.TextSecondary,
    primaryContainer = Ktm.Orange,
    onPrimaryContainer = Ktm.Screen,
    outline = Ktm.Border,
    error = Ktm.Danger,
    onError = Ktm.White,
    errorContainer = Color(0xFF3A1512),
    onErrorContainer = Ktm.TextPrimary,
)

private val KtmHighContrastScheme = KtmColorScheme.copy(
    background = Ktm.Black,
    surface = Ktm.Black,
    onBackground = Ktm.White,
    onSurface = Ktm.White,
)

@Composable
fun OpenDashTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    highContrast: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (highContrast) KtmHighContrastScheme else KtmColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = Color.Transparent.value.toInt()
            window.navigationBarColor = (if (highContrast) Ktm.Black else Ktm.Screen).value.toInt()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
