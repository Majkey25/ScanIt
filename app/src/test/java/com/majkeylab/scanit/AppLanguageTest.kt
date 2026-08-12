package com.majkeylab.scanit

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {
    @Test
    fun supportedLanguageTagsResolveToExactPickerOptions() {
        assertEquals(AppLanguage.System, appLanguageForTag(null))
        assertEquals(AppLanguage.English, appLanguageForTag("en"))
        assertEquals(AppLanguage.Czech, appLanguageForTag("cs"))
        assertEquals(AppLanguage.German, appLanguageForTag("de"))
        assertEquals(AppLanguage.Spanish, appLanguageForTag("es"))
        assertEquals(AppLanguage.SimplifiedChinese, appLanguageForTag("zh-Hans"))
    }

    @Test
    fun unsupportedLanguageTagFallsBackToSystem() {
        assertEquals(AppLanguage.System, appLanguageForTag("fr"))
        assertEquals(AppLanguage.System, appLanguageForTag("zh-Hant"))
        assertEquals(AppLanguage.System, appLanguageForTag(""))
    }
}
