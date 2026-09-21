package com.riffle.feature.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.riffle.core.models.AudiobookBookmark
import com.riffle.feature.player.AudiobookPlayerUiState
import com.riffle.feature.player.AudiobookPlayerViewModel
import com.riffle.feature.player.buildAudiobookFacts
import com.riffle.feature.player.formatHms
import com.riffle.feature.source.ui.CornerBookmarkIndicator
import kotlin.math.abs

/**
 * Minimum downward drag (px) on the player to trigger the switch to the readaloud reader — a
 * deliberate swipe, not an accidental nudge.
 */
private const val SWITCH_TO_READALOUD_THRESHOLD_PX = 160f

/** How close the playhead must be to a bookmark for the corner ribbon to read as "on" it. */
const val BOOKMARK_WINDOW_SEC = 3.0

/** Which list the shared [PlayerListSheet] is showing (no tabs — one kind at a time). */
private enum class SheetKind { Chapters, Bookmarks }

/**
 * A snapshot taken when the New-bookmark dialog opens. Pinning the position (and the title/label
 * derived from it) keeps the dialog stable while playback continues — otherwise the live playhead
 * would rewrite the default title every second and wipe the user's edits.
 */
private data class BookmarkDraft(
    val positionSec: Double,
    val defaultTitle: String,
    val chapterTitle: String,
)

/**
 * Whether the swipe-down readaloud handoff should exist at all: the book must have a linked
 * readaloud ebook AND the host must have somewhere to navigate to.
 *
 * Both halves matter. The gesture calls [AudiobookPlayerViewModel.prepareReadaloudHandoff], which
 * stops the follow loop and releases the player — arming it on a host that cannot navigate would
 * silently pause the book on a downward swipe and leave the user on the same dead screen.
 */
fun readaloudHandoffArmed(readaloudEbookItemId: String?, hostCanNavigate: Boolean): Boolean =
    hostCanNavigate && readaloudEbookItemId != null

/** The bookmark nearest [positionSec] within [BOOKMARK_WINDOW_SEC], or null when there is none. */
fun bookmarkNear(bookmarks: List<AudiobookBookmark>, positionSec: Double): AudiobookBookmark? =
    bookmarks
        .filter { abs(it.positionSec - positionSec) <= BOOKMARK_WINDOW_SEC }
        .minByOrNull { abs(it.positionSec - positionSec) }

/**
 * The full-screen [Audiobook Player] (ADR 0035), rendered by BOTH hosts.
 *
 * `:app` wraps it in its NavGraph destination and `:shared` in `IosAudiobookPlayerScreen`; the only
 * things either supplies are the localized [labels], the [twoColumn] decision (Android has a real
 * `WindowSizeClass`, iOS uses [isCompactPlayerHeight]) and the navigation callbacks. Everything the
 * user can see or touch — the cover, the scrubber, the transport, speed, sleep timer, chapters,
 * bookmarks, the corner ribbon and the swipe-down handoff — lives here so the two platforms cannot
 * drift.
 */
@Composable
fun AudiobookPlayerBody(
    viewModel: AudiobookPlayerViewModel,
    state: AudiobookPlayerUiState,
    labels: PlayerChromeLabels,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    twoColumn: Boolean = false,
    /**
     * Swipe-down handoff target. Called with the linked readaloud ebook item id and the current
     * audio position once the drag passes [SWITCH_TO_READALOUD_THRESHOLD_PX].
     *
     * **Null means the host has no readaloud reader to hand off to**, and then neither the drag
     * handle nor the gesture exists at all. That is not cosmetic: the gesture calls
     * [AudiobookPlayerViewModel.prepareReadaloudHandoff], which stops the follow loop and releases
     * the player, so arming it against a host that cannot navigate anywhere would silently pause
     * the book on a downward swipe. iOS passes null until its readaloud reader lands (#1072 §2).
     */
    onSwitchToReadaloud: ((ebookItemId: String, atSec: Double) -> Unit)? = null,
) {
    // Read fresh inside the gesture (it's keyed on Unit, so it must not capture a stale position).
    val latestState = rememberUpdatedState(state)

    // Local UI state for the sheets and dialogs.
    var openSheet by remember { mutableStateOf<SheetKind?>(null) }
    // Non-null while the New-bookmark dialog is open; carries the position/title pinned at open time
    // so they don't drift with the still-running playhead while the user edits (see [BookmarkDraft]).
    var createDraft by remember { mutableStateOf<BookmarkDraft?>(null) }
    var renaming by remember { mutableStateOf<AudiobookBookmark?>(null) }

    val handoffTarget = onSwitchToReadaloud
        ?.takeIf { readaloudHandoffArmed(state.readaloudEbookItemId, hostCanNavigate = true) }
    val latestHandoffTarget = rememberUpdatedState(handoffTarget)

    val gradient = Brush.verticalGradient(
        listOf(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.background,
        ),
    )
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(gradient)
                    // Swipe DOWN anywhere → switch to the readaloud reader (only when this title has
                    // a linked readaloud ebook; otherwise the drag does nothing). Down = toward
                    // reading. The scrubber's own horizontal drag is unaffected; taps still reach the
                    // transport.
                    .pointerInput(handoffTarget != null) {
                        if (latestHandoffTarget.value == null) return@pointerInput
                        var total = 0f
                        detectVerticalDragGestures(
                            // Pre-warm readaloud the moment a downward drag starts (ADR 0039):
                            // resolves the SMIL seek target while the user is still dragging so
                            // playFromSecond() fires instantly when the threshold is reached.
                            onDragStart = {
                                total = 0f
                                viewModel.hintReadaloudHandoff()
                            },
                            onVerticalDrag = { change, dragAmount ->
                                total += dragAmount
                                change.consume()
                            },
                            onDragEnd = {
                                val s = latestState.value
                                val ebookId = s.readaloudEbookItemId
                                val handoff = latestHandoffTarget.value
                                if (total > SWITCH_TO_READALOUD_THRESHOLD_PX && ebookId != null && handoff != null) {
                                    // Release the shared player to readaloud (without stopping it)
                                    // before navigating, so readaloud keeps playing through the
                                    // handoff.
                                    viewModel.prepareReadaloudHandoff()
                                    handoff(ebookId, s.positionSec)
                                } else {
                                    viewModel.cancelHandoffHint()
                                }
                            },
                        )
                    }
                    .padding(horizontal = 24.dp),
            ) {
                // Leave room for the back button overlaid above, plus the read-along handle when present.
                Spacer(Modifier.size(48.dp))
                if (handoffTarget != null) {
                    ReadAlongDragHandle()
                }

                when {
                    state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                    state.failed -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text(
                            labels.cannotPlay,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("player_failed"),
                        )
                    }
                    // navigationBarsPadding so the pills row at the bottom clears the system nav bar
                    // — otherwise PlayerListPills renders behind it and the chapters/bookmarks entry
                    // point is invisible.
                    else -> Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                        PlayerSurface(
                            modifier = Modifier.weight(1f),
                            state = PlayerSurfaceState(
                                title = state.title,
                                author = state.author,
                                publishedYear = state.publishedYear,
                                coverUrl = state.coverUrl,
                                authToken = state.authToken,
                                isPlaying = state.isPlaying,
                                speed = state.speed,
                                positionSec = state.positionSec,
                                durationSec = state.durationSec,
                                bufferedPositionSec = state.bufferedPositionSec,
                                currentChapterTitle = state.currentChapterTitle,
                                chapterStartsSec = state.chapterStartsSec,
                                bookmarkPositionsSec = state.bookmarks.map { it.positionSec },
                                canPreviousChapter = state.canPreviousChapter,
                                canNextChapter = state.canNextChapter,
                                // Rebuilt here from the host's catalogue rather than taken from
                                // `state.facts` (which the ViewModel builds with English defaults),
                                // so Android's bg/es translations keep reaching the landscape line.
                                facts = buildAudiobookFacts(
                                    durationSec = state.durationSec,
                                    genres = state.genres,
                                    audiobookLabel = labels.audiobook,
                                    durationLabels = labels.compactDuration,
                                ),
                                description = state.description,
                                sleepTimer = state.sleepTimer,
                                skipIntervalSeconds = state.skipIntervalSeconds,
                                rewindIntervalSeconds = state.rewindIntervalSeconds,
                            ),
                            twoColumn = twoColumn,
                            labels = labels,
                            actions = PlayerSurfaceActions(
                                onSeek = viewModel::seekTo,
                                onTogglePlayPause = viewModel::togglePlayPause,
                                onRewind = viewModel::rewind,
                                onForward = viewModel::forward,
                                onPreviousChapter = viewModel::previousChapter,
                                onNextChapter = viewModel::nextChapter,
                                onSpeedChange = viewModel::setSpeed,
                                onSleepTimerSet = viewModel::setSleepTimer,
                                onSleepTimerCancel = viewModel::cancelSleepTimer,
                            ),
                        )
                        PlayerListPills(
                            bookmarkCount = state.bookmarks.size,
                            labels = labels,
                            onOpenChapters = { openSheet = SheetKind.Chapters },
                            onOpenBookmarks = { openSheet = SheetKind.Bookmarks },
                        )
                        Spacer(Modifier.size(12.dp))
                    }
                }
            }
            // Exit affordance, overlaid OUTSIDE the swipe Column so its taps are never captured by the
            // swipe-down gesture. A plain back arrow (not a down-chevron) — distinct from swipe=read.
            IconButton(
                // safeDrawingPadding (not just statusBarsPadding) so the arrow clears the status bar,
                // nav bar AND any display cutout — in landscape the cutout/short status bar otherwise
                // sits right under it.
                onClick = onNavigateBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .safeDrawingPadding()
                    .padding(4.dp)
                    .testTag("player_back"),
            ) {
                Icon(PlayerGlyphs.ArrowBack, contentDescription = labels.back)
            }
            // Corner bookmark ribbon — same shape/placement as the ebook reader. Filled when the
            // playhead is within ±3 s of an existing bookmark, or while the add-bookmark dialog is open.
            val nearBookmark = bookmarkNear(state.bookmarks, state.positionSec) != null
            CornerBookmarkIndicator(
                isBookmarked = createDraft != null || nearBookmark,
                isVisible = true,
                onToggle = {
                    val positionSec = viewModel.currentPositionSec()
                    val nearby = bookmarkNear(state.bookmarks, positionSec)
                    if (nearby != null) {
                        viewModel.deleteBookmark(nearby.id)
                    } else {
                        createDraft = BookmarkDraft(
                            positionSec = positionSec,
                            defaultTitle = viewModel.defaultBookmarkTitle(positionSec),
                            chapterTitle = state.currentChapterTitle?.trim().orEmpty(),
                        )
                    }
                },
                contentDescription = if (nearBookmark && createDraft == null) labels.removeBookmark else labels.addBookmark,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .safeDrawingPadding()
                    .padding(end = 12.dp)
                    .testTag("player_bookmark_ribbon"),
            )
        }
    }

    // Create dialog: pre-fill the default title and offer quick suggestions, all from the pinned draft.
    createDraft?.let { draft ->
        val absolute = formatHms(draft.positionSec)
        val suggestions = listOf(draft.defaultTitle, draft.chapterTitle, absolute)
            .filter { it.isNotBlank() }
            .distinct()
        BookmarkCreateDialog(
            initialTitle = draft.defaultTitle,
            positionLabel = bookmarkPositionLabel(absolute, draft.chapterTitle),
            suggestions = suggestions,
            labels = labels,
            onConfirm = { title ->
                viewModel.addBookmark(title, draft.positionSec)
                createDraft = null
            },
            onDismiss = { createDraft = null },
        )
    }

    // Rename reuses the create dialog with a different title and no suggestions.
    renaming?.let { bookmark ->
        BookmarkCreateDialog(
            initialTitle = bookmark.title,
            positionLabel = formatHms(bookmark.positionSec),
            suggestions = emptyList(),
            labels = labels,
            title = labels.renameBookmark,
            onConfirm = { title ->
                viewModel.renameBookmark(bookmark.id, title)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }

    when (openSheet) {
        SheetKind.Chapters -> PlayerListSheet(
            content = PlayerListContent.Chapters(
                items = state.chapters,
                currentIndex = state.currentChapterIndex,
                // A chapter start is genuine user navigation — seekTo moves the resume baseline.
                onSeek = { chapter -> viewModel.seekTo(chapter.startSec) },
            ),
            labels = labels,
            onDismiss = { openSheet = null },
        )
        SheetKind.Bookmarks -> PlayerListSheet(
            content = PlayerListContent.Bookmarks(
                items = state.bookmarks,
                onSeek = { bm -> viewModel.seekToBookmark(bm.positionSec) },
                onRename = { renaming = it },
                onDelete = { viewModel.deleteBookmark(it.id) },
                offlineNote = state.bookmarksOffline,
            ),
            labels = labels,
            onDismiss = { openSheet = null },
        )
        null -> Unit
    }
}

/**
 * Small drag handle hinting that dragging the player down switches to the read-along reader. Mirrors
 * the handle on the in-reader mini player (see ReadaloudPeek) so the swipe-down ↔ swipe-up gesture
 * pair reads as one continuous affordance across both surfaces.
 */
@Composable
private fun ReadAlongDragHandle() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                .size(width = 32.dp, height = 4.dp),
        )
    }
}

/** The two quiet affordances under the scrubber: Chapters and the bookmark count. */
@Composable
private fun PlayerListPills(
    bookmarkCount: Int,
    labels: PlayerChromeLabels,
    onOpenChapters: () -> Unit,
    onOpenBookmarks: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
    ) {
        AssistChip(
            onClick = onOpenChapters,
            modifier = Modifier.testTag("player_chapters_pill"),
            label = { Text(labels.chapters) },
            leadingIcon = {
                Icon(PlayerGlyphs.MenuBook, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))
            },
        )
        AssistChip(
            onClick = onOpenBookmarks,
            modifier = Modifier.testTag("player_bookmarks_pill"),
            label = { Text(bookmarkCountLabel(bookmarkCount, labels)) },
            leadingIcon = {
                Icon(PlayerGlyphs.Bookmark, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))
            },
        )
    }
}
