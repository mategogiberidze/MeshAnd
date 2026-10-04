package com.meshand.app.ui.theme

import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.meshand.app.data.settings.ThemeMode
import com.meshand.app.graph
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/*
 * One small theme for every MeshAnd screen: the logo's pine green, green for "OK", amber for "check this",
 * red for errors. High contrast on purpose, since the app is read outdoors. Light, dark, or the
 * phone's setting, as chosen in Settings.
 */

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F5A47),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3EBE1),
    onPrimaryContainer = Color(0xFF0B2E23),
    secondary = Color(0xFF2563EB),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE7FF),
    onSecondaryContainer = Color(0xFF0B2A6B),
    error = Color(0xFFDC2626),
    onError = Color.White,
    background = Color(0xFFF1F4F9),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFF1F4F9),
    onSurface = Color(0xFF0F172A),
    onSurfaceVariant = Color(0xFF475569),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8FAFC),
    surfaceContainer = Color(0xFFEEF2F7),
    surfaceContainerHigh = Color(0xFFE7ECF3),
    surfaceContainerHighest = Color(0xFFE1E7EF),
    outline = Color(0xFF94A3B8),
    outlineVariant = Color(0xFFD5DDE8),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8ED1B5),
    onPrimary = Color(0xFF0B2E23),
    primaryContainer = Color(0xFF1F5A47),
    onPrimaryContainer = Color(0xFFD3EBE1),
    secondary = Color(0xFF8AB4FF),
    onSecondary = Color(0xFF0B2A6B),
    secondaryContainer = Color(0xFF1E3A8A),
    onSecondaryContainer = Color(0xFFDCE7FF),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
    background = Color(0xFF0E1318),
    onBackground = Color(0xFFE6EAF0),
    surface = Color(0xFF0E1318),
    onSurface = Color(0xFFE6EAF0),
    onSurfaceVariant = Color(0xFFA9B4C2),
    surfaceContainerLowest = Color(0xFF182029),
    surfaceContainerLow = Color(0xFF151C24),
    surfaceContainer = Color(0xFF1C242E),
    surfaceContainerHigh = Color(0xFF232C37),
    surfaceContainerHighest = Color(0xFF2A3440),
    outline = Color(0xFF6B7785),
    outlineVariant = Color(0xFF2E3946),
)

/**
 * Light or dark as chosen in Settings (or following the phone). Also sets the status- and
 * navigation-bar icon colours and the window background to match.
 */
@Composable
fun MeshAndTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val mode by context.graph.settings.themeMode.collectAsState()
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (dark) DarkColors else LightColors
    val activity = context as? ComponentActivity
    LaunchedEffect(activity, dark) {
        activity ?: return@LaunchedEffect
        activity.enableEdgeToEdge(
            statusBarStyle = if (dark) {
                SystemBarStyle.dark(AndroidColor.TRANSPARENT)
            } else {
                SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
            },
            navigationBarStyle = if (dark) SystemBarStyle.dark(DARK_NAV_SCRIM) else SystemBarStyle.light(LIGHT_NAV_SCRIM, DARK_NAV_SCRIM),
        )
        activity.window.setBackgroundDrawable(ColorDrawable(colors.background.toArgb()))
    }
    MaterialTheme(colorScheme = colors, content = content)
}

// The scrims androidx uses behind 3-button navigation by default.
private val LIGHT_NAV_SCRIM = AndroidColor.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val DARK_NAV_SCRIM = AndroidColor.argb(0x80, 0x1b, 0x1b, 0x1b)

private val ColorScheme.isLight: Boolean get() = background.luminance() > 0.5f

/** "Working / fresh" (≥ 4.5:1 on cards in both themes). */
val ColorScheme.good: Color get() = if (isLight) Color(0xFF15803D) else Color(0xFF4ADE80)

/** "Check this" (≥ 4.5:1 on cards in both themes). */
val ColorScheme.warning: Color get() = if (isLight) Color(0xFFB45309) else Color(0xFFFBBF24)
