package com.majkeylab.scanit

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CancellationException
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PrintOutputFailureDeviceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun printPdfWritePropagatesTheFirstOutputFailureAndLeavesCallerStreamOwned() = runBlocking {
        val source = writeJpeg("source.jpg", Color.BLUE, 640, 800)
        val original = source.readBytes()
        val adapter = ScanPrintAdapter(
            InstrumentationRegistry.getInstrumentation().targetContext,
            "output-failure.pdf",
            listOf(source),
        )
        try {
            val baseline = temporaryFolder.newFile("baseline.pdf")
            baseline.outputStream().use { writeActualPdf(adapter, it) }
            assertTrue(baseline.length() in 1025L..1_048_576L)
            PdfRenderer(ParcelFileDescriptor.open(baseline, ParcelFileDescriptor.MODE_READ_ONLY)).use {
                assertEquals(1, it.pageCount)
            }

            val injected = IOException("Injected print destination failure")
            var accepted = 0
            var failingWrites = 0
            var closed = false
            val partial = temporaryFolder.newFile("partial.pdf")
            partial.outputStream().use { file ->
                val failing = object : OutputStream() {
                    override fun write(value: Int) = write(byteArrayOf(value.toByte()))

                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        val count = minOf(length, 1024 - accepted)
                        file.write(bytes, offset, count)
                        accepted += count
                        if (count != length) {
                            failingWrites += 1
                            throw if (failingWrites == 1) injected else IOException("Later output failure")
                        }
                    }

                    override fun close() { closed = true }
                }
                val failure = runCatching { writeActualPdf(adapter, failing) }.exceptionOrNull()
                assertSame(injected, failure)
                assertEquals(1, failingWrites)
                assertFalse(closed)
            }
            assertEquals(1024L, partial.length())
            assertArrayEquals(original, source.readBytes())
        } finally {
            adapter.onFinish()
        }
    }

    @Test
    fun printPdfPreservesAsymmetricMarginsAndNoncontiguousPageOrder() = runBlocking {
        val pages = listOf(Color.BLUE, Color.GREEN, Color.RED).mapIndexed { index, color ->
            writeJpeg("page-$index.jpg", color, 64, 64)
        }
        val comment = "Private source metadata marker"
        ExifInterface(pages.first()).apply {
            setAttribute(ExifInterface.TAG_USER_COMMENT, comment)
            saveAttributes()
        }
        assertTrue(pages.first().readText(Charsets.ISO_8859_1).contains(comment))
        val attributes = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize("test", "Test", 10_000, 8_000))
            .setResolution(PrintAttributes.Resolution("test", "Test", 300, 300))
            .setMinMargins(PrintAttributes.Margins(500, 1500, 1000, 500))
            .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
            .build()
        val adapter = ScanPrintAdapter(
            InstrumentationRegistry.getInstrumentation().targetContext,
            "print-layout.pdf",
            pages,
        )
        try {
            val pdf = temporaryFolder.newFile("print-layout.pdf")
            pdf.outputStream().use { writeActualPdf(adapter, it, listOf(0, 2), attributes) }
            assertFalse(pdf.readText(Charsets.ISO_8859_1).contains(comment))
            PdfRenderer(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
                assertEquals(2, renderer.pageCount)
                repeat(2) { index ->
                    renderer.openPage(index).use { page ->
                        assertEquals(720, page.width)
                        assertEquals(576, page.height)
                        val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                        try {
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            assertEquals(Color.WHITE, bitmap.getPixel(100, 324))
                            assertEquals(Color.WHITE, bitmap.getPixel(342, 90))
                            assertEquals(Color.WHITE, bitmap.getPixel(342, 555))
                            val pixel = bitmap.getPixel(130, 112)
                            assertTrue(if (index == 0) Color.blue(pixel) > 240 else Color.red(pixel) > 240)
                            assertTrue(Color.green(pixel) < 15)
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
        } finally {
            adapter.onFinish()
        }
    }

    @Test
    fun cancellingDuringImageWriteStopsThePdfAndRemovesItsStagingPage() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = writeJpeg("cancel.jpg", Color.BLUE, 640, 800)
        val staging = providerInputStagingDirectory(context.cacheDir)
        val before = checkNotNull(staging.list()).toSet()
        val adapter = ScanPrintAdapter(context, "cancel.pdf", listOf(source))
        val cancellation = CancellationSignal()
        val output = object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                super.write(bytes, offset, length)
                if (length >= 2 && bytes[offset] == 0xff.toByte() && bytes[offset + 1] == 0xd8.toByte()) {
                    cancellation.cancel()
                }
            }
        }
        try {
            val failure = runCatching {
                writeActualPdf(adapter, output, cancellationSignal = cancellation)
            }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertFalse(output.toString(Charsets.ISO_8859_1.name()).contains("%%EOF"))
            assertEquals(before, checkNotNull(staging.list()).toSet())
        } finally {
            adapter.onFinish()
        }
    }

    private fun writeJpeg(name: String, color: Int, width: Int, height: Int): File {
        val file = temporaryFolder.newFile(name)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        } finally {
            bitmap.recycle()
        }
        return file
    }

    private suspend fun writeActualPdf(
        adapter: ScanPrintAdapter,
        output: OutputStream,
        selectedPages: List<Int> = listOf(0),
        attributes: PrintAttributes = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setResolution(PrintAttributes.Resolution("test", "Test", 300, 300))
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
            .build(),
        cancellationSignal: CancellationSignal = CancellationSignal(),
    ) {
        val writer = ScanPrintAdapter::class.java.getDeclaredMethod(
            "writePdf", PrintAttributes::class.java, List::class.java, OutputStream::class.java,
            CancellationSignal::class.java, Continuation::class.java,
        ).apply { isAccessible = true }
        suspendCoroutine<Unit> { continuation ->
            try {
                val result = writer.invoke(adapter, attributes, selectedPages, output, cancellationSignal, continuation)
                if (result !== COROUTINE_SUSPENDED) {
                    check(result == Unit) { "Unexpected print writer result" }
                    continuation.resume(Unit)
                }
            } catch (failure: InvocationTargetException) {
                continuation.resumeWithException(failure.cause ?: failure)
            } catch (failure: Exception) {
                continuation.resumeWithException(failure)
            }
        }
    }
}
