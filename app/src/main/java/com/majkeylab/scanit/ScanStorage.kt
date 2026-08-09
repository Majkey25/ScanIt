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
import androidx.core.net.toUri
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
private const val RECOVERY_PENDING_PREFIX = ".pending-recovery-"
private const val DELETE_PENDING_PREFIX = ".pending-delete-"
private const val COMMITTED_PRUNE_PREFIX = ".committed-prune-"

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
    maintainPendingDirectories(safeRoot)
    val directory = File(safeRoot, cacheId).absoluteFile
    return readCacheEntry(safeRoot, directory, cacheId)?.cached
}

internal fun deleteRecentScanInRoot(root: File, cacheId: String): Boolean {
    if (!isSafeCacheId(cacheId)) return false
    val safeRoot = ensureShareRoot(root)
    maintainPendingDirectories(safeRoot)
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
    maintainPendingDirectories(safeRoot)
    return pruneRecentEntries(safeRoot, protectedCacheIds, maxEntries).map(ParsedCacheEntry::recent)
}

private fun publishCacheEntryInRoot(
    root: File,
    workDir: File,
    finalDir: File,
    protectedCacheIds: Set<String> = emptySet(),
    maxEntries: Int = MAX_SHARE_CACHE_SCANS,
    moveEntry: (Path, Path) -> Unit,
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
            !isCreatePendingDirectory(safeRoot, work)
    ) {
        throw IOException("Cache publication path is unsafe")
    }

    val recoveryDirectory = File(safeRoot, "$RECOVERY_PENDING_PREFIX$cacheId")
    val deleteDirectory = File(safeRoot, "$DELETE_PENDING_PREFIX$cacheId")
    val commitMarker = File(safeRoot, "$COMMITTED_PRUNE_PREFIX$cacheId")
    var published = false
    var committed = false
    try {
        maintainPendingDirectories(safeRoot, work)
        val entriesToPrune = entriesToPrune(safeRoot, protectedCacheIds, maxEntries - 1)
        val pending =
            readCacheEntry(safeRoot, work, cacheId)
                ?: throw IOException("Pending cache entry is incomplete")
        ensureOutputMetadata(
            directory = work,
            cacheId = cacheId,
            pageCount = pending.cached.pages.size,
            createdAtEpochMs = System.currentTimeMillis(),
        )
        readCacheEntry(safeRoot, work, cacheId)
            ?: throw IOException("Pending cache metadata is incomplete")
        cleanDisposablePendingDirectories(safeRoot, work)
        moveEntry(work.toPath(), final.toPath())
        published = true
        val cached = readCacheEntry(safeRoot, final, cacheId)?.cached
            ?: throw IOException("Published cache entry is incomplete")
        if (entriesToPrune.isNotEmpty()) {
            if (
                recoveryDirectory.exists() ||
                    deleteDirectory.exists() ||
                    commitMarker.exists() ||
                    !recoveryDirectory.mkdir()
            ) {
                throw IOException("Recent scan recovery directory could not be created")
            }
            entriesToPrune.forEach { entry ->
                if (readCacheEntry(safeRoot, entry.directory, entry.recent.cacheId) == null) {
                    if (!entry.directory.exists()) return@forEach
                    throw IOException("Recent scan changed before pruning")
                }
                moveEntry(
                    entry.directory.toPath(),
                    File(recoveryDirectory, entry.recent.cacheId).toPath(),
                )
            }
            moveEntry(recoveryDirectory.toPath(), deleteDirectory.toPath())
            if (!commitMarker.createNewFile()) {
                throw IOException("Recent scan prune could not be committed")
            }
            committed = true
            deleteCommittedPrune(deleteDirectory, commitMarker)
        }
        return cached
    } catch (failure: Throwable) {
        var reportedFailure = failure
        if (!committed) {
            val recoverySource =
                when {
                    recoveryDirectory.exists() -> recoveryDirectory
                    deleteDirectory.exists() -> deleteDirectory
                    else -> null
                }
            try {
                recoverySource?.let {
                    restoreRecoveryDirectory(safeRoot, it, cacheId, moveEntry)
                }
            } catch (restoreFailure: Exception) {
                reportedFailure =
                    IOException("Recent scan recovery is pending", restoreFailure).also {
                        it.addSuppressed(failure)
                    }
            }
        }
        val cleanupTarget = if (published || (!work.exists() && final.exists())) final else work
        if (
            isDirectChild(safeRoot, cleanupTarget) &&
                cleanupTarget.exists() &&
                !deleteTreeWithoutFollowingLinks(cleanupTarget)
        ) {
            reportedFailure.addSuppressed(
                IOException("Incomplete cache publication could not be deleted"),
            )
        }
        throw reportedFailure
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
                !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        ) {
            return null
        }
        when (file.name) {
            OUTPUT_METADATA_FILE_NAME,
            OUTPUT_METADATA_TEMP_FILE_NAME,
            -> Unit
            scanPdfFileName(cacheId) -> {
                if (file.length() <= 0L) return null
                if (pdf != null) return null
                pdf = file
            }
            else -> {
                if (file.length() <= 0L) return null
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
    val outputs = readOutputMetadata(directory, cacheId, orderedPages.size)
    val recent =
        RecentScan(
            cacheId = cacheId,
            displayName = cacheId,
            createdAt = Instant.ofEpochMilli(outputs?.createdAtEpochMs ?: directory.lastModified()),
            pageCount = orderedPages.size,
            pdfBytes = exactPdf.length(),
            firstPage = orderedPages.first(),
            entryId = outputs?.entryId,
            hasSavedPdf = outputs?.pdf != null,
            savedImageCount = outputs?.images?.size ?: 0,
        )
    return ParsedCacheEntry(
        directory = directory,
        recent = recent,
        cached = CachedScan(cacheId, orderedPages, exactPdf, entryId = outputs?.entryId),
    )
}

private fun maintainPendingDirectories(root: File, keepCreate: File? = null) {
    recoverPendingDirectories(root)
    cleanDisposablePendingDirectories(root, keepCreate)
}

private fun recoverPendingDirectories(root: File) {
    val children = root.listFiles() ?: throw IOException("Share cache could not be listed")
    children.filter { child ->
        child.name.startsWith(RECOVERY_PENDING_PREFIX) ||
            child.name.startsWith(DELETE_PENDING_PREFIX)
    }.forEach { child ->
        val recovery = child.absoluteFile
        val prefix =
            if (recovery.name.startsWith(RECOVERY_PENDING_PREFIX)) {
                RECOVERY_PENDING_PREFIX
            } else {
                DELETE_PENDING_PREFIX
            }
        val cacheId = recovery.name.removePrefix(prefix)
        if (
            !isSafeCacheId(cacheId) ||
                !isDirectChild(root, recovery) ||
                !Files.isDirectory(recovery.toPath(), LinkOption.NOFOLLOW_LINKS)
        ) {
            throw IOException("Recent scan recovery directory is unsafe")
        }
        val commitMarker = File(root, "$COMMITTED_PRUNE_PREFIX$cacheId").absoluteFile
        if (prefix == DELETE_PENDING_PREFIX && commitMarker.exists()) {
            if (
                !isDirectChild(root, commitMarker) ||
                    !Files.isRegularFile(commitMarker.toPath(), LinkOption.NOFOLLOW_LINKS)
            ) {
                throw IOException("Recent scan commit marker is unsafe")
            }
            return@forEach
        }
        restoreRecoveryDirectory(root, recovery, cacheId, ::moveCacheEntryAtomically)
    }
}

private fun restoreRecoveryDirectory(
    root: File,
    recovery: File,
    publishedCacheId: String,
    moveEntry: (Path, Path) -> Unit,
) {
    val staged = recovery.listFiles() ?: throw IOException("Recent scan recovery could not be listed")
    val entries =
        staged.map { child ->
            val cacheId = child.name
            readCacheEntry(recovery, child.absoluteFile, cacheId)
                ?: throw IOException("Recent scan recovery entry is incomplete")
        }
    val published = File(root, publishedCacheId).absoluteFile
    if (published.exists()) {
        if (!deleteTreeWithoutFollowingLinks(published)) {
            throw IOException("Rolled-back recent scan could not be deleted")
        }
    }
    entries.forEach { entry ->
        val original = File(root, entry.recent.cacheId).absoluteFile
        if (original.exists()) {
            throw IOException("Recent scan recovery destination already exists")
        }
        try {
            moveEntry(entry.directory.toPath(), original.toPath())
        } catch (failure: Exception) {
            throw IOException("Recent scan recovery move failed", failure)
        }
    }
    if (!deleteTreeWithoutFollowingLinks(recovery)) {
        throw IOException("Recent scan recovery directory could not be removed")
    }
}

private fun cleanDisposablePendingDirectories(root: File, keepCreate: File? = null) {
    val children = root.listFiles() ?: throw IOException("Share cache could not be listed")
    children.filter { child ->
        val directory = child.absoluteFile
        val isRecovery = directory.name.startsWith(RECOVERY_PENDING_PREFIX)
        val isDelete = directory.name.startsWith(DELETE_PENDING_PREFIX)
        directory != keepCreate?.absoluteFile &&
            directory.name.startsWith(".pending-") &&
            !isRecovery &&
            !isDelete &&
            isDirectChild(root, directory) &&
            Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)
    }.forEach { directory ->
        if (!deleteTreeWithoutFollowingLinks(directory)) {
            throw IOException("Stale disposable cache entry could not be deleted")
        }
    }
    children.filter { it.name.startsWith(DELETE_PENDING_PREFIX) }.forEach { child ->
        val directory = child.absoluteFile
        val cacheId = directory.name.removePrefix(DELETE_PENDING_PREFIX)
        val commitMarker = File(root, "$COMMITTED_PRUNE_PREFIX$cacheId").absoluteFile
        if (commitMarker.exists() && !deleteCommittedPrune(directory, commitMarker)) {
            throw IOException("Committed recent scan prune could not be deleted")
        }
    }
    val remaining = root.listFiles() ?: throw IOException("Share cache could not be listed")
    remaining.filter { it.name.startsWith(COMMITTED_PRUNE_PREFIX) }.forEach { child ->
        val marker = child.absoluteFile
        val cacheId = marker.name.removePrefix(COMMITTED_PRUNE_PREFIX)
        val deleteDirectory = File(root, "$DELETE_PENDING_PREFIX$cacheId").absoluteFile
        if (
            !isSafeCacheId(cacheId) ||
                !isDirectChild(root, marker) ||
                !Files.isRegularFile(marker.toPath(), LinkOption.NOFOLLOW_LINKS)
        ) {
            throw IOException("Recent scan commit marker is unsafe")
        }
        if (!deleteDirectory.exists()) {
            try {
                Files.delete(marker.toPath())
            } catch (failure: Exception) {
                throw IOException("Stale recent scan commit marker could not be deleted", failure)
            }
        }
    }
}

private fun deleteCommittedPrune(directory: File, commitMarker: File): Boolean =
    try {
        if (directory.exists() && !deleteTreeWithoutFollowingLinks(directory)) return false
        Files.deleteIfExists(commitMarker.toPath())
        true
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

private fun isCreatePendingDirectory(root: File, directory: File): Boolean =
    directory.name.startsWith(".pending-") &&
        !directory.name.startsWith(RECOVERY_PENDING_PREFIX) &&
        !directory.name.startsWith(DELETE_PENDING_PREFIX) &&
        directory.name.length > ".pending-".length &&
        isDirectChild(root, directory) &&
        Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)

internal fun isSafeCacheId(cacheId: String): Boolean =
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
    private val moveEntry: (Path, Path) -> Unit = ::moveCacheEntryAtomically,
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
            maintainPendingDirectories(safeRoot)
            val children = safeRoot.listFiles() ?: throw IOException("Share cache could not be listed")
            nextDerivedCacheId(sourceCacheId, suffix, children.mapTo(mutableSetOf(), File::getName))
        }

    fun publish(
        workDir: File,
        finalDir: File,
        protectedCacheIds: Set<String> = emptySet(),
        maxEntries: Int = MAX_SHARE_CACHE_SCANS,
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
            val workDirectory = File(shareRoot, ".pending-create-${UUID.randomUUID()}")
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
                initializeOutputMetadata(
                    directory = workDirectory,
                    cacheId = baseName,
                    pageCount = pageUris.size,
                    createdAtEpochMs = clock.millis(),
                )
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

    fun openSavedScan(cacheId: String): SavedScan? =
        synchronized(shareCacheLock) {
            val cached = recentScanCache.open(cacheId) ?: return@synchronized null
            val outputs =
                cached.entryId?.let { entryId ->
                    readOutputMetadata(
                        File(shareCacheRoot(), cacheId),
                        cacheId,
                        cached.pages.size,
                    )?.takeIf { it.entryId == entryId }
                }
            SavedScan(
                cached = cached,
                galleryPages = outputs?.images?.map { it.uri.toUri() }.orEmpty(),
                savedPdf = outputs?.pdf?.uri?.toUri(),
                savedPdfTree = outputs?.pdf?.treeUri?.toUri(),
            )
        }

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
    ): List<Uri> =
        synchronized(shareCacheLock) {
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
                    saved +=
                        insertPendingFile(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            values,
                            source,
                        )
                }
                rewriteCachedOutputMetadata(cached) { metadata ->
                    metadata.copy(
                        images =
                            saved.mapIndexed { index, uri ->
                                ImageOutputRef(page = index + 1, uri = uri.toString())
                            },
                    )
                }
                saved.toList()
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
        cached: CachedScan,
        album: String,
        pdfTreeUri: String?,
    ): SavedPdfOutput =
        synchronized(shareCacheLock) {
            requireReadableFile(cached.pdf)
            val displayName = scanPdfFileName(cached.baseName)
            var failureWarning: UiMessage? = null
            var createdOutput: SavedPdfOutput? = null
            try {
                val output =
                    if (pdfTreeUri != null) {
                        val (savedToTree, cleanupFailed) =
                            savePdfToTree(cached.pdf, displayName, pdfTreeUri)
                        if (savedToTree != null) {
                            SavedPdfOutput(savedToTree, pdfTreeUri.toUri(), warning = null)
                        } else {
                            failureWarning =
                                safFallbackWarning(cleanupFailed, savedToDownloads = false)
                            SavedPdfOutput(
                                savePdfToDownloads(cached.pdf, displayName, album),
                                treeUri = null,
                                warning =
                                    safFallbackWarning(cleanupFailed, savedToDownloads = true),
                            )
                        }
                    } else {
                        SavedPdfOutput(
                            savePdfToDownloads(cached.pdf, displayName, album),
                            treeUri = null,
                            warning = null,
                        )
                    }
                createdOutput = output
                rewriteCachedOutputMetadata(cached) { metadata ->
                    metadata.copy(
                        pdf =
                            PdfOutputRef(
                                uri = output.uri.toString(),
                                treeUri = output.treeUri?.toString(),
                            ),
                    )
                }
                output
            } catch (cancellation: CancellationException) {
                createdOutput?.let { deleteCreatedOutput(it.uri, cancellation) }
                throw cancellation
            } catch (exception: Exception) {
                createdOutput?.let { deleteCreatedOutput(it.uri, exception) }
                throw PdfSaveFailure(failureWarning, exception)
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

    private fun shareCacheRoot(): File {
        val cacheRoot = context.cacheDir.canonicalFile
        val root = File(cacheRoot, "share").absoluteFile
        if (root.canonicalFile != root || root.parentFile != cacheRoot) {
            throw IOException("Share cache path is unsafe")
        }
        return root
    }

    private fun rewriteCachedOutputMetadata(
        cached: CachedScan,
        update: (OutputMetadata) -> OutputMetadata,
    ): OutputMetadata {
        val entryId = cached.entryId ?: throw IOException("Cached scan output metadata is unavailable")
        val root = shareCacheRoot()
        val current = openCachedScanInRoot(root, cached.baseName)
            ?: throw IOException("Cached scan is unavailable")
        if (
            current.entryId != entryId ||
                current.pdf != cached.pdf ||
                current.pages != cached.pages
        ) {
            throw IOException("Cached scan belongs to another generation")
        }
        return rewriteOutputMetadata(
            directory = File(root, cached.baseName),
            expectedCacheId = cached.baseName,
            expectedEntryId = entryId,
            pageCount = cached.pages.size,
            update = update,
        )
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
            val treeUri = treeUriValue.toUri()
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
        val reportedSourceLength = querySize(source)?.takeIf { it >= 0L }
        if (reportedSourceLength == 0L) {
            throw IOException("Source URI is empty")
        }
        val input =
            resolver.openInputStream(source)
                ?: throw IOException("Source URI could not be opened")
        val copied =
            input.use { stream ->
                FileOutputStream(destination).use { output ->
                    stream.copyTo(output).also { output.fd.sync() }
                }
            }
        requireExactProviderCopy(
            expectedLength = reportedSourceLength ?: copied,
            copiedLength = copied,
            reportedLength = destination.length(),
        ) {
            destination.length()
        }
    }

    private fun copyFileToUri(source: File, destination: Uri) {
        requireReadableFile(source)
        val expectedLength = source.length()
        val output =
            resolver.openOutputStream(destination, "w")
                ?: throw IOException("Destination URI could not be opened")
        val copied = output.use { stream -> source.inputStream().use { it.copyTo(stream) } }
        if (source.length() != expectedLength) {
            throw IOException("Source file changed while it was copied")
        }
        requireExactProviderCopy(
            expectedLength = expectedLength,
            copiedLength = copied,
            reportedLength = querySize(destination),
        ) {
            countDestinationBytes(destination, expectedLength)
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

    private fun countDestinationBytes(uri: Uri, expectedLength: Long): Long {
        if (expectedLength >= Long.MAX_VALUE) {
            throw IOException("Destination is too large to verify")
        }
        val input = resolver.openInputStream(uri) ?: throw IOException("Destination URI could not be reopened")
        return input.use { countBytesAtMost(it, expectedLength + 1L) }
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

    private fun deleteCreatedOutput(uri: Uri, failure: Exception) {
        try {
            when {
                uri.authority == MediaStore.AUTHORITY -> deleteMediaRow(uri, failure)
                DocumentsContract.isDocumentUri(context, uri) -> {
                    if (!deleteSafDocument(uri)) {
                        failure.addSuppressed(IOException("Incomplete SAF document could not be deleted"))
                    }
                }
                else -> failure.addSuppressed(IOException("Incomplete provider output could not be deleted"))
            }
        } catch (cleanupFailure: Exception) {
            if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
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
