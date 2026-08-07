package com.majkeylab.scanit

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
            if (!outputClosed.compareAndSet(false, true)) return true
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
                        callback.onWriteFailed(context.getString(R.string.print_failed))
                    }
                }
            }
        job.invokeOnCompletion { failure ->
            closeOutput()
            if (failure is CancellationException && callbackSent.compareAndSet(false, true)) {
                callback.onWriteCancelled()
            }
        }
        cancellationSignal.setOnCancelListener { job.cancel() }
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
        val document = PrintedPdfDocument(context, printAttributes)
        try {
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            selectedPages.forEach { pageIndex ->
                currentCoroutineContext().ensureActive()
                if (cancellationSignal.isCanceled) throw CancellationException()
                val source = pages[pageIndex]
                if (!source.isFile || source.length() <= 0L) {
                    throw IOException("Print page is missing or empty")
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(source.path, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    throw IOException("Print page dimensions could not be read")
                }
                val bitmap =
                    BitmapFactory.decodeFile(
                        source.path,
                        BitmapFactory.Options().apply {
                            inSampleSize = pdfPageSampleSize(bounds.outWidth, bounds.outHeight)
                        },
                    ) ?: throw IOException("Print page could not be decoded")
                try {
                    val page = document.startPage(pageIndex)
                    try {
                        val content = page.info.contentRect
                        val placement =
                            fitRect(bitmap.width, bitmap.height, content.width(), content.height())
                        page.canvas.drawColor(Color.WHITE)
                        page.canvas.drawBitmap(
                            bitmap,
                            null,
                            RectF(
                                content.left + placement.left,
                                content.top + placement.top,
                                content.left + placement.right,
                                content.top + placement.bottom,
                            ),
                            paint,
                        )
                    } finally {
                        document.finishPage(page)
                    }
                } finally {
                    bitmap.recycle()
                }
            }
            document.writeTo(output)
        } finally {
            document.close()
        }
    }
}
