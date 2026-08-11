package com.majkeylab.scanit

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InternalGeminiHarnessTest {
    @Test
    fun apiKeyIsTrimmedAndBounded() {
        assertEquals("key", normalizeInternalGeminiApiKey("  key  "))
        assertThrows(IllegalArgumentException::class.java) {
            normalizeInternalGeminiApiKey("k".repeat(4097))
        }
        assertThrows(IllegalArgumentException::class.java) {
            normalizeInternalGeminiApiKey("bad\nkey")
        }
    }

    @Test
    fun selectedImageCopyAcceptsLimitAndRejectsOneMoreByte() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val output = ByteArrayOutputStream()

        assertEquals(4L, copyInternalGeminiInput(ByteArrayInputStream(bytes), output, 4L))
        assertArrayEquals(bytes, output.toByteArray())
        assertThrows(IOException::class.java) {
            copyInternalGeminiInput(ByteArrayInputStream(bytes), ByteArrayOutputStream(), 3L)
        }
    }

    @Test
    fun previewSamplingBoundsAllocationAndRejectsHostileDimensions() {
        assertEquals(1, internalGeminiPreviewSampleSize(2048, 1536))
        assertEquals(4, internalGeminiPreviewSampleSize(6000, 4000))
        assertThrows(IOException::class.java) {
            internalGeminiPreviewSampleSize(8193, 1)
        }
        assertThrows(IOException::class.java) {
            internalGeminiPreviewSampleSize(8192, 4097)
        }
    }

    @Test
    fun brokenOrOrphanedEncryptedKeyMaterialCanBeCleared() {
        assertFalse(
            internalGeminiKeyCanClear(
                keyLoaded = false,
                encryptedMaterialPresent = false,
                loadFailed = false,
            ),
        )
        assertTrue(
            internalGeminiKeyCanClear(
                keyLoaded = false,
                encryptedMaterialPresent = false,
                loadFailed = true,
            ),
        )
        assertTrue(
            internalGeminiKeyCanClear(
                keyLoaded = false,
                encryptedMaterialPresent = true,
                loadFailed = false,
            ),
        )
    }
}
