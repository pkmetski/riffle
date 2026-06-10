package com.riffle.core.network

fun interface StorytellerApi {
    suspend fun login(
        baseUrl: String,
        username: String,
        password: String,
        insecureAllowed: Boolean,
    ): NetworkStorytellerLoginResult
}

interface StorytellerLibraryApi {
    suspend fun validateToken(
        baseUrl: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkStorytellerValidateResult

    suspend fun listReadalouds(
        baseUrl: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkStorytellerBooksResult

    suspend fun getBook(
        baseUrl: String,
        bookId: Long,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkStorytellerBookResult

    fun coverUrl(baseUrl: String, bookId: Long): String

    /**
     * The ingested-source audiobook fingerprint from `/api/v2/books/{id}` (ADR 0028). Default
     * returns an error so existing fakes need no change; the real client overrides it.
     */
    suspend fun getAudiobookFingerprint(
        baseUrl: String,
        bookId: Long,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkAudiobookFingerprintResult =
        NetworkAudiobookFingerprintResult.NetworkError(NotImplementedError("getAudiobookFingerprint"))
}
