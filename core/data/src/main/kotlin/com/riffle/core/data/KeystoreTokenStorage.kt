package com.riffle.core.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.TokenStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import javax.inject.Inject

class KeystoreTokenStorage @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
) : TokenStorage {

    private val prefs by lazy {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        fun create() = EncryptedSharedPreferences.create(
            "riffle_tokens",
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        try {
            create()
        } catch (_: Exception) {
            // Keyset is missing or corrupt (e.g. app reinstalled without clearing prefs, or
            // KeyStore invalidated). Tokens are unrecoverable — wipe and start fresh.
            context.deleteSharedPreferences("riffle_tokens")
            create()
        }
    }

    override suspend fun saveToken(sourceId: String, token: String) =
        withContext(dispatchers.io) { prefs.edit().putString(sourceId, token).apply() }

    override suspend fun getToken(sourceId: String): String? =
        withContext(dispatchers.io) { prefs.getString(sourceId, null) }

    override suspend fun deleteToken(sourceId: String) =
        withContext(dispatchers.io) { prefs.edit().remove(sourceId).apply() }

    override suspend fun savePassword(sourceId: String, password: String) =
        withContext(dispatchers.io) { prefs.edit().putString(passwordKey(sourceId), password).apply() }

    override suspend fun getPassword(sourceId: String): String? =
        withContext(dispatchers.io) { prefs.getString(passwordKey(sourceId), null) }

    override suspend fun deletePassword(sourceId: String) =
        withContext(dispatchers.io) { prefs.edit().remove(passwordKey(sourceId)).apply() }

    /** Distinct key prefix so passwords never collide with the bare-sourceId token keys. */
    private fun passwordKey(sourceId: String): String = "password:$sourceId"
}
