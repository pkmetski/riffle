package com.riffle.core.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Android-Keystore-backed [EncryptedKeyValueStore] used for credentials such as the
 * WebDAV password. Mirrors the pattern in [KeystoreTokenStorage]: if the keyset is
 * missing or corrupt (reinstall, KeyStore invalidated), we delete the prefs file and
 * start fresh — the secret is unrecoverable anyway.
 */
class KeystoreEncryptedKeyValueStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : EncryptedKeyValueStore {

    private val prefs by lazy {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        fun create() = EncryptedSharedPreferences.create(
            PREFS_NAME,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        try {
            create()
        } catch (_: Exception) {
            context.deleteSharedPreferences(PREFS_NAME)
            create()
        }
    }

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    companion object {
        private const val PREFS_NAME = "riffle_sync_config"
    }
}
