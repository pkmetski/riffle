---
name: reference_pending_seek_gate_optimistic_discontinuity
description: "Media3 fires EVENT_POSITION_DISCONTINUITY optimistically from seekTo() with perTrackOffsetMs, not book-absolute; PendingSeekGate must use maybeConfirm, not onDiscontinuity"
metadata: 
  node_type: memory
  type: reference
  originSessionId: 5bc26624-7622-4b4e-bae8-c3fe554f1a89
  modified: 2026-08-11T08:05:03.782Z
---

When `MediaController.seekTo(trackIdx, offsetMs)` is called, Media3 fires `EVENT_POSITION_DISCONTINUITY` **optimistically** — immediately, before the `MediaSession` responds — with `c.currentPosition = offsetMs` (raw per-track, NOT book-absolute).

`AbsolutePositionPlayer` on the service side projects per-track → book-absolute, but that value only reaches the controller **after** the `PlayerInfo` round-trip. During the optimistic window, `c.currentPosition` is the small per-track value.

**Bug it caused:** Calling `pendingSeek.onDiscontinuity()` in `onEvents` for `EVENT_POSITION_DISCONTINUITY` cleared `pendingSec` too early. A subsequent `skipBy()` read `c.currentPosition / 1000 = perTrackOffsetSec` instead of the absolute target, and the seek bar flashed to chapter 1 for ~0.75s.

**Fix (PR on `pkmetski/audiobook-seek-progress-reset`):**
- `PendingSeekGate.maybeConfirm(rawPositionSec)` clears `pendingSec` only when `abs(rawPositionSec - pendingSec) < 0.5s` (i.e., the book-absolute value has converged)
- `pushState()` calls `maybeConfirm(c.currentPosition / 1000.0)` before reading position
- `onDiscontinuity()` is no longer called from `onEvents`

**Key invariant:** For any seek that crosses a track boundary, `perTrackOffsetSec << absoluteSec` (differs by the track's `startOffsetSec`, at minimum the first track's full duration). The 0.5s epsilon safely distinguishes them.
