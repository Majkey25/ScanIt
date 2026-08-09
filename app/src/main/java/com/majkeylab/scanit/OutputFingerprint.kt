package com.majkeylab.scanit

import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

internal data class OutputFingerprint(
    val byteLength: Long,
    val sha256: String,
)

internal enum class OutputFingerprintCheck {
    Exact,
    Mismatch,
    Failed,
}

internal fun isValidOutputFingerprint(byteLength: Long?, sha256: String?): Boolean {
    if (byteLength == null && sha256 == null) return true
    return byteLength != null &&
        byteLength > 0L &&
        byteLength < Long.MAX_VALUE &&
        sha256 != null &&
        sha256.length == SHA_256_HEX_LENGTH &&
        sha256.all { it in '0'..'9' || it in 'a'..'f' }
}

@Throws(IOException::class)
internal fun readOutputFingerprint(input: InputStream, expectedLength: Long): OutputFingerprint {
    if (expectedLength <= 0L || expectedLength >= Long.MAX_VALUE) {
        throw IOException("Output length is invalid")
    }
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var remaining = expectedLength
    while (remaining > 0L) {
        val requested = minOf(buffer.size.toLong(), remaining).toInt()
        val read = input.read(buffer, 0, requested)
        if (read < 0) throw OutputLengthMismatch("Output is shorter than expected")
        if (read == 0) {
            val value = input.read()
            if (value < 0) throw OutputLengthMismatch("Output is shorter than expected")
            digest.update(value.toByte())
            remaining--
        } else {
            digest.update(buffer, 0, read)
            remaining -= read
        }
    }
    if (input.read() >= 0) throw OutputLengthMismatch("Output is longer than expected")
    return OutputFingerprint(expectedLength, digest.digest().toLowerHex())
}

internal fun outputFingerprintMatches(input: InputStream, expected: OutputFingerprint): Boolean {
    return checkOutputFingerprint(input, expected) == OutputFingerprintCheck.Exact
}

internal fun checkOutputFingerprint(
    input: InputStream,
    expected: OutputFingerprint,
): OutputFingerprintCheck {
    if (!isValidOutputFingerprint(expected.byteLength, expected.sha256)) {
        return OutputFingerprintCheck.Mismatch
    }
    return try {
        if (readOutputFingerprint(input, expected.byteLength) == expected) {
            OutputFingerprintCheck.Exact
        } else {
            OutputFingerprintCheck.Mismatch
        }
    } catch (_: OutputLengthMismatch) {
        OutputFingerprintCheck.Mismatch
    } catch (_: IOException) {
        OutputFingerprintCheck.Failed
    }
}

internal fun savedOutputMatchesSource(
    source: OutputFingerprint,
    saved: OutputFingerprint,
): Boolean = source == saved

internal fun outputFingerprintOrNull(byteLength: Long?, sha256: String?): OutputFingerprint? =
    if (byteLength != null && sha256 != null && isValidOutputFingerprint(byteLength, sha256)) {
        OutputFingerprint(byteLength, sha256)
    } else {
        null
    }

internal fun PdfOutputRef.outputFingerprint(): OutputFingerprint? =
    outputFingerprintOrNull(byteLength, sha256)

internal fun ImageOutputRef.outputFingerprint(): OutputFingerprint? =
    outputFingerprintOrNull(byteLength, sha256)

internal fun OutputMetadata.hasVerifiedOutputFingerprints(target: RecentDeleteTarget): Boolean =
    when (target) {
        RecentDeleteTarget.Pdf -> pdf?.outputFingerprint() != null
        RecentDeleteTarget.Images ->
            images.isNotEmpty() && images.all { it.outputFingerprint() != null }
        RecentDeleteTarget.Both ->
            pdf?.outputFingerprint() != null &&
                images.isNotEmpty() &&
                images.all { it.outputFingerprint() != null }
        RecentDeleteTarget.RemoveFromRecent -> false
    }

private fun ByteArray.toLowerHex(): String {
    val bytes = this
    return buildString(size * 2) {
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0f])
        }
    }
}

private const val SHA_256_HEX_LENGTH = 64
private const val HEX_DIGITS = "0123456789abcdef"
private class OutputLengthMismatch(message: String) : IOException(message)
