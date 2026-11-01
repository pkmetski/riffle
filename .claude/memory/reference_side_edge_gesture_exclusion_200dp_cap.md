---
name: reference_side_edge_gesture_exclusion_200dp_cap
description: Why PR
metadata: 
  node_type: memory
  type: reference
  originSessionId: e2e3e425-1b82-4595-af2c-097ee3e31241
---

Reader bug: a side-edge page-turn swipe in paged immersive mode reveals the system bars and exits immersive (the `topInset` 0→positive watcher in `rememberImmersiveModeState` fires `onBarsRestoredExternally()`). PR #148 tried to fix it with `View.setSystemGestureExclusionRects` (full-height side strips). It does NOT work, for TWO reasons (both confirmed on device):

1. **200dp cap.** `setSystemGestureExclusionRects` is capped at 200dp/edge; the cap is lifted ONLY while the nav bar is `SYSTEM_UI_FLAG_IMMERSIVE_STICKY`-hidden. The reader uses non-sticky `BEHAVIOR_DEFAULT` (`ImmersiveModeState.setBehaviorDefault`), so the strips were silently clipped to the bottom 200dp — mid-screen swipes unaffected.
2. **Exclusion governs the wrong thing.** Even uncapped (tested via sticky), the exclusion only stops the side-edge swipe from becoming a *back navigation*; it does NOT stop the OS from *flashing the system bars*. The bar-flash is what fires the watcher and exits immersive. So gesture exclusion can never fix this bug.

Switching to sticky (`BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`) to uncap the exclusion ALSO backfires: sticky auto-hides the transient bars without telling the app, desyncing `systemBarsHidden`, which breaks the toggle/bounce logic.

**Confirmed mechanism (API-33 AVD, gesture nav, instrumented):** a swipe *starting at the very screen edge* makes the OS flash the bars (topInset 0→136) → watcher → exit immersive. Swipes starting inside the content (x≈300) do NOT — so taps and normal page turns are unaffected.

**The fix that works (bounce-back, verified):** keep `BEHAVIOR_DEFAULT`; in `onBarsRestoredExternally()`, if `systemBarsHidden` is still true (we didn't request the reveal), call `controller.hide()` and stay immersive instead of exiting. A user tap-to-toggle clears `systemBarsHidden` first, so it's not bounced. On the AVD a side-edge swipe then stays immersive. TWO caveats: (a) it also suppresses the incidental top-edge swipe-down reveal (tap becomes the only way to show chrome) — conflicts slightly with the original "top/bottom swipes are fine" ask; (b) it does not stop a *full* back-gesture from popping the reader (synthetic `adb input swipe` from the edge always completes back; a real finger page-turn may not). A fully-correct fix would track touch-down X edge in `ScrollBoundaryNavigationContainer` and only bounce/exclude for side-edge-originated gestures.

AVD testability: existing Harness AVDs are API-25 (no gesture nav) — cannot repro. Made `Edge_Swipe_API33` from the installed `android-33;google_apis;arm64-v8a` image; `-gpu host -cores 4 -memory 4096` (software gfxstream ANRs the app). Enable gesture nav: `adb shell cmd overlay enable com.android.internal.systemui.navbar.gestural`. Drive the edge back-gesture with `adb shell input swipe 2 <y> 900 <y> 40` (fast, start x≈2). ABS dev creds auto-fill the Add-Server form (media-server resolves from the emulator). Old "use a real device" warning ([[reference_test_avd_chrome55_webview]]) was about WebView volume nav, not system gestures.
