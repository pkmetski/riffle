---
name: ios-harness-flakiness-root-causes
description: Why the iOS harness (ios.yml harness-test-phone) flakes — runner variance + a few heavy tests + two failure modes; how to diagnose
metadata:
  node_type: memory
  type: reference
  originSessionId: 9b1180c6-da70-43b7-848a-0026ab644a13
  modified: 2026-09-23T09:55:23.130Z
---

Branch `pkmetski/fix-test-flakiness-on-merge` (PR #1084) investigation, 2026-09-23.

**Setup:** `ios.yml` `harness-test-phone` runs `xcodebuild test -parallel-testing-enabled YES` = 2 simulator clones on a free `macos-15` runner (M1, 3 vCPU / 7 GB). Android's harness (13m50s) and iOS's healthy run (13m50s) are equal — iOS is NOT globally slower.

**The flakiness is environmental variance, proven:** the *same commit* (425db6d07) ran the iOS harness in 13 min (green) on one runner and 41 min (red) on another. Two failure modes:
1. **Sim crash** — a clone's xctrunner dies (`(ipc/mig) server died` / "Failed to launch app with identifier: com.riffle...xctrunner") under memory pressure. Fixed by a scoped in-job retry (pinned `-parallel-testing-worker-count 2` + retry ONCE only when the log carries a narrow infra-crash signature; genuine test failures are never retried).
2. **Slowness** — a contended runner runs every UI op several times slower; timeout-based waits blow.

**A few heavy tests drive almost all slowness failures**, not the whole suite:
- `testAudiobookPlayerShowsSpeedSleepAndListControls` — the #1 offender (failed 5/5 runs). Root cause: waited on 4 control "pills" with 45s-each timeouts that COMPOUND to ~180s because the CMP iOS accessibility bridge populates the tree incrementally. Fixed by collapsing to ONE `XCTNSPredicateExpectation` polling for all four, capped 60s.
- `testAddAbsSourceEndToEnd` — intrinsically ~165s even when passing (5x the comparable `testChitankaInstallDoesNotCrash` at 32s). It's the one test that drives the add-source UI with typed credentials.

**iOS-specific cost drivers** (Android avoids/handles faster): XCUITest `typeText` synthesizes every keystroke (Android Espresso `replaceText` sets value directly); CMP iOS accessibility tree is async/incremental (Android native Compose is synchronous); Ktor Darwin = NSURLSession per-request overhead vs OkHttp. The stub servers (`StubAbsServer.swift`) add NO latency and the `IosLibraryRefresherImpl` does one call per resource — both ruled out.

**Diagnosis gotcha:** in parallel mode the clone's stdout/unified logs (incl. the stub's `STUB-ABS` request prints) VANISH with the clone — the uploaded `xcodebuild-harness.log` does not contain them, and it shows no per-assertion detail. To see per-step timing / the failing assertion, upload and read the `.xcresult` bundle (`~/derived-data/Logs/Test/*.xcresult`, via `xcrun xcresulttool get test-results tests` / `activities` / `export attachments`). Flatten activity `startTime`s to find the biggest gaps.

**Confirmed causes from the .xcresult (2026-09-23), correcting an earlier WRONG guess:**
- The accessibility tree is SMALL (~70 nodes), NOT bloated. The "66s snapshot" I first blamed was actually `openReader` silently polling its 90s `waitForExistence` for a "Read" button that never appeared. Do not chase "huge accessibility tree" — it's not that.
- `testAudiobookPositionRestoredOnReopen` = reopen tile-tap didn't reach item detail; the audiobook tile was scrolled past the right edge of its LazyRow and `openReader` revealed the tile only ONCE before its retry loop, so the retry tapped an offscreen spot. Fix: re-reveal before every tap in `openReader` (shared helper → hardens all book-open tests).
- `testGutenbergInstallDoesNotCrash` (190s) = 68s cold launch + 60s teardown "Failed to terminate" because unbounded catalogues (Gutenberg/Chitanka/RadioES) browse their REAL external API (not stubbed) and the Darwin/NSURLSession client had no timeout (60s default) vs Android OkHttp ~10s. Fix: `install(HttpTimeout)` (15s connect/30s request) on the iOS Ktor clients in `core/net/src/iosMain/.../KtorClientFactory.kt`.
- Residual ~4–11s per element query is the genuine 2-clones-on-3-cores CPU-contention tax on XCUITest snapshots; lever is FEWER lookups per test, not timeouts.

See [[feedback_no_bigger_ci_runners]].
