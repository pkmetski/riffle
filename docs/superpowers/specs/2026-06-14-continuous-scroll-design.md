# Continuous Scroll Mode — Design Spec

**Date:** 2026-06-14  
**Branch:** pkmetski/endless-scroll-chapters  
**Status:** Approved

---

## Overview

Add a third reading orientation — **Continuous** — where the entire book renders as a single endless scroll. Both the end of one chapter and the start of the next are simultaneously visible on screen as the user crosses the boundary. This is distinct from the existing Scroll mode (which uses a pull gesture to navigate between discrete chapter WebViews).

---

## Architecture

### Mode switching

`ReaderOrientation` gains a third enum value: `Continuous`.

`FormattingPreferences.orientation` stores the selected mode. When `Continuous` is active:
- `EpubNavigatorFragment` is removed from the layout entirely
- A new `ContinuousReaderView` (a `NestedScrollView`-based custom view) takes its place in `EpubReaderScreen`
- Switching away from Continuous saves the current locator and re-creates `EpubNavigatorFragment` at that position

The existing Paged and Scroll modes are unchanged.

### ContinuousReaderView

A custom `NestedScrollView` subclass that stacks `ChapterWebView` items vertically. It is the sole scrollable container — no nested scrolling occurs.

At any time it holds a windowed pool of **3 `ChapterWebView`s**: the chapter before the current, the current chapter, and the chapter after. As the user scrolls past a chapter boundary:
- The far end `ChapterWebView` is detached and destroyed (WebViews are not pooled — the cost of creating a new one is acceptable given the 3-item window)
- A new `ChapterWebView` is created and appended at the advancing end

Chapter URLs are loaded from Readium's already-running local HTTP server — the same URLs `EpubNavigatorFragment` uses. No new serving infrastructure is required.

### ChapterWebView

A thin `WebView` wrapper with:
- `isScrollContainer = false` — internal scrolling disabled
- `layoutParams.height` set to its measured content height (see below)
- A loading placeholder of `3 × screen height` shown until height is known

### Height measurement

After each `ChapterWebView` page loads:
1. Inject ReadiumCSS + user preference CSS variables (see Style Injection below)
2. Wait for fonts: `document.fonts.ready`
3. Fire `requestAnimationFrame`, then read `document.body.scrollHeight`
4. Set `layoutParams.height` to the measured value and trigger a layout pass

When a formatting preference changes while in Continuous mode, all loaded `ChapterWebView`s re-inject styles and re-measure height.

---

## Style Injection

A new `ContinuousStyleInjector` is responsible for making each `ChapterWebView` render identically to the same chapter in Scroll mode.

### What gets injected

1. **ReadiumCSS stylesheets** — the base, default, and user layers that `EpubNavigatorFragment` injects. These are injected by auditing `EpubNavigatorFragment`'s `WebViewClient` and `evaluateJavascript` calls and mirroring them exactly. They may be injected as `<link>` tags pointing to Readium's HTTP server URLs or inlined as `<style>` blocks.

2. **CSS custom property overrides** — user formatting preferences mapped from `FormattingPreferences` → `EpubPreferences` → ReadiumCSS CSS variables (`--USER__fontSize`, `--USER__lineHeight`, etc.), applied via `evaluateJavascript`:
   ```js
   document.documentElement.style.setProperty('--USER__fontSize', '...');
   ```

   `ContinuousStyleInjector` mirrors `FormattingPreferencesMapper`: every preference the mapper sends to `EpubPreferences` gets a corresponding CSS variable entry here. Publisher CSS is served by Readium's HTTP server as part of the EPUB content and is automatically present — the ReadiumCSS layer mediates between publisher CSS and user preferences exactly as it does in Scroll/Paged mode.

**Out of scope v1:** custom app-asset fonts (complex to serve into self-managed WebViews, low relative impact).

---

## Position Tracking

The outer `NestedScrollView`'s `scrollY` is the source of truth. Each loaded `ChapterWebView` knows its `top` offset in the container and its measured `height`.

- **Current chapter**: whichever `ChapterWebView` contains `scrollY + viewportHeight / 2`
- **Progression within chapter**: `(scrollY - chapterTop) / chapterHeight`
- **Saved locator**: progression-based, same shape as the existing Scroll mode — no changes needed in `ProgressSweep` or ABS sync

On opening a book in Continuous mode, the saved locator's chapter is loaded as the center WebView of the initial pool, scrolled to `chapterTop + progression × chapterHeight`. The previous and next chapters are loaded above and below.

---

## UI Integration

### Settings

"Continuous" appears as a third chip/option in the orientation selector in the reader formatting panel, alongside "Paged" and "Scroll".

### TOC navigation

Jump to the target chapter's `ChapterWebView` (loading it into the window if needed), then scroll to `chapterTop + progression × chapterHeight`.

### Reader chrome

Top/bottom bars, the formatting sheet, and the progress bar observe the same state shape as today — no changes needed.

---

## Readaloud Highlight Sync

The existing auto-follow JS is routed to whichever `ChapterWebView` corresponds to the current audio position:

- The audio locator carries chapter href + progression
- `ContinuousReaderView` routes highlight injection to the matching `ChapterWebView`
- When readaloud crosses a chapter boundary, the highlight is cleared from the departing `ChapterWebView` and re-injected into the arriving one
- The chapter must be in the current window (within ±1 of current) — if it isn't (e.g. audio has raced ahead of the reading position), the highlight is suppressed rather than shifting the reading window, to avoid disrupting the user's scroll position

### Search result navigation

Search returns a locator (chapter + progression). `ContinuousReaderView`:
1. Loads the target chapter into the window
2. Scrolls to `chapterTop + progression × chapterHeight`
3. Highlights the matched term via `window.find()` injected as JS — bypassing Readium's decoration API, which assumes a single navigator WebView

---

## Out of Scope (v1)

- Text selection across a chapter boundary — requires merging content across two separate WebView contexts; not feasible without a custom selection protocol
- Custom app-asset font injection into self-managed WebViews

---

## Testing

- Unit tests for `ContinuousStyleInjector`: given `FormattingPreferences`, assert correct CSS variable output
- Unit tests for position math: `scrollY` → chapter index + progression, and the reverse (open-at-locator scroll offset)
- Unit tests for window shift logic: scrolling past a boundary drops the far chapter and loads the next
- Harness test (scroll mode): open a multi-chapter book in Continuous mode, scroll past two chapter boundaries, verify no gap/flash and that the progress indicator advances correctly
