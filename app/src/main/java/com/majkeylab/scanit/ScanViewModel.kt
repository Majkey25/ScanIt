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
import kotlinx.coroutines.isActive
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
private const val RESULT_PAGE_INDEX_KEY = "result_page_index"
private const val RESULT_PREVIEW_SIZE = 1024
private const val PAGE_THUMBNAIL_SIZE = 256
internal const val PDF_TREE_FLAGS =
    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

internal fun pdfSizeTargetWarning(result: ScanPdfBuildResult): UiMessage {
    require(!result.targetMet && result.bytes > 0L) { "PDF warning requires an unmet target" }
    return requireNotNull(pdfSizeTargetWarning(result.target, result.bytes)) {
        "PDF warning requires an exceeded size target"
    }
}

internal fun settingsSaveAllowed(state: ScreenState): Boolean =
    (state as? ScreenState.Result)?.appearanceApplyInProgress != true

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
    val checkpoint: ActiveResultCheckpoint? = null,
    val authoritativeWasProvisional: Boolean = false,
)

internal class ActiveResultCleanupException(cause: Exception) :
    IOException("Active result was cleared but provisional cleanup failed", cause)

internal fun clearActiveResultAndReconcileProvisionals(
    clearCheckpoint: () -> AuthorityMutationResult,
    reconcileProvisionals: () -> Unit,
): AuthorityMutationResult =
    withActiveResultAuthority {
        val cleared = clearCheckpoint()
        if (cleared != AuthorityMutationResult.Applied) {
            return@withActiveResultAuthority cleared
        }
        try {
            reconcileProvisionals()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            throw ActiveResultCleanupException(failure)
        }
        AuthorityMutationResult.Applied
    }

internal fun keepCheckpointAfterRestoreFailure(authoritativeWasProvisional: Boolean): Boolean =
    authoritativeWasProvisional

internal fun checkpointRestoreSettings(
    current: AppSettings,
    authoritative: CachedScan?,
    provisional: Boolean,
): AppSettings {
    if (!provisional) return current
    val cached = checkNotNull(authoritative)
    return current.copy(
        appearance = checkNotNull(cached.appearanceSettings),
        pdfSizeTarget = cached.pdfSizeTarget,
    )
}

private data class OutputSaveResult(
    val scan: SavedScan?,
    val successful: Set<SavedOutputKind>,
    val warnings: List<UiMessage>,
)

internal fun automaticOutputTarget(settings: AppSettings): SaveNowTarget? =
    when {
        settings.savePdf && settings.saveImages -> SaveNowTarget.Both
        settings.savePdf -> SaveNowTarget.Pdf
        settings.saveImages -> SaveNowTarget.Images
        else -> null
    }

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
    private val shareRefreshGate = DirtyRefreshGate()
    private val routeMutationMutex = Mutex()
    private val mutableState = MutableStateFlow(initialScreenState(null))
    private val mutableScannerRequest = MutableStateFlow<Long?>(null)
    private val mutableSettings = MutableStateFlow(settingsStore.load())
    private var processingJob: Job? = null
    private var recentJob: Job? = null
    private var cacheRefreshJob: Job? = null
    private var outputSaveJob: Job? = null
    private var appearanceApplyJob: Job? = null
    private var resultPreviewJob: Job? = null
    private var recentScans: List<RecentScan> = emptyList()
    private var navigationInitialized = false
    private var activeResultAuthorityKnown = false
    private var activeResultOwner: ActiveResultOwner? = null

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
        if (mutableState.value !is ScreenState.Result && mutableState.value !is ScreenState.Recent) {
            return
        }
        if (!shareRefreshGate.request()) return
        viewModelScope.launch {
            while (shareRefreshGate.consume()) {
                when (val snapshot = mutableState.value) {
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
                        val latest = mutableState.value as? ScreenState.Result ?: continue
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
                        val latest = mutableState.value as? ScreenState.Recent ?: continue
                        if (scans != null) {
                            recentScans = scans
                            mutableState.value = latest.copy(scans = scans)
                        }
                    }
                    else -> Unit
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
            settingsStore.saveEmailSubject(localized)
            mutableSettings.value = mutableSettings.value.copy(emailSubject = localized)
        }
    }

    fun saveSettings(settings: AppSettings): Boolean {
        if (!settingsSaveAllowed(mutableState.value) || !activeResultAuthorityKnown) return false
        val owner = activeResultOwner ?: return false
        val normalized =
            settings.copy(
                albumName = normalizeAlbumName(settings.albumName),
                pdfTreeUri = mutableSettings.value.pdfTreeUri,
            )
        val saved =
            try {
                settingsStore.trySave(normalized, owner)
            } catch (_: IOException) {
                return false
            } catch (_: RuntimeException) {
                return false
            }
        if (!settingsSaveApplied(saved)) return false
        mutableSettings.value = normalized
        return true
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
            val oldUri = canonicalPdfTreeUri(settingsStore)
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
            val oldUri = canonicalPdfTreeUri(settingsStore) ?: return@withPdfGrantChange null
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

    fun processScan(pageUris: List<Uri>): Boolean {
        completeScannerLaunch()
        if (!isAcceptedScanPageCount(pageUris.size)) {
            scannerResultFailed(UiMessage(R.string.scanner_result_error))
            return false
        }
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
                            val processingContext = currentCoroutineContext()
                            val cacheBuild =
                                storage.cacheScan(
                                    pageUris = pages,
                                    appearanceSettings = settings.appearance,
                                    pdfSizeTarget = settings.pdfSizeTarget,
                                    isCancelled = { !processingContext.isActive },
                                )
                            val cachedScan = cacheBuild.cached
                            cached = cachedScan
                            val thumbnail =
                                storage.loadThumbnail(
                                    cachedScan.pages.first(),
                                    RESULT_PREVIEW_SIZE,
                                )
                            val warnings = mutableListOf<UiMessage>()
                            if (!cacheBuild.pdf.targetMet) {
                                warnings += pdfSizeTargetWarning(cacheBuild.pdf)
                            }
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
                                        savePdfToCanonicalDestination(
                                            cachedScan,
                                            settings.albumName,
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

    fun selectResultPage(selectedPageIndex: Int) {
        val current = mutableState.value as? ScreenState.Result ?: return
        if (current.outputSaveInProgress || current.appearanceApplyInProgress) return
        val pageIndex = resolvedPageIndex(selectedPageIndex, current.scan.cached.pages.size)
        if (
            pageIndex == current.selectedPageIndex &&
                (current.thumbnail != null || current.pagePreviewLoading)
        ) {
            return
        }
        val request =
            ResultPageLoad(
                cacheId = current.scan.cached.baseName,
                entryId = current.scan.cached.entryId,
                pageIndex = pageIndex,
            )
        resultPreviewJob?.cancel()
        savedStateHandle[RESULT_PAGE_INDEX_KEY] = pageIndex
        mutableState.value =
            current.copy(
                thumbnail = null,
                selectedPageIndex = pageIndex,
                pagePreviewLoading = true,
            )
        resultPreviewJob =
            viewModelScope.launch {
                val thumbnail =
                    try {
                        withContext(Dispatchers.IO) {
                            storage.loadThumbnail(
                                current.scan.cached.pages[pageIndex],
                                RESULT_PREVIEW_SIZE,
                            )
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        null
                    }
                val latest = mutableState.value as? ScreenState.Result ?: return@launch
                if (
                    request.isCurrent(
                        cacheId = latest.scan.cached.baseName,
                        entryId = latest.scan.cached.entryId,
                        selectedPageIndex = latest.selectedPageIndex,
                    )
                ) {
                    mutableState.value =
                        latest.copy(
                            thumbnail = thumbnail,
                            pagePreviewLoading = false,
                        )
                }
            }
    }

    fun applyCurrentAppearance(requested: ScanAppearanceSettings) {
        val current = mutableState.value as? ScreenState.Result ?: return
        val cached = current.scan.cached
        if (
            current.outputSaveInProgress ||
                current.appearanceApplyInProgress ||
                current.pagePreviewLoading ||
                appearanceApplyJob?.isActive == true ||
                cached.sourcePages.size != cached.pages.size ||
                cached.appearance == null ||
                cached.entryId == null ||
                !current.scan.outputMetadataValid
        ) {
            return
        }
        val previousSettings = currentSettings()
        val normalized =
            parseScanAppearanceSettings(
                colorModeWireValue = requested.colorMode.wireValue,
                colorIntensity = requested.colorIntensity,
                grayscaleIntensity = requested.grayscaleIntensity,
                blackWhiteIntensity = requested.blackWhiteIntensity,
                shadows = requested.shadows,
            )
        if (
            cached.appearanceSettings == normalized &&
                cached.pdfSizeTarget == previousSettings.pdfSizeTarget
        ) {
            return
        }
        val generation = beginRouteMutation()
        mutableState.value =
            current.copy(
                appearanceApplyInProgress = true,
                appearanceMessage = null,
            )
        appearanceApplyJob =
            viewModelScope.launch {
                var created: CachedScan? = null
                var checkpointCommitted = false
                var pageIndex = current.selectedPageIndex
                var thumbnail = current.thumbnail
                var buildWarnings: List<UiMessage> = emptyList()
                try {
                    withContext(Dispatchers.IO) {
                        val coroutineContext = currentCoroutineContext()
                        val build =
                            storage.createAppearanceVariant(
                                source = cached,
                                appearanceSettings = normalized,
                                pdfSizeTarget = previousSettings.pdfSizeTarget,
                                isCancelled = { !coroutineContext.isActive },
                            )
                        created = build.cached
                        buildWarnings =
                            if (build.pdf.targetMet) {
                                emptyList()
                            } else {
                                listOf(pdfSizeTargetWarning(build.pdf))
                            }
                        pageIndex =
                            resolvedPageIndex(
                                current.selectedPageIndex,
                                build.cached.pages.size,
                            )
                        thumbnail =
                            storage.loadThumbnail(
                                build.cached.pages[pageIndex],
                                RESULT_PREVIEW_SIZE,
                            )
                    }
                    val completed =
                        routeMutationMutex.withLock {
                            val latest = mutableState.value as? ScreenState.Result
                            if (
                                !routeMutationGate.isCurrent(generation) ||
                                    latest?.scan?.cached?.baseName != cached.baseName ||
                                    latest.scan.cached.entryId != cached.entryId
                            ) {
                                return@withLock false
                            }
                            val candidate = checkNotNull(created)
                            val commitResult =
                                withContext(NonCancellable + Dispatchers.IO) {
                                    commitAppliedAppearance(
                                        normalized = normalized,
                                        pdfSizeTarget = previousSettings.pdfSizeTarget,
                                        targetCacheId = candidate.baseName,
                                    )
                                }
                            if (commitResult != AppearanceCommitResult.Applied) {
                                return@withLock false
                            }
                            checkpointCommitted = true
                            mutableSettings.value =
                                mutableSettings.value.copy(
                                    appearance = normalized,
                                    pdfSizeTarget = previousSettings.pdfSizeTarget,
                                )
                            val saved =
                                withContext(NonCancellable + Dispatchers.IO) {
                                    completeAppearanceCandidate(candidate, buildWarnings)
                                }
                            if (routeMutationGate.isCurrent(generation)) {
                                publishResult(
                                    ScreenState.Result(
                                        scan = saved,
                                        thumbnail = thumbnail,
                                        selectedPageIndex = pageIndex,
                                    ),
                                )
                            }
                            true
                        }
                    if (!completed && !checkpointCommitted) {
                        created?.let { discardAppearanceVariantUnlessActive(it) }
                        if (routeMutationGate.isCurrent(generation)) {
                            val latest = mutableState.value as? ScreenState.Result
                            if (
                                latest?.scan?.cached?.baseName == cached.baseName &&
                                    latest.scan.cached.entryId == cached.entryId
                            ) {
                                mutableState.value =
                                    latest.copy(
                                        appearanceApplyInProgress = false,
                                        appearanceMessage =
                                            UiMessage(R.string.appearance_apply_failed),
                                    )
                            }
                        }
                    }
                } catch (cancellation: CancellationException) {
                    if (!checkpointCommitted) {
                        created?.let { discardAppearanceVariantUnlessActive(it) }
                    }
                    throw cancellation
                } catch (_: Exception) {
                    if (checkpointCommitted) {
                        val recovered =
                            try {
                                withContext(NonCancellable + Dispatchers.IO) {
                                    created?.let {
                                        completeAppearanceCandidate(
                                            it,
                                            buildWarnings + UiMessage(R.string.state_update_failed),
                                        )
                                    }
                                }
                            } catch (_: Exception) {
                                null
                            }
                        routeMutationMutex.withLock {
                            if (routeMutationGate.isCurrent(generation) && recovered != null) {
                                publishResult(
                                    ScreenState.Result(
                                        scan = recovered,
                                        thumbnail = thumbnail,
                                        selectedPageIndex = pageIndex,
                                    ),
                                )
                            } else if (routeMutationGate.isCurrent(generation)) {
                                navigationInitialized = true
                                persistRoute(ROUTE_FAILURE)
                                mutableState.value =
                                    ScreenState.Failure(UiMessage(R.string.state_update_failed))
                            }
                        }
                    } else {
                        created?.let { discardAppearanceVariantUnlessActive(it) }
                        if (routeMutationGate.isCurrent(generation)) {
                            mutableState.value =
                                current.copy(
                                    appearanceApplyInProgress = false,
                                    appearanceMessage =
                                        UiMessage(R.string.appearance_apply_failed),
                                )
                        }
                    }
                }
            }
    }


    fun saveCurrentOutputs(target: SaveNowTarget) {
        val current = mutableState.value as? ScreenState.Result ?: return
        if (current.appearanceApplyInProgress) return
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

    suspend fun loadThumbnail(page: File): Bitmap? =
        withContext(Dispatchers.IO) {
            storage.loadThumbnail(page, PAGE_THUMBNAIL_SIZE)
        }

    private fun restoreNavigation() {
        val generation = beginRouteMutation()
        recentJob =
            viewModelScope.launch {
                val checkpoint = readActiveResultCheckpoint(generation)
                if (checkpoint.mutation != CheckpointMutationResult.Applied) return@launch
                val activeCheckpoint = checkpoint.checkpoint
                val authoritativeWasProvisional = checkpoint.authoritativeWasProvisional
                val destination =
                    initialNavigation(savedRoute, savedCacheId, activeCheckpoint?.cacheId)
                when (destination.route) {
                    RestoredRoute.Result -> {
                        val cacheId = checkNotNull(destination.cacheId)
                        val result = loadCachedResult(cacheId)
                        if (result == null) {
                            if (
                                keepCheckpointAfterRestoreFailure(
                                    authoritativeWasProvisional,
                                )
                            ) {
                                routeMutationMutex.withLock {
                                    if (routeMutationGate.isCurrent(generation)) {
                                        navigationInitialized = true
                                        persistRoute(ROUTE_FAILURE)
                                        mutableState.value =
                                            ScreenState.Failure(
                                                UiMessage(R.string.state_update_failed),
                                            )
                                    }
                                }
                                return@launch
                            }
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
        val savedResultCacheId = savedStateHandle.get<String>(ROUTE_CACHE_ID_KEY)
        val savedPageIndex = savedStateHandle.get<Int>(RESULT_PAGE_INDEX_KEY)
        return try {
            withContext(Dispatchers.IO) {
                var saved = storage.openSavedScan(cacheId) ?: return@withContext null
                if (storage.isProvisionalCacheEntry(saved.cached)) {
                    saved =
                        completeAppearanceCandidate(
                            candidate = saved.cached,
                            initialWarnings = saved.warnings,
                        )
                }
                val pageIndex =
                    restoredResultPageIndex(
                        savedCacheId = savedResultCacheId,
                        targetCacheId = cacheId,
                        savedPageIndex = savedPageIndex,
                        pageCount = saved.cached.pages.size,
                    )
                val thumbnail =
                    storage.loadThumbnail(
                        saved.cached.pages[pageIndex],
                        RESULT_PREVIEW_SIZE,
                    )
                ScreenState.Result(
                    scan = saved,
                    thumbnail = thumbnail,
                    selectedPageIndex = pageIndex,
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
                val owner = activeResultOwner ?: return@withLock ResultActivation.Stale
                val saved =
                    withContext(Dispatchers.IO) {
                        settingsStore.saveActiveResult(cacheId, owner)
                    }
                if (saved != AuthorityMutationResult.Applied) {
                    return@withLock ResultActivation.Stale
                }
                activeResultOwner = owner.withCheckpoint(ActiveResultCheckpoint(cacheId))
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
                val owner = activeResultOwner ?: return ResultActivation.Stale
                val result =
                    withContext(Dispatchers.IO) {
                        settingsStore.clearActiveResult(owner)
                    }
                if (result == AuthorityMutationResult.Applied) {
                    activeResultOwner = owner.withCheckpoint(null)
                }
                result == AuthorityMutationResult.Applied
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
        savedStateHandle[RESULT_PAGE_INDEX_KEY] = result.selectedPageIndex
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
        appearanceApplyJob?.cancel()
        resultSaveGate.invalidate()
        outputSaveJob?.cancel()
        recentActionGate.invalidate()
        recentDeletionGate.invalidateCurrent()
        recentJob?.cancel()
        resultPreviewJob?.cancel()
        return routeMutationGate.begin()
    }

    private suspend fun discardAppearanceVariantUnlessActive(variant: CachedScan) {
        withContext(NonCancellable + Dispatchers.IO) {
            val isActive =
                try {
                    settingsStore.activeResultCacheId() == variant.baseName
                } catch (_: IOException) {
                    true
                } catch (_: RuntimeException) {
                    true
                }
            if (!isActive) {
                try {
                    storage.deleteProvisionalCacheEntry(variant)
                } catch (_: IOException) {
                    // Keep an ambiguous candidate for startup reconciliation.
                } catch (_: SecurityException) {
                    // Keep an ambiguous candidate for startup reconciliation.
                } catch (_: IllegalArgumentException) {
                    // Keep an ambiguous candidate for startup reconciliation.
                }
            }
        }
    }

    private fun commitAppliedAppearance(
        normalized: ScanAppearanceSettings,
        pdfSizeTarget: PdfSizeTarget,
        targetCacheId: String,
    ): AppearanceCommitResult {
        val owner = activeResultOwner ?: return AppearanceCommitResult.Stale
        val result =
            settingsStore.saveAppliedAppearanceAndActiveResult(
                normalized,
                pdfSizeTarget,
                targetCacheId,
                owner,
            )
        if (result == AppearanceCommitResult.Applied) {
            activeResultOwner = owner.withCheckpoint(ActiveResultCheckpoint(targetCacheId))
        }
        return result
    }

    private suspend fun saveCurrentOutputs(
        scan: SavedScan,
        target: SaveNowTarget,
        settings: AppSettings = currentSettings(),
    ): OutputSaveResult {
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
                val saved = savePdfToCanonicalDestination(scan.cached, settings.albumName)
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

    private fun savePdfToCanonicalDestination(
        cached: CachedScan,
        albumName: String,
    ): SavedPdfOutput =
        withStorageTransaction {
            storage.savePdf(cached, albumName, canonicalPdfTreeUri(settingsStore))
        }

    private suspend fun completeAppearanceCandidate(
        candidate: CachedScan,
        initialWarnings: List<UiMessage>,
    ): SavedScan {
        val owner = activeResultOwner
            ?: throw IOException("Active result authority is unavailable")
        val activated =
            withActiveResultAuthority {
                if (!settingsStore.ownsActiveResult(owner)) {
                    throw IOException("Active result checkpoint ownership changed")
                }
                if (storage.isProvisionalCacheEntry(candidate)) {
                    val appearance = candidate.appearanceSettings
                        ?: throw IOException("Appearance authority metadata is unavailable")
                    if (
                        settingsStore.restoreAppearanceAuthority(
                            appearance,
                            candidate.pdfSizeTarget,
                            owner,
                        ) != AuthorityMutationResult.Applied
                    ) {
                        throw IOException("Appearance authority could not be repaired")
                    }
                    storage.activateCheckpointProvisional(candidate)
                } else {
                    candidate
                }
            }
        val saved =
            storage.openSavedScan(activated.baseName)
                ?: throw IOException("Cached scan output metadata is unavailable")
        return saved.copy(warnings = (initialWarnings + saved.warnings).distinct())
    }

    private suspend fun readActiveResultCheckpoint(generation: Long): CheckpointReadResult =
        routeMutationMutex.withLock {
            if (!routeMutationGate.isCurrent(generation)) {
                return@withLock CheckpointReadResult(CheckpointMutationResult.Stale)
            }
            val restored =
                try {
                    withContext(Dispatchers.IO) {
                        settingsStore.withAuthoritySnapshot { snapshot ->
                            val checkpoint = snapshot.checkpoint
                            val authoritative =
                                checkpoint?.cacheId?.let(storage::openCachedScan)
                            val provisional =
                                authoritative?.let(storage::isProvisionalCacheEntry) == true
                            if (
                                provisional &&
                                    (authoritative.appearanceSettings == null ||
                                        authoritative.parentCacheId == null ||
                                        authoritative.parentEntryId == null)
                            ) {
                                throw IOException(
                                    "Provisional checkpoint metadata is not authoritative",
                                )
                            }
                            val effectiveSettings =
                                checkpointRestoreSettings(
                                    snapshot.settings,
                                    authoritative,
                                    provisional,
                                )
                            if (provisional) {
                                    if (
                                        settingsStore.restoreAppearanceAuthority(
                                            effectiveSettings.appearance,
                                            effectiveSettings.pdfSizeTarget,
                                            snapshot.owner,
                                        ) != AuthorityMutationResult.Applied
                                    ) {
                                        throw IOException("Appearance authority changed")
                                    }
                            }
                            storage.reconcileProvisionalCacheEntries(authoritative)
                            ActiveResultAuthoritySnapshot(
                                effectiveSettings,
                                checkpoint,
                                snapshot.owner,
                            ) to
                                provisional
                        }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
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
                val (snapshot, provisional) = restored
                activeResultOwner = snapshot.owner
                activeResultAuthorityKnown = true
                mutableSettings.value = snapshot.settings
                CheckpointReadResult(
                    mutation = CheckpointMutationResult.Applied,
                    checkpoint = snapshot.checkpoint,
                    authoritativeWasProvisional = provisional,
                )
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
            val owner = activeResultOwner
                ?: return@withLock CheckpointMutationResult.Stale
            try {
                val cleanup =
                    withContext(NonCancellable + Dispatchers.IO) {
                        clearActiveResultAndReconcileProvisionals(
                            clearCheckpoint = {
                                val result = settingsStore.clearActiveResult(owner)
                                if (result == AuthorityMutationResult.Applied) {
                                    activeResultOwner = owner.withCheckpoint(null)
                                }
                                result
                            },
                            reconcileProvisionals = {
                                storage.reconcileProvisionalCacheEntries(null)
                            },
                        )
                    }
                if (cleanup != AuthorityMutationResult.Applied) {
                    return@withLock CheckpointMutationResult.Stale
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: ActiveResultCleanupException) {
                if (!routeMutationGate.isCurrent(generation)) {
                    return@withLock CheckpointMutationResult.Stale
                }
                navigationInitialized = true
                persistRoute(ROUTE_FAILURE)
                mutableState.value = ScreenState.Failure(UiMessage(R.string.state_update_failed))
                return@withLock CheckpointMutationResult.Failed
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
        val current = canonicalPdfTreeUri(settingsStore)
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
                current = current,
                live = live,
            )
        ) {
            return@withStorageTransaction UiMessage(R.string.pdf_tree_release_warning)
        }
        if (settingsStore.pendingPdfTreeUri() == null) return@withStorageTransaction null
        try {
            settingsStore.savePdfTreeUris(
                current = current,
                pending = null,
            )
            mutableSettings.value = mutableSettings.value.copy(pdfTreeUri = current)
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
            if (cached == null) return@withContext true
            val target =
                when {
                    savedPdf != null && galleryPages.isNotEmpty() -> RecentDeleteTarget.Both
                    savedPdf != null -> RecentDeleteTarget.Pdf
                    galleryPages.isNotEmpty() -> RecentDeleteTarget.Images
                    else -> null
                }
            if (target == null) return@withContext storage.deleteCachedScan(cached)
            storage.deleteDurableOutputs(
                OutputDeleteRequest(cached.baseName, cached.entryId, target),
                deleteRecentCache = true,
            ) == OutputDeleteOperationResult.Completed
        }
}
