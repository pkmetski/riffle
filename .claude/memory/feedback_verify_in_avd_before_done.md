---
name: verify-in-avd-before-done
description: "Never claim a reader/UI fix is done from instrumentation tests alone — reproduce the original symptom in the running app on an AVD with the actual book/state, then re-do the same gesture after applying the fix"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 7ab937a7-1ee8-403c-8b2e-e4d0ac1f9c7a
---

Before announcing a reader/UI fix as complete, actually exercise the exact user-facing gesture in the running app on an AVD with the same book/chapter/mode the user reported — observe with your own eyes that the symptom is gone. Instrumentation tests on the touch-dispatch code path can pass while the real symptom persists because the live system has many other interacting layers (WebView selection lifecycle quirks, ActionMode timing on this OS image, scroll fling continuations, etc.).

**Why:** I shipped a "fix" for the continuous-mode page-jump-on-selection bug based on a passing instrumentation test against `ContinuousReaderView.onInterceptTouchEvent` and never reproduced or re-tested the real symptom in the app. The user re-tested and the bug was unchanged ("no change. next time, before announcing it is done, test in avd").

**How to apply:** When the bug is a UI/gesture/reader symptom: get the actual app reproduction running first (server config + book open + the user's mode), capture the bug with a screen recording or before/after frames, apply the fix, repeat the same gesture, capture again, and only then call it done. Instrumentation tests are good as regression coverage AFTER the live verification, never as a substitute.
