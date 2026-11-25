package com.riffle.core.data

/**
 * Fetches the Readaloud sidecar (SMIL + chapter text, ADR 0040) from the Storyteller `/synced`
 * bundle — the `/synced` bundle minus its audio, repackaged as a small audio-free EPUB.
 *
 * Platform backends: [com.riffle.core.data] Android streams the non-audio prefix with a
 * full-download fallback; iOS downloads the whole bundle and keeps the non-audio entries.
 */
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
