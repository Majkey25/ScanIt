package com.majkeylab.scanit

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScannerV2StoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun strictManifestRoundTripPreservesSessionAndPageAuthority() {
        val manifest = manifest(updatedAtMillis = 1234)

        val decoded = decodeScannerV2Manifest(encodeScannerV2Manifest(manifest))

        assertEquals(manifest, decoded)
    }

    @Test
    fun finishingManifestPersistsExactPreparedResultCacheAuthority() {
        val review = manifest(updatedAtMillis = 1234)
        val finishing = ScannerV2Manifest.create(
            sessionId = review.sessionId,
            state = ScannerSessionGate.finish(review.state),
            pages = review.pages,
            resultCacheId = "Scan_20260816_120000",
            updatedAtMillis = 1235,
        )

        val decoded = decodeScannerV2Manifest(encodeScannerV2Manifest(finishing))

        assertEquals(finishing, decoded)
        assertEquals("Scan_20260816_120000", decoded?.resultCacheId)
    }

    @Test
    fun editedRevisionPersistsExactParentGenerationAcrossReviewAndFinish() {
        val review = manifest(updatedAtMillis = 1234)
        val source = ScannerV2EditSource(
            cacheId = "Scan_20260816_110000",
            entryId = "00000000-0000-0000-0000-000000000001",
        )
        val editing = ScannerV2Manifest.create(
            sessionId = review.sessionId,
            state = review.state,
            pages = review.pages,
            editSource = source,
            updatedAtMillis = 1235,
        )
        val finishing = ScannerV2Manifest.create(
            sessionId = editing.sessionId,
            state = ScannerSessionGate.finish(editing.state),
            pages = editing.pages,
            editSource = editing.editSource,
            updatedAtMillis = 1236,
        )

        val decoded = decodeScannerV2Manifest(encodeScannerV2Manifest(finishing))

        assertEquals(source, decoded?.editSource)
    }

    @Test
    fun editedRevisionRejectsUnsafeOrSelfReferentialParentAuthority() {
        val review = manifest(updatedAtMillis = 1234)
        val finishingState = ScannerSessionGate.finish(review.state)
        assertThrows(IllegalArgumentException::class.java) {
            ScannerV2EditSource(
                cacheId = "../escape",
                entryId = "00000000-0000-0000-0000-000000000001",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScannerV2Manifest.create(
                sessionId = review.sessionId,
                state = finishingState,
                pages = review.pages,
                resultCacheId = "Scan_20260816_110000",
                editSource = ScannerV2EditSource(
                    cacheId = "Scan_20260816_110000",
                    entryId = "00000000-0000-0000-0000-000000000001",
                ),
                updatedAtMillis = 1235,
            )
        }
    }

    @Test
    fun resultCacheAuthorityIsOnlyAllowedForFinishingAndMustBeSafe() {
        val review = manifest(updatedAtMillis = 1234)

        assertThrows(IllegalArgumentException::class.java) {
            ScannerV2Manifest.create(
                sessionId = review.sessionId,
                state = review.state,
                pages = review.pages,
                resultCacheId = "Scan_20260816_120000",
                updatedAtMillis = 1235,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScannerV2Manifest.create(
                sessionId = review.sessionId,
                state = ScannerSessionGate.finish(review.state),
                pages = review.pages,
                resultCacheId = "../escape",
                updatedAtMillis = 1235,
            )
        }
    }

    @Test
    fun exactFinishedSessionCanBeRemovedBeforeStartingANewScan() {
        val store = ScannerV2Store(temporary.newFolder("finished-delete"))
        val initial = emptyManifest()
        store.create(initial)
        val sourceBytes = byteArrayOf(1, 2, 3)
        val renderedBytes = byteArrayOf(4, 5, 6)
        val review = manifest(
            sessionId = initial.sessionId,
            sourceFingerprint = fingerprint(sourceBytes),
            renderedFingerprint = fingerprint(renderedBytes),
        )
        val page = review.pages.single()
        store.sourceFile(review.sessionId, page.pageId).writeBytes(sourceBytes)
        store.renderedFile(review, page).writeBytes(renderedBytes)
        store.update(initial, review)
        val finishing = ScannerV2Manifest.create(
            sessionId = review.sessionId,
            state = ScannerSessionGate.finish(review.state),
            pages = review.pages,
            resultCacheId = "Scan_20260816_120000",
            updatedAtMillis = 2,
        )
        store.update(review, finishing)

        assertTrue(store.deleteForFreshLaunch(finishing))
        assertNull(store.loadActive())
    }

    @Test
    fun freshLaunchCanDiscardAnExactReviewSession() {
        val store = ScannerV2Store(temporary.newFolder("review-launch-delete"))
        val initial = emptyManifest()
        store.create(initial)
        val sourceBytes = byteArrayOf(1, 2, 3)
        val renderedBytes = byteArrayOf(4, 5, 6)
        val review = manifest(
            sessionId = initial.sessionId,
            sourceFingerprint = fingerprint(sourceBytes),
            renderedFingerprint = fingerprint(renderedBytes),
        )
        val page = review.pages.single()
        store.sourceFile(review.sessionId, page.pageId).writeBytes(sourceBytes)
        store.renderedFile(review, page).writeBytes(renderedBytes)
        store.update(initial, review)

        assertTrue(store.deleteForFreshLaunch(review))
        assertNull(store.loadActive())
    }

    @Test
    fun freshLaunchCanDiscardAnExactInterruptedCapture() {
        val store = ScannerV2Store(temporary.newFolder("capture-launch-delete"))
        val initial = emptyManifest()
        store.create(initial)
        val pageId = PageId.parse(UUID.randomUUID().toString())
        val pending = initial.withPendingCapture(pageId, updatedAtMillis = 2)
        store.update(initial, pending)
        store.captureFile(initial.sessionId, pageId).writeBytes(byteArrayOf(1))
        store.sourceFile(initial.sessionId, pageId).writeBytes(byteArrayOf(2))

        assertTrue(store.deleteForFreshLaunch(pending))
        assertNull(store.loadActive())
    }

    @Test
    fun versionThreeManifestMigratesOriginalFilterWithoutLosingAuthority() {
        val original = manifest(
            updatedAtMillis = 1234,
            appearance = ScannerV2Appearance.original(),
        )
        val legacy = JSONObject(String(encodeScannerV2Manifest(original), Charsets.UTF_8)).apply {
            put("version", 3)
            remove("pendingCaptureUseFullFrame")
            remove("resultCacheId")
            remove("editSourceCacheId")
            remove("editSourceEntryId")
            getJSONArray("pages").getJSONObject(0).apply {
                remove("filterIntensity")
                remove("filterShadows")
                remove("renderFileId")
            }
        }.toString().toByteArray()

        val migrated = decodeScannerV2Manifest(legacy)

        assertEquals(original, migrated)
    }

    @Test
    fun versionFourManifestPreservesAppearanceWithoutInventingRenderIdentity() {
        val original = manifest(updatedAtMillis = 1234)
        val versionFour = JSONObject(String(encodeScannerV2Manifest(original), Charsets.UTF_8)).apply {
            put("version", 4)
            remove("pendingCaptureUseFullFrame")
            remove("resultCacheId")
            remove("editSourceCacheId")
            remove("editSourceEntryId")
            getJSONArray("pages").getJSONObject(0).remove("renderFileId")
        }.toString().toByteArray()

        val migrated = decodeScannerV2Manifest(versionFour)

        assertEquals(original, migrated)
    }

    @Test
    fun versionFiveManifestMigratesWithoutInventingResultCacheAuthority() {
        val original = manifest(updatedAtMillis = 1234)
        val versionFive = JSONObject(String(encodeScannerV2Manifest(original), Charsets.UTF_8)).apply {
            put("version", 5)
            remove("pendingCaptureUseFullFrame")
            remove("resultCacheId")
            remove("editSourceCacheId")
            remove("editSourceEntryId")
        }.toString().toByteArray()

        val migrated = decodeScannerV2Manifest(versionFive)

        assertEquals(original, migrated)
        assertNull(migrated?.resultCacheId)
    }

    @Test
    fun versionSixManifestKeepsResultAuthorityWithoutInventingEditSource() {
        val review = manifest(updatedAtMillis = 1234)
        val original = ScannerV2Manifest.create(
            sessionId = review.sessionId,
            state = ScannerSessionGate.finish(review.state),
            pages = review.pages,
            resultCacheId = "Scan_20260816_120000",
            updatedAtMillis = 1235,
        )
        val versionSix =
            JSONObject(String(encodeScannerV2Manifest(original), Charsets.UTF_8)).apply {
                put("version", 6)
                remove("pendingCaptureUseFullFrame")
                remove("editSourceCacheId")
                remove("editSourceEntryId")
            }.toString().toByteArray()

        val migrated = decodeScannerV2Manifest(versionSix)

        assertEquals(original, migrated)
        assertNull(migrated?.editSource)
    }

    @Test
    fun previewUsesRenderedFileOnlyAfterItsFingerprintIsPublished() {
        val store = ScannerV2Store(temporary.newFolder("preview"))
        val initial = emptyManifest()
        store.create(initial)
        val sourceBytes = byteArrayOf(1, 2, 3)
        val renderedBytes = byteArrayOf(4, 5, 6)
        val pending = pageRecord(
            sourceFingerprint = fingerprint(sourceBytes),
            renderedFingerprint = null,
        )
        store.sourceFile(initial.sessionId, pending.pageId).writeBytes(sourceBytes)
        val pendingManifest = manifest(
            sessionId = initial.sessionId,
            sourceFingerprint = pending.sourceFingerprint,
            renderedFingerprint = null,
            pageId = pending.pageId,
        )
        store.update(initial, pendingManifest)

        assertEquals(
            store.sourceFile(initial.sessionId, pending.pageId),
            store.previewFile(pendingManifest, pending),
        )

        store.renderedFile(initial.sessionId, pending.pageId).writeBytes(renderedBytes)
        val published = pending.copy(renderedFingerprint = fingerprint(renderedBytes))
        val publishedManifest = ScannerV2Manifest.create(
            sessionId = initial.sessionId,
            state = pendingManifest.state,
            pages = listOf(published),
            updatedAtMillis = 2,
        )
        store.update(pendingManifest, publishedManifest)

        assertEquals(
            store.renderedFile(initial.sessionId, pending.pageId),
            store.previewFile(publishedManifest, published),
        )
    }

    @Test
    fun interruptedAppearanceChangeRemovesOnlyTheImmutableCandidateAndKeepsTheGoodRender() {
        val store = ScannerV2Store(temporary.newFolder("appearance-recovery"))
        val initial = emptyManifest()
        store.create(initial)
        val sourceBytes = byteArrayOf(1, 2, 3)
        val renderedBytes = byteArrayOf(4, 5, 6)
        val current = manifest(
            sessionId = initial.sessionId,
            sourceFingerprint = fingerprint(sourceBytes),
            renderedFingerprint = fingerprint(renderedBytes),
        )
        val page = current.pages.single()
        store.sourceFile(current.sessionId, page.pageId).writeBytes(sourceBytes)
        val rendered = store.renderedFile(current, page).apply {
            writeBytes(renderedBytes)
        }
        store.update(initial, current)
        val candidate = store.renderCandidateFile(
            current.sessionId,
            page.pageId,
            UUID.randomUUID().toString(),
        ).apply { writeBytes(byteArrayOf(7, 8, 9)) }

        assertEquals(current, store.loadActive())
        assertFalse(candidate.exists())
        assertTrue(rendered.exists())
        assertTrue(store.sourceFile(current.sessionId, page.pageId).exists())
    }

    @Test
    fun completedAppearanceChangeSwitchesToNewRenderThenRemovesTheOldRender() {
        val store = ScannerV2Store(temporary.newFolder("appearance-publication"))
        val initial = emptyManifest()
        store.create(initial)
        val sourceBytes = byteArrayOf(1, 2, 3)
        val oldBytes = byteArrayOf(4, 5, 6)
        val current = manifest(
            sessionId = initial.sessionId,
            sourceFingerprint = fingerprint(sourceBytes),
            renderedFingerprint = fingerprint(oldBytes),
        )
        val page = current.pages.single()
        store.sourceFile(current.sessionId, page.pageId).writeBytes(sourceBytes)
        val oldRender = store.renderedFile(current, page).apply { writeBytes(oldBytes) }
        store.update(initial, current)
        val newBytes = byteArrayOf(7, 8, 9)
        val renderFileId = UUID.randomUUID().toString()
        val newRender = store.renderCandidateFile(current.sessionId, page.pageId, renderFileId).apply {
            writeBytes(newBytes)
        }
        val completed = completeScannerV2PageRender(
            current = current,
            pageId = page.pageId,
            crop = page.crop,
            rotationQuarterTurns = page.rotationQuarterTurns,
            appearance = ScannerV2Appearance.defaultFor(ScannerV2Filter.Color),
            renderFileId = renderFileId,
            renderedFingerprint = fingerprint(newBytes),
            updatedAtMillis = 2,
        )

        store.update(current, completed)

        assertEquals(completed, store.loadActive())
        assertEquals(newRender, store.previewFile(completed, completed.pages.single()))
        assertTrue(newRender.exists())
        assertFalse(oldRender.exists())
    }

    @Test
    fun pendingCaptureIsStrictAndBoundToCameraStage() {
        val pageId = PageId.parse(UUID.randomUUID().toString())
        val capturing = emptyManifest().withPendingCapture(pageId)
        val importing = emptyManifest().withPendingCapture(pageId, useFullFrame = true)

        assertEquals(capturing, decodeScannerV2Manifest(encodeScannerV2Manifest(capturing)))
        assertTrue(importing.pendingCaptureUseFullFrame)
        assertEquals(importing, decodeScannerV2Manifest(encodeScannerV2Manifest(importing)))
        assertTrue(ScannerV2Store(temporary.newFolder("pending" )).run {
            create(capturing)
            captureFile(capturing.sessionId, pageId).name == ".capture-${pageId.value}.jpg"
        })
        val reviewing = manifest()
        assertThrows(IllegalArgumentException::class.java) {
            ScannerV2Manifest.create(
                sessionId = reviewing.sessionId,
                state = reviewing.state,
                pages = reviewing.pages,
                pendingCaptureId = pageId,
                updatedAtMillis = 1,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScannerV2Manifest.create(
                sessionId = capturing.sessionId,
                state = capturing.state,
                pages = capturing.pages,
                pendingCaptureUseFullFrame = true,
                updatedAtMillis = 1,
            )
        }
    }

    @Test
    fun versionSevenPendingCaptureMigratesAsCameraCapture() {
        val pageId = PageId.parse(UUID.randomUUID().toString())
        val initial = emptyManifest()
        val editSource = ScannerV2EditSource(
            cacheId = "Scan_20260816_120000",
            entryId = UUID.randomUUID().toString(),
        )
        val current = ScannerV2Manifest.create(
            sessionId = initial.sessionId,
            state = initial.state,
            pages = initial.pages,
            pendingCaptureId = pageId,
            pendingCaptureUseFullFrame = true,
            editSource = editSource,
            updatedAtMillis = initial.updatedAtMillis,
        )
        val versionSeven = JSONObject(String(encodeScannerV2Manifest(current), Charsets.UTF_8)).apply {
            put("version", 7)
            remove("pendingCaptureUseFullFrame")
        }.toString().toByteArray()

        val migrated = requireNotNull(decodeScannerV2Manifest(versionSeven))

        assertEquals(pageId, migrated.pendingCaptureId)
        assertFalse(migrated.pendingCaptureUseFullFrame)
        assertEquals(editSource, migrated.editSource)
    }

    @Test
    fun malformedExtraMissingOversizeAndUnsafeValuesAreRejected() {
        val encoded = encodeScannerV2Manifest(manifest())
        val extra = JSONObject(String(encoded, Charsets.UTF_8)).put("extra", true).toString().toByteArray()
        val missing = JSONObject(String(encoded, Charsets.UTF_8)).apply { remove("stage") }.toString().toByteArray()
        val badId = JSONObject(String(encoded, Charsets.UTF_8)).put("sessionId", "../escape").toString().toByteArray()
        val badFingerprint = JSONObject(String(encoded, Charsets.UTF_8)).apply {
            getJSONArray("pages").getJSONObject(0).put("sourceSha256", "bad")
        }.toString().toByteArray()

        assertNull(decodeScannerV2Manifest(extra))
        assertNull(decodeScannerV2Manifest(missing))
        assertNull(decodeScannerV2Manifest(badId))
        assertNull(decodeScannerV2Manifest(badFingerprint))
        assertNull(decodeScannerV2Manifest(ByteArray(MAX_SCANNER_V2_MANIFEST_BYTES + 1) { 'x'.code.toByte() }))
    }

    @Test
    fun atomicUpdatePreservesOldManifestWhenMoveFails() {
        val store = ScannerV2Store(temporary.newFolder("scanner-v2"), nowMillis = { 10_000 })
        val initial = emptyManifest(updatedAtMillis = 10_000)
        val directory = store.create(initial)
        val oldBytes = store.manifestFile(initial.sessionId).readBytes()
        val replacement = initial.withUpdatedAt(10_001)

        assertThrows(IOException::class.java) {
            store.update(initial, replacement) { _: Path, _: Path ->
                throw IOException("simulated interrupted atomic move")
            }
        }

        assertArrayEquals(oldBytes, store.manifestFile(initial.sessionId).readBytes())
        assertFalse(directory.resolve(SCANNER_V2_MANIFEST_TEMP_NAME).exists())
        assertEquals(initial, store.loadActive())
    }

    @Test
    fun updateRequiresExactPreviousManifestAndVerifiedSourceFile() {
        val store = ScannerV2Store(temporary.newFolder("scanner-v2"), nowMillis = { 10_000 })
        val initial = emptyManifest(updatedAtMillis = 10_000)
        store.create(initial)
        val sourceBytes = byteArrayOf(1, 2, 3)
        val wanted = manifest(
            sessionId = initial.sessionId,
            updatedAtMillis = 10_001,
            sourceFingerprint = fingerprint(sourceBytes),
            renderedFingerprint = null,
        )
        store.sourceFile(initial.sessionId, wanted.pages.single().pageId).writeBytes(sourceBytes)

        store.update(initial, wanted)

        assertEquals(wanted, store.loadActive())
        assertThrows(IOException::class.java) {
            store.update(initial, wanted.withUpdatedAt(10_002))
        }
        store.sourceFile(initial.sessionId, wanted.pages.single().pageId).writeBytes(byteArrayOf(9, 9, 9))
        assertThrows(IOException::class.java) {
            store.update(wanted, wanted.withUpdatedAt(10_003))
        }
    }

    @Test
    fun selectionUpdateChangesOnlySelectionWithoutRehashingPageFiles() {
        val store = ScannerV2Store(temporary.newFolder("selection"))
        val initial = emptyManifest()
        store.create(initial)
        val firstBytes = byteArrayOf(1, 2, 3)
        val secondBytes = byteArrayOf(4, 5, 6)
        val first = pageRecord(
            sourceFingerprint = fingerprint(firstBytes),
            renderedFingerprint = null,
        )
        val second = pageRecord(
            sourceFingerprint = fingerprint(secondBytes),
            renderedFingerprint = null,
        )
        store.sourceFile(initial.sessionId, first.pageId).writeBytes(firstBytes)
        store.sourceFile(initial.sessionId, second.pageId).writeBytes(secondBytes)
        val reviewing = ScannerV2Manifest.create(
            sessionId = initial.sessionId,
            state = ScannerSessionState.restore(
                generation = 2,
                pages = listOf(ScannerPage(first.pageId), ScannerPage(second.pageId)),
                selectedIndex = 0,
                stage = ScannerSessionStage.Reviewing,
                pendingReplacementIndex = null,
            ),
            pages = listOf(first, second),
            updatedAtMillis = 2,
        )
        store.update(initial, reviewing)
        store.sourceFile(initial.sessionId, first.pageId).writeBytes(byteArrayOf(9, 9, 9))
        val selectedSecond = ScannerV2Manifest.create(
            sessionId = reviewing.sessionId,
            state = ScannerSessionState.restore(
                generation = reviewing.state.generation,
                pages = reviewing.state.pages,
                selectedIndex = 1,
                stage = reviewing.state.stage,
                pendingReplacementIndex = reviewing.state.pendingReplacementIndex,
            ),
            pages = reviewing.pages,
            retiredPages = reviewing.retiredPages,
            pendingCaptureId = reviewing.pendingCaptureId,
            updatedAtMillis = 3,
        )

        store.updateSelection(reviewing, selectedSecond)

        assertEquals(
            selectedSecond,
            decodeScannerV2Manifest(store.manifestFile(initial.sessionId).readBytes()),
        )
        val reordered = ScannerV2Manifest.create(
            sessionId = selectedSecond.sessionId,
            state = ScannerSessionState.restore(
                generation = selectedSecond.state.generation,
                pages = listOf(ScannerPage(second.pageId), ScannerPage(first.pageId)),
                selectedIndex = 0,
                stage = selectedSecond.state.stage,
                pendingReplacementIndex = selectedSecond.state.pendingReplacementIndex,
            ),
            pages = listOf(second, first),
            updatedAtMillis = 4,
        )
        assertThrows(IllegalArgumentException::class.java) {
            store.updateSelection(selectedSecond, reordered)
        }
    }

    @Test
    fun canonicalPathsCannotEscapeSessionRoot() {
        val root = temporary.newFolder("scanner-v2")
        val store = ScannerV2Store(root, nowMillis = { 1 })
        val initial = emptyManifest()
        store.create(initial)

        val source = store.sourceFile(initial.sessionId, PageId.parse(UUID.randomUUID().toString()))

        assertEquals(root.canonicalFile, source.parentFile?.parentFile?.canonicalFile)
        assertThrows(IllegalArgumentException::class.java) { store.manifestFile("../escape") }
    }

    @Test
    fun safeRootAliasIsCanonicalizedBeforeSessionChecks() {
        val applicationFiles = temporary.newFolder("application-files")
        val canonicalRoot = applicationFiles.resolve("scanner-v2-sessions").apply { mkdir() }
        val aliasedRoot = File(applicationFiles, ".${File.separator}scanner-v2-sessions")

        val store = ScannerV2Store(aliasedRoot)
        val manifest = emptyManifest()
        val session = store.create(manifest)

        assertEquals(canonicalRoot.canonicalFile, session.parentFile)
        assertEquals(manifest, store.loadActive())
    }

    @Test
    fun loadRemovesOnlyExactUnpublishedRenderAndBlocksUnknownFiles() {
        val root = temporary.newFolder("scanner-v2")
        val store = ScannerV2Store(root)
        val sourceBytes = byteArrayOf(1, 2, 3)
        val manifest = manifest(sourceFingerprint = fingerprint(sourceBytes), renderedFingerprint = null)
        val initial = emptyManifest(sessionId = manifest.sessionId)
        val directory = store.create(initial)
        store.sourceFile(manifest.sessionId, manifest.pages.single().pageId).writeBytes(sourceBytes)
        store.update(initial, manifest)
        val unpublished = store.renderedFile(manifest.sessionId, manifest.pages.single().pageId)
        unpublished.writeBytes(byteArrayOf(4, 5, 6))

        assertEquals(manifest, store.loadActive())
        assertFalse(unpublished.exists())

        directory.resolve("unknown.backup").writeText("keep")
        assertThrows(IOException::class.java) { store.loadActive() }
        assertTrue(directory.resolve("unknown.backup").exists())
    }

    @Test
    fun pendingCaptureCleanupDeletesOnlyExactOperationFiles() {
        val root = temporary.newFolder("scanner-v2")
        val store = ScannerV2Store(root)
        val pageId = PageId.parse(UUID.randomUUID().toString())
        val initial = emptyManifest()
        store.create(initial)
        val pending = initial.withPendingCapture(pageId, updatedAtMillis = 2)
        store.update(initial, pending)
        val capture = store.captureFile(initial.sessionId, pageId).apply { writeBytes(byteArrayOf(1)) }
        val source = store.sourceFile(initial.sessionId, pageId).apply { writeBytes(byteArrayOf(2)) }

        assertEquals(pending, store.loadActive())
        assertTrue(store.deletePendingCaptureFiles(pending))
        assertFalse(capture.exists())
        assertFalse(source.exists())
        assertEquals(pending, store.loadActive())
    }

    @Test
    fun retiredPageFilesRemainJournaledUntilExactCleanup() {
        val root = temporary.newFolder("scanner-v2")
        val store = ScannerV2Store(root)
        val initial = emptyManifest()
        store.create(initial)
        val activeSource = byteArrayOf(1, 2, 3)
        val retiredSource = byteArrayOf(4, 5, 6)
        val active = pageRecord(sourceFingerprint = fingerprint(activeSource), renderedFingerprint = null)
        val retired = pageRecord(sourceFingerprint = fingerprint(retiredSource), renderedFingerprint = null)
        store.sourceFile(initial.sessionId, active.pageId).writeBytes(activeSource)
        store.sourceFile(initial.sessionId, retired.pageId).writeBytes(retiredSource)
        val journaled = ScannerV2Manifest.create(
            sessionId = initial.sessionId,
            state = ScannerSessionState.restore(
                generation = 2,
                pages = listOf(ScannerPage(active.pageId)),
                selectedIndex = 0,
                stage = ScannerSessionStage.Reviewing,
                pendingReplacementIndex = null,
            ),
            pages = listOf(active),
            retiredPages = listOf(retired),
            updatedAtMillis = 2,
        )
        store.update(initial, journaled)

        assertEquals(journaled, store.loadActive())
        val cleaned = store.reconcileRetiredPages(journaled)

        assertTrue(cleaned.retiredPages.isEmpty())
        assertFalse(store.sourceFile(initial.sessionId, retired.pageId).exists())
        assertTrue(store.sourceFile(initial.sessionId, active.pageId).exists())
        assertEquals(cleaned, store.loadActive())
    }

    @Test
    fun cleanupDeletesOnlyExpiredCancelledExactSessions() {
        var now = 10_000L
        val root = temporary.newFolder("scanner-v2")
        val store = ScannerV2Store(root, nowMillis = { now })
        val cancelled = emptyManifest(
            sessionId = UUID.randomUUID().toString(),
            stage = ScannerSessionStage.Cancelled,
            updatedAtMillis = now,
        )
        store.create(cancelled)
        now += SCANNER_V2_SESSION_RETENTION_MILLIS + 1

        assertEquals(1, store.cleanupExpired())
        assertFalse(store.sessionDirectory(cancelled.sessionId).exists())

        val blocked = emptyManifest(
            sessionId = UUID.randomUUID().toString(),
            stage = ScannerSessionStage.Cancelled,
            updatedAtMillis = 1,
        )
        val blockedDirectory = store.create(blocked)
        blockedDirectory.resolve("unknown.backup").writeText("keep")
        assertEquals(0, store.cleanupExpired())
        assertTrue(blockedDirectory.exists())
    }

    private fun emptyManifest(
        sessionId: String = UUID.randomUUID().toString(),
        stage: ScannerSessionStage = ScannerSessionStage.Capturing,
        updatedAtMillis: Long = 1,
    ): ScannerV2Manifest = ScannerV2Manifest.create(
        sessionId = sessionId,
        state = ScannerSessionState.restore(
            generation = 1,
            pages = emptyList(),
            selectedIndex = null,
            stage = stage,
            pendingReplacementIndex = null,
        ),
        pages = emptyList(),
        updatedAtMillis = updatedAtMillis,
    )

    private fun manifest(
        sessionId: String = UUID.randomUUID().toString(),
        updatedAtMillis: Long = 1,
        sourceFingerprint: OutputFingerprint = OutputFingerprint(3, "a".repeat(64)),
        renderedFingerprint: OutputFingerprint? = OutputFingerprint(2, "b".repeat(64)),
        appearance: ScannerV2Appearance = ScannerV2Appearance(
            ScannerV2Filter.Grayscale,
            intensity = 72,
            shadows = 18,
        ),
        pageId: PageId = PageId.parse(UUID.randomUUID().toString()),
    ): ScannerV2Manifest {
        val record = pageRecord(pageId, sourceFingerprint, renderedFingerprint, appearance)
        return ScannerV2Manifest.create(
            sessionId = sessionId,
            state = ScannerSessionState.restore(
                generation = 7,
                pages = listOf(ScannerPage(pageId)),
                selectedIndex = 0,
                stage = ScannerSessionStage.Reviewing,
                pendingReplacementIndex = null,
            ),
            pages = listOf(record),
            updatedAtMillis = updatedAtMillis,
        )
    }

    private fun pageRecord(
        pageId: PageId = PageId.parse(UUID.randomUUID().toString()),
        sourceFingerprint: OutputFingerprint,
        renderedFingerprint: OutputFingerprint?,
        appearance: ScannerV2Appearance = ScannerV2Appearance(
            ScannerV2Filter.Grayscale,
            intensity = 72,
            shadows = 18,
        ),
    ): ScannerV2PageRecord = ScannerV2PageRecord(
        pageId = pageId,
        sourceFingerprint = sourceFingerprint,
        crop = PageQuad.create(
            NormalizedPoint(.1, .1),
            NormalizedPoint(.9, .12),
            NormalizedPoint(.88, .9),
            NormalizedPoint(.12, .88),
        ),
        rotationQuarterTurns = 1,
        appearance = appearance,
        renderedFingerprint = renderedFingerprint,
    )

    private fun fingerprint(bytes: ByteArray): OutputFingerprint =
        ByteArrayInputStream(bytes).use { readOutputFingerprint(it, bytes.size.toLong()) }
}
