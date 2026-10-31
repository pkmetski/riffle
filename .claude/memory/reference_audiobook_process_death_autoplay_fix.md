---
name: audiobook-process-death-autoplay-fix
description: "Root cause and fix for \"reopening app auto-plays previously paused audiobook\" on Android process death"
metadata:
  node_type: memory
  type: reference
  originSessionId: 6b08caf1-0cbd-45c7-be38-a6eb4db03cb7
  modified: 2026-10-16T07:54:16.661Z
---

## Root cause

Android process death restores nav args (`userPlay=true`) via `SavedStateHandle`, causing `AudiobookPlayerViewModel` to be created with what looked like an explicit user tap. The old `suppressAutoPlay = !navUserPlay && wasSleepStopped` never fired because `navUserPlay` was always true after restore.

## Fix (branch pkmetski/audiobook-auto-resume)

Replaced `navUserPlay: Boolean` parameter with two new mechanisms:

1. **`AudiobookPlayIntentRegistry`** (`feature/player/commonMain`) — in-memory singleton. Set before explicit navigation (`recordIntent`), consumed on VM init (`consumeIntent`). Empty after process death.

2. **`AudiobookUserPausedStore`** (`core/domain/commonMain` interface) — persisted store (DataStore on Android, NSUserDefaults on iOS). Records play/pause transitions via a `controller.state` observer in the VM.

New auto-play logic:
```kotlin
val hasExplicitPlayIntent = playIntentRegistry.consumeIntent(sourceId, itemId)
val wasUserPaused = userPausedStore.wasPaused(sourceId, itemId)
val wasSleepStopped = sleepStopStore.wasSleepStopped(sourceId, itemId)
val shouldAutoPlay = !resume.wasFinishedOnOpen &&
    (hasExplicitPlayIntent || (!wasUserPaused && !wasSleepStopped))
```

## Navigation intent sites (Android)
`recordIntent()` called before `navController.navigate("audiobook_player/...")` in:
- `LibraryNavGraph.kt`: `onAudiobookBookmarkSelected`, `onPlayItem`, `onListen`, `onListenItemAtSec`, `onAudiobookBookmarkSelected` (annotation search)
- `ReaderNavGraph.kt`: `onPlaylistAdvance`

## Navigation intent sites (iOS, HomeScreen.kt)
`playIntentRegistry.recordIntent()` before each `push(LibraryNav.AudiobookPlayer(...))` in `LibraryHost` and `AppSection.Riffle` block.

Notification taps do NOT record intent (intentional).

## Tests
- `SleepTimerTest.kt`: 3 new auto-play suppression tests; removed `navUserPlay` param
- `AudiobookPlayerViewModelBookmarkTest.kt`: added `NoopUserPausedStore`
- `AudiobookPlayerViewModelTests.swift`: 3 new `AudiobookPlayIntentRegistry` tests
