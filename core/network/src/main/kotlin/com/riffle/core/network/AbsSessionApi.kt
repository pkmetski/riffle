package com.riffle.core.network

data class NetworkEbookProgressPayload(
    val ebookLocation: String,
    val ebookProgress: Float,
    // When non-null, also flips ABS's item-level `isFinished` flag in the same PATCH. This is the
    // only field that touches the AUDIO dimension (`currentTime`/`progress`) of the shared
    // media-progress record — `true` sets `progress`=1, `false` zeroes `currentTime`+`progress`.
    // Left null for ordinary reader position saves so the finished state is untouched.
    val isFinished: Boolean? = null,
)

data class NetworkAudiobookProgressPayload(
    val currentTime: Double,
    val duration: Double,
)

data class NetworkServerProgress(
    val ebookLocation: String,
    val ebookProgress: Float = 0f,
    // Audiobook progress from the same ABS media-progress record.
    val currentTime: Double = 0.0,
    val duration: Double = 0.0,
    val lastUpdate: Long,
)

interface AbsSessionApi {
    /** PATCH the ebook dimension of the shared media-progress record. Returns the new `lastUpdate`. */
    suspend fun syncEbookProgress(
        baseUrl: String,
        libraryItemId: String,
        payload: NetworkEbookProgressPayload,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<Long>

    /** PATCH the audiobook dimension of the shared media-progress record. Returns the new `lastUpdate`. */
    suspend fun syncAudiobookProgress(
        baseUrl: String,
        libraryItemId: String,
        payload: NetworkAudiobookProgressPayload,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<Long>

    /** GET the shared media-progress record. A 404 is mapped to an empty record at `lastUpdate=0`. */
    suspend fun getProgress(
        baseUrl: String,
        libraryItemId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<NetworkServerProgress>
}
