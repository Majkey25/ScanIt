package com.majkeylab.scanit

import androidx.lifecycle.SavedStateHandle
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExternalActivityLifecycleTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun launchedImageShareRemainsReadableAfterFiveMinutesAndUnlaunchedExpiry() {
        val root = temporaryFolder.newFolder("share")
        val bytes = byteArrayOf(1, 2, 3, 4)
        val output = preparedImage(bytes)
        val now = System.currentTimeMillis() + 10_000L
        val first =
            prepareImageShareCopies(
                root,
                listOf(output),
                open = { ByteArrayInputStream(bytes) },
                operationId = "123e4567-e89b-12d3-a456-426614174001",
                nowMillis = now,
            )

        assertTrue(markPreparedImageShareLaunched(first, now))
        val duringGrace =
            prepareImageShareCopies(
                root,
                listOf(output),
                open = { ByteArrayInputStream(bytes) },
                operationId = "123e4567-e89b-12d3-a456-426614174002",
                nowMillis = now + 5L * 60L * 1000L + 1L,
            )
        assertTrue(first.directory.isDirectory)
        assertTrue(cleanupPreparedImageShare(duringGrace))

        val afterGrace =
            prepareImageShareCopies(
                root,
                listOf(output),
                open = { ByteArrayInputStream(bytes) },
                operationId = "123e4567-e89b-12d3-a456-426614174003",
                nowMillis = now + PREPARED_IMAGE_SHARE_TTL_MS + 1L,
            )
        assertArrayEquals(bytes, first.files.single().readBytes())
        assertTrue(cleanupPreparedImageShare(afterGrace))
    }

    @Test
    fun textExportPayloadAndSelectionSurviveSavedStateRecreation() = runBlocking {
        val handle = SavedStateHandle()
        val root = temporaryFolder.newFolder("text-export")
        val request = documentRequest()
        val output = DocumentActionOutput.Text("Exact OCR\ntext", truncated = false)
        val first = DocumentTextExportSavedState(handle, root)

        assertTrue(first.saveLaunch(request, output))
        val restoredHandle = recreatedHandle(handle)
        val recreated = DocumentTextExportSavedState(restoredHandle, root)
        assertEquals(PendingDocumentTextExport(request, output), recreated.pendingExport())
        val selection =
            DocumentTextExportSelection(
                request,
                "content://documents/document/output.txt",
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        assertTrue(recreated.saveSelection(selection))

        val afterSelection = DocumentTextExportSavedState(recreatedHandle(restoredHandle), root)
        assertEquals(selection, afterSelection.pendingSelection())
        assertEquals(output, afterSelection.pendingExport()?.output)
        assertTrue(afterSelection.clear(request))
        assertNull(afterSelection.pendingExport())
    }

    @Test
    fun textExportPublishesSavedStateOnlyAfterReturningFromIo() = runBlocking {
        val handle = SavedStateHandle()
        val root = temporaryFolder.newFolder("text-main")
        val io = QueuedDispatcher()
        val state = DocumentTextExportSavedState(handle, root, io)
        val output = DocumentActionOutput.Text("OCR", false)
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            assertTrue(state.saveLaunch(documentRequest(), output))
        }

        assertTrue(handle.keys().isEmpty())
        io.runNext()
        assertTrue(root.listFiles().orEmpty().single().isFile)
        assertTrue(handle.keys().isEmpty())
        job.join()
        assertEquals(documentRequest(), state.activeRequest())
    }

    @Test
    fun cancelledStagingCannotDeleteOrPublishOverNewExport() = runBlocking {
        val handle = SavedStateHandle()
        val root = temporaryFolder.newFolder("text-race")
        val io = QueuedDispatcher()
        val state = DocumentTextExportSavedState(handle, root, io)
        val old = launch(start = CoroutineStart.UNDISPATCHED) {
            state.saveLaunch(documentRequest(), DocumentActionOutput.Text("old", false))
        }
        io.runNext() // File is written, but publication has not resumed.
        old.cancel()
        state.clearAll()
        val newRequest = documentRequest().copy(generation = 8L)
        val latest = launch(start = CoroutineStart.UNDISPATCHED) {
            assertTrue(state.saveLaunch(newRequest, DocumentActionOutput.Text("new", false)))
        }
        io.runNext()
        yield() // Cancelled attempt schedules its exact-file cleanup.
        latest.join()
        io.runNext()
        old.join()

        assertEquals(newRequest, state.activeRequest())
        assertEquals("new", root.listFiles().orEmpty().single().readText())
        assertFalse(state.clear(documentRequest()))
        assertEquals(newRequest, state.activeRequest())
    }

    @Test
    fun invalidatedStagingCannotRecreatePendingExport() = runBlocking {
        val handle = SavedStateHandle()
        val root = temporaryFolder.newFolder("text-invalidated")
        val io = QueuedDispatcher()
        val state = DocumentTextExportSavedState(handle, root, io)
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            assertFalse(state.saveLaunch(documentRequest(), DocumentActionOutput.Text("old", false)))
        }
        state.clearAll()
        io.runNext() // Old writer creates its file after invalidation.
        yield()
        io.runNext()
        job.join()

        assertNull(state.activeRequest())
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun textResultWaitsForRestoreAndRejectsDuplicateOrStaleCallbacks() = runBlocking {
        val root = temporaryFolder.newFolder("text-callback")
        val handle = SavedStateHandle()
        val request = documentRequest()
        assertTrue(DocumentTextExportSavedState(handle, root).saveLaunch(request, DocumentActionOutput.Text("OCR", false)))
        val state = DocumentTextExportSavedState(recreatedHandle(handle), root)
        val selection = DocumentTextExportSelection(request, "content://documents/output.txt", 2)
        var calls = 0

        assertEquals(DocumentTextExportDisposition.Accepted, state.submitResult(request) {
            calls++
            assertTrue(state.saveSelection(selection))
            DocumentTextExportDisposition.Accepted
        })
        assertEquals(DocumentTextExportDisposition.DefiniteStale, state.submitResult(request) {
            error("Duplicate callback must not run")
        })
        assertEquals(0, calls)
        assertNull(state.pendingSelection())
        assertEquals(request, state.pendingExport()?.request)
        state.completeRestore(deliver = true)
        assertEquals(1, calls)
        assertEquals(selection, state.pendingSelection())
        assertTrue(state.clear(request))
        assertEquals(DocumentTextExportDisposition.DefiniteStale, state.submitResult(request) {
            error("Stale callback must not run")
        })
    }

    @Test
    fun textCancellationWaitsForRestoreAndFailedRestoreDropsResult() = runBlocking {
        for (deliver in listOf(true, false)) {
            val state = DocumentTextExportSavedState(SavedStateHandle(), temporaryFolder.newFolder())
            val request = documentRequest()
            assertTrue(state.saveLaunch(request, DocumentActionOutput.Text("OCR", false)))
            var calls = 0
            state.submitResult(request) {
                calls++
                assertTrue(state.clear(request))
                DocumentTextExportDisposition.Accepted
            }
            assertEquals(request, state.activeRequest())
            state.completeRestore(deliver)
            assertEquals(if (deliver) 1 else 0, calls)
            assertEquals(if (deliver) null else request, state.activeRequest())
        }
    }

    @Test
    fun visualMarkScanSourceSurvivesSavedStateRecreationAndRejectsMalformedState() {
        val handle = SavedStateHandle()
        val source = MarkEditorSource(CACHE_ID, ENTRY_ID, 2)
        val first = VisualMarkScanSavedState(handle)

        assertTrue(first.save(source))
        val recreated = VisualMarkScanSavedState(recreatedHandle(handle))
        assertEquals(source, recreated.activeSource())
        assertTrue(recreated.clear(source))
        assertNull(recreated.activeSource())

        val malformed = SavedStateHandle(mapOf(VISUAL_MARK_SCAN_SOURCE_KEY to "../bad\t$ENTRY_ID\t2"))
        assertNull(VisualMarkScanSavedState(malformed).activeSource())
    }

    @Test
    fun shareChooserDoesNotRegisterTargetSelectionCleanup() {
        val root = File("..").canonicalFile
        val share = root.resolve("app/src/main/java/com/majkeylab/scanit/ScanShare.kt").readText()
        val activity = root.resolve("app/src/main/java/com/majkeylab/scanit/MainActivity.kt").readText()

        assertFalse(share.contains("shareResultPendingIntent"))
        assertFalse(share.substringAfter("internal fun Activity.launchShareChooser").substringBefore("private fun shareUri").contains("cleanupRequest"))
        assertEquals(3, Regex("launchShareChooser\\([^,\\n]+\\)").findAll(activity).count())
    }

    private fun recreatedHandle(handle: SavedStateHandle): SavedStateHandle =
        SavedStateHandle(handle.keys().associateWith { key -> handle.get<Any?>(key) })

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.addLast(block)
        }

        fun runNext() = tasks.removeFirst().run()
    }

    private fun documentRequest() =
        DocumentActionRequest(CACHE_ID, ENTRY_ID, 0, DocumentAction.ExtractText, 7L)

    private fun preparedImage(bytes: ByteArray) =
        PreparedImageSource(
            page = 1,
            uri = "content://provider/image/1",
            mimeType = "image/jpeg",
            byteLength = bytes.size.toLong(),
            sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        )

    private companion object {
        const val CACHE_ID = "Scan_lifecycle"
        const val ENTRY_ID = "123e4567-e89b-12d3-a456-426614174000"
    }
}
