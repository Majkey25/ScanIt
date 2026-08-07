package com.majkeylab.scanit

import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class GeminiClientTest {
    @Test
    fun requestBodyUsesPrivateInlineJpegAt2k() {
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())
        val body = buildGeminiCleanupRequest(jpeg)

        assertEquals("gemini-3.1-flash-image", body.getString("model"))
        assertFalse(body.getBoolean("store"))
        val input = body.getJSONArray("input")
        assertEquals(2, input.length())
        assertEquals("image", input.getJSONObject(0).getString("type"))
        assertEquals("image/jpeg", input.getJSONObject(0).getString("mime_type"))
        assertArrayEquals(
            jpeg,
            Base64.getDecoder().decode(input.getJSONObject(0).getString("data")),
        )
        assertEquals("text", input.getJSONObject(1).getString("type"))
        val responseFormat = body.getJSONObject("response_format")
        assertEquals("image", responseFormat.getString("type"))
        assertEquals("image/jpeg", responseFormat.getString("mime_type"))
        assertEquals("inline", responseFormat.getString("delivery"))
        assertEquals("2K", responseFormat.getString("image_size"))
    }

    @Test
    fun uploadDimensionsPreserveAspectRatioAndNeverUpscale() {
        assertEquals(2048 to 1024, geminiUploadDimensions(4000, 2000))
        assertEquals(1000 to 2000, geminiUploadDimensions(1000, 2000))
        assertEquals(1 to 2048, geminiUploadDimensions(1, Int.MAX_VALUE))
    }

    @Test
    fun requestBodyRejectsOversizedUploadBeforeBase64Encoding() {
        assertThrows(IllegalArgumentException::class.java) {
            buildGeminiCleanupRequest(ByteArray(7 * 1024 * 1024 + 1))
        }
    }

    @Test
    fun boundedReadAcceptsLimitAndRejectsOneMoreByte() {
        val expected = byteArrayOf(1, 2, 3, 4)

        assertArrayEquals(expected, readGeminiResponse(ByteArrayInputStream(expected), 4))
        assertThrows(IOException::class.java) {
            readGeminiResponse(ByteArrayInputStream(expected), 3)
        }
    }
}
