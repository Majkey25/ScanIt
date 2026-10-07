package com.majkeylab.scanit

import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

internal enum class AppAppearanceMode(val wireValue: String) {
    System("system"), Light("light"), Dark("dark");

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        System -> systemDark
        Light -> false
        Dark -> true
    }
}

internal enum class AppThemePreset(val wireValue: String) {
    Classic("classic"), MaterialYou("material_you"), Forest("forest"),
    Ocean("ocean"), Amber("amber"), Lavender("lavender"), Paper("paper"), Custom("custom"),
}

internal enum class AppFont(val wireValue: String) {
    System("system"), Serif("serif"), Monospace("monospace"),
}

internal data class AppAppearanceSettings(
    val mode: AppAppearanceMode = AppAppearanceMode.System,
    val lightTheme: AppThemePreset = AppThemePreset.Classic,
    val darkTheme: AppThemePreset = AppThemePreset.Classic,
    val accentRgb: Int = 0x38684E,
    val lightBackgroundRgb: Int = 0xFAFBF8,
    val darkBackgroundRgb: Int = 0x121512,
    val font: AppFont = AppFont.System,
    val textScalePercent: Int = 100,
    val cornerRadiusDp: Int = 16,
    val pureBlackDark: Boolean = false,
) {
    init {
        require(listOf(accentRgb, lightBackgroundRgb, darkBackgroundRgb).all { it in 0..0xFFFFFF })
        require(textScalePercent in 80..150 && cornerRadiusDp in 0..32)
    }
}

internal fun formatThemeColor(rgb: Int): String = String.format(Locale.ROOT, "%06X", rgb)

internal fun parseThemeColor(value: String): Int? {
    val hex = value.removePrefix("#")
    return hex.takeIf { it.length == 6 && it.all { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' } }
        ?.toIntOrNull(16)
}

internal fun encodeAppAppearance(value: AppAppearanceSettings): String = listOf(
    "1", value.mode.wireValue, value.lightTheme.wireValue, value.darkTheme.wireValue,
    formatThemeColor(value.accentRgb), formatThemeColor(value.lightBackgroundRgb),
    formatThemeColor(value.darkBackgroundRgb), value.font.wireValue,
    value.textScalePercent.toString(), value.cornerRadiusDp.toString(), value.pureBlackDark.toString(),
).joinToString(":")

internal fun decodeAppAppearance(value: String?): AppAppearanceSettings {
    val defaults = AppAppearanceSettings()
    if (value == null || value.length > 160) return defaults
    val fields = value.split(':')
    if (fields.size != 11 || fields[0] != "1") return defaults
    return try {
        AppAppearanceSettings(
            mode = AppAppearanceMode.entries.firstOrNull { it.wireValue == fields[1] } ?: return defaults,
            lightTheme = AppThemePreset.entries.firstOrNull { it.wireValue == fields[2] } ?: return defaults,
            darkTheme = AppThemePreset.entries.firstOrNull { it.wireValue == fields[3] } ?: return defaults,
            accentRgb = parseThemeColor(fields[4]) ?: return defaults,
            lightBackgroundRgb = parseThemeColor(fields[5]) ?: return defaults,
            darkBackgroundRgb = parseThemeColor(fields[6]) ?: return defaults,
            font = AppFont.entries.firstOrNull { it.wireValue == fields[7] } ?: return defaults,
            textScalePercent = fields[8].toIntOrNull() ?: return defaults,
            cornerRadiusDp = fields[9].toIntOrNull() ?: return defaults,
            pureBlackDark = fields[10].toBooleanStrictOrNull() ?: return defaults,
        )
    } catch (_: IllegalArgumentException) {
        defaults
    }
}

internal fun themeContrast(first: Int, second: Int): Double {
    fun luminance(rgb: Int): Double = listOf(16 to 0.2126, 8 to 0.7152, 0 to 0.0722).sumOf { (shift, weight) ->
        val channel = ((rgb shr shift) and 255) / 255.0
        weight * if (channel <= 0.04045) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)
    }
    val a = luminance(first)
    val b = luminance(second)
    return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
}

internal fun themeForeground(background: Int): Int =
    if (themeContrast(0, background) >= themeContrast(0xFFFFFF, background)) 0 else 0xFFFFFF

internal fun blendThemeColors(first: Int, second: Int, amount: Double): Int {
    require(amount in 0.0..1.0)
    var result = 0
    for (shift in listOf(16, 8, 0)) {
        val a = (first shr shift) and 255
        val b = (second shr shift) and 255
        result = result or ((a + (b - a) * amount).roundToInt() shl shift)
    }
    return result
}

internal fun readableThemeAccent(accent: Int, background: Int, vararg surfaces: Int): Int {
    val foreground = themeForeground(background)
    for (step in 0..20) {
        val candidate = blendThemeColors(accent, foreground, step / 20.0)
        if (themeContrast(candidate, background) >= 4.5 && surfaces.all { themeContrast(candidate, it) >= 4.5 }) return candidate
    }
    return foreground
}

internal fun themeContainerColor(background: Int, tint: Int, amount: Double): Int {
    val foreground = themeForeground(background)
    val candidate = blendThemeColors(background, tint, amount)
    return if (themeContrast(foreground, candidate) >= 4.5 && themeForeground(candidate) == foreground) {
        candidate
    } else {
        blendThemeColors(background, foreground xor 0xFFFFFF, amount)
    }
}

internal data class AppThemeColors(val accent: Int, val background: Int)

internal fun appThemeColors(settings: AppAppearanceSettings, dark: Boolean): AppThemeColors {
    val preset = if (dark) settings.darkTheme else settings.lightTheme
    val colors = when (preset) {
        AppThemePreset.Classic -> AppThemeColors(if (dark) 0xFFFFFF else 0, if (dark) 0x121212 else 0xFFFBFE)
        AppThemePreset.MaterialYou, AppThemePreset.Forest -> AppThemeColors(0x38684E, if (dark) 0x121812 else 0xF7FBF5)
        AppThemePreset.Ocean -> AppThemeColors(0x265C95, if (dark) 0x111820 else 0xF6FAFF)
        AppThemePreset.Amber -> AppThemeColors(0x865C10, if (dark) 0x1D170E else 0xFFFAF0)
        AppThemePreset.Lavender -> AppThemeColors(0x70519C, if (dark) 0x191420 else 0xFCF7FF)
        AppThemePreset.Paper -> AppThemeColors(0x655A47, if (dark) 0x1C1915 else 0xF8F3E8)
        AppThemePreset.Custom -> AppThemeColors(settings.accentRgb, if (dark) settings.darkBackgroundRgb else settings.lightBackgroundRgb)
    }
    return if (dark && settings.pureBlackDark) colors.copy(background = 0) else colors
}
