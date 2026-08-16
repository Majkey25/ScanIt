package com.majkeylab.scanit

import android.app.Application
import android.graphics.Bitmap
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
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
    RenderFailed,
    CameraUnavailable,
}

internal data class ScannerV2UiState(
    val manifest: ScannerV2Manifest? = null,
    val preview: Bitmap? = null,
    val busy: Boolean = true,
    val issue: ScannerV2Issue? = null,
)

internal data class ScannerV2CaptureTicket(
    val sessionId: String,
    val pageId: PageId,
    val generation: Long,
    val destination: File,
)

internal class ScannerV2ViewModel(application: Application) : AndroidViewModel(application) {
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

    fun captureFailed(ticket: ScannerV2CaptureTicket) {
        viewModelScope.launch {
            try {
                lock.withLock {
                    val current = mutableState.value.manifest ?: return@withLock
                    if (!ticket.matches(current)) return@withLock
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
                        check(record.renderedFingerprint == null) { "Scanner page is already rendered" }
                        val result = renderScannerV2Page(
                            source = store.sourceFile(current.sessionId, record.pageId),
                            destination = store.renderedFile(current.sessionId, record.pageId),
                            crop = crop,
                            rotationQuarterTurns = rotationQuarterTurns,
                        )
                        val pages = current.pages.toMutableList().apply {
                            this[selected] = record.copy(
                                crop = crop,
                                rotationQuarterTurns = rotationQuarterTurns,
                                renderedFingerprint = result.fingerprint,
                            )
                        }
                        val replacement = ScannerV2Manifest.create(
                            sessionId = current.sessionId,
                            state = current.state,
                            pages = pages,
                            retiredPages = current.retiredPages,
                            updatedAtMillis = nextTimestamp(current),
                        )
                        val renderedPreview = decodeScannerV2Preview(
                            store.renderedFile(current.sessionId, record.pageId),
                        )
                        try {
                            store.update(current, replacement)
                        } catch (failure: Throwable) {
                            renderedPreview.recycle()
                            throw failure
                        }
                        replacePreview(renderedPreview)
                        mutableState.value = mutableState.value.copy(
                            manifest = replacement,
                            preview = renderedPreview,
                            busy = false,
                            issue = null,
                        )
                    }
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableState.value = mutableState.value.copy(busy = false, issue = ScannerV2Issue.RenderFailed)
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
                            updatedAtMillis = nextTimestamp(current),
                        )
                        store.update(current, replacement)
                        replacePreview(null)
                        mutableState.value = ScannerV2UiState(replacement, busy = false)
                    }
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableState.value = mutableState.value.copy(busy = false, issue = ScannerV2Issue.SessionUnavailable)
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
                        replacePreview(null)
                        mutableState.value = ScannerV2UiState(replacement, busy = false)
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
                        replacePreview(null)
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
                            updatedAtMillis = nextTimestamp(current),
                        )
                        store.update(current, replacement)
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

    fun cancelCamera() {
        viewModelScope.launch {
            try {
                lock.withLock {
                    withContext(Dispatchers.IO) {
                        val current = requireNotNull(mutableState.value.manifest)
                        if (current.pendingCaptureId != null) return@withContext
                        val replacement = cancelScannerV2Capture(
                            current,
                            current.state.generation,
                            nextTimestamp(current),
                        )
                        store.update(current, replacement)
                        if (replacement.state.stage == ScannerSessionStage.Cancelled) {
                            if (!store.deleteCancelled(replacement)) throw IOException("Cancelled session could not be removed")
                            replacePreview(null)
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
        replacePreview(null)
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
            replacePreview(null)
            val fresh = createSession()
            mutableState.value = ScannerV2UiState(fresh, busy = false)
        } else {
            mutableState.value = ScannerV2UiState(replacement, busy = true)
            loadSelectedPreviewLocked(replacement)
        }
    }

    private suspend fun completeCaptureLocked(ticket: ScannerV2CaptureTicket) = withContext(Dispatchers.IO) {
        val current = mutableState.value.manifest ?: return@withContext
        if (!ticket.matches(current)) return@withContext
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
                filterId = "original",
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
                replacePreview(preview)
                mutableState.value = ScannerV2UiState(
                    replacement,
                    preview = preview,
                    busy = false,
                    issue = ScannerV2Issue.SessionUnavailable,
                )
                return@withContext
            }
            replacePreview(preview)
            mutableState.value = ScannerV2UiState(
                cleaned,
                preview = preview,
                busy = false,
                issue = if (cleaned.retiredPages.isEmpty()) null else ScannerV2Issue.SessionUnavailable,
            )
        } catch (failure: Throwable) {
            preview.recycle()
            throw failure
        }
    }

    private fun validateScannerV2Source(file: File) {
        if (!file.isFile || file.length() !in 1..64L * 1024 * 1024) {
            throw IOException("Scanner capture is missing or too large")
        }
        val dimensions = readJpegDimensions(file)
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
        val preview = decodeScannerV2Preview(store.sourceFile(manifest.sessionId, manifest.pages[index].pageId))
        replacePreview(preview)
        mutableState.value = ScannerV2UiState(manifest, preview = preview, busy = false)
    }

    private fun replacePreview(preview: Bitmap?) {
        val previous = mutableState.value.preview
        if (previous !== preview) previous?.takeUnless(Bitmap::isRecycled)?.recycle()
    }

    private fun nextTimestamp(current: ScannerV2Manifest): Long =
        maxOf(System.currentTimeMillis(), current.updatedAtMillis + 1)

    private fun ScannerV2CaptureTicket.matches(manifest: ScannerV2Manifest): Boolean =
        manifest.sessionId == sessionId &&
            manifest.state.generation == generation &&
            manifest.pendingCaptureId == pageId
}
