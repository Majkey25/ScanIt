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
private const val OUTPUT_CHANGE_GENERATION_KEY = "output_change_generation"
private const val OUTPUT_TREE_ACTIVE_KEY = "output_tree_active"
private const val OUTPUT_TREE_PENDING_KEY = "output_tree_pending"
private const val OUTPUT_TREE_SELECTION_REQUEST_KEY = "output_tree_selection_request"
private const val OUTPUT_TREE_SELECTION_URI_KEY = "output_tree_selection_uri"
private const val OUTPUT_TREE_SELECTION_FLAGS_KEY = "output_tree_selection_flags"
private const val RESULT_PREVIEW_SIZE = 1024
private const val PAGE_THUMBNAIL_SIZE = 256
internal const val PDF_TREE_FLAGS =
    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

internal enum class OutputTreeCallbackDisposition {
    Accepted,
    DefiniteStale,
}

internal class OutputTreeSavedState(private val handle: SavedStateHandle) {
    fun activeRequest(): OutputChangeRequest? {
        val active = decodeOutputTreePickerRequest(handle[OUTPUT_TREE_ACTIVE_KEY]) ?: return null
        val pendingValue = handle.get<String>(OUTPUT_TREE_PENDING_KEY)
        val selectionPresent =
            handle.get<String>(OUTPUT_TREE_SELECTION_REQUEST_KEY) != null ||
                handle.get<String>(OUTPUT_TREE_SELECTION_URI_KEY) != null ||
                handle.get<Int>(OUTPUT_TREE_SELECTION_FLAGS_KEY) != null
        if (pendingValue != null) {
            if (selectionPresent || decodeOutputTreePickerRequest(pendingValue) != active) return null
        } else if (selectionPresent && decodedSelection(active) == null) {
            return null
        }
        return active
    }

    fun pendingLaunch(): OutputChangeRequest? {
        val active = activeRequest() ?: return null
        return decodeOutputTreePickerRequest(handle[OUTPUT_TREE_PENDING_KEY])
            ?.takeIf { it == active }
    }

    fun pendingSelection(): OutputTreeSelection? {
        val active = activeRequest() ?: return null
        if (pendingLaunch() != null) return null
        return decodedSelection(active)
    }

    fun saveLaunch(request: OutputChangeRequest): Boolean {
        if (activeRequest() != null) return false
        if (
            request.kind != OutputChangeKind.PdfLocation &&
            request.kind !is OutputChangeKind.ImageLocation
        ) {
            return false
        }
        val encoded = encodeOutputTreePickerRequest(request)
        handle[OUTPUT_TREE_ACTIVE_KEY] = encoded
        handle[OUTPUT_TREE_PENDING_KEY] = encoded
        clearSelection()
        return true
    }

    fun claimLaunch(request: OutputChangeRequest): Boolean {
        if (activeRequest() != request || pendingLaunch() != request) return false
        handle[OUTPUT_TREE_PENDING_KEY] = null
        return true
    }

    fun saveSelection(selection: OutputTreeSelection): Boolean {
        if (activeRequest() != selection.request || pendingLaunch() != null) return false
        handle[OUTPUT_TREE_SELECTION_REQUEST_KEY] =
            encodeOutputTreePickerRequest(selection.request)
        handle[OUTPUT_TREE_SELECTION_URI_KEY] = selection.uri
        handle[OUTPUT_TREE_SELECTION_FLAGS_KEY] = selection.grantFlags
        return true
    }

    fun consumeSelection(request: OutputChangeRequest): OutputTreeSelection? {
        val selection = pendingSelection()?.takeIf { it.request == request } ?: return null
        clear(request)
        return selection
    }

    fun clear(request: OutputChangeRequest): Boolean {
        if (activeRequest() != request) return false
        clearAll()
        return true
    }

    fun clearAll() {
        handle[OUTPUT_TREE_ACTIVE_KEY] = null
        handle[OUTPUT_TREE_PENDING_KEY] = null
        clearSelection()
    }

    private fun clearSelection() {
        handle[OUTPUT_TREE_SELECTION_REQUEST_KEY] = null
        handle[OUTPUT_TREE_SELECTION_URI_KEY] = null
        handle[OUTPUT_TREE_SELECTION_FLAGS_KEY] = null
    }

    private fun decodedSelection(active: OutputChangeRequest): OutputTreeSelection? {
        val request = decodeOutputTreePickerRequest(
            handle[OUTPUT_TREE_SELECTION_REQUEST_KEY],
        ) ?: return null
        val uri = handle.get<String>(OUTPUT_TREE_SELECTION_URI_KEY) ?: return null
        val flags = handle.get<Int>(OUTPUT_TREE_SELECTION_FLAGS_KEY) ?: return null
        return try {
            OutputTreeSelection(request, uri, flags).takeIf { request == active }
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

internal suspend fun <T> runOutputTreeAttempt(
    validate: () -> Unit,
    acquireGrant: () -> Unit,
    replace: suspend () -> T,
    reconcile: () -> Unit,
): T =
    withContext(Dispatchers.IO) {
        var operationFailure: Throwable? = null
        try {
            currentCoroutineContext().ensureActive()
            validate()
            currentCoroutineContext().ensureActive()
            acquireGrant()
            currentCoroutineContext().ensureActive()
            replace()
        } catch (failure: Throwable) {
            operationFailure = failure
            throw failure
        } finally {
            try {
                withContext(NonCancellable + Dispatchers.IO) { reconcile() }
            } catch (reconciliationFailure: Throwable) {
                if (operationFailure == null) throw reconciliationFailure
                if (operationFailure !== reconciliationFailure) {
                    operationFailure.addSuppressed(reconciliationFailure)
                }
            }
        }
    }

internal fun pdfSizeTargetWarning(result: ScanPdfBuildResult): UiMessage {
    require(!result.targetMet && result.bytes > 0L) { "PDF warning requires an unmet target" }
    return requireNotNull(pdfSizeTargetWarning(result.target, result.bytes)) {
        "PDF warning requires an exceeded size target"
    }
}

internal fun settingsSaveAllowed(state: ScreenState): Boolean =
    (state as? ScreenState.Result)?.resultActionsBlocked != true

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

    fun claim(action: RecentAction, identities: Iterable<Pair<String, String?>>): Boolean {
        if (!isCurrent(action, identities)) return false
        invalidate()
        return true
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
    if (!provisional || authoritative?.restoreAppearanceSettings == false) return current
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

private data class PreparedInitialScan(
    val settings: AppSettings,
    val build: CachedScanBuild,
    val thumbnail: Bitmap?,
)

internal fun automaticOutputTarget(settings: AppSettings): SaveNowTarget? =
    when {
        settings.savePdf && settings.saveImages -> SaveNowTarget.Both
        settings.savePdf -> SaveNowTarget.Pdf
        settings.saveImages -> SaveNowTarget.Images
        else -> null
    }

internal fun automaticPdfUsesDownloads(pdfTreeUri: String?): Boolean = pdfTreeUri == null

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
    private val documentActionProcessor = DocumentActionProcessor(application)
    private val markTemplateStore = MarkTemplateStore(application)
    private val scannerLaunchGate =
        ScannerLaunchGate(
            ScannerLaunchStage.entries.firstOrNull {
                it.name == savedStateHandle.get<String>(SCANNER_STAGE_KEY)
            } ?: ScannerLaunchStage.Idle,
        )
    private val recentActionGate = RecentActionGate()
    private val recentDeletionGate = RecentDeletionGate()
    private val resultSaveGate = ResultSaveGate()
    private val outputTreeSavedState = OutputTreeSavedState(savedStateHandle)
    private val restoredOutputTreeRequest = outputTreeSavedState.activeRequest()
    private val restoredOutputChangeGeneration =
        maxOf(
            savedStateHandle.get<Long>(OUTPUT_CHANGE_GENERATION_KEY) ?: 0L,
            restoredOutputTreeRequest?.generation ?: 0L,
        )
    private val outputChangeGate =
        OutputChangeGate(restoredOutputChangeGeneration, restoredOutputTreeRequest)
    private val outputTreePickerGate =
        OutputTreePickerGate(outputTreeSavedState.pendingLaunch())
    private val resultImageShareGate = RecentActionGate()
    private val routeMutationGate = RouteMutationGate()
    private val shareRefreshGate = DirtyRefreshGate()
    private val routeMutationMutex = Mutex()
    private val mutableState = MutableStateFlow(initialScreenState(null))
    private val mutableScannerRequest = MutableStateFlow<Long?>(null)
    private val mutableOutputTreePickerRequest = MutableStateFlow<OutputChangeRequest?>(null)
    private val mutableSettings = MutableStateFlow(settingsStore.load())
    private var processingJob: Job? = null
    private var recentJob: Job? = null
    private var cacheRefreshJob: Job? = null
    private var outputSaveJob: Job? = null
    private var outputChangeJob: Job? = null
    private var appearanceApplyJob: Job? = null
    private var documentActionJob: Job? = null
    private var documentActionGeneration = 0L
    private var activeDocumentActionRequest: DocumentActionRequest? = null
    private var documentTextExportWriteStarted = false
    private var visualMarkTemplateJob: Job? = null
    private var visualMarkApplyJob: Job? = null
    private var visualMarkScanSource: MarkEditorSource? = null
    private var resultPreviewJob: Job? = null
    private var recentScans: List<RecentScan> = emptyList()
    private var navigationInitialized = false
    private var activeResultAuthorityKnown = false
    private var activeResultOwner: ActiveResultOwner? = null

    val state: StateFlow<ScreenState> = mutableState.asStateFlow()
    val scannerRequest: StateFlow<Long?> = mutableScannerRequest.asStateFlow()
    val outputTreePickerRequest: StateFlow<OutputChangeRequest?> =
        mutableOutputTreePickerRequest.asStateFlow()
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

    fun requestPdfLocationChange() {
        beginOutputTreePicker(OutputChangeKind.PdfLocation)
    }

    fun requestImageLocationChange() {
        beginOutputTreePicker(OutputChangeKind.ImageLocation)
    }

    fun claimOutputTreePicker(request: OutputChangeRequest): Boolean {
        val current = mutableState.value as? ScreenState.Result ?: return false
        val entryId = current.scan.cached.entryId ?: return false
        if (!outputChangeGate.isCurrent(request, current.scan.cached.baseName, entryId) ||
            outputTreePickerGate.pending != request ||
            !outputTreeSavedState.claimLaunch(request) ||
            !outputTreePickerGate.claim(request)
        ) {
            return false
        }
        mutableOutputTreePickerRequest.value = null
        return true
    }

    fun outputTreePickerCancelled(
        request: OutputChangeRequest,
    ): OutputTreeCallbackDisposition {
        if (outputChangeGate.active != request) {
            return OutputTreeCallbackDisposition.DefiniteStale
        }
        finishOutputChange(request)
        return OutputTreeCallbackDisposition.Accepted
    }

    fun outputTreePickerSelected(
        request: OutputChangeRequest,
        treeUri: Uri,
        grantFlags: Int,
    ): OutputTreeCallbackDisposition {
        if (outputChangeGate.active != request) {
            return OutputTreeCallbackDisposition.DefiniteStale
        }
        val selection =
            try {
                OutputTreeSelection(request, treeUri.toString(), grantFlags)
            } catch (_: IllegalArgumentException) {
                null
            }
        if (selection == null || !outputTreeSavedState.saveSelection(selection)) {
            finishOutputChange(request)
            return OutputTreeCallbackDisposition.Accepted
        }
        mutableOutputTreePickerRequest.value = null
        resumePendingOutputTreeSelection()
        return OutputTreeCallbackDisposition.Accepted
    }

    fun changeCurrentImageSize(
        preset: ImageSizePreset,
        customMaxDimension: Int? = null,
    ) {
        val current = mutableState.value as? ScreenState.Result ?: return
        val options = imageExportOptionsForChange(current.scan) ?: return
        val kind =
            try {
                OutputChangeKind.ImageSize(preset, customMaxDimension)
            } catch (_: IllegalArgumentException) {
                return
            }
        runImageOutputChange(
            current,
            kind,
            options.copy(sizePreset = preset, customMaxDimension = customMaxDimension),
        )
    }

    fun changeCurrentImageFormat(format: ImageExportFormat) {
        val current = mutableState.value as? ScreenState.Result ?: return
        val options = imageExportOptionsForChange(current.scan) ?: return
        runImageOutputChange(
            current,
            OutputChangeKind.ImageFormat(format),
            options.copy(format = format),
        )
    }

    fun acknowledgeUnknownOutputCreate(acknowledgement: UnknownOutputCreateAcknowledgement) {
        val current = mutableState.value as? ScreenState.Result ?: return
        val entryId = current.scan.cached.entryId ?: return
        val request =
            outputChangeGate.begin(
                current.scan.cached.baseName,
                entryId,
                OutputChangeKind.UnknownOutputCreate(acknowledgement.operationId),
            ) ?: return
        if (
            current.resultActionsBlocked ||
            !unknownOutputAcknowledgementMatches(current.scan, acknowledgement, request)
        ) {
            outputChangeGate.complete(request)
            return
        }
        persistOutputGeneration()
        mutableState.value = current.copy(outputChangeInProgress = true)
        outputChangeJob =
            viewModelScope.launch {
                try {
                    val refreshed =
                        withContext(Dispatchers.IO) {
                            val result =
                                storage.acknowledgeUnknownOutputCreate(
                                    current.scan.cached,
                                    acknowledgement,
                                )
                            if (!unknownOutputAcknowledgementRefreshAllowed(result)) {
                                throw IOException("Output acknowledgement was not applied")
                            }
                            storage.openSavedScan(request.cacheId)
                                ?: throw IOException("Acknowledged output metadata is unavailable")
                        }
                    publishOutputChangeResult(request, refreshed, emptyList())
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    publishOutputChangeFailure(request, recoverOutputChangeScan(request))
                } finally {
                    finishOutputChange(request)
                }
            }
    }

    fun beginResultImageShare(): RecentAction? {
        val current = mutableState.value as? ScreenState.Result ?: return null
        val entryId = current.scan.cached.entryId ?: return null
        if (current.resultActionsBlocked || current.scan.cached.pages.isEmpty()) return null
        val action = resultImageShareGate.begin(current.scan.cached.baseName, entryId)
        mutableState.value = current.copy(imageSharePreparationInProgress = true)
        return action
    }

    suspend fun prepareResultImageShare(action: RecentAction): PreparedResultImageShare? =
        (mutableState.value as? ScreenState.Result)?.let { current ->
            if (!isResultImageShareCurrent(action, current)) return@let null
            withContext(Dispatchers.IO) {
                val operationContext = currentCoroutineContext()
                val mode = resultImageShareMode(current.scan)
                val copies =
                    when (mode) {
                        ResultImageShareMode.PrivateCopies ->
                            prepareImageShareCopies(
                                getApplication<Application>(),
                                current.scan,
                                isCancelled = { !operationContext.isActive },
                            )
                        ResultImageShareMode.CachedPages -> null
                        ResultImageShareMode.Unavailable -> return@withContext null
                    }
                PreparedResultImageShare(current.scan, copies)
            }
        }

    fun claimResultImageShare(action: RecentAction): Boolean {
        val current = mutableState.value as? ScreenState.Result ?: return false
        if (
            !resultImageShareGate.claim(
                action,
                listOf(current.scan.cached.baseName to current.scan.cached.entryId),
            )
        ) {
            return false
        }
        mutableState.value = current.copy(imageSharePreparationInProgress = false)
        return true
    }

    fun resultImageShareFailed(action: RecentAction) {
        val current = mutableState.value as? ScreenState.Result ?: return
        if (!isResultImageShareCurrent(action, current)) return
        resultImageShareGate.invalidate()
        mutableState.value = current.copy(imageSharePreparationInProgress = false)
    }

    private fun beginOutputTreePicker(kind: OutputChangeKind) {
        val current = mutableState.value as? ScreenState.Result ?: return
        val entryId = current.scan.cached.entryId ?: return
        if (current.resultActionsBlocked || !current.scan.outputMetadataValid) return
        val request = outputChangeGate.begin(current.scan.cached.baseName, entryId, kind) ?: return
        if (!outputTreePickerGate.offer(request)) {
            outputChangeGate.complete(request)
            return
        }
        if (!outputTreeSavedState.saveLaunch(request)) {
            outputTreePickerGate.clear()
            outputChangeGate.complete(request)
            return
        }
        persistOutputGeneration()
        mutableState.value = current.copy(outputChangeInProgress = true)
        mutableOutputTreePickerRequest.value = request
    }

    private fun runImageOutputChange(
        current: ScreenState.Result,
        kind: OutputChangeKind,
        options: ImageExportOptions,
    ) {
        val entryId = current.scan.cached.entryId ?: return
        if (current.resultActionsBlocked || !current.scan.outputMetadataValid) return
        val request =
            outputChangeGate.begin(current.scan.cached.baseName, entryId, kind) ?: return
        persistOutputGeneration()
        mutableState.value = current.copy(outputChangeInProgress = true)
        startOutputReplacement(request) {
            val operationContext = currentCoroutineContext()
            storage.replaceImageOutputs(
                current.scan.cached,
                options,
                isCancelled = { !operationContext.isActive },
            )
        }
    }

    private fun startOutputReplacement(
        request: OutputChangeRequest,
        operation: suspend () -> OutputReplacementResult,
    ) {
        outputChangeJob =
            viewModelScope.launch {
                try {
                    val result = withContext(Dispatchers.IO) { operation() }
                    publishOutputChangeResult(request, result.scan, result.warnings)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    publishOutputChangeFailure(request, recoverOutputChangeScan(request))
                } finally {
                    finishOutputChange(request)
                }
            }
    }

    private fun resumePendingOutputTreeSelection() {
        val selection = outputTreeSavedState.pendingSelection() ?: return
        val request = selection.request
        val current = mutableState.value as? ScreenState.Result ?: return
        if (!outputChangeGate.isCurrent(
                request,
                current.scan.cached.baseName,
                current.scan.cached.entryId,
            )
        ) {
            invalidateOutputChange()
            return
        }
        val consumed = outputTreeSavedState.consumeSelection(request) ?: return
        outputTreePickerGate.clear()
        startOutputTreeReplacement(current, consumed)
    }

    private fun startOutputTreeReplacement(
        current: ScreenState.Result,
        selection: OutputTreeSelection,
    ) {
        val request = selection.request
        outputChangeJob =
            viewModelScope.launch {
                try {
                    val treeUri = Uri.parse(selection.uri)
                    val result =
                        runOutputTreeAttempt(
                            validate = { requireCanonicalOutputTreeUri(treeUri) },
                            acquireGrant = {
                                getApplication<Application>().contentResolver
                                    .takePersistableUriPermission(
                                        treeUri,
                                        selection.grantFlags,
                                    )
                            },
                            replace = {
                                val operationContext = currentCoroutineContext()
                                when (val kind = request.kind) {
                                    OutputChangeKind.PdfLocation ->
                                        storage.replacePdfOutput(current.scan.cached, selection.uri)
                                    OutputChangeKind.ImageLocation ->
                                        storage.relocateImageOutputs(
                                            current.scan.cached,
                                            selection.uri,
                                            isCancelled = { !operationContext.isActive },
                                        )
                                    else ->
                                        throw IllegalArgumentException(
                                            "Output request is not a tree change",
                                        )
                                }
                            },
                            reconcile = ::reconcilePdfTreeGrantsAfterOutputChange,
                        )
                    publishOutputChangeResult(request, result.scan, result.warnings)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    publishOutputChangeFailure(request, recoverOutputChangeScan(request))
                } finally {
                    finishOutputChange(request)
                }
            }
    }

    private suspend fun recoverOutputChangeScan(request: OutputChangeRequest): SavedScan? =
        try {
            withContext(Dispatchers.IO) { storage.openSavedScan(request.cacheId) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }

    private fun publishOutputChangeResult(
        request: OutputChangeRequest,
        refreshed: SavedScan?,
        warnings: List<UiMessage>,
    ) {
        val current = mutableState.value as? ScreenState.Result ?: return
        if (!outputChangeGate.isCurrent(
                request,
                current.scan.cached.baseName,
                current.scan.cached.entryId,
            )
        ) {
            return
        }
        val exact = matchingOutputChangeScan(refreshed, request) ?: return
        mutableState.value =
            current.copy(
                scan =
                    exact.copy(
                        warnings =
                            (current.scan.warnings + exact.warnings + warnings).distinct(),
                    ),
            )
        refreshRecentCache(request.cacheId)
    }

    private fun publishOutputChangeFailure(
        request: OutputChangeRequest,
        recovered: SavedScan? = null,
    ) {
        val current = mutableState.value as? ScreenState.Result ?: return
        if (!outputChangeGate.isCurrent(
                request,
                current.scan.cached.baseName,
                current.scan.cached.entryId,
            )
        ) {
            return
        }
        val scan = replacementFailurePublication(current.scan, recovered, request) ?: return
        mutableState.value =
            current.copy(
                scan =
                    scan.copy(
                        warnings =
                            (current.scan.warnings +
                                scan.warnings +
                                UiMessage(R.string.state_update_failed))
                                .distinct(),
                    ),
            )
    }

    private fun finishOutputChange(request: OutputChangeRequest) {
        if (outputChangeGate.active != request) return
        outputChangeGate.complete(request)
        outputTreePickerGate.clear()
        outputTreeSavedState.clear(request)
        mutableOutputTreePickerRequest.value = null
        persistOutputGeneration()
        outputChangeJob = null
        val current = mutableState.value as? ScreenState.Result ?: return
        mutableState.value = current.copy(outputChangeInProgress = false)
    }

    private fun persistOutputGeneration() {
        savedStateHandle[OUTPUT_CHANGE_GENERATION_KEY] = outputChangeGate.currentGeneration
    }

    private fun invalidateOutputChange() {
        outputChangeJob?.cancel()
        outputChangeJob = null
        outputChangeGate.invalidate()
        outputTreePickerGate.clear()
        outputTreeSavedState.clearAll()
        mutableOutputTreePickerRequest.value = null
        persistOutputGeneration()
        val current = mutableState.value as? ScreenState.Result ?: return
        if (current.outputChangeInProgress) {
            mutableState.value = current.copy(outputChangeInProgress = false)
        }
    }

    private fun isResultImageShareCurrent(
        action: RecentAction,
        current: ScreenState.Result,
    ): Boolean =
        resultImageShareGate.isCurrent(
            action,
            listOf(current.scan.cached.baseName to current.scan.cached.entryId),
        )

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
                var authorityCommitted = false
                var retainedResult: ScreenState.Result? = null
                try {
                    val prepared =
                        withContext(Dispatchers.IO) {
                            val settings = currentSettings()
                            val processingContext = currentCoroutineContext()
                            val cacheBuild =
                                storage.cacheScan(
                                    pageUris = pages,
                                    appearanceSettings = googleScannerAppearanceSettings(),
                                    pdfSizeTarget = settings.pdfSizeTarget,
                                    isCancelled = { !processingContext.isActive },
                                )
                            val cachedScan = cacheBuild.cached
                            cached = cachedScan
                            currentCoroutineContext().ensureActive()
                            PreparedInitialScan(
                                settings = settings,
                                build = cacheBuild,
                                thumbnail =
                                    storage.loadThumbnail(
                                        cachedScan.pages.first(),
                                        RESULT_PREVIEW_SIZE,
                                    ),
                            )
                        }
                    val baseWarnings =
                        if (prepared.build.pdf.targetMet) {
                            emptyList()
                        } else {
                            listOf(pdfSizeTargetWarning(prepared.build.pdf))
                        }
                    retainedResult =
                        ScreenState.Result(
                            scan =
                                SavedScan(
                                    cached = prepared.build.cached,
                                    galleryPages = emptyList(),
                                    savedPdf = null,
                                    warnings = baseWarnings,
                                    outputMetadataValid = false,
                                ),
                            thumbnail = prepared.thumbnail,
                        )
                    when (
                        persistResultCheckpoint(
                            generation = generation,
                            cacheId = prepared.build.cached.baseName,
                        )
                    ) {
                        ResultActivation.Applied -> authorityCommitted = true
                        ResultActivation.Rejected -> {
                            val cleanupComplete = deleteUncommittedCandidate(prepared.build.cached)
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
                            return@launch
                        }
                        ResultActivation.Stale -> {
                            discardAppearanceVariantUnlessActive(prepared.build.cached)
                            return@launch
                        }
                    }
                    if (!routeMutationGate.isCurrent(generation)) return@launch
                    val result =
                        withContext(Dispatchers.IO) {
                            val activated =
                                storage.activateCheckpointProvisional(prepared.build.cached)
                            val opened =
                                storage.openSavedScan(activated.baseName)
                                    ?: throw IOException(
                                        "Cached scan output metadata is unavailable",
                                    )
                            val saved =
                                saveAutomaticReviewOutputs(
                                    opened.copy(
                                        warnings =
                                            (baseWarnings + opened.warnings).distinct(),
                                    ),
                                    prepared.settings,
                                )
                            ScreenState.Result(
                                scan = saved,
                                thumbnail = prepared.thumbnail,
                            )
                        }
                    retainedResult = result
                    routeMutationMutex.withLock {
                        if (routeMutationGate.isCurrent(generation)) publishResult(result)
                    }
                } catch (exception: CancellationException) {
                    if (!authorityCommitted) {
                        cached?.let { candidate ->
                            deleteUncommittedCandidate(candidate)
                        }
                    }
                    throw exception
                } catch (_: Exception) {
                    if (authorityCommitted) {
                        retainedResult?.let { result ->
                            routeMutationMutex.withLock {
                                if (routeMutationGate.isCurrent(generation)) {
                                    publishResult(
                                        result.copy(
                                            scan =
                                                result.scan.copy(
                                                    warnings =
                                                        (result.scan.warnings +
                                                            UiMessage(R.string.state_update_failed))
                                                            .distinct(),
                                                ),
                                        ),
                                    )
                                }
                            }
                        }
                    } else {
                        val cleanupComplete =
                            cached?.let { candidate ->
                                deleteUncommittedCandidate(candidate)
                            } ?: true
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
        if (current.resultActionsBlocked) return
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

    suspend fun loadAppearancePreview(
        sourcePage: File,
        settings: ScanAppearanceSettings,
        maxSize: Int,
    ): Bitmap? =
        withContext(Dispatchers.IO) {
            val processingContext = currentCoroutineContext()
            storage.loadAppearancePreview(
                sourcePage = sourcePage,
                appearance = settings.selected(),
                maxSize = maxSize,
                isCancelled = { !processingContext.isActive },
            )
        }

    suspend fun loadResultPreview(
        page: File,
        maxSize: Int,
    ): Bitmap? =
        withContext(Dispatchers.IO) {
            val bitmap = storage.loadThumbnail(page, maxSize.coerceIn(320, 2048))
            try {
                currentCoroutineContext().ensureActive()
                bitmap
            } catch (cancellation: CancellationException) {
                bitmap?.recycle()
                throw cancellation
            }
        }

    suspend fun loadResultImageDimensions(pages: List<File>): List<Pair<Int, Int>> =
        withContext(Dispatchers.IO) {
            pages.map { page ->
                currentCoroutineContext().ensureActive()
                val dimensions = readJpegDimensions(page)
                dimensions.width to dimensions.height
            }
        }

    fun openAppearanceEditor() {
        val current = mutableState.value as? ScreenState.Result ?: return
        if (current.resultActionsBlocked || !canEditAppearance(current.scan)) return
        mutableState.value =
            current.copy(
                appearanceReviewRequired = true,
                appearanceMessage = null,
            )
    }

    fun closeAppearanceEditor() {
        val current = mutableState.value as? ScreenState.Result ?: return
        if (!current.appearanceReviewRequired || current.appearanceApplyInProgress) return
        mutableState.value =
            current.copy(
                appearanceReviewRequired = false,
                appearanceMessage = null,
            )
    }

    fun openVisualMarkEditor() {
        val current = mutableState.value as? ScreenState.Result ?: return
        val cached = current.scan.cached
        val entryId = cached.entryId ?: return
        if (
            current.resultActionsBlocked ||
                current.pagePreviewLoading ||
                current.thumbnail == null ||
                cached.sourcePages.size != cached.pages.size ||
                cached.sourcePages.isEmpty() ||
                cached.appearanceSettings == null ||
                !current.scan.outputMetadataValid
        ) {
            return
        }
        val source =
            MarkEditorSource(
                cacheId = cached.baseName,
                entryId = entryId,
                pageIndex = resolvedPageIndex(current.selectedPageIndex, cached.pages.size),
            )
        mutableState.value =
            current.copy(
                visualMarkEditor = VisualMarkEditorState(source = source, busy = true),
            )
        startVisualMarkTemplateJob(
            source = source,
            failureMessage = UiMessage(R.string.visual_mark_load_failed),
        ) { null }
    }

    fun closeVisualMarkEditor() {
        val current = mutableState.value as? ScreenState.Result ?: return
        if (current.visualMarkEditor?.applying == true) return
        visualMarkTemplateJob?.cancel()
        visualMarkScanSource = null
        mutableState.value = current.copy(visualMarkEditor = null)
    }

    fun selectVisualMarkTemplate(id: String) {
        val current = mutableState.value as? ScreenState.Result ?: return
        val editor = current.visualMarkEditor ?: return
        if (editor.busy || id !in editor.templateIds) return
        mutableState.value =
            current.copy(
                visualMarkEditor = editor.copy(selectedTemplateId = id, message = null),
            )
    }

    fun updateVisualMarkPlacement(placement: MarkPlacement) {
        val current = mutableState.value as? ScreenState.Result ?: return
        val editor = current.visualMarkEditor ?: return
        if (editor.busy) return
        mutableState.value =
            current.copy(visualMarkEditor = editor.copy(placement = placement, message = null))
    }

    fun beginVisualMarkDrawing() {
        val current = mutableState.value as? ScreenState.Result ?: return
        val editor = current.visualMarkEditor ?: return
        if (editor.busy) return
        mutableState.value =
            current.copy(
                visualMarkEditor = editor.copy(drawingStrokes = emptyList(), message = null),
            )
    }

    fun updateVisualMarkDrawing(strokes: List<MarkStroke>) {
        val current = mutableState.value as? ScreenState.Result ?: return
        val editor = current.visualMarkEditor ?: return
        if (editor.busy || editor.drawingStrokes == null) return
        if (strokes.isNotEmpty()) validateNormalizedMarkStrokes(strokes)
        mutableState.value =
            current.copy(visualMarkEditor = editor.copy(drawingStrokes = strokes, message = null))
    }

    fun cancelVisualMarkDrawing() {
        val current = mutableState.value as? ScreenState.Result ?: return
        val editor = current.visualMarkEditor ?: return
        if (editor.busy || editor.drawingStrokes == null) return
        mutableState.value = current.copy(visualMarkEditor = editor.copy(drawingStrokes = null))
    }

    fun importVisualMark(uri: Uri) {
        beginVisualMarkTemplateMutation { markTemplateStore.import(uri) }
    }

    fun saveDrawnVisualMark(strokes: List<MarkStroke>) {
        val current = mutableState.value as? ScreenState.Result ?: return
        val editor = current.visualMarkEditor ?: return
        if (editor.busy || editor.drawingStrokes == null) return
        try {
            validateNormalizedMarkStrokes(strokes)
        } catch (_: IllegalArgumentException) {
            mutableState.value =
                current.copy(
                    visualMarkEditor =
                        editor.copy(message = UiMessage(R.string.visual_mark_template_failed)),
                )
            return
        }
        beginVisualMarkTemplateMutation(clearDrawingOnSuccess = true) {
            val bitmap = renderDrawnMark(strokes)
            try {
                markTemplateStore.save(bitmap)
            } finally {
                bitmap.recycle()
            }
        }
    }

    fun deleteVisualMarkTemplate(id: String) {
        val current = mutableState.value as? ScreenState.Result ?: return
        val editor = current.visualMarkEditor ?: return
        if (editor.busy || editor.selectedTemplateId != id || id !in editor.templateIds) return
        beginVisualMarkTemplateMutation {
            if (!markTemplateStore.delete(id)) {
                throw IOException("Mark template is unavailable")
            }
            null
        }
    }

    suspend fun loadVisualMarkTemplate(
        id: String,
        maxSide: Int,
    ): Bitmap? =
        try {
            withContext(Dispatchers.IO) { markTemplateStore.load(id, maxSide) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }

    fun beginVisualMarkScan(): MarkEditorSource? {
        val current = mutableState.value as? ScreenState.Result ?: return null
        val editor = current.visualMarkEditor ?: return null
        if (editor.busy || !isVisualMarkSourceCurrent(editor.source)) return null
        visualMarkScanSource = editor.source
        mutableState.value =
            current.copy(visualMarkEditor = editor.copy(busy = true, message = null))
        return editor.source
    }

    fun currentVisualMarkScanSource(): MarkEditorSource? = visualMarkScanSource

    fun visualMarkScannerCancelled(source: MarkEditorSource) {
        finishVisualMarkScanner(source, null)
    }

    fun visualMarkScannerFailed(source: MarkEditorSource) {
        finishVisualMarkScanner(source, UiMessage(R.string.visual_mark_scanner_failed))
    }

    fun importScannedVisualMark(
        source: MarkEditorSource,
        uri: Uri,
    ) {
        val current = mutableState.value as? ScreenState.Result ?: return
        val editor = current.visualMarkEditor ?: return
        if (
            visualMarkScanSource != source ||
                editor.source != source ||
                !editor.busy ||
                !isVisualMarkSourceCurrent(source)
        ) {
            return
        }
        visualMarkScanSource = null
        startVisualMarkTemplateJob(source) { markTemplateStore.import(uri) }
    }

    fun applyVisualMark() {
        val current = mutableState.value as? ScreenState.Result ?: return
        val editor = current.visualMarkEditor ?: return
        val templateId = editor.selectedTemplateId ?: return
        if (
            editor.busy ||
                templateId !in editor.templateIds ||
                !isVisualMarkSourceCurrent(editor.source) ||
                visualMarkApplyJob?.isActive == true
        ) {
            return
        }
        val source = editor.source
        val generation = beginRouteMutation(keepVisualMarkEditor = true)
        mutableState.value =
            current.copy(
                visualMarkEditor =
                    editor.copy(
                        drawingStrokes = null,
                        busy = true,
                        applying = true,
                        message = null,
                    ),
            )
        visualMarkApplyJob =
            viewModelScope.launch {
                var candidate: CachedScan? = null
                var checkpointCommitted = false
                var thumbnail: Bitmap? = null
                var warnings: List<UiMessage> = emptyList()
                try {
                    val build =
                        withContext(Dispatchers.IO) {
                            val template = markTemplateStore.load(templateId)
                                ?: throw IOException("Mark template is unavailable")
                            try {
                                val coroutineContext = currentCoroutineContext()
                                storage.createMarkedVariant(
                                    source = current.scan.cached,
                                    selectedPageIndex = source.pageIndex,
                                    mark = template,
                                    placement = editor.placement,
                                    isCancelled = { !coroutineContext.isActive },
                                )
                            } finally {
                                template.recycle()
                            }
                        }
                    candidate = build.cached
                    if (!build.pdf.targetMet) {
                        warnings = listOf(pdfSizeTargetWarning(build.pdf))
                    }
                    thumbnail =
                        withContext(Dispatchers.IO) {
                            storage.loadThumbnail(
                                build.cached.pages[source.pageIndex],
                                RESULT_PREVIEW_SIZE,
                            )
                        }
                    when (persistResultCheckpoint(generation, build.cached.baseName)) {
                        ResultActivation.Applied -> checkpointCommitted = true
                        ResultActivation.Rejected,
                        ResultActivation.Stale,
                        -> return@launch restoreVisualMarkApplyFailure(source, generation, candidate)
                    }
                    val saved =
                        withContext(NonCancellable + Dispatchers.IO) {
                            completeDerivedCandidate(build.cached, warnings)
                        }
                    routeMutationMutex.withLock {
                        if (routeMutationGate.isCurrent(generation)) {
                            publishResult(
                                ScreenState.Result(
                                    scan = saved,
                                    thumbnail = thumbnail,
                                    selectedPageIndex = source.pageIndex,
                                ),
                            )
                        }
                    }
                } catch (cancellation: CancellationException) {
                    if (!checkpointCommitted) candidate?.let { discardAppearanceVariantUnlessActive(it) }
                    throw cancellation
                } catch (_: Exception) {
                    if (!checkpointCommitted) {
                        candidate?.let { discardAppearanceVariantUnlessActive(it) }
                        restoreVisualMarkApplyFailure(source, generation, null)
                    } else {
                        val recovered =
                            try {
                                withContext(NonCancellable + Dispatchers.IO) {
                                    candidate?.let {
                                        completeDerivedCandidate(
                                            it,
                                            warnings + UiMessage(R.string.state_update_failed),
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
                                        selectedPageIndex = source.pageIndex,
                                    ),
                                )
                            } else if (routeMutationGate.isCurrent(generation)) {
                                persistRoute(ROUTE_FAILURE)
                                mutableState.value =
                                    ScreenState.Failure(UiMessage(R.string.state_update_failed))
                            }
                        }
                    }
                } finally {
                    visualMarkApplyJob = null
                }
            }
    }

    fun applyCurrentAppearance(requested: ScanAppearanceSettings) {
        val current = mutableState.value as? ScreenState.Result ?: return
        val cached = current.scan.cached
        if (
            !current.appearanceReviewRequired ||
                current.outputSaveInProgress ||
                current.appearanceApplyInProgress ||
                current.visualMarkEditor != null ||
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
                naturalIntensity = requested.naturalIntensity,
                colorIntensity = requested.colorIntensity,
                lightTextIntensity = requested.lightTextIntensity,
                grayscaleIntensity = requested.grayscaleIntensity,
                blackWhiteIntensity = requested.blackWhiteIntensity,
                whiteboardIntensity = requested.whiteboardIntensity,
                shadows = requested.shadows,
            )
        if (
            cached.appearanceSettings == normalized &&
                cached.pdfSizeTarget == previousSettings.pdfSizeTarget
        ) {
            finishUnchangedAppearanceReview(current, previousSettings)
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
                            val activated =
                                withContext(NonCancellable + Dispatchers.IO) {
                                    completeAppearanceCandidate(candidate, buildWarnings)
                                }
                            val saved =
                                withContext(NonCancellable + Dispatchers.IO) {
                                    saveAutomaticReviewOutputs(
                                        activated,
                                        previousSettings.copy(appearance = normalized),
                                    )
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
                                    created?.let { candidate ->
                                        val activated =
                                            completeAppearanceCandidate(
                                                candidate,
                                                buildWarnings +
                                                    UiMessage(R.string.state_update_failed),
                                            )
                                        saveAutomaticReviewOutputs(
                                            activated,
                                            previousSettings.copy(appearance = normalized),
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

    fun changeCurrentPdfSize(target: PdfSizeTarget) {
        val current = mutableState.value as? ScreenState.Result ?: return
        val entryId = current.scan.cached.entryId ?: return
        if (
            current.resultActionsBlocked ||
                !canChangePdfSize(current.scan, target)
        ) {
            return
        }
        val cached = current.scan.cached
        val request =
            outputChangeGate.begin(
                cached.baseName,
                entryId,
                OutputChangeKind.PdfSize(target),
            ) ?: return
        persistOutputGeneration()
        mutableState.value =
            current.copy(
                outputChangeInProgress = true,
                appearanceMessage = null,
            )
        startOutputReplacement(request) {
            val operationContext = currentCoroutineContext()
            storage.replacePdfSize(
                cached,
                target,
                isCancelled = { !operationContext.isActive },
            )
        }
    }

    fun runDocumentAction(action: DocumentAction) {
        val current = mutableState.value as? ScreenState.Result ?: return
        val entryId = current.scan.cached.entryId ?: return
        if (current.resultActionsBlocked || current.documentActionState != null) return
        val pageIndex = resolvedPageIndex(current.selectedPageIndex, current.scan.cached.pages.size)
        val request =
            DocumentActionRequest(
                cacheId = current.scan.cached.baseName,
                entryId = entryId,
                pageIndex = pageIndex,
                action = action,
                generation = nextDocumentActionGeneration(),
            )
        activeDocumentActionRequest = request
        documentTextExportWriteStarted = false
        mutableState.value =
            current.copy(documentActionState = DocumentActionState.Processing(action))
        documentActionJob =
            viewModelScope.launch {
                val state =
                    try {
                        val output =
                            withContext(Dispatchers.IO) {
                                when (action) {
                                    DocumentAction.ExtractText ->
                                        documentActionProcessor.extractText(current.scan.cached.pages)
                                    DocumentAction.DetectCodes ->
                                        documentActionProcessor.detectCodes(
                                            current.scan.cached.pages[pageIndex],
                                        )
                                }
                            }
                        DocumentActionState.Completed(output)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        DocumentActionState.Failed(UiMessage(R.string.document_action_failed))
                    }
                val latest = mutableState.value as? ScreenState.Result ?: return@launch
                if (
                    isDocumentActionRequestCurrent(request, latest)
                ) {
                    mutableState.value = latest.copy(documentActionState = state)
                }
                documentActionJob = null
            }
    }

    fun beginDocumentTextExport(): DocumentActionRequest? {
        val current = mutableState.value as? ScreenState.Result ?: return null
        val completed = current.documentActionState as? DocumentActionState.Completed ?: return null
        val output = completed.output as? DocumentActionOutput.Text ?: return null
        val request = activeDocumentActionRequest ?: return null
        if (
            output.value.isEmpty() ||
                request.action != DocumentAction.ExtractText ||
                !isDocumentActionRequestCurrent(request, current)
        ) {
            return null
        }
        documentTextExportWriteStarted = false
        mutableState.value = current.copy(documentActionState = DocumentActionState.Exporting(output))
        return request
    }

    fun documentTextExportDestinationCancelled(
        request: DocumentActionRequest,
    ): DocumentTextExportDisposition {
        val current = mutableState.value as? ScreenState.Result
            ?: return DocumentTextExportDisposition.DefiniteStale
        val exporting = current.documentActionState as? DocumentActionState.Exporting
            ?: return DocumentTextExportDisposition.DefiniteStale
        if (
            activeDocumentActionRequest != request ||
                documentTextExportWriteStarted ||
                !isDocumentActionRequestCurrent(request, current)
        ) {
            return DocumentTextExportDisposition.DefiniteStale
        }
        mutableState.value =
            current.copy(documentActionState = DocumentActionState.Completed(exporting.output))
        return DocumentTextExportDisposition.Accepted
    }

    fun exportDocumentText(
        request: DocumentActionRequest,
        destination: Uri,
        returnedFlags: Int,
    ): DocumentTextExportDisposition {
        val current = mutableState.value as? ScreenState.Result
            ?: return DocumentTextExportDisposition.DefiniteStale
        val exporting = current.documentActionState as? DocumentActionState.Exporting
            ?: return DocumentTextExportDisposition.DefiniteStale
        if (
            activeDocumentActionRequest != request ||
                documentTextExportWriteStarted ||
                !isDocumentActionRequestCurrent(request, current)
        ) {
            return DocumentTextExportDisposition.DefiniteStale
        }
        documentTextExportWriteStarted = true
        documentActionJob =
            viewModelScope.launch {
                val saved =
                    try {
                        withContext(Dispatchers.IO) {
                            val resolver = getApplication<Application>().contentResolver
                            val mimeType = resolver.getType(destination)
                            if (
                                !isSafeTextExportDestination(
                                    scheme = destination.scheme,
                                    authority = destination.authority,
                                    path = destination.path,
                                    query = destination.query,
                                    fragment = destination.fragment,
                                    uriLength = destination.toString().length,
                                    mimeType = mimeType,
                                    writeGranted =
                                        returnedFlags and
                                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0,
                                )
                            ) {
                                false
                            } else {
                                val operationContext = currentCoroutineContext()
                                resolver.openOutputStream(destination, "w")?.use { stream ->
                                    writeDocumentTextUtf8(stream, exporting.output.value) {
                                        !operationContext.isActive
                                    }
                                } != null
                            }
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        false
                    }
                val latest = mutableState.value as? ScreenState.Result ?: return@launch
                if (
                    isDocumentActionRequestCurrent(request, latest) &&
                        latest.documentActionState is DocumentActionState.Exporting
                ) {
                    mutableState.value =
                        latest.copy(
                            documentActionState =
                                DocumentActionState.Completed(
                                    output = exporting.output,
                                    textExportStatus =
                                        if (saved) {
                                            DocumentTextExportStatus.Saved
                                        } else {
                                            DocumentTextExportStatus.Failed
                                        },
                                ),
                        )
                }
                documentActionJob = null
            }
        return DocumentTextExportDisposition.Accepted
    }

    fun dismissDocumentAction() {
        documentActionJob?.cancel()
        documentActionJob = null
        activeDocumentActionRequest = null
        documentTextExportWriteStarted = false
        nextDocumentActionGeneration()
        val current = mutableState.value as? ScreenState.Result ?: return
        if (current.documentActionState != null) {
            mutableState.value = current.copy(documentActionState = null)
        }
    }

    private fun nextDocumentActionGeneration(): Long {
        check(documentActionGeneration < Long.MAX_VALUE) {
            "Document action generation exhausted"
        }
        documentActionGeneration += 1L
        return documentActionGeneration
    }

    private fun isDocumentActionRequestCurrent(
        request: DocumentActionRequest,
        current: ScreenState.Result,
    ): Boolean =
        request.matches(
            cacheId = current.scan.cached.baseName,
            entryId = current.scan.cached.entryId,
            pageIndex =
                resolvedPageIndex(
                    current.selectedPageIndex,
                    current.scan.cached.pages.size,
                ),
            action = request.action,
            generation = documentActionGeneration,
        )

    private fun finishUnchangedAppearanceReview(
        current: ScreenState.Result,
        settings: AppSettings,
    ) {
        val cacheId = current.scan.cached.baseName
        val entryId = current.scan.cached.entryId ?: return
        val generation = beginRouteMutation()
        mutableState.value =
            current.copy(
                appearanceApplyInProgress = true,
                appearanceMessage = null,
            )
        appearanceApplyJob =
            viewModelScope.launch {
                try {
                    val saved =
                        withContext(Dispatchers.IO) {
                            saveAutomaticReviewOutputs(current.scan, settings)
                        }
                    if (
                        persistResultCheckpoint(generation, cacheId) !=
                            ResultActivation.Applied
                    ) {
                        throw IOException("Appearance review state could not be cleared")
                    }
                    routeMutationMutex.withLock {
                        val latest = mutableState.value as? ScreenState.Result
                        if (
                            routeMutationGate.isCurrent(generation) &&
                                latest?.scan?.cached?.baseName == cacheId &&
                                latest.scan.cached.entryId == entryId
                        ) {
                            publishResult(
                                latest.copy(
                                    scan = saved,
                                    appearanceApplyInProgress = false,
                                    appearanceReviewRequired = false,
                                    appearanceMessage = null,
                                ),
                            )
                        }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    if (routeMutationGate.isCurrent(generation)) {
                        val latest = mutableState.value as? ScreenState.Result
                        if (
                            latest?.scan?.cached?.baseName == cacheId &&
                                latest.scan.cached.entryId == entryId
                        ) {
                            mutableState.value =
                                latest.copy(
                                    appearanceApplyInProgress = false,
                                    appearanceMessage =
                                        UiMessage(R.string.appearance_apply_failed),
                                )
                        }
                    }
                } finally {
                    appearanceApplyJob = null
                }
            }
    }


    fun saveCurrentOutputs(target: SaveNowTarget) {
        val current = mutableState.value as? ScreenState.Result ?: return
        if (current.resultActionsBlocked) return
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
        val restoreOutputChange =
            restoredOutputTreeRequest != null &&
                savedRoute == ROUTE_RESULT &&
                savedCacheId == restoredOutputTreeRequest.cacheId
        val generation = beginRouteMutation(keepOutputChange = restoreOutputChange)
        recentJob =
            viewModelScope.launch {
                val checkpoint = readActiveResultCheckpoint(generation)
                if (checkpoint.mutation != CheckpointMutationResult.Applied) {
                    if (restoreOutputChange) invalidateOutputChange()
                    return@launch
                }
                val activeCheckpoint = checkpoint.checkpoint
                val authoritativeWasProvisional = checkpoint.authoritativeWasProvisional
                val destination =
                    initialNavigation(savedRoute, savedCacheId, activeCheckpoint?.cacheId)
                when (destination.route) {
                    RestoredRoute.Result -> {
                        val cacheId = checkNotNull(destination.cacheId)
                        val result =
                            loadCachedResult(
                                cacheId,
                                activeCheckpoint?.appearanceReviewEntryId,
                            )
                        if (result == null) {
                            if (restoreOutputChange) invalidateOutputChange()
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

    private suspend fun loadCachedResult(
        cacheId: String,
        appearanceReviewEntryId: String? = null,
    ): ScreenState.Result? {
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
                    appearanceReviewRequired =
                        appearanceReviewEntryId != null &&
                            saved.cached.entryId == appearanceReviewEntryId,
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
    ): ResultActivation {
        val activation = persistResultCheckpoint(generation, cacheId)
        if (activation == ResultActivation.Applied) {
            routeMutationMutex.withLock {
                if (routeMutationGate.isCurrent(generation)) publishResult(result)
            }
        }
        return activation
    }

    private suspend fun persistResultCheckpoint(
        generation: Long,
        cacheId: String,
        appearanceReviewEntryId: String? = null,
    ): ResultActivation =
        routeMutationMutex.withLock {
            if (!routeMutationGate.isCurrent(generation)) return@withLock ResultActivation.Stale
            val owner = activeResultOwner ?: return@withLock ResultActivation.Stale
            try {
                val saved =
                    withContext(Dispatchers.IO) {
                        settingsStore.saveActiveResult(
                            cacheId = cacheId,
                            expectedOwner = owner,
                            appearanceReviewEntryId = appearanceReviewEntryId,
                        )
                    }
                if (saved != AuthorityMutationResult.Applied) {
                    return@withLock ResultActivation.Stale
                }
                activeResultOwner =
                    owner.withCheckpoint(
                        ActiveResultCheckpoint(cacheId, appearanceReviewEntryId),
                    )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: IOException) {
                return@withLock recoverResultCheckpoint(
                    ActiveResultCheckpoint(cacheId, appearanceReviewEntryId),
                    owner,
                )
            } catch (_: IllegalArgumentException) {
                return@withLock recoverResultCheckpoint(
                    ActiveResultCheckpoint(cacheId, appearanceReviewEntryId),
                    owner,
                )
            }
            ResultActivation.Applied
        }

    private suspend fun recoverResultCheckpoint(
        expectedCheckpoint: ActiveResultCheckpoint,
        previousOwner: ActiveResultOwner,
    ): ResultActivation =
        try {
            val snapshot =
                withContext(Dispatchers.IO) {
                    settingsStore.authoritySnapshot()
                }
            activeResultOwner = snapshot.owner
            when (snapshot.checkpoint) {
                expectedCheckpoint -> ResultActivation.Applied
                previousOwner.checkpoint -> ResultActivation.Rejected
                else -> ResultActivation.Stale
            }
        } catch (_: IOException) {
            ResultActivation.Stale
        }

    private fun publishResult(result: ScreenState.Result) {
        val cacheId = result.scan.cached.baseName
        val request = outputChangeGate.active
        val outputChangeRestored =
            request != null &&
                outputChangeGate.isCurrent(
                    request,
                    result.scan.cached.baseName,
                    result.scan.cached.entryId,
                )
        if (request != null && !outputChangeRestored) invalidateOutputChange()
        mutableState.value = result.copy(outputChangeInProgress = outputChangeRestored)
        if (outputChangeRestored) {
            if (outputTreeSavedState.pendingSelection() != null) {
                resumePendingOutputTreeSelection()
            } else {
                mutableOutputTreePickerRequest.value = outputTreePickerGate.pending
            }
        }
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

    private fun beginRouteMutation(
        keepVisualMarkEditor: Boolean = false,
        keepOutputChange: Boolean = false,
    ): Long {
        appearanceApplyJob?.cancel()
        documentActionJob?.cancel()
        documentActionJob = null
        activeDocumentActionRequest = null
        documentTextExportWriteStarted = false
        nextDocumentActionGeneration()
        visualMarkTemplateJob?.cancel()
        if (!keepVisualMarkEditor) {
            visualMarkApplyJob?.cancel()
            visualMarkScanSource = null
            val result = mutableState.value as? ScreenState.Result
            if (result?.visualMarkEditor != null) {
                mutableState.value = result.copy(visualMarkEditor = null)
            }
        }
        resultSaveGate.invalidate()
        outputSaveJob?.cancel()
        if (!keepOutputChange) invalidateOutputChange()
        resultImageShareGate.invalidate()
        recentActionGate.invalidate()
        recentDeletionGate.invalidateCurrent()
        recentJob?.cancel()
        resultPreviewJob?.cancel()
        return routeMutationGate.begin()
    }

    private fun beginVisualMarkTemplateMutation(
        clearDrawingOnSuccess: Boolean = false,
        operation: () -> String?,
    ) {
        val current = mutableState.value as? ScreenState.Result ?: return
        val editor = current.visualMarkEditor ?: return
        if (editor.busy || !isVisualMarkSourceCurrent(editor.source)) return
        mutableState.value =
            current.copy(visualMarkEditor = editor.copy(busy = true, message = null))
        startVisualMarkTemplateJob(
            source = editor.source,
            clearDrawingOnSuccess = clearDrawingOnSuccess,
            operation = operation,
        )
    }

    private fun startVisualMarkTemplateJob(
        source: MarkEditorSource,
        clearDrawingOnSuccess: Boolean = false,
        failureMessage: UiMessage = UiMessage(R.string.visual_mark_template_failed),
        operation: () -> String?,
    ) {
        visualMarkTemplateJob?.cancel()
        visualMarkTemplateJob =
            viewModelScope.launch {
                val update =
                    try {
                        withContext(Dispatchers.IO) {
                            val preferred = operation()
                            preferred to markTemplateStore.list()
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        null
                    }
                val latest = mutableState.value as? ScreenState.Result ?: return@launch
                val editor = latest.visualMarkEditor ?: return@launch
                if (editor.source != source || !isVisualMarkSourceCurrent(source)) return@launch
                if (update == null) {
                    mutableState.value =
                        latest.copy(
                            visualMarkEditor =
                                editor.copy(
                                    busy = false,
                                    applying = false,
                                    message = failureMessage,
                                ),
                        )
                    return@launch
                }
                val (preferred, ids) = update
                val selected =
                    preferred?.takeIf(ids::contains)
                        ?: editor.selectedTemplateId?.takeIf(ids::contains)
                        ?: ids.firstOrNull()
                mutableState.value =
                    latest.copy(
                        visualMarkEditor =
                            editor.copy(
                                templateIds = ids,
                                selectedTemplateId = selected,
                                drawingStrokes =
                                    if (clearDrawingOnSuccess) null else editor.drawingStrokes,
                                busy = false,
                                applying = false,
                                message = null,
                            ),
                    )
            }
    }

    private fun finishVisualMarkScanner(
        source: MarkEditorSource,
        message: UiMessage?,
    ) {
        val current = mutableState.value as? ScreenState.Result ?: return
        val editor = current.visualMarkEditor ?: return
        if (visualMarkScanSource != source || editor.source != source) return
        visualMarkScanSource = null
        mutableState.value =
            current.copy(visualMarkEditor = editor.copy(busy = false, message = message))
    }

    private fun isVisualMarkSourceCurrent(source: MarkEditorSource): Boolean {
        val current = mutableState.value as? ScreenState.Result ?: return false
        return source.isCurrent(
            cacheId = current.scan.cached.baseName,
            entryId = current.scan.cached.entryId,
            selectedPageIndex = current.selectedPageIndex,
        ) && current.visualMarkEditor?.source == source
    }

    private suspend fun restoreVisualMarkApplyFailure(
        source: MarkEditorSource,
        generation: Long,
        candidate: CachedScan?,
    ) {
        candidate?.let { discardAppearanceVariantUnlessActive(it) }
        routeMutationMutex.withLock {
            if (!routeMutationGate.isCurrent(generation)) return@withLock
            val current = mutableState.value as? ScreenState.Result ?: return@withLock
            val editor = current.visualMarkEditor ?: return@withLock
            if (editor.source != source || !source.isCurrent(
                    current.scan.cached.baseName,
                    current.scan.cached.entryId,
                    current.selectedPageIndex,
                )
            ) {
                return@withLock
            }
            mutableState.value =
                current.copy(
                    visualMarkEditor =
                        editor.copy(
                            busy = false,
                            applying = false,
                            message = UiMessage(R.string.visual_mark_apply_failed),
                        ),
                )
        }
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

    private suspend fun deleteUncommittedCandidate(candidate: CachedScan): Boolean =
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                storage.deleteProvisionalCacheEntry(candidate)
            } catch (_: IOException) {
                false
            } catch (_: SecurityException) {
                false
            } catch (_: IllegalArgumentException) {
                false
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
                        candidate.restoreAppearanceSettings &&
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
        return saved.copy(
            warnings = (initialWarnings + saved.warnings).distinct(),
        )
    }

    private suspend fun completeDerivedCandidate(
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
                    storage.activateCheckpointProvisional(candidate)
                } else {
                    candidate
                }
            }
        val saved =
            storage.openSavedScan(activated.baseName)
                ?: throw IOException("Cached marked scan metadata is unavailable")
        return saved.copy(warnings = (initialWarnings + saved.warnings).distinct())
    }

    private suspend fun saveAutomaticInitialOutputs(
        cached: CachedScan,
        settings: AppSettings,
    ): List<UiMessage> {
        val warnings = mutableListOf<UiMessage>()
        if (settings.saveImages) {
            try {
                storage.saveImages(cached, settings.albumName)
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
            if (automaticPdfUsesDownloads(settings.pdfTreeUri)) {
                try {
                    storage.savePdf(
                        cached,
                        settings.albumName,
                        pdfTreeUri = null,
                    ).warning?.let(warnings::add)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: PdfSaveFailure) {
                    warnings += pdfSaveFailureMessages(failure)
                } catch (_: Exception) {
                    warnings += UiMessage(R.string.pdf_save_failed)
                }
            } else {
                warnings += UiMessage(R.string.pdf_auto_save_deferred)
            }
        }
        return warnings.distinct()
    }

    private suspend fun saveAutomaticReviewOutputs(
        scan: SavedScan,
        settings: AppSettings,
    ): SavedScan {
        val outputWarnings = saveAutomaticInitialOutputs(scan.cached, settings)
        val refreshed =
            storage.openSavedScan(scan.cached.baseName)
                ?: throw IOException("Reviewed scan output metadata is unavailable")
        return refreshed.copy(
            warnings = (scan.warnings + outputWarnings + refreshed.warnings).distinct(),
        )
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
                                        (authoritative.parentCacheId == null) !=
                                        (authoritative.parentEntryId == null))
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
}
