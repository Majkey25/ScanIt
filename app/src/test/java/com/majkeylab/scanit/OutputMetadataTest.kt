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
                    ),
                images =
                    listOf(
                        ImageOutputRef(
                            page = 1,
                            uri = "content://media/external/images/media/43",
                            displayName = "scan (1).jpg",
                            mimeType = "image/jpeg",
                            ownerPackageName = owner,
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

        sidecar.writeText(validJson().replace("\"version\":1", "\"version\":2"))
        assertNull(readOutputMetadata(directory, cacheId, pageCount = 1))

        sidecar.writeBytes(ByteArray(MAX_OUTPUT_METADATA_BYTES + 1))
        assertNull(readOutputMetadata(directory, cacheId, pageCount = 1))
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
