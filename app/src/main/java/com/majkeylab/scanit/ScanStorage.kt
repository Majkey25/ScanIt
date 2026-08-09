package com.majkeylab.scanit

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.UUID
import kotlin.math.min

private const val DEFAULT_THUMBNAIL_SIZE = 1024
private const val PDF_MAX_BITMAP_SIDE = 3508
private const val PDF_MIME_TYPE = "application/pdf"
private const val JPEG_MIME_TYPE = "image/jpeg"
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
): String {
    require(baseName.isNotBlank()) { "Scan base name must not be blank" }
    require(pageNumber > 0) { "Page number must be positive" }
    val page = pageNumber.toString().padStart(2, '0')
    return "${baseName}_${page}.jpg"
}

internal fun scanPdfFileName(baseName: String): String {
    require(baseName.isNotBlank()) { "Scan base name must not be blank" }
    return "$baseName.pdf"
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
                .thenBy { it.name },
        ).drop(keep)
}

private data class ParsedCacheEntry(
    val directory: File,
    val recent: RecentScan,
    val cached: CachedScan,
)

internal fun nextDerivedCacheId(
    sourceCacheId: String,
    suffix: String,
    existingCacheIds: Set<String>,
): String {
    require(isSafeCacheId(sourceCacheId)) { "Source cache ID is unsafe" }
    require(isSafeCacheId(suffix)) { "Derived cache suffix is unsafe" }
    val base = "${sourceCacheId}_$suffix"
    require(isSafeCacheId(base)) { "Derived cache ID is unsafe" }
    if (base !in existingCacheIds) return base
    var number = 2
    while (number < Int.MAX_VALUE) {
        val candidate = "${base}_$number"
        if (candidate !in existingCacheIds) return candidate
        number++
    }
    throw IOException("No derived cache ID is available")
}

internal fun openCachedScanInRoot(root: File, cacheId: String): CachedScan? {
    if (!isSafeCacheId(cacheId)) return null
    val safeRoot = ensureShareRoot(root)
    val directory = File(safeRoot, cacheId).absoluteFile
    return readCacheEntry(safeRoot, directory, cacheId)?.cached
}

internal fun deleteRecentScanInRoot(root: File, cacheId: String): Boolean {
    if (!isSafeCacheId(cacheId)) return false
    val safeRoot = ensureShareRoot(root)
    val directory = File(safeRoot, cacheId).absoluteFile
    if (readCacheEntry(safeRoot, directory, cacheId) == null) {
        return !Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)
    }
    return deleteTreeWithoutFollowingLinks(directory)
}

internal fun listRecentScansInRoot(
    root: File,
    protectedCacheIds: Set<String> = emptySet(),
    maxEntries: Int = MAX_SHARE_CACHE_SCANS,
): List<RecentScan> {
    require(maxEntries >= 0) { "Recent scan retention must not be negative" }
    require(protectedCacheIds.all(::isSafeCacheId)) { "Protected cache ID is unsafe" }
    val safeRoot = ensureShareRoot(root)
    cleanPendingDirectories(safeRoot)
    return pruneRecentEntries(safeRoot, protectedCacheIds, maxEntries).map(ParsedCacheEntry::recent)
}

internal fun publishCacheEntryInRoot(
    root: File,
    workDir: File,
    finalDir: File,
    protectedCacheIds: Set<String> = emptySet(),
    maxEntries: Int = MAX_SHARE_CACHE_SCANS,
    moveEntry: (Path, Path) -> Unit = ::moveCacheEntryAtomically,
): CachedScan {
    require(maxEntries > 0) { "Recent scan retention must be positive for publishing" }
    require(protectedCacheIds.all(::isSafeCacheId)) { "Protected cache ID is unsafe" }
    val safeRoot = ensureShareRoot(root)
    val work = workDir.absoluteFile
    val final = finalDir.absoluteFile
    val cacheId = final.name
    if (
        !isSafeCacheId(cacheId) ||
            !isDirectChild(safeRoot, final) ||
            final.exists() ||
            !isPendingDirectory(safeRoot, work)
    ) {
        throw IOException("Cache publication path is unsafe")
    }

    val stagedEntries = mutableListOf<Pair<File, File>>()
    var published = false
    try {
        val entriesToPrune = entriesToPrune(safeRoot, protectedCacheIds, maxEntries - 1)
        readCacheEntry(safeRoot, work, cacheId)
            ?: throw IOException("Pending cache entry is incomplete")
        cleanPendingDirectories(safeRoot, work)
        moveEntry(work.toPath(), final.toPath())
        published = true
        val cached = readCacheEntry(safeRoot, final, cacheId)?.cached
            ?: throw IOException("Published cache entry is incomplete")
        entriesToPrune.forEach { entry ->
            if (readCacheEntry(safeRoot, entry.directory, entry.recent.cacheId) == null) {
                if (!entry.directory.exists()) return@forEach
                throw IOException("Recent scan changed before pruning")
            }
            val staged = File(safeRoot, ".pending-prune-${UUID.randomUUID()}")
            moveEntry(entry.directory.toPath(), staged.toPath())
            stagedEntries += entry.directory to staged
        }
        stagedEntries.forEach { (_, staged) -> deleteTreeWithoutFollowingLinks(staged) }
        return cached
    } catch (failure: Throwable) {
        stagedEntries.asReversed().forEach { (original, staged) ->
            try {
                moveCacheEntryAtomically(staged.toPath(), original.toPath())
            } catch (restoreFailure: Exception) {
                failure.addSuppressed(restoreFailure)
            }
        }
        val cleanupTarget = if (published || (!work.exists() && final.exists())) final else work
        if (
            isDirectChild(safeRoot, cleanupTarget) &&
                cleanupTarget.exists() &&
                !deleteTreeWithoutFollowingLinks(cleanupTarget)
        ) {
            failure.addSuppressed(IOException("Incomplete cache publication could not be deleted"))
        }
        throw failure
    }
}

private fun pruneRecentEntries(
    root: File,
    protectedCacheIds: Set<String>,
    maxEntries: Int,
): List<ParsedCacheEntry> {
    val entriesToPrune = entriesToPrune(root, protectedCacheIds, maxEntries)
    entriesToPrune.forEach { entry ->
        if (!deleteRecentScanInRoot(root, entry.recent.cacheId)) {
            throw IOException("Old recent scan could not be deleted")
        }
    }
    return readCacheEntries(root)
}

private fun entriesToPrune(
    root: File,
    protectedCacheIds: Set<String>,
    maxEntries: Int,
): List<ParsedCacheEntry> {
    val entries = readCacheEntries(root)
    val protectedEntries = entries.filter { it.recent.cacheId in protectedCacheIds }
    if (protectedEntries.size > maxEntries) {
        throw IOException("Protected recent scans exceed cache capacity")
    }
    val unprotected = entries.filterNot { it.recent.cacheId in protectedCacheIds }
    val unprotectedToKeep = maxEntries - protectedEntries.size
    val directoriesToDelete =
        shareCacheEntriesToPrune(unprotected.map(ParsedCacheEntry::directory), unprotectedToKeep)
            .toSet()
    return entries.filter { it.directory in directoriesToDelete }
}

private fun moveCacheEntryAtomically(source: Path, target: Path) {
    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
}

private fun readCacheEntries(root: File): List<ParsedCacheEntry> {
    val children = root.listFiles() ?: throw IOException("Share cache could not be listed")
    return children
        .mapNotNull { child -> readCacheEntry(root, child.absoluteFile, child.name) }
        .sortedWith(
            compareByDescending<ParsedCacheEntry> { it.recent.createdAt }
                .thenBy { it.recent.cacheId },
        )
}

private fun readCacheEntry(
    root: File,
    directory: File,
    cacheId: String,
): ParsedCacheEntry? {
    if (
        !isSafeCacheId(cacheId) ||
            !isDirectChild(root, directory) ||
            !Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)
    ) {
        return null
    }
    val children = directory.listFiles() ?: return null
    val pagePattern = Regex("${Regex.escape(cacheId)}_([0-9]+)\\.jpg")
    val pagesByNumber = mutableMapOf<Int, File>()
    var pdf: File? = null
    children.forEach { child ->
        val file = child.absoluteFile
        if (
            !isDirectChild(directory, file) ||
                !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                file.length() <= 0L
        ) {
            return null
        }
        when {
            file.name == scanPdfFileName(cacheId) -> {
                if (pdf != null) return null
                pdf = file
            }
            else -> {
                val match = pagePattern.matchEntire(file.name) ?: return null
                val pageNumber = match.groupValues[1].toIntOrNull() ?: return null
                if (
                    pageNumber <= 0 ||
                        file.name != scanPageFileName(cacheId, pageNumber) ||
                        pagesByNumber.put(pageNumber, file) != null
                ) {
                    return null
                }
            }
        }
    }
    val orderedPages = pagesByNumber.toSortedMap().values.toList()
    if (
        pdf == null ||
            orderedPages.isEmpty() ||
            pagesByNumber.keys.sorted() != (1..orderedPages.size).toList()
    ) {
        return null
    }
    val exactPdf = pdf
    val recent =
        RecentScan(
            cacheId = cacheId,
            displayName = cacheId,
            createdAt = Instant.ofEpochMilli(directory.lastModified()),
            pageCount = orderedPages.size,
            pdfBytes = exactPdf.length(),
            firstPage = orderedPages.first(),
        )
    return ParsedCacheEntry(
        directory = directory,
        recent = recent,
        cached = CachedScan(cacheId, orderedPages, exactPdf),
    )
}

private fun cleanPendingDirectories(root: File, keep: File? = null) {
    val children = root.listFiles() ?: throw IOException("Share cache could not be listed")
    children.filter { child ->
        child.absoluteFile != keep?.absoluteFile && isPendingDirectory(root, child.absoluteFile)
    }.forEach { child ->
        if (!deleteTreeWithoutFollowingLinks(child)) {
            throw IOException("Stale pending cache entry could not be deleted")
        }
    }
}

private fun isPendingDirectory(root: File, directory: File): Boolean =
    directory.name.startsWith(".pending-") &&
        directory.name.length > ".pending-".length &&
        isDirectChild(root, directory) &&
        Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)

private fun isSafeCacheId(cacheId: String): Boolean =
    cacheId.isNotBlank() &&
        cacheId != "." &&
        cacheId != ".." &&
        !cacheId.startsWith('.') &&
        !File(cacheId).isAbsolute &&
        cacheId.none { character ->
            character == '/' ||
                character == '\\' ||
                character == ':' ||
                character.isISOControl()
        }

private fun ensureShareRoot(root: File): File {
    val absoluteRoot = root.absoluteFile
    if (absoluteRoot.exists()) {
        if (
            absoluteRoot.canonicalFile != absoluteRoot ||
                !Files.isDirectory(absoluteRoot.toPath(), LinkOption.NOFOLLOW_LINKS)
        ) {
            throw IOException("Share cache path is unsafe")
        }
    } else if (!absoluteRoot.mkdirs()) {
        throw IOException("Share cache directory could not be created")
    }
    if (absoluteRoot.canonicalFile != absoluteRoot) {
        throw IOException("Share cache path is unsafe")
    }
    return absoluteRoot
}

private fun isDirectChild(parent: File, child: File): Boolean =
    try {
        val absoluteChild = child.absoluteFile
        absoluteChild.parentFile == parent && absoluteChild.canonicalFile == absoluteChild
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

private fun deleteTreeWithoutFollowingLinks(directory: File): Boolean =
    try {
        Files.walkFileTree(
            directory.toPath(),
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(directory: Path, failure: IOException?): FileVisitResult {
                    if (failure != null) throw failure
                    Files.delete(directory)
                    return FileVisitResult.CONTINUE
                }
            },
        )
        !Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

internal class RecentScanCache(
    private val root: File,
    private val lock: Any = Any(),
) {
    fun list(
        protectedCacheIds: Set<String> = emptySet(),
        maxEntries: Int = MAX_SHARE_CACHE_SCANS,
    ): List<RecentScan> =
        synchronized(lock) {
            listRecentScansInRoot(root, protectedCacheIds, maxEntries)
        }

    fun open(cacheId: String): CachedScan? =
        synchronized(lock) {
            openCachedScanInRoot(root, cacheId)
        }

    fun delete(cacheId: String): Boolean =
        synchronized(lock) {
            deleteRecentScanInRoot(root, cacheId)
        }

    fun nextDerivedCacheId(sourceCacheId: String, suffix: String): String =
        synchronized(lock) {
            val safeRoot = ensureShareRoot(root)
            val children = safeRoot.listFiles() ?: throw IOException("Share cache could not be listed")
            nextDerivedCacheId(sourceCacheId, suffix, children.mapTo(mutableSetOf(), File::getName))
        }

    fun publish(
        workDir: File,
        finalDir: File,
        protectedCacheIds: Set<String> = emptySet(),
        maxEntries: Int = MAX_SHARE_CACHE_SCANS,
        moveEntry: (Path, Path) -> Unit = ::moveCacheEntryAtomically,
    ): CachedScan =
        synchronized(lock) {
            publishCacheEntryInRoot(
                root,
                workDir,
                finalDir,
                protectedCacheIds,
                maxEntries,
                moveEntry,
            )
        }
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
    private val recentScanCache by lazy { RecentScanCache(shareCacheRoot(), shareCacheLock) }

    fun cacheScan(pageUris: List<Uri>, pdfUri: Uri): CachedScan =
        synchronized(shareCacheLock) {
            require(pageUris.isNotEmpty()) { "Scanner returned no pages" }
            val baseName = scanBaseName(clock)
            val shareRoot = ensureShareRoot(shareCacheRoot())
            val finalDirectory = File(shareRoot, baseName)
            val workDirectory = File(shareRoot, ".pending-${UUID.randomUUID()}")
            if (!workDirectory.mkdir()) {
                throw IOException("Pending scan cache directory could not be created")
            }

            try {
                pageUris.forEachIndexed { index, uri ->
                    File(workDirectory, scanPageFileName(baseName, index + 1)).also {
                        copyUriToFile(uri, it)
                    }
                }
                File(workDirectory, scanPdfFileName(baseName)).also {
                    copyUriToFile(pdfUri, it)
                }
                recentScanCache.publish(workDirectory, finalDirectory)
            } catch (failure: Throwable) {
                deleteRecursivelyOrSuppress(workDirectory, failure)
                throw failure
            }
        }

    fun listRecentScans(protectedCacheIds: Set<String> = emptySet()): List<RecentScan> =
        recentScanCache.list(protectedCacheIds)

    fun openCachedScan(cacheId: String): CachedScan? =
        recentScanCache.open(cacheId)

    fun deleteRecentScan(cacheId: String): Boolean =
        recentScanCache.delete(cacheId)

    fun nextDerivedCacheId(sourceCacheId: String, suffix: String): String =
        recentScanCache.nextDerivedCacheId(sourceCacheId, suffix)

    fun publishCacheEntry(
        workDir: File,
        finalDir: File,
        protectedCacheIds: Set<String> = emptySet(),
    ): CachedScan =
        recentScanCache.publish(workDir, finalDir, protectedCacheIds)

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
                    !directory.exists() || deleteRecentScanInRoot(root, cached.baseName)
                }
            } catch (_: IOException) {
                false
            } catch (_: SecurityException) {
                false
            }
        }

    fun saveImages(
        cached: CachedScan,
        album: String,
    ): List<Uri> {
        require(cached.pages.isNotEmpty()) { "Scan has no pages" }
        val relativePath = "${Environment.DIRECTORY_PICTURES}/${normalizeAlbumName(album)}"
        val saved = mutableListOf<Uri>()
        try {
            cached.pages.forEachIndexed { index, source ->
                val values =
                    pendingValues(
                        scanPageFileName(cached.baseName, index + 1),
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
    ): Pair<Uri, UiMessage?> {
        requireReadableFile(source)
        val displayName = scanPdfFileName(baseName)
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

    private fun shareCacheRoot(): File {
        val cacheRoot = context.cacheDir.canonicalFile
        val root = File(cacheRoot, "share").absoluteFile
        if (root.canonicalFile != root || root.parentFile != cacheRoot) {
            throw IOException("Share cache path is unsafe")
        }
        return root
    }

    private fun deleteRecursivelyOrSuppress(target: File, failure: Throwable) {
        try {
            if (target.exists() && !deleteTreeWithoutFollowingLinks(target)) {
                failure.addSuppressed(IOException("Incomplete scan cache could not be deleted"))
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
