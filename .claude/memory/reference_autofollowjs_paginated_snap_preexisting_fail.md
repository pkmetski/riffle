---
name: reference_autofollowjs_paginated_snap_preexisting_fail
description: "AutoFollowJsTest.paginatedSnapsToTheColumnContainingTheSentence fails on the local Harness AVDs on main too — pre-existing, not your regression"
metadata: 
  node_type: memory
  type: reference
  originSessionId: e7f1a473-a65e-4325-8291-d1d4d12af57e
---

`com.riffle.app.feature.reader.AutoFollowJsTest.paginatedSnapsToTheColumnContainingTheSentence` (an instrumented harness test added by PR #100, exercising `autoFollowSnapJs` in a real WebView) FAILS on the local Harness AVDs with `AssertionError: sentence should be snapped onto the page grid (left=1408, iw=412)`. The snap floored `scrollLeft` to a multiple of iw but the result got clamped (target column near content end), so the sentence's client-left (1408) lands outside `[0, iw)`.

VERIFIED PRE-EXISTING: reproduces identically on `origin/main` on `Harness_Medium_Phone` and a clone, AND on a fresh API-25 AVD cloned from the harness one — so it is NOT introduced by reader-navigation work and NOT a "locally-abused AVD" artifact.

ROOT CAUSE (probed 2026-06-08, supersedes the earlier "clamp edge" guess): on the API-25 (Android 7.1.1) WebView, a synthetic absolute-positioned fixture page is NOT a real horizontal scroll viewport. Setting horizontal scroll via `document.scrollingElement.scrollLeft`, `document.body.scrollLeft`, AND `window.scrollTo(x,0)` all make `window.scrollX` report the new value, but a child's `getBoundingClientRect().left` does NOT move (stays at its document-absolute left). So `autoFollowSnapJs` reads `r.left` as the un-scrolled absolute position and its floor-to-grid `se.scrollLeft=…` write also doesn't move geometry → `rectLeft("offright")=1408` ∉ `[0,iw)`. The production reader is unaffected because Readium paginates into a REAL overflow column-scroller, where geometry tracks scroll. Consequence: ANY paginated bare-WebView fixture assertion that depends on horizontal scroll moving geometry is unreliable on API 25 (the other AutoFollowJsTest paginated tests pass only because they re-assert the value they set / find the sentence at its absolute position). Guards for horizontal-snap behaviour must be real-reader integration tests, not bare fixtures. See [[reference_readaloud_e2e_test_setup]].

When finalizing a reader change: this 1 failure in `make harness-test` is pre-existing — confirm your touched code is unrelated (the rest of the ~152 phone-harness tests pass) and don't block on it. Don't "fix" it inside an unrelated PR. See [[reference_migration_tests_api25_preexisting_fails]] for the same pattern in core:database.
