---
name: reference_annotation_focus_harness_dead_sleeps
description: AnnotationFocusHarnessTest was the top harness time sink; its assertion had been silently replaced by dead Thread.sleep+marker-file scaffolding
metadata: 
  node_type: memory
  type: reference
  originSessionId: e57a2d2e-261e-45e0-894a-b99842d80af8
---

`AnnotationFocusHarnessTest` (annotation nav focus in paged/vertical/continuous) was authored in PR #258 to assert that tapping a library annotation FOCUSES the phrase inside the viewport. The helper `waitForPhraseOnScreen` (JS `getBoundingClientRect` → viewport check) was written but never wired in; instead the test body slept `Thread.sleep(8_000)` × 2 per attempt × 3 attempts × 3 methods = **144s per full run** and wrote marker files nobody reads. Diagnostic-only scaffolding that shipped and stayed.

**Impact when it existed:** 176–184s per full harness run (~40% of harness test time). Occasional local process-crashes on `-no-window` API-25 AVDs during the idle 8s sleeps → gradle abort → 245 tests skipped downstream.

**Fix (2026-07-01):** wired `waitForPhraseOnScreen` into `runModeTest` in place of the sleeps; deleted `writeMarker`. Test now actually asserts what its name promises, runs in ~40s (poll-based), and no longer crashes.

**Signal to watch for elsewhere:** a harness test with `Thread.sleep(N_000)` + `writeMarker("READY_...")` + no assertion body is dead scaffolding from a debug session, not a legit slow test. Grep for `writeMarker` before trusting a harness class's runtime. See also [[reference_flaky_replaceall_emission_tests]], [[reference_harness_run_avd_gotchas]].
