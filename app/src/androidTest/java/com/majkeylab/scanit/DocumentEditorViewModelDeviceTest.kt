package com.majkeylab.scanit

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class DocumentEditorViewModelDeviceTest {
    @Test(timeout = 30_000)
    fun closingBeforeDraftLoadingFinishesCannotDiscardUnseenAnnotations() = runBlocking {
        withFixture { fixture ->
            val viewModel = fixture.restore(fixture.first)
            val expected = draft(fixture.first)
            withContext(Dispatchers.IO) { fixture.drafts.save(expected) }
            act(viewModel) {
                viewModel.openDocumentEditor()
                viewModel.closeDocumentEditor()
            }
            assertEquals(expected.annotations, editor(viewModel).pageAnnotations)
            assertEquals(expected, fixture.readDraft())
        }
    }

    @Test(timeout = 30_000)
    fun dirtyAnnotationsPersistAndReopenAfterViewModelStoreIsCleared() = runBlocking {
        withFixture { fixture ->
            val viewModel = fixture.restore(fixture.first)
            act(viewModel) { viewModel.openDocumentEditor() }
            val expected = draft(fixture.first)

            act(viewModel) { viewModel.updateDocumentAnnotations(expected.annotations) }
            assertEquals(expected.annotations, editor(viewModel).pageAnnotations)
            assertEquals(expected, fixture.readDraft())

            val reopened = fixture.restore(fixture.first)
            assertEquals(expected.source, editor(reopened).source)
            assertEquals(expected.annotations, editor(reopened).pageAnnotations)
            assertEquals(expected, fixture.readDraft())
        }
    }

    @Test(timeout = 30_000)
    fun openingAndClosingAnotherDocumentKeepsTheFirstDocumentsDraft() = runBlocking {
        withFixture { fixture ->
            val first = fixture.restore(fixture.first)
            act(first) { first.openDocumentEditor() }
            val expected = draft(fixture.first)
            act(first) { first.updateDocumentAnnotations(expected.annotations) }
            assertEquals(expected, fixture.readDraft())

            val second = fixture.restore(fixture.second)
            assertNull(result(second).visualMarkEditor)
            act(second) { second.openDocumentEditor() }
            assertTrue(checkNotNull(editor(second).pageAnnotations).isEmpty())
            assertEquals(expected, fixture.readDraft())
            act(second) { second.closeDocumentEditor() }
            assertNull(result(second).visualMarkEditor)
            assertEquals(expected, fixture.readDraft())

            val reopened = fixture.restore(fixture.first)
            assertEquals(expected.source, editor(reopened).source)
            assertEquals(expected.annotations, editor(reopened).pageAnnotations)
        }
    }

    @Test(timeout = 30_000)
    fun secondPageDraftRestoresItsPageAndThumbnailWithoutSavedPageSelection() = runBlocking {
        withFixture { fixture ->
            val viewModel = fixture.restore(fixture.first, selectedPageIndex = 1)
            act(viewModel) { viewModel.openDocumentEditor() }
            val expected = draft(fixture.first, pageIndex = 1)
            act(viewModel) { viewModel.updateDocumentAnnotations(expected.annotations) }
            assertEquals(expected, fixture.readDraft())

            val reopened = fixture.restore(fixture.first, selectedPageIndex = null)
            assertSecondPageDraftRestored(reopened, expected)
        }
    }

    @Test(timeout = 30_000)
    fun retryingMissingPreviewPreservesUnsavedAnnotationsAndOriginalCache() = runBlocking {
        withFixture { fixture ->
            val parent = fixture.first
            val viewModel = fixture.restore(parent, selectedPageIndex = 1)
            act(viewModel) { viewModel.openDocumentEditor() }
            val expected = draft(parent, pageIndex = 1)
            act(viewModel) { viewModel.updateDocumentAnnotations(expected.annotations) }
            assertEquals(expected, fixture.readDraft())
            val originals = withContext(Dispatchers.IO) {
                (parent.sourcePages + parent.pages + parent.pdf).associateWith(File::readBytes)
            }
            val previewPage = parent.pages[1]
            val reopened = try {
                withContext(Dispatchers.IO) {
                    previewPage.writeBytes("Unreadable preview fixture".toByteArray(Charsets.UTF_8))
                    assertNull(fixture.storage.loadThumbnail(previewPage))
                }
                fixture.restore(parent, selectedPageIndex = 1)
            } finally {
                withContext(Dispatchers.IO) { previewPage.writeBytes(originals.getValue(previewPage)) }
            }
            val missingPreview = result(reopened)
            val pendingEditor = editor(reopened)
            assertEquals(parent.baseName, missingPreview.scan.cached.baseName)
            assertEquals(parent.entryId, missingPreview.scan.cached.entryId)
            assertEquals(1, missingPreview.selectedPageIndex)
            assertNull(missingPreview.thumbnail)
            assertFalse(missingPreview.pagePreviewLoading)
            assertEquals(expected.source, pendingEditor.source)
            assertEquals(expected.annotations, pendingEditor.pageAnnotations)
            assertNull(pendingEditor.pageAspectRatio)
            assertEquals(expected, fixture.readDraft())
            val filesBefore = withContext(Dispatchers.IO) {
                fixture.application.cacheDir.walkTopDown().filter(File::isFile).toSet()
            }

            act(reopened) { reopened.switchDocumentEditorPage(missingPreview.selectedPageIndex) }

            val restored = result(reopened)
            val restoredEditor = editor(reopened)
            assertEquals(missingPreview.scan.cached, restored.scan.cached)
            assertEquals(pendingEditor.copy(pageAspectRatio = restoredEditor.pageAspectRatio), restoredEditor)
            assertEquals(0.8f, checkNotNull(restoredEditor.pageAspectRatio), 0.0001f)
            assertFalse(restored.pagePreviewLoading)
            assertSecondPageDraftRestored(reopened, expected)
            assertEquals(expected, fixture.readDraft())
            assertEquals(parent.baseName, fixture.settings.activeResultCacheId())
            withContext(Dispatchers.IO) {
                assertEquals("Retry must not create derived files", filesBefore,
                    fixture.application.cacheDir.walkTopDown().filter(File::isFile).toSet())
                originals.forEach { (file, bytes) -> assertArrayEquals(file.name, bytes, file.readBytes()) }
                assertEquals(parent.entryId, checkNotNull(fixture.storage.openCachedScan(parent.baseName)).entryId)
            }
        }
    }

    @Test(timeout = 30_000)
    fun staleOtherDocumentMarkRequestCannotHideSecondPageDraft() = runBlocking {
        withFixture { fixture ->
            val viewModel = fixture.restore(fixture.first, selectedPageIndex = 1)
            act(viewModel) { viewModel.openDocumentEditor() }
            val expected = draft(fixture.first, pageIndex = 1)
            act(viewModel) { viewModel.updateDocumentAnnotations(expected.annotations) }
            assertEquals(expected, fixture.readDraft())

            lateinit var pendingMark: VisualMarkScanSavedState
            val reopened = fixture.restore(fixture.first, selectedPageIndex = null) { handle ->
                val staleSource = MarkEditorSource(fixture.second.baseName, checkNotNull(fixture.second.entryId), 0)
                pendingMark = VisualMarkScanSavedState(handle)
                assertTrue(pendingMark.save(staleSource))
                assertTrue(pendingMark.markLaunched(staleSource))
                assertEquals(staleSource, pendingMark.activeSource())
            }

            assertSecondPageDraftRestored(reopened, expected)
            assertEquals(expected, fixture.readDraft())
            withContext(Dispatchers.Main) { assertNull(pendingMark.activeSource()) }
        }
    }

    @Test(timeout = 30_000)
    fun missingOcrPayloadCannotHideSecondPageDraft() = runBlocking {
        withFixture { fixture ->
            val viewModel = fixture.restore(fixture.first, selectedPageIndex = 1)
            act(viewModel) { viewModel.openDocumentEditor() }
            val expected = draft(fixture.first, pageIndex = 1)
            act(viewModel) { viewModel.updateDocumentAnnotations(expected.annotations) }
            assertEquals(expected, fixture.readDraft())

            lateinit var pendingText: DocumentTextExportSavedState
            val reopened = fixture.restore(fixture.first, selectedPageIndex = null) { handle ->
                val request = DocumentActionRequest(
                    fixture.first.baseName, checkNotNull(fixture.first.entryId), 0, DocumentAction.ExtractText, 1L,
                )
                val textRoot = File(fixture.application.cacheDir, "text-export")
                pendingText = DocumentTextExportSavedState(handle, textRoot)
                assertTrue(pendingText.saveLaunch(request, DocumentActionOutput.Text("OCR fixture payload", false)))
                assertEquals(request, checkNotNull(pendingText.pendingExport()).request)
                withContext(Dispatchers.IO) {
                    val payload = checkNotNull(textRoot.listFiles()).single()
                    check(payload.canonicalFile.parentFile == textRoot.canonicalFile)
                    assertTrue(payload.isFile)
                    assertTrue(payload.delete())
                }
                assertEquals(request, pendingText.activeRequest())
                assertNull(pendingText.pendingExport())
            }

            assertSecondPageDraftRestored(reopened, expected)
            assertNull(result(reopened).documentActionState)
            assertEquals(expected, fixture.readDraft())
            withContext(Dispatchers.Main) { assertNull(pendingText.activeRequest()) }
        }
    }

    @Test(timeout = 60_000)
    fun applyingCreatesACopyAndClearsOnlyItsMatchingDraft() = runBlocking {
        for (retainOtherDraft in listOf(false, true)) {
            withFixture { fixture ->
                val parent = fixture.first
                val originals = (parent.sourcePages + parent.pages + parent.pdf).associateWith(File::readBytes)
                val viewModel = fixture.restore(parent)
                act(viewModel) { viewModel.openDocumentEditor() }
                val expected = draft(parent)
                act(viewModel) { viewModel.updateDocumentAnnotations(expected.annotations) }
                assertEquals(expected, fixture.readDraft())
                val retained = if (retainOtherDraft) draft(fixture.second) else null
                if (retained != null) {
                    withContext(Dispatchers.IO) { fixture.drafts.save(retained) }
                }

                act(viewModel) { viewModel.applyVisualMark() }

                val state = result(viewModel)
                val edited = state.scan.cached
                assertNotEquals(parent.baseName, edited.baseName)
                assertNotEquals(parent.entryId, edited.entryId)
                assertEquals(parent.baseName, edited.parentCacheId)
                assertEquals(parent.entryId, edited.parentEntryId)
                assertEquals(parent.pages.size, edited.pages.size)
                assertTrue(edited.pdf.isFile)
                assertFalse(fixture.storage.isProvisionalCacheEntry(edited))
                assertNull(state.visualMarkEditor)
                assertEquals(edited.baseName, fixture.settings.activeResultCacheId())
                assertEquals(retained, fixture.readDraft())
                assertArrayEquals(parent.pages[1].readBytes(), edited.pages[1].readBytes())
                originals.forEach { (file, bytes) -> assertArrayEquals(file.name, bytes, file.readBytes()) }
                assertEquals(parent.entryId, checkNotNull(fixture.storage.openCachedScan(parent.baseName)).entryId)
                val thumbnail = checkNotNull(state.thumbnail)
                val center = thumbnail.getPixel(thumbnail.width / 2, thumbnail.height / 2)
                assertTrue("Applied copy must contain the red ink", Color.red(center) > Color.green(center) + 100)
            }
        }
    }

    private fun draft(cached: CachedScan, pageIndex: Int = 0) = AnnotationDraft(
        MarkEditorSource(cached.baseName, checkNotNull(cached.entryId), pageIndex),
        listOf(
            PageAnnotation.Ink(listOf(AnnotationPoint(0.2f, 0.5f), AnnotationPoint(0.8f, 0.5f)), Color.RED, 0.04f),
            PageAnnotation.Text("Žluťoučký kůň", 0.1f, 0.15f, 0.06f, Color.BLACK),
        ),
    )

    private fun result(viewModel: ScanViewModel): ScreenState.Result =
        checkNotNull(viewModel.state.value as? ScreenState.Result)

    private fun editor(viewModel: ScanViewModel): VisualMarkEditorState =
        checkNotNull(result(viewModel).visualMarkEditor).also { assertFalse("Editor is still busy", it.busy) }

    private fun assertSecondPageDraftRestored(viewModel: ScanViewModel, expected: AnnotationDraft) {
        val state = result(viewModel)
        assertEquals(1, state.selectedPageIndex)
        assertEquals(expected.source, editor(viewModel).source)
        assertEquals(expected.annotations, editor(viewModel).pageAnnotations)
        val thumbnail = checkNotNull(state.thumbnail)
        val center = thumbnail.getPixel(thumbnail.width / 2, thumbnail.height / 2)
        assertTrue("Restored thumbnail must show the blue second page", Color.blue(center) > Color.red(center) + 150)
        assertTrue("Restored thumbnail must not show the white first page", Color.blue(center) > Color.green(center) + 150)
    }

    private suspend fun act(viewModel: ScanViewModel, action: () -> Unit) {
        val scope = checkNotNull(viewModel.viewModelScope.coroutineContext[Job])
        val existing = withContext(Dispatchers.Main) { scope.children.toSet().also { action() } }
        // Await spawned saves and apply cleanup without joining the ViewModel's permanent collectors.
        withTimeout(10_000) {
            while (true) {
                val pending = withContext(Dispatchers.Main) { scope.children.filter { it !in existing }.toList() }
                if (pending.isEmpty()) break
                pending.forEach { it.join() }
            }
        }
    }

    private class Fixture(
        val application: Application,
        val storage: ScanStorage,
        val settings: SettingsStore,
        val first: CachedScan,
        val second: CachedScan,
    ) {
        val drafts = AnnotationDraftStore(File(application.filesDir.canonicalFile, "document-editor"))
        private var store: ViewModelStore? = null
        private var scopeJob: Job? = null

        suspend fun restore(
            cached: CachedScan,
            selectedPageIndex: Int? = 0,
            prepare: suspend (SavedStateHandle) -> Unit = {},
        ): ScanViewModel {
            clear()
            withContext(Dispatchers.IO) {
                assertEquals(AuthorityMutationResult.Applied,
                    settings.saveActiveResult(cached.baseName, settings.authoritySnapshot().owner))
            }
            val handle = SavedStateHandle(mapOf(
                "screen_route" to "result",
                "screen_route_cache_id" to cached.baseName,
                "safe_share_active" to true,
            ))
            if (selectedPageIndex != null) handle["result_page_index"] = selectedPageIndex
            else assertNull(handle.get<Int>("result_page_index"))
            withContext(Dispatchers.Main) { prepare(handle) }
            val viewModel = withContext(Dispatchers.Main) {
                val currentStore = ViewModelStore().also { store = it }
                val factory = object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        checkNotNull(modelClass.cast(ScanViewModel(application, handle).also {
                            scopeJob = it.viewModelScope.coroutineContext[Job]
                        }))
                }
                ViewModelProvider(currentStore, factory)[ScanViewModel::class.java]
            }
            val state = withTimeout(5_000) {
                viewModel.state.first { it !is ScreenState.Processing || it.canNavigateBack }
            }
            assertTrue("Expected a restored Result, got $state", state is ScreenState.Result)
            withContext(Dispatchers.Main) { }
            return viewModel
        }

        suspend fun readDraft(): AnnotationDraft? = withContext(Dispatchers.IO) { drafts.load() }

        suspend fun clear() {
            withContext(Dispatchers.Main) { store?.clear(); store = null }
            withTimeout(10_000) { scopeJob?.join() }
            scopeJob = null
        }
    }

    private suspend fun withFixture(test: suspend (Fixture) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        check(base.packageName == "com.majkeylab.scanit.internal")
        assumeTrue("Requires an internal app without persisted grants", base.contentResolver.persistedUriPermissions.isEmpty())
        val root = Files.createTempDirectory(base.filesDir.toPath(), "document-editor-vm-").toFile()
        var fixture: Fixture? = null
        try {
            val application = object : Application() {
                init { attachBaseContext(base) }
                override fun getApplicationContext(): Context = this
                override fun getCacheDir(): File = root
                override fun getFilesDir(): File = root
                override fun getNoBackupFilesDir(): File = root
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                    base.getSharedPreferences("${root.name}_$name", mode)
            }
            val pages = listOf(Color.WHITE, Color.BLUE).mapIndexed { index, color ->
                File(root, "source-$index.jpg").also { file ->
                    val bitmap = Bitmap.createBitmap(160, 200, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(color)
                        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
            val storage = ScanStorage(application)
            val settings = SettingsStore(application)
            settings.save(settings.load().copy(savePdf = false, saveImages = false))
            val first = storage.activateProvisionalCacheEntry(
                storage.cacheScan(pages.map(Uri::fromFile), googleScannerAppearanceSettings(), PdfSizeTarget.Original).cached,
            )
            val second = storage.activateProvisionalCacheEntry(
                storage.cacheScan(listOf(Uri.fromFile(pages[0])), googleScannerAppearanceSettings(), PdfSizeTarget.Original).cached,
            )
            val created = Fixture(application, storage, settings, first, second)
            fixture = created
            test(created)
        } finally {
            fixture?.clear()
            check(root.canonicalFile.parentFile == base.filesDir.canonicalFile)
            check(base.deleteSharedPreferences("${root.name}_settings"))
            assertTrue(deleteTreeWithoutFollowingLinks(root))
        }
    }
}
