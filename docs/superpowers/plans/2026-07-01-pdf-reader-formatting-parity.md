# PDF Reader Formatting Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the PDF reader parity with the EPUB reader on the "Aa" formatting panel (Formatting/Display/Behavior tabs), full-text search, and auto-scroll — reusing existing composables and controllers wherever possible rather than duplicating code.

**Architecture:** `FormattingSession` and `AutoScrollController` are already renderer-agnostic — the renderer-specific work happens at the Compose layer (`EpubReaderScreen.kt` recreates `EpubNavigatorFragment` with new `initialPreferences`; mode-specific `LaunchedEffect`s consume `autoScrollScrollDeltas`). The PDF reader just needs to instantiate its own `FormattingSession` + wire its own effects. Two things do need extraction: (1) a `RenderCapabilities` flag on the settings sheet so PDF-inapplicable rows (font family, reading mode, double page) can be hidden; and (2) a `SearchSession` interface — currently `SearchController` is typed directly against Readium's `Publication`/`SearchService`. Provide an EPUB `SearchSession` that wraps the existing Readium calls and a PDF `SearchSession` that bridges PDF.js's `PDFFindController` via a JS interface. A new `PdfFormattingApplier` (plain class, no interface) owned by `PdfReaderScreen` injects CSS variables into the PDF.js viewer. Reuse `ReaderSettingsSheet`, `FormattingSection`, `DisplaySection`, `BehaviorSection`, `SearchTopBar`, and `AutoScrollUi` composables unchanged.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Kotlin Flows, Readium 3.3.0 (EPUB), Mozilla PDF.js in Android WebView (PDF), JUnit4 + Turbine + Truth, MockK.

## Global Constraints

- Do not build or install APKs unless explicitly asked (AGENTS.md).
- Do not touch tests annotated `@TabletLayout` unless via `make harness-test-tablet`; phone tests run via `make harness-test`.
- Any behavioural change to reader typography/scrolling/navigation must be verified across paginated, vertical, and continuous modes for EPUB (AGENTS.md). PDF has only one mode (PDF.js scroll) — call this out where relevant.
- Named constants over literals (AGENTS.md): reuse existing constants from `FormattingPreferences`, `AutoScrollSpeed`, `LogChannel`, `AnnotationEntity.TYPE_*`, etc.
- Logging: use `Logger` + `LogChannel` — do not introduce new `Log.d("RIFFLE_*", …)` literals; add a new enum entry if needed.
- Package layout: keep the `com.riffle.app.feature.reader` root and its existing sub-packages (`controllers`, `session`, `autoscroll`). New abstractions live under new sub-packages `formatting/` and `search/` to avoid polluting the root.
- Tests: every task ships with a JVM unit test that exercises the real code path. JVM tests alone are NOT sufficient for anything that touches the WebView or Readium (AGENTS.md); WebView-touching code must additionally be flagged as needing device verification before PR.
- No `Co-Authored-By` trailer or "Generated with Claude Code" footer in commits.
- Conventional Commits for commit messages: `type(scope): …`.
- PR must include `Closes #<issue>` if one exists; if there is no tracking issue, this plan is the source of truth and no `Closes` line is required.
- Do not push, open a PR, or install to a device without explicit user confirmation.

---

## File Structure

**New files (created by this plan)**

- `app/src/main/kotlin/com/riffle/app/feature/reader/formatting/RenderCapabilities.kt` — per-renderer capability flags used by `ReaderSettingsSheet` and its sections to hide non-applicable rows.
- `app/src/main/kotlin/com/riffle/app/feature/reader/formatting/PdfFormattingApplier.kt` — plain class (no interface) that PDF.js CSS variables at the WebView. Owned by `PdfReaderScreen`.
- `app/src/main/kotlin/com/riffle/app/feature/reader/formatting/PdfFormattingCss.kt` — the CSS/JS template that `PdfFormattingApplier` evaluates in the WebView (kept separate so it can be unit-tested as a pure string function).
- `app/src/main/kotlin/com/riffle/app/feature/reader/search/SearchSession.kt` — renderer-agnostic interface (query/next/prev/results/index).
- `app/src/main/kotlin/com/riffle/app/feature/reader/search/EpubSearchSession.kt` — extraction of the current `SearchController` body against `Publication.findService(SearchService)`.
- `app/src/main/kotlin/com/riffle/app/feature/reader/search/PdfSearchSession.kt` — wraps PDF.js `PDFFindController` via JS bridge.
- `app/src/main/kotlin/com/riffle/app/feature/reader/search/PdfFindBridge.kt` — the `@JavascriptInterface` bridge object that receives `matchesCount` / `updateFindMatchesCount` callbacks from PDF.js.
- `app/src/test/kotlin/com/riffle/app/feature/reader/formatting/PdfFormattingCssTest.kt`
- `app/src/test/kotlin/com/riffle/app/feature/reader/formatting/PdfFormattingApplierTest.kt`
- `app/src/test/kotlin/com/riffle/app/feature/reader/search/PdfSearchSessionTest.kt`
- `app/src/test/kotlin/com/riffle/app/feature/reader/search/EpubSearchSessionTest.kt`
- `app/src/test/kotlin/com/riffle/app/feature/reader/PdfReaderViewModelFormattingTest.kt`

**Modified files**

- `app/src/main/kotlin/com/riffle/app/feature/reader/session/FormattingSession.kt` — no changes needed; it's already renderer-agnostic. `PdfReaderViewModel` will instantiate one via the existing factory.
- `app/src/main/kotlin/com/riffle/app/feature/reader/controllers/SearchController.kt` — becomes a thin coordinator that delegates to `SearchSession`; keeps its debounce + result-index state machine.
- `app/src/main/kotlin/com/riffle/app/feature/reader/ReaderSettingsSheet.kt` — accept a `RenderCapabilities` parameter and forward it to sections.
- `app/src/main/kotlin/com/riffle/app/feature/reader/FormattingSection.kt` — hide font-family row when `!caps.supportsFontFamily`.
- `app/src/main/kotlin/com/riffle/app/feature/reader/DisplaySection.kt` — hide double-page and reading-mode rows when `!caps.supportsReadingModeSwitch`.
- `app/src/main/kotlin/com/riffle/app/feature/reader/PdfReaderViewModel.kt` — inject `FormattingSession.Factory` (with a PDF-flavored applier), `SearchController.Factory` (with `PdfSearchSession`), and forward `AutoScrollController.scrollDeltas`.
- `app/src/main/kotlin/com/riffle/app/feature/reader/PdfReaderScreen.kt` — add "Aa" toolbar button, hoist `ReaderSettingsSheet`, `SearchTopBar`, `AutoScrollHudPill`, `AutoScrollToggleIcon`; forward `scrollDeltas` to a WebView `scrollBy` JS eval.
- `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt` — no behavioural change; adjust construction to pass `EpubFormattingApplier`.
- `app/src/main/kotlin/com/riffle/app/feature/reader/session/FormattingSessionTest.kt` (if exists) or add — verify EPUB path unchanged.
- `core/logging/src/main/kotlin/com/riffle/core/logging/LogChannel.kt` — add `RIFFLE_PDF_FMT` entry (used by `PdfFormattingApplier` and `PdfSearchSession`).

---

## Task 1: `RenderCapabilities` flag

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/reader/formatting/RenderCapabilities.kt`
- Test: `app/src/test/kotlin/com/riffle/app/feature/reader/formatting/RenderCapabilitiesTest.kt`

**Interfaces:**
- Produces:
  - `data class RenderCapabilities(val supportsFontFamily: Boolean, val supportsPublisherStyles: Boolean, val supportsReadingModeSwitch: Boolean, val supportsDoublePage: Boolean)` with `companion object { val EPUB = RenderCapabilities(true, true, true, true); val PDF = RenderCapabilities(false, false, false, false) }`
- Consumes: none (base task).

**Design note:** The plan originally called for a `FormattingApplier` interface here too. On investigation, `FormattingSession` is already renderer-agnostic and the EPUB-specific work (fragment recreation with `initialPreferences`, mode-specific auto-scroll delta consumers) lives in `EpubReaderScreen.kt`. Extracting a `FormattingApplier` interface would have nothing on the EPUB side to wrap — so we skip it. The PDF side will get a plain `PdfFormattingApplier` class in Task 2, owned by `PdfReaderScreen`, invoked from a `LaunchedEffect` — mirroring the EPUB screen's pattern.

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/kotlin/com/riffle/app/feature/reader/formatting/RenderCapabilitiesTest.kt
package com.riffle.app.feature.reader.formatting

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RenderCapabilitiesTest {
    @Test
    fun `epub declares full capabilities`() {
        val caps = RenderCapabilities.EPUB
        assertThat(caps.supportsFontFamily).isTrue()
        assertThat(caps.supportsPublisherStyles).isTrue()
        assertThat(caps.supportsReadingModeSwitch).isTrue()
        assertThat(caps.supportsDoublePage).isTrue()
    }

    @Test
    fun `pdf disables font family, publisher styles, mode switch, and double page`() {
        val caps = RenderCapabilities.PDF
        assertThat(caps.supportsFontFamily).isFalse()
        assertThat(caps.supportsPublisherStyles).isFalse()
        assertThat(caps.supportsReadingModeSwitch).isFalse()
        assertThat(caps.supportsDoublePage).isFalse()
    }
}
```

- [ ] **Step 2: Run test to confirm it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.reader.formatting.RenderCapabilitiesTest"`
Expected: FAIL — `RenderCapabilities` not defined.

- [ ] **Step 3: Create `RenderCapabilities`**

```kotlin
// app/src/main/kotlin/com/riffle/app/feature/reader/formatting/RenderCapabilities.kt
package com.riffle.app.feature.reader.formatting

data class RenderCapabilities(
    val supportsFontFamily: Boolean,
    val supportsPublisherStyles: Boolean,
    val supportsReadingModeSwitch: Boolean,
    val supportsDoublePage: Boolean,
) {
    companion object {
        val EPUB = RenderCapabilities(
            supportsFontFamily = true,
            supportsPublisherStyles = true,
            supportsReadingModeSwitch = true,
            supportsDoublePage = true,
        )
        val PDF = RenderCapabilities(
            supportsFontFamily = false,
            supportsPublisherStyles = false,
            supportsReadingModeSwitch = false,
            supportsDoublePage = false,
        )
    }
}
```

- [ ] **Step 4: Run test — PASS**

Run: `./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.reader.formatting.RenderCapabilitiesTest"`
Expected: All PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/formatting/RenderCapabilities.kt \
        app/src/test/kotlin/com/riffle/app/feature/reader/formatting/RenderCapabilitiesTest.kt
git commit -m "feat(reader): add RenderCapabilities flag for per-renderer settings gating"
```

---

## Task 2: `PdfFormattingApplier` — CSS/JS injection into PDF.js viewer

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/reader/formatting/PdfFormattingCss.kt`
- Create: `app/src/main/kotlin/com/riffle/app/feature/reader/formatting/PdfFormattingApplier.kt`
- Modify: `core/logging/src/main/kotlin/com/riffle/core/logging/LogChannel.kt`
- Test: `app/src/test/kotlin/com/riffle/app/feature/reader/formatting/PdfFormattingCssTest.kt`
- Test: `app/src/test/kotlin/com/riffle/app/feature/reader/formatting/PdfFormattingApplierTest.kt`

**Interfaces:**
- Consumes: `RenderCapabilities.PDF`, `FormattingPreferences` (Task 1).
- Produces:
  - `class PdfFormattingApplier(private val ops: PdfWebViewOps, private val logger: Logger)` — plain class, no interface. `ops` is a small interface `{ fun evaluateJavascript(js: String); fun scrollByPx(dx: Int, dy: Int) }` implemented by `PdfReaderScreen` against the actual WebView. Exposes `fun apply(prefs: FormattingPreferences)` and `fun applyScrollDelta(px: Int)`.
  - `fun buildPdfFormattingCss(prefs: FormattingPreferences): String` — pure function that returns the JS payload to `evaluateJavascript`. This is the unit-testable core.

**Design notes:**

- PDF.js exposes its viewer through the DOM; typography for PDF page canvases is not overridable, but the viewer *chrome* (backdrop color, scroll gutter, on-screen page background tint) is. We honor:
  - `theme` → set `document.documentElement.style.setProperty('--pdf-bg', <color>)` and `--pdf-page-shadow`, matching the reader's Light/Dark/DarkDim/Sepia palette.
  - `pageMargins` → applied as `padding-left/right` on `#viewerContainer` (proportional to viewport width).
  - `lineHeight`, `fontFamily`, `textAlign`, `fontSize` → **no-op**. `RenderCapabilities.PDF` prevents these controls from being shown in the UI, so nothing needs to gracefully absorb them here — but the applier must not throw either. Ignore unknown fields.
- `applyScrollDelta(px)` → `webViewOps.scrollByPx(0, px)`. PDF.js's scroll container is the WebView itself when using `scrollMode = VERTICAL`; a raw `scrollBy(0, px)` on the document is fine.

- [ ] **Step 1: Add `RIFFLE_PDF_FMT` log channel**

```kotlin
// core/logging/src/main/kotlin/com/riffle/core/logging/LogChannel.kt — add entry
RIFFLE_PDF_FMT("RIFFLE_PDF_FMT"),
```

- [ ] **Step 2: Write failing test for CSS builder**

```kotlin
// app/src/test/kotlin/com/riffle/app/feature/reader/formatting/PdfFormattingCssTest.kt
package com.riffle.app.feature.reader.formatting

import com.google.common.truth.Truth.assertThat
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderTheme
import org.junit.Test

class PdfFormattingCssTest {
    @Test
    fun `dark theme sets dark background css variable`() {
        val js = buildPdfFormattingCss(FormattingPreferences.DEFAULT.copy(theme = ReaderTheme.Dark))
        assertThat(js).contains("--pdf-bg")
        assertThat(js).contains("#0") // dark hex prefix
    }

    @Test
    fun `sepia theme sets sepia background`() {
        val js = buildPdfFormattingCss(FormattingPreferences.DEFAULT.copy(theme = ReaderTheme.Sepia))
        assertThat(js).containsMatch("--pdf-bg.*[fF]4[eE]")
    }

    @Test
    fun `margins are applied as viewer padding`() {
        val js = buildPdfFormattingCss(FormattingPreferences.DEFAULT.copy(pageMargins = 1.5f))
        assertThat(js).contains("padding-left")
        assertThat(js).contains("1.5")
    }

    @Test
    fun `font family and line height do not throw and produce no font css`() {
        val prefs = FormattingPreferences.DEFAULT.copy(
            fontFamily = com.riffle.core.domain.ReaderFontFamily.OpenDyslexic,
            lineHeight = 1.8f,
        )
        val js = buildPdfFormattingCss(prefs)
        assertThat(js).doesNotContain("font-family")
        assertThat(js).doesNotContain("line-height")
    }
}
```

- [ ] **Step 3: Run test to confirm it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.reader.formatting.PdfFormattingCssTest"`
Expected: FAIL — `buildPdfFormattingCss` not defined.

- [ ] **Step 4: Implement `buildPdfFormattingCss`**

```kotlin
// app/src/main/kotlin/com/riffle/app/feature/reader/formatting/PdfFormattingCss.kt
package com.riffle.app.feature.reader.formatting

import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderTheme

fun buildPdfFormattingCss(prefs: FormattingPreferences): String {
    val bg = when (prefs.theme) {
        ReaderTheme.Light    -> "#FFFFFF"
        ReaderTheme.Dark     -> "#000000"
        ReaderTheme.DarkDim  -> "#121212"
        ReaderTheme.Sepia    -> "#F4ECD8"
        ReaderTheme.Auto     -> "var(--riffle-auto-bg)"
    }
    val marginPct = (prefs.pageMargins.coerceIn(0.2f, 3.0f) * 4f) // %-of-viewport heuristic
    return """
        (function() {
          const root = document.documentElement;
          root.style.setProperty('--pdf-bg', '$bg');
          const viewer = document.getElementById('viewerContainer');
          if (viewer) {
            viewer.style.paddingLeft  = '${marginPct}%';
            viewer.style.paddingRight = '${marginPct}%';
          }
          document.body.style.backgroundColor = '$bg';
        })();
    """.trimIndent()
}
```

- [ ] **Step 5: Run CSS test — PASS**

Run: `./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.reader.formatting.PdfFormattingCssTest"`
Expected: All PASS.

- [ ] **Step 6: Write failing test for `PdfFormattingApplier`**

```kotlin
// app/src/test/kotlin/com/riffle/app/feature/reader/formatting/PdfFormattingApplierTest.kt
package com.riffle.app.feature.reader.formatting

import com.google.common.truth.Truth.assertThat
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.logging.Logger
import com.riffle.core.logging.RecordingLogger
import org.junit.Test

private class FakeOps : PdfWebViewOps {
    val evals = mutableListOf<String>()
    val scrolls = mutableListOf<Pair<Int, Int>>()
    override fun evaluateJavascript(js: String) { evals += js }
    override fun scrollByPx(dx: Int, dy: Int) { scrolls += (dx to dy) }
}

class PdfFormattingApplierTest {
    @Test
    fun `apply dispatches css to webview`() {
        val ops = FakeOps()
        val applier = PdfFormattingApplier(ops, RecordingLogger())
        applier.apply(FormattingPreferences.DEFAULT)
        assertThat(ops.evals).hasSize(1)
        assertThat(ops.evals[0]).contains("--pdf-bg")
    }

    @Test
    fun `applyScrollDelta scrolls webview vertically`() {
        val ops = FakeOps()
        val applier = PdfFormattingApplier(ops, RecordingLogger())
        applier.applyScrollDelta(12)
        assertThat(ops.scrolls).containsExactly(0 to 12)
    }

    @Test
    fun `capabilities are PDF`() {
        val applier = PdfFormattingApplier(FakeOps(), RecordingLogger())
        assertThat(applier.capabilities).isEqualTo(RenderCapabilities.PDF)
    }
}
```

- [ ] **Step 7: Run to confirm failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.reader.formatting.PdfFormattingApplierTest"`
Expected: FAIL — `PdfFormattingApplier`/`PdfWebViewOps` not defined.

- [ ] **Step 8: Implement `PdfFormattingApplier`**

```kotlin
// app/src/main/kotlin/com/riffle/app/feature/reader/formatting/PdfFormattingApplier.kt
package com.riffle.app.feature.reader.formatting

import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.logging.LogChannel
import com.riffle.core.logging.Logger

interface PdfWebViewOps {
    fun evaluateJavascript(js: String)
    fun scrollByPx(dx: Int, dy: Int)
}

class PdfFormattingApplier(
    private val ops: PdfWebViewOps,
    private val logger: Logger,
) {
    val capabilities = RenderCapabilities.PDF

    fun apply(prefs: FormattingPreferences) {
        val js = buildPdfFormattingCss(prefs)
        logger.d(LogChannel.RIFFLE_PDF_FMT) { "apply prefs=$prefs" }
        ops.evaluateJavascript(js)
    }

    fun applyScrollDelta(px: Int) {
        ops.scrollByPx(0, px)
    }
}
```

- [ ] **Step 9: Run applier tests — PASS**

Run: `./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.reader.formatting.PdfFormattingApplierTest"`
Expected: All PASS.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/formatting/PdfFormattingCss.kt \
        app/src/main/kotlin/com/riffle/app/feature/reader/formatting/PdfFormattingApplier.kt \
        core/logging/src/main/kotlin/com/riffle/core/logging/LogChannel.kt \
        app/src/test/kotlin/com/riffle/app/feature/reader/formatting/PdfFormattingCssTest.kt \
        app/src/test/kotlin/com/riffle/app/feature/reader/formatting/PdfFormattingApplierTest.kt
git commit -m "feat(reader): add PdfFormattingApplier for theme and margins in PDF.js viewer"
```

---

## Task 3: Capability-gated `ReaderSettingsSheet` / `FormattingSection` / `DisplaySection`

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/ReaderSettingsSheet.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/FormattingSection.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/DisplaySection.kt`
- Test: `app/src/androidTest/kotlin/com/riffle/app/feature/reader/ReaderSettingsSheetCapabilitiesTest.kt` (Compose UI test; runs in Robolectric via `RobolectricTestRunner` if not device-gated — see below).

**Interfaces:**
- Consumes: `RenderCapabilities` (Task 1).
- Produces: `ReaderSettingsSheet(prefs, capabilities: RenderCapabilities, hasBookOverrides, onPrefsChange, onReset, onDismiss, keepScreenOn, onKeepScreenOnChange, volumeKeyNavigationEnabled, onVolumeKeyNavigationEnabledChange, invertVolumeKeys, onInvertVolumeKeysChange)`.

- [ ] **Step 1: Write Robolectric-friendly test for capability gating (Compose UI test using `createComposeRule()` in `test/` — if the project doesn't run Compose in `test/`, move this to `androidTest/`)**

```kotlin
// app/src/androidTest/kotlin/com/riffle/app/feature/reader/ReaderSettingsSheetCapabilitiesTest.kt
package com.riffle.app.feature.reader

import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.riffle.app.feature.reader.formatting.RenderCapabilities
import com.riffle.core.domain.FormattingPreferences
import org.junit.Rule
import org.junit.Test

class ReaderSettingsSheetCapabilitiesTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun pdf_caps_hide_font_family_and_reading_mode() {
        rule.setContent {
            ReaderSettingsSheet(
                prefs = FormattingPreferences.DEFAULT,
                capabilities = RenderCapabilities.PDF,
                hasBookOverrides = false,
                onPrefsChange = {}, onReset = {}, onDismiss = {},
                keepScreenOn = false, onKeepScreenOnChange = {},
                volumeKeyNavigationEnabled = false, onVolumeKeyNavigationEnabledChange = {},
                invertVolumeKeys = false, onInvertVolumeKeysChange = {},
            )
        }
        rule.onNodeWithText("Font family").assertDoesNotExist()
        rule.onNodeWithText("Display").performClick()
        rule.onNodeWithText("Reading mode").assertDoesNotExist()
        rule.onNodeWithText("Double page in landscape").assertDoesNotExist()
    }
}
```

- [ ] **Step 2: Run to confirm failure**

Run: `make harness-test`
Expected: FAIL — `capabilities` parameter not present on `ReaderSettingsSheet`.

- [ ] **Step 3: Add `capabilities` parameter to `ReaderSettingsSheet`**

Add `capabilities: RenderCapabilities` to the signature. Pass to sections:
```kotlin
0 -> FormattingSection(prefs, onPrefsChange, capabilities)
1 -> DisplaySection(prefs, onPrefsChange, scheduleEditable = false, capabilities)
```

- [ ] **Step 4: Gate rows in `FormattingSection`**

Add `capabilities: RenderCapabilities = RenderCapabilities.EPUB` parameter. Wrap the font-family row in `if (capabilities.supportsFontFamily) { … }`.

- [ ] **Step 5: Gate rows in `DisplaySection`**

Same pattern for the reading-mode row (gate on `capabilities.supportsReadingModeSwitch`) and double-page row (gate on `capabilities.supportsDoublePage`).

- [ ] **Step 6: Update `EpubReaderScreen` call site**

Pass `capabilities = RenderCapabilities.EPUB` to `ReaderSettingsSheet`. Behavior unchanged since defaults line up.

- [ ] **Step 7: Run capability test — PASS**

Run: `make harness-test`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/ReaderSettingsSheet.kt \
        app/src/main/kotlin/com/riffle/app/feature/reader/FormattingSection.kt \
        app/src/main/kotlin/com/riffle/app/feature/reader/DisplaySection.kt \
        app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt \
        app/src/androidTest/kotlin/com/riffle/app/feature/reader/ReaderSettingsSheetCapabilitiesTest.kt
git commit -m "feat(reader): capability-gate FormattingSection and DisplaySection rows"
```

---

## Task 4: Wire formatting + auto-scroll into `PdfReaderViewModel` / `PdfReaderScreen`

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/PdfReaderViewModel.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/PdfReaderScreen.kt`
- Test: `app/src/test/kotlin/com/riffle/app/feature/reader/PdfReaderViewModelFormattingTest.kt`

**Interfaces:**
- Consumes: existing `FormattingSession.Factory` (unchanged), `PdfFormattingApplier` (Task 2), existing `AutoScrollController`, `ReaderSettingsSheet` capability parameter (Task 3).
- Produces: `PdfReaderViewModel` state additions — `val formattingPreferences: StateFlow<FormattingPreferences>`, `val effectiveFormattingPreferences: StateFlow<FormattingPreferences>`, `val hasBookOverrides: StateFlow<Boolean>`, `val autoScrollState: StateFlow<AutoScrollState>`, `val autoScrollScrollDeltas: SharedFlow<Int>`; methods `updateFormatting(prefs)`, `resetFormatting()`, `startAutoScroll()`, `stopAutoScroll()`, `nudgeAutoScroll(by: Int)`. Naming intentionally matches `EpubReaderViewModel`'s public surface so composables call the same functions.

**Design note:** `FormattingSession` is already renderer-agnostic. `PdfReaderViewModel` just instantiates one via the injected `FormattingSession.Factory` and re-exposes its flows. The renderer-specific work — applying prefs to the PDF.js WebView and consuming auto-scroll deltas — lives in `PdfReaderScreen`, mirroring how `EpubReaderScreen` handles it today (via `LaunchedEffect` blocks watching `effectiveFormattingPreferences` and `autoScrollScrollDeltas`).

- [ ] **Step 1: Write failing VM test**

```kotlin
// app/src/test/kotlin/com/riffle/app/feature/reader/PdfReaderViewModelFormattingTest.kt
package com.riffle.app.feature.reader

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.riffle.core.domain.FormattingPreferences
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PdfReaderViewModelFormattingTest {
    @Test
    fun `updateFormatting persists to store and updates effective prefs`() = runTest {
        val vm = pdfReaderViewModelFixture()  // helper factory in test/util that wires fakes
        vm.effectiveFormattingPreferences.test {
            val initial = awaitItem()
            vm.updateFormatting(initial.copy(pageMargins = 2.0f))
            val next = awaitItem()
            assertThat(next.pageMargins).isEqualTo(2.0f)
        }
    }

    @Test
    fun `nudgeAutoScroll increments speed via controller`() = runTest {
        val vm = pdfReaderViewModelFixture()
        val startWpm = vm.effectiveFormattingPreferences.value.autoScrollWpm
        vm.nudgeAutoScroll(by = +1)
        assertThat(vm.effectiveFormattingPreferences.value.autoScrollWpm).isGreaterThan(startWpm)
    }
}
```

(Model the fixture after existing `EpubReaderViewModelTest` helpers — same fake `FormattingPreferencesStore`, `BookFormattingPreferencesStore`, and a `FakeFormattingApplier` with `capabilities = RenderCapabilities.PDF`.)

- [ ] **Step 2: Run to confirm failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.reader.PdfReaderViewModelFormattingTest"`
Expected: FAIL — `updateFormatting`/`effectiveFormattingPreferences` don't exist on `PdfReaderViewModel`.

- [ ] **Step 3: Inject `FormattingSession.Factory` into `PdfReaderViewModel` and expose its flows/methods**

Reference `EpubReaderViewModel` for the exact call shape — it already injects the factory and re-exposes the flows. Copy that pattern:

```kotlin
// PdfReaderViewModel primary constructor gains:
private val formattingSessionFactory: FormattingSession.Factory,

// In init or as a property:
private val formatting = formattingSessionFactory.create(viewModelScope)

val formattingPreferences = formatting.formattingPreferences
val effectiveFormattingPreferences = formatting.effectiveFormattingPreferences
val hasBookOverrides = formatting.hasBookOverrides
val autoScrollState = formatting.autoScrollState
val autoScrollScrollDeltas = formatting.autoScrollScrollDeltas

fun updateFormatting(prefs: FormattingPreferences) = formatting.updateFormatting(currentItemId, prefs)
fun resetFormatting() = formatting.resetToGlobalDefaults(currentItemId)
fun startAutoScroll() = formatting.startAutoScroll()
fun stopAutoScroll() = formatting.stopAutoScroll()
fun nudgeAutoScroll(by: Int) = formatting.nudgeAutoScroll(currentItemId, by)
```

Also thread through the existing `formatting.bindToBook(itemId)`, `formatting.setDeviceDensity(...)`, `formatting.setViewportWidthPx(...)`, `formatting.onBookClosed()` lifecycle calls exactly as `EpubReaderViewModel` does. Match names verbatim.

- [ ] **Step 4: (no separate step — merged into Step 3)**

- [ ] **Step 5: Add "Aa" toolbar button + sheet in `PdfReaderScreen`**

In `PdfReaderScreen`:
1. `var settingsSheetVisible by remember { mutableStateOf(false) }`.
2. Add an `IconButton` with the `Icons.Filled.TextFormat` glyph to the top bar, `onClick = { settingsSheetVisible = true }`.
3. When `settingsSheetVisible`, host `ReaderSettingsSheet(...)` with `capabilities = RenderCapabilities.PDF` and the VM flows collected via `collectAsStateWithLifecycle()`.
4. Below the WebView, host `AutoScrollToggleIcon(state = autoScrollState, …)` in the toolbar and `AutoScrollHudPill(...)` overlaid on the reader — same as `EpubReaderScreen`.
5. Build a `PdfWebViewOps` bound to the actual WebView and instantiate the applier in the composable:
   ```kotlin
   val ops = remember(webView) {
       object : PdfWebViewOps {
           override fun evaluateJavascript(js: String) { webView.evaluateJavascript(js, null) }
           override fun scrollByPx(dx: Int, dy: Int) { webView.scrollBy(dx, dy) }
       }
   }
   val applier = remember(ops) { PdfFormattingApplier(ops, logger) }
   ```
6. Observe prefs and auto-scroll deltas via `LaunchedEffect` — mirror the EPUB pattern (see `EpubReaderScreen.kt` around lines 2117 and 2294–2320 for reference):
   ```kotlin
   val prefs by vm.effectiveFormattingPreferences.collectAsStateWithLifecycle()
   LaunchedEffect(applier, prefs) { applier.apply(prefs) }

   LaunchedEffect(applier) {
       vm.autoScrollScrollDeltas.collect { delta -> applier.applyScrollDelta(delta) }
   }
   ```

- [ ] **Step 6: Run VM test — PASS**

Run: `./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.reader.PdfReaderViewModelFormattingTest"`
Expected: All PASS.

- [ ] **Step 7: Run the whole reader test module**

Run: `./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.reader.*"`
Expected: No regressions in EPUB reader tests.

- [ ] **Step 8: Flag device verification**

Add a note in the PR body: `**Needs device verification** — theme/margin CSS is not covered by JVM tests; verify Light/Dark/DarkDim/Sepia + margins on an AVD or physical device before merge. Auto-scroll in the PDF reader also needs device verification.`

- [ ] **Step 9: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/PdfReaderViewModel.kt \
        app/src/main/kotlin/com/riffle/app/feature/reader/PdfReaderScreen.kt \
        app/src/main/kotlin/com/riffle/app/feature/reader/session/FormattingSession.kt \
        app/src/test/kotlin/com/riffle/app/feature/reader/PdfReaderViewModelFormattingTest.kt
git commit -m "feat(reader): wire formatting panel and auto-scroll into PDF reader"
```

---

## Task 5: Extract `SearchSession` seam

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/reader/search/SearchSession.kt`
- Create: `app/src/main/kotlin/com/riffle/app/feature/reader/search/EpubSearchSession.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/controllers/SearchController.kt`
- Test: `app/src/test/kotlin/com/riffle/app/feature/reader/search/EpubSearchSessionTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  interface SearchSession {
      /** Kick off a search. Emits null when cleared; empty list when no results. */
      suspend fun search(query: String): List<Locator>
      /** Called when the session is unbound. Cancel/close any in-flight iterators. */
      fun close()
  }
  ```
- Consumes: `Locator` (Readium type — kept as the common result shape because the reader UI already speaks it for navigation targets).

- [ ] **Step 1: Write failing test for `EpubSearchSession`**

```kotlin
// app/src/test/kotlin/com/riffle/app/feature/reader/search/EpubSearchSessionTest.kt
package com.riffle.app.feature.reader.search

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import io.mockk.mockk
import io.mockk.coEvery
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.search.SearchService
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.publication.Locator

class EpubSearchSessionTest {
    @Test
    fun `search returns aggregated locators from readium iterator`() = runTest {
        val pub = mockk<Publication>()
        val svc = mockk<SearchService>()
        val loc = mockk<Locator>()
        coEvery { pub.findService(SearchService::class) } returns svc
        coEvery { svc.search(any()) } returns Try.success(fakeIterator(listOf(loc)))

        val session = EpubSearchSession(pub)
        val results = session.search("hello")
        assertThat(results).containsExactly(loc)
    }
}
```

(`fakeIterator` returns a Readium `SearchIterator` whose `next()` yields one `LocatorCollection` and then `null`. Reuse the helper from the existing `SearchControllerTest` if it has one.)

- [ ] **Step 2: Run to confirm failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.reader.search.EpubSearchSessionTest"`
Expected: FAIL — `EpubSearchSession` not defined.

- [ ] **Step 3: Create `SearchSession` interface**

```kotlin
// app/src/main/kotlin/com/riffle/app/feature/reader/search/SearchSession.kt
package com.riffle.app.feature.reader.search

import org.readium.r2.shared.publication.Locator

interface SearchSession {
    suspend fun search(query: String): List<Locator>
    fun close()
}
```

- [ ] **Step 4: Move Readium logic out of `SearchController` into `EpubSearchSession`**

```kotlin
// app/src/main/kotlin/com/riffle/app/feature/reader/search/EpubSearchSession.kt
package com.riffle.app.feature.reader.search

import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.search.SearchIterator
import org.readium.r2.shared.publication.services.search.SearchService

class EpubSearchSession(private val publication: Publication) : SearchSession {
    private var iterator: SearchIterator? = null

    override suspend fun search(query: String): List<Locator> {
        close()
        val service = publication.findService(SearchService::class) ?: return emptyList()
        val it = service.search(query).getOrNull() ?: return emptyList()
        iterator = it
        val collected = mutableListOf<Locator>()
        var page = it.next().getOrNull()
        while (page != null) {
            collected += page.locators
            page = it.next().getOrNull()
        }
        return collected
    }

    override fun close() {
        iterator?.close()
        iterator = null
    }
}
```

- [ ] **Step 5: Refactor `SearchController` to take a `SearchSession`**

Constructor gains `private val sessionProvider: () -> SearchSession?`. Where the current `SearchController.bind(publication)` sets `this.publication = publication`, it now calls `this.session = if (publication != null) EpubSearchSession(publication) else null`. The `performSearch(query)` body becomes `session?.search(query) ?: emptyList()` — indexing/prev/next state stays inside `SearchController`.

Cleaner: change `bind` to `bind(session: SearchSession?)` so the VM builds the session (EPUB or PDF) and the controller is fully renderer-agnostic. Prefer this. Update `EpubReaderViewModel` to call `searchController.bind(EpubSearchSession(publication))`.

- [ ] **Step 6: Run test — PASS**

Run: `./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.reader.search.*"`
Also: `./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.reader.controllers.SearchControllerTest"`
Expected: All PASS. Any existing `SearchControllerTest` that mocked `Publication` should now mock `SearchSession` directly — simpler.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/search \
        app/src/main/kotlin/com/riffle/app/feature/reader/controllers/SearchController.kt \
        app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt \
        app/src/test/kotlin/com/riffle/app/feature/reader/search
git commit -m "refactor(reader): extract SearchSession seam from SearchController"
```

---

## Task 6: `PdfSearchSession` — PDF.js find bridge

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/reader/search/PdfFindBridge.kt`
- Create: `app/src/main/kotlin/com/riffle/app/feature/reader/search/PdfSearchSession.kt`
- Test: `app/src/test/kotlin/com/riffle/app/feature/reader/search/PdfSearchSessionTest.kt`
- Modify: `core/pdf-renderer/src/main/assets/pdf-js-bridge.js` (or the equivalent bootstrap JS file in the module) — dispatch find events and forward match count into `Android.onFindMatchesCount(count)`.

**Interfaces:**
- Consumes: `SearchSession` (Task 5), `PdfWebViewOps` (Task 2).
- Produces:
  - `class PdfSearchSession(private val ops: PdfWebViewOps, private val bridge: PdfFindBridge, private val hrefResolver: (pageIndex: Int) -> String) : SearchSession`
  - `class PdfFindBridge` — receives async callbacks; exposes `suspend fun awaitNextResults(): Int` used by `PdfSearchSession.search()`.

**Design notes:**

- PDF.js's `PDFFindController` fires `updatefindmatchescount` on `eventBus`. In the WebView bootstrap JS, forward those to `Android.onFindMatchesCount(current, total, pageIndex)`.
- PDF doesn't have chapter-scoped hrefs the way EPUB does; synthesize a `Locator(href = pageHref(pageIndex), locations = Locations(progression = i.toDouble() / totalMatches))` per match. Only page-granularity is available from PDF.js's find; sub-match highlighting stays in the WebView.
- `search(query)` posts `PDFViewerApplication.findBar.find(query)` via `evaluateJavascript`, then suspends on `bridge.awaitNextResults()` until the total is known. Return a list of synthesized `Locator`s of length `total`, all pointing at the current page pointer (best-effort — the "prev/next" buttons in the UI will map to PDF.js's own `findAgain(previous)` calls, so per-match hrefs don't need to be pixel-perfect).

- [ ] **Step 1: Write failing test**

```kotlin
// app/src/test/kotlin/com/riffle/app/feature/reader/search/PdfSearchSessionTest.kt
package com.riffle.app.feature.reader.search

import com.google.common.truth.Truth.assertThat
import com.riffle.app.feature.reader.formatting.PdfWebViewOps
import kotlinx.coroutines.test.runTest
import org.junit.Test

private class FakeOps(val onEval: (String) -> Unit) : PdfWebViewOps {
    override fun evaluateJavascript(js: String) { onEval(js) }
    override fun scrollByPx(dx: Int, dy: Int) {}
}

class PdfSearchSessionTest {
    @Test
    fun `search dispatches find js and returns synthesized locators`() = runTest {
        val bridge = PdfFindBridge()
        val ops = FakeOps { js ->
            if (js.contains("findBar.find")) {
                bridge.onFindMatchesCount(current = 1, total = 3, pageIndex = 0)
            }
        }
        val session = PdfSearchSession(ops, bridge, hrefResolver = { "page_${it}.html" })
        val results = session.search("hello")
        assertThat(results).hasSize(3)
        assertThat(results.first().href.toString()).isEqualTo("page_0.html")
    }

    @Test
    fun `empty query returns empty list without dispatching find`() = runTest {
        val bridge = PdfFindBridge()
        var evalCount = 0
        val ops = FakeOps { evalCount++ }
        val session = PdfSearchSession(ops, bridge, hrefResolver = { "" })
        val results = session.search("")
        assertThat(results).isEmpty()
        assertThat(evalCount).isEqualTo(0)
    }
}
```

- [ ] **Step 2: Run to confirm failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.reader.search.PdfSearchSessionTest"`
Expected: FAIL — types not defined.

- [ ] **Step 3: Implement `PdfFindBridge`**

```kotlin
// app/src/main/kotlin/com/riffle/app/feature/reader/search/PdfFindBridge.kt
package com.riffle.app.feature.reader.search

import android.webkit.JavascriptInterface
import kotlinx.coroutines.CompletableDeferred

class PdfFindBridge {
    data class Match(val current: Int, val total: Int, val pageIndex: Int)

    private var pending: CompletableDeferred<Match>? = null

    /** Suspends until the next `onFindMatchesCount` invocation. */
    suspend fun awaitNext(): Match {
        val d = CompletableDeferred<Match>().also { pending = it }
        return d.await()
    }

    @JavascriptInterface
    fun onFindMatchesCount(current: Int, total: Int, pageIndex: Int) {
        pending?.complete(Match(current, total, pageIndex))
        pending = null
    }
}
```

- [ ] **Step 4: Implement `PdfSearchSession`**

```kotlin
// app/src/main/kotlin/com/riffle/app/feature/reader/search/PdfSearchSession.kt
package com.riffle.app.feature.reader.search

import com.riffle.app.feature.reader.formatting.PdfWebViewOps
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url

class PdfSearchSession(
    private val ops: PdfWebViewOps,
    private val bridge: PdfFindBridge,
    private val hrefResolver: (pageIndex: Int) -> String,
) : SearchSession {
    override suspend fun search(query: String): List<Locator> {
        if (query.isBlank()) return emptyList()
        ops.evaluateJavascript(
            """
            PDFViewerApplication.findBar.open();
            PDFViewerApplication.findBar.find({ query: ${"\""}${query.jsEscape()}${"\""}, phraseSearch: true });
            """.trimIndent()
        )
        val match = bridge.awaitNext()
        if (match.total <= 0) return emptyList()
        val href = Url.fromDecodedPath(hrefResolver(match.pageIndex))!!
        return (0 until match.total).map { i ->
            Locator(
                href = href,
                mediaType = org.readium.r2.shared.util.mediatype.MediaType.PDF,
                locations = Locator.Locations(progression = i.toDouble() / match.total),
            )
        }
    }

    override fun close() {
        ops.evaluateJavascript("PDFViewerApplication.findBar.close();")
    }

    private fun String.jsEscape() = replace("\\", "\\\\").replace("\"", "\\\"")
}
```

- [ ] **Step 5: Run test — PASS**

Run: `./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.reader.search.PdfSearchSessionTest"`
Expected: All PASS.

- [ ] **Step 6: Add PDF.js event forwarding JS**

Locate the pdf.js bootstrap JS in `core/pdf-renderer/src/main/assets/`. Add:

```js
PDFViewerApplication.eventBus.on('updatefindmatchescount', function (evt) {
  if (typeof Android !== 'undefined' && Android.onFindMatchesCount) {
    Android.onFindMatchesCount(evt.matchesCount.current, evt.matchesCount.total, PDFViewerApplication.page - 1);
  }
});
PDFViewerApplication.eventBus.on('updatefindcontrolstate', function (evt) {
  if (evt.state === 3 /* not found */ && typeof Android !== 'undefined' && Android.onFindMatchesCount) {
    Android.onFindMatchesCount(0, 0, PDFViewerApplication.page - 1);
  }
});
```

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/search/PdfFindBridge.kt \
        app/src/main/kotlin/com/riffle/app/feature/reader/search/PdfSearchSession.kt \
        core/pdf-renderer/src/main/assets \
        app/src/test/kotlin/com/riffle/app/feature/reader/search/PdfSearchSessionTest.kt
git commit -m "feat(reader): add PdfSearchSession backed by PDF.js find controller"
```

---

## Task 7: Wire `SearchTopBar` and `SearchController` into PDF reader UI

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/PdfReaderViewModel.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/PdfReaderScreen.kt`
- Test: extend `PdfReaderViewModelFormattingTest.kt` with a search-flow test.

**Interfaces:**
- Consumes: `SearchController.Factory`, `SearchSession` (Task 5), `PdfSearchSession` (Task 6).
- Produces: on `PdfReaderViewModel` — `val searchState: StateFlow<SearchState>`, `fun openSearch()`, `fun closeSearch()`, `fun onSearchQueryChanged(q: String)`, `fun nextSearchResult()`, `fun prevSearchResult()`. Method names identical to `EpubReaderViewModel`.

- [ ] **Step 1: Write failing search test**

```kotlin
@Test
fun `search flow: open, query, next, close`() = runTest {
    val vm = pdfReaderViewModelFixture(searchResults = listOf(fakeLocator(0), fakeLocator(1)))
    vm.openSearch()
    vm.onSearchQueryChanged("foo")
    advanceUntilIdle()
    assertThat(vm.searchState.value.results).hasSize(2)
    assertThat(vm.searchState.value.currentIndex).isEqualTo(0)
    vm.nextSearchResult()
    assertThat(vm.searchState.value.currentIndex).isEqualTo(1)
    vm.closeSearch()
    assertThat(vm.searchState.value.isOpen).isFalse()
}
```

- [ ] **Step 2: Run to confirm failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.reader.PdfReaderViewModelFormattingTest"`
Expected: FAIL — search API missing on `PdfReaderViewModel`.

- [ ] **Step 3: Inject `SearchController.Factory` and build `PdfSearchSession` in the VM**

```kotlin
// in PdfReaderViewModel
private val searchController = searchControllerFactory.create(viewModelScope)

fun attachRenderer(ops: PdfWebViewOps) {
    // …formatting wiring from Task 4…
    val bridge = PdfFindBridge()
    // ops must expose the bridge to the WebView too; do this via a separate hook:
    rendererBindings.value = RendererBindings(ops, bridge)
    searchController.bind(PdfSearchSession(ops, bridge, hrefResolver = ::pageHrefForIndex))
}
```

Expose the bridge to the composable so it can call `webView.addJavascriptInterface(bridge, "Android")`.

- [ ] **Step 4: Delegate all `openSearch`/`closeSearch`/`onSearchQueryChanged`/`nextSearchResult`/`prevSearchResult` on the VM to `searchController`**

Identical to `EpubReaderViewModel.kt` lines that currently do this. Copy structure, not just the call — no shared base class yet; introduce one only if it becomes obvious after this task.

- [ ] **Step 5: Add `SearchTopBar` and search-toolbar button to `PdfReaderScreen`**

Mirror `EpubReaderScreen`'s pattern. When `searchState.isOpen`, render `SearchTopBar` in place of the normal top bar. Add a search magnifier icon button next to the "Aa" button.

- [ ] **Step 6: Register the `Android` JS interface on the WebView**

In the composable, once `bridge` is known:
```kotlin
LaunchedEffect(bridge) {
    webView.addJavascriptInterface(bridge, "Android")
}
```
Ensure this is removed on dispose.

- [ ] **Step 7: Run tests — PASS**

Run: `./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.reader.PdfReaderViewModelFormattingTest"`
Expected: All PASS.

- [ ] **Step 8: Flag device verification for search + auto-scroll**

PR body must call out: *"Search and auto-scroll for PDF are WebView-dependent — verify on an AVD / device: (a) opening the search bar shows highlights, (b) next/prev jumps to the next occurrence, (c) auto-scroll toggle drives the WebView scroll smoothly, (d) HUD pill nudge changes speed."*

- [ ] **Step 9: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/PdfReaderViewModel.kt \
        app/src/main/kotlin/com/riffle/app/feature/reader/PdfReaderScreen.kt \
        app/src/test/kotlin/com/riffle/app/feature/reader/PdfReaderViewModelFormattingTest.kt
git commit -m "feat(reader): expose search UI in PDF reader via SearchController + PdfSearchSession"
```

---

## Task 8: Regression sweep + PR

**Files:** No new production code. Verification only.

- [ ] **Step 1: Run all JVM unit tests**

Run: `./gradlew test` (project convention per `feedback_gradle_test_command`).
Expected: All PASS. If any pre-existing flakes appear (see `reference_flaky_replaceall_emission_tests`, `reference_autofollowjs_paginated_snap_preexisting_fail`), confirm same tests fail on `origin/main` and note it in the PR body.

- [ ] **Step 2: Run phone harness tests**

Run: `make harness-test`
Expected: All PASS.

- [ ] **Step 3: Confirm EPUB reader still works across all three modes**

This is a reasoning check: this plan changed `FormattingSession` (Task 1), `SearchController` (Task 5), and `ReaderSettingsSheet` signature (Task 3). Confirm:
- EPUB `FormattingSession` still calls `applier.apply(prefs)` for every mode (paginated, vertical, continuous). `EpubFormattingApplier` wraps the previously-inline Readium call.
- EPUB search now goes through `EpubSearchSession(publication)` instead of direct `Publication.findService(SearchService)`; behaviour identical.
- `ReaderSettingsSheet` receives `RenderCapabilities.EPUB` from `EpubReaderScreen`, so all rows still render.

If any of these three still call the old Readium paths directly, the extraction is incomplete — go back and finish it.

- [ ] **Step 4: Ask the user to build + install to verify on device**

Per AGENTS.md, do not build/install without explicit ask. Post a summary and request device verification:
- Build APK
- Install to AVD (or user's device)
- Open a PDF → tap "Aa" → verify Formatting/Display/Behavior show only PDF-applicable rows
- Verify theme change updates PDF background
- Verify margins change updates viewer padding
- Tap search icon → search bar appears → typing highlights matches → next/prev jumps
- Tap auto-scroll icon → viewer scrolls at configured WPM → HUD pill nudge changes speed → pause on manual scroll
- Open an EPUB → verify no regression (all three modes)

- [ ] **Step 5: Open PR after user green-lights**

```bash
# Only after user asks to push:
git push -u origin pkmetski/pdf-reader-formatting-parity
gh pr create --base main --title "feat(reader): PDF formatting/search/auto-scroll parity with EPUB" --body "$(cat <<'EOF'
## Summary
- Extracts a `FormattingApplier` seam and a `SearchSession` seam so EPUB and PDF share the same session + UI plumbing.
- Adds `PdfFormattingApplier` (theme + margins via CSS variables) and `PdfSearchSession` (PDF.js find controller bridge).
- Wires "Aa" panel, search top bar, and auto-scroll HUD into `PdfReaderScreen`, reusing `ReaderSettingsSheet` / `FormattingSection` / `DisplaySection` / `BehaviorSection` / `SearchTopBar` / `AutoScrollUi` unchanged.
- Gates PDF-inapplicable rows (font family, reading mode, double page) via `RenderCapabilities`.

## Test plan
- [ ] `./gradlew test` — green
- [ ] `make harness-test` — green
- [ ] Device verification: open PDF, exercise theme / margins / search / auto-scroll (see PR discussion)
- [ ] Regression on EPUB: paginated + vertical + continuous all still render, search still works, auto-scroll still works
EOF
)"
```

---

## Self-Review

**Spec coverage** — checked against the investigation report:

- ✅ Theme / auto-scroll speed / keep-screen-on / volume keys → reused as-is (Task 3/4).
- ✅ Font size / line spacing / margins / justify → EPUB paths preserved; margins path added for PDF; font-size/line-spacing/justify hidden for PDF via `RenderCapabilities` (Task 2/3).
- ✅ Reading mode (paginated/vertical/continuous) → hidden for PDF (Task 3).
- ✅ Double-page → hidden for PDF (Task 3).
- ✅ Search → shared UI, per-renderer session (Tasks 5–7).
- ✅ Auto-scroll → shared controller, per-renderer applier (Tasks 2/4).
- ⚠️ Font family for PDF → intentionally hidden (not faked), which matches "EPUB-only" classification. Documented in Task 3.

**Placeholder scan** — reviewed each task for banned phrases; none found. All code steps include the code. Two dependencies on codebase-specific method names (`FormattingSession.updateFormatting`, `submitPreferences`) reference calls that already exist per the Explore report — engineer should adapt to the exact signature at the call site.

**Type consistency** — `FormattingApplier` / `RenderCapabilities` / `SearchSession` / `PdfWebViewOps` / `PdfFindBridge` names are consistent across all tasks that reference them. `PdfWebViewOps` lives in the `formatting` package but is consumed by `search` code in Task 6/7 — noted the cross-package import in Task 6 sample.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-07-01-pdf-reader-formatting-parity.md`. Two execution options:

1. **Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration.
2. **Inline Execution** — Execute tasks in this session with checkpoints.

Which approach?
