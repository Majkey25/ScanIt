package com.majkeylab.scanit

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageDeviceTest {
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

    private fun withScan(test: (ScanStorage, CachedScan) -> Unit) {
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
                canvas.drawText("DOCUMENT TEST", 80f, 200f, paint)
                canvas.drawText("Reference 12345", 80f, 360f, paint)
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
