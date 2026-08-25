package com.majkeylab.scanit

import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RedactionRendererTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun pixelRectsApplyTwoPixelPaddingAndClampToPageBounds() {
        assertEquals(
            listOf(
                RedactionPixelRect(18, 18, 42, 42),
                RedactionPixelRect(0, 0, 3, 3),
            ),
            redactionPixelRects(
                width = 80,
                height = 80,
                regions =
                    listOf(
                        NormalizedRect(0.25f, 0.25f, 0.5f, 0.5f),
                        NormalizedRect(0f, 0f, 0.01f, 0.01f),
                    ),
            ),
        )
    }

    @Test
    fun pixelRectPreflightRejectsInvalidBoundsAndUnboundedRegionCounts() {
        val region = NormalizedRect(0.25f, 0.25f, 0.5f, 0.5f)

        assertThrows(IllegalArgumentException::class.java) {
            redactionPixelRects(0, 80, listOf(region))
        }
        assertThrows(IllegalArgumentException::class.java) {
            redactionPixelRects(
                80,
                80,
                List(MAX_SAFE_SHARE_SUGGESTIONS_PER_PAGE + 1) { region },
            )
        }
    }

    @Test
    fun pixelStrokesScaleCoordinatesAndThicknessFromTheShortPageEdge() {
        assertEquals(
            listOf(
                RedactionPixelStroke(
                    points =
                        listOf(
                            RedactionPixelPoint(50f, 25f),
                            RedactionPixelPoint(150f, 75f),
                        ),
                    width = 5f,
                    tool = RedactionTool.Line,
                ),
            ),
            redactionPixelStrokes(
                width = 200,
                height = 100,
                strokes =
                    listOf(
                        RedactionStroke(
                            points = listOf(MarkPoint(0.25f, 0.25f), MarkPoint(0.75f, 0.75f)),
                            widthFraction = 0.05f,
                            tool = RedactionTool.Line,
                        ),
                    ),
            ),
        )
    }

    @Test
    fun pixelStrokePreflightRejectsOutOfRangePointsAndUnboundedInput() {
        assertThrows(IllegalArgumentException::class.java) {
            redactionPixelStrokes(
                100,
                100,
                listOf(RedactionStroke(listOf(MarkPoint(1.01f, 0.5f)), 0.05f)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            redactionPixelStrokes(
                100,
                100,
                List(MAX_MARK_DRAWING_STROKES + 1) {
                    RedactionStroke(listOf(MarkPoint(0.5f, 0.5f)), 0.05f)
                },
            )
        }
    }

    @Test
    fun atomicPublicationPreservesDestinationOnCancellationOrVerificationFailure() {
        val destination = temporaryFolder.newFile("redacted.jpg").apply { writeText("old") }
        var checks = 0

        assertThrows(CancellationException::class.java) {
            publishImageExportAtomically(destination, isCancelled = { ++checks == 2 }) { staging ->
                staging.writeText("new")
            }
        }
        assertEquals("old", destination.readText())

        assertThrows(IOException::class.java) {
            publishImageExportAtomically(destination, isCancelled = { false }) { staging ->
                staging.writeText("incomplete")
                throw IOException("verification failed")
            }
        }
        assertEquals("old", destination.readText())
        assertTrue(
            temporaryFolder.root.listFiles().orEmpty().none {
                it.name.startsWith(".scanit-image-")
            },
        )
    }

    @Test
    fun cancellationBeforeRenderingLeavesNoDestinationAndSkipsWriter() {
        val destination = File(temporaryFolder.root, "cancelled.jpg")
        var wrote = false

        assertThrows(CancellationException::class.java) {
            publishImageExportAtomically(destination, isCancelled = { true }) {
                wrote = true
            }
        }

        assertFalse(wrote)
        assertFalse(destination.exists())
    }
}
