package com.majkeylab.scanit

import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val MIN_PAGE_QUAD_AREA = 0.01
private const val MAX_CORNER_NUDGE = 0.1
private const val GEOMETRY_EPSILON = 1e-8

internal data class NormalizedPoint(val x: Double, val y: Double) {
    init {
        require(x.isFinite() && y.isFinite()) { "Crop point is not finite" }
        require(x in 0.0..1.0 && y in 0.0..1.0) { "Crop point is outside the image" }
    }
}

internal enum class PageCorner {
    TopLeft,
    TopRight,
    BottomRight,
    BottomLeft,
}

@ConsistentCopyVisibility
internal data class PageQuad private constructor(
    val topLeft: NormalizedPoint,
    val topRight: NormalizedPoint,
    val bottomRight: NormalizedPoint,
    val bottomLeft: NormalizedPoint,
    val area: Double,
) {
    fun rotateClockwise(): PageQuad = create(
        topLeft = bottomLeft.rotateClockwise(),
        topRight = topLeft.rotateClockwise(),
        bottomRight = topRight.rotateClockwise(),
        bottomLeft = bottomRight.rotateClockwise(),
    )

    fun mirrorHorizontally(): PageQuad = create(
        topLeft = topRight.mirrorHorizontally(),
        topRight = topLeft.mirrorHorizontally(),
        bottomRight = bottomLeft.mirrorHorizontally(),
        bottomLeft = bottomRight.mirrorHorizontally(),
    )

    fun nudge(corner: PageCorner, deltaX: Double, deltaY: Double): PageQuad {
        require(deltaX.isFinite() && deltaY.isFinite()) { "Crop nudge is not finite" }
        require(kotlin.math.abs(deltaX) <= MAX_CORNER_NUDGE && kotlin.math.abs(deltaY) <= MAX_CORNER_NUDGE) {
            "Crop nudge is too large"
        }
        val current = point(corner)
        val nudged = current.copy(
            x = (current.x + deltaX).coerceIn(0.0, 1.0),
            y = (current.y + deltaY).coerceIn(0.0, 1.0),
        )
        return try {
            create(
                topLeft = if (corner == PageCorner.TopLeft) nudged else topLeft,
                topRight = if (corner == PageCorner.TopRight) nudged else topRight,
                bottomRight = if (corner == PageCorner.BottomRight) nudged else bottomRight,
                bottomLeft = if (corner == PageCorner.BottomLeft) nudged else bottomLeft,
            )
        } catch (_: IllegalArgumentException) {
            this
        }
    }

    private fun point(corner: PageCorner): NormalizedPoint = when (corner) {
        PageCorner.TopLeft -> topLeft
        PageCorner.TopRight -> topRight
        PageCorner.BottomRight -> bottomRight
        PageCorner.BottomLeft -> bottomLeft
    }

    companion object {
        fun create(
            topLeft: NormalizedPoint,
            topRight: NormalizedPoint,
            bottomRight: NormalizedPoint,
            bottomLeft: NormalizedPoint,
        ): PageQuad {
            val points = listOf(topLeft, topRight, bottomRight, bottomLeft)
            require(points.toSet().size == points.size) { "Crop corners are duplicated" }
            require(topLeft.x < topRight.x && bottomLeft.x < bottomRight.x) {
                "Crop corners are not ordered left to right"
            }
            require(topLeft.y < bottomLeft.y && topRight.y < bottomRight.y) {
                "Crop corners are not ordered top to bottom"
            }
            require(points.indices.all { index ->
                cross(
                    points[index],
                    points[(index + 1) % points.size],
                    points[(index + 2) % points.size],
                ) > GEOMETRY_EPSILON
            }) { "Crop shape is not convex clockwise" }
            val area = signedArea(points)
            require(area >= MIN_PAGE_QUAD_AREA) { "Crop area is too small" }
            return PageQuad(topLeft, topRight, bottomRight, bottomLeft, area)
        }

        fun fullFrame(): PageQuad = create(
            topLeft = NormalizedPoint(0.0, 0.0),
            topRight = NormalizedPoint(1.0, 0.0),
            bottomRight = NormalizedPoint(1.0, 1.0),
            bottomLeft = NormalizedPoint(0.0, 1.0),
        )
    }
}

internal data class WarpSize(val width: Int, val height: Int)

internal fun deriveWarpSize(
    sourceWidth: Int,
    sourceHeight: Int,
    quad: PageQuad,
): WarpSize {
    require(sourceWidth > 0 && sourceHeight > 0) { "Source dimensions are invalid" }
    val topWidth = distance(quad.topLeft, quad.topRight, sourceWidth, sourceHeight)
    val bottomWidth = distance(quad.bottomLeft, quad.bottomRight, sourceWidth, sourceHeight)
    val leftHeight = distance(quad.topLeft, quad.bottomLeft, sourceWidth, sourceHeight)
    val rightHeight = distance(quad.topRight, quad.bottomRight, sourceWidth, sourceHeight)
    val rawWidth = maxOf(topWidth, bottomWidth).roundToInt().coerceAtLeast(1)
    val rawHeight = maxOf(leftHeight, rightHeight).roundToInt().coerceAtLeast(1)
    val scale = min(
        1.0,
        min(
            MAX_IMAGE_EXPORT_DIMENSION.toDouble() / maxOf(rawWidth, rawHeight),
            sqrt(MAX_IMAGE_EXPORT_PIXELS.toDouble() / (rawWidth.toLong() * rawHeight)),
        ),
    )
    var width = floor(rawWidth * scale).toInt().coerceAtLeast(1)
    var height = floor(rawHeight * scale).toInt().coerceAtLeast(1)
    while (width.toLong() * height > MAX_IMAGE_EXPORT_PIXELS) {
        if (width >= height) width -= 1 else height -= 1
    }
    return WarpSize(width, height)
}

private fun NormalizedPoint.rotateClockwise(): NormalizedPoint = NormalizedPoint(1.0 - y, x)

private fun NormalizedPoint.mirrorHorizontally(): NormalizedPoint = NormalizedPoint(1.0 - x, y)

private fun cross(a: NormalizedPoint, b: NormalizedPoint, c: NormalizedPoint): Double =
    (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)

private fun signedArea(points: List<NormalizedPoint>): Double = points.indices.sumOf { index ->
    val current = points[index]
    val next = points[(index + 1) % points.size]
    current.x * next.y - next.x * current.y
} / 2.0

private fun distance(
    first: NormalizedPoint,
    second: NormalizedPoint,
    width: Int,
    height: Int,
): Double = hypot((second.x - first.x) * width, (second.y - first.y) * height)
