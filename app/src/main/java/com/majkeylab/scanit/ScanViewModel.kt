package com.majkeylab.scanit

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val SCANNER_STAGE_KEY = "scanner_launch_stage"
private const val ROUTE_KEY = "screen_route"
private const val ROUTE_CACHE_ID_KEY = "screen_route_cache_id"
private const val ROUTE_SCANNER = "scanner"
private const val ROUTE_RECENT = "recent"
private const val ROUTE_RESULT = "result"
private const val ROUTE_FAILURE = "failure"
private const val RECENT_THUMBNAIL_SIZE = 256
internal const val PDF_TREE_FLAGS =
    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

internal fun scannerPreparationMayResume(
    navigationInitialized: Boolean,
    route: String?,
): Boolean = navigationInitialized && route == ROUTE_SCANNER

internal enum class ScannerLaunchStage {
    Idle,
    Preparing,
    Launched,
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

internal data class RecentAction(
    val cacheId: String,
    val entryId: String?,
    val generation: Long,
)

internal class RecentActionGate {
    private var generation = 0L

    fun begin(cacheId: String, entryId: String?): RecentAction =
        RecentAction(cacheId, entryId, nextGeneration())

    fun invalidate() {
        nextGeneration()
    }

    fun isCurrent(action: RecentAction, identities: Iterable<Pair<String, String?>>): Boolean =
        action.generation == generation &&
            identities.any { (cacheId, entryId) ->
                cacheId == action.cacheId && entryId == action.entryId
            }

    private fun nextGeneration(): Long {
        check(generation < Long.MAX_VALUE) { "Recent action generation exhausted" }
        generation += 1L
        return generation
    }
}

internal data class RecentDeletion(
    val cacheId: String,
    val generation: Long,
)

internal class RecentDeletionGate {
    private var generation = 0L
    private var current: RecentDeletion? = null

    fun begin(cacheId: String): RecentDeletion {
        val deletion = RecentDeletion(cacheId, nextGeneration())
        current = deletion
        return deletion
    }

    fun isCurrent(deletion: RecentDeletion): Boolean = current == deletion

    fun complete(deletion: RecentDeletion) {
        if (current == deletion) {
            current = null
        }
    }

    fun invalidateCurrent() {
        current = null
    }

    private fun nextGeneration(): Long {
        check(generation < Long.MAX_VALUE) { "Recent deletion generation exhausted" }
        generation += 1L
        return generation
    }
}

internal class RouteMutationGate {
    private var generation = 0L

    fun begin(): Long = nextGeneration()

    fun isCurrent(requestGeneration: Long): Boolean = requestGeneration == generation

    private fun nextGeneration(): Long {
        check(generation < Long.MAX_VALUE) { "Route mutation generation exhausted" }
        generation += 1L
        return generation
    }
}

private enum class CheckpointMutationResult {
    Applied,
    Failed,
    Stale,
}

private data class CheckpointReadResult(
    val mutation: CheckpointMutationResult,
    val cacheId: String? = null,
)

private data class OutputSaveResult(
    val scan: SavedScan?,
    val successful: Set<SavedOutputKind>,
    val warnings: List<UiMessage>,
)

private enum class ResultActivation {
    Applied,
    Rejected,
    Stale,
}

internal fun initialScreenState(route: RestoredRoute?): ScreenState =
    when (route) {
        null ->
            ScreenState.Processing(
                UiMessage(R.string.starting_scanit),
                canNavigateBack = false,
            )
        RestoredRoute.Scanner -> ScreenState.Ready
        RestoredRoute.Recent -> ScreenState.Recent(emptyList())
        RestoredRoute.Result ->
            ScreenState.Processing(
                UiMessage(R.string.opening_document),
                canNavigateBack = false,
            )
    }

internal class ScanViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val settingsStore = SettingsStore(application)
    private val savedRoute = savedStateHandle.get<String>(ROUTE_KEY)
    private val savedCacheId = savedStateHandle.get<String>(ROUTE_CACHE_ID_KEY)
    private val storage = ScanStorage(application)
    private val scannerLaunchGate =
        ScannerLaunchGate(
            ScannerLaunchStage.entries.firstOrNull {
                it.name == savedStateHandle.get<String>(SCANNER_STAGE_KEY)
            } ?: ScannerLaunchStage.Idle,
        )
    private val recentActionGate = RecentActionGate()
    private val recentDeletionGate = RecentDeletionGate()
    private val resultSaveGate = ResultSaveGate()
    private val routeMutationGate = RouteMutationGate()
    private val routeMutationMutex = Mutex()
    private val mutableState = MutableStateFlow(initialScreenState(null))
    private val mutableScannerRequest = MutableStateFlow<Long?>(null)
    private val mutableSettings = MutableStateFlow(settingsStore.load())
    private var processingJob: Job? = null
    private var recentJob: Job? = null
    private var cacheRefreshJob: Job? = null
    private var outputSaveJob: Job? = null
    private var shareRefreshJob: Job? = null
    private var recentScans: List<RecentScan> = emptyList()
    private var navigationInitialized = false

    val state: StateFlow<ScreenState> = mutableState.asStateFlow()
    val scannerRequest: StateFlow<Long?> = mutableScannerRequest.asStateFlow()
    val settings: StateFlow<AppSettings> = mutableSettings.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                retryPendingPdfTreeGrant()
            } catch (_: IOException) {
                // The single pending grant remains recorded for the next retry.
            } catch (_: RuntimeException) {
                // The single pending grant remains recorded for the next retry.
            }
        }
        restoreNavigation()
    }

    fun currentSettings(): AppSettings = mutableSettings.value

    fun refreshAfterShareCleanup() {
        val snapshot = mutableState.value
        if (snapshot !is ScreenState.Result && snapshot !is ScreenState.Recent) return
        if (shareRefreshJob?.isActive == true) return
        shareRefreshJob =
            viewModelScope.launch {
                when (snapshot) {
                    is ScreenState.Result -> {
                        val cacheId = snapshot.scan.cached.baseName
                        val entryId = snapshot.scan.cached.entryId
                        val saved =
                            try {
                                withContext(Dispatchers.IO) { storage.openSavedScan(cacheId) }
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (_: Exception) {
                                null
                            }
                        val latest = mutableState.value as? ScreenState.Result ?: return@launch
                        val refreshed = saved?.takeIf { it.cached.entryId == entryId }
                        if (
                            latest.scan.cached.baseName == cacheId &&
                                latest.scan.cached.entryId == entryId &&
                                refreshed != null
                        ) {
                            mutableState.value =
                                latest.copy(scan = refreshed.copy(warnings = latest.scan.warnings))
                            refreshRecentCache(cacheId)
                        }
                    }
                    is ScreenState.Recent -> {
                        val scans = loadRecentScans()
                        val latest = mutableState.value as? ScreenState.Recent ?: return@launch
                        if (scans != null) {
                            recentScans = scans
                            mutableState.value = latest.copy(scans = scans)
                        }
                    }
                }
            }
    }

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

    fun beginScannerLaunch() {
        if (processingJob?.isActive == true) return
        if ((mutableState.value as? ScreenState.Recent)?.deletionInProgress == true) return
        completeScannerLaunch()
        val generation = beginRouteMutation()
        recentJob =
            viewModelScope.launch {
                clearCheckpointAndPublish(generation) {
                    publishScannerRequest()
                }
            }
    }

    fun resumeScannerPreparation() {
        if (!scannerPreparationMayResume(navigationInitialized, savedStateHandle[ROUTE_KEY])) {
            return
        }
        val request =
            scannerLaunchGate.resumePreparing(processingJob?.isActive == true) ?: return
        persistScannerStage()
        mutableState.value =
            ScreenState.Processing(
                UiMessage(R.string.opening_scanner),
                canNavigateBack = true,
            )
        mutableScannerRequest.value = request
    }

    fun claimScannerRequest(requestGeneration: Long): Boolean {
        if (mutableScannerRequest.value != requestGeneration) return false
        mutableScannerRequest.value = null
        return true
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
            val generation = beginRouteMutation()
            recentJob =
                viewModelScope.launch {
                    clearCheckpointAndPublish(generation) {
                        persistRoute(ROUTE_FAILURE)
                        mutableState.value = ScreenState.Failure(message)
                    }
                }
        }
    }

    fun scannerCancelled() {
        completeScannerLaunch()
        if (processingJob?.isActive != true) {
            refreshRecentScreen()
        }
    }

    fun scannerResultFailed(message: UiMessage) {
        completeScannerLaunch()
        if (processingJob?.isActive != true) {
            val generation = beginRouteMutation()
            recentJob =
                viewModelScope.launch {
                    clearCheckpointAndPublish(generation) {
                        persistRoute(ROUTE_FAILURE)
                        mutableState.value = ScreenState.Failure(message)
                    }
                }
        }
    }

    fun processScan(pageUris: List<Uri>, pdfUri: Uri): Boolean {
        completeScannerLaunch()
        if (processingJob?.isActive == true) {
            mutableState.value =
                ScreenState.Processing(
                    UiMessage(R.string.saving_document),
                    canNavigateBack = false,
                )
            return false
        }

        val pages = pageUris.toList()
        val generation = beginRouteMutation()
        mutableState.value =
            ScreenState.Processing(
                UiMessage(R.string.saving_document),
                canNavigateBack = false,
            )
        processingJob =
            viewModelScope.launch {
                var cached: CachedScan? = null
                var galleryPages: List<Uri> = emptyList()
                var savedPdfUri: Uri? = null
                var savedPdfTreeUri: Uri? = null
                var completedResult: ScreenState.Result? = null
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
                                } catch (failure: ImageSaveFailure) {
                                    warnings += imageSaveFailureMessages(failure)
                                } catch (_: Exception) {
                                    warnings += UiMessage(R.string.images_save_failed)
                                }
                            }
                            currentCoroutineContext().ensureActive()
                            if (settings.savePdf) {
                                try {
                                    val savedPdf =
                                        storage.savePdf(
                                            cachedScan,
                                            settings.albumName,
                                            settings.pdfTreeUri,
                                        )
                                    savedPdfUri = savedPdf.uri
                                    savedPdfTreeUri = savedPdf.treeUri
                                    savedPdf.warning?.let(warnings::add)
                                } catch (cancellation: CancellationException) {
                                    throw cancellation
                                } catch (failure: PdfSaveFailure) {
                                    warnings += pdfSaveFailureMessages(failure)
                                } catch (_: Exception) {
                                    warnings += UiMessage(R.string.pdf_save_failed)
                                }
                            }
                            val result =
                                ScreenState.Result(
                                    scan =
                                        SavedScan(
                                            cached = cachedScan,
                                            galleryPages = galleryPages,
                                            savedPdf = savedPdfUri,
                                            savedPdfTree = savedPdfTreeUri,
                                            warnings = warnings,
                                            outputMetadataValid = true,
                                            savedPdfDeleteVerified = savedPdfUri != null,
                                            savedImagesDeleteVerified = galleryPages.isNotEmpty(),
                                        ),
                                    thumbnail = thumbnail,
                                )
                            completedResult = result
                            currentCoroutineContext().ensureActive()
                            result
                        }
                    when (
                        activateCachedResult(generation, result.scan.cached.baseName, result)
                    ) {
                        ResultActivation.Applied -> Unit
                        ResultActivation.Rejected -> {
                            val cleanupComplete = cleanup(cached, galleryPages, savedPdfUri)
                            if (routeMutationGate.isCurrent(generation)) {
                                mutableState.value =
                                    ScreenState.Failure(
                                        UiMessage(
                                            if (cleanupComplete) {
                                                R.string.document_save_failed
                                            } else {
                                                R.string.document_save_partial_failed
                                            },
                                        ),
                                    )
                                persistRoute(ROUTE_FAILURE)
                            }
                        }
                        ResultActivation.Stale -> cleanup(cached, galleryPages, savedPdfUri)
                    }
                } catch (exception: CancellationException) {
                    val retainedResult = completedResult
                    when (
                        withContext(NonCancellable) {
                            clearCheckpointAndPublish(
                                generation = generation,
                                onFailure = { retainedResult?.let(::publishResult) },
                                onSuccess = {},
                            )
                        }
                    ) {
                        CheckpointMutationResult.Failed -> {
                            if (retainedResult == null) {
                                cleanup(cached, galleryPages, savedPdfUri)
                            }
                        }
                        CheckpointMutationResult.Applied,
                        CheckpointMutationResult.Stale,
                        -> cleanup(cached, galleryPages, savedPdfUri)
                    }
                    throw exception
                } catch (exception: Exception) {
                    val retainedResult = completedResult
                    val checkpoint =
                        clearCheckpointAndPublish(
                            generation = generation,
                            onFailure = { retainedResult?.let(::publishResult) },
                            onSuccess = {},
                        )
                    val retained =
                        checkpoint == CheckpointMutationResult.Failed && retainedResult != null
                    if (!retained && routeMutationGate.isCurrent(generation)) {
                        val cleanupComplete = cleanup(cached, galleryPages, savedPdfUri)
                        val message =
                            if (cleanupComplete && exception.suppressed.isEmpty()) {
                                UiMessage(R.string.document_save_failed)
                            } else {
                                UiMessage(R.string.document_save_partial_failed)
                            }
                        mutableState.value = ScreenState.Failure(message)
                        persistRoute(ROUTE_FAILURE)
                    }
                } finally {
                    processingJob = null
                }
            }
        return true
    }

    fun showRecent() {
        refreshRecentScreen()
    }

    fun saveCurrentOutputs(target: SaveNowTarget) {
        val current = mutableState.value as? ScreenState.Result ?: return
        val entryId = current.scan.cached.entryId ?: return
        if (target !in saveNowTargets(current.scan)) return
        val action = resultSaveGate.begin(current.scan.cached.baseName, entryId) ?: return
        mutableState.value = current.copy(outputSaveInProgress = true)
        outputSaveJob =
            viewModelScope.launch {
                try {
                    val result =
                        withContext(Dispatchers.IO) {
                            saveCurrentOutputs(current.scan, target)
                        }
                    val latest = mutableState.value as? ScreenState.Result ?: return@launch
                    val latestEntryId = latest.scan.cached.entryId ?: return@launch
                    if (
                        !resultSaveGate.isCurrent(
                            action,
                            latest.scan.cached.baseName,
                            latestEntryId,
                        )
                    ) {
                        return@launch
                    }
                    val saved = matchingSavedScan(result.scan, action)
                    val warnings =
                        mergeSaveNowWarnings(
                            latest.scan.warnings,
                            result.successful,
                            reloadSucceeded = saved != null,
                            added =
                                result.warnings +
                                listOfNotNull(
                                    UiMessage(R.string.state_update_failed).takeIf { saved == null },
                                ),
                        )
                    mutableState.value =
                        latest.copy(
                            scan = (saved ?: latest.scan).copy(warnings = warnings),
                            outputSaveInProgress = false,
                        )
                    refreshRecentCache(action.cacheId)
                } finally {
                    val latest = mutableState.value as? ScreenState.Result
                    val latestEntryId = latest?.scan?.cached?.entryId
                    if (
                        latest != null &&
                            latestEntryId != null &&
                            resultSaveGate.isCurrent(
                                action,
                                latest.scan.cached.baseName,
                                latestEntryId,
                            )
                    ) {
                        mutableState.value = latest.copy(outputSaveInProgress = false)
                    }
                    resultSaveGate.complete(action)
                }
            }
    }

    fun navigateBack() {
        recentActionGate.invalidate()
        val current = mutableState.value
        if (current is ScreenState.Recent && current.deletionInProgress) return
        if (current is ScreenState.Processing && !current.canNavigateBack) {
            return
        }
        completeScannerLaunch()
        refreshRecentScreen()
    }

    fun openRecentScan(cacheId: String) {
        val current = mutableState.value as? ScreenState.Recent ?: return
        if (current.deletionInProgress || current.scans.none { it.cacheId == cacheId }) return
        val generation = beginRouteMutation()
        recentJob =
            viewModelScope.launch {
                if (
                    clearCheckpointAndPublish(generation) {
                        persistRoute(ROUTE_RECENT)
                        mutableState.value =
                            ScreenState.Processing(
                                UiMessage(R.string.opening_document),
                                canNavigateBack = true,
                            )
                    } != CheckpointMutationResult.Applied
                ) {
                    return@launch
                }
                val result = loadCachedResult(cacheId)
                val activation =
                    result?.let { activateCachedResult(generation, cacheId, it) }
                if (result == null || activation == ResultActivation.Rejected) {
                    showRecentResult(
                        generation = generation,
                        message = UiMessage(R.string.recent_scan_unavailable),
                    )
                }
            }
    }

    fun deleteRecentScan(request: OutputDeleteRequest) {
        val current = mutableState.value as? ScreenState.Recent ?: return
        val scan = current.scans.firstOrNull { it.cacheId == request.cacheId } ?: return
        if (!recentDeleteRequestAvailable(scan, request) || current.deletionInProgress) return
        val generation = beginRouteMutation()
        val deletion = recentDeletionGate.begin(request.cacheId)
        mutableState.value = current.copy(deletionInProgress = true)
        recentJob =
            viewModelScope.launch {
                try {
                    if (
                        clearCheckpointAndPublish(
                            generation = generation,
                            onFailure = {
                                persistRoute(ROUTE_RECENT)
                                mutableState.value =
                                    current.copy(
                                        message = UiMessage(R.string.state_update_failed),
                                        deletionInProgress = false,
                                    )
                            },
                            onSuccess = { persistRoute(ROUTE_RECENT) },
                        ) != CheckpointMutationResult.Applied
                    ) {
                        return@launch
                    }
                    val result =
                        try {
                            withContext(Dispatchers.IO) {
                                val result =
                                    if (request.target == RecentDeleteTarget.RemoveFromRecent) {
                                        if (storage.removeRecentPreview(request)) {
                                            OutputDeleteOperationResult.Completed
                                        } else {
                                            OutputDeleteOperationResult.Failed
                                        }
                                    } else {
                                        storage.deleteDurableOutputs(
                                            request,
                                            deleteRecentCache = true,
                                        )
                                    }
                                if (request.target != RecentDeleteTarget.RemoveFromRecent) {
                                    reconcilePdfTreeGrantsAfterOutputChange()
                                }
                                result
                            }
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Exception) {
                            OutputDeleteOperationResult.Failed
                        }
                    if (
                        !routeMutationGate.isCurrent(generation) ||
                        !recentDeletionGate.isCurrent(deletion)
                    ) {
                        return@launch
                    }
                    showRecentResult(
                        generation = generation,
                        message = recentDeleteMessage(result),
                    )
                } finally {
                    recentDeletionGate.complete(deletion)
                }
            }
    }

    fun beginRecentShare(cacheId: String): RecentAction? {
        val current = mutableState.value as? ScreenState.Recent ?: return null
        if (current.deletionInProgress) return null
        val scan = current.scans.firstOrNull { it.cacheId == cacheId } ?: return null
        return recentActionGate.begin(cacheId, scan.entryId)
    }

    suspend fun recentScanForShare(action: RecentAction): SavedScan? =
        try {
            withContext(Dispatchers.IO) {
                storage.openSavedScan(action.cacheId)?.takeIf {
                    it.cached.entryId == action.entryId
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }

    fun claimRecentShare(action: RecentAction): Boolean {
        if (!isRecentShareCurrent(action)) {
            return false
        }
        recentActionGate.invalidate()
        return true
    }

    fun recentShareUnavailable(action: RecentAction): Boolean {
        if (!claimRecentShare(action)) {
            return false
        }
        refreshRecentScreen(message = UiMessage(R.string.recent_scan_unavailable))
        return true
    }

    private fun isRecentShareCurrent(action: RecentAction): Boolean {
        val current = mutableState.value as? ScreenState.Recent ?: return false
        return recentActionGate.isCurrent(
            action,
            current.scans.map { it.cacheId to it.entryId },
        )
    }

    suspend fun loadRecentThumbnail(firstPage: File): Bitmap? =
        withContext(Dispatchers.IO) {
            storage.loadThumbnail(firstPage, RECENT_THUMBNAIL_SIZE)
        }

    private fun restoreNavigation() {
        val generation = beginRouteMutation()
        recentJob =
            viewModelScope.launch {
                val checkpoint = readActiveResultCheckpoint(generation)
                if (checkpoint.mutation != CheckpointMutationResult.Applied) return@launch
                val destination = initialNavigation(savedRoute, savedCacheId, checkpoint.cacheId)
                when (destination.route) {
                    RestoredRoute.Result -> {
                        val cacheId = checkNotNull(destination.cacheId)
                        val result = loadCachedResult(cacheId)
                        if (result == null) {
                            if (
                                clearCheckpointAndPublish(generation) {
                                    navigationInitialized = true
                                    persistRoute(ROUTE_RECENT)
                                    mutableState.value =
                                        ScreenState.Recent(
                                            recentScans,
                                            UiMessage(R.string.recent_scan_unavailable),
                                        )
                                } == CheckpointMutationResult.Applied
                            ) {
                                showRecentResult(
                                    generation,
                                    UiMessage(R.string.recent_scan_unavailable),
                                )
                            }
                        } else {
                            routeMutationMutex.withLock {
                                if (routeMutationGate.isCurrent(generation)) {
                                    navigationInitialized = true
                                    publishResult(result)
                                }
                            }
                        }
                    }
                    RestoredRoute.Recent -> {
                        routeMutationMutex.withLock {
                            if (routeMutationGate.isCurrent(generation)) {
                                navigationInitialized = true
                                persistRoute(ROUTE_RECENT)
                                mutableState.value = ScreenState.Recent(recentScans)
                            }
                        }
                        showRecentResult(generation)
                    }
                    RestoredRoute.Scanner -> {
                        routeMutationMutex.withLock {
                            if (routeMutationGate.isCurrent(generation)) {
                                publishScannerRequest()
                            }
                        }
                    }
                }
            }
    }

    private suspend fun loadCachedResult(cacheId: String): ScreenState.Result? {
        if (!isSafeActiveResultCacheId(cacheId)) return null
        return try {
            withContext(Dispatchers.IO) {
                val saved = storage.openSavedScan(cacheId) ?: return@withContext null
                val thumbnail = storage.loadThumbnail(saved.cached.pages.first())
                ScreenState.Result(
                    scan = saved,
                    thumbnail = thumbnail,
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun activateCachedResult(
        generation: Long,
        cacheId: String,
        result: ScreenState.Result,
    ): ResultActivation =
        routeMutationMutex.withLock {
            if (!routeMutationGate.isCurrent(generation)) return@withLock ResultActivation.Stale
            try {
                withContext(Dispatchers.IO) { settingsStore.saveActiveResult(cacheId) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: IOException) {
                return@withLock recoverFailedResultCheckpoint(generation, result)
            } catch (_: IllegalArgumentException) {
                return@withLock recoverFailedResultCheckpoint(generation, result)
            }
            if (!routeMutationGate.isCurrent(generation)) return@withLock ResultActivation.Stale
            publishResult(result)
            ResultActivation.Applied
        }

    private suspend fun recoverFailedResultCheckpoint(
        generation: Long,
        result: ScreenState.Result,
    ): ResultActivation {
        val cleared =
            try {
                withContext(Dispatchers.IO) { settingsStore.clearActiveResult() }
                true
            } catch (_: IOException) {
                false
            }
        if (!routeMutationGate.isCurrent(generation)) return ResultActivation.Stale
        return if (cleared) {
            ResultActivation.Rejected
        } else {
            publishResult(result)
            ResultActivation.Applied
        }
    }

    private fun publishResult(result: ScreenState.Result) {
        val cacheId = result.scan.cached.baseName
        mutableState.value = result
        persistRoute(ROUTE_RESULT, cacheId)
        refreshRecentCache(cacheId)
    }

    private fun refreshRecentScreen(message: UiMessage? = null) {
        val generation = beginRouteMutation()
        recentJob =
            viewModelScope.launch {
                if (
                    clearCheckpointAndPublish(generation) {
                        navigationInitialized = true
                        persistRoute(ROUTE_RECENT)
                        mutableState.value = ScreenState.Recent(recentScans, message)
                    } != CheckpointMutationResult.Applied
                ) {
                    return@launch
                }
                showRecentResult(generation, message)
            }
    }

    private suspend fun showRecentResult(
        generation: Long,
        message: UiMessage? = null,
    ) {
        val scans = loadRecentScans()
        val effectiveMessage =
            message ?: when {
                scans == null -> UiMessage(R.string.recent_history_unavailable)
                else -> null
            }
        routeMutationMutex.withLock {
            if (!routeMutationGate.isCurrent(generation)) return@withLock
            recentScans = scans ?: emptyList()
            mutableState.value = ScreenState.Recent(recentScans, effectiveMessage)
            persistRoute(ROUTE_RECENT)
        }
    }

    private suspend fun loadRecentScans(protectedCacheId: String? = null): List<RecentScan>? =
        try {
            withContext(Dispatchers.IO) {
                storage.listRecentScans(protectedCacheId?.let(::setOf) ?: emptySet())
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }

    private fun refreshRecentCache(protectedCacheId: String? = null) {
        cacheRefreshJob?.cancel()
        cacheRefreshJob =
            viewModelScope.launch {
                loadRecentScans(protectedCacheId)?.let { recentScans = it }
            }
    }

    private fun persistRoute(route: String, cacheId: String? = null) {
        savedStateHandle[ROUTE_KEY] = route
        savedStateHandle[ROUTE_CACHE_ID_KEY] = cacheId
    }

    private fun beginRouteMutation(): Long {
        resultSaveGate.invalidate()
        outputSaveJob?.cancel()
        recentActionGate.invalidate()
        recentDeletionGate.invalidateCurrent()
        recentJob?.cancel()
        return routeMutationGate.begin()
    }

    private suspend fun saveCurrentOutputs(
        scan: SavedScan,
        target: SaveNowTarget,
    ): OutputSaveResult {
        val settings = currentSettings()
        val successful = mutableSetOf<SavedOutputKind>()
        val warnings = mutableListOf<UiMessage>()
        if (target == SaveNowTarget.Images || target == SaveNowTarget.Both) {
            try {
                storage.saveImages(scan.cached, settings.albumName)
                successful += SavedOutputKind.Images
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: ImageSaveFailure) {
                warnings += imageSaveFailureMessages(failure)
            } catch (_: Exception) {
                warnings += UiMessage(R.string.images_save_failed)
            }
        }
        currentCoroutineContext().ensureActive()
        if (target == SaveNowTarget.Pdf || target == SaveNowTarget.Both) {
            try {
                val saved =
                    storage.savePdf(scan.cached, settings.albumName, settings.pdfTreeUri)
                successful += SavedOutputKind.Pdf
                saved.warning?.let(warnings::add)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: PdfSaveFailure) {
                warnings += pdfSaveFailureMessages(failure)
            } catch (_: Exception) {
                warnings += UiMessage(R.string.pdf_save_failed)
            }
        }
        currentCoroutineContext().ensureActive()
        return OutputSaveResult(
            scan =
                try {
                    storage.openSavedScan(scan.cached.baseName)
                } catch (_: Exception) {
                    null
                },
            successful = successful,
            warnings = warnings.distinct(),
        )
    }

    private suspend fun readActiveResultCheckpoint(generation: Long): CheckpointReadResult =
        routeMutationMutex.withLock {
            if (!routeMutationGate.isCurrent(generation)) {
                return@withLock CheckpointReadResult(CheckpointMutationResult.Stale)
            }
            val cacheId =
                try {
                    withContext(Dispatchers.IO) { settingsStore.activeResultCacheId() }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: IOException) {
                    if (routeMutationGate.isCurrent(generation)) {
                        persistRoute(ROUTE_FAILURE)
                        mutableState.value =
                            ScreenState.Failure(UiMessage(R.string.state_update_failed))
                    }
                    return@withLock CheckpointReadResult(CheckpointMutationResult.Failed)
                }
            if (!routeMutationGate.isCurrent(generation)) {
                CheckpointReadResult(CheckpointMutationResult.Stale)
            } else {
                CheckpointReadResult(CheckpointMutationResult.Applied, cacheId)
            }
        }

    private suspend fun clearCheckpointAndPublish(
        generation: Long,
        onFailure: () -> Unit = {
            persistRoute(ROUTE_FAILURE)
            mutableState.value = ScreenState.Failure(UiMessage(R.string.state_update_failed))
        },
        onSuccess: () -> Unit,
    ): CheckpointMutationResult =
        routeMutationMutex.withLock {
            if (!routeMutationGate.isCurrent(generation)) {
                return@withLock CheckpointMutationResult.Stale
            }
            try {
                withContext(Dispatchers.IO) { settingsStore.clearActiveResult() }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: IOException) {
                if (!routeMutationGate.isCurrent(generation)) {
                    return@withLock CheckpointMutationResult.Stale
                }
                onFailure()
                return@withLock CheckpointMutationResult.Failed
            }
            if (!routeMutationGate.isCurrent(generation)) {
                return@withLock CheckpointMutationResult.Stale
            }
            onSuccess()
            CheckpointMutationResult.Applied
        }

    private fun publishScannerRequest() {
        val request =
            when (scannerLaunchGate.stage) {
                ScannerLaunchStage.Preparing ->
                    scannerLaunchGate.resumePreparing(processingJob?.isActive == true)
                ScannerLaunchStage.Idle -> scannerLaunchGate.begin(processingJob?.isActive == true)
                ScannerLaunchStage.Launched -> null
            } ?: return
        navigationInitialized = true
        persistRoute(ROUTE_SCANNER)
        persistScannerStage()
        mutableState.value =
            ScreenState.Processing(
                UiMessage(R.string.opening_scanner),
                canNavigateBack = true,
            )
        mutableScannerRequest.value = request
    }

    private fun completeScannerLaunch() {
        scannerLaunchGate.complete()
        mutableScannerRequest.value = null
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

    private fun retryPendingPdfTreeGrant(): UiMessage? = withStorageTransaction {
        val live =
            try {
                storage.livePdfTreeUris()
            } catch (_: IOException) {
                return@withStorageTransaction UiMessage(R.string.pdf_tree_release_warning)
            } catch (_: RuntimeException) {
                return@withStorageTransaction UiMessage(R.string.pdf_tree_release_warning)
            }
        if (
            !reconcilePdfTreeGrants(
                context = getApplication(),
                current = mutableSettings.value.pdfTreeUri,
                live = live,
            )
        ) {
            return@withStorageTransaction UiMessage(R.string.pdf_tree_release_warning)
        }
        if (settingsStore.pendingPdfTreeUri() == null) return@withStorageTransaction null
        try {
            settingsStore.savePdfTreeUris(
                current = mutableSettings.value.pdfTreeUri,
                pending = null,
            )
            null
        } catch (_: IOException) {
            UiMessage(R.string.pdf_tree_release_warning)
        }
    }

    private fun reconcilePdfTreeGrantsAfterOutputChange() {
        retryPendingPdfTreeGrant()
    }

    private fun <T> withPdfGrantChange(operation: () -> T): T {
        return tryStorageTransaction(operation)
            ?: throw IOException("PDF destination cleanup is still in progress")
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
