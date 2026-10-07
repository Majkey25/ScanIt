package com.majkeylab.scanit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAppearanceTest {
    @Test
    fun customAppearanceRoundTripsAndInvalidStoredValuesFallBack() {
        val custom = AppAppearanceSettings(
            mode = AppAppearanceMode.Dark, lightTheme = AppThemePreset.Paper,
            darkTheme = AppThemePreset.Custom, accentRgb = 0xDA9821,
            lightBackgroundRgb = 0xFFF7DE, darkBackgroundRgb = 0x181414,
            font = AppFont.Serif, textScalePercent = 125, cornerRadiusDp = 24, pureBlackDark = true,
        )
        assertEquals(custom, decodeAppAppearance(encodeAppAppearance(custom)))
        for (invalid in listOf(null, "", "1:bad", "x".repeat(161),
            "1:dark:paper:custom:FFFFFF:FFFFFF:000000:serif:0:24:true",
            "1:dark:paper:custom:FFFFFF:FFFFFF:000000:serif:100:99:true",
            "1:dark:paper:custom:FFFFFF:FFFFFF:000000:serif:100:24:maybe")) {
            assertEquals(AppAppearanceSettings(), decodeAppAppearance(invalid))
        }
        assertEquals(0x123ABC, parseThemeColor("#123aBc"))
        for (invalid in listOf("FFF", "#GG0000", "#FFFFFFFF", " 123ABC", "123ABC\u0000")) assertNull(parseThemeColor(invalid))
    }

    @Test
    fun palettesStayReadableEvenWithMatchingCustomAccentAndBackground() {
        for (preset in AppThemePreset.entries) {
            for (dark in listOf(false, true)) {
                val colors = appThemeColors(AppAppearanceSettings(lightTheme = preset, darkTheme = preset), dark)
                assertTrue(themeContrast(readableThemeAccent(colors.accent, colors.background), colors.background) >= 4.5)
                assertTrue(themeContrast(themeForeground(colors.background), colors.background) >= 4.5)
            }
        }
        for (background in listOf(0, 0xFFFFFF, 0x777777, 0x7A7A7A, 0x225599, 0xEECCAA)) {
            assertTrue(themeContrast(readableThemeAccent(background, background), background) >= 4.5)
            val foreground = themeForeground(background)
            for (tint in listOf(0, 0xFFFFFF, 0xAA3377)) {
                val container = themeContainerColor(background, tint, 0.12)
                assertEquals(foreground, themeForeground(container))
                assertTrue(themeContrast(foreground, container) >= 4.5)
            }
        }
        assertEquals(21.0, themeContrast(0, 0xFFFFFF), 0.001)
        val background = 0xFFFFFF
        val dialog = themeContainerColor(background, themeForeground(background), 0.08)
        val container = themeContainerColor(background, 0x767676, 0.12)
        val accent = readableThemeAccent(0x767676, background, container, dialog)
        assertTrue(themeContrast(accent, dialog) >= 4.5)
        assertTrue(themeContrast(accent, container) >= 4.5)
    }

    @Test
    fun appearanceModeOverridesSystemAndPureBlackOnlyAffectsDarkPalette() {
        assertTrue(AppAppearanceMode.System.isDark(true))
        assertEquals(false, AppAppearanceMode.Light.isDark(true))
        assertTrue(AppAppearanceMode.Dark.isDark(false))
        val settings = AppAppearanceSettings(pureBlackDark = true)
        assertEquals(0, appThemeColors(settings, true).background)
        assertTrue(appThemeColors(settings, false).background != 0)
    }
}
