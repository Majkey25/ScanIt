package com.majkeylab.scanit

import android.graphics.Bitmap
import android.graphics.Paint as AndroidPaint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.min
import kotlin.math.roundToInt

private data class ManualRedactionPreview(
    val page: Int = -1,
    val file: File? = null,
    val bitmap: Bitmap? = null,
)

@Composable
internal fun ManualRedactionReview(
    pages: List<File>,
    state: SafeShareState.Reviewing,
    scope: SafeShareScope,
    onCancel: () -> Unit,
    onSelectPage: (Int) -> Unit,
    onAddStroke: (RedactionStroke) -> Unit,
    onBrushWidthChange: (Float) -> Unit,
    onToolChange: (RedactionTool) -> Unit,
    onUndoStroke: () -> Unit,
    onRedoStroke: () -> Unit,
    onClearPage: () -> Unit,
    onApply: () -> Unit,
    onLoadPreview: suspend (File, Int) -> Bitmap?,
) {
    val page = pages.getOrNull(state.page)
    val preview by
        produceState(ManualRedactionPreview(), state.page, page) {
            value = ManualRedactionPreview(page = state.page, file = page)
            val bitmap =
                try {
                    page?.let { onLoadPreview(it, 2048) }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    null
                }
            value = ManualRedactionPreview(state.page, page, bitmap)
        }
    val previewReady =
        safeSharePreviewReady(
            previewPage = preview.page,
            previewFile = preview.file,
            bitmapAvailable = preview.bitmap != null,
            currentPage = state.page,
            currentFile = page,
        )
    val pageStrokes = state.strokesByPage[state.page].orEmpty()
    val totalStrokes = state.strokesByPage.values.sumOf(List<RedactionStroke>::size)
    val totalPoints = state.strokesByPage.values.flatten().sumOf { it.points.size }
    val canApply = safeShareCanApply(previewReady, totalStrokes)
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.redact_document), style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.redactionTool == RedactionTool.Line,
                    onClick = { onToolChange(RedactionTool.Line) },
                    label = { Text(stringResource(R.string.redaction_tool_line)) },
                )
                FilterChip(
                    selected = state.redactionTool == RedactionTool.Brush,
                    onClick = { onToolChange(RedactionTool.Brush) },
                    label = { Text(stringResource(R.string.redaction_tool_brush)) },
                )
            }
            Text(
                stringResource(R.string.redaction_draw_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ManualRedactionCanvas(
                bitmap = preview.bitmap.takeIf { previewReady },
                strokes = pageStrokes,
                brushWidthFraction = state.brushWidthFraction,
                tool = state.redactionTool,
                totalStrokeCount = totalStrokes,
                totalPointCount = totalPoints,
                onStroke = onAddStroke,
                modifier =
                    Modifier.fillMaxWidth().weight(1f).heightIn(min = 180.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(1.dp, MaterialTheme.colorScheme.outline),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    onClick = { onSelectPage(state.page - 1) },
                    enabled = scope == SafeShareScope.AllPages && state.page > 0,
                ) { Text(stringResource(R.string.safe_share_previous_page)) }
                Text(
                    stringResource(R.string.page_position, state.page + 1, pages.size),
                    style = MaterialTheme.typography.labelLarge,
                )
                TextButton(
                    onClick = { onSelectPage(state.page + 1) },
                    enabled =
                        scope == SafeShareScope.AllPages && state.page + 1 < pages.size,
                ) { Text(stringResource(R.string.safe_share_next_page)) }
            }
            val thicknessLabel = stringResource(R.string.redaction_brush_thickness)
            Text(
                stringResource(
                    R.string.visual_mark_slider_value,
                    thicknessLabel,
                    stringResource(
                        R.string.visual_mark_percent,
                        (state.brushWidthFraction * 100).roundToInt(),
                    ),
                ),
            )
            Slider(
                value = state.brushWidthFraction,
                onValueChange = onBrushWidthChange,
                valueRange =
                    MIN_REDACTION_BRUSH_WIDTH_FRACTION..MAX_REDACTION_BRUSH_WIDTH_FRACTION,
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = thicknessLabel
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onUndoStroke, enabled = pageStrokes.isNotEmpty()) {
                    Text(stringResource(R.string.undo_drawing))
                }
                TextButton(
                    onClick = onRedoStroke,
                    enabled = state.undoneStrokesByPage[state.page].orEmpty().isNotEmpty(),
                ) { Text(stringResource(R.string.redo_drawing)) }
                TextButton(onClick = onClearPage, enabled = pageStrokes.isNotEmpty()) {
                    Text(stringResource(R.string.clear_drawing))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = { if (canApply) onApply() },
                    enabled = canApply,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.apply_protection)) }
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
private fun ManualRedactionCanvas(
    bitmap: Bitmap?,
    strokes: List<RedactionStroke>,
    brushWidthFraction: Float,
    tool: RedactionTool,
    totalStrokeCount: Int,
    totalPointCount: Int,
    onStroke: (RedactionStroke) -> Unit,
    modifier: Modifier,
) {
    val activePoints = remember(bitmap) { mutableStateListOf<MarkPoint>() }
    var activeWidth by remember(bitmap) { mutableFloatStateOf(brushWidthFraction) }
    var activeTool by remember(bitmap) { mutableStateOf(tool) }
    val paint = remember { AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG) }
    val currentBrushWidth by rememberUpdatedState(brushWidthFraction)
    val currentTool by rememberUpdatedState(tool)
    val currentStrokeCount by rememberUpdatedState(totalStrokeCount)
    val currentPointCount by rememberUpdatedState(totalPointCount)
    val currentOnStroke by rememberUpdatedState(onStroke)
    val description = stringResource(R.string.redaction_canvas_accessibility)
    Canvas(
        modifier =
            modifier.semantics { contentDescription = description }.focusable()
                .pointerInput(bitmap) {
                    val page = bitmap ?: return@pointerInput
                    detectDragGestures(
                        onDragStart = { offset ->
                            if (
                                currentStrokeCount >= MAX_MARK_DRAWING_STROKES ||
                                    currentPointCount >= MAX_MARK_DRAWING_POINTS
                            ) {
                                return@detectDragGestures
                            }
                            offset.normalizedRedactionPoint(size, page)?.let { point ->
                                activeWidth = currentBrushWidth
                                activeTool = currentTool
                                activePoints.clear()
                                activePoints.add(point)
                            }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            if (
                                activePoints.isEmpty() ||
                                    currentPointCount + activePoints.size >= MAX_MARK_DRAWING_POINTS
                            ) {
                                return@detectDragGestures
                            }
                            change.position.normalizedRedactionPoint(size, page)?.let { point ->
                                if (activeTool == RedactionTool.Line && activePoints.size > 1) {
                                    activePoints[1] = point
                                } else {
                                    activePoints.add(point)
                                }
                            }
                        },
                        onDragEnd = {
                            if (activePoints.isNotEmpty()) {
                                currentOnStroke(
                                    RedactionStroke(
                                        redactionStrokePoints(activeTool, activePoints),
                                        activeWidth,
                                    ),
                                )
                                activePoints.clear()
                            }
                        },
                        onDragCancel = { activePoints.clear() },
                    )
                },
    ) {
        val page = bitmap ?: return@Canvas
        val scale = min(size.width / page.width, size.height / page.height)
        val pageWidth = page.width * scale
        val pageHeight = page.height * scale
        val left = (size.width - pageWidth) / 2f
        val top = (size.height - pageHeight) / 2f
        drawContext.canvas.nativeCanvas.drawBitmap(
            page,
            null,
            RectF(left, top, left + pageWidth, top + pageHeight),
            paint,
        )
        clipRect(left, top, left + pageWidth, top + pageHeight) {
            strokes.forEach { drawRedactionStroke(it.points, it.widthFraction, left, top, pageWidth, pageHeight) }
            if (activePoints.isNotEmpty()) {
                drawRedactionStroke(
                    redactionStrokePoints(activeTool, activePoints),
                    activeWidth,
                    left,
                    top,
                    pageWidth,
                    pageHeight,
                )
            }
        }
    }
}

private fun DrawScope.drawRedactionStroke(
    points: List<MarkPoint>,
    widthFraction: Float,
    left: Float,
    top: Float,
    pageWidth: Float,
    pageHeight: Float,
) {
    val offsets = points.map { Offset(left + it.x * pageWidth, top + it.y * pageHeight) }
    val width = (widthFraction * min(pageWidth, pageHeight)).coerceAtLeast(1f)
    if (offsets.size == 1) {
        drawCircle(Color.Black, width / 2f, offsets.single())
        return
    }
    if (offsets.isEmpty()) return
    val path = Path().apply {
        moveTo(offsets.first().x, offsets.first().y)
        offsets.drop(1).forEach { lineTo(it.x, it.y) }
    }
    drawPath(
        path,
        Color.Black,
        style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

private fun Offset.normalizedRedactionPoint(size: IntSize, page: Bitmap): MarkPoint? {
    if (size.width <= 0 || size.height <= 0) return null
    val scale = min(size.width.toFloat() / page.width, size.height.toFloat() / page.height)
    val pageWidth = page.width * scale
    val pageHeight = page.height * scale
    val left = (size.width - pageWidth) / 2f
    val top = (size.height - pageHeight) / 2f
    if (x !in left..left + pageWidth || y !in top..top + pageHeight) return null
    return MarkPoint((x - left) / pageWidth, (y - top) / pageHeight)
}
