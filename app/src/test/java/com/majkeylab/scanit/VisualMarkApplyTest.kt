package com.majkeylab.scanit

import java.io.File
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VisualMarkApplyTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun editorSourceMatchesOnlyTheExactDocumentGenerationAndPage() {
        val source = MarkEditorSource("Scan_A", "4de93580-a259-4d3d-a9e0-225a6b150462", 1)

        assertTrue(source.isCurrent("Scan_A", "4de93580-a259-4d3d-a9e0-225a6b150462", 1))
        assertFalse(source.isCurrent("Scan_B", "4de93580-a259-4d3d-a9e0-225a6b150462", 1))
        assertFalse(source.isCurrent("Scan_A", "1ef0bffe-f239-48d0-aeb0-fb739d244c97", 1))
        assertFalse(source.isCurrent("Scan_A", "4de93580-a259-4d3d-a9e0-225a6b150462", 0))
        assertThrows(IllegalArgumentException::class.java) {
            MarkEditorSource("../Scan_A", "4de93580-a259-4d3d-a9e0-225a6b150462", 1)
        }
    }

    @Test
    fun normalizedDrawingIsBoundedAndScalesToTheRequestedBitmap() {
        val strokes =
            listOf(
                MarkStroke(
                    listOf(
                        MarkPoint(0f, 0f),
                        MarkPoint(0.25f, 0.5f),
                        MarkPoint(1f, 1f),
                    ),
                ),
            )

        assertEquals(
            listOf(
                MarkStroke(
                    listOf(
                        MarkPoint(0f, 0f),
                        MarkPoint(100f, 100f),
                        MarkPoint(400f, 200f),
                    ),
                ),
            ),
            scaleNormalizedMarkStrokes(strokes, width = 400, height = 200),
        )
        assertThrows(IllegalArgumentException::class.java) {
            scaleNormalizedMarkStrokes(emptyList(), width = 400, height = 200)
        }
        assertThrows(IllegalArgumentException::class.java) {
            scaleNormalizedMarkStrokes(
                listOf(MarkStroke(listOf(MarkPoint(1.01f, 0.5f)))),
                width = 400,
                height = 200,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            scaleNormalizedMarkStrokes(
                listOf(MarkStroke(List(MAX_MARK_DRAWING_POINTS + 1) { MarkPoint(0.5f, 0.5f) })),
                width = 400,
                height = 200,
            )
        }
    }

    @Test
    fun visualMarkEditorBlocksEveryResultAction() {
        val editor =
            VisualMarkEditorState(
                source =
                    MarkEditorSource(
                        "Scan_A",
                        "4de93580-a259-4d3d-a9e0-225a6b150462",
                        0,
                    ),
            )
        val result =
            ScreenState.Result(
                scan = savedScan("Scan_A"),
                thumbnail = null,
                visualMarkEditor = editor,
            )

        assertTrue(result.resultActionsBlocked)
        assertFalse(result.copy(visualMarkEditor = null).resultActionsBlocked)
        assertTrue(result.copy(visualMarkEditor = null, outputSaveInProgress = true).resultActionsBlocked)
    }

    @Test
    fun documentActionProcessingBlocksEveryResultAction() {
        val result =
            ScreenState.Result(
                scan = savedScan("Scan_A"),
                thumbnail = null,
                documentActionState = DocumentActionState.Processing(DocumentAction.ExtractText),
            )

        assertTrue(result.resultActionsBlocked)
        assertFalse(
            result.copy(
                documentActionState =
                    DocumentActionState.Completed(
                        DocumentActionOutput.Text("Detected text", truncated = false),
                    ),
            ).resultActionsBlocked,
        )
    }

    @Test
    fun documentActionRequestRequiresExactResultGeneration() {
        val request =
            DocumentActionRequest(
                cacheId = "Scan_A",
                entryId = "4de93580-a259-4d3d-a9e0-225a6b150462",
                pageIndex = 0,
                action = DocumentAction.DetectCodes,
                generation = 7L,
            )

        assertTrue(
            request.matches(
                cacheId = "Scan_A",
                entryId = "4de93580-a259-4d3d-a9e0-225a6b150462",
                pageIndex = 0,
                action = DocumentAction.DetectCodes,
                generation = 7L,
            ),
        )
        assertFalse(
            request.matches(
                cacheId = "Scan_A",
                entryId = "new-generation",
                pageIndex = 0,
                action = DocumentAction.DetectCodes,
                generation = 7L,
            ),
        )
        assertFalse(
            request.matches(
                cacheId = "Scan_A",
                entryId = "4de93580-a259-4d3d-a9e0-225a6b150462",
                pageIndex = 0,
                action = DocumentAction.DetectCodes,
                generation = 8L,
            ),
        )
    }

    @Test
    fun extractedTextKeepsPageOrderAndSkipsBlankPages() {
        assertEquals(
            DocumentActionOutput.Text(
                value = "Page 1\nFirst page\n\nPage 3\nThird page",
                truncated = false,
            ),
            buildDocumentText(listOf(" First page ", "  ", "Third page")),
        )
    }

    @Test
    fun extractedTextIsBoundedAndReportsTruncation() {
        assertEquals(
            DocumentActionOutput.Text(value = "Page 1\n12", truncated = true),
            buildDocumentText(listOf("123456"), maxCharacters = 9),
        )
    }

    @Test
    fun derivedSourceWriterChangesOnlyTheSelectedPageAndPreservesOrder() {
        val sourceDirectory = temporaryFolder.newFolder("source")
        val sourcePages =
            (1..3).map { page ->
                File(sourceDirectory, "source_$page.jpg").apply {
                    writeBytes(byteArrayOf(page.toByte()))
                }
            }
        val workDirectory = temporaryFolder.newFolder("work")

        val derived =
            writeMarkedSourcePages(
                sourcePages = sourcePages,
                workDirectory = workDirectory,
                derivedBaseName = "Scan_A_mark",
                selectedPageIndex = 1,
                renderSelectedPage = { source, target ->
                    target.writeBytes(source.readBytes() + 9)
                },
            )

        assertEquals(
            listOf(
                "Scan_A_mark_source_01.jpg",
                "Scan_A_mark_source_02.jpg",
                "Scan_A_mark_source_03.jpg",
            ),
            derived.map(File::getName),
        )
        assertArrayEquals(byteArrayOf(1), derived[0].readBytes())
        assertArrayEquals(byteArrayOf(2, 9), derived[1].readBytes())
        assertArrayEquals(byteArrayOf(3), derived[2].readBytes())
        assertArrayEquals(byteArrayOf(2), sourcePages[1].readBytes())
    }

    @Test
    fun failedSelectedPageRenderLeavesNoDerivedSources() {
        val sourceDirectory = temporaryFolder.newFolder("source-failure")
        val sourcePages =
            (1..2).map { page ->
                File(sourceDirectory, "source_$page.jpg").apply {
                    writeBytes(byteArrayOf(page.toByte()))
                }
            }
        val workDirectory = temporaryFolder.newFolder("work-failure")

        assertThrows(IOException::class.java) {
            writeMarkedSourcePages(
                sourcePages = sourcePages,
                workDirectory = workDirectory,
                derivedBaseName = "Scan_A_mark",
                selectedPageIndex = 0,
                renderSelectedPage = { _, target ->
                    target.writeBytes(byteArrayOf(9))
                    throw IOException("renderer failed")
                },
            )
        }

        assertEquals(emptyList<String>(), workDirectory.listFiles().orEmpty().map(File::getName))
    }

    @Test
    fun derivedSourceCopyIsIndependentFromTheOriginalFile() {
        val directory = temporaryFolder.newFolder("source-copy")
        val source = File(directory, "source.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val target = File(directory, "derived.jpg")

        copyDerivedSourcePage(source, target)
        target.writeBytes(byteArrayOf(9))

        assertArrayEquals(byteArrayOf(1, 2, 3), source.readBytes())
        assertArrayEquals(byteArrayOf(9), target.readBytes())
    }

    private fun savedScan(cacheId: String): SavedScan =
        SavedScan(
            cached =
                CachedScan(
                    baseName = cacheId,
                    pages = listOf(File("page.jpg")),
                    pdf = File("scan.pdf"),
                    entryId = "4de93580-a259-4d3d-a9e0-225a6b150462",
                ),
            galleryPages = emptyList(),
            savedPdf = null,
            outputMetadataValid = true,
        )
}
