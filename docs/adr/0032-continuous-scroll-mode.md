# ADR 0032 — Continuous scroll mode: self-managed fixed-height WebViews in a NestedScrollView

**Status:** Accepted

## Context

The existing Scroll orientation loads one chapter at a time in Readium's `EpubNavigatorFragment`. Chapter transitions require a deliberate 160 dp pull gesture, which triggers `goForward()`/`go(locator)` — a discrete jump with a brief visual flash masked by a loading cover. This model makes chapter boundaries perceptible and interrupts reading flow.

The desired behaviour is a third orientation — **Continuous** — where the book reads as a single endless scroll: the end of one chapter and the start of the next are simultaneously visible on screen, with no gesture threshold and no transition moment.

`EpubNavigatorFragment` renders exactly one resource at a time and exposes no API for showing adjacent resources simultaneously. Extending it to support this is not feasible without forking Readium.

## Decision

Add `Continuous` as a third value in `ReaderOrientation`. When active, `EpubNavigatorFragment` is replaced by a new `ContinuousReaderView` — a `NestedScrollView` subclass that stacks `ChapterWebView` instances vertically and is the sole scrollable container.

**ChapterWebViews** are thin `WebView` wrappers with internal scrolling disabled (`isScrollContainer = false`) and height fixed to their content height. Chapter URLs are loaded from Readium's already-running local HTTP server — the same URLs the navigator fragment uses; no new serving infrastructure is needed. At any time exactly three `ChapterWebView`s are live (previous, current, next); as the user crosses a chapter boundary the trailing one is destroyed and a new one is appended at the advancing end.

**Height measurement** occurs in three stages after each page loads: inject styles → await `document.fonts.ready` → `requestAnimationFrame` → read `document.body.scrollHeight`. A placeholder of 3× screen height reserves space until the real height is known. On a formatting-preference change all live `ChapterWebView`s re-inject and re-measure.

**Style injection** (`ContinuousStyleInjector`) replicates everything `EpubNavigatorFragment` injects into its managed WebView: the full ReadiumCSS stylesheet stack (base, default, and user layers) plus CSS custom property overrides derived from `FormattingPreferences` → `EpubPreferences` → `--USER__*` variables. Publisher CSS is served by Readium's HTTP server as part of the EPUB resources and requires no additional handling. The injector is derived by auditing `EpubNavigatorFragment`'s `WebViewClient` and `evaluateJavascript` calls to ensure rendering is identical to Scroll mode.

**Position tracking** uses the outer `NestedScrollView`'s `scrollY`. Current chapter = whichever `ChapterWebView` contains `scrollY + viewportHeight / 2`; progression = `(scrollY − chapterTop) / chapterHeight`. Saved locators are progression-based — the same shape as the existing Scroll mode — so `ProgressSweep` and ABS sync need no changes.

**Readaloud highlight sync**: auto-follow JS is routed to the `ChapterWebView` matching the current audio locator. When audio crosses a chapter boundary the highlight moves with it. If the audio position falls outside the ±1 window (audio raced ahead of the reading position), the highlight is suppressed rather than shifting the reading window.

**Search navigation**: `ContinuousReaderView` loads the target chapter into the window, scrolls to position, and highlights the matched term via `window.find()` — bypassing Readium's decoration API, which assumes a single navigator WebView.

## Consequences

- True simultaneous chapter visibility: the chapter boundary is a point in a continuous scroll, not a navigation event.
- Self-managed WebViews mean the app owns the style injection layer. Any ReadiumCSS update or new `FormattingPreferences` entry must be reflected in `ContinuousStyleInjector` as well as `FormattingPreferencesMapper`.
- Text selection across a chapter boundary is not supported (requires merging selection context across two independent WebView instances).
- Custom app-asset fonts are not injected in Continuous mode (v1); the EPUB's own declared fonts are used.
- Existing Paged and Scroll modes are unaffected.

## Alternatives considered

- **DOM-append: inject the next chapter's HTML into the current WebView.** Avoids multiple WebViews but breaks relative URLs (images, CSS) that resolve against the current chapter's base URL; drops each chapter's `<head>`; grows memory unboundedly. Rejected as too fragile.
- **Velocity handoff between three full Readium WebViews.** Each WebView scrolls normally within itself; remaining scroll velocity is passed to the adjacent WebView at the boundary. Does not achieve true simultaneous visibility — there is always a gap between the two WebViews' content at the transition point. Rejected because it doesn't meet the requirement.
