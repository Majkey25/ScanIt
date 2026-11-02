package com.majkeylab.scanit

import kotlin.math.abs
import kotlin.math.hypot

internal const val MAX_PAGE_ANNOTATIONS = 256
internal const val MAX_ANNOTATION_POINTS = 32_768
internal const val MAX_PAGE_ANNOTATION_POINTS = MAX_ANNOTATION_POINTS
internal const val MAX_ANNOTATION_TEXT_LENGTH = 2_000
internal const val MAX_PAGE_ANNOTATION_TEXT_LENGTH = 16_000

internal enum class AnnotationTool { Navigate, Pen, Highlighter, Text, Eraser }

internal data class AnnotationPoint(val x: Float, val y: Float, val pressure: Float = 1f) {
    init {
        requireUnitFraction(x, "Point x")
        requireUnitFraction(y, "Point y")
        requireUnitFraction(pressure, "Pressure")
    }
}

internal sealed interface PageAnnotation {
    data class Ink(
        val points: List<AnnotationPoint>,
        val color: Int,
        val widthFraction: Float,
        val highlighter: Boolean = false,
    ) : PageAnnotation {
        init {
            validateInk(this)
        }
    }

    // x/y locate the top-left text origin; size is relative to the shorter page side.
    data class Text(
        val value: String,
        val x: Float,
        val y: Float,
        val sizeFraction: Float,
        val color: Int,
    ) : PageAnnotation {
        init {
            require(value.isNotBlank() && value.length <= MAX_ANNOTATION_TEXT_LENGTH) {
                "Annotation text must contain 1 to $MAX_ANNOTATION_TEXT_LENGTH characters"
            }
            requireUnitFraction(x, "Text x")
            requireUnitFraction(y, "Text y")
            requireUnitFraction(sizeFraction, "Text size", positive = true)
        }
    }
}

internal fun validatePageAnnotations(items: List<PageAnnotation>) {
    require(items.size <= MAX_PAGE_ANNOTATIONS) { "Too many page annotations" }
    var points = 0
    var characters = 0
    for (item in items) {
        when (item) {
            is PageAnnotation.Ink -> {
                // Recheck caller-owned lists at every boundary, not just at construction.
                validateInk(item)
                points += item.points.size
                require(points <= MAX_PAGE_ANNOTATION_POINTS) { "Too many annotation points" }
            }
            is PageAnnotation.Text -> {
                characters += item.value.length
                require(characters <= MAX_PAGE_ANNOTATION_TEXT_LENGTH) { "Too much annotation text" }
            }
        }
    }
}

internal fun annotationsAtPoint(
    items: List<PageAnnotation>,
    point: AnnotationPoint,
    pageWidth: Float,
    pageHeight: Float,
    toleranceFraction: Float,
    layoutAspectRatio: Float? = null,
): Set<Int> {
    validatePageAnnotations(items)
    requireAnnotationPageSize(pageWidth, pageHeight)
    requireAnnotationLayoutAspectRatio(layoutAspectRatio)
    requireUnitFraction(toleranceFraction, "Hit tolerance")
    val tolerance = toleranceFraction.toDouble() * minOf(pageWidth, pageHeight)
    val x = point.x.toDouble() * pageWidth
    val y = point.y.toDouble() * pageHeight
    return items.indices.filterTo(linkedSetOf()) { index ->
        when (val item = items[index]) {
            is PageAnnotation.Ink -> inkContains(item, x, y, pageWidth, pageHeight, tolerance)
            is PageAnnotation.Text -> {
                val bounds = pageAnnotationTextBounds(item, pageWidth, pageHeight, layoutAspectRatio)
                bounds != null && x >= bounds.left - tolerance && x <= bounds.right + tolerance &&
                    y >= bounds.top - tolerance && y <= bounds.bottom + tolerance
            }
        }
    }
}

internal fun requireAnnotationPageSize(width: Float, height: Float) {
    require(width.isFinite() && height.isFinite() && width > 0f && height > 0f) {
        "Annotation page dimensions must be finite and positive"
    }
}

internal fun requireAnnotationLayoutAspectRatio(layoutAspectRatio: Float?) {
    require(layoutAspectRatio == null || layoutAspectRatio.isFinite() && layoutAspectRatio > 0f) {
        "Annotation layout aspect ratio must be finite and positive"
    }
}

private fun requireUnitFraction(value: Float, name: String, positive: Boolean = false) {
    require(value.isFinite() && value in 0f..1f && (!positive || value > 0f)) {
        "$name must be finite and ${if (positive) "greater than 0 and at most 1" else "between 0 and 1"}"
    }
}

private fun validateInk(ink: PageAnnotation.Ink) {
    require(ink.points.size in 1..MAX_PAGE_ANNOTATION_POINTS) { "Invalid annotation stroke point count" }
    requireUnitFraction(ink.widthFraction, "Stroke width", positive = true)
}

private fun inkContains(
    ink: PageAnnotation.Ink,
    x: Double,
    y: Double,
    width: Float,
    height: Float,
    tolerance: Double,
): Boolean {
    if (ink.color ushr 24 == 0) return false
    val radius = minOf(width, height).toDouble() * ink.widthFraction / 2
    for (index in ink.points.indices) {
        val end = ink.points[index]
        val endX = end.x.toDouble() * width
        val endY = end.y.toDouble() * height
        val endRadius = radius * end.pressure
        if (endRadius > 0 && hypot(x - endX, y - endY) <= endRadius + tolerance) return true
        if (index == 0) continue
        val start = ink.points[index - 1]
        val startRadius = radius * start.pressure
        if (startRadius == 0.0 && endRadius == 0.0) continue
        val startX = start.x.toDouble() * width
        val startY = start.y.toDouble() * height
        val dx = endX - startX
        val dy = endY - startY
        val length = hypot(dx, dy)
        if (length == 0.0) continue
        val along = ((x - startX) * dx + (y - startY) * dy) / length
        val across = ((y - startY) * dx - (x - startX) * dy) / length
        if (along in 0.0..length && abs(across) <= startRadius + (endRadius - startRadius) * along / length) {
            return true
        }
        // Test the tapered ribbon edges too; nearest centre-line pressure can miss a wide end.
        if (distanceToSegment(along, across - startRadius, length, endRadius - startRadius) <= tolerance ||
            distanceToSegment(along, across + startRadius, length, startRadius - endRadius) <= tolerance
        ) return true
    }
    return false
}

private fun distanceToSegment(x: Double, y: Double, dx: Double, dy: Double): Double {
    val fraction = ((x * dx + y * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
    return hypot(x - fraction * dx, y - fraction * dy)
}
