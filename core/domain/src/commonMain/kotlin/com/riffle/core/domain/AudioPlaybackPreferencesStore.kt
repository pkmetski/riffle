package com.riffle.core.domain
import com.riffle.core.models.AudioIdentity

/**
 * Device-local, per-book audio playback settings (ADR 0028). Keyed by a resolved [AudioIdentity] so
 * a Readaloud and its linked audiobook share one record. A record exists only when the user has
 * overridden the default; absence means the global default. Never synced.
 */
interface AudioPlaybackPreferencesStore {
    /** The saved speed for [identity], or null when the user has not overridden the default. */
    suspend fun load(identity: AudioIdentity): Float?

    /** Persist [speed]; saving the default removes the record (absence == default). */
    suspend fun save(identity: AudioIdentity, speed: Float)

    /** Remove any saved settings for [identity]. */
    suspend fun clear(identity: AudioIdentity)

    /** Move the saved record from [old] to [new] (used when a link/unlink changes the identity). */
    suspend fun rekey(old: AudioIdentity, new: AudioIdentity)

    companion object {
        /** The fixed, non-configurable global default playback speed (ADR 0028). */
        const val DEFAULT_PLAYBACK_SPEED = 1.0f
    }
}
