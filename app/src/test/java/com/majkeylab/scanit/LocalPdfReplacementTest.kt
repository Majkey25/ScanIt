package com.majkeylab.scanit

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPdfReplacementTest {
    @Test
    fun startupDiscardsUnjournaledLocalStaging() =
        withCachedScan { root, directory, pdf, _ ->
            File(directory, LOCAL_PDF_REPLACEMENT_OLD_FILE_NAME).writeBytes(OLD_BYTES)
            File(directory, LOCAL_PDF_REPLACEMENT_NEW_FILE_NAME).writeBytes(NEW_BYTES)

            assertTrue(openCachedScanInRoot(root, CACHE_ID) != null)
            assertArrayEquals(OLD_BYTES, pdf.readBytes())
            assertLocalReplacementCleared(directory)
        }

    @Test
    fun startupRollsBackEveryUncommittedLocalPublishPhase() =
        listOf(0, 1, 2).forEach { crashPoint ->
            withCachedScan { root, directory, pdf, candidate ->
                val prepared = prepareLocalPdfReplacement(
                    directory = directory,
                    cacheId = CACHE_ID,
                    entryId = ENTRY_ID,
                    pageCount = 1,
                    cachedPdf = pdf,
                    candidatePdf = candidate,
                    oldTarget = PdfSizeTarget.Original,
                    newTarget = PdfSizeTarget.Mb5,
                )
                if (crashPoint == 2) {
                    publishLocalPdfReplacement(directory, prepared, pdf)
                } else if (crashPoint == 1) {
                    Files.move(
                        File(directory, LOCAL_PDF_REPLACEMENT_NEW_FILE_NAME).toPath(),
                        pdf.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }

                assertTrue(openCachedScanInRoot(root, CACHE_ID) != null)
                assertArrayEquals(OLD_BYTES, pdf.readBytes())
                assertLocalReplacementCleared(directory)
            }
        }

    @Test
    fun startupKeepsCommittedLocalPdfBeforeAndAfterFinalJournalPhase() =
        listOf(false, true).forEach { finalPhaseWritten ->
            withCachedScan { root, directory, pdf, candidate ->
                val prepared = prepareLocalPdfReplacement(
                    directory = directory,
                    cacheId = CACHE_ID,
                    entryId = ENTRY_ID,
                    pageCount = 1,
                    cachedPdf = pdf,
                    candidatePdf = candidate,
                    oldTarget = PdfSizeTarget.Original,
                    newTarget = PdfSizeTarget.Mb5,
                )
                val published = publishLocalPdfReplacement(directory, prepared, pdf)
                rewriteOutputMetadata(directory, CACHE_ID, ENTRY_ID, pageCount = 1) {
                    it.copy(pdfSizeTarget = PdfSizeTarget.Mb5, version = OUTPUT_METADATA_VERSION)
                }
                if (finalPhaseWritten) {
                    markLocalPdfReplacementOutputsCommitted(
                        directory,
                        published,
                        pageCount = 1,
                    )
                }

                assertTrue(openCachedScanInRoot(root, CACHE_ID) != null)
                assertArrayEquals(NEW_BYTES, pdf.readBytes())
                assertLocalReplacementCleared(directory)
            }
        }

    @Test
    fun failedRollbackRetainsExactBackupForNextStartupRetry() =
        withCachedScan { root, directory, pdf, candidate ->
            val prepared = prepareLocalPdfReplacement(
                directory = directory,
                cacheId = CACHE_ID,
                entryId = ENTRY_ID,
                pageCount = 1,
                cachedPdf = pdf,
                candidatePdf = candidate,
                oldTarget = PdfSizeTarget.Original,
                newTarget = PdfSizeTarget.Mb5,
            )
            publishLocalPdfReplacement(directory, prepared, pdf)

            assertThrows(IOException::class.java) {
                reconcileLocalPdfReplacement(
                    directory = directory,
                    cacheId = CACHE_ID,
                    pageCount = 1,
                    cachedPdf = pdf,
                    movePdf = { _: Path, _: Path -> throw IOException("injected rollback failure") },
                )
            }
            assertArrayEquals(NEW_BYTES, pdf.readBytes())
            assertTrue(File(directory, LOCAL_PDF_REPLACEMENT_FILE_NAME).isFile)
            assertArrayEquals(
                OLD_BYTES,
                File(directory, LOCAL_PDF_REPLACEMENT_OLD_FILE_NAME).readBytes(),
            )

            assertTrue(openCachedScanInRoot(root, CACHE_ID) != null)
            assertArrayEquals(OLD_BYTES, pdf.readBytes())
            assertLocalReplacementCleared(directory)
        }

    @Test
    fun cancellationBeforePrepareLeavesOldAuthorityAndNoJournal() =
        withCachedScan { _, directory, pdf, candidate ->
            assertThrows(CancellationException::class.java) {
                prepareLocalPdfReplacement(
                    directory = directory,
                    cacheId = CACHE_ID,
                    entryId = ENTRY_ID,
                    pageCount = 1,
                    cachedPdf = pdf,
                    candidatePdf = candidate,
                    oldTarget = PdfSizeTarget.Original,
                    newTarget = PdfSizeTarget.Mb5,
                    isCancelled = { true },
                )
            }

            assertArrayEquals(OLD_BYTES, pdf.readBytes())
            assertLocalReplacementCleared(directory)
        }

    @Test
    fun cancellationBeforeLocalPublishReconcilesPreparedJournalWithoutChangingPdf() =
        withCachedScan { root, directory, pdf, candidate ->
            val prepared = prepareLocalPdfReplacement(
                directory = directory,
                cacheId = CACHE_ID,
                entryId = ENTRY_ID,
                pageCount = 1,
                cachedPdf = pdf,
                candidatePdf = candidate,
                oldTarget = PdfSizeTarget.Original,
                newTarget = PdfSizeTarget.Mb5,
            )

            assertThrows(CancellationException::class.java) {
                publishLocalPdfReplacement(
                    directory,
                    prepared,
                    pdf,
                    isCancelled = { true },
                )
            }
            assertTrue(openCachedScanInRoot(root, CACHE_ID) != null)
            assertArrayEquals(OLD_BYTES, pdf.readBytes())
            assertLocalReplacementCleared(directory)
        }

    private fun assertLocalReplacementCleared(directory: File) {
        listOf(
            LOCAL_PDF_REPLACEMENT_FILE_NAME,
            LOCAL_PDF_REPLACEMENT_TEMP_FILE_NAME,
            LOCAL_PDF_REPLACEMENT_OLD_FILE_NAME,
            LOCAL_PDF_REPLACEMENT_NEW_FILE_NAME,
        ).forEach { name -> assertFalse(File(directory, name).exists()) }
    }

    private fun withCachedScan(block: (File, File, File, File) -> Unit) {
        val root = Files.createTempDirectory("local-pdf-replacement-root").toFile()
        val candidateRoot = Files.createTempDirectory("local-pdf-replacement-candidate").toFile()
        try {
            val directory = File(root, CACHE_ID).apply { assertTrue(mkdir()) }
            File(directory, scanPageFileName(CACHE_ID, 1)).writeBytes(byteArrayOf(1))
            val pdf = File(directory, scanPdfFileName(CACHE_ID)).apply { writeBytes(OLD_BYTES) }
            val candidate = File(candidateRoot, "candidate.pdf").apply { writeBytes(NEW_BYTES) }
            initializeOutputMetadata(
                directory = directory,
                cacheId = CACHE_ID,
                pageCount = 1,
                createdAtEpochMs = 1L,
                entryId = ENTRY_ID,
                pdfSizeTarget = PdfSizeTarget.Original,
            )

            block(root, directory, pdf, candidate)
        } finally {
            assertTrue(root.deleteRecursively())
            assertTrue(candidateRoot.deleteRecursively())
        }
    }

    private companion object {
        const val CACHE_ID = "Scan_2026-08-09_12-12-00"
        const val ENTRY_ID = "123e4567-e89b-12d3-a456-426614174000"
        val OLD_BYTES = "old-pdf".toByteArray()
        val NEW_BYTES = "new-pdf".toByteArray()
    }
}
