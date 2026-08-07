package com.majkeylab.scanit

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Clock
import java.util.concurrent.CancellationException
import kotlin.math.min

private const val A4_WIDTH = 595
private const val A4_HEIGHT = 842
private const val DEFAULT_THUMBNAIL_SIZE = 1024
private const val PDF_MAX_BITMAP_SIDE = 3508
private const val PDF_MIME_TYPE = "application/pdf"
private const val JPEG_MIME_TYPE = "image/jpeg"
private const val AI_WORK_DIRECTORY = "ai-work"
private const val MAX_SHARE_CACHE_SCANS = 8

internal data class FitRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

internal fun scanPageFileName(
    baseName: String,
    pageNumber: Int,
    isAiCopy: Boolean = false,
): String {
    require(baseName.isNotBlank()) { "Scan base name must not be blank" }
    require(pageNumber > 0) { "Page number must be positive" }
    val page = pageNumber.toString().padStart(2, '0')
    val suffix = if (isAiCopy) "_AI" else ""
    return "${baseName}_${page}${suffix}.jpg"
}

internal fun scanPdfFileName(baseName: String, isAiCopy: Boolean = false): String {
    require(baseName.isNotBlank()) { "Scan base name must not be blank" }
    return if (isAiCopy) "${baseName}_AI.pdf" else "$baseName.pdf"
}

internal fun fitRect(
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Int,
    targetHeight: Int,
): FitRect {
    require(sourceWidth > 0 && sourceHeight > 0) { "Source dimensions must be positive" }
    require(targetWidth > 0 && targetHeight > 0) { "Target dimensions must be positive" }
    val scale = min(targetWidth.toFloat() / sourceWidth, targetHeight.toFloat() / sourceHeight)
    val width = sourceWidth * scale
    val height = sourceHeight * scale
    val left = (targetWidth - width) / 2f
    val top = (targetHeight - height) / 2f
    return FitRect(left, top, left + width, top + height)
}

internal fun thumbnailSampleSize(width: Int, height: Int, maxSize: Int): Int {
    require(width > 0 && height > 0) { "Image dimensions must be positive" }
    require(maxSize > 0) { "Maximum thumbnail size must be positive" }
    val longestSide = maxOf(width, height).toLong()
    var sampleSize = 1L
    while ((longestSide + sampleSize - 1L) / sampleSize > maxSize.toLong()) {
        require(sampleSize <= Int.MAX_VALUE / 2L) { "Required sample size is too large" }
        sampleSize *= 2
    }
    return sampleSize.toInt()
}

internal fun pdfPageSampleSize(width: Int, height: Int): Int =
    thumbnailSampleSize(width, height, PDF_MAX_BITMAP_SIDE)

internal fun shareCacheEntriesToPrune(children: List<File>, keep: Int): List<File> {
    require(keep >= 0) { "Share cache retention must not be negative" }
    return children
        .sortedWith(
            compareByDescending<File> { it.lastModified() }
                .thenByDescending { it.name },
        ).drop(keep)
}

internal class ScanStorage(
    private val context: Context,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private companion object {
        // ponytail: app-wide lock is intentional; use per-root locks only if concurrent scanner throughput is ever needed.
        val shareCacheLock = Any()
    }

    private val resolver = context.contentResolver

    fun cacheScan(pageUris: List<Uri>, pdfUri: Uri): CachedScan =
        synchronized(shareCacheLock) {
            require(pageUris.isNotEmpty()) { "Scanner returned no pages" }
            val baseName = scanBaseName(clock)
            val shareRoot = prepareShareCache()
            val scanDirectory = File(shareRoot, baseName)
            if (!scanDirectory.mkdir()) {
                throw IOException("Fresh scan cache directory could not be created")
            }

            try {
                val pages =
                    pageUris.mapIndexed { index, uri ->
                        File(scanDirectory, scanPageFileName(baseName, index + 1)).also {
                            copyUriToFile(uri, it)
                        }
                    }
                val pdf = File(scanDirectory, scanPdfFileName(baseName)).also {
                    copyUriToFile(pdfUri, it)
                }
                CachedScan(baseName, pages, pdf)
            } catch (failure: Throwable) {
                deleteRecursivelyOrSuppress(scanDirectory, failure)
                throw failure
            }
        }

    fun deleteCachedScan(cached: CachedScan): Boolean =
        synchronized(shareCacheLock) {
            try {
                val root = shareCacheRoot()
                val directory = File(root, cached.baseName).absoluteFile
                if (
                    directory.canonicalFile != directory ||
                        directory.parentFile != root ||
                        cached.pages.plus(cached.pdf).any {
                            val file = it.absoluteFile
                            file.canonicalFile != file || file.parentFile != directory
                        }
                ) {
                    false
                } else {
                    !directory.exists() || directory.deleteRecursively()
                }
            } catch (_: IOException) {
                false
            } catch (_: SecurityException) {
                false
            }
        }

    fun prepareAiWork(original: CachedScan): CachedScan =
        synchronized(shareCacheLock) {
            require(original.pages.isNotEmpty()) { "AI cleanup needs at least one page" }
            requireShareScanDirectory(original)
            if (!clearAiWorkLocked()) {
                throw IOException("Old AI work cache could not be deleted")
            }
            val root = aiWorkRoot()
            if (!root.mkdir()) {
                throw IOException("AI work cache could not be created")
            }
            CachedScan(
                baseName = original.baseName,
                pages =
                    original.pages.indices.map { index ->
                        File(root, scanPageFileName(original.baseName, index + 1, isAiCopy = true))
                    },
                pdf = File(root, scanPdfFileName(original.baseName, isAiCopy = true)),
            )
        }

    fun writeAiPage(destination: File, jpeg: ByteArray) {
        require(jpeg.size >= 4) { "AI page is empty" }
        require(
            jpeg[0] == 0xff.toByte() &&
                jpeg[1] == 0xd8.toByte() &&
                jpeg[jpeg.lastIndex - 1] == 0xff.toByte() &&
                jpeg[jpeg.lastIndex] == 0xd9.toByte(),
        ) { "AI page is not a JPEG" }
        val target = destination.absoluteFile
        val root = aiWorkRoot()
        if (target.canonicalFile != target || target.parentFile != root || target.exists()) {
            throw IOException("AI page destination is unsafe")
        }
        try {
            FileOutputStream(target).use { it.write(jpeg) }
            if (target.length() != jpeg.size.toLong()) {
                throw IOException("AI page was not written completely")
            }
        } catch (failure: Throwable) {
            deleteFileOrSuppress(target, failure)
            throw failure
        }
    }

    fun promoteAiWork(original: CachedScan, work: CachedScan): CachedScan =
        synchronized(shareCacheLock) {
            require(original.baseName == work.baseName) { "AI scan name does not match" }
            require(original.pages.size == work.pages.size) { "AI page count does not match" }
            val directory = requireShareScanDirectory(original)
            requireAiWork(work)
            work.pages.forEach(::requireReadableFile)
            requireReadableFile(work.pdf)
            if (!deleteAiCachedCopyLocked(original, directory)) {
                throw IOException("Old AI cache could not be deleted")
            }

            val promoted =
                CachedScan(
                    baseName = original.baseName,
                    pages =
                        work.pages.indices.map { index ->
                            File(
                                directory,
                                scanPageFileName(original.baseName, index + 1, isAiCopy = true),
                            )
                        },
                    pdf = File(directory, scanPdfFileName(original.baseName, isAiCopy = true)),
                )
            val created = mutableListOf<File>()
            try {
                work.pages.zip(promoted.pages).forEach { (source, destination) ->
                    copyFile(source, destination)
                    created += destination
                }
                copyFile(work.pdf, promoted.pdf)
                created += promoted.pdf
                promoted
            } catch (failure: Throwable) {
                created.forEach { deleteFileOrSuppress(it, failure) }
                throw failure
            }
        }

    fun deleteAiCachedCopy(original: CachedScan): Boolean =
        synchronized(shareCacheLock) {
            try {
                deleteAiCachedCopyLocked(original, requireShareScanDirectory(original))
            } catch (_: IOException) {
                false
            } catch (_: SecurityException) {
                false
            }
        }

    fun clearAiWork(): Boolean = synchronized(shareCacheLock) { clearAiWorkLocked() }

    fun saveImages(
        cached: CachedScan,
        album: String,
        isAiCopy: Boolean = false,
    ): List<Uri> {
        require(cached.pages.isNotEmpty()) { "Scan has no pages" }
        val relativePath = "${Environment.DIRECTORY_PICTURES}/${normalizeAlbumName(album)}"
        val saved = mutableListOf<Uri>()
        try {
            cached.pages.forEachIndexed { index, source ->
                val values =
                    pendingValues(
                        scanPageFileName(cached.baseName, index + 1, isAiCopy),
                        JPEG_MIME_TYPE,
                        relativePath,
                    )
                saved += insertPendingFile(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values, source)
            }
            return saved
        } catch (exception: Exception) {
            saved.forEach { deleteMediaRow(it, exception) }
            throw exception
        }
    }

    fun deleteSavedOutputs(uris: List<Uri>): Boolean {
        var deletedAll = true
        uris.forEach { uri ->
            val deleted =
                if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
                    false
                } else {
                    try {
                        when {
                            uri.authority == MediaStore.AUTHORITY ->
                                resolver.delete(uri, null, null) > 0
                            DocumentsContract.isDocumentUri(context, uri) -> deleteSafDocument(uri)
                            else -> false
                        }
                    } catch (_: Exception) {
                        false
                    }
                }
            deletedAll = deleted && deletedAll
        }
        return deletedAll
    }

    fun savePdf(
        source: File,
        baseName: String,
        album: String,
        pdfTreeUri: String?,
        isAiCopy: Boolean = false,
    ): Pair<Uri, UiMessage?> {
        requireReadableFile(source)
        val displayName = scanPdfFileName(baseName, isAiCopy)
        if (pdfTreeUri != null) {
            val (savedToTree, cleanupFailed) = savePdfToTree(source, displayName, pdfTreeUri)
            if (savedToTree != null) {
                return savedToTree to null
            }
            val warning =
                UiMessage(
                    if (cleanupFailed) {
                        R.string.saf_incomplete_warning
                    } else {
                        R.string.saf_fallback_warning
                    },
                )
            return savePdfToDownloads(source, displayName, album) to warning
        }
        return savePdfToDownloads(source, displayName, album) to null
    }

    fun createPdf(pageFiles: List<File>, outputFile: File): File {
        require(pageFiles.isNotEmpty()) { "PDF needs at least one page" }
        require(!outputFile.exists()) { "PDF output already exists" }
        val parent = outputFile.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw IOException("PDF output directory could not be created")
        }

        try {
            val document = PdfDocument()
            try {
                val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                pageFiles.forEachIndexed { index, source ->
                    requireReadableFile(source)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(source.path, bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                        throw IOException("Scan page dimensions could not be read")
                    }
                    val options =
                        BitmapFactory.Options().apply {
                            inSampleSize = pdfPageSampleSize(bounds.outWidth, bounds.outHeight)
                        }
                    val bitmap =
                        BitmapFactory.decodeFile(source.path, options)
                            ?: throw IOException("Scan page could not be decoded")
                    try {
                        val pageInfo = PdfDocument.PageInfo.Builder(A4_WIDTH, A4_HEIGHT, index + 1).create()
                        val page = document.startPage(pageInfo)
                        try {
                            page.canvas.drawColor(Color.WHITE)
                            val placement = fitRect(bitmap.width, bitmap.height, A4_WIDTH, A4_HEIGHT)
                            page.canvas.drawBitmap(
                                bitmap,
                                null,
                                RectF(
                                    placement.left,
                                    placement.top,
                                    placement.right,
                                    placement.bottom,
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
                FileOutputStream(outputFile).use(document::writeTo)
            } finally {
                document.close()
            }
            if (outputFile.length() <= 0L) {
                throw IOException("Created PDF is empty")
            }
            return outputFile
        } catch (failure: Throwable) {
            deleteFileOrSuppress(outputFile, failure)
            throw failure
        }
    }

    fun loadThumbnail(firstPage: File, maxSize: Int = DEFAULT_THUMBNAIL_SIZE): Bitmap? {
        if (!firstPage.isFile || firstPage.length() <= 0L) {
            return null
        }
        require(maxSize > 0) { "Maximum thumbnail size must be positive" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(firstPage.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }
        val options =
            BitmapFactory.Options().apply {
                inSampleSize = thumbnailSampleSize(bounds.outWidth, bounds.outHeight, maxSize)
            }
        return BitmapFactory.decodeFile(firstPage.path, options)
    }

    private fun prepareShareCache(): File {
        val root = shareCacheRoot()
        if (root.exists()) {
            if (!root.isDirectory) {
                throw IOException("Share cache path is not a directory")
            }
        } else if (!root.mkdirs()) {
            throw IOException("Share cache directory could not be created")
        }
        val children = root.listFiles() ?: throw IOException("Share cache could not be listed")
        val safeChildren = children.map { child ->
            val absoluteChild = child.absoluteFile
            if (absoluteChild.canonicalFile != absoluteChild || absoluteChild.parentFile != root) {
                throw IOException("Unsafe share cache entry was not deleted")
            }
            absoluteChild
        }
        // ponytail: eight cached scans cover normal mail drafts; add persistent attachments only if measured usage needs longer retention.
        shareCacheEntriesToPrune(safeChildren, MAX_SHARE_CACHE_SCANS - 1).forEach { child ->
            if (!child.deleteRecursively()) {
                throw IOException("Old share cache could not be deleted")
            }
        }
        return root
    }

    private fun requireShareScanDirectory(cached: CachedScan): File {
        val root = shareCacheRoot()
        val directory = cached.pdf.parentFile?.absoluteFile
            ?: throw IOException("Scan cache has no directory")
        if (
            directory.canonicalFile != directory ||
                directory.parentFile != root ||
                directory.name != cached.baseName ||
                cached.pages.plus(cached.pdf).any {
                    val file = it.absoluteFile
                    file.canonicalFile != file || file.parentFile != directory
                }
        ) {
            throw IOException("Scan cache path is unsafe")
        }
        return directory
    }

    private fun requireAiWork(work: CachedScan) {
        val root = aiWorkRoot()
        val expectedPages =
            work.pages.indices.map { index ->
                File(root, scanPageFileName(work.baseName, index + 1, isAiCopy = true))
            }
        val expectedPdf = File(root, scanPdfFileName(work.baseName, isAiCopy = true))
        if (
            work.pages.map(File::getAbsoluteFile) != expectedPages ||
                work.pdf.absoluteFile != expectedPdf ||
                work.pages.plus(work.pdf).any { it.canonicalFile != it.absoluteFile }
        ) {
            throw IOException("AI work cache path is unsafe")
        }
    }

    private fun deleteAiCachedCopyLocked(original: CachedScan, directory: File): Boolean {
        val files =
            original.pages.indices.map { index ->
                File(
                    directory,
                    scanPageFileName(original.baseName, index + 1, isAiCopy = true),
                )
            } + File(directory, scanPdfFileName(original.baseName, isAiCopy = true))
        var deletedAll = true
        files.forEach { file ->
            val target = file.absoluteFile
            val deleted =
                try {
                    target.canonicalFile == target && target.parentFile == directory &&
                        (!target.exists() || target.delete())
                } catch (_: IOException) {
                    false
                } catch (_: SecurityException) {
                    false
                }
            deletedAll = deleted && deletedAll
        }
        return deletedAll
    }

    private fun clearAiWorkLocked(): Boolean {
        val root = aiWorkRoot()
        return root.canonicalFile == root && (!root.exists() || root.deleteRecursively())
    }

    private fun aiWorkRoot(): File {
        val cacheRoot = context.cacheDir.canonicalFile
        val root = File(cacheRoot, AI_WORK_DIRECTORY).absoluteFile
        if (root.canonicalFile != root || root.parentFile != cacheRoot) {
            throw IOException("AI work cache path is unsafe")
        }
        return root
    }

    private fun shareCacheRoot(): File {
        val cacheRoot = context.cacheDir.canonicalFile
        val root = File(cacheRoot, "share").absoluteFile
        if (root.canonicalFile != root || root.parentFile != cacheRoot) {
            throw IOException("Share cache path is unsafe")
        }
        return root
    }

    private fun copyFile(source: File, destination: File) {
        requireReadableFile(source)
        if (destination.exists()) {
            throw IOException("AI cache destination already exists")
        }
        try {
            val copied = source.inputStream().use { input ->
                FileOutputStream(destination).use(input::copyTo)
            }
            if (copied <= 0L || destination.length() != source.length()) {
                throw IOException("AI cache copy is incomplete")
            }
        } catch (failure: Throwable) {
            deleteFileOrSuppress(destination, failure)
            throw failure
        }
    }

    private fun deleteRecursivelyOrSuppress(target: File, failure: Throwable) {
        try {
            if (target.exists() && !target.deleteRecursively()) {
                failure.addSuppressed(IOException("Incomplete scan cache could not be deleted"))
            }
        } catch (cleanupFailure: Exception) {
            failure.addSuppressed(cleanupFailure)
        }
    }

    private fun deleteFileOrSuppress(target: File, failure: Throwable) {
        try {
            if (target.exists() && !target.delete()) {
                failure.addSuppressed(IOException("Incomplete PDF could not be deleted"))
            }
        } catch (cleanupFailure: Exception) {
            failure.addSuppressed(cleanupFailure)
        }
    }

    private fun savePdfToDownloads(source: File, displayName: String, album: String): Uri {
        val values =
            pendingValues(
                displayName,
                PDF_MIME_TYPE,
                "${Environment.DIRECTORY_DOWNLOADS}/${normalizeAlbumName(album)}",
            )
        return insertPendingFile(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values, source)
    }

    private fun savePdfToTree(
        source: File,
        displayName: String,
        treeUriValue: String,
    ): Pair<Uri?, Boolean> {
        var createdDocument: Uri? = null
        try {
            val treeUri = Uri.parse(treeUriValue)
            if (
                !DocumentsContract.isTreeUri(treeUri) ||
                    resolver.persistedUriPermissions.none {
                        it.uri == treeUri && it.isWritePermission
                    }
            ) {
                return null to false
            }
            val parent =
                DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    DocumentsContract.getTreeDocumentId(treeUri),
                )
            createdDocument =
                DocumentsContract.createDocument(resolver, parent, PDF_MIME_TYPE, displayName)
                    ?: return null to false
            copyFileToUri(source, createdDocument)
            return createdDocument to false
        } catch (cancellation: CancellationException) {
            createdDocument?.let { document ->
                val deleted =
                    try {
                        deleteSafDocument(document)
                    } catch (cleanupCancellation: CancellationException) {
                        if (cleanupCancellation !== cancellation) {
                            cancellation.addSuppressed(cleanupCancellation)
                        }
                        false
                    }
                if (!deleted) {
                    cancellation.addSuppressed(
                        IOException("Incomplete SAF document could not be deleted"),
                    )
                }
            }
            throw cancellation
        } catch (failure: Exception) {
            val cleanupFailed =
                createdDocument?.let { document ->
                    try {
                        !deleteSafDocument(document)
                    } catch (cleanupCancellation: CancellationException) {
                        cleanupCancellation.addSuppressed(failure)
                        throw cleanupCancellation
                    }
                } ?: false
            return null to cleanupFailed
        }
    }

    private fun pendingValues(displayName: String, mimeType: String, relativePath: String) =
        ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

    private fun insertPendingFile(collection: Uri, values: ContentValues, source: File): Uri {
        requireReadableFile(source)
        val destination =
            resolver.insert(collection, values)
                ?: throw IOException("MediaStore row could not be created")
        try {
            copyFileToUri(source, destination)
            val published =
                resolver.update(
                    destination,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
            if (published != 1) {
                throw IOException("MediaStore row could not be published")
            }
            return destination
        } catch (exception: Exception) {
            deleteMediaRow(destination, exception)
            throw exception
        }
    }

    private fun copyUriToFile(source: Uri, destination: File) {
        val input =
            resolver.openInputStream(source)
                ?: throw IOException("Source URI could not be opened")
        val copied = input.use { stream ->
            FileOutputStream(destination).use(stream::copyTo)
        }
        if (copied <= 0L || destination.length() <= 0L) {
            throw IOException("Source URI is empty")
        }
    }

    private fun copyFileToUri(source: File, destination: Uri) {
        requireReadableFile(source)
        val output =
            resolver.openOutputStream(destination, "w")
                ?: throw IOException("Destination URI could not be opened")
        val copied = output.use { stream -> source.inputStream().use { it.copyTo(stream) } }
        if (copied <= 0L) {
            throw IOException("Destination URI is empty")
        }
        val size = querySize(destination)
        if (size == 0L || ((size == null || size < 0L) && !destinationHasData(destination))) {
            throw IOException("Destination URI is empty")
        }
    }

    private fun querySize(uri: Uri): Long? =
        try {
            resolver
                .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst() || cursor.isNull(0)) null else cursor.getLong(0)
                }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }

    private fun destinationHasData(uri: Uri): Boolean {
        val input = resolver.openInputStream(uri) ?: return false
        return input.use { it.read() != -1 }
    }

    private fun requireReadableFile(file: File) {
        if (!file.isFile || file.length() <= 0L) {
            throw IOException("Source file is missing or empty")
        }
    }

    private fun deleteMediaRow(uri: Uri, failure: Exception) {
        try {
            if (resolver.delete(uri, null, null) <= 0) {
                failure.addSuppressed(IOException("Incomplete MediaStore row could not be deleted"))
            }
        } catch (cleanupFailure: Exception) {
            failure.addSuppressed(cleanupFailure)
        }
    }

    private fun deleteSafDocument(uri: Uri): Boolean =
        try {
            if (!DocumentsContract.deleteDocument(resolver, uri)) {
                resolver.delete(uri, null, null) > 0
            } else {
                true
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            false
        }
}
