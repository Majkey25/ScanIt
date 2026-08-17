package com.majkeylab.scanit

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerV2CaptureQualityTest {
    @Test
    fun exposureWarningsTakePriorityOverBlur() {
        val dark = LumaFrame(64, 64, ByteArray(64 * 64) { 15 })
        val bright = LumaFrame(64, 64, ByteArray(64 * 64) { 250.toByte() })

        assertEquals(setOf(ScannerV2CaptureQualityIssue.TooDark), analyzeScannerV2CaptureQuality(dark))
        assertEquals(setOf(ScannerV2CaptureQualityIssue.Overexposed), analyzeScannerV2CaptureQuality(bright))
    }

    @Test
    fun blurWarnsButSharpDetailDoesNot() {
        val blurred = LumaFrame(64, 64, ByteArray(64 * 64) { index ->
            (110 + index % 64 / 8).toByte()
        })
        val sharp = LumaFrame(64, 64, ByteArray(64 * 64) { index ->
            val x = index % 64
            val y = index / 64
            if ((x / 4 + y / 4) % 2 == 0) 35.toByte() else 220.toByte()
        })

        assertEquals(setOf(ScannerV2CaptureQualityIssue.Blurry), analyzeScannerV2CaptureQuality(blurred))
        assertTrue(analyzeScannerV2CaptureQuality(sharp).isEmpty())
    }

    @Test
    fun qualityCheckStaysInsideLiveSafetyBudget() {
        val random = Random(9)
        val frame = LumaFrame(320, 240, ByteArray(320 * 240) { random.nextInt(256).toByte() })
        val durations = LongArray(50) {
            val started = System.nanoTime()
            analyzeScannerV2CaptureQuality(frame, PageQuad.fullFrame())
            System.nanoTime() - started
        }.sorted()
        val p95Ms = durations[46] / 1_000_000.0

        assertTrue("Quality p95 ${p95Ms}ms exceeds budget", p95Ms < 10.0)
    }

    @Test
    fun realSharpFixturesDoNotShowFalseWarnings() {
        val music = requireNotNull(
            javaClass.getResourceAsStream("/scanner-v2/music-page-256x376.gray"),
        ).use { it.readBytes() }
        val television = requireNotNull(
            javaClass.getResourceAsStream("/scanner-v2/tv-current-capture-240x320.gray"),
        ).use { it.readBytes() }

        assertTrue(analyzeScannerV2CaptureQuality(LumaFrame(256, 376, music)).isEmpty())
        assertTrue(analyzeScannerV2CaptureQuality(LumaFrame(240, 320, television)).isEmpty())
    }

    @Test
    fun exposureIsMeasuredInsideTheDetectedPage() {
        val width = 128
        val height = 128
        val pixels = ByteArray(width * height) { 240.toByte() }
        for (y in 32 until 96) {
            for (x in 32 until 96) pixels[y * width + x] = 15
        }
        val page = PageQuad.create(
            NormalizedPoint(.25, .25),
            NormalizedPoint(.75, .25),
            NormalizedPoint(.75, .75),
            NormalizedPoint(.25, .75),
        )

        assertEquals(
            setOf(ScannerV2CaptureQualityIssue.TooDark),
            analyzeScannerV2CaptureQuality(LumaFrame(width, height, pixels), page),
        )
    }
}
