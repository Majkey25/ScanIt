package com.majkeylab.scanit

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import org.json.JSONObject

internal const val PROVISIONAL_OUTPUT_CREATE_FILE_NAME = ".output-create.json"
internal const val PROVISIONAL_OUTPUT_CREATE_TEMP_FILE_NAME = ".output-create.json.tmp"
private const val PROVISIONAL_OUTPUT_CREATE_VERSION = 1
private const val MAX_PROVISIONAL_OUTPUT_CREATE_BYTES = 8 * 1024
private const val MAX_MARKER_VALUE_LENGTH = 4096

internal enum class ProvisionalOutputKind {
    Pdf,
    Image,
}

internal enum class ProvisionalOutputProvider {
    MediaStore,
    Saf,
}

internal data class ProvisionalOutputCreate(
    val operationId: String,
    val cacheId: String,
    val entryId: String,
    val kind: ProvisionalOutputKind,
    val page: Int?,
    val provider: ProvisionalOutputProvider,
    val displayName: String,
    val mimeType: String,
    val treeUri: String?,
    val returnedUri: String?,
)

internal sealed interface ProvisionalOutputCreateReadResult {
    data object Absent : ProvisionalOutputCreateReadResult

    data class Valid(val marker: ProvisionalOutputCreate) : ProvisionalOutputCreateReadResult

    data object Invalid : ProvisionalOutputCreateReadResult

    data object Failed : ProvisionalOutputCreateReadResult
}

internal data class ProvisionalOutputCreateReconciliation(
    val blocking: Boolean,
    val warnings: List<UiMessage> = emptyList(),
    val acknowledgement: UnknownOutputCreateAcknowledgement? = null,
)

internal data class UnknownOutputCreateAcknowledgement(
    val cacheId: String,
    val entryId: String,
    val operationId: String,
)

internal enum class UnknownOutputAcknowledgementResult {
    Applied,
    Absent,
    Stale,
    Failed,
}

internal fun <T : Any> createProviderOutputWithMarker(
    beforeCreate: () -> Unit,
    create: () -> T?,
    onCreated: (T) -> Unit,
): T {
    beforeCreate()
    val created = create() ?: throw IOException("Provider output could not be created")
    onCreated(created)
    return created
}

internal fun writeProvisionalOutputCreate(
    directory: File,
    marker: ProvisionalOutputCreate,
    pageCount: Int,
) {
    writeProvisionalOutputCreate(directory, marker, pageCount, expected = null)
}

internal fun updateProvisionalOutputCreateUri(
    directory: File,
    expected: ProvisionalOutputCreate,
    returnedUri: String,
    pageCount: Int,
): ProvisionalOutputCreate =
    updateProvisionalOutputCreateIdentity(
        directory,
        expected,
        returnedUri,
        expected.displayName,
        pageCount,
    )

internal fun updateProvisionalOutputCreateIdentity(
    directory: File,
    expected: ProvisionalOutputCreate,
    returnedUri: String,
    displayName: String,
    pageCount: Int,
): ProvisionalOutputCreate {
    if (expected.returnedUri != null && expected.returnedUri != returnedUri) {
        throw IOException("Provisional output URI changed")
    }
    val updated = expected.copy(returnedUri = returnedUri, displayName = displayName)
    writeProvisionalOutputCreate(directory, updated, pageCount, expected)
    return updated
}

internal fun clearProvisionalOutputCreate(
    directory: File,
    expected: ProvisionalOutputCreate,
    pageCount: Int,
) {
    when (val current = readProvisionalOutputCreate(directory, expected.cacheId, expected.entryId, pageCount)) {
        ProvisionalOutputCreateReadResult.Absent -> return
        is ProvisionalOutputCreateReadResult.Valid -> {
            if (current.marker != expected) {
                throw IOException("Provisional output marker changed")
            }
        }
        ProvisionalOutputCreateReadResult.Invalid,
        ProvisionalOutputCreateReadResult.Failed,
        -> throw IOException("Provisional output marker is unavailable")
    }
    Files.delete(File(directory.absoluteFile, PROVISIONAL_OUTPUT_CREATE_FILE_NAME).toPath())
}

internal fun readProvisionalOutputCreate(
    directory: File,
    expectedCacheId: String,
    expectedEntryId: String,
    pageCount: Int,
): ProvisionalOutputCreateReadResult {
    val absoluteDirectory = directory.absoluteFile
    val target = File(absoluteDirectory, PROVISIONAL_OUTPUT_CREATE_FILE_NAME).absoluteFile
    return try {
        if (!target.exists()) return ProvisionalOutputCreateReadResult.Absent
        if (!isSafeMarkerDirectory(absoluteDirectory) ||
            target.parentFile != absoluteDirectory ||
            !Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS) ||
            target.length() !in 1..MAX_PROVISIONAL_OUTPUT_CREATE_BYTES.toLong()
        ) return ProvisionalOutputCreateReadResult.Invalid
        decodeProvisionalOutputCreate(target.readBytes(), expectedCacheId, expectedEntryId, pageCount)
            ?.let(ProvisionalOutputCreateReadResult::Valid)
            ?: ProvisionalOutputCreateReadResult.Invalid
    } catch (_: IOException) {
        ProvisionalOutputCreateReadResult.Failed
    } catch (_: SecurityException) {
        ProvisionalOutputCreateReadResult.Failed
    }
}

internal fun readUnknownOutputCreateAcknowledgement(
    directory: File,
    expectedCacheId: String,
    expectedEntryId: String,
): UnknownOutputCreateAcknowledgement? =
    readMarkerBytes(directory)?.let { bytes ->
        val acknowledgement = decodeUnknownOutputCreateAcknowledgement(bytes) ?: return@let null
        if (acknowledgement.cacheId != expectedCacheId || acknowledgement.entryId != expectedEntryId) {
            return@let null
        }
        acknowledgement
    }

internal fun acknowledgeUnknownProvisionalOutput(
    directory: File,
    acknowledgement: UnknownOutputCreateAcknowledgement,
    pageCount: Int,
    deleteMarker: (File) -> Unit = { Files.delete(it.toPath()) },
): UnknownOutputAcknowledgementResult {
    if (!isSafeCacheId(acknowledgement.cacheId) ||
        !isCanonicalUuid(acknowledgement.entryId) ||
        !isCanonicalUuid(acknowledgement.operationId) ||
        pageCount !in 1..MAX_SCAN_PAGES
    ) return UnknownOutputAcknowledgementResult.Stale
    val target = File(directory.absoluteFile, PROVISIONAL_OUTPUT_CREATE_FILE_NAME).absoluteFile
    if (!target.exists()) return UnknownOutputAcknowledgementResult.Absent
    val bytes = readMarkerBytes(directory) ?: return UnknownOutputAcknowledgementResult.Failed
    if (decodeUnknownOutputCreateAcknowledgement(bytes) != acknowledgement) {
        return UnknownOutputAcknowledgementResult.Stale
    }
    return try {
        deleteMarker(target)
        if (target.exists()) {
            UnknownOutputAcknowledgementResult.Failed
        } else {
            UnknownOutputAcknowledgementResult.Applied
        }
    } catch (_: IOException) {
        UnknownOutputAcknowledgementResult.Failed
    } catch (_: SecurityException) {
        UnknownOutputAcknowledgementResult.Failed
    }
}

internal fun reconcileProvisionalOutputCreate(
    marker: ProvisionalOutputCreate,
    metadata: OutputMetadata,
    delete: (ProvisionalOutputCreate) -> OutputDeleteStatus,
    clear: () -> Unit,
): ProvisionalOutputCreateReconciliation {
    val warning = UiMessage(R.string.output_create_cleanup_required)
    if (marker.cacheId != metadata.cacheId || marker.entryId != metadata.entryId) {
        return ProvisionalOutputCreateReconciliation(true, listOf(warning))
    }
    val returnedUri = marker.returnedUri
        ?: return ProvisionalOutputCreateReconciliation(
            true,
            listOf(warning),
            UnknownOutputCreateAcknowledgement(marker.cacheId, marker.entryId, marker.operationId),
        )
    if (metadata.hasStagedProvisionalOutput(marker, returnedUri)) {
        return clearResolvedProvisionalOutput(warning, clear)
    }
    return when (delete(marker)) {
        OutputDeleteStatus.Deleted,
        OutputDeleteStatus.Absent,
        -> {
            clearResolvedProvisionalOutput(warning, clear)
        }
        OutputDeleteStatus.IdentityMismatch,
        OutputDeleteStatus.Failed,
        -> ProvisionalOutputCreateReconciliation(true, listOf(warning))
    }
}

private fun clearResolvedProvisionalOutput(
    warning: UiMessage,
    clear: () -> Unit,
): ProvisionalOutputCreateReconciliation =
    try {
        clear()
        ProvisionalOutputCreateReconciliation(false)
    } catch (cancellation: java.util.concurrent.CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        ProvisionalOutputCreateReconciliation(true, listOf(warning))
    }

private fun OutputMetadata.hasStagedProvisionalOutput(
    marker: ProvisionalOutputCreate,
    returnedUri: String,
): Boolean =
    when (marker.kind) {
        ProvisionalOutputKind.Pdf ->
            stagedPdf?.let { output ->
                marker.page == null && output.uri == returnedUri &&
                    output.displayName == marker.displayName && output.mimeType == marker.mimeType &&
                    output.treeUri == marker.treeUri
            } == true
        ProvisionalOutputKind.Image ->
            stagedImages.singleOrNull { it.page == marker.page }?.let { output ->
                output.uri == returnedUri && output.displayName == marker.displayName &&
                    output.mimeType == marker.mimeType && output.treeUri == marker.treeUri
            } == true
    }

private fun writeProvisionalOutputCreate(
    directory: File,
    marker: ProvisionalOutputCreate,
    pageCount: Int,
    expected: ProvisionalOutputCreate?,
) {
    val absoluteDirectory = directory.absoluteFile
    if (!isSafeMarkerDirectory(absoluteDirectory) || !isValidProvisionalOutputCreate(marker, pageCount)) {
        throw IOException("Provisional output marker is invalid")
    }
    val target = File(absoluteDirectory, PROVISIONAL_OUTPUT_CREATE_FILE_NAME)
    val temporary = File(absoluteDirectory, PROVISIONAL_OUTPUT_CREATE_TEMP_FILE_NAME)
    if (expected == null) {
        if (target.exists()) throw IOException("Provisional output marker already exists")
    } else {
        val current = readProvisionalOutputCreate(absoluteDirectory, marker.cacheId, marker.entryId, pageCount)
        if (current != ProvisionalOutputCreateReadResult.Valid(expected)) {
            throw IOException("Provisional output marker changed")
        }
    }
    val bytes = encodeProvisionalOutputCreate(marker)
    try {
        Files.deleteIfExists(temporary.toPath())
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        if (decodeProvisionalOutputCreate(temporary.readBytes(), marker.cacheId, marker.entryId, pageCount) != marker) {
            throw IOException("Staged provisional output marker could not be verified")
        }
        if (expected == null) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } else {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    } catch (failure: Exception) {
        try {
            Files.deleteIfExists(temporary.toPath())
        } catch (cleanupFailure: Exception) {
            failure.addSuppressed(cleanupFailure)
        }
        throw IOException("Provisional output marker could not be written", failure)
    }
}

private fun encodeProvisionalOutputCreate(marker: ProvisionalOutputCreate): ByteArray =
    JSONObject()
        .put("version", PROVISIONAL_OUTPUT_CREATE_VERSION)
        .put("operationId", marker.operationId)
        .put("cacheId", marker.cacheId)
        .put("entryId", marker.entryId)
        .put("kind", marker.kind.name)
        .put("page", marker.page ?: JSONObject.NULL)
        .put("provider", marker.provider.name)
        .put("displayName", marker.displayName)
        .put("mimeType", marker.mimeType)
        .put("treeUri", marker.treeUri ?: JSONObject.NULL)
        .put("returnedUri", marker.returnedUri ?: JSONObject.NULL)
        .toString()
        .toByteArray(StandardCharsets.UTF_8)
        .also {
            if (it.size > MAX_PROVISIONAL_OUTPUT_CREATE_BYTES) {
                throw IOException("Provisional output marker is too large")
            }
        }

private fun decodeProvisionalOutputCreate(
    bytes: ByteArray,
    expectedCacheId: String,
    expectedEntryId: String,
    pageCount: Int,
): ProvisionalOutputCreate? {
    if (bytes.isEmpty() || bytes.size > MAX_PROVISIONAL_OUTPUT_CREATE_BYTES) return null
    return try {
        val value = JSONObject(String(bytes, StandardCharsets.UTF_8))
        val keys = value.keys().asSequence().toSet()
        if (keys != MARKER_KEYS || value.opt("version") !is Int ||
            value.getInt("version") != PROVISIONAL_OUTPUT_CREATE_VERSION
        ) return null
        val marker =
            ProvisionalOutputCreate(
                operationId = value.requiredString("operationId") ?: return null,
                cacheId = value.requiredString("cacheId") ?: return null,
                entryId = value.requiredString("entryId") ?: return null,
                kind = value.requiredEnum<ProvisionalOutputKind>("kind") ?: return null,
                page = (value.optionalInt("page") ?: return null).value,
                provider = value.requiredEnum<ProvisionalOutputProvider>("provider") ?: return null,
                displayName = value.requiredString("displayName") ?: return null,
                mimeType = value.requiredString("mimeType") ?: return null,
                treeUri = (value.optionalString("treeUri") ?: return null).value,
                returnedUri = (value.optionalString("returnedUri") ?: return null).value,
            )
        marker.takeIf {
            it.cacheId == expectedCacheId && it.entryId == expectedEntryId &&
                isValidProvisionalOutputCreate(it, pageCount)
        }
    } catch (_: Exception) {
        null
    }
}

private fun readMarkerBytes(directory: File): ByteArray? =
    try {
        val absoluteDirectory = directory.absoluteFile
        val target = File(absoluteDirectory, PROVISIONAL_OUTPUT_CREATE_FILE_NAME).absoluteFile
        if (!isSafeMarkerDirectory(absoluteDirectory) || target.parentFile != absoluteDirectory ||
            !Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS) ||
            target.length() !in 1..MAX_PROVISIONAL_OUTPUT_CREATE_BYTES.toLong()
        ) return null
        target.readBytes()
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

private fun decodeUnknownOutputCreateAcknowledgement(
    bytes: ByteArray,
): UnknownOutputCreateAcknowledgement? =
    try {
        val value = JSONObject(String(bytes, StandardCharsets.UTF_8))
        UnknownOutputCreateAcknowledgement(
            cacheId = value.requiredString("cacheId") ?: return null,
            entryId = value.requiredString("entryId") ?: return null,
            operationId = value.requiredString("operationId") ?: return null,
        ).takeIf {
            value.opt("version") is Int &&
                value.getInt("version") == PROVISIONAL_OUTPUT_CREATE_VERSION &&
                value.has("returnedUri") && value.isNull("returnedUri") &&
                isSafeCacheId(it.cacheId) && isCanonicalUuid(it.entryId) &&
                isCanonicalUuid(it.operationId)
        }
    } catch (_: Exception) {
        null
    }

private fun isValidProvisionalOutputCreate(marker: ProvisionalOutputCreate, pageCount: Int): Boolean {
    if (pageCount !in 1..MAX_SCAN_PAGES || !isCanonicalUuid(marker.operationId) ||
        !isSafeCacheId(marker.cacheId) || !isCanonicalUuid(marker.entryId) ||
        !isProviderDisplayName(marker.displayName) || !isMarkerValue(marker.mimeType) ||
        marker.returnedUri?.let(::isExactContentMarkerUri) == false
    ) return false
    if (marker.kind == ProvisionalOutputKind.Pdf && (marker.page != null || marker.mimeType != "application/pdf") ||
        marker.kind == ProvisionalOutputKind.Image &&
        (marker.page !in 1..pageCount || marker.mimeType !in setOf("image/jpeg", "image/png"))
    ) return false
    return when (marker.provider) {
        ProvisionalOutputProvider.MediaStore -> marker.treeUri == null
        ProvisionalOutputProvider.Saf -> marker.treeUri?.let(::isExactContentMarkerUri) == true
    }
}

private fun isExactContentMarkerUri(value: String): Boolean =
    try {
        val uri = URI(value)
        uri.scheme == "content" && !uri.rawAuthority.isNullOrBlank() && uri.rawQuery == null &&
            uri.rawFragment == null && uri.rawUserInfo == null && uri.port == -1 &&
            isMarkerValue(value)
    } catch (_: Exception) {
        false
    }

private fun isSafeMarkerDirectory(directory: File): Boolean =
    directory.canonicalFile == directory &&
        Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)

private fun isMarkerValue(value: String): Boolean =
    value.isNotBlank() && value.length <= MAX_MARKER_VALUE_LENGTH && value.none(Char::isISOControl)

private fun JSONObject.requiredString(key: String): String? =
    opt(key).takeIf { it is String } as? String

private fun JSONObject.optionalString(key: String): OptionalValue<String>? =
    when (val value = opt(key)) {
        null -> null
        JSONObject.NULL -> OptionalValue(null)
        is String -> OptionalValue(value)
        else -> null
    }

private fun JSONObject.optionalInt(key: String): OptionalValue<Int>? =
    when (val value = opt(key)) {
        null -> null
        JSONObject.NULL -> OptionalValue(null)
        is Int -> OptionalValue(value)
        else -> null
    }

private inline fun <reified T : Enum<T>> JSONObject.requiredEnum(key: String): T? =
    requiredString(key)?.let { name -> enumValues<T>().singleOrNull { it.name == name } }

private data class OptionalValue<T>(val value: T?)

private val MARKER_KEYS =
    setOf(
        "version",
        "operationId",
        "cacheId",
        "entryId",
        "kind",
        "page",
        "provider",
        "displayName",
        "mimeType",
        "treeUri",
        "returnedUri",
    )
