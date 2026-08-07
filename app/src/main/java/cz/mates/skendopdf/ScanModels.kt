package cz.mates.skendopdf

import android.graphics.Bitmap
import android.net.Uri
import java.io.File
import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

internal const val DEFAULT_ALBUM_NAME = "Scan to PDF"

internal data class AppSettings(
    val savePdf: Boolean = true,
    val saveImages: Boolean = true,
    val albumName: String = DEFAULT_ALBUM_NAME,
    val multipage: Boolean = true,
    val allowGallery: Boolean = true,
    val emailSubject: String = "Scanned document",
    val emailBody: String = "",
    val pdfTreeUri: String? = null,
    val aiEnabled: Boolean = false,
    val aiConsent: Boolean = false,
)

internal fun localizedDefaultEmailSubject(
    current: String,
    targetDefault: String,
    supportedDefaults: Set<String>,
): String = if (current in supportedDefaults) targetDefault else current

internal data class UiMessage(
    val resourceId: Int,
    val formatArgs: List<Int> = emptyList(),
)

internal data class CachedScan(
    val baseName: String,
    val pages: List<File>,
    val pdf: File,
)

internal data class SavedScan(
    val cached: CachedScan,
    val galleryPages: List<Uri>,
    val savedPdf: Uri?,
    val warnings: List<UiMessage> = emptyList(),
    val isAiCopy: Boolean = false,
)

internal enum class AiReviewSource {
    Original,
    Ai,
}

internal sealed interface ScreenState {
    data object Ready : ScreenState

    data class Processing(val message: UiMessage) : ScreenState

    data class Result(
        val scan: SavedScan,
        val thumbnail: Bitmap?,
        val original: SavedScan? = null,
        val message: UiMessage? = null,
    ) : ScreenState

    data class AiReview(
        val original: SavedScan,
        val ai: CachedScan,
        val pageIndex: Int,
        val source: AiReviewSource,
        val preview: Bitmap?,
    ) : ScreenState

    data class Failure(val message: UiMessage) : ScreenState
}

internal fun aiReviewPageIndex(requested: Int, pageCount: Int): Int {
    require(pageCount > 0) { "AI review needs at least one page" }
    return requested.coerceIn(0, pageCount - 1)
}

private val scanNameFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT)

internal fun scanBaseName(clock: Clock): String =
    "Scan_${LocalDateTime.now(clock).format(scanNameFormatter)}"
