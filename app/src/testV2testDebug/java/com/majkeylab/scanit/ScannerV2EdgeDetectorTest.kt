package com.majkeylab.scanit

import java.nio.ByteBuffer
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerV2EdgeDetectorTest {
    @Test
    fun detectsCleanDocument() {
        val expected = pixels(
            38 to 20,
            218 to 20,
            218 to 172,
            38 to 172,
        )

        val detected = detectDocumentQuad(frame(expected, documentDelta = 170))

        assertQuadNear(expected, detected)
    }

    @Test
    fun detectsRotatedDocument() {
        val expected = pixels(
            64 to 15,
            230 to 48,
            192 to 176,
            26 to 134,
        )

        val detected = detectDocumentQuad(frame(expected, documentDelta = 150))

        assertQuadNear(expected, detected, tolerance = .07)
    }

    @Test
    fun detectsLowContrastDocumentAcrossLightingGradient() {
        val expected = pixels(
            42 to 24,
            215 to 30,
            208 to 168,
            35 to 162,
        )
        val image = frame(expected, documentDelta = 24) { x, y ->
            72 + x / 18 + y / 24
        }

        val detected = detectDocumentQuad(image)

        assertQuadNear(expected, detected, tolerance = .08)
    }

    @Test
    fun ignoresPatternedBackgroundAroundDocument() {
        val expected = pixels(
            48 to 20,
            216 to 28,
            210 to 170,
            42 to 166,
        )
        val image = frame(expected, documentDelta = 130) { x, y ->
            35 + if ((x / 12 + y / 12) % 2 == 0) 5 else 0
        }

        val detected = detectDocumentQuad(image)

        assertQuadNear(expected, detected, tolerance = .07)
    }

    @Test
    fun ignoresHardLightingShadowAcrossDocument() {
        val expected = pixels(
            44 to 22,
            218 to 26,
            212 to 170,
            38 to 166,
        )
        val image = frame(expected, documentDelta = 105) { x, _ ->
            30 + if (x >= 128) 45 else 0
        }

        val detected = detectDocumentQuad(image)

        assertQuadNear(expected, detected, tolerance = .08)
    }

    @Test
    fun detectsDocumentInRealCameraFixture() {
        val bytes = requireNotNull(
            javaClass.getResourceAsStream("/scanner-v2/music-page-256x376.gray"),
        ).use { it.readBytes() }
        val detected = detectDocumentQuad(LumaFrame(256, 376, bytes))
        val expected = listOf(
            NormalizedPoint(.102, .146),
            NormalizedPoint(.873, .154),
            NormalizedPoint(.883, .918),
            NormalizedPoint(.080, .917),
        )

        requireNotNull(detected)
        val actual = listOf(detected.topLeft, detected.topRight, detected.bottomRight, detected.bottomLeft)
        println("Scanner v2 real fixture quad=$actual")
        expected.zip(actual).forEach { (wanted, found) ->
            assertTrue("x ${found.x} is not near ${wanted.x}", kotlin.math.abs(wanted.x - found.x) <= .08)
            assertTrue("y ${found.y} is not near ${wanted.y}", kotlin.math.abs(wanted.y - found.y) <= .08)
        }
    }

    @Test
    fun uniformAndRandomNoiseDoNotProduceDocument() {
        val uniform = LumaFrame(256, 192, ByteArray(256 * 192) { 100.toByte() })
        val random = Random(7)
        val noise = LumaFrame(256, 192, ByteArray(256 * 192) { random.nextInt(256).toByte() })

        assertNull(detectDocumentQuad(uniform))
        assertNull(detectDocumentQuad(noise))
    }

    @Test
    fun invalidAndOversizedAnalysisFramesAreRejected() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            LumaFrame(31, 192, ByteArray(31 * 192))
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            LumaFrame(1025, 192, ByteArray(1025 * 192))
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            LumaFrame(256, 192, ByteArray(1))
        }
    }

    @Test
    fun copiesPaddedAndInterleavedLumaPlanesExactly() {
        val width = 32
        val height = 32
        val rowStride = 72
        val pixelStride = 2
        val source = ByteArray(rowStride * height) { 0x7f }
        for (y in 0 until height) {
            for (x in 0 until width) {
                source[y * rowStride + x * pixelStride] = (x + y).toByte()
            }
        }

        val frame = copyScannerV2LumaPlane(
            width = width,
            height = height,
            rowStride = rowStride,
            pixelStride = pixelStride,
            source = ByteBuffer.wrap(source),
        )

        assertEquals(0, frame.unsignedPixel(0))
        assertEquals(31, frame.unsignedPixel(31))
        assertEquals(31, frame.unsignedPixel(31 * width))
        assertEquals(62, frame.unsignedPixel(width * height - 1))
    }

    @Test
    fun rejectsShortOrInvalidLumaPlanes() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            copyScannerV2LumaPlane(32, 32, 31, 1, ByteBuffer.allocate(32 * 32))
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            copyScannerV2LumaPlane(32, 32, 32, 0, ByteBuffer.allocate(32 * 32))
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            copyScannerV2LumaPlane(32, 32, 32, 1, ByteBuffer.allocate(32 * 32 - 1))
        }
    }

    @Test
    fun rotatesAnalysisQuadIntoDisplayCoordinates() {
        val source = PageQuad.create(
            topLeft = NormalizedPoint(.1, .2),
            topRight = NormalizedPoint(.8, .1),
            bottomRight = NormalizedPoint(.9, .7),
            bottomLeft = NormalizedPoint(.2, .8),
        )

        val rotated0 = rotateScannerV2AnalysisQuad(source, 0)
        val rotated90 = rotateScannerV2AnalysisQuad(source, 90)
        val rotated180 = rotateScannerV2AnalysisQuad(source, 180)
        val rotated270 = rotateScannerV2AnalysisQuad(source, 270)

        assertPoint(.1, .2, rotated0.topLeft)
        assertPoint(.8, .1, rotated0.topRight)
        assertPoint(.9, .7, rotated0.bottomRight)
        assertPoint(.2, .8, rotated0.bottomLeft)
        assertPoint(.2, .2, rotated90.topLeft)
        assertPoint(.8, .1, rotated90.topRight)
        assertPoint(.9, .8, rotated90.bottomRight)
        assertPoint(.3, .9, rotated90.bottomLeft)
        assertPoint(.1, .3, rotated180.topLeft)
        assertPoint(.8, .2, rotated180.topRight)
        assertPoint(.9, .8, rotated180.bottomRight)
        assertPoint(.2, .9, rotated180.bottomLeft)
        assertPoint(.1, .2, rotated270.topLeft)
        assertPoint(.7, .1, rotated270.topRight)
        assertPoint(.8, .8, rotated270.bottomRight)
        assertPoint(.2, .9, rotated270.bottomLeft)
    }

    @Test
    fun rejectsUnsupportedAnalysisRotation() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            rotateScannerV2AnalysisQuad(PageQuad.fullFrame(), 45)
        }
    }

    @Test
    fun detectorMeetsTenFramesPerSecondBudget() {
        val expected = pixels(
            64 to 15,
            230 to 48,
            192 to 176,
            26 to 134,
        )
        val image = frame(expected, documentDelta = 150)
        repeat(10) { requireNotNull(detectDocumentQuad(image)) }

        val durations = LongArray(100) {
            val started = System.nanoTime()
            requireNotNull(detectDocumentQuad(image))
            System.nanoTime() - started
        }.sorted()
        val medianMs = durations[durations.size / 2] / 1_000_000.0
        val p95Ms = durations[94] / 1_000_000.0

        println("Scanner v2 detector 256x192 median=${medianMs}ms p95=${p95Ms}ms")
        assertTrue("Detector p95 ${p95Ms}ms exceeds 10 fps budget", p95Ms < 100.0)
    }

    @Test
    fun cameraAnalysisFrameMeetsThreeFramesPerSecondBudget() {
        val image = frame(
            width = 320,
            height = 240,
            polygon = pixels(
                48 to 24,
                272 to 24,
                272 to 216,
                48 to 216,
            ),
            documentDelta = 150,
        )
        repeat(3) { requireNotNull(detectDocumentQuad(image)) }

        val durations = LongArray(20) {
            val started = System.nanoTime()
            requireNotNull(detectDocumentQuad(image))
            System.nanoTime() - started
        }.sorted()
        val p95Ms = durations[18] / 1_000_000.0

        println("Scanner v2 detector 320x240 p95=${p95Ms}ms")
        assertTrue("Detector p95 ${p95Ms}ms exceeds 3 fps budget", p95Ms < 300.0)
    }

    private fun frame(
        polygon: List<Pair<Int, Int>>,
        documentDelta: Int,
        background: (Int, Int) -> Int = { _, _ -> 35 },
    ): LumaFrame = frame(256, 192, polygon, documentDelta, background)

    private fun frame(
        width: Int,
        height: Int,
        polygon: List<Pair<Int, Int>>,
        documentDelta: Int,
        background: (Int, Int) -> Int = { _, _ -> 35 },
    ): LumaFrame {
        val pixels = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val base = background(x, y).coerceIn(0, 255)
                val value = if (insidePolygon(x + .5, y + .5, polygon)) {
                    base + documentDelta
                } else {
                    base
                }
                pixels[y * width + x] = value.coerceIn(0, 255).toByte()
            }
        }
        return LumaFrame(width, height, pixels)
    }

    private fun assertQuadNear(
        expectedPixels: List<Pair<Int, Int>>,
        actual: PageQuad?,
        tolerance: Double = .05,
    ) {
        requireNotNull(actual)
        val expected = expectedPixels.map { (x, y) ->
            NormalizedPoint(x / 255.0, y / 191.0)
        }
        val actualPoints = listOf(actual.topLeft, actual.topRight, actual.bottomRight, actual.bottomLeft)
        expected.zip(actualPoints).forEach { (wanted, found) ->
            assertTrue("x ${found.x} is not near ${wanted.x}", kotlin.math.abs(wanted.x - found.x) <= tolerance)
            assertTrue("y ${found.y} is not near ${wanted.y}", kotlin.math.abs(wanted.y - found.y) <= tolerance)
        }
    }

    private fun pixels(vararg points: Pair<Int, Int>): List<Pair<Int, Int>> = points.toList()

    private fun assertPoint(x: Double, y: Double, actual: NormalizedPoint) {
        assertEquals(x, actual.x, .0001)
        assertEquals(y, actual.y, .0001)
    }

    private fun insidePolygon(
        x: Double,
        y: Double,
        polygon: List<Pair<Int, Int>>,
    ): Boolean {
        var inside = false
        var previous = polygon.last()
        for (current in polygon) {
            val crosses = (current.second > y) != (previous.second > y) &&
                x < (previous.first - current.first) * (y - current.second) /
                (previous.second - current.second) + current.first
            if (crosses) inside = !inside
            previous = current
        }
        return inside
    }
}
