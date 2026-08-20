package com.majkeylab.scanit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallClient
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal const val MAX_SAFE_SHARE_SUGGESTIONS_PER_PAGE = 256
internal const val MAX_SAFE_SHARE_ANALYSIS_PIXELS = 2_000_000L
private const val SAFE_SHARE_SUGGESTION_PADDING = 0.01f

internal class SafeShareModelUnavailableException(cause: Throwable? = null) :
    IOException("Safe Share face model is unavailable", cause)

internal interface SafeShareFaceModule {
    suspend fun areModulesAvailable(): Boolean

    suspend fun deferredInstall()
}

internal class PlayServicesSafeShareFaceModule(
    private val client: ModuleInstallClient,
    private val detector: FaceDetector,
) : SafeShareFaceModule {
    override suspend fun areModulesAvailable(): Boolean =
        client.areModulesAvailable(detector).awaitResult().areModulesAvailable()

    override suspend fun deferredInstall() {
        client.deferredInstall(detector).awaitResult()
    }
}

internal class SafeShareFaceModelBoundary(
    private val module: SafeShareFaceModule,
) {
    suspend fun requireAvailable() {
        val available =
            try {
                module.areModulesAvailable()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                throw SafeShareModelUnavailableException(failure)
            }
        if (available) return
        try {
            module.deferredInstall()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            throw SafeShareModelUnavailableException(failure)
        }
        throw SafeShareModelUnavailableException()
    }

    suspend fun verifyEmptyResult(isEmpty: Boolean) {
        if (isEmpty) requireAvailable()
    }
}

internal fun safeShareEntitySuggestion(
    candidate: DocumentEntityCandidate,
): RedactionSuggestion? {
    val kind =
        when (candidate.kind) {
            DocumentEntityKind.Email -> SensitiveRegionKind.Email
            DocumentEntityKind.Phone -> SensitiveRegionKind.Phone
            DocumentEntityKind.Url -> SensitiveRegionKind.Url
            DocumentEntityKind.Iban -> SensitiveRegionKind.Iban
            DocumentEntityKind.PaymentCard -> SensitiveRegionKind.PaymentCard
            DocumentEntityKind.Money,
            DocumentEntityKind.Date,
            -> return null
        }
    return RedactionSuggestion(candidate.page, kind, candidate.bounds)
}

internal fun safeShareCodeSuggestion(
    page: Int,
    bounds: NormalizedRect?,
): RedactionSuggestion? =
    bounds?.let { RedactionSuggestion(page, SensitiveRegionKind.Code, it) }

internal fun safeShareFaceSuggestion(
    page: Int,
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
    sourceWidth: Int,
    sourceHeight: Int,
): RedactionSuggestion? {
    if (page !in 0 until MAX_SCAN_PAGES) return null
    val bounds =
        normalizedRect(left, top, right, bottom, sourceWidth, sourceHeight) ?: return null
    return RedactionSuggestion(page, SensitiveRegionKind.Face, bounds)
}

internal fun safeShareSampleSize(width: Int, height: Int): Int =
    appearanceDecodeSampleSize(
        width = width,
        height = height,
        maxPixels = MAX_SAFE_SHARE_ANALYSIS_PIXELS,
        maxEdge = MAX_IMAGE_EXPORT_DIMENSION,
    )

internal fun buildSafeShareAnalysis(
    pageCount: Int,
    candidates: Iterable<RedactionSuggestion>,
    padding: Float = SAFE_SHARE_SUGGESTION_PADDING,
): SafeShareAnalysis {
    require(pageCount in 1..MAX_SCAN_PAGES) { "Safe Share page count is invalid" }
    require(padding.isFinite() && padding in 0f..1f) { "Safe Share padding is invalid" }
    val suggestionsByPage = Array(pageCount) { ArrayList<RedactionSuggestion>() }
    for (candidate in candidates) {
        require(candidate.page in 0 until pageCount) {
            "Redaction suggestion has no matching page"
        }
        suggestionsByPage[candidate.page].addMerged(candidate.padded(padding))
    }
    return SafeShareAnalysis(pageCount, suggestionsByPage.flatMap { it })
}

private fun MutableList<RedactionSuggestion>.addMerged(candidate: RedactionSuggestion) {
    // ponytail: O(n²) stays bounded at 256 regions; add spatial indexing only if the cap grows.
    var merged = candidate
    var index = 0
    while (index < size) {
        val current = this[index]
        if (current.kind == merged.kind && current.bounds.overlaps(merged.bounds)) {
            merged = merged.copy(bounds = current.bounds.union(merged.bounds))
            removeAt(index)
            index = 0
        } else {
            index += 1
        }
    }
    if (size < MAX_SAFE_SHARE_SUGGESTIONS_PER_PAGE) add(merged)
}

private fun RedactionSuggestion.padded(padding: Float): RedactionSuggestion =
    copy(
        bounds =
            NormalizedRect(
                left = (bounds.left - padding).coerceAtLeast(0f),
                top = (bounds.top - padding).coerceAtLeast(0f),
                right = (bounds.right + padding).coerceAtMost(1f),
                bottom = (bounds.bottom + padding).coerceAtMost(1f),
            ),
    )

private fun NormalizedRect.overlaps(other: NormalizedRect): Boolean =
    left < other.right && right > other.left && top < other.bottom && bottom > other.top

private fun NormalizedRect.union(other: NormalizedRect): NormalizedRect =
    NormalizedRect(
        left = minOf(left, other.left),
        top = minOf(top, other.top),
        right = maxOf(right, other.right),
        bottom = maxOf(bottom, other.bottom),
    )

internal fun safeShareAnalysisPages(
    pages: List<File>,
    scope: SafeShareScope,
    selectedPage: Int,
): List<IndexedValue<File>> {
    require(pages.size in 1..MAX_SCAN_PAGES && selectedPage in pages.indices) {
        "Safe Share page selection is invalid"
    }
    return when (scope) {
        SafeShareScope.SelectedPage -> listOf(IndexedValue(selectedPage, pages[selectedPage]))
        SafeShareScope.AllPages -> pages.mapIndexed(::IndexedValue)
    }
}

internal suspend fun extractSafeShareOcr(
    pages: List<IndexedValue<File>>,
    extract: suspend (List<File>) -> DocumentOcrSnapshot,
): DocumentOcrSnapshot {
    require(pages.size in 1..MAX_SCAN_PAGES) { "Safe Share page selection is invalid" }
    return extract(pages.map(IndexedValue<File>::value)).also { snapshot ->
        require(snapshot.pageTexts.size == pages.size) { "Safe Share pages do not match OCR" }
    }
}

internal fun DocumentOcrSnapshot.selectSafeSharePages(
    pages: List<IndexedValue<File>>,
): DocumentOcrSnapshot {
    require(pages.isNotEmpty() && pages.all { it.index in pageTexts.indices }) {
        "Safe Share page selection does not match OCR"
    }
    val localPageByOriginal = pages.mapIndexed { local, page -> page.index to local }.toMap()
    return DocumentOcrSnapshot(
        pageTexts = pages.map { pageTexts[it.index] },
        elements =
            elements.mapNotNull { element ->
                localPageByOriginal[element.page]?.let { element.copy(page = it) }
            },
        truncated = truncated,
    )
}

internal fun RedactionSuggestion.onOriginalSafeSharePage(
    pages: List<IndexedValue<File>>,
): RedactionSuggestion {
    require(page in pages.indices) { "Safe Share suggestion page is invalid" }
    return copy(page = pages[page].index)
}

internal class SafeShareAnalyzer(context: Context) {
    private val context = context.applicationContext

    suspend fun analyze(
        pages: List<IndexedValue<File>>,
        pageCount: Int,
        ocr: DocumentOcrSnapshot,
    ): SafeShareAnalysis =
        withContext(Dispatchers.IO) {
            require(
                pageCount in 1..MAX_SCAN_PAGES &&
                    pages.size in 1..pageCount &&
                    pages.map(IndexedValue<File>::index).distinct().size == pages.size &&
                    pages.all { it.index in 0 until pageCount } &&
                    ocr.pageTexts.size == pages.size,
            ) {
                "Safe Share pages do not match OCR"
            }
            val detector = FaceDetection.getClient(faceDetectorOptions())
            try {
                val scanner = BarcodeScanning.getClient()
                try {
                    val moduleClient = ModuleInstall.getClient(context)
                    val faceModel =
                        SafeShareFaceModelBoundary(
                            PlayServicesSafeShareFaceModule(moduleClient, detector),
                        )
                    faceModel.requireAvailable()
                    val candidates = ArrayList<RedactionSuggestion>()
                    val entitiesByPage =
                        buildDocumentEntityCandidates(ocr.elements)
                            .mapNotNull(::safeShareEntitySuggestion)
                            .groupBy(RedactionSuggestion::page)
                    for ((localPage, page) in pages.withIndex()) {
                        currentCoroutineContext().ensureActive()
                        val bitmap = decodeSafeSharePage(page.value)
                        try {
                            val image = InputImage.fromBitmap(bitmap, 0)
                            val faces = detector.process(image).awaitResult()
                            faceModel.verifyEmptyResult(faces.isEmpty())
                            var faceAttempts = 0
                            for (face in faces) {
                                if (faceAttempts == MAX_SAFE_SHARE_SUGGESTIONS_PER_PAGE) break
                                faceAttempts += 1
                                val box = face.boundingBox
                                safeShareFaceSuggestion(
                                    page = page.index,
                                    left = box.left,
                                    top = box.top,
                                    right = box.right,
                                    bottom = box.bottom,
                                    sourceWidth = bitmap.width,
                                    sourceHeight = bitmap.height,
                                )?.let(candidates::add)
                            }
                            var codeAttempts = 0
                            for (barcode in scanner.process(image).awaitResult()) {
                                if (codeAttempts == MAX_DETECTED_CODES) break
                                codeAttempts += 1
                                val box = barcode.boundingBox ?: continue
                                safeShareCodeSuggestion(
                                    page.index,
                                    normalizedRect(
                                        box.left,
                                        box.top,
                                        box.right,
                                        box.bottom,
                                        bitmap.width,
                                        bitmap.height,
                                    ),
                                )?.let(candidates::add)
                            }
                        } finally {
                            bitmap.recycle()
                        }
                        entitiesByPage[localPage]
                            .orEmpty()
                            .mapTo(candidates) { it.onOriginalSafeSharePage(pages) }
                    }
                    buildSafeShareAnalysis(pageCount, candidates)
                } finally {
                    scanner.close()
                }
            } finally {
                detector.close()
            }
        }
}

private fun faceDetectorOptions(): FaceDetectorOptions =
    FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
        .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
        .build()

private fun decodeSafeSharePage(file: File): Bitmap {
    val page = validatedDocumentActionPage(file)
    val dimensions = readJpegDimensions(page)
    val bitmap =
        BitmapFactory.decodeFile(
            page.path,
            BitmapFactory.Options().apply {
                inSampleSize = safeShareSampleSize(dimensions.width, dimensions.height)
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
            },
        ) ?: throw IOException("Safe Share page could not be decoded")
    if (
        bitmap.width <= 0 ||
            bitmap.height <= 0 ||
            bitmap.width.toLong() * bitmap.height > MAX_SAFE_SHARE_ANALYSIS_PIXELS
    ) {
        bitmap.recycle()
        throw IOException("Safe Share page exceeds the analysis bound")
    }
    return bitmap
}
