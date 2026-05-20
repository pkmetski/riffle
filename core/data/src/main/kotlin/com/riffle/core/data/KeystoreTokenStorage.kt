package com.riffle.core.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.riffle.core.domain.TokenStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class KeystoreTokenStorage @Inject constructor(
    @ApplicationContext private val context: Context,
) : TokenStorage {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "riffle_tokens",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override suspend fun saveToken(serverId: String, token: String) =
        withContext(Dispatchers.IO) { prefs.edit().putString(serverId, token).apply() }

    override suspend fun getToken(serverId: String): String? =
        withContext(Dispatchers.IO) { prefs.getString(serverId, null) }

    override suspend fun deleteToken(serverId: String) =
        withContext(Dispatchers.IO) { prefs.edit().remove(serverId).apply() }
}
