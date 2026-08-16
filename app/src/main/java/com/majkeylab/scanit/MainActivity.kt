package com.majkeylab.scanit

import android.app.Activity
import android.app.LocaleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Bundle
import android.os.LocaleList
import android.print.PrintManager
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun scannerPageLimit(multipage: Boolean): Int = if (multipage) MAX_SCAN_PAGES else 1

private const val LAUNCHED_OUTPUT_TREE_REQUEST_KEY = "launched_output_tree_request"
private const val LAUNCHED_DOCUMENT_TEXT_EXPORT_REQUEST_KEY =
    "launched_document_text_export_request"
private const val OUTPUT_TREE_INTENT_FLAGS =
    PDF_TREE_FLAGS or
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

internal fun isAcceptedScanPageCount(pageCount: Int): Boolean = pageCount in 1..MAX_SCAN_PAGES

internal fun exactOutputTreeGrantFlags(
    scheme: String?,
    isTreeUri: Boolean,
    returnedFlags: Int,
): Int? {
    val grantedFlags = returnedFlags and PDF_TREE_FLAGS
    return grantedFlags.takeIf {
        scheme == "content" &&
            isTreeUri &&
            grantedFlags == PDF_TREE_FLAGS &&
            returnedFlags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0
    }
}

internal enum class ScannerPurpose {
    Document,
    VisualMark,
}

internal fun scannerMode(purpose: ScannerPurpose): Int =
    when (purpose) {
        ScannerPurpose.Document -> GmsDocumentScannerOptions.SCANNER_MODE_FULL
        ScannerPurpose.VisualMark -> GmsDocumentScannerOptions.SCANNER_MODE_BASE
    }

class MainActivity : ComponentActivity() {
    private val viewModel: ScanViewModel by viewModels()
    private var scannerV2Opening = false
    private var launchedOutputTreeRequest: OutputChangeRequest? = null
    private var launchedDocumentTextExportRequest: DocumentActionRequest? = null
    private val savedOutputsChangedReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ACTION_SAVED_OUTPUTS_CHANGED) {
                    viewModel.refreshAfterShareCleanup()
                }
            }
        }
    private val scannerLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            handleScannerResult(it)
        }
    private val visualMarkScannerLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            handleVisualMarkScannerResult(it)
        }
    private val outputTreeLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            handleOutputTreeResult(it)
        }
    private val documentTextExportLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            handleDocumentTextExportResult(it)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchedOutputTreeRequest =
            decodeOutputTreePickerRequest(
                savedInstanceState?.getString(LAUNCHED_OUTPUT_TREE_REQUEST_KEY),
            )
        launchedDocumentTextExportRequest =
            decodeDocumentActionRequest(
                savedInstanceState?.getString(LAUNCHED_DOCUMENT_TEXT_EXPORT_REQUEST_KEY),
            )
        val defaultEmailSubjects = supportedDefaultEmailSubjects()
        viewModel.localizeDefaultEmailSubject(
            targetDefault = getString(R.string.default_email_subject),
            supportedDefaults = defaultEmailSubjects,
        )
        setContent {
            val state by viewModel.state.collectAsState()
            val settings by viewModel.settings.collectAsState()
            ScanItApp(
                state = state,
                settings = settings,
                language = currentAppLanguage(),
                defaultEmailSubjects = defaultEmailSubjects,
                onScan = ::startScan,
                onEditScan =
                    if (isScannerV2ApplicationId(packageName)) {
                        ::editScannerV2Scan
                    } else {
                        null
                    },
                onSaveSettings = viewModel::saveSettings,
                onLanguageChange = ::setAppLanguage,
                onPdfFolderSelected = viewModel::setPdfTreeUri,
                onPdfFolderCleared = viewModel::clearPdfTreeUri,
                onRecent = viewModel::showRecent,
                onOpenRecent = viewModel::openRecentScan,
                onShareRecentPdf = ::shareRecentPdf,
                onDeleteRecent = viewModel::deleteRecentScan,
                onLoadThumbnail = viewModel::loadThumbnail,
                onLoadResultPreview = viewModel::loadResultPreview,
                onLoadResultImageDimensions = viewModel::loadResultImageDimensions,
                onLoadAppearancePreview = viewModel::loadAppearancePreview,
                onSelectResultPage = viewModel::selectResultPage,
                onNavigateBack = viewModel::navigateBack,
                onSharePdf = ::shareCurrentPdf,
                onShareImages = ::shareCurrentImages,
                onPrint = ::printCurrentScan,
                onSaveNow = viewModel::saveCurrentOutputs,
                onChangePdfSize = viewModel::changeCurrentPdfSize,
                onChangePdfLocation = viewModel::requestPdfLocationChange,
                onChangeImageSize = viewModel::changeCurrentImageSize,
                onChangeImageFormat = viewModel::changeCurrentImageFormat,
                onChangeImageLocation = viewModel::requestImageLocationChange,
                onRenamePdf = viewModel::renameCurrentPdf,
                onRenameImages = viewModel::renameCurrentImages,
                onAcknowledgeUnknownOutput = viewModel::acknowledgeUnknownOutputCreate,
                onCloseAppearanceEditor = viewModel::closeAppearanceEditor,
                onApplyAppearance = viewModel::applyCurrentAppearance,
                onRunDocumentAction = viewModel::runDocumentAction,
                onDismissDocumentAction = viewModel::dismissDocumentAction,
                onExportDocumentText = ::startDocumentTextExport,
                onOpenDocumentActionUrl = ::openDocumentActionUrl,
                onOpenVisualMarkEditor = viewModel::openVisualMarkEditor,
                onCloseVisualMarkEditor = viewModel::closeVisualMarkEditor,
                onSelectVisualMarkTemplate = viewModel::selectVisualMarkTemplate,
                onUpdateVisualMarkPlacement = viewModel::updateVisualMarkPlacement,
                onBeginVisualMarkDrawing = viewModel::beginVisualMarkDrawing,
                onUpdateVisualMarkDrawing = viewModel::updateVisualMarkDrawing,
                onCancelVisualMarkDrawing = viewModel::cancelVisualMarkDrawing,
                onImportVisualMark = viewModel::importVisualMark,
                onSaveDrawnVisualMark = viewModel::saveDrawnVisualMark,
                onDeleteVisualMarkTemplate = viewModel::deleteVisualMarkTemplate,
                onLoadVisualMarkTemplate = viewModel::loadVisualMarkTemplate,
                onScanVisualMark = ::startVisualMarkScan,
                onApplyVisualMark = viewModel::applyVisualMark,
            )
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.scannerRequest.collect { request ->
                    if (request != null && viewModel.claimScannerRequest(request)) {
                        requestScannerIntent(request)
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.outputTreePickerRequest.collect { request ->
                    if (request != null && viewModel.claimOutputTreePicker(request)) {
                        launchOutputTreePicker(request)
                    }
                }
            }
        }
        if (!isScannerV2ApplicationId(packageName)) {
            viewModel.resumeScannerPreparation()
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val result = retryPendingShareCleanup(applicationContext)
            if (result != null && shareCleanupCompletionPolicy(result).warn) {
                showShareCleanupFailure(applicationContext)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(
            savedOutputsChangedReceiver,
            IntentFilter(ACTION_SAVED_OUTPUTS_CHANGED),
            Context.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        launchedOutputTreeRequest?.let { request ->
            outState.putString(
                LAUNCHED_OUTPUT_TREE_REQUEST_KEY,
                encodeOutputTreePickerRequest(request),
            )
        }
        launchedDocumentTextExportRequest?.let { request ->
            outState.putString(
                LAUNCHED_DOCUMENT_TEXT_EXPORT_REQUEST_KEY,
                encodeDocumentActionRequest(request),
            )
        }
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        unregisterReceiver(savedOutputsChangedReceiver)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshAfterShareCleanup()
    }

    internal fun startScan() {
        if (isScannerV2ApplicationId(packageName)) {
            openScannerV2(ACTION_SCANNER_V2_NEW)
        } else {
            viewModel.beginScannerLaunch()
        }
    }

    private fun editScannerV2Scan() {
        val cached = (viewModel.state.value as? ScreenState.Result)?.scan?.cached ?: return
        val entryId = cached.entryId ?: return
        openScannerV2(
            action = ACTION_SCANNER_V2_EDIT,
            editCacheId = cached.baseName,
            editEntryId = entryId,
        )
    }

    private fun openScannerV2(
        action: String,
        editCacheId: String? = null,
        editEntryId: String? = null,
    ) {
        require((editCacheId == null) == (editEntryId == null)) {
            "Scanner v2 edit identity is incomplete"
        }
        if (scannerV2Opening || isFinishing || isDestroyed) return
        scannerV2Opening = true
        try {
            startActivity(
                Intent()
                    .setClassName(packageName, SCANNER_V2_ACTIVITY_CLASS)
                    .setAction(action)
                    .apply {
                        editCacheId?.let { putExtra(EXTRA_SCANNER_V2_EDIT_CACHE_ID, it) }
                        editEntryId?.let { putExtra(EXTRA_SCANNER_V2_EDIT_ENTRY_ID, it) }
                    },
            )
            finish()
        } catch (failure: RuntimeException) {
            scannerV2Opening = false
            throw failure
        }
    }

    private fun requestScannerIntent(requestGeneration: Long) {
        try {
            val settings = viewModel.currentSettings()
            val optionsBuilder =
                GmsDocumentScannerOptions.Builder()
                    .setGalleryImportAllowed(settings.allowGallery)
                    .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                    .setScannerMode(scannerMode(ScannerPurpose.Document))
            optionsBuilder.setPageLimit(scannerPageLimit(settings.multipage))

            GmsDocumentScanning.getClient(optionsBuilder.build())
                .getStartScanIntent(this)
                .addOnSuccessListener { intentSender ->
                    if (isFinishing || isDestroyed) {
                        return@addOnSuccessListener
                    }
                    if (!viewModel.isScannerLaunchCurrent(requestGeneration)) {
                        return@addOnSuccessListener
                    }
                    try {
                        scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                        viewModel.scannerLaunched(requestGeneration)
                    } catch (_: RuntimeException) {
                        viewModel.scannerLaunchFailed(
                            requestGeneration,
                            UiMessage(R.string.scanner_launch_error),
                        )
                    }
                }.addOnFailureListener {
                    viewModel.scannerLaunchFailed(
                        requestGeneration,
                        UiMessage(R.string.scanner_launch_error),
                    )
                }
        } catch (_: RuntimeException) {
            viewModel.scannerLaunchFailed(
                requestGeneration,
                UiMessage(R.string.scanner_launch_error),
            )
        }
    }

    private fun handleScannerResult(activityResult: ActivityResult) {
        when (activityResult.resultCode) {
            Activity.RESULT_CANCELED -> viewModel.scannerCancelled()
            Activity.RESULT_OK -> processScannerResult(activityResult.data)
            else ->
                viewModel.scannerResultFailed(
                    UiMessage(R.string.scanner_unexpected_error),
                )
        }
    }

    private fun launchOutputTreePicker(request: OutputChangeRequest) {
        launchedOutputTreeRequest = request
        try {
            outputTreeLauncher.launch(
                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(OUTPUT_TREE_INTENT_FLAGS),
            )
        } catch (_: RuntimeException) {
            clearLaunchedOutputTreeRequest(
                request,
                viewModel.outputTreePickerCancelled(request),
            )
            showToast(R.string.folder_permission_failed)
        }
    }

    private fun handleOutputTreeResult(activityResult: ActivityResult) {
        val request = launchedOutputTreeRequest ?: return
        if (activityResult.resultCode == Activity.RESULT_CANCELED) {
            clearLaunchedOutputTreeRequest(
                request,
                viewModel.outputTreePickerCancelled(request),
            )
            return
        }
        val data = activityResult.data
        val treeUri = data?.data
        val isTreeUri =
            try {
                treeUri?.let(DocumentsContract::isTreeUri) == true
            } catch (_: RuntimeException) {
                false
            }
        val grantedFlags =
            exactOutputTreeGrantFlags(treeUri?.scheme, isTreeUri, data?.flags ?: 0)
        if (
            activityResult.resultCode != Activity.RESULT_OK ||
                treeUri == null ||
                grantedFlags == null
        ) {
            val disposition = viewModel.outputTreePickerCancelled(request)
            clearLaunchedOutputTreeRequest(request, disposition)
            if (disposition == OutputTreeCallbackDisposition.Accepted) {
                showToast(R.string.folder_permission_missing)
            }
            return
        }
        clearLaunchedOutputTreeRequest(
            request,
            viewModel.outputTreePickerSelected(request, treeUri, grantedFlags),
        )
    }

    private fun clearLaunchedOutputTreeRequest(
        request: OutputChangeRequest,
        disposition: OutputTreeCallbackDisposition,
    ) {
        when (disposition) {
            OutputTreeCallbackDisposition.Accepted,
            OutputTreeCallbackDisposition.DefiniteStale,
            -> if (launchedOutputTreeRequest == request) launchedOutputTreeRequest = null
        }
    }

    private fun startDocumentTextExport() {
        val request = viewModel.beginDocumentTextExport()
        if (request == null) {
            showToast(R.string.text_export_failed)
            return
        }
        launchedDocumentTextExportRequest = request
        val intent =
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain")
                .putExtra(
                    Intent.EXTRA_TITLE,
                    sanitizeTextExportFileName(request.cacheId),
                ).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            documentTextExportLauncher.launch(intent)
        } catch (_: RuntimeException) {
            clearLaunchedDocumentTextExportRequest(
                request,
                viewModel.documentTextExportDestinationCancelled(request),
            )
            showToast(R.string.text_export_failed)
        }
    }

    private fun handleDocumentTextExportResult(activityResult: ActivityResult) {
        val request = launchedDocumentTextExportRequest ?: return
        if (activityResult.resultCode == Activity.RESULT_CANCELED) {
            clearLaunchedDocumentTextExportRequest(
                request,
                viewModel.documentTextExportDestinationCancelled(request),
            )
            return
        }
        val data = activityResult.data
        val destination = data?.data
        val disposition =
            if (activityResult.resultCode == Activity.RESULT_OK && destination != null) {
                viewModel.exportDocumentText(request, destination, data.flags)
            } else {
                viewModel.documentTextExportDestinationCancelled(request)
            }
        clearLaunchedDocumentTextExportRequest(request, disposition)
        if (
            disposition == DocumentTextExportDisposition.Accepted &&
                (activityResult.resultCode != Activity.RESULT_OK || destination == null)
        ) {
            showToast(R.string.text_export_failed)
        }
    }

    private fun clearLaunchedDocumentTextExportRequest(
        request: DocumentActionRequest,
        disposition: DocumentTextExportDisposition,
    ) {
        when (disposition) {
            DocumentTextExportDisposition.Accepted,
            DocumentTextExportDisposition.DefiniteStale,
            -> if (launchedDocumentTextExportRequest == request) {
                launchedDocumentTextExportRequest = null
            }
        }
    }

    private fun openDocumentActionUrl(value: String) {
        val url = validatedHttpUrl(value)
        if (url == null) {
            showToast(R.string.link_open_failed)
            return
        }
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, url.toUri())
                    .addCategory(Intent.CATEGORY_BROWSABLE),
            )
        } catch (_: RuntimeException) {
            showToast(R.string.link_open_failed)
        }
    }

    private fun processScannerResult(data: Intent?) {
        if (data == null) {
            viewModel.scannerResultFailed(UiMessage(R.string.scanner_result_error))
            return
        }
        try {
            val result = GmsDocumentScanningResult.fromActivityResultIntent(data)
            if (result == null) {
                viewModel.scannerResultFailed(UiMessage(R.string.scanner_result_error))
                return
            }
            val pages = result.pages
            if (pages.isNullOrEmpty()) {
                viewModel.scannerResultFailed(UiMessage(R.string.scanner_result_error))
                return
            }
            viewModel.processScan(pages.map { it.imageUri })
        } catch (_: RuntimeException) {
            viewModel.scannerResultFailed(UiMessage(R.string.scanner_result_error))
        }
    }

    private fun startVisualMarkScan() {
        val source = viewModel.beginVisualMarkScan() ?: return
        try {
            val options =
                GmsDocumentScannerOptions.Builder()
                    .setGalleryImportAllowed(false)
                    .setPageLimit(1)
                    .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                    .setScannerMode(scannerMode(ScannerPurpose.VisualMark))
                    .build()
            GmsDocumentScanning.getClient(options)
                .getStartScanIntent(this)
                .addOnSuccessListener { intentSender ->
                    if (
                        isFinishing ||
                            isDestroyed ||
                            viewModel.currentVisualMarkScanSource() != source
                    ) {
                        viewModel.visualMarkScannerFailed(source)
                        return@addOnSuccessListener
                    }
                    try {
                        visualMarkScannerLauncher.launch(
                            IntentSenderRequest.Builder(intentSender).build(),
                        )
                    } catch (_: RuntimeException) {
                        viewModel.visualMarkScannerFailed(source)
                    }
                }.addOnFailureListener {
                    viewModel.visualMarkScannerFailed(source)
                }
        } catch (_: RuntimeException) {
            viewModel.visualMarkScannerFailed(source)
        }
    }

    private fun handleVisualMarkScannerResult(activityResult: ActivityResult) {
        val source = viewModel.currentVisualMarkScanSource() ?: return
        when (activityResult.resultCode) {
            Activity.RESULT_CANCELED -> viewModel.visualMarkScannerCancelled(source)
            Activity.RESULT_OK -> {
                val uri =
                    try {
                        GmsDocumentScanningResult.fromActivityResultIntent(activityResult.data)
                            ?.pages
                            ?.singleOrNull()
                            ?.imageUri
                    } catch (_: RuntimeException) {
                        null
                    }
                if (uri == null) {
                    viewModel.visualMarkScannerFailed(source)
                } else {
                    viewModel.importScannedVisualMark(source, uri)
                }
            }
            else -> viewModel.visualMarkScannerFailed(source)
        }
    }

    private fun currentAppLanguage(): AppLanguage {
        val localeManager = getSystemService(LocaleManager::class.java) ?: return AppLanguage.System
        return appLanguageForTag(localeManager.applicationLocales.get(0)?.toLanguageTag())
    }

    private fun setAppLanguage(language: AppLanguage) {
        val locales =
            language.languageTag?.let(LocaleList::forLanguageTags)
                ?: LocaleList.getEmptyLocaleList()
        val localeManager = getSystemService(LocaleManager::class.java) ?: return
        if (localeManager.applicationLocales != locales) {
            localeManager.applicationLocales = locales
        }
    }

    private fun supportedDefaultEmailSubjects(): Set<String> =
        AppLanguage.entries.mapNotNull { it.languageTag }.mapTo(mutableSetOf()) { languageTag ->
            val configuration = Configuration(resources.configuration)
            configuration.setLocales(LocaleList.forLanguageTags(languageTag))
            createConfigurationContext(configuration).getString(R.string.default_email_subject)
        }

    private fun shareCurrentPdf() {
        shareCurrentScan(ShareCleanupKind.Pdf, ::pdfShareIntent)
    }

    private fun shareRecentPdf(cacheId: String) {
        val action = viewModel.beginRecentShare(cacheId)
        if (action == null) {
            showToast(R.string.share_failed)
            return
        }
        val settings = viewModel.currentSettings()
        lifecycleScope.launch {
            val prepared =
                try {
                    withContext(Dispatchers.IO) {
                        val scan = viewModel.recentScanForShare(action) ?: return@withContext null
                        pdfShareIntent(this@MainActivity, scan.cached, settings) to
                            shareCleanupRequest(
                                scan,
                                ShareCleanupKind.Pdf,
                                settings.deletePdfAfterShare,
                            )
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    null
                }
            if (prepared == null) {
                if (viewModel.recentShareUnavailable(action)) {
                    showToast(R.string.share_failed)
                }
                return@launch
            }
            if (!viewModel.claimRecentShare(action)) {
                return@launch
            }
            try {
                if (!launchShareChooser(prepared.first, prepared.second)) {
                    showToast(R.string.share_failed)
                }
            } catch (_: RuntimeException) {
                showToast(R.string.share_failed)
            }
        }
    }

    private fun shareCurrentImages() {
        val action = viewModel.beginResultImageShare()
        if (action == null) {
            showToast(R.string.share_failed)
            return
        }
        val settings = viewModel.currentSettings()
        lifecycleScope.launch {
            val prepared =
                try {
                    viewModel.prepareResultImageShare(action)
                } catch (cancellation: CancellationException) {
                    viewModel.resultImageShareFailed(action)
                    throw cancellation
                } catch (_: Exception) {
                    null
                }
            if (prepared == null) {
                viewModel.resultImageShareFailed(action)
                showToast(R.string.share_failed)
                return@launch
            }
            val payload =
                try {
                    withContext(Dispatchers.IO) {
                        val intent =
                            prepared.privateCopies?.let { copies ->
                                imageShareIntent(this@MainActivity, copies, settings)
                            } ?: imageShareIntent(this@MainActivity, prepared.scan.cached, settings)
                        intent to
                            shareCleanupRequest(
                                prepared.scan,
                                ShareCleanupKind.Images,
                                settings.deleteImagesAfterShare,
                            )
                    }
                } catch (cancellation: CancellationException) {
                    prepared.privateCopies?.let { copies ->
                        withContext(NonCancellable + Dispatchers.IO) {
                            cleanupPreparedImageShare(copies)
                        }
                    }
                    viewModel.resultImageShareFailed(action)
                    throw cancellation
                } catch (_: Exception) {
                    prepared.privateCopies?.let { copies ->
                        withContext(Dispatchers.IO) { cleanupPreparedImageShare(copies) }
                    }
                    viewModel.resultImageShareFailed(action)
                    showToast(R.string.share_failed)
                    return@launch
                }
            if (!viewModel.claimResultImageShare(action)) {
                prepared.privateCopies?.let { copies ->
                    withContext(Dispatchers.IO) { cleanupPreparedImageShare(copies) }
                }
                return@launch
            }
            val chooserLaunched =
                try {
                    launchShareChooser(payload.first, payload.second)
                } catch (_: RuntimeException) {
                    false
                }
            if (!chooserLaunched) {
                prepared.privateCopies?.let { copies ->
                    lifecycleScope.launch(Dispatchers.IO) { cleanupPreparedImageShare(copies) }
                }
                showToast(R.string.share_failed)
            }
        }
    }

    private fun shareCurrentScan(
        cleanupKind: ShareCleanupKind,
        createIntent: (Context, CachedScan, AppSettings) -> Intent,
    ) {
        val result = viewModel.state.value as? ScreenState.Result
        val scan = result?.scan
        if (scan == null || result.resultActionsBlocked) {
            showToast(R.string.share_failed)
            return
        }
        val settings = viewModel.currentSettings()
        val cleanupEnabled =
            when (cleanupKind) {
                ShareCleanupKind.Pdf -> settings.deletePdfAfterShare
                ShareCleanupKind.Images -> settings.deleteImagesAfterShare
            }
        try {
            if (
                !launchShareChooser(
                    createIntent(this, scan.cached, settings),
                    shareCleanupRequest(scan, cleanupKind, cleanupEnabled),
                )
            ) {
                showToast(R.string.share_failed)
            }
        } catch (_: Exception) {
            showToast(R.string.share_failed)
        }
    }

    private fun printCurrentScan() {
        val result = viewModel.state.value as? ScreenState.Result
        val scan = result?.scan?.cached
        if (scan == null || result.resultActionsBlocked) {
            showToast(R.string.print_open_failed)
            return
        }
        try {
            val printManager = getSystemService(PrintManager::class.java)
            if (printManager == null) {
                showToast(R.string.print_open_failed)
                return
            }
            printManager.print(
                scan.pdf.name,
                ScanPrintAdapter(this, scan.pdf.name, scan.pages),
                null,
            )
        } catch (_: RuntimeException) {
            showToast(R.string.print_open_failed)
        }
    }

    private fun showToast(message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
