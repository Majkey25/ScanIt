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
internal const val MAX_ORIGINAL_IMAGE_DIMENSION = 20_000
internal const val MAX_ORIGINAL_IMAGE_PIXELS = 220_000_000L
internal const val MAX_OUTPUT_BASE_NAME_LENGTH = 96
private val STORAGE_VOLUME_NAME = Regex("[A-Za-z0-9_-]+")
private val SUPPORTED_OUTPUT_EXTENSIONS = listOf(".jpeg", ".jpg", ".png", ".pdf")

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

internal fun validOriginalImageDimensions(width: Int, height: Int): Boolean =
    width in 1..MAX_ORIGINAL_IMAGE_DIMENSION &&
        height in 1..MAX_ORIGINAL_IMAGE_DIMENSION &&
        width.toLong() * height <= MAX_ORIGINAL_IMAGE_PIXELS

internal fun validSavedImageDimensions(
    width: Int,
    height: Int,
    sizePreset: ImageSizePreset?,
): Boolean =
    if (sizePreset == ImageSizePreset.Original) {
        validOriginalImageDimensions(width, height)
    } else {
        width in 1..MAX_IMAGE_EXPORT_DIMENSION &&
            height in 1..MAX_IMAGE_EXPORT_DIMENSION &&
            width.toLong() * height <= MAX_IMAGE_EXPORT_PIXELS
    }

internal sealed interface OutputChangeKind {
    data class PdfSize(val target: PdfSizeTarget) : OutputChangeKind

    data object PdfLocation : OutputChangeKind

    data class PdfName(val baseName: String) : OutputChangeKind {
        init {
            require(normalizeOutputBaseName(baseName) == baseName) {
                "PDF output name is invalid"
            }
        }
    }

    data class ImageSize(
        val preset: ImageSizePreset,
        val customMaxDimension: Int? = null,
    ) : OutputChangeKind

    data class ImageFormat(val format: ImageExportFormat) : OutputChangeKind

    data object ImageLocation : OutputChangeKind

    data class ImageName(val baseName: String) : OutputChangeKind {
        init {
            require(normalizeOutputBaseName(baseName) == baseName) {
                "Image output name is invalid"
            }
        }
    }

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
        is OutputChangeKind.PdfName -> Unit
        is OutputChangeKind.ImageSize ->
            resolveImageExport(kind.preset, kind.customMaxDimension)
        is OutputChangeKind.ImageFormat -> Unit
        OutputChangeKind.ImageLocation -> Unit
        is OutputChangeKind.ImageName -> Unit
        is OutputChangeKind.UnknownOutputCreate -> Unit
    }
}

internal fun normalizeOutputBaseName(value: String): String? {
    var candidate = value.trim()
    val extension =
        listOf(".jpeg", ".jpg", ".png", ".pdf").firstOrNull {
            candidate.endsWith(it, ignoreCase = true)
        }
    if (extension != null) candidate = candidate.dropLast(extension.length).trimEnd()
    if (
        candidate.length !in 1..MAX_OUTPUT_BASE_NAME_LENGTH ||
            candidate == "." ||
            candidate == ".." ||
            candidate.endsWith('.') ||
            candidate.any { it.isISOControl() || it in "<>:\"/\\|?*" }
    ) {
        return null
    }
    return candidate
}

internal fun pdfOutputDisplayName(baseName: String): String {
    require(normalizeOutputBaseName(baseName) == baseName) { "PDF output name is invalid" }
    return "$baseName.pdf"
}

internal fun imageOutputDisplayName(
    baseName: String,
    page: Int,
    extension: String,
): String {
    require(normalizeOutputBaseName(baseName) == baseName) { "Image output name is invalid" }
    require(page in 1..MAX_SCAN_PAGES) { "Image page is invalid" }
    require(extension == "jpg" || extension == "png") { "Image extension is invalid" }
    return "${baseName}_${page.toString().padStart(2, '0')}.$extension"
}

internal fun imageOutputBaseName(displayNames: List<Pair<Int, String?>>): String? {
    if (displayNames.isEmpty()) return null
    val baseNames =
        displayNames.map { (page, displayName) ->
            if (page !in 1..MAX_SCAN_PAGES || displayName == null) return null
            val extension =
                listOf(".jpeg", ".jpg", ".png").firstOrNull {
                    displayName.endsWith(it, ignoreCase = true)
                } ?: return null
            var stem = displayName.dropLast(extension.length)
            val collisionStart = stem.lastIndexOf(" (")
            if (collisionStart >= 0 && stem.endsWith(')')) {
                val collision = stem.substring(collisionStart + 2, stem.length - 1)
                if (collision.isNotEmpty() && collision.all(Char::isDigit)) {
                    stem = stem.substring(0, collisionStart)
                }
            }
            val pageSuffix = "_${page.toString().padStart(2, '0')}"
            if (!stem.endsWith(pageSuffix)) return null
            normalizeOutputBaseName(stem.dropLast(pageSuffix.length)) ?: return null
        }
    return baseNames.distinct().singleOrNull()
}

internal fun outputFileExtension(displayName: String?): String? {
    val value = displayName ?: return null
    val extension =
        SUPPORTED_OUTPUT_EXTENSIONS.firstOrNull { value.endsWith(it, ignoreCase = true) }
            ?: return null
    return value.takeLast(extension.length)
}

internal fun mediaStoreDirectoryPath(volume: String, relativePath: String): String? {
    if (!volume.matches(STORAGE_VOLUME_NAME)) return null
    val path = normalizedStorageSubdirectory(relativePath) ?: return null
    val root =
        if (volume == "external" || volume == "external_primary") {
            "/storage/emulated/0"
        } else {
            "/storage/$volume"
        }
    return if (path.isEmpty()) root else "$root/$path"
}

internal fun externalStorageDirectoryPath(documentId: String): String? {
    val separator = documentId.indexOf(':')
    if (separator <= 0 || documentId.indexOf(':', separator + 1) >= 0) return null
    val volume = documentId.substring(0, separator)
    val path = normalizedStorageSubdirectory(documentId.substring(separator + 1)) ?: return null
    val root =
        if (volume.equals("primary", ignoreCase = true)) {
            "/storage/emulated/0"
        } else {
            if (!volume.matches(STORAGE_VOLUME_NAME)) return null
            "/storage/$volume"
        }
    return if (path.isEmpty()) root else "$root/$path"
}

internal fun outputLocationPath(directory: String, displayName: String?): String? {
    val name = displayName ?: return null
    if (!isProviderDisplayName(name) || '/' in name || '\\' in name) return null
    return "${directory.trimEnd('/')}/$name"
}

internal fun displayOutputLocationPath(value: String?): String? {
    val path = value?.replace('\\', '/')?.trimEnd('/') ?: return null
    if (path.isBlank() || path.startsWith("content://", ignoreCase = true)) return null
    val directory = path.substringBeforeLast('/', missingDelimiterValue = "")
    if (directory.isBlank()) return null
    val internalRoot = "/storage/emulated/0"
    if (directory == internalRoot) return "Internal storage"
    if (directory.startsWith("$internalRoot/")) {
        val relative = directory.removePrefix("$internalRoot/")
        val readable =
            if (relative == "Download" || relative.startsWith("Download/")) {
                "Downloads${relative.removePrefix("Download")}"
            } else {
                relative
            }
        return "Internal storage/$readable"
    }
    if (directory.startsWith("/storage/")) {
        val relative = directory.removePrefix("/storage/").substringAfter('/', missingDelimiterValue = "")
        return if (relative.isBlank()) "External storage" else "External storage/$relative"
    }
    return directory
}

internal data class VisiblePdfOutput(
    val reference: PdfOutputRef?,
    val deleted: Boolean,
)

internal fun visiblePdfOutput(
    reference: PdfOutputRef?,
    query: (PdfOutputRef) -> ExactItemQuery,
): VisiblePdfOutput =
    when {
        reference == null -> VisiblePdfOutput(null, false)
        query(reference) == ExactItemQuery.Absent -> VisiblePdfOutput(null, true)
        else -> VisiblePdfOutput(reference, false)
    }

internal enum class ImageOutputPresence {
    Present,
    Deleted,
    Uncertain,
}

internal fun imageOutputPresence(results: List<ExactItemQuery>): ImageOutputPresence =
    when {
        results.isNotEmpty() && results.all { it == ExactItemQuery.Exact } ->
            ImageOutputPresence.Present
        results.isNotEmpty() && results.all { it == ExactItemQuery.Absent } ->
            ImageOutputPresence.Deleted
        else -> ImageOutputPresence.Uncertain
    }

internal fun imageOutputLocationLabel(locations: List<String>): String? {
    val unique = locations.distinct()
    if (unique.isEmpty()) return null
    if (unique.size == 1) return unique.single()
    val parents = unique.mapNotNull { it.substringBeforeLast('/', missingDelimiterValue = "").ifEmpty { null } }
    if (
        unique.all { it.startsWith('/') } &&
            parents.size == unique.size &&
            parents.distinct().size == 1
    ) {
        return parents.first()
    }
    return unique.joinToString(separator = "\n", limit = 3, truncated = "…")
}

private fun normalizedStorageSubdirectory(value: String): String? {
    if (value.any { it.isISOControl() || it == '\\' }) return null
    val trimmed = value.trim('/')
    if (trimmed.isEmpty()) return ""
    val parts = trimmed.split('/')
    if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
    return parts.joinToString("/")
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
    val deletePdfAfterShare: Boolean = false,
    val deleteImagesAfterShare: Boolean = false,
    val appearance: ScanAppearanceSettings = ScanAppearanceSettings(),
    val pdfSizeTarget: PdfSizeTarget = PdfSizeTarget.Original,
    val ocrScript: OcrScript = OcrScript.Auto,
    val readAloudLanguage: ReadAloudLanguage = ReadAloudLanguage.Auto,
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
    if (targetBytes < PDF_DISPLAY_BYTES) {
        return UiMessage(
            R.string.pdf_size_target_not_met_kb,
            listOf(
                (targetBytes / PDF_DISPLAY_KILOBYTES).toInt(),
                bytes / PDF_DISPLAY_KILOBYTES.toDouble(),
            ),
        )
    }
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
private const val PDF_DISPLAY_KILOBYTES = 1_000L

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
    val location: String? = null,
)

internal data class SavedScan(
    val cached: CachedScan,
    val savedImages: List<SavedImageOutput>,
    val savedPdf: Uri?,
    val savedPdfTree: Uri? = null,
    val savedPdfDisplayName: String? = null,
    val savedPdfLocation: String? = null,
    val savedPdfDeleted: Boolean = false,
    val savedImagesDeleted: Boolean = false,
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
    PdfName,
    PdfSize,
    PdfLocation,
    ImageName,
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
    val canRenamePdf: Boolean = false,
    val canRenameImages: Boolean = false,
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
            canRenamePdf =
                scan.savedPdf != null &&
                    normalizeOutputBaseName(scan.savedPdfDisplayName.orEmpty()) != null,
            canRenameImages =
                scan.savedImages.size == scan.cached.pages.size &&
                    imageOutputBaseName(
                        scan.savedImages.map { it.page to it.displayName },
                    ) != null,
        ),
    )

internal fun fileDetailControls(availability: FileDetailAvailability): Set<FileDetailControl> {
    if (!availability.outputMetadataValid || !availability.hasEntryId) return emptySet()
    if (availability.pageCount <= 0 || availability.savedImageCount !in 0..availability.pageCount) {
        return emptySet()
    }
    return buildSet {
        if (availability.canRenamePdf) add(FileDetailControl.PdfName)
        if (availability.canChoosePdfSize) add(FileDetailControl.PdfSize)
        if (availability.pdfAvailable) add(FileDetailControl.PdfLocation)
        if (availability.canRenameImages) add(FileDetailControl.ImageName)
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
            values.all { (width, height) -> validOriginalImageDimensions(width, height) }
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

internal enum class ResultEntryAction {
    Rescan,
    SignOrStamp,
    Actions,
}

internal enum class ResultActionDestination {
    Scanner,
    MarkEditor,
    DocumentActions,
}

internal fun resultActionDestination(action: ResultEntryAction): ResultActionDestination =
    when (action) {
        ResultEntryAction.Rescan -> ResultActionDestination.Scanner
        ResultEntryAction.SignOrStamp -> ResultActionDestination.MarkEditor
        ResultEntryAction.Actions -> ResultActionDestination.DocumentActions
    }

internal fun recentRowAction(target: RecentRowTarget): RecentRowAction =
    when (target) {
        RecentRowTarget.Content -> RecentRowAction.Open
        RecentRowTarget.Overflow -> RecentRowAction.ShowMenu
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

internal data class ManualCleanupEditorState(
    val source: MarkEditorSource,
    val strokes: List<MarkStroke> = emptyList(),
    val applying: Boolean = false,
    val message: UiMessage? = null,
)

internal enum class DocumentAction(val wireValue: String) {
    ExtractText("extract_text"),
    FindText("find_text"),
    ReadAloud("read_aloud"),
    DetectCodes("detect_codes"),
    ReceiptDetails("receipt_details"),
    CreateContact("create_contact"),
    SafeShare("safe_share"),
    RedactDocument("redact_document"),
    CleanWhiteboard("clean_whiteboard"),
    ManualCleanup("manual_cleanup"),
}

internal enum class DocumentActionSectionTitle {
    Read,
    Use,
    Protect,
    Improve,
}

internal data class DocumentActionSection(
    val title: DocumentActionSectionTitle,
    val actions: List<DocumentAction>,
)

internal fun documentActionInventory(): List<DocumentActionSection> =
    listOf(
        DocumentActionSection(
            DocumentActionSectionTitle.Read,
            listOf(DocumentAction.ExtractText, DocumentAction.FindText, DocumentAction.ReadAloud),
        ),
        DocumentActionSection(
            DocumentActionSectionTitle.Use,
            listOf(
                DocumentAction.DetectCodes,
                DocumentAction.ReceiptDetails,
                DocumentAction.CreateContact,
            ),
        ),
        DocumentActionSection(
            DocumentActionSectionTitle.Protect,
            listOf(DocumentAction.SafeShare, DocumentAction.RedactDocument),
        ),
        DocumentActionSection(
            DocumentActionSectionTitle.Improve,
            listOf(
                DocumentAction.CleanWhiteboard,
                DocumentAction.ManualCleanup,
            ),
        ),
    )

internal data class DocumentOcrSnapshot(
    val pageTexts: List<String>,
    val elements: List<OcrElement>,
    val truncated: Boolean,
) {
    init {
        require(pageTexts.size <= MAX_SCAN_PAGES) { "OCR page count is invalid" }
        var characters = 0
        for (text in pageTexts) {
            require(text.length <= MAX_DOCUMENT_TEXT_CHARACTERS - characters) {
                "OCR text is too large"
            }
            characters += text.length
        }
        require(elements.all { it.page in pageTexts.indices }) {
            "OCR element page has no matching text page"
        }
    }
}

internal data class TextMatch(
    val page: Int,
    val start: Int,
    val endExclusive: Int,
) {
    init {
        require(page in 0 until MAX_SCAN_PAGES) { "Text match page is invalid" }
        require(start >= 0 && endExclusive > start) { "Text match range is invalid" }
    }
}

internal enum class DocumentEntityKind {
    Email,
    Phone,
    Url,
    Iban,
    PaymentCard,
    Money,
    Date,
}

internal enum class SensitiveRegionKind {
    Email,
    Phone,
    Url,
    Iban,
    PaymentCard,
    Code,
    Face,
    Manual,
}

internal data class RedactionSuggestion(
    val page: Int,
    val kind: SensitiveRegionKind,
    val bounds: NormalizedRect,
) {
    init {
        require(page in 0 until MAX_SCAN_PAGES) { "Redaction suggestion page is invalid" }
    }
}

internal data class SafeShareAnalysis(
    val pageCount: Int,
    val suggestions: List<RedactionSuggestion>,
) {
    init {
        require(pageCount in 1..MAX_SCAN_PAGES) { "Safe Share page count is invalid" }
        val counts = IntArray(pageCount)
        for (suggestion in suggestions) {
            require(suggestion.page in 0 until pageCount) {
                "Redaction suggestion has no matching page"
            }
            require(++counts[suggestion.page] <= MAX_SAFE_SHARE_SUGGESTIONS_PER_PAGE) {
                "Safe Share suggestions exceed the page limit"
            }
        }
    }
}

internal data class RedactionRegion(
    val id: String,
    val page: Int,
    val kind: SensitiveRegionKind,
    val bounds: NormalizedRect,
    val selected: Boolean,
) {
    init {
        require(id.isNotBlank() && id.length <= 128 && id.none(Char::isISOControl)) {
            "Redaction region ID is invalid"
        }
        require(page in 0 until MAX_SCAN_PAGES) { "Redaction region page is invalid" }
    }
}

internal const val MIN_REDACTION_BRUSH_WIDTH_FRACTION = 0.01f
internal const val MAX_REDACTION_BRUSH_WIDTH_FRACTION = 0.10f
internal const val DEFAULT_REDACTION_BRUSH_WIDTH_FRACTION = 0.03f

internal enum class RedactionTool {
    Line,
    Brush,
}

internal fun redactionStrokePoints(
    tool: RedactionTool,
    points: List<MarkPoint>,
): List<MarkPoint> =
    if (tool == RedactionTool.Line && points.size > 1) {
        listOf(points.first(), points.last())
    } else {
        points.toList()
    }

internal data class RedactionStroke(
    val points: List<MarkPoint>,
    val widthFraction: Float,
    val tool: RedactionTool = RedactionTool.Brush,
) {
    init {
        require(points.isNotEmpty()) { "Redaction stroke must contain a point" }
        require(tool != RedactionTool.Line || points.size <= 2) {
            "Straight redaction must contain only its endpoints"
        }
        require(
            widthFraction.isFinite() &&
                widthFraction in
                MIN_REDACTION_BRUSH_WIDTH_FRACTION..MAX_REDACTION_BRUSH_WIDTH_FRACTION,
        ) { "Redaction stroke width is invalid" }
    }
}

internal fun validateRedactionStrokes(strokes: List<RedactionStroke>) {
    if (strokes.isEmpty()) return
    require(strokes.size <= MAX_MARK_DRAWING_STROKES) { "Redaction stroke count is too large" }
    validateNormalizedMarkStrokes(strokes.map { MarkStroke(it.points) })
}

internal enum class SafeShareScope {
    SelectedPage,
    AllPages,
}

internal enum class RedactionMode(val requiresAnalysis: Boolean) {
    Automatic(true),
    Manual(false),
}

internal data class SafeShareRequest(
    val cacheId: String,
    val entryId: String,
    val scope: SafeShareScope,
    val selectedPage: Int,
    val generation: Long,
    val mode: RedactionMode,
) {
    init {
        require(isSafeCacheId(cacheId)) { "Safe Share cache ID is invalid" }
        require(isCanonicalUuid(entryId)) { "Safe Share entry ID is invalid" }
        require(selectedPage in 0 until MAX_SCAN_PAGES) { "Safe Share page is invalid" }
        require(generation > 0L) { "Safe Share generation is invalid" }
    }
}

internal fun SafeShareRequest.matches(
    cacheId: String,
    entryId: String?,
    scope: SafeShareScope,
    selectedPage: Int,
    generation: Long,
    mode: RedactionMode,
): Boolean =
    this.cacheId == cacheId &&
        this.entryId == entryId &&
        this.scope == scope &&
        this.selectedPage == selectedPage &&
        this.generation == generation &&
        this.mode == mode

internal data class CleanWhiteboardRequest(
    val cacheId: String,
    val entryId: String,
    val scope: SafeShareScope,
    val selectedPage: Int,
    val generation: Long,
) {
    init {
        require(isSafeCacheId(cacheId)) { "Clean Whiteboard cache ID is invalid" }
        require(isCanonicalUuid(entryId)) { "Clean Whiteboard entry ID is invalid" }
        require(selectedPage in 0 until MAX_SCAN_PAGES) { "Clean Whiteboard page is invalid" }
        require(generation > 0L) { "Clean Whiteboard generation is invalid" }
    }
}

internal fun CleanWhiteboardRequest.matches(
    cacheId: String,
    entryId: String?,
    scope: SafeShareScope,
    selectedPage: Int,
    generation: Long,
): Boolean =
    this.cacheId == cacheId &&
        this.entryId == entryId &&
        this.scope == scope &&
        this.selectedPage == selectedPage &&
        this.generation == generation

internal sealed interface SafeShareState {
    data object Analyzing : SafeShareState

    data class Reviewing(
        val page: Int,
        val regions: List<RedactionRegion>,
        val mode: RedactionMode = RedactionMode.Automatic,
        val strokesByPage: Map<Int, List<RedactionStroke>> = emptyMap(),
        val undoneStrokesByPage: Map<Int, List<RedactionStroke>> = emptyMap(),
        val brushWidthFraction: Float = DEFAULT_REDACTION_BRUSH_WIDTH_FRACTION,
        val redactionTool: RedactionTool = RedactionTool.Line,
    ) : SafeShareState {
        init {
            require(page in 0 until MAX_SCAN_PAGES) { "Safe Share review page is invalid" }
            require(regions.map(RedactionRegion::id).distinct().size == regions.size) {
                "Safe Share region IDs are duplicated"
            }
            val counts = IntArray(MAX_SCAN_PAGES)
            for (region in regions) {
                require(++counts[region.page] <= MAX_SAFE_SHARE_SUGGESTIONS_PER_PAGE) {
                    "Safe Share regions exceed the page limit"
                }
            }
            require(mode != RedactionMode.Manual || regions.isEmpty()) {
                "Manual redaction cannot contain detected regions"
            }
            require(
                brushWidthFraction.isFinite() &&
                    brushWidthFraction in
                    MIN_REDACTION_BRUSH_WIDTH_FRACTION..MAX_REDACTION_BRUSH_WIDTH_FRACTION,
            ) { "Redaction brush width is invalid" }
            require(
                (strokesByPage.keys + undoneStrokesByPage.keys).all {
                    it in 0 until MAX_SCAN_PAGES
                } &&
                    strokesByPage.values.none(List<RedactionStroke>::isEmpty) &&
                    undoneStrokesByPage.values.none(List<RedactionStroke>::isEmpty),
            ) { "Redaction stroke pages are invalid" }
            validateRedactionStrokes(
                strokesByPage.values.flatten() + undoneStrokesByPage.values.flatten(),
            )
        }
    }

    data object Applying : SafeShareState

    data class Failed(val message: UiMessage) : SafeShareState
}

internal fun initialRedactionReview(
    mode: RedactionMode,
    selectedPage: Int,
): SafeShareState.Reviewing? =
    if (mode.requiresAnalysis) {
        null
    } else {
        SafeShareState.Reviewing(selectedPage, emptyList(), mode = mode)
    }

internal sealed interface DetectedCodeAction {
    data class OpenUrl(val url: String) : DetectedCodeAction

    data class Dial(val phone: String) : DetectedCodeAction

    data class ComposeEmail(
        val address: String,
        val subject: String?,
        val body: String?,
    ) : DetectedCodeAction

    data class ComposeSms(val phone: String, val message: String?) : DetectedCodeAction

    data class CreateContact(
        val name: String?,
        val phones: List<String>,
        val emails: List<String>,
    ) : DetectedCodeAction

    data class CreateCalendarEvent(
        val title: String,
        val startMillis: Long?,
        val endMillis: Long?,
    ) : DetectedCodeAction

    data class OpenGeo(val latitude: Double, val longitude: Double) : DetectedCodeAction

    data class OpenWifiSettings(val ssid: String, val password: String?) : DetectedCodeAction
}

internal sealed interface DocumentActionOutput {
    data class Text(
        val value: String,
        val truncated: Boolean,
    ) : DocumentActionOutput

    data class Codes(val values: List<DetectedCode>) : DocumentActionOutput

    data object FindReady : DocumentActionOutput

    data class Speech(
        val hasText: Boolean,
        val truncated: Boolean,
    ) : DocumentActionOutput

    data class WhiteboardPreview(
        val before: Bitmap,
        val after: Bitmap,
        val appearance: ScanAppearanceSettings,
    ) : DocumentActionOutput
}

internal data class EntityCandidates(
    val values: List<DocumentEntityCandidate>,
) : DocumentActionOutput

internal sealed interface DocumentActionState {
    data class Processing(val action: DocumentAction) : DocumentActionState

    data class Completed(
        val output: DocumentActionOutput,
        val textExportStatus: DocumentTextExportStatus? = null,
        val action: DocumentAction = DocumentAction.ExtractText,
    ) : DocumentActionState

    data class Exporting(val output: DocumentActionOutput.Text) : DocumentActionState

    data class Failed(val message: UiMessage) : DocumentActionState
}

internal fun documentActionDismissAllowed(state: DocumentActionState?): Boolean =
    state !is DocumentActionState.Processing ||
        state.action != DocumentAction.CleanWhiteboard

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
        action = DocumentAction.ExtractText,
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
    val clearCheckpointBeforeLaunch: Boolean = false,
)

internal fun initialNavigation(
    savedRoute: String?,
    savedCacheId: String?,
    activeResultCacheId: String?,
    safeShareWasActive: Boolean = false,
): InitialNavigation {
    val durableCacheId = activeResultCacheId?.takeIf(::isSafeActiveResultCacheId)
    if (
        safeShareWasActive &&
            savedRoute == "result" &&
            durableCacheId != null
    ) {
        return InitialNavigation(RestoredRoute.Result, durableCacheId)
    }
    if (durableCacheId != null) {
        return InitialNavigation(
            route = RestoredRoute.Scanner,
            cacheId = null,
            clearCheckpointBeforeLaunch = true,
        )
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
        val visualMarkEditor: VisualMarkEditorState? = null,
        val manualCleanupEditor: ManualCleanupEditorState? = null,
        val documentActionState: DocumentActionState? = null,
        val safeShareState: SafeShareState? = null,
        val safeShareScope: SafeShareScope? = null,
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
            documentActionState is DocumentActionState.Processing ||
            documentActionState is DocumentActionState.Exporting ||
            safeShareState != null ||
            visualMarkEditor != null ||
            manualCleanupEditor != null

internal val ScreenState.Result.canAddVisualMark: Boolean
    get() =
        !pagePreviewLoading &&
            thumbnail != null &&
            scan.cached.entryId != null &&
            scan.cached.sourcePages.size == scan.cached.pages.size &&
            scan.cached.appearanceSettings != null &&
            scan.outputMetadataValid

internal val ScreenState.Result.canCleanWhiteboard: Boolean
    get() =
        scan.cached.entryId != null &&
            scan.cached.pages.isNotEmpty() &&
            scan.cached.sourcePages.size == scan.cached.pages.size &&
            scan.cached.appearanceSettings != null &&
            scan.outputMetadataValid

internal enum class AppBackAction {
    CloseSettings,
    CollapseFileDetails,
    ShowRecent,
    LaunchScanner,
    CancelSafeShare,
    Consume,
}

internal fun appBackAction(
    settingsOpen: Boolean,
    fileDetailsOpen: Boolean,
    state: ScreenState,
): AppBackAction =
    when {
        settingsOpen -> AppBackAction.CloseSettings
        state is ScreenState.Result && state.safeShareState != null ->
            when (state.safeShareState) {
                SafeShareState.Analyzing,
                SafeShareState.Applying,
                -> AppBackAction.Consume
                is SafeShareState.Reviewing,
                is SafeShareState.Failed,
                -> AppBackAction.CancelSafeShare
            }
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
