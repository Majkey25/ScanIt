package com.majkeylab.scanit

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import java.util.Locale
import kotlin.math.hypot

private const val HIGHLIGHTER_MAX_ALPHA = 80
private const val TEXT_LAYOUT_SHORT_SIDE = 1_000f

internal fun drawPageAnnotations(
    canvas: Canvas,
    width: Int,
    height: Int,
    annotations: List<PageAnnotation>,
    layoutAspectRatio: Float? = null,
) {
    require(width > 0 && height > 0) { "Annotation page dimensions must be positive" }
    requireAnnotationLayoutAspectRatio(layoutAspectRatio)
    validatePageAnnotations(annotations)
    val saveCount = canvas.save()
    try {
        canvas.clipRect(0, 0, width, height)
        for (annotation in annotations) {
            when (annotation) {
                is PageAnnotation.Ink -> drawAnnotationInk(canvas, width, height, annotation)
                is PageAnnotation.Text -> {
                    val layout = annotationTextLayout(annotation, width.toFloat(), height.toFloat(), layoutAspectRatio) ?: continue
                    val textSave = canvas.save()
                    try {
                        canvas.translate(annotation.x * width, annotation.y * height)
                        val scale = minOf(width, height) / TEXT_LAYOUT_SHORT_SIDE
                        canvas.scale(scale, scale)
                        layout.draw(canvas)
                    } finally {
                        canvas.restoreToCount(textSave)
                    }
                }
            }
        }
    } finally {
        canvas.restoreToCount(saveCount)
    }
}

// Bounds include visible line boxes after page clipping, using the exact drawing layout.
internal fun pageAnnotationTextBounds(
    text: PageAnnotation.Text,
    pageWidth: Float,
    pageHeight: Float,
    layoutAspectRatio: Float? = null,
): RectF? {
    requireAnnotationPageSize(pageWidth, pageHeight)
    val layout = annotationTextLayout(text, pageWidth, pageHeight, layoutAspectRatio) ?: return null
    val scale = minOf(pageWidth, pageHeight) / TEXT_LAYOUT_SHORT_SIDE
    val x = text.x * pageWidth
    val y = text.y * pageHeight
    var bounds: RectF? = null
    for (line in 0 until layout.lineCount) {
        val left = maxOf(0f, x + layout.getLineLeft(line) * scale)
        val right = minOf(pageWidth, x + layout.getLineRight(line) * scale)
        val top = maxOf(0f, y + layout.getLineTop(line) * scale)
        val bottom = minOf(pageHeight, y + layout.getLineBottom(line) * scale)
        if (top >= pageHeight) break
        if (left >= right || top >= bottom) continue
        if (bounds == null) bounds = RectF(left, top, right, bottom) else bounds.union(left, top, right, bottom)
    }
    return bounds
}

private fun annotationTextLayout(
    text: PageAnnotation.Text,
    width: Float,
    height: Float,
    layoutAspectRatio: Float? = null,
): StaticLayout? {
    requireAnnotationLayoutAspectRatio(layoutAspectRatio)
    if (text.color ushr 24 == 0 || text.x == 1f || text.y == 1f) return null
    // A fixed layout scale keeps wrapping identical between the preview and full-resolution export.
    val aspectRatio = layoutAspectRatio ?: (width / height)
    val availableWidth = ((1f - text.x) * maxOf(1f, aspectRatio) * TEXT_LAYOUT_SHORT_SIDE).toInt()
    if (availableWidth <= 0) return null
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = text.color
        typeface = Typeface.DEFAULT
        textLocale = Locale.ROOT
        textSize = text.sizeFraction * TEXT_LAYOUT_SHORT_SIDE
    }
    return StaticLayout.Builder.obtain(text.value, 0, text.value.length, paint, availableWidth)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
        .setIncludePad(false)
        .setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE)
        .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
        .build()
}

private fun drawAnnotationInk(canvas: Canvas, width: Int, height: Int, ink: PageAnnotation.Ink) {
    val radius = minOf(width, height) * ink.widthFraction / 2f
    val path = Path()
    for (index in ink.points.indices) {
        val end = ink.points[index]
        val endX = end.x * width
        val endY = end.y * height
        val endRadius = radius * end.pressure
        if (endRadius > 0f) path.addCircle(endX, endY, endRadius, Path.Direction.CW)
        if (index == 0) continue
        val start = ink.points[index - 1]
        val startRadius = radius * start.pressure
        if (startRadius == 0f && endRadius == 0f) continue
        val startX = start.x * width
        val startY = start.y * height
        val dx = endX - startX
        val dy = endY - startY
        val length = hypot(dx, dy)
        if (length == 0f) continue
        val normalX = -dy / length
        val normalY = dx / length
        path.moveTo(startX - normalX * startRadius, startY - normalY * startRadius)
        path.lineTo(endX - normalX * endRadius, endY - normalY * endRadius)
        path.lineTo(endX + normalX * endRadius, endY + normalY * endRadius)
        path.lineTo(startX + normalX * startRadius, startY + normalY * startRadius)
        path.close()
    }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink.color
        style = Paint.Style.FILL
        if (ink.highlighter) alpha = minOf(alpha, HIGHLIGHTER_MAX_ALPHA)
    }
    canvas.drawPath(path, paint)
}
