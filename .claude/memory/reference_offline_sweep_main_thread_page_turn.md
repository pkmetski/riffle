---
name: offline-sweep-main-thread-page-turn
description: "REAL cause of comics 5-10s blank page turns (cached book, Flip8) = offline-mode library sweep on Main per progress write; fixed with flowOn(Default) + availability memo"
metadata: 
  node_type: memory
  type: reference
  originSessionId: c0dec8c8-5eea-47ca-9280-b4f9508df7b3
  modified: 2026-08-29T19:05:28.373Z
---

The user's comic page-turn delay (5-10 s blank, book fully downloaded, Galaxy Flip8 "Android 17") was NOT the streaming phase and NOT the OS: every page turn writes reading progress → Room re-emits all library flows → `LibraryFilterEngine` projections re-run **on Main**; with `isOffline == (!online || refreshFailed)` true, every projection runs `isAvailableOffline` over every item (~8 `File.exists()` each, ErrnoException built per miss) + one Room flow per series/collection. Fixed in `pkmetski/comic-page-load-delay` (commit 0f2f0211d): `flowOn(dispatchers.default)` on the engine/`CollectionDetailViewModel.items`/`seriesCoverUrls`, plus per-item memo in `LibraryItemOfflineAvailability` invalidated by `LocalAvailabilityEvents.changes`.

**Why:** `stateIn(viewModelScope)` collects on Main; nothing hopped the combine graph off it, and one failed refresh latches offline mode for the session.

**How to apply:** repro recipe for "slow only on the user's device" bugs = blackhole the source port on a rooted google_apis AVD (`adb root; iptables -A OUTPUT -p tcp --dport <port> -j DROP`) and watch `logcat | grep -E "Skipped .* frames|Davey"`; catch the culprit red-handed with `adb shell debuggerd -j <pid>` mid-stall (main-thread Java stack names the exact frame). Android 17 / API 37 emulator images are 16 KB-page-only and refuse HVF on this Mac — closest bootable twin is android-36 `google_apis_ps16k` + "6.7in Foldable" profile (AVD `session_castries_flip16k`). Related: [[comics-streaming-page-turn-latency]] (separate, also-real streaming fix on the same branch).
