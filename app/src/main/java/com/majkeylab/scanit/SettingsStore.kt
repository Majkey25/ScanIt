package com.majkeylab.scanit

import android.content.Context
import android.content.SharedPreferences
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private const val MAX_ALBUM_NAME_LENGTH = 64
private const val PREFERENCES_NAME = "settings"
private const val KEY_SAVE_PDF = "save_pdf"
private const val KEY_SAVE_IMAGES = "save_images"
private const val KEY_ALBUM_NAME = "album_name_scanit"
private const val KEY_MULTIPAGE = "multipage"
private const val KEY_ALLOW_GALLERY = "allow_gallery"
private const val KEY_EMAIL_SUBJECT = "email_subject"
private const val KEY_EMAIL_BODY = "email_body"
private const val KEY_DELETE_PDF_AFTER_SHARE = "delete_pdf_after_share"
private const val KEY_DELETE_IMAGES_AFTER_SHARE = "delete_images_after_share"
private const val KEY_APPEARANCE_MODE = "appearance_mode"
private const val KEY_APPEARANCE_NATURAL_INTENSITY = "appearance_natural_intensity"
private const val KEY_APPEARANCE_COLOR_INTENSITY = "appearance_color_intensity"
private const val KEY_APPEARANCE_LIGHT_TEXT_INTENSITY = "appearance_light_text_intensity"
private const val KEY_APPEARANCE_GRAYSCALE_INTENSITY = "appearance_grayscale_intensity"
private const val KEY_APPEARANCE_BLACK_WHITE_INTENSITY = "appearance_black_white_intensity"
private const val KEY_APPEARANCE_WHITEBOARD_INTENSITY = "appearance_whiteboard_intensity"
private const val KEY_APPEARANCE_SHADOWS = "appearance_shadows"
private const val KEY_PDF_SIZE_TARGET = "pdf_size_target"
private const val KEY_OCR_SCRIPT = "ocr_script"
private const val KEY_READ_ALOUD_LANGUAGE = "read_aloud_language"
private const val KEY_PDF_TREE_URI = "pdf_tree_uri"
private const val KEY_PENDING_PDF_TREE_URI = "pending_pdf_tree_uri"
private const val KEY_ACTIVE_RESULT_CHECKPOINT = "active_result_checkpoint"
private const val KEY_PENDING_SHARE_CLEANUP = "pending_share_cleanup"
private const val ACTIVE_RESULT_CHECKPOINT_V1_PREFIX = "1:"
private const val ACTIVE_RESULT_CHECKPOINT_V2_PREFIX = "2:"
private const val ACTIVE_RESULT_CHECKPOINT_V3_PREFIX = "3:"
private const val MAX_ACTIVE_RESULT_CACHE_ID_LENGTH = 128
private const val MAX_ACTIVE_RESULT_CHECKPOINT_LENGTH = 192
private const val PENDING_SHARE_CLEANUP_PREFIX = "1:"
private const val CANONICAL_UUID_LENGTH = 36
private const val MAX_SHARE_CLEANUP_KIND_LENGTH = 6
private const val MAX_PENDING_SHARE_CLEANUP_LENGTH =
    PENDING_SHARE_CLEANUP_PREFIX.length +
        MAX_ACTIVE_RESULT_CACHE_ID_LENGTH + 1 +
        CANONICAL_UUID_LENGTH + 1 +
        MAX_SHARE_CLEANUP_KIND_LENGTH
internal const val MAX_PENDING_SHARE_CLEANUPS = 8
private val shareCleanupQueueLock = ReentrantLock()

private val ACTIVE_RESULT_AUTHORITY_LOCK = ReentrantLock()
private var activeResultAuthorityRevision = 0L

internal fun <T> withActiveResultAuthority(block: () -> T): T =
    ACTIVE_RESULT_AUTHORITY_LOCK.withLock(block)

internal enum class AuthorityMutationResult {
    Applied,
    Stale,
    Busy,
}

internal fun settingsSaveApplied(result: AuthorityMutationResult): Boolean =
    result == AuthorityMutationResult.Applied

internal data class ActiveResultCheckpoint(
    val cacheId: String,
)

internal data class ActiveResultOwner(
    val revision: Long,
    val checkpoint: ActiveResultCheckpoint?,
) {
    fun withCheckpoint(checkpoint: ActiveResultCheckpoint?): ActiveResultOwner =
        copy(checkpoint = checkpoint)
}

internal data class ActiveResultAuthoritySnapshot(
    val settings: AppSettings,
    val checkpoint: ActiveResultCheckpoint?,
    val owner: ActiveResultOwner,
)

internal fun isSafeActiveResultCacheId(cacheId: String): Boolean =
    cacheId.length <= MAX_ACTIVE_RESULT_CACHE_ID_LENGTH && isSafeCacheId(cacheId)

internal fun encodeActiveResultCheckpoint(cacheId: String): String {
    require(isSafeActiveResultCacheId(cacheId)) {
        "Active result cache ID is unsafe"
    }
    return "$ACTIVE_RESULT_CHECKPOINT_V1_PREFIX$cacheId"
}

internal fun decodeActiveResultCheckpointPayload(value: String?): ActiveResultCheckpoint? {
    if (value == null || value.length > MAX_ACTIVE_RESULT_CHECKPOINT_LENGTH) return null
    if (value.startsWith(ACTIVE_RESULT_CHECKPOINT_V3_PREFIX)) {
        val fields = value.split(':')
        if (fields.size != 3 || fields[0] != "3") return null
        val cacheId = fields[1].takeIf(::isSafeActiveResultCacheId) ?: return null
        fields[2].takeIf(::isCanonicalUuid) ?: return null
        return ActiveResultCheckpoint(cacheId)
    }
    if (value.startsWith(ACTIVE_RESULT_CHECKPOINT_V1_PREFIX)) {
        val cacheId = value.removePrefix(ACTIVE_RESULT_CHECKPOINT_V1_PREFIX)
        return cacheId.takeIf(::isSafeActiveResultCacheId)?.let {
            ActiveResultCheckpoint(it)
        }
    }
    if (!value.startsWith(ACTIVE_RESULT_CHECKPOINT_V2_PREFIX)) return null
    val fields = value.split(':')
    if (fields.size != 7 || fields[0] != "2") return null
    val cacheId = fields[1].takeIf(::isSafeActiveResultCacheId) ?: return null
    val colorMode = ScanColorMode.entries.firstOrNull { it.wireValue == fields[2] } ?: return null
    val percentages = fields.drop(3).map { it.toIntOrNull()?.takeIf { value -> value in 0..100 } }
    if (percentages.any { it == null }) return null
    return ActiveResultCheckpoint(cacheId)
}

internal fun encodePendingShareCleanup(request: ShareCleanupRequest): String {
    require(isSafeActiveResultCacheId(request.cacheId) && isCanonicalUuid(request.entryId)) {
        "Pending share cleanup identity is unsafe"
    }
    return "$PENDING_SHARE_CLEANUP_PREFIX${request.cacheId}:${request.entryId}:${request.kind.wireValue}"
}

internal fun decodePendingShareCleanup(value: String?): ShareCleanupRequest? {
    if (
        value == null ||
            value.length > MAX_PENDING_SHARE_CLEANUP_LENGTH ||
            !value.startsWith(PENDING_SHARE_CLEANUP_PREFIX)
    ) {
        return null
    }
    val parts = value.removePrefix(PENDING_SHARE_CLEANUP_PREFIX).split(':')
    if (parts.size != 3) return null
    return decodeShareCleanupRequest(parts[0], parts[1], parts[2])
        ?.takeIf { isSafeActiveResultCacheId(it.cacheId) }
}

internal fun decodeActiveResultCheckpoint(value: String?): String? =
    decodeActiveResultCheckpointPayload(value)?.cacheId

internal fun normalizeAlbumName(value: String): String {
    val trimmed = value.trim()
    if (
        trimmed.isEmpty() ||
            trimmed == "." ||
            trimmed == ".." ||
            '/' in trimmed ||
            '\\' in trimmed ||
            value.any { it.isISOControl() }
    ) {
        return DEFAULT_ALBUM_NAME
    }
    return trimmed.take(MAX_ALBUM_NAME_LENGTH)
}

internal inline fun <T> readPreferenceOrDefault(default: T, read: () -> T): T =
    try {
        read()
    } catch (_: ClassCastException) {
        default
    }

internal fun canonicalPdfTreeUri(store: SettingsStore): String? = store.currentPdfTreeUri()

internal class SettingsStore(
    private val preferences: SharedPreferences,
    private val defaultEmailSubject: String,
) {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
        context.getString(R.string.default_email_subject),
    )

    fun load(): AppSettings {
        val defaults = AppSettings(emailSubject = defaultEmailSubject)
        val appearance = defaults.appearance
        return AppSettings(
            savePdf = readPreferenceOrDefault(defaults.savePdf) {
                preferences.getBoolean(KEY_SAVE_PDF, defaults.savePdf)
            },
            saveImages = readPreferenceOrDefault(defaults.saveImages) {
                preferences.getBoolean(KEY_SAVE_IMAGES, defaults.saveImages)
            },
            albumName = normalizeAlbumName(
                readPreferenceOrDefault(defaults.albumName) {
                    preferences.getString(KEY_ALBUM_NAME, defaults.albumName)
                        ?: defaults.albumName
                },
            ),
            multipage = readPreferenceOrDefault(defaults.multipage) {
                preferences.getBoolean(KEY_MULTIPAGE, defaults.multipage)
            },
            allowGallery = readPreferenceOrDefault(defaults.allowGallery) {
                preferences.getBoolean(KEY_ALLOW_GALLERY, defaults.allowGallery)
            },
            emailSubject =
                readPreferenceOrDefault(defaults.emailSubject) {
                    preferences.getString(KEY_EMAIL_SUBJECT, defaults.emailSubject)
                        ?: defaults.emailSubject
                },
            emailBody = readPreferenceOrDefault(defaults.emailBody) {
                preferences.getString(KEY_EMAIL_BODY, defaults.emailBody) ?: defaults.emailBody
            },
            pdfTreeUri = readPreferenceOrDefault(defaults.pdfTreeUri) {
                preferences.getString(KEY_PDF_TREE_URI, defaults.pdfTreeUri)
            },
            deletePdfAfterShare = readPreferenceOrDefault(defaults.deletePdfAfterShare) {
                preferences.getBoolean(KEY_DELETE_PDF_AFTER_SHARE, defaults.deletePdfAfterShare)
            },
            deleteImagesAfterShare = readPreferenceOrDefault(defaults.deleteImagesAfterShare) {
                preferences.getBoolean(
                    KEY_DELETE_IMAGES_AFTER_SHARE,
                    defaults.deleteImagesAfterShare,
                )
            },
            appearance =
                parseScanAppearanceSettings(
                    colorModeWireValue =
                        readPreferenceOrDefault<String?>(null) {
                            preferences.getString(KEY_APPEARANCE_MODE, null)
                        },
                    colorIntensity =
                        readPreferenceOrDefault(appearance.colorIntensity) {
                            preferences.getInt(
                                KEY_APPEARANCE_COLOR_INTENSITY,
                                appearance.colorIntensity,
                            )
                        },
                    naturalIntensity =
                        readPreferenceOrDefault(appearance.naturalIntensity) {
                            if (preferences.contains(KEY_APPEARANCE_NATURAL_INTENSITY)) {
                                preferences.getInt(
                                    KEY_APPEARANCE_NATURAL_INTENSITY,
                                    appearance.naturalIntensity,
                                )
                            } else {
                                preferences.getInt(
                                    KEY_APPEARANCE_COLOR_INTENSITY,
                                    appearance.naturalIntensity,
                                )
                            }
                        },
                    lightTextIntensity =
                        readPreferenceOrDefault(appearance.lightTextIntensity) {
                            if (preferences.contains(KEY_APPEARANCE_LIGHT_TEXT_INTENSITY)) {
                                preferences.getInt(
                                    KEY_APPEARANCE_LIGHT_TEXT_INTENSITY,
                                    appearance.lightTextIntensity,
                                )
                            } else {
                                preferences.getInt(
                                    KEY_APPEARANCE_COLOR_INTENSITY,
                                    appearance.lightTextIntensity,
                                )
                            }
                        },
                    grayscaleIntensity =
                        readPreferenceOrDefault(appearance.grayscaleIntensity) {
                            preferences.getInt(
                                KEY_APPEARANCE_GRAYSCALE_INTENSITY,
                                appearance.grayscaleIntensity,
                            )
                        },
                    whiteboardIntensity =
                        readPreferenceOrDefault(appearance.whiteboardIntensity) {
                            if (preferences.contains(KEY_APPEARANCE_WHITEBOARD_INTENSITY)) {
                                preferences.getInt(
                                    KEY_APPEARANCE_WHITEBOARD_INTENSITY,
                                    appearance.whiteboardIntensity,
                                )
                            } else {
                                preferences.getInt(
                                    KEY_APPEARANCE_BLACK_WHITE_INTENSITY,
                                    appearance.whiteboardIntensity,
                                )
                            }
                        },
                    blackWhiteIntensity =
                        readPreferenceOrDefault(appearance.blackWhiteIntensity) {
                            preferences.getInt(
                                KEY_APPEARANCE_BLACK_WHITE_INTENSITY,
                                appearance.blackWhiteIntensity,
                            )
                        },
                    shadows =
                        readPreferenceOrDefault(appearance.shadows) {
                            preferences.getInt(KEY_APPEARANCE_SHADOWS, appearance.shadows)
                        },
                ),
            pdfSizeTarget =
                parsePdfSizeTarget(
                    readPreferenceOrDefault<String?>(null) {
                        preferences.getString(KEY_PDF_SIZE_TARGET, null)
                    },
                ),
            ocrScript =
                ocrScriptForWireValue(
                    readPreferenceOrDefault<String?>(null) {
                        preferences.getString(KEY_OCR_SCRIPT, null)
                    },
                ),
            readAloudLanguage =
                readAloudLanguageForWireValue(
                    readPreferenceOrDefault<String?>(null) {
                        preferences.getString(KEY_READ_ALOUD_LANGUAGE, null)
                    },
                ),
        )
    }

    fun save(settings: AppSettings) {
        saveLocked(settings)
    }

    @Throws(IOException::class)
    internal fun trySave(
        settings: AppSettings,
        expectedOwner: ActiveResultOwner,
    ): AuthorityMutationResult {
        if (!ACTIVE_RESULT_AUTHORITY_LOCK.tryLock()) return AuthorityMutationResult.Busy
        return try {
            if (!ownerMatchesLocked(expectedOwner)) {
                AuthorityMutationResult.Stale
            } else {
                saveLocked(settings)
                AuthorityMutationResult.Applied
            }
        } finally {
            ACTIVE_RESULT_AUTHORITY_LOCK.unlock()
        }
    }

    private fun saveLocked(settings: AppSettings) {
        preferences
            .edit()
            .putBoolean(KEY_SAVE_PDF, settings.savePdf)
            .putBoolean(KEY_SAVE_IMAGES, settings.saveImages)
            .putString(KEY_ALBUM_NAME, normalizeAlbumName(settings.albumName))
            .putBoolean(KEY_MULTIPAGE, settings.multipage)
            .putBoolean(KEY_ALLOW_GALLERY, settings.allowGallery)
            .putString(KEY_EMAIL_SUBJECT, settings.emailSubject)
            .putString(KEY_EMAIL_BODY, settings.emailBody)
            .putBoolean(KEY_DELETE_PDF_AFTER_SHARE, settings.deletePdfAfterShare)
            .putBoolean(KEY_DELETE_IMAGES_AFTER_SHARE, settings.deleteImagesAfterShare)
            .putAppearance(settings.appearance)
            .putString(KEY_PDF_SIZE_TARGET, settings.pdfSizeTarget.wireValue)
            .putString(KEY_OCR_SCRIPT, settings.ocrScript.wireValue)
            .putString(KEY_READ_ALOUD_LANGUAGE, settings.readAloudLanguage.wireValue)
            .apply()
    }

    internal fun saveEmailSubject(subject: String) {
        preferences.edit().putString(KEY_EMAIL_SUBJECT, subject).apply()
    }

    @Throws(IOException::class)
    internal fun restoreAppearanceAuthority(
        appearance: ScanAppearanceSettings,
        pdfSizeTarget: PdfSizeTarget,
        expectedOwner: ActiveResultOwner,
    ): AuthorityMutationResult =
        withActiveResultAuthority {
            if (!ownerMatchesLocked(expectedOwner)) {
                return@withActiveResultAuthority AuthorityMutationResult.Stale
            }
            val normalized = normalizeAppearanceSettings(appearance)
            preferences
                .edit()
                .putAppearance(normalized)
                .putString(KEY_PDF_SIZE_TARGET, pdfSizeTarget.wireValue)
                .commit()
            val loaded = load()
            if (loaded.appearance != normalized || loaded.pdfSizeTarget != pdfSizeTarget) {
                throw IOException("Appearance authority could not be restored")
            }
            AuthorityMutationResult.Applied
        }

    @Throws(IOException::class)
    internal fun authoritySnapshot(): ActiveResultAuthoritySnapshot =
        withActiveResultAuthority(::authoritySnapshotLocked)

    @Throws(IOException::class)
    internal fun <T> withAuthoritySnapshot(
        block: (ActiveResultAuthoritySnapshot) -> T,
    ): T = withActiveResultAuthority { block(authoritySnapshotLocked()) }

    private fun authoritySnapshotLocked(): ActiveResultAuthoritySnapshot {
        val checkpoint = activeResultCheckpointLocked()
        check(activeResultAuthorityRevision < Long.MAX_VALUE) {
            "Active result authority revision exhausted"
        }
        val owner = ActiveResultOwner(++activeResultAuthorityRevision, checkpoint)
        return ActiveResultAuthoritySnapshot(
            settings = load(),
            checkpoint = checkpoint,
            owner = owner,
        )
    }

    @Throws(IOException::class)
    internal fun activeResultCheckpoint(): ActiveResultCheckpoint? =
        withActiveResultAuthority(::activeResultCheckpointLocked)

    @Throws(IOException::class)
    internal fun ownsActiveResult(owner: ActiveResultOwner): Boolean =
        withActiveResultAuthority { ownerMatchesLocked(owner) }

    private fun activeResultCheckpointLocked(): ActiveResultCheckpoint? {
        val storedValue =
            readPreferenceOrDefault<String?>(null) {
                preferences.getString(KEY_ACTIVE_RESULT_CHECKPOINT, null)
            }
        val checkpoint = decodeActiveResultCheckpointPayload(storedValue)
        if (checkpoint == null && preferences.contains(KEY_ACTIVE_RESULT_CHECKPOINT)) {
            throw IOException("Active result checkpoint is invalid")
        }
        return checkpoint
    }

    private fun ownerMatchesLocked(owner: ActiveResultOwner): Boolean =
        owner.revision == activeResultAuthorityRevision &&
            owner.checkpoint == activeResultCheckpointLocked()

    @Throws(IOException::class)
    internal fun activeResultCacheId(): String? = activeResultCheckpoint()?.cacheId

    @Throws(IOException::class)
    internal fun saveActiveResult(
        cacheId: String,
        expectedOwner: ActiveResultOwner,
    ): AuthorityMutationResult =
        withActiveResultAuthority {
            if (!ownerMatchesLocked(expectedOwner)) {
                return@withActiveResultAuthority AuthorityMutationResult.Stale
            }
            val encoded = encodeActiveResultCheckpoint(cacheId)
            preferences.edit().putString(KEY_ACTIVE_RESULT_CHECKPOINT, encoded).commit()
            val verified =
                readPreferenceOrDefault<String?>(null) {
                    preferences.getString(KEY_ACTIVE_RESULT_CHECKPOINT, null)
                } == encoded
            if (!verified) {
                throw IOException("Active result could not be stored")
            }
            AuthorityMutationResult.Applied
        }

    @Throws(IOException::class)
    internal fun clearActiveResult(
        expectedOwner: ActiveResultOwner,
    ): AuthorityMutationResult =
        withActiveResultAuthority {
            if (!ownerMatchesLocked(expectedOwner)) {
                return@withActiveResultAuthority AuthorityMutationResult.Stale
            }
            if (expectedOwner.checkpoint == null) {
                return@withActiveResultAuthority AuthorityMutationResult.Applied
            }
            preferences.edit().remove(KEY_ACTIVE_RESULT_CHECKPOINT).commit()
            if (activeResultCheckpointLocked() != null) {
                throw IOException("Active result could not be cleared")
            }
            AuthorityMutationResult.Applied
        }

    internal fun pendingPdfTreeUri(): String? =
        withStorageTransaction {
            readPreferenceOrDefault<String?>(null) {
                preferences.getString(KEY_PENDING_PDF_TREE_URI, null)
            }
        }

    @Throws(IOException::class)
    internal fun savePdfTreeUris(current: String?, pending: String?) =
        withStorageTransaction {
            val stored =
                preferences
                    .edit()
                    .putString(KEY_PDF_TREE_URI, current)
                    .putString(KEY_PENDING_PDF_TREE_URI, pending)
                    .commit()
            val currentRead =
                readPreferenceOrDefault<String?>(null) {
                    preferences.getString(KEY_PDF_TREE_URI, null)
                }
            val pendingRead =
                readPreferenceOrDefault<String?>(null) {
                    preferences.getString(KEY_PENDING_PDF_TREE_URI, null)
                }
            if (!stored || currentRead != current || pendingRead != pending) {
                throw IOException("PDF destinations could not be stored")
            }
        }
    @Throws(IOException::class)
    internal fun pendingShareCleanups(): List<ShareCleanupRequest> =
        shareCleanupQueueLock.withLock {
            readPendingShareCleanups()
        }

    @Throws(IOException::class)
    internal fun pendingShareCleanup(): ShareCleanupRequest? = pendingShareCleanups().firstOrNull()

    @Throws(IOException::class)
    internal fun canSavePendingShareCleanup(request: ShareCleanupRequest): Boolean =
        shareCleanupQueueLock.withLock {
            val pending = readPendingShareCleanups()
            request in pending || pending.size < MAX_PENDING_SHARE_CLEANUPS
        }

    private fun readPendingShareCleanups(): List<ShareCleanupRequest> {
        val stored =
            readPreferenceOrDefault<String?>(null) {
                preferences.getString(KEY_PENDING_SHARE_CLEANUP, null)
            }
        val requests =
            stored?.split('\n')?.takeIf { it.size <= MAX_PENDING_SHARE_CLEANUPS }
                ?.mapNotNull(::decodePendingShareCleanup)
                ?.takeIf { it.size == stored.split('\n').size && it.distinct().size == it.size }
        if (requests == null && preferences.contains(KEY_PENDING_SHARE_CLEANUP)) {
            val cleared = preferences.edit().remove(KEY_PENDING_SHARE_CLEANUP).commit()
            if (!cleared || preferences.contains(KEY_PENDING_SHARE_CLEANUP)) {
                throw IOException("Invalid pending share cleanup could not be cleared")
            }
        }
        return requests.orEmpty()
    }

    @Throws(IOException::class)
    internal fun savePendingShareCleanup(request: ShareCleanupRequest) =
        shareCleanupQueueLock.withLock {
            val existing = readPendingShareCleanups()
            if (request in existing) return@withLock
            if (existing.size >= MAX_PENDING_SHARE_CLEANUPS) {
                throw IOException("Pending share cleanup queue is full")
            }
            writePendingShareCleanups(existing + request)
        }

    @Throws(IOException::class)
    internal fun clearPendingShareCleanup(request: ShareCleanupRequest) =
        shareCleanupQueueLock.withLock {
            val pending = readPendingShareCleanups()
            if (pending.firstOrNull() != request) return@withLock
            writePendingShareCleanups(pending.drop(1))
        }

    private fun writePendingShareCleanups(requests: List<ShareCleanupRequest>) {
        val encoded = requests.joinToString("\n", transform = ::encodePendingShareCleanup)
        val editor = preferences.edit()
        if (requests.isEmpty()) editor.remove(KEY_PENDING_SHARE_CLEANUP)
        else editor.putString(KEY_PENDING_SHARE_CLEANUP, encoded)
        val stored = editor.commit()
        val verified =
            if (requests.isEmpty()) {
                !preferences.contains(KEY_PENDING_SHARE_CLEANUP)
            } else {
                readPreferenceOrDefault<String?>(null) {
                    preferences.getString(KEY_PENDING_SHARE_CLEANUP, null)
                } == encoded
            }
        if (!stored || !verified) throw IOException("Pending share cleanup could not be stored")
    }

    internal fun currentPdfTreeUri(): String? =
        withStorageTransaction {
            readPreferenceOrDefault<String?>(null) {
                preferences.getString(KEY_PDF_TREE_URI, null)
            }
        }

}

private fun SharedPreferences.Editor.putAppearance(
    appearance: ScanAppearanceSettings,
): SharedPreferences.Editor {
    val normalized = normalizeAppearanceSettings(appearance)
    return putString(KEY_APPEARANCE_MODE, normalized.colorMode.wireValue)
        .putInt(KEY_APPEARANCE_NATURAL_INTENSITY, normalized.naturalIntensity)
        .putInt(KEY_APPEARANCE_COLOR_INTENSITY, normalized.colorIntensity)
        .putInt(KEY_APPEARANCE_LIGHT_TEXT_INTENSITY, normalized.lightTextIntensity)
        .putInt(KEY_APPEARANCE_GRAYSCALE_INTENSITY, normalized.grayscaleIntensity)
        .putInt(KEY_APPEARANCE_BLACK_WHITE_INTENSITY, normalized.blackWhiteIntensity)
        .putInt(KEY_APPEARANCE_WHITEBOARD_INTENSITY, normalized.whiteboardIntensity)
        .putInt(KEY_APPEARANCE_SHADOWS, normalized.shadows)
}

private fun normalizeAppearanceSettings(
    appearance: ScanAppearanceSettings,
): ScanAppearanceSettings =
    parseScanAppearanceSettings(
        colorModeWireValue = appearance.colorMode.wireValue,
        naturalIntensity = appearance.naturalIntensity,
        colorIntensity = appearance.colorIntensity,
        lightTextIntensity = appearance.lightTextIntensity,
        grayscaleIntensity = appearance.grayscaleIntensity,
        blackWhiteIntensity = appearance.blackWhiteIntensity,
        whiteboardIntensity = appearance.whiteboardIntensity,
        shadows = appearance.shadows,
    )
