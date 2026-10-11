package com.majkeylab.scanit

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentDeletionDeviceTest {
    @Test fun deletePdfKeepsImagesInRecent() = withScan { storage, cached ->
        val images = checkNotNull(storage.openSavedScan(cached.baseName)).savedImages
        delete(storage, cached, RecentDeleteTarget.Pdf)
        assertFalse(checkNotNull(readOutputMetadata(checkNotNull(cached.pdf.parentFile), cached.baseName, 1)).removeRecentPending)
        val recent = storage.listRecentScans().single()
        assertFalse(recent.hasSavedPdf)
        assertEquals(1, recent.savedImageCount)
        val reopened = checkNotNull(storage.openSavedScan(cached.baseName))
        assertNull(reopened.savedPdf)
        assertEquals(images, reopened.savedImages)
        assertTrue(reopened.cached.pages.single().isFile)
        assertSurvivingBytes(cached)
    }

    @Test fun deleteImagesKeepsPdfInRecent() = withScan { storage, cached ->
        val pdf = checkNotNull(storage.openSavedScan(cached.baseName)).savedPdf
        delete(storage, cached, RecentDeleteTarget.Images)
        assertFalse(checkNotNull(readOutputMetadata(checkNotNull(cached.pdf.parentFile), cached.baseName, 1)).removeRecentPending)
        val recent = storage.listRecentScans().single()
        assertTrue(recent.hasSavedPdf)
        assertEquals(0, recent.savedImageCount)
        val reopened = checkNotNull(storage.openSavedScan(cached.baseName))
        assertEquals(pdf, reopened.savedPdf)
        assertTrue(reopened.savedImages.isEmpty())
        assertTrue(reopened.cached.pdf.isFile)
        assertSurvivingBytes(cached)
        delete(storage, cached, RecentDeleteTarget.Pdf)
        assertTrue(storage.listRecentScans().isEmpty())
    }

    @Test fun deleteBothRemovesRecent() = withScan { storage, cached ->
        delete(storage, cached, RecentDeleteTarget.Both)
        assertTrue(storage.listRecentScans().isEmpty())
        assertNull(storage.openSavedScan(cached.baseName))
    }

    private fun delete(storage: ScanStorage, cached: CachedScan, target: RecentDeleteTarget) {
        assertEquals(OutputDeleteOperationResult.Completed, storage.deleteDurableOutputs(
            OutputDeleteRequest(cached.baseName, cached.entryId, target), deleteRecentCache = true,
        ))
    }

    private fun assertSurvivingBytes(cached: CachedScan) {
        val metadata = checkNotNull(readOutputMetadata(checkNotNull(cached.pdf.parentFile), cached.baseName, 1))
        val references = buildList {
            metadata.pdf?.let { add(it.uri to checkNotNull(it.outputFingerprint())) }
            metadata.images.forEach { add(it.uri to checkNotNull(it.outputFingerprint())) }
        }
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        references.forEach { (uri, expected) ->
            val actual = checkNotNull(resolver.openInputStream(Uri.parse(uri))).use {
                readOutputFingerprint(it, expected.byteLength)
            }
            assertEquals(expected, actual)
        }
    }

    private fun withScan(test: (ScanStorage, CachedScan) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        check(base.packageName == "com.majkeylab.scanit.internal")
        val root = Files.createTempDirectory(base.cacheDir.toPath(), "recent-delete-test-").toFile()
        val context = object : ContextWrapper(base) { override fun getCacheDir(): File = root }
        val storage = ScanStorage(context)
        var cached: CachedScan? = null
        var outputs: OutputMetadata? = null
        try {
            val source = File(root, "source.jpg")
            val bitmap = Bitmap.createBitmap(96, 128, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(android.graphics.Color.WHITE)
                source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            } finally { bitmap.recycle() }
            cached = storage.activateProvisionalCacheEntry(storage.cacheScan(
                listOf(Uri.fromFile(source)), googleScannerAppearanceSettings(), PdfSizeTarget.Original,
            ).cached)
            storage.savePdf(cached, "SeliaScanRecentDeleteTest", null)
            outputs = readOutputMetadata(checkNotNull(cached.pdf.parentFile), cached.baseName, 1)
            storage.saveImages(cached, "SeliaScanRecentDeleteTest")
            outputs = readOutputMetadata(checkNotNull(cached.pdf.parentFile), cached.baseName, 1)
            assertNotNull(outputs)
            test(storage, cached)
        } finally {
            val deleter = ExactOutputDeleter(context)
            outputs?.pdf?.let { assertTrue(deleter.deletePdf(it) in setOf(OutputDeleteStatus.Deleted, OutputDeleteStatus.Absent)) }
            cached?.let { scan -> outputs?.images?.forEach {
                assertTrue(deleter.deleteImage(scan, it) in setOf(OutputDeleteStatus.Deleted, OutputDeleteStatus.Absent))
            } }
            check(root.deleteRecursively())
        }
    }
}
