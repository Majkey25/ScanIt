package com.majkeylab.scanit

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.system.Os
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
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
    fun fourLargeGalleryPagesPreserveSourcesAndProduceACompletePdf() =
        withScan(dimensions = JpegDimensions(4000, 3000)) { storage, first ->
            val source = first.sourcePages.single()
            val original = fileFingerprint(source)
            val started = SystemClock.elapsedRealtime()
            var orientationMillis = 0L
            val build = runBlocking {
                withContext(Dispatchers.IO) {
                    storage.cacheScannerResult(
                        List(4) { Uri.fromFile(source) },
                        googleScannerAppearanceSettings(),
                        PdfSizeTarget.Original,
                    ) { pages ->
                        assertEquals(4, pages.distinct().size)
                        val orientationStarted = SystemClock.elapsedRealtime()
                        DocumentOrientationProcessor().detect(pages).also {
                            orientationMillis = SystemClock.elapsedRealtime() - orientationStarted
                        }
                    }
                }
            }
            val imported = storage.activateProvisionalCacheEntry(build.cached)
            assertEquals(4, imported.pages.size)
            assertEquals(original, fileFingerprint(source))
            imported.pages.forEach { page ->
                assertEquals(JpegDimensions(4000, 3000), readJpegDimensions(page))
                assertEquals(original, fileFingerprint(page))
            }
            ParcelFileDescriptor.open(imported.pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { pdf -> assertEquals(4, pdf.pageCount) }
            }
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("stream", "four12mp totalMs=${SystemClock.elapsedRealtime() - started} orientationMs=$orientationMillis\n")
            })
        }

    @Test
    fun orientationAndCacheUseOneBoundedPrivateSnapshot() = withScan { storage, first ->
        val source = first.sourcePages.single()
        val original = source.readBytes()
        val snapshotFiles = mutableListOf<File>()
        try {
            val second = runBlocking {
                storage.cacheScannerResult(
                    listOf(Uri.fromFile(source)), googleScannerAppearanceSettings(), PdfSizeTarget.Original,
                ) { pages ->
                    snapshotFiles.addAll(pages)
                    assertFalse(source == pages.single())
                    assertEquals(fileFingerprint(source), fileFingerprint(pages.single()))
                    source.writeText("Changed after the snapshot")
                    listOf(ImageExifOrientation.Normal)
                }
            }.cached
            assertTrue(original.contentEquals(second.sourcePages.single().readBytes()))
            assertTrue(snapshotFiles.none(File::exists))
        } finally {
            source.writeBytes(original)
        }
    }

    @Test
    fun sameSecondScansPreserveBothCacheEntries() =
        withScan(clock = Clock.fixed(Instant.parse("2026-10-13T11:20:00Z"), ZoneOffset.UTC)) { storage, first ->
            val originals = (first.pages + first.sourcePages + first.pdf).associateWith(::fileFingerprint)
            val second = storage.activateProvisionalCacheEntry(
                storage.cacheScan(
                    first.sourcePages.map(Uri::fromFile), googleScannerAppearanceSettings(), PdfSizeTarget.Original,
                ).cached,
            )
            assertFalse(first.baseName == second.baseName)
            assertEquals(first.entryId, checkNotNull(storage.openCachedScan(first.baseName)).entryId)
            assertEquals(second.entryId, checkNotNull(storage.openCachedScan(second.baseName)).entryId)
            originals.forEach { (file, expected) -> assertEquals(expected, fileFingerprint(file)) }
        }

    @Test
    fun manualCleanupPreservesUntouchedLargePageBytesAndDimensions() =
        withScan(pageCount = 2, dimensions = JpegDimensions(3060, 4080)) { storage, parent ->
            assertEquals(JpegDimensions(3060, 4080), readJpegDimensions(parent.pages[1]))
            val originals = (parent.sourcePages + parent.pages + parent.pdf)
                .associateWith(::fileFingerprint)
            val child = storage.createManualCleanupVariant(
                parent,
                selectedPageIndex = 0,
                strokes = listOf(MarkStroke(listOf(
                    MarkPoint(0.25f, 0.25f), MarkPoint(0.75f, 0.25f),
                    MarkPoint(0.75f, 0.75f), MarkPoint(0.25f, 0.75f),
                ))),
            ).cached

            assertEquals(originals.getValue(parent.pages[1]), fileFingerprint(child.pages[1]))
            assertEquals(JpegDimensions(3060, 4080), readJpegDimensions(child.pages[1]))
            assertEquals(originals.getValue(parent.sourcePages[1]), fileFingerprint(child.sourcePages[1]))
            assertEquals(parent.appearanceSettings, child.appearanceSettings)
            assertEquals(parent.pdfSizeTarget, child.pdfSizeTarget)
            assertEquals(parent.baseName, child.parentCacheId)
            assertEquals(parent.entryId, child.parentEntryId)
            assertTrue(storage.isProvisionalCacheEntry(child))
            assertTrue(child.pdf.length() > 0L)

            val activated = storage.activateCheckpointProvisional(child)
            val reopened = checkNotNull(storage.openCachedScan(activated.baseName))
            assertEquals(originals.getValue(parent.pages[1]), fileFingerprint(reopened.pages[1]))
            assertEquals(JpegDimensions(3060, 4080), readJpegDimensions(reopened.pages[1]))
            assertFalse(storage.isProvisionalCacheEntry(reopened))
            assertEquals(parent.entryId, checkNotNull(storage.openCachedScan(parent.baseName)).entryId)
            originals.forEach { (file, expected) -> assertEquals(expected, fileFingerprint(file)) }
        }

    private fun fileFingerprint(file: File): OutputFingerprint =
        file.inputStream().use { readOutputFingerprint(it, file.length()) }

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
    fun renamedPdfAndImagesExposeSavedNamesThroughFileProvider() = withScan(pageCount = 2) { storage, cached ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val originals = (cached.sourcePages + cached.pages + cached.pdf).associateWith(::fileFingerprint)
        storage.savePdf(cached, "SeliaScanQA", null)
        storage.saveImages(cached, "SeliaScanQA")
        storage.renamePdfOutput(cached, "Smlouva & kůň #1")
        val saved = storage.renameImageOutputs(cached, "Obrázek & kůň #2").scan
        val shareRoot = File(context.cacheDir.canonicalFile, "share")
        check(shareRoot.isDirectory || shareRoot.mkdir())
        val directory = Files.createTempDirectory(shareRoot.toPath(), ".share-name-qa-").toFile()
        val shareContext = object : ContextWrapper(context) {
            override fun getCacheDir(): File = directory
        }
        fun assertSharedOutput(uri: Uri, name: String, expected: OutputFingerprint, mimeType: String) {
            checkNotNull(resolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null,
            )).use { row ->
                assertTrue(row.moveToFirst())
                assertEquals(name, row.getString(0))
                assertEquals(expected.byteLength, row.getLong(1))
                assertFalse(row.moveToNext())
            }
            assertEquals(mimeType, resolver.getType(uri))
            assertEquals(expected, checkNotNull(resolver.openInputStream(uri)).use {
                readOutputFingerprint(it, expected.byteLength)
            })
        }
        try {
            val pdf = cached.pdf.copyTo(File(directory, "cached.pdf"))
            val scan = saved.copy(cached = saved.cached.copy(pdf = pdf))
            val pdfUri = checkNotNull(pdfShareIntent(context, scan, AppSettings()).clipData?.getItemAt(0)?.uri)
            assertSharedOutput(pdfUri, checkNotNull(saved.savedPdfDisplayName), originals.getValue(cached.pdf), "application/pdf")
            val temporaryUri = checkNotNull(pdfShareIntent(
                context, scan.copy(savedPdfDisplayName = null), AppSettings(),
            ).clipData?.getItemAt(0)?.uri)
            assertSharedOutput(temporaryUri, "cached.pdf", originals.getValue(cached.pdf), "application/pdf")
            assertEquals("cached.pdf", pdf.name)

            val prepared = prepareImageShareCopies(shareContext, saved)
            try {
                val clip = checkNotNull(imageShareIntent(context, prepared, AppSettings()).clipData)
                assertEquals(saved.savedImages.size, clip.itemCount)
                saved.savedImages.forEachIndexed { index, output ->
                    assertSharedOutput(
                        checkNotNull(clip.getItemAt(index).uri), checkNotNull(output.displayName),
                        checkNotNull(outputFingerprintOrNull(output.byteLength, output.sha256)),
                        checkNotNull(output.mimeType),
                    )
                }
                assertEquals(listOf("page-01.jpg", "page-02.jpg"), prepared.files.map(File::getName))
            } finally {
                assertTrue(cleanupPreparedImageShare(prepared))
            }
            originals.forEach { (file, expected) -> assertEquals(expected, fileFingerprint(file)) }
        } finally {
            check(directory.deleteRecursively())
        }
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
        pageCount: Int = 1,
        dimensions: JpegDimensions = JpegDimensions(1200, 1600),
        clock: Clock = Clock.systemDefaultZone(),
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
        val storage = ScanStorage(context, clock)
        var cached: CachedScan? = null
        try {
            val source = File(root, "fixture.jpg")
            val bitmap = Bitmap.createBitmap(dimensions.width, dimensions.height, Bitmap.Config.ARGB_8888)
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
                    List(pageCount) { Uri.fromFile(source) },
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
