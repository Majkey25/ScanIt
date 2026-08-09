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
private const val ROUTE_KEY = "screen_route"
private const val ROUTE_CACHE_ID_KEY = "screen_route_cache_id"
private const val ROUTE_SCANNER = "scanner"
private const val ROUTE_RECENT = "recent"
private const val ROUTE_RECENT_WITH_RESULT_BACK = "recent_with_result_back"
private const val ROUTE_RESULT_WITH_RECENT_BACK = "result_with_recent_back"
private const val ROUTE_RESULT = "result"
private const val ROUTE_FAILURE = "failure"
private const val RECENT_THUMBNAIL_SIZE = 256
internal const val PDF_TREE_FLAGS =
    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

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
    val generation: Long,
)

internal class RecentActionGate {
    private var generation = 0L

    fun begin(cacheId: String): RecentAction = RecentAction(cacheId, nextGeneration())

    fun invalidate() {
        nextGeneration()
    }

    fun isCurrent(action: RecentAction, cacheIds: Iterable<String>): Boolean =
        action.generation == generation && cacheIds.any { it == action.cacheId }

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
    private val inFlight = mutableSetOf<RecentDeletion>()

    fun begin(cacheId: String): RecentDeletion {
        val deletion = RecentDeletion(cacheId, nextGeneration())
        current = deletion
        inFlight += deletion
        return deletion
    }

    fun isCurrent(deletion: RecentDeletion): Boolean = current == deletion

    fun complete(deletion: RecentDeletion): Boolean {
        if (current == deletion) {
            current = null
        }
        return inFlight.remove(deletion)
    }

    fun invalidateCurrent() {
        current = null
    }

    fun canRestore(cacheId: String?): Boolean =
        cacheId != null && inFlight.none { it.cacheId == cacheId }

    private fun nextGeneration(): Long {
        check(generation < Long.MAX_VALUE) { "Recent deletion generation exhausted" }
        generation += 1L
        return generation
    }
}

internal fun retainedRecentResultCacheId(
    cacheId: String?,
    recentCacheIds: Iterable<String>?,
): String? = cacheId?.takeIf { id -> recentCacheIds?.any { it == id } == true }

internal class ScanViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val initialRoute =
        restoredRoute(
            savedStateHandle[ROUTE_KEY],
            savedStateHandle[ROUTE_CACHE_ID_KEY],
        )
    private val initialCacheId = savedStateHandle.get<String>(ROUTE_CACHE_ID_KEY)
    private val settingsStore = SettingsStore(application)
    private val storage = ScanStorage(application)
    private val scannerLaunchGate =
        ScannerLaunchGate(
            ScannerLaunchStage.entries.firstOrNull {
                it.name == savedStateHandle.get<String>(SCANNER_STAGE_KEY)
            } ?: ScannerLaunchStage.Idle,
        )
    private val recentActionGate = RecentActionGate()
    private val recentDeletionGate = RecentDeletionGate()
    private val mutableState =
        MutableStateFlow<ScreenState>(
            if (initialRoute == RestoredRoute.Scanner) {
                ScreenState.Ready
            } else {
                ScreenState.Recent(emptyList(), canGoBack = false)
            },
        )
    private val mutableSettings = MutableStateFlow(settingsStore.load())
    private val pdfGrantLock = ReentrantLock()
    private var processingJob: Job? = null
    private var recentJob: Job? = null
    private var recentScans: List<RecentScan> = emptyList()
    private var previousResult: ScreenState.Result? = null

    val state: StateFlow<ScreenState> = mutableState.asStateFlow()
    val settings: StateFlow<AppSettings> = mutableSettings.asStateFlow()

    init {
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
        restoreNavigation()
    }

    fun shouldLaunchScannerOnCreate(): Boolean = initialRoute == RestoredRoute.Scanner

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

    fun beginScannerLaunch(): Long? {
        val request = scannerLaunchGate.begin(processingJob?.isActive == true) ?: return null
        recentActionGate.invalidate()
        recentDeletionGate.invalidateCurrent()
        recentJob?.cancel()
        previousResult = null
        persistRoute(ROUTE_SCANNER)
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
            persistRoute(ROUTE_FAILURE)
            mutableState.value = ScreenState.Failure(message)
        }
    }

    fun scannerCancelled() {
        completeScannerLaunch()
        if (processingJob?.isActive != true) {
            previousResult = null
            refreshRecentScreen(canGoBack = false)
        }
    }

    fun scannerResultFailed(message: UiMessage) {
        completeScannerLaunch()
        if (processingJob?.isActive != true) {
            persistRoute(ROUTE_FAILURE)
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
                    persistRoute(ROUTE_RESULT)
                    refreshRecentCache(result.scan.cached.baseName)
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
                    persistRoute(ROUTE_FAILURE)
                } finally {
                    processingJob = null
                }
            }
        return true
    }

    fun showRecent() {
        val result = (mutableState.value as? ScreenState.Result)?.takeUnless { it.returnToRecent }
        previousResult = result
        refreshRecentScreen(canGoBack = result != null)
    }

    fun navigateBack() {
        recentActionGate.invalidate()
        when (val current = mutableState.value) {
            is ScreenState.Recent -> {
                val result = previousResult
                val cacheId = result?.scan?.cached?.baseName
                if (current.canGoBack && result != null && recentDeletionGate.canRestore(cacheId)) {
                    recentDeletionGate.invalidateCurrent()
                    recentJob?.cancel()
                    previousResult = null
                    mutableState.value = result
                    persistRoute(ROUTE_RESULT)
                }
            }
            is ScreenState.Result -> {
                if (current.returnToRecent) {
                    refreshRecentScreen(canGoBack = false)
                }
            }
            else -> Unit
        }
    }

    fun openRecentScan(cacheId: String) {
        recentActionGate.invalidate()
        recentDeletionGate.invalidateCurrent()
        previousResult = null
        persistRoute(ROUTE_RECENT)
        mutableState.value = ScreenState.Processing(UiMessage(R.string.opening_document))
        recentJob?.cancel()
        recentJob =
            viewModelScope.launch {
                val result = loadCachedResult(cacheId, returnToRecent = true)
                if (result == null) {
                    showRecentResult(
                        canGoBack = false,
                        message = UiMessage(R.string.recent_scan_unavailable),
                    )
                } else {
                    mutableState.value = result
                    persistRoute(ROUTE_RESULT_WITH_RECENT_BACK, cacheId)
                    refreshRecentCache(cacheId)
                }
            }
    }

    fun deleteRecentScan(cacheId: String) {
        recentActionGate.invalidate()
        val deletion = recentDeletionGate.begin(cacheId)
        val canGoBack = previousResult != null
        recentJob?.cancel()
        recentJob =
            viewModelScope.launch {
                try {
                    val deleted =
                        try {
                            withContext(Dispatchers.IO) { storage.deleteRecentScan(cacheId) }
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Exception) {
                            false
                        }
                    if (!recentDeletionGate.isCurrent(deletion)) {
                        return@launch
                    }
                    showRecentResult(
                        canGoBack = canGoBack,
                        message = if (deleted) null else UiMessage(R.string.recent_delete_failed),
                    )
                } finally {
                    recentDeletionGate.complete(deletion)
                }
            }
    }

    fun beginRecentShare(cacheId: String): RecentAction? {
        val current = mutableState.value as? ScreenState.Recent ?: return null
        if (current.scans.none { it.cacheId == cacheId }) {
            return null
        }
        return recentActionGate.begin(cacheId)
    }

    suspend fun recentScanForShare(action: RecentAction): CachedScan? =
        try {
            withContext(Dispatchers.IO) { storage.openCachedScan(action.cacheId) }
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
        refreshRecentScreen(
            canGoBack = previousResult != null,
            message = UiMessage(R.string.recent_scan_unavailable),
        )
        return true
    }

    private fun isRecentShareCurrent(action: RecentAction): Boolean {
        val current = mutableState.value as? ScreenState.Recent ?: return false
        return recentActionGate.isCurrent(action, current.scans.map(RecentScan::cacheId))
    }

    suspend fun loadRecentThumbnail(firstPage: File): Bitmap? =
        withContext(Dispatchers.IO) {
            storage.loadThumbnail(firstPage, RECENT_THUMBNAIL_SIZE)
        }

    private fun restoreNavigation() {
        when (initialRoute) {
            RestoredRoute.Scanner -> refreshRecentCache()
            RestoredRoute.Recent -> refreshRecentScreen(canGoBack = false)
            RestoredRoute.RecentWithResultBack -> restoreSavedResult(showRecent = true)
            RestoredRoute.ResultWithRecentBack -> restoreSavedResult(showRecent = false)
        }
    }

    private fun restoreSavedResult(showRecent: Boolean) {
        val cacheId = initialCacheId
        if (cacheId == null) {
            refreshRecentScreen(canGoBack = false)
            return
        }
        recentJob?.cancel()
        recentJob =
            viewModelScope.launch {
                val result = loadCachedResult(cacheId, returnToRecent = !showRecent)
                if (result == null) {
                    showRecentResult(
                        canGoBack = false,
                        message = UiMessage(R.string.recent_scan_unavailable),
                    )
                } else if (showRecent) {
                    previousResult = result
                    showRecentResult(canGoBack = true)
                } else {
                    mutableState.value = result
                    refreshRecentCache(cacheId)
                }
            }
    }

    private suspend fun loadCachedResult(
        cacheId: String,
        returnToRecent: Boolean,
    ): ScreenState.Result? =
        try {
            withContext(Dispatchers.IO) {
                val cached = storage.openCachedScan(cacheId) ?: return@withContext null
                ScreenState.Result(
                    scan = SavedScan(cached, galleryPages = emptyList(), savedPdf = null),
                    thumbnail = storage.loadThumbnail(cached.pages.first()),
                    returnToRecent = returnToRecent,
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }

    private fun refreshRecentScreen(
        canGoBack: Boolean,
        message: UiMessage? = null,
    ) {
        recentActionGate.invalidate()
        recentDeletionGate.invalidateCurrent()
        val cacheId = previousResult?.scan?.cached?.baseName.takeIf { canGoBack }
        persistRoute(
            if (cacheId == null) ROUTE_RECENT else ROUTE_RECENT_WITH_RESULT_BACK,
            cacheId,
        )
        mutableState.value = ScreenState.Recent(recentScans, canGoBack && cacheId != null, message)
        recentJob?.cancel()
        recentJob =
            viewModelScope.launch {
                showRecentResult(canGoBack = canGoBack, message = message)
            }
    }

    private suspend fun showRecentResult(
        canGoBack: Boolean,
        message: UiMessage? = null,
    ) {
        val cacheId = previousResult?.scan?.cached?.baseName.takeIf { canGoBack }
        val scans = loadRecentScans(cacheId)
        recentScans = scans ?: emptyList()
        val retainedCacheId =
            retainedRecentResultCacheId(cacheId, scans?.map(RecentScan::cacheId))
        val resultAvailable = retainedCacheId != null
        if (cacheId != null && !resultAvailable) {
            previousResult = null
        }
        val effectiveMessage =
            message ?: when {
                scans == null -> UiMessage(R.string.recent_history_unavailable)
                cacheId != null && !resultAvailable -> UiMessage(R.string.recent_scan_unavailable)
                else -> null
            }
        val effectiveCanGoBack = resultAvailable
        mutableState.value =
            ScreenState.Recent(recentScans, effectiveCanGoBack, effectiveMessage)
        persistRoute(
            if (effectiveCanGoBack) ROUTE_RECENT_WITH_RESULT_BACK else ROUTE_RECENT,
            retainedCacheId,
        )
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
        recentJob?.cancel()
        recentJob =
            viewModelScope.launch {
                loadRecentScans(protectedCacheId)?.let { recentScans = it }
            }
    }

    private fun persistRoute(route: String, cacheId: String? = null) {
        savedStateHandle[ROUTE_KEY] = route
        savedStateHandle[ROUTE_CACHE_ID_KEY] = cacheId
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
