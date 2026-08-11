package com.majkeylab.scanit

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal const val OUTPUT_METADATA_FILE_NAME = "outputs.json"
internal const val OUTPUT_METADATA_TEMP_FILE_NAME = ".outputs.json.tmp"
internal const val MAX_OUTPUT_METADATA_BYTES = 64 * 1024

private const val OUTPUT_METADATA_VERSION = 1
private const val MAX_CACHE_ID_LENGTH = 128
private const val MAX_CONTENT_URI_LENGTH = 4096

internal data class PdfOutputRef(
    val uri: String,
    val treeUri: String?,
    val displayName: String? = null,
    val mimeType: String? = null,
    val ownerPackageName: String? = null,
    val byteLength: Long? = null,
    val sha256: String? = null,
)

internal data class ImageOutputRef(
    val page: Int,
    val uri: String,
    val displayName: String? = null,
    val mimeType: String? = null,
    val ownerPackageName: String? = null,
    val byteLength: Long? = null,
    val sha256: String? = null,
)

internal data class OutputMetadata(
    val entryId: String,
    val cacheId: String,
    val createdAtEpochMs: Long,
    val pdf: PdfOutputRef? = null,
    val images: List<ImageOutputRef> = emptyList(),
    val removeRecentPending: Boolean = false,
)

internal sealed interface OutputMetadataReadResult {
    data class Valid(val metadata: OutputMetadata) : OutputMetadataReadResult

    data object Invalid : OutputMetadataReadResult

    data object Failed : OutputMetadataReadResult
}

internal fun encodeOutputMetadata(metadata: OutputMetadata, pageCount: Int): ByteArray {
    if (!isValidOutputMetadata(metadata, metadata.cacheId, pageCount)) {
        throw IllegalArgumentException("Output metadata is invalid")
    }
    val json =
        JSONObject()
            .put("version", OUTPUT_METADATA_VERSION)
            .put("entryId", metadata.entryId)
            .put("cacheId", metadata.cacheId)
            .put("createdAtEpochMs", metadata.createdAtEpochMs)
    metadata.pdf?.let { pdf ->
        val value = JSONObject().put("uri", pdf.uri)
        pdf.treeUri?.let { value.put("treeUri", it) }
        pdf.displayName?.let { value.put("displayName", it) }
        pdf.mimeType?.let { value.put("mimeType", it) }
        pdf.ownerPackageName?.let { value.put("ownerPackageName", it) }
        pdf.byteLength?.let { value.put("byteLength", it) }
        pdf.sha256?.let { value.put("sha256", it) }
        json.put("pdf", value)
    }
    val images = JSONArray()
    metadata.images.forEach { image ->
        val value = JSONObject().put("page", image.page).put("uri", image.uri)
        image.displayName?.let { value.put("displayName", it) }
        image.mimeType?.let { value.put("mimeType", it) }
        image.ownerPackageName?.let { value.put("ownerPackageName", it) }
        image.byteLength?.let { value.put("byteLength", it) }
        image.sha256?.let { value.put("sha256", it) }
        images.put(value)
    }
    json.put("images", images)
    if (metadata.removeRecentPending) json.put("removeRecentPending", true)
    return json.toString().toByteArray(StandardCharsets.UTF_8).also { bytes ->
        if (bytes.size > MAX_OUTPUT_METADATA_BYTES) {
            throw IllegalArgumentException("Output metadata is too large")
        }
    }
}

internal fun decodeOutputMetadata(
    bytes: ByteArray,
    expectedCacheId: String,
    pageCount: Int,
): OutputMetadata? {
    if (bytes.isEmpty() || bytes.size > MAX_OUTPUT_METADATA_BYTES) return null
    return try {
        val json = JSONObject(String(bytes, StandardCharsets.UTF_8))
        if (
            !json.hasOnlyKeys(ROOT_KEYS, REQUIRED_ROOT_KEYS) ||
                json.strictInt("version") != OUTPUT_METADATA_VERSION
        ) {
            return null
        }
        val pdf =
            if (json.has("pdf")) {
                val value = json.opt("pdf") as? JSONObject ?: return null
                if (!value.hasOnlyKeys(PDF_KEYS, REQUIRED_PDF_KEYS)) return null
                PdfOutputRef(
                    uri = value.strictString("uri") ?: return null,
                    treeUri = if (value.has("treeUri")) value.strictString("treeUri") ?: return null else null,
                    displayName =
                        if (value.has("displayName")) {
                            value.strictString("displayName") ?: return null
                        } else {
                            null
                        },
                    mimeType =
                        if (value.has("mimeType")) value.strictString("mimeType") ?: return null else null,
                    ownerPackageName =
                        if (value.has("ownerPackageName")) {
                            value.strictString("ownerPackageName") ?: return null
                        } else {
                            null
                        },
                    byteLength =
                        if (value.has("byteLength")) value.strictLong("byteLength") ?: return null else null,
                    sha256 =
                        if (value.has("sha256")) value.strictString("sha256") ?: return null else null,
                )
            } else {
                null
            }
        val imageValues = json.opt("images") as? JSONArray ?: return null
        val images =
            buildList {
                repeat(imageValues.length()) { index ->
                    val value = imageValues.opt(index) as? JSONObject ?: return null
                    if (!value.hasOnlyKeys(IMAGE_KEYS, REQUIRED_IMAGE_KEYS)) return null
                    add(
                        ImageOutputRef(
                            page = value.strictInt("page") ?: return null,
                            uri = value.strictString("uri") ?: return null,
                            displayName =
                                if (value.has("displayName")) {
                                    value.strictString("displayName") ?: return null
                                } else {
                                    null
                                },
                            mimeType =
                                if (value.has("mimeType")) {
                                    value.strictString("mimeType") ?: return null
                                } else {
                                    null
                                },
                            ownerPackageName =
                                if (value.has("ownerPackageName")) {
                                    value.strictString("ownerPackageName") ?: return null
                                } else {
                                    null
                                },
                            byteLength =
                                if (value.has("byteLength")) {
                                    value.strictLong("byteLength") ?: return null
                                } else {
                                    null
                                },
                            sha256 =
                                if (value.has("sha256")) {
                                    value.strictString("sha256") ?: return null
                                } else {
                                    null
                                },
                        ),
                    )
                }
            }
        val metadata =
            OutputMetadata(
                entryId = json.strictString("entryId") ?: return null,
                cacheId = json.strictString("cacheId") ?: return null,
                createdAtEpochMs = json.strictLong("createdAtEpochMs") ?: return null,
                pdf = pdf,
                images = images,
                removeRecentPending =
                    if (json.has("removeRecentPending")) {
                        json.strictBoolean("removeRecentPending") ?: return null
                    } else {
                        false
                    },
            )
        metadata.takeIf { isValidOutputMetadata(it, expectedCacheId, pageCount) }
    } catch (_: Exception) {
        null
    }
}

internal fun readOutputMetadata(
    directory: File,
    cacheId: String,
    pageCount: Int,
): OutputMetadata? =
    (readOutputMetadataResult(directory, cacheId, pageCount) as? OutputMetadataReadResult.Valid)
        ?.metadata

internal fun readOutputMetadataResult(
    directory: File,
    cacheId: String,
    pageCount: Int,
    readBytes: (File) -> ByteArray = { it.readBytes() },
): OutputMetadataReadResult {
    val file = File(directory, OUTPUT_METADATA_FILE_NAME).absoluteFile
    return try {
        if (
            file.parentFile != directory.absoluteFile ||
                !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                file.length() !in 1..MAX_OUTPUT_METADATA_BYTES.toLong()
        ) {
            return OutputMetadataReadResult.Invalid
        }
        decodeOutputMetadata(readBytes(file), cacheId, pageCount)
            ?.let { OutputMetadataReadResult.Valid(it) }
            ?: OutputMetadataReadResult.Invalid
    } catch (_: IOException) {
        OutputMetadataReadResult.Failed
    } catch (_: SecurityException) {
        OutputMetadataReadResult.Failed
    }
}

internal fun initializeOutputMetadata(
    directory: File,
    cacheId: String,
    pageCount: Int,
    createdAtEpochMs: Long,
    entryId: String = UUID.randomUUID().toString(),
): OutputMetadata {
    val file = File(directory, OUTPUT_METADATA_FILE_NAME)
    if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
        throw IOException("Output metadata already exists")
    }
    return OutputMetadata(entryId, cacheId, createdAtEpochMs).also {
        writeOutputMetadata(directory, it, pageCount)
    }
}

internal fun ensureOutputMetadata(
    directory: File,
    cacheId: String,
    pageCount: Int,
    createdAtEpochMs: Long,
): OutputMetadata {
    val file = File(directory, OUTPUT_METADATA_FILE_NAME)
    if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
        return initializeOutputMetadata(directory, cacheId, pageCount, createdAtEpochMs)
    }
    return readOutputMetadata(directory, cacheId, pageCount)
        ?: throw IOException("Output metadata is invalid")
}

internal fun rewriteOutputMetadata(
    directory: File,
    expectedCacheId: String,
    expectedEntryId: String,
    pageCount: Int,
    moveMetadata: (Path, Path) -> Unit = ::moveOutputMetadataAtomically,
    update: (OutputMetadata) -> OutputMetadata,
): OutputMetadata {
    val current =
        readOutputMetadata(directory, expectedCacheId, pageCount)
            ?: throw IOException("Output metadata is unavailable")
    if (current.entryId != expectedEntryId) {
        throw IOException("Output metadata belongs to another cache generation")
    }
    val updated = update(current)
    if (
        updated.entryId != current.entryId ||
            updated.cacheId != current.cacheId ||
            updated.createdAtEpochMs != current.createdAtEpochMs
    ) {
        throw IOException("Output metadata identity cannot change")
    }
    writeOutputMetadata(directory, updated, pageCount, moveMetadata)
    return updated
}

internal fun requireExactProviderCopy(
    expectedLength: Long,
    copiedLength: Long,
    reportedLength: Long?,
    recount: () -> Long,
) {
    if (expectedLength <= 0L || copiedLength != expectedLength) {
        throw IOException("Provider copy length differs from its source")
    }
    val destinationLength = reportedLength?.takeIf { it >= 0L } ?: recount()
    if (destinationLength != expectedLength) {
        throw IOException("Provider destination length differs from its source")
    }
}

internal fun countBytesAtMost(input: InputStream, limit: Long): Long {
    require(limit > 0L) { "Byte count limit must be positive" }
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (total < limit) {
        val requested = minOf(buffer.size.toLong(), limit - total).toInt()
        val read = input.read(buffer, 0, requested)
        if (read < 0) break
        if (read == 0) {
            if (input.read() < 0) break
            total++
        } else {
            total += read
        }
    }
    return total
}

private fun writeOutputMetadata(
    directory: File,
    metadata: OutputMetadata,
    pageCount: Int,
    moveMetadata: (Path, Path) -> Unit = ::moveOutputMetadataAtomically,
) {
    val absoluteDirectory = directory.absoluteFile
    if (
        absoluteDirectory.canonicalFile != absoluteDirectory ||
            !Files.isDirectory(absoluteDirectory.toPath(), LinkOption.NOFOLLOW_LINKS)
    ) {
        throw IOException("Output metadata directory is unsafe")
    }
    val target = File(absoluteDirectory, OUTPUT_METADATA_FILE_NAME)
    val temporary = File(absoluteDirectory, OUTPUT_METADATA_TEMP_FILE_NAME)
    val bytes = encodeOutputMetadata(metadata, pageCount)
    try {
        Files.deleteIfExists(temporary.toPath())
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        if (
            decodeOutputMetadata(temporary.readBytes(), metadata.cacheId, pageCount) != metadata
        ) {
            throw IOException("Staged output metadata could not be verified")
        }
        moveMetadata(temporary.toPath(), target.toPath())
    } catch (failure: Exception) {
        try {
            Files.deleteIfExists(temporary.toPath())
        } catch (cleanupFailure: Exception) {
            failure.addSuppressed(cleanupFailure)
        }
        throw IOException("Output metadata could not be written", failure)
    }
}

private fun moveOutputMetadataAtomically(source: Path, target: Path) {
    Files.move(
        source,
        target,
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
    )
}

private fun isValidOutputMetadata(
    metadata: OutputMetadata,
    expectedCacheId: String,
    pageCount: Int,
): Boolean {
    if (
        pageCount <= 0 ||
            metadata.cacheId != expectedCacheId ||
            metadata.cacheId.length > MAX_CACHE_ID_LENGTH ||
            !isSafeCacheId(metadata.cacheId) ||
            metadata.createdAtEpochMs < 0L ||
            !isCanonicalUuid(metadata.entryId) ||
            metadata.images.size > pageCount ||
            metadata.images != metadata.images.sortedBy(ImageOutputRef::page) ||
            metadata.images.map(ImageOutputRef::page).distinct().size != metadata.images.size
    ) {
        return false
    }
    if (
        metadata.pdf?.let { pdf ->
            !isContentUri(pdf.uri) ||
                (pdf.treeUri != null && !isContentUri(pdf.treeUri)) ||
                !isValidOutputFingerprint(pdf.byteLength, pdf.sha256) ||
                if (pdf.treeUri == null) {
                    !isValidMediaIdentity(
                        pdf.displayName,
                        pdf.mimeType,
                        pdf.ownerPackageName,
                        PDF_MIME_TYPE,
                    )
                } else {
                    !isProviderDisplayName(pdf.displayName) ||
                        pdf.mimeType != PDF_MIME_TYPE ||
                        pdf.ownerPackageName != null
                }
        } == true
    ) {
        return false
    }
    return metadata.images.all { image ->
        image.page in 1..pageCount &&
            isContentUri(image.uri) &&
            isValidOutputFingerprint(image.byteLength, image.sha256) &&
            isValidMediaIdentity(
                image.displayName,
                image.mimeType,
                image.ownerPackageName,
                JPEG_MIME_TYPE,
            )
    }
}

internal fun isCanonicalUuid(value: String): Boolean =
    try {
        value == value.lowercase(Locale.ROOT) && UUID.fromString(value).toString() == value
    } catch (_: IllegalArgumentException) {
        false
    }

private fun isContentUri(value: String): Boolean {
    if (value.length !in 1..MAX_CONTENT_URI_LENGTH) return false
    return try {
        val uri = URI(value)
        uri.scheme == "content" && !uri.rawAuthority.isNullOrBlank() && uri.rawFragment == null
    } catch (_: Exception) {
        false
    }
}

internal fun isProviderDisplayName(value: String?): Boolean =
    value != null && value.length in 1..255 && value.none(Char::isISOControl)

private fun isValidMediaIdentity(
    displayName: String?,
    mimeType: String?,
    ownerPackageName: String?,
    requiredMimeType: String,
): Boolean {
    val values = listOf(displayName, mimeType, ownerPackageName)
    if (values.all { it == null }) return true
    return isProviderDisplayName(displayName) &&
        mimeType == requiredMimeType &&
        (ownerPackageName == null ||
            ownerPackageName.length in 1..255 && ownerPackageName.none(Char::isISOControl))
}

private fun JSONObject.hasOnlyKeys(
    allowed: Set<String>,
    required: Set<String> = allowed,
): Boolean {
    val present = keys().asSequence().toSet()
    return present.all { it in allowed } && present.containsAll(required)
}

private fun JSONObject.strictString(key: String): String? = opt(key) as? String

private fun JSONObject.strictInt(key: String): Int? =
    when (val value = opt(key)) {
        is Int -> value
        is Long -> value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
        null -> null
        else -> null
    }

private fun JSONObject.strictLong(key: String): Long? =
    when (val value = opt(key)) {
        is Int -> value.toLong()
        is Long -> value
        null -> null
        else -> null
    }

private fun JSONObject.strictBoolean(key: String): Boolean? = opt(key) as? Boolean

private val ROOT_KEYS =
    setOf(
        "version",
        "entryId",
        "cacheId",
        "createdAtEpochMs",
        "pdf",
        "images",
        "removeRecentPending",
    )
private val REQUIRED_ROOT_KEYS = ROOT_KEYS - setOf("pdf", "removeRecentPending")
private val PDF_KEYS =
    setOf("uri", "treeUri", "displayName", "mimeType", "ownerPackageName", "byteLength", "sha256")
private val REQUIRED_PDF_KEYS = setOf("uri")
private val IMAGE_KEYS =
    setOf("page", "uri", "displayName", "mimeType", "ownerPackageName", "byteLength", "sha256")
private val REQUIRED_IMAGE_KEYS = setOf("page", "uri")
private const val PDF_MIME_TYPE = "application/pdf"
private const val JPEG_MIME_TYPE = "image/jpeg"
