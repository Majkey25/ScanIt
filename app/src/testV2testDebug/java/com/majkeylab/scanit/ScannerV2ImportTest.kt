package com.majkeylab.scanit

import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScannerV2ImportTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun importCopyPublishesExactBytes() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val destination = temporary.newFile("destination").also { assertTrue(it.delete()) }

        val length = copyScannerV2ImportSource(
            input = ByteArrayInputStream(bytes),
            destination = destination,
            maxBytes = 10,
        )

        assertEquals(bytes.size.toLong(), length)
        assertArrayEquals(bytes, destination.readBytes())
    }

    @Test
    fun oversizedImportDeletesPartialDestination() {
        val destination = temporary.newFile("destination").also { assertTrue(it.delete()) }

        assertThrows(IOException::class.java) {
            copyScannerV2ImportSource(
                input = ByteArrayInputStream(ByteArray(11)),
                destination = destination,
                maxBytes = 10,
            )
        }

        assertFalse(destination.exists())
    }

    @Test
    fun cancelledImportDeletesPartialDestination() {
        val destination = temporary.newFile("destination").also { assertTrue(it.delete()) }

        assertThrows(CancellationException::class.java) {
            copyScannerV2ImportSource(
                input = ByteArrayInputStream(ByteArray(8)),
                destination = destination,
                maxBytes = 10,
                isCancelled = { true },
            )
        }

        assertFalse(destination.exists())
    }

    @Test
    fun onlySupportedStillImageFormatsAreAccepted() {
        assertTrue(isSupportedScannerV2ImportMimeType("image/jpeg"))
        assertTrue(isSupportedScannerV2ImportMimeType("image/png"))
        assertTrue(isSupportedScannerV2ImportMimeType("image/webp"))
        assertFalse(isSupportedScannerV2ImportMimeType("image/gif"))
        assertFalse(isSupportedScannerV2ImportMimeType(null))
    }
}
