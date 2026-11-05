package com.majkeylab.scanit

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.pdf.PrintedPdfDocument
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal fun requestedPageIndexes(pageCount: Int, ranges: List<IntRange>): List<Int> {
    require(pageCount >= 0) { "Page count must not be negative" }
    // ponytail: print jobs have few pages; replace this scan only if measured page counts become large.
    return (0 until pageCount).filter { page -> ranges.any { page in it } }
}

internal class ScanPrintAdapter(
    context: Context,
    private val documentName: String,
    private val pages: List<File>,
) : PrintDocumentAdapter() {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var attributes: PrintAttributes? = null

    init {
        require(documentName.isNotBlank()) { "Document name must not be blank" }
        require(pages.isNotEmpty()) { "Print document has no pages" }
    }

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal.isCanceled) {
            callback.onLayoutCancelled()
            return
        }
        attributes = newAttributes
        val info =
            PrintDocumentInfo.Builder(documentName)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(pages.size)
                .build()
        if (cancellationSignal.isCanceled) {
            callback.onLayoutCancelled()
            return
        }
        callback.onLayoutFinished(info, oldAttributes != newAttributes)
    }

    override fun onWrite(
        pageRanges: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        callback: WriteResultCallback,
    ) {
        val output = ParcelFileDescriptor.AutoCloseOutputStream(destination)
        val outputClosed = AtomicBoolean()
        fun closeOutput(): Boolean {
            if (!outputClosed.compareAndSet(false, true)) return false
            return try {
                output.close()
                true
            } catch (_: IOException) {
                false
            }
        }

        val printAttributes = attributes
        if (printAttributes == null) {
            closeOutput()
            callback.onWriteFailed(context.getString(R.string.print_not_ready))
            return
        }
        val selected =
            requestedPageIndexes(
                pages.size,
                pageRanges.map { it.start..it.end },
            )
        if (selected.isEmpty()) {
            closeOutput()
            callback.onWriteFailed(context.getString(R.string.print_no_pages))
            return
        }
        val callbackSent = AtomicBoolean()
        val job =
            scope.launch {
                try {
                    ensureActive()
                    if (cancellationSignal.isCanceled) throw CancellationException()
                    writePdf(printAttributes, selected, output, cancellationSignal)
                    ensureActive()
                    if (cancellationSignal.isCanceled) throw CancellationException()
                    if (!closeOutput()) throw IOException("Print destination could not be closed")
                    ensureActive()
                    if (cancellationSignal.isCanceled) throw CancellationException()
                    if (callbackSent.compareAndSet(false, true)) {
                        callback.onWriteFinished(
                            selected.map { PageRange(it, it) }.toTypedArray(),
                        )
                    }
                } catch (_: CancellationException) {
                    closeOutput()
                    if (callbackSent.compareAndSet(false, true)) callback.onWriteCancelled()
                } catch (_: Exception) {
                    closeOutput()
                    if (callbackSent.compareAndSet(false, true)) {
                        if (cancellationSignal.isCanceled) {
                            callback.onWriteCancelled()
                        } else {
                            callback.onWriteFailed(context.getString(R.string.print_failed))
                        }
                    }
                }
            }
        job.invokeOnCompletion { failure ->
            closeOutput()
            if (failure is CancellationException && callbackSent.compareAndSet(false, true)) {
                callback.onWriteCancelled()
            }
        }
        cancellationSignal.setOnCancelListener {
            job.cancel()
            closeOutput()
        }
    }

    override fun onFinish() {
        scope.cancel()
    }

    private suspend fun writePdf(
        printAttributes: PrintAttributes,
        selectedPages: List<Int>,
        output: OutputStream,
        cancellationSignal: CancellationSignal,
    ) {
        val jobContext = currentCoroutineContext()
        val isCancelled = { cancellationSignal.isCanceled || !jobContext.isActive }
        if (isCancelled()) throw CancellationException()
        val document = PrintedPdfDocument(context, printAttributes)
        val layout = try {
            val content = document.pageContentRect
            JpegPdfPrintLayout(
                document.pageWidth, document.pageHeight,
                content.left, content.top, content.width(), content.height(),
            )
        } finally {
            document.close()
        }
        val staging = Files.createTempDirectory(providerInputStagingDirectory(context.cacheDir).toPath(), ".print-").toFile()
        var failure: Throwable? = null
        try {
            val destination = File(staging, "page.jpg")
            val appearance = ScanAppearance(ScanColorMode.Color, intensity = 0, shadows = 0)
            // Encode each page independently to keep source metadata out of the printed PDF.
            JpegPdfWriter.writeForPrint(output, selectedPages.size, layout, isCancelled) { index ->
                val source = pages[selectedPages[index]]
                val bounds = readJpegDimensions(source)
                val rendered = ScanAppearanceRenderer.renderJpeg(
                    source, destination, appearance,
                    minimumSampleSize = pdfPageSampleSize(bounds.width, bounds.height),
                    isCancelled = isCancelled,
                )
                JpegPdfPage(destination, rendered.width, rendered.height)
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            if (!deleteTreeWithoutFollowingLinks(staging)) {
                val cleanupError = IOException("Print page staging could not be removed")
                failure?.addSuppressed(cleanupError) ?: throw cleanupError
            }
        }
    }
}
