# Readaloud expandable player — design

**Date:** 2026-06-13
**Branch:** pkmetski/seamless-ebook-audiobook-switch
**Scope:** Feature A only (the in-reader swipe-up gesture). Feature B (detail-screen
cross-mode link) is explicitly out of scope for this change.

## Goal

While reading an EPUB with Readaloud playing, let the user **swipe the mini player up**
to expand it into a full-screen player, and **swipe (or pull) down** to collapse back to
exactly the reading spot. It is one playback session shown two ways — the mini player is
the *peek* state, the full player is the *expanded* state. Nothing switches; the reader
stays mounted underneath the whole time.

The expanded surface reuses the **same visual surface** the standalone Audiobook player
uses (square cover, title/author, current-chapter label, seekable chapter-map scrubber,
transport cluster, speed pill).

## Non-goals

- No position handoff between modes (already handled elsewhere; positions are independent).
- No detail-screen / library cross-mode link (feature B).
- No change to the standalone audiobook destination's behaviour beyond refactoring its
  body into the shared surface.

## Architecture

### 1. Extract a stateless `PlayerSurface`

New file `app/src/main/kotlin/com/riffle/app/feature/audio/PlayerSurface.kt`.

- `data class PlayerSurfaceState` — the playback fields the surface renders:
  `title, author, coverUrl, authToken, isPlaying, speed, positionSec, durationSec,
  currentChapterTitle: String?, chapterStartsSec: List<Double>, canPreviousChapter,
  canNextChapter`.
- `class PlayerSurfaceActions` (or a set of lambda params) — `onSeek(Double),
  onTogglePlayPause, onRewind, onForward, onPreviousChapter, onNextChapter,
  onSpeedChange(Float)`.
- `@Composable fun PlayerSurface(state, actions, modifier)` — the body currently living in
  `AudiobookPlayerScreen.PlayerBody` plus `TransportRow`, `DualTime`, `ChapterSeekBar`,
  `formatHms`. Moved verbatim, parameterised by state/actions instead of the audiobook
  view-model.

`AudiobookPlayerScreen` keeps its `Surface` + gradient + collapse row, maps its
`AudiobookPlayerUiState` → `PlayerSurfaceState`, and renders `PlayerSurface`. Behaviour
unchanged — this is a pure refactor with the existing audiobook tests as the safety net.

### 2. Surface the missing readaloud playback data

The readaloud session must supply what the surface needs. Changes, smallest first:

- **Duration:** expose `ReadaloudTrack.totalDuration` (currently private) via a public
  accessor, surface it through `ReadaloudController.PlaybackState.durationSec`.
- **Seek:** add `ReadaloudController.seekTo(globalSec: Double)` using the existing
  `ReadaloudTrack.positionAt(globalSec)` → `seekToAudio(file, offset)` path; expose
  through `PlayerCoordinator` and the reader view-model. Auto-follow already re-syncs the
  highlight from audio position on the 250ms poll, so a seek re-anchors the page the same
  way a chapter skip does.
- **Chapter starts:** compute `chapterStartsSec: List<Double>` from the track (global start
  time of each chapter's first clip) and surface it. Drives the scrubber ticks.
- **Chapter title:** readaloud chapter hrefs are not human-readable. v1 shows a simple
  `Chapter {index+1} of {count}` label (or `null` when no chapters). Not derived from the
  ebook TOC — that coupling was tried and rejected previously.

The reader view-model already holds the `LibraryItem` (title/author/coverUrl) and the auth
token plumbing exists for covers; build a `readaloudPlayerState: StateFlow<PlayerSurfaceState>`
by combining `playbackState` + the item metadata + token.

### 3. The draggable sheet in the reader

Replace the fixed mini-player slot with an `AnchoredDraggable` sheet that has two anchors:

- **Peek** — height = current mini-player height; renders `ReadaloudMiniPlayer`.
- **Expanded** — full screen; renders `PlayerSurface` fed by `readaloudPlayerState`, with a
  grabber + "pull down to return to your page" affordance at the top (matching the
  audiobook collapse row).

Drag progress (0 peek → 1 expanded) cross-fades mini ↔ full, raises a scrim over the
reader, and squares the top corners as it fills. Flick or cross-the-midpoint snaps. Tapping
the peek expands; tapping the grabber / dragging down collapses. The reader composable
stays mounted underneath, so collapse returns to the exact spot for free.

Interaction with existing reader machinery:
- The paginated **reserve** (`ReadaloudReserve`, 56dp) stays sized to the peek height — the
  expanded sheet floats above the page and does not reflow it.
- **Immersive mode** unchanged; the sheet lives in the same bottom-anchored area today.
- The **chapter rail overlay** continues to sit directly above the peek mini player; when
  expanded it is covered by the full surface.
- Back press / predictive back collapses an expanded sheet before exiting the reader.

## Testing

- **PlayerSurface refactor:** existing `AudiobookPlayerScreen` instrumented tests must stay
  green (same test tags preserved). Add a couple of `PlayerSurface` tests that render it
  from a fixed state and assert transport/scrubber tags.
- **Readaloud data:** unit-test `ReadaloudTrack` duration + chapter-starts computation and
  `seekTo` global→(file,offset) mapping (pure JVM).
- **Sheet behaviour:** a harness test that opens readaloud, expands the sheet (drag/tap),
  asserts the full surface tag is shown, collapses, asserts the mini player tag returns and
  the reader is still at the same locator.

## Risks

- The draggable sheet layered over the WebView + immersive mode is the highest-risk piece;
  build it last, after the surface and data are in place and unit-tested.
- Seeking readaloud relies on auto-follow re-syncing; verify the page actually navigates on
  a scrubber seek on a real device (headless AVDs stall reader nav per project notes).
