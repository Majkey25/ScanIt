package com.majkeylab.scanit

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.min

@Composable
internal fun ManualCleanupEditorScreen(
    result: ScreenState.Result,
    editor: ManualCleanupEditorState,
    onClose: () -> Unit,
    onStrokesChange: (List<MarkStroke>) -> Unit,
    onApply: () -> Unit,
) {
    val strokes =
        remember(editor.source) {
            mutableStateListOf<SnapshotStateList<MarkPoint>>().apply {
                editor.strokes.forEach { stroke ->
                    add(mutableStateListOf<MarkPoint>().apply { addAll(stroke.points) })
                }
            }
        }
    var pointCount by remember(editor.source) { mutableIntStateOf(editor.strokes.sumOf { it.points.size }) }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = { ManualCleanupTopBar(!editor.applying, onClose) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.manual_cleanup_hint))
            ManualCleanupCanvas(
                page = result.thumbnail,
                strokes = strokes,
                enabled = !editor.applying,
                pointCount = pointCount,
                onPointAdded = { pointCount++ },
                onDraftCommitted = { onStrokesChange(strokes.toCleanupStrokes()) },
                modifier =
                    Modifier.fillMaxWidth().weight(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(1.dp, MaterialTheme.colorScheme.outline),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        strokes.removeLastOrNull()?.let { pointCount -= it.size }
                        onStrokesChange(strokes.toCleanupStrokes())
                    },
                    enabled = strokes.isNotEmpty() && !editor.applying,
                ) {
                    Text(stringResource(R.string.undo_drawing))
                }
                TextButton(
                    onClick = {
                        strokes.clear()
                        pointCount = 0
                        onStrokesChange(emptyList())
                    },
                    enabled = strokes.isNotEmpty() && !editor.applying,
                ) {
                    Text(stringResource(R.string.clear_drawing))
                }
            }
            editor.message?.let { message ->
                Text(
                    message.resolve(),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                )
            }
            if (editor.applying) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.manual_cleanup_working))
                }
            }
            Button(
                onClick = onApply,
                enabled =
                    !editor.applying &&
                        strokes.isNotEmpty() &&
                        strokes.all { it.size >= 3 },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Text(stringResource(R.string.manual_cleanup_apply))
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun ManualCleanupTopBar(
    closeEnabled: Boolean,
    onClose: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose, enabled = closeEnabled, modifier = Modifier.size(48.dp)) {
                Icon(
                    painterResource(R.drawable.ic_arrow_back),
                    stringResource(R.string.back),
                    Modifier.size(24.dp),
                )
            }
            Text(
                stringResource(R.string.manual_cleanup),
                modifier = Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ManualCleanupCanvas(
    page: android.graphics.Bitmap?,
    strokes: SnapshotStateList<SnapshotStateList<MarkPoint>>,
    enabled: Boolean,
    pointCount: Int,
    onPointAdded: () -> Unit,
    onDraftCommitted: () -> Unit,
    modifier: Modifier,
) {
    val paint = remember { AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG) }
    val currentPointCount by rememberUpdatedState(pointCount)
    val currentOnPointAdded by rememberUpdatedState(onPointAdded)
    val currentOnDraftCommitted by rememberUpdatedState(onDraftCommitted)
    val description = stringResource(R.string.manual_cleanup_canvas_accessibility)
    Canvas(
        modifier =
            modifier.semantics { contentDescription = description }.focusable()
                .pointerInput(page, enabled) {
                    val pageBitmap = page ?: return@pointerInput
                    if (!enabled) return@pointerInput
                    var active: SnapshotStateList<MarkPoint>? = null
                    detectDragGestures(
                        onDragStart = { offset ->
                            if (
                                strokes.size >= MAX_MARK_DRAWING_STROKES ||
                                    currentPointCount >= MAX_MARK_DRAWING_POINTS
                            ) {
                                return@detectDragGestures
                            }
                            offset.normalizedCleanupPoint(size, pageBitmap)?.let { point ->
                                active = mutableStateListOf(point)
                                strokes.add(requireNotNull(active))
                                currentOnPointAdded()
                            }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val stroke = active ?: return@detectDragGestures
                            if (currentPointCount >= MAX_MARK_DRAWING_POINTS) return@detectDragGestures
                            change.position.normalizedCleanupPoint(size, pageBitmap)?.let { point ->
                                stroke.add(point)
                                currentOnPointAdded()
                            }
                        },
                        onDragEnd = {
                            active = null
                            currentOnDraftCommitted()
                        },
                        onDragCancel = {
                            active = null
                            currentOnDraftCommitted()
                        },
                    )
                },
    ) {
        val pageBitmap = page ?: return@Canvas
        val scale = min(size.width / pageBitmap.width, size.height / pageBitmap.height)
        val pageWidth = pageBitmap.width * scale
        val pageHeight = pageBitmap.height * scale
        val left = (size.width - pageWidth) / 2f
        val top = (size.height - pageHeight) / 2f
        drawContext.canvas.nativeCanvas.drawBitmap(
            pageBitmap,
            null,
            RectF(left, top, left + pageWidth, top + pageHeight),
            paint,
        )
        strokes.forEach { stroke ->
            val points = stroke.map { Offset(left + it.x * pageWidth, top + it.y * pageHeight) }
            if (points.size >= 2) {
                val path = Path().apply {
                    moveTo(points.first().x, points.first().y)
                    points.drop(1).forEach { lineTo(it.x, it.y) }
                    if (points.size >= 3) close()
                }
                if (points.size >= 3) drawPath(path, Color(0x33D32F2F))
                drawPath(
                    path,
                    Color(0xFFD32F2F),
                    style = Stroke(4f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
    }
}

private fun Offset.normalizedCleanupPoint(
    size: androidx.compose.ui.unit.IntSize,
    page: android.graphics.Bitmap,
): MarkPoint? {
    if (size.width <= 0 || size.height <= 0) return null
    val scale = min(size.width.toFloat() / page.width, size.height.toFloat() / page.height)
    val pageWidth = page.width * scale
    val pageHeight = page.height * scale
    val left = (size.width - pageWidth) / 2f
    val top = (size.height - pageHeight) / 2f
    if (x !in left..left + pageWidth || y !in top..top + pageHeight) return null
    return MarkPoint((x - left) / pageWidth, (y - top) / pageHeight)
}

private fun List<SnapshotStateList<MarkPoint>>.toCleanupStrokes(): List<MarkStroke> = map(::MarkStroke)
