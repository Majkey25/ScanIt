package cz.mates.skendopdf

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.concurrent.CancellationException
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SCANNER_STAGE_KEY = "scanner_launch_stage"
internal const val PDF_TREE_FLAGS =
    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

internal enum class ScannerLaunchStage {
    Idle,
    Preparing,
    Launched,
}

internal sealed interface AiKeyStatus {
    data object Unknown : AiKeyStatus

    data object Checking : AiKeyStatus

    data object Missing : AiKeyStatus

    data object Present : AiKeyStatus

    data object Saving : AiKeyStatus

    data class Error(val message: UiMessage) : AiKeyStatus
}

internal class ScannerLaunchGate(
    initialStage: ScannerLaunchStage = ScannerLaunchStage.Idle,
) {
    var stage: ScannerLaunchStage = initialStage
        private set

    private var generation = 0L

    fun begin(processing: Boolean): Long? {
        if (processing || stage != ScannerLaunchStage.Idle) {
            return null
        }
        stage = ScannerLaunchStage.Preparing
        return nextGeneration()
    }

    fun resumePreparing(processing: Boolean): Long? {
        if (processing || stage != ScannerLaunchStage.Preparing) {
            return null
        }
        return nextGeneration()
    }

    fun isPreparing(requestGeneration: Long): Boolean =
        requestGeneration == generation && stage == ScannerLaunchStage.Preparing

    fun markLaunched(requestGeneration: Long): Boolean {
        if (!isPreparing(requestGeneration)) {
            return false
        }
        stage = ScannerLaunchStage.Launched
        return true
    }

    fun fail(requestGeneration: Long): Boolean {
        if (requestGeneration != generation || stage == ScannerLaunchStage.Idle) {
            return false
        }
        reset()
        return true
    }

    fun complete() {
        if (stage != ScannerLaunchStage.Idle) {
            reset()
        }
    }

    private fun reset() {
        stage = ScannerLaunchStage.Idle
        nextGeneration()
    }

    private fun nextGeneration(): Long {
        check(generation < Long.MAX_VALUE) { "Scanner request generation exhausted" }
        generation += 1L
        return generation
    }
}

internal class ScanViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val settingsStore = SettingsStore(application)
    private val storage = ScanStorage(application)
    private val scannerLaunchGate =
        ScannerLaunchGate(
            ScannerLaunchStage.entries.firstOrNull {
                it.name == savedStateHandle.get<String>(SCANNER_STAGE_KEY)
            } ?: ScannerLaunchStage.Idle,
        )
    private val mutableState = MutableStateFlow<ScreenState>(ScreenState.Ready)
    private val mutableSettings = MutableStateFlow(settingsStore.load())
    private val mutableAiKeyStatus = MutableStateFlow<AiKeyStatus>(AiKeyStatus.Unknown)
    private val pdfGrantLock = ReentrantLock()
    private val aiStartupCleanupJob: Job
    private var processingJob: Job? = null
    private var aiGeneration = 0L

    val state: StateFlow<ScreenState> = mutableState.asStateFlow()
    val settings: StateFlow<AppSettings> = mutableSettings.asStateFlow()
    val aiKeyStatus: StateFlow<AiKeyStatus> = mutableAiKeyStatus.asStateFlow()

    init {
        aiStartupCleanupJob =
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    storage.clearAiWork()
                } catch (_: Exception) {
                    // A cleanup attempt also runs before every AI request.
                }
            }
        refreshGeminiKeyStatus()
        viewModelScope.launch(Dispatchers.IO) {
            pdfGrantLock.lock()
            try {
                retryPendingPdfTreeGrant()
            } catch (_: IOException) {
                // The single pending grant remains recorded for the next retry.
            } catch (_: RuntimeException) {
                // The single pending grant remains recorded for the next retry.
            } finally {
                pdfGrantLock.unlock()
            }
        }
    }

    fun currentSettings(): AppSettings = mutableSettings.value

    fun localizeDefaultEmailSubject(
        targetDefault: String,
        supportedDefaults: Set<String>,
    ) {
        val current = mutableSettings.value
        val localized =
            localizedDefaultEmailSubject(current.emailSubject, targetDefault, supportedDefaults)
        if (localized != current.emailSubject) {
            saveSettings(current.copy(emailSubject = localized))
        }
    }

    fun saveSettings(settings: AppSettings) {
        val normalized =
            settings.copy(
                albumName = normalizeAlbumName(settings.albumName),
                pdfTreeUri = mutableSettings.value.pdfTreeUri,
            )
        settingsStore.save(normalized)
        mutableSettings.value = normalized
    }

    fun setPdfTreeUri(uri: Uri, grantedFlags: Int): UiMessage? =
        withPdfGrantChange {
            retryPendingPdfTreeGrantOrThrow()
            require(uri.scheme == "content" && DocumentsContract.isTreeUri(uri)) {
                "PDF destination must be a document tree URI"
            }
            require(grantedFlags == PDF_TREE_FLAGS) {
                "PDF destination must grant persisted read/write access"
            }
            val resolver = getApplication<Application>().contentResolver
            val oldUri = mutableSettings.value.pdfTreeUri
            val newUri = uri.toString()
            if (oldUri == newUri) {
                resolver.takePersistableUriPermission(uri, PDF_TREE_FLAGS)
                return@withPdfGrantChange null
            }
            settingsStore.savePdfTreeUris(current = oldUri, pending = newUri)
            resolver.takePersistableUriPermission(uri, PDF_TREE_FLAGS)
            settingsStore.savePdfTreeUris(current = newUri, pending = oldUri)
            mutableSettings.value = mutableSettings.value.copy(pdfTreeUri = newUri)
            retryPendingPdfTreeGrant()
        }

    fun clearPdfTreeUri(): UiMessage? =
        withPdfGrantChange {
            retryPendingPdfTreeGrantOrThrow()
            val oldUri = mutableSettings.value.pdfTreeUri ?: return@withPdfGrantChange null
            settingsStore.savePdfTreeUris(current = null, pending = oldUri)
            mutableSettings.value = mutableSettings.value.copy(pdfTreeUri = null)
            retryPendingPdfTreeGrant()
        }

    fun refreshGeminiKeyStatus() {
        runKeyOperation(
            pending = AiKeyStatus.Checking,
            failureMessage = UiMessage(R.string.ai_key_check_failed),
        ) {
            if (settingsStore.loadGeminiApiKey() == null) {
                AiKeyStatus.Missing
            } else {
                AiKeyStatus.Present
            }
        }
    }

    fun saveGeminiApiKey(apiKey: String) {
        require(apiKey.isNotBlank()) { "Gemini API key must not be blank" }
        runKeyOperation(
            pending = AiKeyStatus.Saving,
            failureMessage = UiMessage(R.string.ai_key_save_failed),
        ) {
            settingsStore.saveGeminiApiKey(apiKey)
            AiKeyStatus.Present
        }
    }

    fun deleteGeminiApiKey() {
        runKeyOperation(
            pending = AiKeyStatus.Saving,
            failureMessage = UiMessage(R.string.ai_key_delete_failed),
        ) {
            settingsStore.clearGeminiApiKey()
            AiKeyStatus.Missing
        }
    }

    fun beginScannerLaunch(): Long? {
        val request = scannerLaunchGate.begin(processingJob?.isActive == true) ?: return null
        persistScannerStage()
        mutableState.value = ScreenState.Processing(UiMessage(R.string.opening_scanner))
        return request
    }

    fun resumeScannerPreparation(): Long? {
        val request =
            scannerLaunchGate.resumePreparing(processingJob?.isActive == true) ?: return null
        persistScannerStage()
        mutableState.value = ScreenState.Processing(UiMessage(R.string.opening_scanner))
        return request
    }

    fun isScannerLaunchCurrent(requestGeneration: Long): Boolean =
        scannerLaunchGate.isPreparing(requestGeneration)

    fun scannerLaunched(requestGeneration: Long) {
        if (scannerLaunchGate.markLaunched(requestGeneration)) {
            persistScannerStage()
        }
    }

    fun scannerLaunchFailed(requestGeneration: Long, message: UiMessage) {
        if (scannerLaunchGate.fail(requestGeneration)) {
            persistScannerStage()
            mutableState.value = ScreenState.Failure(message)
        }
    }

    fun scannerCancelled() {
        completeScannerLaunch()
        if (processingJob?.isActive != true) {
            mutableState.value = ScreenState.Ready
        }
    }

    fun scannerResultFailed(message: UiMessage) {
        completeScannerLaunch()
        if (processingJob?.isActive != true) {
            mutableState.value = ScreenState.Failure(message)
        }
    }

    fun processScan(pageUris: List<Uri>, pdfUri: Uri): Boolean {
        completeScannerLaunch()
        if (processingJob?.isActive == true) {
            mutableState.value = ScreenState.Processing(UiMessage(R.string.saving_document))
            return false
        }

        val pages = pageUris.toList()
        mutableState.value = ScreenState.Processing(UiMessage(R.string.saving_document))
        processingJob =
            viewModelScope.launch {
                var cached: CachedScan? = null
                var galleryPages: List<Uri> = emptyList()
                var savedPdfUri: Uri? = null
                try {
                    val result =
                        withContext(Dispatchers.IO) {
                            val settings = currentSettings()
                            val cachedScan = storage.cacheScan(pages, pdfUri)
                            cached = cachedScan
                            val thumbnail = storage.loadThumbnail(cachedScan.pages.first())
                            val warnings = mutableListOf<UiMessage>()
                            if (settings.saveImages) {
                                try {
                                    galleryPages =
                                        storage.saveImages(cachedScan, settings.albumName)
                                } catch (cancellation: CancellationException) {
                                    throw cancellation
                                } catch (_: Exception) {
                                    warnings += UiMessage(R.string.images_save_failed)
                                }
                            }
                            currentCoroutineContext().ensureActive()
                            if (settings.savePdf) {
                                try {
                                    val savedPdf =
                                        storage.savePdf(
                                            cachedScan.pdf,
                                            cachedScan.baseName,
                                            settings.albumName,
                                            settings.pdfTreeUri,
                                        )
                                    savedPdfUri = savedPdf.first
                                    savedPdf.second?.let(warnings::add)
                                } catch (cancellation: CancellationException) {
                                    throw cancellation
                                } catch (_: Exception) {
                                    warnings += UiMessage(R.string.pdf_save_failed)
                                }
                            }
                            ScreenState.Result(
                                scan =
                                    SavedScan(
                                        cached = cachedScan,
                                        galleryPages = galleryPages,
                                        savedPdf = savedPdfUri,
                                        warnings = warnings,
                                    ),
                                thumbnail = thumbnail,
                            )
                        }
                    mutableState.value = result
                } catch (exception: CancellationException) {
                    cleanup(cached, galleryPages, savedPdfUri)
                    throw exception
                } catch (exception: Exception) {
                    val cleanupComplete = cleanup(cached, galleryPages, savedPdfUri)
                    val message =
                        if (cleanupComplete && exception.suppressed.isEmpty()) {
                            UiMessage(R.string.document_save_failed)
                        } else {
                            UiMessage(R.string.document_save_partial_failed)
                        }
                    mutableState.value = ScreenState.Failure(message)
                } finally {
                    processingJob = null
                }
            }
        return true
    }

    fun startAiCleanup() {
        val originalResult = mutableState.value as? ScreenState.Result ?: return
        val original = originalResult.scan
        if (original.isAiCopy || processingJob?.isActive == true) return
        val settings = currentSettings()
        val prerequisiteError =
            when {
                !settings.aiEnabled -> UiMessage(R.string.ai_disabled)
                !settings.aiConsent -> UiMessage(R.string.ai_consent_required)
                else -> null
            }
        if (prerequisiteError != null) {
            mutableState.value = originalResult.copy(message = prerequisiteError)
            return
        }

        val generation = nextAiGeneration()
        mutableState.value = ScreenState.Processing(UiMessage(R.string.preparing_ai_cleanup))
        processingJob =
            viewModelScope.launch {
                try {
                    aiStartupCleanupJob.join()
                    val apiKey = withContext(Dispatchers.IO) { settingsStore.loadGeminiApiKey() }
                    if (apiKey.isNullOrBlank()) {
                        clearAiWork()
                        if (generation == aiGeneration) {
                            mutableState.value =
                                originalResult.copy(
                                    message = UiMessage(R.string.ai_key_required),
                                )
                        }
                        return@launch
                    }
                    val work =
                        withContext(Dispatchers.IO) {
                            if (!storage.deleteAiCachedCopy(original.cached)) {
                                throw IOException("Old AI cache could not be deleted")
                            }
                            storage.prepareAiWork(original.cached)
                        }
                    original.cached.pages.forEachIndexed { index, page ->
                        currentCoroutineContext().ensureActive()
                        mutableState.value =
                            ScreenState.Processing(
                                UiMessage(
                                    R.string.ai_cleaning_page,
                                    listOf(index + 1, original.cached.pages.size),
                                ),
                            )
                        val jpeg = requestGeminiCleanup(page, apiKey)
                        currentCoroutineContext().ensureActive()
                        withContext(Dispatchers.IO) {
                            storage.writeAiPage(work.pages[index], jpeg)
                        }
                    }
                    withContext(Dispatchers.IO) {
                        storage.createPdf(work.pages, work.pdf)
                    }
                    val preview =
                        withContext(Dispatchers.IO) {
                            storage.loadThumbnail(work.pages.first())
                                ?: throw IOException("AI preview could not be decoded")
                        }
                    currentCoroutineContext().ensureActive()
                    if (generation == aiGeneration) {
                        mutableState.value =
                            ScreenState.AiReview(
                                original = original,
                                ai = work,
                                pageIndex = 0,
                                source = AiReviewSource.Ai,
                                preview = preview,
                            )
                    }
                } catch (cancellation: CancellationException) {
                    clearAiWork()
                    throw cancellation
                } catch (_: Exception) {
                    clearAiWork()
                    if (generation == aiGeneration) {
                        mutableState.value =
                            originalResult.copy(
                                message = UiMessage(R.string.ai_cleanup_failed),
                            )
                    }
                } finally {
                    if (generation == aiGeneration) processingJob = null
                }
            }
    }

    fun selectAiReviewPage(requestedIndex: Int) {
        val review = mutableState.value as? ScreenState.AiReview ?: return
        if (review.preview == null) return
        val pageIndex = aiReviewPageIndex(requestedIndex, review.ai.pages.size)
        if (pageIndex == review.pageIndex) return
        loadAiPreview(review.copy(pageIndex = pageIndex, preview = null))
    }

    fun selectAiReviewSource(source: AiReviewSource) {
        val review = mutableState.value as? ScreenState.AiReview ?: return
        if (review.preview == null) return
        if (source == review.source) return
        loadAiPreview(review.copy(source = source, preview = null))
    }

    fun acceptAiCleanup() {
        val review = mutableState.value as? ScreenState.AiReview ?: return
        if (review.preview == null) return
        val generation = startAiOperation(UiMessage(R.string.saving_ai_copy))
        processingJob =
            viewModelScope.launch {
                var galleryPages: List<Uri> = emptyList()
                var savedPdf: Uri? = null
                try {
                    val result =
                        withContext(Dispatchers.IO) {
                            val settings = currentSettings()
                            val cached = storage.promoteAiWork(review.original.cached, review.ai)
                            // ponytail: no durable publish journal; add one if hard process-kill rollback becomes required.
                            if (settings.saveImages) {
                                galleryPages =
                                    storage.saveImages(
                                        cached,
                                        settings.albumName,
                                        isAiCopy = true,
                                    )
                            }
                            currentCoroutineContext().ensureActive()
                            val pdfResult =
                                if (settings.savePdf) {
                                    storage.savePdf(
                                        cached.pdf,
                                        cached.baseName,
                                        settings.albumName,
                                        settings.pdfTreeUri,
                                        isAiCopy = true,
                                    )
                                } else {
                                    null
                                }
                            savedPdf = pdfResult?.first
                            currentCoroutineContext().ensureActive()
                            val thumbnail = storage.loadThumbnail(cached.pages.first())
                            val workCleared =
                                try {
                                    storage.clearAiWork()
                                } catch (_: Exception) {
                                    false
                                }
                            ScreenState.Result(
                                scan =
                                    SavedScan(
                                        cached = cached,
                                        galleryPages = galleryPages,
                                        savedPdf = savedPdf,
                                        warnings = listOfNotNull(pdfResult?.second),
                                        isAiCopy = true,
                                    ),
                                thumbnail = thumbnail,
                                original = review.original,
                                message =
                                    if (workCleared) {
                                        null
                                    } else {
                                        UiMessage(R.string.ai_cache_cleanup_failed)
                                    },
                            )
                        }
                    if (generation == aiGeneration) mutableState.value = result
                } catch (cancellation: CancellationException) {
                    cleanupAiAcceptance(review.original, galleryPages, savedPdf)
                    throw cancellation
                } catch (exception: Exception) {
                    val cleanupComplete =
                        cleanupAiAcceptance(review.original, galleryPages, savedPdf)
                    val thumbnail = loadOriginalThumbnail(review.original)
                    if (generation == aiGeneration) {
                        mutableState.value =
                            ScreenState.Result(
                                scan = review.original,
                                thumbnail = thumbnail,
                                message =
                                    if (cleanupComplete && exception.suppressed.isEmpty()) {
                                        UiMessage(R.string.ai_copy_save_failed)
                                    } else {
                                        UiMessage(R.string.ai_copy_save_partial_failed)
                                    },
                            )
                    }
                } finally {
                    if (generation == aiGeneration) processingJob = null
                }
            }
    }

    fun discardAiCleanup() {
        val review = mutableState.value as? ScreenState.AiReview ?: return
        val generation = startAiOperation(UiMessage(R.string.discarding_ai_preview))
        processingJob =
            viewModelScope.launch {
                try {
                    val cleared = clearAiWork()
                    val thumbnail = loadOriginalThumbnail(review.original)
                    if (generation == aiGeneration) {
                        mutableState.value =
                            ScreenState.Result(
                                scan = review.original,
                                thumbnail = thumbnail,
                                message =
                                    if (cleared) {
                                        null
                                    } else {
                                        UiMessage(R.string.ai_cache_cleanup_failed)
                                    },
                            )
                    }
                } catch (cancellation: CancellationException) {
                    clearAiWork()
                    throw cancellation
                } catch (_: Exception) {
                    clearAiWork()
                    if (generation == aiGeneration) {
                        mutableState.value =
                            ScreenState.Result(
                                review.original,
                                null,
                                message = UiMessage(R.string.ai_preview_discarded_load_failed),
                            )
                    }
                } finally {
                    if (generation == aiGeneration) processingJob = null
                }
            }
    }

    fun useOriginal() {
        val result = mutableState.value as? ScreenState.Result ?: return
        val original = result.original ?: return
        val generation = startAiOperation(UiMessage(R.string.loading_original))
        processingJob =
            viewModelScope.launch {
                try {
                    val thumbnail = loadOriginalThumbnail(original)
                    if (generation == aiGeneration) {
                        mutableState.value = ScreenState.Result(original, thumbnail)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    if (generation == aiGeneration) {
                        mutableState.value =
                            ScreenState.Result(
                                original,
                                null,
                                message = UiMessage(R.string.original_preview_failed),
                            )
                    }
                } finally {
                    if (generation == aiGeneration) processingJob = null
                }
            }
    }

    private fun loadAiPreview(review: ScreenState.AiReview) {
        val generation = startAiOperation(null)
        mutableState.value = review
        processingJob =
            viewModelScope.launch {
                try {
                    val page =
                        when (review.source) {
                            AiReviewSource.Original -> review.original.cached.pages[review.pageIndex]
                            AiReviewSource.Ai -> review.ai.pages[review.pageIndex]
                        }
                    val preview =
                        withContext(Dispatchers.IO) {
                            storage.loadThumbnail(page)
                                ?: throw IOException("AI preview could not be decoded")
                        }
                    if (generation == aiGeneration) {
                        mutableState.value = review.copy(preview = preview)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    if (generation == aiGeneration) {
                        mutableState.value =
                            ScreenState.Processing(UiMessage(R.string.closing_ai_preview))
                    }
                    clearAiWork()
                    val thumbnail = loadOriginalThumbnail(review.original)
                    if (generation == aiGeneration) {
                        mutableState.value =
                            ScreenState.Result(
                                review.original,
                                thumbnail,
                                message = UiMessage(R.string.ai_preview_failed),
                            )
                    }
                } finally {
                    if (generation == aiGeneration) processingJob = null
                }
            }
    }

    private fun startAiOperation(message: UiMessage?): Long {
        processingJob?.cancel()
        val generation = nextAiGeneration()
        if (message != null) mutableState.value = ScreenState.Processing(message)
        return generation
    }

    private fun nextAiGeneration(): Long {
        check(aiGeneration < Long.MAX_VALUE) { "AI request generation exhausted" }
        aiGeneration += 1L
        return aiGeneration
    }

    private suspend fun clearAiWork(): Boolean =
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                storage.clearAiWork()
            } catch (_: Exception) {
                false
            }
        }

    private suspend fun loadOriginalThumbnail(original: SavedScan): Bitmap? =
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                storage.loadThumbnail(original.cached.pages.first())
            } catch (_: Exception) {
                null
            }
        }

    private suspend fun cleanupAiAcceptance(
        original: SavedScan,
        galleryPages: List<Uri>,
        savedPdf: Uri?,
    ): Boolean =
        withContext(NonCancellable + Dispatchers.IO) {
            val outputsDeleted = storage.deleteSavedOutputs(galleryPages + listOfNotNull(savedPdf))
            val cacheDeleted = storage.deleteAiCachedCopy(original.cached)
            val workDeleted =
                try {
                    storage.clearAiWork()
                } catch (_: Exception) {
                    false
                }
            outputsDeleted && cacheDeleted && workDeleted
        }

    private fun completeScannerLaunch() {
        scannerLaunchGate.complete()
        persistScannerStage()
    }

    private fun persistScannerStage() {
        savedStateHandle[SCANNER_STAGE_KEY] = scannerLaunchGate.stage.name
    }

    private fun retryPendingPdfTreeGrantOrThrow() {
        if (retryPendingPdfTreeGrant() != null) {
            throw IOException("Pending PDF destination grant could not be released")
        }
    }

    private fun retryPendingPdfTreeGrant(): UiMessage? {
        val pending = settingsStore.pendingPdfTreeUri() ?: return null
        if (releasePdfTreeGrant(pending) != null) {
            return UiMessage(R.string.pdf_tree_release_warning)
        }
        return try {
            settingsStore.savePdfTreeUris(
                current = mutableSettings.value.pdfTreeUri,
                pending = null,
            )
            null
        } catch (_: IOException) {
            UiMessage(R.string.pdf_tree_release_warning)
        }
    }

    private fun releasePdfTreeGrant(uriValue: String): UiMessage? {
        val resolver = getApplication<Application>().contentResolver
        val uri = Uri.parse(uriValue)
        return try {
            val permission =
                resolver.persistedUriPermissions.firstOrNull { it.uri == uri } ?: return null
            var flags = 0
            if (permission.isReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
            if (permission.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            if (flags == 0) return null
            resolver.releasePersistableUriPermission(uri, flags)
            null
        } catch (_: SecurityException) {
            UiMessage(R.string.pdf_tree_release_warning)
        } catch (_: RuntimeException) {
            UiMessage(R.string.pdf_tree_release_warning)
        }
    }

    private fun <T> withPdfGrantChange(operation: () -> T): T {
        if (!pdfGrantLock.tryLock()) {
            throw IOException("PDF destination cleanup is still in progress")
        }
        return try {
            operation()
        } finally {
            pdfGrantLock.unlock()
        }
    }

    private fun runKeyOperation(
        pending: AiKeyStatus,
        failureMessage: UiMessage,
        operation: () -> AiKeyStatus,
    ) {
        if (
            mutableAiKeyStatus.value is AiKeyStatus.Checking ||
                mutableAiKeyStatus.value is AiKeyStatus.Saving
        ) {
            return
        }
        mutableAiKeyStatus.value = pending
        viewModelScope.launch {
            try {
                mutableAiKeyStatus.value = withContext(Dispatchers.IO) { operation() }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: GeneralSecurityException) {
                mutableAiKeyStatus.value = AiKeyStatus.Error(failureMessage)
            } catch (_: Exception) {
                mutableAiKeyStatus.value = AiKeyStatus.Error(failureMessage)
            }
        }
    }

    private suspend fun cleanup(
        cached: CachedScan?,
        galleryPages: List<Uri>,
        savedPdf: Uri?,
    ): Boolean =
        withContext(NonCancellable + Dispatchers.IO) {
            val outputsDeleted =
                storage.deleteSavedOutputs(galleryPages + listOfNotNull(savedPdf))
            val cacheDeleted = cached == null || storage.deleteCachedScan(cached)
            outputsDeleted && cacheDeleted
        }
}
