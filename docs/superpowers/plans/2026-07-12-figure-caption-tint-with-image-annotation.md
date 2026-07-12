# Figure-caption tint with image annotation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When a `TYPE_IMAGE` annotation exists on a `<figure>`, also tint that figure's `<figcaption>` in the annotation's color so the figure and caption read as one annotation.

**Architecture:** Extend `figureBorderApplyJs` in `FigureBorderInjection.kt` — the CSS/JS pass already dispatched to all three reader modes via `FigureBorderDecoration.kt` and `ContinuousDecorationController.kt`. Add a JS-only tint step because (a) inline SVG has no CSS-matchable selector, and (b) using CSS `:has()` for raster would fail on the API-25 harness AVD's Chrome 55 WebView. No schema, no locator, no sync changes.

**Tech Stack:** Kotlin, JUnit 4, WebView JS injection.

## Global Constraints

- Semantic markup only — `figure > figcaption` and `[role="figure"] > figcaption`. No heuristic fallback.
- Silent behavior — no toast, no setting, no first-run explainer.
- No schema/Room migration; no changes to `AnnotationEntity`, `AnnotationDao`, `AnnotationW3CCodec`, `WebDavAnnotationSyncTarget`, `HighlightsPublicationFactory`, or `EpubReaderViewModel`.
- Caption tint color must be the annotation color emitted the same way the border is (via `HighlightColor.fromToken(token).argb.toCssRgba()`), so alpha stays in sync.
- Every apply pass must **clear** stale caption tints first, mirroring the existing SVG "always-clear-then-apply" pattern in `figureBorderApplyJs`.

---

### Task 1: Tint `<figcaption>` when its `<figure>` holds an annotated image or SVG

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/decorations/FigureBorderInjection.kt` (extend `figureBorderApplyJs`)
- Create: `app/src/test/kotlin/com/riffle/app/feature/reader/decorations/FigureBorderInjectionTest.kt`

**Interfaces:**
- Consumes: `FigureBorderDecoration.RasterMark(filename, color, hasNote)` and `FigureBorderDecoration.SvgMatch(fingerprint, color, hasNote)` — already exist.
- Produces: no new public symbols. `figureBorderApplyJs(cssRules, svgMatches, rasterMarks)` signature unchanged; the emitted JS gains caption-tint statements.

- [ ] **Step 1: Write the failing test file**

Create `app/src/test/kotlin/com/riffle/app/feature/reader/decorations/FigureBorderInjectionTest.kt`:

```kotlin
package com.riffle.app.feature.reader.decorations

import org.junit.Assert.assertTrue
import org.junit.Test

class FigureBorderInjectionTest {

    @Test
    fun `apply js clears stale figcaption tints before applying`() {
        val js = figureBorderApplyJs(
            cssRules = emptyList(),
            svgMatches = emptyList(),
            rasterMarks = emptyList(),
        )
        // Every apply pass must sweep figcaption[data-riffle-fig-tint] and clear the tint,
        // mirroring the SVG "always-clear-then-apply" pattern already in this file. Reverting
        // to a leave-stale-tints-in-place approach flips this red.
        assertTrue(
            "apply JS is missing the figcaption clear pass",
            js.contains("data-riffle-fig-tint"),
        )
        assertTrue(
            "apply JS should query figcaption elements with the tint marker",
            js.contains("figcaption[data-riffle-fig-tint]"),
        )
    }

    @Test
    fun `apply js tints figcaption for raster image annotations`() {
        val marks = listOf(
            FigureBorderDecoration.RasterMark(
                filename = "graph.png",
                color = "rgba(52,211,153,0.5)",
                hasNote = false,
            ),
        )
        val js = figureBorderApplyJs(cssRules = emptyList(), svgMatches = emptyList(), rasterMarks = marks)

        // For every matched raster image, the JS must walk up to the containing <figure> (or
        // role="figure") and tint the first child <figcaption> with the annotation color.
        // Reverting the caption-tint pass flips this red.
        assertTrue(
            "apply JS should look for a figure ancestor via closest()",
            js.contains("closest('figure, [role=\"figure\"]')") ||
                js.contains("closest(\"figure, [role='figure']\")"),
        )
        assertTrue(
            "apply JS should target the first child figcaption",
            js.contains("querySelector('figcaption')") ||
                js.contains("querySelector(\"figcaption\")"),
        )
        assertTrue(
            "apply JS should set backgroundColor to the raster mark's color",
            js.contains("52,211,153"),
        )
    }

    @Test
    fun `apply js tints figcaption for svg annotations`() {
        val matches = listOf(
            FigureBorderDecoration.SvgMatch(
                fingerprint = "<svg id=\"chart\">",
                color = "rgba(56,189,248,0.5)",
                hasNote = false,
            ),
        )
        val js = figureBorderApplyJs(cssRules = emptyList(), svgMatches = matches, rasterMarks = emptyList())

        // Same treatment for inline-SVG figures — must tint the containing figcaption.
        // Reverting the SVG branch's caption tint flips this red.
        assertTrue(
            "apply JS should carry the svg mark's color for the caption tint",
            js.contains("56,189,248"),
        )
        assertTrue(
            "svg branch should also invoke figure/figcaption traversal",
            js.contains("closest('figure, [role=\"figure\"]')") ||
                js.contains("closest(\"figure, [role='figure']\")"),
        )
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```
export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home
./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.reader.decorations.FigureBorderInjectionTest"
```

Expected: 3 FAILs — the current `figureBorderApplyJs` doesn't emit `data-riffle-fig-tint`, doesn't call `closest('figure, [role="figure"]')`, and doesn't tint figcaption at all.

- [ ] **Step 3: Extend `figureBorderApplyJs` in `FigureBorderInjection.kt`**

In `app/src/main/kotlin/com/riffle/app/feature/reader/decorations/FigureBorderInjection.kt`, inside the returned template string of `figureBorderApplyJs(...)`, add a shared helper function above the two existing `try { ... }` blocks (right after the note-badge helpers), and invoke it from the raster loop and the SVG loop.

Insert this helper block just before the `try { var rasters = $rasterJson; ... }` block (i.e., after `function addNoteBadge(el, color) { ... }` closes):

```kotlin
          function clearAllFigcaptionTints() {
            var stale = document.querySelectorAll('figcaption[data-riffle-fig-tint]');
            for (var k = 0; k < stale.length; k++) {
              stale[k].style.backgroundColor = '';
              stale[k].removeAttribute('data-riffle-fig-tint');
            }
          }
          function tintCaptionFor(el, color) {
            if (!el) return;
            var fig = el.closest && el.closest('figure, [role="figure"]');
            if (!fig) return;
            var cap = fig.querySelector('figcaption');
            if (!cap) return;
            cap.style.backgroundColor = color;
            cap.setAttribute('data-riffle-fig-tint', '1');
          }
          clearAllFigcaptionTints();
```

(These are JS lines inside the Kotlin raw string — they don't need Kotlin-side escaping beyond what's already there. No `$` in the added code, so no interpolation collision.)

Then, in the **raster** `try` block, inside the innermost `for (var ii = 0; ii < imgs.length; ii++)` loop, after the `var img = imgs[ii];` line, add:

```
                var rasterColor = 'rgba(0,0,0,0)';
                var cssRules = style.textContent || '';
                var selectorNeedle = 'img[src$="' + rf.fn + '"]';
                var ruleIdx = cssRules.indexOf(selectorNeedle);
                if (ruleIdx >= 0) {
                  var openBrace = cssRules.indexOf('{', ruleIdx);
                  var closeBrace = cssRules.indexOf('}', openBrace);
                  var block = openBrace >= 0 && closeBrace >= 0 ? cssRules.substring(openBrace, closeBrace) : '';
                  var m = block.match(/rgba\([^)]+\)/);
                  if (m) rasterColor = m[0];
                }
                tintCaptionFor(img, rasterColor);
```

This reads the color back out of the just-installed style block (the raster border's color) so we don't need to thread the color through `rasterMarks`. **Simpler alternative:** promote `RasterMark.color` into the `rasterJson` payload and use it directly. Do that instead:

**Prefer this alternative.** Change the `rasterJson` construction in `figureBorderApplyJs` (around line 56 of the current file) to include the color:

```kotlin
    val rasterJson = rasterMarks.joinToString(",", prefix = "[", postfix = "]") { m ->
        val escFn = m.filename.replace("\\", "\\\\").replace("\"", "\\\"")
        val escColor = m.color.replace("\"", "\\\"")
        "{\"fn\":\"$escFn\",\"color\":\"$escColor\",\"note\":${if (m.hasNote) 1 else 0}}"
    }
```

Then in the raster inner loop, replace the color-parsing block above with the direct call:

```
                tintCaptionFor(img, rf.color);
```

And in the **SVG** `try` block, inside the `if (outer.indexOf(fp) === 0 || ...)` branch, right after `s.__riffleBorderApplied = true;`, add:

```
                  tintCaptionFor(s, matches[j].color);
```

- [ ] **Step 4: Run tests to verify they pass**

```
./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.reader.decorations.FigureBorderInjectionTest"
```

Expected: 3 PASSes.

- [ ] **Step 5: Run the sibling decoration test to confirm no regression**

```
./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.reader.decorations.FigureBorderDecorationTest"
```

Expected: PASS (should be unaffected — we didn't change `FigureBorderDecoration.kt`).

- [ ] **Step 6: Full app JVM test suite as a safety net**

```
./gradlew :app:testDebugUnitTest
```

Expected: PASS. Per the `feedback_gradle_test_command` memory, run `:app:testDebugUnitTest` (module-scoped is fine here — the change is inside `:app`).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/decorations/FigureBorderInjection.kt \
        app/src/test/kotlin/com/riffle/app/feature/reader/decorations/FigureBorderInjectionTest.kt
git commit -m "feat(reader): tint <figcaption> alongside image annotation's figure border

When an image annotation covers a <figure> that has a <figcaption> child
(or role=\"figure\" with a figcaption child), tint the caption in the
annotation's color so the figure and caption read as one unit. Purely a
render-side extension of the existing FigureBorderInjection JS pass; no
schema, locator, or sync changes."
```

---

## Verification (not part of the plan tasks — for the developer executing the plan)

Per AGENTS.md, reading-behavior changes must be verified in all three reader modes. Because this is a CSS/JS injection change fanned out through the same path as the existing figure border, mode parity is expected — verification guards against WebView quirks.

Book to use: any EPUB whose figures use `<figure><figcaption>...</figcaption></figure>` markup. The dev's usual test EPUBs on the ABS test server include titles that do.

Repro path in each mode:
1. Open a book with a `<figure><figcaption>` figure.
2. Long-press the figure to create an image annotation.
3. Confirm: figure has the colored border AND the caption text has a colored background tint in the annotation's color.
4. Change the annotation's color from the highlight popup. Confirm the caption tint follows.
5. Delete the annotation. Confirm both the border and the caption tint disappear.
6. In continuous mode specifically: scroll the chapter offscreen and back. Confirm the tint restores on chapter re-attach.

## Self-review

- **Spec coverage:** Rendering rule (design §Rendering) — Task 1. Selection scope (design §Selection scope) — the JS uses `closest('figure, [role="figure"]')` and `querySelector('figcaption')`, matching the design's HTML-5-first-child rule (HTML5 restricts `<figcaption>` to be a direct child; `querySelector('figcaption')` grabs the first, which is what we want). Undo/delete symmetry — the always-clear-then-apply pass covers it. Text-highlight overlap — nothing prevents the composite; no code needed. Elided view — no change (design §Interaction). ABS sync — no change (design §Interaction). Continuous re-attach — covered by the existing `ContinuousDecorationController` fanout, no new code.
- **Placeholder scan:** No TBDs, no "add error handling", no "similar to Task N". Each step has concrete code or command.
- **Type consistency:** Test uses `FigureBorderDecoration.RasterMark(filename, color, hasNote)` and `SvgMatch(fingerprint, color, hasNote)` — both match the existing signatures in `FigureBorderDecoration.kt` (verified against source).
- **Scope check:** Single-file source change + single new test file. Right-sized for one task.
