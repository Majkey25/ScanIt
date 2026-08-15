package com.majkeylab.scanit

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.CancellationException
import org.json.JSONObject

internal const val LOCAL_PDF_REPLACEMENT_FILE_NAME = ".pdf-replacement.json"
internal const val LOCAL_PDF_REPLACEMENT_TEMP_FILE_NAME = ".pdf-replacement.json.tmp"
internal const val LOCAL_PDF_REPLACEMENT_OLD_FILE_NAME = ".pdf-replacement-old"
internal const val LOCAL_PDF_REPLACEMENT_NEW_FILE_NAME = ".pdf-replacement-new"
private const val LOCAL_PDF_REPLACEMENT_VERSION = 1
private const val MAX_LOCAL_PDF_REPLACEMENT_BYTES = 8 * 1024

internal enum class LocalPdfReplacementPhase {
    Prepared,
    LocalPublished,
    OutputsCommitted,
}

internal data class LocalPdfReplacementJournal(
    val operationId: String,
    val cacheId: String,
    val entryId: String,
    val phase: LocalPdfReplacementPhase,
    val pdfFileName: String,
    val oldTarget: PdfSizeTarget,
    val newTarget: PdfSizeTarget,
    val oldFingerprint: OutputFingerprint,
    val newFingerprint: OutputFingerprint,
)

private sealed interface LocalPdfReplacementReadResult {
    data object Absent : LocalPdfReplacementReadResult

    data class Valid(val journal: LocalPdfReplacementJournal) : LocalPdfReplacementReadResult

    data object Invalid : LocalPdfReplacementReadResult

    data object Failed : LocalPdfReplacementReadResult
}

internal fun prepareLocalPdfReplacement(
    directory: File,
    cacheId: String,
    entryId: String,
    pageCount: Int,
    cachedPdf: File,
    candidatePdf: File,
    oldTarget: PdfSizeTarget,
    newTarget: PdfSizeTarget,
    isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
    operationId: String = UUID.randomUUID().toString(),
): LocalPdfReplacementJournal {
    val safeDirectory = requireLocalPdfReplacementDirectory(directory, cacheId)
    requireLocalPdfFile(safeDirectory, cachedPdf, cacheId)
    requireReadableLocalPdf(candidatePdf)
    if (!isCanonicalUuid(entryId) || !isCanonicalUuid(operationId) || oldTarget == newTarget) {
        throw IOException("Local PDF replacement identity is invalid")
    }
    val metadata = readOutputMetadata(safeDirectory, cacheId, pageCount)
        ?: throw IOException("Output metadata is unavailable")
    if (metadata.entryId != entryId ||
        metadata.pdfSizeTarget?.let { it != oldTarget } == true
    ) {
        throw IOException("Output metadata changed before PDF replacement")
    }
    if (readLocalPdfReplacement(safeDirectory, cacheId, entryId) != LocalPdfReplacementReadResult.Absent) {
        throw IOException("Local PDF replacement is already pending")
    }
    val oldFile = File(safeDirectory, LOCAL_PDF_REPLACEMENT_OLD_FILE_NAME)
    val newFile = File(safeDirectory, LOCAL_PDF_REPLACEMENT_NEW_FILE_NAME)
    if (oldFile.exists() || newFile.exists()) {
        throw IOException("Local PDF replacement staging already exists")
    }
    try {
        copyLocalPdf(cachedPdf, oldFile, isCancelled)
        copyLocalPdf(candidatePdf, newFile, isCancelled)
        checkCancellation(isCancelled)
        val journal =
            LocalPdfReplacementJournal(
                operationId = operationId,
                cacheId = cacheId,
                entryId = entryId,
                phase = LocalPdfReplacementPhase.Prepared,
                pdfFileName = scanPdfFileName(cacheId),
                oldTarget = oldTarget,
                newTarget = newTarget,
                oldFingerprint = fingerprintLocalPdf(oldFile),
                newFingerprint = fingerprintLocalPdf(newFile),
            )
        writeLocalPdfReplacement(safeDirectory, journal, expected = null)
        return journal
    } catch (failure: Throwable) {
        if (!File(safeDirectory, LOCAL_PDF_REPLACEMENT_FILE_NAME).exists()) {
            deleteLocalPdfReplacementFile(oldFile, failure)
            deleteLocalPdfReplacementFile(newFile, failure)
        }
        throw failure
    }
}

internal fun publishLocalPdfReplacement(
    directory: File,
    expected: LocalPdfReplacementJournal,
    cachedPdf: File,
    isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
): LocalPdfReplacementJournal {
    val safeDirectory = requireLocalPdfReplacementDirectory(directory, expected.cacheId)
    requireLocalPdfFile(safeDirectory, cachedPdf, expected.cacheId)
    requireCurrentLocalPdfReplacement(safeDirectory, expected)
    if (expected.phase != LocalPdfReplacementPhase.Prepared) {
        throw IOException("Local PDF replacement phase is invalid")
    }
    requireLocalFingerprint(cachedPdf, expected.oldFingerprint)
    requireLocalFingerprint(
        File(safeDirectory, LOCAL_PDF_REPLACEMENT_OLD_FILE_NAME),
        expected.oldFingerprint,
    )
    val candidate = File(safeDirectory, LOCAL_PDF_REPLACEMENT_NEW_FILE_NAME)
    requireLocalFingerprint(candidate, expected.newFingerprint)
    checkCancellation(isCancelled)
    Files.move(
        candidate.toPath(),
        cachedPdf.toPath(),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
    )
    requireLocalFingerprint(cachedPdf, expected.newFingerprint)
    return expected.copy(phase = LocalPdfReplacementPhase.LocalPublished).also {
        writeLocalPdfReplacement(safeDirectory, it, expected)
    }
}

internal fun markLocalPdfReplacementOutputsCommitted(
    directory: File,
    expected: LocalPdfReplacementJournal,
    pageCount: Int,
): LocalPdfReplacementJournal {
    val safeDirectory = requireLocalPdfReplacementDirectory(directory, expected.cacheId)
    requireCurrentLocalPdfReplacement(safeDirectory, expected)
    if (expected.phase != LocalPdfReplacementPhase.LocalPublished) {
        throw IOException("Local PDF replacement phase is invalid")
    }
    val metadata = readOutputMetadata(safeDirectory, expected.cacheId, pageCount)
        ?: throw IOException("Output metadata is unavailable")
    if (!localPdfOutputsCommitted(metadata, expected)) {
        throw IOException("PDF replacement outputs are not committed")
    }
    return expected.copy(phase = LocalPdfReplacementPhase.OutputsCommitted).also {
        writeLocalPdfReplacement(safeDirectory, it, expected)
    }
}

internal fun reconcileLocalPdfReplacement(
    directory: File,
    cacheId: String,
    pageCount: Int,
    cachedPdf: File,
    movePdf: (Path, Path) -> Unit = ::moveLocalPdfAtomically,
) {
    val safeDirectory = requireLocalPdfReplacementDirectory(directory, cacheId)
    requireLocalPdfFile(safeDirectory, cachedPdf, cacheId)
    val target = File(safeDirectory, LOCAL_PDF_REPLACEMENT_FILE_NAME)
    val temporary = File(safeDirectory, LOCAL_PDF_REPLACEMENT_TEMP_FILE_NAME)
    val oldFile = File(safeDirectory, LOCAL_PDF_REPLACEMENT_OLD_FILE_NAME)
    val newFile = File(safeDirectory, LOCAL_PDF_REPLACEMENT_NEW_FILE_NAME)
    when (val read = readLocalPdfReplacement(safeDirectory, cacheId, expectedEntryId = null)) {
        LocalPdfReplacementReadResult.Absent -> {
            deleteExactLocalStaging(temporary)
            deleteExactLocalStaging(oldFile)
            deleteExactLocalStaging(newFile)
            return
        }
        LocalPdfReplacementReadResult.Invalid,
        LocalPdfReplacementReadResult.Failed,
        -> throw IOException("Local PDF replacement journal is unavailable")
        is LocalPdfReplacementReadResult.Valid -> {
            val journal = read.journal
            deleteExactLocalStaging(temporary)
            val metadata = readOutputMetadata(safeDirectory, cacheId, pageCount)
                ?: throw IOException("Output metadata is unavailable")
            if (metadata.entryId != journal.entryId) {
                throw IOException("Local PDF replacement belongs to another cache generation")
            }
            val cachedFingerprint = fingerprintLocalPdf(cachedPdf)
            if (localPdfOutputsCommitted(metadata, journal)) {
                if (cachedFingerprint != journal.newFingerprint) {
                    throw IOException("Committed local PDF differs from its replacement")
                }
                deleteExactLocalStaging(oldFile)
                deleteExactLocalStaging(newFile)
                Files.delete(target.toPath())
                return
            }
            if (cachedFingerprint == journal.oldFingerprint) {
                deleteExactLocalStaging(oldFile)
                deleteExactLocalStaging(newFile)
                Files.delete(target.toPath())
                return
            }
            if (cachedFingerprint != journal.newFingerprint) {
                throw IOException("Local PDF replacement state is ambiguous")
            }
            requireLocalFingerprint(oldFile, journal.oldFingerprint)
            copyLocalPdf(oldFile, newFile) { false }
            try {
                movePdf(newFile.toPath(), cachedPdf.toPath())
                requireLocalFingerprint(cachedPdf, journal.oldFingerprint)
            } catch (failure: Throwable) {
                deleteLocalPdfReplacementFile(newFile, failure)
                throw IOException("Local PDF replacement rollback failed", failure)
            }
            deleteExactLocalStaging(oldFile)
            deleteExactLocalStaging(newFile)
            Files.delete(target.toPath())
        }
    }
}

private fun localPdfOutputsCommitted(
    metadata: OutputMetadata,
    journal: LocalPdfReplacementJournal,
): Boolean =
    metadata.entryId == journal.entryId &&
        metadata.pdfSizeTarget == journal.newTarget &&
        (metadata.pdf == null || metadata.pdf.outputFingerprint() == journal.newFingerprint)

private fun writeLocalPdfReplacement(
    directory: File,
    journal: LocalPdfReplacementJournal,
    expected: LocalPdfReplacementJournal?,
) {
    if (!isValidLocalPdfReplacement(journal)) {
        throw IOException("Local PDF replacement journal is invalid")
    }
    if (expected == null) {
        if (readLocalPdfReplacement(directory, journal.cacheId, journal.entryId) !=
            LocalPdfReplacementReadResult.Absent
        ) {
            throw IOException("Local PDF replacement journal already exists")
        }
    } else {
        requireCurrentLocalPdfReplacement(directory, expected)
    }
    val target = File(directory, LOCAL_PDF_REPLACEMENT_FILE_NAME)
    val temporary = File(directory, LOCAL_PDF_REPLACEMENT_TEMP_FILE_NAME)
    val bytes = encodeLocalPdfReplacement(journal)
    try {
        Files.deleteIfExists(temporary.toPath())
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        if (decodeLocalPdfReplacement(temporary.readBytes(), journal.cacheId, journal.entryId) != journal) {
            throw IOException("Staged local PDF replacement journal could not be verified")
        }
        val options =
            if (expected == null) {
                arrayOf(StandardCopyOption.ATOMIC_MOVE)
            } else {
                arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
        Files.move(temporary.toPath(), target.toPath(), *options)
    } catch (failure: Exception) {
        deleteLocalPdfReplacementFile(temporary, failure)
        throw IOException("Local PDF replacement journal could not be written", failure)
    }
}

private fun readLocalPdfReplacement(
    directory: File,
    expectedCacheId: String,
    expectedEntryId: String?,
): LocalPdfReplacementReadResult {
    val target = File(directory.absoluteFile, LOCAL_PDF_REPLACEMENT_FILE_NAME).absoluteFile
    return try {
        if (!target.exists()) return LocalPdfReplacementReadResult.Absent
        if (
            target.parentFile != directory.absoluteFile ||
                !Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                target.length() !in 1..MAX_LOCAL_PDF_REPLACEMENT_BYTES.toLong()
        ) {
            return LocalPdfReplacementReadResult.Invalid
        }
        decodeLocalPdfReplacement(target.readBytes(), expectedCacheId, expectedEntryId)
            ?.let(LocalPdfReplacementReadResult::Valid)
            ?: LocalPdfReplacementReadResult.Invalid
    } catch (_: IOException) {
        LocalPdfReplacementReadResult.Failed
    } catch (_: SecurityException) {
        LocalPdfReplacementReadResult.Failed
    }
}

private fun requireCurrentLocalPdfReplacement(
    directory: File,
    expected: LocalPdfReplacementJournal,
) {
    if (readLocalPdfReplacement(directory, expected.cacheId, expected.entryId) !=
        LocalPdfReplacementReadResult.Valid(expected)
    ) {
        throw IOException("Local PDF replacement journal changed")
    }
}

private fun encodeLocalPdfReplacement(journal: LocalPdfReplacementJournal): ByteArray =
    JSONObject()
        .put("version", LOCAL_PDF_REPLACEMENT_VERSION)
        .put("operationId", journal.operationId)
        .put("cacheId", journal.cacheId)
        .put("entryId", journal.entryId)
        .put("phase", journal.phase.name)
        .put("pdfFileName", journal.pdfFileName)
        .put("oldTarget", journal.oldTarget.wireValue)
        .put("newTarget", journal.newTarget.wireValue)
        .put("oldByteLength", journal.oldFingerprint.byteLength)
        .put("oldSha256", journal.oldFingerprint.sha256)
        .put("newByteLength", journal.newFingerprint.byteLength)
        .put("newSha256", journal.newFingerprint.sha256)
        .toString()
        .toByteArray(StandardCharsets.UTF_8)
        .also {
            if (it.size > MAX_LOCAL_PDF_REPLACEMENT_BYTES) {
                throw IOException("Local PDF replacement journal is too large")
            }
        }

private fun decodeLocalPdfReplacement(
    bytes: ByteArray,
    expectedCacheId: String,
    expectedEntryId: String?,
): LocalPdfReplacementJournal? {
    if (bytes.isEmpty() || bytes.size > MAX_LOCAL_PDF_REPLACEMENT_BYTES) return null
    return try {
        val value = JSONObject(String(bytes, StandardCharsets.UTF_8))
        if (value.keys().asSequence().toSet() != LOCAL_PDF_REPLACEMENT_KEYS) return null
        if (value.opt("version") != LOCAL_PDF_REPLACEMENT_VERSION) return null
        val cacheId = value.opt("cacheId") as? String ?: return null
        val entryId = value.opt("entryId") as? String ?: return null
        if (cacheId != expectedCacheId || expectedEntryId?.let { it != entryId } == true) return null
        val journal =
            LocalPdfReplacementJournal(
                operationId = value.opt("operationId") as? String ?: return null,
                cacheId = cacheId,
                entryId = entryId,
                phase = LocalPdfReplacementPhase.entries.firstOrNull {
                    it.name == value.opt("phase") as? String
                } ?: return null,
                pdfFileName = value.opt("pdfFileName") as? String ?: return null,
                oldTarget = decodePdfSizeTarget(value.opt("oldTarget") as? String) ?: return null,
                newTarget = decodePdfSizeTarget(value.opt("newTarget") as? String) ?: return null,
                oldFingerprint =
                    OutputFingerprint(
                        byteLength = value.strictJournalLong("oldByteLength") ?: return null,
                        sha256 = value.opt("oldSha256") as? String ?: return null,
                    ),
                newFingerprint =
                    OutputFingerprint(
                        byteLength = value.strictJournalLong("newByteLength") ?: return null,
                        sha256 = value.opt("newSha256") as? String ?: return null,
                    ),
            )
        journal.takeIf(::isValidLocalPdfReplacement)
    } catch (_: Exception) {
        null
    }
}

private fun isValidLocalPdfReplacement(journal: LocalPdfReplacementJournal): Boolean =
    isSafeCacheId(journal.cacheId) &&
        isCanonicalUuid(journal.entryId) &&
        isCanonicalUuid(journal.operationId) &&
        journal.pdfFileName == scanPdfFileName(journal.cacheId) &&
        journal.oldTarget != journal.newTarget &&
        isValidOutputFingerprint(
            journal.oldFingerprint.byteLength,
            journal.oldFingerprint.sha256,
        ) &&
        isValidOutputFingerprint(
            journal.newFingerprint.byteLength,
            journal.newFingerprint.sha256,
        )

private fun JSONObject.strictJournalLong(key: String): Long? =
    when (val value = opt(key)) {
        is Int -> value.toLong()
        is Long -> value
        null -> null
        else -> null
    }

private fun requireLocalPdfReplacementDirectory(directory: File, cacheId: String): File {
    val absolute = directory.absoluteFile
    if (
        !isSafeCacheId(cacheId) ||
            absolute.name != cacheId ||
            absolute.canonicalFile != absolute ||
            !Files.isDirectory(absolute.toPath(), LinkOption.NOFOLLOW_LINKS)
    ) {
        throw IOException("Local PDF replacement directory is unsafe")
    }
    return absolute
}

private fun requireLocalPdfFile(directory: File, pdf: File, cacheId: String) {
    if (pdf.absoluteFile != File(directory, scanPdfFileName(cacheId)).absoluteFile) {
        throw IOException("Local PDF replacement path is unsafe")
    }
    requireReadableLocalPdf(pdf)
}

private fun requireReadableLocalPdf(file: File) {
    if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) || file.length() <= 0L) {
        throw IOException("Local PDF is unavailable")
    }
}

private fun copyLocalPdf(source: File, destination: File, isCancelled: () -> Boolean) {
    requireReadableLocalPdf(source)
    if (destination.exists()) throw IOException("Local PDF staging already exists")
    checkCancellation(isCancelled)
    try {
        source.inputStream().use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    checkCancellation(isCancelled)
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
                output.fd.sync()
            }
        }
        if (fingerprintLocalPdf(source) != fingerprintLocalPdf(destination)) {
            throw IOException("Local PDF staging differs from its source")
        }
    } catch (failure: Throwable) {
        deleteLocalPdfReplacementFile(destination, failure)
        throw failure
    }
}

private fun fingerprintLocalPdf(file: File): OutputFingerprint {
    requireReadableLocalPdf(file)
    return file.inputStream().use { readOutputFingerprint(it, file.length()) }
}

private fun requireLocalFingerprint(file: File, expected: OutputFingerprint) {
    if (fingerprintLocalPdf(file) != expected) {
        throw IOException("Local PDF fingerprint changed")
    }
}

private fun checkCancellation(isCancelled: () -> Boolean) {
    if (isCancelled()) throw CancellationException("PDF replacement cancelled")
}

private fun moveLocalPdfAtomically(source: Path, target: Path) {
    Files.move(
        source,
        target,
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
    )
}

private fun deleteExactLocalStaging(file: File) {
    if (!file.exists()) return
    if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
        throw IOException("Local PDF replacement staging is unsafe")
    }
    Files.delete(file.toPath())
}

private fun deleteLocalPdfReplacementFile(file: File, failure: Throwable) {
    try {
        deleteExactLocalStaging(file)
    } catch (cleanupFailure: Throwable) {
        if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
    }
}

private val LOCAL_PDF_REPLACEMENT_KEYS =
    setOf(
        "version",
        "operationId",
        "cacheId",
        "entryId",
        "phase",
        "pdfFileName",
        "oldTarget",
        "newTarget",
        "oldByteLength",
        "oldSha256",
        "newByteLength",
        "newSha256",
    )
