package com.majkeylab.scanit

import android.content.SharedPreferences
import androidx.lifecycle.SavedStateHandle
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanLifecycleRegressionTest {
    @Test(timeout = 10_000L)
    fun cancelledCommitHandsOffOwnerBeforeWaitingNavigationClearsCheckpoint() = queuedTest { io, main ->
        val store = SettingsStore(preferences(), "Scanned document")
        var owner = store.authoritySnapshot().owner
        val mutex = Mutex()
        val route = RouteMutationGate()
        val generation = route.begin()
        var currentRoute: Boolean? = null
        val writer = launch(start = CoroutineStart.UNDISPATCHED) {
            currentRoute = withCheckpointHandoff(mutex) {
                val saved = withContext(io) { store.saveActiveResult(CACHE_ID, owner) }
                assertEquals(AuthorityMutationResult.Applied, saved)
                owner = owner.withCheckpoint(ActiveResultCheckpoint(CACHE_ID))
                route.isCurrent(generation)
            }
            currentCoroutineContext().ensureActive()
            error("Cancellation must still stop the writer after handoff")
        }

        io.runNext()
        assertEquals(CACHE_ID, store.activeResultCacheId())
        assertNull(owner.checkpoint)
        writer.cancel()
        route.begin()
        val navigation = launch(start = CoroutineStart.UNDISPATCHED) {
            withCheckpointHandoff(mutex) {
                assertTrue(store.ownsActiveResult(owner))
                assertEquals(AuthorityMutationResult.Applied, store.clearActiveResult(owner))
            }
        }
        writer.joinQueued(io, main)
        navigation.joinQueued(io, main)

        assertFalse(requireNotNull(currentRoute))
        assertTrue(writer.isCancelled)
        assertNull(store.activeResultCheckpoint())
        assertFalse(mutex.isLocked)
    }

    @Test
    fun cancellationWhileWaitingForCheckpointMutexNeverCommits() = runBlocking {
        val mutex = Mutex(locked = true)
        var committed = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            withCheckpointHandoff(mutex) { committed = true }
        }

        job.cancel()
        mutex.unlock()
        job.join()

        assertFalse(committed)
        assertTrue(job.isCancelled)
    }

    @Test(timeout = 10_000L)
    fun cancelledAuthorityReadHandsOffNewRevisionBeforeNextMutation() = queuedTest { io, main ->
        val store = SettingsStore(preferences(), "Scanned document")
        var owner = store.authoritySnapshot().owner
        val previousOwner = owner
        val mutex = Mutex()
        val reader = launch(start = CoroutineStart.UNDISPATCHED) {
            withCheckpointHandoff(mutex) {
                owner = withContext(io) { store.authoritySnapshot().owner }
            }
            currentCoroutineContext().ensureActive()
            error("Cancellation must still stop the reader after handoff")
        }

        io.runNext()
        assertFalse(store.ownsActiveResult(previousOwner))
        assertSame(previousOwner, owner)
        reader.cancel()
        val mutation = launch(start = CoroutineStart.UNDISPATCHED) {
            withCheckpointHandoff(mutex) {
                assertEquals(AuthorityMutationResult.Applied, store.saveActiveResult(CACHE_ID, owner))
            }
        }
        reader.joinQueued(io, main)
        mutation.joinQueued(io, main)

        assertTrue(reader.isCancelled)
        assertEquals(CACHE_ID, store.activeResultCacheId())
    }

    @Test
    fun unchangedContentRefreshPreservesCompletedOcrWhileUpdatingOutputMetadata() {
        val action = DocumentActionState.Completed(DocumentActionOutput.FindReady, action = DocumentAction.FindText)
        val snapshot = result().copy(documentActionState = action)
        val loaded = snapshot.scan.copy(savedPdfDisplayName = "renamed.pdf")
        val refreshed = requireNotNull(refreshResultAfterShareCleanup(snapshot, snapshot, loaded, true))

        assertEquals("renamed.pdf", refreshed.scan.savedPdfDisplayName)
        assertSame(action, refreshed.documentActionState)
    }

    @Test
    fun refreshCannotOverwriteNewerOperationOrInvalidateNewAnalysis() {
        val snapshot = result()
        val latestStates = listOf(
            snapshot.copy(scan = snapshot.scan.copy(savedPdfDisplayName = "new.pdf")),
            snapshot.copy(safeShareState = SafeShareState.Analyzing),
            snapshot.copy(documentActionState = DocumentActionState.Exporting(DocumentActionOutput.Text("OCR", false))),
        )

        latestStates.forEach { latest ->
            assertNull(refreshResultAfterShareCleanup(snapshot, latest, snapshot.scan, true))
        }
        assertNull(refreshResultAfterShareCleanup(snapshot, snapshot, snapshot.scan, false))
        assertNull(
            refreshResultAfterShareCleanup(
                snapshot,
                snapshot,
                snapshot.scan.copy(cached = snapshot.scan.cached.copy(entryId = OTHER_ENTRY_ID)),
                true,
            ),
        )
    }

    @Test
    fun refreshWaitsForExportCompletionInsteadOfCancellingIt() = runBlocking {
        val exporting = result().copy(
            documentActionState = DocumentActionState.Exporting(DocumentActionOutput.Text("OCR", false)),
        )
        val states = MutableStateFlow<ScreenState>(exporting)
        var observed: ScreenState? = null
        val refresh = launch(start = CoroutineStart.UNDISPATCHED) {
            observed = states.first(::shareCleanupRefreshAllowed)
        }

        assertNull(observed)
        assertTrue(refresh.isActive)
        val completed = exporting.copy(documentActionState = DocumentActionState.Completed(DocumentActionOutput.Text("OCR", false)))
        states.value = completed
        refresh.join()

        assertSame(completed, observed)
    }

    @Test
    fun refreshDefersProtectedOperationsAndDropsOcrOnlyWhenCachedContentChanges() {
        val snapshot = result()
        listOf(
            snapshot.copy(outputSaveInProgress = true),
            snapshot.copy(outputChangeInProgress = true),
            snapshot.copy(imageSharePreparationInProgress = true),
            snapshot.copy(safeShareState = SafeShareState.Analyzing),
            snapshot.copy(documentActionState = DocumentActionState.Processing(DocumentAction.FindText)),
        ).forEach { assertFalse(shareCleanupRefreshAllowed(it)) }
        assertFalse(shareCleanupRefreshAllowed(ScreenState.Recent(emptyList(), deletionInProgress = true)))

        val withOcr = snapshot.copy(
            documentActionState = DocumentActionState.Completed(DocumentActionOutput.FindReady, action = DocumentAction.FindText),
        )
        val changed = withOcr.scan.copy(cached = withOcr.scan.cached.copy(pages = listOf(File("replacement.jpg"))))
        assertNull(requireNotNull(refreshResultAfterShareCleanup(withOcr, withOcr, changed, true)).documentActionState)
    }

    @Test
    fun folderTransactionReturnsNullableSuccessAndAcceptedCleanupWarning() {
        assertNull(withPdfGrantChange<UiMessage?> { null })
        val warning = UiMessage(R.string.pdf_tree_release_warning)
        assertSame(warning, withPdfGrantChange { warning })
        val failure = IOException("Grant rejected")
        assertSame(failure, assertThrows(IOException::class.java) { withPdfGrantChange<UiMessage?> { throw failure } })
    }

    @Test
    fun busyFolderTransactionDoesNotRunDestinationChange() {
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = thread {
            withStorageTransaction {
                locked.countDown()
                release.await()
            }
        }
        try {
            assertTrue(locked.await(5, TimeUnit.SECONDS))
            var changed = false
            val failure = assertThrows(IOException::class.java) {
                withPdfGrantChange<UiMessage?> {
                    changed = true
                    null
                }
            }
            assertEquals("PDF destination cleanup is still in progress", failure.message)
            assertFalse(changed)
        } finally {
            release.countDown()
            holder.join(5_000L)
        }
        assertFalse(holder.isAlive)
    }

    @Test
    fun settingsCompletionInvalidatesOverlappingAndCompletedOcrButNotActiveExportOrCodes() {
        val request = DocumentActionRequest(CACHE_ID, ENTRY_ID, 0, DocumentAction.FindText, 1L)
        val processing = result().copy(documentActionState = DocumentActionState.Processing(DocumentAction.FindText))
        val completed = processing.copy(
            documentActionState = DocumentActionState.Completed(DocumentActionOutput.FindReady, action = DocumentAction.FindText),
        )
        listOf(processing, completed).forEach { state ->
            assertTrue(ocrScriptChangeInvalidatesAction(OcrScript.Latin, OcrScript.Chinese, request, state))
            assertFalse(ocrScriptChangeInvalidatesAction(OcrScript.Latin, OcrScript.Latin, request, state))
        }
        val exporting = result().copy(
            documentActionState = DocumentActionState.Exporting(DocumentActionOutput.Text("Exact OCR", false)),
        )
        assertFalse(ocrScriptChangeInvalidatesAction(OcrScript.Latin, OcrScript.Chinese, request, exporting))
        assertFalse(ocrScriptChangeInvalidatesAction(
            OcrScript.Latin, OcrScript.Chinese, request.copy(action = DocumentAction.DetectCodes), processing,
        ))
        assertFalse(ocrScriptChangeInvalidatesAction(OcrScript.Latin, OcrScript.Chinese, null, completed))
    }

    @Test
    fun markScannerPreparationResumesAfterRecreationButLaunchedScannerDoesNotRelaunch() {
        val handle = SavedStateHandle()
        val source = MarkEditorSource(CACHE_ID, ENTRY_ID, 0)
        val first = VisualMarkScanSavedState(handle)
        assertTrue(first.save(source))

        val restoredHandle = recreatedMarkHandle(handle)
        val preparing = VisualMarkScanSavedState(restoredHandle)
        assertEquals(source, preparing.preparingSource())
        assertFalse(preparing.markLaunched(source.copy(pageIndex = 1)))
        assertTrue(preparing.markLaunched(source))

        val launched = VisualMarkScanSavedState(recreatedMarkHandle(restoredHandle))
        assertEquals(source, launched.activeSource())
        assertNull(launched.preparingSource())
        assertFalse(launched.markLaunched(source))
        assertTrue(launched.clear(source))
        assertNull(launched.activeSource())
        assertNull(launched.preparingSource())
        assertFalse(launched.markLaunched(source))
    }

    @Test
    fun legacyMarkScannerSourceNeverCausesUnprovenDuplicateLaunch() {
        val source = MarkEditorSource(CACHE_ID, ENTRY_ID, 0)
        val handle = SavedStateHandle(
            mapOf(VISUAL_MARK_SCAN_SOURCE_KEY to "$CACHE_ID\t$ENTRY_ID\t0"),
        )
        val restored = VisualMarkScanSavedState(handle)
        assertEquals(source, restored.activeSource())
        assertNull(restored.preparingSource())
    }

    @Test
    fun restartedMarkPreparationRejectsCallbacksFromPreviousActivityAttempt() {
        val gate = ScannerLaunchGate()
        val oldAttempt = requireNotNull(gate.begin(processing = false))
        gate.complete()
        val resumedAttempt = requireNotNull(gate.begin(processing = false))

        assertFalse(gate.isPreparing(oldAttempt))
        assertFalse(gate.fail(oldAttempt))
        assertFalse(gate.markLaunched(oldAttempt))
        assertTrue(gate.isPreparing(resumedAttempt))
        assertTrue(gate.markLaunched(resumedAttempt))
        assertNull(gate.begin(processing = false))
    }

    @Test
    fun intentSenderFailureMarkerDoesNotTreatOrdinaryScannerCancellationAsFailure() {
        val action = ActivityResultContracts.StartIntentSenderForResult.ACTION_INTENT_SENDER_REQUEST
        assertTrue(scannerIntentSenderFailed(action, hasSendIntentException = true))
        assertFalse(scannerIntentSenderFailed(action, hasSendIntentException = false))
        assertFalse(scannerIntentSenderFailed(null, hasSendIntentException = false))
        assertFalse(scannerIntentSenderFailed(null, hasSendIntentException = true))
        assertFalse(scannerIntentSenderFailed("scanner.cancelled", hasSendIntentException = true))
    }

    @Test
    fun closingMarkEditorClearsSavedRequestAndAllowsRetryAfterRecreation() {
        for (launched in listOf(false, true)) {
            val handle = SavedStateHandle()
            val state = VisualMarkScanSavedState(handle)
            val source = MarkEditorSource(CACHE_ID, ENTRY_ID, 0)
            assertTrue(state.save(source))
            if (launched) assertTrue(state.markLaunched(source))

            state.clearAll()
            val recreated = VisualMarkScanSavedState(recreatedMarkHandle(handle))
            assertNull(recreated.activeSource())
            assertNull(recreated.preparingSource())
            assertTrue(recreated.save(source))
        }
    }

    @Test
    fun launchedMarkScannerRestoresOrdinaryDurableResultWithoutChangingColdStartPolicy() {
        val handle = markRestoreHandle()
        val state = VisualMarkScanSavedState(handle)
        val source = MarkEditorSource(CACHE_ID, ENTRY_ID, 1)
        val cached = result().scan.cached.copy(pages = listOf(File("page1.jpg"), File("page2.jpg")))
        assertTrue(state.save(source))
        assertTrue(state.markLaunched(source))

        val restored = VisualMarkScanSavedState(recreatedMarkHandle(handle))
        assertTrue(restored.shouldRestoreResult(cached))
        assertNull(restored.preparingSource())
        assertEquals(
            InitialNavigation(RestoredRoute.Result, CACHE_ID),
            initialNavigation("result", CACHE_ID, cached.baseName, restored.shouldRestoreResult(cached)),
        )
        assertEquals(
            InitialNavigation(RestoredRoute.Scanner, null, clearCheckpointBeforeLaunch = true),
            initialNavigation("result", CACHE_ID, cached.baseName),
        )
        assertEquals(source, restored.activeSource())
    }

    @Test
    fun markRestoreRejectsStaleEntryRouteCacheAndSelectedPage() {
        val handle = markRestoreHandle()
        val state = VisualMarkScanSavedState(handle)
        val cached = result().scan.cached.copy(pages = listOf(File("page1.jpg"), File("page2.jpg")))
        assertFalse(state.shouldRestoreResult(cached))
        assertTrue(state.save(MarkEditorSource(CACHE_ID, ENTRY_ID, 1)))
        assertTrue(state.shouldRestoreResult(cached))
        assertFalse(state.shouldRestoreResult(null))
        assertFalse(state.shouldRestoreResult(cached.copy(entryId = OTHER_ENTRY_ID)))
        assertFalse(state.shouldRestoreResult(cached.copy(baseName = "Scan_other")))
        assertFalse(state.shouldRestoreResult(cached.copy(pages = emptyList())))
        assertFalse(state.shouldRestoreResult(cached.copy(pages = listOf(File("page1.jpg")))))
        handle["result_page_index"] = 0
        assertFalse(state.shouldRestoreResult(cached))
        handle["result_page_index"] = 1
        handle["screen_route_cache_id"] = "Scan_other"
        assertFalse(state.shouldRestoreResult(cached))
        handle["screen_route_cache_id"] = CACHE_ID
        handle["screen_route"] = "scanner"
        assertFalse(state.shouldRestoreResult(cached))
        assertEquals(
            InitialNavigation(RestoredRoute.Scanner, null, clearCheckpointBeforeLaunch = true),
            initialNavigation("scanner", CACHE_ID, cached.baseName, state.shouldRestoreResult(cached)),
        )
    }


    @Test(timeout = 10_000L)
    fun cancelledPreviewReturnReleasesResourceBeforeMainHandoff() = queuedTest { io, main ->
        val resource = FakePreviewResource()
        var handedOff = false
        val job = launch(main, start = CoroutineStart.UNDISPATCHED) {
            withPreviewHandoff(
                load = { resource },
                recycle = FakePreviewResource::release,
                ioDispatcher = io,
            ) {
                handedOff = true
                true
            }
        }

        io.runNext()
        assertEquals(0, resource.releases)
        job.cancel()
        main.runNext()
        job.joinQueued(io, main)

        assertTrue(job.isCancelled)
        assertFalse(handedOff)
        assertEquals(1, resource.releases)
    }

    @Test(timeout = 10_000L)
    fun cancelledCompositeReturnReleasesBothUnpublishedResources() = queuedTest { io, main ->
        val before = FakePreviewResource()
        val after = FakePreviewResource()
        var published = false
        val job = launch(main, start = CoroutineStart.UNDISPATCHED) {
            withPreviewHandoff(
                load = { before to after },
                recycle = { output ->
                    output.first.release()
                    output.second.release()
                },
                ioDispatcher = io,
            ) {
                published = true
                true
            }
        }

        io.runNext()
        job.cancel()
        main.runNext()
        job.joinQueued(io, main)

        assertFalse(published)
        assertEquals(1, before.releases)
        assertEquals(1, after.releases)
    }

    @Test(timeout = 10_000L)
    fun previewHandoffRejectsChangedPageAndReleasesLoadedResource() = queuedTest { io, main ->
        val resource = FakePreviewResource()
        val request = ResultPageLoad(CACHE_ID, ENTRY_ID, 0)
        var selectedPage = 0
        var published: FakePreviewResource? = null
        var returned: FakePreviewResource? = resource
        val job = launch(main, start = CoroutineStart.UNDISPATCHED) {
            returned = withPreviewHandoff(
                load = { resource },
                recycle = FakePreviewResource::release,
                ioDispatcher = io,
            ) {
                if (request.isCurrent(CACHE_ID, ENTRY_ID, selectedPage)) {
                    published = it
                    true
                } else {
                    false
                }
            }
        }

        io.runNext()
        selectedPage = 1
        main.runNext()
        job.joinQueued(io, main)

        assertNull(published)
        assertNull(returned)
        assertEquals(1, resource.releases)
    }

    @Test(timeout = 10_000L)
    fun acceptedPreviewTransfersOwnershipAndNullLoadStillCompletesHandoff() = queuedTest { io, main ->
        for (resource in listOf(FakePreviewResource(), null)) {
            var handedOff = false
            var published: FakePreviewResource? = null
            var returned: FakePreviewResource? = null
            val job = launch(main, start = CoroutineStart.UNDISPATCHED) {
                returned = withPreviewHandoff(
                    load = { resource },
                    recycle = FakePreviewResource::release,
                    ioDispatcher = io,
                ) {
                    handedOff = true
                    published = it
                    true
                }
            }

            io.runNext()
            main.runNext()
            job.joinQueued(io, main)

            assertTrue(handedOff)
            assertSame(resource, published)
            assertSame(resource, returned)
            assertEquals(0, resource?.releases ?: 0)
            resource?.release()
        }
    }

    @Test(timeout = 10_000L)
    fun previewLoadRemainsCancellableAndReleasesLateAllocation() = queuedTest { io, main ->
        val resource = FakePreviewResource()
        var handedOff = false
        lateinit var job: Job
        job = launch(main, start = CoroutineStart.UNDISPATCHED) {
            withPreviewHandoff(
                load = {
                    job.cancel()
                    val context = currentCoroutineContext()
                    assertThrows(java.util.concurrent.CancellationException::class.java) {
                        context.ensureActive()
                    }
                    resource
                },
                recycle = FakePreviewResource::release,
                ioDispatcher = io,
            ) {
                handedOff = true
                true
            }
        }

        io.runNext()
        main.runNext()
        job.joinQueued(io, main)

        assertTrue(job.isCancelled)
        assertFalse(handedOff)
        assertEquals(1, resource.releases)
    }

    @Test(timeout = 10_000L)
    fun failedPreviewPublicationReleasesResourceAndPropagatesFailure() = queuedTest { io, main ->
        val resource = FakePreviewResource()
        val failure = IOException("Preview publication failed")
        var caught: IOException? = null
        val job = launch(main, start = CoroutineStart.UNDISPATCHED) {
            try {
                withPreviewHandoff(
                    load = { resource },
                    recycle = FakePreviewResource::release,
                    ioDispatcher = io,
                ) {
                    throw failure
                }
            } catch (exception: IOException) {
                caught = exception
            }
        }

        io.runNext()
        main.runNext()
        job.joinQueued(io, main)

        assertSame(failure, caught)
        assertEquals(1, resource.releases)
    }

    @Test(timeout = 10_000L)
    fun unpublishedCompositeIsReleasedWhenLaterRestoreIsCancelled() = queuedTest { io, main ->
        val resource = FakePreviewResource()
        var published: FakePreviewResult? = null
        val job = launch(main, start = CoroutineStart.UNDISPATCHED) {
            var pending: FakePreviewResource? = null
            try {
                val result = requireNotNull(withPreviewHandoff(
                    load = { FakePreviewResult(resource) },
                    recycle = { it.thumbnail?.release() },
                    ioDispatcher = io,
                ))
                pending = result.thumbnail
                withContext(io) { Unit }
                published = result
                pending = null
            } finally {
                pending?.release()
            }
        }

        io.runNext()
        main.runNext()
        io.runNext()
        assertEquals(0, resource.releases)
        job.cancel()
        main.runNext()
        job.joinQueued(io, main)

        assertTrue(job.isCancelled)
        assertNull(published)
        assertEquals(1, resource.releases)
    }

    @Test(timeout = 10_000L)
    fun publishedCompositeSurvivesLaterExceptionAndRecoveryByBitmapIdentity() = queuedTest { io, main ->
        for (recoverySucceeds in listOf(true, false)) {
            val resource = FakePreviewResource()
            val failure = IOException("Failure after state publication")
            var published: FakePreviewResult? = null
            var displayed: FakePreviewResult? = null
            var caught: IOException? = null
            val job = launch(main, start = CoroutineStart.UNDISPATCHED) {
                var pending: FakePreviewResource? = null
                try {
                    val result = requireNotNull(withPreviewHandoff(
                        load = { FakePreviewResult(resource) },
                        recycle = { it.thumbnail?.release() },
                        ioDispatcher = io,
                    ))
                    pending = result.thumbnail
                    try {
                        try {
                            published = result.copy()
                            displayed = published
                            assertFalse(published === result)
                            throw failure
                        } finally {
                            if (published?.thumbnail === pending) pending = null
                        }
                    } catch (exception: IOException) {
                        caught = exception
                        published = if (recoverySucceeds) result.copy() else null
                    }
                } finally {
                    pending?.release()
                }
            }

            io.runNext()
            main.runNext()
            job.joinQueued(io, main)

            assertSame(failure, caught)
            assertSame(resource, displayed?.thumbnail)
            if (recoverySucceeds) assertSame(resource, published?.thumbnail) else assertNull(published)
            assertEquals(0, resource.releases)
            resource.release()
        }
    }

    private fun markRestoreHandle(): SavedStateHandle =
        SavedStateHandle(mapOf(
            "screen_route" to "result",
            "screen_route_cache_id" to CACHE_ID,
            "result_page_index" to 1,
        ))

    private fun recreatedMarkHandle(handle: SavedStateHandle): SavedStateHandle =
        SavedStateHandle(mapOf(
            VISUAL_MARK_SCAN_SOURCE_KEY to handle.get<String>(VISUAL_MARK_SCAN_SOURCE_KEY),
            VISUAL_MARK_SCAN_LAUNCHED_KEY to handle.get<Boolean>(VISUAL_MARK_SCAN_LAUNCHED_KEY),
            "screen_route" to handle.get<String>("screen_route"),
            "screen_route_cache_id" to handle.get<String>("screen_route_cache_id"),
            "result_page_index" to handle.get<Int>("result_page_index"),
        ))

    private fun result(): ScreenState.Result =
        ScreenState.Result(
            SavedScan(
                CachedScan(CACHE_ID, listOf(File("page.jpg")), File("scan.pdf"), entryId = ENTRY_ID),
                galleryPages = emptyList(),
                savedPdf = null,
                outputMetadataValid = true,
            ),
            thumbnail = null,
        )

    private fun preferences(): SharedPreferences {
        val values = mutableMapOf<String, String?>()
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
            when (method.name) {
                "putString" -> editor.also { values[args!![0] as String] = args[1] as String? }
                "remove" -> editor.also { values.remove(args!![0] as String) }
                "commit" -> true
                "apply" -> null
                else -> error(method.name)
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "edit" -> editor
                "contains" -> values.containsKey(args!![0] as String)
                "getString" -> values[args!![0] as String] ?: args[1]
                "getBoolean", "getInt", "getLong" -> args!![1]
                else -> error(method.name)
            }
        } as SharedPreferences
    }

    private data class FakePreviewResult(val thumbnail: FakePreviewResource?)

    private class FakePreviewResource {
        var releases = 0
            private set

        fun release() {
            releases += 1
        }
    }

    private fun queuedTest(
        block: suspend CoroutineScope.(QueuedDispatcher, QueuedDispatcher) -> Unit,
    ) = runBlocking {
        val io = QueuedDispatcher()
        val main = QueuedDispatcher()
        try {
            block(io, main)
        } finally {
            val children = requireNotNull(coroutineContext[Job]).children.toList()
            children.forEach { it.cancel() }
            // Assertion failures must not strand continuations on paused dispatchers.
            io.resumeOn(coroutineContext)
            main.resumeOn(coroutineContext)
            withContext(NonCancellable) {
                withTimeout(5_000L) { children.forEach { it.join() } }
            }
        }
    }

    private suspend fun Job.joinQueued(io: QueuedDispatcher, main: QueuedDispatcher) {
        repeat(100) {
            if (isCompleted) {
                join()
                return
            }
            io.runNextIfPending()
            main.runNextIfPending()
            yield()
        }
        check(isCompleted) { "Coroutine did not finish after 100 queued dispatcher turns" }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        private var cleanupDispatcher: CoroutineDispatcher? = null
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            val dispatcher = cleanupDispatcher
            if (dispatcher == null) tasks.addLast(block) else dispatcher.dispatch(context, block)
        }
        fun runNext() = tasks.removeFirst().run()
        fun runNextIfPending() = tasks.pollFirst()?.run()
        fun resumeOn(context: CoroutineContext) {
            val dispatcher = requireNotNull(context[ContinuationInterceptor] as? CoroutineDispatcher)
            cleanupDispatcher = dispatcher
            while (tasks.isNotEmpty()) dispatcher.dispatch(context, tasks.removeFirst())
        }
    }

    private companion object {
        const val CACHE_ID = "Scan_lifecycle"
        const val ENTRY_ID = "123e4567-e89b-12d3-a456-426614174000"
        const val OTHER_ENTRY_ID = "123e4567-e89b-12d3-a456-426614174001"
    }
}
