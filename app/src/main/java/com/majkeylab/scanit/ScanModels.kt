package com.majkeylab.scanit

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
)

internal enum class RestoredRoute {
    Scanner,
    Recent,
    Result,
}

internal fun restoredRoute(savedRoute: String?, cacheId: String?): RestoredRoute =
    when (savedRoute) {
        null -> RestoredRoute.Scanner
        "scanner" -> RestoredRoute.Scanner
        "result" -> if (cacheId.isNullOrBlank()) RestoredRoute.Recent else RestoredRoute.Result
        "recent" -> RestoredRoute.Recent
        else -> RestoredRoute.Recent
    }

internal sealed interface ScreenState {
    data object Ready : ScreenState

    data class Processing(
        val message: UiMessage,
        val canNavigateBack: Boolean,
    ) : ScreenState

    data class Result(
        val scan: SavedScan,
        val thumbnail: Bitmap?,
    ) : ScreenState

    data class Recent(
        val scans: List<RecentScan>,
        val message: UiMessage? = null,
    ) : ScreenState

    data class Failure(val message: UiMessage) : ScreenState
}

internal enum class AppBackAction {
    CloseSettings,
    CollapseFileDetails,
    ShowRecent,
    LaunchScanner,
    Consume,
}

internal fun appBackAction(
    settingsOpen: Boolean,
    fileDetailsOpen: Boolean,
    state: ScreenState,
): AppBackAction =
    when {
        settingsOpen -> AppBackAction.CloseSettings
        fileDetailsOpen && state is ScreenState.Result -> AppBackAction.CollapseFileDetails
        state is ScreenState.Processing && !state.canNavigateBack -> AppBackAction.Consume
        state is ScreenState.Recent -> AppBackAction.LaunchScanner
        else -> AppBackAction.ShowRecent
    }

private val scanNameFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT)

internal fun scanBaseName(clock: Clock): String =
    "Scan_${LocalDateTime.now(clock).format(scanNameFormatter)}"
