# ADR 0065 — Shared AudiobookPlayerViewModel in commonMain

**Date:** 2026-09-15
**Status:** Accepted

## Context

Two `AudiobookPlayerViewModel` implementations exist — `feature/player/src/jvmMain` (590 L, full feature set: sleep timer, bookmarks, rewind-on-resume, skip intervals, playlist, offline, readaloud handoff, progress sync) and `shared/src/iosMain/IosAudiobookPlayerViewModel` (220 L, ABS-only, missing ~70% of features). iOS users can't access sleep timer, bookmarks, configurable skip/rewind, playlist advance, or readaloud handoff. Issue #1033 requires one shared ViewModel.

Decision (b) from #1033: whether to lift the JVM ViewModel to `commonMain` or author a new shared one.

## Decision

**Lift the existing JVM ViewModel to `commonMain`.**

### Why not "new shared ViewModel"

The JVM ViewModel already has a correct, tested implementation of every required capability (>20 unit tests in `AudiobookPlayerViewModelBookmarkTest`, `SleepTimerTest`, `PendingSeekGateTest`, etc.). Rewriting from scratch would reproduce the same logic, duplicate risk, and need re-verification. Lifting moves the same code under a shared build.

### What makes lifting viable

`AudiobookPlayerViewModel` extends `androidx.lifecycle.ViewModel` — available as a KMP library (`lifecycle-viewmodel`) already used by `feature/player/src/commonMain` (e.g. `CbzReaderViewModel`). The constructor dependencies fall into three groups:

| Group | Examples | Action |
|---|---|---|
| Already shared interfaces | `AudioPlayerInterface`, `ListeningPreferencesStore`, `AudiobookBookmarkStore`, `ProgressFlushScope`, `AudiobookHandoffState`, `Clock`, `Logger` | None — already in `commonMain` |
| JVM-named but already interface-backed | `JvmAudiobookDownloadRepository`, `JvmAudiobookCacheRepository`, `JvmReadaloudAudioRepository` | Rename to `AudiobookDownloadRepository`, `AudiobookCacheRepository`, `ReadaloudAudioRepository` in `core/domain/commonMain`; keep JVM impls in `androidMain`; add no-op iOS stubs |
| Genuinely platform-specific | `BundleAudiobookSource` (bundle file access), `ContentCacheAccessStore`, `ProgressSweep` | Define shared interface in `commonMain`; JVM impl stays in `androidMain`; iOS no-op or deferred impl |

No rewrite is needed. Only the dependency seams change.

### Architecture after lift

```
feature/player/src/commonMain/
  AudiobookPlayerViewModel.kt          ← lifted from jvmMain
  AudiobookDownloadRepository.kt       ← interface (was JvmAudiobookDownloadRepository)
  AudiobookCacheRepository.kt          ← interface (was JvmAudiobookCacheRepository)
  ReadaloudAudioRepository.kt          ← interface (was JvmReadaloudAudioRepository)
  BundleAudiobookSource.kt             ← interface (new)

feature/player/src/androidMain/
  AndroidAudiobookDownloadRepositoryImpl.kt
  AndroidAudiobookCacheRepositoryImpl.kt
  ... (unchanged business logic, only naming)

feature/player/src/iosMain/
  IosAudiobookDownloadRepositoryNoOp.kt
  IosAudiobookCacheRepositoryNoOp.kt
  IosReadaloudAudioRepositoryNoOp.kt   ← (already exists as IosNoOpReadaloudAudioRepository)
  IosBundleAudiobookSourceNoOp.kt

shared/src/iosMain/
  IosAudiobookPlayerViewModel.kt       ← DELETED; iOS wired to shared VM via Koin
```

`IosAudiobookPlayerViewModel` is deleted. The Koin iOS module binds the shared `AudiobookPlayerViewModel`. The iOS `IosAudiobookPlayerScreen` is updated to consume `AudiobookPlayerUiState` from the shared VM.

### Tests

`AudiobookPlayerViewModelBookmarkTest`, `SleepTimerTest`, `PendingSeekGateTest`, `FollowLoopOrchestratorTest` move from `app/src/test` to `feature/player/src/commonTest`. `AudiobookPlayerViewModelTest` in `iosAppTests` is retired with a `Removed-test:` trailer.

### ADR for iOS continuous mode host architecture

Separate ADR 0066 documents the stacked-WKWebView host for continuous mode (deferred from this ADR).

## Consequences

- iOS audiobook player gains sleep timer, bookmarks, skip/rewind intervals, playlist, readaloud handoff, progress sync at parity with Android.
- One shared implementation eliminates drift risk.
- Intermediate PRs must land feature-flag–free: each shared-interface extraction is a standalone PR; the final lift is a single PR that wires iOS Koin and deletes `IosAudiobookPlayerViewModel`.
- `JvmAudiobookDownloadRepository` and similar names disappear from public API; callers updated mechanically.
