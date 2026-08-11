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

    @Test
    fun validResponseUsesFinalModelOutputImage() {
        val oldBytes =
            byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0x01, 0xff.toByte(), 0xd9.toByte())
        val expected =
            byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0x02, 0xff.toByte(), 0xd9.toByte())
        val old = Base64.getEncoder().encodeToString(oldBytes)
        val current = Base64.getEncoder().encodeToString(expected)
        val response =
            """
            {
              "status": "completed",
              "steps": [
                {"type": "model_output", "content": [
                  {"type": "image", "mime_type": "image/jpeg", "data": "$old"}
                ]},
                {"type": "thought"},
                {"type": "model_output", "content": [
                  {"type": "text", "text": "done"},
                  {"type": "image", "mime_type": "image/jpeg", "data": "$current"}
                ]}
              ]
            }
            """.trimIndent()

        assertArrayEquals(expected, parseGeminiImageResponse(response))
    }

    @Test
    fun responseWithoutImageIsRejected() {
        val response =
            """
            {"status":"completed","steps":[
              {"type":"model_output","content":[{"type":"text","text":"none"}]}
            ]}
            """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            parseGeminiImageResponse(response)
        }
    }

    @Test
    fun responseWithInvalidBase64IsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            parseGeminiImageResponse(imageResponse(data = "not base64!"))
        }
    }

    @Test
    fun responseWithNullImageDataIsRejected() {
        val response =
            """
            {"status":"completed","steps":[
              {"type":"model_output","content":[
                {"type":"image","mime_type":"image/jpeg","data":null}
              ]}
            ]}
            """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            parseGeminiImageResponse(response)
        }
    }

    @Test
    fun responseWithNonStringImageDataIsRejected() {
        val response =
            """
            {"status":"completed","steps":[
              {"type":"model_output","content":[
                {"type":"image","mime_type":"image/jpeg","data":1234}
              ]}
            ]}
            """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            parseGeminiImageResponse(response)
        }
    }

    @Test
    fun responseWithBase64NonJpegIsRejected() {
        val data = Base64.getEncoder().encodeToString("not-jpeg".toByteArray())

        assertThrows(IllegalArgumentException::class.java) {
            parseGeminiImageResponse(imageResponse(data = data))
        }
    }

    @Test
    fun responseWithEmptyImageIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            parseGeminiImageResponse(imageResponse(data = ""))
        }
    }

    @Test
    fun responseWithWrongMimeIsRejected() {
        val data = Base64.getEncoder().encodeToString("png".toByteArray())

        assertThrows(IllegalArgumentException::class.java) {
            parseGeminiImageResponse(imageResponse(mimeType = "image/png", data = data))
        }
    }

    @Test
    fun incompleteResponseIsRejected() {
        val data = Base64.getEncoder().encodeToString("jpeg".toByteArray())

        assertThrows(IllegalArgumentException::class.java) {
            parseGeminiImageResponse(imageResponse(status = "in_progress", data = data))
        }
    }

    private fun imageResponse(
        status: String = "completed",
        mimeType: String = "image/jpeg",
        data: String,
    ): String =
        """
        {"status":"$status","steps":[
          {"type":"model_output","content":[
            {"type":"image","mime_type":"$mimeType","data":"$data"}
          ]}
        ]}
        """.trimIndent()
}
