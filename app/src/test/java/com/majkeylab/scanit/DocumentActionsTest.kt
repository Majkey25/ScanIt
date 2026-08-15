package com.majkeylab.scanit

import android.content.ClipDescription
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DocumentActionsTest {
    @Test
    fun emptyActionResultsStayEmpty() {
        assertEquals(
            DocumentActionOutput.Text(value = "", truncated = false),
            buildDocumentText(listOf(" ", "\n")),
        )
        assertTrue(buildDetectedCodes(emptySequence()).values.isEmpty())
    }

    @Test
    fun extractedTextNeverExceedsProductionCharacterLimit() {
        val output = buildDocumentText(listOf("a".repeat(MAX_DOCUMENT_TEXT_CHARACTERS + 1)))

        assertEquals(MAX_DOCUMENT_TEXT_CHARACTERS, output.value.length)
        assertTrue(output.truncated)
        expectFailure<IllegalArgumentException> {
            buildDocumentText(listOf("text"), MAX_DOCUMENT_TEXT_CHARACTERS + 1)
        }
    }

    @Test
    fun barcodePayloadRequiresStrictUtf8AndBounds() {
        assertNull(
            validatedDetectedCode(
                rawValue = "value",
                displayValue = "value",
                rawBytes = byteArrayOf(0xc3.toByte(), 0x28),
                isQrCode = false,
                typedUrl = null,
            ),
        )
        assertNull(
            validatedDetectedCode(
                rawValue = "x".repeat(MAX_DETECTED_CODE_CHARACTERS + 1),
                displayValue = null,
                rawBytes = null,
                isQrCode = false,
                typedUrl = null,
            ),
        )
    }

    @Test
    fun typedQrHttpUrlIsExplicitlyOpenable() {
        val value = "https://example.com/document?id=1"

        assertEquals(
            DetectedCode(
                kind = DetectedCodeKind.QrCode,
                value = value,
                openableHttpUrl = value,
            ),
            validatedDetectedCode(
                rawValue = value,
                displayValue = value,
                rawBytes = value.toByteArray(StandardCharsets.UTF_8),
                isQrCode = true,
                typedUrl = value,
            ),
        )
    }

    @Test
    fun structuredBookmarkDisplaysRawPayloadButOpensTypedUrl() {
        val rawValue = "MEBKM:TITLE:ScanIt;URL:https://example.com/document;;"
        val typedUrl = "https://example.com/document"

        assertEquals(
            DetectedCode(
                kind = DetectedCodeKind.QrCode,
                value = rawValue,
                openableHttpUrl = typedUrl,
            ),
            validatedDetectedCode(
                rawValue = rawValue,
                displayValue = "ScanIt",
                rawBytes = rawValue.toByteArray(StandardCharsets.UTF_8),
                isQrCode = true,
                typedUrl = typedUrl,
            ),
        )
    }

    @Test
    fun urlLookingUntypedPayloadStaysInert() {
        val value = "https://example.com/document"

        assertNull(
            validatedDetectedCode(
                rawValue = value,
                displayValue = value,
                rawBytes = value.toByteArray(StandardCharsets.UTF_8),
                isQrCode = true,
                typedUrl = null,
            )?.openableHttpUrl,
        )
        assertNull(validatedHttpUrl("javascript:alert(1)"))
        assertNull(validatedHttpUrl("https://user:password@example.com"))
        assertNull(validatedHttpUrl("https://example.com/\u202Ehidden"))
        assertEquals("http://example.com/path", validatedHttpUrl("http://example.com/path"))
    }

    @Test
    fun detectedCodeBuilderCannotPromoteUnvalidatedLinks() {
        val output =
            buildDetectedCodes(
                sequenceOf(
                    DetectedCode(
                        kind = DetectedCodeKind.QrCode,
                        value = "plain text",
                        openableHttpUrl = "javascript:alert(1)",
                    ),
                ),
            )

        assertNull(output.values.single().openableHttpUrl)
    }

    @Test
    fun detectedCodesAreDeduplicatedAndBoundedWithoutDrainingSequence() {
        var produced = 0
        val candidates =
            generateSequence {
                produced += 1
                DetectedCode(
                    kind = DetectedCodeKind.Barcode,
                    value = "code-$produced",
                    openableHttpUrl = null,
                )
            }

        val output = buildDetectedCodes(candidates)

        assertEquals(MAX_DETECTED_CODES, output.values.size)
        assertEquals(MAX_DETECTED_CODES, produced)
        assertEquals("code-1", output.values.first().value)
        assertEquals("code-64", output.values.last().value)
        assertEquals(
            listOf("abc"),
            buildDetectedCodes(
                sequenceOf(
                    DetectedCode(DetectedCodeKind.Barcode, "abc", null),
                    DetectedCode(DetectedCodeKind.Barcode, "def", null),
                ),
                maxCharacters = 4,
            ).values.map(DetectedCode::value),
        )
    }

    @Test
    fun textExportFilenameCannotContainPathsOrControls() {
        assertEquals("My_scan_text.txt", sanitizeTextExportFileName("../../My scan\r\n\u0000"))
        assertEquals("ScanIt_text.txt", sanitizeTextExportFileName("../.."))
        assertTrue(sanitizeTextExportFileName("a".repeat(500)).length <= MAX_TEXT_EXPORT_FILE_NAME_LENGTH)
    }

    @Test
    fun textExportWritesExactStrictUtf8AndHonorsCancellation() {
        val output = ByteArrayOutputStream()
        val text = "Příliš žluťoučký kůň\n文本"

        writeDocumentTextUtf8(output, text)

        assertArrayEquals(text.toByteArray(StandardCharsets.UTF_8), output.toByteArray())
        expectFailure<CancellationException> {
            writeDocumentTextUtf8(ByteArrayOutputStream(), "text") { true }
        }
        expectFailure<IllegalArgumentException> {
            writeDocumentTextUtf8(ByteArrayOutputStream(), "\ud800")
        }
    }

    @Test
    fun textExportPropagatesDestinationWriteFailure() {
        val failingOutput =
            object : OutputStream() {
                override fun write(value: Int) {
                    throw IOException("provider rejected write")
                }
            }

        expectFailure<IOException> {
            writeDocumentTextUtf8(failingOutput, "text")
        }
    }

    @Test
    fun providerTextExportTruncatesExistingContentAndMapsFailure() {
        val destination = File.createTempFile("scanit-text-export", ".txt")
        try {
            destination.writeText("existing content that is longer than the replacement")
            var openedMode: String? = null

            val saved =
                writeDocumentTextToDestination(
                    text = "short",
                    openOutputStream = { mode ->
                        openedMode = mode
                        FileOutputStream(destination, mode != "wt")
                    },
                )

            assertTrue(saved)
            assertEquals("wt", openedMode)
            assertEquals("short", destination.readText())
            val output = DocumentActionOutput.Text("short", truncated = false)
            assertEquals(
                DocumentTextExportStatus.Saved,
                completedDocumentTextExport(output, saved).textExportStatus,
            )
            assertFalse(
                writeDocumentTextToDestination(
                    text = "short",
                    openOutputStream = { null },
                ),
            )
            assertEquals(
                DocumentTextExportStatus.Failed,
                completedDocumentTextExport(output, saved = false).textExportStatus,
            )
        } finally {
            destination.delete()
        }
    }

    @Test
    fun textExportDestinationRequiresContentWriteGrantAndPlainTextMime() {
        assertTrue(
            isSafeTextExportDestination(
                scheme = "content",
                authority = "com.android.providers.downloads.documents",
                path = "/document/123",
                query = null,
                fragment = null,
                uriLength = 65,
                mimeType = "text/plain",
                writeGranted = true,
            ),
        )
        assertTrue(
            isSafeTextExportDestination(
                scheme = "content",
                authority = "provider",
                path = "/document/123",
                query = null,
                fragment = null,
                uriLength = 31,
                mimeType = null,
                writeGranted = true,
            ),
        )
        assertFalse(
            isSafeTextExportDestination(
                scheme = "file",
                authority = null,
                path = "/tmp/text.txt",
                query = null,
                fragment = null,
                uriLength = 18,
                mimeType = "text/plain",
                writeGranted = true,
            ),
        )
        assertFalse(
            isSafeTextExportDestination(
                scheme = "content",
                authority = "provider",
                path = "/document/123",
                query = null,
                fragment = null,
                uriLength = 31,
                mimeType = "text/plain",
                writeGranted = false,
            ),
        )
        assertFalse(
            isSafeTextExportDestination(
                scheme = "content",
                authority = "provider/other",
                path = "/document/123",
                query = null,
                fragment = null,
                uriLength = 37,
                mimeType = "text/plain",
                writeGranted = true,
            ),
        )
        assertFalse(
            isSafeTextExportDestination(
                scheme = "content",
                authority = "provider",
                path = "/document/123",
                query = "redirect=other",
                fragment = null,
                uriLength = 46,
                mimeType = "text/plain",
                writeGranted = true,
            ),
        )
        assertFalse(
            isSafeTextExportDestination(
                scheme = "content",
                authority = "provider",
                path = "/document/123",
                query = null,
                fragment = null,
                uriLength = 31,
                mimeType = "application/octet-stream",
                writeGranted = true,
            ),
        )
    }

    @Test
    fun documentActionRequestCodecAndMatcherBindSelectedPageAndAction() {
        val request =
            DocumentActionRequest(
                cacheId = "Scan_2026-08-15_12-00-00",
                entryId = "4de93580-a259-4d3d-a9e0-225a6b150462",
                pageIndex = 2,
                action = DocumentAction.ExtractText,
                generation = 7L,
            )

        assertEquals(request, decodeDocumentActionRequest(encodeDocumentActionRequest(request)))
        assertTrue(
            request.matches(
                cacheId = request.cacheId,
                entryId = request.entryId,
                pageIndex = 2,
                action = DocumentAction.ExtractText,
                generation = 7L,
            ),
        )
        assertFalse(
            request.matches(
                cacheId = request.cacheId,
                entryId = request.entryId,
                pageIndex = 1,
                action = DocumentAction.ExtractText,
                generation = 7L,
            ),
        )
        assertFalse(
            request.matches(
                cacheId = request.cacheId,
                entryId = request.entryId,
                pageIndex = 2,
                action = DocumentAction.DetectCodes,
                generation = 7L,
            ),
        )
        assertNull(decodeDocumentActionRequest("Scan\tentry\t-1\textract_text\t0"))
        assertNull(decodeDocumentActionRequest("x".repeat(500)))
    }

    @Test
    fun exportStateBlocksOtherResultActions() {
        val result =
            ScreenState.Result(
                scan =
                    SavedScan(
                        cached =
                            CachedScan(
                                baseName = "Scan_A",
                                pages = emptyList(),
                                pdf = File("scan.pdf"),
                                entryId = "4de93580-a259-4d3d-a9e0-225a6b150462",
                            ),
                        savedImages = emptyList(),
                        savedPdf = null,
                    ),
                thumbnail = null,
                documentActionState =
                    DocumentActionState.Exporting(
                        DocumentActionOutput.Text("text", truncated = false),
                    ),
            )

        assertTrue(result.resultActionsBlocked)
    }

    @Test
    fun clipboardPolicyMarksCopiedDocumentContentSensitive() {
        assertEquals(
            SensitiveClipboardExtra(ClipDescription.EXTRA_IS_SENSITIVE, true),
            documentClipboardSensitiveExtra(),
        )
    }

    private inline fun <reified T : Throwable> expectFailure(block: () -> Unit): T {
        try {
            block()
        } catch (failure: Throwable) {
            if (failure is T) return failure
            throw failure
        }
        fail("Expected ${T::class.java.simpleName}")
        throw AssertionError("unreachable")
    }
}
