package com.majkeylab.scanit

import android.content.Context
import android.content.SharedPreferences
import java.io.IOException

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
private const val KEY_PDF_TREE_URI = "pdf_tree_uri"
private const val KEY_PENDING_PDF_TREE_URI = "pending_pdf_tree_uri"
private const val KEY_ACTIVE_RESULT_CHECKPOINT = "active_result_checkpoint"
private const val ACTIVE_RESULT_CHECKPOINT_PREFIX = "1:"
private const val MAX_ACTIVE_RESULT_CACHE_ID_LENGTH = 128

internal fun isSafeActiveResultCacheId(cacheId: String): Boolean =
    cacheId.length <= MAX_ACTIVE_RESULT_CACHE_ID_LENGTH && isSafeCacheId(cacheId)

internal fun encodeActiveResultCheckpoint(cacheId: String): String {
    require(isSafeActiveResultCacheId(cacheId)) {
        "Active result cache ID is unsafe"
    }
    return "$ACTIVE_RESULT_CHECKPOINT_PREFIX$cacheId"
}

internal fun decodeActiveResultCheckpoint(value: String?): String? {
    if (
        value == null ||
            value.length >
            ACTIVE_RESULT_CHECKPOINT_PREFIX.length + MAX_ACTIVE_RESULT_CACHE_ID_LENGTH ||
            !value.startsWith(ACTIVE_RESULT_CHECKPOINT_PREFIX)
    ) {
        return null
    }
    return value.removePrefix(ACTIVE_RESULT_CHECKPOINT_PREFIX)
        .takeIf(::isSafeActiveResultCacheId)
}

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

internal class SettingsStore(
    private val preferences: SharedPreferences,
    private val defaultEmailSubject: String,
) {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
        context.getString(R.string.default_email_subject),
    )

    @Volatile
    private var pendingPdfTreeUriValue =
        readPreferenceOrDefault<String?>(null) {
            preferences.getString(KEY_PENDING_PDF_TREE_URI, null)
    }

    fun load(): AppSettings {
        val defaults = AppSettings(emailSubject = defaultEmailSubject)
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
        )
    }

    fun save(settings: AppSettings) {
        preferences
            .edit()
            .putBoolean(KEY_SAVE_PDF, settings.savePdf)
            .putBoolean(KEY_SAVE_IMAGES, settings.saveImages)
            .putString(KEY_ALBUM_NAME, normalizeAlbumName(settings.albumName))
            .putBoolean(KEY_MULTIPAGE, settings.multipage)
            .putBoolean(KEY_ALLOW_GALLERY, settings.allowGallery)
            .putString(KEY_EMAIL_SUBJECT, settings.emailSubject)
            .putString(KEY_EMAIL_BODY, settings.emailBody)
            .putString(KEY_PDF_TREE_URI, settings.pdfTreeUri)
            .putBoolean(KEY_DELETE_PDF_AFTER_SHARE, settings.deletePdfAfterShare)
            .putBoolean(KEY_DELETE_IMAGES_AFTER_SHARE, settings.deleteImagesAfterShare)
            .putString(KEY_PENDING_PDF_TREE_URI, pendingPdfTreeUriValue)
            .apply()
    }

    @Throws(IOException::class)
    internal fun activeResultCacheId(): String? {
        val storedValue =
            readPreferenceOrDefault<String?>(null) {
                preferences.getString(KEY_ACTIVE_RESULT_CHECKPOINT, null)
            }
        val cacheId = decodeActiveResultCheckpoint(storedValue)
        if (cacheId == null && preferences.contains(KEY_ACTIVE_RESULT_CHECKPOINT)) {
            clearActiveResult()
        }
        return cacheId
    }

    @Throws(IOException::class)
    internal fun saveActiveResult(cacheId: String) {
        val checkpoint = encodeActiveResultCheckpoint(cacheId)
        val stored =
            preferences.edit().putString(KEY_ACTIVE_RESULT_CHECKPOINT, checkpoint).commit()
        val verified =
            readPreferenceOrDefault<String?>(null) {
                preferences.getString(KEY_ACTIVE_RESULT_CHECKPOINT, null)
            } == checkpoint
        if (!stored || !verified) {
            throw IOException("Active result could not be stored")
        }
    }

    @Throws(IOException::class)
    internal fun clearActiveResult() {
        if (!preferences.contains(KEY_ACTIVE_RESULT_CHECKPOINT)) return
        val cleared = preferences.edit().remove(KEY_ACTIVE_RESULT_CHECKPOINT).commit()
        if (!cleared || preferences.contains(KEY_ACTIVE_RESULT_CHECKPOINT)) {
            throw IOException("Active result could not be cleared")
        }
    }

    internal fun pendingPdfTreeUri(): String? = pendingPdfTreeUriValue

    @Throws(IOException::class)
    internal fun savePdfTreeUris(current: String?, pending: String?) {
        val stored =
            preferences
                .edit()
                .putString(KEY_PDF_TREE_URI, current)
                .putString(KEY_PENDING_PDF_TREE_URI, pending)
                .commit()
        if (!stored) {
            throw IOException("PDF destinations could not be stored")
        }
        pendingPdfTreeUriValue = pending
    }

}
