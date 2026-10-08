package com.riffle.core.data.developer

import com.riffle.core.data.KEYCHAIN_SERVICE
import com.riffle.core.data.deleteKeychainItem
import com.riffle.core.data.loadKeychainItem
import com.riffle.core.data.saveKeychainItem
import com.riffle.core.domain.developer.DeveloperOptionsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import platform.Foundation.NSUserDefaults

private const val PAT_ACCOUNT = "github_pat"

internal class IosDeveloperOptionsRepositoryImpl : DeveloperOptionsRepository {
    private val defaults = NSUserDefaults.standardUserDefaults

    init {
        // One-time migration: move PAT from NSUserDefaults to Keychain
        val existingPat = defaults.stringForKey(KEY_GITHUB_PAT)?.takeIf { it.isNotEmpty() }
        if (existingPat != null && loadKeychainItem(KEYCHAIN_SERVICE, PAT_ACCOUNT) == null) {
            saveKeychainItem(KEYCHAIN_SERVICE, PAT_ACCOUNT, existingPat)
            defaults.removeObjectForKey(KEY_GITHUB_PAT)
        }
    }

    private val _developerModeEnabled = MutableStateFlow(
        if (defaults.objectForKey(KEY_DEV_MODE) != null) defaults.boolForKey(KEY_DEV_MODE) else false,
    )

    override val developerModeEnabled: Flow<Boolean> = _developerModeEnabled

    override suspend fun setDeveloperModeEnabled(enabled: Boolean) {
        defaults.setBool(enabled, forKey = KEY_DEV_MODE)
        _developerModeEnabled.value = enabled
    }

    override suspend fun getGithubPat(): String? = loadKeychainItem(KEYCHAIN_SERVICE, PAT_ACCOUNT)

    override suspend fun setGithubPat(pat: String?) {
        if (pat.isNullOrEmpty()) {
            deleteKeychainItem(KEYCHAIN_SERVICE, PAT_ACCOUNT)
        } else {
            saveKeychainItem(KEYCHAIN_SERVICE, PAT_ACCOUNT, pat)
        }
    }

    private companion object {
        const val KEY_DEV_MODE = "developer.mode_enabled"
        const val KEY_GITHUB_PAT = "developer.github_pat"
    }
}
