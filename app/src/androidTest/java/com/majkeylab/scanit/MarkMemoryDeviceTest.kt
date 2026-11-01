package com.majkeylab.scanit

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Debug
import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkMemoryDeviceTest {
    @Test
    fun normalizationCropsOffsetRowsWithoutChangingPixels() {
        val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.MAGENTA, Color.CYAN)
        val source = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888)
        source.setPixels(colors, 0, 3, 1, 1, 3, 2)
        try {
            val cropped = normalizeMarkBitmap(source)
            try {
                assertEquals(3, cropped.width)
                assertEquals(2, cropped.height)
                val actual = IntArray(6)
                cropped.getPixels(actual, 0, 3, 0, 0, 3, 2)
                assertArrayEquals(colors, actual)
                assertEquals(Color.TRANSPARENT, source.getPixel(0, 0))
                assertEquals(Color.CYAN, source.getPixel(3, 2))
            } finally {
                cropped.recycle()
            }
        } finally {
            source.recycle()
        }
    }

    @Test
    fun largestMarkNormalizesWithoutChangingSourceOrLosingTransparency() {
        val source = Bitmap.createBitmap(2048, 2048, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.BLACK)
        source.setPixel(0, 0, Color.TRANSPARENT)
        source.setPixel(1000, 1000, Color.TRANSPARENT)
        try {
            repeat(3) { pass ->
                val allocated = Debug.getRuntimeStat("art.gc.bytes-allocated").toLong()
                val started = SystemClock.elapsedRealtime()
                val normalized = normalizeMarkBitmap(source)
                try {
                    val bytes = Debug.getRuntimeStat("art.gc.bytes-allocated").toLong() - allocated
                    InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                        putString("stream", "mark pass=$pass javaAllocatedBytes=$bytes elapsedMs=${SystemClock.elapsedRealtime() - started}\n")
                    })
                    assertEquals(1024, normalized.width)
                    assertEquals(1024, normalized.height)
                    assertEquals(Color.BLACK, normalized.getPixel(900, 900))
                    assertTrue(Color.alpha(normalized.getPixel(500, 500)) < 255)
                    assertEquals(Color.TRANSPARENT, source.getPixel(1000, 1000))
                    assertEquals(Color.BLACK, source.getPixel(1900, 1900))
                } finally {
                    normalized.recycle()
                }
            }
        } finally {
            source.recycle()
        }
    }
}
