package cz.mates.skendopdf

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val MAX_ALBUM_NAME_LENGTH = 64
private const val PREFERENCES_NAME = "settings"
private const val KEY_SAVE_PDF = "save_pdf"
private const val KEY_SAVE_IMAGES = "save_images"
private const val KEY_ALBUM_NAME = "album_name_scanit"
private const val KEY_MULTIPAGE = "multipage"
private const val KEY_ALLOW_GALLERY = "allow_gallery"
private const val KEY_EMAIL_SUBJECT = "email_subject"
private const val KEY_EMAIL_BODY = "email_body"
private const val KEY_PDF_TREE_URI = "pdf_tree_uri"
private const val KEY_PENDING_PDF_TREE_URI = "pending_pdf_tree_uri"
private const val KEY_AI_ENABLED = "ai_enabled"
private const val KEY_AI_CONSENT = "ai_consent"
private const val KEY_GEMINI_CIPHERTEXT = "gemini_ciphertext"
private const val KEY_GEMINI_IV = "gemini_iv"
private const val GEMINI_KEY_ALIAS = "cz.mates.skendopdf.gemini_api_key"
private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
private const val GCM_TAG_LENGTH_BITS = 128

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

internal class SettingsStore(private val context: Context) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    @Volatile
    private var pendingPdfTreeUriValue =
        readPreferenceOrDefault<String?>(null) {
            preferences.getString(KEY_PENDING_PDF_TREE_URI, null)
    }

    fun load(): AppSettings {
        val defaults =
            AppSettings(emailSubject = context.getString(R.string.default_email_subject))
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
            aiEnabled = readPreferenceOrDefault(defaults.aiEnabled) {
                preferences.getBoolean(KEY_AI_ENABLED, defaults.aiEnabled)
            },
            aiConsent = readPreferenceOrDefault(defaults.aiConsent) {
                preferences.getBoolean(KEY_AI_CONSENT, defaults.aiConsent)
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
            .putString(KEY_PENDING_PDF_TREE_URI, pendingPdfTreeUriValue)
            .putBoolean(KEY_AI_ENABLED, settings.aiEnabled)
            .putBoolean(KEY_AI_CONSENT, settings.aiConsent)
            .apply()
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

    @Throws(GeneralSecurityException::class)
    fun saveGeminiApiKey(apiKey: String) {
        if (apiKey.isBlank()) {
            clearGeminiApiKey()
            return
        }

        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateGeminiKey())
        val ciphertext = cipher.doFinal(apiKey.toByteArray(StandardCharsets.UTF_8))
        val stored =
            preferences
                .edit()
                .putString(KEY_GEMINI_CIPHERTEXT, Base64.getEncoder().encodeToString(ciphertext))
                .putString(KEY_GEMINI_IV, Base64.getEncoder().encodeToString(cipher.iv))
                .commit()
        if (!stored) {
            throw GeneralSecurityException("Gemini API key ciphertext could not be stored")
        }
    }

    @Throws(GeneralSecurityException::class)
    fun loadGeminiApiKey(): String? {
        val ciphertextValue = storedSecretValue(KEY_GEMINI_CIPHERTEXT)
        val ivValue = storedSecretValue(KEY_GEMINI_IV)
        if (ciphertextValue == null && ivValue == null) {
            return null
        }
        if (ciphertextValue == null || ivValue == null) {
            throw GeneralSecurityException("Stored Gemini API key is incomplete")
        }

        val ciphertext = decodeStoredBase64(ciphertextValue)
        val iv = decodeStoredBase64(ivValue)
        if (ciphertext.isEmpty() || iv.isEmpty()) {
            throw GeneralSecurityException("Stored Gemini API key is empty")
        }

        val key = androidKeyStore().getKey(GEMINI_KEY_ALIAS, null) as? SecretKey
            ?: throw GeneralSecurityException("Gemini API key encryption key is missing")
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        val plaintext = cipher.doFinal(ciphertext)
        if (plaintext.isEmpty()) {
            throw GeneralSecurityException("Stored Gemini API key decrypts to an empty value")
        }
        return String(plaintext, StandardCharsets.UTF_8)
    }

    @Throws(GeneralSecurityException::class)
    fun clearGeminiApiKey() {
        val cleared =
            preferences
                .edit()
                .remove(KEY_GEMINI_CIPHERTEXT)
                .remove(KEY_GEMINI_IV)
                .commit()
        if (!cleared) {
            throw GeneralSecurityException("Stored Gemini API key could not be cleared")
        }

        val keyStore = androidKeyStore()
        if (keyStore.containsAlias(GEMINI_KEY_ALIAS)) {
            keyStore.deleteEntry(GEMINI_KEY_ALIAS)
        }
    }

    @Throws(GeneralSecurityException::class)
    private fun getOrCreateGeminiKey(): SecretKey {
        val keyStore = androidKeyStore()
        val storedKey = keyStore.getKey(GEMINI_KEY_ALIAS, null)
        if (storedKey != null) {
            return storedKey as? SecretKey
                ?: throw GeneralSecurityException("Gemini API key alias is not an AES key")
        }
        if (keyStore.containsAlias(GEMINI_KEY_ALIAS)) {
            throw GeneralSecurityException("Gemini API key alias is unusable")
        }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                GEMINI_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    @Throws(GeneralSecurityException::class)
    private fun androidKeyStore(): KeyStore =
        try {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        } catch (exception: IOException) {
            throw GeneralSecurityException("Android Keystore could not be loaded", exception)
        }

    @Throws(GeneralSecurityException::class)
    private fun storedSecretValue(key: String): String? =
        try {
            preferences.getString(key, null)
        } catch (exception: ClassCastException) {
            throw GeneralSecurityException("Stored Gemini API key is malformed", exception)
        }

    @Throws(GeneralSecurityException::class)
    private fun decodeStoredBase64(value: String): ByteArray =
        try {
            Base64.getDecoder().decode(value)
        } catch (exception: IllegalArgumentException) {
            throw GeneralSecurityException("Stored Gemini API key is not valid Base64", exception)
        }
}
