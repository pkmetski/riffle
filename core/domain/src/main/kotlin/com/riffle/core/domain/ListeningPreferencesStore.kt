package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow

interface ListeningPreferencesStore {

    /** Global default playback speed applied when no per-book override exists. */
    val defaultPlaybackSpeed: Flow<Float>

    /** Seconds the ⏪/⏩ skip buttons jump. */
    val skipIntervalSeconds: Flow<Int>

    /** Seconds to rewind when resuming after a pause or stop. */
    val rewindOnResumeSeconds: Flow<Int>

    suspend fun setDefaultPlaybackSpeed(speed: Float)
    suspend fun setSkipIntervalSeconds(seconds: Int)
    suspend fun setRewindOnResumeSeconds(seconds: Int)

    companion object {
        const val DEFAULT_PLAYBACK_SPEED = 1.0f
        const val DEFAULT_SKIP_INTERVAL_SECONDS = 30
        const val DEFAULT_REWIND_ON_RESUME_SECONDS = 0
    }
}
