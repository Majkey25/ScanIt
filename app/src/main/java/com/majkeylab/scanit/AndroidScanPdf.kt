package com.majkeylab.scanit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

internal fun buildScanPdfFromPages(
    output: File,
    sourcePages: List<File>,
    renderedPages: List<File>,
    appearance: ScanAppearance,
    target: PdfSizeTarget,
    isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
): ScanPdfBuildResult {
    require(sourcePages.isNotEmpty() && sourcePages.size == renderedPages.size) {
        "PDF source and rendered pages must be complete"
    }
    require(sourcePages.size <= MAX_SCAN_PAGES) {
        "PDF page count exceeds $MAX_SCAN_PAGES"
    }
    val pages =
        sourcePages.indices.map { index ->
            throwIfAndroidPdfCancelled(isCancelled)
            val source = sourcePages[index]
            val rendered = renderedPages[index]
            val sourceBounds = readJpegDimensions(source)
            throwIfAndroidPdfCancelled(isCancelled)
            val renderedBounds = readJpegDimensions(rendered)
            val baseSample = appearanceDecodeSampleSize(sourceBounds.width, sourceBounds.height)
            ScanPdfBuildPage(
                longestEdge = maxOf(renderedBounds.width, renderedBounds.height),
                renderJpeg = { workingDirectory, sampleMultiplier ->
                    if (sampleMultiplier == 1) {
                        JpegPdfPage(rendered, renderedBounds.width, renderedBounds.height)
                    } else {
                        val destination = File(workingDirectory, "page-${index + 1}.jpg")
                        val result =
                            ScanAppearanceRenderer.renderJpeg(
                                source = source,
                                destination = destination,
                                appearance = appearance,
                                minimumSampleSize =
                                    relativePdfSourceSampleSize(baseSample, sampleMultiplier),
                                isCancelled = isCancelled,
                            )
                        JpegPdfPage(
                            destination,
                            result.width,
                            result.height,
                            physicalWidthPixels = renderedBounds.width,
                            physicalHeightPixels = renderedBounds.height,
                        )
                    }
                },
                createBitonal = { page -> lazyBitonalPdfPage(page, isCancelled) },
            )
        }
    return buildScanPdf(output, pages, target, isBitonalPdfEligible(appearance), isCancelled)
}

internal fun readJpegDimensions(file: File): JpegDimensions {
    if (!file.isFile || file.length() <= 0L) throw IOException("JPEG page is missing or empty")
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, options)
    if (
        options.outWidth <= 0 ||
            options.outHeight <= 0 ||
            !options.outMimeType.equals(SCAN_JPEG_MIME_TYPE, ignoreCase = true)
    ) {
        throw IOException("Scan page is not a readable JPEG")
    }
    return JpegDimensions(options.outWidth, options.outHeight)
}

internal data class JpegDimensions(val width: Int, val height: Int)

private fun lazyBitonalPdfPage(
    page: JpegPdfPage,
    isCancelled: () -> Boolean,
): BitonalPdfPage {
    var bitmap: Bitmap? = null
    var argb: IntArray? = null
    return BitonalPdfPage(
        width = page.width,
        height = page.height,
        physicalWidthPixels = page.physicalWidthPixels,
        physicalHeightPixels = page.physicalHeightPixels,
        onComplete = {
            bitmap?.recycle()
            bitmap = null
            argb = null
        },
        readRow = { row, grayscale ->
            throwIfAndroidPdfCancelled(isCancelled)
            val decoded =
                bitmap ?: decodeBitonalSource(page).also { opened -> bitmap = opened }
            val pixels = argb ?: IntArray(page.width).also { rowPixels -> argb = rowPixels }
            decoded.getPixels(pixels, 0, page.width, 0, row, page.width, 1)
            pixels.indices.forEach { x ->
                grayscale[x] = if (argbLuma(pixels[x]) <= BITONAL_THRESHOLD) 0 else 1
            }
        },
    )
}

private fun decodeBitonalSource(page: JpegPdfPage): Bitmap {
    val bitmap =
        BitmapFactory.decodeFile(
            page.file.path,
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                inScaled = false
            },
        ) ?: throw IOException("Rendered JPEG could not be decoded for the bitonal PDF")
    if (bitmap.width != page.width || bitmap.height != page.height) {
        bitmap.recycle()
        throw IOException("Rendered JPEG dimensions changed during PDF creation")
    }
    return bitmap
}

private fun throwIfAndroidPdfCancelled(isCancelled: () -> Boolean) {
    if (isCancelled()) throw CancellationException("PDF build cancelled")
}

private const val SCAN_JPEG_MIME_TYPE = "image/jpeg"
private const val BITONAL_THRESHOLD = 127
