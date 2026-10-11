package com.majkeylab.scanit

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryCropGeometryTest {
    @Test
    fun defaultCropKeepsEveryImageEdge() {
        val plan = requireNotNull(galleryCropPlan(fullGalleryCrop(), 800, 600))
        assertEquals(800, plan.width)
        assertEquals(600, plan.height)
        assertArrayEquals(floatArrayOf(0f, 0f, 800f, 0f, 800f, 600f, 0f, 600f), plan.source, 0f)
        assertArrayEquals(plan.source, plan.destination, 0f)
    }

    @Test
    fun manualRectangleUsesImageCoordinatesRatherThanPreviewPixels() {
        val corners = listOf(GalleryCropPoint(.1f, .2f), GalleryCropPoint(.9f, .2f), GalleryCropPoint(.9f, .8f), GalleryCropPoint(.1f, .8f))
        val plan = requireNotNull(galleryCropPlan(corners, 1000, 500))
        assertEquals(800, plan.width)
        assertEquals(300, plan.height)
        assertArrayEquals(floatArrayOf(100f, 100f, 900f, 100f, 900f, 400f, 100f, 400f), plan.source, .001f)
        assertArrayEquals(floatArrayOf(0f, 0f, 800f, 0f, 800f, 300f, 0f, 300f), plan.destination, .001f)
    }

    @Test
    fun perspectiveOutputKeepsTheLongerOpposingEdges() {
        val corners = listOf(GalleryCropPoint(.2f, .1f), GalleryCropPoint(.8f, .1f), GalleryCropPoint(1f, .9f), GalleryCropPoint(0f, .9f))
        val plan = requireNotNull(galleryCropPlan(corners, 1000, 1000))
        assertEquals(1000, plan.width)
        assertEquals(825, plan.height)
    }

    @Test
    fun crossingConcaveRepeatedAndReversedCornersAreRejected() {
        val full = fullGalleryCrop()
        assertNull(galleryCropPlan(listOf(full[0], full[2], full[1], full[3]), 1000, 1000))
        assertNull(galleryCropPlan(listOf(full[0], full[1], GalleryCropPoint(.1f, .1f), full[3]), 1000, 1000))
        assertNull(galleryCropPlan(listOf(full[0], full[0], full[2], full[3]), 1000, 1000))
        assertNull(galleryCropPlan(full.reversed(), 1000, 1000))
        assertNull(galleryCropPlan(full.take(3), 1000, 1000))
    }

    @Test
    fun nonFiniteAndOutOfBoundsCoordinatesAreRejected() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -.01f, 1.01f)) {
            assertNull(galleryCropPlan(fullGalleryCrop().mapIndexed { index, point -> if (index == 0) point.copy(x = value) else point }, 1000, 1000))
            assertNull(galleryCropPlan(fullGalleryCrop().mapIndexed { index, point -> if (index == 0) point.copy(y = value) else point }, 1000, 1000))
        }
    }

    @Test
    fun unsafeSourceDimensionsAndNearlySingularCropsAreRejected() {
        assertNull(galleryCropPlan(fullGalleryCrop(), 0, 100))
        assertNull(galleryCropPlan(fullGalleryCrop(), MAX_ORIGINAL_IMAGE_DIMENSION + 1, 100))
        assertNull(galleryCropPlan(fullGalleryCrop(), 20_000, 20_000))
        val thin = listOf(GalleryCropPoint(0f, 0f), GalleryCropPoint(.0001f, 0f), GalleryCropPoint(.0001f, 1f), GalleryCropPoint(0f, 1f))
        assertNull(galleryCropPlan(thin, 1000, 1000))
    }

    @Test
    fun outputStaysInsideExistingPixelAndEdgeLimits() {
        val plan = requireNotNull(galleryCropPlan(fullGalleryCrop(), 16_000, 10_000))
        assertTrue(plan.width <= MAX_IMAGE_EXPORT_DIMENSION)
        assertTrue(plan.height <= MAX_IMAGE_EXPORT_DIMENSION)
        assertTrue(plan.width.toLong() * plan.height <= MAX_IMAGE_EXPORT_PIXELS)
    }

    @Test
    fun letterboxedPreviewMapsCornersAndClampsDraggingToImage() {
        val frame = galleryCropFrame(800, 400, 400f, 400f)
        assertEquals(GalleryCropFrame(0f, 100f, 400f, 200f), frame)
        assertEquals(GalleryCropPoint(0f, 0f), frame.imagePoint(0f, 100f))
        assertEquals(GalleryCropPoint(1f, 1f), frame.imagePoint(400f, 300f))
        assertEquals(GalleryCropPoint(.5f, .5f), frame.imagePoint(200f, 200f))
        assertEquals(GalleryCropPoint(0f, 1f), frame.imagePoint(-100f, 500f))
        assertEquals(GalleryCropFrame(100f, 0f, 200f, 400f), galleryCropFrame(400, 800, 400f, 400f))
    }
}
