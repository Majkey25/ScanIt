package com.majkeylab.scanit

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class ScannerV2SessionTest {
    @Test
    fun onePageCaptureMovesToReview() {
        val initial = ScannerSessionGate.start()
        val page = page()

        val completed = ScannerSessionGate.completeCapture(initial, initial.generation, page)

        assertEquals(listOf(page), completed.pages)
        assertEquals(0, completed.selectedIndex)
        assertEquals(ScannerSessionStage.Reviewing, completed.stage)
        assertEquals(null, completed.pendingReplacementIndex)
    }

    @Test
    fun twentyPagesAreAcceptedButTwentyFirstIsRejected() {
        var state = ScannerSessionGate.start()
        repeat(MAX_SCAN_PAGES) {
            state = ScannerSessionGate.completeCapture(state, state.generation, page())
            if (it < MAX_SCAN_PAGES - 1) {
                state = ScannerSessionGate.beginCapture(state)
            }
        }

        assertEquals(MAX_SCAN_PAGES, state.pages.size)
        assertThrows(IllegalStateException::class.java) {
            ScannerSessionGate.beginCapture(state)
        }
    }

    @Test
    fun staleAndDuplicateCaptureCallbacksCannotMutateSession() {
        val initial = ScannerSessionGate.start()
        val first = ScannerSessionGate.completeCapture(initial, initial.generation, page())
        val capturing = ScannerSessionGate.beginCapture(first)

        assertSame(
            capturing,
            ScannerSessionGate.completeCapture(capturing, initial.generation, page()),
        )
        assertSame(
            first,
            ScannerSessionGate.completeCapture(first, first.generation, page()),
        )
        assertThrows(IllegalArgumentException::class.java) {
            ScannerSessionGate.completeCapture(capturing, capturing.generation, first.pages.single())
        }
    }

    @Test
    fun reorderKeepsTheSamePageSelected() {
        val pages = List(3) { page() }
        val state = reviewing(pages, selectedIndex = 1)

        val reordered = ScannerSessionGate.reorder(state, fromIndex = 0, toIndex = 2)

        assertEquals(listOf(pages[1], pages[2], pages[0]), reordered.pages)
        assertEquals(0, reordered.selectedIndex)
    }

    @Test
    fun retakeReplacesOnlySelectedPageAfterExactCallback() {
        val pages = List(3) { page() }
        val state = reviewing(pages, selectedIndex = 1)
        val capturing = ScannerSessionGate.beginRetake(state)
        val replacement = page()

        val stale = ScannerSessionGate.completeCapture(
            capturing,
            capturing.generation - 1,
            replacement,
        )
        val completed = ScannerSessionGate.completeCapture(
            capturing,
            capturing.generation,
            replacement,
        )

        assertSame(capturing, stale)
        assertEquals(listOf(pages[0], replacement, pages[2]), completed.pages)
        assertEquals(1, completed.selectedIndex)
        assertEquals(ScannerSessionStage.Reviewing, completed.stage)
    }

    @Test
    fun deletingLastPageStartsAnewCaptureGeneration() {
        val state = reviewing(listOf(page()), selectedIndex = 0)

        val deleted = ScannerSessionGate.delete(state, index = 0)

        assertEquals(emptyList<ScannerPage>(), deleted.pages)
        assertEquals(null, deleted.selectedIndex)
        assertEquals(ScannerSessionStage.Capturing, deleted.stage)
        assertEquals(state.generation + 1, deleted.generation)
    }

    @Test
    fun captureCancellationReturnsToPagesOrClosesEmptySession() {
        val empty = ScannerSessionGate.start()
        val pages = reviewing(listOf(page()), selectedIndex = 0)
        val capturing = ScannerSessionGate.beginCapture(pages)

        val emptyCancelled = ScannerSessionGate.cancelCapture(empty, empty.generation)
        val pageCancelled = ScannerSessionGate.cancelCapture(capturing, capturing.generation)

        assertEquals(ScannerSessionStage.Cancelled, emptyCancelled.stage)
        assertEquals(ScannerSessionStage.Reviewing, pageCancelled.stage)
        assertEquals(pages.pages, pageCancelled.pages)
        assertSame(capturing, ScannerSessionGate.cancelCapture(capturing, pages.generation))
    }

    @Test
    fun restoredCaptureRetainsExactRetakeAndRejectsInvalidState() {
        val pages = List(2) { page() }
        val restored = ScannerSessionState.restore(
            generation = 42,
            pages = pages,
            selectedIndex = 1,
            stage = ScannerSessionStage.Capturing,
            pendingReplacementIndex = 1,
        )

        assertEquals(42, restored.generation)
        assertEquals(1, restored.pendingReplacementIndex)
        assertThrows(IllegalArgumentException::class.java) {
            ScannerSessionState.restore(
                generation = 42,
                pages = pages,
                selectedIndex = 1,
                stage = ScannerSessionStage.Reviewing,
                pendingReplacementIndex = 1,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScannerSessionState.restore(
                generation = 42,
                pages = listOf(pages[0], pages[0]),
                selectedIndex = 0,
                stage = ScannerSessionStage.Reviewing,
                pendingReplacementIndex = null,
            )
        }
    }

    @Test
    fun pageIdsMustBeExactCanonicalUuids() {
        val canonical = UUID.randomUUID().toString()

        assertEquals(canonical, PageId.parse(canonical).value)
        assertThrows(IllegalArgumentException::class.java) { PageId.parse(canonical.uppercase()) }
        assertThrows(IllegalArgumentException::class.java) { PageId.parse("1-1-1-1-1") }
        assertThrows(IllegalArgumentException::class.java) { PageId.parse("not-a-page") }
    }

    @Test
    fun finishIsTerminalAndRequiresPages() {
        val empty = ScannerSessionGate.start()
        val review = reviewing(listOf(page()), selectedIndex = 0)
        val finishing = ScannerSessionGate.finish(review)

        assertEquals(ScannerSessionStage.Finishing, finishing.stage)
        assertThrows(IllegalStateException::class.java) { ScannerSessionGate.finish(empty) }
        assertThrows(IllegalStateException::class.java) {
            ScannerSessionGate.delete(finishing, 0)
        }
        assertNotEquals(review, finishing)
    }

    private fun page(): ScannerPage = ScannerPage(PageId.parse(UUID.randomUUID().toString()))

    private fun reviewing(
        pages: List<ScannerPage>,
        selectedIndex: Int,
        generation: Long = 8,
    ): ScannerSessionState = ScannerSessionState.restore(
        generation = generation,
        pages = pages,
        selectedIndex = selectedIndex,
        stage = ScannerSessionStage.Reviewing,
        pendingReplacementIndex = null,
    )
}
