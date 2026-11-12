---
name: verify-landed-content-not-stability
description: "When verifying a reader navigation/landing fix on the AVD, check the landed CONTENT against the expected target (chapter + figure/phrase), not merely that the viewport stops moving — a stable wrong position is still the bug"
metadata:
  node_type: memory
  type: feedback
  originSessionId: 71b1a37f-1348-4eab-bd44-d946ac81f75c
  modified: 2026-09-29T11:09:53.030Z
---

When validating a navigation/landing fix (annotation focus, TOC jump, bookmark, resume) on device, compare what is on screen with the *expected* destination — the specific figure/heading/phrase in the specific chapter — at several timestamps. "The position didn't change between 3s and 15s" is NOT a pass.

**Why:** On 2026-09-29 (branch pkmetski/annotation-figure-nav-focus, PR #1127) I reported the cross-chapter figure-annotation drift as fixed because the AVD screenshot was identical at 3s and 15s. The viewport had already drifted (deferred chapter above the target grew and its scroll compensation was silently swallowed) *before* the 3s capture — it was stable at the wrong place. The user had to send a screen recording to show it "most definitely does NOT work". Two earlier fix claims on the same bug also failed on device.

**How to apply:**
- Capture at ~1s, 3s, 6s, 12s and confirm the target element (e.g. the annotated figure + its caption) is in frame at EVERY capture; note the chapter heading/context.
- If the target isn't in frame at the first capture, the landing already failed — instrument (`[DEBUG-…]` scroll tracer with stack frames in `onScrollChanged` worked well) instead of scrolling around trying to interpret where you are.
- Extract frames from the user's recording with ffmpeg (`fps=2`, hstack strip) and match them against the AVD frames before concluding anything.
- Related: [[verify-on-device-before-done]], [[no-overconfident-fix-claims]].
