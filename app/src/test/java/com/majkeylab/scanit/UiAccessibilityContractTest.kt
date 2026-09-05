package com.majkeylab.scanit

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiAccessibilityContractTest {
    private val repository = File("..").canonicalFile

    @Test
    fun compactTopBarsGrowAndConstrainTitles() {
        val sections =
            listOf(
                source("app/src/main/java/com/majkeylab/scanit/AppUi.kt")
                    .section("internal fun CompactTopBar(", "private fun SettingsScreen("),
                source("app/src/main/java/com/majkeylab/scanit/ManualCleanupUi.kt")
                    .section("private fun ManualCleanupTopBar(", "private fun ManualCleanupCanvas("),
                source("app/src/main/java/com/majkeylab/scanit/VisualMarkUi.kt")
                    .section("private fun VisualMarkTopBar(", "private fun VisualMarkCreationActions("),
            )

        sections.forEach { section ->
            assertFalse(section.contains(".height(48.dp)"))
            assertTrue(section.contains(".heightIn(min = 48.dp)"))
            assertTrue(section.contains("maxLines = 1"))
            assertTrue(section.contains("overflow = TextOverflow.Ellipsis"))
            assertTrue(section.contains(".semantics { heading() }"))
        }
    }

    @Test
    fun screenTitlesAndSettingsCategoriesExposeAccessibilitySemantics() {
        val appUi = source("app/src/main/java/com/majkeylab/scanit/AppUi.kt")
        val settingsHeader =
            appUi.section("private fun SettingsCategoryHeader(", "private fun SettingsSwitch(")
        val redaction = source("app/src/main/java/com/majkeylab/scanit/ManualRedactionUi.kt")

        assertTrue(settingsHeader.contains("collapse(action ="))
        assertTrue(settingsHeader.contains("expand(action ="))
        assertTrue(redaction.contains("Modifier.semantics { heading() }"))
    }

    @Test
    fun longDialogContentIsBoundedAndScrollable() {
        val appUi = source("app/src/main/java/com/majkeylab/scanit/AppUi.kt")
        val dialogs =
            listOf(
                appUi.section("if (languageDialogOpen)", "if (customPdfSizeDialogOpen)"),
                appUi.section("private fun PdfSizeTargetDialog(", "private fun CustomPdfSizeInput("),
                appUi.section("private fun ImageSizeDialog(", "private fun ImageFormatDialog("),
                appUi.section("private fun RecentDeleteDialog(", "private fun RecentThumbnail("),
            )

        dialogs.forEach { dialog ->
            assertTrue(dialog.contains(".heightIn(max = dialogContentMaxHeight())"))
            assertTrue(dialog.contains(".verticalScroll(rememberScrollState())"))
        }
    }

    @Test
    fun failedSettingsSaveRestoresEveryPersistedDraftField() {
        val settings =
            source("app/src/main/java/com/majkeylab/scanit/AppUi.kt")
                .section("private fun SettingsScreen(", "private fun imageSizePresetLabel(")
        listOf(
            "savePdf = settings.savePdf",
            "saveImages = settings.saveImages",
            "albumName = settings.albumName",
            "multipage = settings.multipage",
            "allowGallery = settings.allowGallery",
            "emailSubject = settings.emailSubject",
            "emailBody = settings.emailBody",
            "deletePdfAfterShare = settings.deletePdfAfterShare",
            "deleteImagesAfterShare = settings.deleteImagesAfterShare",
            "pdfTreeUri = settings.pdfTreeUri",
            "pdfSizeTargetWire = settings.pdfSizeTarget.wireValue",
            "ocrScriptWire = settings.ocrScript.wireValue",
            "readAloudLanguageWire = settings.readAloudLanguage.wireValue",
        ).forEach { assignment -> assertTrue(settings.contains(assignment)) }
        assertTrue(settings.contains("if (!saved) restoreLocalSettings()"))
        assertTrue(
            settings.contains(
                "catch (_: RuntimeException) {\n                restoreLocalSettings()",
            ),
        )
    }

    private fun source(path: String): String = File(repository, path).readText()

    private fun String.section(start: String, end: String): String =
        substringAfter(start).substringBefore(end)
}
