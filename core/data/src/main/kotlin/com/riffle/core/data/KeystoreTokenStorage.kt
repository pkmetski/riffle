package com.riffle.core.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.riffle.core.domain.TokenStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class KeystoreTokenStorage @Inject constructor(
    @param:ApplicationContext private val context: Context,
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

    override suspend fun saveToken(serverId: String, token: String) =
        withContext(Dispatchers.IO) { prefs.edit().putString(serverId, token).apply() }

    override suspend fun getToken(serverId: String): String? =
        withContext(Dispatchers.IO) { prefs.getString(serverId, null) }

    override suspend fun deleteToken(serverId: String) =
        withContext(Dispatchers.IO) { prefs.edit().remove(serverId).apply() }
}
