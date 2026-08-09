package com.majkeylab.scanit

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
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
    private val pdfGrantLock = ReentrantLock()
    private var processingJob: Job? = null

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
