# Unified Slider Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace typography steppers with sliders and unify auto-scroll / cadence sliders under one `UnifiedSlider` composable, styled per prototype A.

**Architecture:** Add a `UnifiedSlider(...)` + `UnifiedSliderRow(...)` composable in the reader feature package. It draws a custom track with tick notches (minors every step, majors every `majorEvery`), an edge-icon slot on either side, and a value bubble anchored above the thumb, on top of an M3 `Slider` for interaction/a11y. Migrate call sites in `FormattingSection.kt` (font size / line spacing / margins) and `PacingPanelBits.kt::WpmSliderRow` (auto-scroll + cadence). Delete `StepperRow`.

**Tech Stack:** Kotlin, Jetpack Compose, Material3, JUnit 4.

## Global Constraints

- Value ranges and defaults are locked to the current values — do not shift them.
  - Font size: `0.5f..2.5f`, step `0.1f`, majors every `0.5f`.
  - Line spacing: `1.0f..2.0f`, step `0.1f`, majors every `0.2f`.
  - Margins: `0.2f..3.0f`, step `0.1f`, majors every `0.5f`.
  - WPM (auto-scroll + cadence): `AutoScrollSpeed.MIN_WPM..MAX_WPM` (80..600), step `AutoScrollSpeed.STEP_WPM` (10), majors every `100f`.
- All rounding to 1 dp goes through `Float.round1()` (already in `ReaderSettingsControls.kt:241`).
- WPM snapping stays inside `AutoScrollSpeed.of(...)`.
- Do not touch `FormattingPreferences` shape or `AutoScrollSpeed` constants.
- Use Material `Icons.Outlined.*` where possible; fall back to inline vector drawables only when needed.
- No feature flag. Single PR to `main`. PR title uses Conventional Commits (`feat(reader):` / `refactor(reader):`).

---

### Task 1: Add `UnifiedSlider` composable and label helpers

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/reader/UnifiedSlider.kt`
- Create: `app/src/test/kotlin/com/riffle/app/feature/reader/UnifiedSliderLabelsTest.kt`

**Interfaces produced:**
- `internal fun UnifiedSliderRow(title: String, caption: String, value: Float, onValueChange: (Float) -> Unit, valueRange: ClosedFloatingPointRange<Float>, steps: Int, majorEvery: Float?, edgeLeft: @Composable () -> Unit, edgeRight: @Composable () -> Unit, bubbleLabel: (Float) -> String, modifier: Modifier = Modifier)`
- `internal fun isMajorTick(value: Float, min: Float, majorEvery: Float, epsilon: Float = 1e-3f): Boolean`
- `internal fun fontSizeBubble(v: Float): String = "${(v * 100).roundToInt()}%"`
- `internal fun lineSpacingBubble(v: Float): String = "%.1f×".format(v)`
- `internal fun marginsBubble(v: Float): String = "%.1f×".format(v)`
- `internal fun wpmBubble(v: Float): String = "${v.roundToInt()}"`

- [ ] **Step 1: Write failing tests for label/predicate helpers**

Create `app/src/test/kotlin/com/riffle/app/feature/reader/UnifiedSliderLabelsTest.kt`:

```kotlin
package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedSliderLabelsTest {

    @Test fun fontSizeBubbleRoundsToPercent() {
        assertEquals("50%", fontSizeBubble(0.5f))
        assertEquals("100%", fontSizeBubble(1.0f))
        assertEquals("120%", fontSizeBubble(1.2f))
        assertEquals("250%", fontSizeBubble(2.5f))
    }

    @Test fun lineSpacingBubbleFormatsOneDecimal() {
        assertEquals("1.0×", lineSpacingBubble(1.0f))
        assertEquals("1.4×", lineSpacingBubble(1.4f))
        assertEquals("2.0×", lineSpacingBubble(2.0f))
    }

    @Test fun marginsBubbleFormatsOneDecimal() {
        assertEquals("0.2×", marginsBubble(0.2f))
        assertEquals("1.0×", marginsBubble(1.0f))
        assertEquals("3.0×", marginsBubble(3.0f))
    }

    @Test fun wpmBubbleShowsInteger() {
        assertEquals("80", wpmBubble(80f))
        assertEquals("250", wpmBubble(250.4f))
        assertEquals("600", wpmBubble(600f))
    }

    @Test fun isMajorTickTrueAtMajorSteps() {
        assertTrue(isMajorTick(value = 1.0f, min = 0.5f, majorEvery = 0.5f))
        assertTrue(isMajorTick(value = 2.5f, min = 0.5f, majorEvery = 0.5f))
        assertTrue(isMajorTick(value = 200f, min = 80f, majorEvery = 100f))
        assertTrue(isMajorTick(value = 500f, min = 80f, majorEvery = 100f))
    }

    @Test fun isMajorTickFalseAtMinorSteps() {
        assertFalse(isMajorTick(value = 0.6f, min = 0.5f, majorEvery = 0.5f))
        assertFalse(isMajorTick(value = 1.3f, min = 1.0f, majorEvery = 0.2f))
        assertFalse(isMajorTick(value = 190f, min = 80f, majorEvery = 100f))
    }

    @Test fun isMajorTickToleratesFloatDrift() {
        // 1.0 - 0.5 in Float lands at 0.5000001 or so on some JVMs; guard against
        // the (v - min) % majorEvery ~= 0 comparison silently flipping.
        val drifted = 0.5f + 0.1f + 0.1f + 0.1f + 0.1f + 0.1f  // ~= 1.0
        assertTrue(isMajorTick(drifted, min = 0.5f, majorEvery = 0.5f))
    }
}
```

- [ ] **Step 2: Run to verify FAIL**

```
JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home \
  ./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.reader.UnifiedSliderLabelsTest"
```

Expected: FAIL with unresolved references to the helper functions.

- [ ] **Step 3: Create `UnifiedSlider.kt` with helpers + composable**

Create `app/src/main/kotlin/com/riffle/app/feature/reader/UnifiedSlider.kt`:

```kotlin
package com.riffle.app.feature.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun fontSizeBubble(v: Float): String = "${(v * 100).roundToInt()}%"
internal fun lineSpacingBubble(v: Float): String = "%.1f×".format(v)
internal fun marginsBubble(v: Float): String = "%.1f×".format(v)
internal fun wpmBubble(v: Float): String = "${v.roundToInt()}"

/**
 * True when [value] sits on a major tick — i.e. `(value - min) / majorEvery` is (near-)integer.
 * Uses float-tolerant rounding because 0.1 arithmetic in Float drifts across ranges like 0.5..2.5.
 */
internal fun isMajorTick(
    value: Float,
    min: Float,
    majorEvery: Float,
    epsilon: Float = 1e-3f,
): Boolean {
    val k = (value - min) / majorEvery
    return abs(k - k.roundToInt()) < epsilon
}

/**
 * Unified typography/pacing slider row. See `docs/superpowers/specs/2026-07-17-formatting-unified-slider-design.md`.
 *
 * Draws:
 *  - Title (left) + live caption (right).
 *  - Edge icon slots (28.dp), a custom tick-notched track, a value bubble on the thumb.
 *  - An M3 [Slider] on top provides interaction, snapping, focus + a11y.
 */
@Composable
internal fun UnifiedSliderRow(
    title: String,
    caption: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    majorEvery: Float?,
    edgeLeft: @Composable () -> Unit,
    edgeRight: @Composable () -> Unit,
    bubbleLabel: (Float) -> String,
    modifier: Modifier = Modifier,
    contentDescription: String = title,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                caption,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Box(
                Modifier.size(28.dp),
                contentAlignment = Alignment.Center,
            ) { edgeLeft() }
            Spacer(Modifier.width(8.dp))
            SliderTrack(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                steps = steps,
                majorEvery = majorEvery,
                bubbleLabel = bubbleLabel,
                contentDescription = contentDescription,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(28.dp),
                contentAlignment = Alignment.Center,
            ) { edgeRight() }
        }
    }
}

@Composable
private fun SliderTrack(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    majorEvery: Float?,
    bubbleLabel: (Float) -> String,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val min = valueRange.start
    val max = valueRange.endInclusive
    val span = max - min
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val majorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
    val step = if (steps <= 0) span else span / (steps + 1)
    // Skip minor ticks when there are too many to read comfortably (WPM has 52 stops).
    val showMinors = steps <= 40
    var trackWidthPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    var interacting by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .height(56.dp)
            .onSizeChanged { trackWidthPx = it.width },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(20.dp)) {
            val h = size.height
            val cy = h / 2f
            val trackHeightPx = with(density) { 6.dp.toPx() }
            val corner = trackHeightPx / 2f
            // Base track.
            drawRoundRect(
                color = tickColor.copy(alpha = 0.3f),
                topLeft = Offset(0f, cy - trackHeightPx / 2f),
                size = Size(size.width, trackHeightPx),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner),
            )
            // Filled portion.
            val filledFrac = ((value - min) / span).coerceIn(0f, 1f)
            drawRoundRect(
                color = majorColor,
                topLeft = Offset(0f, cy - trackHeightPx / 2f),
                size = Size(size.width * filledFrac, trackHeightPx),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner),
            )
            // Ticks.
            if (steps > 0) {
                for (i in 0..(steps + 1)) {
                    val v = min + i * step
                    val isMajor = majorEvery != null && isMajorTick(v, min, majorEvery)
                    if (!showMinors && !isMajor) continue
                    val x = size.width * ((v - min) / span)
                    val tickH = if (isMajor) with(density) { 12.dp.toPx() } else with(density) { 8.dp.toPx() }
                    val tickW = with(density) { 2.dp.toPx() }
                    val color = if (isMajor) majorColor else tickColor
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(x - tickW / 2f, cy - tickH / 2f),
                        size = Size(tickW, tickH),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(1f, 1f),
                    )
                }
            }
        }

        // Bubble above the thumb — visible only during interaction.
        if (interacting && trackWidthPx > 0) {
            val frac = ((value - min) / span).coerceIn(0f, 1f)
            val thumbHalfPx = with(density) { 12.dp.toPx() }
            val usable = trackWidthPx - thumbHalfPx * 2f
            val xPx = thumbHalfPx + frac * usable
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 28.dp),
                contentAlignment = Alignment.BottomStart,
            ) {
                Box(
                    modifier = Modifier
                        .graphicsLayer {
                            translationX = xPx - 24f
                            compositingStrategy = CompositingStrategy.Offscreen
                        },
                ) {
                    Bubble(bubbleLabel(value))
                }
            }
        }

        Slider(
            value = value,
            onValueChange = { new ->
                interacting = true
                onValueChange(new)
            },
            onValueChangeFinished = { interacting = false },
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = Color.Transparent,
                inactiveTrackColor = Color.Transparent,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { this.contentDescription = contentDescription },
        )
    }
}

@Composable
private fun Bubble(text: String) {
    androidx.compose.material3.Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
        )
    }
}
```

- [ ] **Step 4: Run tests to verify PASS**

```
JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home \
  ./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.reader.UnifiedSliderLabelsTest"
```

Expected: 7 tests PASS.

- [ ] **Step 5: Commit**

```
git add app/src/main/kotlin/com/riffle/app/feature/reader/UnifiedSlider.kt \
        app/src/test/kotlin/com/riffle/app/feature/reader/UnifiedSliderLabelsTest.kt
git commit -m "feat(reader): add UnifiedSlider composable + label helpers"
```

---

### Task 2: Migrate FormattingSection to UnifiedSlider

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/FormattingSection.kt`

**Interfaces consumed:** `UnifiedSliderRow`, `fontSizeBubble`, `lineSpacingBubble`, `marginsBubble`, `lineSpacingWord`, `marginsWord`, `Float.round1()`.

- [ ] **Step 1: Rewrite the three stepper blocks as `UnifiedSliderRow` calls**

In `FormattingSection.kt`, replace the "Font size", "Line spacing", and "Margins" blocks. The full replacement composable body:

```kotlin
Column {
    if (capabilities.supportsTextTypography || capabilities.supportsFontFamily) {
        Text("Text", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(8.dp))
    }

    if (capabilities.supportsTextTypography) {
        UnifiedSliderRow(
            title = "Font size",
            caption = "%.0f%%".format(prefs.fontSize * 100),
            value = prefs.fontSize,
            onValueChange = { onPrefsChange(prefs.copy(fontSize = it.round1())) },
            valueRange = 0.5f..2.5f,
            steps = 19, // (2.5 - 0.5) / 0.1 - 1
            majorEvery = 0.5f,
            edgeLeft = { Text("A", style = MaterialTheme.typography.labelMedium) },
            edgeRight = { Text("A", style = MaterialTheme.typography.titleLarge) },
            bubbleLabel = ::fontSizeBubble,
            contentDescription = "Font size",
        )
        Spacer(Modifier.height(16.dp))
    }

    if (capabilities.supportsFontFamily) {
        Text("Font", style = MaterialTheme.typography.labelMedium)
        val genericFonts = listOf(ReaderFontFamily.Original, ReaderFontFamily.Serif, ReaderFontFamily.SansSerif, ReaderFontFamily.Monospace)
        val bundledFonts = listOf(ReaderFontFamily.Literata, ReaderFontFamily.Merriweather, ReaderFontFamily.OpenDyslexic)
        FontChipRow(genericFonts, prefs.fontFamily) { onPrefsChange(prefs.copy(fontFamily = it)) }
        Spacer(Modifier.height(4.dp))
        FontChipRow(bundledFonts, prefs.fontFamily) { onPrefsChange(prefs.copy(fontFamily = it)) }
        Spacer(Modifier.height(12.dp))
    }

    if (capabilities.supportsTextTypography) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Justify text", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = prefs.justifyText, onCheckedChange = { onPrefsChange(prefs.copy(justifyText = it)) })
        }
        Spacer(Modifier.height(16.dp))

        UnifiedSliderRow(
            title = "Line spacing",
            caption = "${lineSpacingWord(prefs.lineSpacing)} · %.1f×".format(prefs.lineSpacing),
            value = prefs.lineSpacing,
            onValueChange = { onPrefsChange(prefs.copy(lineSpacing = it.round1())) },
            valueRange = 1.0f..2.0f,
            steps = 9,
            majorEvery = 0.2f,
            edgeLeft = { TightLinesIcon() },
            edgeRight = { LooseLinesIcon() },
            bubbleLabel = ::lineSpacingBubble,
            contentDescription = "Line spacing",
        )
        Spacer(Modifier.height(20.dp))
    }

    if (capabilities.supportsTextTypography || capabilities.supportsFontFamily) {
        Text("Page", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(8.dp))
    }
    UnifiedSliderRow(
        title = "Margins",
        caption = "${marginsWord(prefs.margins)} · %.1f×".format(prefs.margins),
        value = prefs.margins,
        onValueChange = { onPrefsChange(prefs.copy(margins = it.round1())) },
        valueRange = 0.2f..3.0f,
        steps = 27,
        majorEvery = 0.5f,
        edgeLeft = { NarrowMarginsIcon() },
        edgeRight = { WideMarginsIcon() },
        bubbleLabel = ::marginsBubble,
        contentDescription = "Margins",
    )
}
```

- [ ] **Step 2: Add the four inline vector edge-icon composables at the bottom of `FormattingSection.kt`**

```kotlin
@Composable
private fun TightLinesIcon() {
    val c = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(18.dp)) {
        val stroke = size.width * 0.10f
        val pad = size.width * 0.15f
        val w = size.width - pad * 2
        listOf(0.35f, 0.50f, 0.65f).forEach { y ->
            drawRect(color = c, topLeft = Offset(pad, size.height * y - stroke / 2), size = Size(w, stroke))
        }
    }
}

@Composable
private fun LooseLinesIcon() {
    val c = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(18.dp)) {
        val stroke = size.width * 0.10f
        val pad = size.width * 0.15f
        val w = size.width - pad * 2
        listOf(0.20f, 0.50f, 0.80f).forEach { y ->
            drawRect(color = c, topLeft = Offset(pad, size.height * y - stroke / 2), size = Size(w, stroke))
        }
    }
}

@Composable
private fun NarrowMarginsIcon() {
    val c = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(18.dp)) {
        val stroke = size.width * 0.08f
        drawRect(color = c, topLeft = Offset(0f, 0f), size = size, style = Stroke(width = stroke))
        val innerPad = size.width * 0.15f
        listOf(0.35f, 0.55f, 0.75f).forEach { y ->
            drawRect(
                color = c,
                topLeft = Offset(innerPad, size.height * y - stroke),
                size = Size(size.width - innerPad * 2, stroke * 1.5f),
            )
        }
    }
}

@Composable
private fun WideMarginsIcon() {
    val c = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(18.dp)) {
        val stroke = size.width * 0.08f
        drawRect(color = c, topLeft = Offset(0f, 0f), size = size, style = Stroke(width = stroke))
        val innerPad = size.width * 0.30f
        listOf(0.40f, 0.60f).forEach { y ->
            drawRect(
                color = c,
                topLeft = Offset(innerPad, size.height * y - stroke),
                size = Size(size.width - innerPad * 2, stroke * 1.5f),
            )
        }
    }
}
```

Add the imports `import androidx.compose.foundation.Canvas`, `import androidx.compose.ui.geometry.Offset`, `import androidx.compose.ui.geometry.Size`, `import androidx.compose.ui.graphics.drawscope.Stroke`, `import androidx.compose.foundation.layout.size`.

- [ ] **Step 3: Run compile + tests**

```
JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest
```

Expected: green.

- [ ] **Step 4: Commit**

```
git add app/src/main/kotlin/com/riffle/app/feature/reader/FormattingSection.kt
git commit -m "refactor(reader): switch typography controls to UnifiedSlider"
```

---

### Task 3: Migrate `WpmSliderRow` to UnifiedSlider

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/settings/panels/PacingPanelBits.kt`

- [ ] **Step 1: Rewrite `WpmSliderRow` on top of `UnifiedSliderRow`**

Full replacement (keep the existing `HighlightColorRow` untouched):

```kotlin
package com.riffle.app.feature.settings.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.riffle.app.feature.reader.UnifiedSliderRow
import com.riffle.app.feature.reader.wpmBubble
import com.riffle.core.domain.HighlightColor
import com.riffle.core.domain.autoscroll.AutoScrollSpeed

@Composable
internal fun WpmSliderRow(
    label: String,
    helper: String,
    wpm: Int,
    onWpmChange: (Int) -> Unit,
) {
    Column {
        UnifiedSliderRow(
            title = label,
            caption = "$wpm wpm",
            value = wpm.toFloat(),
            onValueChange = { onWpmChange(AutoScrollSpeed.of(it.toInt()).wpm) },
            valueRange = AutoScrollSpeed.MIN_WPM.toFloat()..AutoScrollSpeed.MAX_WPM.toFloat(),
            steps = (AutoScrollSpeed.MAX_WPM - AutoScrollSpeed.MIN_WPM) / AutoScrollSpeed.STEP_WPM - 1,
            majorEvery = 100f,
            edgeLeft = { TurtleIcon() },
            edgeRight = { HareIcon() },
            bubbleLabel = ::wpmBubble,
            contentDescription = "$label speed",
        )
        Text(
            text = helper,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
    }
}

@Composable
private fun TurtleIcon() {
    val c = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(20.dp)) {
        val stroke = size.width * 0.10f
        // A very simple "slow" glyph: a circle with a small notch — reads as a dial pointing left.
        drawCircle(color = c, radius = size.minDimension / 2f - stroke / 2f, style = Stroke(width = stroke))
        drawRect(
            color = c,
            topLeft = Offset(size.width * 0.20f, size.height / 2f - stroke / 2f),
            size = Size(size.width * 0.30f, stroke),
        )
    }
}

@Composable
private fun HareIcon() {
    val c = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(20.dp)) {
        val stroke = size.width * 0.10f
        drawCircle(color = c, radius = size.minDimension / 2f - stroke / 2f, style = Stroke(width = stroke))
        drawRect(
            color = c,
            topLeft = Offset(size.width * 0.50f, size.height / 2f - stroke / 2f),
            size = Size(size.width * 0.30f, stroke),
        )
    }
}

/**
 * Colour-swatch picker for Cadence's highlight — mirrors the Readaloud picker to keep the visual
 * pattern identical (issue #403: "Independent from Readaloud. Same palette."). Selected swatch
 * carries a contrast ring + a check glyph so the pick is unambiguous in screenshots.
 */
@Composable
internal fun HighlightColorRow(
    selected: HighlightColor,
    onSelectedChange: (HighlightColor) -> Unit,
) {
    ListItem(
        headlineContent = { Text("Highlight color") },
        supportingContent = { Text("Independent from Readaloud. Same palette.") },
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HighlightColor.entries.forEach { color ->
                    val isSelected = selected == color
                    val swatchColor = Color(color.argb.toLong() and 0xFFFFFFFFL)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable { onSelectedChange(color) }
                            .then(
                                if (isSelected)
                                    Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                else Modifier,
                            )
                            .padding(4.dp)
                            .clip(CircleShape)
                            .background(swatchColor)
                            .semantics {
                                contentDescription = color.name.lowercase()
                                    .replaceFirstChar { it.uppercase() } +
                                    " highlight" + if (isSelected) ", selected" else ""
                            },
                    ) {
                        if (isSelected) {
                            Icon(imageVector = Icons.Default.Check, contentDescription = null)
                        }
                    }
                }
            }
        },
    )
}
```

- [ ] **Step 2: Run compile + tests**

```
JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest
```

Expected: green.

- [ ] **Step 3: Commit**

```
git add app/src/main/kotlin/com/riffle/app/feature/settings/panels/PacingPanelBits.kt
git commit -m "refactor(settings): route WpmSliderRow through UnifiedSlider"
```

---

### Task 4: Drop `StepperRow`

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/ReaderSettingsControls.kt`

- [ ] **Step 1: Confirm no remaining callers**

```
grep -rn "StepperRow\b" app/ core/ 2>/dev/null
```

Expected: only the definition itself.

- [ ] **Step 2: Delete the `StepperRow` composable (lines ~58-92) and its now-unused imports**

Remove `IconButton`, `Surface` (if unused elsewhere in the file — verify first with the file's other functions), and any other imports left dangling.

- [ ] **Step 3: Compile + full app test suite**

```
JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest
```

Expected: green.

- [ ] **Step 4: Full-project test run**

```
JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home ./gradlew test
```

Expected: no new failures beyond the pre-existing ones in memory (`reference_migration_tests_api25_preexisting_fails`, `reference_flaky_replaceall_emission_tests`, `reference_autofollowjs_paginated_snap_preexisting_fail`).

- [ ] **Step 5: Commit**

```
git add app/src/main/kotlin/com/riffle/app/feature/reader/ReaderSettingsControls.kt
git commit -m "refactor(reader): remove obsolete StepperRow"
```
