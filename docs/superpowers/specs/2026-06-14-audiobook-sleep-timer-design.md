# Audiobook Sleep Timer

**Date:** 2026-06-14
**Status:** Approved

## Overview

A sleep timer for the audiobook player. The user sets a duration (or end-of-chapter) and playback fades out and pauses automatically. Targeted at the falling-asleep use case: set it, put the phone down, done.

## UI

### Speed + Sleep row

A matched pair of pills sits directly below the transport controls, replacing the standalone speed pill that exists today:

```
⏮  ⏪  ▶  ⏩  ⏭

[ 🐢 1× ]   [ 🌙 Sleep ]        ← idle (sleep pill muted)
[ 🐢 1× ]   [ 🌙 24:00 ]        ← active, counting down (purple)
[ 🐢 1× ]   [ 🌙 End of ch. ]   ← end-of-chapter mode (purple)
```

Both pills are the same size and visual weight. The speed pill's tap behaviour is unchanged (opens the existing speed sheet). The sleep pill is always present; it is visually muted when no timer is set.

### Sleep timer bottom sheet

Opens when the user taps the sleep pill (in either idle or active state).

**Layout:**
```
  ───────  (drag handle)
  SLEEP TIMER

  [ 🌙 Sleeping in 24:00  ✕ ]   ← shown only when a timer is already running
                                   ✕ cancels the timer

  [ 🌙 End of chapter          ]  ← full-width pill

  [ 15 min ]  [ 30 min ]  [ 45 min ]
  [ 60 min ]  [ 90 min ]  [        ]   ← 3-column grid
```

- Tapping any preset immediately sets the timer and dismisses the sheet.
- Tapping a preset when a timer is already running replaces it (no extra confirmation).
- The active-timer banner lets the user cancel without picking a new duration.
- The sixth grid cell is empty (5 presets + 1 blank), which is fine.

### Presets

| Option | Duration |
|---|---|
| End of chapter | Fires at the next chapter boundary |
| 15 min | 15:00 countdown |
| 30 min | 30:00 countdown |
| 45 min | 45:00 countdown |
| 60 min | 60:00 countdown |
| 90 min | 90:00 countdown |

## Behaviour

### Timer firing
1. Volume fades linearly from current level to 0 over 5 seconds via `AudiobookController`.
2. Playback pauses at the end of the fade.
3. No notification, no sound — silent stop (the user is likely asleep).

### Cancellation
The timer is cancelled when:
- The user taps ✕ in the active-timer banner.
- The user pauses playback manually.
- The app process is killed / the app restarts (no persistence).

Navigating away from the player screen does **not** cancel the timer — background playback keeps the timer alive.

### End-of-chapter mode
The controller listens for chapter-boundary events from Media3. When the current chapter ends, it triggers the same fade-and-pause sequence as a countdown timer reaching zero.

## Architecture

### Where the timer lives

The countdown and fade logic live in `AudiobookController` (which runs inside `AudioPlayerService`), so the timer survives the player screen being backgrounded or the ViewModel being cleared.

```
AudiobookController
  ├── sleepTimer: SleepTimer          (new)
  │     ├── mode: CountDown(ms) | EndOfChapter | None
  │     ├── remainingMs: StateFlow<Long>
  │     └── cancel()
  └── onChapterChanged()              (triggers EoC check)

AudiobookPlayerViewModel
  └── sleepTimerState: StateFlow<SleepTimerUiState>
        = Idle | Active(remainingMs) | EndOfChapter

AudiobookPlayerScreen / PlayerSurface
  └── SleepTimerPill(state, onClick)  (new composable)

SleepTimerSheet                       (new composable, bottom sheet)
  └── presets grid + active banner
```

### SleepTimer class (in AudiobookController)

- Holds a `CoroutineScope` tied to the service lifetime.
- `CountDown` mode: ticks every second, exposes `remainingMs` as a `StateFlow`.
- `EndOfChapter` mode: no tick needed; `AudiobookController.onChapterChanged()` calls `checkEndOfChapter()`.
- Fade: adjusts `player.volume` in 100 ms steps over 5 seconds, then calls `player.pause()` and resets to `None`.

### ViewModel

Collects `sleepTimer.remainingMs` and maps it to `SleepTimerUiState` for the UI. Exposes `setSleepTimer(mode)` and `cancelSleepTimer()`.

### UI

`PlayerSurface` gains a `sleepTimerState` parameter and renders the new row. `SleepTimerSheet` is a `ModalBottomSheet` with the grid; selecting a preset calls `viewModel.setSleepTimer(mode)`.

## What is not in scope

- Custom duration picker.
- "Shake to extend" or extend-on-notification.
- Persistence across restarts.
- A notification countdown while the timer is running.
