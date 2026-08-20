package com.majkeylab.scanit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.core.graphics.scale
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CancellationException
import kotlin.math.roundToInt

private const val MARK_DIRECTORY = "marks"
private const val COPY_BUFFER_BYTES = 8_192
private const val MARK_TEMP_PREFIX = ".mark_"
private const val MARK_TEMP_SUFFIX = ".tmp"
private val MARK_STORAGE_LOCK = Any()

internal class MarkTemplateStore(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val noBackupRoot = context.applicationContext.noBackupFilesDir.canonicalFile
    private val directory = File(noBackupRoot, MARK_DIRECTORY).absoluteFile

    fun list(): List<String> = synchronized(MARK_STORAGE_LOCK) {
        listMarkTemplateIds(requireDirectory())
    }

    fun import(uri: Uri): String = synchronized(MARK_STORAGE_LOCK) {
        val importFile = File.createTempFile(".mark_import_", ".tmp", requireDirectory())
        try {
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(importFile).use { output ->
                    copyBoundedInput(input, output, MAX_MARK_INPUT_BYTES)
                }
            } ?: throw IOException("The selected mark could not be opened")
            val decoded = decodeMarkBitmap(importFile, MARK_DECODE_MAX_SIDE)
            try {
                save(decoded)
            } finally {
                decoded.recycle()
            }
        } finally {
            importFile.delete()
        }
    }

    fun save(bitmap: Bitmap): String = synchronized(MARK_STORAGE_LOCK) {
        val normalized = normalizeMarkBitmap(bitmap)
        try {
            val targetDirectory = requireDirectory()
            saveNewMarkTemplate(targetDirectory, System.currentTimeMillis()) { output ->
                if (!normalized.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw IOException("The mark template could not be encoded")
                }
            }
        } finally {
            normalized.recycle()
        }
    }

    fun load(
        id: String,
        maxSide: Int = MARK_OUTPUT_MAX_SIDE,
    ): Bitmap? = synchronized(MARK_STORAGE_LOCK) {
        require(maxSide in 1..MARK_OUTPUT_MAX_SIDE) { "Mark preview size is invalid" }
        val targetDirectory = requireDirectory()
        val file = canonicalMarkTemplateFile(targetDirectory, id) ?: return@synchronized null
        if (!file.isFile || file.length() !in 1..MAX_MARK_INPUT_BYTES) return@synchronized null
        decodeMarkBitmap(file, maxSide)
    }

    fun delete(id: String): Boolean = synchronized(MARK_STORAGE_LOCK) {
        deleteMarkTemplateFile(requireDirectory(), id)
    }

    private fun requireDirectory(): File {
        if (!noBackupRoot.isDirectory) throw IOException("The private storage directory is unavailable")
        if (!directory.isDirectory && !directory.mkdir()) {
            throw IOException("The mark template directory could not be created")
        }
        if (directory.canonicalFile != directory || directory.parentFile != noBackupRoot) {
            throw IOException("The mark template directory is unsafe")
        }
        purgeIncompleteMarkFiles(directory)
        return directory
    }
}

internal fun boundedMarkDimensions(
    width: Int,
    height: Int,
    maxSide: Int,
): Pair<Int, Int> {
    require(width > 0 && height > 0 && maxSide > 0) { "Image dimensions are invalid" }
    val largest = maxOf(width, height)
    if (largest <= maxSide) return width to height
    val scale = maxSide.toDouble() / largest.toDouble()
    return maxOf(1, (width * scale).roundToInt()) to maxOf(1, (height * scale).roundToInt())
}

internal fun nextMarkTemplateId(
    existingIds: Set<String>,
    epochMillis: Long,
): String {
    var timestamp = epochMillis.coerceAtLeast(0)
    while (true) {
        val candidate = "mark_$timestamp.png"
        if (candidate !in existingIds) return candidate
        if (timestamp == Long.MAX_VALUE) error("No mark template ID is available")
        timestamp++
    }
}

internal fun copyBoundedInput(
    input: InputStream,
    output: OutputStream,
    maxBytes: Long,
    isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
): Long {
    require(maxBytes > 0) { "Input limit must be positive" }
    val buffer = ByteArray(COPY_BUFFER_BYTES)
    var total = 0L
    while (true) {
        if (isCancelled()) throw CancellationException("Input copy was cancelled")
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        if (total > maxBytes - count) throw IOException("Input is too large")
        output.write(buffer, 0, count)
        total += count
    }
    if (total == 0L) throw IOException("Input is empty")
    return total
}

internal fun listMarkTemplateIds(directory: File): List<String> {
    val canonicalDirectory = requireCanonicalMarkDirectory(directory)
    val files = canonicalDirectory.listFiles()
        ?: throw IOException("The mark template directory could not be read")
    return files
        .asSequence()
        .filter { file ->
            file.isFile &&
                isValidMarkTemplateId(file.name) &&
                runCatching { file.absoluteFile == file.canonicalFile }.getOrDefault(false)
        }
        .sortedByDescending { file -> markTemplateTimestamp(file.name) }
        .map(File::getName)
        .toList()
}

internal fun availableMarkTemplateId(
    directory: File,
    epochMillis: Long,
): String {
    val existing = listMarkTemplateIds(directory)
    if (existing.size >= MAX_MARK_TEMPLATES) {
        throw IOException("The mark template limit has been reached")
    }
    return nextMarkTemplateId(existing.toSet(), epochMillis)
}

internal fun saveNewMarkTemplate(
    directory: File,
    epochMillis: Long,
    write: (OutputStream) -> Unit,
): String = synchronized(MARK_STORAGE_LOCK) {
    val id = availableMarkTemplateId(directory, epochMillis)
    atomicSaveMarkTemplate(directory, id, write)
    id
}

internal fun purgeIncompleteMarkFiles(directory: File) {
    val canonicalDirectory = requireCanonicalMarkDirectory(directory)
    val files = canonicalDirectory.listFiles()
        ?: throw IOException("The mark template directory could not be read")
    files.filter { file ->
        file.name.startsWith(MARK_TEMP_PREFIX) && file.name.endsWith(MARK_TEMP_SUFFIX)
    }.forEach { file ->
        if (!file.isFile || file.absoluteFile != file.canonicalFile) {
            throw IOException("An incomplete mark template is unsafe")
        }
        Files.delete(file.toPath())
    }
}

internal fun deleteMarkTemplateFile(
    directory: File,
    id: String,
): Boolean {
    val canonicalDirectory = requireCanonicalMarkDirectory(directory)
    val file = canonicalMarkTemplateFile(canonicalDirectory, id) ?: return false
    if (!file.isFile) return false
    return Files.deleteIfExists(file.toPath())
}

internal fun atomicSaveMarkTemplate(
    directory: File,
    id: String,
    write: (OutputStream) -> Unit,
) {
    val canonicalDirectory = requireCanonicalMarkDirectory(directory)
    val target = canonicalMarkTemplateFile(canonicalDirectory, id)
        ?: throw IOException("The mark template ID is invalid")
    if (target.exists()) throw IOException("The mark template already exists")
    val temporary = File.createTempFile(MARK_TEMP_PREFIX, MARK_TEMP_SUFFIX, canonicalDirectory)
    try {
        FileOutputStream(temporary).use { output ->
            write(output)
            output.flush()
            output.fd.sync()
        }
        publishStagedFileNoReplace(temporary.toPath(), target.toPath())
    } catch (error: Exception) {
        runCatching { Files.deleteIfExists(temporary.toPath()) }
            .exceptionOrNull()
            ?.let(error::addSuppressed)
        throw error
    }
}

private fun normalizeMarkBitmap(source: Bitmap): Bitmap {
    require(source.width > 0 && source.height > 0) { "Mark bitmap is empty" }
    val (processingWidth, processingHeight) =
        boundedMarkDimensions(source.width, source.height, MARK_DECODE_MAX_SIDE)
    val processing =
        if (processingWidth == source.width && processingHeight == source.height) {
            source
        } else {
            source.scale(processingWidth, processingHeight)
        }
    try {
        val pixels = IntArray(processing.width * processing.height)
        processing.getPixels(pixels, 0, processing.width, 0, 0, processing.width, processing.height)
        pixels.indices.forEach { index -> pixels[index] = whiteToTransparentArgb(pixels[index]) }
        val bounds = visiblePixelBounds(processing.width, processing.height, pixels)
            ?: throw IOException("The selected mark has no visible ink")
        val croppedPixels = cropArgbPixels(processing.width, processing.height, pixels, bounds)
        val cropped =
            Bitmap.createBitmap(croppedPixels, bounds.width, bounds.height, Bitmap.Config.ARGB_8888)
        val (outputWidth, outputHeight) =
            boundedMarkDimensions(cropped.width, cropped.height, MARK_OUTPUT_MAX_SIDE)
        if (outputWidth == cropped.width && outputHeight == cropped.height) return cropped
        return try {
            cropped.scale(outputWidth, outputHeight)
        } finally {
            cropped.recycle()
        }
    } finally {
        if (processing !== source) processing.recycle()
    }
}

private fun decodeMarkBitmap(
    file: File,
    maxSide: Int,
): Bitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
        val (width, height) = boundedMarkDimensions(info.size.width, info.size.height, maxSide)
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetSize(width, height)
    }

private fun requireCanonicalMarkDirectory(directory: File): File {
    val absolute = directory.absoluteFile
    if (!absolute.isDirectory || absolute.canonicalFile != absolute) {
        throw IOException("The mark template directory is unsafe")
    }
    return absolute
}

private fun canonicalMarkTemplateFile(
    directory: File,
    id: String,
): File? {
    if (!isValidMarkTemplateId(id)) return null
    val file = File(directory, id).absoluteFile
    if (file.parentFile != directory) return null
    if (file.exists() && file.canonicalFile != file) return null
    return file
}

private fun markTemplateTimestamp(id: String): Long =
    id.substringAfter("mark_").substringBefore(".png").toLong()
