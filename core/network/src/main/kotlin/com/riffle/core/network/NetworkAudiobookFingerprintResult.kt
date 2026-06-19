package com.riffle.core.network

import com.riffle.core.domain.AudiobookFingerprint

/** Result of fetching an audiobook's identity fingerprint from either backend (ADR 0028). */
sealed class NetworkAudiobookFingerprintResult {
    data class Success(val fingerprint: AudiobookFingerprint) : NetworkAudiobookFingerprintResult()
    object NoAudiobook : NetworkAudiobookFingerprintResult()
    data class NetworkError(val cause: Throwable) : NetworkAudiobookFingerprintResult()
}
