package com.majkeylab.scanit

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

private const val REDACTION_PADDING_PIXELS = 2

internal data class RedactionPixelRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

internal data class RedactionPixelPoint(
    val x: Float,
    val y: Float,
)

internal data class RedactionPixelStroke(
    val points: List<RedactionPixelPoint>,
    val width: Float,
    val tool: RedactionTool,
)

internal fun redactionPixelRects(
    width: Int,
    height: Int,
    regions: List<NormalizedRect>,
): List<RedactionPixelRect> {
    require(width > 0 && height > 0) { "Redaction page bounds are invalid" }
    require(regions.size <= MAX_SAFE_SHARE_SUGGESTIONS_PER_PAGE) {
        "Redaction region count exceeds the per-page limit"
    }
    return regions.map { region ->
        RedactionPixelRect(
            left =
                (floor(region.left * width).toInt() - REDACTION_PADDING_PIXELS)
                    .coerceAtLeast(0),
            top =
                (floor(region.top * height).toInt() - REDACTION_PADDING_PIXELS)
                    .coerceAtLeast(0),
            right =
                (ceil(region.right * width).toInt() + REDACTION_PADDING_PIXELS)
                    .coerceAtMost(width),
            bottom =
                (ceil(region.bottom * height).toInt() + REDACTION_PADDING_PIXELS)
                    .coerceAtMost(height),
        )
    }
}

internal fun redactionPixelStrokes(
    width: Int,
    height: Int,
    strokes: List<RedactionStroke>,
): List<RedactionPixelStroke> {
    require(width > 0 && height > 0) { "Redaction page bounds are invalid" }
    validateRedactionStrokes(strokes)
    val shortEdge = min(width, height).toFloat()
    return strokes.map { stroke ->
        RedactionPixelStroke(
            points =
                stroke.points.map { point ->
                    RedactionPixelPoint(point.x * width, point.y * height)
                },
            width = (stroke.widthFraction * shortEdge).coerceAtLeast(1f),
            tool = stroke.tool,
        )
    }
}

internal fun renderRedactedJpeg(
    source: File,
    destination: File,
    regions: List<NormalizedRect>,
    isCancelled: () -> Boolean,
): JpegDimensions = renderRedactedJpeg(source, destination, regions, emptyList(), isCancelled)

internal fun renderRedactedJpeg(
    source: File,
    destination: File,
    regions: List<NormalizedRect>,
    strokes: List<RedactionStroke>,
    isCancelled: () -> Boolean,
): JpegDimensions {
    if (isCancelled()) throw CancellationException("Redaction render cancelled")
    val sourceBounds = readJpegDimensions(source)
    redactionPixelRects(sourceBounds.width, sourceBounds.height, regions)
    redactionPixelStrokes(sourceBounds.width, sourceBounds.height, strokes)
    var result: JpegDimensions? = null
    publishImageExportAtomically(destination, isCancelled) { staging ->
        val rendered =
            ScanAppearanceRenderer.renderJpeg(
                source = source,
                destination = staging,
                appearance = ScanAppearance(ScanColorMode.Natural, intensity = 0, shadows = 0),
                transformBitmap = { bitmap ->
                    val canvas = Canvas(bitmap)
                    val paint = Paint().apply {
                        color = Color.BLACK
                        isAntiAlias = true
                    }
                    redactionPixelRects(bitmap.width, bitmap.height, regions).forEach { region ->
                        if (isCancelled()) {
                            throw CancellationException("Redaction render cancelled")
                        }
                        canvas.drawRect(
                            region.left.toFloat(),
                            region.top.toFloat(),
                            region.right.toFloat(),
                            region.bottom.toFloat(),
                            paint,
                        )
                    }
                    redactionPixelStrokes(bitmap.width, bitmap.height, strokes).forEach { stroke ->
                        if (isCancelled()) {
                            throw CancellationException("Redaction render cancelled")
                        }
                        paint.strokeWidth = stroke.width
                        paint.strokeCap =
                            if (stroke.tool == RedactionTool.Line) {
                                Paint.Cap.SQUARE
                            } else {
                                Paint.Cap.ROUND
                            }
                        paint.strokeJoin =
                            if (stroke.tool == RedactionTool.Line) {
                                Paint.Join.MITER
                            } else {
                                Paint.Join.ROUND
                            }
                        if (stroke.points.size == 1) {
                            paint.style = Paint.Style.FILL
                            val point = stroke.points.single()
                            if (stroke.tool == RedactionTool.Line) {
                                val radius = stroke.width / 2f
                                canvas.drawRect(
                                    point.x - radius,
                                    point.y - radius,
                                    point.x + radius,
                                    point.y + radius,
                                    paint,
                                )
                            } else {
                                canvas.drawCircle(point.x, point.y, stroke.width / 2f, paint)
                            }
                        } else {
                            paint.style = Paint.Style.STROKE
                            val path = Path().apply {
                                moveTo(stroke.points.first().x, stroke.points.first().y)
                                stroke.points.drop(1).forEach { point -> lineTo(point.x, point.y) }
                            }
                            canvas.drawPath(path, paint)
                        }
                    }
                },
                isCancelled = isCancelled,
            )
        result = JpegDimensions(rendered.width, rendered.height)
    }
    return checkNotNull(result) { "Redaction renderer produced no JPEG" }
}
