package com.riffle.core.data

// Fetches the Readaloud sidecar (SMIL + chapter text, ADR 0040) as a small audio-free EPUB; platform
// backends differ in how they obtain it (Android streams the prefix, iOS downloads the whole bundle).
interface StorytellerSidecarFetcher {
    sealed interface FetchResult {
        /** Sidecar bytes ready to write to disk. */
        data class Success(val bytes: ByteArray) : FetchResult {
            override fun equals(other: Any?): Boolean =
                this === other || (other is Success && bytes.contentEquals(other.bytes))

            override fun hashCode(): Int = bytes.contentHashCode()
        }

        /** Transient transport failure — caller should retry. */
        data object NetworkError : FetchResult

        /** Bundle has no SMIL (Storyteller not yet aligned) — no point retrying until alignment. */
        data object NotAligned : FetchResult
    }

    suspend fun fetch(
        baseUrl: String,
        bookId: String,
        token: String,
        insecureAllowed: Boolean,
    ): FetchResult
}
