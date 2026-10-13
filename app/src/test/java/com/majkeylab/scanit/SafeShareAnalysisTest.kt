package com.majkeylab.scanit

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SafeShareAnalysisTest {
    private val bounds = NormalizedRect(0.1f, 0.2f, 0.4f, 0.3f)

    @Test
    fun completeOcrKeepsSensitiveEntityCoordinates() {
        val snapshot =
            DocumentOcrSnapshot(
                pageTexts = listOf("person@example.com"),
                elements = listOf(OcrElement(0, "person@example.com", bounds)),
                truncated = false,
            )

        assertEquals(
            listOf(RedactionSuggestion(0, SensitiveRegionKind.Email, bounds)),
            safeShareEntitySuggestions(snapshot),
        )
    }

    @Test
    fun decimalReceiptAmountBesideDateDoesNotBecomeAPhoneRedaction() {
        val snapshot =
            DocumentOcrSnapshot(
                pageTexts = listOf("USD 12.34 2026-08-18"),
                elements =
                    listOf(
                        OcrElement(0, "USD", NormalizedRect(0.02f, 0.2f, 0.07f, 0.24f)),
                        OcrElement(0, "12.34", NormalizedRect(0.08f, 0.2f, 0.13f, 0.24f)),
                        OcrElement(0, "2026-08-18", NormalizedRect(0.14f, 0.2f, 0.19f, 0.24f)),
                    ),
                truncated = false,
            )

        assertEquals(emptyList<RedactionSuggestion>(), safeShareEntitySuggestions(snapshot))
    }

    @Test
    fun selectedTallPageKeepsAspectMetadataAndSplitPhoneProtection() {
        val words = listOf("+420", "123", "456", "789").mapIndexed { index, value ->
            val left = 100 + index * 55
            OcrElement(
                2, value,
                checkNotNull(normalizedRect(left, 400, left + 40, 420, 1000, 4000)),
                pageAspectRatio = 0.25f,
            )
        }
        val snapshot = DocumentOcrSnapshot(listOf("", "", "+420 123 456 789"), words, false)
            .selectSafeSharePages(listOf(IndexedValue(2, java.io.File("page-2.jpg"))))

        assertEquals(words.map { it.copy(page = 0) }, snapshot.elements)
        assertEquals(
            listOf(
                RedactionSuggestion(
                    0, SensitiveRegionKind.Phone,
                    checkNotNull(normalizedRect(100, 400, 305, 420, 1000, 4000)),
                ),
            ),
            safeShareEntitySuggestions(snapshot),
        )
    }

    @Test
    fun truncatedOcrCannotProduceAnApparentlyCompleteReview() {
        val snapshot =
            DocumentOcrSnapshot(
                pageTexts = listOf("person@example.com", ""),
                elements = listOf(OcrElement(0, "person@example.com", bounds)),
                truncated = true,
            )

        assertThrows(IOException::class.java) { safeShareEntitySuggestions(snapshot) }
    }

    @Test
    fun saturatedEntityPageFailsBeforeFilteringOutDates() {
        val elements =
            List(MAX_DOCUMENT_ENTITY_CANDIDATES_PER_PAGE) { index ->
                val left = (index % 16) / 16f
                val top = (index / 16) / 16f
                OcrElement(
                    0,
                    "18.08.2026",
                    NormalizedRect(left, top, left + 0.02f, top + 0.02f),
                )
            } + OcrElement(0, "person@example.com", bounds)
        val snapshot = DocumentOcrSnapshot(listOf("dates and email"), elements, truncated = false)

        assertEquals(
            MAX_DOCUMENT_ENTITY_CANDIDATES_PER_PAGE,
            buildDocumentEntityCandidates(elements).size,
        )
        assertThrows(IOException::class.java) { safeShareEntitySuggestions(snapshot) }
    }

    @Test
    fun entityLimitIsPerPageRatherThanPerDocument() {
        val perPage = MAX_DOCUMENT_ENTITY_CANDIDATES_PER_PAGE - 1
        val elements =
            (0..1).flatMap { page ->
                List(perPage) { index -> OcrElement(page, "person$index@example.com", bounds) }
            }
        val snapshot = DocumentOcrSnapshot(listOf("emails", "emails"), elements, truncated = false)

        assertEquals(perPage * 2, safeShareEntitySuggestions(snapshot).size)
    }

    @Test
    fun twoCodesAnd255EmailsCannotSilentlyDropTheLastEmail() {
        val candidates =
            List(MAX_SAFE_SHARE_SUGGESTIONS_PER_PAGE + 1) { index ->
                spacedSuggestion(
                    index,
                    if (index < 2) SensitiveRegionKind.Code else SensitiveRegionKind.Email,
                )
            }

        assertEquals(
            MAX_SAFE_SHARE_SUGGESTIONS_PER_PAGE,
            buildSafeShareAnalysis(1, candidates.dropLast(1)).suggestions.size,
        )
        assertThrows(IOException::class.java) { buildSafeShareAnalysis(1, candidates) }
    }

    @Test
    fun duplicatesOfEveryKindAreAcceptedAtCapacity() {
        val kinds = SensitiveRegionKind.entries
        val candidates =
            List(MAX_SAFE_SHARE_SUGGESTIONS_PER_PAGE) { index ->
                spacedSuggestion(index, kinds[index % kinds.size])
            }
        val duplicates = kinds.map { kind -> candidates.first { it.kind == kind } }
        val analysis = buildSafeShareAnalysis(1, candidates + duplicates, padding = 0f)

        assertEquals(MAX_SAFE_SHARE_SUGGESTIONS_PER_PAGE, analysis.suggestions.size)
        assertEquals(candidates.toSet(), analysis.suggestions.toSet())
    }

    @Test
    fun overlapAtCapacityKeepsNewCoveredPixelsAndOtherKinds() {
        val candidates =
            List(MAX_SAFE_SHARE_SUGGESTIONS_PER_PAGE) { index ->
                spacedSuggestion(
                    index,
                    if (index == 0) SensitiveRegionKind.Code else SensitiveRegionKind.Email,
                )
            }
        val overlap =
            RedactionSuggestion(
                0,
                SensitiveRegionKind.Code,
                NormalizedRect(0.01f, 0.01f, 0.035f, 0.035f),
            )
        val analysis = buildSafeShareAnalysis(1, candidates + overlap, padding = 0f)
        val expanded = candidates.first().copy(bounds = NormalizedRect(0f, 0f, 0.035f, 0.035f))

        assertEquals(MAX_SAFE_SHARE_SUGGESTIONS_PER_PAGE, analysis.suggestions.size)
        assertEquals((candidates.drop(1) + expanded).toSet(), analysis.suggestions.toSet())
    }

    private fun spacedSuggestion(index: Int, kind: SensitiveRegionKind): RedactionSuggestion {
        val left = (index % 17) / 17f
        val top = (index / 17) / 17f
        return RedactionSuggestion(
            0,
            kind,
            NormalizedRect(left, top, left + 0.02f, top + 0.02f),
        )
    }
}
