package com.majkeylab.scanit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class DocumentEditorCanvasDeviceTest {
    @Test
    fun twoRapidStrokesSurviveUntilTheFirstExternalEchoWithoutAnInterveningDraw() = withEditor {
        send(MotionEvent.ACTION_DOWN, finger(1, 40f, 60f))
        send(MotionEvent.ACTION_UP, finger(1, 140f, 60f))
        send(MotionEvent.ACTION_DOWN, finger(1, 40f, 100f))
        send(MotionEvent.ACTION_UP, finger(1, 140f, 100f))
        assertEquals(listOf(1, 2), commits.map { it.size })
        val first = commits[0].single() as PageAnnotation.Ink
        val second = commits[1][1] as PageAnnotation.Ink
        assertEquals(first, commits[1][0])
        assertEquals(commits[1], view.annotations)
        assertEquals(2, first.points.size)
        assertEquals(2, second.points.size)
        assertPoint(first.points.first(), 0.1f, 0.15f)
        assertPoint(first.points.last(), 0.35f, 0.15f)
        assertPoint(second.points.first(), 0.1f, 0.25f)
        assertPoint(second.points.last(), 0.35f, 0.25f)
        val committed = frame()
        assertEquals("Rapid second stroke lost the first stroke", Color.BLUE, committed[60 * 400 + 90])
        assertEquals(Color.BLUE, committed[100 * 400 + 90])
        assertEquals("Separate gestures were joined", Color.WHITE, committed[80 * 400 + 90])
        val echo = ArrayList(commits.last())
        assertFalse(view.annotations === echo)
        view.annotations = echo
        assertTrue(view.annotations === echo)
        assertArrayEquals(committed, frame())
    }

    @Test
    fun equalDistinctExternalEchoDuringTheNextStrokeKeepsItsPreviewAndWholeCommit() = withEditor {
        send(MotionEvent.ACTION_DOWN, finger(1, 40f, 60f))
        send(MotionEvent.ACTION_UP, finger(1, 140f, 60f))
        val first = commits.single().single() as PageAnnotation.Ink
        send(MotionEvent.ACTION_DOWN, finger(1, 40f, 120f))
        send(MotionEvent.ACTION_MOVE, finger(1, 100f, 120f))
        assertEquals(1, commits.size)
        val preview = frame()
        assertEquals(Color.BLUE, preview[60 * 400 + 80])
        assertEquals(Color.BLUE, preview[120 * 400 + 80])
        val echo = ArrayList(commits.single())
        assertEquals(view.annotations, echo)
        assertFalse(view.annotations === echo)
        view.annotations = echo
        assertTrue("Equal VM state did not replace the list reference", view.annotations === echo)
        assertArrayEquals("Equal VM echo canceled the active stroke preview", preview, frame())
        send(MotionEvent.ACTION_UP, finger(1, 160f, 120f))
        assertEquals(listOf(1, 2), commits.map { it.size })
        assertEquals(first, commits.last().first())
        assertEquals(commits.last(), view.annotations)
        val second = commits.last().last() as PageAnnotation.Ink
        assertEquals(3, second.points.size)
        assertPoint(second.points[0], 0.1f, 0.3f)
        assertPoint(second.points[1], 0.25f, 0.3f)
        assertPoint(second.points[2], 0.4f, 0.3f)
        val committed = frame()
        assertEquals(Color.BLUE, committed[60 * 400 + 80])
        assertEquals(Color.BLUE, committed[120 * 400 + 80])
        assertEquals("Release after the echo lost the stroke's tail", Color.BLUE, committed[120 * 400 + 140])
        assertEquals(Color.WHITE, committed[90 * 400 + 90])
    }

    @Test
    fun pageExitAndReentryKeepOnlyTheTwoClippedEdgeSpansUntilOneReleaseCommit() {
        for (tool in listOf(AnnotationTool.Pen, AnnotationTool.Highlighter)) withEditor {
            view.tool = tool
            send(MotionEvent.ACTION_DOWN, finger(1, 80f, 80f))
            send(MotionEvent.ACTION_MOVE, finger(1, 80f, -40f))
            send(MotionEvent.ACTION_MOVE, finger(1, 320f, -40f))
            send(MotionEvent.ACTION_MOVE, finger(1, 320f, 80f))
            assertTrue("Leaving the page committed before release", commits.isEmpty())
            val preview = frame()
            val inkColor = if (tool == AnnotationTool.Highlighter) Color.rgb(175, 175, 255) else Color.BLUE
            for (x in listOf(80, 320)) for (y in listOf(1, 40, 80)) {
                assertEquals("Visible edge span missing at $x,$y", inkColor, preview[y * 400 + x])
            }
            assertEquals("Outside movement joined the page's top edge", Color.WHITE, preview[1 * 400 + 200])
            assertEquals("Reentry drew a straight bridge", Color.WHITE, preview[80 * 400 + 200])

            send(MotionEvent.ACTION_UP, finger(1, 320f, 80f))
            assertEquals(1, commits.size)
            val segments = commits.single().map { it as PageAnnotation.Ink }
            assertEquals(2, segments.size)
            assertTrue(segments.all { it.highlighter == (tool == AnnotationTool.Highlighter) && it.points.size == 2 })
            assertPoint(segments[0].points[0], 0.2f, 0.2f)
            assertPoint(segments[0].points[1], 0.2f, 0f)
            assertPoint(segments[1].points[0], 0.8f, 0f)
            assertPoint(segments[1].points[1], 0.8f, 0.2f)
            assertArrayEquals("Committed pixels differ from the segmented preview", preview, frame())
        }
    }

    @Test
    fun historicalSamplesPreserveSeparateEdgeIntersectionsAndInterpolatedStylusPressure() = withEditor {
        send(MotionEvent.ACTION_DOWN, pen(7, 80f, 80f, 0.5f))
        send(MotionEvent.ACTION_MOVE, pen(7, 360f, 80f, 2f), history = listOf(
            pen(7, 120f, 80f, 0.5f),
            pen(7, 120f, -40f, 0.2f),
            pen(7, 320f, -40f, 0.2f),
            pen(7, 320f, 80f, 0.25f),
        ))
        assertTrue(commits.isEmpty())
        val preview = frame()
        assertEquals(Color.BLUE, preview[80 * 400 + 100])
        assertEquals(Color.BLUE, preview[80 * 400 + 340])
        assertEquals("Batched outside samples were joined", Color.WHITE, preview[80 * 400 + 200])
        send(MotionEvent.ACTION_UP, pen(7, 360f, 80f, 2f))
        assertEquals(1, commits.size)
        assertEquals(1, stylusStarts)
        val segments = commits.single().map { it as PageAnnotation.Ink }
        assertEquals(listOf(3, 3), segments.map { it.points.size })
        assertPoint(segments[0].points[0], 0.2f, 0.2f, 0.5f)
        assertPoint(segments[0].points[1], 0.3f, 0.2f, 0.5f)
        assertPoint(segments[0].points[2], 0.3f, 0f, 0.3f)
        assertPoint(segments[1].points[0], 0.8f, 0f, 0.2f + 0.05f / 3f)
        assertPoint(segments[1].points[1], 0.8f, 0.2f, 0.25f)
        assertPoint(segments[1].points[2], 0.9f, 0.2f, 1f)
        assertArrayEquals(preview, frame())
    }

    @Test
    fun twoOutsideSamplesCrossingALetterboxedPageKeepBothVisibleEdgesAndPressure() = withEditor {
        val widePage = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        try {
            view.page = widePage
            view.inkWidth = 0.08f
            val initial = frame()
            // The fitted page occupies y=100..300 inside the 400x400 view.
            send(MotionEvent.ACTION_DOWN, pen(7, 120f, 60f, 0.25f))
            send(MotionEvent.ACTION_MOVE, pen(7, 120f, 340f, 1f))
            assertTrue(commits.isEmpty())
            val preview = frame()
            for (y in listOf(101, 200, 299)) assertEquals(Color.BLUE, preview[y * 400 + 120])
            for (y in listOf(80, 320)) assertEquals(initial[y * 400 + 120], preview[y * 400 + 120])
            send(MotionEvent.ACTION_UP, pen(7, 120f, 340f, 1f))
            val ink = committedInk()
            assertEquals(2, ink.points.size)
            assertPoint(ink.points[0], 0.3f, 0f, 0.25f + 0.75f / 7f)
            assertPoint(ink.points[1], 0.3f, 1f, 0.25f + 0.75f * 6f / 7f)
            assertArrayEquals(preview, frame())
        } finally {
            view.page = null
            widePage.recycle()
        }
    }

    @Test
    fun cancelClearsCompletedAndLiveSegmentsBeforeTheNextGesture() = withEditor {
        val initial = frame()
        send(MotionEvent.ACTION_DOWN, finger(1, 80f, 80f))
        send(MotionEvent.ACTION_MOVE, finger(1, 80f, -40f))
        send(MotionEvent.ACTION_MOVE, finger(1, 320f, -40f))
        send(MotionEvent.ACTION_MOVE, finger(1, 320f, 80f))
        assertFalse(initial.contentEquals(frame()))
        send(MotionEvent.ACTION_CANCEL, finger(1, 320f, 80f))
        assertTrue(commits.isEmpty())
        assertArrayEquals(initial, frame())
        send(MotionEvent.ACTION_DOWN, finger(1, 40f, 300f))
        send(MotionEvent.ACTION_UP, finger(1, 80f, 300f))
        val ink = committedInk()
        assertEquals(2, ink.points.size)
        assertPoint(ink.points[0], 0.1f, 0.75f)
        assertPoint(ink.points[1], 0.2f, 0.75f)
        val committed = frame()
        assertEquals(initial[40 * 400 + 80], committed[40 * 400 + 80])
        assertEquals(initial[40 * 400 + 320], committed[40 * 400 + 320])
        assertEquals(Color.BLUE, committed[300 * 400 + 60])
    }

    @Test
    fun segmentedGestureUsesOnlyTheRemainingAnnotationSlotAndReportsTheLimitOnce() = withEditor {
        val existing = List(MAX_PAGE_ANNOTATIONS - 1) { stroke(0.85f) }
        view.annotations = existing
        var limits = 0
        view.onLimit = { limits++ }
        send(MotionEvent.ACTION_DOWN, finger(1, 80f, 80f))
        send(MotionEvent.ACTION_MOVE, finger(1, 80f, -40f))
        send(MotionEvent.ACTION_MOVE, finger(1, 320f, -40f))
        send(MotionEvent.ACTION_MOVE, finger(1, 320f, 80f))
        assertTrue(commits.isEmpty())
        val preview = frame()
        assertEquals(Color.BLUE, preview[40 * 400 + 80])
        assertEquals(Color.WHITE, preview[40 * 400 + 320])
        assertEquals(Color.WHITE, preview[80 * 400 + 200])
        send(MotionEvent.ACTION_UP, finger(1, 320f, 80f))
        assertEquals(1, commits.size)
        val committed = commits.single()
        validatePageAnnotations(committed)
        assertEquals(MAX_PAGE_ANNOTATIONS, committed.size)
        assertEquals(existing, committed.take(existing.size))
        val accepted = committed.last() as PageAnnotation.Ink
        assertEquals(2, accepted.points.size)
        assertPoint(accepted.points[0], 0.2f, 0.2f)
        assertPoint(accepted.points[1], 0.2f, 0f)
        assertEquals(1, limits)
        assertArrayEquals(preview, frame())
    }

    @Test
    fun clippedIntersectionsAndNewSegmentsShareTheRemainingTotalPointBudget() = withEditor {
        val existing = PageAnnotation.Ink(
            List(MAX_ANNOTATION_POINTS - 5) { AnnotationPoint(0.5f, 0.85f) }, Color.BLUE, 0.02f,
        )
        view.annotations = listOf(existing)
        var limits = 0
        view.onLimit = { limits++ }
        send(MotionEvent.ACTION_DOWN, finger(1, 80f, 80f))
        send(MotionEvent.ACTION_MOVE, finger(1, 120f, 80f))
        send(MotionEvent.ACTION_MOVE, finger(1, 120f, -40f))
        send(MotionEvent.ACTION_MOVE, finger(1, 320f, -40f))
        send(MotionEvent.ACTION_MOVE, finger(1, 320f, 80f))
        send(MotionEvent.ACTION_MOVE, finger(1, 360f, 80f))
        assertTrue(commits.isEmpty())
        val preview = frame()
        assertEquals(Color.BLUE, preview[80 * 400 + 100])
        assertEquals(Color.BLUE, preview[40 * 400 + 320])
        assertEquals(Color.WHITE, preview[80 * 400 + 200])
        assertEquals("Point quota was reset after a gap", Color.WHITE, preview[80 * 400 + 360])
        send(MotionEvent.ACTION_UP, finger(1, 360f, 80f))
        assertEquals(1, commits.size)
        val committed = commits.single()
        validatePageAnnotations(committed)
        val ink = committed.map { it as PageAnnotation.Ink }
        assertEquals(existing, ink.first())
        assertEquals(listOf(MAX_ANNOTATION_POINTS - 5, 3, 2), ink.map { it.points.size })
        assertEquals(MAX_ANNOTATION_POINTS, ink.sumOf { it.points.size })
        assertPoint(ink[1].points.last(), 0.3f, 0f)
        assertPoint(ink[2].points.first(), 0.8f, 0f)
        assertPoint(ink[2].points.last(), 0.8f, 0.2f)
        assertEquals(1, limits)
        assertArrayEquals(preview, frame())
    }

    @Test
    fun externalUndoDuringAStrokeCannotCommitAgainstTheChangedPageState() = withEditor {
        send(MotionEvent.ACTION_DOWN, finger(1, 40f, 60f))
        send(MotionEvent.ACTION_MOVE, finger(1, 140f, 60f))
        val replacement = listOf(stroke(0.8f))
        view.annotations = replacement
        val updated = frame()
        send(MotionEvent.ACTION_UP, finger(1, 180f, 60f))
        assertTrue(commits.isEmpty())
        assertEquals(replacement, view.annotations)
        assertArrayEquals(updated, frame())
    }

    @Test
    fun zeroPressureFingerDrawsVisibleInkInThePreviewAndCommittedPage() = withEditor {
        val down = finger(1, 40f, 60f).copy(pressure = 0f)
        assertEquals(Color.WHITE, frame()[60 * 400 + 90])
        send(MotionEvent.ACTION_DOWN, down)
        send(MotionEvent.ACTION_MOVE, down.copy(x = 140f))
        assertTrue(commits.isEmpty())
        assertEquals("Zero-pressure finger preview is invisible", Color.BLUE, frame()[60 * 400 + 90])

        send(MotionEvent.ACTION_UP, down.copy(x = 180f))
        val ink = committedInk()
        assertEquals(3, ink.points.size)
        assertTrue("Finger pressure was not normalized", ink.points.all { it.pressure == 1f })
        val rendered = frame()
        assertEquals("Committed finger stroke is invisible", Color.BLUE, rendered[60 * 400 + 90])
        assertEquals("Finger release lost its visible endpoint", Color.BLUE, rendered[60 * 400 + 180])
    }

    @Test
    fun zeroPressureStylusRemainsTransparentInThePreviewAndCommittedPage() = withEditor {
        val initial = frame()
        val down = pen(7, 40f, 60f, 0f)
        send(MotionEvent.ACTION_DOWN, down)
        send(MotionEvent.ACTION_MOVE, down.copy(x = 140f))
        assertTrue(commits.isEmpty())
        assertArrayEquals("Zero-pressure stylus preview gained ink", initial, frame())

        send(MotionEvent.ACTION_UP, down.copy(x = 180f))
        val ink = committedInk()
        assertEquals(3, ink.points.size)
        assertTrue("Stylus zero pressure was replaced", ink.points.all { it.pressure == 0f })
        assertEquals(1, stylusStarts)
        assertArrayEquals("Zero-pressure stylus drew committed ink", initial, frame())
    }

    @Test
    fun cancelDiscardsPenAndHighlighterPreviewsAndTheNextStrokeStartsClean() {
        for (tool in listOf(AnnotationTool.Pen, AnnotationTool.Highlighter)) {
            for (input in listOf(MotionEvent.TOOL_TYPE_FINGER, MotionEvent.TOOL_TYPE_STYLUS)) withEditor {
                view.tool = tool
                val initial = frame()
                val down = Pointer(1, 40f, 60f, input)
                val moved = down.copy(x = 140f, y = 100f)
                send(MotionEvent.ACTION_DOWN, down)
                send(MotionEvent.ACTION_MOVE, moved)
                assertTrue(commits.isEmpty())
                assertFalse("The stroke never reached the native preview", initial.contentEquals(frame()))
                send(MotionEvent.ACTION_CANCEL, moved)
                assertTrue(commits.isEmpty())
                assertArrayEquals(initial, frame())

                send(MotionEvent.ACTION_DOWN, finger(1, 300f, 80f))
                send(MotionEvent.ACTION_UP, finger(1, 320f, 100f))
                val ink = committedInk()
                assertEquals(tool == AnnotationTool.Highlighter, ink.highlighter)
                assertEquals(2, ink.points.size)
                assertPoint(ink.points[0], 0.75f, 0.2f)
                assertPoint(ink.points[1], 0.8f, 0.25f)
            }
        }
    }

    @Test
    fun cancelRestoresPendingWholeStrokeErasureAndTextMovement() = withEditor {
        val ink = stroke(0.2f)
        val text = text()
        view.annotations = listOf(ink, text)
        val initial = frame()
        view.tool = AnnotationTool.Eraser
        send(MotionEvent.ACTION_DOWN, finger(1, 200f, 80f))
        send(MotionEvent.ACTION_MOVE, finger(1, 220f, 80f))
        assertFalse("Eraser did not change the preview", initial.contentEquals(frame()))
        send(MotionEvent.ACTION_CANCEL, finger(1, 220f, 80f))
        assertTrue(commits.isEmpty())
        assertArrayEquals(initial, frame())

        view.tool = AnnotationTool.Text
        val bounds = checkNotNull(pageAnnotationTextBounds(text, 400f, 400f))
        val x = bounds.centerX()
        val y = bounds.centerY()
        send(MotionEvent.ACTION_DOWN, finger(1, x, y))
        send(MotionEvent.ACTION_MOVE, finger(1, x + 40f, y + 60f))
        assertFalse("Text did not move in the preview", initial.contentEquals(frame()))
        send(MotionEvent.ACTION_CANCEL, finger(1, x + 40f, y + 60f))
        assertTrue(commits.isEmpty())
        assertTrue(textRequests.isEmpty())
        assertEquals(listOf(ink, text), view.annotations)
        assertArrayEquals(initial, frame())
    }

    @Test
    fun flagCanceledStylusReleaseDiscardsInkOnAndroid13AndLater() {
        assumeTrue("MotionEvent.FLAG_CANCELED requires API 33", Build.VERSION.SDK_INT >= 33)
        withEditor {
            val initial = frame()
            send(MotionEvent.ACTION_DOWN, pen(7, 40f, 60f, 0.25f))
            send(MotionEvent.ACTION_MOVE, pen(7, 140f, 100f, 0.5f))
            assertFalse(initial.contentEquals(frame()))
            send(MotionEvent.ACTION_UP, pen(7, 160f, 120f, 0.5f), flags = MotionEvent.FLAG_CANCELED)
            assertTrue(commits.isEmpty())
            assertEquals(1, stylusStarts)
            assertArrayEquals(initial, frame())
        }
    }

    @Test
    fun twoFingerPanAndZoomDiscardPendingInkAndDoNotCommitTheRemainingFinger() = withEditor {
        assertEquals(Color.WHITE, frame()[225 * 400 + 256])
        send(MotionEvent.ACTION_DOWN, finger(1, 100f, 200f))
        send(MotionEvent.ACTION_POINTER_DOWN, finger(1, 100f, 200f), finger(3, 300f, 200f), actionIndex = 1)
        send(MotionEvent.ACTION_MOVE, finger(1, 80f, 225f), finger(3, 380f, 225f))
        // The page's 40-pixel centre marker grows and moves with the real viewport transform.
        val pinched = frame()
        assertEquals(Color.BLACK, pinched[225 * 400 + 256])
        assertEquals(Color.WHITE, pinched[260 * 400 + 270])
        send(MotionEvent.ACTION_POINTER_UP, finger(1, 80f, 225f), finger(3, 380f, 225f), actionIndex = 1)
        assertArrayEquals("Navigation jumped when one finger lifted", pinched, frame())
        send(MotionEvent.ACTION_MOVE, finger(1, 120f, 260f))
        assertEquals("The remaining finger lost navigation ownership", Color.BLACK, frame()[260 * 400 + 270])
        send(MotionEvent.ACTION_UP, finger(1, 140f, 270f))
        assertTrue(commits.isEmpty())
        assertTrue(view.annotations.isEmpty())
        assertTrue(textRequests.isEmpty())
        assertEquals(0, stylusStarts)
    }

    @Test
    fun stylusTakesOwnershipFromAnEarlierPalmAndSurvivesPointerIndexChanges() = withEditor {
        send(MotionEvent.ACTION_DOWN, palm(3, 320f, 300f))
        send(MotionEvent.ACTION_POINTER_DOWN, palm(3, 320f, 300f), pen(7, 80f, 120f, 0.25f), actionIndex = 1)
        send(MotionEvent.ACTION_MOVE, palm(3, 340f, 320f), pen(7, 120f, 160f, 2f))
        send(MotionEvent.ACTION_POINTER_UP, palm(3, 340f, 320f), pen(7, 120f, 160f, 2f), actionIndex = 0)
        assertTrue(commits.isEmpty())
        send(MotionEvent.ACTION_MOVE, pen(7, 180f, 200f, 0.75f))
        send(MotionEvent.ACTION_UP, pen(7, 200f, 220f, 0.5f))
        val ink = committedInk()
        assertEquals(1, stylusStarts)
        assertEquals(4, ink.points.size)
        assertPoint(ink.points[0], 0.2f, 0.3f, 0.25f)
        assertPoint(ink.points[1], 0.3f, 0.4f, 1f)
        assertPoint(ink.points[2], 0.45f, 0.5f, 0.75f)
        assertPoint(ink.points[3], 0.5f, 0.55f, 0.5f)
    }

    @Test
    fun palmArrivingDuringPenInputCannotReplaceTheStrokeOrNavigate() = withEditor {
        send(MotionEvent.ACTION_DOWN, pen(7, 80f, 120f, 0.25f))
        send(MotionEvent.ACTION_POINTER_DOWN, pen(7, 80f, 120f, 0.25f), palm(3, 320f, 300f), actionIndex = 1)
        send(MotionEvent.ACTION_MOVE, pen(7, 120f, 160f, 0.5f), palm(3, 340f, 320f))
        send(MotionEvent.ACTION_POINTER_UP, pen(7, 120f, 160f, 0.5f), palm(3, 340f, 320f), actionIndex = 1)
        assertTrue(commits.isEmpty())
        send(MotionEvent.ACTION_MOVE, pen(7, 180f, 200f, 0.75f))
        send(MotionEvent.ACTION_UP, pen(7, 200f, 220f, 0.5f))
        val ink = committedInk()
        assertEquals(1, stylusStarts)
        assertEquals(4, ink.points.size)
        assertPoint(ink.points[0], 0.2f, 0.3f, 0.25f)
        assertPoint(ink.points[1], 0.3f, 0.4f, 0.5f)
        assertPoint(ink.points[2], 0.45f, 0.5f, 0.75f)
        assertPoint(ink.points[3], 0.5f, 0.55f, 0.5f)
    }

    @Test
    fun palmsRemainingAfterPenLiftStaySuppressedUntilEveryContactIsReleased() {
        for (palmFirst in listOf(false, true)) withEditor {
            val penDown = pen(7, 80f, 80f, 0.5f)
            val palmDown = palm(3, 100f, 200f)
            val contacts = if (palmFirst) arrayOf(palmDown, penDown) else arrayOf(penDown, palmDown)
            send(MotionEvent.ACTION_DOWN, contacts[0])
            send(MotionEvent.ACTION_POINTER_DOWN, *contacts, actionIndex = 1)
            send(MotionEvent.ACTION_POINTER_UP, *contacts, actionIndex = if (palmFirst) 1 else 0)
            committedInk()
            val afterPenLift = frame()

            send(MotionEvent.ACTION_MOVE, palm(3, 80f, 300f))
            send(MotionEvent.ACTION_POINTER_DOWN, palm(3, 80f, 300f), palm(9, 300f, 300f), actionIndex = 1)
            send(MotionEvent.ACTION_MOVE, palm(3, 40f, 320f), palm(9, 360f, 320f))
            send(MotionEvent.ACTION_POINTER_UP, palm(3, 40f, 320f), palm(9, 360f, 320f), actionIndex = 1)
            send(MotionEvent.ACTION_MOVE, palm(3, 200f, 350f))
            send(MotionEvent.ACTION_UP, palm(3, 200f, 350f))
            assertEquals("Palm contacts committed annotations", 1, commits.size)
            assertArrayEquals("Palm contacts moved the page after pen lift", afterPenLift, frame())

            send(MotionEvent.ACTION_DOWN, finger(1, 40f, 40f))
            send(MotionEvent.ACTION_UP, finger(1, 60f, 60f))
            assertEquals("Fresh touch gesture stayed suppressed", 2, commits.size)
            val fresh = view.annotations.last() as PageAnnotation.Ink
            assertPoint(fresh.points.first(), 0.1f, 0.1f)
            assertPoint(fresh.points.last(), 0.15f, 0.15f)
        }
    }

    @Test
    fun textTapsRequestExistingOrNewTextWithoutCommittingAnnotations() = withEditor {
        val original = text()
        view.annotations = listOf(original)
        view.tool = AnnotationTool.Text
        send(MotionEvent.ACTION_DOWN, finger(1, 340f, 340f))
        send(MotionEvent.ACTION_UP, finger(1, 340f, 340f))
        assertEquals(null, textRequests.single().first)
        assertPoint(textRequests.single().second, 0.85f, 0.85f)

        val bounds = checkNotNull(pageAnnotationTextBounds(original, 400f, 400f))
        send(MotionEvent.ACTION_DOWN, finger(1, bounds.centerX(), bounds.centerY()))
        send(MotionEvent.ACTION_UP, finger(1, bounds.centerX(), bounds.centerY()))
        assertEquals(2, textRequests.size)
        assertEquals(0, textRequests.last().first)
        assertTrue(commits.isEmpty())
        assertEquals(listOf(original), view.annotations)
    }

    @Test
    fun textDragCommitsOneNormalizedMoveWithoutChangingTheTextOrOtherAnnotations() = withEditor {
        val ink = stroke(0.85f)
        val original = text()
        view.annotations = listOf(ink, original)
        view.tool = AnnotationTool.Text
        val bounds = checkNotNull(pageAnnotationTextBounds(original, 400f, 400f))
        val x = bounds.centerX()
        val y = bounds.centerY()
        val dx = 32f + 12f * view.resources.displayMetrics.density
        val dy = 48f
        send(MotionEvent.ACTION_DOWN, finger(1, x, y))
        send(MotionEvent.ACTION_MOVE, finger(1, x + dx, y + dy))
        assertTrue(commits.isEmpty())
        send(MotionEvent.ACTION_UP, finger(1, x + dx, y + dy))
        assertEquals(1, commits.size)
        val updated = commits.single()
        assertEquals(2, updated.size)
        assertEquals(ink, updated.first())
        val moved = updated.last() as PageAnnotation.Text
        assertEquals(original.value, moved.value)
        assertEquals(original.color, moved.color)
        assertEquals(original.sizeFraction, moved.sizeFraction, 0f)
        assertEquals(original.x + dx / 400f, moved.x, 0.0001f)
        assertEquals(original.y + dy / 400f, moved.y, 0.0001f)
        assertTrue(textRequests.isEmpty())
    }

    @Test
    fun eraserRemovesWholeIntersectedInkAndTextOnlyWhenTheGestureCommits() = withEditor {
        val erasedInk = stroke(0.2f)
        val erasedText = text()
        val retainedInk = stroke(0.85f)
        val initial = listOf(erasedInk, erasedText, retainedInk)
        view.annotations = initial
        view.tool = AnnotationTool.Eraser
        val bounds = checkNotNull(pageAnnotationTextBounds(erasedText, 400f, 400f))
        send(MotionEvent.ACTION_DOWN, finger(1, 200f, 80f))
        send(MotionEvent.ACTION_MOVE, finger(1, bounds.centerX(), bounds.centerY()))
        assertTrue(commits.isEmpty())
        assertEquals(initial, view.annotations)
        send(MotionEvent.ACTION_UP, finger(1, bounds.centerX(), bounds.centerY()))
        assertEquals(listOf(listOf(retainedInk)), commits)
        assertEquals(listOf(retainedInk), view.annotations)
        assertEquals(2, erasedInk.points.size)
        assertTrue(textRequests.isEmpty())
    }

    private fun withEditor(block: EditorFixture.() -> Unit) {
        val failure = AtomicReference<Throwable>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            var page: Bitmap? = null
            var view: DocumentEditorCanvas? = null
            try {
                check(Looper.myLooper() == Looper.getMainLooper())
                page = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(Color.WHITE)
                    Canvas(this).drawRect(180f, 180f, 220f, 220f, Paint().apply { color = Color.BLACK })
                }
                view = DocumentEditorCanvas(instrumentation.targetContext).apply {
                    this.page = page
                    inkColor = Color.BLUE
                    inkWidth = 0.02f
                    measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
                    layout(0, 0, 400, 400)
                }
                EditorFixture(view).block()
            } catch (throwable: Throwable) {
                // Propagate assertions on the test thread rather than crashing Android's main looper.
                failure.set(throwable)
            } finally {
                view?.page = null
                page?.recycle()
            }
        }
        failure.get()?.let { throw it }
    }

    private class EditorFixture(val view: DocumentEditorCanvas) {
        val commits = mutableListOf<List<PageAnnotation>>()
        val textRequests = mutableListOf<Pair<Int?, AnnotationPoint>>()
        var stylusStarts = 0
        private var eventTime = SystemClock.uptimeMillis()
        private var downTime = eventTime

        init {
            // The canvas must retain rapid commits before the external UI echoes its state.
            view.onCommit = { updated -> commits += updated }
            view.onText = { index, point -> textRequests += index to point }
            view.onStylus = { stylusStarts++ }
            view.onLimit = { throw AssertionError("Fixture unexpectedly reached an annotation limit") }
        }

        fun send(action: Int, vararg pointers: Pointer, actionIndex: Int = 0, flags: Int = 0, history: List<Pointer> = emptyList()) {
            check(Looper.myLooper() == Looper.getMainLooper())
            require(pointers.isNotEmpty() && actionIndex in pointers.indices)
            require(history.isEmpty() || action == MotionEvent.ACTION_MOVE && pointers.size == 1 &&
                history.all { it.id == pointers[0].id && it.tool == pointers[0].tool })
            eventTime += 16L
            if (action == MotionEvent.ACTION_DOWN) downTime = eventTime
            val encodedAction = when (action) {
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP ->
                    action or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                else -> action
            }
            val properties = pointers.map { pointer ->
                MotionEvent.PointerProperties().apply { id = pointer.id; toolType = pointer.tool }
            }.toTypedArray()
            val coordinates = pointers.map { pointer ->
                val sample = history.firstOrNull() ?: pointer
                MotionEvent.PointerCoords().apply {
                    x = sample.x; y = sample.y; pressure = sample.pressure
                    size = if (sample.major > 8f) 1f else 0.1f
                    touchMajor = sample.major; touchMinor = sample.major
                }
            }.toTypedArray()
            val source = if (pointers.any { it.tool == MotionEvent.TOOL_TYPE_STYLUS }) {
                InputDevice.SOURCE_STYLUS
            } else InputDevice.SOURCE_TOUCHSCREEN
            val event = MotionEvent.obtain(downTime, eventTime, encodedAction, pointers.size,
                properties, coordinates, 0, 0, 1f, 1f, 0, 0, source, flags)
            try {
                if (history.isNotEmpty()) {
                    for (sample in history.drop(1) + pointers.single()) {
                        eventTime += 16L
                        event.addBatch(eventTime, sample.x, sample.y, sample.pressure,
                            if (sample.major > 8f) 1f else 0.1f, 0)
                    }
                    assertEquals("MotionEvent history was not batched", history.size, event.historySize)
                }
                assertTrue("Native View rejected action $encodedAction", view.dispatchTouchEvent(event))
            } finally {
                event.recycle()
            }
        }

        fun committedInk(): PageAnnotation.Ink {
            assertEquals(1, commits.size)
            assertEquals(1, commits.single().size)
            return commits.single().single() as PageAnnotation.Ink
        }

        fun frame(): IntArray {
            val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                return IntArray(400 * 400).also { bitmap.getPixels(it, 0, 400, 0, 0, 400, 400) }
            } finally {
                bitmap.recycle()
            }
        }
    }

    private data class Pointer(val id: Int, val x: Float, val y: Float, val tool: Int, val pressure: Float = 1f, val major: Float = 8f)

    private fun finger(id: Int, x: Float, y: Float): Pointer = Pointer(id, x, y, MotionEvent.TOOL_TYPE_FINGER)

    // Public MotionEvent has no PALM tool type; large finger contacts exercise the ownership barrier.
    private fun palm(id: Int, x: Float, y: Float): Pointer = finger(id, x, y).copy(major = 80f)

    private fun pen(id: Int, x: Float, y: Float, pressure: Float): Pointer =
        Pointer(id, x, y, MotionEvent.TOOL_TYPE_STYLUS, pressure, major = 1f)

    private fun stroke(y: Float): PageAnnotation.Ink =
        PageAnnotation.Ink(listOf(AnnotationPoint(0.1f, y), AnnotationPoint(0.9f, y)), Color.BLUE, 0.02f)

    private fun text(): PageAnnotation.Text = PageAnnotation.Text("Žluťoučký kůň", 0.2f, 0.5f, 0.06f, Color.BLUE)

    private fun assertPoint(actual: AnnotationPoint, x: Float, y: Float, pressure: Float = 1f) {
        assertEquals(x, actual.x, 0.0001f)
        assertEquals(y, actual.y, 0.0001f)
        assertEquals(pressure, actual.pressure, 0.0001f)
    }
}
