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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun scannerPageLimit(multipage: Boolean): Int = if (multipage) MAX_SCAN_PAGES else 1

internal fun isAcceptedScanPageCount(pageCount: Int): Boolean = pageCount in 1..MAX_SCAN_PAGES

class MainActivity : ComponentActivity() {
    private val viewModel: ScanViewModel by viewModels()
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                onSaveSettings = viewModel::saveSettings,
                onLanguageChange = ::setAppLanguage,
                onPdfFolderSelected = viewModel::setPdfTreeUri,
                onPdfFolderCleared = viewModel::clearPdfTreeUri,
                onRecent = viewModel::showRecent,
                onOpenRecent = viewModel::openRecentScan,
                onShareRecentPdf = ::shareRecentPdf,
                onDeleteRecent = viewModel::deleteRecentScan,
                onLoadThumbnail = viewModel::loadThumbnail,
                onSelectResultPage = viewModel::selectResultPage,
                onNavigateBack = viewModel::navigateBack,
                onSharePdf = ::shareCurrentPdf,
                onShareImages = ::shareCurrentImages,
                onPrint = ::printCurrentScan,
                onSaveNow = viewModel::saveCurrentOutputs,
                onApplyAppearance = viewModel::applyCurrentAppearance,
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
        viewModel.resumeScannerPreparation()
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

    override fun onStop() {
        unregisterReceiver(savedOutputsChangedReceiver)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshAfterShareCleanup()
    }

    internal fun startScan() {
        viewModel.beginScannerLaunch()
    }

    private fun requestScannerIntent(requestGeneration: Long) {
        try {
            val settings = viewModel.currentSettings()
            val optionsBuilder =
                GmsDocumentScannerOptions.Builder()
                    .setGalleryImportAllowed(settings.allowGallery)
                    .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                    .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_BASE)
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

    private fun currentAppLanguage(): AppLanguage {
        val localeManager = getSystemService(LocaleManager::class.java) ?: return AppLanguage.System
        return when (localeManager.applicationLocales.get(0)?.language) {
            AppLanguage.English.languageTag -> AppLanguage.English
            AppLanguage.Czech.languageTag -> AppLanguage.Czech
            else -> AppLanguage.System
        }
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
        shareCurrentScan(ShareCleanupKind.Images, ::imageShareIntent)
    }

    private fun shareCurrentScan(
        cleanupKind: ShareCleanupKind,
        createIntent: (Context, CachedScan, AppSettings) -> Intent,
    ) {
        val scan = (viewModel.state.value as? ScreenState.Result)?.scan
        if (scan == null) {
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
        val scan = (viewModel.state.value as? ScreenState.Result)?.scan?.cached
        if (scan == null) {
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
