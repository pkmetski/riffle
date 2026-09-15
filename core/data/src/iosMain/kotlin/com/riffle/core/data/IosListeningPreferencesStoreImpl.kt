package com.riffle.core.data

import com.riffle.core.domain.ListeningPreferencesStore
import com.riffle.core.domain.ListeningPreferencesStore.Companion.DEFAULT_PLAYBACK_SPEED
import com.riffle.core.domain.ListeningPreferencesStore.Companion.DEFAULT_REWIND_INTERVAL_SECONDS
import com.riffle.core.domain.ListeningPreferencesStore.Companion.DEFAULT_REWIND_ON_RESUME_SECONDS
import com.riffle.core.domain.ListeningPreferencesStore.Companion.DEFAULT_SKIP_INTERVAL_SECONDS
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import platform.Foundation.NSUserDefaults

internal class IosListeningPreferencesStoreImpl : ListeningPreferencesStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    private val _defaultPlaybackSpeed = MutableStateFlow(
        floatOrDefault(KEY_PLAYBACK_SPEED, DEFAULT_PLAYBACK_SPEED),
    )
    private val _skipIntervalSeconds = MutableStateFlow(
        intOrDefault(KEY_SKIP_INTERVAL, DEFAULT_SKIP_INTERVAL_SECONDS),
    )
    private val _rewindIntervalSeconds = MutableStateFlow(
        intOrDefault(KEY_REWIND_INTERVAL, DEFAULT_REWIND_INTERVAL_SECONDS),
    )
    private val _rewindOnResumeSeconds = MutableStateFlow(
        intOrDefault(KEY_REWIND_ON_RESUME, DEFAULT_REWIND_ON_RESUME_SECONDS),
    )

    override val defaultPlaybackSpeed: Flow<Float> = _defaultPlaybackSpeed
    override val skipIntervalSeconds: Flow<Int> = _skipIntervalSeconds
    override val rewindIntervalSeconds: Flow<Int> = _rewindIntervalSeconds
    override val rewindOnResumeSeconds: Flow<Int> = _rewindOnResumeSeconds

    override suspend fun setDefaultPlaybackSpeed(speed: Float) {
        defaults.setFloat(speed, forKey = KEY_PLAYBACK_SPEED)
        _defaultPlaybackSpeed.value = speed
    }

    override suspend fun setSkipIntervalSeconds(seconds: Int) {
        defaults.setInteger(seconds.toLong(), forKey = KEY_SKIP_INTERVAL)
        _skipIntervalSeconds.value = seconds
    }

    override suspend fun setRewindIntervalSeconds(seconds: Int) {
        defaults.setInteger(seconds.toLong(), forKey = KEY_REWIND_INTERVAL)
        _rewindIntervalSeconds.value = seconds
    }

    override suspend fun setRewindOnResumeSeconds(seconds: Int) {
        defaults.setInteger(seconds.toLong(), forKey = KEY_REWIND_ON_RESUME)
        _rewindOnResumeSeconds.value = seconds
    }

    private fun floatOrDefault(key: String, default: Float): Float =
        if (defaults.objectForKey(key) != null) defaults.floatForKey(key) else default

    private fun intOrDefault(key: String, default: Int): Int =
        if (defaults.objectForKey(key) != null) defaults.integerForKey(key).toInt() else default

    private companion object {
        const val KEY_PLAYBACK_SPEED = "listening.default_playback_speed"
        const val KEY_SKIP_INTERVAL = "listening.skip_interval_seconds"
        const val KEY_REWIND_INTERVAL = "listening.rewind_interval_seconds"
        const val KEY_REWIND_ON_RESUME = "listening.rewind_on_resume_seconds"
    }
}
