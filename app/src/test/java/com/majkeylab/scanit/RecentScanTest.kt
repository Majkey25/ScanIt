package com.majkeylab.scanit

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentScanTest {
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

        val cached = publishCacheEntryInRoot(root, pending, finalDirectory)

        assertFalse(pending.exists())
        assertTrue(finalDirectory.isDirectory)
        assertEquals(finalId, cached.baseName)
        assertEquals(listOf(finalId), listRecentScansInRoot(root).map(RecentScan::cacheId))
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
            publishCacheEntryInRoot(root, pending, finalDirectory)
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
    fun concurrentlyEvictedEntryIsAlreadyDeleted() = withShareRoot { root ->
        assertTrue(deleteRecentScanInRoot(root, "Scan_evicted"))
    }

    private fun createEntry(
        root: File,
        directoryName: String,
        pageCount: Int = 1,
        pdfBytes: ByteArray = byteArrayOf(1),
        fileBaseName: String = directoryName,
    ): File =
        File(root, directoryName).apply {
            assertTrue(mkdir())
            File(this, scanPdfFileName(fileBaseName)).writeBytes(pdfBytes)
            (pageCount downTo 1).forEach { page ->
                File(this, scanPageFileName(fileBaseName, page)).writeBytes(byteArrayOf(page.toByte()))
            }
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
