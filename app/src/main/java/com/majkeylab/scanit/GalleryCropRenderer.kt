package com.majkeylab.scanit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException

internal const val GALLERY_CROP_SESSION = "gallery_crop_session"
internal const val GALLERY_CROP_PAGE_COUNT = "gallery_crop_page_count"
internal const val GALLERY_CROP_PAGE_LIMIT = "gallery_crop_page_limit"
private const val GALLERY_CROP_TOTAL_SOURCE_BYTES = 512L * 1024L * 1024L

internal fun galleryCropDirectory(context: Context, session: String): File {
    require(isCanonicalUuid(session)) { "Gallery crop session is invalid" }
    val root = File(context.cacheDir.canonicalFile, "gallery-crop").absoluteFile
    val directory = File(root, session)
    listOf(root, directory).forEach { child ->
        if (Files.exists(child.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            (!child.isDirectory || child.canonicalFile != child)) {
            throw IOException("Gallery crop directory is unsafe")
        }
        if ((!child.isDirectory && !child.mkdir()) || child.canonicalFile != child) {
            throw IOException("Gallery crop directory is unavailable")
        }
    }
    return directory
}

internal fun discardGalleryCropSession(context: Context, session: String): Boolean {
    require(isCanonicalUuid(session)) { "Gallery crop session is invalid" }
    val root = File(context.cacheDir.canonicalFile, "gallery-crop").absoluteFile
    val directory = File(root, session)
    if (!Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) return true
    if (root.canonicalFile != root || !root.isDirectory) {
        throw IOException("Gallery crop directory is unsafe")
    }
    if (!Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) return true
    if (directory.canonicalFile != directory || !directory.isDirectory) {
        throw IOException("Gallery crop directory is unsafe")
    }
    return deleteTreeWithoutFollowingLinks(directory)
}

internal fun clearOldGalleryCrops(context: Context, session: String) {
    val directory = galleryCropDirectory(context, session)
    directory.parentFile?.listFiles().orEmpty().forEach { old ->
        if (old != directory && isCanonicalUuid(old.name) && !discardGalleryCropSession(context, old.name)) {
            throw IOException("Previous gallery crop could not be cleared")
        }
    }
}

internal fun prepareGalleryCropSources(
    context: Context,
    session: String,
    uris: List<String>,
    isCancelled: () -> Boolean,
) {
    require(isAcceptedScanPageCount(uris.size)) { "Gallery page count is invalid" }
    if (isCancelled()) throw CancellationException("Gallery import was cancelled")
    val directory = galleryCropDirectory(context, session)
    var remaining = GALLERY_CROP_TOTAL_SOURCE_BYTES
    uris.forEachIndexed { index, value ->
        if (isCancelled()) throw CancellationException("Gallery import was cancelled")
        val uri = Uri.parse(value)
        require(uri.scheme == "content") { "Gallery image URI is invalid" }
        val source = File(directory, "source_$index")
        if (source.isFile && source.length() in 1..minOf(remaining, MAX_SCAN_SOURCE_PAGE_BYTES)) {
            remaining -= source.length()
        } else {
            val work = File.createTempFile("source-", ".tmp", directory)
            try {
                val length = (context.contentResolver.openInputStream(uri)
                    ?: throw IOException("Gallery image could not be opened")).use { input ->
                    work.outputStream().use { output ->
                        copyBoundedInput(input, output, minOf(remaining, MAX_SCAN_SOURCE_PAGE_BYTES), isCancelled)
                    }
                }
                if (isCancelled()) throw CancellationException("Gallery import was cancelled")
                Files.move(work.toPath(), source.toPath(), StandardCopyOption.REPLACE_EXISTING)
                remaining -= length
            } finally {
                work.delete()
            }
        }
    }
}

internal fun decodeGalleryCropBitmap(
    source: File,
    maxPixels: Long = MAX_IMAGE_EXPORT_PIXELS,
    maxEdge: Int = MAX_IMAGE_EXPORT_DIMENSION,
): Bitmap {
    val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
        require(validOriginalImageDimensions(info.size.width, info.size.height)) { "Gallery image dimensions are unsafe" }
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        decoder.setTargetSampleSize(appearanceDecodeSampleSize(info.size.width, info.size.height, maxPixels, maxEdge))
        decoder.setOnPartialImageListener { false }
    }
    if (bitmap.width > maxEdge || bitmap.height > maxEdge ||
        bitmap.width.toLong() * bitmap.height > maxPixels || bitmap.allocationByteCount.toLong() > maxPixels * 4L) {
        bitmap.recycle()
        throw IOException("Decoded gallery image exceeds memory limits")
    }
    return bitmap
}

internal fun galleryCropMatrix(plan: GalleryCropPlan): Matrix =
    Matrix().also { matrix ->
        require(matrix.setPolyToPoly(plan.source, 0, plan.destination, 0, 4)) { "Gallery crop transform is singular" }
    }

internal fun cropGalleryBitmap(source: Bitmap, corners: List<GalleryCropPoint>): Bitmap {
    val plan = requireNotNull(galleryCropPlan(corners, source.width, source.height)) { "Gallery crop is invalid" }
    val matrix = galleryCropMatrix(plan)
    val output = Bitmap.createBitmap(plan.width, plan.height, Bitmap.Config.ARGB_8888)
    try {
        Canvas(output).apply {
            drawColor(Color.WHITE)
            drawBitmap(source, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
        return output
    } catch (failure: Throwable) {
        output.recycle()
        throw failure
    }
}

internal fun renderGalleryCrop(directory: File, page: Int, corners: List<GalleryCropPoint>, isCancelled: () -> Boolean) {
    require(page in 0 until MAX_SCAN_PAGES)
    val source = decodeGalleryCropBitmap(File(directory, "source_$page"))
    var output: Bitmap? = null
    var work: File? = null
    try {
        if (isCancelled()) throw CancellationException("Gallery crop was cancelled")
        output = if (corners == fullGalleryCrop() && !source.hasAlpha()) source else cropGalleryBitmap(source, corners)
        work = File.createTempFile("crop-", ".tmp", directory)
        work.outputStream().use { stream ->
            if (!output.compress(Bitmap.CompressFormat.JPEG, 95, stream)) throw IOException("Gallery crop could not be encoded")
            stream.fd.sync()
        }
        if (isCancelled()) throw CancellationException("Gallery crop was cancelled")
        Files.move(work.toPath(), File(directory, "page_$page.jpg").toPath(), StandardCopyOption.REPLACE_EXISTING)
    } finally {
        work?.delete()
        if (output !== source) output?.recycle()
        source.recycle()
    }
}

internal fun galleryCropResultPages(context: Context, session: String, count: Int): List<Uri> {
    require(isAcceptedScanPageCount(count)) { "Gallery crop page count is invalid" }
    val directory = galleryCropDirectory(context, session)
    return List(count) { index ->
        val file = File(directory, "page_$index.jpg")
        require(file.isFile && file.length() in 1..MAX_SCAN_SOURCE_PAGE_BYTES && file.canonicalFile == file) { "Gallery crop page is unavailable" }
        Uri.fromFile(file)
    }
}
