# ADR 0032 — Pre-warm player handoff: keep MediaController alive and pre-fetch session data during drag

**Status:** Accepted
**Relates to:** [ADR 0029](0029-audiobook-direct-abs-streaming-audio-led-sync.md), [ADR 0031](0031-unified-matched-book-position.md).

## Context

Switching between audiobook and readaloud (swipe up on the mini-player, swipe down on the full player) had a noticeable audio gap in both directions. The gap came from three sequential costs that ran *after* the swipe threshold, while the user was already waiting:

1. **MediaController reconnect.** Both `releaseForHandoff()` implementations called `controller.release()` and set `controller = null`, destroying the Binder connection to `AudioPlayerService`. The incoming side had to call `MediaController.Builder.buildAsync()` to reconnect (~100–200 ms).

2. **ABS play session fetch.** When navigating to `AudiobookPlayerViewModel`, the init path called `audiobookRepository.openPlaySession()` — a network round-trip (~200–500 ms) — before it could queue tracks via `setMediaItems`.

3. **SMIL seek resolve.** When navigating back to readaloud, `playerCoordinator.playFromSecond()` resolved the global-second seek target from the bundle's SMIL. This is pure in-memory computation but runs synchronously after navigation.

The drag gesture itself takes 300–800 ms to reach the threshold, which is free preparation time that was previously wasted.

## Decision

### 1. Keep the MediaController connection alive across handoffs

`releaseForHandoff()` in both `AudiobookController` and `ReadaloudController` no longer calls `release()` or nulls `controller`. It pauses playback and removes the listener, but the Binder channel to `AudioPlayerService` stays open.

`ensureConnected()` tracks a `listenerAttached` flag so it re-adds the listener exactly once when the incoming side takes ownership, without double-registration.

This turns the ~150 ms reconnect in both directions into a no-op on every handoff after the first.

### 2. Pre-fetch ABS session data during the drag (readaloud → audiobook)

`EpubReaderViewModel` gains a `hintAudiobookHandoff()` method, wired to the gesture detector's `onDragStart`. It launches a coroutine to call `audiobookRepository.openPlaySession()` and stores the result in `AudiobookController.preWarmState`.

When `AudiobookPlayerViewModel.prepare()` runs after navigation, it calls `controller.consumePreWarm()`. If non-null, it skips the `openPlaySession()` network call and uses the pre-fetched tracks directly.

If the drag is abandoned, `cancelHandoffHint()` calls `AudiobookController.cancelPreWarm()`, discarding the stored state.

### 3. Pre-resolve SMIL seek target during the drag (audiobook → readaloud)

`AudiobookPlayerViewModel` gains a `hintReadaloudHandoff()` method, wired to its gesture detector's `onDragStart`. It calls `ReadaloudController.preWarmSeek(bundle, currentPositionSec)`, which resolves `track.seekTarget(positionSec)` and caches the result.

When `playerCoordinator.playFromSecond()` runs post-navigation, the seek target is already computed.

### Old audio continues playing during the drag

Pausing the outgoing player happens at the threshold (inside `releaseForHandoff()`), not at drag start. The user hears continuous audio throughout the swipe gesture.

## Timing after this change

**Readaloud → audiobook:**
- Drag start: `openPlaySession()` fires in background
- Threshold: old readaloud pauses, navigate, `AudiobookPlayerViewModel` finds pre-fetched session → `setMediaItems + prepare + play` (only remaining cost: ExoPlayer `STATE_READY`, ~50–150 ms for cached streams)

**Audiobook → readaloud:**
- Drag start: SMIL seek target pre-resolved in memory
- Threshold: old audiobook pauses, navigate, `playFromSecond()` uses cached target → `play()` immediately (readaloud uses local `zipaudio://` files; `STATE_READY` is <50 ms)

## Consequences

- `AudiobookController` and `ReadaloudController` hold a persistent Binder connection to `AudioPlayerService` for the lifetime of the session (negligible overhead — just a handle).
- Both controllers gain `preWarmState` fields. If the app is killed and restarted mid-drag, the pre-warm is lost; the incoming side falls back to the normal `openPlaySession()` path.
- Drag gestures that start and abandon rapidly (e.g. accidental touch) trigger a `cancelPreWarm()` call, which is a no-op if pre-warm hasn't completed yet.
- The 10 px hint threshold is below the 60 px / 160 px commit thresholds so there is enough lead time on typical hardware. If the drag completes faster than `openPlaySession()` returns, the audiobook side falls back to the normal path (pre-warm is a best-effort optimisation, not a gate).
