package com.majkeylab.scanit

import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanCacheBudgetTest {
    @Test
    fun closedFileMovesWaitForTheRecountAndKeepTheirBytesCharged() = withRoot { root ->
        val first = File(root, "first").apply { check(mkdir()) }
        val second = File(root, "second").apply { check(mkdir()) }
        val source = File(first, "page").apply { writeBytes(ByteArray(32)) }
        val target = File(second, "page")
        val budget = ScanCacheBudget(root, 32)
        val previousBudget = scanCacheBudget
        val executor = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        scanCacheBudget = budget
        try {
            val move = synchronized(budget) {
                val pending = executor.submit {
                    started.countDown()
                    publishStagedFileNoReplace(source.toPath(), target.toPath())
                }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                assertThrows(TimeoutException::class.java) { pending.get(100, TimeUnit.MILLISECONDS) }
                budget.open(File(root, "during-move")).use {
                    assertThrows(IOException::class.java) { it.write(1) }
                }
                assertTrue(source.exists())
                pending
            }
            move.get(5, TimeUnit.SECONDS)
            assertFalse(source.exists())
            assertEquals(32L, target.length())
            budget.open(File(root, "after-move")).use {
                assertThrows(IOException::class.java) { it.write(1) }
            }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            scanCacheBudget = previousBudget
        }
    }

    @Test
    fun concurrentWritersCannotBothSpendTheRemainingBytes() = withRoot { root ->
        val budget = ScanCacheBudget(root, 100)
        val first = budget.open(File(root, "first"))
        val second = budget.open(File(root, "second"))
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val writes = listOf(first, second).map { output ->
                executor.submit<Boolean> {
                    check(start.await(5, TimeUnit.SECONDS))
                    try {
                        output.write(ByteArray(60))
                        true
                    } catch (_: IOException) {
                        false
                    }
                }
            }
            start.countDown()
            assertEquals(1, writes.count { it.get(5, TimeUnit.SECONDS) })
            assertEquals(60L, cacheDirectoryBytes(root))
        } finally {
            start.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            first.close()
            second.close()
        }
    }

    @Test
    fun failedWriteKeepsItsCompletedPrefixChargedUntilDeletion() = withRoot { root ->
        val partial = File(root, "partial")
        val budget = ScanCacheBudget(root, 48)
        budget.open(partial).use { first ->
            first.write(ByteArray(32))
            assertThrows(IOException::class.java) { first.write(ByteArray(32)) }
            budget.open(File(root, "second")).use { second ->
                second.write(ByteArray(16), 0, 16)
                assertThrows(IOException::class.java) { second.write(1) }
            }
            assertEquals(48L, cacheDirectoryBytes(root))
        }
        assertTrue(partial.delete())
        budget.open(File(root, "recovered")).use { it.write(ByteArray(32)) }
        assertEquals(48L, cacheDirectoryBytes(root))
    }

    @Test
    fun bufferedLateWriteMustStillAcquireBytesAfterAnotherWriterCloses() = withRoot { root ->
        val budget = ScanCacheBudget(root, 32)
        val buffered = BufferedOutputStream(budget.open(File(root, "buffered")), 64)
        try {
            buffered.write(ByteArray(24))
            budget.open(File(root, "other")).use { it.write(ByteArray(16)) }
            assertThrows(IOException::class.java) { buffered.flush() }
            assertEquals(16L, cacheDirectoryBytes(root))
        } finally {
            assertThrows(IOException::class.java) { buffered.close() }
        }
        budget.open(File(root, "recovered")).use { it.write(ByteArray(16)) }
        assertEquals(32L, cacheDirectoryBytes(root))
    }

    @Test
    fun pdfCannotDuplicateSourcesPastTheSharedBudgetAndLeavesNoPartialOutput() = withRoot { root ->
        val source = File(root, "page.jpg").apply { writeBytes(ByteArray(16_384) { 7 }) }
        val oldBudget = scanCacheBudget
        scanCacheBudget = ScanCacheBudget(root, 24_576)
        try {
            val output = File(root, "scan.pdf")
            assertThrows(IOException::class.java) {
                JpegPdfWriter.write(output, listOf(JpegPdfPage(source, 20, 20)))
            }
            assertFalse(output.exists())
            assertEquals(listOf("page.jpg"), root.listFiles()!!.map(File::getName))
            assertEquals(16_384L, source.length())
            scanCacheOutput(File(root, "next")).use { it.write(ByteArray(8_192)) }
        } finally {
            scanCacheBudget = oldBudget
        }
    }

    @Test
    fun cancelledRenderClosesItsWriterBeforeRemovingWorkAndRestoresCapacity() = withRoot { root ->
        val history = File(root, "history").apply { writeText("preserve") }
        val oldBudget = scanCacheBudget
        scanCacheBudget = ScanCacheBudget(root, 64)
        try {
            val page = ScanPdfBuildPage(
                longestEdge = 20,
                renderJpeg = { directory, _ ->
                    scanCacheOutput(File(directory, "page.jpg")).use { output ->
                        output.write(ByteArray(32))
                        throw CancellationException("Cancelled after a partial render")
                    }
                },
                createBitonal = { error("Cancelled render must not build bitonal output") },
            )
            assertThrows(CancellationException::class.java) {
                buildScanPdf(File(root, "scan.pdf"), listOf(page), PdfSizeTarget.Original, false)
            }
            assertEquals(listOf("history"), root.listFiles()!!.map(File::getName))
            assertEquals("preserve", history.readText())
            scanCacheOutput(File(root, "next")).use { it.write(ByteArray(56)) }
            assertEquals(64L, cacheDirectoryBytes(root))
        } finally {
            scanCacheBudget = oldBudget
        }
    }

    private fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("scan-cache-budget-test-").toFile()
        try {
            block(root)
        } finally {
            assertTrue(deleteTreeWithoutFollowingLinks(root))
        }
    }
}
