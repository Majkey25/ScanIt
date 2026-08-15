package com.majkeylab.scanit

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputMetadataTest {
    private val entryId = "123e4567-e89b-12d3-a456-426614174000"
    private val cacheId = "Scan_2026-08-09_12-12-00"

    @Test
    fun downloadsPdfRoundTripsWithoutATree() {
        val metadata =
            OutputMetadata(
                entryId = entryId,
                cacheId = cacheId,
                createdAtEpochMs = 1_786_268_430_000L,
                pdf = PdfOutputRef("content://media/external/downloads/42", treeUri = null),
            )

        assertEquals(
            metadata,
            decodeOutputMetadata(
                encodeOutputMetadata(metadata, pageCount = 1),
                expectedCacheId = cacheId,
                pageCount = 1,
            ),
        )
    }

    @Test
    fun safPdfRoundTripsWithItsExactTree() {
        val metadata =
            OutputMetadata(
                entryId = entryId,
                cacheId = cacheId,
                createdAtEpochMs = 1L,
                pdf =
                    PdfOutputRef(
                        uri = "content://com.android.externalstorage.documents/document/primary%3AScans%2Fscan.pdf",
                        treeUri = "content://com.android.externalstorage.documents/tree/primary%3AScans",
                        displayName = "scan (1).pdf",
                        mimeType = "application/pdf",
                    ),
            )

        val decoded =
            decodeOutputMetadata(
                encodeOutputMetadata(metadata, pageCount = 1),
                expectedCacheId = cacheId,
                pageCount = 1,
            )

        assertEquals(metadata, decoded)
        assertEquals(metadata.pdf?.treeUri, decoded?.pdf?.treeUri)
    }

    @Test
    fun safPdfWithoutARecordedProviderNameIsRejected() {
        val bytes =
            """{"version":1,"entryId":"$entryId","cacheId":"$cacheId","createdAtEpochMs":1,"pdf":{"uri":"content://docs/tree/root/document/root%3Ascan.pdf","treeUri":"content://docs/tree/root"},"images":[]}"""
                .toByteArray(StandardCharsets.UTF_8)

        assertNull(decodeOutputMetadata(bytes, expectedCacheId = cacheId, pageCount = 1))
    }

    @Test
    fun pendingRecentRemovalRoundTrips() {
        val metadata =
            OutputMetadata(
                entryId = entryId,
                cacheId = cacheId,
                createdAtEpochMs = 1L,
                removeRecentPending = true,
            )

        assertEquals(
            metadata,
            decodeOutputMetadata(
                encodeOutputMetadata(metadata, pageCount = 1),
                expectedCacheId = cacheId,
                pageCount = 1,
            ),
        )
    }

    @Test
    fun providerObservedMediaIdentityRoundTrips() {
        val owner = "com.majkeylab.scanit.internal"
        val metadata =
            OutputMetadata(
                entryId = entryId,
                cacheId = cacheId,
                createdAtEpochMs = 1L,
                pdf =
                    PdfOutputRef(
                        uri = "content://media/external/downloads/42",
                        treeUri = null,
                        displayName = "scan (1).pdf",
                        mimeType = "application/pdf",
                        ownerPackageName = owner,
                        byteLength = 10L,
                        sha256 = "00".repeat(32),
                    ),
                images =
                    listOf(
                        ImageOutputRef(
                            page = 1,
                            uri = "content://media/external/images/media/43",
                            displayName = "scan (1).jpg",
                            mimeType = "image/jpeg",
                            ownerPackageName = owner,
                            byteLength = 11L,
                            sha256 = "11".repeat(32),
                        ),
                    ),
            )

        assertEquals(
            metadata,
            decodeOutputMetadata(
                encodeOutputMetadata(metadata, pageCount = 1),
                expectedCacheId = cacheId,
                pageCount = 1,
            ),
        )
    }

    @Test
    fun pendingMediaOwnershipRoundTripsAndLegacyRowsDefaultToPublished() {
        val pending =
            OutputMetadata(
                entryId = entryId,
                cacheId = cacheId,
                createdAtEpochMs = 1L,
                pdf =
                    PdfOutputRef(
                        uri = "content://media/external/downloads/42",
                        treeUri = null,
                        displayName = "scan.pdf",
                        mimeType = "application/pdf",
                        ownerPackageName = "com.majkeylab.scanit.internal",
                        byteLength = 10L,
                        sha256 = "00".repeat(32),
                        pending = true,
                    ),
                images =
                    listOf(
                        ImageOutputRef(
                            page = 1,
                            uri = "content://media/external/images/media/43",
                            displayName = "scan.jpg",
                            mimeType = "image/jpeg",
                            ownerPackageName = "com.majkeylab.scanit.internal",
                            byteLength = 11L,
                            sha256 = "11".repeat(32),
                            pending = true,
                        ),
                    ),
            )

        assertEquals(
            pending,
            decodeOutputMetadata(encodeOutputMetadata(pending, 1), cacheId, 1),
        )

        val legacy =
            validJson(
                images = """[{"page":1,"uri":"content://media/external/images/media/43"}]""",
            ).toByteArray(StandardCharsets.UTF_8)
        assertEquals(false, decodeOutputMetadata(legacy, cacheId, 1)?.images?.single()?.pending)
    }

    @Test
    fun pendingMediaWithoutExactIdentityIsRejected() {
        val incomplete =
            OutputMetadata(
                entryId = entryId,
                cacheId = cacheId,
                createdAtEpochMs = 1L,
                pdf =
                    PdfOutputRef(
                        uri = "content://media/external/downloads/42",
                        treeUri = null,
                        pending = true,
                    ),
            )

        assertThrows(IllegalArgumentException::class.java) {
            encodeOutputMetadata(incomplete, pageCount = 1)
        }
    }

    @Test
    fun pendingImagesAreNotTreatedAsCompletedSaves() {
        val metadata =
            OutputMetadata(
                entryId = entryId,
                cacheId = cacheId,
                createdAtEpochMs = 1L,
                images =
                    listOf(
                        ImageOutputRef(
                            page = 1,
                            uri = "content://media/external/images/media/43",
                            pending = true,
                        ),
                    ),
            )

        assertThrows(IOException::class.java) {
            existingCompleteImagesForSave(metadata, pageCount = 1)
        }
    }

    @Test
    fun outputFingerprintRoundTripsAndRejectsIncompleteOrMalformedPairs() {
        val metadata =
            OutputMetadata(
                entryId = entryId,
                cacheId = cacheId,
                createdAtEpochMs = 1L,
                pdf =
                    PdfOutputRef(
                        uri = "content://media/external/downloads/42",
                        treeUri = null,
                        byteLength = 7L,
                        sha256 = "ab".repeat(32),
                    ),
            )

        assertEquals(
            metadata,
            decodeOutputMetadata(encodeOutputMetadata(metadata, 1), cacheId, 1),
        )
        assertNull(
            decodeOutputMetadata(
                encodeOutputMetadata(metadata, 1)
                    .toString(StandardCharsets.UTF_8)
                    .replace("\"sha256\":\"${"ab".repeat(32)}\"", "\"sha256\":null")
                    .toByteArray(StandardCharsets.UTF_8),
                cacheId,
                1,
            ),
        )
        assertNull(
            decodeOutputMetadata(
                encodeOutputMetadata(metadata, 1)
                    .toString(StandardCharsets.UTF_8)
                    .replace("${"ab".repeat(32)}", "not-a-hash")
                    .toByteArray(StandardCharsets.UTF_8),
                cacheId,
                1,
            ),
        )
    }

    @Test
    fun fingerprintReadsExactlyTheExpectedBytes() {
        val bytes = "ScanIt".toByteArray()
        val fingerprint = readOutputFingerprint(ByteArrayInputStream(bytes), bytes.size.toLong())

        assertEquals(bytes.size.toLong(), fingerprint.byteLength)
        assertEquals(64, fingerprint.sha256.length)
        assertTrue(outputFingerprintMatches(ByteArrayInputStream(bytes), fingerprint))
        assertTrue(!outputFingerprintMatches(ByteArrayInputStream(bytes + 0.toByte()), fingerprint))
        assertThrows(IOException::class.java) {
            readOutputFingerprint(ByteArrayInputStream(bytes.copyOf(3)), bytes.size.toLong())
        }
    }

    @Test
    fun numberedImagesRoundTripInPageOrder() {
        val metadata =
            OutputMetadata(
                entryId = entryId,
                cacheId = cacheId,
                createdAtEpochMs = 2L,
                images =
                    listOf(
                        ImageOutputRef(1, "content://media/external/images/media/10"),
                        ImageOutputRef(2, "content://media/external/images/media/11"),
                    ),
            )

        assertEquals(
            metadata,
            decodeOutputMetadata(
                encodeOutputMetadata(metadata, pageCount = 2),
                expectedCacheId = cacheId,
                pageCount = 2,
            ),
        )
    }

    @Test
    fun validV1V2AndV3MetadataRemainReadable() {
        assertEquals(1, decode(validJson(), pageCount = 1)?.version)
        assertEquals(
            2,
            decode(validJson().replace("\"version\":1", "\"version\":2"), pageCount = 1)
                ?.version,
        )

        val metadata =
            OutputMetadata(
                entryId = entryId,
                cacheId = cacheId,
                createdAtEpochMs = 3L,
                pdf = exactPdf("content://media/external/downloads/current"),
                images =
                    listOf(
                        exactImage(
                            page = 1,
                            uri = "content://docs/document/root%3Acurrent.png",
                            format = ImageExportFormat.Png,
                            mimeType = "image/png",
                            treeUri = "content://docs/tree/root",
                        ),
                    ),
                stagedPdf = exactPdf("content://media/external/downloads/staged"),
                stagedImages =
                    listOf(exactImage(1, "content://media/external/images/staged")),
                retiredPdf = exactPdf("content://media/external/downloads/retired"),
                retiredImages =
                    listOf(exactImage(1, "content://media/external/images/retired")),
                version = 3,
            )

        assertEquals(metadata, decodeOutputMetadata(encodeOutputMetadata(metadata, 1), cacheId, 1))
    }

    @Test
    fun readingUntouchedLegacyMetadataDoesNotRewriteIt() = withDirectory { directory ->
        val bytes = validJson().toByteArray(StandardCharsets.UTF_8)
        val sidecar = File(directory, OUTPUT_METADATA_FILE_NAME)
        sidecar.writeBytes(bytes)

        assertEquals(1, ensureOutputMetadata(directory, cacheId, 1, createdAtEpochMs = 99L).version)
        assertTrue(bytes.contentEquals(sidecar.readBytes()))
    }

    @Test
    fun v3RequiresExactFingerprintsForCurrentAndRetiredOutputs() {
        val currentWithoutFingerprint =
            OutputMetadata(
                entryId = entryId,
                cacheId = cacheId,
                createdAtEpochMs = 1L,
                images =
                    listOf(
                        ImageOutputRef(
                            page = 1,
                            uri = "content://media/external/images/current",
                            displayName = "current.jpg",
                            mimeType = "image/jpeg",
                            ownerPackageName = "com.majkeylab.scanit.internal",
                            width = 1200,
                            height = 800,
                            format = ImageExportFormat.Jpeg,
                        ),
                    ),
                version = 3,
            )
        assertThrows(IllegalArgumentException::class.java) {
            encodeOutputMetadata(currentWithoutFingerprint, 1)
        }

        val retiredWithoutFingerprint =
            currentWithoutFingerprint.copy(
                images = listOf(exactImage(1, "content://media/external/images/current")),
                retiredPdf =
                    PdfOutputRef(
                        uri = "content://media/external/downloads/retired",
                        treeUri = null,
                        displayName = "retired.pdf",
                        mimeType = "application/pdf",
                        ownerPackageName = "com.majkeylab.scanit.internal",
                    ),
            )
        assertThrows(IllegalArgumentException::class.java) {
            encodeOutputMetadata(retiredWithoutFingerprint, 1)
        }
    }

    @Test
    fun v3CleanupJournalIsBoundedAndKeepsExactPageOrder() {
        val maximum =
            (1..MAX_SCAN_PAGES).map { page ->
                exactImage(page, "content://media/external/images/retired-$page")
            }
        val metadata =
            OutputMetadata(
                entryId = entryId,
                cacheId = cacheId,
                createdAtEpochMs = 1L,
                retiredImages = maximum,
                version = 3,
            )

        assertEquals(metadata, decodeOutputMetadata(encodeOutputMetadata(metadata, 20), cacheId, 20))
        assertThrows(IllegalArgumentException::class.java) {
            encodeOutputMetadata(metadata.copy(retiredImages = maximum.reversed()), 20)
        }
        assertThrows(IllegalArgumentException::class.java) {
            encodeOutputMetadata(
                metadata.copy(
                    retiredImages = maximum + exactImage(21, "content://media/external/images/retired-21"),
                ),
                21,
            )
        }
    }

    @Test
    fun v3RejectsUnknownDataUnsafeUrisFormatMismatchAndDuplicateUris() {
        val current = exactImage(1, "content://media/external/images/current")
        val metadata =
            OutputMetadata(
                entryId = entryId,
                cacheId = cacheId,
                createdAtEpochMs = 1L,
                images = listOf(current),
                version = 3,
            )

        val unknown =
            encodeOutputMetadata(metadata, 1)
                .toString(StandardCharsets.UTF_8)
                .replace("\"version\":3", "\"version\":3,\"unknown\":true")
        assertNull(decode(unknown, 1))
        assertNull(decode(unknown.replace("\"version\":3", "\"version\":4"), 1))
        assertThrows(IllegalArgumentException::class.java) {
            encodeOutputMetadata(
                metadata.copy(
                    images = listOf(current.copy(treeUri = "https://example.com/tree")),
                ),
                1,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            encodeOutputMetadata(
                metadata.copy(images = listOf(current.copy(mimeType = "image/png"))),
                1,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            encodeOutputMetadata(
                metadata.copy(images = listOf(current.copy(width = 0))),
                1,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            encodeOutputMetadata(
                metadata.copy(images = listOf(current.copy(width = 4000, height = 3001))),
                1,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            encodeOutputMetadata(
                metadata.copy(
                    images = listOf(current.copy(ownerPackageName = "a".repeat(256))),
                ),
                1,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            encodeOutputMetadata(metadata.copy(retiredImages = listOf(current)), 1)
        }
    }

    @Test
    fun v3EncodingRetainsTheStrict64KiBBound() {
        val metadata =
            OutputMetadata(
                entryId = entryId,
                cacheId = cacheId,
                createdAtEpochMs = 1L,
                retiredImages =
                    (1..MAX_SCAN_PAGES).map { page ->
                        exactImage(
                            page,
                            "content://media/external/images/$page/${"a".repeat(3700)}",
                        )
                    },
                version = 3,
            )

        assertThrows(IllegalArgumentException::class.java) {
            encodeOutputMetadata(metadata, MAX_SCAN_PAGES)
        }
    }

    @Test
    fun validEmptyMetadataRoundTrips() {
        val metadata = OutputMetadata(entryId, cacheId, createdAtEpochMs = 3L)

        assertEquals(
            metadata,
            decodeOutputMetadata(
                encodeOutputMetadata(metadata, pageCount = 1),
                expectedCacheId = cacheId,
                pageCount = 1,
            ),
        )
    }

    @Test
    fun missingCorruptUnknownAndOversizeMetadataAreUnavailable() = withDirectory { directory ->
        assertNull(readOutputMetadata(directory, cacheId, pageCount = 1))

        val sidecar = File(directory, OUTPUT_METADATA_FILE_NAME)
        sidecar.writeText("not json")
        assertNull(readOutputMetadata(directory, cacheId, pageCount = 1))

        sidecar.writeText(validJson().replace("\"version\":1", "\"version\":99"))
        assertNull(readOutputMetadata(directory, cacheId, pageCount = 1))

        sidecar.writeBytes(ByteArray(MAX_OUTPUT_METADATA_BYTES + 1))
        assertNull(readOutputMetadata(directory, cacheId, pageCount = 1))
    }

    @Test
    fun metadataReadDistinguishesPermanentInvalidDataFromTransientIo() = withDirectory { directory ->
        assertEquals(
            OutputMetadataReadResult.Invalid,
            readOutputMetadataResult(directory, cacheId, pageCount = 1),
        )

        val sidecar = File(directory, OUTPUT_METADATA_FILE_NAME)
        sidecar.writeText("not json")
        assertEquals(
            OutputMetadataReadResult.Invalid,
            readOutputMetadataResult(directory, cacheId, pageCount = 1),
        )

        sidecar.writeText(validJson(cacheId = "Scan_other"))
        assertEquals(
            OutputMetadataReadResult.Invalid,
            readOutputMetadataResult(directory, cacheId, pageCount = 1),
        )

        val metadata = OutputMetadata(entryId, cacheId, createdAtEpochMs = 3L)
        sidecar.writeBytes(encodeOutputMetadata(metadata, pageCount = 1))
        assertEquals(
            OutputMetadataReadResult.Valid(metadata),
            readOutputMetadataResult(directory, cacheId, pageCount = 1),
        )
        assertEquals(
            OutputMetadataReadResult.Failed,
            readOutputMetadataResult(directory, cacheId, pageCount = 1) {
                throw IOException("temporary read failure")
            },
        )
    }

    @Test
    fun duplicateOutOfRangeAndMismatchedMetadataAreUnavailable() {
        val duplicateImages =
            validJson(
                images =
                    """[{"page":1,"uri":"content://media/images/1"},{"page":1,"uri":"content://media/images/2"}]""",
            )
        assertNull(decode(duplicateImages, pageCount = 2))
        assertNull(
            decode(
                validJson(images = """[{"page":2,"uri":"content://media/images/2"}]"""),
                pageCount = 1,
            ),
        )
        assertNull(decode(validJson(cacheId = "Scan_other"), pageCount = 1))
        assertNull(decode(validJson(entryId = "123E4567-E89B-12D3-A456-426614174000"), 1))
        assertNull(decode(validJson(entryId = "not-a-uuid"), pageCount = 1))
        assertNull(
            decode(
                validJson(images = """[{"page":1,"uri":"https://example.com/1"}]"""),
                pageCount = 1,
            ),
        )
    }

    @Test
    fun rewritePreservesGenerationCreationTimeOtherKindAndImageOrder() =
        withDirectory { directory ->
            val original =
                OutputMetadata(
                    entryId = entryId,
                    cacheId = cacheId,
                    createdAtEpochMs = 99L,
                    images =
                        listOf(
                            ImageOutputRef(1, "content://media/images/1"),
                            ImageOutputRef(2, "content://media/images/2"),
                        ),
                )
            initializeOutputMetadata(
                directory = directory,
                cacheId = cacheId,
                pageCount = 2,
                createdAtEpochMs = original.createdAtEpochMs,
                entryId = original.entryId,
            )
            rewriteOutputMetadata(directory, cacheId, entryId, pageCount = 2) {
                original
            }

            val rewritten =
                rewriteOutputMetadata(directory, cacheId, entryId, pageCount = 2) {
                    it.copy(pdf = PdfOutputRef("content://media/downloads/7", null))
                }

            assertEquals(entryId, rewritten.entryId)
            assertEquals(99L, rewritten.createdAtEpochMs)
            assertEquals(original.images, rewritten.images)
            assertEquals("content://media/downloads/7", rewritten.pdf?.uri)
            assertEquals(rewritten, readOutputMetadata(directory, cacheId, pageCount = 2))
        }

    @Test
    fun rewritePromotesV1ToV2UnlessCallerExplicitlySelectsV3() =
        withDirectory { directory ->
            File(directory, OUTPUT_METADATA_FILE_NAME).writeText(validJson())
            val pendingImage =
                ImageOutputRef(
                    page = 1,
                    uri = "content://media/external/images/pending",
                    displayName = "pending.jpg",
                    mimeType = "image/jpeg",
                    ownerPackageName = "com.majkeylab.scanit.internal",
                    byteLength = 10L,
                    sha256 = "11".repeat(32),
                    pending = true,
                )

            val promoted =
                rewriteOutputMetadata(directory, cacheId, entryId, pageCount = 1) {
                    it.copy(images = listOf(pendingImage))
                }
            assertEquals(2, promoted.version)
            assertEquals(promoted, readOutputMetadata(directory, cacheId, pageCount = 1))

            File(directory, OUTPUT_METADATA_FILE_NAME).writeText(validJson())
            val upgraded =
                rewriteOutputMetadata(directory, cacheId, entryId, pageCount = 1) {
                    it.copy(
                        images = listOf(exactImage(1, "content://media/external/images/current")),
                        version = 3,
                    )
                }
            assertEquals(3, upgraded.version)
            assertEquals(upgraded, readOutputMetadata(directory, cacheId, pageCount = 1))
        }

    @Test
    fun v3RejectsWhitespaceOnlyPdfAndImageOwners() {
        val imageMetadata =
            OutputMetadata(
                entryId = entryId,
                cacheId = cacheId,
                createdAtEpochMs = 1L,
                images =
                    listOf(
                        exactImage(1, "content://media/external/images/current")
                            .copy(ownerPackageName = "   "),
                    ),
                version = 3,
            )
        val pdfMetadata =
            imageMetadata.copy(
                images = emptyList(),
                pdf = exactPdf("content://media/external/downloads/current")
                    .copy(ownerPackageName = "   "),
            )

        assertThrows(IllegalArgumentException::class.java) {
            encodeOutputMetadata(imageMetadata, 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            encodeOutputMetadata(pdfMetadata, 1)
        }
    }

    @Test
    fun rewriteRejectsAnotherCacheGenerationWithoutChangingMetadata() =
        withDirectory { directory ->
            val original =
                initializeOutputMetadata(
                    directory,
                    cacheId,
                    pageCount = 1,
                    createdAtEpochMs = 7L,
                    entryId = entryId,
                )

            assertThrows(IOException::class.java) {
                rewriteOutputMetadata(
                    directory,
                    cacheId,
                    expectedEntryId = "223e4567-e89b-12d3-a456-426614174000",
                    pageCount = 1,
                ) { it.copy(pdf = PdfOutputRef("content://media/downloads/8", null)) }
            }

            assertEquals(original, readOutputMetadata(directory, cacheId, pageCount = 1))
        }

    @Test
    fun committedMetadataDoesNotDependOnAPostMoveReadback() =
        withDirectory { directory ->
            initializeOutputMetadata(
                directory,
                cacheId,
                pageCount = 1,
                createdAtEpochMs = 7L,
                entryId = entryId,
            )
            val relocated = File(directory.parentFile, "${directory.name}-relocated")
            try {
                val updated =
                    rewriteOutputMetadata(
                        directory = directory,
                        expectedCacheId = cacheId,
                        expectedEntryId = entryId,
                        pageCount = 1,
                        moveMetadata = { source, target ->
                            Files.move(
                                source,
                                target,
                                StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING,
                            )
                            Files.move(
                                directory.toPath(),
                                relocated.toPath(),
                                StandardCopyOption.ATOMIC_MOVE,
                            )
                        },
                    ) {
                        it.copy(pdf = PdfOutputRef("content://media/downloads/8", null))
                    }

                assertEquals(updated, readOutputMetadata(relocated, cacheId, pageCount = 1))
            } finally {
                if (relocated.exists()) assertTrue(relocated.deleteRecursively())
            }
        }

    @Test
    fun exactCopyAcceptsOnlyEqualKnownLengths() {
        requireExactProviderCopy(
            expectedLength = 5L,
            copiedLength = 5L,
            reportedLength = 5L,
        ) { throw AssertionError("Known length must not reopen") }

        assertThrows(IOException::class.java) {
            requireExactProviderCopy(5L, copiedLength = 4L, reportedLength = 5L) { 5L }
        }
        assertThrows(IOException::class.java) {
            requireExactProviderCopy(5L, copiedLength = 6L, reportedLength = 5L) { 5L }
        }
        assertThrows(IOException::class.java) {
            requireExactProviderCopy(5L, copiedLength = 5L, reportedLength = 4L) { 5L }
        }
        assertThrows(IOException::class.java) {
            requireExactProviderCopy(0L, copiedLength = 0L, reportedLength = 0L) { 0L }
        }
    }

    @Test
    fun unknownProviderLengthRequiresAnExactBoundedRecount() {
        requireExactProviderCopy(5L, copiedLength = 5L, reportedLength = null) { 5L }
        requireExactProviderCopy(5L, copiedLength = 5L, reportedLength = -1L) { 5L }

        assertThrows(IOException::class.java) {
            requireExactProviderCopy(5L, copiedLength = 5L, reportedLength = null) { 4L }
        }
        assertThrows(IOException::class.java) {
            requireExactProviderCopy(5L, copiedLength = 5L, reportedLength = null) { 6L }
        }
    }

    @Test
    fun providerRecountReadsAtMostTheRequestedLimit() {
        assertEquals(4L, countBytesAtMost(ByteArrayInputStream(ByteArray(4)), limit = 6L))
        assertEquals(6L, countBytesAtMost(ByteArrayInputStream(ByteArray(10)), limit = 6L))
        assertThrows(IllegalArgumentException::class.java) {
            countBytesAtMost(ByteArrayInputStream(ByteArray(1)), limit = 0L)
        }
    }

    private fun decode(json: String, pageCount: Int): OutputMetadata? =
        decodeOutputMetadata(
            json.toByteArray(StandardCharsets.UTF_8),
            expectedCacheId = cacheId,
            pageCount = pageCount,
        )

    private fun exactPdf(uri: String): PdfOutputRef =
        PdfOutputRef(
            uri = uri,
            treeUri = null,
            displayName = "scan.pdf",
            mimeType = "application/pdf",
            ownerPackageName = "com.majkeylab.scanit.internal",
            byteLength = 10L,
            sha256 = "00".repeat(32),
        )

    private fun exactImage(
        page: Int,
        uri: String,
        format: ImageExportFormat = ImageExportFormat.Jpeg,
        mimeType: String = "image/jpeg",
        treeUri: String? = null,
    ): ImageOutputRef =
        ImageOutputRef(
            page = page,
            uri = uri,
            displayName = "scan-$page.${if (format == ImageExportFormat.Png) "png" else "jpg"}",
            mimeType = mimeType,
            ownerPackageName =
                if (treeUri == null) "com.majkeylab.scanit.internal" else null,
            byteLength = 10L,
            sha256 = "11".repeat(32),
            treeUri = treeUri,
            width = 1200,
            height = 800,
            format = format,
        )

    private fun validJson(
        cacheId: String = this.cacheId,
        entryId: String = this.entryId,
        images: String = "[]",
    ): String =
        """{"version":1,"entryId":"$entryId","cacheId":"$cacheId","createdAtEpochMs":1,"images":$images}"""

    private fun withDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("output-metadata").toFile()
        try {
            block(directory)
        } finally {
            assertTrue(directory.deleteRecursively())
        }
    }
}
