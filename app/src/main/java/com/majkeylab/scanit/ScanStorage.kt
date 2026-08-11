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
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.locks.ReentrantLock
import java.util.UUID
import kotlin.concurrent.withLock
import kotlin.math.min

private const val DEFAULT_THUMBNAIL_SIZE = 1024
private const val PDF_MAX_BITMAP_SIDE = 3508
private const val PDF_MIME_TYPE = "application/pdf"
private const val JPEG_MIME_TYPE = "image/jpeg"
private const val MAX_SHARE_CACHE_SCANS = 8
private const val RECOVERY_PENDING_PREFIX = ".pending-recovery-"
private const val DELETE_PENDING_PREFIX = ".pending-delete-"
private const val REMOVE_PENDING_PREFIX = ".pending-remove-"
private const val COMMITTED_PRUNE_PREFIX = ".committed-prune-"
internal val storageTransactionLock = ReentrantLock()

internal inline fun <T> withStorageTransaction(operation: () -> T): T =
    storageTransactionLock.withLock(operation)

internal inline fun <T> tryStorageTransaction(operation: () -> T): T? {
    if (!storageTransactionLock.tryLock()) return null
    return try {
        operation()
    } finally {
        storageTransactionLock.unlock()
    }
}
private const val PROVISIONAL_CACHE_MARKER = ".provisional"
private const val ACTIVATION_ROLLBACK_MARKER = ".activation-rollback"
private val MEDIA_IDENTITY_PROJECTION =
    arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
    )

internal fun <T> pendingMediaWrite(
    rollback: (Exception) -> Boolean,
    operation: () -> T,
): T =
    try {
        operation()
    } catch (cancellation: CancellationException) {
        rollback(cancellation)
        throw cancellation
    } catch (exception: Exception) {
        throw PendingMediaFailure(!rollback(exception), exception)
    }

internal fun existingCompleteImagesForSave(
    metadata: OutputMetadata,
    pageCount: Int,
): List<ImageOutputRef>? =
    when {
        pageCount <= 0 -> throw IOException("Scan has no pages")
        metadata.images.isEmpty() -> null
        metadata.images.map(ImageOutputRef::page) == (1..pageCount).toList() -> metadata.images
        else -> throw IOException("Cached image output metadata is incomplete")
    }

private fun SavedMediaOutput.toPdfOutput(warning: UiMessage? = null): SavedPdfOutput =
    SavedPdfOutput(
        uri = uri,
        treeUri = null,
        warning = warning,
        displayName = displayName,
        mimeType = mimeType,
        ownerPackageName = ownerPackageName,
        byteLength = byteLength,
        sha256 = sha256,
    )

internal data class FitRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

internal data class CachedScanBuild(
    val cached: CachedScan,
    val pdf: ScanPdfBuildResult,
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

internal fun scanSourcePageFileName(
    baseName: String,
    pageNumber: Int,
): String {
    require(baseName.isNotBlank()) { "Scan base name must not be blank" }
    require(pageNumber > 0) { "Page number must be positive" }
    val page = pageNumber.toString().padStart(2, '0')
    return "${baseName}_source_${page}.jpg"
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
    val outputs: OutputMetadata?,
    val provisional: Boolean,
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

internal fun deleteRecentScanInRoot(
    root: File,
    cacheId: String,
    moveEntry: (Path, Path) -> Unit = ::moveCacheEntryAtomically,
    deleteTree: (File) -> Boolean = ::deleteTreeWithoutFollowingLinks,
): Boolean {
    if (!isSafeCacheId(cacheId)) return false
    val safeRoot = ensureShareRoot(root)
    try {
        maintainPendingDirectories(safeRoot)
    } catch (_: IOException) {
        return false
    } catch (_: SecurityException) {
        return false
    }
    val directory = File(safeRoot, cacheId).absoluteFile
    if (readCacheEntry(safeRoot, directory, cacheId) == null) {
        return !Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)
    }
    val pending = File(safeRoot, "$REMOVE_PENDING_PREFIX${UUID.randomUUID()}").absoluteFile
    if (!isDirectChild(safeRoot, pending) || pending.exists()) return false
    return try {
        moveEntry(directory.toPath(), pending.toPath())
        deleteTree(pending)
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }
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

internal fun recoverPendingRecentRemovalsInRoot(
    root: File,
    deleteTree: (File) -> Boolean = ::deleteTreeWithoutFollowingLinks,
): Boolean {
    val safeRoot = ensureShareRoot(root)
    if (!recoverPendingRemovalDirectories(safeRoot, deleteTree)) return false
    maintainPendingDirectories(safeRoot, recoverRemovals = false)
    var recoveredAll = true
    readCacheEntries(safeRoot)
        .filter { it.outputs?.removeRecentPending == true }
        .forEach { entry ->
            if (
                !deleteRecentScanInRoot(
                    safeRoot,
                    entry.recent.cacheId,
                    deleteTree = deleteTree,
                )
            ) {
                recoveredAll = false
            }
        }
    return recoveredAll
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

    var published = false
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
        commitCacheEntryRemovals(safeRoot, cacheId, entriesToPrune, moveEntry)
        return cached
    } catch (failure: Throwable) {
        var reportedFailure = failure
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

private fun publishProvisionalCacheEntryInRoot(
    root: File,
    workDir: File,
    finalDir: File,
    moveEntry: (Path, Path) -> Unit,
): CachedScan {
    val safeRoot = ensureShareRoot(root)
    val work = workDir.absoluteFile
    if (!isCreatePendingDirectory(safeRoot, work)) {
        throw IOException("Provisional cache publication path is unsafe")
    }
    try {
        val marker = File(work, PROVISIONAL_CACHE_MARKER)
        if (!marker.createNewFile()) {
            throw IOException("Reserved provisional cache marker already exists")
        }
        return publishCacheEntryInRoot(
            root = safeRoot,
            workDir = work,
            finalDir = finalDir,
            maxEntries = Int.MAX_VALUE,
            moveEntry = moveEntry,
        )
    } catch (failure: Throwable) {
        if (work.exists() && !deleteTreeWithoutFollowingLinks(work)) {
            failure.addSuppressed(IOException("Failed provisional cache could not be deleted"))
        }
        throw failure
    }
}

private fun activateProvisionalCacheEntryInRoot(
    root: File,
    candidateCacheId: String,
    retireCacheId: String?,
    protectedCacheIds: Set<String>,
    maxEntries: Int,
    moveEntry: (Path, Path) -> Unit,
): CachedScan {
    require(maxEntries > 0) { "Recent scan retention must be positive for activation" }
    require(protectedCacheIds.all(::isSafeCacheId)) { "Protected cache ID is unsafe" }
    val safeRoot = ensureShareRoot(root)
    maintainPendingDirectories(safeRoot)
    if (!isSafeCacheId(candidateCacheId)) throw IOException("Candidate cache ID is unsafe")
    val candidateDirectory = File(safeRoot, candidateCacheId).absoluteFile
    val candidate = readCacheEntry(safeRoot, candidateDirectory, candidateCacheId)
        ?.takeIf(ParsedCacheEntry::provisional)
        ?: throw IOException("Provisional cache entry is unavailable")
    var activated: CachedScan? = null
    try {
        val entriesToRemove =
            activationEntriesToRemove(
                safeRoot,
                retireCacheId,
                candidate.cached.lineageCacheId,
                protectedCacheIds,
                maxEntries - 1,
            )
        commitCacheEntryRemovals(
            root = safeRoot,
            publishedCacheId = candidateCacheId,
            entries = entriesToRemove,
            moveEntry = moveEntry,
            preservePublishedOnRollback = true,
        ) {
            Files.delete(File(candidateDirectory, PROVISIONAL_CACHE_MARKER).toPath())
            activated =
                readCacheEntry(safeRoot, candidateDirectory, candidateCacheId)
                    ?.takeUnless(ParsedCacheEntry::provisional)
                    ?.cached
                    ?: throw IOException("Activated cache entry is incomplete")
        }
        return checkNotNull(activated)
    } catch (failure: Throwable) {
        try {
            ensureProvisionalCacheMarker(safeRoot, candidateCacheId)
        } catch (restoreFailure: Exception) {
            failure.addSuppressed(restoreFailure)
        }
        throw failure
    }
}

private fun isProvisionalCacheEntryInRoot(root: File, cacheId: String): Boolean {
    if (!isSafeCacheId(cacheId)) return false
    val safeRoot = ensureShareRoot(root)
    maintainPendingDirectories(safeRoot)
    return readCacheEntry(safeRoot, File(safeRoot, cacheId).absoluteFile, cacheId)?.provisional == true
}

private fun reconcileProvisionalCacheEntriesInRoot(
    root: File,
    authoritativeCacheId: String?,
) {
    val safeRoot = ensureShareRoot(root)
    maintainPendingDirectories(safeRoot)
    val entries = readCacheEntries(safeRoot, includeProvisional = true)
    if (authoritativeCacheId != null) {
        if (
            !isSafeCacheId(authoritativeCacheId) ||
                entries.none {
                    it.provisional && it.recent.cacheId == authoritativeCacheId
                }
        ) {
            throw IOException("Authoritative provisional cache entry is unavailable")
        }
    }
    entries
        .filter {
            it.provisional && it.recent.cacheId != authoritativeCacheId
        }.forEach { entry ->
            if (!deleteTreeWithoutFollowingLinks(entry.directory)) {
                throw IOException("Orphaned provisional cache entry could not be deleted")
            }
        }
}

private fun activateCheckpointProvisionalCacheEntryInRoot(
    root: File,
    candidateCacheId: String,
    maxEntries: Int,
    moveEntry: (Path, Path) -> Unit,
): CachedScan {
    val safeRoot = ensureShareRoot(root)
    maintainPendingDirectories(safeRoot)
    if (!isSafeCacheId(candidateCacheId)) throw IOException("Candidate cache ID is unsafe")
    val candidate =
        readCacheEntry(
            safeRoot,
            File(safeRoot, candidateCacheId).absoluteFile,
            candidateCacheId,
    ) ?: throw IOException("Checkpoint cache entry is unavailable")
    if (!candidate.provisional) return candidate.cached
    val parentCacheId = candidate.cached.parentCacheId
        ?: throw IOException("Provisional cache parent is unavailable")
    val parentEntryId = candidate.cached.parentEntryId
        ?: throw IOException("Provisional cache parent generation is unavailable")
    if (candidate.cached.appearanceSettings == null) {
        throw IOException("Provisional cache appearance authority is unavailable")
    }
    val parent = readCacheEntries(safeRoot).singleOrNull {
        it.recent.cacheId == parentCacheId
    }
    val exactParent = parent?.takeIf { it.cached.entryId == parentEntryId }
    if (exactParent != null && exactParent.cached.lineageCacheId != candidate.cached.lineageCacheId) {
        throw IOException("Provisional cache parent belongs to another lineage")
    }
    val retireParent =
        exactParent?.takeIf { entry ->
            entry.outputs?.let { it.pdf == null && it.images.isEmpty() } == true
        }
    val protectedParent =
        parent?.takeUnless { it === retireParent }?.recent?.cacheId?.let(::setOf).orEmpty()
    return activateProvisionalCacheEntryInRoot(
        root = safeRoot,
        candidateCacheId = candidateCacheId,
        retireCacheId = retireParent?.recent?.cacheId,
        protectedCacheIds = protectedParent,
        maxEntries = maxEntries,
        moveEntry = moveEntry,
    )
}

private fun activationEntriesToRemove(
    root: File,
    retireCacheId: String?,
    candidateLineageCacheId: String,
    protectedCacheIds: Set<String>,
    activeCapacity: Int,
): List<ParsedCacheEntry> {
    require(activeCapacity >= 0) { "Active cache capacity must not be negative" }
    if (retireCacheId != null && !isSafeCacheId(retireCacheId)) {
        throw IOException("Retired cache ID is unsafe")
    }
    if (retireCacheId != null && retireCacheId in protectedCacheIds) {
        throw IOException("Retired cache entry cannot also be protected")
    }
    val entries = readCacheEntries(root)
    val retired =
        retireCacheId?.let { id ->
            val entry =
                entries.singleOrNull { it.recent.cacheId == id }
                    ?: throw IOException("Retired cache entry is unavailable")
            if (entry.cached.lineageCacheId != candidateLineageCacheId) {
                throw IOException("Retired cache entry belongs to another lineage")
            }
            entry
        }
    val remaining = entries.filterNot { it === retired }
    val protectedEntries = remaining.filter { it.recent.cacheId in protectedCacheIds }
    if (protectedEntries.size > activeCapacity) {
        throw IOException("Protected recent scans exceed cache capacity")
    }
    val protectedDirectories = protectedEntries.mapTo(mutableSetOf(), ParsedCacheEntry::directory)
    val unprotected = remaining.filterNot { it.directory in protectedDirectories }
    val unprotectedToKeep = activeCapacity - protectedEntries.size
    val directoriesToDelete =
        shareCacheEntriesToPrune(unprotected.map(ParsedCacheEntry::directory), unprotectedToKeep)
            .toSet()
    return listOfNotNull(retired) + remaining.filter { it.directory in directoriesToDelete }
}

private fun commitCacheEntryRemovals(
    root: File,
    publishedCacheId: String,
    entries: List<ParsedCacheEntry>,
    moveEntry: (Path, Path) -> Unit,
    preservePublishedOnRollback: Boolean = false,
    beforeCommit: () -> Unit = {},
) {
    if (entries.isEmpty()) {
        beforeCommit()
        return
    }
    val recoveryDirectory = File(root, "$RECOVERY_PENDING_PREFIX$publishedCacheId")
    val deleteDirectory = File(root, "$DELETE_PENDING_PREFIX$publishedCacheId")
    val commitMarker = File(root, "$COMMITTED_PRUNE_PREFIX$publishedCacheId")
    if (
        recoveryDirectory.exists() ||
            deleteDirectory.exists() ||
            commitMarker.exists() ||
            !recoveryDirectory.mkdir()
    ) {
        throw IOException("Recent scan recovery directory could not be created")
    }
    if (
        preservePublishedOnRollback &&
            !File(recoveryDirectory, ACTIVATION_ROLLBACK_MARKER).createNewFile()
    ) {
        deleteTreeWithoutFollowingLinks(recoveryDirectory)
        throw IOException("Activation recovery marker could not be created")
    }
    var committed = false
    try {
        entries.forEach { entry ->
            if (readCacheEntry(root, entry.directory, entry.recent.cacheId) == null) {
                if (!entry.directory.exists()) return@forEach
                throw IOException("Recent scan changed before pruning")
            }
            moveEntry(
                entry.directory.toPath(),
                File(recoveryDirectory, entry.recent.cacheId).toPath(),
            )
        }
        beforeCommit()
        moveEntry(recoveryDirectory.toPath(), deleteDirectory.toPath())
        if (!commitMarker.createNewFile()) {
            throw IOException("Recent scan prune could not be committed")
        }
        committed = true
        deleteCommittedPrune(deleteDirectory, commitMarker)
    } catch (failure: Throwable) {
        if (committed) throw failure
        val recoverySource =
            when {
                recoveryDirectory.exists() -> recoveryDirectory
                deleteDirectory.exists() -> deleteDirectory
                else -> null
            }
        try {
            recoverySource?.let {
                restoreRecoveryDirectory(root, it, publishedCacheId, moveEntry)
            }
        } catch (restoreFailure: Exception) {
            throw IOException("Recent scan recovery is pending", restoreFailure).also {
                it.addSuppressed(failure)
            }
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
    val protectedDirectories = protectedEntries.mapTo(mutableSetOf(), ParsedCacheEntry::directory)
    val unprotected = entries.filterNot { it.directory in protectedDirectories }
    val unprotectedToKeep = maxEntries - protectedEntries.size
    val directoriesToDelete =
        shareCacheEntriesToPrune(unprotected.map(ParsedCacheEntry::directory), unprotectedToKeep)
            .toSet()
    return entries.filter { it.directory in directoriesToDelete }
}

private fun moveCacheEntryAtomically(source: Path, target: Path) {
    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
}

private fun readCacheEntries(
    root: File,
    includeProvisional: Boolean = false,
): List<ParsedCacheEntry> {
    val children = root.listFiles() ?: throw IOException("Share cache could not be listed")
    return children
        .mapNotNull { child -> readCacheEntry(root, child.absoluteFile, child.name) }
        .filter { includeProvisional || !it.provisional }
        .sortedWith(
            compareByDescending<ParsedCacheEntry> { it.recent.createdAt }
                .thenBy { it.recent.cacheId },
        )
}

private fun outputSidecarExists(directory: File): Boolean {
    val directoryAttributes =
        Files.readAttributes(
            directory.toPath(),
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
    if (!directoryAttributes.isDirectory) return false
    val sidecar = File(directory, OUTPUT_METADATA_FILE_NAME)
    return try {
        Files.readAttributes(
            sidecar.toPath(),
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        true
    } catch (_: NoSuchFileException) {
        false
    }
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
    val sourcePagePattern = Regex("${Regex.escape(cacheId)}_source_([0-9]+)\\.jpg")
    val pagesByNumber = mutableMapOf<Int, File>()
    val sourcePagesByNumber = mutableMapOf<Int, File>()
    var pdf: File? = null
    var provisional = false
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
            SCAN_APPEARANCE_FILE_NAME -> Unit
            PROVISIONAL_CACHE_MARKER -> {
                if (file.length() != 0L) return null
                provisional = true
            }
            scanPdfFileName(cacheId) -> {
                if (file.length() <= 0L) return null
                if (pdf != null) return null
                pdf = file
            }
            else -> {
                if (file.length() <= 0L) return null
                val sourceMatch = sourcePagePattern.matchEntire(file.name)
                if (sourceMatch != null) {
                    val pageNumber = sourceMatch.groupValues[1].toIntOrNull() ?: return null
                    if (
                        pageNumber <= 0 ||
                            file.name != scanSourcePageFileName(cacheId, pageNumber) ||
                            sourcePagesByNumber.put(pageNumber, file) != null
                    ) {
                        return null
                    }
                    return@forEach
                }
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
    val orderedSourcePages = sourcePagesByNumber.toSortedMap().values.toList()
    if (
        pdf == null ||
            orderedPages.isEmpty() ||
            pagesByNumber.keys.sorted() != (1..orderedPages.size).toList() ||
            orderedSourcePages.isNotEmpty() &&
            (
                orderedSourcePages.size != orderedPages.size ||
                    sourcePagesByNumber.keys.sorted() != (1..orderedSourcePages.size).toList()
            )
    ) {
        return null
    }
    val exactPdf = pdf
    val appearanceMetadata = readScanAppearanceMetadata(directory, cacheId)
    if (orderedSourcePages.isNotEmpty() && appearanceMetadata == null) return null
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
            hasSavedPdf = outputs?.pdf?.outputFingerprint() != null,
            savedImageCount =
                outputs?.images?.takeIf {
                    it.isNotEmpty() && it.all { image -> image.outputFingerprint() != null }
                }?.size ?: 0,
            removeRecentPending = outputs?.removeRecentPending == true,
        )
    return ParsedCacheEntry(
        directory = directory,
        recent = recent,
        cached =
            CachedScan(
                cacheId,
                orderedPages,
                exactPdf,
                entryId = outputs?.entryId,
                sourcePages = orderedSourcePages,
                appearance = appearanceMetadata?.appearance,
                appearanceSettings = appearanceMetadata?.appearanceSettings,
                pdfSizeTarget = appearanceMetadata?.pdfSizeTarget ?: PdfSizeTarget.Original,
                lineageCacheId = appearanceMetadata?.lineageCacheId ?: cacheId,
                parentCacheId = appearanceMetadata?.parentCacheId,
                parentEntryId = appearanceMetadata?.parentEntryId,
            ),
        outputs = outputs,
        provisional = provisional,
    )
}

private fun maintainPendingDirectories(
    root: File,
    keepCreate: File? = null,
    recoverRemovals: Boolean = true,
) {
    if (recoverRemovals && !recoverPendingRemovalDirectories(root)) {
        throw IOException("Pending recent scan removal could not be completed")
    }
    recoverPendingDirectories(root)
    cleanDisposablePendingDirectories(root, keepCreate)
}

private fun recoverPendingRemovalDirectories(
    root: File,
    deleteTree: (File) -> Boolean = ::deleteTreeWithoutFollowingLinks,
): Boolean {
    val children = root.listFiles() ?: throw IOException("Share cache could not be listed")
    var recoveredAll = true
    children.filter { it.name.startsWith(REMOVE_PENDING_PREFIX) }.forEach { child ->
        val directory = child.absoluteFile
        val id = directory.name.removePrefix(REMOVE_PENDING_PREFIX)
        if (
            !isCanonicalUuid(id) ||
                !isDirectChild(root, directory) ||
                !Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)
        ) {
            throw IOException("Pending recent scan removal directory is unsafe")
        }
        if (!deleteTree(directory)) recoveredAll = false
    }
    return recoveredAll
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
    val activationMarker = staged.singleOrNull { it.name == ACTIVATION_ROLLBACK_MARKER }
    if (
        activationMarker != null &&
            (
                !isDirectChild(recovery, activationMarker.absoluteFile) ||
                    !Files.isRegularFile(
                        activationMarker.toPath(),
                        LinkOption.NOFOLLOW_LINKS,
                    ) ||
                    activationMarker.length() != 0L
            )
    ) {
        throw IOException("Activation recovery marker is unsafe")
    }
    val entries =
        staged.filterNot { it.name == ACTIVATION_ROLLBACK_MARKER }.map { child ->
            val cacheId = child.name
            readCacheEntry(recovery, child.absoluteFile, cacheId)
                ?: throw IOException("Recent scan recovery entry is incomplete")
        }
    val published = File(root, publishedCacheId).absoluteFile
    val publishedIsProvisional =
        readCacheEntry(root, published, publishedCacheId)?.provisional == true
    if (activationMarker != null || publishedIsProvisional) {
        ensureProvisionalCacheMarker(root, publishedCacheId)
    } else if (published.exists()) {
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

private fun ensureProvisionalCacheMarker(root: File, cacheId: String) {
    val directory = File(root, cacheId).absoluteFile
    if (!directory.exists()) return
    val current = readCacheEntry(root, directory, cacheId)
        ?: throw IOException("Provisional cache recovery entry is incomplete")
    if (current.provisional) return
    val marker = File(directory, PROVISIONAL_CACHE_MARKER)
    if (!marker.createNewFile()) {
        throw IOException("Provisional cache marker could not be restored")
    }
    if (readCacheEntry(root, directory, cacheId)?.provisional != true) {
        Files.deleteIfExists(marker.toPath())
        throw IOException("Restored provisional cache entry is incomplete")
    }
}

private fun cleanDisposablePendingDirectories(root: File, keepCreate: File? = null) {
    val children = root.listFiles() ?: throw IOException("Share cache could not be listed")
    children.filter { child ->
        val directory = child.absoluteFile
        val isRecovery = directory.name.startsWith(RECOVERY_PENDING_PREFIX)
        val isDelete = directory.name.startsWith(DELETE_PENDING_PREFIX)
        val isRemove = directory.name.startsWith(REMOVE_PENDING_PREFIX)
        directory != keepCreate?.absoluteFile &&
            directory.name.startsWith(".pending-") &&
            !isRecovery &&
            !isDelete &&
            !isRemove &&
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
        !directory.name.startsWith(REMOVE_PENDING_PREFIX) &&
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
    private val lock: ReentrantLock = ReentrantLock(),
    private val moveEntry: (Path, Path) -> Unit = ::moveCacheEntryAtomically,
) {
    fun list(
        protectedCacheIds: Set<String> = emptySet(),
        maxEntries: Int = MAX_SHARE_CACHE_SCANS,
    ): List<RecentScan> =
        lock.withLock {
            listRecentScansInRoot(root, protectedCacheIds, maxEntries)
        }

    fun open(cacheId: String): CachedScan? =
        lock.withLock {
            openCachedScanInRoot(root, cacheId)
        }

    fun delete(cacheId: String): Boolean =
        lock.withLock {
            deleteRecentScanInRoot(root, cacheId)
        }

    fun deleteExact(cacheId: String, entryId: String): Boolean =
        lock.withLock {
            if (!isSafeCacheId(cacheId) || !isCanonicalUuid(entryId)) return@withLock false
            val current = openCachedScanInRoot(root, cacheId) ?: return@withLock false
            if (current.entryId != entryId) return@withLock false
            deleteRecentScanInRoot(root, cacheId)
        }

    fun recoverPendingRemovals(): Boolean =
        lock.withLock {
            recoverPendingRecentRemovalsInRoot(root)
        }

    fun nextDerivedCacheId(sourceCacheId: String, suffix: String): String =
        lock.withLock {
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
        lock.withLock {
            publishCacheEntryInRoot(
                root,
                workDir,
                finalDir,
                protectedCacheIds,
                maxEntries,
                moveEntry,
            )
        }

    fun publishProvisional(
        workDir: File,
        finalDir: File,
    ): CachedScan =
        synchronized(lock) {
            publishProvisionalCacheEntryInRoot(root, workDir, finalDir, moveEntry)
        }

    fun activateProvisional(
        candidateCacheId: String,
        retireCacheId: String? = null,
        protectedCacheIds: Set<String> = emptySet(),
        maxEntries: Int = MAX_SHARE_CACHE_SCANS,
    ): CachedScan =
        synchronized(lock) {
            activateProvisionalCacheEntryInRoot(
                root,
                candidateCacheId,
                retireCacheId,
                protectedCacheIds,
                maxEntries,
                moveEntry,
            )
        }

    fun isProvisional(cacheId: String): Boolean =
        synchronized(lock) {
            isProvisionalCacheEntryInRoot(root, cacheId)
        }

    fun deleteProvisional(cacheId: String, entryId: String): Boolean =
        synchronized(lock) {
            if (!isSafeCacheId(cacheId) || !isCanonicalUuid(entryId)) return@synchronized false
            val safeRoot = ensureShareRoot(root)
            maintainPendingDirectories(safeRoot)
            val current =
                readCacheEntry(
                    safeRoot,
                    File(safeRoot, cacheId).absoluteFile,
                    cacheId,
                ) ?: return@synchronized false
            if (!current.provisional || current.cached.entryId != entryId) {
                return@synchronized false
            }
            deleteRecentScanInRoot(safeRoot, cacheId)
        }

    fun reconcileProvisionals(authoritativeCacheId: String?) =
        synchronized(lock) {
            reconcileProvisionalCacheEntriesInRoot(
                root,
                authoritativeCacheId,
            )
        }

    fun activateCheckpointProvisional(
        candidateCacheId: String,
        maxEntries: Int = MAX_SHARE_CACHE_SCANS,
    ): CachedScan =
        synchronized(lock) {
            activateCheckpointProvisionalCacheEntryInRoot(
                root,
                candidateCacheId,
                maxEntries,
                moveEntry,
            )
        }
}

internal class ScanStorage(
    private val context: Context,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val resolver = context.contentResolver
    private val recentScanCache by lazy { RecentScanCache(shareCacheRoot(), storageTransactionLock) }
    private val outputDeleter by lazy { ExactOutputDeleter(context) }

    fun cacheScan(
        pageUris: List<Uri>,
        appearanceSettings: ScanAppearanceSettings,
        pdfSizeTarget: PdfSizeTarget,
        isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
    ): CachedScanBuild =
        storageTransactionLock.withLock {
            require(pageUris.isNotEmpty()) { "Scanner returned no pages" }
            require(isAcceptedScanPageCount(pageUris.size)) { "Scanner returned too many pages" }
            val baseName = scanBaseName(clock)
            val appearance = appearanceSettings.selected()
            val shareRoot = ensureShareRoot(shareCacheRoot())
            val finalDirectory = File(shareRoot, baseName)
            val workDirectory = File(shareRoot, ".pending-create-${UUID.randomUUID()}")
            if (!workDirectory.mkdir()) {
                throw IOException("Pending scan cache directory could not be created")
            }

            try {
                val sourcePages =
                    pageUris.mapIndexed { index, uri ->
                        File(workDirectory, scanSourcePageFileName(baseName, index + 1)).also {
                            copyUriToFile(uri, it)
                        }
                    }
                val renderedPages =
                    sourcePages.mapIndexed { index, source ->
                        File(workDirectory, scanPageFileName(baseName, index + 1)).also { destination ->
                            ScanAppearanceRenderer.renderJpeg(
                                source,
                                destination,
                                appearance,
                                isCancelled = isCancelled,
                            )
                        }
                    }
                val pdfBuild =
                    buildScanPdfFromPages(
                        output = File(workDirectory, scanPdfFileName(baseName)),
                        sourcePages = sourcePages,
                        renderedPages = renderedPages,
                        appearance = appearance,
                        target = pdfSizeTarget,
                        isCancelled = isCancelled,
                    )
                writeScanAppearanceMetadata(
                    workDirectory,
                    appearanceSettings,
                    pdfSizeTarget,
                    baseName,
                )
                initializeOutputMetadata(
                    directory = workDirectory,
                    cacheId = baseName,
                    pageCount = pageUris.size,
                    createdAtEpochMs = clock.millis(),
                )
                CachedScanBuild(
                    cached = recentScanCache.publish(workDirectory, finalDirectory),
                    pdf = pdfBuild,
                )
            } catch (failure: Throwable) {
                deleteRecursivelyOrSuppress(workDirectory, failure)
                throw failure
            }
        }

    fun createAppearanceVariant(
        source: CachedScan,
        appearanceSettings: ScanAppearanceSettings,
        pdfSizeTarget: PdfSizeTarget,
        isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
    ): CachedScanBuild =
        storageTransactionLock.withLock {
            requireCurrentOutputMetadata(source)
            if (
                source.sourcePages.size != source.pages.size ||
                    source.sourcePages.isEmpty() ||
                    source.appearance == null ||
                    source.entryId == null
            ) {
                throw IOException("Cached scan has no immutable appearance sources")
            }
            val appearance = appearanceSettings.selected()
            val shareRoot = ensureShareRoot(shareCacheRoot())
            val baseName =
                recentScanCache.nextDerivedCacheId(source.lineageCacheId, "appearance")
            val finalDirectory = File(shareRoot, baseName)
            val workDirectory = File(shareRoot, ".pending-create-${UUID.randomUUID()}")
            if (!workDirectory.mkdir()) {
                throw IOException("Pending appearance cache directory could not be created")
            }
            try {
                val sourcePages =
                    source.sourcePages.mapIndexed { index, page ->
                        File(workDirectory, scanSourcePageFileName(baseName, index + 1)).also {
                            Files.createLink(it.toPath(), page.toPath())
                        }
                    }
                val renderedPages =
                    sourcePages.mapIndexed { index, page ->
                        File(workDirectory, scanPageFileName(baseName, index + 1)).also { destination ->
                            ScanAppearanceRenderer.renderJpeg(
                                page,
                                destination,
                                appearance,
                                isCancelled = isCancelled,
                            )
                        }
                    }
                val pdfBuild =
                    buildScanPdfFromPages(
                        output = File(workDirectory, scanPdfFileName(baseName)),
                        sourcePages = sourcePages,
                        renderedPages = renderedPages,
                        appearance = appearance,
                        target = pdfSizeTarget,
                        isCancelled = isCancelled,
                    )
                writeScanAppearanceMetadata(
                    workDirectory,
                    appearanceSettings,
                    pdfSizeTarget,
                    source.lineageCacheId,
                    parentCacheId = source.baseName,
                    parentEntryId = source.entryId,
                )
                initializeOutputMetadata(
                    directory = workDirectory,
                    cacheId = baseName,
                    pageCount = renderedPages.size,
                    createdAtEpochMs = clock.millis(),
                )
                CachedScanBuild(
                    cached =
                        recentScanCache.publishProvisional(workDirectory, finalDirectory),
                    pdf = pdfBuild,
                )
            } catch (failure: Throwable) {
                deleteRecursivelyOrSuppress(workDirectory, failure)
                throw failure
            }
        }

    fun listRecentScans(protectedCacheIds: Set<String> = emptySet()): List<RecentScan> =
        storageTransactionLock.withLock {
            if (!recentScanCache.recoverPendingRemovals()) {
                throw IOException("Pending recent scan removal could not be completed")
            }
            recentScanCache.list(protectedCacheIds).map { scan ->
                val metadata =
                    readOutputMetadata(
                        File(shareCacheRoot(), scan.cacheId),
                        scan.cacheId,
                        scan.pageCount,
                    )
                if (metadata?.hasCompleteExactDeleteInventory(context.packageName) == true) {
                    scan
                } else {
                    scan.copy(hasSavedPdf = false, savedImageCount = 0)
                }
            }
        }

    fun openCachedScan(cacheId: String): CachedScan? =
        recentScanCache.open(cacheId)

    fun openSavedScan(cacheId: String): SavedScan? =
        storageTransactionLock.withLock {
            val cached = recentScanCache.open(cacheId) ?: return@withLock null
            val outputs =
                cached.entryId?.let { entryId ->
                    readOutputMetadata(
                        File(shareCacheRoot(), cacheId),
                        cacheId,
                        cached.pages.size,
                    )?.takeIf { it.entryId == entryId }
                }
            val deleteMetadata =
                outputs?.takeIf { it.hasCompleteExactDeleteInventory(context.packageName) }
            SavedScan(
                cached = cached,
                galleryPages = outputs?.images?.map { it.uri.toUri() }.orEmpty(),
                savedPdf = outputs?.pdf?.uri?.toUri(),
                savedPdfTree = outputs?.pdf?.treeUri?.toUri(),
                warnings = listOfNotNull(pdfSizeTargetWarning(cached.pdfSizeTarget, cached.pdf.length())),
                outputMetadataValid = outputs != null,
                savedPdfDeleteVerified = deleteMetadata?.pdf != null,
                savedImagesDeleteVerified = deleteMetadata?.images?.isNotEmpty() == true,
            )
        }

    fun deleteRecentScan(cacheId: String): Boolean =
        storageTransactionLock.withLock {
            recentScanCache.delete(cacheId)
        }

    fun removeRecentPreview(request: OutputDeleteRequest): Boolean =
        storageTransactionLock.withLock {
            if (request.target != RecentDeleteTarget.RemoveFromRecent) return@withLock false
            val cached = recentScanCache.open(request.cacheId) ?: return@withLock false
            if (cached.entryId != request.entryId) return@withLock false
            recentScanCache.delete(request.cacheId)
        }

    fun deleteDurableOutputs(
        request: OutputDeleteRequest,
        deleteRecentCache: Boolean,
    ): OutputDeleteOperationResult =
        deleteDurableOutputsLocked(request, deleteRecentCache)

    private fun deleteDurableOutputsLocked(
        request: OutputDeleteRequest,
        deleteRecentCache: Boolean,
    ): OutputDeleteOperationResult =
        storageTransactionLock.withLock {
            val cached = recentScanCache.open(request.cacheId)
                ?: return@withLock OutputDeleteOperationResult.Stale
            val entryId = cached.entryId
                ?: return@withLock OutputDeleteOperationResult.Stale
            if (entryId != request.entryId) return@withLock OutputDeleteOperationResult.Stale
            val directory = File(shareCacheRoot(), cached.baseName)
            val metadataRead =
                readOutputMetadataResult(directory, cached.baseName, cached.pages.size)
            val decoded =
                when (metadataRead) {
                    is OutputMetadataReadResult.Valid -> metadataRead.metadata
                    OutputMetadataReadResult.Invalid ->
                        return@withLock OutputDeleteOperationResult.IdentityMismatch
                    OutputMetadataReadResult.Failed ->
                        return@withLock OutputDeleteOperationResult.Failed
                }
            val current =
                matchingOutputMetadata(
                    decoded,
                    request.cacheId,
                    entryId,
                ) ?: return@withLock OutputDeleteOperationResult.IdentityMismatch
            if (!deleteRecentCache && outputDeleteTargetIsAbsent(current, request.target)) {
                return@withLock OutputDeleteOperationResult.Stale
            }
            val metadata =
                matchingDeleteMetadata(
                    current,
                    request,
                    context.packageName,
                ) ?: return@withLock OutputDeleteOperationResult.IdentityMismatch
            val selected =
                buildList<Pair<String, () -> OutputDeleteStatus>> {
                    if (
                        request.target == RecentDeleteTarget.Pdf ||
                            request.target == RecentDeleteTarget.Both
                    ) {
                        val pdf = metadata.pdf
                            ?: return@withLock OutputDeleteOperationResult.Failed
                        add(pdf.uri to { outputDeleter.deletePdf(pdf) })
                    }
                    if (
                        request.target == RecentDeleteTarget.Images ||
                            request.target == RecentDeleteTarget.Both
                    ) {
                        if (metadata.images.isEmpty()) {
                            return@withLock OutputDeleteOperationResult.Failed
                        }
                        metadata.images.forEach { image ->
                            add(image.uri to { outputDeleter.deleteImage(cached, image) })
                        }
                    }
                }
            if (
                request.target == RecentDeleteTarget.RemoveFromRecent ||
                    selected.isEmpty() ||
                    selected.map { it.first }.distinct().size != selected.size
            ) {
                return@withLock OutputDeleteOperationResult.Failed
            }
            val outcomes = selected.associate { (uri, delete) -> uri to delete() }
            val reduction = reduceOutputDeletion(metadata, request.target, outcomes)
            val committed =
                try {
                    rewriteOutputMetadata(
                        directory = directory,
                        expectedCacheId = request.cacheId,
                        expectedEntryId = entryId,
                        pageCount = cached.pages.size,
                    ) {
                        reduction.metadata.copy(
                            removeRecentPending =
                                metadata.removeRecentPending ||
                                    (deleteRecentCache && reduction.allRequestedRemoved),
                        )
                    }
                    true
                } catch (_: IOException) {
                    false
                } catch (_: SecurityException) {
                    false
                }
            if (!committed) {
                return@withLock outputDeleteOperationResult(
                    outcomes = outcomes.values,
                    metadataCommitted = false,
                    cacheDeletionRequested = deleteRecentCache,
                    cacheDeleted = false,
                )
            }
            val cacheDeleted =
                deleteRecentCache &&
                    reduction.allRequestedRemoved &&
                    recentScanCache.delete(request.cacheId)
            outputDeleteOperationResult(
                outcomes = outcomes.values,
                metadataCommitted = true,
                cacheDeletionRequested = deleteRecentCache,
                cacheDeleted = cacheDeleted,
            )
        }

    fun livePdfTreeUris(): Set<String> =
        storageTransactionLock.withLock {
            val root = ensureShareRoot(shareCacheRoot())
            maintainPendingDirectories(root)
            val entries =
                readCacheEntries(root, includeProvisional = true)
                    .associateBy(ParsedCacheEntry::directory)
            val children = root.listFiles() ?: throw IOException("Share cache could not be listed")
            completePdfTreeGrantInventory(
                children.map { child ->
                    val directory = child.absoluteFile
                    OutputMetadataInventoryEntry(
                        sidecarPresent = outputSidecarExists(directory),
                        metadata =
                            entries[directory]?.outputs?.takeIf {
                                it.hasCompleteExactDeleteInventory(context.packageName)
                            },
                    )
                },
            ) ?: throw IOException("PDF tree grant inventory is incomplete")
        }

    fun nextDerivedCacheId(sourceCacheId: String, suffix: String): String =
        recentScanCache.nextDerivedCacheId(sourceCacheId, suffix)

    fun publishCacheEntry(
        workDir: File,
        finalDir: File,
    ): CachedScan =
        recentScanCache.publishProvisional(workDir, finalDir)

    fun activateProvisionalCacheEntry(
        candidate: CachedScan,
        retireCacheId: String? = null,
        protectedCacheIds: Set<String> = emptySet(),
    ): CachedScan =
        storageTransactionLock.withLock {
            requireCurrentOutputMetadata(candidate)
            recentScanCache.activateProvisional(
                candidate.baseName,
                retireCacheId,
                protectedCacheIds,
            )
        }

    fun isProvisionalCacheEntry(candidate: CachedScan): Boolean =
        storageTransactionLock.withLock {
            requireCurrentOutputMetadata(candidate)
            recentScanCache.isProvisional(candidate.baseName)
        }

    fun reconcileProvisionalCacheEntries(authoritative: CachedScan?) =
        storageTransactionLock.withLock {
            val authoritativeCacheId =
                authoritative?.let { candidate ->
                    requireCurrentOutputMetadata(candidate)
                    candidate.baseName.takeIf { recentScanCache.isProvisional(it) }
                }
            recentScanCache.reconcileProvisionals(authoritativeCacheId)
        }

    fun deleteProvisionalCacheEntry(candidate: CachedScan): Boolean =
        storageTransactionLock.withLock {
            val metadata = requireCurrentOutputMetadata(candidate)
            recentScanCache.deleteProvisional(candidate.baseName, metadata.entryId)
        }

    fun activateCheckpointProvisional(candidate: CachedScan): CachedScan =
        storageTransactionLock.withLock {
            requireCurrentOutputMetadata(candidate)
            recentScanCache.activateCheckpointProvisional(candidate.baseName)
        }

    fun deleteCachedScan(cached: CachedScan): Boolean =
        storageTransactionLock.withLock {
            try {
                val entryId = cached.entryId ?: return@withLock false
                recentScanCache.deleteExact(cached.baseName, entryId)
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
        storageTransactionLock.withLock {
            require(cached.pages.isNotEmpty()) { "Scan has no pages" }
            val relativePath = "${Environment.DIRECTORY_PICTURES}/${normalizeAlbumName(album)}"
            val saved = mutableListOf<SavedMediaOutput>()
            try {
                existingCompleteImagesForSave(
                    requireCurrentOutputMetadata(cached),
                    cached.pages.size,
                )?.let { existing ->
                    return@withLock existing.map { it.uri.toUri() }
                }
                cached.pages.forEachIndexed { index, source ->
                    val page = index + 1
                    val values =
                        pendingValues(
                            scanPageFileName(cached.baseName, page),
                            JPEG_MIME_TYPE,
                            relativePath,
                        )
                    saved +=
                        insertPendingFile(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            values,
                            source,
                            MediaOutputCollection.Images,
                            JPEG_MIME_TYPE,
                        )
                }
                rewriteCachedOutputMetadata(cached) { metadata ->
                    metadata.copy(
                        images =
                            saved.mapIndexed { index, uri ->
                                ImageOutputRef(
                                    page = index + 1,
                                    uri = uri.uri.toString(),
                                    displayName = uri.displayName,
                                    mimeType = uri.mimeType,
                                    ownerPackageName = uri.ownerPackageName,
                                    byteLength = uri.byteLength,
                                    sha256 = uri.sha256,
                                )
                            },
                    )
                }
                saved.map(SavedMediaOutput::uri)
            } catch (cancellation: CancellationException) {
                saved.forEach { rollbackMediaOutput(it, MediaOutputCollection.Images, cancellation) }
                throw cancellation
            } catch (exception: Exception) {
                var rollbackFailed = false
                saved.forEach { output ->
                    if (!rollbackMediaOutput(output, MediaOutputCollection.Images, exception)) {
                        rollbackFailed = true
                    }
                }
                throw ImageSaveFailure(exception, rollbackFailed)
            }
        }

    fun savePdf(
        cached: CachedScan,
        album: String,
        pdfTreeUri: String?,
    ): SavedPdfOutput =
        storageTransactionLock.withLock {
            requireReadableFile(cached.pdf)
            val displayName = scanPdfFileName(cached.baseName)
            var failureWarning: UiMessage? = null
            var createdOutput: SavedPdfOutput? = null
            try {
                requireCurrentOutputMetadata(cached).pdf?.let { existing ->
                    return@withLock SavedPdfOutput(
                        uri = existing.uri.toUri(),
                        treeUri = existing.treeUri?.toUri(),
                        warning = null,
                        displayName = existing.displayName,
                        mimeType = existing.mimeType,
                        ownerPackageName = existing.ownerPackageName,
                        byteLength = existing.byteLength,
                        sha256 = existing.sha256,
                    )
                }
                val output =
                    if (pdfTreeUri != null) {
                        val (savedToTree, cleanupFailed) =
                            savePdfToTree(cached.pdf, displayName, pdfTreeUri)
                        if (savedToTree != null) {
                            savedToTree
                        } else {
                            failureWarning =
                                safFallbackWarning(cleanupFailed, savedToDownloads = false)
                            savePdfToDownloads(cached.pdf, displayName, album).toPdfOutput(
                                safFallbackWarning(cleanupFailed, savedToDownloads = true),
                            )
                        }
                    } else {
                        savePdfToDownloads(cached.pdf, displayName, album).toPdfOutput()
                    }
                createdOutput = output
                rewriteCachedOutputMetadata(cached) { metadata ->
                    metadata.copy(
                        pdf =
                            PdfOutputRef(
                                uri = output.uri.toString(),
                                treeUri = output.treeUri?.toString(),
                                displayName = output.displayName,
                                mimeType = output.mimeType,
                                ownerPackageName = output.ownerPackageName,
                                byteLength = output.byteLength,
                                sha256 = output.sha256,
                            ),
                    )
                }
                output
            } catch (cancellation: CancellationException) {
                createdOutput?.let { rollbackPdfOutput(it, cancellation) }
                throw cancellation
            } catch (exception: Exception) {
                val rollbackFailed =
                    createdOutput?.let { !rollbackPdfOutput(it, exception) } ?: false
                throw PdfSaveFailure(failureWarning, exception, rollbackFailed)
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
        val current = requireCurrentOutputMetadata(cached)
        return rewriteOutputMetadata(
            directory = File(shareCacheRoot(), cached.baseName),
            expectedCacheId = cached.baseName,
            expectedEntryId = current.entryId,
            pageCount = cached.pages.size,
            update = update,
        )
    }

    private fun requireCurrentOutputMetadata(cached: CachedScan): OutputMetadata {
        val entryId = cached.entryId ?: throw IOException("Cached scan output metadata is unavailable")
        val root = shareCacheRoot()
        val current = openCachedScanInRoot(root, cached.baseName)
            ?: throw IOException("Cached scan is unavailable")
        if (
            current.entryId != entryId ||
                current.pdf != cached.pdf ||
                current.pages != cached.pages ||
                current.sourcePages != cached.sourcePages ||
                current.appearance != cached.appearance ||
                current.appearanceSettings != cached.appearanceSettings ||
                current.pdfSizeTarget != cached.pdfSizeTarget ||
                current.lineageCacheId != cached.lineageCacheId ||
                current.parentCacheId != cached.parentCacheId ||
                current.parentEntryId != cached.parentEntryId
        ) {
            throw IOException("Cached scan belongs to another generation")
        }
        val metadata =
            readOutputMetadata(File(root, cached.baseName), cached.baseName, cached.pages.size)
        return matchingOutputMetadata(metadata, cached.baseName, entryId)
            ?: throw IOException("Cached scan output metadata is incomplete")
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

    private fun savePdfToDownloads(
        source: File,
        displayName: String,
        album: String,
    ): SavedMediaOutput {
        val values =
            pendingValues(
                displayName,
                PDF_MIME_TYPE,
                "${Environment.DIRECTORY_DOWNLOADS}/${normalizeAlbumName(album)}",
            )
        return insertPendingFile(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            values,
            source,
            MediaOutputCollection.Downloads,
            PDF_MIME_TYPE,
        )
    }

    private fun savePdfToTree(
        source: File,
        displayName: String,
        treeUriValue: String,
    ): Pair<SavedPdfOutput?, Boolean> {
        var createdDocument: Uri? = null
        try {
            val sourceFingerprint = fingerprintFile(source)
            val treeUri = treeUriValue.toUri()
            if (
                !DocumentsContract.isTreeUri(treeUri) ||
                    resolver.persistedUriPermissions.none {
                        it.uri == treeUri && it.isReadPermission && it.isWritePermission
                    }
            ) {
                return null to false
            }
            val parent =
                DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    DocumentsContract.getTreeDocumentId(treeUri),
                )
            val document =
                DocumentsContract.createDocument(
                    resolver,
                    parent,
                    PDF_MIME_TYPE,
                    displayName,
                )
                    ?: return null to false
            createdDocument = document
            if (!isCreatedSafChild(treeUri, document)) return null to true
            copyFileToUri(source, document)
            if (fingerprintFile(source) != sourceFingerprint) {
                throw IOException("Source file changed while it was copied")
            }
            val actualName = readSafPdfDisplayName(treeUri, document)
            val fingerprint = fingerprintUri(document, sourceFingerprint.byteLength)
            if (!savedOutputMatchesSource(sourceFingerprint, fingerprint)) {
                throw IOException("Saved PDF differs from its source")
            }
            return SavedPdfOutput(
                uri = document,
                treeUri = treeUri,
                warning = null,
                displayName = actualName,
                mimeType = PDF_MIME_TYPE,
                byteLength = fingerprint.byteLength,
                sha256 = fingerprint.sha256,
            ) to false
        } catch (cancellation: CancellationException) {
            if (createdDocument != null) {
                cancellation.addSuppressed(
                    IOException("Unverified SAF document was left for safe manual cleanup"),
                )
            }
            throw cancellation
        } catch (failure: Exception) {
            return null to (createdDocument != null)
        }
    }

    private fun readSafPdfDisplayName(tree: Uri, document: Uri): String {
        if (!isCreatedSafChild(tree, document)) {
            throw IOException("Created SAF document identity is unsafe")
        }
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val documentId = DocumentsContract.getDocumentId(document)
        val cursor =
            resolver.query(
                document,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null,
                null,
                null,
            ) ?: throw IOException("Created SAF document identity is unavailable")
        return cursor.use {
            if (!it.moveToFirst()) throw IOException("Created SAF document is unavailable")
            val idIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            if (
                idIndex < 0 ||
                    nameIndex < 0 ||
                    mimeIndex < 0 ||
                    it.isNull(idIndex) ||
                    it.isNull(nameIndex) ||
                    it.isNull(mimeIndex)
            ) {
                throw IOException("Created SAF document identity is incomplete")
            }
            val actualName = it.getString(nameIndex)
            if (
                it.getString(idIndex) != documentId ||
                    it.getString(mimeIndex) != PDF_MIME_TYPE ||
                    !isProviderDisplayName(actualName) ||
                    it.moveToNext()
            ) {
                throw IOException("Created SAF document identity changed")
            }
            actualName
        }
    }

    private fun isCreatedSafChild(tree: Uri, document: Uri): Boolean =
        try {
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            val documentId = DocumentsContract.getDocumentId(document)
            val rootDocument = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
            safChildStructureIsValid(
                sameAuthority =
                    document.scheme == ContentResolver.SCHEME_CONTENT &&
                        tree.authority != null &&
                        tree.authority == document.authority,
                rootDocumentId = rootId,
                returnedTreeDocumentId = DocumentsContract.getTreeDocumentId(document),
                returnedDocumentId = documentId,
                returnedUriIsCanonical =
                    document.query == null &&
                        document.fragment == null &&
                        DocumentsContract.buildDocumentUriUsingTree(tree, documentId) == document,
            ) && DocumentsContract.isChildDocument(resolver, rootDocument, document)
        } catch (_: Exception) {
            false
        }

    private fun pendingValues(displayName: String, mimeType: String, relativePath: String) =
        ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

    private fun insertPendingFile(
        collection: Uri,
        values: ContentValues,
        source: File,
        expectedCollection: MediaOutputCollection,
        expectedMimeType: String,
    ): SavedMediaOutput {
        requireReadableFile(source)
        val sourceFingerprint = fingerprintFile(source)
        val destination =
            resolver.insert(collection, values)
                ?: throw IOException("MediaStore row could not be created")
        var verifiedOutput: SavedMediaOutput? = null
        return pendingMediaWrite(
            rollback = { failure ->
                val verified =
                    verifiedOutput
                        ?: try {
                            readSavedMediaOutput(
                                destination,
                                expectedCollection,
                                expectedMimeType,
                                sourceFingerprint,
                            )
                        } catch (verificationFailure: Exception) {
                            failure.addSuppressed(verificationFailure)
                            null
                        }
                verified != null && rollbackMediaOutput(verified, expectedCollection, failure)
            },
        ) {
            copyFileToUri(source, destination)
            if (fingerprintFile(source) != sourceFingerprint) {
                throw IOException("Source file changed while it was copied")
            }
            val pendingOutput =
                readSavedMediaOutput(
                    destination,
                    expectedCollection,
                    expectedMimeType,
                    sourceFingerprint,
                )
            verifiedOutput = pendingOutput
            val address = parseMediaItemAddress(destination.toString())
                ?: throw IOException("MediaStore item URI is invalid")
            val published =
                resolver.update(
                    destination,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    MEDIA_PUBLISH_SELECTION,
                    mediaDeleteSelectionArgs(
                        ExpectedMediaItem(
                            id = address.id,
                            displayName = pendingOutput.displayName,
                            mimeType = pendingOutput.mimeType,
                            ownerPackageName = pendingOutput.ownerPackageName,
                        ),
                    ),
                )
            if (published != 1) {
                throw IOException("MediaStore row could not be published")
            }
            readSavedMediaOutput(
                destination,
                expectedCollection,
                expectedMimeType,
                sourceFingerprint,
            )
        }
    }

    private fun readSavedMediaOutput(
        uri: Uri,
        expectedCollection: MediaOutputCollection,
        expectedMimeType: String,
        sourceFingerprint: OutputFingerprint,
    ): SavedMediaOutput {
        val address = parseMediaItemAddress(uri.toString())
            ?: throw IOException("MediaStore item URI is invalid")
        if (address.collection != expectedCollection) {
            throw IOException("MediaStore item belongs to the wrong collection")
        }
        val canonical =
            when (expectedCollection) {
                MediaOutputCollection.Images ->
                    MediaStore.Images.Media.getContentUri(address.volume, address.id)
                MediaOutputCollection.Downloads ->
                    MediaStore.Downloads.getContentUri(address.volume, address.id)
            }
        if (canonical != uri) throw IOException("MediaStore item URI is not canonical")
        val cursor =
            resolver.query(uri, MEDIA_IDENTITY_PROJECTION, null, null, null)
                ?: throw IOException("MediaStore item identity is unavailable")
        return cursor.use {
            if (!it.moveToFirst()) throw IOException("MediaStore item is unavailable")
            val idIndex = it.getColumnIndex(MediaStore.MediaColumns._ID)
            val nameIndex = it.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeIndex = it.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
            val ownerIndex = it.getColumnIndex(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
            if (
                idIndex < 0 ||
                    nameIndex < 0 ||
                    mimeIndex < 0 ||
                    ownerIndex < 0 ||
                    it.isNull(idIndex) ||
                    it.isNull(nameIndex) ||
                    it.isNull(mimeIndex)
            ) {
                throw IOException("MediaStore item identity is incomplete")
            }
            val displayName = it.getString(nameIndex)
            val mimeType = it.getString(mimeIndex)
            val ownerPackageName =
                if (it.isNull(ownerIndex)) null else it.getString(ownerIndex)
            if (
                it.getLong(idIndex) != address.id ||
                    !isProviderDisplayName(displayName) ||
                    mimeType != expectedMimeType ||
                    ownerPackageName != context.packageName ||
                    it.moveToNext()
            ) {
                throw IOException("MediaStore item identity changed")
            }
            val fingerprint = fingerprintUri(uri, sourceFingerprint.byteLength)
            if (!savedOutputMatchesSource(sourceFingerprint, fingerprint)) {
                throw IOException("Saved output differs from its source")
            }
            SavedMediaOutput(
                uri,
                displayName,
                mimeType,
                context.packageName,
                fingerprint.byteLength,
                fingerprint.sha256,
            )
        }
    }

    private fun fingerprintUri(uri: Uri, expectedLength: Long): OutputFingerprint {
        val input = resolver.openInputStream(uri)
            ?: throw IOException("Saved output could not be reopened")
        return input.use { readOutputFingerprint(it, expectedLength) }
    }

    private fun fingerprintFile(file: File): OutputFingerprint {
        requireReadableFile(file)
        val expectedLength = file.length()
        return file.inputStream().use { readOutputFingerprint(it, expectedLength) }
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

    private fun rollbackMediaOutput(
        output: SavedMediaOutput,
        collection: MediaOutputCollection,
        failure: Exception,
    ): Boolean =
        try {
            val status = outputDeleter.deleteMediaOutput(output, collection)
            if (status == OutputDeleteStatus.Deleted || status == OutputDeleteStatus.Absent) {
                true
            } else {
                failure.addSuppressed(IOException("Verified MediaStore rollback failed"))
                false
            }
        } catch (cleanupFailure: Exception) {
            if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
            false
        }

    private fun rollbackPdfOutput(output: SavedPdfOutput, failure: Exception): Boolean =
        try {
            val status =
                outputDeleter.deletePdf(
                    PdfOutputRef(
                        uri = output.uri.toString(),
                        treeUri = output.treeUri?.toString(),
                        displayName = output.displayName,
                        mimeType = output.mimeType,
                        ownerPackageName = output.ownerPackageName,
                        byteLength = output.byteLength,
                        sha256 = output.sha256,
                    ),
                )
            if (status == OutputDeleteStatus.Deleted || status == OutputDeleteStatus.Absent) {
                true
            } else {
                failure.addSuppressed(IOException("Verified PDF rollback failed"))
                false
            }
        } catch (cleanupFailure: Exception) {
            if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
            false
        }
}
