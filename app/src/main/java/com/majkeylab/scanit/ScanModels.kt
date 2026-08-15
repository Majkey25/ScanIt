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
internal const val MIN_IMAGE_EXPORT_DIMENSION = 320
internal const val MAX_IMAGE_EXPORT_DIMENSION = 6000
internal const val MAX_IMAGE_EXPORT_PIXELS = 12_000_000L

internal enum class ImageExportFormat(val wireValue: String, val mimeType: String?) {
    Original("original", null),
    Jpeg("jpeg", "image/jpeg"),
    Png("png", "image/png"),
}

internal enum class ImageSizePreset(val maxDimension: Int?) {
    Original(null),
    High(3840),
    Balanced(2560),
    Small(1600),
    Custom(null),
}

internal data class ImageExportOptions(
    val format: ImageExportFormat,
    val sizePreset: ImageSizePreset,
    val customMaxDimension: Int? = null,
    val treeUri: String? = null,
)

internal data class ResolvedImageExport(
    val format: ImageExportFormat,
    val maxDimension: Int?,
    val treeUri: String?,
) {
    val maxPixels: Long
        get() = MAX_IMAGE_EXPORT_PIXELS
}

internal sealed interface OutputChangeKind {
    data class PdfSize(val target: PdfSizeTarget) : OutputChangeKind

    data object PdfLocation : OutputChangeKind

    data class ImageSize(
        val preset: ImageSizePreset,
        val customMaxDimension: Int? = null,
    ) : OutputChangeKind

    data class ImageFormat(val format: ImageExportFormat) : OutputChangeKind

    data object ImageLocation : OutputChangeKind

    data class UnknownOutputCreate(val operationId: String) : OutputChangeKind {
        init {
            require(isCanonicalUuid(operationId)) { "Output operation ID is invalid" }
        }
    }
}

internal data class OutputChangeRequest(
    val cacheId: String,
    val entryId: String,
    val kind: OutputChangeKind,
    val generation: Long,
) {
    init {
        require(isSafeCacheId(cacheId)) { "Output cache ID is invalid" }
        require(isCanonicalUuid(entryId)) { "Output entry ID is invalid" }
        require(generation > 0L) { "Output generation is invalid" }
        validateOutputChangeKind(kind)
    }
}

internal data class OutputTreeSelection(
    val request: OutputChangeRequest,
    val uri: String,
    val grantFlags: Int,
) {
    init {
        require(uri.isNotBlank() && uri.length <= 4096 && '\u0000' !in uri) {
            "Output tree URI is invalid"
        }
        require(grantFlags == PDF_TREE_FLAGS) { "Output tree grant flags are invalid" }
    }
}

internal class OutputChangeGate(
    initialGeneration: Long = 0L,
    initialCurrent: OutputChangeRequest? = null,
) {
    private var generation = initialGeneration
    private var current = initialCurrent

    init {
        require(initialGeneration >= 0L) { "Output generation is invalid" }
        require(initialCurrent == null || initialCurrent.generation == initialGeneration) {
            "Restored output generation does not match its request"
        }
    }

    val active: OutputChangeRequest?
        get() = current

    val currentGeneration: Long
        get() = generation

    fun begin(
        cacheId: String,
        entryId: String,
        kind: OutputChangeKind,
    ): OutputChangeRequest? {
        if (current != null) return null
        return OutputChangeRequest(cacheId, entryId, kind, nextGeneration()).also { current = it }
    }

    fun isCurrent(
        request: OutputChangeRequest,
        cacheId: String,
        entryId: String?,
    ): Boolean = current == request && request.cacheId == cacheId && request.entryId == entryId

    fun complete(request: OutputChangeRequest) {
        if (current == request) current = null
    }

    fun invalidate() {
        current = null
        nextGeneration()
    }

    private fun nextGeneration(): Long {
        check(generation < Long.MAX_VALUE) { "Output generation exhausted" }
        generation += 1L
        return generation
    }
}

internal class OutputTreePickerGate(initialPending: OutputChangeRequest?) {
    var pending: OutputChangeRequest? = initialPending
        private set

    init {
        require(initialPending == null || initialPending.kind.isTreePicker()) {
            "Only a location change may launch the tree picker"
        }
    }

    fun claim(request: OutputChangeRequest): Boolean {
        if (pending != request) return false
        pending = null
        return true
    }

    fun offer(request: OutputChangeRequest): Boolean {
        if (pending != null || !request.kind.isTreePicker()) return false
        pending = request
        return true
    }

    fun clear() {
        pending = null
    }
}

internal fun encodeOutputTreePickerRequest(request: OutputChangeRequest): String {
    require(request.kind.isTreePicker()) { "Only a location change may be persisted for a picker" }
    return when (request.kind) {
        OutputChangeKind.PdfLocation ->
            listOf("1", request.cacheId, request.entryId, "pdf", request.generation.toString())
                .joinToString("\t")
        OutputChangeKind.ImageLocation ->
            listOf("1", request.cacheId, request.entryId, "image", request.generation.toString())
                .joinToString("\t")
        else -> error("Tree picker kind validation changed")
    }
}

internal fun decodeOutputTreePickerRequest(value: String?): OutputChangeRequest? {
    val parts = value?.split('\t') ?: return null
    if (parts.size != 5 || parts[0] != "1") return null
    val cacheId = parts[1]
    val entryId = parts[2]
    val generation = parts[4].toLongOrNull()?.takeIf { it > 0L } ?: return null
    val kind =
        when {
            parts.size == 5 && parts[3] == "pdf" -> OutputChangeKind.PdfLocation
            parts.size == 5 && parts[3] == "image" -> OutputChangeKind.ImageLocation
            else -> return null
        }
    return try {
        OutputChangeRequest(cacheId, entryId, kind, generation)
    } catch (_: IllegalArgumentException) {
        null
    }
}

internal fun matchingOutputChangeScan(
    scan: SavedScan?,
    request: OutputChangeRequest,
): SavedScan? =
    matchingOutputChangeIdentityScan(scan, request)?.takeIf(SavedScan::outputMetadataValid)

internal fun matchingOutputChangeIdentityScan(
    scan: SavedScan?,
    request: OutputChangeRequest,
): SavedScan? =
    scan?.takeIf {
        it.cached.baseName == request.cacheId && it.cached.entryId == request.entryId
    }

internal fun replacementFailurePublication(
    current: SavedScan,
    recovered: SavedScan?,
    request: OutputChangeRequest,
): SavedScan? =
    matchingOutputChangeIdentityScan(recovered, request)
        ?: matchingOutputChangeIdentityScan(current, request)

internal fun unknownOutputAcknowledgementMatches(
    scan: SavedScan,
    acknowledgement: UnknownOutputCreateAcknowledgement,
    request: OutputChangeRequest,
): Boolean =
    scan.unknownOutputCreateAcknowledgement == acknowledgement &&
        request.cacheId == acknowledgement.cacheId &&
        request.entryId == acknowledgement.entryId &&
        (request.kind as? OutputChangeKind.UnknownOutputCreate)?.operationId ==
        acknowledgement.operationId

internal fun unknownOutputAcknowledgementRefreshAllowed(
    result: UnknownOutputAcknowledgementResult,
): Boolean =
    result == UnknownOutputAcknowledgementResult.Applied ||
        result == UnknownOutputAcknowledgementResult.Absent

private fun OutputChangeKind.isTreePicker(): Boolean =
    this == OutputChangeKind.PdfLocation || this == OutputChangeKind.ImageLocation

private fun validateOutputChangeKind(kind: OutputChangeKind) {
    when (kind) {
        is OutputChangeKind.PdfSize -> Unit
        OutputChangeKind.PdfLocation -> Unit
        is OutputChangeKind.ImageSize ->
            resolveImageExport(kind.preset, kind.customMaxDimension)
        is OutputChangeKind.ImageFormat -> Unit
        OutputChangeKind.ImageLocation -> Unit
        is OutputChangeKind.UnknownOutputCreate -> Unit
    }
}

internal fun resolveImageExport(
    sizePreset: ImageSizePreset,
    customMaxDimension: Int?,
): ResolvedImageExport =
    resolveImageExport(
        ImageExportOptions(
            format = ImageExportFormat.Original,
            sizePreset = sizePreset,
            customMaxDimension = customMaxDimension,
        ),
    )

internal fun resolveImageExport(options: ImageExportOptions): ResolvedImageExport {
    val maxDimension =
        if (options.sizePreset == ImageSizePreset.Custom) {
            require(options.customMaxDimension in MIN_IMAGE_EXPORT_DIMENSION..MAX_IMAGE_EXPORT_DIMENSION) {
                "Custom image dimension must be between $MIN_IMAGE_EXPORT_DIMENSION and $MAX_IMAGE_EXPORT_DIMENSION"
            }
            options.customMaxDimension
        } else {
            require(options.customMaxDimension == null) {
                "Custom image dimension requires the Custom preset"
            }
            options.sizePreset.maxDimension
        }
    require(options.treeUri == null || isContentUri(options.treeUri)) {
        "Image export tree URI is invalid"
    }
    return ResolvedImageExport(options.format, maxDimension, options.treeUri)
}

internal data class AppSettings(
    val savePdf: Boolean = true,
    val saveImages: Boolean = true,
    val albumName: String = DEFAULT_ALBUM_NAME,
    val multipage: Boolean = true,
    val allowGallery: Boolean = true,
    val emailSubject: String = "Scanned document",
    val emailBody: String = "",
    val pdfTreeUri: String? = null,
    val deletePdfAfterShare: Boolean = true,
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
    val restoreAppearanceSettings: Boolean = true,
)

private const val PDF_DISPLAY_BYTES = 1_000_000L

internal data class SavedImageOutput(
    val page: Int,
    val uri: Uri,
    val treeUri: Uri?,
    val displayName: String?,
    val mimeType: String?,
    val ownerPackageName: String?,
    val byteLength: Long?,
    val sha256: String?,
    val width: Int?,
    val height: Int?,
    val format: ImageExportFormat?,
    val sizePreset: ImageSizePreset? = null,
    val customMaxDimension: Int? = null,
)

internal data class SavedScan(
    val cached: CachedScan,
    val savedImages: List<SavedImageOutput>,
    val savedPdf: Uri?,
    val savedPdfTree: Uri? = null,
    val warnings: List<UiMessage> = emptyList(),
    val outputMetadataValid: Boolean = false,
    val savedPdfDeleteVerified: Boolean = false,
    val savedImagesDeleteVerified: Boolean = false,
    val unknownOutputCreateAcknowledgement: UnknownOutputCreateAcknowledgement? = null,
) {
    val galleryPages: List<Uri>
        get() = savedImages.map(SavedImageOutput::uri)

    companion object {
        operator fun invoke(
            cached: CachedScan,
            galleryPages: List<Uri>,
            savedPdf: Uri?,
            savedPdfTree: Uri? = null,
            warnings: List<UiMessage> = emptyList(),
            outputMetadataValid: Boolean = false,
            savedPdfDeleteVerified: Boolean = false,
            savedImagesDeleteVerified: Boolean = false,
            unknownOutputCreateAcknowledgement: UnknownOutputCreateAcknowledgement? = null,
        ): SavedScan =
            SavedScan(
                cached = cached,
                savedImages =
                    galleryPages.mapIndexed { index, uri ->
                        SavedImageOutput(
                            page = index + 1,
                            uri = uri,
                            treeUri = null,
                            displayName = null,
                            mimeType = null,
                            ownerPackageName = null,
                            byteLength = null,
                            sha256 = null,
                            width = null,
                            height = null,
                            format = null,
                        )
                    },
                savedPdf = savedPdf,
                savedPdfTree = savedPdfTree,
                warnings = warnings,
                outputMetadataValid = outputMetadataValid,
                savedPdfDeleteVerified = savedPdfDeleteVerified,
                savedImagesDeleteVerified = savedImagesDeleteVerified,
                unknownOutputCreateAcknowledgement = unknownOutputCreateAcknowledgement,
            )
    }
}

internal fun totalFileBytes(files: List<File>): Long {
    var total = 0L
    files.forEach { file ->
        val bytes = file.length().coerceAtLeast(0L)
        if (Long.MAX_VALUE - total < bytes) return Long.MAX_VALUE
        total += bytes
    }
    return total
}

internal fun canChangePdfSize(
    scan: SavedScan,
    target: PdfSizeTarget,
): Boolean = canChoosePdfSize(scan) && scan.cached.pdfSizeTarget != target

internal fun canChoosePdfSize(scan: SavedScan): Boolean {
    val cached = scan.cached
    return scan.outputMetadataValid &&
        cached.entryId != null &&
        cached.pages.isNotEmpty() &&
        cached.sourcePages.size == cached.pages.size &&
        cached.appearanceSettings != null
}

internal enum class FileDetailControl {
    PdfSize,
    PdfLocation,
    ImageSize,
    ImageFormat,
    ImageLocation,
}

internal data class FileDetailAvailability(
    val outputMetadataValid: Boolean,
    val hasEntryId: Boolean,
    val canChoosePdfSize: Boolean,
    val pdfAvailable: Boolean,
    val pageCount: Int,
    val savedImageCount: Int,
    val canChangeImages: Boolean,
    val canRelocateImages: Boolean,
)

internal fun fileDetailControls(scan: SavedScan): Set<FileDetailControl> =
    fileDetailControls(
        FileDetailAvailability(
            outputMetadataValid = scan.outputMetadataValid,
            hasEntryId = scan.cached.entryId != null,
            canChoosePdfSize = canChoosePdfSize(scan),
            pdfAvailable = scan.cached.pdf.isFile || scan.cached.pages.isNotEmpty(),
            pageCount = scan.cached.pages.size,
            savedImageCount = scan.savedImages.size,
            canChangeImages = imageExportOptionsForChange(scan) != null,
            canRelocateImages = canRelocateImageOutputs(scan),
        ),
    )

internal fun fileDetailControls(availability: FileDetailAvailability): Set<FileDetailControl> {
    if (!availability.outputMetadataValid || !availability.hasEntryId) return emptySet()
    if (availability.pageCount <= 0 || availability.savedImageCount !in 0..availability.pageCount) {
        return emptySet()
    }
    return buildSet {
        if (availability.canChoosePdfSize) add(FileDetailControl.PdfSize)
        if (availability.pdfAvailable) add(FileDetailControl.PdfLocation)
        if (availability.canChangeImages) {
            add(FileDetailControl.ImageSize)
            add(FileDetailControl.ImageFormat)
        }
        if (availability.canRelocateImages) {
            add(FileDetailControl.ImageLocation)
        }
    }
}

internal fun parseCustomImageDimension(value: String): Int? =
    value.toIntOrNull()?.takeIf { it in MIN_IMAGE_EXPORT_DIMENSION..MAX_IMAGE_EXPORT_DIMENSION }

internal fun fullscreenPageIndex(selected: Int, pageCount: Int): Int =
    if (pageCount <= 0) 0 else selected.coerceIn(0, pageCount - 1)

internal fun exactImageDimensions(
    pageCount: Int,
    savedDimensions: List<Pair<Int, Int>?>,
    cachedDimensions: List<Pair<Int, Int>>?,
): List<Pair<Int, Int>>? {
    if (pageCount <= 0) return null
    val dimensions =
        if (savedDimensions.isEmpty()) {
            cachedDimensions
        } else {
            if (savedDimensions.size != pageCount || savedDimensions.any { it == null }) return null
            savedDimensions.filterNotNull()
        }
    return dimensions?.takeIf { values ->
        values.size == pageCount &&
            values.all { (width, height) ->
                width > 0 &&
                    height > 0 &&
                    width.toLong() * height <= MAX_IMAGE_EXPORT_PIXELS
            }
    }
}

internal enum class RecentRowTarget {
    Content,
    Overflow,
}

internal enum class RecentRowAction {
    Open,
    ShowMenu,
}

internal fun recentRowAction(target: RecentRowTarget): RecentRowAction =
    when (target) {
        RecentRowTarget.Content -> RecentRowAction.Open
        RecentRowTarget.Overflow -> RecentRowAction.ShowMenu
    }

internal fun canEditAppearance(scan: SavedScan): Boolean {
    val cached = scan.cached
    return scan.outputMetadataValid &&
        cached.entryId != null &&
        cached.pages.isNotEmpty() &&
        cached.sourcePages.size == cached.pages.size &&
        cached.appearance != null &&
        cached.appearanceSettings != null
}

internal fun confirmedUnknownOutputAcknowledgement(
    scan: SavedScan,
    confirmed: Boolean,
): UnknownOutputCreateAcknowledgement? =
    scan.unknownOutputCreateAcknowledgement.takeIf { confirmed }

internal fun imageExportOptionsForChange(scan: SavedScan): ImageExportOptions? {
    if (!scan.outputMetadataValid || scan.cached.entryId == null || scan.cached.pages.isEmpty()) {
        return null
    }
    if (scan.savedImages.isEmpty()) {
        return ImageExportOptions(ImageExportFormat.Original, ImageSizePreset.Original)
    }
    if (scan.savedImages.size != scan.cached.pages.size) return null
    return activeImageExportOptions(
        formats = scan.savedImages.map(SavedImageOutput::format),
        treeUris = scan.savedImages.map { it.treeUri?.toString() },
        sizePresets = scan.savedImages.map(SavedImageOutput::sizePreset),
        customMaxDimensions = scan.savedImages.map(SavedImageOutput::customMaxDimension),
    )
}

internal fun activeImageExportOptions(
    formats: List<ImageExportFormat?>,
    treeUris: List<String?>,
    sizePresets: List<ImageSizePreset?>,
    customMaxDimensions: List<Int?>,
): ImageExportOptions? {
    if (
        formats.isEmpty() ||
            treeUris.size != formats.size ||
            sizePresets.size != formats.size ||
            customMaxDimensions.size != formats.size
    ) {
        return null
    }
    val distinctFormats = formats.filterNotNull().distinct()
    if (distinctFormats.size != 1 || formats.any { it == null }) return null
    val distinctTreeUris = treeUris.distinct()
    if (distinctTreeUris.size != 1) return null
    if (sizePresets.any { it == null }) return null
    val distinctPresets = sizePresets.filterNotNull().distinct()
    if (distinctPresets.size != 1) return null
    val distinctCustomDimensions = customMaxDimensions.distinct()
    if (distinctCustomDimensions.size != 1) return null
    val sizePreset = distinctPresets.single()
    val customMaxDimension = distinctCustomDimensions.single()
    try {
        resolveImageExport(sizePreset, customMaxDimension)
    } catch (_: IllegalArgumentException) {
        return null
    }
    return ImageExportOptions(
        format = distinctFormats.single(),
        sizePreset = sizePreset,
        customMaxDimension = customMaxDimension,
        treeUri = distinctTreeUris.single(),
    )
}

private fun canRelocateImageOutputs(scan: SavedScan): Boolean =
    scan.outputMetadataValid &&
        scan.cached.entryId != null &&
        scan.cached.pages.isNotEmpty() &&
        (scan.savedImages.isEmpty() || scan.savedImages.size == scan.cached.pages.size)

internal enum class SaveNowTarget {
    Pdf,
    Images,
    Both,
}

internal data class VisualMarkEditorState(
    val source: MarkEditorSource,
    val templateIds: List<String> = emptyList(),
    val selectedTemplateId: String? = null,
    val placement: MarkPlacement = MarkPlacement(),
    val drawingStrokes: List<MarkStroke>? = null,
    val busy: Boolean = false,
    val applying: Boolean = false,
    val message: UiMessage? = null,
)

internal enum class DocumentAction(val wireValue: String) {
    ExtractText("extract_text"),
    DetectCodes("detect_codes"),
}

internal sealed interface DocumentActionOutput {
    data class Text(
        val value: String,
        val truncated: Boolean,
    ) : DocumentActionOutput

    data class Codes(val values: List<DetectedCode>) : DocumentActionOutput
}

internal sealed interface DocumentActionState {
    data class Processing(val action: DocumentAction) : DocumentActionState

    data class Completed(
        val output: DocumentActionOutput,
        val textExportStatus: DocumentTextExportStatus? = null,
    ) : DocumentActionState

    data class Exporting(val output: DocumentActionOutput.Text) : DocumentActionState

    data class Failed(val message: UiMessage) : DocumentActionState
}

internal enum class DocumentTextExportStatus {
    Saved,
    Failed,
}

internal fun completedDocumentTextExport(
    output: DocumentActionOutput.Text,
    saved: Boolean,
): DocumentActionState.Completed =
    DocumentActionState.Completed(
        output = output,
        textExportStatus =
            if (saved) DocumentTextExportStatus.Saved else DocumentTextExportStatus.Failed,
    )

internal enum class DocumentTextExportDisposition {
    Accepted,
    DefiniteStale,
}

internal data class DocumentActionRequest(
    val cacheId: String,
    val entryId: String,
    val pageIndex: Int,
    val action: DocumentAction,
    val generation: Long,
) {
    init {
        require(isSafeCacheId(cacheId)) { "Document action cache ID is invalid" }
        require(isCanonicalUuid(entryId)) { "Document action entry ID is invalid" }
        require(pageIndex in 0 until MAX_SCAN_PAGES) { "Document action page is invalid" }
        require(generation > 0L) { "Document action generation is invalid" }
    }
}

internal fun encodeDocumentActionRequest(request: DocumentActionRequest): String =
    listOf(
        request.cacheId,
        request.entryId,
        request.pageIndex.toString(),
        request.action.wireValue,
        request.generation.toString(),
    ).joinToString("\t")

internal fun decodeDocumentActionRequest(value: String?): DocumentActionRequest? {
    if (value == null || value.length > 512) return null
    val parts = value.split('\t')
    if (parts.size != 5) return null
    val pageIndex = parts[2].toIntOrNull() ?: return null
    val action = DocumentAction.entries.firstOrNull { it.wireValue == parts[3] } ?: return null
    val generation = parts[4].toLongOrNull() ?: return null
    return try {
        DocumentActionRequest(parts[0], parts[1], pageIndex, action, generation)
    } catch (_: IllegalArgumentException) {
        null
    }
}

internal fun DocumentActionRequest.matches(
    cacheId: String,
    entryId: String?,
    pageIndex: Int,
    action: DocumentAction,
    generation: Long,
): Boolean =
    this.cacheId == cacheId &&
        this.entryId == entryId &&
        this.pageIndex == pageIndex &&
        this.action == action &&
        this.generation == generation

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
    val pending: Boolean,
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
        val outputChangeInProgress: Boolean = false,
        val imageSharePreparationInProgress: Boolean = false,
        val appearanceApplyInProgress: Boolean = false,
        val appearanceReviewRequired: Boolean = false,
        val appearanceMessage: UiMessage? = null,
        val visualMarkEditor: VisualMarkEditorState? = null,
        val documentActionState: DocumentActionState? = null,
    ) : ScreenState

    data class Recent(
        val scans: List<RecentScan>,
        val message: UiMessage? = null,
        val deletionInProgress: Boolean = false,
    ) : ScreenState

    data class Failure(val message: UiMessage) : ScreenState
}

internal val ScreenState.Result.resultActionsBlocked: Boolean
    get() =
        outputSaveInProgress ||
            outputChangeInProgress ||
            imageSharePreparationInProgress ||
            appearanceApplyInProgress ||
            documentActionState is DocumentActionState.Processing ||
            documentActionState is DocumentActionState.Exporting ||
            visualMarkEditor != null

internal val ScreenState.Result.canAddVisualMark: Boolean
    get() =
        !pagePreviewLoading &&
            thumbnail != null &&
            scan.cached.entryId != null &&
            scan.cached.sourcePages.size == scan.cached.pages.size &&
            scan.cached.appearanceSettings != null &&
            scan.outputMetadataValid

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
        state is ScreenState.Result && state.resultActionsBlocked -> AppBackAction.Consume
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
