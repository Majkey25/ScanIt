package com.majkeylab.scanit

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageReliabilityTest {
    @Test
    fun returnedSafAcknowledgementRequiresValidatedMarkerAndExactOperation() {
        val directory = Files.createTempDirectory("scanit-returned-saf-ack").toFile()
        val acknowledgement = UnknownOutputCreateAcknowledgement(CACHE_ID, ENTRY_ID, OPERATION_ID)
        try {
            for (fingerprint in listOf(null, OutputFingerprint(17L, "ab".repeat(32)))) {
                val marker = ProvisionalOutputCreate(
                    operationId = OPERATION_ID,
                    cacheId = CACHE_ID,
                    entryId = ENTRY_ID,
                    kind = ProvisionalOutputKind.Image,
                    page = 1,
                    provider = ProvisionalOutputProvider.Saf,
                    displayName = "scan.jpg",
                    mimeType = "image/jpeg",
                    treeUri = "content://documents/tree/root",
                    returnedUri = "content://documents/tree/root/document/scan-1",
                    fingerprint = fingerprint,
                )
                writeProvisionalOutputCreate(directory, marker, 1)
                val file = File(directory, PROVISIONAL_OUTPUT_CREATE_FILE_NAME)
                val encoded = file.readText()
                val metadata = OutputMetadata(entryId = ENTRY_ID, cacheId = CACHE_ID, createdAtEpochMs = 1L)
                for (status in listOf(OutputDeleteStatus.Failed, OutputDeleteStatus.IdentityMismatch)) {
                    val result = reconcileProvisionalOutputCreate(
                        marker, metadata,
                        delete = { status },
                        clear = { throw AssertionError("Unresolved marker must not be cleared automatically") },
                    )
                    assertTrue(result.blocking)
                    assertEquals(acknowledgement, result.acknowledgement)
                    assertEquals(encoded, file.readText())
                }
                val rejected = listOf(
                    org.json.JSONObject(encoded).put("operationId", java.util.UUID.randomUUID().toString()),
                    org.json.JSONObject(encoded).put("entryId", java.util.UUID.randomUUID().toString()),
                    org.json.JSONObject(encoded).put("cacheId", "Scan_other"),
                    org.json.JSONObject(encoded).put("page", 2),
                    org.json.JSONObject(encoded).put("sha256", "invalid"),
                    org.json.JSONObject(encoded).put("returnedUri", "https://documents/scan-1"),
                    org.json.JSONObject(encoded).put("provider", "MediaStore").put("treeUri", org.json.JSONObject.NULL),
                )
                for (value in rejected) {
                    val bytes = value.toString()
                    file.writeText(bytes)
                    assertEquals(
                        UnknownOutputAcknowledgementResult.Stale,
                        acknowledgeUnknownProvisionalOutput(directory, acknowledgement, 1),
                    )
                    assertEquals(bytes, file.readText())
                }
                file.writeText(encoded)
                assertEquals(
                    UnknownOutputAcknowledgementResult.Failed,
                    acknowledgeUnknownProvisionalOutput(directory, acknowledgement, 1) {
                        throw IOException("Marker could not be removed")
                    },
                )
                assertEquals(encoded, file.readText())
                assertEquals(
                    UnknownOutputAcknowledgementResult.Applied,
                    acknowledgeUnknownProvisionalOutput(directory, acknowledgement, 1),
                )
                assertFalse(file.exists())
                assertEquals(
                    UnknownOutputAcknowledgementResult.Absent,
                    acknowledgeUnknownProvisionalOutput(directory, acknowledgement, 1),
                )
            }
        } finally {
            assertTrue(directory.deleteRecursively())
        }
    }

    @Test
    fun safMarkerRoundTripsFingerprintBeforeCreateAndRejectsMalformedPairs() {
        val directory = Files.createTempDirectory("scanit-saf-fingerprint").toFile()
        val marker = ProvisionalOutputCreate(
            operationId = OPERATION_ID,
            cacheId = CACHE_ID,
            entryId = ENTRY_ID,
            kind = ProvisionalOutputKind.Pdf,
            page = null,
            provider = ProvisionalOutputProvider.Saf,
            displayName = "scan.pdf",
            mimeType = "application/pdf",
            treeUri = "content://documents/tree/root",
            returnedUri = null,
            fingerprint = OutputFingerprint(17L, "ab".repeat(32)),
        )
        try {
            val returnedUri = "content://documents/tree/root/document/scan-1"
            createProviderOutputWithMarker(
                beforeCreate = { writeProvisionalOutputCreate(directory, marker, 1) },
                create = {
                    assertEquals(
                        ProvisionalOutputCreateReadResult.Valid(marker),
                        readProvisionalOutputCreate(directory, CACHE_ID, ENTRY_ID, 1),
                    )
                    returnedUri
                },
                onCreated = { updateProvisionalOutputCreateUri(directory, marker, it, 1) },
            )
            val exact = marker.copy(returnedUri = returnedUri)
            assertEquals(
                ProvisionalOutputCreateReadResult.Valid(exact),
                readProvisionalOutputCreate(directory, CACHE_ID, ENTRY_ID, 1),
            )
            clearProvisionalOutputCreate(directory, exact, 1)

            writeProvisionalOutputCreate(directory, marker, 1)
            val acknowledgement = UnknownOutputCreateAcknowledgement(CACHE_ID, ENTRY_ID, OPERATION_ID)
            assertEquals(acknowledgement, readUnknownOutputCreateAcknowledgement(directory, CACHE_ID, ENTRY_ID))
            assertEquals(
                UnknownOutputAcknowledgementResult.Applied,
                acknowledgeUnknownProvisionalOutput(directory, acknowledgement, 1),
            )

            val file = File(directory, PROVISIONAL_OUTPUT_CREATE_FILE_NAME)
            writeProvisionalOutputCreate(directory, marker, 1)
            val encoded = file.readText()
            val invalid = listOf(
                org.json.JSONObject(encoded).apply { remove("sha256") },
                org.json.JSONObject(encoded).put("byteLength", "17"),
                org.json.JSONObject(encoded).put("byteLength", 1.5),
                org.json.JSONObject(encoded).put("byteLength", 0),
                org.json.JSONObject(encoded).put("sha256", "invalid"),
            )
            invalid.forEach {
                file.writeText(it.toString())
                assertEquals(
                    ProvisionalOutputCreateReadResult.Invalid,
                    readProvisionalOutputCreate(directory, CACHE_ID, ENTRY_ID, 1),
                )
            }
            file.writeText(org.json.JSONObject(encoded).apply {
                remove("byteLength")
                remove("sha256")
            }.toString())
            assertEquals(
                ProvisionalOutputCreateReadResult.Valid(marker.copy(fingerprint = null)),
                readProvisionalOutputCreate(directory, CACHE_ID, ENTRY_ID, 1),
            )
        } finally {
            assertTrue(directory.deleteRecursively())
        }
    }

    @Test
    fun flattenedPdfSamplingDoesNotMultiplyTheRendererBaseSampleAgain() {
        val directory = Files.createTempDirectory("scanit-flattened-sample").toFile()
        try {
            val page = File(directory, "page.jpg").apply {
                writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte()) +
                    ByteArray(220_000) { 0x41 } + byteArrayOf(0xff.toByte(), 0xd9.toByte()))
            }
            val samples = mutableListOf<Int>()
            val result = buildProtectedScanPdf(
                File(directory, "scan.pdf"),
                listOf(page),
                listOf(JpegDimensions(3060, 4080)),
                PdfSizeTarget.Kb200,
                isCancelled = { false },
                renderSampledPage = { _, destination, sample ->
                    samples += sample
                    destination.writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(),
                        0x41, 0xff.toByte(), 0xd9.toByte()))
                    RenderedJpeg(3060 / sample, 4080 / sample, sample, destination.length())
                },
            )
            assertEquals(listOf(2), samples)
            assertEquals(2, result.sampleMultiplier)
            assertTrue(result.targetMet)
        } finally {
            assertTrue(directory.deleteRecursively())
        }
    }

    @Test
    fun providerRenamedOutputBecomesTheAuthoritativeCrashMarkerIdentity() {
        val directory = Files.createTempDirectory("scanit-provider-name").toFile()
        val treeUri = "content://documents/tree/root"
        val outputUri = "content://documents/tree/root/document/scan-1"
        val requested =
            ProvisionalOutputCreate(
                operationId = OPERATION_ID,
                cacheId = CACHE_ID,
                entryId = ENTRY_ID,
                kind = ProvisionalOutputKind.Pdf,
                page = null,
                provider = ProvisionalOutputProvider.Saf,
                displayName = "scan.pdf",
                mimeType = "application/pdf",
                treeUri = treeUri,
                returnedUri = null,
            )
        try {
            writeProvisionalOutputCreate(directory, requested, pageCount = 1)
            val withUri =
                updateProvisionalOutputCreateUri(
                    directory,
                    requested,
                    outputUri,
                    pageCount = 1,
                )
            val authoritative =
                updateProvisionalOutputCreateIdentity(
                    directory,
                    withUri,
                    outputUri,
                    "scan (1).pdf",
                    pageCount = 1,
                )

            assertEquals(
                ProvisionalOutputCreateReadResult.Valid(authoritative),
                readProvisionalOutputCreate(directory, CACHE_ID, ENTRY_ID, pageCount = 1),
            )
            val reconciliation =
                reconcileProvisionalOutputCreate(
                    marker = authoritative,
                    metadata =
                        OutputMetadata(
                            entryId = ENTRY_ID,
                            cacheId = CACHE_ID,
                            createdAtEpochMs = 1L,
                            stagedPdf =
                                PdfOutputRef(
                                    uri = outputUri,
                                    treeUri = treeUri,
                                    displayName = "scan (1).pdf",
                                    mimeType = "application/pdf",
                                    ownerPackageName = null,
                                    byteLength = 1L,
                                    sha256 = "00".repeat(32),
                                    pending = false,
                                ),
                            version = OUTPUT_METADATA_VERSION,
                        ),
                    delete = { throw IOException("staged output must not be deleted") },
                    clear = {
                        clearProvisionalOutputCreate(directory, authoritative, pageCount = 1)
                    },
                )

            assertFalse(reconciliation.blocking)
            assertEquals(
                ProvisionalOutputCreateReadResult.Absent,
                readProvisionalOutputCreate(directory, CACHE_ID, ENTRY_ID, pageCount = 1),
            )
        } finally {
            assertTrue(directory.deleteRecursively())
        }
    }

    @Test
    fun automaticOrientationNeverSelectsTheDownsamplingRendererForLargeSources() {
        assertTrue(canNormalizeScannerOrientationWithoutDownsampling(6000, 2000))
        assertFalse(canNormalizeScannerOrientationWithoutDownsampling(6001, 1999))
        assertFalse(canNormalizeScannerOrientationWithoutDownsampling(4001, 3000))
        assertFalse(canNormalizeScannerOrientationWithoutDownsampling(16384, 12288))
    }

    @Test
    fun firstSavesUseTheExistingReplacementJournalAndRequireProviderCallbacks() {
        val source = scanStorageSource()
        val saveImages = source.substringAfter("    fun saveImages(").substringBefore("    fun savePdf(")
        val savePdf = source.substringAfter("    fun savePdf(").substringBefore("    fun loadThumbnail(")

        assertTrue(saveImages.contains("replaceStagedImageOutputs"))
        assertTrue(savePdf.contains("replacePdfOutputLocked"))
        assertFalse(source.contains("beforeCreate: () -> Unit = {}"))
        assertFalse(source.contains("onCreated: (Uri) -> Unit = {}"))
    }

    @Test
    fun copiedCachePagesAreByteExactAndDurablySynchronized() {
        val directory = Files.createTempDirectory("scanit-derived-copy").toFile()
        try {
            val bytes = ByteArray(32_000) { (it % 251).toByte() }
            val source = File(directory, "source.jpg").apply { writeBytes(bytes) }
            val target = File(directory, "target.jpg")

            copyDerivedSourcePage(source, target)

            assertArrayEquals(bytes, target.readBytes())
            val implementation =
                scanStorageSource()
                    .substringAfter("internal fun copyDerivedSourcePage(")
                    .substringBefore("private fun normalizeScannerPageOrientation(")
            assertTrue(implementation.contains("fd.sync()"))
        } finally {
            assertTrue(directory.deleteRecursively())
        }
    }

    private fun scanStorageSource(): String =
        File("..").canonicalFile
            .resolve("app/src/main/java/com/majkeylab/scanit/ScanStorage.kt")
            .readText()

    private companion object {
        const val CACHE_ID = "Scan_2026-09-05_12-00-00"
        const val ENTRY_ID = "123e4567-e89b-12d3-a456-426614174001"
        const val OPERATION_ID = "123e4567-e89b-12d3-a456-426614174002"
    }
}
