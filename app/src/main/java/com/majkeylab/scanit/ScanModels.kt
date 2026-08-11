package com.majkeylab.scanit

import android.graphics.Bitmap
import android.net.Uri
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

internal const val DEFAULT_ALBUM_NAME = "Scan to PDF"
internal const val MAX_SCAN_PAGES = 20

internal data class AppSettings(
    val savePdf: Boolean = true,
    val saveImages: Boolean = true,
    val albumName: String = DEFAULT_ALBUM_NAME,
    val multipage: Boolean = true,
    val allowGallery: Boolean = true,
    val emailSubject: String = "Scanned document",
    val emailBody: String = "",
    val pdfTreeUri: String? = null,
    val deletePdfAfterShare: Boolean = false,
    val deleteImagesAfterShare: Boolean = false,
    val appearance: ScanAppearanceSettings = ScanAppearanceSettings(),
    val pdfSizeTarget: PdfSizeTarget = PdfSizeTarget.Original,
)

internal enum class RecentDeleteTarget {
    Pdf,
    Images,
    Both,
    RemoveFromRecent,
}

internal enum class OutputDeleteStatus {
    Deleted,
    Absent,
    IdentityMismatch,
    Failed,
}

internal enum class OutputDeleteOperationResult {
    Completed,
    Stale,
    IdentityMismatch,
    Failed,
    Partial,
    MetadataFailed,
    CacheFailed,
}

internal fun recentDeleteMessage(result: OutputDeleteOperationResult): UiMessage? =
    when (result) {
        OutputDeleteOperationResult.Completed -> null
        OutputDeleteOperationResult.Partial -> UiMessage(R.string.recent_delete_partial)
        OutputDeleteOperationResult.MetadataFailed -> UiMessage(R.string.recent_delete_metadata_failed)
        OutputDeleteOperationResult.CacheFailed -> UiMessage(R.string.recent_delete_cache_failed)
        OutputDeleteOperationResult.Stale,
        OutputDeleteOperationResult.IdentityMismatch,
        OutputDeleteOperationResult.Failed,
        -> UiMessage(R.string.recent_delete_failed)
    }

internal enum class ShareCleanupKind(val wireValue: String) {
    Pdf("pdf"),
    Images("images"),
}

internal data class ShareCleanupRequest(
    val cacheId: String,
    val entryId: String,
    val kind: ShareCleanupKind,
)

internal fun shareCleanupRequest(
    scan: SavedScan,
    kind: ShareCleanupKind,
    enabled: Boolean,
): ShareCleanupRequest? =
    shareCleanupRequest(
        cacheId = scan.cached.baseName,
        entryId = scan.cached.entryId,
        metadataValid = scan.outputMetadataValid,
        kind = kind,
        available =
            when (kind) {
                ShareCleanupKind.Pdf -> scan.savedPdf != null && scan.savedPdfDeleteVerified
                ShareCleanupKind.Images ->
                    scan.galleryPages.isNotEmpty() && scan.savedImagesDeleteVerified
            },
        enabled = enabled,
    )

internal fun shareCleanupRequest(
    cacheId: String,
    entryId: String?,
    metadataValid: Boolean,
    kind: ShareCleanupKind,
    available: Boolean,
    enabled: Boolean,
): ShareCleanupRequest? {
    if (
        !enabled ||
            !metadataValid ||
            !available ||
            entryId == null ||
            !isSafeCacheId(cacheId) ||
            !isCanonicalUuid(entryId)
    ) {
        return null
    }
    return ShareCleanupRequest(cacheId, entryId, kind)
}

internal fun decodeShareCleanupRequest(
    cacheId: String?,
    entryId: String?,
    kindValue: String?,
): ShareCleanupRequest? {
    if (cacheId == null || entryId == null || !isSafeCacheId(cacheId) || !isCanonicalUuid(entryId)) {
        return null
    }
    val kind = ShareCleanupKind.entries.firstOrNull { it.wireValue == kindValue } ?: return null
    return ShareCleanupRequest(cacheId, entryId, kind)
}

internal data class ShareCleanupCompletionPolicy(
    val clear: Boolean,
    val warn: Boolean,
)

internal fun shareCleanupCompletionPolicy(
    result: OutputDeleteOperationResult,
): ShareCleanupCompletionPolicy =
    ShareCleanupCompletionPolicy(
        clear =
            result == OutputDeleteOperationResult.Completed ||
                result == OutputDeleteOperationResult.Stale ||
                result == OutputDeleteOperationResult.IdentityMismatch,
        warn =
            result != OutputDeleteOperationResult.Completed &&
                result != OutputDeleteOperationResult.Stale,
    )

internal class DirtyRefreshGate {
    private var running = false
    private var dirty = false

    fun request(): Boolean {
        dirty = true
        if (running) return false
        running = true
        return true
    }

    fun consume(): Boolean {
        if (!running) return false
        if (dirty) {
            dirty = false
            return true
        }
        running = false
        return false
    }
}

internal data class OutputDeleteReduction(
    val metadata: OutputMetadata,
    val allRequestedRemoved: Boolean,
)

internal fun recentDeleteTargets(
    metadataValid: Boolean,
    hasPdf: Boolean,
    savedImageCount: Int,
    removeRecentPending: Boolean = false,
): List<RecentDeleteTarget> {
    if (removeRecentPending) return listOf(RecentDeleteTarget.RemoveFromRecent)
    if (!metadataValid || (!hasPdf && savedImageCount == 0)) {
        return listOf(RecentDeleteTarget.RemoveFromRecent)
    }
    return when {
        hasPdf && savedImageCount > 0 ->
            listOf(RecentDeleteTarget.Pdf, RecentDeleteTarget.Images, RecentDeleteTarget.Both)
        hasPdf -> listOf(RecentDeleteTarget.Pdf)
        else -> listOf(RecentDeleteTarget.Images)
    }
}

internal fun reduceOutputDeletion(
    metadata: OutputMetadata,
    target: RecentDeleteTarget,
    outcomes: Map<String, OutputDeleteStatus>,
): OutputDeleteReduction {
    require(target != RecentDeleteTarget.RemoveFromRecent) { "No durable output was selected" }
    val deletePdf = target == RecentDeleteTarget.Pdf || target == RecentDeleteTarget.Both
    val deleteImages = target == RecentDeleteTarget.Images || target == RecentDeleteTarget.Both
    val requestedUris =
        buildList {
            if (deletePdf) metadata.pdf?.let { add(it.uri) }
            if (deleteImages) metadata.images.forEach { add(it.uri) }
        }
    require(requestedUris.isNotEmpty()) { "Selected durable output is unavailable" }
    require(outcomes.keys == requestedUris.toSet()) { "Delete results do not match selected outputs" }
    fun removed(uri: String) =
        outcomes.getValue(uri) == OutputDeleteStatus.Deleted ||
            outcomes.getValue(uri) == OutputDeleteStatus.Absent
    return OutputDeleteReduction(
        metadata =
            metadata.copy(
                pdf = metadata.pdf?.takeUnless { deletePdf && removed(it.uri) },
                images = metadata.images.filterNot { deleteImages && removed(it.uri) },
            ),
        allRequestedRemoved =
            outcomes.values.all {
                it == OutputDeleteStatus.Deleted || it == OutputDeleteStatus.Absent
            },
    )
}

internal fun localizedDefaultEmailSubject(
    current: String,
    targetDefault: String,
    supportedDefaults: Set<String>,
): String = if (current in supportedDefaults) targetDefault else current

internal data class UiMessage(
    val resourceId: Int,
    val formatArgs: List<Any> = emptyList(),
)

internal fun pdfSizeTargetWarning(
    target: PdfSizeTarget,
    bytes: Long,
): UiMessage? {
    val targetBytes = target.maxBytes ?: return null
    if (bytes <= 0L || bytes <= targetBytes) return null
    return UiMessage(
        R.string.pdf_size_target_not_met,
        listOf(
            (targetBytes / PDF_DISPLAY_BYTES).toInt(),
            bytes / PDF_DISPLAY_BYTES.toDouble(),
        ),
    )
}

internal data class CachedScan(
    val baseName: String,
    val pages: List<File>,
    val pdf: File,
    val entryId: String? = null,
    val sourcePages: List<File> = emptyList(),
    val appearance: ScanAppearance? = null,
    val appearanceSettings: ScanAppearanceSettings? = null,
    val pdfSizeTarget: PdfSizeTarget = PdfSizeTarget.Original,
    val lineageCacheId: String = baseName,
    val parentCacheId: String? = null,
    val parentEntryId: String? = null,
)

private const val PDF_DISPLAY_BYTES = 1_000_000L

internal data class SavedScan(
    val cached: CachedScan,
    val galleryPages: List<Uri>,
    val savedPdf: Uri?,
    val savedPdfTree: Uri? = null,
    val warnings: List<UiMessage> = emptyList(),
    val outputMetadataValid: Boolean = false,
    val savedPdfDeleteVerified: Boolean = false,
    val savedImagesDeleteVerified: Boolean = false,
)

internal enum class SaveNowTarget {
    Pdf,
    Images,
    Both,
}

internal enum class SavedOutputKind {
    Pdf,
    Images,
}

internal fun saveNowTargets(
    pdfMissing: Boolean,
    savedImageCount: Int,
    pageCount: Int,
): List<SaveNowTarget> {
    if (pageCount <= 0 || savedImageCount !in 0..pageCount) return emptyList()
    return missingSaveNowTargets(pdfMissing, savedImageCount == 0)
}

internal fun saveNowTargets(scan: SavedScan): List<SaveNowTarget> {
    if (!scan.outputMetadataValid || scan.cached.entryId == null) return emptyList()
    return saveNowTargets(
        pdfMissing = scan.savedPdf == null,
        savedImageCount = scan.galleryPages.size,
        pageCount = scan.cached.pages.size,
    )
}

internal fun matchingOutputMetadata(
    metadata: OutputMetadata?,
    expectedCacheId: String,
    expectedEntryId: String,
): OutputMetadata? =
    metadata?.takeIf {
        it.cacheId == expectedCacheId && it.entryId == expectedEntryId
    }

private fun missingSaveNowTargets(
    pdfMissing: Boolean,
    imagesMissing: Boolean,
): List<SaveNowTarget> =
    when {
        pdfMissing && imagesMissing ->
            listOf(SaveNowTarget.Pdf, SaveNowTarget.Images, SaveNowTarget.Both)
        pdfMissing -> listOf(SaveNowTarget.Pdf)
        imagesMissing -> listOf(SaveNowTarget.Images)
        else -> emptyList()
    }

internal fun mergeSaveNowWarnings(
    existing: List<UiMessage>,
    successful: Set<SavedOutputKind>,
    reloadSucceeded: Boolean,
    added: List<UiMessage>,
): List<UiMessage> {
    val cleared =
        buildSet {
            if (SavedOutputKind.Pdf in successful) add(R.string.pdf_save_failed)
            if (SavedOutputKind.Images in successful) add(R.string.images_save_failed)
            if (reloadSucceeded) add(R.string.state_update_failed)
        }
    return (existing.filterNot { it.resourceId in cleared } + added).distinct()
}

internal data class ResultSaveAction(
    val cacheId: String,
    val entryId: String,
    val generation: Long,
)

internal fun matchingSavedScan(
    scan: SavedScan?,
    action: ResultSaveAction,
): SavedScan? =
    scan?.takeIf {
        it.outputMetadataValid &&
            it.cached.baseName == action.cacheId &&
            it.cached.entryId == action.entryId
    }

internal class ResultSaveGate {
    private var generation = 0L
    private var current: ResultSaveAction? = null

    fun begin(cacheId: String, entryId: String): ResultSaveAction? {
        if (current != null || cacheId.isBlank() || entryId.isBlank()) return null
        check(generation < Long.MAX_VALUE) { "Result save generation exhausted" }
        return ResultSaveAction(cacheId, entryId, ++generation).also { current = it }
    }

    fun isCurrent(action: ResultSaveAction, cacheId: String, entryId: String): Boolean =
        current == action && action.cacheId == cacheId && action.entryId == entryId

    fun complete(action: ResultSaveAction) {
        if (current == action) current = null
    }

    fun invalidate() {
        current = null
    }
}

internal data class SavedPdfOutput(
    val uri: Uri,
    val treeUri: Uri?,
    val warning: UiMessage?,
    val displayName: String? = null,
    val mimeType: String? = null,
    val ownerPackageName: String? = null,
    val byteLength: Long? = null,
    val sha256: String? = null,
)

internal data class SavedMediaOutput(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val ownerPackageName: String,
    val byteLength: Long,
    val sha256: String,
)

internal class PendingMediaFailure(
    val rollbackFailed: Boolean,
    cause: Exception,
) : IOException("Pending media write failed", cause)

internal class PdfSaveFailure(
    val warning: UiMessage?,
    cause: Exception,
    rollbackFailed: Boolean = false,
) : IOException("PDF save failed", cause) {
    val rollbackFailed = outputRollbackFailed(cause, rollbackFailed)
}

internal class ImageSaveFailure(
    cause: Exception,
    rollbackFailed: Boolean = false,
) : IOException("Image save failed", cause) {
    val rollbackFailed = outputRollbackFailed(cause, rollbackFailed)
}

internal fun pdfSaveFailureMessages(failure: PdfSaveFailure): List<UiMessage> =
    listOfNotNull(
        UiMessage(R.string.pdf_save_failed),
        failure.warning,
        UiMessage(R.string.document_save_partial_failed).takeIf { failure.rollbackFailed },
    ).distinct()

internal fun imageSaveFailureMessages(failure: ImageSaveFailure): List<UiMessage> =
    listOfNotNull(
        UiMessage(R.string.images_save_failed),
        UiMessage(R.string.document_save_partial_failed).takeIf { failure.rollbackFailed },
    ).distinct()

private fun outputRollbackFailed(cause: Exception, rollbackFailed: Boolean): Boolean =
    rollbackFailed || (cause as? PendingMediaFailure)?.rollbackFailed == true

internal fun safFallbackWarning(
    cleanupFailed: Boolean,
    savedToDownloads: Boolean,
): UiMessage? =
    when {
        cleanupFailed && savedToDownloads -> UiMessage(R.string.saf_incomplete_warning)
        cleanupFailed -> UiMessage(R.string.saf_cleanup_warning)
        savedToDownloads -> UiMessage(R.string.saf_fallback_warning)
        else -> null
    }

internal enum class RestoredRoute {
    Scanner,
    Recent,
    Result,
}

internal fun restoredRoute(savedRoute: String?, cacheId: String?): RestoredRoute =
    when (savedRoute) {
        null -> RestoredRoute.Scanner
        "scanner" -> RestoredRoute.Scanner
        "result" -> if (cacheId.isNullOrBlank()) RestoredRoute.Recent else RestoredRoute.Result
        "recent" -> RestoredRoute.Recent
        else -> RestoredRoute.Recent
    }

internal data class InitialNavigation(
    val route: RestoredRoute,
    val cacheId: String?,
)

internal fun initialNavigation(
    savedRoute: String?,
    savedCacheId: String?,
    activeResultCacheId: String?,
): InitialNavigation {
    val durableCacheId = activeResultCacheId?.takeIf(::isSafeActiveResultCacheId)
    if (durableCacheId != null) {
        return InitialNavigation(RestoredRoute.Result, durableCacheId)
    }
    val route = restoredRoute(savedRoute, savedCacheId)
    return InitialNavigation(
        route = if (route == RestoredRoute.Result) RestoredRoute.Recent else route,
        cacheId = null,
    )
}

internal sealed interface ScreenState {
    data object Ready : ScreenState

    data class Processing(
        val message: UiMessage,
        val canNavigateBack: Boolean,
    ) : ScreenState

    data class Result(
        val scan: SavedScan,
        val thumbnail: Bitmap?,
        val selectedPageIndex: Int = 0,
        val pagePreviewLoading: Boolean = false,
        val outputSaveInProgress: Boolean = false,
        val appearanceApplyInProgress: Boolean = false,
        val appearanceMessage: UiMessage? = null,
    ) : ScreenState

    data class Recent(
        val scans: List<RecentScan>,
        val message: UiMessage? = null,
        val deletionInProgress: Boolean = false,
    ) : ScreenState

    data class Failure(val message: UiMessage) : ScreenState
}

internal enum class AppBackAction {
    CloseSettings,
    CollapseFileDetails,
    CollapseAppearance,
    ShowRecent,
    LaunchScanner,
    Consume,
}

internal fun appBackAction(
    settingsOpen: Boolean,
    fileDetailsOpen: Boolean,
    state: ScreenState,
    appearanceOpen: Boolean = false,
): AppBackAction =
    when {
        settingsOpen -> AppBackAction.CloseSettings
        state is ScreenState.Result &&
            (state.outputSaveInProgress || state.appearanceApplyInProgress) -> AppBackAction.Consume
        appearanceOpen && state is ScreenState.Result -> AppBackAction.CollapseAppearance
        fileDetailsOpen && state is ScreenState.Result -> AppBackAction.CollapseFileDetails
        state is ScreenState.Processing && !state.canNavigateBack -> AppBackAction.Consume
        state is ScreenState.Recent && state.deletionInProgress -> AppBackAction.Consume
        state is ScreenState.Recent -> AppBackAction.LaunchScanner
        else -> AppBackAction.ShowRecent
    }

private val scanNameFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT)

internal fun scanBaseName(clock: Clock): String =
    "Scan_${LocalDateTime.now(clock).format(scanNameFormatter)}"
