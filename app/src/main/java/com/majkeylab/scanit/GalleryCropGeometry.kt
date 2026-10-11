package com.majkeylab.scanit

import kotlin.math.hypot
import kotlin.math.roundToInt

internal data class GalleryCropPoint(val x: Float, val y: Float)

internal fun fullGalleryCrop(): List<GalleryCropPoint> =
    listOf(GalleryCropPoint(0f, 0f), GalleryCropPoint(1f, 0f), GalleryCropPoint(1f, 1f), GalleryCropPoint(0f, 1f))

internal data class GalleryCropPlan(
    val source: FloatArray,
    val destination: FloatArray,
    val width: Int,
    val height: Int,
)

internal fun galleryCropPlan(
    corners: List<GalleryCropPoint>,
    width: Int,
    height: Int,
): GalleryCropPlan? {
    if (!validOriginalImageDimensions(width, height) || corners.size != 4) return null
    if (corners.any { !it.x.isFinite() || !it.y.isFinite() || it.x !in 0f..1f || it.y !in 0f..1f }) return null
    val points = corners.map { GalleryCropPoint(it.x * width, it.y * height) }
    val edges = points.indices.map { index ->
        val a = points[index]
        val b = points[(index + 1) % 4]
        GalleryCropPoint(b.x - a.x, b.y - a.y)
    }
    // Clockwise, strictly convex corners exclude crossings and singular transforms.
    if (edges.indices.any { index ->
            val a = edges[index]
            val b = edges[(index + 1) % 4]
            a.x * b.y - a.y * b.x < 1f || hypot(a.x, a.y) < 2f
        }) return null
    val output = imageExportDimensions(
        maxOf(hypot(edges[0].x, edges[0].y), hypot(edges[2].x, edges[2].y)).roundToInt(),
        maxOf(hypot(edges[1].x, edges[1].y), hypot(edges[3].x, edges[3].y)).roundToInt(),
        null,
    )
    return GalleryCropPlan(
        points.flatMap { listOf(it.x, it.y) }.toFloatArray(),
        floatArrayOf(0f, 0f, output.width.toFloat(), 0f, output.width.toFloat(), output.height.toFloat(), 0f, output.height.toFloat()),
        output.width,
        output.height,
    )
}

internal data class GalleryCropFrame(val x: Float, val y: Float, val width: Float, val height: Float) {
    fun imagePoint(x: Float, y: Float): GalleryCropPoint =
        GalleryCropPoint(((x - this.x) / width).coerceIn(0f, 1f), ((y - this.y) / height).coerceIn(0f, 1f))
}

internal fun galleryCropFrame(imageWidth: Int, imageHeight: Int, viewWidth: Float, viewHeight: Float): GalleryCropFrame {
    require(imageWidth > 0 && imageHeight > 0 && viewWidth > 0f && viewHeight > 0f)
    val scale = minOf(viewWidth / imageWidth, viewHeight / imageHeight)
    val width = imageWidth * scale
    val height = imageHeight * scale
    return GalleryCropFrame((viewWidth - width) / 2f, (viewHeight - height) / 2f, width, height)
}
