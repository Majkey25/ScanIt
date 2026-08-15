package com.majkeylab.scanit

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine

private const val MAX_DOCUMENT_TEXT_CHARACTERS = 200_000
private const val MAX_DETECTED_CODES = 64

internal fun buildDocumentText(
    pageTexts: List<String>,
    maxCharacters: Int = MAX_DOCUMENT_TEXT_CHARACTERS,
    pageLabel: (Int) -> String = { page -> "Page $page" },
): DocumentActionOutput.Text {
    require(maxCharacters > 0) { "Document text limit must be positive" }
    val output = StringBuilder(minOf(maxCharacters, 8_192))
    var truncated = false
    fun appendBounded(value: String) {
        val remaining = maxCharacters - output.length
        if (value.length <= remaining) {
            output.append(value)
        } else {
            output.append(value, 0, remaining)
            truncated = true
        }
    }
    pageTexts.forEachIndexed { index, pageText ->
        val text = pageText.trim()
        if (text.isEmpty() || truncated) return@forEachIndexed
        if (output.isNotEmpty()) appendBounded("\n\n")
        if (!truncated) appendBounded(pageLabel(index + 1))
        if (!truncated) appendBounded("\n")
        if (!truncated) appendBounded(text)
    }
    return DocumentActionOutput.Text(output.toString(), truncated)
}

internal class DocumentActionProcessor(private val context: Context) {
    suspend fun extractText(pages: List<File>): DocumentActionOutput.Text {
        require(pages.isNotEmpty()) { "Text extraction requires at least one page" }
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val pageTexts = ArrayList<String>(pages.size)
            pages.forEach { page ->
                currentCoroutineContext().ensureActive()
                pageTexts += recognizer.process(inputImage(page)).awaitResult().text
            }
            buildDocumentText(pageTexts) { page ->
                context.getString(R.string.document_action_page, page)
            }
        } finally {
            recognizer.close()
        }
    }

    suspend fun detectCodes(page: File): DocumentActionOutput.Codes {
        val scanner = BarcodeScanning.getClient()
        return try {
            val values =
                scanner.process(inputImage(page)).awaitResult()
                    .mapNotNull { barcode ->
                        (barcode.displayValue ?: barcode.rawValue)?.trim()?.takeIf(String::isNotEmpty)
                    }.distinct()
                    .take(MAX_DETECTED_CODES)
            DocumentActionOutput.Codes(values)
        } finally {
            scanner.close()
        }
    }

    private fun inputImage(file: File): InputImage {
        if (!file.isFile) throw IOException("Scan page is unavailable")
        return InputImage.fromFilePath(context, Uri.fromFile(file))
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
