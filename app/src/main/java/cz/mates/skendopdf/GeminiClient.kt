package cz.mates.skendopdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

private const val GEMINI_ENDPOINT = "https://generativelanguage.googleapis.com/v1/interactions"
private const val GEMINI_MODEL = "gemini-3.1-flash-image"
private const val GEMINI_MAX_IMAGE_SIDE = 2048
private const val GEMINI_JPEG_QUALITY = 90
private const val GEMINI_MAX_UPLOAD_BYTES = 7 * 1024 * 1024
private const val GEMINI_MAX_RESPONSE_BYTES = 12 * 1024 * 1024
private const val GEMINI_CONNECT_TIMEOUT_MS = 30_000
private const val GEMINI_READ_TIMEOUT_MS = 180_000
private const val GEMINI_PROMPT =
    "Remove only fingers and objects outside the document or on blank margins. " +
        "Preserve every text glyph, number, signature, stamp, color, line and layout. " +
        "If preservation is uncertain, return the image unchanged."

@Throws(IOException::class)
internal suspend fun requestGeminiCleanup(page: File, apiKey: String): ByteArray =
    withContext(Dispatchers.IO) {
        val key = apiKey.trim()
        require(key.isNotEmpty() && '\r' !in key && '\n' !in key) { "Gemini API key is invalid" }
        val request =
            buildGeminiCleanupRequest(encodeGeminiUpload(page))
                .toString()
                .toByteArray(StandardCharsets.UTF_8)
        val connection =
            URI(GEMINI_ENDPOINT).toURL().openConnection() as? HttpsURLConnection
                ?: throw IOException("Gemini endpoint is not HTTPS")

        suspendCancellableCoroutine { continuation ->
            val cancelled = AtomicBoolean(false)
            continuation.invokeOnCancellation {
                cancelled.set(true)
                connection.disconnect()
            }
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = GEMINI_CONNECT_TIMEOUT_MS
                connection.readTimeout = GEMINI_READ_TIMEOUT_MS
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("x-goog-api-key", key)
                connection.setFixedLengthStreamingMode(request.size)
                if (cancelled.get()) return@suspendCancellableCoroutine
                connection.connect()
                if (cancelled.get()) return@suspendCancellableCoroutine
                connection.outputStream.use { it.write(request) }

                val status = connection.responseCode
                if (status !in 200..299) {
                    connection.errorStream?.close()
                    throw IOException("Gemini request failed with HTTP $status")
                }
                if (connection.contentLengthLong > GEMINI_MAX_RESPONSE_BYTES) {
                    throw IOException("Gemini response is too large")
                }
                val response =
                    connection.inputStream.use {
                        readGeminiResponse(it, GEMINI_MAX_RESPONSE_BYTES)
                    }
                val image =
                    try {
                        parseGeminiImageResponse(String(response, StandardCharsets.UTF_8))
                    } catch (exception: IllegalArgumentException) {
                        throw IOException("Gemini response is invalid", exception)
                    }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    throw IOException("Gemini response image is invalid")
                }
                continuation.resume(image) { _, _, _ -> }
            } catch (failure: Throwable) {
                if (!cancelled.get()) continuation.resumeWithException(failure)
            } finally {
                connection.disconnect()
            }
        }
    }

internal fun buildGeminiCleanupRequest(jpeg: ByteArray): JSONObject {
    require(jpeg.isNotEmpty()) { "Gemini upload is empty" }
    require(jpeg.size <= GEMINI_MAX_UPLOAD_BYTES) { "Gemini upload is too large" }
    return JSONObject()
        .put("model", GEMINI_MODEL)
        .put("store", false)
        .put(
            "input",
            JSONArray()
                .put(
                    JSONObject()
                        .put("type", "image")
                        .put("mime_type", "image/jpeg")
                        .put("data", Base64.getEncoder().encodeToString(jpeg)),
                )
                .put(JSONObject().put("type", "text").put("text", GEMINI_PROMPT)),
        )
        .put(
            "response_format",
            JSONObject()
                .put("type", "image")
                .put("mime_type", "image/jpeg")
                .put("delivery", "inline")
                .put("image_size", "2K"),
        )
}

internal fun geminiUploadDimensions(
    width: Int,
    height: Int,
): Pair<Int, Int> {
    require(width > 0 && height > 0) { "Image dimensions must be positive" }
    val longest = maxOf(width, height)
    if (longest <= GEMINI_MAX_IMAGE_SIDE) {
        return width to height
    }
    val scale = GEMINI_MAX_IMAGE_SIDE.toDouble() / longest
    return maxOf(1, (width * scale).roundToInt()) to
        maxOf(1, (height * scale).roundToInt())
}

@Throws(IOException::class)
internal fun readGeminiResponse(input: InputStream, maxBytes: Int): ByteArray {
    require(maxBytes > 0) { "Maximum response size must be positive" }
    val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
    val buffer = ByteArray(8 * 1024)
    while (true) {
        val read = input.read(buffer)
        if (read == -1) {
            return output.toByteArray()
        }
        if (output.size() > maxBytes - read) {
            throw IOException("Gemini response is too large")
        }
        output.write(buffer, 0, read)
    }
}

@Throws(IOException::class)
private fun encodeGeminiUpload(page: File): ByteArray {
    if (!page.isFile) {
        throw IOException("Gemini input page is missing")
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(page.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        throw IOException("Gemini input page is not a readable image")
    }
    val decoded =
        BitmapFactory.decodeFile(
            page.path,
            BitmapFactory.Options().apply {
                inSampleSize =
                    thumbnailSampleSize(
                        bounds.outWidth,
                        bounds.outHeight,
                        GEMINI_MAX_IMAGE_SIDE,
                    )
            },
        ) ?: throw IOException("Gemini input page could not be decoded")
    val (width, height) = geminiUploadDimensions(decoded.width, decoded.height)
    val upload =
        if (width == decoded.width && height == decoded.height) {
            decoded
        } else {
            try {
                decoded.scale(width, height)
            } finally {
                decoded.recycle()
            }
        }

    return try {
        val output = ByteArrayOutputStream()
        if (!upload.compress(Bitmap.CompressFormat.JPEG, GEMINI_JPEG_QUALITY, output)) {
            throw IOException("Gemini input page could not be encoded")
        }
        output.toByteArray().also {
            if (it.isEmpty()) {
                throw IOException("Gemini input page encoded to an empty file")
            }
            if (it.size > GEMINI_MAX_UPLOAD_BYTES) {
                throw IOException("Gemini input page is too large")
            }
        }
    } finally {
        upload.recycle()
    }
}

internal fun parseGeminiImageResponse(response: String): ByteArray {
    val root =
        try {
            JSONObject(response)
        } catch (exception: JSONException) {
            throw IllegalArgumentException("Gemini response is not valid JSON", exception)
        }
    require(root.optString("status") == "completed") {
        "Gemini response status is not completed"
    }

    val steps = root.optJSONArray("steps")
        ?: throw IllegalArgumentException("Gemini response has no steps")
    val modelOutput = steps.finalObjectOfType("model_output")
        ?: throw IllegalArgumentException("Gemini response has no model output")
    val content = modelOutput.optJSONArray("content")
        ?: throw IllegalArgumentException("Gemini model output has no content")
    val image = content.finalObjectOfType("image")
        ?: throw IllegalArgumentException("Gemini model output has no image")
    require(image.optString("mime_type") == "image/jpeg") {
        "Gemini image MIME type must be image/jpeg"
    }

    val encoded =
        try {
            image.getString("data")
        } catch (exception: JSONException) {
            throw IllegalArgumentException("Gemini image data must be a string", exception)
        }
    require(encoded.isNotEmpty()) { "Gemini image data is empty" }
    val decoded =
        try {
            Base64.getDecoder().decode(encoded)
        } catch (exception: IllegalArgumentException) {
            throw IllegalArgumentException("Gemini image data is not valid Base64", exception)
        }
    require(decoded.isNotEmpty()) { "Gemini image data is empty" }
    require(
        decoded.size >= 4 &&
            decoded[0] == 0xff.toByte() &&
            decoded[1] == 0xd8.toByte() &&
            decoded[decoded.lastIndex - 1] == 0xff.toByte() &&
            decoded[decoded.lastIndex] == 0xd9.toByte(),
    ) { "Gemini image data is not a JPEG" }
    return decoded
}

private fun JSONArray.finalObjectOfType(type: String): JSONObject? {
    for (index in length() - 1 downTo 0) {
        val item = optJSONObject(index) ?: continue
        if (item.optString("type") == type) {
            return item
        }
    }
    return null
}
