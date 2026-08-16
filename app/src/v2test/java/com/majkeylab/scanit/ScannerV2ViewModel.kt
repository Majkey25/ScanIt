package com.majkeylab.scanit

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class ScannerV2Issue {
    SessionUnavailable,
    CaptureFailed,
    CaptureRecoveryRequired,
    ImportFailed,
    RenderFailed,
    FinishFailed,
    CameraUnavailable,
}

internal data class ScannerV2UiState(
    val manifest: ScannerV2Manifest? = null,
    val preview: Bitmap? = null,
    val busy: Boolean = true,
    val issue: ScannerV2Issue? = null,
    val cropEditing: Boolean = false,
    val filterPreviews: Map<ScannerV2Filter, Bitmap> = emptyMap(),
    val pageThumbnails: Map<PageId, ScannerV2PageThumbnail> = emptyMap(),
)

internal data class ScannerV2PageThumbnail(
    val sourceFingerprint: OutputFingerprint,
    val renderedFingerprint: OutputFingerprint?,
    val renderFileId: String?,
    val bitmap: Bitmap,
) {
    fun matches(record: ScannerV2PageRecord): Boolean =
        sourceFingerprint == record.sourceFingerprint &&
            renderedFingerprint == record.renderedFingerprint &&
            renderFileId == record.renderFileId
}

internal data class ScannerV2CaptureTicket(
    val sessionId: String,
    val pageId: PageId,
    val generation: Long,
    val destination: File,
)

internal class ScannerV2ViewModel(application: Application) : AndroidViewModel(application) {
    val instanceToken: String = UUID.randomUUID().toString()
    private val store = ScannerV2Store(File(application.filesDir, "scanner-v2-sessions"))
    private val lock = Mutex()
    private val mutableState = MutableStateFlow(ScannerV2UiState())
    private val mutableSurfaceRequest = MutableStateFlow<SurfaceRequest?>(null)
    val state: StateFlow<ScannerV2UiState> = mutableState.asStateFlow()
    val surfaceRequest: StateFlow<SurfaceRequest?> = mutableSurfaceRequest.asStateFlow()

    init {
        viewModelScope.launch { loadSession() }
    }

    fun bindPreview(preview: Preview) {
        preview.setSurfaceProvider { request -> mutableSurfaceRequest.value = request }
    }

    fun clearPreviewSurface() {
        mutableSurfaceRequest.value = null
    }

    fun cameraUnavailable() {
        mutableState.value = mutableState.value.copy(busy = false, issue = ScannerV2Issue.CameraUnavailable)
    }

    fun sessionUnavailable() {
        mutableState.value = mutableState.value.copy(busy = false, issue = ScannerV2Issue.SessionUnavailable)
    }

    suspend fun reserveCapture(): ScannerV2CaptureTicket? = withContext(Dispatchers.IO) {
        lock.withLock {
            val current = mutableState.value.manifest ?: return@withLock null
            if (mutableState.value.busy || current.state.stage != ScannerSessionStage.Capturing) {
                return@withLock null
            }
            val pageId = PageId.parse(UUID.randomUUID().toString())
            val replacement = reserveScannerV2Capture(current, pageId, nextTimestamp(current))
            store.update(current, replacement)
            val destination = store.captureFile(current.sessionId, pageId)
            if (destination.exists()) throw IOException("Scanner capture destination already exists")
            mutableState.value = mutableState.value.copy(manifest = replacement, busy = true, issue = null)
            ScannerV2CaptureTicket(current.sessionId, pageId, current.state.generation, destination)
        }
    }

    fun captureCompleted(ticket: ScannerV2CaptureTicket) {
        viewModelScope.launch {
            try {
                lock.withLock { completeCaptureLocked(ticket) }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableState.value = mutableState.value.copy(busy = false, issue = ScannerV2Issue.CaptureFailed)
            }
        }
    }

    fun importImage(uri: Uri) {
        viewModelScope.launch {
            try {
                lock.withLock {
                    withContext(Dispatchers.IO) {
                        val current = requireNotNull(mutableState.value.manifest)
                        if (mutableState.value.busy || current.state.stage != ScannerSessionStage.Capturing) {
                            return@withContext
                        }
                        val pageId = PageId.parse(UUID.randomUUID().toString())
                        val destination = store.captureFile(current.sessionId, pageId)
                        if (destination.exists()) throw IOException("Scanner import destination already exists")
                        val reserved = reserveScannerV2Capture(current, pageId, nextTimestamp(current))
                        store.update(current, reserved)
                        val ticket = ScannerV2CaptureTicket(
                            current.sessionId,
                            pageId,
                            current.state.generation,
                            destination,
                        )
                        mutableState.value = mutableState.value.copy(
                            manifest = reserved,
                            busy = true,
                            issue = null,
                        )
                        try {
                            val input = getApplication<Application>().contentResolver.openInputStream(uri)
                                ?: throw IOException("Scanner import could not be opened")
                            input.use { copyScannerV2ImportSource(it, destination) }
                            completeCaptureLocked(ticket)
                        } catch (failure: Throwable) {
                            val pending = mutableState.value.manifest
                            if (pending != null && ticket.matches(pending)) {
                                if (!store.deletePendingCaptureFiles(pending)) {
                                    mutableState.value = mutableState.value.copy(
                                        busy = false,
                                        issue = ScannerV2Issue.CaptureRecoveryRequired,
                                    )
                                    return@withContext
                                }
                                restoreAfterCaptureCancellation(pending)
                            }
                            throw failure
                        }
                    }
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableState.value = mutableState.value.copy(
                    busy = false,
                    issue = ScannerV2Issue.ImportFailed,
                )
            }
        }
    }

    fun captureFailed(ticket: ScannerV2CaptureTicket) {
        viewModelScope.launch {
            try {
                lock.withLock {
                    val current = mutableState.value.manifest ?: return@withLock
                    if (!ticket.matches(current)) {
                        if (ticket.destination.exists() && !ticket.destination.delete()) {
                            throw IOException("Stale capture could not be removed")
                        }
                        return@withLock
                    }
                    if (ticket.destination.exists() && !ticket.destination.delete()) {
                        throw IOException("Failed capture could not be removed")
                    }
                    val replacement = cancelScannerV2Capture(
                        current,
                        ticket.generation,
                        nextTimestamp(current),
                    )
                    store.update(current, replacement)
                    mutableState.value = mutableState.value.copy(
                        manifest = replacement,
                        busy = false,
                        issue = ScannerV2Issue.CaptureFailed,
                    )
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableState.value = mutableState.value.copy(busy = false, issue = ScannerV2Issue.CaptureFailed)
            }
        }
    }

    fun discardInterruptedCapture() {
        viewModelScope.launch {
            try {
                lock.withLock {
                    withContext(Dispatchers.IO) {
                        val current = requireNotNull(mutableState.value.manifest)
                        if (current.pendingCaptureId == null) return@withContext
                        if (!store.deletePendingCaptureFiles(current)) {
                            throw IOException("Interrupted capture could not be removed")
                        }
                        restoreAfterCaptureCancellation(current)
                    }
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableState.value = mutableState.value.copy(
                    busy = false,
                    issue = ScannerV2Issue.CaptureRecoveryRequired,
                )
            }
        }
    }

    fun confirmCrop(crop: PageQuad, rotationQuarterTurns: Int) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(busy = true, issue = null)
            try {
                lock.withLock {
                    withContext(Dispatchers.IO) {
                        val current = requireNotNull(mutableState.value.manifest)
                        check(current.state.stage == ScannerSessionStage.Reviewing)
                        val selected = requireNotNull(current.state.selectedIndex)
                        val record = current.pages[selected]
                        val renderFileId = UUID.randomUUID().toString()
                        val destination = store.renderCandidateFile(current.sessionId, record.pageId, renderFileId)
                        val result = renderScannerV2Page(
                            source = store.sourceFile(current.sessionId, record.pageId),
                            destination = destination,
                            crop = crop,
                            rotationQuarterTurns = rotationQuarterTurns,
                            appearance = record.appearance,
                        )
                        val replacement = completeScannerV2PageRender(
                            current = current,
                            pageId = record.pageId,
                            crop = crop,
                            rotationQuarterTurns = rotationQuarterTurns,
                            appearance = record.appearance,
                            renderFileId = renderFileId,
                            renderedFingerprint = result.fingerprint,
                            updatedAtMillis = nextTimestamp(current),
                        )
                        val renderedPreview = decodeScannerV2Preview(
                            destination,
                        )
                        var filterPreviews: Map<ScannerV2Filter, Bitmap> = emptyMap()
                        try {
                            filterPreviews = renderScannerV2FilterPreviews(
                                source = store.sourceFile(current.sessionId, record.pageId),
                                crop = crop,
                                rotationQuarterTurns = rotationQuarterTurns,
                            )
                            store.update(current, replacement)
                            val reconciled = requireNotNull(store.loadActive())
                            check(reconciled == replacement) { "Scanner crop publication changed" }
                        } catch (failure: Throwable) {
                            renderedPreview.recycle()
                            filterPreviews.values.forEach { it.recycle() }
                            throw failure
                        }
                        mutableState.value = mutableState.value.copy(
                            manifest = replacement,
                            preview = renderedPreview,
                            busy = false,
                            issue = null,
                            cropEditing = false,
                            filterPreviews = filterPreviews,
                            pageThumbnails = loadPageThumbnails(replacement),
                        )
                    }
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                recoverAfterRenderFailure()
            }
        }
    }

    fun addPage() {
        viewModelScope.launch {
            try {
                lock.withLock {
                    withContext(Dispatchers.IO) {
                        val current = requireNotNull(mutableState.value.manifest)
                        check(current.pages.all { it.renderedFingerprint != null })
                        val nextState = ScannerSessionGate.beginCapture(current.state)
                        val replacement = ScannerV2Manifest.create(
                            sessionId = current.sessionId,
                            state = nextState,
                            pages = current.pages,
                            retiredPages = current.retiredPages,
                            editSource = current.editSource,
                            updatedAtMillis = nextTimestamp(current),
                        )
                        store.update(current, replacement)
                        mutableState.value = mutableState.value.copy(
                            manifest = replacement,
                            preview = null,
                            busy = false,
                            issue = null,
                            cropEditing = false,
                            filterPreviews = emptyMap(),
                        )
                    }
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableState.value = mutableState.value.copy(busy = false, issue = ScannerV2Issue.SessionUnavailable)
            }
        }
    }

    fun applyAppearance(appearance: ScannerV2Appearance) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(busy = true, issue = null)
            try {
                lock.withLock {
                    withContext(Dispatchers.IO) {
                        val current = requireNotNull(mutableState.value.manifest)
                        val selected = requireNotNull(current.state.selectedIndex)
                        val page = current.pages[selected]
                        check(page.renderedFingerprint != null) { "Scanner page render is not published" }
                        if (page.appearance == appearance) {
                            mutableState.value = mutableState.value.copy(busy = false)
                            return@withContext
                        }
                        val renderFileId = UUID.randomUUID().toString()
                        val destination = store.renderCandidateFile(current.sessionId, page.pageId, renderFileId)
                        val result = renderScannerV2Page(
                            source = store.sourceFile(current.sessionId, page.pageId),
                            destination = destination,
                            crop = page.crop,
                            rotationQuarterTurns = page.rotationQuarterTurns,
                            appearance = appearance,
                        )
                        val preview = decodeScannerV2Preview(destination)
                        try {
                            val completed = completeScannerV2PageRender(
                                current = current,
                                pageId = page.pageId,
                                crop = page.crop,
                                rotationQuarterTurns = page.rotationQuarterTurns,
                                appearance = appearance,
                                renderFileId = renderFileId,
                                renderedFingerprint = result.fingerprint,
                                updatedAtMillis = nextTimestamp(current),
                            )
                            store.update(current, completed)
                            val reconciled = requireNotNull(store.loadActive())
                            check(reconciled == completed) { "Scanner appearance publication changed" }
                            mutableState.value = ScannerV2UiState(
                                manifest = reconciled,
                                preview = preview,
                                busy = false,
                                filterPreviews = mutableState.value.filterPreviews,
                                pageThumbnails = loadPageThumbnails(reconciled),
                            )
                        } catch (failure: Throwable) {
                            preview.recycle()
                            throw failure
                        }
                    }
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                recoverAfterRenderFailure()
            }
        }
    }

    fun editSelectedCrop() {
        viewModelScope.launch {
            try {
                lock.withLock {
                    withContext(Dispatchers.IO) {
                        val current = requireNotNull(mutableState.value.manifest)
                        check(current.state.stage == ScannerSessionStage.Reviewing)
                        val selected = requireNotNull(current.state.selectedIndex)
                        val page = current.pages[selected]
                        check(page.renderedFingerprint != null) { "Scanner page render is not published" }
                        val preview = decodeScannerV2Preview(store.sourceFile(current.sessionId, page.pageId))
                        mutableState.value = ScannerV2UiState(
                            manifest = current,
                            preview = preview,
                            busy = false,
                            cropEditing = true,
                            pageThumbnails = mutableState.value.pageThumbnails,
                        )
                    }
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableState.value = mutableState.value.copy(
                    busy = false,
                    issue = ScannerV2Issue.SessionUnavailable,
                    cropEditing = false,
                )
            }
        }
    }

    fun cancelCropEditing() {
        viewModelScope.launch {
            try {
                lock.withLock {
                    withContext(Dispatchers.IO) {
                        val current = requireNotNull(mutableState.value.manifest)
                        loadSelectedPreviewLocked(current)
                    }
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableState.value = mutableState.value.copy(
                    busy = false,
                    issue = ScannerV2Issue.SessionUnavailable,
                    cropEditing = false,
                )
            }
        }
    }

    fun retakeSelectedPage() {
        viewModelScope.launch {
            try {
                lock.withLock {
                    withContext(Dispatchers.IO) {
                        val current = requireNotNull(mutableState.value.manifest)
                        val replacement = beginScannerV2Retake(current, nextTimestamp(current))
                        store.update(current, replacement)
                        mutableState.value = mutableState.value.copy(
                            manifest = replacement,
                            preview = null,
                            busy = false,
                            issue = null,
                            cropEditing = false,
                            filterPreviews = emptyMap(),
                        )
                    }
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableState.value = mutableState.value.copy(busy = false, issue = ScannerV2Issue.SessionUnavailable)
            }
        }
    }

    fun deleteSelectedPage() {
        viewModelScope.launch {
            try {
                lock.withLock {
                    withContext(Dispatchers.IO) {
                        val current = requireNotNull(mutableState.value.manifest)
                        val selected = requireNotNull(current.state.selectedIndex)
                        val journaled = deleteScannerV2Page(current, selected, nextTimestamp(current))
                        store.update(current, journaled)
                        val replacement = store.reconcileRetiredPages(journaled)
                        if (replacement.retiredPages.isNotEmpty()) {
                            mutableState.value = ScannerV2UiState(
                                replacement,
                                busy = false,
                                issue = ScannerV2Issue.SessionUnavailable,
                            )
                        } else if (replacement.state.stage == ScannerSessionStage.Capturing) {
                            mutableState.value = ScannerV2UiState(replacement, busy = false)
                        } else {
                            mutableState.value = ScannerV2UiState(replacement, busy = true)
                            loadSelectedPreviewLocked(replacement)
                        }
                    }
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableState.value = mutableState.value.copy(busy = false, issue = ScannerV2Issue.SessionUnavailable)
            }
        }
    }

    fun moveSelectedPage(delta: Int) {
        viewModelScope.launch {
            try {
                lock.withLock {
                    withContext(Dispatchers.IO) {
                        val current = requireNotNull(mutableState.value.manifest)
                        val selected = requireNotNull(current.state.selectedIndex)
                        val target = (selected + delta).coerceIn(current.pages.indices)
                        val replacement = reorderScannerV2Pages(
                            current,
                            selected,
                            target,
                            nextTimestamp(current),
                        )
                        if (replacement === current) return@withContext
                        store.update(current, replacement)
                        mutableState.value = mutableState.value.copy(manifest = replacement, issue = null)
                    }
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableState.value = mutableState.value.copy(busy = false, issue = ScannerV2Issue.SessionUnavailable)
            }
        }
    }

    fun selectPage(index: Int) {
        viewModelScope.launch {
            try {
                lock.withLock {
                    withContext(Dispatchers.IO) {
                        val current = requireNotNull(mutableState.value.manifest)
                        val replacement = ScannerV2Manifest.create(
                            sessionId = current.sessionId,
                            state = ScannerSessionGate.select(current.state, index),
                            pages = current.pages,
                            retiredPages = current.retiredPages,
                            editSource = current.editSource,
                            updatedAtMillis = nextTimestamp(current),
                        )
                        store.updateSelection(current, replacement)
                        mutableState.value = mutableState.value.copy(manifest = replacement, busy = true, issue = null)
                        loadSelectedPreviewLocked(replacement)
                    }
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableState.value = mutableState.value.copy(busy = false, issue = ScannerV2Issue.SessionUnavailable)
            }
        }
    }

    fun finish() {
        viewModelScope.launch {
            try {
                lock.withLock {
                    withContext(Dispatchers.IO) {
                        val current = requireNotNull(mutableState.value.manifest)
                        check(current.pages.isNotEmpty() && current.pages.all { it.renderedFingerprint != null })
                        val replacement = ScannerV2Manifest.create(
                            sessionId = current.sessionId,
                            state = ScannerSessionGate.finish(current.state),
                            pages = current.pages,
                            retiredPages = current.retiredPages,
                            editSource = current.editSource,
                            updatedAtMillis = nextTimestamp(current),
                        )
                        store.update(current, replacement)
                        mutableState.value = mutableState.value.copy(manifest = replacement, busy = false)
                    }
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableState.value = mutableState.value.copy(busy = false, issue = ScannerV2Issue.SessionUnavailable)
            }
        }
    }

    suspend fun finishedPageUris(): List<Uri> = withContext(Dispatchers.IO) {
        lock.withLock {
            val current = requireNotNull(mutableState.value.manifest)
            check(current.state.stage == ScannerSessionStage.Finishing)
            check(current.pages.isNotEmpty() && current.pages.all { it.renderedFingerprint != null })
            check(store.loadActive() == current) { "Scanner finish authority changed" }
            current.pages.map { page ->
                FileProvider.getUriForFile(
                    getApplication(),
                    "${getApplication<Application>().packageName}.fileprovider",
                    store.renderedFile(current, page),
                )
            }
        }
    }

    suspend fun recordResultCacheId(cacheId: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val current = requireNotNull(mutableState.value.manifest)
            check(current.state.stage == ScannerSessionStage.Finishing)
            if (current.resultCacheId == cacheId) return@withLock
            check(current.resultCacheId == null) { "Scanner result cache authority changed" }
            val replacement = current.withResultCacheId(cacheId, nextTimestamp(current))
            store.update(current, replacement)
            mutableState.value = mutableState.value.copy(manifest = replacement, issue = null)
        }
    }

    suspend fun resumeFinishedReview(
        expectedCacheId: String?,
        failed: Boolean,
        editSource: ScannerV2EditSource? = null,
    ): Boolean =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val current = mutableState.value.manifest ?: return@withLock false
                if (
                    current.state.stage != ScannerSessionStage.Finishing ||
                        current.resultCacheId != expectedCacheId ||
                        (editSource != null && editSource.cacheId != expectedCacheId)
                ) {
                    return@withLock false
                }
                val replacement = ScannerV2Manifest.create(
                    sessionId = current.sessionId,
                    state = ScannerSessionGate.resumeReview(current.state),
                    pages = current.pages,
                    retiredPages = current.retiredPages,
                    editSource = editSource ?: current.editSource,
                    updatedAtMillis = nextTimestamp(current),
                )
                store.update(current, replacement)
                mutableState.value = ScannerV2UiState(replacement, busy = true)
                loadSelectedPreviewLocked(replacement)
                if (failed) {
                    mutableState.value = mutableState.value.copy(issue = ScannerV2Issue.FinishFailed)
                }
                true
            }
        }

    suspend fun startNewSessionForFreshLaunch(): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            val current = mutableState.value.manifest ?: return@withLock false
            if (!store.deleteForFreshLaunch(current)) {
                throw IOException("Previous scanner session could not be removed")
            }
            val fresh = createSession()
            mutableState.value = ScannerV2UiState(fresh, busy = false)
            true
        }
    }

    fun cancelCamera() {
        viewModelScope.launch {
            try {
                lock.withLock {
                    withContext(Dispatchers.IO) {
                        val current = requireNotNull(mutableState.value.manifest)
                        if (current.state.stage != ScannerSessionStage.Capturing) return@withContext
                        if (current.pendingCaptureId != null && !store.deletePendingCaptureFiles(current)) {
                            mutableState.value = mutableState.value.copy(
                                busy = false,
                                issue = ScannerV2Issue.CaptureRecoveryRequired,
                            )
                            return@withContext
                        }
                        val replacement = cancelScannerV2Capture(
                            current,
                            current.state.generation,
                            nextTimestamp(current),
                        )
                        store.update(current, replacement)
                        if (replacement.state.stage == ScannerSessionStage.Cancelled) {
                            if (!store.deleteCancelled(replacement)) throw IOException("Cancelled session could not be removed")
                            val fresh = createSession()
                            mutableState.value = ScannerV2UiState(fresh, busy = false)
                        } else {
                            mutableState.value = ScannerV2UiState(replacement, busy = true)
                            loadSelectedPreviewLocked(replacement)
                        }
                    }
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableState.value = mutableState.value.copy(busy = false, issue = ScannerV2Issue.SessionUnavailable)
            }
        }
    }

    override fun onCleared() {
        mutableState.value.preview?.takeUnless(Bitmap::isRecycled)?.recycle()
        mutableState.value.filterPreviews.values.forEach { bitmap ->
            bitmap.takeUnless(Bitmap::isRecycled)?.recycle()
        }
        mutableState.value.pageThumbnails.values.forEach { thumbnail ->
            thumbnail.bitmap.takeUnless(Bitmap::isRecycled)?.recycle()
        }
        super.onCleared()
    }

    private suspend fun loadSession() = lock.withLock {
        try {
            withContext(Dispatchers.IO) {
                store.cleanupExpired()
                var manifest = store.loadActive()
                if (manifest != null) manifest = store.reconcileRetiredPages(manifest)
                if (manifest?.retiredPages?.isNotEmpty() == true) {
                    mutableState.value = ScannerV2UiState(
                        manifest = manifest,
                        busy = false,
                        issue = ScannerV2Issue.SessionUnavailable,
                    )
                    return@withContext
                }
                if (manifest?.state?.stage == ScannerSessionStage.Cancelled) {
                    if (!store.deleteCancelled(manifest)) throw IOException("Cancelled session could not be removed")
                    manifest = null
                }
                manifest = manifest ?: createSession()
                mutableState.value = ScannerV2UiState(manifest, busy = true)
                if (manifest.pendingCaptureId != null) {
                    recoverPendingCaptureLocked(manifest)
                } else if (manifest.state.stage == ScannerSessionStage.Reviewing) {
                    loadSelectedPreviewLocked(manifest)
                } else {
                    mutableState.value = mutableState.value.copy(busy = false)
                }
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            mutableState.value = ScannerV2UiState(busy = false, issue = ScannerV2Issue.SessionUnavailable)
        }
    }

    private fun createSession(): ScannerV2Manifest {
        val manifest = ScannerV2Manifest.create(
            sessionId = UUID.randomUUID().toString(),
            state = ScannerSessionGate.start(),
            pages = emptyList(),
            updatedAtMillis = System.currentTimeMillis().coerceAtLeast(1),
        )
        store.create(manifest)
        return manifest
    }

    private suspend fun recoverPendingCaptureLocked(manifest: ScannerV2Manifest) {
        val pageId = requireNotNull(manifest.pendingCaptureId)
        val capture = store.captureFile(manifest.sessionId, pageId)
        val source = store.sourceFile(manifest.sessionId, pageId)
        if (!capture.exists() && !source.exists()) {
            restoreAfterCaptureCancellation(manifest)
            return
        }
        if (capture.exists() && source.exists()) {
            mutableState.value = ScannerV2UiState(
                manifest = manifest,
                busy = false,
                issue = ScannerV2Issue.CaptureRecoveryRequired,
            )
            return
        }
        try {
            completeCaptureLocked(
                ScannerV2CaptureTicket(
                    manifest.sessionId,
                    pageId,
                    manifest.state.generation,
                    capture,
                ),
            )
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            mutableState.value = ScannerV2UiState(
                manifest = manifest,
                busy = false,
                issue = ScannerV2Issue.CaptureRecoveryRequired,
            )
        }
    }

    private fun restoreAfterCaptureCancellation(current: ScannerV2Manifest) {
        val replacement = cancelScannerV2Capture(
            current,
            current.state.generation,
            nextTimestamp(current),
        )
        store.update(current, replacement)
        if (replacement.state.stage == ScannerSessionStage.Cancelled) {
            if (!store.deleteCancelled(replacement)) throw IOException("Cancelled session could not be removed")
            val fresh = createSession()
            mutableState.value = ScannerV2UiState(fresh, busy = false)
        } else {
            mutableState.value = ScannerV2UiState(replacement, busy = true)
            loadSelectedPreviewLocked(replacement)
        }
    }

    private suspend fun completeCaptureLocked(ticket: ScannerV2CaptureTicket) = withContext(Dispatchers.IO) {
        val current = mutableState.value.manifest
        if (current == null || !ticket.matches(current)) {
            if (ticket.destination.exists() && !ticket.destination.delete()) {
                throw IOException("Stale capture could not be removed")
            }
            return@withContext
        }
        val source = store.sourceFile(ticket.sessionId, ticket.pageId)
        when {
            source.exists() && ticket.destination.exists() -> throw IOException("Scanner capture recovery is ambiguous")
            ticket.destination.exists() -> {
                validateScannerV2Source(ticket.destination)
                Files.move(ticket.destination.toPath(), source.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }
            !source.exists() -> throw IOException("Scanner capture is unavailable")
        }
        validateScannerV2Source(source)
        val preview = decodeScannerV2Preview(source)
        try {
            val crop = detectScannerV2Crop(preview) ?: PageQuad.fullFrame()
            val fingerprint = source.inputStream().use { readOutputFingerprint(it, source.length()) }
            val record = ScannerV2PageRecord(
                pageId = ticket.pageId,
                sourceFingerprint = fingerprint,
                crop = crop,
                rotationQuarterTurns = 0,
                appearance = ScannerV2Appearance.original(),
                renderedFingerprint = null,
            )
            val replacement = completeScannerV2Capture(
                current,
                ticket.generation,
                record,
                nextTimestamp(current),
            )
            if (replacement === current) {
                preview.recycle()
                return@withContext
            }
            store.update(current, replacement)
            val cleaned = try {
                store.reconcileRetiredPages(replacement)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableState.value = ScannerV2UiState(
                    replacement,
                    preview = preview,
                    busy = false,
                    issue = ScannerV2Issue.SessionUnavailable,
                )
                return@withContext
            }
            mutableState.value = ScannerV2UiState(
                cleaned,
                preview = preview,
                busy = false,
                issue = if (cleaned.retiredPages.isEmpty()) null else ScannerV2Issue.SessionUnavailable,
                pageThumbnails = loadPageThumbnails(cleaned),
            )
        } catch (failure: Throwable) {
            preview.recycle()
            throw failure
        }
    }

    private fun validateScannerV2Source(file: File) {
        if (!file.isFile || file.length() !in 1..MAX_SCANNER_V2_PAGE_BYTES) {
            throw IOException("Scanner capture is missing or too large")
        }
        val dimensions = readScannerV2SourceDimensions(file)
        if (
            dimensions.width > MAX_IMAGE_EXPORT_DIMENSION ||
                dimensions.height > MAX_IMAGE_EXPORT_DIMENSION ||
                dimensions.width.toLong() * dimensions.height > MAX_IMAGE_EXPORT_PIXELS
        ) {
            throw IOException("Scanner capture exceeds the image limit")
        }
    }

    private fun loadSelectedPreviewLocked(manifest: ScannerV2Manifest) {
        val index = requireNotNull(manifest.state.selectedIndex)
        val record = manifest.pages[index]
        val preview = decodeScannerV2Preview(store.previewFile(manifest, record))
        val filterPreviews = if (record.renderedFingerprint == null) {
            emptyMap()
        } else {
            renderScannerV2FilterPreviews(
                source = store.sourceFile(manifest.sessionId, record.pageId),
                crop = record.crop,
                rotationQuarterTurns = record.rotationQuarterTurns,
            )
        }
        mutableState.value = ScannerV2UiState(
            manifest = manifest,
            preview = preview,
            busy = false,
            filterPreviews = filterPreviews,
            pageThumbnails = loadPageThumbnails(manifest),
        )
    }

    private fun loadPageThumbnails(
        manifest: ScannerV2Manifest,
    ): Map<PageId, ScannerV2PageThumbnail> {
        val cached = mutableState.value.pageThumbnails
        return manifest.pages.associate { record ->
            val existing = cached[record.pageId]
            record.pageId to if (existing?.matches(record) == true) {
                existing
            } else {
                ScannerV2PageThumbnail(
                    sourceFingerprint = record.sourceFingerprint,
                    renderedFingerprint = record.renderedFingerprint,
                    renderFileId = record.renderFileId,
                    bitmap = decodeScannerV2Thumbnail(store.previewFile(manifest, record)),
                )
            }
        }
    }

    private suspend fun recoverAfterRenderFailure() {
        try {
            lock.withLock {
                withContext(Dispatchers.IO) {
                    val persisted = store.loadActive()
                    if (persisted == null || persisted.state.stage != ScannerSessionStage.Reviewing) {
                        mutableState.value = ScannerV2UiState(
                            manifest = persisted,
                            busy = false,
                            issue = ScannerV2Issue.RenderFailed,
                        )
                        return@withContext
                    }
                    val selected = requireNotNull(persisted.state.selectedIndex)
                    val preview = decodeScannerV2Preview(
                        store.previewFile(persisted, persisted.pages[selected]),
                    )
                    mutableState.value = ScannerV2UiState(
                        manifest = persisted,
                        preview = preview,
                        busy = false,
                        issue = ScannerV2Issue.RenderFailed,
                        pageThumbnails = loadPageThumbnails(persisted),
                    )
                }
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            mutableState.value = mutableState.value.copy(
                busy = false,
                issue = ScannerV2Issue.SessionUnavailable,
            )
        }
    }

    private fun nextTimestamp(current: ScannerV2Manifest): Long =
        maxOf(System.currentTimeMillis(), current.updatedAtMillis + 1)

    private fun ScannerV2CaptureTicket.matches(manifest: ScannerV2Manifest): Boolean =
        manifest.sessionId == sessionId &&
            manifest.state.generation == generation &&
            manifest.pendingCaptureId == pageId
}
