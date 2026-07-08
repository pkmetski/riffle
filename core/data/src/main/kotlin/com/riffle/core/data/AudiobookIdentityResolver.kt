package com.riffle.core.data

import com.riffle.core.domain.AudiobookFingerprint
import com.riffle.core.domain.AudiobookIdentity
import com.riffle.core.domain.AudiobookIdentityResult

/**
 * Resolves the two fetched fingerprints into an [AudiobookIdentityResult] (ADR 0028). A fetch
 * failure resolves to [AudiobookIdentityResult.UNKNOWN] — never a false VERIFIED — so an offline
 * or flaky check can only ever keep a book on the (safe) bundle path. A successful fetch with no
 * audiobook attached resolves to [AudiobookIdentityResult.NO_AUDIOBOOK].
 */
object AudiobookIdentityResolver {
    fun resolve(
        storyteller: Result<AudiobookFingerprint?>,
        abs: Result<AudiobookFingerprint?>,
    ): AudiobookIdentityResult {
        val storytellerFp = storyteller.fingerprintOr { return it }
        val absFp = abs.fingerprintOr { return it }
        return if (AudiobookIdentity.matches(storytellerFp, absFp)) {
            AudiobookIdentityResult.VERIFIED
        } else {
            AudiobookIdentityResult.MISMATCH
        }
    }

    private inline fun Result<AudiobookFingerprint?>.fingerprintOr(
        onNonSuccess: (AudiobookIdentityResult) -> Nothing,
    ): AudiobookFingerprint = fold(
        onSuccess = { it ?: onNonSuccess(AudiobookIdentityResult.NO_AUDIOBOOK) },
        onFailure = { onNonSuccess(AudiobookIdentityResult.UNKNOWN) },
    )
}
