package com.majkeylab.scanit

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.service.chooser.ChooserResult
import java.io.FileNotFoundException
import java.io.IOException
import java.net.URI
import java.util.concurrent.CancellationException

internal enum class MediaOutputCollection {
    Images,
    Downloads,
}

internal data class MediaItemAddress(
    val collection: MediaOutputCollection,
    val volume: String,
    val id: Long,
)

internal data class ExpectedMediaItem(
    val id: Long,
    val displayName: String,
    val mimeType: String,
    val ownerPackageName: String,
)

internal data class MediaItemRow(
    val id: Long,
    val displayName: String,
    val mimeType: String,
    val ownerPackageName: String,
)

internal data class SafDocumentRow(
    val documentId: String,
    val displayName: String,
    val mimeType: String,
    val flags: Int,
)

internal data class OutputMetadataInventoryEntry(
    val sidecarPresent: Boolean,
    val metadata: OutputMetadata?,
)

internal data class OutputDeleteRequest(
    val cacheId: String,
    val entryId: String?,
    val target: RecentDeleteTarget,
)

internal fun parseMediaItemAddress(value: String): MediaItemAddress? =
    try {
        val uri = URI(value)
        if (
            uri.scheme != "content" ||
                uri.rawAuthority != "media" ||
                uri.rawQuery != null ||
                uri.rawFragment != null ||
                uri.rawUserInfo != null ||
                uri.port != -1
        ) {
            return null
        }
        val segments = uri.rawPath?.split('/')?.drop(1) ?: return null
        val volume = segments.firstOrNull()?.takeIf { it.matches(VOLUME_NAME) } ?: return null
        val collection =
            when {
                segments.size == 4 && segments[1] == "images" && segments[2] == "media" ->
                    MediaOutputCollection.Images
                segments.size == 3 && segments[1] == "downloads" ->
                    MediaOutputCollection.Downloads
                else -> null
            } ?: return null
        val idValue = segments.last()
        val id = idValue.toLongOrNull()?.takeIf { it > 0L && it.toString() == idValue } ?: return null
        MediaItemAddress(collection, volume, id)
    } catch (_: Exception) {
        null
    }

internal fun isExactMediaItem(row: MediaItemRow, expected: ExpectedMediaItem): Boolean =
    row.id == expected.id &&
        row.displayName == expected.displayName &&
        row.mimeType == expected.mimeType &&
        row.ownerPackageName == expected.ownerPackageName

internal fun mediaDeleteSucceeded(deletedRows: Int): Boolean = deletedRows == 1

internal fun outputDeleteOperationResult(
    outcomes: Collection<OutputDeleteStatus>,
    metadataCommitted: Boolean,
    cacheDeletionRequested: Boolean,
    cacheDeleted: Boolean,
): OutputDeleteOperationResult {
    require(outcomes.isNotEmpty()) { "Delete outcomes are required" }
    val removed = outcomes.count { it != OutputDeleteStatus.Failed }
    if (!metadataCommitted) {
        return if (removed > 0) {
            OutputDeleteOperationResult.MetadataFailed
        } else {
            OutputDeleteOperationResult.Failed
        }
    }
    if (removed < outcomes.size) {
        return if (removed > 0) {
            OutputDeleteOperationResult.Partial
        } else {
            OutputDeleteOperationResult.Failed
        }
    }
    return if (cacheDeletionRequested && !cacheDeleted) {
        OutputDeleteOperationResult.CacheFailed
    } else {
        OutputDeleteOperationResult.Completed
    }
}

internal fun safMissingDocumentStatus(
    rootExact: Boolean,
    documentIsChild: Boolean,
): OutputDeleteStatus =
    if (rootExact && !documentIsChild) OutputDeleteStatus.Absent else OutputDeleteStatus.Failed

internal fun mediaDeleteSelectionArgs(expected: ExpectedMediaItem): Array<String> =
    arrayOf(
        expected.id.toString(),
        expected.displayName,
        expected.mimeType,
        expected.ownerPackageName,
    )

internal fun isExactSafDocument(
    row: SafDocumentRow,
    expectedDocumentId: String,
    expectedDisplayName: String,
): Boolean =
    row.documentId == expectedDocumentId &&
        row.displayName == expectedDisplayName &&
        row.mimeType == "application/pdf" &&
        row.flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE != 0

internal fun matchingDeleteMetadata(
    metadata: OutputMetadata?,
    request: OutputDeleteRequest,
): OutputMetadata? =
    metadata?.takeIf {
        isSafeCacheId(request.cacheId) &&
            request.entryId != null &&
            request.target != RecentDeleteTarget.RemoveFromRecent &&
            it.cacheId == request.cacheId &&
            it.entryId == request.entryId
    }

internal fun recentDeleteRequestAvailable(
    scan: RecentScan,
    request: OutputDeleteRequest,
): Boolean =
    scan.cacheId == request.cacheId &&
        scan.entryId == request.entryId &&
        request.target in
        recentDeleteTargets(
            metadataValid = scan.entryId != null,
            hasPdf = scan.hasSavedPdf,
            savedImageCount = scan.savedImageCount,
            removeRecentPending = scan.removeRecentPending,
        )

internal fun mayDeleteRecentCache(
    allRequestedRemoved: Boolean,
    metadataCommitted: Boolean,
): Boolean = allRequestedRemoved && metadataCommitted

internal fun chooserResultAllowsCleanup(
    resultType: Int,
    selectedComponentPresent: Boolean,
): Boolean =
    resultType == ChooserResult.CHOOSER_RESULT_SELECTED_COMPONENT && selectedComponentPresent

internal fun pdfTreeGrantsToRelease(
    persisted: Set<String>,
    current: String?,
    live: Set<String>,
): Set<String> = persisted - live - setOfNotNull(current)

internal fun completePdfTreeGrantInventory(
    entries: List<OutputMetadataInventoryEntry>,
): Set<String>? {
    if (entries.any { it.sidecarPresent != (it.metadata != null) }) return null
    return entries.mapNotNullTo(mutableSetOf()) { it.metadata?.pdf?.treeUri }
}

internal fun reconcilePdfTreeGrants(
    context: Context,
    current: String?,
    live: Set<String>,
): Boolean {
    val resolver = context.contentResolver
    val permissions = resolver.persistedUriPermissions.filter { DocumentsContract.isTreeUri(it.uri) }
    val release =
        pdfTreeGrantsToRelease(
            persisted = permissions.mapTo(mutableSetOf()) { it.uri.toString() },
            current = current,
            live = live,
        )
    var releasedAll = true
    permissions.filter { it.uri.toString() in release }.forEach { permission ->
        var flags = 0
        if (permission.isReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (permission.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            if (flags != 0) resolver.releasePersistableUriPermission(permission.uri, flags)
        } catch (_: SecurityException) {
            releasedAll = false
        } catch (_: RuntimeException) {
            releasedAll = false
        }
    }
    return releasedAll
}

internal class ExactOutputDeleter(private val context: Context) {
    private val resolver = context.contentResolver

    fun deletePdf(cached: CachedScan, reference: PdfOutputRef): OutputDeleteStatus =
        if (reference.treeUri == null) {
            val identity =
                expectedMediaIdentity(
                    reference.displayName,
                    reference.mimeType,
                    reference.ownerPackageName,
                    scanPdfFileName(cached.baseName),
                    PDF_MIME_TYPE,
                ) ?: return OutputDeleteStatus.Failed
            deleteMediaItem(
                uriValue = reference.uri,
                collection = MediaOutputCollection.Downloads,
                expectedIdentity = identity,
            )
        } else {
            deleteSafPdf(reference)
        }

    fun deleteImage(cached: CachedScan, reference: ImageOutputRef): OutputDeleteStatus {
        if (reference.page !in 1..cached.pages.size) return OutputDeleteStatus.Failed
        val identity =
            expectedMediaIdentity(
                reference.displayName,
                reference.mimeType,
                reference.ownerPackageName,
                scanPageFileName(cached.baseName, reference.page),
                JPEG_MIME_TYPE,
            ) ?: return OutputDeleteStatus.Failed
        return deleteMediaItem(
            uriValue = reference.uri,
            collection = MediaOutputCollection.Images,
            expectedIdentity = identity,
        )
    }

    private fun expectedMediaIdentity(
        displayName: String?,
        mimeType: String?,
        ownerPackageName: String?,
        legacyDisplayName: String,
        requiredMimeType: String,
    ): MediaItemRow? {
        if (displayName == null && mimeType == null && ownerPackageName == null) {
            return MediaItemRow(0L, legacyDisplayName, requiredMimeType, context.packageName)
        }
        val exactDisplayName = displayName ?: return null
        val exactMimeType = mimeType ?: return null
        val exactOwnerPackageName = ownerPackageName ?: return null
        if (
            !isProviderDisplayName(exactDisplayName) ||
                exactMimeType != requiredMimeType ||
                exactOwnerPackageName != context.packageName
        ) {
            return null
        }
        return MediaItemRow(0L, exactDisplayName, exactMimeType, exactOwnerPackageName)
    }

    private fun deleteMediaItem(
        uriValue: String,
        collection: MediaOutputCollection,
        expectedIdentity: MediaItemRow,
    ): OutputDeleteStatus {
        val address = parseMediaItemAddress(uriValue) ?: return OutputDeleteStatus.Failed
        if (address.collection != collection) return OutputDeleteStatus.Failed
        val uri = Uri.parse(uriValue)
        val canonical =
            when (collection) {
                MediaOutputCollection.Images ->
                    MediaStore.Images.Media.getContentUri(address.volume, address.id)
                MediaOutputCollection.Downloads ->
                    MediaStore.Downloads.getContentUri(address.volume, address.id)
            }
        if (canonical != uri) return OutputDeleteStatus.Failed
        val expected =
            ExpectedMediaItem(
                address.id,
                expectedIdentity.displayName,
                expectedIdentity.mimeType,
                expectedIdentity.ownerPackageName,
            )
        return try {
            when (queryMediaItem(uri, expected)) {
                QueryResult.Absent -> OutputDeleteStatus.Absent
                QueryResult.Invalid -> OutputDeleteStatus.Failed
                QueryResult.Exact -> {
                    val deletedRows =
                        resolver.delete(
                            uri,
                            MEDIA_DELETE_SELECTION,
                            mediaDeleteSelectionArgs(expected),
                        )
                    if (mediaDeleteSucceeded(deletedRows)) {
                        OutputDeleteStatus.Deleted
                    } else if (
                        deletedRows == 0 && queryMediaItem(uri, expected) == QueryResult.Absent
                    ) {
                        OutputDeleteStatus.Absent
                    } else {
                        OutputDeleteStatus.Failed
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            OutputDeleteStatus.Failed
        }
    }

    private fun queryMediaItem(uri: Uri, expected: ExpectedMediaItem): QueryResult {
        val cursor =
            resolver.query(
                uri,
                MEDIA_PROJECTION,
                null,
                null,
                null,
            ) ?: return QueryResult.Invalid
        return cursor.use {
            if (!it.moveToFirst()) return@use QueryResult.Absent
            val row =
                MediaItemRow(
                    id = it.requiredLong(MediaStore.MediaColumns._ID),
                    displayName = it.requiredString(MediaStore.MediaColumns.DISPLAY_NAME),
                    mimeType = it.requiredString(MediaStore.MediaColumns.MIME_TYPE),
                    ownerPackageName = it.requiredString(MediaStore.MediaColumns.OWNER_PACKAGE_NAME),
                )
            if (it.moveToNext() || !isExactMediaItem(row, expected)) {
                QueryResult.Invalid
            } else {
                QueryResult.Exact
            }
        }
    }

    private fun deleteSafPdf(reference: PdfOutputRef): OutputDeleteStatus {
        if (reference.mimeType != PDF_MIME_TYPE || reference.ownerPackageName != null) {
            return OutputDeleteStatus.Failed
        }
        val tree = exactContentUri(reference.treeUri ?: return OutputDeleteStatus.Failed)
            ?: return OutputDeleteStatus.Failed
        val document = exactContentUri(reference.uri) ?: return OutputDeleteStatus.Failed
        if (tree.authority != document.authority) return OutputDeleteStatus.Failed
        return try {
            if (
                !DocumentsContract.isTreeUri(tree) ||
                    !DocumentsContract.isDocumentUri(context, document) ||
                    resolver.persistedUriPermissions.none {
                        it.uri == tree && it.isReadPermission && it.isWritePermission
                    }
            ) {
                return OutputDeleteStatus.Failed
            }
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            val documentId = DocumentsContract.getDocumentId(document)
            val root = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
            if (
                rootId == documentId ||
                    DocumentsContract.getTreeDocumentId(document) != rootId ||
                    DocumentsContract.buildTreeDocumentUri(tree.authority!!, rootId) != tree ||
                    DocumentsContract.buildDocumentUriUsingTree(tree, documentId) != document ||
                    context.checkUriPermission(
                        document,
                        Process.myPid(),
                        Process.myUid(),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    ) != PackageManager.PERMISSION_GRANTED
            ) {
                return OutputDeleteStatus.Failed
            }
            when (
                querySafDocument(
                    document,
                    documentId,
                    reference.displayName ?: return OutputDeleteStatus.Failed,
                )
            ) {
                QueryResult.Absent ->
                    confirmSafDocumentAbsent(root, rootId, document, documentId, reference.displayName)
                QueryResult.Invalid -> OutputDeleteStatus.Failed
                QueryResult.Exact -> {
                    if (!DocumentsContract.isChildDocument(resolver, root, document)) {
                        OutputDeleteStatus.Failed
                    } else {
                        try {
                            if (DocumentsContract.deleteDocument(resolver, document)) {
                                OutputDeleteStatus.Deleted
                            } else {
                                confirmSafDocumentAbsent(
                                    root,
                                    rootId,
                                    document,
                                    documentId,
                                    reference.displayName,
                                )
                            }
                        } catch (_: FileNotFoundException) {
                            confirmSafDocumentAbsent(
                                root,
                                rootId,
                                document,
                                documentId,
                                reference.displayName,
                            )
                        }
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            OutputDeleteStatus.Failed
        }
    }

    private fun confirmSafDocumentAbsent(
        root: Uri,
        rootId: String,
        document: Uri,
        documentId: String,
        displayName: String,
    ): OutputDeleteStatus {
        if (querySafDocument(document, documentId, displayName) != QueryResult.Absent) {
            return OutputDeleteStatus.Failed
        }
        val rootExact = querySafRoot(root, rootId)
        val documentIsChild = DocumentsContract.isChildDocument(resolver, root, document)
        return safMissingDocumentStatus(rootExact, documentIsChild)
    }

    private fun querySafRoot(uri: Uri, rootId: String): Boolean {
        val cursor = resolver.query(uri, SAF_ROOT_PROJECTION, null, null, null) ?: return false
        return cursor.use {
            if (!it.moveToFirst()) return@use false
            val exact =
                it.requiredString(DocumentsContract.Document.COLUMN_DOCUMENT_ID) == rootId &&
                    it.requiredString(DocumentsContract.Document.COLUMN_MIME_TYPE) ==
                    DocumentsContract.Document.MIME_TYPE_DIR
            exact && !it.moveToNext()
        }
    }

    private fun querySafDocument(
        uri: Uri,
        documentId: String,
        displayName: String,
    ): QueryResult {
        val cursor = resolver.query(uri, SAF_PROJECTION, null, null, null)
            ?: return QueryResult.Invalid
        return cursor.use {
            if (!it.moveToFirst()) return@use QueryResult.Absent
            val row =
                SafDocumentRow(
                    documentId = it.requiredString(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                    displayName = it.requiredString(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    mimeType = it.requiredString(DocumentsContract.Document.COLUMN_MIME_TYPE),
                    flags = it.requiredLong(DocumentsContract.Document.COLUMN_FLAGS).toInt(),
                )
            if (it.moveToNext() || !isExactSafDocument(row, documentId, displayName)) {
                QueryResult.Invalid
            } else {
                QueryResult.Exact
            }
        }
    }

    private fun exactContentUri(value: String): Uri? =
        try {
            val parsed = URI(value)
            if (
                parsed.scheme != ContentResolver.SCHEME_CONTENT ||
                    parsed.rawAuthority.isNullOrBlank() ||
                    parsed.rawQuery != null ||
                    parsed.rawFragment != null ||
                    parsed.rawUserInfo != null ||
                    parsed.port != -1
            ) {
                null
            } else {
                Uri.parse(value)
            }
        } catch (_: Exception) {
            null
        }
}

private fun Cursor.requiredString(column: String): String {
    val index = getColumnIndex(column)
    if (index < 0 || isNull(index)) throw IOException("Provider row is missing $column")
    return getString(index)
}

private fun Cursor.requiredLong(column: String): Long {
    val index = getColumnIndex(column)
    if (index < 0 || isNull(index)) throw IOException("Provider row is missing $column")
    return getLong(index)
}

private enum class QueryResult {
    Exact,
    Absent,
    Invalid,
}

private const val PDF_MIME_TYPE = "application/pdf"
private const val JPEG_MIME_TYPE = "image/jpeg"
internal const val MEDIA_DELETE_SELECTION =
    "_id = ? AND _display_name = ? AND mime_type = ? AND owner_package_name = ?"
private val MEDIA_PROJECTION =
    arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
    )
private val SAF_PROJECTION =
    arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_FLAGS,
    )
private val SAF_ROOT_PROJECTION =
    arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
    )
private val VOLUME_NAME = Regex("[A-Za-z0-9_-]+")
