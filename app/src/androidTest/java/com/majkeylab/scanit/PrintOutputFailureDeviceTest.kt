package com.majkeylab.scanit

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
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
    fun nativePdfWritePropagatesTheFirstOutputFailureAndLeavesCallerStreamOwned() = runBlocking {
        val source = temporaryFolder.newFile("source.jpg")
        Bitmap.createBitmap(640, 800, Bitmap.Config.ARGB_8888).let { bitmap ->
            try {
                bitmap.eraseColor(Color.BLUE)
                source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            } finally {
                bitmap.recycle()
            }
        }
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
            var injectedInsideNativeWrite = false
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
                            injectedInsideNativeWrite = Thread.currentThread().stackTrace.any {
                                it.className == "android.graphics.pdf.PdfDocument" && it.methodName == "writeTo"
                            }
                            failingWrites += 1
                            throw if (failingWrites == 1) injected else IOException("Later output failure")
                        }
                    }

                    override fun close() { closed = true }
                }
                val failure = runCatching { writeActualPdf(adapter, failing) }.exceptionOrNull()
                assertSame(injected, failure)
                assertTrue(injectedInsideNativeWrite)
                assertEquals(1, failingWrites)
                assertFalse(closed)
            }
            assertEquals(1024L, partial.length())
            assertArrayEquals(original, source.readBytes())
        } finally {
            adapter.onFinish()
        }
    }

    private suspend fun writeActualPdf(adapter: ScanPrintAdapter, output: OutputStream) {
        val attributes = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setResolution(PrintAttributes.Resolution("test", "Test", 300, 300))
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
            .build()
        val writer = ScanPrintAdapter::class.java.getDeclaredMethod(
            "writePdf", PrintAttributes::class.java, List::class.java, OutputStream::class.java,
            CancellationSignal::class.java, Continuation::class.java,
        ).apply { isAccessible = true }
        suspendCoroutine<Unit> { continuation ->
            try {
                val result = writer.invoke(adapter, attributes, listOf(0), output, CancellationSignal(), continuation)
                if (result !== COROUTINE_SUSPENDED) {
                    check(result == Unit) { "Unexpected native writer result" }
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
