package com.majkeylab.scanit

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class VariantHandoffDeviceTest {
    @Test(timeout = 20_000)
    fun clearingViewModelBeforeIoResumeDeletesOnlyItsManualCleanupCandidate() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        check(base.packageName == "com.majkeylab.scanit.internal")
        // ViewModel startup reconciles app-wide tree grants; never release another fixture's grants.
        assumeTrue("Requires an internal app without persisted grants", base.contentResolver.persistedUriPermissions.isEmpty())
        val root = Files.createTempDirectory(base.cacheDir.toPath(), "variant-handoff-").toFile()
        val application = object : Application() {
            init { attachBaseContext(base) }
            override fun getApplicationContext(): Context = this
            override fun getCacheDir(): File = root
            override fun getFilesDir(): File = root
            override fun getNoBackupFilesDir(): File = root
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("${root.name}_$name", mode)
        }
        val store = ViewModelStore()
        val releaseMain = CountDownLatch(1)
        var scopeJob: Job? = null
        try {
            val source = File(root, "source.jpg")
            val bitmap = Bitmap.createBitmap(96, 128, Bitmap.Config.ARGB_8888)
            try {
                Canvas(bitmap).apply {
                    drawColor(Color.WHITE)
                    drawRect(28f, 36f, 60f, 80f, Paint().apply { color = Color.BLACK })
                }
                source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            } finally {
                bitmap.recycle()
            }
            val fixtureClock = Clock.fixed(Instant.parse("2026-08-06T12:34:56Z"), ZoneOffset.UTC)
            val storage = ScanStorage(application, fixtureClock)
            val settings = SettingsStore(application)
            settings.save(settings.load().copy(savePdf = false, saveImages = false))
            val parent = storage.activateProvisionalCacheEntry(
                storage.cacheScan(listOf(Uri.fromFile(source)), googleScannerAppearanceSettings(), PdfSizeTarget.Original).cached,
            )
            assertEquals(AuthorityMutationResult.Applied,
                settings.saveActiveResult(parent.baseName, settings.authoritySnapshot().owner))
            val checkpoint = checkNotNull(settings.activeResultCheckpoint())
            val parentDirectory = checkNotNull(parent.pdf.parentFile)
            val originals = (checkNotNull(parentDirectory.listFiles()).toList() + source)
                .associateWith(File::readBytes)
            val handle = SavedStateHandle().apply {
                set("screen_route", "result")
                set("screen_route_cache_id", parent.baseName)
                set("safe_share_active", true)
            }
            lateinit var viewModel: ScanViewModel
            instrumentation.runOnMainSync {
                val factory = object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        checkNotNull(modelClass.cast(ScanViewModel(application, handle).also {
                            scopeJob = it.viewModelScope.coroutineContext[Job]
                        }))
                }
                viewModel = ViewModelProvider(store, factory)[ScanViewModel::class.java]
            }
            withTimeout(5_000) { viewModel.state.filterIsInstance<ScreenState.Result>().first() }
            val bystander = ScanStorage(
                application,
                Clock.fixed(fixtureClock.instant().plusSeconds(1), fixtureClock.zone),
            ).cacheScan(
                listOf(Uri.fromFile(source)), googleScannerAppearanceSettings(), PdfSizeTarget.Original,
            ).cached
            val shareRoot = checkNotNull(parentDirectory.parentFile)
            val knownIds = setOf(parent.baseName, bystander.baseName)
            val blocked = CountDownLatch(1)
            val mainFailure = AtomicReference<Throwable>()
            val main = Handler(Looper.getMainLooper())
            storageTransactionLock.lock()
            try {
                assertTrue(main.post {
                    try {
                        viewModel.openManualCleanupEditor()
                        viewModel.updateManualCleanupStrokes(listOf(MarkStroke(listOf(
                            MarkPoint(0.25f, 0.25f), MarkPoint(0.75f, 0.25f),
                            MarkPoint(0.75f, 0.75f), MarkPoint(0.25f, 0.75f),
                        ))))
                        viewModel.applyManualCleanup()
                        check((viewModel.state.value as ScreenState.Result).manualCleanupEditor?.applying == true)
                        blocked.countDown()
                        check(releaseMain.await(5, TimeUnit.SECONDS)) { "Main blocker timed out" }
                    } catch (failure: Throwable) {
                        mainFailure.set(failure)
                        blocked.countDown()
                    }
                })
                assertTrue("Manual cleanup did not enter Main blocker", blocked.await(2, TimeUnit.SECONDS))
                mainFailure.get()?.let { throw it }
            } finally {
                storageTransactionLock.unlock()
            }
            var owned: CachedScan? = null
            awaitCondition {
                tryStorageTransaction {
                    owned = checkNotNull(shareRoot.listFiles()).singleOrNull {
                        it.name !in knownIds && !it.name.startsWith(".") && File(it, ".provisional").isFile
                    }?.let { storage.openCachedScan(it.name) }
                    owned != null
                } == true
            }
            assertEquals(1L, releaseMain.count)
            // Main remains blocked, so this clear precedes any queued IO continuation.
            assertTrue(main.postAtFrontOfQueue { store.clear() })
            releaseMain.countDown()
            withTimeout(5_000) { checkNotNull(scopeJob).join() }
            mainFailure.get()?.let { throw it }

            assertFalse(File(shareRoot, checkNotNull(owned).baseName).exists())
            assertTrue(storage.isProvisionalCacheEntry(bystander))
            assertEquals(checkpoint, settings.activeResultCheckpoint())
            originals.forEach { (file, bytes) -> assertArrayEquals(file.name, bytes, file.readBytes()) }
        } finally {
            releaseMain.countDown()
            instrumentation.runOnMainSync { store.clear() }
            // Do not remove fixture files while any native IO child is still alive.
            withTimeout(5_000) { scopeJob?.join() }
            check(base.deleteSharedPreferences("${root.name}_settings"))
            check(root.deleteRecursively())
        }
    }

    @Test(timeout = 20_000)
    fun provisionalCheckpointSurvivesScannerAndMissingRoutes() = runBlocking {
        for (route in listOf("scanner", null)) {
            withRestoreFixture { fixture ->
                val handle = SavedStateHandle()
                if (route != null) handle["screen_route"] = route
                val viewModel = fixture.restore(handle)

                val state = awaitInitialScreen(viewModel)
                assertTrue("Committed provisional scan must restore Result for route $route", state is ScreenState.Result)
                assertEquals(fixture.cached.baseName, (state as ScreenState.Result).scan.cached.baseName)
                assertTrue(fixture.cached.pdf.isFile)
                assertFalse(fixture.storage.isProvisionalCacheEntry(fixture.cached))
                assertEquals(fixture.cached.baseName, fixture.settings.activeResultCacheId())
                assertNull(viewModel.scannerRequest.value)
            }
        }
    }

    @Test(timeout = 15_000)
    fun ordinaryColdStartStillLaunchesScannerWithoutDeletingCompletedScan() = runBlocking {
        withRestoreFixture { fixture ->
            fixture.storage.activateCheckpointProvisional(fixture.cached)
            val viewModel = fixture.restore(SavedStateHandle())

            assertTrue(awaitInitialScreen(viewModel) is ScreenState.Processing)
            assertNotNull(viewModel.scannerRequest.value)
            assertNull(fixture.settings.activeResultCacheId())
            assertTrue(fixture.cached.pdf.isFile)
        }
    }

    @Test(timeout = 30_000)
    fun outputTreeRestoreRequiresAuthoritativeCacheAndEntry() = runBlocking {
        for (kind in listOf(OutputChangeKind.PdfLocation, OutputChangeKind.ImageLocation)) {
            for (mismatch in listOf(null, "cache", "entry")) {
                withRestoreFixture { fixture ->
                    fixture.storage.activateCheckpointProvisional(fixture.cached)
                    val request = OutputChangeRequest(
                        if (mismatch == "cache") "Scan_other" else fixture.cached.baseName,
                        if (mismatch == "entry") UUID.randomUUID().toString() else checkNotNull(fixture.cached.entryId),
                        kind,
                        1L,
                    )
                    val handle = if (mismatch == null) {
                        SavedStateHandle(mapOf("screen_route" to "scanner", "screen_route_cache_id" to "Scan_stale_route"))
                    } else {
                        resultRestoreHandle(request.cacheId)
                    }
                    val pending = OutputTreeSavedState(handle)
                    assertTrue(pending.saveLaunch(request))
                    assertTrue(pending.claimLaunch(request))
                    val viewModel = fixture.restore(handle)

                    val state = awaitInitialScreen(viewModel)
                    if (mismatch == null) {
                        assertTrue("Matching $kind request must restore its Result", state is ScreenState.Result)
                        assertTrue((state as ScreenState.Result).outputChangeInProgress)
                        assertEquals(fixture.cached.entryId, state.scan.cached.entryId)
                        assertNull(viewModel.outputTreePickerRequest.value)
                        InstrumentationRegistry.getInstrumentation().runOnMainSync {
                            assertEquals(OutputTreeCallbackDisposition.Accepted, viewModel.outputTreePickerCancelled(request))
                            assertEquals(OutputTreeCallbackDisposition.DefiniteStale, viewModel.outputTreePickerCancelled(request))
                        }
                    } else {
                        assertTrue(state is ScreenState.Processing)
                        assertNotNull(viewModel.scannerRequest.value)
                        InstrumentationRegistry.getInstrumentation().runOnMainSync {
                            assertNull(pending.activeRequest())
                            assertEquals(OutputTreeCallbackDisposition.DefiniteStale, viewModel.outputTreePickerCancelled(request))
                        }
                    }
                    assertTrue(fixture.cached.pdf.isFile)
                }
            }
        }
    }

    @Test(timeout = 25_000)
    fun textExportRestoreRequiresAuthoritativeCacheAndEntry() = runBlocking {
        for (mismatch in listOf(null, "cache", "entry")) {
            withRestoreFixture { fixture ->
                fixture.storage.activateCheckpointProvisional(fixture.cached)
                val request = DocumentActionRequest(
                    if (mismatch == "cache") "Scan_other" else fixture.cached.baseName,
                    if (mismatch == "entry") UUID.randomUUID().toString() else checkNotNull(fixture.cached.entryId),
                    0,
                    DocumentAction.ExtractText,
                    1L,
                )
                val output = DocumentActionOutput.Text("Exact OCR text", false)
                val handle = if (mismatch == null) SavedStateHandle() else resultRestoreHandle(request.cacheId)
                val pending = DocumentTextExportSavedState(handle, File(fixture.application.cacheDir, "text-export"))
                withContext(Dispatchers.Main) { assertTrue(pending.saveLaunch(request, output)) }
                val viewModel = fixture.restore(handle)

                val state = awaitInitialScreen(viewModel)
                if (mismatch == null) {
                    assertTrue("Matching text export must restore its Result", state is ScreenState.Result)
                    assertEquals(DocumentActionState.Exporting(output), (state as ScreenState.Result).documentActionState)
                    assertEquals(fixture.cached.entryId, state.scan.cached.entryId)
                    InstrumentationRegistry.getInstrumentation().runOnMainSync {
                        assertEquals(DocumentTextExportDisposition.Accepted, viewModel.documentTextExportDestinationCancelled(request))
                        assertEquals(DocumentActionState.Completed(output), (viewModel.state.value as ScreenState.Result).documentActionState)
                        assertEquals(DocumentTextExportDisposition.DefiniteStale, viewModel.documentTextExportDestinationCancelled(request))
                    }
                } else {
                    assertTrue(state is ScreenState.Processing)
                    assertNotNull(viewModel.scannerRequest.value)
                    InstrumentationRegistry.getInstrumentation().runOnMainSync {
                        assertNull(pending.activeRequest())
                        assertEquals(DocumentTextExportDisposition.DefiniteStale, viewModel.documentTextExportDestinationCancelled(request))
                    }
                }
                assertTrue(fixture.cached.pdf.isFile)
            }
        }
    }

    @Test(timeout = 15_000)
    fun replayedOutputTreeResultCannotReplacePendingSelection() = runBlocking {
        withRestoreFixture { fixture ->
            fixture.storage.activateCheckpointProvisional(fixture.cached)
            val request = OutputChangeRequest(
                fixture.cached.baseName, checkNotNull(fixture.cached.entryId), OutputChangeKind.PdfLocation, 1L,
            )
            val handle = resultRestoreHandle(request.cacheId)
            val pending = OutputTreeSavedState(handle)
            assertTrue(pending.saveLaunch(request))
            assertTrue(pending.claimLaunch(request))
            val first = Uri.parse("content://qa.documents/tree/first")
            val replay = Uri.parse("content://qa.documents/tree/replay")
            fixture.restore(handle) { viewModel ->
                assertEquals(OutputTreeCallbackDisposition.Accepted, viewModel.outputTreePickerSelected(request, first, PDF_TREE_FLAGS))
                val replayDisposition = viewModel.outputTreePickerSelected(request, replay, PDF_TREE_FLAGS)
                assertEquals(first.toString(), pending.pendingSelection()?.uri)
                assertEquals(OutputTreeCallbackDisposition.DefiniteStale, replayDisposition)
                assertEquals(OutputTreeCallbackDisposition.DefiniteStale, viewModel.outputTreePickerCancelled(request))
                assertEquals(first.toString(), pending.pendingSelection()?.uri)
            }
        }
    }

    @Test(timeout = 15_000)
    fun outputTreeCancellationBeforeBootstrapStillRestoresItsResult() = runBlocking {
        withRestoreFixture { fixture ->
            fixture.storage.activateCheckpointProvisional(fixture.cached)
            val request = OutputChangeRequest(
                fixture.cached.baseName, checkNotNull(fixture.cached.entryId), OutputChangeKind.PdfLocation, 1L,
            )
            val handle = resultRestoreHandle(request.cacheId)
            val pending = OutputTreeSavedState(handle)
            assertTrue(pending.saveLaunch(request))
            assertTrue(pending.claimLaunch(request))
            val viewModel = fixture.restore(handle) {
                assertEquals(OutputTreeCallbackDisposition.Accepted, it.outputTreePickerCancelled(request))
            }

            val state = awaitInitialScreen(viewModel)
            assertTrue(state is ScreenState.Result)
            assertFalse((state as ScreenState.Result).outputChangeInProgress)
            assertEquals(fixture.cached.entryId, state.scan.cached.entryId)
            assertNull(viewModel.scannerRequest.value)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                assertEquals(OutputTreeCallbackDisposition.DefiniteStale, viewModel.outputTreePickerCancelled(request))
            }
        }
    }

    @Test(timeout = 15_000)
    fun textExportCancellationBeforeBootstrapStillRestoresItsResult() = runBlocking {
        withRestoreFixture { fixture ->
            fixture.storage.activateCheckpointProvisional(fixture.cached)
            val request = DocumentActionRequest(
                fixture.cached.baseName, checkNotNull(fixture.cached.entryId), 0, DocumentAction.ExtractText, 1L,
            )
            val output = DocumentActionOutput.Text("Retained OCR text", false)
            val handle = resultRestoreHandle(request.cacheId)
            val pending = DocumentTextExportSavedState(handle, File(fixture.application.cacheDir, "text-export"))
            withContext(Dispatchers.Main) { assertTrue(pending.saveLaunch(request, output)) }
            val viewModel = fixture.restore(handle) {
                assertEquals(DocumentTextExportDisposition.Accepted, it.documentTextExportDestinationCancelled(request))
            }

            assertTrue(awaitInitialScreen(viewModel) is ScreenState.Result)
            assertEquals(DocumentActionState.Completed(output), (viewModel.state.value as ScreenState.Result).documentActionState)
            assertNull(viewModel.scannerRequest.value)
        }
    }

    @Test(timeout = 15_000)
    fun navigationRequestsBeforeBootstrapCannotDiscardCheckpoint() = runBlocking {
        for (action in listOf("scanner", "recent", "back", "resume")) {
            withRestoreFixture { fixture ->
                val handle = resultRestoreHandle(fixture.cached.baseName).apply {
                    set("safe_share_active", true)
                }
                val viewModel = fixture.restore(handle) {
                    val initial = it.state.value
                    assertTrue(initial is ScreenState.Processing && !initial.canNavigateBack)
                    when (action) {
                        "scanner" -> it.beginScannerLaunch()
                        "recent" -> it.showRecent()
                        "back" -> it.navigateBack()
                        "resume" -> it.resumeScannerPreparation()
                    }
                    assertEquals(initial, it.state.value)
                }

                val state = awaitInitialScreen(viewModel)
                assertTrue("$action must not cancel bootstrap", state is ScreenState.Result)
                assertEquals(fixture.cached.entryId, (state as ScreenState.Result).scan.cached.entryId)
                assertEquals(fixture.cached.baseName, fixture.settings.activeResultCacheId())
                assertTrue(fixture.cached.pdf.isFile)
                assertNull(viewModel.scannerRequest.value)
            }
        }
    }

    @Test(timeout = 15_000)
    fun immediateScannerLaunchFailureDoesNotStrandCallbackBootstrap() = runBlocking {
        withRestoreFixture { fixture ->
            fixture.storage.activateCheckpointProvisional(fixture.cached)
            val launchFailure = UiMessage(R.string.scanner_launch_error)
            val failedRequest = CompletableDeferred<Long>()
            val viewModel = fixture.restore(SavedStateHandle()) { restored ->
                restored.viewModelScope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                    val request = checkNotNull(restored.scannerRequest.first { it != null })
                    assertTrue(restored.claimScannerRequest(request))
                    restored.scannerLaunchFailed(request, launchFailure)
                    failedRequest.complete(request)
                }
            }
            val failure = withTimeout(5_000) { viewModel.state.filterIsInstance<ScreenState.Failure>().first() }
            assertEquals(launchFailure, failure.message)
            val staleRequest = withTimeout(5_000) { failedRequest.await() }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModel.beginScannerLaunch() }
            val request = checkNotNull(withTimeout(5_000) { viewModel.scannerRequest.first { it != null } })
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                viewModel.scannerLaunchFailed(staleRequest, launchFailure)
                assertEquals(request, viewModel.scannerRequest.value)
                assertTrue(viewModel.claimScannerRequest(request))
                viewModel.scannerLaunched(request)
                viewModel.scannerCancelled()
            }
            withTimeout(5_000) { viewModel.state.filterIsInstance<ScreenState.Recent>().first() }
            assertTrue(fixture.cached.pdf.isFile)
        }
    }

    @Test(timeout = 15_000)
    fun recentStartupPublishesLoadedHistoryBeforeAcceptingScannerCallbacks() = runBlocking {
        withRestoreFixture { fixture ->
            fixture.storage.activateCheckpointProvisional(fixture.cached)
            assertEquals(AuthorityMutationResult.Applied, fixture.settings.clearActiveResult(fixture.settings.authoritySnapshot().owner))
            val firstRecent = CompletableDeferred<ScreenState.Recent>()
            val viewModel = fixture.restore(SavedStateHandle(mapOf("screen_route" to "recent"))) { restored ->
                restored.viewModelScope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                    val recent = restored.state.filterIsInstance<ScreenState.Recent>().first()
                    firstRecent.complete(recent)
                    assertTrue(Handler(Looper.getMainLooper()).post { restored.beginScannerLaunch() })
                }
            }

            val recent = withTimeout(5_000) { firstRecent.await() }
            assertEquals(listOf(fixture.cached.baseName), recent.scans.map(RecentScan::cacheId))
            val request = checkNotNull(withTimeout(5_000) { viewModel.scannerRequest.first { it != null } })
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                assertTrue(viewModel.claimScannerRequest(request))
                viewModel.scannerLaunched(request)
                viewModel.scannerCancelled()
            }
            val afterCancellation = withTimeout(5_000) { viewModel.state.filterIsInstance<ScreenState.Recent>().first() }
            assertEquals(listOf(fixture.cached.baseName), afterCancellation.scans.map(RecentScan::cacheId))
        }
    }

    private fun resultRestoreHandle(cacheId: String) = SavedStateHandle(mapOf(
        "screen_route" to "result",
        "screen_route_cache_id" to cacheId,
        "result_page_index" to 0,
    ))

    private suspend fun awaitInitialScreen(viewModel: ScanViewModel): ScreenState {
        val state = withTimeout(5_000) {
            viewModel.state.first { it !is ScreenState.Processing || it.canNavigateBack }
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { }
        return state
    }

    private class RestoreFixture(
        val application: Application,
        val storage: ScanStorage,
        val settings: SettingsStore,
        val cached: CachedScan,
    ) {
        val store = ViewModelStore()
        var scopeJob: Job? = null

        fun restore(
            handle: SavedStateHandle,
            onCreated: (ScanViewModel) -> Unit = {},
        ): ScanViewModel {
            lateinit var viewModel: ScanViewModel
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val factory = object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        checkNotNull(modelClass.cast(ScanViewModel(application, handle).also {
                            scopeJob = it.viewModelScope.coroutineContext[Job]
                        }))
                }
                viewModel = ViewModelProvider(store, factory)[ScanViewModel::class.java]
                onCreated(viewModel)
            }
            return viewModel
        }
    }

    private suspend fun withRestoreFixture(test: suspend (RestoreFixture) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        check(base.packageName == "com.majkeylab.scanit.internal")
        assumeTrue("Requires an internal app without persisted grants", base.contentResolver.persistedUriPermissions.isEmpty())
        val root = Files.createTempDirectory(base.cacheDir.toPath(), "restore-handoff-").toFile()
        var fixture: RestoreFixture? = null
        try {
            val application = object : Application() {
                init { attachBaseContext(base) }
                override fun getApplicationContext(): Context = this
                override fun getCacheDir(): File = root
                override fun getFilesDir(): File = root
                override fun getNoBackupFilesDir(): File = root
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                    base.getSharedPreferences(root.name + "_" + name, mode)
            }
            val source = File(root, "source.jpg")
            val bitmap = Bitmap.createBitmap(96, 128, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.WHITE)
                source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            } finally {
                bitmap.recycle()
            }
            val storage = ScanStorage(application)
            val settings = SettingsStore(application)
            settings.save(settings.load().copy(savePdf = false, saveImages = false))
            val cached = storage.cacheScan(
                listOf(Uri.fromFile(source)), googleScannerAppearanceSettings(), PdfSizeTarget.Original,
            ).cached
            assertEquals(AuthorityMutationResult.Applied, settings.saveActiveResult(cached.baseName, settings.authoritySnapshot().owner))
            val restored = RestoreFixture(application, storage, settings, cached)
            fixture = restored
            test(restored)
        } finally {
            val restored = fixture
            if (restored != null) {
                instrumentation.runOnMainSync { restored.store.clear() }
                withTimeout(5_000) { restored.scopeJob?.join() }
            }
            check(base.deleteSharedPreferences(root.name + "_settings"))
            check(root.deleteRecursively())
        }
    }

    private suspend fun awaitCondition(condition: () -> Boolean) {
        withTimeout(4_000) {
            while (!condition()) delay(10)
        }
    }
}
