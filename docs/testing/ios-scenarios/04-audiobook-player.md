# iOS Scenario 04 — Audiobook Player

Corresponds to Android harness tests: `AudiobookPlayerSnackbarTest`, `PlayerTitleYearTest`,
`AudioPlaybackSpeedPersistenceTest`, `WakeLockHarnessTest`.

## Prerequisites

- An ABS source is configured and the library contains at least one audiobook.
- The ABS server is reachable (use the dev server at `http://media-server:13378`).

## Scenario 04-A: Open audiobook from library

1. Launch the app — library browser is shown.
2. Tap an audiobook item (one that shows a headphones/audio indicator).
3. Tap the "Read" button on the item detail screen.
4. **Expected**: The audiobook player screen opens.
5. **Expected**: Title and author are displayed.
6. **Expected**: Playback starts automatically within 3 s (loading spinner goes away).

## Scenario 04-B: Play / pause

1. Open an audiobook (scenario 04-A).
2. Tap the play/pause button while playback is active.
3. **Expected**: Playback pauses — the button changes to the play icon.
4. Tap again.
5. **Expected**: Playback resumes — button changes to the pause icon.

## Scenario 04-C: Seek via chapter list

1. Open an audiobook that has chapters.
2. Tap a chapter title in the chapter list.
3. **Expected**: Playback seeks to that chapter's start. The current chapter indicator
   moves to the tapped chapter within 1 s.

## Scenario 04-D: Previous / next chapter buttons

1. Open an audiobook that has at least 3 chapters and advance to chapter 2.
2. Tap the ⏭ (next chapter) button.
3. **Expected**: Playback jumps to the start of chapter 3.
4. Tap the ⏮ (previous chapter) button.
5. **Expected**: Playback restarts chapter 3 (within restart threshold) or jumps to chapter 2.

## Scenario 04-E: Now Playing lock screen integration

1. Open an audiobook and start playback.
2. Lock the device / go to the home screen.
3. **Expected**: The lock screen / Control Centre shows the book title and author in
   Now Playing with a play/pause button.
4. Tap pause on the lock screen.
5. **Expected**: Playback pauses. The in-app player also shows the paused state on resume.

## Scenario 04-F: Background audio

1. Open an audiobook and start playback.
2. Press the Home button (background the app).
3. **Expected**: Audio continues playing in the background.

## Scenario 04-G: Back navigation

1. Open an audiobook player.
2. Tap "← Back".
3. **Expected**: The library browser is shown. The player screen is dismissed.
