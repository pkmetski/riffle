# PDF Reader Formatting Panel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the PDF reader an "Aa" settings sheet covering the formatting knobs Readium's pdfium adapter actually supports (scroll direction, page fit, page spacing, reading progression), plus theme (as a Compose background scrim) and the device-level Behavior tab (keep screen on, volume keys). Leave `RenderCapabilities` structured so a future PDF.js/WebView renderer swap can flip more flags to `true` and unlock the rest of EPUB's controls without rewriting this layer.

**Architecture:** `FormattingSession` and the settings-sheet composables are already renderer-agnostic. `RenderCapabilities` (Task 1, landed) gates which rows the sheet shows. `PdfReaderViewModel` gains a `FormattingSession` (identical wiring pattern to `EpubReaderViewModel`) and re-exposes its flows. `PdfReaderScreen` gets an "Aa" toolbar button, hosts `ReaderSettingsSheet(capabilities = RenderCapabilities.PDF)`, and translates `FormattingPreferences` into `PdfiumPreferences` at fragment-construction time. Prefs changes trigger fragment recreation (same pattern EPUB uses for double-page-spread today). Theme is applied at the composable layer as a background color behind the `FragmentContainerView` — bitmap pdfium pages can't be recolored.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Kotlin Flows, Readium 3.3.0 (`org.readium.adapter.pdfium.navigator`), JUnit4.

## Global Constraints

- Do not build or install APKs unless explicitly asked (AGENTS.md).
- Do not touch tests annotated `@TabletLayout` unless via `make harness-test-tablet`; phone tests run via `make harness-test`.
- Named constants over literals — use existing `FormattingPreferences`, `RenderCapabilities`, `LogChannel` values.
- Logging: `Logger` + `LogChannel` — no new `Log.d("RIFFLE_*", …)` literals. Do not add a new `LogChannel` entry unless a task requires production-recipe grep-ability.
- Test style: JUnit `org.junit.Assert.assertEquals` / `assertTrue` / `assertFalse`. **Do NOT add Google Truth** — it is not a project dependency and prior implementers had it reverted.
- Package layout: shared UI stays in `com.riffle.app.feature.reader`; new PDF-specific types live in `com.riffle.app.feature.reader.formatting` (already exists from Task 1).
- No `Co-Authored-By` trailer, no "Generated with Claude Code" footer.
- Conventional Commits for messages.
- Do not push, open a PR, or install to a device without explicit user confirmation.

**Assume this branch already has:**
- Task 1 (`2d8b0076c`): `RenderCapabilities.EPUB`/`RenderCapabilities.PDF` in `formatting/RenderCapabilities.kt`.
- Task 3 (`739326195`): `ReaderSettingsSheet`, `FormattingSection`, `DisplaySection` gated on `RenderCapabilities` — PDF hides Font, Reading mode, Double page rows.

---

## File Structure

**New files**

- `app/src/main/kotlin/com/riffle/app/feature/reader/formatting/PdfiumPreferencesMapper.kt` — pure functions `FormattingPreferences.toPdfiumPreferences(): PdfiumPreferences` and helpers mapping orientation → `Axis`, margins → `pageSpacing`. Pure Kotlin, no Readium fragment interaction. Unit-tested.
- `app/src/test/kotlin/com/riffle/app/feature/reader/formatting/PdfiumPreferencesMapperTest.kt` — one test per mapping decision.
- `app/src/test/kotlin/com/riffle/app/feature/reader/PdfReaderViewModelFormattingTest.kt` — verifies the `FormattingSession` delegations on the VM (`updateFormatting`, `resetToGlobalDefaults`, `effectiveFormattingPreferences` StateFlow).

**Modified files**

- `app/src/main/kotlin/com/riffle/app/feature/reader/PdfReaderViewModel.kt` — inject `FormattingSession.Factory` + supporting stores (`KeepScreenOnStore`, `VolumeKeyPreferencesStore` — check what EPUB VM uses); re-expose `formattingPreferences`, `effectiveFormattingPreferences`, `hasBookOverrides`, `keepScreenOn`, `volumeKeyNavigationEnabled`, `invertVolumeKeys`, `formattingPreferencesReady`; add `updateFormatting(prefs)`, `resetToGlobalDefaults()`, `setKeepScreenOn(v)`, `setVolumeKeyNavigationEnabled(v)`, `setInvertVolumeKeys(v)`, `setReaderViewportWidthPx(px)`; wire `formatting.bindToBook(itemId)` before `openBook()` and `formatting.onBookClosed()` in `onCleared()`. Do NOT expose auto-scroll flows — Task drops that scope.
- `app/src/main/kotlin/com/riffle/app/feature/reader/PdfReaderScreen.kt` — add `showFormattingPanel` state, add "Aa" `IconButton` to the `TopAppBar` actions block; host `ReaderSettingsSheet(capabilities = RenderCapabilities.PDF, ...)` when visible; wrap the `FragmentContainerView` `AndroidView` in a `Box` with a theme-colored background from `effectiveFormattingPreferences.theme`; pass `toPdfiumPreferences()` as the fragment's `initialPreferences` at construction (and force recreation via a key change when it changes — mirror the EPUB `if (double-page changed) recreate fragment` pattern).

---

## Task 4: PdfiumPreferences mapper + VM wiring + PdfReaderScreen "Aa"

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/reader/formatting/PdfiumPreferencesMapper.kt`
- Create: `app/src/test/kotlin/com/riffle/app/feature/reader/formatting/PdfiumPreferencesMapperTest.kt`
- Create: `app/src/test/kotlin/com/riffle/app/feature/reader/PdfReaderViewModelFormattingTest.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/PdfReaderViewModel.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/PdfReaderScreen.kt`

**Interfaces produced:**
```kotlin
// Pure top-level extension function (no class). Mirrors EPUB's FormattingPreferencesMapper.kt shape.
fun FormattingPreferences.toPdfiumPreferences(): PdfiumPreferences
```
- Consumes: existing `FormattingSession.Factory`, `RenderCapabilities.PDF`, Readium 3.3.0's `PdfiumPreferences(fit, pageSpacing, readingProgression, scrollAxis)`, `Fit`, `Axis`, `ReadingProgression` (all in `org.readium.r2.navigator.preferences`).

**Design notes on the mapping** (based on verified `PdfiumPreferences.kt` class shape — only these 4 fields exist):

| `FormattingPreferences` field | `PdfiumPreferences` field | Rationale |
|---|---|---|
| `orientation == Horizontal` | `scrollAxis = Axis.HORIZONTAL`, `fit = Fit.CONTAIN` | horizontal-swipe pages |
| `orientation == Vertical`   | `scrollAxis = Axis.VERTICAL`, `fit = Fit.WIDTH` | vertical-scroll one page at a time |
| `orientation == Continuous` | `scrollAxis = Axis.VERTICAL`, `fit = Fit.WIDTH` | pdfium has no distinct "continuous" — treat same as Vertical for now; note in code |
| `margins` (0.2f..3.0f multiplier) | `pageSpacing = margins.toDouble() * 8.0` | scale user's margin multiplier to a page-gap in dp; 8dp base is a reasonable middle |
| (no field)                       | `readingProgression = ReadingProgression.LTR` | leave default LTR; a future RTL toggle can join FormattingPreferences |
| `theme`, `fontFamily`, `fontSize`, `lineSpacing`, `justifyText`, `autoScrollWpm`, `doublePageSpread` | (none) | not applicable to pdfium; ignored by mapper |

Theme is *not* passed into `PdfiumPreferences` — it's applied at the composable layer in `PdfReaderScreen` as a `Box(Modifier.background(themeColor))` wrapping the `FragmentContainerView`. The `themeColor` derives from `effectiveFormattingPreferences.theme` via the same palette EPUB uses (see `EpubReaderScreen`'s theme resolution — reuse the color source rather than redefining).

**Fragment recreation:** `PdfiumPreferences` is passed via `PdfiumNavigatorFactory(...).createFragmentFactory(initialLocator = ..., initialPreferences = prefs.toPdfiumPreferences())` at the point where `PdfReaderScreen.kt:379` builds the factory today. Since prefs are baked in at construction (same limitation EPUB has for column-count), a change to any `PdfiumPreferences`-affecting field must trigger fragment recreation — key the `AndroidView` on those specific fields (`orientation` + `margins`) so Compose remounts the fragment when they change. Other prefs (theme, keepScreenOn, volume keys) do NOT require recreation.

**Steps**

- [ ] **Step 1: Write failing test for the mapper**

```kotlin
// app/src/test/kotlin/com/riffle/app/feature/reader/formatting/PdfiumPreferencesMapperTest.kt
package com.riffle.app.feature.reader.formatting

import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderOrientation
import org.junit.Assert.assertEquals
import org.junit.Test
import org.readium.r2.navigator.preferences.Axis
import org.readium.r2.navigator.preferences.Fit

class PdfiumPreferencesMapperTest {
    @Test
    fun `horizontal orientation maps to horizontal axis and contain fit`() {
        val prefs = FormattingPreferences(orientation = ReaderOrientation.Horizontal)
        val out = prefs.toPdfiumPreferences()
        assertEquals(Axis.HORIZONTAL, out.scrollAxis)
        assertEquals(Fit.CONTAIN, out.fit)
    }

    @Test
    fun `vertical orientation maps to vertical axis and width fit`() {
        val prefs = FormattingPreferences(orientation = ReaderOrientation.Vertical)
        val out = prefs.toPdfiumPreferences()
        assertEquals(Axis.VERTICAL, out.scrollAxis)
        assertEquals(Fit.WIDTH, out.fit)
    }

    @Test
    fun `continuous orientation maps like vertical`() {
        val prefs = FormattingPreferences(orientation = ReaderOrientation.Continuous)
        val out = prefs.toPdfiumPreferences()
        assertEquals(Axis.VERTICAL, out.scrollAxis)
        assertEquals(Fit.WIDTH, out.fit)
    }

    @Test
    fun `margins multiplier maps to pageSpacing in dp scale`() {
        val prefs = FormattingPreferences(margins = 1.5f)
        val out = prefs.toPdfiumPreferences()
        assertEquals(12.0, out.pageSpacing!!, 0.001)
    }
}
```

Verify `FormattingPreferences` field names (`orientation`, `margins`) against the real domain model before running — if a name differs, update the test to match reality (a previous implementer discovered `pageMargins` was actually `margins`).

- [ ] **Step 2: Run test to confirm RED**

Run: `./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.reader.formatting.PdfiumPreferencesMapperTest"`
Expected: FAIL — `toPdfiumPreferences` not defined.

- [ ] **Step 3: Implement the mapper**

```kotlin
// app/src/main/kotlin/com/riffle/app/feature/reader/formatting/PdfiumPreferencesMapper.kt
package com.riffle.app.feature.reader.formatting

import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderOrientation
import org.readium.adapter.pdfium.navigator.PdfiumPreferences
import org.readium.r2.navigator.preferences.Axis
import org.readium.r2.navigator.preferences.Fit
import org.readium.r2.navigator.preferences.ReadingProgression

private const val MARGIN_BASE_DP = 8.0

fun FormattingPreferences.toPdfiumPreferences(): PdfiumPreferences {
    val axis = when (orientation) {
        ReaderOrientation.Horizontal -> Axis.HORIZONTAL
        ReaderOrientation.Vertical, ReaderOrientation.Continuous -> Axis.VERTICAL
    }
    val fit = when (orientation) {
        ReaderOrientation.Horizontal -> Fit.CONTAIN
        ReaderOrientation.Vertical, ReaderOrientation.Continuous -> Fit.WIDTH
    }
    return PdfiumPreferences(
        fit = fit,
        pageSpacing = margins.toDouble() * MARGIN_BASE_DP,
        readingProgression = ReadingProgression.LTR,
        scrollAxis = axis,
    )
}
```

- [ ] **Step 4: Run mapper test — GREEN**

Run: same command as Step 2.
Expected: 4 PASS.

- [ ] **Step 5: Write failing test for `PdfReaderViewModel.updateFormatting` delegation**

Look at how `EpubReaderViewModel` tests structure their fakes (`app/src/test/kotlin/com/riffle/app/feature/reader/EpubReaderViewModelTest.kt`). It uses standalone `Fake*` helpers, not full VM construction, because Readium types touch `android.net.Uri`. Follow the same pattern — do NOT try to construct a real `PdfReaderViewModel`. Instead, test the `FormattingSession` delegation logic by constructing a `FormattingSession` directly with fake stores + `AutoScrollController` and verifying:

```kotlin
// app/src/test/kotlin/com/riffle/app/feature/reader/PdfReaderViewModelFormattingTest.kt
package com.riffle.app.feature.reader

import com.riffle.app.feature.reader.session.FormattingSession
import com.riffle.core.domain.FormattingPreferences
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.assertEquals

class PdfReaderViewModelFormattingTest {
    @Test
    fun `updateFormatting persists to book override store`() = runTest {
        val fixture = formattingSessionFixture()
        fixture.session.bindToBook("book-1")
        fixture.session.updateFormatting("book-1", FormattingPreferences(margins = 2.0f))
        // FakeBookFormattingStore's captured writes:
        assertEquals(2.0f, fixture.bookStore.captured("book-1")?.margins)
    }
}
```

Look at `app/src/test/kotlin/com/riffle/app/feature/reader/session/FormattingSessionTest.kt` — if it already has a `formattingSessionFixture` helper, reuse it. If not, add a small local one in the test file. Do NOT extract shared helpers unless FormattingSessionTest already needs them.

- [ ] **Step 6: Run VM test to confirm RED**

Expected: FAIL — either the fixture doesn't exist or the assertion doesn't pass yet. If it passes trivially without touching `PdfReaderViewModel`, this test is only exercising `FormattingSession` (already tested elsewhere) — in which case skip this test and rely on the mapper test + build compilation to verify the VM wiring.

- [ ] **Step 7: Wire `FormattingSession` into `PdfReaderViewModel`**

Reference `EpubReaderViewModel.kt` sections identified in the plan self-review:
- Constructor: add `private val formattingSessionFactory: FormattingSession.Factory,` and any keep-screen-on / volume-key stores EPUB uses (grep `EpubReaderViewModel.kt` for `KeepScreenOn`, `VolumeKey`, `setKeepScreenOn`, `setVolumeKeyNavigationEnabled` to find them).
- Property: `private val formatting: FormattingSession = formattingSessionFactory.create(viewModelScope).also { it.setDeviceDensity(application.resources.displayMetrics.density) }`
- Delegations block (mirror lines 326–388 of `EpubReaderViewModel.kt` — copy the shape verbatim). **Skip auto-scroll delegations** (`autoScrollState`, `autoScrollScrollDeltas`, `startAutoScroll`, `stopAutoScroll`, `nudgeAutoScroll`, `pauseAutoScroll`, `resumeAutoScrollIfPaused`, `setAutoScrollPaused`, `reachedEndOfBookForAutoScroll`) — those are out of scope for pdfium.
- Lifecycle: `formatting.bindToBook(itemId)` before `openBook()`; `formatting.onBookClosed()` in `onCleared()`.
- Public methods: `updateFormatting`, `resetToGlobalDefaults`, `setKeepScreenOn`, `setVolumeKeyNavigationEnabled`, `setInvertVolumeKeys`, `setReaderViewportWidthPx`.

- [ ] **Step 8: Wire "Aa" button + `ReaderSettingsSheet` + theme scrim + fragment recreation into `PdfReaderScreen`**

Reference `EpubReaderScreen.kt` for the pattern:
1. Line ~197: `var showFormattingPanel by remember { mutableStateOf(false) }`.
2. Line ~635 pattern: add `IconButton(onClick = { showFormattingPanel = true }) { Text("Aa", …) }` to the `TopAppBar` actions block (`PdfReaderScreen.kt:228–243`).
3. Collect the VM's formatting flows via `collectAsStateWithLifecycle()`.
4. Line ~676 pattern: `if (showFormattingPanel) { ReaderSettingsSheet(prefs = ..., capabilities = RenderCapabilities.PDF, hasBookOverrides = ..., onPrefsChange = { viewModel.updateFormatting(it) }, onReset = { viewModel.resetToGlobalDefaults() }, onDismiss = { showFormattingPanel = false }, keepScreenOn = ..., onKeepScreenOnChange = { viewModel.setKeepScreenOn(it) }, ...) }`.
5. Wrap the existing `AndroidView { … FragmentContainerView … }` in a `Box(modifier = Modifier.background(themeColorFor(prefs.theme)))` — the theme background sits behind the pdfium fragment. Find how EPUB computes a theme color; reuse.
6. **Fragment recreation on prefs change:** the current `AndroidView` factory (lines ~355–395) constructs `PdfiumNavigatorFactory(...)` unconditionally. Change the `AndroidView` key/remember scoping so it recreates when `prefs.orientation` or `prefs.margins` changes. One way: `AndroidView(factory = { … prefs.toPdfiumPreferences() … }, update = { /* no-op */ }, modifier = Modifier)` inside a `key(prefs.orientation, prefs.margins) { … }` block. Verify this matches the "recreate fragment" pattern EPUB uses for double-page toggles.

- [ ] **Step 9: Compile + run tests**

Run:
- `./gradlew :app:testDebugUnitTest` — full JVM suite (per `feedback_gradle_test_command`).
- `./gradlew :app:compileDebugAndroidTestKotlin` — verify androidTests still compile.

Expected: all green. If pre-existing flakes appear (see `reference_flaky_replaceall_emission_tests`, `reference_autofollowjs_paginated_snap_preexisting_fail`), confirm same tests fail on main and note in the PR body.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/formatting/PdfiumPreferencesMapper.kt \
        app/src/test/kotlin/com/riffle/app/feature/reader/formatting/PdfiumPreferencesMapperTest.kt \
        app/src/main/kotlin/com/riffle/app/feature/reader/PdfReaderViewModel.kt \
        app/src/main/kotlin/com/riffle/app/feature/reader/PdfReaderScreen.kt \
        app/src/test/kotlin/com/riffle/app/feature/reader/PdfReaderViewModelFormattingTest.kt
git commit -m "feat(reader): add Aa formatting sheet to PDF reader"
```

---

## Task 5: Regression sweep + PR

**Files:** No new production code. Verification only.

- [ ] **Step 1: Run all JVM unit tests**

Run: `./gradlew test`.
Expected: All PASS.

- [ ] **Step 2: Confirm EPUB reader unaffected**

Sanity check: only touched files that ship for both — `PdfReaderViewModel`/`PdfReaderScreen` are PDF-only. `ReaderSettingsSheet`/`FormattingSection`/`DisplaySection` were already changed in Task 3 with `RenderCapabilities.EPUB` defaults — no further changes here.

- [ ] **Step 3: Ask user to install and verify on device**

Per AGENTS.md, do not build/install without ask. Post a summary and request:
- Build APK, install to AVD/device.
- Open a PDF → tap "Aa" → verify:
  - Formatting tab shows only the applicable rows (per Task 3 gating: no Font, no Reading mode, no Double page).
  - Display tab shows Theme; changing theme updates the background scrim around the page.
  - Behavior tab shows Keep screen on, Volume keys.
  - Changing margins bumps `pageSpacing` (visible as more gap between pages when scrolling).
  - Changing orientation between Horizontal/Vertical recreates the fragment (fresh render, correct scroll axis).
- Open an EPUB → verify no regression (all three modes render, "Aa" sheet unchanged, all rows visible).

- [ ] **Step 4: Open PR after user green-lights**

```bash
# Only after user asks to push:
git push -u origin pkmetski/pdf-reader-formatting-parity
gh pr create --base main --title "feat(reader): add Aa formatting sheet to PDF reader" --body "$(cat <<'EOF'
## Summary
- Adds an "Aa" formatting sheet to the PDF reader, reusing the existing EPUB `ReaderSettingsSheet` composables.
- Introduces `RenderCapabilities` so PDF-inapplicable rows (font family, reading mode, double page) are hidden.
- Maps `FormattingPreferences` → Readium 3.3.0's `PdfiumPreferences` (scroll axis, fit, page spacing).
- Theme is applied at the composable layer as a background scrim behind the pdfium fragment (pdfium bitmaps aren't recolorable).
- Auto-scroll and search are out of scope on the pdfium adapter and remain EPUB-only.
- Structure leaves room for a future PDF.js/WebView renderer to bump `RenderCapabilities.PDF` and unlock the currently-hidden rows.

## Test plan
- [x] `./gradlew test` — green
- [x] Compose androidTest for capability-gated rows (authored in Task 3; deferred harness run)
- [ ] Device verification (see PR discussion): PDF Aa flow + EPUB regression across all three modes
EOF
)"
```

---

## Self-Review

**Spec coverage** — checked against the redraft brief:
- ✅ Aa button + sheet: Task 4 Step 8.
- ✅ Only-what-pdfium-supports mapping: Task 4 Step 3.
- ✅ Theme via Compose scrim: Task 4 Step 8.5.
- ✅ Behavior tab (keep screen on, volume keys): Task 4 Step 7 (VM delegations).
- ✅ `RenderCapabilities` structured for future PDF.js: unchanged from Task 1 landed.
- ✅ Auto-scroll and search explicitly out of scope.

**Placeholder scan:** no TBD / TODO / "similar to Task N" phrases. Code steps have code. Some steps reference "grep EpubReaderScreen at line ~635" rather than copying the entire code block — this is deliberate because copying 30 lines of EPUB call-site verbatim risks bit-rot; the implementer should read the real file. Every code choice has a rationale.

**Type consistency:** `toPdfiumPreferences()` return type is `PdfiumPreferences` (from `org.readium.adapter.pdfium.navigator`), matches the constructor signature verified via `javap`. `FormattingPreferences` field names (`orientation`, `margins`, `theme`) match reality per prior implementers' findings. `RenderCapabilities.PDF` matches Task 1's landed constant.

**Risk callout:** Task 4 Step 8's "fragment recreation via `key(prefs.orientation, prefs.margins) { AndroidView(...) }`" pattern needs verification against how `EpubReaderScreen` handles the double-page-spread toggle. If EPUB uses a different mechanism, match it there instead. Do NOT invent a new recreation strategy.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-07-01-pdf-reader-formatting-parity.md`.

Continuing under `superpowers:subagent-driven-development` with Task 4 dispatched to a fresh implementer.
