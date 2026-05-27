# Fully Immersive Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the blank status-bar-height strip visible in immersive mode on physical devices, and show the status bar (clock, battery) when the user taps to exit immersive mode.

**Architecture:** Two independent fixes. (1) The Readium navigator fragments receive Android window insets through the native View dispatch system; on physical devices the status-bar inset stays non-zero even after `hide()`, causing Readium to apply it as top padding. Consuming all insets at the `AndroidView` root stops this. (2) `ImmersiveModeState.toggle()` currently only shows the TopAppBar on tap-exit; it must also call `controller.show(statusBars())` and clear `systemBarsHidden` so the status bar is visible and auto-dismiss is disabled.

**Tech Stack:** Kotlin, Jetpack Compose, AndroidX Core (`ViewCompat`, `WindowInsetsCompat`), Readium Kotlin SDK.

---

### Task 1: Consume window insets at the EPUB AndroidView root

Readium's `EpubNavigatorFragment` (and the WebViews it manages) receives Android window insets via the native View dispatch tree. On physical devices the status-bar inset remains non-zero after `controller.hide()`, causing the WebViews to apply a top padding equal to the status-bar height — the blank strip. Consuming all insets at the `ScrollBoundaryNavigationContainer` stops dispatch before it reaches Readium.

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt`

- [ ] **Step 1: Add missing imports**

In `EpubReaderScreen.kt`, add two imports after the existing `android.view.View` import line:

```kotlin
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
```

- [ ] **Step 2: Consume insets at the AndroidView root**

In `EpubReaderScreen.kt`, locate the `AndroidView` factory (around line 380). Change the `ScrollBoundaryNavigationContainer` construction to consume all insets:

```kotlin
AndroidView(
    factory = { ctx ->
        ScrollBoundaryNavigationContainer(ctx).apply {
            // Compose handles all inset-based padding (navigationBarsPadding on the outer
            // Box, TopAppBarDefaults.windowInsets on the floating TopAppBar). Consuming
            // insets here prevents Readium's WebViews from applying status-bar padding,
            // which on physical devices remains non-zero even after controller.hide().
            ViewCompat.setOnApplyWindowInsetsListener(this) { _, _ ->
                WindowInsetsCompat.CONSUMED
            }
            val fragmentContainer = FragmentContainerView(ctx).apply { id = View.generateViewId() }
            addView(fragmentContainer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }.also { containerRef.value = it }
    },
```

- [ ] **Step 3: Build and run harness tests**

```bash
make harness-test
```

Expected: all existing EPUB harness tests pass. In particular, the immersive-mode toggle test (tap to reveal TopAppBar, tap again to hide) must still pass.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt
git commit -m "fix(reader): consume window insets at EPUB AndroidView root to prevent blank strip"
```

---

### Task 2: Consume window insets at the PDF AndroidView root

Same root cause as Task 1, but `PdfReaderScreen` uses a bare `FragmentContainerView` as the `AndroidView` root instead of `ScrollBoundaryNavigationContainer`.

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/PdfReaderScreen.kt`

- [ ] **Step 1: Add missing imports**

In `PdfReaderScreen.kt`, add two imports after the existing `android.view.View` import line:

```kotlin
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
```

- [ ] **Step 2: Consume insets at the AndroidView root**

In `PdfReaderScreen.kt`, locate the `AndroidView` factory (around line 217). Change the `FragmentContainerView` construction:

```kotlin
AndroidView(
    factory = { ctx ->
        FragmentContainerView(ctx).apply {
            id = View.generateViewId()
            // Compose handles all inset-based padding. Consuming insets here prevents
            // Readium's PDF views from applying status-bar padding on physical devices.
            ViewCompat.setOnApplyWindowInsetsListener(this) { _, _ ->
                WindowInsetsCompat.CONSUMED
            }
        }
    },
```

- [ ] **Step 3: Build and run harness tests**

```bash
make harness-test
```

Expected: all existing PDF harness tests pass.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/PdfReaderScreen.kt
git commit -m "fix(reader): consume window insets at PDF AndroidView root to prevent blank strip"
```

---

### Task 3: Show status bar when exiting immersive mode

When the user taps to exit immersive mode, `toggle()` must restore the status bar (clock, battery) alongside the TopAppBar. The navigation bar stays hidden to avoid reflowing the paginated EPUB layout. Clearing `systemBarsHidden` disables auto-dismiss of the TopAppBar on page turns — the user must tap explicitly to re-enter immersive.

See ADR 0015 for the full rationale.

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/ImmersiveModeState.kt`

- [ ] **Step 1: Modify `toggle()` in `ImmersiveModeState`**

Replace the existing `toggle()` function:

```kotlin
// Does NOT call controller.show() when revealing the AppBar — showing the nav bar
// changes the WebView's visible height and reflows paginated EPUB content.
fun toggle() {
    if (isImmersive) {
        lastToggleMs = SystemClock.elapsedRealtime()
        isImmersive = false
    } else {
        hide()
    }
}
```

With:

```kotlin
// Shows the status bar but not the navigation bar — showing the nav bar changes the
// WebView's visible height and reflows paginated EPUB content. Clearing systemBarsHidden
// disables auto-dismiss on page turns; the user must tap to re-enter immersive.
// See ADR 0015.
fun toggle() {
    if (isImmersive) {
        lastToggleMs = SystemClock.elapsedRealtime()
        systemBarsHidden = false
        isImmersive = false
        controller.show(WindowInsetsCompat.Type.statusBars())
    } else {
        hide()
    }
}
```

- [ ] **Step 2: Build and run harness tests**

```bash
make harness-test
```

Expected: all EPUB and PDF harness tests pass. The immersive toggle test must still confirm: tap shows TopAppBar, tap again hides it.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/ImmersiveModeState.kt
git commit -m "feat(reader): show status bar when exiting immersive mode"
```

---

### Task 4: Update stale comments in reader screens

The comment on the content `Box` in both reader screens says "status bar insets are omitted because in immersive mode the status bar is hidden". That's now slightly stale — we're actively consuming them at the AndroidView root, not just omitting them.

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/PdfReaderScreen.kt`

- [ ] **Step 1: Update comment in `EpubReaderScreen.kt`**

Find (around line 128):
```kotlin
        // navigationBarsPadding only — status bar insets are omitted because in immersive
        // mode the status bar is hidden, and the floating TopAppBar carries its own
        // TopAppBarDefaults.windowInsets when the user taps to reveal it.
```

Replace with:
```kotlin
        // navigationBarsPadding only — status bar insets are consumed at the AndroidView
        // root (see ViewCompat.setOnApplyWindowInsetsListener in EpubNavigatorView) so
        // they never reach Readium's WebViews. The floating TopAppBar carries its own
        // TopAppBarDefaults.windowInsets to position itself below the status bar when visible.
```

- [ ] **Step 2: Update comment in `PdfReaderScreen.kt`**

Find (around line 106):
```kotlin
        // navigationBarsPadding only — status bar insets are omitted because in immersive
        // mode the status bar is hidden, and the floating TopAppBar carries its own
        // TopAppBarDefaults.windowInsets when the user taps to reveal it.
```

Replace with:
```kotlin
        // navigationBarsPadding only — status bar insets are consumed at the AndroidView
        // root (see ViewCompat.setOnApplyWindowInsetsListener in the PDF AndroidView factory)
        // so they never reach Readium's PDF views. The floating TopAppBar carries its own
        // TopAppBarDefaults.windowInsets to position itself below the status bar when visible.
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt \
        app/src/main/kotlin/com/riffle/app/feature/reader/PdfReaderScreen.kt
git commit -m "docs(reader): update stale inset comments after immersive mode fix"
```

---

## Self-review

**Spec coverage:**
- Blank strip in immersive mode → Tasks 1 & 2 (consume insets at AndroidView root for EPUB and PDF)
- Show status bar on tap exit → Task 3 (toggle() change)
- Auto-dismiss disabled → Task 3 (systemBarsHidden = false)
- Nav bar stays hidden → Task 3 (only `statusBars()` shown, not `systemBars()`)
- Comments updated → Task 4

**Placeholder scan:** No TBDs, no vague steps. All code shown in full.

**Type consistency:** `WindowInsetsCompat.Type.statusBars()` used in Task 3 matches the existing usage pattern in `ImmersiveModeState.kt` (`WindowInsetsCompat.Type.systemBars()`).

**Note on verification:** The blank strip fix (Tasks 1 & 2) requires physical device verification — the harness tests run on an emulator where the inset correctly zeros out after `hide()`. Manual testing on the physical device is required to confirm the strip is gone in immersive mode and the status bar appears on tap.
