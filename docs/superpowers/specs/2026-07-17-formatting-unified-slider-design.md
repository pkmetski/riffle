# Unified slider for typography and pacing controls

**Status:** Design approved (prototype A confirmed 2026-07-17).
**Prototype:** `.context/formatting-prototype/index.html`.

## Problem

Font size, line spacing, and margins in the Formatting sheet are driven by `StepperRow` — a −/+ pill that requires many taps to sweep across the range. Auto-scroll wpm and readaloud cadence already use sliders, but they are plain `androidx.compose.material3.Slider` calls with no visual affordance for range, no live value bubble, and inconsistent styling versus the rest of the sheet. There is no shared slider primitive, so any future control has to reinvent one.

## Goal

Replace the three typography steppers with sliders, upgrade the two pacing sliders, and factor the whole thing into one shared composable used by all five controls.

## Non-goals

- No change to the underlying value ranges, defaults, or persistence (`AutoScrollSpeed`, `FormattingPreferences`, `Cadence*`).
- No change to the sheet's tab structure (Formatting / Display / Behavior — merged 2026-07-06 in PR #198).
- No new controls; this is a replacement, not an expansion.
- Font family chips and the Justify switch stay as-is.

## Design

### Shared composable

Add `UnifiedSlider` under `app/src/main/kotlin/com/riffle/app/feature/reader/UnifiedSlider.kt` (reader-feature-local; matches where `StepperRow` and `WpmSliderRow` live today). Signature:

```kotlin
@Composable
internal fun UnifiedSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,                 // Material3 semantics: N interior stops
    majorEvery: Float? = null,  // e.g. 100f for wpm, 0.5f for font, null = all major
    edgeLeft: @Composable () -> Unit,
    edgeRight: @Composable () -> Unit,
    bubbleLabel: (Float) -> String,
    modifier: Modifier = Modifier,
)
```

Renders a Material3 `Slider` on top of a custom track that draws:

- Base track: `surfaceVariant`, 6.dp, rounded.
- Filled portion left of the thumb: `primary`.
- Ticks: 2.dp × 8.dp `tick` colour at every step; 2.dp × 12.dp `primary` at every `majorEvery`. Skip minor ticks entirely if step count > 40 to avoid noise on wpm.
- Value bubble anchored above the thumb (small pill, `inverseSurface` / `inverseOnSurface`, 12sp semibold). Visible during hover, focus, and drag; fades out on release with a short delay.
- Edge slots: 28.dp wide, centred vertically, `onSurfaceVariant` tint, meant for icons.

Everything else (haptics, keyboard nudge, semantics) comes for free from the underlying M3 `Slider`.

### Row wrapper

`UnifiedSliderRow(title, caption, ...UnifiedSlider params...)` renders:

- Header row: `title` on the left, `caption` on the right (the current live-value line — kept because it's the discoverable label; the bubble is only visible on interaction).
- Slider beneath.
- Optional helper text below (used by wpm rows today).

### Call sites

Replace the following in `FormattingSection.kt`:

- **Font size** — range 0.5f..2.5f, step 0.1 (steps=19), `majorEvery=0.5f`. Edges: small serif "A" / large serif "A". Bubble `"${(v*100).roundToInt()}%"`, caption same as today.
- **Line spacing** — range 1.0f..2.0f, step 0.1 (steps=9), `majorEvery=0.2f`. Edges: three tight horizontal lines / three loose horizontal lines. Bubble `"${format1(v)}×"`, caption `"$label · ${v}×"` (label logic reused from today's `LineSpacingLabel`).
- **Margins** — range 0.2f..3.0f, step 0.1 (steps=27), `majorEvery=0.5f`. Edges: page-glyph-with-tall-text / page-glyph-with-narrow-text. Bubble/caption as today.

Replace `WpmSliderRow` in `PacingPanelBits.kt`:

- **Auto-scroll wpm** — range 80f..600f, step 10 (steps=51), `majorEvery=100f`. Edges: turtle-with-down-arrow / hare-with-up-arrow (Material `Icons.Outlined` where possible, else vector drawables). Bubble `"$v"`, caption `"$v wpm"`.
- **Readaloud cadence** — same range/step/majorEvery/edges/caption; feeds the existing cadence view-model instead of auto-scroll.

Delete `StepperRow` after the migration; audit any lingering callers first.

### Icons

Prefer Material `Icons.Outlined.*` (TextIncrease/TextDecrease, FormatLineSpacing, Speed, etc.) so we don't ship raster assets. Where Material doesn't have a clean fit — margins, and the "reading pace" turtle/hare in particular — add a vector drawable under `app/src/main/res/drawable/`.

## Interaction

- Drag: bubble appears immediately, tracks the thumb, disappears ~150 ms after release.
- Focus (keyboard/TB): bubble visible while focused; arrow keys nudge by `step` (M3 default).
- Snapping: value snaps to `step` grid on release (M3 default when `steps > 0`).
- Haptics: on tick crossing during drag (M3 `SliderColors` / `HapticFeedback.LongPress` at each snap). Guard behind `Build.VERSION.SDK_INT >= 26` — no-op on API 25.
- No live-preview panel inside the sheet — the sheet is already translucent over the reader, so the reader IS the preview. (The preview block in the prototype was a browser affordance only.)

## Testing

Two JVM tests in `app/src/test/kotlin/com/riffle/app/feature/reader/UnifiedSliderTest.kt`:

1. `formatsBubbleAtEveryStep` — for each of the five call-site configurations, assert `bubbleLabel(v)` at min / mid / max produces the expected string (guards the `100%`/`1.4×`/`250` wpm shape from regressing).
2. `majorTickPredicateIsCorrect` — extract the "is this value a major tick?" predicate to a top-level `internal fun isMajorTick(value, min, majorEvery, epsilon)`; verify no false positives for step-neighbours (0.1 float-arithmetic edge case).

Instrumentation coverage is unchanged — the existing `ReaderSettingsSheet` harness tests already drive font-size / line-spacing / margins by their semantics; those tests need to be updated to match the new semantic labels (Slider vs stepper Button roles), but the behavioural assertions carry over.

## Rollout

Single PR. No feature flag — this is a visual-only change to persisted values that already worked, and the current stepper and slider both produce identical wire values.

## Open questions

None; edge-icon designs are locked to the prototype (small-A/big-A, tight/loose lines, narrow/wide page, turtle/hare).
