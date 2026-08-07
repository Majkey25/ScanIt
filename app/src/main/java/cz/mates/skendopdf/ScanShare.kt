package cz.mates.skendopdf

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

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

internal fun Activity.launchShareChooser(shareIntent: Intent): Boolean =
    try {
        startActivity(Intent.createChooser(shareIntent, getString(R.string.app_name)))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

private fun shareUri(context: Context, file: File): Uri {
    if (!file.isFile || file.length() <= 0L) {
        throw IOException("Share file is missing or empty")
    }
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
