package com.majkeylab.scanit

import java.io.ByteArrayInputStream
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
        val record = ScannerV2PageRecord(
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

    private fun fingerprint(bytes: ByteArray): OutputFingerprint =
        ByteArrayInputStream(bytes).use { readOutputFingerprint(it, bytes.size.toLong()) }
}
