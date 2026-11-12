---
name: reference_continuous_annotation_focus_reflow_race
description: Continuous-mode annotation/resume landing intermittently rests near chapter top (annotation off-screen) = initial scroll fired against PRE-reflow chapter height; fix = re-land on every target remeasure until height stabilizes
metadata: 
  node_type: memory
  type: reference
  originSessionId: 877fc3aa-a36b-4c4e-b4d7-cc76f6989506
---

Opening an annotation from the library (or resuming) in **continuous** mode intermittently landed near the chapter TOP with the annotation off-screen below. Branch pkmetski/continuous-annotation-nav-focus (commit 96e0ce58).

**Root cause:** `ContinuousReaderView` initial scroll computes target Y = `slot.top + progression*slot.height` from the chapter's MEASURED height, but the first `onHeightMeasured` is PRE-typography-reflow. Style injection then grows the chapter, so a mid-chapter progression landing rests short. The 700ms `INITIAL_SCROLL_FALLBACK_MS` safety-net made it worse by firing against the placeholder height. Both the normal measure path AND the fallback path landed once and never re-derived after reflow.

**Fix:** retain the initial-landing closure (`reapplyLandingAfterFallback`) and re-invoke it on EVERY target-chapter remeasure (track `reapplyTargetLastHeight`) until height stabilizes, in BOTH the normal and fallback paths; disarm on first `ACTION_DOWN` in `onInterceptTouchEvent` so a manual scroll is never yanked. Re-invokes the SAME closure → resume/annotation semantics unchanged, just honored against the final height.

**Also in this branch:** `ContinuousReaderView.initialize(alignToTop=true)` (CFI-derived positions are content-top-relative, commit 2ff4bf1f). The library-open path uses Readium's `initialLocator` (createFragmentFactory) for paged/vertical and `initialize()` for continuous — NOT `navigationTarget.navigateTo` (that's the in-reader annotation-panel path). Paged/vertical focus correctly via Readium; only continuous needed fixing.

**Verification recipe (reusable):** real harness test `AnnotationFocusHarnessTest` drives the actual library→search→tap-annotation→reader flow, then asserts the annotated phrase's `getBoundingClientRect` is in the viewport. For continuous (stacked ChapterWebViews in a NestedScrollView) a WebView-local rect is NOT viewport-relative — convert to screen coords via `WebView.getLocationOnScreen() + rect*scale` and test against the content view's on-screen bounds. `uiAutomation.takeScreenshot()` returns null on a `-no-window` AVD; use host `adb exec-out screencap -p` instead, coordinated via a marker file the test writes + an 18s hold. Continuous was ~50% flaky before, 8/8 after. See [[reference_harness_run_avd_gotchas]].

**Lesson:** logic-only tests that check CFI→progression math do NOT verify the page actually focuses the annotation — must drive the real reader and assert on-screen geometry. Earlier "manual tests" that just printed messages were theater; deleted.
