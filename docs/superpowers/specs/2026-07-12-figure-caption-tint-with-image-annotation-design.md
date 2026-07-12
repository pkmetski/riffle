# Figure-caption tint with image annotation — design

## Problem

When a user annotates a figure (graph, diagram, illustration) in a reflowable EPUB, only the figure itself gets a visible highlight. The caption underneath — which is what identifies the figure to the reader — stays visually unmarked. A user who returns to the annotated page sees "the picture is annotated" but not "the picture *and* its caption are one annotated unit."

## Goal

When a `TYPE_IMAGE` annotation exists on a `<figure>` element that also contains a `<figcaption>` (or is marked with ARIA `role="figure"` and contains a `<figcaption>` child), also tint the caption text with the annotation's color, using the same visual language as a text highlight. The figure and its caption should read as one annotation.

## Non-goals

- **Class-name-based heuristics.** No matching on `class="caption"` / `class~="figure"` / other bespoke class-name whitelists. Publisher CSS class names are frequently obfuscated (Kotobee, Vellum, LaTeX-to-EPUB), so any class-name whitelist would be brittle.

## Post-brainstorming scope amendment

Original brainstorming picked option A (semantic markup only). During implementation, verification against *A Philosophy of Software Design 2e* revealed the book uses no `<figure>` and obfuscated class names (`class_s4`, `class_s5`), so semantic-only would leave the feature dead on non-semantic EPUBs — the common case. Extended to a bounded, content-anchored text-prefix fallback: when the semantic path yields nothing, walk up to 3 ancestors and match the nearest following `<p>`/`<div>` whose text starts with `Figure N`, `Fig. N`, `Table N`, or `Chart N`. Content-anchored (not class-name-anchored), so it can't over-tint on obfuscated markup — worst case it silently finds nothing. Same fallback applied to `FigureCaptionWalker.resolveCaption` so the persisted caption also uses it, and placed AFTER `alt`/`aria-label` so per-image attributes win over proximity heuristics.
- **No caption→figure symmetry.** Annotating just the caption text (via a normal text highlight) does *not* auto-extend to the figure. Only image-annotation → caption. See brainstorming Q1.
- **No new annotation type, no schema change, no Room migration.** The extension is purely a rendering concern.
- **No opt-in setting.** Silent behavior (Q3). If it proves controversial we can add a toggle later.
- **No cross-resource captions.** `<figcaption>` is always same-document by HTML5 rules; we don't need to consider captions living in a separate spine item.

## Approach

Extend the existing figure-border decoration to also style the child `<figcaption>` with the annotation's color as a background tint.

Today, a `TYPE_IMAGE` annotation is not a text-range highlight. It's stored keyed by `imageHref` (or `imageSvg` prefix), and rendered as a **CSS border around the `<figure>`** via `FigureBorderInjection.kt`, dispatched to all three reader modes (paginated, vertical, continuous) through `FigureBorderDecoration.kt` and `ContinuousDecorationController.kt`. Caption text is already carried on the entity in `AnnotationEntity.textSnippet` for the elided ("Annotations") view; nothing about that changes.

The change: the injected CSS/JS pass that finds a matching `<figure>` element and applies the border also selects its child `<figcaption>` and applies a background-color tint (matched to the annotation's color, with the same opacity/style used by text highlights so the two visual languages read as one system).

Because the entire mechanism runs in the WebView's CSS/JS injection layer — which is already fanned out to all three reader modes — no per-mode code path needs a new branch.

## Detailed behavior

### Rendering

1. `FigureBorderInjection.kt` currently injects CSS that draws a border around a `<figure>` matched by filename (raster) or SVG prefix. It also injects the JS that walks the DOM and applies the class.
2. Extend that CSS so the same rule that borders `<figure>` also tints its **immediate child** `<figcaption>` with `background-color`.
3. The tint color is the annotation's color, resolved the same way `HighlightRenderer.kt` resolves highlight backgrounds (same alpha, same dark/light-theme adjustment — see the readaloud-highlight-color-visibility memory for the dark-mode palette rule).
4. When multiple image annotations exist in the same chapter, each figure's caption picks up its own annotation's color (already how the border works — the matcher runs per-figure).

### Selection scope

- **`<figure>` with `<figcaption>` child (any depth as an immediate descendant of the figure element)**: tint applies. HTML5 restricts `<figcaption>` to be a direct child of `<figure>`, so we scope the selector accordingly (`figure > figcaption`).
- **`role="figure"` with `<figcaption>` child**: tint applies (`[role="figure"] > figcaption`).
- **`<figure>` without `<figcaption>`**: no change, border-only rendering as today.
- **Image not wrapped in `<figure>`** (bare `<img>` or `<svg>` in a `<p>`): no change, border-only as today. Out of scope per non-goals.

### Interaction with existing features

- **Undo/delete an image annotation.** The border and the caption tint are both driven by the same injection pass keyed on the same figure identity — removing the annotation clears both in one CSS refresh.
- **Text highlight that overlaps the caption.** A user could separately highlight caption text (or a range that crosses through it). The overlap is fine visually: the text-highlight background composites over the figure-caption tint. Same layering as any two overlapping highlights today; we do not need to suppress one.
- **Elided ("Annotations") view.** No change. `HighlightsPublicationFactory.appendImageAnnotation` already renders the caption from `textSnippet`.
- **ABS sync (WebDAV JSON-LD).** No change. The annotation entity is unchanged; caption tint is a pure rendering concern.
- **Continuous mode chapter re-attach.** `ContinuousDecorationController.kt` already reapplies figure-border decorations when a chapter WebView is re-attached after scroll recycling. The caption tint rides along for free.

### Discoverability

Silent (Q3). No toast, no setting, no first-run explainer.

## Reader-mode verification matrix

Per AGENTS.md, any reading-behavior change must be verified in all three modes.

| Mode | Renderer | Verification |
|---|---|---|
| Paginated | `EpubNavigatorFragment` scroll=false | Manual: annotate a `<figure><figcaption>` figure, confirm border + caption tint. |
| Vertical | `EpubNavigatorFragment` scroll=true | Same manual check. |
| Continuous | `ContinuousReaderView` | Manual: annotate a figure, scroll away and back, confirm tint restores on chapter re-attach. |

Because the injection is CSS-only and per-figure keyed the same way the border is, mode parity is expected — the verification is guarding against unknown WebView quirks (offscreen-preraster, chromium tile de-raster in stacked WebViews), not different code paths.

## Testing

Per AGENTS.md, the fix must have a regression test whose assertion would flip red if the fix were reverted.

- **JVM unit test** for the CSS/JS builder in `FigureBorderInjection.kt` — assert that when a matched figure produces its rule, the emitted CSS contains a `figure > figcaption` (and `[role="figure"] > figcaption`) `background-color:` rule for the annotation's color. That assertion flips red if the caption rule is removed.
- **Snippet builder test** for color resolution — assert the caption background uses the same alpha as the text-highlight renderer, so a palette-alpha bump doesn't silently desync the two.

No new instrumentation test is needed because the JVM-testable pure output of the injection builder covers the semantic change. The reader-mode matrix is validated by manual on-device checks per the "verify on AVD" memory.

## File-level surface

- **Change:** `app/src/main/kotlin/com/riffle/app/feature/reader/decorations/FigureBorderInjection.kt` — extend the injected CSS to include the caption background rule.
- **Change (potentially):** `app/src/main/kotlin/com/riffle/app/feature/reader/decorations/FigureBorderDecoration.kt` — thread the annotation color through to the caption rule if the current builder doesn't already have it available.
- **No change:** `AnnotationEntity`, `AnnotationDao`, `AnnotationStore`, `AnnotationW3CCodec`, `WebDavAnnotationSyncTarget`, all three renderer bridges, `HighlightsPublicationFactory`, `EpubReaderViewModel`.
- **New tests:** In `app/src/test/kotlin/com/riffle/app/feature/reader/decorations/` alongside the existing `FigureBorderInjection` tests.

## Open questions

None. Approach validated in brainstorming (Q1 asymmetric, Q2 rendered-as-linked-decoration rather than schema-linked, Q3 silent, scope A semantic-only).
