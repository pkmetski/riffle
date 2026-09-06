# Scenario 08d: Scroll Boundary Navigation (pull-to-chapter-navigate)

**Android reference:** `ScrollBoundaryNavigationContainerTest.kt` (27 tests)  
**iOS status:** GAP — iOS reader is paginated-only; `NavigatorScrollBoundary.None` always returned  
**KMP source:** `app/src/main/kotlin/com/riffle/app/feature/reader/ScrollBoundaryNavigationContainer.kt`

---

## Context

`ScrollBoundaryNavigationContainer` is an Android `FrameLayout` subclass that intercepts
`MotionEvent` streams to detect:

- **Pull-to-navigate**: a slow upward/downward drag at the scroll boundary arms a navigation
  indicator and fires `onNavigateForward` / `onNavigateBackward` once past the threshold
  (160 dp by default).
- **Swipe suppression**: a fast horizontal swipe in paginated mode is forwarded to the Readium
  WebView (page-turn gesture) and its `ACTION_UP` is intercepted so the WebView doesn't also
  fire a chapter navigation.
- **Volume key navigation**: `KEYCODE_VOLUME_DOWN` / `UP` at the boundary fires chapter nav
  after a 1500 ms cooldown; mid-chapter the key fires the JS scroll JS instead.

---

## Scenarios (all deferred)

### 08d-A through 08d-K: Pull-past-boundary navigation (11 tests)
### 08d-L through 08d-R: Chapter-nav suppression on ACTION_UP (7 tests)
### 08d-S through 08d-Z: Volume key scroll (8 tests)
### 08d-AA: Touch event passthrough (1 test)

**Gap reason:** All 27 tests require an Android-specific `MotionEvent`-based gesture layer.
On iOS the equivalent gesture is a `UIScrollView` `UIScrollViewDelegate` event, but:

- iOS reader does not yet implement scroll mode (only paginated via Readium Swift's
  `EPUBNavigatorViewController`).
- `ReadiumSwiftNavigator.scrollBoundary()` always returns `NavigatorScrollBoundary.None`.
- There is no iOS `ScrollBoundaryNavigationContainer` equivalent.

**Acceptance when implemented:**
- iOS EPUB reader supports scroll (karaoke) mode via `EPUBNavigatorViewController`'s
  scroll layout mode.
- A `ScrollBoundaryGestureHandler` Swift class monitors `UIScrollView` content offset to
  detect boundary conditions and fires `onNavigateForward/Backward` callbacks.
- `ScrollBoundaryNavigationTests.swift` verifies the threshold, cooldown, and suppression
  behaviours via `UIScrollView` programmatic content offset changes.
- `NavigatorScrollBoundary.None` stub is replaced with real boundary emission.

**Work needed:**
1. Implement scroll-mode layout for iOS Readium navigator.
2. Implement `ScrollBoundaryGestureHandler` in Swift with equivalent threshold logic.
3. Wire boundary callbacks into KMP `VolumeNavigationController`.
4. Add `ScrollBoundaryNavigationTests.swift`.
5. Update this scenario doc.
