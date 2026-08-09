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

internal data class AppSettings(
    val savePdf: Boolean = true,
    val saveImages: Boolean = true,
    val albumName: String = DEFAULT_ALBUM_NAME,
    val multipage: Boolean = true,
    val allowGallery: Boolean = true,
    val emailSubject: String = "Scanned document",
    val emailBody: String = "",
    val pdfTreeUri: String? = null,
)

internal fun localizedDefaultEmailSubject(
    current: String,
    targetDefault: String,
    supportedDefaults: Set<String>,
): String = if (current in supportedDefaults) targetDefault else current

internal data class UiMessage(
    val resourceId: Int,
    val formatArgs: List<Int> = emptyList(),
)

internal data class CachedScan(
    val baseName: String,
    val pages: List<File>,
    val pdf: File,
    val entryId: String? = null,
)

internal data class SavedScan(
    val cached: CachedScan,
    val galleryPages: List<Uri>,
    val savedPdf: Uri?,
    val savedPdfTree: Uri? = null,
    val warnings: List<UiMessage> = emptyList(),
    val outputMetadataValid: Boolean = false,
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
        val outputSaveInProgress: Boolean = false,
    ) : ScreenState

    data class Recent(
        val scans: List<RecentScan>,
        val message: UiMessage? = null,
    ) : ScreenState

    data class Failure(val message: UiMessage) : ScreenState
}

internal enum class AppBackAction {
    CloseSettings,
    CollapseFileDetails,
    ShowRecent,
    LaunchScanner,
    Consume,
}

internal fun appBackAction(
    settingsOpen: Boolean,
    fileDetailsOpen: Boolean,
    state: ScreenState,
): AppBackAction =
    when {
        settingsOpen -> AppBackAction.CloseSettings
        state is ScreenState.Result && state.outputSaveInProgress -> AppBackAction.Consume
        fileDetailsOpen && state is ScreenState.Result -> AppBackAction.CollapseFileDetails
        state is ScreenState.Processing && !state.canNavigateBack -> AppBackAction.Consume
        state is ScreenState.Recent -> AppBackAction.LaunchScanner
        else -> AppBackAction.ShowRecent
    }

private val scanNameFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT)

internal fun scanBaseName(clock: Clock): String =
    "Scan_${LocalDateTime.now(clock).format(scanNameFormatter)}"
