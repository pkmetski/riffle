---
name: ""
metadata: 
  node_type: memory
  originSessionId: f7a41fc1-f316-4c5d-934e-d2f2e31a02c8
---

The "page turns look like falling from above" on **chapter start** (worst on one-page chapters; on swipe AND volume; at default settings) is the **`margins` entry in `TypographyOverride.kt`**: it injected `padding-top: calc(--RS__pageGutter * --USER__pageMargins * 0.8)` (and a bottom) on `:root` via `typographyOverrideInjectionJs()` inside `paginationListener.onPageLoaded` — i.e. **after the WebView has already painted**. The page paints, then the `<style>` lands and `:root` padding-top goes 0→~16px, dropping the whole page by the top margin. It fires even at default margins because Readium sets `--USER__pageMargins` inline (=1), so the gate `:root[style*="--USER__pageMargins"]` matches.

PROVEN on device by an `onPageLoaded` rAF probe logging `getComputedStyle(:root).paddingTop` + heading `getBoundingClientRect().top` each frame: before fix `rootPt 0→16` and heading-top `23→39` at ~t=27ms with `scrollTop` constant 0 (layout reflow, NOT a scroll, NOT a font swap — `document.fonts` was already `loaded` at t=0). After fix both stay flat.

FIX (branch pkmetski/page-turn-falling-animation): delete the `margins` entry from `TYPOGRAPHY_OVERRIDES`, add `margins` to `EXCLUDED_FROM_TYPOGRAPHY_OVERRIDES`. Vertical margin is then owned entirely by Readium's pre-paint layout: left/right from its built-in body padding (still scales with `--USER__pageMargins`), top/bottom from its native container vertical padding (`readium_navigator_epub_vertical_padding`, 40dp port / 20dp land), applied before the page is revealed → no reflow. Trade-off: top/bottom margin no longer scales with the margins slider (left/right still does). Updated the contract test `margins_has_no_post_paint_root_padding_override`.

DEAD ENDS (don't repeat): (1) NOT Readium's native container vertical padding applied late — zeroing the dimen + static-padding the FragmentContainerView "looked the same" because the real cause is the in-WebView :root padding. (2) The fall is ~1 frame (~40ms); 24fps device recordings and emulator `screenrecord` (variable/low fps) both alias it away — sparse frame sampling MISSES it. To SEE it, extract adjacent native frames and stack tightly, or instrument computed style frame-by-frame. To MEASURE it, an `onPageLoaded` rAF probe read back via `evaluateJavascript` + Log.d (keep payload short — logcat truncates ~4000 chars).

Repro recipe used: AVD riffle_ra_hl_api33 (API33, WebView 109) on port 5602; ABS bridge = host `127.0.0.1:13378` already proxied to media-server + `adb reverse tcp:13378 tcp:13378`, app server URL `http://127.0.0.1:13378` test/test; book "Приказки от хиляда и една нощ" (id 471ab948-…, Books library) has many one-page tale chapters; volume key 25 = page turn (works on WebView 109, unlike the Chrome-55 harness AVD).
