package com.riffle.core.data

import com.riffle.core.domain.AudiobookFingerprint
import com.riffle.core.domain.AudiobookIdentity
import com.riffle.core.domain.AudiobookIdentityResult
import com.riffle.core.network.NetworkResult

/**
 * Resolves the two fetched fingerprints into an [AudiobookIdentityResult] (ADR 0028). A fetch
 * failure resolves to [AudiobookIdentityResult.UNKNOWN] — never a false VERIFIED — so an offline
 * or flaky check can only ever keep a book on the (safe) bundle path.
 */
object AudiobookIdentityResolver {
    fun resolve(
        storyteller: NetworkResult<AudiobookFingerprint?>,
        abs: NetworkResult<AudiobookFingerprint?>,
    ): AudiobookIdentityResult {
        val storytellerFp = storyteller.fingerprintOr { return it }
        val absFp = abs.fingerprintOr { return it }
        return if (AudiobookIdentity.matches(storytellerFp, absFp)) {
            AudiobookIdentityResult.VERIFIED
        } else {
            AudiobookIdentityResult.MISMATCH
        }
    }

    /** Either the fingerprint, or an early-return verdict for the non-success cases. */
    private inline fun NetworkResult<AudiobookFingerprint?>.fingerprintOr(
        onNonSuccess: (AudiobookIdentityResult) -> Nothing,
    ): AudiobookFingerprint = when (this) {
        is NetworkResult.Success -> value ?: onNonSuccess(AudiobookIdentityResult.NO_AUDIOBOOK)
        else -> onNonSuccess(AudiobookIdentityResult.UNKNOWN)
    }
}
