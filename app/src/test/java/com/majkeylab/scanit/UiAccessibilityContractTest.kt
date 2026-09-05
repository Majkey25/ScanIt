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
                appUi.section("private fun PageScopeDialog(", "private fun DocumentActionPickerRow("),
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
                "revision == settingsSaveRevision",
            ),
        )
    }

    @Test
    fun manualRedactionKeepsControlsReachable() {
        val redaction = source("app/src/main/java/com/majkeylab/scanit/ManualRedactionUi.kt")

        assertTrue(redaction.contains("LazyColumn("))
        assertTrue(redaction.contains("boundedRedactionCanvasHeightDp("))
        assertTrue(redaction.contains("if (stackActions)"))
    }

    @Test
    fun manualCleanupDiscardsShortStrokes() {
        val cleanup = source("app/src/main/java/com/majkeylab/scanit/ManualCleanupUi.kt")

        assertTrue(cleanup.section("onDragEnd = {", "onDragCancel = {")
            .contains("discardShortCleanupStroke()"))
        assertTrue(cleanup.section("onDragCancel = {", "},\n                    )")
            .contains("discardShortCleanupStroke()"))
    }

    @Test
    fun pageScopeUsesVerticalChoices() {
        val appUi = source("app/src/main/java/com/majkeylab/scanit/AppUi.kt")
        val pageScope = appUi.section("private fun PageScopeDialog(", "private fun DocumentActionPickerRow(")

        assertTrue(pageScope.contains("Column("))
        assertTrue(pageScope.split("Modifier.fillMaxWidth().heightIn(min = 48.dp)").size == 3)
        assertFalse(pageScope.contains("dismissButton = {\n            Row"))
    }

    @Test
    fun safeShareUsesCurrentPageCount() {
        val safeShare = source("app/src/main/java/com/majkeylab/scanit/SafeShareUi.kt")

        assertTrue(safeShare.contains("val selectedCount = pageRegions.count(RedactionRegion::selected)"))
        assertTrue(safeShare.contains("safeShareCanApply(previewReady, totalSelectedCount)"))
    }

    @Test
    fun safeShareHidesResizeHandleSemantics() {
        val safeShare = source("app/src/main/java/com/majkeylab/scanit/SafeShareUi.kt")

        assertTrue(safeShare.contains(".clearAndSetSemantics {}"))
    }

    @Test
    fun settingsKeepSharedFiles() {
        val appUi = source("app/src/main/java/com/majkeylab/scanit/AppUi.kt")
        val settings = appUi.section("private fun SettingsScreen(", "private fun imageSizePresetLabel(")

        assertFalse(settings.contains("R.string.delete_pdf_after_share"))
        assertFalse(settings.contains("R.string.delete_images_after_share"))
        assertTrue(settings.contains("R.string.shared_files_kept_for_safety"))
        listOf("values", "values-cs", "values-de", "values-es", "values-zh-rCN")
            .forEach { directory ->
                val strings = source("app/src/main/res/$directory/strings.xml")
                assertTrue(strings.contains("<string name=\"shared_files_kept_for_safety\">"))
            }
    }

    @Test
    fun donationNoticeIsTruthful() {
        val notices =
            mapOf(
                "values" to "Opening Buy Me a Coffee.",
                "values-cs" to "Otevírá se Buy Me a Coffee.",
                "values-de" to "Buy Me a Coffee wird geöffnet.",
                "values-es" to "Abriendo Buy Me a Coffee.",
                "values-zh-rCN" to "正在打开 Buy Me a Coffee。",
            )

        notices.forEach { (directory, notice) ->
            val strings = source("app/src/main/res/$directory/strings.xml")
            assertTrue(strings.contains("<string name=\"support_scanit_notice\">$notice</string>"))
        }
    }

    @Test
    fun germanCleanupCopyUsesInformalAddress() {
        val strings = source("app/src/main/res/values-de/strings.xml")

        assertTrue(strings.contains("Umrunde jeden Fleck, den du entfernen möchtest."))
        assertTrue(strings.contains("Bereinigung fehlgeschlagen. Wähle einen kleineren Bereich."))
    }

    private fun source(path: String): String = File(repository, path).readText()

    private fun String.section(start: String, end: String): String =
        substringAfter(start).substringBefore(end)
}
