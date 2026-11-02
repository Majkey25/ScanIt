package com.majkeylab.scanit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DocumentAnnotationsTest {
    private val black = 0xFF000000.toInt()
    private val center = AnnotationPoint(0.5f, 0.5f)

    @Test
    fun pointsRejectNonFiniteAndUnnormalizedCoordinatesAndPressure() {
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -0.01f, 1.01f)) {
            assertThrows(IllegalArgumentException::class.java) { AnnotationPoint(invalid, 0f) }
            assertThrows(IllegalArgumentException::class.java) { AnnotationPoint(0f, invalid) }
            assertThrows(IllegalArgumentException::class.java) { AnnotationPoint(0f, 0f, invalid) }
        }
        assertEquals(0f, AnnotationPoint(0f, 1f, 0f).pressure, 0f)
        assertEquals(1f, center.pressure, 0f)
    }

    @Test
    fun strokesAndTextRejectInvalidSizesAndEmptyContent() {
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1f, 0f, 1.01f)) {
            assertThrows(IllegalArgumentException::class.java) { ink(width = invalid) }
            assertThrows(IllegalArgumentException::class.java) { text(size = invalid) }
        }
        assertThrows(IllegalArgumentException::class.java) { ink(points = emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { ink(points = List(32_769) { center }) }
        assertThrows(IllegalArgumentException::class.java) { text(value = " ") }
        assertThrows(IllegalArgumentException::class.java) { text(value = "x".repeat(2_001)) }
        assertThrows(IllegalArgumentException::class.java) { text().copy(x = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { text().copy(y = -0.1f) }
        assertEquals("Žluťoučký kůň\nďábelské ódy", text("Žluťoučký kůň\nďábelské ódy").value)
    }

    @Test
    fun pageLimitsAcceptTheBoundaryAndRejectTheNextItemPointOrCharacter() {
        validatePageAnnotations(emptyList())
        validatePageAnnotations(List(256) { ink() })
        assertThrows(IllegalArgumentException::class.java) { validatePageAnnotations(List(257) { ink() }) }
        val points = List(2) { ink(points = List(16_384) { center }) }
        validatePageAnnotations(points)
        assertThrows(IllegalArgumentException::class.java) { validatePageAnnotations(points + ink()) }
        val characters = List(8) { text("x".repeat(2_000)) }
        validatePageAnnotations(characters)
        assertThrows(IllegalArgumentException::class.java) { validatePageAnnotations(characters + text()) }
    }

    @Test
    fun boundaryValidationRechecksMutableCallerOwnedStrokeLists() {
        val points = mutableListOf(center)
        val stroke = ink(points)
        points.clear()
        assertThrows(IllegalArgumentException::class.java) { validatePageAnnotations(listOf(stroke)) }
        points.addAll(List(32_769) { center })
        assertThrows(IllegalArgumentException::class.java) { hits(listOf(stroke), center) }
    }

    @Test
    fun eraserHitsBetweenSamplesButDoesNotEraseTheWholeStrokeBoundingBox() {
        val diagonal = ink(listOf(AnnotationPoint(0.125f, 0.125f), AnnotationPoint(0.875f, 0.875f)))
        assertEquals(setOf(0), hits(listOf(diagonal), center))
        assertEquals(emptySet<Int>(), hits(listOf(diagonal), AnnotationPoint(0.25f, 0.75f)))
    }

    @Test
    fun eraserIncludesRoundCapsAndToleranceWithoutExtendingTheWholeLine() {
        val stroke = ink(listOf(AnnotationPoint(0.25f, 0.5f), AnnotationPoint(0.75f, 0.5f)), 0.125f)
        assertEquals(setOf(0), hits(listOf(stroke), AnnotationPoint(0.1875f, 0.5f)))
        assertEquals(emptySet<Int>(), hits(listOf(stroke), AnnotationPoint(0.15625f, 0.5f)))
        assertEquals(setOf(0), hits(listOf(stroke), AnnotationPoint(0.15625f, 0.5f), 0.03125f))
        assertEquals(emptySet<Int>(), hits(listOf(stroke), AnnotationPoint(0f, 0.5f), 0.03125f))
    }

    @Test
    fun thicknessUsesTheShortPageSideRatherThanNormalizedEuclideanDistance() {
        val stroke = ink(listOf(AnnotationPoint(0.25f, 0.5f), AnnotationPoint(0.75f, 0.5f)), 0.125f)
        assertEquals(setOf(0), annotationsAtPoint(listOf(stroke), AnnotationPoint(0.5f, 0.5625f), 400f, 100f, 0f))
        assertEquals(emptySet<Int>(), annotationsAtPoint(listOf(stroke), AnnotationPoint(0.5f, 0.578125f), 400f, 100f, 0f))
        assertEquals(emptySet<Int>(), annotationsAtPoint(listOf(stroke), AnnotationPoint(0.5f, 0.5625f), 100f, 400f, 0f))
    }

    @Test
    fun singlePointsRepeatedPointsAndZeroPressureHaveDefinedGeometry() {
        assertEquals(setOf(0), hits(listOf(ink()), center))
        assertEquals(setOf(0), hits(listOf(ink(listOf(center, center))), center))
        assertEquals(emptySet<Int>(), hits(listOf(ink(listOf(center.copy(pressure = 0f)))), center, 1f))
        assertEquals(emptySet<Int>(), hits(listOf(ink().copy(color = 0)), center, 1f))
    }

    @Test
    fun taperedPressureRibbonAndItsSlopingEdgesRemainSelectable() {
        val stroke = ink(listOf(AnnotationPoint(0.25f, 0.5f, 0f), AnnotationPoint(0.75f, 0.5f)), 0.5f)
        assertEquals(setOf(0), hits(listOf(stroke), AnnotationPoint(0.5f, 0.625f)))
        assertEquals(setOf(0), hits(listOf(stroke), AnnotationPoint(0.375f, 0.578125f), 0.015625f))
        assertEquals(emptySet<Int>(), hits(listOf(stroke), AnnotationPoint(0.375f, 0.59375f), 0.015625f))
    }

    @Test
    fun eraserReturnsEveryMatchingAnnotationIndex() {
        val items = listOf(ink(), ink(listOf(AnnotationPoint(0f, 0f))), ink())
        assertEquals(setOf(0, 2), hits(items, center))
    }

    @Test
    fun hitTestingRejectsInvalidPageDimensionsAndToleranceEvenForEmptyPages() {
        for (invalid in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { annotationsAtPoint(emptyList(), center, invalid, 1f, 0f) }
            assertThrows(IllegalArgumentException::class.java) { annotationsAtPoint(emptyList(), center, 1f, invalid, 0f) }
        }
        for (invalid in listOf(-1f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { hits(emptyList(), center, invalid) }
        }
    }

    private fun ink(points: List<AnnotationPoint> = listOf(center), width: Float = 0.0625f): PageAnnotation.Ink =
        PageAnnotation.Ink(points, black, width)

    private fun text(value: String = "Text", size: Float = 0.0625f): PageAnnotation.Text =
        PageAnnotation.Text(value, 0f, 0f, size, black)

    private fun hits(items: List<PageAnnotation>, point: AnnotationPoint, tolerance: Float = 0f): Set<Int> =
        annotationsAtPoint(items, point, 256f, 256f, tolerance)
}
