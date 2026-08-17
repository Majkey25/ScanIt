package com.majkeylab.scanit

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

internal const val MAX_SCANNER_V2_MANIFEST_BYTES = 64 * 1024
internal const val SCANNER_V2_MANIFEST_NAME = "session.json"
internal const val SCANNER_V2_MANIFEST_TEMP_NAME = ".session.json.tmp"
internal const val SCANNER_V2_SESSION_RETENTION_MILLIS = 24L * 60 * 60 * 1000
private const val SCANNER_V2_MANIFEST_VERSION = 8
private const val SCANNER_V2_EDIT_SOURCE_MANIFEST_VERSION = 7
private const val SCANNER_V2_RESULT_AUTHORITY_MANIFEST_VERSION = 6
private const val SCANNER_V2_RENDER_GENERATION_MANIFEST_VERSION = 5
private const val SCANNER_V2_APPEARANCE_MANIFEST_VERSION = 4
private const val SCANNER_V2_LEGACY_MANIFEST_VERSION = 3
private const val SCANNER_V2_SESSION_PREFIX = "session-"

internal data class ScannerV2PageRecord(
    val pageId: PageId,
    val sourceFingerprint: OutputFingerprint,
    val crop: PageQuad,
    val rotationQuarterTurns: Int,
    val appearance: ScannerV2Appearance,
    val renderedFingerprint: OutputFingerprint?,
    val renderFileId: String? = null,
) {
    init {
        require(isValidOutputFingerprint(sourceFingerprint.byteLength, sourceFingerprint.sha256)) {
            "Scanner source fingerprint is invalid"
        }
        require(rotationQuarterTurns in 0..3) { "Scanner rotation is invalid" }
        require(
            renderedFingerprint == null ||
                isValidOutputFingerprint(renderedFingerprint.byteLength, renderedFingerprint.sha256),
        ) { "Scanner render fingerprint is invalid" }
        require(renderFileId == null || isCanonicalUuid(renderFileId)) { "Scanner render id is invalid" }
        require(renderFileId == null || renderedFingerprint != null) { "Scanner render id has no fingerprint" }
    }
}

internal data class ScannerV2EditSource(
    val cacheId: String,
    val entryId: String,
) {
    init {
        require(isSafeActiveResultCacheId(cacheId)) { "Scanner edit source cache id is invalid" }
        require(isCanonicalUuid(entryId)) { "Scanner edit source entry id is invalid" }
    }
}

@ConsistentCopyVisibility
internal data class ScannerV2Manifest private constructor(
    val sessionId: String,
    val state: ScannerSessionState,
    val pages: List<ScannerV2PageRecord>,
    val retiredPages: List<ScannerV2PageRecord>,
    val pendingCaptureId: PageId?,
    val pendingCaptureUseFullFrame: Boolean,
    val resultCacheId: String?,
    val editSource: ScannerV2EditSource?,
    val updatedAtMillis: Long,
) {
    fun withUpdatedAt(updatedAtMillis: Long): ScannerV2Manifest = create(
        sessionId = sessionId,
        state = state,
        pages = pages,
        retiredPages = retiredPages,
        pendingCaptureId = pendingCaptureId,
        pendingCaptureUseFullFrame = pendingCaptureUseFullFrame,
        resultCacheId = resultCacheId,
        editSource = editSource,
        updatedAtMillis = updatedAtMillis,
    )

    fun withPendingCapture(
        pageId: PageId?,
        useFullFrame: Boolean = false,
        updatedAtMillis: Long = this.updatedAtMillis,
    ): ScannerV2Manifest = create(
        sessionId = sessionId,
        state = state,
        pages = pages,
        retiredPages = retiredPages,
        pendingCaptureId = pageId,
        pendingCaptureUseFullFrame = useFullFrame,
        resultCacheId = resultCacheId,
        editSource = editSource,
        updatedAtMillis = updatedAtMillis,
    )

    fun withResultCacheId(
        cacheId: String?,
        updatedAtMillis: Long,
    ): ScannerV2Manifest = create(
        sessionId = sessionId,
        state = state,
        pages = pages,
        retiredPages = retiredPages,
        pendingCaptureId = pendingCaptureId,
        pendingCaptureUseFullFrame = pendingCaptureUseFullFrame,
        resultCacheId = cacheId,
        editSource = editSource,
        updatedAtMillis = updatedAtMillis,
    )

    companion object {
        fun create(
            sessionId: String,
            state: ScannerSessionState,
            pages: List<ScannerV2PageRecord>,
            retiredPages: List<ScannerV2PageRecord> = emptyList(),
            pendingCaptureId: PageId? = null,
            pendingCaptureUseFullFrame: Boolean = false,
            resultCacheId: String? = null,
            editSource: ScannerV2EditSource? = null,
            updatedAtMillis: Long,
        ): ScannerV2Manifest {
            require(isCanonicalUuid(sessionId)) { "Scanner session id is invalid" }
            require(updatedAtMillis > 0) { "Scanner session timestamp is invalid" }
            require(pages.size <= MAX_SCAN_PAGES) { "Scanner page limit exceeded" }
            require(retiredPages.size <= MAX_SCAN_PAGES) { "Scanner retired page limit exceeded" }
            require(pages.map { it.pageId } == state.pages.map { it.id }) {
                "Scanner page records do not match session order"
            }
            require((pages + retiredPages).map { it.pageId }.toSet().size == pages.size + retiredPages.size) {
                "Scanner active and retired pages overlap"
            }
            require(pendingCaptureId == null || state.stage == ScannerSessionStage.Capturing) {
                "Pending capture requires the camera stage"
            }
            require(pendingCaptureId == null || pages.none { it.pageId == pendingCaptureId }) {
                "Pending capture duplicates a saved page"
            }
            require(pendingCaptureId != null || !pendingCaptureUseFullFrame) {
                "Full-frame capture preference requires a pending capture"
            }
            require(resultCacheId == null || state.stage == ScannerSessionStage.Finishing) {
                "Result cache authority requires the finishing stage"
            }
            require(resultCacheId == null || isSafeActiveResultCacheId(resultCacheId)) {
                "Result cache authority is invalid"
            }
            require(resultCacheId == null || resultCacheId != editSource?.cacheId) {
                "Scanner result cannot replace itself"
            }
            return ScannerV2Manifest(
                sessionId = sessionId,
                state = state,
                pages = pages.toList(),
                retiredPages = retiredPages.toList(),
                pendingCaptureId = pendingCaptureId,
                pendingCaptureUseFullFrame = pendingCaptureUseFullFrame,
                resultCacheId = resultCacheId,
                editSource = editSource,
                updatedAtMillis = updatedAtMillis,
            )
        }
    }
}

internal fun encodeScannerV2Manifest(manifest: ScannerV2Manifest): ByteArray {
    val state = manifest.state
    val pages = JSONArray()
    manifest.pages.forEach { record -> pages.put(record.toJson()) }
    val retiredPages = JSONArray()
    manifest.retiredPages.forEach { record -> retiredPages.put(record.toJson()) }
    return JSONObject()
        .put("version", SCANNER_V2_MANIFEST_VERSION)
        .put("sessionId", manifest.sessionId)
        .put("generation", state.generation)
        .put("stage", state.stage.name)
        .put("selectedIndex", state.selectedIndex ?: JSONObject.NULL)
        .put("pendingReplacementIndex", state.pendingReplacementIndex ?: JSONObject.NULL)
        .put("pendingCaptureId", manifest.pendingCaptureId?.value ?: JSONObject.NULL)
        .put("pendingCaptureUseFullFrame", manifest.pendingCaptureUseFullFrame)
        .put("resultCacheId", manifest.resultCacheId ?: JSONObject.NULL)
        .put("editSourceCacheId", manifest.editSource?.cacheId ?: JSONObject.NULL)
        .put("editSourceEntryId", manifest.editSource?.entryId ?: JSONObject.NULL)
        .put("updatedAtMillis", manifest.updatedAtMillis)
        .put("pages", pages)
        .put("retiredPages", retiredPages)
        .toString()
        .toByteArray(StandardCharsets.UTF_8)
        .also { require(it.size in 1..MAX_SCANNER_V2_MANIFEST_BYTES) { "Scanner manifest is too large" } }
}

internal fun decodeScannerV2Manifest(bytes: ByteArray): ScannerV2Manifest? {
    if (bytes.size !in 1..MAX_SCANNER_V2_MANIFEST_BYTES) return null
    return try {
        val value = JSONObject(String(bytes, StandardCharsets.UTF_8))
        val version = value.strictInt("version") ?: return null
        if (
            !value.hasOnlyKeys(
                when (version) {
                    SCANNER_V2_MANIFEST_VERSION -> MANIFEST_KEYS_V8
                    SCANNER_V2_EDIT_SOURCE_MANIFEST_VERSION -> MANIFEST_KEYS_V7
                    SCANNER_V2_RESULT_AUTHORITY_MANIFEST_VERSION -> MANIFEST_KEYS_V6
                    else -> MANIFEST_KEYS_V3_TO_V5
                },
            ) ||
                version !in setOf(
                    SCANNER_V2_LEGACY_MANIFEST_VERSION,
                    SCANNER_V2_APPEARANCE_MANIFEST_VERSION,
                    SCANNER_V2_RENDER_GENERATION_MANIFEST_VERSION,
                    SCANNER_V2_RESULT_AUTHORITY_MANIFEST_VERSION,
                    SCANNER_V2_EDIT_SOURCE_MANIFEST_VERSION,
                    SCANNER_V2_MANIFEST_VERSION,
                )
        ) {
            return null
        }
        val sessionId = value.strictString("sessionId") ?: return null
        val generation = value.strictLong("generation") ?: return null
        val stage = value.strictEnum<ScannerSessionStage>("stage") ?: return null
        val selectedIndex = value.strictOptionalInt("selectedIndex") ?: return null
        val pendingReplacement = value.strictOptionalInt("pendingReplacementIndex") ?: return null
        val pendingCaptureRaw = value.strictOptionalString("pendingCaptureId") ?: return null
        val pendingCaptureId = pendingCaptureRaw.value?.let { PageId.parse(it) }
        val pendingCaptureUseFullFrame = if (version >= SCANNER_V2_MANIFEST_VERSION) {
            value.strictBoolean("pendingCaptureUseFullFrame") ?: return null
        } else {
            false
        }
        val resultCacheId = if (version >= SCANNER_V2_RESULT_AUTHORITY_MANIFEST_VERSION) {
            (value.strictOptionalString("resultCacheId") ?: return null).value
        } else {
            null
        }
        val editSource = if (version >= SCANNER_V2_EDIT_SOURCE_MANIFEST_VERSION) {
            val cacheId = (value.strictOptionalString("editSourceCacheId") ?: return null).value
            val entryId = (value.strictOptionalString("editSourceEntryId") ?: return null).value
            if ((cacheId == null) != (entryId == null)) return null
            cacheId?.let { ScannerV2EditSource(it, checkNotNull(entryId)) }
        } else {
            null
        }
        val updatedAt = value.strictLong("updatedAtMillis") ?: return null
        val array = value.opt("pages") as? JSONArray ?: return null
        if (array.length() > MAX_SCAN_PAGES) return null
        val pages = buildList(array.length()) {
            repeat(array.length()) { index ->
                add(decodePageRecord(array.opt(index) as? JSONObject ?: return null, version) ?: return null)
            }
        }
        val retiredArray = value.opt("retiredPages") as? JSONArray ?: return null
        if (retiredArray.length() > MAX_SCAN_PAGES) return null
        val retiredPages = buildList(retiredArray.length()) {
            repeat(retiredArray.length()) { index ->
                add(decodePageRecord(retiredArray.opt(index) as? JSONObject ?: return null, version) ?: return null)
            }
        }
        val state = ScannerSessionState.restore(
            generation = generation,
            pages = pages.map { ScannerPage(it.pageId) },
            selectedIndex = selectedIndex.value,
            stage = stage,
            pendingReplacementIndex = pendingReplacement.value,
        )
        ScannerV2Manifest.create(
            sessionId = sessionId,
            state = state,
            pages = pages,
            retiredPages = retiredPages,
            pendingCaptureId = pendingCaptureId,
            pendingCaptureUseFullFrame = pendingCaptureUseFullFrame,
            resultCacheId = resultCacheId,
            editSource = editSource,
            updatedAtMillis = updatedAt,
        )
    } catch (_: Exception) {
        null
    }
}

internal class ScannerV2Store(
    root: File,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val root = root.absoluteFile.let { requested ->
        if (!requested.exists() && !requested.mkdirs()) {
            throw IOException("Scanner session root could not be created")
        }
        if (!requested.isDirectory || Files.isSymbolicLink(requested.toPath())) {
            throw IOException("Scanner session root is invalid")
        }
        requested.canonicalFile
    }

    fun create(manifest: ScannerV2Manifest): File {
        require(manifest.pages.isEmpty()) { "A new scanner session must be empty" }
        val existing = root.listFiles().orEmpty().filter { it.name.startsWith(SCANNER_V2_SESSION_PREFIX) }
        if (existing.isNotEmpty()) throw IOException("Another scanner session already exists")
        val directory = sessionDirectory(manifest.sessionId)
        if (!directory.mkdir()) throw IOException("Scanner session directory could not be created")
        return try {
            writeManifest(directory, manifest, expected = null, move = ::moveAtomically)
            directory
        } catch (failure: Throwable) {
            if (directory.listFiles().orEmpty().isEmpty()) directory.delete()
            throw failure
        }
    }

    fun update(
        expected: ScannerV2Manifest,
        replacement: ScannerV2Manifest,
        move: (Path, Path) -> Unit = ::moveAtomically,
    ) {
        require(expected.sessionId == replacement.sessionId) { "Scanner session identity changed" }
        require(replacement.updatedAtMillis >= expected.updatedAtMillis) { "Scanner timestamp moved backwards" }
        val directory = requireSessionDirectory(expected.sessionId)
        val current = readManifest(directory)
        if (current != expected) throw IOException("Scanner session changed before update")
        verifyPageFiles(directory, replacement.pages)
        verifyRetiredPageFiles(directory, replacement.retiredPages)
        writeManifest(directory, replacement, expected, move)
    }

    fun updateSelection(expected: ScannerV2Manifest, replacement: ScannerV2Manifest) {
        require(expected.sessionId == replacement.sessionId) { "Scanner session identity changed" }
        require(expected.pages == replacement.pages) { "Scanner page authority changed during selection" }
        require(expected.retiredPages == replacement.retiredPages) { "Scanner cleanup authority changed during selection" }
        require(expected.pendingCaptureId == replacement.pendingCaptureId) { "Scanner capture changed during selection" }
        require(expected.pendingCaptureUseFullFrame == replacement.pendingCaptureUseFullFrame) {
            "Scanner capture preference changed during selection"
        }
        require(expected.resultCacheId == replacement.resultCacheId) { "Scanner result authority changed during selection" }
        require(expected.state.generation == replacement.state.generation) { "Scanner generation changed during selection" }
        require(expected.state.pages == replacement.state.pages) { "Scanner page order changed during selection" }
        require(expected.state.stage == replacement.state.stage) { "Scanner stage changed during selection" }
        require(expected.state.pendingReplacementIndex == replacement.state.pendingReplacementIndex) {
            "Scanner replacement changed during selection"
        }
        require(replacement.updatedAtMillis >= expected.updatedAtMillis) { "Scanner timestamp moved backwards" }
        val directory = requireSessionDirectory(expected.sessionId)
        if (readManifest(directory) != expected) throw IOException("Scanner session changed before selection")
        writeManifest(directory, replacement, expected, ::moveAtomically)
    }

    fun loadActive(): ScannerV2Manifest? {
        val directories = root.listFiles().orEmpty().filter { it.name.startsWith(SCANNER_V2_SESSION_PREFIX) }
        if (directories.size > 1) throw IOException("Multiple scanner sessions require manual recovery")
        val directory = directories.singleOrNull() ?: return null
        val manifest = readManifest(requireSessionDirectory(directory.name.removePrefix(SCANNER_V2_SESSION_PREFIX)))
        reconcilePageInventory(directory, manifest)
        verifyPageFiles(directory, manifest.pages)
        verifyRetiredPageFiles(directory, manifest.retiredPages)
        return manifest
    }

    fun cleanupExpired(): Int {
        val currentTime = nowMillis()
        var removed = 0
        root.listFiles().orEmpty().forEach { candidate ->
            if (!candidate.name.startsWith(SCANNER_V2_SESSION_PREFIX)) return@forEach
            val sessionId = candidate.name.removePrefix(SCANNER_V2_SESSION_PREFIX)
            val directory = try {
                requireSessionDirectory(sessionId)
            } catch (_: IllegalArgumentException) {
                return@forEach
            } catch (_: IOException) {
                return@forEach
            }
            val manifest = try {
                readManifest(directory)
            } catch (_: IOException) {
                return@forEach
            }
            if (
                manifest.state.stage != ScannerSessionStage.Cancelled ||
                currentTime < manifest.updatedAtMillis ||
                currentTime - manifest.updatedAtMillis <= SCANNER_V2_SESSION_RETENTION_MILLIS
            ) return@forEach
            if (deleteExactSession(directory, manifest)) removed += 1
        }
        return removed
    }

    fun deleteCancelled(manifest: ScannerV2Manifest): Boolean {
        require(manifest.state.stage == ScannerSessionStage.Cancelled) {
            "Only a cancelled scanner session can be deleted"
        }
        val directory = requireSessionDirectory(manifest.sessionId)
        if (readManifest(directory) != manifest) throw IOException("Scanner session changed before deletion")
        return deleteExactSession(directory, manifest)
    }

    fun deleteForFreshLaunch(manifest: ScannerV2Manifest): Boolean {
        val directory = requireSessionDirectory(manifest.sessionId)
        if (readManifest(directory) != manifest) {
            throw IOException("Scanner session changed before fresh launch")
        }
        reconcilePageInventory(directory, manifest)
        verifyPageFiles(directory, manifest.pages)
        verifyRetiredPageFiles(directory, manifest.retiredPages)
        return deleteExactSession(directory, manifest)
    }

    fun deletePendingCaptureFiles(manifest: ScannerV2Manifest): Boolean {
        val pageId = requireNotNull(manifest.pendingCaptureId) { "Scanner capture is not pending" }
        val directory = requireSessionDirectory(manifest.sessionId)
        if (readManifest(directory) != manifest) throw IOException("Scanner session changed before capture cleanup")
        val files = listOf(captureFile(manifest.sessionId, pageId), sourceFile(manifest.sessionId, pageId))
        if (files.any { it.exists() && (!it.isFile || it.canonicalFile != it) }) return false
        return files.all { !it.exists() || it.delete() }
    }

    fun reconcileRetiredPages(manifest: ScannerV2Manifest): ScannerV2Manifest {
        if (manifest.retiredPages.isEmpty()) return manifest
        val directory = requireSessionDirectory(manifest.sessionId)
        if (readManifest(directory) != manifest) throw IOException("Scanner session changed before page cleanup")
        verifyRetiredPageFiles(directory, manifest.retiredPages)
        manifest.retiredPages.forEach { page ->
            val files = buildList {
                add(sourceFile(manifest.sessionId, page.pageId))
                if (page.renderedFingerprint != null) add(renderedFile(manifest, page))
            }
            if (files.any { it.exists() && !it.delete() }) return manifest
        }
        val cleaned = ScannerV2Manifest.create(
            sessionId = manifest.sessionId,
            state = manifest.state,
            pages = manifest.pages,
            retiredPages = emptyList(),
            pendingCaptureId = manifest.pendingCaptureId,
            pendingCaptureUseFullFrame = manifest.pendingCaptureUseFullFrame,
            resultCacheId = manifest.resultCacheId,
            editSource = manifest.editSource,
            updatedAtMillis = manifest.updatedAtMillis,
        )
        update(manifest, cleaned)
        return cleaned
    }

    fun sessionDirectory(sessionId: String): File {
        require(isCanonicalUuid(sessionId)) { "Scanner session id is invalid" }
        val directory = File(root, "$SCANNER_V2_SESSION_PREFIX$sessionId").absoluteFile
        require(directory.parentFile == root && directory.canonicalFile == directory) {
            "Scanner session path escaped its root"
        }
        return directory
    }

    fun manifestFile(sessionId: String): File = File(sessionDirectory(sessionId), SCANNER_V2_MANIFEST_NAME)

    fun sourceFile(sessionId: String, pageId: PageId): File = pageFile(sessionId, pageId, "source", "jpg")

    fun renderedFile(sessionId: String, pageId: PageId): File = pageFile(sessionId, pageId, "render", "jpg")

    fun renderedFile(manifest: ScannerV2Manifest, page: ScannerV2PageRecord): File {
        require(page in manifest.pages || page in manifest.retiredPages) { "Scanner render page is not journaled" }
        return page.renderFileId?.let { renderCandidateFile(manifest.sessionId, page.pageId, it) }
            ?: renderedFile(manifest.sessionId, page.pageId)
    }

    fun renderCandidateFile(sessionId: String, pageId: PageId, renderFileId: String): File {
        require(isCanonicalUuid(renderFileId)) { "Scanner render id is invalid" }
        val directory = requireSessionDirectory(sessionId)
        val file = File(directory, "render-${pageId.value}-$renderFileId.jpg").absoluteFile
        require(file.parentFile == directory && file.canonicalFile == file) { "Scanner render path escaped its session" }
        return file
    }

    fun previewFile(manifest: ScannerV2Manifest, page: ScannerV2PageRecord): File {
        require(page in manifest.pages) { "Scanner preview page is not active" }
        return if (page.renderedFingerprint == null) {
            sourceFile(manifest.sessionId, page.pageId)
        } else {
            renderedFile(manifest, page)
        }
    }

    fun captureFile(sessionId: String, pageId: PageId): File = pageFile(sessionId, pageId, ".capture", "jpg")

    private fun pageFile(sessionId: String, pageId: PageId, prefix: String, extension: String): File {
        val directory = requireSessionDirectory(sessionId)
        val file = File(directory, "$prefix-${pageId.value}.$extension").absoluteFile
        require(file.parentFile == directory && file.canonicalFile == file) { "Scanner page path escaped its session" }
        return file
    }

    private fun requireSessionDirectory(sessionId: String): File = sessionDirectory(sessionId).also { directory ->
        if (!directory.isDirectory || directory.parentFile != root || directory.canonicalFile != directory) {
            throw IOException("Scanner session directory is invalid")
        }
    }

    private fun readManifest(directory: File): ScannerV2Manifest {
        val target = File(directory, SCANNER_V2_MANIFEST_NAME)
        val temporary = File(directory, SCANNER_V2_MANIFEST_TEMP_NAME)
        if (temporary.exists()) throw IOException("Scanner manifest update is incomplete")
        if (!target.isFile || target.length() !in 1..MAX_SCANNER_V2_MANIFEST_BYTES.toLong()) {
            throw IOException("Scanner manifest is unavailable")
        }
        val decoded = try {
            decodeScannerV2Manifest(Files.readAllBytes(target.toPath()))
        } catch (failure: IOException) {
            throw IOException("Scanner manifest could not be read", failure)
        } ?: throw IOException("Scanner manifest is invalid")
        if (directory != sessionDirectory(decoded.sessionId)) {
            throw IOException("Scanner manifest belongs to another session")
        }
        return decoded
    }

    private fun writeManifest(
        directory: File,
        manifest: ScannerV2Manifest,
        expected: ScannerV2Manifest?,
        move: (Path, Path) -> Unit,
    ) {
        if (expected != null && readManifest(directory) != expected) {
            throw IOException("Scanner session changed before write")
        }
        val target = File(directory, SCANNER_V2_MANIFEST_NAME)
        val temporary = File(directory, SCANNER_V2_MANIFEST_TEMP_NAME)
        val bytes = encodeScannerV2Manifest(manifest)
        var failure: Throwable? = null
        try {
            Files.deleteIfExists(temporary.toPath())
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            if (decodeScannerV2Manifest(Files.readAllBytes(temporary.toPath())) != manifest) {
                throw IOException("Scanner manifest verification failed")
            }
            move(temporary.toPath(), target.toPath())
            if (readManifest(directory) != manifest) throw IOException("Scanner manifest publication failed")
        } catch (throwable: Throwable) {
            failure = throwable
            throw throwable
        } finally {
            if (temporary.exists() && !temporary.delete()) {
                val cleanup = IOException("Incomplete scanner manifest could not be deleted")
                failure?.addSuppressed(cleanup) ?: throw cleanup
            }
        }
    }

    private fun verifyPageFiles(directory: File, pages: List<ScannerV2PageRecord>) {
        pages.forEach { page ->
            requireFingerprint(File(directory, "source-${page.pageId.value}.jpg"), page.sourceFingerprint)
            page.renderedFingerprint?.let { fingerprint ->
                requireFingerprint(File(directory, page.renderFileName()), fingerprint)
            }
        }
    }

    private fun verifyRetiredPageFiles(directory: File, pages: List<ScannerV2PageRecord>) {
        pages.forEach { page ->
            File(directory, "source-${page.pageId.value}.jpg").takeIf(File::exists)?.let { source ->
                requireFingerprint(source, page.sourceFingerprint)
            }
            page.renderedFingerprint?.let { fingerprint ->
                File(directory, page.renderFileName()).takeIf(File::exists)?.let { rendered ->
                    requireFingerprint(rendered, fingerprint)
                }
            }
        }
    }

    private fun reconcilePageInventory(directory: File, manifest: ScannerV2Manifest) {
        val expectedNames = buildSet {
            add(SCANNER_V2_MANIFEST_NAME)
            manifest.pages.forEach { page ->
                add("source-${page.pageId.value}.jpg")
                if (page.renderedFingerprint != null) add(page.renderFileName())
            }
            manifest.retiredPages.forEach { page ->
                add("source-${page.pageId.value}.jpg")
                if (page.renderedFingerprint != null) add(page.renderFileName())
            }
            manifest.pendingCaptureId?.let { pageId ->
                add(".capture-${pageId.value}.jpg")
                add("source-${pageId.value}.jpg")
            }
        }
        val knownPageIds = (manifest.pages + manifest.retiredPages).mapTo(mutableSetOf()) { it.pageId }
        val initialChildren = directory.listFiles() ?: throw IOException("Scanner session inventory is unavailable")
        initialChildren.filter { child ->
            child.name !in expectedNames && parseScannerV2RenderPageId(child.name) in knownPageIds
        }.forEach { orphan ->
            if (!orphan.isFile || orphan.canonicalFile != orphan || !orphan.delete()) {
                throw IOException("Incomplete scanner render could not be removed")
            }
        }
        val children = directory.listFiles() ?: throw IOException("Scanner session inventory is unavailable")
        if (children.any { it.name !in expectedNames || !it.isFile || it.canonicalFile != it }) {
            throw IOException("Scanner session contains an unknown file")
        }
    }

    private fun requireFingerprint(file: File, expected: OutputFingerprint) {
        if (!file.isFile || file.canonicalFile != file || file.parentFile?.parentFile != root) {
            throw IOException("Scanner page file is unavailable")
        }
        val actual = try {
            file.inputStream().use { readOutputFingerprint(it, file.length()) }
        } catch (failure: IOException) {
            throw IOException("Scanner page fingerprint could not be read", failure)
        }
        if (actual != expected) throw IOException("Scanner page fingerprint changed")
    }

    private fun deleteExactSession(directory: File, manifest: ScannerV2Manifest): Boolean {
        val expectedNames = buildSet {
            add(SCANNER_V2_MANIFEST_NAME)
            manifest.pages.forEach { page ->
                add("source-${page.pageId.value}.jpg")
                if (page.renderedFingerprint != null) add(page.renderFileName())
            }
            manifest.retiredPages.forEach { page ->
                add("source-${page.pageId.value}.jpg")
                if (page.renderedFingerprint != null) add(page.renderFileName())
            }
            manifest.pendingCaptureId?.let { pageId ->
                add(".capture-${pageId.value}.jpg")
                add("source-${pageId.value}.jpg")
            }
        }
        val children = directory.listFiles() ?: return false
        if (children.any { it.name !in expectedNames || it.canonicalFile != it || !it.isFile }) return false
        val ordered = children.sortedBy { it.name == SCANNER_V2_MANIFEST_NAME }
        if (ordered.any { !it.delete() }) return false
        return directory.delete()
    }
}

private fun ScannerV2PageRecord.toJson(): JSONObject = JSONObject()
    .put("id", pageId.value)
    .put("sourceLength", sourceFingerprint.byteLength)
    .put("sourceSha256", sourceFingerprint.sha256)
    .put("crop", JSONArray(listOf(
        crop.topLeft.x,
        crop.topLeft.y,
        crop.topRight.x,
        crop.topRight.y,
        crop.bottomRight.x,
        crop.bottomRight.y,
        crop.bottomLeft.x,
        crop.bottomLeft.y,
    )))
    .put("rotationQuarterTurns", rotationQuarterTurns)
    .put("filterId", appearance.filter.wireValue)
    .put("filterIntensity", appearance.intensity)
    .put("filterShadows", appearance.shadows)
    .put("renderedLength", renderedFingerprint?.byteLength ?: JSONObject.NULL)
    .put("renderedSha256", renderedFingerprint?.sha256 ?: JSONObject.NULL)
    .put("renderFileId", renderFileId ?: JSONObject.NULL)

private fun decodePageRecord(value: JSONObject, version: Int): ScannerV2PageRecord? {
    val expectedKeys = when (version) {
        SCANNER_V2_LEGACY_MANIFEST_VERSION -> PAGE_KEYS_V3
        SCANNER_V2_APPEARANCE_MANIFEST_VERSION -> PAGE_KEYS_V4
        else -> PAGE_KEYS_V5
    }
    if (!value.hasOnlyKeys(expectedKeys)) return null
    val pageId = runCatching { PageId.parse(value.strictString("id") ?: return null) }.getOrNull() ?: return null
    val source = outputFingerprintOrNull(value.strictLong("sourceLength"), value.strictString("sourceSha256"))
        ?: return null
    val cropValues = value.opt("crop") as? JSONArray ?: return null
    if (cropValues.length() != 8) return null
    val crop = runCatching {
        PageQuad.create(
            NormalizedPoint(cropValues.strictDouble(0) ?: return null, cropValues.strictDouble(1) ?: return null),
            NormalizedPoint(cropValues.strictDouble(2) ?: return null, cropValues.strictDouble(3) ?: return null),
            NormalizedPoint(cropValues.strictDouble(4) ?: return null, cropValues.strictDouble(5) ?: return null),
            NormalizedPoint(cropValues.strictDouble(6) ?: return null, cropValues.strictDouble(7) ?: return null),
        )
    }.getOrNull() ?: return null
    val renderedLength = value.strictOptionalLong("renderedLength") ?: return null
    val renderedSha = value.strictOptionalString("renderedSha256") ?: return null
    if ((renderedLength.value == null) != (renderedSha.value == null)) return null
    val rendered = if (renderedLength.value == null) null else {
        outputFingerprintOrNull(renderedLength.value, renderedSha.value) ?: return null
    }
    val filter = ScannerV2Filter.parse(value.strictString("filterId") ?: return null) ?: return null
    val appearance = if (version == SCANNER_V2_LEGACY_MANIFEST_VERSION) {
        ScannerV2Appearance.defaultFor(filter)
    } else {
        runCatching {
            ScannerV2Appearance(
                filter = filter,
                intensity = value.strictInt("filterIntensity") ?: return null,
                shadows = value.strictInt("filterShadows") ?: return null,
            )
        }.getOrNull() ?: return null
    }
    val renderFileId = if (version >= SCANNER_V2_RENDER_GENERATION_MANIFEST_VERSION) {
        val optional = value.strictOptionalString("renderFileId") ?: return null
        optional.value
    } else {
        null
    }
    return runCatching {
        ScannerV2PageRecord(
            pageId = pageId,
            sourceFingerprint = source,
            crop = crop,
            rotationQuarterTurns = value.strictInt("rotationQuarterTurns") ?: return null,
            appearance = appearance,
            renderedFingerprint = rendered,
            renderFileId = renderFileId,
        )
    }.getOrNull()
}

private fun moveAtomically(source: Path, target: Path) {
    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}

private data class ScannerOptional<T>(val value: T?)

private fun JSONObject.hasOnlyKeys(expected: Set<String>): Boolean {
    val actual = mutableSetOf<String>()
    val iterator = keys()
    while (iterator.hasNext()) actual += iterator.next()
    return actual == expected
}

private fun JSONObject.strictString(key: String): String? = opt(key) as? String

private fun JSONObject.strictInt(key: String): Int? = opt(key) as? Int

private fun JSONObject.strictLong(key: String): Long? {
    val value = opt(key)
    return if (value is Int) value.toLong() else value as? Long
}

private fun JSONObject.strictBoolean(key: String): Boolean? = opt(key) as? Boolean

private inline fun <reified T : Enum<T>> JSONObject.strictEnum(key: String): T? =
    strictString(key)?.let { raw -> enumValues<T>().firstOrNull { it.name == raw } }

private fun JSONObject.strictOptionalInt(key: String): ScannerOptional<Int>? {
    val value = opt(key)
    if (value === JSONObject.NULL) return ScannerOptional(null)
    return (value as? Int)?.let(::ScannerOptional)
}

private fun JSONObject.strictOptionalLong(key: String): ScannerOptional<Long>? {
    val value = opt(key)
    if (value === JSONObject.NULL) return ScannerOptional(null)
    if (value is Int) return ScannerOptional(value.toLong())
    return (value as? Long)?.let(::ScannerOptional)
}

private fun JSONObject.strictOptionalString(key: String): ScannerOptional<String>? {
    val value = opt(key)
    if (value === JSONObject.NULL) return ScannerOptional(null)
    return (value as? String)?.let(::ScannerOptional)
}

private fun JSONArray.strictDouble(index: Int): Double? = (opt(index) as? Number)?.toDouble()?.takeIf { it.isFinite() }

private val MANIFEST_KEYS_V3_TO_V5 = setOf(
    "version",
    "sessionId",
    "generation",
    "stage",
    "selectedIndex",
    "pendingReplacementIndex",
    "pendingCaptureId",
    "updatedAtMillis",
    "pages",
    "retiredPages",
)

private val MANIFEST_KEYS_V6 = MANIFEST_KEYS_V3_TO_V5 + setOf("resultCacheId")

private val MANIFEST_KEYS_V7 = MANIFEST_KEYS_V6 + setOf("editSourceCacheId", "editSourceEntryId")

private val MANIFEST_KEYS_V8 = MANIFEST_KEYS_V7 + setOf("pendingCaptureUseFullFrame")

private val PAGE_KEYS_V3 = setOf(
    "id",
    "sourceLength",
    "sourceSha256",
    "crop",
    "rotationQuarterTurns",
    "filterId",
    "renderedLength",
    "renderedSha256",
)

private val PAGE_KEYS_V4 = PAGE_KEYS_V3 + setOf("filterIntensity", "filterShadows")

private val PAGE_KEYS_V5 = PAGE_KEYS_V4 + setOf("renderFileId")

private fun ScannerV2PageRecord.renderFileName(): String = renderFileId?.let { id ->
    "render-${pageId.value}-$id.jpg"
} ?: "render-${pageId.value}.jpg"

private fun parseScannerV2RenderPageId(name: String): PageId? {
    if (!name.startsWith("render-") || !name.endsWith(".jpg")) return null
    val body = name.removePrefix("render-").removeSuffix(".jpg")
    if (body.length == 36) return runCatching { PageId.parse(body) }.getOrNull()
    if (body.length != 73 || body[36] != '-') return null
    val pageId = runCatching { PageId.parse(body.substring(0, 36)) }.getOrNull() ?: return null
    return pageId.takeIf { isCanonicalUuid(body.substring(37)) }
}
