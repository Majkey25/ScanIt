package com.majkeylab.scanit

import androidx.lifecycle.SavedStateHandle
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OutputChangeOrchestrationLifecycleTest {
    @Test
    fun outputTreeStateBuffersCallbackBeforeResultRestoreAndSurvivesRecreation() {
        val handle = SavedStateHandle()
        val request = OutputChangeRequest(CACHE_ID, ENTRY_ID, OutputChangeKind.PdfLocation, 8L)
        val selection = OutputTreeSelection(request, "content://provider/tree/root", 3)
        val first = OutputTreeSavedState(handle)

        first.saveLaunch(request)
        assertTrue(first.claimLaunch(request))
        assertTrue(first.saveSelection(selection))

        val restoredHandle =
            SavedStateHandle(
                handle.keys().associateWith { key -> handle.get<Any?>(key) },
            )
        val recreatedBeforeResultRestore = OutputTreeSavedState(restoredHandle)
        assertEquals(request, recreatedBeforeResultRestore.activeRequest())
        assertNull(recreatedBeforeResultRestore.pendingLaunch())
        assertEquals(selection, recreatedBeforeResultRestore.pendingSelection())
        assertEquals(selection, recreatedBeforeResultRestore.consumeSelection(request))
        assertNull(OutputTreeSavedState(restoredHandle).activeRequest())
    }

    @Test
    fun scannerCallbacksWaitForBootstrapAndRunExactlyOnce() {
        listOf("ok", "cancel", "error").forEach { callback ->
            val gate = ScannerCallbackGate()
            val events = mutableListOf<String>()

            assertTrue(gate.submit { events += callback })
            assertFalse(gate.submit { events += "duplicate" })
            assertTrue(events.isEmpty())

            gate.complete(deliver = true)

            assertEquals(listOf(callback), events)
            assertTrue(gate.submit { events += "after" })
            assertEquals(listOf(callback, "after"), events)
        }
    }

    @Test
    fun failedBootstrapDropsQueuedScannerCallback() {
        val gate = ScannerCallbackGate()
        var calls = 0

        assertTrue(gate.submit { calls++ })
        gate.complete(deliver = false)

        assertEquals(0, calls)
        assertFalse(gate.submit { calls++ })
        assertEquals(0, calls)
    }

    @Test
    fun outputTreeAttemptValidatesBeforeGrantAndAlwaysReconciles() = runBlocking {
        val validationFailureEvents = mutableListOf<String>()
        try {
            runOutputTreeAttempt(
                validate = {
                    validationFailureEvents += "validate"
                    throw IOException("validate")
                },
                acquireGrant = { validationFailureEvents += "grant" },
                replace = {
                    validationFailureEvents += "replace"
                    1
                },
                reconcile = { validationFailureEvents += "reconcile" },
            )
            fail("Validation failure must escape")
        } catch (_: IOException) {
            assertEquals(listOf("validate", "reconcile"), validationFailureEvents)
        }

        val grantFailureEvents = mutableListOf<String>()
        try {
            runOutputTreeAttempt(
                validate = { grantFailureEvents += "validate" },
                acquireGrant = {
                    grantFailureEvents += "grant"
                    throw IOException("grant")
                },
                replace = {
                    grantFailureEvents += "replace"
                    1
                },
                reconcile = { grantFailureEvents += "reconcile" },
            )
            fail("Grant failure must escape")
        } catch (_: IOException) {
            assertEquals(listOf("validate", "grant", "reconcile"), grantFailureEvents)
        }

        val replacementFailureEvents = mutableListOf<String>()
        try {
            runOutputTreeAttempt(
                validate = { replacementFailureEvents += "validate" },
                acquireGrant = { replacementFailureEvents += "grant" },
                replace = {
                    replacementFailureEvents += "replace"
                    throw IOException("replace")
                },
                reconcile = { replacementFailureEvents += "reconcile" },
            )
            fail("Replacement failure must escape")
        } catch (_: IOException) {
            assertEquals(
                listOf("validate", "grant", "replace", "reconcile"),
                replacementFailureEvents,
            )
        }

        val successEvents = mutableListOf<String>()
        val result =
            runOutputTreeAttempt(
                validate = { successEvents += "validate" },
                acquireGrant = { successEvents += "grant" },
                replace = {
                    successEvents += "replace"
                    7
                },
                reconcile = { successEvents += "reconcile" },
            )
        assertEquals(7, result)
        assertEquals(listOf("validate", "grant", "replace", "reconcile"), successEvents)
    }

    @Test
    fun preparedImageShareCannotClaimAfterRouteMutation() {
        val gate = RecentActionGate()
        val action = gate.begin(CACHE_ID, ENTRY_ID)

        assertFalse(gate.claim(action, listOf(CACHE_ID to OTHER_ENTRY_ID)))
        gate.invalidate()

        assertFalse(gate.claim(action, listOf(CACHE_ID to ENTRY_ID)))
    }

    @Test
    fun replacementFailurePublishesOnlyExactRecoveredOutputAndKeepsAcknowledgement() {
        val request = OutputChangeRequest(
            CACHE_ID,
            ENTRY_ID,
            OutputChangeKind.ImageFormat(ImageExportFormat.Png),
            4L,
        )
        val acknowledgement = UnknownOutputCreateAcknowledgement(
            CACHE_ID,
            ENTRY_ID,
            OPERATION_ID,
        )
        val current = savedScan(CACHE_ID, ENTRY_ID, acknowledgement)
        val exactRecovered = savedScan(CACHE_ID, ENTRY_ID, acknowledgement)
        val staleRecovered = savedScan(CACHE_ID, OTHER_ENTRY_ID, acknowledgement)

        assertSame(exactRecovered, replacementFailurePublication(current, exactRecovered, request))
        assertSame(current, replacementFailurePublication(current, staleRecovered, request))
    }

    @Test
    fun unknownOutputAcknowledgementRequiresExactGenerationOperationAndOutput() {
        val acknowledgement = UnknownOutputCreateAcknowledgement(CACHE_ID, ENTRY_ID, OPERATION_ID)
        val request = OutputChangeRequest(
            CACHE_ID,
            ENTRY_ID,
            OutputChangeKind.UnknownOutputCreate(OPERATION_ID),
            6L,
        )
        val scan = savedScan(CACHE_ID, ENTRY_ID, acknowledgement)
        val gate = OutputChangeGate(initialGeneration = 6L, initialCurrent = request)

        assertTrue(unknownOutputAcknowledgementMatches(scan, acknowledgement, request))
        assertTrue(gate.isCurrent(request, CACHE_ID, ENTRY_ID))
        gate.invalidate()
        assertFalse(gate.isCurrent(request, CACHE_ID, ENTRY_ID))
        assertFalse(
            unknownOutputAcknowledgementMatches(
                scan,
                acknowledgement.copy(operationId = OTHER_OPERATION_ID),
                request,
            ),
        )
    }

    private fun savedScan(
        cacheIdentity: String,
        entryId: String,
        acknowledgement: UnknownOutputCreateAcknowledgement?,
    ): SavedScan {
        return SavedScan(
            cached = CachedScan(
                baseName = cacheIdentity,
                pages = listOf(File("$cacheIdentity.jpg")),
                pdf = File("$cacheIdentity.pdf"),
                entryId = entryId,
            ),
            galleryPages = emptyList(),
            savedPdf = null,
            outputMetadataValid = true,
            unknownOutputCreateAcknowledgement = acknowledgement,
        )
    }

    private companion object {
        const val CACHE_ID = "Scan_2026-08-09_12-12-00"
        const val ENTRY_ID = "00000000-0000-0000-0000-000000000001"
        const val OTHER_ENTRY_ID = "00000000-0000-0000-0000-000000000002"
        const val OPERATION_ID = "00000000-0000-0000-0000-000000000003"
        const val OTHER_OPERATION_ID = "00000000-0000-0000-0000-000000000004"
    }
}
