package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow

sealed class LibraryRefreshResult {
    data object Success : LibraryRefreshResult()
    data class NetworkError(val cause: Throwable) : LibraryRefreshResult()
    data object NoActiveServer : LibraryRefreshResult()
}

interface LibraryRepository {
    fun observeLibraries(): Flow<List<Library>>
    fun observeLibraryItems(libraryId: String): Flow<List<LibraryItem>>
    suspend fun refreshLibraries(): LibraryRefreshResult
    suspend fun refreshLibraryItems(libraryId: String): LibraryRefreshResult
}
