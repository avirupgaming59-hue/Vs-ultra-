package com.example.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

enum class ThemePreset(
    val displayName: String,
    val subtitle: String,
    val previewBg: Color,
    val previewAccent: Color,
    val isDark: Boolean,
) {
    VSCODE_DARK(
        displayName = "VS Code Dark+",
        subtitle = "Classic IDE Slate",
        previewBg = Color(0xFF1E1E1E),
        previewAccent = Color(0xFF007ACC),
        isDark = true,
    ),
    TOKYO_NIGHT(
        displayName = "Tokyo Night",
        subtitle = "Midnight Neon",
        previewBg = Color(0xFF1A1B26),
        previewAccent = Color(0xFF7AA2F7),
        isDark = true,
    ),
    DRACULA(
        displayName = "Dracula",
        subtitle = "Violet & Pink",
        previewBg = Color(0xFF282A36),
        previewAccent = Color(0xFFBD93F9),
        isDark = true,
    ),
    VSCODE_LIGHT(
        displayName = "VS Code Light",
        subtitle = "Crisp Daylight",
        previewBg = Color(0xFFFFFFFF),
        previewAccent = Color(0xFF0066B8),
        isDark = false,
    ),
    HIGH_CONTRAST(
        displayName = "High Contrast",
        subtitle = "Pure Black Clarity",
        previewBg = Color(0xFF000000),
        previewAccent = Color(0xFF6FC3DF),
        isDark = true,
    ),
    MATERIAL_DYNAMIC(
        displayName = "Material You",
        subtitle = "System Dynamic",
        previewBg = Color(0xFF21262E),
        previewAccent = Color(0xFF4EC9B0),
        isDark = true,
    ),
}

@Composable
fun VsShellTheme(
    themePreset: ThemePreset = ThemePreset.VSCODE_DARK,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()

    val colorScheme = when (themePreset) {
        ThemePreset.VSCODE_DARK -> VsDarkColorScheme
        ThemePreset.TOKYO_NIGHT -> TokyoNightColorScheme
        ThemePreset.DRACULA -> DraculaColorScheme
        ThemePreset.VSCODE_LIGHT -> VsLightColorScheme
        ThemePreset.HIGH_CONTRAST -> HighContrastDarkColorScheme
        ThemePreset.MATERIAL_DYNAMIC -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching {
                    if (systemDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                }.getOrElse {
                    if (systemDark) VsDarkColorScheme else VsLightColorScheme
                }
            } else {
                if (systemDark) VsDarkColorScheme else VsLightColorScheme
            }
        }
    }

    val useDarkStatusIcons = when (themePreset) {
        ThemePreset.VSCODE_LIGHT -> true
        ThemePreset.MATERIAL_DYNAMIC -> !systemDark
        else -> false
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            runCatching {
                val window = (view.context as? Activity)?.window
                if (window != null) {
                    WindowCompat.getInsetsController(window, view).apply {
                        isAppearanceLightStatusBars = useDarkStatusIcons
                        isAppearanceLightNavigationBars = useDarkStatusIcons
                    }
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}
