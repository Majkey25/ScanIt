package com.majkeylab.scanit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import java.io.File

internal const val DRAWN_MARK_WIDTH = 1_024
internal const val DRAWN_MARK_HEIGHT = 384
private const val DRAWN_MARK_STROKE_WIDTH = 12f

internal fun renderDrawnMark(strokes: List<MarkStroke>): Bitmap {
    val scaled = scaleNormalizedMarkStrokes(strokes, DRAWN_MARK_WIDTH, DRAWN_MARK_HEIGHT)
    val bitmap = createBitmap(DRAWN_MARK_WIDTH, DRAWN_MARK_HEIGHT, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = DRAWN_MARK_STROKE_WIDTH
        }
    scaled.forEach { stroke ->
        if (stroke.points.size == 1) {
            val point = stroke.points.single()
            canvas.drawPoint(point.x, point.y, paint)
        } else {
            val path = Path().apply {
                moveTo(stroke.points.first().x, stroke.points.first().y)
                for (index in 1 until stroke.points.size) {
                    val point = stroke.points[index]
                    lineTo(point.x, point.y)
                }
            }
            canvas.drawPath(path, paint)
        }
    }
    return bitmap
}

internal fun renderMarkedSourceJpeg(
    source: File,
    destination: File,
    mark: Bitmap,
    placement: MarkPlacement,
    isCancelled: () -> Boolean,
) {
    require(mark.width > 0 && mark.height > 0) { "Mark bitmap is empty" }
    ScanAppearanceRenderer.renderJpeg(
        source = source,
        destination = destination,
        appearance = ScanAppearance(ScanColorMode.Color, intensity = 0, shadows = 0),
        transformBitmap = { page ->
            val rect =
                resolveMarkRect(
                    pageWidth = page.width.toFloat(),
                    pageHeight = page.height.toFloat(),
                    markWidth = mark.width,
                    markHeight = mark.height,
                    placement = placement,
                )
            Canvas(page).drawBitmap(
                mark,
                null,
                RectF(rect.left, rect.top, rect.right, rect.bottom),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
            )
        },
        isCancelled = isCancelled,
    )
}
