package com.riffle.feature.reader

/**
 * Platform-neutral readaloud playback state emitted by [ReadaloudPlayerController].
 *
 * Mirrors the fields both Android's `ReadaloudController.PlaybackState` and iOS's
 * `IosReadaloudController.PlaybackState` expose so one [ReadaloudSession] can drive both hosts.
 */
data class ReadaloudPlaybackState(
    val isPlaying: Boolean = false,
    val speed: Float = 1f,
    val currentAudioSrc: String? = null,
    val positionSec: Double = 0.0,
    val positionGlobalSec: Double = 0.0,
    val currentChapterIndex: Int = -1,
    val chapterCount: Int = 0,
)
