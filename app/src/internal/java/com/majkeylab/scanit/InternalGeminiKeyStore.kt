package com.majkeylab.scanit

import android.content.Context
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

internal const val INTERNAL_GEMINI_MAX_API_KEY_LENGTH = 4096

private const val PREFERENCES_NAME = "settings"
private const val KEY_GEMINI_CIPHERTEXT = "gemini_ciphertext"
private const val KEY_GEMINI_IV = "gemini_iv"
private const val GEMINI_KEY_ALIAS = "com.majkeylab.scanit.gemini_api_key"
private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
private const val GCM_TAG_LENGTH_BITS = 128
private const val MAX_STORED_CIPHERTEXT_LENGTH = 8192
private const val MAX_STORED_IV_LENGTH = 128

internal fun normalizeInternalGeminiApiKey(value: String): String {
    val normalized = value.trim()
    require(normalized.isNotEmpty()) { "Gemini API key must not be blank" }
    require('\r' !in normalized && '\n' !in normalized) { "Gemini API key is invalid" }
    require(normalized.toByteArray(StandardCharsets.UTF_8).size <= INTERNAL_GEMINI_MAX_API_KEY_LENGTH) {
        "Gemini API key is too long"
    }
    return normalized
}

internal class InternalGeminiKeyStore(context: Context) {
    private val preferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Throws(GeneralSecurityException::class)
    fun save(apiKey: String) {
        val normalized = normalizeInternalGeminiApiKey(apiKey)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(normalized.toByteArray(StandardCharsets.UTF_8))
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
    fun load(): String? {
        val ciphertextValue = storedValue(KEY_GEMINI_CIPHERTEXT, MAX_STORED_CIPHERTEXT_LENGTH)
        val ivValue = storedValue(KEY_GEMINI_IV, MAX_STORED_IV_LENGTH)
        if (ciphertextValue == null && ivValue == null) return null
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
        return normalizeInternalGeminiApiKey(
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8),
        )
    }

    @Throws(GeneralSecurityException::class)
    fun clear() {
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
    private fun getOrCreateKey(): SecretKey {
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
    private fun storedValue(key: String, maxLength: Int): String? {
        val value =
            try {
                preferences.getString(key, null)
            } catch (exception: ClassCastException) {
                throw GeneralSecurityException("Stored Gemini API key is malformed", exception)
            }
        if (value != null && value.length > maxLength) {
            throw GeneralSecurityException("Stored Gemini API key is too large")
        }
        return value
    }

    @Throws(GeneralSecurityException::class)
    private fun decodeStoredBase64(value: String): ByteArray =
        try {
            Base64.getDecoder().decode(value)
        } catch (exception: IllegalArgumentException) {
            throw GeneralSecurityException("Stored Gemini API key is not valid Base64", exception)
        }
}
