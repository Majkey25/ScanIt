package com.majkeylab.scanit

import android.graphics.Bitmap
import android.graphics.Paint as AndroidPaint
import android.graphics.RectF
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException

private const val MARK_PREVIEW_SIZE = 512
private const val MARK_THUMBNAIL_SIZE = 256

private data class LoadedVisualMarkBitmap(
    val id: String?,
    val bitmap: Bitmap?,
)

@Composable
internal fun VisualMarkEditorScreen(
    result: ScreenState.Result,
    editor: VisualMarkEditorState,
    onClose: () -> Unit,
    onSelectTemplate: (String) -> Unit,
    onPlacementChange: (MarkPlacement) -> Unit,
    onBeginDrawing: () -> Unit,
    onUpdateDrawing: (List<MarkStroke>) -> Unit,
    onCancelDrawing: () -> Unit,
    onImport: (Uri) -> Unit,
    onSaveDrawing: (List<MarkStroke>) -> Unit,
    onDeleteTemplate: (String) -> Unit,
    onLoadTemplate: suspend (String, Int) -> Bitmap?,
    onScan: () -> Unit,
    onApply: () -> Unit,
) {
    val source = editor.source
    if (source.pageIndex !in result.scan.cached.pages.indices) return
    var showDeleteConfirmation by
        rememberSaveable(source.cacheId, source.entryId, source.pageIndex) {
            mutableStateOf(false)
        }
    var manualPositionExpanded by
        rememberSaveable(source.cacheId, source.entryId, source.pageIndex) {
            mutableStateOf(false)
        }
    val selectedBitmap =
        ownedVisualMarkBitmap(editor.selectedTemplateId, MARK_PREVIEW_SIZE, onLoadTemplate)
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(onImport)
        }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = { VisualMarkTopBar(!editor.applying, onClose) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                VisualMarkPreview(
                    page = result.thumbnail,
                    mark = selectedBitmap,
                    placement = editor.placement,
                    enabled = selectedBitmap != null && !editor.busy,
                    onPlacementChange = onPlacementChange,
                )
                if (selectedBitmap != null) {
                    Text(
                        stringResource(R.string.drag_visual_mark_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            item {
                VisualMarkCreationActions(
                    enabled = !editor.busy && editor.templateIds.size < MAX_MARK_TEMPLATES,
                    onDraw = onBeginDrawing,
                    onImport = { importLauncher.launch(arrayOf("image/*")) },
                    onScan = onScan,
                )
            }
            item {
                Text(stringResource(R.string.saved_visual_marks), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                if (editor.templateIds.isEmpty()) {
                    Text(
                        stringResource(R.string.no_saved_visual_marks),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    VisualMarkTemplateRow(
                        ids = editor.templateIds,
                        selectedId = editor.selectedTemplateId,
                        enabled = !editor.busy,
                        onSelect = onSelectTemplate,
                        onLoad = onLoadTemplate,
                    )
                }
                if (editor.selectedTemplateId != null) {
                    TextButton(
                        onClick = { showDeleteConfirmation = true },
                        enabled = !editor.busy,
                    ) {
                        Text(stringResource(R.string.delete_visual_mark))
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = { manualPositionExpanded = !manualPositionExpanded },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.manual_position))
                        Icon(
                            painterResource(R.drawable.ic_expand_more),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                if (manualPositionExpanded) {
                    VisualMarkSlider(
                        stringResource(R.string.horizontal_position),
                        editor.placement.centerX,
                        selectedBitmap != null && !editor.busy,
                        0f..1f,
                    ) { onPlacementChange(editor.placement.copy(centerX = it)) }
                    VisualMarkSlider(
                        stringResource(R.string.vertical_position),
                        editor.placement.centerY,
                        selectedBitmap != null && !editor.busy,
                        0f..1f,
                    ) { onPlacementChange(editor.placement.copy(centerY = it)) }
                    VisualMarkSlider(
                        stringResource(R.string.visual_mark_size),
                        editor.placement.widthFraction,
                        selectedBitmap != null && !editor.busy,
                        MIN_MARK_WIDTH_FRACTION..MAX_MARK_WIDTH_FRACTION,
                    ) { onPlacementChange(editor.placement.copy(widthFraction = it)) }
                    VisualMarkSlider(
                        stringResource(R.string.visual_mark_rotation),
                        editor.placement.rotationDegrees,
                        selectedBitmap != null && !editor.busy,
                        -180f..180f,
                        asDegrees = true,
                    ) { onPlacementChange(editor.placement.copy(rotationDegrees = it)) }
                }
            }
            editor.message?.takeIf { editor.drawingStrokes == null }?.let { message ->
                item {
                    Text(
                        message.resolve(),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
            if (editor.busy) {
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.visual_mark_working))
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.visual_mark_disclaimer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Button(
                    onClick = onApply,
                    enabled = selectedBitmap != null && !editor.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    Text(stringResource(R.string.apply_visual_mark, source.pageIndex + 1))
                }
            }
            item { Spacer(Modifier.height(4.dp)) }
        }
    }

    editor.drawingStrokes?.takeUnless { editor.busy }?.let { strokes ->
        DrawVisualMarkDialog(
            initialStrokes = strokes,
            message = editor.message,
            onDraftChange = onUpdateDrawing,
            onDismiss = onCancelDrawing,
            onSave = onSaveDrawing,
        )
    }
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(stringResource(R.string.delete_visual_mark_title)) },
            text = { Text(stringResource(R.string.delete_visual_mark_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        editor.selectedTemplateId?.let(onDeleteTemplate)
                    },
                ) {
                    Text(stringResource(R.string.delete_visual_mark_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun VisualMarkTopBar(
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
                stringResource(R.string.visual_mark_title),
                modifier = Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun VisualMarkCreationActions(
    enabled: Boolean,
    onDraw: () -> Unit,
    onImport: () -> Unit,
    onScan: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            Triple(R.drawable.ic_signature, R.string.draw_visual_mark, onDraw),
            Triple(R.drawable.ic_image, R.string.import_visual_mark, onImport),
            Triple(R.drawable.ic_camera, R.string.scan_visual_mark, onScan),
        ).forEach { (icon, label, action) ->
            OutlinedButton(
                onClick = action,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(label))
            }
        }
    }
}

@Composable
private fun VisualMarkTemplateRow(
    ids: List<String>,
    selectedId: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onLoad: suspend (String, Int) -> Bitmap?,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(ids, key = { _, id -> id }) { index, id ->
            val selected = id == selectedId
            val description = stringResource(R.string.saved_visual_mark_number, index + 1)
            Surface(
                shape = MaterialTheme.shapes.small,
                color = Color.White,
                border =
                    BorderStroke(
                        if (selected) 3.dp else 1.dp,
                        if (selected) Color.Black else MaterialTheme.colorScheme.outline,
                    ),
                modifier =
                    Modifier.selectable(
                        selected = selected,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onSelect(id) },
                    ).semantics(mergeDescendants = true) {
                        contentDescription = description
                        this.selected = selected
                    },
            ) {
                val bitmap = ownedVisualMarkBitmap(id, MARK_THUMBNAIL_SIZE, onLoad)
                Box(
                    modifier = Modifier.width(84.dp).height(64.dp).padding(6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    bitmap?.let {
                        Image(
                            it.asImageBitmap(),
                            null,
                            Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ownedVisualMarkBitmap(
    id: String?,
    maxSide: Int,
    onLoad: suspend (String, Int) -> Bitmap?,
): Bitmap? {
    val loaded by produceState<LoadedVisualMarkBitmap?>(null, id, maxSide) {
        value = LoadedVisualMarkBitmap(id, null)
        if (id != null) {
            val bitmap =
                try {
                    onLoad(id, maxSide)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    null
                }
            value = LoadedVisualMarkBitmap(id, bitmap)
        }
    }
    return loaded?.takeIf { it.id == id }?.bitmap
}

@Composable
private fun VisualMarkPreview(
    page: Bitmap?,
    mark: Bitmap?,
    placement: MarkPlacement,
    enabled: Boolean,
    onPlacementChange: (MarkPlacement) -> Unit,
) {
    val paint = remember { AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG) }
    val description = stringResource(R.string.visual_mark_preview)
    val currentPlacement by rememberUpdatedState(placement)
    val currentOnPlacementChange by rememberUpdatedState(onPlacementChange)
    Canvas(
        modifier =
            Modifier.fillMaxWidth().height(420.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .pointerInput(page, mark, enabled) {
                    val pageBitmap = page ?: return@pointerInput
                    val markBitmap = mark ?: return@pointerInput
                    if (!enabled) return@pointerInput
                    val scale =
                        min(
                            size.width.toFloat() / pageBitmap.width,
                            size.height.toFloat() / pageBitmap.height,
                        )
                    val pageWidth = pageBitmap.width * scale
                    val pageHeight = pageBitmap.height * scale
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var gesturePlacement = currentPlacement
                        do {
                            val event = awaitPointerEvent()
                            val pan = event.calculatePan()
                            val zoom = event.calculateZoom()
                            val rotation = event.calculateRotation()
                            if (pan != Offset.Zero || zoom != 1f || rotation != 0f) {
                                gesturePlacement =
                                    transformMarkPlacement(
                                        pageWidth = pageWidth,
                                        pageHeight = pageHeight,
                                        markWidth = markBitmap.width,
                                        markHeight = markBitmap.height,
                                        placement = gesturePlacement,
                                        panX = pan.x,
                                        panY = pan.y,
                                        zoom = zoom,
                                        rotationDegrees = rotation,
                                    )
                                event.changes.forEach { it.consume() }
                                currentOnPlacementChange(gesturePlacement)
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .semantics { contentDescription = description },
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
        mark?.let {
            val rect = resolveMarkRect(pageWidth, pageHeight, it.width, it.height, placement)
            val canvas = drawContext.canvas.nativeCanvas
            val saveCount = canvas.save()
            try {
                canvas.rotate(
                    placement.rotationDegrees,
                    left + (rect.left + rect.right) / 2f,
                    top + (rect.top + rect.bottom) / 2f,
                )
                canvas.drawBitmap(
                    it,
                    null,
                    RectF(left + rect.left, top + rect.top, left + rect.right, top + rect.bottom),
                    paint,
                )
            } finally {
                canvas.restoreToCount(saveCount)
            }
        }
    }
}

@Composable
private fun VisualMarkSlider(
    label: String,
    value: Float,
    enabled: Boolean,
    valueRange: ClosedFloatingPointRange<Float>,
    asDegrees: Boolean = false,
    onValueChange: (Float) -> Unit,
) {
    val displayedValue =
        if (asDegrees) {
            stringResource(R.string.visual_mark_degrees, value.roundToInt())
        } else {
            stringResource(R.string.visual_mark_percent, (value * 100).roundToInt())
        }
    Text(stringResource(R.string.visual_mark_slider_value, label, displayedValue))
    Slider(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        valueRange = valueRange,
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = label
            stateDescription = displayedValue
        },
    )
}

@Composable
private fun DrawVisualMarkDialog(
    initialStrokes: List<MarkStroke>,
    message: UiMessage?,
    onDraftChange: (List<MarkStroke>) -> Unit,
    onDismiss: () -> Unit,
    onSave: (List<MarkStroke>) -> Unit,
) {
    val strokes =
        remember {
            mutableStateListOf<SnapshotStateList<MarkPoint>>().apply {
                initialStrokes.forEach { stroke ->
                    add(mutableStateListOf<MarkPoint>().apply { addAll(stroke.points) })
                }
            }
        }
    var pointCount by remember { mutableIntStateOf(initialStrokes.sumOf { it.points.size }) }
    var drawingSize by remember { mutableStateOf(IntSize.Zero) }
    val description = stringResource(R.string.draw_visual_mark_accessibility)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.draw_visual_mark_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(stringResource(R.string.draw_visual_mark_hint))
                message?.let {
                    Text(
                        it.resolve(),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                    )
                }
                Canvas(
                    modifier =
                        Modifier.fillMaxWidth().height(220.dp).background(Color.White)
                            .border(1.dp, Color.Gray)
                            .semantics { contentDescription = description }
                            .focusable()
                            .onSizeChanged { drawingSize = it }
                            .pointerInput(drawingSize) {
                                var active: SnapshotStateList<MarkPoint>? = null
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        val point = offset.normalized(drawingSize)
                                        if (
                                            point != null &&
                                                strokes.size < MAX_MARK_DRAWING_STROKES &&
                                                pointCount < MAX_MARK_DRAWING_POINTS
                                        ) {
                                            active = mutableStateListOf(point)
                                            strokes.add(requireNotNull(active))
                                            pointCount++
                                        }
                                    },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        val stroke = active ?: return@detectDragGestures
                                        if (pointCount >= MAX_MARK_DRAWING_POINTS) return@detectDragGestures
                                        change.position.normalized(drawingSize)?.let {
                                            stroke.add(it)
                                            pointCount++
                                        }
                                    },
                                    onDragEnd = {
                                        active = null
                                        onDraftChange(strokes.toMarkStrokes())
                                    },
                                    onDragCancel = {
                                        active = null
                                        onDraftChange(strokes.toMarkStrokes())
                                    },
                                )
                            },
                ) {
                    strokes.forEach { stroke ->
                        val points = stroke.map { Offset(it.x * size.width, it.y * size.height) }
                        if (points.size == 1) {
                            drawCircle(Color.Black, 3f, points.single())
                        } else if (points.isNotEmpty()) {
                            val path = Path().apply {
                                moveTo(points.first().x, points.first().y)
                                for (index in 1 until points.size) {
                                    lineTo(points[index].x, points[index].y)
                                }
                            }
                            drawPath(
                                path,
                                Color.Black,
                                style = Stroke(6f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                            )
                        }
                    }
                }
                Row {
                    TextButton(
                        onClick = {
                            strokes.removeLastOrNull()?.let { pointCount -= it.size }
                            onDraftChange(strokes.toMarkStrokes())
                        },
                        enabled = strokes.isNotEmpty(),
                    ) { Text(stringResource(R.string.undo_drawing)) }
                    TextButton(
                        onClick = {
                            strokes.clear()
                            pointCount = 0
                            onDraftChange(emptyList())
                        },
                        enabled = strokes.isNotEmpty(),
                    ) { Text(stringResource(R.string.clear_drawing)) }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(strokes.toMarkStrokes()) },
                enabled = strokes.isNotEmpty(),
            ) { Text(stringResource(R.string.save_visual_mark)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun Offset.normalized(size: IntSize): MarkPoint? {
    if (size.width <= 0 || size.height <= 0) return null
    return MarkPoint(
        (x / size.width.toFloat()).coerceIn(0f, 1f),
        (y / size.height.toFloat()).coerceIn(0f, 1f),
    )
}

private fun List<SnapshotStateList<MarkPoint>>.toMarkStrokes(): List<MarkStroke> = map(::MarkStroke)
