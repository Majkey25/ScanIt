package com.majkeylab.scanit

import boofcv.abst.filter.binary.InputToBinary
import boofcv.alg.shapes.edge.SnapToLineEdge
import boofcv.alg.shapes.polygon.DetectPolygonBinaryGrayRefine
import boofcv.factory.filter.binary.FactoryThresholdBinary
import boofcv.factory.feature.detect.line.ConfigHoughGradient
import boofcv.factory.feature.detect.line.ConfigLineRansac
import boofcv.factory.feature.detect.line.ConfigParamPolar
import boofcv.factory.feature.detect.line.FactoryDetectLine
import boofcv.factory.shape.ConfigPolygonDetector
import boofcv.factory.shape.FactoryShapeDetector
import boofcv.struct.ConfigLength
import boofcv.struct.image.GrayU8
import georegression.struct.line.LineParametric2D_F32
import georegression.struct.line.LineGeneral2D_F64
import georegression.struct.line.LineSegment2D_F32
import georegression.struct.point.Point2D_F64
import georegression.struct.shapes.Polygon2D_F64
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sign
import kotlin.math.sqrt

private const val MIN_ANALYSIS_EDGE = 32
private const val MAX_ANALYSIS_EDGE = 1024
private const val MIN_DETECTED_AREA = 0.07
private const val MAX_DETECTED_AREA = 0.94
private const val MIN_SIDE_RATIO = 0.18
private const val MAX_ASPECT_RATIO = 3.2
private const val MIN_SIDE_CONTRAST = 5.0
private const val MIN_SIDE_SUPPORT = 0.3
private const val MAX_HOUGH_LINES = 160
private const val MAX_PARALLEL_PAIRS_PER_BUCKET = 16
private const val MAX_LIVE_PARALLEL_PAIRS_PER_BUCKET = 8
private const val MAX_REFINED_CANDIDATES = 64
private const val MAX_DETAIL_CANDIDATES = 192
private const val MAX_LIVE_LINE_QUADS = 2_048
private const val MAX_STILL_LINE_QUADS = 32_768
private const val MAX_SEGMENT_LINE_QUADS = 8_192
private const val ORIENTATION_BUCKETS = 12
private const val MIN_PARALLEL_DOT = 0.88
private const val MAX_PERPENDICULAR_DOT = 0.45

internal class LumaFrame(
    val width: Int,
    val height: Int,
    ownedPixels: ByteArray,
) {
    private val pixels: ByteArray = ownedPixels

    init {
        require(width in MIN_ANALYSIS_EDGE..MAX_ANALYSIS_EDGE) { "Analysis width is invalid" }
        require(height in MIN_ANALYSIS_EDGE..MAX_ANALYSIS_EDGE) { "Analysis height is invalid" }
        require(width.toLong() * height == ownedPixels.size.toLong()) { "Analysis buffer size is invalid" }
    }

    fun unsignedPixel(index: Int): Int = pixels[index].toInt() and 0xff

    fun copyPixelsTo(destination: ByteArray) {
        require(destination.size >= pixels.size) { "Analysis destination size is invalid" }
        pixels.copyInto(destination, endIndex = pixels.size)
    }
}

internal fun detectDocumentQuad(frame: LumaFrame): PageQuad? = DocumentQuadDetector.detect(frame, true)

internal fun detectLiveDocumentQuad(frame: LumaFrame): PageQuad? = DocumentQuadDetector.detect(frame, false)

internal fun copyScannerV2LumaPlane(
    width: Int,
    height: Int,
    rowStride: Int,
    pixelStride: Int,
    source: ByteBuffer,
): LumaFrame = copyScannerV2LumaCrop(
    sourceWidth = width,
    sourceHeight = height,
    cropLeft = 0,
    cropTop = 0,
    cropWidth = width,
    cropHeight = height,
    rowStride = rowStride,
    pixelStride = pixelStride,
    source = source,
)

internal fun copyScannerV2LumaCrop(
    sourceWidth: Int,
    sourceHeight: Int,
    cropLeft: Int,
    cropTop: Int,
    cropWidth: Int,
    cropHeight: Int,
    rowStride: Int,
    pixelStride: Int,
    source: ByteBuffer,
): LumaFrame {
    require(sourceWidth in MIN_ANALYSIS_EDGE..MAX_ANALYSIS_EDGE) { "Analysis width is invalid" }
    require(sourceHeight in MIN_ANALYSIS_EDGE..MAX_ANALYSIS_EDGE) { "Analysis height is invalid" }
    require(cropWidth in MIN_ANALYSIS_EDGE..sourceWidth) { "Analysis crop width is invalid" }
    require(cropHeight in MIN_ANALYSIS_EDGE..sourceHeight) { "Analysis crop height is invalid" }
    require(cropLeft >= 0 && cropTop >= 0) { "Analysis crop origin is invalid" }
    require(cropLeft + cropWidth <= sourceWidth && cropTop + cropHeight <= sourceHeight) {
        "Analysis crop is outside the image"
    }
    require(pixelStride > 0) { "Analysis pixel stride is invalid" }
    require(rowStride >= (sourceWidth - 1L) * pixelStride + 1L) { "Analysis row stride is invalid" }
    val lastOffset = (sourceHeight - 1L) * rowStride + (sourceWidth - 1L) * pixelStride
    require(lastOffset < source.remaining()) { "Analysis plane is too short" }

    val input = source.duplicate()
    val start = input.position()
    val pixels = ByteArray(cropWidth * cropHeight)
    for (y in 0 until cropHeight) {
        val rowOffset = (cropTop + y) * rowStride + cropLeft * pixelStride
        for (x in 0 until cropWidth) {
            pixels[y * cropWidth + x] = input.get(start + rowOffset + x * pixelStride)
        }
    }
    return LumaFrame(cropWidth, cropHeight, pixels)
}

internal fun rotateScannerV2AnalysisQuad(crop: PageQuad, rotationDegrees: Int): PageQuad {
    require(rotationDegrees in setOf(0, 90, 180, 270)) { "Analysis rotation is invalid" }
    var rotated = crop
    repeat(rotationDegrees / 90) { rotated = rotated.rotateClockwise() }
    return rotated
}

private object DocumentQuadDetector {
    private val polygonDetector: DetectPolygonBinaryGrayRefine<GrayU8>
    private val lineDetector = FactoryDetectLine.houghLinePolar(
        ConfigHoughGradient(MAX_HOUGH_LINES).apply {
            localMaxRadius = 3
            minCounts = 6
            mergeAngle = Math.PI * 0.035
            mergeDistance = 5.0
            refineRadius = 2
            edgeThreshold.threshold = 8f
        },
        ConfigParamPolar(2.0, 180),
        GrayU8::class.java,
    )
    private val edgeRefiner = SnapToLineEdge<GrayU8>(24, 8, GrayU8::class.java)
    private val thresholds: List<InputToBinary<GrayU8>>
    private var gray = GrayU8(1, 1)
    private var binary = GrayU8(1, 1)
    private var sourcePixels = ByteArray(0)
    private var integral = IntArray(0)

    init {
        val config = ConfigPolygonDetector(4, 4).apply {
            detector.canTouchBorder = false
            detector.minimumContour = ConfigLength.relative(0.08, 16.0)
            detector.minimumEdgeIntensity = 3.0
            minimumRefineEdgeIntensity = 3.0
            refineContour = true
        }
        polygonDetector = FactoryShapeDetector.polygon(config, GrayU8::class.java)
        thresholds = listOf(
            FactoryThresholdBinary.globalOtsu(0.0, 255.0, 1.0, true, GrayU8::class.java),
            FactoryThresholdBinary.localMean(ConfigLength.fixed(31.0), 0.96, true, GrayU8::class.java),
        )
    }

    @Synchronized
    fun detect(frame: LumaFrame, includeSegmentFallback: Boolean): PageQuad? {
        gray.reshape(frame.width, frame.height)
        binary.reshape(frame.width, frame.height)
        val pixelCount = frame.width * frame.height
        if (sourcePixels.size < pixelCount) sourcePixels = ByteArray(pixelCount)
        frame.copyPixelsTo(sourcePixels)
        sourcePixels.copyInto(gray.data, endIndex = pixelCount)

        var best: PageQuad? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (blurRadius in listOf(0, 4, 10)) {
            if (blurRadius > 0) boxBlur(sourcePixels, gray, frame.width, frame.height, blurRadius)
            repeat(2) { polarity ->
                if (polarity == 1) invert(gray, pixelCount)
                for (threshold in thresholds) {
                    threshold.process(gray, binary)
                    polygonDetector.process(gray, binary)
                    for (polygon in polygonDetector.getPolygons(null, null)) {
                        val candidate = polygon.toCandidate(frame.width, frame.height) ?: continue
                        val evidence = sideEvidence(frame, polygon)
                        if (!evidence.isDocumentBoundary) continue
                        val score = candidate.score(evidence)
                        if (score > bestScore) {
                            bestScore = score
                            best = candidate.quad
                        }
                    }
                }
            }
            if (blurRadius == 0) sourcePixels.copyInto(gray.data, endIndex = pixelCount)
        }
        if (best != null) return best
        boxBlur(sourcePixels, gray, frame.width, frame.height, radius = 3)
        val detectedLines = lineDetector.detect(gray)
        val segmentCandidates = if (includeSegmentFallback) {
            val detector = FactoryDetectLine.lineRansac(
                ConfigLineRansac(24, 10.0, 2.36, true),
                GrayU8::class.java,
            )
            buildList {
                repeat(5) {
                    addAll(
                        segmentQuads(
                            detector.detect(gray),
                            frame.width,
                            frame.height,
                            MAX_SEGMENT_LINE_QUADS,
                        ),
                    )
                }
            }
        } else {
            emptyList()
        }
        sourcePixels.copyInto(gray.data, endIndex = pixelCount)
        edgeRefiner.setImage(gray)
        val lineCandidates = lineQuads(
            detectedLines,
            frame.width,
            frame.height,
            if (includeSegmentFallback) {
                MAX_PARALLEL_PAIRS_PER_BUCKET
            } else {
                MAX_LIVE_PARALLEL_PAIRS_PER_BUCKET
            },
            if (includeSegmentFallback) MAX_STILL_LINE_QUADS else MAX_LIVE_LINE_QUADS,
        ) +
            segmentCandidates
        val shortlisted = lineCandidates.asSequence()
            .mapNotNull { polygon ->
                val candidate = polygon.toCandidate(frame.width, frame.height) ?: return@mapNotNull null
                val evidence = sideEvidence(frame, polygon)
                if (!evidence.isDocumentBoundary) return@mapNotNull null
                SeedCandidate(polygon, candidate.score(evidence))
            }
            .sortedByDescending(SeedCandidate::score)
            .take(MAX_DETAIL_CANDIDATES)
            .map { seed ->
                val candidate = requireNotNull(seed.polygon.toCandidate(frame.width, frame.height))
                val evidence = sideEvidence(frame, seed.polygon)
                seed.copy(score = candidate.score(evidence, interiorDetailScore(frame, candidate.quad)))
            }
            .sortedByDescending(SeedCandidate::score)
            .take(MAX_REFINED_CANDIDATES)
            .toList()
        for (seed in shortlisted) {
            val polygon = seed.polygon
            val refined = edgeRefiner.refinePolygon(polygon, frame.width, frame.height) ?: polygon
            val candidate = refined.toCandidate(frame.width, frame.height) ?: continue
            val evidence = sideEvidence(frame, refined)
            if (!evidence.isDocumentBoundary) continue
            val detail = interiorDetailScore(frame, candidate.quad)
            val score = candidate.score(evidence, detail)
            if (score > bestScore) {
                bestScore = score
                best = candidate.quad
            }
        }
        return best
    }

    private fun boxBlur(
        source: ByteArray,
        destination: GrayU8,
        width: Int,
        height: Int,
        radius: Int,
    ) {
        val integralWidth = width + 1
        val required = integralWidth * (height + 1)
        if (integral.size < required) integral = IntArray(required)
        integral.fill(0, 0, required)
        for (y in 0 until height) {
            var rowSum = 0
            for (x in 0 until width) {
                rowSum += source[y * width + x].toInt() and 0xff
                integral[(y + 1) * integralWidth + x + 1] =
                    integral[y * integralWidth + x + 1] + rowSum
            }
        }
        for (y in 0 until height) {
            val top = (y - radius).coerceAtLeast(0)
            val bottom = (y + radius + 1).coerceAtMost(height)
            for (x in 0 until width) {
                val left = (x - radius).coerceAtLeast(0)
                val right = (x + radius + 1).coerceAtMost(width)
                val sum = integral[bottom * integralWidth + right] -
                    integral[top * integralWidth + right] -
                    integral[bottom * integralWidth + left] +
                    integral[top * integralWidth + left]
                destination.data[y * width + x] = (sum / ((right - left) * (bottom - top))).toByte()
            }
        }
    }

    private fun invert(image: GrayU8, pixelCount: Int) {
        for (index in 0 until pixelCount) {
            image.data[index] = (255 - (image.data[index].toInt() and 0xff)).toByte()
        }
    }
}

private data class NormalLine(
    val a: Double,
    val b: Double,
    val c: Double,
)

private data class RankedNormalLine(
    val line: NormalLine,
    val rank: Int,
)

private data class ParallelPair(
    val first: NormalLine,
    val second: NormalLine,
    val separation: Double,
    val rank: Int,
)

private data class PairBucket(
    val separation: Int,
    val orientation: Int,
)

private data class SeedCandidate(
    val polygon: Polygon2D_F64,
    val score: Double,
)

private fun lineQuads(
    detected: List<LineParametric2D_F32>,
    width: Int,
    height: Int,
    maxPairsPerBucket: Int,
    maxCandidates: Int,
): List<Polygon2D_F64> {
    val lines = detected.take(MAX_HOUGH_LINES)
        .mapNotNull(LineParametric2D_F32::normalized)
        .mapIndexed { index, line -> RankedNormalLine(line, index) }
    return normalLineQuads(lines, width, height, maxPairsPerBucket, maxCandidates)
}

private fun segmentQuads(
    detected: List<LineSegment2D_F32>,
    width: Int,
    height: Int,
    maxCandidates: Int,
): List<Polygon2D_F64> {
    val minimumLength = minOf(width, height) * 0.08
    val lines = detected.asSequence()
        .filter { it.length >= minimumLength }
        .sortedByDescending(LineSegment2D_F32::getLength2)
        .take(MAX_HOUGH_LINES)
        .mapIndexedNotNull { index, segment ->
            segment.normalized()?.let { line -> RankedNormalLine(line, index) }
        }
        .toList()
    return normalLineQuads(
        lines,
        width,
        height,
        MAX_PARALLEL_PAIRS_PER_BUCKET,
        maxCandidates,
    )
}

private fun normalLineQuads(
    lines: List<RankedNormalLine>,
    width: Int,
    height: Int,
    maxPairsPerBucket: Int,
    maxCandidates: Int,
): List<Polygon2D_F64> {
    val minimumDimension = minOf(width, height).toDouble()
    val minimumSeparation = minimumDimension * 0.12
    val allPairs = buildList {
        for (firstIndex in lines.indices) {
            for (secondIndex in firstIndex + 1 until lines.size) {
                val first = lines[firstIndex]
                val second = lines[secondIndex]
                if (abs(first.line.a * second.line.a + first.line.b * second.line.b) < MIN_PARALLEL_DOT) continue
                val aligned = second.line.alignedWith(first.line)
                val separation = abs(first.line.c - aligned.c)
                if (separation >= minimumSeparation) {
                    add(ParallelPair(first.line, aligned, separation, first.rank + second.rank))
                }
            }
        }
    }
    val pairs = allPairs.groupBy { pair ->
        val separationBucket = when (pair.separation / minimumDimension) {
            in 0.0..<0.25 -> 0
            in 0.25..<0.4 -> 1
            in 0.4..<0.65 -> 2
            else -> 3
        }
        var angle = atan2(pair.first.b, pair.first.a)
        if (angle < 0.0) angle += PI
        PairBucket(
            separation = separationBucket,
            orientation = (angle / PI * ORIENTATION_BUCKETS).toInt().coerceAtMost(ORIENTATION_BUCKETS - 1),
        )
    }.values.flatMap { bucket ->
        val strongest = bucket.sortedBy(ParallelPair::rank)
            .take(maxPairsPerBucket * 3 / 4)
        val widest = bucket.sortedByDescending(ParallelPair::separation)
            .take(maxPairsPerBucket / 4)
        (strongest + widest).distinct()
    }

    return buildList {
        for (firstIndex in pairs.indices) {
            for (secondIndex in firstIndex + 1 until pairs.size) {
                val first = pairs[firstIndex]
                val second = pairs[secondIndex]
                if (abs(first.first.a * second.first.a + first.first.b * second.first.b) >
                    MAX_PERPENDICULAR_DOT
                ) {
                    continue
                }
                val corners = listOfNotNull(
                    intersection(first.first, second.first),
                    intersection(first.first, second.second),
                    intersection(first.second, second.second),
                    intersection(first.second, second.first),
                )
                if (corners.size != 4 || corners.any { point ->
                        point.x !in 1.0..(width - 2.0) || point.y !in 1.0..(height - 2.0)
                    }
                ) {
                    continue
                }
                val ordered = orderCorners(corners) ?: continue
                add(Polygon2D_F64().apply { setTo(ordered) })
                if (size >= maxCandidates) return@buildList
            }
        }
    }
}

private fun LineSegment2D_F32.normalized(): NormalLine? {
    val dx = b.x.toDouble() - a.x
    val dy = b.y.toDouble() - a.y
    val length = hypot(dx, dy)
    if (length < 1e-6) return null
    var normalX = -dy / length
    var normalY = dx / length
    var distance = normalX * a.x + normalY * a.y
    val direction = if (abs(normalX) > 1e-6) normalX.sign else normalY.sign
    if (direction < 0.0) {
        normalX = -normalX
        normalY = -normalY
        distance = -distance
    }
    return NormalLine(normalX, normalY, distance)
}

private fun LineParametric2D_F32.normalized(): NormalLine? {
    val length = hypot(slope.x.toDouble(), slope.y.toDouble())
    if (length < 1e-6) return null
    var a = -slope.y / length
    var b = slope.x / length
    var c = a * p.x + b * p.y
    val direction = if (abs(a) > 1e-6) a.sign else b.sign
    if (direction < 0.0) {
        a = -a
        b = -b
        c = -c
    }
    return NormalLine(a, b, c)
}

private fun NormalLine.alignedWith(reference: NormalLine): NormalLine =
    if (a * reference.a + b * reference.b >= 0.0) this else NormalLine(-a, -b, -c)

private fun intersection(first: NormalLine, second: NormalLine): Point2D_F64? {
    val determinant = first.a * second.b - second.a * first.b
    if (abs(determinant) < 1e-6) return null
    return Point2D_F64(
        (first.c * second.b - second.c * first.b) / determinant,
        (first.a * second.c - second.a * first.c) / determinant,
    )
}

private data class QuadCandidate(
    val quad: PageQuad,
    val rectangularity: Double,
    val centerScore: Double,
) {
    fun score(evidence: SideEvidence, interiorDetail: Double = 0.0): Double =
        sqrt(quad.area) * 0.02 +
            rectangularity * 0.25 +
            centerScore * 0.05 +
            evidence.averageContrast.coerceAtMost(40.0) / 40.0 * 0.12 +
            evidence.minimumSupport * 0.32 +
            interiorDetail * 0.60
}

private fun Polygon2D_F64.toCandidate(width: Int, height: Int): QuadCandidate? {
    if (size() != 4) return null
    val ordered = orderCorners(List(4) { get(it) }) ?: return null
    val quad = try {
        PageQuad.create(
            topLeft = ordered[0].normalized(width, height),
            topRight = ordered[1].normalized(width, height),
            bottomRight = ordered[2].normalized(width, height),
            bottomLeft = ordered[3].normalized(width, height),
        )
    } catch (_: IllegalArgumentException) {
        return null
    }
    if (quad.area !in MIN_DETECTED_AREA..MAX_DETECTED_AREA) return null

    val sideLengths = List(4) { index ->
        val first = ordered[index]
        val second = ordered[(index + 1) % 4]
        hypot(second.x - first.x, second.y - first.y)
    }
    val shortest = sideLengths.minOrNull() ?: return null
    val longest = sideLengths.maxOrNull() ?: return null
    if (shortest / longest < MIN_SIDE_RATIO) return null
    val horizontal = (sideLengths[0] + sideLengths[2]) / 2.0
    val vertical = (sideLengths[1] + sideLengths[3]) / 2.0
    val aspect = maxOf(horizontal, vertical) / minOf(horizontal, vertical)
    if (aspect > MAX_ASPECT_RATIO) return null

    val vertices = List(4) { get(it) }
    val minX = vertices.minOf(Point2D_F64::x)
    val maxX = vertices.maxOf(Point2D_F64::x)
    val minY = vertices.minOf(Point2D_F64::y)
    val maxY = vertices.maxOf(Point2D_F64::y)
    val boundsArea = (maxX - minX) * (maxY - minY)
    val rectangularity = if (boundsArea > 0.0) {
        (quad.area * width * height / boundsArea).coerceIn(0.0, 1.0)
    } else {
        0.0
    }
    if (rectangularity < 0.65) return null
    val centerX = vertices.sumOf(Point2D_F64::x) / 4.0 / (width - 1)
    val centerY = vertices.sumOf(Point2D_F64::y) / 4.0 / (height - 1)
    val centerScore = (1.0 - hypot(centerX - 0.5, centerY - 0.5)).coerceIn(0.0, 1.0)
    return QuadCandidate(quad, rectangularity, centerScore)
}

private fun orderCorners(points: List<Point2D_F64>): List<Point2D_F64>? {
    val topLeft = points.minByOrNull { it.x + it.y } ?: return null
    val bottomRight = points.maxByOrNull { it.x + it.y } ?: return null
    val topRight = points.maxByOrNull { it.x - it.y } ?: return null
    val bottomLeft = points.minByOrNull { it.x - it.y } ?: return null
    val ordered = listOf(topLeft, topRight, bottomRight, bottomLeft)
    return ordered.takeIf { it.toSet().size == 4 }
}

private data class SideEvidence(
    val averageContrast: Double,
    val minimumSupport: Double,
) {
    val isDocumentBoundary: Boolean
        get() = averageContrast >= MIN_SIDE_CONTRAST && minimumSupport >= MIN_SIDE_SUPPORT
}

private fun sideEvidence(frame: LumaFrame, polygon: Polygon2D_F64): SideEvidence {
    var totalContrast = 0.0
    var totalSamples = 0
    var minimumSupport = 1.0
    for (side in 0 until 4) {
        val first = polygon.get(side)
        val second = polygon.get((side + 1) % 4)
        val dx = second.x - first.x
        val dy = second.y - first.y
        val length = hypot(dx, dy)
        if (length < 1.0) return SideEvidence(0.0, 0.0)
        val normalX = -dy / length * 2.0
        val normalY = dx / length * 2.0
        var supported = 0
        var sideSamples = 0
        for (step in 2 until 18) {
            val fraction = step / 20.0
            val x = first.x + dx * fraction
            val y = first.y + dy * fraction
            val a = frame.sample(x + normalX, y + normalY) ?: continue
            val b = frame.sample(x - normalX, y - normalY) ?: continue
            val contrast = abs(a - b)
            totalContrast += contrast
            totalSamples += 1
            sideSamples += 1
            if (contrast >= MIN_SIDE_CONTRAST) supported += 1
        }
        if (sideSamples == 0) return SideEvidence(0.0, 0.0)
        minimumSupport = minOf(minimumSupport, supported.toDouble() / sideSamples)
    }
    return SideEvidence(
        averageContrast = if (totalSamples == 0) 0.0 else totalContrast / totalSamples,
        minimumSupport = minimumSupport,
    )
}

private fun interiorDetailScore(frame: LumaFrame, quad: PageQuad): Double {
    var total = 0.0
    var samples = 0
    for (row in 1..7) {
        val y = row / 8.0
        for (column in 1..7) {
            val x = column / 8.0
            val point = quad.interpolate(x, y)
            val pixelX = point.x * (frame.width - 1)
            val pixelY = point.y * (frame.height - 1)
            val left = frame.sample(pixelX - 1.0, pixelY) ?: continue
            val right = frame.sample(pixelX + 1.0, pixelY) ?: continue
            val top = frame.sample(pixelX, pixelY - 1.0) ?: continue
            val bottom = frame.sample(pixelX, pixelY + 1.0) ?: continue
            total += abs(right - left) + abs(bottom - top)
            samples += 1
        }
    }
    return if (samples == 0) 0.0 else (total / samples / 64.0).coerceIn(0.0, 1.0)
}

private fun PageQuad.interpolate(x: Double, y: Double): NormalizedPoint {
    fun blend(first: Double, second: Double, weight: Double): Double = first + (second - first) * weight
    val topX = blend(topLeft.x, topRight.x, x)
    val topY = blend(topLeft.y, topRight.y, x)
    val bottomX = blend(bottomLeft.x, bottomRight.x, x)
    val bottomY = blend(bottomLeft.y, bottomRight.y, x)
    return NormalizedPoint(blend(topX, bottomX, y), blend(topY, bottomY, y))
}

private fun SnapToLineEdge<GrayU8>.refinePolygon(
    polygon: Polygon2D_F64,
    width: Int,
    height: Int,
): Polygon2D_F64? {
    val ordered = orderCorners(List(4) { polygon.get(it) }) ?: return null
    val lines = List(4) { index ->
        LineGeneral2D_F64().takeIf { output ->
            refine(ordered[index], ordered[(index + 1) % 4], output)
        }
    }
    if (lines.any { it == null }) return null
    val corners = List(4) { index ->
        intersection(lines[(index + 3) % 4]!!, lines[index]!!) ?: return null
    }
    if (corners.any { it.x !in 1.0..(width - 2.0) || it.y !in 1.0..(height - 2.0) }) return null
    return Polygon2D_F64().apply { setTo(corners) }
}

private fun intersection(first: LineGeneral2D_F64, second: LineGeneral2D_F64): Point2D_F64? {
    val determinant = first.A * second.B - second.A * first.B
    if (abs(determinant) < 1e-6) return null
    return Point2D_F64(
        (first.B * second.C - second.B * first.C) / determinant,
        (first.C * second.A - second.C * first.A) / determinant,
    )
}

private fun LumaFrame.sample(x: Double, y: Double): Int? {
    val sampleX = x.toInt()
    val sampleY = y.toInt()
    if (sampleX !in 0 until width || sampleY !in 0 until height) return null
    return unsignedPixel(sampleY * width + sampleX)
}

private fun Point2D_F64.normalized(width: Int, height: Int): NormalizedPoint = NormalizedPoint(
    x = (x / (width - 1)).coerceIn(0.0, 1.0),
    y = (y / (height - 1)).coerceIn(0.0, 1.0),
)
