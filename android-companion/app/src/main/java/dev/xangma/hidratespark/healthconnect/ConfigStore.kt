package dev.xangma.hidratespark.healthconnect

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ConnectionSettings(
    val baseUrl: String,
    val token: String,
) {
    companion object {
        fun normalizeBaseUrl(raw: String): String {
            val value = raw.trim().trimEnd('/')
            val uri = runCatching { URI(value) }
                .getOrElse { throw IllegalArgumentException("Enter a valid Home Assistant URL") }
            require(uri.scheme.equals("https", ignoreCase = true)) {
                "Use an HTTPS Home Assistant URL so the access token stays private"
            }
            require(!uri.host.isNullOrBlank() && uri.userInfo == null) {
                "Enter a valid HTTPS Home Assistant URL"
            }
            require(uri.query == null && uri.fragment == null) {
                "The Home Assistant URL cannot include a query or fragment"
            }
            return value
        }
    }
}

// Direct SharedPreferences commits are intentional: cursor durability is part
// of the synchronization transaction, and the commit result must be checked.
@SuppressLint("UseKtx")
class ConfigStore(context: Context) {
    private val preferences: SharedPreferences = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val secrets = SecretCipher()

    fun savedBaseUrl(): String = preferences.getString(KEY_BASE_URL, "").orEmpty()

    fun loadConnection(): ConnectionSettings? {
        val baseUrl = preferences.getString(KEY_BASE_URL, null) ?: return null
        val ciphertext = preferences.getString(KEY_TOKEN_CIPHERTEXT, null) ?: return null
        val iv = preferences.getString(KEY_TOKEN_IV, null) ?: return null
        return ConnectionSettings(baseUrl, secrets.decrypt(ciphertext, iv))
    }

    /** A blank token keeps the existing token, but only for the same server. */
    fun saveConnection(rawBaseUrl: String, rawToken: String) {
        val baseUrl = ConnectionSettings.normalizeBaseUrl(rawBaseUrl)
        val oldBaseUrl = preferences.getString(KEY_BASE_URL, null)
        val token = rawToken.trim()
        val changingServer = oldBaseUrl != null && oldBaseUrl != baseUrl
        require(token.isNotEmpty() || (!changingServer && hasSavedToken())) {
            "Enter a Home Assistant long-lived access token"
        }

        val editor = preferences.edit().putString(KEY_BASE_URL, baseUrl)
        if (token.isNotEmpty()) {
            val encrypted = secrets.encrypt(token)
            editor
                .putString(KEY_TOKEN_CIPHERTEXT, encrypted.ciphertext)
                .putString(KEY_TOKEN_IV, encrypted.iv)
        }
        check(editor.commit()) { "Could not save connection settings" }
    }

    fun cursor(baseUrl: String, entryId: String, journalId: String): Long =
        preferences.getLong(cursorKey(baseUrl, entryId, journalId), 0L)

    fun saveCursor(baseUrl: String, entryId: String, journalId: String, cursor: Long) {
        synchronized(CURSOR_LOCK) {
            val key = cursorKey(baseUrl, entryId, journalId)
            if (cursor > preferences.getLong(key, 0L)) {
                check(preferences.edit().putLong(key, cursor).commit()) {
                    "Could not save synchronization cursor"
                }
            }
        }
    }

    private fun hasSavedToken(): Boolean =
        preferences.contains(KEY_TOKEN_CIPHERTEXT) && preferences.contains(KEY_TOKEN_IV)

    private fun cursorKey(baseUrl: String, entryId: String, journalId: String): String =
        CURSOR_PREFIX + RecordIds.sha256("$baseUrl\u0000$entryId\u0000$journalId")

    private data class EncryptedValue(val ciphertext: String, val iv: String)

    private class SecretCipher {
        fun encrypt(plaintext: String): EncryptedValue {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            return EncryptedValue(
                ciphertext = Base64.encodeToString(cipher.doFinal(plaintext.toByteArray()), Base64.NO_WRAP),
                iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            )
        }

        fun decrypt(ciphertext: String, iv: String): String {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
            return String(
                cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)),
                Charsets.UTF_8,
            )
        }

        private fun key(): SecretKey {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
                .apply {
                    init(
                        KeyGenParameterSpec.Builder(
                            KEY_ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .build(),
                    )
                }
                .generateKey()
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "health_connect_sync"
        const val KEY_BASE_URL = "base_url"
        const val KEY_TOKEN_CIPHERTEXT = "token_ciphertext"
        const val KEY_TOKEN_IV = "token_iv"
        const val CURSOR_PREFIX = "cursor_"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "hidratespark_home_assistant_token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        val CURSOR_LOCK = Any()
    }
}
