package com.majkeylab.scanit

import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

@Composable
internal fun appColorScheme(settings: AppAppearanceSettings, dark: Boolean): ColorScheme {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(settings, dark, context, configuration) {
        val preset = if (dark) settings.darkTheme else settings.lightTheme
        if (preset == AppThemePreset.Classic) {
            val primary = if (dark) Color.White else Color.Black
            val onPrimary = if (dark) Color.Black else Color.White
            val container = Color(if (dark) 0xFF303030 else 0xFFE5E5E5)
            val base = if (dark) darkColorScheme() else lightColorScheme()
            base.copy(
                primary = primary, onPrimary = onPrimary,
                primaryContainer = container, onPrimaryContainer = primary,
                secondary = Color(if (dark) 0xFFD0D0D0 else 0xFF444444), onSecondary = onPrimary,
                secondaryContainer = container, onSecondaryContainer = primary,
                tertiary = Color(if (dark) 0xFFB0B0B0 else 0xFF666666), onTertiary = onPrimary,
                tertiaryContainer = container, onTertiaryContainer = primary, inversePrimary = onPrimary,
            ).let { if (dark && settings.pureBlackDark) it.copy(background = Color.Black, surface = Color.Black) else it }
        } else if (preset == AppThemePreset.MaterialYou && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            if (dark && settings.pureBlackDark) scheme.copy(background = Color.Black, surface = Color.Black) else scheme
        } else {
            val colors = appThemeColors(settings, dark)
            fun color(rgb: Int) = Color(0xFF000000.toInt() or rgb)
            val background = colors.background
            val foreground = themeForeground(background)
            val container = themeContainerColor(background, colors.accent, 0.12)
            val surfaceHigh = themeContainerColor(background, foreground, 0.08)
            val primary = readableThemeAccent(colors.accent, background, container, surfaceHigh)
            val error = readableThemeAccent(if (dark) 0xFFB4AB else 0xBA1A1A, background, container, surfaceHigh)
            val base = if (dark) darkColorScheme() else lightColorScheme()
            base.copy(
                primary = color(primary), onPrimary = color(themeForeground(primary)),
                primaryContainer = color(container), onPrimaryContainer = color(themeForeground(container)),
                secondary = color(primary), onSecondary = color(themeForeground(primary)),
                secondaryContainer = color(container), onSecondaryContainer = color(themeForeground(container)),
                tertiary = color(primary), onTertiary = color(themeForeground(primary)),
                tertiaryContainer = color(container), onTertiaryContainer = color(themeForeground(container)),
                background = color(background), onBackground = color(foreground),
                surface = color(background), onSurface = color(foreground),
                surfaceVariant = color(container), onSurfaceVariant = color(themeForeground(container)),
                surfaceContainerLowest = color(background), surfaceContainerLow = color(background),
                surfaceContainer = color(background), surfaceContainerHigh = color(surfaceHigh),
                surfaceContainerHighest = color(surfaceHigh), surfaceBright = color(surfaceHigh),
                surfaceDim = color(background), surfaceTint = color(primary),
                outline = color(blendThemeColors(background, foreground, 0.65)),
                outlineVariant = color(blendThemeColors(background, foreground, 0.35)),
                inverseSurface = color(foreground), inverseOnSurface = color(background),
                inversePrimary = color(readableThemeAccent(colors.accent, foreground)),
                error = color(error), onError = color(themeForeground(error)),
                errorContainer = color(themeContainerColor(background, error, 0.12)),
                onErrorContainer = color(themeForeground(themeContainerColor(background, error, 0.12))),
            )
        }
    }
}

@Composable
internal fun ScanItTheme(appearance: AppAppearanceSettings, content: @Composable () -> Unit) {
    val dark = appearance.mode.isDark(isSystemInDarkTheme())
    val scheme = appColorScheme(appearance, dark)
    val window = LocalActivity.current?.window
    val view = LocalView.current
    SideEffect {
        if (window != null && !view.isInEditMode) {
            val lightBars = themeForeground(scheme.background.toArgb() and 0xFFFFFF) == 0
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = lightBars
                isAppearanceLightNavigationBars = lightBars
            }
        }
    }
    val typography = remember(appearance.font, appearance.textScalePercent) {
        val base = Typography()
        val family = when (appearance.font) {
            AppFont.System -> FontFamily.Default
            AppFont.Serif -> FontFamily.Serif
            AppFont.Monospace -> FontFamily.Monospace
        }
        fun TextStyle.adjusted() = copy(
            fontFamily = family,
            fontSize = (fontSize.value * appearance.textScalePercent / 100f).sp,
            lineHeight = (lineHeight.value * appearance.textScalePercent / 100f).sp,
        )
        Typography(
            displayLarge = base.displayLarge.adjusted(), displayMedium = base.displayMedium.adjusted(),
            displaySmall = base.displaySmall.adjusted(), headlineLarge = base.headlineLarge.adjusted(),
            headlineMedium = base.headlineMedium.adjusted(), headlineSmall = base.headlineSmall.adjusted(),
            titleLarge = base.titleLarge.adjusted(), titleMedium = base.titleMedium.adjusted(),
            titleSmall = base.titleSmall.adjusted(), bodyLarge = base.bodyLarge.adjusted(),
            bodyMedium = base.bodyMedium.adjusted(), bodySmall = base.bodySmall.adjusted(),
            labelLarge = base.labelLarge.adjusted(), labelMedium = base.labelMedium.adjusted(),
            labelSmall = base.labelSmall.adjusted(),
        )
    }
    val radius = appearance.cornerRadiusDp.dp
    MaterialTheme(
        colorScheme = scheme, typography = typography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(radius / 4), small = RoundedCornerShape(radius / 2),
            medium = RoundedCornerShape(radius * 0.75f), large = RoundedCornerShape(radius),
            extraLarge = RoundedCornerShape(radius * 1.75f),
        ),
        content = content,
    )
}
