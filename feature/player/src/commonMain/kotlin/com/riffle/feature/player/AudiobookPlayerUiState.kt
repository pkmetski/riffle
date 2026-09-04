package com.riffle.feature.player

import com.riffle.core.domain.AudiobookChapter
import com.riffle.core.models.AudiobookBookmark

/** UI state for the full-screen Audiobook Player (ADR 0035). */
data class AudiobookPlayerUiState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val title: String = "",
    val author: String = "",
    val publishedYear: String? = null,
    val coverUrl: String? = null,
    val authToken: String = "",
    val isPlaying: Boolean = false,
    val speed: Float = 1f,
    val positionSec: Double = 0.0,
    val durationSec: Double = 0.0,
    // Book-absolute position up to which audio has buffered ahead of [positionSec]. Rendered as a
    // lighter fill on the seek bar, YouTube/Spotify-style.
    val bufferedPositionSec: Double = 0.0,
    val currentChapterTitle: String? = null,
    val chapterStartsSec: List<Double> = emptyList(),
    // The full chapter list + the index of the chapter the playhead is in, for the Chapters sheet.
    val chapters: List<AudiobookChapter> = emptyList(),
    val currentChapterIndex: Int = -1,
    val canPreviousChapter: Boolean = false,
    val canNextChapter: Boolean = false,
    // Book details for the landscape two-column player: raw facts plus the blurb (ADR 0035).
    val genres: List<String> = emptyList(),
    val facts: String? = null,
    val description: String? = null,
    // The linked readaloud EBOOK item id, when this title has one (split-library ebook, or this same
    // item if it's a combined ebook+audio). Non-null enables swipe-down → switch to the readaloud
    // reader; null means there's no readaloud to switch to, so no swipe-down.
    val readaloudEbookItemId: String? = null,
    // User bookmarks for this audiobook, observed live from the store (ordered by position, earliest first).
    val bookmarks: List<AudiobookBookmark> = emptyList(),
    // True only when this item has unsynced (dirty) bookmarks AND the device is offline, so the
    // Bookmarks sheet can show a quiet "Offline — bookmarks will sync" note. Sync is otherwise silent.
    val bookmarksOffline: Boolean = false,
    val sleepTimer: SleepTimerMode = SleepTimerMode.None,
    val skipIntervalSeconds: Int = 30,
    val rewindIntervalSeconds: Int = 15,
)
