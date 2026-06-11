package com.riffle.app.playback

import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single playback session currently backed by [com.riffle.app.feature.reader.readaloud.AudioPlayerService].
 * Only one can be active at a time (one shared player/session), so [NowPlayingStore] holds at most one.
 */
sealed interface NowPlaying {
    val itemId: String

    /** A full-screen ABS audiobook, opened at `audiobook_player/{itemId}`. */
    data class Audiobook(override val itemId: String) : NowPlaying

    /** A readaloud session, which lives inside the EPUB reader at `epub_reader/{itemId}`. */
    data class Readaloud(override val itemId: String) : NowPlaying
}

/**
 * App-scoped record of what is playing right now, so a media-notification tap can route to the
 * matching player view. In-memory only: while a media notification exists to tap, the foreground
 * [com.riffle.app.feature.reader.readaloud.AudioPlayerService] keeps the process alive, so this
 * singleton outlives any [androidx.activity.ComponentActivity] recreation without needing to be
 * persisted across process death (when there would be no notification to tap anyway).
 */
@Singleton
class NowPlayingStore @Inject constructor() {

    @Volatile
    var current: NowPlaying? = null
        private set

    fun set(value: NowPlaying) {
        current = value
    }

    /**
     * Clears the current session only when it matches [predicate]. Each player screen clears its own
     * session on teardown; the guard stops one screen's teardown from wiping another's entry.
     */
    fun clearIf(predicate: (NowPlaying) -> Boolean) {
        if (current?.let(predicate) == true) current = null
    }
}
