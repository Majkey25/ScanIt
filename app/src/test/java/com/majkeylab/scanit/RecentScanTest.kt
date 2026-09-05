package com.majkeylab.scanit

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentScanTest {
    @Test
    fun scanInputCopyRejectsEmptyAndOversizedProviderStreams() {
        val exact = ByteArrayOutputStream()

        assertEquals(
            4L,
            copyBoundedInput(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), exact, 4L),
        )
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), exact.toByteArray())
        assertThrows(IOException::class.java) {
            copyBoundedInput(ByteArrayInputStream(ByteArray(5)), ByteArrayOutputStream(), 4L)
        }
        assertThrows(IOException::class.java) {
            copyBoundedInput(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), 4L)
        }
        assertThrows(CancellationException::class.java) {
            copyBoundedInput(
                ByteArrayInputStream(byteArrayOf(1)),
                ByteArrayOutputStream(),
                4L,
                isCancelled = { true },
            )
        }
    }

    @Test
    fun provisionalCreateMarkerIsBoundedAtomicAndAmbiguousRecoveryBlocksMutation() =
        withShareRoot { root ->
            val marker = provisionalCreate(returnedUri = null)

            writeProvisionalOutputCreate(root, marker, pageCount = 2)

            assertEquals(
                ProvisionalOutputCreateReadResult.Valid(marker),
                readProvisionalOutputCreate(root, CACHE_ID, ENTRY_ID, pageCount = 2),
            )
            var deleted = false
            var cleared = false
            val recovery =
                reconcileProvisionalOutputCreate(
                    marker = marker,
                    metadata = metadata(),
                    delete = {
                        deleted = true
                        OutputDeleteStatus.Deleted
                    },
                    clear = { cleared = true },
                )

            assertTrue(recovery.blocking)
            assertEquals(R.string.output_create_cleanup_required, recovery.warnings.single().resourceId)
            assertFalse(deleted)
            assertFalse(cleared)
            assertTrue(File(root, PROVISIONAL_OUTPUT_CREATE_FILE_NAME).isFile)
        }

    @Test
    fun nullOrThrowingProviderCreateRetainsExactAcknowledgableMarker() =
        withShareRoot { root ->
            val marker = provisionalCreate()

            assertThrows(IOException::class.java) {
                createProviderOutputWithMarker<String>(
                    beforeCreate = { writeProvisionalOutputCreate(root, marker, pageCount = 2) },
                    create = { null },
                    onCreated = {},
                )
            }
            assertEquals(
                UnknownOutputCreateAcknowledgement(CACHE_ID, ENTRY_ID, marker.operationId),
                readUnknownOutputCreateAcknowledgement(root, CACHE_ID, ENTRY_ID),
            )
            clearProvisionalOutputCreate(root, marker, pageCount = 2)

            assertThrows(IOException::class.java) {
                createProviderOutputWithMarker<String>(
                    beforeCreate = { writeProvisionalOutputCreate(root, marker, pageCount = 2) },
                    create = { throw IOException("provider failed before return") },
                    onCreated = {},
                )
            }
            assertEquals(
                UnknownOutputCreateAcknowledgement(CACHE_ID, ENTRY_ID, marker.operationId),
                readUnknownOutputCreateAcknowledgement(root, CACHE_ID, ENTRY_ID),
            )
        }

    @Test
    fun unknownOutputAcknowledgementIsExactIdempotentAndNeverDeletesProviderData() =
        withShareRoot { root ->
            val marker = provisionalCreate()
            val exact = UnknownOutputCreateAcknowledgement(CACHE_ID, ENTRY_ID, marker.operationId)
            writeProvisionalOutputCreate(root, marker, pageCount = 2)

            assertEquals(
                UnknownOutputAcknowledgementResult.Stale,
                acknowledgeUnknownProvisionalOutput(
                    root,
                    exact.copy(entryId = "123e4567-e89b-12d3-a456-426614174088"),
                    pageCount = 2,
                ),
            )
            assertEquals(
                UnknownOutputAcknowledgementResult.Stale,
                acknowledgeUnknownProvisionalOutput(
                    root,
                    exact.copy(operationId = "123e4567-e89b-12d3-a456-426614174077"),
                    pageCount = 2,
                ),
            )
            assertTrue(File(root, PROVISIONAL_OUTPUT_CREATE_FILE_NAME).isFile)
            assertEquals(
                UnknownOutputAcknowledgementResult.Applied,
                acknowledgeUnknownProvisionalOutput(root, exact, pageCount = 2),
            )
            assertEquals(
                UnknownOutputAcknowledgementResult.Absent,
                acknowledgeUnknownProvisionalOutput(root, exact, pageCount = 2),
            )
            assertFalse(File(root, PROVISIONAL_OUTPUT_CREATE_FILE_NAME).exists())
        }

    @Test
    fun invalidUnknownMarkerWithExactEnvelopeCanBeAcknowledgedButClearFailureStaysBlocking() =
        withShareRoot { root ->
            val marker = provisionalCreate()
            val exact = UnknownOutputCreateAcknowledgement(CACHE_ID, ENTRY_ID, marker.operationId)
            writeProvisionalOutputCreate(root, marker, pageCount = 2)
            val file = File(root, PROVISIONAL_OUTPUT_CREATE_FILE_NAME)
            file.writeText(
                file.readText().replace(
                    "\"page\":1",
                    "\"page\":\"invalid\"",
                ),
            )

            assertEquals(
                ProvisionalOutputCreateReadResult.Invalid,
                readProvisionalOutputCreate(root, CACHE_ID, ENTRY_ID, pageCount = 2),
            )
            assertEquals(exact, readUnknownOutputCreateAcknowledgement(root, CACHE_ID, ENTRY_ID))
            assertEquals(
                UnknownOutputAcknowledgementResult.Failed,
                acknowledgeUnknownProvisionalOutput(
                    root,
                    exact,
                    pageCount = 2,
                    deleteMarker = { throw IOException("disk") },
                ),
            )
            assertTrue(file.isFile)
            assertEquals(
                UnknownOutputAcknowledgementResult.Applied,
                acknowledgeUnknownProvisionalOutput(root, exact, pageCount = 2),
            )
            assertFalse(file.exists())
        }

    @Test
    fun reconciliationClearFailureRetainsBlockingWarningInsteadOfClosingCoreScan() {
        val marker = provisionalCreate("content://media/external/images/media/9")
        val result =
            reconcileProvisionalOutputCreate(
                marker = marker,
                metadata = metadata(),
                delete = { OutputDeleteStatus.Absent },
                clear = { throw IOException("disk") },
            )

        assertTrue(result.blocking)
        assertEquals(R.string.output_create_cleanup_required, result.warnings.single().resourceId)
    }

    @Test
    fun invalidUnknownOutputMarkerDoesNotHideTheCoreCachedScan() =
        withShareRoot { root ->
            val directory = createEntry(root, CACHE_ID)
            initializeOutputMetadata(directory, CACHE_ID, 1, 1L, ENTRY_ID)
            val marker = provisionalCreate()
            writeProvisionalOutputCreate(directory, marker, pageCount = 1)
            val file = File(directory, PROVISIONAL_OUTPUT_CREATE_FILE_NAME)
            file.writeText(file.readText().replace("\"returnedUri\":null", "\"returnedUri\":7"))

            assertEquals(
                ProvisionalOutputCreateReadResult.Invalid,
                readProvisionalOutputCreate(directory, CACHE_ID, ENTRY_ID, pageCount = 1),
            )
            assertEquals(CACHE_ID, requireNotNull(RecentScanCache(root).open(CACHE_ID)).baseName)
        }

    @Test
    fun exactOrStagedProvisionalCreateRecoveryClearsOnlyAfterResolution() {
        val exact = provisionalCreate(returnedUri = "content://media/external/images/media/9")
        var deletes = 0
        var clears = 0

        val deleted =
            reconcileProvisionalOutputCreate(
                marker = exact,
                metadata = metadata(),
                delete = {
                    deletes++
                    OutputDeleteStatus.Absent
                },
                clear = { clears++ },
            )
        assertFalse(deleted.blocking)
        assertEquals(1, deletes)
        assertEquals(1, clears)

        val staged =
            exactImage(1, requireNotNull(exact.returnedUri), pending = true)
                .copy(displayName = exact.displayName)
        val journaled =
            reconcileProvisionalOutputCreate(
                marker = exact,
                metadata = metadata().copy(stagedImages = listOf(staged), version = OUTPUT_METADATA_VERSION),
                delete = {
                    deletes++
                    OutputDeleteStatus.Failed
                },
                clear = { clears++ },
            )
        assertFalse(journaled.blocking)
        assertEquals(1, deletes)
        assertEquals(2, clears)
    }

    @Test
    fun provisionalCreateUriUpdateIsExactCasAndInvalidMarkerIsRetained() =
        withShareRoot { root ->
            val ambiguous = provisionalCreate(returnedUri = null)
            writeProvisionalOutputCreate(root, ambiguous, pageCount = 2)
            val exact =
                updateProvisionalOutputCreateUri(
                    root,
                    ambiguous,
                    "content://media/external/images/media/9",
                    pageCount = 2,
                )
            assertEquals(
                ProvisionalOutputCreateReadResult.Valid(exact),
                readProvisionalOutputCreate(root, CACHE_ID, ENTRY_ID, pageCount = 2),
            )
            assertThrows(IOException::class.java) {
                updateProvisionalOutputCreateUri(
                    root,
                    ambiguous,
                    "content://media/external/images/media/10",
                    pageCount = 2,
                )
            }
            clearProvisionalOutputCreate(root, exact, pageCount = 2)

            val marker = File(root, PROVISIONAL_OUTPUT_CREATE_FILE_NAME)
            marker.writeText("{}")
            assertEquals(
                ProvisionalOutputCreateReadResult.Invalid,
                readProvisionalOutputCreate(root, CACHE_ID, ENTRY_ID, pageCount = 2),
            )
            assertTrue(marker.isFile)
        }

    @Test
    fun provisionalCreateCodecRejectsUnsafeUriWrongGenerationExtraFieldsAndOversize() =
        withShareRoot { root ->
            assertThrows(IOException::class.java) {
                writeProvisionalOutputCreate(File(root, "."), provisionalCreate(), pageCount = 2)
            }
            assertThrows(IOException::class.java) {
                writeProvisionalOutputCreate(
                    root,
                    provisionalCreate(returnedUri = "https://provider/image/9"),
                    pageCount = 2,
                )
            }
            assertFalse(File(root, PROVISIONAL_OUTPUT_CREATE_FILE_NAME).exists())

            val first = provisionalCreate()
            writeProvisionalOutputCreate(root, first, pageCount = 2)
            assertEquals(
                ProvisionalOutputCreateReadResult.Invalid,
                readProvisionalOutputCreate(
                    root,
                    CACHE_ID,
                    "123e4567-e89b-12d3-a456-426614174088",
                    pageCount = 2,
                ),
            )
            assertThrows(IOException::class.java) {
                writeProvisionalOutputCreate(
                    root,
                    provisionalCreate(operationId = "123e4567-e89b-12d3-a456-426614174077").copy(page = 2),
                    pageCount = 2,
                )
            }
            val file = File(root, PROVISIONAL_OUTPUT_CREATE_FILE_NAME)
            assertEquals(
                ProvisionalOutputCreateReadResult.Valid(first),
                readProvisionalOutputCreate(root, CACHE_ID, ENTRY_ID, 2),
            )
            file.writeText(file.readText().replace("\"version\":1", "\"version\":2"))
            assertEquals(
                ProvisionalOutputCreateReadResult.Invalid,
                readProvisionalOutputCreate(root, CACHE_ID, ENTRY_ID, pageCount = 2),
            )
            file.writeText(file.readText().replace("\"version\":2", "\"version\":1").dropLast(1) + ",\"extra\":true}")
            assertEquals(
                ProvisionalOutputCreateReadResult.Invalid,
                readProvisionalOutputCreate(root, CACHE_ID, ENTRY_ID, pageCount = 2),
            )
            file.writeBytes(ByteArray(8 * 1024 + 1))
            assertEquals(
                ProvisionalOutputCreateReadResult.Invalid,
                readProvisionalOutputCreate(root, CACHE_ID, ENTRY_ID, pageCount = 2),
            )
            assertTrue(file.isFile)
        }

    @Test
    fun provisionalCreateRecoveryRetainsFailureThenConvergesOnExactRetry() =
        withShareRoot { root ->
            val exact = provisionalCreate("content://media/external/images/media/9")
            writeProvisionalOutputCreate(root, exact, pageCount = 2)
            var attempt = 0

            fun recover() =
                reconcileProvisionalOutputCreate(
                    exact,
                    metadata(),
                    delete = {
                        attempt++
                        if (attempt == 1) OutputDeleteStatus.Failed else OutputDeleteStatus.Absent
                    },
                    clear = { clearProvisionalOutputCreate(root, exact, pageCount = 2) },
                )

            assertTrue(recover().blocking)
            assertTrue(File(root, PROVISIONAL_OUTPUT_CREATE_FILE_NAME).isFile)
            assertFalse(recover().blocking)
            assertEquals(
                ProvisionalOutputCreateReadResult.Absent,
                readProvisionalOutputCreate(root, CACHE_ID, ENTRY_ID, pageCount = 2),
            )
        }

    @Test
    fun preparedImageShareUsesVerifiedBoundedPrivateCopiesAndExplicitCleanup() =
        withShareRoot { root ->
            val jpeg = byteArrayOf(1, 2, 3)
            val png = byteArrayOf(4, 5, 6, 7)
            val contents =
                mapOf(
                    "content://provider/image/1" to jpeg,
                    "content://provider/image/2" to png,
                )
            val outputs =
                listOf(
                    savedImage(1, "content://provider/image/1", "image/jpeg", jpeg),
                    savedImage(2, "content://provider/image/2", "image/png", png),
                )

            val prepared =
                prepareImageShareCopies(
                    shareRoot = root,
                    outputs = outputs,
                    open = { uri -> ByteArrayInputStream(requireNotNull(contents[uri])) },
                    operationId = "123e4567-e89b-12d3-a456-426614174099",
                )

            assertEquals("image/*", prepared.mimeType)
            assertEquals(listOf("page-01.jpg", "page-02.png"), prepared.files.map(File::getName))
            assertEquals(jpeg.toList(), prepared.files[0].readBytes().toList())
            assertEquals(png.toList(), prepared.files[1].readBytes().toList())
            assertTrue(prepared.files.all { it.canonicalFile.parentFile == prepared.directory })
            assertTrue(cleanupPreparedImageShare(prepared))
            assertFalse(prepared.directory.exists())
        }

    @Test
    fun preparedImageShareSurvivesDurableSourceDeletion() =
        withShareRoot { root ->
            val bytes = byteArrayOf(8, 6, 7, 5, 3, 0, 9)
            val durable = File(root, "durable.jpg").apply { writeBytes(bytes) }
            val prepared =
                prepareImageShareCopies(
                    shareRoot = File(root, "private").apply { assertTrue(mkdir()) },
                    outputs =
                        listOf(
                            savedImage(
                                1,
                                "content://provider/image/1",
                                "image/jpeg",
                                bytes,
                            ),
                        ),
                    open = { FileInputStream(durable) },
                    operationId = "123e4567-e89b-12d3-a456-426614174094",
                )

            assertTrue(durable.delete())
            assertEquals(bytes.toList(), prepared.files.single().readBytes().toList())
            assertTrue(cleanupPreparedImageShare(prepared))
        }

    @Test
    fun preparedImageShareValidationRejectsFilesOutsideItsPrivateDirectory() =
        withShareRoot { root ->
            val directory =
                File(root, "${PREPARED_IMAGE_SHARE_PREFIX}123e4567-e89b-12d3-a456-426614174096")
            assertTrue(directory.mkdir())
            val outside = File(root, "outside.jpg").apply { writeBytes(byteArrayOf(1)) }

            assertThrows(IOException::class.java) {
                validatePreparedImageShare(
                    PreparedImageShare(root, directory, listOf(outside), "image/jpeg"),
                )
            }
        }

    @Test
    fun preparedImageShareValidationRejectsNonCanonicalPayloadDirectoryName() =
        withShareRoot { root ->
            val directory = File(root, "${PREPARED_IMAGE_SHARE_PREFIX}not-a-uuid")
            assertTrue(directory.mkdir())
            File(directory, ".lease").writeText("1")
            val page = File(directory, "page-01.jpg").apply { writeBytes(byteArrayOf(1)) }

            assertThrows(IOException::class.java) {
                validatePreparedImageShare(
                    PreparedImageShare(root, directory, listOf(page), "image/jpeg"),
                )
            }
            assertFalse(
                cleanupPreparedImageShare(
                    PreparedImageShare(root, directory, listOf(page), "image/jpeg"),
                ),
            )
            assertTrue(directory.isDirectory)
        }

    @Test
    fun preparedImageShareRejectsUnboundedOrCancelledCopiesAndCleansScratch() =
        withShareRoot { root ->
            val oversized =
                savedImage(
                    page = 1,
                    uri = "content://provider/image/large",
                    mimeType = "image/jpeg",
                    bytes = byteArrayOf(1),
                    declaredLength = MAX_PREPARED_IMAGE_SHARE_BYTES + 1,
                )
            assertThrows(IOException::class.java) {
                prepareImageShareCopies(
                    shareRoot = root,
                    outputs = listOf(oversized),
                    open = { ByteArrayInputStream(byteArrayOf(1)) },
                    operationId = "123e4567-e89b-12d3-a456-426614174098",
                )
            }

            val cancellation = java.util.concurrent.CancellationException("cancelled")
            assertSame(
                cancellation,
                assertThrows(java.util.concurrent.CancellationException::class.java) {
                    prepareImageShareCopies(
                        shareRoot = root,
                        outputs = listOf(savedImage(1, "content://provider/image/1", "image/jpeg", byteArrayOf(1))),
                        open = { ByteArrayInputStream(byteArrayOf(1)) },
                        isCancelled = { throw cancellation },
                        operationId = "123e4567-e89b-12d3-a456-426614174097",
                    )
                },
            )
            assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(PREPARED_IMAGE_SHARE_PREFIX) })
        }

    @Test
    fun preparedImageShareAllows64FreshPayloadsAndReclaimsOnlyAfter24Hours() =
        withShareRoot { root ->
            val output = savedImage(1, "content://provider/image/1", "image/jpeg", byteArrayOf(1))
            repeat(8) { index ->
                prepareImageShareCopies(
                    root,
                    listOf(output),
                    open = { ByteArrayInputStream(byteArrayOf(1)) },
                    operationId = "123e4567-e89b-12d3-a456-${(426614174000L + index).toString().padStart(12, '0')}",
                )
            }
            val ninth =
                prepareImageShareCopies(
                    root,
                    listOf(output),
                    open = { ByteArrayInputStream(byteArrayOf(1)) },
                    operationId = "123e4567-e89b-12d3-a456-426614174099",
                )
            assertTrue(ninth.directory.isDirectory)
            assertEquals(9, root.listFiles().orEmpty().count(File::isDirectory))
            (8..62).forEach { index ->
                prepareImageShareCopies(
                    root,
                    listOf(output),
                    open = { ByteArrayInputStream(byteArrayOf(1)) },
                    operationId = "123e4567-e89b-12d3-a456-${(426614174000L + index).toString().padStart(12, '0')}",
                )
            }
            assertThrows(IOException::class.java) {
                prepareImageShareCopies(
                    root,
                    listOf(output),
                    open = { ByteArrayInputStream(byteArrayOf(1)) },
                    operationId = "123e4567-e89b-12d3-a456-426614174098",
                )
            }
            assertEquals(64, root.listFiles().orEmpty().count(File::isDirectory))

            val expired = requireNotNull(root.listFiles()).first()
            val twentyFourHours = 24L * 60L * 60L * 1000L
            assertTrue(expired.setLastModified(System.currentTimeMillis() - twentyFourHours - 1L))
            val prepared =
                prepareImageShareCopies(
                    root,
                    listOf(output),
                    open = { ByteArrayInputStream(byteArrayOf(1)) },
                    operationId = "123e4567-e89b-12d3-a456-426614174098",
                )
            assertFalse(expired.exists())
            assertTrue(prepared.directory.isDirectory)
            assertEquals(64, root.listFiles().orEmpty().count(File::isDirectory))
        }

    @Test
    fun preparedImageShareFailsClosedOnMaliciousPrefixedEntry() =
        withShareRoot { root ->
            val malicious = File(root, "${PREPARED_IMAGE_SHARE_PREFIX}malicious")
            malicious.writeBytes(byteArrayOf(1))

            assertThrows(IOException::class.java) {
                prepareImageShareCopies(
                    root,
                    listOf(savedImage(1, "content://provider/image/1", "image/jpeg", byteArrayOf(1))),
                    open = { ByteArrayInputStream(byteArrayOf(1)) },
                    operationId = "123e4567-e89b-12d3-a456-426614174095",
                )
            }
            assertTrue(malicious.isFile)
        }

    @Test
    fun preparedImageShareRootEnforcesAggregateReservedBytesWithoutDeletingPayloads() =
        withShareRoot { root ->
            repeat(2) { index ->
                val directory =
                    File(
                        root,
                        "${PREPARED_IMAGE_SHARE_PREFIX}123e4567-e89b-12d3-a456-42661417409$index",
                    )
                assertTrue(directory.mkdir())
                File(directory, ".lease").writeText(MAX_PREPARED_IMAGE_SHARE_BYTES.toString())
            }

            assertThrows(IOException::class.java) {
                prepareImageShareCopies(
                    root,
                    listOf(savedImage(1, "content://provider/image/1", "image/jpeg", byteArrayOf(1))),
                    open = { ByteArrayInputStream(byteArrayOf(1)) },
                    operationId = "123e4567-e89b-12d3-a456-426614174095",
                )
            }
            assertEquals(2, root.listFiles().orEmpty().count(File::isDirectory))
        }

    @Test
    fun replacementMetadataUpdateRequiresTheExactCacheGeneration() {
        val current =
            OutputMetadata(
                entryId = "123e4567-e89b-12d3-a456-426614174000",
                cacheId = "Scan_exact_generation",
                createdAtEpochMs = 1L,
            )
        val updated = current.copy(version = OUTPUT_METADATA_VERSION)

        assertEquals(updated, exactReplacementMetadataUpdate(current, current, updated))
        assertThrows(IOException::class.java) {
            exactReplacementMetadataUpdate(
                current.copy(entryId = "00000000-0000-0000-0000-000000000002"),
                current,
                updated,
            )
        }
        assertThrows(IOException::class.java) {
            exactReplacementMetadataUpdate(
                current.copy(cacheId = "Scan_reused_cache_id"),
                current,
                updated,
            )
        }
    }

    @Test
    fun sourcePageFileNamesAreStableAndRejectInvalidInput() {
        val id = "Scan_2026-08-09_10-20-30"

        assertEquals("${id}_source_01.jpg", scanSourcePageFileName(id, 1))
        assertEquals("${id}_source_12.jpg", scanSourcePageFileName(id, 12))
        assertThrows(IllegalArgumentException::class.java) {
            scanSourcePageFileName(id, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            scanSourcePageFileName("", 1)
        }
    }

    @Test
    fun validFolderProducesRecentEntryAndOrderedCachedScan() = withShareRoot { root ->
        val id = "Scan_2026-08-09_10-20-30"
        val directory = createEntry(root, id, pageCount = 10, pdfBytes = byteArrayOf(1, 2, 3))
        assertTrue(directory.setLastModified(1_786_268_430_000L))

        val recent = listRecentScansInRoot(root).single()
        val cached = openCachedScanInRoot(root, id)!!

        assertEquals(id, recent.cacheId)
        assertEquals(id, recent.displayName)
        assertEquals(Instant.ofEpochMilli(1_786_268_430_000L), recent.createdAt)
        assertEquals(10, recent.pageCount)
        assertEquals(3L, recent.pdfBytes)
        assertEquals("${id}_01.jpg", recent.firstPage.name)
        assertEquals((1..10).map { scanPageFileName(id, it) }, cached.pages.map(File::getName))
        assertEquals(scanPdfFileName(id), cached.pdf.name)
        assertEquals(emptyList<File>(), cached.sourcePages)
        assertNull(recent.entryId)
        assertFalse(recent.hasSavedPdf)
        assertEquals(0, recent.savedImageCount)
        assertNull(cached.entryId)
    }

    @Test
    fun completeSourceSetProducesOrderedImmutableSourcePages() = withShareRoot { root ->
        val id = "Scan_with_sources"
        createEntry(root, id, pageCount = 3, sourcePageCount = 3)

        val cached = openCachedScanInRoot(root, id)!!

        assertEquals(
            (1..3).map { scanSourcePageFileName(id, it) },
            cached.sourcePages.map(File::getName),
        )
        assertEquals(ScanAppearance(), cached.appearance)
    }

    @Test
    fun sourceSetMustMatchEveryCanonicalPage() = withShareRoot { root ->
        createEntry(root, "Scan_source_gap", pageCount = 3, sourcePageCount = 3).apply {
            assertTrue(File(this, scanSourcePageFileName(name, 2)).delete())
        }
        createEntry(root, "Scan_source_short", pageCount = 3, sourcePageCount = 2)
        createEntry(root, "Scan_source_extra", pageCount = 2, sourcePageCount = 3)

        assertEquals(emptyList<RecentScan>(), listRecentScansInRoot(root))
    }

    @Test
    fun malformedEmptyAndDuplicateSourceLookalikesAreRejected() = withShareRoot { root ->
        val malformed = "Scan_source_malformed"
        createEntry(root, malformed).apply {
            File(this, "${malformed}_source_1.jpg").writeBytes(byteArrayOf(1))
        }
        val empty = "Scan_source_empty"
        createEntry(root, empty).apply {
            File(this, scanSourcePageFileName(empty, 1)).writeBytes(byteArrayOf())
        }
        val duplicate = "Scan_source_duplicate"
        createEntry(root, duplicate, sourcePageCount = 1).apply {
            File(this, "${duplicate}_source_001.jpg").writeBytes(byteArrayOf(1))
        }

        assertEquals(emptyList<RecentScan>(), listRecentScansInRoot(root))
    }

    @Test
    fun deletingSourceBackedEntryRemovesItsWholeCache() = withShareRoot { root ->
        val id = "Scan_source_delete"
        val directory = createEntry(root, id, pageCount = 2, sourcePageCount = 2)

        assertTrue(deleteRecentScanInRoot(root, id))
        assertFalse(directory.exists())
    }

    @Test
    fun validMetadataControlsRecentTimeAndExposesOnlyOutputKindKnowledge() =
        withShareRoot { root ->
            val id = "Scan_with_outputs"
            val directory = createEntry(root, id, pageCount = 2)
            assertTrue(directory.setLastModified(999L))
            val entryId = "123e4567-e89b-12d3-a456-426614174000"
            initializeOutputMetadata(directory, id, 2, 100L, entryId)
            rewriteOutputMetadata(directory, id, entryId, 2) {
                it.copy(
                    pdf =
                        PdfOutputRef(
                            "content://media/downloads/1",
                            null,
                            byteLength = 1L,
                            sha256 = "00".repeat(32),
                        ),
                    images =
                        listOf(
                            ImageOutputRef(
                                1,
                                "content://media/images/1",
                                byteLength = 1L,
                                sha256 = "11".repeat(32),
                            ),
                        ),
                )
            }

            val recent = listRecentScansInRoot(root).single()
            val cached = openCachedScanInRoot(root, id)!!

            assertEquals(Instant.ofEpochMilli(100L), recent.createdAt)
            assertEquals(entryId, recent.entryId)
            assertTrue(recent.hasSavedPdf)
            assertEquals(1, recent.savedImageCount)
            assertEquals(entryId, cached.entryId)
        }

    @Test
    fun pendingMediaOwnershipStaysHiddenUntilProviderPublicationCompletes() =
        withShareRoot { root ->
            val id = "Scan_pending_outputs"
            val directory = createEntry(root, id)
            val entryId = "123e4567-e89b-12d3-a456-426614174000"
            initializeOutputMetadata(directory, id, 1, 100L, entryId)
            rewriteOutputMetadata(directory, id, entryId, 1) {
                it.copy(
                    pdf =
                        PdfOutputRef(
                            uri = "content://media/external/downloads/1",
                            treeUri = null,
                            displayName = "scan.pdf",
                            mimeType = "application/pdf",
                            ownerPackageName = "com.majkeylab.scanit.internal",
                            byteLength = 1L,
                            sha256 = "00".repeat(32),
                            pending = true,
                        ),
                    images =
                        listOf(
                            ImageOutputRef(
                                page = 1,
                                uri = "content://media/external/images/media/1",
                                displayName = "scan.jpg",
                                mimeType = "image/jpeg",
                                ownerPackageName = "com.majkeylab.scanit.internal",
                                byteLength = 1L,
                                sha256 = "11".repeat(32),
                                pending = true,
                            ),
                        ),
                )
            }

            val recent = listRecentScansInRoot(root).single()

            assertFalse(recent.hasSavedPdf)
            assertEquals(0, recent.savedImageCount)

            rewriteOutputMetadata(directory, id, entryId, 1) { metadata ->
                metadata.copy(
                    pdf = metadata.pdf?.copy(pending = false),
                    images = metadata.images.map { it.copy(pending = false) },
                )
            }
            val published = listRecentScansInRoot(root).single()
            assertTrue(published.hasSavedPdf)
            assertEquals(1, published.savedImageCount)
        }

    @Test
    fun fixedMetadataTempCompanionDoesNotInvalidateCoreEntry() = withShareRoot { root ->
        val id = "Scan_with_temp"
        val directory = createEntry(root, id)
        File(directory, OUTPUT_METADATA_TEMP_FILE_NAME).writeText("interrupted rewrite")

        assertEquals(id, openCachedScanInRoot(root, id)?.baseName)
        assertEquals(listOf(id), listRecentScansInRoot(root).map(RecentScan::cacheId))
    }

    @Test
    fun corruptMetadataKeepsCoreEntryOpenWithUnknownOutputs() = withShareRoot { root ->
        val id = "Scan_corrupt_outputs"
        val directory = createEntry(root, id)
        File(directory, OUTPUT_METADATA_FILE_NAME).writeText("not json")

        val recent = listRecentScansInRoot(root).single()
        val cached = openCachedScanInRoot(root, id)!!

        assertNull(recent.entryId)
        assertFalse(recent.hasSavedPdf)
        assertEquals(0, recent.savedImageCount)
        assertNull(cached.entryId)
    }

    @Test
    fun everyInvalidMetadataVariantKeepsItsCoreEntryOpen() = withShareRoot { root ->
        val variants =
            listOf(
                "unknown" to
                    """{"version":99,"entryId":"123e4567-e89b-12d3-a456-426614174000","cacheId":"Scan_unknown","createdAtEpochMs":1,"images":[]}"""
                        .toByteArray(),
                "duplicate" to
                    """{"version":1,"entryId":"123e4567-e89b-12d3-a456-426614174000","cacheId":"Scan_duplicate","createdAtEpochMs":1,"images":[{"page":1,"uri":"content://media/images/1"},{"page":1,"uri":"content://media/images/2"}]}"""
                        .toByteArray(),
                "out_of_range" to
                    """{"version":1,"entryId":"123e4567-e89b-12d3-a456-426614174000","cacheId":"Scan_out_of_range","createdAtEpochMs":1,"images":[{"page":2,"uri":"content://media/images/2"}]}"""
                        .toByteArray(),
                "cache_mismatch" to
                    """{"version":1,"entryId":"123e4567-e89b-12d3-a456-426614174000","cacheId":"Scan_other","createdAtEpochMs":1,"images":[]}"""
                        .toByteArray(),
                "entry_mismatch" to
                    """{"version":1,"entryId":"not-a-uuid","cacheId":"Scan_entry_mismatch","createdAtEpochMs":1,"images":[]}"""
                        .toByteArray(),
                "oversize" to ByteArray(MAX_OUTPUT_METADATA_BYTES + 1),
            )
        variants.forEach { (suffix, bytes) ->
            val id = "Scan_$suffix"
            File(createEntry(root, id), OUTPUT_METADATA_FILE_NAME).writeBytes(bytes)
        }

        val listed = listRecentScansInRoot(root).associateBy(RecentScan::cacheId)

        assertEquals(variants.size, listed.size)
        variants.forEach { (suffix, _) ->
            val id = "Scan_$suffix"
            assertNull(listed.getValue(id).entryId)
            assertNull(openCachedScanInRoot(root, id)?.entryId)
        }
    }

    @Test
    fun malformedFolderNamesAndLookalikeFilesAreSkipped() = withShareRoot { root ->
        val wrongPdf = "Scan_wrong_pdf"
        File(root, wrongPdf).apply {
            assertTrue(mkdir())
            File(this, "other.pdf").writeBytes(byteArrayOf(1))
            File(this, scanPageFileName(wrongPdf, 1)).writeBytes(byteArrayOf(1))
        }
        val duplicatePage = "Scan_duplicate_page"
        createEntry(root, duplicatePage).apply {
            File(this, "${duplicatePage}_1.jpg").writeBytes(byteArrayOf(1))
        }
        val extraFile = "Scan_extra_file"
        createEntry(root, extraFile).apply {
            File(this, "notes.txt").writeText("unexpected")
        }

        assertEquals(emptyList<RecentScan>(), listRecentScansInRoot(root))
    }

    @Test
    fun missingOrEmptyPdfAndPageGapsAreSkipped() = withShareRoot { root ->
        val missingPdf = "Scan_missing_pdf"
        File(root, missingPdf).apply {
            assertTrue(mkdir())
            File(this, scanPageFileName(missingPdf, 1)).writeBytes(byteArrayOf(1))
        }
        val emptyPdf = "Scan_empty_pdf"
        createEntry(root, emptyPdf).apply {
            File(this, scanPdfFileName(emptyPdf)).writeBytes(byteArrayOf())
        }
        val gap = "Scan_page_gap"
        createEntry(root, gap, pageCount = 3).apply {
            assertTrue(File(this, scanPageFileName(gap, 2)).delete())
        }

        assertEquals(emptyList<RecentScan>(), listRecentScansInRoot(root))
    }

    @Test
    fun traversalAndNonChildIdsAreRejectedWithoutTouchingOutsideFiles() = withShareRoot { root ->
        val outside = File(root.parentFile, "outside-scan").apply {
            mkdirs()
            File(this, "proof.txt").writeText("keep")
        }
        try {
            listOf("", ".", "..", "../outside-scan", "..\\outside-scan", outside.absolutePath)
                .forEach { cacheId ->
                    assertNull(openCachedScanInRoot(root, cacheId))
                    assertFalse(deleteRecentScanInRoot(root, cacheId))
                }
            assertTrue(File(outside, "proof.txt").isFile)
        } finally {
            assertTrue(outside.deleteRecursively())
        }
    }

    @Test
    fun entriesAreNewestFirstWithAscendingNameTieBreak() = withShareRoot { root ->
        val older = createEntry(root, "Scan_c").apply { assertTrue(setLastModified(100L)) }
        createEntry(root, "Scan_b").apply { assertTrue(setLastModified(200L)) }
        createEntry(root, "Scan_a").apply { assertTrue(setLastModified(200L)) }

        assertEquals(
            listOf("Scan_a", "Scan_b", older.name),
            listRecentScansInRoot(root).map(RecentScan::cacheId),
        )
    }

    @Test
    fun derivedIdsRemainCollisionSafe() {
        assertEquals(
            "Scan_2026_Signed_3",
            nextDerivedCacheId(
                sourceCacheId = "Scan_2026",
                suffix = "Signed",
                existingCacheIds = setOf("Scan_2026_Signed", "Scan_2026_Signed_2"),
            ),
        )
        assertEquals(
            "Scan_2026_Signed",
            nextDerivedCacheId("Scan_2026", "Signed", emptySet()),
        )
    }

    @Test
    fun pruningKeepsProtectedEntryAndNewestUnprotectedEntries() = withShareRoot { root ->
        (1..10).forEach { index ->
            createManagedEntry(root, "Scan_$index", index.toLong())
        }

        val visible =
            listRecentScansInRoot(
                root,
                protectedCacheIds = setOf("Scan_1"),
                maxEntries = 8,
            )

        assertEquals(
            listOf("Scan_10", "Scan_9", "Scan_8", "Scan_7", "Scan_6", "Scan_5", "Scan_4", "Scan_1"),
            visible.map(RecentScan::cacheId),
        )
        assertFalse(File(root, "Scan_2").exists())
        assertFalse(File(root, "Scan_3").exists())
        assertTrue(File(root, "Scan_1").isDirectory)
    }

    @Test
    fun pruningRefusesCapacitySmallerThanProtectedEntries() = withShareRoot { root ->
        (1..3).forEach { index -> createEntry(root, "Scan_$index") }

        assertThrows(IOException::class.java) {
            listRecentScansInRoot(
                root,
                protectedCacheIds = setOf("Scan_1", "Scan_2", "Scan_3"),
                maxEntries = 2,
            )
        }
        assertTrue((1..3).all { File(root, "Scan_$it").isDirectory })
    }

    @Test
    fun validPendingEntryIsPublishedWithoutPartialVisibility() = withShareRoot { root ->
        val finalId = "Scan_published"
        val pending = createEntry(root, ".pending-test", fileBaseName = finalId)
        val finalDirectory = File(root, finalId)

        val cached = RecentScanCache(root).publish(pending, finalDirectory)

        assertFalse(pending.exists())
        assertTrue(finalDirectory.isDirectory)
        assertEquals(finalId, cached.baseName)
        assertTrue(cached.entryId != null)
        assertTrue(File(finalDirectory, OUTPUT_METADATA_FILE_NAME).isFile)
        assertEquals(listOf(finalId), listRecentScansInRoot(root).map(RecentScan::cacheId))
    }

    @Test
    fun provisionalPublishAtCapacityCanBeRolledBackWithoutPruningHistory() =
        withShareRoot { root ->
            val oldIds =
                (1..8).map { index ->
                    "Scan_old_$index".also { id ->
                        createEntry(root, id).apply { assertTrue(setLastModified(index.toLong())) }
                    }
                }
            val finalId = "Scan_candidate"
            val pending = createEntry(root, ".pending-candidate", fileBaseName = finalId)
            val cache = RecentScanCache(root)

            val candidate = cache.publishProvisional(pending, File(root, finalId))

            assertEquals(oldIds.toSet(), cache.list(maxEntries = 8).map(RecentScan::cacheId).toSet())
            assertEquals(finalId, cache.open(finalId)?.baseName)
            assertTrue(cache.delete(candidate.baseName))
            assertEquals(oldIds.toSet(), cache.list(maxEntries = 8).map(RecentScan::cacheId).toSet())
            assertEquals(oldIds.toSet(), root.listFiles()!!.map(File::getName).toSet())
        }

    @Test
    fun provisionalDeleteRejectsAnotherEntryGeneration() =
        withShareRoot { root ->
            val finalId = "Scan_candidate"
            val pending = createEntry(root, ".pending-candidate", fileBaseName = finalId)
            val cache = RecentScanCache(root)
            val candidate = cache.publishProvisional(pending, File(root, finalId))

            assertFalse(
                cache.deleteProvisional(
                    finalId,
                    "123e4567-e89b-12d3-a456-426614174000",
                ),
            )
            assertTrue(File(root, finalId).isDirectory)
            assertTrue(cache.deleteProvisional(finalId, checkNotNull(candidate.entryId)))
            assertFalse(File(root, finalId).exists())
        }

    @Test
    fun exactDeleteRejectsAnotherEntryGeneration() =
        withShareRoot { root ->
            val cacheId = "Scan_candidate"
            val cache = RecentScanCache(root)
            val candidate =
                cache.publish(
                    createEntry(root, ".pending-candidate", fileBaseName = cacheId),
                    File(root, cacheId),
                )
            val entryId = checkNotNull(candidate.entryId)

            assertFalse(
                cache.deleteExact(
                    cacheId,
                    "123e4567-e89b-12d3-a456-426614174000",
                ),
            )
            assertTrue(File(root, cacheId).isDirectory)
            assertTrue(cache.deleteExact(cacheId, entryId))
            assertFalse(File(root, cacheId).exists())
        }

    @Test
    fun failedProvisionalPublishCleansPendingWithoutTouchingExistingFinalEntry() =
        withShareRoot { root ->
            val finalId = "Scan_existing"
            val finalDirectory = createEntry(root, finalId)
            val pending = createEntry(root, ".pending-candidate", fileBaseName = finalId)

            assertThrows(IOException::class.java) {
                RecentScanCache(root).publishProvisional(pending, finalDirectory)
            }

            assertFalse(pending.exists())
            assertTrue(finalDirectory.isDirectory)
            assertEquals(listOf(finalId), listRecentScansInRoot(root).map(RecentScan::cacheId))
        }

    @Test
    fun activationRetiresOnlyRequestedRevisionAfterExplicitActivation() =
        withShareRoot { root ->
            val lineageId = "Scan_lineage"
            val oldIds =
                (1..8).map { index ->
                    "Scan_old_$index".also { id ->
                        createManagedEntry(root, id, index.toLong()).apply {
                            if (index == 1) {
                                writeLegacyAppearanceMetadata(this, lineageId)
                            }
                        }
                    }
                }
            val finalId = "Scan_candidate"
            val pending =
                createEntry(root, ".pending-candidate", fileBaseName = finalId).apply {
                    writeLegacyAppearanceMetadata(this, lineageId)
                }
            val cache = RecentScanCache(root)
            cache.publishProvisional(pending, File(root, finalId))
            assertEquals(oldIds.toSet(), cache.list(maxEntries = 8).map(RecentScan::cacheId).toSet())

            val activated =
                cache.activateProvisional(
                    candidateCacheId = finalId,
                    retireCacheId = oldIds.first(),
                    maxEntries = 8,
                )

            assertEquals(finalId, activated.baseName)
            assertEquals(
                (oldIds.drop(1) + finalId).toSet(),
                cache.list(maxEntries = 8).map(RecentScan::cacheId).toSet(),
            )
            assertFalse(File(root, oldIds.first()).exists())
        }

    @Test
    fun activationRejectsRetiringAnUnrelatedLineage() =
        withShareRoot { root ->
            val oldId = "Scan_unrelated"
            createEntry(root, oldId)
            val finalId = "Scan_candidate"
            val pending = createEntry(root, ".pending-candidate", fileBaseName = finalId)
            val cache = RecentScanCache(root)
            cache.publishProvisional(pending, File(root, finalId))

            assertThrows(IOException::class.java) {
                cache.activateProvisional(
                    candidateCacheId = finalId,
                    retireCacheId = oldId,
                )
            }

            assertTrue(cache.isProvisional(finalId))
            assertTrue(File(root, oldId).isDirectory)
            assertEquals(listOf(oldId), cache.list().map(RecentScan::cacheId))
        }

    @Test
    fun failedActivationKeepsCandidateProvisionalAndRestoresEveryPriorEntry() =
        withShareRoot { root ->
            val lineageId = "Scan_lineage"
            val oldIds =
                (1..3).map { index ->
                    "Scan_old_$index".also { id ->
                        createEntry(root, id).apply {
                            assertTrue(setLastModified(index.toLong()))
                            if (index == 1) {
                                writeLegacyAppearanceMetadata(this, lineageId)
                            }
                        }
                    }
                }
            val finalId = "Scan_candidate"
            val pending =
                createEntry(root, ".pending-candidate", fileBaseName = finalId).apply {
                    writeLegacyAppearanceMetadata(this, lineageId)
                }
            var failActivationMove = true
            val cache =
                RecentScanCache(
                    root,
                    moveEntry = { source, target ->
                        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
                        if (
                            failActivationMove &&
                                target.parent.fileName.toString().startsWith(".pending-recovery-")
                        ) {
                            failActivationMove = false
                            throw IOException("Forced activation move failure")
                        }
                    },
                )
            cache.publishProvisional(pending, File(root, finalId))

            assertThrows(IOException::class.java) {
                cache.activateProvisional(
                    candidateCacheId = finalId,
                    retireCacheId = oldIds.first(),
                    maxEntries = 3,
                )
            }

            assertTrue(File(root, finalId).isDirectory)
            assertTrue(cache.isProvisional(finalId))
            assertEquals(oldIds.toSet(), cache.list(maxEntries = 3).map(RecentScan::cacheId).toSet())
            assertEquals(
                oldIds.toSet() + finalId,
                root.listFiles()!!.map(File::getName).toSet(),
            )
        }

    @Test
    fun startupRecoveryKeepsProvisionalCheckpointWhenActivationMarkerWasNotWritten() =
        withShareRoot { root ->
            val oldId = "Scan_old"
            createEntry(root, oldId)
            val finalId = "Scan_candidate"
            val pending = createEntry(root, ".pending-candidate", fileBaseName = finalId)
            val cache = RecentScanCache(root)
            cache.publishProvisional(pending, File(root, finalId))
            assertTrue(File(root, ".pending-recovery-$finalId").mkdir())

            assertEquals(listOf(oldId), cache.list().map(RecentScan::cacheId))

            assertTrue(cache.isProvisional(finalId))
            assertEquals(finalId, cache.open(finalId)?.baseName)
            assertFalse(File(root, ".pending-recovery-$finalId").exists())
        }

    @Test
    fun protectedPageWriterUsesRenderedPixelsForFreshIdenticalSourceAndPagePairs() =
        withShareRoot { root ->
            val current = File(root, "current").apply { assertTrue(mkdir()) }
            val pages =
                (1..2).map { page ->
                    File(current, "page-$page.jpg").apply {
                        writeBytes(byteArrayOf(page.toByte(), 40))
                    }
                }
            val originals = pages.map(File::readBytes)
            val work = File(root, "work").apply { assertTrue(mkdir()) }
            val region = NormalizedRect(0.25f, 0.25f, 0.5f, 0.5f)
            val calls = mutableListOf<Pair<String, Int>>()

            val protected =
                writeRedactedPages(
                    renderedPages = pages,
                    workDirectory = work,
                    derivedBaseName = "Scan_protected",
                    regionsByPage = mapOf(0 to listOf(region)),
                    isCancelled = { false },
                ) { source, destination, regions, isCancelled ->
                    assertFalse(isCancelled())
                    calls += source.name to regions.size
                    destination.writeBytes(
                        source.readBytes().map { byte -> (byte + 10).toByte() }.toByteArray(),
                    )
                    JpegDimensions(80, 80)
                }

            assertEquals(listOf("page-1.jpg" to 1, "page-2.jpg" to 0), calls)
            assertEquals(
                listOf("Scan_protected_source_01.jpg", "Scan_protected_source_02.jpg"),
                protected.sourcePages.map(File::getName),
            )
            assertEquals(
                listOf("Scan_protected_01.jpg", "Scan_protected_02.jpg"),
                protected.renderedPages.map(File::getName),
            )
            protected.sourcePages.indices.forEach { index ->
                assertArrayEquals(
                    protected.sourcePages[index].readBytes(),
                    protected.renderedPages[index].readBytes(),
                )
                assertArrayEquals(originals[index], pages[index].readBytes())
                assertFalse(originals[index].contentEquals(protected.sourcePages[index].readBytes()))
            }
            assertEquals(listOf(JpegDimensions(80, 80), JpegDimensions(80, 80)), protected.dimensions)
        }

    @Test
    fun protectedPageWriterAcceptsStrokeOnlyRedactions() =
        withShareRoot { root ->
            val current = File(root, "current").apply { assertTrue(mkdir()) }
            val pages =
                (1..2).map { page ->
                    File(current, "page-$page.jpg").apply { writeBytes(byteArrayOf(page.toByte())) }
                }
            val work = File(root, "work").apply { assertTrue(mkdir()) }
            val stroke =
                RedactionStroke(
                    listOf(MarkPoint(0.1f, 0.2f), MarkPoint(0.8f, 0.2f)),
                    0.04f,
                )
            val calls = mutableListOf<Pair<Int, Int>>()

            writeRedactedPages(
                renderedPages = pages,
                workDirectory = work,
                derivedBaseName = "Scan_protected",
                regionsByPage = emptyMap(),
                strokesByPage = mapOf(1 to listOf(stroke)),
                isCancelled = { false },
            ) { source, destination, regions, strokes, isCancelled ->
                assertFalse(isCancelled())
                calls += regions.size to strokes.size
                destination.writeBytes(source.readBytes() + 10)
                JpegDimensions(80, 80)
            }

            assertEquals(listOf(0 to 0, 0 to 1), calls)
        }

    @Test
    fun protectedPageCancellationRemovesEveryDerivedPixelFileAndKeepsOriginals() =
        withShareRoot { root ->
            val current = File(root, "current").apply { assertTrue(mkdir()) }
            val pages =
                (1..2).map { page ->
                    File(current, "page-$page.jpg").apply { writeBytes(byteArrayOf(page.toByte())) }
                }
            val originals = pages.map(File::readBytes)
            val work = File(root, "work").apply { assertTrue(mkdir()) }
            var rendered = 0

            assertThrows(CancellationException::class.java) {
                writeRedactedPages(
                    renderedPages = pages,
                    workDirectory = work,
                    derivedBaseName = "Scan_cancelled",
                    regionsByPage = mapOf(0 to listOf(NormalizedRect(0f, 0f, 0.5f, 0.5f))),
                    isCancelled = { false },
                ) { _, destination, _, _ ->
                    destination.writeBytes(byteArrayOf(9))
                    if (++rendered == 2) throw CancellationException("cancelled")
                    JpegDimensions(80, 80)
                }
            }

            assertEquals(emptyList<File>(), work.listFiles().orEmpty().toList())
            pages.indices.forEach { index -> assertArrayEquals(originals[index], pages[index].readBytes()) }
        }

    @Test
    fun protectedPdfEmbedsOnlyProtectedJpegStreams() =
        withShareRoot { root ->
            File(root, "original.jpg").writeBytes(
                byteArrayOf(0xff.toByte(), 0xd8.toByte()) +
                    "ORIGINAL_SECRET_PIXELS".toByteArray() +
                    byteArrayOf(0xff.toByte(), 0xd9.toByte()),
            )
            val protectedPages =
                listOf("PROTECTED_PAGE_ONE", "PROTECTED_PAGE_TWO").mapIndexed { index, marker ->
                    File(root, "protected-$index.jpg").apply {
                        writeBytes(
                            byteArrayOf(0xff.toByte(), 0xd8.toByte()) +
                                marker.toByteArray() +
                                byteArrayOf(0xff.toByte(), 0xd9.toByte()),
                        )
                    }
                }
            val output = File(root, "protected.pdf")

            val result =
                buildProtectedScanPdf(
                    output,
                    protectedPages,
                    listOf(JpegDimensions(80, 80), JpegDimensions(80, 80)),
                    PdfSizeTarget.Original,
                    isCancelled = { false },
                )

            val pdf = output.readText(Charsets.ISO_8859_1)
            assertEquals(PdfEncoding.Jpeg, result.encoding)
            assertTrue(pdf.contains("PROTECTED_PAGE_ONE"))
            assertTrue(pdf.contains("PROTECTED_PAGE_TWO"))
            assertFalse(pdf.contains("ORIGINAL_SECRET_PIXELS"))
            assertEquals(2, "/Subtype /Image".toRegex().findAll(pdf).count())
        }

    @Test
    fun selectedWhiteboardAndLaterPdfReplacementUseOnlyFlattenedRenderedPixels() =
        withShareRoot { root ->
            val parentAppearance =
                ScanAppearanceSettings(
                    colorMode = ScanColorMode.Grayscale,
                    grayscaleIntensity = 73,
                    shadows = 41,
                )
            val requested = parentAppearance.copy(colorMode = ScanColorMode.Whiteboard)
            val stored =
                appearanceVariantStoredSettings(
                    current = parentAppearance,
                    requested = requested,
                    selectedPageIndex = 1,
                )
            assertEquals(googleScannerAppearanceSettings(), stored)

            fun page(name: String): File =
                File(root, "$name.jpg").apply {
                    outputStream().use { output ->
                        output.write(byteArrayOf(0xff.toByte(), 0xd8.toByte()))
                        output.write(name.toByteArray())
                        output.write(ByteArray(140_000) { 0x41 })
                        output.write(byteArrayOf(0xff.toByte(), 0xd9.toByte()))
                    }
                }

            val originalSources = listOf(page("ORIGINAL_SOURCE"), page("STALE_FILTER_OUTPUT"))
            val pages = listOf(page("CURRENT_RENDERED"), page("WHITEBOARD_RENDERED"))
            val dimensions = List(2) { JpegDimensions(4_000, 3_000) }
            val samples = mutableListOf<Pair<String, Int>>()
            val renderer: (File, File, Int) -> RenderedJpeg = { source, destination, sample ->
                samples += source.nameWithoutExtension to sample
                destination.outputStream().use { output ->
                    output.write(byteArrayOf(0xff.toByte(), 0xd8.toByte()))
                    output.write("${source.nameWithoutExtension}_S$sample".toByteArray())
                    output.write(ByteArray(1_000) { 0x42 })
                    output.write(byteArrayOf(0xff.toByte(), 0xd9.toByte()))
                }
                RenderedJpeg(2_000, 1_500, sample, destination.length())
            }

            val initialOutput = File(root, "selected-whiteboard.pdf")
            val initial =
                buildAppearanceVariantPdf(
                    output = initialOutput,
                    sourcePages = originalSources,
                    renderedPages = pages,
                    renderedDimensions = dimensions,
                    appearance = requested.selected(),
                    target = PdfSizeTarget.Kb200,
                    selectedPageIndex = 1,
                    isCancelled = { false },
                    renderFlattenedSampledPage = renderer,
                )
            val replacementOutput = File(root, "replacement.pdf")
            val replacement =
                buildReplacementScanPdf(
                    output = replacementOutput,
                    sourcePages = originalSources,
                    renderedPages = pages,
                    renderedDimensions = dimensions,
                    appearance = stored.selected(),
                    target = PdfSizeTarget.Kb200,
                    isCancelled = { false },
                    renderFlattenedSampledPage = renderer,
                )
            val initialPdf = initialOutput.readText(Charsets.ISO_8859_1)
            val replacementPdf = replacementOutput.readText(Charsets.ISO_8859_1)

            assertEquals(2, initial.sampleMultiplier)
            assertEquals(2, replacement.sampleMultiplier)
            assertEquals(
                listOf(
                    "CURRENT_RENDERED" to 2,
                    "WHITEBOARD_RENDERED" to 2,
                    "CURRENT_RENDERED" to 2,
                    "WHITEBOARD_RENDERED" to 2,
                ),
                samples,
            )
            listOf(initialPdf, replacementPdf).forEach { pdf ->
                assertTrue(pdf.contains("CURRENT_RENDERED_S2"))
                assertTrue(pdf.contains("WHITEBOARD_RENDERED_S2"))
                assertFalse(pdf.contains("ORIGINAL_SOURCE"))
                assertFalse(pdf.contains("STALE_FILTER_OUTPUT"))
            }
        }

    @Test
    fun protectedCheckpointActivationSurvivesInterruptionAndNeverDeletesItsParent() =
        withShareRoot { root ->
            val lineageId = "Scan_lineage"
            val parentId = "Scan_parent"
            val parentEntryId = "00000000-0000-0000-0000-000000000001"
            val parent =
                createEntry(root, parentId, sourcePageCount = 1).apply {
                    initializeOutputMetadata(
                        this,
                        parentId,
                        1,
                        100L,
                        parentEntryId,
                        PdfSizeTarget.Original,
                    )
                    writeLegacyAppearanceMetadata(this, lineageId)
                    assertTrue(setLastModified(100L))
                }
            (1..7).forEach { index -> createManagedEntry(root, "Scan_old_$index", index.toLong()) }
            val candidateId = "Scan_protected"
            val pending =
                createEntry(
                    root,
                    ".pending-protected",
                    sourcePageCount = 1,
                    fileBaseName = candidateId,
                ).apply {
                    writeScanAppearanceMetadata(
                        this,
                        ScanAppearanceSettings(),
                        PdfSizeTarget.Original,
                        lineageId,
                        parentId,
                        parentEntryId,
                        restoreSettingsOnActivation = false,
                    )
                    initializeOutputMetadata(
                        this,
                        candidateId,
                        1,
                        200L,
                        pdfSizeTarget = PdfSizeTarget.Original,
                    )
                    assertTrue(File(this, PRESERVE_PARENT_CACHE_MARKER).createNewFile())
                }
            RecentScanCache(root).publishProvisional(pending, File(root, candidateId))

            var interrupted = false
            assertThrows(IOException::class.java) {
                RecentScanCache(
                    root,
                    moveEntry = { source, target ->
                        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
                        if (!interrupted) {
                            interrupted = true
                            throw IOException("activation interrupted after move")
                        }
                    },
                ).activateCheckpointProvisional(candidateId, maxEntries = 8)
            }

            assertTrue(parent.isDirectory)
            assertTrue(RecentScanCache(root).isProvisional(candidateId))
            val activated = RecentScanCache(root).activateCheckpointProvisional(candidateId, maxEntries = 8)
            val outputs = checkNotNull(readOutputMetadata(File(root, candidateId), candidateId, 1))
            assertEquals(candidateId, activated.baseName)
            assertFalse(RecentScanCache(root).isProvisional(candidateId))
            assertTrue(parent.isDirectory)
            assertEquals(OUTPUT_METADATA_VERSION, outputs.version)
            assertNull(outputs.pdf)
            assertEquals(emptyList<ImageOutputRef>(), outputs.images)
            assertEquals(8, RecentScanCache(root).list(maxEntries = 8).size)
        }

    @Test
    fun protectedActivationKeepsParentDurableMetadataAndExternalBytesUnchanged() =
        withShareRoot { root ->
            val durable = File(root.parentFile, "protected-parent-output.pdf").apply {
                writeBytes("DURABLE_PARENT".toByteArray())
            }
            try {
                val lineageId = "Scan_lineage"
                val parentId = "Scan_parent"
                val parentEntryId = "00000000-0000-0000-0000-000000000001"
                val parent =
                    createEntry(root, parentId, sourcePageCount = 1).apply {
                        initializeOutputMetadata(
                            this,
                            parentId,
                            1,
                            100L,
                            parentEntryId,
                            PdfSizeTarget.Original,
                        )
                        rewriteOutputMetadata(this, parentId, parentEntryId, 1) {
                            it.copy(pdf = exactPdf("content://media/external/downloads/1"))
                        }
                        writeLegacyAppearanceMetadata(this, lineageId)
                    }
                val parentMetadata = File(parent, OUTPUT_METADATA_FILE_NAME).readBytes()
                val durableBytes = durable.readBytes()
                val candidateId = "Scan_protected"
                val pending =
                    createEntry(
                        root,
                        ".pending-protected",
                        sourcePageCount = 1,
                        fileBaseName = candidateId,
                    ).apply {
                        writeScanAppearanceMetadata(
                            this,
                            ScanAppearanceSettings(),
                            PdfSizeTarget.Original,
                            lineageId,
                            parentId,
                            parentEntryId,
                            restoreSettingsOnActivation = false,
                        )
                        initializeOutputMetadata(
                            this,
                            candidateId,
                            1,
                            200L,
                            pdfSizeTarget = PdfSizeTarget.Original,
                        )
                        assertTrue(File(this, PRESERVE_PARENT_CACHE_MARKER).createNewFile())
                    }
                RecentScanCache(root).publishProvisional(pending, File(root, candidateId))

                RecentScanCache(root).activateCheckpointProvisional(candidateId)

                assertTrue(parent.isDirectory)
                assertArrayEquals(parentMetadata, File(parent, OUTPUT_METADATA_FILE_NAME).readBytes())
                assertArrayEquals(durableBytes, durable.readBytes())
            } finally {
                assertTrue(durable.delete())
            }
        }

    @Test
    fun checkpointRecoveryRetiresTheOnlyVisibleRevisionWithTheSameLineage() =
        withShareRoot { root ->
            val lineageId = "Scan_lineage"
            val oldId = "Scan_old"
            val oldEntryId = "00000000-0000-0000-0000-000000000001"
            createEntry(root, oldId, sourcePageCount = 1).also { directory ->
                initializeOutputMetadata(directory, oldId, 1, 1L, oldEntryId)
                writeLegacyAppearanceMetadata(directory, lineageId)
            }
            val unrelatedId = "Scan_unrelated"
            createEntry(root, unrelatedId)
            val finalId = "Scan_candidate"
            val pending =
                createEntry(
                    root,
                    ".pending-candidate",
                    sourcePageCount = 1,
                    fileBaseName = finalId,
                ).also { directory ->
                    writeScanAppearanceMetadata(
                        directory,
                        ScanAppearanceSettings(),
                        lineageCacheId = lineageId,
                        parentCacheId = oldId,
                        parentEntryId = oldEntryId,
                    )
                }
            val cache = RecentScanCache(root)
            cache.publishProvisional(pending, File(root, finalId))
            assertTrue(cache.isProvisional(finalId))

            val recovered = cache.activateCheckpointProvisional(finalId)

            assertEquals(finalId, recovered.baseName)
            assertFalse(cache.isProvisional(finalId))
            assertEquals(
                setOf(finalId, unrelatedId),
                cache.list().map(RecentScan::cacheId).toSet(),
            )
            assertFalse(File(root, oldId).exists())
            assertEquals(finalId, cache.activateCheckpointProvisional(finalId).baseName)
        }

    @Test
    fun checkpointRecoveryActivatesAnInitialScanWithoutPruningBeforeAuthority() =
        withShareRoot { root ->
            val oldIds =
                (1..8).map { index ->
                    "Scan_old_$index".also { id ->
                        createManagedEntry(root, id, index.toLong())
                    }
                }
            val candidateId = "Scan_initial"
            val pending =
                createEntry(
                    root,
                    ".pending-initial",
                    sourcePageCount = 1,
                    fileBaseName = candidateId,
                ).apply {
                    writeScanAppearanceMetadata(
                        directory = this,
                        appearanceSettings = ScanAppearanceSettings(),
                        pdfSizeTarget = PdfSizeTarget.Original,
                        lineageCacheId = candidateId,
                    )
                }
            val cache = RecentScanCache(root)

            cache.publishProvisional(pending, File(root, candidateId))
            assertEquals(oldIds.toSet(), cache.list(maxEntries = 8).map(RecentScan::cacheId).toSet())

            val activated = cache.activateCheckpointProvisional(candidateId, maxEntries = 8)

            assertEquals(candidateId, activated.baseName)
            assertFalse(cache.isProvisional(candidateId))
            assertEquals(
                (oldIds.drop(1) + candidateId).toSet(),
                cache.list(maxEntries = 8).map(RecentScan::cacheId).toSet(),
            )
        }

    @Test
    fun activationPruneKeepsSidecarOwnedPendingMedia() = withShareRoot { root ->
        val ownedId = "Scan_pending_owned"
        val ownedEntryId = "123e4567-e89b-12d3-a456-426614174000"
        val ownedDirectory = createEntry(root, ownedId)
        initializeOutputMetadata(ownedDirectory, ownedId, 1, 1L, ownedEntryId)
        rewriteOutputMetadata(ownedDirectory, ownedId, ownedEntryId, 1) {
            it.copy(
                pdf =
                    PdfOutputRef(
                        uri = "content://media/external/downloads/1",
                        treeUri = null,
                        displayName = "scan.pdf",
                        mimeType = "application/pdf",
                        ownerPackageName = "com.majkeylab.scanit.internal",
                        byteLength = 1L,
                        sha256 = "00".repeat(32),
                        pending = true,
                    ),
            )
        }
        assertTrue(ownedDirectory.setLastModified(1L))
        val disposableId = "Scan_disposable"
        createManagedEntry(root, disposableId, 2L)
        val candidateId = "Scan_initial"
        val pending =
            createEntry(root, ".pending-initial", fileBaseName = candidateId).apply {
                writeScanAppearanceMetadata(
                    directory = this,
                    appearanceSettings = ScanAppearanceSettings(),
                    lineageCacheId = candidateId,
                )
            }
        val cache = RecentScanCache(root)
        cache.publishProvisional(pending, File(root, candidateId))

        cache.activateCheckpointProvisional(candidateId, maxEntries = 2)

        assertTrue(ownedDirectory.isDirectory)
        assertFalse(File(root, disposableId).exists())
        assertEquals(
            setOf(ownedId, candidateId),
            cache.list(maxEntries = 2).map(RecentScan::cacheId).toSet(),
        )
    }

    @Test
    fun checkpointRecoveryActivatesWhenThePriorLineageRevisionIsAlreadyGone() =
        withShareRoot { root ->
            val unrelatedId = "Scan_unrelated"
            createEntry(root, unrelatedId)
            val finalId = "Scan_candidate"
            val pending =
                createEntry(
                    root,
                    ".pending-candidate",
                    sourcePageCount = 1,
                    fileBaseName = finalId,
                ).apply {
                    writeScanAppearanceMetadata(
                        this,
                        ScanAppearanceSettings(),
                        lineageCacheId = "Scan_gone",
                        parentCacheId = "Scan_gone",
                        parentEntryId = "00000000-0000-0000-0000-000000000001",
                    )
                }
            val cache = RecentScanCache(root)
            cache.publishProvisional(pending, File(root, finalId))

            val recovered = cache.activateCheckpointProvisional(finalId)

            assertEquals(finalId, recovered.baseName)
            assertFalse(cache.isProvisional(finalId))
            assertEquals(
                setOf(finalId, unrelatedId),
                cache.list().map(RecentScan::cacheId).toSet(),
            )
        }

    @Test
    fun checkpointRecoveryRetiresOnlyExactUnsavedParentAndKeepsSavedHistory() =
        withShareRoot { root ->
            val lineageId = "Scan_lineage"
            val savedId = "Scan_saved"
            val savedEntryId = "00000000-0000-0000-0000-000000000001"
            createEntry(root, savedId, sourcePageCount = 1).apply {
                initializeOutputMetadata(this, savedId, 1, 1L, savedEntryId)
                rewriteOutputMetadata(this, savedId, savedEntryId, 1) {
                    it.copy(
                        images =
                            listOf(
                                ImageOutputRef(
                                    page = 1,
                                    uri = "content://media/external/images/media/1",
                                ),
                            ),
                    )
                }
                writeLegacyAppearanceMetadata(this, lineageId)
            }
            val parentId = "Scan_parent"
            val parentEntryId = "00000000-0000-0000-0000-000000000002"
            createEntry(root, parentId, sourcePageCount = 1).apply {
                initializeOutputMetadata(this, parentId, 1, 2L, parentEntryId)
                writeLegacyAppearanceMetadata(
                    this,
                    lineageId,
                    ScanAppearance(colorMode = ScanColorMode.Grayscale),
                )
            }
            val finalId = "Scan_candidate"
            val pending =
                createEntry(
                    root,
                    ".pending-candidate",
                    sourcePageCount = 1,
                    fileBaseName = finalId,
                ).apply {
                    writeScanAppearanceMetadata(
                        this,
                        ScanAppearanceSettings(colorMode = ScanColorMode.Color),
                        lineageCacheId = lineageId,
                        parentCacheId = parentId,
                        parentEntryId = parentEntryId,
                    )
                }
            val cache = RecentScanCache(root)
            cache.publishProvisional(pending, File(root, finalId))

            val activated = cache.activateCheckpointProvisional(finalId)

            assertEquals(finalId, activated.baseName)
            assertFalse(cache.isProvisional(finalId))
            assertFalse(File(root, parentId).exists())
            assertTrue(File(root, savedId).isDirectory)
            assertEquals(
                setOf(savedId, finalId),
                cache.list().map(RecentScan::cacheId).toSet(),
            )
        }

    @Test
    fun checkpointRecoveryPreservesParentWithAnyDurableRefOrStaleGeneration() =
        withShareRoot { root ->
            val lineageId = "Scan_lineage"
            val parentId = "Scan_parent"
            val parentEntryId = "00000000-0000-0000-0000-000000000001"
            createEntry(root, parentId, sourcePageCount = 1).apply {
                initializeOutputMetadata(this, parentId, 1, 1L, parentEntryId)
                rewriteOutputMetadata(this, parentId, parentEntryId, 1) {
                    it.copy(
                        pdf = PdfOutputRef("content://media/external/downloads/1", null),
                    )
                }
                writeLegacyAppearanceMetadata(this, lineageId)
            }
            val finalId = "Scan_candidate"
            val pending =
                createEntry(
                    root,
                    ".pending-candidate",
                    sourcePageCount = 1,
                    fileBaseName = finalId,
                ).apply {
                    writeScanAppearanceMetadata(
                        this,
                        ScanAppearanceSettings(),
                        lineageCacheId = lineageId,
                        parentCacheId = parentId,
                        parentEntryId = parentEntryId,
                    )
                }
            val cache = RecentScanCache(root)
            cache.publishProvisional(pending, File(root, finalId))

            assertEquals(finalId, cache.activateCheckpointProvisional(finalId).baseName)
            assertTrue(File(root, parentId).isDirectory)
            assertEquals(
                setOf(parentId, finalId),
                cache.list().map(RecentScan::cacheId).toSet(),
            )
        }

    @Test
    fun legacyV2ProvisionalCannotBecomeCheckpointAuthority() =
        withShareRoot { root ->
            val finalId = "Scan_candidate"
            val pending =
                createEntry(
                    root,
                    ".pending-candidate",
                    sourcePageCount = 1,
                    fileBaseName = finalId,
                ).apply {
                    File(this, SCAN_APPEARANCE_FILE_NAME).writeText(
                        "scanit-appearance-v2\nblack_white\n100\n50\noriginal\n$finalId\n",
                        Charsets.US_ASCII,
                    )
                }
            val cache = RecentScanCache(root)
            cache.publishProvisional(pending, File(root, finalId))

            assertThrows(IOException::class.java) {
                cache.activateCheckpointProvisional(finalId)
            }

            assertTrue(cache.isProvisional(finalId))
        }

    @Test
    fun provisionalReconciliationKeepsOnlyTheAuthoritativeCandidate() =
        withShareRoot { root ->
            val activeId = "Scan_active"
            createEntry(root, activeId)
            val authoritativeId = "Scan_authoritative"
            val orphanId = "Scan_orphan"
            val cache = RecentScanCache(root)
            cache.publishProvisional(
                createEntry(root, ".pending-authoritative", fileBaseName = authoritativeId),
                File(root, authoritativeId),
            )
            cache.publishProvisional(
                createEntry(root, ".pending-orphan", fileBaseName = orphanId),
                File(root, orphanId),
            )

            assertThrows(IOException::class.java) {
                cache.reconcileProvisionals(activeId)
            }
            assertTrue(File(root, authoritativeId).isDirectory)
            assertTrue(File(root, orphanId).isDirectory)

            cache.reconcileProvisionals(authoritativeId)

            assertTrue(cache.isProvisional(authoritativeId))
            assertFalse(File(root, orphanId).exists())
            assertEquals(listOf(activeId), cache.list().map(RecentScan::cacheId))

            cache.reconcileProvisionals(null)

            assertFalse(File(root, authoritativeId).exists())
            assertEquals(listOf(activeId), cache.list().map(RecentScan::cacheId))
        }

    @Test
    fun pruningNeverDeletesDurableOutputsOutsideTheCache() {
        val parent = Files.createTempDirectory("recent-scans-durable").toFile()
        try {
            val root = File(parent, "share").also { assertTrue(it.mkdir()) }
            val durableOutput = File(parent, "durable-output.pdf").apply {
                writeBytes(byteArrayOf(1, 2, 3))
            }
            createManagedEntry(root, "Scan_old", 1L)
            createManagedEntry(root, "Scan_new", 2L)

            listRecentScansInRoot(root, maxEntries = 1)

            assertTrue(durableOutput.isFile)
            assertEquals(3L, durableOutput.length())
        } finally {
            assertTrue(parent.deleteRecursively())
        }
    }

    @Test
    fun automaticPruneKeepsSidecarOwnedPendingMedia() = withShareRoot { root ->
        val pendingId = "Scan_pending_owned"
        val pendingEntryId = "123e4567-e89b-12d3-a456-426614174000"
        val pendingDirectory = createEntry(root, pendingId)
        initializeOutputMetadata(pendingDirectory, pendingId, 1, 1L, pendingEntryId)
        rewriteOutputMetadata(pendingDirectory, pendingId, pendingEntryId, 1) {
            it.copy(
                pdf =
                    PdfOutputRef(
                        uri = "content://media/external/downloads/1",
                        treeUri = null,
                        displayName = "scan.pdf",
                        mimeType = "application/pdf",
                        ownerPackageName = "com.majkeylab.scanit.internal",
                        byteLength = 1L,
                        sha256 = "00".repeat(32),
                        pending = true,
                    ),
            )
        }
        assertTrue(pendingDirectory.setLastModified(1L))
        createManagedEntry(root, "Scan_newer", 2L)

        val listed = listRecentScansInRoot(root, maxEntries = 1)

        assertEquals(listOf(pendingId), listed.map(RecentScan::cacheId))
        assertTrue(pendingDirectory.isDirectory)
    }

    @Test
    fun ninthPublishKeepsEveryUnresolvedDurableState() {
        val states =
            listOf(
                "staged_pdf" to DurableState.StagedPdf,
                "staged_images" to DurableState.StagedImages,
                "retired_pdf" to DurableState.RetiredPdf,
                "retired_images" to DurableState.RetiredImages,
                "pending_pdf" to DurableState.PendingPdf,
                "pending_images" to DurableState.PendingImages,
                "marker" to DurableState.Marker,
                "missing" to DurableState.Missing,
                "invalid" to DurableState.Invalid,
                "oversize" to DurableState.Oversize,
                "read_failure" to DurableState.ReadFailure,
            )
        states.forEachIndexed { stateIndex, (label, state) ->
            withShareRoot { root ->
                val protectedId = "Scan_protected_$label"
                val protected = createManagedEntry(root, protectedId, 1L)
                applyDurableState(protected, protectedId, state)
                assertTrue(protected.setLastModified(1L))
                (2..8).forEach { index ->
                    createManagedEntry(root, "Scan_clean_$index", index.toLong())
                }
                val candidateId = "Scan_candidate_$stateIndex"
                val pending = createEntry(root, ".pending-$stateIndex", fileBaseName = candidateId)
                val cache =
                    RecentScanCache(
                        root,
                        readOutputMetadata = failingMetadataReader(protectedId, state),
                    )

                cache.publish(pending, File(root, candidateId), maxEntries = 8)

                assertTrue("$label was pruned", protected.isDirectory)
                assertTrue(File(root, candidateId).isDirectory)
                assertEquals(
                    8,
                    root.listFiles()!!.count { it.isDirectory && !it.name.startsWith('.') },
                )
            }
        }
    }

    @Test
    fun checkpointActivationKeepsParentWithEveryUnresolvedDurableState() {
        val states =
            listOf(
                DurableState.StagedPdf,
                DurableState.StagedImages,
                DurableState.RetiredPdf,
                DurableState.RetiredImages,
                DurableState.PendingPdf,
                DurableState.PendingImages,
                DurableState.Marker,
                DurableState.Missing,
                DurableState.Invalid,
                DurableState.Oversize,
                DurableState.ReadFailure,
            )
        states.forEachIndexed { index, state ->
            withShareRoot { root ->
                val lineageId = "Scan_lineage_$index"
                val parentId = "Scan_parent_$index"
                val parentEntryId = "00000000-0000-0000-0000-${(index + 1).toString().padStart(12, '0')}"
                val parent =
                    createEntry(root, parentId, sourcePageCount = 1).apply {
                        initializeOutputMetadata(this, parentId, 1, 1L, parentEntryId)
                        writeLegacyAppearanceMetadata(this, lineageId)
                    }
                applyDurableState(parent, parentId, state)
                val candidateId = "Scan_candidate_$index"
                val pending =
                    createEntry(
                        root,
                        ".pending-candidate-$index",
                        sourcePageCount = 1,
                        fileBaseName = candidateId,
                    ).apply {
                        writeScanAppearanceMetadata(
                            this,
                            ScanAppearanceSettings(),
                            lineageCacheId = lineageId,
                            parentCacheId = parentId,
                            parentEntryId = parentEntryId,
                        )
                    }
                val cache =
                    RecentScanCache(
                        root,
                        readOutputMetadata = failingMetadataReader(parentId, state),
                    )
                cache.publishProvisional(pending, File(root, candidateId))

                val activated = cache.activateCheckpointProvisional(candidateId)

                assertEquals(candidateId, activated.baseName)
                assertFalse(cache.isProvisional(candidateId))
                assertTrue("$state parent was removed", parent.isDirectory)
            }
        }
    }

    @Test
    fun automaticPruneKeepsResolvedActiveRefPolicyUnchanged() = withShareRoot { root ->
        val resolvedId = "Scan_resolved"
        val resolved = createManagedEntry(root, resolvedId, 1L)
        rewriteOutputMetadata(
            resolved,
            resolvedId,
            checkNotNull(readOutputMetadata(resolved, resolvedId, 1)).entryId,
            1,
        ) {
            it.copy(pdf = exactPdf("content://media/external/downloads/1"))
        }
        assertTrue(resolved.setLastModified(1L))
        createManagedEntry(root, "Scan_newer", 2L)

        val listed = listRecentScansInRoot(root, maxEntries = 1)

        assertEquals(listOf("Scan_newer"), listed.map(RecentScan::cacheId))
        assertFalse(resolved.exists())
    }

    @Test
    fun pruneFailureRestoresOldHistoryAndRollsBackPublishedEntry() = withShareRoot { root ->
        val oldIds = (1..3).map { index ->
            "Scan_old_$index".also { id ->
                createManagedEntry(root, id, index.toLong())
            }
        }
        val finalId = "Scan_new"
        val pending = createEntry(root, ".pending-new", fileBaseName = finalId)
        val finalDirectory = File(root, finalId)
        var moveCount = 0
        val cache =
            RecentScanCache(
                root,
                moveEntry = { source, target ->
                    moveCount++
                    if (moveCount == 3) throw IOException("Forced prune staging failure")
                    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
                },
            )

        assertThrows(IOException::class.java) {
            cache.publish(
                pending,
                finalDirectory,
                maxEntries = 2,
            )
        }

        assertEquals(4, moveCount)
        assertFalse(finalDirectory.exists())
        assertFalse(pending.exists())
        assertEquals(oldIds.toSet(), root.listFiles()!!.map(File::getName).toSet())
        assertEquals(oldIds.toSet(), cache.list(maxEntries = 3).map(RecentScan::cacheId).toSet())
    }

    @Test
    fun impossiblePublishCapacityRemovesPendingAndKeepsProtectedHistory() = withShareRoot { root ->
        val oldIds = setOf("Scan_protected_1", "Scan_protected_2")
        oldIds.forEach { createEntry(root, it) }
        val finalId = "Scan_new"
        val pending = createEntry(root, ".pending-capacity", fileBaseName = finalId)
        val finalDirectory = File(root, finalId)
        val cache = RecentScanCache(root)

        assertThrows(IOException::class.java) {
            cache.publish(
                pending,
                finalDirectory,
                protectedCacheIds = oldIds,
                maxEntries = 2,
            )
        }

        assertFalse(pending.exists())
        assertFalse(finalDirectory.exists())
        assertEquals(oldIds, cache.list(maxEntries = 2).map(RecentScan::cacheId).toSet())
    }

    @Test
    fun failedInitialAtomicMoveKeepsOldHistoryAndCleansPending() = withShareRoot { root ->
        val oldIds = setOf("Scan_old_1", "Scan_old_2")
        oldIds.forEach { createEntry(root, it) }
        val finalId = "Scan_new"
        val pending = createEntry(root, ".pending-initial-failure", fileBaseName = finalId)
        val finalDirectory = File(root, finalId)
        val cache =
            RecentScanCache(
                root,
                moveEntry = { _, _ -> throw IOException("Forced initial move failure") },
            )

        assertThrows(IOException::class.java) {
            cache.publish(pending, finalDirectory, maxEntries = 2)
        }

        assertFalse(pending.exists())
        assertFalse(finalDirectory.exists())
        assertEquals(oldIds, RecentScanCache(root).list(maxEntries = 2).map(RecentScan::cacheId).toSet())
        assertEquals(oldIds, root.listFiles()!!.map(File::getName).toSet())
    }

    @Test
    fun moverThatMovesThenThrowsDoesNotExposeNewOrLoseOldHistory() = withShareRoot { root ->
        val oldIds = setOf("Scan_old_1", "Scan_old_2")
        oldIds.forEach { createEntry(root, it) }
        val finalId = "Scan_ambiguous"
        val pending = createEntry(root, ".pending-ambiguous", fileBaseName = finalId)
        val finalDirectory = File(root, finalId)
        val cache =
            RecentScanCache(
                root,
                moveEntry = { source, target ->
                    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
                    throw IOException("Forced ambiguous move result")
                },
            )

        assertThrows(IOException::class.java) {
            cache.publish(pending, finalDirectory, maxEntries = 2)
        }

        assertFalse(pending.exists())
        assertFalse(finalDirectory.exists())
        assertEquals(oldIds, RecentScanCache(root).list(maxEntries = 2).map(RecentScan::cacheId).toSet())
        assertEquals(oldIds, root.listFiles()!!.map(File::getName).toSet())
    }

    @Test
    fun failedRollbackRemainsRecoverableAndMaintenanceRestoresOldEntry() = withShareRoot { root ->
        val oldIds = (1..3).map { index ->
            "Scan_old_$index".also { id ->
                createManagedEntry(root, id, index.toLong())
            }
        }
        val finalId = "Scan_new"
        val pending = createEntry(root, ".pending-recovery", fileBaseName = finalId)
        val finalDirectory = File(root, finalId)
        var moveCount = 0
        val cache =
            RecentScanCache(
                root,
                moveEntry = { source, target ->
                    moveCount++
                    if (moveCount == 3 || moveCount == 4) {
                        throw IOException("Forced move failure $moveCount")
                    }
                    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
                },
            )

        val failure =
            assertThrows(IOException::class.java) {
                cache.publish(pending, finalDirectory, maxEntries = 2)
            }

        assertTrue(failure.message.orEmpty().contains("recovery", ignoreCase = true))
        assertFalse(pending.exists())
        assertFalse(finalDirectory.exists())
        assertTrue(root.listFiles()!!.any { it.name.startsWith(".pending-recovery-") })

        val recovered = RecentScanCache(root).list(maxEntries = 3)

        assertEquals(oldIds.toSet(), recovered.map(RecentScan::cacheId).toSet())
        assertFalse(root.listFiles()!!.any { it.name.startsWith(".pending-recovery-") })
    }

    @Test
    fun committedPruneTrashIsDeletedWithoutRestoringOldEntry() = withShareRoot { root ->
        val trash = File(root, ".pending-delete-test").apply { assertTrue(mkdir()) }
        createEntry(trash, "Scan_pruned")
        File(root, ".committed-prune-test").apply { assertTrue(createNewFile()) }

        assertEquals(emptyList<RecentScan>(), RecentScanCache(root).list())
        assertFalse(trash.exists())
        assertFalse(File(root, ".committed-prune-test").exists())
        assertFalse(File(root, "Scan_pruned").exists())
    }

    @Test
    fun listWaitsForAtomicPublishAndNeverReturnsPendingEntry() = withShareRoot { root ->
        val finalId = "Scan_coordinated"
        val pending = createEntry(root, ".pending-coordinated", fileBaseName = finalId)
        val finalDirectory = File(root, finalId)
        val moveStarted = CountDownLatch(1)
        val allowMove = CountDownLatch(1)
        val listStarted = CountDownLatch(1)
        val cache =
            RecentScanCache(
                root,
                moveEntry = { source, target ->
                    moveStarted.countDown()
                    assertTrue(allowMove.await(5, TimeUnit.SECONDS))
                    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
                },
            )
        val executor = Executors.newFixedThreadPool(2)
        try {
            val publish =
                executor.submit<CachedScan> {
                    cache.publish(
                        pending,
                        finalDirectory,
                    )
                }
            assertTrue(moveStarted.await(5, TimeUnit.SECONDS))
            assertTrue(pending.isDirectory)
            val list =
                executor.submit<List<RecentScan>> {
                    listStarted.countDown()
                    cache.list()
                }
            assertTrue(listStarted.await(5, TimeUnit.SECONDS))
            assertFalse(list.isDone)

            allowMove.countDown()

            assertEquals(finalId, publish.get(5, TimeUnit.SECONDS).baseName)
            assertEquals(listOf(finalId), list.get(5, TimeUnit.SECONDS).map(RecentScan::cacheId))
            assertFalse(pending.exists())
        } finally {
            allowMove.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun invalidPendingEntryNeverBecomesVisibleAndStalePendingIsRemoved() = withShareRoot { root ->
        val finalId = "Scan_invalid"
        val pending = File(root, ".pending-invalid").apply {
            assertTrue(mkdir())
            File(this, scanPdfFileName(finalId)).writeBytes(byteArrayOf(1))
        }
        val finalDirectory = File(root, finalId)

        assertThrows(IOException::class.java) {
            RecentScanCache(root).publish(pending, finalDirectory)
        }
        assertFalse(finalDirectory.exists())
        listRecentScansInRoot(root)
        assertFalse(pending.exists())
    }

    @Test
    fun deleteOnlyRemovesValidatedCompleteEntry() = withShareRoot { root ->
        val validId = "Scan_valid"
        createEntry(root, validId)
        val incompleteId = "Scan_incomplete"
        File(root, incompleteId).apply {
            assertTrue(mkdir())
            File(this, scanPdfFileName(incompleteId)).writeBytes(byteArrayOf(1))
        }

        assertFalse(deleteRecentScanInRoot(root, incompleteId))
        assertTrue(File(root, incompleteId).isDirectory)
        assertTrue(deleteRecentScanInRoot(root, validId))
        assertFalse(File(root, validId).exists())
    }

    @Test
    fun cacheRemovalRenamesFirstAndRetriesAnUndeletedPendingDirectory() =
        withShareRoot { root ->
            val id = "Scan_crash_safe_remove"
            val live = createEntry(root, id)

            assertFalse(
                deleteRecentScanInRoot(
                    root,
                    id,
                    deleteTree = { false },
                ),
            )
            assertFalse(live.exists())
            val pending = root.listFiles()!!.single { it.name.startsWith(".pending-remove-") }
            assertTrue(pending.isDirectory)

            assertFalse(recoverPendingRecentRemovalsInRoot(root, deleteTree = { false }))
            assertTrue(pending.exists())
            assertTrue(recoverPendingRecentRemovalsInRoot(root, deleteTree = { it.deleteRecursively() }))
            assertFalse(pending.exists())
        }

    @Test
    fun failedCacheRenameLeavesTheLiveEntryUntouched() = withShareRoot { root ->
        val id = "Scan_rename_failure"
        val live = createEntry(root, id)

        assertFalse(
            deleteRecentScanInRoot(
                root,
                id,
                moveEntry = { _, _ -> throw IOException("move") },
            ),
        )
        assertTrue(live.isDirectory)
        assertFalse(root.listFiles()!!.any { it.name.startsWith(".pending-remove-") })
    }

    @Test
    fun movedThenThrownCacheRenameIsRecoveredWithoutReadingPartialContents() =
        withShareRoot { root ->
            val id = "Scan_ambiguous_remove"
            val live = createEntry(root, id)

            assertFalse(
                deleteRecentScanInRoot(
                    root,
                    id,
                    moveEntry = { source, target ->
                        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
                        File(target.toFile(), scanPdfFileName(id)).delete()
                        throw IOException("move result unknown")
                    },
                ),
            )
            assertFalse(live.exists())
            assertTrue(root.listFiles()!!.any { it.name.startsWith(".pending-remove-") })
            assertTrue(recoverPendingRecentRemovalsInRoot(root))
            assertFalse(root.listFiles()!!.any { it.name.startsWith(".pending-remove-") })
        }

    @Test
    fun legacyReferencesWithoutFingerprintsOfferOnlyRemoveRecent() = withShareRoot { root ->
        val id = "Scan_legacy_output"
        val directory = createEntry(root, id)
        val entryId = "123e4567-e89b-12d3-a456-426614174000"
        initializeOutputMetadata(directory, id, 1, 1L, entryId)
        rewriteOutputMetadata(directory, id, entryId, 1) {
            it.copy(pdf = PdfOutputRef("content://media/external/downloads/7", null))
        }

        val scan = listRecentScansInRoot(root).single()
        assertFalse(scan.hasSavedPdf)
        assertEquals(0, scan.savedImageCount)
    }

    @Test
    fun pendingRecentRemovalIsVisibleUntilStartupRecoveryDeletesOnlyItsCache() =
        withShareRoot { root ->
            val id = "Scan_pending_recent_removal"
            val directory = createEntry(root, id)
            val entryId = "123e4567-e89b-12d3-a456-426614174000"
            initializeOutputMetadata(directory, id, 1, 1L, entryId)
            rewriteOutputMetadata(directory, id, entryId, 1) {
                it.copy(
                    images = listOf(ImageOutputRef(1, "content://media/external/images/media/7")),
                    removeRecentPending = true,
                )
            }

            val pending = listRecentScansInRoot(root).single()
            assertTrue(pending.removeRecentPending)
            assertTrue(recoverPendingRecentRemovalsInRoot(root))
            assertFalse(directory.exists())
        }

    private fun createEntry(
        root: File,
        directoryName: String,
        pageCount: Int = 1,
        sourcePageCount: Int = 0,
        pdfBytes: ByteArray = byteArrayOf(1),
        fileBaseName: String = directoryName,
    ): File =
        File(root, directoryName).apply {
            assertTrue(mkdir())
            File(this, scanPdfFileName(fileBaseName)).writeBytes(pdfBytes)
            (pageCount downTo 1).forEach { page ->
                File(this, scanPageFileName(fileBaseName, page)).writeBytes(byteArrayOf(page.toByte()))
            }
            (sourcePageCount downTo 1).forEach { page ->
                File(this, scanSourcePageFileName(fileBaseName, page))
                    .writeBytes(byteArrayOf(page.toByte()))
            }
            if (sourcePageCount > 0) {
                writeScanAppearanceMetadata(
                    this,
                    ScanAppearanceSettings(),
                    lineageCacheId = fileBaseName,
                )
            }
        }

    private enum class DurableState {
        StagedPdf,
        StagedImages,
        RetiredPdf,
        RetiredImages,
        PendingPdf,
        PendingImages,
        Marker,
        Missing,
        Invalid,
        Oversize,
        ReadFailure,
    }

    private fun createManagedEntry(root: File, cacheId: String, createdAt: Long): File =
        createEntry(root, cacheId).apply {
            initializeOutputMetadata(this, cacheId, 1, createdAt)
            assertTrue(setLastModified(createdAt))
        }

    private fun applyDurableState(directory: File, cacheId: String, state: DurableState) {
        val metadata = readOutputMetadata(directory, cacheId, 1)
        when (state) {
            DurableState.StagedPdf ->
                rewriteOutputMetadata(directory, cacheId, checkNotNull(metadata).entryId, 1) {
                    it.copy(
                        stagedPdf = exactPdf("content://media/external/downloads/staged"),
                        version = OUTPUT_METADATA_VERSION,
                    )
                }
            DurableState.StagedImages ->
                rewriteOutputMetadata(directory, cacheId, checkNotNull(metadata).entryId, 1) {
                    it.copy(
                        stagedImages =
                            listOf(exactImage(1, "content://media/external/images/media/11", false)),
                        version = OUTPUT_METADATA_VERSION,
                    )
                }
            DurableState.RetiredPdf ->
                rewriteOutputMetadata(directory, cacheId, checkNotNull(metadata).entryId, 1) {
                    it.copy(
                        retiredPdf = exactPdf("content://media/external/downloads/retired"),
                        version = OUTPUT_METADATA_VERSION,
                    )
                }
            DurableState.RetiredImages ->
                rewriteOutputMetadata(directory, cacheId, checkNotNull(metadata).entryId, 1) {
                    it.copy(
                        retiredImages =
                            listOf(exactImage(1, "content://media/external/images/media/12", false)),
                        version = OUTPUT_METADATA_VERSION,
                    )
                }
            DurableState.PendingPdf ->
                rewriteOutputMetadata(directory, cacheId, checkNotNull(metadata).entryId, 1) {
                    it.copy(
                        pdf = exactPdf("content://media/external/downloads/pending", pending = true),
                        version = OUTPUT_METADATA_VERSION,
                    )
                }
            DurableState.PendingImages ->
                rewriteOutputMetadata(directory, cacheId, checkNotNull(metadata).entryId, 1) {
                    it.copy(
                        images =
                            listOf(exactImage(1, "content://media/external/images/media/13", true)),
                        version = OUTPUT_METADATA_VERSION,
                    )
                }
            DurableState.Marker ->
                writeProvisionalOutputCreate(
                    directory,
                    ProvisionalOutputCreate(
                        operationId = "123e4567-e89b-12d3-a456-426614174096",
                        cacheId = cacheId,
                        entryId = checkNotNull(metadata).entryId,
                        kind = ProvisionalOutputKind.Image,
                        page = 1,
                        provider = ProvisionalOutputProvider.MediaStore,
                        displayName = "page-01.jpg",
                        mimeType = "image/jpeg",
                        treeUri = null,
                        returnedUri = null,
                    ),
                    pageCount = 1,
                )
            DurableState.Missing -> assertTrue(File(directory, OUTPUT_METADATA_FILE_NAME).delete())
            DurableState.Invalid -> File(directory, OUTPUT_METADATA_FILE_NAME).writeText("{}")
            DurableState.Oversize ->
                File(directory, OUTPUT_METADATA_FILE_NAME)
                    .writeBytes(ByteArray(MAX_OUTPUT_METADATA_BYTES + 1) { 1 })
            DurableState.ReadFailure -> Unit
        }
    }

    private fun failingMetadataReader(
        cacheId: String,
        state: DurableState,
    ): (File, String, Int) -> OutputMetadataReadResult = { directory, actualCacheId, pageCount ->
        if (state == DurableState.ReadFailure && actualCacheId == cacheId) {
            OutputMetadataReadResult.Failed
        } else {
            readOutputMetadataResult(directory, actualCacheId, pageCount)
        }
    }

    private fun exactPdf(uri: String, pending: Boolean = false) =
        PdfOutputRef(
            uri = uri,
            treeUri = null,
            displayName = "scan.pdf",
            mimeType = "application/pdf",
            ownerPackageName = "com.majkeylab.scanit.internal",
            byteLength = 1L,
            sha256 = "00".repeat(32),
            pending = pending,
        )

    private fun writeLegacyAppearanceMetadata(
        directory: File,
        lineageCacheId: String,
        appearance: ScanAppearance = ScanAppearance(),
    ) {
        File(directory, SCAN_APPEARANCE_FILE_NAME).writeText(
            "scanit-appearance-v2\n" +
                "${appearance.colorMode.wireValue}\n" +
                "${appearance.intensity}\n" +
                "${appearance.shadows}\n" +
                "original\n" +
                "$lineageCacheId\n",
            Charsets.US_ASCII,
        )
    }

    private fun provisionalCreate(
        returnedUri: String? = null,
        operationId: String = "123e4567-e89b-12d3-a456-426614174096",
    ) =
        ProvisionalOutputCreate(
            operationId = operationId,
            cacheId = CACHE_ID,
            entryId = ENTRY_ID,
            kind = ProvisionalOutputKind.Image,
            page = 1,
            provider = ProvisionalOutputProvider.MediaStore,
            displayName = "page-01.jpg",
            mimeType = "image/jpeg",
            treeUri = null,
            returnedUri = returnedUri,
        )

    private fun metadata() =
        OutputMetadata(
            entryId = ENTRY_ID,
            cacheId = CACHE_ID,
            createdAtEpochMs = 1L,
        )

    private fun exactImage(page: Int, uri: String, pending: Boolean) =
        ImageOutputRef(
            page = page,
            uri = uri,
            displayName = "page-01.jpg",
            mimeType = "image/jpeg",
            ownerPackageName = "com.majkeylab.scanit.internal",
            byteLength = 1L,
            sha256 = "00".repeat(32),
            pending = pending,
            width = 1,
            height = 1,
            format = ImageExportFormat.Jpeg,
        )

    private fun savedImage(
        page: Int,
        uri: String,
        mimeType: String,
        bytes: ByteArray,
        declaredLength: Long = bytes.size.toLong(),
    ): PreparedImageSource {
        val fingerprint = readOutputFingerprint(ByteArrayInputStream(bytes), bytes.size.toLong())
        return PreparedImageSource(
            page = page,
            uri = uri,
            mimeType = mimeType,
            byteLength = declaredLength,
            sha256 = fingerprint.sha256,
        )
    }

    private fun withShareRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("recent-scans").toFile()
        try {
            block(root)
        } finally {
            assertTrue(root.deleteRecursively())
        }
    }

    private companion object {
        const val CACHE_ID = "Scan_marker"
        const val ENTRY_ID = "123e4567-e89b-12d3-a456-426614174000"
    }

}
