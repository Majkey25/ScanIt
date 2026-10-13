package com.majkeylab.scanit

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Secure-behavior assertions intentionally fail on the vulnerable production baseline.
class StorageSecurityDeviceTest {
    private val ownedPdfs = linkedMapOf<String, PdfOutputRef>()
    private val ownedImages = linkedMapOf<String, ImageOutputRef>()

    @Test
    fun provisionalSafPdfRecoveryRequiresExactBytes() = provisionalSafRecovery(true)

    @Test
    fun provisionalSafImageRecoveryRequiresExactBytes() = provisionalSafRecovery(false)

    @Test
    fun unknownSafCreateRetainsMarkerAndDoesNotFallBackToDownloads() =
        withScan(1) { context, storage, cached ->
            withTree(context, SecurityProviderMode.Stable) { tree ->
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, SECURITY_ROOT_ID)
                // Exhaust the existing fixture's bounded capacity to fail inside provider creation.
                repeat(8) { index ->
                    checkNotNull(DocumentsContract.createDocument(
                        context.contentResolver, parent, "application/pdf", "occupied-$index.pdf",
                    ))
                }
                val before = outputs(cached)
                val failure = rejection { storage.savePdf(cached, "SeliaScanSecurityTest", tree.toString()) }
                rememberMedia(cached)
                assertNotNull("Unknown SAF outcome must not become a successful Downloads save", failure)
                assertEquals(before, outputs(cached))
                val directory = checkNotNull(cached.pdf.parentFile)
                val marker = JSONObject(File(directory, PROVISIONAL_OUTPUT_CREATE_FILE_NAME).readText())
                assertTrue(marker.has("returnedUri") && marker.isNull("returnedUri"))
                val expected = fingerprint(cached.pdf)
                assertEquals(expected.byteLength, marker.getLong("byteLength"))
                assertEquals(expected.sha256, marker.getString("sha256"))
                val reopened = checkNotNull(ScanStorage(context).openSavedScan(cached.baseName))
                assertFalse(reopened.outputMetadataValid)
                val acknowledgement = checkNotNull(reopened.unknownOutputCreateAcknowledgement)
                assertEquals(
                    UnknownOutputAcknowledgementResult.Applied,
                    storage.acknowledgeUnknownOutputCreate(cached, acknowledgement),
                )
                assertTrue(checkNotNull(storage.openSavedScan(cached.baseName)).outputMetadataValid)
                assertEquals(8, fixtureCall(context, "fixture_stats").getInt("created"))
            }
        }

    private fun provisionalSafRecovery(pdf: Boolean) =
        withScan(1) { context, _, cached ->
            withTree(context, SecurityProviderMode.Stable) { tree ->
                val resolver = context.contentResolver
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, SECURITY_ROOT_ID)
                val source = if (pdf) cached.pdf else cached.pages.single()
                val original = source.readBytes()
                val expected = fingerprint(source)
                val changed = original.copyOf().apply { this[lastIndex] = (last().toInt() xor 1).toByte() }
                val cases = listOf(
                    null to original,
                    expected to changed,
                    expected to original.copyOf(original.size - 1),
                    expected to byteArrayOf(),
                    expected to original,
                )
                val mime = if (pdf) "application/pdf" else "image/jpeg"
                val extension = if (pdf) "pdf" else "jpg"
                val directory = checkNotNull(cached.pdf.parentFile)
                val before = outputs(cached)
                cases.forEachIndexed { index, (fingerprint, bytes) ->
                    val name = "provisional-$index.$extension"
                    val document = checkNotNull(DocumentsContract.createDocument(resolver, parent, mime, name))
                    checkNotNull(resolver.openOutputStream(document, "wt")).use { it.write(bytes) }
                    // Raw disk format also runs against the saved baseline APK's older constructor.
                    var marker = JSONObject()
                        .put("version", 1)
                        .put("operationId", java.util.UUID.randomUUID().toString())
                        .put("cacheId", cached.baseName)
                        .put("entryId", checkNotNull(cached.entryId))
                        .put("kind", if (pdf) "Pdf" else "Image")
                        .put("page", if (pdf) JSONObject.NULL else 1)
                        .put("provider", "Saf")
                        .put("displayName", name)
                        .put("mimeType", mime)
                        .put("treeUri", tree.toString())
                        .put("returnedUri", document.toString())
                        .also { value ->
                            fingerprint?.let {
                                value.put("byteLength", it.byteLength).put("sha256", it.sha256)
                            }
                        }
                        .toString()
                    val markerFile = File(directory, PROVISIONAL_OUTPUT_CREATE_FILE_NAME)
                    markerFile.writeText(marker)
                    val fresh = ScanStorage(context)
                    val reopened = checkNotNull(fresh.openSavedScan(cached.baseName))
                    val removed = index == cases.lastIndex
                    assertFalse("case $index must remain blocked until acknowledgement", reopened.outputMetadataValid)
                    assertEquals(marker, markerFile.readText())
                    val acknowledgement = checkNotNull(reopened.unknownOutputCreateAcknowledgement)
                    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, SECURITY_ROOT_ID)
                    checkNotNull(resolver.query(children, arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null)).use {
                        assertEquals("case $index", removed, !it.moveToFirst())
                        if (!removed) {
                            assertEquals(DocumentsContract.getDocumentId(document), it.getString(0))
                            assertFalse(it.moveToNext())
                        }
                    }
                    if (!removed) {
                        checkNotNull(resolver.openInputStream(document)).use {
                            org.junit.Assert.assertArrayEquals(bytes, it.readBytes())
                        }
                        assertEquals(
                            UnknownOutputAcknowledgementResult.Applied,
                            fresh.acknowledgeUnknownOutputCreate(cached, acknowledgement),
                        )
                        assertFalse(markerFile.exists())
                        assertTrue(checkNotNull(fresh.openSavedScan(cached.baseName)).outputMetadataValid)
                        // Acknowledgement must leave even partial or unverified provider bytes untouched.
                        checkNotNull(resolver.openInputStream(document)).use {
                            org.junit.Assert.assertArrayEquals(bytes, it.readBytes())
                        }
                        marker = JSONObject(marker)
                            .put("operationId", java.util.UUID.randomUUID().toString()).toString()
                        markerFile.writeText(marker)
                        assertEquals(
                            UnknownOutputAcknowledgementResult.Stale,
                            fresh.acknowledgeUnknownOutputCreate(cached, acknowledgement),
                        )
                        assertEquals(marker, markerFile.readText())
                        assertTrue(DocumentsContract.deleteDocument(resolver, document))
                    }
                    // Query the live parent: Android rejects the deleted child's tree URI.
                    checkNotNull(resolver.query(children, arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null)).use {
                        assertFalse(it.moveToFirst())
                    }
                    val afterRemoval = checkNotNull(fresh.openSavedScan(cached.baseName))
                    assertFalse(afterRemoval.outputMetadataValid)
                    assertEquals(marker, markerFile.readText())
                    val currentAcknowledgement = checkNotNull(afterRemoval.unknownOutputCreateAcknowledgement)
                    assertEquals(
                        UnknownOutputAcknowledgementResult.Applied,
                        fresh.acknowledgeUnknownOutputCreate(cached, currentAcknowledgement),
                    )
                    assertFalse(markerFile.exists())
                    assertTrue(checkNotNull(fresh.openSavedScan(cached.baseName)).outputMetadataValid)
                    assertEquals(
                        UnknownOutputAcknowledgementResult.Absent,
                        fresh.acknowledgeUnknownOutputCreate(cached, currentAcknowledgement),
                    )
                    assertEquals(before, outputs(cached))
                }
            }
        }

    @Test fun pdfRejectsChangedId() = invalidReplacement(SecurityProviderMode.WrongIdAfterBytes, true)
    @Test fun imageRejectsChangedId() = invalidReplacement(SecurityProviderMode.WrongIdAfterBytes, false)
    @Test fun pdfRejectsChangedMime() = invalidReplacement(SecurityProviderMode.WrongMimeAfterBytes, true)
    @Test fun imageRejectsChangedMime() = invalidReplacement(SecurityProviderMode.WrongMimeAfterBytes, false)
    @Test fun pdfRejectsLostDeleteCapability() = invalidReplacement(SecurityProviderMode.NoDeleteAfterBytes, true)
    @Test fun imageRejectsLostDeleteCapability() = invalidReplacement(SecurityProviderMode.NoDeleteAfterBytes, false)
    @Test fun pdfRejectsUnsafeFinalName() = invalidReplacement(SecurityProviderMode.UnsafeNameAfterBytes, true)
    @Test fun imageRejectsUnsafeFinalName() = invalidReplacement(SecurityProviderMode.UnsafeNameAfterBytes, false)
    @Test fun pdfRejectsCurrentNonChild() = invalidReplacement(SecurityProviderMode.NotChildAfterBytes, true)
    @Test fun imageRejectsCurrentNonChild() = invalidReplacement(SecurityProviderMode.NotChildAfterBytes, false)

    @Test fun imagesRejectFirstIdentityPoisonedBySecondCreate() =
        poisonedImages(SecurityProviderMode.PoisonIdentityOnSecondCreate)
    @Test fun imagesRejectFirstBytesPoisonedBySecondCreate() =
        poisonedImages(SecurityProviderMode.PoisonBytesOnSecondCreate)
    @Test fun imagesRejectFirstIdentityPoisonedBySecondRead() =
        poisonedImages(SecurityProviderMode.PoisonIdentityOnSecondRead)
    @Test fun imagesRejectFirstBytesPoisonedBySecondRead() =
        poisonedImages(SecurityProviderMode.PoisonBytesOnSecondRead)

    @Test fun pdfAcceptsUnicodeCollision() = validReplacement(true, false)
    @Test fun imageAcceptsUnicodeCollision() = validReplacement(false, false)
    @Test fun pdfRecordsSafeRenameAfterClose() = validReplacement(true, true)
    @Test fun imageRecordsSafeRenameAfterClose() = validReplacement(false, true)

    @Test fun smartCleanupSelectedPagePreservesUnsavedParent() = preservesParent(false, 1)
    @Test fun smartCleanupAllPagesPreservesUnsavedParent() = preservesParent(false, null)
    @Test fun manualLassoPreservesUnsavedParent() = preservesParent(true, 1)

    private fun invalidReplacement(mode: SecurityProviderMode, pdf: Boolean) =
        withScan(1) { context, storage, cached ->
            saveMedia(storage, cached)
            val before = outputs(cached)
            val source = if (pdf) cached.pdf else cached.pages.single()
            val fingerprint = fingerprint(source)
            withTree(context, mode) { tree ->
                val failure = rejection {
                    if (pdf) storage.replacePdfOutput(cached, tree.toString())
                    else storage.relocateImageOutputs(cached, tree.toString())
                }
                val stats = fixtureCall(context, "fixture_stats")
                assertFalse("Fixture close callback timed out", stats.getBoolean("closeTimedOut"))
                assertNull("Fixture writer close failed", stats.getString("closeError0"))
                assertEquals("Fixture must reach exactly one real create", 1, stats.getInt("created"))
                assertEquals("Written bytes must still match source", fingerprint.sha256, stats.getString("sha0"))
                assertEquals(fingerprint.byteLength, stats.getLong("length0"))
                // Android may reject a non-child during fingerprint open. That is a valid control.
                assertNotNull("$mode must reject the replacement after bytes were written", failure)
                assertRetained(context, cached, before)
                assertEquals("Source must remain unchanged", fingerprint, fingerprint(source))
            }
        }

    private fun poisonedImages(mode: SecurityProviderMode) =
        withScan(2) { context, storage, cached ->
            saveMedia(storage, cached)
            val before = outputs(cached)
            val sourceFingerprints = cached.pages.map(::fingerprint)
            withTree(context, mode) { tree ->
                val failure = rejection { storage.relocateImageOutputs(cached, tree.toString()) }
                val stats = fixtureCall(context, "fixture_stats")
                assertFalse("Fixture close callback timed out", stats.getBoolean("closeTimedOut"))
                assertEquals("Both pages must reach the real provider", 2, stats.getInt("created"))
                assertTrue("Second create/read must poison the first page", stats.getBoolean("poisoned"))
                sourceFingerprints.forEachIndexed { index, expected ->
                    assertNull("Fixture writer close failed", stats.getString("closeError$index"))
                    assertEquals(expected.sha256, stats.getString("sha$index"))
                    assertEquals(expected.byteLength, stats.getLong("length$index"))
                }
                assertNotNull("$mode must reject the whole image set", failure)
                assertRetained(context, cached, before)
                assertEquals(sourceFingerprints, cached.pages.map(::fingerprint))
            }
        }

    private fun validReplacement(pdf: Boolean, renameAfterClose: Boolean) =
        withScan(1) { context, storage, cached ->
            saveMedia(storage, cached)
            if (pdf) storage.renamePdfOutput(cached, "Žluťoučký kůň")
            else storage.renameImageOutputs(cached, "Žluťoučký kůň")
            rememberMedia(cached)
            val before = outputs(cached)
            val source = if (pdf) cached.pdf else cached.pages.single()
            val expected = fingerprint(source)
            val mime = if (pdf) "application/pdf" else "image/jpeg"
            val requested = checkNotNull(if (pdf) before.pdf?.displayName else before.images.single().displayName)
            val mode = if (renameAfterClose) SecurityProviderMode.SafeRenameAfterClose
                else SecurityProviderMode.Stable
            withTree(context, mode) { tree ->
                if (!renameAfterClose) {
                    val parent = DocumentsContract.buildDocumentUriUsingTree(tree, SECURITY_ROOT_ID)
                    val seed = checkNotNull(DocumentsContract.createDocument(
                        context.contentResolver, parent, mime, requested,
                    ))
                    checkNotNull(context.contentResolver.openOutputStream(seed, "w")).use {
                        source.inputStream().use { input -> input.copyTo(it) }
                    }
                }
                val result = if (pdf) storage.replacePdfOutput(cached, tree.toString())
                    else storage.relocateImageOutputs(cached, tree.toString())
                val metadata = outputs(cached)
                val uri = if (pdf) Uri.parse(checkNotNull(metadata.pdf).uri)
                    else Uri.parse(metadata.images.single().uri)
                val name = checkNotNull(if (pdf) metadata.pdf?.displayName
                    else metadata.images.single().displayName)
                val extension = requested.substringAfterLast('.')
                val stem = requested.substringBeforeLast('.')
                val expectedName = if (renameAfterClose) "${stem}_uzavřeno.$extension"
                    else "$stem (1).$extension"
                assertEquals(expectedName, name)
                val row = checkNotNull(context.contentResolver.query(
                    uri,
                    arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
                        Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS),
                    null, null, null,
                ))
                row.use {
                    assertTrue(it.moveToFirst())
                    assertEquals(DocumentsContract.getDocumentId(uri), it.getString(0))
                    assertEquals(name, it.getString(1))
                    assertEquals(mime, it.getString(2))
                    assertTrue(it.getInt(3) and Document.FLAG_SUPPORTS_DELETE != 0)
                    assertFalse(it.moveToNext())
                }
                val savedFingerprint = if (pdf) checkNotNull(metadata.pdf).outputFingerprint()
                    else metadata.images.single().outputFingerprint()
                assertEquals(expected, savedFingerprint)
                assertEquals(mime, if (pdf) metadata.pdf?.mimeType else metadata.images.single().mimeType)
                assertEquals(expected, fingerprint(context, uri, expected.byteLength))
                assertEquals(tree.toString(), if (pdf) metadata.pdf?.treeUri
                    else metadata.images.single().treeUri)
                assertEquals(cached.entryId, result.scan.cached.entryId)
                val fresh = checkNotNull(ScanStorage(context).openSavedScan(cached.baseName))
                assertEquals(name, if (pdf) fresh.savedPdfDisplayName else fresh.savedImages.single().displayName)
                assertEquals(uri, if (pdf) fresh.savedPdf else fresh.galleryPages.single())
                assertEquals(expected, fingerprint(source))
            }
        }

    private fun preservesParent(manual: Boolean, selectedPage: Int?) =
        withScan(2) { context, storage, parent ->
            val before = outputs(parent)
            assertNull("Parent must be unsaved", before.pdf)
            assertTrue(before.images.isEmpty())
            val originals = (parent.sourcePages + parent.pages + parent.pdf)
                .associateWith(::fingerprint)
            val candidate = if (manual) {
                storage.createManualCleanupVariant(
                    parent,
                    checkNotNull(selectedPage),
                    listOf(MarkStroke(listOf(
                        MarkPoint(0.25f, 0.25f), MarkPoint(0.75f, 0.25f),
                        MarkPoint(0.75f, 0.75f), MarkPoint(0.25f, 0.75f),
                    ))),
                ).cached
            } else {
                // These are the settings and arguments used by shipped Smart Cleanup.
                storage.createAppearanceVariant(
                    source = parent,
                    appearanceSettings = cleanWhiteboardAppearanceSettings(),
                    pdfSizeTarget = parent.pdfSizeTarget,
                    selectedPageIndex = selectedPage,
                    restoreSettingsOnActivation = false,
                ).cached
            }
            assertTrue(storage.isProvisionalCacheEntry(candidate))
            assertEquals(parent.baseName, candidate.parentCacheId)
            assertEquals(parent.entryId, candidate.parentEntryId)
            val activated = storage.activateCheckpointProvisional(candidate)
            assertEquals(candidate.entryId, activated.entryId)
            assertFalse(storage.isProvisionalCacheEntry(activated))
            val immediate = storage.openCachedScan(parent.baseName)
            val freshStorage = ScanStorage(context)
            freshStorage.reconcileProvisionalCacheEntries(activated)
            val recovered = freshStorage.openCachedScan(parent.baseName)
            assertNotNull("Cleanup checkpoint must preserve unsaved parent immediately", immediate)
            assertNotNull("Fresh storage recovery must preserve unsaved parent", recovered)
            listOf(checkNotNull(immediate), checkNotNull(recovered)).forEach {
                assertEquals(parent.entryId, it.entryId)
                assertEquals(parent.sourcePages, it.sourcePages)
                assertEquals(parent.pages, it.pages)
                assertEquals(parent.pdf, it.pdf)
            }
            originals.forEach { (file, expected) ->
                assertTrue("Original file must survive: ${file.name}", file.isFile)
                assertEquals(expected, fingerprint(file))
            }
            assertEquals(before, outputs(checkNotNull(recovered)))
            assertEquals(activated.entryId,
                checkNotNull(freshStorage.openCachedScan(activated.baseName)).entryId)
        }

    private fun assertRetained(context: Context, cached: CachedScan, before: OutputMetadata) {
        val current = outputs(cached)
        assertEquals("Old PDF reference must remain active", before.pdf, current.pdf)
        assertEquals("Old image references must remain active", before.images, current.images)
        val deleter = ExactOutputDeleter(context)
        before.pdf?.let {
            assertEquals(ExactItemQuery.Exact, deleter.queryPdf(it))
            assertEquals(it.outputFingerprint(), fingerprint(context, Uri.parse(it.uri), checkNotNull(it.byteLength)))
        }
        before.images.forEach {
            assertEquals(ExactItemQuery.Exact, deleter.queryImage(cached, it))
            assertEquals(it.outputFingerprint(), fingerprint(context, Uri.parse(it.uri), checkNotNull(it.byteLength)))
        }
    }

    private fun rejection(operation: () -> Unit): IOException? =
        try {
            operation()
            null
        } catch (failure: IOException) {
            failure
        }

    private fun saveMedia(storage: ScanStorage, cached: CachedScan) {
        storage.savePdf(cached, "SeliaScanSecurityTest", null)
        rememberMedia(cached)
        storage.saveImages(cached, "SeliaScanSecurityTest")
        rememberMedia(cached)
    }

    private fun outputs(cached: CachedScan): OutputMetadata =
        checkNotNull(readOutputMetadata(
            checkNotNull(cached.pdf.parentFile), cached.baseName, cached.pages.size,
        ))

    private fun rememberMedia(cached: CachedScan) {
        val metadata = outputs(cached)
        metadata.pdf?.takeIf { it.treeUri == null }?.let { ownedPdfs[it.uri] = it }
        metadata.images.filter { it.treeUri == null }.forEach { ownedImages[it.uri] = it }
    }

    private fun fingerprint(file: File): OutputFingerprint =
        file.inputStream().use { readOutputFingerprint(it, file.length()) }

    private fun fingerprint(context: Context, uri: Uri, length: Long): OutputFingerprint =
        checkNotNull(context.contentResolver.openInputStream(uri)).use {
            readOutputFingerprint(it, length)
        }

    private fun fixtureCall(context: Context, method: String, mode: SecurityProviderMode? = null): Bundle =
        checkNotNull(context.contentResolver.call(securityTreeUri(), method, mode?.name, null))

    private fun withTree(context: Context, mode: SecurityProviderMode, test: (Uri) -> Unit) {
        var failure: Throwable? = null
        try {
            val testPackage = InstrumentationRegistry.getInstrumentation().context.packageName
            context.startActivity(Intent().apply {
                component = ComponentName(testPackage, SecurityGrantActivity::class.java.name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            val deadline = SystemClock.elapsedRealtime() + 5_000
            var grantFailure: SecurityException? = null
            var granted = false
            while (SystemClock.elapsedRealtime() < deadline && !granted) {
                try {
                    context.contentResolver.takePersistableUriPermission(securityTreeUri(), SECURITY_RW)
                    granted = context.contentResolver.persistedUriPermissions.any {
                        it.uri == securityTreeUri() && it.isReadPermission && it.isWritePermission
                    }
                } catch (exception: SecurityException) {
                    grantFailure = exception
                }
                if (!granted) SystemClock.sleep(25)
            }
            if (!granted) throw AssertionError("Test APK tree grant unavailable after 5s", grantFailure)
            fixtureCall(context, "fixture_reset", mode)
            test(securityTreeUri())
        } catch (exception: Throwable) {
            failure = exception
            throw exception
        } finally {
            try {
                try {
                    fixtureCall(context, "fixture_finish")
                } finally {
                    if (context.contentResolver.persistedUriPermissions.any { it.uri == securityTreeUri() }) {
                        context.contentResolver.releasePersistableUriPermission(securityTreeUri(), SECURITY_RW)
                    }
                    assertFalse(context.contentResolver.persistedUriPermissions.any { it.uri == securityTreeUri() })
                }
            } catch (cleanup: Throwable) {
                if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
            }
        }
    }

    private fun withScan(pageCount: Int, test: (Context, ScanStorage, CachedScan) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        check(base.packageName == SECURITY_TARGET_PACKAGE) {
            "Storage security tests may only target com.majkeylab.scanit.internal"
        }
        val root = Files.createTempDirectory(base.cacheDir.toPath(), "storage-security-test-").toFile()
        val context = object : ContextWrapper(base) {
            override fun getCacheDir(): File = root
        }
        val storage = ScanStorage(context)
        var cached: CachedScan? = null
        var failure: Throwable? = null
        try {
            val sources = List(pageCount) { index ->
                File(root, "fixture-$index.jpg").also { file ->
                    val bitmap = Bitmap.createBitmap(96, 128, Bitmap.Config.ARGB_8888)
                    try {
                        val canvas = Canvas(bitmap)
                        canvas.drawColor(Color.WHITE)
                        val paint = Paint().apply { color = Color.BLACK }
                        canvas.drawRect(28f, 36f, 60f + index, 80f, paint)
                        FileOutputStream(file).use {
                            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it))
                            it.fd.sync()
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
            cached = storage.activateProvisionalCacheEntry(storage.cacheScan(
                sources.map(Uri::fromFile),
                googleScannerAppearanceSettings(),
                PdfSizeTarget.Original,
            ).cached)
            test(context, storage, cached)
        } catch (exception: Throwable) {
            failure = exception
            throw exception
        } finally {
            val cleanupFailures = mutableListOf<Throwable>()
            fun cleanup(action: () -> Unit) {
                runCatching(action).exceptionOrNull()?.let(cleanupFailures::add)
            }
            cached?.let { scan ->
                // Exact references captured before replacements also cover baseline failures.
                val deleter = ExactOutputDeleter(context)
                ownedPdfs.values.forEach { reference ->
                    cleanup {
                        assertTrue("Exact test PDF cleanup failed",
                            deleter.deletePdf(reference) in setOf(OutputDeleteStatus.Deleted, OutputDeleteStatus.Absent))
                        assertEquals(ExactItemQuery.Absent, deleter.queryPdf(reference))
                    }
                }
                ownedImages.values.forEach { reference ->
                    cleanup {
                        assertTrue("Exact test image cleanup failed",
                            deleter.deleteImage(scan, reference) in setOf(OutputDeleteStatus.Deleted, OutputDeleteStatus.Absent))
                        assertEquals(ExactItemQuery.Absent, deleter.queryImage(scan, reference))
                    }
                }
            }
            cleanup {
                check(root.deleteRecursively()) { "Synthetic storage-test cache could not be removed" }
            }
            ownedPdfs.clear()
            ownedImages.clear()
            cleanupFailures.firstOrNull()?.let { first ->
                cleanupFailures.drop(1).forEach(first::addSuppressed)
                if (failure != null) failure.addSuppressed(first) else throw first
            }
        }
    }
}
