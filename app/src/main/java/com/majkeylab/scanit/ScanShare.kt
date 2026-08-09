package com.majkeylab.scanit

import android.app.Activity
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.util.UUID

private const val PDF_MIME_TYPE = "application/pdf"
private const val JPEG_MIME_TYPE = "image/jpeg"

internal fun pdfShareIntent(
    context: Context,
    scan: CachedScan,
    settings: AppSettings,
): Intent {
    val uri = shareUri(context, scan.pdf)
    return Intent(Intent.ACTION_SEND).apply {
        type = PDF_MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, settings.emailSubject)
        putExtra(Intent.EXTRA_TEXT, settings.emailBody)
        clipData = ClipData.newUri(context.contentResolver, context.getString(R.string.app_name), uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

internal fun imageShareIntent(
    context: Context,
    scan: CachedScan,
    settings: AppSettings,
): Intent {
    require(scan.pages.isNotEmpty()) { "Scan has no pages" }
    val uris = ArrayList(scan.pages.map { shareUri(context, it) })
    return Intent(
        if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE,
    ).apply {
        type = JPEG_MIME_TYPE
        if (uris.size == 1) {
            putExtra(Intent.EXTRA_STREAM, uris.single())
        } else {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        putExtra(Intent.EXTRA_SUBJECT, settings.emailSubject)
        putExtra(Intent.EXTRA_TEXT, settings.emailBody)
        clipData = ClipData.newUri(context.contentResolver, context.getString(R.string.app_name), uris.first())
        uris.drop(1).forEach { clipData?.addItem(ClipData.Item(it)) }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

internal fun Activity.launchShareChooser(
    shareIntent: Intent,
    cleanupRequest: ShareCleanupRequest? = null,
): Boolean =
    try {
        if (
            cleanupRequest != null &&
                !SettingsStore(applicationContext).canSavePendingShareCleanup(cleanupRequest)
        ) {
            return false
        }
        val chooser =
            if (cleanupRequest == null) {
                Intent.createChooser(shareIntent, getString(R.string.app_name))
            } else {
                Intent.createChooser(
                    shareIntent,
                    getString(R.string.app_name),
                    shareResultPendingIntent(this, cleanupRequest).intentSender,
                )
            }
        startActivity(chooser)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: IOException) {
        false
    }

private fun shareResultPendingIntent(
    context: Context,
    request: ShareCleanupRequest,
): PendingIntent {
    val callback =
        Intent(context, ShareResultReceiver::class.java).apply {
            action = "${context.packageName}.share_result.${UUID.randomUUID()}"
            putExtra(EXTRA_SHARE_CACHE_ID, request.cacheId)
            putExtra(EXTRA_SHARE_ENTRY_ID, request.entryId)
            putExtra(EXTRA_SHARE_CLEANUP_KIND, request.kind.wireValue)
        }
    return PendingIntent.getBroadcast(
        context,
        0,
        callback,
        PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE,
    )
}

private fun shareUri(context: Context, file: File): Uri {
    if (!file.isFile || file.length() <= 0L) {
        throw IOException("Share file is missing or empty")
    }
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
