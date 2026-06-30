package com.riffle.core.network

import com.riffle.core.domain.AudiobookFingerprint

fun interface StorytellerApi {
    suspend fun login(
        baseUrl: String,
        username: String,
        password: String,
        insecureAllowed: Boolean,
    ): NetworkResult<String>
}

interface StorytellerLibraryApi {
    /** `true` = valid token, `false` = explicitly invalid (401/403). Other failures bubble up. */
    suspend fun validateToken(
        baseUrl: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<Boolean>

    suspend fun listReadalouds(
        baseUrl: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<List<NetworkStorytellerBook>>

    /** A 404 surfaces as `ServerError(404)` — callers detect that to mean "book gone". */
    suspend fun getBook(
        baseUrl: String,
        bookId: Long,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<NetworkStorytellerBook>

    fun coverUrl(baseUrl: String, bookId: Long): String

    /**
     * The ingested-source audiobook fingerprint from `/api/v2/books/{id}` (ADR 0028). Success(null)
     * means the source carries no audiobook. Default returns Unknown so existing fakes need no change.
     */
    suspend fun getAudiobookFingerprint(
        baseUrl: String,
        bookId: Long,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<AudiobookFingerprint?> = NetworkResult.Unknown(NotImplementedError("getAudiobookFingerprint"))
}
