package com.majkeylab.scanit

import kotlin.math.hypot

private const val MIN_AUTO_CAPTURE_AREA = 0.07
private const val MAX_AUTO_CAPTURE_AREA = 0.94
private const val MAX_AUTO_CAPTURE_CLUSTER_MOVEMENT = 0.12
private const val AUTO_CAPTURE_SMOOTHING_WEIGHT = 0.45
private const val MAX_AUTO_CAPTURE_MISSED_FRAMES = 2

internal fun isSupportedScannerV2CaptureSize(width: Int, height: Int): Boolean =
    width in 1..MAX_IMAGE_EXPORT_DIMENSION &&
        height in 1..MAX_IMAGE_EXPORT_DIMENSION &&
        width.toLong() * height <= MAX_IMAGE_EXPORT_PIXELS

internal fun resolveScannerV2CaptureCrop(
    suggested: PageQuad?,
    detected: PageQuad?,
    preferSuggested: Boolean = false,
): PageQuad =
    suggested.takeIf { preferSuggested } ?: detected ?: suggested ?: PageQuad.fullFrame()

internal data class ScannerV2AutoCaptureDecision(
    val guide: PageQuad?,
    val ready: Boolean,
    val shouldCapture: Boolean,
)

internal class ScannerV2AutoCaptureGate(
    private val requiredStableFrames: Int = 3,
) {
    private val recentDetections = ArrayDeque<PageQuad>()
    private var smoothedGuide: PageQuad? = null
    private var missedFrames = 0
    private var captured = false

    init {
        require(requiredStableFrames in 2..10) { "Auto capture stability is invalid" }
    }

    @Synchronized
    fun update(detected: PageQuad?): ScannerV2AutoCaptureDecision {
        if (detected == null) {
            missedFrames += 1
            if (missedFrames > MAX_AUTO_CAPTURE_MISSED_FRAMES) reset()
            return ScannerV2AutoCaptureDecision(
                smoothedGuide,
                ready = false,
                shouldCapture = false,
            )
        }
        missedFrames = 0
        val eligible = detected.area in MIN_AUTO_CAPTURE_AREA..MAX_AUTO_CAPTURE_AREA
        if (!eligible) {
            reset()
            return ScannerV2AutoCaptureDecision(null, ready = false, shouldCapture = false)
        }
        recentDetections.addLast(detected)
        while (recentDetections.size > requiredStableFrames * 2) recentDetections.removeFirst()
        val consensus = requireNotNull(scannerV2QuadConsensus(recentDetections))
        val previousGuide = smoothedGuide
        smoothedGuide =
            if (
                previousGuide != null &&
                maximumScannerV2CornerMovement(previousGuide, consensus.guide) <=
                MAX_AUTO_CAPTURE_CLUSTER_MOVEMENT
            ) {
                smoothQuad(previousGuide, consensus.guide)
            } else {
                consensus.guide
            }
        val ready =
            consensus.count >= requiredStableFrames &&
                maximumScannerV2CornerMovement(consensus.guide, detected) <=
                MAX_AUTO_CAPTURE_CLUSTER_MOVEMENT
        val shouldCapture = ready && !captured
        if (shouldCapture) captured = true
        return ScannerV2AutoCaptureDecision(smoothedGuide, ready, shouldCapture)
    }

    @Synchronized
    fun reset() {
        recentDetections.clear()
        smoothedGuide = null
        missedFrames = 0
        captured = false
    }
}

private data class ScannerV2QuadConsensus(val guide: PageQuad, val count: Int)

private fun scannerV2QuadConsensus(detections: Collection<PageQuad>): ScannerV2QuadConsensus? {
    var best: ScannerV2QuadConsensus? = null
    var bestDispersion = Double.POSITIVE_INFINITY
    detections.forEach { seed ->
        val cluster = detections.filter {
            maximumScannerV2CornerMovement(seed, it) <= MAX_AUTO_CAPTURE_CLUSTER_MOVEMENT
        }
        val dispersion = cluster.sumOf { maximumScannerV2CornerMovement(seed, it) }
        if (cluster.size > (best?.count ?: 0) || cluster.size == best?.count && dispersion < bestDispersion) {
            best = ScannerV2QuadConsensus(seed, cluster.size)
            bestDispersion = dispersion
        }
    }
    return best
}

internal fun maximumScannerV2CornerMovement(first: PageQuad, second: PageQuad): Double = maxOf(
    pointDistance(first.topLeft, second.topLeft),
    pointDistance(first.topRight, second.topRight),
    pointDistance(first.bottomRight, second.bottomRight),
    pointDistance(first.bottomLeft, second.bottomLeft),
)

private fun pointDistance(first: NormalizedPoint, second: NormalizedPoint): Double =
    hypot(first.x - second.x, first.y - second.y)

private fun smoothQuad(previous: PageQuad?, current: PageQuad): PageQuad {
    if (previous == null) return current
    fun blend(old: NormalizedPoint, new: NormalizedPoint): NormalizedPoint = NormalizedPoint(
        x = old.x + (new.x - old.x) * AUTO_CAPTURE_SMOOTHING_WEIGHT,
        y = old.y + (new.y - old.y) * AUTO_CAPTURE_SMOOTHING_WEIGHT,
    )
    return try {
        PageQuad.create(
            topLeft = blend(previous.topLeft, current.topLeft),
            topRight = blend(previous.topRight, current.topRight),
            bottomRight = blend(previous.bottomRight, current.bottomRight),
            bottomLeft = blend(previous.bottomLeft, current.bottomLeft),
        )
    } catch (_: IllegalArgumentException) {
        current
    }
}

internal fun reserveScannerV2Capture(
    current: ScannerV2Manifest,
    pageId: PageId,
    updatedAtMillis: Long,
    useFullFrame: Boolean = false,
): ScannerV2Manifest {
    check(current.state.stage == ScannerSessionStage.Capturing) { "Scanner is not ready to capture" }
    check(current.pendingCaptureId == null) { "Scanner capture is already pending" }
    check(current.retiredPages.isEmpty()) { "Scanner page cleanup is pending" }
    require(current.pages.none { it.pageId == pageId }) { "Scanner capture id is duplicated" }
    return ScannerV2Manifest.create(
        sessionId = current.sessionId,
        state = current.state,
        pages = current.pages,
        retiredPages = current.retiredPages,
        pendingCaptureId = pageId,
        pendingCaptureUseFullFrame = useFullFrame,
        editSource = current.editSource,
        updatedAtMillis = nextScannerV2Timestamp(current, updatedAtMillis),
    )
}

internal fun completeScannerV2Capture(
    current: ScannerV2Manifest,
    callbackGeneration: Long,
    record: ScannerV2PageRecord,
    updatedAtMillis: Long,
): ScannerV2Manifest {
    if (
        current.state.stage != ScannerSessionStage.Capturing ||
            callbackGeneration != current.state.generation ||
            current.pendingCaptureId == null
    ) {
        return current
    }
    require(record.pageId == current.pendingCaptureId) { "Scanner capture identity changed" }
    val nextState = ScannerSessionGate.completeCapture(
        current = current.state,
        callbackGeneration = callbackGeneration,
        page = ScannerPage(record.pageId),
    )
    val replacementIndex = current.state.pendingReplacementIndex
    val retiredPages = if (replacementIndex == null) {
        current.retiredPages
    } else {
        current.retiredPages + current.pages[replacementIndex]
    }
    val pages = if (replacementIndex == null) {
        current.pages + record
    } else {
        current.pages.toMutableList().apply { this[replacementIndex] = record }
    }
    return ScannerV2Manifest.create(
        sessionId = current.sessionId,
        state = nextState,
        pages = pages,
        retiredPages = retiredPages,
        pendingCaptureId = null,
        editSource = current.editSource,
        updatedAtMillis = nextScannerV2Timestamp(current, updatedAtMillis),
    )
}

internal fun cancelScannerV2Capture(
    current: ScannerV2Manifest,
    callbackGeneration: Long,
    updatedAtMillis: Long,
): ScannerV2Manifest {
    if (
        current.state.stage != ScannerSessionStage.Capturing ||
            callbackGeneration != current.state.generation
    ) {
        return current
    }
    val nextState = ScannerSessionGate.cancelCapture(current.state, callbackGeneration)
    return ScannerV2Manifest.create(
        sessionId = current.sessionId,
        state = nextState,
        pages = current.pages,
        retiredPages = current.retiredPages,
        pendingCaptureId = null,
        editSource = current.editSource,
        updatedAtMillis = nextScannerV2Timestamp(current, updatedAtMillis),
    )
}

private fun nextScannerV2Timestamp(current: ScannerV2Manifest, requested: Long): Long {
    require(requested > current.updatedAtMillis) { "Scanner timestamp did not advance" }
    return requested
}
