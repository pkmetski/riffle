# Continuous Scroll Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "Continuous" reading orientation where the full book renders as a single endless scroll, with multiple chapters simultaneously visible on screen.

**Architecture:** A new `ContinuousReaderView` (`NestedScrollView` subclass) stacks up to 3 `ChapterWebView` instances (fixed height, internal scrolling disabled) loaded from Readium's already-running HTTP server. An invisible zero-height `EpubNavigatorFragment` stays alive to keep the HTTP server running and provide the base URL. A `ContinuousStyleInjector` replicates what Readium's fragment injects on page load (CSS variables + TypographyOverride). Position is tracked as `scrollY → chapterHref + progression` and emitted to the existing `EpubReaderViewModel.onPositionChanged` pipeline.

**Tech Stack:** Kotlin, Android WebView (`addJavascriptInterface`, `evaluateJavascript`), Android `NestedScrollView`, Readium Kotlin SDK (`EpubNavigatorFragment`, `Publication.readingOrder`), Jetpack Compose `AndroidView`, Coroutines, JUnit 4 (project standard)

---

## File Map

**New files:**
- `app/src/main/kotlin/com/riffle/app/feature/reader/ContinuousStyleInjector.kt` — CSS variable + TypographyOverride JS injection for self-managed WebViews; pure, unit-testable
- `app/src/main/kotlin/com/riffle/app/feature/reader/ContinuousPositionTracker.kt` — maps `scrollY + chapter heights` to `href + progression` and vice versa; pure math, unit-testable
- `app/src/main/kotlin/com/riffle/app/feature/reader/ChapterWebView.kt` — thin `WebView` wrapper: disabled internal scroll, JS-driven height measurement via `JavascriptInterface`, placeholder height until measured
- `app/src/main/kotlin/com/riffle/app/feature/reader/ContinuousReaderView.kt` — `NestedScrollView` that owns a windowed pool of 3 `ChapterWebView`s; handles window shifting, position emission, TOC navigation, search navigation, and readaloud highlight routing
- `app/src/test/kotlin/com/riffle/app/feature/reader/ContinuousStyleInjectorTest.kt`
- `app/src/test/kotlin/com/riffle/app/feature/reader/ContinuousPositionTrackerTest.kt`

**Modified files:**
- `core/domain/src/main/kotlin/com/riffle/core/domain/FormattingPreferences.kt` — add `Continuous` to `ReaderOrientation`
- `app/src/main/kotlin/com/riffle/app/feature/reader/FormattingPanel.kt` — add "Continuous" chip to the orientation row
- `app/src/main/kotlin/com/riffle/app/feature/reader/FormattingPreferencesMapper.kt` — handle `Continuous` in `toEpubPreferences` and `toFragmentConfiguration`
- `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt` — branch on `Continuous`: keep invisible fragment, extract base URL, show `ContinuousReaderView`, wire readaloud + search

---

## Task 1: Add `Continuous` to `ReaderOrientation` and update the formatting panel + mapper

**Files:**
- Modify: `core/domain/src/main/kotlin/com/riffle/core/domain/FormattingPreferences.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/FormattingPanel.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/FormattingPreferencesMapper.kt`

- [ ] **Step 1: Add `Continuous` to the enum**

In `core/domain/src/main/kotlin/com/riffle/core/domain/FormattingPreferences.kt`, line 36:

```kotlin
enum class ReaderOrientation { Horizontal, Vertical, Continuous }
```

- [ ] **Step 2: Add "Continuous" chip to the formatting panel**

In `app/src/main/kotlin/com/riffle/app/feature/reader/FormattingPanel.kt`, find the `when (orientation)` label map inside the `ReaderOrientation.entries.forEach` loop (around line 270) and add the new case:

```kotlin
val label = when (orientation) {
    ReaderOrientation.Horizontal -> "Paginated"
    ReaderOrientation.Vertical -> "Scroll"
    ReaderOrientation.Continuous -> "Continuous"
}
```

- [ ] **Step 3: Update `toEpubPreferences` — treat Continuous like Vertical for the hidden fragment**

In `FormattingPreferencesMapper.kt`, the invisible fragment we keep alive in Continuous mode needs valid scroll-mode preferences. Change line 27 and 60:

```kotlin
// Line 27 — isDoublePage guard
val isDoublePage = orientation == ReaderOrientation.Horizontal && doublePageSpread && isLandscape

// Line 60 — scroll flag
scroll = orientation != ReaderOrientation.Horizontal,
```

- [ ] **Step 4: Update `toFragmentConfiguration` — Continuous uses no column layout**

In `FormattingPreferencesMapper.kt` around line 96, the `when` in `readiumCssRsProperties` currently branches on `orientation != ReaderOrientation.Vertical`. Change it to also exclude `Continuous`:

```kotlin
readiumCssRsProperties = when {
    isDoublePage -> RsProperties(
        colCount = ColCount.TWO,
        overrides = mapOf("--RS__colWidth" to "auto"),
    )
    !isFixedLayout && orientation == ReaderOrientation.Horizontal ->
        RsProperties(colCount = ColCount.ONE)
    else -> RsProperties()
},
```

- [ ] **Step 5: Build to verify no exhaustive-`when` compile errors**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug 2>&1 | grep -E "error:|warning:|BUILD"
```

Expected: `BUILD SUCCESSFUL` — no unresolved `when` branches.

- [ ] **Step 6: Commit**

```bash
git add core/domain/src/main/kotlin/com/riffle/core/domain/FormattingPreferences.kt \
        app/src/main/kotlin/com/riffle/app/feature/reader/FormattingPanel.kt \
        app/src/main/kotlin/com/riffle/app/feature/reader/FormattingPreferencesMapper.kt
git commit -m "feat(reader): add Continuous to ReaderOrientation enum, panel, and mapper"
```

---

## Task 2: `ContinuousStyleInjector` — replicate Readium's page-load injections

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/reader/ContinuousStyleInjector.kt`
- Create: `app/src/test/kotlin/com/riffle/app/feature/reader/ContinuousStyleInjectorTest.kt`

- [ ] **Step 1: Audit what Readium's fragment sets on page load**

Before writing any code, open the app in Scroll mode on the Harness AVD or your device. Open WebView DevTools (`adb forward tcp:9222 localabstract:chrome_devtools_remote`, then `chrome://inspect`). In the console of a loaded chapter, run:

```js
// What CSS vars are set on :root?
JSON.stringify(
  Array.from({ length: document.documentElement.style.length }, (_, i) =>
    document.documentElement.style.item(i)
  ).map(p => [p, document.documentElement.style.getPropertyValue(p)])
)
// What classes does <html> have?
document.documentElement.getAttribute('class')
```

Record the output — this is exactly what `ContinuousStyleInjector.buildVariableInjectionJs()` must reproduce for a given `FormattingPreferences`. Also check if ReadiumCSS `<link>` tags are present:

```js
[...document.querySelectorAll('link[rel=stylesheet]')].map(l => l.href)
```

If ReadiumCSS stylesheets appear in this list, the HTTP server is already injecting them — our injector only needs to set variables (the common case). If not, add a step to inject them as `<style>` blocks from Readium's assets.

- [ ] **Step 2: Write the failing test**

Create `app/src/test/kotlin/com/riffle/app/feature/reader/ContinuousStyleInjectorTest.kt`:

```kotlin
package com.riffle.app.feature.reader

import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderFontFamily
import com.riffle.core.domain.ReaderTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuousStyleInjectorTest {

    @Test
    fun `default prefs — fontSize 1rem, pageMargins 1, no lineHeight variable`() {
        val js = ContinuousStyleInjector.buildVariableInjectionJs(FormattingPreferences())
        assertTrue("fontSize", js.contains("--USER__fontSize', '1.0rem'"))
        assertTrue("pageMargins", js.contains("--USER__pageMargins', '1.0'"))
        // lineSpacing == DEFAULT (1.2f) → variable must NOT be set
        assertFalse("lineHeight not set on default", js.contains("setProperty('--USER__lineHeight'"))
        assertTrue("lineHeight removed on default", js.contains("removeProperty('--USER__lineHeight'"))
    }

    @Test
    fun `non-default lineSpacing sets --USER__lineHeight`() {
        val js = ContinuousStyleInjector.buildVariableInjectionJs(
            FormattingPreferences(lineSpacing = 1.6f)
        )
        assertTrue(js.contains("setProperty('--USER__lineHeight', '1.6'"))
    }

    @Test
    fun `justifyText sets --USER__textAlign to justify`() {
        val js = ContinuousStyleInjector.buildVariableInjectionJs(
            FormattingPreferences(justifyText = true)
        )
        assertTrue(js.contains("setProperty('--USER__textAlign', 'justify'"))
    }

    @Test
    fun `non-justify removes --USER__textAlign`() {
        val js = ContinuousStyleInjector.buildVariableInjectionJs(
            FormattingPreferences(justifyText = false)
        )
        assertTrue(js.contains("removeProperty('--USER__textAlign'"))
    }

    @Test
    fun `Serif font family (default) — no fontFamily variable set`() {
        val js = ContinuousStyleInjector.buildVariableInjectionJs(
            FormattingPreferences(fontFamily = ReaderFontFamily.Serif)
        )
        assertFalse(js.contains("setProperty('--USER__fontFamily'"))
        assertTrue(js.contains("removeProperty('--USER__fontFamily'"))
    }

    @Test
    fun `SansSerif family sets sans-serif`() {
        val js = ContinuousStyleInjector.buildVariableInjectionJs(
            FormattingPreferences(fontFamily = ReaderFontFamily.SansSerif)
        )
        assertTrue(js.contains("setProperty('--USER__fontFamily', 'sans-serif'"))
    }

    @Test
    fun `Dark theme — textColor NOT set (DarkDim only)`() {
        val js = ContinuousStyleInjector.buildVariableInjectionJs(
            FormattingPreferences(theme = ReaderTheme.Dark)
        )
        assertFalse(js.contains("setProperty('--USER__textColor'"))
    }

    @Test
    fun `DarkDim theme — textColor set to AAAAAA`() {
        val js = ContinuousStyleInjector.buildVariableInjectionJs(
            FormattingPreferences(theme = ReaderTheme.DarkDim)
        )
        assertTrue(js.contains("setProperty('--USER__textColor', '#AAAAAA'"))
    }
}
```

- [ ] **Step 3: Run test to confirm it fails**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.reader.ContinuousStyleInjectorTest" 2>&1 | tail -10
```

Expected: `FAILED` — `ContinuousStyleInjector` doesn't exist yet.

- [ ] **Step 4: Implement `ContinuousStyleInjector`**

Create `app/src/main/kotlin/com/riffle/app/feature/reader/ContinuousStyleInjector.kt`:

```kotlin
package com.riffle.app.feature.reader

import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderFontFamily
import com.riffle.core.domain.ReaderTheme

internal object ContinuousStyleInjector {

    /**
     * Produces JS that sets the same `--USER__*` CSS custom properties on `:root` that
     * Readium's `EpubNavigatorFragment` sets via `submitPreferences()`. The null-gating
     * logic mirrors [FormattingPreferencesMapper.toEpubPreferences]: when a value equals
     * the default, Readium passes null — leaving the variable unset so publisher defaults
     * are preserved. We match that behaviour with `removeProperty`.
     *
     * NOTE: verify after any Readium SDK upgrade that the variable names and value formats
     * still match. Run DevTools on a Scroll-mode chapter and compare with the output of
     * this function for the same [FormattingPreferences].
     */
    fun buildVariableInjectionJs(prefs: FormattingPreferences): String {
        val lines = mutableListOf<String>()
        val r = "document.documentElement.style"

        // fontSize — always set (Readium always passes it as a Double)
        lines += "$r.setProperty('--USER__fontSize', '${prefs.fontSize}rem');"

        // lineHeight — null-gated (matches FormattingPreferencesMapper: null when == default)
        if (prefs.lineSpacing != FormattingPreferences.DEFAULT_LINE_SPACING) {
            lines += "$r.setProperty('--USER__lineHeight', '${prefs.lineSpacing}');"
        } else {
            lines += "$r.removeProperty('--USER__lineHeight');"
        }

        // pageMargins — always set
        lines += "$r.setProperty('--USER__pageMargins', '${prefs.margins}');"

        // textAlign — null-gated
        if (prefs.justifyText) {
            lines += "$r.setProperty('--USER__textAlign', 'justify');"
        } else {
            lines += "$r.removeProperty('--USER__textAlign');"
        }

        // fontFamily — null-gated (Serif is default → no variable, matching mapper)
        val fontFamilyValue = when (prefs.fontFamily) {
            ReaderFontFamily.Serif -> null
            ReaderFontFamily.SansSerif -> "sans-serif"
            ReaderFontFamily.Monospace -> "monospace"
            ReaderFontFamily.Literata -> "Literata"
            ReaderFontFamily.Merriweather -> "Merriweather"
            ReaderFontFamily.OpenDyslexic -> "OpenDyslexic"
        }
        if (fontFamilyValue != null) {
            lines += "$r.setProperty('--USER__fontFamily', '$fontFamilyValue');"
        } else {
            lines += "$r.removeProperty('--USER__fontFamily');"
        }

        // textColor — DarkDim only. 0xFFAAAAAA = ReaderThemePalette.DARK_DIM_TEXT
        if (prefs.theme == ReaderTheme.DarkDim) {
            lines += "$r.setProperty('--USER__textColor', '#AAAAAA');"
        } else {
            lines += "$r.removeProperty('--USER__textColor');"
        }

        return lines.joinToString("\n")
    }

    /**
     * JS that fires after fonts load + one rAF and calls `window.RiffleChapter.onHeightMeasured`
     * with `document.body.scrollHeight`. Requires the calling [ChapterWebView] to have
     * registered a `JavascriptInterface` named `RiffleChapter`.
     */
    const val HEIGHT_MEASUREMENT_JS = """
        document.fonts.ready.then(function() {
            requestAnimationFrame(function() {
                window.RiffleChapter.onHeightMeasured(document.body.scrollHeight);
            });
        });
    """

    /**
     * JS that highlights [escapedText] via `window.find` + DOM `<mark>` injection, replacing
     * any existing mark with id `_riffle_hl`. Pass an empty string to clear.
     */
    fun highlightTextJs(escapedText: String): String {
        if (escapedText.isBlank()) return CLEAR_HIGHLIGHT_JS
        return """
            (function() {
                var existing = document.getElementById('_riffle_hl');
                if (existing) { existing.outerHTML = existing.innerHTML; }
                if (!window.find('$escapedText', false, false, false, false, false, false)) return;
                var sel = window.getSelection();
                if (!sel || sel.rangeCount === 0) return;
                var range = sel.getRangeAt(0);
                var mark = document.createElement('mark');
                mark.id = '_riffle_hl';
                mark.style.cssText = 'background:#7DD3FC;color:inherit;';
                try { range.surroundContents(mark); } catch(e) {}
                sel.removeAllRanges();
            })();
        """.trimIndent()
    }

    const val CLEAR_HIGHLIGHT_JS = """
        (function() {
            var m = document.getElementById('_riffle_hl');
            if (m) m.outerHTML = m.innerHTML;
        })();
    """
}
```

- [ ] **Step 5: Run tests to confirm they pass**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.reader.ContinuousStyleInjectorTest" 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`, all tests `PASSED`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/ContinuousStyleInjector.kt \
        app/src/test/kotlin/com/riffle/app/feature/reader/ContinuousStyleInjectorTest.kt
git commit -m "feat(reader): add ContinuousStyleInjector for CSS variable injection in self-managed WebViews"
```

---

## Task 3: `ContinuousPositionTracker` — pure position math

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/reader/ContinuousPositionTracker.kt`
- Create: `app/src/test/kotlin/com/riffle/app/feature/reader/ContinuousPositionTrackerTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/riffle/app/feature/reader/ContinuousPositionTrackerTest.kt`:

```kotlin
package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContinuousPositionTrackerTest {

    // Window: chapter A = height 1000, top 0; chapter B = height 500, top 1000
    private val window = listOf(
        ContinuousPositionTracker.ChapterSlot("A.xhtml", top = 0, height = 1000),
        ContinuousPositionTracker.ChapterSlot("B.xhtml", top = 1000, height = 500),
    )

    @Test
    fun `scrollY 0 with viewport 800 — midY 400 is in chapter A, progression 0_4`() {
        val (href, prog) = ContinuousPositionTracker.locatorAt(
            scrollY = 0, viewportHeight = 800, window = window
        )
        assertEquals("A.xhtml", href)
        assertEquals(0.4f, prog, 0.001f)
    }

    @Test
    fun `scrollY 1000 with viewport 800 — midY 1400 is in chapter B, progression 0_8`() {
        val (href, prog) = ContinuousPositionTracker.locatorAt(
            scrollY = 1000, viewportHeight = 800, window = window
        )
        assertEquals("B.xhtml", href)
        assertEquals(0.8f, prog, 0.001f)
    }

    @Test
    fun `scrollY past all chapters — clamps to last chapter`() {
        val (href, prog) = ContinuousPositionTracker.locatorAt(
            scrollY = 5000, viewportHeight = 800, window = window
        )
        assertEquals("B.xhtml", href)
        assertEquals(1.0f, prog.coerceAtMost(1.0f), 0.001f)
    }

    @Test
    fun `scrollOffsetFor returns correct offset for chapter B at progression 0_5`() {
        val offset = ContinuousPositionTracker.scrollOffsetFor(
            href = "B.xhtml", progression = 0.5f, window = window
        )
        // top=1000 + 0.5*500 = 1250; then subtract half viewport so it centres — but this
        // function returns raw content offset (caller subtracts viewport/2 if desired)
        assertEquals(1250, offset)
    }

    @Test
    fun `scrollOffsetFor returns null when chapter not in window`() {
        val offset = ContinuousPositionTracker.scrollOffsetFor(
            href = "C.xhtml", progression = 0.0f, window = window
        )
        assertNull(offset)
    }

    @Test
    fun `windowShiftNeeded — current index below topIndex triggers backward shift`() {
        // window covers chapters [2,3,4], topIndex=2
        assertEquals(
            ContinuousPositionTracker.ShiftDirection.BACKWARD,
            ContinuousPositionTracker.shiftNeeded(currentChapterIndex = 1, topIndex = 2, readingOrderSize = 5)
        )
    }

    @Test
    fun `windowShiftNeeded — current index above topIndex+2 triggers forward shift`() {
        assertEquals(
            ContinuousPositionTracker.ShiftDirection.FORWARD,
            ContinuousPositionTracker.shiftNeeded(currentChapterIndex = 3, topIndex = 2, readingOrderSize = 5)
        )
    }

    @Test
    fun `windowShiftNeeded — current within window, no shift`() {
        assertEquals(
            ContinuousPositionTracker.ShiftDirection.NONE,
            ContinuousPositionTracker.shiftNeeded(currentChapterIndex = 3, topIndex = 2, readingOrderSize = 5)
        ).also { }
        // Actually currentChapterIndex=3, topIndex=2 → covers [2,3,4], index 3 is inside → NONE
    }

    @Test
    fun `shiftNeeded NONE when at first chapter and cannot go backward`() {
        assertEquals(
            ContinuousPositionTracker.ShiftDirection.NONE,
            ContinuousPositionTracker.shiftNeeded(currentChapterIndex = 0, topIndex = 0, readingOrderSize = 3)
        )
    }
}
```

> Note: fix the `windowShiftNeeded NONE` test assertion — it has a doubled `also {}`. Remove the spurious block:
> ```kotlin
> assertEquals(NONE, shiftNeeded(3, 2, 5))
> ```

- [ ] **Step 2: Run test to confirm failure**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.reader.ContinuousPositionTrackerTest" 2>&1 | tail -10
```

Expected: `FAILED`.

- [ ] **Step 3: Implement `ContinuousPositionTracker`**

Create `app/src/main/kotlin/com/riffle/app/feature/reader/ContinuousPositionTracker.kt`:

```kotlin
package com.riffle.app.feature.reader

internal object ContinuousPositionTracker {

    data class ChapterSlot(val href: String, val top: Int, val height: Int)

    enum class ShiftDirection { NONE, FORWARD, BACKWARD }

    /**
     * Returns the chapter href and within-chapter progression (0..1) at the viewport midpoint.
     * Falls back to the last slot if [scrollY] is past all content.
     */
    fun locatorAt(scrollY: Int, viewportHeight: Int, window: List<ChapterSlot>): Pair<String, Float> {
        val midY = scrollY + viewportHeight / 2
        val slot = window.lastOrNull { midY >= it.top } ?: window.first()
        val progression = if (slot.height > 0) {
            ((midY - slot.top).toFloat() / slot.height).coerceIn(0f, 1f)
        } else {
            0f
        }
        return slot.href to progression
    }

    /**
     * Returns the content offset (from the top of the scroll view) for a given
     * chapter + progression. Returns null if [href] is not in [window].
     */
    fun scrollOffsetFor(href: String, progression: Float, window: List<ChapterSlot>): Int? {
        val slot = window.firstOrNull { it.href == href } ?: return null
        return (slot.top + progression * slot.height).toInt()
    }

    /**
     * Indicates whether the window (3 chapters: [topIndex, topIndex+2]) needs to shift
     * because [currentChapterIndex] has moved outside it.
     */
    fun shiftNeeded(currentChapterIndex: Int, topIndex: Int, readingOrderSize: Int): ShiftDirection {
        return when {
            currentChapterIndex < topIndex && topIndex > 0 -> ShiftDirection.BACKWARD
            currentChapterIndex > topIndex + 2 && topIndex + 3 < readingOrderSize -> ShiftDirection.FORWARD
            else -> ShiftDirection.NONE
        }
    }
}
```

- [ ] **Step 4: Fix the test — remove spurious `also {}`**

In `ContinuousPositionTrackerTest`, replace:

```kotlin
    @Test
    fun `windowShiftNeeded — current within window, no shift`() {
        assertEquals(
            ContinuousPositionTracker.ShiftDirection.NONE,
            ContinuousPositionTracker.shiftNeeded(currentChapterIndex = 3, topIndex = 2, readingOrderSize = 5)
        ).also { }
        // Actually currentChapterIndex=3, topIndex=2 → covers [2,3,4], index 3 is inside → NONE
    }
```

with:

```kotlin
    @Test
    fun `shiftNeeded — current within window returns NONE`() {
        assertEquals(
            ContinuousPositionTracker.ShiftDirection.NONE,
            ContinuousPositionTracker.shiftNeeded(currentChapterIndex = 3, topIndex = 2, readingOrderSize = 5)
        )
    }
```

- [ ] **Step 5: Run tests to confirm they pass**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.reader.ContinuousPositionTrackerTest" 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`, all tests `PASSED`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/ContinuousPositionTracker.kt \
        app/src/test/kotlin/com/riffle/app/feature/reader/ContinuousPositionTrackerTest.kt
git commit -m "feat(reader): add ContinuousPositionTracker for scrollY ↔ chapter+progression math"
```

---

## Task 4: `ChapterWebView` — WebView wrapper with disabled scroll and JS height measurement

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/reader/ChapterWebView.kt`

No unit tests — `WebView` is not usable in JVM tests. Tested as part of integration via Task 5 and device testing.

- [ ] **Step 1: Implement `ChapterWebView`**

Create `app/src/main/kotlin/com/riffle/app/feature/reader/ChapterWebView.kt`:

```kotlin
package com.riffle.app.feature.reader

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * A [WebView] that:
 * - Has internal scrolling disabled so the parent [ContinuousReaderView] owns all scroll.
 * - Measures its content height via a [JavascriptInterface] callback after fonts load.
 * - Injects CSS variables and the TypographyOverride stylesheet on page load.
 *
 * Set [onHeightMeasured] before calling [loadChapter]. Height arrives asynchronously on
 * the main thread after [ContinuousStyleInjector.HEIGHT_MEASUREMENT_JS] fires.
 */
@SuppressLint("SetJavaScriptEnabled")
internal class ChapterWebView(context: Context) : WebView(context) {

    /** Called on the main thread once the content height is known. */
    var onHeightMeasured: ((heightPx: Int) -> Unit)? = null

    /** Called on the main thread once the page finishes loading (before height is known). */
    var onPageFinished: (() -> Unit)? = null

    /** The chapter href this view is currently loading (e.g. `"EPUB/chapter01.xhtml"`). */
    var chapterHref: String = ""
        private set

    init {
        isScrollContainer = false
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        settings.javaScriptEnabled = true
        addJavascriptInterface(HeightBridge(), "RiffleChapter")
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                this@ChapterWebView.onPageFinished?.invoke()
            }
        }
    }

    /**
     * Load [chapterUrl] (absolute URL from Readium's HTTP server) for chapter at [href].
     */
    fun loadChapter(href: String, chapterUrl: String) {
        chapterHref = href
        loadUrl(chapterUrl)
    }

    /**
     * Inject style variables + TypographyOverride + trigger height measurement.
     * Call this from [onPageFinished] after Readium's server has served the page.
     *
     * @param variableJs output of [ContinuousStyleInjector.buildVariableInjectionJs]
     */
    fun injectStylesAndMeasure(variableJs: String) {
        evaluateJavascript(variableJs, null)
        evaluateJavascript(typographyOverrideInjectionJs(), null)
        evaluateJavascript(ContinuousStyleInjector.HEIGHT_MEASUREMENT_JS, null)
    }

    /** Re-measure after a preference change. Call [injectStylesAndMeasure] with updated vars. */
    fun reinjectAndRemeasure(variableJs: String) = injectStylesAndMeasure(variableJs)

    private inner class HeightBridge {
        @JavascriptInterface
        fun onHeightMeasured(height: Int) {
            post { this@ChapterWebView.onHeightMeasured?.invoke(height) }
        }
    }
}
```

- [ ] **Step 2: Build to confirm it compiles**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug 2>&1 | grep -E "error:|BUILD"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/ChapterWebView.kt
git commit -m "feat(reader): add ChapterWebView with disabled scroll and JS height measurement bridge"
```

---

## Task 5: `ContinuousReaderView` — windowed NestedScrollView

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/reader/ContinuousReaderView.kt`

- [ ] **Step 1: Implement `ContinuousReaderView`**

Create `app/src/main/kotlin/com/riffle/app/feature/reader/ContinuousReaderView.kt`:

```kotlin
package com.riffle.app.feature.reader

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import androidx.core.widget.NestedScrollView
import com.riffle.core.domain.FormattingPreferences
import org.readium.r2.shared.publication.Link

/**
 * Renders the entire book as a single vertical scroll by stacking a sliding window of 3
 * [ChapterWebView]s (previous, current, next) inside a [LinearLayout].
 *
 * Scrolling is owned entirely by this [NestedScrollView]; each [ChapterWebView] has its
 * internal scrolling disabled and its height fixed to its measured content height.
 *
 * Window shifting: when [currentChapterIndex] advances past the bottom chapter or retreats
 * past the top chapter, the far end is destroyed and a new chapter is added at the other end.
 * Adding a chapter at the TOP adjusts [scrollY] by the new chapter's height to keep the
 * visible content stable.
 */
internal class ContinuousReaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : NestedScrollView(context, attrs) {

    data class ChapterEntry(val link: Link, val url: String)

    /** Called on main thread when position changes; supplies `href` and `progression`. */
    var onPositionChanged: ((href: String, progression: Float) -> Unit)? = null

    private val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    /** All chapters in reading order. Set once via [initialize]. */
    private var allChapters: List<ChapterEntry> = emptyList()

    /** Current formatting preferences for CSS injection. */
    private var formattingPrefs: FormattingPreferences = FormattingPreferences()

    /**
     * Index into [allChapters] of the topmost loaded chapter.
     * The window covers [topIndex, topIndex+1, topIndex+2] (clamped to list bounds).
     */
    private var topIndex: Int = 0

    /** Parallel list to the loaded WebViews; index i matches container.getChildAt(i). */
    private val webViews = mutableListOf<ChapterWebView>()

    /** Measured content heights for each WebView in the current window. */
    private val measuredHeights = mutableListOf<Int>()

    /** Placeholder height (3× screen height) used before real measurement arrives. */
    private val placeholderHeight: Int get() = resources.displayMetrics.heightPixels * 3

    init {
        addView(container, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setOnScrollChangeListener { _, _, scrollY, _, _ ->
            handleScrollChange(scrollY)
        }
    }

    /**
     * Initialize the view at [initialHref] + [initialProgression].
     * Call once after attaching to the window.
     */
    fun initialize(
        chapters: List<ChapterEntry>,
        prefs: FormattingPreferences,
        initialHref: String,
        initialProgression: Float,
    ) {
        allChapters = chapters
        formattingPrefs = prefs
        val centerIndex = chapters.indexOfFirst { it.link.href.toString() == initialHref }
            .coerceAtLeast(0)
        topIndex = (centerIndex - 1).coerceAtLeast(0)
        val windowSize = minOf(3, chapters.size - topIndex)
        repeat(windowSize) { i -> appendChapter(topIndex + i) }
        // Scroll to initial position after the first height measurement settles.
        // We post to let the first layout pass happen.
        post {
            val window = buildWindow()
            val offset = ContinuousPositionTracker.scrollOffsetFor(initialHref, initialProgression, window)
            if (offset != null) scrollTo(0, (offset - height / 2).coerceAtLeast(0))
        }
    }

    /** Update preferences and re-inject styles + remeasure all loaded chapters. */
    fun updatePreferences(prefs: FormattingPreferences) {
        formattingPrefs = prefs
        val variableJs = ContinuousStyleInjector.buildVariableInjectionJs(prefs)
        webViews.forEachIndexed { i, wv ->
            wv.reinjectAndRemeasure(variableJs)
        }
    }

    /** Scroll to [href] at [progression]. Loads the chapter into the window if needed. */
    fun navigateTo(href: String, progression: Float) {
        val targetIndex = allChapters.indexOfFirst { it.link.href.toString() == href }
        if (targetIndex < 0) return
        // Shift window so target is in range
        if (targetIndex < topIndex || targetIndex > topIndex + 2) {
            rebuildWindowAround(targetIndex)
        }
        post {
            val window = buildWindow()
            val offset = ContinuousPositionTracker.scrollOffsetFor(href, progression, window)
            if (offset != null) smoothScrollTo(0, (offset - height / 2).coerceAtLeast(0))
        }
    }

    /** Inject a highlight for [escapedText] in the chapter matching [href]. Clear if blank. */
    fun highlightInChapter(href: String, escapedText: String) {
        val i = webViewIndexFor(href) ?: return
        webViews[i].evaluateJavascript(ContinuousStyleInjector.highlightTextJs(escapedText), null)
    }

    /** Clear any active highlight in the chapter at [href]. */
    fun clearHighlightInChapter(href: String) {
        val i = webViewIndexFor(href) ?: return
        webViews[i].evaluateJavascript(ContinuousStyleInjector.CLEAR_HIGHLIGHT_JS, null)
    }

    // ── private ────────────────────────────────────────────────────────────────

    private fun appendChapter(index: Int) {
        val entry = allChapters[index]
        val wv = ChapterWebView(context)
        val placeholder = placeholderHeight
        wv.onHeightMeasured = { measuredPx ->
            val i = webViews.indexOf(wv)
            if (i >= 0) {
                val wasPlaceholder = measuredHeights[i] == placeholder
                val delta = measuredPx - measuredHeights[i]
                measuredHeights[i] = measuredPx
                wv.layoutParams = wv.layoutParams.also { it.height = measuredPx }
                if (wasPlaceholder && i == 0 && scrollY > 0) {
                    // Chapter was prepended above the viewport — adjust scroll to keep visible
                    // content in place. (Real height differs from placeholder.)
                    scrollBy(0, delta)
                }
            }
        }
        wv.onPageFinished = {
            val variableJs = ContinuousStyleInjector.buildVariableInjectionJs(formattingPrefs)
            wv.injectStylesAndMeasure(variableJs)
        }
        webViews.add(wv)
        measuredHeights.add(placeholder)
        container.addView(wv, LinearLayout.LayoutParams(MATCH_PARENT, placeholder))
        wv.loadChapter(entry.link.href.toString(), entry.url)
    }

    private fun prependChapter(index: Int) {
        val entry = allChapters[index]
        val wv = ChapterWebView(context)
        val placeholder = placeholderHeight
        wv.onHeightMeasured = { measuredPx ->
            val i = webViews.indexOf(wv)
            if (i >= 0) {
                val delta = measuredPx - measuredHeights[i]
                measuredHeights[i] = measuredPx
                wv.layoutParams = wv.layoutParams.also { it.height = measuredPx }
                // Always adjust scroll when prepending — this chapter is above the viewport.
                scrollBy(0, delta)
            }
        }
        wv.onPageFinished = {
            val variableJs = ContinuousStyleInjector.buildVariableInjectionJs(formattingPrefs)
            wv.injectStylesAndMeasure(variableJs)
        }
        webViews.add(0, wv)
        measuredHeights.add(0, placeholder)
        container.addView(wv, 0, LinearLayout.LayoutParams(MATCH_PARENT, placeholder))
        // Compensate scroll immediately for the placeholder height so visible content doesn't jump.
        scrollBy(0, placeholder)
        wv.loadChapter(entry.link.href.toString(), entry.url)
    }

    private fun removeTop() {
        if (webViews.isEmpty()) return
        val h = measuredHeights.removeAt(0)
        val wv = webViews.removeAt(0)
        container.removeView(wv)
        wv.destroy()
        scrollBy(0, -h)
        topIndex++
    }

    private fun removeBottom() {
        if (webViews.isEmpty()) return
        measuredHeights.removeAt(measuredHeights.lastIndex)
        val wv = webViews.removeAt(webViews.lastIndex)
        container.removeView(wv)
        wv.destroy()
    }

    private fun handleScrollChange(scrollY: Int) {
        val window = buildWindow()
        val (href, progression) = ContinuousPositionTracker.locatorAt(scrollY, height, window)
        onPositionChanged?.invoke(href, progression)

        val currentChapterIndex = allChapters.indexOfFirst { it.link.href.toString() == href }
        when (ContinuousPositionTracker.shiftNeeded(currentChapterIndex, topIndex, allChapters.size)) {
            ContinuousPositionTracker.ShiftDirection.FORWARD -> {
                removeTop()
                val nextIndex = topIndex + 2 // topIndex already incremented in removeTop()
                if (nextIndex < allChapters.size) appendChapter(nextIndex)
            }
            ContinuousPositionTracker.ShiftDirection.BACKWARD -> {
                removeBottom()
                val prevIndex = topIndex - 1
                if (prevIndex >= 0) {
                    topIndex--
                    prependChapter(prevIndex)
                }
            }
            ContinuousPositionTracker.ShiftDirection.NONE -> Unit
        }
    }

    private fun rebuildWindowAround(centerIndex: Int) {
        // Destroy all current WebViews
        webViews.forEach { it.destroy() }
        webViews.clear()
        measuredHeights.clear()
        container.removeAllViews()
        topIndex = (centerIndex - 1).coerceAtLeast(0)
        val windowSize = minOf(3, allChapters.size - topIndex)
        repeat(windowSize) { i -> appendChapter(topIndex + i) }
    }

    private fun buildWindow(): List<ContinuousPositionTracker.ChapterSlot> {
        var top = 0
        return webViews.mapIndexed { i, wv ->
            val h = measuredHeights[i]
            ContinuousPositionTracker.ChapterSlot(wv.chapterHref, top, h).also { top += h }
        }
    }

    private fun webViewIndexFor(href: String): Int? {
        val i = webViews.indexOfFirst { it.chapterHref == href }
        return if (i >= 0) i else null
    }
}
```

- [ ] **Step 2: Build to confirm it compiles**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug 2>&1 | grep -E "error:|BUILD"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/ContinuousReaderView.kt
git commit -m "feat(reader): add ContinuousReaderView with windowed ChapterWebView pool"
```

---

## Task 6: Wire `ContinuousReaderView` into `EpubReaderScreen`

This is the largest single change: branch on `Continuous` orientation in the screen, keep an invisible fragment as the HTTP server, extract its base URL, mount `ContinuousReaderView`, and hook up position callbacks.

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt`

- [ ] **Step 1: Add a `continuousViewRef` and `serverBaseUrl` state alongside the existing `fragmentRef`**

Near the top of the composable that contains the reader `AndroidView` (around line 155, with the other `remember` values), add:

```kotlin
val continuousViewRef = remember { mutableStateOf<ContinuousReaderView?>(null) }
val serverBaseUrl = remember { mutableStateOf<String?>(null) }
val isContinuous = formattingPrefs.orientation == ReaderOrientation.Continuous
```

- [ ] **Step 2: Extract base URL from the invisible fragment's first page load**

After the existing `LaunchedEffect` that collects `fragment.currentLocator` (around line ~900), add:

```kotlin
// In Continuous mode, the fragment is zero-height (HTTP server keeper only).
// Once it loads its first page, extract the HTTP server base URL so ContinuousReaderView
// can construct chapter URLs without going through the Readium navigator.
LaunchedEffect(fragmentRef.value, isContinuous) {
    if (!isContinuous) return@LaunchedEffect
    val fragment = fragmentRef.value ?: return@LaunchedEffect
    fragment.currentLocator.collect { locator ->
        if (serverBaseUrl.value != null) return@collect
        val absoluteUrl = fragment.evaluateJavascript("window.location.href") ?: return@collect
        val relHref = locator.href.toString().trimStart('/')
        // absoluteUrl = "http://127.0.0.1:PORT/PUB_HASH/EPUB/chapter.xhtml"
        // relHref     = "EPUB/chapter.xhtml"
        // base        = "http://127.0.0.1:PORT/PUB_HASH"
        val base = absoluteUrl.trim('"').removeSuffix("/$relHref")
        serverBaseUrl.value = base
    }
}
```

- [ ] **Step 3: Suppress scroll-boundary polling in Continuous mode**

The existing `LaunchedEffect` that polls JS boundaries every 120 ms (around line 1480) currently guards on `orientation != ReaderOrientation.Vertical`. Extend the guard:

```kotlin
LaunchedEffect(fragmentRef.value, currentFormattingPrefs.orientation) {
    val fragment = fragmentRef.value ?: return@LaunchedEffect
    if (currentFormattingPrefs.orientation != ReaderOrientation.Vertical) return@LaunchedEffect
    // ... existing polling loop unchanged ...
}
```

- [ ] **Step 4: Make the fragment container zero-height in Continuous mode**

The `AndroidView` factory creates a `ScrollBoundaryNavigationContainer`. In the `update` lambda, conditionally collapse it:

```kotlin
update = { container ->
    container.layoutParams = container.layoutParams.apply {
        height = if (isContinuous) 0 else MATCH_PARENT
    }
    container.visibility = if (isContinuous) View.INVISIBLE else View.VISIBLE
    // ... rest of existing update logic unchanged ...
},
```

- [ ] **Step 5: Mount `ContinuousReaderView` alongside the existing `AndroidView`**

After the existing `AndroidView(...)` block (which now collapses to zero when Continuous), add:

```kotlin
val base = serverBaseUrl.value
if (isContinuous && base != null && state is ReaderState.Ready) {
    val chapters = state.publication.readingOrder.map { link ->
        ContinuousReaderView.ChapterEntry(link, "$base/${link.href.toString().trimStart('/')}")
    }
    AndroidView(
        factory = { ctx ->
            ContinuousReaderView(ctx).also { view ->
                continuousViewRef.value = view
                view.onPositionChanged = { href, progression ->
                    val locator = Locator(
                        href = Href(href),
                        mediaType = MediaType.XHTML,
                        locations = Locator.Locations(progression = progression.toDouble()),
                    )
                    viewModel.onPositionChanged(locator)
                }
            }
        },
        update = { _ -> /* ContinuousReaderView state is driven by LaunchedEffect below, not update */ },
        modifier = readerModifier,
    )
    // Initialize once after factory runs.
    LaunchedEffect(base, state.publication) {
        val view = continuousViewRef.value ?: return@LaunchedEffect
        val initialLocator = latestLocator() ?: state.initialLocator
        view.initialize(
            chapters = chapters,
            prefs = formattingPrefs,
            initialHref = initialLocator.href.toString(),
            initialProgression = initialLocator.locations.progression?.toFloat() ?: 0f,
        )
    }
}
```

- [ ] **Step 6: Propagate preference changes to `ContinuousReaderView`**

Find the existing `LaunchedEffect` that calls `fragment.submitPreferences(...)` (search for `submitPreferences`). After that line, also update the continuous view if present:

```kotlin
continuousViewRef.value?.updatePreferences(formattingPrefs)
```

- [ ] **Step 7: Build**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug 2>&1 | grep -E "error:|BUILD"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt
git commit -m "feat(reader): wire ContinuousReaderView into EpubReaderScreen with invisible fragment server keeper"
```

---

## Task 7: Readaloud highlight routing in Continuous mode

When readaloud is active, `activeFragmentRef` contains the current sentence's chapter href + span id. In Continuous mode, route the highlight to the correct `ChapterWebView` using `ContinuousReaderView.highlightInChapter`.

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt`

- [ ] **Step 1: Short-circuit the existing Readium decoration `LaunchedEffect` in Continuous mode**

The `LaunchedEffect` around line 1278 calls `fragment.applyDecorations(...)`. Readium's API only works on its own managed WebView. Wrap the entire body with an early-return when `isContinuous`:

```kotlin
LaunchedEffect(activeFragmentRef, reflowGeneration, pageLoadGeneration.value, sentenceQuotes) {
    if (isContinuous) return@LaunchedEffect  // handled separately below
    // ... existing applyDecorations body unchanged ...
}
```

- [ ] **Step 2: Add a new `LaunchedEffect` that routes highlights to `ContinuousReaderView` in Continuous mode**

After the block above, add:

```kotlin
val prevHighlightHref = remember { mutableStateOf<String?>(null) }

LaunchedEffect(activeFragmentRef, isContinuous, sentenceQuotes) {
    if (!isContinuous) return@LaunchedEffect
    val view = continuousViewRef.value ?: return@LaunchedEffect
    val ref = activeFragmentRef
    if (ref == null) {
        // Readaloud stopped — clear the last known chapter's highlight
        prevHighlightHref.value?.let { view.clearHighlightInChapter(it) }
        prevHighlightHref.value = null
        return@LaunchedEffect
    }
    val chapterHref = ref.substringBefore('#')
    val sid = ref.substringAfter('#', "")
    val quote = sentenceQuotes[sid]
    val highlightText = quote?.highlight?.take(12) ?: return@LaunchedEffect
    // If the chapter changed, clear the previous chapter's mark first
    val prev = prevHighlightHref.value
    if (prev != null && prev != chapterHref) view.clearHighlightInChapter(prev)
    prevHighlightHref.value = chapterHref
    // Use 12-char prefix — same heuristic as autoFollowSnapJs — so window.find matches reliably
    view.highlightInChapter(chapterHref, highlightText.replace("'", "\\'"))
}
```

- [ ] **Step 3: Short-circuit the auto-follow `LaunchedEffect` in Continuous mode**

Around line 1363, the `LaunchedEffect` calls `ColumnSnap.followNarratedSentence(fragment, ...)`. In Continuous mode, the `ContinuousReaderView` already keeps the current position in view via its scroll position — no auto-scroll needed from this effect. Add at its top:

```kotlin
if (isContinuous) return@LaunchedEffect
```

- [ ] **Step 4: Build**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug 2>&1 | grep -E "error:|BUILD"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt
git commit -m "feat(reader): route readaloud highlight to ContinuousReaderView in Continuous mode"
```

---

## Task 8: Search navigation in Continuous mode

In Continuous mode, `goAndSnapWithCover` calls `ColumnSnap.goAndSnap(fragment, ...)` which requires Readium's fragment WebView. Replace the search navigation flow with `ContinuousReaderView.navigateTo` for Continuous mode.

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt`

- [ ] **Step 1: Branch `searchNavigationEvents` collection on `isContinuous`**

Find the existing `LaunchedEffect(searchNavigationEvents)` around line 1179:

```kotlin
LaunchedEffect(searchNavigationEvents) {
    searchNavigationEvents.collect { goAndSnapWithCover(it) }
}
```

Replace with:

```kotlin
LaunchedEffect(searchNavigationEvents, isContinuous) {
    searchNavigationEvents.collect { locator ->
        if (isContinuous) {
            val view = continuousViewRef.value ?: return@collect
            val href = locator.href.toString()
            val progression = locator.locations.progression?.toFloat() ?: 0f
            val highlightText = locator.text.highlight?.take(40)?.replace("'", "\\'") ?: ""
            view.navigateTo(href, progression)
            // Clear previous search highlight then apply new one
            view.highlightInChapter(href, highlightText)
        } else {
            goAndSnapWithCover(locator)
        }
    }
}
```

- [ ] **Step 2: Suppress Readium `applyDecorations` for search in Continuous mode**

The `LaunchedEffect(searchResults, currentSearchIndex, ...)` around line 1217 calls `fragment.applyDecorations(...)` for search highlights. Add an early return at its top:

```kotlin
LaunchedEffect(searchResults, currentSearchIndex, reflowGeneration, pageLoadGeneration.value) {
    if (isContinuous) return@LaunchedEffect  // ContinuousReaderView uses window.find instead
    // ... existing applyDecorations logic unchanged ...
}
```

- [ ] **Step 3: Build**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug 2>&1 | grep -E "error:|BUILD"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Run all reader JVM tests to confirm no regressions**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew test 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`, no test failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt
git commit -m "feat(reader): route search navigation to ContinuousReaderView; suppress fragment decorations in Continuous mode"
```

---

## Task 9: Style injection verification + final cleanup

Before declaring done, verify that the CSS variables set by `ContinuousStyleInjector` actually match what Readium's own fragment sets. Then run the full test suite.

**Files:**
- Possibly modify: `app/src/main/kotlin/com/riffle/app/feature/reader/ContinuousStyleInjector.kt` (if audit reveals mismatches)

- [ ] **Step 1: Install a Continuous-mode build on the Harness AVD and open a book**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug
adb -s <HARNESS_SERIAL> install -r app/build/outputs/apk/debug/app-debug.apk
```

Open a multi-chapter book, switch to Continuous mode. Scroll across two chapter boundaries. Verify:
- Chapter transition is seamless (both chapters visible simultaneously, no gap)
- Chapter below loads while still scrolling through current chapter
- Progress indicator advances as you cross chapters

- [ ] **Step 2: DevTools CSS variable audit**

With the app open in Continuous mode, open WebView DevTools for a `ChapterWebView`:

```bash
adb forward tcp:9222 localabstract:chrome_devtools_remote
```

Open `chrome://inspect`, select a chapter tab and run:

```js
JSON.stringify(
  Array.from({ length: document.documentElement.style.length }, (_, i) =>
    document.documentElement.style.item(i)
  ).map(p => [p, document.documentElement.style.getPropertyValue(p)])
)
```

Then switch the book to **Scroll mode** and run the same query in the Readium fragment's tab. Compare both outputs. Any variables present in Scroll but missing in Continuous mode must be added to `ContinuousStyleInjector.buildVariableInjectionJs`.

- [ ] **Step 3: Run full test suite**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew test 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`, zero failures.

- [ ] **Step 4: Final commit**

```bash
git add -p  # stage only the style-injector changes, if any
git commit -m "fix(reader): align ContinuousStyleInjector CSS variables with Readium fragment audit"
```
