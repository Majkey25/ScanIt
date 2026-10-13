package com.majkeylab.scanit

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PageBrowserTest {
    @Test
    fun appearanceEditorUsesSplitLayoutOnlyInLandscape() {
        assertTrue(appearanceEditorUsesSplitLayout(2400, 1080))
        assertFalse(appearanceEditorUsesSplitLayout(1080, 2400))
        assertFalse(appearanceEditorUsesSplitLayout(1080, 1080))
    }

    @Test
    fun resultActionsUseCompactAccessibleHeight() {
        assertEquals(48, RESULT_ACTION_MIN_HEIGHT_DP)
    }

    @Test
    fun resultPageStatusIsOneBasedAndClamped() {
        assertEquals(1 to 1, resultPageStatus(currentIndex = 0, pageCount = 1))
        assertEquals(2 to 3, resultPageStatus(currentIndex = 1, pageCount = 3))
        assertEquals(1 to 3, resultPageStatus(currentIndex = -1, pageCount = 3))
        assertEquals(3 to 3, resultPageStatus(currentIndex = 10, pageCount = 3))
    }

    @Test
    fun resultActionsStackOnlyWhenWidthOrTextNeedsIt() {
        assertFalse(stackResultActions(fontScale = 1f, availableWidthDp = 360))
        assertTrue(stackResultActions(fontScale = 1.3f, availableWidthDp = 360))
        assertTrue(stackResultActions(fontScale = 1f, availableWidthDp = 359))
    }

    @Test
    fun redactionCanvasHeightStaysUsefulAndBounded() {
        assertEquals(180, boundedRedactionCanvasHeightDp(320))
        assertEquals(360, boundedRedactionCanvasHeightDp(800))
        assertEquals(420, boundedRedactionCanvasHeightDp(1_200))
    }

    @Test
    fun selectedPageInsideBoundsIsPreserved() {
        assertEquals(2, resolvedPageIndex(selectedIndex = 2, pageCount = 4))
    }

    @Test
    fun selectedPageOutsideBoundsIsClamped() {
        assertEquals(0, resolvedPageIndex(selectedIndex = -1, pageCount = 4))
        assertEquals(3, resolvedPageIndex(selectedIndex = 4, pageCount = 4))
    }

    @Test
    fun selectingFromEmptyPagesIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            resolvedPageIndex(selectedIndex = 0, pageCount = 0)
        }
    }

    @Test
    fun savedPageIsRestoredOnlyForTheSameDocument() {
        assertEquals(
            2,
            restoredResultPageIndex(
                savedCacheId = "Scan_A",
                targetCacheId = "Scan_A",
                savedPageIndex = 2,
                pageCount = 4,
            ),
        )
        assertEquals(
            0,
            restoredResultPageIndex(
                savedCacheId = "Scan_A",
                targetCacheId = "Scan_B",
                savedPageIndex = 2,
                pageCount = 4,
            ),
        )
        assertEquals(
            3,
            restoredResultPageIndex(
                savedCacheId = "Scan_A",
                targetCacheId = "Scan_A",
                savedPageIndex = 20,
                pageCount = 4,
            ),
        )
    }

    @Test
    fun manualCleanupNeedsReadyPreviewAndEditableSource() {
        assertFalse(manualCleanupAvailable(previewLoading = true, hasPreview = true, sourceEditable = true))
        assertFalse(manualCleanupAvailable(previewLoading = false, hasPreview = false, sourceEditable = true))
        assertFalse(manualCleanupAvailable(previewLoading = false, hasPreview = true, sourceEditable = false))
        assertTrue(manualCleanupAvailable(previewLoading = false, hasPreview = true, sourceEditable = true))
    }

    @Test
    fun fullscreenPanUsesFittedBitmapRatherThanViewportBounds() {
        assertEquals(
            Offset(0f, 200f),
            clampFullscreenOffset(Offset(1_000f, 1_000f), IntSize(600, 400), IntSize(100, 200), 2f),
        )
        assertEquals(
            Offset(-200f, 0f),
            clampFullscreenOffset(Offset(-1_000f, -1_000f), IntSize(400, 600), IntSize(200, 100), 2f),
        )
    }

    @Test
    fun fullscreenPanClampsBothDirectionsAndPreservesValidTranslation() {
        val viewport = IntSize(400, 300)
        val bitmap = IntSize(100, 100)
        assertEquals(Offset(100f, 150f), clampFullscreenOffset(Offset(1_000f, 1_000f), viewport, bitmap, 2f))
        assertEquals(Offset(-100f, -150f), clampFullscreenOffset(Offset(-1_000f, -1_000f), viewport, bitmap, 2f))
        assertEquals(Offset(42f, -73f), clampFullscreenOffset(Offset(42f, -73f), viewport, bitmap, 2f))
        assertEquals(Offset(25f, 75f), clampFullscreenOffset(Offset(100f, 150f), viewport, bitmap, 1.5f))
    }

    @Test
    fun fullscreenPanReclampsAfterViewportAndBitmapChanges() {
        val bitmap = IntSize(400, 400)
        val offset = clampFullscreenOffset(Offset(1_000f, 1_000f), IntSize(600, 400), bitmap, 2f)
        assertEquals(Offset(100f, 200f), offset)
        val resized = clampFullscreenOffset(offset, IntSize(400, 600), bitmap, 2f)
        assertEquals(Offset(100f, 100f), resized)
        assertEquals(
            Offset(100f, 0f),
            clampFullscreenOffset(resized, IntSize(400, 600), IntSize(800, 200), 2f),
        )
    }

    @Test
    fun fullscreenPanResetsAtFitScaleAndWithoutMeasuredContent() {
        val offset = Offset(100f, -200f)
        val size = IntSize(400, 600)
        assertEquals(Offset.Zero, clampFullscreenOffset(offset, size, size, 1f))
        assertEquals(Offset.Zero, clampFullscreenOffset(offset, IntSize.Zero, size, 2f))
        assertEquals(Offset.Zero, clampFullscreenOffset(offset, size, IntSize.Zero, 2f))
    }

    @Test
    fun pageLoadIsCurrentOnlyForTheSameDocumentEntryAndPage() {
        val request = ResultPageLoad("Scan_A", "entry-1", pageIndex = 2)

        assertTrue(request.isCurrent("Scan_A", "entry-1", selectedPageIndex = 2))
        assertFalse(request.isCurrent("Scan_B", "entry-1", selectedPageIndex = 2))
        assertFalse(request.isCurrent("Scan_A", "entry-2", selectedPageIndex = 2))
        assertFalse(request.isCurrent("Scan_A", "entry-1", selectedPageIndex = 1))
    }
}
