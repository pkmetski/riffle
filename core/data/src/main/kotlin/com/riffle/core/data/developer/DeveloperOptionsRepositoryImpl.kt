package com.riffle.core.data.developer

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.riffle.core.domain.developer.DeveloperOptionsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Seam for PAT storage — injected as [AndroidPatStore] in production, test doubles in tests. */
interface PatStore {
    fun get(): String?
    fun set(pat: String?)
}

class AndroidPatStore(context: Context) : PatStore {

    private val prefs by lazy {
        val alias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        fun create() = EncryptedSharedPreferences.create(
            PREFS_NAME,
            alias,
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

    override fun get(): String? = prefs.getString(KEY_PAT, null)

    override fun set(pat: String?) {
        if (pat != null) {
            prefs.edit().putString(KEY_PAT, pat).apply()
        } else {
            prefs.edit().remove(KEY_PAT).apply()
        }
    }

    companion object {
        private const val PREFS_NAME = "riffle_developer_options"
        private const val KEY_PAT = "github_pat"
    }
}

class DeveloperOptionsRepositoryImpl constructor(
    private val dataStore: DataStore<Preferences>,
    private val patStore: PatStore,
) : DeveloperOptionsRepository {

    override val developerModeEnabled: Flow<Boolean> =
        dataStore.data.map { it[KEY_DEVELOPER_MODE] ?: false }

    override suspend fun setDeveloperModeEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_DEVELOPER_MODE] = enabled }
    }

    override suspend fun getGithubPat(): String? = withContext(Dispatchers.IO) { patStore.get() }

    override suspend fun setGithubPat(pat: String?) = withContext(Dispatchers.IO) { patStore.set(pat) }

    companion object {
        private val KEY_DEVELOPER_MODE = booleanPreferencesKey("developer_mode_enabled")
    }
}
