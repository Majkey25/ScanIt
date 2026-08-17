package com.majkeylab.scanit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerV2GeometryTest {
    @Test
    fun validQuadRequiresCanonicalClockwiseOrder() {
        val quad = quad()

        assertEquals(0.64, quad.area, 0.0001)
        assertThrows(IllegalArgumentException::class.java) {
            PageQuad.create(quad.topRight, quad.topLeft, quad.bottomLeft, quad.bottomRight)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PageQuad.create(quad.topLeft, quad.bottomRight, quad.topRight, quad.bottomLeft)
        }
    }

    @Test
    fun rejectsConcaveTinyNonFiniteAndOutOfBoundsQuads() {
        assertThrows(IllegalArgumentException::class.java) {
            PageQuad.create(point(.1, .1), point(.9, .1), point(.4, .4), point(.1, .9))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PageQuad.create(point(.1, .1), point(.15, .1), point(.15, .15), point(.1, .15))
        }
        assertThrows(IllegalArgumentException::class.java) { NormalizedPoint(Double.NaN, .5) }
        assertThrows(IllegalArgumentException::class.java) { NormalizedPoint(.5, 1.01) }
    }

    @Test
    fun clockwiseRotationKeepsCanonicalCornerNames() {
        val original = PageQuad.create(
            topLeft = point(.1, .2),
            topRight = point(.8, .1),
            bottomRight = point(.9, .7),
            bottomLeft = point(.2, .8),
        )

        val rotated = original.rotateClockwise()

        assertPoint(.2, .2, rotated.topLeft)
        assertPoint(.8, .1, rotated.topRight)
        assertPoint(.9, .8, rotated.bottomRight)
        assertPoint(.3, .9, rotated.bottomLeft)
    }

    @Test
    fun horizontalMirrorKeepsCanonicalCornerNames() {
        val original = PageQuad.create(
            topLeft = point(.1, .2),
            topRight = point(.8, .1),
            bottomRight = point(.9, .7),
            bottomLeft = point(.2, .8),
        )

        val mirrored = original.mirrorHorizontally()

        assertPoint(.2, .1, mirrored.topLeft)
        assertPoint(.9, .2, mirrored.topRight)
        assertPoint(.8, .8, mirrored.bottomRight)
        assertPoint(.1, .7, mirrored.bottomLeft)
    }

    @Test
    fun nudgeClampsToFrameAndRejectsInvalidShape() {
        val original = PageQuad.create(
            topLeft = point(.01, .01),
            topRight = point(.9, .1),
            bottomRight = point(.9, .9),
            bottomLeft = point(.1, .9),
        )

        val clamped = original.nudge(PageCorner.TopLeft, deltaX = -.05, deltaY = -.05)
        val narrow = PageQuad.create(
            topLeft = point(.1, .1),
            topRight = point(.18, .1),
            bottomRight = point(.9, .9),
            bottomLeft = point(.1, .9),
        )
        val rejected = narrow.nudge(PageCorner.TopLeft, deltaX = .1, deltaY = 0.0)

        assertPoint(0.0, 0.0, clamped.topLeft)
        assertSame(narrow, rejected)
        assertThrows(IllegalArgumentException::class.java) {
            original.nudge(PageCorner.TopLeft, deltaX = .11, deltaY = 0.0)
        }
    }

    @Test
    fun absoluteCornerMoveRecoversAfterAnInvalidTarget() {
        val original = PageQuad.create(
            topLeft = point(.1, .1),
            topRight = point(.18, .1),
            bottomRight = point(.9, .9),
            bottomLeft = point(.1, .9),
        )

        val rejected = moveScannerV2CornerTo(
            original,
            PageCorner.TopLeft,
            target = point(.3, .1),
        )
        val recovered = moveScannerV2CornerTo(
            rejected,
            PageCorner.TopLeft,
            target = point(.05, .05),
        )

        assertSame(original, rejected)
        assertPoint(.05, .05, recovered.topLeft)
    }

    @Test
    fun absoluteCornerMoveTracksLargePointerJumpsAndRejectsCrossedCorners() {
        val original = quad()

        val largeValidJump = moveScannerV2CornerTo(
            original,
            PageCorner.TopLeft,
            target = point(.35, .4),
        )
        val crossedCorner = moveScannerV2CornerTo(
            original,
            PageCorner.BottomRight,
            target = point(0.0, 0.0),
        )

        assertPoint(.35, .4, largeValidJump.topLeft)
        assertSame(original, crossedCorner)
    }

    @Test
    fun cropMagnifierStaysVisibleOnTheOppositeSideOfTheDraggedCorner() {
        val topLeftLens = scannerV2MagnifierCenter(
            PageCorner.TopLeft,
            point(.05, .02),
        )
        val bottomRightLens = scannerV2MagnifierCenter(
            PageCorner.BottomRight,
            point(.96, .98),
        )

        assertPoint(.82, .18, topLeftLens)
        assertPoint(.18, .82, bottomRightLens)
    }

    @Test
    fun cropMagnifierCrosshairTracksCornersInsideClampedSourceWindow() {
        val nearEdge = scannerV2MagnifierSourcePosition(
            draggedPoint = point(.02, .03),
            imageWidth = 1200,
            imageHeight = 800,
            sourceLeft = 0,
            sourceTop = 0,
            sourceSize = 112,
        )
        val centered = scannerV2MagnifierSourcePosition(
            draggedPoint = point(.5, .5),
            imageWidth = 1200,
            imageHeight = 800,
            sourceLeft = 544,
            sourceTop = 344,
            sourceSize = 112,
        )

        assertPoint(24.0 / 112.0, 24.0 / 112.0, nearEdge)
        assertPoint(.5, .5, centered)
    }

    @Test
    fun warpSizeUsesQuadEdgesWithoutUpscaling() {
        val size = deriveWarpSize(
            sourceWidth = 4000,
            sourceHeight = 3000,
            quad = quad(),
        )

        assertEquals(3200, size.width)
        assertEquals(2400, size.height)
    }

    @Test
    fun warpSizeHonorsPixelAndEdgeBounds() {
        val size = deriveWarpSize(
            sourceWidth = 12000,
            sourceHeight = 8000,
            quad = PageQuad.fullFrame(),
        )

        assertTrue(size.width <= MAX_IMAGE_EXPORT_DIMENSION)
        assertTrue(size.height <= MAX_IMAGE_EXPORT_DIMENSION)
        assertTrue(size.width.toLong() * size.height <= MAX_IMAGE_EXPORT_PIXELS)
        assertEquals(1.5, size.width.toDouble() / size.height, .002)
    }

    private fun quad(): PageQuad = PageQuad.create(
        topLeft = point(.1, .1),
        topRight = point(.9, .1),
        bottomRight = point(.9, .9),
        bottomLeft = point(.1, .9),
    )

    private fun point(x: Double, y: Double): NormalizedPoint = NormalizedPoint(x, y)

    private fun assertPoint(x: Double, y: Double, actual: NormalizedPoint) {
        assertEquals(x, actual.x, .0001)
        assertEquals(y, actual.y, .0001)
    }
}
