package com.majkeylab.scanit

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FileImportDeviceTest {
    @Test
    fun mixedProviderInputsKeepPageOrderAndSourceBytesThroughCacheAndPdf() = withFixture { context, root ->
        val first = File(root, "first.png").also { writeImage(it, 160, 80, Color.BLUE) }
        val pdf = File(root, "middle.pdf").also {
            writePdf(it, listOf(180 to 120, 120 to 180), listOf(Color.RED, Color.GREEN))
        }
        val last = File(root, "last.jpg").also { writeImage(it, 90, 150, Color.MAGENTA) }
        val sources = listOf(first, pdf, last).associateWith(File::readBytes)
        val dimensions = listOf(160 to 80, 750 to 500, 500 to 750, 90 to 150)
        val colors = listOf(Color.BLUE, Color.RED, Color.GREEN, Color.MAGENTA)
        val storage = ScanStorage(context)
        val staged = mutableListOf<File>()

        val build = withImportedDocumentPages(context, sources.keys.map(::providerUri), DocumentImportAction.Open) { pages ->
            staged += assertPrivateJpegPages(context, pages)
            assertEquals(4, pages.size)
            staged.forEachIndexed { index, file ->
                withBitmap(file) { bitmap ->
                    assertEquals(dimensions[index], bitmap.width to bitmap.height)
                    assertColor(colors[index], bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
                }
            }
            storage.cacheScan(pages, googleScannerAppearanceSettings(), PdfSizeTarget.Original)
        }
        val cached = storage.activateProvisionalCacheEntry(build.cached)

        assertTrue(staged.none(File::exists))
        assertStagingEmpty(context)
        assertEquals(4, cached.pages.size)
        cached.pages.forEachIndexed { index, file ->
            withBitmap(file) { bitmap ->
                assertEquals(dimensions[index], bitmap.width to bitmap.height)
                assertColor(colors[index], bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
            }
        }
        assertPdf(cached.pdf, dimensions) { index, bitmap ->
            assertColor(colors[index], bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
        }
        sources.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
    }

    @Test
    fun transparentPngAndExifRotatedAndMirroredJpegsProduceOrientedOpaquePages() = withFixture { context, root ->
        val transparent = File(root, "alpha.png")
        val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.TRANSPARENT)
            Canvas(bitmap).drawRect(30f, 20f, 90f, 60f, Paint().apply { color = Color.argb(128, 255, 0, 0) })
            writeBitmap(bitmap, transparent, Bitmap.CompressFormat.PNG)
        } finally {
            bitmap.recycle()
        }
        val rotated = File(root, "rotated.jpg")
        val mirrored = File(root, "mirrored.jpg")
        for ((file, orientation) in listOf(
            rotated to ExifInterface.ORIENTATION_ROTATE_90,
            mirrored to ExifInterface.ORIENTATION_FLIP_HORIZONTAL,
        )) {
            val image = Bitmap.createBitmap(160, 80, Bitmap.Config.ARGB_8888)
            try {
                image.eraseColor(Color.RED)
                Canvas(image).drawRect(80f, 0f, 160f, 80f, Paint().apply { color = Color.BLUE })
                writeBitmap(image, file, Bitmap.CompressFormat.JPEG)
            } finally {
                image.recycle()
            }
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
        }
        val sources = listOf(transparent, rotated, mirrored).associateWith(File::readBytes)

        withImportedDocumentPages(context, sources.keys.map(::providerUri), DocumentImportAction.ImagesToPdf) { pages ->
            val files = assertPrivateJpegPages(context, pages)
            assertEquals(3, files.size)
            withBitmap(files[0]) { page ->
                assertEquals(120 to 80, page.width to page.height)
                assertColor(Color.WHITE, page.getPixel(6, 4))
                assertColor(Color.rgb(255, 127, 127), page.getPixel(60, 40))
            }
            withBitmap(files[1]) { page ->
                assertEquals(80 to 160, page.width to page.height)
                assertColor(Color.RED, page.getPixel(40, 40))
                assertColor(Color.BLUE, page.getPixel(40, 120))
            }
            withBitmap(files[2]) { page ->
                assertEquals(160 to 80, page.width to page.height)
                assertColor(Color.BLUE, page.getPixel(40, 40))
                assertColor(Color.RED, page.getPixel(120, 40))
            }
        }
        assertStagingEmpty(context)
        sources.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
    }

    @Test
    fun largeImagesRespectBothDecodedSizeLimits() = withFixture { context, root ->
        val wide = File(root, "wide.png").also { writeImage(it, 4_000, 1_000, Color.BLUE) }
        val square = File(root, "square.png").also { writeImage(it, 3_000, 3_000, Color.RED) }
        val sources = listOf(wide, square).associateWith(File::readBytes)
        withImportedDocumentPages(context, sources.keys.map(::providerUri), DocumentImportAction.Open) { pages ->
            val files = assertPrivateJpegPages(context, pages)
            assertEquals(2, files.size)
            withBitmap(files[0]) { page ->
                assertEquals(3_508 to 877, page.width to page.height)
                assertColor(Color.BLUE, page.getPixel(page.width / 2, page.height / 2))
            }
            withBitmap(files[1]) { page ->
                assertEquals(2_828 to 2_828, page.width to page.height)
                assertColor(Color.RED, page.getPixel(page.width / 2, page.height / 2))
            }
        }
        assertStagingEmpty(context)
        sources.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
    }

    @Test
    fun pdfModeAndCombinedPageLimitUseDecodedPagesRatherThanInputCount() = withFixture { context, root ->
        val image = File(root, "page.png").also { writeImage(it, 80, 100, Color.BLUE) }
        val pdf = File(root, "pdf-with-image-suffix.png").also {
            writePdf(it, listOf(60 to 80, 80 to 60), listOf(Color.RED, Color.GREEN))
        }
        val originalImage = image.readBytes()
        val originalPdf = pdf.readBytes()
        withImportedDocumentPages(context, listOf(providerUri(pdf)), DocumentImportAction.PdfToImages) { pages ->
            assertEquals(2, assertPrivateJpegPages(context, pages).size)
        }
        val eighteenImages = List(18) { providerUri(image) }
        withImportedDocumentPages(context, eighteenImages + providerUri(pdf), DocumentImportAction.Open) { pages ->
            assertEquals(20, assertPrivateJpegPages(context, pages).size)
        }
        assertArrayEquals(originalPdf, pdf.readBytes())
        writePdf(pdf, List(3) { 60 to 80 }, List(3) { Color.RED })
        val threePagePdf = pdf.readBytes()

        assertRejected(context, eighteenImages + providerUri(pdf), DocumentImportAction.Open,
            DocumentImportFailureReason.TooManyPages)

        assertArrayEquals(originalImage, image.readBytes())
        assertArrayEquals(threePagePdf, pdf.readBytes())
        assertStagingEmpty(context)
    }

    @Test
    fun importedPdfCanBeCachedSignedAndExportedWithoutChangingItsSources() = withFixture { context, root ->
        val source = File(root, "sign.pdf").also {
            writePdf(it, listOf(120 to 144), listOf(Color.BLUE))
        }
        val original = source.readBytes()
        val storage = ScanStorage(context)
        val parent = storage.activateProvisionalCacheEntry(
            withImportedDocumentPages(context, listOf(providerUri(source)), DocumentImportAction.Open) { pages ->
                storage.cacheScan(pages, googleScannerAppearanceSettings(), PdfSizeTarget.Original).cached
            },
        )
        val parentBytes = (parent.sourcePages + parent.pages + parent.pdf).associateWith(File::readBytes)
        val placement = MarkPlacement(centerX = 0.5f, centerY = 0.85f, widthFraction = 0.6f)
        val mark = renderDrawnMark(listOf(
            MarkStroke(listOf(MarkPoint(0.1f, 0.5f), MarkPoint(0.9f, 0.5f))),
            MarkStroke(listOf(MarkPoint(0.3f, 0.8f), MarkPoint(0.7f, 0.2f))),
        ))
        val marked = try {
            storage.activateProvisionalCacheEntry(
                storage.createMarkedVariant(parent, 0, mark, placement).cached,
                protectedCacheIds = setOf(parent.baseName),
            )
        } finally {
            mark.recycle()
        }
        assertEquals(parent.baseName, marked.parentCacheId)
        assertEquals(parent.entryId, marked.parentEntryId)
        withBitmap(parent.pages.single()) { page ->
            assertEquals(500 to 600, page.width to page.height)
            assertColor(Color.WHITE, page.getPixel(250, 510))
        }
        withBitmap(marked.pages.single()) { page -> assertColor(Color.BLACK, page.getPixel(250, 510), 48) }
        val exported = renderImageExport(
            marked.pages.single(), File(root, "signed.png"),
            ResolvedImageExport(ImageExportFormat.Png, maxDimension = null, treeUri = null),
        )
        assertEquals("image/png", exported.mimeType)
        assertEquals(500 to 600, exported.width to exported.height)
        withBitmap(exported.file) { page -> assertColor(Color.BLACK, page.getPixel(250, 510), 48) }
        assertPdf(marked.pdf, listOf(500 to 600)) { _, page ->
            assertColor(Color.BLACK, page.getPixel(250, 510), 64)
            assertColor(Color.BLUE, page.getPixel(250, 300))
        }
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val saved = checkNotNull(storage.openSavedScan(marked.baseName))
        val savedName = "Podepsaná smlouva & kůň #1.pdf"
        for ((scan, expectedName) in listOf(
            saved.copy(savedPdfDisplayName = null) to marked.pdf.name,
            saved.copy(savedPdfDisplayName = savedName) to savedName,
        )) {
            val intent = pdfViewIntent(base, scan)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("application/pdf", intent.type)
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
            val uri = checkNotNull(intent.data)
            assertEquals("content", uri.scheme)
            assertEquals("${base.packageName}.fileprovider", uri.authority)
            val clip = checkNotNull(intent.clipData)
            assertEquals(1, clip.itemCount)
            assertEquals(uri, clip.getItemAt(0).uri)
            assertTrue(clip.description.hasMimeType("application/pdf"))
            assertEquals("application/pdf", base.contentResolver.getType(uri))
            checkNotNull(base.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
            )).use { row ->
                assertTrue(row.moveToFirst())
                assertEquals(expectedName, row.getString(0))
                assertFalse(row.moveToNext())
            }
            assertArrayEquals(marked.pdf.readBytes(), checkNotNull(base.contentResolver.openInputStream(uri)).use { it.readBytes() })
        }
        assertEquals(parent.entryId, checkNotNull(storage.openCachedScan(parent.baseName)).entryId)
        parentBytes.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
        assertArrayEquals(original, source.readBytes())
        assertStagingEmpty(context)
    }

    @Test
    fun wrongModesPlaintextEmptyAndBrokenPdfRejectWithoutChangingInputsOrLeavingStaging() = withFixture { context, root ->
        val image = File(root, "page.png").also { writeImage(it, 80, 100, Color.BLUE) }
        val pdf = File(root, "page.pdf").also { writePdf(it, listOf(80 to 100), listOf(Color.RED)) }
        val plaintext = File(root, "not-an-image.png").apply { writeText("plain text, no image or PDF") }
        val empty = File(root, "empty.png").apply { writeBytes(byteArrayOf()) }
        val brokenPdf = File(root, "broken.pdf").apply { writeText("%PDF-1.7\ntruncated") }
        val sources = listOf(image, pdf, plaintext, empty, brokenPdf).associateWith(File::readBytes)

        assertRejected(context, listOf(providerUri(image)), DocumentImportAction.PdfToImages,
            DocumentImportFailureReason.Unreadable)
        assertRejected(context, listOf(providerUri(pdf)), DocumentImportAction.ImagesToPdf,
            DocumentImportFailureReason.Unreadable)
        for (file in listOf(plaintext, empty, brokenPdf)) {
            assertRejected(context, listOf(providerUri(image), providerUri(file)), DocumentImportAction.Open)
        }
        sources.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
    }

    @Test
    fun invalidSchemesAndEmptyOrExcessSelectionsNeverReachTheConsumer() = withFixture { context, root ->
        val source = File(root, "page.png").also { writeImage(it, 80, 100, Color.BLUE) }
        val original = source.readBytes()
        val accepted = providerUri(source)
        for (uri in listOf(
            Uri.fromFile(source), Uri.parse("https://example.invalid/page.png"),
            Uri.parse("data:image/png;base64,AAAA"), Uri.parse("android.resource://${context.packageName}/1"),
            Uri.parse("content:/missing-authority"), Uri.parse("relative.png"),
        )) {
            assertRejected(context, listOf(accepted, uri), DocumentImportAction.Open,
                DocumentImportFailureReason.Unreadable)
        }
        assertRejected(context, emptyList(), DocumentImportAction.Open, DocumentImportFailureReason.TooManyPages)
        assertRejected(context, List(21) { accepted }, DocumentImportAction.Open, DocumentImportFailureReason.TooManyPages)
        assertArrayEquals(original, source.readBytes())
    }

    @Test
    fun consumerFailureAndCoroutineCancellationRemoveTemporaryPages() = withFixture { context, root ->
        val source = File(root, "page.png").also { writeImage(it, 80, 100, Color.BLUE) }
        val original = source.readBytes()
        val staged = mutableListOf<File>()
        val failure = IOException("Synthetic consumer failure")
        val thrown = runCatching {
            withImportedDocumentPages<Unit>(context, listOf(providerUri(source)), DocumentImportAction.Open) { pages ->
                staged += assertPrivateJpegPages(context, pages)
                throw failure
            }
        }.exceptionOrNull()
        assertSame(failure, thrown)
        assertTrue(staged.none(File::exists))
        assertStagingEmpty(context)
        coroutineScope {
            val operation = launch {
                val jobContext = currentCoroutineContext()
                withImportedDocumentPages<Unit>(context, listOf(providerUri(source)), DocumentImportAction.Open) { pages ->
                    staged += assertPrivateJpegPages(context, pages)
                    jobContext.cancel(CancellationException("Synthetic consumer cancellation"))
                    jobContext.ensureActive()
                    error("A cancelled consumer must not complete")
                }
            }
            operation.join()
            assertTrue(operation.isCancelled)
        }
        assertTrue(staged.none(File::exists))
        assertStagingEmpty(context)
        assertArrayEquals(original, source.readBytes())
    }

    private suspend fun assertRejected(
        context: Context,
        uris: List<Uri>,
        action: DocumentImportAction,
        reason: DocumentImportFailureReason? = null,
    ) {
        val failure = try {
            withImportedDocumentPages<Unit>(context, uris, action) { error("Rejected input reached the consumer") }
            error("Invalid input was accepted")
        } catch (error: IOException) {
            error
        }
        if (reason != null) {
            assertTrue("Expected $reason, got $failure", failure is DocumentImportException)
            assertEquals(reason, (failure as DocumentImportException).reason)
        }
        assertStagingEmpty(context)
    }

    private fun assertPrivateJpegPages(context: Context, pages: List<Uri>): List<File> {
        assertTrue(pages.isNotEmpty())
        assertEquals(pages.size, pages.distinct().size)
        val staging = providerInputStagingDirectory(context.cacheDir).canonicalFile.toPath()
        return pages.map { uri ->
            assertEquals("file", uri.scheme)
            val file = File(checkNotNull(uri.path)).canonicalFile
            assertTrue(file.toPath().startsWith(staging))
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            assertEquals("image/jpeg", bounds.outMimeType)
            assertTrue(bounds.outWidth in 1..3_508 && bounds.outHeight in 1..3_508)
            assertTrue(bounds.outWidth.toLong() * bounds.outHeight <= 8_000_000L)
            withBitmap(file) { bitmap -> assertFalse(bitmap.hasAlpha()) }
            file
        }
    }

    private fun assertStagingEmpty(context: Context) {
        assertTrue(checkNotNull(providerInputStagingDirectory(context.cacheDir).list()).isEmpty())
    }

    private fun providerUri(file: File): Uri {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        return FileProvider.getUriForFile(base, "${base.packageName}.fileprovider", file)
    }

    private fun writeImage(file: File, width: Int, height: Int, color: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            Canvas(bitmap).drawRect(width * 0.2f, height * 0.2f, width * 0.8f, height * 0.8f,
                Paint().apply { this.color = color })
            writeBitmap(bitmap, file, if (file.extension == "png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG)
        } finally {
            bitmap.recycle()
        }
    }

    private fun writeBitmap(bitmap: Bitmap, file: File, format: Bitmap.CompressFormat) {
        file.outputStream().use { assertTrue(bitmap.compress(format, 100, it)) }
    }

    private fun writePdf(file: File, dimensions: List<Pair<Int, Int>>, colors: List<Int>) {
        val document = PdfDocument()
        try {
            dimensions.forEachIndexed { index, (width, height) ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(width, height, index + 1).create())
                try {
                    page.canvas.drawRect(width * 0.2f, height * 0.2f, width * 0.8f, height * 0.8f,
                        Paint().apply { color = colors[index] })
                } finally {
                    document.finishPage(page)
                }
            }
            file.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }
    }

    private fun assertPdf(file: File, dimensions: List<Pair<Int, Int>>, checkPage: (Int, Bitmap) -> Unit) {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                assertEquals(dimensions.size, renderer.pageCount)
                dimensions.forEachIndexed { index, (width, height) ->
                    renderer.openPage(index).use { page ->
                        assertEquals(width.toDouble() / height, page.width.toDouble() / page.height, 0.05)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            checkPage(index, bitmap)
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
        }
    }

    private inline fun withBitmap(file: File, check: (Bitmap) -> Unit) {
        val bitmap = checkNotNull(BitmapFactory.decodeFile(file.path)) { "Could not decode ${file.name}" }
        try {
            check(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertColor(expected: Int, actual: Int, tolerance: Int = 16) {
        assertEquals(Color.red(expected).toDouble(), Color.red(actual).toDouble(), tolerance.toDouble())
        assertEquals(Color.green(expected).toDouble(), Color.green(actual).toDouble(), tolerance.toDouble())
        assertEquals(Color.blue(expected).toDouble(), Color.blue(actual).toDouble(), tolerance.toDouble())
    }

    private fun withFixture(test: suspend (Context, File) -> Unit) = runBlocking {
        withContext(Dispatchers.IO) {
            val base = InstrumentationRegistry.getInstrumentation().targetContext
            check(base.packageName == "com.majkeylab.scanit.internal") { "File import tests require the .internal app" }
            val share = File(base.cacheDir.canonicalFile, "share").apply { check(isDirectory || mkdir()) }
            val root = Files.createTempDirectory(share.toPath(), ".file-import-test-").toFile()
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
