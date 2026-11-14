package com.riffle.core.data

import com.riffle.core.domain.ListeningPreferencesStore
import com.riffle.core.domain.ListeningPreferencesStore.Companion.DEFAULT_PLAYBACK_SPEED
import com.riffle.core.domain.ListeningPreferencesStore.Companion.DEFAULT_REWIND_INTERVAL_SECONDS
import com.riffle.core.domain.ListeningPreferencesStore.Companion.DEFAULT_REWIND_ON_RESUME_SECONDS
import com.riffle.core.domain.ListeningPreferencesStore.Companion.DEFAULT_SKIP_INTERVAL_SECONDS
import kotlinx.coroutines.flow.Flow

class ListeningPreferencesStoreImpl(private val store: KeyValueStore) : ListeningPreferencesStore {

    override val defaultPlaybackSpeed: Flow<Float> =
        store.observe { store.getFloat(KEY_PLAYBACK_SPEED, DEFAULT_PLAYBACK_SPEED) }

    override val skipIntervalSeconds: Flow<Int> =
        store.observe { store.getInt(KEY_SKIP_INTERVAL, DEFAULT_SKIP_INTERVAL_SECONDS) }

    override val rewindIntervalSeconds: Flow<Int> =
        store.observe { store.getInt(KEY_REWIND_INTERVAL, DEFAULT_REWIND_INTERVAL_SECONDS) }

    override val rewindOnResumeSeconds: Flow<Int> =
        store.observe { store.getInt(KEY_REWIND_ON_RESUME, DEFAULT_REWIND_ON_RESUME_SECONDS) }

    override suspend fun setDefaultPlaybackSpeed(speed: Float) =
        store.setFloat(KEY_PLAYBACK_SPEED, speed)

    override suspend fun setSkipIntervalSeconds(seconds: Int) =
        store.setInt(KEY_SKIP_INTERVAL, seconds)

    override suspend fun setRewindIntervalSeconds(seconds: Int) =
        store.setInt(KEY_REWIND_INTERVAL, seconds)

    override suspend fun setRewindOnResumeSeconds(seconds: Int) =
        store.setInt(KEY_REWIND_ON_RESUME, seconds)

    private companion object {
        const val KEY_PLAYBACK_SPEED = "default_playback_speed"
        const val KEY_SKIP_INTERVAL = "skip_interval_seconds"
        const val KEY_REWIND_INTERVAL = "rewind_interval_seconds"
        const val KEY_REWIND_ON_RESUME = "rewind_on_resume_seconds"
    }
}
