package com.riffle.core.network

interface AbsLibraryApi {
    suspend fun getLibraries(
        baseUrl: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkLibrariesResult

    suspend fun getLibraryItems(
        baseUrl: String,
        libraryId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkLibraryItemsResult

    suspend fun getSeries(
        baseUrl: String,
        libraryId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkSeriesResult

    suspend fun getCollections(
        baseUrl: String,
        libraryId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkCollectionResult
}
