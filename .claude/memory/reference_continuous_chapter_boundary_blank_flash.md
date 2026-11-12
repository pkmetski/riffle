---
name: continuous-chapter-boundary-blank-flash
description: "In continuous mode the previous chapter's trailing text vanishes mid-scroll at the boundary and reappears when scrolling back — root cause is Chromium's off-screen tile de-rasterization in stacked WebViews, not the sliding-window controller"
metadata: 
  node_type: memory
  type: reference
  originSessionId: cf0977b3-c381-4a49-a783-23dbd91c2c5c
---

Symptom: In Continuous reader mode, as the user scrolls across a chapter boundary, the trailing text of the previous chapter disappears earlier than it should, and re-appears when scrolling back the other way. Consistent, direction-dependent, repros on both AVD and physical devices.

**Not** a sliding-window bug. Verified with heavy `DEBUG-CONT` instrumentation on `ContinuousWindowController`: no shift, no landing snap, no reapply-landing, no scroll compensation fires during the disappearance window. Every WebView's Android `scrollY=0`, `translationY=0`, LinearLayout `top/bot` stable. JS-side probe of every visible chapter's `pageYOffset`, `body.getBoundingClientRect().{top,bottom}`, `documentElement.offsetHeight` — all stable. Chapter slot heights match rendered content exactly (`cssBodyBottom * dpr == slotHeight`).

Root cause: Chromium's WebView tile pipeline optimises for the single-WebView case. In our stacked-WebView continuous layout, a WebView that is only partially in view can have its off-screen tiles dropped/lazily re-rastered, so text at the top of viewport that scrolled up from the previous chapter is drawn from a re-rasterizing surface — brief blank until raster completes → the "disappeared" perception.

Fix: `WebSettingsCompat.setOffscreenPreRaster(settings, true)` on every `ChapterWebView` (feature-gated on `WebViewFeature.OFF_SCREEN_PRERASTER`). Added at `app/src/main/kotlin/com/riffle/app/feature/reader/ChapterWebView.kt` init block. Requires `androidx.webkit` dependency (public `WebView.setOffscreenPreRaster` was never made a public API — the AndroidX WebViewCompat surface is the officially supported entry point).

How to apply: When users report anything like "text disappears/flashes mid-scroll" in continuous mode, DO NOT re-instrument the sliding-window controller first — the answer is not there. Check whether the `setOffscreenPreRaster` line is still in `ChapterWebView.init`; if it's been removed or the feature check fails on their device, this is the mechanism.

Related: [[reference_continuous_empty_pages_scrollheight_clamp]], [[reference_continuous_annotation_focus_reflow_race]].
