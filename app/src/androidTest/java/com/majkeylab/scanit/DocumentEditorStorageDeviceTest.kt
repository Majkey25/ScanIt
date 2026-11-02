package com.majkeylab.scanit

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentEditorStorageDeviceTest {
    @Test
    fun importedPdfEditsKeepColorsVisiblePagesAndParentThroughActivationAndExport() = withFixture { context, root ->
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val original = File(root, "original.pdf").also(::writeTwoPagePdf)
        val originalBytes = original.readBytes()
        val storage = ScanStorage(context)
        val uri = FileProvider.getUriForFile(base, "${base.packageName}.fileprovider", original)
        val appearance = googleScannerAppearanceSettings().copy(colorMode = ScanColorMode.Grayscale, grayscaleIntensity = 100)
        val imported = storage.activateProvisionalCacheEntry(
            withImportedDocumentPages(context, listOf(uri), DocumentImportAction.Open) { pages ->
                assertEquals(2, pages.size)
                storage.cacheScan(pages, googleScannerAppearanceSettings(), PdfSizeTarget.Original).cached
            },
        )
        val parent = storage.activateCheckpointProvisional(
            storage.createAppearanceVariant(imported, appearance, PdfSizeTarget.Original).cached,
        )
        assertEquals(2, parent.pages.size)
        assertEquals(2, parent.sourcePages.size)
        val parentEntryId = checkNotNull(parent.entryId)
        val parentBytes = (imported.sourcePages + imported.pages + imported.pdf +
            parent.sourcePages + parent.pages + parent.pdf).associateWith(File::readBytes)
        val untouchedPreview = parent.pages[1].readBytes()
        withBitmap(parent.sourcePages[1]) { bitmap ->
            val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
            assertTrue("Imported immutable source lost its blue color", Color.blue(pixel) > Color.red(pixel) + 150)
        }
        withBitmap(parent.pages[1]) { assertGray(it.getPixel(it.width / 2, it.height / 2)) }
        assertFalse("Fixture must distinguish current preview from immutable source",
            untouchedPreview.contentEquals(parent.sourcePages[1].readBytes()))
        val pageBackground = withBitmap(parent.pages[0]) {
            it.getPixel((it.width * 0.6f).toInt(), (it.height * 0.58f).toInt()).also(::assertGray)
        }

        val build = storage.createAnnotatedVariant(parent, 0, annotations(), mark = null, placement = MarkPlacement())
        assertTrue("Editor revision must request parent preservation",
            File(build.cached.pdf.parentFile, PRESERVE_PARENT_CACHE_MARKER).isFile)
        // Checkpoint activation exercises the preservation marker without an explicit protected-parent override.
        val edited = storage.activateCheckpointProvisional(build.cached)
        assertNotEquals(parent.baseName, edited.baseName)
        assertTrue(isCanonicalUuid(checkNotNull(edited.entryId)))
        assertNotEquals(parentEntryId, edited.entryId)
        assertEquals(parent.baseName, edited.parentCacheId)
        assertEquals(parentEntryId, edited.parentEntryId)
        assertEquals(parent.lineageCacheId, edited.lineageCacheId)
        assertEquals(parent.pdfSizeTarget, edited.pdfSizeTarget)
        assertEquals(2, edited.pages.size)
        assertEquals(2, edited.sourcePages.size)
        assertArrayEquals(untouchedPreview, edited.pages[1].readBytes())
        assertArrayEquals(untouchedPreview, edited.sourcePages[1].readBytes())
        assertFalse(parent.pages[0].readBytes().contentEquals(edited.pages[0].readBytes()))
        withBitmap(edited.pages[0]) { assertOverlays(it, pageBackground) }

        val reopened = ScanStorage(context)
        assertEquals(parentEntryId, checkNotNull(reopened.openCachedScan(parent.baseName)).entryId)
        val saved = checkNotNull(reopened.openSavedScan(edited.baseName))
        assertEquals(edited.entryId, saved.cached.entryId)
        val view = pdfViewIntent(base, saved)
        assertEquals(Intent.ACTION_VIEW, view.action)
        assertEquals("application/pdf", view.type)
        assertTrue(view.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val exported = File(root, "edited.pdf")
        checkNotNull(base.contentResolver.openInputStream(checkNotNull(view.data))).use { input ->
            exported.outputStream().use { output -> input.copyTo(output) }
        }
        assertArrayEquals(edited.pdf.readBytes(), exported.readBytes())
        assertExportedPdf(exported, edited.pages, pageBackground)
        assertTrue(reopened.listRecentScans().any { it.cacheId == parent.baseName })
        assertEquals(parentEntryId, checkNotNull(reopened.openCachedScan(parent.baseName)).entryId)
        parentBytes.forEach { (file, bytes) -> assertArrayEquals("Parent changed: ${file.name}", bytes, file.readBytes()) }
        assertArrayEquals(originalBytes, original.readBytes())
        assertTrue(checkNotNull(providerInputStagingDirectory(context.cacheDir).list()).isEmpty())
    }

    @Test
    fun draftRoundTripsUnderTheProductionCanonicalPrivateFilesRoot() = runBlocking {
        withContext(Dispatchers.IO) {
            val base = InstrumentationRegistry.getInstrumentation().targetContext
            check(base.packageName == "com.majkeylab.scanit.internal") { "Draft tests require the .internal app" }
            val directory = File(base.filesDir.canonicalFile, ".annotation-draft-device-${UUID.randomUUID()}")
            check(directory.mkdir())
            val location = "filesDir=${base.filesDir.path}; draft=${directory.absolutePath}; canonical=${directory.canonicalPath}"
            try {
                val rawAlias = File(base.filesDir, directory.name)
                if (rawAlias.absoluteFile != rawAlias.canonicalFile) {
                    assertThrows(IOException::class.java) { AnnotationDraftStore(rawAlias).load() }
                }
                val expected = AnnotationDraft(
                    MarkEditorSource("Scan_device_draft", "f58f9a90-21b0-4d14-8ac4-1a419a1b1001", 1),
                    annotations(),
                )
                assertNull(AnnotationDraftStore(directory).load())
                AnnotationDraftStore(directory).save(expected)
                assertEquals(location, expected, AnnotationDraftStore(directory).load())
                AnnotationDraftStore(directory).clear()
                assertNull(AnnotationDraftStore(directory).load())
            } catch (failure: IOException) {
                throw AssertionError("App-private annotation draft failed: $location", failure)
            } finally {
                check(directory.canonicalFile.parentFile == base.filesDir.canonicalFile)
                assertTrue(deleteTreeWithoutFollowingLinks(directory))
            }
        }
    }

    private fun annotations(): List<PageAnnotation> = listOf(
        PageAnnotation.Ink(listOf(AnnotationPoint(0.2f, 0.2f), AnnotationPoint(0.8f, 0.2f)), Color.RED, 0.04f),
        PageAnnotation.Text("Žluťoučký kůň", 0.15f, 0.38f, 0.08f, Color.BLUE),
        PageAnnotation.Ink(
            listOf(AnnotationPoint(0.2f, 0.75f), AnnotationPoint(0.8f, 0.75f), AnnotationPoint(0.2f, 0.75f)),
            Color.YELLOW, 0.08f, highlighter = true,
        ),
    )

    private fun writeTwoPagePdf(file: File) {
        val document = PdfDocument()
        try {
            for ((index, dimensions) in listOf(180 to 216, 216 to 180).withIndex()) {
                val (width, height) = dimensions
                val page = document.startPage(PdfDocument.PageInfo.Builder(width, height, index + 1).create())
                try {
                    page.canvas.drawColor(Color.WHITE)
                    page.canvas.drawRect(width * 0.2f, height * 0.35f, width * 0.8f, height * 0.65f,
                        Paint().apply { color = if (index == 0) Color.RED else Color.BLUE })
                } finally {
                    document.finishPage(page)
                }
            }
            file.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }
    }

    private fun assertExportedPdf(file: File, pages: List<File>, pageBackground: Int) {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                assertEquals(2, renderer.pageCount)
                pages.forEachIndexed { index, preview ->
                    val size = readJpegDimensions(preview)
                    renderer.openPage(index).use { page ->
                        assertEquals(size.width.toDouble() / size.height, page.width.toDouble() / page.height, 0.02)
                        val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            assertColor(Color.WHITE, bitmap.getPixel(5, 5))
                            if (index == 0) {
                                assertOverlays(bitmap, pageBackground)
                            } else {
                                val center = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                                assertGray(center)
                                withBitmap(preview) { assertColor(it.getPixel(it.width / 2, it.height / 2), center) }
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
        }
    }

    private fun assertOverlays(bitmap: Bitmap, pageBackground: Int) {
        fun pixel(x: Float, y: Float) = bitmap.getPixel((x * bitmap.width).toInt(), (y * bitmap.height).toInt())
        assertColor(Color.RED, pixel(0.5f, 0.2f))
        assertColor(Color.rgb(255, 255, 175), pixel(0.5f, 0.75f))
        assertColor(pageBackground, pixel(0.6f, 0.58f))
        var blueTextPixels = 0
        for (y in (bitmap.height * 0.36f).toInt() until (bitmap.height * 0.6f).toInt()) {
            for (x in (bitmap.width * 0.12f).toInt() until (bitmap.width * 0.92f).toInt()) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.blue(pixel) > 140 && Color.blue(pixel) > Color.red(pixel) + 70 &&
                    Color.blue(pixel) > Color.green(pixel) + 70) blueTextPixels++
            }
        }
        assertTrue("Blue Czech text was missing or recolored", blueTextPixels > 100)
    }

    private fun assertGray(pixel: Int) {
        val channels = listOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
        assertTrue("Expected a gray current preview, got ${Integer.toHexString(pixel)}", channels.max() - channels.min() <= 12)
        assertTrue("Expected the imported page content, not blank white", channels.max() < 180)
    }

    private fun assertColor(expected: Int, actual: Int) {
        for (shift in listOf(16, 8, 0)) {
            assertEquals("RGB channel $shift", (expected ushr shift and 255).toDouble(),
                (actual ushr shift and 255).toDouble(), 24.0)
        }
    }

    private inline fun <T> withBitmap(file: File, check: (Bitmap) -> T): T {
        val bitmap = checkNotNull(BitmapFactory.decodeFile(file.path))
        return try { check(bitmap) } finally { bitmap.recycle() }
    }

    private fun withFixture(test: suspend (Context, File) -> Unit) = runBlocking {
        withContext(Dispatchers.IO) {
            val base = InstrumentationRegistry.getInstrumentation().targetContext
            check(base.packageName == "com.majkeylab.scanit.internal") { "Editor storage tests require the .internal app" }
            val share = File(base.cacheDir.canonicalFile, "share").apply { check(isDirectory || mkdir()) }
            val root = Files.createTempDirectory(share.toPath(), ".document-editor-test-").toFile()
            try {
                val cache = File(root, "cache").apply { check(mkdir()) }
                val context = object : ContextWrapper(base) {
                    override fun getApplicationContext(): Context = this
                    override fun getCacheDir(): File = cache
                }
                test(context, root)
            } finally {
                check(root.canonicalFile.parentFile == share.canonicalFile)
                assertTrue(deleteTreeWithoutFollowingLinks(root))
            }
        }
    }
}
