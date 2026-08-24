package com.majkeylab.scanit

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.min
import kotlin.math.roundToInt

private const val MIN_REDACTION_REGION_SIZE = 0.02f
private val DEFAULT_MANUAL_REDACTION_BOUNDS = NormalizedRect(0.25f, 0.35f, 0.75f, 0.65f)

private data class SafeSharePreviewState(
    val page: Int = -1,
    val file: File? = null,
    val bitmap: Bitmap? = null,
)

internal fun safeSharePreviewReady(
    previewPage: Int,
    previewFile: File?,
    bitmapAvailable: Boolean,
    currentPage: Int,
    currentFile: File?,
): Boolean =
    bitmapAvailable &&
        currentFile != null &&
        previewPage == currentPage &&
        previewFile == currentFile

internal fun safeShareCanApply(previewReady: Boolean, selectedCount: Int): Boolean =
    previewReady && selectedCount > 0

internal fun safeShareRegions(
    analysis: SafeShareAnalysis,
    scope: SafeShareScope,
    selectedPage: Int,
): List<RedactionRegion> {
    require(selectedPage in 0 until analysis.pageCount) { "Safe Share page is invalid" }
    return analysis.suggestions.mapIndexedNotNull { index, suggestion ->
        suggestion.takeIf {
            scope == SafeShareScope.AllPages || suggestion.page == selectedPage
        }?.let {
            RedactionRegion(
                id = "suggestion-${it.page}-$index",
                page = it.page,
                kind = it.kind,
                bounds = it.bounds,
                selected = true,
            )
        }
    }
}

internal fun addManualRedactionRegion(
    regions: List<RedactionRegion>,
    id: String,
    page: Int,
): List<RedactionRegion> {
    if (
        page !in 0 until MAX_SCAN_PAGES ||
            regions.any { it.id == id } ||
            regions.count { it.page == page } >= MAX_SAFE_SHARE_SUGGESTIONS_PER_PAGE
    ) {
        return regions
    }
    return regions +
        RedactionRegion(
            id = id,
            page = page,
            kind = SensitiveRegionKind.Manual,
            bounds = DEFAULT_MANUAL_REDACTION_BOUNDS,
            selected = true,
        )
}

internal fun moveRedactionRegion(
    regions: List<RedactionRegion>,
    id: String,
    deltaX: Float,
    deltaY: Float,
): List<RedactionRegion> {
    if (!deltaX.isFinite() || !deltaY.isFinite()) return regions
    return regions.updateManualRegion(id) { bounds ->
        val width = bounds.right - bounds.left
        val height = bounds.bottom - bounds.top
        val left = (bounds.left + deltaX).coerceIn(0f, 1f - width)
        val top = (bounds.top + deltaY).coerceIn(0f, 1f - height)
        NormalizedRect(
            left,
            top,
            (left + width).coerceAtMost(1f),
            (top + height).coerceAtMost(1f),
        )
    }
}

internal fun resizeRedactionRegion(
    regions: List<RedactionRegion>,
    id: String,
    deltaX: Float,
    deltaY: Float,
): List<RedactionRegion> {
    if (!deltaX.isFinite() || !deltaY.isFinite()) return regions
    return regions.updateManualRegion(id) { bounds ->
        NormalizedRect(
            bounds.left,
            bounds.top,
            (bounds.right + deltaX).coerceIn(
                minOf(bounds.left + MIN_REDACTION_REGION_SIZE, 1f),
                1f,
            ),
            (bounds.bottom + deltaY).coerceIn(
                minOf(bounds.top + MIN_REDACTION_REGION_SIZE, 1f),
                1f,
            ),
        )
    }
}

internal fun toggleRedactionRegion(
    regions: List<RedactionRegion>,
    id: String,
): List<RedactionRegion> =
    regions.map { region ->
        if (region.id == id) region.copy(selected = !region.selected) else region
    }

internal fun deleteManualRedactionRegion(
    regions: List<RedactionRegion>,
    id: String,
): List<RedactionRegion> =
    regions.filterNot { region -> region.id == id && region.kind == SensitiveRegionKind.Manual }

internal fun addManualRedactionStroke(
    review: SafeShareState.Reviewing,
    stroke: RedactionStroke,
): SafeShareState.Reviewing {
    if (review.mode != RedactionMode.Manual) return review
    validateRedactionStrokes(listOf(stroke))
    val current = review.strokesByPage[review.page].orEmpty()
    val totalStrokes = review.strokesByPage.values.sumOf(List<RedactionStroke>::size)
    val totalPoints = review.strokesByPage.values.flatten().sumOf { it.points.size }
    if (
        totalStrokes >= MAX_MARK_DRAWING_STROKES ||
            totalPoints + stroke.points.size > MAX_MARK_DRAWING_POINTS
    ) {
        return review
    }
    return review.copy(
        strokesByPage = review.strokesByPage + (review.page to current + stroke),
        undoneStrokesByPage = emptyMap(),
    )
}

internal fun undoManualRedactionStroke(
    review: SafeShareState.Reviewing,
): SafeShareState.Reviewing {
    if (review.mode != RedactionMode.Manual) return review
    val pageStrokes = review.strokesByPage[review.page].orEmpty()
    val stroke = pageStrokes.lastOrNull() ?: return review
    val strokesByPage = review.strokesByPage.toMutableMap()
    if (pageStrokes.size == 1) strokesByPage.remove(review.page)
    else strokesByPage[review.page] = pageStrokes.dropLast(1)
    return review.copy(
        strokesByPage = strokesByPage,
        undoneStrokesByPage =
            review.undoneStrokesByPage +
                (review.page to review.undoneStrokesByPage[review.page].orEmpty() + stroke),
    )
}

internal fun redoManualRedactionStroke(
    review: SafeShareState.Reviewing,
): SafeShareState.Reviewing {
    if (review.mode != RedactionMode.Manual) return review
    val undone = review.undoneStrokesByPage[review.page].orEmpty()
    val stroke = undone.lastOrNull() ?: return review
    val undoneByPage = review.undoneStrokesByPage.toMutableMap()
    if (undone.size == 1) undoneByPage.remove(review.page)
    else undoneByPage[review.page] = undone.dropLast(1)
    return review.copy(
        strokesByPage =
            review.strokesByPage +
                (review.page to review.strokesByPage[review.page].orEmpty() + stroke),
        undoneStrokesByPage = undoneByPage,
    )
}

internal fun clearManualRedactionPage(
    review: SafeShareState.Reviewing,
): SafeShareState.Reviewing {
    if (review.mode != RedactionMode.Manual || review.page !in review.strokesByPage) return review
    return review.copy(
        strokesByPage = review.strokesByPage - review.page,
        undoneStrokesByPage = emptyMap(),
    )
}

private inline fun List<RedactionRegion>.updateManualRegion(
    id: String,
    update: (NormalizedRect) -> NormalizedRect,
): List<RedactionRegion> =
    map { region ->
        if (region.id == id && region.kind == SensitiveRegionKind.Manual) {
            region.copy(bounds = update(region.bounds))
        } else {
            region
        }
    }

@Composable
internal fun SafeShareScreen(
    result: ScreenState.Result,
    onCancel: () -> Unit,
    onSelectPage: (Int) -> Unit,
    onAddArea: () -> Unit,
    onToggleRegion: (String) -> Unit,
    onMoveRegion: (String, Float, Float) -> Unit,
    onResizeRegion: (String, Float, Float) -> Unit,
    onDeleteRegion: (String) -> Unit,
    onAddStroke: (RedactionStroke) -> Unit,
    onBrushWidthChange: (Float) -> Unit,
    onToolChange: (RedactionTool) -> Unit,
    onUndoStroke: () -> Unit,
    onRedoStroke: () -> Unit,
    onClearPage: () -> Unit,
    onApply: () -> Unit,
    onLoadPreview: suspend (File, Int) -> Bitmap?,
) {
    when (val state = result.safeShareState) {
        SafeShareState.Analyzing ->
            SafeShareProgress(stringResource(R.string.safe_share_analyzing), onCancel)
        SafeShareState.Applying ->
            SafeShareProgress(stringResource(R.string.safe_share_applying), onCancel = null)
        is SafeShareState.Failed -> SafeShareFailure(state.message.resolve(), onCancel)
        is SafeShareState.Reviewing -> {
            val scope = result.safeShareScope ?: SafeShareScope.SelectedPage
            if (state.mode == RedactionMode.Manual) {
                ManualRedactionReview(
                    pages = result.scan.cached.pages,
                    state = state,
                    scope = scope,
                    onCancel = onCancel,
                    onSelectPage = onSelectPage,
                    onAddStroke = onAddStroke,
                    onBrushWidthChange = onBrushWidthChange,
                    onToolChange = onToolChange,
                    onUndoStroke = onUndoStroke,
                    onRedoStroke = onRedoStroke,
                    onClearPage = onClearPage,
                    onApply = onApply,
                    onLoadPreview = onLoadPreview,
                )
            } else {
                SafeShareReview(
                    pages = result.scan.cached.pages,
                    state = state,
                    scope = scope,
                    onCancel = onCancel,
                    onSelectPage = onSelectPage,
                    onAddArea = onAddArea,
                    onToggleRegion = onToggleRegion,
                    onMoveRegion = onMoveRegion,
                    onResizeRegion = onResizeRegion,
                    onDeleteRegion = onDeleteRegion,
                    onApply = onApply,
                    onLoadPreview = onLoadPreview,
                )
            }
        }
        null -> Unit
    }
}

@Composable
private fun SafeShareProgress(message: String, onCancel: (() -> Unit)?) {
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(20.dp))
            Text(message, style = MaterialTheme.typography.titleMedium)
            if (onCancel != null) {
                Spacer(Modifier.height(20.dp))
                TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}

@Composable
private fun SafeShareFailure(message: String, onCancel: () -> Unit) {
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(20.dp))
            OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        }
    }
}

@Composable
private fun SafeShareReview(
    pages: List<File>,
    state: SafeShareState.Reviewing,
    scope: SafeShareScope,
    onCancel: () -> Unit,
    onSelectPage: (Int) -> Unit,
    onAddArea: () -> Unit,
    onToggleRegion: (String) -> Unit,
    onMoveRegion: (String, Float, Float) -> Unit,
    onResizeRegion: (String, Float, Float) -> Unit,
    onDeleteRegion: (String) -> Unit,
    onApply: () -> Unit,
    onLoadPreview: suspend (File, Int) -> Bitmap?,
) {
    val page = pages.getOrNull(state.page)
    val preview by
        produceState(SafeSharePreviewState(), state.page, page) {
            value = SafeSharePreviewState(page = state.page, file = page)
            val bitmap =
                try {
                    page?.let { onLoadPreview(it, 2048) }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    null
                }
            value = SafeSharePreviewState(state.page, page, bitmap)
        }
    val pageRegions = state.regions.filter { it.page == state.page }
    val selectedCount = state.regions.count(RedactionRegion::selected)
    val previewReady =
        safeSharePreviewReady(
            previewPage = preview.page,
            previewFile = preview.file,
            bitmapAvailable = preview.bitmap != null,
            currentPage = state.page,
            currentFile = page,
        )
    val ownedBitmap = preview.bitmap.takeIf { previewReady }
    val canApply = safeShareCanApply(previewReady, selectedCount)
    val wide =
        with(LocalDensity.current) {
            LocalWindowInfo.current.containerSize.width.toDp() >= 720.dp
        }
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text(
                stringResource(R.string.safe_share_review_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(12.dp))
            if (wide) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SafeSharePreview(
                        bitmap = ownedBitmap,
                        regions = pageRegions,
                        onToggleRegion = onToggleRegion,
                        onMoveRegion = onMoveRegion,
                        onResizeRegion = onResizeRegion,
                        modifier = Modifier.weight(1f).fillMaxSize(),
                    )
                    SafeShareControls(
                        page = state.page,
                        pageCount = pages.size,
                        canChangePage = scope == SafeShareScope.AllPages,
                        regions = pageRegions,
                        selectedCount = selectedCount,
                        previewReady = previewReady,
                        canApply = canApply,
                        onCancel = onCancel,
                        onSelectPage = onSelectPage,
                        onAddArea = onAddArea,
                        onDeleteRegion = onDeleteRegion,
                        onApply = onApply,
                        modifier = Modifier.widthIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    )
                }
            } else {
                SafeSharePreview(
                    bitmap = ownedBitmap,
                    regions = pageRegions,
                    onToggleRegion = onToggleRegion,
                    onMoveRegion = onMoveRegion,
                    onResizeRegion = onResizeRegion,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                SafeShareControls(
                    page = state.page,
                    pageCount = pages.size,
                    canChangePage = scope == SafeShareScope.AllPages,
                    regions = pageRegions,
                    selectedCount = selectedCount,
                    previewReady = previewReady,
                    canApply = canApply,
                    onCancel = onCancel,
                    onSelectPage = onSelectPage,
                    onAddArea = onAddArea,
                    onDeleteRegion = onDeleteRegion,
                    onApply = onApply,
                    modifier =
                        Modifier.fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

@Composable
private fun SafeSharePreview(
    bitmap: Bitmap?,
    regions: List<RedactionRegion>,
    onToggleRegion: (String) -> Unit,
    onMoveRegion: (String, Float, Float) -> Unit,
    onResizeRegion: (String, Float, Float) -> Unit,
    modifier: Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier,
    ) {
        if (bitmap == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.preview_unavailable))
            }
            return@Surface
        }
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            val scale =
                min(
                    constraints.maxWidth.toFloat() / bitmap.width,
                    constraints.maxHeight.toFloat() / bitmap.height,
                )
            val previewWidthPx = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
            val previewHeightPx = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
            val density = LocalDensity.current
            val previewDescription = stringResource(R.string.safe_share_preview)
            Box(
                modifier =
                    Modifier.size(
                            with(density) { previewWidthPx.toDp() },
                            with(density) { previewHeightPx.toDp() },
                        )
                        .semantics { contentDescription = previewDescription },
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
                regions.forEachIndexed { index, region ->
                    SafeShareRegionOutline(
                        region = region,
                        position = index + 1,
                        previewWidthPx = previewWidthPx,
                        previewHeightPx = previewHeightPx,
                        onToggle = { onToggleRegion(region.id) },
                        onMove = { x, y -> onMoveRegion(region.id, x, y) },
                        onResize = { x, y -> onResizeRegion(region.id, x, y) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SafeShareRegionOutline(
    region: RedactionRegion,
    position: Int,
    previewWidthPx: Int,
    previewHeightPx: Int,
    onToggle: () -> Unit,
    onMove: (Float, Float) -> Unit,
    onResize: (Float, Float) -> Unit,
) {
    val density = LocalDensity.current
    val kind = safeShareKindLabel(region.kind)
    val state =
        stringResource(
            if (region.selected) {
                R.string.safe_share_region_selected
            } else {
                R.string.safe_share_region_not_selected
            },
        )
    val description =
        stringResource(R.string.safe_share_region_description, position, kind, state)
    val moveLeft = stringResource(R.string.safe_share_move_left)
    val moveRight = stringResource(R.string.safe_share_move_right)
    val moveUp = stringResource(R.string.safe_share_move_up)
    val moveDown = stringResource(R.string.safe_share_move_down)
    val grow = stringResource(R.string.safe_share_grow)
    val shrink = stringResource(R.string.safe_share_shrink)
    val manualActions =
        if (region.kind == SensitiveRegionKind.Manual) {
            listOf(
                CustomAccessibilityAction(moveLeft) {
                    onMove(-0.02f, 0f)
                    true
                },
                CustomAccessibilityAction(moveRight) {
                    onMove(0.02f, 0f)
                    true
                },
                CustomAccessibilityAction(moveUp) {
                    onMove(0f, -0.02f)
                    true
                },
                CustomAccessibilityAction(moveDown) {
                    onMove(0f, 0.02f)
                    true
                },
                CustomAccessibilityAction(grow) {
                    onResize(0.02f, 0.02f)
                    true
                },
                CustomAccessibilityAction(shrink) {
                    onResize(-0.02f, -0.02f)
                    true
                },
            )
        } else {
            emptyList()
        }
    val borderColor =
        if (region.selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val widthPx = ((region.bounds.right - region.bounds.left) * previewWidthPx).roundToInt()
    val heightPx = ((region.bounds.bottom - region.bounds.top) * previewHeightPx).roundToInt()
    val leftPx = (region.bounds.left * previewWidthPx).roundToInt()
    val topPx = (region.bounds.top * previewHeightPx).roundToInt()
    val touchTargetPx = with(density) { 48.dp.roundToPx() }
    val touchWidthPx = widthPx.coerceAtLeast(touchTargetPx)
    val touchHeightPx = heightPx.coerceAtLeast(touchTargetPx)
    val touchLeftPx =
        (leftPx - (touchWidthPx - widthPx) / 2)
            .coerceIn(0, (previewWidthPx - touchWidthPx).coerceAtLeast(0))
    val touchTopPx =
        (topPx - (touchHeightPx - heightPx) / 2)
            .coerceIn(0, (previewHeightPx - touchHeightPx).coerceAtLeast(0))
    Box(
        modifier =
            Modifier
                .offset { IntOffset(leftPx, topPx) }
                .size(
                    with(density) { widthPx.coerceAtLeast(1).toDp() },
                    with(density) { heightPx.coerceAtLeast(1).toDp() },
                )
                .alpha(if (region.selected) 1f else 0.55f)
                .border(2.dp, borderColor),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            Text(
                kind,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
        if (region.kind == SensitiveRegionKind.Manual) {
            Box(
                modifier = Modifier.align(Alignment.BottomEnd).size(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(color = borderColor, modifier = Modifier.size(12.dp)) {}
            }
        }
    }
    Box(
        modifier =
            Modifier
                .offset { IntOffset(touchLeftPx, touchTopPx) }
                .size(
                    with(density) { touchWidthPx.toDp() },
                    with(density) { touchHeightPx.toDp() },
                )
                .semantics {
                    contentDescription = description
                    stateDescription = state
                    customActions = manualActions
                }
                .toggleable(
                    value = region.selected,
                    role = Role.Checkbox,
                    onValueChange = { onToggle() },
                )
                .then(
                    if (region.kind == SensitiveRegionKind.Manual) {
                        Modifier.pointerInput(region.id, previewWidthPx, previewHeightPx) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                onMove(
                                    dragAmount.x / previewWidthPx,
                                    dragAmount.y / previewHeightPx,
                                )
                            }
                        }
                    } else {
                        Modifier
                    },
                ),
    ) {}
    if (region.kind != SensitiveRegionKind.Manual) return
    val resizeDescription =
        stringResource(R.string.safe_share_resize_handle, position)
    val resizeTargetPx = touchTargetPx
    val resizeLeftPx =
        (leftPx + widthPx - resizeTargetPx / 2)
            .coerceIn(0, (previewWidthPx - resizeTargetPx).coerceAtLeast(0))
    val resizeTopPx =
        (topPx + heightPx - resizeTargetPx / 2)
            .coerceIn(0, (previewHeightPx - resizeTargetPx).coerceAtLeast(0))
    Box(
        modifier =
            Modifier
                .offset { IntOffset(resizeLeftPx, resizeTopPx) }
                .size(with(density) { resizeTargetPx.toDp() })
                .semantics { contentDescription = resizeDescription }
                .pointerInput(region.id, previewWidthPx, previewHeightPx) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onResize(
                            dragAmount.x / previewWidthPx,
                            dragAmount.y / previewHeightPx,
                        )
                    }
                },
    ) {}
}

@Composable
private fun SafeShareControls(
    page: Int,
    pageCount: Int,
    canChangePage: Boolean,
    regions: List<RedactionRegion>,
    selectedCount: Int,
    previewReady: Boolean,
    canApply: Boolean,
    onCancel: () -> Unit,
    onSelectPage: (Int) -> Unit,
    onAddArea: () -> Unit,
    onDeleteRegion: (String) -> Unit,
    onApply: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(R.string.page_position, page + 1, pageCount),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                onClick = { onSelectPage(page - 1) },
                enabled = canChangePage && page > 0,
            ) {
                Text(stringResource(R.string.safe_share_previous_page))
            }
            TextButton(
                onClick = { onSelectPage(page + 1) },
                enabled = canChangePage && page + 1 < pageCount,
            ) {
                Text(stringResource(R.string.safe_share_next_page))
            }
        }
        if (regions.isEmpty()) {
            Text(
                stringResource(R.string.safe_share_no_regions),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(
                    regions.groupingBy(RedactionRegion::kind).eachCount().entries.toList(),
                    key = { it.key },
                ) { (kind, count) ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Text(
                            "${safeShareKindLabel(kind)} · $count",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        )
                    }
                }
            }
        }
        Text(
            stringResource(R.string.safe_share_selected_count, selectedCount),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(R.string.safe_share_drag_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        regions.forEachIndexed { index, region ->
            if (region.kind == SensitiveRegionKind.Manual) {
                TextButton(
                    onClick = { onDeleteRegion(region.id) },
                    enabled = previewReady,
                ) {
                    Text(stringResource(R.string.safe_share_delete_area, index + 1))
                }
            }
        }
        OutlinedButton(
            onClick = onAddArea,
            enabled = previewReady && regions.size < MAX_SAFE_SHARE_SUGGESTIONS_PER_PAGE,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.safe_share_add_area))
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
            ) {
                Text(stringResource(R.string.apply_protection))
            }
        }
    }
}

@Composable
private fun safeShareKindLabel(kind: SensitiveRegionKind): String =
    stringResource(
        when (kind) {
            SensitiveRegionKind.Email -> R.string.safe_share_kind_email
            SensitiveRegionKind.Phone -> R.string.safe_share_kind_phone
            SensitiveRegionKind.Url -> R.string.safe_share_kind_url
            SensitiveRegionKind.Iban -> R.string.safe_share_kind_iban
            SensitiveRegionKind.PaymentCard -> R.string.safe_share_kind_payment_card
            SensitiveRegionKind.Code -> R.string.safe_share_kind_code
            SensitiveRegionKind.Face -> R.string.safe_share_kind_face
            SensitiveRegionKind.Manual -> R.string.safe_share_kind_manual
        },
    )
