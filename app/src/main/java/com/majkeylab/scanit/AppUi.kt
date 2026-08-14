package com.majkeylab.scanit

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
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
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException

private const val PRIVACY_POLICY_URL =
    "https://majkey25.github.io/ScanIt/privacy.html"
private const val THIRD_PARTY_NOTICES_URL =
    "https://majkey25.github.io/ScanIt/third-party-notices.txt"
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
    onSaveSettings: (AppSettings) -> Boolean,
    onLanguageChange: (AppLanguage) -> Unit,
    onPdfFolderSelected: (Uri, Int) -> UiMessage?,
    onPdfFolderCleared: () -> UiMessage?,
    onRecent: () -> Unit,
    onOpenRecent: (String) -> Unit,
    onShareRecentPdf: (String) -> Unit,
    onDeleteRecent: (OutputDeleteRequest) -> Unit,
    onLoadThumbnail: suspend (File) -> Bitmap?,
    onSelectResultPage: (Int) -> Unit,
    onNavigateBack: () -> Unit,
    onSharePdf: (() -> Unit)? = null,
    onShareImages: (() -> Unit)? = null,
    onPrint: (() -> Unit)? = null,
    onSaveNow: (SaveNowTarget) -> Unit = {},
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
        when {
            visualMarkEditor != null -> onCloseVisualMarkEditor()
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
        if (state is ScreenState.Result && state.visualMarkEditor != null) {
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
                        onSelectPage = onSelectResultPage,
                        onLoadThumbnail = onLoadThumbnail,
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
private fun ResultScreen(
    result: ScreenState.Result,
    onNewScan: () -> Unit,
    onRecent: () -> Unit,
    onSettings: () -> Unit,
    onSharePdf: (() -> Unit)?,
    onShareImages: (() -> Unit)?,
    onPrint: (() -> Unit)?,
    fileDetailsExpanded: Boolean,
    onFileDetailsChange: (Boolean) -> Unit,
    onSaveNow: (SaveNowTarget) -> Unit,
    onSelectPage: (Int) -> Unit,
    onLoadThumbnail: suspend (File) -> Bitmap?,
    onAddVisualMark: () -> Unit,
) {
    val scan = result.scan
    val pageCount = scan.cached.pages.size
    val selectedPageIndex = resolvedPageIndex(result.selectedPageIndex, pageCount)
    val pagePosition =
        stringResource(R.string.page_position, selectedPageIndex + 1, pageCount)
    val saveTargets = saveNowTargets(scan)
    var showSaveDialog by rememberSaveable(scan.cached.entryId) { mutableStateOf(false) }
    val actionsEnabled = !result.resultActionsBlocked
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
                when {
                    result.pagePreviewLoading -> {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(160.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    result.thumbnail == null -> {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(160.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(stringResource(R.string.preview_unavailable))
                        }
                    }
                    else -> {
                        Image(
                            bitmap = result.thumbnail.asImageBitmap(),
                            contentDescription = pagePosition,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 360.dp),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }
            }
            item {
                Text(
                    pagePosition,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (pageCount > 1) {
                item {
                    ResultPageStrip(
                        pages = scan.cached.pages,
                        selectedPageIndex = selectedPageIndex,
                        enabled = actionsEnabled,
                        onSelectPage = onSelectPage,
                        onLoadThumbnail = onLoadThumbnail,
                    )
                }
            }
            item {
                OutlinedButton(
                    onClick = onAddVisualMark,
                    enabled =
                        actionsEnabled &&
                            !result.pagePreviewLoading &&
                            result.thumbnail != null &&
                            scan.cached.entryId != null &&
                            scan.cached.sourcePages.size == scan.cached.pages.size &&
                            scan.cached.appearanceSettings != null &&
                            scan.outputMetadataValid,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.add_signature_or_stamp))
                }
            }
            item {
                Button(
                    onClick = { onSharePdf?.invoke() },
                    enabled = onSharePdf != null && actionsEnabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    Text(stringResource(R.string.send_pdf))
                }
            }
            item {
                OutlinedButton(
                    onClick = { onShareImages?.invoke() },
                    enabled = onShareImages != null && actionsEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.send_images))
                }
            }
            item {
                OutlinedButton(
                    onClick = { onPrint?.invoke() },
                    enabled = onPrint != null && actionsEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.print_document))
                }
            }
            item {
                Button(
                    onClick = onNewScan,
                    enabled = actionsEnabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    Text(stringResource(R.string.new_scan))
                }
            }
            item {
                TextButton(
                    onClick = { onFileDetailsChange(!fileDetailsExpanded) },
                    enabled = actionsEnabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.file_details), modifier = Modifier.weight(1f))
                    Icon(
                        painter = painterResource(R.drawable.ic_expand_more),
                        contentDescription =
                            stringResource(
                                if (fileDetailsExpanded) {
                                    R.string.collapse_file_details
                                } else {
                                    R.string.expand_file_details
                                },
                            ),
                        modifier = Modifier.size(24.dp).rotate(if (fileDetailsExpanded) 180f else 0f),
                    )
                }
                if (fileDetailsExpanded) {
                    FileDetails(
                        scan = scan,
                        saveTargets = saveTargets,
                        saveInProgress = result.outputSaveInProgress,
                        onSaveNow = { showSaveDialog = true },
                    )
                }
            }
            item { DistributionBannerAd() }
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
private fun FileDetails(
    scan: SavedScan,
    saveTargets: List<SaveNowTarget>,
    saveInProgress: Boolean,
    onSaveNow: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
        val savedPdf = scan.savedPdf
        Text(
            when {
                savedPdf == null -> stringResource(R.string.pdf_temporary)
                savedPdf.authority == MediaStore.AUTHORITY ->
                    stringResource(R.string.pdf_saved_downloads, scan.cached.pdf.name)
                else -> stringResource(R.string.pdf_saved_selected_folder, scan.cached.pdf.name)
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            if (scan.galleryPages.isEmpty()) {
                stringResource(R.string.images_temporary)
            } else {
                stringResource(R.string.images_saved_gallery, scan.cached.pages.first().name)
            },
            style = MaterialTheme.typography.bodySmall,
        )
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
            item { DistributionBannerAd() }
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
                onClick = { menuExpanded = true },
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
    var folderError by remember { mutableStateOf<UiMessage?>(null) }
    var settingsError by remember { mutableStateOf<UiMessage?>(null) }
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val supportNotice = stringResource(R.string.support_scanit_notice)

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
        val customMegabytes = parseCustomPdfMegabytes(customPdfSizeInput)
        AlertDialog(
            onDismissRequest = { customPdfSizeDialogOpen = false },
            title = { Text(stringResource(R.string.pdf_size_custom_title)) },
            text = {
                OutlinedTextField(
                    value = customPdfSizeInput,
                    onValueChange = { customPdfSizeInput = it },
                    label = { Text(stringResource(R.string.pdf_size_custom_field)) },
                    supportingText = { Text(stringResource(R.string.pdf_size_custom_hint)) },
                    isError = customPdfSizeInput.isNotEmpty() && customMegabytes == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        customMegabytes?.let { megabytes ->
                            pdfSizeTargetWire = PdfSizeTarget.Custom(megabytes).wireValue
                            persistSettings()
                            customPdfSizeDialogOpen = false
                        }
                    },
                    enabled = customMegabytes != null,
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
                                customPdfSizeInput =
                                    (selectedTarget as? PdfSizeTarget.Custom)
                                        ?.megabytes
                                        ?.toString()
                                        .orEmpty()
                                customPdfSizeDialogOpen = true
                            },
                            label = {
                                Text(
                                    if (selectedTarget is PdfSizeTarget.Custom) {
                                        stringResource(
                                            R.string.pdf_size_custom_value,
                                            selectedTarget.megabytes,
                                        )
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
                DistributionSettingsFooter()
            }
            item { Spacer(Modifier.height(4.dp)) }
        }
    }
}

@Composable
private fun pdfSizeTargetLabel(target: PdfSizeTarget): String =
    when (target) {
        PdfSizeTarget.Original -> stringResource(R.string.pdf_size_original)
        PdfSizeTarget.Mb5 -> stringResource(R.string.pdf_size_5_mb)
        PdfSizeTarget.Mb10 -> stringResource(R.string.pdf_size_10_mb)
        PdfSizeTarget.Mb20 -> stringResource(R.string.pdf_size_20_mb)
        is PdfSizeTarget.Custom ->
            stringResource(R.string.pdf_size_custom_value, target.megabytes)
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
