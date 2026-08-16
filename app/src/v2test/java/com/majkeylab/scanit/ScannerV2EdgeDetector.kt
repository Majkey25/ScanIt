package com.majkeylab.scanit

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max

private const val MIN_ANALYSIS_EDGE = 32
private const val MAX_ANALYSIS_EDGE = 1024
private const val MIN_EDGE_MAGNITUDE = 24
private const val MAX_SOBEL_MAGNITUDE = 2040
private const val MIN_COMPONENT_SCORE = 0.008
private const val CORNER_BORDER_INSET = 0.04

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
}

internal fun detectDocumentQuad(frame: LumaFrame): PageQuad? {
    val magnitudes = sobelMagnitudes(frame)
    val threshold = edgeThreshold(magnitudes) ?: return null
    val strong = BooleanArray(magnitudes.size) { magnitudes[it] >= threshold }
    val candidates = dilateEdges(strong, frame.width, frame.height)
    val visited = BooleanArray(candidates.size)
    val queue = IntArray(candidates.size)
    var bestQuad: PageQuad? = null
    var bestScore = MIN_COMPONENT_SCORE

    for (start in candidates.indices) {
        if (!candidates[start] || visited[start]) continue
        val component = collectComponent(
            start = start,
            candidates = candidates,
            strong = strong,
            magnitudes = magnitudes,
            visited = visited,
            queue = queue,
            width = frame.width,
            height = frame.height,
        )
        val quad = component.toQuad(frame.width, frame.height) ?: continue
        val score = component.score(quad)
        if (score > bestScore) {
            bestScore = score
            bestQuad = quad
        }
    }
    return bestQuad
}

internal fun copyScannerV2LumaPlane(
    width: Int,
    height: Int,
    rowStride: Int,
    pixelStride: Int,
    source: ByteBuffer,
): LumaFrame {
    require(width in MIN_ANALYSIS_EDGE..MAX_ANALYSIS_EDGE) { "Analysis width is invalid" }
    require(height in MIN_ANALYSIS_EDGE..MAX_ANALYSIS_EDGE) { "Analysis height is invalid" }
    require(pixelStride > 0) { "Analysis pixel stride is invalid" }
    require(rowStride >= (width - 1L) * pixelStride + 1L) { "Analysis row stride is invalid" }
    val lastOffset = (height - 1L) * rowStride + (width - 1L) * pixelStride
    require(lastOffset < source.remaining()) { "Analysis plane is too short" }

    val input = source.duplicate()
    val start = input.position()
    val pixels = ByteArray(width * height)
    for (y in 0 until height) {
        val rowOffset = y * rowStride
        for (x in 0 until width) {
            pixels[y * width + x] = input.get(start + rowOffset + x * pixelStride)
        }
    }
    return LumaFrame(width, height, pixels)
}

internal fun rotateScannerV2AnalysisQuad(crop: PageQuad, rotationDegrees: Int): PageQuad {
    require(
        rotationDegrees == 0 || rotationDegrees == 90 ||
            rotationDegrees == 180 || rotationDegrees == 270,
    ) { "Analysis rotation is invalid" }
    var rotated = crop
    repeat(rotationDegrees / 90) { rotated = rotated.rotateClockwise() }
    return rotated
}

private data class EdgeComponent(
    val strongCount: Int,
    val edgeMagnitude: Long,
    val minX: Int,
    val maxX: Int,
    val minY: Int,
    val maxY: Int,
    val topLeftIndex: Int,
    val topRightIndex: Int,
    val bottomRightIndex: Int,
    val bottomLeftIndex: Int,
    val cornerEvidenceCount: Int,
    val frameWidth: Int,
    val frameHeight: Int,
) {
    fun toQuad(width: Int, height: Int): PageQuad? {
        if (cornerEvidenceCount == 0) return null
        val spanWidth = maxX - minX
        val spanHeight = maxY - minY
        if (spanWidth < width * .15 || spanHeight < height * .15) return null
        val borderSides = (if (minX <= 1) 1 else 0) +
            (if (maxX >= width - 2) 1 else 0) +
            (if (minY <= 1) 1 else 0) +
            (if (maxY >= height - 2) 1 else 0)
        if (borderSides >= 3) return null
        val perimeter = 2 * (spanWidth + spanHeight)
        if (strongCount < perimeter * .12) return null

        return try {
            PageQuad.create(
                topLeft = topLeftIndex.normalized(width, height),
                topRight = topRightIndex.normalized(width, height),
                bottomRight = bottomRightIndex.normalized(width, height),
                bottomLeft = bottomLeftIndex.normalized(width, height),
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun score(quad: PageQuad): Double {
        val averageEdge = edgeMagnitude.toDouble() / strongCount.coerceAtLeast(1)
        val spanPerimeter = 2 * ((maxX - minX) + (maxY - minY)).coerceAtLeast(1)
        val coverage = (strongCount.toDouble() / spanPerimeter).coerceAtMost(1.0)
        val borderPenalty = if (
            minX <= frameWidth * .02 || maxX >= frameWidth * .98 ||
            minY <= frameHeight * .02 || maxY >= frameHeight * .98
        ) .45 else 1.0
        return quad.area * (averageEdge / MAX_SOBEL_MAGNITUDE) * coverage * borderPenalty
    }
}

private fun collectComponent(
    start: Int,
    candidates: BooleanArray,
    strong: BooleanArray,
    magnitudes: IntArray,
    visited: BooleanArray,
    queue: IntArray,
    width: Int,
    height: Int,
): EdgeComponent {
    var head = 0
    var tail = 0
    queue[tail++] = start
    visited[start] = true
    var strongCount = 0
    var edgeMagnitude = 0L
    var minX = width
    var maxX = 0
    var minY = height
    var maxY = 0
    var topLeft = start
    var topRight = start
    var bottomRight = start
    var bottomLeft = start
    var minSum = Int.MAX_VALUE
    var maxSum = Int.MIN_VALUE
    var minDifference = Int.MAX_VALUE
    var maxDifference = Int.MIN_VALUE
    var cornerEvidenceCount = 0
    val insetX = width * CORNER_BORDER_INSET
    val insetY = height * CORNER_BORDER_INSET

    while (head < tail) {
        val index = queue[head++]
        val x = index % width
        val y = index / width
        minX = minOf(minX, x)
        maxX = maxOf(maxX, x)
        minY = minOf(minY, y)
        maxY = maxOf(maxY, y)
        if (strong[index]) {
            strongCount += 1
            edgeMagnitude += magnitudes[index]
            if (x >= insetX && x <= width - 1 - insetX && y >= insetY && y <= height - 1 - insetY) {
                cornerEvidenceCount += 1
                val sum = x + y
                val difference = x - y
                if (sum < minSum) {
                    minSum = sum
                    topLeft = index
                }
                if (difference > maxDifference) {
                    maxDifference = difference
                    topRight = index
                }
                if (sum > maxSum) {
                    maxSum = sum
                    bottomRight = index
                }
                if (difference < minDifference) {
                    minDifference = difference
                    bottomLeft = index
                }
            }
        }

        val firstY = max(0, y - 1)
        val lastY = minOf(height - 1, y + 1)
        val firstX = max(0, x - 1)
        val lastX = minOf(width - 1, x + 1)
        for (nextY in firstY..lastY) {
            for (nextX in firstX..lastX) {
                val next = nextY * width + nextX
                if (!visited[next] && candidates[next]) {
                    visited[next] = true
                    queue[tail++] = next
                }
            }
        }
    }
    return EdgeComponent(
        strongCount = strongCount,
        edgeMagnitude = edgeMagnitude,
        minX = minX,
        maxX = maxX,
        minY = minY,
        maxY = maxY,
        topLeftIndex = topLeft,
        topRightIndex = topRight,
        bottomRightIndex = bottomRight,
        bottomLeftIndex = bottomLeft,
        cornerEvidenceCount = cornerEvidenceCount,
        frameWidth = width,
        frameHeight = height,
    )
}

private fun sobelMagnitudes(frame: LumaFrame): IntArray {
    val width = frame.width
    val height = frame.height
    val result = IntArray(width * height)
    fun value(x: Int, y: Int): Int = frame.unsignedPixel(y * width + x)

    for (y in 1 until height - 1) {
        for (x in 1 until width - 1) {
            val gx = -value(x - 1, y - 1) + value(x + 1, y - 1) -
                2 * value(x - 1, y) + 2 * value(x + 1, y) -
                value(x - 1, y + 1) + value(x + 1, y + 1)
            val gy = -value(x - 1, y - 1) - 2 * value(x, y - 1) - value(x + 1, y - 1) +
                value(x - 1, y + 1) + 2 * value(x, y + 1) + value(x + 1, y + 1)
            result[y * width + x] = abs(gx) + abs(gy)
        }
    }
    return result
}

private fun edgeThreshold(magnitudes: IntArray): Int? {
    val histogram = IntArray(MAX_SOBEL_MAGNITUDE + 1)
    var nonZero = 0
    for (magnitude in magnitudes) {
        if (magnitude > 0) {
            histogram[magnitude.coerceAtMost(MAX_SOBEL_MAGNITUDE)] += 1
            nonZero += 1
        }
    }
    if (nonZero == 0) return null
    val target = (nonZero * .75).toInt().coerceAtLeast(1)
    var seen = 0
    for (magnitude in histogram.indices) {
        seen += histogram[magnitude]
        if (seen >= target) return max(MIN_EDGE_MAGNITUDE, magnitude)
    }
    return null
}

private fun dilateEdges(strong: BooleanArray, width: Int, height: Int): BooleanArray {
    val result = BooleanArray(strong.size)
    for (y in 1 until height - 1) {
        for (x in 1 until width - 1) {
            val index = y * width + x
            if (!strong[index]) continue
            for (nextY in y - 1..y + 1) {
                for (nextX in x - 1..x + 1) {
                    result[nextY * width + nextX] = true
                }
            }
        }
    }
    return result
}

private fun Int.normalized(width: Int, height: Int): NormalizedPoint = NormalizedPoint(
    x = (this % width).toDouble() / (width - 1),
    y = (this / width).toDouble() / (height - 1),
)
