package com.majkeylab.scanit

import android.app.Activity
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException
import java.util.UUID

private const val PDF_MIME_TYPE = "application/pdf"
private const val JPEG_MIME_TYPE = "image/jpeg"
private const val PNG_MIME_TYPE = "image/png"
private const val MIXED_IMAGE_MIME_TYPE = "image/*"
internal const val MAX_PREPARED_IMAGE_SHARE_BYTES = 128L * 1024L * 1024L
internal const val PREPARED_IMAGE_SHARE_PREFIX = ".image-share-"
internal const val PREPARED_IMAGE_SHARE_TTL_MS = 24L * 60L * 60L * 1000L
private const val MAX_PREPARED_IMAGE_SHARES = 64
private const val MAX_PREPARED_IMAGE_SHARE_ROOT_BYTES = 256L * 1024L * 1024L
private const val PREPARED_IMAGE_SHARE_LEASE_FILE = ".lease"
private val preparedImageShareLock = Any()

internal data class PreparedImageShare(
    val shareRoot: File,
    val directory: File,
    val files: List<File>,
    val mimeType: String,
)

internal data class PreparedResultImageShare(
    val scan: SavedScan,
    val privateCopies: PreparedImageShare?,
)

internal data class PreparedImageSource(
    val page: Int,
    val uri: String,
    val mimeType: String?,
    val byteLength: Long?,
    val sha256: String?,
)

internal enum class ResultImageShareMode {
    PrivateCopies,
    CachedPages,
    Unavailable,
}

internal fun resultImageShareMode(outputs: List<PreparedImageSource>): ResultImageShareMode {
    if (outputs.isEmpty()) return ResultImageShareMode.CachedPages
    val rich = outputs.map(PreparedImageSource::hasExactShareIdentity)
    return when {
        rich.all { it } -> ResultImageShareMode.PrivateCopies
        rich.none { it } -> ResultImageShareMode.CachedPages
        else -> ResultImageShareMode.Unavailable
    }
}

internal fun resultImageShareMode(scan: SavedScan): ResultImageShareMode =
    resultImageShareMode(scan.savedImages.map(SavedImageOutput::toPreparedImageSource)).let { mode ->
        if (!scan.outputMetadataValid && mode == ResultImageShareMode.PrivateCopies) {
            ResultImageShareMode.Unavailable
        } else {
            mode
        }
    }

private fun PreparedImageSource.hasExactShareIdentity(): Boolean =
    page in 1..MAX_SCAN_PAGES &&
        isContentUri(uri) &&
        mimeType in setOf(JPEG_MIME_TYPE, PNG_MIME_TYPE) &&
        outputFingerprintOrNull(byteLength, sha256) != null

internal fun activeImageShareMimeType(mimeTypes: List<String?>): String {
    require(mimeTypes.isNotEmpty()) { "Image share requires at least one output" }
    val concrete = mimeTypes.map { it ?: JPEG_MIME_TYPE }.toSet()
    require(concrete.all { it == JPEG_MIME_TYPE || it == PNG_MIME_TYPE }) {
        "Image output MIME type is invalid"
    }
    return concrete.singleOrNull() ?: MIXED_IMAGE_MIME_TYPE
}

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

internal fun imageShareIntent(
    context: Context,
    prepared: PreparedImageShare,
    settings: AppSettings,
): Intent {
    validatePreparedImageShare(prepared)
    val uris = ArrayList(prepared.files.map { shareUri(context, it) })
    return Intent(
        if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE,
    ).apply {
        type = prepared.mimeType
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

internal fun validatePreparedImageShare(prepared: PreparedImageShare) =
    synchronized(preparedImageShareLock) {
        validatePreparedImageShareLocked(prepared)
    }

private fun validatePreparedImageShareLocked(prepared: PreparedImageShare) {
    val root = prepared.shareRoot.absoluteFile
    val directory = prepared.directory.absoluteFile
    if (prepared.files.isEmpty() || prepared.files.size > MAX_SCAN_PAGES ||
        root.canonicalFile != root || directory.canonicalFile != directory ||
        directory.parentFile != root || !isPreparedImageShareDirectoryName(directory.name) ||
        prepared.files.any {
            val file = it.absoluteFile
            file.canonicalFile != file || file.parentFile != directory || !file.isFile || file.length() <= 0L
        }
    ) throw IOException("Prepared image share is invalid")
    val totalBytes = prepared.files.fold(0L) { total, file ->
        if (file.length() > MAX_PREPARED_IMAGE_SHARE_BYTES - total) {
            throw IOException("Prepared image share is too large")
        }
        total + file.length()
    }
    if (readPreparedImageShareLease(directory) != totalBytes) {
        throw IOException("Prepared image share lease changed")
    }
    val expectedMime =
        activeImageShareMimeType(
            prepared.files.map { file ->
                when (file.extension) {
                    "jpg" -> JPEG_MIME_TYPE
                    "png" -> PNG_MIME_TYPE
                    else -> throw IOException("Prepared image share extension is invalid")
                }
            },
        )
    if (prepared.mimeType != expectedMime) throw IOException("Prepared image share MIME type changed")
}

internal fun prepareImageShareCopies(
    context: Context,
    scan: SavedScan,
    isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
): PreparedImageShare {
    val cacheRoot = context.cacheDir.canonicalFile
    val shareRoot = File(cacheRoot, "share").absoluteFile
    if (shareRoot.canonicalFile != shareRoot || shareRoot.parentFile != cacheRoot) {
        throw IOException("Image share root is unsafe")
    }
    if (!shareRoot.isDirectory && !shareRoot.mkdir()) {
        throw IOException("Image share root could not be created")
    }
    return prepareImageShareCopies(
        shareRoot = shareRoot,
        outputs = scan.savedImages.map(SavedImageOutput::toPreparedImageSource),
        open = { uri ->
            context.contentResolver.openInputStream(uri.toUri())
                ?: throw IOException("Saved image could not be opened for sharing")
        },
        isCancelled = isCancelled,
    )
}

internal fun prepareImageShareCopies(
    shareRoot: File,
    outputs: List<PreparedImageSource>,
    open: (String) -> InputStream,
    isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
    operationId: String = UUID.randomUUID().toString(),
): PreparedImageShare {
    if (outputs.isEmpty() || outputs.size > MAX_SCAN_PAGES || !isCanonicalUuid(operationId)) {
        throw IOException("Image share request is invalid")
    }
    if (outputs.map(PreparedImageSource::page) != outputs.map(PreparedImageSource::page).sorted() ||
        outputs.map(PreparedImageSource::page).distinct().size != outputs.size
    ) {
        throw IOException("Image share page order is invalid")
    }
    val fingerprints =
        outputs.map { output ->
            outputFingerprintOrNull(output.byteLength, output.sha256)
                ?: throw IOException("Saved image fingerprint is unavailable")
        }
    val totalBytes =
        fingerprints.fold(0L) { total, fingerprint ->
            if (fingerprint.byteLength > MAX_PREPARED_IMAGE_SHARE_BYTES - total) {
                throw IOException("Prepared image share is too large")
            }
            total + fingerprint.byteLength
        }
    if (totalBytes <= 0L) throw IOException("Prepared image share is empty")
    val mimeTypes = outputs.map(PreparedImageSource::mimeType)
    val mimeType = activeImageShareMimeType(mimeTypes)
    val root = shareRoot.absoluteFile
    val directory = reservePreparedImageShareDirectory(root, operationId, totalBytes)
    val prepared = PreparedImageShare(root, directory, emptyList(), mimeType)
    try {
        val files =
            outputs.mapIndexed { index, output ->
                throwIfShareCancelled(isCancelled)
                val extension = if (mimeTypes[index] == PNG_MIME_TYPE) "png" else "jpg"
                val target = File(directory, "page-${output.page.toString().padStart(2, '0')}.$extension")
                copyImageShareOutput(open(output.uri), target, fingerprints[index], isCancelled)
                target
            }
        return prepared.copy(files = files)
    } catch (cancellation: CancellationException) {
        if (!cleanupPreparedImageShare(prepared)) {
            cancellation.addSuppressed(IOException("Prepared image share cleanup failed"))
        }
        throw cancellation
    } catch (failure: Exception) {
        if (!cleanupPreparedImageShare(prepared)) {
            failure.addSuppressed(IOException("Prepared image share cleanup failed"))
        }
        throw IOException("Image share copies could not be prepared", failure)
    }
}

internal fun SavedImageOutput.toPreparedImageSource(): PreparedImageSource =
    PreparedImageSource(page, uri.toString(), mimeType, byteLength, sha256)

internal fun cleanupPreparedImageShare(prepared: PreparedImageShare): Boolean {
    synchronized(preparedImageShareLock) {
        return cleanupPreparedImageShareLocked(prepared)
    }
}

private fun cleanupPreparedImageShareLocked(prepared: PreparedImageShare): Boolean {
    val root = prepared.shareRoot.absoluteFile
    val directory = prepared.directory.absoluteFile
    if (
        root.canonicalFile != root ||
            directory.canonicalFile != directory ||
            directory.parentFile != root ||
            !isPreparedImageShareDirectoryName(directory.name)
    ) return false
    return !directory.exists() || deleteTreeWithoutFollowingLinks(directory)
}

private fun reservePreparedImageShareDirectory(
    root: File,
    operationId: String,
    expectedBytes: Long,
): File =
    synchronized(preparedImageShareLock) {
        if (root.canonicalFile != root || !root.isDirectory) {
            throw IOException("Prepared image share root is unavailable")
        }
        val now = System.currentTimeMillis()
        preparedImageShareDirectories(root).forEach { directory ->
            if (directory.lastModified() <= 0L || directory.lastModified() > now) {
                throw IOException("Prepared image share timestamp is invalid")
            }
            val children = directory.listFiles()
                ?: throw IOException("Prepared image share directory could not be listed")
            if (children.isEmpty()) {
                if (now - directory.lastModified() > PREPARED_IMAGE_SHARE_TTL_MS &&
                    !deleteTreeWithoutFollowingLinks(directory)
                ) throw IOException("Expired image share reservation could not be cleaned")
                if (directory.exists()) {
                    throw IOException("Prepared image share reservation is incomplete")
                }
                return@forEach
            }
            validatePreparedImageShareDirectory(directory)
            if (now - directory.lastModified() > PREPARED_IMAGE_SHARE_TTL_MS &&
                !deleteTreeWithoutFollowingLinks(directory)
            ) throw IOException("Expired image share could not be cleaned")
        }
        val active = preparedImageShareDirectories(root)
        if (active.size >= MAX_PREPARED_IMAGE_SHARES) {
            throw IOException("Prepared image share root is full")
        }
        val activeBytes =
            active.fold(0L) { total, directory ->
                val bytes = readPreparedImageShareLease(directory)
                if (bytes > MAX_PREPARED_IMAGE_SHARE_ROOT_BYTES - total) {
                    throw IOException("Prepared image share root is too large")
                }
                total + bytes
            }
        if (expectedBytes > MAX_PREPARED_IMAGE_SHARE_ROOT_BYTES - activeBytes) {
            throw IOException("Prepared image share root is too large")
        }
        val directory = File(root, "$PREPARED_IMAGE_SHARE_PREFIX$operationId").absoluteFile
        if (directory.parentFile != root || directory.exists() || !directory.mkdir()) {
            throw IOException("Prepared image share directory could not be created")
        }
        try {
            FileOutputStream(File(directory, PREPARED_IMAGE_SHARE_LEASE_FILE)).use { output ->
                output.write(expectedBytes.toString().toByteArray(Charsets.US_ASCII))
                output.fd.sync()
            }
        } catch (failure: Exception) {
            deleteTreeWithoutFollowingLinks(directory)
            throw IOException("Prepared image share lease could not be written", failure)
        }
        directory
    }

private fun preparedImageShareDirectories(root: File): List<File> {
    val children = root.listFiles() ?: throw IOException("Prepared image share root could not be listed")
    val prefixed = children.filter { it.name.startsWith(PREPARED_IMAGE_SHARE_PREFIX) }
    if (prefixed.any { !it.isDirectory || !isPreparedImageShareDirectoryName(it.name) }) {
        throw IOException("Prepared image share root contains an unsafe entry")
    }
    return prefixed
}

private fun validatePreparedImageShareDirectory(directory: File) {
    val children = directory.listFiles()
    if (directory.canonicalFile != directory || children == null ||
        children.size > MAX_SCAN_PAGES + 1 ||
        children.all { child ->
            child.canonicalFile == child.absoluteFile && child.isFile &&
                (child.name == PREPARED_IMAGE_SHARE_LEASE_FILE ||
                    child.name.matches(PREPARED_IMAGE_FILE_NAME))
        }.not()
    ) throw IOException("Prepared image share directory is unsafe")
    val expectedBytes = readPreparedImageShareLease(directory)
    children.filterNot { it.name == PREPARED_IMAGE_SHARE_LEASE_FILE }.fold(0L) { total, file ->
        if (file.length() > expectedBytes - total) {
            throw IOException("Prepared image share directory is too large")
        }
        total + file.length()
    }
}

private fun readPreparedImageShareLease(directory: File): Long {
    val lease = File(directory, PREPARED_IMAGE_SHARE_LEASE_FILE).absoluteFile
    if (lease.parentFile != directory || lease.canonicalFile != lease || !lease.isFile ||
        lease.length() !in 1..20
    ) throw IOException("Prepared image share lease is invalid")
    return lease.readText(Charsets.US_ASCII).toLongOrNull()
        ?.takeIf { it in 1..MAX_PREPARED_IMAGE_SHARE_BYTES }
        ?: throw IOException("Prepared image share lease is invalid")
}

private fun isPreparedImageShareDirectoryName(name: String): Boolean =
    name.startsWith(PREPARED_IMAGE_SHARE_PREFIX) &&
        isCanonicalUuid(name.removePrefix(PREPARED_IMAGE_SHARE_PREFIX))

private val PREPARED_IMAGE_FILE_NAME = Regex("page-[0-9]+\\.(jpg|png)")

private fun copyImageShareOutput(
    input: InputStream,
    target: File,
    fingerprint: OutputFingerprint,
    isCancelled: () -> Boolean,
) {
    input.use { source ->
        FileOutputStream(target).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var remaining = fingerprint.byteLength
            while (remaining > 0L) {
                throwIfShareCancelled(isCancelled)
                val read = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) throw IOException("Saved image is shorter than its metadata")
                if (read == 0) {
                    val byte = source.read()
                    if (byte < 0) throw IOException("Saved image is shorter than its metadata")
                    output.write(byte)
                    remaining--
                } else {
                    output.write(buffer, 0, read)
                    remaining -= read
                }
            }
            if (source.read() >= 0) throw IOException("Saved image is longer than its metadata")
            output.fd.sync()
        }
    }
    FileInputStream(target).use { copied ->
        if (readOutputFingerprint(copied, fingerprint.byteLength) != fingerprint) {
            throw IOException("Prepared image share differs from its saved output")
        }
    }
}

private fun throwIfShareCancelled(isCancelled: () -> Boolean) {
    if (isCancelled()) throw CancellationException("Image share preparation was cancelled")
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
