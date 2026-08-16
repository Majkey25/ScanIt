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
    fun pendingCaptureIsStrictAndBoundToCameraStage() {
        val pageId = PageId.parse(UUID.randomUUID().toString())
        val capturing = emptyManifest().withPendingCapture(pageId)

        assertEquals(capturing, decodeScannerV2Manifest(encodeScannerV2Manifest(capturing)))
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
    ): ScannerV2Manifest {
        val pageId = PageId.parse(UUID.randomUUID().toString())
        val record = pageRecord(pageId, sourceFingerprint, renderedFingerprint)
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
        filterId = "drawing",
        renderedFingerprint = renderedFingerprint,
    )

    private fun fingerprint(bytes: ByteArray): OutputFingerprint =
        ByteArrayInputStream(bytes).use { readOutputFingerprint(it, bytes.size.toLong()) }
}
