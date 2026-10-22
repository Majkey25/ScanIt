package com.majkeylab.scanit

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanCacheBudgetDeviceTest {
    @Test
    fun unlinkedOpenWriterStaysChargedThroughLateWrites() = withRoot { root ->
        val budget = ScanCacheBudget(root, 10)
        val file = File(root, "unlinked")
        budget.open(file).use { first ->
            first.write(ByteArray(6))
            assertTrue(file.delete())
            assertFalse(file.exists())
            budget.open(File(root, "second")).use { second ->
                assertThrows(IOException::class.java) { second.write(ByteArray(5)) }
                first.write(ByteArray(4))
                assertEquals(10L, Os.fstat(first.fd).st_size)
                assertThrows(IOException::class.java) { second.write(1) }
            }
        }
        budget.open(File(root, "recovered")).use { it.write(ByteArray(10)) }
        assertEquals(10L, cacheDirectoryBytes(root))
    }

    @Test
    fun aggregateImportLimitCleansPartialCopiesAndPreservesHistory() = withRoot { root ->
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val cache = File(root, "cache").apply { check(mkdir()) }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getCacheDir(): File = cache
        }
        val input = File(root, "small.jpg")
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.WHITE)
            FileOutputStream(input).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
        } finally {
            bitmap.recycle()
        }
        val padded = File(root, "padded.jpg")
        input.copyTo(padded)
        RandomAccessFile(padded, "rw").use { it.setLength(65_536) }
        assertEquals(JpegDimensions(32, 32), readJpegDimensions(padded))
        val previousBudget = scanCacheBudget
        scanCacheBudget = ScanCacheBudget(cache, 192L * 1024)
        try {
            val storage = ScanStorage(context)
            val history = storage.activateProvisionalCacheEntry(
                storage.cacheScan(
                    listOf(Uri.fromFile(input)), googleScannerAppearanceSettings(), PdfSizeTarget.Original,
                ).cached,
            )
            val preserved = (history.pages + history.sourcePages + history.pdf)
                .associateWith { it.readBytes().toList() }
            assertThrows(IOException::class.java) {
                runBlocking {
                    storage.cacheScannerResult(
                        listOf(Uri.fromFile(padded)), googleScannerAppearanceSettings(), PdfSizeTarget.Original,
                    ) { listOf(ImageExifOrientation.Normal) }
                }
            }
            assertTrue(providerInputStagingDirectory(cache).listFiles()!!.isEmpty())
            assertEquals(listOf(history.baseName), File(cache, "share").listFiles()!!.map(File::getName))
            preserved.forEach { (file, bytes) -> assertEquals(bytes, file.readBytes().toList()) }
            assertTrue(cacheDirectoryBytes(cache) < 192L * 1024)

            val variant = storage.createAppearanceVariant(
                history, googleScannerAppearanceSettings(), PdfSizeTarget.Original,
            ).cached
            assertTrue(variant.pdf.length() > 0)
            val activated = storage.activateCheckpointProvisional(variant)
            assertEquals(activated.entryId, storage.openCachedScan(activated.baseName)?.entryId)
            preserved.forEach { (file, bytes) -> assertEquals(bytes, file.readBytes().toList()) }
        } finally {
            scanCacheBudget = previousBudget
        }
    }

    private fun withRoot(block: (File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.majkeylab.scanit.internal")
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "cache-budget-qa-").toFile()
        try {
            block(root)
        } finally {
            assertTrue(deleteTreeWithoutFollowingLinks(root))
        }
    }
}
