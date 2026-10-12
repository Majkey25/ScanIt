package com.majkeylab.scanit

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.system.Os
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageDeviceTest {
    @Test
    fun textExportRejectsSymlinkedPayload() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.majkeylab.scanit.internal")
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "text-export-qa-").toFile()
        val outside = File.createTempFile("text-export-outside-", ".txt", context.cacheDir)
        var staged: File? = null
        try {
            outside.writeText("cached text")
            withContext(Dispatchers.Main) {
                val request = DocumentActionRequest(
                    "Scan_export_qa",
                    "123e4567-e89b-12d3-a456-426614174000",
                    0,
                    DocumentAction.ExtractText,
                    1L,
                )
                val state = DocumentTextExportSavedState(SavedStateHandle(), root)
                assertTrue(state.saveLaunch(request, DocumentActionOutput.Text("cached text", false)))
                val payload = root.canonicalFile.listFiles().orEmpty().single()
                staged = payload
                assertTrue(payload.delete())
                Os.symlink(outside.absolutePath, payload.absolutePath)
                assertNull(state.pendingExport())
                assertTrue(state.clear(request))
                assertEquals("cached text", outside.readText())
            }
        } finally {
            staged?.delete()
            outside.delete()
            assertTrue(root.delete())
        }
    }

    @Test
    fun saveReopenRenameAndFormatPreserveExactOutputs() = withScan { storage, cached ->
        val pdf = storage.savePdf(cached, "SeliaScanQA", null)
        val images = storage.saveImages(cached, "SeliaScanQA")
        assertEquals(1, images.size)
        assertEquals(pdf.uri, storage.savePdf(cached, "SeliaScanQA", null).uri)
        assertEquals(images, storage.saveImages(cached, "SeliaScanQA"))

        val renamed = storage.renamePdfOutput(cached, "SeliaScanQA-renamed")
        assertEquals("SeliaScanQA-renamed.pdf", renamed.scan.savedPdfDisplayName)
        val png = storage.replaceImageOutputs(
            cached,
            ImageExportOptions(ImageExportFormat.Png, ImageSizePreset.Small),
        )
        assertEquals("image/png", png.scan.savedImages.single().mimeType)
        assertEquals(png.scan.galleryPages, storage.saveImages(cached, "SeliaScanQA"))
        val reopened = checkNotNull(storage.openSavedScan(cached.baseName))
        assertEquals("image/png", reopened.savedImages.single().mimeType)
        assertTrue(reopened.savedPdfDeleteVerified)
        assertTrue(reopened.savedImagesDeleteVerified)
    }

    @Test
    fun textRecognitionReadsSyntheticDocument() = withScan { _, cached ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val text = runBlocking {
            DocumentActionProcessor(context).extractOcr(cached.pages, OcrScript.Latin)
        }.pageTexts.joinToString("\n")
        assertTrue(text, text.contains("DOCUMENT", ignoreCase = true))
        assertTrue(text, text.contains("12345"))
    }

    @Test
    fun latinTextRecognitionPreservesCzechDiacriticsAndPageOrder() =
        withScan(lines = listOf("Příliš žluťoučký kůň", "úpěl ďábelské ódy", "Reference 24680")) { _, czech ->
            withScan { _, english ->
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val snapshot = runBlocking {
                    DocumentActionProcessor(context).extractOcr(czech.pages + english.pages, OcrScript.Latin)
                }
                assertEquals(2, snapshot.pageTexts.size)
                assertFalse(snapshot.truncated)
                val firstPage = snapshot.pageTexts[0]
                for (word in listOf("Příliš", "žluťoučký", "kůň", "úpěl", "ďábelské", "ódy", "24680")) {
                    assertTrue("Missing $word on Czech page: $firstPage", firstPage.contains(word, ignoreCase = true))
                }
                val secondPage = snapshot.pageTexts[1]
                assertTrue(secondPage, secondPage.contains("DOCUMENT", ignoreCase = true))
                assertTrue(secondPage, secondPage.contains("12345"))
                assertEquals(setOf(0, 1), snapshot.elements.map { it.page }.toSet())
            }
        }

    @Test
    fun chineseTextRecognitionReadsExplicitChineseScript() =
        withScan(lines = listOf("中文识别测试", "你好世界", "Reference 97531")) { _, cached ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val snapshot = runBlocking {
                DocumentActionProcessor(context).extractOcr(cached.pages, OcrScript.Chinese)
            }
            assertEquals(1, snapshot.pageTexts.size)
            assertFalse(snapshot.truncated)
            val text = snapshot.pageTexts.single()
            val compact = text.filterNot(Char::isWhitespace)
            assertTrue(text, compact.contains("中文识别测试"))
            assertTrue(text, compact.contains("你好世界"))
            assertTrue(text, compact.contains("97531"))
            assertEquals(setOf(0), snapshot.elements.map { it.page }.toSet())
        }

    private fun withScan(
        lines: List<String> = listOf("DOCUMENT TEST", "Reference 12345"),
        test: (ScanStorage, CachedScan) -> Unit,
    ) {
        require(lines.size in 1..8 && lines.all { it.isNotBlank() && it.length <= 64 }) {
            "Synthetic fixture text exceeds its bounds"
        }
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        check(base.packageName == "com.majkeylab.scanit.internal") {
            "Device tests may only target the isolated internal app"
        }
        val root = Files.createTempDirectory(base.cacheDir.toPath(), "device-test-").toFile()
        val context = object : ContextWrapper(base) {
            override fun getCacheDir(): File = root
        }
        val storage = ScanStorage(context)
        var cached: CachedScan? = null
        try {
            val source = File(root, "fixture.jpg")
            val bitmap = Bitmap.createBitmap(1200, 1600, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 72f
                }
                lines.forEachIndexed { index, line ->
                    require(paint.measureText(line) <= 1040f) { "Synthetic fixture line is too wide: $line" }
                    require(line.all { it.isWhitespace() || paint.hasGlyph(it.toString()) }) {
                        "Synthetic fixture font lacks a required glyph: $line"
                    }
                    canvas.drawText(line, 80f, 200f + index * 160f, paint)
                }
                FileOutputStream(source).use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
                    output.fd.sync()
                }
            } finally {
                bitmap.recycle()
            }
            cached = storage.activateProvisionalCacheEntry(
                storage.cacheScan(
                    listOf(Uri.fromFile(source)),
                    googleScannerAppearanceSettings(),
                    PdfSizeTarget.Original,
                ).cached,
            )
            test(storage, cached)
        } finally {
            try {
                cached?.let {
                    val saved = storage.openSavedScan(it.baseName)
                    if (saved?.savedPdf != null || !saved?.galleryPages.isNullOrEmpty()) {
                        val target = when {
                            saved.savedPdf == null -> RecentDeleteTarget.Images
                            saved.galleryPages.isEmpty() -> RecentDeleteTarget.Pdf
                            else -> RecentDeleteTarget.Both
                        }
                        assertEquals(
                            OutputDeleteOperationResult.Completed,
                            storage.deleteDurableOutputs(
                                OutputDeleteRequest(it.baseName, checkNotNull(it.entryId), target),
                                deleteRecentCache = true,
                            ),
                        )
                    }
                }
            } finally {
                check(root.deleteRecursively()) { "Synthetic device-test cache could not be removed" }
            }
        }
    }
}
