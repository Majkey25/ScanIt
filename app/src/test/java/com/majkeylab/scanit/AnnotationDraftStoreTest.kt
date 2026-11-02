package com.majkeylab.scanit

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.FileSystemException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AnnotationDraftStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun czechTextInkAndSourceIdentitySurviveANewStoreInstance() {
        val directory = temporary.newFolder()
        val original = draft()
        AnnotationDraftStore(directory).save(original)
        assertEquals(original, AnnotationDraftStore(directory).load())
        val replacement = original.copy(source = MarkEditorSource("Scan_other", OTHER_ENTRY_ID, MAX_SCAN_PAGES - 1))
        AnnotationDraftStore(directory).save(replacement)
        assertEquals(replacement, AnnotationDraftStore(directory).load())
        assertEquals(listOf(FILE_NAME), directory.list()!!.toList())
    }

    @Test
    fun missingLoadAndClearDoNotCreateStorageAndClearTouchesOnlyOwnedFiles() {
        val directory = File(temporary.root, "missing/nested")
        val store = AnnotationDraftStore(directory)
        assertNull(store.load())
        store.clear()
        assertFalse(directory.exists())
        store.save(draft())
        val unrelated = File(directory, "another-draft.json").apply { writeText("retain") }
        File(directory, STAGING_NAME).writeText("interrupted")
        store.clear()
        store.clear()
        assertNull(store.load())
        assertEquals("retain", unrelated.readText())
        assertFalse(File(directory, STAGING_NAME).exists())
    }

    @Test
    fun malformedJsonIsRejectedWithoutChangingTheFile() {
        val directory = temporary.newFolder()
        val store = AnnotationDraftStore(directory)
        store.save(draft())
        val file = File(directory, FILE_NAME)
        val valid = file.readText()
        val malformed = listOf(
            "", "{", "null", "[]", "{}", "$valid trailing", "$valid{}",
            valid.replace("\"version\":1", "\"version\":01"),
            valid.replace("\"version\":1", "\"version\":+1"),
            valid.replace("\"version\":1", "version:1"),
            valid.replace("\"version\":1", "'version':1"),
            valid.replace("\"highlighter\":true", "\"highlighter\":TRUE"),
            valid.dropLast(1) + ",}",
            valid.dropLast(1) + ",\"version\":1}",
            valid.dropLast(1) + ",\"\\u0076ersion\":1}",
            "{\"nested\":" + "[".repeat(10_000) + "0" + "]".repeat(10_000) + "}",
        )
        malformed.forEach { text ->
            file.writeText(text)
            assertNull("Accepted malformed JSON: ${text.take(100)}", store.load())
            assertEquals(text, file.readText())
        }
        file.writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        assertNull(store.load())
    }

    @Test
    fun oversizedFilesAreRejectedWithoutReadingOrDeletingThem() {
        val directory = temporary.newFolder()
        val file = File(directory, FILE_NAME)
        RandomAccessFile(file, "rw").use { it.setLength(MAX_BYTES + 1L) }
        assertNull(AnnotationDraftStore(directory).load())
        assertEquals(MAX_BYTES + 1L, file.length())
    }

    @Test
    fun versionIdentityAndFieldsAreStrict() {
        rejectJsonChanges(
            { it.put("version", 2) },
            { it.put("version", "1") },
            { it.put("version", 1.5) },
            { it.remove("version") },
            { it.put("extra", true) },
            { it.put("source", JSONObject.NULL) },
            { it.getJSONObject("source").put("cacheId", "../outside") },
            { it.getJSONObject("source").put("cacheId", "/absolute") },
            { it.getJSONObject("source").put("cacheId", "Scan\\outside") },
            { it.getJSONObject("source").put("entryId", "not-a-uuid") },
            { it.getJSONObject("source").put("entryId", ENTRY_ID.uppercase()) },
            { it.getJSONObject("source").put("pageIndex", -1) },
            { it.getJSONObject("source").put("pageIndex", MAX_SCAN_PAGES) },
            { it.getJSONObject("source").put("pageIndex", "0") },
            { it.getJSONObject("source").put("pageIndex", 0.5) },
            { it.getJSONObject("source").put("extra", 0) },
            { it.getJSONObject("source").remove("entryId") },
            { it.put("annotations", JSONObject.NULL) },
            { it.getJSONArray("annotations").put(0, JSONObject.NULL) },
            { it.getJSONArray("annotations").getJSONObject(0).put("type", "Ink") },
            { it.getJSONArray("annotations").getJSONObject(0).put("extra", false) },
            { it.getJSONArray("annotations").getJSONObject(0).remove("highlighter") },
            { it.getJSONArray("annotations").getJSONObject(0).put("highlighter", "true") },
            { it.getJSONArray("annotations").getJSONObject(0).put("color", 2_147_483_648L) },
            { it.getJSONArray("annotations").getJSONObject(0).put("color", "-1") },
            { it.getJSONArray("annotations").getJSONObject(1).put("value", 42) },
            { it.getJSONArray("annotations").getJSONObject(1).put("extra", 0) },
        )
    }

    @Test
    fun geometryIsNormalizedBeforeConversionToFloat() {
        rejectJsonChanges(
            { it.ink().put("widthFraction", 0) },
            { it.ink().put("widthFraction", 1.00000001) },
            { it.ink().put("widthFraction", "0.01") },
            { it.ink().getJSONArray("points").put(0, JSONObject.NULL) },
            { it.point().put("x", -0.00000001) },
            { it.point().put("y", 1.00000001) },
            { it.point().put("pressure", 1.1) },
            { it.point().put("pressure", "1") },
            { it.point().remove("pressure") },
            { it.point().put("extra", 0) },
            { it.getJSONArray("annotations").getJSONObject(1).put("x", -0.1) },
            { it.getJSONArray("annotations").getJSONObject(1).put("y", 1.1) },
            { it.getJSONArray("annotations").getJSONObject(1).put("sizeFraction", 0) },
        )
        val directory = temporary.newFolder()
        val store = AnnotationDraftStore(directory)
        store.save(draft())
        val file = File(directory, FILE_NAME)
        file.writeText(file.readText().replace("\"widthFraction\":0.01", "\"widthFraction\":1e999"))
        assertNull(store.load())
    }

    @Test
    fun annotationPointAndTextBudgetsAreEnforcedOnLoad() {
        rejectJsonChanges(
            { value ->
                value.put("annotations", JSONArray(List(MAX_PAGE_ANNOTATIONS + 1) { value.ink() }))
            },
            { value -> value.ink().put("points", JSONArray()) },
            { value ->
                value.ink().put("points", JSONArray(List(MAX_PAGE_ANNOTATION_POINTS + 1) { value.point() }))
            },
            { value ->
                val ink = value.ink()
                ink.put("points", JSONArray(List(MAX_PAGE_ANNOTATION_POINTS / 2 + 1) { value.point() }))
                value.put("annotations", JSONArray(listOf(ink, ink)))
            },
            { value -> value.getJSONArray("annotations").getJSONObject(1).put("value", "x".repeat(2_001)) },
            { value ->
                val text = value.getJSONArray("annotations").getJSONObject(1).put("value", "x".repeat(2_000))
                value.put("annotations", JSONArray(List(9) { text }))
            },
        )
    }

    @Test
    fun validBoundaryBudgetsRoundTrip() {
        val directory = temporary.newFolder()
        val store = AnnotationDraftStore(directory)
        val ink = PageAnnotation.Ink(List(MAX_PAGE_ANNOTATION_POINTS) { AnnotationPoint(0f, 1f, 0f) }, -1, 1f)
        val text = PageAnnotation.Text("č".repeat(MAX_ANNOTATION_TEXT_LENGTH), 1f, 0f, 1f, Int.MIN_VALUE)
        for (annotations in listOf(
            emptyList(), listOf(ink), List(8) { text },
            List(MAX_PAGE_ANNOTATIONS) { PageAnnotation.Text("a", 0f, 0f, 0.01f, -1) },
        )) {
            val expected = draft().copy(annotations = annotations)
            store.save(expected)
            assertEquals(expected, AnnotationDraftStore(directory).load())
        }
    }

    @Test
    fun invalidSavesPreserveThePreviousDraft() {
        val directory = temporary.newFolder()
        val store = AnnotationDraftStore(directory)
        val original = draft()
        store.save(original)
        val bytes = File(directory, FILE_NAME).readBytes()
        val points = mutableListOf(AnnotationPoint(0.5f, 0.5f))
        val ink = PageAnnotation.Ink(points, -1, 0.01f)
        points.clear()
        val invalid = listOf(
            original.copy(source = original.source.copy(pageIndex = MAX_SCAN_PAGES)),
            original.copy(annotations = List(MAX_PAGE_ANNOTATIONS + 1) { original.annotations.last() }),
            original.copy(annotations = listOf(ink)),
            original.copy(source = original.source.copy(cacheId = "a".repeat(MAX_BYTES + 1))),
        )
        invalid.forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { store.save(value) }
            assertArrayEquals(bytes, File(directory, FILE_NAME).readBytes())
            assertEquals(original, AnnotationDraftStore(directory).load())
            assertFalse(File(directory, STAGING_NAME).exists())
        }
    }

    @Test
    fun invalidUnicodeAndOversizedUtf8CannotReplaceAValidDraft() {
        val directory = temporary.newFolder()
        val store = AnnotationDraftStore(directory)
        val original = draft()
        store.save(original)
        val bytes = File(directory, FILE_NAME).readBytes()
        val text = original.annotations.last() as PageAnnotation.Text
        assertThrows(IOException::class.java) {
            store.save(original.copy(annotations = listOf(text.copy(value = "\uD800"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.save(original.copy(source = original.source.copy(cacheId = "č".repeat(MAX_BYTES / 2))))
        }
        assertArrayEquals(bytes, File(directory, FILE_NAME).readBytes())
        assertEquals(original, store.load())
        assertFalse(File(directory, STAGING_NAME).exists())
    }

    @Test
    fun interruptedStagingIsIgnoredThenReplacedWithoutSweepingOtherFiles() {
        val directory = temporary.newFolder()
        val store = AnnotationDraftStore(directory)
        val original = draft()
        store.save(original)
        val unrelated = File(directory, "another.tmp").apply { writeText("keep") }
        val staging = File(directory, STAGING_NAME).apply { writeText("incomplete") }
        assertEquals(original, AnnotationDraftStore(directory).load())
        val replacement = original.copy(annotations = emptyList())
        store.save(replacement)
        assertEquals(replacement, store.load())
        assertFalse(staging.exists())
        assertEquals("keep", unrelated.readText())
    }

    @Test
    fun stagingFailureIsExplicitAndPreservesThePreviousDraftAndOtherFiles() {
        val directory = temporary.newFolder()
        val store = AnnotationDraftStore(directory)
        val original = draft()
        store.save(original)
        val bytes = File(directory, FILE_NAME).readBytes()
        val staging = File(directory, STAGING_NAME).apply { assertTrue(mkdir()) }
        val unrelated = File(staging, "retain.txt").apply { writeText("retain") }
        assertThrows(IOException::class.java) { store.save(original.copy(annotations = emptyList())) }
        assertArrayEquals(bytes, File(directory, FILE_NAME).readBytes())
        assertEquals(original, AnnotationDraftStore(directory).load())
        assertEquals("retain", unrelated.readText())
    }

    @Test
    fun windowsAtomicReplaceFailurePreservesTheDraftAndCleansStaging() {
        assumeTrue(System.getProperty("os.name").orEmpty().startsWith("Windows"))
        val directory = temporary.newFolder()
        val store = AnnotationDraftStore(directory)
        val original = draft()
        store.save(original)
        val file = File(directory, FILE_NAME)
        val bytes = file.readBytes()
        assertTrue(file.setReadOnly())
        try {
            assertThrows(IOException::class.java) { store.save(original.copy(annotations = emptyList())) }
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(original, AnnotationDraftStore(directory).load())
            assertFalse(File(directory, STAGING_NAME).exists())
        } finally {
            assertTrue(file.setWritable(true))
        }
    }

    @Test
    fun symlinkFilesDirectoriesAndAncestorsAreRejected() {
        val directory = temporary.newFolder()
        val outside = temporary.newFolder()
        val otherFile = File(outside, "outside.json").apply { writeText("outside") }
        val target = File(directory, FILE_NAME).toPath()
        createLinkOrSkip(target.toFile(), otherFile)
        try {
            val store = AnnotationDraftStore(directory)
            assertThrows(IOException::class.java) { store.load() }
            assertThrows(IOException::class.java) { store.save(draft()) }
            assertThrows(IOException::class.java) { store.clear() }
            assertEquals("outside", otherFile.readText())
        } finally {
            Files.delete(target)
        }
        val staging = File(directory, STAGING_NAME).toPath()
        createLinkOrSkip(staging.toFile(), otherFile)
        try {
            assertThrows(IOException::class.java) { AnnotationDraftStore(directory).save(draft()) }
            assertEquals("outside", otherFile.readText())
        } finally {
            Files.delete(staging)
        }
        val link = File(temporary.root, "linked-directory")
        createLinkOrSkip(link, outside)
        try {
            for (root in listOf(link, File(link, "nested"))) {
                val store = AnnotationDraftStore(root)
                assertThrows(IOException::class.java) { store.load() }
                assertThrows(IOException::class.java) { store.save(draft()) }
                assertThrows(IOException::class.java) { store.clear() }
            }
            assertFalse(File(outside, "nested").exists())
        } finally {
            Files.delete(link.toPath())
        }
    }

    private fun rejectJsonChanges(vararg changes: (JSONObject) -> Unit) {
        val directory = temporary.newFolder()
        val store = AnnotationDraftStore(directory)
        store.save(draft())
        val file = File(directory, FILE_NAME)
        val original = file.readText()
        changes.forEachIndexed { index, change ->
            val value = JSONObject(original)
            change(value)
            val text = value.toString()
            file.writeText(text)
            assertNull("Accepted invalid mutation $index", store.load())
            assertEquals(text, file.readText())
        }
    }

    private fun JSONObject.ink(): JSONObject = getJSONArray("annotations").getJSONObject(0)

    private fun JSONObject.point(): JSONObject = ink().getJSONArray("points").getJSONObject(0)

    private fun createLinkOrSkip(link: File, target: File) {
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
        } catch (failure: FileSystemException) {
            assumeNoException("Host does not permit symbolic links", failure)
        } catch (failure: UnsupportedOperationException) {
            assumeNoException("Host does not support symbolic links", failure)
        }
    }

    private fun draft() = AnnotationDraft(
        MarkEditorSource("Scan_test", ENTRY_ID, 0),
        listOf(
            PageAnnotation.Ink(listOf(AnnotationPoint(0f, 1f), AnnotationPoint(0.75f, 0.25f, 0.6f)), -16_777_216, 0.01f, true),
            PageAnnotation.Text("Příliš žluťoučký kůň, účet č. 42", 0.2f, 0.3f, 0.04f, -1),
        ),
    )

    companion object {
        private const val FILE_NAME = "annotation-draft.json"
        private const val STAGING_NAME = "annotation-draft.json.tmp"
        private const val MAX_BYTES = 4 * 1024 * 1024
        private const val ENTRY_ID = "f58f9a90-21b0-4d14-8ac4-1a419a1b1001"
        private const val OTHER_ENTRY_ID = "75c936d0-b8bf-4c90-bc83-7a312dcda002"
    }
}
