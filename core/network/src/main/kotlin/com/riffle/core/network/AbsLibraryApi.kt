package com.riffle.core.network

sealed class NetworkUserProgressResult {
    data class Success(val progressByItemId: Map<String, Float>) : NetworkUserProgressResult()
    data class NetworkError(val cause: Throwable) : NetworkUserProgressResult()
}

interface AbsLibraryApi {
    suspend fun getUserProgress(
        baseUrl: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkUserProgressResult = NetworkUserProgressResult.Success(emptyMap())

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

    /**
     * POST /api/collections. Creates a Collection in [libraryId] named [name]. If [initialBookId]
     * is non-null it is included as the collection's first (and only) book. The ABS endpoint
     * accepts a list, but the only caller — `ToReadRepository` — adds one book at a time.
     */
    suspend fun createCollection(
        baseUrl: String,
        libraryId: String,
        name: String,
        initialBookId: String?,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkCollectionWriteResult = throw UnsupportedOperationException("createCollection not implemented")

    suspend fun addBookToCollection(
        baseUrl: String,
        collectionId: String,
        libraryItemId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkCollectionWriteResult = throw UnsupportedOperationException("addBookToCollection not implemented")

    suspend fun removeBookFromCollection(
        baseUrl: String,
        collectionId: String,
        libraryItemId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkCollectionWriteResult = throw UnsupportedOperationException("removeBookFromCollection not implemented")

    suspend fun getPlaylists(
        baseUrl: String,
        libraryId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkPlaylistResult = throw UnsupportedOperationException("getPlaylists not implemented")

    suspend fun createPlaylist(
        baseUrl: String,
        libraryId: String,
        name: String,
        initialBookId: String?,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkPlaylistWriteResult = throw UnsupportedOperationException("createPlaylist not implemented")

    suspend fun addBookToPlaylist(
        baseUrl: String,
        playlistId: String,
        libraryItemId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkPlaylistWriteResult = throw UnsupportedOperationException("addBookToPlaylist not implemented")

    suspend fun removeBookFromPlaylist(
        baseUrl: String,
        playlistId: String,
        libraryItemId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkPlaylistWriteResult = throw UnsupportedOperationException("removeBookFromPlaylist not implemented")

    suspend fun getItemEbookFileIno(
        baseUrl: String,
        itemId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkItemEbookInoResult = throw UnsupportedOperationException("getItemEbookFileIno not implemented")

    suspend fun downloadEpub(
        baseUrl: String,
        itemId: String,
        fileIno: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkEpubDownloadResult = throw UnsupportedOperationException("downloadEpub not implemented")
}
