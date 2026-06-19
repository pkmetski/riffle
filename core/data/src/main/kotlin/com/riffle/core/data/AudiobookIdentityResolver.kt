package com.riffle.core.data

import com.riffle.core.domain.AudiobookFingerprint
import com.riffle.core.domain.AudiobookIdentity
import com.riffle.core.domain.AudiobookIdentityResult
import com.riffle.core.network.NetworkAudiobookFingerprintResult

/**
 * Resolves the two fetched fingerprints into an [AudiobookIdentityResult] (ADR 0028). A fetch
 * failure resolves to [AudiobookIdentityResult.UNKNOWN] — never a false VERIFIED — so an offline
 * or flaky check can only ever keep a book on the (safe) bundle path.
 */
object AudiobookIdentityResolver {
    fun resolve(
        storyteller: NetworkAudiobookFingerprintResult,
        abs: NetworkAudiobookFingerprintResult,
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
    private inline fun NetworkAudiobookFingerprintResult.fingerprintOr(
        onNonSuccess: (AudiobookIdentityResult) -> Nothing,
    ): AudiobookFingerprint = when (this) {
        is NetworkAudiobookFingerprintResult.Success -> fingerprint
        NetworkAudiobookFingerprintResult.NoAudiobook -> onNonSuccess(AudiobookIdentityResult.NO_AUDIOBOOK)
        is NetworkAudiobookFingerprintResult.NetworkError -> onNonSuccess(AudiobookIdentityResult.UNKNOWN)
    }
}
