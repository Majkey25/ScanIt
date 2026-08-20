package com.majkeylab.scanit

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MarkTemplateStorageTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun boundedDimensionsPreserveAspectWithoutUpscaling() {
        assertEquals(2_048 to 1_024, boundedMarkDimensions(4_000, 2_000, 2_048))
        assertEquals(1 to 2_048, boundedMarkDimensions(1, Int.MAX_VALUE, 2_048))
        assertEquals(320 to 160, boundedMarkDimensions(320, 160, 2_048))
        assertThrows(IllegalArgumentException::class.java) {
            boundedMarkDimensions(0, 160, 2_048)
        }
    }

    @Test
    fun nextIdUsesFirstFreeNonNegativeTimestamp() {
        assertEquals("mark_42.png", nextMarkTemplateId(emptySet(), 42))
        assertEquals(
            "mark_44.png",
            nextMarkTemplateId(setOf("mark_42.png", "mark_43.png"), 42),
        )
        assertEquals("mark_0.png", nextMarkTemplateId(emptySet(), -1))
        assertThrows(IllegalStateException::class.java) {
            nextMarkTemplateId(setOf("mark_${Long.MAX_VALUE}.png"), Long.MAX_VALUE)
        }
    }

    @Test
    fun boundedCopyAcceptsExactLimitAndRejectsEmptyOrOversizedInput() {
        val output = ByteArrayOutputStream()

        assertEquals(
            4L,
            copyBoundedInput(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), output, 4),
        )
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), output.toByteArray())
        assertThrows(IOException::class.java) {
            copyBoundedInput(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)), ByteArrayOutputStream(), 4)
        }
        assertThrows(IOException::class.java) {
            copyBoundedInput(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(), 4)
        }
    }

    @Test
    fun listingReturnsOnlyCanonicalTemplateFilesNewestFirst() {
        val directory = temporaryFolder.newFolder("marks")
        File(directory, "mark_2.png").writeBytes(byteArrayOf(2))
        File(directory, "mark_10.png").writeBytes(byteArrayOf(10))
        File(directory, "mark_-1.png").writeBytes(byteArrayOf(1))
        File(directory, "mark_12.png").mkdir()

        assertEquals(listOf("mark_10.png", "mark_2.png"), listMarkTemplateIds(directory))
    }

    @Test
    fun templateAllocationRejectsTheThirteenthSavedMark() {
        val directory = temporaryFolder.newFolder("marks")
        repeat(12) { index -> File(directory, "mark_$index.png").writeBytes(byteArrayOf(1)) }

        assertThrows(IOException::class.java) {
            availableMarkTemplateId(directory, 99)
        }
        File(directory, "mark_0.png").delete()
        assertEquals("mark_99.png", availableMarkTemplateId(directory, 99))
    }

    @Test
    fun concurrentTemplateSavesCannotPublishAThirteenthMark() {
        val directory = temporaryFolder.newFolder("marks")
        repeat(11) { index -> File(directory, "mark_$index.png").writeBytes(byteArrayOf(1)) }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results =
                listOf(100L, 101L).map { timestamp ->
                    executor.submit<String> {
                        saveNewMarkTemplate(directory, timestamp) { output -> output.write(7) }
                    }
                }

            assertEquals(1, results.count { future -> runCatching { future.get() }.isSuccess })
            assertEquals(12, listMarkTemplateIds(directory).size)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun staleOwnedTemporaryFilesArePurgedWithoutTouchingTemplates() {
        val directory = temporaryFolder.newFolder("marks")
        val template = File(directory, "mark_1.png").apply { writeBytes(byteArrayOf(1)) }
        val importTemporary = File(directory, ".mark_import_1.tmp").apply { writeBytes(byteArrayOf(2)) }
        val saveTemporary = File(directory, ".mark_2.tmp").apply { writeBytes(byteArrayOf(3)) }

        purgeIncompleteMarkFiles(directory)

        assertTrue(template.isFile)
        assertFalse(importTemporary.exists())
        assertFalse(saveTemporary.exists())
    }

    @Test
    fun deletionCannotEscapeTemplateDirectory() {
        val root = temporaryFolder.newFolder("private")
        val directory = File(root, "marks").apply { mkdir() }
        val template = File(directory, "mark_1.png").apply { writeBytes(byteArrayOf(1)) }
        val sibling = File(root, "mark_1.png").apply { writeBytes(byteArrayOf(2)) }

        assertFalse(deleteMarkTemplateFile(directory, "../mark_1.png"))
        assertTrue(sibling.isFile)
        assertTrue(deleteMarkTemplateFile(directory, "mark_1.png"))
        assertFalse(template.exists())
        assertFalse(deleteMarkTemplateFile(directory, "mark_1.png"))
    }

    @Test
    fun atomicSavePublishesCompleteFileAndCleansFailedTemporaryFile() {
        val directory = temporaryFolder.newFolder("marks")
        val expected = byteArrayOf(1, 3, 3, 7)

        atomicSaveMarkTemplate(directory, "mark_1.png") { output -> output.write(expected) }

        assertArrayEquals(expected, File(directory, "mark_1.png").readBytes())
        assertThrows(IOException::class.java) {
            atomicSaveMarkTemplate(directory, "mark_2.png") { output ->
                output.write(9)
                throw IOException("broken encoder")
            }
        }
        assertFalse(File(directory, "mark_2.png").exists())
        assertEquals(listOf("mark_1.png"), directory.listFiles()?.map(File::getName))
    }

    @Test
    fun atomicPublishMovesTheTemporaryFileWithoutCreatingAHardLink() {
        val directory = temporaryFolder.newFolder("atomic-move")
        val temporary = File(directory, ".mark_1.tmp").apply { writeBytes(byteArrayOf(4, 2)) }
        val target = File(directory, "mark_1.png")

        publishStagedFileNoReplace(temporary.toPath(), target.toPath())

        assertFalse(temporary.exists())
        assertArrayEquals(byteArrayOf(4, 2), target.readBytes())
    }
}
