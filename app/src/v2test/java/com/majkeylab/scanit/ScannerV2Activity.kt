package com.majkeylab.scanit

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Rational
import android.util.Size
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionFilter
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val V2_LAUNCH_DIRECTIVE_CONSUMED_KEY = "v2_launch_directive_consumed"
private const val V2_VIEW_MODEL_TOKEN_KEY = "v2_view_model_token"
private const val V2_ANALYSIS_INTERVAL_MS = 100L
private const val V2_ANALYSIS_GUIDE_HOLD_MS = 250L
private val V2_ANALYSIS_SIZE = Size(320, 240)

class ScannerV2Activity : ComponentActivity() {
    private val viewModel: ScannerV2ViewModel by viewModels()
    private val resultViewModel: ScanViewModel by viewModels()
    private val customFilterStore by lazy(LazyThreadSafetyMode.NONE) {
        ScannerV2CustomFilterStore(applicationContext)
    }
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private val cameraAnalysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val autoCaptureGate = ScannerV2AutoCaptureGate()
    private var liveDocumentQuad by mutableStateOf<PageQuad?>(null)
    private var liveDocumentReady by mutableStateOf(false)
    private var analysisGuide: PageQuad? = null
    private var captureRequestInFlight = false
    private var cameraPermissionGranted by mutableStateOf(false)
    @Volatile
    private var cameraBindGeneration = 0L
    @Volatile
    private var lastAnalysisStartedAt = 0L
    @Volatile
    private var lastAnalysisDetectedAt = 0L
    @Volatile
    private var analysisGuideVisible = false
    private var freshLaunchRequested = false
    private var launchDirectiveConsumed = false
    private var launchDirectiveClaimed = false
    private var importRequestedSessionId: String? = null
    private var resultOpened = false
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> cameraPermissionGranted = granted }
    private val imageImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importImage) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        freshLaunchRequested = scannerV2StartsFresh(
            savedProcessToken = savedInstanceState?.getString(V2_VIEW_MODEL_TOKEN_KEY),
            currentProcessToken = viewModel.instanceToken,
            action = intent.action,
        )
        launchDirectiveConsumed =
            !freshLaunchRequested &&
                savedInstanceState?.getBoolean(V2_LAUNCH_DIRECTIVE_CONSUMED_KEY) == true
        cameraPermissionGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        setContent {
            var customFilters by remember {
                mutableStateOf<List<ScannerV2CustomFilter>>(emptyList())
            }
            var customFiltersLoaded by remember { mutableStateOf(false) }
            var customFiltersUnreadable by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                when (val loaded = withContext(Dispatchers.IO) { customFilterStore.load() }) {
                    is ScannerV2CustomFilterLoadResult.Loaded -> {
                        customFilters = loaded.presets
                        customFiltersUnreadable = false
                    }
                    ScannerV2CustomFilterLoadResult.Unreadable -> {
                        customFilters = emptyList()
                        customFiltersUnreadable = true
                    }
                }
                customFiltersLoaded = true
            }
            val state by viewModel.state.collectAsState()
            val resultState by resultViewModel.state.collectAsState()
            val resultNavigationReady by resultViewModel.navigationReady.collectAsState()
            val resultProcessingActive by
                resultViewModel.initialScanProcessingActive.collectAsState()
            val surfaceRequest by viewModel.surfaceRequest.collectAsState()
            val manifest = state.manifest
            val stage = state.manifest?.state?.stage
            LaunchedEffect(
                manifest?.sessionId,
                manifest?.state?.stage,
                manifest?.resultCacheId,
                resultState,
                resultNavigationReady,
                resultProcessingActive,
            ) {
                val current = manifest ?: return@LaunchedEffect
                if (!launchDirectiveConsumed && !launchDirectiveClaimed) {
                    try {
                        when {
                            intent.action == ACTION_SCANNER_V2_EDIT -> {
                                val editSource = scannerV2EditSource(intent)
                                val active = (resultState as? ScreenState.Result)?.scan?.cached
                                when (
                                    scannerV2EditLaunchAction(
                                        navigationReady = resultNavigationReady,
                                        stage = current.state.stage,
                                        resultCacheId = current.resultCacheId,
                                        manifestEditSource = current.editSource,
                                        requestedEditSource = editSource,
                                        activeCacheId = active?.baseName,
                                        activeEntryId = active?.entryId,
                                    )
                                ) {
                                    ScannerV2EditLaunchAction.Wait -> return@LaunchedEffect
                                    ScannerV2EditLaunchAction.Resume -> {
                                        launchDirectiveClaimed = true
                                        checkNotNull(editSource)
                                        check(
                                            viewModel.resumeFinishedReview(
                                                expectedCacheId = current.resultCacheId,
                                                failed = false,
                                                editSource = editSource,
                                            ),
                                        ) { "Scanner edit authority changed" }
                                        launchDirectiveConsumed = true
                                        return@LaunchedEffect
                                    }
                                    ScannerV2EditLaunchAction.AlreadyApplied -> {
                                        launchDirectiveClaimed = true
                                        launchDirectiveConsumed = true
                                        return@LaunchedEffect
                                    }
                                    ScannerV2EditLaunchAction.Reject -> {
                                        launchDirectiveClaimed = true
                                        throw IllegalStateException("Scanner edit source is stale")
                                    }
                                }
                            }
                            freshLaunchRequested -> {
                                launchDirectiveClaimed = true
                                check(viewModel.startNewSessionForFreshLaunch()) {
                                    "Scanner fresh launch authority changed"
                                }
                                launchDirectiveConsumed = true
                                return@LaunchedEffect
                            }
                            current.state.stage == ScannerSessionStage.Finishing -> {
                                viewModel.startNewSessionForFreshLaunch()
                                launchDirectiveConsumed = true
                                return@LaunchedEffect
                            }
                            else -> launchDirectiveConsumed = true
                        }
                    } catch (failure: Exception) {
                        if (failure is kotlinx.coroutines.CancellationException) throw failure
                        viewModel.sessionUnavailable()
                        return@LaunchedEffect
                    }
                }
                if (current.state.stage != ScannerSessionStage.Finishing) {
                    return@LaunchedEffect
                }
                val resultStatus =
                    scannerV2ResultStatus(
                        navigationReady = resultNavigationReady,
                        processingState = resultState is ScreenState.Processing,
                        processingActive = resultProcessingActive,
                        failed = resultState is ScreenState.Failure,
                        matchingResult =
                            scannerV2ResultMatches(
                                expectedCacheId = current.resultCacheId,
                                resultCacheId =
                                    (resultState as? ScreenState.Result)
                                        ?.scan
                                        ?.cached
                                        ?.baseName,
                            ),
                    )
                try {
                    when (
                        scannerV2BridgeAction(
                            resultCacheId = current.resultCacheId,
                            resultStatus = resultStatus,
                            importRequested = importRequestedSessionId == current.sessionId,
                        )
                    ) {
                        ScannerV2BridgeAction.Wait -> Unit
                        ScannerV2BridgeAction.StartImport -> {
                            val pageUris = viewModel.finishedPageUris()
                            importRequestedSessionId = current.sessionId
                            val accepted = resultViewModel.processScan(
                                pageUris = pageUris,
                                parentCacheId = current.editSource?.cacheId,
                                parentEntryId = current.editSource?.entryId,
                                onPrepared = viewModel::recordResultCacheId,
                            )
                            if (!accepted && resultViewModel.state.value !is ScreenState.Processing) {
                                viewModel.resumeFinishedReview(current.resultCacheId, failed = true)
                                importRequestedSessionId = null
                            }
                        }
                        ScannerV2BridgeAction.OpenResult -> openResultScreen()
                        ScannerV2BridgeAction.RecoverReview -> {
                            viewModel.resumeFinishedReview(current.resultCacheId, failed = true)
                            importRequestedSessionId = null
                        }
                    }
                } catch (failure: Exception) {
                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                    try {
                        viewModel.resumeFinishedReview(current.resultCacheId, failed = true)
                        importRequestedSessionId = null
                    } catch (recoveryFailure: Exception) {
                        if (recoveryFailure is kotlinx.coroutines.CancellationException) {
                            throw recoveryFailure
                        }
                        viewModel.sessionUnavailable()
                    }
                }
            }
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
                    liveDocumentQuad = liveDocumentQuad,
                    liveDocumentReady = liveDocumentReady,
                    cameraPermissionGranted = cameraPermissionGranted,
                    onRequestCameraPermission = {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    },
                    onCapture = ::capture,
                    onImportImage = {
                        imageImportLauncher.launch(arrayOf("image/jpeg", "image/png", "image/webp"))
                    },
                    onCancelCamera = ::cancelCamera,
                    onDiscardInterruptedCapture = viewModel::discardInterruptedCapture,
                    onConfirmCrop = viewModel::confirmCrop,
                    onEditCrop = viewModel::editSelectedCrop,
                    onCancelCropEdit = viewModel::cancelCropEditing,
                    onApplyAppearance = viewModel::applyAppearance,
                    customFilters = customFilters,
                    customFiltersLoaded = customFiltersLoaded,
                    customFiltersUnreadable = customFiltersUnreadable,
                    onSaveCustomFilter = { name, appearance ->
                        customFilters = upsertScannerV2CustomFilter(
                            current = customFilters,
                            rawName = name,
                            appearance = appearance,
                            newId = UUID.randomUUID().toString(),
                        ).also(customFilterStore::save)
                    },
                    onDeleteCustomFilter = { id ->
                        customFilters = deleteScannerV2CustomFilter(customFilters, id)
                            .also(customFilterStore::save)
                    },
                    onResetCustomFilters = {
                        customFilterStore.reset()
                        customFilters = emptyList()
                        customFiltersUnreadable = false
                    },
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
        cameraAnalysisExecutor.shutdown()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(V2_LAUNCH_DIRECTIVE_CONSUMED_KEY, launchDirectiveConsumed)
        outState.putString(V2_VIEW_MODEL_TOKEN_KEY, viewModel.instanceToken)
        super.onSaveInstanceState(outState)
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
                    val previewResolution = ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .build()
                    val preview = Preview.Builder()
                        .setResolutionSelector(previewResolution)
                        .build()
                    val captureResolution = ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionFilter(
                            ResolutionFilter { supportedSizes, _ ->
                                supportedSizes.filter { size ->
                                    isSupportedScannerV2CaptureSize(size.width, size.height)
                                }
                            },
                        )
                        .build()
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setResolutionSelector(captureResolution)
                        .setTargetRotation(display?.rotation ?: Surface.ROTATION_0)
                        .build()
                    val analysisResolution = ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                V2_ANALYSIS_SIZE,
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                            ),
                        )
                        .build()
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setResolutionSelector(analysisResolution)
                        .build()
                    analysis.setAnalyzer(cameraAnalysisExecutor) { image ->
                        analyzeCameraFrame(image, generation)
                    }
                    val viewPort = ViewPort.Builder(
                        Rational(3, 4),
                        display?.rotation ?: Surface.ROTATION_0,
                    ).setScaleType(ViewPort.FILL_CENTER).build()
                    val useCaseGroup = UseCaseGroup.Builder()
                        .setViewPort(viewPort)
                        .addUseCase(preview)
                        .addUseCase(capture)
                        .addUseCase(analysis)
                        .build()
                    viewModel.bindPreview(preview)
                    provider.unbindAll()
                    provider.bindToLifecycle(this, selector, useCaseGroup)
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
        liveDocumentQuad = null
        liveDocumentReady = false
        analysisGuide = null
        autoCaptureGate.reset()
        captureRequestInFlight = false
        lastAnalysisStartedAt = 0L
        lastAnalysisDetectedAt = 0L
        analysisGuideVisible = false
        cameraProvider?.unbindAll()
        cameraProvider = null
        viewModel.clearPreviewSurface()
    }

    private fun analyzeCameraFrame(image: ImageProxy, generation: Long) {
        try {
            if (generation != cameraBindGeneration) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastAnalysisStartedAt < V2_ANALYSIS_INTERVAL_MS) return
            lastAnalysisStartedAt = now
            val plane = image.planes.firstOrNull() ?: return
            val detected = try {
                val crop = image.cropRect
                val frame = copyScannerV2LumaCrop(
                    sourceWidth = image.width,
                    sourceHeight = image.height,
                    cropLeft = crop.left,
                    cropTop = crop.top,
                    cropWidth = crop.width(),
                    cropHeight = crop.height(),
                    rowStride = plane.rowStride,
                    pixelStride = plane.pixelStride,
                    source = plane.buffer,
                )
                detectLiveDocumentQuad(frame)?.let { crop ->
                    rotateScannerV2AnalysisQuad(crop, image.imageInfo.rotationDegrees)
                }
            } catch (_: IllegalArgumentException) {
                null
            }
            if (generation != cameraBindGeneration) return
            val decision = autoCaptureGate.update(detected)
            val publishedGuide = when {
                detected != null -> {
                    lastAnalysisDetectedAt = now
                    analysisGuideVisible = true
                    decision.guide.also { analysisGuide = it }
                }
                analysisGuideVisible && now - lastAnalysisDetectedAt < V2_ANALYSIS_GUIDE_HOLD_MS ->
                    analysisGuide
                else -> {
                    analysisGuideVisible = false
                    analysisGuide = null
                    null
                }
            }
            runOnUiThread {
                if (generation == cameraBindGeneration && !isDestroyed) {
                    liveDocumentQuad = publishedGuide
                    liveDocumentReady = detected != null && decision.ready
                    if (decision.shouldCapture) capture(preferLiveGuide = true)
                }
            }
        } finally {
            image.close()
        }
    }

    private fun capture(preferLiveGuide: Boolean = false) {
        if (captureRequestInFlight) return
        val capture = imageCapture ?: run {
            viewModel.cameraUnavailable()
            return
        }
        captureRequestInFlight = true
        val suggestedCrop = liveDocumentQuad
        lifecycleScope.launch {
            val ticket = try {
                viewModel.reserveCapture(suggestedCrop, preferLiveGuide)
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                null
            } ?: run {
                captureRequestInFlight = false
                autoCaptureGate.reset()
                return@launch
            }
            capture.targetRotation = display?.rotation ?: Surface.ROTATION_0
            val output = ImageCapture.OutputFileOptions.Builder(ticket.destination).build()
            capture.takePicture(
                output,
                ContextCompat.getMainExecutor(this@ScannerV2Activity),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        captureRequestInFlight = false
                        viewModel.captureCompleted(ticket)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        captureRequestInFlight = false
                        autoCaptureGate.reset()
                        liveDocumentReady = false
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

    private fun openResultScreen() {
        if (resultOpened || isFinishing || isDestroyed) return
        val cached = checkNotNull((resultViewModel.state.value as? ScreenState.Result)?.scan?.cached)
        val entryId = checkNotNull(cached.entryId)
        resultOpened = true
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(EXTRA_SCANNER_V2_RESULT_CACHE_ID, cached.baseName)
                    .putExtra(EXTRA_SCANNER_V2_RESULT_ENTRY_ID, entryId),
            )
            finish()
        } catch (failure: RuntimeException) {
            resultOpened = false
            throw failure
        }
    }
}

private fun scannerV2EditSource(intent: Intent): ScannerV2EditSource? {
    val cacheId = intent.getStringExtra(EXTRA_SCANNER_V2_EDIT_CACHE_ID)
    val entryId = intent.getStringExtra(EXTRA_SCANNER_V2_EDIT_ENTRY_ID)
    if (cacheId == null || entryId == null) return null
    return try {
        ScannerV2EditSource(cacheId, entryId)
    } catch (_: IllegalArgumentException) {
        null
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
    liveDocumentQuad: PageQuad?,
    liveDocumentReady: Boolean,
    cameraPermissionGranted: Boolean,
    onRequestCameraPermission: () -> Unit,
    onCapture: () -> Unit,
    onImportImage: () -> Unit,
    onCancelCamera: () -> Unit,
    onDiscardInterruptedCapture: () -> Unit,
    onConfirmCrop: (PageQuad, Int) -> Unit,
    onEditCrop: () -> Unit,
    onCancelCropEdit: () -> Unit,
    onApplyAppearance: (ScannerV2Appearance) -> Unit,
    customFilters: List<ScannerV2CustomFilter>,
    customFiltersLoaded: Boolean,
    customFiltersUnreadable: Boolean,
    onSaveCustomFilter: (String, ScannerV2Appearance) -> Unit,
    onDeleteCustomFilter: (String) -> Unit,
    onResetCustomFilters: () -> Unit,
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
                liveDocumentQuad = liveDocumentQuad,
                liveDocumentReady = liveDocumentReady,
                permissionGranted = cameraPermissionGranted,
                onRequestPermission = onRequestCameraPermission,
                onCapture = onCapture,
                onImportImage = onImportImage,
                onCancel = onCancelCamera,
                onDiscardInterruptedCapture = onDiscardInterruptedCapture,
            )
            ScannerSessionStage.Reviewing -> ScannerV2ReviewScreen(
                state = state,
                onConfirmCrop = onConfirmCrop,
                onEditCrop = onEditCrop,
                onCancelCropEdit = onCancelCropEdit,
                onApplyAppearance = onApplyAppearance,
                customFilters = customFilters,
                customFiltersLoaded = customFiltersLoaded,
                customFiltersUnreadable = customFiltersUnreadable,
                onSaveCustomFilter = onSaveCustomFilter,
                onDeleteCustomFilter = onDeleteCustomFilter,
                onResetCustomFilters = onResetCustomFilters,
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
    liveDocumentQuad: PageQuad?,
    liveDocumentReady: Boolean,
    permissionGranted: Boolean,
    onRequestPermission: () -> Unit,
    onCapture: () -> Unit,
    onImportImage: () -> Unit,
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
            Modifier.fillMaxWidth().weight(1f, fill = false)
                .aspectRatio(3f / 4f, matchHeightConstraintsFirst = true)
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
                    contentScale = ContentScale.Crop,
                )
                else -> CircularProgressIndicator(color = Color.White)
            }
            if (surfaceRequest != null && liveDocumentQuad != null) {
                ScannerV2LiveDocumentGuide(
                    crop = liveDocumentQuad,
                    ready = liveDocumentReady,
                    modifier = Modifier.fillMaxSize(),
                )
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
        Row(
            Modifier.fillMaxWidth().height(84.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onImportImage,
                enabled = !state.busy,
                modifier = Modifier.size(56.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_image),
                    contentDescription = stringResource(R.string.v2_import_image),
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.width(28.dp))
            val captureEnabled = permissionGranted && surfaceRequest != null && !state.busy
            val captureDescription = stringResource(R.string.v2_capture_button)
            Surface(
                onClick = onCapture,
                enabled = captureEnabled,
                modifier = Modifier.size(76.dp).semantics {
                    contentDescription = captureDescription
                    role = Role.Button
                },
                shape = CircleShape,
                color = Color.Transparent,
                border = BorderStroke(
                    4.dp,
                    MaterialTheme.colorScheme.onSurface.copy(alpha = if (captureEnabled) 1f else .38f),
                ),
            ) {
                Box(
                    Modifier.fillMaxSize().padding(8.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurface.copy(
                                alpha = if (captureEnabled) 1f else .38f,
                            ),
                            CircleShape,
                        ),
                )
            }
            Spacer(Modifier.width(84.dp))
        }
    }
}

@Composable
private fun ScannerV2LiveDocumentGuide(
    crop: PageQuad,
    ready: Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val description = stringResource(
        if (ready) R.string.v2_document_ready else R.string.v2_document_detected,
    )
    Canvas(
        modifier.semantics {
            contentDescription = description
            stateDescription = description
            if (ready) liveRegion = LiveRegionMode.Polite
        },
    ) {
        fun point(value: NormalizedPoint): Offset = Offset(
            x = (value.x * size.width).toFloat(),
            y = (value.y * size.height).toFloat(),
        )
        val path = Path().apply {
            val first = point(crop.topLeft)
            moveTo(first.x, first.y)
            val topRight = point(crop.topRight)
            val bottomRight = point(crop.bottomRight)
            val bottomLeft = point(crop.bottomLeft)
            lineTo(topRight.x, topRight.y)
            lineTo(bottomRight.x, bottomRight.y)
            lineTo(bottomLeft.x, bottomLeft.y)
            close()
        }
        drawPath(
            path = path,
            color = Color.Black.copy(alpha = .58f),
            style = Stroke(width = with(density) { 4.dp.toPx() }, cap = StrokeCap.Round),
        )
        drawPath(
            path = path,
            color = if (ready) Color(0xFF69E6A6) else Color.White,
            style = Stroke(width = with(density) { 2.dp.toPx() }, cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun ScannerV2ReviewScreen(
    state: ScannerV2UiState,
    onConfirmCrop: (PageQuad, Int) -> Unit,
    onEditCrop: () -> Unit,
    onCancelCropEdit: () -> Unit,
    onApplyAppearance: (ScannerV2Appearance) -> Unit,
    customFilters: List<ScannerV2CustomFilter>,
    customFiltersLoaded: Boolean,
    customFiltersUnreadable: Boolean,
    onSaveCustomFilter: (String, ScannerV2Appearance) -> Unit,
    onDeleteCustomFilter: (String) -> Unit,
    onResetCustomFilters: () -> Unit,
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
    var holdingComparison by remember(record.pageId, record.renderedFingerprint) { mutableStateOf(false) }
    var showFullscreen by rememberSaveable(record.pageId.value, record.renderedFingerprint) {
        mutableStateOf(false)
    }
    val rendered = record.renderedFingerprint != null && !state.cropEditing
    val originalPreview = state.filterPreviews[ScannerV2Filter.Original]
    val showOriginal = shouldShowScannerV2Original(
        rendered = rendered,
        busy = state.busy,
        holding = holdingComparison,
        originalAvailable = originalPreview != null,
    )
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
                Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 520.dp).then(
                    if (rendered && !state.busy && originalPreview != null) {
                        Modifier.pointerInput(record.pageId, record.renderedFingerprint, originalPreview) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { holdingComparison = true },
                                onDragEnd = { holdingComparison = false },
                                onDragCancel = { holdingComparison = false },
                                onDrag = { change, _ -> change.consume() },
                            )
                        }
                    } else {
                        Modifier
                    },
                ),
                contentAlignment = Alignment.Center,
            ) {
                val preview = if (showOriginal) {
                    originalPreview
                } else {
                    state.preview
                }
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
                    if (showOriginal) {
                        Surface(
                            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = .88f),
                        ) {
                            Text(
                                stringResource(R.string.v2_comparison_original),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                    if (rendered && !state.busy) {
                        Surface(
                            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface.copy(alpha = .9f),
                        ) {
                            IconButton(onClick = { showFullscreen = true }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_fullscreen),
                                    contentDescription = stringResource(
                                        R.string.open_fullscreen_preview,
                                        stringResource(
                                            R.string.v2_page_count,
                                            selected + 1,
                                            manifest.pages.size,
                                        ),
                                    ),
                                )
                            }
                        }
                    }
                }
            }
            if (rendered && originalPreview != null) {
                Text(
                    stringResource(R.string.v2_hold_to_compare),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (!rendered && !state.busy) {
                OutlinedButton(
                    onClick = { crop = PageQuad.fullFrame() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.v2_use_whole_image))
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
                pageKey = record.pageId.value,
                customFilters = customFilters,
                customFiltersLoaded = customFiltersLoaded,
                customFiltersUnreadable = customFiltersUnreadable,
                busy = state.busy,
                onApply = onApplyAppearance,
                onSaveCustomFilter = onSaveCustomFilter,
                onDeleteCustomFilter = onDeleteCustomFilter,
                onResetCustomFilters = onResetCustomFilters,
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
    if (showFullscreen) {
        state.preview?.let { preview ->
            ScannerV2FullscreenPreview(
                bitmap = preview,
                pageIndex = selected,
                pageCount = manifest.pages.size,
                onDismiss = { showFullscreen = false },
            )
        }
    }
}

@Composable
private fun ScannerV2FullscreenPreview(
    bitmap: android.graphics.Bitmap,
    pageIndex: Int,
    pageCount: Int,
    onDismiss: () -> Unit,
) {
    val pagePosition = stringResource(R.string.v2_page_count, pageIndex + 1, pageCount)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 12.dp)) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(pagePosition, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = stringResource(R.string.close_fullscreen_preview),
                        )
                    }
                }
                BoxWithConstraints(
                    Modifier.fillMaxWidth().weight(1f).clipToBounds(),
                    contentAlignment = Alignment.Center,
                ) {
                    val ratio = bitmap.width.toFloat() / bitmap.height
                    val pageWidth = minOf(maxWidth, maxHeight * ratio)
                    val pageHeight = pageWidth / ratio
                    val density = LocalDensity.current
                    val contentWidth = with(density) { pageWidth.toPx() }
                    val contentHeight = with(density) { pageHeight.toPx() }
                    val viewportWidth = with(density) { maxWidth.toPx() }
                    val viewportHeight = with(density) { maxHeight.toPx() }
                    var transform by remember(bitmap) {
                        mutableStateOf(ScannerV2ViewportTransform(1f, 0f, 0f))
                    }
                    val transformableState = rememberTransformableState { _, zoom, pan, _ ->
                        transform = updateScannerV2ViewportTransform(
                            current = transform,
                            zoomChange = zoom,
                            panX = pan.x,
                            panY = pan.y,
                            contentWidth = contentWidth,
                            contentHeight = contentHeight,
                            viewportWidth = viewportWidth,
                            viewportHeight = viewportHeight,
                        )
                    }
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = pagePosition,
                        modifier = Modifier.width(pageWidth).height(pageHeight)
                            .pointerInput(bitmap) {
                                detectTapGestures(
                                    onDoubleTap = {
                                        transform = ScannerV2ViewportTransform(1f, 0f, 0f)
                                    },
                                )
                            }
                            .transformable(transformableState)
                            .graphicsLayer(
                                scaleX = transform.scale,
                                scaleY = transform.scale,
                                translationX = transform.offsetX,
                                translationY = transform.offsetY,
                                clip = true,
                            ),
                        contentScale = ContentScale.FillBounds,
                    )
                }
                Text(
                    stringResource(R.string.fullscreen_preview_hint),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ScannerV2AppearanceEditor(
    appearance: ScannerV2Appearance,
    previews: Map<ScannerV2Filter, android.graphics.Bitmap>,
    pageKey: String,
    customFilters: List<ScannerV2CustomFilter>,
    customFiltersLoaded: Boolean,
    customFiltersUnreadable: Boolean,
    busy: Boolean,
    onApply: (ScannerV2Appearance) -> Unit,
    onSaveCustomFilter: (String, ScannerV2Appearance) -> Unit,
    onDeleteCustomFilter: (String) -> Unit,
    onResetCustomFilters: () -> Unit,
) {
    var selectedFilterWire by rememberSaveable(
        pageKey,
        appearance.filter.wireValue,
        appearance.intensity,
        appearance.shadows,
    ) { mutableStateOf(appearance.filter.wireValue) }
    var intensity by rememberSaveable(
        pageKey,
        appearance.filter.wireValue,
        appearance.intensity,
        appearance.shadows,
    ) { mutableIntStateOf(appearance.intensity) }
    var shadows by rememberSaveable(
        pageKey,
        appearance.filter.wireValue,
        appearance.intensity,
        appearance.shadows,
    ) { mutableIntStateOf(appearance.shadows) }
    var selectedCustomFilterId by rememberSaveable(pageKey) { mutableStateOf<String?>(null) }
    var showSaveDialog by rememberSaveable(pageKey) { mutableStateOf(false) }
    var showResetDialog by rememberSaveable(pageKey) { mutableStateOf(false) }
    var customFilterName by rememberSaveable(pageKey) { mutableStateOf("") }
    var pendingDeleteFilterId by rememberSaveable(pageKey) { mutableStateOf<String?>(null) }
    val selectedFilter = ScannerV2Filter.parse(selectedFilterWire) ?: appearance.filter
    val edited = ScannerV2Appearance(selectedFilter, intensity, shadows)
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
                    selectedFilterWire = filter.wireValue
                    intensity = preset.intensity
                    shadows = preset.shadows
                    selectedCustomFilterId = null
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
    if (customFilters.isNotEmpty()) {
        Text(stringResource(R.string.v2_custom_filters), style = MaterialTheme.typography.titleSmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(customFilters, key = { preset -> preset.id }) { preset ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = selectedCustomFilterId == preset.id,
                        enabled = !busy,
                        onClick = {
                            selectedFilterWire = preset.appearance.filter.wireValue
                            intensity = preset.appearance.intensity
                            shadows = preset.appearance.shadows
                            selectedCustomFilterId = preset.id
                            if (preset.appearance != appearance) onApply(preset.appearance)
                        },
                        label = {
                            Text(
                                preset.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick = { pendingDeleteFilterId = preset.id },
                        enabled = !busy,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = stringResource(
                                R.string.v2_delete_custom_filter,
                                preset.name,
                            ),
                        )
                    }
                }
            }
        }
    }
    if (selectedFilter != ScannerV2Filter.Original) {
        Text(stringResource(R.string.v2_filter_intensity, intensity))
        Slider(
            value = intensity.toFloat(),
            onValueChange = {
                intensity = it.toInt()
                selectedCustomFilterId = null
            },
            valueRange = 0f..100f,
            enabled = !busy,
        )
        if (selectedFilter != ScannerV2Filter.Drawing) {
            Text(stringResource(R.string.v2_filter_shadows, shadows))
            Slider(
                value = shadows.toFloat(),
                onValueChange = {
                    shadows = it.toInt()
                    selectedCustomFilterId = null
                },
                valueRange = 0f..100f,
                enabled = !busy,
            )
        }
        Button(
            onClick = { onApply(edited) },
            enabled = !busy && edited != appearance,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.v2_apply_filter))
        }
        OutlinedButton(
            onClick = {
                if (customFiltersUnreadable) {
                    showResetDialog = true
                } else {
                    customFilterName = ""
                    showSaveDialog = true
                }
            },
            enabled = !busy && customFiltersLoaded,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.v2_save_custom_filter))
        }
    }
    if (showSaveDialog) {
        val saveError = scannerV2CustomFilterSaveError(customFilters, customFilterName, edited)
        val replacementName = scannerV2CustomFilterReplacementName(customFilters, customFilterName)
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text(stringResource(R.string.v2_save_custom_filter)) },
            text = {
                OutlinedTextField(
                    value = customFilterName,
                    onValueChange = { value ->
                        if (value.length <= MAX_SCANNER_V2_CUSTOM_FILTER_NAME_LENGTH + 1) {
                            customFilterName = value
                        }
                    },
                    label = { Text(stringResource(R.string.v2_custom_filter_name)) },
                    singleLine = true,
                    isError = customFilterName.isNotEmpty() && saveError != null,
                    supportingText = {
                        if (customFilterName.isNotEmpty()) {
                            when (saveError) {
                                ScannerV2CustomFilterSaveError.InvalidName,
                                ScannerV2CustomFilterSaveError.UnsupportedAppearance,
                                -> Text(
                                    stringResource(
                                        R.string.v2_custom_filter_invalid_name,
                                        MAX_SCANNER_V2_CUSTOM_FILTER_NAME_LENGTH,
                                    ),
                                )
                                ScannerV2CustomFilterSaveError.CapacityReached -> Text(
                                    stringResource(
                                        R.string.v2_custom_filter_limit,
                                        MAX_SCANNER_V2_CUSTOM_FILTERS,
                                    ),
                                )
                                null -> replacementName?.let { name ->
                                    Text(stringResource(R.string.v2_custom_filter_replaces, name))
                                }
                            }
                        }
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSaveCustomFilter(customFilterName, edited)
                        showSaveDialog = false
                    },
                    enabled = !busy && saveError == null,
                ) {
                    Text(
                        stringResource(
                            if (replacementName == null) R.string.v2_save else R.string.v2_replace,
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text(stringResource(R.string.v2_cancel))
                }
            },
        )
    }
    customFilters.firstOrNull { it.id == pendingDeleteFilterId }?.let { preset ->
        AlertDialog(
            onDismissRequest = { pendingDeleteFilterId = null },
            title = { Text(stringResource(R.string.v2_delete_custom_filter_title)) },
            text = { Text(stringResource(R.string.v2_delete_custom_filter_confirm, preset.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteCustomFilter(preset.id)
                        if (selectedCustomFilterId == preset.id) selectedCustomFilterId = null
                        pendingDeleteFilterId = null
                    },
                ) { Text(stringResource(R.string.v2_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteFilterId = null }) {
                    Text(stringResource(R.string.v2_cancel))
                }
            },
        )
    }
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(R.string.v2_custom_filter_reset_title)) },
            text = { Text(stringResource(R.string.v2_custom_filter_reset_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onResetCustomFilters()
                        showResetDialog = false
                    },
                ) { Text(stringResource(R.string.v2_reset)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(stringResource(R.string.v2_cancel))
                }
            },
        )
    }
}

private fun scannerV2FilterLabel(filter: ScannerV2Filter): Int = when (filter) {
    ScannerV2Filter.Original -> R.string.v2_filter_original
    ScannerV2Filter.Natural -> R.string.v2_filter_natural
    ScannerV2Filter.Color -> R.string.v2_filter_color
    ScannerV2Filter.LightText -> R.string.v2_filter_light_text
    ScannerV2Filter.Grayscale -> R.string.v2_filter_grayscale
    ScannerV2Filter.Drawing -> R.string.v2_filter_drawing
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
        val image = remember(bitmap) { bitmap.asImageBitmap() }
        var activeCorner by remember(bitmap) { mutableStateOf<PageCorner?>(null) }
        var grabOffset by remember(bitmap) { mutableStateOf(Offset.Zero) }
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
                                grabOffset = activeCorner?.let { corner ->
                                    val point = currentCrop.corner(corner)
                                    Offset(
                                        x = (point.x * size.width).toFloat() - offset.x,
                                        y = (point.y * size.height).toFloat() - offset.y,
                                    )
                                } ?: Offset.Zero
                            },
                            onDragEnd = {
                                activeCorner = null
                                grabOffset = Offset.Zero
                            },
                            onDragCancel = {
                                activeCorner = null
                                grabOffset = Offset.Zero
                            },
                            onDrag = { change, _ ->
                                activeCorner?.let { corner ->
                                    change.consume()
                                    onCropChange(
                                        moveScannerV2CornerTo(
                                            crop = currentCrop,
                                            corner,
                                            target = NormalizedPoint(
                                                x = ((change.position.x + grabOffset.x) / size.width)
                                                    .toDouble()
                                                    .coerceIn(0.0, 1.0),
                                                y = ((change.position.y + grabOffset.y) / size.height)
                                                    .toDouble()
                                                    .coerceIn(0.0, 1.0),
                                            ),
                                        ),
                                    )
                                }
                            },
                        )
                    } else Modifier,
                ),
        ) {
            Image(
                bitmap = image,
                contentDescription = stringResource(R.string.v2_page_preview),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
            )
            if (editable) ScannerV2CropOverlay(image, crop, activeCorner)
        }
    }
}

@Composable
private fun ScannerV2CropOverlay(
    image: ImageBitmap,
    crop: PageQuad,
    activeCorner: PageCorner?,
) {
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
        activeCorner?.let { corner ->
            val draggedPoint = crop.corner(corner)
            val normalizedCenter = scannerV2MagnifierCenter(corner, draggedPoint)
            val radius = 52.dp.toPx().coerceAtMost(minOf(size.width, size.height) * .22f)
            val center = Offset(
                x = (normalizedCenter.x * size.width).toFloat().coerceIn(radius, size.width - radius),
                y = (normalizedCenter.y * size.height).toFloat().coerceIn(radius, size.height - radius),
            )
            val sourceDimension = minOf(image.width, image.height)
            val sourceSize = (sourceDimension * .14f).roundToInt()
                .coerceAtLeast(1)
                .coerceAtMost(sourceDimension)
            val sourceLeft = ((draggedPoint.x * image.width).roundToInt() - sourceSize / 2)
                .coerceIn(0, image.width - sourceSize)
            val sourceTop = ((draggedPoint.y * image.height).roundToInt() - sourceSize / 2)
                .coerceIn(0, image.height - sourceSize)
            val destinationSize = (radius * 2f).roundToInt()
            val destinationOffset = IntOffset(
                (center.x - radius).roundToInt(),
                (center.y - radius).roundToInt(),
            )
            val sourcePosition = scannerV2MagnifierSourcePosition(
                draggedPoint,
                image.width,
                image.height,
                sourceLeft,
                sourceTop,
                sourceSize,
            )
            val crosshairCenter = Offset(
                destinationOffset.x + sourcePosition.x.toFloat() * destinationSize,
                destinationOffset.y + sourcePosition.y.toFloat() * destinationSize,
            )
            val lensPath = Path().apply { addOval(Rect(center, radius)) }
            clipPath(lensPath) {
                drawImage(
                    image = image,
                    srcOffset = IntOffset(sourceLeft, sourceTop),
                    srcSize = IntSize(sourceSize, sourceSize),
                    dstOffset = destinationOffset,
                    dstSize = IntSize(destinationSize, destinationSize),
                )
            }
            drawCircle(Color.Black.copy(alpha = .78f), radius, center, style = Stroke(6.dp.toPx()))
            drawCircle(Color.White, radius, center, style = Stroke(2.dp.toPx()))
            val crosshair = 10.dp.toPx()
            drawLine(
                Color.White,
                Offset(crosshairCenter.x - crosshair, crosshairCenter.y),
                Offset(crosshairCenter.x + crosshair, crosshairCenter.y),
                strokeWidth = 2.dp.toPx(),
            )
            drawLine(
                Color.White,
                Offset(crosshairCenter.x, crosshairCenter.y - crosshair),
                Offset(crosshairCenter.x, crosshairCenter.y + crosshair),
                strokeWidth = 2.dp.toPx(),
            )
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
        Spacer(Modifier.height(24.dp))
        if (state.issue == null) {
            CircularProgressIndicator()
        } else {
            ScannerV2IssueText(state.issue)
        }
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
        ScannerV2Issue.ImportFailed -> R.string.v2_error_import
        ScannerV2Issue.RenderFailed -> R.string.v2_error_render
        ScannerV2Issue.FinishFailed -> R.string.v2_error_finish
        ScannerV2Issue.CameraUnavailable -> R.string.v2_error_camera
    }
    Text(stringResource(message), color = MaterialTheme.colorScheme.error)
}
