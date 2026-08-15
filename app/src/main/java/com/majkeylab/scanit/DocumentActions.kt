package com.majkeylab.scanit

import android.content.ClipDescription
import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.URI
import java.net.URISyntaxException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine

internal const val MAX_DOCUMENT_TEXT_CHARACTERS = 200_000
internal const val MAX_DETECTED_CODES = 64
internal const val MAX_DETECTED_CODE_CHARACTERS = 4_096
internal const val MAX_TEXT_EXPORT_FILE_NAME_LENGTH = 96
private const val MAX_DETECTED_CODE_TOTAL_CHARACTERS = 65_536
private const val MAX_DETECTED_CODE_BYTES = MAX_DETECTED_CODE_CHARACTERS * 4
private const val MAX_DOCUMENT_ACTION_IMAGE_BYTES = 64L * 1024L * 1024L
private const val MAX_OPENABLE_HTTP_URL_CHARACTERS = 4_096
private const val TEXT_EXPORT_SUFFIX = "_text.txt"

internal enum class DetectedCodeKind {
    QrCode,
    Barcode,
}

internal data class DetectedCode(
    val kind: DetectedCodeKind,
    val value: String,
    val openableHttpUrl: String?,
)

internal data class SensitiveClipboardExtra(
    val key: String,
    val value: Boolean,
)

internal fun documentClipboardSensitiveExtra(): SensitiveClipboardExtra =
    SensitiveClipboardExtra(ClipDescription.EXTRA_IS_SENSITIVE, true)

internal fun buildDocumentText(
    pageTexts: List<String>,
    maxCharacters: Int = MAX_DOCUMENT_TEXT_CHARACTERS,
    pageLabel: (Int) -> String = { page -> "Page $page" },
): DocumentActionOutput.Text {
    val accumulator = DocumentTextAccumulator(maxCharacters, pageLabel)
    for ((index, pageText) in pageTexts.withIndex()) {
        if (!accumulator.appendPage(index + 1, pageText)) break
    }
    return accumulator.result()
}

private class DocumentTextAccumulator(
    private val maxCharacters: Int,
    private val pageLabel: (Int) -> String,
) {
    private val output: StringBuilder
    private var truncated = false

    init {
        require(maxCharacters in 1..MAX_DOCUMENT_TEXT_CHARACTERS) {
            "Document text limit is invalid"
        }
        output = StringBuilder(minOf(maxCharacters, 8_192))
    }

    fun appendPage(pageNumber: Int, pageText: String): Boolean {
        if (truncated) return false
        val text = pageText.trim()
        if (text.isEmpty()) return true
        if (output.isNotEmpty()) appendBounded("\n\n")
        if (!truncated) appendBounded(pageLabel(pageNumber))
        if (!truncated) appendBounded("\n")
        if (!truncated) appendBounded(text)
        return !truncated
    }

    fun result(): DocumentActionOutput.Text =
        DocumentActionOutput.Text(output.toString(), truncated)

    private fun appendBounded(value: String) {
        val remaining = maxCharacters - output.length
        if (value.length <= remaining) {
            output.append(value)
        } else {
            output.append(value, 0, remaining)
            truncated = true
        }
    }
}

internal fun validatedDetectedCode(
    rawValue: String?,
    displayValue: String?,
    rawBytes: ByteArray?,
    isQrCode: Boolean,
    isUrlType: Boolean,
): DetectedCode? {
    val decodedBytes =
        rawBytes?.let { bytes ->
            if (bytes.isEmpty() || bytes.size > MAX_DETECTED_CODE_BYTES) return null
            decodeStrictUtf8(bytes) ?: return null
        }
    val value = (rawValue ?: decodedBytes ?: displayValue)?.trim()?.takeIf(String::isNotEmpty)
        ?: return null
    if (value.length > MAX_DETECTED_CODE_CHARACTERS || strictUtf8Bytes(value) == null) return null
    if (decodedBytes != null && decodedBytes.trim() != value) return null
    return DetectedCode(
        kind = if (isQrCode) DetectedCodeKind.QrCode else DetectedCodeKind.Barcode,
        value = value,
        openableHttpUrl = if (isUrlType) validatedHttpUrl(value) else null,
    )
}

internal fun buildDetectedCodes(
    candidates: Sequence<DetectedCode>,
    maxCodes: Int = MAX_DETECTED_CODES,
    maxCharacters: Int = MAX_DETECTED_CODE_TOTAL_CHARACTERS,
): DocumentActionOutput.Codes {
    require(maxCodes in 1..MAX_DETECTED_CODES) { "Detected-code limit is invalid" }
    require(maxCharacters in 1..MAX_DETECTED_CODE_TOTAL_CHARACTERS) {
        "Detected-code character limit is invalid"
    }
    val values = ArrayList<DetectedCode>(maxCodes)
    val seen = HashSet<Pair<DetectedCodeKind, String>>(maxCodes)
    var characters = 0
    val iterator = candidates.iterator()
    while (values.size < maxCodes && iterator.hasNext()) {
        val candidate = iterator.next()
        if (
            candidate.value.isEmpty() ||
                candidate.value.length > MAX_DETECTED_CODE_CHARACTERS ||
                strictUtf8Bytes(candidate.value) == null ||
                !seen.add(candidate.kind to candidate.value)
        ) {
            continue
        }
        if (candidate.value.length > maxCharacters - characters) break
        val openableUrl =
            candidate.openableHttpUrl
                ?.let(::validatedHttpUrl)
                ?.takeIf { it == candidate.value }
        values += candidate.copy(openableHttpUrl = openableUrl)
        characters += candidate.value.length
    }
    return DocumentActionOutput.Codes(values)
}

internal fun validatedHttpUrl(raw: String): String? {
    val value = raw.trim()
    if (
        value.isEmpty() ||
            value.length > MAX_OPENABLE_HTTP_URL_CHARACTERS ||
            strictUtf8Bytes(value) == null ||
            value.any(Char::isUnsafeUriDisplayCharacter)
    ) {
        return null
    }
    val uri =
        try {
            URI(value)
        } catch (_: URISyntaxException) {
            return null
        }
    val scheme = uri.scheme?.lowercase() ?: return null
    if (
        (scheme != "http" && scheme != "https") ||
            uri.isOpaque ||
            uri.host.isNullOrBlank() ||
            uri.rawUserInfo != null ||
            uri.port > 65_535
    ) {
        return null
    }
    return value
}

internal fun sanitizeTextExportFileName(baseName: String): String {
    val maxStemLength = MAX_TEXT_EXPORT_FILE_NAME_LENGTH - TEXT_EXPORT_SUFFIX.length
    val stem = StringBuilder(maxStemLength)
    var previousUnderscore = false
    baseName.trim().forEach { character ->
        if (stem.length >= maxStemLength) return@forEach
        val safe =
            when {
                character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' ->
                    character
                character == '-' || character == '_' -> character
                else -> '_'
            }
        if (safe != '_' || !previousUnderscore) stem.append(safe)
        previousUnderscore = safe == '_'
    }
    val cleaned = stem.toString().trim('_', '-')
    return "${cleaned.ifEmpty { "ScanIt" }}$TEXT_EXPORT_SUFFIX"
}

internal fun writeDocumentTextUtf8(
    output: OutputStream,
    text: String,
    isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
) {
    require(text.isNotEmpty() && text.length <= MAX_DOCUMENT_TEXT_CHARACTERS) {
        "Document text is empty or too large"
    }
    if (isCancelled()) throw CancellationException("Text export was cancelled")
    val bytes = strictUtf8Bytes(text) ?: throw IllegalArgumentException("Document text is invalid")
    if (isCancelled()) throw CancellationException("Text export was cancelled")
    output.write(bytes)
    output.flush()
    if (isCancelled()) throw CancellationException("Text export was cancelled")
}

internal fun isSafeTextExportDestination(
    scheme: String?,
    authority: String?,
    path: String?,
    query: String?,
    fragment: String?,
    uriLength: Int,
    mimeType: String?,
    writeGranted: Boolean,
): Boolean =
    scheme == "content" &&
        !authority.isNullOrBlank() &&
        authority.length <= 255 &&
        authority.all(Char::isSafeContentAuthorityCharacter) &&
        !path.isNullOrBlank() &&
        path.startsWith('/') &&
        path.length <= 2_048 &&
        path.none(Char::isUnsafeUriDisplayCharacter) &&
        query == null &&
        fragment == null &&
        uriLength in 1..4_096 &&
        writeGranted &&
        (mimeType == null || mimeType.equals("text/plain", ignoreCase = true))

private fun Char.isSafeContentAuthorityCharacter(): Boolean =
    this in 'a'..'z' ||
        this in 'A'..'Z' ||
        this in '0'..'9' ||
        this == '.' ||
        this == '_' ||
        this == '-'

private fun Char.isUnsafeUriDisplayCharacter(): Boolean =
    isISOControl() ||
        when (Character.getType(this)) {
            Character.FORMAT.toInt(),
            Character.LINE_SEPARATOR.toInt(),
            Character.PARAGRAPH_SEPARATOR.toInt(),
            -> true
            else -> false
        }

private fun strictUtf8Bytes(value: String): ByteArray? =
    try {
        val encoded =
            StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value))
        ByteArray(encoded.remaining()).also(encoded::get)
    } catch (_: CharacterCodingException) {
        null
    }

private fun decodeStrictUtf8(bytes: ByteArray): String? =
    try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

internal class DocumentActionProcessor(private val context: Context) {
    suspend fun extractText(pages: List<File>): DocumentActionOutput.Text {
        require(pages.size in 1..MAX_SCAN_PAGES) { "Text extraction page count is invalid" }
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val accumulator =
                DocumentTextAccumulator(MAX_DOCUMENT_TEXT_CHARACTERS) { page ->
                    context.getString(R.string.document_action_page, page)
                }
            for ((index, page) in pages.withIndex()) {
                currentCoroutineContext().ensureActive()
                val pageText = recognizer.process(inputImage(page)).awaitResult().text
                if (!accumulator.appendPage(index + 1, pageText)) break
            }
            accumulator.result()
        } finally {
            recognizer.close()
        }
    }

    suspend fun detectCodes(page: File): DocumentActionOutput.Codes {
        val scanner = BarcodeScanning.getClient()
        return try {
            buildDetectedCodes(
                scanner.process(inputImage(page)).awaitResult().asSequence().mapNotNull { barcode ->
                    validatedDetectedCode(
                        rawValue = barcode.rawValue,
                        displayValue = barcode.displayValue,
                        rawBytes = barcode.rawBytes,
                        isQrCode = barcode.format == Barcode.FORMAT_QR_CODE,
                        isUrlType = barcode.valueType == Barcode.TYPE_URL,
                    )
                },
            )
        } finally {
            scanner.close()
        }
    }

    private fun inputImage(file: File): InputImage {
        val page = file.absoluteFile
        if (
            page.canonicalFile != page ||
                !page.isFile ||
                page.length() !in 1..MAX_DOCUMENT_ACTION_IMAGE_BYTES
        ) {
            throw IOException("Scan page is unavailable")
        }
        val dimensions = readJpegDimensions(page)
        if (
            dimensions.width > MAX_IMAGE_EXPORT_DIMENSION ||
                dimensions.height > MAX_IMAGE_EXPORT_DIMENSION ||
                dimensions.width.toLong() * dimensions.height > MAX_IMAGE_EXPORT_PIXELS
        ) {
            throw IOException("Scan page dimensions are invalid")
        }
        return InputImage.fromFilePath(context, Uri.fromFile(page))
    }
}

private suspend fun <T> Task<T>.awaitResult(): T =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result ->
            if (continuation.isActive) continuation.resume(result)
        }
        addOnFailureListener { failure ->
            if (continuation.isActive) continuation.resumeWithException(failure)
        }
        addOnCanceledListener {
            if (continuation.isActive) continuation.cancel(CancellationException("ML task cancelled"))
        }
    }
