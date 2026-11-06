---
name: reference-test-avd-chrome55-webview
description: "The Android 7.1.1 (API 25) test AVD runs WebView Chrome 55 — volume page-turn ALWAYS fails there regardless of code; never diagnose volume-nav on the AVD, use a real device"
metadata: 
  node_type: memory
  type: reference
  originSessionId: d381325e-4c07-4da1-95f7-9ca6a720f684
---

The riffle test/harness AVD is Android 7.1.1 (API 25), whose System WebView is **Chrome 55** (2016, 55.0.2883.91). Modern DOM/JS APIs that landed later **silently no-op** there (no throw, no log): code looks correct but does nothing on the emulator, while working fine on a real/modern device.

This wrecked a full debugging session. Volume-key page-turn **always fails on this AVD regardless of the code**, in BOTH reader modes:
- **Paginated:** Readium's `goForward`/`goBackward` don't move the page on Chrome 55 (but work on real devices).
- **Scroll:** `window.scrollBy({top, behavior: 'smooth'})` (needs Chrome 61+) is a no-op on Chrome 55 (but smooth on real devices).

Because of these artifacts I "fixed" goForward (scrollLeft) and the smooth scroll (rAF), and even reverted PR #120 — all chasing AVD-only failures. The truth: the volume-nav code on the branch was **byte-identical to tag `v1.5.0`, which works on a real device**, so there was never a real regression. Everything was reverted; #120 stays in; branch left clean at origin/main.

**Rule: never diagnose or verify volume-key navigation (or any WebView scroll/paging) on the API-25 AVD — it lies. Build on a real/modern device.** Diffing the suspect code path against the last-known-good tag (`git diff v1.5.0 HEAD -- <files>`) is the fast way to prove "the code didn't change" before chasing a phantom. See [[reference_api25_avd_boot_incantation]].
