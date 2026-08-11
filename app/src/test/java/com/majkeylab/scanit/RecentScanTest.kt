package com.majkeylab.scanit

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentScanTest {
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
            createEntry(root, "Scan_$index").apply { assertTrue(setLastModified(index.toLong())) }
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
                        createEntry(root, id).apply { assertTrue(setLastModified(index.toLong())) }
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
        createEntry(root, disposableId).apply { assertTrue(setLastModified(2L)) }
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
    fun pruningNeverDeletesDurableOutputsOutsideTheCache() = withShareRoot { root ->
        val durableOutput = File(root.parentFile, "durable-output.pdf").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        try {
            createEntry(root, "Scan_old").apply { assertTrue(setLastModified(1L)) }
            createEntry(root, "Scan_new").apply { assertTrue(setLastModified(2L)) }

            listRecentScansInRoot(root, maxEntries = 1)

            assertTrue(durableOutput.isFile)
            assertEquals(3L, durableOutput.length())
        } finally {
            assertTrue(durableOutput.delete())
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
        createEntry(root, "Scan_newer").apply { assertTrue(setLastModified(2L)) }

        val listed = listRecentScansInRoot(root, maxEntries = 1)

        assertEquals(listOf(pendingId), listed.map(RecentScan::cacheId))
        assertTrue(pendingDirectory.isDirectory)
    }

    @Test
    fun pruneFailureRestoresOldHistoryAndRollsBackPublishedEntry() = withShareRoot { root ->
        val oldIds = (1..3).map { index ->
            "Scan_old_$index".also { id ->
                createEntry(root, id).apply { assertTrue(setLastModified(index.toLong())) }
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
                createEntry(root, id).apply { assertTrue(setLastModified(index.toLong())) }
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

    private fun withShareRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("recent-scans").toFile()
        try {
            block(root)
        } finally {
            assertTrue(root.deleteRecursively())
        }
    }

}
