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
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
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
    val ownerPackageName: String?,
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
    val removed =
        outcomes.count {
            it == OutputDeleteStatus.Deleted || it == OutputDeleteStatus.Absent
        }
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
        } else if (outcomes.all { it == OutputDeleteStatus.IdentityMismatch }) {
            OutputDeleteOperationResult.IdentityMismatch
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

internal fun safChildStructureIsValid(
    sameAuthority: Boolean,
    rootDocumentId: String,
    returnedTreeDocumentId: String,
    returnedDocumentId: String,
    returnedUriIsCanonical: Boolean,
): Boolean =
    sameAuthority &&
        rootDocumentId.isNotEmpty() &&
        returnedTreeDocumentId == rootDocumentId &&
        returnedDocumentId != rootDocumentId &&
        returnedDocumentId.isNotEmpty() &&
        returnedUriIsCanonical

internal fun mediaDeleteSelectionArgs(expected: ExpectedMediaItem): Array<String> =
    arrayOf(
        expected.id.toString(),
        expected.displayName,
        expected.mimeType,
        expected.ownerPackageName,
    )

internal enum class ExactItemQuery {
    Exact,
    Absent,
    IdentityMismatch,
    Failed,
}

internal fun deleteVerifiedMediaOutput(
    fingerprint: OutputFingerprint,
    query: () -> ExactItemQuery,
    open: () -> InputStream?,
    delete: () -> Int,
): OutputDeleteStatus =
    providerDeleteResult {
        when (query()) {
            ExactItemQuery.Absent -> OutputDeleteStatus.Absent
            ExactItemQuery.IdentityMismatch -> OutputDeleteStatus.IdentityMismatch
            ExactItemQuery.Failed -> OutputDeleteStatus.Failed
            ExactItemQuery.Exact -> {
                val input = open() ?: return@providerDeleteResult OutputDeleteStatus.Failed
                when (input.use { checkOutputFingerprint(it, fingerprint) }) {
                    OutputFingerprintCheck.Exact -> Unit
                    OutputFingerprintCheck.Mismatch ->
                        return@providerDeleteResult OutputDeleteStatus.IdentityMismatch
                    OutputFingerprintCheck.Failed ->
                        return@providerDeleteResult OutputDeleteStatus.Failed
                }
                when (query()) {
                    ExactItemQuery.Absent -> return@providerDeleteResult OutputDeleteStatus.Absent
                    ExactItemQuery.IdentityMismatch ->
                        return@providerDeleteResult OutputDeleteStatus.IdentityMismatch
                    ExactItemQuery.Failed -> return@providerDeleteResult OutputDeleteStatus.Failed
                    ExactItemQuery.Exact -> Unit
                }
                val deleted = delete()
                if (deleted == 1) return@providerDeleteResult OutputDeleteStatus.Deleted
                if (deleted != 0) return@providerDeleteResult OutputDeleteStatus.Failed
                when (query()) {
                    ExactItemQuery.Absent -> OutputDeleteStatus.Absent
                    ExactItemQuery.IdentityMismatch -> OutputDeleteStatus.IdentityMismatch
                    ExactItemQuery.Exact,
                    ExactItemQuery.Failed,
                    -> OutputDeleteStatus.Failed
                }
            }
        }
    }

internal fun deleteVerifiedSafOutput(
    fingerprint: OutputFingerprint,
    query: () -> ExactItemQuery,
    open: () -> InputStream?,
    isChild: () -> Boolean,
    delete: () -> Boolean,
    confirmAbsent: () -> OutputDeleteStatus,
): OutputDeleteStatus =
    providerDeleteResult {
        when (query()) {
            ExactItemQuery.Absent -> confirmAbsent()
            ExactItemQuery.IdentityMismatch -> OutputDeleteStatus.IdentityMismatch
            ExactItemQuery.Failed -> OutputDeleteStatus.Failed
            ExactItemQuery.Exact -> {
                val input = open() ?: return@providerDeleteResult OutputDeleteStatus.Failed
                when (input.use { checkOutputFingerprint(it, fingerprint) }) {
                    OutputFingerprintCheck.Exact -> Unit
                    OutputFingerprintCheck.Mismatch ->
                        return@providerDeleteResult OutputDeleteStatus.IdentityMismatch
                    OutputFingerprintCheck.Failed ->
                        return@providerDeleteResult OutputDeleteStatus.Failed
                }
                when (query()) {
                    ExactItemQuery.Absent -> return@providerDeleteResult confirmAbsent()
                    ExactItemQuery.IdentityMismatch ->
                        return@providerDeleteResult OutputDeleteStatus.IdentityMismatch
                    ExactItemQuery.Failed -> return@providerDeleteResult OutputDeleteStatus.Failed
                    ExactItemQuery.Exact -> Unit
                }
                if (!isChild()) return@providerDeleteResult OutputDeleteStatus.IdentityMismatch
                val deleted =
                    try {
                        delete()
                    } catch (_: FileNotFoundException) {
                        false
                    }
                when (confirmAbsent()) {
                    OutputDeleteStatus.Absent ->
                        if (deleted) OutputDeleteStatus.Deleted else OutputDeleteStatus.Absent
                    else -> OutputDeleteStatus.Failed
                }
            }
        }
    }

private inline fun providerDeleteResult(operation: () -> OutputDeleteStatus): OutputDeleteStatus =
    try {
        operation()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        OutputDeleteStatus.Failed
    }

internal fun isExactSafDocument(
    row: SafDocumentRow,
    expectedDocumentId: String,
    expectedDisplayName: String,
    expectedMimeType: String = PDF_MIME_TYPE,
): Boolean =
    row.documentId == expectedDocumentId &&
        row.displayName == expectedDisplayName &&
        row.mimeType == expectedMimeType &&
        row.flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE != 0

internal fun matchingDeleteMetadata(
    metadata: OutputMetadata?,
    request: OutputDeleteRequest,
    ownerPackageName: String,
): OutputMetadata? =
    metadata?.takeIf {
        isSafeCacheId(request.cacheId) &&
            request.entryId != null &&
            request.target != RecentDeleteTarget.RemoveFromRecent &&
            it.cacheId == request.cacheId &&
            it.entryId == request.entryId &&
            it.hasCompleteExactDeleteInventory(ownerPackageName) &&
            it.hasVerifiedOutputFingerprints(request.target) &&
            it.hasExactDeleteIdentity(request.target, ownerPackageName)
    }

internal fun OutputMetadata.hasCompleteExactDeleteInventory(ownerPackageName: String): Boolean =
    (pdf == null ||
        pdf.outputFingerprint() != null && pdf.hasExactDeleteIdentity(ownerPackageName)) &&
        images.all {
            it.outputFingerprint() != null && it.hasExactDeleteIdentity(ownerPackageName)
        }

internal fun OutputMetadata.hasExactDeleteIdentity(
    target: RecentDeleteTarget,
    ownerPackageName: String,
): Boolean {
    return when (target) {
        RecentDeleteTarget.Pdf -> pdf?.hasExactDeleteIdentity(ownerPackageName) == true
        RecentDeleteTarget.Images ->
            images.isNotEmpty() && images.all { it.hasExactDeleteIdentity(ownerPackageName) }
        RecentDeleteTarget.Both ->
            pdf?.hasExactDeleteIdentity(ownerPackageName) == true &&
                images.isNotEmpty() &&
                images.all { it.hasExactDeleteIdentity(ownerPackageName) }
        RecentDeleteTarget.RemoveFromRecent -> false
    }
}

private fun PdfOutputRef.hasExactDeleteIdentity(ownerPackageName: String): Boolean =
    isProviderDisplayName(displayName) &&
        mimeType == PDF_MIME_TYPE &&
        if (treeUri == null) this.ownerPackageName == ownerPackageName
        else this.ownerPackageName == null

private fun ImageOutputRef.hasExactDeleteIdentity(ownerPackageName: String): Boolean =
    isProviderDisplayName(displayName) &&
        mimeType in IMAGE_MIME_TYPES &&
        if (treeUri == null) this.ownerPackageName == ownerPackageName
        else this.ownerPackageName == null

internal fun outputDeleteTargetIsAbsent(
    metadata: OutputMetadata,
    target: RecentDeleteTarget,
): Boolean =
    when (target) {
        RecentDeleteTarget.Pdf -> metadata.pdf == null
        RecentDeleteTarget.Images -> metadata.images.isEmpty()
        RecentDeleteTarget.Both -> metadata.pdf == null && metadata.images.isEmpty()
        RecentDeleteTarget.RemoveFromRecent -> false
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
    resultType: Int?,
    selectedComponentPresent: Boolean,
): Boolean =
    selectedComponentPresent &&
        (resultType == null || resultType == CHOOSER_RESULT_SELECTED_COMPONENT)

private const val CHOOSER_RESULT_SELECTED_COMPONENT = 0

internal fun pdfTreeGrantsToRelease(
    persisted: Set<String>,
    current: String?,
    live: Set<String>,
): Set<String> = persisted - live - setOfNotNull(current)

internal fun completePdfTreeGrantInventory(
    entries: List<OutputMetadataInventoryEntry>,
): Set<String>? = completeOutputTreeGrantInventory(entries)

internal fun completeOutputTreeGrantInventory(
    entries: List<OutputMetadataInventoryEntry>,
): Set<String>? {
    if (entries.any { it.sidecarPresent != (it.metadata != null) }) return null
    return entries.flatMapTo(mutableSetOf()) { entry ->
        entry.metadata?.outputTreeUris().orEmpty()
    }
}

internal fun reconcilePdfTreeGrants(
    context: Context,
    current: String?,
    live: Set<String>,
): Boolean = reconcileOutputTreeGrants(context, setOfNotNull(current), live)

internal fun reconcileOutputTreeGrants(
    context: Context,
    current: Set<String>,
    live: Set<String>,
): Boolean =
    withStorageTransaction {
        val resolver = context.contentResolver
        val permissions =
            resolver.persistedUriPermissions.filter { DocumentsContract.isTreeUri(it.uri) }
        val release = permissions.mapTo(mutableSetOf()) { it.uri.toString() } - current - live
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
        releasedAll
    }

internal class ExactOutputDeleter(private val context: Context) {
    private val resolver = context.contentResolver

    fun deletePdf(reference: PdfOutputRef): OutputDeleteStatus =
        reference.outputFingerprint()?.let { fingerprint ->
            if (reference.treeUri != null) {
                if (reference.ownerPackageName != null || reference.mimeType != PDF_MIME_TYPE) {
                    return@let OutputDeleteStatus.IdentityMismatch
                }
                return@let deleteSafOutput(
                    uriValue = reference.uri,
                    treeUriValue = reference.treeUri,
                    displayName = reference.displayName,
                    mimeType = reference.mimeType,
                    fingerprint = fingerprint,
                )
            }
            val identity =
                expectedMediaIdentity(
                    reference.displayName,
                    reference.mimeType,
                    reference.ownerPackageName,
                    PDF_MIME_TYPE,
                ) ?: return OutputDeleteStatus.IdentityMismatch
            deleteMediaItem(
                uriValue = reference.uri,
                collection = MediaOutputCollection.Downloads,
                expectedIdentity = identity,
                fingerprint = fingerprint,
            )
        } ?: OutputDeleteStatus.Failed

    fun deleteMediaOutput(
        output: SavedMediaOutput,
        collection: MediaOutputCollection,
    ): OutputDeleteStatus {
        val identity =
            expectedMediaIdentity(
                output.displayName,
                output.mimeType,
                output.ownerPackageName,
                when (collection) {
                    MediaOutputCollection.Images ->
                        output.mimeType.takeIf { it in IMAGE_MIME_TYPES }
                            ?: return OutputDeleteStatus.IdentityMismatch
                    MediaOutputCollection.Downloads -> PDF_MIME_TYPE
                },
            ) ?: return OutputDeleteStatus.IdentityMismatch
        val fingerprint =
            outputFingerprintOrNull(output.byteLength, output.sha256)
                ?: return OutputDeleteStatus.Failed
        return deleteMediaItem(output.uri.toString(), collection, identity, fingerprint)
    }

    fun deleteImage(cached: CachedScan, reference: ImageOutputRef): OutputDeleteStatus {
        if (reference.page !in 1..cached.pages.size) return OutputDeleteStatus.IdentityMismatch
        val fingerprint = reference.outputFingerprint() ?: return OutputDeleteStatus.Failed
        val mimeType = reference.mimeType?.takeIf { it in IMAGE_MIME_TYPES }
            ?: return OutputDeleteStatus.IdentityMismatch
        if (reference.treeUri != null) {
            if (reference.ownerPackageName != null) return OutputDeleteStatus.IdentityMismatch
            return deleteSafOutput(
                uriValue = reference.uri,
                treeUriValue = reference.treeUri,
                displayName = reference.displayName,
                mimeType = mimeType,
                fingerprint = fingerprint,
            )
        }
        val identity =
            expectedMediaIdentity(
                reference.displayName,
                mimeType,
                reference.ownerPackageName,
                mimeType,
            ) ?: return OutputDeleteStatus.IdentityMismatch
        return deleteMediaItem(
            uriValue = reference.uri,
            collection = MediaOutputCollection.Images,
            expectedIdentity = identity,
            fingerprint = fingerprint,
        )
    }

    private fun expectedMediaIdentity(
        displayName: String?,
        mimeType: String?,
        ownerPackageName: String?,
        requiredMimeType: String,
    ): MediaItemRow? {
        val exactDisplayName = displayName ?: return null
        val exactMimeType = mimeType ?: return null
        if (
            !isProviderDisplayName(exactDisplayName) ||
                exactMimeType != requiredMimeType ||
                ownerPackageName != context.packageName
        ) {
            return null
        }
        return MediaItemRow(0L, exactDisplayName, exactMimeType, ownerPackageName)
    }

    private fun deleteMediaItem(
        uriValue: String,
        collection: MediaOutputCollection,
        expectedIdentity: MediaItemRow,
        fingerprint: OutputFingerprint,
    ): OutputDeleteStatus {
        val address = parseMediaItemAddress(uriValue) ?: return OutputDeleteStatus.IdentityMismatch
        if (address.collection != collection) return OutputDeleteStatus.IdentityMismatch
        val uri = Uri.parse(uriValue)
        val canonical =
            when (collection) {
                MediaOutputCollection.Images ->
                    MediaStore.Images.Media.getContentUri(address.volume, address.id)
                MediaOutputCollection.Downloads ->
                    MediaStore.Downloads.getContentUri(address.volume, address.id)
            }
        if (canonical != uri) return OutputDeleteStatus.IdentityMismatch
        val owner = expectedIdentity.ownerPackageName ?: return OutputDeleteStatus.IdentityMismatch
        val expected =
            ExpectedMediaItem(
                address.id,
                expectedIdentity.displayName,
                expectedIdentity.mimeType,
                owner,
            )
        return deleteVerifiedMediaOutput(
            fingerprint = fingerprint,
            query = { queryMediaItem(uri, expected) },
            open = { resolver.openInputStream(uri) },
            delete = {
                resolver.delete(
                    uri,
                    MEDIA_DELETE_SELECTION,
                    mediaDeleteSelectionArgs(expected),
                )
            },
        )
    }

    private fun queryMediaItem(uri: Uri, expected: ExpectedMediaItem): ExactItemQuery {
        val cursor =
            resolver.query(
                uri,
                MEDIA_PROJECTION,
                null,
                null,
                null,
            ) ?: return ExactItemQuery.Failed
        return cursor.use {
            if (!it.moveToFirst()) return@use ExactItemQuery.Absent
            val row =
                MediaItemRow(
                    id = it.requiredLong(MediaStore.MediaColumns._ID),
                    displayName = it.requiredString(MediaStore.MediaColumns.DISPLAY_NAME),
                    mimeType = it.requiredString(MediaStore.MediaColumns.MIME_TYPE),
                    ownerPackageName = it.optionalString(MediaStore.MediaColumns.OWNER_PACKAGE_NAME),
                )
            if (it.moveToNext() || !isExactMediaItem(row, expected)) {
                ExactItemQuery.IdentityMismatch
            } else {
                ExactItemQuery.Exact
            }
        }
    }

    private fun deleteSafOutput(
        uriValue: String,
        treeUriValue: String,
        displayName: String?,
        mimeType: String?,
        fingerprint: OutputFingerprint,
    ): OutputDeleteStatus {
        val exactMimeType = mimeType ?: return OutputDeleteStatus.IdentityMismatch
        if (exactMimeType != PDF_MIME_TYPE && exactMimeType !in IMAGE_MIME_TYPES) {
            return OutputDeleteStatus.IdentityMismatch
        }
        val tree = exactContentUri(treeUriValue)
            ?: return OutputDeleteStatus.IdentityMismatch
        val document = exactContentUri(uriValue) ?: return OutputDeleteStatus.IdentityMismatch
        if (tree.authority != document.authority) return OutputDeleteStatus.IdentityMismatch
        return try {
            if (
                !DocumentsContract.isTreeUri(tree) ||
                    !DocumentsContract.isDocumentUri(context, document) ||
                    resolver.persistedUriPermissions.none {
                        it.uri == tree && it.isReadPermission && it.isWritePermission
                    }
            ) {
                return OutputDeleteStatus.IdentityMismatch
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
                return OutputDeleteStatus.IdentityMismatch
            }
            val exactDisplayName = displayName ?: return OutputDeleteStatus.IdentityMismatch
            deleteVerifiedSafOutput(
                fingerprint = fingerprint,
                query = { querySafDocument(document, documentId, exactDisplayName, exactMimeType) },
                open = { resolver.openInputStream(document) },
                isChild = { DocumentsContract.isChildDocument(resolver, root, document) },
                delete = { DocumentsContract.deleteDocument(resolver, document) },
                confirmAbsent = {
                    confirmSafDocumentAbsent(
                        root,
                        rootId,
                        document,
                        documentId,
                        exactDisplayName,
                        exactMimeType,
                    )
                },
            )
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
        mimeType: String,
    ): OutputDeleteStatus {
        if (querySafDocument(document, documentId, displayName, mimeType) != ExactItemQuery.Absent) {
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
        mimeType: String,
    ): ExactItemQuery {
        val cursor = resolver.query(uri, SAF_PROJECTION, null, null, null)
            ?: return ExactItemQuery.Failed
        return cursor.use {
            if (!it.moveToFirst()) return@use ExactItemQuery.Absent
            val row =
                SafDocumentRow(
                    documentId = it.requiredString(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                    displayName = it.requiredString(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    mimeType = it.requiredString(DocumentsContract.Document.COLUMN_MIME_TYPE),
                    flags = it.requiredLong(DocumentsContract.Document.COLUMN_FLAGS).toInt(),
                )
            if (it.moveToNext() || !isExactSafDocument(row, documentId, displayName, mimeType)) {
                ExactItemQuery.IdentityMismatch
            } else {
                ExactItemQuery.Exact
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

private fun Cursor.optionalString(column: String): String? {
    val index = getColumnIndex(column)
    if (index < 0) throw IOException("Provider row is missing $column")
    if (isNull(index)) return null
    return getString(index)
}

private const val PDF_MIME_TYPE = "application/pdf"
private const val JPEG_MIME_TYPE = "image/jpeg"
private const val PNG_MIME_TYPE = "image/png"
private val IMAGE_MIME_TYPES = setOf(JPEG_MIME_TYPE, PNG_MIME_TYPE)
internal const val MEDIA_DELETE_SELECTION =
    "_id = ? AND _display_name = ? AND mime_type = ? AND owner_package_name = ?"
internal const val MEDIA_PUBLISH_SELECTION = "$MEDIA_DELETE_SELECTION AND is_pending = 1"
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
