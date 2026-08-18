package com.riffle.core.domain.developer

import kotlinx.coroutines.flow.Flow

interface DeveloperOptionsRepository {
    val developerModeEnabled: Flow<Boolean>
    suspend fun setDeveloperModeEnabled(enabled: Boolean)
    suspend fun getGithubPat(): String?
    suspend fun setGithubPat(pat: String?)
}
