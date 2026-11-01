---
name: reference-continuous-webview-16384-raster-cap
description: "Continuous-mode blank screen deep in a chapter = WebView laid out at full content height can't be drawn past the GPU max texture height (16384 px); fix = capped sliding WebView window inside a full-height slot; discrete re-centres jitter because View translation and Chromium scroll land in different frames"
metadata:
  node_type: memory
  type: reference
  originSessionId: fc4dde56-5ee2-4e50-a03c-46b5b4b9607b
  modified: 2026-09-25T13:01:27.276Z
---

Continuous mode (Android) blank screen when opening at a saved position deep in a long chapter (2026-09-25, branch `pkmetski/continuous-mode-text-cutoff`):

- **Root cause:** each `ChapterWebView` was laid out at its full content height (148 K–190 K px). The hardware compositor renders only the first `GL max texture/viewport` rows of a view — measured on the API-25 emulator as a hard cut at exactly **16 384 px into every chapter WebView** (DevTools element rect + screen row). Everything below is white, scrolling never helps. Same oversized views drove Chromium's "tile memory limits exceeded" on high-DPR phones. The "interest rect at internal scroll 0" and "tile budget / GONE strategy" theories were symptoms, not the cause. `setOffscreenPreRaster(false)` changed nothing.
- **Diagnostic recipe:** DevTools (`adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>`) `Runtime.evaluate` to prove DOM is intact + locate a paragraph's `getBoundingClientRect().top*dpr`, compare with the screen row where render stops. Emulator has no `screenrecord`; use `adb emu screenrecord start/stop file.webm` + ffmpeg frames + per-frame row cross-correlation to spot one-frame jumps.
- **Fix shape:** slot `FrameLayout` at full content height carries scroll geometry (`measuredHeights`, `buildWindow`); WebView capped via `ContinuousPositionTracker.chapterWebViewHeight` (3 viewports, ≤ `Canvas.maximumBitmapHeight` from `ContinuousReaderView.dispatchDraw`, ≥ 1 viewport); `syncChapterWindows` sets `translationY` + `wv.scrollTo(0, offset)` on every outer scroll (`chapterWebViewWindowOffset`).
- **Jitter trap:** the View translation applies in the current frame but Chromium applies the WebView's internal scroll a frame later. A discrete re-centre of Δ px therefore shows content displaced by Δ for one frame (600–950 px jumps in recordings). The window must **slide every frame** (uniform one-frame lag = smooth) and park (no lag) at chapter edges where a neighbour is visible.
- JS now runs with non-zero `window.scrollY`: selection progression must add `pageYOffset`; `window.find` sweeps must save/restore `scrollY`.
- iOS has no analogue: its Continuous mode is Readium-Swift's single viewport-sized WebView with internal scrolling.
