package com.majkeylab.scanit

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PureLogicTest {
    @Test
    fun appSettingsDefaultsAreStable() {
        assertEquals(
            AppSettings(
                savePdf = true,
                saveImages = true,
                albumName = "Scan to PDF",
                multipage = true,
                allowGallery = true,
                emailSubject = "Scanned document",
                emailBody = "",
                pdfTreeUri = null,
            ),
            AppSettings(),
        )
    }

    @Test
    fun pdfSaveFailureReportsSafAndProviderCleanupWarnings() {
        val warning = UiMessage(R.string.saf_cleanup_warning)
        val failure =
            PdfSaveFailure(
                warning,
                IOException("metadata write failed"),
                rollbackFailed = true,
            )

        assertEquals(
            listOf(
                UiMessage(R.string.pdf_save_failed),
                warning,
                UiMessage(R.string.document_save_partial_failed),
            ),
            pdfSaveFailureMessages(failure),
        )
    }

    @Test
    fun pdfSaveFailureOmitsPartialWarningAfterSuccessfulRollback() {
        val warning = UiMessage(R.string.saf_cleanup_warning)

        assertEquals(
            listOf(UiMessage(R.string.pdf_save_failed), warning),
            pdfSaveFailureMessages(PdfSaveFailure(warning, IOException("failed"))),
        )
    }

    @Test
    fun safCleanupWarningClaimsDownloadsOnlyAfterSuccessfulFallback() {
        assertEquals(
            null,
            safFallbackWarning(cleanupFailed = false, savedToDownloads = false),
        )
        assertEquals(
            UiMessage(R.string.saf_fallback_warning),
            safFallbackWarning(cleanupFailed = false, savedToDownloads = true),
        )
        assertEquals(
            UiMessage(R.string.saf_cleanup_warning),
            safFallbackWarning(cleanupFailed = true, savedToDownloads = false),
        )
        assertEquals(
            UiMessage(R.string.saf_incomplete_warning),
            safFallbackWarning(cleanupFailed = true, savedToDownloads = true),
        )
    }

    @Test
    fun pdfSaveFailureMessagesAreDeduplicated() {
        val warning = UiMessage(R.string.document_save_partial_failed)

        assertEquals(
            listOf(UiMessage(R.string.pdf_save_failed), warning),
            pdfSaveFailureMessages(
                PdfSaveFailure(warning, IOException("failed"), rollbackFailed = true),
            ),
        )
    }

    @Test
    fun pdfCurrentPendingRowCleanupFailureIsReported() {
        val pendingFailure =
            assertThrows(PendingMediaFailure::class.java) {
                pendingMediaWrite(rollback = { false }) {
                    throw IOException("copy failed")
                }
            }
        val warning = UiMessage(R.string.saf_cleanup_warning)

        assertEquals(
            listOf(
                UiMessage(R.string.pdf_save_failed),
                warning,
                UiMessage(R.string.document_save_partial_failed),
            ),
            pdfSaveFailureMessages(PdfSaveFailure(warning, pendingFailure)),
        )
    }

    @Test
    fun imageCurrentPendingRowCleanupFailureIsReported() {
        val pendingFailure =
            assertThrows(PendingMediaFailure::class.java) {
                pendingMediaWrite(rollback = { false }) {
                    throw IOException("publish failed")
                }
            }

        assertEquals(
            listOf(
                UiMessage(R.string.images_save_failed),
                UiMessage(R.string.document_save_partial_failed),
            ),
            imageSaveFailureMessages(ImageSaveFailure(pendingFailure)),
        )
    }

    @Test
    fun currentPendingRowCleanupSuccessDoesNotReportPartialOutput() {
        val pendingFailure =
            assertThrows(PendingMediaFailure::class.java) {
                pendingMediaWrite(rollback = { true }) {
                    throw IOException("publish failed")
                }
            }

        assertFalse(pendingFailure.rollbackFailed)
        assertEquals(
            listOf(UiMessage(R.string.images_save_failed)),
            imageSaveFailureMessages(ImageSaveFailure(pendingFailure)),
        )
    }

    @Test
    fun imagePriorRowCleanupFailureIsReported() {
        assertEquals(
            listOf(
                UiMessage(R.string.images_save_failed),
                UiMessage(R.string.document_save_partial_failed),
            ),
            imageSaveFailureMessages(
                ImageSaveFailure(IOException("metadata failed"), rollbackFailed = true),
            ),
        )
    }

    @Test
    fun pendingMediaCancellationCleanupFailureIsSuppressedAndRethrown() {
        val cancellation = CancellationException("cancelled")
        val cleanupFailure = IOException("cleanup failed")

        val thrown =
            assertThrows(CancellationException::class.java) {
                pendingMediaWrite(
                    rollback = { failure ->
                        failure.addSuppressed(cleanupFailure)
                        false
                    },
                ) {
                    throw cancellation
                }
            }

        assertSame(cancellation, thrown)
        assertEquals(listOf(cleanupFailure), thrown.suppressed.toList())
    }

    @Test
    fun localizedDefaultEmailSubjectOnlyReplacesSupportedDefaults() {
        val defaults = setOf("Scanned document", "Naskenovaný dokument")

        assertEquals(
            "Scanned document",
            localizedDefaultEmailSubject("Naskenovaný dokument", "Scanned document", defaults),
        )
        assertEquals(
            "Naskenovaný dokument",
            localizedDefaultEmailSubject("Naskenovaný dokument", "Naskenovaný dokument", defaults),
        )
        assertEquals(
            "Invoice 42",
            localizedDefaultEmailSubject("Invoice 42", "Scanned document", defaults),
        )
        assertEquals("", localizedDefaultEmailSubject("", "Scanned document", defaults))
    }

    @Test
    fun scanBaseNameUsesSuppliedClock() {
        val clock = Clock.fixed(Instant.parse("2026-08-06T12:34:56Z"), ZoneOffset.UTC)

        assertEquals("Scan_2026-08-06_12-34-56", scanBaseName(clock))
    }

    @Test
    fun scannerPageLimitOnlyDisablesMultipageCapture() {
        assertEquals(null, scannerPageLimit(multipage = true))
        assertEquals(1, scannerPageLimit(multipage = false))
    }

    @Test
    fun scannerLaunchGateResumesPreparationAndRejectsItsStaleCallback() {
        val gate = ScannerLaunchGate()
        val original = gate.begin(processing = false)!!
        val resumed = gate.resumePreparing(processing = false)!!

        assertEquals(ScannerLaunchStage.Preparing, gate.stage)
        assertEquals(true, resumed > original)
        assertEquals(false, gate.fail(original))
        assertEquals(false, gate.markLaunched(original))
        assertEquals(true, gate.markLaunched(resumed))
        assertEquals(ScannerLaunchStage.Launched, gate.stage)
        assertEquals(null, gate.resumePreparing(processing = false))
    }

    @Test
    fun scannerLaunchGateBlocksNewScannerWhileStorageIsProcessing() {
        val gate = ScannerLaunchGate()

        assertEquals(null, gate.begin(processing = true))
        assertEquals(ScannerLaunchStage.Idle, gate.stage)
    }

    @Test
    fun scannerLaunchFailureResetsAndInvalidatesItsRequest() {
        val gate = ScannerLaunchGate()
        val failed = gate.begin(processing = false)!!

        assertEquals(true, gate.fail(failed))
        assertEquals(ScannerLaunchStage.Idle, gate.stage)
        assertEquals(false, gate.markLaunched(failed))
        assertEquals(true, gate.begin(processing = false)!! > failed)
    }

    @Test
    fun completingScannerPreparationInvalidatesItsCallback() {
        val gate = ScannerLaunchGate()
        val preparing = gate.begin(processing = false)!!

        gate.complete()

        assertEquals(ScannerLaunchStage.Idle, gate.stage)
        assertEquals(false, gate.markLaunched(preparing))
        assertEquals(false, gate.fail(preparing))
    }

    @Test
    fun recentActionGateRejectsShareAfterLaterAction() {
        val gate = RecentActionGate()
        val share = gate.begin("Scan_1")

        gate.invalidate()

        assertEquals(false, gate.isCurrent(share, listOf("Scan_1")))
    }

    @Test
    fun recentActionGateRequiresCurrentRowIdentity() {
        val gate = RecentActionGate()
        val share = gate.begin("Scan_1")

        assertEquals(true, gate.isCurrent(share, listOf("Scan_1", "Scan_2")))
        assertEquals(false, gate.isCurrent(share, listOf("Scan_2")))
    }

    @Test
    fun invalidatedRecentDeletionCannotChangeTheNewScreen() {
        val gate = RecentDeletionGate()
        val deletion = gate.begin("Scan_1")

        gate.invalidateCurrent()

        assertEquals(false, gate.isCurrent(deletion))
        gate.complete(deletion)
    }

    @Test
    fun completingEarlierDeletionKeepsLaterDeletionCurrent() {
        val gate = RecentDeletionGate()
        val earlier = gate.begin("Scan_1")
        val later = gate.begin("Scan_2")

        gate.complete(earlier)

        assertEquals(true, gate.isCurrent(later))
    }

    @Test
    fun routeMutationGateRejectsEveryStaleCompletion() {
        val gate = RouteMutationGate()
        val first = gate.begin()
        val second = gate.begin()

        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))

        val third = gate.begin()

        assertFalse(gate.isCurrent(second))
        assertTrue(gate.isCurrent(third))
    }

    @Test
    fun saveNowTargetMatrixOnlyOffersMissingCompleteOutputs() {
        assertEquals(
            listOf(SaveNowTarget.Pdf, SaveNowTarget.Images, SaveNowTarget.Both),
            saveNowTargets(pdfMissing = true, savedImageCount = 0, pageCount = 2),
        )
        assertEquals(
            listOf(SaveNowTarget.Images),
            saveNowTargets(pdfMissing = false, savedImageCount = 0, pageCount = 2),
        )
        assertEquals(
            listOf(SaveNowTarget.Pdf),
            saveNowTargets(pdfMissing = true, savedImageCount = 2, pageCount = 2),
        )
        assertEquals(
            emptyList<SaveNowTarget>(),
            saveNowTargets(pdfMissing = false, savedImageCount = 2, pageCount = 2),
        )
    }

    @Test
    fun partialImagesBlockImageTargetsButLeaveIndependentPdfAvailable() {
        assertEquals(
            listOf(SaveNowTarget.Pdf),
            saveNowTargets(pdfMissing = true, savedImageCount = 1, pageCount = 2),
        )
        assertEquals(
            emptyList<SaveNowTarget>(),
            saveNowTargets(pdfMissing = false, savedImageCount = 1, pageCount = 2),
        )
        assertEquals(
            emptyList<SaveNowTarget>(),
            saveNowTargets(pdfMissing = true, savedImageCount = 3, pageCount = 2),
        )
    }

    @Test
    fun saveNowTargetsFailClosedForLegacyOrUnreadableOutputMetadata() {
        val legacy = savedScan(entryId = null, outputMetadataValid = false)
        val unreadable = savedScan(entryId = ENTRY_ID, outputMetadataValid = false)

        assertEquals(emptyList<SaveNowTarget>(), saveNowTargets(legacy))
        assertEquals(emptyList<SaveNowTarget>(), saveNowTargets(unreadable))
    }

    @Test
    fun storagePolicyAllowsPdfButRejectsImageCreationForPartialMetadata() {
        val partial =
            outputMetadata(images = listOf(ImageOutputRef(1, "content://media/images/1")))

        assertSame(partial, matchingOutputMetadata(partial, CACHE_ID, ENTRY_ID))
        assertNull(matchingOutputMetadata(partial, "Scan_other", ENTRY_ID))
        assertNull(matchingOutputMetadata(partial, CACHE_ID, OTHER_ENTRY_ID))
        assertThrows(IOException::class.java) {
            existingCompleteImagesForSave(partial, pageCount = 2)
        }
        assertNull(existingCompleteImagesForSave(outputMetadata(), pageCount = 2))
        assertEquals(
            completeImageRefs(),
            existingCompleteImagesForSave(
                outputMetadata(images = completeImageRefs()),
                pageCount = 2,
            ),
        )
    }

    @Test
    fun saveNowGateRejectsDoubleTapAndStaleResultCompletion() {
        val gate = ResultSaveGate()
        val action = gate.begin(CACHE_ID, ENTRY_ID)!!

        assertNull(gate.begin(CACHE_ID, ENTRY_ID))
        assertTrue(gate.isCurrent(action, CACHE_ID, ENTRY_ID))
        assertFalse(gate.isCurrent(action, CACHE_ID, OTHER_ENTRY_ID))

        gate.invalidate()

        assertFalse(gate.isCurrent(action, CACHE_ID, ENTRY_ID))
        assertTrue(gate.begin(CACHE_ID, ENTRY_ID)!!.generation > action.generation)
    }

    @Test
    fun saveNowWarningMergeClearsOnlySuccessfulFormatFailure() {
        val unrelated = UiMessage(R.string.saf_cleanup_warning)
        val existing =
            listOf(
                UiMessage(R.string.pdf_save_failed),
                UiMessage(R.string.images_save_failed),
                UiMessage(R.string.document_save_partial_failed),
                unrelated,
            )

        assertEquals(
            listOf(
                UiMessage(R.string.images_save_failed),
                UiMessage(R.string.document_save_partial_failed),
                unrelated,
                UiMessage(R.string.saf_fallback_warning),
            ),
            mergeSaveNowWarnings(
                existing,
                successful = setOf(SavedOutputKind.Pdf),
                reloadSucceeded = false,
                added = listOf(UiMessage(R.string.saf_fallback_warning)),
            ),
        )
        assertEquals(
            existing,
            mergeSaveNowWarnings(
                existing,
                successful = emptySet(),
                reloadSucceeded = false,
                added = emptyList(),
            ),
        )
    }

    @Test
    fun exactReloadClearsStaleStateFailureAndRejectsAnotherGeneration() {
        val action = ResultSaveAction(CACHE_ID, ENTRY_ID, generation = 1L)
        val exact = savedScan(entryId = ENTRY_ID, outputMetadataValid = true)
        val replacement = savedScan(entryId = OTHER_ENTRY_ID, outputMetadataValid = true)

        assertSame(exact, matchingSavedScan(exact, action))
        assertNull(matchingSavedScan(replacement, action))
        assertEquals(
            emptyList<UiMessage>(),
            mergeSaveNowWarnings(
                existing =
                    listOf(
                        UiMessage(R.string.state_update_failed),
                        UiMessage(R.string.pdf_save_failed),
                    ),
                successful = setOf(SavedOutputKind.Pdf),
                reloadSucceeded = true,
                added = emptyList(),
            ),
        )
    }

    @Test
    fun backNavigationIsConsumedWhileResultOutputIsSaving() {
        val result =
            ScreenState.Result(
                scan =
                    SavedScan(
                        cached = CachedScan("Scan_1", emptyList(), File("Scan_1.pdf")),
                        galleryPages = emptyList(),
                        savedPdf = null,
                    ),
                thumbnail = null,
                outputSaveInProgress = true,
            )

        assertEquals(
            AppBackAction.Consume,
            appBackAction(settingsOpen = false, fileDetailsOpen = true, state = result),
        )
    }

    @Test
    fun restoredPreparingScannerLaunchCanResume() {
        val gate = ScannerLaunchGate(ScannerLaunchStage.Preparing)

        assertEquals(true, gate.resumePreparing(processing = false) != null)
        assertEquals(ScannerLaunchStage.Preparing, gate.stage)
    }

    @Test
    fun restoredLaunchedScannerWaitsForResultUntilCompleted() {
        val gate = ScannerLaunchGate(ScannerLaunchStage.Launched)

        assertEquals(null, gate.begin(processing = false))
        assertEquals(null, gate.resumePreparing(processing = false))
        gate.complete()
        assertEquals(ScannerLaunchStage.Idle, gate.stage)
        assertEquals(true, gate.begin(processing = false) != null)
    }

    @Test
    fun missingSavedRouteKeepsFreshLaunchScanFirst() {
        assertEquals(RestoredRoute.Scanner, restoredRoute(null, null))
    }

    @Test
    fun savedScannerRouteResumesScannerFlow() {
        assertEquals(RestoredRoute.Scanner, restoredRoute("scanner", null))
    }

    @Test
    fun malformedSavedRouteFallsBackToRecentWithoutLaunchingScanner() {
        assertEquals(RestoredRoute.Recent, restoredRoute("unknown", "Scan_1"))
    }

    @Test
    fun savedResultRouteRequiresItsCacheId() {
        assertEquals("Result", restoredRoute("result", "Scan_1").name)
        assertEquals(RestoredRoute.Recent, restoredRoute("result", null))
        assertEquals(RestoredRoute.Recent, restoredRoute("result", ""))
    }

    @Test
    fun savedResultStartsInNonInteractiveProcessingState() {
        assertEquals(
            ScreenState.Processing(
                UiMessage(R.string.opening_document),
                canNavigateBack = false,
            ),
            initialScreenState(RestoredRoute.Result),
        )
        assertEquals(ScreenState.Recent(emptyList()), initialScreenState(RestoredRoute.Recent))
    }

    @Test
    fun coldNavigationStartsNonInteractiveUntilCheckpointPolicyResolves() {
        assertEquals(
            ScreenState.Processing(
                UiMessage(R.string.starting_scanit),
                canNavigateBack = false,
            ),
            initialScreenState(null),
        )
    }

    @Test
    fun activeResultCheckpointRoundTripsOnlySafeVersionedCacheId() {
        val cacheId = "Scan_2026-08-09_13-47-50"

        assertEquals(cacheId, decodeActiveResultCheckpoint(encodeActiveResultCheckpoint(cacheId)))
        assertNull(decodeActiveResultCheckpoint(null))
        assertNull(decodeActiveResultCheckpoint(""))
        assertNull(decodeActiveResultCheckpoint("2:$cacheId"))
        assertNull(decodeActiveResultCheckpoint("1:../outside"))
        assertNull(decodeActiveResultCheckpoint("1:..\\outside"))
        assertNull(decodeActiveResultCheckpoint("1:.hidden"))
        assertNull(decodeActiveResultCheckpoint("1:C:outside"))
        assertNull(decodeActiveResultCheckpoint("1:Scan\u0000outside"))
        assertNull(decodeActiveResultCheckpoint("1:${"a".repeat(129)}"))
        assertThrows(IllegalArgumentException::class.java) {
            encodeActiveResultCheckpoint("../outside")
        }
    }

    @Test
    fun durableActiveResultCheckpointPrecedesSavedRoute() {
        assertEquals(
            InitialNavigation(RestoredRoute.Result, "Scan_durable"),
            initialNavigation(
                savedRoute = "result",
                savedCacheId = "Scan_saved",
                activeResultCacheId = "Scan_durable",
            ),
        )
        assertEquals(
            InitialNavigation(RestoredRoute.Result, "Scan_durable"),
            initialNavigation(
                savedRoute = "recent",
                savedCacheId = null,
                activeResultCacheId = "Scan_durable",
            ),
        )
        assertEquals(
            InitialNavigation(RestoredRoute.Result, "Scan_durable"),
            initialNavigation("scanner", null, "Scan_durable"),
        )
    }

    @Test
    fun absentCheckpointCannotRecreateSavedResult() {
        assertEquals(
            InitialNavigation(RestoredRoute.Recent, null),
            initialNavigation("result", "Scan_saved", null),
        )
        assertEquals(
            InitialNavigation(RestoredRoute.Recent, null),
            initialNavigation("result", null, null),
        )
    }

    @Test
    fun coldNavigationUsesOnlyValidDurableActiveResultCheckpoint() {
        assertEquals(
            InitialNavigation(RestoredRoute.Result, "Scan_durable"),
            initialNavigation(null, null, "Scan_durable"),
        )
        assertEquals(
            InitialNavigation(RestoredRoute.Scanner, null),
            initialNavigation(null, null, null),
        )
        assertEquals(
            InitialNavigation(RestoredRoute.Scanner, null),
            initialNavigation(null, null, "../outside"),
        )
        assertEquals(
            InitialNavigation(RestoredRoute.Scanner, null),
            initialNavigation(null, null, "a".repeat(129)),
        )
    }

    @Test
    fun scannerPreparationResumeUsesCurrentPersistedRoute() {
        assertFalse(scannerPreparationMayResume(false, "scanner"))
        assertTrue(scannerPreparationMayResume(true, "scanner"))
        assertFalse(scannerPreparationMayResume(true, "result"))
        assertFalse(scannerPreparationMayResume(true, "recent"))
        assertFalse(scannerPreparationMayResume(true, null))
    }

    @Test
    fun backNavigationUsesScannerRecentResultMap() {
        val result =
            ScreenState.Result(
                scan =
                    SavedScan(
                        cached = CachedScan("Scan_1", emptyList(), File("Scan_1.pdf")),
                        galleryPages = emptyList(),
                        savedPdf = null,
                    ),
                thumbnail = null,
            )

        assertEquals(
            AppBackAction.ShowRecent,
            appBackAction(settingsOpen = false, fileDetailsOpen = false, state = result),
        )
        assertEquals(
            AppBackAction.LaunchScanner,
            appBackAction(
                settingsOpen = false,
                fileDetailsOpen = false,
                state = ScreenState.Recent(emptyList()),
            ),
        )
    }

    @Test
    fun backNavigationClosesOverlaysBeforeChangingResultRoute() {
        val result =
            ScreenState.Result(
                scan =
                    SavedScan(
                        cached = CachedScan("Scan_1", emptyList(), File("Scan_1.pdf")),
                        galleryPages = emptyList(),
                        savedPdf = null,
                    ),
                thumbnail = null,
            )

        assertEquals(
            AppBackAction.CloseSettings,
            appBackAction(settingsOpen = true, fileDetailsOpen = true, state = result),
        )
        assertEquals(
            AppBackAction.CollapseFileDetails,
            appBackAction(settingsOpen = false, fileDetailsOpen = true, state = result),
        )
    }

    @Test
    fun backNavigationConsumesReadyAndProcessingStates() {
        assertEquals(
            AppBackAction.ShowRecent,
            appBackAction(
                settingsOpen = false,
                fileDetailsOpen = false,
                state = ScreenState.Ready,
            ),
        )
        assertEquals(
            AppBackAction.ShowRecent,
            appBackAction(
                settingsOpen = false,
                fileDetailsOpen = false,
                state =
                    ScreenState.Processing(
                        UiMessage(R.string.opening_document),
                        canNavigateBack = true,
                    ),
            ),
        )
    }

    @Test
    fun backNavigationConsumesNonCancellableProcessingWithoutChangingRoute() {
        assertEquals(
            AppBackAction.Consume,
            appBackAction(
                settingsOpen = false,
                fileDetailsOpen = false,
                state =
                    ScreenState.Processing(
                        UiMessage(R.string.opening_document),
                        canNavigateBack = false,
                    ),
            ),
        )
    }

    @Test
    fun obsoleteBackStackRoutesFallBackToRecent() {
        assertEquals(RestoredRoute.Recent, restoredRoute("recent_with_result_back", "Scan_1"))
        assertEquals(RestoredRoute.Recent, restoredRoute("recent_with_result_back", null))
        assertEquals(RestoredRoute.Recent, restoredRoute("result_with_recent_back", "Scan_1"))
        assertEquals(RestoredRoute.Recent, restoredRoute("result_with_recent_back", null))
    }

    @Test
    fun albumNameIsTrimmedAndBlankFallsBack() {
        assertEquals("Rodinné skeny", normalizeAlbumName("  Rodinné skeny  "))
        assertEquals("Scan to PDF", normalizeAlbumName(" \t "))
    }

    @Test
    fun albumNameRejectsPathSeparators() {
        assertEquals("Scan to PDF", normalizeAlbumName("Skeny/2026"))
        assertEquals("Scan to PDF", normalizeAlbumName("Skeny\\2026"))
    }

    @Test
    fun albumNameRejectsDotPathComponents() {
        assertEquals("Scan to PDF", normalizeAlbumName(" . "))
        assertEquals("Scan to PDF", normalizeAlbumName(".."))
    }

    @Test
    fun albumNameRejectsIsoControlCharacters() {
        assertEquals("Scan to PDF", normalizeAlbumName("\u0000Skeny"))
        assertEquals("Scan to PDF", normalizeAlbumName("Skeny\n"))
        assertEquals("Scan to PDF", normalizeAlbumName("Sken\u0085y"))
    }

    @Test
    fun albumNameIsBoundedTo64Characters() {
        assertEquals("a".repeat(64), normalizeAlbumName("a".repeat(65)))
    }

    @Test
    fun preferenceReadReturnsStoredValue() {
        assertEquals("stored", readPreferenceOrDefault("default") { "stored" })
    }

    @Test
    fun preferenceReadFallsBackForWrongType() {
        assertEquals(true, readPreferenceOrDefault(true) { throw ClassCastException("wrong type") })
    }

    @Test
    fun scanFileNamesUseStableOriginalNames() {
        val baseName = "Scan_2026-08-06_12-34-56"

        assertEquals("${baseName}_01.jpg", scanPageFileName(baseName, 1))
        assertEquals("${baseName}_12.jpg", scanPageFileName(baseName, 12))
        assertEquals("$baseName.pdf", scanPdfFileName(baseName))
    }

    @Test
    fun scanPageFileNameRejectsInvalidPageNumber() {
        assertThrows(IllegalArgumentException::class.java) {
            scanPageFileName("Scan", 0)
        }
    }

    @Test
    fun portraitBitmapIsCenteredInsideA4WithoutCropping() {
        val placement = fitRect(1000, 2000, 595, 842)

        assertEquals(87f, placement.left, 0.001f)
        assertEquals(0f, placement.top, 0.001f)
        assertEquals(508f, placement.right, 0.001f)
        assertEquals(842f, placement.bottom, 0.001f)
    }

    @Test
    fun landscapeBitmapIsCenteredInsideA4WithoutCropping() {
        val placement = fitRect(2000, 1000, 595, 842)

        assertEquals(0f, placement.left, 0.001f)
        assertEquals(272.25f, placement.top, 0.001f)
        assertEquals(595f, placement.right, 0.001f)
        assertEquals(569.75f, placement.bottom, 0.001f)
    }

    @Test
    fun fitRectRejectsInvalidDimensions() {
        assertThrows(IllegalArgumentException::class.java) {
            fitRect(0, 1000, 595, 842)
        }
        assertThrows(IllegalArgumentException::class.java) {
            fitRect(1000, 1000, -1, 842)
        }
    }

    @Test
    fun thumbnailSampleSizeIsPowerOfTwoAndBoundsTheLongestSide() {
        assertEquals(1, thumbnailSampleSize(800, 600, 1024))
        assertEquals(4, thumbnailSampleSize(4096, 3072, 1024))
        assertEquals(8, thumbnailSampleSize(4097, 1, 1024))
    }

    @Test
    fun thumbnailSampleSizeRejectsInvalidDimensions() {
        assertThrows(IllegalArgumentException::class.java) {
            thumbnailSampleSize(0, 600, 1024)
        }
        assertThrows(IllegalArgumentException::class.java) {
            thumbnailSampleSize(800, 600, 0)
        }
    }

    @Test
    fun pdfPageSamplingReusesTheBoundedPowerOfTwoCalculation() {
        assertEquals(1, pdfPageSampleSize(2480, 3508))
        assertEquals(2, pdfPageSampleSize(3509, 2480))
        assertEquals(4, pdfPageSampleSize(7017, 4961))
    }

    @Test
    fun shareCachePruningKeepsNewestEntries() {
        val root = Files.createTempDirectory("scan-share-cache").toFile()
        try {
            val now = System.currentTimeMillis()
            val old = File(root, "old").apply { assertTrue(mkdir()) }
            val middle = File(root, "middle").apply { assertTrue(mkdir()) }
            val newest = File(root, "newest").apply { assertTrue(mkdir()) }
            assertTrue(old.setLastModified(now - 2_000L))
            assertTrue(middle.setLastModified(now - 1_000L))
            assertTrue(newest.setLastModified(now))

            assertEquals(
                listOf("old"),
                shareCacheEntriesToPrune(listOf(newest, old, middle), keep = 2)
                    .map { it.name },
            )
            assertEquals(emptyList<File>(), shareCacheEntriesToPrune(listOf(old), keep = 2))
        } finally {
            assertTrue(root.deleteRecursively())
        }
    }

    @Test
    fun printPageSelectionReturnsOnlyRequestedPages() {
        assertEquals(listOf(1, 2, 4), requestedPageIndexes(5, listOf(1..2, 4..4)))
    }

    @Test
    fun printPageSelectionClipsOverlapsAndOutOfBoundsRanges() {
        assertEquals(listOf(0, 1, 2), requestedPageIndexes(3, listOf(-2..1, 1..8)))
    }

    @Test
    fun printPageSelectionHandlesNoRequestedPages() {
        assertEquals(emptyList<Int>(), requestedPageIndexes(3, emptyList()))
        assertEquals(emptyList<Int>(), requestedPageIndexes(0, listOf(0..5)))
        assertThrows(IllegalArgumentException::class.java) {
            requestedPageIndexes(-1, listOf(0..1))
        }
    }

    private fun outputMetadata(
        images: List<ImageOutputRef> = emptyList(),
    ): OutputMetadata =
        OutputMetadata(
            entryId = ENTRY_ID,
            cacheId = CACHE_ID,
            createdAtEpochMs = 1L,
            images = images,
        )

    private fun completeImageRefs(): List<ImageOutputRef> =
        listOf(
            ImageOutputRef(1, "content://media/images/1"),
            ImageOutputRef(2, "content://media/images/2"),
        )

    private fun savedScan(
        entryId: String?,
        outputMetadataValid: Boolean,
    ): SavedScan =
        SavedScan(
            cached =
                CachedScan(
                    baseName = CACHE_ID,
                    pages = listOf(File("page-1.jpg"), File("page-2.jpg")),
                    pdf = File("scan.pdf"),
                    entryId = entryId,
                ),
            galleryPages = emptyList(),
            savedPdf = null,
            outputMetadataValid = outputMetadataValid,
        )

    private companion object {
        const val CACHE_ID = "Scan_2026-08-09_12-12-00"
        const val ENTRY_ID = "00000000-0000-0000-0000-000000000001"
        const val OTHER_ENTRY_ID = "00000000-0000-0000-0000-000000000002"
    }

}
