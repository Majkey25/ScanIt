package com.majkeylab.scanit

import android.content.Intent
import android.content.SharedPreferences
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
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
    fun redactionModesDeclareWhetherAnalysisIsRequired() {
        assertTrue(RedactionMode.Automatic.requiresAnalysis)
        assertFalse(RedactionMode.Manual.requiresAnalysis)
    }

    @Test
    fun manualRedactionStartsInReviewWithoutAnalysis() {
        assertEquals(false, RedactionMode.Manual.requiresAnalysis)
        assertEquals(true, RedactionMode.Automatic.requiresAnalysis)
        assertEquals(
            SafeShareState.Reviewing(
                page = 2,
                regions = emptyList(),
                mode = RedactionMode.Manual,
            ),
            initialRedactionReview(RedactionMode.Manual, selectedPage = 2),
        )
        assertEquals(null, initialRedactionReview(RedactionMode.Automatic, selectedPage = 2))
    }

    @Test
    fun safeShareRequestIdentityIncludesRedactionMode() {
        val request =
            SafeShareRequest(
                cacheId = CACHE_ID,
                entryId = ENTRY_ID,
                scope = SafeShareScope.SelectedPage,
                selectedPage = 1,
                generation = 8L,
                mode = RedactionMode.Manual,
            )

        assertTrue(
            request.matches(
                CACHE_ID,
                ENTRY_ID,
                SafeShareScope.SelectedPage,
                selectedPage = 1,
                generation = 8L,
                mode = RedactionMode.Manual,
            ),
        )
        assertFalse(
            request.matches(
                CACHE_ID,
                ENTRY_ID,
                SafeShareScope.SelectedPage,
                selectedPage = 1,
                generation = 8L,
                mode = RedactionMode.Automatic,
            ),
        )
    }

    @Test
    fun cleanWhiteboardApplyConsumesBackAndCannotBeDismissed() {
        val applying = DocumentActionState.Processing(DocumentAction.CleanWhiteboard)
        val result =
            ScreenState.Result(
                scan = savedScan(entryId = ENTRY_ID, outputMetadataValid = true),
                thumbnail = null,
                documentActionState = applying,
            )

        assertEquals(
            AppBackAction.Consume,
            appBackAction(settingsOpen = false, fileDetailsOpen = false, state = result),
        )
        assertFalse(documentActionDismissAllowed(applying))
        assertTrue(
            documentActionDismissAllowed(
                DocumentActionState.Completed(
                    DocumentActionOutput.FindReady,
                    action = DocumentAction.CleanWhiteboard,
                ),
            ),
        )
    }

    @Test
    fun cleanWhiteboardScopeBindsExactPageGenerationAndVariantPlan() {
        val selected =
            CleanWhiteboardRequest(
                cacheId = CACHE_ID,
                entryId = ENTRY_ID,
                scope = SafeShareScope.SelectedPage,
                selectedPage = 1,
                generation = 7L,
            )

        assertTrue(
            selected.matches(
                CACHE_ID,
                ENTRY_ID,
                SafeShareScope.SelectedPage,
                selectedPage = 1,
                generation = 7L,
            ),
        )
        assertFalse(
            selected.matches(
                CACHE_ID,
                ENTRY_ID,
                SafeShareScope.AllPages,
                selectedPage = 1,
                generation = 7L,
            ),
        )
        assertFalse(
            selected.matches(
                CACHE_ID,
                ENTRY_ID,
                SafeShareScope.SelectedPage,
                selectedPage = 0,
                generation = 7L,
            ),
        )
        assertFalse(
            selected.matches(
                CACHE_ID,
                ENTRY_ID,
                SafeShareScope.SelectedPage,
                selectedPage = 1,
                generation = 8L,
            ),
        )
        assertEquals(
            listOf(
                AppearanceVariantPagePlan(applyToSource = false, applyToRendered = false),
                AppearanceVariantPagePlan(applyToSource = true, applyToRendered = false),
                AppearanceVariantPagePlan(applyToSource = false, applyToRendered = false),
            ),
            appearanceVariantPagePlan(pageCount = 3, selectedPageIndex = 1),
        )
        assertEquals(
            List(3) {
                AppearanceVariantPagePlan(applyToSource = false, applyToRendered = true)
            },
            appearanceVariantPagePlan(pageCount = 3, selectedPageIndex = null),
        )
    }

    @Test
    fun documentActionsUseTheFinalGroupedInventory() {
        val inventory = documentActionInventory()

        assertEquals(
            listOf(
                DocumentActionSectionTitle.Read,
                DocumentActionSectionTitle.Use,
                DocumentActionSectionTitle.Protect,
                DocumentActionSectionTitle.Improve,
            ),
            inventory.map(DocumentActionSection::title),
        )
        assertEquals(
            listOf(
                DocumentAction.ExtractText,
                DocumentAction.FindText,
                DocumentAction.ReadAloud,
                DocumentAction.DetectCodes,
                DocumentAction.ReceiptDetails,
                DocumentAction.CreateContact,
                DocumentAction.SafeShare,
                DocumentAction.RedactDocument,
                DocumentAction.CleanWhiteboard,
                DocumentAction.ManualCleanup,
            ),
            inventory.flatMap(DocumentActionSection::actions),
        )
    }

    @Test
    fun documentActionsHaveDistinctIcons() {
        val actions = documentActionInventory().flatMap(DocumentActionSection::actions)
        val icons = actions.map(::documentActionIcon)

        assertEquals(actions.size, icons.distinct().size)
    }

    @Test
    fun documentActionsExposeAutomaticAndManualProtection() {
        assertEquals(
            listOf(DocumentAction.SafeShare, DocumentAction.RedactDocument),
            documentActionInventory()
                .single { it.title == DocumentActionSectionTitle.Protect }
                .actions,
        )
    }

    @Test
    fun documentActionsExposeAutomaticAndManualCleanupSeparately() {
        assertEquals(
            listOf(
                DocumentAction.CleanWhiteboard,
                DocumentAction.ManualCleanup,
            ),
            documentActionInventory()
                .single { it.title == DocumentActionSectionTitle.Improve }
                .actions,
        )
    }

    @Test
    fun safeShareSelectedPageScopeExcludesOtherPages() {
        val analysis =
            SafeShareAnalysis(
                pageCount = 2,
                suggestions =
                    listOf(
                        RedactionSuggestion(
                            0,
                            SensitiveRegionKind.Email,
                            NormalizedRect(0.1f, 0.1f, 0.2f, 0.2f),
                        ),
                        RedactionSuggestion(
                            1,
                            SensitiveRegionKind.Face,
                            NormalizedRect(0.3f, 0.3f, 0.5f, 0.6f),
                        ),
                    ),
            )

        val regions = safeShareRegions(analysis, SafeShareScope.SelectedPage, selectedPage = 1)

        assertEquals(1, regions.size)
        assertEquals(1, regions.single().page)
        assertEquals(SensitiveRegionKind.Face, regions.single().kind)
        assertTrue(regions.single().selected)
    }

    @Test
    fun safeShareAllPagesScopeKeepsEveryPage() {
        val analysis =
            SafeShareAnalysis(
                pageCount = 2,
                suggestions =
                    listOf(
                        RedactionSuggestion(
                            0,
                            SensitiveRegionKind.Email,
                            NormalizedRect(0.1f, 0.1f, 0.2f, 0.2f),
                        ),
                        RedactionSuggestion(
                            1,
                            SensitiveRegionKind.Face,
                            NormalizedRect(0.3f, 0.3f, 0.5f, 0.6f),
                        ),
                    ),
            )

        val regions = safeShareRegions(analysis, SafeShareScope.AllPages, selectedPage = 1)

        assertEquals(listOf(0, 1), regions.map(RedactionRegion::page))
        assertEquals(listOf("suggestion-0-0", "suggestion-1-1"), regions.map(RedactionRegion::id))
    }

    @Test
    fun safeShareApplyRequiresCurrentLoadedPreview() {
        val page = File("page-2.jpg")

        assertFalse(
            safeSharePreviewReady(
                previewPage = 2,
                previewFile = page,
                bitmapAvailable = false,
                currentPage = 2,
                currentFile = page,
            ),
        )
        assertFalse(
            safeSharePreviewReady(
                previewPage = -1,
                previewFile = null,
                bitmapAvailable = false,
                currentPage = 2,
                currentFile = page,
            ),
        )
        assertFalse(
            safeSharePreviewReady(
                previewPage = 1,
                previewFile = File("page-1.jpg"),
                bitmapAvailable = true,
                currentPage = 2,
                currentFile = page,
            ),
        )
        assertFalse(safeShareCanApply(previewReady = false, selectedCount = 1))
        assertFalse(safeShareCanApply(previewReady = true, selectedCount = 0))
        assertTrue(
            safeShareCanApply(
                previewReady =
                    safeSharePreviewReady(
                        previewPage = 2,
                        previewFile = page,
                        bitmapAvailable = true,
                        currentPage = 2,
                        currentFile = page,
                    ),
                selectedCount = 1,
            ),
        )
    }

    @Test
    fun safeShareManualAreaCanBeAddedAndDeleted() {
        val added = addManualRedactionRegion(emptyList(), id = "manual-1", page = 0)

        assertEquals(1, added.size)
        assertEquals(SensitiveRegionKind.Manual, added.single().kind)
        assertEquals(NormalizedRect(0.25f, 0.35f, 0.75f, 0.65f), added.single().bounds)
        assertEquals(emptyList<RedactionRegion>(), deleteManualRedactionRegion(added, "manual-1"))
    }

    @Test
    fun manualRedactionUndoAndRedoOnlyAffectTheCurrentPage() {
        val first =
            RedactionStroke(
                points = listOf(MarkPoint(0.1f, 0.2f), MarkPoint(0.4f, 0.2f)),
                widthFraction = 0.03f,
            )
        val last =
            RedactionStroke(
                points = listOf(MarkPoint(0.2f, 0.4f), MarkPoint(0.6f, 0.4f)),
                widthFraction = 0.05f,
            )
        val otherPage =
            RedactionStroke(
                points = listOf(MarkPoint(0.1f, 0.6f), MarkPoint(0.5f, 0.6f)),
                widthFraction = 0.04f,
            )
        val review =
            SafeShareState.Reviewing(
                page = 0,
                regions = emptyList(),
                mode = RedactionMode.Manual,
                strokesByPage = mapOf(0 to listOf(first, last), 1 to listOf(otherPage)),
            )

        val undone = undoManualRedactionStroke(review)
        val redone = redoManualRedactionStroke(undone)

        assertEquals(listOf(first), undone.strokesByPage[0])
        assertEquals(listOf(otherPage), undone.strokesByPage[1])
        assertEquals(listOf(last), undone.undoneStrokesByPage[0])
        assertEquals(review.strokesByPage, redone.strokesByPage)
        assertEquals(emptyMap<Int, List<RedactionStroke>>(), redone.undoneStrokesByPage)
    }

    @Test
    fun newManualRedactionStrokeClearsRedoAndClearKeepsOtherPages() {
        val old = RedactionStroke(listOf(MarkPoint(0.1f, 0.2f)), 0.03f)
        val otherPage = RedactionStroke(listOf(MarkPoint(0.2f, 0.3f)), 0.04f)
        val replacement = RedactionStroke(listOf(MarkPoint(0.3f, 0.4f)), 0.05f)
        val review =
            SafeShareState.Reviewing(
                page = 0,
                regions = emptyList(),
                mode = RedactionMode.Manual,
                strokesByPage = mapOf(1 to listOf(otherPage)),
                undoneStrokesByPage = mapOf(0 to listOf(old)),
            )

        val changed = addManualRedactionStroke(review, replacement)
        val cleared = clearManualRedactionPage(changed)

        assertEquals(listOf(replacement), changed.strokesByPage[0])
        assertEquals(emptyMap<Int, List<RedactionStroke>>(), changed.undoneStrokesByPage)
        assertEquals(mapOf(1 to listOf(otherPage)), cleared.strokesByPage)
    }

    @Test
    fun straightRedactionKeepsOnlyDragEndpointsWhileBrushKeepsSamples() {
        val points =
            listOf(
                MarkPoint(0.1f, 0.2f),
                MarkPoint(0.4f, 0.6f),
                MarkPoint(0.8f, 0.9f),
            )

        assertEquals(
            listOf(MarkPoint(0.1f, 0.2f), MarkPoint(0.8f, 0.9f)),
            redactionStrokePoints(RedactionTool.Line, points),
        )
        val mutablePoints = points.toMutableList()
        val brushPoints = redactionStrokePoints(RedactionTool.Brush, mutablePoints)
        mutablePoints.clear()
        assertEquals(points, brushPoints)
    }

    @Test
    fun automaticOcrUsesChineseOnlyForChineseAppLanguage() {
        assertEquals(OcrScript.Chinese, resolveOcrScript(OcrScript.Auto, "zh-Hans"))
        assertEquals(OcrScript.Latin, resolveOcrScript(OcrScript.Auto, "cs"))
        assertEquals(OcrScript.Latin, resolveOcrScript(OcrScript.Latin, "zh-Hans"))
        assertEquals(OcrScript.Chinese, resolveOcrScript(OcrScript.Chinese, "en"))
    }

    @Test
    fun cachedOcrIsReusedOnlyForTheSameScript() {
        val owner = OcrSnapshotOwner()
        val snapshot = DocumentOcrSnapshot(listOf("text"), emptyList(), truncated = false)
        val generation = owner.begin()

        assertTrue(
            owner.publish(
                generation,
                CACHE_ID,
                ENTRY_ID,
                OcrScript.Latin,
                snapshot,
            ),
        )
        assertSame(snapshot, owner.current(CACHE_ID, ENTRY_ID, OcrScript.Latin))
        assertNull(owner.current(CACHE_ID, ENTRY_ID, OcrScript.Chinese))
    }

    @Test
    fun readAloudAutoUsesAppLanguageThenSystemLanguage() {
        assertEquals(
            "cs",
            resolveReadAloudLanguageTag(ReadAloudLanguage.Auto, "cs", "de"),
        )
        assertEquals(
            "de",
            resolveReadAloudLanguageTag(ReadAloudLanguage.Auto, null, "de"),
        )
        assertEquals(
            "es",
            resolveReadAloudLanguageTag(ReadAloudLanguage.Spanish, "cs", "de"),
        )
    }

    @Test
    fun safeShareMoveClampsWithoutChangingRegionSize() {
        val region =
            RedactionRegion(
                "manual-1",
                0,
                SensitiveRegionKind.Manual,
                NormalizedRect(0.2f, 0.3f, 0.5f, 0.7f),
                selected = true,
            )

        val moved = moveRedactionRegion(listOf(region), "manual-1", deltaX = -1f, deltaY = 1f)

        assertEquals(NormalizedRect(0f, 0.6f, 0.3f, 1f), moved.single().bounds)
    }

    @Test
    fun safeShareResizeClampsAndKeepsMinimumSize() {
        val region =
            RedactionRegion(
                "manual-1",
                0,
                SensitiveRegionKind.Manual,
                NormalizedRect(0.2f, 0.3f, 0.5f, 0.7f),
                selected = true,
            )

        val expanded = resizeRedactionRegion(listOf(region), "manual-1", 1f, 1f)
        val shrunk = resizeRedactionRegion(expanded, "manual-1", -1f, -1f)

        assertEquals(NormalizedRect(0.2f, 0.3f, 1f, 1f), expanded.single().bounds)
        with(shrunk.single().bounds) {
            assertEquals(0.2f, left, 0.0001f)
            assertEquals(0.3f, top, 0.0001f)
            assertEquals(0.22f, right, 0.0001f)
            assertEquals(0.32f, bottom, 0.0001f)
        }
    }

    @Test
    fun safeShareResizeKeepsValidTinyEdgeRegionInsteadOfCrashing() {
        val region =
            RedactionRegion(
                "manual-edge",
                0,
                SensitiveRegionKind.Manual,
                NormalizedRect(0.99f, 0.99f, 1f, 1f),
                selected = true,
            )

        val resized = resizeRedactionRegion(listOf(region), region.id, -1f, -1f)

        assertEquals(region.bounds, resized.single().bounds)
    }

    @Test
    fun safeShareAutomaticAreasCanBeDeselectedButNotDeleted() {
        val region =
            RedactionRegion(
                "suggestion-0",
                0,
                SensitiveRegionKind.Email,
                NormalizedRect(0.1f, 0.1f, 0.2f, 0.2f),
                selected = true,
            )

        val toggled = toggleRedactionRegion(listOf(region), region.id)

        assertFalse(toggled.single().selected)
        assertEquals(toggled, deleteManualRedactionRegion(toggled, region.id))
    }

    @Test
    fun safeShareManualAreasStopAtPerPageLimit() {
        val fullPage =
            List(MAX_SAFE_SHARE_SUGGESTIONS_PER_PAGE) { index ->
                RedactionRegion(
                    "manual-$index",
                    0,
                    SensitiveRegionKind.Manual,
                    NormalizedRect(0.1f, 0.1f, 0.2f, 0.2f),
                    selected = true,
                )
            }

        assertEquals(
            fullPage,
            addManualRedactionRegion(fullPage, id = "manual-overflow", page = 0),
        )
        assertEquals(
            MAX_SAFE_SHARE_SUGGESTIONS_PER_PAGE + 1,
            addManualRedactionRegion(fullPage, id = "manual-page-2", page = 1).size,
        )
    }

    @Test
    fun safeShareBackCancelsReviewButConsumesBusyStates() {
        val result =
            ScreenState.Result(
                scan = savedScan(entryId = ENTRY_ID, outputMetadataValid = true),
                thumbnail = null,
            )

        assertEquals(
            AppBackAction.CancelSafeShare,
            appBackAction(
                settingsOpen = false,
                fileDetailsOpen = false,
                state =
                    result.copy(
                        safeShareState = SafeShareState.Reviewing(0, emptyList()),
                    ),
            ),
        )
        assertEquals(
            AppBackAction.Consume,
            appBackAction(
                settingsOpen = false,
                fileDetailsOpen = false,
                state = result.copy(safeShareState = SafeShareState.Analyzing),
            ),
        )
        assertEquals(
            AppBackAction.Consume,
            appBackAction(
                settingsOpen = false,
                fileDetailsOpen = false,
                state = result.copy(safeShareState = SafeShareState.Applying),
            ),
        )
    }

    @Test
    fun safeShareProcessRestoreReturnsToResult() {
        assertEquals(
            InitialNavigation(RestoredRoute.Result, CACHE_ID),
            initialNavigation(
                savedRoute = "result",
                savedCacheId = CACHE_ID,
                activeResultCacheId = CACHE_ID,
                safeShareWasActive = true,
            ),
        )
    }

    @Test
    fun safeShareProvisionalRestoreUsesActiveCandidateInsteadOfSavedParent() {
        assertEquals(
            InitialNavigation(RestoredRoute.Result, "Scan_protected"),
            initialNavigation(
                savedRoute = "result",
                savedCacheId = "Scan_parent",
                activeResultCacheId = "Scan_protected",
                safeShareWasActive = true,
            ),
        )
        assertEquals(
            InitialNavigation(
                route = RestoredRoute.Scanner,
                cacheId = null,
                clearCheckpointBeforeLaunch = true,
            ),
            initialNavigation(
                savedRoute = "result",
                savedCacheId = "Scan_parent",
                activeResultCacheId = "Scan_protected",
            ),
        )
    }

    @Test
    fun safeShareRequestRejectsStaleGenerationAndWrongIdentity() {
        val request =
            SafeShareRequest(
                cacheId = CACHE_ID,
                entryId = ENTRY_ID,
                scope = SafeShareScope.AllPages,
                selectedPage = 1,
                generation = 7L,
                mode = RedactionMode.Automatic,
            )

        assertTrue(
            request.matches(
                CACHE_ID,
                ENTRY_ID,
                SafeShareScope.AllPages,
                selectedPage = 1,
                generation = 7L,
                mode = RedactionMode.Automatic,
            ),
        )
        assertFalse(
            request.matches(
                CACHE_ID,
                ENTRY_ID,
                SafeShareScope.AllPages,
                selectedPage = 0,
                generation = 7L,
                mode = RedactionMode.Automatic,
            ),
        )
        assertFalse(
            request.matches(
                CACHE_ID,
                ENTRY_ID,
                SafeShareScope.SelectedPage,
                selectedPage = 1,
                generation = 7L,
                mode = RedactionMode.Automatic,
            ),
        )
        assertFalse(
            request.matches(
                CACHE_ID,
                ENTRY_ID,
                SafeShareScope.AllPages,
                selectedPage = 1,
                generation = 8L,
                mode = RedactionMode.Automatic,
            ),
        )
        assertFalse(
            request.matches(
                CACHE_ID,
                OTHER_ENTRY_ID,
                SafeShareScope.AllPages,
                selectedPage = 1,
                generation = 7L,
                mode = RedactionMode.Automatic,
            ),
        )
        assertFalse(
            request.matches(
                "Scan_other",
                ENTRY_ID,
                SafeShareScope.AllPages,
                selectedPage = 1,
                generation = 7L,
                mode = RedactionMode.Automatic,
            ),
        )
    }

    @Test
    fun ttsInitializationQueuesReplayAfterStopAndSecondPlay() {
        val gate = TtsInitializationGate()

        assertEquals(TtsPlayDecision.Initialize, gate.play("first", engineExists = false))
        gate.stop()
        assertEquals(TtsPlayDecision.Queue, gate.play("second", engineExists = true))
        assertEquals(
            TtsInitializationResult(ready = true, text = "second"),
            gate.initialized(success = true),
        )
        assertEquals(TtsPlayDecision.Speak, gate.play("third", engineExists = true))
    }

    @Test
    fun ttsInitializationSuccessWithoutQueuedTextStaysReadyAndFailureResets() {
        val stopped = TtsInitializationGate()
        assertEquals(TtsPlayDecision.Initialize, stopped.play("first", engineExists = false))
        stopped.stop()
        assertEquals(
            TtsInitializationResult(ready = true, text = null),
            stopped.initialized(success = true),
        )
        assertEquals(TtsPlayDecision.Speak, stopped.play("second", engineExists = true))

        val failed = TtsInitializationGate()
        assertEquals(TtsPlayDecision.Initialize, failed.play("first", engineExists = false))
        assertEquals(
            TtsInitializationResult(ready = false, text = "first"),
            failed.initialized(success = false),
        )
        assertEquals(TtsPlayDecision.Initialize, failed.play("retry", engineExists = false))
    }

    @Test
    fun internalBuildHasDistinctTestAppLabel() {
        val repository = File("..").canonicalFile
        val manifest = File(repository, "app/src/internal/AndroidManifest.xml").readText()
        val strings = File(repository, "app/src/internal/res/values/strings.xml").readText()

        assertTrue(manifest.contains("android:label=\"@string/internal_app_name\""))
        assertTrue(manifest.contains("tools:replace=\"android:label\""))
        assertTrue(
            strings.contains(
                "<string name=\"internal_app_name\" translatable=\"false\">SeliaScan.test</string>",
            ),
        )
    }

    @Test
    fun betaReleaseMetadataAndVerifierSupportAab() {
        val repository = File("..").canonicalFile
        val build = File(repository, "app/build.gradle.kts").readText()
        val verifier = File(repository, "tools/verify-release.ps1").readText()

        assertTrue(build.contains("versionCode = 34"))
        assertTrue(build.contains("versionName = \"1.6.0-vip-ads.8\""))
        assertTrue(verifier.contains("\$expectedVersionCode = \"34\""))
        assertTrue(verifier.contains("\$expectedVersionName = \"1.6.0-vip-ads.8\""))
        assertTrue(verifier.contains("\$requiredBetaPermissions = @("))
        assertFalse(
            verifier.contains(
                "if (\$Flavor -eq \"beta\" -and \$artifactType -ne \"apk\")",
            ),
        )
    }

    @Test
    fun composeDisplayedBitmapsAreNotManuallyRecycled() {
        val repository = File("..").canonicalFile
        val appUi = File(repository, "app/src/main/java/com/majkeylab/scanit/AppUi.kt").readText()
        val markUi = File(repository, "app/src/main/java/com/majkeylab/scanit/VisualMarkUi.kt").readText()

        assertFalse(appUi.contains("awaitDispose { bitmap?.recycle() }"))
        assertFalse(markUi.contains("onDispose { bitmap?.recycle() }"))
    }

    @Test
    fun resultCopyIsPresentAcrossEverySupportedLanguage() {
        val repository = File("..").canonicalFile
        val resourceDirectories =
            listOf("values", "values-cs", "values-de", "values-es", "values-zh-rCN")

        resourceDirectories.forEach { directory ->
            val strings =
                File(repository, "app/src/main/res/$directory/strings.xml").readText()
            assertTrue(strings.contains("<string name=\"page_position_short\">"))
            assertFalse(strings.contains("<string name=\"edit\">"))
            assertTrue(strings.contains("<string name=\"rescan\">"))
            assertFalse(strings.contains("<string name=\"edit_scan\">"))
        }
    }

    @Test
    fun documentActionCopyIsPresentAcrossEverySupportedLanguage() {
        val repository = File("..").canonicalFile
        val resourceDirectories =
            listOf("values", "values-cs", "values-de", "values-es", "values-zh-rCN")
        val keyPattern = Regex("""<string name="([^"]+)"""")
        val keys =
            listOf(
                "find_text",
                "read_aloud",
                "receipt_details",
                "create_contact",
                "search_document",
                "no_matches_found",
                "tts_disclosure_message",
                "receipt_candidates",
                "contact_candidates",
                "system_action_failed",
            )
        val defaultKeys =
            keyPattern.findAll(
                File(repository, "app/src/main/res/values/strings.xml").readText(),
            ).map { it.groupValues[1] }.toSet()

        resourceDirectories.forEach { directory ->
            val strings = File(repository, "app/src/main/res/$directory/strings.xml").readText()
            keys.forEach { key -> assertTrue(strings.contains("<string name=\"$key\">")) }
            assertEquals(
                "String keys differ in $directory",
                defaultKeys,
                keyPattern.findAll(strings).map { it.groupValues[1] }.toSet(),
            )
        }
    }

    @Test
    fun fileDetailControlsCoverSaveAndLegacyStates() {
        val baseControls =
            setOf(
                FileDetailControl.PdfSize,
                FileDetailControl.PdfLocation,
                FileDetailControl.ImageSize,
                FileDetailControl.ImageFormat,
                FileDetailControl.ImageLocation,
            )
        val pdfControls =
            setOf(
                FileDetailControl.PdfSize,
                FileDetailControl.PdfLocation,
            )

        assertEquals(baseControls, fileDetailControls(fileDetailAvailability()))
        assertEquals(
            baseControls + FileDetailControl.PdfName + FileDetailControl.ImageName,
            fileDetailControls(fileDetailAvailability(savedImages = 2, outputsRenameable = true)),
        )
        assertEquals(pdfControls, fileDetailControls(fileDetailAvailability(imagesChangeable = false)))
        assertEquals(
            pdfControls + FileDetailControl.ImageLocation,
            fileDetailControls(
                fileDetailAvailability(imagesChangeable = false, imagesRelocatable = true),
            ),
        )
        assertEquals(
            emptySet<FileDetailControl>(),
            fileDetailControls(fileDetailAvailability(valid = false)),
        )
    }

    @Test
    fun outputNamesAreNormalizedWithoutChangingTheirDocumentIdentity() {
        assertEquals("Practice sheet", normalizeOutputBaseName("  Practice sheet.pdf  "))
        assertEquals("Výkres 7", normalizeOutputBaseName("Výkres 7.PNG"))
        assertEquals("Practice sheet.pdf", pdfOutputDisplayName("Practice sheet"))
        assertEquals("Practice sheet_02.png", imageOutputDisplayName("Practice sheet", 2, "png"))
        assertEquals(
            "Practice sheet",
            imageOutputBaseName(
                listOf(
                    1 to "Practice sheet_01 (1).jpg",
                    2 to "Practice sheet_02 (1).jpg",
                ),
            ),
        )
    }

    @Test
    fun fileNamePresentationKeepsTheCurrentExtensionOutsideTheEditableStem() {
        assertEquals(".pdf", outputFileExtension("Practice sheet.pdf"))
        assertEquals(".png", outputFileExtension("Practice sheet_01.png"))
        assertEquals(".jpg", outputFileExtension("Practice sheet_01 (1).jpg"))
        assertEquals(".JPG", outputFileExtension("Practice sheet_01.JPG"))
        assertNull(outputFileExtension("Practice sheet"))
        assertNull(outputFileExtension("Practice sheet.exe"))
    }

    @Test
    fun savedOutputLocationsUseFullStoragePaths() {
        assertEquals(
            "/storage/emulated/0/Download/Scan to PDF",
            mediaStoreDirectoryPath("external_primary", "Download/Scan to PDF/"),
        )
        assertEquals(
            "/storage/emulated/0/Download/Scan to PDF/Practice sheet.pdf",
            outputLocationPath(
                "/storage/emulated/0/Download/Scan to PDF",
                "Practice sheet.pdf",
            ),
        )
        assertEquals(
            "/storage/emulated/0/Pictures/Scan to PDF",
            mediaStoreDirectoryPath("external", "Pictures/Scan to PDF"),
        )
        assertEquals(
            "/storage/ABCD-1234/Documents/Scans",
            mediaStoreDirectoryPath("ABCD-1234", "Documents/Scans/"),
        )
        assertEquals(
            "/storage/emulated/0/Documents/Scans",
            externalStorageDirectoryPath("primary:Documents/Scans"),
        )
        assertEquals(
            "/storage/ABCD-1234/Scans",
            externalStorageDirectoryPath("ABCD-1234:Scans"),
        )
        assertNull(mediaStoreDirectoryPath("bad/volume", "Download/Scans"))
        assertNull(mediaStoreDirectoryPath("external_primary", "../Scans"))
        assertNull(externalStorageDirectoryPath("cloud-root"))
        assertNull(outputLocationPath("/storage/emulated/0/Download", "../bad.pdf"))
        assertEquals(
            "/storage/emulated/0/Pictures/Scan to PDF",
            imageOutputLocationLabel(
                listOf(
                    "/storage/emulated/0/Pictures/Scan to PDF/Page_01.jpg",
                    "/storage/emulated/0/Pictures/Scan to PDF/Page_02.jpg",
                ),
            ),
        )
        assertEquals(
            "content://provider/one\ncontent://provider/two\ncontent://provider/three\n…",
            imageOutputLocationLabel(
                listOf(
                    "content://provider/one",
                    "content://provider/two",
                    "content://provider/three",
                    "content://provider/four",
                ),
            ),
        )
    }

    @Test
    fun fileDetailsShowReadableStoragePathsAndNeverProviderUris() {
        assertEquals(
            "Internal storage/Downloads/Scan to PDF",
            displayOutputLocationPath(
                "/storage/emulated/0/Download/Scan to PDF/Practice sheet.pdf",
            ),
        )
        assertEquals(
            "External storage/Documents/Scans",
            displayOutputLocationPath(
                "/storage/ABCD-1234/Documents/Scans/Practice sheet.pdf",
            ),
        )
        assertEquals(
            "Internal storage/Pictures/Scan to PDF",
            displayOutputLocationPath(
                "/storage/emulated/0/Pictures/Scan to PDF/Page 01.png",
            ),
        )
        assertNull(displayOutputLocationPath("content://media/external/downloads/15911"))
        assertNull(displayOutputLocationPath(null))
    }

    @Test
    fun missingPdfIsShownAsDeletedOnlyAfterProviderConfirmsAbsence() {
        val reference =
            PdfOutputRef(
                uri = "content://media/external/downloads/15911",
                treeUri = null,
                displayName = "Practice sheet.pdf",
                mimeType = "application/pdf",
                ownerPackageName = "com.majkeylab.scanit",
                byteLength = 123L,
                sha256 = "01".repeat(32),
            )

        val deleted = visiblePdfOutput(reference) { ExactItemQuery.Absent }
        assertNull(deleted.reference)
        assertTrue(deleted.deleted)

        listOf(ExactItemQuery.Exact, ExactItemQuery.IdentityMismatch, ExactItemQuery.Failed)
            .forEach { status ->
                val retained = visiblePdfOutput(reference) { status }
                assertEquals(reference, retained.reference)
                assertFalse(retained.deleted)
            }
    }

    @Test
    fun imagesAreDeletedOnlyWhenEverySavedPageIsConfirmedAbsent() {
        assertEquals(
            ImageOutputPresence.Present,
            imageOutputPresence(listOf(ExactItemQuery.Exact, ExactItemQuery.Exact)),
        )
        assertEquals(
            ImageOutputPresence.Deleted,
            imageOutputPresence(listOf(ExactItemQuery.Absent, ExactItemQuery.Absent)),
        )
        listOf(
            emptyList(),
            listOf(ExactItemQuery.Exact, ExactItemQuery.Absent),
            listOf(ExactItemQuery.IdentityMismatch),
            listOf(ExactItemQuery.Failed),
        ).forEach { results ->
            assertEquals(ImageOutputPresence.Uncertain, imageOutputPresence(results))
        }
    }

    @Test
    fun outputNamesRejectPathsControlsAndEmptyValues() {
        listOf(
            "",
            "   ",
            ".pdf",
            "..",
            "folder/name",
            "folder\\name",
            "bad\u0000name",
            "x".repeat(97),
        ).forEach { assertNull(normalizeOutputBaseName(it)) }
        assertNull(imageOutputBaseName(listOf(1 to "One_01.jpg", 2 to "Two_02.jpg")))
        assertNull(imageOutputBaseName(listOf(1 to null)))
    }

    @Test
    fun outputNameRequestsValidateBeforeStartingIo() {
        val pdf = OutputChangeKind.PdfName("Practice sheet")
        val images = OutputChangeKind.ImageName("Practice sheet")
        assertEquals("Practice sheet", pdf.baseName)
        assertEquals("Practice sheet", images.baseName)
        assertThrows(IllegalArgumentException::class.java) {
            OutputChangeRequest(CACHE_ID, ENTRY_ID, OutputChangeKind.PdfName("../bad"), 1L)
        }
    }

    @Test
    fun resultCustomValuesAndFullscreenPageStayBounded() {
        assertNull(parseCustomImageDimension("319"))
        assertEquals(320, parseCustomImageDimension("320"))
        assertEquals(6000, parseCustomImageDimension("6000"))
        assertNull(parseCustomImageDimension("6001"))
        assertNull(parseCustomImageDimension(" 320"))

        assertEquals(0, fullscreenPageIndex(-1, 3))
        assertEquals(2, fullscreenPageIndex(9, 3))
        assertEquals(0, fullscreenPageIndex(4, 0))
    }

    @Test
    fun imageDetailsUseOnlyCompleteSavedOrCachedDimensions() {
        val cached = listOf(2000 to 3000, 2000 to 3000)

        assertEquals(cached, exactImageDimensions(2, emptyList(), cached))
        assertEquals(
            listOf(1600 to 2400, 1600 to 2400),
            exactImageDimensions(
                2,
                listOf(1600 to 2400, 1600 to 2400),
                cached,
            ),
        )
        assertNull(exactImageDimensions(2, listOf(1600 to 2400, null), cached))
        assertNull(exactImageDimensions(2, emptyList(), listOf(2000 to 3000)))
        assertNull(exactImageDimensions(2, emptyList(), listOf(0 to 3000, 2000 to 3000)))
    }

    @Test
    fun recentRowContentOpensWhileOverflowRemainsIsolated() {
        assertEquals(RecentRowAction.Open, recentRowAction(RecentRowTarget.Content))
        assertEquals(RecentRowAction.ShowMenu, recentRowAction(RecentRowTarget.Overflow))
    }

    @Test
    fun resultActionsRouteAndUnknownOutputWarningFailsClosed() {
        val editable = uiSavedScan()
        assertEquals(
            ResultActionDestination.Scanner,
            resultActionDestination(ResultEntryAction.Rescan),
        )
        assertEquals(
            ResultActionDestination.MarkEditor,
            resultActionDestination(ResultEntryAction.SignOrStamp),
        )
        assertEquals(
            ResultActionDestination.DocumentActions,
            resultActionDestination(ResultEntryAction.Actions),
        )
        val acknowledgement =
            UnknownOutputCreateAcknowledgement(CACHE_ID, ENTRY_ID, OTHER_ENTRY_ID)
        val warning = editable.copy(unknownOutputCreateAcknowledgement = acknowledgement)
        assertSame(acknowledgement, confirmedUnknownOutputAcknowledgement(warning, confirmed = true))
        assertNull(confirmedUnknownOutputAcknowledgement(warning, confirmed = false))
        assertNull(confirmedUnknownOutputAcknowledgement(editable, confirmed = true))
    }

    @Test
    fun resultExposesOnlyRescanSignAndActions() {
        assertEquals(
            listOf(
                ResultEntryAction.Rescan,
                ResultEntryAction.SignOrStamp,
                ResultEntryAction.Actions,
            ),
            ResultEntryAction.entries,
        )
    }

    @Test
    fun unsavedImagesStartWithOriginalExportOptions() {
        assertEquals(
            ImageExportOptions(ImageExportFormat.Original, ImageSizePreset.Original),
            imageExportOptionsForChange(uiSavedScan()),
        )
        assertNull(imageExportOptionsForChange(uiSavedScan().copy(outputMetadataValid = false)))
    }

    @Test
    fun savedImageOptionsUsePersistedPresetInsteadOfRenderedDimensions() {
        assertEquals(
            ImageExportOptions(ImageExportFormat.Jpeg, ImageSizePreset.High),
            activeImageExportOptions(
                formats = listOf(ImageExportFormat.Jpeg),
                treeUris = listOf(null),
                sizePresets = listOf(ImageSizePreset.High),
                customMaxDimensions = listOf(null),
            ),
        )
        assertNull(
            activeImageExportOptions(
                formats = listOf(ImageExportFormat.Jpeg),
                treeUris = listOf(null),
                sizePresets = listOf(null),
                customMaxDimensions = listOf(null),
            ),
        )
    }

    @Test
    fun outputChangeGateRejectsDoubleTapAndReusedCacheGeneration() {
        val gate = OutputChangeGate()
        val request =
            gate.begin(
                CACHE_ID,
                ENTRY_ID,
                OutputChangeKind.PdfLocation,
            )!!

        assertNull(gate.begin(CACHE_ID, ENTRY_ID, OutputChangeKind.PdfLocation))
        assertTrue(gate.isCurrent(request, CACHE_ID, ENTRY_ID))
        assertFalse(gate.isCurrent(request, CACHE_ID, OTHER_ENTRY_ID))

        gate.invalidate()

        assertFalse(gate.isCurrent(request, CACHE_ID, ENTRY_ID))
        val replacement = gate.begin(CACHE_ID, OTHER_ENTRY_ID, OutputChangeKind.PdfLocation)!!
        assertTrue(replacement.generation > request.generation)
    }

    @Test
    fun outputTreePickerRestoresBeforeClaimAndLaunchesOnlyOnce() {
        val request =
            OutputChangeRequest(
                CACHE_ID,
                ENTRY_ID,
                OutputChangeKind.ImageLocation,
                generation = 7L,
            )
        val encoded = encodeOutputTreePickerRequest(request)
        val restored = decodeOutputTreePickerRequest(encoded)
        val operationGate = OutputChangeGate(initialGeneration = 7L, initialCurrent = restored)
        val pickerGate = OutputTreePickerGate(restored)

        assertEquals(request, restored)
        assertTrue(pickerGate.claim(request))
        assertFalse(pickerGate.claim(request))
        assertTrue(operationGate.isCurrent(request, CACHE_ID, ENTRY_ID))
        assertNull(pickerGate.pending)
    }

    @Test
    fun outputTreePickerRestoredAfterClaimWaitsForExactCallback() {
        val request =
            OutputChangeRequest(
                CACHE_ID,
                ENTRY_ID,
                OutputChangeKind.PdfLocation,
                generation = 4L,
            )
        val operationGate = OutputChangeGate(initialGeneration = 4L, initialCurrent = request)
        val pickerGate = OutputTreePickerGate(initialPending = null)

        assertNull(pickerGate.pending)
        assertTrue(operationGate.isCurrent(request, CACHE_ID, ENTRY_ID))
        assertFalse(
            operationGate.isCurrent(
                request.copy(generation = 3L),
                CACHE_ID,
                ENTRY_ID,
            ),
        )

        assertTrue(pickerGate.offer(request))
        assertFalse(pickerGate.offer(request))
    }

    @Test
    fun outputTreePickerCodecRejectsNonPickerAndMalformedState() {
        val immediate =
            OutputChangeRequest(
                CACHE_ID,
                ENTRY_ID,
                OutputChangeKind.PdfSize(PdfSizeTarget.Mb5),
                generation = 1L,
            )

        assertThrows(IllegalArgumentException::class.java) {
            encodeOutputTreePickerRequest(immediate)
        }
        assertNull(decodeOutputTreePickerRequest(null))
        assertNull(decodeOutputTreePickerRequest(""))
        assertNull(decodeOutputTreePickerRequest("2\t$CACHE_ID\t$ENTRY_ID\tpdf\t1"))
        assertNull(decodeOutputTreePickerRequest("1\t../outside\t$ENTRY_ID\tpdf\t1"))
        assertNull(decodeOutputTreePickerRequest("1\t$CACHE_ID\t$ENTRY_ID\timage\t0\tpng\tCustom\t2400"))
    }

    @Test
    fun outputTreeGrantRequiresValidatedTreeAndExactReadWriteAccess() {
        val extraFlags = Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION

        assertEquals(
            PDF_TREE_FLAGS,
            exactOutputTreeGrantFlags("content", isTreeUri = true, PDF_TREE_FLAGS or extraFlags),
        )
        assertNull(exactOutputTreeGrantFlags("file", isTreeUri = true, PDF_TREE_FLAGS))
        assertNull(exactOutputTreeGrantFlags("content", isTreeUri = false, PDF_TREE_FLAGS))
        assertNull(exactOutputTreeGrantFlags("content", isTreeUri = true, PDF_TREE_FLAGS))
        assertNull(
            exactOutputTreeGrantFlags(
                "content",
                isTreeUri = true,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            ),
        )
    }

    @Test
    fun outputChangeRefreshRequiresExactIdentityAndValidMetadata() {
        val request =
            OutputChangeRequest(
                CACHE_ID,
                ENTRY_ID,
                OutputChangeKind.ImageFormat(ImageExportFormat.Png),
                generation = 11L,
            )
        val exact = savedScan(entryId = ENTRY_ID, outputMetadataValid = true)
        val stale = savedScan(entryId = OTHER_ENTRY_ID, outputMetadataValid = true)
        val unreadable = savedScan(entryId = ENTRY_ID, outputMetadataValid = false)

        assertSame(exact, matchingOutputChangeScan(exact, request))
        assertNull(matchingOutputChangeScan(stale, request))
        assertNull(matchingOutputChangeScan(unreadable, request))
        assertSame(unreadable, matchingOutputChangeIdentityScan(unreadable, request))
        assertNull(matchingOutputChangeIdentityScan(stale, request))
    }

    @Test
    fun outputChangeBusyStateBlocksActionsAndConsumesBack() {
        val result =
            ScreenState.Result(
                scan = savedScan(entryId = ENTRY_ID, outputMetadataValid = true),
                thumbnail = null,
                outputChangeInProgress = true,
            )

        assertTrue(result.resultActionsBlocked)
        assertEquals(
            AppBackAction.Consume,
            appBackAction(settingsOpen = false, fileDetailsOpen = true, state = result),
        )
    }

    @Test
    fun unknownOutputAcknowledgementRefreshesOnlyExactTerminalSuccess() {
        assertTrue(
            unknownOutputAcknowledgementRefreshAllowed(
                UnknownOutputAcknowledgementResult.Applied,
            ),
        )
        assertTrue(
            unknownOutputAcknowledgementRefreshAllowed(
                UnknownOutputAcknowledgementResult.Absent,
            ),
        )
        assertFalse(
            unknownOutputAcknowledgementRefreshAllowed(
                UnknownOutputAcknowledgementResult.Stale,
            ),
        )
        assertFalse(
            unknownOutputAcknowledgementRefreshAllowed(
                UnknownOutputAcknowledgementResult.Failed,
            ),
        )
    }

    @Test
    fun imageShareModeUsesPrivateCopiesForRichOutputsAndCachedFilesForLegacy() {
        val rich =
            listOf(
                PreparedImageSource(
                    page = 1,
                    uri = "content://media/images/1",
                    mimeType = "image/png",
                    byteLength = 42L,
                    sha256 = "a".repeat(64),
                ),
            )
        val legacy =
            listOf(
                PreparedImageSource(
                    page = 1,
                    uri = "content://media/images/1",
                    mimeType = null,
                    byteLength = null,
                    sha256 = null,
                ),
            )

        assertEquals(ResultImageShareMode.PrivateCopies, resultImageShareMode(rich))
        assertEquals(ResultImageShareMode.CachedPages, resultImageShareMode(legacy))
        assertEquals(ResultImageShareMode.Unavailable, resultImageShareMode(rich + legacy))
        assertEquals(
            "image/*",
            activeImageShareMimeType(listOf("image/jpeg", "image/png")),
        )
    }

    @Test
    fun imagePresetsResolveExactLongEdges() {
        assertEquals(null, resolveImageExport(ImageSizePreset.Original, null).maxDimension)
        assertEquals(3840, resolveImageExport(ImageSizePreset.High, null).maxDimension)
        assertEquals(2560, resolveImageExport(ImageSizePreset.Balanced, null).maxDimension)
        assertEquals(1600, resolveImageExport(ImageSizePreset.Small, null).maxDimension)
    }

    @Test
    fun customImageDimensionIsBounded() {
        assertThrows(IllegalArgumentException::class.java) {
            resolveImageExport(ImageSizePreset.Custom, 319)
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolveImageExport(ImageSizePreset.Custom, 6001)
        }
        assertEquals(320, resolveImageExport(ImageSizePreset.Custom, 320).maxDimension)
        assertEquals(6000, resolveImageExport(ImageSizePreset.Custom, 6000).maxDimension)
    }

    @Test
    fun imageExportOptionsRejectUnusedCustomDimension() {
        assertThrows(IllegalArgumentException::class.java) {
            resolveImageExport(ImageSizePreset.High, 1000)
        }
    }

    @Test
    fun supportUsesThePublishedBuyMeACoffeePage() {
        assertEquals("https://www.buymeacoffee.com/majkey", SUPPORT_URL)
    }

    @Test
    fun mainScannerUsesFullGoogleEditorWhileMarkCaptureStaysBase() {
        assertEquals(
            GmsDocumentScannerOptions.SCANNER_MODE_FULL,
            scannerMode(ScannerPurpose.Document),
        )
        assertEquals(
            GmsDocumentScannerOptions.SCANNER_MODE_BASE,
            scannerMode(ScannerPurpose.VisualMark),
        )
    }

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
                deletePdfAfterShare = true,
                deleteImagesAfterShare = false,
                appearance = ScanAppearanceSettings(),
                pdfSizeTarget = PdfSizeTarget.Original,
            ),
            AppSettings(),
        )
    }

    @Test
    fun automaticPdfSaveDefersCustomSafUntilExplicitSave() {
        assertTrue(automaticPdfUsesDownloads(null))
        assertFalse(automaticPdfUsesDownloads("content://documents/tree/scanit"))
    }

    @Test
    fun recentDeleteChoicesUseOnlyExactDurableReferences() {
        assertEquals(
            listOf(RecentDeleteTarget.Pdf, RecentDeleteTarget.Images, RecentDeleteTarget.Both),
            recentDeleteTargets(metadataValid = true, hasPdf = true, savedImageCount = 2),
        )
        assertEquals(
            listOf(RecentDeleteTarget.Images),
            recentDeleteTargets(metadataValid = true, hasPdf = false, savedImageCount = 1),
        )
        assertEquals(
            listOf(RecentDeleteTarget.RemoveFromRecent),
            recentDeleteTargets(metadataValid = false, hasPdf = true, savedImageCount = 2),
        )
    }

    @Test
    fun pendingCacheRemovalOffersOnlyARecentRetry() {
        assertEquals(
            listOf(RecentDeleteTarget.RemoveFromRecent),
            recentDeleteTargets(
                metadataValid = true,
                hasPdf = false,
                savedImageCount = 2,
                removeRecentPending = true,
            ),
        )
    }

    @Test
    fun outputDeleteReductionRemovesDeletedAndAbsentRefsButKeepsFailures() {
        val metadata =
            outputMetadata(
                images =
                    listOf(
                        ImageOutputRef(1, "content://media/external/images/media/1"),
                        ImageOutputRef(2, "content://media/external/images/media/2"),
                    ),
            ).copy(
                pdf = PdfOutputRef("content://media/external/downloads/3", null),
            )

        val reduced =
            reduceOutputDeletion(
                metadata,
                RecentDeleteTarget.Both,
                mapOf(
                    "content://media/external/downloads/3" to OutputDeleteStatus.Absent,
                    "content://media/external/images/media/1" to OutputDeleteStatus.Deleted,
                    "content://media/external/images/media/2" to OutputDeleteStatus.Failed,
                ),
            )

        assertEquals(null, reduced.metadata.pdf)
        assertEquals(listOf(metadata.images[1]), reduced.metadata.images)
        assertFalse(reduced.allRequestedRemoved)
    }

    @Test
    fun deleteAfterShareSettingsRoundTripAndWrongTypesFailClosed() {
        val (preferences, values) = inMemoryPreferences()
        val store = SettingsStore(preferences, "Scanned document")
        val enabled =
            AppSettings(deletePdfAfterShare = true, deleteImagesAfterShare = true)
        val owner = store.authoritySnapshot().owner

        assertEquals(
            AuthorityMutationResult.Applied,
            store.trySave(enabled, owner),
        )

        assertTrue(store.load().deletePdfAfterShare)
        assertTrue(store.load().deleteImagesAfterShare)

        values["delete_pdf_after_share"] = "wrong"
        values["delete_images_after_share"] = 1

        assertTrue(store.load().deletePdfAfterShare)
        assertFalse(store.load().deleteImagesAfterShare)
    }

    @Test
    fun explicitDeleteAfterShareChoicesSurviveStoreRecreation() {
        val (preferences, _) = inMemoryPreferences()
        val first = SettingsStore(preferences, "Scanned document")
        val owner = first.authoritySnapshot().owner

        assertEquals(
            AuthorityMutationResult.Applied,
            first.trySave(
                AppSettings(deletePdfAfterShare = false, deleteImagesAfterShare = true),
                owner,
            ),
        )

        val restored = SettingsStore(preferences, "Scanned document").load()
        assertFalse(restored.deletePdfAfterShare)
        assertTrue(restored.deleteImagesAfterShare)
    }

    @Test
    fun documentActionLanguagesSurviveStoreRecreation() {
        val (preferences, _) = inMemoryPreferences()
        SettingsStore(preferences, "Scanned document").save(
            AppSettings(
                ocrScript = OcrScript.Chinese,
                readAloudLanguage = ReadAloudLanguage.Czech,
            ),
        )

        val restored = SettingsStore(preferences, "Scanned document").load()

        assertEquals(OcrScript.Chinese, restored.ocrScript)
        assertEquals(ReadAloudLanguage.Czech, restored.readAloudLanguage)
    }

    @Test
    fun selectedShareCleanupQueueRetriesTransientFailureAndDrainsTerminalResults() {
        val (preferences, _) = inMemoryPreferences()
        val store = SettingsStore(preferences, "Scanned document")
        val first = ShareCleanupRequest(CACHE_ID, ENTRY_ID, ShareCleanupKind.Pdf)
        val second = ShareCleanupRequest(CACHE_ID, OTHER_ENTRY_ID, ShareCleanupKind.Images)
        var attempts = 0
        var refreshes = 0

        store.savePendingShareCleanup(first)
        assertTrue(store.canSavePendingShareCleanup(second))
        store.savePendingShareCleanup(second)
        store.savePendingShareCleanup(first)
        assertEquals(listOf(first, second), store.pendingShareCleanups())
        assertEquals(
            OutputDeleteOperationResult.Failed,
            processPendingShareCleanup(
                store,
                delete = { attempts++; OutputDeleteOperationResult.Failed },
                afterDelete = { refreshes++ },
            )?.result,
        )
        assertEquals(listOf(first, second), store.pendingShareCleanups())
        assertEquals(
            OutputDeleteOperationResult.IdentityMismatch,
            processPendingShareCleanup(
                store,
                delete = { attempts++; OutputDeleteOperationResult.IdentityMismatch },
                afterDelete = { refreshes++ },
            )?.result,
        )
        assertEquals(listOf(second), store.pendingShareCleanups())
        assertEquals(
            OutputDeleteOperationResult.Completed,
            processPendingShareCleanup(
                store,
                delete = { attempts++; OutputDeleteOperationResult.Completed },
                afterDelete = { refreshes++ },
            )?.result,
        )
        assertTrue(store.pendingShareCleanups().isEmpty())
        assertEquals(3, attempts)
        assertEquals(3, refreshes)
        assertTrue(shareCleanupCompletionPolicy(OutputDeleteOperationResult.Failed).warn)
        assertTrue(shareCleanupCompletionPolicy(OutputDeleteOperationResult.IdentityMismatch).clear)
        assertTrue(shareCleanupCompletionPolicy(OutputDeleteOperationResult.IdentityMismatch).warn)
    }

    @Test
    fun selectedShareCleanupQueueIsBoundedAndMalformedValuesFailClosed() {
        val (preferences, values) = inMemoryPreferences()
        val store = SettingsStore(preferences, "Scanned document")
        repeat(MAX_PENDING_SHARE_CLEANUPS) { index ->
            store.savePendingShareCleanup(
                ShareCleanupRequest(
                    CACHE_ID,
                    "00000000-0000-0000-0000-${(index + 1).toString().padStart(12, '0')}",
                    ShareCleanupKind.Images,
                ),
            )
        }
        assertThrows(IOException::class.java) {
            store.savePendingShareCleanup(
                ShareCleanupRequest(CACHE_ID, OTHER_ENTRY_ID, ShareCleanupKind.Pdf),
            )
        }
        assertFalse(
            store.canSavePendingShareCleanup(
                ShareCleanupRequest(CACHE_ID, OTHER_ENTRY_ID, ShareCleanupKind.Pdf),
            ),
        )
        values["pending_share_cleanup"] = "1:$CACHE_ID:$ENTRY_ID:pdf\n1:../outside:$ENTRY_ID:pdf"
        assertTrue(store.pendingShareCleanups().isEmpty())
        val longCacheId = "x".repeat(129)
        assertThrows(IllegalArgumentException::class.java) {
            encodePendingShareCleanup(
                ShareCleanupRequest(longCacheId, ENTRY_ID, ShareCleanupKind.Pdf),
            )
        }
        values["pending_share_cleanup"] = "1:$longCacheId:$ENTRY_ID:pdf"
        assertNull(store.pendingShareCleanup())
    }

    @Test
    fun canonicalPdfTreeUriIgnoresAStaleStoreSnapshot() {
        val (preferences, _) = inMemoryPreferences()
        val stale = SettingsStore(preferences, "Scanned document")
        val current = SettingsStore(preferences, "Scanned document")

        stale.savePdfTreeUris(current = "content://docs/tree/old", pending = null)
        current.savePdfTreeUris(current = "content://docs/tree/current", pending = null)
        stale.save(AppSettings(pdfTreeUri = "content://docs/tree/old"))

        assertEquals("content://docs/tree/current", canonicalPdfTreeUri(stale))
    }

    @Test
    fun dirtyRefreshGateRunsAgainWhenARequestArrivesDuringRefresh() {
        val gate = DirtyRefreshGate()

        assertTrue(gate.request())
        assertTrue(gate.consume())
        assertFalse(gate.request())
        assertTrue(gate.consume())
        assertFalse(gate.consume())
        assertTrue(gate.request())
    }

    @Test
    fun settingsInstancesDoNotResurrectAReleasedPendingTreeGrant() {
        val (preferences, _) = inMemoryPreferences()
        val first = SettingsStore(preferences, "Scanned document")
        val second = SettingsStore(preferences, "Scanned document")

        first.savePdfTreeUris(
            current = "content://docs/tree/current",
            pending = "content://docs/tree/old",
        )
        second.save(AppSettings(pdfTreeUri = "content://docs/tree/current"))
        assertEquals("content://docs/tree/old", first.pendingPdfTreeUri())

        second.savePdfTreeUris(current = "content://docs/tree/current", pending = null)
        first.save(AppSettings(pdfTreeUri = "content://docs/tree/current"))
        assertNull(first.pendingPdfTreeUri())
    }

    @Test
    fun appearanceAndPdfSizeSettingsRoundTripWithStableValues() {
        val (preferences, values) = inMemoryPreferences()
        val store = SettingsStore(preferences, "Scanned document")
        val expected =
            ScanAppearanceSettings(
                colorMode = ScanColorMode.Grayscale,
                naturalIntensity = 5,
                colorIntensity = 15,
                lightTextIntensity = 25,
                grayscaleIntensity = 35,
                blackWhiteIntensity = 75,
                whiteboardIntensity = 85,
                shadows = 45,
            )
        val owner = store.authoritySnapshot().owner

        assertEquals(
            AuthorityMutationResult.Applied,
            store.trySave(
                AppSettings(
                    appearance = expected,
                    pdfSizeTarget = PdfSizeTarget.Mb10,
                ),
                expectedOwner = owner,
            ),
        )

        assertEquals(expected, store.load().appearance)
        assertEquals(PdfSizeTarget.Mb10, store.load().pdfSizeTarget)
        assertEquals("grayscale", values["appearance_mode"])
        assertEquals(5, values["appearance_natural_intensity"])
        assertEquals(25, values["appearance_light_text_intensity"])
        assertEquals(85, values["appearance_whiteboard_intensity"])
        assertEquals("10_mb", values["pdf_size_target"])
    }

    @Test
    fun settingsSurviveStoreRecreationUsedByAppUpdates() {
        val (preferences, _) = inMemoryPreferences()
        val treeUri = "content://docs/tree/scans"
        val expected =
            AppSettings(
                savePdf = false,
                saveImages = false,
                albumName = "My scans",
                multipage = false,
                allowGallery = false,
                emailSubject = "Saved subject",
                emailBody = "Saved message",
                pdfTreeUri = treeUri,
                deletePdfAfterShare = true,
                deleteImagesAfterShare = true,
                appearance =
                    ScanAppearanceSettings(
                        colorMode = ScanColorMode.Grayscale,
                        grayscaleIntensity = 63,
                        shadows = 28,
                    ),
                pdfSizeTarget = PdfSizeTarget.Custom(17_000),
            )
        val beforeUpdate = SettingsStore(preferences, "Scanned document")
        beforeUpdate.savePdfTreeUris(current = treeUri, pending = null)
        beforeUpdate.save(expected)

        val afterUpdate = SettingsStore(preferences, "Scanned document")

        assertEquals(expected, afterUpdate.load())
    }

    @Test
    fun customPdfTargetSurvivesSettingsRoundTrip() {
        val (preferences, values) = inMemoryPreferences()
        val store = SettingsStore(preferences, "Scanned document")
        val owner = store.authoritySnapshot().owner

        assertEquals(
            AuthorityMutationResult.Applied,
            store.trySave(
                AppSettings(pdfSizeTarget = PdfSizeTarget.Custom(37_000)),
                expectedOwner = owner,
            ),
        )

        assertEquals(PdfSizeTarget.Custom(37_000), store.load().pdfSizeTarget)
        assertEquals("custom_37000_kb", values["pdf_size_target"])
    }

    @Test
    fun perDocumentPdfRevisionCheckpointKeepsTheSettingsDefault() {
        val (preferences, _) = inMemoryPreferences()
        val store = SettingsStore(preferences, "Scanned document")
        assertEquals(
            AuthorityMutationResult.Applied,
            store.trySave(
                AppSettings(pdfSizeTarget = PdfSizeTarget.Mb20),
                expectedOwner = store.authoritySnapshot().owner,
            ),
        )
        val owner = store.authoritySnapshot().owner

        assertEquals(
            AuthorityMutationResult.Applied,
            store.saveActiveResult("Scan_target_5", owner),
        )

        assertEquals(PdfSizeTarget.Mb20, store.load().pdfSizeTarget)
    }

    @Test
    fun corruptAppearanceAndPdfSizeSettingsFailClosedToDefaults() {
        val (preferences, values) = inMemoryPreferences()
        values["appearance_mode"] = "future"
        values["appearance_natural_intensity"] = "wrong"
        values["appearance_color_intensity"] = -10
        values["appearance_light_text_intensity"] = 17
        values["appearance_grayscale_intensity"] = 140
        values["appearance_black_white_intensity"] = "wrong"
        values["appearance_whiteboard_intensity"] = 101
        values["appearance_shadows"] = 101
        values["pdf_size_target"] = "2_mb"

        val loaded = SettingsStore(preferences, "Scanned document").load()

        assertEquals(ScanColorMode.BlackWhite, loaded.appearance.colorMode)
        assertEquals(100, loaded.appearance.naturalIntensity)
        assertEquals(0, loaded.appearance.colorIntensity)
        assertEquals(17, loaded.appearance.lightTextIntensity)
        assertEquals(100, loaded.appearance.grayscaleIntensity)
        assertEquals(100, loaded.appearance.blackWhiteIntensity)
        assertEquals(100, loaded.appearance.whiteboardIntensity)
        assertEquals(100, loaded.appearance.shadows)
        assertEquals(PdfSizeTarget.Original, loaded.pdfSizeTarget)
    }

    @Test
    fun legacySharedFilterIntensitiesMigrateWithoutChangingTheUsersTuning() {
        val (preferences, values) = inMemoryPreferences()
        values["appearance_color_intensity"] = 23
        values["appearance_grayscale_intensity"] = 45
        values["appearance_black_white_intensity"] = 67

        val appearance = SettingsStore(preferences, "Scanned document").load().appearance

        assertEquals(23, appearance.naturalIntensity)
        assertEquals(23, appearance.colorIntensity)
        assertEquals(23, appearance.lightTextIntensity)
        assertEquals(45, appearance.grayscaleIntensity)
        assertEquals(67, appearance.blackWhiteIntensity)
        assertEquals(67, appearance.whiteboardIntensity)
    }

    @Test
    fun legacyPendingAppearanceReviewCheckpointRestoresCacheOnly() {
        val entryId = "123e4567-e89b-12d3-a456-426614174000"

        assertEquals(
            ActiveResultCheckpoint("Scan_review"),
            decodeActiveResultCheckpointPayload("3:Scan_review:$entryId"),
        )
    }

    @Test
    fun falseCommitReturnStillSucceedsWhenEveryExactReadbackMatches() {
        val (preferences, _) = inMemoryPreferences(commitResults = listOf(false, false, false))
        val store = SettingsStore(preferences, "Scanned document")
        val initialOwner = store.authoritySnapshot().owner

        assertEquals(
            AuthorityMutationResult.Applied,
            store.saveActiveResult("Scan_candidate", initialOwner),
        )
        val candidateOwner =
            initialOwner.withCheckpoint(ActiveResultCheckpoint("Scan_candidate"))
        val appearance = ScanAppearanceSettings(colorMode = ScanColorMode.Grayscale)
        assertEquals(
            AuthorityMutationResult.Applied,
            store.restoreAppearanceAuthority(appearance, PdfSizeTarget.Mb5, candidateOwner),
        )
        assertEquals(appearance, store.load().appearance)
        assertEquals(
            AuthorityMutationResult.Applied,
            store.clearActiveResult(candidateOwner),
        )
        assertNull(store.activeResultCheckpoint())
    }

    @Test
    fun staleActiveResultOwnerCannotOverwriteOrClearANewerCheckpoint() {
        val (preferences, _) = inMemoryPreferences()
        val store = SettingsStore(preferences, "Scanned document")
        val originalOwner = store.authoritySnapshot().owner

        assertEquals(
            AuthorityMutationResult.Applied,
            store.saveActiveResult("Scan_first", originalOwner),
        )
        val firstOwner = store.authoritySnapshot().owner
        assertEquals(
            AuthorityMutationResult.Applied,
            store.saveActiveResult("Scan_newer", firstOwner),
        )

        assertEquals(
            AuthorityMutationResult.Stale,
            store.saveActiveResult("Scan_stale", firstOwner),
        )
        assertEquals(AuthorityMutationResult.Stale, store.clearActiveResult(firstOwner))
        assertEquals("Scan_newer", store.activeResultCacheId())
    }

    @Test
    fun newerVmLeaseInvalidatesOlderVmWithTheSameCheckpointValue() {
        val (preferences, _) = inMemoryPreferences()
        val store = SettingsStore(preferences, "Scanned document")
        val initialOwner = store.authoritySnapshot().owner
        assertEquals(
            AuthorityMutationResult.Applied,
            store.saveActiveResult("Scan_shared", initialOwner),
        )
        val oldVmOwner = initialOwner.withCheckpoint(ActiveResultCheckpoint("Scan_shared"))
        val newVmOwner = store.authoritySnapshot().owner

        assertEquals(
            AuthorityMutationResult.Stale,
            store.trySave(AppSettings(savePdf = false), oldVmOwner),
        )
        assertEquals(AuthorityMutationResult.Stale, store.clearActiveResult(oldVmOwner))
        assertEquals(AuthorityMutationResult.Applied, store.clearActiveResult(newVmOwner))
    }

    @Test
    fun staleSettingsCannotOverwriteNewerAuthority() {
        val (preferences, _) = inMemoryPreferences()
        val store = SettingsStore(preferences, "Scanned document")
        val original = store.authoritySnapshot()
        val staleAppearance =
            ScanAppearanceSettings(colorMode = ScanColorMode.Color, colorIntensity = 12)

        assertEquals(
            AuthorityMutationResult.Applied,
            store.saveActiveResult("Scan_newer", original.owner),
        )

        assertEquals(
            AuthorityMutationResult.Stale,
            store.trySave(
                original.settings.copy(appearance = staleAppearance),
                original.owner,
            ),
        )
        assertEquals(ScanAppearanceSettings(), store.load().appearance)
        assertEquals("Scan_newer", store.activeResultCacheId())
    }

    @Test
    fun authoritySnapshotBlocksCheckpointMutationUntilRestoreWorkCompletes() {
        val (preferences, _) = inMemoryPreferences()
        val store = SettingsStore(preferences, "Scanned document")
        val restoreEntered = CountDownLatch(1)
        val releaseRestore = CountDownLatch(1)
        val writerStarted = CountDownLatch(1)
        val writerFinished = CountDownLatch(1)
        val writerResult = AtomicReference<AuthorityMutationResult>()
        val restoreOwner = AtomicReference<ActiveResultOwner>()
        val initialOwner = store.authoritySnapshot().owner

        val restore =
            thread {
                store.withAuthoritySnapshot { snapshot ->
                    restoreOwner.set(snapshot.owner)
                    restoreEntered.countDown()
                    releaseRestore.await(5, TimeUnit.SECONDS)
                }
            }
        assertTrue(restoreEntered.await(5, TimeUnit.SECONDS))
        assertEquals(
            AuthorityMutationResult.Busy,
            store.trySave(AppSettings(), initialOwner),
        )
        val writer =
            thread {
                writerStarted.countDown()
                writerResult.set(store.saveActiveResult("Scan_newer", restoreOwner.get()))
                writerFinished.countDown()
            }
        assertTrue(writerStarted.await(5, TimeUnit.SECONDS))
        assertFalse(writerFinished.await(100, TimeUnit.MILLISECONDS))

        releaseRestore.countDown()
        restore.join(5_000)
        writer.join(5_000)

        assertFalse(restore.isAlive)
        assertFalse(writer.isAlive)
        assertEquals(AuthorityMutationResult.Applied, writerResult.get())
    }

    @Test
    fun settingsScreenClosesOnlyAfterAnAppliedSave() {
        assertTrue(settingsSaveApplied(AuthorityMutationResult.Applied))
        assertFalse(settingsSaveApplied(AuthorityMutationResult.Busy))
        assertFalse(settingsSaveApplied(AuthorityMutationResult.Stale))
    }

    @Test
    fun activeResultCleanupRunsOnlyAfterCheckpointClear() {
        val operations = mutableListOf<String>()

        val result = clearActiveResultAndReconcileProvisionals(
            clearCheckpoint = {
                operations += "clear"
                AuthorityMutationResult.Applied
            },
            reconcileProvisionals = { operations += "reconcile" },
        )

        assertEquals(AuthorityMutationResult.Applied, result)
        assertEquals(listOf("clear", "reconcile"), operations)
    }

    @Test
    fun staleCheckpointClearDoesNotReconcileProvisionals() {
        var reconciled = false

        val result =
            clearActiveResultAndReconcileProvisionals(
                clearCheckpoint = { AuthorityMutationResult.Stale },
                reconcileProvisionals = { reconciled = true },
            )

        assertEquals(AuthorityMutationResult.Stale, result)
        assertFalse(reconciled)
    }

    @Test
    fun checkpointClearKeepsAuthorityUntilProvisionalReconciliationCompletes() {
        val (preferences, _) = inMemoryPreferences()
        val store = SettingsStore(preferences, "Scanned document")
        val initialOwner = store.authoritySnapshot().owner
        assertEquals(
            AuthorityMutationResult.Applied,
            store.saveActiveResult("Scan_old", initialOwner),
        )
        val oldOwner = store.authoritySnapshot().owner
        val reconciliationEntered = CountDownLatch(1)
        val releaseReconciliation = CountDownLatch(1)
        val writerFinished = CountDownLatch(1)
        val writerResult = AtomicReference<AuthorityMutationResult>()

        val cleanup =
            thread {
                clearActiveResultAndReconcileProvisionals(
                    clearCheckpoint = { store.clearActiveResult(oldOwner) },
                    reconcileProvisionals = {
                        reconciliationEntered.countDown()
                        releaseReconciliation.await(5, TimeUnit.SECONDS)
                    },
                )
            }
        assertTrue(reconciliationEntered.await(5, TimeUnit.SECONDS))
        val writer =
            thread {
                writerResult.set(
                    store.saveActiveResult("Scan_newer", oldOwner.withCheckpoint(null)),
                )
                writerFinished.countDown()
            }
        assertFalse(writerFinished.await(100, TimeUnit.MILLISECONDS))

        releaseReconciliation.countDown()
        cleanup.join(5_000)
        writer.join(5_000)

        assertFalse(cleanup.isAlive)
        assertFalse(writer.isAlive)
        assertEquals(AuthorityMutationResult.Applied, writerResult.get())
        assertEquals("Scan_newer", store.activeResultCacheId())
    }

    @Test
    fun reconciliationFailureIsReportedAsPostCheckpointCleanup() {
        val failure = IOException("provider failed")

        val thrown =
            assertThrows(ActiveResultCleanupException::class.java) {
                clearActiveResultAndReconcileProvisionals(
                    clearCheckpoint = { AuthorityMutationResult.Applied },
                    reconcileProvisionals = { throw failure },
                )
            }

        assertSame(failure, thrown.cause)
    }

    @Test
    fun checkpointClearFailureDoesNotStartProvisionalCleanup() {
        var reconciled = false

        assertThrows(IOException::class.java) {
            clearActiveResultAndReconcileProvisionals(
                clearCheckpoint = { throw IOException("checkpoint failed") },
                reconcileProvisionals = { reconciled = true },
            )
        }

        assertFalse(reconciled)
    }

    @Test
    fun failedRestoreKeepsAnAuthoritativeProvisionalCheckpointForRetry() {
        assertTrue(keepCheckpointAfterRestoreFailure(authoritativeWasProvisional = true))
        assertFalse(keepCheckpointAfterRestoreFailure(authoritativeWasProvisional = false))
    }

    @Test
    fun onlyProvisionalV3CheckpointRepairsAppearancePreferences() {
        val newerDefaults =
            AppSettings(
                appearance = ScanAppearanceSettings(colorMode = ScanColorMode.Color),
                pdfSizeTarget = PdfSizeTarget.Mb20,
            )
        val cachedDefaults =
            ScanAppearanceSettings(
                colorMode = ScanColorMode.Grayscale,
                grayscaleIntensity = 25,
            )
        val authoritative =
            CachedScan(
                baseName = "Scan_candidate",
                pages = listOf(File("page.jpg")),
                pdf = File("scan.pdf"),
                appearanceSettings = cachedDefaults,
                pdfSizeTarget = PdfSizeTarget.Mb5,
            )

        assertEquals(
            newerDefaults,
            checkpointRestoreSettings(newerDefaults, authoritative, provisional = false),
        )
        assertEquals(
            newerDefaults.copy(
                appearance = cachedDefaults,
                pdfSizeTarget = PdfSizeTarget.Mb5,
            ),
            checkpointRestoreSettings(newerDefaults, authoritative, provisional = true),
        )
        assertEquals(
            newerDefaults,
            checkpointRestoreSettings(
                newerDefaults,
                authoritative.copy(restoreAppearanceSettings = false),
                provisional = true,
            ),
        )
    }

    @Test
    fun appearanceRestoreRequiresCurrentCheckpointOwnership() {
        val (preferences, _) = inMemoryPreferences()
        val store = SettingsStore(preferences, "Scanned document")
        val initialOwner = store.authoritySnapshot().owner
        assertEquals(
            AuthorityMutationResult.Applied,
            store.saveActiveResult("Scan_candidate", initialOwner),
        )
        val owner = initialOwner.withCheckpoint(ActiveResultCheckpoint("Scan_candidate"))
        val appearance =
            ScanAppearanceSettings(
                colorMode = ScanColorMode.Grayscale,
                grayscaleIntensity = 22,
                shadows = 44,
            )

        assertEquals(
            AuthorityMutationResult.Applied,
            store.restoreAppearanceAuthority(appearance, PdfSizeTarget.Mb5, owner),
        )
        assertEquals(appearance, store.load().appearance)
        assertEquals(PdfSizeTarget.Mb5, store.load().pdfSizeTarget)
        assertEquals(
            AuthorityMutationResult.Applied,
            store.saveActiveResult("Scan_newer", owner),
        )
        assertEquals(
            AuthorityMutationResult.Stale,
            store.restoreAppearanceAuthority(ScanAppearanceSettings(), PdfSizeTarget.Original, owner),
        )
        assertEquals(appearance, store.load().appearance)
        assertEquals("Scan_newer", store.activeResultCacheId())
    }

    @Test
    fun emailLocalizationDoesNotRewriteAppearanceTransactionKeys() {
        val (preferences, values) = inMemoryPreferences()
        values["appearance_mode"] = "black_white"
        values["appearance_shadows"] = 50
        values["active_result_checkpoint"] = "1:Scan_current"

        SettingsStore(preferences, "Scanned document").saveEmailSubject("Localized subject")

        assertEquals("Localized subject", values["email_subject"])
        assertEquals("black_white", values["appearance_mode"])
        assertEquals(50, values["appearance_shadows"])
        assertEquals("1:Scan_current", values["active_result_checkpoint"])
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
    fun scannerPageLimitBoundsMultipageCapture() {
        assertEquals(20, scannerPageLimit(multipage = true))
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
        val share = gate.begin("Scan_1", ENTRY_ID)

        gate.invalidate()

        assertEquals(false, gate.isCurrent(share, listOf("Scan_1" to ENTRY_ID)))
    }

    @Test
    fun recentActionGateRequiresCurrentRowGenerationIdentity() {
        val gate = RecentActionGate()
        val share = gate.begin("Scan_1", ENTRY_ID)

        assertEquals(
            true,
            gate.isCurrent(share, listOf("Scan_1" to ENTRY_ID, "Scan_2" to null)),
        )
        assertEquals(false, gate.isCurrent(share, listOf("Scan_1" to OTHER_ENTRY_ID)))
        assertEquals(false, gate.isCurrent(share, listOf("Scan_2" to ENTRY_ID)))
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
        val unreadable = savedScan(entryId = ENTRY_ID, outputMetadataValid = false)
        val replacement = savedScan(entryId = OTHER_ENTRY_ID, outputMetadataValid = true)

        assertSame(exact, matchingSavedScan(exact, action))
        assertNull(matchingSavedScan(unreadable, action))
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
    fun backNavigationIsConsumedWhileRecentDeletionIsRunning() {
        assertEquals(
            AppBackAction.Consume,
            appBackAction(
                settingsOpen = false,
                fileDetailsOpen = false,
                state = ScreenState.Recent(emptyList(), deletionInProgress = true),
            ),
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
        assertNull(
            decodeActiveResultCheckpointPayload(
                "2:$cacheId:color:101:50:50:50",
            ),
        )
        assertNull(
            decodeActiveResultCheckpointPayload(
                "2:$cacheId:future:50:50:50:50",
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            encodeActiveResultCheckpoint("../outside")
        }
    }

    @Test
    fun malformedDurableCheckpointIsRetainedForFailClosedRecovery() {
        val (preferences, values) = inMemoryPreferences()
        values["active_result_checkpoint"] = "2:malformed"
        val store = SettingsStore(preferences, "Scanned document")

        assertThrows(IOException::class.java) { store.activeResultCheckpoint() }
        assertEquals("2:malformed", values["active_result_checkpoint"])
    }

    @Test
    fun durableActiveResultCheckpointStartsAColdLaunchInScanner() {
        assertEquals(
            InitialNavigation(
                route = RestoredRoute.Scanner,
                cacheId = null,
                clearCheckpointBeforeLaunch = true,
            ),
            initialNavigation(
                savedRoute = "result",
                savedCacheId = "Scan_saved",
                activeResultCacheId = "Scan_durable",
            ),
        )
        assertEquals(
            InitialNavigation(
                route = RestoredRoute.Scanner,
                cacheId = null,
                clearCheckpointBeforeLaunch = true,
            ),
            initialNavigation(
                savedRoute = "recent",
                savedCacheId = null,
                activeResultCacheId = "Scan_durable",
            ),
        )
        assertEquals(
            InitialNavigation(
                route = RestoredRoute.Scanner,
                cacheId = null,
                clearCheckpointBeforeLaunch = true,
            ),
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
    fun coldNavigationIgnoresMalformedOrAbsentCheckpoints() {
        assertEquals(
            InitialNavigation(
                route = RestoredRoute.Scanner,
                cacheId = null,
                clearCheckpointBeforeLaunch = true,
            ),
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

    private fun uiSavedScan(): SavedScan {
        val pages = listOf(File("page-1.jpg"), File("page-2.jpg"))
        val cached =
            CachedScan(
                baseName = CACHE_ID,
                pages = pages,
                pdf = File("scan.pdf"),
                entryId = ENTRY_ID,
                sourcePages = pages,
                appearance = ScanAppearance(),
                appearanceSettings = ScanAppearanceSettings(),
            )
        return SavedScan(
            cached = cached,
            savedImages = emptyList(),
            savedPdf = null,
            outputMetadataValid = true,
        )
    }

    private fun fileDetailAvailability(
        savedImages: Int = 0,
        valid: Boolean = true,
        imagesChangeable: Boolean = true,
        imagesRelocatable: Boolean = imagesChangeable,
        outputsRenameable: Boolean = false,
    ): FileDetailAvailability =
        FileDetailAvailability(
            outputMetadataValid = valid,
            hasEntryId = true,
            canChoosePdfSize = true,
            pdfAvailable = true,
            pageCount = 2,
            savedImageCount = savedImages,
            canChangeImages = imagesChangeable,
            canRelocateImages = imagesRelocatable,
            canRenamePdf = outputsRenameable,
            canRenameImages = outputsRenameable,
        )

    private fun inMemoryPreferences(
        commitResults: List<Boolean> = emptyList(),
        afterCommit: (Int, MutableMap<String, Any?>) -> Unit = { _, _ -> },
    ): Pair<SharedPreferences, MutableMap<String, Any?>> {
        val values = mutableMapOf<String, Any?>()
        var commitIndex = 0
        lateinit var editor: SharedPreferences.Editor
        editor =
            Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(SharedPreferences.Editor::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "putBoolean", "putString", "putInt" ->
                        editor.also { values[args!![0] as String] = args[1] }
                    "remove" -> editor.also { values.remove(args!![0] as String) }
                    "apply" -> null
                    "commit" -> {
                        val index = commitIndex++
                        afterCommit(index, values)
                        commitResults.getOrElse(index) { true }
                    }
                    else -> throw UnsupportedOperationException(method.name)
                }
            } as SharedPreferences.Editor
        val preferences =
            Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(SharedPreferences::class.java),
            ) { _, method, args ->
                val key = args?.firstOrNull() as? String
                when (method.name) {
                    "getBoolean" ->
                        values[key]?.let { it as? Boolean ?: throw ClassCastException() }
                            ?: args!![1] as Boolean
                    "getString" ->
                        values[key]?.let { it as? String ?: throw ClassCastException() }
                            ?: args!![1] as String?
                    "getInt" ->
                        values[key]?.let { it as? Int ?: throw ClassCastException() }
                            ?: args!![1] as Int
                    "contains" -> values.containsKey(key)
                    "getAll" -> values.toMap()
                    "edit" -> editor
                    else -> throw UnsupportedOperationException(method.name)
                }
            } as SharedPreferences
        return preferences to values
    }

    private companion object {
        const val CACHE_ID = "Scan_2026-08-09_12-12-00"
        const val ENTRY_ID = "00000000-0000-0000-0000-000000000001"
        const val OTHER_ENTRY_ID = "00000000-0000-0000-0000-000000000002"
    }

}
