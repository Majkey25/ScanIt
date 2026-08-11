package com.majkeylab.scanit

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal const val SCAN_APPEARANCE_FILE_NAME = "appearance.txt"
internal const val SCAN_APPEARANCE_TEMP_FILE_NAME = ".appearance.txt.tmp"

internal data class ScanAppearanceMetadata(
    val appearance: ScanAppearance,
    val appearanceSettings: ScanAppearanceSettings?,
    val pdfSizeTarget: PdfSizeTarget,
    val lineageCacheId: String,
    val parentCacheId: String?,
    val parentEntryId: String?,
    val restoreSettingsOnActivation: Boolean = true,
)

internal fun encodeScanAppearanceMetadata(
    appearanceSettings: ScanAppearanceSettings,
    pdfSizeTarget: PdfSizeTarget,
    lineageCacheId: String,
    parentCacheId: String? = null,
    parentEntryId: String? = null,
    restoreSettingsOnActivation: Boolean = true,
): ByteArray {
    requireSafeAppearanceIdentity(lineageCacheId, parentCacheId, parentEntryId)
    val normalized = normalizeMetadataAppearance(appearanceSettings)
    val relation =
        if (parentCacheId == null) {
            "initial\n"
        } else {
            "derived\n$parentCacheId\n$parentEntryId\n"
        }
    val activationPolicy = if (restoreSettingsOnActivation) "restore" else "preserve"
    return (
        "$SCAN_APPEARANCE_VERSION_V4\n${normalized.colorMode.wireValue}\n" +
            "${normalized.colorIntensity}\n${normalized.grayscaleIntensity}\n" +
            "${normalized.blackWhiteIntensity}\n${normalized.shadows}\n" +
            "${pdfSizeTarget.wireValue}\n$lineageCacheId\n$activationPolicy\n$relation"
    ).toByteArray(StandardCharsets.US_ASCII)
}

internal fun decodeScanAppearanceMetadata(bytes: ByteArray): ScanAppearanceMetadata? {
    if (bytes.isEmpty() || bytes.size > MAX_SCAN_APPEARANCE_BYTES || bytes.any { it < 0 }) return null
    val text = String(bytes, StandardCharsets.US_ASCII)
    return when {
        text.startsWith("$SCAN_APPEARANCE_VERSION_V4\n") -> decodeV4Metadata(text)
        text.startsWith("$SCAN_APPEARANCE_VERSION_V3\n") -> decodeV3Metadata(text)
        text.startsWith("$SCAN_APPEARANCE_VERSION_V2\n") -> decodeV2Metadata(text)
        else -> null
    }
}

private fun decodeV4Metadata(text: String): ScanAppearanceMetadata? {
    val fields = text.split('\n')
    val derived = fields.getOrNull(9) == "derived"
    if (fields.size != (if (derived) 13 else 11) || fields.last().isNotEmpty()) return null
    if (fields[0] != SCAN_APPEARANCE_VERSION_V4) return null
    val settings = decodeFullAppearanceSettings(fields) ?: return null
    val target = PdfSizeTarget.entries.firstOrNull { it.wireValue == fields[6] } ?: return null
    val lineageCacheId = fields[7]
    val restoreSettings =
        when (fields[8]) {
            "restore" -> true
            "preserve" -> false
            else -> return null
        }
    val parentCacheId = if (derived) fields[10] else null
    val parentEntryId = if (derived) fields[11] else null
    if (!safeAppearanceIdentity(lineageCacheId, parentCacheId, parentEntryId)) return null
    if (!derived && fields[9] != "initial") return null
    return ScanAppearanceMetadata(
        appearance = settings.selected(),
        appearanceSettings = settings,
        pdfSizeTarget = target,
        lineageCacheId = lineageCacheId,
        parentCacheId = parentCacheId,
        parentEntryId = parentEntryId,
        restoreSettingsOnActivation = restoreSettings,
    )
}

private fun decodeV3Metadata(text: String): ScanAppearanceMetadata? {
    val fields = text.split('\n')
    val derived = fields.getOrNull(8) == "derived"
    if (fields.size != (if (derived) 12 else 10) || fields.last().isNotEmpty()) return null
    if (fields[0] != SCAN_APPEARANCE_VERSION_V3) return null
    val settings = decodeFullAppearanceSettings(fields) ?: return null
    val target = PdfSizeTarget.entries.firstOrNull { it.wireValue == fields[6] } ?: return null
    val lineageCacheId = fields[7]
    val parentCacheId = if (derived) fields[9] else null
    val parentEntryId = if (derived) fields[10] else null
    if (!safeAppearanceIdentity(lineageCacheId, parentCacheId, parentEntryId)) return null
    if (!derived && fields[8] != "initial") return null
    return ScanAppearanceMetadata(
        appearance = settings.selected(),
        appearanceSettings = settings,
        pdfSizeTarget = target,
        lineageCacheId = lineageCacheId,
        parentCacheId = parentCacheId,
        parentEntryId = parentEntryId,
    )
}

private fun decodeFullAppearanceSettings(fields: List<String>): ScanAppearanceSettings? =
    ScanAppearanceSettings(
        colorMode = ScanColorMode.entries.firstOrNull { it.wireValue == fields.getOrNull(1) }
            ?: return null,
        colorIntensity = strictPercent(fields.getOrNull(2) ?: return null) ?: return null,
        grayscaleIntensity = strictPercent(fields.getOrNull(3) ?: return null) ?: return null,
        blackWhiteIntensity = strictPercent(fields.getOrNull(4) ?: return null) ?: return null,
        shadows = strictPercent(fields.getOrNull(5) ?: return null) ?: return null,
    )

private fun decodeV2Metadata(text: String): ScanAppearanceMetadata? {
    val match = SCAN_APPEARANCE_V2_PATTERN.matchEntire(text) ?: return null
    val appearance =
        ScanAppearance(
            colorMode = ScanColorMode.entries.firstOrNull { it.wireValue == match.groupValues[1] }
                ?: return null,
            intensity = strictPercent(match.groupValues[2]) ?: return null,
            shadows = strictPercent(match.groupValues[3]) ?: return null,
        )
    val target = PdfSizeTarget.entries.firstOrNull { it.wireValue == match.groupValues[4] }
        ?: return null
    val lineageCacheId = match.groupValues[5]
    if (!isSafeAppearanceCacheId(lineageCacheId)) return null
    return ScanAppearanceMetadata(
        appearance = appearance,
        appearanceSettings = null,
        pdfSizeTarget = target,
        lineageCacheId = lineageCacheId,
        parentCacheId = null,
        parentEntryId = null,
    )
}

internal fun readScanAppearanceMetadata(
    directory: File,
    expectedCacheId: String = directory.name,
): ScanAppearanceMetadata? {
    val file = File(directory, SCAN_APPEARANCE_FILE_NAME)
    if (!file.isFile || file.length() !in 1..MAX_SCAN_APPEARANCE_BYTES.toLong()) return null
    val metadata =
        try {
            decodeScanAppearanceMetadata(Files.readAllBytes(file.toPath()))
        } catch (_: IOException) {
            null
        } ?: return null
    if (metadata.appearanceSettings != null) {
        if (metadata.parentCacheId == null && metadata.lineageCacheId != expectedCacheId) return null
        if (metadata.parentCacheId == expectedCacheId) return null
    }
    return metadata
}

internal fun writeScanAppearanceMetadata(
    directory: File,
    appearanceSettings: ScanAppearanceSettings,
    pdfSizeTarget: PdfSizeTarget = PdfSizeTarget.Original,
    lineageCacheId: String = directory.name,
    parentCacheId: String? = null,
    parentEntryId: String? = null,
    restoreSettingsOnActivation: Boolean = true,
) {
    val parent = directory.absoluteFile
    require(parent.isDirectory) { "Appearance metadata directory does not exist" }
    val target = File(parent, SCAN_APPEARANCE_FILE_NAME)
    val temporary = File(parent, SCAN_APPEARANCE_TEMP_FILE_NAME)
    var failure: Throwable? = null
    try {
        FileOutputStream(temporary).use { output ->
            output.write(
                encodeScanAppearanceMetadata(
                    appearanceSettings,
                    pdfSizeTarget,
                    lineageCacheId,
                    parentCacheId,
                    parentEntryId,
                    restoreSettingsOnActivation,
                ),
            )
            output.fd.sync()
        }
        Files.move(
            temporary.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (throwable: Throwable) {
        failure = throwable
        throw throwable
    } finally {
        if (temporary.exists() && !temporary.delete()) {
            val cleanup = IOException("Incomplete appearance metadata could not be deleted")
            failure?.addSuppressed(cleanup) ?: throw cleanup
        }
    }
}

private fun normalizeMetadataAppearance(settings: ScanAppearanceSettings): ScanAppearanceSettings =
    parseScanAppearanceSettings(
        colorModeWireValue = settings.colorMode.wireValue,
        colorIntensity = settings.colorIntensity,
        grayscaleIntensity = settings.grayscaleIntensity,
        blackWhiteIntensity = settings.blackWhiteIntensity,
        shadows = settings.shadows,
    )

private fun strictPercent(value: String): Int? = value.toIntOrNull()?.takeIf { it in 0..100 }

private fun requireSafeAppearanceIdentity(
    lineageCacheId: String,
    parentCacheId: String?,
    parentEntryId: String?,
) {
    require(safeAppearanceIdentity(lineageCacheId, parentCacheId, parentEntryId)) {
        "Appearance identity is invalid"
    }
}

private fun safeAppearanceIdentity(
    lineageCacheId: String,
    parentCacheId: String?,
    parentEntryId: String?,
): Boolean =
    isSafeAppearanceCacheId(lineageCacheId) &&
        ((parentCacheId == null && parentEntryId == null) ||
            (parentCacheId != null &&
                isSafeAppearanceCacheId(parentCacheId) &&
                parentEntryId != null &&
                isCanonicalUuid(parentEntryId)))

private fun isSafeAppearanceCacheId(value: String): Boolean =
    value.length <= MAX_APPEARANCE_CACHE_ID_LENGTH && isSafeCacheId(value)

private const val SCAN_APPEARANCE_VERSION_V2 = "scanit-appearance-v2"
private const val SCAN_APPEARANCE_VERSION_V3 = "scanit-appearance-v3"
private const val SCAN_APPEARANCE_VERSION_V4 = "scanit-appearance-v4"
private const val MAX_SCAN_APPEARANCE_BYTES = 640
private const val MAX_APPEARANCE_CACHE_ID_LENGTH = 128
private val SCAN_APPEARANCE_V2_PATTERN =
    Regex(
        "$SCAN_APPEARANCE_VERSION_V2\\n(color|grayscale|black_white)\\n" +
            "([0-9]{1,3})\\n([0-9]{1,3})\\n(original|5_mb|10_mb|20_mb)\\n" +
            "([^\\r\\n]{1,$MAX_APPEARANCE_CACHE_ID_LENGTH})\\n",
    )
