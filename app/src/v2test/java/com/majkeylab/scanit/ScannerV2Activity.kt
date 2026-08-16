package com.majkeylab.scanit

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class ScannerV2Activity : ComponentActivity() {
    private val viewModel: ScannerV2ViewModel by viewModels()
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var cameraPermissionGranted by mutableStateOf(false)
    private var cameraBindGeneration = 0L
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> cameraPermissionGranted = granted }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraPermissionGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        setContent {
            val state by viewModel.state.collectAsState()
            val surfaceRequest by viewModel.surfaceRequest.collectAsState()
            val stage = state.manifest?.state?.stage
            LaunchedEffect(cameraPermissionGranted, stage) {
                if (cameraPermissionGranted && stage == ScannerSessionStage.Capturing) {
                    bindCamera()
                } else {
                    unbindCamera()
                }
            }
            ScannerV2Theme {
                ScannerV2App(
                    state = state,
                    surfaceRequest = surfaceRequest,
                    cameraPermissionGranted = cameraPermissionGranted,
                    onRequestCameraPermission = {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    },
                    onCapture = ::capture,
                    onCancelCamera = ::cancelCamera,
                    onDiscardInterruptedCapture = viewModel::discardInterruptedCapture,
                    onConfirmCrop = viewModel::confirmCrop,
                    onEditCrop = viewModel::editSelectedCrop,
                    onCancelCropEdit = viewModel::cancelCropEditing,
                    onApplyAppearance = viewModel::applyAppearance,
                    onAddPage = viewModel::addPage,
                    onRetakePage = viewModel::retakeSelectedPage,
                    onDeletePage = viewModel::deleteSelectedPage,
                    onMovePage = viewModel::moveSelectedPage,
                    onFinish = viewModel::finish,
                    onSelectPage = viewModel::selectPage,
                )
            }
        }
    }

    override fun onDestroy() {
        unbindCamera()
        super.onDestroy()
    }

    private fun bindCamera() {
        val generation = ++cameraBindGeneration
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                if (generation != cameraBindGeneration || isDestroyed) return@addListener
                try {
                    val provider = future.get()
                    val selector = CameraSelector.DEFAULT_BACK_CAMERA
                    val resolution = ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .build()
                    val preview = Preview.Builder()
                        .setResolutionSelector(resolution)
                        .build()
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setResolutionSelector(resolution)
                        .setTargetRotation(display?.rotation ?: Surface.ROTATION_0)
                        .build()
                    viewModel.bindPreview(preview)
                    provider.unbindAll()
                    provider.bindToLifecycle(this, selector, preview, capture)
                    cameraProvider = provider
                    imageCapture = capture
                } catch (_: Exception) {
                    viewModel.cameraUnavailable()
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun unbindCamera() {
        cameraBindGeneration += 1
        imageCapture = null
        cameraProvider?.unbindAll()
        cameraProvider = null
        viewModel.clearPreviewSurface()
    }

    private fun capture() {
        val capture = imageCapture ?: run {
            viewModel.cameraUnavailable()
            return
        }
        lifecycleScope.launch {
            val ticket = try {
                viewModel.reserveCapture()
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                null
            } ?: return@launch
            capture.targetRotation = display?.rotation ?: Surface.ROTATION_0
            val output = ImageCapture.OutputFileOptions.Builder(ticket.destination).build()
            capture.takePicture(
                output,
                ContextCompat.getMainExecutor(this@ScannerV2Activity),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        viewModel.captureCompleted(ticket)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        viewModel.captureFailed(ticket)
                    }
                },
            )
        }
    }

    private fun cancelCamera() {
        unbindCamera()
        viewModel.cancelCamera()
    }
}

@Composable
private fun ScannerV2Theme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color.White, onPrimary = Color.Black)
    } else {
        lightColorScheme(primary = Color.Black, onPrimary = Color.White)
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
private fun ScannerV2App(
    state: ScannerV2UiState,
    surfaceRequest: SurfaceRequest?,
    cameraPermissionGranted: Boolean,
    onRequestCameraPermission: () -> Unit,
    onCapture: () -> Unit,
    onCancelCamera: () -> Unit,
    onDiscardInterruptedCapture: () -> Unit,
    onConfirmCrop: (PageQuad, Int) -> Unit,
    onEditCrop: () -> Unit,
    onCancelCropEdit: () -> Unit,
    onApplyAppearance: (ScannerV2Appearance) -> Unit,
    onAddPage: () -> Unit,
    onRetakePage: () -> Unit,
    onDeletePage: () -> Unit,
    onMovePage: (Int) -> Unit,
    onFinish: () -> Unit,
    onSelectPage: (Int) -> Unit,
) {
    Surface(Modifier.fillMaxSize()) {
        when (state.manifest?.state?.stage) {
            ScannerSessionStage.Capturing -> ScannerV2CameraScreen(
                state = state,
                surfaceRequest = surfaceRequest,
                permissionGranted = cameraPermissionGranted,
                onRequestPermission = onRequestCameraPermission,
                onCapture = onCapture,
                onCancel = onCancelCamera,
                onDiscardInterruptedCapture = onDiscardInterruptedCapture,
            )
            ScannerSessionStage.Reviewing -> ScannerV2ReviewScreen(
                state = state,
                onConfirmCrop = onConfirmCrop,
                onEditCrop = onEditCrop,
                onCancelCropEdit = onCancelCropEdit,
                onApplyAppearance = onApplyAppearance,
                onAddPage = onAddPage,
                onRetakePage = onRetakePage,
                onDeletePage = onDeletePage,
                onMovePage = onMovePage,
                onFinish = onFinish,
                onSelectPage = onSelectPage,
            )
            ScannerSessionStage.Finishing -> ScannerV2FinishedScreen(state)
            else -> ScannerV2LoadingOrError(state)
        }
    }
}

@Composable
private fun ScannerV2CameraScreen(
    state: ScannerV2UiState,
    surfaceRequest: SurfaceRequest?,
    permissionGranted: Boolean,
    onRequestPermission: () -> Unit,
    onCapture: () -> Unit,
    onCancel: () -> Unit,
    onDiscardInterruptedCapture: () -> Unit,
) {
    BackHandler(onBack = onCancel)
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.v2_cancel)) }
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.v2_capture_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.weight(1f))
            Text("${state.manifest?.pages?.size ?: 0}/$MAX_SCAN_PAGES")
        }
        Box(
            Modifier.fillMaxWidth().aspectRatio(3f / 4f)
                .background(Color.Black, RoundedCornerShape(20.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                !permissionGranted -> Button(onClick = onRequestPermission) {
                    Text(stringResource(R.string.v2_allow_camera))
                }
                surfaceRequest != null -> CameraXViewfinder(
                    surfaceRequest = surfaceRequest,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> CircularProgressIndicator(color = Color.White)
            }
        }
        state.issue?.let { ScannerV2IssueText(it) }
        if (state.manifest?.pendingCaptureId != null && !state.busy) {
            OutlinedButton(
                onClick = onDiscardInterruptedCapture,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.v2_discard_interrupted_capture))
            }
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Button(
                onClick = onCapture,
                enabled = permissionGranted && surfaceRequest != null && !state.busy,
                modifier = Modifier.size(88.dp),
                shape = CircleShape,
            ) {
                Text(stringResource(R.string.v2_capture_button))
            }
        }
    }
}

@Composable
private fun ScannerV2ReviewScreen(
    state: ScannerV2UiState,
    onConfirmCrop: (PageQuad, Int) -> Unit,
    onEditCrop: () -> Unit,
    onCancelCropEdit: () -> Unit,
    onApplyAppearance: (ScannerV2Appearance) -> Unit,
    onAddPage: () -> Unit,
    onRetakePage: () -> Unit,
    onDeletePage: () -> Unit,
    onMovePage: (Int) -> Unit,
    onFinish: () -> Unit,
    onSelectPage: (Int) -> Unit,
) {
    val manifest = requireNotNull(state.manifest)
    val selected = requireNotNull(manifest.state.selectedIndex)
    val record = manifest.pages[selected]
    var crop by remember(record.pageId, record.renderedFingerprint) { mutableStateOf(record.crop) }
    var rotation by remember(record.pageId, record.renderedFingerprint) {
        mutableIntStateOf(record.rotationQuarterTurns)
    }
    val rendered = record.renderedFingerprint != null && !state.cropEditing
    if (state.cropEditing) BackHandler(onBack = onCancelCropEdit)
    Scaffold(
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        bottomBar = {
            Surface(shadowElevation = 4.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (rendered) {
                        OutlinedButton(
                            onClick = onAddPage,
                            enabled = !state.busy && manifest.pages.size < MAX_SCAN_PAGES,
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.v2_add_page)) }
                        Button(
                            onClick = onFinish,
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.v2_finish)) }
                    } else {
                        OutlinedButton(
                            onClick = { rotation = (rotation + 1) % 4 },
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.v2_rotate)) }
                        Button(
                            onClick = { onConfirmCrop(crop, rotation) },
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.v2_keep_page)) }
                    }
                }
            }
        },
    ) { contentPadding ->
        Column(
            Modifier.fillMaxSize().padding(contentPadding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (rendered) stringResource(R.string.v2_review_title) else stringResource(R.string.v2_crop_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                stringResource(R.string.v2_page_count, selected + 1, manifest.pages.size),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(
                Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 520.dp),
                contentAlignment = Alignment.Center,
            ) {
                val preview = state.preview
                if (preview == null) {
                    CircularProgressIndicator()
                } else {
                    ScannerV2CropPreview(
                        bitmap = preview,
                        crop = crop,
                        editable = !rendered && !state.busy,
                        onCropChange = { crop = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (state.busy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                    }
                }
            }
            state.issue?.let { ScannerV2IssueText(it) }
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(end = 56.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(manifest.pages, key = { _, page -> page.pageId.value }) { index, page ->
                    val pageDescription = stringResource(
                        R.string.v2_page_thumbnail,
                        index + 1,
                        manifest.pages.size,
                    )
                    val selectedPage = index == selected
                    Surface(
                        onClick = { onSelectPage(index) },
                        enabled = !state.busy && rendered,
                        modifier = Modifier.width(92.dp).height(116.dp)
                            .clearAndSetSemantics { contentDescription = pageDescription },
                        shape = RoundedCornerShape(12.dp),
                        color = if (selectedPage) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        border = BorderStroke(
                            width = if (selectedPage) 2.dp else 1.dp,
                            color = if (selectedPage) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                        ),
                    ) {
                        Column(
                            Modifier.fillMaxSize().padding(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            val thumbnail = state.pageThumbnails[page.pageId]?.bitmap
                            if (thumbnail == null) {
                                Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(Modifier.size(24.dp))
                                }
                            } else {
                                Image(
                                    bitmap = thumbnail.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxWidth().height(80.dp)
                                        .background(Color.Black, RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Fit,
                                )
                            }
                            Text("${index + 1}", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            if (rendered) {
            ScannerV2AppearanceEditor(
                appearance = record.appearance,
                previews = state.filterPreviews,
                busy = state.busy,
                onApply = onApplyAppearance,
            )
            OutlinedButton(
                onClick = onEditCrop,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.v2_adjust_corners)) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onRetakePage,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.v2_retake)) }
                OutlinedButton(
                    onClick = onDeletePage,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.v2_delete)) }
            }
            if (manifest.pages.size > 1) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onMovePage(-1) },
                        enabled = selected > 0,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.v2_move_left)) }
                    OutlinedButton(
                        onClick = { onMovePage(1) },
                        enabled = selected < manifest.pages.lastIndex,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.v2_move_right)) }
                }
            }
            }
        }
    }
}

@Composable
private fun ScannerV2AppearanceEditor(
    appearance: ScannerV2Appearance,
    previews: Map<ScannerV2Filter, android.graphics.Bitmap>,
    busy: Boolean,
    onApply: (ScannerV2Appearance) -> Unit,
) {
    var selectedFilter by remember(appearance) { mutableStateOf(appearance.filter) }
    var intensity by remember(appearance) { mutableIntStateOf(appearance.intensity) }
    var shadows by remember(appearance) { mutableIntStateOf(appearance.shadows) }
    Text(stringResource(R.string.v2_filters), style = MaterialTheme.typography.titleMedium)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(ScannerV2Filter.entries.size) { index ->
            val filter = ScannerV2Filter.entries[index]
            val filterLabel = stringResource(scannerV2FilterLabel(filter))
            FilterChip(
                selected = selectedFilter == filter,
                enabled = !busy,
                modifier = Modifier.width(128.dp).heightIn(min = 120.dp)
                    .semantics { contentDescription = filterLabel },
                onClick = {
                    val preset = ScannerV2Appearance.defaultFor(filter)
                    selectedFilter = filter
                    intensity = preset.intensity
                    shadows = preset.shadows
                    if (preset != appearance) onApply(preset)
                },
                label = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        previews[filter]?.let { preview ->
                            Image(
                                bitmap = preview.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth().height(72.dp)
                                    .background(Color.Black, RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop,
                            )
                        }
                        Text(
                            text = filterLabel,
                            maxLines = 1,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
            )
        }
    }
    if (selectedFilter != ScannerV2Filter.Original) {
        Text(stringResource(R.string.v2_filter_intensity, intensity))
        Slider(
            value = intensity.toFloat(),
            onValueChange = { intensity = it.toInt() },
            valueRange = 0f..100f,
            enabled = !busy,
        )
        Text(stringResource(R.string.v2_filter_shadows, shadows))
        Slider(
            value = shadows.toFloat(),
            onValueChange = { shadows = it.toInt() },
            valueRange = 0f..100f,
            enabled = !busy,
        )
        val edited = ScannerV2Appearance(selectedFilter, intensity, shadows)
        Button(
            onClick = { onApply(edited) },
            enabled = !busy && edited != appearance,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.v2_apply_filter))
        }
    }
}

private fun scannerV2FilterLabel(filter: ScannerV2Filter): Int = when (filter) {
    ScannerV2Filter.Original -> R.string.v2_filter_original
    ScannerV2Filter.Natural -> R.string.v2_filter_natural
    ScannerV2Filter.Color -> R.string.v2_filter_color
    ScannerV2Filter.LightText -> R.string.v2_filter_light_text
    ScannerV2Filter.Grayscale -> R.string.v2_filter_grayscale
    ScannerV2Filter.BlackWhite -> R.string.v2_filter_black_white
    ScannerV2Filter.Whiteboard -> R.string.v2_filter_whiteboard
}

@Composable
private fun ScannerV2CropPreview(
    bitmap: android.graphics.Bitmap,
    crop: PageQuad,
    editable: Boolean,
    onCropChange: (PageQuad) -> Unit,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val ratio = bitmap.width.toFloat() / bitmap.height
        val width = minOf(maxWidth, maxHeight * ratio)
        val height = width / ratio
        var activeCorner by remember { mutableStateOf<PageCorner?>(null) }
        val currentCrop by rememberUpdatedState(crop)
        Box(
            Modifier.width(width).height(height)
                .background(Color.Black)
                .then(
                    if (editable) Modifier.pointerInput(bitmap, editable) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                activeCorner = nearestScannerV2Corner(
                                    offset,
                                    size.width,
                                    size.height,
                                    currentCrop,
                                    56.dp.toPx(),
                                )
                            },
                            onDragEnd = { activeCorner = null },
                            onDragCancel = { activeCorner = null },
                            onDrag = { change, drag ->
                                change.consume()
                                activeCorner?.let { corner ->
                                    onCropChange(
                                        currentCrop.nudge(
                                            corner,
                                            (drag.x.toDouble() / size.width).coerceIn(-.1, .1),
                                            (drag.y.toDouble() / size.height).coerceIn(-.1, .1),
                                        ),
                                    )
                                }
                            },
                        )
                    } else Modifier,
                ),
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.v2_page_preview),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
            )
            if (editable) ScannerV2CropOverlay(crop)
        }
    }
}

@Composable
private fun ScannerV2CropOverlay(crop: PageQuad) {
    Canvas(Modifier.fillMaxSize()) {
        val points = listOf(crop.topLeft, crop.topRight, crop.bottomRight, crop.bottomLeft).map {
            Offset((it.x * size.width).toFloat(), (it.y * size.height).toFloat())
        }
        val path = Path().apply {
            moveTo(points[0].x, points[0].y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
            close()
        }
        drawPath(path, Color.White, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        points.forEach { point ->
            drawCircle(Color.Black.copy(alpha = .65f), radius = 14.dp.toPx(), center = point)
            drawCircle(Color.White, radius = 9.dp.toPx(), center = point)
        }
    }
}

private fun nearestScannerV2Corner(
    offset: Offset,
    width: Int,
    height: Int,
    crop: PageQuad,
    touchRadius: Float,
): PageCorner? {
    val points = listOf(
        PageCorner.TopLeft to crop.topLeft,
        PageCorner.TopRight to crop.topRight,
        PageCorner.BottomRight to crop.bottomRight,
        PageCorner.BottomLeft to crop.bottomLeft,
    )
    return points.minByOrNull { (_, point) ->
        val dx = offset.x - point.x * width
        val dy = offset.y - point.y * height
        dx * dx + dy * dy
    }?.takeIf { (_, point) ->
        val dx = offset.x - point.x * width
        val dy = offset.y - point.y * height
        dx * dx + dy * dy <= touchRadius * touchRadius
    }?.first
}

@Composable
private fun ScannerV2FinishedScreen(state: ScannerV2UiState) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.v2_ready_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.v2_ready_pages, state.manifest?.pages?.size ?: 0))
    }
}

@Composable
private fun ScannerV2LoadingOrError(state: ScannerV2UiState) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        if (state.issue == null) CircularProgressIndicator() else ScannerV2IssueText(state.issue)
    }
}

@Composable
private fun ScannerV2IssueText(issue: ScannerV2Issue) {
    val message = when (issue) {
        ScannerV2Issue.SessionUnavailable -> R.string.v2_error_session
        ScannerV2Issue.CaptureFailed -> R.string.v2_error_capture
        ScannerV2Issue.CaptureRecoveryRequired -> R.string.v2_error_capture_recovery
        ScannerV2Issue.RenderFailed -> R.string.v2_error_render
        ScannerV2Issue.CameraUnavailable -> R.string.v2_error_camera
    }
    Text(stringResource(message), color = MaterialTheme.colorScheme.error)
}
