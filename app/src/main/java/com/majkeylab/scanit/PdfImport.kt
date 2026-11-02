package com.majkeylab.scanit

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal enum class DocumentImportFailureReason { Unreadable, PasswordProtected, TooManyPages, TimedOut, TooLarge }

internal class DocumentImportException(
    val reason: DocumentImportFailureReason,
    cause: Throwable? = null,
) : IOException(reason.name, cause)

private const val PDF_IMPORT_TIMEOUT_MS = 120_000L
private const val PDF_IMPORT_JPEG_QUALITY = 95
private const val PDF_IMPORT_RENDER_DPI = 300.0
private const val PDF_OPEN = 1
private const val PDF_RENDER = 2
private const val PDF_CLOSE = 3
private const val PDF_TOKEN = "token"
private const val PDF_SOURCE = "source"
private const val PDF_OUTPUT = "output"
private const val PDF_LIMIT = "limit"
private const val PDF_COUNT = "count"
private const val PDF_PAGE = "page"
private const val PDF_FAILURE = "failure"
private val PDF_IMPORT_MUTEX = Mutex()

// A Binder send cannot be reliably cancelled. Bound its worker and queue, and never join it.
private val PDF_IMPORT_SENDER = ThreadPoolExecutor(
    0, 1, 30L, TimeUnit.SECONDS, ArrayBlockingQueue(1),
    { task -> Thread(task, "pdf-import-send").apply { isDaemon = true } },
)

/** Source is a private bounded snapshot; destination is a dedicated, existing empty private directory. */
internal suspend fun renderImportedPdf(
    context: Context,
    source: File,
    destinationDirectory: File,
    pageLimit: Int,
): List<File> {
    val outputs = mutableListOf<File>()
    var completed = false
    try {
        val result = withTimeoutOrNull(PDF_IMPORT_TIMEOUT_MS) {
            PDF_IMPORT_MUTEX.withLock {
                val application = context.applicationContext
                withContext(Dispatchers.IO) {
                    validatePdfImport(application, source, destinationDirectory, pageLimit)
                }
                val client = PdfImportConnection(application)
                try {
                    client.bind()
                    val count = withContext(Dispatchers.IO) {
                        ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { input ->
                            client.request(PDF_OPEN, Bundle().apply {
                                putInt(PDF_LIMIT, pageLimit)
                            }, input).getInt(PDF_COUNT)
                        }
                    }
                    validateImportedPageCount(count, pageLimit)
                    repeat(count) { index ->
                        currentCoroutineContext().ensureActive()
                        withContext(Dispatchers.IO) {
                            val output = File(destinationDirectory, "page_${(index + 1).toString().padStart(2, '0')}.jpg")
                            if (!output.createNewFile()) throw DocumentImportException(DocumentImportFailureReason.Unreadable)
                            outputs += output
                            ParcelFileDescriptor.open(
                                output, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE,
                            ).use { descriptor ->
                                client.request(PDF_RENDER, Bundle().apply {
                                    putInt(PDF_PAGE, index)
                                }, descriptor)
                            }
                            val dimensions = readJpegDimensions(output)
                            if (output.length() > MAX_SCAN_SOURCE_PAGE_BYTES ||
                                documentImportDimensions(dimensions.width, dimensions.height) !=
                                    (dimensions.width to dimensions.height)
                            ) throw DocumentImportException(DocumentImportFailureReason.Unreadable)
                        }
                    }
                    client.request(PDF_CLOSE, Bundle())
                    outputs.toList()
                } finally {
                    client.close()
                }
            }
        } ?: throw DocumentImportException(DocumentImportFailureReason.TimedOut)
        currentCoroutineContext().ensureActive()
        completed = true
        return result
    } catch (error: CancellationException) {
        throw error
    } catch (error: DocumentImportException) {
        throw error
    } catch (error: Exception) {
        throw DocumentImportException(DocumentImportFailureReason.Unreadable, error)
    } finally {
        if (!completed) {
            withContext(NonCancellable + Dispatchers.IO) {
                outputs.forEach { output ->
                    if (!output.delete() && output.exists()) {
                        // The caller owns this directory and must also discard it after a failed import.
                        android.util.Log.w("PdfImport", "An incomplete imported page could not be deleted")
                    }
                }
            }
        }
    }
}

private fun validatePdfImport(context: Context, source: File, destination: File, pageLimit: Int) {
    if (pageLimit !in 1..MAX_SCAN_PAGES) throw DocumentImportException(DocumentImportFailureReason.TooManyPages)
    if (source.length() > MAX_SCAN_SOURCE_PAGE_BYTES) {
        throw DocumentImportException(DocumentImportFailureReason.TooLarge)
    }
    val root = context.dataDir.canonicalFile.toPath()
    val input = source.canonicalFile.toPath()
    val output = destination.canonicalFile.toPath()
    if (
        !input.startsWith(root) || !output.startsWith(root) || output == root ||
        !Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS) || source.length() == 0L ||
        !Files.isDirectory(destination.toPath(), LinkOption.NOFOLLOW_LINKS) ||
        destination.list()?.isEmpty() != true
    ) throw DocumentImportException(DocumentImportFailureReason.Unreadable)
    @Suppress("DEPRECATION")
    val service = context.packageManager.getServiceInfo(ComponentName(context, PdfImportService::class.java), 0)
    if (service.exported || service.flags and ServiceInfo.FLAG_ISOLATED_PROCESS == 0) {
        throw DocumentImportException(DocumentImportFailureReason.Unreadable)
    }
}

private fun validateImportedPageCount(count: Int, limit: Int) {
    if (count < 1) throw DocumentImportException(DocumentImportFailureReason.Unreadable)
    if (limit !in 1..MAX_SCAN_PAGES || count > limit) {
        throw DocumentImportException(DocumentImportFailureReason.TooManyPages)
    }
}

/** Connection state belongs to the main thread; Messenger sends use the bounded sender. */
private class PdfImportConnection(private val context: Context) : ServiceConnection {
    private val connected = CompletableDeferred<Messenger>()
    private var pending: Pending? = null
    private var failure: DocumentImportException? = null
    private var bindingRequested = false
    private var bound = false
    private var closed = false
    private val replies = Handler(Looper.getMainLooper()) { message ->
        val request = pending
        if (request != null) {
            try {
                if (message.what == request.what && message.data.getString(PDF_TOKEN) == request.token) {
                    request.result.complete(message.data)
                }
            } catch (error: Exception) {
                request.result.completeExceptionally(DocumentImportException(DocumentImportFailureReason.Unreadable, error))
            }
        }
        true
    }
    private val replyTo = Messenger(replies)

    suspend fun bind() = withContext(Dispatchers.Main.immediate) {
        bindingRequested = true
        bound = context.bindIsolatedService(
            Intent(context, PdfImportService::class.java), Context.BIND_AUTO_CREATE,
            UUID.randomUUID().toString().replace("-", ""), context.mainExecutor, this@PdfImportConnection,
        )
        if (!bound) throw DocumentImportException(DocumentImportFailureReason.Unreadable)
        connected.await()
        Unit
    }

    suspend fun request(what: Int, data: Bundle, descriptor: ParcelFileDescriptor? = null): Bundle =
        withContext(Dispatchers.Main.immediate) {
            failure?.let { throw it }
            check(!closed && pending == null) { "Only one PDF request may be pending" }
            val request = Pending(what, UUID.randomUUID().toString(), CompletableDeferred())
            val remote = connected.await()
            data.putString(PDF_TOKEN, request.token)
            val message = Message.obtain().apply {
                this.what = what
                this.data = data
                replyTo = this@PdfImportConnection.replyTo
            }
            val sendingDescriptor = descriptor?.dup()
            val send = Runnable {
                try {
                    sendingDescriptor.use {
                        if (request.result.isActive) remote.send(message)
                    }
                } catch (error: Exception) {
                    request.result.completeExceptionally(DocumentImportException(DocumentImportFailureReason.Unreadable, error))
                }
            }
            pending = request
            var submitted = false
            try {
                if (sendingDescriptor != null) {
                    data.putParcelable(if (what == PDF_OPEN) PDF_SOURCE else PDF_OUTPUT, sendingDescriptor)
                }
                PDF_IMPORT_SENDER.execute(send)
                submitted = true
                val response = request.result.await()
                if (response.containsKey(PDF_FAILURE)) {
                    val reason = DocumentImportFailureReason.entries.firstOrNull {
                        it.name == response.getString(PDF_FAILURE)
                    } ?: DocumentImportFailureReason.Unreadable
                    throw DocumentImportException(reason)
                }
                response
            } finally {
                pending = null
                request.result.cancel()
                // A running send owns its duplicate until Binder returns; never close it under Binder.
                if (!submitted || PDF_IMPORT_SENDER.remove(send)) sendingDescriptor?.close()
            }
        }

    suspend fun close() = withContext(NonCancellable + Dispatchers.Main.immediate) {
        closed = true
        pending?.result?.cancel()
        pending = null
        connected.cancel()
        replies.removeCallbacksAndMessages(null)
        if (bindingRequested) {
            bindingRequested = false
            try {
                context.unbindService(this@PdfImportConnection)
            } catch (error: IllegalArgumentException) {
                if (bound) throw error
            }
        }
    }

    override fun onServiceConnected(name: ComponentName, service: IBinder) {
        if (!closed && failure == null) connected.complete(Messenger(service))
    }

    override fun onServiceDisconnected(name: ComponentName) = disconnected()
    override fun onBindingDied(name: ComponentName) = disconnected()
    override fun onNullBinding(name: ComponentName) = disconnected()

    private fun disconnected() {
        if (closed) return
        val error = DocumentImportException(DocumentImportFailureReason.Unreadable)
        failure = error
        connected.completeExceptionally(error)
        pending?.result?.completeExceptionally(error)
    }

    private data class Pending(val what: Int, val token: String, val result: CompletableDeferred<Bundle>)
}

/**
 * Manifest: exported="false", isolatedProcess="true", process=":pdf_import"; no intent filters.
 * ScanApplication.onCreate must return after super.onCreate when Process.isIsolated().
 * Only the worker opens/parses/renders/closes PDFs. No path or URI crosses the service boundary.
 */
class PdfImportService : Service() {
    private val worker = HandlerThread("pdf-import-render")
    private var receiver: Messenger? = null
    private var renderer: PdfRenderer? = null
    private var nextPage = 0

    override fun onCreate() {
        super.onCreate()
        if (Process.isIsolated()) {
            worker.start()
            receiver = Messenger(Handler(worker.looper, ::handleRequest))
        }
    }

    override fun onBind(intent: Intent): IBinder? = receiver?.binder

    override fun onDestroy() {
        super.onDestroy()
        worker.quit()
        // Native rendering may ignore interrupts. Only this service's own isolated process is killed.
        if (Process.isIsolated()) Process.killProcess(Process.myPid())
    }

    private fun handleRequest(message: Message): Boolean {
        var input: ParcelFileDescriptor? = null
        var output: ParcelFileDescriptor? = null
        val response = Bundle()
        try {
            val data = message.data
            input = data.pdfDescriptor(PDF_SOURCE)
            output = data.pdfDescriptor(PDF_OUTPUT)
            response.putString(PDF_TOKEN, data.getString(PDF_TOKEN) ?: throw IOException("Missing PDF request token"))
            if (message.replyTo == null) throw IOException("Missing PDF reply channel")
            when (message.what) {
                PDF_OPEN -> {
                    check(renderer == null && nextPage == 0) { "PDF already opened" }
                    val limit = data.getInt(PDF_LIMIT)
                    if (limit !in 1..MAX_SCAN_PAGES) throw DocumentImportException(DocumentImportFailureReason.TooManyPages)
                    val source = input ?: throw IOException("Missing PDF input descriptor")
                    // The private client opens this read-only; the kernel preserves that mode across IPC.
                    val document = try {
                        PdfRenderer(source)
                    } catch (error: SecurityException) {
                        // Android also uses SecurityException for unsupported PDF security schemes.
                        throw DocumentImportException(DocumentImportFailureReason.PasswordProtected, error)
                    }
                    input = null
                    renderer = document
                    validateImportedPageCount(document.pageCount, limit)
                    response.putInt(PDF_COUNT, document.pageCount)
                }
                PDF_RENDER -> {
                    val document = renderer ?: throw IOException("PDF is not open")
                    val index = data.getInt(PDF_PAGE, -1)
                    require(index == nextPage && index < document.pageCount) { "PDF page is out of order" }
                    val destination = output ?: throw IOException("Missing PDF output descriptor")
                    renderPage(document, index, destination)
                    nextPage++
                }
                PDF_CLOSE -> closeRenderer()
                else -> throw IOException("Unknown PDF request")
            }
        } catch (error: Exception) {
            response.putString(PDF_FAILURE, (error as? DocumentImportException)?.reason?.name
                ?: DocumentImportFailureReason.Unreadable.name)
            closeRenderer()
        } finally {
            try {
                input?.close()
            } finally {
                output?.close()
            }
        }
        try {
            message.replyTo?.send(Message.obtain().apply {
                what = message.what
                data = response
            })
        } catch (_: RemoteException) {
            closeRenderer()
        }
        return true
    }

    private fun closeRenderer() {
        val document = renderer
        renderer = null
        document?.close()
    }

    private fun renderPage(document: PdfRenderer, index: Int, output: ParcelFileDescriptor) {
        document.openPage(index).use { page ->
            val (width, height) = documentImportDimensions(
                (page.width * PDF_IMPORT_RENDER_DPI / 72).toInt().coerceAtLeast(1),
                (page.height * PDF_IMPORT_RENDER_DPI / 72).toInt().coerceAtLeast(1),
            )
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                Canvas(bitmap).drawColor(Color.WHITE, PorterDuff.Mode.DST_OVER)
                ParcelFileDescriptor.AutoCloseOutputStream(output).use { stream ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, PDF_IMPORT_JPEG_QUALITY, stream)) {
                        throw IOException("PDF page could not be encoded")
                    }
                }
            } finally {
                bitmap.recycle()
            }
        }
    }
}

@Suppress("DEPRECATION")
private fun Bundle.pdfDescriptor(key: String): ParcelFileDescriptor? = getParcelable(key)
