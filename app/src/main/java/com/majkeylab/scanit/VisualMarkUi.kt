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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
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
    val selectedBitmap =
        ownedVisualMarkBitmap(editor.selectedTemplateId, MARK_PREVIEW_SIZE, onLoadTemplate)
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(onImport)
        }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = { VisualMarkTopBar(!editor.applying, onClose) },
        bottomBar = {
            Surface {
                Column {
                    editor.message?.takeIf { editor.drawingStrokes == null }?.let { message ->
                        Text(message.resolve(), color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp).semantics {
                                liveRegion = LiveRegionMode.Polite
                            })
                    }
                    if (editor.busy) {
                        Row(Modifier.padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.visual_mark_working),
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        }
                    }
                    if (selectedBitmap != null && !editor.busy) {
                        Text(
                            stringResource(R.string.drag_visual_mark_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    Button(
                        onClick = onApply,
                        enabled = selectedBitmap != null && !editor.busy,
                        modifier = Modifier.fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(horizontal = 16.dp, vertical = 8.dp).heightIn(min = 48.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_check), null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.apply_visual_mark, source.pageIndex + 1))
                    }
                }
            }
        },
    ) { padding ->
        val controls: @Composable (Modifier) -> Unit = { modifier ->
            Column(
                modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.saved_visual_marks),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    VisualMarkCreationActions(
                        enabled = !editor.busy && editor.templateIds.size < MAX_MARK_TEMPLATES,
                        onDraw = onBeginDrawing,
                        onImport = { importLauncher.launch(arrayOf("image/*")) },
                        onScan = onScan, compact = true,
                    )
                    if (editor.selectedTemplateId != null) {
                        IconButton(onClick = { showDeleteConfirmation = true }, enabled = !editor.busy) {
                            Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.delete_visual_mark))
                        }
                    }
                }
                if (editor.templateIds.isEmpty()) {
                    Text(
                        stringResource(R.string.no_saved_visual_marks),
                        style = MaterialTheme.typography.bodyMedium,
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
                if (editor.templateIds.size >= MAX_MARK_TEMPLATES) {
                    Text(stringResource(R.string.visual_mark_limit, MAX_MARK_TEMPLATES),
                        style = MaterialTheme.typography.bodySmall)
                }
                if (selectedBitmap != null) {
                    TextButton(onClick = { onPlacementChange(MarkPlacement()) }, enabled = !editor.busy) {
                        ActionButtonContent(R.drawable.ic_restore, R.string.reset_visual_mark)
                    }
                }
                Text(
                    stringResource(R.string.visual_mark_disclaimer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val controlsMaxHeight = maxHeight * 0.55f
            val preview: @Composable (Modifier) -> Unit = { modifier ->
                VisualMarkPreview(
                    page = result.thumbnail, mark = selectedBitmap, placement = editor.placement,
                    enabled = selectedBitmap != null && !editor.busy,
                    onPlacementChange = onPlacementChange, modifier = modifier,
                )
            }
            val toolsWidth = (maxWidth * 0.45f).coerceIn(240.dp, 320.dp)
            if (maxWidth >= 480.dp && maxWidth > maxHeight) {
                Row(Modifier.fillMaxSize()) {
                    preview(Modifier.weight(1f).fillMaxSize().padding(8.dp))
                    controls(Modifier.width(toolsWidth).fillMaxSize())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    preview(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
                    controls(Modifier.fillMaxWidth().heightIn(max = controlsMaxHeight))
                }
            }
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
                    ActionButtonContent(R.drawable.ic_delete, R.string.delete_visual_mark_confirm)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    ActionButtonContent(R.drawable.ic_close, R.string.cancel)
                }
            },
        )
    }
}

@Composable
internal fun VisualMarkTopBar(
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
internal fun VisualMarkCreationActions(
    enabled: Boolean,
    onDraw: () -> Unit,
    onImport: () -> Unit,
    onScan: () -> Unit,
    compact: Boolean = false,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        if (compact) {
            IconButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.ic_add), stringResource(R.string.add_visual_mark))
            }
        } else OutlinedButton(
            onClick = { expanded = true }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                if (enabled) {
                    if (expanded) collapse { expanded = false; true }
                    else expand { expanded = true; true }
                }
            },
        ) {
            ActionButtonContent(R.drawable.ic_signature, R.string.add_visual_mark)
            Spacer(Modifier.width(8.dp))
            Icon(painterResource(R.drawable.ic_expand_more), null, Modifier.size(20.dp))
        }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            listOf(
                Triple(R.drawable.ic_camera, R.string.scan_visual_mark, onScan),
                Triple(R.drawable.ic_image, R.string.import_visual_mark, onImport),
                Triple(R.drawable.ic_signature, R.string.draw_visual_mark, onDraw),
            ).forEach { (icon, label, action) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    leadingIcon = { Icon(painterResource(icon), null) },
                    onClick = { expanded = false; action() },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    }
}

@Composable
internal fun VisualMarkTemplateRow(
    ids: List<String>,
    selectedId: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onLoad: suspend (String, Int) -> Bitmap?,
) {
    LazyRow(
        modifier = Modifier.selectableGroup(),
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
                    modifier = Modifier.width(84.dp).height(64.dp).markTransparencyBackground().padding(6.dp),
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
internal fun ownedVisualMarkBitmap(
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
    modifier: Modifier = Modifier,
) {
    val paint = remember { AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG or AndroidPaint.FILTER_BITMAP_FLAG) }
    val outlinePaint = remember {
        AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            style = AndroidPaint.Style.STROKE
        }
    }
    val description = stringResource(R.string.visual_mark_preview)
    val placementDescription = stringResource(R.string.visual_mark_placement_summary,
        (placement.widthFraction * 100).roundToInt(), placement.rotationDegrees.roundToInt(),
        (placement.centerX * 100).roundToInt(), (placement.centerY * 100).roundToInt())
    val currentPlacement by rememberUpdatedState(placement)
    val currentOnPlacementChange by rememberUpdatedState(onPlacementChange)
    val currentPage by rememberUpdatedState(page)
    val currentMark by rememberUpdatedState(mark)
    val currentEnabled by rememberUpdatedState(enabled)
    fun adjust(panX: Float = 0f, panY: Float = 0f, sizeDelta: Float = 0f, rotation: Float = 0f): Boolean {
        val pageBitmap = currentPage ?: return false
        val markBitmap = currentMark ?: return false
        if (!currentEnabled) return false
        val current = currentPlacement
        currentOnPlacementChange(transformMarkPlacement(
            pageWidth = pageBitmap.width.toFloat(), pageHeight = pageBitmap.height.toFloat(),
            markWidth = markBitmap.width, markHeight = markBitmap.height,
            placement = current, panX = panX * pageBitmap.width, panY = panY * pageBitmap.height,
            zoom = (current.widthFraction + sizeDelta)
                .coerceIn(MIN_MARK_WIDTH_FRACTION, MAX_MARK_WIDTH_FRACTION) / current.widthFraction,
            rotationDegrees = rotation,
        ))
        return true
    }
    val accessibleActions = listOf(
        CustomAccessibilityAction(stringResource(R.string.safe_share_move_left)) { adjust(panX = -0.02f) },
        CustomAccessibilityAction(stringResource(R.string.safe_share_move_right)) { adjust(panX = 0.02f) },
        CustomAccessibilityAction(stringResource(R.string.safe_share_move_up)) { adjust(panY = -0.02f) },
        CustomAccessibilityAction(stringResource(R.string.safe_share_move_down)) { adjust(panY = 0.02f) },
        CustomAccessibilityAction(stringResource(R.string.visual_mark_enlarge)) { adjust(sizeDelta = 0.02f) },
        CustomAccessibilityAction(stringResource(R.string.visual_mark_reduce)) { adjust(sizeDelta = -0.02f) },
        CustomAccessibilityAction(stringResource(R.string.visual_mark_rotate_left)) { adjust(rotation = -5f) },
        CustomAccessibilityAction(stringResource(R.string.visual_mark_rotate_right)) { adjust(rotation = 5f) },
    )
    Canvas(
        modifier =
            modifier
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .pointerInput(page, mark, enabled) {
                    val pageBitmap = page ?: return@pointerInput
                    val markBitmap = mark ?: return@pointerInput
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var gesturePlacement = currentPlacement
                        do {
                            val event = awaitPointerEvent()
                            val pan = event.calculatePan()
                            val zoom = event.calculateZoom()
                            val rotation = event.calculateRotation()
                            if (pan != Offset.Zero || zoom != 1f || rotation != 0f) {
                                if (size.width <= 0 || size.height <= 0) return@awaitEachGesture
                                val scale =
                                    min(
                                        size.width.toFloat() / pageBitmap.width,
                                        size.height.toFloat() / pageBitmap.height,
                                    )
                                gesturePlacement =
                                    transformMarkPlacement(
                                        pageWidth = pageBitmap.width * scale,
                                        pageHeight = pageBitmap.height * scale,
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
                .semantics {
                    contentDescription = description
                    if (mark != null) stateDescription = placementDescription
                    if (enabled && page != null && mark != null) customActions = accessibleActions
                },
    ) {
        val pageBitmap = page ?: return@Canvas
        if (size.width <= 0f || size.height <= 0f) return@Canvas
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
                if (enabled) {
                    // A two-tone outline stays visible on the document in either app theme.
                    for (pass in 0..1) {
                        outlinePaint.color = if (pass == 0) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                        outlinePaint.strokeWidth = (if (pass == 0) 3.dp else 1.dp).toPx()
                        canvas.drawRect(
                            left + rect.left, top + rect.top, left + rect.right, top + rect.bottom,
                            outlinePaint,
                        )
                    }
                }
            } finally {
                canvas.restoreToCount(saveCount)
            }
        }
    }
}

internal fun Modifier.markTransparencyBackground(): Modifier = drawBehind {
    val tile = 8.dp.toPx()
    drawRect(Color.White)
    for (row in 0 until kotlin.math.ceil(size.height / tile).toInt()) {
        for (column in 0 until kotlin.math.ceil(size.width / tile).toInt()) {
            if ((row + column) % 2 == 0) {
                drawRect(Color(0xFFE0E0E0), Offset(column * tile, row * tile),
                    androidx.compose.ui.geometry.Size(min(tile, size.width - column * tile),
                        min(tile, size.height - row * tile)))
            }
        }
    }
}

@Composable
internal fun DrawVisualMarkDialog(
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
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
        modifier = Modifier.padding(horizontal = 16.dp).widthIn(max = 640.dp).fillMaxWidth(),
        title = { Text(stringResource(R.string.draw_visual_mark_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = dialogContentMaxHeight()).verticalScroll(rememberScrollState()),
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
                        Modifier.fillMaxWidth()
                            .aspectRatio(DRAWN_MARK_WIDTH.toFloat() / DRAWN_MARK_HEIGHT)
                            .background(Color.White)
                            .border(1.dp, MaterialTheme.colorScheme.outline)
                            .semantics { contentDescription = description }
                            .focusable()
                            .onSizeChanged { drawingSize = it }
                            .pointerInput(drawingSize) {
                                var active: SnapshotStateList<MarkPoint>? = null
                                fun finishDrawingStroke(cancelled: Boolean) {
                                    val stroke = active
                                    if (cancelled && stroke != null) {
                                        val index = strokes.indexOfFirst { it === stroke }
                                        if (index >= 0) {
                                            strokes.removeAt(index)
                                            pointCount -= stroke.size
                                        }
                                    }
                                    active = null
                                    onDraftChange(strokes.toMarkStrokes())
                                }
                                try {
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
                                            finishDrawingStroke(cancelled = false)
                                        },
                                        onDragCancel = {
                                            finishDrawingStroke(cancelled = true)
                                        },
                                    )
                                } finally {
                                    if (active != null) finishDrawingStroke(cancelled = true)
                                }
                            },
                ) {
                    val strokeWidth = DRAWN_MARK_STROKE_WIDTH * size.width / DRAWN_MARK_WIDTH
                    strokes.forEach { stroke ->
                        val points = stroke.map { Offset(it.x * size.width, it.y * size.height) }
                        if (points.size == 1) {
                            drawCircle(Color.Black, strokeWidth / 2f, points.single())
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
                                style = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
                            )
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            strokes.removeLastOrNull()?.let { pointCount -= it.size }
                            onDraftChange(strokes.toMarkStrokes())
                        },
                        enabled = strokes.isNotEmpty(),
                    ) {
                        ActionButtonContent(R.drawable.ic_restore, R.string.undo_drawing)
                    }
                    TextButton(
                        onClick = {
                            strokes.clear()
                            pointCount = 0
                            onDraftChange(emptyList())
                        },
                        enabled = strokes.isNotEmpty(),
                    ) {
                        ActionButtonContent(R.drawable.ic_delete, R.string.clear_drawing)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(strokes.toMarkStrokes()) },
                enabled = strokes.isNotEmpty(),
            ) {
                ActionButtonContent(R.drawable.ic_save, R.string.save_visual_mark)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                ActionButtonContent(R.drawable.ic_close, R.string.cancel)
            }
        },
    )
}

private fun Offset.normalized(size: IntSize): MarkPoint? {
    if (size.width <= 0 || size.height <= 0) return null
    return MarkPoint(
        (x / size.width.toFloat()).coerceIn(0f, 1f),
        (y / size.height.toFloat()).coerceIn(0f, 1f),
    )
}

private fun List<SnapshotStateList<MarkPoint>>.toMarkStrokes(): List<MarkStroke> =
    map { MarkStroke(it.toList()) }
