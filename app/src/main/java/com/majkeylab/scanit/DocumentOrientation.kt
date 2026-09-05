package com.majkeylab.scanit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.abs

private const val MIN_ORIENTATION_CHARACTERS = 6
private const val MIN_ORIENTATION_CHARACTER_ADVANTAGE = 4
private const val MIN_ORIENTATION_CHARACTER_RATIO = 0.65f
private const val MAX_LINE_ANGLE_ERROR = 30f
internal const val MAX_ORIENTATION_ANALYSIS_PIXELS = 1_048_576L
private val PHYSICAL_DOCUMENT_ORIENTATIONS =
    setOf(
        ImageExifOrientation.Normal,
        ImageExifOrientation.Rotate90,
        ImageExifOrientation.Rotate180,
        ImageExifOrientation.Rotate270,
    )

internal data class DocumentLineOrientationEvidence(
    val angleDegrees: Float,
    val readableCharacters: Int,
) {
    init {
        require(angleDegrees.isFinite() && angleDegrees in -180f..180f) {
            "Document line angle is invalid"
        }
        require(readableCharacters >= 0) { "Document character count must not be negative" }
    }
}

internal fun selectDocumentOrientation(
    evidence: List<DocumentLineOrientationEvidence>,
    imageWidth: Int,
    imageHeight: Int,
): ImageExifOrientation {
    require(imageWidth > 0 && imageHeight > 0) { "Document dimensions must be positive" }
    val votes =
        evidence.mapNotNull { line ->
            lineCorrection(line.angleDegrees)?.let { it to line.readableCharacters }
        }.filter { (_, characters) -> characters > 0 }
            .groupingBy(Pair<ImageExifOrientation, Int>::first)
            .fold(0) { characters, (_, lineCharacters) -> characters + lineCharacters }
    val totalCharacters = votes.values.sum()
    if (totalCharacters >= MIN_ORIENTATION_CHARACTERS) {
        val ranked = votes.entries.sortedByDescending(Map.Entry<ImageExifOrientation, Int>::value)
        val best = ranked.firstOrNull() ?: return ImageExifOrientation.Normal
        val runnerUpCharacters = ranked.getOrNull(1)?.value ?: 0
        return best.key.takeIf {
            best.value - runnerUpCharacters >= MIN_ORIENTATION_CHARACTER_ADVANTAGE &&
                best.value >= totalCharacters * MIN_ORIENTATION_CHARACTER_RATIO
        } ?: ImageExifOrientation.Normal
    }
    return ImageExifOrientation.Normal
}

private fun lineCorrection(angleDegrees: Float): ImageExifOrientation? =
    when {
        abs(angleDegrees) <= MAX_LINE_ANGLE_ERROR -> ImageExifOrientation.Normal
        abs(abs(angleDegrees) - 180f) <= MAX_LINE_ANGLE_ERROR ->
            ImageExifOrientation.Rotate180
        abs(angleDegrees - 90f) <= MAX_LINE_ANGLE_ERROR -> ImageExifOrientation.Rotate270
        abs(angleDegrees + 90f) <= MAX_LINE_ANGLE_ERROR -> ImageExifOrientation.Rotate90
        else -> null
    }

internal fun validatedScannerPageOrientations(
    pageCount: Int,
    orientations: List<ImageExifOrientation>,
): List<ImageExifOrientation> {
    require(pageCount in 1..MAX_SCAN_PAGES) { "Scanner orientation page count is invalid" }
    require(orientations.size == pageCount) { "Scanner orientation count does not match pages" }
    require(orientations.all(PHYSICAL_DOCUMENT_ORIENTATIONS::contains)) {
        "Scanner orientation must not mirror document pixels"
    }
    return orientations.toList()
}

internal class DocumentOrientationProcessor(private val context: Context) {
    suspend fun detect(pageUris: List<Uri>): List<ImageExifOrientation> {
        require(pageUris.size in 1..MAX_SCAN_PAGES) { "Orientation page count is invalid" }
        val recognizer =
            try {
                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            } catch (_: Exception) {
                return List(pageUris.size) { ImageExifOrientation.Normal }
            }
        return try {
            pageUris.map { uri ->
                currentCoroutineContext().ensureActive()
                try {
                    detectPage(uri, recognizer::process)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    ImageExifOrientation.Normal
                }
            }
        } finally {
            recognizer.close()
        }
    }

    private suspend fun detectPage(
        uri: Uri,
        recognize: (InputImage) -> com.google.android.gms.tasks.Task<Text>,
    ): ImageExifOrientation {
        val bitmap = decodeOrientationBitmap(uri)
        return try {
            currentCoroutineContext().ensureActive()
            selectDocumentOrientation(
                orientationEvidence(
                    recognize(InputImage.fromBitmap(bitmap, 0)).awaitResult(),
                ),
                imageWidth = bitmap.width,
                imageHeight = bitmap.height,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            selectDocumentOrientation(
                emptyList(),
                imageWidth = bitmap.width,
                imageHeight = bitmap.height,
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeOrientationBitmap(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsInput = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Scanner page could not be opened for orientation")
        boundsInput.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (
            bounds.outWidth <= 0 ||
                bounds.outHeight <= 0 ||
            !validOriginalImageDimensions(bounds.outWidth, bounds.outHeight)
        ) {
            throw IOException("Scanner page dimensions are unsafe for orientation")
        }
        val sampleSize = documentOrientationSampleSize(bounds.outWidth, bounds.outHeight)
        val options =
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
            }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: throw IOException("Scanner page could not be decoded for orientation")
    }
}

internal fun documentOrientationSampleSize(width: Int, height: Int): Int {
    require(validOriginalImageDimensions(width, height)) {
        "Scanner page dimensions are unsafe for orientation"
    }
    return appearanceDecodeSampleSize(
        width = width,
        height = height,
        maxPixels = MAX_ORIENTATION_ANALYSIS_PIXELS,
        maxEdge = MAX_ORIGINAL_IMAGE_DIMENSION,
    )
}

private fun orientationEvidence(text: Text): List<DocumentLineOrientationEvidence> =
    text.textBlocks.flatMap(Text.TextBlock::getLines).mapNotNull { line ->
        val readableCharacters = line.text.count(Char::isLetterOrDigit)
        DocumentLineOrientationEvidence(line.angle, readableCharacters)
            .takeIf { readableCharacters > 0 }
    }
