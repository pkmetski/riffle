package com.riffle.core.network

import com.riffle.core.models.AudiobookFingerprint
import com.riffle.core.network.model.AbsItemDetailResponse

data class NetworkUserMediaProgress(
    val ebookProgress: Float?,
    val lastUpdate: Long?,
    val finishedAt: Long? = null,
    val currentTime: Double = 0.0,
    val duration: Double = 0.0,
    val isFinished: Boolean = false,
)

interface AbsLibraryApi {
    suspend fun uploadBook(
        baseUrl: String,
        libraryId: String,
        metadata: NetworkUploadMetadata,
        files: List<NetworkUploadPart>,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<Unit> = throw UnsupportedOperationException("uploadBook not implemented")

    suspend fun updateItemMedia(
        baseUrl: String,
        itemId: String,
        metadata: NetworkAbsMetadataUpdate,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<Unit> = throw UnsupportedOperationException("updateItemMedia not implemented")

    suspend fun updateItemChapters(
        baseUrl: String,
        itemId: String,
        chapters: List<NetworkAbsChapterUpdate>,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<Unit> = throw UnsupportedOperationException("updateItemChapters not implemented")

    suspend fun uploadItemCoverFromUrl(
        baseUrl: String,
        itemId: String,
        url: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<Unit> = throw UnsupportedOperationException("uploadItemCoverFromUrl not implemented")

    suspend fun getUserProgress(
        baseUrl: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<Map<String, NetworkUserMediaProgress>> = NetworkResult.Success(emptyMap())

    suspend fun getLibraries(
        baseUrl: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<List<NetworkLibrary>>

    suspend fun getLibraryItems(
        baseUrl: String,
        libraryId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<List<NetworkLibraryItem>>

    /** Returns the newest items first, for recovering IDs after an asynchronous upload. */
    suspend fun getRecentlyAddedLibraryItems(
        baseUrl: String,
        libraryId: String,
        limit: Int,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<List<NetworkLibraryItem>> =
        getLibraryItems(baseUrl, libraryId, token, insecureAllowed)

    /** Starts ABS's background watcher/scan after files have been moved into the library folder. */
    suspend fun scanLibrary(
        baseUrl: String,
        libraryId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<Unit> = NetworkResult.Success(Unit)

    /**
     * `GET /api/libraries/:libraryId/search?q=`. Returns the `book` group of ABS's grouped
     * search response — podcasts, tags, authors, and series-with-books are dropped, since a
     * Catalog exposes items only.
     */
    suspend fun searchLibrary(
        baseUrl: String,
        libraryId: String,
        query: String,
        limit: Int,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<List<NetworkLibraryItem>> = throw UnsupportedOperationException("searchLibrary not implemented")

    suspend fun getSeries(
        baseUrl: String,
        libraryId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<List<NetworkSeries>>

    suspend fun getCollections(
        baseUrl: String,
        libraryId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<List<NetworkCollection>>

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
    ): NetworkResult<NetworkCollection?> = throw UnsupportedOperationException("createCollection not implemented")

    suspend fun addBookToCollection(
        baseUrl: String,
        collectionId: String,
        libraryItemId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<NetworkCollection?> = throw UnsupportedOperationException("addBookToCollection not implemented")

    suspend fun removeBookFromCollection(
        baseUrl: String,
        collectionId: String,
        libraryItemId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<NetworkCollection?> = throw UnsupportedOperationException("removeBookFromCollection not implemented")

    suspend fun getPlaylists(
        baseUrl: String,
        libraryId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<List<NetworkPlaylist>> = throw UnsupportedOperationException("getPlaylists not implemented")

    suspend fun createPlaylist(
        baseUrl: String,
        libraryId: String,
        name: String,
        initialBookId: String?,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<NetworkPlaylist?> = throw UnsupportedOperationException("createPlaylist not implemented")

    suspend fun addBookToPlaylist(
        baseUrl: String,
        playlistId: String,
        libraryItemId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<NetworkPlaylist?> = throw UnsupportedOperationException("addBookToPlaylist not implemented")

    suspend fun removeBookFromPlaylist(
        baseUrl: String,
        playlistId: String,
        libraryItemId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<NetworkPlaylist?> = throw UnsupportedOperationException("removeBookFromPlaylist not implemented")

    suspend fun getItemEbookFileIno(
        baseUrl: String,
        itemId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<String> = throw UnsupportedOperationException("getItemEbookFileIno not implemented")

    suspend fun getItemDetail(
        baseUrl: String,
        itemId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<AbsItemDetailResponse> = throw UnsupportedOperationException("getItemDetail not implemented")

    /**
     * `GET /api/items/:itemId?expanded=1`. Returns the full library-item envelope for a single
     * item; `null` when ABS responds 404 (item no longer exists). Distinct from [getItemDetail]
     * which only carries chapter markers.
     */
    suspend fun getItem(
        baseUrl: String,
        itemId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<NetworkLibraryItem?> = throw UnsupportedOperationException("getItem not implemented")

    /**
     * The ABS audiobook's identity fingerprint for the streaming check (ADR 0040). Success(null)
     * means the item carries no audiobook (the old `NoAudiobook` variant).
     */
    suspend fun getAudiobookFingerprint(
        baseUrl: String,
        itemId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<AudiobookFingerprint?> = NetworkResult.Unknown(NotImplementedError("getAudiobookFingerprint"))

    /**
     * The ABS audiobook's streamable tracks (ino + duration) for streaming playback (ADR 0040).
     * Success with an empty list means the item carries no audiobook (the old `NoAudiobook` variant).
     */
    suspend fun getAudiobookTracks(
        baseUrl: String,
        itemId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<List<NetworkAbsAudioTrack>> = NetworkResult.Unknown(NotImplementedError("getAudiobookTracks"))
}
