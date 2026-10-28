---
name: comics-streaming-page-turn-latency
description: "Comics 5-10s blank page turns = streaming phase cold fetches, NOT Android version; fixed with NetworkImageSource read-ahead + spinner + decodeWithRetry"
metadata: 
  node_type: memory
  type: reference
  originSessionId: c0dec8c8-5eea-47ca-9280-b4f9508df7b3
  modified: 2026-08-29T10:21:32.413Z
---

Comics "next page takes 5-10 s, blank while loading" (2026-08-29, branch pkmetski/castries): root cause is the CBZ **streaming phase** — when the book isn't cached locally, every page turn is a cold synchronous full-res page download competing with the 274 MB background full-file download. The panel-resolution prefetch is the ONLY incidental byte prefetch and it no-ops once panels are persisted in comic-panels JSON (typical for a previously-read book). "Repro on device X but not AVD Y" for comics almost always means cached-vs-uncached CBZ (`run-as com.riffle.app ls cache/cbz/*/`), not OS version.

**Why:** the blank page (not spinner) came from feeding a null bitmap into Coil — null model resolves as instant empty error, and `produceState` + `runCatching.getOrNull()` never retried a failed fetch (permanent blank).

**How to apply:** fix = `NetworkImageSource` read-ahead (readAheadCount=2, full-res source only), `cbzPageContent()` Loading branch, and `decodeWithRetry()` in `CbzReaderScreen.kt`. Repro/verify recipe: clone an AVD, delete the book's cache/cbz file, `adb emu network speed edge`, open book → page turns must render <1 s from read-ahead. Note: emulator throttle speeds below ~hsdpa are near-unusably slow (umts measured ~4 KB/s effective). Related: [[harness-avd-contention-flake]] — emulator-5554 got force-stopped mid-experiment by another workspace.
