package com.riffle.core.network

import com.riffle.core.domain.AudiobookFingerprint
import com.riffle.core.network.model.AbsItemDetailResponse
import okhttp3.ResponseBody

data class NetworkUserMediaProgress(
    val ebookProgress: Float?,
    val lastUpdate: Long?,
    val finishedAt: Long? = null,
)

interface AbsLibraryApi {
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

    suspend fun downloadEpub(
        baseUrl: String,
        itemId: String,
        fileIno: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<ResponseBody> = throw UnsupportedOperationException("downloadEpub not implemented")

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
     * The ABS audiobook's identity fingerprint for the streaming check (ADR 0028). Success(null)
     * means the item carries no audiobook (the old `NoAudiobook` variant).
     */
    suspend fun getAudiobookFingerprint(
        baseUrl: String,
        itemId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<AudiobookFingerprint?> = NetworkResult.Unknown(NotImplementedError("getAudiobookFingerprint"))

    /**
     * The ABS audiobook's streamable tracks (ino + duration) for streaming playback (ADR 0028).
     * Success with an empty list means the item carries no audiobook (the old `NoAudiobook` variant).
     */
    suspend fun getAudiobookTracks(
        baseUrl: String,
        itemId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<List<NetworkAbsAudioTrack>> = NetworkResult.Unknown(NotImplementedError("getAudiobookTracks"))
}
