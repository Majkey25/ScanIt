package com.majkeylab.scanit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

private data class InkSample(val x: Float, val y: Float, val pressure: Float)

/** Owns only the current gesture. Committed annotations belong to the ViewModel. */
internal class DocumentEditorCanvas(context: Context) : View(context) {
    var page: Bitmap? = null
        set(value) { field = value; invalidate() }
    var annotations: List<PageAnnotation> = emptyList()
        set(value) {
            if (field === value) return
            if (field == value) { field = value; return }
            cancelGesture()
            ignoreRemainingContacts = true
            field = value
            invalidate()
        }
    var tool: AnnotationTool = AnnotationTool.Pen
    var inkColor: Int = android.graphics.Color.BLACK
    var inkWidth: Float = 0.004f
    var pageAspectRatio: Float? = null
    var fingerDrawing: Boolean = true
    var onCommit: (List<PageAnnotation>) -> Unit = {}
    var onText: (Int?, AnnotationPoint) -> Unit = { _, _ -> }
    var onStylus: () -> Unit = {}
    var onLimit: () -> Unit = {}
    var canvasBackgroundColor: Int = android.graphics.Color.LTGRAY
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val points = mutableListOf<AnnotationPoint>()
    private val inkSegments = mutableListOf<List<AnnotationPoint>>()
    private var previousSample: InkSample? = null
    private var gesturePointCount = 0
    private var inkLimitReached = false
    private val erased = mutableSetOf<Int>()
    private var movedText: Pair<Int, PageAnnotation.Text>? = null
    private var activeId = -1
    private var activeTool = AnnotationTool.Navigate
    private var activeStylus = false
    private var ignoreRemainingContacts = false
    private var lastX = 0f
    private var lastY = 0f
    private var lastSpan = 0f
    private var downX = 0f
    private var downY = 0f
    private var textOrigin: PageAnnotation.Text? = null
    private var pointBudget = 0
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f

    init { isFocusable = true; isClickable = true }

    fun fitPage() { zoom = 1f; panX = 0f; panY = 0f; invalidate() }

    fun zoomBy(factor: Float) {
        zoom = (zoom * factor).coerceIn(1f, 6f)
        constrainPan()
        invalidate()
    }

    private fun pageRect(): RectF {
        val bitmap = page ?: return RectF()
        val fit = min(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val w = bitmap.width * fit * zoom
        val h = bitmap.height * fit * zoom
        return RectF((width - w) / 2f + panX, (height - h) / 2f + panY,
            (width + w) / 2f + panX, (height + h) / 2f + panY)
    }

    private fun constrainPan() {
        val rect = pageRect()
        val maxX = ((rect.width() - width) / 2f).coerceAtLeast(0f)
        val maxY = ((rect.height() - height) / 2f).coerceAtLeast(0f)
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { constrainPan() }

    private fun point(x: Float, y: Float, pressure: Float = 1f): AnnotationPoint? {
        val rect = pageRect()
        if (rect.width() <= 0 || rect.height() <= 0 || !x.isFinite() || !y.isFinite()) return null
        if (!rect.contains(x, y)) return null
        return AnnotationPoint((x - rect.left) / rect.width(), (y - rect.top) / rect.height(),
            pressure.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 1f)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.clipRect(0, 0, width, height)
        canvas.drawColor(canvasBackgroundColor)
        val bitmap = page ?: return
        if (bitmap.isRecycled) return
        val rect = pageRect()
        if (rect.width() <= 0 || rect.height() <= 0) return
        val saved = canvas.save()
        try {
            canvas.translate(rect.left, rect.top)
            canvas.scale(rect.width() / bitmap.width, rect.height() / bitmap.height)
            canvas.clipRect(0, 0, bitmap.width, bitmap.height)
            canvas.drawBitmap(bitmap, 0f, 0f, bitmapPaint)
            val preview = annotations.mapIndexedNotNull { index, annotation ->
                if (index in erased) null else movedText?.takeIf { it.first == index }?.second ?: annotation
            }
            drawPageAnnotations(canvas, bitmap.width, bitmap.height, preview, pageAspectRatio)
            if ((points.isNotEmpty() || inkSegments.isNotEmpty()) && activeTool in listOf(AnnotationTool.Pen, AnnotationTool.Highlighter)) {
                drawPageAnnotations(canvas, bitmap.width, bitmap.height,
                    (inkSegments + listOf(points)).filter { it.isNotEmpty() }.map {
                        PageAnnotation.Ink(it, inkColor, inkWidth, activeTool == AnnotationTool.Highlighter)
                    }, pageAspectRatio)
            }
        } finally { canvas.restoreToCount(saved) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || page == null) return false
        parent?.requestDisallowInterceptTouchEvent(true)
        when (event.actionMasked) {
            MotionEvent.ACTION_CANCEL -> { cancelGesture(); ignoreRemainingContacts = false }
            MotionEvent.ACTION_DOWN -> { ignoreRemainingContacts = false; beginGesture(event, 0) }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A palm cannot turn a live pen stroke into a navigation gesture.
                if (event.getToolType(event.actionIndex) in listOf(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER)) {
                    beginGesture(event, event.actionIndex)
                } else if (!activeStylus && !ignoreRemainingContacts) {
                    cancelGesture()
                    lastX = (event.getX(0) + event.getX(1)) / 2f
                    lastY = (event.getY(0) + event.getY(1)) / 2f
                    lastSpan = hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0))
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (ignoreRemainingContacts) return true
                if (event.pointerCount >= 2 && !activeStylus) {
                    val x = (event.getX(0) + event.getX(1)) / 2f
                    val y = (event.getY(0) + event.getY(1)) / 2f
                    val span = hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0))
                    val old = pageRect()
                    val previousZoom = zoom
                    if (lastSpan > 0f && span > 0f) zoom = (zoom * span / lastSpan).coerceIn(1f, 6f)
                    val ratio = zoom / previousZoom
                    panX = x - (lastX - old.left) * ratio - (width - old.width() * ratio) / 2f
                    panY = y - (lastY - old.top) * ratio - (height - old.height() * ratio) / 2f
                    lastX = x; lastY = y; lastSpan = span
                    constrainPan()
                } else {
                    val index = event.findPointerIndex(activeId)
                    if (index < 0) return true
                    val x = event.getX(index); val y = event.getY(index)
                    if (activeTool == AnnotationTool.Navigate) {
                        panX += x - lastX; panY += y - lastY; constrainPan()
                    } else if (activeTool == AnnotationTool.Text) {
                        val original = textOrigin
                        val moved = movedText
                        val rect = pageRect()
                        if (original != null && moved != null && rect.width() > 0f && rect.height() > 0f) {
                            movedText = moved.first to original.copy(
                                x = (original.x + (x - downX) / rect.width()).coerceIn(0f, 0.98f),
                                y = (original.y + (y - downY) / rect.height()).coerceIn(0f, 0.98f),
                            )
                        }
                    } else {
                        for (h in 0 until event.historySize) {
                            collectSample(event.getHistoricalX(index, h), event.getHistoricalY(index, h),
                                if (activeStylus) event.getHistoricalPressure(index, h) else 1f)
                        }
                        collectSample(x, y, if (activeStylus) event.getPressure(index) else 1f)
                    }
                    lastX = x; lastY = y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == activeId) {
                    val wasStylus = activeStylus
                    val cancelled = Build.VERSION.SDK_INT >= 33 && event.flags and MotionEvent.FLAG_CANCELED != 0
                    if (!cancelled) {
                        collectSample(event.getX(event.actionIndex), event.getY(event.actionIndex),
                            if (activeStylus) event.getPressure(event.actionIndex) else 1f)
                        finishGesture(event)
                    }
                    cancelGesture()
                    ignoreRemainingContacts = wasStylus && event.pointerCount > 1
                    performClick()
                }
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    cancelGesture()
                    ignoreRemainingContacts = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                } else if (!activeStylus && !ignoreRemainingContacts) {
                    val remaining = (0 until event.pointerCount).filter { it != event.actionIndex }
                    val first = remaining.first()
                    val second = remaining.getOrNull(1)
                    activeId = if (second == null) event.getPointerId(first) else -1
                    activeTool = AnnotationTool.Navigate
                    lastX = if (second == null) event.getX(first) else (event.getX(first) + event.getX(second)) / 2f
                    lastY = if (second == null) event.getY(first) else (event.getY(first) + event.getY(second)) / 2f
                    lastSpan = if (second == null) 0f else hypot(event.getX(second) - event.getX(first), event.getY(second) - event.getY(first))
                }
            }
        }
        invalidate()
        return true
    }

    private fun beginGesture(event: MotionEvent, index: Int) {
        cancelGesture()
        activeId = event.getPointerId(index)
        activeStylus = event.getToolType(index) in listOf(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER)
        if (activeStylus) { ignoreRemainingContacts = false; onStylus() }
        activeTool = when {
            event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER -> AnnotationTool.Eraser
            !activeStylus && !fingerDrawing && tool in listOf(AnnotationTool.Pen, AnnotationTool.Highlighter, AnnotationTool.Eraser) -> AnnotationTool.Navigate
            else -> tool
        }
        downX = event.getX(index); downY = event.getY(index)
        lastX = downX; lastY = downY
        pointBudget = MAX_ANNOTATION_POINTS - annotations.sumOf { (it as? PageAnnotation.Ink)?.points?.size ?: 0 }
        point(downX, downY, if (activeStylus) event.getPressure(index) else 1f)?.let { point ->
            if (activeTool == AnnotationTool.Text) {
                val bitmap = checkNotNull(page)
                val textIndex = annotationsAtPoint(annotations, point, bitmap.width.toFloat(), bitmap.height.toFloat(), 0.02f, pageAspectRatio)
                    .lastOrNull { annotations[it] is PageAnnotation.Text }
                textOrigin = textIndex?.let { annotations[it] as PageAnnotation.Text }
                movedText = textIndex?.let { it to checkNotNull(textOrigin) }
            }
        }
        collectSample(downX, downY, if (activeStylus) event.getPressure(index) else 1f)
    }

    private fun collectSample(x: Float, y: Float, pressure: Float) {
        if (activeTool !in listOf(AnnotationTool.Pen, AnnotationTool.Highlighter)) {
            point(x, y, pressure)?.let(::collectPoint)
            return
        }
        val rect = pageRect()
        if (rect.width() <= 0f || rect.height() <= 0f || !x.isFinite() || !y.isFinite()) {
            completeInkSegment(); previousSample = null
            return
        }
        val sample = InkSample((x - rect.left) / rect.width(), (y - rect.top) / rect.height(),
            pressure.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 1f)
        if (!sample.x.isFinite() || !sample.y.isFinite()) {
            completeInkSegment(); previousSample = null
            return
        }
        val previous = previousSample
        previousSample = sample
        if (previous == null) point(x, y, pressure)?.let(::collectPoint)
        else clippedInkSegment(previous, sample)?.let { (start, end) -> collectPoint(start); collectPoint(end) }
        if (sample.x !in 0f..1f || sample.y !in 0f..1f) completeInkSegment()
    }

    private fun completeInkSegment() {
        if (points.isEmpty()) return
        inkSegments += points.toList()
        points.clear()
    }

    private fun clippedInkSegment(from: InkSample, to: InkSample): Pair<AnnotationPoint, AnnotationPoint>? {
        val dx = to.x.toDouble() - from.x
        val dy = to.y.toDouble() - from.y
        var start = 0.0
        var end = 1.0
        fun clip(direction: Double, distance: Double): Boolean {
            if (direction == 0.0) return distance >= 0.0
            val fraction = distance / direction
            if (direction < 0.0) start = maxOf(start, fraction) else end = minOf(end, fraction)
            return start <= end
        }
        if (!clip(-dx, from.x.toDouble()) || !clip(dx, 1.0 - from.x) ||
            !clip(-dy, from.y.toDouble()) || !clip(dy, 1.0 - from.y)) return null
        fun at(fraction: Double) = AnnotationPoint(
            (from.x + dx * fraction).toFloat().coerceIn(0f, 1f),
            (from.y + dy * fraction).toFloat().coerceIn(0f, 1f),
            (from.pressure + (to.pressure - from.pressure) * fraction).toFloat().coerceIn(0f, 1f),
        )
        return at(start) to at(end)
    }

    private fun collectPoint(point: AnnotationPoint) {
        when (activeTool) {
            AnnotationTool.Pen, AnnotationTool.Highlighter -> {
                val previous = points.lastOrNull()
                if (previous != null && hypot(point.x - previous.x, point.y - previous.y) <= 0.0004f) return
                if (annotations.size + inkSegments.size >= MAX_PAGE_ANNOTATIONS || gesturePointCount >= pointBudget) {
                    inkLimitReached = true
                    return
                }
                points += point
                gesturePointCount++
            }
            AnnotationTool.Eraser -> page?.let { bitmap ->
                erased += annotationsAtPoint(annotations, point, bitmap.width.toFloat(), bitmap.height.toFloat(), 0.02f, pageAspectRatio)
            }
            else -> Unit
        }
    }

    private fun finishGesture(event: MotionEvent) {
        val limitReached = inkLimitReached
        val updated = when {
            activeTool == AnnotationTool.Text -> {
                val moved = movedText
                if (moved != null && hypot(event.getX(event.actionIndex) - downX, event.getY(event.actionIndex) - downY) > 8 * resources.displayMetrics.density) {
                    annotations.toMutableList().also { it[moved.first] = moved.second }
                } else {
                    point(event.getX(event.actionIndex), event.getY(event.actionIndex))?.let { onText(moved?.first, it) }
                    null
                }
            }
            erased.isNotEmpty() -> annotations.filterIndexed { index, _ -> index !in erased }
            points.isNotEmpty() || inkSegments.isNotEmpty() -> {
                completeInkSegment()
                annotations + inkSegments.map { PageAnnotation.Ink(it, inkColor, inkWidth, activeTool == AnnotationTool.Highlighter) }
            }
            else -> null
        }
        if (updated != null) {
            try { validatePageAnnotations(updated); annotations = updated; onCommit(updated) }
            catch (_: IllegalArgumentException) { onLimit() }
        }
        if (limitReached) onLimit()
    }

    private fun cancelGesture() {
        points.clear(); erased.clear(); movedText = null; textOrigin = null
        inkSegments.clear(); previousSample = null; gesturePointCount = 0; inkLimitReached = false
        activeId = -1; activeStylus = false; activeTool = AnnotationTool.Navigate
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onDetachedFromWindow() { cancelGesture(); super.onDetachedFromWindow() }
}
