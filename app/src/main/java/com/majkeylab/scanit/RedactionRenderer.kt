package com.majkeylab.scanit

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.ceil
import kotlin.math.floor

private const val REDACTION_PADDING_PIXELS = 2

internal data class RedactionPixelRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

internal fun redactionPixelRects(
    width: Int,
    height: Int,
    regions: List<NormalizedRect>,
): List<RedactionPixelRect> {
    require(width > 0 && height > 0) { "Redaction page bounds are invalid" }
    require(regions.size <= MAX_SAFE_SHARE_SUGGESTIONS_PER_PAGE) {
        "Redaction region count exceeds the per-page limit"
    }
    return regions.map { region ->
        RedactionPixelRect(
            left =
                (floor(region.left * width).toInt() - REDACTION_PADDING_PIXELS)
                    .coerceAtLeast(0),
            top =
                (floor(region.top * height).toInt() - REDACTION_PADDING_PIXELS)
                    .coerceAtLeast(0),
            right =
                (ceil(region.right * width).toInt() + REDACTION_PADDING_PIXELS)
                    .coerceAtMost(width),
            bottom =
                (ceil(region.bottom * height).toInt() + REDACTION_PADDING_PIXELS)
                    .coerceAtMost(height),
        )
    }
}

internal fun renderRedactedJpeg(
    source: File,
    destination: File,
    regions: List<NormalizedRect>,
    isCancelled: () -> Boolean,
): JpegDimensions {
    if (isCancelled()) throw CancellationException("Redaction render cancelled")
    val sourceBounds = readJpegDimensions(source)
    redactionPixelRects(sourceBounds.width, sourceBounds.height, regions)
    var result: JpegDimensions? = null
    publishImageExportAtomically(destination, isCancelled) { staging ->
        val rendered =
            ScanAppearanceRenderer.renderJpeg(
                source = source,
                destination = staging,
                appearance = ScanAppearance(ScanColorMode.Natural, intensity = 0, shadows = 0),
                transformBitmap = { bitmap ->
                    val canvas = Canvas(bitmap)
                    val paint = Paint().apply {
                        color = Color.BLACK
                        style = Paint.Style.FILL
                        isAntiAlias = false
                    }
                    redactionPixelRects(bitmap.width, bitmap.height, regions).forEach { region ->
                        if (isCancelled()) {
                            throw CancellationException("Redaction render cancelled")
                        }
                        canvas.drawRect(
                            region.left.toFloat(),
                            region.top.toFloat(),
                            region.right.toFloat(),
                            region.bottom.toFloat(),
                            paint,
                        )
                    }
                },
                isCancelled = isCancelled,
            )
        result = JpegDimensions(rendered.width, rendered.height)
    }
    return checkNotNull(result) { "Redaction renderer produced no JPEG" }
}
