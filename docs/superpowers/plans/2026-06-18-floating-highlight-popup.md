# Floating Highlight Action Popup — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the `ModalBottomSheet` highlight actions UI with a small floating `Popup` card that appears anchored to the highlighted text.

**Architecture:** The WebView-local `RectF` from both trigger paths (decoration tap + text selection) is mapped to window pixel coordinates using `View.getLocationOnScreen()`, stored in the ViewModel as `HighlightEditTarget(id, anchorRect)`, and consumed by a `PopupPositionProvider` that places the card above/below the anchor and clamps it to screen edges.

**Tech Stack:** Kotlin, Jetpack Compose (`Popup`, `PopupPositionProvider`, `Surface`), Readium 3.3 (`DecorableNavigator.OnActivatedEvent`, `SelectableNavigator.currentSelection()`), Hilt, JUnit 4.

## Global Constraints

- No mocking libraries available — unit-test pure functions by extracting the Android-touching layer into a thin wrapper; pass `viewLeft`/`viewTop` ints to the pure logic.
- `JAVA_HOME` must be set before running Gradle: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`
- Run JVM tests with `./gradlew :app:testDebugUnitTest` (or `./gradlew test`).
- Never call `./gradlew :app:connectedDebugAndroidTest` directly — use `make harness-test` for instrumented tests.
- The `HighlightSwatchRow` and `NoteEditorDialog` composables are unchanged — reuse them verbatim.

---

### Task 1: Coordinate helper + position provider (with tests)

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/reader/ReaderCoordinates.kt`
- Create: `app/src/test/kotlin/com/riffle/app/feature/reader/ReaderCoordinatesTest.kt`

**Interfaces:**
- Produces:
  - `internal fun RectF.toWindowIntRect(viewLeft: Int, viewTop: Int): IntRect` — pure arithmetic, testable
  - `internal fun RectF.toWindowIntRect(view: View): IntRect` — Android wrapper, reads offset via `getLocationOnScreen`
  - `internal class HighlightPopupPositionProvider(anchorRect: IntRect, margin: Int) : PopupPositionProvider`

---

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/riffle/app/feature/reader/ReaderCoordinatesTest.kt`:

```kotlin
package com.riffle.app.feature.reader

import android.graphics.RectF
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderCoordinatesTest {

    // ── toWindowIntRect ──────────────────────────────────────────────────────

    @Test
    fun `toWindowIntRect adds view offset to rect coordinates`() {
        val rect = RectF(10f, 20f, 50f, 60f)
        val result = rect.toWindowIntRect(viewLeft = 100, viewTop = 200)
        assertEquals(IntRect(left = 110, top = 220, right = 150, bottom = 260), result)
    }

    @Test
    fun `toWindowIntRect rounds float coordinates`() {
        val rect = RectF(10.4f, 20.6f, 50.3f, 60.7f)
        val result = rect.toWindowIntRect(viewLeft = 0, viewTop = 0)
        assertEquals(IntRect(left = 10, top = 21, right = 50, bottom = 61), result)
    }

    // ── HighlightPopupPositionProvider ───────────────────────────────────────

    private fun provider(anchorRect: IntRect, margin: Int = 8) =
        HighlightPopupPositionProvider(anchorRect, margin)

    private fun position(
        provider: HighlightPopupPositionProvider,
        windowSize: IntSize,
        popupSize: IntSize,
    ): IntOffset = provider.calculatePosition(
        anchorBounds = IntRect.Zero,
        windowSize = windowSize,
        layoutDirection = LayoutDirection.Ltr,
        popupContentSize = popupSize,
    )

    @Test
    fun `popup positioned above anchor when space available`() {
        // anchorTop=300, popupHeight=100, margin=8 → preferredTop = 300-100-8 = 192
        val anchor = IntRect(left = 100, top = 300, right = 200, bottom = 320)
        val result = position(provider(anchor), IntSize(400, 800), IntSize(280, 100))
        assertEquals(192, result.y)
    }

    @Test
    fun `popup flips below anchor when not enough space above`() {
        // anchorTop=80, popupHeight=100, margin=8 → preferredTop = -28 < 8 → flip: 100+8=108
        val anchor = IntRect(left = 100, top = 80, right = 200, bottom = 100)
        val result = position(provider(anchor), IntSize(400, 800), IntSize(280, 100))
        assertEquals(108, result.y)
    }

    @Test
    fun `popup centred on anchor horizontally`() {
        // anchorCentreX=150, popupWidth=100 → left = 150-50 = 100; within [8, 292] → 100
        val anchor = IntRect(left = 100, top = 300, right = 200, bottom = 320)
        val result = position(provider(anchor), IntSize(400, 800), IntSize(100, 100))
        assertEquals(100, result.x)
    }

    @Test
    fun `popup clamped to left margin when anchor is near left edge`() {
        // anchorCentreX=10, popupWidth=200 → centreX = 10-100 = -90, clamped to 8
        val anchor = IntRect(left = 5, top = 300, right = 15, bottom = 320)
        val result = position(provider(anchor, margin = 8), IntSize(400, 800), IntSize(200, 100))
        assertEquals(8, result.x)
    }

    @Test
    fun `popup clamped to right margin when anchor is near right edge`() {
        // anchorCentreX=395, popupWidth=200 → centreX=295, maxLeft=max(8,400-200-8)=192 → 192
        val anchor = IntRect(left = 390, top = 300, right = 400, bottom = 320)
        val result = position(provider(anchor, margin = 8), IntSize(400, 800), IntSize(200, 100))
        assertEquals(192, result.x)
    }

    @Test
    fun `popup max-left guard prevents crash on very narrow window`() {
        // Window width 100, popup width 150, margin 8 → maxLeft = max(8, 100-150-8)=max(8,-58)=8
        // centreX = 50-75 = -25, clamped to 8 (not crash from min>max)
        val anchor = IntRect(left = 40, top = 300, right = 60, bottom = 320)
        val result = position(provider(anchor, margin = 8), IntSize(100, 800), IntSize(150, 100))
        assertEquals(8, result.x)
    }
}
```

- [ ] **Step 2: Run tests — expect compile failure (classes don't exist yet)**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.reader.ReaderCoordinatesTest" 2>&1 | tail -20
```

Expected: compilation error — `toWindowIntRect` and `HighlightPopupPositionProvider` not found.

- [ ] **Step 3: Create `ReaderCoordinates.kt`**

```kotlin
package com.riffle.app.feature.reader

import android.graphics.RectF
import android.view.View
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import kotlin.math.roundToInt

internal fun RectF.toWindowIntRect(viewLeft: Int, viewTop: Int): IntRect = IntRect(
    left   = viewLeft + left.roundToInt(),
    top    = viewTop  + top.roundToInt(),
    right  = viewLeft + right.roundToInt(),
    bottom = viewTop  + bottom.roundToInt(),
)

internal fun RectF.toWindowIntRect(view: View): IntRect {
    val loc = IntArray(2)
    view.getLocationOnScreen(loc)
    return toWindowIntRect(loc[0], loc[1])
}

internal class HighlightPopupPositionProvider(
    private val anchorRect: IntRect,
    private val margin: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        // anchorBounds (Compose's composable anchor) is intentionally ignored — anchorRect is
        // already mapped to window coordinates from the WebView's coordinate space.
        val preferredTop = anchorRect.top - popupContentSize.height - margin
        val top = if (preferredTop >= margin) preferredTop else anchorRect.bottom + margin
        val centreX = anchorRect.center.x - popupContentSize.width / 2
        val maxLeft = maxOf(margin, windowSize.width - popupContentSize.width - margin)
        val left = centreX.coerceIn(margin, maxLeft)
        return IntOffset(left, top)
    }
}
```

- [ ] **Step 4: Run tests — expect all green**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.reader.ReaderCoordinatesTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL` with 7 tests passing.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/ReaderCoordinates.kt \
        app/src/test/kotlin/com/riffle/app/feature/reader/ReaderCoordinatesTest.kt
git commit -m "feat(annotations): coordinate helper + popup position provider"
```

---

### Task 2: ViewModel — `HighlightEditTarget` + updated signatures

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt`

**Interfaces:**
- Consumes: `IntRect` from `androidx.compose.ui.unit`
- Produces:
  - `data class HighlightEditTarget(val id: String, val anchorRect: IntRect)` (nested in `EpubReaderViewModel`)
  - `val highlightToEdit: StateFlow<HighlightEditTarget?>`
  - `fun openHighlightActions(id: String, anchorRect: IntRect)`
  - `fun createHighlight(selectionLocator: Locator, anchorRect: IntRect)`

---

- [ ] **Step 1: Add `IntRect` import to `EpubReaderViewModel.kt`**

Open `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt`. Add to the import block (keep alphabetical order):

```kotlin
import androidx.compose.ui.unit.IntRect
```

- [ ] **Step 2: Replace the `_highlightToEdit` block (lines ~330–335)**

Find this block:

```kotlin
private val _highlightToEdit = MutableStateFlow<String?>(null)
/** Id of a highlight whose actions sheet should be open (just-created or tapped), else null. */
val highlightToEdit: StateFlow<String?> = _highlightToEdit

fun openHighlightActions(id: String) { _highlightToEdit.value = id }
fun dismissHighlightActions() { _highlightToEdit.value = null }
```

Replace with:

```kotlin
data class HighlightEditTarget(val id: String, val anchorRect: IntRect)

private val _highlightToEdit = MutableStateFlow<HighlightEditTarget?>(null)
/** Highlight whose actions popup should be open (just-created or tapped), else null. */
val highlightToEdit: StateFlow<HighlightEditTarget?> = _highlightToEdit

fun openHighlightActions(id: String, anchorRect: IntRect) {
    _highlightToEdit.value = HighlightEditTarget(id, anchorRect)
}
fun dismissHighlightActions() { _highlightToEdit.value = null }
```

- [ ] **Step 3: Update `createHighlight` signature and its call to `openHighlightActions`**

Find the function signature (line ~1049):

```kotlin
fun createHighlight(selectionLocator: Locator) {
```

Replace with:

```kotlin
fun createHighlight(selectionLocator: Locator, anchorRect: IntRect) {
```

Find (line ~1086):

```kotlin
_highlightToEdit.value = created.id
```

Replace with:

```kotlin
openHighlightActions(created.id, anchorRect)
```

- [ ] **Step 4: Run unit tests to verify no breakage**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`. (The only compilation errors at this point are in `EpubReaderScreen.kt` where call sites pass the old signatures — those are fixed in Task 4.)

Note: if the build fails due to `EpubReaderScreen.kt` call sites, that is expected and is not a problem — it will be resolved in Task 4. Focus on the ViewModel tests passing.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt
git commit -m "feat(annotations): HighlightEditTarget + updated openHighlightActions/createHighlight"
```

---

### Task 3: Replace `HighlightActionsSheet` with `HighlightActionsPopup`

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/HighlightActionsSheet.kt`

**Interfaces:**
- Consumes: `HighlightPopupPositionProvider` and `toWindowIntRect` from Task 1; `IntRect` from ViewModel (Task 2)
- Produces: `@Composable fun HighlightActionsPopup(anchorRect: IntRect, selected, note, onPick, onDelete, onUpdateNote, onDismiss)`

The `HighlightSwatchRow` and `NoteEditorDialog` composables are unchanged — keep them exactly as-is.

---

- [ ] **Step 1: Replace the import block in `HighlightActionsSheet.kt`**

Replace the entire import section at the top of the file (everything after `package com.riffle.app.feature.reader` up to the first `/**` comment) with:

```kotlin
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.riffle.core.domain.HighlightColor
```

- [ ] **Step 2: Replace `HighlightActionsSheet` with `HighlightActionsPopup`**

Find and delete the entire `HighlightActionsSheet` composable (lines ~91–169):

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HighlightActionsSheet(
    selected: HighlightColor?,
    note: String?,
    onPick: (HighlightColor) -> Unit,
    onDelete: () -> Unit,
    onUpdateNote: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var noteEditorOpen by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, dragHandle = null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HighlightSwatchRow(selected = selected, onPick = onPick)
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete highlight",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { noteEditorOpen = true }
                .padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (note != null) "Note" else "Add note",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (note != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }

        Spacer(Modifier.height(8.dp))
    }

    if (noteEditorOpen) {
        NoteEditorDialog(
            initialNote = note ?: "",
            onConfirm = { text ->
                onUpdateNote(text.takeIf { it.isNotBlank() })
                noteEditorOpen = false
            },
            onDismiss = { noteEditorOpen = false },
        )
    }
}
```

Replace with:

```kotlin
@Composable
fun HighlightActionsPopup(
    anchorRect: IntRect,
    selected: HighlightColor?,
    note: String?,
    onPick: (HighlightColor) -> Unit,
    onDelete: () -> Unit,
    onUpdateNote: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var noteEditorOpen by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val margin = with(density) { 8.dp.roundToPx() }
    val provider = remember(anchorRect) { HighlightPopupPositionProvider(anchorRect, margin) }

    Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            shadowElevation = 4.dp,
            tonalElevation = 0.dp,
        ) {
            Column(modifier = Modifier.width(280.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HighlightSwatchRow(selected = selected, onPick = onPick)
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete highlight",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onDismiss()
                            noteEditorOpen = true
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (note != null) "Note" else "Add note",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (note != null) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = note,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }

    if (noteEditorOpen) {
        NoteEditorDialog(
            initialNote = note ?: "",
            onConfirm = { text ->
                onUpdateNote(text.takeIf { it.isNotBlank() })
                noteEditorOpen = false
            },
            onDismiss = { noteEditorOpen = false },
        )
    }
}
```

- [ ] **Step 3: Commit (build will fail until Task 4 wires up the screen)**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/HighlightActionsSheet.kt
git commit -m "feat(annotations): replace HighlightActionsSheet with HighlightActionsPopup"
```

---

### Task 4: Wire up `EpubReaderScreen.kt`

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt`

**Interfaces:**
- Consumes: `HighlightEditTarget` (Task 2), `toWindowIntRect(view: View)` (Task 1), `HighlightActionsPopup` (Task 3)

This task makes the project compile and the feature work end-to-end.

---

- [ ] **Step 1: Update the top-level call site — `highlightToEdit` collect and `EpubNavigatorView` call**

In the outer composable (around line 255), `highlightToEdit` now has type `EpubReaderViewModel.HighlightEditTarget?` — no code change needed, the type is inferred.

At the `EpubNavigatorView` call site (around line 374–376), update two lines:

Find:
```kotlin
                        onHighlight = viewModel::createHighlight,
                        highlightToEdit = highlightToEdit,
                        onOpenHighlightActions = viewModel::openHighlightActions,
```

Replace with (no change — method references still compile because signatures now match):
```kotlin
                        onHighlight = viewModel::createHighlight,
                        highlightToEdit = highlightToEdit,
                        onOpenHighlightActions = viewModel::openHighlightActions,
```

No change needed here — Kotlin method references adapt automatically to the new signatures.

- [ ] **Step 2: Update `EpubNavigatorView` parameter declarations (around line 981–983)**

Find:
```kotlin
    onHighlight: (Locator) -> Unit,
    highlightToEdit: String?,
    onOpenHighlightActions: (String) -> Unit,
```

Replace with:
```kotlin
    onHighlight: (Locator, androidx.compose.ui.unit.IntRect) -> Unit,
    highlightToEdit: EpubReaderViewModel.HighlightEditTarget?,
    onOpenHighlightActions: (String, androidx.compose.ui.unit.IntRect) -> Unit,
```

- [ ] **Step 3: Update `rememberUpdatedState` for `onHighlight` and `onOpenHighlightActions` (around line 1022–1023)**

No change needed — `rememberUpdatedState` is generic and adapts to the new lambda types automatically.

- [ ] **Step 4: Update the highlight menu handler to pass the rect (around line 1096–1103)**

Find:
```kotlin
                    highlightMenuId -> {
                        val selectable = fragmentRef.value as? org.readium.r2.navigator.SelectableNavigator
                            ?: return false
                        coroutineScope.launch {
                            val selection = selectable.currentSelection() ?: return@launch
                            currentOnHighlight(selection.locator)
                            selectable.clearSelection()
                        }
                    }
```

Replace with:
```kotlin
                    highlightMenuId -> {
                        val selectable = fragmentRef.value as? org.readium.r2.navigator.SelectableNavigator
                            ?: return false
                        val container = containerRef.value ?: return false
                        coroutineScope.launch {
                            val selection = selectable.currentSelection() ?: return@launch
                            val rect = selection.rect.toWindowIntRect(container)
                            currentOnHighlight(selection.locator, rect)
                            selectable.clearSelection()
                        }
                    }
```

- [ ] **Step 5: Update the decoration tap listener to pass the rect (around line 1657–1660)**

Find:
```kotlin
            override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent): Boolean {
                if (event.group != "annotations") return false
                currentOnOpenHighlightActions(event.decoration.id)
                return true
            }
```

Replace with:
```kotlin
            override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent): Boolean {
                if (event.group != "annotations") return false
                val container = containerRef.value ?: return false
                val rect = event.rect.toWindowIntRect(container)
                currentOnOpenHighlightActions(event.decoration.id, rect)
                return true
            }
```

- [ ] **Step 6: Update the call site that renders the popup (around line 2150–2161)**

Find:
```kotlin
        val editId = highlightToEdit
        if (editId != null) {
            val current = highlightRenders.firstOrNull { it.id == editId }
            HighlightActionsSheet(
                selected = current?.let { HighlightColor.fromToken(it.color) },
                note = current?.note,
                onPick = { color -> onRecolorHighlight(editId, color) },
                onDelete = { onDeleteHighlight(editId) },
                onUpdateNote = { note -> onUpdateHighlightNote(editId, note) },
                onDismiss = onDismissHighlightActions,
            )
        }
```

Replace with:
```kotlin
        val editTarget = highlightToEdit
        if (editTarget != null) {
            val current = highlightRenders.firstOrNull { it.id == editTarget.id }
            HighlightActionsPopup(
                anchorRect = editTarget.anchorRect,
                selected = current?.let { HighlightColor.fromToken(it.color) },
                note = current?.note,
                onPick = { color -> onRecolorHighlight(editTarget.id, color) },
                onDelete = { onDeleteHighlight(editTarget.id) },
                onUpdateNote = { note -> onUpdateHighlightNote(editTarget.id, note) },
                onDismiss = onDismissHighlightActions,
            )
        }
```

- [ ] **Step 7: Build and verify all unit tests pass**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest 2>&1 | tail -30
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt
git commit -m "feat(annotations): wire floating highlight popup in EpubReaderScreen"
```

---

## Done

All four tasks complete. The project compiles, unit tests pass, and the floating popup replaces the bottom sheet.

**Manual verification checklist:**
- [ ] Tap existing highlight → popup appears near (above) the highlight text, never off-screen
- [ ] Select text → highlight → popup appears near the selection
- [ ] Tap a colour swatch → highlight recolours, popup stays open
- [ ] Tap delete → highlight removed, popup dismissed
- [ ] Tap "Add note" → popup dismisses, note dialog opens
- [ ] Tap outside popup → dismissed
- [ ] Highlight near top → popup appears below
- [ ] Highlight near left/right edge → popup clamped to edge
