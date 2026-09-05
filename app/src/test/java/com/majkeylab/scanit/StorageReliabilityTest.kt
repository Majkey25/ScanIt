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
