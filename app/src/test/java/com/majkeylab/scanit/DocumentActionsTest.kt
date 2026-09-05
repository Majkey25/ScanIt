package com.majkeylab.scanit

import android.content.ClipDescription
import com.google.android.gms.common.moduleinstall.ModuleAvailabilityResponse
import com.google.android.gms.common.moduleinstall.ModuleInstallClient
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.face.FaceDetector
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DocumentActionsTest {
    @Test
    fun selectedPageSafeShareProcessesOneFileAndKeepsOriginalPageIdentity() = runBlocking {
        val pages = listOf(File("page-0.jpg"), File("unrelated-failure.jpg"), File("page-2.jpg"))
        val analysisPages =
            safeShareAnalysisPages(pages, SafeShareScope.SelectedPage, selectedPage = 2)
        var processedPages = emptyList<File>()

        val snapshot =
            extractSafeShareOcr(analysisPages) { selectedFiles ->
                processedPages = selectedFiles
                if (selectedFiles.any { it.name == "unrelated-failure.jpg" }) {
                    throw IOException("Unrelated page must not be processed")
                }
                DocumentOcrSnapshot(
                    pageTexts = listOf("person@example.com"),
                    elements =
                        listOf(
                            OcrElement(
                                page = 0,
                                value = "person@example.com",
                                bounds = NormalizedRect(0.1f, 0.2f, 0.4f, 0.3f),
                            ),
                        ),
                    truncated = false,
                )
            }
        val suggestion =
            safeShareEntitySuggestion(buildDocumentEntityCandidates(snapshot.elements).single())
                ?.onOriginalSafeSharePage(analysisPages)
        val regions =
            safeShareRegions(
                buildSafeShareAnalysis(pageCount = pages.size, listOfNotNull(suggestion)),
                SafeShareScope.SelectedPage,
                selectedPage = 2,
            )

        assertEquals(listOf(pages[2]), processedPages)
        assertEquals(listOf(IndexedValue(2, pages[2])), analysisPages)
        assertEquals(2, regions.single().page)
        assertEquals("suggestion-2-0", regions.single().id)
        assertEquals(NormalizedRect(0.09f, 0.19f, 0.41f, 0.31f), regions.single().bounds)

        val selectedOwnedSnapshot =
            DocumentOcrSnapshot(
                pageTexts = listOf("first", "unrelated", "person@example.com"),
                elements =
                    listOf(
                        OcrElement(0, "first", NormalizedRect(0f, 0f, 0.1f, 0.1f)),
                        OcrElement(2, "person@example.com", NormalizedRect(0.1f, 0.2f, 0.4f, 0.3f)),
                    ),
                truncated = false,
            ).selectSafeSharePages(analysisPages)
        assertEquals(listOf("person@example.com"), selectedOwnedSnapshot.pageTexts)
        assertEquals(0, selectedOwnedSnapshot.elements.single().page)
    }

    @Test
    fun allPagesSafeShareAnalysisStillProcessesEveryPageInOrder() {
        val pages = listOf(File("page-0.jpg"), File("page-1.jpg"), File("page-2.jpg"))

        assertEquals(
            pages.mapIndexed(::IndexedValue),
            safeShareAnalysisPages(pages, SafeShareScope.AllPages, selectedPage = 1),
        )
    }

    @Test
    fun safeShareConvertsOnlyMechanicalSensitiveEntitiesAndBoundedCodes() {
        val emailBounds = NormalizedRect(0.1f, 0.2f, 0.4f, 0.3f)
        val cardBounds = NormalizedRect(0.2f, 0.4f, 0.6f, 0.5f)
        val codeBounds = NormalizedRect(0.5f, 0.6f, 0.9f, 0.9f)
        val entities =
            listOf(
                DocumentEntityCandidate(0, DocumentEntityKind.Email, "person@example.com", emailBounds),
                DocumentEntityCandidate(0, DocumentEntityKind.PaymentCard, "4111111111111111", cardBounds),
                DocumentEntityCandidate(0, DocumentEntityKind.Money, "EUR 12.00", cardBounds),
                DocumentEntityCandidate(0, DocumentEntityKind.Date, "18.08.2026", cardBounds),
            )
        val codeBoundsCandidates = listOf(codeBounds, null)

        val analysis =
            buildSafeShareAnalysis(
                pageCount = 1,
                candidates =
                    entities.mapNotNull(::safeShareEntitySuggestion) +
                        codeBoundsCandidates.mapNotNull { safeShareCodeSuggestion(0, it) },
                padding = 0f,
            )

        assertEquals(
            listOf(
                RedactionSuggestion(0, SensitiveRegionKind.Email, emailBounds),
                RedactionSuggestion(0, SensitiveRegionKind.PaymentCard, cardBounds),
                RedactionSuggestion(0, SensitiveRegionKind.Code, codeBounds),
            ),
            analysis.suggestions,
        )
    }

    @Test
    fun safeSharePadsClampsAndMergesOnlyOverlappingSameKindOnSamePage() {
        val analysis =
            buildSafeShareAnalysis(
                pageCount = 2,
                candidates =
                    listOf(
                        RedactionSuggestion(
                            0,
                            SensitiveRegionKind.Email,
                            NormalizedRect(0f, 0.25f, 0.5f, 0.5f),
                        ),
                        RedactionSuggestion(
                            0,
                            SensitiveRegionKind.Email,
                            NormalizedRect(0.375f, 0.375f, 0.75f, 0.75f),
                        ),
                        RedactionSuggestion(
                            0,
                            SensitiveRegionKind.Face,
                            NormalizedRect(0.375f, 0.375f, 0.75f, 0.75f),
                        ),
                        RedactionSuggestion(
                            1,
                            SensitiveRegionKind.Email,
                            NormalizedRect(0.375f, 0.375f, 0.75f, 0.75f),
                        ),
                    ),
                padding = 0.125f,
            )

        assertEquals(
            listOf(
                RedactionSuggestion(
                    0,
                    SensitiveRegionKind.Email,
                    NormalizedRect(0f, 0.125f, 0.875f, 0.875f),
                ),
                RedactionSuggestion(
                    0,
                    SensitiveRegionKind.Face,
                    NormalizedRect(0.25f, 0.25f, 0.875f, 0.875f),
                ),
                RedactionSuggestion(
                    1,
                    SensitiveRegionKind.Email,
                    NormalizedRect(0.25f, 0.25f, 0.875f, 0.875f),
                ),
            ),
            analysis.suggestions,
        )
    }

    @Test
    fun safeShareCapsEveryPageAndRejectsMoreThanTwentyPages() {
        val candidates =
            List(300) { index ->
                val left = index / 600f
                RedactionSuggestion(
                    0,
                    SensitiveRegionKind.Face,
                    NormalizedRect(left, 0f, left + 1f / 1_200f, 1f),
                )
            } +
                RedactionSuggestion(
                    MAX_SCAN_PAGES - 1,
                    SensitiveRegionKind.Code,
                    NormalizedRect(0f, 0f, 1f, 1f),
                )

        val analysis =
            buildSafeShareAnalysis(
                pageCount = MAX_SCAN_PAGES,
                candidates = candidates,
                padding = 0f,
            )

        assertEquals(MAX_SCAN_PAGES, analysis.pageCount)
        assertEquals(MAX_SAFE_SHARE_SUGGESTIONS_PER_PAGE, analysis.suggestions.count { it.page == 0 })
        assertEquals(1, analysis.suggestions.count { it.page == MAX_SCAN_PAGES - 1 })
        expectFailure<IllegalArgumentException> {
            buildSafeShareAnalysis(MAX_SCAN_PAGES + 1, emptyList())
        }
    }

    @Test
    fun safeShareRejectsMalformedFaceBounds() {
        assertEquals(
            RedactionSuggestion(
                0,
                SensitiveRegionKind.Face,
                NormalizedRect(0.1f, 0.2f, 0.5f, 0.8f),
            ),
            safeShareFaceSuggestion(0, 10, 20, 50, 80, 100, 100),
        )
        assertNull(safeShareFaceSuggestion(0, -1, 20, 50, 80, 100, 100))
        assertNull(safeShareFaceSuggestion(0, 50, 20, 10, 80, 100, 100))
        assertNull(safeShareFaceSuggestion(0, 10, 20, 101, 80, 100, 100))
        assertNull(safeShareFaceSuggestion(0, 10, 20, 50, 80, 0, 100))
        assertNull(safeShareFaceSuggestion(MAX_SCAN_PAGES, 10, 20, 50, 80, 100, 100))
    }

    @Test
    fun faceModelBoundaryDistinguishesNoFaceFromUnavailableDownload() = runBlocking {
        val availableModule = RecordingSafeShareFaceModule(listOf(true, true))
        val availableBoundary = SafeShareFaceModelBoundary(availableModule)

        availableBoundary.requireAvailable()
        availableBoundary.verifyEmptyResult(isEmpty = true)
        availableBoundary.verifyEmptyResult(isEmpty = false)

        assertTrue(buildSafeShareAnalysis(1, emptyList()).suggestions.isEmpty())
        assertEquals(listOf("available", "available"), availableModule.events)

        val downloadingModule = RecordingSafeShareFaceModule(listOf(true, false))
        val downloadingBoundary = SafeShareFaceModelBoundary(downloadingModule)

        downloadingBoundary.requireAvailable()
        val failure =
            expectSuspendFailure<SafeShareModelUnavailableException> {
                downloadingBoundary.verifyEmptyResult(isEmpty = true)
            }

        assertEquals(
            DocumentActionFailureKind.ModelUnavailable,
            documentActionFailureKind(failure),
        )
        assertEquals(listOf("available", "available", "deferred"), downloadingModule.events)
    }

    @Test
    fun playServicesFaceModuleWiresExactDetectorAndWaitsAvailabilityTerminalOnCancellation() =
        runBlocking {
            val availability = TaskCompletionSource<ModuleAvailabilityResponse>()
            val deferred = TaskCompletionSource<Void?>()
            var availabilityDetector: Any? = null
            var deferredDetector: Any? = null
            val detector =
                Proxy.newProxyInstance(
                    FaceDetector::class.java.classLoader,
                    arrayOf(FaceDetector::class.java),
                ) { _, method, _ ->
                    throw AssertionError("Unexpected FaceDetector call: ${method.name}")
                } as FaceDetector
            val client =
                Proxy.newProxyInstance(
                    ModuleInstallClient::class.java.classLoader,
                    arrayOf(ModuleInstallClient::class.java),
                ) { _, method, arguments ->
                    when (method.name) {
                        "areModulesAvailable" -> {
                            availabilityDetector = (arguments?.single() as Array<*>).single()
                            availability.task
                        }
                        "deferredInstall" -> {
                            deferredDetector = (arguments?.single() as Array<*>).single()
                            deferred.task
                        }
                        else -> throw AssertionError("Unexpected ModuleInstallClient call: ${method.name}")
                    }
                } as ModuleInstallClient
            val module = PlayServicesSafeShareFaceModule(client, detector)
            val availabilityJob =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    module.areModulesAvailable()
                }

            assertSame(detector, availabilityDetector)
            availabilityJob.cancel()
            assertFalse(availabilityJob.isCompleted)
            availability.setException(IOException("terminal availability failure"))
            availabilityJob.join()
            assertTrue(availabilityJob.isCancelled)

            val deferredJob =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    module.deferredInstall()
                }
            assertSame(detector, deferredDetector)
            deferred.setResult(null)
            deferredJob.join()
            assertFalse(deferredJob.isCancelled)
        }

    @Test
    fun taskAwaitKeepsOwnerAliveUntilTaskTerminalAfterCoroutineCancellation() = runBlocking {
        val completion = TaskCompletionSource<String>()
        var ownerReleased = false
        val job =
            launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    completion.task.awaitResult()
                } finally {
                    ownerReleased = true
                }
            }

        job.cancel()

        assertFalse(ownerReleased)
        assertFalse(job.isCompleted)
        completion.setResult("late result")
        job.join()
        assertTrue(ownerReleased)
        assertTrue(job.isCancelled)
    }

    @Test
    fun taskCancellationIsOrdinaryFailureWhileOwnerCoroutineIsActive() {
        val outcome =
            completedTaskResult(
                cancelled = true,
                successful = false,
                result = { error("Cancelled task has no result") },
                exception = { null },
            )

        assertTrue(outcome.exceptionOrNull() is IOException)
    }

    @Test
    fun faceModelBoundaryWaitsForDeferredInstallTerminalOnCancellation() = runBlocking {
        val deferred = TaskCompletionSource<Unit>()
        val module = RecordingSafeShareFaceModule(listOf(false), deferred.task)
        val boundary = SafeShareFaceModelBoundary(module)
        val job =
            launch(start = CoroutineStart.UNDISPATCHED) {
                boundary.requireAvailable()
            }

        assertEquals(listOf("available", "deferred"), module.events)
        job.cancel()
        assertFalse(job.isCompleted)
        deferred.setResult(Unit)
        job.join()
        assertTrue(job.isCancelled)
    }

    @Test
    fun safeShareSamplingNeverExceedsTwoMegapixels() {
        assertEquals(2, safeShareSampleSize(4_000, 2_000))
        assertEquals(4, safeShareSampleSize(6_000, 2_000))
        val sample = safeShareSampleSize(6_000, 2_000)
        assertTrue(
            sampledDimensionUpperBound(6_000, sample).toLong() *
                sampledDimensionUpperBound(2_000, sample) <=
                MAX_SAFE_SHARE_ANALYSIS_PIXELS,
        )
    }

    @Test
    fun supportedLargeScannerPagesUseBoundedAnalysisSamples() {
        listOf(3060 to 4080, 5092 to 7146, 16384 to 12288).forEach { (width, height) ->
            assertTrue(validDocumentActionSourceDimensions(width, height))

            val actionSample = documentActionSampleSize(width, height)
            assertTrue(
                sampledDimensionUpperBound(width, actionSample).toLong() *
                    sampledDimensionUpperBound(height, actionSample) <= MAX_IMAGE_EXPORT_PIXELS,
            )

            val safeShareSample = safeShareSampleSize(width, height)
            assertTrue(
                sampledDimensionUpperBound(width, safeShareSample).toLong() *
                    sampledDimensionUpperBound(height, safeShareSample) <=
                    MAX_SAFE_SHARE_ANALYSIS_PIXELS,
            )
        }
        assertEquals(128L * 1024L * 1024L, MAX_SCAN_SOURCE_PAGE_BYTES)
        assertFalse(validDocumentActionSourceDimensions(MAX_ORIGINAL_IMAGE_DIMENSION + 1, 1))
    }

    @Test
    fun findTextValidatesQueryAndMapsUnicodeMatchesToExactPages() {
        val snapshot =
            DocumentOcrSnapshot(
                pageTexts =
                    listOf(
                        "One ŽLUŤOUČKÝ two žluťoučký",
                        "žluťoučký",
                    ),
                elements = emptyList(),
                truncated = false,
            )

        assertEquals(
            listOf(
                TextMatch(page = 0, start = 4, endExclusive = 13),
                TextMatch(page = 0, start = 18, endExclusive = 27),
                TextMatch(page = 1, start = 0, endExclusive = 9),
            ),
            findText(snapshot, "Žluťoučký"),
        )
        assertEquals(
            listOf(TextMatch(0, 0, MAX_FIND_QUERY_CHARACTERS)),
            findText(
                DocumentOcrSnapshot(
                    listOf("a".repeat(MAX_FIND_QUERY_CHARACTERS)),
                    emptyList(),
                    false,
                ),
                "a".repeat(MAX_FIND_QUERY_CHARACTERS),
            ),
        )
        expectFailure<IllegalArgumentException> { findText(snapshot, "") }
        expectFailure<IllegalArgumentException> {
            findText(snapshot, "x".repeat(MAX_FIND_QUERY_CHARACTERS + 1))
        }
        expectFailure<IllegalArgumentException> { findText(snapshot, "\ud800") }
    }

    @Test
    fun findTextStopsAtTheBoundedMatchLimit() {
        val matches =
            findText(
                DocumentOcrSnapshot(listOf("a".repeat(2_000)), emptyList(), false),
                "a",
            )

        assertEquals(MAX_TEXT_MATCHES, matches.size)
        assertEquals(TextMatch(0, 0, 1), matches.first())
        assertEquals(TextMatch(0, 999, 1_000), matches.last())
    }

    @Test
    fun speechTextIsExplicitNonEmptyStrictUtf8AndBounded() {
        assertNull(validatedSpeechText(" "))
        assertNull(validatedSpeechText("\ud800"))
        assertEquals(
            "x".repeat(MAX_TTS_CHARACTERS),
            validatedSpeechText("x".repeat(MAX_TTS_CHARACTERS + 1)),
        )
        assertEquals(
            "x".repeat(MAX_TTS_CHARACTERS - 1),
            validatedSpeechText("x".repeat(MAX_TTS_CHARACTERS - 1) + "😀"),
        )
    }

    @Test
    fun systemActionBoundaryRevalidatesEveryTypedField() {
        val valid =
            listOf(
                DetectedCodeAction.OpenUrl("https://example.com/path"),
                DetectedCodeAction.Dial("+420123456789"),
                DetectedCodeAction.ComposeEmail("person@example.com", "Subject", "Body"),
                DetectedCodeAction.ComposeSms("+420123456789", "Message"),
                DetectedCodeAction.CreateContact(
                    "Person",
                    listOf("+420123456789"),
                    listOf("person@example.com"),
                ),
                DetectedCodeAction.CreateCalendarEvent("Meeting", 1L, 2L),
                DetectedCodeAction.OpenGeo(50.0, 14.0),
                DetectedCodeAction.OpenWifiSettings("School", "secret"),
            )
        assertEquals(valid, valid.mapNotNull(::validatedSystemAction))

        assertNull(validatedSystemAction(DetectedCodeAction.OpenUrl("javascript:alert(1)")))
        assertNull(
            validatedSystemAction(
                DetectedCodeAction.OpenUrl("https://user:password@example.com"),
            ),
        )
        assertNull(validatedSystemAction(DetectedCodeAction.Dial("not a phone")))
        assertNull(
            validatedSystemAction(
                DetectedCodeAction.ComposeEmail("not-an-email", null, null),
            ),
        )
        assertNull(validatedSystemAction(DetectedCodeAction.ComposeSms("123", null)))
        assertNull(
            validatedSystemAction(
                DetectedCodeAction.CreateContact(null, emptyList(), listOf("bad")),
            ),
        )
        assertNull(
            validatedSystemAction(
                DetectedCodeAction.CreateContact(
                    null,
                    List(4) { "+42012345678$it" },
                    emptyList(),
                ),
            ),
        )
        assertNull(
            validatedSystemAction(
                DetectedCodeAction.CreateCalendarEvent("Meeting", 2L, 1L),
            ),
        )
        assertNull(validatedSystemAction(DetectedCodeAction.OpenGeo(Double.NaN, 14.0)))
        assertNull(validatedSystemAction(DetectedCodeAction.OpenWifiSettings("", null)))
    }

    @Test
    fun emailActionsRequireConservativeLocalPartAtEveryBoundary() {
        val malformed =
            listOf(
                "a@b@example.com",
                "person name@example.com",
                "person\r\nBcc:other@example.com",
            )

        malformed.forEach { address ->
            assertNull(validatedEmailAction(address, null, null))
            assertNull(
                validatedSystemAction(
                    DetectedCodeAction.ComposeEmail(address, null, null),
                ),
            )
        }
        assertEquals(
            DetectedCodeAction.ComposeEmail("first.last+tag@example.com", null, null),
            validatedEmailAction("first.last+tag@example.com", null, null),
        )
    }

    @Test
    fun receiptCandidateActionsStayMechanicalAndValidated() {
        val bounds = NormalizedRect(0f, 0f, 1f, 1f)
        assertEquals(
            DetectedCodeAction.ComposeEmail("person@example.com", null, null),
            systemActionForCandidate(
                DocumentEntityCandidate(
                    0,
                    DocumentEntityKind.Email,
                    "person@example.com",
                    bounds,
                ),
            ),
        )
        assertEquals(
            DetectedCodeAction.Dial("+420 123 456 789"),
            systemActionForCandidate(
                DocumentEntityCandidate(0, DocumentEntityKind.Phone, "+420 123 456 789", bounds),
            ),
        )
        assertEquals(
            DetectedCodeAction.OpenUrl("https://example.com"),
            systemActionForCandidate(
                DocumentEntityCandidate(0, DocumentEntityKind.Url, "https://example.com", bounds),
            ),
        )
        assertNull(
            systemActionForCandidate(
                DocumentEntityCandidate(0, DocumentEntityKind.Money, "EUR 12.00", bounds),
            ),
        )
    }

    @Test
    fun clearedOcrOwnershipRejectsLateCompletionForEveryMutationBoundary() {
        val snapshot = DocumentOcrSnapshot(listOf("text"), emptyList(), false)
        val cacheId = "Scan_2026-08-18_12-00-00"
        val entryId = "4de93580-a259-4d3d-a9e0-225a6b150462"
        val boundaries =
            listOf(
                OcrSnapshotInvalidation.RouteMutation,
                OcrSnapshotInvalidation.ResultMutation,
                OcrSnapshotInvalidation.Cancellation,
                OcrSnapshotInvalidation.Replacement,
                OcrSnapshotInvalidation.Cleared,
            )
        assertEquals(boundaries, OcrSnapshotInvalidation.entries)

        boundaries.forEach { boundary ->
            val owner = OcrSnapshotOwner()
            val generation = owner.begin()
            assertTrue(owner.publish(generation, cacheId, entryId, OcrScript.Latin, snapshot))
            assertEquals(snapshot, owner.current(cacheId, entryId, OcrScript.Latin))

            owner.invalidate(boundary)

            assertNull(owner.current(cacheId, entryId, OcrScript.Latin))
            assertFalse(owner.publish(generation, cacheId, entryId, OcrScript.Latin, snapshot))
        }
    }

    @Test
    fun moneyCandidateMatchingBoundsLongGroupedNumberWork() {
        assertEquals(listOf("USD 12.34"), moneyEntityValues("USD 12.34").toList())
        assertTrue(moneyEntityValues("1".repeat(65) + " USD").none())
        assertTrue(moneyEntityValues("1".repeat(8_192)).none())
    }

    @Test
    fun financialCandidatesValidateFormatWithoutGuessingMeaning() {
        assertTrue(isValidIban("GB82 WEST 1234 5698 7654 32"))
        assertFalse(isValidIban("GB82 WEST 1234 5698 7654 31"))
        assertTrue(isLuhnValid("4111111111111111"))
        assertFalse(isLuhnValid("4111111111111112"))
    }

    @Test
    fun entityCandidatesUseExactElementBoundsAndMechanicalFormatsOnly() {
        val bounds = NormalizedRect(0.1f, 0.2f, 0.8f, 0.4f)
        val candidates =
            buildDocumentEntityCandidates(
                listOf(
                    OcrElement(0, "person@example.com", bounds),
                    OcrElement(0, "+420 123 456 789", bounds),
                    OcrElement(0, "https://example.com/invoice", bounds),
                    OcrElement(0, "GB82 WEST 1234 5698 7654 32", bounds),
                    OcrElement(0, "4111 1111 1111 1111", bounds),
                    OcrElement(0, "EUR 1,234.56", bounds),
                    OcrElement(0, "18.08.2026", bounds),
                ),
            )

        assertEquals(
            listOf(
                DocumentEntityKind.Email,
                DocumentEntityKind.Phone,
                DocumentEntityKind.Url,
                DocumentEntityKind.Iban,
                DocumentEntityKind.PaymentCard,
                DocumentEntityKind.Money,
                DocumentEntityKind.Date,
            ),
            candidates.map(DocumentEntityCandidate::kind),
        )
        assertTrue(candidates.all { it.bounds == bounds })
        assertEquals(
            listOf("Email", "Phone", "Url", "Iban", "PaymentCard", "Money", "Date"),
            DocumentEntityKind.entries.map(Enum<*>::name),
        )
    }

    @Test
    fun malformedTextDoesNotBecomeAnEntityCandidate() {
        val bounds = NormalizedRect(0f, 0f, 1f, 1f)

        assertTrue(
            buildDocumentEntityCandidates(
                listOf(
                    OcrElement(0, "person@example", bounds),
                    OcrElement(0, "javascript:alert(1)", bounds),
                    OcrElement(0, "123456", bounds),
                    OcrElement(0, "1234.56", bounds),
                    OcrElement(0, "31.02.2026", bounds),
                ),
            ).isEmpty(),
        )
    }

    @Test
    fun entityCandidatesDeduplicateAndCapEachPage() {
        val bounds = NormalizedRect(0f, 0f, 1f, 1f)
        val first = OcrElement(0, "first@example.com", bounds)
        val elements =
            buildList {
                add(first)
                add(first)
                repeat(300) { add(OcrElement(0, "person$it@example.com", bounds)) }
                add(OcrElement(1, "other@example.com", bounds))
            }

        val candidates = buildDocumentEntityCandidates(elements)

        assertEquals(MAX_DOCUMENT_ENTITY_CANDIDATES_PER_PAGE, candidates.count { it.page == 0 })
        assertEquals(1, candidates.count { it.page == 1 })
        assertEquals(1, candidates.count { it.value == "first@example.com" })
    }

    @Test
    fun ocrSnapshotRejectsUnboundedOrMismatchedContent() {
        val bounds = NormalizedRect(0f, 0f, 1f, 1f)
        assertEquals(
            MAX_SCAN_PAGES,
            DocumentOcrSnapshot(List(MAX_SCAN_PAGES) { "page" }, emptyList(), false)
                .pageTexts.size,
        )
        expectFailure<IllegalArgumentException> {
            DocumentOcrSnapshot(List(MAX_SCAN_PAGES + 1) { "page" }, emptyList(), false)
        }
        expectFailure<IllegalArgumentException> {
            DocumentOcrSnapshot(
                listOf("x".repeat(MAX_DOCUMENT_TEXT_CHARACTERS + 1)),
                emptyList(),
                true,
            )
        }
        expectFailure<IllegalArgumentException> {
            DocumentOcrSnapshot(listOf("page"), listOf(OcrElement(1, "text", bounds)), false)
        }
    }

    @Test
    fun normalizedRectRejectsNonFiniteOrInvertedCoordinates() {
        expectFailure<IllegalArgumentException> { NormalizedRect(Float.NaN, 0f, 1f, 1f) }
        expectFailure<IllegalArgumentException> { NormalizedRect(0.8f, 0f, 0.2f, 1f) }
        expectFailure<IllegalArgumentException> { NormalizedRect(-0.1f, 0f, 1f, 1f) }
        assertEquals(
            NormalizedRect(0.25f, 0.2f, 0.75f, 0.8f),
            normalizedRect(25, 10, 75, 40, 100, 50),
        )
        assertNull(normalizedRect(0, 0, 101, 50, 100, 50))
        assertNull(
            normalizedRect(
                Int.MAX_VALUE - 1,
                0,
                Int.MAX_VALUE,
                1,
                Int.MAX_VALUE,
                1,
            ),
        )
    }

    @Test
    fun typedWifiAndContactRequireBoundedFields() {
        assertNull(validatedWifiAction("", "secret"))
        assertEquals(
            DetectedCodeAction.OpenWifiSettings("School", "secret"),
            validatedWifiAction("School", "secret"),
        )
        assertNull(validatedWifiAction("s".repeat(257), null))
        assertNull(validatedWifiAction("School", "p".repeat(4_097)))
        assertNull(validatedContactAction(null, emptyList(), emptyList()))
        assertNull(validatedContactAction("n".repeat(257), emptyList(), emptyList()))
        assertNull(validatedContactAction(null, List(17) { "+420123456789" }, emptyList()))
        assertNull(validatedContactAction(null, listOf("\ud800"), emptyList()))
    }

    @Test
    fun typedActionsRejectInvalidFieldsInsteadOfInterpretingRawText() {
        assertEquals(
            DetectedCodeAction.OpenUrl("https://example.com/path"),
            validatedUrlAction("https://example.com/path"),
        )
        assertNull(validatedUrlAction("javascript:alert(1)"))
        assertEquals(DetectedCodeAction.Dial("+420123456789"), validatedDialAction("+420123456789"))
        assertNull(validatedDialAction("\ud800"))
        assertEquals(
            DetectedCodeAction.ComposeEmail("person@example.com", "Hello", "Body"),
            validatedEmailAction("person@example.com", "Hello", "Body"),
        )
        assertNull(validatedEmailAction("person@example.com", "s".repeat(257), null))
        assertNull(validatedSmsAction("+420123456789", "m".repeat(4_097)))
        assertEquals(
            DetectedCodeAction.CreateCalendarEvent(
                "Appointment",
                Instant.parse("2026-08-18T12:30:45Z").toEpochMilli(),
                null,
            ),
            validatedCalendarAction(
                "Appointment",
                Barcode.CalendarDateTime(2026, 8, 18, 12, 30, 45, true, "ignored"),
                null,
            ),
        )
        assertNull(validatedCalendarEventAction("Appointment", 2L, 1L))
        assertNull(
            validatedCalendarAction(
                "Appointment",
                Barcode.CalendarDateTime(
                    999_999_999,
                    12,
                    31,
                    23,
                    59,
                    59,
                    true,
                    "ignored",
                ),
                null,
            ),
        )
        assertEquals(DetectedCodeAction.OpenGeo(50.0, 14.0), validatedGeoAction(50.0, 14.0))
        assertNull(validatedGeoAction(Double.NaN, 14.0))
        assertNull(validatedGeoAction(91.0, 14.0))
    }

    @Test
    fun detectedCodeCarriesValidatedBoundsAndTypedAction() {
        val bounds = NormalizedRect(0.1f, 0.2f, 0.4f, 0.6f)

        assertEquals(
            DetectedCode(
                kind = DetectedCodeKind.QrCode,
                value = "TEL:+420123456789",
                bounds = bounds,
                action = DetectedCodeAction.Dial("+420123456789"),
            ),
            validatedDetectedCode(
                rawValue = "TEL:+420123456789",
                displayValue = "+420123456789",
                rawBytes = null,
                isQrCode = true,
                typedUrl = null,
                bounds = bounds,
                typedAction = DetectedCodeAction.Dial("+420123456789"),
            ),
        )
    }

    @Test
    fun emptyActionResultsStayEmpty() {
        assertEquals(
            DocumentActionOutput.Text(value = "", truncated = false),
            buildDocumentText(listOf(" ", "\n")),
        )
        assertTrue(buildDetectedCodes(emptySequence()).values.isEmpty())
    }

    @Test
    fun unavailablePlayServicesModelGetsHonestRetryMessage() {
        assertEquals(
            DocumentActionFailureKind.ModelUnavailable,
            documentActionFailureKindForMlKitError(MlKitException.UNAVAILABLE),
        )
        assertEquals(
            DocumentActionFailureKind.Failed,
            documentActionFailureKind(IOException("page unavailable")),
        )
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
        assertEquals("SeliaScan_text.txt", sanitizeTextExportFileName("../.."))
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
        assertFalse(
            request.matches(
                cacheId = request.cacheId,
                entryId = request.entryId,
                pageIndex = 2,
                action = DocumentAction.ExtractText,
                generation = 8L,
            ),
        )
        val findRequest = request.copy(action = DocumentAction.FindText, generation = 8L)
        assertEquals(
            findRequest,
            decodeDocumentActionRequest(encodeDocumentActionRequest(findRequest)),
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

    private suspend inline fun <reified T : Throwable> expectSuspendFailure(
        block: suspend () -> Unit,
    ): T {
        try {
            block()
        } catch (failure: Throwable) {
            if (failure is T) return failure
            throw failure
        }
        fail("Expected ${T::class.java.simpleName}")
        throw AssertionError("unreachable")
    }

    private class RecordingSafeShareFaceModule(
        availability: List<Boolean>,
        private val deferredTask: Task<Unit>? = null,
    ) : SafeShareFaceModule {
        private val availability = ArrayDeque(availability)
        val events = ArrayList<String>()

        override suspend fun areModulesAvailable(): Boolean {
            events += "available"
            return availability.removeFirst()
        }

        override suspend fun deferredInstall() {
            events += "deferred"
            deferredTask?.awaitResult()
        }
    }
}
