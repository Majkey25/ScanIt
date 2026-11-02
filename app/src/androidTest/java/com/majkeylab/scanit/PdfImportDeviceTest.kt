package com.majkeylab.scanit

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfImportDeviceTest {
    @Test
    fun multipagePdfRendersOrderedInkOnWhiteWithinSizeAndAspectBounds() = withFixture { context, directory, output ->
        @Suppress("DEPRECATION")
        val service = context.packageManager.getServiceInfo(ComponentName(context, PdfImportService::class.java), 0)
        assertFalse(service.exported)
        assertTrue(service.flags and ServiceInfo.FLAG_ISOLATED_PROCESS != 0)
        val pages = listOf(200 to 100, 100 to 200, 720 to 720, 1_440 to 360)
        val colors = listOf(Color.RED, Color.BLUE, Color.GREEN, Color.MAGENTA)
        // PDF sizes are points; these are the bounded 300 dpi raster sizes.
        val expectedDimensions = listOf(833 to 416, 416 to 833, 2_828 to 2_828, 3_508 to 877)
        val source = File(directory, "source.pdf")
        writePdf(source, pages, colors)
        val originalHash = hash(source)

        val rendered = renderImportedPdf(context, source, output, MAX_SCAN_PAGES)

        assertEquals(pages.size, rendered.size)
        assertEquals(listOf("page_01.jpg", "page_02.jpg", "page_03.jpg", "page_04.jpg"), rendered.map(File::getName))
        assertEquals(rendered.toSet(), output.listFiles()!!.toSet())
        rendered.forEachIndexed { index, file ->
            assertEquals(output.canonicalFile, file.parentFile!!.canonicalFile)
            val bitmap = checkNotNull(BitmapFactory.decodeFile(file.path))
            try {
                val (pageWidth, pageHeight) = pages[index]
                assertEquals(expectedDimensions[index], bitmap.width to bitmap.height)
                assertTrue(bitmap.width <= 3_508 && bitmap.height <= 3_508)
                assertTrue(bitmap.width.toLong() * bitmap.height <= 8_000_000L)
                // Allow one pixel of floor rounding on each axis.
                assertTrue(abs(bitmap.width.toLong() * pageHeight - bitmap.height.toLong() * pageWidth) <=
                    pageWidth.toLong() + pageHeight)
                assertColor(Color.WHITE, bitmap.getPixel(bitmap.width / 20, bitmap.height / 20))
                assertColor(colors[index], bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
            } finally {
                bitmap.recycle()
            }
        }
        assertTrue(originalHash.contentEquals(hash(source)))
    }

    @Test
    fun malformedEmptyAndZeroPagePdfsLeaveNoOutputsOrSourceChanges() = withFixture { context, directory, output ->
        val source = File(directory, "bad.pdf")
        listOf(byteArrayOf(), "%PDF-1.7\ntruncated".toByteArray(), zeroPagePdf()).forEach { bytes ->
            source.writeBytes(bytes)
            assertFailure(context, source, output, MAX_SCAN_PAGES, DocumentImportFailureReason.Unreadable)
            assertTrue(output.list()!!.isEmpty())
        }
    }

    @Test
    fun pageCapIsCheckedBeforeCreatingAnyOutput() = withFixture { context, directory, output ->
        val source = File(directory, "too-many.pdf")
        writePdf(source, List(2) { 100 to 100 }, List(2) { Color.BLACK })
        assertFailure(context, source, output, 1, DocumentImportFailureReason.TooManyPages)
        assertTrue(output.list()!!.isEmpty())
        writePdf(source, List(21) { 10 to 10 }, List(21) { Color.BLACK })
        assertFailure(context, source, output, 20, DocumentImportFailureReason.TooManyPages)
        assertTrue(output.list()!!.isEmpty())
        listOf(0, 21).forEach { limit ->
            assertFailure(context, source, output, limit, DocumentImportFailureReason.TooManyPages)
            assertTrue(output.list()!!.isEmpty())
        }
    }

    @Test
    fun nonemptyDestinationIsRejectedWithoutOverwritingAnything() = withFixture { context, directory, output ->
        val source = File(directory, "source.pdf")
        writePdf(source, listOf(100 to 100), listOf(Color.BLACK))
        val existing = File(output, "page_01.jpg").apply { writeText("keep") }
        assertFailure(context, source, output, 20, DocumentImportFailureReason.Unreadable)
        assertEquals("keep", existing.readText())
        assertEquals(listOf(existing), output.listFiles()!!.toList())
    }

    @Test
    fun cancellationDuringBindingUnbindsAndReleasesTheNextImport() = withFixture { context, directory, output ->
        val source = File(directory, "source.pdf")
        writePdf(source, listOf(100 to 100), listOf(Color.BLACK))
        val originalHash = hash(source)
        val waiting = ControlledBindingContext(context, BindingBehavior.Wait)
        coroutineScope {
            val operation = launch(Dispatchers.Default) {
                renderImportedPdf(waiting, source, output, 20)
                error("A service that never connects must not finish an import")
            }
            try {
                withTimeout(5_000L) { waiting.binding.await() }
            } finally {
                operation.cancelAndJoin()
            }
        }
        assertEquals(1, waiting.unbinds)
        assertTrue(output.list()!!.isEmpty())
        assertTrue(originalHash.contentEquals(hash(source)))
        assertEquals(1, renderImportedPdf(context, source, output, 20).size)
    }

    @Test
    fun disconnectionBeforeFirstReplyFailsAndUnbinds() = withFixture { context, directory, output ->
        val source = File(directory, "source.pdf")
        writePdf(source, listOf(100 to 100), listOf(Color.BLACK))
        val disconnected = ControlledBindingContext(context, BindingBehavior.Disconnect)
        withTimeout(5_000L) {
            assertFailure(disconnected, source, output, 20, DocumentImportFailureReason.Unreadable)
        }
        assertEquals(1, disconnected.unbinds)
        assertTrue(output.list()!!.isEmpty())
    }

    @Test
    fun cancellationOfPendingPageDeletesPartialOutputAndClosesLocalDescriptor() = withFixture { context, directory, output ->
        val source = File(directory, "source.pdf")
        writePdf(source, listOf(100 to 100), listOf(Color.BLACK))
        val originalHash = hash(source)
        val waiting = ControlledBindingContext(context, BindingBehavior.PausePage)
        coroutineScope {
            val operation = launch(Dispatchers.Default) {
                renderImportedPdf(waiting, source, output, 20)
                error("An unanswered page request must not finish an import")
            }
            try {
                withTimeout(5_000L) { waiting.pageRequested.await() }
                assertEquals(1, output.list()!!.size)
            } finally {
                operation.cancelAndJoin()
            }
        }
        assertEquals(1, waiting.unbinds)
        assertFalse(checkNotNull(waiting.outputDescriptor).fileDescriptor.valid())
        assertTrue(output.list()!!.isEmpty())
        assertTrue(originalHash.contentEquals(hash(source)))
    }

    @Test
    fun disconnectionOfPendingPageDeletesPartialOutput() = withFixture { context, directory, output ->
        val source = File(directory, "source.pdf")
        writePdf(source, listOf(100 to 100), listOf(Color.BLACK))
        val disconnected = ControlledBindingContext(context, BindingBehavior.DisconnectPage)
        withTimeout(5_000L) {
            assertFailure(disconnected, source, output, 20, DocumentImportFailureReason.Unreadable)
        }
        assertTrue(disconnected.pageRequested.isCompleted)
        assertEquals(1, disconnected.unbinds)
        assertFalse(checkNotNull(disconnected.outputDescriptor).fileDescriptor.valid())
        assertTrue(output.list()!!.isEmpty())
    }

    private suspend fun assertFailure(
        context: Context,
        source: File,
        output: File,
        limit: Int,
        reason: DocumentImportFailureReason,
    ) {
        val originalHash = hash(source)
        val failure = try {
            renderImportedPdf(context, source, output, limit)
            error("Import should fail with $reason")
        } catch (error: DocumentImportException) {
            error
        }
        assertEquals(reason, failure.reason)
        assertTrue(originalHash.contentEquals(hash(source)))
    }

    private fun writePdf(file: File, sizes: List<Pair<Int, Int>>, colors: List<Int>) {
        val document = PdfDocument()
        try {
            sizes.forEachIndexed { index, (width, height) ->
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

    private fun zeroPagePdf(): ByteArray {
        val header = "%PDF-1.4\n"
        val catalog = "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n"
        val pages = "2 0 obj\n<< /Type /Pages /Kids [] /Count 0 >>\nendobj\n"
        val body = header + catalog + pages
        return (body + "xref\n0 3\n0000000000 65535 f \n" +
            "${header.length.toString().padStart(10, '0')} 00000 n \n" +
            "${(header.length + catalog.length).toString().padStart(10, '0')} 00000 n \n" +
            "trailer\n<< /Size 3 /Root 1 0 R >>\nstartxref\n${body.length}\n%%EOF\n").toByteArray(Charsets.US_ASCII)
    }

    private fun hash(file: File): ByteArray = MessageDigest.getInstance("SHA-256").digest(file.readBytes())

    private fun assertColor(expected: Int, actual: Int) {
        assertEquals(Color.red(expected).toDouble(), Color.red(actual).toDouble(), 12.0)
        assertEquals(Color.green(expected).toDouble(), Color.green(actual).toDouble(), 12.0)
        assertEquals(Color.blue(expected).toDouble(), Color.blue(actual).toDouble(), 12.0)
    }

    private fun withFixture(test: suspend (Context, File, File) -> Unit) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.majkeylab.scanit.internal") { "PDF import tests require the .internal app" }
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "pdf-import-test-").toFile()
        try {
            val output = File(directory, "pages").apply { check(mkdir()) }
            test(context, directory, output)
        } finally {
            assertTrue(directory.deleteRecursively())
        }
    }

    private enum class BindingBehavior { Wait, Disconnect, PausePage, DisconnectPage }

    private class ControlledBindingContext(base: Context, private val behavior: BindingBehavior) : ContextWrapper(base) {
        val binding = CompletableDeferred<Unit>()
        val pageRequested = CompletableDeferred<Unit>()
        var outputDescriptor: ParcelFileDescriptor? = null
            private set
        var unbinds = 0
            private set
        private var handler: Handler? = null

        override fun getApplicationContext(): Context = this

        override fun bindIsolatedService(
            service: Intent,
            flags: Int,
            instanceName: String,
            executor: Executor,
            connection: ServiceConnection,
        ): Boolean {
            check(service.data == null && service.extras == null)
            check(instanceName.matches(Regex("[A-Za-z0-9_.]+")))
            binding.complete(Unit)
            if (behavior == BindingBehavior.Disconnect) {
                executor.execute { connection.onServiceDisconnected(ComponentName(this, PdfImportService::class.java)) }
            } else if (behavior != BindingBehavior.Wait) {
                val receiver = Handler(Looper.getMainLooper()) { message ->
                    when (message.what) {
                        1 -> {
                            // A stale token must not admit this deliberately excessive page count.
                            replyToOpen(message, "stale", MAX_SCAN_PAGES + 1)
                            replyToOpen(message, checkNotNull(message.data.getString("token")), 1)
                        }
                        2 -> {
                            @Suppress("DEPRECATION")
                            val descriptor = message.data.getParcelable<ParcelFileDescriptor>("output")
                            outputDescriptor = descriptor
                            pageRequested.complete(Unit)
                            if (behavior == BindingBehavior.DisconnectPage) {
                                connection.onServiceDisconnected(ComponentName(this, PdfImportService::class.java))
                            }
                        }
                        else -> error("Unexpected PDF fixture request")
                    }
                    true
                }
                handler = receiver
                executor.execute {
                    connection.onServiceConnected(ComponentName(this, PdfImportService::class.java), Messenger(receiver).binder)
                }
            }
            return true
        }

        override fun unbindService(connection: ServiceConnection) {
            unbinds++
            handler?.removeCallbacksAndMessages(null)
        }

        private fun replyToOpen(request: Message, token: String, count: Int) {
            request.replyTo.send(Message.obtain().apply {
                what = request.what
                data = Bundle().apply {
                    putString("token", token)
                    putInt("count", count)
                }
            })
        }
    }
}
