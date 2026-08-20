package com.majkeylab.scanit

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLanguageTest {
    @Test
    fun documentActionKeysMatchAcrossAllFiveLocales() {
        val repository = File("..").canonicalFile
        val directories = listOf("values", "values-cs", "values-de", "values-es", "values-zh-rCN")
        val keyPattern = Regex("""<string name="([^"]+)"""")
        val required =
            setOf(
                "document_action_section_read",
                "document_action_section_use",
                "document_action_section_protect",
                "document_action_section_improve",
                "document_actions_close",
                "clean_whiteboard",
                "clean_whiteboard_preparing",
                "clean_whiteboard_before",
                "clean_whiteboard_after",
                "apply_clean_whiteboard",
                "clean_whiteboard_scope_title",
                "clean_whiteboard_scope_body",
                "redact_document",
                "redact_document_scope_title",
                "redact_document_scope_body",
            )
        val keysByDirectory =
            directories.associateWith { directory ->
                keyPattern.findAll(
                    File(repository, "app/src/main/res/$directory/strings.xml").readText(),
                ).map { it.groupValues[1] }.toSet()
            }

        assertTrue(required.all(keysByDirectory.getValue("values")::contains))
        keysByDirectory.values.forEach { keys ->
            assertEquals(keysByDirectory.getValue("values"), keys)
        }
    }

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
