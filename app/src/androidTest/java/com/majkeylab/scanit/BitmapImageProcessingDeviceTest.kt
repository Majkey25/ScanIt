package com.majkeylab.scanit

import android.content.ContentResolver
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BitmapImageProcessingDeviceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun cancelledOrientationWaiterCannotReadBitmapWhileAnotherNativeOwnerIsActive() = runBlocking {
        val source = createSource()
        var resolverReads = 0
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun getContentResolver(): ContentResolver {
                resolverReads += 1
                return super.getContentResolver()
            }
        }
        val release = CompletableDeferred<Unit>()
        val owner = launch(start = CoroutineStart.UNDISPATCHED) {
            withDocumentActionOwner { release.await() }
        }
        val orientation = launch(start = CoroutineStart.UNDISPATCHED) {
            DocumentOrientationProcessor(context).detect(listOf(Uri.fromFile(source)))
        }
        try {
            assertEquals(0, resolverReads)
            assertFalse(orientation.isCompleted)
            orientation.cancel()
            orientation.join()
            assertTrue(orientation.isCancelled)
            assertFalse(owner.isCompleted)
            assertEquals(0, resolverReads)
        } finally {
            release.complete(Unit)
            owner.join()
            orientation.cancel()
            orientation.join()
        }
        assertEquals(0, resolverReads)
    }

    @Test
    fun rgb565DecoderResultWithinOrientationBoundsIsAccepted() {
        val source = createSource()
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
            inScaled = false
        }
        val bitmap = requireNotNull(BitmapFactory.decodeFile(source.path, options))
        try {
            assertEquals(Bitmap.Config.RGB_565, bitmap.config)
            assertEquals(256 * 256 * 2, bitmap.byteCount)
            requireOrientationAnalysisBounds(bitmap.width, bitmap.height, bitmap.allocationByteCount.toLong())
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun decoderReuseRejectsRetainedAllocationDespiteSmallDecodedPixels() {
        val source = createSource()
        val reusable = Bitmap.createBitmap(1536, 1536, Bitmap.Config.RGB_565)
        val initialAllocation = reusable.allocationByteCount
        var decoded: Bitmap? = null
        try {
            val options = BitmapFactory.Options().apply {
                inBitmap = reusable
                inMutable = true
                inPreferredConfig = Bitmap.Config.RGB_565
                inScaled = false
            }
            val bitmap = requireNotNull(BitmapFactory.decodeFile(source.path, options))
            decoded = bitmap
            assertSame(reusable, bitmap)
            assertEquals(256, bitmap.width)
            assertEquals(256, bitmap.height)
            assertEquals(initialAllocation, bitmap.allocationByteCount)
            assertTrue(bitmap.byteCount <= MAX_ORIENTATION_ANALYSIS_PIXELS * 4L)
            assertTrue(bitmap.allocationByteCount > MAX_ORIENTATION_ANALYSIS_PIXELS * 4L)
            assertThrows(IOException::class.java) {
                requireOrientationAnalysisBounds(bitmap.width, bitmap.height, bitmap.allocationByteCount.toLong())
            }
        } finally {
            decoded?.takeUnless(Bitmap::isRecycled)?.recycle()
            if (!reusable.isRecycled) reusable.recycle()
        }
    }

    @Test
    fun wideColorBitmapExceedsByteBoundBeforePixelBound() {
        val bitmap = Bitmap.createBitmap(768, 768, Bitmap.Config.RGBA_F16)
        try {
            assertEquals(Bitmap.Config.RGBA_F16, bitmap.config)
            assertTrue(bitmap.width.toLong() * bitmap.height <= MAX_ORIENTATION_ANALYSIS_PIXELS)
            assertTrue(bitmap.allocationByteCount > MAX_ORIENTATION_ANALYSIS_PIXELS * 4L)
            assertThrows(IOException::class.java) {
                requireOrientationAnalysisBounds(bitmap.width, bitmap.height, bitmap.allocationByteCount.toLong())
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun twoHundredMegapixelHeaderIsSampledBeforeOrientationPixelAnalysis() {
        val source = createSource()
        val bytes = source.readBytes()
        val frame = (0 until bytes.size - 8).first { index ->
            bytes[index] == 0xff.toByte() && bytes[index + 1] == 0xc0.toByte()
        }
        // Header-only fixture: never decode this modified JPEG into pixels.
        bytes[frame + 5] = 0x30
        bytes[frame + 6] = 0
        bytes[frame + 7] = 0x40
        bytes[frame + 8] = 0
        source.writeBytes(bytes)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }

        assertNull(BitmapFactory.decodeFile(source.path, bounds))
        assertEquals(16_384, bounds.outWidth)
        assertEquals(12_288, bounds.outHeight)
        assertEquals("image/jpeg", bounds.outMimeType)
        assertTrue(validOriginalImageDimensions(bounds.outWidth, bounds.outHeight))
        val sample = documentOrientationSampleSize(bounds.outWidth, bounds.outHeight)
        assertEquals(16, sample)
        assertEquals(8, documentActionSampleSize(bounds.outWidth, bounds.outHeight))
        val width = sampledDimensionUpperBound(bounds.outWidth, sample)
        val height = sampledDimensionUpperBound(bounds.outHeight, sample)
        assertEquals(1024, width)
        assertEquals(768, height)
        requireOrientationAnalysisBounds(width, height, width.toLong() * height * 2L)
        requireOrientationAnalysisBounds(width, height, width.toLong() * height * 4L)
        assertThrows(IOException::class.java) {
            requireOrientationAnalysisBounds(bounds.outWidth, bounds.outHeight, 131_072L)
        }
    }

    @Test
    fun cancellationAfterDecodeSkipsTransformAndPreservesDestination() {
        val source = createSource()
        val destination = temporaryFolder.newFile("cancelled.jpg").apply { writeText("previous") }
        var checks = 0
        var transformed = false

        assertThrows(CancellationException::class.java) {
            ScanAppearanceRenderer.renderJpeg(
                source,
                destination,
                ScanAppearance(ScanColorMode.Natural, 0, 0),
                transformBitmap = { transformed = true },
                isCancelled = { ++checks >= 2 },
            )
        }

        assertFalse(transformed)
        assertEquals("previous", destination.readText())
        assertNoStagingFiles()
    }

    @Test
    fun failingTransformRecyclesPageWithoutPublishingPartialOutput() {
        val source = createSource()
        val destination = temporaryFolder.newFile("failed.jpg").apply { writeText("previous") }
        var acquired: Bitmap? = null

        assertThrows(IOException::class.java) {
            ScanAppearanceRenderer.renderJpeg(
                source,
                destination,
                ScanAppearance(ScanColorMode.Natural, 0, 0),
                transformBitmap = {
                    acquired = it
                    throw IOException("Fixture transform failed")
                },
            )
        }

        assertTrue(requireNotNull(acquired).isRecycled)
        assertEquals("previous", destination.readText())
        assertNoStagingFiles()
    }

    @Test
    fun repeatedCleanupRemovesOnlySelectedInkAndPreservesSource() {
        val source = createSource()
        val original = source.readBytes()
        val destination = File(temporaryFolder.root, "cleaned.jpg")
        val strokes = listOf(
            MarkStroke(listOf(
                MarkPoint(0.3f, 0.3f), MarkPoint(0.7f, 0.3f),
                MarkPoint(0.7f, 0.7f), MarkPoint(0.3f, 0.7f),
            )),
        )

        repeat(3) {
            assertEquals(
                JpegDimensions(256, 256),
                renderManualCleanupJpeg(source, destination, strokes, isCancelled = { false }),
            )
            val decoded = requireNotNull(BitmapFactory.decodeFile(destination.path))
            try {
                assertTrue(Color.red(decoded.getPixel(128, 128)) >= 240)
                assertTrue(Color.red(decoded.getPixel(20, 20)) <= 20)
                assertTrue(
                    "Cleanup erased nearby ink outside the lasso",
                    Color.red(decoded.getPixel(184, 128)) <= 40,
                )
                assertTrue(Color.red(decoded.getPixel(230, 230)) >= 240)
            } finally {
                decoded.recycle()
            }
        }

        assertArrayEquals(original, source.readBytes())
        assertNoStagingFiles()
    }

    @Test
    fun completeTwoHundredMegapixelJpegUsesBoundedDecodeAndRealExifExports() {
        val source = createLargeSource()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        assertNull(BitmapFactory.decodeFile(source.path, bounds))
        assertEquals(16_384, bounds.outWidth)
        assertEquals(12_288, bounds.outHeight)
        assertEquals(16, documentOrientationSampleSize(bounds.outWidth, bounds.outHeight))
        assertEquals(8, documentActionSampleSize(bounds.outWidth, bounds.outHeight))

        for (sample in listOf(16, 8)) {
            val bitmap = requireNotNull(BitmapFactory.decodeFile(
                source.path,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inScaled = false
                },
            ))
            try {
                assertEquals(16_384 / sample, bitmap.width)
                assertEquals(12_288 / sample, bitmap.height)
                assertTrue(bitmap.allocationByteCount.toLong() in 1..bitmap.width.toLong() * bitmap.height * 4L)
                if (sample == 16) {
                    requireOrientationAnalysisBounds(bitmap.width, bitmap.height, bitmap.allocationByteCount.toLong())
                }
                assertGrayFixturePixels(bitmap)
            } finally {
                bitmap.recycle()
            }
        }

        for ((exif, expected) in listOf(
            ExifInterface.ORIENTATION_NORMAL to JpegDimensions(4000, 3000),
            ExifInterface.ORIENTATION_ROTATE_90 to JpegDimensions(3000, 4000),
        )) {
            ExifInterface(source).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, exif.toString())
                saveAttributes()
            }
            val original = imageDigest(source)
            val destination = File(temporaryFolder.root, "export-$exif.jpg")
            val plan = imageExportRenderPlan(16_384, 12_288, imageExifOrientation(exif), null)
            assertTrue(plan.tiled)
            assertEquals(52_194_304L, plan.peakBitmapBytes)
            assertTrue(plan.peakBitmapBytes < 64L * 1024 * 1024)
            val rendered = renderImageExport(
                source,
                destination,
                ResolvedImageExport(ImageExportFormat.Jpeg, null, null),
                isCancelled = { false },
            )
            assertFalse(rendered.exactSourceCopy)
            assertEquals(expected.width, rendered.width)
            assertEquals(expected.height, rendered.height)
            assertEquals(expected, readJpegDimensions(destination))
            val rawExifOrientation = ExifInterface(destination).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_UNDEFINED,
            )
            assertEquals(ImageExifOrientation.Normal, imageExifOrientation(rawExifOrientation))
            val verification = requireNotNull(BitmapFactory.decodeFile(
                destination.path,
                BitmapFactory.Options().apply {
                    inSampleSize = 4
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inScaled = false
                },
            ))
            try {
                assertEquals(expected.width / 4, verification.width)
                assertEquals(expected.height / 4, verification.height)
                assertTrue(verification.allocationByteCount.toLong() in 1..4_194_304L)
                assertGrayFixturePixels(verification)
            } finally {
                verification.recycle()
            }
            assertArrayEquals(original, imageDigest(source))
            assertNoStagingFiles()
        }
    }

    private fun createLargeSource(): File {
        val source = temporaryFolder.newFile("gray-200mp.jpg")
        // Complete grayscale JPEG generated by host Pillow 12.2.0; gzip only packs fixture bytes.
        val encoded =
            "H4sIAAAAAAAC/+3PNXdUQRQA4LfZ3bebbIoEh4aDByrcGiQQSDosSIdLh8s5FDikw6UjOB0SrEKCdngSGtwq3JrHe+FPUHx3vpkzc+7MvWeipuh5UFZTVV0VpFJBkIpHED0LKoOiVBLJWpREJp2s2UwmnQmzYdg6c8X5eObCMF/IF5ckEe9KCyWlySG6GhTy/YPRcdnyIGoMusbFs6014w7/IlWUzmTDXPy2EF9oKIt7ptNxp2zcJs6uj/NBpjzbptuAMWHbSbNz3Ze2G7hh5+F8j7FnrrWffP9Tz0Fzlm0sLunQsVPnLr1696no22/wkKHDho8YWTlufNWEidU1U6ZOq50+Y+asufPmL1i4aPGS5StWrlq9Zu26TZu3bN22fUfdrt179u7bf+DgofojR48dP3Hy1Omz5843XLh46fKV6zcab966fefuvQcPHz1+8rSpueXFy1ev37x99/7Dx89fvn77/uPnr99/opYgn/xnVFBRXwc" +
                "A".repeat(3046) + "PBfiZr/AjTBgNtKASQA"
        GZIPInputStream(ByteArrayInputStream(Base64.decode(encoded, Base64.NO_WRAP))).use { input ->
            source.outputStream().use { output -> input.copyTo(output) }
        }
        assertEquals(2_359_626L, source.length())
        assertEquals(
            "03c497a62bf1b1b7a27511e8bb696dde9b35b4ece0b52cc0d1e8591ffa838c8a",
            imageDigest(source).joinToString("") { "%02x".format(it) },
        )
        return source
    }

    private fun imageDigest(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }

    private fun assertGrayFixturePixels(bitmap: Bitmap) {
        for ((x, y) in listOf(0 to 0, bitmap.width / 2 to bitmap.height / 2, bitmap.width - 1 to bitmap.height - 1)) {
            val pixel = bitmap.getPixel(x, y)
            assertTrue(Color.red(pixel) in 126..130)
            assertTrue(Color.green(pixel) in 126..130)
            assertTrue(Color.blue(pixel) in 126..130)
        }
    }

    private fun createSource(): File {
        val source = temporaryFolder.newFile("source.jpg")
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            val canvas = Canvas(bitmap)
            val paint = Paint().apply { color = Color.BLACK }
            canvas.drawRect(110f, 110f, 146f, 146f, paint)
            canvas.drawRect(10f, 10f, 30f, 30f, paint)
            canvas.drawRect(181f, 120f, 187f, 136f, paint)
            source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        return source
    }

    private fun assertNoStagingFiles() {
        assertTrue(temporaryFolder.root.listFiles().orEmpty().none { it.name.startsWith(".scanit-") })
    }
}
