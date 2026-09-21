package com.riffle.app.feature.audiobook

import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riffle.app.R
import com.riffle.feature.player.AudiobookPlayerEvent
import com.riffle.feature.player.AudiobookPlayerViewModel
import com.riffle.feature.player.CompactDurationLabelTemplates
import com.riffle.feature.player.ui.AudiobookPlayerBody
import com.riffle.feature.player.ui.PlayerChromeLabels
import org.koin.androidx.compose.koinViewModel

/**
 * Android's host for the full-screen [Audiobook Player] (ADR 0035). The chrome itself —
 * cover, scrubber, transport, speed, sleep timer, chapters/bookmarks sheets, the corner ribbon and
 * the swipe-down readaloud handoff — is [AudiobookPlayerBody] in `:feature:player-ui`, rendered by
 * iOS from `IosAudiobookPlayerScreen` as well. This file supplies only what is genuinely
 * Android-specific: the Koin ViewModel, the `res/values*` string catalogue and the `WindowSizeClass`
 * the two-column decision is taken from.
 */
@Composable
fun AudiobookPlayerScreen(
    windowSizeClass: WindowSizeClass,
    onNavigateBack: () -> Unit,
    onSwitchToReadaloud: (ebookItemId: String, atSec: Double) -> Unit = { _, _ -> },
    /** Called on end-of-book when the player was opened inside a playlist context (via
     *  [PlaylistDetailScreen]) and there IS a next item. Callers navigate to the next item's
     *  audiobook player, preserving the playlist context so auto-advance chains through. */
    onPlaylistAdvance: (sourceId: String, nextItemId: String) -> Unit = { _, _ -> },
    viewModel: AudiobookPlayerViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // When the book ends naturally (last track played through to STATE_ENDED), either advance to
    // the next playlist item (if opened inside a playlist context with a successor) or close the
    // player. The VM's onCleared() stops the MediaSession, which clears the foreground notification.
    val latestOnNavigateBack = rememberUpdatedState(onNavigateBack)
    val latestOnPlaylistAdvance = rememberUpdatedState(onPlaylistAdvance)
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                AudiobookPlayerEvent.Finished -> latestOnNavigateBack.value()
                is AudiobookPlayerEvent.PlaylistAdvance -> latestOnPlaylistAdvance.value(event.sourceId, event.nextItemId)
            }
        }
    }
    // Any short (Compact-height) window — i.e. a phone in landscape — is too short for the vertical
    // layout (the square cover pushes the controls off-screen), so split into cover+details / controls.
    val twoColumn = windowSizeClass.heightSizeClass == WindowHeightSizeClass.Compact

    AudiobookPlayerBody(
        viewModel = viewModel,
        state = state,
        labels = androidPlayerChromeLabels(),
        onNavigateBack = onNavigateBack,
        twoColumn = twoColumn,
        onSwitchToReadaloud = onSwitchToReadaloud,
    )
}

/**
 * The player's string catalogue.
 *
 * Starts from [PlayerChromeLabels.English] and overrides every entry Android actually has a
 * `res/values*` string for, so bg/es keep working exactly as before and the handful of strings
 * Android had inlined as Kotlin literals ("Play", "Sleep", "1 bookmark", …) are not written out a
 * second time here. `stringResource(id)` with no arguments deliberately returns the RAW template
 * for the three positional entries — the shared chrome substitutes the live countdown, the preset
 * minutes and the bookmark count itself.
 */
@Composable
private fun androidPlayerChromeLabels(): PlayerChromeLabels = PlayerChromeLabels.English.copy(
    back = stringResource(R.string.ui_back),
    cannotPlay = stringResource(R.string.ui_this_audiobook_can_t_be_played_right_now),
    previousChapter = stringResource(R.string.ui_previous_chapter),
    nextChapter = stringResource(R.string.ui_next_chapter),
    chapters = stringResource(R.string.ui_chapters),
    renameBookmark = stringResource(R.string.ui_rename_bookmark),
    bookmarkOptions = stringResource(R.string.ui_bookmark_options),
    rename = stringResource(R.string.ui_rename),
    delete = stringResource(R.string.ui_delete),
    save = stringResource(R.string.ui_save),
    cancel = stringResource(R.string.ui_cancel),
    noBookmarksYet = stringResource(R.string.ui_no_bookmarks_yet),
    offlineBookmarksWillSync = stringResource(R.string.ui_offline_bookmarks_will_sync),
    nowPlaying = stringResource(R.string.ui_now_playing),
    playbackSpeed = stringResource(R.string.ui_playback_speed),
    sleepTimerSheetTitle = stringResource(R.string.ui_sleep_timer_3),
    sleepTimer = stringResource(R.string.ui_sleep_timer),
    endOfChapter = stringResource(R.string.ui_end_of_chapter),
    sleepingIn = stringResource(R.string.ui_sleeping_in),
    sleepingAtEndOfChapter = stringResource(R.string.ui_sleeping_at_end_of_chapter),
    cancelTimer = stringResource(R.string.ui_cancel_timer),
    minutesShort = stringResource(R.string.ui_minutes_short),
    audiobook = stringResource(R.string.ui_audiobook),
    compactDuration = CompactDurationLabelTemplates(
        minutes = stringResource(R.string.ui_duration_minutes_short),
        hours = stringResource(R.string.ui_duration_hours_short),
        hoursMinutes = stringResource(R.string.ui_duration_hours_minutes_short),
    ),
)
