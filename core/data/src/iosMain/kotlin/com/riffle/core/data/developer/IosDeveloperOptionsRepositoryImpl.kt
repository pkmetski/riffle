package com.riffle.core.data.developer

import com.riffle.core.domain.developer.DeveloperOptionsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import platform.Foundation.NSUserDefaults

internal class IosDeveloperOptionsRepositoryImpl : DeveloperOptionsRepository {
    private val defaults = NSUserDefaults.standardUserDefaults

    private val _developerModeEnabled = MutableStateFlow(
        if (defaults.objectForKey(KEY_DEV_MODE) != null) defaults.boolForKey(KEY_DEV_MODE) else false,
    )

    override val developerModeEnabled: Flow<Boolean> = _developerModeEnabled

    override suspend fun setDeveloperModeEnabled(enabled: Boolean) {
        defaults.setBool(enabled, forKey = KEY_DEV_MODE)
        _developerModeEnabled.value = enabled
    }

    override suspend fun getGithubPat(): String? =
        defaults.stringForKey(KEY_GITHUB_PAT)?.takeIf { it.isNotEmpty() }

    override suspend fun setGithubPat(pat: String?) {
        if (pat != null) {
            defaults.setObject(pat, forKey = KEY_GITHUB_PAT)
        } else {
            defaults.removeObjectForKey(KEY_GITHUB_PAT)
        }
    }

    private companion object {
        const val KEY_DEV_MODE = "developer.mode_enabled"
        const val KEY_GITHUB_PAT = "developer.github_pat"
    }
}
