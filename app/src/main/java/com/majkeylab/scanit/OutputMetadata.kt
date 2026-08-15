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

internal const val OUTPUT_METADATA_VERSION = 3
private const val OUTPUT_METADATA_VERSION_2 = 2
private const val OUTPUT_METADATA_LEGACY_VERSION = 1
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
    val pending: Boolean = false,
)

internal data class ImageOutputRef(
    val page: Int,
    val uri: String,
    val displayName: String? = null,
    val mimeType: String? = null,
    val ownerPackageName: String? = null,
    val byteLength: Long? = null,
    val sha256: String? = null,
    val pending: Boolean = false,
    val treeUri: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val format: ImageExportFormat? = null,
)

internal data class OutputMetadata(
    val entryId: String,
    val cacheId: String,
    val createdAtEpochMs: Long,
    val pdf: PdfOutputRef? = null,
    val images: List<ImageOutputRef> = emptyList(),
    val removeRecentPending: Boolean = false,
    val stagedPdf: PdfOutputRef? = null,
    val stagedImages: List<ImageOutputRef> = emptyList(),
    val retiredPdf: PdfOutputRef? = null,
    val retiredImages: List<ImageOutputRef> = emptyList(),
    val version: Int = OUTPUT_METADATA_VERSION_2,
)

internal fun OutputMetadata.outputTreeUris(): Set<String> =
    buildSet {
        listOfNotNull(pdf, stagedPdf, retiredPdf).mapNotNullTo(this) { it.treeUri }
        listOf(images, stagedImages, retiredImages).flatten().mapNotNullTo(this) { it.treeUri }
    }

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
            .put("version", metadata.version)
            .put("entryId", metadata.entryId)
            .put("cacheId", metadata.cacheId)
            .put("createdAtEpochMs", metadata.createdAtEpochMs)
    metadata.pdf?.let { json.put("pdf", it.toJson()) }
    json.put("images", metadata.images.toJson())
    if (metadata.removeRecentPending) json.put("removeRecentPending", true)
    if (metadata.version == OUTPUT_METADATA_VERSION) {
        metadata.stagedPdf?.let { json.put("stagedPdf", it.toJson()) }
        if (metadata.stagedImages.isNotEmpty()) {
            json.put("stagedImages", metadata.stagedImages.toJson())
        }
        metadata.retiredPdf?.let { json.put("retiredPdf", it.toJson()) }
        if (metadata.retiredImages.isNotEmpty()) {
            json.put("retiredImages", metadata.retiredImages.toJson())
        }
    }
    return json.toString().toByteArray(StandardCharsets.UTF_8).also { bytes ->
        if (bytes.size > MAX_OUTPUT_METADATA_BYTES) {
            throw IllegalArgumentException("Output metadata is too large")
        }
    }
}

private fun PdfOutputRef.toJson(): JSONObject =
    JSONObject().put("uri", uri).also { value ->
        treeUri?.let { value.put("treeUri", it) }
        displayName?.let { value.put("displayName", it) }
        mimeType?.let { value.put("mimeType", it) }
        ownerPackageName?.let { value.put("ownerPackageName", it) }
        byteLength?.let { value.put("byteLength", it) }
        sha256?.let { value.put("sha256", it) }
        if (pending) value.put("pending", true)
    }

private fun List<ImageOutputRef>.toJson(): JSONArray =
    JSONArray().also { values ->
        forEach { image ->
            values.put(
                JSONObject().put("page", image.page).put("uri", image.uri).also { value ->
                    image.treeUri?.let { value.put("treeUri", it) }
                    image.displayName?.let { value.put("displayName", it) }
                    image.mimeType?.let { value.put("mimeType", it) }
                    image.ownerPackageName?.let { value.put("ownerPackageName", it) }
                    image.byteLength?.let { value.put("byteLength", it) }
                    image.sha256?.let { value.put("sha256", it) }
                    if (image.pending) value.put("pending", true)
                    image.width?.let { value.put("width", it) }
                    image.height?.let { value.put("height", it) }
                    image.format?.let { value.put("format", it.wireValue) }
                },
            )
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
        val version = json.strictInt("version") ?: return null
        val rootKeys = if (version == OUTPUT_METADATA_VERSION) ROOT_KEYS_V3 else ROOT_KEYS_V2
        if (version !in SUPPORTED_OUTPUT_METADATA_VERSIONS ||
            !json.hasOnlyKeys(rootKeys, REQUIRED_ROOT_KEYS)
        ) return null
        val pdf =
            if (json.has("pdf")) {
                val value = json.opt("pdf") as? JSONObject ?: return null
                decodePdfOutputRef(value, version) ?: return null
            } else {
                null
            }
        val imageValues = json.opt("images") as? JSONArray ?: return null
        val images = decodeImageOutputRefs(imageValues, version) ?: return null
        val stagedPdf = json.optionalPdfOutputRef("stagedPdf", version) ?: return null
        val stagedImages = json.optionalImageOutputRefs("stagedImages", version) ?: return null
        val retiredPdf = json.optionalPdfOutputRef("retiredPdf", version) ?: return null
        val retiredImages = json.optionalImageOutputRefs("retiredImages", version) ?: return null
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
                stagedPdf = stagedPdf.value,
                stagedImages = stagedImages.value ?: return null,
                retiredPdf = retiredPdf.value,
                retiredImages = retiredImages.value ?: return null,
                version = version,
            )
        metadata.takeIf { isValidOutputMetadata(it, expectedCacheId, pageCount) }
    } catch (_: Exception) {
        null
    }
}

private data class DecodedOptional<T>(val value: T?)

private fun JSONObject.optionalPdfOutputRef(
    key: String,
    version: Int,
): DecodedOptional<PdfOutputRef>? {
    if (!has(key)) return DecodedOptional(null)
    val value = opt(key) as? JSONObject ?: return null
    return DecodedOptional(decodePdfOutputRef(value, version) ?: return null)
}

private fun JSONObject.optionalImageOutputRefs(
    key: String,
    version: Int,
): DecodedOptional<List<ImageOutputRef>>? {
    if (!has(key)) return DecodedOptional(emptyList())
    val values = opt(key) as? JSONArray ?: return null
    return DecodedOptional(decodeImageOutputRefs(values, version) ?: return null)
}

private fun decodePdfOutputRef(
    value: JSONObject,
    version: Int,
): PdfOutputRef? {
    if (!value.hasOnlyKeys(PDF_KEYS, REQUIRED_PDF_KEYS) ||
        version == OUTPUT_METADATA_LEGACY_VERSION && value.has("pending")
    ) return null
    return PdfOutputRef(
        uri = value.strictString("uri") ?: return null,
        treeUri = (value.optionalString("treeUri") ?: return null).value,
        displayName = (value.optionalString("displayName") ?: return null).value,
        mimeType = (value.optionalString("mimeType") ?: return null).value,
        ownerPackageName = (value.optionalString("ownerPackageName") ?: return null).value,
        byteLength = (value.optionalLong("byteLength") ?: return null).value,
        sha256 = (value.optionalString("sha256") ?: return null).value,
        pending = value.optionalBoolean("pending") ?: return null,
    )
}

private fun decodeImageOutputRefs(
    values: JSONArray,
    version: Int,
): List<ImageOutputRef>? =
    buildList {
        repeat(values.length()) { index ->
            val value = values.opt(index) as? JSONObject ?: return null
            val keys = if (version == OUTPUT_METADATA_VERSION) IMAGE_KEYS_V3 else IMAGE_KEYS_V2
            if (!value.hasOnlyKeys(keys, REQUIRED_IMAGE_KEYS) ||
                version == OUTPUT_METADATA_LEGACY_VERSION && value.has("pending")
            ) return null
            add(
                ImageOutputRef(
                    page = value.strictInt("page") ?: return null,
                    uri = value.strictString("uri") ?: return null,
                    displayName = (value.optionalString("displayName") ?: return null).value,
                    mimeType = (value.optionalString("mimeType") ?: return null).value,
                    ownerPackageName =
                        (value.optionalString("ownerPackageName") ?: return null).value,
                    byteLength = (value.optionalLong("byteLength") ?: return null).value,
                    sha256 = (value.optionalString("sha256") ?: return null).value,
                    pending = value.optionalBoolean("pending") ?: return null,
                    treeUri = (value.optionalString("treeUri") ?: return null).value,
                    width = (value.optionalInt("width") ?: return null).value,
                    height = (value.optionalInt("height") ?: return null).value,
                    format =
                        if (value.has("format")) {
                            val wireValue = value.strictString("format") ?: return null
                            ImageExportFormat.entries.firstOrNull { it.wireValue == wireValue }
                                ?: return null
                        } else {
                            null
                        },
                ),
            )
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
    val requested = update(current)
    val updated =
        if (current.version == OUTPUT_METADATA_LEGACY_VERSION &&
            requested.version == OUTPUT_METADATA_LEGACY_VERSION
        ) requested.copy(version = OUTPUT_METADATA_VERSION_2) else requested
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
        pageCount !in 1..MAX_SCAN_PAGES ||
            metadata.version !in SUPPORTED_OUTPUT_METADATA_VERSIONS ||
            metadata.cacheId != expectedCacheId ||
            metadata.cacheId.length > MAX_CACHE_ID_LENGTH ||
            !isSafeCacheId(metadata.cacheId) ||
            metadata.createdAtEpochMs < 0L ||
            !isCanonicalUuid(metadata.entryId) ||
            !metadata.images.hasValidPageOrder(pageCount, pageCount)
    ) {
        return false
    }
    if (metadata.version != OUTPUT_METADATA_VERSION && metadata.hasV3Data()) return false
    if (metadata.version == OUTPUT_METADATA_LEGACY_VERSION &&
        (metadata.pdf?.pending == true || metadata.images.any(ImageOutputRef::pending))
    ) return false
    if (metadata.version == OUTPUT_METADATA_VERSION &&
        (!metadata.stagedImages.hasValidPageOrder(pageCount, MAX_SCAN_PAGES) ||
            !metadata.retiredImages.hasValidPageOrder(pageCount, MAX_SCAN_PAGES))
    ) return false

    val pdfs = listOfNotNull(metadata.pdf, metadata.stagedPdf, metadata.retiredPdf)
    val imageLists = listOf(metadata.images, metadata.stagedImages, metadata.retiredImages)
    if (pdfs.any { !it.isValid(metadata.version) } ||
        imageLists.flatten().any { !it.isValid(metadata.version) }
    ) return false
    val uris = pdfs.map(PdfOutputRef::uri) + imageLists.flatten().map(ImageOutputRef::uri)
    if (uris.size != uris.distinct().size) {
        return false
    }
    return true
}

private fun OutputMetadata.hasV3Data(): Boolean =
    stagedPdf != null ||
        stagedImages.isNotEmpty() ||
        retiredPdf != null ||
        retiredImages.isNotEmpty() ||
        images.any(ImageOutputRef::hasV3Data)

private fun ImageOutputRef.hasV3Data(): Boolean =
    treeUri != null || width != null || height != null || format != null

private fun List<ImageOutputRef>.hasValidPageOrder(
    pageCount: Int,
    maximumSize: Int,
): Boolean =
    size <= maximumSize &&
        all { it.page in 1..pageCount } &&
        this == sortedBy(ImageOutputRef::page) &&
        map(ImageOutputRef::page).distinct().size == size

private fun PdfOutputRef.isValid(version: Int): Boolean {
    if (!isContentUri(uri) ||
        treeUri != null && !isContentUri(treeUri) ||
        pending && treeUri != null ||
        !isValidOutputFingerprint(byteLength, sha256)
    ) return false
    if (version == OUTPUT_METADATA_VERSION) return hasExactIdentity()
    if (pending && !hasPendingMediaIdentity(PDF_MIME_TYPE)) return false
    return if (treeUri == null) {
        isValidMediaIdentity(displayName, mimeType, ownerPackageName, PDF_MIME_TYPE)
    } else {
        isProviderDisplayName(displayName) &&
            mimeType == PDF_MIME_TYPE &&
            ownerPackageName == null
    }
}

private fun PdfOutputRef.hasExactIdentity(): Boolean =
    outputFingerprint() != null &&
        isProviderDisplayName(displayName) &&
        mimeType == PDF_MIME_TYPE &&
        if (treeUri == null) isValidOwnerPackageName(ownerPackageName) else ownerPackageName == null

private fun ImageOutputRef.isValid(version: Int): Boolean {
    if (!isContentUri(uri) ||
        treeUri != null && !isContentUri(treeUri) ||
        pending && treeUri != null ||
        !isValidOutputFingerprint(byteLength, sha256)
    ) return false
    if (version != OUTPUT_METADATA_VERSION) {
        return (!pending || hasPendingMediaIdentity(JPEG_MIME_TYPE)) &&
            isValidMediaIdentity(displayName, mimeType, ownerPackageName, JPEG_MIME_TYPE)
    }
    val actualFormat = format ?: return false
    val actualWidth = width ?: return false
    val actualHeight = height ?: return false
    val actualMimeType = mimeType ?: return false
    if (actualWidth <= 0 ||
        actualHeight <= 0 ||
        actualWidth.toLong() * actualHeight > MAX_IMAGE_EXPORT_PIXELS ||
        actualMimeType !in IMAGE_MIME_TYPES ||
        actualFormat.mimeType?.let { it != actualMimeType } == true ||
        outputFingerprint() == null ||
        !isProviderDisplayName(displayName)
    ) return false
    return if (treeUri == null) isValidOwnerPackageName(ownerPackageName) else ownerPackageName == null
}

private fun PdfOutputRef.hasPendingMediaIdentity(requiredMimeType: String): Boolean =
    isProviderDisplayName(displayName) &&
        mimeType == requiredMimeType &&
        !ownerPackageName.isNullOrBlank() &&
        outputFingerprint() != null

private fun ImageOutputRef.hasPendingMediaIdentity(requiredMimeType: String): Boolean =
    isProviderDisplayName(displayName) &&
        mimeType == requiredMimeType &&
        !ownerPackageName.isNullOrBlank() &&
        outputFingerprint() != null

internal fun isCanonicalUuid(value: String): Boolean =
    try {
        value == value.lowercase(Locale.ROOT) && UUID.fromString(value).toString() == value
    } catch (_: IllegalArgumentException) {
        false
    }

internal fun isContentUri(value: String): Boolean {
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

internal fun upgradeLegacyImageReference(
    reference: ImageOutputRef,
    observedUri: String,
    observedDisplayName: String,
    observedMimeType: String,
    observedOwnerPackageName: String,
    expectedOwnerPackageName: String,
    fingerprint: OutputFingerprint,
    width: Int,
    height: Int,
): ImageOutputRef? {
    if (
        reference.uri != observedUri ||
            reference.treeUri != null ||
            reference.pending ||
            !isProviderDisplayName(observedDisplayName) ||
            !observedDisplayName.lowercase(Locale.ROOT).endsWith(".jpg") ||
            observedMimeType != JPEG_MIME_TYPE ||
            observedOwnerPackageName != expectedOwnerPackageName ||
            !isValidOwnerPackageName(expectedOwnerPackageName) ||
            reference.displayName?.let { it != observedDisplayName } == true ||
            reference.mimeType?.let { it != observedMimeType } == true ||
            reference.ownerPackageName?.let { it != observedOwnerPackageName } == true ||
            reference.byteLength?.let { it != fingerprint.byteLength } == true ||
            reference.sha256?.let { it != fingerprint.sha256 } == true ||
            width <= 0 ||
            height <= 0 ||
            width.toLong() * height > MAX_IMAGE_EXPORT_PIXELS
    ) return null
    return reference.copy(
        displayName = observedDisplayName,
        mimeType = observedMimeType,
        ownerPackageName = observedOwnerPackageName,
        byteLength = fingerprint.byteLength,
        sha256 = fingerprint.sha256,
        pending = false,
        width = width,
        height = height,
        format = ImageExportFormat.Jpeg,
    )
}

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
        (ownerPackageName == null || isValidOwnerPackageName(ownerPackageName))
}

private fun isValidOwnerPackageName(value: String?): Boolean =
    value != null && value.isNotBlank() && value.length <= 255 && value.none(Char::isISOControl)

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

private fun JSONObject.optionalString(key: String): DecodedOptional<String>? =
    if (!has(key)) DecodedOptional(null) else strictString(key)?.let(::DecodedOptional)

private fun JSONObject.optionalInt(key: String): DecodedOptional<Int>? =
    if (!has(key)) DecodedOptional(null) else strictInt(key)?.let(::DecodedOptional)

private fun JSONObject.optionalLong(key: String): DecodedOptional<Long>? =
    if (!has(key)) DecodedOptional(null) else strictLong(key)?.let(::DecodedOptional)

private fun JSONObject.optionalBoolean(key: String): Boolean? =
    if (!has(key)) false else strictBoolean(key)

private val SUPPORTED_OUTPUT_METADATA_VERSIONS =
    setOf(OUTPUT_METADATA_LEGACY_VERSION, OUTPUT_METADATA_VERSION_2, OUTPUT_METADATA_VERSION)
private val ROOT_KEYS_V2 =
    setOf(
        "version",
        "entryId",
        "cacheId",
        "createdAtEpochMs",
        "pdf",
        "images",
        "removeRecentPending",
    )
private val ROOT_KEYS_V3 =
    ROOT_KEYS_V2 +
        setOf(
            "stagedPdf",
            "stagedImages",
            "retiredPdf",
            "retiredImages",
        )
private val REQUIRED_ROOT_KEYS = ROOT_KEYS_V2 - setOf("pdf", "removeRecentPending")
private val PDF_KEYS =
    setOf(
        "uri",
        "treeUri",
        "displayName",
        "mimeType",
        "ownerPackageName",
        "byteLength",
        "sha256",
        "pending",
    )
private val REQUIRED_PDF_KEYS = setOf("uri")
private val IMAGE_KEYS_V2 =
    setOf(
        "page",
        "uri",
        "displayName",
        "mimeType",
        "ownerPackageName",
        "byteLength",
        "sha256",
        "pending",
    )
private val IMAGE_KEYS_V3 =
    IMAGE_KEYS_V2 + setOf("treeUri", "width", "height", "format")
private val REQUIRED_IMAGE_KEYS = setOf("page", "uri")
private const val PDF_MIME_TYPE = "application/pdf"
private const val JPEG_MIME_TYPE = "image/jpeg"
private const val PNG_MIME_TYPE = "image/png"
private val IMAGE_MIME_TYPES = setOf(JPEG_MIME_TYPE, PNG_MIME_TYPE)
