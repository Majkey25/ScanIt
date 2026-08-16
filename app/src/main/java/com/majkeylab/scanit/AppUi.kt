package com.majkeylab.scanit

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.PersistableBundle
import android.provider.MediaStore
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged

private const val PRIVACY_POLICY_URL =
    "https://majkey25.github.io/ScanIt/privacy.html"
private const val THIRD_PARTY_NOTICES_URL =
    "https://majkey25.github.io/ScanIt/third-party-notices.txt"
private const val SOURCE_CODE_URL = "https://github.com/Majkey25/ScanIt"
internal const val SUPPORT_URL = "https://www.buymeacoffee.com/majkey"

private val LightColorScheme =
    lightColorScheme(
        primary = Color.Black,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFE5E5E5),
        onPrimaryContainer = Color.Black,
        secondary = Color(0xFF444444),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFE5E5E5),
        onSecondaryContainer = Color.Black,
        tertiary = Color(0xFF666666),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFE5E5E5),
        onTertiaryContainer = Color.Black,
        inversePrimary = Color.White,
    )

private val DarkColorScheme =
    darkColorScheme(
        primary = Color.White,
        onPrimary = Color.Black,
        primaryContainer = Color(0xFF303030),
        onPrimaryContainer = Color.White,
        secondary = Color(0xFFD0D0D0),
        onSecondary = Color.Black,
        secondaryContainer = Color(0xFF303030),
        onSecondaryContainer = Color.White,
        tertiary = Color(0xFFB0B0B0),
        onTertiary = Color.Black,
        tertiaryContainer = Color(0xFF303030),
        onTertiaryContainer = Color.White,
        inversePrimary = Color.Black,
    )

@Composable
internal fun ScanItApp(
    state: ScreenState,
    settings: AppSettings,
    language: AppLanguage,
    defaultEmailSubjects: Set<String>,
    onScan: () -> Unit,
    onEditScan: (() -> Unit)? = null,
    onSaveSettings: (AppSettings) -> Boolean,
    onLanguageChange: (AppLanguage) -> Unit,
    onPdfFolderSelected: (Uri, Int) -> UiMessage?,
    onPdfFolderCleared: () -> UiMessage?,
    onRecent: () -> Unit,
    onOpenRecent: (String) -> Unit,
    onShareRecentPdf: (String) -> Unit,
    onDeleteRecent: (OutputDeleteRequest) -> Unit,
    onLoadThumbnail: suspend (File) -> Bitmap?,
    onLoadResultPreview: suspend (File, Int) -> Bitmap? = { _, _ -> null },
    onLoadResultImageDimensions: suspend (List<File>) -> List<Pair<Int, Int>>? = { null },
    onLoadAppearancePreview: suspend (File, ScanAppearanceSettings, Int) -> Bitmap? =
        { _, _, _ -> null },
    onSelectResultPage: (Int) -> Unit,
    onNavigateBack: () -> Unit,
    onSharePdf: (() -> Unit)? = null,
    onShareImages: (() -> Unit)? = null,
    onPrint: (() -> Unit)? = null,
    onSaveNow: (SaveNowTarget) -> Unit = {},
    onChangePdfSize: (PdfSizeTarget) -> Unit = {},
    onChangePdfLocation: () -> Unit = {},
    onChangeImageSize: (ImageSizePreset, Int?) -> Unit = { _, _ -> },
    onChangeImageFormat: (ImageExportFormat) -> Unit = {},
    onChangeImageLocation: () -> Unit = {},
    onAcknowledgeUnknownOutput: (UnknownOutputCreateAcknowledgement) -> Unit = {},
    onCloseAppearanceEditor: () -> Unit = {},
    onApplyAppearance: (ScanAppearanceSettings) -> Unit = {},
    onRunDocumentAction: (DocumentAction) -> Unit = {},
    onDismissDocumentAction: () -> Unit = {},
    onExportDocumentText: () -> Unit = {},
    onOpenDocumentActionUrl: (String) -> Unit = {},
    onOpenVisualMarkEditor: () -> Unit = {},
    onCloseVisualMarkEditor: () -> Unit = {},
    onSelectVisualMarkTemplate: (String) -> Unit = {},
    onUpdateVisualMarkPlacement: (MarkPlacement) -> Unit = {},
    onBeginVisualMarkDrawing: () -> Unit = {},
    onUpdateVisualMarkDrawing: (List<MarkStroke>) -> Unit = {},
    onCancelVisualMarkDrawing: () -> Unit = {},
    onImportVisualMark: (Uri) -> Unit = {},
    onSaveDrawnVisualMark: (List<MarkStroke>) -> Unit = {},
    onDeleteVisualMarkTemplate: (String) -> Unit = {},
    onLoadVisualMarkTemplate: suspend (String, Int) -> Bitmap? = { _, _ -> null },
    onScanVisualMark: () -> Unit = {},
    onApplyVisualMark: () -> Unit = {},
) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val resultCacheId = (state as? ScreenState.Result)?.scan?.cached?.baseName
    var fileDetailsExpanded by rememberSaveable(resultCacheId) { mutableStateOf(false) }
    val backAction =
        appBackAction(
            showSettings,
            fileDetailsExpanded,
            state,
        )
    BackHandler {
        val visualMarkEditor = (state as? ScreenState.Result)?.visualMarkEditor
        val documentActionState = (state as? ScreenState.Result)?.documentActionState
        when {
            visualMarkEditor != null -> onCloseVisualMarkEditor()
            state is ScreenState.Result && state.appearanceReviewRequired ->
                onCloseAppearanceEditor()
            documentActionState != null -> onDismissDocumentAction()
            backAction == AppBackAction.CloseSettings -> showSettings = false
            backAction == AppBackAction.CollapseFileDetails -> fileDetailsExpanded = false
            backAction == AppBackAction.ShowRecent -> onNavigateBack()
            backAction == AppBackAction.LaunchScanner -> onScan()
            else -> Unit
        }
    }

    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColorScheme else LightColorScheme,
    ) {
        if (state is ScreenState.Result && state.appearanceReviewRequired) {
            AppearanceEditScreen(
                result = state,
                defaultAppearance = settings.appearance,
                onClose = onCloseAppearanceEditor,
                onApply = onApplyAppearance,
                onSelectPage = onSelectResultPage,
                onLoadThumbnail = onLoadThumbnail,
                onLoadAppearancePreview = onLoadAppearancePreview,
            )
        } else if (state is ScreenState.Result && state.visualMarkEditor != null) {
            VisualMarkEditorScreen(
                result = state,
                editor = state.visualMarkEditor,
                onClose = onCloseVisualMarkEditor,
                onSelectTemplate = onSelectVisualMarkTemplate,
                onPlacementChange = onUpdateVisualMarkPlacement,
                onBeginDrawing = onBeginVisualMarkDrawing,
                onUpdateDrawing = onUpdateVisualMarkDrawing,
                onCancelDrawing = onCancelVisualMarkDrawing,
                onImport = onImportVisualMark,
                onSaveDrawing = onSaveDrawnVisualMark,
                onDeleteTemplate = onDeleteVisualMarkTemplate,
                onLoadTemplate = onLoadVisualMarkTemplate,
                onScan = onScanVisualMark,
                onApply = onApplyVisualMark,
            )
        } else if (showSettings) {
            SettingsScreen(
                settings = settings,
                language = language,
                defaultEmailSubjects = defaultEmailSubjects,
                onClose = {
                    showSettings = false
                },
                onSave = onSaveSettings,
                onLanguageChange = onLanguageChange,
                onPdfFolderSelected = onPdfFolderSelected,
                onPdfFolderCleared = onPdfFolderCleared,
            )
        } else {
            when (state) {
                ScreenState.Ready -> ProcessingScreen(stringResource(R.string.opening_scanner))
                is ScreenState.Processing -> ProcessingScreen(state.message.resolve())
                is ScreenState.Failure ->
                    FailureScreen(
                        message = state.message.resolve(),
                        onRetry = onScan,
                        onRecent = onRecent,
                        onSettings = {
                            fileDetailsExpanded = false
                            showSettings = true
                        },
                    )
                is ScreenState.Recent ->
                    RecentScreen(
                        state = state,
                        onNewScan = onScan,
                        onOpen = onOpenRecent,
                        onSharePdf = onShareRecentPdf,
                        onDelete = onDeleteRecent,
                        onLoadThumbnail = onLoadThumbnail,
                        onSettings = { showSettings = true },
                    )
                is ScreenState.Result ->
                    ResultScreen(
                        result = state,
                        onNewScan = onScan,
                        onEditScan = onEditScan,
                        onRecent = onRecent,
                        onSettings = {
                            fileDetailsExpanded = false
                            showSettings = true
                        },
                        onSharePdf = onSharePdf,
                        onShareImages = onShareImages,
                        onPrint = onPrint,
                        fileDetailsExpanded = fileDetailsExpanded,
                        onFileDetailsChange = { expanded ->
                            fileDetailsExpanded = expanded
                        },
                        onSaveNow = onSaveNow,
                        onChangePdfSize = onChangePdfSize,
                        onChangePdfLocation = onChangePdfLocation,
                        onChangeImageSize = onChangeImageSize,
                        onChangeImageFormat = onChangeImageFormat,
                        onChangeImageLocation = onChangeImageLocation,
                        onAcknowledgeUnknownOutput = onAcknowledgeUnknownOutput,
                        onRunDocumentAction = onRunDocumentAction,
                        onDismissDocumentAction = onDismissDocumentAction,
                        onExportDocumentText = onExportDocumentText,
                        onOpenDocumentActionUrl = onOpenDocumentActionUrl,
                        onSelectPage = onSelectResultPage,
                        onLoadThumbnail = onLoadThumbnail,
                        onLoadResultPreview = onLoadResultPreview,
                        onLoadResultImageDimensions = onLoadResultImageDimensions,
                        onAddVisualMark = {
                            fileDetailsExpanded = false
                            onOpenVisualMarkEditor()
                        },
                    )
            }
        }
    }
}

@Composable
private fun ProcessingScreen(message: String) {
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(20.dp))
            Text(message, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun FailureScreen(
    message: String,
    onRetry: () -> Unit,
    onRecent: () -> Unit,
    onSettings: () -> Unit,
) {
    MainScaffold(onRecent, onSettings) { modifier ->
        Column(
            modifier = modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(message, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(stringResource(R.string.try_again))
            }
        }
    }
}

@Composable
private fun AppearanceEditScreen(
    result: ScreenState.Result,
    defaultAppearance: ScanAppearanceSettings,
    onClose: () -> Unit,
    onApply: (ScanAppearanceSettings) -> Unit,
    onSelectPage: (Int) -> Unit,
    onLoadThumbnail: suspend (File) -> Bitmap?,
    onLoadAppearancePreview: suspend (File, ScanAppearanceSettings, Int) -> Bitmap?,
) {
    val cached = result.scan.cached
    val pageIndex = resolvedPageIndex(result.selectedPageIndex, cached.pages.size)
    val initial =
        cached.appearanceSettings
            ?: cached.appearance?.let(defaultAppearance::withApplied)
            ?: defaultAppearance
    var modeWire by rememberSaveable(cached.entryId) { mutableStateOf(initial.colorMode.wireValue) }
    val mode = ScanColorMode.entries.firstOrNull { it.wireValue == modeWire } ?: initial.colorMode
    val draft = initial.copy(colorMode = mode)
    val sourcePage = cached.sourcePages.getOrNull(pageIndex)
    val preview =
        ownedAppearancePreview(
            sourcePage = sourcePage,
            settings = draft,
            maxSize = 1024,
            onLoad = onLoadAppearancePreview,
        )
    val enabled = !result.appearanceApplyInProgress
    val pagePosition = stringResource(R.string.page_position, pageIndex + 1, cached.pages.size)
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            CompactTopBar(
                title = stringResource(R.string.appearance),
                onBack = onClose,
                backEnabled = enabled,
                actionsEnabled = enabled,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 420.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (preview.loading) {
                        CircularProgressIndicator()
                    } else if (preview.bitmap == null) {
                        Text(stringResource(R.string.preview_unavailable))
                    } else {
                        Image(
                            bitmap = preview.bitmap.asImageBitmap(),
                            contentDescription = pagePosition,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }
            }
            item { Text(pagePosition, style = MaterialTheme.typography.titleMedium) }
            if (cached.pages.size > 1) {
                item {
                    ResultPageStrip(
                        pages = cached.pages,
                        selectedPageIndex = pageIndex,
                        enabled = enabled,
                        onSelectPage = onSelectPage,
                        onLoadThumbnail = onLoadThumbnail,
                    )
                }
            }
            item {
                Text(stringResource(R.string.filters), style = MaterialTheme.typography.titleMedium)
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ScanColorMode.entries) { candidate ->
                        AppearanceFilterTile(
                            mode = candidate,
                            selected = candidate == mode,
                            sourcePage = sourcePage,
                            settings = initial.copy(colorMode = candidate),
                            enabled = enabled,
                            onLoad = onLoadAppearancePreview,
                            onClick = { modeWire = candidate.wireValue },
                        )
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.edit_filters_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            result.appearanceMessage?.let { message ->
                item {
                    Text(
                        message.resolve(),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onClose,
                        enabled = enabled,
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                    Button(
                        onClick = { onApply(draft) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    ) {
                        Text(
                            stringResource(
                                if (result.appearanceApplyInProgress) {
                                    R.string.applying_appearance
                                } else {
                                    R.string.apply_appearance
                                },
                            ),
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(4.dp)) }
        }
    }
}

@Composable
private fun AppearanceFilterTile(
    mode: ScanColorMode,
    selected: Boolean,
    sourcePage: File?,
    settings: ScanAppearanceSettings,
    enabled: Boolean,
    onLoad: suspend (File, ScanAppearanceSettings, Int) -> Bitmap?,
    onClick: () -> Unit,
) {
    val preview = ownedAppearancePreview(sourcePage, settings, 160, onLoad)
    Surface(
        onClick = onClick,
        enabled = enabled,
        selected = selected,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(if (selected) 2.dp else 1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.width(112.dp).heightIn(min = 144.dp),
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().height(104.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (preview.loading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else if (preview.bitmap == null) {
                    Text(
                        stringResource(R.string.preview_unavailable),
                        style = MaterialTheme.typography.labelSmall,
                    )
                } else {
                    Image(
                        bitmap = preview.bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            Text(scanColorModeLabel(mode), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ownedAppearancePreview(
    sourcePage: File?,
    settings: ScanAppearanceSettings,
    maxSize: Int,
    onLoad: suspend (File, ScanAppearanceSettings, Int) -> Bitmap?,
): BitmapPreviewState {
    val preview by produceState(BitmapPreviewState(), sourcePage, settings, maxSize) {
        val bitmap =
            if (sourcePage == null) {
                null
            } else {
                try {
                    onLoad(sourcePage, settings, maxSize)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    null
                }
            }
        value = BitmapPreviewState(bitmap = bitmap, loading = false)
        awaitDispose { bitmap?.recycle() }
    }
    return preview
}

@Composable
private fun ResultScreen(
    result: ScreenState.Result,
    onNewScan: () -> Unit,
    onEditScan: (() -> Unit)?,
    onRecent: () -> Unit,
    onSettings: () -> Unit,
    onSharePdf: (() -> Unit)?,
    onShareImages: (() -> Unit)?,
    onPrint: (() -> Unit)?,
    fileDetailsExpanded: Boolean,
    onFileDetailsChange: (Boolean) -> Unit,
    onSaveNow: (SaveNowTarget) -> Unit,
    onChangePdfSize: (PdfSizeTarget) -> Unit,
    onChangePdfLocation: () -> Unit,
    onChangeImageSize: (ImageSizePreset, Int?) -> Unit,
    onChangeImageFormat: (ImageExportFormat) -> Unit,
    onChangeImageLocation: () -> Unit,
    onAcknowledgeUnknownOutput: (UnknownOutputCreateAcknowledgement) -> Unit,
    onRunDocumentAction: (DocumentAction) -> Unit,
    onDismissDocumentAction: () -> Unit,
    onExportDocumentText: () -> Unit,
    onOpenDocumentActionUrl: (String) -> Unit,
    onSelectPage: (Int) -> Unit,
    onLoadThumbnail: suspend (File) -> Bitmap?,
    onLoadResultPreview: suspend (File, Int) -> Bitmap?,
    onLoadResultImageDimensions: suspend (List<File>) -> List<Pair<Int, Int>>?,
    onAddVisualMark: () -> Unit,
) {
    val scan = result.scan
    val pageCount = scan.cached.pages.size
    val selectedPageIndex = resolvedPageIndex(result.selectedPageIndex, pageCount)
    val (displayedPage, displayedPageCount) = resultPageStatus(selectedPageIndex, pageCount)
    val pagePosition =
        stringResource(R.string.page_position_short, displayedPage, displayedPageCount)
    val saveTargets = saveNowTargets(scan)
    var showSaveDialog by rememberSaveable(scan.cached.entryId) { mutableStateOf(false) }
    var showPdfSizeDialog by rememberSaveable(scan.cached.entryId) { mutableStateOf(false) }
    var showImageSizeDialog by rememberSaveable(scan.cached.entryId) { mutableStateOf(false) }
    var showImageFormatDialog by rememberSaveable(scan.cached.entryId) { mutableStateOf(false) }
    var showUnknownOutputDialog by rememberSaveable(scan.cached.entryId) { mutableStateOf(false) }
    var showFullscreen by rememberSaveable(scan.cached.entryId) { mutableStateOf(false) }
    var showDocumentActions by rememberSaveable(scan.cached.entryId) { mutableStateOf(false) }
    val cachedImageDimensions by
        produceState<List<Pair<Int, Int>>?>(
            initialValue = null,
            key1 = scan.cached.entryId,
            key2 = scan.cached.pages,
        ) {
            value =
                try {
                    onLoadResultImageDimensions(scan.cached.pages)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    null
                }
        }
    val actionsEnabled = !result.resultActionsBlocked
    val configuration = LocalConfiguration.current
    val availableWidthDp =
        with(LocalDensity.current) {
            LocalWindowInfo.current.containerSize.width.toDp().value.toInt()
        }
    val stackSecondaryActions =
        stackResultActions(configuration.fontScale, availableWidthDp)
    val pagerState =
        rememberPagerState(initialPage = selectedPageIndex) { pageCount }
    val currentSelectedPageIndex by rememberUpdatedState(selectedPageIndex)
    LaunchedEffect(selectedPageIndex) {
        if (pagerState.currentPage != selectedPageIndex) {
            pagerState.scrollToPage(selectedPageIndex)
        }
    }
    LaunchedEffect(pagerState, actionsEnabled) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                if (actionsEnabled && page != currentSelectedPageIndex) onSelectPage(page)
            }
    }
    val onResultEntryAction: (ResultEntryAction) -> Unit = { action ->
        when (resultActionDestination(action)) {
            ResultActionDestination.Scanner -> (onEditScan ?: onNewScan).invoke()
            ResultActionDestination.MarkEditor -> onAddVisualMark()
            ResultActionDestination.DocumentActions -> showDocumentActions = true
        }
    }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            CompactTopBar(
                title = "",
                onRecent = onRecent,
                onSettings = onSettings,
                actionsEnabled = actionsEnabled,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                HorizontalPager(
                    state = pagerState,
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    pageSpacing = 8.dp,
                    userScrollEnabled = actionsEnabled && pageCount > 1,
                    modifier = Modifier.fillMaxWidth().height(360.dp),
                    key = { page -> scan.cached.pages[page].absolutePath },
                ) { page ->
                    ResultPagerPage(
                        page = scan.cached.pages[page],
                        pageIndex = page,
                        pageCount = pageCount,
                        selectedPageIndex = selectedPageIndex,
                        selectedPreview = result.thumbnail,
                        selectedPreviewLoading = result.pagePreviewLoading,
                        enabled = actionsEnabled,
                        onOpenFullscreen = { showFullscreen = true },
                        onLoadPreview = onLoadResultPreview,
                    )
                }
            }
            item {
                Text(
                    text = pagePosition,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            item {
                if (stackSecondaryActions) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                onResultEntryAction(ResultEntryAction.Rescan)
                            },
                            enabled = actionsEnabled,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            ActionButtonContent(
                                iconRes = if (onEditScan == null) R.drawable.ic_camera else R.drawable.ic_edit,
                                textRes = if (onEditScan == null) R.string.rescan else R.string.edit_scan,
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                onResultEntryAction(ResultEntryAction.SignOrStamp)
                            },
                            enabled = actionsEnabled && result.canAddVisualMark,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            ActionButtonContent(
                                iconRes = R.drawable.ic_signature,
                                textRes = R.string.add_signature_or_stamp,
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                onResultEntryAction(ResultEntryAction.Actions)
                            },
                            enabled = actionsEnabled,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            ActionButtonContent(
                                iconRes = R.drawable.ic_actions,
                                textRes = R.string.actions,
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                onResultEntryAction(ResultEntryAction.Rescan)
                            },
                            enabled = actionsEnabled,
                            modifier = Modifier.weight(1f).heightIn(min = RESULT_ACTION_MIN_HEIGHT_DP.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 3.dp),
                        ) {
                            ResultActionButtonContent(
                                iconRes = if (onEditScan == null) R.drawable.ic_camera else R.drawable.ic_edit,
                                textRes = if (onEditScan == null) R.string.rescan else R.string.edit_scan,
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                onResultEntryAction(ResultEntryAction.SignOrStamp)
                            },
                            enabled = actionsEnabled && result.canAddVisualMark,
                            modifier = Modifier.weight(1f).heightIn(min = RESULT_ACTION_MIN_HEIGHT_DP.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 3.dp),
                        ) {
                            ResultActionButtonContent(
                                iconRes = R.drawable.ic_signature,
                                textRes = R.string.sign_or_stamp,
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                onResultEntryAction(ResultEntryAction.Actions)
                            },
                            enabled = actionsEnabled,
                            modifier = Modifier.weight(1f).heightIn(min = RESULT_ACTION_MIN_HEIGHT_DP.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 3.dp),
                        ) {
                            ResultActionButtonContent(
                                iconRes = R.drawable.ic_actions,
                                textRes = R.string.actions,
                            )
                        }
                    }
                }
            }
            item {
                Button(
                    onClick = { onSharePdf?.invoke() },
                    enabled = onSharePdf != null && actionsEnabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    ActionButtonContent(
                        iconRes = R.drawable.ic_share,
                        textRes = R.string.send_pdf,
                    )
                }
            }
            item {
                SecondaryResultActions(
                    stacked = stackSecondaryActions,
                    enabled = actionsEnabled,
                    onShareImages = onShareImages,
                    onPrint = onPrint,
                )
            }
            item {
                FileDetailsHeader(
                    scan = scan,
                    expanded = fileDetailsExpanded,
                    enabled = actionsEnabled,
                    onClick = { onFileDetailsChange(!fileDetailsExpanded) },
                )
                if (fileDetailsExpanded) {
                    Spacer(Modifier.height(12.dp))
                    FileDetails(
                        scan = scan,
                        cachedImageDimensions = cachedImageDimensions,
                        saveTargets = saveTargets,
                        saveInProgress = result.outputSaveInProgress,
                        outputChangeInProgress = result.outputChangeInProgress,
                        onSaveNow = { showSaveDialog = true },
                        onChangePdfSize = { showPdfSizeDialog = true },
                        onChangePdfLocation = onChangePdfLocation,
                        onChangeImageSize = { showImageSizeDialog = true },
                        onChangeImageFormat = { showImageFormatDialog = true },
                        onChangeImageLocation = onChangeImageLocation,
                        onAcknowledgeUnknownOutput = { showUnknownOutputDialog = true },
                    )
                }
            }
            item { Spacer(Modifier.height(4.dp)) }
        }
    }
    if (showSaveDialog) {
        SaveNowDialog(
            targets = saveTargets,
            onDismiss = { showSaveDialog = false },
            onSave = { target ->
                showSaveDialog = false
                onSaveNow(target)
            },
        )
    }
    if (showPdfSizeDialog) {
        PdfSizeTargetDialog(
            current = scan.cached.pdfSizeTarget,
            onDismiss = { showPdfSizeDialog = false },
            onSelect = { target ->
                showPdfSizeDialog = false
                onChangePdfSize(target)
            },
        )
    }
    if (showImageSizeDialog) {
        ImageSizeDialog(
            current = imageExportOptionsForChange(scan),
            onDismiss = { showImageSizeDialog = false },
            onSelect = { preset, custom ->
                showImageSizeDialog = false
                onChangeImageSize(preset, custom)
            },
        )
    }
    if (showImageFormatDialog) {
        ImageFormatDialog(
            current = imageExportOptionsForChange(scan)?.format,
            onDismiss = { showImageFormatDialog = false },
            onSelect = { format ->
                showImageFormatDialog = false
                onChangeImageFormat(format)
            },
        )
    }
    if (showUnknownOutputDialog) {
        UnknownOutputAcknowledgementDialog(
            onDismiss = { showUnknownOutputDialog = false },
            onConfirm = {
                showUnknownOutputDialog = false
                confirmedUnknownOutputAcknowledgement(scan, confirmed = true)
                    ?.let(onAcknowledgeUnknownOutput)
            },
        )
    }
    if (showFullscreen) {
        FullscreenPreviewDialog(
            pages = scan.cached.pages,
            initialPageIndex = selectedPageIndex,
            onDismiss = { showFullscreen = false },
            onSelectPage = onSelectPage,
            onLoadThumbnail = onLoadThumbnail,
            onLoadPreview = onLoadResultPreview,
        )
    }
    if (showDocumentActions) {
        DocumentActionPickerDialog(
            onDismiss = { showDocumentActions = false },
            onSelect = { action ->
                showDocumentActions = false
                onRunDocumentAction(action)
            },
        )
    }
    result.documentActionState?.let { actionState ->
        DocumentActionStateDialog(
            state = actionState,
            onDismiss = onDismissDocumentAction,
            onExportText = onExportDocumentText,
            onOpenUrl = onOpenDocumentActionUrl,
        )
    }
}

@Composable
private fun ResultPagerPage(
    page: File,
    pageIndex: Int,
    pageCount: Int,
    selectedPageIndex: Int,
    selectedPreview: Bitmap?,
    selectedPreviewLoading: Boolean,
    enabled: Boolean,
    onOpenFullscreen: () -> Unit,
    onLoadPreview: suspend (File, Int) -> Bitmap?,
) {
    val isSelected = pageIndex == selectedPageIndex
    val ownedPreview =
        if (isSelected) null else ownedResultPreview(page, 1024, onLoadPreview)
    val preview = if (isSelected) selectedPreview else ownedPreview?.bitmap
    val loading = if (isSelected) selectedPreviewLoading else ownedPreview?.loading == true
    val pageDescription = stringResource(R.string.page_position, pageIndex + 1, pageCount)
    Surface(
        onClick = onOpenFullscreen,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when {
                loading -> CircularProgressIndicator()
                preview == null -> Text(stringResource(R.string.preview_unavailable))
                else ->
                    Image(
                        bitmap = preview.asImageBitmap(),
                        contentDescription =
                            stringResource(R.string.open_fullscreen_preview, pageDescription),
                        modifier = Modifier.fillMaxSize().padding(4.dp),
                        contentScale = ContentScale.Fit,
                    )
            }
        }
    }
}

@Composable
private fun FullscreenPreviewDialog(
    pages: List<File>,
    initialPageIndex: Int,
    onDismiss: () -> Unit,
    onSelectPage: (Int) -> Unit,
    onLoadThumbnail: suspend (File) -> Bitmap?,
    onLoadPreview: suspend (File, Int) -> Bitmap?,
) {
    var pageIndex by rememberSaveable(pages, initialPageIndex) {
        mutableIntStateOf(fullscreenPageIndex(initialPageIndex, pages.size))
    }
    val page = pages.getOrNull(pageIndex)
    val preview = ownedResultPreview(page, 2048, onLoadPreview)
    var scale by remember(page) { mutableFloatStateOf(1f) }
    var offset by remember(page) { mutableStateOf(Offset.Zero) }
    val transformableState =
        rememberTransformableState { _, zoomChange, panChange, _ ->
            val nextScale = (scale * zoomChange).coerceIn(1f, 5f)
            scale = nextScale
            offset = if (nextScale == 1f) Offset.Zero else offset + panChange
        }
    val pagePosition =
        stringResource(
            R.string.page_position,
            pageIndex + 1,
            pages.size.coerceAtLeast(1),
        )
    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnClickOutside = false,
            ),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier =
                    Modifier.fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(horizontal = 12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        pagePosition,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = stringResource(R.string.close_fullscreen_preview),
                        )
                    }
                }
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    if (preview.loading) {
                        CircularProgressIndicator()
                    } else if (preview.bitmap == null) {
                        Text(stringResource(R.string.preview_unavailable))
                    } else {
                        Image(
                            bitmap = preview.bitmap.asImageBitmap(),
                            contentDescription = pagePosition,
                            modifier =
                                Modifier.fillMaxSize()
                                    .pointerInput(page) {
                                        detectTapGestures(
                                            onDoubleTap = {
                                                scale = 1f
                                                offset = Offset.Zero
                                            },
                                        )
                                    }.transformable(transformableState)
                                    .graphicsLayer(
                                        scaleX = scale,
                                        scaleY = scale,
                                        translationX = offset.x,
                                        translationY = offset.y,
                                    ),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }
                if (pages.size > 1) {
                    ResultPageStrip(
                        pages = pages,
                        selectedPageIndex = pageIndex,
                        enabled = true,
                        onSelectPage = { selected ->
                            pageIndex = fullscreenPageIndex(selected, pages.size)
                            onSelectPage(pageIndex)
                        },
                        onLoadThumbnail = onLoadThumbnail,
                    )
                }
                Text(
                    stringResource(R.string.fullscreen_preview_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
            }
        }
    }
}

private data class BitmapPreviewState(
    val bitmap: Bitmap? = null,
    val loading: Boolean = true,
)

@Composable
private fun ownedResultPreview(
    page: File?,
    maxSize: Int,
    onLoad: suspend (File, Int) -> Bitmap?,
): BitmapPreviewState {
    val preview by produceState(BitmapPreviewState(), page, maxSize) {
        val bitmap =
            if (page == null) {
                null
            } else {
                try {
                    onLoad(page, maxSize)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    null
                }
            }
        value = BitmapPreviewState(bitmap = bitmap, loading = false)
        awaitDispose { bitmap?.recycle() }
    }
    return preview
}

@Composable
private fun DocumentActionPickerDialog(
    onDismiss: () -> Unit,
    onSelect: (DocumentAction) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.document_actions)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.document_actions_on_device),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.document_actions_model_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DocumentActionPickerRow(
                    iconRes = R.drawable.ic_text,
                    labelRes = R.string.extract_text,
                    scopeRes = R.string.all_pages,
                    onClick = { onSelect(DocumentAction.ExtractText) },
                )
                DocumentActionPickerRow(
                    iconRes = R.drawable.ic_qr_code,
                    labelRes = R.string.detect_codes,
                    scopeRes = R.string.selected_page,
                    onClick = { onSelect(DocumentAction.DetectCodes) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

@Composable
private fun DocumentActionPickerRow(
    iconRes: Int,
    labelRes: Int,
    scopeRes: Int,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(labelRes), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(scopeRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DocumentActionStateDialog(
    state: DocumentActionState,
    onDismiss: () -> Unit,
    onExportText: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val context = LocalContext.current
    val truncatedMessage = stringResource(R.string.document_action_truncated)
    val clipboardLabel = stringResource(R.string.document_action_clip_label)
    val qrCodeLabel = stringResource(R.string.qr_code)
    val barcodeLabel = stringResource(R.string.barcode)
    val noTextFound = stringResource(R.string.no_text_found)
    val maxContentHeight =
        with(LocalDensity.current) {
            (LocalWindowInfo.current.containerSize.height * 0.55f).toDp()
        }.coerceAtLeast(180.dp)
    val title =
        when (state) {
            is DocumentActionState.Processing ->
                when (state.action) {
                    DocumentAction.ExtractText -> stringResource(R.string.extracting_text)
                    DocumentAction.DetectCodes -> stringResource(R.string.detecting_codes)
                }
            is DocumentActionState.Completed ->
                when (state.output) {
                    is DocumentActionOutput.Text -> stringResource(R.string.extracted_text)
                    is DocumentActionOutput.Codes -> stringResource(R.string.detected_codes)
                }
            is DocumentActionState.Exporting -> stringResource(R.string.exporting_text)
            is DocumentActionState.Failed -> stringResource(R.string.document_actions)
        }
    val copyText =
        when (state) {
            is DocumentActionState.Completed ->
                when (val output = state.output) {
                    is DocumentActionOutput.Text -> output.value.takeIf { it.isNotEmpty() }
                    is DocumentActionOutput.Codes ->
                        output.values.joinToString("\n\n") { code ->
                            code.openableHttpUrl
                                ?.takeIf { it != code.value }
                                ?.let { url -> "${code.value}\n$url" }
                                ?: code.value
                        }.takeIf { it.isNotEmpty() }
                }
            else -> null
        }
    val completedCodes =
        (state as? DocumentActionState.Completed)?.output as? DocumentActionOutput.Codes
    val openableUrl = completedCodes?.values?.singleOrNull()?.openableHttpUrl
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            when (state) {
                is DocumentActionState.Processing,
                is DocumentActionState.Exporting,
                ->
                    Box(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                is DocumentActionState.Completed ->
                    Column(
                        modifier =
                            Modifier.heightIn(max = maxContentHeight)
                                .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        SelectionContainer {
                            when (val output = state.output) {
                                is DocumentActionOutput.Text ->
                                    Text(
                                        buildString {
                                            append(
                                                output.value.ifEmpty {
                                                    noTextFound
                                                },
                                            )
                                            if (output.truncated) {
                                                append("\n\n")
                                                append(truncatedMessage)
                                            }
                                        },
                                    )
                                is DocumentActionOutput.Codes ->
                                    if (output.values.isEmpty()) {
                                        Text(stringResource(R.string.no_codes_found))
                                    } else {
                                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            output.values.forEach { code ->
                                                Column {
                                                    Text(
                                                        when (code.kind) {
                                                            DetectedCodeKind.QrCode -> qrCodeLabel
                                                            DetectedCodeKind.Barcode -> barcodeLabel
                                                        },
                                                        style = MaterialTheme.typography.labelMedium,
                                                        color =
                                                            MaterialTheme.colorScheme
                                                                .onSurfaceVariant,
                                                    )
                                                    Text(code.value)
                                                    val url = code.openableHttpUrl
                                                    if (url != null && url != code.value) {
                                                        Text(
                                                            stringResource(R.string.web_link),
                                                            style = MaterialTheme.typography.labelMedium,
                                                            color =
                                                                MaterialTheme.colorScheme
                                                                    .onSurfaceVariant,
                                                            modifier = Modifier.padding(top = 6.dp),
                                                        )
                                                        Text(url)
                                                    }
                                                }
                                            }
                                        }
                                    }
                            }
                        }
                        state.textExportStatus?.let { status ->
                            Text(
                                stringResource(
                                    when (status) {
                                        DocumentTextExportStatus.Saved -> R.string.text_export_saved
                                        DocumentTextExportStatus.Failed -> R.string.text_export_failed
                                    },
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color =
                                    if (status == DocumentTextExportStatus.Saved) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                    }
                is DocumentActionState.Failed -> Text(state.message.resolve())
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.End,
            ) {
                if (
                    state is DocumentActionState.Completed &&
                        state.output is DocumentActionOutput.Text &&
                        copyText != null
                ) {
                    Button(
                        onClick = onExportText,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.export_text))
                    }
                }
                if (openableUrl != null) {
                    Button(
                        onClick = { onOpenUrl(openableUrl) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.open_link))
                    }
                }
                Row {
                    if (state is DocumentActionState.Completed && copyText != null) {
                        TextButton(
                            onClick = {
                                copySensitiveDocumentActionResult(
                                    context = context,
                                    label = clipboardLabel,
                                    value = copyText,
                                )
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.copy))
                        }
                    }
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.close))
                    }
                }
            }
        },
    )
}

private fun copySensitiveDocumentActionResult(
    context: android.content.Context,
    label: String,
    value: String,
) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(label, value)
    val sensitive = documentClipboardSensitiveExtra()
    clip.description.extras =
        PersistableBundle().apply {
            putBoolean(sensitive.key, sensitive.value)
        }
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
}

@Composable
private fun ResultPageStrip(
    pages: List<File>,
    selectedPageIndex: Int,
    enabled: Boolean,
    onSelectPage: (Int) -> Unit,
    onLoadThumbnail: suspend (File) -> Bitmap?,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(
            items = pages,
            key = { _, page -> page.path },
        ) { index, page ->
            val isSelected = index == selectedPageIndex
            val pageDescription = stringResource(R.string.page_position, index + 1, pages.size)
            Surface(
                onClick = { onSelectPage(index) },
                enabled = enabled,
                shape = MaterialTheme.shapes.small,
                color =
                    if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                border =
                    BorderStroke(
                        width = if (isSelected) 2.dp else 1.dp,
                        color =
                            if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                    ),
                modifier =
                    Modifier.semantics(mergeDescendants = true) {
                        contentDescription = pageDescription
                        selected = isSelected
                    },
            ) {
                Column(
                    modifier = Modifier.padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    ResultPageThumbnail(page, onLoadThumbnail)
                    Text((index + 1).toString(), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun ResultPageThumbnail(
    page: File,
    onLoadThumbnail: suspend (File) -> Bitmap?,
) {
    val thumbnail by produceState<Bitmap?>(null, page) {
        value =
            try {
                onLoadThumbnail(page)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }
    }
    if (thumbnail == null) {
        Box(
            modifier = Modifier.width(64.dp).height(84.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.preview_unavailable),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    } else {
        Image(
            bitmap = requireNotNull(thumbnail).asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.width(64.dp).height(84.dp),
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
private fun ActionButtonContent(
    iconRes: Int,
    textRes: Int,
) {
    Icon(
        painter = painterResource(iconRes),
        contentDescription = null,
        modifier = Modifier.size(20.dp),
    )
    Spacer(Modifier.width(8.dp))
    Text(stringResource(textRes))
}

@Composable
private fun ResultActionButtonContent(
    iconRes: Int,
    textRes: Int,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.height(1.dp))
        Text(
            text = stringResource(textRes),
            maxLines = 2,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun SecondaryResultActions(
    stacked: Boolean,
    enabled: Boolean,
    onShareImages: (() -> Unit)?,
    onPrint: (() -> Unit)?,
) {
    if (stacked) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onShareImages?.invoke() },
                enabled = onShareImages != null && enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                ActionButtonContent(R.drawable.ic_image, R.string.send_images)
            }
            OutlinedButton(
                onClick = { onPrint?.invoke() },
                enabled = onPrint != null && enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                ActionButtonContent(R.drawable.ic_print, R.string.print_document)
            }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { onShareImages?.invoke() },
                enabled = onShareImages != null && enabled,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) {
                ActionButtonContent(R.drawable.ic_image, R.string.send_images)
            }
            OutlinedButton(
                onClick = { onPrint?.invoke() },
                enabled = onPrint != null && enabled,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) {
                ActionButtonContent(R.drawable.ic_print, R.string.print_document)
            }
        }
    }
}

@Composable
private fun FileDetailsHeader(
    scan: SavedScan,
    expanded: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val summary =
        stringResource(
            R.string.file_details_summary,
            Formatter.formatShortFileSize(context, scan.cached.pdf.length()),
            stringResource(
                if (scan.savedPdf == null) R.string.file_temporary else R.string.file_saved,
            ),
        )
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_file_details),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.file_details), style = MaterialTheme.typography.titleSmall)
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_expand_more),
                contentDescription =
                    stringResource(
                        if (expanded) {
                            R.string.collapse_file_details
                        } else {
                            R.string.expand_file_details
                        },
                    ),
                modifier = Modifier.size(24.dp).rotate(if (expanded) 180f else 0f),
            )
        }
    }
}

@Composable
private fun FileDetails(
    scan: SavedScan,
    cachedImageDimensions: List<Pair<Int, Int>>?,
    saveTargets: List<SaveNowTarget>,
    saveInProgress: Boolean,
    outputChangeInProgress: Boolean,
    onSaveNow: () -> Unit,
    onChangePdfSize: () -> Unit,
    onChangePdfLocation: () -> Unit,
    onChangeImageSize: () -> Unit,
    onChangeImageFormat: () -> Unit,
    onChangeImageLocation: () -> Unit,
    onAcknowledgeUnknownOutput: () -> Unit,
) {
    val context = LocalContext.current
    val controls = fileDetailControls(scan)
    val configuration = LocalConfiguration.current
    val availableWidthDp =
        with(LocalDensity.current) {
            LocalWindowInfo.current.containerSize.width.toDp().value.toInt()
        }
    val stackFileDetailControls =
        availableWidthDp < 360 || configuration.fontScale >= 1.3f
    val pdfLocation =
        when {
            scan.savedPdf == null -> stringResource(R.string.file_not_saved)
            scan.savedPdf.authority == MediaStore.AUTHORITY -> stringResource(R.string.downloads)
            else -> stringResource(R.string.selected_folder)
        }
    val pdfStatus =
        stringResource(
            if (scan.savedPdf == null) R.string.file_temporary else R.string.file_saved,
        )
    val imagesLocation = imageLocationLabel(scan)
    val imageStatus =
        when {
            scan.galleryPages.isEmpty() -> stringResource(R.string.file_temporary)
            scan.galleryPages.size == scan.cached.pages.size -> stringResource(R.string.file_saved)
            else ->
                stringResource(
                    R.string.saved_pages,
                    scan.galleryPages.size,
                    scan.cached.pages.size,
                )
        }
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (scan.unknownOutputCreateAcknowledgement != null) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        stringResource(R.string.unknown_output_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        stringResource(R.string.unknown_output_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    TextButton(
                        onClick = onAcknowledgeUnknownOutput,
                        enabled = !outputChangeInProgress,
                        modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.review_output_warning))
                    }
                }
            }
        }
        if (scan.warnings.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().semantics {
                    liveRegion = LiveRegionMode.Polite
                },
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                scan.warnings.forEach { warning ->
                    Text(
                        warning.resolve(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        FileDetailSection(
            iconRes = R.drawable.ic_pdf,
            title = stringResource(R.string.pdf_document),
        ) {
            FileDetailRow(
                label = stringResource(R.string.format),
                value = stringResource(R.string.pdf_format),
            )
            FileDetailRow(
                label = stringResource(R.string.actual_size),
                value = Formatter.formatShortFileSize(context, scan.cached.pdf.length()),
            )
            FileDetailRow(
                label = stringResource(R.string.target_size),
                value = pdfSizeTargetLabel(scan.cached.pdfSizeTarget),
            )
            FileDetailRow(
                label = stringResource(R.string.status),
                value = pdfStatus,
            )
            FileDetailRow(
                label = stringResource(R.string.location),
                value = pdfLocation,
            )
            val pdfSizeEnabled =
                FileDetailControl.PdfSize in controls &&
                    !saveInProgress &&
                    !outputChangeInProgress
            val pdfLocationEnabled =
                FileDetailControl.PdfLocation in controls &&
                    !saveInProgress &&
                    !outputChangeInProgress
            if (stackFileDetailControls) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FileDetailActionButton(
                        textRes = R.string.change_size,
                        onClick = onChangePdfSize,
                        enabled = pdfSizeEnabled,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FileDetailActionButton(
                        textRes = R.string.change_location,
                        onClick = onChangePdfLocation,
                        enabled = pdfLocationEnabled,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FileDetailActionButton(
                        textRes = R.string.change_size,
                        onClick = onChangePdfSize,
                        enabled = pdfSizeEnabled,
                        modifier = Modifier.weight(1f),
                    )
                    FileDetailActionButton(
                        textRes = R.string.change_location,
                        onClick = onChangePdfLocation,
                        enabled = pdfLocationEnabled,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        FileDetailSection(
            iconRes = R.drawable.ic_image,
            title = stringResource(R.string.images),
        ) {
            FileDetailRow(
                label = stringResource(R.string.format),
                value = imageFormatLabel(scan),
            )
            FileDetailRow(
                label = stringResource(R.string.resolution),
                value = imageResolutionLabel(scan, cachedImageDimensions),
            )
            FileDetailRow(
                label = stringResource(R.string.actual_size),
                value =
                    Formatter.formatShortFileSize(
                        context,
                        savedImageBytes(scan),
                    ),
            )
            FileDetailRow(
                label = stringResource(R.string.status),
                value = imageStatus,
            )
            FileDetailRow(
                label = stringResource(R.string.location),
                value = imagesLocation,
            )
            val imageSizeEnabled =
                FileDetailControl.ImageSize in controls &&
                    !saveInProgress &&
                    !outputChangeInProgress
            val imageFormatEnabled =
                FileDetailControl.ImageFormat in controls &&
                    !saveInProgress &&
                    !outputChangeInProgress
            val imageLocationEnabled =
                FileDetailControl.ImageLocation in controls &&
                    !saveInProgress &&
                    !outputChangeInProgress
            if (stackFileDetailControls) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FileDetailActionButton(
                        textRes = R.string.change_size,
                        onClick = onChangeImageSize,
                        enabled = imageSizeEnabled,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FileDetailActionButton(
                        textRes = R.string.change_format,
                        onClick = onChangeImageFormat,
                        enabled = imageFormatEnabled,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FileDetailActionButton(
                        textRes = R.string.change_location,
                        onClick = onChangeImageLocation,
                        enabled = imageLocationEnabled,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FileDetailActionButton(
                            textRes = R.string.change_size,
                            onClick = onChangeImageSize,
                            enabled = imageSizeEnabled,
                            modifier = Modifier.weight(1f),
                        )
                        FileDetailActionButton(
                            textRes = R.string.change_location,
                            onClick = onChangeImageLocation,
                            enabled = imageLocationEnabled,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    FileDetailActionButton(
                        textRes = R.string.change_format,
                        onClick = onChangeImageFormat,
                        enabled = imageFormatEnabled,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        if (scan.savedPdf == null || scan.galleryPages.size != scan.cached.pages.size) {
            Text(
                stringResource(R.string.automatic_saving_hint),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (saveTargets.isNotEmpty()) {
            Button(
                onClick = onSaveNow,
                enabled = !saveInProgress,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(
                    stringResource(
                        if (saveInProgress) R.string.saving_now else R.string.save_now,
                    ),
                )
            }
        }
    }
}

@Composable
private fun FileDetailActionButton(
    textRes: Int,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        Text(
            text = stringResource(textRes),
            maxLines = 2,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun FileDetailSection(
    iconRes: Int,
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
                Text(title, style = MaterialTheme.typography.titleSmall)
            }
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun FileDetailRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1.2f),
        )
    }
}

@Composable
private fun imageLocationLabel(scan: SavedScan): String {
    if (scan.savedImages.isEmpty()) return stringResource(R.string.file_not_saved)
    val trees = scan.savedImages.map(SavedImageOutput::treeUri).distinct()
    return when {
        trees == listOf(null) -> stringResource(R.string.gallery)
        trees.size == 1 -> stringResource(R.string.selected_folder)
        else -> stringResource(R.string.multiple_locations)
    }
}

@Composable
private fun imageFormatLabel(scan: SavedScan): String {
    val mimeTypes = scan.savedImages.mapNotNull(SavedImageOutput::mimeType).distinct()
    if (
        mimeTypes.isNotEmpty() &&
            scan.savedImages.all { it.mimeType != null }
    ) {
        return when {
            mimeTypes.size > 1 -> stringResource(R.string.mixed_formats)
            mimeTypes.single().equals("image/jpeg", ignoreCase = true) ->
                stringResource(R.string.image_format_jpeg)
            mimeTypes.single().equals("image/png", ignoreCase = true) ->
                stringResource(R.string.image_format_png)
            else -> stringResource(R.string.not_available)
        }
    }
    val formats = scan.savedImages.mapNotNull(SavedImageOutput::format).distinct()
    val format = formats.singleOrNull()
    return when {
        formats.size > 1 -> stringResource(R.string.mixed_formats)
        format == ImageExportFormat.Jpeg -> stringResource(R.string.image_format_jpeg)
        format == ImageExportFormat.Png -> stringResource(R.string.image_format_png)
        scan.savedImages.isNotEmpty() &&
            scan.savedImages.any { it.format == null } -> stringResource(R.string.not_available)
        scan.cached.pages.all { it.extension.equals("png", ignoreCase = true) } ->
            stringResource(R.string.image_format_png)
        scan.cached.pages.all {
            it.extension.equals("jpg", ignoreCase = true) ||
                it.extension.equals("jpeg", ignoreCase = true)
        } -> stringResource(R.string.image_format_jpeg)
        scan.cached.pages.isNotEmpty() -> stringResource(R.string.mixed_formats)
        else -> stringResource(R.string.not_available)
    }
}

@Composable
private fun imageResolutionLabel(
    scan: SavedScan,
    cachedImageDimensions: List<Pair<Int, Int>>?,
): String {
    val savedDimensions =
        scan.savedImages.map { output ->
            val width = output.width ?: return@map null
            val height = output.height ?: return@map null
            width to height
        }
    val dimensions =
        exactImageDimensions(
            pageCount = scan.cached.pages.size,
            savedDimensions = savedDimensions,
            cachedDimensions = cachedImageDimensions,
        )?.distinct() ?: return stringResource(R.string.not_available)
    return when {
        dimensions.size == 1 -> {
            val (width, height) = dimensions.single()
            stringResource(R.string.image_resolution_value, width, height)
        }
        else -> stringResource(R.string.multiple_resolutions)
    }
}

private fun savedImageBytes(scan: SavedScan): Long {
    val lengths = scan.savedImages.mapNotNull(SavedImageOutput::byteLength)
    return if (lengths.size == scan.savedImages.size && lengths.isNotEmpty()) {
        lengths.fold(0L) { total, bytes ->
            if (Long.MAX_VALUE - total < bytes) Long.MAX_VALUE else total + bytes
        }
    } else {
        totalFileBytes(scan.cached.pages)
    }
}

@Composable
private fun PdfSizeTargetDialog(
    current: PdfSizeTarget,
    onDismiss: () -> Unit,
    onSelect: (PdfSizeTarget) -> Unit,
) {
    val currentCustom = current as? PdfSizeTarget.Custom
    val initialUnit = preferredPdfSizeUnit(currentCustom)
    var selectedWire by rememberSaveable(current.wireValue) {
        mutableStateOf(current.wireValue)
    }
    var customMode by rememberSaveable(current.wireValue) {
        mutableStateOf(current is PdfSizeTarget.Custom)
    }
    var customUnit by rememberSaveable(current.wireValue) {
        mutableStateOf(initialUnit)
    }
    var customInput by rememberSaveable(current.wireValue) {
        mutableStateOf(formatCustomPdfSizeInput(currentCustom, initialUnit))
    }
    val customKilobytes = parseCustomPdfKilobytes(customInput, customUnit)
    val selected =
        if (customMode) {
            customKilobytes?.let(PdfSizeTarget::Custom)
        } else {
            decodePdfSizeTarget(selectedWire)
        }
    val choices: List<PdfSizeTarget?> = PdfSizeTarget.presets + null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pdf_size)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.current_pdf_size, pdfSizeTargetLabel(current)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    choices.chunked(3).forEach { rowChoices ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            rowChoices.forEach { target ->
                                FilterChip(
                                    selected =
                                        if (target == null) {
                                            customMode
                                        } else {
                                            !customMode && target.wireValue == selectedWire
                                        },
                                    onClick = {
                                        customMode = target == null
                                        target?.let { selectedWire = it.wireValue }
                                    },
                                    label = {
                                        Text(
                                            if (target == null) {
                                                customKilobytes
                                                    ?.let {
                                                        pdfSizeTargetLabel(
                                                            PdfSizeTarget.Custom(it),
                                                        )
                                                    }
                                                    ?: stringResource(R.string.pdf_size_custom)
                                            } else {
                                                pdfSizeTargetLabel(target)
                                            },
                                        )
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            repeat(3 - rowChoices.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                if (customMode) {
                    CustomPdfSizeInput(
                        value = customInput,
                        onValueChange = { customInput = it },
                        unit = customUnit,
                        onUnitChange = { nextUnit ->
                            val value = customKilobytes
                            customUnit = nextUnit
                            customInput = formatCustomPdfSizeInput(value, nextUnit)
                        },
                        valid = customKilobytes != null,
                    )
                }
                Text(
                    stringResource(R.string.pdf_size_current_document_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selected?.let(onSelect) },
                enabled = selected != null && selected != current,
            ) {
                Text(stringResource(R.string.apply_appearance))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun CustomPdfSizeInput(
    value: String,
    onValueChange: (String) -> Unit,
    unit: PdfSizeUnit,
    onUnitChange: (PdfSizeUnit) -> Unit,
    valid: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(stringResource(R.string.pdf_size_custom_field)) },
            isError = value.isNotEmpty() && !valid,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PdfSizeUnit.entries.forEach { option ->
                FilterChip(
                    selected = unit == option,
                    onClick = { onUnitChange(option) },
                    label = {
                        Text(
                            stringResource(
                                if (option == PdfSizeUnit.Kilobytes) {
                                    R.string.pdf_size_unit_kb
                                } else {
                                    R.string.pdf_size_unit_mb
                                },
                            ),
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Text(
            stringResource(R.string.pdf_size_custom_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun preferredPdfSizeUnit(target: PdfSizeTarget.Custom?): PdfSizeUnit =
    if (target != null && target.kilobytes % 1_000 == 0) {
        PdfSizeUnit.Megabytes
    } else {
        PdfSizeUnit.Kilobytes
    }

private fun formatCustomPdfSizeInput(
    target: PdfSizeTarget.Custom?,
    unit: PdfSizeUnit,
): String = formatCustomPdfSizeInput(target?.kilobytes, unit)

private fun formatCustomPdfSizeInput(
    kilobytes: Int?,
    unit: PdfSizeUnit,
): String =
    when {
        kilobytes == null -> ""
        unit == PdfSizeUnit.Kilobytes -> kilobytes.toString()
        kilobytes % 1_000 == 0 -> (kilobytes / 1_000).toString()
        else -> ""
    }

@Composable
private fun ImageSizeDialog(
    current: ImageExportOptions?,
    onDismiss: () -> Unit,
    onSelect: (ImageSizePreset, Int?) -> Unit,
) {
    var presetName by rememberSaveable(current) {
        mutableStateOf((current?.sizePreset ?: ImageSizePreset.Original).name)
    }
    var customInput by rememberSaveable(current) {
        mutableStateOf(current?.customMaxDimension?.toString().orEmpty())
    }
    val preset = ImageSizePreset.entries.firstOrNull { it.name == presetName }
        ?: ImageSizePreset.Original
    val custom = parseCustomImageDimension(customInput)
    val selectionValid = preset != ImageSizePreset.Custom || custom != null
    val selectedCustom = custom.takeIf { preset == ImageSizePreset.Custom }
    val changed =
        current == null ||
            preset != current.sizePreset ||
            selectedCustom != current.customMaxDimension
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.image_size)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ImageSizePreset.entries.forEach { candidate ->
                    Row(
                        modifier =
                            Modifier.fillMaxWidth()
                                .selectable(
                                    selected = preset == candidate,
                                    onClick = { presetName = candidate.name },
                                    role = Role.RadioButton,
                                ).heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = preset == candidate,
                            onClick = null,
                        )
                        Text(imageSizePresetLabel(candidate))
                    }
                }
                if (preset == ImageSizePreset.Custom) {
                    OutlinedTextField(
                        value = customInput,
                        onValueChange = { customInput = it },
                        label = { Text(stringResource(R.string.image_size_custom_field)) },
                        supportingText = { Text(stringResource(R.string.image_size_custom_hint)) },
                        isError = customInput.isNotEmpty() && custom == null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSelect(preset, selectedCustom) },
                enabled = selectionValid && changed,
            ) {
                Text(stringResource(R.string.apply_appearance))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun ImageFormatDialog(
    current: ImageExportFormat?,
    onDismiss: () -> Unit,
    onSelect: (ImageExportFormat) -> Unit,
) {
    var selectedWire by rememberSaveable(current) {
        mutableStateOf((current ?: ImageExportFormat.Original).wireValue)
    }
    val selected =
        ImageExportFormat.entries.firstOrNull { it.wireValue == selectedWire }
            ?: ImageExportFormat.Original
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.image_format)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ImageExportFormat.entries.forEach { format ->
                    Row(
                        modifier =
                            Modifier.fillMaxWidth()
                                .selectable(
                                    selected = selected == format,
                                    onClick = { selectedWire = format.wireValue },
                                    role = Role.RadioButton,
                                ).heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == format, onClick = null)
                        Text(imageExportFormatLabel(format))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSelect(selected) },
                enabled = current == null || selected != current,
            ) {
                Text(stringResource(R.string.apply_appearance))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun UnknownOutputAcknowledgementDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.unknown_output_confirm_title)) },
        text = { Text(stringResource(R.string.unknown_output_confirm_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.unknown_output_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun SaveNowDialog(
    targets: List<SaveNowTarget>,
    onDismiss: () -> Unit,
    onSave: (SaveNowTarget) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.save_now)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                targets.forEach { target ->
                    OutlinedButton(
                        onClick = { onSave(target) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(
                            stringResource(
                                when (target) {
                                    SaveNowTarget.Pdf -> R.string.save_pdf_now
                                    SaveNowTarget.Images -> R.string.save_images_now
                                    SaveNowTarget.Both -> R.string.save_pdf_and_images_now
                                },
                            ),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun RecentScreen(
    state: ScreenState.Recent,
    onNewScan: () -> Unit,
    onOpen: (String) -> Unit,
    onSharePdf: (String) -> Unit,
    onDelete: (OutputDeleteRequest) -> Unit,
    onLoadThumbnail: suspend (File) -> Bitmap?,
    onSettings: () -> Unit,
) {
    MainScaffold(
        onRecent = null,
        onSettings = onSettings,
        titleRes = R.string.recent_scans,
        onScan = onNewScan,
        actionsEnabled = !state.deletionInProgress,
    ) { modifier ->
        LazyColumn(
            modifier = modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.message?.let { message ->
                item {
                    Text(
                        message.resolve(),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
            if (state.deletionInProgress) {
                item {
                    Row(
                        modifier =
                            Modifier.fillMaxWidth().semantics {
                                liveRegion = LiveRegionMode.Polite
                            },
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.deleting_scan))
                    }
                }
            }
            if (state.scans.isEmpty()) {
                item { Text(stringResource(R.string.recent_scans_empty)) }
            } else {
                items(state.scans, key = RecentScan::cacheId) { scan ->
                    RecentScanRow(
                        scan = scan,
                        onOpen = onOpen,
                        onSharePdf = onSharePdf,
                        onDelete = onDelete,
                        onLoadThumbnail = onLoadThumbnail,
                        deletionInProgress = state.deletionInProgress,
                    )
                    HorizontalDivider()
                }
            }
            item {
                Button(
                    onClick = onNewScan,
                    enabled = !state.deletionInProgress,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    Text(stringResource(R.string.new_scan))
                }
            }
            item {
                Text(
                    stringResource(R.string.recent_scans_temporary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { Spacer(Modifier.height(4.dp)) }
        }
    }
}

@Composable
private fun RecentScanRow(
    scan: RecentScan,
    onOpen: (String) -> Unit,
    onSharePdf: (String) -> Unit,
    onDelete: (OutputDeleteRequest) -> Unit,
    onLoadThumbnail: suspend (File) -> Bitmap?,
    deletionInProgress: Boolean,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val deleteTargets =
        recentDeleteTargets(
            metadataValid = scan.entryId != null,
            hasPdf = scan.hasSavedPdf,
            savedImageCount = scan.savedImageCount,
            removeRecentPending = scan.removeRecentPending,
        )
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val formattedDate =
        remember(scan.createdAt, locale) {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
                .format(Date.from(scan.createdAt))
        }
    val handleClick = { target: RecentRowTarget ->
        when (recentRowAction(target)) {
            RecentRowAction.Open -> onOpen(scan.cacheId)
            RecentRowAction.ShowMenu -> menuExpanded = true
        }
    }
    Surface(
        onClick = { handleClick(RecentRowTarget.Content) },
        enabled = !deletionInProgress,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RecentThumbnail(scan.firstPage, onLoadThumbnail)
            Column(modifier = Modifier.weight(1f)) {
                Text(scan.displayName, style = MaterialTheme.typography.titleMedium)
                Text(formattedDate, style = MaterialTheme.typography.bodySmall)
                Text(
                    "${pluralStringResource(R.plurals.page_count, scan.pageCount, scan.pageCount)} · " +
                        Formatter.formatShortFileSize(context, scan.pdfBytes),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Box {
                IconButton(
                    onClick = { handleClick(RecentRowTarget.Overflow) },
                    enabled = !deletionInProgress,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_more_vert),
                        contentDescription = stringResource(R.string.more_actions),
                        modifier = Modifier.size(24.dp),
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.open_scan)) },
                        enabled = !deletionInProgress,
                        onClick = {
                            menuExpanded = false
                            onOpen(scan.cacheId)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.send_pdf)) },
                        enabled = !deletionInProgress,
                        onClick = {
                            menuExpanded = false
                            onSharePdf(scan.cacheId)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete_scan)) },
                        enabled = !deletionInProgress,
                        onClick = {
                            menuExpanded = false
                            showDeleteDialog = true
                        },
                    )
                }
            }
        }
    }
    if (showDeleteDialog) {
        RecentDeleteDialog(
            targets = deleteTargets,
            removeRecentPending = scan.removeRecentPending,
            onDismiss = { showDeleteDialog = false },
            onDelete = { target ->
                showDeleteDialog = false
                onDelete(OutputDeleteRequest(scan.cacheId, scan.entryId, target))
            },
        )
    }
}

@Composable
private fun RecentDeleteDialog(
    targets: List<RecentDeleteTarget>,
    removeRecentPending: Boolean,
    onDismiss: () -> Unit,
    onDelete: (RecentDeleteTarget) -> Unit,
) {
    val legacy = targets == listOf(RecentDeleteTarget.RemoveFromRecent)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_scan)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (removeRecentPending) {
                    Text(stringResource(R.string.recent_delete_pending_hint))
                } else if (legacy) {
                    Text(stringResource(R.string.recent_delete_legacy_hint))
                } else {
                    Text(stringResource(R.string.recent_delete_saved_hint))
                }
                targets.forEach { target ->
                    OutlinedButton(
                        onClick = { onDelete(target) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(
                            stringResource(
                                when (target) {
                                    RecentDeleteTarget.Pdf -> R.string.delete_pdf
                                    RecentDeleteTarget.Images -> R.string.delete_images
                                    RecentDeleteTarget.Both -> R.string.delete_pdf_and_images
                                    RecentDeleteTarget.RemoveFromRecent -> R.string.remove_from_recent
                                },
                            ),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun RecentThumbnail(
    firstPage: File,
    onLoadThumbnail: suspend (File) -> Bitmap?,
) {
    val thumbnail by produceState<Bitmap?>(null, firstPage) {
        value =
            try {
                onLoadThumbnail(firstPage)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }
    }
    if (thumbnail == null) {
        Box(
            modifier = Modifier.width(72.dp).height(96.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.preview_unavailable))
        }
    } else {
        Image(
            bitmap = requireNotNull(thumbnail).asImageBitmap(),
            contentDescription = stringResource(R.string.recent_scan_preview),
            modifier = Modifier.width(72.dp).height(96.dp),
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
private fun MainScaffold(
    onRecent: (() -> Unit)?,
    onSettings: () -> Unit,
    titleRes: Int = R.string.app_name,
    onScan: (() -> Unit)? = null,
    actionsEnabled: Boolean = true,
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            CompactTopBar(
                title = stringResource(titleRes),
                onRecent = onRecent,
                onScan = onScan,
                onSettings = onSettings,
                actionsEnabled = actionsEnabled,
            )
        },
    ) { padding -> content(Modifier.fillMaxSize().padding(padding)) }
}

@Composable
private fun CompactTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    backEnabled: Boolean = true,
    onRecent: (() -> Unit)? = null,
    onScan: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
    actionsEnabled: Boolean = true,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column {
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    IconButton(
                        onClick = onBack,
                        enabled = backEnabled,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.back),
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                Text(
                    title,
                    modifier =
                        Modifier.weight(1f).padding(start = if (onBack == null) 16.dp else 0.dp),
                    style = MaterialTheme.typography.titleLarge,
                )
                if (onRecent != null) {
                    IconButton(
                        onClick = onRecent,
                        enabled = actionsEnabled,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_dashboard),
                            contentDescription = stringResource(R.string.open_recent_scans),
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                if (onScan != null) {
                    IconButton(
                        onClick = onScan,
                        enabled = actionsEnabled,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_camera),
                            contentDescription = stringResource(R.string.start_new_scan),
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                if (onSettings != null) {
                    IconButton(
                        onClick = onSettings,
                        enabled = actionsEnabled,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_settings),
                            contentDescription = stringResource(R.string.open_settings),
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    settings: AppSettings,
    language: AppLanguage,
    defaultEmailSubjects: Set<String>,
    onClose: () -> Unit,
    onSave: (AppSettings) -> Boolean,
    onLanguageChange: (AppLanguage) -> Unit,
    onPdfFolderSelected: (Uri, Int) -> UiMessage?,
    onPdfFolderCleared: () -> UiMessage?,
) {
    val folderPermissionMissing = UiMessage(R.string.folder_permission_missing)
    val folderPermissionFailed = UiMessage(R.string.folder_permission_failed)
    val folderClearFailed = UiMessage(R.string.folder_clear_failed)
    val settingsSaveFailed = UiMessage(R.string.settings_save_failed)
    var savePdf by rememberSaveable { mutableStateOf(settings.savePdf) }
    var saveImages by rememberSaveable { mutableStateOf(settings.saveImages) }
    var albumName by rememberSaveable { mutableStateOf(settings.albumName) }
    var multipage by rememberSaveable { mutableStateOf(settings.multipage) }
    var allowGallery by rememberSaveable { mutableStateOf(settings.allowGallery) }
    var emailSubject by rememberSaveable { mutableStateOf(settings.emailSubject) }
    var emailBody by rememberSaveable { mutableStateOf(settings.emailBody) }
    var deletePdfAfterShare by rememberSaveable { mutableStateOf(settings.deletePdfAfterShare) }
    var deleteImagesAfterShare by rememberSaveable {
        mutableStateOf(settings.deleteImagesAfterShare)
    }
    var pdfTreeUri by rememberSaveable { mutableStateOf(settings.pdfTreeUri) }
    var pdfSizeTargetWire by rememberSaveable {
        mutableStateOf(settings.pdfSizeTarget.wireValue)
    }
    var languageDialogOpen by rememberSaveable { mutableStateOf(false) }
    var customPdfSizeDialogOpen by rememberSaveable { mutableStateOf(false) }
    var customPdfSizeInput by rememberSaveable { mutableStateOf("") }
    var customPdfSizeUnit by rememberSaveable { mutableStateOf(PdfSizeUnit.Kilobytes) }
    var appInfoExpanded by rememberSaveable { mutableStateOf(false) }
    var folderError by remember { mutableStateOf<UiMessage?>(null) }
    var settingsError by remember { mutableStateOf<UiMessage?>(null) }
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val supportNotice = stringResource(R.string.support_scanit_notice)
    val appVersionName =
        remember(context) {
            try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }

    fun persistSettings() {
        settingsError =
            try {
                val saved =
                    onSave(
                        AppSettings(
                            savePdf = savePdf,
                            saveImages = saveImages,
                            albumName = albumName,
                            multipage = multipage,
                            allowGallery = allowGallery,
                            emailSubject = emailSubject,
                            emailBody = emailBody,
                            pdfTreeUri = pdfTreeUri,
                            deletePdfAfterShare = deletePdfAfterShare,
                            deleteImagesAfterShare = deleteImagesAfterShare,
                            appearance = settings.appearance,
                            pdfSizeTarget = parsePdfSizeTarget(pdfSizeTargetWire),
                        ),
                    )
                settingsSaveFailed.takeUnless { saved }
            } catch (_: RuntimeException) {
                settingsSaveFailed
            }
    }

    if (languageDialogOpen) {
        AlertDialog(
            onDismissRequest = { languageDialogOpen = false },
            title = { Text(stringResource(R.string.choose_language)) },
            text = {
                Column {
                    AppLanguage.entries.forEach { option ->
                        Row(
                            modifier =
                                Modifier.fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .selectable(
                                        selected = language == option,
                                        role = Role.RadioButton,
                                        onClick = {
                                            languageDialogOpen = false
                                            onLanguageChange(option)
                                        },
                                    ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = language == option, onClick = null)
                            Text(appLanguageLabel(option), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { languageDialogOpen = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (customPdfSizeDialogOpen) {
        val customKilobytes = parseCustomPdfKilobytes(customPdfSizeInput, customPdfSizeUnit)
        AlertDialog(
            onDismissRequest = { customPdfSizeDialogOpen = false },
            title = { Text(stringResource(R.string.pdf_size_custom_title)) },
            text = {
                CustomPdfSizeInput(
                    value = customPdfSizeInput,
                    onValueChange = { customPdfSizeInput = it },
                    unit = customPdfSizeUnit,
                    onUnitChange = { nextUnit ->
                        val value = customKilobytes
                        customPdfSizeUnit = nextUnit
                        customPdfSizeInput = formatCustomPdfSizeInput(value, nextUnit)
                    },
                    valid = customKilobytes != null,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        customKilobytes?.let { kilobytes ->
                            pdfSizeTargetWire = PdfSizeTarget.Custom(kilobytes).wireValue
                            persistSettings()
                            customPdfSizeDialogOpen = false
                        }
                    },
                    enabled = customKilobytes != null,
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { customPdfSizeDialogOpen = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    LaunchedEffect(settings.emailSubject, defaultEmailSubjects) {
        emailSubject =
            localizedDefaultEmailSubject(
                current = emailSubject,
                targetDefault = settings.emailSubject,
                supportedDefaults = defaultEmailSubjects,
            )
    }

    val folderLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data
                val uri = data?.data
                val grantedFlags = (data?.flags ?: 0) and PDF_TREE_FLAGS
                if (uri == null || grantedFlags != PDF_TREE_FLAGS) {
                    folderError = folderPermissionMissing
                } else {
                    try {
                        folderError = onPdfFolderSelected(uri, grantedFlags)
                        pdfTreeUri = uri.toString()
                    } catch (_: SecurityException) {
                        folderError = folderPermissionFailed
                    } catch (_: IOException) {
                        folderError = folderPermissionFailed
                    } catch (_: RuntimeException) {
                        folderError = folderPermissionFailed
                    }
                }
            }
        }

    Scaffold(
        modifier = Modifier.imePadding(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            CompactTopBar(title = stringResource(R.string.settings), onBack = onClose)
        },
    ) { padding ->
        LazyColumn(
            modifier =
                Modifier.fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
                    .padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                OutlinedButton(
                    onClick = { languageDialogOpen = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.choose_language))
                        Text(
                            appLanguageLabel(language),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item { SectionTitle(stringResource(R.string.pdf_size)) }
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(PdfSizeTarget.presets) { target ->
                        FilterChip(
                            selected = target.wireValue == pdfSizeTargetWire,
                            onClick = {
                                pdfSizeTargetWire = target.wireValue
                                persistSettings()
                            },
                            label = { Text(pdfSizeTargetLabel(target)) },
                        )
                    }
                    item {
                        val selectedTarget = decodePdfSizeTarget(pdfSizeTargetWire)
                        FilterChip(
                            selected = selectedTarget is PdfSizeTarget.Custom,
                            onClick = {
                                val selectedCustom = selectedTarget as? PdfSizeTarget.Custom
                                customPdfSizeUnit = preferredPdfSizeUnit(selectedCustom)
                                customPdfSizeInput =
                                    formatCustomPdfSizeInput(selectedCustom, customPdfSizeUnit)
                                customPdfSizeDialogOpen = true
                            },
                            label = {
                                Text(
                                    if (selectedTarget is PdfSizeTarget.Custom) {
                                        pdfSizeTargetLabel(selectedTarget)
                                    } else {
                                        stringResource(R.string.pdf_size_custom)
                                    },
                                )
                            },
                        )
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.pdf_size_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { HorizontalDivider() }
            item { SectionTitle(stringResource(R.string.saving)) }
            item {
                SettingsSwitch(
                    label = stringResource(R.string.save_pdf),
                    checked = savePdf,
                    onCheckedChange = {
                        savePdf = it
                        persistSettings()
                    },
                )
            }
            item {
                SettingsSwitch(
                    label = stringResource(R.string.save_images),
                    checked = saveImages,
                    onCheckedChange = {
                        saveImages = it
                        persistSettings()
                    },
                )
            }
            item {
                Text(
                    stringResource(R.string.automatic_saving_scope),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                OutlinedTextField(
                    value = albumName,
                    onValueChange = {
                        albumName = it
                        persistSettings()
                    },
                    label = { Text(stringResource(R.string.album_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Text(
                    if (pdfTreeUri == null) {
                        stringResource(R.string.default_pdf_folder)
                    } else {
                        stringResource(R.string.custom_pdf_folder)
                    },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            folderError = null
                            folderLauncher.launch(
                                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                                    PDF_TREE_FLAGS or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
                                ),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.choose_folder))
                    }
                    OutlinedButton(
                        onClick = {
                            try {
                                folderError = onPdfFolderCleared()
                                pdfTreeUri = null
                            } catch (_: SecurityException) {
                                folderError = folderClearFailed
                            } catch (_: IOException) {
                                folderError = folderClearFailed
                            } catch (_: RuntimeException) {
                                folderError = folderClearFailed
                            }
                        },
                        enabled = pdfTreeUri != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.clear_folder))
                    }
                }
                folderError?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error) }
            }
            item { SectionTitle(stringResource(R.string.scanning)) }
            item {
                SettingsSwitch(
                    label = stringResource(R.string.multiple_pages),
                    checked = multipage,
                    onCheckedChange = {
                        multipage = it
                        persistSettings()
                    },
                )
            }
            item {
                SettingsSwitch(
                    label = stringResource(R.string.allow_gallery),
                    checked = allowGallery,
                    onCheckedChange = {
                        allowGallery = it
                        persistSettings()
                    },
                )
            }
            item { SectionTitle(stringResource(R.string.sharing)) }
            item {
                SettingsSwitch(
                    label = stringResource(R.string.delete_pdf_after_share),
                    checked = deletePdfAfterShare,
                    onCheckedChange = {
                        deletePdfAfterShare = it
                        persistSettings()
                    },
                )
            }
            item {
                SettingsSwitch(
                    label = stringResource(R.string.delete_images_after_share),
                    checked = deleteImagesAfterShare,
                    onCheckedChange = {
                        deleteImagesAfterShare = it
                        persistSettings()
                    },
                )
            }
            item {
                Text(
                    stringResource(R.string.delete_after_share_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                OutlinedTextField(
                    value = emailSubject,
                    onValueChange = {
                        emailSubject = it
                        persistSettings()
                    },
                    label = { Text(stringResource(R.string.email_subject)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = emailBody,
                    onValueChange = {
                        emailBody = it
                        persistSettings()
                    },
                    label = { Text(stringResource(R.string.email_body)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                settingsError?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error) }
                Button(
                    onClick = {
                        Toast.makeText(context, supportNotice, Toast.LENGTH_SHORT).show()
                        uriHandler.openUri(SUPPORT_URL)
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    border = BorderStroke(1.dp, Color(0xFF111111)),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFFDD00),
                            contentColor = Color(0xFF111111),
                        ),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_coffee),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.support_scanit))
                }
            }
            item {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        Row(
                            modifier =
                                Modifier.fillMaxWidth()
                                    .clickable { appInfoExpanded = !appInfoExpanded }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_file_details),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.app_info),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                appVersionName?.let { version ->
                                    Text(
                                        stringResource(R.string.app_version, version),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Icon(
                                painter = painterResource(R.drawable.ic_expand_more),
                                contentDescription =
                                    stringResource(
                                        if (appInfoExpanded) {
                                            R.string.collapse_app_info
                                        } else {
                                            R.string.expand_app_info
                                        },
                                    ),
                                modifier =
                                    Modifier.size(24.dp)
                                        .rotate(if (appInfoExpanded) 180f else 0f),
                            )
                        }
                        if (appInfoExpanded) {
                            HorizontalDivider()
                            TextButton(
                                onClick = { uriHandler.openUri(PRIVACY_POLICY_URL) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.privacy_policy))
                            }
                            TextButton(
                                onClick = { uriHandler.openUri(THIRD_PARTY_NOTICES_URL) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.third_party_notices))
                            }
                            TextButton(
                                onClick = { uriHandler.openUri(SOURCE_CODE_URL) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.source_code))
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(4.dp)) }
        }
    }
}

@Composable
private fun scanColorModeLabel(mode: ScanColorMode): String =
    stringResource(
        when (mode) {
            ScanColorMode.Natural -> R.string.filter_natural
            ScanColorMode.Color -> R.string.filter_color
            ScanColorMode.LightText -> R.string.filter_light_text
            ScanColorMode.Grayscale -> R.string.filter_grayscale
            ScanColorMode.BlackWhite -> R.string.filter_black_white
            ScanColorMode.Whiteboard -> R.string.filter_whiteboard
        },
    )

@Composable
private fun imageSizePresetLabel(preset: ImageSizePreset): String =
    stringResource(
        when (preset) {
            ImageSizePreset.Original -> R.string.image_size_original
            ImageSizePreset.High -> R.string.image_size_3840
            ImageSizePreset.Balanced -> R.string.image_size_2560
            ImageSizePreset.Small -> R.string.image_size_1600
            ImageSizePreset.Custom -> R.string.image_size_custom
        },
    )

@Composable
private fun imageExportFormatLabel(format: ImageExportFormat): String =
    stringResource(
        when (format) {
            ImageExportFormat.Original -> R.string.image_format_original
            ImageExportFormat.Jpeg -> R.string.image_format_jpeg
            ImageExportFormat.Png -> R.string.image_format_png
        },
    )

@Composable
private fun pdfSizeTargetLabel(target: PdfSizeTarget): String =
    when (target) {
        PdfSizeTarget.Original -> stringResource(R.string.pdf_size_original)
        PdfSizeTarget.Kb200 -> stringResource(R.string.pdf_size_200_kb)
        PdfSizeTarget.Kb500 -> stringResource(R.string.pdf_size_500_kb)
        PdfSizeTarget.Mb1 -> stringResource(R.string.pdf_size_1_mb)
        PdfSizeTarget.Mb5 -> stringResource(R.string.pdf_size_5_mb)
        PdfSizeTarget.Mb10 -> stringResource(R.string.pdf_size_10_mb)
        PdfSizeTarget.Mb20 -> stringResource(R.string.pdf_size_20_mb)
        is PdfSizeTarget.Custom ->
            if (target.kilobytes % 1_000 == 0) {
                stringResource(R.string.pdf_size_custom_value_mb, target.kilobytes / 1_000)
            } else {
                stringResource(R.string.pdf_size_custom_value_kb, target.kilobytes)
            }
    }

@Composable
private fun appLanguageLabel(language: AppLanguage): String =
    stringResource(
        when (language) {
            AppLanguage.System -> R.string.language_system
            AppLanguage.English -> R.string.language_english
            AppLanguage.Czech -> R.string.language_czech
            AppLanguage.German -> R.string.language_german
            AppLanguage.Spanish -> R.string.language_spanish
            AppLanguage.SimplifiedChinese -> R.string.language_simplified_chinese
        },
    )

@Composable
private fun SettingsSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
internal fun UiMessage.resolve(): String =
    stringResource(resourceId, *formatArgs.toTypedArray())
